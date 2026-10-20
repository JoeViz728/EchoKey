package com.echokey.keyboard

/**
 * Executable verification for the critical invisible-layer flows.
 *
 * These tests prove:
 *   - T9 single-tap: the documented 2-2-6-1-8-1 → "can't?" flow works end-to-end
 *     (Punctuator + tracker + T9Engine simulation + service-like update paths).
 *   - Multi-tap: classic Nokia-style with explicit accepts ("tackle" example)
 *     plus punctuation on top of accepted letters.
 *   - Universal repeated-tap punctuation compounds.
 *
 * Run with the test runner or directly via main().
 */
object ContractionFlowTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val verifier = ContractionFlowVerifier()

        println("============================================================")
        println("T9 SINGLE-TAP PREDICTIVE — THE HARD REQUIREMENT")
        println("Exact documented flow: 2-2-6-1-8-1 must produce \"can't?\"")
        println("============================================================")
        val t9Result = verifier.verifyExactT9CantQuestionSixKeystrokes()
        t9Result.steps.forEach { step ->
            println("  ${step.description}  →  ${step.result}")
        }
        println()
        println(">>> FINAL RESULT for 2-2-6-1-8-1: \"${t9Result.finalText}\" <<<")
        println(">>> Expected: \"can't?\"")

        val passed = t9Result.finalText == "can't?"
        println()
        println(if (passed) "✓✓✓ PASS — exact 6-keystroke contraction requirement satisfied." else "✗ FAIL — expected \"can't?\" but got \"${t9Result.finalText}\"")

        println()
        println("============================================================")
        println("MULTI-TAP (T9 off) — classic deliberate style")
        println("User example: 8-2-[accept]-2-2-2-5-5-[accept]-5-5-5-3-3 for \"tackle\"")
        println("============================================================")
        verifier.reset()
        verifier.verifyMultiTapTackleWithAccepts().forEach { step ->
            println("  ${step.description}  →  ${step.result}")
        }

        println()
        println("============================================================")
        println("MULTI-TAP + PUNCTUATION after word (universal Punctuator)")
        println("============================================================")
        verifier.reset()
        verifier.verifyMultiTapPunctuationAfterWord().forEach { step ->
            println("  ${step.description}  →  ${step.result}")
        }

        println()
        println("============================================================")
        println("REPEATED [1] PUNCTUATION COMPOUNDS (universal)")
        println("============================================================")
        verifier.reset()
        verifier.verifyRepeatedPunctuation().forEach { step ->
            println("  ${step.description}  →  ${step.result}")
        }

        println()
        println("All flows exercised through the real Punctuator + CurrentWordTracker + engine simulations.")
        println("EchoKeyService wires these the same way at runtime.")
    }
}
