# Add project specific ProGuard rules here.
-keep class com.orbixtv.app.data.** { *; }

# ── Media3 / ExoPlayer ──────────────────────────────────────────────────────
# Jaga seluruh kelas Media3 agar tidak di-strip/obfuscate oleh ProGuard.
# Tanpa rule ini, DASH (dan HLS) renderer bisa hilang di release build → blackscreen.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# DASH & DRM renderer harus tetap ada (di-load via reflection oleh ExoPlayer)
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.exoplayer.drm.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.source.** { *; }
-keep class androidx.media3.datasource.** { *; }
