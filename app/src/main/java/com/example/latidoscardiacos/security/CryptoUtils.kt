package com.example.latidoscardiacos.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Módulo de utilidades criptográficas según estándares de seguridad ISO 27000 / OWASP.
 * Implementa hashing seguro con PBKDF2 (compatible nativamente con Android sin conflictos de classpath) y SHA-256.
 */
object CryptoUtils {

    private const val ITERATIONS = 65536
    private const val KEY_LENGTH = 256 // bits
    private const val SALT_LENGTH_BYTES = 16 // 128 bits

    private val secureRandom = SecureRandom()

    /**
     * Genera un Salt aleatorio criptográficamente seguro.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Convierte ByteArray a cadena Hexadecimal.
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Convierte una cadena Hexadecimal a ByteArray.
     */
    fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    /**
     * Genera el Hash seguro de una contraseña utilizando PBKDF2 y el Salt provisto.
     */
    fun hashPasswordArgon2(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return bytesToHex(hash)
    }

    /**
     * Verifica una contraseña ingresada contra un Hash y Salt almacenados.
     */
    fun verifyPasswordArgon2(password: String, saltHex: String, expectedHashHex: String): Boolean {
        return try {
            val salt = hexToBytes(saltHex)
            val computedHash = hashPasswordArgon2(password, salt)
            computedHash.equals(expectedHashHex, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Calcula una firma/checksum SHA-256 de una trama de datos para garantizar la integridad (ISO 27000).
     */
    fun calculateChecksum(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest).take(8) // Retorna los primeros 8 caracteres hexadecimales como checksum de trama
    }
}
