// Mirrors com.eventmanager.app.portal.PortalInputValidator (Kotlin). The Admin SDK bypasses
// Firestore rules, so these checks must stay identical; test/validation-cases.json is run by both.

export type PortalRequestType =
  | "PROFILE"
  | "QR_EMAIL"
  | "CLAIM"
  | "GOOGLE_LINK"
  | "GOOGLE_UNLINK"
  | "GUEST_PROFILE"
  | "GUEST_QR_EMAIL"
  | "TEMP_GUEST_NAME"
  | "CERTIFICATE_EMAIL";
/** Who a registration link registers (PortalHolderKind, Kotlin). */
export type HolderType = "VOLUNTEER" | "GUEST";
export type PortalGoogleClaimType = "ADMIN_MATCH" | "LINK_CODE";

export interface SignupSubmission {
  formId: string;
  uid: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string;
  /** Empty for a guest. */
  dateOfBirth: string;
  /** Not asked of a guest: PREFER_NOT_TO_DISCLOSE there, never used. */
  gender: string;
  photoPath: string;
  lang: string;
  holderType: HolderType;
  /** GUEST only: the venue stored on the link by the app (the public side cannot change it). */
  venueName: string;
}

export interface PortalRequest {
  requestId: string;
  uid: string;
  type: PortalRequestType;
  status: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string;
  /** PROFILE and GUEST_PROFILE only: a new photo waits in `portalPhotoUploads/{uid}.jpg`. */
  photo: boolean;
  /** GOOGLE_LINK only: SHA-256 hex of the one-time link code. */
  codeHash?: string;
  /** TEMP_GUEST_NAME only: the guest's key on the account's temporary page (never its id). */
  guestKey?: string;
  /** CERTIFICATE_EMAIL only: the period (AAAA-MM-JJ, from <= to) and the job type names to certify. */
  from?: string;
  to?: string;
  jobTypes?: string[];
}

export interface PortalGoogleClaim {
  googleUid: string;
  type: PortalGoogleClaimType;
  status: string;
  email: string;
  /** LINK_CODE only: the plaintext one-time link code. */
  code: string;
}

// `u` makes quantifiers count code points like the JVM regex engine does.
const NAME = /^[^<>{}\\]{1,60}$/u;
const PHONE = /^[0-9+() .-]{4,32}$/u;
const DATE = /^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/u;
const EMAIL = /^[^@ \t\n\x0B\f\r]{1,64}@[^@ \t\n\x0B\f\r]{1,190}\.[^@ \t\n\x0B\f\r]{2,}$/u;
const UID = /^[A-Za-z0-9]{6,128}$/u;
const CODE_HASH = /^[0-9a-f]{64}$/u;
const LINK_CODE = /^[A-Za-z0-9_-]{32,64}$/u;
const GUEST_KEY = /^[0-9a-f]{16}$/u;
const LANGS = new Set(["fr", "en", "es", "de", "it"]);
const TYPES: readonly string[] = [
  "PROFILE", "QR_EMAIL", "CLAIM", "GOOGLE_LINK", "GOOGLE_UNLINK", "GUEST_PROFILE", "GUEST_QR_EMAIL",
  "TEMP_GUEST_NAME", "CERTIFICATE_EMAIL",
];
/** What a CERTIFICATE_EMAIL request may carry: the portal's fields and the ones a claim adds. */
const CERTIFICATE_FIELDS = new Set([
  "uid", "type", "status", "requestedAt", "email", "from", "to", "jobTypes",
  "processingAt", "processingDevice", "processedAt", "reason",
]);
const MAX_CERT_TYPES = 50;
const MAX_CERT_TYPE_NAME = 80;
const CLAIM_TYPES: readonly string[] = ["ADMIN_MATCH", "LINK_CODE"];
const GENDERS = new Set(["FEMALE", "MALE", "NON_BINARY", "OTHER", "PREFER_NOT_TO_DISCLOSE"]);

// Kotlin Char.isWhitespace: Character.isWhitespace || isSpaceChar.
const WS = "\\t-\\r\\u001C-\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
const TRIM = new RegExp(`^[${WS}]+|[${WS}]+$`, "gu");
// Java \s (no UNICODE_CHARACTER_CLASS) is ASCII only.
const ASCII_RUN = /[ \t\n\x0B\f\r]+/g;

/** Kotlin String.trim(): strips Char.isWhitespace at both ends. */
export const kotlinTrim = (s: string): string => s.replace(TRIM, "");

export function cleanName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const value = kotlinTrim(raw).replace(ASCII_RUN, " ");
  return value.length > 0 && NAME.test(value) ? value : null;
}

export function cleanPhone(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const value = kotlinTrim(raw);
  return PHONE.test(value) ? value : null;
}

