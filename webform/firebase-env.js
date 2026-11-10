// Shared Firebase bootstrap for the public pages.
//
// The project config comes from Firebase Hosting's reserved `/__/firebase/init.json`, so the same
// static files work for every institution that deploys them on its own project.
//
// Local development only: when the page is served from localhost, the SDKs talk to the Firebase
// emulators (ports below, matching firebase/tests/firebase.json). A production site is never
// served from localhost, so this can never redirect real visitors.

export const SDK = "https://www.gstatic.com/firebasejs/10.14.1";

export const EMULATOR_PORTS = Object.freeze({
  auth: 9099,
  firestore: 8080,
  storage: 9199,
});

export function isLocalDev() {
  return location.hostname === "localhost" || location.hostname === "127.0.0.1";
}

export async function loadConfig(errorMessage) {
  const response = await fetch("/__/firebase/init.json", { credentials: "omit", cache: "no-store" });
  if (!response.ok) throw new Error(errorMessage || "Firebase Hosting is not serving this site yet.");
  return response.json();
}

/** `/x/{orgId}/{id}` → `{ orgId, id }`, or null when the path does not match. */
export function parseOrgPath(prefix, needsId = true) {
  const parts = location.pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  const at = parts.indexOf(prefix);
  if (at < 0 || parts.length < at + 2) return null;
  const orgId = decodeURIComponent(parts[at + 1] || "");
  const id = decodeURIComponent(parts[at + 2] || "");
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(orgId)) return null;
  if (needsId && !/^[A-Za-z0-9_-]{10,64}$/.test(id)) return null;
  return { orgId, id };
}
