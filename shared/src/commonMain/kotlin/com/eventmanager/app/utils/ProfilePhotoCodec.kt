package com.eventmanager.app.utils

/**
 * Compress a picked image to JPEG for Firebase Storage.
 * Returns null if the bytes cannot be decoded or stay above [MAX_JPEG_BYTES] after compress.
 */
expect object ProfilePhotoCodec {
    fun compressToJpeg(sourceBytes: ByteArray): ByteArray?
    fun compressToThumbnailJpeg(sourceBytes: ByteArray): ByteArray?
    /** Small JPEG for public guest-form headers — must fit a Firestore string field. */
    fun compressToGuestFormLogoJpeg(sourceBytes: ByteArray): ByteArray?
}

const val PROFILE_PHOTO_MAX_EDGE_PX = 1024
const val PROFILE_PHOTO_JPEG_QUALITY = 80
const val PROFILE_PHOTO_MAX_JPEG_BYTES = 2 * 1024 * 1024

/** Tiny disk-cached avatar: enough for a 40–72dp circle, not a fullscreen view. */
const val PROFILE_PHOTO_THUMB_EDGE_PX = 96
const val PROFILE_PHOTO_THUMB_JPEG_QUALITY = 40
const val PROFILE_PHOTO_THUMB_MAX_BYTES = 48 * 1024

/** Public form logos: Firestore rejects a single field over ~1 MiB. */
const val GUEST_FORM_LOGO_MAX_EDGE_PX = 384
const val GUEST_FORM_LOGO_JPEG_QUALITY = 75
const val GUEST_FORM_LOGO_MAX_JPEG_BYTES = 120 * 1024
