package id.mutqin.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Penyimpanan lokal: progres & pengaturan (file JSON atomik) + preferensi ringan (SharedPreferences). */
class MutqinStore(context: Context) {
    private val appContext = context.applicationContext
    private val progressFile = File(appContext.filesDir, "progress_v1.json")
    private val settingsFile = File(appContext.filesDir, "settings_v1.json")
    private val prefs = appContext.getSharedPreferences("mutqin_prefs", Context.MODE_PRIVATE)

    private fun readText(file: File): String? =
        try {
            if (file.exists()) AtomicFile(file).readFully().toString(Charsets.UTF_8) else null
        } catch (e: Exception) {
            null
        }

    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    fun loadProgress(): Map<Int, CardProgress> =
        try {
            readText(progressFile)?.let { Codec.progressFromJson(JSONObject(it)) } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

    fun saveProgress(map: Map<Int, CardProgress>) {
        writeAtomic(progressFile, Codec.progressToJson(map).toString())
    }

    fun loadSettings(): UserSettings =
        try {
            readText(settingsFile)?.let { Codec.settingsFromJson(JSONObject(it)) } ?: UserSettings()
        } catch (e: Exception) {
            UserSettings()
        }

    fun saveSettings(settings: UserSettings) {
        writeAtomic(settingsFile, Codec.settingsToJson(settings).toString())
    }

    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = "dev_" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    var lastPage: Int?
        get() = if (prefs.contains(KEY_LAST_PAGE)) prefs.getInt(KEY_LAST_PAGE, 1) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_LAST_PAGE) else putInt(KEY_LAST_PAGE, value) }.apply()
        }

    /** Tema aplikasi: "mushaf", "emerald", atau "dark". Pengguna lama (mode gelap/terang) dimigrasi otomatis. */
    var themeKey: String
        get() = prefs.getString(KEY_THEME, null) ?: if (prefs.getBoolean(KEY_DARK, true)) "dark" else "mushaf"
        set(value) { prefs.edit().putString(KEY_THEME, value).apply() }

    /** Waktu (ms) pengaturan terakhir diubah pengguna; dipakai untuk memilih versi terbaru saat sinkronisasi cloud. */
    var settingsUpdatedAt: Long
        get() = prefs.getLong(KEY_SETTINGS_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_SETTINGS_AT, value).apply() }

    /** Halaman yang berubah di perangkat ini dan belum dipastikan terkirim ke cloud. */
    var dirtyPages: Set<Int>
        get() = prefs.getStringSet(KEY_DIRTY, emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_DIRTY, value.map { it.toString() }.toSet()).apply() }

    fun markDirty(pages: Collection<Int>) {
        if (pages.isNotEmpty()) dirtyPages = dirtyPages + pages
    }

    fun lastPull(uid: String): Long = prefs.getLong("last_pull_$uid", 0L)

    fun setLastPull(uid: String, millis: Long) {
        prefs.edit().putLong("last_pull_$uid", millis).apply()
    }

    private companion object {
        const val KEY_SETTINGS_AT = "settings_updated_at"
        const val KEY_DIRTY = "dirty_pages"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_PAGE = "last_page"
        const val KEY_DARK = "dark_mode"
        const val KEY_THEME = "theme_key"
    }
}
