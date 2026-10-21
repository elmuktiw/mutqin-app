package id.mutqin.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===========================================================================
// 1. Leitner 7 kotak + penilaian lokal berbasis metode Dr. Ali Rajabi
// ===========================================================================

object Leitner {
    val INTERVALS_DAYS: Map<Int, Int> = mapOf(
        1 to 1,     // Harian
        2 to 3,     // 3 hari
        3 to 7,     // 1 pekan
        4 to 14,    // 2 pekan
        5 to 30,    // 1 bulan
        6 to 60,    // 2 bulan
        7 to 120    // 4 bulan (mutqin permanen)
    )

    val BOX_DESCRIPTIONS: Map<Int, String> = mapOf(
        1 to "Box 1: Harian (Penguatan Awal / Evaluasi)",
        2 to "Box 2: Siklus 3 Hari (Stabilisasi Jangka Pendek)",
        3 to "Box 3: Siklus 1 Pekan (Konsolidasi Memori)",
        4 to "Box 4: Siklus 2 Pekan (Retensi Menengah)",
        5 to "Box 5: Siklus 1 Bulan (Transisi Memori Jangka Panjang)",
        6 to "Box 6: Siklus 2 Bulan (Hafalan Kuat/Kukuh)",
        7 to "Box 7: Siklus 4 Bulan (Mutqin Permanen)"
    )

    fun intervalFor(box: Int): Int = INTERVALS_DAYS[box] ?: 1
}

object SrsEngine {

    fun intervalLabel(days: Int): String = when {
        days == 1 -> "Besok (24 jam)"
        days < 7 -> "$days Hari lagi"
        days == 7 -> "1 Pekan lagi"
        days == 14 -> "2 Pekan lagi"
        days == 30 -> "1 Bulan lagi"
        else -> "$days Hari lagi"
    }

    fun evaluate(input: EvaluationInput): EvaluationResult {
        val rating = input.userRating
        val mistakes = input.mistakeCount
        val hesitations = input.hesitationCount
        val skipped = input.skippedCount
        val tajweed = input.tajweedCount
        val box = input.currentBox

        // Bobot beban kognitif: penilaian subjektif (52..100) dikurangi penalti objektif.
        val baseUserScore = rating / 5.0 * 60 + 40
        val penalty = mistakes * 12 + skipped * 10 + hesitations * 4 + tajweed * 3
        val composite = max(10, min(100, (baseUserScore - penalty).roundToInt()))

        val grade: String
        val recommendedBox: Int
        val intervalDays: Int
        when {
            composite >= 88 -> {
                grade = "Sangat Lancar"
                recommendedBox = min(7, box + 1)
                intervalDays = Leitner.intervalFor(recommendedBox)
            }
            composite >= 72 -> {
                grade = "Lancar"
                recommendedBox = min(7, box + 1)
                intervalDays = Leitner.intervalFor(recommendedBox)
            }
            composite >= 52 -> {
                grade = "Cukup"
                recommendedBox = max(1, box)
                intervalDays = Leitner.intervalFor(recommendedBox)
            }
            composite >= 35 -> {
                grade = "Kurang Lancar"
                recommendedBox = 1
                intervalDays = 1
            }
            else -> {
                grade = "Perlu Diulang"
                recommendedBox = 1
                intervalDays = 1
            }
        }

        val diagnosis = when {
            mistakes == 0 && skipped == 0 && hesitations == 0 ->
                "Retensi memori sangat prima. Jalur saraf hafalan halaman ini terhubung kuat tanpa friksi waqaf."
            skipped > 0 ->
                "Ditemukan $skipped bagian terlewat. Ini indikasi \"loncatan memori\" yang biasa terjadi pada ayat-ayat mutasyabihat atau kemiripan rima akhir ayat."
            mistakes > 0 ->
                "Tercatat $mistakes salah lafal/kata. Terjadi interferensi fonetik atau kemiripan kata di halaman lain."
            hesitations > 0 ->
                "Terdapat $hesitations keraguan (hesitation). Memori sudah terbentuk namun kecepatan retrieval (pemanggilan) masih butuh penguatan jembatan kata."
            else ->
                "Hafalan berada pada fase transisi konsolidasi memori."
        }

        val taktik = when {
            mistakes > 0 || skipped > 0 -> listOf(
                "Taktik Jembatan Visual: Ulangi 5x transisi antara 2 kata sebelum dan sesudah kata yang salah tanpa melihat mushaf.",
                "Active Recall Trigger: Tutup mushaf, sebutkan hanya 3 kata awal dari setiap baris halaman ini untuk mengunci peta spasial."
            )
            hesitations > 0 -> listOf(
                "Taktik Ritme & Nafas: Bacakan halaman dengan tempo stabil perlahan (tartil) untuk menghilangkan jeda keraguan.",
                "Mental Mapping: Bayangkan letak posisi ayat pada lembar mushaf (atas, tengah, atau bawah) saat melafalkan."
            )
            else -> listOf(
                "Taktik Pemantapan Mutqin: Uji hafalan dengan membaca sambil mendengarkan atau sambung ayat acak.",
                "Pertahankan interval Spaced Repetition untuk memasukkan hafalan ke memori jangka panjang (LTM)."
            )
        }

        return EvaluationResult(
            compositeScore = composite,
            aiGrade = grade,
            recommendedBox = recommendedBox,
            intervalDays = intervalDays,
            intervalLabel = intervalLabel(intervalDays),
            scientificDiagnosis = diagnosis,
            taktikAliRajabi = taktik
        )
    }
}

