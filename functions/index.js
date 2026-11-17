/* eslint-disable max-len */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();

function pickPayload(data) {
  // ✅ Soporta ambos formatos:
  // - data = { targetUid, reason, idToken }
  // - data = { data: { targetUid, reason, idToken } }  (doble wrapper)
  if (data && typeof data === "object" && data.data && typeof data.data === "object") {
    return data.data;
  }
  return data || {};
}

function getBearerToken(context) {
  const h = context?.rawRequest?.headers?.authorization || "";
  if (!h) return "";
  const m = String(h).match(/^Bearer\s+(.+)$/i);
  return m ? m[1].trim() : "";
}

exports.pingAuth = functions.https.onCall(async (data, context) => {
  const payload = pickPayload(data);
  const bearer = getBearerToken(context);

  console.log("[pingAuth] context.auth?", !!context.auth, "uid=", context.auth?.uid || null);
  console.log("[pingAuth] data.keys=", data ? Object.keys(data) : null);
  console.log("[pingAuth] payload.keys=", payload ? Object.keys(payload) : null);
  console.log("[pingAuth] bearerPresent=", bearer.length > 0);

  return {
    hasAuth: !!context.auth,
    uid: context.auth ? context.auth.uid : null,
    dataKeys: data ? Object.keys(data) : null,
    payloadKeys: payload ? Object.keys(payload) : null,
    bearerPresent: bearer.length > 0,
  };
});

/**
 * Binds one non-exportable Android Keystore public key to an approved account.
 * The UID always comes from Firebase Auth; it is never accepted from JSON.
 */
exports.claimDevice = functions.https.onCall(async (request) => {
  const data = request.data;
  const auth = request.auth;
  const logClaimDevice = (event, fields = {}) => {
    console.log(`[claimDevice] ${event}`, fields);
  };

  logClaimDevice("request received", {
    authValid: Boolean(auth?.uid),
  });

  if (!auth?.uid) {
    logClaimDevice("rejected", { reason: "unauthenticated" });
    throw new functions.https.HttpsError("unauthenticated", "Auth requerida.");
  }

  const payload = data && typeof data === "object" ? data : {};
  const publicKey = typeof payload.publicKey === "string" ? payload.publicKey.trim() : "";
  const signature = typeof payload.signature === "string" ? payload.signature.trim() : "";
  const keyId = typeof payload.keyId === "string" ? payload.keyId.trim() : "";
  const allowClaim = payload.allowClaim === true;

  logClaimDevice("payload parsed", {
    hasPublicKey: Boolean(publicKey),
    publicKeyLength: publicKey.length,
    hasSignature: Boolean(signature),
    signatureLength: signature.length,
    hasKeyId: Boolean(keyId),
    keyIdLength: keyId.length,
    allowClaim,
  });

  if (!publicKey || !signature || !keyId || publicKey.length > 4096 || signature.length > 1024) {
    logClaimDevice("rejected", { reason: "incomplete-proof" });
    throw new functions.https.HttpsError("invalid-argument", "Prueba de dispositivo incompleta.");
  }

  let publicKeyBytes;
  let signatureBytes;
  try {
    publicKeyBytes = Buffer.from(publicKey, "base64");
    signatureBytes = Buffer.from(signature, "base64");
    if (publicKeyBytes.length < 32 || signatureBytes.length < 32) throw new Error("short proof");
  } catch (_) {
    logClaimDevice("rejected", { reason: "invalid-base64-proof" });
    throw new functions.https.HttpsError("invalid-argument", "Prueba de dispositivo inválida.");
  }

  const calculatedKeyId = crypto.createHash("sha256").update(publicKeyBytes).digest("hex");
  if (calculatedKeyId !== keyId) {
    logClaimDevice("rejected", { reason: "key-id-mismatch" });
    throw new functions.https.HttpsError("invalid-argument", "Identificador de dispositivo inválido.");
  }

  const challenge = `blackhawk-device-v1:${auth.uid}:${publicKey}`;
  let proofIsValid = false;
  try {
    const verifier = crypto.createVerify("SHA256");
    verifier.update(challenge, "utf8");
    verifier.end();
    proofIsValid = verifier.verify(
      { key: publicKeyBytes, format: "der", type: "spki" },
      signatureBytes
    );
  } catch (_) {
    proofIsValid = false;
  }
  if (!proofIsValid) {
    logClaimDevice("rejected", { reason: "signature-invalid" });
    throw new functions.https.HttpsError("permission-denied", "Prueba de dispositivo inválida.");
  }

  logClaimDevice("proof verified", { allowClaim });

  const userRef = admin.firestore().collection("users").doc(auth.uid);
  try {
    await admin.firestore().runTransaction(async (tx) => {
      const userDoc = await tx.get(userRef);
      if (!userDoc.exists) {
        logClaimDevice("rejected", { reason: "profile-not-found" });
        throw new functions.https.HttpsError("not-found", "Perfil no encontrado.");
      }

      const profileUid = String(userDoc.get("uid") || "").trim();
      const status = String(userDoc.get("status") || "").trim().toLowerCase();
      if (profileUid !== auth.uid || status !== "approved") {
        logClaimDevice("rejected", {
          reason: "profile-not-authorized",
          profileUidMatches: profileUid === auth.uid,
          status,
        });
        throw new functions.https.HttpsError("permission-denied", "Cuenta no autorizada.");
      }

      const storedKeyId = String(userDoc.get("deviceKeyId") || "").trim();
      if (storedKeyId && storedKeyId !== keyId) {
        logClaimDevice("rejected", { reason: "different-device-already-bound" });
        throw new functions.https.HttpsError("failed-precondition", "Dispositivo no autorizado.");
      }
      if (!storedKeyId && !allowClaim) {
        logClaimDevice("rejected", { reason: "claim-not-allowed" });
        throw new functions.https.HttpsError("failed-precondition", "La cuenta aún no tiene dispositivo vinculado.");
      }

      const update = {
        deviceKeyId: keyId,
        devicePublicKey: publicKey,
        deviceLastSeenAt: admin.firestore.FieldValue.serverTimestamp(),
      };
      if (!storedKeyId) update.deviceClaimedAt = admin.firestore.FieldValue.serverTimestamp();
      tx.set(userRef, update, { merge: true });
      logClaimDevice("transaction update prepared", { hadStoredKey: Boolean(storedKeyId) });
    });
  } catch (error) {
    logClaimDevice("request failed", {
      code: error?.code || "internal",
      message: error?.message || String(error),
    });
    if (error instanceof functions.https.HttpsError) throw error;
    console.error("[claimDevice] transaction failed:", error?.message || error);
    throw new functions.https.HttpsError("internal", "No se pudo validar el dispositivo.");
  }

  logClaimDevice("request succeeded");
  return { ok: true, keyId };
});

