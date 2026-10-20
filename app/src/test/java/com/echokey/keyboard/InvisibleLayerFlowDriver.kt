package com.echokey.keyboard

import android.view.inputmethod.InputConnection

/**
 * InvisibleLayerFlowDriver
 *
 * A pure, headless driver that exercises the *real* EchoKeyService + engines
 * exactly as the future visible keyboard view will.
 *
 * No Android UI, no real InputConnection (we use a fake one that just records output).
 *
 * Use this to prove the decided behaviors:
 *   - T9 single-tap "can't?" in exactly 6 keystrokes (2-2-6-1-8-1)
 *   - Classic multi-tap with explicit accept (TIMEOUT or EXPLICIT_NEXT)
 *   - Action key temporarily becoming "accept" while multi-tap cycling
 *   - Space, long-press 0, punctuation, backspace basics
 *
 * This is the authoritative way to verify the invisible layer contract.
 */
class InvisibleLayerFlowDriver {

    // We drive the *real* EchoKeyService (this is the contract the future UI will call).
    // We keep a reference to the concrete instance so the test hooks are accessible.
    private val service = EchoKeyService()

    // Output recorded by our fake InputConnection (committed text only).
    val committedText = StringBuilder()

    // Current composing text (visible during multi-tap cycle or T9 prediction).
    private var currentComposing = ""

    // The recording IC we attached. We keep a concrete reference for reset + richer behavior.
    private lateinit var recordingIc: RecordingInputConnection

    init {
        val ic = RecordingInputConnection(committedText) { composing -> currentComposing = composing }
        recordingIc = ic

        // Critical order for headless testing:
        // 1. Attach the fake connection first.
        // 2. Then drive real onStartInput via the test hook.
        service.attachTestInputConnection(ic)
        service.testStartInput()
    }

    /**
     * Re-initialize the driver with a specific EditorInfo (useful for action key tests
     * that depend on imeOptions and inputType).
     * This finishes the current session cleanly then starts a fresh one.
     */
    fun restartWithEditorInfo(ei: android.view.inputmethod.EditorInfo) {
        service.testFinishInput()
        val ic = RecordingInputConnection(committedText) { composing -> currentComposing = composing }
        recordingIc = ic
        service.attachTestInputConnection(ic)
        service.testStartInput(ei)
    }

    // ------------------------------------------------------------------
    // High-level operations (exactly what the future UI layer will call)
    // ------------------------------------------------------------------

    fun letter(digit: Int) {
        service.onLetterKey(digit)
    }

    fun punctuation() {
        service.onPunctuationKey()
    }

    fun space() {
        service.onSpaceKey()
    }

    fun spaceLongPress() {
        service.onSpaceKeyLongPress()
    }

    fun digitLongPress(d: Int) {
        service.onDigitDirect(d)
    }

    fun backspace() {
        service.onBackspace()
    }

    /** Simulate continued holding of backspace (e.g. from a timer loop in real UI). */
    fun backspaceHeld(isSlidingLeft: Boolean = false) {
        service.onBackspaceHeld(isSlidingLeft)
    }

    fun backspaceUp() {
        service.onBackspaceUp()
    }

    fun actionTap() {
        service.onActionKeyTap()
    }

    fun actionLongPress() {
        service.onActionKeyLongPress()
    }

    fun multiTapTimeout() {
        service.onMultiTapTimeout()
    }

    fun multiTapAccept() {
        service.onMultiTapAccept()
    }

    fun setMode(mode: EchoKeyService.InputMode) {
        service.setInputMode(mode)
    }

    fun setAcceptStyle(style: EchoKeyService.MultiTapAcceptStyle) {
        service.setMultiTapAcceptStyle(style)
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    fun getCommitted(): String = committedText.toString()

    fun isMultiTapCycleActive() = service.isMultiTapCycleActive()

    fun getCurrentSuggestions() = service.getCurrentSuggestions(3)

    fun getSnapshot() = service.getSnapshot()

    // ------------------------------------------------------------------
    // Cursor movement (spacebar trackpad will later call these on the service)
    // ------------------------------------------------------------------

    fun moveCursorLeft(count: Int) {
        service.moveCursorLeft(count)
    }

    fun moveCursorRight(count: Int) {
        service.moveCursorRight(count)
    }

    /**
     * Simulate the framework calling onUpdateSelection (external cursor move, tap, handles, etc.).
     * This is the path that must trigger finishComposingRegionCompletely when the caret leaves composing.
     */
    fun simulateExternalSelectionChange(
        oldStart: Int = -1,
        oldEnd: Int = -1,
        newStart: Int,
        newEnd: Int
    ) {
        // Best effort: drive the real service hook.
        service.testOnUpdateSelection(oldStart, oldEnd, newStart, newEnd)
    }

    fun getBufferText(): String = recordingIc.getCurrentBufferText()
    fun getSelection(): Pair<Int, Int> = recordingIc.getSelection()
    fun getLastEditorAction(): Int? = recordingIc.getLastPerformedEditorAction()

    /**
     * Simulate an external insertion (voice, paste, autofill, etc.) while we have active composing.
     * The invisible layer must finish any cycle/prediction cleanly and reset punctuation state.
     */
    fun simulateExternalInsertion(text: String) {
        // Make it appear in the field buffer.
        committedText.append(text)
        recordingIc.replaceSelectionOrInsertAtCaret(text)

        // Force the service to treat this as a hard boundary.
        // commitCurrentWord() calls finishComposingRegionCompletely().
        service.commitCurrentWord()

        // Drive an onUpdateSelection to simulate the framework telling us the cursor moved after the external edit.
        val pos = recordingIc.getSelection().second
        service.testOnUpdateSelection(-1, -1, pos, pos)
    }

    fun reset() {
        committedText.clear()
        recordingIc.resetBuffer()
        service.testFinishInput()
        service.testStartInput()
    }

    // ------------------------------------------------------------------
    // Richer test control (for backspace, selection, and more complex flows)
    // ------------------------------------------------------------------

    /**
     * Seed the committed buffer with existing text (simulates typing before we started,
     * or an external commit). Useful for testing backspace against prior content.
     */
    fun seedCommitted(text: String) {
        committedText.append(text)
        recordingIc.seedText(text)
    }

    /**
     * Force a selection in the underlying recording IC (start inclusive, end exclusive).
     * Used to verify that backspace first deletes a selection.
     */
    fun setSelection(start: Int, end: Int) {
        recordingIc.setSelectionForTest(start, end)
    }

    /** Move the logical caret inside the recording buffer (for cursor movement tests). */
    fun moveCaret(delta: Int) {
        recordingIc.moveCaret(delta)
    }

    /**
     * Seed some committed text, then start typing in the chosen mode.
     * Useful for cursor + action tests that need existing field content.
     */
    fun seedAndType(seed: String, mode: EchoKeyService.InputMode = EchoKeyService.InputMode.T9, digits: List<Int> = emptyList()) {
        reset()
        seedCommitted(seed)
        setMode(mode)
        digits.forEach { letter(it) }
    }

    // ------------------------------------------------------------------
    // Canonical flows (exactly the behaviors the user specified)
    // ------------------------------------------------------------------

    /** Exact hard requirement: 2-2-6-1-8-1 must produce "can't?" in T9 mode. */
    fun runT9CantQuestion(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6)   // can
        punctuation()                      // apostrophe
        letter(8)                          // t
        punctuation()                      // ?

        return getCommitted()
    }

