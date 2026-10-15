package com.jarvis.assistant.core

/** What Jarvis decided to do. Pure data, no Android classes, so it can be unit-tested anywhere. */
data class Plan(
    val action: String,
    val target: String = "",
    val message: String = "",
    val hour: Int = -1,
    val minute: Int = 0,
    val seconds: Int = 0,
    val value: String = "",
    val reply: String = ""
)

/**
 * The result of doing a [Plan].
 * [awaiting] is set when Jarvis asked a question and needs your answer to finish the job.
 * [keepOpen] keeps the mic open for a follow-up (used for answers, not for actions like calls).
 */
data class Outcome(
    val reply: String,
    val awaiting: Plan? = null,
    val keepOpen: Boolean = false
)
