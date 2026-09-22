package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ide

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.AppliedFix
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ApplyQuickFixResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Applies an IDE quick fix, optionally to every matching site in a file.
 *
 * Three things here are not obvious, and each was found by running this rather than reading it:
 *
 * 1. The file is re-analysed between fixes. Applying one rewrites the PSI and invalidates every
 *    other descriptor from the same run; reusing them would apply a fix at a position that has
 *    moved, which is how batch refactoring corrupts files.
 * 2. A pass that does not reduce the number of matching problems stops the loop. One family can
 *    hold mutually inverse fixes - convert a string literal one way, then back - and without
 *    this the loop ping-pongs a single site until it hits the cap.
 * 3. `QuickFix.applyFix` returns nothing and a fix may decline a site after all, so the document
 *    stamp is compared rather than assuming the call did something. Reporting a success that
 *    never happened is worse than reporting a failure.
 */
class ApplyQuickFixTool : AbstractMcpTool() {

    override val name = "apply_quick_fix"

    override val description = """
        Applies an IDE quick fix, matched against the fix NAME reported by list_quick_fixes.
        The family name also works, but prefer the name: one family can hold mutually inverse
        fixes, and matching the family can apply one and then undo it.
        With a line, fixes that single problem. With apply_all, fixes every matching problem in
        the file - one call instead of one text edit per site, each edit made by the IDE, and
        all of them landing as a single undo step.
        apply_all stops as soon as a pass stops reducing the number of matching problems, so a
        reversible fix cannot loop. A fix that declines to change anything is reported as such
        rather than counted as applied.
        Changes are saved to disk, because the next step is usually to read the file or build.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Apply Quick Fix")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file") { put("type", "string") }
            putJsonObject("applied") {
                put("type", "array")
                putJsonObject("items") { put("type", "object") }
                put("description", "Each fix that actually changed the file, with its line")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Why the run ended: finished, capped, or stopped making progress")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("file")); add(JsonPrimitive("applied")); add(JsonPrimitive("message"))
        })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("file_path", stringProperty("Absolute path to the file."))
            put("fix_name", stringProperty("Fix name as reported by list_quick_fixes. The family name is accepted too, but is less precise."))
            put("line", integerProperty("1-based line of the single problem to fix.", minimum = 1))
            putJsonObject("apply_all") {
                put("type", "boolean")
                put("description", "Fix every matching problem in the file. Default false.")
            }
            put("max_fixes", integerProperty("Safety cap for apply_all. Default 50.", minimum = 1))
        }
        put("required", buildJsonArray { add(JsonPrimitive("file_path")); add(JsonPrimitive("fix_name")) })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val filePath = ToolArguments.requireString(arguments, "file_path")
        val fixName = ToolArguments.requireString(arguments, "fix_name")
        val line = ToolArguments.optionalIntOrNull(arguments, "line", min = 1)
        val applyAll = ToolArguments.optionalBoolean(arguments, "apply_all", default = false)
        val maxFixes = ToolArguments.optionalInt(arguments, "max_fixes", default = 50, min = 1)

        if (DumbService.getInstance(project).isDumb) {
            return createErrorResult("The IDE is indexing; inspections are unavailable until it finishes.")
        }
        if (line == null && !applyAll) {
            return createErrorResult("Pass a line to fix one problem, or apply_all=true to fix every match in the file.")
        }
        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult("File not found: $filePath")

        val applied = mutableListOf<AppliedFix>()
        var stoppedBecause: String? = null
        var previousRemaining = Int.MAX_VALUE

        repeat(if (applyAll) maxFixes else 1) {
            val matches = readAction {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@readAction emptyList()
                QuickFixSupport.problems(project, psiFile)
                    .filter { (_, d) -> line == null || d.lineNumber + 1 == line }
                    .mapNotNull { (_, d) ->
                        d.fixes.orEmpty()
                            .firstOrNull { it.name == fixName || it.familyName == fixName }
                            ?.let { fix -> Triple(d, fix, QuickFixSupport.describe(d)) }
                    }
            }

            if (matches.isEmpty()) {
                stoppedBecause = "no matches left"
                return@repeat
            }
            if (matches.size >= previousRemaining) {
                stoppedBecause =
                    "'$fixName' stopped reducing the problem count at ${matches.size}; it may be " +
                        "reversible, or re-report on the site it just changed. Nothing further applied"
                return@repeat
            }
            previousRemaining = matches.size

            val (descriptor, fix, description) = matches.first()
            val atLine = descriptor.lineNumber + 1
            val document = readAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
                ?: return@repeat

            val before = document.modificationStamp
            WriteCommandAction.writeCommandAction(project)
                .withName("Quick fix: $fixName")
                .run<RuntimeException> {
                    @Suppress("UNCHECKED_CAST")
                    (fix as QuickFix<CommonProblemDescriptor>).applyFix(project, descriptor)
                }

            if (document.modificationStamp == before) {
                stoppedBecause =
                    "'${fix.name}' changed nothing at line $atLine - the inspection reports a " +
                        "problem there, but the fix declines to act on that site"
                return@repeat
            }
            WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
                FileDocumentManager.getInstance().saveDocument(document)
            }
            applied += AppliedFix(atLine, description, fix.name)
        }

        return createJsonResult(
            ApplyQuickFixResult(
                file = virtualFile.path,
                applied = applied,
                message = when {
                    applied.isEmpty() && stoppedBecause != null -> stoppedBecause!!
                    applied.isEmpty() ->
                        "No problem in this file offers a fix named '$fixName'. " +
                            "Call list_quick_fixes to see what is available."
                    stoppedBecause != null -> "Applied ${applied.size} fix(es); $stoppedBecause."
                    applied.size == maxFixes && applyAll ->
                        "Applied ${applied.size} fixes and stopped at the cap; call again to continue."
                    else -> "Applied ${applied.size} fix(es)."
                },
            )
        )
    }
}
