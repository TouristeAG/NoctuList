// Shared helpers for the registration page and the portal (volunteers, guests, guest groups).
// Every piece of data reaches the page through textContent — never innerHTML — so nothing a
// volunteer types (or a document contains) can run as script.

/** Same as the rules: a PROCESSING claim this old was abandoned and the request can be sent again. */
const PROCESSING_STALE_MS = 5 * 60_000;
/**
 * A QR e-mail request nobody took for this long is given up: only the server or a phone can send
 * the mail, so an old one (sent before mail worked, for example) would otherwise read "received" forever.
 */
const QR_PENDING_STALE_MS = 15 * 60_000;
/**
 * The server answers within seconds and devices step in after 2 minutes. A request still pending
 * after this was missed (the Functions were stopped, no device open): the volunteer may send it
 * again, which the rules allow and which wakes the server up.
 */
const PENDING_STALE_MS = 5 * 60_000;
/** Pending requests that wait for something else than the server or a device: never given up. */
const ALWAYS_WAIT = new Set(["GOOGLE_LINK", "ADMIN_MATCH", "LINK_CODE"]);

/** Firestore timestamp, plain millis or nothing, as millis (0 when unknown). */
export function millisOf(value) {
  if (!value) return 0;
  if (typeof value === "number") return value;
  if (typeof value.toMillis === "function") return value.toMillis();
  return 0;
}

function pendingStaleMs(type) {
  if (ALWAYS_WAIT.has(type)) return Infinity;
  return type === "QR_EMAIL" || type === "GUEST_QR_EMAIL" ? QR_PENDING_STALE_MS : PENDING_STALE_MS;
}

/** The request waits for an answer: pending and recent, or claimed less than 5 minutes ago. */
export function isWaiting(r, now = Date.now()) {
  if (r?.status === "PENDING") {
    // No time yet: just written, the server timestamp is still on its way.
    const at = millisOf(r.requestedAt);
    return !at || now - at <= pendingStaleMs(r.type);
  }
  if (r?.status !== "PROCESSING") return false;
  const at = millisOf(r.processingAt);
  return !at || now - at <= PROCESSING_STALE_MS;
}

/** Still pending, but for so long that nobody is going to take it: it can be sent again. */
export function isStalePending(r, now = Date.now()) {
  return r?.status === "PENDING" && !isWaiting(r, now);
}

// ---------- Profiles of one portal account ----------

/** The pages one account may have, in the order the selector shows them. */
export const PROFILE_ORDER = ["volunteer", "guest", "temporary"];

/**
 * Pages a session reads: a session whose address is not verified only reads the temporary guest
 * lists (the rules refuse it everything else, and those too unless a device made the account).
 */
export function profileKinds({ google, verified }) {
  return google || verified ? [...PROFILE_ORDER] : ["temporary"];
}

/**
 * One address, one account (spec 2026-11-21 §6.1): the personal profiles go in the selector and
 * the managed guest lists below them; an account with lists only gets the lists page.
 */
export function profilesLayout(available) {
  const personal = PROFILE_ORDER.filter((k) => k !== "temporary" && available.includes(k));
  return { personal, managed: available.includes("temporary") };
}

/** Lists still visible: schema 2 `visibleUntil`, schema 1 `endsAt`, none means visible; by date. */
export function visibleLists(groups, now = Date.now()) {
  return (Array.isArray(groups) ? groups : [])
    .filter((g) => {
      const until = Number(g.visibleUntil ?? g.endsAt ?? 0);
      return !until || until > now;
    })
    .sort((a, b) => Number(a.eventDate) - Number(b.eventDate));
}

/**
 * Places left once [draft] (people with access ids) is sent: overall and per access. An access
 * with max 0 has no limit of its own. `ok` is false as soon as one count goes below zero.
 */
export function inviteRemaining(invite, draft) {
  if (!invite) return { remaining: 0, accesses: {}, ok: false };
  const people = Array.isArray(draft) ? draft : [];
  const remaining = Number(invite.remaining || 0) - people.length;
  const accesses = {};
  let ok = remaining >= 0;
  (Array.isArray(invite.accesses) ? invite.accesses : []).forEach((a) => {
    const taken = people.filter((p) => (p.access || []).includes(a.id)).length;
    const left = Number(a.remaining || 0) - taken;
    accesses[a.id] = left;
    if (Number(a.max || 0) > 0 && left < 0) ok = false;
  });
  return { remaining, accesses, ok };
}

