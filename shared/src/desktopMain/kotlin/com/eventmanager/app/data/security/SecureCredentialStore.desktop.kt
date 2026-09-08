package com.eventmanager.app.data.security

import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.appDataDir
import org.bouncycastle.crypto.engines.AESFastEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Desktop fallback: AES-256-GCM file encrypted with a key derived from the app data path.
 * macOS/Windows Keychain integration can replace this later without API changes.
 */
actual fun createSecureCredentialStore(context: PlatformContext): SecureCredentialStore {
    val file = File(context.appDataDir, "secure_credentials.enc")
    return DesktopSecureCredentialStore(file, context.appDataDir.absolutePath)
}

private class DesktopSecureCredentialStore(
    private val file: File,
    private val saltSource: String,
) : SecureCredentialStore {
    private val lock = Any()
    private val cache = mutableMapOf<String, String>()

    init {
        loadFromDisk()
    }

    override fun getSecret(key: String): String? = synchronized(lock) {
        cache[key]?.takeIf { it.isNotEmpty() }
    }

    override fun putSecret(key: String, value: String) = synchronized(lock) {
        cache[key] = value
        persist()
    }

    override fun removeSecret(key: String) = synchronized(lock) {
        cache.remove(key)
        persist()
    }

    override fun containsSecret(key: String): Boolean = synchronized(lock) {
        cache.containsKey(key) && !cache[key].isNullOrEmpty()
    }

    override fun clearAll() {
        synchronized(lock) {
            cache.clear()
            persist()
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        runCatching {
            val decoded = Base64.getDecoder().decode(file.readText().trim())
            val plain = decrypt(decoded)
            plain.lineSequence().forEach { line ->
                val idx = line.indexOf('\u0001')
                if (idx > 0) {
                    cache[line.substring(0, idx)] = line.substring(idx + 1)
                }
            }
        }
    }

    private fun persist() {
        val payload = cache.entries.joinToString("\n") { "${it.key}\u0001${it.value}" }
        val encrypted = encrypt(payload.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        file.writeText(Base64.getEncoder().encodeToString(encrypted))
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    @Volatile
    private var cachedMasterKey: ByteArray? = null

    private fun masterKey(): ByteArray {
        cachedMasterKey?.let { return it }
        val spec = PBEKeySpec(
            "noctulist-desktop-secure-store".toCharArray(),
            saltSource.toByteArray(Charsets.UTF_8),
            120_000,
            256,
        )
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        cachedMasterKey = key
        return key
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val cipher = GCMBlockCipher(AESFastEngine())
        cipher.init(true, AEADParameters(KeyParameter(masterKey()), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val len = cipher.processBytes(plain, 0, plain.size, out, 0)
        cipher.doFinal(out, len)
        return nonce + out
    }

    private fun decrypt(blob: ByteArray): String {
        if (blob.size < 13) return ""
        val nonce = blob.copyOfRange(0, 12)
        val cipherBytes = blob.copyOfRange(12, blob.size)
        val cipher = GCMBlockCipher(AESFastEngine())
        cipher.init(false, AEADParameters(KeyParameter(masterKey()), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(cipherBytes.size))
        val len = cipher.processBytes(cipherBytes, 0, cipherBytes.size, out, 0)
        val finalLen = cipher.doFinal(out, len)
        return String(out, 0, len + finalLen, Charsets.UTF_8)
    }
}
