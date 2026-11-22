// Security rules for full guest list access (spec 2026-11-21 guest list access §7) — run with `npm test`.
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, serverTimestamp, setDoc, deleteDoc } from "firebase/firestore";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const HOUR = 60 * 60 * 1000;
const DATE = 1_800_000_000_000;

let env;
const portal = (uid, verified = true) =>
  env.authenticatedContext(uid, { email: `${uid}@mail.example`, email_verified: verified, firebase: { sign_in_provider: "password" } });
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

const edit = (uid, over = {}) => ({
  uid, email: `${uid}@mail.example`, grantId: "G1", eventDateMillis: DATE, action: "UPDATE",
  guestKey: "0123456789abcdef", firstName: "Ann", lastName: "", listName: "", venueName: "", accessIds: ["bs"],
  status: "PENDING", createdAt: serverTimestamp(), ...over,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-noctulist-guestlist",
    firestore: { rules: readFileSync(here("../firestore.rules"), "utf8"), host: "127.0.0.1", port: 8080 },
  });
});
after(async () => { await env?.cleanup(); });

beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (db) => {
    await setDoc(doc(db, path("metadata/config")), { createdAt: Date.now() });
    await setDoc(doc(db, path("guestListPortal/v1")), { payload: "{}", grantDates: [DATE], expiresAtMillis: Date.now() + HOUR });
    await setDoc(doc(db, path("guestListPortal/old")), { payload: "{}", grantDates: [DATE], expiresAtMillis: Date.now() - HOUR });
    await setDoc(doc(db, path("portalGoogleAccounts/gg1")), {
      volunteerUid: "v1", email: "gg1@asso.example", linkedBy: "LINK_CODE", linkedAt: Date.now(),
    });
  });
  await setSetting("volunteer_portal_enabled", "true");
  await setSetting("temp_guest_portal_enabled", "true");
});

describe("guestListPortal", () => {
  test("a verified session reads its own page only", async () => {
    const db = portal("v1").firestore();
    await assertSucceeds(getDoc(doc(db, path("guestListPortal/v1"))));
    await assertFails(getDoc(doc(portal("v2").firestore(), path("guestListPortal/v1"))));
    await assertFails(getDocs(collection(db, path("guestListPortal"))));
    await assertFails(setDoc(doc(db, path("guestListPortal/v1")), { payload: "x" }));
    await assertFails(getDoc(doc(anon().firestore(), path("guestListPortal/v1"))));
  });

  test("an unverified session never reads it", async () => {
    await assertFails(getDoc(doc(portal("v1", false).firestore(), path("guestListPortal/v1"))));
  });

  test("a linked Google session reads the page of its account", async () => {
    await setSetting("volunteer_portal_google_enabled", "true");
    await setSetting("volunteer_portal_google_domains", "asso.example");
    await assertSucceeds(getDoc(doc(google("gg1").firestore(), path("guestListPortal/v1"))));
  });

  test("an expired page or the space off is refused", async () => {
    await assertFails(getDoc(doc(portal("old").firestore(), path("guestListPortal/old"))));
    await setSetting("temp_guest_portal_enabled", "false");
    await assertFails(getDoc(doc(portal("v1").firestore(), path("guestListPortal/v1"))));
  });
});

