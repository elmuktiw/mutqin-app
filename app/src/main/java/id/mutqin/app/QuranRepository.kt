package id.mutqin.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Mengambil teks Uthmani per halaman: aset offline -> cache file -> api.quran.com -> fallback trigger. */
object QuranRepository {
    private const val TAG = "QuranRepository"

    fun audioUrl(surah: Int, ayah: Int, reciter: String = "Alafasy_128kbps"): String =
        "https://everyayah.com/data/$reciter/${surah.toString().padStart(3, '0')}${ayah.toString().padStart(3, '0')}.mp3"

    private fun tokenize(page: Int, surah: Int, ayah: Int, text: String): QuranAyah {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .mapIndexed { i, w -> QuranWord("p${page}_s${surah}_a${ayah}_w${i + 1}", w, ayah, i + 1) }
        return QuranAyah(surah, ayah, words)
    }

    private fun fromWordLists(page: Int, items: List<Triple<Int, Int, List<String>>>): List<QuranAyah> =
        items.map { (s, a, ws) ->
            QuranAyah(s, a, ws.mapIndexed { i, w -> QuranWord("p${page}_s${s}_a${a}_w${i + 1}", w, a, i + 1) })
        }

    suspend fun getPage(context: Context, page: Int): PageText = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext

        // 1) Data bawaan aplikasi
        QuranData.offlineAyahs(page)?.let { list ->
            return@withContext PageText(list.map { tokenize(page, it.surah, it.ayah, it.text) }, isFallback = false)
        }

        // 2) Cache di penyimpanan perangkat
        val cacheFile = File(File(appCtx.filesDir, "quran_cache"), "p$page.json")
        runCatching {
            if (cacheFile.exists()) {
                val arr = JSONArray(cacheFile.readText(Charsets.UTF_8))
                val items = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    val wArr = o.getJSONArray("w")
                    Triple(o.getInt("s"), o.getInt("a"), (0 until wArr.length()).map { i -> wArr.getString(i) })
                }
                if (items.isNotEmpty()) return@withContext PageText(fromWordLists(page, items), isFallback = false)
            }
        }

        // 3) api.quran.com (dengan paginasi agar halaman >10 ayat tidak terpotong)
        runCatching { fetchFromApi(page) }
            .onSuccess { items ->
                if (items.isNotEmpty()) {
                    runCatching {
                        cacheFile.parentFile?.mkdirs()
                        val arr = JSONArray()
                        items.forEach { (s, a, ws) ->
                            arr.put(JSONObject().put("s", s).put("a", a).put("w", JSONArray(ws)))
                        }
                        cacheFile.writeText(arr.toString(), Charsets.UTF_8)
                    }
                    return@withContext PageText(fromWordLists(page, items), isFallback = false)
                }
            }
            .onFailure { Log.i(TAG, "Fallback trigger untuk halaman $page: ${it.message}") }

        // 4) Fallback offline: hanya potongan awal halaman
        val meta = QuranData.pageMeta(page)
        val (startSurah, startAyah) = QuranData.startAyah(page)
        val words = meta.memoryTriggerArabic.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .mapIndexed { i, w -> QuranWord("p${page}_w${i + 1}", w, startAyah, i + 1) }
        PageText(listOf(QuranAyah(startSurah, startAyah, words)), isFallback = true)
    }

    private fun fetchFromApi(page: Int): List<Triple<Int, Int, List<String>>> {
        val result = ArrayList<Triple<Int, Int, List<String>>>()
        var apiPage = 1
        while (apiPage <= 8) {
            val url = URL(
                "https://api.quran.com/api/v4/verses/by_page/$page" +
                    "?words=true&word_fields=text_uthmani&per_page=20&page=$apiPage"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val root = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val verses = root.optJSONArray("verses") ?: break
                for (i in 0 until verses.length()) {
                    val v = verses.getJSONObject(i)
                    val key = v.optString("verse_key", "")
                    val parts = key.split(":")
                    val s = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val a = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val wArr = v.optJSONArray("words") ?: continue
                    val words = ArrayList<String>()
                    for (j in 0 until wArr.length()) {
                        val w = wArr.getJSONObject(j)
                        if (w.optString("char_type_name") == "end") continue // penanda akhir ayat
                        val t = w.optString("text_uthmani").ifEmpty { w.optString("text") }
                        if (t.isNotEmpty()) words += t
                    }
                    if (words.isNotEmpty()) result += Triple(s, a, words)
                }
                val next = root.optJSONObject("pagination")?.let { if (it.isNull("next_page")) null else it.optInt("next_page") }
                if (next == null || next <= apiPage) break
                apiPage = next
            } finally {
                conn.disconnect()
            }
        }
        return result
    }
}
