package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetBreakpointResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Sets a line breakpoint at a specified location.
 */
class SetBreakpointTool : AbstractMcpTool() {

    override val name = "set_breakpoint"

    override val description = """
        Sets a line breakpoint at the specified file and line.
        Supports conditions, log messages (tracepoints), and suspend policies.
        Use {expr} in log_message to evaluate expressions.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the file")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
                put("minimum", 1)
            }
            putJsonObject("condition") {
                put("type", "string")
                put("description", "Conditional expression (breakpoint only hits when true)")
            }
            putJsonObject("log_message") {
                put("type", "string")
                put("description", "Log message (tracepoint). Use {expr} for expression evaluation.")
            }
            putJsonObject("suspend_policy") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("all"))
                    add(JsonPrimitive("thread"))
                    add(JsonPrimitive("none"))
                }
                put("description", "Thread suspend policy. Default: all")
            }
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "Whether breakpoint is enabled. Default: true")
            }
            putJsonObject("temporary") {
                put("type", "boolean")
                put("description", "Remove after first hit. Default: false")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments["file_path"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file_path")
        val line = arguments["line"]?.jsonPrimitive?.intOrNull
            ?: return createErrorResult("Missing required parameter: line")
        val condition = arguments["condition"]?.jsonPrimitive?.content
        val logMessage = arguments["log_message"]?.jsonPrimitive?.content
        val suspendPolicy = arguments["suspend_policy"]?.jsonPrimitive?.content
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val temporary = arguments["temporary"]?.jsonPrimitive?.booleanOrNull ?: false

        // Find the file
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return createErrorResult("File not found: $filePath")

        val breakpointManager = getDebuggerManager(project).breakpointManager
        val lineIndex = line - 1 // Convert to 0-based

        // Check if we can put a breakpoint at this location
        val canPut = runReadAction {
            XDebuggerUtil.getInstance().canPutBreakpointAt(project, virtualFile, lineIndex)
        }

        if (!canPut) {
            return createErrorResult("Cannot set breakpoint at $filePath:$line (not a valid breakpoint location)")
        }

        return try {
            // Use XDebuggerUtil.toggleLineBreakpoint which properly handles type resolution
            // and integrates with the debugger infrastructure
            withContext(Dispatchers.Main) {
                ApplicationManager.getApplication().invokeAndWait {
                    // toggleLineBreakpoint is the same method called when clicking in the gutter
                    // It properly resolves the breakpoint type using getBreakpointTypeByPosition()
                    XDebuggerUtil.getInstance().toggleLineBreakpoint(
                        project,
                        virtualFile,
                        lineIndex,
                        temporary
                    )
                }
            }

            // Find the breakpoint that was just created
            val breakpoint = findBreakpointAtLine(breakpointManager, virtualFile, lineIndex)

            if (breakpoint == null) {
                return createErrorResult("Failed to create breakpoint at $filePath:$line")
            }

            // Configure breakpoint properties
            withContext(Dispatchers.Main) {
                ApplicationManager.getApplication().runWriteAction {
                    breakpoint.isEnabled = enabled

                    condition?.let {
                        breakpoint.conditionExpression = XExpressionImpl.fromText(it)
                    }

                    logMessage?.let {
                        breakpoint.logExpressionObject = XExpressionImpl.fromText(it)
                    }

                    suspendPolicy?.let { policy ->
                        breakpoint.suspendPolicy = when (policy.lowercase()) {
                            "none" -> com.intellij.xdebugger.breakpoints.SuspendPolicy.NONE
                            "thread" -> com.intellij.xdebugger.breakpoints.SuspendPolicy.THREAD
                            else -> com.intellij.xdebugger.breakpoints.SuspendPolicy.ALL
                        }
                    }
                }
            }

            createJsonResult(SetBreakpointResult(
                breakpointId = breakpoint.hashCode().toString(),
                status = "set",
                verified = true,
                message = "Breakpoint set at ${virtualFile.name}:$line",
                file = filePath,
                line = line
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to set breakpoint: ${e.message}")
        }
    }

    /**
     * Find a line breakpoint at the specified file and line.
     */
    private fun findBreakpointAtLine(
        breakpointManager: com.intellij.xdebugger.breakpoints.XBreakpointManager,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        lineIndex: Int
    ): XLineBreakpoint<*>? {
        return breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .firstOrNull { bp ->
                bp.fileUrl == virtualFile.url && bp.line == lineIndex
            }
    }
}
