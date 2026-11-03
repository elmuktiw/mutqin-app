package id.mutqin.app

/** Aturan penggabungan data antar-perangkat (murni Kotlin, diuji unit test). */
object CardMerge {

    private fun reviewedMillis(c: CardProgress): Long =
        c.lastReviewedDate?.let { runCatching { Time.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun logMillis(l: ReviewLog): Long =
        runCatching { Time.parse(l.timestamp).toEpochMilli() }.getOrDefault(0L)

    /**
     * Pemenang = murajaah paling baru (`lastReviewedDate`); jika sama, repetisi terbanyak.
     * Riwayat kedua sisi digabung (tanpa duplikat, terbaru dulu, maksimal 30) agar tidak ada log yang hilang.
     */
    fun merge(a: CardProgress, b: CardProgress): CardProgress {
        val ta = reviewedMillis(a)
        val tb = reviewedMillis(b)
        val winner = when {
            ta != tb -> if (ta > tb) a else b
            a.repetitions != b.repetitions -> if (a.repetitions > b.repetitions) a else b
            else -> a
        }
        val history = (a.history + b.history)
            .distinctBy { it.id }
            .sortedByDescending { logMillis(it) }
            .take(30)
        return winner.copy(history = history)
    }
}

/** Jenis pertanyaan saat sebuah akun Google pertama kali dipakai di perangkat ini. */
enum class AccountPrompt { NONE, NEW_ACCOUNT_WITH_DEVICE_DATA, BOTH_HAVE_DATA }

/** Kebijakan saat sebuah akun Google pertama kali dipakai di perangkat ini. */
object AccountPolicy {
    /**
     *  - perangkat tanpa progres            -> tidak perlu bertanya (data akun langsung dipakai bila ada);
     *  - akun baru + perangkat punya progres -> tanya: mulai dari awal, atau pakai progres perangkat;
     *  - akun punya data + perangkat punya progres -> tanya: ganti data perangkat / ganti data akun / gabungkan.
     *  - bukan sinkronisasi pertama (akun yang sama masuk lagi) -> digabung diam-diam.
     */
    fun prompt(firstSync: Boolean, accountHasData: Boolean, localReviewedPages: Int): AccountPrompt = when {
        !firstSync || localReviewedPages <= 0 -> AccountPrompt.NONE
        accountHasData -> AccountPrompt.BOTH_HAVE_DATA
        else -> AccountPrompt.NEW_ACCOUNT_WITH_DEVICE_DATA
    }
}
