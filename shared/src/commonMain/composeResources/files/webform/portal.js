// Volunteer space: /v/{orgId}
// Reads only the signed-in volunteer's own projection (orgs/{org}/volunteerPortal/{uid}); every
// change goes through a request document that a NoctuList device validates and applies.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  EmailAuthProvider,
  browserLocalPersistence,
  browserSessionPersistence,
  connectAuthEmulator,
  initializeAuth,
  onAuthStateChanged,
  reauthenticateWithCredential,
  sendEmailVerification,
  sendPasswordResetEmail,
  setPersistence,
  signInWithEmailAndPassword,
  signOut,
  updatePassword,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore,
  onSnapshot,
  serverTimestamp,
  setDoc,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  connectStorageEmulator,
  getDownloadURL,
  getStorage,
  ref as storageRef,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-storage.js";
import { EMULATOR_PORTS, isLocalDev, loadConfig, parseOrgPath } from "./firebase-env.js?v=portal-2";
import { applyStaticI18n, bindLanguageSelect, currentLang, locale, t } from "./portal-i18n.js?v=portal-2";
import {
  EMAIL_RE,
  authErrorKey,
  cleanName,
  cleanPhone,
  clear,
  el,
  icon,
  passwordProblem,
} from "./portal-common.js?v=portal-2";

const $ = (id) => document.getElementById(id);
const VIEWS = ["view-loading", "view-disabled", "view-signin", "view-reset", "view-verify", "view-pending", "view-dash"];
const REMEMBER_KEY = "nl-portal-remember";
const LAST_ORG_KEY = "nl-portal-org";
const IDLE_LIMIT_MS = 30 * 60 * 1000;
const DELAYS_MIN = { PROFILE: 1, QR_EMAIL: 10, CLAIM: 1 };
/** Same as the rules: a PROCESSING claim this old was abandoned and the request can be sent again. */
const PROCESSING_STALE_MS = 5 * 60_000;

let path = null;
let auth = null;
let db = null;
let storage = null;
let branding = null;
let user = null;
let projection = null;
let projectionUpdatedAt = 0;
let photoUrl = "";
let photoVersionLoaded = "";
let requests = {};
let unsubscribers = [];
let idleTimer = null;

// ---------- Views ----------

function showView(id) {
  VIEWS.forEach((v) => $(v).classList.toggle("hidden", v !== id));
  document.body.classList.toggle("dash-open", id === "view-dash");
}

function setError(id, key, vars) {
  const node = $(id);
  if (!key) {
    node.textContent = "";
    node.classList.add("hidden");
    return;
  }
  node.textContent = t(key, vars);
  node.classList.remove("hidden");
}

// ---------- Formatting ----------

function fmtDate(millis, opts = { day: "2-digit", month: "2-digit", year: "numeric" }) {
  if (!millis) return "-";
  return new Intl.DateTimeFormat(locale(), opts).format(new Date(Number(millis)));
}

function fmtIsoDate(iso) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso || "")) return iso || "-";
  const [y, m, d] = iso.split("-").map(Number);
  return new Intl.DateTimeFormat(locale(), { day: "2-digit", month: "long", year: "numeric" })
    .format(new Date(Date.UTC(y, m - 1, d, 12)));
}

function fmtMoney(amount, currency, signed = false) {
  const value = Number(amount) || 0;
  let text;
  try {
    text = new Intl.NumberFormat(locale(), { style: "currency", currency: currency || "CHF" }).format(Math.abs(value));
  } catch {
    text = `${Math.abs(value).toFixed(2)} ${currency || ""}`.trim();
  }
  if (!signed) return value < 0 ? `−${text}` : text;
  return `${value < 0 ? "−" : "+"}${text}`;
}

function ago(millis) {
  const diff = Math.max(0, Date.now() - Number(millis || 0));
  const min = Math.floor(diff / 60000);
  if (min < 1) return t("justNow");
  if (min < 60) return t("minutesAgo", { n: min });
  const h = Math.floor(min / 60);
  if (h < 48) return t("hoursAgo", { n: h });
  return t("daysAgo", { n: Math.floor(h / 24) });
}

function millisOf(value) {
  if (!value) return 0;
  if (typeof value === "number") return value;
  if (typeof value.toMillis === "function") return value.toMillis();
  return 0;
}

// ---------- Branding ----------

async function loadBranding() {
  try {
    const snap = await getDoc(doc(db, "orgs", path.orgId, "portalBranding", "default"));
    branding = snap.exists() ? snap.data() : {};
    return true;
  } catch (error) {
    // Firestore rules refuse everything here while the institution has the portal turned off.
    console.warn("portal branding unavailable", error?.code);
    return false;
  }
}

