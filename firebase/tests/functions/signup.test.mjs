// Integration tests for the signup Cloud Function. Run with `npm run test:functions`: the
// emulators run Firestore, Storage and the functions, and this file talks to them with the
// Admin SDK, then polls for what the function did.
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

const PROJECT = "demo-noctulist";
const BUCKET = `${PROJECT}.appspot.com`;
const DAY = 24 * 60 * 60 * 1000;
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
const VOLUNTEER_KEYS = [
  "id", "name", "lastNameAbbreviation", "email", "phone", "dateOfBirth", "gender", "currentRank",
  "isActive", "lastShiftDate", "lastModified", "nfcCardUid", "nfcCardUidHash", "isAdmin",
  "portalEnabled", "portalUid",
];
// GitLiveFirestoreGateway.guestToMap; blank photo fields are omitted.
const GUEST_KEYS = [
  "nanoId", "name", "lastNameAbbreviation", "email", "phone", "invitations", "venueName", "notes",
  "isVolunteerBenefit", "volunteerId", "lastModified", "isTemporaryGuest", "temporaryArtistName",
  "temporaryEventDate", "temporaryContactPhone", "temporaryVenueName", "temporaryContactEmail",
  "temporaryPortalEmail", "temporaryAccessIds", "temporaryEntryValidatedAt", "temporaryEntryValidatedBy",
  "nfcCardUid", "nfcCardUidHash", "isAdmin", "barDiscountPercent", "portalEnabled", "portalUid",
  "portalGoogleEmail", "portalGoogleUid", "portalGoogleLinkedBy",
];
// GuestPortalProjectionBuilder.build, in order.
const GUEST_PAYLOAD_KEYS = [
  "firstName", "lastName", "email", "phone", "hasPhoto", "photoVersion", "venueName", "invitations",
  "barDiscountPercent", "wallet", "qrEmailAllowed", "mustSetPassword", "association", "googleEmail",
  "googleLinkedBy", "googleLinkAvailable",
];
const PAYLOAD_KEYS = [
  "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "hasPhoto", "photoVersion",
  "shifts", "olderShifts", "lastShiftFloor", "timeline", "wallet", "qrEmailAllowed", "mustSetPassword",
  "association",
];

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST) {
  throw new Error("Run through `npm run test:functions`: the emulators must be running.");
}

let app;
let db;
let bucket;
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
async function newOrg({ portal = "true", photos = "true", association = "Asso Test" } = {}) {
  counter += 1;
  const org = `org-fn-${counter}-${Date.now()}`;
  const now = Date.now();
  await db.doc(`orgs/${org}/metadata/config`).set({ createdAt: now });
  await db.doc(`orgs/${org}/institutionSettings/volunteer_portal_enabled`).set({ value: portal, lastModified: now });
  await db.doc(`orgs/${org}/institutionSettings/profile_photos_enabled`).set({ value: photos, lastModified: now });
  if (association !== null) {
    await db.doc(`orgs/${org}/portalBranding/default`).set({ associationName: association, logoDataUri: "", lastModified: now });
  }
  return org;
}

function newFormId() {
  counter += 1;
  return `signupForm${String(counter).padStart(4, "0")}${Date.now().toString(36)}`;
}

function submission(uid, overrides = {}) {
  return {
    status: "SUBMITTED",
    submittedUid: uid,
    email: `${uid.toLowerCase()}@mail.example`,
    firstName: "Léa",
    lastName: "Martin",
    phone: "+41 79 123 45 67",
    dateOfBirth: "1998-04-12",
    gender: "FEMALE",
    photoPath: "",
    consent: true,
    lang: "fr",
    submittedAt: FieldValue.serverTimestamp(),
    lastModified: Date.now(),
    ...overrides,
  };
}

