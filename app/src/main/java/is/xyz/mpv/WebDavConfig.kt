package `is`.xyz.mpv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WebDavConfig(
    val serverUrl: String,
    val rootPath: String,
    val username: String,
    val password: String,
    val certificateFingerprint: String? = null,
)

class WebDavConfigStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): WebDavConfig? {
        val server = preferences.getString(KEY_SERVER, null) ?: return null
        val encryptedPassword = preferences.getString(KEY_PASSWORD, null) ?: return null
        return runCatching {
            WebDavConfig(
                serverUrl = server,
                rootPath = preferences.getString(KEY_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT,
                username = preferences.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME,
                password = decrypt(encryptedPassword),
                certificateFingerprint = preferences.getString(KEY_FINGERPRINT, null),
            )
        }.getOrNull()
    }

    fun save(config: WebDavConfig) {
        preferences.edit()
            .putString(KEY_SERVER, config.serverUrl.trimEnd('/'))
            .putString(KEY_ROOT, normalizeRoot(config.rootPath))
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, encrypt(config.password))
            .apply {
                if (config.certificateFingerprint == null) remove(KEY_FINGERPRINT)
                else putString(KEY_FINGERPRINT, config.certificateFingerprint)
            }
            .apply()
        if (config.certificateFingerprint == null)
            trustedCertificateFile.delete()
    }

    fun saveTrustedCertificate(pem: String) {
        trustedCertificateFile.writeText(pem)
    }

    fun trustedCertificatePath(): String? =
        trustedCertificateFile.takeIf { it.isFile && it.length() > 0 }?.absolutePath

    private val trustedCertificateFile: File
        get() = File(applicationContext.filesDir, TRUSTED_CERTIFICATE_FILE)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://Yuesong-NAS-US.local:5006"
        const val DEFAULT_ROOT = "/Data/TVShow/TV Shows/"
        const val DEFAULT_USERNAME = "cedricxie"

        private const val PREFERENCES = "webdav_config"
        private const val KEY_SERVER = "server"
        private const val KEY_ROOT = "root"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FINGERPRINT = "certificate_fingerprint"
        private const val KEY_ALIAS = "mpv_study_webdav_password"
        private const val TRUSTED_CERTIFICATE_FILE = "webdav-nas-cert.pem"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12

        fun normalizeRoot(path: String): String = "/${path.trim('/')}".let {
            if (it == "/") it else "$it/"
        }
    }
}

const val EXTRA_WEBDAV_STUDY_URL = "webdav_study_url"
const val EXTRA_WEBDAV_SUBTITLE_URL = "webdav_subtitle_url"