function renderBranding() {
  const brand = $("brand");
  clear(brand);
  const name = String(branding?.associationName || "").trim();
  const logo = String(branding?.logoDataUri || "");
  const safeLogo = /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(logo) ? logo : "";
  if (safeLogo) brand.append(el("img", { src: safeLogo, alt: "" }));
  if (name) brand.append(el("span", { text: name }));
  const signinLogo = $("signin-logo");
  if (safeLogo) {
    signinLogo.src = safeLogo;
    signinLogo.classList.remove("hidden");
  }
  document.title = name ? `${t("portalTitle")} · ${name}` : t("portalTitle");
}

// ---------- Auth flows ----------

async function onSignIn(event) {
  event.preventDefault();
  setError("signin-error");
  const email = $("signin-email").value.trim().toLowerCase();
  const password = $("signin-password").value;
  if (!EMAIL_RE.test(email)) return setError("signin-error", "errEmail");
  if (!password) return setError("signin-error", "errSignIn");
  const remember = $("remember").checked;
  const btn = $("signin-btn");
  btn.disabled = true;
  try {
    localStorage.setItem(REMEMBER_KEY, remember ? "1" : "0");
    await setPersistence(auth, remember ? browserLocalPersistence : browserSessionPersistence);
    await signInWithEmailAndPassword(auth, email, password);
    $("signin-password").value = "";
  } catch (error) {
    setError("signin-error", authErrorKey(error));
  } finally {
    btn.disabled = false;
  }
}

async function onReset(event) {
  event.preventDefault();
  setError("reset-error");
  $("reset-done").classList.add("hidden");
  const email = $("reset-email").value.trim().toLowerCase();
  if (!EMAIL_RE.test(email)) return setError("reset-error", "errEmail");
  const btn = $("reset-btn");
  btn.disabled = true;
  try {
    await sendPasswordResetEmail(auth, email, { url: location.href.split("#")[0] });
  } catch (error) {
    // Never reveal whether an account exists: only real failures are shown.
    const key = authErrorKey(error);
    if (key === "errNetwork" || key === "errTooMany") {
      setError("reset-error", key);
      btn.disabled = false;
      return;
    }
  }
  $("reset-done").classList.remove("hidden");
  btn.disabled = false;
}

async function doSignOut(reasonKey) {
  if ($("account-dialog").open) $("account-dialog").close();
  stopListeners();
  projection = null;
  photoUrl = "";
  photoVersionLoaded = "";
  await signOut(auth).catch(() => {});
  if (reasonKey) {
    $("signin-notice").textContent = t(reasonKey);
    $("signin-notice").classList.remove("hidden");
  }
}

function stopListeners() {
  unsubscribers.forEach((fn) => {
    try {
      fn();
    } catch {
      /* ignore */
    }
  });
  unsubscribers = [];
  requests = {};
}

function armIdleTimer() {
  if (idleTimer) clearTimeout(idleTimer);
  if (!user) return;
  idleTimer = setTimeout(() => doSignOut("idleSignOut"), IDLE_LIMIT_MS);
}

async function onUser(next) {
  stopListeners();
  user = next;
  armIdleTimer();
  if (!user) {
    $("signin-email").value = $("signin-email").value || "";
    return showView("view-signin");
  }
  if (!user.emailVerified) {
    $("verify-body").textContent = t("verifyBody", { email: user.email });
    return showView("view-verify");
  }
  // Make sure the ID token carries email_verified = true (verified in another tab).
  await user.getIdToken(true).catch(() => {});
  watchProjection();
}

function watchProjection() {
  showView("view-loading");
  const ref = doc(db, "orgs", path.orgId, "volunteerPortal", user.uid);
  unsubscribers.push(onSnapshot(ref, (snap) => {
    if (!snap.exists()) {
      projection = null;
      showPending();
      return;
    }
    try {
      projection = JSON.parse(snap.data().payload || "{}");
    } catch {
      projection = null;
      return showPending();
    }
    projectionUpdatedAt = Number(snap.data().updatedAt || 0);
    renderDashboard();
    showView("view-dash");
  }, (error) => {
    console.warn("projection unavailable", error?.code);
    if (String(error?.code).includes("permission-denied")) {
      $("disabled-error").classList.add("hidden");
      showView("view-disabled");
    }
  }));
  ["PROFILE", "QR_EMAIL", "CLAIM"].forEach((type) => {
    const ref = doc(db, "orgs", path.orgId, "portalRequests", `${user.uid}_${type}`);
    unsubscribers.push(onSnapshot(ref, (snap) => {
      requests[type] = snap.exists() ? snap.data() : null;
      if (type === "CLAIM") renderPending();
      else if (projection) renderDashboard();
    }, () => {}));
  });
}

let claimSentAt = 0;

