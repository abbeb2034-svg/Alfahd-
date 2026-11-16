package com.example.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityUtils {
    private const val ITERATIONS = 12_000
    private const val KEY_LENGTH = 256

    fun hashPassword(password: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(password.toCharArray(), salt)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "pbkdf2:$ITERATIONS:$saltB64:$hashB64"
    }

    fun verifyPassword(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Base64.decode(parts[2], Base64.NO_WRAP)
        val expectedHash = Base64.decode(parts[3], Base64.NO_WRAP)
        val actualHash = pbkdf2(password.toCharArray(), salt, iterations)
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    private fun pbkdf2(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = ITERATIONS
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun newUuid(): String = UUID.randomUUID().toString()

    fun generateCitizenCode(): String {
        val suffix = UUID.randomUUID().toString().substring(0, 6).uppercase(Locale.US)
        return "FAHD-$suffix"
    }

    fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return sdf.format(Date())
    }

    fun todayDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

    fun formatArabicDateTime(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
        return sdf.format(Date(millis))
    }
}