// ===========================================================================
// 2. Antrean kartu: "Bayar Utang Dulu" -> jatuh tempo hari ini -> halaman baru
// ===========================================================================

enum class PickReason { DUE_SRS, NEW_UNREVIEWED }

data class NextCardSelection(
    val pageNumber: Int,
    val reason: PickReason,
    val box: Int? = null,
    val isOverdueDebt: Boolean = false
)

object QueueEngine {

    /**
     * Menjaga urutan antrean FIFO halaman yang belum dimurajaah.
     * - Pertama kali: urut naik (halaman terdepan lebih dulu).
     * - Jika cakupan diperluas: halaman baru masuk ke BELAKANG antrean.
     */
    fun syncScopeQueueOrder(previousQueue: List<Int>?, newScopePages: List<Int>): List<Int> {
        if (previousQueue.isNullOrEmpty()) return newScopePages.sorted()
        val scopeSet = newScopePages.toHashSet()
        val prevSet = previousQueue.toHashSet()
        val preserved = previousQueue.filter { it in scopeSet }
        val newlyAdded = newScopePages.filter { it !in prevSet }.sorted()
        return preserved + newlyAdded
    }

    fun nextFlashcard(
        progress: Map<Int, CardProgress>,
        activeScopePages: List<Int>,
        excludePage: Int? = null,
        scopeQueueOrder: List<Int>? = null,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): NextCardSelection? {
        if (activeScopePages.isEmpty()) return null

        val todayStart = LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant()

        // 1) Kartu jatuh tempo
        data class Due(val page: Int, val box: Int, val overdueMs: Long, val isDebt: Boolean)

        val due = ArrayList<Due>()
        for (p in activeScopePages) {
            if (excludePage != null && p == excludePage) continue
            val prog = progress[p] ?: continue
            if (prog.repetitions <= 0) continue
            val next = Time.parse(prog.nextReviewDate)
            if (!next.isAfter(now)) {
                due += Due(
                    page = p,
                    box = prog.box,
                    overdueMs = now.toEpochMilli() - next.toEpochMilli(),
                    isDebt = next.isBefore(todayStart)
                )
            }
        }

        if (due.isNotEmpty()) {
            // Utang hari sebelumnya dulu (yang paling lama menunggak di depan);
            // lalu jatuh tempo hari ini: Box 1 -> 7, kemudian yang paling lama menunggu.
            val sorted = due.sortedWith(Comparator { a, b ->
                when {
                    a.isDebt && !b.isDebt -> -1
                    !a.isDebt && b.isDebt -> 1
                    a.isDebt && b.isDebt -> b.overdueMs.compareTo(a.overdueMs)
                    a.box != b.box -> a.box.compareTo(b.box)
                    else -> b.overdueMs.compareTo(a.overdueMs)
                }
            })
            val top = sorted.first()
            return NextCardSelection(top.page, PickReason.DUE_SRS, top.box, top.isDebt)
        }

        // 2) Halaman baru sesuai urutan antrean
        val scopeSet = activeScopePages.toHashSet()
        val effectiveQueue: List<Int> =
            if (!scopeQueueOrder.isNullOrEmpty()) {
                val queued = scopeQueueOrder.toHashSet()
                scopeQueueOrder.filter { it in scopeSet } + activeScopePages.filter { it !in queued }
            } else {
                activeScopePages.sorted()
            }

        val unreviewed = effectiveQueue.firstOrNull { p ->
            if (excludePage != null && p == excludePage) return@firstOrNull false
            val prog = progress[p]
            prog == null || prog.repetitions == 0
        }
        if (unreviewed != null) return NextCardSelection(unreviewed, PickReason.NEW_UNREVIEWED)

        // 3) Semua sudah direview dan tidak ada yang jatuh tempo
        return null
    }
}

