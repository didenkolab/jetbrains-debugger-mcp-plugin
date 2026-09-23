package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * What this debugger can actually do, as a value an agent can read.
 *
 * This exists because the alternative is prose. The support matrix used to live
 * in documentation -- "Full Support (Java, Kotlin, Python...)", "Limited Support
 * (Rust, C++, C, Go, Swift)" -- which is a documentation answer to a machine
 * problem: the agent learns the limit from text it may never have been given,
 * and cannot check it before acting. It plans against this instead, and finds
 * out before a call rather than by making one.
 *
 * The values are derived from the debugger actually attached, not from a table
 * of language names, because the same IDE debugs different languages through
 * different engines and the engine is what sets the limit.
 */
@Serializable
data class BackendCapabilities(
    /** "full", "primitives" or "none" -- whether a value can be changed at runtime. */
    val setVariable: String,
    /** "full", "guarded" or "none" -- whether an expression may call functions. */
    val evalCallsFunctions: String,
    /**
     * "full" or "none" -- whether this server offers a watchpoint tool.
     *
     * None, and the reason is worth stating precisely because the obvious one is
     * wrong. It is not that watchpoints are unreachable: a field watchpoint is an
     * ordinary `XBreakpointType` for the languages that have one
     * (`JavaFieldBreakpointType`, for instance), registered at the same extension
     * point this plugin already enumerates. It is that no tool here exposes them,
     * and the capability describes the tool surface rather than the IDE's reach.
     * Adding them is per-language work, not a blocked door.
     */
    val watchpoints: String,
    /**
     * "per_unit", "total" or "none".
     *
     * None, and that is not an oversight: the language-agnostic breakpoint API
     * exposes no hit-count accessor, so the counts live in engine-specific code
     * this plugin does not reach.
     */
    val hitCounts: String,
    /**
     * "buffered", "auto_continue" or "suspend_only".
     *
     * auto_continue: a log breakpoint reports and lets execution carry on, so
     * the agent is not in the loop -- but the process is still interrupted at
     * every hit, so timing is perturbed exactly as a breakpoint perturbs it.
     */
    val traceMode: String,
    /** Whether a breakpoint can be placed by function name rather than by line. */
    val breakpointBySymbol: Boolean,
    /** Whether the chain of threads that created a thread can be recovered. */
    val ancestry: Boolean,
    /** The breakpoint kinds this debugger accepts, by neutral name. */
    val breakpointKinds: List<String>
)

/**
 * The answer to describe_backend.
 */
@Serializable
data class BackendDescription(
    /** The debug engine actually in use, or null when no session is running. */
    val engine: String? = null,
    /** The language it is debugging, as far as the engine reveals it. */
    val language: String? = null,
    /** IDE name and build, because a capability can differ between them. */
    val ide: String,
    val capabilities: BackendCapabilities,
    /**
     * True when no session is open, so the capabilities are the generic ones
     * and the engine-specific half is a guess. Saying so is the point: a guess
     * presented as a reading is worse than no reading.
     */
    val withoutSession: Boolean,
    val message: String
)


/**
 * One unit of execution, named neutrally.
 *
 * "Thread" is the wrong word in more runtimes than it is the right one: Go has
 * goroutines, Python has threads plus asyncio tasks, a browser has one thread and
 * an async stack. The label travels with the unit so a caller does not have to
 * guess which it is, and so one tool name serves every runtime.
 */
@Serializable
data class ExecutionUnitInfo(
    val id: String,
    /** "thread", "goroutine" or "task", as far as the engine reveals it. */
    val kind: String,
    val name: String? = null,
    val state: String,
    val isCurrent: Boolean = false
)

@Serializable
data class ExecutionUnitListResult(
    val sessionId: String,
    val units: List<ExecutionUnitInfo>,
    val currentUnitId: String? = null,
    val message: String? = null
)

/** One captured line of what the debuggee printed. */
@Serializable
data class SessionOutputLine(
    val seq: Int,
    /** "stdout" or "stderr". */
    val stream: String,
    val text: String
)

@Serializable
data class SessionOutputResult(
    val sessionId: String,
    val lines: List<SessionOutputLine>,
    /** Pass this back as `since` to read only what is new. */
    val nextSince: Int,
    /**
     * Lines discarded because the buffer wrapped. Non-zero means output was lost,
     * not that the program was quiet -- a silent drop would let a reader conclude
     * the opposite of the truth.
     */
    val dropped: Int,
    val message: String? = null
)
