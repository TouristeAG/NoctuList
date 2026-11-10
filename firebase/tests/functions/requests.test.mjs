// Integration tests for the portal request Cloud Function (PROFILE, CLAIM, QR_EMAIL). Run with
// `npm run test:functions`: the emulators run Firestore and the functions, mail is captured to
// `_testMail` (PORTAL_MAIL_TRANSPORT=capture in .env.local), and this file polls for the results.
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";

const PROJECT = "demo-noctulist";
const TOKEN = "%%NOCTULIST_FIRST_NAME%%";
// 1x1 PNG.
const LOGO_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run through `npm run test:functions`: the emulators must be running.");
}

let app;
let db;
let counter = 0;

const sha256 = (s) => createHash("sha256").update(s, "utf8").digest("hex");
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

/** A fresh organization per test, so a late trigger of one test cannot touch another. */
async function newOrg({ portal = "true", association = "Asso Test" } = {}) {
  counter += 1;
  const org = `org-rq-${counter}-${Date.now()}`;
  const now = Date.now();
  await db.doc(`orgs/${org}/metadata/config`).set({ createdAt: now });
  await db.doc(`orgs/${org}/institutionSettings/volunteer_portal_enabled`).set({ value: portal, lastModified: now });
  if (association !== null) {
    await db.doc(`orgs/${org}/portalBranding/default`).set({ associationName: association, logoDataUri: "", lastModified: now });
  }
  return org;
}

function newUid(prefix) {
  counter += 1;
  return `${prefix}${String(counter).padStart(4, "0")}${Date.now().toString(36)}`;
}

function newVolunteerId() {
  counter += 1;
  return `vol${String(counter).padStart(4, "0")}${Date.now().toString(36)}`.padEnd(21, "x").slice(0, 21);
}

/** A roster entry in the format of GitLiveFirestoreGateway.volunteerToMap. */
async function addVolunteer(org, overrides = {}) {
  const id = overrides.id ?? newVolunteerId();
  const data = {
    id,
    name: "Léa",
    lastNameAbbreviation: "MARTIN",
    email: "lea@mail.example",
    phone: "079 000 00 00",
    dateOfBirth: "1998-04-12",
    gender: "FEMALE",
    currentRank: "NOVA",
    isActive: true,
    lastShiftDate: 123,
    lastModified: 1000,
    nfcCardUid: "04AABBCC",
    nfcCardUidHash: "hash",
    isAdmin: false,
    profilePhotoPath: `orgs/${org}/profilePhotos/volunteers/${id}.jpg`,
    portalEnabled: true,
    portalUid: "",
    ...overrides,
  };
  await db.doc(`orgs/${org}/volunteers/${id}`).set(data);
  return data;
}

async function volunteer(org, id) {
  return (await db.doc(`orgs/${org}/volunteers/${id}`).get()).data();
}

