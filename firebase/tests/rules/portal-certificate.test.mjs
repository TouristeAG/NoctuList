// Security rules for the volunteer certificate request (CERTIFICATE_EMAIL) — run with `npm run test:rules`.
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { Timestamp, doc, getDoc, serverTimestamp, setDoc } from "firebase/firestore";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const path = (p) => `orgs/${ORG}/${p}`;
const REQUEST = path("portalRequests/vol1_CERTIFICATE_EMAIL");
const [HOST, PORT] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080").split(":");

let env;

const portal = (uid = "vol1") =>
  env.authenticatedContext(uid, {
    email: `${uid}@mail.example`,
    email_verified: true,
    firebase: { sign_in_provider: "password" },
  });
const staff = (uid = "admin1") =>
  env.authenticatedContext(uid, {
    email: `${uid}@team.example`,
    email_verified: true,
    firebase: { sign_in_provider: "google.com" },
  });

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
}
const setSetting = (key, value) =>
  seed((db) => setDoc(doc(db, path(`institutionSettings/${key}`)), { value, lastModified: Date.now() }));

function certificate(uid = "vol1", extra = {}) {
  return {
    uid,
    type: "CERTIFICATE_EMAIL",
    status: "PENDING",
    requestedAt: serverTimestamp(),
    email: `${uid}@mail.example`,
    from: "2026-01-01",
    to: "2026-06-30",
    jobTypes: ["Bar", "Accueil"],
    ...extra,
  };
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-noctulist",
    firestore: { rules: readFileSync(here("../firestore.rules"), "utf8"), host: HOST, port: Number(PORT) },
  });
});
after(async () => {
  await env?.cleanup();
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed((db) => setDoc(doc(db, path("metadata/config")), { createdAt: Date.now() }));
  await setSetting("volunteer_portal_enabled", "true");
  await setSetting("volunteer_portal_certificate_enabled", "true");
});

describe("CERTIFICATE_EMAIL portal request", () => {
  test("volunteer creates a valid certificate request", async () => {
    await assertSucceeds(setDoc(doc(portal().firestore(), REQUEST), certificate()));
  });
  test("boolean true setting also enables it", async () => {
    await setSetting("volunteer_portal_certificate_enabled", true);
    await assertSucceeds(setDoc(doc(portal().firestore(), REQUEST), certificate()));
  });
  test("refused when the option is off", async () => {
    await setSetting("volunteer_portal_certificate_enabled", "false");
    await assertFails(setDoc(doc(portal().firestore(), REQUEST), certificate()));
  });
  test("refused when the option is absent", async () => {
    await seed((db) => import("firebase/firestore").then(({ deleteDoc }) =>
      deleteDoc(doc(db, path("institutionSettings/volunteer_portal_certificate_enabled")))));
    await assertFails(setDoc(doc(portal().firestore(), REQUEST), certificate()));
  });
  test("refused when the portal is off", async () => {
    await setSetting("volunteer_portal_enabled", "false");
    await assertFails(setDoc(doc(portal().firestore(), REQUEST), certificate()));
  });
  test("refused with an extra key", async () => {
    await assertFails(setDoc(doc(portal().firestore(), REQUEST), certificate("vol1", { volunteerId: "abc" })));
  });
  test("refused with a missing key", async () => {
    const { jobTypes, ...rest } = certificate();
    await assertFails(setDoc(doc(portal().firestore(), REQUEST), rest));
  });
  test("refused with 51 job types, accepted with 50", async () => {
    const many = (n) => Array.from({ length: n }, (_, i) => `Job ${i}`);
    await assertFails(setDoc(doc(portal().firestore(), REQUEST), certificate("vol1", { jobTypes: many(51) })));
    await assertSucceeds(setDoc(doc(portal().firestore(), REQUEST), certificate("vol1", { jobTypes: many(50) })));
  });
  test("refused with no job type or a non-list", async () => {
    const db = portal().firestore();
    await assertFails(setDoc(doc(db, REQUEST), certificate("vol1", { jobTypes: [] })));
    await assertFails(setDoc(doc(db, REQUEST), certificate("vol1", { jobTypes: "Bar" })));
  });
  test("refused with from after to, accepted with from equal to", async () => {
    const db = portal().firestore();
    await assertFails(setDoc(doc(db, REQUEST), certificate("vol1", { from: "2026-07-01", to: "2026-06-30" })));
    await assertSucceeds(setDoc(doc(db, REQUEST), certificate("vol1", { from: "2026-06-30", to: "2026-06-30" })));
  });
  test("refused with a malformed or non-string date", async () => {
    const db = portal().firestore();
    await assertFails(setDoc(doc(db, REQUEST), certificate("vol1", { from: "2026-13-01" })));
    await assertFails(setDoc(doc(db, REQUEST), certificate("vol1", { to: "30.06.2026" })));
    await assertFails(setDoc(doc(db, REQUEST), certificate("vol1", { from: 20260101 })));
  });
  test("refused for another volunteer's uid or the wrong document id", async () => {
    await assertFails(setDoc(doc(portal("vol2").firestore(), REQUEST), certificate("vol1")));
    await assertFails(setDoc(doc(portal().firestore(), path("portalRequests/vol1_QR_EMAIL")), certificate()));
  });
  test("refused again before 10 minutes, accepted after", async () => {
    const db = portal().firestore();
    await assertSucceeds(setDoc(doc(db, REQUEST), certificate()));
    await assertFails(setDoc(doc(db, REQUEST), certificate()));
    await seed((sdb) => setDoc(doc(sdb, REQUEST), {
      ...certificate(), requestedAt: Timestamp.fromMillis(Date.now() - 5 * 60 * 1000), status: "DONE", processedAt: Date.now(),
    }));
    await assertFails(setDoc(doc(db, REQUEST), certificate()));
    await seed((sdb) => setDoc(doc(sdb, REQUEST), {
      ...certificate(), requestedAt: Timestamp.fromMillis(Date.now() - 11 * 60 * 1000), status: "DONE", processedAt: Date.now(),
    }));
    await assertSucceeds(setDoc(doc(db, REQUEST), certificate()));
  });
  test("a stale PROCESSING claim can be sent again, a fresh one cannot", async () => {
    const db = portal().firestore();
    const claimed = (processingAt) => seed((sdb) => setDoc(doc(sdb, REQUEST), {
      ...certificate(), requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000),
      status: "PROCESSING", processingAt, processingDevice: "dev",
    }));
    await claimed(Date.now() - 60 * 1000);
    await assertFails(setDoc(doc(db, REQUEST), certificate()));
    await claimed(Date.now() - 10 * 60 * 1000);
    await assertSucceeds(setDoc(doc(db, REQUEST), certificate()));
  });
  test("owner reads its own request, others cannot", async () => {
    await seed((db) => setDoc(doc(db, REQUEST), { ...certificate(), requestedAt: Timestamp.now() }));
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), REQUEST)));
    await assertFails(getDoc(doc(portal("vol2").firestore(), REQUEST)));
  });
  test("portal user cannot read portalMail/certificate, a team member can", async () => {
    await seed(async (db) => {
      await setDoc(doc(db, path("portalMail/certificate")), { subject: "x" });
      await setDoc(doc(db, path("members/admin1")), { role: "ADMIN" });
    });
    await assertFails(getDoc(doc(portal("vol1").firestore(), path("portalMail/certificate"))));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), path("portalMail/certificate"))));
    await assertSucceeds(getDoc(doc(staff().firestore(), path("portalMail/certificate"))));
  });
});
