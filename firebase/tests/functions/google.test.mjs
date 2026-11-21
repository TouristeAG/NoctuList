// Integration tests for the Google link Cloud Function (portalGoogleClaims) and the GOOGLE_LINK /
// GOOGLE_UNLINK portal requests. Run with `npm run test:functions`: the emulators run Firestore and
// the functions, and this file polls for the results. Same decisions as PortalGoogleLinkProcessor.
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";

const PROJECT = "demo-noctulist";
const DOMAIN = "asso.example";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run through `npm run test:functions`: the emulators must be running.");
}

let app;
let db;
let auth;
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
async function newOrg({ portal = "true", google = "true", domains = `${DOMAIN},other-asso.example`, anyAccount = null } = {}) {
  counter += 1;
  const org = `org-gg-${counter}-${Date.now()}`;
  const now = Date.now();
  const setting = (key, value) => db.doc(`orgs/${org}/institutionSettings/${key}`).set({ value, lastModified: now });
  await db.doc(`orgs/${org}/metadata/config`).set({ createdAt: now });
  await setting("volunteer_portal_enabled", portal);
  await setting("volunteer_portal_google_enabled", google);
  await setting("volunteer_portal_google_domains", domains);
  if (anyAccount !== null) await setting("volunteer_portal_google_any_account", anyAccount);
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

/** One-time link code in the format of the portal page (32 to 64 URL-safe characters). */
function newCode() {
  counter += 1;
  return `code_${counter}_${Date.now().toString(36)}`.padEnd(40, "A");
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
    isAdmin: false,
    lastModified: 1000,
    portalEnabled: true,
    portalUid: newUid("portal"),
    portalGoogleEmail: "",
    portalGoogleUid: "",
    portalGoogleLinkedBy: "",
    ...overrides,
  };
  await db.doc(`orgs/${org}/volunteers/${id}`).set(data);
  return data;
}

async function volunteer(org, id) {
  return (await db.doc(`orgs/${org}/volunteers/${id}`).get()).data();
}

/** The page a device published, without the Google fields (as before this feature). */
async function publishProjection(org, uid, extra = {}) {
  const payload = JSON.stringify({
    firstName: "Léa", lastName: "MARTIN", email: "lea@mail.example", phone: "079 000 00 00",
    shifts: [], qrEmailAllowed: true, mustSetPassword: false, association: "Asso Test", ...extra,
  });
  await db.doc(`orgs/${org}/volunteerPortal/${uid}`).set({ schema: 2, payload, contentHash: sha256(payload), updatedAt: 1 });
  return JSON.parse(payload);
}

async function projection(org, uid) {
  const doc = (await db.doc(`orgs/${org}/volunteerPortal/${uid}`).get()).data();
  assert.equal(doc.contentHash, sha256(doc.payload));
  return JSON.parse(doc.payload);
}

/** Written by the portal page after a Google sign-in. */
async function sendClaim(org, googleUid, type, email, code) {
  const ref = db.doc(`orgs/${org}/portalGoogleClaims/${googleUid}`);
  await ref.set({
    type,
    status: "PENDING",
    requestedAt: FieldValue.serverTimestamp(),
    email,
    ...(code !== undefined ? { code } : {}),
  });
  return ref;
}

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

function assertResult(doc, status, reason) {
  assert.equal(doc.status, status);
  assert.equal(doc.reason, reason);
  assert.equal(typeof doc.processedAt, "number");
  assert.equal(doc.processingDevice, "server");
}

/** Proves both functions are running: a claim and a request in another org get processed. */
async function barrier() {
  const org = await newOrg();
  const v = await addVolunteer(org);
  const claimRef = await sendClaim(org, newUid("barrierG"), "ADMIN_MATCH", `nobody@${DOMAIN}`);
  const requestRef = await sendRequest(org, v.portalUid, "GOOGLE_UNLINK");
  await waitResult(claimRef);
  await waitResult(requestRef);
  await sleep(1000);
}

/** The volunteer's e-mail account, as grantAccess created it earlier (Auth emulator). */
async function addAccount(email, { password = true } = {}) {
  const user = await auth.createUser({ uid: newUid("account"), email, ...(password ? { password: "Secret-123456" } : {}) });
  return user.uid;
}

/** A fresh roster address per test: the Auth emulator keeps accounts across tests. */
function newEmail() {
  counter += 1;
  return `lea${counter}.${Date.now().toString(36)}@mail.example`;
}

