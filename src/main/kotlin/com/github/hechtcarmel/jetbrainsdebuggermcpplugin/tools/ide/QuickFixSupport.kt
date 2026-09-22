package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ide

import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile

/**
 * Runs the project's enabled local inspections over one file and pairs each problem with the
 * fixes the IDE offers for it — the machinery behind Alt+Enter, reached without an editor.
 *
 * It uses the project's own inspection profile rather than a fixed set: an agent fixing things
 * should fix what this project considers a problem, not what the plugin author does.
 *
 * The InspectionEngine call is kept here alone on purpose. Its overloads are long, positional
 * and carry no stability guarantee across platform versions, so a change breaks one small file
 * with an obvious fix instead of scattering through the tools.
 */
internal object QuickFixSupport {

    /** Keyed by inspection short name, which is also what callers want reported. */
    private fun inspect(
        wrappers: List<LocalInspectionToolWrapper>,
        psiFile: PsiFile,
        manager: InspectionManager,
    ): Map<String, List<ProblemDescriptor>> =
        @Suppress("DEPRECATION")
        InspectionEngine.inspectEx(
            wrappers,
            psiFile,
            manager,
            // isOnTheFly=false: batch semantics, which is what a non-interactive caller wants.
            false,
            EmptyProgressIndicator(),
        )

    /** Descriptors flattened with the inspection that produced each, ordered by line. */
    fun problems(project: Project, psiFile: PsiFile): List<Pair<String, ProblemDescriptor>> {
        @Suppress("DEPRECATION")
        val profile = InspectionProjectProfileManager.getInstance(project).inspectionProfile
        val wrappers = profile.getAllEnabledInspectionTools(project)
            .mapNotNull { it.tool as? LocalInspectionToolWrapper }
        if (wrappers.isEmpty()) return emptyList()

        return inspect(wrappers, psiFile, InspectionManager.getInstance(project))
            .flatMap { (inspection, descriptors) -> descriptors.map { inspection to it } }
            .sortedBy { it.second.lineNumber }
    }

    /**
     * The one-line text shown in the Problems view.
     *
     * Description templates carry markup placeholders such as `#ref` and `#loc`; leaving them
     * in makes the output read like a template rather than a problem.
     */
    fun describe(descriptor: ProblemDescriptor): String =
        descriptor.descriptionTemplate
            .replace("#ref", "")
            .replace("#loc", "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