/** The QR dialog closes only when the profile whose code went away is the one it shows. */
export function qrDialogClosesFor(shownKind, droppedKind) {
  return !!shownKind && shownKind === droppedKind;
}

/** `states`: kind to "loading", "present", "absent" or "denied". */
export function profilesSettled(states) {
  const kinds = PROFILE_ORDER.filter((k) => k in states);
  return {
    done: kinds.every((k) => states[k] !== "loading"),
    available: kinds.filter((k) => states[k] === "present"),
  };
}

/** The profile to show: the last one chosen on this device if it is still there, else the first. */
export function chooseProfile(available, stored) {
  const list = PROFILE_ORDER.filter((k) => available.includes(k));
  if (!list.length) return null;
  return list.includes(stored) ? stored : list[0];
}

export function profileStoreKey(orgId) {
  return `noctulist.portalProfile.${orgId}`;
}

/**
 * When the pages refused before a DONE claim are read again, in ms between tries: about 3 minutes
 * in all, since the page can follow the answer late (sync, 5 s publish debounce, and a device that
 * only steps in after 2 minutes when the server is down).
 */
export function claimRetryDelays() {
  return [0, 2000, 5000, 10000, 20000, 30000, 30000, 30000, 30000, 30000];
}

/**
 * A claim just answered DONE: the pages it linked were refused before (a missing temporary page
 * is refused, not empty) and must be read again. Returns the claim's key the first time, then null.
 */
export function claimReloadKey(claim, handledKey) {
  if (claim?.status !== "DONE") return null;
  const key = `DONE:${millisOf(claim.requestedAt)}`;
  return key === handledKey ? null : key;
}

/** Rules: a request is renewed at most once a minute. */
const CLAIM_RENEW_MS = 60_000;

/**
 * A CLAIM may be sent now: none is waiting, the last one is over a minute old (the rules refuse a
 * faster renewal) and this page sent none in the last minute ([lastSentAt]).
 */
export function claimCanAsk(claim, lastSentAt, now = Date.now()) {
  const renewable = !claim || (!isWaiting(claim, now) && now - millisOf(claim.requestedAt) > CLAIM_RENEW_MS);
  return renewable && now - lastSentAt > CLAIM_RENEW_MS;
}

/**
 * A verified e-mail + password session that already shows pages still sends one CLAIM per
 * session: a profile opened later for the same address (an existing account on the device side)
 * is only linked by it. CLAIM is idempotent: an owner gets DONE and its free profiles are linked.
 * Never for Google or unverified sessions, and only once the CLAIM listener answered
 * ([claim] undefined means not read yet).
 */
export function claimWithPages({ google, verified, claim, sentThisSession, lastSentAt }, now = Date.now()) {
  if (google || !verified || sentThisSession || claim === undefined) return false;
  return claimCanAsk(claim, lastSentAt, now);
}

/** `el("p", { class: "muted", text: "…" }, child1, child2)` */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = String(value);
    else if (key === "dataset") Object.assign(node.dataset, value);
    else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

// Same shapes as firebase/firestore.rules (validPersonName, validPhone, dateOfBirth).
export const NAME_RE = /^[^<>{}\\]{1,60}$/;
export const PHONE_RE = /^[0-9+() .-]{4,32}$/;
export const DATE_RE = /^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/;
export const EMAIL_RE = /^[^@\s]{1,64}@[^@\s]{1,190}\.[^@\s]{2,}$/;
export const GENDERS = ["FEMALE", "MALE", "NON_BINARY", "OTHER", "PREFER_NOT_TO_DISCLOSE"];
/** Same minimum as Firebase Authentication, so every page (including the e-mail reset page) agrees. */
export const MIN_PASSWORD = 6;

export function cleanName(value) {
  const v = String(value || "").trim().replace(/\s+/g, " ");
  return v && NAME_RE.test(v) ? v : null;
}

export function cleanPhone(value) {
  const v = String(value || "").trim();
  return PHONE_RE.test(v) ? v : null;
}

const COMMON = new Set([
  "1234567890", "12345678910", "0123456789", "1111111111", "0000000000", "azertyuiop", "qwertyuiop",
  "motdepasse", "motdepasse1", "motdepasse123", "password12", "password123", "password1234", "passwort123",
  "contraseña1", "iloveyou12", "123456789a", "a123456789", "1q2w3e4r5t", "qwerty1234", "azerty1234",
  "trustno1234", "letmein123", "welcome123", "bienvenue1", "soleil1234", "football12", "superman12",
  "doudou1234", "chocolat12", "1234567890a", "abcdefghij", "abcd123456", "abc1234567", "q1w2e3r4t5",
]);

