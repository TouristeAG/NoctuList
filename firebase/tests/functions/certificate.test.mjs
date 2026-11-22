// Integration tests for the CERTIFICATE_EMAIL request (volunteer certificate PDF by e-mail). Run with
// `npm run test:functions`: same setup as requests.test.mjs (emulators, mail captured to `_testMail`).
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";

const PROJECT = "demo-noctulist";
// 1x1 PNG.
const LOGO_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
const TEMPLATE = JSON.parse(
  readFileSync(new URL("../../../webform/functions/test/certificate-default-fr.json", import.meta.url), "utf8"),
);
// 2024-03-10 and 2024-03-17 (noon UTC): well in the past.
const DAY_1 = Date.UTC(2024, 2, 10, 12);
const DAY_2 = Date.UTC(2024, 2, 17, 12);

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run through `npm run test:functions`: the emulators must be running.");
}

let app;
let db;
let counter = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(what, check, timeoutMs = 30_000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await sleep(250);
  }
}

/** A fresh organization per test; the certificate option is on and a template is published. */
async function newOrg({ enabled = true, published = true } = {}) {
  counter += 1;
  const org = `org-cert-${counter}-${Date.now()}`;
  const now = Date.now();
  await db.doc(`orgs/${org}/metadata/config`).set({ createdAt: now });
  await db.doc(`orgs/${org}/institutionSettings/volunteer_portal_enabled`).set({ value: "true", lastModified: now });
  await db.doc(`orgs/${org}/portalBranding/default`).set({ associationName: "Asso Test", logoDataUri: "", lastModified: now });
  if (enabled) {
    await db.doc(`orgs/${org}/institutionSettings/volunteer_portal_certificate_enabled`).set({ value: true, lastModified: now });
  }
  if (published) await publish(org);
  return org;
}

async function publish(org, overrides = {}) {
  await db.doc(`orgs/${org}/portalMail/certificate`).set({
    template: {
      ...TEMPLATE,
      mail: { fromName: "Comité", fromAddress: "", replyTo: "reponses@mail.example", subject: "Ton attestation, {firstName}", body: "Bonjour {firstName}, voici ton attestation pour {association}." },
      signer: { name: "Jean Dupont", title: "Président", imageBase64: "" },
    },
    association: "Asso Test",
    logoBase64: LOGO_BASE64,
    jobTypes: [
      { name: "Bar", category: "SHIFT", description: "Service au bar" },
      { name: "Sécurité", category: "SHIFT", description: "" },
    ],
    updatedAt: Date.now(),
    ...overrides,
  });
}

function newUid(prefix) {
  counter += 1;
  return `${prefix}${String(counter).padStart(4, "0")}${Date.now().toString(36)}`;
}

async function addVolunteer(org, overrides = {}) {
  counter += 1;
  const id = `vol${String(counter).padStart(4, "0")}${Date.now().toString(36)}`.padEnd(21, "x").slice(0, 21);
  const data = {
    id, name: "Léa", lastNameAbbreviation: "MARTIN", email: "lea@mail.example", phone: "079 000 00 00",
    dateOfBirth: "1998-04-12", gender: "FEMALE", currentRank: "NOVA", isActive: true, lastModified: 1000,
    isAdmin: false, portalEnabled: true, portalUid: "", ...overrides,
  };
  await db.doc(`orgs/${org}/volunteers/${id}`).set(data);
  return data;
}

async function addJob(org, volunteerId, jobTypeName, date, extra = {}) {
  counter += 1;
  await db.doc(`orgs/${org}/jobs/job${counter}x${Date.now().toString(36)}`).set({
    volunteerId, jobTypeName, venueName: "Main", date, ...extra,
  });
}

/** Writes a request the way the portal page does. */
async function sendCertificate(org, uid, fields = {}) {
  const ref = db.doc(`orgs/${org}/portalRequests/${uid}_CERTIFICATE_EMAIL`);
  await ref.set({
    uid,
    type: "CERTIFICATE_EMAIL",
    status: "PENDING",
    requestedAt: FieldValue.serverTimestamp(),
    email: `${uid.toLowerCase()}@auth.example`,
    from: "2024-01-01",
    to: "2024-12-31",
    jobTypes: ["Bar"],
    ...fields,
  });
  return ref;
}

async function waitResult(ref) {
  return waitFor(`${ref.path} to be processed`, async () => {
    const d = (await ref.get()).data();
    return d && (d.status === "DONE" || d.status === "REJECTED") ? d : null;
  });
}

async function capturedMailsTo(address) {
  const snap = await db.collection("_testMail").where("to", "==", address).get();
  return snap.docs.map((d) => d.data());
}

async function waitMailTo(address) {
  const [mail] = await waitFor(`mail to ${address}`, async () => {
    const mails = await capturedMailsTo(address);
    return mails.length > 0 ? mails : null;
  });
  return mail;
}

function assertResult(doc, status, reason) {
  assert.equal(doc.status, status);
  assert.equal(doc.reason, reason);
  assert.equal(typeof doc.processedAt, "number");
  assert.equal(doc.processingDevice, "server");
}

