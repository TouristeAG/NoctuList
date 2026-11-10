// Mirrors com.eventmanager.app.portal.PortalInputValidator (Kotlin). The Admin SDK bypasses
// Firestore rules, so these checks must stay identical; test/validation-cases.json is run by both.

export type PortalRequestType = "PROFILE" | "QR_EMAIL" | "CLAIM";

export interface SignupSubmission {
  formId: string;
  uid: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string;
  dateOfBirth: string;
  gender: string;
  photoPath: string;
  lang: string;
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
}

// `u` makes quantifiers count code points like the JVM regex engine does.
const NAME = /^[^<>{}\\]{1,60}$/u;
const PHONE = /^[0-9+() .-]{4,32}$/u;
const DATE = /^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/u;
const EMAIL = /^[^@ \t\n\x0B\f\r]{1,64}@[^@ \t\n\x0B\f\r]{1,190}\.[^@ \t\n\x0B\f\r]{2,}$/u;
const UID = /^[A-Za-z0-9]{6,128}$/u;
const LANGS = new Set(["fr", "en", "es", "de", "it"]);
const TYPES: readonly string[] = ["PROFILE", "QR_EMAIL", "CLAIM"];
const GENDERS = new Set(["FEMALE", "MALE", "NON_BINARY", "OTHER", "PREFER_NOT_TO_DISCLOSE"]);

// Kotlin Char.isWhitespace: Character.isWhitespace || isSpaceChar.
const WS = "\\t-\\r\\u001C-\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
const TRIM = new RegExp(`^[${WS}]+|[${WS}]+$`, "gu");
// Java \s (no UNICODE_CHARACTER_CLASS) is ASCII only.
const ASCII_RUN = /[ \t\n\x0B\f\r]+/g;

const kotlinTrim = (s: string): string => s.replace(TRIM, "");

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
  const dateOfBirth = str(data.dateOfBirth);
  if (dateOfBirth === undefined || !DATE.test(dateOfBirth)) return null;
  const gender = String(data.gender);
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
  if (type === "PROFILE") {
    const firstName = cleanName(data.firstName);
    const lastName = cleanName(data.lastName);
    const phone = cleanPhone(data.phone);
    if (firstName === null || lastName === null || phone === null) return null;
    return { requestId, uid, type, status, email, firstName, lastName, phone };
  }
  return { requestId, uid, type, status, email, firstName: "", lastName: "", phone: "" };
}