/** 0..4, a rough estimate shown by the meter to steer people away from weak choices. It never blocks. */
export function passwordStrength(password, email = "") {
  const p = String(password || "");
  if (p.length < MIN_PASSWORD) return 0;
  if (COMMON.has(p.toLowerCase())) return 0;
  const local = String(email || "").split("@")[0].toLowerCase();
  if (local.length >= 4 && p.toLowerCase().includes(local)) return 0;
  let classes = 0;
  if (/[a-z]/.test(p)) classes++;
  if (/[A-Z]/.test(p)) classes++;
  if (/[0-9]/.test(p)) classes++;
  if (/[^A-Za-z0-9]/.test(p)) classes++;
  const unique = new Set(p).size;
  let score = 1;
  if (p.length >= 12) score++;
  if (p.length >= 16) score++;
  if (classes >= 3) score++;
  if (unique < p.length / 3) score--;
  return Math.max(1, Math.min(4, score));
}

/**
 * Returns an i18n error key, or null when the password is acceptable. Only Firebase's own rule
 * blocks (length); the strength meter just advises.
 */
export function passwordProblem(password) {
  const p = String(password || "");
  if (p.length < MIN_PASSWORD) return "errPasswordShort";
  return null;
}

/** Firebase Auth error code → i18n key. */
export function authErrorKey(error) {
  const code = String(error?.code || "");
  if (code.includes("network-request-failed")) return "errNetwork";
  if (code.includes("too-many-requests")) return "errTooMany";
  if (code.includes("weak-password") || code.includes("password-does-not-meet-requirements")) return "errWeakPassword";
  if (code.includes("email-already-in-use")) return "errEmailInUse";
  if (code.includes("invalid-email")) return "errEmail";
  if (code.includes("wrong-password") || code.includes("invalid-credential") || code.includes("user-not-found") ||
      code.includes("invalid-login-credentials")) return "errSignIn";
  return "errGeneric";
}

// ---------- Images ----------

const MAX_EDGE_PX = 1024;
const JPEG_QUALITY = 0.86;
const MAX_UPLOAD_BYTES = 2.8 * 1024 * 1024;

