package com.fiftyhoursaweek.slowpage.store

/**
 * State-file failures, kept until the next success of the same kind, so whichever screen is
 * up shows them, and one reported with no editor open still shows in the editor opened later
 * (S3).
 */
class StateProblems {
    enum class Kind(val label: String) { PREFERENCES("Settings"), RECENTS("The recent list") }

    private val failures = LinkedHashMap<Kind, String>()

    @Synchronized
    fun report(kind: Kind, failure: Exception?) {
        if (failure == null) failures.remove(kind) else failures[kind] = failure.message ?: failure.toString()
    }

    /** One line per failure, e.g. "The recent list could not be saved: No space left on device". */
    @Synchronized
    fun lines(): List<String> = failures.map { (kind, reason) -> "${kind.label} could not be saved: $reason" }
}
