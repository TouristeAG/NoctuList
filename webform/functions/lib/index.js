// src/index.ts
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { defineString } from "firebase-functions/params";

// src/signup.ts
import * as logger from "firebase-functions/logger";

// src/common.ts
import { createHash } from "node:crypto";
var SERVER_VERSION = "1.0.0";
var STATUS_PROCESSING = "PROCESSING";
var PROCESSING_STALE_MILLIS = 5 * 60 * 1e3;
var PROJECTION_SCHEMA = 2;
var MAX_LABEL = 80;
var sha256 = (text) => createHash("sha256").update(text, "utf8").digest("hex");
async function portalEnabled(db, orgId) {
  const snap = await db.doc(`orgs/${orgId}/institutionSettings/volunteer_portal_enabled`).get();
  const value = snap.exists ? snap.get("value") : void 0;
  return value === "true" || value === true;
}
async function institutionSetting(db, orgId, key) {
  const snap = await db.doc(`orgs/${orgId}/institutionSettings/${key}`).get();
  const value = snap.exists ? snap.get("value") : void 0;
  return typeof value === "string" ? value : value === true ? "true" : "";
}
function millisOf(value) {
  if (value === null || value === void 0) return null;
  if (typeof value === "number") return Number.isFinite(value) ? Math.trunc(value) : null;
  if (typeof value === "string") {
    const n = Number(value);
    return value.trim() !== "" && Number.isFinite(n) ? Math.trunc(n) : null;
  }
  if (value instanceof Date) return value.getTime();
  const toMillis = value.toMillis;
  if (typeof toMillis === "function") return toMillis.call(value);
  return null;
}
function isStale(d, now = Date.now()) {
  if (d.status !== STATUS_PROCESSING) return false;
  const at = millisOf(d.processingAt);
  return at === null || now - at > PROCESSING_STALE_MILLIS;
}
async function claim(ref, isClaimable2) {
  return ref.firestore.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const data = snap.data();
    if (!isClaimable2(data)) return null;
    tx.set(ref, { status: STATUS_PROCESSING, processingAt: Date.now(), processingDevice: "server" }, { merge: true });
    return data;
  });
}
async function touchStatus(db, orgId) {
  await db.doc(`orgs/${orgId}/portalServer/status`).set({ version: SERVER_VERSION, lastRunAt: Date.now() });
}
function minimalProjection(v, now = Date.now()) {
  const hasPhoto = v.photoVersion !== "";
  const payload = JSON.stringify({
    firstName: v.firstName.trim(),
    lastName: v.lastName.trim(),
    email: v.email.trim(),
    phone: v.phone.trim(),
    dateOfBirth: v.dateOfBirth.trim(),
    gender: v.gender,
    hasPhoto,
    photoVersion: hasPhoto ? v.photoVersion : "",
    shifts: [],
    lastShiftFloor: 0,
    timeline: [{ from: 0, active: false, summary: null, benefits: [], uniqueEntriesRemaining: null }],
    wallet: { balance: 0, currency: v.currency.trim() || "CHF", transfers: [] },
    // A new volunteer is never an admin, so only a missing e-mail forbids the QR mail.
    qrEmailAllowed: v.email.trim() !== "",
    mustSetPassword: false,
    association: v.association.trim().slice(0, MAX_LABEL)
  });
  return { schema: PROJECTION_SCHEMA, payload, contentHash: sha256(payload), updatedAt: now };
}

// src/nanoid.ts
import { randomInt } from "node:crypto";
var ALPHABET = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
var SIZE = 21;
function volunteerId() {
  let id = "";
  for (let i = 0; i < SIZE; i++) id += ALPHABET[randomInt(ALPHABET.length)];
  return id;
}

