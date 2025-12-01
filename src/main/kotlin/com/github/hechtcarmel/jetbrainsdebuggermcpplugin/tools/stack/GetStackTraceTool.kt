package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackFrameInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackTraceResult
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

/**
 * Gets the stack trace for the current debug session.
 */
class GetStackTraceTool : AbstractMcpTool() {

    override val name = "get_stack_trace"

    override val description = """
        Gets the call stack (stack trace) for the current thread.
        Returns stack frames with file, line, class, and method information.
        Use to understand the call path that led to the current execution point.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("max_frames") {
                put("type", "integer")
                put("description", "Maximum number of frames to return. Default: 50")
                put("minimum", 1)
                put("maximum", 200)
            }
        }
        put("required", buildJsonArray { })
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val maxFrames = arguments["max_frames"]?.jsonPrimitive?.intOrNull ?: 50

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to get stack trace")
        }

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val executionStack = suspendContext.activeExecutionStack
            ?: return createErrorResult("No execution stack available")

        val frames = getStackFrames(executionStack, maxFrames)

        return createJsonResult(StackTraceResult(
            sessionId = getSessionId(session),
            threadId = executionStack.displayName,
            frames = frames,
            totalFrames = frames.size
        ))
    }

    private suspend fun getStackFrames(
        executionStack: XExecutionStack,
        maxFrames: Int
    ): List<StackFrameInfo> {
        val frames = mutableListOf<StackFrameInfo>()

        // Add the top frame
        executionStack.topFrame?.let { topFrame ->
            frames.add(createFrameInfo(topFrame, 0))
        }

        // Get remaining frames with timeout
        val additionalFrames = withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine<List<XStackFrame>> { continuation ->
                val collectedFrames = mutableListOf<XStackFrame>()

                executionStack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        collectedFrames.addAll(stackFrames)
                        if (last || collectedFrames.size >= maxFrames - 1) {
                            continuation.resume(collectedFrames.take(maxFrames - 1))
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        continuation.resume(collectedFrames)
                    }
                })
            }
        } ?: emptyList()

        // Add remaining frames
        additionalFrames.forEachIndexed { index, frame ->
            frames.add(createFrameInfo(frame, index + 1))
        }

        return frames.take(maxFrames)
    }

    private fun createFrameInfo(frame: XStackFrame, index: Int): StackFrameInfo {
        val position = frame.sourcePosition

        return StackFrameInfo(
            index = index,
            file = position?.file?.path,
            line = position?.let { it.line + 1 }, // Convert to 1-based
            className = extractClassName(frame),
            methodName = extractMethodName(frame),
            isCurrent = index == 0,
            isLibrary = position?.file?.path?.contains(".jar!") == true ||
                    position?.file?.path?.contains("/jdk/") == true,
            presentation = frame.toString().take(150)
        )
    }

    private fun extractClassName(frame: XStackFrame): String? {
        val presentation = frame.toString()
        val match = Regex("""([a-zA-Z_][\w.]*)\.[a-zA-Z_]\w*\(""").find(presentation)
        return match?.groupValues?.get(1)
    }

    private fun extractMethodName(frame: XStackFrame): String? {
        val presentation = frame.toString()
        val match = Regex("""\.([a-zA-Z_]\w*)\(""").find(presentation)
        return match?.groupValues?.get(1)
    }
}
