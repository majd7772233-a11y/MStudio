package dev.mutwakil.androidide.cloudbuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBuildPolicyTest {
    @Test fun detectsAppModuleTask() {
        assertEquals(":app:assembleDebug", CloudBuildPolicy.detectTask(setOf(
            "gradlew", "settings.gradle.kts", "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml"
        )))
    }

    @Test fun detectsNamedAndroidModule() {
        assertEquals(":mobile:assembleDebug", CloudBuildPolicy.detectTask(setOf(
            "gradlew", "settings.gradle", "mobile/build.gradle",
            "mobile/src/main/AndroidManifest.xml"
        )))
    }

    @Test fun rejectsProjectsWithoutWrapperOrSettings() {
        assertEquals(null, CloudBuildPolicy.detectTask(setOf("app/build.gradle", "settings.gradle.kts")))
        assertEquals(null, CloudBuildPolicy.detectTask(setOf("gradlew", "app/build.gradle")))
    }

    @Test fun taskAllowlistBlocksShellInjection() {
        assertTrue(CloudBuildPolicy.isSafeGradleTask(":app:assembleDebug"))
        assertTrue(CloudBuildPolicy.isSafeGradleTask("assembleRelease"))
        assertFalse(CloudBuildPolicy.isSafeGradleTask(":app:assembleDebug;cat ~/.ssh/id_rsa"))
        assertFalse(CloudBuildPolicy.isSafeGradleTask(":app:assembleDebug\nmalicious"))
        assertFalse(CloudBuildPolicy.isSafeGradleTask("help"))
    }

    @Test fun workflowIsManualAndDoesNotPassPersonalToken() {
        val yaml = CloudBuildPolicy.workflow(":app:assembleDebug", "17")
        assertTrue(yaml.contains("workflow_dispatch:"))
        assertFalse(yaml.contains("on: push"))
        assertTrue(yaml.contains("contents: read"))
        assertTrue(yaml.contains("persist-credentials: false"))
        assertTrue(yaml.contains("timeout-minutes: 45"))
        assertTrue(yaml.contains("actions/upload-artifact@v4"))
        assertFalse(yaml.contains("GH_TOKEN"))
        assertFalse(yaml.contains("secrets."))
    }

    @Test fun workflowRejectsUnsupportedJdkAndTask() {
        assertFalse(runCatching { CloudBuildPolicy.workflow(":app:assembleDebug;echo x", "17") }.isSuccess)
        assertFalse(runCatching { CloudBuildPolicy.workflow(":app:assembleDebug", "8") }.isSuccess)
        assertNotNull(CloudBuildPolicy.workflow("assembleDebug", "21"))
    }
}
