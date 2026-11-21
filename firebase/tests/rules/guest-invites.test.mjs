// Security rules for portal invites and temporary guest name requests (spec 2026-11-21) — run with `npm test`.
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, serverTimestamp } from "firebase/firestore";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const HOUR = 60 * 60 * 1000;
const KEY = "0123456789abcdef";

let env;

const portal = (uid, verified = true) =>
  env.authenticatedContext(uid, {
    email: `${uid}@mail.example`,
    email_verified: verified,
    firebase: { sign_in_provider: "password" },
  });
const google = (uid, email = `${uid}@asso.example`) =>
  env.authenticatedContext(uid, { email, email_verified: true, firebase: { sign_in_provider: "google.com" } });
const anon = () => env.unauthenticatedContext();
const path = (p) => `orgs/${ORG}/${p}`;

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
}
async function setSetting(key, value) {
  await seed((db) => setDoc(doc(db, path(`institutionSettings/${key}`)), { value, lastModified: Date.now() }));
}

const template = (over = {}) => ({
  formId: "TPL1",
  portalUid: "t1",
  status: "OPEN",
  allowMultipleResponses: true,
  venueName: "Club",
  eventName: "",
  eventDateMillis: 1_800_000_000_000,
  artistName: "Friends",
  offeredAccessIds: "",
  maxGuests: 5,
  expiresAtMillis: Date.now() + HOUR,
  ...over,
});

const answer = (over = {}) => ({
  formId: "R1",
  parentFormId: "TPL1",
  portalUid: "t1",
  status: "PENDING_REVIEW",
  requestKind: "ADD",
  removeGuestKeys: "",
  submissionJson: '{"people":[{"name":"Cy Doe","access":[]}]}',
  venueName: "Club",
  eventName: "",
  eventDateMillis: 1_800_000_000_000,
  artistName: "Friends",
  offeredAccessIds: "",
  maxGuests: 5,
  expiresAtMillis: template().expiresAtMillis,
  submittedAt: Date.now(),
  lastModified: Date.now(),
  ...over,
});

const nameRequest = (uid, over = {}) => ({
  uid,
  type: "TEMP_GUEST_NAME",
  status: "PENDING",
  requestedAt: serverTimestamp(),
  email: `${uid}@mail.example`,
  guestKey: KEY,
  firstName: "Ann",
  lastName: "Doe",
  ...over,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-noctulist-invites",
    firestore: { rules: readFileSync(here("../firestore.rules"), "utf8"), host: "127.0.0.1", port: 8080 },
  });
});

after(async () => {
  await env?.cleanup();
});

let expires;

beforeEach(async () => {
  await env.clearFirestore();
  expires = Date.now() + HOUR;
  await seed(async (db) => {
    await setDoc(doc(db, path("metadata/config")), { createdAt: Date.now() });
    await setDoc(doc(db, path("temporaryGuestPortal/t1")), { deviceCreated: true, expiresAtMillis: expires });
    await setDoc(doc(db, path("temporaryGuestPortal/v1")), { deviceCreated: false, expiresAtMillis: expires });
    await setDoc(doc(db, path("guestForms/TPL1")), template({ expiresAtMillis: expires }));
    await setDoc(doc(db, path("guestForms/TPLV")), template({ formId: "TPLV", portalUid: "v1", expiresAtMillis: expires }));
    await setDoc(doc(db, path("guestForms/PUB")), template({ formId: "PUB", portalUid: "", expiresAtMillis: expires }));
    await setDoc(doc(db, path("portalGoogleAccounts/gg1")), {
      volunteerUid: "v1", email: "gg1@asso.example", linkedBy: "LINK_CODE", linkedAt: Date.now(),
    });
  });
  await setSetting("volunteer_portal_enabled", "true");
  await setSetting("temp_guest_portal_enabled", "true");
});

describe("TEMP_GUEST_NAME requests", () => {
  test("a device-created account renames from an unverified session", async () => {
    const db = portal("t1", false).firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/t1_TEMP_GUEST_NAME")), nameRequest("t1")));
    await assertSucceeds(getDoc(doc(db, path("portalRequests/t1_TEMP_GUEST_NAME"))));
  });

  test("a verified session renames on a joined account", async () => {
    const db = portal("v1", true).firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/v1_TEMP_GUEST_NAME")), nameRequest("v1")));
  });

  test("an unverified session of a joined account is refused", async () => {
    const db = portal("v1", false).firestore();
    await assertFails(setDoc(doc(db, path("portalRequests/v1_TEMP_GUEST_NAME")), nameRequest("v1")));
  });

  test("another uid, a bad key, empty names or extra keys are refused", async () => {
    const db = portal("t1", false).firestore();
    await assertFails(setDoc(doc(db, path("portalRequests/t2_TEMP_GUEST_NAME")), nameRequest("t2")));
    await assertFails(setDoc(doc(db, path("portalRequests/t1_TEMP_GUEST_NAME")), nameRequest("t1", { guestKey: "nanoId-raw-value" })));
    await assertFails(setDoc(doc(db, path("portalRequests/t1_TEMP_GUEST_NAME")), nameRequest("t1", { firstName: "", lastName: "" })));
    await assertFails(setDoc(doc(db, path("portalRequests/t1_TEMP_GUEST_NAME")), nameRequest("t1", { phone: "1" })));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/t1_TEMP_GUEST_NAME")), nameRequest("t1", { lastName: "" })));
  });

  test("refused while the temporary space is off", async () => {
    await setSetting("temp_guest_portal_enabled", "false");
    await assertFails(setDoc(doc(portal("t1", false).firestore(), path("portalRequests/t1_TEMP_GUEST_NAME")), nameRequest("t1")));
    await assertFails(setDoc(doc(portal("v1", true).firestore(), path("portalRequests/v1_TEMP_GUEST_NAME")), nameRequest("v1")));
  });
});

