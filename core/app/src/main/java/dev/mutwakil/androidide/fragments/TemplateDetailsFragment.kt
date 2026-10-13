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

package dev.mutwakil.androidide.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionManager
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.R.string
import dev.mutwakil.androidide.adapters.TemplateWidgetsListAdapter
import dev.mutwakil.androidide.databinding.FragmentTemplateDetailsBinding
import dev.mutwakil.androidide.tasks.executeAsyncProvideError
import dev.mutwakil.androidide.templates.ProjectTemplateRecipeResult
import dev.mutwakil.androidide.templates.StringParameter
import dev.mutwakil.androidide.templates.Template
import dev.mutwakil.androidide.templates.base.util.isValidProjectName
import dev.mutwakil.androidide.templates.impl.ConstraintVerifier
import dev.mutwakil.androidide.utils.TemplateRecipeExecutor
import dev.mutwakil.androidide.utils.flashError
import dev.mutwakil.androidide.viewmodel.MainViewModel
import org.slf4j.LoggerFactory
import dev.mutwakil.androidide.viewmodel.RecentProjectsViewModel
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * A fragment which shows a wizard-like interface for creating templates.
 *
 * @author Akash Yadav
 */
class TemplateDetailsFragment :
  FragmentWithBinding<FragmentTemplateDetailsBinding>(
    R.layout.fragment_template_details, FragmentTemplateDetailsBinding::bind) {

  private val viewModel by activityViewModel<MainViewModel>()
  companion object {

    private val log = LoggerFactory.getLogger(TemplateDetailsFragment::class.java)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    viewModel.template.observe(viewLifecycleOwner) {
      binding.widgets.adapter = null
      viewModel.postTransition(viewLifecycleOwner) { bindWithTemplate(it) }
    }

    viewModel.creatingProject.observe(viewLifecycleOwner) {
      TransitionManager.beginDelayedTransition(binding.root)
      binding.progress.isVisible = it
      binding.finish.isEnabled = !it
      binding.previous.isEnabled = !it
    }

    binding.previous.setOnClickListener {
      viewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
    }

    binding.finish.setOnClickListener {
      if (viewModel.creatingProject.value == true) {
        return@setOnClickListener
      }
      viewModel.creatingProject.value = true
      val template = viewModel.template.value ?: run {
        viewModel.creatingProject.value = false
        viewModel.setScreen(MainViewModel.SCREEN_MAIN)
        return@setOnClickListener
      }

      val projectName = template.parameters
        .filterIsInstance<StringParameter>()
        .firstOrNull()
        ?.value
      val isValid = projectName != null &&
        isValidProjectName(projectName) &&
        template.parameters.fold(true) { parametersValid, param ->
          if (param is StringParameter) {
            parametersValid && ConstraintVerifier.isValid(param.value, param.constraints)
          } else {
            parametersValid
          }
        }

      if (!isValid) {
        viewModel.creatingProject.value = false
        flashError(string.msg_invalid_project_details)
        return@setOnClickListener
      }

      val defaultFailureMessage = getString(string.project_creation_failed)
      executeAsyncProvideError({
        TemplateRecipeExecutor().executeRecipe {
          template.recipe.execute(this) as? ProjectTemplateRecipeResult
        }
      }) { result, err ->
        viewModel.creatingProject.value = false

        if (result !is ProjectTemplateRecipeResult || err != null) {
          if (err != null) {
            log.error("Failed to create project", err)
            viewModel.pendingTemplateCreationError.value =
              err.cause?.message ?: err.message ?: defaultFailureMessage
          } else {
            log.error("Template did not return a project result: {}", result)
            viewModel.pendingTemplateCreationError.value =
              defaultFailureMessage
          }
          return@executeAsyncProvideError
        }

        // Keep the result in the activity-scoped ViewModel. MainActivity owns the
        // transition, recent-project entry, and launch so rotation cannot lose the
        // completed result or cause the fragment to launch the project twice.
        if (!result.data.projectDir.isDirectory) {
          log.error("Template returned a project directory that does not exist: {}", result.data.projectDir)
          viewModel.pendingTemplateCreationError.value = defaultFailureMessage
          return@executeAsyncProvideError
        }

        // MainActivity observes this value and owns the screen transition.
        viewModel.pendingCreatedProject.value = result
      }
    }

    binding.widgets.layoutManager = LinearLayoutManager(requireContext())
  }

  private fun bindWithTemplate(template: Template<*>?) {
    template ?: return

    binding.widgets.adapter = TemplateWidgetsListAdapter(template.widgets)
    binding.title.setText(template.templateName)
  }
}