/** Writes a request the way the portal page does. */
async function sendRequest(org, uid, type, fields = {}) {
  const ref = db.doc(`orgs/${org}/portalRequests/${uid}_${type}`);
  await ref.set({
    uid,
    type,
    status: "PENDING",
    requestedAt: FieldValue.serverTimestamp(),
    email: `${uid.toLowerCase()}@auth.example`,
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

async function publishTemplate(org, overrides = {}) {
  await db.doc(`orgs/${org}/portalMail/default`).set({
    subject: "Ton QR code bénévole",
    html: `<html><body><img src="cid:logo"><p>Salut ${TOKEN},</p><img src="cid:qrcode" alt="QR"></body></html>`,
    text: "Voici ton QR code.",
    logoBase64: "",
    schema: 1,
    lastModified: Date.now(),
    ...overrides,
  });
}

/** Proves the function is running: a valid request in another org gets processed. */
async function barrier() {
  const org = await newOrg();
  const uid = newUid("barrier");
  await addVolunteer(org, { portalUid: uid });
  const ref = await sendRequest(org, uid, "PROFILE", { firstName: "Max", lastName: "Roe", phone: "079 111 22 33" });
  await waitResult(ref);
  await sleep(1000);
}

function assertResult(doc, status, reason) {
  assert.equal(doc.status, status);
  assert.equal(doc.reason, reason);
  assert.equal(typeof doc.processedAt, "number");
  assert.equal(doc.processingDevice, "server");
}

before(async () => {
  app = initializeApp({ projectId: PROJECT }, "request-tests");
  db = getFirestore(app);
  await fetch(
    `http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,
    { method: "DELETE" },
  );
});

after(async () => {
  if (app) await deleteApp(app);
});

describe("onPortalRequestWritten: PROFILE", () => {
  test("profileUpdatesVolunteerAndProjection", async () => {
    const org = await newOrg();
    const uid = newUid("profile");
    // A lastModified in the future: the update must still move it forward.
    const future = Date.now() + 10 * 60 * 1000;
    const existing = await addVolunteer(org, { portalUid: uid, lastModified: future });
    const payload = JSON.stringify({
      firstName: "Léa", lastName: "MARTIN", email: "lea@mail.example", phone: "079 000 00 00",
      dateOfBirth: "1998-04-12", gender: "FEMALE", hasPhoto: true, photoVersion: "abcdef0123456789",
      shifts: [{ date: 1, venue: "Main" }], lastShiftFloor: 0,
      timeline: [{ from: 0, active: true, summary: "x", benefits: [], uniqueEntriesRemaining: null }],
      wallet: { balance: 12.5, currency: "CHF", transfers: [] },
      qrEmailAllowed: true, mustSetPassword: false, association: "Asso Test",
    });
    await db.doc(`orgs/${org}/volunteerPortal/${uid}`).set({ schema: 2, payload, contentHash: sha256(payload), updatedAt: 1 });

    const ref = await sendRequest(org, uid, "PROFILE", {
      firstName: "  Léa   Marie ", lastName: "dupont-durand", phone: " +41 79 123 45 67 ",
    });
    assertResult(await waitResult(ref), "DONE", "");

    const v = await volunteer(org, existing.id);
    assert.equal(v.name, "Léa Marie");
    assert.equal(v.lastNameAbbreviation, "DUPONT-DURAND");
    assert.equal(v.phone, "+41 79 123 45 67");
    assert.equal(v.lastModified, future + 1);
    assert.deepEqual(
      { ...v, name: existing.name, lastNameAbbreviation: existing.lastNameAbbreviation, phone: existing.phone, lastModified: existing.lastModified },
      existing,
      "admin, NFC, photo and every other field stay untouched",
    );

    const projection = (await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data();
    assert.equal(projection.schema, 2);
    assert.equal(projection.contentHash, sha256(projection.payload));
    assert.ok(projection.updatedAt > 1);
    const before = JSON.parse(payload);
    const patched = JSON.parse(projection.payload);
    assert.deepEqual(Object.keys(patched), Object.keys(before), "key order kept");
    assert.deepEqual(patched, { ...before, firstName: "Léa Marie", lastName: "DUPONT-DURAND", phone: "+41 79 123 45 67" });
    assert.ok(!projection.payload.includes(existing.id));
  });

  test("profileLastModifiedIsNowWhenPreviousIsOld", async () => {
    const org = await newOrg();
    const uid = newUid("profileOld");
    const existing = await addVolunteer(org, { portalUid: uid, lastModified: 1000 });
    const start = Date.now();
    const ref = await sendRequest(org, uid, "PROFILE", { firstName: "Max", lastName: "Roe", phone: "0791112233" });
    assertResult(await waitResult(ref), "DONE", "");
    const v = await volunteer(org, existing.id);
    assert.ok(v.lastModified >= start);
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, false, "no projection created");
  });

  test("profileWithoutAccessRejected", async () => {
    const org = await newOrg();
    const uid = newUid("profileNoAccess");
    // Linked to this account but the portal access was removed.
    const existing = await addVolunteer(org, { portalUid: uid, portalEnabled: false });
    const ref = await sendRequest(org, uid, "PROFILE", { firstName: "Max", lastName: "Roe", phone: "0791112233" });
    assertResult(await waitResult(ref), "REJECTED", "NO_ACCESS");
    assert.deepEqual(await volunteer(org, existing.id), existing);
  });

  test("invalidRequestRejected", async () => {
    const org = await newOrg();
    const uid = newUid("invalid");
    const existing = await addVolunteer(org, { portalUid: uid });
    const ref = await sendRequest(org, uid, "PROFILE", { firstName: "<script>", lastName: "Roe", phone: "0791112233" });
    assertResult(await waitResult(ref), "REJECTED", "INVALID");
    assert.deepEqual(await volunteer(org, existing.id), existing);
  });
});

describe("onPortalRequestWritten: CLAIM", () => {
  test("claimLinksSingleFreeMatch", async () => {
    const org = await newOrg();
    const uid = newUid("claim");
    const email = `${uid.toLowerCase()}@auth.example`;
    const existing = await addVolunteer(org, { email: `  ${email.toUpperCase()} `, lastModified: 1000 });
    // Same address but without portal access: not a candidate.
    const disabled = await addVolunteer(org, { email, portalEnabled: false });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "DONE", "");

    const v = await volunteer(org, existing.id);
    assert.equal(v.portalUid, uid);
    assert.ok(v.lastModified > 1000);
    assert.deepEqual({ ...v, portalUid: "", lastModified: 1000 }, existing);
    assert.deepEqual(await volunteer(org, disabled.id), disabled);
  });

  test("claimTwoMatchesRejectedLinkedElsewhere", async () => {
    const org = await newOrg();
    const uid = newUid("claimTwo");
    const email = `${uid.toLowerCase()}@auth.example`;
    const a = await addVolunteer(org, { email });
    const b = await addVolunteer(org, { email });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "REJECTED", "LINKED_ELSEWHERE");
    assert.deepEqual(await volunteer(org, a.id), a);
    assert.deepEqual(await volunteer(org, b.id), b);
  });

  test("claimOfEntryLinkedToAnotherAccountRejected", async () => {
    const org = await newOrg();
    const uid = newUid("claimTaken");
    const email = `${uid.toLowerCase()}@auth.example`;
    const taken = await addVolunteer(org, { email, portalUid: "someoneElse01" });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "REJECTED", "LINKED_ELSEWHERE");
    assert.deepEqual(await volunteer(org, taken.id), taken);
  });

  test("claimWithoutMatchRejectedNoAccess", async () => {
    const org = await newOrg();
    const uid = newUid("claimNone");
    await addVolunteer(org, { email: "someone@else.example" });
    const ref = await sendRequest(org, uid, "CLAIM");
    assertResult(await waitResult(ref), "REJECTED", "NO_ACCESS");
  });

  test("claimWhenAlreadyLinkedIsDone", async () => {
    const org = await newOrg();
    const uid = newUid("claimOwner");
    const owner = await addVolunteer(org, { portalUid: uid, email: "owner@mail.example" });
    const ref = await sendRequest(org, uid, "CLAIM");
    assertResult(await waitResult(ref), "DONE", "");
    assert.deepEqual(await volunteer(org, owner.id), owner);
  });
});

describe("onPortalRequestWritten: QR_EMAIL", () => {
  test("qrMailSentToProfileAddress", async () => {
    const org = await newOrg({ association: "Les Amis du Bar" });
    await publishTemplate(org, { logoBase64: LOGO_BASE64 });
    const uid = newUid("qrMail");
    const profileEmail = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email: ` ${profileEmail} ` });
    const ref = await sendRequest(org, uid, "QR_EMAIL", { email: `${uid.toLowerCase()}@other.example` });
    const result = await waitResult(ref);
    assertResult(result, "DONE", "");

    const mail = await waitMailTo(profileEmail);
    assert.equal(mail.subject, "Ton QR code bénévole");
    assert.deepEqual(mail.from, { name: "Les Amis du Bar", address: "test@example.org" });
    assert.ok(mail.html.includes("Salut Léa,"));
    assert.ok(!mail.html.includes(TOKEN));
    assert.equal(mail.text, "Voici ton QR code.");
    const qr = mail.attachments.find((a) => a.cid === "qrcode");
    assert.ok(qr, "inline QR attachment");
    assert.equal(qr.contentType, "image/png");
    assert.ok(qr.size > 100);
    const logo = mail.attachments.find((a) => a.cid === "logo");
    assert.ok(logo, "logo attached when the template has one");
    assert.equal(logo.contentType, "image/png");
    assert.equal(logo.size, Buffer.from(LOGO_BASE64, "base64").length);
    assert.equal((await capturedMailsTo(`${uid.toLowerCase()}@other.example`)).length, 0, "never to the request address");

    // The wallet id only lives inside the PNG.
    assert.ok(!JSON.stringify(mail).includes(v.id), "volunteer id absent from the captured mail");
    assert.ok(!JSON.stringify(result).includes(v.id), "volunteer id absent from the request");
    const status = (await db.doc(`orgs/${org}/portalServer/status`).get()).data();
    assert.ok(!JSON.stringify(status ?? {}).includes(v.id));
  });

  test("qrRefusedForAdmin", async () => {
    const org = await newOrg();
    await publishTemplate(org);
    const uid = newUid("qrAdmin");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email, isAdmin: true });
    const ref = await sendRequest(org, uid, "QR_EMAIL");
    assertResult(await waitResult(ref), "REJECTED", "NOT_ALLOWED");
    await sleep(500);
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("qrRefusedWithoutEmail", async () => {
    const org = await newOrg();
    await publishTemplate(org);
    const uid = newUid("qrNoMail");
    await addVolunteer(org, { portalUid: uid, email: "  " });
    const ref = await sendRequest(org, uid, "QR_EMAIL");
    assertResult(await waitResult(ref), "REJECTED", "NO_EMAIL");
  });

  test("qrRefusedForUnusableAddress", async () => {
    const org = await newOrg();
    await publishTemplate(org);
    const uid = newUid("qrBadMail");
    const email = `${uid.toLowerCase()}@profile.example, other@evil.example`;
    await addVolunteer(org, { portalUid: uid, email });
    const ref = await sendRequest(org, uid, "QR_EMAIL");
    assertResult(await waitResult(ref), "REJECTED", "NO_EMAIL");
    assert.equal((await capturedMailsTo("other@evil.example")).length, 0);
  });

  test("qrRefusedWithoutAccess", async () => {
    const org = await newOrg();
    const uid = newUid("qrNoAccess");
    const ref = await sendRequest(org, uid, "QR_EMAIL");
    assertResult(await waitResult(ref), "REJECTED", "NO_ACCESS");
  });

  test("firstNameEscapedInHtml", async () => {
    const org = await newOrg();
    await publishTemplate(org, { text: `Salut ${TOKEN}, voici ton QR code.` });
    const uid = newUid("qrEscape");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email, name: "Léa & <Co> $&" });
    const ref = await sendRequest(org, uid, "QR_EMAIL");
    assertResult(await waitResult(ref), "DONE", "");
    const mail = await waitMailTo(email);
    assert.ok(mail.html.includes("Salut Léa &amp; &lt;Co&gt; $&amp;,"), mail.html);
    assert.ok(!mail.html.includes("<Co>"));
    assert.equal(mail.text, "Salut Léa & <Co> $&, voici ton QR code.");
    assert.equal(mail.attachments.some((a) => a.cid === "logo"), false, "no logo without logoBase64");
  });

  test("missingTemplateUsesFallback", async () => {
    const org = await newOrg({ association: null });
    const uid = newUid("qrFallback");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email, name: "Max" });
    const ref = await sendRequest(org, uid, "QR_EMAIL");
    assertResult(await waitResult(ref), "DONE", "");
    const mail = await waitMailTo(email);
    assert.equal(mail.subject, "Your volunteer QR code");
    assert.ok(mail.html.includes("cid:qrcode"));
    assert.ok(mail.html.includes("Max"));
    assert.ok(mail.text.includes("Max"));
    assert.ok(!mail.html.includes(TOKEN) && !mail.text.includes(TOKEN));
    assert.equal(mail.from.address, "test@example.org");
    assert.ok(mail.attachments.some((a) => a.cid === "qrcode"));
    assert.equal(mail.attachments.some((a) => a.cid === "logo"), false);
  });
});

describe("onPortalRequestWritten: protocol", () => {
  test("serverStatusTouched", async () => {
    const org = await newOrg();
    const uid = newUid("status");
    await addVolunteer(org, { portalUid: uid });
    const start = Date.now();
    const ref = await sendRequest(org, uid, "CLAIM");
    await waitResult(ref);
    const status = await waitFor("server status", async () => (await db.doc(`orgs/${org}/portalServer/status`).get()).data());
    assert.equal(typeof status.version, "string");
    assert.ok(status.lastRunAt >= start);
  });

  test("portalDisabledDoesNothing", async () => {
    const org = await newOrg({ portal: "false" });
    const uid = newUid("disabled");
    const existing = await addVolunteer(org, { portalUid: uid });
    const ref = await sendRequest(org, uid, "PROFILE", { firstName: "Max", lastName: "Roe", phone: "0791112233" });
    await barrier();
    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PENDING");
    assert.equal("processingDevice" in doc, false);
    assert.equal("reason" in doc, false);
    assert.deepEqual(await volunteer(org, existing.id), existing);
    assert.equal((await db.doc(`orgs/${org}/portalServer/status`).get()).exists, false);
  });

  test("processingNotStaleIsLeftAlone", async () => {
    const org = await newOrg();
    const uid = newUid("busy");
    const existing = await addVolunteer(org, { portalUid: uid });
    const processingAt = Date.now() - 60 * 1000;
    const ref = await sendRequest(org, uid, "PROFILE", {
      firstName: "Max", lastName: "Roe", phone: "0791112233",
      status: "PROCESSING", processingAt, processingDevice: "device-1",
    });
    await barrier();
    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PROCESSING");
    assert.equal(doc.processingDevice, "device-1");
    assert.equal(doc.processingAt, processingAt);
    assert.deepEqual(await volunteer(org, existing.id), existing);
  });

  test("staleProcessingIsTakenOver", async () => {
    const org = await newOrg();
    const uid = newUid("stale");
    const existing = await addVolunteer(org, { portalUid: uid });
    const ref = await sendRequest(org, uid, "PROFILE", {
      firstName: "Max", lastName: "Roe", phone: "0791112233",
      status: "PROCESSING", processingAt: Date.now() - 6 * 60 * 1000, processingDevice: "gone-device",
    });
    assertResult(await waitResult(ref), "DONE", "");
    assert.equal((await volunteer(org, existing.id)).name, "Max");
  });
});
