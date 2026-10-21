package id.mutqin.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CardSide { FRONT, BACK }
enum class MainTab { FLASHCARD, EXPLORER, STATS, GUIDE }

data class LabelCounts(val mistake: Int = 0, val hesitation: Int = 0, val skipped: Int = 0, val tajweed: Int = 0) {
    val total: Int get() = mistake + hesitation + skipped + tajweed
}

fun countLabels(ayahs: List<QuranAyah>, labels: Map<String, ErrorLabel>): LabelCounts {
    var m = 0; var h = 0; var s = 0; var t = 0
    for (a in ayahs) for (w in a.words) {
        when (labels[w.id]) {
            ErrorLabel.MISTAKE -> m++
            ErrorLabel.HESITATION -> h++
            ErrorLabel.SKIPPED -> s++
            ErrorLabel.TAJWEED -> t++
            else -> Unit
        }
    }
    return LabelCounts(m, h, s, t)
}

data class GradingUi(
    val pageNumber: Int,
    val result: EvaluationResult,
    val userRating: Int,
    val counts: LabelCounts
)

class MutqinViewModel(private val app: Application) : AndroidViewModel(app) {

    private val store = MutqinStore(app)
    private val persistMutex = Mutex()

    var isReady by mutableStateOf(false); private set
    var progress by mutableStateOf<Map<Int, CardProgress>>(emptyMap()); private set
    var settings by mutableStateOf(UserSettings()); private set
    var stats by mutableStateOf(DailyStats()); private set

    var tab by mutableStateOf(MainTab.FLASHCARD)
    var currentPage by mutableIntStateOf(1); private set
    var cardSide by mutableStateOf(CardSide.FRONT); private set
    var elapsedSeconds by mutableIntStateOf(0); private set

    var pageText by mutableStateOf<PageText?>(null); private set
    var isLoadingText by mutableStateOf(true); private set
    var labels by mutableStateOf<Map<String, ErrorLabel>>(emptyMap()); private set

    var grading by mutableStateOf<GradingUi?>(null); private set
    var isDarkMode by mutableStateOf(store.darkMode); private set

    val deviceId: String get() = store.deviceId

    private var textJob: Job? = null

    init {
        viewModelScope.launch {
            val (p, s) = withContext(Dispatchers.IO) { store.loadProgress() to store.loadSettings() }
            progress = p
            settings = s
            currentPage = initialPage(s)
            recomputeStats()
            isReady = true
            loadPageText()
            ReminderScheduler.schedule(app, s)
        }
    }

    // ------------------------------------------------------------------ navigasi

    private fun initialPage(s: UserSettings): Int {
        store.lastPage?.takeIf { it in 1..QuranStructure.TOTAL_PAGES }?.let { return it }
        s.scopeQueueOrder?.firstOrNull()?.let { return it }
        return s.activeScopePages.minOrNull() ?: 1
    }

    fun selectPage(page: Int) {
        if (page !in 1..QuranStructure.TOTAL_PAGES) return
        currentPage = page
        cardSide = CardSide.FRONT
        elapsedSeconds = 0
        tab = MainTab.FLASHCARD
        store.lastPage = page
        loadPageText()
    }

    fun flipToBack(durationSeconds: Int) {
        elapsedSeconds = durationSeconds
        cardSide = CardSide.BACK
    }

    fun backToFront() {
        cardSide = CardSide.FRONT
    }

    /** Dipanggil tombol "Coba lagi" saat teks halaman gagal dimuat (offline). */
    fun reloadPageText() = loadPageText()

    private fun loadPageText() {
        textJob?.cancel()
        val page = currentPage
        isLoadingText = true
        pageText = null
        labels = emptyMap()
        textJob = viewModelScope.launch {
            val text = QuranRepository.getPage(app, page)
            if (page == currentPage) {
                pageText = text
                isLoadingText = false
            }
        }
    }

    // ------------------------------------------------------------------ pelabelan kata / ayat

    fun toggleWordLabel(wordId: String, tool: ErrorLabel) {
        val current = labels[wordId] ?: ErrorLabel.NONE
        val next = if (current == tool) ErrorLabel.NONE else tool
        labels = if (next == ErrorLabel.NONE) labels - wordId else labels + (wordId to next)
    }

    fun toggleAyahLabel(ayah: QuranAyah, tool: ErrorLabel) {
        val allHave = ayah.words.all { (labels[it.id] ?: ErrorLabel.NONE) == tool }
        setAyahLabel(ayah, if (allHave) ErrorLabel.NONE else tool)
    }

    fun setAyahLabel(ayah: QuranAyah, label: ErrorLabel) {
        val m = labels.toMutableMap()
        for (w in ayah.words) if (label == ErrorLabel.NONE) m.remove(w.id) else m[w.id] = label
        labels = m
    }

    fun clearAllLabels() {
        labels = emptyMap()
    }

    // ------------------------------------------------------------------ penilaian & SRS

