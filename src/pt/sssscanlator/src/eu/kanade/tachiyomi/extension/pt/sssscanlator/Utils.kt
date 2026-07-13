package eu.kanade.tachiyomi.extension.pt.sssscanlator

import android.util.Base64
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.extractNextJs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val SALTED_PREFIX = "Salted__"
private const val SALT_SIZE = 8
private const val AES_KEY_SIZE = 32
private const val AES_IV_SIZE = 16

/**
 * Decrypts a base64 payload produced by the site's front-end using CryptoJS's
 * `AES.encrypt(plaintext, passphrase)` helper (OpenSSL-compatible "Salted__" envelope,
 * key/IV derived via the classic EVP_BytesToKey/MD5 scheme). Returns null on any failure
 * so callers can fall back to other parsing strategies.
 */
internal fun decryptCryptoJsAes(base64Ciphertext: String, passphrase: String): String? = runCatching {
    val raw = Base64.decode(base64Ciphertext, Base64.DEFAULT)
    require(raw.size > SALTED_PREFIX.length + SALT_SIZE)
    require(String(raw, 0, SALTED_PREFIX.length, Charsets.US_ASCII) == SALTED_PREFIX)

    val salt = raw.copyOfRange(SALTED_PREFIX.length, SALTED_PREFIX.length + SALT_SIZE)
    val ciphertext = raw.copyOfRange(SALTED_PREFIX.length + SALT_SIZE, raw.size)
    val keyAndIv = deriveKeyAndIv(passphrase.toByteArray(Charsets.UTF_8), salt)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(keyAndIv.copyOfRange(0, AES_KEY_SIZE), "AES"),
        IvParameterSpec(keyAndIv.copyOfRange(AES_KEY_SIZE, AES_KEY_SIZE + AES_IV_SIZE)),
    )

    String(cipher.doFinal(ciphertext), Charsets.UTF_8)
}.getOrNull()

private fun deriveKeyAndIv(passphrase: ByteArray, salt: ByteArray): ByteArray {
    val result = ByteArray(AES_KEY_SIZE + AES_IV_SIZE)
    var digest = ByteArray(0)
    var generated = 0
    val md5 = MessageDigest.getInstance("MD5")

    while (generated < result.size) {
        digest = md5.digest(digest + passphrase + salt)
        val copyLen = minOf(digest.size, result.size - generated)
        digest.copyInto(result, generated, 0, copyLen)
        generated += copyLen
    }

    return result
}

internal fun extractSeriesPayload(document: Document, mangaSlug: String): SeriesPayloadDto {
    require(mangaSlug.isNotBlank()) { "Slug da obra nao encontrado na URL" }

    return document.extractNextJs<SeriesPayloadDto> { element ->
        element.matchesSeriesPayload(mangaSlug)
    } ?: throw IllegalStateException("Payload da obra nao encontrado para slug=$mangaSlug")
}

internal fun extractBadgeTexts(titleElement: Element?): List<String> {
    val nearbyElements = listOfNotNull(
        titleElement,
        titleElement?.previousElementSibling(),
        titleElement?.nextElementSibling(),
        titleElement?.nextElementSibling()?.nextElementSibling(),
        titleElement?.parent(),
        titleElement?.parent()?.parent(),
    )

    return nearbyElements
        .flatMap { element -> element.select("span[data-slot=badge]") }
        .map { it.text() }
        .filter(String::isNotEmpty)
        .distinct()
}

internal fun isStatusBadge(text: String): Boolean = parseStatus(text) != SManga.UNKNOWN

internal fun parseStatus(statusText: String?): Int = when (statusText?.lowercase()) {
    "em lancamento", "em lançamento", "ongoing" -> SManga.ONGOING
    "completo", "concluido", "concluído", "completed" -> SManga.COMPLETED
    "hiato", "hiatus" -> SManga.ON_HIATUS
    "cancelado", "canceled", "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private fun JsonElement.matchesSeriesPayload(expectedSlug: String): Boolean {
    val payload = this as? JsonObject ?: return false
    if (payload["slug"]?.jsonPrimitive?.contentOrNull != expectedSlug) return false

    // chapters now travel as an AES-encrypted string blob rather than a plain array
    val hasEncryptedChapters = (payload["encryptedChapters"] as? JsonPrimitive)?.contentOrNull != null

    return hasEncryptedChapters &&
        (
            "chapterTotal" in payload ||
                "refId" in payload ||
                "coverImage" in payload ||
                "description" in payload
            )
}
