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
    /** "full" or "none". The language-agnostic IDE API exposes no watchpoints. */
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
