// src/index.ts
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { defineSecret, defineString } from "firebase-functions/params";

// src/requests.ts
import * as logger2 from "firebase-functions/logger";

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
async function associationName(db, orgId) {
  const branding = await db.doc(`orgs/${orgId}/portalBranding/default`).get();
  const name = branding.exists ? branding.get("associationName") : void 0;
  if (typeof name === "string" && name.trim() !== "") return name;
  return institutionSetting(db, orgId, "email_association_name");
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
async function claim(ref, isClaimable3) {
  return ref.firestore.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const data = snap.data();
    if (!isClaimable3(data)) return null;
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

// src/mail.ts
import * as logger from "firebase-functions/logger";
import nodemailer from "nodemailer";
import QRCode from "qrcode";

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

// src/mail.ts
var PORTAL_FIRST_NAME_TOKEN = "%%NOCTULIST_FIRST_NAME%%";
var MAIL_TEMPLATE_SCHEMA = 1;
var QR_WIDTH = 600;
var QR_FILE = "qr_code.png";
var MAX_LOGO_BYTES = 512 * 1024;
var MAX_SUBJECT = 200;
function smtpOptions(user, password) {
  return {
    service: "gmail",
    auth: { user, pass: password },
    connectionTimeout: 1e4,
    greetingTimeout: 1e4,
    socketTimeout: 2e4
  };
}
function fallbackTemplate(withName) {
  const greeting = withName ? `Hello ${PORTAL_FIRST_NAME_TOKEN},` : "Hello,";
  return {
    subject: "Your volunteer QR code",
    html: `<!DOCTYPE html><html><body style="font-family: Arial, sans-serif; color: #222;"><p>${greeting}</p><p>Here is your volunteer QR code.</p><p><img src="cid:qrcode" alt="QR code" width="300" height="300"></p><p>It is also attached to this e-mail.</p></body></html>`,
    text: `${greeting}

Here is your volunteer QR code. It is attached to this e-mail.
`,
    logoBase64: ""
  };
}
var CONTROL = /[\u0000-\u001F\u007F]+/g;
var LOGO_IMG = /<img\b[^>]*\bsrc\s*=\s*["']?cid:logo["']?[^>]*>/gi;
var escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
var fill = (template, value) => template.split(PORTAL_FIRST_NAME_TOKEN).join(value);
function parseMailTemplate(data) {
  if (data === void 0 || data.schema !== MAIL_TEMPLATE_SCHEMA) return null;
  const { subject, html, text, logoBase64 } = data;
  if (typeof subject !== "string" || subject.trim() === "") return null;
  if (typeof html !== "string" || html.trim() === "") return null;
  if (typeof text !== "string") return null;
  return { subject, html, text, logoBase64: typeof logoBase64 === "string" ? logoBase64 : "" };
}
function logoBytes(logoBase64) {
  const raw = logoBase64.trim();
  if (raw === "") return null;
  const bytes = Buffer.from(raw, "base64");
  const isPng = bytes.length > 8 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71;
  return isPng && bytes.length <= MAX_LOGO_BYTES ? bytes : null;
}
async function buildQrMail(template, firstName, qrPayload) {
  const name = firstName.replace(CONTROL, " ").trim();
  const t = template ?? fallbackTemplate(name !== "");
  const qr = await QRCode.toBuffer(qrPayload, { type: "png", width: QR_WIDTH, errorCorrectionLevel: "M" });
  const attachments = [
    { filename: QR_FILE, content: qr, contentType: "image/png", contentDisposition: "inline", cid: "qrcode" },
    { filename: QR_FILE, content: qr, contentType: "image/png", contentDisposition: "attachment" }
  ];
  const logo = logoBytes(t.logoBase64);
  if (logo !== null) {
    attachments.push({ filename: "logo.png", content: logo, contentType: "image/png", contentDisposition: "inline", cid: "logo" });
  }
  const html = logo === null ? t.html.replace(LOGO_IMG, "") : t.html;
  return {
    subject: fill(t.subject, name).replace(CONTROL, " ").trim().slice(0, MAX_SUBJECT),
    // QrEmailHtmlBuilder puts the holder name in raw: escape it here.
    html: fill(html, escapeHtml(name)),
    text: fill(t.text, name),
    attachments
  };
}
var ADDRESS_FORBIDDEN = /[\s,;<>"()[\]\\:]/u;
function safeRecipient(raw) {
  const address = raw.trim();
  if (ADDRESS_FORBIDDEN.test(address) || cleanEmail(address) === null) return null;
  return address;
}
var isCapture = () => (
  // Capture is test plumbing: it is ignored outside the emulator, so a stray setting never stores mail.
  process.env.FUNCTIONS_EMULATOR === "true" && process.env.PORTAL_MAIL_TRANSPORT === "capture"
);
var cached = null;
function gmail(user, password) {
  const key = `${user}
${password}`;
  if (cached === null || cached.key !== key) {
    cached = { key, transporter: nodemailer.createTransport(smtpOptions(user, password)) };
  }
  return cached.transporter;
}
async function sendMail(msg, transport) {
  try {
    const to = safeRecipient(msg.to);
    const user = transport.user().trim();
    if (to === null || safeRecipient(user) === null) {
      logger.error("mail not sent", { reason: "BAD_ADDRESS" });
      return false;
    }
    const fromName = msg.fromName.replace(CONTROL, " ").trim();
    if (isCapture()) {
      await transport.db.collection("_testMail").add({
        to,
        from: { name: fromName, address: user },
        subject: msg.subject,
        html: msg.html,
        text: msg.text,
        attachments: msg.attachments.map((a) => ({
          filename: a.filename,
          contentType: a.contentType,
          disposition: a.contentDisposition,
          cid: a.cid ?? "",
          size: a.content.length
        })),
        capturedAt: Date.now()
      });
      return true;
    }
    const password = transport.password().replace(/\s+/g, "");
    if (password === "") {
      logger.error("mail not sent", { reason: "NO_PASSWORD" });
      return false;
    }
    const smtp = transport.smtp ?? gmail;
    await smtp(user, password).sendMail({
      from: fromName === "" ? user : { name: fromName, address: user },
      to: { name: "", address: to },
      // Pinned envelope: the message goes to this one address whatever the headers say.
      envelope: { from: user, to: [to] },
      subject: msg.subject,
      html: msg.html,
      text: msg.text,
      attachments: msg.attachments
    });
    return true;
  } catch (e) {
    const err = e;
    logger.error("mail not sent", {
      reason: "SEND_FAILED",
      code: err?.code === void 0 ? "" : String(err.code),
      responseCode: typeof err?.responseCode === "number" ? err.responseCode : 0
    });
    return false;
  }
}

// src/requests.ts
var STATUS_PENDING = "PENDING";
var MAX_LABEL2 = 80;
var DONE = { status: "DONE", reason: "" };
var rejected = (reason) => ({ status: "REJECTED", reason });
var isClaimable = (d) => d.status === STATUS_PENDING || isStale(d);
var stringOf = (v) => v === null || v === void 0 ? "" : typeof v === "string" ? v : String(v);
function boolOf(v) {
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v.toLowerCase() === "true";
  if (typeof v === "number") return Number.isFinite(v) && Math.trunc(v) !== 0;
  return false;
}
var isBlank = (v) => kotlinTrim(stringOf(v)) === "";
var isOwner = (d, uid) => boolOf(d.get("portalEnabled")) && stringOf(d.get("portalUid")) === uid;
var errorCode = (e) => {
  const code = e?.code;
  return code === void 0 ? "" : String(code);
};
async function onRequestDocument(db, mail, orgId, requestId, current) {
  if (current === void 0 || !isClaimable(current)) return;
  let result;
  try {
    if (!await portalEnabled(db, orgId)) {
      logger2.info("request skipped", { orgId, requestId, reason: "PORTAL_OFF" });
      return;
    }
    const ref = db.doc(`orgs/${orgId}/portalRequests/${requestId}`);
    const claimed = await claim(ref, isClaimable);
    if (claimed === null) return;
    result = await processClaimed(db, mail, orgId, requestId, claimed);
    await complete(ref, result);
  } catch (e) {
    logger2.error("request failed", { orgId, requestId, reason: "FAILED", code: errorCode(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger2.error("server status not written", { orgId, requestId, code: errorCode(e) });
  }
  logger2.info("request processed", { orgId, requestId, status: result.status, reason: result.reason });
}
async function processClaimed(db, mail, orgId, requestId, claimed) {
  const request = parseRequest(requestId, claimed);
  if (request === null) return rejected("INVALID");
  switch (request.type) {
    case "PROFILE":
      return updateProfile(db, orgId, requestId, request);
    case "CLAIM":
      return handleClaim(db, orgId, requestId, request);
    case "QR_EMAIL":
      return sendQr(db, mail, orgId, request);
  }
}
async function complete(ref, result) {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now() }, { merge: true });
}
var volunteers = (db, orgId) => db.collection(`orgs/${orgId}/volunteers`);
async function updateProfile(db, orgId, requestId, request) {
  const projectionRef = db.doc(`orgs/${orgId}/volunteerPortal/${request.uid}`);
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(volunteers(db, orgId).where("portalUid", "==", request.uid));
      const owner = linked.docs.find((d) => isOwner(d, request.uid));
      if (owner === void 0) return rejected("NO_ACCESS");
      const projection = await tx.get(projectionRef);
      const now = Date.now();
      const lastName = request.lastName.toUpperCase();
      tx.update(owner.ref, {
        name: request.firstName,
        lastNameAbbreviation: lastName,
        phone: request.phone,
        lastModified: Math.max(now, (millisOf(owner.get("lastModified")) ?? 0) + 1)
      });
      const patch = patchedProjection(projection.data(), request.firstName, lastName, request.phone, now);
      if (patch !== null) tx.update(projectionRef, patch);
      return DONE;
    });
  } catch (e) {
    logger2.error("profile not saved", { orgId, requestId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}
function patchedProjection(data, firstName, lastName, phone, now) {
  if (data === void 0 || typeof data.payload !== "string") return null;
  let payload;
  try {
    payload = JSON.parse(data.payload);
  } catch {
    return null;
  }
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return null;
  const text = JSON.stringify({ ...payload, firstName, lastName, phone });
  return { payload: text, contentHash: sha256(text), updatedAt: now };
}
async function handleClaim(db, orgId, requestId, request) {
  const all = await volunteers(db, orgId).select("email", "portalEnabled", "portalUid").get();
  if (all.docs.some((d) => isOwner(d, request.uid))) return DONE;
  const sameEmail = (d) => boolOf(d.get("portalEnabled")) && kotlinTrim(stringOf(d.get("email"))).toLowerCase() === request.email;
  const candidates = all.docs.filter(sameEmail);
  const free = candidates.filter((d) => isBlank(d.get("portalUid")));
  if (candidates.length === 0) return rejected("NO_ACCESS");
  if (free.length !== 1) return rejected("LINKED_ELSEWHERE");
  const ref = free[0].ref;
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(volunteers(db, orgId).where("portalUid", "==", request.uid));
      if (linked.docs.some((d) => isOwner(d, request.uid))) return DONE;
      const snap = await tx.get(ref);
      if (!snap.exists || !sameEmail(snap) || !isBlank(snap.get("portalUid"))) {
        return rejected("SAVE_FAILED");
      }
      tx.update(ref, {
        portalUid: request.uid,
        lastModified: Math.max(Date.now(), (millisOf(snap.get("lastModified")) ?? 0) + 1)
      });
      return DONE;
    });
  } catch (e) {
    logger2.error("claim not saved", { orgId, requestId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}
async function sendQr(db, mail, orgId, request) {
  const linked = await volunteers(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = linked.docs.find((d) => isOwner(d, request.uid));
  if (owner === void 0) return rejected("NO_ACCESS");
  if (boolOf(owner.get("isAdmin"))) return rejected("NOT_ALLOWED");
  const email = kotlinTrim(stringOf(owner.get("email")));
  if (email === "") return rejected("NO_EMAIL");
  const to = safeRecipient(email);
  if (to === null) return rejected("NO_EMAIL");
  const [template, association] = await Promise.all([
    db.doc(`orgs/${orgId}/portalMail/default`).get(),
    associationName(db, orgId)
  ]);
  const message = await buildQrMail(parseMailTemplate(template.data()), stringOf(owner.get("name")), owner.id);
  const sent = await sendMail({ ...message, to, fromName: association.trim().slice(0, MAX_LABEL2) }, mail);
  return sent ? DONE : rejected("SEND_FAILED");
}

// src/signup.ts
import * as logger3 from "firebase-functions/logger";

// src/nanoid.ts
import { randomInt } from "node:crypto";
var ALPHABET = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
var SIZE = 21;
function volunteerId() {
  let id = "";
  for (let i = 0; i < SIZE; i++) id += ALPHABET[randomInt(ALPHABET.length)];
  return id;
}

// src/signup.ts
var STATUS_SUBMITTED = "SUBMITTED";
var MAX_PHOTO_BYTES = 3 * 1024 * 1024;
var isClaimable2 = (d) => d.status === STATUS_SUBMITTED || isStale(d);
var isBlank2 = (v) => typeof v !== "string" || v.trim() === "";
var errorCode2 = (e) => {
  const code = e?.code;
  return code === void 0 ? "" : String(code);
};
var signupPhotoPath = (orgId, formId) => `orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg`;
var volunteerPhotoPath = (orgId, id) => `orgs/${orgId.trim()}/profilePhotos/volunteers/${id.trim()}.jpg`;
var portalPhotoPath = (orgId, uid) => `orgs/${orgId}/portalPhotos/${uid}.jpg`;
async function onSignupDocument(db, bucket, orgId, formId, current) {
  if (current === void 0 || !isClaimable2(current)) return;
  if (!await portalEnabled(db, orgId)) {
    logger3.info("signup skipped", { orgId, formId, reason: "PORTAL_OFF" });
    return;
  }
  const ref = db.doc(`orgs/${orgId}/volunteerSignupForms/${formId}`);
  const claimed = await claim(ref, isClaimable2);
  if (claimed === null) return;
  let outcome;
  try {
    outcome = await processClaimed2(db, bucket, orgId, formId, ref, claimed);
  } catch (e) {
    logger3.error("signup failed", { orgId, formId, reason: "FAILED", code: errorCode2(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger3.error("server status not written", { orgId, formId, code: errorCode2(e) });
  }
  logger3.info("signup processed", { orgId, formId, reason: outcome });
}
async function processClaimed2(db, bucket, orgId, formId, ref, claimed) {
  const submission = parseSignup(formId, { ...claimed, status: STATUS_SUBMITTED }, orgId);
  if (submission === null) {
    await finish(bucket, ref, orgId, formId);
    return "INVALID";
  }
  const photo = await readSignupPhoto(db, bucket, orgId, submission.photoPath);
  const volunteers2 = await db.collection(`orgs/${orgId}/volunteers`).select("email", "portalUid", "profilePhotoPath", "profilePhotoUrl").get();
  let outcome;
  if (volunteers2.docs.some((d) => d.get("portalUid") === submission.uid)) {
    outcome = "ALREADY_LINKED";
  } else {
    const sameEmail = volunteers2.docs.find(
      (d) => typeof d.get("email") === "string" && d.get("email").trim().toLowerCase() === submission.email && isBlank2(d.get("portalUid"))
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
    if (!snap.exists || !isBlank2(snap.get("portalUid"))) return null;
    tx.update(ref, { portalEnabled: true, portalUid: submission.uid, lastModified: Date.now() });
    return snap.data();
  });
  if (linked === null) throw Object.assign(new Error("link conflict"), { code: "LINK_CONFLICT" });
  if (photo === null || !isBlank2(linked.profilePhotoPath) || !isBlank2(linked.profilePhotoUrl)) return;
  const path = volunteerPhotoPath(orgId, ref.id);
  await saveJpeg(bucket, path, photo);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank2(snap.get("profilePhotoPath")) || !isBlank2(snap.get("profilePhotoUrl"))) return;
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
async function finish(bucket, ref, orgId, formId) {
  await bucket.file(signupPhotoPath(orgId, formId)).delete({ ignoreNotFound: true }).catch(() => void 0);
  await ref.delete();
}

// src/index.ts
var REGION = defineString("REGION", { default: "europe-west1" });
var GMAIL_USER = defineString("GMAIL_USER");
var GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");
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
var onPortalRequestWritten = onDocumentWritten(
  {
    document: "orgs/{orgId}/portalRequests/{requestId}",
    region: REGION,
    retry: false,
    // Room for the SMTP timeouts (mail.ts) and the Firestore work around them.
    timeoutSeconds: 120,
    secrets: [GMAIL_APP_PASSWORD]
  },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db } = admin();
    const mail = { db, user: () => GMAIL_USER.value(), password: () => GMAIL_APP_PASSWORD.value() };
    await onRequestDocument(db, mail, event.params.orgId, event.params.requestId, after.data());
  }
);
export {
  buildQrMail,
  cleanEmail,
  cleanName,
  cleanPhone,
  onPortalRequestWritten,
  onSignupWritten,
  parseMailTemplate,
  parseRequest,
  parseSignup,
  safeRecipient,
  sendMail,
  smtpOptions
};
