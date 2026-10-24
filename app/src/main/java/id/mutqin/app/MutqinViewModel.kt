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
import java.time.LocalDate
import org.json.JSONObject
import android.app.Activity
import kotlinx.coroutines.CancellationException

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
    var dueNowPages by mutableStateOf<List<Int>>(emptyList()); private set
    var isLoadingText by mutableStateOf(true); private set
    var pageTextError by mutableStateOf(false); private set
    var labels by mutableStateOf<Map<String, ErrorLabel>>(emptyMap()); private set

    var grading by mutableStateOf<GradingUi?>(null); private set
    var themeKey by mutableStateOf(store.themeKey); private set

    val deviceId: String get() = store.deviceId

    // ---- cloud (Firebase)
    val cloudAvailable: Boolean = CloudSync.isAvailable(app)
    var cloudUserEmail by mutableStateOf<String?>(null); private set
    var cloudBusy by mutableStateOf(false); private set
    var cloudStatus by mutableStateOf<Pair<String, Boolean>?>(null); private set
    private val syncMutex = Mutex()

    private var textJob: Job? = null

    /** Tanggal (lokal) saat kartu aktif ditentukan; dipakai agar kartu dipilih ulang saat hari berganti. */
    private var cardDay: LocalDate = LocalDate.now()

    init {
        viewModelScope.launch {
            val (p, s) = withContext(Dispatchers.IO) { store.loadProgress() to store.loadSettings() }
            progress = p
            settings = s
            currentPage = openingPage(p, s)
            cardDay = LocalDate.now()
            recomputeStats()
            isReady = true
            loadPageText()
            ReminderScheduler.schedule(app, s, acknowledgePast = true)
            if (cloudAvailable) {
                cloudUserEmail = CloudSync.currentUser()?.let { it.email ?: it.displayName ?: it.uid }
                if (cloudUserEmail != null) syncInternal(repick = true)
            }
        }
    }

    // ------------------------------------------------------------------ navigasi

    /**
     * Kartu yang tampil saat aplikasi dibuka: selalu ditentukan mesin antrean
     * (utang jatuh tempo -> jatuh tempo hari ini -> halaman baru sesuai antrean -> early review),
     * bukan "halaman yang terakhir dibuka". Dengan begitu halaman yang sudah dinilai tidak muncul lagi,
     * dan setiap hari utang selalu didahulukan.
     */
    private fun openingPage(prog: Map<Int, CardProgress>, s: UserSettings): Int {
        val pick = QueueEngine.nextFlashcard(prog, s.activeScopePages, null, s.scopeQueueOrder)
        return pick?.pageNumber ?: s.activeScopePages.minOrNull() ?: 1
    }

    fun selectPage(page: Int) {
        if (page !in 1..QuranStructure.TOTAL_PAGES) return
        currentPage = page
        cardDay = LocalDate.now()
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

    private fun loadPageText() {
        textJob?.cancel()
        val page = currentPage
        isLoadingText = true
        pageTextError = false
        pageText = null
        labels = emptyMap()
        textJob = viewModelScope.launch {
            val text = QuranRepository.getPage(app, page)
            if (page == currentPage) {
                pageText = text
                pageTextError = text == null
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
        store.markDirty(listOf(page))
        CloudSync.currentUser()?.let { CloudSync.pushPageAsync(it.uid, updated) }
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
        ReminderScheduler.schedule(app, updated, acknowledgePast = true)
        markSettingsChanged()
    }

    /** Mencatat waktu perubahan pengaturan dan mengirimnya ke cloud (jika sudah masuk). */
    private fun markSettingsChanged() {
        val at = System.currentTimeMillis()
        store.settingsUpdatedAt = at
        CloudSync.currentUser()?.let {
            CloudSync.pushSettingsAsync(it.uid, Codec.settingsToJson(settings).toString(), at)
        }
    }

    fun updateDailyTarget(pages: Int) = updateSettings(settings.copy(dailyTargetPages = pages.coerceIn(1, 100)))

    /**
     * Menyimpan target hafalan baru. Antrean dan kartu aktif diatur oleh [ScopeTransition]:
     * jika rentang baru dimulai setelah kartu aktif, kartu langsung berpindah ke halaman paling awal rentang baru.
     */
    fun saveScope(mode: String, pages: Set<Int>) {
        if (pages.isEmpty()) return
        val result = ScopeTransition.apply(
            oldScope = settings.activeScopePages,
            oldQueue = settings.scopeQueueOrder,
            newScopePages = pages,
            currentPage = currentPage,
            progress = progress
        )
        val effective = pages.sorted()
        val juz = (1..30).filter { j -> QuranStructure.pagesInJuz(j).any { it in pages } }
        val surahs = QuranData.surahs
            .filter { s -> (s.startPage..s.endPage).any { it in pages } }
            .map { it.number }
        updateSettings(
            settings.copy(
                scopeMode = mode,
                activeScopePages = effective,
                scopeQueueOrder = result.queueOrder,
                activeScopeJuz = juz,
                activeScopeSurahs = surahs
            )
        )
        if (result.jumped) selectPage(result.page)
    }

    fun setTheme(key: String) {
        themeKey = key
        store.themeKey = key
    }

    // ------------------------------------------------------------------ cadangan JSON

    suspend fun exportJson(): String = withContext(Dispatchers.Default) {
        Codec.exportSync(store.deviceId, progress, settings)
    }

    /** Mengembalikan jumlah halaman yang diperbarui, atau pesan galat. */
    suspend fun importJson(json: String): Result<Int> {
        val outcome = withContext(Dispatchers.Default) { Codec.importSync(json, progress, settings) }
        if (!outcome.success) return Result.failure(IllegalArgumentException(outcome.error ?: "Format data tidak sesuai"))

        val changedPages = outcome.progress.filter { (page, card) -> progress[page] != card }.keys
        progress = outcome.progress
        outcome.settings?.let {
            settings = it
            markSettingsChanged()
        }
        recomputeStats()
        persistProgress()
        persistSettings()
        ReminderScheduler.schedule(app, settings, acknowledgePast = true)
        store.markDirty(changedPages)
        if (cloudUserEmail != null) viewModelScope.launch { syncInternal(repick = false) }
        return Result.success(outcome.count)
    }

    // ------------------------------------------------------------------ cloud (Google + Firestore)

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            cloudBusy = true
            cloudStatus = null
            val result = CloudSync.signIn(activity)
            cloudBusy = false
            result.fold(
                onSuccess = { user ->
                    cloudUserEmail = user.email ?: user.displayName ?: user.uid
                    syncInternal(repick = true)
                },
                onFailure = { cloudStatus = CloudSync.friendlyError(it) to false }
            )
        }
    }

    fun signOutCloud() {
        viewModelScope.launch {
            cloudBusy = true
            CloudSync.signOut(app)
            cloudUserEmail = null
            cloudBusy = false
            cloudStatus = "Anda sudah keluar. Data di perangkat ini tetap ada." to true
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncInternal(repick = false) }
    }

    /**
     * Sinkronisasi dua arah per halaman: tarik perubahan cloud, gabungkan (murajaah terbaru menang,
     * riwayat digabung), lalu kirim halaman lokal yang lebih baru. Penggabungan dilakukan tanpa jeda suspend
     * agar tidak menimpa murajaah yang baru disimpan saat menunggu jaringan.
     */
    private suspend fun syncInternal(repick: Boolean) {
        val user = CloudSync.currentUser() ?: return
        if (!syncMutex.tryLock()) return
        cloudBusy = true
        try {
            val uid = user.uid
            val startedAt = System.currentTimeMillis()
            val since = store.lastPull(uid)
            val remote = CloudSync.pull(uid, if (since == 0L) 0L else since - PULL_MARGIN_MS)

            // ---- gabungkan halaman (tanpa suspend dari sini sampai progress diperbarui)
            val merged = HashMap(progress)
            val toPush = LinkedHashSet<Int>()
            var received = 0
            for ((page, r) in remote.pages) {
                val local = merged[page]
                val m = if (local == null) r else CardMerge.merge(local, r)
                if (m != local) {
                    merged[page] = m
                    received++
                }
                if (m != r) toPush += page
            }
            val candidates = HashSet(store.dirtyPages)
            if (since == 0L) candidates += merged.keys
            for (p in candidates) {
                if (p !in remote.pages && merged.containsKey(p)) toPush += p
            }
            if (received > 0) {
                progress = merged
                recomputeStats()
                persistProgress()
            }

            // ---- pengaturan: yang terbaru menang
            var settingsAdopted = false
            var pushSettings = false
            val localSettingsAt = store.settingsUpdatedAt
            val remoteSettingsData = remote.settingsData
            if (remoteSettingsData != null && remote.settingsUpdatedAt > localSettingsAt) {
                val parsed = runCatching { Codec.settingsFromJson(JSONObject(remoteSettingsData), settings) }.getOrNull()
                if (parsed != null) {
                    settings = parsed
                    store.settingsUpdatedAt = remote.settingsUpdatedAt
                    persistSettings()
                    ReminderScheduler.schedule(app, parsed, acknowledgePast = true)
                    recomputeStats()
                    settingsAdopted = true
                }
            } else if (localSettingsAt > remote.settingsUpdatedAt) {
                pushSettings = true
            }

            // ---- pilih ulang kartu jika data cloud mengubah antrean (saat dibuka / baru masuk)
            if (repick && (received > 0 || settingsAdopted) &&
                tab == MainTab.FLASHCARD && cardSide == CardSide.FRONT && grading == null
            ) {
                val page = openingPage(progress, settings)
                if (page != currentPage) selectPage(page)
            }

            // ---- kirim ke cloud
            val snapshot = progress
            val confirmed = if (toPush.isEmpty()) true
            else CloudSync.pushPages(uid, toPush.mapNotNull { snapshot[it] }, System.currentTimeMillis())
            if (pushSettings) {
                CloudSync.pushSettingsAsync(uid, Codec.settingsToJson(settings).toString(), localSettingsAt)
            }
            if (confirmed) store.dirtyPages = store.dirtyPages - candidates
            store.setLastPull(uid, startedAt)

            cloudStatus = buildString {
                append("Sinkronisasi selesai: $received halaman diterima, ${toPush.size} dikirim.")
                if (settingsAdopted) append(" Pengaturan dari cloud diterapkan.")
                if (!confirmed) append(" Sebagian data masih antre dan terkirim saat internet tersedia.")
            } to true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cloudStatus = CloudSync.friendlyError(e) to false
        } finally {
            cloudBusy = false
            syncMutex.unlock()
        }
    }

    // ------------------------------------------------------------------ util

    fun refresh() {
        if (!isReady) return
        recomputeStats()
        // Hari berganti saat aplikasi masih terbuka di kartu depan: utang hari baru harus didahulukan.
        if (LocalDate.now() != cardDay && tab == MainTab.FLASHCARD && cardSide == CardSide.FRONT && grading == null) {
            val page = openingPage(progress, settings)
            if (page != currentPage) selectPage(page) else cardDay = LocalDate.now()
        }
    }

    private fun recomputeStats() {
        // Jumlah & daftar jatuh tempo dihitung dengan aturan yang sama dengan antrean kartu (rentang aktif, berbasis hari)
        val due = QueueEngine.dueCards(progress, settings.activeScopePages)
        dueNowPages = due.map { it.page }
        stats = StatsEngine.dailyStats(progress.values.sortedBy { it.pageNumber }).copy(dueTodayCount = due.size)
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

private const val PULL_MARGIN_MS = 10 * 60 * 1000L
