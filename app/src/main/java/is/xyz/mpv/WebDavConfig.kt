package `is`.xyz.mpv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import java.util.UUID
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
    val id: String = "",
    val name: String = "",
)

class WebDavConfigStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): WebDavConfig? {
        ensureMigrated()
        val profiles = listInternal()
        if (profiles.isEmpty()) return null
        val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    @Synchronized
    fun list(): List<WebDavConfig> {
        ensureMigrated()
        return listInternal()
    }

    @Synchronized
    fun save(config: WebDavConfig): WebDavConfig {
        ensureMigrated()
        val saved = config.copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            name = config.name.ifBlank { defaultProfileName(config.serverUrl) },
        )
        val ids = profileIds().toMutableList().apply {
            if (saved.id !in this) add(saved.id)
        }
        preferences.edit()
            .putString(profileKey(saved.id, FIELD_NAME), saved.name)
            .putString(profileKey(saved.id, FIELD_SERVER), saved.serverUrl.trimEnd('/'))
            .putString(profileKey(saved.id, FIELD_ROOT), normalizeRoot(saved.rootPath))
            .putString(profileKey(saved.id, FIELD_USERNAME), saved.username)
            .putString(profileKey(saved.id, FIELD_PASSWORD), encrypt(saved.password))
            .apply {
                if (saved.certificateFingerprint == null) {
                    remove(profileKey(saved.id, FIELD_FINGERPRINT))
                } else {
                    putString(profileKey(saved.id, FIELD_FINGERPRINT), saved.certificateFingerprint)
                }
            }
            .putString(KEY_PROFILE_IDS, ids.joinToString(PROFILE_SEPARATOR))
            .putString(KEY_ACTIVE_PROFILE, saved.id)
            .apply()
        return saved
    }

    @Synchronized
    fun select(id: String): WebDavConfig? {
        ensureMigrated()
        val selected = listInternal().firstOrNull { it.id == id } ?: return null
        preferences.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
        return selected
    }

    @Synchronized
    fun delete(id: String): WebDavConfig? {
        ensureMigrated()
        val ids = profileIds().filterNot { it == id }
        val editor = preferences.edit()
        PROFILE_FIELDS.forEach { editor.remove(profileKey(id, it)) }
        editor.putString(KEY_PROFILE_IDS, ids.joinToString(PROFILE_SEPARATOR))
        if (preferences.getString(KEY_ACTIVE_PROFILE, null) == id) {
            if (ids.isEmpty()) editor.remove(KEY_ACTIVE_PROFILE)
            else editor.putString(KEY_ACTIVE_PROFILE, ids.first())
        }
        editor.apply()
        return load()
    }

    private fun listInternal(): List<WebDavConfig> = profileIds().mapNotNull(::loadProfile)

    private fun loadProfile(id: String): WebDavConfig? {
        val server = preferences.getString(profileKey(id, FIELD_SERVER), null) ?: return null
        val encryptedPassword = preferences.getString(profileKey(id, FIELD_PASSWORD), null) ?: return null
        return runCatching {
            WebDavConfig(
                id = id,
                name = preferences.getString(profileKey(id, FIELD_NAME), null)
                    ?: defaultProfileName(server),
                serverUrl = server,
                rootPath = preferences.getString(profileKey(id, FIELD_ROOT), DEFAULT_ROOT) ?: DEFAULT_ROOT,
                username = preferences.getString(profileKey(id, FIELD_USERNAME), DEFAULT_USERNAME)
                    ?: DEFAULT_USERNAME,
                password = decrypt(encryptedPassword),
                certificateFingerprint = preferences.getString(
                    profileKey(id, FIELD_FINGERPRINT),
                    null,
                ),
            )
        }.getOrNull()
    }

    private fun loadLegacy(): WebDavConfig? {
        val server = preferences.getString(KEY_SERVER, null) ?: return null
        val encryptedPassword = preferences.getString(KEY_PASSWORD, null) ?: return null
        return runCatching {
            WebDavConfig(
                serverUrl = server,
                rootPath = preferences.getString(KEY_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT,
                username = preferences.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME,
                password = decrypt(encryptedPassword),
                certificateFingerprint = preferences.getString(KEY_FINGERPRINT, null),
                id = LEGACY_PROFILE_ID,
                name = defaultProfileName(server),
            )
        }.getOrNull()
    }

    private fun ensureMigrated() {
        if (preferences.contains(KEY_PROFILE_IDS)) return
        val legacy = loadLegacy()
        if (legacy == null) {
            preferences.edit().putString(KEY_PROFILE_IDS, "").apply()
            return
        }
        val encryptedPassword = preferences.getString(KEY_PASSWORD, null) ?: return
        preferences.edit()
            .putString(profileKey(legacy.id, FIELD_NAME), legacy.name)
            .putString(profileKey(legacy.id, FIELD_SERVER), legacy.serverUrl)
            .putString(profileKey(legacy.id, FIELD_ROOT), legacy.rootPath)
            .putString(profileKey(legacy.id, FIELD_USERNAME), legacy.username)
            .putString(profileKey(legacy.id, FIELD_PASSWORD), encryptedPassword)
            .apply {
                legacy.certificateFingerprint?.let {
                    putString(profileKey(legacy.id, FIELD_FINGERPRINT), it)
                }
            }
            .putString(KEY_PROFILE_IDS, legacy.id)
            .putString(KEY_ACTIVE_PROFILE, legacy.id)
            .apply()
    }

    private fun profileIds(): List<String> = preferences
        .getString(KEY_PROFILE_IDS, "")
        .orEmpty()
        .split(PROFILE_SEPARATOR)
        .filter(String::isNotBlank)

    private fun profileKey(id: String, field: String) = "profile.$id.$field"

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
        private const val KEY_PROFILE_IDS = "profile_ids"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_ALIAS = "mpv_study_webdav_password"
        private const val LEGACY_PROFILE_ID = "legacy"
        private const val PROFILE_SEPARATOR = "\n"
        private const val FIELD_NAME = "name"
        private const val FIELD_SERVER = "server"
        private const val FIELD_ROOT = "root"
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_FINGERPRINT = "fingerprint"
        private val PROFILE_FIELDS = listOf(
            FIELD_NAME,
            FIELD_SERVER,
            FIELD_ROOT,
            FIELD_USERNAME,
            FIELD_PASSWORD,
            FIELD_FINGERPRINT,
        )
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12

        fun normalizeRoot(path: String): String = "/${path.trim('/')}".let {
            if (it == "/") it else "$it/"
        }

        fun defaultProfileName(serverUrl: String): String = runCatching {
            URI(serverUrl).host?.takeIf(String::isNotBlank)
        }.getOrNull() ?: "NAS"
    }
}

const val EXTRA_WEBDAV_STUDY_URL = "webdav_study_url"
const val EXTRA_WEBDAV_SUBTITLE_URL = "webdav_subtitle_url"
const val EXTRA_WEBDAV_SECONDARY_SUBTITLE_URL = "webdav_secondary_subtitle_url"
