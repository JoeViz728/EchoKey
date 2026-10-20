package com.echokey.keyboard

/**
 * PureEchoKeyServiceStub
 *
 * Ultra-minimal stand-in for EchoKeyService.
 *
 * Used ONLY by the 100% pure kotlinc verification (run-pure-verification.ps1).
 *
 * The real EchoKeyService extends Android's InputMethodService and lives in the
 * main sources. The pure verifier script deliberately excludes the real service
 * file and compiles this tiny stub instead so that T9Engine (and any other
 * invisible logic that holds a nullable service reference) can resolve types.
 *
 * All methods here are no-ops because the pure verifier drives engines via their
 * simulation helpers (simulateContinueWithDigit, applyExternalComposingUpdate, etc.).
 */
open class EchoKeyService {

    internal fun updateCurrentWordComposing(committedPrefix: String, currentSuffix: String) {
        // no-op for pure verification
    }

    internal fun commitText(text: String) {
        // no-op
    }

    internal fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        // no-op
    }
}
