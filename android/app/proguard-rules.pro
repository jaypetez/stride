# Stride release rules (ANDF-012). R8 full mode is the AGP default.
#
# We deliberately keep this file (almost) empty:
# - kotlinx.serialization ships its own consumer rules for @Serializable classes
#   (generated serializers are referenced statically, no reflection).
# - Hilt/Dagger, Room, WorkManager, Tink and OkHttp ship consumer rules.
# - The Garmin FIT SDK is used through its public API without reflection.
# The release build is exercised in CI (`assembleRelease` in android.yml and the
# release-android.yml dry run), and `ReleaseConfigTest` checks the build config.

# Keep line numbers for readable crash stack traces (the mapping file is a CI artifact, never public).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
