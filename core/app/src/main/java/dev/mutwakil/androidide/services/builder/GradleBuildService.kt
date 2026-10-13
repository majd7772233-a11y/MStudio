/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.mutwakil.androidide.services.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import com.blankj.utilcode.util.ResourceUtils
import com.blankj.utilcode.util.ZipUtils
import dev.mutwakil.androidide.BuildConfig
import dev.mutwakil.androidide.R.*
import dev.mutwakil.androidide.app.BaseApplication
import dev.mutwakil.androidide.lookup.Lookup
import dev.mutwakil.androidide.managers.ToolsManager
import dev.mutwakil.androidide.preferences.internal.BuildPreferences
import dev.mutwakil.androidide.preferences.internal.DevOpsPreferences
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import dev.mutwakil.androidide.projects.builder.BuildService
import dev.mutwakil.androidide.resources.R
import dev.mutwakil.androidide.services.ToolingServerNotStartedException
import dev.mutwakil.androidide.services.builder.ToolingServerRunner.OnServerStartListener
import dev.mutwakil.androidide.tasks.ifCancelledOrInterrupted
import dev.mutwakil.androidide.tasks.runOnUiThread
import dev.mutwakil.androidide.tooling.api.ForwardingToolingApiClient
import dev.mutwakil.androidide.tooling.api.IToolingApiClient
import dev.mutwakil.androidide.tooling.api.IToolingApiServer
import dev.mutwakil.androidide.tooling.api.LogSenderConfig.PROPERTY_LOGSENDER_ENABLED
import dev.mutwakil.androidide.tooling.api.messages.InitializeProjectParams
import dev.mutwakil.androidide.tooling.api.messages.LogMessageParams
import dev.mutwakil.androidide.tooling.api.messages.TaskExecutionMessage
import dev.mutwakil.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import dev.mutwakil.androidide.tooling.api.messages.result.BuildInfo
import dev.mutwakil.androidide.tooling.api.messages.result.BuildResult
import dev.mutwakil.androidide.tooling.api.messages.result.GradleWrapperCheckResult
import dev.mutwakil.androidide.tooling.api.messages.result.InitializeResult
import dev.mutwakil.androidide.tooling.api.messages.result.TaskExecutionResult
import dev.mutwakil.androidide.tooling.api.models.ToolingServerMetadata
import dev.mutwakil.androidide.tooling.events.ProgressEvent
import dev.mutwakil.androidide.utils.Environment
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * A foreground service that handles interaction with the Gradle Tooling API.
 *
 * @author Akash Yadav
 */
