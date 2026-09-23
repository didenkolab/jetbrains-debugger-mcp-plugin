package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.capability.DebugEngine
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionUnitInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionUnitListResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ExecutionStackUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lists the units of execution, under a name that is true of every runtime.
 *
 * This is `list_threads` answered neutrally. The older name is kept because
 * clients use it, but "thread" is the wrong word in more runtimes than it is the
 * right one -- Go has goroutines, Python has threads plus asyncio tasks -- and an
 * agent told "thread" reasons about a thing that is not there. The kind travels
 * with each unit instead, so one tool name serves every engine and the caller
 * does not have to guess.
 */
class ListExecutionUnitsTool : AbstractMcpTool() {

    override val name = "list_execution_units"

    override val description = """
        Lists the debugged program's units of execution -- threads, goroutines or tasks -- with their
        state and which one is current. Each carries the kind it actually is, so nothing has to be
        assumed from the tool's name. Equivalent to list_threads, phrased for every runtime.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("List Execution Units")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val session = requirePausedSession(project, sessionId, "list execution units")

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val kind = DebugEngine.of(session.debugProcess?.javaClass?.name).unitKind
        val stacks = ExecutionStackUtils.collectExecutionStacks(suspendContext)
        val active = suspendContext.activeExecutionStack

        val units = stacks.map { stack ->
            ExecutionUnitInfo(
                id = stack.hashCode().toString(),
                kind = kind,
                name = stack.displayName,
                // Paused is the one that stopped; the rest are held because the
                // suspend policy stopped everything, which is a different fact.
                state = if (stack == active) "paused" else "suspended",
                isCurrent = stack == active
            )
        }

        return createJsonResult(
            ExecutionUnitListResult(
                sessionId = getSessionId(session),
                units = units,
                currentUnitId = active?.hashCode()?.toString()
            )
        )
    }
}
