// Volunteer space: /v/{orgId}
// Reads only the signed-in volunteer's own projection (orgs/{org}/volunteerPortal/{uid}); every
// change goes through a request document that a NoctuList device validates and applies.
import { deleteApp, initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  EmailAuthProvider,
  GoogleAuthProvider,
  browserLocalPersistence,
  browserPopupRedirectResolver,
  browserSessionPersistence,
  connectAuthEmulator,
  inMemoryPersistence,
  initializeAuth,
  onAuthStateChanged,
  reauthenticateWithCredential,
  sendEmailVerification,
  sendPasswordResetEmail,
  setPersistence,
  signInWithEmailAndPassword,
  signInWithPopup,
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
  uploadBytes,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-storage.js";
import { EMULATOR_PORTS, isLocalDev, loadConfig, parseOrgPath } from "./firebase-env.js?v=portal-2";
import { applyStaticI18n, bindLanguageSelect, currentLang, locale, t } from "./portal-i18n.js?v=qr-3";
import { clearQr, closeQrDialog, isStaleFor, missingIsFinal, openQrDialog, readCachedQr, storeQr } from "./portal-qr.js?v=qr-2";
import {
  EMAIL_RE,
  authErrorKey,
  cleanName,
  cleanPhone,
  clear,
  el,
  icon,
  passwordProblem,
  toJpeg,
} from "./portal-common.js?v=portal-2";

const $ = (id) => document.getElementById(id);
const VIEWS = ["view-loading", "view-disabled", "view-signin", "view-reset", "view-verify", "view-pending", "view-google", "view-dash", "view-offline"];
const REMEMBER_KEY = "nl-portal-remember";
const LAST_ORG_KEY = "nl-portal-org";
const IDLE_LIMIT_MS = 30 * 60 * 1000;
const DELAYS_MIN = { PROFILE: 1, QR_EMAIL: 10, CLAIM: 1, GOOGLE_LINK: 1, GOOGLE_UNLINK: 1 };
const REQUEST_TYPES = ["PROFILE", "QR_EMAIL", "CLAIM", "GOOGLE_LINK", "GOOGLE_UNLINK"];
/** A device takes over a claim the Functions left alone after 2 minutes; past this the page stops waiting. */
const LINK_WAIT_MS = 3 * 60_000;
/** Messages for the answers to a Google claim (portalGoogleClaims). */
const ADMIN_MATCH_KEYS = { NO_MATCH: "googleNoMatch", DOMAIN: "googleDomain" };
const LINK_CODE_KEYS = { CODE: "googleCode", TAKEN: "googleTaken", ALREADY_LINKED: "googleAlreadyLinked", DOMAIN: "googleDomain" };
/** Same as the rules: a PROCESSING claim this old was abandoned and the request can be sent again. */
const PROCESSING_STALE_MS = 5 * 60_000;
/** Without any answer from Firestore for this long, a QR stored on the device is shown alone. */
const OFFLINE_FALLBACK_MS = 4000;

let path = null;
let firebaseConfig = null;
let auth = null;
let db = null;
let storage = null;
let branding = null;
let user = null;
/** Portal account the page reads and writes for: the signed-in uid, or the volunteer a Google account is linked to. */
let portalUid = "";
let projection = null;
let qrDoc = null;
let projectionUpdatedAt = 0;
let photoUrl = "";
let photoVersionLoaded = "";
let requests = {};
let unsubscribers = [];
let offlineTimer = null;
let idleTimer = null;
/** Google section of "My account": the node, the message of the last link attempt, a link in progress. */
let googleBox = null;
let googleMsg = null;
let linking = false;
/** Set when a Google session removes its own link: the page then loses access on purpose. */
let unlinkSent = false;

// ---------- Views ----------

function showView(id) {
  VIEWS.forEach((v) => $(v).classList.toggle("hidden", v !== id));
  document.body.classList.toggle("dash-open", id === "view-dash");
}

/** Reduced screen with only "My QR", from the copy stored on this device. */
function showOffline() {
  showView("view-offline");
}

/** Offline screen shown at boot: the page never started Firebase, so it reloads once the network is back. */
function showOfflineUntilOnline() {
  showOffline();
  window.addEventListener("online", () => location.reload(), { once: true });
}

function openOfflineQr() {
  const cached = readCachedQr(path.orgId);
  if (cached) openQrDialog({ payload: cached.payload, name: cached.name, association: cached.association });
}

/** The worker serves the page without network. Off on the emulator unless ?sw=1 is in the URL. */
function registerWorker() {
  if (!("serviceWorker" in navigator)) return;
  if (isLocalDev() && new URLSearchParams(location.search).get("sw") !== "1") {
    // A worker left by an earlier ?sw=1 run would keep serving old local files.
    navigator.serviceWorker.getRegistrations()
      .then((list) => list.forEach((reg) => reg.unregister()))
      .catch(() => {});
    return;
  }
  navigator.serviceWorker.register("/portal-sw.js", { scope: "/v/" }).catch(() => {});
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
    // "unavailable": no network, not a refusal.
    return String(error?.code).includes("unavailable") ? null : false;
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
  $("google-signin").classList.toggle("hidden", branding?.googleLogin !== true);
  document.title = name ? `${t("portalTitle")} · ${name}` : t("portalTitle");
}

// ---------- Auth flows ----------

async function onSignIn(event) {
  event.preventDefault();
  setError("signin-error");
  $("signin-notice").classList.add("hidden");
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
    const key = authErrorKey(error);
    // An association address belongs on the Google button, not in this form.
    setError("signin-error", key === "errSignIn" && branding?.googleLogin === true ? "errSignInGoogle" : key);
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

// ---------- Google ----------

function googleProvider() {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  return provider;
}

/** Sign-in method of the current user's ID token, read in onUser: true for a Google session. */
let googleSession = false;

/**
 * A Google session as the rules see it (`firebase.sign_in_provider` of the ID token), not from
 * providerData: an account can hold both a password and Google.
 */
function isGoogleUser(u) {
  return Boolean(u) && u === user && googleSession;
}

async function readGoogleSession(u) {
  try {
    return (await u.getIdTokenResult()).signInProvider === "google.com";
  } catch {
    // No token yet (offline): fall back to the providers of the account.
    const providers = (u?.providerData || []).map((p) => p.providerId);
    return providers.includes("google.com") && !providers.includes("password");
  }
}

/** Closing the popup or opening a second one is not an error worth showing. */
function popupDismissed(error) {
  const code = String(error?.code || "");
  return code.includes("popup-closed-by-user") || code.includes("cancelled-popup-request");
}

/**
 * Message for a failed Google sign-in or link, never the e-mail/password one: `[key, vars]`.
 * Unknown failures show the Firebase code so the association can tell what to fix.
 */
function googleError(error) {
  const code = String(error?.code || "");
  if (code.includes("popup-blocked")) return ["googlePopupBlocked"];
  if (code.includes("account-exists-with-different-credential")) return ["googleAccountExists"];
  if (code.includes("unauthorized-domain")) return ["googleUnauthorizedDomain"];
  if (code.includes("operation-not-allowed")) return ["googleNotEnabled"];
  if (code.includes("network-request-failed")) return ["errNetwork"];
  if (code.includes("too-many-requests")) return ["errTooMany"];
  return ["googleSignInFailed", { code: code.replace(/^auth\//, "") || "unknown" }];
}

async function onGoogleSignIn() {
  setError("signin-error");
  $("signin-notice").classList.add("hidden");
  const btn = $("google-btn");
  btn.disabled = true;
  try {
    // No await before the popup: browsers only allow it right after the click.
    // The persistence follows the "stay signed in" box as soon as it changes.
    await signInWithPopup(auth, googleProvider());
  } catch (error) {
    if (!popupDismissed(error)) {
      // The message names the real cause (wrong OAuth client, audience, secret): keep it for the association.
      console.warn("google sign-in failed", error?.code, error?.message, error?.customData?._serverResponse || "");
      setError("signin-error", ...googleError(error));
    }
  } finally {
    btn.disabled = false;
  }
}

/** The volunteer portal uid a Google account is linked to, or "" when there is no usable link. */
async function googleAliasTarget(googleUid) {
  try {
    const snap = await getDoc(doc(db, "orgs", path.orgId, "portalGoogleAccounts", googleUid));
    const target = snap.exists() ? String(snap.data().volunteerUid || "") : "";
    return /^[A-Za-z0-9_-]{1,128}$/.test(target) ? target : "";
  } catch (error) {
    console.warn("google link unavailable", error?.code);
    return "";
  }
}

async function openGoogleSession(current) {
  showView("view-google");
  renderGoogleWait();
  const target = await googleAliasTarget(current.uid);
  if (user !== current) return;
  if (target) {
    portalUid = target;
    return watchProjection();
  }
  await requestAdminMatch(current);
}

function renderGoogleWait() {
  if (user?.email) $("google-wait-body").textContent = t("googleWaitBody", { email: user.email });
}

/** Not linked yet: ask the team to match this address with the one an admin entered on a volunteer. */
async function requestAdminMatch(current) {
  const ref = doc(db, "orgs", path.orgId, "portalGoogleClaims", current.uid);
  let previous = null;
  try {
    const snap = await getDoc(ref);
    previous = snap.exists() ? snap.data() : null;
  } catch (error) {
    console.warn("google claim unreadable", error?.code);
  }
  if (user !== current) return;
  if (!(previous?.type === "ADMIN_MATCH" && isWaiting(previous))) {
    try {
      await setDoc(ref, { type: "ADMIN_MATCH", status: "PENDING", requestedAt: serverTimestamp(), email: current.email });
    } catch (error) {
      console.warn("google claim refused", error?.code);
      if (user !== current) return;
      // The rules refuse a new claim within a minute of the last one, and any claim from an
      // address outside the association's domains.
      const recent = previous && Date.now() - millisOf(previous.requestedAt) < 60_000;
      if (recent && previous.status === "REJECTED" && previous.type === "ADMIN_MATCH") {
        return doSignOut(ADMIN_MATCH_KEYS[previous.reason] || "googleFailed");
      }
      const denied = String(error?.code).includes("permission-denied");
      return doSignOut(denied && !recent ? "googleDomain" : "googleFailed");
    }
  }
  if (user !== current) return;
  // Nobody answered (no device online, Functions down): stop waiting rather than spin forever.
  const timer = setTimeout(() => {
    if (user === current && !portalUid) doSignOut("googleSlow");
  }, LINK_WAIT_MS);
  unsubscribers.push(() => clearTimeout(timer));
  unsubscribers.push(onSnapshot(ref, async (snap) => {
    const claim = snap.exists() ? snap.data() : null;
    if (!claim || snap.metadata.hasPendingWrites || user !== current || portalUid) return;
    if (claim.status === "REJECTED") {
      doSignOut(ADMIN_MATCH_KEYS[claim.reason] || "googleFailed");
    } else if (claim.status === "DONE") {
      const target = await googleAliasTarget(current.uid);
      if (user !== current || portalUid) return;
      if (!target) return doSignOut("googleFailed");
      stopListeners();
      portalUid = target;
      watchProjection();
    }
  }, (error) => {
    console.warn("google claim unavailable", error?.code);
    if (user === current && !portalUid) doSignOut("googleFailed");
  }));
}

/** URL-safe one-time code: 32 random bytes in base64url (43 characters). */
function randomCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * A separate Firebase app for the Google account being linked, so the portal session stays as it is.
 * Made when the "Link" button is shown, so the click only has to open the popup: Safari and iOS
 * block a popup that waits on the sign-in frame first (Firebase prepares it as soon as Auth starts
 * on those browsers).
 */
let linkSession = null;

function prepareLinkSession() {
  if (!linkSession) linkSession = linkInstance();
  return linkSession;
}

/** Signs the second instance out and deletes it; a link waiting for its answer stops quietly. */
async function disposeLinkSession() {
  const session = linkSession;
  linkSession = null;
  if (!session) return;
  session.aborted = true;
  session.stopWaiting?.();
  await signOut(session.auth).catch(() => {});
  await deleteApp(session.app).catch(() => {});
}

function linkInstance() {
  const app = initializeApp(firebaseConfig, `google-link-${Date.now()}`);
  const linkAuth = initializeAuth(app, { persistence: inMemoryPersistence, popupRedirectResolver: browserPopupRedirectResolver });
  const linkDb = getFirestore(app);
  if (isLocalDev()) {
    connectAuthEmulator(linkAuth, `http://${location.hostname}:${EMULATOR_PORTS.auth}`, { disableWarnings: true });
    connectFirestoreEmulator(linkDb, location.hostname, EMULATOR_PORTS.firestore);
  }
  linkAuth.languageCode = currentLang();
  return { app, auth: linkAuth, db: linkDb, aborted: false, stopWaiting: null };
}

/** Resolves with the answered claim, or null when nobody answered in time or the wait was stopped. */
function waitForClaim(session, ref) {
  return new Promise((resolve, reject) => {
    let unsubscribe = () => {};
    const timer = setTimeout(() => {
      unsubscribe();
      resolve(null);
    }, LINK_WAIT_MS);
    session.stopWaiting = () => {
      clearTimeout(timer);
      unsubscribe();
      resolve(null);
    };
    unsubscribe = onSnapshot(ref, (snap) => {
      const claim = snap.exists() ? snap.data() : null;
      if (!claim || snap.metadata.hasPendingWrites || !["DONE", "REJECTED"].includes(claim.status)) return;
      clearTimeout(timer);
      unsubscribe();
      resolve(claim);
    }, (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

/** Password session: link an association Google account with a one-time code (GOOGLE_LINK + LINK_CODE). */
async function onLinkClick() {
  if (linking) return;
  // A GOOGLE_LINK request stays pending until a claim uses its code: only the rules' 1-minute
  // throttle stops a new attempt.
  if (requestCooldownMinutes("GOOGLE_LINK") > 0) {
    googleMsg = { cls: "notice", key: "googleWait" };
    return renderGoogleBox();
  }
  const second = prepareLinkSession();
  const code = randomCode();
  // The popup opens first, while the click still allows it; the request only has to exist
  // before the claim that carries the code.
  const attempt = Promise.allSettled([
    signInWithPopup(second.auth, googleProvider()),
    sha256Hex(code).then((codeHash) => sendRequest("GOOGLE_LINK", { codeHash })),
  ]);
  linking = true;
  googleMsg = { cls: "notice", key: "googleLinking" };
  renderGoogleBox();
  try {
    const [popup, sent] = await attempt;
    if (second.aborted) return;
    if (popup.status === "rejected") throw popup.reason;
    if (sent.status === "rejected") throw sent.reason;
    const google = popup.value.user;
    const ref = doc(second.db, "orgs", path.orgId, "portalGoogleClaims", google.uid);
    try {
      await setDoc(ref, { type: "LINK_CODE", status: "PENDING", requestedAt: serverTimestamp(), email: google.email, code });
    } catch (error) {
      console.warn("google link claim refused", error?.code);
      if (second.aborted) return;
      const previous = await getDoc(ref).then((s) => (s.exists() ? s.data() : null)).catch(() => null);
      const recent = previous && Date.now() - millisOf(previous.requestedAt) < 60_000;
      googleMsg = { cls: "error", key: recent ? "googleWait" : "googleDomain" };
      return;
    }
    let answer;
    try {
      answer = await waitForClaim(second, ref);
    } catch (error) {
      console.warn("google link claim unavailable", error?.code);
      if (!second.aborted) googleMsg = { cls: "error", key: "googleFailed" };
      return;
    }
    if (second.aborted) return;
    if (!answer) googleMsg = { cls: "notice", key: "googleLinkLater" };
    else if (answer.status === "DONE") googleMsg = { cls: "success", key: "googleLinkDone" };
    else googleMsg = { cls: "error", key: LINK_CODE_KEYS[answer.reason] || "googleFailed" };
  } catch (error) {
    if (second.aborted) return;
    if (popupDismissed(error)) {
      googleMsg = null;
    } else {
      console.warn("google link failed", error?.code);
      const [key, vars] = String(error?.code).includes("permission-denied") ? ["googleWait"] : googleError(error);
      googleMsg = { cls: "error", key, vars };
    }
  } finally {
    // One instance per attempt: a new one is prepared when the button is shown again.
    if (linkSession === second) await disposeLinkSession();
    linking = false;
    renderGoogleBox();
  }
}

async function onUnlinkClick() {
  if (isWaiting(requests.GOOGLE_UNLINK) || requestCooldownMinutes("GOOGLE_UNLINK") > 0) {
    googleMsg = { cls: "notice", key: "googleWait" };
    return renderGoogleBox();
  }
  googleMsg = null;
  // A Google session loses its access once the link is gone: say why instead of "turned off".
  if (isGoogleUser(user)) unlinkSent = true;
  try {
    await sendRequest("GOOGLE_UNLINK");
  } catch (error) {
    console.warn("google unlink failed", error?.code);
    unlinkSent = false;
    googleMsg = { cls: "error", key: String(error?.code).includes("permission-denied") ? "googleWait" : "errGeneric" };
  }
  renderGoogleBox();
}

/** "My account": link an association Google account, or remove the one the volunteer linked. */
function renderGoogleBox() {
  const box = googleBox;
  if (!box || !box.isConnected) return;
  clear(box);
  const p = projection || {};
  const email = String(p.googleEmail || "").trim();
  // Empty text node when there is nothing to say: Node.append would print "null".
  const say = () => (googleMsg ? el("p", { class: googleMsg.cls, role: "status", text: t(googleMsg.key, googleMsg.vars) }) : "");
  if (email && p.googleLinkedBy === "VOLUNTEER") {
    const unlink = requests.GOOGLE_UNLINK;
    const waiting = isWaiting(unlink);
    const btn = el("button", { type: "button", class: "ghost", onclick: onUnlinkClick, text: t("googleUnlink") });
    btn.disabled = waiting;
    box.append(
      el("h3", { text: t("googleSection") }),
      el("p", { class: "hint", text: t("googleLinked", { email }) }));
    if (waiting) {
      box.append(el("p", { class: "notice", text: t("googleUnlinkPending") }));
    } else if (unlink?.status === "REJECTED" && millisOf(unlink.requestedAt) > Date.now() - 86_400_000) {
      box.append(el("p", { class: "error", text: t(unlink.reason === "ADMIN_LINK" ? "googleUnlinkAdmin" : "errGeneric") }));
    } else {
      box.append(say());
    }
    box.append(btn);
  } else if (!email && p.googleLinkAvailable === true && !isGoogleUser(user)) {
    const btn = el("button", { type: "button", class: "google-btn", onclick: onLinkClick }, googleMark(), t("googleLinkButton"));
    btn.disabled = linking;
    if (!linking && $("account-dialog").open) prepareLinkSession();
    box.append(
      el("h3", { text: t("googleSection") }),
      el("p", { class: "hint", text: t("googleLinkHint") }),
      say(),
      btn);
  } else if (googleMsg?.cls === "success") {
    // Linked while the window was open: keep the confirmation until the page catches up.
    box.append(el("h3", { text: t("googleSection") }), say());
  } else {
    box.classList.add("hidden");
    return;
  }
  box.classList.remove("hidden");
}

/** Google "G" mark, drawn with DOM nodes (no HTML strings). */
function googleMark() {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 48 48");
  svg.setAttribute("width", "18");
  svg.setAttribute("height", "18");
  svg.setAttribute("aria-hidden", "true");
  [
    ["#EA4335", "M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"],
    ["#4285F4", "M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"],
    ["#FBBC05", "M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"],
    ["#34A853", "M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"],
  ].forEach(([fill, d]) => {
    const pathNode = document.createElementNS(ns, "path");
    pathNode.setAttribute("fill", fill);
    pathNode.setAttribute("d", d);
    svg.append(pathNode);
  });
  return svg;
}

async function doSignOut(reasonKey) {
  if ($("account-dialog").open) $("account-dialog").close();
  closeQrDialog();
  disposeLinkSession();
  stopListeners();
  clearQr(path.orgId);
  portalUid = "";
  unlinkSent = false;
  projection = null;
  qrDoc = null;
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
  clearTimeout(offlineTimer);
  offlineTimer = null;
}

function armIdleTimer() {
  if (idleTimer) clearTimeout(idleTimer);
  if (!user) return;
  idleTimer = setTimeout(() => {
    // Signing out offline would wipe the only copy of the QR: wait until the network is back.
    if (!navigator.onLine) return armIdleTimer();
    doSignOut("idleSignOut");
  }, IDLE_LIMIT_MS);
}

async function onUser(next) {
  stopListeners();
  user = next;
  googleSession = false;
  armIdleTimer();
  portalUid = "";
  qrDoc = null; // watchProjection fills it again
  if (!user) {
    // Session ended (signed out elsewhere, expired, tab session gone): no QR without a session.
    if (path) clearQr(path.orgId);
    $("signin-email").value = $("signin-email").value || "";
    return showView("view-signin");
  }
  // Read again for every new sign-in: the token says how this session signed in.
  const google = await readGoogleSession(next);
  if (user !== next) return;
  googleSession = google;
  // Another account's QR must not stay on this device. Google sessions are keyed by the linked
  // volunteer uid, not by the auth uid, so watchProjection replaces their copy instead.
  if (isStaleFor(readCachedQr(path.orgId), next.uid, google)) clearQr(path.orgId);
  // Google accounts are verified by Google; their access goes through the link to a volunteer.
  if (isGoogleUser(user)) return openGoogleSession(user);
  if (!user.emailVerified) {
    $("verify-body").textContent = t("verifyBody", { email: user.email });
    return showView("view-verify");
  }
  // Make sure the ID token carries email_verified = true (verified in another tab).
  await user.getIdToken(true).catch(() => {});
  if (user !== next) return;
  portalUid = user.uid;
  watchProjection();
}

function watchProjection() {
  showView("view-loading");
  const uidAtStart = portalUid;
  clearTimeout(offlineTimer);
  offlineTimer = setTimeout(() => {
    offlineTimer = null;
    if (portalUid === uidAtStart && readCachedQr(path.orgId, uidAtStart)) showOffline();
  }, OFFLINE_FALLBACK_MS);
  const ref = doc(db, "orgs", path.orgId, "volunteerPortal", portalUid);
  unsubscribers.push(onSnapshot(ref, (snap) => {
    // An empty answer from the local cache (no network) says nothing about the page: with a QR
    // stored here, the offline screen stays or the timer above shows it.
    if (!snap.exists() && snap.metadata.fromCache && readCachedQr(path.orgId, uidAtStart)) return;
    clearTimeout(offlineTimer);
    offlineTimer = null;
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
    renderGoogleBox();
    showView("view-dash");
  }, (error) => {
    console.warn("projection unavailable", error?.code);
    if (String(error?.code).includes("permission-denied") && isGoogleUser(user)) {
      // The Google option, the domain or the link itself was removed.
      doSignOut(unlinkSent ? "googleUnlinked" : "googleDisabled");
    } else if (String(error?.code).includes("permission-denied")) {
      $("disabled-error").classList.add("hidden");
      showView("view-disabled");
    }
  }));
  const qrRef = doc(db, "orgs", path.orgId, "volunteerPortalQr", portalUid);
  unsubscribers.push(onSnapshot(qrRef, (snap) => {
    if (snap.exists() && snap.data().payload) {
      const data = snap.data();
      qrDoc = { payload: data.payload, name: data.name || "" };
      // Kept on the device only for a remembered session: a tab session ends without a sign-out.
      if (rememberDefault()) {
        storeQr(path.orgId, { uid: uidAtStart, payload: data.payload, name: qrDoc.name, association: projection?.association || "" });
      } else {
        clearQr(path.orgId);
      }
    } else if (missingIsFinal(snap)) {
      qrDoc = null;
      clearQr(path.orgId);
      closeQrDialog();
    }
    if (projection) renderDashboard();
  }, (error) => {
    if (String(error?.code).includes("permission-denied")) {
      qrDoc = null;
      clearQr(path.orgId);
      closeQrDialog();
      if (projection) renderDashboard();
    }
  }));
  REQUEST_TYPES.forEach((type) => {
    const ref = doc(db, "orgs", path.orgId, "portalRequests", `${portalUid}_${type}`);
    unsubscribers.push(onSnapshot(ref, (snap) => {
      requests[type] = snap.exists() ? snap.data() : null;
      if (type === "CLAIM") renderPending();
      else if (type.startsWith("GOOGLE_")) renderGoogleBox();
      else if (projection) renderDashboard();
    }, () => {}));
  });
}

let claimSentAt = 0;

async function showPending() {
  showView("view-pending");
  renderPending();
  // Only an e-mail + password session may claim a profile.
  if (isGoogleUser(user)) return;
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
  // A Google session writes for the volunteer it is linked to, with its own (Google) address.
  const ref = doc(db, "orgs", path.orgId, "portalRequests", `${portalUid}_${type}`);
  await setDoc(ref, {
    uid: portalUid,
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
    photoUrl = await getDownloadURL(storageRef(storage, `orgs/${path.orgId}/portalPhotos/${portalUid}.jpg`));
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
  if (qrDoc) {
    const shown = { payload: qrDoc.payload, name: qrDoc.name || projection?.firstName || "", association: projection?.association || "" };
    qr.append(el("button", {
      type: "button",
      class: "primary qr-open",
      onclick: () => openQrDialog(shown),
    }, icon("qr", 18), el("span", { text: t("myQr") })));
  }
  if (!p.qrEmailAllowed) {
    if (!qrDoc) qr.append(el("p", { class: "small-note", text: t("qrNotAllowed") }));
  } else {
    if (!qrDoc) qr.append(el("p", { class: "muted left", text: t("qrBody", { email: p.email }) }));
    const r = requests.QR_EMAIL;
    const cooldown = requestCooldownMinutes("QR_EMAIL");
    const btn = el("button", { type: "button", class: qrDoc ? "link-small" : "tonal", onclick: onQrClick }, icon("mail", qrDoc ? 14 : 16), t("qrButton"));
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
    // Past shifts left out of the list by the 60 cap still count in the total.
    const older = Math.max(0, Number(payload.olderShifts) || 0);
    return {
      active: Boolean(step?.active),
      totalShifts: past.length + older,
      lastShiftAt: lastShiftAt(payload, now),
      entries: Number(step?.uniqueEntriesRemaining) || 0,
      summary,
      benefits,
      upcoming: upcoming.slice().sort((x, y) => Number(x.date) - Number(y.date)),
      past: past.slice().sort((x, y) => Number(y.date) - Number(x.date)),
      moreShifts: older,
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

/** One line of "Right now": what the volunteer gets, then how much. */
function perkRow(label, value) {
  return el("li", {}, el("span", { class: "perk-name", text: label }), el("span", { class: "perk-amount", text: value }));
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
    if (s.freeEntry) perks.push(perkRow(t("perkFreeEntry"), t("included")));
    const invites = Number(s.inviteCount) || (s.friendInvitation ? 1 : 0);
    if (invites > 0) perks.push(perkRow(t("perkInvites"), `+${invites}`));
    if (Number(s.drinkTokens) > 0) perks.push(perkRow(t("perkDrinks"), String(s.drinkTokens)));
    if (Number(s.barDiscount) > 0) perks.push(perkRow(t("perkBarDiscount"), `${s.barDiscount} %`));
    // Free entry already gets you in: the guest list only matters without it.
    if (s.guestListAccess && !s.freeEntry) perks.push(perkRow(t("perkGuestList"), t("included")));
  }
  if (perks.length) {
    const until = Number(s.validUntil) || Math.max(0, ...list.map((b) => Number(b.validUntil) || 0));
    card.append(el("div", { class: "section-row" },
      el("h3", { class: "list-title", text: t("nowTitle") }),
      until ? el("span", { class: "until", text: t("untilShort", { date: fmtDate(until) }) }) : null));
    card.append(el("ul", { class: "perk-list" }, perks));
  }
  const ul = el("ul", { class: "list benefit-list" });
  list.forEach((b) => {
    ul.append(el("li", {},
      el("span", { class: "grow meta-line", text: benefitLine(b) }),
      b.validUntil ? el("span", { class: "until", text: t("untilShort", { date: fmtDate(b.validUntil) }) }) : null));
  });
  card.append(ul);
  return card;
}

/**
 * A benefit in the visitor's language. The app's own `description` is English and stays unused:
 * the structured fields say the same thing.
 */
function benefitLine(b) {
  const text = gainsText(b);
  const entries = Number(b.futureEntriesRemaining) || 0;
  const extra = entries > 0 ? `${entries} ${t("perkSingleEntries")}` : "";
  return [text, extra].filter(Boolean).join(" · ") || t("perkSpecial");
}

/** Compact one-line wording of a benefit, using the same labels as "Right now". */
function gainsText(b) {
  const parts = [];
  if (b.freeEntry) parts.push(t("perkFreeEntry"));
  const invites = Number(b.inviteCount) || (b.friendInvitation ? 1 : 0);
  if (invites > 0) parts.push(`+${invites} ${t("perkInvites")}`);
  if (Number(b.drinkTokens) > 0) parts.push(`${b.drinkTokens} ${t("perkDrinks")}`);
  if (Number(b.barDiscount) > 0) parts.push(`${b.barDiscount} % ${t("perkBarDiscount")}`);
  if (b.guestListAccess && !b.freeEntry) parts.push(t("perkGuestList"));
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

/** The team's devices publish whether profile photos are on; the Storage rules refuse uploads otherwise. */
function photosEnabled() {
  return branding?.photosEnabled === true;
}

/** Current photo with a button to pick a new one; the picked JPEG is kept until the form is sent. */
function photoPicker(onError) {
  let blob = null;
  let previewUrl = "";
  const preview = avatar();
  preview.classList.add("avatar-edit");
  const file = el("input", { type: "file", accept: "image/*", class: "hidden" });
  const choose = el("button", { type: "button", class: "ghost", text: t(photoUrl ? "changePhoto" : "choosePhoto") });
  choose.addEventListener("click", () => file.click());
  file.addEventListener("change", async () => {
    onError("");
    const picked = file.files && file.files[0];
    file.value = "";
    if (!picked) return;
    if (picked.type && !picked.type.startsWith("image/")) return onError("notImage");
    try {
      blob = await toJpeg(picked);
    } catch {
      return onError("unreadable");
    }
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = URL.createObjectURL(blob);
    preview.textContent = "";
    preview.style.backgroundImage = `url("${previewUrl}")`;
    choose.textContent = t("changePhoto");
  });
  const node = el("div", { class: "photo-edit" }, preview, choose, file);
  return { node, picked: () => blob };
}

function profileForm(onDone) {
  const p = projection;
  const first = el("input", { value: p.firstName || "", maxlength: "60", autocomplete: "given-name" });
  const last = el("input", { value: p.lastName || "", maxlength: "60", autocomplete: "family-name" });
  const phone = el("input", { type: "tel", value: p.phone || "", maxlength: "32", autocomplete: "tel" });
  const msg = el("p", { class: "hidden", role: "status" });
  const save = el("button", { type: "submit", class: "primary", text: t("save") });
  const say = (cls, key) => {
    msg.className = cls;
    msg.textContent = t(key);
  };
  const photo = photosEnabled()
    ? photoPicker((key) => (key ? say("error", key) : (msg.className = "hidden")))
    : null;
  const form = el("form", { novalidate: true },
    photo?.node ?? null,
    el("div", { class: "row-2" },
      el("label", {}, el("span", { text: t("firstName") }), first),
      el("label", {}, el("span", { text: t("lastName") }), last)),
    el("label", {}, el("span", { text: t("phone") }), phone),
    msg,
    save);
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
    const blob = photo?.picked() ?? null;
    if (blob) {
      try {
        const upload = storageRef(storage, `orgs/${path.orgId}/portalPhotoUploads/${portalUid}.jpg`);
        await uploadBytes(upload, blob, { contentType: "image/jpeg" });
      } catch (error) {
        console.warn("photo upload failed", error?.code);
        say("error", "photoUploadFailed");
        save.disabled = false;
        return;
      }
    }
    try {
      await sendRequest("PROFILE", { firstName, lastName, phone: phoneValue, ...(blob ? { photo: true } : {}) });
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
  googleMsg = null;
  googleBox = el("section", { class: "dialog-section hidden" });
  body.append(...[
    el("section", { class: "dialog-section" },
      el("h3", { text: t("myInfo") }),
      profileForm()),
    // The password belongs to the e-mail sign-in; a Google session cannot change it.
    isGoogleUser(user) ? null : el("details", { class: "dialog-section fold" },
      el("summary", {}, icon("shield", 16), el("span", { text: t("changePassword") })),
      passwordForm()),
    googleBox,
    el("section", { class: "dialog-section quiet" },
      el("button", { type: "button", class: "link-btn", onclick: exportData }, t("exportData")),
      el("p", { class: "small-note", text: t("exportHint") }),
      el("p", { class: "small-note", text: t("signedInAs", { email: user.email }) })),
  ].filter(Boolean));
  $("account-title").textContent = t("account");
  if (typeof dialog.showModal === "function") dialog.showModal();
  else dialog.setAttribute("open", "");
  // After opening: the Google section prepares its sign-in instance only while the window is open.
  renderGoogleBox();
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
  $("google-btn").addEventListener("click", onGoogleSignIn);
  // Applied right away, so the Google popup can open without waiting on anything after the click.
  $("remember").addEventListener("change", () => {
    const remember = $("remember").checked;
    localStorage.setItem(REMEMBER_KEY, remember ? "1" : "0");
    setPersistence(auth, remember ? browserLocalPersistence : browserSessionPersistence).catch(() => {});
  });
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
  // Closing "My account" stops a link in progress and drops the second Auth instance.
  $("account-dialog").addEventListener("close", () => {
    googleBox = null;
    disposeLinkSession();
    linking = false;
  });
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
    renderGoogleWait();
    renderDashboard();
    renderGoogleBox();
  });
  registerWorker();
  path = parseOrgPath("v", false);
  if (!path) {
    // Opened from the home-screen icon (/v/): go back to the organisation last used here.
    const last = localStorage.getItem(LAST_ORG_KEY) || "";
    if (/^[A-Za-z0-9_-]{1,64}$/.test(last)) return location.replace(`/v/${encodeURIComponent(last)}`);
    return showView("view-disabled");
  }
  localStorage.setItem(LAST_ORG_KEY, path.orgId);
  $("offline-qr").prepend(icon("qr", 18));
  $("offline-qr").addEventListener("click", openOfflineQr);
  if (!navigator.onLine && readCachedQr(path.orgId)) return showOfflineUntilOnline();
  let app;
  try {
    const config = await loadConfig(t("hostingError"));
    // authDomain is this site: Hosting serves /__/auth/ itself, so the Google popup and its frame
    // stay on this origin instead of firebaseapp.com. The emulator keeps its own handler.
    firebaseConfig = isLocalDev() ? config : { ...config, authDomain: location.host };
    app = initializeApp(firebaseConfig);
  } catch (error) {
    // fetch rejects with a TypeError when the network is down.
    if (error instanceof TypeError && readCachedQr(path.orgId)) return showOfflineUntilOnline();
    $("disabled-error").textContent = error.message;
    $("disabled-error").classList.remove("hidden");
    return showView("view-disabled");
  }
  const remember = rememberDefault();
  // The popup resolver is for "Sign in with Google"; its frame comes from authDomain (this site).
  auth = initializeAuth(app, {
    persistence: remember ? browserLocalPersistence : browserSessionPersistence,
    popupRedirectResolver: browserPopupRedirectResolver,
  });
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
  if (open === null && readCachedQr(path.orgId)) return showOfflineUntilOnline();
  if (!open) return showView("view-disabled");
  onAuthStateChanged(auth, (next) => {
    onUser(next).catch((error) => console.error("portal state failed", error));
  });
}

main();
