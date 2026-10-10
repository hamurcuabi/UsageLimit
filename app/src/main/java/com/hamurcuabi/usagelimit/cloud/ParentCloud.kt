package com.hamurcuabi.usagelimit.cloud

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.hamurcuabi.usagelimit.data.RawApp
import com.hamurcuabi.usagelimit.data.RuleSet
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

data class Child(
    val id: String,
    val name: String,
    val pairCode: String,
    val paired: Boolean,
    val deviceModel: String,
    val lastSeen: Long,
    val totalTodayMs: Long,
    /** İzin durumu: usage, overlay, notify, battery. Henüz veri gelmediyse boş. */
    val status: Map<String, Boolean>,
)

data class ChildSnapshot(
    val day: String = "",
    val updatedAt: Long = 0L,
    val weekLabels: List<String> = emptyList(),
    val apps: List<RawApp> = emptyList(),
)

data class ChildEvent(
    val id: String,
    val type: String,
    val pkg: String,
    val label: String,
    val scope: String,
    val groupTitle: String,
    val usedMs: Long,
    val limitMin: Int,
    val extensionMin: Int,
    val at: Long,
)

/** Ebeveyn tarafının bulut işlemleri. */
object ParentCloud {
    val uid: String? get() = Cloud.auth.currentUser?.takeIf { !it.isAnonymous }?.uid
    val email: String? get() = Cloud.auth.currentUser?.email

    suspend fun signIn(email: String, password: String) {
        Cloud.auth.signInWithEmailAndPassword(email.trim(), password).await()
        registerPushToken()
    }

    suspend fun signUp(email: String, password: String) {
        Cloud.auth.createUserWithEmailAndPassword(email.trim(), password).await()
        registerPushToken()
    }