async function showPending() {
  showView("view-pending");
  renderPending();
  // Ask the team's devices to link this verified address to a volunteer profile.
  const claim = requests.CLAIM;
  const canAsk = !claim || (!isWaiting(claim) && Date.now() - millisOf(claim.requestedAt) > 60_000);
  if (canAsk && Date.now() - claimSentAt > 60_000) {
    claimSentAt = Date.now();
    await sendRequest("CLAIM").catch((error) => console.warn("claim failed", error?.code));
  }
}

function renderPending() {
  if ($("view-pending").classList.contains("hidden")) return;
  const claim = requests.CLAIM;
  const rejected = claim && claim.status === "REJECTED";
  $("pending-spinner").classList.toggle("hidden", Boolean(rejected));
  if (rejected) {
    setError("pending-error", claim.reason === "LINKED_ELSEWHERE" ? "pendingLinked" : "pendingNoAccess", { email: user?.email || "" });
  } else {
    setError("pending-error");
  }
}

async function sendRequest(type, extra = {}) {
  const ref = doc(db, "orgs", path.orgId, "portalRequests", `${user.uid}_${type}`);
  await setDoc(ref, {
    uid: user.uid,
    type,
    status: "PENDING",
    requestedAt: serverTimestamp(),
    email: user.email,
    ...extra,
  });
}

/** The request waits for an answer: pending, or claimed less than 5 minutes ago. */
function isWaiting(r) {
  if (r?.status === "PENDING") return true;
  if (r?.status !== "PROCESSING") return false;
  const at = millisOf(r.processingAt);
  return !at || Date.now() - at <= PROCESSING_STALE_MS;
}

function requestCooldownMinutes(type) {
  const r = requests[type];
  if (!r) return 0;
  const at = millisOf(r.requestedAt);
  if (!at) return 0;
  const left = at + DELAYS_MIN[type] * 60_000 - Date.now();
  return left > 0 ? Math.ceil(left / 60_000) : 0;
}

// ---------- Dashboard ----------

function tile(name, title, iconName, { alt = false, action = null } = {}) {
  const head = el("div", { class: "tile-head" }, icon(iconName, 18), el("h2", { text: title }), action);
  if (alt) head.querySelector(".ico").classList.add("alt");
  return el("section", { class: `card tile ${name}` }, head);
}

function kv(rows) {
  const dl = el("dl", { class: "kv" });
  rows.forEach(([k, v]) => {
    dl.append(el("dt", { text: k }));
    dl.append(v instanceof Node ? el("dd", {}, v) : el("dd", { text: v || "-" }));
  });
  return dl;
}

function initials() {
  const a = (projection.firstName || "").trim()[0] || "";
  const b = (projection.lastName || "").trim()[0] || "";
  return (a + b).toUpperCase() || "?";
}

async function ensurePhoto() {
  if (!projection?.hasPhoto) {
    photoUrl = "";
    photoVersionLoaded = "";
    return;
  }
  if (photoVersionLoaded === projection.photoVersion && photoUrl) return;
  try {
    photoUrl = await getDownloadURL(storageRef(storage, `orgs/${path.orgId}/portalPhotos/${user.uid}.jpg`));
    photoVersionLoaded = projection.photoVersion;
    renderDashboard();
  } catch (error) {
    console.warn("portal photo unavailable", error?.code);
    photoUrl = "";
  }
}

