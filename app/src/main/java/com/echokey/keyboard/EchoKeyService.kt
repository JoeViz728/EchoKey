package com.echokey.keyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * EchoKeyService — the main InputMethodService.
 *
 * This is the "invisible" core. It owns:
 * - Current editor state (EditorInfo)
 * - Private / password mode detection
 * - Input mode (multi-tap vs T9)
 * - The active input engine (responsible for composing + committing)
 * - High-level key event routing
 *
 * The UI layer (keyboard view) will later call into this service with
 * high-level events: letterKey(keyCode), punctuationKey(), backspace(), etc.
 *
 * We keep all visible layout decisions OUT of this class.
 */
class EchoKeyService : InputMethodService() {

    // Current connection to the target text field.
    // In headless verification this is pre-set via the internal attachTestInputConnection() hook.
    private var currentInputConnection: InputConnection? = null

    // When a test/verification connection has been attached via the internal hook,
    // we must never let onStartInput or framework callbacks replace it.
    private var testConnectionAttached: Boolean = false

    // The EditorInfo for the currently focused field.
    private var currentEditorInfo: EditorInfo? = null

    // Whether we are in a private / incognito / password context.
    private var isPrivateContext: Boolean = false

    // Active input mode. Users can toggle; password fields force multi-tap.
    enum class InputMode { MULTI_TAP, T9 }
    private var inputMode: InputMode = InputMode.T9
    private var userPreferredMode: InputMode = InputMode.T9 // restored after leaving password fields

    // For pure multi-tap (T9 off): user preference for accepting the current cycling
    // letter before continuing on the same physical key (the classic "abc" / "tackle" problem).
    enum class MultiTapAcceptStyle { TIMEOUT, EXPLICIT_NEXT }
    private var multiTapAcceptStyle: MultiTapAcceptStyle = MultiTapAcceptStyle.TIMEOUT

    fun setMultiTapAcceptStyle(style: MultiTapAcceptStyle) {
        multiTapAcceptStyle = style
        // Future: persist + notify UI
    }

    fun getMultiTapAcceptStyle(): MultiTapAcceptStyle = multiTapAcceptStyle

    // ------------------------------------------------------------------
    // Snapshot for the (future) visible keyboard view
    // ------------------------------------------------------------------

    /**
     * Small, immutable snapshot of state the visible layer needs.
     * The keyboard view (when it exists) will call getSnapshot() on every relevant change
     * or on a timer to decide what to draw.
     */
    data class Snapshot(
        val mode: InputMode,
        val isPrivate: Boolean,
        val shift: ShiftState,
        val composingText: String,
        val multiTapCycleChars: String?,   // "abc" etc. while cycling
        val multiTapCycleIndex: Int,
        val actionLabel: String,            // "accept" | "send" | "go" | "newline" | ...
        val suggestions: List<String>
    )

    fun getSnapshot(): Snapshot {
        val mt = currentEngine as? MultiTapEngine
        val t9 = currentEngine as? T9Engine

        val cycleChars = mt?.getCurrentCycleOptions()
        val cycleIdx = mt?.getCurrentCycleIndex() ?: 0

        val comp = when {
            mt != null -> mt.getCurrentWordLettersForSnapshot() + mt.getCurrentComposingForSnapshot()
            t9 != null -> t9.getCurrentWord()
            else -> ""
        }

        return Snapshot(
            mode = inputMode,
            isPrivate = isPrivateContext,
            shift = shiftState,
            composingText = comp,
            multiTapCycleChars = cycleChars,
            multiTapCycleIndex = cycleIdx,
            actionLabel = getCurrentActionLabel(),
            suggestions = getCurrentSuggestions(3)
        )
    }

    // The engine that actually handles letter entry, composing, and punctuation.
    // Starts as a harmless no-op; onStartInput always calls recreateEngine() immediately.
    private var currentEngine: InputEngine = NoOpEngine()

