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
var LANGS = /* @__PURE__ */ new Set(["fr", "en", "es", "de", "it"]);
var TYPES = [
  "PROFILE",
  "QR_EMAIL",
  "CLAIM",
  "GOOGLE_LINK",
  "GOOGLE_UNLINK",
  "GUEST_PROFILE",
  "GUEST_QR_EMAIL"
];
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
    lang: lang !== void 0 && LANGS.has(lang) ? lang : "fr",
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
  const clean = kotlinTrim(email);
  const at = clean.lastIndexOf("@");
  return at >= 0 && clean.slice(at + 1) !== "";
}
function domainAllowed(email, domains) {
  const clean = kotlinTrim(email).toLowerCase();
  const at = clean.lastIndexOf("@");
  const domain = at < 0 ? "" : clean.slice(at + 1);
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
  const clean = typeof s === "string" ? s.replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 40) : "";
  return clean === "" ? fallback : clean;
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
    if (isCapture()) {
      await transport2.db.collection("_testMail").add({
        to,
        from: { name: fromName, address: from },
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
      from: fromName === "" ? from : { name: fromName, address: from },
      to: { name: "", address: to },
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
var isBlank2 = (v) => typeof v !== "string" || v.trim() === "";
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
    (d) => typeof d.get("email") === "string" && d.get("email").trim().toLowerCase() === submission.email && isBlank2(d.get("portalUid"))
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
    if (!snap.exists || !isBlank2(snap.get("portalUid")) || !isPermanentGuest(snap)) return null;
    tx.update(ref, {
      portalEnabled: true,
      portalUid: submission.uid,
      lastModified: Math.max(Date.now(), (millisOf(snap.get("lastModified")) ?? 0) + 1)
    });
    return snap.data();
  });
  if (linked === null) throw Object.assign(new Error("link conflict"), { code: "LINK_CONFLICT" });
  if (photo === null || !isBlank2(linked.profilePhotoPath) || !isBlank2(linked.profilePhotoUrl)) return;
  const path = guestPhotoPath(orgId, ref.id);
  await saveJpeg(bucket, path, photo);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank2(snap.get("profilePhotoPath")) || !isBlank2(snap.get("profilePhotoUrl"))) return;
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
    case "GOOGLE_UNLINK": {
      const reason = await unlinkGoogle(db, orgId, requestId, request.uid);
      return reason === "" ? DONE2 : rejected2(reason);
    }
    case "GOOGLE_LINK":
      return rejected2("INVALID");
  }
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
function displayName(name) {
  const clean = name.replace(CONTROL2, " ").trim();
  if (clean === "") return "";
  if (/^[\x20-\x7E]*$/.test(clean)) return `"${clean.replace(/["\\]/g, "\\$&")}"`;
  return `=?UTF-8?B?${Buffer.from(clean, "utf8").toString("base64")}?=`;
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
  const shown = displayName(fromName);
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
  buildQrMail,
  cleanEmail,
  cleanName,
  cleanPhone,
  delegatedToken,
  domainAllowed,
  gmailSendRaw,
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
  rewriteHeaders,
  safeRecipient,
  sendMail,
  sendRawMail,
  smtpOptions
};
