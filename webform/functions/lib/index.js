// src/index.ts
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as logger6 from "firebase-functions/logger";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { defineSecret, defineString } from "firebase-functions/params";

// src/requests.ts
import * as logger4 from "firebase-functions/logger";
import { FieldValue } from "firebase-admin/firestore";

// src/common.ts
import { createHash } from "node:crypto";

// src/validate.ts
var NAME = /^[^<>{}\\]{1,60}$/u;
var PHONE = /^[0-9+() .-]{4,32}$/u;
var DATE = /^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/u;
var EMAIL = /^[^@ \t\n\x0B\f\r]{1,64}@[^@ \t\n\x0B\f\r]{1,190}\.[^@ \t\n\x0B\f\r]{2,}$/u;
var UID = /^[A-Za-z0-9]{6,128}$/u;
var CODE_HASH = /^[0-9a-f]{64}$/u;
var LINK_CODE = /^[A-Za-z0-9_-]{32,64}$/u;
var GUEST_KEY = /^[0-9a-f]{16}$/u;
var LANGS = /* @__PURE__ */ new Set(["fr", "en", "es", "de", "it"]);
var TYPES = [
  "PROFILE",
  "QR_EMAIL",
  "CLAIM",
  "GOOGLE_LINK",
  "GOOGLE_UNLINK",
  "GUEST_PROFILE",
  "GUEST_QR_EMAIL",
  "TEMP_GUEST_NAME",
  "CERTIFICATE_EMAIL"
];
var CERTIFICATE_FIELDS = /* @__PURE__ */ new Set([
  "uid",
  "type",
  "status",
  "requestedAt",
  "email",
  "from",
  "to",
  "jobTypes",
  "processingAt",
  "processingDevice",
  "processedAt",
  "reason"
]);
var MAX_CERT_TYPES = 50;
var MAX_CERT_TYPE_NAME = 80;
var CLAIM_TYPES = ["ADMIN_MATCH", "LINK_CODE"];
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
  const holderType = data.holderType === "GUEST" ? "GUEST" : "VOLUNTEER";
  const guest = holderType === "GUEST";
  const dateOfBirth = guest ? "" : str(data.dateOfBirth);
  if (dateOfBirth === void 0 || !guest && !DATE.test(dateOfBirth)) return null;
  const gender = guest ? "PREFER_NOT_TO_DISCLOSE" : String(data.gender);
  if (!GENDERS.has(gender)) return null;
  if (data.consent !== true) return null;
  const photoPath = str(data.photoPath) ?? "";
  const ownPhoto = `orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg`;
  const lang2 = str(data.lang);
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
    lang: lang2 !== void 0 && LANGS.has(lang2) ? lang2 : "fr",
    holderType,
    venueName: guest ? kotlinTrim(str(data.venueName) ?? "") : ""
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
  if (type === "PROFILE" || type === "GUEST_PROFILE") {
    const firstName = cleanName(data.firstName);
    const lastName = cleanName(data.lastName);
    const phone = cleanPhone(data.phone);
    if (firstName === null || lastName === null || phone === null) return null;
    return { requestId, uid, type, status, email, firstName, lastName, phone, photo: data.photo === true };
  }
  if (type === "TEMP_GUEST_NAME") {
    const guestKey = str(data.guestKey);
    if (guestKey === void 0 || !GUEST_KEY.test(guestKey)) return null;
    const optional = (v) => kotlinTrim(str(v) ?? "") === "" ? "" : cleanName(v);
    const firstName = optional(data.firstName);
    const lastName = optional(data.lastName);
    if (firstName === null || lastName === null || firstName === "" && lastName === "") return null;
    return { requestId, uid, type, status, email, firstName, lastName, phone: "", photo: false, guestKey };
  }
  if (type === "CERTIFICATE_EMAIL") {
    if (Object.keys(data).some((k) => !CERTIFICATE_FIELDS.has(k))) return null;
    const from = str(data.from);
    const to = str(data.to);
    if (from === void 0 || to === void 0 || !DATE.test(from) || !DATE.test(to) || from > to) return null;
    const raw = data.jobTypes;
    if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_CERT_TYPES) return null;
    const jobTypes = [];
    for (const name of raw) {
      if (typeof name !== "string") return null;
      const trimmed = kotlinTrim(name);
      if (trimmed.length < 1 || trimmed.length > MAX_CERT_TYPE_NAME) return null;
      jobTypes.push(trimmed);
    }
    return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "", photo: false, from, to, jobTypes };
  }
  if (type === "GOOGLE_LINK") {
    const codeHash = str(data.codeHash);
    if (codeHash === void 0 || !CODE_HASH.test(codeHash)) return null;
    return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "", photo: false, codeHash };
  }
  return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "", photo: false };
}
function parseGoogleClaim(googleUid, data) {
  if (!UID.test(googleUid)) return null;
  const typeName = String(data.type);
  if (!CLAIM_TYPES.includes(typeName)) return null;
  const type = typeName;
  const email = cleanEmail(data.email);
  if (email === null) return null;
  const status = data.status === null || data.status === void 0 ? "" : String(data.status);
  let code2 = "";
  if (type === "LINK_CODE") {
    const raw = str(data.code);
    if (raw === void 0 || !LINK_CODE.test(raw)) return null;
    code2 = raw;
  }
  return { googleUid, type, status, email, code: code2 };
}

