package com.hamurcuabi.usagelimit.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Firebase erişim noktası.
 *
 * Firestore yapısı:
 * - users/{parentUid}                       ebeveynin bildirim jetonları
 * - pairCodes/{kod}                         eşleştirme kodu -> çocuk kaydı
 * - children/{childId}                      çocuk kaydı (parentUid, name, deviceUid, lastSeen, status...)
 * - children/{childId}/config/rules         ebeveynin koyduğu limitler (çocuk yalnızca okur)
 * - children/{childId}/snapshots/today      çocuğun telefonundaki kullanım verisi
 * - children/{childId}/events/{id}          ek süre alma, limit dolması gibi olaylar
 */
object Cloud {
    const val CHILDREN = "children"
    const val PAIR_CODES = "pairCodes"
    const val USERS = "users"

    /** google-services.json eklenmeden derlenen sürümde Firebase başlatılmaz; bulut özellikleri kapalı kalır. */
    fun isAvailable(context: Context): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    fun child(childId: String): DocumentReference = db.collection(CHILDREN).document(childId)
    fun rules(childId: String): DocumentReference = child(childId).collection("config").document("rules")
    fun snapshot(childId: String): DocumentReference = child(childId).collection("snapshots").document("today")
}
