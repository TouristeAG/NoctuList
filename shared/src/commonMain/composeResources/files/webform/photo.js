import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getFirestore,
  doc,
  getDoc,
  updateDoc,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  getStorage,
  ref as storageRef,
  uploadBytes,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-storage.js";
import { applyStaticI18n, currentLang, setLang, t } from "./photo-i18n.js?v=photo-1";

// Mirrors PROFILE_PHOTO_MAX_EDGE_PX / JPEG quality in the app, so the upload is already the size
// the app stores and stays well under the 3 MB Storage rule.
const MAX_EDGE_PX = 1024;
const JPEG_QUALITY = 0.85;
const MAX_UPLOAD_BYTES = 3 * 1024 * 1024;

const loadingEl = document.getElementById("state-loading");
const closedEl = document.getElementById("state-closed");
const doneEl = document.getElementById("state-done");
const formEl = document.getElementById("form");
const previewEl = document.getElementById("preview");
const doneVisualEl = document.getElementById("done-preview");
const fileEl = document.getElementById("file");
const chooseBtn = document.getElementById("choose");
const uploadBtn = document.getElementById("upload");
const statusEl = document.getElementById("status");
const errorEl = document.getElementById("error");
const langEl = document.getElementById("lang");

let pendingJpeg = null;
let previewUrl = "";

function show(el) {
  [loadingEl, closedEl, doneEl, formEl].forEach((node) => node.classList.add("hidden"));
  el.classList.remove("hidden");
}

function showError(message) {
  errorEl.textContent = message;
  errorEl.classList.remove("hidden");
}

function clearError() {
  errorEl.classList.add("hidden");
  errorEl.textContent = "";
}

function parsePath() {
  const parts = location.pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  const p = parts.indexOf("p");
  if (p < 0 || parts.length < p + 3) return null;
  return { orgId: decodeURIComponent(parts[p + 1]), requestId: decodeURIComponent(parts[p + 2]) };
}

function asMillis(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function isOpen(data) {
  return data && data.status === "OPEN" && Date.now() < asMillis(data.expiresAtMillis);
}

function refreshChooseLabel() {
  chooseBtn.textContent = t(pendingJpeg ? "change" : "choose");
}

function bindLanguage() {
  langEl.value = setLang(currentLang());
  applyStaticI18n();
  langEl.addEventListener("change", () => {
    setLang(langEl.value);
    applyStaticI18n();
    refreshChooseLabel();
    if (formEl.dataset.name) {
      document.getElementById("meta").textContent = t("forName", { name: formEl.dataset.name });
    }
  });
}

async function loadConfig() {
  const response = await fetch("/__/firebase/init.json");
  if (!response.ok) throw new Error(t("hostingError"));
  return response.json();
}

async function decodeImage(file) {
  // createImageBitmap honours EXIF orientation, so portrait phone shots stay upright.
  if (window.createImageBitmap) {
    try {
      return await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      /* Safari < 17 rejects the options bag, or the format: fall back to <img>. */
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

async function toJpeg(file) {
  const image = await decodeImage(file);
  const width = image.width || image.naturalWidth;
  const height = image.height || image.naturalHeight;
  if (!width || !height) throw new Error("empty image");
  const scale = Math.min(1, MAX_EDGE_PX / Math.max(width, height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(width * scale));
  canvas.height = Math.max(1, Math.round(height * scale));
  const ctx = canvas.getContext("2d");
  // Transparent PNGs would turn black as JPEG.
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
  if (typeof image.close === "function") image.close();
  let quality = JPEG_QUALITY;
  let blob = null;
  while (quality >= 0.5) {
    blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
    if (blob && blob.size < MAX_UPLOAD_BYTES) break;
    quality -= 0.15;
  }
  if (!blob || blob.size >= MAX_UPLOAD_BYTES) throw new Error("too large");
  return blob;
}

function setPreview(blob) {
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = URL.createObjectURL(blob);
  previewEl.style.backgroundImage = `url("${previewUrl}")`;
  previewEl.classList.add("has-photo");
  doneVisualEl.style.backgroundImage = `url("${previewUrl}")`;
  doneVisualEl.classList.add("has-photo");
}

async function onFilePicked() {
  clearError();
  const file = fileEl.files && fileEl.files[0];
  fileEl.value = "";
  if (!file) return;
  if (file.type && !file.type.startsWith("image/")) {
    showError(t("notImage"));
    return;
  }
  try {
    pendingJpeg = await toJpeg(file);
    setPreview(pendingJpeg);
    uploadBtn.disabled = false;
  } catch (error) {
    console.warn("photo decode failed", error);
    pendingJpeg = null;
    uploadBtn.disabled = true;
    showError(t("unreadable"));
  }
  refreshChooseLabel();
}

function tryClosePage() {
  // Browsers only let a script close a tab it opened itself; otherwise the thank-you card stays.
  setTimeout(() => {
    try {
      window.close();
    } catch {
      /* ignored */
    }
  }, 2500);
}

async function main() {
  bindLanguage();
  const path = parsePath();
  if (!path) {
    show(closedEl);
    return;
  }
  let app;
  try {
    app = initializeApp(await loadConfig());
  } catch (error) {
    showError(error.message || t("hostingError"));
    show(closedEl);
    return;
  }
  const db = getFirestore(app);
  const storage = getStorage(app);
  const requestRef = doc(db, "orgs", path.orgId, "photoRequests", path.requestId);
  let data;
  try {
    const snap = await getDoc(requestRef);
    data = snap.exists() ? snap.data() : null;
  } catch (error) {
    console.error("photo request get failed", path, error?.code, error?.message);
    data = null;
  }
  if (!isOpen(data)) {
    show(closedEl);
    return;
  }

  const name = String(data.displayName || "").trim();
  if (name) {
    formEl.dataset.name = name;
    document.getElementById("meta").textContent = t("forName", { name });
  }

  const pick = () => fileEl.click();
  previewEl.addEventListener("click", pick);
  chooseBtn.addEventListener("click", pick);
  fileEl.addEventListener("change", onFilePicked);

  uploadBtn.addEventListener("click", async () => {
    if (!pendingJpeg) return;
    clearError();
    uploadBtn.disabled = true;
    chooseBtn.disabled = true;
    statusEl.textContent = t("uploading");
    statusEl.classList.remove("hidden");
    const photoPath = `orgs/${path.orgId}/photoRequests/${path.requestId}.jpg`;
    try {
      await uploadBytes(storageRef(storage, photoPath), pendingJpeg, { contentType: "image/jpeg" });
      const now = Date.now();
      await updateDoc(requestRef, {
        status: "UPLOADED",
        photoPath,
        uploadedAt: now,
        lastModified: now,
      });
      show(doneEl);
      tryClosePage();
    } catch (error) {
      const code = error && error.code ? String(error.code) : "";
      console.error("photo upload failed", code, error?.message);
      statusEl.classList.add("hidden");
      uploadBtn.disabled = false;
      chooseBtn.disabled = false;
      showError(t("sendError") + (code ? ` (${code})` : ""));
    }
  });

  refreshChooseLabel();
  show(formEl);
}

main();
