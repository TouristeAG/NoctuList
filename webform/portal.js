// Portal: /v/{orgId}
// Reads only the signed-in account's own pages (orgs/{org}/volunteerPortal, guestPortal and
// temporaryGuestPortal under its uid); every change goes through a request document that a
// NoctuList device validates and applies. The temporary group page is read-only.
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
  collection,
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
import { applyStaticI18n, bindLanguageSelect, currentLang, locale, t } from "./portal-i18n.js?v=guest-2";
import { cachedQrEntries, clearQr, closeQrDialog, isStaleFor, missingIsFinal, openQrDialog, readCachedQr, storeQr } from "./portal-qr.js?v=guest-2";
import {
  EMAIL_RE,
  authErrorKey,
  chooseProfile,
  inviteRemaining,
  profilesLayout,
  qrDialogClosesFor,
  visibleLists,
  claimCanAsk,
  claimReloadKey,
  claimRetryDelays,
  cleanName,
  claimWithPages,
  cleanPhone,
  clear,
  el,
  icon,
  isStalePending,
  isWaiting,
  millisOf,
  passwordProblem,
  profileKinds,
  profileStoreKey,
  profilesSettled,
  toJpeg,
} from "./portal-common.js?v=guest-2";

const $ = (id) => document.getElementById(id);
const VIEWS = ["view-loading", "view-disabled", "view-signin", "view-reset", "view-verify", "view-pending", "view-google", "view-dash", "view-offline"];
const REMEMBER_KEY = "nl-portal-remember";
const LAST_ORG_KEY = "nl-portal-org";
const IDLE_LIMIT_MS = 30 * 60 * 1000;
const DELAYS_MIN = { PROFILE: 1, QR_EMAIL: 10, GUEST_PROFILE: 1, GUEST_QR_EMAIL: 10, CLAIM: 1, GOOGLE_LINK: 1, GOOGLE_UNLINK: 1, TEMP_GUEST_NAME: 1 };
const REQUEST_TYPES = ["PROFILE", "QR_EMAIL", "GUEST_PROFILE", "GUEST_QR_EMAIL", "CLAIM", "GOOGLE_LINK", "GOOGLE_UNLINK", "TEMP_GUEST_NAME"];
/** The only request an unverified session of a list account may send (rules v18). */
const UNVERIFIED_REQUEST_TYPES = ["TEMP_GUEST_NAME"];
/** The published page and QR document of each profile an account may have. */
const PAGES = {
  volunteer: { page: "volunteerPortal", qr: "volunteerPortalQr" },
  guest: { page: "guestPortal", qr: "guestPortalQr" },
  temporary: { page: "temporaryGuestPortal", qr: "temporaryGuestPortalQr" },
};
const PROFILE_LABELS = { volunteer: "profileVolunteer", guest: "profileGuest", temporary: "profileTemporary" };
/** A device takes over a claim the Functions left alone after 2 minutes; past this the page stops waiting. */
const LINK_WAIT_MS = 3 * 60_000;
/** Messages for the answers to a Google claim (portalGoogleClaims). */
const ADMIN_MATCH_KEYS = { NO_MATCH: "googleNoMatch", DOMAIN: "googleDomain" };
const LINK_CODE_KEYS = { CODE: "googleCode", TAKEN: "googleTaken", ALREADY_LINKED: "googleAlreadyLinked", DOMAIN: "googleDomain" };
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
/** Each profile read for this session: `{ state: "loading" | "present" | "absent" | "denied", data, updatedAt }`. */
let pages = {};
/** QR documents by profile: `{ payload, name }`, or `{ items }` for the temporary groups. */
let qrDocs = {};
/** Profile on screen ("volunteer", "guest" or "temporary"), null until one is shown. */
let activeKind = null;
/** Payload of the volunteer or guest page on screen, null for the groups page. */
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

/** Reduced screen with only the QR codes stored on this device, one button each. */
function showOffline() {
  renderOffline();
  showView("view-offline");
}

function renderOffline() {
  const box = $("offline-list");
  clear(box);
  const entries = path ? cachedQrEntries(readCachedQr(path.orgId)) : [];
  const own = entries.filter((e) => e.kind !== "temporary");
  entries.forEach((entry) => {
    // Your own code alone is "My QR"; otherwise each button says whose it is.
    const label = entry.kind === "temporary" ? entry.name || t("qrShort")
      : own.length === 1 ? t("myQr") : t(PROFILE_LABELS[entry.kind]);
    box.append(el("button", {
      type: "button",
      class: "primary full qr-open",
      onclick: () => openQrDialog({ payload: entry.payload, name: entry.name, association: entry.association }),
    }, icon("qr", 18), el("span", { text: label })));
  });
}

