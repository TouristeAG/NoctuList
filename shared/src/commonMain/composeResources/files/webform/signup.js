// Volunteer registration page: /s/{orgId}/{formId}
// 1. account (e-mail + password)  2. profile  3. optional photo
// The account is created with Firebase Auth; the answer is written once onto the single-use form
// document (Firestore rules check every field); NoctuList turns it into a volunteer profile.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  browserSessionPersistence,
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  initializeAuth,
  sendEmailVerification,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore,
  serverTimestamp,
  updateDoc,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  connectStorageEmulator,
  getStorage,
  ref as storageRef,
  uploadBytes,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-storage.js";
import { EMULATOR_PORTS, isLocalDev, loadConfig, parseOrgPath } from "./firebase-env.js?v=portal-1";
import { bindLanguageSelect, currentLang, t } from "./portal-i18n.js?v=portal-1";
import {
  DATE_RE,
  EMAIL_RE,
  GENDERS,
  authErrorKey,
  cleanName,
  cleanPhone,
  clear,
  el,
  passwordProblem,
  passwordStrength,
  toJpeg,
} from "./portal-common.js?v=portal-1";

const $ = (id) => document.getElementById(id);
const states = ["state-loading", "state-closed", "state-done", "form"].map($);
const formEl = $("form");
const errorEl = $("error");
const nextBtn = $("next");
const backBtn = $("back");
const skipBtn = $("skip");

let path = null;
let auth = null;
let db = null;
let storage = null;
let formData = null;
let step = 1;
let totalSteps = 2;
let photoBlob = null;
let previewUrl = null;
let busy = false;

function show(node) {
  states.forEach((s) => s.classList.add("hidden"));
  node.classList.remove("hidden");
}

function showError(key, raw) {
  errorEl.textContent = raw || t(key);
  errorEl.classList.remove("hidden");
}

function clearError() {
  errorEl.textContent = "";
  errorEl.classList.add("hidden");
  formEl.querySelectorAll("[aria-invalid]").forEach((n) => n.removeAttribute("aria-invalid"));
}

function invalid(id, key) {
  const input = $(id);
  input.setAttribute("aria-invalid", "true");
  input.focus();
  showError(key);
  return false;
}

function association() {
  return String(formData?.associationName || "").trim();
}

function renderTexts() {
  const name = association();
  $("lead").textContent = name ? t("signupLead", { association: name }) : t("signupLeadPlain");
  $("consent-text").textContent = name ? t("consent", { association: name }) : t("consentPlain");
  document.title = t("signupTitle");
  // Gender options keep the selected value across language changes.
  const gender = $("gender");
  const selected = gender.value;
  clear(gender);
  gender.append(el("option", { value: "", text: t("genderChoose") }));
  GENDERS.forEach((g) => gender.append(el("option", { value: g, text: t(`gender${g}`) })));
  gender.value = selected;
  renderStepper();
  updateMeter();
  $("choose").textContent = photoBlob ? t("changePhoto") : t("choosePhoto");
  if (formData) $("done-body").textContent = t("doneBody", { email: auth?.currentUser?.email || $("email").value.trim() });
}

function renderStepper() {
  const stepper = $("stepper");
  clear(stepper);
  const labels = [t("stepAccount"), t("stepProfile"), t("stepPhoto")].slice(0, totalSteps);
  labels.forEach((label, index) => {
    const n = index + 1;
    stepper.append(
      el("li", { class: n < step ? "done" : n === step ? "current" : "", "aria-current": n === step ? "step" : null },
        el("span", { class: "dot", text: n < step ? "✓" : String(n) }),
        el("span", { class: "label", text: label })),
    );
  });
  stepper.setAttribute("aria-label", t("stepOf", { n: step, total: totalSteps }));
}

function goTo(n) {
  step = n;
  formEl.querySelectorAll(".step").forEach((fs) => fs.classList.toggle("hidden", Number(fs.dataset.step) !== n));
  backBtn.classList.toggle("invisible", n === 1 || Boolean(auth?.currentUser && n === 2 && accountLocked));
  const last = n === totalSteps;
  nextBtn.textContent = last ? t("submit") : t("next");
  skipBtn.classList.toggle("hidden", !(n === 3 && !photoBlob));
  renderStepper();
  clearError();
  const first = formEl.querySelector(`.step[data-step="${n}"] input, .step[data-step="${n}"] select`);
  if (first && window.matchMedia("(pointer: fine)").matches) first.focus();
}