// ===========================================================================
// 3. Statistik (kartu ringkasan, grafik garis, juz, trouble pages)
// ===========================================================================

object Time {
    fun parse(iso: String): Instant = try {
        Instant.parse(iso)
    } catch (_: Exception) {
        java.time.OffsetDateTime.parse(iso).toInstant()
    }

    fun nowIso(): String = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()

    fun localDate(iso: String, zone: ZoneId): LocalDate = LocalDate.ofInstant(parse(iso), zone)

    /** Instant "sekarang + N hari" dalam kalender lokal (jam yang sama). */
    fun plusDaysIso(days: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.ZonedDateTime.now(zone).plusDays(days.toLong()).toInstant()
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
}

data class TroublePage(val pageNumber: Int, val totalMistakes: Int, val lastScore: Int)

data class DailyStats(
    val reviewedTodayCount: Int = 0,
    val dueTodayCount: Int = 0,
    val streakDays: Int = 0,
    val totalCardsActive: Int = 0,
    val boxDistribution: Map<Int, Int> = (1..7).associateWith { 0 },
    /** 0 jika belum ada murajaah hari ini. */
    val averageScoreToday: Int = 0,
    val troublePages: List<TroublePage> = emptyList()
)

data class ReviewEntry(val pageNumber: Int, val score: Int?, val timestamp: String)

data class JuzStat(
    val juz: Int,
    val totalPages: Int,
    val reviewedCount: Int,
    val isComplete: Boolean,
    val averageScore: Int?,
    val progressPercent: Int
)

enum class ChartRange(val label: String, val days: Int) {
    WEEK("1 Minggu", 7),
    MONTH("1 Bulan", 30),
    THREE_MONTHS("3 Bulan", 90),
    SIX_MONTHS("6 Bulan", 180),
    YEAR("1 Tahun", 365)
}

data class ChartPoint(val label: String, val count: Int, val date: LocalDate)

object StatsEngine {
    private val DAY_SHORT = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")           // 0 = Ahad
    private val MONTH_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    fun dailyStats(
        cards: Collection<CardProgress>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): DailyStats {
        val today = LocalDate.ofInstant(now, zone)
        var reviewedToday = 0
        var dueToday = 0
        var scoreSum = 0
        var scoreCount = 0
        val boxes = (1..7).associateWithTo(LinkedHashMap()) { 0 }
        val trouble = ArrayList<TroublePage>()

        for (card in cards) {
            if (card.box in 1..7) boxes[card.box] = (boxes[card.box] ?: 0) + 1

            val last = card.lastReviewedDate
            if (last != null && Time.localDate(last, zone) == today) {
                reviewedToday++
                val s = card.lastCompositeScore
                if (s != null && s != 0) {
                    scoreSum += s
                    scoreCount++
                }
            }

            if (!Time.parse(card.nextReviewDate).isAfter(now)) dueToday++

            val recent = card.history.take(3).sumOf { it.mistakeCount + it.skippedCount }
            val ls = card.lastCompositeScore
            if (recent > 0 || (ls != null && ls != 0 && ls < 60)) {
                trouble += TroublePage(card.pageNumber, recent, if (ls == null || ls == 0) 50 else ls)
            }
        }

        trouble.sortByDescending { it.totalMistakes }

        return DailyStats(
            reviewedTodayCount = reviewedToday,
            dueTodayCount = dueToday,
            streakDays = streak(cards, today, zone),
            totalCardsActive = cards.size,
            boxDistribution = boxes,
            averageScoreToday = if (scoreCount > 0) (scoreSum.toDouble() / scoreCount).roundToInt() else 0,
            troublePages = trouble.take(10)
        )
    }

