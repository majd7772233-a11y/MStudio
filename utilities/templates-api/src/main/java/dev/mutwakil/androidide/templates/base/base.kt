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

package dev.mutwakil.androidide.templates.base

import dev.mutwakil.androidide.templates.BooleanParameter
import dev.mutwakil.androidide.templates.CheckBoxWidget
import dev.mutwakil.androidide.templates.EnumParameter
import dev.mutwakil.androidide.templates.FileTemplate
import dev.mutwakil.androidide.templates.FileTemplateRecipeResult
import dev.mutwakil.androidide.templates.Language
import dev.mutwakil.androidide.templates.ModuleTemplate
import dev.mutwakil.androidide.templates.ModuleTemplateData
import dev.mutwakil.androidide.templates.ModuleType
import dev.mutwakil.androidide.templates.ModuleType.AndroidApp
import dev.mutwakil.androidide.templates.ModuleType.AndroidLibrary
import dev.mutwakil.androidide.templates.ParameterConstraint.DIRECTORY
import dev.mutwakil.androidide.templates.ParameterConstraint.EXISTS
import dev.mutwakil.androidide.templates.ParameterConstraint.MODULE_NAME
import dev.mutwakil.androidide.templates.ParameterConstraint.NONEMPTY
import dev.mutwakil.androidide.templates.ProjectTemplate
import dev.mutwakil.androidide.templates.ProjectTemplateData
import dev.mutwakil.androidide.templates.ProjectVersionData
import dev.mutwakil.androidide.templates.R
import dev.mutwakil.androidide.templates.Sdk
import dev.mutwakil.androidide.templates.SpinnerWidget
import dev.mutwakil.androidide.templates.StringParameter
import dev.mutwakil.androidide.templates.TextFieldWidget
import dev.mutwakil.androidide.templates.base.util.getNewProjectName
import dev.mutwakil.androidide.templates.base.util.isValidProjectName
import dev.mutwakil.androidide.templates.base.util.moduleNameToDir
import dev.mutwakil.androidide.templates.enumParameter
import dev.mutwakil.androidide.templates.minSdkParameter
import dev.mutwakil.androidide.templates.packageNameParameter
import dev.mutwakil.androidide.templates.projectLanguageParameter
import dev.mutwakil.androidide.templates.projectNameParameter
import dev.mutwakil.androidide.templates.stringParameter
import dev.mutwakil.androidide.templates.useKtsParameter
import dev.mutwakil.androidide.utils.AndroidUtils
import dev.mutwakil.androidide.utils.Environment
import java.io.File

typealias AndroidModuleTemplateConfigurator = AndroidModuleTemplateBuilder.() -> Unit

/**
 * Setup base files for project templates.
 *
 * @param block Function to configure the template.
 */