async function decodeImage(file) {
  if (window.createImageBitmap) {
    try {
      return await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      /* older Safari: fall back to <img> */
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.decoding = "async";
    img.src = url;
    await img.decode();
    return img;
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Any picked image → upright JPEG under the Storage rule's size limit. */
export async function toJpeg(file) {
  const image = await decodeImage(file);
  const width = image.width || image.naturalWidth;
  const height = image.height || image.naturalHeight;
  if (!width || !height) throw new Error("empty image");
  const scale = Math.min(1, MAX_EDGE_PX / Math.max(width, height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(width * scale));
  canvas.height = Math.max(1, Math.round(height * scale));
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
  if (typeof image.close === "function") image.close();
  let quality = JPEG_QUALITY;
  let blob = null;
  while (quality >= 0.5) {
    blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
    if (blob && blob.size < MAX_UPLOAD_BYTES) break;
    quality -= 0.12;
  }
  if (!blob || blob.size >= MAX_UPLOAD_BYTES) throw new Error("too large");
  return blob;
}

// ---------- Icons (static, trusted markup) ----------

const ICON_PATHS = {
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-8 8-8s8 3.6 8 8"/>',
  star: '<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
  gift: '<rect x="3" y="8" width="18" height="13" rx="2"/><path d="M12 8v13M3 12h18M12 8c-2-3-6-3-6 0s4 0 6 0zm0 0c2-3 6-3 6 0s-4 0-6 0z"/>',
  wallet: '<rect x="3" y="6" width="18" height="14" rx="3"/><path d="M16 13h2M3 10h18M6 6l9-3 1 3"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  qr: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v.01M14 20h.01M17 17h3v3"/>',
  shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
  logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H3"/>',
  ticket: '<path d="M3 8a2 2 0 0 0 2-2h14a2 2 0 0 0 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 0-2 2H5a2 2 0 0 0-2-2v-2a2 2 0 0 0 0-4z"/><path d="M13 6v12" stroke-dasharray="2 2"/>',
  download: '<path d="M12 3v12M7 10l5 5 5-5M4 21h16"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M14 6l4 4"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 7l9 6 9-6"/>',
  drink: '<path d="M6 3h12l-1.5 17a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1z"/><path d="M6.5 8h11"/>',
  percent: '<path d="M19 5L5 19"/><circle cx="7" cy="7" r="2.5"/><circle cx="17" cy="17" r="2.5"/>',
  list: '<path d="M9 6h11M9 12h11M9 18h11"/><path d="M4 6l1 1 2-2M4 12l1 1 2-2M4 18l1 1 2-2"/>',
};

export function icon(name, size = 18) {
  const span = document.createElement("span");
  span.className = "ico";
  span.setAttribute("aria-hidden", "true");
  span.innerHTML = `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">${ICON_PATHS[name] || ""}</svg>`;
  return span;
}

const fold = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

/** The full guest list of a grant, by list then by name; [query] matches names, accents ignored. */
export function guestListGroups(guests, query = "") {
  const q = fold(query).trim();
  const byList = new Map();
  (Array.isArray(guests) ? guests : [])
    .filter((g) => !q || fold(`${g.firstName} ${g.lastName}`).includes(q))
    .forEach((g) => {
      const key = `${g.listName || ""}\u0000${g.venueName || ""}`;
      if (!byList.has(key)) byList.set(key, { listName: String(g.listName || ""), venueName: String(g.venueName || ""), guests: [] });
      byList.get(key).guests.push(g);
    });
  const groups = [...byList.values()].sort((a, b) => fold(a.listName).localeCompare(fold(b.listName)));
  groups.forEach((x) => x.guests.sort((a, b) => fold(`${a.firstName} ${a.lastName}`).localeCompare(fold(`${b.firstName} ${b.lastName}`))));
  return groups;
}

/** "{entered} entered of {total}". */
export function guestListCounts(guests) {
  const list = Array.isArray(guests) ? guests : [];
  return { entered: list.filter((g) => g.entryUsed === true).length, total: list.length };
}

/** No profile page but a granted guest list still to come: the space shows that list alone. */
export function guestListOnly(available, grants, now = Date.now()) {
  return !(Array.isArray(available) ? available : []).length && visibleLists(grants, now).length > 0;
}

export const MAX_GUEST_NOTES = 500;

/**
 * The ADD edits of the "Add guests" form of a guest list (spec guest list access §6.2): one per
 * person, with the list, the notes and, for a new list only, its contact. A first name is needed,
 * the last name is optional; empty person boxes are skipped. Returns { edits } or { error }.
 */
export function guestListAddEdits({ people, listName, venueName = "", newList = false, phone = "", contactEmail = "", notes = "" }) {
  const filled = (Array.isArray(people) ? people : [])
    .filter((p) => String(p?.firstName || "").trim() || String(p?.lastName || "").trim());
  if (!filled.length) return { error: "errName" };
  const persons = [];
  for (const p of filled) {
    const firstName = cleanName(p.firstName);
    const lastName = String(p.lastName || "").trim() ? cleanName(p.lastName) : "";
    if (firstName === null || lastName === null) return { error: "errName" };
    persons.push({ firstName, lastName, accessIds: [...new Set(Array.isArray(p.accessIds) ? p.accessIds : [])] });
  }
  const list = String(listName || "").trim();
  if (!list) return { error: "guestListNeedsName" };
  const tel = String(phone || "").trim();
  if (tel && cleanPhone(tel) === null) return { error: "errPhone" };
  const mail = String(contactEmail || "").trim().toLowerCase();
  if (mail && !(mail.length <= 254 && EMAIL_RE.test(mail))) return { error: "errEmail" };
  const note = String(notes || "").trim();
  if (note.length > MAX_GUEST_NOTES) return { error: "notesTooLong" };
  const shared = { action: "ADD", listName: list, venueName: String(venueName || "").trim(), notes: note, ...(newList ? { phone: tel, contactEmail: mail } : {}) };
  return { edits: persons.map((p) => ({ ...shared, ...p })) };
}

/**
 * Past and upcoming shifts as of [now]. A shift is past once its event day ends: `pastFrom`, the
 * next midnight plus the app's synced day-change offset. Older pages without it use the date.
 */
export function splitShifts(shifts, now) {
  const list = Array.isArray(shifts) ? shifts : [];
  const pastFrom = (s) => Number(s.pastFrom ?? s.date);
  return {
    past: list.filter((s) => pastFrom(s) <= now),
    upcoming: list.filter((s) => pastFrom(s) > now),
  };
}
