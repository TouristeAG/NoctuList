// Full-screen portal QR: SVG rendering, on-device copy (one per profile) and the dialog.
// No Firebase here, so the storage functions can be unit tested in Node.
import qrcode from "./vendor/qrcode.js";

const keyFor = (orgId) => `noctulist.portalQr.${orgId}`;

/** Inline SVG element for the payload (no external URL, allowed by the portal CSP). */
export function qrSvg(payload) {
  const qr = qrcode(0, "M");
  qr.addData(String(payload));
  qr.make();
  const svg = qr.createSvgTag({ cellSize: 8, margin: 4, scalable: true });
  const doc = new DOMParser().parseFromString(svg, "image/svg+xml");
  return document.importNode(doc.documentElement, true);
}

const KINDS = ["volunteer", "guest", "temporary"];

function validEntry(e) {
  return Boolean(e) && typeof e === "object" && typeof e.payload === "string" && e.payload.length > 0;
}

function entryOf(e) {
  return { payload: e.payload, name: String(e.name || ""), association: String(e.association || "") };
}

/**
 * The stored value, one entry per profile: `{ uid, profiles: { volunteer, guest, temporary } }`,
 * temporary being a list of `{ key, payload, name, association }`. The value written before guest
 * profiles existed (`{ uid, payload, name, association }`) is read as the volunteer entry.
 */
function normalize(value) {
  if (!value || typeof value !== "object") return null;
  const uid = typeof value.uid === "string" ? value.uid : "";
  const source = value.profiles && typeof value.profiles === "object"
    ? value.profiles
    : { volunteer: value };
  const profiles = {};
  for (const kind of KINDS) {
    const item = source[kind];
    if (kind === "temporary") {
      const list = Array.isArray(item)
        ? item.filter((e) => validEntry(e) && typeof e.key === "string").map((e) => ({ key: e.key, ...entryOf(e) }))
        : [];
      if (list.length) profiles.temporary = list;
    } else if (validEntry(item)) {
      profiles[kind] = entryOf(item);
    }
  }
  return Object.keys(profiles).length ? { uid, profiles } : null;
}

function readRaw(orgId) {
  try {
    const raw = localStorage.getItem(keyFor(orgId));
    return raw ? normalize(JSON.parse(raw)) : null;
  } catch {
    return null;
  }
}

function write(orgId, value) {
  try {
    if (value && Object.keys(value.profiles).length) localStorage.setItem(keyFor(orgId), JSON.stringify(value));
    else localStorage.removeItem(keyFor(orgId));
  } catch {
    /* storage full or blocked: the QR still shows from the live document */
  }
}

/** Keeps one profile's QR (an entry, or a list of entries for "temporary"); another account's copy is replaced. */
export function storeQr(orgId, uid, kind, value) {
  if (!KINDS.includes(kind)) return;
  const current = readRaw(orgId);
  const next = { uid, profiles: current && current.uid === uid ? { ...current.profiles } : {} };
  delete next.profiles[kind];
  const stored = normalize({ uid, profiles: { [kind]: value } });
  if (stored) next.profiles[kind] = stored.profiles[kind];
  write(orgId, next);
}

export function readCachedQr(orgId, uid) {
  const value = readRaw(orgId);
  if (!value) return null;
  if (uid && value.uid !== uid) return null;
  return value;
}

/** Every stored code, in profile order: `{ kind, key, payload, name, association }`. */
export function cachedQrEntries(cached) {
  const profiles = cached?.profiles || {};
  const out = [];
  for (const kind of KINDS) {
    const item = profiles[kind];
    if (!item) continue;
    if (kind === "temporary") item.forEach((e) => out.push({ kind, ...e }));
    else out.push({ kind, key: kind, ...item });
  }
  return out;
}

/** Without `kind`, or when the stored copy belongs to another account than `uid`, forgets every stored code. */
export function clearQr(orgId, kind, uid) {
  const current = kind ? readRaw(orgId) : null;
  if (!kind || (current && uid && current.uid !== uid)) {
    try {
      localStorage.removeItem(keyFor(orgId));
    } catch {
      /* ignore */
    }
    return;
  }
  if (!current?.profiles[kind]) return;
  const profiles = { ...current.profiles };
  delete profiles[kind];
  write(orgId, { uid: current.uid, profiles });
}

/** True when the stored copy belongs to another account than the one signing in (Google sessions are keyed by the linked uid, so never judged here). */
export function isStaleFor(cached, authUid, google) {
  return Boolean(cached?.uid) && !google && cached.uid !== authUid;
}

/** True when a snapshot is allowed to wipe the stored copy: the server said the document is gone. */
export function missingIsFinal(snap) {
  return !snap.exists() && !snap.metadata?.fromCache;
}

export function closeQrDialog() {
  const dialog = document.getElementById("qr-dialog");
  if (dialog?.open) dialog.close();
}

let wakeLock = null;

async function releaseWakeLock() {
  const lock = wakeLock;
  wakeLock = null;
  try {
    await lock?.release();
  } catch {
    /* ignore */
  }
}

export function openQrDialog({ payload, name, association }) {
  const dialog = document.getElementById("qr-dialog");
  if (!dialog || dialog.open) return;
  const box = dialog.querySelector("#qr-box");
  box.replaceChildren(qrSvg(payload));
  dialog.querySelector("#qr-name").textContent = name || "";
  dialog.querySelector("#qr-assoc").textContent = association || "";
  const close = () => {
    if (dialog.open) dialog.close();
  };
  let pushed = false;
  try {
    history.pushState({ qr: true }, "");
    pushed = true;
  } catch {
    /* ignore */
  }
  const onPop = () => {
    pushed = false;
    close();
  };
  window.addEventListener("popstate", onPop);
  dialog.addEventListener("close", () => {
    window.removeEventListener("popstate", onPop);
    releaseWakeLock();
    box.replaceChildren();
    // Closed by the button or Escape: drop the history entry we added.
    if (pushed) history.back();
  }, { once: true });
  dialog.querySelector("#qr-close").onclick = close;
  dialog.showModal();
  try {
    navigator.wakeLock?.request("screen").then((lock) => {
      if (dialog.open) wakeLock = lock;
      else lock.release().catch(() => {});
    }).catch(() => {});
  } catch {
    /* ignore */
  }
}