// Once the account exists (step 1 done), the e-mail can no longer change on this page.
let accountLocked = false;

function updateMeter() {
  const pw = $("password").value;
  const score = pw ? passwordStrength(pw, $("email").value) : 0;
  const bar = $("meter-bar");
  bar.style.width = pw ? `${Math.max(8, score * 25)}%` : "0%";
  bar.dataset.score = String(score);
  $("meter-label").textContent = pw ? t("strength", { level: t(`strength${score}`) }) : "";
}

async function submitAccount() {
  const email = $("email").value.trim().toLowerCase();
  const password = $("password").value;
  if (!EMAIL_RE.test(email)) return invalid("email", "errEmail");
  const problem = passwordProblem(password, email);
  if (problem) return invalid("password", problem);
  if (password !== $("password2").value) return invalid("password2", "errPasswordMatch");
  if (!$("consent").checked) return invalid("consent", "errConsent");
  if (auth.currentUser && auth.currentUser.email === email) {
    accountLocked = true;
    return true;
  }
  try {
    await createUserWithEmailAndPassword(auth, email, password);
  } catch (error) {
    if (String(error?.code).includes("email-already-in-use")) {
      // Someone who already has a portal account can still answer with it.
      try {
        await signInWithEmailAndPassword(auth, email, password);
      } catch {
        return invalid("email", "errEmailInUse");
      }
    } else {
      const key = authErrorKey(error);
      return invalid(key === "errWeakPassword" ? "password" : "email", key);
    }
  }
  accountLocked = true;
  $("email").readOnly = true;
  return true;
}

function readProfile() {
  const firstName = cleanName($("firstName").value);
  if (!firstName) return invalid("firstName", "errName");
  const lastName = cleanName($("lastName").value);
  if (!lastName) return invalid("lastName", "errName");
  const phone = cleanPhone($("phone").value);
  if (!phone) return invalid("phone", "errPhone");
  const dob = $("dob").value;
  const year = Number(dob.slice(0, 4));
  if (!DATE_RE.test(dob) || year > new Date().getFullYear()) return invalid("dob", "errDob");
  const gender = $("gender").value;
  if (!GENDERS.includes(gender)) return invalid("gender", "errGender");
  return { firstName, lastName, phone, dateOfBirth: dob, gender };
}

async function submitAll() {
  const profile = readProfile();
  if (!profile) return false;
  const user = auth.currentUser;
  if (!user) {
    goTo(1);
    return false;
  }
  setBusy(true, t("sending"));
  try {
    let photoPath = "";
    if (photoBlob && formData.photosEnabled === true) {
      photoPath = `orgs/${path.orgId}/portalSignupPhotos/${path.id}.jpg`;
      try {
        await uploadBytes(storageRef(storage, photoPath), photoBlob, { contentType: "image/jpeg" });
      } catch (error) {
        console.warn("signup photo upload failed", error?.code);
        photoPath = ""; // the registration still goes through without the photo
      }
    }
    await updateDoc(doc(db, "orgs", path.orgId, "volunteerSignupForms", path.id), {
      status: "SUBMITTED",
      submittedUid: user.uid,
      email: user.email,
      ...profile,
      photoPath,
      consent: true,
      lang: currentLang(),
      submittedAt: serverTimestamp(),
      lastModified: Date.now(),
    });
    try {
      await sendEmailVerification(user, { url: `${location.origin}/v/${encodeURIComponent(path.orgId)}` });
    } catch (error) {
      // Already verified (existing account) or a transient problem: they can resend from the portal.
      console.warn("verification e-mail not sent", error?.code);
    }
    $("done-body").textContent = t("doneBody", { email: user.email });
    $("open-portal").href = `/v/${encodeURIComponent(path.orgId)}`;
    await signOut(auth).catch(() => {});
    show($("state-done"));
    return true;
  } catch (error) {
    console.error("registration failed", error?.code, error?.message);
    showError(String(error?.code).includes("permission-denied") ? "closedBody" : authErrorKey(error));
    return false;
  } finally {
    setBusy(false);
  }
}

