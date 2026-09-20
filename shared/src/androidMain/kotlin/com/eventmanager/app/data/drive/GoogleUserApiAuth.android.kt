package com.eventmanager.app.data.drive

import com.eventmanager.app.platform.PlatformContext

/**
 * Android discards the OAuth refresh token during the Firebase sign-in code exchange
 * ([com.eventmanager.app.data.remote.AndroidFirebaseWebOAuth]), so there is no durable user
 * credential to hand to the Drive/Docs APIs yet. The import UI hides itself when unsupported.
 */
actual fun createGoogleUserApiAuth(platformContext: PlatformContext?): GoogleUserApiAuth =
    UnsupportedGoogleUserApiAuth()
