package com.eventmanager.app.data.utils

import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import java.security.SecureRandom

/**
 * Utility object for generating globally unique NanoIDs.
 * 
 * NanoIDs are used as the primary identifier for volunteers, replacing auto-incrementing Long IDs.
 * This enables conflict-free synchronization between distributed devices and Google Sheets.
 * 
 * The generated IDs are:
 * - 21 characters long (default NanoID length)
 * - URL-safe (using alphabet: A-Za-z0-9_-)
 * - Collision-resistant (1% probability of collision after ~149 billion IDs)
 * - Generated using SecureRandom for cryptographic randomness
 */
object NanoIdGenerator {
    
    // Thread-safe random instance for ID generation
    private val random = SecureRandom()
    
    // Default NanoID alphabet (URL-safe)
    private val DEFAULT_ALPHABET = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
    
    // Default ID length (21 characters provides excellent collision resistance)
    private const val DEFAULT_SIZE = 21
    
    /**
     * Generates a new globally unique NanoID for volunteers.
     * 
     * This should be called immediately when creating a new Volunteer object,
     * before any database insertion.
     * 
     * @return A 21-character URL-safe unique identifier
     */
    fun generateVolunteerId(): String {
        return NanoIdUtils.randomNanoId(random, DEFAULT_ALPHABET, DEFAULT_SIZE)
    }

    /**
     * Generates a new globally unique NanoID for guests (permanent and temporary).
     *
     * @return A 21-character URL-safe unique identifier
     */
    fun generateGuestId(): String {
        return NanoIdUtils.randomNanoId(random, DEFAULT_ALPHABET, DEFAULT_SIZE)
    }

    // Alphanumeric only: a guest form ID travels inside a URL that is copied by hand and pasted
    // into chats, where a leading '-' or a '_' swallowed by an underline is a real support call.
    private val URL_TOKEN_ALPHABET =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
    private const val URL_TOKEN_SIZE = 24

    /**
     * Generates the public token for a guest list form.
     *
     * This ID is the only thing standing between the public internet and the form, so it is
     * longer than a regular NanoID and drawn from a strictly alphanumeric alphabet.
     */
    fun generateGuestFormId(): String {
        return NanoIdUtils.randomNanoId(random, URL_TOKEN_ALPHABET, URL_TOKEN_SIZE)
    }
    
    /**
     * Validates if a given string is a valid NanoID format.
     * 
     * A valid NanoID must:
     * - Be exactly 21 characters long
     * - Contain only characters from the URL-safe alphabet (A-Za-z0-9_-)
     * - Not be blank
     * 
     * @param id The ID string to validate
     * @return true if the ID matches NanoID format, false otherwise
     */
    fun isValidNanoId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (id.length != DEFAULT_SIZE) return false
        return id.all { it in DEFAULT_ALPHABET }
    }
    
    /**
     * Checks if a string looks like an old Long ID (numeric only).
     * Used during migration to identify records that need ID conversion.
     * 
     * @param id The ID string to check
     * @return true if the ID appears to be an old numeric ID
     */
    fun isLegacyLongId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return id.toLongOrNull() != null
    }
    
    /**
     * Ensures the given ID is valid, generating a new NanoID if not.
     * 
     * This method handles:
     * - Null or blank IDs → generates new NanoID
     * - Legacy Long IDs (numeric) → generates new NanoID  
     * - Invalid format IDs → generates new NanoID
     * - Valid NanoIDs → returns as-is
     * 
     * Use this when loading data that might have corrupted or missing IDs
     * (e.g., from Google Sheets after manual editing, data recovery, etc.)
     * 
     * @param id The ID to validate and potentially replace
     * @param volunteerName Optional name for logging purposes
     * @return A valid NanoID (either the original if valid, or a newly generated one)
     */
    fun ensureValidNanoId(id: String?, volunteerName: String? = null): String {
        return when {
            id.isNullOrBlank() -> {
                val newId = generateVolunteerId()
                println("⚠️ Generated new NanoID for volunteer${volunteerName?.let { " '$it'" } ?: ""}: blank/null ID → $newId")
                newId
            }
            isLegacyLongId(id) -> {
                val newId = generateVolunteerId()
                println("⚠️ Generated new NanoID for volunteer${volunteerName?.let { " '$it'" } ?: ""}: legacy Long ID '$id' → $newId")
                newId
            }
            !isValidNanoId(id) -> {
                val newId = generateVolunteerId()
                println("⚠️ Generated new NanoID for volunteer${volunteerName?.let { " '$it'" } ?: ""}: invalid ID '$id' → $newId")
                newId
            }
            else -> id // Valid NanoID, return as-is
        }
    }
    
    /**
     * Checks if an ID needs regeneration (is invalid or legacy format).
     * 
     * @param id The ID to check
     * @return true if the ID should be replaced with a new NanoID
     */
    fun needsRegeneration(id: String?): Boolean {
        return id.isNullOrBlank() || isLegacyLongId(id) || !isValidNanoId(id)
    }
}