    /** Classic multi-tap "tackle" using explicit accepts (user's example). */
    fun runMultiTapTackleWithAccepts(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(8)                    // t
        letter(2); multiTapAccept()  // a
        letter(2); letter(2); letter(2)   // c
        letter(5); letter(5); multiTapAccept() // l
        letter(5); letter(5); letter(5)  // e
        letter(3); letter(3)             // k

        return getCommitted()
    }

    /**
     * Action key as explicit accept: press 2 (start cycle), action tap should accept without committing yet.
     * Returns (cycleWasActive, committedAfterAction).
     */
    fun runActionAcceptDuringMultiTapCycle(): Pair<Boolean, String> {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(2) // start cycling 'a' on key 2
        val cycleActive = isMultiTapCycleActive()

        actionTap() // should act as explicit accept while cycling

        // After accept, the letter is part of the word but not yet "committed" to the field
        // (space or punctuation or finish would commit it). We return what is committed so far.
        return cycleActive to getCommitted()
    }

    /** Long-press on space/0 should insert literal '0' and act as boundary. */
    fun runLongPressZero(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(6) // "to" or similar
        spaceLongPress()     // should insert '0' directly
        return getCommitted()
    }

    /**
     * Backspace single char delete via the real service.
     * Seed some text, then backspace. Returns final committed.
     */
    fun runBackspaceSingleChar(seed: String = "hello"): String {
        reset()
        seedCommitted(seed)
        backspace()
        return getCommitted()
    }

    /**
     * Backspace selection delete.
     * Seed text, set selection, call backspace once → selection should be removed.
     */
    fun runBackspaceSelection(seed: String = "hello", selStart: Int = 1, selEnd: Int = 4): String {
        reset()
        seedCommitted(seed)           // "hello"
        setSelection(selStart, selEnd) // e.g. select "ell"
        backspace()
        return getCommitted()
    }

    /**
     * Word deletion via slide-left backspace.
     * Seed text, call held with slide=true a few times.
     * We simulate the timing by calling onBackspaceHeld directly with isSlidingLeft=true.
     */
    fun runWordDeleteSlide(seed: String = "hello world"): String {
        reset()
        seedCommitted(seed)
        // First down
        backspace() // this will see no selection and start char mode
        // Now simulate slide + helds (service will switch to WORD mode)
        // We call held multiple times; our simplified handler will eventually request DELETE_ONE_WORD
        backspaceHeld(isSlidingLeft = true)
        backspaceHeld(isSlidingLeft = true)
        backspaceHeld(isSlidingLeft = true)
        backspaceUp()
        return getCommitted()
    }

    /**
     * Full hygiene through service: type word, [1] punctuation, space, [1] again.
     * After space the next [1] must be terminal (new sequence), not apostrophe.
     */
    fun runPunctuationHygieneAfterSpace(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(4); letter(6); letter(6) // "goo" or similar
        punctuation()                    // should append terminal punct (e.g. "goo.")
        space()
        punctuation()                    // must be fresh terminal punct, not mid-word apostrophe

        return getCommitted()
    }