/** Opens a registration link, then answers it the way the public page does. */
async function submit(org, formId, data, form = {}) {
  const ref = db.doc(`orgs/${org}/volunteerSignupForms/${formId}`);
  const now = Date.now();
  await ref.set({
    formId, status: "OPEN", expiresAtMillis: now + 7 * DAY, photosEnabled: true,
    associationName: "Asso Test", createdAt: now, lastModified: now, ...form,
  });
  await ref.set(data, { merge: true });
  return ref;
}

async function uploadSignupPhoto(org, formId) {
  const path = `orgs/${org}/portalSignupPhotos/${formId}.jpg`;
  await bucket.file(path).save(JPEG, { contentType: "image/jpeg", resumable: false });
  return path;
}

async function fileExists(path) {
  const [exists] = await bucket.file(path).exists();
  return exists;
}

async function volunteers(org) {
  const snap = await db.collection(`orgs/${org}/volunteers`).get();
  return snap.docs.map((d) => ({ docId: d.id, ...d.data() }));
}

async function guests(org) {
  const snap = await db.collection(`orgs/${org}/guests`).get();
  return snap.docs.map((d) => ({ docId: d.id, ...d.data() }));
}

async function guestSpace(org, value = "true") {
  await db.doc(`orgs/${org}/institutionSettings/guest_portal_enabled`).set({ value, lastModified: Date.now() });
}

/** A GUEST registration answer: no date of birth nor gender. */
function guestSubmission(uid, overrides = {}) {
  const { dateOfBirth, gender, ...rest } = submission(uid, overrides);
  return rest;
}

const GUEST_FORM = { holderType: "GUEST", venueName: " Club Nord " };

/** A guest row in the format of GitLiveFirestoreGateway.guestToMap. */
function guestDoc(nanoId, overrides = {}) {
  return {
    nanoId, name: "Léa", lastNameAbbreviation: "M", email: "lea@mail.example", phone: "079",
    invitations: 2, venueName: "Main", notes: "VIP", isVolunteerBenefit: false, volunteerId: null,
    lastModified: 1000, isTemporaryGuest: false, temporaryArtistName: "", temporaryEventDate: null,
    temporaryContactPhone: "", temporaryVenueName: "", temporaryContactEmail: "", temporaryPortalEmail: "",
    temporaryAccessIds: "", temporaryEntryValidatedAt: 0, temporaryEntryValidatedBy: "", nfcCardUid: "04AA",
    nfcCardUidHash: "", isAdmin: false, barDiscountPercent: 10, portalEnabled: false, portalUid: "",
    portalGoogleEmail: "", portalGoogleUid: "", portalGoogleLinkedBy: "",
    ...overrides,
  };
}

async function waitProcessed(ref) {
  await waitFor(`${ref.path} to be processed`, async () => !(await ref.get()).exists);
}

/** Proves the function is running: a valid signup in another org gets processed. */
async function barrier() {
  const org = await newOrg();
  const ref = await submit(org, newFormId(), submission("barrierUid01"));
  await waitProcessed(ref);
  await sleep(1000);
}