function avatar() {
  const node = el("div", { class: "avatar", "aria-hidden": "true", text: photoUrl ? "" : initials() });
  if (photoUrl && /^(https?:)\/\//.test(photoUrl)) node.style.backgroundImage = `url("${photoUrl.replace(/"/g, "%22")}")`;
  return node;
}

function profileTile() {
  const p = projection;
  const editBtn = el("button", { type: "button", class: "mini-btn", onclick: openAccountDialog },
    icon("edit", 14), el("span", { text: t("edit") }));
  const card = tile("profile", t("personalInfo"), "user", { action: editBtn });
  const fullName = `${p.firstName || ""} ${p.lastName || ""}`.trim();
  card.append(el("div", { class: "profile-top" },
    avatar(),
    el("div", {}, el("p", { class: "profile-name", text: fullName }), el("p", { class: "profile-sub", text: p.email }))));
  card.append(kv([
    [t("phone"), p.phone],
    [t("dateOfBirth"), fmtIsoDate(p.dateOfBirth)],
    [t("gender"), p.gender ? t(`gender${p.gender}`) : "-"],
  ]));
  const status = requests.PROFILE;
  if (isWaiting(status)) {
    card.append(el("p", { class: "notice", text: t("profilePending") }));
  } else if (status?.status === "REJECTED" && millisOf(status.requestedAt) > Date.now() - 86_400_000) {
    card.append(el("p", { class: "error", text: t("profileRejected") }));
  }

  // QR code by e-mail: the wallet code itself never appears on this page.
  const qr = el("div", { class: "qr-block" });
  qr.append(el("div", { class: "tile-head" }, icon("qr", 18), el("h2", { text: t("qrTitle") })));
  if (!p.qrEmailAllowed) {
    qr.append(el("p", { class: "small-note", text: t("qrNotAllowed") }));
  } else {
    qr.append(el("p", { class: "muted left", text: t("qrBody", { email: p.email }) }));
    const r = requests.QR_EMAIL;
    const cooldown = requestCooldownMinutes("QR_EMAIL");
    const btn = el("button", { type: "button", class: "tonal", onclick: onQrClick }, icon("mail", 16), t("qrButton"));
    const waiting = isWaiting(r);
    if (waiting) {
      qr.append(el("p", { class: "notice", text: t("qrPending") }));
      btn.disabled = true;
    } else if (r?.status === "DONE" && millisOf(r.requestedAt) > Date.now() - 15 * 60_000) {
      qr.append(el("p", { class: "success", text: t("qrSent") }));
    } else if (r?.status === "REJECTED" && millisOf(r.requestedAt) > Date.now() - 86_400_000) {
      qr.append(el("p", { class: "error", text: t("qrRejected") }));
    }
    if (cooldown > 0 && !waiting) {
      btn.disabled = true;
      qr.append(el("p", { class: "small-note", text: t("qrWait", { n: cooldown }) }));
    }
    qr.append(btn);
  }
  card.append(qr);
  return card;
}

async function onQrClick(event) {
  const btn = event.currentTarget;
  btn.disabled = true;
  try {
    await sendRequest("QR_EMAIL");
  } catch (error) {
    console.warn("qr request failed", error?.code);
    btn.disabled = false;
  }
}

// ---------- Portal data (schema 2 timeline, schema 1 fallback) ----------

/** Last timeline step whose start has passed; the first step starts at 0, so there is one unless the list is empty. */
function currentStep(payload, now) {
  const steps = Array.isArray(payload?.timeline) ? payload.timeline : [];
  if (!steps.length) return null;
  let current = steps[0];
  steps.forEach((step) => {
    if (Number(step.from) <= now) current = step;
  });
  return current;
}

/** Shifts that already started (past) and the rest (upcoming), by comparing the shift date with now. */
function splitShifts(shifts, now) {
  const list = Array.isArray(shifts) ? shifts : [];
  return {
    past: list.filter((s) => Number(s.date) < now),
    upcoming: list.filter((s) => Number(s.date) >= now),
  };
}

/** Latest of the past shift dates and the published floor; 0 means no shift yet. */
function lastShiftAt(payload, now) {
  const { past } = splitShifts(payload?.shifts, now);
  return Math.max(0, Number(payload?.lastShiftFloor) || 0, ...past.map((s) => Number(s.date) || 0));
}

function stillValid(benefit, now) {
  return !(benefit?.validUntil != null && Number(benefit.validUntil) < now);
}

/** One shape for both payload versions, as of `now`. */
function portalView(payload, now) {
  if (Array.isArray(payload.timeline)) {
    const step = currentStep(payload, now);
    const { past, upcoming } = splitShifts(payload.shifts, now);
    const benefits = (step?.benefits || []).filter((b) => stillValid(b, now));
    const summary = step?.summary && stillValid(step.summary, now) ? step.summary : null;
    return {
      active: Boolean(step?.active),
      totalShifts: past.length,
      lastShiftAt: lastShiftAt(payload, now),
      entries: Number(step?.uniqueEntriesRemaining) || 0,
      summary,
      benefits,
      upcoming: upcoming.slice().sort((x, y) => Number(x.date) - Number(y.date)),
      past: past.slice().sort((x, y) => Number(y.date) - Number(x.date)),
      moreShifts: 0,
    };
  }
  const list = Array.isArray(payload.benefits) ? payload.benefits : [];
  return {
    active: Boolean(payload.active),
    totalShifts: Number(payload.totalShifts) || 0,
    lastShiftAt: Number(payload.lastShiftAt) || 0,
    entries: Number(payload.uniqueEntriesRemaining) || 0,
    summary: benefitSummary(list, payload),
    benefits: list,
    upcoming: Array.isArray(payload.upcomingShifts) ? payload.upcomingShifts : [],
    past: Array.isArray(payload.shifts) ? payload.shifts : [],
    moreShifts: Math.max(0, (Number(payload.shiftsTotal) || 0) - (Array.isArray(payload.shifts) ? payload.shifts.length : 0)),
  };
}

function infoTile(view) {
  const card = tile("info", t("volunteerInfo"), "star", { alt: true });
  const status = el("span", { class: `status-dot${view.active ? " on" : ""}`, text: view.active ? t("active") : t("inactive") });
  card.append(kv([
    [t("status"), status],
    [t("totalShifts"), String(view.totalShifts)],
    [t("lastShift"), view.lastShiftAt ? fmtDate(view.lastShiftAt) : "-"],
  ]));
  return card;
}

/** Schema 1: the combined benefits as the door and bar apply them, or a merge of the list. */
function benefitSummary(list, payload) {
  if (payload.summary && typeof payload.summary === "object") return payload.summary;
  if (!list.length) return null;
  return list.reduce((acc, b) => ({
    freeEntry: acc.freeEntry || Boolean(b.freeEntry),
    friendInvitation: acc.friendInvitation || Boolean(b.friendInvitation),
    inviteCount: Math.max(acc.inviteCount, Number(b.inviteCount) || 0),
    drinkTokens: Math.max(acc.drinkTokens, Number(b.drinkTokens) || 0),
    barDiscount: Math.max(acc.barDiscount, Number(b.barDiscount) || 0),
    guestListAccess: acc.guestListAccess || Boolean(b.guestListAccess),
    validUntil: Math.max(acc.validUntil, Number(b.validUntil) || 0),
  }), { freeEntry: false, friendInvitation: false, inviteCount: 0, drinkTokens: 0, barDiscount: 0, guestListAccess: false, validUntil: 0 });
}

function perkTile(iconName, value, label) {
  return el("div", { class: "perk-tile" },
    icon(iconName, 18),
    el("div", { class: "perk-value", text: value }),
    el("div", { class: "perk-label", text: label }));
}

function benefitsTile(view) {
  const card = tile("benefits", t("benefits"), "gift");
  const list = view.benefits;
  const entries = view.entries;
  if (entries > 0) {
    card.append(el("div", { class: "entries-banner" },
      el("div", { class: "entries-count", text: String(entries) }),
      el("div", {},
        el("div", { class: "entries-title", text: t("uniqueEntries") }),
        el("div", { class: "entries-hint", text: t("uniqueEntriesHint") }))));
  }
  if (!list.length) {
    if (!entries) card.append(el("p", { class: "empty", text: t("noBenefits") }));
    return card;
  }
  const s = view.summary;
  const perks = [];
  if (s) {
    if (s.freeEntry) perks.push(perkTile("ticket", t("included"), t("perkFreeEntry")));
    const invites = Number(s.inviteCount) || (s.friendInvitation ? 1 : 0);
    if (invites > 0) perks.push(perkTile("user", `+${invites}`, t("perkInvites")));
    if (Number(s.drinkTokens) > 0) perks.push(perkTile("drink", String(s.drinkTokens), t("perkDrinks")));
    if (Number(s.barDiscount) > 0) perks.push(perkTile("percent", `${s.barDiscount} %`, t("perkBarDiscount")));
    if (s.guestListAccess) perks.push(perkTile("list", t("included"), t("perkGuestList")));
  }
  if (perks.length) {
    const until = Number(s.validUntil) || Math.max(0, ...list.map((b) => Number(b.validUntil) || 0));
    card.append(el("div", { class: "section-row" },
      el("h3", { class: "list-title", text: t("nowTitle") }),
      until ? el("span", { class: "until", text: t("untilShort", { date: fmtDate(until) }) }) : null));
    card.append(el("div", { class: "perk-grid" }, perks));
  }
  const ul = el("ul", { class: "list benefit-list" });
  list.forEach((b) => {
    ul.append(el("li", {},
      el("span", { class: "grow meta-line", text: b.description || gainsText(b) }),
      b.validUntil ? el("span", { class: "until", text: t("untilShort", { date: fmtDate(b.validUntil) }) }) : null));
  });
  card.append(ul);
  return card;
}

/** Compact one-line wording of a benefit, using the same labels as the perk tiles. */
function gainsText(b) {
  const parts = [];
  if (b.freeEntry) parts.push(t("perkFreeEntry"));
  const invites = Number(b.inviteCount) || (b.friendInvitation ? 1 : 0);
  if (invites > 0) parts.push(`+${invites} ${t("perkInvites")}`);
  if (Number(b.drinkTokens) > 0) parts.push(`${b.drinkTokens} ${t("perkDrinks")}`);
  if (Number(b.barDiscount) > 0) parts.push(`${b.barDiscount} % ${t("perkBarDiscount")}`);
  if (b.guestListAccess) parts.push(t("perkGuestList"));
  return parts.join(" · ");
}

function walletTile() {
  const w = projection.wallet || {};
  const card = tile("wallet", t("wallet"), "wallet", { alt: true });
  card.append(el("div", { class: "balance-box" },
    el("div", { class: "label", text: t("balance") }),
    el("div", { class: "value", text: fmtMoney(w.balance, w.currency) })));
  card.append(el("h3", { class: "list-title", text: t("lastTransfers") }));
  const transfers = Array.isArray(w.transfers) ? w.transfers.slice(0, 5) : [];
  if (!transfers.length) {
    card.append(el("p", { class: "empty", text: t("noTransfers") }));
    return card;
  }
  const ul = el("ul", { class: "list" });
  transfers.forEach((tr) => {
    const label = String(tr.label || "").trim();
    const kind = t(`t${tr.type}`);
    ul.append(el("li", {},
      el("div", { class: "grow" },
        el("div", { class: "title", text: label || kind }),
        el("div", { class: "meta-line", text: [label ? kind : "", fmtDate(tr.at), tr.venue].filter(Boolean).join(" · ") })),
      el("span", { class: `amount ${Number(tr.amount) >= 0 ? "plus" : "minus"}`, text: fmtMoney(tr.amount, w.currency, true) })));
  });
  card.append(ul);
  return card;
}

function dateBadge(millis, soon = false) {
  const d = new Date(Number(millis));
  return el("div", { class: `date-badge${soon ? " soon" : ""}` },
    el("div", { class: "d", text: new Intl.DateTimeFormat(locale(), { day: "2-digit" }).format(d) }),
    el("div", { class: "m", text: new Intl.DateTimeFormat(locale(), { month: "short" }).format(d).replace(".", "") }));
}

function shiftMeta(s) {
  const date = new Date(Number(s.date));
  const rawDay = new Intl.DateTimeFormat(locale(), { weekday: "long" }).format(date);
  const weekday = rawDay.charAt(0).toUpperCase() + rawDay.slice(1);
  const year = date.getFullYear() !== new Date().getFullYear() ? String(date.getFullYear()) : "";
  return [
    [weekday, year].filter(Boolean).join(" "),
    s.venueName,
    s.shiftTime ? t(`shift${s.shiftTime}`) : "",
  ].filter(Boolean).join(" · ");
}

function gainLines(shift) {
  const lines = (Array.isArray(shift.gains) ? shift.gains : []).map(gainsText).filter(Boolean);
  if (!lines.length) return [];
  return [
    el("div", { class: "gains-label", text: t("shiftGains") }),
    ...lines.map((text) => el("div", { class: "gains-line", text })),
  ];
}

function upcomingTile(view) {
  const list = view.upcoming;
  if (!list.length) return null;
  const card = tile("upcoming", t("upcoming"), "calendar");
  const grid = el("div", { class: "upcoming-grid" });
  list.forEach((s) => {
    grid.append(el("div", { class: "upcoming-card" },
      dateBadge(s.date, true),
      el("div", { class: "grow" },
        el("div", { class: "title", text: s.jobTypeName }),
        el("div", { class: "meta-line", text: shiftMeta(s) }),
        ...gainLines(s))));
  });
  card.append(grid);
  return card;
}

function historyTile(view) {
  const list = view.past;
  const card = tile("history", t("history"), "clock", { alt: true });
  if (!list.length) {
    card.append(el("p", { class: "empty", text: t("noShifts") }));
    return card;
  }
  const ul = el("ul", { class: "list shift-list" });
  list.forEach((s) => {
    ul.append(el("li", {},
      dateBadge(s.date),
      el("div", { class: "grow" },
        el("div", { class: "title", text: s.jobTypeName }),
        el("div", { class: "meta-line", text: shiftMeta(s) }))));
  });
  card.append(ul);
  const more = view.moreShifts;
  if (more > 0) card.append(el("p", { class: "small-note", text: t("moreShifts", { n: more }) }));
  return card;
}

// ---------- "My account" window (details, password, data) ----------

function profileForm(onDone) {
  const p = projection;
  const first = el("input", { value: p.firstName || "", maxlength: "60", autocomplete: "given-name" });
  const last = el("input", { value: p.lastName || "", maxlength: "60", autocomplete: "family-name" });
  const phone = el("input", { type: "tel", value: p.phone || "", maxlength: "32", autocomplete: "tel" });
  const msg = el("p", { class: "hidden", role: "status" });
  const save = el("button", { type: "submit", class: "primary", text: t("save") });
  const form = el("form", { novalidate: true },
    el("div", { class: "row-2" },
      el("label", {}, el("span", { text: t("firstName") }), first),
      el("label", {}, el("span", { text: t("lastName") }), last)),
    el("label", {}, el("span", { text: t("phone") }), phone),
    msg,
    save);
  const say = (cls, key) => {
    msg.className = cls;
    msg.textContent = t(key);
  };
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    form.querySelectorAll("[aria-invalid]").forEach((n) => n.removeAttribute("aria-invalid"));
    const firstName = cleanName(first.value);
    const lastName = cleanName(last.value);
    const phoneValue = cleanPhone(phone.value);
    const fail = (key, input) => {
      say("error", key);
      input.setAttribute("aria-invalid", "true");
      input.focus();
    };
    if (!firstName) return fail("errName", first);
    if (!lastName) return fail("errName", last);
    if (!phoneValue) return fail("errPhone", phone);
    if (isWaiting(requests.PROFILE) || requestCooldownMinutes("PROFILE") > 0) return fail("profileWait", first);
    save.disabled = true;
    try {
      await sendRequest("PROFILE", { firstName, lastName, phone: phoneValue });
      say("success", "profileSent");
      onDone?.();
    } catch (error) {
      console.warn("profile request failed", error?.code);
      say("error", String(error?.code).includes("permission-denied") ? "profileWait" : "errGeneric");
    } finally {
      save.disabled = false;
    }
  });
  return form;
}

