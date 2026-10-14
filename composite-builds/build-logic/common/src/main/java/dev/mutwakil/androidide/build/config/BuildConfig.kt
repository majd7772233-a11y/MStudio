package dev.mutwakil.androidide.build.config/*
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

import org.gradle.api.JavaVersion

/**
 * Build configuration for the IDE.
 *
 * @author Akash Yadav
 */
object BuildConfig {

  /** The internal name for the IDE */
  const val INTERNAL_NAME = "MStudio"

  /** Kotlin/Java namespace retained from the AndroidIDE codebase; not the installed app ID. */
  const val PACKAGE_NAME = "dev.mutwakil.androidide"

  /** Installed Android package ID used for app identity and PackageManager operations. */
  const val APPLICATION_ID = "com.magd.mstudio"

  /** The compile SDK version. */
  const val COMPILE_SDK = 36

  /** The minimum SDK version. */
  const val MIN_SDK = 26

  /** The target SDK version. */
  const val TARGET_SDK = 28

  const val NDK_VERSION ="28.2.13676358" //"26.1.10909125"

  /** The source and target Java compatibility. */
  val JAVA_VERSION = JavaVersion.VERSION_17
}