    // Invisible handlers (pure logic where possible)
    private val punctuator = Punctuator()
    private val wordTracker = CurrentWordTracker()
    private val backspaceHandler = BackspaceHandler()
    private val actionKeyHandler = ActionKeyHandler()

    // Simple flag for shift state (one-shot, caps lock, etc.). UI will drive this.
    private var shiftState: ShiftState = ShiftState.OFF

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // Future: load user preferences (inputMode, etc.)
    }

    /** On-device 12-key UI. Kept as a field so we can refresh it on start/selection. */
    private var keyboardView: EchoKeyKeyboardView? = null

    override fun onCreateInputView(): android.view.View {
        val view = EchoKeyKeyboardView(this)
        view.attachService(this)
        keyboardView = view
        return view
    }

    /** Push latest snapshot (suggestions, mode, action label) into the visible keyboard. */
    internal fun refreshKeyboardUi() {
        keyboardView?.refreshFromService()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute

        // Real IME path: always take the framework connection for this field.
        // Headless verification: never overwrite a deliberately attached test connection.
        if (!testConnectionAttached) {
            currentInputConnection = getCurrentInputConnection()
        }

        evaluateContext()

        // Password / sensitive fields force multi-tap and disable learning/suggestions.
        if (isPrivateContext) {
            forceMultiTapForPrivacy()
        } else {
            // Restore user's preferred mode when leaving private fields.
            inputMode = userPreferredMode
        }

        // Create the appropriate engine for the current mode.
        recreateEngine()

        refreshKeyboardUi()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Proper hygiene: finish engine state + tell the framework there is no more composing text.
        finishComposingRegionCompletely()

        if (!testConnectionAttached) {
            currentInputConnection = null
        }
        currentEditorInfo = null
        isPrivateContext = false
        wordTracker.reset()
        punctuator.onWordBoundary()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // View lifecycle (even though onCreateInputView currently returns null)
    // ------------------------------------------------------------------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Framework may hand us a fresh connection when the view starts.
        if (!testConnectionAttached) {
            currentInputConnection = getCurrentInputConnection() ?: currentInputConnection
        }
        refreshKeyboardUi()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Make sure we never leave composing text when the keyboard view is going away.
        currentEngine.finishComposing()
        try {
            currentInputConnection?.finishComposingText()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Selection / cursor movement from the target field (critical for real IME)
    // ------------------------------------------------------------------

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // If the selection/cursor moved outside our current composing region,
        // or the user (or the app) moved the caret, we must finish our composing state.
        //
        // This is vital for:
        //   - Spacebar trackpad cursor movement (future)
        //   - User tapping elsewhere in the field
        //   - External selection changes (handles, voice, etc.)
        //
        // We use the central hygiene path for consistency.

        val ic = currentInputConnection ?: return

        val composingActive = candidatesStart >= 0 && candidatesEnd >= 0
        val cursorLeftComposing = composingActive &&
            (newSelStart < candidatesStart || newSelStart > candidatesEnd)

        if (cursorLeftComposing || (!composingActive && (newSelStart != oldSelStart || newSelEnd != oldSelEnd))) {
            finishComposingRegionCompletely()
        }
    }

    // ------------------------------------------------------------------
    // Context evaluation (privacy, passwords, editor capabilities)
    // ------------------------------------------------------------------

    private fun evaluateContext() {
        val ei = currentEditorInfo ?: return
        val inputType = ei.inputType

        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val isPassword = when (variation) {
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }

        // Also respect private/IME_FLAG_NO_PERSONALIZED_LEARNING
        val noPersonalization = (ei.imeOptions and
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        isPrivateContext = isPassword || noPersonalization
    }

    private fun forceMultiTapForPrivacy() {
        userPreferredMode = inputMode
        inputMode = InputMode.MULTI_TAP
        // Future: tell UI to hide T9 toggle and suggestions.
    }

    // ------------------------------------------------------------------
    // Engine management
    // ------------------------------------------------------------------

    private fun recreateEngine() {
        // Commit anything in progress before switching engines (hygiene).
        currentEngine.finishComposing()

        currentEngine = when (inputMode) {
            InputMode.MULTI_TAP -> MultiTapEngine(this)
            InputMode.T9 -> T9Engine(this)
        }
    }

    fun setInputMode(newMode: InputMode) {
        if (isPrivateContext && newMode == InputMode.T9) {
            // Never allow T9 in private contexts.
            return
        }
        if (inputMode == newMode) return

        userPreferredMode = newMode
        inputMode = newMode
        recreateEngine()
        // Future: persist preference + notify UI
    }

    fun getCurrentInputMode(): InputMode = inputMode

    /**
     * Returns true when the active engine is in the middle of a classic multi-tap
     * letter cycle on the same physical key (e.g. user has tapped "2" once or twice
     * but has not yet accepted the letter).
     *
     * When true, the primary action of the action/enter key should temporarily
     * become "accept current letter" (explicit NEXT) instead of the normal
     * EditorInfo action. Long-press on the action key can still produce newline
     * or the normal action in that moment.
     */
    fun isMultiTapCycleActive(): Boolean {
        return currentEngine.isInMultiTapCycle()
    }

    /**
     * Returns up to [max] current word candidates / suggestions for the suggestions bar.
     *
     * In T9 mode this returns real predictions from the dictionary for the digits typed so far.
     * In multi-tap mode it returns at minimum the word built so far (for display or learning).
     *
     * In private mode this always returns empty (no suggestions).
     */
    fun getCurrentSuggestions(max: Int = 3): List<String> {
        if (isPrivateContext) return emptyList()
        return currentEngine.getCurrentCandidates(max)
    }

    // ------------------------------------------------------------------
    // Public API for the future UI layer (back-end entry points)
    // These will be the main "invisible" contract.
    // ------------------------------------------------------------------

    /**
     * A letter key was pressed (2-9).
     * The engine decides what to do based on current mode and timing.
     */
    fun onLetterKey(keyDigit: Int) {
        val ic = currentInputConnection ?: return

        currentEngine.handleLetter(keyDigit, ic, shiftState)

        if (shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
        }
    }

    /**
     * Called by the (future) UI layer when the multi-tap timeout has elapsed
     * for the current key (user stopped rapid-tapping the same digit).
     *
     * This finalizes the current cycling letter into the word while keeping
     * the word open for punctuation or continuation.
     */
    fun onMultiTapTimeout() {
        val engine = currentEngine as? MultiTapEngine ?: return
        engine.commitCurrentLetterOnTimeout()
    }

    /**
     * Explicit "accept current letter / NEXT" command while in multi-tap mode.
     * The user (or UI) can bind this to a dedicated action or temporarily repurpose
     * the action key while cycling letters on the same physical key.
     */
    fun onMultiTapAccept() {
        val engine = currentEngine as? MultiTapEngine ?: return
        engine.acceptCurrentLetterExplicit()
    }

    /**
     * The [1] key was tapped.
     * Primary purpose: smart punctuation after (or mid) word.
     * Also supports repeated taps for !!! ??? ... etc. and apostrophe for contractions.
     *
     * Hygiene rule: terminal punctuation (textToCommit) is a hard word boundary.
     * Mid-word apostrophe keeps the word open (just updates composing + tracker via engine).
     */
    fun onPunctuationKey() {
        val ic = currentInputConnection ?: return

        // Multi-tap: lock in the cycling letter into the word, but keep composing open
        // so mid-word apostrophe can still extend the same word.
        if (currentEngine.isInMultiTapCycle()) {
            (currentEngine as? MultiTapEngine)?.acceptCurrentLetterExplicit()
        }

        // Authoritative word BEFORE any commit. Do not finishComposing first —
        // that would commit the bare word and break contractions / double-insert
        // when we later commit "can't?".
        var word = wordTracker.getWordForPunctuation()
        if (word.isEmpty()) {
            word = (currentEngine as? T9Engine)?.getCurrentWord().orEmpty()
        }
        if (word.isEmpty()) {
            word = getBestCurrentWordForPunctuation()
        }
        val isMid = word.isNotEmpty()

        val result = punctuator.onPunctuationTap(word, isMid)

        when {
            result.textToCommit != null -> {
                // commitText replaces any active composing region with the full
                // "word+punct" string. Reset engine state without a second commit.
                commitText(result.textToCommit)
                currentEngine.notifyExternalCommit()
                wordTracker.commitWordBoundary()
                punctuator.onWordBoundary()
            }
            result.composingUpdate != null -> {
                // Mid-word apostrophe insertion (the key to contractions).
                setComposingText(result.composingUpdate)
                currentEngine.notifyComposingUpdatedForPunctuation(result.composingUpdate)
            }
        }

        currentEngine.handlePunctuation(ic)
    }

    /**
     * Space key (0) tapped.
     *
     * The engine is responsible for:
     *   - Accepting/committing any in-progress letter or predicted word
     *   - Inserting the space character
     *   - Resetting its internal word/letter state
     *
     * After the engine does its work we reset the service-level punctuation tracker
     * so the next [1] tap starts fresh.
     */
    fun onSpaceKey() {
        val ic = currentInputConnection ?: return

        currentEngine.handleSpace(ic)

        // Space always creates a word boundary for punctuation/contraction logic.
        wordTracker.commitWordBoundary()
        punctuator.onWordBoundary()
    }

    /**
     * Long-press on the space/0 key → insert the digit "0" directly.
     *
     * This must:
     *   - Finish any active multi-tap cycle or T9 prediction first (hygiene)
     *   - Insert the literal character '0'
     *   - Leave the word/punctuation state in a clean boundary
     *
     * Same semantics as long-press on 1-9 via onDigitDirect.
     */
    fun onSpaceKeyLongPress() {
        val ic = currentInputConnection ?: return

        // Long-press digit is always a hard boundary.
        finishComposingRegionCompletely()

        // Insert the digit directly (bypasses engines' letter logic).
        ic.commitText("0", 1)

        // Ensure trackers know we just committed something that is not a word letter.
        wordTracker.commitWordBoundary()
        punctuator.onWordBoundary()
    }

    /**
     * Backspace single tap.
     * Full detailed behavior is implemented via BackspaceHandler.
     */
    fun onBackspace() {
        val ic = currentInputConnection ?: return

        val (hasSelection, beforeCursor, afterCursor) = getSelectionInfo(ic)

        val action = backspaceHandler.onBackspaceDown(hasSelection, System.currentTimeMillis())

        performDeletionAction(action, ic, beforeCursor, afterCursor)
    }

    /**
     * Called repeatedly while backspace is held (from UI timer or motion).
     */
    fun onBackspaceHeld(isSlidingLeft: Boolean) {
        val ic = currentInputConnection ?: return
        val (_, beforeCursor, afterCursor) = getSelectionInfo(ic)
        val action = backspaceHandler.onBackspaceHeld(System.currentTimeMillis(), isSlidingLeft)
        performDeletionAction(action, ic, beforeCursor, afterCursor)
    }

    fun onBackspaceUp() {
        backspaceHandler.onBackspaceUp()
    }

    /**
     * Query selection + surrounding text using the most reliable signals available.
     * Returns (hasSelection, textBeforeCursor, textAfterCursor).
     *
     * This feeds BackspaceHandler so it can decide "delete selection first".
     */
    private fun getSelectionInfo(ic: InputConnection): Triple<Boolean, String, String> {
        val before = ic.getTextBeforeCursor(96, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(32, 0)?.toString() ?: ""

        // Most reliable way to know if there is a real selection (handles, etc.).
        val extracted = try {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
                token = 0
                flags = 0
            }, 0)
        } catch (_: Exception) { null }

        val hasSelection = extracted != null && extracted.selectionStart != extracted.selectionEnd

        return Triple(hasSelection, before, after)
    }

    private fun performDeletionAction(
        action: BackspaceHandler.DeletionAction,
        ic: InputConnection,
        beforeCursor: String,
        afterCursor: String
    ) {
        when (action) {
            BackspaceHandler.DeletionAction.DELETE_SELECTION -> {
                // Selection deletion is always a hard boundary.
                // Commit empty over the selection (framework usually handles the actual range).
                ic.commitText("", 1)
                // Full reset of word + punctuation state — a selection could have spanned multiple words.
                finishComposingRegionCompletely()
                currentEngine.notifyExternalCommit()
            }

            BackspaceHandler.DeletionAction.DELETE_ONE_CHAR -> {
                // Engine gets first chance (multi-tap can drop the cycling letter without touching the field).
                // If the engine ate the cycling/predicted letter internally, it returns true and we must
                // NOT also backspace the tracker (no real field character was removed).
                val engineConsumed = currentEngine.handleBackspace(ic)
                if (!engineConsumed) {
                    wordTracker.backspaceOne()
                }
            }

            BackspaceHandler.DeletionAction.DELETE_ONE_WORD -> {
                // Whole-word deletion (slide left while holding backspace).
                // Must be extremely careful with composing regions.
                deleteOneWordRespectingComposing(ic, beforeCursor)
                wordTracker.commitWordBoundary()
                punctuator.onWordBoundary()
                currentEngine.notifyExternalCommit()
            }

            else -> {}
        }
        backspaceHandler.notifyDeletionPerformed()
    }

    /**
     * Delete one logical word to the left of the cursor.
     *
     * This is used exclusively for the "slide left while holding backspace" gesture.
     * It must never leave ghost composing text.
     */
    private fun deleteOneWordRespectingComposing(ic: InputConnection, beforeCursor: String) {
        // First give the engine a chance to clean any in-progress cycle or prediction.
        // This is critical for multi-tap (current cycling letter) and T9 (predicted word).
        currentEngine.finishComposing()

        // Ask the framework to finish the composing region so nothing is left behind.
        try {
            ic.finishComposingText()
        } catch (_: Exception) {}

        if (beforeCursor.isEmpty()) return

        // Walk backwards to find a word boundary (letters + apostrophe).
        var deleteCount = 0
        var i = beforeCursor.length - 1

        // Skip trailing non-word characters.
        while (i >= 0 && !beforeCursor[i].isLetterOrDigit() && beforeCursor[i] != '\'') {
            deleteCount++
            i--
        }

        // Consume the actual word.
        while (i >= 0 && (beforeCursor[i].isLetterOrDigit() || beforeCursor[i] == '\'')) {
            deleteCount++
            i--
        }

        if (deleteCount > 0) {
            ic.deleteSurroundingText(deleteCount, 0)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    /**
     * Long-press on a number key (1-9) → direct digit.
     * Always finishes any in-progress letter composing first (important for multi-tap).
     */
    fun onDigitDirect(digit: Int) {
        val ic = currentInputConnection ?: return

        // Long-press digit is a hard commit boundary for any current letter/word.
        finishComposingRegionCompletely()

        currentEngine.handleDirectDigit(digit, ic)
    }

    /**
     * A character was chosen via long-press popup target (Gboard-style).
     *
     * Examples:
     *   - Long-press 'e' → slide to 'é' → this method is called with 'é'
     *   - Long-press [1] in URL field → slide to '/' → '/'
     *
     * This always commits the exact character immediately and creates a word boundary.
     * It bypasses multi-tap cycling and T9 prediction.
     */
    fun onPopupCharChosen(ch: Char) {
        val ic = currentInputConnection ?: return

        finishComposingRegionCompletely()
        ic.commitText(ch.toString(), 1)

        // Characters from popups are treated as word terminators for punctuation purposes
        // (unless it's a letter, in which case a new word starts — but we keep it simple).
        if (!ch.isLetter() && ch != '\'') {
            wordTracker.commitWordBoundary()
            punctuator.onWordBoundary()
        } else {
            // If the user picked a letter via popup, start a new word with it.
            wordTracker.setCurrentWord(ch.toString())
        }
    }

    /**
     * Action key (bottom-right enter/send/go/etc) single tap.
     * Respects EditorInfo and the decided priority rules.
     *
     * Special multi-tap rule (per design):
     *   While the user is actively cycling letters on the same key (e.g. pressing 2 repeatedly),
     *   the primary action of this key **temporarily becomes "accept current letter" (NEXT)**.
     *   The normal EditorInfo action (Send, Go, newline, etc.) is pushed to long-press for that moment.
     *
     * T9 rule:
     *   While a T9 prediction is open (composing a predicted word), single-tap on the action key
     *   first commits the current predicted word, then performs the real EditorInfo action (Send, Go, etc).
     *   This gives the expected "type suggestion then send" behavior.
     */
    fun onActionKeyTap() {
        val ic = currentInputConnection ?: return

        // Multi-tap explicit accept takes priority over normal action while cycling.
        if (currentEngine.isInMultiTapCycle()) {
            val multiTap = currentEngine as? MultiTapEngine ?: return
            multiTap.acceptCurrentLetterExplicit()
            // After explicit accept the cycle is over; normal action behavior resumes on next tap.
            return
        }

        // T9: if we have an open prediction (digits or current word), commit it first,
        // then fall through to the real EditorInfo action.
        val t9 = currentEngine as? T9Engine
        val hasActiveT9Prediction = t9 != null &&
            (t9.getCurrentDigitSequence().isNotEmpty() || t9.getCurrentWord().isNotEmpty())

        if (hasActiveT9Prediction) {
            // Commit the predicted word cleanly (this also resets trackers/punctuator via finish path).
            finishComposingRegionCompletely()
            // Now perform the real action (Send / Go / Done / etc). The action handler will decide.
        }

        val ei = currentEditorInfo
        val result = actionKeyHandler.onActionKeyTap(ei)

        if (result.insertNewline || result.performEditorAction != null) {
            // Any action that moves focus / sends / finishes the field is a hard boundary.
            finishComposingRegionCompletely()
        } else {
            finishComposingRegionCompletely()
        }

        actionKeyHandler.performAction(ic, result)

        // After a real editor action (Send, Go, Next, Done...), make sure the engine
        // knows an external commit-like boundary happened. This keeps state clean if the
        // field stays focused or for the next field.
        if (result.performEditorAction != null) {
            currentEngine.notifyExternalCommit()
            // Also reset our own word/punct state for extra safety.
            wordTracker.commitWordBoundary()
            punctuator.onWordBoundary()
        }
    }

    /**
     * Returns the label/icon name the action key should currently show.
     *
     * When in an active multi-tap cycle, this returns "accept" (or "next") so the
     * UI can temporarily show the "accept letter" affordance instead of the normal
     * send/go/done icon.
     */
    fun getCurrentActionLabel(): String {
        if (currentEngine.isInMultiTapCycle()) {
            return "accept"
        }
        val ei = currentEditorInfo
        return actionKeyHandler.onActionKeyTap(ei).label
    }

    /**
     * Long-press on action key.
     *
     * Decided behavior:
     * - When a non-newline action is the normal primary (Send, Go, etc.), long-press inserts a literal newline.
     * - When we are in the middle of a multi-tap cycle, long-press still gives newline (the tap path is temporarily "accept").
     * - Always finishes composing cleanly.
     */
    fun onActionKeyLongPress() {
        val ic = currentInputConnection ?: return

        // Regardless of whether we are cycling letters, long-press on the action key
        // is defined to insert a real newline.
        finishComposingRegionCompletely()

        val result = actionKeyHandler.onActionKeyLongPressForNewline()
        actionKeyHandler.performAction(ic, result)
    }

    /**
     * Commit the current composing text (if any) and finish the word.
     * Usually called when user picks a suggestion or hits certain action keys.
     */
    fun commitCurrentWord() {
        finishComposingRegionCompletely()
    }

    /**
     * Commit a specific suggestion (word or emoji result) chosen from the suggestions bar.
     *
     * This is the clean path the future UI layer should use when the user taps
     * a suggestion. It replaces any current composing region with the chosen text
     * and creates a proper word boundary.
     */
    fun commitSuggestion(suggestion: String) {
        val ic = currentInputConnection ?: return
        if (suggestion.isEmpty()) return

        // Replace whatever we were composing with the chosen text.
        // finishComposingRegionCompletely will clear our internal state.
        finishComposingRegionCompletely()

        ic.commitText(suggestion, 1)

        // The chosen suggestion becomes a completed word.
        wordTracker.setCurrentWord(suggestion.filter { it.isLetter() || it == '\'' })
        // Then immediately treat it as finished for punctuation purposes.
        // Next [1] will be terminal punctuation (or start a new sequence).
        wordTracker.commitWordBoundary()
        punctuator.onWordBoundary()
    }

    /**
     * Central hygiene point.
     * Finishes any in-progress composing in the engine, clears tracker/punctuator state,
     * and tells the InputConnection there is no more composing region.
     */
    private fun finishComposingRegionCompletely() {
        currentEngine.finishComposing()

        // Keep punctuation and word tracking in sync with a hard boundary.
        wordTracker.commitWordBoundary()
        punctuator.onWordBoundary()

        val ic = currentInputConnection
        if (ic != null) {
            try {
                ic.finishComposingText()
            } catch (_: Exception) { /* best effort */ }
        }
    }

    /**
     * Shift key handling from UI.
     */
    fun setShiftState(newState: ShiftState) {
        shiftState = newState
    }

    fun getShiftState(): ShiftState = shiftState

    // ------------------------------------------------------------------
    // Helpers for engines
    // ------------------------------------------------------------------

    internal fun getCurrentEditorInfo(): EditorInfo? = currentEditorInfo

    internal fun isInPrivateContext(): Boolean = isPrivateContext

    /**
     * Convenience to commit text and clear composing.
     */
    internal fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    /**
     * Set composing text for the *current letter only* during multi-tap cycling.
     * The engine is responsible for telling us the accumulated word prefix separately
     * so the tracker (and thus [1] key punctuation) sees the full word.
     */
    internal fun setComposingText(text: String) {
        val ic = currentInputConnection ?: return
        ic.setComposingText(text, 1)
    }

    /**
     * Called by engines to keep the visible composing region and the punctuation system in sync.
     *
     * For Multi-tap:
     *   - committedPrefix = letters already accepted (e.g. "ca")
     *   - currentSuffix   = the letter currently being cycled (e.g. "n")
     *
     * For T9 (single-tap predictive):
     *   - We pass ("", predictedWord) — the whole current best word is the "suffix".
     *   - The engine may also have a committed base after mid-word apostrophe.
     *
     * The tracker is updated with the authoritative full word so that [1] punctuation
     * decisions (apostrophe for "can" → "can'", terminal after finished word) are correct.
     */
    internal fun updateCurrentWordComposing(committedPrefix: String, currentSuffix: String) {
        val ic = currentInputConnection ?: return
        val full = committedPrefix + currentSuffix
        ic.setComposingText(full, 1)

        // Authoritative sync for punctuation / contractions.
        val clean = full.filter { it.isLetter() || it == '\'' }
        wordTracker.setCurrentWord(clean)
    }

    /**
     * Best-effort authoritative word for punctuation decisions.
     * Tries the tracker first (fed by engine), then falls back to reading
     * the actual text before the cursor + current composing region.
     *
     * This makes the [1] key robust even if engine state gets out of sync.
     */
    internal fun getBestCurrentWordForPunctuation(): String {
        // Prefer the tracker's view when it has something.
        val tracked = wordTracker.getWordForPunctuation()
        if (tracked.isNotEmpty()) return tracked

        val ic = currentInputConnection ?: return ""
        // Try to read a reasonable amount of preceding text.
        val before = ic.getTextBeforeCursor(32, 0)?.toString() ?: ""
        if (before.isEmpty()) return ""

        // Take the last "word-ish" sequence (letters + apostrophe) before the cursor.
        val lastChunk = before.takeLastWhile { it.isLetter() || it == '\'' || it.isDigit() }
        return lastChunk
    }

    internal fun deleteSurroundingText(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(before, after)
    }

    // ------------------------------------------------------------------
    // Headless verification / test support (invisible layer only)
    // These hooks let us drive the real public API with a fake InputConnection.
    // ------------------------------------------------------------------

    /**
     * Attach a fake/recording InputConnection.
     * Must be called BEFORE testStartInput() for headless verification.
     */
    internal fun attachTestInputConnection(ic: InputConnection) {
        currentInputConnection = ic
        testConnectionAttached = true
    }

    /**
     * Drive onStartInput for verification without a real framework.
     * If no connection is attached yet, a minimal dummy is provided.
     */
    internal fun testStartInput(editorInfo: EditorInfo? = null) {
        val ei = editorInfo ?: EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        if (currentInputConnection == null) {
            // Headless path should call attachTestInputConnection first.
            // Provide a no-op connection so onStartInput does not NPE in isolation.
            currentInputConnection = NoOpInputConnection
        }

        onStartInput(ei, false)
    }

    internal fun testFinishInput() {
        onFinishInput()
    }

    /**
     * Test-only hook to drive onUpdateSelection from the headless driver.
     * This simulates the framework telling us the cursor/selection moved (user tapped,
     * handles, spacebar trackpad, external edit, etc.).
     */
    internal fun testOnUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int = -1,
        candidatesEnd: Int = -1
    ) {
        onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    // ------------------------------------------------------------------
    // Cursor movement (spacebar trackpad and future arrow keys feed here)
    // ------------------------------------------------------------------

    /**
     * Move the cursor/insertion point left by the given number of characters.
     * Called by the future UI layer when the user drags left on the spacebar.
     *
     * We finish any active composing region first (you can't sanely have a
     * multi-tap cycle or T9 prediction while the caret is jumping around).
     */
    fun moveCursorLeft(count: Int) {
        val ic = currentInputConnection ?: return
        if (count <= 0) return

        finishComposingRegionCompletely()

        // Use negative "before" to move left.
        // The framework interprets deleteSurroundingText(0, 0) + setSelection as movement,
        // but the clean portable way is getExtractedText + setSelection or send key events.
        // For simplicity and compatibility we use a small surrounding delete + reinsert
        // trick is wrong. Better: ask for extracted text and set a new selection.
        moveCursorBy(ic, -count)
    }

    /**
     * Move the cursor/insertion point right by the given number of characters.
     */
    fun moveCursorRight(count: Int) {
        val ic = currentInputConnection ?: return
        if (count <= 0) return

        finishComposingRegionCompletely()
        moveCursorBy(ic, count)
    }

    private fun moveCursorBy(ic: InputConnection, delta: Int) {
        try {
            val extracted = ic.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest().apply { token = 0 },
                0
            ) ?: return

            val newPos = (extracted.selectionEnd + delta)
                .coerceIn(0, extracted.text?.length ?: 0)

            ic.setSelection(newPos, newPos)
        } catch (_: Exception) {
            // Fallback: send DPAD keys (works in many fields).
            val direction = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val steps = kotlin.math.abs(delta)
            repeat(steps) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction))
            }
        }
    }

    // TODO later (invisible layer priorities):
    // - Richer EditorInfo action handling (sendKeyEvent fallbacks)
    // - Full end-to-end instrumentation tests exercising the invisible layer on device
}

// NOTE: InputEngine interface and the NoOpEngine implementation have been extracted
// to InputEngine.kt so they can be shared cleanly and used by pure-Kotlin verification
// (kotlinc without Android). The real service still references them via the same package.