function passwordForm() {
  const current = el("input", { type: "password", autocomplete: "current-password", maxlength: "128" });
  const next = el("input", { type: "password", autocomplete: "new-password", maxlength: "128" });
  const next2 = el("input", { type: "password", autocomplete: "new-password", maxlength: "128" });
  const msg = el("p", { class: "hidden", role: "status" });
  const btn = el("button", { type: "submit", class: "ghost", text: t("changePassword") });
  const form = el("form", { novalidate: true },
    el("label", {}, el("span", { text: t("currentPassword") }), current),
    el("div", { class: "row-2" },
      el("label", {}, el("span", { text: t("newPassword") }), next),
      el("label", {}, el("span", { text: t("passwordConfirm") }), next2)),
    el("p", { class: "hint", text: t("passwordHint") }),
    msg,
    btn);
  const say = (cls, key) => {
    msg.className = cls;
    msg.textContent = t(key);
  };
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const problem = passwordProblem(next.value, user.email);
    if (problem) return say("error", problem);
    if (next.value !== next2.value) return say("error", "errPasswordMatch");
    btn.disabled = true;
    try {
      await reauthenticateWithCredential(user, EmailAuthProvider.credential(user.email, current.value));
      await updatePassword(user, next.value);
      current.value = next.value = next2.value = "";
      say("success", "passwordChanged");
    } catch (error) {
      const key = authErrorKey(error);
      say("error", key === "errSignIn" ? "errCurrentPassword" : key);
    } finally {
      btn.disabled = false;
    }
  });
  return form;
}

