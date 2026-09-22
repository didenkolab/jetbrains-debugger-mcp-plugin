package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ide

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ProblemWithFixes
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.QuickFixInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.QuickFixesResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
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

/** The Alt+Enter menu, reached without an editor. */
class ListQuickFixesTool : AbstractMcpTool() {

    override val name = "list_quick_fixes"

    override val description = """
        Runs this project's enabled inspections over a file and lists each problem together with
        the fixes the IDE offers for it - the Alt+Enter menu, reached without an editor.
        It uses the project's own inspection profile, so it reports what this project considers
        a problem rather than a fixed set.
        Pass a line to narrow to one. Problems carrying no automatic fix are counted, not listed.
        Use the reported fix NAME with apply_quick_fix.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("List Quick Fixes")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file") { put("type", "string") }
            putJsonObject("problems") {
                put("type", "array")
                putJsonObject("items") { put("type", "object") }
                put("description", "Each with line, description, inspection, and its fixes")
            }
            putJsonObject("total") { put("type", "integer") }
            putJsonObject("omitted") { put("type", "integer") }
            putJsonObject("withoutFixes") {
                put("type", "integer")
                put("description", "Problems reported with no automatic fix available")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("file")); add(JsonPrimitive("problems")); add(JsonPrimitive("total"))
        })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("file_path", stringProperty("Absolute path to the file."))
            put("line", integerProperty("1-based line to narrow to. Omit for the whole file.", minimum = 1))
            put("max_problems", integerProperty("Maximum problems to return. Default 50.", minimum = 1))
        }
        put("required", buildJsonArray { add(JsonPrimitive("file_path")) })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val filePath = ToolArguments.requireString(arguments, "file_path")
        val line = ToolArguments.optionalIntOrNull(arguments, "line", min = 1)
        val maxProblems = ToolArguments.optionalInt(arguments, "max_problems", default = 50, min = 1)

        if (DumbService.getInstance(project).isDumb) {
            return createErrorResult("The IDE is indexing; inspections are unavailable until it finishes.")
        }
        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult("File not found: $filePath")

        return readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@readAction createErrorResult("The IDE has no PSI for $filePath; is it inside the project?")

            val all = QuickFixSupport.problems(project, psiFile)
                .filter { (_, descriptor) -> line == null || descriptor.lineNumber + 1 == line }
            val withFixes = all.filter { it.second.fixes?.isNotEmpty() == true }

            createJsonResult(
                QuickFixesResult(
                    file = virtualFile.path,
                    problems = withFixes.take(maxProblems).map { (inspection, descriptor) ->
                        ProblemWithFixes(
                            line = descriptor.lineNumber + 1,
                            description = QuickFixSupport.describe(descriptor),
                            inspection = inspection,
                            fixes = descriptor.fixes.orEmpty().mapIndexed { index, fix ->
                                QuickFixInfo(index, fix.name, fix.familyName)
                            },
                        )
                    },
                    total = withFixes.size,
                    omitted = (withFixes.size - maxProblems).coerceAtLeast(0),
                    withoutFixes = all.size - withFixes.size,
                )
            )
        }
    }
}
