import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// The worker is a classic script: run it in a context without ServiceWorkerGlobalScope, so only
// its helper functions are defined.
const source = readFileSync(new URL("../site/portal-sw.js", import.meta.url), "utf8");
const sw = vm.createContext({ URL });
vm.runInContext(source, sw);
const plain = (value) => JSON.parse(JSON.stringify(value));

const ORIGIN = "https://portal.example.org";
const CDN = "https://www.gstatic.com/firebasejs/10.14.1";

test("collectsHtmlAssetsWithTheirQuery", () => {
  const html = readFileSync(new URL("../site/portal.html", import.meta.url), "utf8");
  const urls = plain(sw.collectUrls(html, "html", `${ORIGIN}/portal.html`));
  assert.ok(urls.some((u) => /^https:\/\/portal\.example\.org\/portal\.js\?v=[\w-]+$/.test(u)), urls.join("\n"));
  assert.ok(urls.includes(`${ORIGIN}/theme.css?v=perf-1`));
  assert.ok(urls.includes(`${ORIGIN}/manifest.webmanifest`));
  assert.ok(urls.includes(`${ORIGIN}/icons/apple-touch-icon.png`));
  assert.ok(urls.every((u) => u.startsWith(`${ORIGIN}/`)));
});

test("collectsPortalJsImports", () => {
  const js = readFileSync(new URL("../site/portal.js", import.meta.url), "utf8");
  const urls = plain(sw.collectUrls(js, "js", `${ORIGIN}/portal.js?v=qr-2`));
  assert.ok(urls.includes(`${CDN}/firebase-app.js`));
  assert.ok(urls.includes(`${CDN}/firebase-auth.js`));
  assert.ok(urls.includes(`${CDN}/firebase-firestore.js`));
  assert.ok(urls.includes(`${CDN}/firebase-storage.js`));
  assert.ok(urls.includes(`${ORIGIN}/firebase-env.js?v=portal-2`));
  assert.ok(urls.includes(`${ORIGIN}/portal-qr.js?v=guest-8`));
  assert.ok(urls.includes(`${ORIGIN}/portal-common.js?v=guest-8`));
});

test("collectsMinifiedAndRelativeImports", () => {
  const js = 'import{a as b}from"./firebase-app.js";export*from"https://www.gstatic.com/firebasejs/10.14.1/x.js";import"./side.js";import q from "https://cdn.example.com/q.js";';
  const urls = plain(sw.collectUrls(js, "js", `${CDN}/firebase-auth.js`));
  assert.deepEqual(urls, [`${CDN}/firebase-app.js`, `${CDN}/x.js`, `${CDN}/side.js`]);
  const local = plain(sw.collectUrls('import qrcode from "./vendor/qrcode.js";', "js", `${ORIGIN}/portal-qr.js?v=qr-2`));
  assert.deepEqual(local, [`${ORIGIN}/vendor/qrcode.js`]);
});

test("collectsCssFonts", () => {
  const css = '@font-face { src: url("/fonts/nunito-400.ttf") format("truetype"); } a { background: url(data:image/png;base64,AA) }';
  assert.deepEqual(plain(sw.collectUrls(css, "css", `${ORIGIN}/theme.css?v=perf-1`)), [`${ORIGIN}/fonts/nunito-400.ttf`]);
});

test("routesNavigationUnderVOnly", () => {
  assert.equal(sw.routeFor({ method: "GET", url: `${ORIGIN}/v/org1`, mode: "navigate" }, ORIGIN), "navigate");
  assert.equal(sw.routeFor({ method: "GET", url: `${ORIGIN}/s/org1`, mode: "navigate" }, ORIGIN), null);
  assert.equal(sw.routeFor({ method: "GET", url: `${ORIGIN}/__/auth/handler`, mode: "navigate" }, ORIGIN), null);
});

test("siteFilesNetworkFirstPinnedSdkCacheFirst", () => {
  for (const path of ["/portal.js?v=qr-2", "/portal-i18n.js?v=qr-3", "/vendor/qrcode.js", "/fonts/nunito-400.ttf", "/manifest.webmanifest", "/icons/icon-192.png"]) {
    assert.equal(sw.routeFor({ method: "GET", url: `${ORIGIN}${path}`, mode: "cors" }, ORIGIN), "asset", path);
  }
  assert.equal(sw.routeFor({ method: "GET", url: `${CDN}/firebase-app.js`, mode: "cors" }, ORIGIN), "sdk");
  assert.equal(sw.routeFor({ method: "GET", url: "https://www.gstatic.com/firebasejs/11.0.0/firebase-app.js", mode: "cors" }, ORIGIN), "asset");
});

test("cacheNameBumped", () => {
  assert.equal(vm.runInContext("CACHE", sw), "portal-v13");
});

test("i18nImportedWithOneSpecifier", () => {
  const specs = ["portal.js", "signup.js"].map((f) =>
    readFileSync(new URL(`../site/${f}`, import.meta.url), "utf8").match(/"\.\/portal-i18n\.js(\?v=[\w-]+)?"/)[0]);
  assert.equal(specs[0], specs[1]);
  assert.ok(specs[0].includes("?v="), specs[0]);
});

test("leavesFirebaseServicesAndWritesAlone", () => {
  const skip = [
    "https://firestore.googleapis.com/google.firestore.v1.Firestore/Listen/channel?x=1",
    "https://identitytoolkit.googleapis.com/v1/accounts:lookup",
    "https://securetoken.googleapis.com/v1/token",
    "https://firebasestorage.googleapis.com/v0/b/x/o/y",
    "https://apis.google.com/js/api.js",
    "https://www.gstatic.com/recaptcha/x.js",
    `${ORIGIN}/__/firebase/init.json`,
    `${ORIGIN}/__/auth/iframe.js`,
    `${ORIGIN}/portal-sw.js`,
    "http://127.0.0.1:8080/google.firestore.v1.Firestore/Listen",
  ];
  for (const url of skip) assert.equal(sw.routeFor({ method: "GET", url, mode: "cors" }, ORIGIN), null, url);
  assert.equal(sw.routeFor({ method: "POST", url: `${ORIGIN}/portal.js`, mode: "cors" }, ORIGIN), null);
});

test("assetKindByExtension", () => {
  assert.equal(sw.assetKind(`${ORIGIN}/portal.html`), "html");
  assert.equal(sw.assetKind(`${ORIGIN}/portal.js?v=qr-2`), "js");
  assert.equal(sw.assetKind(`${ORIGIN}/theme.css?v=perf-1`), "css");
  assert.equal(sw.assetKind(`${ORIGIN}/icons/icon-192.png`), null);
});
