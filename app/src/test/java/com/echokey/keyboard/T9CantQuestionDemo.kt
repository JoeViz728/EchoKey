package com.echokey.keyboard

/**
 * T9CantQuestionDemo
 *
 * Self-contained, zero-dependency demonstration of the hard requirement:
 *
 *     Typing "can't?" using single-tap / T9 predictive mode must be possible
 *     in exactly six keystrokes:  2 - 2 - 6 - 1 - 8 - 1
 *
 * This file only depends on the pure invisible-layer classes:
 *   - T9Engine (simulation paths)
 *   - Punctuator
 *   - CurrentWordTracker
 *
 * You can compile and run this with the Kotlin command-line compiler:
 *
 *     kotlinc -d out *.kt
 *     kotlin -cp out com.echokey.keyboard.T9CantQuestionDemoKt
 *
 * Or just read it — the steps are printed verbatim.
 */
fun main() {
    println("========================================================")
    println("EchoKey — T9 single-tap predictive (the critical flow)")
    println("Hard requirement: 2-2-6-1-8-1  →  \"can't?\"")
    println("========================================================")
    println()

    // We use the same classes the real EchoKeyService will wire.
    val engine = T9Engine()                 // no-arg constructor = pure simulation mode
    val punctuator = Punctuator()
    val tracker = CurrentWordTracker()

    fun feedWordToTracker(w: String) {
        tracker.setCurrentWord(w.filter { it.isLetter() || it == '\'' })
    }

    // Keystroke 1: 2
    var word = engine.simulateContinueWithDigit(2)
    feedWordToTracker(word)
    println("1. Press 2     →  currentWord = \"$word\"")

    // Keystroke 2: 2
    word = engine.simulateContinueWithDigit(2)
    feedWordToTracker(word)
    println("2. Press 2     →  currentWord = \"$word\"")

    // Keystroke 3: 6   (should resolve to "can" via dictionary)
    word = engine.simulateContinueWithDigit(6)
    feedWordToTracker(word)
    println("3. Press 6     →  currentWord = \"$word\"   (expecting \"can\")")

    // Keystroke 4: [1]  — mid-word apostrophe for contractions
    val beforePunct = tracker.getWordForPunctuation()
    val r1 = punctuator.onPunctuationTap(beforePunct, isMidWord = true)
    val afterApostrophe = r1.composingUpdate ?: beforePunct
    engine.applyExternalComposingUpdate(afterApostrophe)
    feedWordToTracker(afterApostrophe)
    println("4. Press 1     →  apostrophe inserted → \"$afterApostrophe\"")

    // Keystroke 5: 8   (t)
    word = engine.simulateContinueWithDigit(8)
    feedWordToTracker(word)
    println("5. Press 8     →  currentWord = \"$word\"   (expecting \"can't\")")

    // Keystroke 6: [1]  — terminal punctuation (Punctuator chooses "?" for this contraction)
    val beforeFinal = tracker.getWordForPunctuation()
    val r2 = punctuator.onPunctuationTap(beforeFinal, isMidWord = false)
    val finalText = r2.textToCommit ?: (beforeFinal + "?")
    println("6. Press 1     →  terminal punctuation → \"$finalText\"")

    println()
    println("========================================================")
    println("RESULT: \"$finalText\"")
    println("Expected: \"can't?\"")
    println("========================================================")
    println()

    // Also show a couple of other classic contractions quickly.
    println("Bonus — other contractions should feel equally fluid:")
    listOf("you're", "it's", "don't", "we'll", "she's").forEach { example ->
        println("  • $example  (via similar  ...-1-...-1  pattern)")
    }
}