async function alias(org, googleUid) {
  const snap = await db.doc(`orgs/${org}/portalGoogleAccounts/${googleUid}`).get();
  return snap.exists ? snap.data() : null;
}

before(async () => {
  app = initializeApp({ projectId: PROJECT }, "google-tests");
  db = getFirestore(app);
  auth = getAuth(app);
});

after(async () => {
  if (app) await deleteApp(app);
});

describe("onPortalGoogleClaimWritten: ADMIN_MATCH", () => {
  test("admin match links the only volunteer", async () => {
    const org = await newOrg();
    const future = Date.now() + 10 * 60 * 1000;
    const v = await addVolunteer(org, {
      portalGoogleEmail: ` Lea.Martin@${DOMAIN} `, portalGoogleLinkedBy: "ADMIN", lastModified: future,
    });
    // Same address on an entry without portal access: not a candidate.
    await addVolunteer(org, { portalEnabled: false, portalGoogleEmail: `lea.martin@${DOMAIN}` });
    const before = await publishProjection(org, v.portalUid);
    const googleUid = newUid("google");

    const ref = await sendClaim(org, googleUid, "ADMIN_MATCH", `LEA.martin@${DOMAIN}`);
    const result = await waitResult(ref);
    assertResult(result, "DONE", "");
    assert.equal(result.code, "");

    const a = await alias(org, googleUid);
    assert.deepEqual({ ...a, linkedAt: 0 }, { volunteerUid: v.portalUid, email: `lea.martin@${DOMAIN}`, linkedBy: "ADMIN", linkedAt: 0 });
    assert.equal(typeof a.linkedAt, "number");

    const after = await volunteer(org, v.id);
    assert.equal(after.portalGoogleUid, googleUid);
    assert.equal(after.portalGoogleEmail, `Lea.Martin@${DOMAIN}`, "the address the admin typed is kept, trimmed");
    assert.equal(after.portalGoogleLinkedBy, "ADMIN");
    assert.equal(after.lastModified, future + 1);

    const page = await projection(org, v.portalUid);
    assert.deepEqual(Object.keys(page), [...Object.keys(before), "googleEmail", "googleLinkedBy", "googleLinkAvailable"]);
    assert.equal(page.googleEmail, `Lea.Martin@${DOMAIN}`);
    assert.equal(page.googleLinkedBy, "ADMIN");
    assert.equal(page.googleLinkAvailable, false);
    assert.ok(!JSON.stringify(page).includes(googleUid), "the Google uid stays off the page");
  });

  test("admin match keeps a volunteer link as VOLUNTEER", async () => {
    const org = await newOrg();
    // Linked by the volunteer earlier; the alias is gone (deleted, or a new Google account).
    const v = await addVolunteer(org, {
      portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleUid: newUid("oldGoogle"), portalGoogleLinkedBy: "VOLUNTEER",
    });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "DONE", "");
    assert.equal((await alias(org, googleUid)).linkedBy, "VOLUNTEER");
    const after = await volunteer(org, v.id);
    assert.equal(after.portalGoogleUid, googleUid);
    assert.equal(after.portalGoogleLinkedBy, "VOLUNTEER");
  });

  test("admin match drops the previous alias of the volunteer", async () => {
    const org = await newOrg();
    const oldUid = newUid("oldGoogle");
    const v = await addVolunteer(org, {
      portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleUid: oldUid, portalGoogleLinkedBy: "ADMIN",
    });
    await db.doc(`orgs/${org}/portalGoogleAccounts/${oldUid}`).set({ volunteerUid: v.portalUid, email: `lea@${DOMAIN}`, linkedBy: "ADMIN", linkedAt: 1 });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "DONE", "");
    assert.equal(await alias(org, oldUid), null, "the previous Google account loses access");
    assert.equal((await alias(org, googleUid)).volunteerUid, v.portalUid);
    assert.equal((await volunteer(org, v.id)).portalGoogleUid, googleUid);
  });

  test("gmail.com in the domain list is ignored", async () => {
    const org = await newOrg({ domains: "gmail.com" });
    const v = await addVolunteer(org, { portalGoogleEmail: "lea@gmail.com", portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", "lea@gmail.com")), "REJECTED", "DOMAIN");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("any-account mode links a personal Google account without any domain", async () => {
    const org = await newOrg({ domains: "", anyAccount: "true" });
    const v = await addVolunteer(org, { portalGoogleEmail: "lea@gmail.com", portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", "lea@gmail.com")), "DONE", "");
    assert.equal((await alias(org, googleUid)).volunteerUid, v.portalUid);
  });

  test("admin match with a valid alias is done without writing", async () => {
    const org = await newOrg();
    const googleUid = newUid("google");
    const v = await addVolunteer(org, { portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleUid: googleUid, portalGoogleLinkedBy: "ADMIN" });
    const existing = { volunteerUid: v.portalUid, email: `lea@${DOMAIN}`, linkedBy: "ADMIN", linkedAt: 5 };
    await db.doc(`orgs/${org}/portalGoogleAccounts/${googleUid}`).set(existing);
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "DONE", "");
    assert.deepEqual(await alias(org, googleUid), existing);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("no match", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org, { portalGoogleEmail: `someone@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("ambiguous", async () => {
    const org = await newOrg();
    await addVolunteer(org, { portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    await addVolunteer(org, { portalGoogleEmail: `LEA@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "AMBIGUOUS");
    assert.equal(await alias(org, googleUid), null);
  });

  test("domain refused", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org, { portalGoogleEmail: "lea@gmail.example", portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", "lea@gmail.example")), "REJECTED", "DOMAIN");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("stale alias is deleted before deciding", async () => {
    const org = await newOrg();
    const googleUid = newUid("google");
    // The alias points at a volunteer whose access was revoked.
    await db.doc(`orgs/${org}/portalGoogleAccounts/${googleUid}`).set({ volunteerUid: "goneUid123", email: `lea@${DOMAIN}`, linkedBy: "ADMIN", linkedAt: 1 });
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.equal(await alias(org, googleUid), null);
  });

  test("invalid claim rejected", async () => {
    const org = await newOrg();
    const ref = await sendClaim(org, newUid("google"), "LINK_CODE", `lea@${DOMAIN}`, "short");
    const result = await waitResult(ref);
    assertResult(result, "REJECTED", "INVALID");
    assert.equal(result.code, "");
  });
});

describe("onPortalGoogleClaimWritten: protocol", () => {
  test("option off: claim ignored and left PENDING", async () => {
    const org = await newOrg({ google: "false" });
    const v = await addVolunteer(org, { portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const offPortal = await newOrg({ portal: "false" });
    const googleUid = newUid("google");
    const ref = await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`);
    const offRef = await sendClaim(offPortal, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`);
    await barrier();
    for (const r of [ref, offRef]) {
      const doc = (await r.get()).data();
      assert.equal(doc.status, "PENDING");
      assert.equal("processingDevice" in doc, false);
      assert.equal("reason" in doc, false);
    }
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });
});

describe("onPortalGoogleClaimWritten: ADMIN_MATCH on a reopened access", () => {
  // Reopening an access (or opening one for an address that already has an account) leaves
  // portalUid empty: the device cannot learn the uid of an existing account. The server can.
  test("links the volunteer's existing e-mail account, then the Google account", async () => {
    const org = await newOrg();
    const email = newEmail();
    const accountUid = await addAccount(email);
    const future = Date.now() + 10 * 60 * 1000;
    const v = await addVolunteer(org, {
      email: ` ${email.toUpperCase()} `, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN",
      lastModified: future, sourceDeviceId: "admin-device-1",
    });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "DONE", "");

    assert.equal((await alias(org, googleUid)).volunteerUid, accountUid);
    const after = await volunteer(org, v.id);
    assert.equal(after.portalUid, accountUid);
    assert.equal(after.portalGoogleUid, googleUid);
    assert.equal(after.portalGoogleLinkedBy, "ADMIN");
    assert.equal(after.lastModified, future + 1);
    assert.equal(after.sourceDeviceId, "server", "the admin device must not drop the change as its own echo");
    assert.deepEqual(
      { ...after, portalUid: "", portalGoogleUid: "", lastModified: future, sourceDeviceId: "admin-device-1" },
      v,
      "nothing else changes on the roster entry",
    );
  });

  test("no account for the roster address: no match", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org, { email: newEmail(), portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("an account without a password is not a portal account", async () => {
    const org = await newOrg();
    const email = newEmail();
    await addAccount(email, { password: false });
    const v = await addVolunteer(org, { email, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("an account already used by another volunteer is not taken", async () => {
    const org = await newOrg();
    const email = newEmail();
    const accountUid = await addAccount(email);
    await addVolunteer(org, { email, portalUid: accountUid });
    const v = await addVolunteer(org, { email, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("two entries with portal access share the roster address: no match", async () => {
    const org = await newOrg();
    const email = newEmail();
    await addAccount(email);
    const v = await addVolunteer(org, { email, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    await addVolunteer(org, { email, portalUid: "" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("two unopened entries with the same Google address: ambiguous", async () => {
    const org = await newOrg();
    const first = newEmail();
    const second = newEmail();
    await addAccount(first);
    await addAccount(second);
    await addVolunteer(org, { email: first, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    await addVolunteer(org, { email: second, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "AMBIGUOUS");
    assert.equal(await alias(org, googleUid), null);
  });

  test("access closed: never opened through Google", async () => {
    const org = await newOrg();
    const email = newEmail();
    await addAccount(email);
    const v = await addVolunteer(org, { email, portalEnabled: false, portalUid: "", portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "ADMIN_MATCH", `lea@${DOMAIN}`)), "REJECTED", "NO_MATCH");
    assert.deepEqual(await volunteer(org, v.id), v);
  });
});

describe("onPortalGoogleClaimWritten: LINK_CODE", () => {
  test("link code links and removes the code", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org);
    const before = await publishProjection(org, v.portalUid, { googleEmail: "", googleLinkedBy: "", googleLinkAvailable: true });
    const code = newCode();
    const request = await sendRequest(org, v.portalUid, "GOOGLE_LINK", { codeHash: sha256(code) });
    const googleUid = newUid("google");

    const result = await waitResult(await sendClaim(org, googleUid, "LINK_CODE", `Lea@${DOMAIN}`, code));
    assertResult(result, "DONE", "");
    assert.equal(result.code, "", "the one-time code is removed");
    const answered = (await request.get()).data();
    assert.equal(answered.status, "DONE");
    assert.equal(answered.reason, "");

    const a = await alias(org, googleUid);
    assert.equal(a.volunteerUid, v.portalUid);
    assert.equal(a.email, `lea@${DOMAIN}`);
    assert.equal(a.linkedBy, "VOLUNTEER");
    const after = await volunteer(org, v.id);
    assert.equal(after.portalGoogleEmail, `lea@${DOMAIN}`);
    assert.equal(after.portalGoogleUid, googleUid);
    assert.equal(after.portalGoogleLinkedBy, "VOLUNTEER");
    assert.ok(after.lastModified > 1000);
    assert.equal(after.sourceDeviceId, "server");

    const page = await projection(org, v.portalUid);
    assert.deepEqual(Object.keys(page), Object.keys(before), "key order kept");
    assert.deepEqual(page, { ...before, googleEmail: `lea@${DOMAIN}`, googleLinkedBy: "VOLUNTEER", googleLinkAvailable: false });
  });

  test("wrong code", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org);
    const request = await sendRequest(org, v.portalUid, "GOOGLE_LINK", { codeHash: sha256(newCode()) });
    const googleUid = newUid("google");
    const result = await waitResult(await sendClaim(org, googleUid, "LINK_CODE", `lea@${DOMAIN}`, newCode()));
    assertResult(result, "REJECTED", "CODE");
    assert.equal(result.code, "");
    assert.equal((await request.get()).data().status, "PENDING");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("expired code", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org);
    const code = newCode();
    await sendRequest(org, v.portalUid, "GOOGLE_LINK", {
      codeHash: sha256(code), requestedAt: Timestamp.fromMillis(Date.now() - 11 * 60 * 1000),
    });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "LINK_CODE", `lea@${DOMAIN}`, code)), "REJECTED", "CODE");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("volunteer already has an admin address", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org, { portalGoogleEmail: `admin.typed@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const code = newCode();
    await sendRequest(org, v.portalUid, "GOOGLE_LINK", { codeHash: sha256(code) });
    const googleUid = newUid("google");
    assertResult(await waitResult(await sendClaim(org, googleUid, "LINK_CODE", `lea@${DOMAIN}`, code)), "REJECTED", "ALREADY_LINKED");
    assert.equal(await alias(org, googleUid), null);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("link code from another domain refused", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org);
    const code = newCode();
    await sendRequest(org, v.portalUid, "GOOGLE_LINK", { codeHash: sha256(code) });
    assertResult(await waitResult(await sendClaim(org, newUid("google"), "LINK_CODE", "lea@gmail.example", code)), "REJECTED", "DOMAIN");
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("google account taken", async () => {
    const org = await newOrg();
    const googleUid = newUid("google");
    const v = await addVolunteer(org);
    await addVolunteer(org, { portalGoogleEmail: `other@${DOMAIN}`, portalGoogleUid: googleUid, portalGoogleLinkedBy: "VOLUNTEER" });
    const code = newCode();
    await sendRequest(org, v.portalUid, "GOOGLE_LINK", { codeHash: sha256(code) });
    assertResult(await waitResult(await sendClaim(org, googleUid, "LINK_CODE", `lea@${DOMAIN}`, code)), "REJECTED", "TAKEN");
    assert.deepEqual(await volunteer(org, v.id), v);

    // The address is used by another entry: taken as well.
    const w = await addVolunteer(org);
    await addVolunteer(org, { portalEnabled: false, portalGoogleEmail: `Taken@${DOMAIN}`, portalGoogleLinkedBy: "ADMIN" });
    const code2 = newCode();
    await sendRequest(org, w.portalUid, "GOOGLE_LINK", { codeHash: sha256(code2) });
    assertResult(await waitResult(await sendClaim(org, newUid("google"), "LINK_CODE", `taken@${DOMAIN}`, code2)), "REJECTED", "TAKEN");
    assert.deepEqual(await volunteer(org, w.id), w);
  });
});

describe("onPortalRequestWritten: Google requests", () => {
  test("unlink of a volunteer link", async () => {
    const org = await newOrg();
    const googleUid = newUid("google");
    const v = await addVolunteer(org, { portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleUid: googleUid, portalGoogleLinkedBy: "VOLUNTEER" });
    await db.doc(`orgs/${org}/portalGoogleAccounts/${googleUid}`).set({ volunteerUid: v.portalUid, email: `lea@${DOMAIN}`, linkedBy: "VOLUNTEER", linkedAt: 1 });
    const before = await publishProjection(org, v.portalUid, { googleEmail: `lea@${DOMAIN}`, googleLinkedBy: "VOLUNTEER", googleLinkAvailable: false });

    assertResult(await waitResult(await sendRequest(org, v.portalUid, "GOOGLE_UNLINK")), "DONE", "");
    assert.equal(await alias(org, googleUid), null);
    const after = await volunteer(org, v.id);
    assert.equal(after.portalGoogleEmail, "");
    assert.equal(after.portalGoogleUid, "");
    assert.equal(after.portalGoogleLinkedBy, "");
    assert.ok(after.lastModified > 1000);
    assert.equal(after.sourceDeviceId, "server");
    const page = await projection(org, v.portalUid);
    assert.deepEqual(Object.keys(page), Object.keys(before));
    assert.deepEqual(page, { ...before, googleEmail: "", googleLinkedBy: "", googleLinkAvailable: true });
  });

  test("unlink of an admin link is refused with ADMIN_LINK", async () => {
    const org = await newOrg();
    const googleUid = newUid("google");
    const v = await addVolunteer(org, { portalGoogleEmail: `lea@${DOMAIN}`, portalGoogleUid: googleUid, portalGoogleLinkedBy: "ADMIN" });
    const existing = { volunteerUid: v.portalUid, email: `lea@${DOMAIN}`, linkedBy: "ADMIN", linkedAt: 1 };
    await db.doc(`orgs/${org}/portalGoogleAccounts/${googleUid}`).set(existing);
    assertResult(await waitResult(await sendRequest(org, v.portalUid, "GOOGLE_UNLINK")), "REJECTED", "ADMIN_LINK");
    assert.deepEqual(await alias(org, googleUid), existing);
    assert.deepEqual(await volunteer(org, v.id), v);
  });

  test("unlink without access refused with NO_ACCESS", async () => {
    const org = await newOrg();
    assertResult(await waitResult(await sendRequest(org, newUid("nobody"), "GOOGLE_UNLINK")), "REJECTED", "NO_ACCESS");
  });

  test("GOOGLE_LINK request alone stays PENDING", async () => {
    const org = await newOrg();
    const v = await addVolunteer(org);
    const ref = await sendRequest(org, v.portalUid, "GOOGLE_LINK", { codeHash: sha256(newCode()) });
    await barrier();
    const doc = (await ref.get()).data();
    assert.equal(doc.status, "PENDING");
    assert.equal("processingDevice" in doc, false);
    assert.equal("reason" in doc, false);
  });
});
