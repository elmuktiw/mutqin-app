package id.mutqin.app

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Teks Uthmani dan audio petunjuk seluruh 604 halaman dibundel di dalam APK (assets/pages dan assets/audio),
 * sehingga aplikasi sepenuhnya offline. Berkas dibuat oleh workflow "Generate Assets" (lihat README).
 */
object QuranRepository {
    private const val TAG = "QuranRepository"

    fun pageAssetPath(page: Int): String = "pages/p${page.toString().padStart(3, '0')}.json"

    fun audioAssetPath(page: Int): String = "audio/p${page.toString().padStart(3, '0')}.mp3"

    /** Pembuka akses klip audio 3,5 detik pertama halaman [page]; null jika berkas tidak ada. */
    fun openHintAudio(context: Context, page: Int): AssetFileDescriptor? =
        try {
            context.applicationContext.assets.openFd(audioAssetPath(page))
        } catch (e: Exception) {
            Log.w(TAG, "Audio halaman $page tidak ditemukan: ${e.message}")
            null
        }

    private fun wordId(page: Int, surah: Int, ayah: Int, index: Int) = "p${page}_s${surah}_a${ayah}_w$index"

    /** Memuat teks satu halaman dari assets; null jika berkas tidak ada/rusak (APK tidak lengkap). */
    suspend fun getPage(context: Context, page: Int): PageText? = withContext(Dispatchers.IO) {
        try {
            val json = context.applicationContext.assets.open(pageAssetPath(page))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(json)
            val ayahs = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val surah = o.getInt("s")
                val ayah = o.getInt("a")
                val wArr = o.getJSONArray("w")
                val words = (0 until wArr.length()).map { j ->
                    QuranWord(wordId(page, surah, ayah, j + 1), wArr.getString(j), ayah, j + 1)
                }
                QuranAyah(surah, ayah, words)
            }
            if (ayahs.isEmpty()) null else PageText(ayahs)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memuat teks halaman $page: ${e.message}")
            null
        }
    }
}
