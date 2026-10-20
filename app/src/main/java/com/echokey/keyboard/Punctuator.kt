package com.echokey.keyboard

/**
 * Punctuator
 *
 * The single source of truth for the [1] key's primary everyday behavior:
 * smart punctuation insertion after words AND mid-word for contractions.
 *
 * Design requirements (verbatim from decisions):
 * - Primary purpose of single-tapping [1] after a word is punctuation.
 * - Suggestions bar shows contextually good options (you., you,, you?, you!, you')
 * - Repeated taps on [1] yield compounds: you...  you???  you!!!  you?!?
 * - Must be extremely fluid for contractions in BOTH multi-tap and T9:
 *     "can't?" == 2-2-6-1-8-1   (c a n ' t ?)
 * - Same for you're, it's, I'm, don't, we'll, she's, etc.
 *
 * This class is intentionally pure / side-effect free where possible.
 * The engines (or service) call into it and then perform the actual commit/setComposing.
 */
class Punctuator {

    /**
     * Result of a punctuation action.
     * The caller decides whether to commit immediately or update composing.
     */
    data class Result(
        val textToCommit: String? = null,
        val composingUpdate: String? = null,
        val shouldClearComposingFirst: Boolean = false
    )

    // State for repeated-tap punctuation compounds.
    // We track what the last punctuation action was so that repeated [1] can extend it.
    private var lastPunctAction: PunctAction? = null

    private enum class PunctAction {
        APOSTROPHE,
        TERMINAL,      // . ? !
        ELLIPSIS,      // ...
        COMPOUND       // ???  !!!  ?!?  etc.
    }

    /**
     * Called when the user taps the [1] key.
     *
     * @param precedingWord the text of the current word being built (before this tap).
     *                      Engines are responsible for providing the best available view of "current word".
     *                      Can be empty if we are not in the middle of a word.
     * @param isMidWord     true if we are still composing a word (no space or punctuation committed yet after letters).
     */
    fun onPunctuationTap(
        precedingWord: String,
        isMidWord: Boolean
    ): Result {
        // Case 1: Mid-word with no apostrophe yet → insert apostrophe for contractions.
        // If the word already contains an apostrophe ("can'", "can't"), a further [1]
        // is terminal punctuation ("can't?") — never a second apostrophe.
        if (isMidWord && precedingWord.isNotEmpty() && !precedingWord.contains('\'')) {
            lastPunctAction = PunctAction.APOSTROPHE
            return Result(composingUpdate = precedingWord + "'")
        }

        // Case 2: After a word (or at start of a new "punctuation sequence").
        // We start with the most common single punctuation or offer via suggestions.
        // For the direct commit path we choose a sensible default.
        // The UI layer will usually show suggestions and let the user pick or just accept default on tap.

        // If the last action was punctuation, treat this as a repeated-tap compound request.
        when (lastPunctAction) {
            PunctAction.TERMINAL,
            PunctAction.ELLIPSIS,
            PunctAction.COMPOUND -> {
                // Extend the previous punctuation.
                val extended = extendPunctuation(precedingWord)
                lastPunctAction = PunctAction.COMPOUND
                return Result(textToCommit = extended, shouldClearComposingFirst = true)
            }
            else -> {
                // First punctuation tap after a word.
                // Heuristic: contractions (contain apostrophe) very often want "?" or "!" next.
                // We bias toward "?" for common cases like "can't", "don't", "you're", etc.
                // The suggestions bar will offer the full palette; direct tap gets a good default.
                val terminal = chooseFirstTerminalPunctuation(precedingWord)
                lastPunctAction = PunctAction.TERMINAL
                return Result(textToCommit = precedingWord + terminal, shouldClearComposingFirst = true)
            }
        }
    }

    /**
     * Special path: user explicitly wants an apostrophe (e.g. via long-press target or explicit action).
     * Still useful for possessives and certain contractions where the engine didn't auto-insert.
     */
    fun onExplicitApostrophe(precedingWord: String): Result {
        lastPunctAction = PunctAction.APOSTROPHE
        return Result(textToCommit = precedingWord + "'", shouldClearComposingFirst = true)
    }

    /**
     * Called when the user picks a specific punctuation suggestion from the bar
     * (e.g. they chose "you?" instead of letting the default go through).
     */
    fun onPunctuationSuggestionChosen(
        fullSuggestedText: String   // e.g. "can't?" or "you!!!"
    ): Result {
        lastPunctAction = classifyFromSuggestion(fullSuggestedText)
        return Result(textToCommit = fullSuggestedText, shouldClearComposingFirst = true)
    }

    /**
     * Called on space, letter, or explicit word commit. Resets repeated-punctuation state.
     */
    fun onWordBoundary() {
        lastPunctAction = null
    }

    // ------------------------------------------------------------------
    // Internal helpers for repeated-tap magic
    // ------------------------------------------------------------------

    private fun chooseFirstTerminalPunctuation(word: String): String {
        val lower = word.lowercase()
        // Common contractions and questions strongly prefer "?".
        if (lower.endsWith("'t") || lower.endsWith("'re") || lower.endsWith("'m") ||
            lower.endsWith("'s") || lower.endsWith("'ll") || lower.endsWith("'ve") ||
            lower.endsWith("'d") || lower.contains("can") || lower.contains("don") ||
            lower.contains("won") || lower.contains("would") || lower.contains("could") ||
            lower.contains("should") || lower.contains("is it") || lower.contains("are you") ||
            lower.startsWith("who") || lower.startsWith("what") || lower.startsWith("where") ||
            lower.startsWith("when") || lower.startsWith("why") || lower.startsWith("how")) {
            return "?"
        }
        // Friendly defaults for statements.
        return "."
    }

    private fun extendPunctuation(currentTextBeforePunct: String): String {
        // currentTextBeforePunct here is the word without the punctuation we are about to extend.
        // In practice the engine will pass the word part.

        // Simple but effective rules for the examples the user gave:
        // "you."   + [1] → "you.."
        // "you.."  + [1] → "you..."
        // "you..." + [1] → "you...."?  (or we could stop). For now we allow extension.
        //
        // Better: detect the last trailing punctuation cluster and extend it intelligently.

        val trailing = currentTextBeforePunct.takeLastWhile { it in ".,!?;:'\"()[]{}" }
        val base = currentTextBeforePunct.dropLast(trailing.length)

        return when {
            trailing.isEmpty() -> base + "."
            trailing == "."   -> base + ".."
            trailing == ".."  -> base + "..."
            trailing == "..." -> base + "...."
            trailing.endsWith("?") -> base + trailing + "?"
            trailing.endsWith("!") -> base + trailing + "!"
            trailing.endsWith(".") -> base + trailing + "."
            else -> base + trailing + "."
        }
    }

    private fun classifyFromSuggestion(s: String): PunctAction {
        val punct = s.takeLastWhile { !it.isLetterOrDigit() }
        return when {
            punct.contains("'") -> PunctAction.APOSTROPHE
            punct == "..." || punct.startsWith("...") -> PunctAction.ELLIPSIS
            punct.count { it == '?' } >= 2 || punct.count { it == '!' } >= 2 -> PunctAction.COMPOUND
            punct.any { it in ".?!" } -> PunctAction.TERMINAL
            else -> PunctAction.TERMINAL
        }
    }

    // ------------------------------------------------------------------
    // Future extension points (invisible back-end only)
    // ------------------------------------------------------------------
    // - Context-aware ranking for suggestions (frequency, open parens/quotes, etc.)
    // - Personal usage model
    // - Different behavior in URL fields (already partially handled via long-press popup elevation)
}
