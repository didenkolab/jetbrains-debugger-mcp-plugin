package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.output

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.output.DebuggeeOutputService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SessionOutputLine
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SessionOutputResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Returns what the debuggee printed.
 *
 * The IDE already shows this in a console, which is the problem rather than the
 * solution: a console is for a person. An agent stopped at a breakpoint could
 * read the stack and the variables and not the line the program had just printed
 * -- often the shortest route to the answer, and for a library that logs rather
 * than throws, the only route.
 */
class GetSessionOutputTool : AbstractMcpTool() {

    override val name = "get_session_output"

    override val description = """
        Returns what the debugged program printed on stdout and stderr.
        Pass the previous call's next_since as 'since' to read only what is new.
        Keeps working after the process exits, which is when a program's last words matter most.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Get Session Output")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("since") {
                put("type", "integer")
                put("description", "Return only output newer than this sequence number. Use the previous reply's next_since.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum lines to return. Defaults to 200.")
            }
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        // Sequence numbers start at one, so zero means "from the beginning" and a
        // negative cursor is a caller mistake rather than a way to rewind.
        val since = ToolArguments.optionalInt(arguments, "since", default = 0, min = 0)
        val limit = ToolArguments.optionalInt(arguments, "limit", default = DEFAULT_LIMIT, min = 1)

        val session = requireSession(project, sessionId)
        val key = getSessionId(session)
        val page = DebuggeeOutputService.getInstance(project).page(key, since, limit)

        val message = when {
            page.lines.isEmpty() && page.dropped == 0 ->
                "The program has printed nothing new."
            page.dropped > 0 ->
                "${page.dropped} earlier line(s) were dropped because the buffer wrapped, so this is not the whole output."
            else -> null
        }

        return createJsonResult(
            SessionOutputResult(
                sessionId = key,
                lines = page.lines.map { SessionOutputLine(it.seq, it.stream, it.text) },
                nextSince = page.nextSince,
                dropped = page.dropped,
                message = message
            )
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
