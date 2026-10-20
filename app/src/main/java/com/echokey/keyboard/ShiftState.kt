package com.echokey.keyboard

/**
 * Shift state for the invisible layer.
 * Extracted so pure-Kotlin verification (kotlinc without Android SDK) can reference it.
 */
enum class ShiftState { OFF, ONCE, LOCKED }
