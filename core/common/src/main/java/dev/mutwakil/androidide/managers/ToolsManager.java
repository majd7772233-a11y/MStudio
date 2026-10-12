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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarFile;

import kotlin.io.FilesKt;

public class ToolsManager {

  private static final Logger LOG = LoggerFactory.getLogger(ToolsManager.class);

  public static String COMMON_ASSET_DATA_DIR = "data/common";

  private static volatile InitResult lastInitResult =
      new InitResult(false, Collections.emptySet(),
          Collections.singletonMap("setup", "Runtime setup has not completed yet."));

  /** Immutable per-component setup status for onboarding and retry UI. */
  public static final class InitResult {
    private final boolean supportedAbi;
    private final Set<String> readyComponents;
    private final Map<String, String> failures;

    private InitResult(
        boolean supportedAbi, Set<String> readyComponents, Map<String, String> failures) {
      this.supportedAbi = supportedAbi;
      this.readyComponents = Collections.unmodifiableSet(new LinkedHashSet<>(readyComponents));
      this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public boolean isReady() {
      return supportedAbi && failures.isEmpty();
    }

    public boolean isSupportedAbi() {
      return supportedAbi;
    }

    public Set<String> getReadyComponents() {
      return readyComponents;
    }

    public Map<String, String> getFailures() {
      return failures;
    }
  }

  public static InitResult getLastInitResult() {
    return lastInitResult;
  }

  /** Keeps the original callback contract for existing callers. */
  public static void init(@NonNull BaseApplication app, Runnable onFinish) {
    initWithResult(app, result -> {
      if (onFinish != null) {
        onFinish.run();
      }
    });
  }

  /**
   * Initializes each runtime component independently and reports which parts are ready.
   * One failed extraction no longer hides the status of every later component.
   */
  public static void initWithResult(
      @NonNull BaseApplication app, Consumer<InitResult> onComplete) {
    if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
      Map<String, String> failures = new LinkedHashMap<>();
      failures.put("abi", "This device ABI is not supported by the bundled runtime.");
      InitResult result = new InitResult(false, Collections.emptySet(), failures);
      lastInitResult = result;
      LOG.error("Device not supported by the bundled runtime");
      if (onComplete != null) {
        onComplete.accept(result);
      }
      return;
    }

    CompletableFuture.supplyAsync(() -> {
      Set<String> ready = new LinkedHashSet<>();
      Map<String, String> failures = new LinkedHashMap<>();

      runComponent("jdk-distributions", ready, failures, () -> {
        IJdkDistributionProvider.getInstance().loadDistributions();
        if (IJdkDistributionProvider.getInstance().getInstalledDistributions().isEmpty()) {
          throw new IllegalStateException("No installed JDK distributions were discovered.");
        }
      });
      runComponent("managed-java", ready, failures, () -> {
        if (!Environment.JAVA.canExecute() && !Environment.JAVA.setExecutable(true)) {
          throw new IllegalStateException(
              "Managed Java executable permission could not be restored: " + Environment.JAVA);
        }
        if (!isUsableJava(Environment.JAVA)) {
          throw new IllegalStateException(
              "Managed Java is missing or failed its version check: " + Environment.JAVA);
        }
      });
      runComponent("projects-marker", ready, failures, ToolsManager::writeNoMediaFile);
      runComponent("gradle-plugin", ready, failures, ToolsManager::extractAndroidIdeGradlePlugin);
      runComponent("aapt2", ready, failures, ToolsManager::extractAapt2);
      runComponent("tooling-api", ready, failures, ToolsManager::extractToolingApi);
      runComponent("android-jar", ready, failures, ToolsManager::extractAndroidJar);
      runComponent("color-schemes", ready, failures, () -> extractColorScheme(app));
      runComponent("gradle-init-script", ready, failures, ToolsManager::writeInitScript);
      runComponent("kotlin-artifact-script", ready, failures, ToolsManager::extractDownloadKtScript);
      runComponent("runtime-cleanup", ready, failures, ToolsManager::deleteIdeenv);

      return new InitResult(true, ready, failures);
    }).whenComplete((result, error) -> {
      InitResult completed = result;
      if (error != null) {
        LOG.error("Unexpected error initializing runtime tools", error);
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put("setup", error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage());
        completed = new InitResult(true, Collections.emptySet(), failures);
      }

      lastInitResult = completed;
      if (onComplete != null) {
        try {
          onComplete.accept(completed);
        } catch (Throwable callbackError) {
          LOG.error("Runtime setup completion callback failed", callbackError);
        }
      }
    });
  }

