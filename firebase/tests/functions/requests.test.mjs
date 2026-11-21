// Integration tests for the portal request Cloud Function (PROFILE, CLAIM, QR_EMAIL). Run with
// `npm run test:functions`: the emulators run Firestore and the functions, mail is captured to
// `_testMail` (PORTAL_MAIL_TRANSPORT=capture in .env.local), and this file polls for the results.
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

const PROJECT = "demo-noctulist";
const BUCKET = `${PROJECT}.appspot.com`;
const TOKEN = "%%NOCTULIST_FIRST_NAME%%";
// 1x1 PNG.
const LOGO_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run through `npm run test:functions`: the emulators must be running.");
}

let app;
let db;
let bucket;
let counter = 0;
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);

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

async function setting(org, key, value) {
  await db.doc(`orgs/${org}/institutionSettings/${key}`).set({ value, lastModified: Date.now() });
}

function newNanoId() {
  counter += 1;
  return `gst${String(counter).padStart(4, "0")}${Date.now().toString(36)}`.padEnd(21, "x").slice(0, 21);
}

/** A guest row in the format of GitLiveFirestoreGateway.guestToMap (permanent, with portal access). */
async function addGuest(org, overrides = {}) {
  const nanoId = overrides.nanoId ?? newNanoId();
  const data = {
    nanoId, name: "Noé", lastNameAbbreviation: "BLANC", email: "noe@mail.example", phone: "079 999 00 00",
    invitations: 2, venueName: "Main", notes: "VIP", isVolunteerBenefit: false, volunteerId: null,
    lastModified: 1000, isTemporaryGuest: false, temporaryArtistName: "", temporaryEventDate: null,
    temporaryContactPhone: "", temporaryVenueName: "", temporaryContactEmail: "", temporaryPortalEmail: "",
    temporaryAccessIds: "", temporaryEntryValidatedAt: 0, temporaryEntryValidatedBy: "", nfcCardUid: "04CC",
    nfcCardUidHash: "h", isAdmin: false, barDiscountPercent: 10, portalEnabled: true, portalUid: "",
    portalGoogleEmail: "", portalGoogleUid: "", portalGoogleLinkedBy: "",
    ...overrides,
  };
  await db.doc(`orgs/${org}/guests/${nanoId}`).set(data);
  return data;
}

async function guest(org, nanoId) {
  return (await db.doc(`orgs/${org}/guests/${nanoId}`).get()).data();
}

