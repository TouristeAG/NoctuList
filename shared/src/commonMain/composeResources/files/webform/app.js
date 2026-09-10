import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getFirestore,
  doc,
  getDoc,
  setDoc,
  updateDoc,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import { applyStaticI18n, currentLang, setLang, setLabelOverrides, t } from "./i18n.js?v=field-labels-2";

const loadingEl = document.getElementById("state-loading");
const closedEl = document.getElementById("state-closed");
const doneEl = document.getElementById("state-done");
const formEl = document.getElementById("form");
const peopleEl = document.getElementById("people");
const addBtn = document.getElementById("add-person");
const errorEl = document.getElementById("error");
const quotaLegendEl = document.getElementById("quota-legend");
const langEl = document.getElementById("lang");

let offeredAccesses = [];

const NANO_ALPHABET = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

function nanoId(size = 21) {
  const bytes = crypto.getRandomValues(new Uint8Array(size));
  let id = "";
  for (let i = 0; i < size; i++) id += NANO_ALPHABET[bytes[i] % NANO_ALPHABET.length];
  return id;
}

function show(el) {
  [loadingEl, closedEl, doneEl, formEl].forEach((node) => node.classList.add("hidden"));
  el.classList.remove("hidden");
}

function parsePath() {
  const parts = location.pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  const g = parts.indexOf("g");
  if (g < 0 || parts.length < g + 3) return null;
  return { orgId: decodeURIComponent(parts[g + 1]), formId: decodeURIComponent(parts[g + 2]) };
}

function parseAccesses(raw) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed
        .filter((item) => item && item.id && item.name)
        .map((item) => ({
          id: String(item.id),
          name: String(item.name),
          maxRequests: Math.max(0, Number(item.maxRequests) || 0),
        }));
    }
  } catch (_) {
    /* comma-separated IDs from an older build */
  }
  return String(raw)
    .split(",")
    .map((id) => id.trim())
    .filter(Boolean)
    .map((id) => ({ id, name: id, maxRequests: 0 }));
}

function accessCap(access) {
  const n = Number(access && access.maxRequests) || 0;
  return n > 0 ? n : 0;
}

function chipsForAccess(accessId, onOnly = false) {
  return [...peopleEl.querySelectorAll(".chip")].filter((chip) => {
    if (chip.dataset.id !== accessId) return false;
    if (onOnly && !chip.classList.contains("on")) return false;
    return true;
  });
}

function countRequested(accessId) {
  return chipsForAccess(accessId, true).length;
}

function firstQuotaViolation(people) {
  for (const access of offeredAccesses) {
    const cap = accessCap(access);
    if (cap <= 0) continue;
    const count = people.filter((person) => person.access.includes(access.id)).length;
    if (count > cap) return access;
  }
  return null;
}

function showQuotaError(access) {
  errorEl.textContent = t("quotaExceeded", { n: accessCap(access), name: access.name });
  errorEl.classList.remove("hidden");
}

function refreshQuotaUi() {
  const capped = offeredAccesses.filter((access) => accessCap(access) > 0);
  if (!quotaLegendEl) return;
  if (!capped.length) {
    quotaLegendEl.classList.add("hidden");
    quotaLegendEl.textContent = "";
  } else {
    quotaLegendEl.classList.remove("hidden");
    quotaLegendEl.textContent = capped
      .map((access) => `${access.name} ${countRequested(access.id)}/${accessCap(access)}`)
      .join(" · ");
  }
  offeredAccesses.forEach((access) => {
    const cap = accessCap(access);
    const used = countRequested(access.id);
    const full = cap > 0 && used >= cap;
    chipsForAccess(access.id).forEach((chip) => {
      chip.classList.toggle("full", full && !chip.classList.contains("on"));
    });
  });
}

function formatDate(millis) {
  if (!millis) return "";
  const lang = currentLang();
  const locale = { fr: "fr-CH", es: "es", de: "de-CH", it: "it-CH", en: "en-GB" }[lang] || "en-GB";
  return new Date(Number(millis)).toLocaleDateString(locale, {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  });
}

function asBool(value) {
  return value === true || value === 1 || value === "1" || value === "true" || value === "TRUE";
}

function resolveFormData(raw) {
  let nested = {};
  if (typeof raw?.json === "string" && raw.json.trim().startsWith("{")) {
    try {
      nested = JSON.parse(raw.json);
    } catch (_) {
      /* ignore corrupt envelope */
    }
  }
  return Object.assign({}, nested && typeof nested === "object" ? nested : {}, raw || {});
}

function normalizeLogoShape(raw) {
  const shape = String(raw || "ROUNDED").trim().toLowerCase();
  if (shape === "square" || shape === "circle" || shape === "rounded") return shape;
  return "rounded";
}