class GradleBuildService : Service(), BuildService, IToolingApiClient,
  ToolingServerRunner.Observer {

  private var mBinder: GradleServiceBinder? = null
  private var isToolingServerStarted = false
  override var isBuildInProgress = false
    private set

  /**
   * We do not provide direct access to GradleBuildService instance to the Tooling API launcher as
   * it may cause memory leaks. Instead, we create another client object which forwards all calls to
   * us. So, when the service is destroyed, we release the reference to the service from this
   * client.
   */
  private var _toolingApiClient: ForwardingToolingApiClient? = null
  private var toolingServerRunner: ToolingServerRunner? = null
  private var outputReaderJob: Job? = null
  private var notificationManager: NotificationManager? = null
  private var server: IToolingApiServer? = null
  private var eventListener: EventListener? = null
  private val pendingServerRequests = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

  private val buildServiceScope = CoroutineScope(
    Dispatchers.Default + CoroutineName("GradleBuildService"))

  private val isGradleWrapperAvailable: Boolean
    get() {
      val projectManager = ProjectManagerImpl.getInstance()
      val projectDir = projectManager.projectDirPath
      if (TextUtils.isEmpty(projectDir)) {
        return false
      }

      val projectRoot = Objects.requireNonNull(projectManager.projectDir)
      if (!projectRoot.exists()) {
        return false
      }

      val gradlew = File(projectRoot, "gradlew")
      val gradleWrapperJar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
      val gradleWrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")

      if (!gradlew.isFile || gradlew.length() == 0L ||
        !gradleWrapperJar.isFile || gradleWrapperJar.length() == 0L ||
        !gradleWrapperProps.isFile || !gradleWrapperProps.canRead() ||
        gradleWrapperProps.length() == 0L
      ) {
        return false
      }

      val hasDistributionUrl = try {
        gradleWrapperProps.useLines { lines ->
          lines.any { line ->
            val entry = line.trim()
            entry.startsWith("distributionUrl=") &&
              entry.substringAfter('=').trim().isNotEmpty()
          }
        }
      } catch (error: IOException) {
        log.warn("Unable to read Gradle wrapper properties", error)
        false
      }
      if (!hasDistributionUrl) {
        return false
      }

      return try {
        java.util.zip.ZipFile(gradleWrapperJar).use { zip ->
          zip.entries().hasMoreElements()
        }
      } catch (error: IOException) {
        log.warn("Gradle wrapper JAR is not a readable ZIP archive: {}", gradleWrapperJar, error)
        false
      }
    }

  companion object {

    private val log = LoggerFactory.getLogger(GradleBuildService::class.java)
    private val NOTIFICATION_ID = R.string.app_name
    private val SERVER_System_err = LoggerFactory.getLogger("ToolingApiErrorStream")
  }

  override fun onCreate() {
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    showNotification(getString(R.string.build_status_idle), false)
    Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, this)
  }

  override fun isToolingServerStarted(): Boolean {
    return isToolingServerStarted && server != null
  }

  private fun showNotification(message: String,
    @Suppress("SameParameterValue") isProgress: Boolean) {
    log.info("Showing notification to user...")
    createNotificationChannels()
    startForeground(NOTIFICATION_ID, buildNotification(message, isProgress))
  }

  private fun createNotificationChannels() {
    val buildNotificationChannel = NotificationChannel(
      BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE,
      getString(string.title_gradle_service_notification_channel),
      NotificationManager.IMPORTANCE_LOW)
    NotificationManagerCompat.from(this)
      .createNotificationChannel(buildNotificationChannel)
  }

  private fun buildNotification(message: String, isProgress: Boolean): Notification {
    val ticker = getString(R.string.title_gradle_service_notification_ticker)
    val title = getString(R.string.title_gradle_service_notification)
    val launch = packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
    val intent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = Notification.Builder(this, BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE)
      .setSmallIcon(R.drawable.ic_launcher_notification).setTicker(ticker)
      .setWhen(System.currentTimeMillis()).setContentTitle(title).setContentText(message)
      .setContentIntent(intent)

    // Checking whether to add a ProgressBar to the notification
    if (isProgress) {
      // Add ProgressBar to Notification
      builder.setProgress(100, 0, true)
    }
    return builder.build()
  }

  override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
    // No point in restarting the service if it really gets killed.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    mBinder?.release()
    mBinder = null

    log.info("Service is being destroyed. Dismissing the shown notification...")
    notificationManager!!.cancel(NOTIFICATION_ID)

    val lookup = Lookup.getDefault()
    lookup.unregister(BuildService.KEY_BUILD_SERVICE)

    server?.also { server ->
      try {
        log.info("Shutting down Tooling API server...")
        // send the shutdown request but do not wait for the server to respond
        // the service should not block the onDestroy call in order to avoid timeouts
        // the tooling server must release resources and exit automatically
        server.shutdown().get(1, TimeUnit.SECONDS)
      } catch (e: Throwable) {
        log.error("Failed to shutdown Tooling API server", e)
      }
    }

    log.debug("Cancelling tooling server runner...")
    toolingServerRunner?.release()
    toolingServerRunner = null

    _toolingApiClient?.client = null
    _toolingApiClient = null

    log.debug("Cancelling tooling server output reader job...")
    outputReaderJob?.cancel()
    outputReaderJob = null

    isToolingServerStarted = false
  }

  override fun onBind(intent: Intent): IBinder? {
    if (mBinder == null) {
      mBinder = GradleServiceBinder(this)
    }
    return mBinder
  }

  override fun onListenerStarted(
    server: IToolingApiServer,
    errorStream: InputStream
  ) {
    startServerOutputReader(errorStream)
    this.server = server
    isToolingServerStarted = true
  }

  override fun onServerExited(exitCode: Int) {
    log.warn("Tooling API process terminated with exit code: {}", exitCode)
    markToolingServerUnavailable(IllegalStateException("Tooling API process exited with code $exitCode"))
  }

  override fun onServerStartFailed(error: Throwable) {
    log.error("Tooling API server failed before becoming ready", error)
    markToolingServerUnavailable(IllegalStateException("Unable to start the Tooling API server", error))
  }

  private fun markToolingServerUnavailable(cause: Throwable) {
    server = null
    isToolingServerStarted = false
    isBuildInProgress = false
    pendingServerRequests.forEach { request ->
      request.completeExceptionally(cause)
    }
    pendingServerRequests.clear()
    outputReaderJob?.cancel()
    outputReaderJob = null
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  private fun <T> trackServerRequest(request: CompletableFuture<T>): CompletableFuture<T> {
    pendingServerRequests.add(request)
    request.whenComplete { _, _ -> pendingServerRequests.remove(request) }
    // Close the race where the process dies immediately before the request is registered.
    if (!isToolingServerStarted()) {
      request.completeExceptionally(IllegalStateException("Tooling API server is not running"))
    }
    return request
  }

  override fun getClient(): IToolingApiClient {
    if (_toolingApiClient == null) {
      _toolingApiClient = ForwardingToolingApiClient(this)
    }
    return _toolingApiClient!!
  }

  override fun logMessage(params: LogMessageParams) {
    val logger = LoggerFactory.getLogger(params.tag)
    when (params.level) {
      'D' -> logger.debug(params.message)
      'W' -> logger.warn(params.message)
      'E' -> logger.error(params.message)
      'I' -> logger.info(params.message)

      else -> logger.trace(params.message)
    }
  }

  override fun logOutput(line: String) {
    eventListener?.onOutput(line)
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    updateNotification(getString(R.string.build_status_in_progress), true)
    eventListener?.prepareBuild(buildInfo)
  }

  override fun onBuildSuccessful(result: BuildResult) {
    updateNotification(getString(R.string.build_status_sucess), false)
    dispatchBuildResult(result,true)
    eventListener?.onBuildSuccessful(result.tasks)
  }

  override fun onBuildFailed(result: BuildResult) {
    updateNotification(getString(R.string.build_status_failed), false)
    dispatchBuildResult(result,false)
    eventListener?.onBuildFailed(result.tasks)
  }

  private fun dispatchBuildResult(result: BuildResult,isSuccess: Boolean){
    buildServiceScope.launch {
      ProjectManagerImpl.getInstance().indexingServiceManager.onBuildCompleted()
    }
  }

  override fun onProgressEvent(event: ProgressEvent) {
    eventListener?.onProgressEvent(event)
  }

  override fun getBuildArguments(): CompletableFuture<List<String>> {
    val extraArgs = ArrayList<String>()
    extraArgs.add("--init-script")
    extraArgs.add(Environment.INIT_SCRIPT.absolutePath)

    // Override AAPT2 binary
    // The one downloaded from Maven is not built for Android
    extraArgs.add("-Pandroid.aapt2FromMavenOverride=" + Environment.AAPT2.absolutePath)
    extraArgs.add("-P${PROPERTY_LOGSENDER_ENABLED}=${DevOpsPreferences.logsenderEnabled}")
    if (BuildPreferences.isStacktraceEnabled) {
      extraArgs.add("--stacktrace")
    }
    if (BuildPreferences.isInfoEnabled) {
      extraArgs.add("--info")
    }
    if (BuildPreferences.isDebugEnabled) {
      extraArgs.add("--debug")
    }
    if (BuildPreferences.isScanEnabled) {
      extraArgs.add("--scan")
    }
    if (BuildPreferences.isWarningModeAllEnabled) {
      extraArgs.add("--warning-mode")
      extraArgs.add("all")
    }
    if (BuildPreferences.isBuildCacheEnabled) {
      extraArgs.add("--build-cache")
    }
    if (BuildPreferences.isOfflineEnabled) {
      extraArgs.add("--offline")
    }
    return CompletableFuture.completedFuture(extraArgs)
  }

  override fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult> {
    return if (isGradleWrapperAvailable) CompletableFuture.completedFuture(
      GradleWrapperCheckResult(true)) else installWrapper()
  }

  internal fun setServerListener(listener: OnServerStartListener?) {
    if (toolingServerRunner != null) {
      toolingServerRunner!!.setListener(listener)
    }
  }

  private fun installWrapper(): CompletableFuture<GradleWrapperCheckResult> {
    eventListener?.also { eventListener ->
      eventListener.onOutput("-------------------- NOTE --------------------")
      eventListener.onOutput(getString(R.string.msg_installing_gradlew))
      eventListener.onOutput("----------------------------------------------")
    }
    return CompletableFuture.supplyAsync { doInstallWrapper() }
  }

  private fun doInstallWrapper(): GradleWrapperCheckResult {
    val extracted = File(Environment.TMP_DIR, "gradle-wrapper.zip")
    if (!ResourceUtils.copyFileFromAssets(ToolsManager.getCommonAsset("gradle-wrapper.zip"),
        extracted.absolutePath)
    ) {
      log.error("Unable to extract gradle-wrapper.zip from IDE resources.")
      return GradleWrapperCheckResult(false)
    }

    val stagingDir = File(Environment.TMP_DIR, "gradle-wrapper-staging")
    try {
      val projectDir = ProjectManagerImpl.getInstance().projectDir
      if (!stagingDir.exists() && !stagingDir.mkdirs()) {
        throw IOException("Unable to create Gradle wrapper staging directory: $stagingDir")
      }
      stagingDir.listFiles()?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
      }

      val extractedFiles = ZipUtils.unzipFile(extracted, stagingDir)
      if (extractedFiles.isNullOrEmpty()) {
        throw IOException("Gradle wrapper archive extracted no files")
      }

      // Repair only missing or invalid components. Never overwrite a valid wrapper file,
      // especially the project's own distributionUrl or executable script.
      val relativePaths = listOf(
        "gradlew",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties"
      )
      for (relativePath in relativePaths) {
        val destination = File(projectDir, relativePath)
        if (isValidWrapperComponent(destination, relativePath)) continue

        val source = File(stagingDir, relativePath)
        if (!isValidWrapperComponent(source, relativePath)) {
          throw IOException("Bundled Gradle wrapper component is missing or invalid: $relativePath")
        }
        destination.parentFile?.let { parent ->
          if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create Gradle wrapper directory: $parent")
          }
        }
        val temporary = File(destination.parentFile, destination.name + ".repair.tmp")
        try {
          source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
          }
          if (temporary.length() == 0L) {
            throw IOException("Repaired Gradle wrapper component is empty: $relativePath")
          }
          if (destination.exists() && !destination.delete()) {
            throw IOException("Unable to replace invalid Gradle wrapper component: $destination")
          }
          if (!temporary.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            temporary.delete()
          }
          if (relativePath == "gradlew") destination.setExecutable(true, false)
        } finally {
          if (temporary.exists()) temporary.delete()
        }
      }

      if (isGradleWrapperAvailable) return GradleWrapperCheckResult(true)
      log.error("Gradle wrapper repair did not produce a complete, readable wrapper in {}", projectDir)
    } catch (e: IOException) {
      log.error("An error occurred while repairing Gradle wrapper files", e)
    } finally {
      stagingDir.deleteRecursively()
    }
    return GradleWrapperCheckResult(false)
  }

  private fun isValidWrapperComponent(file: File, relativePath: String): Boolean {
    if (!file.isFile || file.length() == 0L || !file.canRead()) return false
    return when (relativePath) {
      "gradle/wrapper/gradle-wrapper.jar" -> try {
        java.util.zip.ZipFile(file).use { it.entries().hasMoreElements() }
      } catch (error: IOException) {
        false
      }
      "gradle/wrapper/gradle-wrapper.properties" -> try {
        file.useLines { lines ->
          lines.any { line ->
            val entry = line.trim()
            entry.startsWith("distributionUrl=") &&
              entry.substringAfter('=').trim().isNotEmpty()
          }
        }
      } catch (error: IOException) {
        false
      }
      else -> true
    }
  }

  private fun updateNotification(message: String, isProgress: Boolean) {
    runOnUiThread { doUpdateNotification(message, isProgress) }
  }

  private fun doUpdateNotification(message: String, isProgress: Boolean) {
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,
      buildNotification(message, isProgress))
  }

  override fun metadata(): CompletableFuture<ToolingServerMetadata> {
    checkServerStarted()
    val currentServer = server ?: throw ToolingServerNotStartedException()
    return trackServerRequest(currentServer.metadata())
  }

  override fun initializeProject(
    params: InitializeProjectParams): CompletableFuture<InitializeResult> {
    checkServerStarted()
    Objects.requireNonNull(params)
    val currentServer = server ?: throw ToolingServerNotStartedException()
    return performBuildTasks(trackServerRequest(currentServer.initialize(params)))
  }

  override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
    checkServerStarted()
    val currentServer = server ?: throw ToolingServerNotStartedException()
    return performBuildTasks(trackServerRequest(currentServer.executeTasks(message)))
  }

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
    checkServerStarted()
    val currentServer = server ?: throw ToolingServerNotStartedException()
    return trackServerRequest(currentServer.cancelCurrentBuild())
  }

  private fun <T> performBuildTasks(future: CompletableFuture<T>): CompletableFuture<T> {
    return CompletableFuture.runAsync(this::onPrepareBuildRequest).handleAsync { _, _ ->
      try {
        return@handleAsync future.get()
      } catch (e: Throwable) {
        throw CompletionException(e)
      }
    }.handle(this::markBuildAsFinished)
  }

  private fun onPrepareBuildRequest() {
    checkServerStarted()
    ensureTmpdir()
    if (isBuildInProgress) {
      logBuildInProgress()
      throw BuildInProgressException()
    }
    isBuildInProgress = true
  }

  @Throws(ToolingServerNotStartedException::class)
  private fun checkServerStarted() {
    if (!isToolingServerStarted()) {
      throw ToolingServerNotStartedException()
    }
  }

  private fun ensureTmpdir() {
    Environment.mkdirIfNotExists(Environment.TMP_DIR)
  }

  private fun logBuildInProgress() {
    log.warn("A build is already in progress!")
  }

  @Suppress("UNUSED_PARAMETER")
  private fun <T> markBuildAsFinished(result: T, throwable: Throwable?): T {
    isBuildInProgress = false
    return result
  }

  internal fun startToolingServer(listener: OnServerStartListener?) {
    if (toolingServerRunner?.isStarted != true) {
      val envs = TermuxShellEnvironment().getEnvironment(this, false)
      toolingServerRunner = ToolingServerRunner(listener, this).also { it.startAsync(envs) }
      return
    }

    if (toolingServerRunner!!.isStarted && listener != null) {
      listener.onServerStarted(toolingServerRunner!!.pid!!)
    } else {
      setServerListener(listener)
    }
  }

  fun setEventListener(eventListener: EventListener?): GradleBuildService {
    if (eventListener == null) {
      this.eventListener = null
      return this
    }
    this.eventListener = wrap(eventListener)
    return this
  }

  private fun wrap(listener: EventListener?): EventListener? {
    return if (listener == null) {
      null
    } else object : EventListener {
      override fun prepareBuild(buildInfo: BuildInfo) {
        runOnUiThread { listener.prepareBuild(buildInfo) }
      }

      override fun onBuildSuccessful(tasks: List<String?>) {
        runOnUiThread { listener.onBuildSuccessful(tasks) }
      }

      override fun onProgressEvent(event: ProgressEvent) {
        runOnUiThread { listener.onProgressEvent(event) }
      }

      override fun onBuildFailed(tasks: List<String?>) {
        runOnUiThread { listener.onBuildFailed(tasks) }
      }

      override fun onOutput(line: String?) {
        runOnUiThread { listener.onOutput(line) }
      }
    }
  }

  private fun startServerOutputReader(input: InputStream) {
    if (outputReaderJob?.isActive == true) {
      return
    }

    outputReaderJob = buildServiceScope.launch(
      Dispatchers.IO + CoroutineName("ToolingServerErrorReader")) {
      val reader = input.bufferedReader()
      try {
        reader.forEachLine { line ->
          SERVER_System_err.error(line)
        }
      } catch (e: Throwable) {
        e.ifCancelledOrInterrupted(suppress = true) {
          // will be suppressed
          return@launch
        }

        // log the error and fail silently
        log.error("Failed to read tooling server output", e)
      }
    }
  }

  /**
   * Handles events received from a Gradle build.
   */
  interface EventListener {

    /**
     * Called just before a build is started.
     *
     * @param buildInfo The information about the build to be executed.
     * @see IToolingApiClient.prepareBuild
     */
    fun prepareBuild(buildInfo: BuildInfo)

    /**
     * Called when a build is successful.
     *
     * @param tasks The tasks that were run.
     * @see IToolingApiClient.onBuildSuccessful
     */
    fun onBuildSuccessful(tasks: List<String?>)

    /**
     * Called when a progress event is received from the Tooling API server.
     *
     * @param event The event model describing the event.
     */
    fun onProgressEvent(event: ProgressEvent)

    /**
     * Called when a build fails.
     *
     * @param tasks The tasks that were run.
     * @see IToolingApiClient.onBuildFailed
     */
    fun onBuildFailed(tasks: List<String?>)

    /**
     * Called when the output line is received.
     *
     * @param line The line of the build output.
     */
    fun onOutput(line: String?)
  }
}