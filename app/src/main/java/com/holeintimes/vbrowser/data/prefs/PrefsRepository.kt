package com.holeintimes.vbrowser.data.prefs

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.holeintimes.vbrowser.domain.AppLanguage
import com.holeintimes.vbrowser.domain.BookmarkEntry
import com.holeintimes.vbrowser.domain.FilesSort
import com.holeintimes.vbrowser.domain.HistoryEntry
import com.holeintimes.vbrowser.domain.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("vbrowser_prefs")

class PrefsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val language = stringPreferencesKey("language")
        val forceWebDarkMode = booleanPreferencesKey("force_web_dark")
        val autoDownload = booleanPreferencesKey("auto_download")
        val showFoundList = booleanPreferencesKey("show_found_list")
        val filesGroupOn = booleanPreferencesKey("files_group_on")
        val filesGroupPattern = stringPreferencesKey("files_group_pattern")
        val filesSort = stringPreferencesKey("files_sort")
        val filesSearchFilter = stringPreferencesKey("files_search_filter")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val privacyPasswordSalt = stringPreferencesKey("privacy_password_salt")
        val privacyPasswordHash = stringPreferencesKey("privacy_password_hash")
        val lastPlayedPath = stringPreferencesKey("last_played_path")
        val lastBrightness = floatPreferencesKey("last_brightness")
        val history = stringPreferencesKey("visit_history")
        val bookmarks = stringPreferencesKey("bookmarks")
        val urlIndex = stringSetPreferencesKey("url_index")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { p ->
        UserPreferences(
            language = when (p[Keys.language]) {
                "en" -> AppLanguage.English
                "zh" -> AppLanguage.Chinese
                else -> AppLanguage.System
            },
            forceWebDarkMode = p[Keys.forceWebDarkMode] ?: false,
            autoDownload = p[Keys.autoDownload] ?: false,
            showFoundList = p[Keys.showFoundList] ?: true,
            filesGroupOn = p[Keys.filesGroupOn] ?: false,
            filesGroupPattern = p[Keys.filesGroupPattern] ?: "",
            filesSort = when (p[Keys.filesSort]) {
                "date_oldest" -> FilesSort.DateOldest
                "title_asc" -> FilesSort.TitleAsc
                "title_desc" -> FilesSort.TitleDesc
                else -> FilesSort.DateNewest
            },
            filesSearchFilter = p[Keys.filesSearchFilter] ?: "",
            autoPlayNext = p[Keys.autoPlayNext] ?: false,
            lastPlayedPath = p[Keys.lastPlayedPath] ?: "",
            lastBrightness = p[Keys.lastBrightness] ?: -1f
        )
    }

    val hasPrivacyPassword: Flow<Boolean> = context.dataStore.data.map { prefs ->
        !prefs[Keys.privacyPasswordSalt].isNullOrBlank() &&
            !prefs[Keys.privacyPasswordHash].isNullOrBlank()
    }

    suspend fun isPrivacyPasswordSet(): Boolean = hasPrivacyPassword.first()

    suspend fun setLanguage(language: AppLanguage) {
        val value = when (language) {
            AppLanguage.English -> "en"
            AppLanguage.Chinese -> "zh"
            AppLanguage.System -> "system"
        }
        context.getSharedPreferences("vbrowser_prefs_meta", Context.MODE_PRIVATE)
            .edit().putString("language", value).apply()
        context.dataStore.edit {
            it[Keys.language] = value
        }
    }

    suspend fun setForceWebDarkMode(value: Boolean) {
        context.dataStore.edit { it[Keys.forceWebDarkMode] = value }
    }

    suspend fun setAutoDownload(value: Boolean) {
        context.dataStore.edit { it[Keys.autoDownload] = value }
    }

    suspend fun setShowFoundList(value: Boolean) {
        context.dataStore.edit { it[Keys.showFoundList] = value }
    }

    suspend fun setFilesGroupOn(value: Boolean) {
        context.dataStore.edit { it[Keys.filesGroupOn] = value }
    }

    suspend fun setFilesGroupPattern(value: String) {
        context.dataStore.edit { it[Keys.filesGroupPattern] = value }
    }

    suspend fun setFilesSort(sort: FilesSort) {
        val value = when (sort) {
            FilesSort.DateNewest -> "date_newest"
            FilesSort.DateOldest -> "date_oldest"
            FilesSort.TitleAsc -> "title_asc"
            FilesSort.TitleDesc -> "title_desc"
        }
        context.dataStore.edit { it[Keys.filesSort] = value }
    }

    suspend fun setFilesSearchFilter(value: String) {
        context.dataStore.edit { it[Keys.filesSearchFilter] = value }
    }

    suspend fun setAutoPlayNext(value: Boolean) {
        context.dataStore.edit { it[Keys.autoPlayNext] = value }
    }

    suspend fun verifyPrivacyPassword(password: String): Boolean {
        if (password.isEmpty()) return false
        val prefs = context.dataStore.data.first()
        val salt = prefs[Keys.privacyPasswordSalt]?.decodeBase64() ?: return false
        val expected = prefs[Keys.privacyPasswordHash]?.decodeBase64() ?: return false
        val actual = derivePassword(password, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    suspend fun setPrivacyPassword(password: String) {
        require(password.isNotEmpty())
        val salt = ByteArray(PRIVACY_SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derivePassword(password, salt)
        context.dataStore.edit {
            it[Keys.privacyPasswordSalt] = salt.encodeBase64()
            it[Keys.privacyPasswordHash] = hash.encodeBase64()
        }
    }

    suspend fun setLastPlayedPath(path: String) {
        context.dataStore.edit { it[Keys.lastPlayedPath] = path }
    }

    suspend fun setLastBrightness(value: Float) {
        context.dataStore.edit { it[Keys.lastBrightness] = value }
    }

    val history: Flow<List<HistoryEntry>> = context.dataStore.data.map { p ->
        val raw = p[Keys.history] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun addHistory(entry: HistoryEntry) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.history]
                ?.let { runCatching { json.decodeFromString<List<HistoryEntry>>(it) }.getOrNull() }
                .orEmpty()
            val updated = (listOf(entry) + current.filterNot { it.url == entry.url }).take(100)
            prefs[Keys.history] = json.encodeToString(updated)
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(Keys.history) }
    }

    val bookmarks: Flow<List<BookmarkEntry>> = context.dataStore.data.map { p ->
        val raw = p[Keys.bookmarks] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<BookmarkEntry>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun addBookmark(entry: BookmarkEntry) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.bookmarks]
                ?.let { runCatching { json.decodeFromString<List<BookmarkEntry>>(it) }.getOrNull() }
                .orEmpty()
            val updated = listOf(entry) + current.filterNot {
                it.url == entry.url && it.isPrivate == entry.isPrivate
            }
            prefs[Keys.bookmarks] = json.encodeToString(updated.take(200))
        }
    }

    suspend fun removeBookmark(url: String, isPrivate: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.bookmarks]
                ?.let { runCatching { json.decodeFromString<List<BookmarkEntry>>(it) }.getOrNull() }
                .orEmpty()
            prefs[Keys.bookmarks] = json.encodeToString(
                current.filterNot { it.url == url && it.isPrivate == isPrivate }
            )
        }
    }

    val downloadedUrls: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.urlIndex] ?: emptySet()
    }

    suspend fun markUrlDownloaded(url: String) {
        context.dataStore.edit {
            val set = (it[Keys.urlIndex] ?: emptySet()).toMutableSet()
            set.add(url)
            it[Keys.urlIndex] = set
        }
    }

    suspend fun removeUrlDownloaded(url: String) {
        context.dataStore.edit {
            val set = (it[Keys.urlIndex] ?: emptySet()).toMutableSet()
            set.remove(url)
            it[Keys.urlIndex] = set
        }
    }

    private suspend fun derivePassword(password: String, salt: ByteArray): ByteArray =
        withContext(Dispatchers.Default) {
            val chars = password.toCharArray()
            val spec = PBEKeySpec(chars, salt, PRIVACY_KDF_ITERATIONS, PRIVACY_HASH_BITS)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .encoded
            } finally {
                spec.clearPassword()
                chars.fill('\u0000')
            }
        }

    private fun ByteArray.encodeBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray? =
        runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()

    companion object {
        private const val PRIVACY_SALT_BYTES = 16
        private const val PRIVACY_KDF_ITERATIONS = 120_000
        private const val PRIVACY_HASH_BITS = 256
    }
}