    fun finishReview(userRating: Int) {
        val text = pageText ?: return
        val page = currentPage
        val duration = elapsedSeconds

        val counts = countLabels(text.ayahs, labels)
        val labeled = ArrayList<LabeledWord>()
        for (a in text.ayahs) for (w in a.words) {
            val l = labels[w.id] ?: ErrorLabel.NONE
            if (l != ErrorLabel.NONE) labeled += LabeledWord(a.ayahNumber, w.text, l)
        }

        val current = progress[page] ?: CardProgress(pageNumber = page, nextReviewDate = Time.nowIso())
        val result = SrsEngine.evaluate(
            EvaluationInput(userRating, counts.mistake, counts.hesitation, counts.skipped, counts.tajweed, current.box)
        )

        val nowIso = Time.nowIso()
        val nextIso = Time.plusDaysIso(result.intervalDays)
        val log = ReviewLog(
            id = "rev_${System.currentTimeMillis()}_p$page",
            timestamp = nowIso,
            userRating = userRating,
            compositeScore = result.compositeScore,
            aiGrade = result.aiGrade,
            mistakeCount = counts.mistake,
            hesitationCount = counts.hesitation,
            skippedCount = counts.skipped,
            tajweedCount = counts.tajweed,
            durationSeconds = duration,
            previousBox = current.box,
            newBox = result.recommendedBox,
            nextReviewDate = nextIso,
            scientificFeedback = result.scientificDiagnosis,
            labeledWordsSummary = labeled
        )
        val updated = current.copy(
            box = result.recommendedBox,
            intervalDays = result.intervalDays,
            repetitions = current.repetitions + 1,
            lapses = if (counts.mistake > 0 || counts.skipped > 0) current.lapses + 1 else current.lapses,
            lastReviewedDate = nowIso,
            nextReviewDate = nextIso,
            lastUserRating = userRating,
            lastCompositeScore = result.compositeScore,
            history = (listOf(log) + current.history).take(30)
        )

        progress = progress + (page to updated)
        recomputeStats()
        grading = GradingUi(page, result, userRating, counts)
        persistProgress()
    }

    fun dismissGrading() {
        grading = null
    }

    /** "Simpan & Lanjut": Bayar utang dulu -> jatuh tempo hari ini -> halaman baru sesuai antrean. */
    fun nextAfterReview() {
        grading = null
        val pick = nextPick(excludeCurrent = true)
        if (pick != null) {
            selectPage(pick.pageNumber)
        } else {
            val scope = settings.activeScopePages
            if (scope.isNotEmpty()) {
                val idx = scope.indexOf(currentPage)
                selectPage(scope[(idx + 1) % scope.size])
            }
        }
    }

    fun nextPick(excludeCurrent: Boolean = true): NextCardSelection? =
        QueueEngine.nextFlashcard(
            progress, settings.activeScopePages,
            if (excludeCurrent) currentPage else null,
            settings.scopeQueueOrder
        )

    /** Halaman yang akan dipakai di isi notifikasi. */
    val targetNextPage: Int get() = nextPick()?.pageNumber ?: currentPage

    // ------------------------------------------------------------------ pengaturan

    fun updateSettings(updated: UserSettings) {
        settings = updated
        recomputeStats()
        persistSettings()
        ReminderScheduler.schedule(app, updated)
    }

    fun updateDailyTarget(pages: Int) = updateSettings(settings.copy(dailyTargetPages = pages.coerceIn(1, 100)))

    fun saveScope(mode: String, pages: Set<Int>) {
        val effective = pages.sorted().ifEmpty { listOf(1) }
        val order = QueueEngine.syncScopeQueueOrder(settings.scopeQueueOrder, effective)
        val juz = (1..30).filter { j -> QuranStructure.pagesInJuz(j).any { it in pages } }
        updateSettings(
            settings.copy(scopeMode = mode, activeScopePages = effective, scopeQueueOrder = order, activeScopeJuz = juz)
        )
    }

    fun toggleDarkMode() {
        isDarkMode = !isDarkMode
        store.darkMode = isDarkMode
    }

    // ------------------------------------------------------------------ cadangan JSON

    suspend fun exportJson(): String = withContext(Dispatchers.Default) {
        Codec.exportSync(store.deviceId, progress, settings)
    }

    /** Mengembalikan jumlah halaman yang diperbarui, atau pesan galat. */
    suspend fun importJson(json: String): Result<Int> {
        val outcome = withContext(Dispatchers.Default) { Codec.importSync(json, progress, settings) }
        if (!outcome.success) return Result.failure(IllegalArgumentException(outcome.error ?: "Format data tidak sesuai"))

        progress = outcome.progress
        outcome.settings?.let { settings = it }
        recomputeStats()
        persistProgress()
        persistSettings()
        ReminderScheduler.schedule(app, settings)
        return Result.success(outcome.count)
    }

    // ------------------------------------------------------------------ util

    fun refresh() {
        if (isReady) recomputeStats()
    }

    private fun recomputeStats() {
        stats = StatsEngine.dailyStats(progress.values.sortedBy { it.pageNumber })
    }

    private fun persistProgress() {
        val snapshot = progress
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                persistMutex.withLock { runCatching { store.saveProgress(snapshot) } }
            }
        }
    }

    private fun persistSettings() {
        val snapshot = settings
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                persistMutex.withLock { runCatching { store.saveSettings(snapshot) } }
            }
        }
    }
}
