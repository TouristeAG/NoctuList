// Regenerates firebase.json for the local emulators from the production Hosting config
// (webform/firebase.json): same rewrites and headers, with the CSP widened to the emulator
// ports on localhost and without upgrade-insecure-requests (emulators speak plain HTTP).
import { existsSync, lstatSync, readFileSync, symlinkSync, unlinkSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const prod = JSON.parse(readFileSync(here("../../webform/firebase.json"), "utf8"));
const hosting = prod.hosting;
// firebase-tools refuses paths outside this folder, so the site and the rules are linked in.
function link(target, name) {
  const path = here(name);
  if (existsSync(path) || (() => { try { return lstatSync(path).isSymbolicLink(); } catch { return false; } })()) {
    unlinkSync(path);
  }
  symlinkSync(target, path);
}
link("../../webform", "site");
link("../firestore.rules", "firestore.rules");
link("../storage.rules", "storage.rules");
hosting.public = "site";
const LOCAL = ["127.0.0.1", "localhost"];
const ports = [9099, 8080, 9199];
for (const rule of hosting.headers) {
  for (const header of rule.headers) {
    if (header.key !== "Content-Security-Policy") continue;
    const connect = LOCAL.flatMap((h) => ports.map((p) => `http://${h}:${p}`)).join(" ");
    const img = LOCAL.map((h) => `http://${h}:9199`).join(" ");
    header.value = header.value
      .replace("connect-src 'self'", `connect-src 'self' ${connect}`)
      .replace("img-src 'self' data: blob:", `img-src 'self' data: blob: ${img}`)
      .replace("; upgrade-insecure-requests", "");
  }
}
hosting.headers = hosting.headers.map((rule) => ({
  ...rule,
  headers: rule.headers.filter((h) => h.key !== "Strict-Transport-Security"),
}));
const config = {
  firestore: { rules: "firestore.rules" },
  storage: { rules: "storage.rules" },
  hosting,
  emulators: {
    auth: { port: 9099, host: "127.0.0.1" },
    firestore: { port: 8080, host: "127.0.0.1" },
    storage: { port: 9199, host: "127.0.0.1" },
    hosting: { port: 5002, host: "127.0.0.1" },
    ui: { enabled: false },
    singleProjectMode: true,
  },
};
writeFileSync(here("firebase.json"), JSON.stringify(config, null, 2) + "\n");
console.log("firebase.json regenerated for the emulators");