/**
 * Records the authenticated app installation's version and most recent
 * presence. The UID and timestamp are always server-controlled; the client
 * can only report its build metadata for its own approved profile.
 */
exports.reportAppPresence = functions.https.onCall(async (request) => {
  const data = request?.data;
  const auth = request?.auth;
  if (!auth?.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Auth requerida.");
  }

  const payload = data && typeof data === "object" ? data : {};
  const appVersionCode = payload.appVersionCode;
  const appVersionName = typeof payload.appVersionName === "string"
    ? payload.appVersionName.trim()
    : "";

  if (!Number.isSafeInteger(appVersionCode) || appVersionCode < 1 || appVersionCode > 100000000) {
    throw new functions.https.HttpsError("invalid-argument", "Versión de aplicación inválida.");
  }
  if (!appVersionName || appVersionName.length > 100) {
    throw new functions.https.HttpsError("invalid-argument", "Nombre de versión inválido.");
  }

  const userRef = admin.firestore().collection("users").doc(auth.uid);
  try {
    const userDoc = await userRef.get();
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "Perfil no encontrado.");
    }

    const profileUid = String(userDoc.get("uid") || "").trim();
    const status = String(userDoc.get("status") || "").trim().toLowerCase();
    if (profileUid !== auth.uid || status !== "approved") {
      throw new functions.https.HttpsError("permission-denied", "Cuenta no autorizada.");
    }

    await userRef.set({
      appVersionCode,
      appVersionName,
      appLastSeenAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
  } catch (error) {
    if (error instanceof functions.https.HttpsError) throw error;
    console.error("[reportAppPresence] request failed:", error?.message || error);
    throw new functions.https.HttpsError("internal", "No se pudo registrar la versión de la aplicación.");
  }

  return { ok: true };
});

/**
 * Releases the authenticated account's device binding after proving possession
 * of the currently bound Android Keystore key.
 */