  private static void runComponent(
      String name, Set<String> ready, Map<String, String> failures, Runnable action) {
    try {
      action.run();
      ready.add(name);
    } catch (Throwable error) {
      final String message = error.getMessage() == null
          ? error.getClass().getSimpleName() : error.getMessage();
      failures.put(name, message);
      LOG.error("Runtime component '{}' failed to initialize: {}", name, message, error);
    }
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

    // Recover a previous interrupted swap before cleaning any staging files.
    if (backup.exists()) {
      if (!destination.exists()) {
        movePath(backup, destination);
      } else if (!deleteRecursively(backup)) {
        throw new IOException("Unable to remove stale color-scheme backup: " + backup);
      }
    }
    if (!deleteRecursively(temporary)) {
      throw new IOException("Unable to remove stale color-scheme staging directory: " + temporary);
    }

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
    if (!isValidJar(Environment.ANDROID_JAR)) {
      copyAssetAtomically(getCommonAsset("android.jar"), Environment.ANDROID_JAR);
    }
    if (!isValidJar(Environment.ANDROID_JAR)) {
      throw new IllegalStateException(
          "Android API stub is missing or invalid after extraction: " + Environment.ANDROID_JAR);
    }
  }

  private static boolean isValidJar(File file) {
    if (!file.isFile() || file.length() == 0) {
      return false;
    }
    try (JarFile jar = new JarFile(file)) {
      return jar.entries().hasMoreElements();
    } catch (IOException error) {
      LOG.warn("Android API stub is not a valid JAR and will be repaired: {}", file, error);
      return false;
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

    if (!isUsableAapt2(Environment.AAPT2)) {
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

    if (!Environment.AAPT2.canExecute() && !Environment.AAPT2.setExecutable(true)) {
      throw new IllegalStateException(
          "Cannot set executable permissions to AAPT2 binary: " + Environment.AAPT2);
    }
    if (!isUsableAapt2(Environment.AAPT2)) {
      throw new IllegalStateException(
          "AAPT2 failed its version check after extraction: " + Environment.AAPT2);
    }
  }

  /** Confirms that the managed Java executable can actually start. */
  private static boolean isUsableJava(File java) {
    if (!java.isFile() || java.length() == 0 || !java.canExecute()) {
      return false;
    }

    final File parent = java.getParentFile();
    final File output = new File(parent, java.getName() + ".version.tmp");
    Process process = null;
    try {
      if (output.exists() && !output.delete()) {
        LOG.warn("Unable to remove previous Java version output: {}", output);
        return false;
      }

      process = new ProcessBuilder(java.getAbsolutePath(), "-version")
          .redirectErrorStream(true)
          .redirectOutput(output)
          .start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor();
        return false;
      }
      return process.exitValue() == 0 && output.isFile() && output.length() > 0;
    } catch (IOException error) {
      LOG.warn("Unable to execute managed Java version check: {}", java, error);
      return false;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while checking managed Java: {}", java, error);
      return false;
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      if (output.exists() && !output.delete()) {
        LOG.warn("Unable to remove Java version output: {}", output);
      }
    }
  }

  /** Runs the lightweight AAPT2 version command so non-empty corrupt binaries are repaired. */
  private static boolean isUsableAapt2(File binary) {
    if (!binary.isFile() || binary.length() == 0 || !binary.canExecute()) {
      return false;
    }

    final File parent = binary.getParentFile();
    final File output = new File(parent, binary.getName() + ".version.tmp");
    Process process = null;
    try {
      if (output.exists() && !output.delete()) {
        LOG.warn("Unable to remove previous AAPT2 version output: {}", output);
        return false;
      }

      process = new ProcessBuilder(binary.getAbsolutePath(), "version")
          .redirectErrorStream(true)
          .redirectOutput(output)
          .start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor();
        return false;
      }
      return process.exitValue() == 0 && output.isFile() && output.length() > 0;
    } catch (IOException error) {
      LOG.warn("Unable to execute AAPT2 version check: {}", binary, error);
      return false;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while checking AAPT2: {}", binary, error);
      return false;
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      if (output.exists() && !output.delete()) {
        LOG.warn("Unable to remove AAPT2 version output: {}", output);
      }
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