/** A guest page as a device publishes it (GuestPortalProjectionBuilder key order). */
async function publishGuestPage(org, uid) {
  const payload = JSON.stringify({
    firstName: "Noé", lastName: "BLANC", email: "noe@mail.example", phone: "079 999 00 00",
    hasPhoto: false, photoVersion: "", venueName: "Main", invitations: 2, barDiscountPercent: 10,
    wallet: { balance: 4.5, currency: "CHF", transfers: [] }, qrEmailAllowed: true, mustSetPassword: false,
    association: "Asso Test", googleEmail: "", googleLinkedBy: "", googleLinkAvailable: false,
  });
  await db.doc(`orgs/${org}/guestPortal/${uid}`).set({ schema: 1, payload, contentHash: sha256(payload), updatedAt: 1 });
  return JSON.parse(payload);
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
  app = initializeApp({ projectId: PROJECT, storageBucket: BUCKET }, "request-tests");
  db = getFirestore(app);
  bucket = getStorage(app).bucket();
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
      shifts: [{ date: 1, venue: "Main" }], olderShifts: 0, lastShiftFloor: 0,
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

  test("profilePhotoReplacesPhotoWithNewVersion", async () => {
    const org = await newOrg();
    await db.doc(`orgs/${org}/institutionSettings/profile_photos_enabled`).set({ value: "true", lastModified: 1 });
    const uid = newUid("profilePhoto");
    const existing = await addVolunteer(org, { portalUid: uid, profilePhotoUrl: "https://old.example/photo.jpg" });
    const payload = JSON.stringify({
      firstName: "Léa", lastName: "MARTIN", phone: "079 000 00 00", hasPhoto: true, photoVersion: "abcdef0123456789",
      shifts: [],
    });
    await db.doc(`orgs/${org}/volunteerPortal/${uid}`).set({ schema: 2, payload, contentHash: sha256(payload), updatedAt: 1 });
    const upload = `orgs/${org}/portalPhotoUploads/${uid}.jpg`;
    await bucket.file(upload).save(JPEG, { contentType: "image/jpeg", resumable: false });

    const ref = await sendRequest(org, uid, "PROFILE", { firstName: "Léa", lastName: "Martin", phone: "079 000 00 00", photo: true });
    assertResult(await waitResult(ref), "DONE", "");

    const v = await volunteer(org, existing.id);
    const path = `orgs/${org}/profilePhotos/volunteers/${existing.id}.jpg`;
    assert.equal(v.profilePhotoPath, path);
    assert.ok(v.profilePhotoUrl.includes(encodeURIComponent(path)) && /[?&]token=/.test(v.profilePhotoUrl));
    assert.deepEqual((await bucket.file(path).download())[0], JPEG);
    assert.deepEqual((await bucket.file(`orgs/${org}/portalPhotos/${uid}.jpg`).download())[0], JPEG);
    assert.equal((await bucket.file(upload).exists())[0], false, "upload removed");

    const patched = JSON.parse((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data().payload);
    assert.equal(patched.hasPhoto, true);
    assert.equal(patched.photoVersion, sha256(v.profilePhotoUrl).slice(0, 16));
    assert.deepEqual(Object.keys(patched), Object.keys(JSON.parse(payload)), "key order kept");
  });

  test("profilePhotoRefusedWhenPhotosOffOrMissing", async () => {
    for (const photos of ["false", "true"]) {
      const org = await newOrg();
      await db.doc(`orgs/${org}/institutionSettings/profile_photos_enabled`).set({ value: photos, lastModified: 1 });
      const uid = newUid("profileNoPhoto");
      const existing = await addVolunteer(org, { portalUid: uid });
      const upload = `orgs/${org}/portalPhotoUploads/${uid}.jpg`;
      // Photos off: a file is there but must be ignored. Photos on: nothing was uploaded.
      if (photos === "false") await bucket.file(upload).save(JPEG, { contentType: "image/jpeg", resumable: false });
      const ref = await sendRequest(org, uid, "PROFILE", { firstName: "Max", lastName: "Roe", phone: "0791112233", photo: true });
      assertResult(await waitResult(ref), "REJECTED", "INVALID");
      assert.deepEqual(await volunteer(org, existing.id), existing, photos);
      assert.equal((await bucket.file(upload).exists())[0], false, "upload removed");
    }
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

  test("staleProcessingIsLeftToDevices", async () => {
    const org = await newOrg();
    const uid = newUid("stale");
    const existing = await addVolunteer(org, { portalUid: uid });
    const processingAt = Date.now() - 6 * 60 * 1000;
    const ref = await sendRequest(org, uid, "PROFILE", {
      firstName: "Max", lastName: "Roe", phone: "0791112233",
      status: "PROCESSING", processingAt, processingDevice: "gone-device",
    });
    await barrier();
    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PROCESSING");
    assert.equal(doc.processingDevice, "gone-device");
    assert.equal(doc.processingAt, processingAt);
    assert.equal("reason" in doc, false);
    assert.deepEqual(await volunteer(org, existing.id), existing);
  });
});

describe("onPortalRequestWritten: guests", () => {
  test("GUEST_PROFILE updates the guest and its page", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    const uid = newUid("gProfile");
    const future = Date.now() + 10 * 60 * 1000;
    const g = await addGuest(org, { portalUid: uid, lastModified: future });
    // The account's temporary group is not the permanent guest.
    const temp = await addGuest(org, { portalUid: uid, isTemporaryGuest: true, lastModified: future + 50 });
    const before = await publishGuestPage(org, uid);

    const ref = await sendRequest(org, uid, "GUEST_PROFILE", {
      firstName: "  Noé   Paul ", lastName: "blanc-roux", phone: " +41 79 555 44 33 ",
    });
    assertResult(await waitResult(ref), "DONE", "");

    const after = await guest(org, g.nanoId);
    assert.equal(after.name, "Noé Paul");
    assert.equal(after.lastNameAbbreviation, "BLANC-ROUX");
    assert.equal(after.phone, "+41 79 555 44 33");
    assert.equal(after.lastModified, future + 1);
    assert.deepEqual({ ...after, name: g.name, lastNameAbbreviation: g.lastNameAbbreviation, phone: g.phone, lastModified: g.lastModified }, g);
    assert.deepEqual(await guest(org, temp.nanoId), temp);

    const page = (await db.doc(`orgs/${org}/guestPortal/${uid}`).get()).data();
    assert.equal(page.contentHash, sha256(page.payload));
    assert.equal(page.schema, 1);
    const payload = JSON.parse(page.payload);
    assert.deepEqual(Object.keys(payload), Object.keys(before));
    assert.deepEqual(payload, { ...before, firstName: "Noé Paul", lastName: "BLANC-ROUX", phone: "+41 79 555 44 33" });
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, false);
  });

  test("GUEST_PROFILE photo goes to the guest and the account photo", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    await setting(org, "profile_photos_enabled", "true");
    const uid = newUid("gPhoto");
    const g = await addGuest(org, { portalUid: uid });
    await publishGuestPage(org, uid);
    await bucket.file(`orgs/${org}/portalPhotoUploads/${uid}.jpg`).save(JPEG, { contentType: "image/jpeg", resumable: false });

    const ref = await sendRequest(org, uid, "GUEST_PROFILE", { firstName: "Noé", lastName: "Blanc", phone: "079 999 00 00", photo: true });
    assertResult(await waitResult(ref), "DONE", "");
    const after = await guest(org, g.nanoId);
    const path = `orgs/${org}/profilePhotos/guests/${g.nanoId}.jpg`;
    assert.equal(after.profilePhotoPath, path);
    assert.ok(after.profilePhotoUrl.includes(encodeURIComponent(path)) && /[?&]token=/.test(after.profilePhotoUrl));
    assert.deepEqual((await bucket.file(path).download())[0], JPEG);
    assert.deepEqual((await bucket.file(`orgs/${org}/portalPhotos/${uid}.jpg`).download())[0], JPEG);
    const [uploadLeft] = await bucket.file(`orgs/${org}/portalPhotoUploads/${uid}.jpg`).exists();
    assert.equal(uploadLeft, false);
    const payload = JSON.parse((await db.doc(`orgs/${org}/guestPortal/${uid}`).get()).data().payload);
    assert.equal(payload.hasPhoto, true);
    assert.equal(payload.photoVersion, sha256(after.profilePhotoUrl).slice(0, 16));
  });

  test("GUEST_PROFILE without a permanent guest rejected NO_ACCESS", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    const uid = newUid("gNoAccess");
    await addVolunteer(org, { portalUid: uid });
    const benefit = await addGuest(org, { portalUid: uid, isVolunteerBenefit: true });
    const ref = await sendRequest(org, uid, "GUEST_PROFILE", { firstName: "X", lastName: "Y", phone: "079 111 22 33" });
    assertResult(await waitResult(ref), "REJECTED", "NO_ACCESS");
    assert.deepEqual(await guest(org, benefit.nanoId), benefit);
  });

  test("guest requests left PENDING when guest space off", async () => {
    const org = await newOrg();
    await publishTemplate(org);
    const uid = newUid("gOff");
    const g = await addGuest(org, { portalUid: uid });
    const profile = await sendRequest(org, uid, "GUEST_PROFILE", { firstName: "X", lastName: "Y", phone: "079 111 22 33" });
    const qr = await sendRequest(org, uid, "GUEST_QR_EMAIL");
    await barrier();
    for (const ref of [profile, qr]) {
      const doc = (await ref.get()).data();
      assert.equal(doc.status, "PENDING");
      assert.equal("processingDevice" in doc, false);
    }
    assert.deepEqual(await guest(org, g.nanoId), g);
  });

  test("GUEST_QR_EMAIL sends the QR with the guest name to the guest address", async () => {
    const org = await newOrg({ association: "Les Amis du Bar" });
    await setting(org, "guest_portal_enabled", "true");
    await publishTemplate(org);
    const uid = newUid("gQr");
    const address = `${uid.toLowerCase()}@guest.example`;
    const g = await addGuest(org, { portalUid: uid, email: ` ${address} `, name: "Noé" });
    const ref = await sendRequest(org, uid, "GUEST_QR_EMAIL");
    const result = await waitResult(ref);
    assertResult(result, "DONE", "");
    const mail = await waitMailTo(address);
    assert.equal(mail.subject, "Ton QR code bénévole");
    assert.deepEqual(mail.from, { name: "Les Amis du Bar", address: "test@example.org" });
    assert.ok(mail.html.includes("Salut Noé,"));
    assert.ok(mail.attachments.some((a) => a.cid === "qrcode"));
    assert.ok(!JSON.stringify(mail).includes(g.nanoId), "the nanoId only lives inside the PNG");
    assert.ok(!JSON.stringify(result).includes(g.nanoId));
  });

  test("GUEST_QR_EMAIL refused for admin guest", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    await publishTemplate(org);
    const uid = newUid("gQrAdmin");
    const address = `${uid.toLowerCase()}@guest.example`;
    await addGuest(org, { portalUid: uid, email: address, isAdmin: true });
    const ref = await sendRequest(org, uid, "GUEST_QR_EMAIL");
    assertResult(await waitResult(ref), "REJECTED", "NOT_ALLOWED");
    await sleep(500);
    assert.equal((await capturedMailsTo(address)).length, 0);
  });

  test("GUEST_QR_EMAIL refused without e-mail or for a temporary guest", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    await publishTemplate(org);
    const uid = newUid("gQrNoMail");
    await addGuest(org, { portalUid: uid, email: "  " });
    assertResult(await waitResult(await sendRequest(org, uid, "GUEST_QR_EMAIL")), "REJECTED", "NO_EMAIL");
    const tempUid = newUid("gQrTemp");
    await addGuest(org, { portalUid: tempUid, isTemporaryGuest: true, email: "temp@guest.example" });
    assertResult(await waitResult(await sendRequest(org, tempUid, "GUEST_QR_EMAIL")), "REJECTED", "NO_ACCESS");
  });

  test("GUEST_QR_EMAIL left PENDING without a published template", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    const uid = newUid("gQrNoTpl");
    const address = `${uid.toLowerCase()}@guest.example`;
    await addGuest(org, { portalUid: uid, email: address });
    const ref = await sendRequest(org, uid, "GUEST_QR_EMAIL");
    await barrier();
    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PENDING");
    assert.equal("processingDevice" in doc, false);
    assert.equal((await capturedMailsTo(address)).length, 0);
  });

  test("CLAIM links volunteer, guest and temporary group", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    await setting(org, "temp_guest_portal_enabled", "true");
    const uid = newUid("claimAll");
    const email = `${uid.toLowerCase()}@auth.example`;
    const v = await addVolunteer(org, { email: email.toUpperCase() });
    const g = await addGuest(org, { email: ` ${email} ` });
    // Temporary group: the address the access was opened for wins over the contact address.
    const t1 = await addGuest(org, { isTemporaryGuest: true, temporaryContactEmail: email, temporaryPortalEmail: "" });
    const t2 = await addGuest(org, { isTemporaryGuest: true, temporaryContactEmail: "old@contact.example", temporaryPortalEmail: email });
    const t3 = await addGuest(org, { isTemporaryGuest: true, temporaryContactEmail: email, temporaryPortalEmail: "other@contact.example" });
    // Never claimable: volunteer benefits, closed access, the guest's own e-mail field of a temporary guest.
    const benefit = await addGuest(org, { email, isVolunteerBenefit: true });
    const closed = await addGuest(org, { email, portalEnabled: false });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "DONE", "");

    assert.equal((await volunteer(org, v.id)).portalUid, uid);
    for (const linked of [g, t1, t2]) {
      const after = await guest(org, linked.nanoId);
      assert.equal(after.portalUid, uid, linked.nanoId);
      assert.ok(after.lastModified > 1000);
      assert.deepEqual({ ...after, portalUid: "", lastModified: 1000 }, linked);
    }
    for (const untouched of [t3, benefit, closed]) assert.deepEqual(await guest(org, untouched.nanoId), untouched);
  });

  test("CLAIM leaves guests alone while their space is off", async () => {
    const org = await newOrg();
    const uid = newUid("claimOff");
    const email = `${uid.toLowerCase()}@auth.example`;
    const g = await addGuest(org, { email });
    const t = await addGuest(org, { isTemporaryGuest: true, temporaryContactEmail: email });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "REJECTED", "NO_ACCESS");
    assert.deepEqual(await guest(org, g.nanoId), g);
    assert.deepEqual(await guest(org, t.nanoId), t);
  });

  test("CLAIM refused when a guest of that address is linked elsewhere", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    const uid = newUid("claimForeign");
    const email = `${uid.toLowerCase()}@auth.example`;
    const v = await addVolunteer(org, { email });
    const foreign = await addGuest(org, { email, portalUid: "someoneElse01" });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "REJECTED", "LINKED_ELSEWHERE");
    assert.deepEqual(await volunteer(org, v.id), v);
    assert.deepEqual(await guest(org, foreign.nanoId), foreign);
  });

  test("CLAIM of an account with access skips the guest linked elsewhere", async () => {
    const org = await newOrg();
    await setting(org, "guest_portal_enabled", "true");
    const uid = newUid("claimOwnerG");
    const email = `${uid.toLowerCase()}@auth.example`;
    // Access through a guest only.
    await addGuest(org, { email: "first@mail.example", portalUid: uid });
    const foreign = await addGuest(org, { email, portalUid: "someoneElse02" });
    const free = await addGuest(org, { email });
    const ref = await sendRequest(org, uid, "CLAIM", { email });
    assertResult(await waitResult(ref), "DONE", "");
    assert.deepEqual(await guest(org, foreign.nanoId), foreign);
    assert.equal((await guest(org, free.nanoId)).portalUid, uid);
  });
});
