package id.mutqin.app

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

data class RemotePull(
    val pages: Map<Int, CardProgress>,
    val settingsData: String?,
    val settingsUpdatedAt: Long
)

/**
 * Login Google (Credential Manager) + penyimpanan Firestore.
 *
 * Struktur data (cocok dengan aturan keamanan users/{uid}/sync_data/{docId}):
 *   users/{uid}/sync_data/page_{n}   -> { page, updatedAt, data = JSON CardProgress }
 *   users/{uid}/sync_data/settings   -> { updatedAt, data = JSON UserSettings }
 */
object CloudSync {
    /** Web client ID (OAuth client_type 3) dari google-services.json proyek Firebase. */
    const val WEB_CLIENT_ID = "824725394854-bu9l73p6enp2men9fm0l34qnb58escol.apps.googleusercontent.com"

    private const val TIMEOUT_MS = 20_000L
    private const val PAGE_PREFIX = "page_"
    private const val SETTINGS_DOC = "settings"

    /** true jika Firebase terinisialisasi (artinya google-services.json ikut dibangun). */
    fun isAvailable(context: Context): Boolean =
        try {
            FirebaseApp.getApps(context.applicationContext).isNotEmpty()
        } catch (e: Exception) {
            false
        }

    fun currentUser(): FirebaseUser? =
        try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            null
        }

    private fun col(uid: String): CollectionReference =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("sync_data")

    // ------------------------------------------------------------------ login

    suspend fun signIn(activity: Activity): Result<FirebaseUser> =
        try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw IllegalStateException("Jenis kredensial Google tidak dikenali.")
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val authResult = FirebaseAuth.getInstance()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await()
            val user = authResult.user ?: throw IllegalStateException("Login berhasil tetapi akun tidak terbaca.")
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun signOut(context: Context) {
        try {
            FirebaseAuth.getInstance().signOut()
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keluar dari Firebase sudah terjadi; pembersihan status kredensial bersifat opsional
        }
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is GetCredentialCancellationException -> "Login dibatalkan."
        is NoCredentialException ->
            "Tidak ada akun Google di perangkat ini. Tambahkan akun Google di Pengaturan HP, lalu coba lagi."
        is GetCredentialException -> "Login Google gagal: ${e.message ?: "kesalahan tidak diketahui"}"
        is FirebaseNetworkException, is IOException -> "Tidak ada koneksi internet atau server terlalu lambat. Coba lagi nanti."
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Akses ditolak oleh aturan keamanan Firestore. Periksa tab Rules di Firebase Console."
            FirebaseFirestoreException.Code.UNAVAILABLE ->
                "Server tidak terjangkau. Periksa koneksi internet, lalu coba lagi."
            else -> "Kesalahan Firestore: ${e.message}"
        }
        else -> e.message ?: "Terjadi kesalahan."
    }

    // ------------------------------------------------------------------ unduh

    /** Mengambil halaman yang berubah sejak [sinceMillis] dan pengaturan dari server (bukan dari cache). */
    suspend fun pull(uid: String, sinceMillis: Long): RemotePull {
        val c = col(uid)
        val pagesSnap = withTimeoutOrNull(TIMEOUT_MS) {
            c.whereGreaterThan("updatedAt", sinceMillis).get(Source.SERVER).await()
        } ?: throw IOException("Koneksi ke server terlalu lambat atau tidak tersedia.")

        val pages = HashMap<Int, CardProgress>()
        for (d in pagesSnap.documents) {
            if (!d.id.startsWith(PAGE_PREFIX)) continue
            val page = d.id.removePrefix(PAGE_PREFIX).toIntOrNull() ?: continue
            if (page !in 1..QuranStructure.TOTAL_PAGES) continue
            val data = d.getString("data") ?: continue
            val card: CardProgress = runCatching { Codec.cardFromJson(JSONObject(data), page) }.getOrNull() ?: continue
            pages[page] = card
        }

        val settingsSnap = withTimeoutOrNull(TIMEOUT_MS) {
            c.document(SETTINGS_DOC).get(Source.SERVER).await()
        } ?: throw IOException("Koneksi ke server terlalu lambat atau tidak tersedia.")
        val settingsData = if (settingsSnap.exists()) settingsSnap.getString("data") else null
        val settingsAt = if (settingsSnap.exists()) (settingsSnap.getLong("updatedAt") ?: 0L) else 0L
        return RemotePull(pages, settingsData, settingsAt)
    }

    // ------------------------------------------------------------------ kirim

    private fun pageFields(c: CardProgress, now: Long): Map<String, Any> = hashMapOf(
        "page" to c.pageNumber,
        "updatedAt" to now,
        "data" to Codec.cardToJson(c).toString()
    )

    /** Mengirim banyak halaman; true jika server mengonfirmasi semuanya (false = masih antre/luring). */
    suspend fun pushPages(uid: String, cards: List<CardProgress>, now: Long): Boolean {
        for (chunk in cards.chunked(400)) {
            val batch = FirebaseFirestore.getInstance().batch()
            for (card in chunk) batch.set(col(uid).document("$PAGE_PREFIX${card.pageNumber}"), pageFields(card, now))
            val ok = withTimeoutOrNull(TIMEOUT_MS) {
                batch.commit().await()
                true
            } ?: false
            if (!ok) return false
        }
        return true
    }

    /** Kirim satu halaman tanpa menunggu (Firestore mengantre sendiri saat luring). */
    fun pushPageAsync(uid: String, card: CardProgress) {
        try {
            col(uid).document("$PAGE_PREFIX${card.pageNumber}").set(pageFields(card, System.currentTimeMillis()))
        } catch (e: Exception) {
            // gagal diam-diam; halaman tetap bertanda "dirty" dan dikirim pada sinkronisasi berikutnya
        }
    }

    fun pushSettingsAsync(uid: String, settingsJson: String, updatedAt: Long) {
        try {
            col(uid).document(SETTINGS_DOC).set(hashMapOf("updatedAt" to updatedAt, "data" to settingsJson))
        } catch (e: Exception) {
            // diabaikan; akan dicoba lagi saat sinkronisasi berikutnya
        }
    }
}
