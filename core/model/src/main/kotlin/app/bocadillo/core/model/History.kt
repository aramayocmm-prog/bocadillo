package app.bocadillo.core.model

/**
 * Linear undo/redo history of immutable states.
 *
 * Every committed edit pushes a full immutable [T]; because documents share their unchanged
 * lists structurally, each entry is cheap. Pushing after an undo discards the redo branch.
 * When [limit] is exceeded the oldest entries after the first one are dropped, so the
 * "picture opened" stage is always reachable.
 */
class History<T>(initial: T, initialLabel: String, private val limit: Int = 200) {

    data class Entry<T>(val label: String, val state: T)

    init { require(limit >= 2) { "limit must be >= 2" } }

    private val entries = ArrayList<Entry<T>>().apply { add(Entry(initialLabel, initial)) }
    private var cursor = 0

    val current: T get() = entries[cursor].state
    val currentLabel: String get() = entries[cursor].label
    val index: Int get() = cursor
    val size: Int get() = entries.size
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < entries.lastIndex

    /** Snapshot of every stage, used by "History Paint" and the history list. */
    fun stages(): List<Entry<T>> = entries.toList()

    fun push(state: T, label: String) {
        while (entries.lastIndex > cursor) entries.removeAt(entries.lastIndex)
        entries.add(Entry(label, state))
        if (entries.size > limit) entries.removeAt(1)
        cursor = entries.lastIndex
    }

    /** Replaces the current state without creating a step (e.g. renaming, autosave metadata). */
    fun replaceCurrent(state: T) {
        entries[cursor] = entries[cursor].copy(state = state)
    }

    fun undo(): T {
        check(canUndo) { "Nothing to undo" }
        cursor--
        return current
    }

    fun redo(): T {
        check(canRedo) { "Nothing to redo" }
        cursor++
        return current
    }

    /** Jumps to a stage, keeping later stages available for redo. */
    fun jumpTo(stageIndex: Int): T {
        require(stageIndex in entries.indices) { "No stage $stageIndex" }
        cursor = stageIndex
        return current
    }
}