// src/common.ts
var SERVER_VERSION = "1.0.0";
var STATUS_PROCESSING = "PROCESSING";
var PROJECTION_SCHEMA = 2;
var GUEST_PROJECTION_SCHEMA = 1;
var MAX_LABEL = 80;
var sha256 = (text) => createHash("sha256").update(text, "utf8").digest("hex");
async function portalEnabled(db, orgId) {
  const snap = await db.doc(`orgs/${orgId}/institutionSettings/volunteer_portal_enabled`).get();
  const value = snap.exists ? snap.get("value") : void 0;
  return value === "true" || value === true;
}
async function guestPortalEnabled(db, orgId) {
  if (!await portalEnabled(db, orgId)) return false;
  return await institutionSetting(db, orgId, "guest_portal_enabled") === "true";
}
async function temporaryGuestPortalEnabled(db, orgId) {
  return await institutionSetting(db, orgId, "temp_guest_portal_enabled") === "true";
}
var stringOf = (v) => v === null || v === void 0 ? "" : typeof v === "string" ? v : String(v);
function boolOf(v) {
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v.toLowerCase() === "true";
  if (typeof v === "number") return Number.isFinite(v) && Math.trunc(v) !== 0;
  return false;
}
var isBlankValue = (v) => kotlinTrim(stringOf(v)) === "";
var isPermanentGuest = (d) => !boolOf(d.get("isTemporaryGuest")) && !boolOf(d.get("isVolunteerBenefit"));
function guestOwnerOf(docs, uid) {
  let best = null;
  let bestAt = -Infinity;
  for (const d of docs) {
    if (!isPermanentGuest(d) || !boolOf(d.get("portalEnabled")) || stringOf(d.get("portalUid")) !== uid) continue;
    const at = millisOf(d.get("lastModified")) ?? 0;
    if (best === null || at > bestAt) {
      best = d;
      bestAt = at;
    }
  }
  return best;
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
async function claim(ref, isClaimable5) {
  return ref.firestore.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const data = snap.data();
    if (!isClaimable5(data)) return null;
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
    olderShifts: 0,
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
function minimalGuestProjection(g, now = Date.now()) {
  const hasPhoto = g.photoVersion !== "";
  const payload = JSON.stringify({
    firstName: g.firstName.trim(),
    lastName: g.lastName.trim(),
    email: g.email.trim(),
    phone: g.phone.trim(),
    hasPhoto,
    photoVersion: hasPhoto ? g.photoVersion : "",
    venueName: g.venueName.trim().slice(0, MAX_LABEL),
    invitations: 0,
    barDiscountPercent: 0,
    wallet: { balance: 0, currency: g.currency.trim() || "CHF", transfers: [] },
    // A new guest is never an admin, so only a missing e-mail forbids the QR mail.
    qrEmailAllowed: g.email.trim() !== "",
    mustSetPassword: false,
    association: g.association.trim().slice(0, MAX_LABEL),
    googleEmail: "",
    googleLinkedBy: "",
    googleLinkAvailable: g.googleLinkAllowed
  });
  return { schema: GUEST_PROJECTION_SCHEMA, payload, contentHash: sha256(payload), updatedAt: now };
}
function patchedProjection(data, fields, now) {
  if (data === void 0 || typeof data.payload !== "string") return null;
  let payload;
  try {
    payload = JSON.parse(data.payload);
  } catch {
    return null;
  }
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return null;
  const text = JSON.stringify({ ...payload, ...fields });
  return { payload: text, contentHash: sha256(text), updatedAt: now };
}

// src/google.ts
import * as logger from "firebase-functions/logger";
var LINKED_BY_ADMIN = "ADMIN";
var LINKED_BY_VOLUNTEER = "VOLUNTEER";
var LINK_CODE_VALIDITY_MS = 10 * 60 * 1e3;
var STATUS_PENDING = "PENDING";
var DONE = { status: "DONE", reason: "" };
var rejected = (reason) => ({ status: "REJECTED", reason });
var isClaimable = (d) => d.status === STATUS_PENDING;
var isBlank = (s) => kotlinTrim(s) === "";
function holderOf(kind, id, d) {
  return {
    kind,
    id,
    portalEnabled: boolOf(d.get("portalEnabled")),
    portalUid: stringOf(d.get("portalUid")),
    portalGoogleEmail: stringOf(d.get("portalGoogleEmail")),
    portalGoogleUid: stringOf(d.get("portalGoogleUid")),
    portalGoogleLinkedBy: stringOf(d.get("portalGoogleLinkedBy"))
  };
}
var CONSUMER_DOMAINS = ["gmail.com", "googlemail.com"];
function parseDomains(raw) {
  const out = [];
  for (const part of raw.split(/[,;\n\r]/)) {
    let domain = kotlinTrim(part).toLowerCase();
    if (domain.startsWith("@")) domain = domain.slice(1);
    domain = kotlinTrim(domain).replace(/\.+$/, "");
    if (domain !== "" && domain.includes(".") && !out.includes(domain) && !CONSUMER_DOMAINS.includes(domain)) {
      out.push(domain);
    }
  }
  return out;
}
function accountAllowed(email, domains, anyAccount) {
  if (!anyAccount) return domainAllowed(email, domains);
  const clean2 = kotlinTrim(email);
  const at = clean2.lastIndexOf("@");
  return at >= 0 && clean2.slice(at + 1) !== "";
}
function domainAllowed(email, domains) {
  const clean2 = kotlinTrim(email).toLowerCase();
  const at = clean2.lastIndexOf("@");
  const domain = at < 0 ? "" : clean2.slice(at + 1);
  return domain !== "" && !CONSUMER_DOMAINS.includes(domain) && domains.some((d) => kotlinTrim(d).toLowerCase() === domain);
}
function sameAddress(a, b) {
  const left = kotlinTrim(a).toLowerCase();
  return left !== "" && left === kotlinTrim(b).toLowerCase();
}
function isAliasValid(googleUid, aliasTarget, holders) {
  return aliasTarget !== null && !isBlank(aliasTarget) && holders.some((v) => v.portalUid === aliasTarget && v.portalGoogleUid === googleUid);
}
var hasPortal = (v) => v.portalEnabled && !isBlank(v.portalUid);
var sameHolder = (a, b) => a.kind === b.kind && a.id === b.id;
function accountOf(uid, holders) {
  return isBlank(uid) ? [] : holders.filter((h) => h.portalEnabled && h.portalUid === uid);
}
function primary(account) {
  return account.find((h) => h.kind === "VOLUNTEER") ?? account[0];
}
function adminMatch(c, holders, domains, aliasTarget, anyAccount = false) {
  if (!accountAllowed(c.email, domains, anyAccount)) return { kind: "REJECT", reason: "DOMAIN" };
  if (isAliasValid(c.googleUid, aliasTarget, holders)) return { kind: "ALREADY_LINKED" };
  const candidates = holders.filter((v) => hasPortal(v) && sameAddress(v.portalGoogleEmail, c.email));
  const byUid = /* @__PURE__ */ new Map();
  for (const h of candidates) byUid.set(h.portalUid, [...byUid.get(h.portalUid) ?? [], h]);
  const people = [...byUid.values()].map(primary);
  if (people.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  if (people.length > 1) return { kind: "REJECT", reason: "AMBIGUOUS" };
  const match = people[0];
  const linkedBy = match.portalGoogleLinkedBy === LINKED_BY_VOLUNTEER ? LINKED_BY_VOLUNTEER : LINKED_BY_ADMIN;
  return { kind: "LINK", holder: match, linkedBy };
}
function linkCode(c, request, requestedAtMillis, now, holders, domains, aliasTarget, anyAccount = false) {
  const codeOk = request !== null && request.type === "GOOGLE_LINK" && request.status === STATUS_PENDING && c.code !== "" && request.codeHash === sha256(c.code) && requestedAtMillis !== null && now - requestedAtMillis < LINK_CODE_VALIDITY_MS;
  if (!codeOk) return { kind: "REJECT", reason: "CODE" };
  const account = accountOf(request.uid, holders);
  if (account.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  const owner = primary(account);
  if (aliasTarget === owner.portalUid && account.some((h) => h.portalGoogleUid === c.googleUid)) {
    return { kind: "ALREADY_LINKED" };
  }
  if (account.some((h) => !isBlank(h.portalGoogleEmail))) return { kind: "REJECT", reason: "ALREADY_LINKED" };
  if (!accountAllowed(c.email, domains, anyAccount)) return { kind: "REJECT", reason: "DOMAIN" };
  const others = holders.filter((h) => !account.some((a) => sameHolder(a, h)));
  if (others.some((v) => v.portalGoogleUid === c.googleUid)) return { kind: "REJECT", reason: "TAKEN" };
  if (others.some((v) => sameAddress(v.portalGoogleEmail, c.email))) return { kind: "REJECT", reason: "TAKEN" };
  return { kind: "LINK", holder: owner, linkedBy: LINKED_BY_VOLUNTEER };
}
function canUnlink(owner) {
  if (owner === null) return "NO_ACCESS";
  if (owner.portalGoogleLinkedBy === LINKED_BY_ADMIN) return "ADMIN_LINK";
  return null;
}
var errorCode = (e) => {
  const code2 = e?.code;
  return code2 === void 0 ? "" : String(code2);
};
var COLLECTION = { VOLUNTEER: "volunteers", GUEST: "guests" };
var PAGE = { VOLUNTEER: "volunteerPortal", GUEST: "guestPortal" };
var holderRef = (db, orgId, h) => db.doc(`orgs/${orgId}/${COLLECTION[h.kind]}/${h.id}`);
var aliasRef = (db, orgId, googleUid) => db.doc(`orgs/${orgId}/portalGoogleAccounts/${googleUid}`);
var pageRef = (db, orgId, kind, portalUid) => db.doc(`orgs/${orgId}/${PAGE[kind]}/${portalUid}`);
var GOOGLE_FIELDS = ["portalEnabled", "portalUid", "portalGoogleEmail", "portalGoogleUid", "portalGoogleLinkedBy"];
var GUEST_FIELDS = [...GOOGLE_FIELDS, "isTemporaryGuest", "isVolunteerBenefit", "lastModified"];
async function loadHolders(db, orgId) {
  const [volunteers2, guestSpace] = await Promise.all([
    db.collection(`orgs/${orgId}/volunteers`).select(...GOOGLE_FIELDS).get(),
    guestPortalEnabled(db, orgId)
  ]);
  const holders = volunteers2.docs.map((d) => holderOf("VOLUNTEER", d.id, d));
  if (!guestSpace) return holders;
  const guests2 = await db.collection(`orgs/${orgId}/guests`).select(...GUEST_FIELDS).get();
  return [...holders, ...guestsByRecency(guests2.docs.filter(isPermanentGuest)).map((d) => holderOf("GUEST", d.id, d))];
}
function guestsByRecency(docs) {
  return docs.map((d, i) => ({ d, i, at: millisOf(d.get("lastModified")) ?? 0 })).sort((a, b) => b.at - a.at || a.i - b.i).map((x) => x.d);
}
async function googleOn(db, orgId) {
  return await institutionSetting(db, orgId, "volunteer_portal_google_enabled") === "true";
}
async function anyAccountOn(db, orgId) {
  return await institutionSetting(db, orgId, "volunteer_portal_google_any_account") === "true";
}
async function googleLinkAllowed(db, orgId) {
  const [on, any, domains] = await Promise.all([
    googleOn(db, orgId),
    anyAccountOn(db, orgId),
    institutionSetting(db, orgId, "volunteer_portal_google_domains")
  ]);
  return on && (any || parseDomains(domains).length > 0);
}
function googleProjectionFields(portalGoogleEmail, linkedBy, allowed) {
  const googleEmail = kotlinTrim(portalGoogleEmail);
  return {
    googleEmail,
    googleLinkedBy: kotlinTrim(linkedBy),
    googleLinkAvailable: allowed && googleEmail === ""
  };
}
async function onGoogleClaimDocument(db, orgId, googleUid, data) {
  if (data === void 0 || !isClaimable(data)) return;
  let result;
  try {
    if (!await portalEnabled(db, orgId) || !await googleOn(db, orgId)) {
      logger.info("google claim skipped", { orgId, reason: "OPTION_OFF" });
      return;
    }
    const ref = db.doc(`orgs/${orgId}/portalGoogleClaims/${googleUid}`);
    const claimed = await claim(ref, isClaimable);
    if (claimed === null) return;
    result = await processClaim(db, orgId, googleUid, claimed);
    await completeClaim(ref, result);
  } catch (e) {
    logger.error("google claim failed", { orgId, reason: "FAILED", code: errorCode(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger.error("server status not written", { orgId, code: errorCode(e) });
  }
  logger.info("google claim processed", { orgId, status: result.status, reason: result.reason });
}
async function completeClaim(ref, result) {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now(), code: "" }, { merge: true });
}
async function processClaim(db, orgId, googleUid, claimed) {
  const c = parseGoogleClaim(googleUid, claimed);
  if (c === null) return rejected("INVALID");
  const now = Date.now();
  const [holders, domainSetting, anyAccount, aliasSnap] = await Promise.all([
    loadHolders(db, orgId),
    institutionSetting(db, orgId, "volunteer_portal_google_domains"),
    anyAccountOn(db, orgId),
    aliasRef(db, orgId, googleUid).get()
  ]);
  const domains = parseDomains(domainSetting);
  const rawTarget = aliasSnap.exists ? aliasSnap.get("volunteerUid") : void 0;
  let aliasTarget = typeof rawTarget === "string" ? rawTarget : null;
  if (aliasTarget !== null && !isAliasValid(googleUid, aliasTarget, holders)) {
    await aliasRef(db, orgId, googleUid).delete();
    aliasTarget = null;
  }
  let linkRequest = null;
  let decision;
  if (c.type === "ADMIN_MATCH") {
    decision = adminMatch(c, holders, domains, aliasTarget, anyAccount);
  } else {
    const found = await db.collection(`orgs/${orgId}/portalRequests`).where("type", "==", "GOOGLE_LINK").where("codeHash", "==", sha256(c.code)).limit(1).get();
    const doc = found.docs[0];
    linkRequest = doc?.ref ?? null;
    const request = doc !== void 0 ? parseRequest(doc.id, doc.data()) : null;
    const requestedAt = doc !== void 0 ? millisOf(doc.get("requestedAt")) : null;
    decision = linkCode(c, request, requestedAt, now, holders, domains, aliasTarget, anyAccount);
  }
  let result;
  switch (decision.kind) {
    case "REJECT":
      return rejected(decision.reason);
    case "ALREADY_LINKED":
      result = DONE;
      break;
    case "LINK":
      result = await writeLink(db, orgId, c, accountOf(decision.holder.portalUid, holders), decision.linkedBy, aliasTarget);
      break;
  }
  if (result.status === "DONE" && linkRequest !== null) {
    await linkRequest.set({ status: "DONE", reason: "", processedAt: Date.now() }, { merge: true });
  }
  return result;
}
var sameGoogleFields = (a, b) => a.portalEnabled === b.portalEnabled && a.portalUid === b.portalUid && a.portalGoogleEmail === b.portalGoogleEmail && a.portalGoogleUid === b.portalGoogleUid && a.portalGoogleLinkedBy === b.portalGoogleLinkedBy;
var linkedAddress = (h, email) => isBlank(h.portalGoogleEmail) ? email : kotlinTrim(h.portalGoogleEmail);
async function writeLink(db, orgId, c, account, linkedBy, aliasTarget) {
  try {
    const allowed = await googleLinkAllowed(db, orgId);
    const portalUid = account[0].portalUid;
    const previousUids = [...new Set(account.map((h) => h.portalGoogleUid))].filter(
      (u) => !isBlank(u) && u !== c.googleUid
    );
    return await db.runTransaction(async (tx) => {
      const alias = aliasRef(db, orgId, c.googleUid);
      const kinds = [...new Set(account.map((h) => h.kind))];
      const [aliasSnap, snaps, pages, previous] = await Promise.all([
        tx.get(alias),
        Promise.all(account.map((h) => tx.get(holderRef(db, orgId, h)))),
        Promise.all(kinds.map((k) => tx.get(pageRef(db, orgId, k, portalUid)))),
        Promise.all(previousUids.map((u) => tx.get(aliasRef(db, orgId, u))))
      ]);
      const changed = snaps.some((snap, i) => !snap.exists || !sameGoogleFields(holderOf(account[i].kind, snap.id, snap), account[i]));
      if (changed) return rejected("SAVE_FAILED");
      const target = aliasSnap.exists ? aliasSnap.get("volunteerUid") : void 0;
      if ((typeof target === "string" ? target : null) !== aliasTarget) return rejected("SAVE_FAILED");
      const now = Date.now();
      for (const p of previous) if (p.exists && p.get("volunteerUid") === portalUid) tx.delete(p.ref);
      tx.set(alias, { volunteerUid: portalUid, email: c.email, linkedBy, linkedAt: now });
      snaps.forEach((snap, i) => {
        tx.update(snap.ref, {
          portalGoogleEmail: linkedAddress(account[i], c.email),
          portalGoogleUid: c.googleUid,
          portalGoogleLinkedBy: linkedBy,
          lastModified: Math.max(now, (millisOf(snap.get("lastModified")) ?? 0) + 1)
        });
      });
      kinds.forEach((kind, i) => {
        const shown = account.find((h) => h.kind === kind);
        const patch = patchedProjection(pages[i].data(), googleProjectionFields(linkedAddress(shown, c.email), linkedBy, allowed), now);
        if (patch !== null) tx.update(pages[i].ref, patch);
      });
      return DONE;
    });
  } catch (e) {
    logger.error("google link not saved", { orgId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}
async function unlinkGoogle(db, orgId, requestId, uid) {
  try {
    const [allowed, guestSpace] = await Promise.all([googleLinkAllowed(db, orgId), guestPortalEnabled(db, orgId)]);
    return await db.runTransaction(async (tx) => {
      const [linkedVolunteers, linkedGuests] = await Promise.all([
        tx.get(db.collection(`orgs/${orgId}/volunteers`).where("portalUid", "==", uid)),
        tx.get(db.collection(`orgs/${orgId}/guests`).where("portalUid", "==", uid))
      ]);
      const permanentGuests = guestsByRecency(linkedGuests.docs.filter(isPermanentGuest));
      const holders = [
        ...linkedVolunteers.docs.map((d) => holderOf("VOLUNTEER", d.id, d)),
        ...guestSpace ? permanentGuests.map((d) => holderOf("GUEST", d.id, d)) : []
      ];
      const account = accountOf(uid, holders);
      const accountGuests = account.length === 0 ? [] : permanentGuests;
      const everyone = [
        ...account,
        ...accountGuests.map((d) => holderOf("GUEST", d.id, d)).filter((g) => !account.some((a) => sameHolder(a, g)))
      ];
      const decider = everyone.find((h) => h.portalGoogleLinkedBy === LINKED_BY_ADMIN) ?? (account.length > 0 ? primary(account) : null);
      const reason = canUnlink(decider);
      if (reason !== null) return reason;
      const docs = [
        ...linkedVolunteers.docs.filter((d) => account.some((a) => a.kind === "VOLUNTEER" && a.id === d.id)),
        ...accountGuests
      ];
      const pageKinds = [...new Set(everyone.map((h) => h.kind))];
      const pages = await Promise.all(pageKinds.map((k) => tx.get(pageRef(db, orgId, k, uid))));
      const now = Date.now();
      for (const googleUid of new Set(everyone.map((h) => h.portalGoogleUid))) {
        if (!isBlank(googleUid)) tx.delete(aliasRef(db, orgId, googleUid));
      }
      for (const doc of docs) {
        tx.update(doc.ref, {
          portalGoogleEmail: "",
          portalGoogleUid: "",
          portalGoogleLinkedBy: "",
          lastModified: Math.max(now, (millisOf(doc.get("lastModified")) ?? 0) + 1)
        });
      }
      pages.forEach((page) => {
        const patch = patchedProjection(page.data(), googleProjectionFields("", "", allowed), now);
        if (patch !== null) tx.update(page.ref, patch);
      });
      return "";
    });
  } catch (e) {
    logger.error("google unlink not saved", { orgId, requestId, code: errorCode(e) });
    return "SAVE_FAILED";
  }
}

// src/certificate.ts
var CATEGORIES = ["MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT", "GRAPHIC_ASSOCIATION", "OTHER", "MEETING"];
function categoryOf(config) {
  if (!config) return "OTHER";
  if (config.isOrionJob === true) return "MANDATE";
  switch (config.novaJobType) {
    case "MEETING":
      return "MEETING";
    case "PHOTOGRAPHER_VIDEOGRAPHER":
      return "PHOTO";
    case "GRAPHIC_DESIGNER_EVENT":
      return "GRAPHIC_EVENT";
    case "GRAPHIC_DESIGNER_ASSOCIATION":
      return "GRAPHIC_ASSOCIATION";
    default:
      return config.isShiftJob === false ? "OTHER" : "SHIFT";
  }
}
var ALIGNS = ["LEFT", "CENTER", "RIGHT", "JUSTIFY"];
var SIZES = ["SMALL", "NORMAL", "LARGE", "TITLE"];
var isObj = (v) => typeof v === "object" && v !== null && !Array.isArray(v);
var Unreadable = class extends Error {
};
function field(o, key, fallback, ok) {
  const v = o[key];
  if (v === void 0) return fallback;
  if (!ok(v)) throw new Unreadable(key);
  return v;
}
var isStr = (v) => typeof v === "string";
var isBool = (v) => typeof v === "boolean";
var isInt = (v) => typeof v === "number" && Number.isInteger(v);
function objOf(v) {
  if (!isObj(v)) throw new Unreadable("object");
  return v;
}
function listOf(o, key, read) {
  const v = o[key];
  if (v === void 0) return [];
  if (!Array.isArray(v)) throw new Unreadable(key);
  return v.map(read);
}
function readRun(raw) {
  const o = objOf(raw);
  return {
    text: field(o, "text", "", isStr),
    var: field(o, "var", "", isStr),
    gender: listOf(o, "gender", (g) => {
      if (typeof g !== "string") throw new Unreadable("gender");
      return g;
    }),
    b: field(o, "b", false, isBool),
    i: field(o, "i", false, isBool),
    u: field(o, "u", false, isBool)
  };
}
function readParagraph(raw) {
  const o = objOf(raw);
  return {
    align: field(o, "align", "LEFT", (v) => ALIGNS.includes(v)),
    size: field(o, "size", "NORMAL", (v) => SIZES.includes(v)),
    bullet: field(o, "bullet", false, isBool),
    runs: listOf(o, "runs", readRun)
  };
}
function readBlock(raw) {
  const o = objOf(raw);
  return {
    one: listOf(o, "one", readParagraph),
    many: listOf(o, "many", readParagraph),
    manyOneRole: listOf(o, "manyOneRole", readParagraph),
    line: listOf(o, "line", readParagraph)
  };
}
function strings(raw, keys) {
  const o = raw === void 0 ? {} : objOf(raw);
  const out = {};
  for (const k of keys) out[k] = field(o, k, "", isStr);
  return out;
}
function parseTemplate(raw) {
  try {
    const value = typeof raw === "string" ? JSON.parse(raw) : raw;
    const o = objOf(value);
    const blocks = {};
    if (o.blocks !== void 0) {
      for (const [key, block] of Object.entries(objOf(o.blocks))) blocks[key] = readBlock(block);
    }
    return {
      version: field(o, "version", 1, isInt),
      language: field(o, "language", "fr", isStr),
      place: field(o, "place", "", isStr),
      signer: strings(o.signer, ["name", "title", "imageBase64"]),
      mail: strings(o.mail, ["fromName", "fromAddress", "replyTo", "subject", "body"]),
      blocks
    };
  } catch {
    return null;
  }
}
var MONTHS = {
  fr: ["janvier", "f\xE9vrier", "mars", "avril", "mai", "juin", "juillet", "ao\xFBt", "septembre", "octobre", "novembre", "d\xE9cembre"],
  en: ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"],
  de: ["Januar", "Februar", "M\xE4rz", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"],
  it: ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"],
  es: ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"]
};
var zurich = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Europe/Zurich",
  year: "numeric",
  month: "2-digit",
  day: "2-digit"
});
function dayOf(millis) {
  const parts = zurich.formatToParts(new Date(millis));
  const get = (type) => Number(parts.find((p) => p.type === type)?.value);
  return get("year") * 1e4 + get("month") * 100 + get("day");
}
var lang = (l) => Object.prototype.hasOwnProperty.call(MONTHS, l) ? l : "en";
var yearOf = (day) => Math.trunc(day / 1e4);
var monthIndex = (day) => Math.trunc(day / 100) % 100;
var monthName = (day, l) => MONTHS[l][monthIndex(day) - 1];
var withYear = (l, month, year) => l === "es" ? `${month} de ${year}` : `${month} ${year}`;
function longDate(day, language) {
  const l = lang(language);
  const d = day % 100;
  const m = monthName(day, l);
  const y = yearOf(day);
  switch (l) {
    case "fr":
      return `${d === 1 ? "1er" : d} ${m} ${y}`;
    case "de":
      return `${d}. ${m} ${y}`;
    case "es":
      return `${d} de ${m} de ${y}`;
    case "it":
      return `${d === 1 ? "1\xB0" : d} ${m} ${y}`;
    default:
      return `${d} ${m} ${y}`;
  }
}
function monthYear(day, language) {
  const l = lang(language);
  return withYear(l, monthName(day, l), yearOf(day));
}
function period(firstDay, lastDay, language) {
  const l = lang(language);
  const sameYear = yearOf(firstDay) === yearOf(lastDay);
  const sameMonth = sameYear && monthIndex(firstDay) === monthIndex(lastDay);
  const first = monthName(firstDay, l);
  const last = monthName(lastDay, l);
  if (sameMonth) {
    const my = withYear(l, first, yearOf(firstDay));
    switch (l) {
      case "fr":
      case "es":
        return `en ${my}`;
      case "en":
        return `in ${my}`;
      case "de":
        return `im ${my}`;
      default:
        return `a ${my}`;
    }
  }
  let between;
  let and;
  switch (l) {
    case "fr":
      [between, and] = ["entre", "et"];
      break;
    case "en":
      [between, and] = ["between", "and"];
      break;
    case "de":
      [between, and] = ["zwischen", "und"];
      break;
    case "it":
      [between, and] = ["tra", "e"];
      break;
    default:
      [between, and] = ["entre", "y"];
  }
  return sameYear ? `${between} ${first} ${and} ${withYear(l, last, yearOf(lastDay))}` : `${between} ${withYear(l, first, yearOf(firstDay))} ${and} ${withYear(l, last, yearOf(lastDay))}`;
}
var LANGUAGES = ["fr", "en", "de", "it", "es"];
var TEXTS = {
  fr: {
    title: "Attestation de b\xE9n\xE9volat",
    introOne: "{association} atteste que {firstName} {lastName} s'est [engag\xE9|engag\xE9e|engag\xE9x] b\xE9n\xE9volement au sein de l'association.",
    introMany: "{association} atteste que {firstName} {lastName} s'est [engag\xE9|engag\xE9e|engag\xE9x] b\xE9n\xE9volement au sein de l'association {period}.",
    mandate: "{firstName} a exerc\xE9 le mandat de {role} {period}.",
    shiftOne: "Le {date}, {firstName} a particip\xE9 b\xE9n\xE9volement \xE0 un \xE9v\xE9nement organis\xE9 par {association} en tant que {role}.",
    shiftMany: "{firstName} a particip\xE9 b\xE9n\xE9volement \xE0 {count} \xE9v\xE9nements organis\xE9s par {association} {period}, dans les r\xF4les suivants :",
    shiftManyOneRole: "{firstName} a particip\xE9 b\xE9n\xE9volement \xE0 {count} \xE9v\xE9nements organis\xE9s par {association} {period}, en tant que {role}.",
    photoOne: "Le {date}, {firstName} a assur\xE9 la couverture photo et vid\xE9o d'un \xE9v\xE9nement organis\xE9 par {association}.",
    photoMany: "{firstName} a assur\xE9 la couverture photo et vid\xE9o de {count} \xE9v\xE9nements organis\xE9s par {association} {period}.",
    graphicEventOne: "Le {date}, {firstName} a r\xE9alis\xE9 les visuels d'un \xE9v\xE9nement organis\xE9 par {association}.",
    graphicEventMany: "{firstName} a r\xE9alis\xE9 les visuels de {count} \xE9v\xE9nements organis\xE9s par {association} {period}.",
    graphicAssociation: "{firstName} a contribu\xE9 \xE0 l'identit\xE9 graphique de l'association {period}.",
    otherOne: "Le {date}, {firstName} a men\xE9 une mission pour {association} en tant que {role}.",
    otherMany: "{firstName} a men\xE9 {count} missions pour {association} {period}, dans les r\xF4les suivants :",
    otherManyOneRole: "{firstName} a men\xE9 {count} missions pour {association} {period}, en tant que {role}.",
    meetingOne: "Le {date}, {firstName} a particip\xE9 \xE0 une r\xE9union organis\xE9e par {association}.",
    meetingMany: "{firstName} a particip\xE9 \xE0 {count} r\xE9unions organis\xE9es par {association} {period}.",
    closing: "Nous remercions chaleureusement {firstName} pour son engagement.",
    dateLine: "Fait \xE0 {place}, le {today}",
    mailSubject: "Ton attestation de b\xE9n\xE9volat",
    mailBody: "Bonjour {firstName}, voici ton attestation de b\xE9n\xE9volat pour {association}. Elle est jointe \xE0 cet e-mail."
  },
  en: {
    title: "Volunteer certificate",
    introOne: "{association} certifies that {firstName} {lastName} has volunteered with the association.",
    introMany: "{association} certifies that {firstName} {lastName} has volunteered with the association {period}.",
    mandate: "{firstName} held the role of {role} {period}.",
    shiftOne: "On {date}, {firstName} volunteered at an event organised by {association} as {role}.",
    shiftMany: "{firstName} volunteered at {count} events organised by {association} {period}, in the following roles:",
    shiftManyOneRole: "{firstName} volunteered at {count} events organised by {association} {period}, as {role}.",
    photoOne: "On {date}, {firstName} provided photo and video coverage of an event organised by {association}.",
    photoMany: "{firstName} provided photo and video coverage of {count} events organised by {association} {period}.",
    graphicEventOne: "On {date}, {firstName} designed the visuals for an event organised by {association}.",
    graphicEventMany: "{firstName} designed the visuals for {count} events organised by {association} {period}.",
    graphicAssociation: "{firstName} contributed to the association's visual identity {period}.",
    otherOne: "On {date}, {firstName} carried out a task for {association} as {role}.",
    otherMany: "{firstName} carried out {count} tasks for {association} {period}, in the following roles:",
    otherManyOneRole: "{firstName} carried out {count} tasks for {association} {period}, as {role}.",
    meetingOne: "On {date}, {firstName} took part in a meeting organised by {association}.",
    meetingMany: "{firstName} took part in {count} meetings organised by {association} {period}.",
    closing: "We warmly thank {firstName} for their commitment.",
    dateLine: "Issued in {place}, on {today}",
    mailSubject: "Your volunteer certificate",
    mailBody: "Hello {firstName}, here is your volunteer certificate for {association}. It is attached to this e-mail."
  },
  de: {
    title: "Best\xE4tigung \xFCber ehrenamtliches Engagement",
    introOne: "{association} best\xE4tigt, dass sich {firstName} {lastName} ehrenamtlich im Verein engagiert hat.",
    introMany: "{association} best\xE4tigt, dass sich {firstName} {lastName} {period} ehrenamtlich im Verein engagiert hat.",
    mandate: "{firstName} hat {period} das Amt als {role} ausge\xFCbt.",
    shiftOne: "Am {date} hat sich {firstName} ehrenamtlich bei einer Veranstaltung von {association} als {role} engagiert.",
    shiftMany: "{firstName} hat sich {period} ehrenamtlich bei {count} Veranstaltungen von {association} engagiert, und zwar in folgenden Funktionen:",
    shiftManyOneRole: "{firstName} hat sich {period} ehrenamtlich bei {count} Veranstaltungen von {association} engagiert, und zwar als {role}.",
    photoOne: "Am {date} hat {firstName} eine Veranstaltung von {association} fotografisch und filmisch begleitet.",
    photoMany: "{firstName} hat {period} {count} Veranstaltungen von {association} fotografisch und filmisch begleitet.",
    graphicEventOne: "Am {date} hat {firstName} die Grafiken f\xFCr eine Veranstaltung von {association} gestaltet.",
    graphicEventMany: "{firstName} hat {period} die Grafiken f\xFCr {count} Veranstaltungen von {association} gestaltet.",
    graphicAssociation: "{firstName} hat {period} zum grafischen Auftritt von {association} beigetragen.",
    otherOne: "Am {date} hat {firstName} f\xFCr {association} eine Aufgabe als {role} \xFCbernommen.",
    otherMany: "{firstName} hat {period} f\xFCr {association} {count} Aufgaben \xFCbernommen, und zwar in folgenden Funktionen:",
    otherManyOneRole: "{firstName} hat {period} f\xFCr {association} {count} Aufgaben \xFCbernommen, und zwar als {role}.",
    meetingOne: "Am {date} hat {firstName} an einer Sitzung von {association} teilgenommen.",
    meetingMany: "{firstName} hat {period} an {count} Sitzungen von {association} teilgenommen.",
    closing: "Wir danken {firstName} herzlich f\xFCr das Engagement.",
    dateLine: "Ausgestellt in {place}, am {today}",
    mailSubject: "Deine Best\xE4tigung \xFCber ehrenamtliches Engagement",
    mailBody: "Hallo {firstName}, hier ist deine Best\xE4tigung \xFCber dein ehrenamtliches Engagement bei {association}. Sie ist dieser E-Mail angeh\xE4ngt."
  },
  it: {
    title: "Attestazione di volontariato",
    introOne: "{association} attesta che {firstName} {lastName} ha svolto attivit\xE0 di volontariato nell'associazione.",
    introMany: "{association} attesta che {firstName} {lastName} ha svolto attivit\xE0 di volontariato nell'associazione {period}.",
    mandate: "{firstName} ha ricoperto l'incarico di {role} {period}.",
    shiftOne: "In data {date}, {firstName} ha svolto attivit\xE0 di volontariato a un evento organizzato da {association} come {role}.",
    shiftMany: "{firstName} ha svolto attivit\xE0 di volontariato a {count} eventi organizzati da {association} {period}, con i seguenti ruoli:",
    shiftManyOneRole: "{firstName} ha svolto attivit\xE0 di volontariato a {count} eventi organizzati da {association} {period}, come {role}.",
    photoOne: "In data {date}, {firstName} ha curato la copertura fotografica e video di un evento organizzato da {association}.",
    photoMany: "{firstName} ha curato la copertura fotografica e video di {count} eventi organizzati da {association} {period}.",
    graphicEventOne: "In data {date}, {firstName} ha realizzato la grafica di un evento organizzato da {association}.",
    graphicEventMany: "{firstName} ha realizzato la grafica di {count} eventi organizzati da {association} {period}.",
    graphicAssociation: "{firstName} ha contribuito all'identit\xE0 grafica dell'associazione {period}.",
    otherOne: "In data {date}, {firstName} ha svolto un incarico per {association} come {role}.",
    otherMany: "{firstName} ha svolto {count} incarichi per {association} {period}, con i seguenti ruoli:",
    otherManyOneRole: "{firstName} ha svolto {count} incarichi per {association} {period}, come {role}.",
    meetingOne: "In data {date}, {firstName} ha partecipato a una riunione organizzata da {association}.",
    meetingMany: "{firstName} ha partecipato a {count} riunioni organizzate da {association} {period}.",
    closing: "Ringraziamo calorosamente {firstName} per il suo impegno.",
    dateLine: "Fatto a {place}, il {today}",
    mailSubject: "La tua attestazione di volontariato",
    mailBody: "Ciao {firstName}, ecco la tua attestazione di volontariato per {association}. La trovi in allegato a questa e-mail."
  },
  es: {
    title: "Certificado de voluntariado",
    introOne: "{association} certifica que {firstName} {lastName} ha realizado tareas de voluntariado en la asociaci\xF3n.",
    introMany: "{association} certifica que {firstName} {lastName} ha realizado tareas de voluntariado en la asociaci\xF3n {period}.",
    mandate: "{firstName} ejerci\xF3 el cargo de {role} {period}.",
    shiftOne: "El {date}, {firstName} realiz\xF3 tareas de voluntariado en un evento organizado por {association}, en calidad de {role}.",
    shiftMany: "{firstName} realiz\xF3 tareas de voluntariado en {count} eventos organizados por {association} {period}, con las siguientes funciones:",
    shiftManyOneRole: "{firstName} realiz\xF3 tareas de voluntariado en {count} eventos organizados por {association} {period}, en calidad de {role}.",
    photoOne: "El {date}, {firstName} se encarg\xF3 de la cobertura fotogr\xE1fica y de v\xEDdeo de un evento organizado por {association}.",
    photoMany: "{firstName} se encarg\xF3 de la cobertura fotogr\xE1fica y de v\xEDdeo de {count} eventos organizados por {association} {period}.",
    graphicEventOne: "El {date}, {firstName} dise\xF1\xF3 los elementos gr\xE1ficos de un evento organizado por {association}.",
    graphicEventMany: "{firstName} dise\xF1\xF3 los elementos gr\xE1ficos de {count} eventos organizados por {association} {period}.",
    graphicAssociation: "{firstName} contribuy\xF3 a la identidad gr\xE1fica de la asociaci\xF3n {period}.",
    otherOne: "El {date}, {firstName} llev\xF3 a cabo una misi\xF3n para {association} como {role}.",
    otherMany: "{firstName} llev\xF3 a cabo {count} misiones para {association} {period}, con las siguientes funciones:",
    otherManyOneRole: "{firstName} llev\xF3 a cabo {count} misiones para {association} {period}, como {role}.",
    meetingOne: "El {date}, {firstName} particip\xF3 en una reuni\xF3n organizada por {association}.",
    meetingMany: "{firstName} particip\xF3 en {count} reuniones organizadas por {association} {period}.",
    closing: "Agradecemos sinceramente a {firstName} su compromiso.",
    dateLine: "Expedido en {place}, a {today}",
    mailSubject: "Tu certificado de voluntariado",
    mailBody: "Hola {firstName}, aqu\xED tienes tu certificado de voluntariado para {association}. Va adjunto a este correo."
  }
};
var PLACEHOLDER = /\{(\w+)\}|\[([^\]|]*)\|([^\]|]*)\|([^\]|]*)\]/g;
function runsOf(template, bold = false) {
  const out = [];
  const plain = (text) => ({ text, var: "", gender: [], b: bold, i: false, u: false });
  let last = 0;
  for (const m of template.matchAll(PLACEHOLDER)) {
    const start = m.index ?? 0;
    if (start > last) out.push(plain(template.substring(last, start)));
    if (m[1] !== void 0) {
      out.push({ text: "", var: m[1], gender: [], b: bold, i: false, u: false });
    } else {
      out.push({ text: "", var: "", gender: [m[2], m[3], m[4]], b: bold, i: false, u: false });
    }
    last = start + m[0].length;
  }
  if (last < template.length) out.push(plain(template.substring(last)));
  return out;
}
function para(template, align = "LEFT", size = "NORMAL", bold = false) {
  return { align, size, bullet: false, runs: runsOf(template, bold) };
}
var only = (variable) => {
  const paragraph = para(`{${variable}}`);
  return variable === "description" ? { ...paragraph, runs: paragraph.runs.map((r) => ({ ...r, i: true })) } : paragraph;
};
var emptyBlock = () => ({ one: [], many: [], manyOneRole: [], line: [] });
var fixed = (paragraph) => ({ ...emptyBlock(), one: [paragraph] });
var roleLine = () => [
  {
    align: "LEFT",
    size: "NORMAL",
    bullet: true,
    runs: [
      { text: "", var: "role", gender: [], b: true, i: false, u: false },
      { text: ": ", var: "", gender: [], b: false, i: false, u: false },
      { text: "", var: "description", gender: [], b: false, i: false, u: false }
    ]
  }
];
function defaultTemplate(language, associationName2) {
  const l = LANGUAGES.includes(language) ? language : "en";
  const t = TEXTS[l];
  const blocks = {
    header: fixed(para("{association}", "CENTER", "LARGE", true)),
    title: fixed(para(t.title, "CENTER", "TITLE", true)),
    intro: { ...emptyBlock(), one: [para(t.introOne)], many: [para(t.introMany)], manyOneRole: [para(t.introMany)] },
    MANDATE: { ...emptyBlock(), one: [para(t.mandate), only("description")] },
    SHIFT: {
      one: [para(t.shiftOne), only("description")],
      many: [para(t.shiftMany), only("roles")],
      manyOneRole: [para(t.shiftManyOneRole), only("description")],
      line: roleLine()
    },
    PHOTO: { ...emptyBlock(), one: [para(t.photoOne)], many: [para(t.photoMany)] },
    GRAPHIC_EVENT: { ...emptyBlock(), one: [para(t.graphicEventOne)], many: [para(t.graphicEventMany)] },
    GRAPHIC_ASSOCIATION: { ...emptyBlock(), one: [para(t.graphicAssociation)], many: [para(t.graphicAssociation)] },
    OTHER: {
      one: [para(t.otherOne), only("description")],
      many: [para(t.otherMany), only("roles")],
      manyOneRole: [para(t.otherManyOneRole), only("description")],
      line: roleLine()
    },
    MEETING: { ...emptyBlock(), one: [para(t.meetingOne)], many: [para(t.meetingMany)] },
    closing: fixed(para(t.closing)),
    dateLine: fixed(para(t.dateLine)),
    // Optional: one empty paragraph, dropped when rendering.
    footer: fixed({
      align: "CENTER",
      size: "SMALL",
      bullet: false,
      runs: [{ text: "", var: "", gender: [], b: false, i: false, u: false }]
    })
  };
  return {
    version: 1,
    language: l,
    place: "",
    signer: { name: "", title: "", imageBase64: "" },
    mail: { fromName: associationName2, fromAddress: "", replyTo: "", subject: t.mailSubject, body: t.mailBody },
    blocks
  };
}
function isWhitespace(c) {
  const code2 = c.charCodeAt(0);
  return code2 >= 9 && code2 <= 13 || code2 >= 28 && code2 <= 31 || new RegExp("^\\p{Z}$", "u").test(c);
}
function trimWhile(s, drop, start, end) {
  let from = 0;
  let to = s.length;
  if (start) while (from < to && drop(s[from])) from++;
  if (end) while (to > from && drop(s[to - 1])) to--;
  return s.substring(from, to);
}
var trim = (s) => trimWhile(s, isWhitespace, true, true);
var isBlank2 = (s) => trim(s) === "";
function genderIndex(gender) {
  return gender === "MALE" ? 0 : gender === "FEMALE" ? 1 : 2;
}
function upper(c) {
  const u = c.toUpperCase();
  return [...u].length === 1 ? u : c;
}
function lower(c) {
  if (c === "\u0130") return "i";
  const l = c.toLowerCase();
  return [...l].length === 1 ? l : c;
}
function displayName(raw) {
  const name = trim(raw);
  if (new RegExp("\\p{Lowercase}", "u").test(name)) return name;
  let out = "";
  let startOfWord = true;
  for (const c of name) {
    out += startOfWord ? upper(c) : lower(c);
    startOfWord = c === " " || c === "-" || c === "'";
  }
  return out;
}
var SEPARATORS = ":;,.-\u2013\u2014";
function assemble(template, association, person, types, jobs, selection) {
  const wanted = new Set(selection.jobTypes);
  const kept = jobs.filter((j) => j.day >= selection.fromDay && j.day <= selection.toDay && j.day <= selection.todayDay && wanted.has(j.jobTypeName)).sort((a, b) => a.day - b.day);
  if (kept.length === 0) return null;
  const language = template.language;
  const byName = /* @__PURE__ */ new Map();
  for (const t of types) if (!byName.has(t.name)) byName.set(t.name, t);
  const typeOf = (job) => byName.get(job.jobTypeName) ?? { name: job.jobTypeName, category: "OTHER", description: "" };
  const gIndex = genderIndex(person.gender);
  const first = kept[0].day;
  const last = kept[kept.length - 1].day;
  const global = /* @__PURE__ */ new Map([
    ["firstName", displayName(person.firstName)],
    ["lastName", displayName(person.lastName)],
    ["association", association],
    ["place", trim(template.place)],
    ["today", longDate(selection.todayDay, language)],
    ["period", period(first, last, language)],
    ["start", monthYear(first, language)],
    ["end", monthYear(last, language)],
    ["date", kept.length === 1 ? longDate(first, language) : ""]
  ]);
  function resolve(paragraph, vars) {
    const runs = paragraph.runs.map((run) => {
      let text;
      if (run.var !== "") text = vars.get(run.var) ?? "";
      else if (run.gender.length > 0) text = run.gender[gIndex] ?? "";
      else text = run.text;
      return { text, b: run.b, i: run.i, u: run.u };
    }).filter((r) => r.text !== "");
    if (runs.every((r) => isBlank2(r.text))) return null;
    const merged = [];
    for (const run of runs) {
      const prev = merged[merged.length - 1];
      if (prev && prev.b === run.b && prev.i === run.i && prev.u === run.u) {
        merged[merged.length - 1] = { ...prev, text: prev.text + run.text };
      } else {
        merged.push(run);
      }
    }
    return { align: paragraph.align, size: paragraph.size, bullet: paragraph.bullet, runs: merged };
  }
  const resolveAll = (paragraphs, vars) => paragraphs.flatMap((p) => resolve(p, vars) ?? []);
  const block = (key, variant, vars) => {
    const b = template.blocks[key];
    return b ? resolveAll(variant(b), vars) : [];
  };
  const isRolesParagraph = (p) => p.runs.some((r) => r.var === "roles") && p.runs.every((r) => r.var === "roles" || r.var === "" && r.gender.length === 0 && isBlank2(r.text));
  function withoutDescription(p) {
    const runs = [...p.runs];
    for (let i = runs.length - 1; i >= 0; i--) {
      if (runs[i].var === "description") {
        runs.splice(i, 1);
        const before = runs[i - 1];
        if (before && before.var === "" && before.gender.length === 0) {
          runs[i - 1] = { ...before, text: trimWhile(before.text, (c) => isWhitespace(c) || SEPARATORS.includes(c), false, true) };
        }
      }
    }
    return { ...p, runs };
  }
  const body = [];
  body.push(...block("header", (b) => b.one, global));
  body.push(...block("title", (b) => b.one, global));
  body.push(...block("intro", (b) => kept.length === 1 ? b.one : b.many, global));
  for (const category of CATEGORIES) {
    const ofCategory = kept.filter((j) => typeOf(j).category === category);
    if (ofCategory.length === 0) continue;
    const local = (group, withType) => {
      const vars = new Map(global);
      if (category !== "MANDATE" && category !== "GRAPHIC_ASSOCIATION") vars.set("count", String(group.length));
      vars.set("period", period(group[0].day, group[group.length - 1].day, language));
      if (category === "MANDATE") vars.set("date", "");
      else if (group.length === 1) vars.set("date", longDate(group[0].day, language));
      if (withType) {
        vars.set("role", withType.name);
        vars.set("description", withType.description);
      }
      return vars;
    };
    const byType = /* @__PURE__ */ new Map();
    for (const job of ofCategory) {
      const group = byType.get(job.jobTypeName);
      if (group) group.push(job);
      else byType.set(job.jobTypeName, [job]);
    }
    switch (category) {
      case "MANDATE":
        for (const group of byType.values()) body.push(...block(category, (b) => b.one, local(group, typeOf(group[0]))));
        break;
      case "GRAPHIC_ASSOCIATION":
        body.push(...block(category, (b) => b.one, local(ofCategory, null)));
        break;
      case "SHIFT":
      case "OTHER":
        if (ofCategory.length === 1) {
          body.push(...block(category, (b) => b.one, local(ofCategory, typeOf(ofCategory[0]))));
        } else if (byType.size === 1) {
          body.push(...block(category, (b) => b.manyOneRole, local(ofCategory, typeOf(ofCategory[0]))));
        } else {
          const vars = local(ofCategory, null);
          const lineParagraphs = template.blocks[category]?.line ?? [];
          const lines = [...byType.values()].sort((a, b) => b.length - a.length || (a[0].jobTypeName < b[0].jobTypeName ? -1 : a[0].jobTypeName > b[0].jobTypeName ? 1 : 0)).flatMap((group) => {
            const type = typeOf(group[0]);
            const lineVars = local(group, type);
            return lineParagraphs.flatMap((p) => resolve(isBlank2(type.description) ? withoutDescription(p) : p, lineVars) ?? []);
          });
          for (const p of template.blocks[category]?.many ?? []) {
            if (isRolesParagraph(p)) body.push(...lines);
            else {
              const r = resolve(p, vars);
              if (r) body.push(r);
            }
          }
        }
        break;
      default:
        body.push(...block(category, (b) => ofCategory.length === 1 ? b.one : b.many, local(ofCategory, null)));
    }
  }
  body.push(...block("closing", (b) => b.one, global));
  if (!isBlank2(template.place)) body.push(...block("dateLine", (b) => b.one, global));
  return { body, footer: block("footer", (b) => b.one, global) };
}

// src/certificatePdf.ts
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import fontkit from "@pdf-lib/fontkit";
import { PDFDocument, rgb } from "pdf-lib";
var SIZE_PT = { SMALL: 9, NORMAL: 11, LARGE: 14, TITLE: 22 };
var LEADING = 1.5;
var PARAGRAPH_GAP = 1.1;
var TITLE_GAP_AFTER = 28;
var DATE_GAP_BEFORE = 24;
var LOGO_GAP = 14;
var RULE_HEIGHT = 50;
var SIGNATURE_GAP = 44;
var BULLET_INDENT = 14;
function tokenize(p) {
  const out = [];
  for (const r of p.runs) {
    for (const part of r.text.split(/(\n|[^\S\n]+)/)) {
      if (part === "") continue;
      if (part === "\n") out.push({ br: true });
      else out.push({ text: part, b: r.b, i: r.i, u: r.u, space: /^\s+$/.test(part) });
    }
  }
  return out;
}
function layoutParagraphs(paragraphs, width, measure) {
  const lines = [];
  paragraphs.forEach((p, index) => {
    const size = SIZE_PT[p.size];
    const prevTitle = index > 0 && paragraphs[index - 1].size === "TITLE";
    const indent = p.bullet ? BULLET_INDENT : 0;
    const avail = width - indent;
    const rows = [];
    const hardBreak = [];
    let cur = [];
    let curWidth = 0;
    let pendingSpace = null;
    const flush = (hard) => {
      rows.push(cur);
      hardBreak.push(hard);
      cur = [];
      curWidth = 0;
      pendingSpace = null;
    };
    const add = (text, t) => {
      const last = cur[cur.length - 1];
      const merged = last && last.b === t.b && last.i === t.i && last.u === t.u ? last.text + text : null;
      if (merged !== null) {
        const w = measure(merged, t.b, t.i, size);
        curWidth += w - last.width;
        last.text = merged;
        last.width = w;
      } else {
        const w = measure(text, t.b, t.i, size);
        cur.push({ text, b: t.b, i: t.i, u: t.u, width: w });
        curWidth += w;
      }
    };
    for (const tok of tokenize(p)) {
      if ("br" in tok) {
        flush(true);
        continue;
      }
      if (tok.space) {
        if (cur.length > 0) pendingSpace = { text: tok.text, b: tok.b, i: tok.i, u: tok.u };
        continue;
      }
      const spaceW = pendingSpace ? measure(pendingSpace.text, pendingSpace.b, pendingSpace.i, size) : 0;
      const wordW = measure(tok.text, tok.b, tok.i, size);
      if (curWidth + spaceW + wordW <= avail) {
        if (pendingSpace) add(pendingSpace.text, pendingSpace);
        pendingSpace = null;
        add(tok.text, tok);
        continue;
      }
      if (cur.length > 0) flush(false);
      pendingSpace = null;
      if (wordW <= avail) {
        add(tok.text, tok);
        continue;
      }
      let piece = "";
      for (const ch of Array.from(tok.text)) {
        if (piece !== "" && measure(piece + ch, tok.b, tok.i, size) > avail) {
          add(piece, tok);
          flush(false);
          piece = "";
        }
        piece += ch;
      }
      if (piece !== "") add(piece, tok);
    }
    if (cur.length > 0 || rows.length === 0) flush(true);
    rows.forEach((segs, r) => {
      const isLast = r === rows.length - 1 || hardBreak[r];
      const lineWidth = segs.reduce((s, x2) => s + x2.width, 0);
      let x = indent;
      if (p.align === "CENTER") x = indent + (avail - lineWidth) / 2;
      else if (p.align === "RIGHT") x = indent + (avail - lineWidth);
      lines.push({
        x,
        size,
        gapBefore: r === 0 && index > 0 ? Math.max(size * PARAGRAPH_GAP, prevTitle ? TITLE_GAP_AFTER : 0) : 0,
        bullet: p.bullet && r === 0,
        justify: p.align === "JUSTIFY" && !isLast,
        last: isLast,
        segments: segs
      });
    });
  });
  return lines;
}
var FILES = {
  "400": ["nunito-400.ttf"],
  "700": ["nunito-700.ttf"],
  "400i": ["nunito-latin-400-italic.woff", "nunito-latin-ext-400-italic.woff"],
  "700i": ["nunito-latin-700-italic.woff", "nunito-latin-ext-700-italic.woff"]
};
var fontBytes = /* @__PURE__ */ new Map();
function loadFile(name) {
  let p = fontBytes.get(name);
  if (!p) {
    p = readFile(fileURLToPath(new URL(`../assets/${name}`, import.meta.url)));
    fontBytes.set(name, p);
    p.catch(() => {
      if (fontBytes.get(name) === p) fontBytes.delete(name);
    });
  }
  return p;
}
var faceKey = (b, i) => b ? i ? "700i" : "700" : i ? "400i" : "400";
function pieces(face, text) {
  const out = [];
  for (const ch of Array.from(text)) {
    const cp = ch.codePointAt(0);
    let idx = face.has.findIndex((h) => h(cp));
    if (idx < 0) idx = 0;
    const last = out[out.length - 1];
    if (last && last.font === face.fonts[idx]) last.text += ch;
    else out.push({ font: face.fonts[idx], text: ch });
  }
  return out;
}
async function loadFaces(doc) {
  doc.registerFontkit(fontkit);
  const faces = {};
  for (const key of Object.keys(FILES)) {
    const fonts = [];
    const has = [];
    for (const name of FILES[key]) {
      const bytes = await loadFile(name);
      const kit = fontkit.create(bytes);
      has.push((cp) => kit.hasGlyphForCodePoint(cp));
      fonts.push(await doc.embedFont(bytes, { subset: true }));
    }
    faces[key] = { fonts, has };
  }
  return faces;
}
function clean(text, faces) {
  let out = "";
  for (const ch of Array.from(text)) {
    const cp = ch.codePointAt(0);
    if (ch === "\n" || /\s/.test(ch)) {
      out += ch === "\n" ? "\n" : " ";
    } else if (cp < 32 || cp >= 127 && cp < 160) {
      continue;
    } else if (faces["400"].has.some((h) => h(cp)) || faces["400i"].has.some((h) => h(cp))) {
      out += ch;
    } else {
      out += "?";
    }
  }
  return out;
}
var PAGE_W = 595.28;
var PAGE_H = 841.89;
var MARGIN = 70.87;
var FOOTER_BASE = 38;
var INK = rgb(0.1, 0.1, 0.12);
var GREY = rgb(0.45, 0.45, 0.48);
var RULE = rgb(0.75, 0.75, 0.78);
var lineHeight = (l) => l.size * LEADING;
async function embedImage(doc, bytes) {
  if (!bytes || bytes.length === 0) return null;
  try {
    return await doc.embedPng(bytes);
  } catch {
    try {
      return await doc.embedJpg(bytes);
    } catch {
      return null;
    }
  }
}
async function renderCertificatePdf(input) {
  const doc = await PDFDocument.create();
  doc.setTitle(input.title);
  doc.setAuthor("");
  doc.setProducer("");
  doc.setCreator("");
  doc.setSubject("");
  doc.setKeywords([]);
  const faces = await loadFaces(doc);
  const measure = (text, b, i, size) => pieces(faces[faceKey(b, i)], text).reduce((s, p) => s + p.font.widthOfTextAtSize(p.text, size), 0);
  const prep = (ps) => ps.map((p) => ({ ...p, runs: p.runs.map((r) => ({ ...r, text: clean(r.text, faces) })) }));
  const textW = PAGE_W - 2 * MARGIN;
  const paragraphs = prep(input.paragraphs);
  const titleAt = paragraphs.findIndex((p) => p.size === "TITLE");
  const headerCount = titleAt > 0 ? titleAt : 0;
  const items = [];
  const logo = await embedImage(doc, input.logoPng);
  if (logo) {
    const scale = Math.min(48 / logo.height, 200 / logo.width);
    const w = logo.width * scale;
    const h = logo.height * scale;
    const align = paragraphs[0]?.align ?? "LEFT";
    const x = align === "CENTER" ? (textW - w) / 2 : align === "RIGHT" ? textW - w : 0;
    items.push({ kind: "image", image: logo, w, h, x, gapBefore: 0 });
  }
  const logoGap = logo ? LOGO_GAP : 0;
  const headerLines = layoutParagraphs(paragraphs.slice(0, headerCount), textW, measure);
  headerLines.forEach((line, k) => {
    items.push({ kind: "line", line: k === 0 && logo ? { ...line, gapBefore: logoGap } : line });
  });
  if (headerCount > 0) items.push({ kind: "rule", height: RULE_HEIGHT });
  const bodyLines = layoutParagraphs(paragraphs.slice(headerCount), textW, measure);
  let dateIdx = bodyLines.length - 1;
  while (dateIdx > 0 && bodyLines[dateIdx].gapBefore === 0) dateIdx--;
  bodyLines.forEach((line, k) => {
    const gap = k === dateIdx && k > 0 ? Math.max(line.gapBefore, DATE_GAP_BEFORE) : line.gapBefore;
    items.push({ kind: "line", line: { ...line, gapBefore: gap } });
  });
  const sig = await embedImage(doc, input.signatureImage);
  const signerLines = [];
  const signerName = clean(input.signer.name, faces).trim();
  const signerTitle = clean(input.signer.title, faces).trim();
  const plain = (text, b) => ({
    align: "RIGHT",
    size: "NORMAL",
    bullet: false,
    runs: [{ text, b, i: false, u: false }]
  });
  if (signerName) signerLines.push(...layoutParagraphs([plain(signerName, true)], textW, measure));
  if (signerTitle) signerLines.push(...layoutParagraphs([plain(signerTitle, false)], textW, measure));
  if (sig || signerLines.length > 0) {
    let imgItem = null;
    if (sig) {
      const scale = Math.min(120 / sig.width, 60 / sig.height);
      const w = sig.width * scale;
      imgItem = { kind: "image", image: sig, w, h: sig.height * scale, x: textW - w, gapBefore: 0 };
    }
    if (imgItem) items.push(imgItem);
    items.push({ kind: "signer", lines: signerLines, gapBefore: SIGNATURE_GAP, afterImage: imgItem !== null });
  }
  const footerLines = layoutParagraphs(
    prep(input.footer).map((p) => ({ ...p, size: "SMALL" })),
    textW,
    measure
  );
  const footerH = footerLines.reduce((s, l) => s + lineHeight(l), 0);
  const bottomLimit = FOOTER_BASE + footerH + (footerLines.length > 0 ? 18 : 0);
  const top = PAGE_H - MARGIN;
  const drawLine = (page2, line, topY, left, color = INK) => {
    const baseline = topY - line.size * 1.08;
    if (line.bullet) {
      const bullet = faces["400"].has[0]("\u2022".codePointAt(0)) ? "\u2022" : "-";
      page2.drawText(bullet, { x: left, y: baseline, size: line.size, font: faces["400"].fonts[0], color });
    }
    let spaceExtra = 0;
    if (line.justify) {
      const spaces = line.segments.reduce((n, s) => n + (s.text.match(/ /g)?.length ?? 0), 0);
      const used = line.segments.reduce((s, x2) => s + x2.width, 0);
      const target = textW - line.x;
      if (spaces > 0 && used < target) spaceExtra = (target - used) / spaces;
    }
    let x = left + line.x;
    for (const seg of line.segments) {
      const face = faces[faceKey(seg.b, seg.i)];
      const start = x;
      const words = spaceExtra > 0 ? seg.text.split(/( )/) : [seg.text];
      for (const word of words) {
        if (word === "") continue;
        for (const piece of pieces(face, word)) {
          if (piece.text.trim() !== "") {
            page2.drawText(piece.text, { x, y: baseline, size: line.size, font: piece.font, color });
          }
          x += piece.font.widthOfTextAtSize(piece.text, line.size);
        }
        if (word === " ") x += spaceExtra;
      }
      if (seg.u) {
        page2.drawLine({ start: { x: start, y: baseline - 1.6 }, end: { x, y: baseline - 1.6 }, thickness: 0.6, color });
      }
    }
  };
  const drawFooter = (page2) => {
    let y2 = FOOTER_BASE + footerH;
    for (const l of footerLines) {
      drawLine(page2, l, y2, MARGIN, GREY);
      y2 -= lineHeight(l);
    }
  };
  let page = doc.addPage([PAGE_W, PAGE_H]);
  drawFooter(page);
  let y = top;
  const newPage = () => {
    page = doc.addPage([PAGE_W, PAGE_H]);
    drawFooter(page);
    y = top;
  };
  for (const item of items) {
    if (item.kind === "line") {
      const h = item.line.gapBefore + lineHeight(item.line);
      if (y - h < bottomLimit && y < top) newPage();
      const gap = y === top ? 0 : item.line.gapBefore;
      drawLine(page, item.line, y - gap, MARGIN);
      y -= gap + lineHeight(item.line);
    } else if (item.kind === "rule") {
      const mid = item.height / 2;
      if (y - item.height < bottomLimit && y < top) newPage();
      page.drawLine({
        start: { x: MARGIN, y: y - mid },
        end: { x: PAGE_W - MARGIN, y: y - mid },
        thickness: 0.5,
        color: RULE
      });
      y -= item.height;
    } else if (item.kind === "image") {
      const next = items[items.indexOf(item) + 1];
      const follow = next && next.kind === "signer" ? next.gapBefore + next.lines.reduce((s, l) => s + lineHeight(l), 0) : 0;
      if (y - item.h - follow - (item.gapBefore + SIGNATURE_GAP) < bottomLimit && y < top && next?.kind === "signer") newPage();
      const gap = next?.kind === "signer" && y !== top ? SIGNATURE_GAP : item.gapBefore;
      page.drawImage(item.image, { x: MARGIN + item.x, y: y - gap - item.h, width: item.w, height: item.h });
      y -= gap + item.h + 4;
    } else {
      const afterImage = item.afterImage;
      const h = item.lines.reduce((s, l) => s + lineHeight(l), 0);
      if (!afterImage && y - h - item.gapBefore < bottomLimit && y < top) newPage();
      if (!afterImage && y !== top) y -= item.gapBefore;
      for (const l of item.lines) {
        drawLine(page, l, y, MARGIN);
        y -= lineHeight(l);
      }
    }
  }
  return doc.save();
}

// src/mail.ts
import * as logger2 from "firebase-functions/logger";
import nodemailer from "nodemailer";
import QRCode from "qrcode";
import MailComposer from "nodemailer/lib/mail-composer";

// src/workspace.ts
var MailSendError = class extends Error {
  code;
  constructor(code2) {
    super(code2);
    this.name = "MailSendError";
    this.code = code2;
  }
};
var DEFAULT_TIMEOUT_MS = 1e4;
var METADATA_TOKEN = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
var TOKEN_URL = "https://oauth2.googleapis.com/token";
var SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
var LIFETIME_SECONDS = 3600;
var REFRESH_MARGIN_MS = 6e4;
var MAX_CODE = 80;
var safePart = (s, fallback) => {
  const clean2 = typeof s === "string" ? s.replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 40) : "";
  return clean2 === "" ? fallback : clean2;
};
var code = (c) => c.slice(0, MAX_CODE);
async function jsonOf(r) {
  try {
    const body = await r.json();
    return typeof body === "object" && body !== null ? body : {};
  } catch {
    return {};
  }
}
var cache = /* @__PURE__ */ new Map();
async function delegatedToken(sa, subject, deps) {
  const key = `${sa}|${subject}`;
  const hit = cache.get(key);
  if (hit !== void 0 && deps.now() < hit.expiresAt - REFRESH_MARGIN_MS) return hit.token;
  const f = deps.fetch;
  const signal = () => AbortSignal.timeout(deps.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const meta = await f(METADATA_TOKEN, { headers: { "Metadata-Flavor": "Google" }, signal: signal() });
  const metaBody = await jsonOf(meta);
  if (!meta.ok || typeof metaBody.access_token !== "string") throw new MailSendError(code(`delegation:metadata:${meta.status}`));
  const iat = Math.floor(deps.now() / 1e3);
  const claims = { iss: sa, sub: subject, scope: SEND_SCOPE, aud: TOKEN_URL, iat, exp: iat + LIFETIME_SECONDS };
  const signed = await f(`https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${encodeURIComponent(sa).replace(/%40/g, "@")}:signJwt`, {
    method: "POST",
    headers: { Authorization: `Bearer ${metaBody.access_token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ payload: JSON.stringify(claims) }),
    signal: signal()
  });
  const signedBody = await jsonOf(signed);
  if (!signed.ok || typeof signedBody.signedJwt !== "string") throw new MailSendError(code(`delegation:sign:${signed.status}`));
  const exchanged = await f(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${encodeURIComponent(signedBody.signedJwt)}`,
    signal: signal()
  });
  const body = await jsonOf(exchanged);
  if (!exchanged.ok || typeof body.access_token !== "string") {
    throw new MailSendError(code(`delegation:${safePart(body.error, `http_${exchanged.status}`)}`));
  }
  const lifetime = typeof body.expires_in === "number" && body.expires_in > 0 ? body.expires_in : LIFETIME_SECONDS;
  cache.set(key, { token: body.access_token, expiresAt: deps.now() + lifetime * 1e3 });
  return body.access_token;
}
async function gmailSendRaw(token, user, raw, f, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const r = await f(`https://gmail.googleapis.com/gmail/v1/users/${encodeURIComponent(user).replace(/%40/g, "@")}/messages/send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ raw: raw.toString("base64url") }),
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (r.ok) return;
  const body = await jsonOf(r);
  const error6 = body.error ?? {};
  const reason = safePart(error6.errors?.[0]?.reason ?? error6.status, "unknown");
  throw new MailSendError(code(`gmail:${r.status}:${reason}`));
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
var slug = (s) => s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
var CERTIFICATE_FILE_PREFIX = {
  fr: "attestation-benevolat",
  en: "volunteer-certificate",
  de: "freiwilligen-bestaetigung",
  it: "attestato-volontariato",
  es: "certificado-voluntariado"
};
function certificateFileName(firstName, lastName, language) {
  const prefix = CERTIFICATE_FILE_PREFIX[language] ?? CERTIFICATE_FILE_PREFIX.fr;
  const parts = [prefix, slug(firstName), slug(lastName)].filter((p) => p !== "");
  return `${parts.join("-")}.pdf`;
}
function buildCertificateMail(args) {
  const text = args.body.replace(/\r\n?/g, "\n");
  const paragraphs = text.split(/\n{2,}/).map((p) => `<p>${escapeHtml(p).replace(/\n/g, "<br>")}</p>`).join("");
  const hasLogo = args.logoPng !== null && args.logoPng.length > 0;
  const html = '<!DOCTYPE html><html><body style="font-family: Arial, sans-serif; color: #222;">' + (hasLogo ? '<p><img src="cid:logo" alt="" style="max-width: 200px; max-height: 60px;"></p>' : "") + `${paragraphs}</body></html>`;
  const attachments = [
    { filename: args.fileName, content: Buffer.from(args.pdf), contentType: "application/pdf", contentDisposition: "attachment" }
  ];
  if (hasLogo) {
    attachments.push({ filename: "logo.png", content: Buffer.from(args.logoPng), contentType: "image/png", contentDisposition: "inline", cid: "logo" });
  }
  const replyTo = safeRecipient(args.replyTo);
  return {
    to: args.to,
    fromName: args.fromName,
    fromAddress: args.fromAddress,
    ...replyTo === null ? {} : { replyTo },
    subject: args.subject.replace(CONTROL, " ").trim().slice(0, MAX_SUBJECT),
    html,
    text,
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
var SENT = { sent: true, failure: "" };
var failed = (failure) => ({ sent: false, failure });
function fromAddress(transport2, user) {
  if (transport2.mode() !== "workspace") return user;
  const from = transport2.from().trim();
  return from === "" ? user : from;
}
function failureOf(e) {
  if (e instanceof MailSendError) return e.code;
  const err = e;
  if (typeof err?.responseCode === "number") return `smtp:${err.responseCode}`;
  const c = err?.code === void 0 ? "" : String(err.code).replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 40);
  return `smtp:${c === "" ? "0" : c}`;
}
function logFailure(e) {
  const err = e;
  logger2.error("mail not sent", {
    reason: "SEND_FAILED",
    failure: failureOf(e),
    code: err?.code === void 0 ? "" : String(err.code),
    responseCode: typeof err?.responseCode === "number" ? err.responseCode : 0
  });
}
async function sendWorkspace(transport2, user, raw) {
  const sa = transport2.sa().trim();
  if (sa === "") throw new MailSendError("config:no_service_account");
  const ws = transport2.workspace ?? { token: delegatedToken, send: gmailSendRaw };
  const f = transport2.fetch ?? fetch;
  const phase = (name) => (e) => {
    throw e instanceof MailSendError ? e : new MailSendError(`${name}:network`);
  };
  const token = await ws.token(sa, user, { fetch: f, now: Date.now, timeoutMs: transport2.timeoutMs }).catch(phase("delegation"));
  await ws.send(token, user, raw, f, transport2.timeoutMs).catch(phase("gmail"));
}
async function sendRawMail(msg, transport2) {
  try {
    const to = safeRecipient(msg.to);
    const user = transport2.user().trim();
    const from = fromAddress(transport2, user);
    if (to === null || safeRecipient(user) === null || safeRecipient(from) === null) {
      logger2.error("mail not sent", { reason: "BAD_ADDRESS" });
      return failed("bad_address");
    }
    const raw = msg.build(from, /* @__PURE__ */ new Date());
    if (isCapture()) {
      await transport2.db.collection("_testMail").add({
        to,
        from: { name: msg.fromName.replace(CONTROL, " ").trim(), address: from },
        raw,
        size: raw.length,
        capturedAt: Date.now()
      });
      return SENT;
    }
    if (transport2.mode() === "workspace") {
      await sendWorkspace(transport2, user, Buffer.from(raw, "utf8"));
      return SENT;
    }
    const password = transport2.password().replace(/\s+/g, "");
    if (password === "") {
      logger2.error("mail not sent", { reason: "NO_PASSWORD" });
      return failed("no_password");
    }
    const smtp = transport2.smtp ?? gmail;
    await smtp(user, password).sendMail({ envelope: { from: user, to: [to] }, raw });
    return SENT;
  } catch (e) {
    logFailure(e);
    return failed(failureOf(e));
  }
}
async function sendMail(msg, transport2) {
  try {
    const to = safeRecipient(msg.to);
    const user = transport2.user().trim();
    const from = fromAddress(transport2, user);
    if (to === null || safeRecipient(user) === null || safeRecipient(from) === null) {
      logger2.error("mail not sent", { reason: "BAD_ADDRESS" });
      return failed("bad_address");
    }
    const fromName = msg.fromName.replace(CONTROL, " ").trim();
    const header = msg.fromAddress === void 0 ? null : safeRecipient(msg.fromAddress);
    const shown = header ?? from;
    const replyTo = msg.replyTo === void 0 ? null : safeRecipient(msg.replyTo);
    if (isCapture()) {
      await transport2.db.collection("_testMail").add({
        to,
        from: { name: fromName, address: shown },
        ...replyTo === null ? {} : { replyTo },
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
      return SENT;
    }
    const message = {
      from: fromName === "" ? shown : { name: fromName, address: shown },
      to: { name: "", address: to },
      ...replyTo === null ? {} : { replyTo },
      subject: msg.subject,
      html: msg.html,
      text: msg.text,
      attachments: msg.attachments
    };
    if (transport2.mode() === "workspace") {
      await sendWorkspace(transport2, user, await new MailComposer(message).compile().build());
      return SENT;
    }
    const password = transport2.password().replace(/\s+/g, "");
    if (password === "") {
      logger2.error("mail not sent", { reason: "NO_PASSWORD" });
      return failed("no_password");
    }
    const smtp = transport2.smtp ?? gmail;
    await smtp(user, password).sendMail({ ...message, envelope: { from: user, to: [to] } });
    return SENT;
  } catch (e) {
    logFailure(e);
    return failed(failureOf(e));
  }
}

// src/requests.ts
import { randomUUID } from "node:crypto";

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
var isClaimable2 = (d) => d.status === STATUS_SUBMITTED;
var isBlank3 = (v) => typeof v !== "string" || v.trim() === "";
var errorCode2 = (e) => {
  const code2 = e?.code;
  return code2 === void 0 ? "" : String(code2);
};
var signupPhotoPath = (orgId, formId) => `orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg`;
var volunteerPhotoPath = (orgId, id) => `orgs/${orgId.trim()}/profilePhotos/volunteers/${id.trim()}.jpg`;
var guestPhotoPath = (orgId, nanoId) => `orgs/${orgId.trim()}/profilePhotos/guests/${nanoId.trim()}.jpg`;
var portalPhotoPath = (orgId, uid) => `orgs/${orgId}/portalPhotos/${uid}.jpg`;
var portalUploadPath = (orgId, uid) => `orgs/${orgId}/portalPhotoUploads/${uid}.jpg`;
async function onSignupDocument(db, bucket, orgId, formId, current) {
  if (current === void 0 || !isClaimable2(current)) return;
  if (!await portalEnabled(db, orgId)) {
    logger3.info("signup skipped", { orgId, formId, reason: "PORTAL_OFF" });
    return;
  }
  if (current.holderType === "GUEST" && !await guestPortalEnabled(db, orgId)) {
    logger3.info("signup skipped", { orgId, formId, reason: "GUEST_SPACE_OFF" });
    return;
  }
  const ref = db.doc(`orgs/${orgId}/volunteerSignupForms/${formId}`);
  const claimed = await claim(ref, isClaimable2);
  if (claimed === null) return;
  let outcome;
  try {
    outcome = await processClaimed(db, bucket, orgId, formId, ref, claimed);
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
async function processClaimed(db, bucket, orgId, formId, ref, claimed) {
  const submission = parseSignup(formId, { ...claimed, status: STATUS_SUBMITTED }, orgId);
  if (submission === null) {
    await finish(bucket, ref, orgId, formId);
    return "INVALID";
  }
  const photo = await readSignupPhoto(db, bucket, orgId, submission.photoPath);
  if (submission.holderType === "GUEST") {
    const outcome2 = await registerGuest(db, bucket, orgId, submission, photo);
    await finish(bucket, ref, orgId, formId);
    return outcome2;
  }
  const volunteers2 = await db.collection(`orgs/${orgId}/volunteers`).select("email", "portalUid", "profilePhotoPath", "profilePhotoUrl").get();
  let outcome;
  if (volunteers2.docs.some((d) => d.get("portalUid") === submission.uid)) {
    outcome = "ALREADY_LINKED";
  } else {
    const sameEmail = volunteers2.docs.find(
      (d) => typeof d.get("email") === "string" && d.get("email").trim().toLowerCase() === submission.email && isBlank3(d.get("portalUid"))
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
  if (!await profilePhotosOn(db, orgId)) return null;
  return readJpeg(bucket, photoPath);
}
async function profilePhotosOn(db, orgId) {
  return (await institutionSetting(db, orgId, "profile_photos_enabled")).trim().toLowerCase() === "true";
}
async function readJpeg(bucket, photoPath) {
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
    if (!snap.exists || !isBlank3(snap.get("portalUid"))) return null;
    tx.update(ref, { portalEnabled: true, portalUid: submission.uid, lastModified: Date.now() });
    return snap.data();
  });
  if (linked === null) throw Object.assign(new Error("link conflict"), { code: "LINK_CONFLICT" });
  if (photo === null || !isBlank3(linked.profilePhotoPath) || !isBlank3(linked.profilePhotoUrl)) return;
  const path = volunteerPhotoPath(orgId, ref.id);
  await saveJpeg(bucket, path, photo);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank3(snap.get("profilePhotoPath")) || !isBlank3(snap.get("profilePhotoUrl"))) return;
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
  const [currency, association, qrEnabled] = await Promise.all([
    institutionSetting(db, orgId, "currency_code"),
    associationName(db, orgId),
    institutionSetting(db, orgId, "volunteer_portal_qr_enabled")
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
  if (qrEnabled.trim() !== "false") {
    batch.set(db.doc(`orgs/${orgId}/volunteerPortalQr/${submission.uid}`), {
      schema: 1,
      payload: id,
      name: `${submission.firstName} ${lastName}`.trim()
    });
  }
  await batch.commit();
}
async function registerGuest(db, bucket, orgId, submission, photo) {
  const all = await db.collection(`orgs/${orgId}/guests`).select("email", "portalUid", "isTemporaryGuest", "isVolunteerBenefit").get();
  const permanents = all.docs.filter(isPermanentGuest);
  if (permanents.some((d) => d.get("portalUid") === submission.uid)) return "ALREADY_LINKED";
  const sameEmail = permanents.find(
    (d) => typeof d.get("email") === "string" && d.get("email").trim().toLowerCase() === submission.email && isBlank3(d.get("portalUid"))
  );
  if (sameEmail !== void 0) {
    await linkGuest(db, bucket, orgId, sameEmail.ref, submission, photo);
    return "LINKED";
  }
  await createGuest(db, bucket, orgId, submission, photo);
  return "CREATED";
}
async function linkGuest(db, bucket, orgId, ref, submission, photo) {
  const linked = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank3(snap.get("portalUid")) || !isPermanentGuest(snap)) return null;
    tx.update(ref, {
      portalEnabled: true,
      portalUid: submission.uid,
      lastModified: Math.max(Date.now(), (millisOf(snap.get("lastModified")) ?? 0) + 1)
    });
    return snap.data();
  });
  if (linked === null) throw Object.assign(new Error("link conflict"), { code: "LINK_CONFLICT" });
  if (photo === null || !isBlank3(linked.profilePhotoPath) || !isBlank3(linked.profilePhotoUrl)) return;
  const path = guestPhotoPath(orgId, ref.id);
  await saveJpeg(bucket, path, photo);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank3(snap.get("profilePhotoPath")) || !isBlank3(snap.get("profilePhotoUrl"))) return;
    tx.update(ref, {
      profilePhotoPath: path,
      lastModified: Math.max(Date.now(), (millisOf(snap.get("lastModified")) ?? 0) + 1)
    });
  });
}
async function createGuest(db, bucket, orgId, submission, photo) {
  const nanoId = volunteerId();
  const sameAccount = await db.collection(`orgs/${orgId}/volunteers`).where("portalUid", "==", submission.uid).get();
  const volunteerOwnsPhoto = sameAccount.docs.some(
    (d) => !isBlankValue(d.get("profilePhotoPath")) || !isBlankValue(d.get("profilePhotoUrl"))
  );
  let profilePhotoPath = "";
  if (photo !== null) {
    profilePhotoPath = guestPhotoPath(orgId, nanoId);
    await saveJpeg(bucket, profilePhotoPath, photo);
    if (!volunteerOwnsPhoto) await saveJpeg(bucket, portalPhotoPath(orgId, submission.uid), photo);
  }
  const lastName = submission.lastName.toUpperCase();
  const now = Date.now();
  const guest = {
    nanoId,
    name: submission.firstName,
    lastNameAbbreviation: lastName,
    email: submission.email,
    phone: submission.phone,
    invitations: 0,
    venueName: submission.venueName,
    notes: "",
    isVolunteerBenefit: false,
    volunteerId: null,
    lastModified: now,
    isTemporaryGuest: false,
    temporaryArtistName: "",
    temporaryEventDate: null,
    temporaryContactPhone: "",
    temporaryVenueName: "",
    temporaryContactEmail: "",
    temporaryPortalEmail: "",
    temporaryAccessIds: "",
    temporaryEntryValidatedAt: 0,
    temporaryEntryValidatedBy: "",
    nfcCardUid: "",
    nfcCardUidHash: "",
    isAdmin: false,
    barDiscountPercent: 0,
    portalEnabled: true,
    portalUid: submission.uid,
    portalGoogleEmail: "",
    portalGoogleUid: "",
    portalGoogleLinkedBy: "",
    ...profilePhotoPath !== "" ? { profilePhotoPath } : {}
  };
  const [currency, association, qrEnabled, googleAllowed] = await Promise.all([
    institutionSetting(db, orgId, "currency_code"),
    associationName(db, orgId),
    institutionSetting(db, orgId, "volunteer_portal_qr_enabled"),
    googleLinkAllowed(db, orgId)
  ]);
  const projection = minimalGuestProjection({
    firstName: submission.firstName,
    lastName,
    email: submission.email,
    phone: submission.phone,
    venueName: submission.venueName,
    photoVersion: profilePhotoPath !== "" && !volunteerOwnsPhoto ? sha256(profilePhotoPath).slice(0, 16) : "",
    currency: currency.trim().toUpperCase(),
    association,
    googleLinkAllowed: googleAllowed
  }, now);
  const batch = db.batch();
  batch.create(db.doc(`orgs/${orgId}/guests/${nanoId}`), guest);
  batch.set(db.doc(`orgs/${orgId}/guestPortal/${submission.uid}`), projection);
  if (qrEnabled.trim() !== "false") {
    batch.set(db.doc(`orgs/${orgId}/guestPortalQr/${submission.uid}`), {
      schema: 1,
      payload: nanoId,
      name: `${submission.firstName} ${lastName}`.trim()
    });
  }
  await batch.commit();
}
async function finish(bucket, ref, orgId, formId) {
  await bucket.file(signupPhotoPath(orgId, formId)).delete({ ignoreNotFound: true }).catch(() => void 0);
  await ref.delete();
}

// src/requests.ts
var STATUS_PENDING2 = "PENDING";
var MAX_LABEL2 = 80;
var DONE2 = { status: "DONE", reason: "" };
var rejected2 = (reason) => ({ status: "REJECTED", reason });
var isClaimable3 = (d) => d.status === STATUS_PENDING2 && d.type !== "GOOGLE_LINK";
var GUEST_TYPES = ["GUEST_PROFILE", "GUEST_QR_EMAIL"];
var TEMP_TYPES = ["TEMP_GUEST_NAME"];
var Deferred = class extends Error {
  code = "DEFERRED";
};
var isOwner = (d, uid) => boolOf(d.get("portalEnabled")) && stringOf(d.get("portalUid")) === uid;
var errorCode3 = (e) => {
  const code2 = e?.code;
  return code2 === void 0 ? "" : String(code2);
};
async function onRequestDocument(db, bucket, mail, orgId, requestId, current) {
  if (current === void 0 || !isClaimable3(current)) return;
  let result;
  const ref = db.doc(`orgs/${orgId}/portalRequests/${requestId}`);
  try {
    if (!await portalEnabled(db, orgId)) {
      logger4.info("request skipped", { orgId, requestId, reason: "PORTAL_OFF" });
      return;
    }
    const wait = await waitReason(db, orgId, current.type);
    if (wait !== null) {
      logger4.info("request skipped", { orgId, requestId, reason: wait });
      return;
    }
    const claimed = await claim(ref, isClaimable3);
    if (claimed === null) return;
    result = await processClaimed2(db, bucket, mail, orgId, requestId, claimed);
    await complete(ref, result);
  } catch (e) {
    if (e instanceof Deferred) {
      await release(ref).catch(() => void 0);
      logger4.info("request skipped", { orgId, requestId, reason: e.message });
      return;
    }
    logger4.error("request failed", { orgId, requestId, reason: "FAILED", code: errorCode3(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger4.error("server status not written", { orgId, requestId, code: errorCode3(e) });
  }
  logger4.info("request processed", { orgId, requestId, status: result.status, reason: result.reason });
}
async function waitReason(db, orgId, type) {
  if (TEMP_TYPES.includes(type)) return await temporaryGuestPortalEnabled(db, orgId) ? null : "TEMP_SPACE_OFF";
  if (!GUEST_TYPES.includes(type)) return null;
  if (!await guestPortalEnabled(db, orgId)) return "GUEST_SPACE_OFF";
  if (type === "GUEST_QR_EMAIL" && await publishedGuestTemplate(db, orgId) === null) return "NO_TEMPLATE";
  return null;
}
async function publishedGuestTemplate(db, orgId) {
  return parseMailTemplate((await db.doc(`orgs/${orgId}/portalMail/guest`).get()).data());
}
async function release(ref) {
  await ref.set(
    { status: STATUS_PENDING2, processingAt: FieldValue.delete(), processingDevice: FieldValue.delete() },
    { merge: true }
  );
}
async function processClaimed2(db, bucket, mail, orgId, requestId, claimed) {
  const request = parseRequest(requestId, claimed);
  if (request === null) return rejected2("INVALID");
  switch (request.type) {
    case "PROFILE":
      return updateProfile(db, bucket, orgId, requestId, request, "VOLUNTEER");
    case "GUEST_PROFILE":
      await checkGuestSpace(db, orgId);
      return updateProfile(db, bucket, orgId, requestId, request, "GUEST");
    case "CLAIM":
      return handleClaim(db, orgId, requestId, request);
    case "QR_EMAIL":
      return sendQr(db, mail, orgId, request);
    case "GUEST_QR_EMAIL":
      await checkGuestSpace(db, orgId);
      return sendGuestQr(db, mail, orgId, request);
    case "CERTIFICATE_EMAIL":
      return sendCertificate(db, mail, orgId, request);
    case "GOOGLE_UNLINK": {
      const reason = await unlinkGoogle(db, orgId, requestId, request.uid);
      return reason === "" ? DONE2 : rejected2(reason);
    }
    case "TEMP_GUEST_NAME":
      if (!await temporaryGuestPortalEnabled(db, orgId)) throw new Deferred("TEMP_SPACE_OFF");
      return renameTemporaryGuest(db, orgId, requestId, request);
    case "GOOGLE_LINK":
      return rejected2("INVALID");
  }
}
var guestKeyOf = (nanoId) => sha256(nanoId).slice(0, 16);
async function renameTemporaryGuest(db, orgId, requestId, request) {
  const pageRef2 = db.doc(`orgs/${orgId}/temporaryGuestPortal/${request.uid}`);
  const qrRef = db.doc(`orgs/${orgId}/temporaryGuestPortalQr/${request.uid}`);
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(guests(db, orgId).where("portalUid", "==", request.uid));
      const target = linked.docs.find(
        (d) => boolOf(d.get("isTemporaryGuest")) && boolOf(d.get("portalEnabled")) && guestKeyOf(stringOf(d.get("nanoId")) || d.id) === request.guestKey
      );
      if (target === void 0) return rejected2("NOT_FOUND");
      if ((millisOf(target.get("temporaryEntryValidatedAt")) ?? 0) > 0) return rejected2("ENTRY_USED");
      const page = await tx.get(pageRef2);
      const qr = await tx.get(qrRef);
      const now = Date.now();
      const firstName = request.firstName || stringOf(target.get("name"));
      const lastName = request.lastName ? request.lastName.toUpperCase() : stringOf(target.get("lastNameAbbreviation"));
      const renamed = { ...request, firstName, lastName };
      tx.update(target.ref, {
        name: firstName,
        lastNameAbbreviation: lastName,
        lastModified: Math.max(now, (millisOf(target.get("lastModified")) ?? 0) + 1)
      });
      const patch = renamedPage(page.data(), renamed, now);
      if (patch !== null) tx.update(pageRef2, patch);
      const item = qr.data()?.items?.[request.guestKey ?? ""];
      if (item !== void 0 && item !== null && typeof item === "object") {
        tx.update(qrRef, { [`items.${request.guestKey}.name`]: `${firstName} ${lastName}`.trim() });
      }
      return DONE2;
    });
  } catch (e) {
    logger4.error("name not saved", { orgId, requestId, code: errorCode3(e) });
    return rejected2("SAVE_FAILED");
  }
}
function renamedPage(data, request, now) {
  if (data === void 0 || typeof data.payload !== "string") return null;
  let payload;
  try {
    payload = JSON.parse(data.payload);
  } catch {
    return null;
  }
  if (!Array.isArray(payload?.groups)) return null;
  for (const group of payload.groups) {
    if (!Array.isArray(group?.guests)) continue;
    for (const g of group.guests) {
      if (g?.guestKey === request.guestKey) {
        g.firstName = request.firstName;
        g.lastName = request.lastName;
      }
    }
  }
  const text = JSON.stringify(payload);
  return { payload: text, contentHash: sha256(text), updatedAt: now };
}
async function complete(ref, result) {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now() }, { merge: true });
}
async function checkGuestSpace(db, orgId) {
  if (!await guestPortalEnabled(db, orgId)) throw new Deferred("GUEST_SPACE_OFF");
}
var volunteers = (db, orgId) => db.collection(`orgs/${orgId}/volunteers`);
var guests = (db, orgId) => db.collection(`orgs/${orgId}/guests`);
var PROFILE_KINDS = {
  VOLUNTEER: {
    collection: volunteers,
    page: "volunteerPortal",
    photoPath: volunteerPhotoPath,
    owner: (docs, uid) => docs.find((d) => isOwner(d, uid))
  },
  GUEST: {
    collection: guests,
    page: "guestPortal",
    photoPath: guestPhotoPath,
    owner: (docs, uid) => guestOwnerOf(docs, uid) ?? void 0
  }
};
async function volunteerHasPhoto(db, orgId, uid) {
  if (!await profilePhotosOn(db, orgId)) return false;
  const linked = await volunteers(db, orgId).where("portalUid", "==", uid).get();
  return linked.docs.some(
    (d) => isOwner(d, uid) && (!isBlankValue(d.get("profilePhotoPath")) || !isBlankValue(d.get("profilePhotoUrl")))
  );
}
async function updateProfile(db, bucket, orgId, requestId, request, kind) {
  try {
    return await saveProfile(db, bucket, orgId, requestId, request, kind);
  } finally {
    if (request.photo) {
      await bucket.file(portalUploadPath(orgId, request.uid)).delete({ ignoreNotFound: true }).catch(() => void 0);
    }
  }
}
async function saveProfile(db, bucket, orgId, requestId, request, kind) {
  const k = PROFILE_KINDS[kind];
  const projectionRef = db.doc(`orgs/${orgId}/${k.page}/${request.uid}`);
  let photo = null;
  let ownerId = "";
  if (request.photo) {
    const linked = await k.collection(db, orgId).where("portalUid", "==", request.uid).get();
    const owner = k.owner(linked.docs, request.uid);
    if (owner === void 0) return rejected2("NO_ACCESS");
    if (!await profilePhotosOn(db, orgId)) return rejected2("INVALID");
    const bytes = await readJpeg(bucket, portalUploadPath(orgId, request.uid));
    if (bytes === null) return rejected2("INVALID");
    const accountPhoto = kind === "VOLUNTEER" || !await volunteerHasPhoto(db, orgId, request.uid);
    try {
      photo = await savePhoto(bucket, orgId, k.photoPath(orgId, owner.id), accountPhoto ? request.uid : null, bytes);
    } catch (e) {
      logger4.error("photo not saved", { orgId, requestId, code: errorCode3(e) });
      return rejected2("SAVE_FAILED");
    }
    ownerId = owner.id;
  }
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(k.collection(db, orgId).where("portalUid", "==", request.uid));
      const owner = k.owner(linked.docs, request.uid);
      if (owner === void 0) return rejected2("NO_ACCESS");
      if (photo !== null && owner.id !== ownerId) return rejected2("SAVE_FAILED");
      const projection = await tx.get(projectionRef);
      const now = Date.now();
      const lastName = request.lastName.toUpperCase();
      tx.update(owner.ref, {
        name: request.firstName,
        lastNameAbbreviation: lastName,
        phone: request.phone,
        ...photo !== null ? { profilePhotoPath: photo.path, profilePhotoUrl: photo.url } : {},
        lastModified: Math.max(now, (millisOf(owner.get("lastModified")) ?? 0) + 1)
      });
      const fields = { firstName: request.firstName, lastName, phone: request.phone };
      if (photo !== null && photo.account) Object.assign(fields, { hasPhoto: true, photoVersion: photo.version });
      const patch = patchedProjection(projection.data(), fields, now);
      if (patch !== null) tx.update(projectionRef, patch);
      return DONE2;
    });
  } catch (e) {
    logger4.error("profile not saved", { orgId, requestId, code: errorCode3(e) });
    return rejected2("SAVE_FAILED");
  }
}
async function savePhoto(bucket, orgId, path, uid, bytes) {
  const token = randomUUID();
  await bucket.file(path).save(bytes, {
    contentType: "image/jpeg",
    resumable: false,
    metadata: { metadata: { firebaseStorageDownloadTokens: token } }
  });
  if (uid !== null) {
    await bucket.file(portalPhotoPath(orgId, uid)).save(bytes, { contentType: "image/jpeg", resumable: false });
  }
  const url = `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
  return { path, url, version: sha256(url).slice(0, 16), account: uid !== null };
}
function claimAddress(d) {
  if (!boolOf(d.get("isTemporaryGuest"))) return kotlinTrim(stringOf(d.get("email"))).toLowerCase();
  const portal = stringOf(d.get("temporaryPortalEmail"));
  const address = kotlinTrim(portal) === "" ? stringOf(d.get("temporaryContactEmail")) : portal;
  return kotlinTrim(address).toLowerCase();
}
async function handleClaim(db, orgId, requestId, request) {
  const uid = request.uid;
  const [allVolunteers, allGuests, guestSpace, tempSpace] = await Promise.all([
    volunteers(db, orgId).select("email", "portalEnabled", "portalUid").get(),
    guests(db, orgId).select(
      "email",
      "portalEnabled",
      "portalUid",
      "isTemporaryGuest",
      "isVolunteerBenefit",
      "temporaryPortalEmail",
      "temporaryContactEmail"
    ).get(),
    guestPortalEnabled(db, orgId),
    temporaryGuestPortalEnabled(db, orgId)
  ]);
  const sameEmail = (d) => boolOf(d.get("portalEnabled")) && kotlinTrim(stringOf(d.get("email"))).toLowerCase() === request.email;
  const owner = allVolunteers.docs.some((d) => isOwner(d, uid));
  let volunteerToLink = null;
  if (!owner) {
    const candidates = allVolunteers.docs.filter(sameEmail);
    const free = candidates.filter((d) => isBlankValue(d.get("portalUid")));
    if (candidates.length > 0 && free.length !== 1) return rejected2("LINKED_ELSEWHERE");
    volunteerToLink = free[0]?.ref ?? null;
  }
  const claimable = (d) => {
    if (!boolOf(d.get("portalEnabled"))) return false;
    if (boolOf(d.get("isTemporaryGuest"))) return tempSpace;
    if (boolOf(d.get("isVolunteerBenefit"))) return false;
    return guestSpace;
  };
  const open = allGuests.docs.filter(claimable);
  const guestOwned = open.some((d) => stringOf(d.get("portalUid")) === uid);
  const targets = open.filter((d) => stringOf(d.get("portalUid")) !== uid && claimAddress(d) === request.email);
  const foreign = targets.filter((d) => !isBlankValue(d.get("portalUid")));
  const hasAccess = owner || guestOwned;
  if (foreign.length > 0 && !hasAccess) return rejected2("LINKED_ELSEWHERE");
  const freeGuests = targets.filter((d) => isBlankValue(d.get("portalUid")));
  if (volunteerToLink === null && freeGuests.length === 0) return hasAccess ? DONE2 : rejected2("NO_ACCESS");
  const guestStillFree = (d) => claimable(d) && claimAddress(d) === request.email && isBlankValue(d.get("portalUid"));
  try {
    return await db.runTransaction(async (tx) => {
      const [linked, volunteerSnap, ...guestSnaps] = await Promise.all([
        tx.get(volunteers(db, orgId).where("portalUid", "==", uid)),
        volunteerToLink !== null ? tx.get(volunteerToLink) : Promise.resolve(null),
        ...freeGuests.map((d) => tx.get(d.ref))
      ]);
      const linkVolunteer = volunteerSnap !== null && !linked.docs.some((d) => isOwner(d, uid));
      if (linkVolunteer && (!volunteerSnap.exists || !sameEmail(volunteerSnap) || !isBlankValue(volunteerSnap.get("portalUid")))) {
        return rejected2("SAVE_FAILED");
      }
      if (guestSnaps.some((g) => !g.exists || !guestStillFree(g))) return rejected2("SAVE_FAILED");
      const now = Date.now();
      const linkFields = (snap) => ({
        portalUid: uid,
        lastModified: Math.max(now, (millisOf(snap.get("lastModified")) ?? 0) + 1)
      });
      if (linkVolunteer) tx.update(volunteerSnap.ref, linkFields(volunteerSnap));
      for (const g of guestSnaps) tx.update(g.ref, linkFields(g));
      return DONE2;
    });
  } catch (e) {
    logger4.error("claim not saved", { orgId, requestId, code: errorCode3(e) });
    return rejected2("SAVE_FAILED");
  }
}
async function sendQr(db, mail, orgId, request) {
  const linked = await volunteers(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = linked.docs.find((d) => isOwner(d, request.uid));
  if (owner === void 0) return rejected2("NO_ACCESS");
  if (boolOf(owner.get("isAdmin"))) return rejected2("NOT_ALLOWED");
  const email = kotlinTrim(stringOf(owner.get("email")));
  if (email === "") return rejected2("NO_EMAIL");
  const to = safeRecipient(email);
  if (to === null) return rejected2("NO_EMAIL");
  const [template, association] = await Promise.all([
    db.doc(`orgs/${orgId}/portalMail/default`).get(),
    associationName(db, orgId)
  ]);
  const message = await buildQrMail(parseMailTemplate(template.data()), stringOf(owner.get("name")), owner.id);
  const result = await sendMail({ ...message, to, fromName: association.trim().slice(0, MAX_LABEL2) }, mail);
  return result.sent ? DONE2 : rejected2("SEND_FAILED");
}
async function sendGuestQr(db, mail, orgId, request) {
  const linked = await guests(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = guestOwnerOf(linked.docs, request.uid);
  if (owner === null) return rejected2("NO_ACCESS");
  if (boolOf(owner.get("isAdmin"))) return rejected2("NOT_ALLOWED");
  const email = kotlinTrim(stringOf(owner.get("email")));
  if (email === "") return rejected2("NO_EMAIL");
  const to = safeRecipient(email);
  if (to === null) return rejected2("NO_EMAIL");
  const [template, association] = await Promise.all([publishedGuestTemplate(db, orgId), associationName(db, orgId)]);
  if (template === null) throw new Deferred("NO_TEMPLATE");
  const message = await buildQrMail(template, stringOf(owner.get("name")), owner.id);
  const result = await sendMail({ ...message, to, fromName: association.trim().slice(0, MAX_LABEL2) }, mail);
  return result.sent ? DONE2 : rejected2("SEND_FAILED");
}
var CERT_CATEGORIES = [
  "MANDATE",
  "SHIFT",
  "PHOTO",
  "GRAPHIC_EVENT",
  "GRAPHIC_ASSOCIATION",
  "OTHER",
  "MEETING"
];
var CERT_TITLE_FALLBACK = {
  fr: "Attestation",
  en: "Certificate",
  de: "Best\xE4tigung",
  it: "Attestazione",
  es: "Certificado"
};
function publishedJobTypes(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const item of raw) {
    if (item === null || typeof item !== "object" || typeof item.name !== "string") continue;
    const category = CERT_CATEGORIES.includes(item.category) ? item.category : "OTHER";
    out.push({ name: kotlinTrim(item.name), category, description: stringOf(item.description) });
  }
  return out;
}
function certificateJobs(docs) {
  const jobs = [];
  for (const d of docs) {
    const jobTypeName = kotlinTrim(stringOf(d.get("jobTypeName")));
    const millis = millisOf(d.get("date"));
    if (jobTypeName === "" || millis === null || !Number.isFinite(new Date(millis).getTime())) continue;
    jobs.push({ jobTypeName, day: dayOf(millis) });
  }
  return jobs;
}
var fillMail = (text, firstName, association) => text.split("{firstName}").join(firstName).split("{association}").join(association);
async function sendCertificate(db, mail, orgId, request) {
  if (await institutionSetting(db, orgId, "volunteer_portal_certificate_enabled") !== "true") return rejected2("NOT_ALLOWED");
  const published = (await db.doc(`orgs/${orgId}/portalMail/certificate`).get()).data();
  const rawTemplate = published?.template;
  const template = rawTemplate !== null && typeof rawTemplate === "object" ? parseTemplate(rawTemplate) : null;
  if (published === void 0 || template === null) return rejected2("NO_TEMPLATE");
  const linked = await volunteers(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = linked.docs.find((d) => isOwner(d, request.uid));
  if (owner === void 0) return rejected2("NO_ACCESS");
  const to = safeRecipient(kotlinTrim(stringOf(owner.get("email"))));
  if (to === null) return rejected2("NO_EMAIL");
  const jobDocs = await db.collection(`orgs/${orgId}/jobs`).where("volunteerId", "==", owner.id).get();
  const publishedAssociation = stringOf(published.association);
  const association = (publishedAssociation.trim() !== "" ? publishedAssociation : await associationName(db, orgId)).trim();
  const firstName = stringOf(owner.get("name"));
  const lastName = stringOf(owner.get("lastNameAbbreviation"));
  const assembly = assemble(
    template,
    association,
    { firstName, lastName, gender: stringOf(owner.get("gender")) },
    publishedJobTypes(published.jobTypes),
    certificateJobs(jobDocs.docs),
    {
      fromDay: Number(request.from?.replace(/-/g, "")),
      toDay: Number(request.to?.replace(/-/g, "")),
      todayDay: dayOf(Date.now()),
      jobTypes: request.jobTypes ?? []
    }
  );
  if (assembly === null) return rejected2("NOTHING_TO_CERTIFY");
  const titleParagraph = assembly.body.find((p) => p.size === "TITLE");
  const title = titleParagraph?.runs.map((r) => r.text).join("").trim() || (CERT_TITLE_FALLBACK[template.language] ?? "Attestation");
  const logoPng = logoBytes(stringOf(published.logoBase64));
  const signature = template.signer.imageBase64.trim();
  let pdf;
  try {
    pdf = await renderCertificatePdf({
      paragraphs: assembly.body,
      footer: assembly.footer,
      logoPng,
      signatureImage: signature === "" ? null : Buffer.from(signature, "base64"),
      signer: { name: template.signer.name, title: template.signer.title },
      title
    });
  } catch (e) {
    logger4.error("certificate not rendered", { reason: "RENDER_FAILED", code: errorCode3(e) });
    return rejected2("SEND_FAILED");
  }
  const fallback = defaultTemplate(template.language, association).mail;
  const name = displayName(firstName);
  const subject = fillMail(template.mail.subject.trim() === "" ? fallback.subject : template.mail.subject, name, association);
  const body = fillMail(template.mail.body.trim() === "" ? fallback.body : template.mail.body, name, association);
  const message = buildCertificateMail({
    to,
    fromName: (template.mail.fromName.trim() === "" ? association : template.mail.fromName).trim().slice(0, MAX_LABEL2),
    fromAddress: template.mail.fromAddress.trim(),
    replyTo: template.mail.replyTo.trim(),
    subject,
    body,
    pdf,
    fileName: certificateFileName(firstName, lastName, template.language),
    logoPng
  });
  const result = await sendMail(message, mail);
  return result.sent ? DONE2 : rejected2("SEND_FAILED");
}

// src/outbox.ts
import * as logger5 from "firebase-functions/logger";
import { randomUUID as randomUUID2 } from "node:crypto";
import { FieldValue as FieldValue2 } from "firebase-admin/firestore";
var OUTBOX_SCHEMA = 1;
var MAX_RAW_LENGTH = 9e5;
var MAX_LABEL3 = 80;
function parseOutbox(data) {
  if (data === void 0 || data.schema !== OUTBOX_SCHEMA) return null;
  const { to, raw, fromName } = data;
  if (typeof to !== "string" || typeof raw !== "string") return null;
  if (raw.length === 0 || raw.length > MAX_RAW_LENGTH) return null;
  if (headerEnd(raw) < 0) return null;
  return { to, raw, fromName: typeof fromName === "string" ? fromName.slice(0, MAX_LABEL3) : "" };
}
function headerEnd(raw) {
  const crlf = raw.indexOf("\r\n\r\n");
  if (crlf >= 0) return crlf;
  return raw.indexOf("\n\n");
}
var REPLACED = /* @__PURE__ */ new Set(["from", "sender", "to", "cc", "bcc", "date", "message-id", "return-path"]);
var CONTROL2 = /[\u0000-\u001F\u007F]+/g;
function displayName2(name) {
  const clean2 = name.replace(CONTROL2, " ").trim();
  if (clean2 === "") return "";
  if (/^[\x20-\x7E]*$/.test(clean2)) return `"${clean2.replace(/["\\]/g, "\\$&")}"`;
  return `=?UTF-8?B?${Buffer.from(clean2, "utf8").toString("base64")}?=`;
}
function rewriteHeaders(raw, from, fromName, to, now) {
  const end = headerEnd(raw);
  const eol = raw.startsWith("\r\n", end) ? "\r\n" : "\n";
  const lines = raw.slice(0, end).split(/\r?\n/);
  const kept = [];
  let dropping = false;
  for (const line of lines) {
    if (/^[ \t]/.test(line)) {
      if (!dropping) kept.push(line);
      continue;
    }
    const name = line.slice(0, Math.max(0, line.indexOf(":"))).trim().toLowerCase();
    dropping = REPLACED.has(name);
    if (!dropping) kept.push(line);
  }
  const shown = displayName2(fromName);
  const domain = from.slice(from.lastIndexOf("@") + 1);
  const head = [
    `From: ${shown === "" ? from : `${shown} <${from}>`}`,
    `To: ${to}`,
    `Date: ${now.toUTCString().replace("GMT", "+0000")}`,
    `Message-ID: <${randomUUID2()}@${domain}>`,
    ...kept
  ];
  return head.join("\r\n") + "\r\n" + raw.slice(end + eol.length);
}
var isClaimable4 = (d) => d.status === "PENDING";
var errorCode4 = (e) => {
  const code2 = e?.code;
  return code2 === void 0 ? "" : String(code2);
};
async function onOutboxDocument(db, mail, orgId, id, current) {
  if (current === void 0 || !isClaimable4(current)) return;
  const ref = db.doc(`orgs/${orgId}/mailOutbox/${id}`);
  let answer;
  try {
    const claimed = await claim(ref, isClaimable4);
    if (claimed === null) return;
    answer = await sendClaimed(mail, claimed);
    const { reason, failure } = answer;
    await ref.set(
      { status: reason === "" ? "DONE" : "REJECTED", reason, failure, processedAt: Date.now(), raw: FieldValue2.delete() },
      { merge: true }
    );
  } catch (e) {
    logger5.error("outbox mail failed", { orgId, id, code: errorCode4(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger5.error("server status not written", { orgId, id, code: errorCode4(e) });
  }
  logger5.info("outbox mail processed", { orgId, id, reason: answer.reason, failure: answer.failure });
}
async function sendClaimed(mail, claimed) {
  const parsed = parseOutbox(claimed);
  if (parsed === null) return { reason: "INVALID", failure: "" };
  const to = safeRecipient(parsed.to);
  if (to === null) return { reason: "BAD_ADDRESS", failure: "" };
  const result = await sendRawMail(
    { to, fromName: parsed.fromName, build: (from, now) => rewriteHeaders(parsed.raw, from, parsed.fromName, to, now) },
    mail
  );
  return result.sent ? { reason: "", failure: "" } : { reason: "SEND_FAILED", failure: result.failure };
}

// src/index.ts
var REGION = defineString("REGION", { default: "europe-west1" });
var GMAIL_USER = defineString("GMAIL_USER");
var MAIL_MODE = defineString("MAIL_MODE", { default: "app_password" });
var MAIL_FROM = defineString("MAIL_FROM", { default: "" });
var MAIL_SA = defineString("MAIL_SA", { default: "" });
var GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");
function transport(db) {
  return {
    db,
    mode: () => {
      const mode = MAIL_MODE.value().trim().toLowerCase();
      if (mode === "workspace" || mode === "app_password") return mode;
      logger6.warn("unknown MAIL_MODE, using app_password", { length: mode.length });
      return "app_password";
    },
    user: () => GMAIL_USER.value(),
    from: () => MAIL_FROM.value(),
    sa: () => MAIL_SA.value(),
    // Not read in workspace mode, where the app writes "unused".
    password: () => GMAIL_APP_PASSWORD.value()
  };
}
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
    const { db, bucket } = admin();
    const mail = transport(db);
    await onRequestDocument(db, bucket, mail, event.params.orgId, event.params.requestId, after.data());
  }
);
var onMailOutboxWritten = onDocumentWritten(
  {
    document: "orgs/{orgId}/mailOutbox/{mailId}",
    region: REGION,
    retry: false,
    timeoutSeconds: 120,
    secrets: [GMAIL_APP_PASSWORD]
  },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db } = admin();
    const mail = transport(db);
    await onOutboxDocument(db, mail, event.params.orgId, event.params.mailId, after.data());
  }
);
var onPortalGoogleClaimWritten = onDocumentWritten(
  { document: "orgs/{orgId}/portalGoogleClaims/{googleUid}", region: REGION, retry: false },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db } = admin();
    await onGoogleClaimDocument(db, event.params.orgId, event.params.googleUid, after.data());
  }
);
export {
  MAX_RAW_LENGTH,
  MailSendError,
  assemble as assembleCertificate,
  buildCertificateMail,
  buildQrMail,
  categoryOf,
  certificateFileName,
  cleanEmail,
  cleanName,
  cleanPhone,
  dayOf,
  defaultTemplate,
  delegatedToken,
  displayName,
  domainAllowed,
  gmailSendRaw,
  layoutParagraphs,
  longDate,
  monthYear,
  onMailOutboxWritten,
  onOutboxDocument,
  onPortalGoogleClaimWritten,
  onPortalRequestWritten,
  onSignupWritten,
  parseDomains,
  parseGoogleClaim,
  parseMailTemplate,
  parseOutbox,
  parseRequest,
  parseSignup,
  parseTemplate,
  period,
  renderCertificatePdf,
  rewriteHeaders,
  safeRecipient,
  sendMail,
  sendRawMail,
  smtpOptions
};
