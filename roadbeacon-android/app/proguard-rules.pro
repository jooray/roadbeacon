# Keep Nostr event classes for serialization
-keep class app.roadbeacon.nostr.NostrEvent { *; }
-keep class app.roadbeacon.model.** { *; }

# Keep secp256k1 JNI
-keep class fr.acinq.secp256k1.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