/** Offline screen shown at boot: the page never started Firebase, so it reloads once the network is back. */
function showOfflineUntilOnline() {
  showOffline();
  window.addEventListener("online", () => location.reload(), { once: true });
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
    return watchProfiles(profileKinds({ google: true }));
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
      watchProfiles(profileKinds({ google: true }));
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
  claimHandled = "";
  resetProfiles();
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
  // A new account (or none) starts a new session for the one CLAIM sent while pages are shown.
  if ((next?.uid || "") !== claimSessionUid) {
    claimSessionUid = next?.uid || "";
    sessionClaimSent = false;
  }
  user = next;
  googleSession = false;
  armIdleTimer();
  portalUid = "";
  resetProfiles(); // watchProfiles fills them again
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
  // portal uid, not by the auth uid, so watchProfiles replaces their copy instead.
  if (isStaleFor(readCachedQr(path.orgId), next.uid, google)) clearQr(path.orgId);
  // Google accounts are verified by Google; their access goes through the link to a volunteer.
  if (isGoogleUser(user)) return openGoogleSession(user);
  portalUid = user.uid;
  // An unverified address may still own a temporary group page; without one it must verify first.
  if (!user.emailVerified) return watchProfiles(profileKinds({ google: false, verified: false }));
  // Make sure the ID token carries email_verified = true (verified in another tab).
  await user.getIdToken(true).catch(() => {});
  if (user !== next) return;
  watchProfiles(profileKinds({ google: false, verified: true }));
}

function showVerify() {
  $("verify-body").textContent = t("verifyBody", { email: user?.email || "" });
  showView("view-verify");
}

/** Profile whose code the QR dialog shows, so only that profile's QR going away closes it. */
let qrShownKind = null;
/** Kinds whose QR document is being listened to. */
let qrWatched = {};

function showQr(kind, args) {
  qrShownKind = kind;
  openQrDialog(args);
}

function resetProfiles() {
  pages = {};
  qrDocs = {};
  qrWatched = {};
  activeKind = null;
  projection = null;
  qrDoc = null;
  projectionUpdatedAt = 0;
}

/** Reads every page of `kinds` for portalUid at once; refused or missing means the profile is absent. */
function watchProfiles(kinds) {
  showView("view-loading");
  const uidAtStart = portalUid;
  resetProfiles();
  kinds.forEach((kind) => { pages[kind] = { state: "loading", data: null, updatedAt: 0 }; });
  clearTimeout(offlineTimer);
  offlineTimer = setTimeout(() => {
    offlineTimer = null;
    if (portalUid === uidAtStart && !activeKind && readCachedQr(path.orgId, uidAtStart)) showOffline();
  }, OFFLINE_FALLBACK_MS);
  kinds.forEach((kind) => {
    watchPage(kind, uidAtStart);
    qrWatched[kind] = true;
    watchQr(kind, uidAtStart);
  });
  // Requests need a verified address or a Google link, except a name change on a list account.
  const types = kinds.includes("volunteer") ? REQUEST_TYPES : UNVERIFIED_REQUEST_TYPES;
  types.forEach((type) => {
    const ref = doc(db, "orgs", path.orgId, "portalRequests", `${portalUid}_${type}`);
    unsubscribers.push(onSnapshot(ref, (snap) => {
      requests[type] = snap.exists() ? snap.data() : null;
      if (type === "CLAIM") {
        renderPending();
        reloadAfterClaim(uidAtStart);
        claimIfPagesShown();
      }
      else if (type.startsWith("GOOGLE_")) renderGoogleBox();
      else render();
    }, () => {}));
  });
}

function watchPage(kind, uidAtStart) {
  const ref = doc(db, "orgs", path.orgId, PAGES[kind].page, uidAtStart);
  unsubscribers.push(onSnapshot(ref, (snap) => {
    // An empty answer from the local cache (no network) says nothing about the page: with a QR
    // stored here, the offline screen stays or the timer in watchProfiles shows it.
    if (!snap.exists() && snap.metadata.fromCache && readCachedQr(path.orgId, uidAtStart)) return;
    let data = null;
    try {
      data = snap.exists() ? JSON.parse(snap.data().payload || "{}") : null;
    } catch {
      data = null;
    }
    if (!data || typeof data !== "object") return setPage(kind, "absent");
    setPage(kind, "present", data, Number(snap.data().updatedAt || 0));
    storeKindQr(kind, uidAtStart);
    // A page read again after a claim gets its QR listener back only once it is readable.
    if (!qrWatched[kind]) {
      qrWatched[kind] = true;
      watchQr(kind, uidAtStart);
    }
  }, (error) => {
    console.warn(`${kind} page unavailable`, error?.code);
    // The temporary page is refused, not missing, once it is gone or past its end.
    setPage(kind, String(error?.code).includes("permission-denied") ? "denied" : "absent");
  }));
}

function watchQr(kind, uidAtStart) {
  const ref = doc(db, "orgs", path.orgId, PAGES[kind].qr, uidAtStart);
  const drop = () => {
    qrDocs[kind] = null;
    clearQr(path.orgId, kind, uidAtStart);
    if (qrDialogClosesFor(qrShownKind, kind)) closeQrDialog();
    render();
  };
  unsubscribers.push(onSnapshot(ref, (snap) => {
    const data = snap.exists() ? snap.data() : null;
    const value = data ? qrValue(kind, data) : null;
    if (value) {
      qrDocs[kind] = value;
      storeKindQr(kind, uidAtStart);
      render();
    } else if (missingIsFinal(snap) || data) {
      drop();
    }
  }, (error) => {
    if (String(error?.code).includes("permission-denied")) {
      qrWatched[kind] = false;
      drop();
    }
  }));
}

