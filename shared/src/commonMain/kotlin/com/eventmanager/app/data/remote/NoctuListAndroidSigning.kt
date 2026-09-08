package com.eventmanager.app.data.remote

/**
 * Legacy Android signing fingerprints — kept for reference / Play Console setup only.
 * Firebase Sign-In uses localhost Web OAuth (same redirect URIs as Desktop); no SHA-1 per institution.
 */
object NoctuListAndroidSigning {
    const val PACKAGE_NAME = "com.eventmanager.app"

    /**
     * Production / sideload release builds.
     * Must match the keystore uploaded to GitHub secrets (`ANDROID_KEYSTORE_BASE64`)
     * and the Android Studio `~/.android/debug.keystore` currently used to ship updates.
     * A CI APK signed with any other key is rejected on device ("app not updated").
     */
    const val SHA1_RELEASE = "67:1C:E8:D6:DD:CC:01:5D:2C:62:C8:82:DC:5C:84:FA:05:EC:3D:29"

    /** Local dev builds signed with the same Android Studio debug keystore. */
    const val SHA1_DEBUG = "67:1C:E8:D6:DD:CC:01:5D:2C:62:C8:82:DC:5C:84:FA:05:EC:3D:29"
}
