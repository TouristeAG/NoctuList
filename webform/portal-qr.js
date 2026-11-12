// Full-screen volunteer QR: SVG rendering, on-device copy and the dialog.
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

export function storeQr(orgId, { uid, payload, name, association }) {
  try {
    localStorage.setItem(keyFor(orgId), JSON.stringify({ uid, payload, name, association }));
  } catch {
    /* storage full or blocked: the QR still shows from the live document */
  }
}

export function readCachedQr(orgId, uid) {
  try {
    const raw = localStorage.getItem(keyFor(orgId));
    if (!raw) return null;
    const value = JSON.parse(raw);
    if (!value || typeof value !== "object" || typeof value.payload !== "string") return null;
    if (uid && value.uid !== uid) return null;
    return value;
  } catch {
    return null;
  }
}

export function clearQr(orgId) {
  try {
    localStorage.removeItem(keyFor(orgId));
  } catch {
    /* ignore */
  }
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
