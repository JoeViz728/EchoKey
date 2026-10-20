package com.echokey.keyboard

/**
 * CurrentWordTracker
 *
 * Invisible helper that maintains the best possible view of "the word we are currently building".
 *
 * Critical for the [1] key punctuation contract:
 *   - Mid-word apostrophe insertion for contractions ("can" + [1] → "can'")
 *   - Terminal punctuation after words
 *   - Repeated-tap compounds ("you!!!")
 *
 * The tracker is fed explicitly by the engines via the service.
 * Engines call updateCurrentWordComposing(...) which syncs both the visible
 * composing region and this tracker.
 *
 * The service also has a fallback reader from the actual InputConnection.
 */
class CurrentWordTracker {

    private var buffer = StringBuilder()

    val currentWord: String
        get() = buffer.toString()

    val isBuildingWord: Boolean
        get() = buffer.isNotEmpty()

    /**
     * Set the authoritative current word string (letters + apostrophes only).
     * Used by the service after engines provide prefix + suffix.
     */
    fun setCurrentWord(word: String) {
        buffer.clear()
        val clean = word.filter { it.isLetter() || it == '\'' }
        buffer.append(clean)
    }

    /**
     * Append a single accepted letter (used when engines explicitly commit
     * a letter into the current word before starting the next).
     */
    fun appendLetter(letter: Char) {
        if (letter.isLetter() || letter == '\'') {
            buffer.append(letter)
        }
    }

    /**
     * Append apostrophe for mid-word contractions. Idempotent if already ends with one.
     */
    fun appendApostrophe() {
        if (buffer.isNotEmpty() && !buffer.endsWith('\'')) {
            buffer.append('\'')
        }
    }

    fun commitPunctuation() {
        buffer.clear()
    }

    fun commitWordBoundary() {
        buffer.clear()
    }

    fun backspaceOne(): Int {
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.length - 1)
        }
        return buffer.length
    }

    fun reset() {
        buffer.clear()
    }

    fun getWordForPunctuation(): String = currentWord
}