/** The QR document as the page uses it, or null when it holds no code. */
function qrValue(kind, data) {
  if (kind !== "temporary") return data.payload ? { payload: String(data.payload), name: String(data.name || "") } : null;
  const items = {};
  Object.entries(data.items && typeof data.items === "object" ? data.items : {}).forEach(([key, item]) => {
    if (/^[A-Za-z0-9_-]{1,64}$/.test(key) && typeof item?.payload === "string" && item.payload) {
      items[key] = { payload: item.payload, name: String(item.name || "") };
    }
  });
  return Object.keys(items).length ? { items } : null;
}

/** Kept on the device only for a remembered session: a tab session ends without a sign-out. */
function storeKindQr(kind, uid) {
  if (!rememberDefault()) return clearQr(path.orgId);
  const q = qrDocs[kind];
  if (!q || uid !== portalUid) return;
  const page = pages[kind]?.data || {};
  const association = String(page.association || "");
  if (kind === "temporary") {
    storeQr(path.orgId, uid, kind, Object.entries(q.items).map(([key, item]) => ({ key, ...item, association })));
  } else {
    storeQr(path.orgId, uid, kind, { payload: q.payload, name: q.name || String(page.firstName || ""), association });
  }
}

function setPage(kind, state, data = null, updatedAt = 0) {
  if (!pages[kind]) return;
  pages[kind] = { state, data, updatedAt };
  showProfiles();
}

function pageStates() {
  return Object.fromEntries(Object.entries(pages).map(([kind, page]) => [kind, page.state]));
}

/** Shows the chosen profile once every page answered, or what to do when there is none. */
function showProfiles() {
  const { done, available } = profilesSettled(pageStates());
  if (!available.length) {
    if (!done) return;
    clearTimeout(offlineTimer);
    offlineTimer = null;
    activeKind = null;
    projection = null;
    qrDoc = null;
    return showNoProfile();
  }
  // Wait for every answer, so the page does not jump from one profile to another.
  if (!done) return;
  clearTimeout(offlineTimer);
  offlineTimer = null;
  // The profile on screen stays while it exists; the stored choice only decides the first one.
  const choices = profileChoices(available);
  if (!choices.includes(activeKind)) activeKind = chooseProfile(choices, storedProfile());
  render();
  renderGoogleBox();
  showView("view-dash");
  claimIfPagesShown();
}

function showNoProfile() {
  if (!user) return;
  closeQrDialog();
  if (!pages.volunteer) return showVerify();
  const refused = pages.volunteer.state === "denied";
  if (isGoogleUser(user) && refused) {
    // The Google option, the domain or the link itself was removed.
    return doSignOut(unlinkSent ? "googleUnlinked" : "googleDisabled");
  }
  if (refused) {
    $("disabled-error").classList.add("hidden");
    return showView("view-disabled");
  }
  showPending();
}

/** Key of the last DONE claim whose pages were read again, so each answer reloads only once. */
let claimHandled = "";

/**
 * A DONE claim linked this account to a profile whose page was missing, and so refused (the
 * temporary page, whose listener then stops): read the refused pages again on a short backoff,
 * since the page can be published minutes after the answer. Also while a profile is shown: the
 * claim sent with pages on screen can link another kind, which then joins the selector.
 */
function reloadAfterClaim(uid) {
  const key = claimReloadKey(requests.CLAIM, claimHandled);
  if (!key) return;
  claimHandled = key;
  const delays = claimRetryDelays();
  let timer = null;
  unsubscribers.push(() => clearTimeout(timer));
  const tick = (step) => {
    if (portalUid !== uid) return;
    Object.entries(pages).forEach(([kind, page]) => {
      if (page.state !== "denied") return;
      page.state = "loading";
      // Its QR is listened to again by watchPage once the page reads (a refused QR would close the dialog).
      watchPage(kind, uid);
    });
    arm(step + 1);
  };
  // The next try waits for the answers of this one and only runs while a page is still refused.
  const arm = (step) => {
    if (step >= delays.length) return;
    timer = setTimeout(() => {
      if (portalUid !== uid) return;
      const states = Object.values(pages).map((p) => p.state);
      // Still waiting for the last answers: look again later, as one more step of the schedule.
      if (states.includes("loading")) return arm(step + 1);
      if (states.includes("denied")) tick(step);
    }, delays[step]);
  };
  tick(0);
}

function storedProfile() {
  try {
    return localStorage.getItem(profileStoreKey(path.orgId));
  } catch {
    return null;
  }
}

function selectProfile(kind) {
  if (kind === activeKind) return;
  try {
    localStorage.setItem(profileStoreKey(path.orgId), kind);
  } catch {
    /* the choice is only remembered for this visit */
  }
  activeKind = kind;
  render();
  renderGoogleBox();
  window.scrollTo({ top: 0 });
}

/** Points projection and qrDoc at the profile on screen, then draws it. */
function render() {
  if (!activeKind || !pages[activeKind]) return;
  const page = pages[activeKind];
  projection = activeKind === "temporary" ? null : page.data;
  qrDoc = activeKind === "temporary" ? null : qrDocs[activeKind] || null;
  projectionUpdatedAt = page.updatedAt;
  renderDashboard();
}

/** Guests send GUEST_PROFILE and GUEST_QR_EMAIL, volunteers PROFILE and QR_EMAIL. */
function requestType(base) {
  return activeKind === "guest" ? `GUEST_${base}` : base;
}

