package id.mutqin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class OfflineAyah(val surah: Int, val ayah: Int, val text: String)

/** Data statis mushaf yang dimuat dari assets/: daftar surat, trigger 604 halaman, ayat pembuka tiap halaman. */
object QuranData {
    private const val BASMALAH = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ..."

    private var inited = false
    var surahs: List<SurahMeta> = emptyList()
        private set
    private var triggers: List<String> = emptyList()
    private var startAyahs: List<IntArray> = emptyList()
    private var offline: Map<Int, List<OfflineAyah>> = emptyMap()
    private val metaCache = arrayOfNulls<QuranPageMeta>(QuranStructure.TOTAL_PAGES + 1)

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        val assets = context.applicationContext.assets
        fun read(name: String): String = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

        val sArr = JSONArray(read("surahs.json"))
        surahs = (0 until sArr.length()).map {
            val o = sArr.getJSONObject(it)
            SurahMeta(
                o.getInt("number"), o.getString("nameArabic"), o.getString("nameLatin"),
                o.getInt("startPage"), o.getInt("endPage"), o.getInt("totalVerses")
            )
        }

        val tArr = JSONArray(read("page_triggers.json"))
        triggers = (0 until tArr.length()).map { tArr.getString(it) }

        val aArr = JSONArray(read("page_start_ayahs.json"))
        startAyahs = (0 until aArr.length()).map {
            val pair = aArr.getJSONArray(it)
            intArrayOf(pair.getInt(0), pair.getInt(1))
        }

        val oObj = JSONObject(read("offline_pages.json"))
        val map = HashMap<Int, List<OfflineAyah>>()
        val keys = oObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = oObj.getJSONArray(k)
            map[k.toInt()] = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                OfflineAyah(o.getInt("surah"), o.getInt("ayah"), o.getString("text"))
            }
        }
        offline = map
        inited = true
    }

    fun offlineAyahs(page: Int): List<OfflineAyah>? = offline[page]

    fun triggerText(page: Int): String = triggers.getOrNull(page - 1) ?: BASMALAH

    /** Pair(surah, ayah) pembuka halaman (dipakai untuk audio petunjuk). */
    fun startAyah(page: Int): Pair<Int, Int> {
        val e = startAyahs.getOrNull(page - 1) ?: return 1 to 1
        return e[0] to e[1]
    }

    fun surahsForPage(page: Int): List<SurahMeta> = surahs.filter { page >= it.startPage && page <= it.endPage }

    fun surahByNumber(n: Int): SurahMeta? = surahs.firstOrNull { it.number == n }

    fun pageMeta(page: Int): QuranPageMeta {
        val p = page.coerceIn(1, QuranStructure.TOTAL_PAGES)
        metaCache[p]?.let { return it }

        val info = QuranStructure.lembarAndSide(p)
        val onPage = surahsForPage(p)
        val primary = onPage.firstOrNull() ?: surahs.first()
        val meta = QuranPageMeta(
            pageNumber = p,
            juz = info.juz,
            lembar = info.lembar,
            side = info.side,
            isRightPage = p % 2 == 1,
            surahNumber = primary.number,
            surahNameArabic = primary.nameArabic,
            surahNameLatin = primary.nameLatin,
            ayahRange = if (onPage.size > 1) onPage.joinToString(", ") { it.nameLatin } else primary.nameLatin,
            memoryTriggerArabic = triggerText(p)
        )
        metaCache[p] = meta
        return meta
    }

    val allPages: List<QuranPageMeta> by lazy { (1..QuranStructure.TOTAL_PAGES).map { pageMeta(it) } }
}
