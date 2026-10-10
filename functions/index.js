/**
 * Çocuk ek süre aldığında ebeveynin telefonlarına bildirim gönderir.
 *
 * Tetikleyici: children/{childId}/events/{eventId} belgesi oluşturulunca.
 * REGION, Firestore veritabanının konumuyla aynı olmalı.
 */
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const REGION = "europe-west3";
// Uygulamadaki PushService.CHANNEL ile aynı olmalı.
const CHANNEL = "child_events";

function formatMs(ms) {
  const total = Math.floor((ms || 0) / 60000);
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h > 0 && m > 0) return `${h} sa ${m} dk`;
  if (h > 0) return `${h} sa`;
  return `${m} dk`;
}

exports.notifyParent = onDocumentCreated(
  { document: "children/{childId}/events/{eventId}", region: REGION },
  async (event) => {
    const data = event.data ? event.data.data() : null;
    // Limitin dolması yalnızca kayda geçer; bildirim ek süre alındığında gider.
    if (!data || data.type !== "extension") return;

    const db = getFirestore();
    const child = await db.doc(`children/${event.params.childId}`).get();
    if (!child.exists) return;

    const parentUid = child.get("parentUid");
    const userRef = db.doc(`users/${parentUid}`);
    const tokens = (await userRef.get()).get("fcmTokens") || [];
    if (tokens.length === 0) return;

    const name = child.get("name") || "Çocuğun";
    const subject =
      data.scope === "group" && data.groupTitle
        ? `${data.label} (${data.groupTitle} grubu)`
        : data.label;

    const response = await getMessaging().sendEachForMulticast({
      tokens,
      notification: {
        title: `${name} ${data.extensionMin} dk ek süre aldı`,
        body: `${subject} · bugün ${formatMs(data.usedMs)}, limit ${data.limitMin} dk`,
      },
      android: {
        priority: "high",
        notification: { channelId: CHANNEL },
      },
    });

    // Artık geçersiz olan jetonları temizle.
    const dead = [];
    response.responses.forEach((r, i) => {
      const code = r.error && r.error.code;
      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token"
      ) {
        dead.push(tokens[i]);
      }
    });
    if (dead.length > 0) {
      await userRef.update({ fcmTokens: FieldValue.arrayRemove(...dead) });
    }
  }
);