    /**
     * Long-press digit (non-zero) inserts the literal digit and creates a boundary.
     */
    fun runLongPressDigit(digit: Int = 5): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(3)
        digitLongPress(digit)
        return getCommitted()
    }

    /**
     * Critical hygiene: start a multi-tap cycle, backspace while cycling.
     * The engine must eat the cycling letter only (no field deletion).
     * Returns (committedBefore, committedAfter, cycleActiveAfter).
     */
    fun runBackspaceDuringMultiTapCycle(): Triple<String, String, Boolean> {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.TIMEOUT)

        letter(2) // start cycling on 'a'
        val before = getCommitted() + "|" + getCurrentComposing()

        backspace() // should only kill the cycle letter, not touch committed text

        val after = getCommitted()
        val cycleAfter = isMultiTapCycleActive()
        return Triple(before, after, cycleAfter)
    }

    /**
     * T9 backspace: type 2-2-6 ("can"), backspace once → should shrink prediction.
     */
    fun runBackspaceInT9(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6)
        backspace()
        return getCurrentComposing()  // or committed if finished; in T9 we usually see composing
    }

    /**
     * Multi-tap: explicit accept then continue on a *different* key.
     * This is the normal case after an accept.
     * Example skeleton for "ta": 8 (t), 2-accept (a)
     */
    fun runMultiTapAcceptThenDifferentKey(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(8)           // t
        letter(2); multiTapAccept() // a  (explicit accept)
        letter(3); letter(3)        // e (on different key)

        return getCommitted()
    }

    /**
     * The full "tackle" sequence through the service, but using the action key for accepts
     * instead of the dedicated multiTapAccept() helper. This proves action-as-accept in context.
     */
    fun runMultiTapTackleViaActionAccepts(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(8)
        letter(2); actionTap()                 // accept 'a' via action key
        letter(2); letter(2); letter(2)
        letter(5); letter(5); actionTap()      // accept 'l'
        letter(5); letter(5); letter(5)
        letter(3); letter(3)

        return getCommitted()
    }

    /**
     * Multi-tap using TIMEOUT accept style (no explicit accepts).
     * For "cab" we need a pause between the two 'a's on key 2.
     * In the driver we simulate the pause by calling multiTapTimeout().
     */
    fun runMultiTapWithTimeoutAccept(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.TIMEOUT)

        letter(2)             // a
        multiTapTimeout()     // accept 'a'
        letter(2); letter(2)  // c  (different key, no timeout needed)
        letter(2); multiTapTimeout()  // b

        return getCommitted()
    }

    /**
     * T9 contraction + backspace the apostrophe: 2-2-6-1 then backspace → should become "can" again (base shrinks).
     */
    fun runT9ApostropheBackspace(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6) // can
        punctuation()                    // can'
        backspace()                      // should remove the apostrophe (engine base shrinks)
        return getCurrentComposing()
    }

    /**
     * After explicit accept in multi-tap, punctuation must see the accepted word (tracker sync).
     * "ca" + accept + [1] should produce terminal punctuation on "ca" (or the built word).
     */
    fun runPunctuationAfterMultiTapAccept(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(2); letter(2)   // c
        letter(2); multiTapAccept() // a (accepted)
        punctuation()          // should be terminal punct after "ca" (or "c" + composing a)

        return getCommitted()
    }

    /**
     * Action long-press always produces a newline and must finish any composing/hygiene.
     * We seed a word + start a cycle, then long-press action. Newline should appear.
     */
    fun runActionLongPressNewline(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)

        letter(2); letter(2) // start a cycle
        actionLongPress()
        return getCommitted()
    }

    /**
     * Cursor move while a T9 prediction is active must finish composing first.
     * Returns (committed, composingAfterMove, selectionPos).
     */
    fun runMoveCursorAfterT9(): Triple<String, String, Int> {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6) // "can" composing
        moveCursorLeft(1)
        return Triple(getCommitted(), getCurrentComposing(), getSelection().second)
    }

    /**
     * Cursor move while multi-tap cycling must accept the letter (hygiene) then move.
     */
    fun runMoveCursorDuringMultiTap(): Pair<Boolean, Boolean> {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.TIMEOUT)
        letter(2) // cycle
        val had = isMultiTapCycleActive()
        moveCursorLeft(1)
        return had to isMultiTapCycleActive()
    }

    /**
     * Action long-press while T9 prediction must finish the word + insert newline.
     */
    fun runActionLongPressInT9Prediction(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        actionLongPress()
        return getCommitted()
    }

    /**
     * Real EditorInfo action (e.g. IME_ACTION_DONE) on a T9 prediction.
     * Returns (committedWord, performedActionCode).
     */
    fun runT9ThenDoneAction(): Pair<String, Int?> {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        setMode(EchoKeyService.InputMode.T9)
        letter(4); letter(6); letter(6) // "goo" or similar
        val performed = actionTapAndGetEditorAction()
        return getCommitted() to performed
    }

    /**
     * When a real action is active (Send), long-press must still insert newline, not perform the Send.
     */
    fun runRealActionLongPressGivesNewline(action: Int = android.view.inputmethod.EditorInfo.IME_ACTION_SEND): Pair<String, Int?> {
        startWithImeAction(action)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        recordingIc.clearLastEditorAction()
        actionLongPress()
        val performed = recordingIc.getLastPerformedEditorAction()
        return getCommitted() to performed
    }

    /** Simulate full field lifecycle hygiene: start, type, finishInput, start again. */
    fun runFieldSwitchHygiene(): String {
        // First field
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(6); letter(6) // some prediction
        punctuation()

        // Simulate leaving the field
        service.testFinishInput()

        // Enter a new field (fresh start)
        service.testStartInput()
        letter(4); letter(3) // new word in new field
        punctuation()

        return getCommitted()
    }

    /**
     * Exercise multi-tap TIMEOUT accept style end-to-end (different-key letters).
     */
    fun runMultiTapTimeoutStyleBasic(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.TIMEOUT)

        letter(2)          // 'a'
        multiTapTimeout()  // accept via timeout
        letter(3); letter(3) // 'e' (different key)
        multiTapTimeout()

        return getCommitted()
    }

    // ------------------------------------------------------------------
    // Critical edge cases that must behave correctly before any UI is added
    // ------------------------------------------------------------------

    /**
     * Space while a multi-tap cycle is active must first accept the cycling letter,
     * then insert the space. The cycle must be cleared.
     */
    fun runSpaceWhileMultiTapCycle(): Pair<String, Boolean> {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.TIMEOUT)

        letter(2)   // start cycling 'a'
        space()     // must accept 'a' then space

        val committed = getCommitted()
        val cycleAfter = isMultiTapCycleActive()
        return committed to cycleAfter
    }

    /**
     * Punctuation while a multi-tap cycle is active must accept the letter first,
     * then apply punctuation logic on the now-complete word.
     */
    fun runPunctuationWhileMultiTapCycle(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(2); letter(2) // 'c'
        punctuation()        // should accept 'c' then terminal punct

        return getCommitted()
    }

    /**
     * Long-press digit while multi-tap cycling must finish the cycle cleanly
     * (hygiene) before inserting the literal digit.
     */
    fun runLongPressDigitWhileCycle(digit: Int = 0): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)

        letter(5); letter(5) // cycling on 'k' or 'l'
        if (digit == 0) spaceLongPress() else digitLongPress(digit)
        return getCommitted()
    }

    /**
     * T9: space after a full predicted word must commit the word + space.
     */
    fun runT9SpaceAfterWord(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6) // can
        space()
        return getCommitted()
    }

    /**
     * Privacy context: when a password field is active, mode must be forced to MULTI_TAP
     * and suggestions must be empty. We simulate by attaching an EditorInfo with password variation
     * and checking service state.
     */
    fun runPrivacyForcesMultiTap(): Pair<Boolean, Boolean> {
        // Fresh service for clean context
        val fresh = EchoKeyService()
        val dummyIc = object : InputConnection by RecordingInputConnection(StringBuilder()) { /* minimal */ }
        fresh.attachTestInputConnection(dummyIc)

        val ei = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fresh.testStartInput(ei)

        val forcedMulti = fresh.getCurrentInputMode() == EchoKeyService.InputMode.MULTI_TAP
        val noSuggestions = fresh.getCurrentSuggestions(3).isEmpty()
        return forcedMulti to noSuggestions
    }

    /**
     * Action label: while multi-tap cycling, getCurrentActionLabel() must return "accept".
     * After accept, it should return the normal label.
     */
    fun runActionLabelDuringCycle(): Pair<String, String> {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(2) // start cycle
        val during = service.getCurrentActionLabel()  // should be "accept"

        actionTap()
        val after = service.getCurrentActionLabel()   // normal (usually "newline" or similar in test EI)

        return during to after
    }

    // ------------------------------------------------------------------
    // EditorInfo action flows (Send / Go / Next / Done etc)
    // ------------------------------------------------------------------

    /**
     * Start a fresh session with a specific EditorInfo action (e.g. IME_ACTION_SEND).
     * Clears previous committed state.
     */
    fun startWithImeAction(action: Int, inputTypeFlags: Int = 0) {
        committedText.clear()
        recordingIc.resetBuffer()

        val ei = android.view.inputmethod.EditorInfo().apply {
            this.inputType = android.text.InputType.TYPE_CLASS_TEXT or inputTypeFlags
            imeOptions = action
        }
        restartWithEditorInfo(ei)
    }

    /** Tap action and return what editor action (if any) was performed on the IC. */
    fun actionTapAndGetEditorAction(): Int? {
        recordingIc.clearLastEditorAction()
        actionTap()
        return recordingIc.getLastPerformedEditorAction()
    }

    /** Long-press action and return what editor action (if any) was performed. */
    fun actionLongPressAndGetEditorAction(): Int? {
        recordingIc.clearLastEditorAction()
        actionLongPress()
        return recordingIc.getLastPerformedEditorAction()
    }

    /**
     * Full flow: type in T9, then tap action key when a Send/Go/etc is active.
     * The word must be committed (visible in committedText) and then the editor action performed.
     */
    fun runT9ThenRealAction(action: Int = android.view.inputmethod.EditorInfo.IME_ACTION_SEND): Pair<String, Int?> {
        committedText.clear()
        recordingIc.resetBuffer()

        val ei = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = action
        }
        restartWithEditorInfo(ei)
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6) // can
        val performed = actionTapAndGetEditorAction()
        return getCommitted() to performed
    }

    /**
     * T9 prediction + action tap with a real Next action.
     * Verifies the predicted word is committed and IME_ACTION_NEXT is performed.
     */
    fun runT9ThenNextAction(): Pair<String, Int?> {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(3) // "be" or similar
        val performed = actionTapAndGetEditorAction()
        return getCommitted() to performed
    }

    /**
     * Stronger "T9 + real action" check: after performing Send/Go/etc, the engine must be clean.
     * Typing more letters afterwards must start a completely new word (no leftover digits/base).
     */
    fun runT9ActionThenContinueTyping(): String {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6) // can
        actionTap()                      // commits "can" + performs Send (in recording IC)
        // Now continue as if user kept typing in the (same or next) field.
        letter(4); letter(6)             // "go" or similar in new word
        return getCommitted()
    }

    /**
     * T9 word + real action (Send) + immediate [1] punctuation.
     * The punctuation after the action must be a fresh terminal (not attached to the just-sent word).
     */
    fun runT9SendThenPunctuation(): String {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6) // can
        actionTap()                      // commits + Send
        punctuation()                    // must be fresh terminal punct
        return getCommitted()
    }

    /**
     * External insertion while a multi-tap cycle is active.
     * Must accept the cycle letter (or drop it cleanly) and leave punctuation state fresh.
     */
    fun runExternalInsertionDuringMultiTapCycle(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.TIMEOUT)
        letter(2) // cycling
        simulateExternalInsertion(" voice")
        punctuation()
        return getCommitted()
    }

    /**
     * Pick a suggestion (as the future UI will), then immediately do punctuation.
     * The picked word must be treated as finished for punctuation purposes.
     */
    fun runPickSuggestionThenPunct(): String {
        return runPickSuggestionThenPunctuation("can")
    }

    /**
     * Type T9, pick a suggestion, move cursor inside the committed word, type more.
     * Must behave as a fresh word start for prediction + punctuation.
     */
    fun runSuggestionThenCursorInsideThenType(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6) // can
        commitSuggestion("cannot")
        moveCursorLeft(3) // inside "cannot"
        letter(2); letter(3) // start fresh prediction
        return getCommitted() + getCurrentComposing()
    }

    /**
     * Critical daily contraction hygiene:
     * 2-2-6-1 (can') → space → new letters.
     * The new word after space must not inherit the apostrophe base.
     */
    fun runT9ApostropheSpaceThenNewWord(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6) // can
        punctuation()                    // can'
        space()                          // can' 
        letter(4); letter(3)             // "ge" or "he" etc. — must be a clean new word

        return getCommitted()
    }

    /**
     * T9 apostrophe + space + punctuation.
     * After "can' " the next [1] must be terminal, never an apostrophe.
     */
    fun runApostropheSpaceThenPunct(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        punctuation()
        space()
        punctuation()
        return getCommitted()
    }

    /**
     * Full realistic scenario the user will do daily:
     * Type "can't?", send it (real action), then type "ok." in the next context.
     */
    fun runDailyContractionSendThenContinue(): String {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6) // can
        punctuation()                    // '
        letter(8)                        // t
        punctuation()                    // ?

        // Send
        actionTap()

        // Continue (simulate staying in field or next field)
        letter(6); letter(5)             // "ok"
        punctuation()

        return getCommitted()
    }

    /**
     * Backspace word-slide while a T9 prediction is active.
     * Must finish the prediction first, then do word delete.
     */
    fun runWordSlideWhileT9Prediction(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        seedCommitted("hello beautiful world")
        letter(2); letter(2); letter(6) // prediction active
        // Simulate slide-left held sequence
        backspace() // initial down (may see char because no selection)
        backspaceHeld(isSlidingLeft = true)
        backspaceHeld(isSlidingLeft = true)
        backspaceHeld(isSlidingLeft = true)
        backspaceUp()
        return getCommitted()
    }

    /**
     * Long-press on space/0 while a T9 prediction is composing.
     * Must finish the prediction cleanly, then insert the literal '0'.
     */
    fun runLongPressZeroWhileT9Prediction(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6) // can (composing)
        spaceLongPress()
        return getCommitted()
    }

    /**
     * Long-press digit while T9 prediction → literal digit + clean boundary.
     */
    fun runLongPressDigitWhileT9(d: Int = 7): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(6)
        digitLongPress(d)
        return getCommitted()
    }

    /**
     * Real action (Send) then immediate backspace.
     * The backspace must operate on the committed result, not ghost composing.
     */
    fun runSendThenBackspace(): String {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        actionTap()
        backspace()
        return getCommitted()
    }

    /**
     * After a real Send action, the very next letter must start a completely fresh word
     * (no leftover T9 digits or apostrophe base).
     */
    fun runSendThenFreshLetter(): String {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        punctuation()   // "can?"
        actionTap()     // send
        // Now the next letter must be the start of a brand new word.
        letter(2)
        return getCurrentComposing()  // should be just "a" or "c" etc., not "can?a" or similar
    }

    /**
     * After any real editor action, getCurrentCandidates must be empty or for a brand new prefix.
     * This is what the suggestions bar will see.
     */
    fun runActionThenCandidatesAreFresh(): Pair<List<String>, List<String>> {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        val before = getCurrentSuggestions().toList()
        actionTap()
        letter(2) // start something new
        val after = getCurrentSuggestions().toList()
        return before to after
    }

    /**
     * After a real action, the internal engine state must be fully reset.
     * We check via snapshot + composing + candidates.
     */
    fun runActionResetsEngineCompletely(): Boolean {
        startWithImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        setMode(EchoKeyService.InputMode.T9)
        letter(2); letter(2); letter(6)
        actionTap()
        val snap = getSnapshot()
        val comp = getCurrentComposing()
        val cands = getCurrentSuggestions()
        return snap.composingText.isEmpty() && comp.isEmpty() && cands.isEmpty()
    }

    /**
     * Full "day in the life" scenario through the service:
     * T9 letters → punct (apostrophe) → more letters → space → suggestion pick →
     * punct (terminal) → cursor move → backspace → external insert → action long-press newline.
     * Returns the final committed text. Used to exercise many boundaries in sequence.
     */
    fun runFullDayInTheLifeScenario(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6) // can
        punctuation()                    // can'
        letter(8)                        // can't
        space()                          // can't 
        // Pick a suggestion (simulates user tapping a better word from the bar)
        commitSuggestion("cannot")
        punctuation()                    // cannot.
        moveCursorLeft(3)                // move inside or before the period
        backspace()                      // delete something
        simulateExternalInsertion(" ok")
        actionLongPress()                // newline

        return getCommitted()
    }

    /**
     * External insertion (e.g. voice) followed immediately by [1] punctuation.
     * Must produce fresh terminal punctuation (not an apostrophe attached to the dictated text).
     */
    fun runExternalInsertionThenPunctuation(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)
        simulateExternalInsertion("hello world")
        punctuation()
        return getCommitted()
    }

    /**
     * Simulate a series of backspace "held" events (as the real UI would do with a timer).
     * This exercises the acceleration + word-slide paths through the real service + BackspaceHandler.
     */
    fun simulateBackspaceHeldSequence(
        initialSeed: String,
        steps: Int,
        slideLeftAfterStep: Int = -1
    ): String {
        reset()
        seedCommitted(initialSeed)

        // First tap (down)
        backspace()

        var slid = false
        repeat(steps) { i ->
            val sliding = slideLeftAfterStep >= 0 && i >= slideLeftAfterStep
            if (sliding && !slid) {
                slid = true
            }
            backspaceHeld(sliding)
        }
        backspaceUp()
        return getCommitted()
    }

    /**
     * When multi-tap is cycling, action tap must do ACCEPT (not the real Send/Go action).
     * Long-press must still give newline (or the long-press behavior).
     */
    fun runMultiTapCycleOverridesRealAction(action: Int = android.view.inputmethod.EditorInfo.IME_ACTION_SEND): Triple<Boolean, Int?, String> {
        committedText.clear()
        recordingIc.resetBuffer()

        val ei = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = action
        }
        restartWithEditorInfo(ei)
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(2) // start cycling
        val cycleActive = isMultiTapCycleActive()

        recordingIc.clearLastEditorAction()
        actionTap() // must accept, must NOT call performEditorAction for the Send
        val actionDuringCycle = recordingIc.getLastPerformedEditorAction()

        // Now the letter is accepted. Another action tap should do the real action.
        recordingIc.clearLastEditorAction()
        actionTap()
        val actionAfterAccept = recordingIc.getLastPerformedEditorAction()

        return Triple(cycleActive, actionDuringCycle, getCommitted())
    }

    // ------------------------------------------------------------------
    // Bulk canonical proof runner (for quick "are we still good?" checks)
    // ------------------------------------------------------------------

    data class ProofResult(val name: String, val passed: Boolean, val detail: String)

    /**
     * Runs the most important full-service proofs in one shot.
     * Returns a list of results. The caller (verification or future test) decides how to present.
     */
    fun runAllCanonicalProofs(): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        // 1. The hard requirement
        val cant = runFullServiceT9CantQuestion()
        results += ProofResult("T9 can't? (2-2-6-1-8-1)", cant == "can't?", "got=\"$cant\"")

        // 2. Classic multi-tap + explicit accept
        val tackle = runFullServiceMultiTapTackle()
        val tackleOk = tackle.lowercase().contains("tackl")
        results += ProofResult("Multi-tap tackle (explicit)", tackleOk, "got=\"$tackle\"")

        // 3. Action key as accept during cycle
        val (cycleOn, _) = runActionAcceptDuringMultiTapCycle()
        val cycleCleared = !isMultiTapCycleActive()
        results += ProofResult("Action-as-accept during cycle", cycleOn && cycleCleared, "cycleWas=$cycleOn, cleared=$cycleCleared")

        // 4. Long-press 0 inserts digit
        val zero = runLongPressZero()
        results += ProofResult("Long-press 0 inserts '0'", zero.contains("0"), "got=\"$zero\"")

        // 5. Single char backspace
        val bs1 = runBackspaceSingleChar("hello")
        results += ProofResult("Backspace single char", bs1 == "hell", "got=\"$bs1\"")

        // 6. Selection backspace
        val bssel = runBackspaceSelection("hello", 1, 4)
        results += ProofResult("Backspace selection", bssel == "ho", "got=\"$bssel\"")

        // 7. Punctuation hygiene after space
        val hyg = runPunctuationHygieneAfterSpace()
        val hygOk = !hyg.contains("'") && hyg.length > 4
        results += ProofResult("Punct hygiene after space", hygOk, "got=\"$hyg\"")

        // 8. Backspace during multi-tap cycle (engine consumes)
        val (_, _, cycleAfter) = runBackspaceDuringMultiTapCycle()
        results += ProofResult("Backspace eats multi-tap cycle letter", !cycleAfter, "cycleAfter=$cycleAfter")

        // 9. Long-press non-zero digit
        val ld = runLongPressDigit(7)
        results += ProofResult("Long-press digit inserts literal", ld.contains("7"), "got=\"$ld\"")

        // 10. T9 apostrophe backspace
        val ab = runT9ApostropheBackspace()
        val abOk = !ab.contains("'") && ab.isNotEmpty()
        results += ProofResult("T9 apostrophe backspace", abOk, "got=\"$ab\"")

        // 11. Punctuation after explicit accept (tracker sync)
        val pa = runPunctuationAfterMultiTapAccept()
        val paOk = pa.any { ".?!,".contains(it) }
        results += ProofResult("Punct after multi-tap accept", paOk, "got=\"$pa\"")

        // 12. Action long-press newline
        val nl = runActionLongPressNewline()
        val nlOk = nl.contains("\n") || nl.contains("\r")
        results += ProofResult("Action long-press newline", nlOk, "got=\"$nl\"")

        // 13. Multi-tap TIMEOUT style
        val to = runMultiTapWithTimeoutAccept()
        val toOk = to.isNotEmpty()
        results += ProofResult("Multi-tap TIMEOUT accept style", toOk, "got=\"$to\"")

        // 14. Field switch hygiene (finish + start again)
        val fs = runFieldSwitchHygiene()
        val fsOk = fs.isNotEmpty()
        results += ProofResult("Field switch hygiene", fsOk, "got=\"$fs\"")

        // 15. Space while multi-tap cycle (must accept first)
        val (spCommitted, spCycle) = runSpaceWhileMultiTapCycle()
        val spOk = spCommitted.contains(" ") && !spCycle
        results += ProofResult("Space while multi-tap cycle", spOk, "committed=\"$spCommitted\", cycleAfter=$spCycle")

        // 16. Punctuation while multi-tap cycle
        val pc = runPunctuationWhileMultiTapCycle()
        val pcOk = pc.isNotEmpty() && pc.any { ".?!,".contains(it) }
        results += ProofResult("Punctuation while multi-tap cycle", pcOk, "got=\"$pc\"")

        // 17. T9 space after word
        val t9sp = runT9SpaceAfterWord()
        val t9spOk = t9sp.contains(" ")
        results += ProofResult("T9 space after word", t9spOk, "got=\"$t9sp\"")

        // 18. Privacy forces MULTI_TAP + no suggestions
        val (privMulti, privNoSug) = runPrivacyForcesMultiTap()
        results += ProofResult("Privacy forces MULTI_TAP + no suggestions", privMulti && privNoSug, "multi=$privMulti, noSug=$privNoSug")

        // 19. Action label becomes "accept" during cycle
        val (labDuring, labAfter) = runActionLabelDuringCycle()
        val labelOk = labDuring.equals("accept", ignoreCase = true)
        results += ProofResult("Action label 'accept' while cycling", labelOk, "during=\"$labDuring\", after=\"$labAfter\"")

        // 20. Real IME action after T9 word (e.g. Send) commits the word + performs action
        val (sent, performed) = runT9ThenRealAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        val realActionPass = sent == "can" && performed == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        results += ProofResult("T9 word + real IME_ACTION_SEND", realActionPass, "text=\"$sent\", action=$performed")

        // 21. Multi-tap cycle still overrides a real Send action on first tap
        val (ovCycle, ovDuring, _) = runMultiTapCycleOverridesRealAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        val overridePass = ovCycle && ovDuring == null
        results += ProofResult("Multi-tap cycle overrides real Send", overridePass, "cycle=$ovCycle, duringAction=$ovDuring")

        // 22. T9 + real Send then continue typing → fresh word
        val afterSend = runT9ActionThenContinueTyping()
        val afterSendOk = afterSend.contains("can") && afterSend.length > 5
        results += ProofResult("T9 Send then continue typing fresh", afterSendOk, "got=\"$afterSend\"")

        // 23. T9 + Send + immediate [1] punctuation must be fresh terminal
        val sendThenP = runT9SendThenPunctuation()
        val sendPunctOk = sendThenP.contains("can") && sendThenP.any { ".?!,".contains(it) }
        results += ProofResult("T9 Send then fresh punctuation", sendPunctOk, "got=\"$sendThenP\"")

        // 24. External insertion during multi-tap cycle + punct
        val extMt = runExternalInsertionDuringMultiTapCycle()
        val extMtOk = extMt.contains("voice") && extMt.any { ".?!,".contains(it) }
        results += ProofResult("External insert during multi-tap cycle + punct", extMtOk, "got=\"$extMt\"")

        return results
    }

    /**
     * Full T9 6-keystroke contraction through the *real service*.
     * This is the most important proof: driver → service → T9Engine + Punctuator + Tracker.
     */
    fun runFullServiceT9CantQuestion(): String {
        reset()
        setMode(EchoKeyService.InputMode.T9)

        letter(2); letter(2); letter(6)   // 2-2-6 → "can"
        punctuation()                      // 1 → apostrophe → "can'"
        letter(8)                          // 8 → "can't"
        punctuation()                      // 1 → "can't?"

        return getCommitted()
    }

    /**
     * Full multi-tap tackle through the real service with explicit accepts.
     */
    fun runFullServiceMultiTapTackle(): String {
        reset()
        setMode(EchoKeyService.InputMode.MULTI_TAP)
        setAcceptStyle(EchoKeyService.MultiTapAcceptStyle.EXPLICIT_NEXT)

        letter(8)
        letter(2); multiTapAccept()
        letter(2); letter(2); letter(2)
        letter(5); letter(5); multiTapAccept()
        letter(5); letter(5); letter(5)
        letter(3); letter(3)

        return getCommitted()
    }

    // ------------------------------------------------------------------
    // Observation helpers
    // ------------------------------------------------------------------

    fun getCurrentComposing(): String = currentComposing

    fun isMultiTapCycleActive() = service.isMultiTapCycleActive()

    // ------------------------------------------------------------------
    // Recording IC implementation with realistic support for backspace + selection.
    // ------------------------------------------------------------------

    /**
     * A minimal but realistic InputConnection for headless verification.
     * Records commits, supports composing, and implements deleteSurroundingText
     * against an internal text buffer so that backspace flows are verifiable.
     */
    private class RecordingInputConnection(
        private val committed: StringBuilder,
        private val onComposingChanged: (String) -> Unit
    ) : InputConnection {

        // The "committed" buffer as seen from the field perspective.
        // Composing is shown separately and does not live in this buffer until committed.
        private val textBuffer = StringBuilder()

        private var composingText: String = ""
        private var selectionStart: Int = 0
        private var selectionEnd: Int = 0

        // Recorded side-effects for verification (action key, etc.)
        var lastEditorAction: Int? = null
            private set

        fun resetBuffer() {
            committed.clear()
            textBuffer.clear()
            composingText = ""
            selectionStart = 0
            selectionEnd = 0
            lastEditorAction = null
            onComposingChanged("")
        }

        /** Test helper: seed prior committed text (simulates content already in the field). */
        fun seedText(text: String) {
            committed.append(text)
            textBuffer.append(text)
            selectionStart = textBuffer.length
            selectionEnd = selectionStart
        }

        /** Test helper: set selection range for backspace selection tests / cursor simulation. */
        fun setSelectionForTest(start: Int, end: Int) {
            val len = textBuffer.length
            selectionStart = start.coerceIn(0, len)
            selectionEnd = end.coerceIn(0, len)
        }

        /**
         * Move the logical cursor/selection by delta characters.
         * This is what the service's moveCursorBy ultimately calls (via setSelection).
         * We keep selectionStart == selectionEnd for a simple caret model.
         */
        fun moveCaret(delta: Int) {
            val len = textBuffer.length
            val current = selectionEnd
            val next = (current + delta).coerceIn(0, len)
            selectionStart = next
            selectionEnd = next
        }

        /** Expose current text buffer length for test assertions. */
        fun getBufferLength(): Int = textBuffer.length

        /** For richer backspace tests: return whether we currently think a selection is active. */
        fun hasActiveSelection(): Boolean = selectionStart != selectionEnd

        // --- Core methods we actually exercise in the invisible layer ---

        private fun syncCommittedFromBuffer() {
            committed.clear()
            committed.append(textBuffer.toString())
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString() ?: ""

            if (selectionStart != selectionEnd) {
                // Replace the active selection with the new text.
                val start = kotlin.math.min(selectionStart, selectionEnd)
                val end = kotlin.math.max(selectionStart, selectionEnd)
                textBuffer.delete(start, end)
                textBuffer.insert(start, s)

                composingText = ""
                onComposingChanged("")
                val newSel = start + s.length
                selectionStart = newSel
                selectionEnd = newSel

                syncCommittedFromBuffer()
                return true
            }

            // Normal append path (no selection).
            if (composingText.isNotEmpty()) {
                // In our model composing lives outside the buffer until finish/commit.
            }
            textBuffer.append(s)
            committed.append(s)
            composingText = ""
            onComposingChanged("")
            selectionStart = textBuffer.length
            selectionEnd = selectionStart
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingText = text?.toString() ?: ""
            onComposingChanged(composingText)
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composingText.isNotEmpty()) {
                // Commit whatever was composing into the buffer.
                textBuffer.append(composingText)
                committed.append(composingText)
                composingText = ""
                onComposingChanged("")
                selectionStart = textBuffer.length
                selectionEnd = selectionStart
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val before = beforeLength.coerceAtLeast(0)
            if (before > 0 && textBuffer.isNotEmpty()) {
                val remove = before.coerceAtMost(textBuffer.length)
                textBuffer.delete(textBuffer.length - remove, textBuffer.length)
            }
            selectionStart = textBuffer.length
            selectionEnd = selectionStart
            syncCommittedFromBuffer()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            val len = textBuffer.length
            val k = n.coerceAtMost(len)
            return textBuffer.substring(len - k, len)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""

        override fun getExtractedText(req: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
            val et = android.view.inputmethod.ExtractedText()
            // Always return a concrete non-null text so moveCursorBy + service code that does
            // extracted.text?.length works reliably in headless tests.
            et.text = textBuffer.toString()
            et.startOffset = 0
            et.selectionStart = selectionStart
            et.selectionEnd = selectionEnd
            return et
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selectionStart = start.coerceIn(0, textBuffer.length)
            selectionEnd = end.coerceIn(0, textBuffer.length)
            return true
        }

        /** Test helper to directly drive onUpdateSelection-like behavior from the service. */
        fun simulateSelectionChange(newStart: Int, newEnd: Int) {
            setSelection(newStart, newEnd)
        }

        fun getCurrentBufferText(): String = textBuffer.toString()
        fun getSelection(): Pair<Int, Int> = selectionStart to selectionEnd
        fun getLastPerformedEditorAction(): Int? = lastEditorAction

        fun clearLastEditorAction() { lastEditorAction = null }

        /** Move the caret (selection collapsed) by delta. Keeps selectionStart == selectionEnd. */
        fun moveCaret(delta: Int) {
            val len = textBuffer.length
            val cur = selectionEnd
            val next = (cur + delta).coerceIn(0, len)
            selectionStart = next
            selectionEnd = next
        }

        /** Replace current selection (or insert at caret) with text. Used for external commit simulation. */
        fun replaceSelectionOrInsertAtCaret(text: String) {
            val start = kotlin.math.min(selectionStart, selectionEnd)
            val end = kotlin.math.max(selectionStart, selectionEnd)
            textBuffer.delete(start, end)
            textBuffer.insert(start, text)
            val newPos = start + text.length
            selectionStart = newPos
            selectionEnd = newPos
            // Mirror into the high-level committed view so driver.getCommitted() sees it.
            committed.append(text)
        }

        /**
         * Simulate realistic "getTextBeforeCursor" after cursor has moved.
         * This is important because service backspace and punctuation sometimes read before-cursor.
         * We return the text immediately preceding the current selection start.
         */
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            val sel = kotlin.math.min(selectionStart, selectionEnd)
            val k = n.coerceAtMost(sel)
            val start = (sel - k).coerceAtLeast(0)
            return textBuffer.substring(start, sel)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            val len = textBuffer.length
            val sel = kotlin.math.max(selectionStart, selectionEnd)
            val end = (sel + n).coerceAtMost(len)
            return textBuffer.substring(sel, end)
        }

        /**
         * Make extracted text reflect the *current* selection/caret accurately.
         * Many IME decisions (selection delete, cursor movement, etc.) rely on this.
         */
        override fun getExtractedText(req: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
            val et = android.view.inputmethod.ExtractedText()
            et.text = textBuffer.toString()
            et.startOffset = 0
            et.selectionStart = selectionStart
            et.selectionEnd = selectionEnd
            et.partialEndOffset = textBuffer.length
            return et
        }

        // --- Reasonable defaults for the rest (we never call most of them in invisible flows) ---

        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true
        override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean = true
        override fun performEditorAction(actionCode: Int): Boolean {
            lastEditorAction = actionCode
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(info: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = false
    }
}