function setBusy(on, label) {
  busy = on;
  nextBtn.disabled = on;
  backBtn.disabled = on;
  if (on && label) nextBtn.textContent = label;
  if (!on) nextBtn.textContent = step === totalSteps ? t("submit") : t("next");
}

async function onNext(event) {
  event.preventDefault();
  if (busy) return;
  clearError();
  if (step === 1) {
    setBusy(true);
    const ok = await submitAccount();
    setBusy(false);
    if (ok) goTo(2);
    return;
  }
  if (step === 2 && totalSteps === 3) {
    if (readProfile()) goTo(3);
    return;
  }
  await submitAll();
}

function bindPhoto() {
  const fileEl = $("file");
  const previewEl = $("preview");
  const pick = () => fileEl.click();
  previewEl.addEventListener("click", pick);
  $("choose").addEventListener("click", pick);
  fileEl.addEventListener("change", async () => {
    clearError();
    const file = fileEl.files && fileEl.files[0];
    fileEl.value = "";
    if (!file) return;
    if (file.type && !file.type.startsWith("image/")) return showError("notImage");
    try {
      photoBlob = await toJpeg(file);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = URL.createObjectURL(photoBlob);
      previewEl.style.backgroundImage = `url("${previewUrl}")`;
      previewEl.classList.add("has-photo");
      $("choose").textContent = t("changePhoto");
      skipBtn.classList.add("hidden");
    } catch {
      photoBlob = null;
      showError("unreadable");
    }
  });
}

function bindPasswordToggles() {
  formEl.querySelectorAll(".pw-toggle").forEach((btn) => {
    btn.addEventListener("click", () => {
      const input = $(btn.dataset.target);
      input.type = input.type === "password" ? "text" : "password";
      btn.classList.toggle("on", input.type === "text");
    });
  });
}

async function main() {
  bindLanguageSelect($("lang"), renderTexts);
  path = parseOrgPath("s");
  if (!path) return show($("state-closed"));

  let app;
  try {
    app = initializeApp(await loadConfig(t("hostingError")));
  } catch (error) {
    $("closed-error").textContent = error.message;
    $("closed-error").classList.remove("hidden");
    return show($("state-closed"));
  }
  // Session-only: a registration never leaves a signed-in account on a shared device.
  auth = initializeAuth(app, { persistence: browserSessionPersistence });
  db = getFirestore(app);
  storage = getStorage(app);
  if (isLocalDev()) {
    connectAuthEmulator(auth, `http://${location.hostname}:${EMULATOR_PORTS.auth}`, { disableWarnings: true });
    connectFirestoreEmulator(db, location.hostname, EMULATOR_PORTS.firestore);
    connectStorageEmulator(storage, location.hostname, EMULATOR_PORTS.storage);
  }
  auth.languageCode = currentLang();

  try {
    const snap = await getDoc(doc(db, "orgs", path.orgId, "volunteerSignupForms", path.id));
    if (!snap.exists()) return show($("state-closed"));
    formData = snap.data();
  } catch (error) {
    console.warn("signup form unavailable", error?.code);
    return show($("state-closed"));
  }
  if (formData.status !== "OPEN" || Date.now() >= Number(formData.expiresAtMillis || 0)) {
    return show($("state-closed"));
  }

  const name = association();
  if (name) {
    const brand = $("brand");
    brand.append(el("span", { text: name }));
    brand.classList.remove("hidden");
  }
  totalSteps = formData.photosEnabled === true ? 3 : 2;
  $("open-portal").href = `/v/${encodeURIComponent(path.orgId)}`;
  ["password", "email"].forEach((id) => $(id).addEventListener("input", updateMeter));
  formEl.addEventListener("submit", onNext);
  backBtn.addEventListener("click", () => step > 1 && goTo(step - 1));
  skipBtn.addEventListener("click", () => submitAll());
  bindPhoto();
  bindPasswordToggles();
  renderTexts();
  show(formEl);
  goTo(1);
}

main();