// src/validate.ts
var NAME = /^[^<>{}\\]{1,60}$/u;
var PHONE = /^[0-9+() .-]{4,32}$/u;
var DATE = /^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/u;
var EMAIL = /^[^@ \t\n\x0B\f\r]{1,64}@[^@ \t\n\x0B\f\r]{1,190}\.[^@ \t\n\x0B\f\r]{2,}$/u;
var UID = /^[A-Za-z0-9]{6,128}$/u;
var LANGS = /* @__PURE__ */ new Set(["fr", "en", "es", "de", "it"]);
var TYPES = ["PROFILE", "QR_EMAIL", "CLAIM"];
var GENDERS = /* @__PURE__ */ new Set(["FEMALE", "MALE", "NON_BINARY", "OTHER", "PREFER_NOT_TO_DISCLOSE"]);
var WS = "\\t-\\r\\u001C-\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
var TRIM = new RegExp(`^[${WS}]+|[${WS}]+$`, "gu");
var ASCII_RUN = /[ \t\n\x0B\f\r]+/g;
var kotlinTrim = (s) => s.replace(TRIM, "");
function cleanName(raw) {
  if (typeof raw !== "string") return null;
  const value = kotlinTrim(raw).replace(ASCII_RUN, " ");
  return value.length > 0 && NAME.test(value) ? value : null;
}
function cleanPhone(raw) {
  if (typeof raw !== "string") return null;
  const value = kotlinTrim(raw);
  return PHONE.test(value) ? value : null;
}
function cleanEmail(raw) {
  if (typeof raw !== "string") return null;
  const value = kotlinTrim(raw).toLowerCase();
  return value.length <= 254 && EMAIL.test(value) ? value : null;
}
var str = (v) => typeof v === "string" ? v : void 0;
function parseSignup(formId, data, orgId = "") {
  if (String(data.status) !== "SUBMITTED") return null;
  const uid = str(data.submittedUid);
  if (uid === void 0 || !UID.test(uid)) return null;
  const email = cleanEmail(data.email);
  if (email === null) return null;
  const firstName = cleanName(data.firstName);
  if (firstName === null) return null;
  const lastName = cleanName(data.lastName);
  if (lastName === null) return null;
  const phone = cleanPhone(data.phone);
  if (phone === null) return null;
  const dateOfBirth = str(data.dateOfBirth);
  if (dateOfBirth === void 0 || !DATE.test(dateOfBirth)) return null;
  const gender = String(data.gender);
  if (!GENDERS.has(gender)) return null;
  if (data.consent !== true) return null;
  const photoPath = str(data.photoPath) ?? "";
  const ownPhoto = `orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg`;
  const lang = str(data.lang);
  return {
    formId,
    uid,
    email,
    firstName,
    lastName,
    phone,
    dateOfBirth,
    gender,
    photoPath: photoPath === ownPhoto && orgId !== "" ? ownPhoto : "",
    lang: lang !== void 0 && LANGS.has(lang) ? lang : "fr"
  };
}
function parseRequest(requestId, data) {
  const uid = str(data.uid);
  if (uid === void 0 || !UID.test(uid)) return null;
  const typeName = String(data.type);
  if (!TYPES.includes(typeName)) return null;
  const type = typeName;
  if (requestId !== `${uid}_${type}`) return null;
  const email = cleanEmail(data.email);
  if (email === null) return null;
  const status = data.status === null || data.status === void 0 ? "" : String(data.status);
  if (type === "PROFILE") {
    const firstName = cleanName(data.firstName);
    const lastName = cleanName(data.lastName);
    const phone = cleanPhone(data.phone);
    if (firstName === null || lastName === null || phone === null) return null;
    return { requestId, uid, type, status, email, firstName, lastName, phone };
  }
  return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "" };
}

