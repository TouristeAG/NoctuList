// Service worker of the volunteer portal (scope /v/): keeps the page and its scripts on the
// device so "My QR" opens without network. Classic script (module workers are not supported
// on every iOS version). Firestore, Auth and Storage requests are never touched.
// The helpers below are plain functions so the unit tests can load this file in a Node vm.

const CACHE = "portal-v3";
const SHELL = "/portal.html";
const FIREBASE_CDN = "https://www.gstatic.com/firebasejs/";
/** Pinned SDK version: its files never change, so they are served from the cache first. */
const FIREBASE_SDK = "https://www.gstatic.com/firebasejs/10.14.1/";
const FIXED = ["/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png", "/icons/apple-touch-icon.png"];

/** "html", "js" or "css" when the file is read for more URLs at install, else null. */
function assetKind(url) {
  const path = new URL(url).pathname;
  if (path.endsWith(".html")) return "html";
  if (path.endsWith(".js")) return "js";
  if (path.endsWith(".css")) return "css";
  return null;
}

/** True for a URL the worker may store: this site (outside /__/) or the Firebase SDK on gstatic. */
function cacheable(url, origin) {
  const u = new URL(url);
  if (u.href.startsWith(FIREBASE_CDN)) return true;
  return u.origin === origin && !u.pathname.startsWith("/__/") && u.pathname !== "/portal-sw.js";
}

/**
 * URLs referenced by a page file, absolute and with their query string:
 * src/href starting with "/" in HTML, static import/export specifiers in JS, url() in CSS.
 */
function collectUrls(text, kind, base) {
  const origin = new URL(base).origin;
  const found = [];
  const patterns = {
    html: [/\s(?:src|href)\s*=\s*["'](\/(?!\/)[^"']*)["']/g],
    js: [
      /\b(?:import|export)\b[^"'`;]*?\bfrom\s*["']([^"']+)["']/g,
      /\bimport\s*["']([^"']+)["']/g,
    ],
    css: [/url\(\s*["']?(\/(?!\/)[^"')]+)["']?\s*\)/g],
  }[kind] || [];
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) {
      let url;
      try {
        url = new URL(match[1], base).href;
      } catch {
        continue;
      }
      if (cacheable(url, origin) && !found.includes(url)) found.push(url);
    }
  }
  return found;
}

/**
 * "navigate", "sdk" (pinned Firebase SDK, cache first), "asset" (other stored file, network first)
 * or null (not intercepted) for a request seen by the worker.
 */
function routeFor({ method, url, mode }, origin) {
  if (method !== "GET") return null;
  if (mode === "navigate") {
    const u = new URL(url);
    return u.origin === origin && u.pathname.startsWith("/v/") ? "navigate" : null;
  }
  if (!cacheable(url, origin)) return null;
  return url.startsWith(FIREBASE_SDK) ? "sdk" : "asset";
}

function requestFor(url, origin) {
  return new Request(url, { mode: new URL(url).origin === origin ? "same-origin" : "cors", cache: "no-cache" });
}

/** Fetches the shell and every file it needs, following HTML, JS imports and CSS url(). */
async function precache() {
  const origin = self.location.origin;
  const cache = await caches.open(CACHE);
  const shell = new URL(SHELL, origin).href;
  const seen = new Set([shell]);
  const queue = [shell];
  const others = [];
  while (queue.length) {
    const url = queue.shift();
    const kind = assetKind(url);
    if (!kind) {
      others.push(url);
      continue;
    }
    const response = await fetch(requestFor(url, origin));
    if (!response.ok) throw new Error(`precache ${url}: ${response.status}`);
    await cache.put(url, response.clone());
    for (const next of collectUrls(await response.text(), kind, url)) {
      if (seen.has(next)) continue;
      seen.add(next);
      queue.push(next);
    }
  }
  for (const path of FIXED) {
    const url = new URL(path, origin).href;
    if (!seen.has(url)) {
      seen.add(url);
      others.push(url);
    }
  }
  await cache.addAll(others.map((url) => requestFor(url, origin)));
}

async function networkFirst(event) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(event.request);
    // Every /v/ page is portal.html: keep the stored shell in step with the site.
    if (response.ok && !response.redirected) event.waitUntil(cache.put(SHELL, response.clone()));
    return response;
  } catch (error) {
    const shell = await cache.match(SHELL);
    if (shell) return shell;
    throw error;
  }
}

/** Site files change on deploy without always changing their URL: fresh copy first, stored one offline. */
async function networkFirstAsset(event) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(event.request);
    if (response.ok) event.waitUntil(cache.put(event.request, response.clone()));
    return response;
  } catch (error) {
    const hit = await cache.match(event.request, { ignoreVary: true });
    if (hit) return hit;
    throw error;
  }
}

async function cacheFirst(event) {
  const cache = await caches.open(CACHE);
  const hit = await cache.match(event.request, { ignoreVary: true });
  if (hit) return hit;
  const response = await fetch(event.request);
  if (response.ok) event.waitUntil(cache.put(event.request, response.clone()));
  return response;
}

if (typeof ServiceWorkerGlobalScope !== "undefined" && self instanceof ServiceWorkerGlobalScope) {
  self.addEventListener("install", (event) => {
    event.waitUntil(precache().then(() => self.skipWaiting()));
  });

  self.addEventListener("activate", (event) => {
    event.waitUntil((async () => {
      const names = await caches.keys();
      await Promise.all(names.filter((n) => n.startsWith("portal-") && n !== CACHE).map((n) => caches.delete(n)));
      await self.clients.claim();
    })());
  });

  self.addEventListener("fetch", (event) => {
    const route = routeFor(event.request, self.location.origin);
    if (route === "navigate") event.respondWith(networkFirst(event));
    else if (route === "sdk") event.respondWith(cacheFirst(event));
    else if (route === "asset") event.respondWith(networkFirstAsset(event));
  });
}
