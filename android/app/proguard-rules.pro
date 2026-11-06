# Stride release rules (ANDF-012). R8 full mode is the AGP default.
#
# We deliberately keep this file (almost) empty:
# - kotlinx.serialization ships its own consumer rules for @Serializable classes
#   (generated serializers are referenced statically, no reflection).
# - Hilt/Dagger, Room, WorkManager, Tink and OkHttp ship consumer rules.
# - The Garmin FIT SDK is used through its public API without reflection.
# The minified release is built on every Android PR (`:app:assembleRelease` in
# .github/workflows/android.yml), where ManifestSecurityTest also checks the merged
# release manifest; release-android.yml builds the signed APK/AAB. ReleaseConfigTest
# checks the build config.

# Keep line numbers for readable crash stack traces. The R8 mapping file is attached to
# each GitHub Release (release-android.yml): the source is public, so it reveals nothing new.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
