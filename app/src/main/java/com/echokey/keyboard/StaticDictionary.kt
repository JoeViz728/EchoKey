package com.echokey.keyboard

/**
 * StaticDictionary
 *
 * A minimal, self-contained dictionary implementation.
 *
 * Seeded with the words required to prove the critical punctuation flows
 * (especially the 2-2-6-1-8-1 "can't?" contraction) plus a small set of
 * everyday words so T9 feels usable during development.
 *
 * This is deliberately tiny. A production dictionary would be larger,
 * possibly generated at build time, and/or backed by persistent storage.
 *
 * The engine only cares about:
 *   - getWordsForT9Sequence("226") → ["can", "can't", ...]
 *   - containsWord(...)
 */
class StaticDictionary : Dictionary {

    // Core lexicon. Add words here as the invisible layer needs them for
    // contraction demos or basic prediction quality.
    private val words: Set<String> = setOf(
        // Critical for the documented contraction flow
        "a", "i",
        "can", "can't",
        "tackle", "tack", "take",
        "you", "your", "you're",
        "it", "it's", "im", "i'm",
        "do", "don't", "we", "we'll", "she", "she's",
        "hello", "help", "good", "great", "yes", "no",
        "the", "and", "for", "with", "that", "this",
        "ok", "okay", "thanks", "thank", "please",
        "love", "like", "see", "soon", "later",
        "now", "here", "there", "where", "when", "why",
        "what", "who", "how", "are", "have", "has", "had",
        "will", "would", "could", "should", "might",
        "about", "after", "again", "all", "also", "always",
        "back", "because", "been", "before", "being",
        "better", "between", "big", "both", "but", "by",
        "came", "come", "day", "days", "did", "down",
        "each", "even", "every", "few", "first", "from",
        "get", "give", "go", "going", "got", "had", "has",
        "have", "he", "her", "here", "him", "his", "how",
        "if", "in", "into", "is", "it", "its", "just",
        "know", "last", "left", "like", "little", "long",
        "look", "made", "make", "man", "many", "may",
        "me", "men", "might", "more", "most", "much",
        "must", "my", "myself", "never", "new", "no",
        "not", "now", "of", "off", "on", "once", "one",
        "only", "or", "other", "our", "out", "over",
        "own", "part", "people", "place", "put", "right",
        "said", "same", "saw", "say", "says", "see",
        "seem", "seen", "several", "she", "should",
        "show", "side", "since", "small", "so", "some",
        "something", "still", "such", "take", "taken",
        "tell", "than", "that", "the", "their", "them",
        "then", "there", "these", "they", "thing",
        "think", "this", "those", "though", "thought",
        "three", "through", "time", "to", "today",
        "together", "told", "too", "took", "toward",
        "two", "under", "until", "up", "upon", "us",
        "use", "used", "very", "want", "was", "way",
        "we", "well", "went", "were", "what", "when",
        "where", "which", "while", "who", "whom",
        "whose", "why", "will", "with", "within",
        "without", "work", "would", "yet", "you",
        "young", "your", "yours", "yourself"
    )

    // Precomputed T9 signatures (digit strings) for fast lookup.
    private val signatureToWords: Map<String, List<String>> by lazy {
        words.groupBy { t9Signature(it) }
            .mapValues { (_, list) -> list.sortedBy { it.length } } // prefer shorter roots
    }

    private val digitForLetter: Map<Char, Char> = buildMap {
        putAll("abc".map { it to '2' })
        putAll("def".map { it to '3' })
        putAll("ghi".map { it to '4' })
        putAll("jkl".map { it to '5' })
        putAll("mno".map { it to '6' })
        putAll("pqrs".map { it to '7' })
        putAll("tuv".map { it to '8' })
        putAll("wxyz".map { it to '9' })
    }

    private fun t9Signature(word: String): String =
        word.lowercase()
            .filter { it.isLetter() }   // deliberately drops apostrophes so "can't" → "2268"
            .mapNotNull { digitForLetter[it] }
            .joinToString("")

    override fun getWordsForT9Sequence(digitSequence: String): List<String> {
        if (digitSequence.isEmpty()) return emptyList()

        // Exact signature hits first (e.g. "226" → "can", "2268" → "can't").
        val exact = signatureToWords[digitSequence].orEmpty()
        if (exact.isNotEmpty()) return exact

        // Prefix stems: user has only typed part of a longer word.
        // "366" should surface "don" (stem of "don't"), not naive "dmm".
        val n = digitSequence.length
        val stems = linkedSetOf<String>()
        for (word in words) {
            val sig = t9Signature(word)
            if (sig.length > n && sig.startsWith(digitSequence)) {
                val letters = word.filter { it.isLetter() }
                if (letters.length >= n) {
                    stems.add(letters.take(n).lowercase())
                }
            }
        }
        return stems.toList().sortedBy { it.length }
    }

    override fun containsWord(word: String): Boolean {
        return words.contains(word.lowercase())
    }

    override fun getCompletions(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        return words
            .filter { it.startsWith(p) }
            .sortedBy { it.length }
            .take(8)
    }

    /**
     * Exposed for engines that want to fall back to naive first-letter mapping
     * when the dictionary has no good match. Kept here so the mapping stays
     * consistent with the dictionary's own digit assignment.
     */
    fun firstLetterForDigit(digit: Int?): Char? = when (digit) {
        2 -> 'a'
        3 -> 'd'
        4 -> 'g'
        5 -> 'j'
        6 -> 'm'
        7 -> 'p'
        8 -> 't'
        9 -> 'w'
        else -> null
    }
}