inline fun baseProject(projectName: StringParameter = projectNameParameter(),
  packageName: StringParameter = packageNameParameter(),
  useKts: BooleanParameter = useKtsParameter(),
  minSdk: EnumParameter<Sdk> = minSdkParameter(),
  language: EnumParameter<Language> = projectLanguageParameter(),
  projectVersionData: ProjectVersionData = ProjectVersionData(),
  crossinline block: ProjectTemplateBuilder.() -> Unit
): ProjectTemplate {
  return ProjectTemplateBuilder().apply {

    // When project name is changed, change the package name accordingly
    projectName.observe { name ->
      val newPackage =
        AndroidUtils.appNameToPackageName(name.value, packageName.value)
      packageName.setValue(newPackage)
    }

    Environment.mkdirIfNotExists(Environment.PROJECTS_DIR)

    val saveLocation = stringParameter {
      name = R.string.wizard_save_location
      default = Environment.PROJECTS_DIR.absolutePath
      endIcon = { R.drawable.ic_folder }
      constraints = listOf(NONEMPTY, DIRECTORY, EXISTS)
    }

    projectName.doBeforeCreateView {
      it.setValue(getNewProjectName(saveLocation.value, projectName.value))
    }

    widgets(TextFieldWidget(projectName), TextFieldWidget(packageName),
      TextFieldWidget(saveLocation), SpinnerWidget(language),
      SpinnerWidget(minSdk), CheckBoxWidget(useKts))

    // Setup the required properties before executing the recipe
    preRecipe = {
      this@apply._executor = this

      val saveRoot = File(saveLocation.value).canonicalFile
      require(saveRoot.isDirectory && saveRoot.canRead() && saveRoot.canWrite()) {
        "Project save location is not a readable, writable directory: '$saveRoot'"
      }
      require(isValidProjectName(projectName.value)) {
        "Project name must be a valid single directory name"
      }

      val projectDir = File(saveRoot, projectName.value).canonicalFile
      require(projectDir.parentFile == saveRoot) {
        "Project directory must remain inside the selected save location"
      }

      this@apply._data = ProjectTemplateData(projectName.value,
        projectDir, projectVersionData,
        language = language.value, useKts = useKts.value)

      // Start the transaction before template builders create directories directly.
      beginProjectCreation(data.projectDir)
      require(!data.projectDir.exists()) {
        "Project directory already exists: '${data.projectDir}'"
      }

      setDefaultModuleData(
        ModuleTemplateData(":app", appName = data.name, packageName.value,
          data.moduleNameToDir(":app"), type = AndroidApp,
          language = language.value, minSdk = minSdk.value,
          useKts = data.useKts))
    }

    // After the recipe is executed, finalize the project creation
    // In this phase, we write the build scripts as they may need additional data based on the previous recipe
    // For example, writing settings.gradle[.kts] needs to know the name of the modules so that those can be includedl
    postRecipe = {
      // build.gradle[.kts]
      buildGradle()

      // settings.gradle[.kts]
      settingsGradle()

      // gradle.properties
      gradleProps()

      // gradlew
      // gradlew.bat
      // gradle/wrapper/gradle-wrapper.jar
      // gradle/wrapper/gradle-wrapper.properties
      gradleWrapper()

      // .gitignore
      gitignore()
    }

    block()

  }.build() as ProjectTemplate
}

/**
 * Create a new module project in this project.
 *
 * @param block The module configurator.
 */
inline fun baseAndroidModule(isLibrary: Boolean = false,
  crossinline block: AndroidModuleTemplateConfigurator
): ModuleTemplate {
  return AndroidModuleTemplateBuilder().apply {

    val appName = if (isLibrary) null else projectNameParameter()
    val language = projectLanguageParameter()
    val minSdk = minSdkParameter()
    val packageName = packageNameParameter()
    val useKts = useKtsParameter()

    val moduleName = stringParameter {
      name = R.string.wizard_module_name
      default = ":app"
      constraints = listOf(NONEMPTY, MODULE_NAME)
    }

    val type = enumParameter<ModuleType> {
      name = R.string.wizard_module_type
      default = AndroidLibrary
      startIcon = { R.drawable.ic_android }
      displayName = ModuleType::typeName
    }

    widgets(TextFieldWidget(moduleName))

    appName?.let {
      widgets(TextFieldWidget(it))
    }

    widgets(TextFieldWidget(packageName), SpinnerWidget(minSdk),
      SpinnerWidget(type), SpinnerWidget(language), CheckBoxWidget(useKts))

    preRecipe = commonPreRecipe {
      ModuleTemplateData(name = moduleName.value, appName = appName?.value,
        packageName = packageName.value,
        projectDir = requireProjectData().moduleNameToDir(moduleName.value),
        type = type.value, language = language.value, minSdk = minSdk.value,
        useKts = useKts.value)
    }
    postRecipe = commonPostRecipe()

    block()
  }.build() as ModuleTemplate
}

/**
 * Creates a template for a file.
 *
 * @param dir The directory in which the file will be created.
 * @param configurator The configurator to configure the template.
 * @return The [FileTemplate].
 */
inline fun <R : FileTemplateRecipeResult> baseFile(dir: File,
  crossinline configurator: FileTemplateBuilder<R>.() -> Unit
): FileTemplate<R> {
  return FileTemplateBuilder<R>(dir).apply(configurator)
    .build() as FileTemplate<R>
}