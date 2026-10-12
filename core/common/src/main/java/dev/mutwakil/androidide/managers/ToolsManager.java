/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package dev.mutwakil.androidide.managers;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ResourceUtils;
import dev.mutwakil.androidide.app.BaseApplication;
import dev.mutwakil.androidide.app.configuration.IDEBuildConfigProvider;
import dev.mutwakil.androidide.app.configuration.IJdkDistributionProvider;
import dev.mutwakil.androidide.utils.Environment;

import org.jetbrains.annotations.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import kotlin.io.FilesKt;

public class ToolsManager {

  private static final Logger LOG = LoggerFactory.getLogger(ToolsManager.class);

  public static String COMMON_ASSET_DATA_DIR = "data/common";

  public static void init(@NonNull BaseApplication app, Runnable onFinish) {

    if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
      LOG.error("Device not supported");
      return;
    }

    CompletableFuture.runAsync(() -> {
      // Load installed JDK distributions
      IJdkDistributionProvider.getInstance().loadDistributions();

      writeNoMediaFile();
      extractAndroidIdeGradlePlugin();
      extractAapt2();
      extractToolingApi();
      extractAndroidJar();
      extractColorScheme(app);
      writeInitScript();
      extractDownloadKtScript();

      deleteIdeenv();
    }).whenComplete((__, error) -> {
      if (error != null) {
        LOG.error("Error extracting tools", error);
      }

      if (onFinish != null) {
        onFinish.run();
      }
    });
  }

  private static void extractAndroidIdeGradlePlugin() {
    copyAssetAtomically(
        getCommonAsset("androidide-gradle-plugin.jar"),
        Environment.ANDROIDIDE_GRADLE_PLUGIN_JAR);
  }
  
  private static void extractDownloadKtScript() {
    copyAssetAtomically(
        getCommonAsset("download-kotlin-artifacts.sh"),
        Environment.DOWNLOAD_KT_SCRIPT);
  }

  private static void extractColorScheme(final BaseApplication app) {
    final String assetRoot = "editor/schemes";
    final File destinationRoot = new File(Environment.ANDROIDIDE_UI, assetRoot);
    try {
      final String[] schemes = app.getAssets().list(assetRoot);
      if (schemes == null) {
        throw new IOException("Color-scheme asset directory is missing: " + assetRoot);
      }
      if (!destinationRoot.isDirectory()
          && !destinationRoot.mkdirs()
          && !destinationRoot.isDirectory()) {
        throw new IOException("Unable to create color-scheme directory: " + destinationRoot);
      }

      for (final String scheme : schemes) {
        final String assetPath = assetRoot + "/" + scheme;
        final File destination = new File(destinationRoot, scheme);
        final File currentVersion = new File(destination, "scheme.prop");
        if (currentVersion.isFile()
            && !shouldExtractScheme(app, destination, assetPath)) {
          continue;
        }

        copyAssetDirectoryAtomically(app, assetPath, destination);
      }
    } catch (IOException error) {
      throw new IllegalStateException("Failed to extract color schemes", error);
    }
  }

  /**
   * Stages a complete asset directory beside its destination and swaps it only after copying
   * succeeds. If the swap fails, the previous directory is restored whenever possible.
   */
  private static void copyAssetDirectoryAtomically(
      BaseApplication app, String assetPath, File destination) throws IOException {
    final File parent = destination.getParentFile();
    if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
      throw new IOException("Unable to create asset destination directory: " + parent);
    }

    final File temporary = new File(parent, destination.getName() + ".tmp");
    final File backup = new File(parent, destination.getName() + ".bak");
    deleteRecursively(temporary);
    deleteRecursively(backup);

    try {
      copyAssetTree(app, assetPath, temporary);
      if (!new File(temporary, "scheme.prop").isFile()) {
        throw new IOException("Color scheme is missing scheme.prop: " + assetPath);
      }

      final boolean hadDestination = destination.exists();
      if (hadDestination) {
        movePath(destination, backup);
      }

      try {
        movePath(temporary, destination);
      } catch (IOException swapError) {
        if (hadDestination && backup.exists() && !destination.exists()) {
          try {
            movePath(backup, destination);
          } catch (IOException restoreError) {
            swapError.addSuppressed(restoreError);
          }
        }
        throw swapError;
      }

      if (backup.exists() && !deleteRecursively(backup)) {
        LOG.warn("Unable to remove previous color-scheme backup: {}", backup);
      }
    } finally {
      if (temporary.exists() && !deleteRecursively(temporary)) {
        LOG.warn("Unable to remove temporary color-scheme directory: {}", temporary);
      }
    }
  }

  private static void copyAssetTree(BaseApplication app, String assetPath, File destination)
      throws IOException {
    final String[] children = app.getAssets().list(assetPath);
    if (children != null && children.length > 0) {
      if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
        throw new IOException("Unable to create extracted asset directory: " + destination);
      }
      for (String child : children) {
        copyAssetTree(app, assetPath + "/" + child, new File(destination, child));
      }
      return;
    }

    final File parent = destination.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
      throw new IOException("Unable to create extracted asset directory: " + parent);
    }
    try (InputStream input = app.getAssets().open(assetPath);
         FileOutputStream output = new FileOutputStream(destination)) {
      final byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        output.write(buffer, 0, count);
      }
      output.getFD().sync();
    }
    if (!destination.isFile() || destination.length() == 0) {
      throw new IOException("Extracted asset is empty: " + assetPath);
    }
  }

  private static void movePath(File source, File destination) throws IOException {
    try {
      Files.move(
          source.toPath(),
          destination.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(
          source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static boolean deleteRecursively(File file) {
    if (!file.exists()) {
      return true;
    }
    if (file.isDirectory()) {
      final File[] children = file.listFiles();
      if (children == null) {
        return false;
      }
      for (File child : children) {
        if (!deleteRecursively(child)) {
          return false;
        }
      }
    }
    return file.delete();
  }

  private static boolean shouldExtractScheme(final BaseApplication app, final File dir,
      final String path) throws IOException {

    final var schemePropFile = new File(dir, "scheme.prop");
    if (!schemePropFile.exists()) {
      return true;
    }

    final var files = app.getAssets().list(path);
    if (Arrays.stream(files).noneMatch("scheme.prop"::equals)) {
      // no scheme.prop file
      return true;
    }

    try {
      final var props = new Properties();
      Reader reader = new InputStreamReader(app.getAssets().open(path + "/scheme.prop"));
      props.load(reader);
      reader.close();

      final var version = Integer.parseInt(props.getProperty("scheme.version", "0"));
      if (version == 0) {
        return true;
      }

      props.clear();

      reader = new FileReader(schemePropFile);
      props.load(reader);
      reader.close();

      final var fileVersion = Integer.parseInt(props.getProperty("scheme.version", "0"));
      if (fileVersion < 0) {
        return true;
      }

      return version > fileVersion;
    } catch (Throwable err) {
      LOG.error("Failed to read color scheme version for scheme '{}'; scheduling repair", path, err);
      return true;
    }
  }

  private static void writeNoMediaFile() {
    final var noMedia = new File(BaseApplication.getBaseInstance().getProjectsDir(), ".nomedia");
    if (!noMedia.exists()) {
      try {
        if (!noMedia.createNewFile()) {
          LOG.error("Failed to create .nomedia file in projects directory");
        }
      } catch (IOException e) {
        LOG.error("Failed to create .nomedia file in projects directory");
      }
    }
  }

  private static void extractAndroidJar() {
    if (Environment.ANDROID_JAR.isDirectory()) {
      throw new IllegalStateException(
          "Android API stub path is a directory and cannot be repaired safely: "
              + Environment.ANDROID_JAR);
    }
    if (!Environment.ANDROID_JAR.isFile() || Environment.ANDROID_JAR.length() == 0) {
      copyAssetAtomically(getCommonAsset("android.jar"), Environment.ANDROID_JAR);
    }
    if (!Environment.ANDROID_JAR.isFile() || Environment.ANDROID_JAR.length() == 0) {
      throw new IllegalStateException(
          "Android API stub is missing or empty after extraction: " + Environment.ANDROID_JAR);
    }
  }

  private static void deleteIdeenv() {
    final var file = new File(Environment.BIN_DIR, "ideenv");
    if (file.exists() && !file.delete()) {
      LOG.warn("Unable to delete file: {}", file);
    }
  }

  @NonNull
  @Contract(pure = true)
  public static String getCommonAsset(String name) {
    return COMMON_ASSET_DATA_DIR + "/" + name;
  }

  private static void extractAapt2() {
    if (Environment.AAPT2.isDirectory()) {
      throw new IllegalStateException(
          "AAPT2 destination is a directory and cannot be repaired safely: " + Environment.AAPT2);
    }

    if (!Environment.AAPT2.isFile() || Environment.AAPT2.length() == 0) {
      final var context = BaseApplication.getBaseInstance();
      final var nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
      final var sourceAapt2 = new File(nativeLibraryDir, "libaapt2.so");
      if (!sourceAapt2.isFile() || sourceAapt2.length() == 0) {
        throw new IllegalStateException(
            "AAPT2 binary is missing or empty in the app's native library directory: "
                + sourceAapt2);
      }
      copyFileAtomically(sourceAapt2, Environment.AAPT2);
    }

    if (!Environment.AAPT2.isFile() || Environment.AAPT2.length() == 0) {
      throw new IllegalStateException("AAPT2 binary is missing or empty: " + Environment.AAPT2);
    }
    if (!Environment.AAPT2.canExecute() && !Environment.AAPT2.setExecutable(true)) {
      throw new IllegalStateException(
          "Cannot set executable permissions to AAPT2 binary: " + Environment.AAPT2);
    }
  }

  private static void copyFileAtomically(File source, File destination) {
    final File parent = destination.getParentFile();
    if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
      throw new IllegalStateException("Unable to create AAPT2 destination directory: " + parent);
    }

    final File temporary = new File(parent, destination.getName() + ".tmp");
    try {
      Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
      if (!temporary.isFile() || temporary.length() == 0) {
        throw new IOException("Copied AAPT2 binary is empty");
      }

      try {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to extract AAPT2 binary from " + source + " to " + destination, error);
    } finally {
      if (temporary.exists() && !temporary.delete()) {
        LOG.warn("Unable to remove temporary AAPT2 binary: {}", temporary);
      }
    }
  }

  private static void extractToolingApi() {
    copyAssetAtomically(
        getCommonAsset("tooling-api-all.jar"),
        Environment.TOOLING_API_JAR);
  }

  /**
   * Replaces an extracted asset only after its complete contents have been written.
   * A failed copy must not destroy a previously working runtime/tooling file.
   */
  private static void copyAssetAtomically(String assetPath, File destination) {
    final File parent = destination.getParentFile();
    if (parent == null && !destination.isAbsolute()) {
      throw new IllegalStateException("No parent directory for asset destination: " + destination);
    }
    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
      throw new IllegalStateException("Unable to create asset directory: " + parent);
    }

    final File temporary = new File(parent, destination.getName() + ".tmp");
    try {
      try (InputStream input = BaseApplication.getBaseInstance().getAssets().open(assetPath);
           FileOutputStream output = new FileOutputStream(temporary)) {
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          output.write(buffer, 0, count);
        }
        output.getFD().sync();
      }

      if (!temporary.isFile() || temporary.length() == 0) {
        throw new IOException("Extracted asset is empty: " + assetPath);
      }

      try {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to extract asset '" + assetPath + "' to " + destination, error);
    } finally {
      if (temporary.exists() && !temporary.delete()) {
        LOG.warn("Unable to remove temporary extracted asset: {}", temporary);
      }
    }
  }

  private static void writeInitScript() {
    final var initScript = Environment.INIT_SCRIPT;
    final var initScriptBak = new File(initScript.getParentFile(), initScript.getName() + ".bak");
    final var contents = readInitScript();

    FilesKt.writeText(initScriptBak, contents, StandardCharsets.UTF_8);
    if (!initScript.exists()) {
      FilesKt.writeText(initScript, contents, StandardCharsets.UTF_8);
    }
  }

  @NonNull
  private static String readInitScript() {
    return ResourceUtils.readAssets2String(getCommonAsset("androidide.init.gradle"));
  }

}