describe("guestListEdits", () => {
  test("the account sends an edit for a granted date and reads it back", async () => {
    const db = portal("v1").firestore();
    await assertSucceeds(setDoc(doc(db, path("guestListEdits/e1")), edit("v1")));
    await assertSucceeds(getDoc(doc(db, path("guestListEdits/e1"))));
    await assertSucceeds(setDoc(doc(db, path("guestListEdits/e2")), edit("v1", { action: "ADD", guestKey: "", listName: "Friends", venueName: "Groove" })));
    await assertFails(getDoc(doc(portal("v2").firestore(), path("guestListEdits/e1"))));
    await assertFails(setDoc(doc(db, path("guestListEdits/e1")), edit("v1", { firstName: "Bo" })));
    await assertFails(deleteDoc(doc(db, path("guestListEdits/e1"))));
  });

  test("another date, account, key, action or field is refused", async () => {
    const db = portal("v1").firestore();
    await assertFails(setDoc(doc(db, path("guestListEdits/x1")), edit("v1", { eventDateMillis: DATE + 86_400_000 })));
    await assertFails(setDoc(doc(db, path("guestListEdits/x2")), edit("v2")));
    await assertFails(setDoc(doc(db, path("guestListEdits/x3")), edit("v1", { guestKey: "nanoid-raw-value" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/x4")), edit("v1", { action: "WIPE" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/x5")), edit("v1", { status: "DONE" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/x6")), edit("v1", { nanoId: "x" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/x7")), edit("v1", { firstName: "<b>" })));
    await assertFails(setDoc(doc(portal("v1", false).firestore(), path("guestListEdits/x8")), edit("v1")));
    await assertFails(setDoc(doc(anon().firestore(), path("guestListEdits/x9")), edit("v1")));
  });

  test("an ADD may carry an optional phone, contact address and notes", async () => {
    const db = portal("v1").firestore();
    const add = (over) => edit("v1", { action: "ADD", guestKey: "", listName: "Friends", venueName: "Groove", ...over });
    await assertSucceeds(setDoc(doc(db, path("guestListEdits/c1")), add({ phone: "+41 79 555 44 33", contactEmail: "me@mail.example", notes: "After midnight" })));
    await assertSucceeds(setDoc(doc(db, path("guestListEdits/c2")), add({ phone: "", contactEmail: "", notes: "" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/c3")), add({ phone: "call me" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/c4")), add({ contactEmail: "not-an-address" })));
    await assertFails(setDoc(doc(db, path("guestListEdits/c5")), add({ notes: "x".repeat(501) })));
    await assertFails(setDoc(doc(db, path("guestListEdits/c6")), add({ notes: 5 })));
  });

  test("a decision on a guest request needs a granted date and the review key", async () => {
    const db = portal("v1").firestore();
    const decide = (over) => edit("v1", {
      action: "ACCEPT", guestKey: undefined, reviewKey: "fedcba9876543210", firstName: "", accessIds: undefined,
      people: [{ name: "Ann Lee", accessIds: ["bs"] }], ...over,
    });
    const clean = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));
    await assertSucceeds(setDoc(doc(db, path("guestListEdits/d1")), clean(decide({}))));
    await assertSucceeds(setDoc(doc(db, path("guestListEdits/d2")), clean(decide({ action: "REJECT", people: [] }))));
    await assertFails(setDoc(doc(db, path("guestListEdits/d3")), clean(decide({ reviewKey: undefined }))));
    await assertFails(setDoc(doc(db, path("guestListEdits/d4")), clean(decide({ reviewKey: "NOT-HEX" }))));
    await assertFails(setDoc(doc(db, path("guestListEdits/d5")), clean(decide({ eventDateMillis: DATE + 86_400_000 }))));
    await assertFails(setDoc(doc(db, path("guestListEdits/d6")), clean(decide({ people: Array.from({ length: 101 }, () => ({ name: "A", accessIds: [] })) }))));
    await assertFails(setDoc(doc(db, path("guestListEdits/d7")), clean(decide({ people: "Ann" }))));
    await assertFails(setDoc(doc(portal("v2").firestore(), path("guestListEdits/d8")), clean(decide({}))));
  });

  test("a linked Google session edits for its account", async () => {
    await setSetting("volunteer_portal_google_enabled", "true");
    await setSetting("volunteer_portal_google_domains", "asso.example");
    await assertSucceeds(setDoc(doc(google("gg1").firestore(), path("guestListEdits/g1")), edit("v1", { email: "gg1@asso.example" })));
  });
});
