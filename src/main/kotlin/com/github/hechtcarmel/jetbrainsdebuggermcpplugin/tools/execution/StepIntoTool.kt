package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Steps into the function call on the current line.
 */
class StepIntoTool : AbstractMcpTool() {

    override val name = "step_into"

    override val description = """
        Steps into the function call on the current line.
        If the current line has a function call, execution enters that function.
        Use to debug into function implementations.
        After stepping, use get_debug_session_status to see the new state.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
        }
        put("required", buildJsonArray { })
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to step into")
        }

        return try {
            session.stepInto()
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "step_into",
                status = "success",
                message = "Stepped into",
                newState = "running" // Will pause again after step completes
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to step into: ${e.message}")
        }
    }
}
