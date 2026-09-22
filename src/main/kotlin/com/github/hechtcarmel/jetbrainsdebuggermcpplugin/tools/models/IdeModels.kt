package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

@Serializable
data class UsageLocation(
    val file: String,
    val line: Int,
    val column: Int,
    /** The source line, trimmed — enough to judge a usage without opening the file. */
    val text: String,
    /**
     * True when the reference lives in a comment rather than in code.
     *
     * These are real references, not text matches: a doc comment references the symbol it
     * documents, and the IDE models that so documentation navigation works. They are still
     * rarely what "who calls this" means, so they are excluded by default.
     */
    val inComment: Boolean = false,
)

@Serializable
data class FindUsagesResult(
    /** The element the search resolved to, which may not be the one under the cursor. */
    val target: String,
    val targetKind: String,
    val declaration: UsageLocation?,
    val usages: List<UsageLocation>,
    val total: Int,
    val omitted: Int = 0,
    /** References found in comments and left out; pass include_comments to see them. */
    val commentReferencesExcluded: Int = 0,
)

@Serializable
data class QuickFixInfo(
    val index: Int,
    val name: String,
    /** The family a fix belongs to. Prefer the name: one family can hold inverse fixes. */
    val familyName: String,
)

@Serializable
data class ProblemWithFixes(
    val line: Int,
    val description: String,
    /** The inspection that reported it, e.g. to suppress or configure it later. */
    val inspection: String,
    val fixes: List<QuickFixInfo>,
)

@Serializable
data class QuickFixesResult(
    val file: String,
    val problems: List<ProblemWithFixes>,
    val total: Int,
    val omitted: Int = 0,
    /** Problems the IDE reported but that carry no automatic fix. */
    val withoutFixes: Int = 0,
)

@Serializable
data class AppliedFix(val line: Int, val description: String, val fix: String)

@Serializable
data class ApplyQuickFixResult(
    val file: String,
    val applied: List<AppliedFix>,
    val message: String,
)
