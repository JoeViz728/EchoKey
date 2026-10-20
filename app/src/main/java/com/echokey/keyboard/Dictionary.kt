package com.echokey.keyboard

/**
 * Dictionary
 *
 * Clean interface for word lookup / prediction.
 *
 * The invisible layer uses this for T9 (single-tap) candidate generation.
 * Multi-tap can also use it for auto-correction suggestions later.
 *
 * Goal: keep the prediction concern completely decoupled from engines and service.
 * A real implementation can be a large static list, SQLite, or learned model.
 * The interface stays tiny.
 */
interface Dictionary {

    /**
     * Given a sequence of T9 key digits (e.g. "226" for c-a-n),
     * return the best matching words, ordered from most to least preferred.
     *
     * The engine decides how many to take (usually top 1-3 for composing + bar).
     */
    fun getWordsForT9Sequence(digitSequence: String): List<String>

    /**
     * Does this dictionary contain the exact word (case-insensitive)?
     * Useful for validation / learning decisions.
     */
    fun containsWord(word: String): Boolean

    /**
     * Optional: given a partial word, suggest completions.
     * Not required for v1 punctuation flows.
     */
    fun getCompletions(prefix: String): List<String> = emptyList()
}