let claimSentAt = 0;
/** Uid of the session [sessionClaimSent] is about, and whether that session sent a CLAIM. */
let claimSessionUid = "";
let sessionClaimSent = false;

async function showPending() {
  showView("view-pending");
  renderPending();
  // Only an e-mail + password session may claim a profile.
  if (isGoogleUser(user)) return;
  // Ask the team's devices to link this verified address to a volunteer profile.
  if (claimCanAsk(requests.CLAIM, claimSentAt)) {
    claimSentAt = Date.now();
    sessionClaimSent = true;
    await sendRequest("CLAIM").catch((error) => console.warn("claim failed", error?.code));
  }
}

/**
 * Pages are on screen: a verified password session still sends one CLAIM, so a profile opened
 * for this address after the account existed gets linked. No pending screen is shown for it; a
 * kind it links appears through its listener or reloadAfterClaim.
 */
function claimIfPagesShown() {
  if (!user || !activeKind || !pages.volunteer) return;
  const ask = claimWithPages({
    google: isGoogleUser(user),
    verified: user.emailVerified === true,
    claim: requests.CLAIM,
    sentThisSession: sessionClaimSent,
    lastSentAt: claimSentAt,
  });
  if (!ask) return;
  claimSentAt = Date.now();
  sessionClaimSent = true;
  sendRequest("CLAIM").catch((error) => console.warn("claim failed", error?.code));
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
  // Guests have no date of birth or gender.
  card.append(kv(activeKind === "guest" ? [[t("phone"), p.phone]] : [
    [t("phone"), p.phone],
    [t("dateOfBirth"), fmtIsoDate(p.dateOfBirth)],
    [t("gender"), p.gender ? t(`gender${p.gender}`) : "-"],
  ]));
  const status = requests[requestType("PROFILE")];
  if (isWaiting(status)) {
    card.append(el("p", { class: "notice", text: t("profilePending") }));
  } else if (isStalePending(status)) {
    card.append(el("p", { class: "notice", text: t("profileStale") }));
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
      onclick: () => showQr(activeKind, shown),
    }, icon("qr", 18), el("span", { text: t("myQr") })));
  }
  if (!p.qrEmailAllowed) {
    if (!qrDoc) qr.append(el("p", { class: "small-note", text: t("qrNotAllowed") }));
  } else {
    if (!qrDoc) qr.append(el("p", { class: "muted left", text: t("qrBody", { email: p.email }) }));
    const r = requests[requestType("QR_EMAIL")];
    const cooldown = requestCooldownMinutes(requestType("QR_EMAIL"));
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
    await sendRequest(requestType("QR_EMAIL"));
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

/** One line of "Right now": what the volunteer gets, then how much when it is counted. */
function perkRow(label, value = "") {
  return el("li", {},
    el("span", { class: "perk-name", text: label }),
    value ? el("span", { class: "perk-amount", text: value }) : null);
}

function benefitsTile(view) {
  const card = tile("benefits", t("benefits"), "gift");
  const list = view.benefits;
  const entries = view.entries;
  const s = view.summary;
  // Invitations come with the unique entries: shown together, not as a separate perk.
  const invites = s ? Number(s.inviteCount) || (s.friendInvitation ? 1 : 0) : 0;
  if (entries > 0) {
    const title = invites > 0 ? `${t("uniqueEntries")} (${t("withInvites", { n: invites })})` : t("uniqueEntries");
    card.append(el("div", { class: "entries-banner" },
      el("div", { class: "entries-count", text: String(entries) }),
      el("div", {},
        el("div", { class: "entries-title", text: title }),
        el("div", { class: "entries-hint", text: t("uniqueEntriesHint") }))));
  }
  // Drink tokens stay out: the bar applies them, the volunteer has nothing to do with the number.
  const perks = [];
  if (s && list.length) {
    if (s.freeEntry) perks.push(perkRow(t("perkFreeEntry")));
    if (invites > 0 && entries <= 0) perks.push(perkRow(t("perkInvites"), `+${invites}`));
    if (Number(s.barDiscount) > 0) perks.push(perkRow(t("perkBarDiscount"), `${s.barDiscount} %`));
    // Free entry already gets you in: the guest list only matters without it.
    if (s.guestListAccess && !s.freeEntry) perks.push(perkRow(t("perkGuestList")));
  }
  if (perks.length) {
    const until = Number(s.validUntil) || Math.max(0, ...list.map((b) => Number(b.validUntil) || 0));
    card.append(el("div", { class: "section-row" },
      el("h3", { class: "list-title", text: t("nowTitle") }),
      until ? el("span", { class: "until", text: t("untilShort", { date: fmtDate(until) }) }) : null));
    card.append(el("ul", { class: "perk-list" }, perks));
  } else if (!entries) {
    card.append(el("p", { class: "empty", text: t("noBenefits") }));
  }
  return card;
}

/** Compact one-line wording of a benefit, using the same labels as "Right now". */
function gainsText(b) {
  const parts = [];
  const invites = Number(b.inviteCount) || (b.friendInvitation ? 1 : 0);
  // The +n guests come in with the free entry: "Free entry (+2)".
  if (b.freeEntry) parts.push(invites > 0 ? `${t("perkFreeEntry")} (+${invites})` : t("perkFreeEntry"));
  else if (invites > 0) parts.push(`+${invites} ${t("perkInvites")}`);
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

// ---------- Guest page ----------

/** A permanent guest's benefits: bar discount, invitations and venue, as the door and bar apply them. */
function guestBenefitsTile() {
  const p = projection;
  const card = tile("benefits", t("benefits"), "gift");
  const perks = [];
  const discount = Number(p.barDiscountPercent) || 0;
  const invites = Number(p.invitations) || 0;
  const venue = String(p.venueName || "").trim();
  if (discount > 0) perks.push(perkRow(t("perkBarDiscount"), `${discount} %`));
  if (invites > 0) perks.push(perkRow(t("perkInvites"), String(invites)));
  if (venue) perks.push(perkRow(t("venue"), venue));
  if (perks.length) card.append(el("ul", { class: "perk-list" }, perks));
  else card.append(el("p", { class: "empty", text: t("noBenefits") }));
  return card;
}

// ---------- Temporary guest lists (spec 2026-11-21 §6) ----------

/** A window built on demand, like "My account": title, body, closed and removed by its button. */
function openSheet(title, body) {
  const close = el("button", { type: "button", class: "icon-btn", "aria-label": t("close"), text: "✕" });
  const dialog = el("dialog", { class: "sheet" },
    el("div", { class: "sheet-head" }, el("h2", { text: title }), close),
    el("div", { class: "sheet-body" }, body));
  const done = () => {
    if (typeof dialog.close === "function") dialog.close();
    dialog.remove();
  };
  close.addEventListener("click", done);
  dialog.addEventListener("close", () => dialog.remove());
  document.body.append(dialog);
  if (typeof dialog.showModal === "function") dialog.showModal();
  else dialog.setAttribute("open", "");
  return done;
}

function guestName(g) {
  return `${g.firstName || ""} ${g.lastName || ""}`.trim();
}

/** Small labelled block of a guest card ("Access", "Balance", …). */
function guestModule(label, ...lines) {
  const content = lines.filter(Boolean);
  if (!content.length) return null;
  return el("div", { class: "guest-module" },
    el("div", { class: "module-label", text: label }),
    ...content.map((line) => (line instanceof Node ? line : el("div", { class: "module-value", text: line }))));
}

function badge(text, kind = "") {
  return el("span", { class: `guest-badge${kind ? ` ${kind}` : ""}`, text });
}

/** Waiting answers of a list, by guest key (removals) and as plain names (additions). */
function pendingOf(group) {
  const list = Array.isArray(group.pending) ? group.pending : [];
  const removing = new Set();
  const adds = [];
  list.forEach((p) => {
    const names = Array.isArray(p.names) ? p.names.map(String) : [];
    if (p.kind === "REMOVE" && p.status === "PENDING_REVIEW") (p.guestKeys || []).forEach((k) => removing.add(String(k)));
    if (p.kind !== "REMOVE") names.forEach((name) => adds.push({ name, status: String(p.status || "") }));
  });
  return { removing, adds };
}

function guestCard(g, group, ctx) {
  const name = guestName(g);
  const key = String(g.guestKey || "");
  const removing = ctx.pending.removing.has(key);
  const used = g.entryUsed === true;
  const item = g.qr === true ? ctx.items[key] : null;
  const date = Number(group.eventDate) || 0;
  const head = el("div", { class: "guest-head" },
    el("div", { class: "guest-name", text: name || "-" }),
    removing ? badge(t("removalPending"), "wait") : null,
    !used && !removing ? el("button", {
      type: "button",
      class: "mini-btn",
      onclick: () => openNameDialog(g),
    }, icon("edit", 14), el("span", { text: t("edit") })) : null);
  const accessNames = Array.isArray(g.accessNames) ? g.accessNames.map(String).filter(Boolean) : [];
  const phone = String(g.emergencyPhone || "").trim();
  let qrModule;
  if (used) {
    qrModule = el("div", { class: "guest-module qr-module" }, badge(t("entryUsed"), "used"));
  } else if (item) {
    qrModule = el("div", { class: "guest-module qr-module" }, el("button", {
      type: "button",
      class: "primary qr-open",
      onclick: () => showQr("temporary", { payload: item.payload, name: item.name || name, association: ctx.association }),
    }, icon("qr", 18), el("span", { text: t("qrShort") })));
  }
  const modules = el("div", { class: "guest-modules" },
    guestModule(t("access"),
      date ? fmtDate(date, { weekday: "short", day: "numeric", month: "long" }) : "",
      [String(group.venueName || "").trim(), ...accessNames].filter(Boolean).join(" · ")),
    g.wallet ? guestModule(t("balance"), el("div", { class: "module-value big", text: fmtMoney(g.wallet.balance, g.wallet.currency) })) : null,
    phone ? guestModule(t("emergencyContactLabel"), phone) : null,
    qrModule);
  const card = el("li", { class: "guest-card" }, head, modules);
  if (group.invite && !used && !removing) {
    card.append(el("button", {
      type: "button",
      class: "link-btn danger-text",
      onclick: () => confirmRemove(g, group),
      text: t("removeGuest"),
    }));
  }
  return card;
}

function pendingCard(entry) {
  const refused = entry.status === "REJECTED";
  return el("li", { class: `guest-card pending${refused ? " refused" : ""}` },
    el("div", { class: "guest-head" },
      el("div", { class: "guest-name", text: entry.name || "-" }),
      badge(refused ? t("inviteRefused") : t("invitePending"), refused ? "refused" : "wait")));
}

function inviteBlock(group) {
  const invite = group.invite;
  if (!invite) return null;
  const accesses = Array.isArray(invite.accesses) ? invite.accesses : [];
  return el("div", { class: "invite-block" },
    el("div", { class: "invite-count", text: t("placesLeft", { n: Number(invite.remaining || 0), max: Number(invite.maxGuests || 0) }) }),
    accesses.length ? el("div", { class: "chips" }, ...accesses.map((a) => el("span", {
      class: "perk",
      text: Number(a.max) > 0 ? t("accessLeft", { name: a.name, n: Number(a.remaining || 0), max: Number(a.max) }) : String(a.name || ""),
    }))) : null,
    el("button", {
      type: "button",
      class: "tonal",
      disabled: Number(invite.remaining || 0) <= 0,
      onclick: () => openInviteDialog(group),
    }, icon("plus", 16), el("span", { text: t("addGuests") })));
}

function listCard(group, association, items) {
  const date = Number(group.eventDate) || 0;
  const artist = String(group.artistName || "").trim();
  const venue = String(group.venueName || "").trim();
  const card = tile("group", artist || venue || fmtDate(date), "calendar");
  const when = date ? fmtDate(date, { weekday: "long", day: "numeric", month: "long", year: "numeric" }) : "";
  card.querySelector(".tile-head").after(el("p", {
    class: "group-meta",
    text: [when, artist ? venue : ""].filter(Boolean).join(" · "),
  }));
  const ctx = { association, items, pending: pendingOf(group) };
  const block = inviteBlock(group);
  if (block) card.append(block);
  const guests = Array.isArray(group.guests) ? group.guests : [];
  const ul = el("ul", { class: "guest-cards" },
    ...guests.map((g) => guestCard(g, group, ctx)),
    ...ctx.pending.adds.map((entry) => pendingCard(entry)));
  if (!guests.length && !ctx.pending.adds.length) card.append(el("p", { class: "empty", text: t("noGuestsYet") }));
  else card.append(ul);
  return card;
}

function listCards() {
  const p = pages.temporary?.data;
  if (!p) return [];
  const association = String(p.association || "");
  const items = qrDocs.temporary?.items || {};
  return visibleLists(p.groups, Date.now()).map((g) => listCard(g, association, items));
}

/** "Guests I manage", under a volunteer or permanent guest profile. */
function appendManagedLists(dash) {
  if (pages.temporary?.state !== "present") return;
  const cards = listCards();
  if (!cards.length) return;
  dash.append(el("section", { class: "managed full" },
    el("h2", { class: "managed-title", text: t("managedTitle") }),
    el("div", { class: "managed-lists" }, ...cards)));
}

function renderGroups() {
  const p = pages.temporary?.data;
  if (!p) return;
  const association = String(p.association || "");
  $("dash-hello").textContent = t("profileTemporary");
  $("dash-sub").textContent = [association, String(p.contactEmail || ""), projectionUpdatedAt ? t("updatedAgo", { ago: ago(projectionUpdatedAt) }) : ""]
    .filter(Boolean).join(" · ");
  const cards = listCards();
  const dash = $("dash");
  clear(dash);
  if (!cards.length) {
    dash.className = "dash cols-1";
    dash.append(el("section", { class: "card tile" }, el("p", { class: "empty", text: t("noGroups") })));
    return;
  }
  const columns = Math.min(layoutColumns(), 2, cards.length);
  dash.className = `dash cols-${columns}`;
  const cols = Array.from({ length: columns }, () => el("div", { class: "col" }));
  cards.forEach((c, i) => cols[i % columns].append(c));
  dash.append(...cols);
}

/** Message line of a window form. */
function formMessage() {
  const msg = el("p", { class: "hidden", role: "status" });
  const say = (cls, key, params) => {
    msg.className = cls;
    msg.textContent = t(key, params);
  };
  return { msg, say };
}

/** First and last name of one guest: TEMP_GUEST_NAME, answered by the server or a device. */
function openNameDialog(g) {
  const first = el("input", { value: g.firstName || "", maxlength: "60", autocomplete: "off" });
  const last = el("input", { value: g.lastName || "", maxlength: "60", autocomplete: "off" });
  const { msg, say } = formMessage();
  const save = el("button", { type: "submit", class: "primary", text: t("save") });
  const form = el("form", { novalidate: true },
    el("div", { class: "row-2" },
      el("label", {}, el("span", { text: t("firstName") }), first),
      el("label", {}, el("span", { text: t("lastName") }), last)),
    msg,
    save);
  const close = openSheet(t("editName"), form);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const raw = [first.value.trim(), last.value.trim()];
    const firstName = raw[0] ? cleanName(raw[0]) : "";
    const lastName = raw[1] ? cleanName(raw[1]) : "";
    if (firstName === null || lastName === null || (!firstName && !lastName)) return say("error", "errName");
    const type = "TEMP_GUEST_NAME";
    if (isWaiting(requests[type])) return say("error", "profilePending");
    if (requestCooldownMinutes(type) > 0) return say("error", "profileWait");
    save.disabled = true;
    try {
      await sendRequest(type, { guestKey: String(g.guestKey || ""), firstName, lastName });
      say("success", "nameSent");
      setTimeout(close, 1200);
    } catch (error) {
      console.warn("name request failed", error?.code);
      say("error", String(error?.code).includes("permission-denied") ? "profileWait" : "errGeneric");
    } finally {
      save.disabled = false;
    }
  });
}

