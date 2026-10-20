package android.view.inputmethod

/**
 * Pure verification stubs.
 *
 * These are the minimal type declarations needed so that the real
 * invisible-layer source files (T9Engine, ActionKeyHandler, etc.) can be
 * compiled by plain kotlinc on a machine that has ZERO Android SDK.
 *
 * They are ONLY used by the "pure" kotlinc verification path.
 * The real Android build uses the genuine android.jar instead.
 */

interface InputConnection {
    fun performEditorAction(actionCode: Int): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
}

open class EditorInfo {
    var imeOptions: Int = 0
    var inputType: Int = 0
    var actionLabel: CharSequence? = null

    companion object {
        const val IME_MASK_ACTION: Int = 0x000000ff
        const val IME_ACTION_UNSPECIFIED: Int = 0x00000000
        const val IME_ACTION_NONE: Int = 0x00000001
        const val IME_ACTION_GO: Int = 0x00000002
        const val IME_ACTION_SEARCH: Int = 0x00000003
        const val IME_ACTION_SEND: Int = 0x00000004
        const val IME_ACTION_NEXT: Int = 0x00000005
        const val IME_ACTION_DONE: Int = 0x00000006
        const val IME_ACTION_PREVIOUS: Int = 0x00000007
    }
}
