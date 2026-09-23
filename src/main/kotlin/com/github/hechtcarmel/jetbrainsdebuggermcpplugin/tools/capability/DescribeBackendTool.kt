package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.capability

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.BackendCapabilities
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.BackendDescription
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Reports what this debugger can do, as data rather than as documentation.
 *
 * The support matrix used to be prose in a README: an agent had to be told the
 * limits out of band, could not check them, and discovered them by making a call
 * that failed. Reading them here costs one call and changes the plan instead of
 * wasting several.
 *
 * Capabilities are derived from the debug engine actually attached. The same IDE
 * debugs different languages through different engines, and the engine is what
 * sets the limit -- a table keyed by language name would be wrong the moment a
 * language gained a second backend.
 */
class DescribeBackendTool : AbstractMcpTool() {

    override val name = "describe_backend"

    override val description = """
        Reports what this debugger can and cannot do: changing variables, calling functions during
        evaluation, watchpoints, hit counts, tracing without stopping, breakpoints by symbol.
        Read it before planning a debugging strategy rather than discovering the limits by failing
        into them. Capabilities depend on the engine attached, so the answer is most accurate while
        a session is running.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Describe Backend")

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
        val session = runCatching { requireSession(project, sessionId) }.getOrNull()

        val engineClass = session?.debugProcess?.javaClass?.name
        val engine = DebugEngine.of(engineClass)
        val ide = ApplicationInfo.getInstance().let { "${it.versionName} ${it.fullVersion}" }

        val message = if (session == null) {
            "No session is running, so only the capabilities that hold for every engine are known. " +
                "The ones that depend on the language -- changing variables, calling functions in an " +
                "expression -- are reported at their most cautious; ask again once a session is open."
        } else {
            "Plan against these rather than discovering the limits by failing into them."
        }

        return createJsonResult(
            BackendDescription(
                engine = engineClass,
                language = engine.language,
                ide = ide,
                capabilities = engine.capabilities(),
                withoutSession = session == null,
                message = message
            )
        )
    }
}

/**
 * The debug engines this plugin can recognise, and what each is true of.
 *
 * Recognition is by the engine's own class name because that is the only thing
 * available that the IDE cannot be wrong about. An unrecognised engine is
 * reported as unknown with the cautious answer, never as the optimistic one: a
 * capability claimed and absent costs more than one withheld and present.
 */
internal enum class DebugEngine(
    val language: String?,
    private val classMarkers: List<String>,
    private val richEvaluation: Boolean,
    /**
     * What this runtime calls its unit of execution.
     *
     * "Thread" is the wrong word in more runtimes than it is the right one, and
     * calling a goroutine a thread makes an agent reason about a thing that is
     * not there.
     */
    val unitKind: String = "thread"
) {
    JVM("jvm", listOf("com.intellij.debugger.engine.JavaDebugProcess", "JavaDebugProcess"), true),
    PYTHON("python", listOf("com.jetbrains.python.debugger.PyDebugProcess", "PyDebugProcess"), true),
    JAVASCRIPT("javascript", listOf("JavaScriptDebugProcess", "com.intellij.javascript.debugger"), true),
    PHP("php", listOf("com.jetbrains.php.debug", "PhpDebugProcess"), true),
    RUBY("ruby", listOf("org.jetbrains.plugins.ruby", "RubyDebugProcess"), true),

    /**
     * Native engines -- LLDB and GDB behind CLion, RustRover and the rest.
     *
     * Their evaluation reads variables but frequently refuses method calls, and
     * writing back works for primitives while a String, a Vec or a struct
     * commonly fails. Reporting them as fully capable is how an agent ends up
     * blaming its own expression for a limit of the engine.
     */
    NATIVE("native", listOf("com.jetbrains.cidr", "CidrDebugProcess", "LLDBDriver", "GDBDriver"), false),

    /** Go through Delve inside the IDE. */
    GO("go", listOf("com.goide.dlv", "DlvDebugProcess"), false, unitKind = "goroutine"),

    UNKNOWN(null, emptyList(), false);

    fun capabilities(): BackendCapabilities = BackendCapabilities(
        setVariable = if (richEvaluation) "full" else "primitives",
        evalCallsFunctions = if (richEvaluation) "full" else "guarded",
        // No tool here sets one. Not because they are unreachable -- a field
        // watchpoint is an ordinary XBreakpointType for the languages that have
        // one -- but because the capability describes what this surface offers.
        watchpoints = "none",
        // XBreakpoint has no hit-count accessor; the counts live in
        // engine-specific code this plugin does not reach.
        hitCounts = "none",
        // A log breakpoint keeps the agent out of the loop but still stops the
        // process at every hit.
        traceMode = "auto_continue",
        breakpointBySymbol = false,
        ancestry = false,
        breakpointKinds = listOf("line")
    )

    companion object {
        fun of(engineClass: String?): DebugEngine {
            if (engineClass == null) return UNKNOWN
            return entries.firstOrNull { engine ->
                engine.classMarkers.any { engineClass.contains(it) }
            } ?: UNKNOWN
        }
    }
}
