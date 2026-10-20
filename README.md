**Note.** The code in this project was generated automatically to match my design, and I tested it from the user side.

# EchoKey

A 12-key Android keyboard. The input path is an `InputMethodService` with two engines, multi-tap and T9, plus a punctuation handler and a backspace handler.

The punctuation key is the part that has to feel right. One tap after a word inserts punctuation. Another tap builds compounds such as `...` and `?!`. Mid-word, the same key inserts the apostrophe in a contraction. The check for that path is `2-2-6-1-8-1` producing `can't?`.

Password fields force multi-tap and turn suggestions and learning off.

## Status

The service, both engines, punctuation, and backspace are in and covered by a pure-logic check. The on-screen keyboard is a minimal 12-key view. Voice, emoji, and a floating window are not built yet.

Open the folder in Android Studio and run `./gradlew :app:assembleDebug`.
