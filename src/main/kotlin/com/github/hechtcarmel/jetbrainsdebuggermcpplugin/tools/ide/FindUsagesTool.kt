package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ide

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.UsageLocation
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Semantic reference search through the IDE index.
 *
 * The distinction from a text search is the whole point: it resolves the symbol first, so it
 * finds usages under aliases and never matches an unrelated identifier that happens to share
 * the name. On a small Go file this is the difference between six grep hits and the three
 * calls that actually exist.
 */
class FindUsagesTool : AbstractMcpTool() {

    override val name = "find_usages"

    override val description = """
        Finds every semantic reference to the symbol at a file position, using the IDE's index.
        This is not a text search: the symbol is resolved first, so usages are found under
        aliases and an unrelated identifier sharing the name is never matched.
        References inside comments are real - a doc comment references what it documents - but
        are excluded by default, because they are rarely what "who calls this" means; set
        include_comments to see them.
        Line and column are 1-based, as the editor and search tools report them.
        Prefer this over a text search whenever the question is "who uses this".
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Find Usages")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") { put("type", "string"); put("description", "Name of the resolved symbol") }
            putJsonObject("targetKind") { put("type", "string"); put("description", "PSI class of the resolved symbol") }
            putJsonObject("declaration") { put("type", "object"); put("description", "Where the symbol is declared") }
            putJsonObject("usages") {
                put("type", "array")
                putJsonObject("items") { put("type", "object") }
                put("description", "Each with file, line, column, the source line, and inComment")
            }
            putJsonObject("total") { put("type", "integer") }
            putJsonObject("omitted") { put("type", "integer"); put("description", "Usages beyond max_results") }
            putJsonObject("commentReferencesExcluded") {
                put("type", "integer")
                put("description", "Comment references left out; pass include_comments to see them")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("target")); add(JsonPrimitive("targetKind")); add(JsonPrimitive("usages")); add(JsonPrimitive("total"))
        })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("file_path", stringProperty("Absolute path to the file."))
            put("line", integerProperty("1-based line number.", minimum = 1))
            put("column", integerProperty("1-based column. Defaults to 1, which is usually enough when the line declares the symbol.", minimum = 1))
            put("max_results", integerProperty("Maximum usages to return. Default 100.", minimum = 1))
            putJsonObject("include_comments") {
                put("type", "boolean")
                put("description", "Include references that live in comments. Default false.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("file_path")); add(JsonPrimitive("line")) })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val filePath = ToolArguments.requireString(arguments, "file_path")
        val line = ToolArguments.requireInt(arguments, "line", min = 1)
        val column = ToolArguments.optionalInt(arguments, "column", default = 1, min = 1)
        val maxResults = ToolArguments.optionalInt(arguments, "max_results", default = 100, min = 1)
        val includeComments = ToolArguments.optionalBoolean(arguments, "include_comments", default = false)

        if (DumbService.getInstance(project).isDumb) {
            return createErrorResult("The IDE is indexing; symbol resolution is unavailable until it finishes.")
        }
        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult("File not found: $filePath")

        return readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@readAction createErrorResult("The IDE has no PSI for $filePath; is it inside the project?")
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: return@readAction createErrorResult("No document for $filePath")
            if (line > document.lineCount) {
                return@readAction createErrorResult("$filePath has ${document.lineCount} lines; asked for line $line")
            }

            val offset = document.getLineStartOffset(line - 1) + (column - 1)
            val leaf = psiFile.findElementAt(offset)
                ?: return@readAction createErrorResult("Nothing at $filePath:$line:$column")
            val target = resolveTarget(leaf)
                ?: return@readAction createErrorResult(
                    "No named symbol at $filePath:$line:$column - the position may be whitespace, " +
                        "punctuation or a comment rather than an identifier."
                )

            val all = ReferencesSearch
                .search(target, GlobalSearchScope.projectScope(project))
                .findAll()
                .mapNotNull { locationOf(it.element) }
                .sortedWith(compareBy({ it.file }, { it.line }, { it.column }))

            val inComments = all.count { it.inComment }
            val found = if (includeComments) all else all.filterNot { it.inComment }

            createJsonResult(
                FindUsagesResult(
                    target = (target as? PsiNamedElement)?.name ?: target.text.take(80),
                    targetKind = target::class.java.simpleName,
                    declaration = locationOf(target),
                    usages = found.take(maxResults),
                    total = found.size,
                    omitted = (found.size - maxResults).coerceAtLeast(0),
                    commentReferencesExcluded = if (includeComments) 0 else inComments,
                )
            )
        }
    }

    /**
     * Turns the element under the cursor into the thing worth searching for.
     *
     * A caller points at whatever the editor shows them, which is usually a reference rather
     * than a declaration. Searching the reference itself would find nothing, so resolve it
     * first, and fall back to the nearest named ancestor when the position is already a
     * declaration.
     */
    private fun resolveTarget(leaf: PsiElement): PsiElement? {
        leaf.parent?.let { parent ->
            (parent as? PsiReference ?: parent.reference)?.resolve()?.let { return it }
        }
        leaf.reference?.resolve()?.let { return it }
        return PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
    }

    private fun isInComment(element: PsiElement): Boolean =
        element is PsiComment ||
            PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null

    private fun locationOf(element: PsiElement): UsageLocation? {
        val virtualFile = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val offset = element.textRange?.startOffset ?: return null
        if (offset > document.textLength) return null

        val lineIndex = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        return UsageLocation(
            file = virtualFile.path,
            line = lineIndex + 1,
            column = offset - lineStart + 1,
            text = document.getText(TextRange(lineStart, lineEnd)).trim(),
            inComment = isInComment(element),
        )
    }
}