describe("portal invite templates", () => {
  test("the bound account reads its template; the public and other accounts do not", async () => {
    await assertSucceeds(getDoc(doc(portal("t1", false).firestore(), path("guestForms/TPL1"))));
    await assertFails(getDoc(doc(anon().firestore(), path("guestForms/TPL1"))));
    await assertFails(getDoc(doc(portal("t2", true).firestore(), path("guestForms/TPL1"))));
    await assertSucceeds(getDoc(doc(anon().firestore(), path("guestForms/PUB"))));
  });

  test("a Google alias reads the template of its account", async () => {
    await setSetting("volunteer_portal_google_enabled", "true");
    await setSetting("volunteer_portal_google_domains", "asso.example");
    await assertSucceeds(getDoc(doc(google("gg1").firestore(), path("guestForms/TPLV"))));
    await assertSucceeds(setDoc(doc(google("gg1").firestore(), path("guestForms/RG")),
      answer({ formId: "RG", parentFormId: "TPLV", portalUid: "v1", expiresAtMillis: expires })));
  });
});

describe("portal invite answers", () => {
  test("the bound account adds and removes", async () => {
    const db = portal("t1", false).firestore();
    await assertSucceeds(setDoc(doc(db, path("guestForms/R1")), answer({ expiresAtMillis: expires })));
    await assertSucceeds(setDoc(doc(db, path("guestForms/R2")), answer({
      formId: "R2", requestKind: "REMOVE", removeGuestKeys: KEY, submissionJson: "", expiresAtMillis: expires,
    })));
  });

  test("another account, no session, or a joined account without verification is refused", async () => {
    await assertFails(setDoc(doc(portal("t2", true).firestore(), path("guestForms/R1")), answer({ expiresAtMillis: expires })));
    await assertFails(setDoc(doc(anon().firestore(), path("guestForms/R1")), answer({ expiresAtMillis: expires })));
    await assertFails(setDoc(doc(anon().firestore(), path("guestForms/R1")), answer({ portalUid: "", expiresAtMillis: expires })));
    await assertFails(setDoc(doc(portal("v1", false).firestore(), path("guestForms/R3")),
      answer({ formId: "R3", parentFormId: "TPLV", portalUid: "v1", expiresAtMillis: expires })));
    await assertSucceeds(setDoc(doc(portal("v1", true).firestore(), path("guestForms/R3")),
      answer({ formId: "R3", parentFormId: "TPLV", portalUid: "v1", expiresAtMillis: expires })));
  });

  test("closed or expired templates take no answer", async () => {
    await seed((db) => setDoc(doc(db, path("guestForms/TPL1")), template({ status: "EXPIRED", expiresAtMillis: expires })));
    await assertFails(setDoc(doc(portal("t1", false).firestore(), path("guestForms/R1")), answer({ expiresAtMillis: expires })));
    await seed((db) => setDoc(doc(db, path("guestForms/TPL1")), template({ expiresAtMillis: Date.now() - 1 })));
    await assertFails(setDoc(doc(portal("t1", false).firestore(), path("guestForms/R1")), answer({ expiresAtMillis: Date.now() - 1 })));
  });

  test("a mismatched portalUid, unknown kind or changed limits are refused", async () => {
    const db = portal("t1", false).firestore();
    await assertFails(setDoc(doc(db, path("guestForms/R1")), answer({ portalUid: "t2", expiresAtMillis: expires })));
    await assertFails(setDoc(doc(db, path("guestForms/R1")), answer({ requestKind: "EDIT", expiresAtMillis: expires })));
    await assertFails(setDoc(doc(db, path("guestForms/R1")), answer({ maxGuests: 50, expiresAtMillis: expires })));
    await assertFails(setDoc(doc(db, path("guestForms/R1")), answer({ status: "ACCEPTED", expiresAtMillis: expires })));
  });

  test("a public template still takes public answers but never a bound one", async () => {
    const pub = answer({ formId: "RP", parentFormId: "PUB", portalUid: "", expiresAtMillis: expires });
    delete pub.requestKind;
    delete pub.removeGuestKeys;
    await assertSucceeds(setDoc(doc(anon().firestore(), path("guestForms/RP")), pub));
    await assertFails(setDoc(doc(portal("t1", false).firestore(), path("guestForms/RQ")),
      answer({ formId: "RQ", parentFormId: "PUB", portalUid: "t1", expiresAtMillis: expires })));
  });
});