/** Common fields of an answer, copied from the template as the rules compare them. */
async function inviteBase(group) {
  const formId = String(group.invite?.formId || "");
  const snap = await getDoc(doc(db, "orgs", path.orgId, "guestForms", formId));
  if (!snap.exists()) throw Object.assign(new Error("template missing"), { code: "not-found" });
  const tpl = snap.data();
  const ref = doc(collection(db, "orgs", path.orgId, "guestForms"));
  const now = Date.now();
  return {
    ref,
    data: {
      formId: ref.id,
      parentFormId: formId,
      portalUid: String(tpl.portalUid || ""),
      status: "PENDING_REVIEW",
      venueName: tpl.venueName,
      eventName: tpl.eventName,
      eventDateMillis: tpl.eventDateMillis,
      artistName: tpl.artistName,
      offeredAccessIds: tpl.offeredAccessIds,
      maxGuests: tpl.maxGuests,
      expiresAtMillis: tpl.expiresAtMillis,
      submittedAt: now,
      lastModified: now,
    },
  };
}

/** One name per line, accesses to tick per person; sent for review like a guest form answer. */
function openInviteDialog(group) {
  const invite = group.invite;
  const accesses = Array.isArray(invite?.accesses) ? invite.accesses : [];
  const rows = el("div", { class: "invite-rows" });
  const { msg, say } = formMessage();
  const left = el("p", { class: "hint" });
  const draft = () => Array.from(rows.querySelectorAll(".invite-row")).map((row) => ({
    name: row.querySelector("input[type=text]").value.trim(),
    access: Array.from(row.querySelectorAll("input[type=checkbox]:checked")).map((c) => c.value),
  })).filter((p) => p.name);
  const refresh = () => {
    const r = inviteRemaining(invite, draft());
    left.textContent = t("placesLeft", { n: Math.max(r.remaining, 0), max: Number(invite?.maxGuests || 0) });
    return r;
  };
  const addRow = () => {
    const row = el("div", { class: "invite-row" },
      el("input", { type: "text", maxlength: "60", autocomplete: "off", "aria-label": t("guestName"), placeholder: t("guestName") }),
      accesses.length ? el("div", { class: "chips" }, ...accesses.map((a) => el("label", { class: "check-chip" },
        el("input", { type: "checkbox", value: a.id }), el("span", { text: a.name })))) : null);
    row.addEventListener("input", refresh);
    row.addEventListener("change", refresh);
    rows.append(row);
    row.querySelector("input").focus();
  };
  const more = el("button", { type: "button", class: "ghost", onclick: addRow, text: t("addRow") });
  const send = el("button", { type: "submit", class: "primary", text: t("sendForReview") });
  const form = el("form", { novalidate: true }, rows, more, left, msg, send);
  const close = openSheet(t("addGuests"), form);
  addRow();
  refresh();
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const people = draft();
    if (!people.length) return say("error", "errName");
    if (people.some((p) => cleanName(p.name) === null)) return say("error", "errName");
    if (!refresh().ok) return say("error", "invitesFull");
    send.disabled = true;
    try {
      const { ref, data } = await inviteBase(group);
      await setDoc(ref, {
        ...data,
        requestKind: "ADD",
        removeGuestKeys: "",
        submissionJson: JSON.stringify({ people: people.map((p) => ({ name: cleanName(p.name), access: p.access })) }),
      });
      say("success", "invitesSent");
      setTimeout(close, 1200);
    } catch (error) {
      console.warn("invite failed", error?.code);
      say("error", "errGeneric");
    } finally {
      send.disabled = false;
    }
  });
}