before(async () => {
  app = initializeApp({ projectId: PROJECT, storageBucket: BUCKET }, "signup-tests");
  db = getFirestore(app);
  bucket = getStorage(app).bucket();
  await fetch(
    `http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,
    { method: "DELETE" },
  );
  await bucket.deleteFiles({ prefix: "orgs/" }).catch(() => {});
});

after(async () => {
  if (app) await deleteApp(app);
});

describe("onSignupWritten", () => {
  test("newSignupCreatesVolunteerAndProjection", async () => {
    const org = await newOrg();
    const uid = "newUid000001";
    const before = Date.now();
    const ref = await submit(org, newFormId(), submission(uid, {
      email: "  Lea.Martin@Mail.Example ",
      lastName: "Martin  Dupont",
    }));
    await waitProcessed(ref);

    const all = await volunteers(org);
    assert.equal(all.length, 1);
    const v = all[0];
    assert.match(v.id, /^[A-Za-z0-9_-]{21}$/);
    assert.equal(v.docId, v.id);
    assert.deepEqual(Object.keys(v).filter((k) => k !== "docId").sort(), [...VOLUNTEER_KEYS].sort());
    assert.equal(v.name, "Léa");
    assert.equal(v.lastNameAbbreviation, "MARTIN DUPONT");
    assert.equal(v.email, "lea.martin@mail.example");
    assert.equal(v.phone, "+41 79 123 45 67");
    assert.equal(v.dateOfBirth, "1998-04-12");
    assert.equal(v.gender, "FEMALE");
    assert.equal(v.currentRank, null);
    assert.equal(v.isActive, true);
    assert.equal(v.lastShiftDate, null);
    assert.equal(typeof v.lastModified, "number");
    assert.ok(v.lastModified >= before);
    assert.equal(v.nfcCardUid, "");
    assert.equal(v.nfcCardUidHash, "");
    assert.equal(v.isAdmin, false);
    assert.equal(v.portalEnabled, true);
    assert.equal(v.portalUid, uid);

    const projection = (await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data();
    assert.ok(projection, "projection written");
    assert.deepEqual(Object.keys(projection).sort(), ["contentHash", "payload", "schema", "updatedAt"]);
    assert.equal(projection.schema, 2);
    assert.equal(projection.contentHash, sha256(projection.payload));
    assert.equal(typeof projection.updatedAt, "number");
    assert.ok(!projection.payload.includes(v.id), "payload never carries the volunteer id");
    const payload = JSON.parse(projection.payload);
    assert.deepEqual(Object.keys(payload), PAYLOAD_KEYS);
    assert.deepEqual(payload, {
      firstName: "Léa",
      lastName: "MARTIN DUPONT",
      email: "lea.martin@mail.example",
      phone: "+41 79 123 45 67",
      dateOfBirth: "1998-04-12",
      gender: "FEMALE",
      hasPhoto: false,
      photoVersion: "",
      shifts: [],
      olderShifts: 0,
      lastShiftFloor: 0,
      timeline: [{ from: 0, active: false, summary: null, benefits: [], uniqueEntriesRemaining: null }],
      wallet: { balance: 0, currency: "CHF", transfers: [] },
      qrEmailAllowed: true,
      mustSetPassword: false,
      association: "Asso Test",
    });

    // Written once the run is over, just after the answer is deleted.
    const status = await waitFor("server status", async () => (await db.doc(`orgs/${org}/portalServer/status`).get()).data());
    assert.equal(typeof status.version, "string");
    assert.ok(status.lastRunAt >= before);
  });

  test("signupWritesQrDoc", async () => {
    const org = await newOrg();
    const uid = "qrUid0000001";
    const ref = await submit(org, newFormId(), submission(uid, { lastName: "Martin" }));
    await waitProcessed(ref);
    const [v] = await volunteers(org);
    const qr = (await db.doc(`orgs/${org}/volunteerPortalQr/${uid}`).get()).data();
    assert.ok(qr, "QR document written");
    assert.equal(qr.schema, 1);
    assert.equal(qr.payload, v.id);
    assert.equal(qr.name, "Léa MARTIN");
  });

  test("signupSkipsQrDocWhenSettingOff", async () => {
    const org = await newOrg();
    await db.doc(`orgs/${org}/institutionSettings/volunteer_portal_qr_enabled`).set({ value: "false", lastModified: Date.now() });
    const uid = "qrOffUid0001";
    const ref = await submit(org, newFormId(), submission(uid));
    await waitProcessed(ref);
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, true);
    assert.equal((await db.doc(`orgs/${org}/volunteerPortalQr/${uid}`).get()).exists, false);
  });

  test("projectionUsesInstitutionCurrencyAndAssociationFallback", async () => {
    const org = await newOrg({ association: null });
    await db.doc(`orgs/${org}/institutionSettings/currency_code`).set({ value: " eur ", lastModified: Date.now() });
    await db.doc(`orgs/${org}/institutionSettings/email_association_name`).set({ value: "Collectif Test", lastModified: Date.now() });
    const uid = "currencyUid1";
    const ref = await submit(org, newFormId(), submission(uid));
    await waitProcessed(ref);
    const payload = JSON.parse((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data().payload);
    assert.deepEqual(payload.wallet, { balance: 0, currency: "EUR", transfers: [] });
    assert.equal(payload.association, "Collectif Test");
  });

  test("sameEmailLinksExistingVolunteer", async () => {
    const org = await newOrg();
    const uid = "linkUid00001";
    const existing = {
      id: "existingVolunteer0001", name: "Léa", lastNameAbbreviation: "M", email: " LEA@Mail.example ",
      phone: "079", dateOfBirth: "", gender: null, currentRank: "NOVA", isActive: true, lastShiftDate: 123,
      lastModified: 1000, nfcCardUid: "04AABB", nfcCardUidHash: "", isAdmin: false,
      portalEnabled: false, portalUid: "",
    };
    await db.doc(`orgs/${org}/volunteers/${existing.id}`).set(existing);
    const ref = await submit(org, newFormId(), submission(uid, { email: "lea@mail.example", firstName: "Other" }));
    await waitProcessed(ref);

    const all = await volunteers(org);
    assert.equal(all.length, 1);
    const { docId, ...linked } = all[0];
    assert.equal(docId, existing.id);
    assert.equal(linked.portalUid, uid);
    assert.equal(linked.portalEnabled, true);
    assert.ok(linked.lastModified > existing.lastModified);
    assert.deepEqual(
      { ...linked, portalUid: "", portalEnabled: false, lastModified: existing.lastModified },
      existing,
      "nothing else changes on the roster entry",
    );
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, false);
  });

  test("alreadyLinkedUidCreatesNothing", async () => {
    const org = await newOrg();
    const uid = "linkedUid001";
    const existing = {
      id: "linkedVolunteer000001", name: "Max", lastNameAbbreviation: "R", email: "max@mail.example",
      phone: "", dateOfBirth: "", gender: null, currentRank: null, isActive: true, lastShiftDate: null,
      lastModified: 1000, nfcCardUid: "", nfcCardUidHash: "", isAdmin: false, portalEnabled: true, portalUid: uid,
    };
    await db.doc(`orgs/${org}/volunteers/${existing.id}`).set(existing);
    const formId = newFormId();
    const photoPath = await uploadSignupPhoto(org, formId);
    const ref = await submit(org, formId, submission(uid, { photoPath }));
    await waitProcessed(ref);

    const all = await volunteers(org);
    assert.equal(all.length, 1);
    const { docId, ...unchanged } = all[0];
    assert.deepEqual(unchanged, existing);
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, false);
    assert.equal(await fileExists(photoPath), false, "signup photo removed");
  });

  test("photoCopiedAndOriginalDeleted", async () => {
    const org = await newOrg();
    const uid = "photoUid0001";
    const formId = newFormId();
    const photoPath = await uploadSignupPhoto(org, formId);
    const ref = await submit(org, formId, submission(uid, { photoPath }));
    await waitProcessed(ref);

    const [v] = await volunteers(org);
    const profilePath = `orgs/${org}/profilePhotos/volunteers/${v.id}.jpg`;
    assert.equal(v.profilePhotoPath, profilePath);
    assert.equal("profilePhotoUrl" in v, false, "blank url is omitted like on devices");
    assert.equal(await fileExists(photoPath), false, "original removed");
    for (const path of [profilePath, `orgs/${org}/portalPhotos/${uid}.jpg`]) {
      const file = bucket.file(path);
      const [bytes] = await file.download();
      assert.deepEqual(bytes, JPEG, path);
      const [meta] = await file.getMetadata();
      assert.equal(meta.contentType, "image/jpeg");
    }
    const payload = JSON.parse((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data().payload);
    assert.equal(payload.hasPhoto, true);
    assert.equal(payload.photoVersion, sha256(profilePath).slice(0, 16));
  });

  test("photoIgnoredWhenProfilePhotosAreOff", async () => {
    const org = await newOrg({ photos: "false" });
    const uid = "noPhotoUid01";
    const formId = newFormId();
    const photoPath = await uploadSignupPhoto(org, formId);
    const ref = await submit(org, formId, submission(uid, { photoPath }));
    await waitProcessed(ref);

    const [v] = await volunteers(org);
    assert.equal("profilePhotoPath" in v, false);
    assert.equal(await fileExists(photoPath), false);
    assert.equal(await fileExists(`orgs/${org}/profilePhotos/volunteers/${v.id}.jpg`), false);
    assert.equal(await fileExists(`orgs/${org}/portalPhotos/${uid}.jpg`), false);
    const payload = JSON.parse((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data().payload);
    assert.equal(payload.hasPhoto, false);
    assert.equal(payload.photoVersion, "");
  });

  test("linkedVolunteerWithoutPhotoGetsTheSignupPhoto", async () => {
    const org = await newOrg();
    const uid = "linkPhoto001";
    const id = "existingVolunteer0002";
    await db.doc(`orgs/${org}/volunteers/${id}`).set({
      id, name: "Léa", lastNameAbbreviation: "M", email: "lea@mail.example", phone: "", dateOfBirth: "",
      gender: null, currentRank: null, isActive: true, lastShiftDate: null, lastModified: 1000,
      nfcCardUid: "", nfcCardUidHash: "", isAdmin: false, portalEnabled: false, portalUid: "",
    });
    const formId = newFormId();
    const photoPath = await uploadSignupPhoto(org, formId);
    const ref = await submit(org, formId, submission(uid, { email: "lea@mail.example", photoPath }));
    await waitProcessed(ref);

    const [v] = await volunteers(org);
    const profilePath = `orgs/${org}/profilePhotos/volunteers/${id}.jpg`;
    assert.equal(v.portalUid, uid);
    assert.equal(v.profilePhotoPath, profilePath);
    assert.deepEqual((await bucket.file(profilePath).download())[0], JPEG);
    assert.equal(await fileExists(photoPath), false);
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, false);
  });

  test("invalidSubmissionIsDropped", async () => {
    const org = await newOrg();
    const formId = newFormId();
    const photoPath = await uploadSignupPhoto(org, formId);
    const ref = await submit(org, formId, submission("invalidUid01", { firstName: "<script>", photoPath }));
    await waitProcessed(ref);

    assert.equal((await volunteers(org)).length, 0);
    assert.equal((await db.collection(`orgs/${org}/volunteerPortal`).get()).size, 0);
    assert.equal(await fileExists(photoPath), false, "photo of a dropped answer removed");
  });

  test("staleProcessingIsLeftToDevices", async () => {
    const org = await newOrg();
    const processingAt = Date.now() - 6 * 60 * 1000;
    const ref = await submit(org, newFormId(), submission("staleUid0001", {
      status: "PROCESSING", processingAt, processingDevice: "gone-device",
    }));
    await barrier();

    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PROCESSING");
    assert.equal(doc.processingDevice, "gone-device");
    assert.equal(doc.processingAt, processingAt);
    assert.equal((await volunteers(org)).length, 0);
  });

  test("portalDisabledDoesNothing", async () => {
    const org = await newOrg({ portal: "false" });
    const ref = await submit(org, newFormId(), submission("disabledUid1"));
    await barrier();

    const doc = (await ref.get()).data();
    assert.equal(doc.status, "SUBMITTED");
    assert.equal("processingDevice" in doc, false);
    assert.equal((await volunteers(org)).length, 0);
    assert.equal((await db.doc(`orgs/${org}/portalServer/status`).get()).exists, false);
  });

  test("processingNotStaleIsLeftAlone", async () => {
    const org = await newOrg();
    const processingAt = Date.now() - 60 * 1000;
    const ref = await submit(org, newFormId(), submission("busyUid00001", {
      status: "PROCESSING", processingAt, processingDevice: "device-1",
    }));
    await barrier();

    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PROCESSING");
    assert.equal(doc.processingDevice, "device-1");
    assert.equal(doc.processingAt, processingAt);
    assert.equal((await volunteers(org)).length, 0);
  });
});

describe("onSignupWritten: guests", () => {
  test("guest signup creates a permanent guest", async () => {
    const org = await newOrg();
    await guestSpace(org);
    const uid = "guestNewUid1";
    const before = Date.now();
    const ref = await submit(org, newFormId(), guestSubmission(uid, {
      email: " Lea.Martin@Mail.Example ", lastName: "martin  dupont",
      // Not asked of a guest: ignored even when present.
      dateOfBirth: "1998-04-12", gender: "FEMALE",
    }), GUEST_FORM);
    await waitProcessed(ref);

    assert.equal((await volunteers(org)).length, 0, "no volunteer created");
    const all = await guests(org);
    assert.equal(all.length, 1);
    const { docId, ...g } = all[0];
    assert.match(g.nanoId, /^[A-Za-z0-9_-]{21}$/);
    assert.equal(docId, g.nanoId);
    assert.deepEqual(Object.keys(g).sort(), [...GUEST_KEYS].sort());
    assert.ok(g.lastModified >= before);
    assert.deepEqual({ ...g, nanoId: "", lastModified: 0 }, {
      nanoId: "", name: "Léa", lastNameAbbreviation: "MARTIN DUPONT", email: "lea.martin@mail.example",
      phone: "+41 79 123 45 67", invitations: 0, venueName: "Club Nord", notes: "", isVolunteerBenefit: false,
      volunteerId: null, lastModified: 0, isTemporaryGuest: false, temporaryArtistName: "",
      temporaryEventDate: null, temporaryContactPhone: "", temporaryVenueName: "", temporaryContactEmail: "",
      temporaryPortalEmail: "", temporaryAccessIds: "", temporaryEntryValidatedAt: 0,
      temporaryEntryValidatedBy: "", nfcCardUid: "", nfcCardUidHash: "", isAdmin: false, barDiscountPercent: 0,
      portalEnabled: true, portalUid: uid, portalGoogleEmail: "", portalGoogleUid: "", portalGoogleLinkedBy: "",
    });

    const page = (await db.doc(`orgs/${org}/guestPortal/${uid}`).get()).data();
    assert.ok(page, "guest page written");
    assert.deepEqual(Object.keys(page).sort(), ["contentHash", "payload", "schema", "updatedAt"]);
    assert.equal(page.schema, 1);
    assert.equal(page.contentHash, sha256(page.payload));
    assert.ok(!page.payload.includes(g.nanoId), "payload never carries the nanoId");
    const payload = JSON.parse(page.payload);
    assert.deepEqual(Object.keys(payload), GUEST_PAYLOAD_KEYS);
    assert.deepEqual(payload, {
      firstName: "Léa", lastName: "MARTIN DUPONT", email: "lea.martin@mail.example", phone: "+41 79 123 45 67",
      hasPhoto: false, photoVersion: "", venueName: "Club Nord", invitations: 0, barDiscountPercent: 0,
      wallet: { balance: 0, currency: "CHF", transfers: [] }, qrEmailAllowed: true, mustSetPassword: false,
      association: "Asso Test", googleEmail: "", googleLinkedBy: "", googleLinkAvailable: false,
    });
    assert.equal((await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).exists, false);

    const qr = (await db.doc(`orgs/${org}/guestPortalQr/${uid}`).get()).data();
    assert.deepEqual(qr, { schema: 1, payload: g.nanoId, name: "Léa MARTIN DUPONT" });
    assert.equal((await db.doc(`orgs/${org}/volunteerPortalQr/${uid}`).get()).exists, false);
  });

  test("guest signup copies the photo and skips the QR document when the QR is off", async () => {
    const org = await newOrg();
    await guestSpace(org);
    await db.doc(`orgs/${org}/institutionSettings/volunteer_portal_qr_enabled`).set({ value: "false", lastModified: Date.now() });
    const uid = "guestPhoto01";
    const formId = newFormId();
    const photoPath = await uploadSignupPhoto(org, formId);
    const ref = await submit(org, formId, guestSubmission(uid, { photoPath }), GUEST_FORM);
    await waitProcessed(ref);

    const [g] = await guests(org);
    const profilePath = `orgs/${org}/profilePhotos/guests/${g.nanoId}.jpg`;
    assert.equal(g.profilePhotoPath, profilePath);
    assert.equal("profilePhotoUrl" in g, false);
    assert.equal(await fileExists(photoPath), false, "original removed");
    for (const path of [profilePath, `orgs/${org}/portalPhotos/${uid}.jpg`]) {
      assert.deepEqual((await bucket.file(path).download())[0], JPEG, path);
    }
    const payload = JSON.parse((await db.doc(`orgs/${org}/guestPortal/${uid}`).get()).data().payload);
    assert.equal(payload.hasPhoto, true);
    assert.equal(payload.photoVersion, sha256(profilePath).slice(0, 16));
    assert.equal((await db.doc(`orgs/${org}/guestPortalQr/${uid}`).get()).exists, false);
  });

  test("guest signup links an existing guest", async () => {
    const org = await newOrg();
    await guestSpace(org);
    const uid = "guestLinkUid";
    const existing = guestDoc("existingGuest00000001", { email: " LEA@Mail.example " });
    // Same address, but not permanent guests: never linked by a guest registration.
    const temporary = guestDoc("temporaryGuest0000001", { email: "lea@mail.example", isTemporaryGuest: true });
    const benefit = guestDoc("benefitGuest000000001", { email: "lea@mail.example", isVolunteerBenefit: true });
    for (const d of [existing, temporary, benefit]) await db.doc(`orgs/${org}/guests/${d.nanoId}`).set(d);
    const ref = await submit(org, newFormId(), guestSubmission(uid, { email: "lea@mail.example", firstName: "Other" }), GUEST_FORM);
    await waitProcessed(ref);

    const all = await guests(org);
    assert.equal(all.length, 3, "nothing created");
    const byId = Object.fromEntries(all.map(({ docId, ...d }) => [docId, d]));
    const linked = byId[existing.nanoId];
    assert.equal(linked.portalUid, uid);
    assert.equal(linked.portalEnabled, true);
    assert.ok(linked.lastModified > existing.lastModified);
    assert.deepEqual({ ...linked, portalUid: "", portalEnabled: false, lastModified: existing.lastModified }, existing);
    assert.deepEqual(byId[temporary.nanoId], temporary);
    assert.deepEqual(byId[benefit.nanoId], benefit);
    // Reading the portal still requires verifying the address: nothing is published.
    assert.equal((await db.doc(`orgs/${org}/guestPortal/${uid}`).get()).exists, false);
    assert.equal((await volunteers(org)).length, 0);
  });

  test("guest signup ignored when guest space off", async () => {
    const org = await newOrg();
    await guestSpace(org, "false");
    const ref = await submit(org, newFormId(), guestSubmission("guestOffUid1"), GUEST_FORM);
    await barrier();

    const doc = (await ref.get()).data();
    assert.equal(doc.status, "SUBMITTED");
    assert.equal("processingDevice" in doc, false);
    assert.equal((await guests(org)).length, 0);
    assert.equal((await volunteers(org)).length, 0);
  });
});
