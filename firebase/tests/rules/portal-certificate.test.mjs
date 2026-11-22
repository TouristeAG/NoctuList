// Security rules for the volunteer certificate request (CERTIFICATE_EMAIL) — run with `npm run test:rules`.
import assert from "node:assert/strict";
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { Timestamp, deleteDoc, doc, getDoc, serverTimestamp, setDoc, updateDoc } from "firebase/firestore";

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
  test("a PROCESSING certificate claim cannot be sent again, stale or not (the team resets it)", async () => {
    const db = portal().firestore();
    const claimed = (processingAt) => seed((sdb) => setDoc(doc(sdb, REQUEST), {
      ...certificate(), requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000),
      status: "PROCESSING", processingAt, processingDevice: "dev",
    }));
    await claimed(Date.now() - 60 * 1000);
    await assertFails(setDoc(doc(db, REQUEST), certificate()));
    await claimed(Date.now() - 10 * 60 * 1000);
    await assertFails(setDoc(doc(db, REQUEST), certificate()));
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

const APPROVAL = (id) => path(`certificateApprovals/${id}`);
const REQ_ID = "vol1_CERTIFICATE_EMAIL";

const seedRequest = (status, extra = {}) => seed((db) => setDoc(doc(db, REQUEST), {
  ...certificate(), requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000), status, ...extra,
}));
const seedMember = () => seed((db) => setDoc(doc(db, path("members/admin1")), { role: "ADMIN" }));

const paragraphs = [{ align: "LEFT", size: "NORMAL", bullet: false, runs: [{ text: "Hello", b: false, i: false, u: false }] }];
function approvalCommon(extra = {}) {
  return {
    body: paragraphs,
    footer: [],
    title: "Certificate",
    signer: { name: "Jane Doe", title: "President", imageBase64: "" },
    approvedBy: "admin1@team.example",
    approvedAt: Timestamp.now(),
    ...extra,
  };
}
const requestApproval = (id, extra = {}) => ({ kind: "REQUEST", requestId: REQ_ID, ...approvalCommon(), ...extra });
const directApproval = (extra = {}) => ({
  kind: "DIRECT", email: "ana@mail.example", firstName: "Ana", lastName: "Roe", ...approvalCommon(), ...extra,
});

