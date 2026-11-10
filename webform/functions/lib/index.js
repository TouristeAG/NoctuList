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
export {
  cleanEmail,
  cleanName,
  cleanPhone,
  parseRequest,
  parseSignup
};
