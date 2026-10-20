package com.echokey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal on-device 12-key UI for EchoKey.
 *
 * All input logic lives in [EchoKeyService]. This view only:
 *  - routes taps / long-presses / holds to the service API
 *  - refreshes suggestions, mode label, shift, action label from [EchoKeyService.getSnapshot]
 */
class EchoKeyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var service: EchoKeyService? = null

    private val handler = Handler(Looper.getMainLooper())
    private val multiTapTimeoutMs = 900L
    private val longPressMs = 450L

    private val multiTapTimeoutRunnable = Runnable {
        service?.onMultiTapTimeout()
        refreshFromService()
    }

    private lateinit var suggestionViews: List<TextView>
    private lateinit var modeToggle: TextView
    private lateinit var actionLabel: TextView
    private lateinit var shiftLabel: TextView
    private lateinit var shiftSub: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)
        bindChrome()
        bindKeys()
    }

    fun attachService(svc: EchoKeyService) {
        service = svc
        refreshFromService()
    }

    fun refreshFromService() {
        val snap = service?.getSnapshot() ?: return

        if (snap.isPrivate) {
            suggestionViews[0].text = context.getString(R.string.private_mode_indicator)
            suggestionViews[1].text = ""
            suggestionViews[2].text = ""
        } else {
            val sugs = snap.suggestions
            for (i in suggestionViews.indices) {
                suggestionViews[i].text = sugs.getOrNull(i).orEmpty()
            }
        }

        modeToggle.text = when (snap.mode) {
            EchoKeyService.InputMode.T9 -> "T9"
            EchoKeyService.InputMode.MULTI_TAP -> "abc"
        }
        modeToggle.isEnabled = !snap.isPrivate
        modeToggle.alpha = if (snap.isPrivate) 0.4f else 1f

        when (snap.shift) {
            ShiftState.OFF -> {
                shiftLabel.text = "⇧"
                shiftSub.text = ""
            }
            ShiftState.ONCE -> {
                shiftLabel.text = "⇧"
                shiftSub.text = "once"
            }
            ShiftState.LOCKED -> {
                shiftLabel.text = "⇪"
                shiftSub.text = "lock"
            }
        }

        actionLabel.text = when (snap.actionLabel.lowercase()) {
            "accept", "next" -> "✓ accept"
            "send" -> "➤ send"
            "go" -> "go →"
            "search" -> "⌕ search"
            "done" -> "done"
            "newline", "return", "enter" -> "⏎"
            else -> snap.actionLabel.ifBlank { "⏎" }
        }
    }

    private fun bindChrome() {
        suggestionViews = listOf(
            findViewById(R.id.suggestion_0),
            findViewById(R.id.suggestion_1),
            findViewById(R.id.suggestion_2)
        )
        suggestionViews.forEachIndexed { index, tv ->
            tv.setOnClickListener {
                val text = tv.text?.toString().orEmpty()
                if (text.isNotEmpty() && service?.isInPrivateContext() != true) {
                    // Private indicator is not a suggestion.
                    if (!text.startsWith("Private")) {
                        service?.commitSuggestion(text)
                        // Optional trailing space after pick feels natural for word chips.
                        if (text.any { it.isLetter() } && !text.last().let { c -> c in ".?!," }) {
                            service?.onSpaceKey()
                        }
                        refreshFromService()
                    }
                }
            }
        }

        modeToggle = findViewById(R.id.mode_toggle)
        modeToggle.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isInPrivateContext()) return@setOnClickListener
            val next = when (svc.getCurrentInputMode()) {
                EchoKeyService.InputMode.T9 -> EchoKeyService.InputMode.MULTI_TAP
                EchoKeyService.InputMode.MULTI_TAP -> EchoKeyService.InputMode.T9
            }
            svc.setInputMode(next)
            refreshFromService()
        }
    }

    private fun bindKeys() {
        setupDigitKey(R.id.key_1, primary = "1", sub = ".,?!", digit = 1, isPunct = true)
        setupDigitKey(R.id.key_2, primary = "2", sub = "abc", digit = 2)
        setupDigitKey(R.id.key_3, primary = "3", sub = "def", digit = 3)
        setupDigitKey(R.id.key_4, primary = "4", sub = "ghi", digit = 4)
        setupDigitKey(R.id.key_5, primary = "5", sub = "jkl", digit = 5)
        setupDigitKey(R.id.key_6, primary = "6", sub = "mno", digit = 6)
        setupDigitKey(R.id.key_7, primary = "7", sub = "pqrs", digit = 7)
        setupDigitKey(R.id.key_8, primary = "8", sub = "tuv", digit = 8)
        setupDigitKey(R.id.key_9, primary = "9", sub = "wxyz", digit = 9)

        setupSpaceKey()
        setupShiftKey()
        setupBackspaceKey()
        setupActionKey()
    }

    private fun keyRoot(id: Int): FrameLayout = findViewById(id)

    private fun labels(root: FrameLayout): Pair<TextView, TextView> {
        val label = root.findViewById<TextView>(R.id.key_label)
        val sub = root.findViewById<TextView>(R.id.key_sublabel)
        return label to sub
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDigitKey(
        id: Int,
        primary: String,
        sub: String,
        digit: Int,
        isPunct: Boolean = false
    ) {
        val root = keyRoot(id)
        val (label, subLabel) = labels(root)
        label.text = primary
        subLabel.text = sub

        var longFired = false
        val longRunnable = Runnable {
            longFired = true
            service?.onDigitDirect(digit)
            bumpAfterInput(restartMultiTapTimeout = false)
        }

        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    v.isPressed = true
                    handler.postDelayed(longRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(longRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !longFired) {
                        if (isPunct) {
                            service?.onPunctuationKey()
                            bumpAfterInput(restartMultiTapTimeout = false)
                        } else {
                            service?.onLetterKey(digit)
                            bumpAfterInput(restartMultiTapTimeout = true)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSpaceKey() {
        val root = keyRoot(R.id.key_0)
        val (label, subLabel) = labels(root)
        label.text = "␣"
        subLabel.text = "0"

        var longFired = false
        val longRunnable = Runnable {
            longFired = true
            service?.onSpaceKeyLongPress()
            bumpAfterInput(restartMultiTapTimeout = false)
        }

        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    v.isPressed = true
                    handler.postDelayed(longRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(longRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !longFired) {
                        service?.onSpaceKey()
                        bumpAfterInput(restartMultiTapTimeout = false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupShiftKey() {
        val root = keyRoot(R.id.key_shift)
        val (label, sub) = labels(root)
        shiftLabel = label
        shiftSub = sub
        label.text = "⇧"
        sub.text = ""

        root.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val next = when (svc.getShiftState()) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            svc.setShiftState(next)
            refreshFromService()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspaceKey() {
        val root = keyRoot(R.id.key_backspace)
        val (label, sub) = labels(root)
        label.text = "⌫"
        sub.text = ""

        val holdRunnable = object : Runnable {
            override fun run() {
                service?.onBackspaceHeld(isSlidingLeft = false)
                refreshFromService()
                handler.postDelayed(this, 50L)
            }
        }

        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    service?.onBackspace()
                    refreshFromService()
                    handler.postDelayed(holdRunnable, 400L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(holdRunnable)
                    service?.onBackspaceUp()
                    bumpAfterInput(restartMultiTapTimeout = false)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupActionKey() {
        val root = keyRoot(R.id.key_action)
        val (label, sub) = labels(root)
        actionLabel = label
        sub.text = "hold ⏎"
        label.text = "⏎"

        var longFired = false
        val longRunnable = Runnable {
            longFired = true
            service?.onActionKeyLongPress()
            bumpAfterInput(restartMultiTapTimeout = false)
        }

        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    v.isPressed = true
                    handler.postDelayed(longRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(longRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !longFired) {
                        service?.onActionKeyTap()
                        bumpAfterInput(restartMultiTapTimeout = false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun bumpAfterInput(restartMultiTapTimeout: Boolean) {
        handler.removeCallbacks(multiTapTimeoutRunnable)
        if (restartMultiTapTimeout) {
            handler.postDelayed(multiTapTimeoutRunnable, multiTapTimeoutMs)
        }
        refreshFromService()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