    fun signOut() {
        val current = uid
        if (current != null) {
            // Bu telefona artık bildirim gelmesin.
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                Cloud.db.collection(Cloud.USERS).document(current)
                    .update("fcmTokens", FieldValue.arrayRemove(token))
                    .addOnCompleteListener { Cloud.auth.signOut() }
            }.addOnFailureListener { Cloud.auth.signOut() }
        } else {
            Cloud.auth.signOut()
        }
    }

    /** Olay bildirimlerinin bu telefona gelmesi için jetonu ebeveynin kaydına ekler. */
    suspend fun registerPushToken() {
        val current = uid ?: return
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            saveToken(current, token)
        } catch (_: Exception) {
        }
    }

    fun saveToken(parentUid: String, token: String) {
        Cloud.db.collection(Cloud.USERS).document(parentUid)
            .set(mapOf("fcmTokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
    }

    fun children(): Flow<List<Child>> = callbackFlow {
        val current = uid
        if (current == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val registration = Cloud.db.collection(Cloud.CHILDREN)
            .whereEqualTo("parentUid", current)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val list = snapshot.documents.map { doc ->
                    val status = HashMap<String, Boolean>()
                    (doc.get("status") as? Map<*, *>)?.forEach { (k, v) ->
                        if (k is String && v is Boolean) status[k] = v
                    }
                    Child(
                        id = doc.id,
                        name = doc.getString("name") ?: "",
                        pairCode = doc.getString("pairCode") ?: "",
                        paired = doc.getString("deviceUid") != null,
                        deviceModel = doc.getString("deviceModel") ?: "",
                        lastSeen = doc.getLong("lastSeen") ?: 0L,
                        totalTodayMs = doc.getLong("totalTodayMs") ?: 0L,
                        status = status,
                    )
                }.sortedBy { it.name.lowercase() }
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    /** Yeni çocuk kaydı açar ve eşleştirme kodunu döndürür. */
    suspend fun addChild(name: String): String {
        val current = uid ?: error("Önce giriş yap.")
        val code = newCode()
        val ref = Cloud.db.collection(Cloud.CHILDREN).document()
        ref.set(
            mapOf(
                "parentUid" to current,
                "name" to name.trim(),
                "pairCode" to code,
                "deviceUid" to null,
                "createdAt" to System.currentTimeMillis(),
                "lastSeen" to 0L,
            )
        ).await()
        Cloud.db.collection(Cloud.PAIR_CODES).document(code)
            .set(mapOf("childId" to ref.id, "parentUid" to current, "createdAt" to System.currentTimeMillis()))
            .await()
        Cloud.rules(ref.id).set(RuleSet().toMap()).await()
        return code
    }

    /** Çocuğun telefonu değiştiyse ya da uygulama silindiyse yeni kodla yeniden eşleştirmek için. */
    suspend fun resetPairing(child: Child): String {
        val current = uid ?: error("Önce giriş yap.")
        val code = newCode()
        if (child.pairCode.isNotEmpty()) {
            try {
                Cloud.db.collection(Cloud.PAIR_CODES).document(child.pairCode).delete().await()
            } catch (_: Exception) {
            }
        }
        Cloud.child(child.id).update(
            mapOf(
                "pairCode" to code,
                "deviceUid" to null,
                "claimCode" to FieldValue.delete(),
                "status" to FieldValue.delete(),
            )
        ).await()
        Cloud.db.collection(Cloud.PAIR_CODES).document(code)
            .set(mapOf("childId" to child.id, "parentUid" to current, "createdAt" to System.currentTimeMillis()))
            .await()
        return code
    }

    suspend fun deleteChild(child: Child) {
        try {
            Cloud.rules(child.id).delete().await()
            Cloud.snapshot(child.id).delete().await()
            if (child.pairCode.isNotEmpty()) {
                Cloud.db.collection(Cloud.PAIR_CODES).document(child.pairCode).delete().await()
            }
        } catch (_: Exception) {
        }
        Cloud.child(child.id).delete().await()
    }

    private suspend fun newCode(): String {
        repeat(8) {
            val code = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
            val existing = Cloud.db.collection(Cloud.PAIR_CODES).document(code).get().await()
            if (!existing.exists()) return code
        }
        error("Kod üretilemedi, tekrar dene.")
    }

    fun snapshot(childId: String): Flow<ChildSnapshot> = callbackFlow {
        val registration = Cloud.snapshot(childId).addSnapshotListener { doc, _ ->
            if (doc == null) return@addSnapshotListener
            val apps = (doc.get("apps") as? List<*>)?.mapNotNull { item -> (item as? Map<*, *>)?.let { RawApp.fromMap(it) } }
            trySend(
                ChildSnapshot(
                    day = doc.getString("day") ?: "",
                    updatedAt = doc.getLong("updatedAt") ?: 0L,
                    weekLabels = (doc.get("weekLabels") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    apps = apps ?: emptyList(),
                )
            )
        }
        awaitClose { registration.remove() }
    }

    fun rules(childId: String): Flow<RuleSet> = callbackFlow {
        val registration = Cloud.rules(childId).addSnapshotListener { doc, _ ->
            if (doc == null) return@addSnapshotListener
            trySend(doc.data?.let { RuleSet.fromMap(it) } ?: RuleSet())
        }
        awaitClose { registration.remove() }
    }

    fun saveRules(childId: String, rules: RuleSet) {
        Cloud.rules(childId).set(rules.toMap())
    }

    fun events(childId: String, limit: Long = 100): Flow<List<ChildEvent>> = callbackFlow {
        val registration = Cloud.child(childId).collection("events")
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                trySend(
                    snapshot.documents.map { doc ->
                        ChildEvent(
                            id = doc.id,
                            type = doc.getString("type") ?: "",
                            pkg = doc.getString("pkg") ?: "",
                            label = doc.getString("label") ?: "",
                            scope = doc.getString("scope") ?: "",
                            groupTitle = doc.getString("groupTitle") ?: "",
                            usedMs = doc.getLong("usedMs") ?: 0L,
                            limitMin = (doc.getLong("limitMin") ?: 0L).toInt(),
                            extensionMin = (doc.getLong("extensionMin") ?: 0L).toInt(),
                            at = doc.getLong("at") ?: 0L,
                        )
                    }
                )
            }
        awaitClose { registration.remove() }
    }
}
