package dev.mutwakil.androidide.tooling.impl.serial

import com.android.builder.model.v2.dsl.BuildType as AgpBuildType
import com.android.builder.model.v2.ide.AndroidArtifact as AgpAndroidArtifact
import com.android.builder.model.v2.ide.AndroidLibraryData as AgpAndroidLibraryData
import com.android.builder.model.v2.ide.ArtifactDependencies as AgpArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem as AgpGraphItem
import com.android.builder.model.v2.ide.JavaCompileOptions as AgpJavaCompileOptions
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.LibraryInfo as AgpLibraryInfo
import com.android.builder.model.v2.ide.LibraryType as AgpLibraryType
import com.android.builder.model.v2.ide.ProjectInfo as AgpProjectInfo
import com.android.builder.model.v2.ide.ProjectType as AgpProjectType
import com.android.builder.model.v2.ide.SourceProvider as AgpSourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer as AgpSourceSetContainer
import com.android.builder.model.v2.ide.UnresolvedDependency as AgpUnresolvedDependency
import com.android.builder.model.v2.ide.Variant as AgpVariant
import com.android.builder.model.v2.ide.ViewBindingOptions as AgpViewBindingOptions
import com.android.builder.model.v2.models.AndroidDsl as AgpAndroidDsl
import com.android.builder.model.v2.models.AndroidProject as AgpAndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject as AgpBasicAndroidProject
import com.android.builder.model.v2.models.VariantDependencies as AgpVariantDependencies
import com.android.builder.model.v2.models.Versions as AgpVersions
import dev.mutwakil.androidide.project.AndroidArtifact
import dev.mutwakil.androidide.project.AndroidLibraryData
import dev.mutwakil.androidide.project.AndroidModels
import dev.mutwakil.androidide.project.AndroidProject
import dev.mutwakil.androidide.project.AndroidVariant
import dev.mutwakil.androidide.project.ArtifactDependencies
import dev.mutwakil.androidide.project.GraphItem
import dev.mutwakil.androidide.project.JavaCompilerSettings
import dev.mutwakil.androidide.project.Library
import dev.mutwakil.androidide.project.LibraryInfo
import dev.mutwakil.androidide.project.ProjectInfo
import dev.mutwakil.androidide.project.SourceProvider
import dev.mutwakil.androidide.project.SourceSetContainer
import dev.mutwakil.androidide.project.UnresolvedDependency
import dev.mutwakil.androidide.project.VariantDependencies
import dev.mutwakil.androidide.project.ViewBindingOptions
import dev.mutwakil.androidide.utils.AndroidPluginVersion
import dev.mutwakil.androidide.utils.capitalizeString
import java.io.File

fun createAndroidProjectProtoModel(
    basicAndroidProject: AgpBasicAndroidProject,
    androidProject: AgpAndroidProject,
    androidDsl: AgpAndroidDsl,
    versions: AgpVersions,
    variantDependencies: AgpVariantDependencies,
    configuredVariantName: String?,
    classesJar: File,
) = AndroidProject(
    namespace = androidProject.namespace,
    projectType =
        when (basicAndroidProject.projectType) {
            AgpProjectType.APPLICATION -> AndroidModels.ProjectType.ApplicationProject
            AgpProjectType.LIBRARY -> AndroidModels.ProjectType.LibraryProject
            AgpProjectType.DYNAMIC_FEATURE -> AndroidModels.ProjectType.DynamicFeature
            AgpProjectType.TEST -> AndroidModels.ProjectType.Test
            AgpProjectType.FUSED_LIBRARY -> AndroidModels.ProjectType.FusedLibrary
        },
    mainSourceSet = basicAndroidProject.mainSourceSet?.asProtoModel(),
    javaCompilerSettings = androidProject.javaCompileOptions?.asProtoModel(),
    kotlinCompilerSettings = null, // TODO: Read kotlin compiler settings
    viewBindingOptions = androidProject.viewBindingOptions?.asProtoModel(),
    bootClassPathsList = basicAndroidProject.bootClasspath.map { file -> file.absolutePath },
    variantList =
        androidProject.variants.map {
            it.asProtoModel(
                basicAndroidProject,
                androidProject,
                androidDsl,
                versions,
            )
        },
    configuredVariantName = configuredVariantName,
    classesJarPath = classesJar.absolutePath,
    variantDependencies = variantDependencies.asProtoModel(),
)