before(async () => {
  app = initializeApp({ projectId: PROJECT }, "certificate-tests");
  db = getFirestore(app);
});

after(async () => {
  if (app) await deleteApp(app);
});

describe("onPortalRequestWritten: CERTIFICATE_EMAIL", () => {
  test("sends a certificate", async () => {
    const org = await newOrg();
    const uid = newUid("certOk");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    await addJob(org, v.id, "Bar", DAY_1);
    await addJob(org, v.id, "Bar", DAY_2);
    await addJob(org, v.id, "Sécurité", DAY_1);
    // Another volunteer's job and one outside the period never count.
    const other = await addVolunteer(org, { portalUid: newUid("other") });
    await addJob(org, other.id, "Bar", DAY_1);
    await addJob(org, v.id, "Bar", Date.UTC(2023, 5, 1));

    const ref = await sendCertificate(org, uid);
    const result = await waitResult(ref);
    assertResult(result, "DONE", "");

    const mail = await waitMailTo(email);
    assert.equal(mail.subject, "Ton attestation, Léa");
    assert.deepEqual(mail.from, { name: "Comité", address: "test@example.org" });
    assert.equal(mail.replyTo, "reponses@mail.example");
    assert.equal(mail.text, "Bonjour Léa, voici ton attestation pour Asso Test.");
    const pdf = mail.attachments.find((a) => a.contentType === "application/pdf");
    assert.ok(pdf, "PDF attached");
    assert.equal(pdf.filename, "attestation-benevolat-lea-martin.pdf");
    assert.equal(pdf.disposition, "attachment");
    assert.ok(pdf.size > 1000);
    assert.ok(mail.attachments.some((a) => a.cid === "logo"));
    assert.equal((await capturedMailsTo(`${uid.toLowerCase()}@auth.example`)).length, 0, "never to the request address");

    assert.ok(!JSON.stringify(mail).includes(v.id), "volunteer id absent from the captured mail");
    assert.ok(!JSON.stringify(result).includes(v.id), "volunteer id absent from the request");
    assert.ok(!JSON.stringify((await ref.get()).data()).includes(v.id));
  });

  test("refuses when the option is off", async () => {
    const org = await newOrg({ enabled: false });
    const uid = newUid("certOff");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    await addJob(org, v.id, "Bar", DAY_1);
    assertResult(await waitResult(await sendCertificate(org, uid)), "REJECTED", "NOT_ALLOWED");
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("refuses without a published template", async () => {
    const org = await newOrg({ published: false });
    const uid = newUid("certNoTpl");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    await addJob(org, v.id, "Bar", DAY_1);
    assertResult(await waitResult(await sendCertificate(org, uid)), "REJECTED", "NO_TEMPLATE");
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("rejects an empty selection", async () => {
    const org = await newOrg();
    const uid = newUid("certEmpty");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    // Only a "Sécurité" job and a "Bar" job after the period; "Bar" is the checked type.
    await addJob(org, v.id, "Sécurité", DAY_1);
    await addJob(org, v.id, "Bar", Date.UTC(2025, 0, 5));
    assertResult(await waitResult(await sendCertificate(org, uid)), "REJECTED", "NOTHING_TO_CERTIFY");
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("skips jobs without a usable date or type", async () => {
    const org = await newOrg();
    const uid = newUid("certBad");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    await addJob(org, v.id, "Bar", null);
    await addJob(org, v.id, "Bar", "not a date");
    await addJob(org, v.id, "  ", DAY_1);
    await addJob(org, v.id, "Bar", DAY_1);
    assertResult(await waitResult(await sendCertificate(org, uid)), "DONE", "");
    await waitMailTo(email);
  });

  test("accepts an admin volunteer", async () => {
    const org = await newOrg();
    const uid = newUid("certAdmin");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email, isAdmin: true });
    await addJob(org, v.id, "Bar", DAY_1);
    assertResult(await waitResult(await sendCertificate(org, uid)), "DONE", "");
    await waitMailTo(email);
  });

  test("refuses without access or without a usable address", async () => {
    const org = await newOrg();
    const stranger = newUid("certNone");
    assertResult(await waitResult(await sendCertificate(org, stranger)), "REJECTED", "NO_ACCESS");
    const revoked = newUid("certRevoked");
    await addVolunteer(org, { portalUid: revoked, portalEnabled: false });
    assertResult(await waitResult(await sendCertificate(org, revoked)), "REJECTED", "NO_ACCESS");
    const noMail = newUid("certNoMail");
    await addVolunteer(org, { portalUid: noMail, email: "a@b.c, d@e.f" });
    assertResult(await waitResult(await sendCertificate(org, noMail)), "REJECTED", "NO_EMAIL");
  });

  test("rejects a malformed request", async () => {
    const org = await newOrg();
    const uid = newUid("certInvalid");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    await addJob(org, v.id, "Bar", DAY_1);
    assertResult(await waitResult(await sendCertificate(org, uid, { from: "2024-12-31", to: "2024-01-01" })), "REJECTED", "INVALID");
    assert.equal((await capturedMailsTo(email)).length, 0);
  });
});