function openAccountDialog() {
  const dialog = $("account-dialog");
  const body = $("account-body");
  clear(body);
  body.append(
    el("section", { class: "dialog-section" },
      el("h3", { text: t("myInfo") }),
      el("p", { class: "hint", text: t("myInfoHint") }),
      profileForm()),
    el("details", { class: "dialog-section fold" },
      el("summary", {}, icon("shield", 16), el("span", { text: t("changePassword") })),
      passwordForm()),
    el("section", { class: "dialog-section quiet" },
      el("button", { type: "button", class: "link-btn", onclick: exportData }, t("exportData")),
      el("p", { class: "small-note", text: t("exportHint") }),
      el("p", { class: "small-note", text: t("signedInAs", { email: user.email }) })),
  );
  $("account-title").textContent = t("account");
  if (typeof dialog.showModal === "function") dialog.showModal();
  else dialog.setAttribute("open", "");
}

function exportData() {
  const data = {
    exportedAt: new Date().toISOString(),
    organization: path.orgId,
    account: { email: user.email },
    volunteer: projection,
  };
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = el("a", { href: url, download: "my-volunteer-data.json" });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

function renderDashboard() {
  if (!projection || !user) return;
  const view = portalView(projection, Date.now());
  $("dash-hello").textContent = t("hello", { name: projection.firstName || "" });
  const sub = [projection.association, projectionUpdatedAt ? t("updatedAgo", { ago: ago(projectionUpdatedAt) }) : ""]
    .filter(Boolean).join(" · ");
  $("dash-sub").textContent = sub;
  const dash = $("dash");
  clear(dash);
  const tiles = {
    profile: profileTile(),
    info: infoTile(view),
    benefits: benefitsTile(view),
    wallet: walletTile(),
    upcoming: upcomingTile(view),
    history: historyTile(view),
  };
  const columns = layoutColumns();
  dash.className = `dash cols-${columns}`;
  const col = (...names) => el("div", { class: "col" }, names.map((n) => tiles[n]).filter(Boolean));
  if (columns === 1) {
    dash.append(col("profile", "info", "benefits", "wallet", "upcoming", "history"));
  } else if (columns === 2) {
    dash.append(col("profile", "info", "upcoming"), col("benefits", "wallet"));
    tiles.history.classList.add("full");
    dash.append(tiles.history);
  } else {
    dash.append(col("profile", "info", "upcoming"), col("benefits", "wallet"), col("history"));
  }
  ensurePhoto();
}

const MEDIA_2 = window.matchMedia("(min-width: 760px)");
const MEDIA_3 = window.matchMedia("(min-width: 1500px) and (min-height: 760px)");

function layoutColumns() {
  if (MEDIA_3.matches) return 3;
  return MEDIA_2.matches ? 2 : 1;
}

// ---------- Installed from the browser menu ("Add to Home Screen") ----------

function isStandalone() {
  return window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone === true;
}

/** Installed app: staying signed in is the expected default (the user can still untick it). */
function rememberDefault() {
  const stored = localStorage.getItem(REMEMBER_KEY);
  return stored === null ? isStandalone() : stored === "1";
}

// ---------- Boot ----------

function bindStatic() {
  $("view-signin").addEventListener("submit", onSignIn);
  $("view-reset").addEventListener("submit", onReset);
  $("to-reset").addEventListener("click", () => {
    $("reset-email").value = $("signin-email").value;
    $("reset-done").classList.add("hidden");
    setError("reset-error");
    showView("view-reset");
  });
  $("reset-back").addEventListener("click", () => showView("view-signin"));
  document.querySelectorAll("[data-signout]").forEach((b) => b.addEventListener("click", () => doSignOut()));
  document.querySelectorAll(".pw-toggle").forEach((btn) => {
    btn.addEventListener("click", () => {
      const input = $(btn.dataset.target);
      input.type = input.type === "password" ? "text" : "password";
      btn.classList.toggle("on", input.type === "text");
    });
  });
  $("verify-resend").addEventListener("click", async (event) => {
    event.currentTarget.disabled = true;
    try {
      await sendEmailVerification(user, { url: location.href.split("#")[0] });
      $("verify-notice").textContent = t("resent");
    } catch (error) {
      $("verify-notice").textContent = t(authErrorKey(error));
    }
    $("verify-notice").classList.remove("hidden");
    setTimeout(() => { $("verify-resend").disabled = false; }, 30_000);
  });
  $("verify-check").addEventListener("click", async () => {
    await user.reload().catch(() => {});
    if (auth.currentUser?.emailVerified) {
      await onUser(auth.currentUser);
    } else {
      $("verify-notice").textContent = t("notVerifiedYet");
      $("verify-notice").classList.remove("hidden");
    }
  });
  $("pending-refresh").addEventListener("click", async () => {
    if (!user) return;
    claimSentAt = 0;
    await onUser(user);
  });
  ["pointerdown", "keydown", "scroll", "touchstart"].forEach((type) =>
    window.addEventListener(type, armIdleTimer, { passive: true }));
  [MEDIA_2, MEDIA_3].forEach((m) => m.addEventListener("change", () => renderDashboard()));
  $("account-close").addEventListener("click", () => $("account-dialog").close());
  $("account-dialog").addEventListener("click", (event) => {
    // A click on the backdrop (outside the panel) closes the window.
    if (event.target === $("account-dialog")) $("account-dialog").close();
  });
  // Keep "updated … ago" fresh.
  setInterval(() => { if (projection) renderDashboard(); }, 60_000);
}

async function main() {
  $("remember").checked = rememberDefault();
  bindLanguageSelect($("lang"), () => {
    if (auth) auth.languageCode = currentLang();
    if (user?.email) $("verify-body").textContent = t("verifyBody", { email: user.email });
    renderBranding();
    renderPending();
    renderDashboard();
  });
  path = parseOrgPath("v", false);
  if (!path) {
    // Opened from the home-screen icon (/v/): go back to the organisation last used here.
    const last = localStorage.getItem(LAST_ORG_KEY) || "";
    if (/^[A-Za-z0-9_-]{1,64}$/.test(last)) return location.replace(`/v/${encodeURIComponent(last)}`);
    return showView("view-disabled");
  }
  localStorage.setItem(LAST_ORG_KEY, path.orgId);
  let app;
  try {
    app = initializeApp(await loadConfig(t("hostingError")));
  } catch (error) {
    $("disabled-error").textContent = error.message;
    $("disabled-error").classList.remove("hidden");
    return showView("view-disabled");
  }
  const remember = rememberDefault();
  // No popup/redirect resolver: e-mail + password only, no third-party iframe.
  auth = initializeAuth(app, { persistence: remember ? browserLocalPersistence : browserSessionPersistence });
  db = getFirestore(app);
  storage = getStorage(app);
  if (isLocalDev()) {
    connectAuthEmulator(auth, `http://${location.hostname}:${EMULATOR_PORTS.auth}`, { disableWarnings: true });
    connectFirestoreEmulator(db, location.hostname, EMULATOR_PORTS.firestore);
    connectStorageEmulator(storage, location.hostname, EMULATOR_PORTS.storage);
  }
  auth.languageCode = currentLang();
  bindStatic();
  const open = await loadBranding();
  renderBranding();
  applyStaticI18n();
  if (!open) return showView("view-disabled");
  onAuthStateChanged(auth, (next) => {
    onUser(next).catch((error) => console.error("portal state failed", error));
  });
}

main();