function renderLogos(rawForm) {
  const form = resolveFormData(rawForm);
  const box = document.getElementById("logos");
  box.innerHTML = "";
  const entries = [
    form.institutionLogoDataUri
      ? {
          src: form.institutionLogoDataUri,
          shape: normalizeLogoShape(form.institutionLogoShape),
          invert: asBool(form.institutionLogoInvert),
        }
      : null,
    form.guestLogoDataUri
      ? {
          src: form.guestLogoDataUri,
          shape: normalizeLogoShape(form.guestLogoShape),
          invert: asBool(form.guestLogoInvert),
        }
      : null,
  ].filter(Boolean);
  box.className = "logos " + (entries.length === 2 ? "two" : entries.length === 1 ? "one" : "");
  entries.forEach((entry) => {
    const wrap = document.createElement("div");
    wrap.className = "logo shape-" + entry.shape + (entry.invert ? " invert" : "");
    wrap.style.width = "88px";
    wrap.style.height = "88px";
    wrap.style.display = "flex";
    wrap.style.alignItems = "center";
    wrap.style.justifyContent = "center";
    wrap.style.overflow = "hidden";
    wrap.style.flex = "0 0 auto";
    wrap.style.background = "transparent";
    wrap.style.borderRadius =
      entry.shape === "circle" ? "50%" : entry.shape === "rounded" ? "14px" : "0";
    const img = document.createElement("img");
    img.src = entry.src;
    img.alt = "";
    img.style.width = "100%";
    img.style.height = "100%";
    img.style.objectFit = "contain";
    img.style.display = "block";
    img.style.background = "transparent";
    img.style.borderRadius = wrap.style.borderRadius;
    if (entry.invert) {
      img.style.filter = "invert(1)";
    }
    wrap.appendChild(img);
    box.appendChild(wrap);
  });
}

function personRow(index, accesses, selectedIds = []) {
  const wrap = document.createElement("div");
  wrap.className = "person";
  wrap.innerHTML = `
    <div class="person-head">
      <input data-name required />
    </div>
  `;
  wrap.querySelector("[data-name]").placeholder = t("namePlaceholder", { n: index + 1 });
  if (accesses.length) {
    const block = document.createElement("div");
    block.className = "access-block";
    const label = document.createElement("p");
    label.className = "access-label";
    label.textContent = t("accessLabel");
    const chips = document.createElement("div");
    chips.className = "accesses";
    chips.dataset.accesses = "1";
    accesses.forEach((access) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "chip";
      chip.textContent = access.name;
      chip.dataset.id = access.id;
      if (selectedIds.includes(access.id)) chip.classList.add("on");
      chip.addEventListener("click", () => {
        const turningOn = !chip.classList.contains("on");
        if (turningOn) {
          const cap = accessCap(access);
          if (cap > 0 && countRequested(access.id) >= cap) {
            showQuotaError(access);
            return;
          }
        }
        errorEl.classList.add("hidden");
        chip.classList.toggle("on");
        refreshQuotaUi();
      });
      chips.appendChild(chip);
    });
    block.appendChild(label);
    block.appendChild(chips);
    wrap.appendChild(block);
  }
  return wrap;
}

function collectPeople() {
  return [...peopleEl.querySelectorAll(".person")].map((row) => ({
    name: row.querySelector("[data-name]").value.trim(),
    access: [...row.querySelectorAll(".chip.on")].map((chip) => chip.dataset.id),
  })).filter((person) => person.name);
}

function relabelPeople() {
  [...peopleEl.querySelectorAll(".person")].forEach((row, index) => {
    const input = row.querySelector("[data-name]");
    input.placeholder = t("namePlaceholder", { n: index + 1 });
    const label = row.querySelector(".access-label");
    if (label) label.textContent = t("accessLabel");
  });
  refreshQuotaUi();
}

function bindLanguage() {
  const lang = setLang(currentLang());
  langEl.value = lang;
  applyStaticI18n();
  relabelPeople();
  langEl.addEventListener("change", () => {
    setLang(langEl.value);
    applyStaticI18n();
    relabelPeople();
  });
}