describe("admin review of certificate requests", () => {
  test("portal cannot rewrite a request in review", async () => {
    const db = portal().firestore();
    for (const status of ["PENDING_REVIEW", "APPROVED", "PROCESSING"]) {
      await seedRequest(status, { processingAt: Date.now() - 60 * 60 * 1000, processingDevice: "dev", approvedAt: Date.now() });
      await assertFails(setDoc(doc(db, REQUEST), certificate()));
    }
    await seedRequest("REJECTED", { reason: "REFUSED", note: "no", processedAt: Date.now() });
    await assertSucceeds(setDoc(doc(db, REQUEST), certificate()));
  });
  test("portal reads its refused request and note", async () => {
    await seedRequest("REJECTED", { reason: "REFUSED", note: "Not enough shifts", processedAt: Date.now() });
    const snap = await assertSucceeds(getDoc(doc(portal().firestore(), REQUEST)));
    assert(snap.data().note === "Not enough shifts");
    await seedRequest("PENDING_REVIEW");
    await assertSucceeds(getDoc(doc(portal().firestore(), REQUEST)));
  });
  test("member approves a request in review", async () => {
    await seedMember();
    await seedRequest("PENDING_REVIEW", { reviewAt: Date.now(), processedAt: Date.now() });
    await assertSucceeds(updateDoc(doc(staff().firestore(), REQUEST), {
      status: "APPROVED", approvalId: "ap1", approvedAt: Date.now(), processingDevice: "dev1", processingAt: Date.now(),
    }));
  });
  test("member refuses with a note", async () => {
    await seedMember();
    await seedRequest("PENDING_REVIEW");
    await assertSucceeds(updateDoc(doc(staff().firestore(), REQUEST), {
      status: "REJECTED", reason: "REFUSED", note: "x".repeat(300), processedAt: Date.now(),
    }));
  });
  test("note over 300 is refused", async () => {
    await seedMember();
    await seedRequest("PENDING_REVIEW");
    await assertFails(updateDoc(doc(staff().firestore(), REQUEST), {
      status: "REJECTED", reason: "REFUSED", note: "x".repeat(301), processedAt: Date.now(),
    }));
    await assertFails(updateDoc(doc(staff().firestore(), REQUEST), {
      status: "REJECTED", reason: "REFUSED", note: 42, processedAt: Date.now(),
    }));
  });
  test("member resets a stale approval to review", async () => {
    await seedMember();
    for (const status of ["APPROVED", "PROCESSING"]) {
      await seedRequest(status, { approvalId: "ap1", approvedAt: Date.now() - 600000, processingAt: Date.now() - 600000 });
      await assertSucceeds(updateDoc(doc(staff().firestore(), REQUEST), { status: "PENDING_REVIEW", reason: "SEND_FAILED" }));
    }
  });
  test("approval needs an APPROVED request with the same id", async () => {
    await seedMember();
    const db = staff().firestore();
    await seedRequest("PENDING_REVIEW", { approvalId: "ap1" });
    await assertFails(setDoc(doc(db, APPROVAL("ap1")), requestApproval()));
    await seedRequest("APPROVED", { approvalId: "other" });
    await assertFails(setDoc(doc(db, APPROVAL("ap1")), requestApproval()));
    await seedRequest("APPROVED", { approvalId: "ap1" });
    await assertSucceeds(setDoc(doc(db, APPROVAL("ap1")), requestApproval()));
    await assertFails(setDoc(doc(db, APPROVAL("ap2")), requestApproval("x", { requestId: "nobody_CERTIFICATE_EMAIL" })));
    await assertFails(setDoc(doc(db, APPROVAL("ap3")), requestApproval("x", { footer: "text" })));
  });
  test("portal cannot read or write certificateApprovals", async () => {
    await seedMember();
    await seed((db) => setDoc(doc(db, APPROVAL("ap1")), directApproval()));
    await seedRequest("APPROVED", { approvalId: "ap2" });
    const db = portal().firestore();
    await assertFails(getDoc(doc(db, APPROVAL("ap1"))));
    await assertFails(setDoc(doc(db, APPROVAL("ap2")), requestApproval()));
    await assertFails(setDoc(doc(db, APPROVAL("ap4")), directApproval()));
    await assertFails(deleteDoc(doc(db, APPROVAL("ap1"))));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), APPROVAL("ap1"))));
  });
  test("members read and delete approvals but cannot edit them", async () => {
    await seedMember();
    await seed((db) => setDoc(doc(db, APPROVAL("ap1")), directApproval()));
    const db = staff().firestore();
    await assertSucceeds(getDoc(doc(db, APPROVAL("ap1"))));
    await assertFails(updateDoc(doc(db, APPROVAL("ap1")), { title: "Other" }));
    await assertSucceeds(deleteDoc(doc(db, APPROVAL("ap1"))));
  });
  test("member creates a direct approval", async () => {
    await seedMember();
    const db = staff().firestore();
    await assertSucceeds(setDoc(doc(db, APPROVAL("d1")), directApproval()));
    await assertSucceeds(setDoc(doc(db, APPROVAL("d2")), directApproval({ approvedAt: Date.now() })));
    await assertFails(setDoc(doc(db, APPROVAL("d3")), directApproval({ email: "not an address" })));
    await assertFails(setDoc(doc(db, APPROVAL("d4")), directApproval({ signer: { name: "a", title: "b", imageBase64: "x".repeat(200001) } })));
    const { firstName, ...noName } = directApproval();
    await assertFails(setDoc(doc(db, APPROVAL("d5")), noName));
    await assertFails(setDoc(doc(db, APPROVAL("d6")), { ...directApproval(), kind: "OTHER" }));
  });
  test("direct approval with an extra field is refused", async () => {
    await seedMember();
    const db = staff().firestore();
    await assertFails(setDoc(doc(db, APPROVAL("d1")), directApproval({ volunteerId: "abc" })));
    await assertFails(setDoc(doc(db, APPROVAL("d2")), directApproval({ status: "PROCESSING" })));
    await assertFails(setDoc(doc(db, APPROVAL("d3")), directApproval({ requestId: REQ_ID })));
  });
});