function confirmRemove(g, group) {
  const { msg, say } = formMessage();
  const yes = el("button", { type: "button", class: "danger", text: t("removeGuest") });
  const body = el("div", {}, el("p", { text: t("removeConfirm", { name: guestName(g) }) }), msg, yes);
  const close = openSheet(t("removeGuest"), body);
  yes.addEventListener("click", async () => {
    yes.disabled = true;
    try {
      const { ref, data } = await inviteBase(group);
      await setDoc(ref, { ...data, requestKind: "REMOVE", removeGuestKeys: String(g.guestKey || ""), submissionJson: "" });
      say("success", "invitesSent");
      setTimeout(close, 1200);
    } catch (error) {
      console.warn("removal failed", error?.code);
      say("error", "errGeneric");
      yes.disabled = false;
    }
  });
}

/** Buttons to move between the profiles of one account, shown only when there are several. */
/** Personal profiles in the selector; the lists page only when the account has nothing else. */
function profileChoices(available) {
  const { personal, managed } = profilesLayout(available);
  return personal.length ? personal : managed ? ["temporary"] : [];
}

function renderSwitch() {
  const box = $("profile-switch");
  clear(box);
  const available = profileChoices(profilesSettled(pageStates()).available);
  if (available.length < 2) {
    box.classList.add("hidden");
    return;
  }
  box.setAttribute("aria-label", t("profileSwitch"));
  available.forEach((kind) => {
    box.append(el("button", {
      type: "button",
      class: `switch-btn${kind === activeKind ? " on" : ""}`,
      "aria-pressed": kind === activeKind ? "true" : "false",
      onclick: () => selectProfile(kind),
      text: t(PROFILE_LABELS[kind]),
    }));
  });
  box.classList.remove("hidden");
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
  if (view.totalShifts > 0 || view.lastShiftAt) {
    card.append(el("div", { class: "stat-row" },
      el("div", { class: "stat" },
        el("div", { class: "value", text: String(view.totalShifts) }),
        el("div", { class: "label", text: t("totalShifts") })),
      el("div", { class: "stat" },
        el("div", { class: "value", text: view.lastShiftAt ? fmtDate(view.lastShiftAt) : "-" }),
        el("div", { class: "label", text: t("lastShift") }))));
  }
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
    const type = requestType("PROFILE");
    if (isWaiting(requests[type])) return fail("profilePending", first);
    if (requestCooldownMinutes(type) > 0) return fail("profileWait", first);
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
      await sendRequest(type, { firstName, lastName, phone: phoneValue, ...(blob ? { photo: true } : {}) });
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
  googleBox = null;
  if (activeKind === "temporary") {
    // Read-only page: the password of the account and signing out, nothing else.
    body.append(
      el("section", { class: "dialog-section" },
        el("h3", { text: t("changePassword") }),
        passwordForm()),
      el("section", { class: "dialog-section quiet" },
        el("p", { class: "small-note", text: t("signedInAs", { email: user.email }) }),
        el("button", { type: "button", class: "ghost", onclick: () => doSignOut(), text: t("signOut") })));
    $("account-title").textContent = t("account");
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
    return;
  }
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
  const guest = activeKind === "guest";
  const data = {
    exportedAt: new Date().toISOString(),
    organization: path.orgId,
    account: { email: user.email },
    [guest ? "guest" : "volunteer"]: projection,
  };
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = el("a", { href: url, download: guest ? "my-guest-data.json" : "my-volunteer-data.json" });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

function renderDashboard() {
  if (!user || !activeKind) return;
  renderSwitch();
  // "My account" has its own button only on the groups page: elsewhere "Edit" opens it.
  $("account-btn").classList.toggle("hidden", activeKind !== "temporary");
  if (activeKind === "temporary") return renderGroups();
  if (!projection) return;
  $("dash-hello").textContent = t("hello", { name: projection.firstName || "" });
  const sub = [projection.association, projectionUpdatedAt ? t("updatedAgo", { ago: ago(projectionUpdatedAt) }) : ""]
    .filter(Boolean).join(" · ");
  $("dash-sub").textContent = sub;
  const dash = $("dash");
  clear(dash);
  if (activeKind === "guest") {
    renderGuest(dash);
    appendManagedLists(dash);
    ensurePhoto();
    return;
  }
  const view = portalView(projection, Date.now());
  const tiles = {
    profile: profileTile(),
    benefits: benefitsTile(view),
    wallet: walletTile(),
    upcoming: upcomingTile(view),
    history: historyTile(view),
  };
  const columns = layoutColumns();
  dash.className = `dash cols-${columns}`;
  const col = (...names) => el("div", { class: "col" }, names.map((n) => tiles[n]).filter(Boolean));
  if (columns === 1) {
    dash.append(col("profile", "benefits", "wallet", "upcoming", "history"));
  } else if (columns === 2) {
    dash.append(col("profile", "upcoming"), col("benefits", "wallet"));
    tiles.history.classList.add("full");
    dash.append(tiles.history);
  } else {
    dash.append(col("profile", "upcoming"), col("benefits", "wallet"), col("history"));
  }
  appendManagedLists(dash);
  ensurePhoto();
}

function renderGuest(dash) {
  const tiles = {
    profile: profileTile(),
    benefits: guestBenefitsTile(),
    wallet: projection.wallet ? walletTile() : null,
  };
  const col = (...names) => el("div", { class: "col" }, names.map((n) => tiles[n]).filter(Boolean));
  if (layoutColumns() === 1) {
    dash.className = "dash cols-1";
    dash.append(col("profile", "benefits", "wallet"));
  } else {
    dash.className = "dash cols-2";
    dash.append(col("profile"), col("benefits", "wallet"));
  }
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
  [MEDIA_2, MEDIA_3].forEach((m) => m.addEventListener("change", () => render()));
  $("account-btn").prepend(icon("user", 16));
  $("account-btn").addEventListener("click", openAccountDialog);
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
  setInterval(() => render(), 60_000);
}

async function main() {
  $("remember").checked = rememberDefault();
  bindLanguageSelect($("lang"), () => {
    if (auth) auth.languageCode = currentLang();
    if (user?.email) $("verify-body").textContent = t("verifyBody", { email: user.email });
    renderBranding();
    renderPending();
    renderGoogleWait();
    render();
    renderGoogleBox();
    if (!$("view-offline").classList.contains("hidden")) renderOffline();
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