async function loadConfig() {
  const response = await fetch("/__/firebase/init.json");
  if (!response.ok) throw new Error(t("hostingError"));
  return response.json();
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
    errorEl.textContent = error.message || t("hostingError");
    errorEl.classList.remove("hidden");
    show(closedEl);
    return;
  }
  const db = getFirestore(app);
  const ref = doc(db, "orgs", path.orgId, "guestForms", path.formId);
  let snap;
  try {
    snap = await getDoc(ref);
  } catch (error) {
    console.error("guest form get failed", path, error?.code, error?.message);
    show(closedEl);
    return;
  }
  if (!snap || !snap.exists()) {
    show(closedEl);
    return;
  }
  const form = snap.data();
  if (form.status !== "OPEN" || Date.now() >= Number(form.expiresAtMillis || 0)) {
    show(closedEl);
    return;
  }

  offeredAccesses = parseAccesses(form.offeredAccessIds);
  const maxGuests = Math.max(1, Math.min(100, Number(form.maxGuests) || 10));
  const title = document.getElementById("title");
  title.dataset.locked = "1";
  title.textContent = form.eventName || form.artistName || t("title");
  document.getElementById("meta").textContent = [form.artistName, form.venueName, formatDate(form.eventDateMillis)]
    .filter(Boolean)
    .join(" · ");
  const askEmail = form.askEmail !== false;
  const askPhone = form.askPhone !== false;
  const emailField = document.getElementById("email-field");
  const phoneField = document.getElementById("phone-field");
  if (emailField) emailField.hidden = !askEmail;
  if (phoneField) phoneField.hidden = !askPhone;
  if (askEmail) {
    document.getElementById("email").value = form.prefillEmail || "";
  }
  if (askPhone) {
    document.getElementById("phone").value = form.prefillPhone || "";
  }
  document.getElementById("disclaimer").hidden = offeredAccesses.length === 0;
  setLabelOverrides(form.fieldLabelsJson);
  applyStaticI18n();
  renderLogos(form);
  peopleEl.appendChild(personRow(0, offeredAccesses));
  refreshQuotaUi();
  addBtn.addEventListener("click", () => {
    if (peopleEl.children.length >= maxGuests) return;
    peopleEl.appendChild(personRow(peopleEl.children.length, offeredAccesses));
    addBtn.hidden = peopleEl.children.length >= maxGuests;
    refreshQuotaUi();
  });

  formEl.addEventListener("submit", async (event) => {
    event.preventDefault();
    errorEl.classList.add("hidden");
    const people = collectPeople();
    if (!people.length) {
      errorEl.textContent = t("needName");
      errorEl.classList.remove("hidden");
      return;
    }
    const quotaHit = firstQuotaViolation(people);
    if (quotaHit) {
      showQuotaError(quotaHit);
      return;
    }
    const payload = JSON.stringify({
      email: askEmail ? document.getElementById("email").value.trim() : "",
      phone: askPhone ? document.getElementById("phone").value.trim() : "",
      notes: document.getElementById("notes").value.trim().slice(0, 500),
      people,
    });
    if (payload.length >= 20000) {
      errorEl.textContent = t("tooLong");
      errorEl.classList.remove("hidden");
      return;
    }
    try {
      const now = Date.now();
      if (form.allowMultipleResponses === true) {
        // Keep the template OPEN; each answer is a new PENDING_REVIEW review item.
        const responseId = nanoId();
        await setDoc(doc(db, "orgs", path.orgId, "guestForms", responseId), {
          formId: responseId,
          parentFormId: path.formId,
          venueName: form.venueName || "",
          eventName: form.eventName || "",
          eventDateMillis: Number(form.eventDateMillis) || 0,
          artistName: form.artistName || "",
          offeredAccessIds: form.offeredAccessIds || "",
          maxGuests: Number(form.maxGuests) || 10,
          expiryMode: form.expiryMode || "EVENT_DAY",
          expiresAtMillis: Number(form.expiresAtMillis) || 0,
          allowMultipleResponses: false,
          showInstitutionLogo: false,
          institutionLogoDataUri: "",
          institutionLogoShape: form.institutionLogoShape || "ROUNDED",
          institutionLogoInvert: false,
          guestLogoDataUri: "",
          guestLogoShape: form.guestLogoShape || "ROUNDED",
          guestLogoInvert: false,
          askEmail,
          askPhone,
          prefillEmail: "",
          prefillPhone: "",
          status: "PENDING_REVIEW",
          submissionJson: payload,
          submittedAt: now,
          reviewedAt: 0,
          reviewedBy: "",
          createdAt: now,
          lastModified: now,
          sourceDeviceId: "",
        });
      } else {
        // Public get is only allowed while status is OPEN. After this update the form is
        // PENDING_REVIEW, so a follow-up getDoc would permission-deny even though the write stuck.
        await updateDoc(ref, {
          status: "PENDING_REVIEW",
          submissionJson: payload,
          submittedAt: now,
          lastModified: now,
        });
      }
      show(doneEl);
    } catch (error) {
      const code = error && error.code ? String(error.code) : "";
      // Single-response double-submit: first update already closed the form.
      if (
        form.allowMultipleResponses !== true &&
        (code === "permission-denied" || code === "firestore/permission-denied")
      ) {
        show(doneEl);
        return;
      }
      const msg = error && error.message ? String(error.message) : String(error);
      errorEl.textContent = (code ? code + ": " : "") + (msg || t("sendError"));
      errorEl.classList.remove("hidden");
    }
  });
  show(formEl);
}

main();
