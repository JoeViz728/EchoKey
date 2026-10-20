# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK.

# Keep the InputMethodService and our engines visible to the system.
-keep public class com.echokey.keyboard.EchoKeyService { *; }
-keep public class com.echokey.keyboard.*Engine { *; }