export function cleanEmail(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const value = kotlinTrim(raw).toLowerCase();
  return value.length <= 254 && EMAIL.test(value) ? value : null;
}

const str = (v: unknown): string | undefined => (typeof v === "string" ? v : undefined);

/** `orgId` is only needed to recognise the form's own photo path; without it photoPath is "". */
export function parseSignup(
  formId: string,
  data: Record<string, unknown>,
  orgId = "",
): SignupSubmission | null {
  if (String(data.status) !== "SUBMITTED") return null;
  const uid = str(data.submittedUid);
  if (uid === undefined || !UID.test(uid)) return null;
  const email = cleanEmail(data.email);
  if (email === null) return null;
  const firstName = cleanName(data.firstName);
  if (firstName === null) return null;
  const lastName = cleanName(data.lastName);
  if (lastName === null) return null;
  const phone = cleanPhone(data.phone);
  if (phone === null) return null;
  // Same test as the rules: only "GUEST" makes a guest link, anything else is a volunteer one.
  const holderType: HolderType = data.holderType === "GUEST" ? "GUEST" : "VOLUNTEER";
  const guest = holderType === "GUEST";
  const dateOfBirth = guest ? "" : str(data.dateOfBirth);
  if (dateOfBirth === undefined || (!guest && !DATE.test(dateOfBirth))) return null;
  const gender = guest ? "PREFER_NOT_TO_DISCLOSE" : String(data.gender);
  if (!GENDERS.has(gender)) return null;
  if (data.consent !== true) return null;
  const photoPath = str(data.photoPath) ?? "";
  // Only ever trust the path this form owns, whatever the document says.
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
    lang: lang !== undefined && LANGS.has(lang) ? lang : "fr",
    holderType,
    venueName: guest ? kotlinTrim(str(data.venueName) ?? "") : "",
  };
}

export function parseRequest(requestId: string, data: Record<string, unknown>): PortalRequest | null {
  const uid = str(data.uid);
  if (uid === undefined || !UID.test(uid)) return null;
  const typeName = String(data.type);
  if (!TYPES.includes(typeName)) return null;
  const type = typeName as PortalRequestType;
  if (requestId !== `${uid}_${type}`) return null;
  const email = cleanEmail(data.email);
  if (email === null) return null;
  const status = data.status === null || data.status === undefined ? "" : String(data.status);
  if (type === "PROFILE" || type === "GUEST_PROFILE") {
    const firstName = cleanName(data.firstName);
    const lastName = cleanName(data.lastName);
    const phone = cleanPhone(data.phone);
    if (firstName === null || lastName === null || phone === null) return null;
    return { requestId, uid, type, status, email, firstName, lastName, phone, photo: data.photo === true };
  }
  if (type === "TEMP_GUEST_NAME") {
    const guestKey = str(data.guestKey);
    if (guestKey === undefined || !GUEST_KEY.test(guestKey)) return null;
    // One of the two may be left empty; a non-empty one must be a valid name.
    const optional = (v: unknown): string | null => (kotlinTrim(str(v) ?? "") === "" ? "" : cleanName(v));
    const firstName = optional(data.firstName);
    const lastName = optional(data.lastName);
    if (firstName === null || lastName === null || (firstName === "" && lastName === "")) return null;
    return { requestId, uid, type, status, email, firstName, lastName, phone: "", photo: false, guestKey };
  }
  if (type === "CERTIFICATE_EMAIL") {
    if (Object.keys(data).some((k) => !CERTIFICATE_FIELDS.has(k))) return null;
    const from = str(data.from);
    const to = str(data.to);
    if (from === undefined || to === undefined || !DATE.test(from) || !DATE.test(to) || from > to) return null;
    const raw = data.jobTypes;
    if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_CERT_TYPES) return null;
    const jobTypes: string[] = [];
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
    if (codeHash === undefined || !CODE_HASH.test(codeHash)) return null;
    return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "", photo: false, codeHash };
  }
  return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "", photo: false };
}

export function parseGoogleClaim(googleUid: string, data: Record<string, unknown>): PortalGoogleClaim | null {
  if (!UID.test(googleUid)) return null;
  const typeName = String(data.type);
  if (!CLAIM_TYPES.includes(typeName)) return null;
  const type = typeName as PortalGoogleClaimType;
  const email = cleanEmail(data.email);
  if (email === null) return null;
  const status = data.status === null || data.status === undefined ? "" : String(data.status);
  let code = "";
  if (type === "LINK_CODE") {
    const raw = str(data.code);
    if (raw === undefined || !LINK_CODE.test(raw)) return null;
    code = raw;
  }
  return { googleUid, type, status, email, code };
}