fun AgpVariantDependencies.asProtoModel() =
    VariantDependencies(
        name = this.name,
        mainArtifact = this.mainArtifact.asProtoModel(),
        librariesMap = this.libraries.mapValues { entry -> entry.value.asProtoModel() },
        androidTestArtifact = this.androidTestArtifact?.asProtoModel(),
        unitTestArtifact = this.unitTestArtifact?.asProtoModel(),
        testFixturesArtifact = this.testFixturesArtifact?.asProtoModel(),
        deviceTestArtifactsMap = this.deviceTestArtifacts.mapValues { entry ->
            entry.value.asProtoModel()
        },
        hostTestArtifactsMap = this.hostTestArtifacts.mapValues { entry ->
            entry.value.asProtoModel()
        },
    )

fun AgpLibrary.asProtoModel() =
    Library(
        key = this.key,
        type =
            when (this.type) {
                AgpLibraryType.PROJECT -> AndroidModels.LibraryType.Project
                AgpLibraryType.ANDROID_LIBRARY -> AndroidModels.LibraryType.ExternalAndroidLibrary
                AgpLibraryType.JAVA_LIBRARY -> AndroidModels.LibraryType.ExternalJavaLibrary
                AgpLibraryType.RELOCATED -> AndroidModels.LibraryType.Relocated
                AgpLibraryType.NO_ARTIFACT_FILE -> AndroidModels.LibraryType.NoArtifactFile
            },
        projectInfo = this.projectInfo?.asProtoModel(),
        libraryInfo = this.libraryInfo?.asProtoModel(),
        androidLibraryData = this.androidLibraryData?.asProtoModel(),
        artifactPath = this.artifact?.absolutePath,
    )

fun AgpAndroidLibraryData.asProtoModel() =
    AndroidLibraryData(
        compileJarFilePathsList = this.compileJarFiles.map { it.absolutePath },
        manifestFilePath = this.manifest.absolutePath,
        resFolderPath = this.resFolder.absolutePath,
    )

fun AgpProjectInfo.asProtoModel() =
    ProjectInfo(
        buildId = this.buildId,
        projectPath = projectPath,
    )

fun AgpLibraryInfo.asProtoModel() =
    LibraryInfo(
        group = this.group,
        name = this.name,
        version = this.version,
    )

fun AgpArtifactDependencies.asProtoModel() =
    ArtifactDependencies(
        compileDependencyList = this.compileDependencies.map { it.asProtoModel() },
        unresolvedDependencyList = this.unresolvedDependencies.map { it.asProtoModel() },
    )

fun AgpGraphItem.asProtoModel(): AndroidModels.GraphItem =
    GraphItem(
        key = this.key,
        requestedCoordinates = this.requestedCoordinates,
        dependencyList = this.dependencies.map { it.asProtoModel() },
    )

fun AgpUnresolvedDependency.asProtoModel() =
    UnresolvedDependency(
        name = this.name,
        cause = this.cause,
    )

fun AgpSourceSetContainer.asProtoModel() = SourceSetContainer(sourceProvider = this.sourceProvider?.asProtoModel())

fun AgpSourceProvider.asProtoModel() =
    SourceProvider(
        javaDirsList = this.javaDirectories.map { dir -> dir.absolutePath },
        kotlinDirsList = this.kotlinDirectories.map { dir -> dir.absolutePath },
        resDirsList = this.resDirectories?.map { dir -> dir.absolutePath } ?: emptyList(),
    )

fun AgpJavaCompileOptions.asProtoModel() =
    JavaCompilerSettings(
        sourceCompatibility = this.sourceCompatibility,
        targetCompatibility = this.targetCompatibility,
    )

fun AgpViewBindingOptions.asProtoModel() =
    ViewBindingOptions(
        isEnabled = this.isEnabled,
    )

fun AgpVariant.asProtoModel(
    basicAndroidProject: BasicAndroidProject,
    androidProject: AndroidProject,
    androidDsl: AndroidDsl,
    versions: Versions,
) = AndroidVariant(
    name = this.name,
    mainArtifact =
        this.mainArtifact.asProtoModel(
            artifactName = "main",
            variantName = this.name,
            basicAndroidProject = basicAndroidProject,
            androidProject = androidProject,
            androidDsl = androidDsl,
            versions = versions,
        ),
)

