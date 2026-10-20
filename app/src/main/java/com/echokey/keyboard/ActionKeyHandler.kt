package com.echokey.keyboard

// These imports are satisfied at compile time by either:
// - the real Android SDK (full Gradle/Android build), or
// - the tiny PureAndroidStubs.kt + android/text/InputType.kt (pure kotlinc verification).
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.InputType

/**
 * ActionKeyHandler
 *
 * Handles the bottom-right action key (enter / send / go / next / done / search, etc.).
 *
 * Decided behavior (Critical Area #1):
 * - Context-aware via EditorInfo.imeOptions + actionLabel.
 * - When a specific action is available (Send, Go, Next, Search, Done...), that is the **primary** single-tap behavior.
 * - If the field supports newlines and no overriding action is present, single-tap inserts a newline.
 * - When an action is the primary behavior, **long-press** on the key inserts a literal newline.
 *
 * The handler tells the caller what to do; the service performs the actual commit or send.
 */
class ActionKeyHandler {

    data class ActionResult(
        val performEditorAction: Int? = null,   // e.g. EditorInfo.IME_ACTION_SEND, or null
        val insertNewline: Boolean = false,
        val label: String = ""                  // For UI to show appropriate icon/text later
    )

    /**
     * Given the current EditorInfo, decide what a single-tap on the action key should do.
     */
    fun onActionKeyTap(editorInfo: EditorInfo?): ActionResult {
        if (editorInfo == null) {
            return ActionResult(insertNewline = true, label = "newline")
        }

        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION

        return when (action) {
            EditorInfo.IME_ACTION_SEND -> ActionResult(
                performEditorAction = EditorInfo.IME_ACTION_SEND,
                label = "send"
            )
            EditorInfo.IME_ACTION_GO -> ActionResult(
                performEditorAction = EditorInfo.IME_ACTION_GO,
                label = "go"
            )
            EditorInfo.IME_ACTION_SEARCH -> ActionResult(
                performEditorAction = EditorInfo.IME_ACTION_SEARCH,
                label = "search"
            )
            EditorInfo.IME_ACTION_NEXT -> ActionResult(
                performEditorAction = EditorInfo.IME_ACTION_NEXT,
                label = "next"
            )
            EditorInfo.IME_ACTION_DONE -> ActionResult(
                performEditorAction = EditorInfo.IME_ACTION_DONE,
                label = "done"
            )
            EditorInfo.IME_ACTION_PREVIOUS -> ActionResult(
                performEditorAction = EditorInfo.IME_ACTION_PREVIOUS,
                label = "previous"
            )
            else -> {
                // Default: if the field allows newlines, insert one.
                // Many single-line fields will have NONE here.
                val supportsNewline = (editorInfo.inputType and
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                    (editorInfo.inputType and
                    InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0

                if (supportsNewline) {
                    ActionResult(insertNewline = true, label = "newline")
                } else {
                    // Fallback to "done" style
                    ActionResult(performEditorAction = EditorInfo.IME_ACTION_DONE, label = "done")
                }
            }
        }
    }

    /**
     * Long-press on the action key when a non-newline action is primary.
     * Should insert a literal newline.
     */
    fun onActionKeyLongPressForNewline(): ActionResult {
        return ActionResult(insertNewline = true, label = "newline")
    }

    /**
     * Convenience: actually perform the action on the connection.
     * Call this from the service after deciding.
     */
    fun performAction(ic: InputConnection, result: ActionResult) {
        when {
            result.performEditorAction != null -> {
                ic.performEditorAction(result.performEditorAction)
            }
            result.insertNewline -> {
                ic.commitText("\n", 1)
            }
        }
    }
}
