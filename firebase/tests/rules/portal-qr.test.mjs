// Security rules for the volunteer portal QR document — run with `npm run test:rules`.
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc } from "firebase/firestore";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const path = (p) => `orgs/${ORG}/${p}`;
const QR = path("volunteerPortalQr/vol1");

let env;

const portal = (uid = "vol1") =>
  env.authenticatedContext(uid, {
    email: `${uid}@mail.example`,
    email_verified: true,
    firebase: { sign_in_provider: "password" },
  });
const google = (uid, email = `${uid}@asso.example`) =>
  env.authenticatedContext(uid, { email, email_verified: true, firebase: { sign_in_provider: "google.com" } });

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
}
const setSetting = (key, value) =>
  seed((db) => setDoc(doc(db, path(`institutionSettings/${key}`)), { value, lastModified: Date.now() }));
const clearSetting = (key) => seed((db) => deleteDoc(doc(db, path(`institutionSettings/${key}`))));

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-noctulist",
    firestore: { rules: readFileSync(here("../firestore.rules"), "utf8"), host: "127.0.0.1", port: 8080 },
  });
});
after(async () => {
  await env?.cleanup();
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (db) => {
    await setDoc(doc(db, path("metadata/config")), { createdAt: Date.now() });
    await setDoc(doc(db, QR), { schema: 1, payload: "VOL1", name: "Léa M" });
    await setDoc(doc(db, path("portalGoogleAccounts/g1")), {
      volunteerUid: "vol1", email: "g1@asso.example", linkedBy: "LINK_CODE", linkedAt: Date.now(),
    });
  });
  await setSetting("volunteer_portal_enabled", "true");
  await setSetting("volunteer_portal_google_enabled", "true");
  await setSetting("volunteer_portal_google_domains", "asso.example");
});

describe("volunteerPortalQr", () => {
  test("ownerReadsQr", async () => {
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), QR)));
  });
  test("linkedGoogleReadsQr", async () => {
    await assertSucceeds(getDoc(doc(google("g1").firestore(), QR)));
  });
  test("otherVolunteerCannotReadQr", async () => {
    await assertFails(getDoc(doc(portal("vol2").firestore(), QR)));
  });
  test("anonymousCannotReadQr", async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), QR)));
  });
  test("qrBlockedWhenSettingStringFalse", async () => {
    await setSetting("volunteer_portal_qr_enabled", "false");
    await assertFails(getDoc(doc(portal("vol1").firestore(), QR)));
  });
  test("qrBlockedWhenSettingBoolFalse", async () => {
    await setSetting("volunteer_portal_qr_enabled", false);
    await assertFails(getDoc(doc(portal("vol1").firestore(), QR)));
  });
  test("qrReadableWhenSettingAbsent", async () => {
    await clearSetting("volunteer_portal_qr_enabled");
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), QR)));
  });
  test("qrReadableWhenSettingTrue", async () => {
    await setSetting("volunteer_portal_qr_enabled", "true");
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), QR)));
    await setSetting("volunteer_portal_qr_enabled", true);
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), QR)));
  });
  test("qrBlockedWhenPortalOff", async () => {
    await setSetting("volunteer_portal_enabled", "false");
    await assertFails(getDoc(doc(portal("vol1").firestore(), QR)));
  });
  test("ownerCannotListOrWrite", async () => {
    const db = portal("vol1").firestore();
    await assertFails(getDocs(collection(db, path("volunteerPortalQr"))));
    await assertFails(setDoc(doc(db, QR), { schema: 1, payload: "X", name: "X" }));
    await assertFails(deleteDoc(doc(db, QR)));
  });
});
