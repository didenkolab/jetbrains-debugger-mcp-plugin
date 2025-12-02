package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pauses execution of a running debug session.
 */
class PauseTool : AbstractMcpTool() {

    override val name = "pause"

    override val description = """
        Pauses execution of a running debug session.
        Use to break into the debugger at the current execution point.
        After pausing, you can inspect variables and step through code.
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

        if (session.isPaused) {
            return createErrorResult("Session is already paused")
        }

        return try {
            // pause must be called from EDT
            ApplicationManager.getApplication().invokeAndWait {
                session.pause()
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "pause",
                status = "success",
                message = "Execution paused",
                newState = "paused"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to pause: ${e.message}")
        }
    }
}