exports.releaseDevice = functions.https.onCall(async (request) => {
  const auth = request.auth;
  if (!auth?.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Auth requerida.");
  }

  const payload = request.data && typeof request.data === "object" ? request.data : {};
  const publicKey = typeof payload.publicKey === "string" ? payload.publicKey.trim() : "";
  const signature = typeof payload.signature === "string" ? payload.signature.trim() : "";
  const keyId = typeof payload.keyId === "string" ? payload.keyId.trim() : "";

  if (!publicKey || !signature || !keyId || publicKey.length > 4096 || signature.length > 1024) {
    throw new functions.https.HttpsError("invalid-argument", "Prueba de dispositivo incompleta.");
  }

  let publicKeyBytes;
  let signatureBytes;
  try {
    publicKeyBytes = Buffer.from(publicKey, "base64");
    signatureBytes = Buffer.from(signature, "base64");
    if (publicKeyBytes.length < 32 || signatureBytes.length < 32) throw new Error("short proof");
  } catch (_) {
    throw new functions.https.HttpsError("invalid-argument", "Prueba de dispositivo inválida.");
  }

  const calculatedKeyId = crypto.createHash("sha256").update(publicKeyBytes).digest("hex");
  if (calculatedKeyId !== keyId) {
    throw new functions.https.HttpsError("invalid-argument", "Identificador de dispositivo inválido.");
  }

  const challenge = `blackhawk-device-release-v1:${auth.uid}:${publicKey}`;
  try {
    const verifier = crypto.createVerify("SHA256");
    verifier.update(challenge, "utf8");
    verifier.end();
    if (!verifier.verify({ key: publicKeyBytes, format: "der", type: "spki" }, signatureBytes)) {
      throw new functions.https.HttpsError("permission-denied", "Prueba de dispositivo inválida.");
    }
  } catch (error) {
    if (error instanceof functions.https.HttpsError) throw error;
    throw new functions.https.HttpsError("permission-denied", "Prueba de dispositivo inválida.");
  }

  const userRef = admin.firestore().collection("users").doc(auth.uid);
  try {
    await admin.firestore().runTransaction(async (tx) => {
      const userDoc = await tx.get(userRef);
      if (!userDoc.exists) throw new functions.https.HttpsError("not-found", "Perfil no encontrado.");

      const profileUid = String(userDoc.get("uid") || "").trim();
      const status = String(userDoc.get("status") || "").trim().toLowerCase();
      if (profileUid !== auth.uid || status !== "approved") {
        throw new functions.https.HttpsError("permission-denied", "Cuenta no autorizada.");
      }

      const storedKeyId = String(userDoc.get("deviceKeyId") || "").trim();
      const storedPublicKey = String(userDoc.get("devicePublicKey") || "").trim();
      if (!storedKeyId) return;
      if (storedKeyId !== keyId || storedPublicKey !== publicKey) {
        throw new functions.https.HttpsError("failed-precondition", "Dispositivo no autorizado.");
      }

      tx.update(userRef, {
        deviceKeyId: admin.firestore.FieldValue.delete(),
        devicePublicKey: admin.firestore.FieldValue.delete(),
        deviceClaimedAt: admin.firestore.FieldValue.delete(),
        deviceLastSeenAt: admin.firestore.FieldValue.delete(),
      });
    });
  } catch (error) {
    if (error instanceof functions.https.HttpsError) throw error;
    console.error("[releaseDevice] transaction failed:", error?.message || error);
    throw new functions.https.HttpsError("internal", "No se pudo desvincular el dispositivo.");
  }

  return { ok: true };
});

/**
 * Callable: adminSuspendUser
 * Input: { targetUid: string, reason: string, idToken?: string }
 */
exports.adminSuspendUser = functions.https.onCall(async (data, context) => {
  const payload = pickPayload(data);

  // Logs mínimos para ver si está llegando lo que esperamos
  console.log("[adminSuspendUser] context.auth?", !!context.auth, "uid=", context.auth?.uid || null);
  console.log("[adminSuspendUser] data.keys=", data ? Object.keys(data) : null);
  console.log("[adminSuspendUser] payload.keys=", payload ? Object.keys(payload) : null);

  // ✅ 1) Si context.auth llega, úsalo
  let callerUid = context.auth?.uid || null;

  // ✅ 2) Si no, intenta token por payload (idToken) o por Authorization header
  const idTokenFromPayload = (payload.idToken ? String(payload.idToken) : "").trim();
  const idTokenFromHeader = getBearerToken(context);
  const idToken = idTokenFromPayload || idTokenFromHeader;

  if (!callerUid) {
    if (!idToken) {
      throw new functions.https.HttpsError("unauthenticated", "Auth requerida.");
    }
    try {
      const decoded = await admin.auth().verifyIdToken(idToken);
      callerUid = decoded.uid;
    } catch (e) {
      console.log("[adminSuspendUser] verifyIdToken failed:", e?.message || e);
      throw new functions.https.HttpsError("unauthenticated", "Auth inválida.");
    }
  }

  const targetUid = (payload.targetUid ? String(payload.targetUid) : "").trim();
  const reason = (payload.reason ? String(payload.reason) : "").trim();

  if (!targetUid) {
    throw new functions.https.HttpsError("invalid-argument", "targetUid es requerido.");
  }

  // Validar rol del caller (admin/developer)
  const callerDoc = await admin.firestore().collection("users").doc(callerUid).get();
  const callerRole = (callerDoc.get("role") || "user").toString().toLowerCase();
  const callerStatus = (callerDoc.get("status") || "approved").toString().toLowerCase();

  if (callerStatus !== "approved") {
    throw new functions.https.HttpsError("permission-denied", "Cuenta no aprobada.");
  }

  if (callerRole !== "admin" && callerRole !== "developer") {
    throw new functions.https.HttpsError("permission-denied", "Sin permisos.");
  }

  const userRef = admin.firestore().collection("users").doc(targetUid);
  const auditRef = userRef.collection("audit").doc();

  await admin.firestore().runTransaction(async (tx) => {
    tx.set(
      userRef,
      {
        status: "suspended",
        suspendedAt: admin.firestore.FieldValue.serverTimestamp(),
        suspendedBy: callerUid,
        suspendReason: reason,

        reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
        reviewedBy: callerUid,
      },
      { merge: true }
    );

    tx.set(auditRef, {
      type: "SUSPEND",
      at: admin.firestore.FieldValue.serverTimestamp(),
      actorUid: callerUid,
      reason: reason,
    });
  });

  await admin.auth().updateUser(targetUid, { disabled: true });
  await admin.auth().revokeRefreshTokens(targetUid);

  return { ok: true };
});