// src/signup.ts
var STATUS_SUBMITTED = "SUBMITTED";
var MAX_PHOTO_BYTES = 3 * 1024 * 1024;
var isClaimable = (d) => d.status === STATUS_SUBMITTED || isStale(d);
var isBlank = (v) => typeof v !== "string" || v.trim() === "";
var signupPhotoPath = (orgId, formId) => `orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg`;
var volunteerPhotoPath = (orgId, id) => `orgs/${orgId.trim()}/profilePhotos/volunteers/${id.trim()}.jpg`;
var portalPhotoPath = (orgId, uid) => `orgs/${orgId}/portalPhotos/${uid}.jpg`;
async function onSignupDocument(db, bucket, orgId, formId, current) {
  if (current === void 0 || !isClaimable(current)) return;
  if (!await portalEnabled(db, orgId)) {
    logger.info("signup skipped", { orgId, formId, reason: "PORTAL_OFF" });
    return;
  }
  const ref = db.doc(`orgs/${orgId}/volunteerSignupForms/${formId}`);
  const claimed = await claim(ref, isClaimable);
  if (claimed === null) return;
  try {
    const outcome = await processClaimed(db, bucket, orgId, formId, ref, claimed);
    await touchStatus(db, orgId);
    logger.info("signup processed", { orgId, formId, reason: outcome });
  } catch (e) {
    const code = e?.code;
    logger.error("signup failed", { orgId, formId, reason: "FAILED", code: code === void 0 ? "" : String(code) });
  }
}
async function processClaimed(db, bucket, orgId, formId, ref, claimed) {
  const submission = parseSignup(formId, { ...claimed, status: STATUS_SUBMITTED }, orgId);
  if (submission === null) {
    await finish(bucket, ref, orgId, formId);
    return "INVALID";
  }
  const photo = await readSignupPhoto(db, bucket, orgId, submission.photoPath);
  const volunteers = await db.collection(`orgs/${orgId}/volunteers`).select("email", "portalUid", "profilePhotoPath", "profilePhotoUrl").get();
  let outcome;
  if (volunteers.docs.some((d) => d.get("portalUid") === submission.uid)) {
    outcome = "ALREADY_LINKED";
  } else {
    const sameEmail = volunteers.docs.find(
      (d) => typeof d.get("email") === "string" && d.get("email").trim().toLowerCase() === submission.email && isBlank(d.get("portalUid"))
    );
    if (sameEmail !== void 0) {
      await link(db, bucket, orgId, sameEmail.ref, submission, photo);
      outcome = "LINKED";
    } else {
      await create(db, bucket, orgId, submission, photo);
      outcome = "CREATED";
    }
  }
  await finish(bucket, ref, orgId, formId);
  return outcome;
}
async function readSignupPhoto(db, bucket, orgId, photoPath) {
  if (photoPath === "") return null;
  if ((await institutionSetting(db, orgId, "profile_photos_enabled")).trim().toLowerCase() !== "true") return null;
  const file = bucket.file(photoPath);
  const [exists] = await file.exists();
  if (!exists) return null;
  const [meta] = await file.getMetadata();
  if (meta.contentType !== "image/jpeg" || Number(meta.size) >= MAX_PHOTO_BYTES) return null;
  const [bytes] = await file.download();
  const isJpeg = bytes.length > 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  return isJpeg && bytes.length < MAX_PHOTO_BYTES ? bytes : null;
}
async function saveJpeg(bucket, path, bytes) {
  await bucket.file(path).save(bytes, { contentType: "image/jpeg", resumable: false });
}
async function link(db, bucket, orgId, ref, submission, photo) {
  const linked = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank(snap.get("portalUid"))) return null;
    tx.update(ref, { portalEnabled: true, portalUid: submission.uid, lastModified: Date.now() });
    return snap.data();
  });
  if (linked === null) throw Object.assign(new Error("link conflict"), { code: "LINK_CONFLICT" });
  if (photo === null || !isBlank(linked.profilePhotoPath) || !isBlank(linked.profilePhotoUrl)) return;
  const path = volunteerPhotoPath(orgId, ref.id);
  await saveJpeg(bucket, path, photo);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank(snap.get("profilePhotoPath")) || !isBlank(snap.get("profilePhotoUrl"))) return;
    tx.update(ref, { profilePhotoPath: path, lastModified: Date.now() });
  });
}
async function create(db, bucket, orgId, submission, photo) {
  const id = volunteerId();
  let profilePhotoPath = "";
  if (photo !== null) {
    profilePhotoPath = volunteerPhotoPath(orgId, id);
    await saveJpeg(bucket, profilePhotoPath, photo);
    await saveJpeg(bucket, portalPhotoPath(orgId, submission.uid), photo);
  }
  const lastName = submission.lastName.toUpperCase();
  const now = Date.now();
  const volunteer = {
    id,
    name: submission.firstName,
    lastNameAbbreviation: lastName,
    email: submission.email,
    phone: submission.phone,
    dateOfBirth: submission.dateOfBirth,
    gender: submission.gender,
    currentRank: null,
    isActive: true,
    lastShiftDate: null,
    lastModified: now,
    nfcCardUid: "",
    nfcCardUidHash: "",
    isAdmin: false,
    ...profilePhotoPath !== "" ? { profilePhotoPath } : {},
    portalEnabled: true,
    portalUid: submission.uid
  };
  const [currency, association] = await Promise.all([
    institutionSetting(db, orgId, "currency_code"),
    associationName(db, orgId)
  ]);
  const projection = minimalProjection({
    firstName: submission.firstName,
    lastName,
    email: submission.email,
    phone: submission.phone,
    dateOfBirth: submission.dateOfBirth,
    gender: submission.gender,
    photoVersion: profilePhotoPath !== "" ? sha256(profilePhotoPath).slice(0, 16) : "",
    currency: currency.trim().toUpperCase(),
    association
  }, now);
  const batch = db.batch();
  batch.create(db.doc(`orgs/${orgId}/volunteers/${id}`), volunteer);
  batch.set(db.doc(`orgs/${orgId}/volunteerPortal/${submission.uid}`), projection);
  await batch.commit();
}
async function associationName(db, orgId) {
  const branding = await db.doc(`orgs/${orgId}/portalBranding/default`).get();
  const name = branding.exists ? branding.get("associationName") : void 0;
  if (typeof name === "string" && name.trim() !== "") return name;
  return institutionSetting(db, orgId, "email_association_name");
}
async function finish(bucket, ref, orgId, formId) {
  await bucket.file(signupPhotoPath(orgId, formId)).delete({ ignoreNotFound: true }).catch(() => void 0);
  await ref.delete();
}

// src/index.ts
var REGION = defineString("REGION", { default: "europe-west1" });
function admin() {
  if (!getApps().some((app) => app.name === "[DEFAULT]")) initializeApp();
  return { db: getFirestore(), bucket: getStorage().bucket() };
}
var onSignupWritten = onDocumentWritten(
  { document: "orgs/{orgId}/volunteerSignupForms/{formId}", region: REGION, retry: false },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db, bucket } = admin();
    await onSignupDocument(db, bucket, event.params.orgId, event.params.formId, after.data());
  }
);
export {
  cleanEmail,
  cleanName,
  cleanPhone,
  onSignupWritten,
  parseRequest,
  parseSignup
};