    fun streak(cards: Collection<CardProgress>, today: LocalDate, zone: ZoneId): Int {
        val days = HashSet<LocalDate>()
        for (c in cards) for (log in c.history) days += Time.localDate(log.timestamp, zone)
        if (days.isEmpty()) return 0

        var cursor = today
        if (today !in days) cursor = cursor.minusDays(1) // belum murajaah hari ini -> hitung dari kemarin
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    fun reviewsByDate(cards: Collection<CardProgress>, zone: ZoneId): Map<LocalDate, List<ReviewEntry>> {
        val map = HashMap<LocalDate, MutableList<ReviewEntry>>()
        for (card in cards) {
            for (log in card.history) {
                map.getOrPut(Time.localDate(log.timestamp, zone)) { ArrayList() }
                    .add(ReviewEntry(card.pageNumber, log.compositeScore, log.timestamp))
            }
        }
        return map
    }

    fun consistencyChart(
        range: ChartRange,
        byDate: Map<LocalDate, List<ReviewEntry>>,
        today: LocalDate
    ): List<ChartPoint> {
        val numDays = range.days
        val raw = ArrayList<ChartPoint>(numDays)
        for (i in numDays - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val dayShort = DAY_SHORT[d.dayOfWeek.value % 7]
            val label = when {
                numDays <= 7 -> "$dayShort ${d.dayOfMonth}"
                numDays > 30 -> "${d.dayOfMonth} ${MONTH_SHORT[d.monthValue - 1]}"
                else -> "${d.dayOfMonth}"
            }
            raw += ChartPoint(label, byDate[d]?.size ?: 0, d)
        }

        if (numDays > 30) {
            val bucket = when (numDays) { 90 -> 3; 180 -> 6; else -> 14 }
            return raw.chunked(bucket).map { chunk ->
                ChartPoint(chunk.first().label, chunk.sumOf { it.count }, chunk.first().date)
            }
        }
        return raw
    }

    fun juzStats(progress: Map<Int, CardProgress>): List<JuzStat> =
        (1..30).map { juz ->
            val pages = QuranStructure.pagesInJuz(juz)
            var reviewed = 0
            var sum = 0
            for (p in pages) {
                val prog = progress[p]
                val score = prog?.lastCompositeScore
                if (prog != null && prog.repetitions > 0 && score != null) {
                    reviewed++
                    sum += score
                }
            }
            val complete = reviewed == pages.size
            JuzStat(
                juz = juz,
                totalPages = pages.size,
                reviewedCount = reviewed,
                isComplete = complete,
                averageScore = if (complete) (sum.toDouble() / pages.size).roundToInt() else null,
                progressPercent = (reviewed * 100.0 / pages.size).roundToInt()
            )
        }

    fun unreviewedCount(progress: Map<Int, CardProgress>): Int =
        (1..QuranStructure.TOTAL_PAGES).count { (progress[it]?.repetitions ?: 0) == 0 }
}