fun AgpAndroidArtifact.asProtoModel(
    artifactName: String,
    variantName: String,
    basicAndroidProject: BasicAndroidProject,
    androidProject: AndroidProject,
    androidDsl: AndroidDsl,
    versions: Versions,
) = AndroidArtifact(
    name = artifactName,
    applicationId =
        computeApplicationId(
            variantName,
            basicAndroidProject,
            androidProject,
            androidDsl,
            versions,
        ),
    resGenTaskName = this.resGenTaskName,
    sourceGenTaskName = this.sourceGenTaskName,
    compileTaskName = this.compileTaskName,
    assembleTaskName = this.assembleTaskName,
    assembleTaskOutputListingFilePath = this.assembleTaskOutputListingFile?.absolutePath,
    generatedResourceFolderPathsList = this.generatedResourceFolders.map { it.absolutePath },
    generatedSourceFolderPathsList = this.generatedSourceFolders.map { it.absolutePath },
    maxSdkVersion = this.maxSdkVersion,
    minSdkVersion = this.minSdkVersion.apiLevel,
    classJarPathsList =
        this.classesFolders.mapNotNull { classesFolder ->
            classesFolder.absolutePath.takeIf { path ->
                path.endsWith(
                    ".jar",
                )
            }
        },
    targetSdkVersionOverride = this.targetSdkVersionOverride?.apiLevel ?: 1,
)

private fun AgpAndroidArtifact.computeApplicationId(
    variantName: String,
    basicAndroidProject: BasicAndroidProject,
    androidProject: AndroidProject,
    androidDsl: AndroidDsl,
    versions: Versions,
): String? {
    val minAgpForAppId = AndroidPluginVersion(7, 4, 0)
    return if (minAgpForAppId <= AndroidPluginVersion.parse(versions.agp)) {
        applicationId
    } else {
        computeApplicationIdLegacy(variantName, basicAndroidProject, androidProject, androidDsl)
    }
}

// Adapted from the following :
// https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/core/dsl/impl/ComponentDslInfoImpl.kt;drc=6a5551bdea55c0c991f1ccf1e3f8f6f3d2cd2cb7;l=107
// https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/core/dsl/impl/VariantDslInfoImpl.kt;drc=d44f5b98cd5530eceb230e0d151ad96c4277f78d;l=109

private fun computeApplicationIdLegacy(
    variantName: String,
    basicAndroidProject: BasicAndroidProject,
    androidProject: AndroidProject,
    androidDsl: AndroidDsl,
): String {
    val basicVariant = basicAndroidProject.variants.firstOrNull { it.name == variantName }
    val buildType =
        basicVariant?.buildType?.let { buildTypeName ->
            androidDsl.buildTypes.find { buildType -> buildType.name == buildTypeName }
        }!!

    val appIdFromFlavor =
        if (basicAndroidProject.projectType == ProjectType.APPLICATION) {
            androidDsl.productFlavors
                .find { flavor ->
                    "${flavor.name}${buildType.name.capitalizeString()}" == variantName
                }?.applicationId
        } else {
            androidDsl.defaultConfig.applicationId
        }

    return if (appIdFromFlavor == null) {
        // No appId value set from DSL; use the namespace value from the DSL.
        "${androidProject.namespace}${
            computeApplicationIdSuffix(
                variantName,
                buildType,
                basicAndroidProject,
                androidDsl,
            )
        }"
    } else {
        // use value from flavors/defaultConfig
        // needed to make nullability work in kotlinc
        val finalAppIdFromFlavors: String = appIdFromFlavor
        "$finalAppIdFromFlavors${
            computeApplicationIdSuffix(
                variantName,
                buildType,
                basicAndroidProject,
                androidDsl,
            )
        }"
    }
}

/**
 * Combines all the appId suffixes into a single one.
 *
 * The suffixes are separated by '.' whether their first char is a '.' or not.
 */
private fun computeApplicationIdSuffix(
    variantName: String,
    buildType: AgpBuildType,
    basicAndroidProject: BasicAndroidProject,
    androidDsl: AndroidDsl,
): String {
    // for the suffix we combine the suffix from all the flavors. However, we're going to
    // want the higher priority one to be last.
    val suffixes = mutableListOf<String>()
    androidDsl.defaultConfig.applicationIdSuffix?.let {
        suffixes.add(it)
    }

    if (basicAndroidProject.projectType == ProjectType.APPLICATION) {
        val flavorSuffix =
            androidDsl.productFlavors
                .find { flavor ->
                    "${flavor.name}${buildType.name.capitalizeString()}" == variantName
                }?.applicationIdSuffix

        flavorSuffix?.also { suffixes.add(flavorSuffix) }

        // then we add the build type after.
        buildType.applicationIdSuffix?.also {
            suffixes.add(it)
        }
    }

    val nonEmptySuffixes = suffixes.filter { it.isNotEmpty() }
    return if (nonEmptySuffixes.isNotEmpty()) {
        ".${nonEmptySuffixes.joinToString(separator = ".", transform = { it.removePrefix(".") })}"
    } else {
        ""
    }
}