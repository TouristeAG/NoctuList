// Security rules for the volunteer portal — run with `npm test` (Firebase emulators).
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  updateDoc,
} from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const DAY = 24 * 60 * 60 * 1000;
const FORM = "signupFormAAAA0001";
const FORM_NO_PHOTO = "signupFormBBBB0002";
const FORM_EXPIRED = "signupFormCCCC0003";

let env;

const staff = (uid = "admin1") =>
  env.authenticatedContext(uid, {
    email: `${uid}@team.example`,
    email_verified: true,
    firebase: { sign_in_provider: "google.com" },
  });
const portal = (uid = "vol1", verified = true) =>
  env.authenticatedContext(uid, {
    email: `${uid}@mail.example`,
    email_verified: verified,
    firebase: { sign_in_provider: "password" },
  });
const anon = () => env.unauthenticatedContext();
const path = (p) => `orgs/${ORG}/${p}`;

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore(), ctx.storage()));
}

async function setPortal(on) {
  await seed((db) => setDoc(doc(db, path("institutionSettings/volunteer_portal_enabled")), {
    value: on ? "true" : "false",
    lastModified: Date.now(),
  }));
}

function submission(uid = "vol1", overrides = {}) {
  return {
    status: "SUBMITTED",
    submittedUid: uid,
    email: `${uid}@mail.example`,
    firstName: "Léa",
    lastName: "Martin",
    phone: "+41 79 123 45 67",
    dateOfBirth: "1998-04-12",
    gender: "FEMALE",
    photoPath: "",
    consent: true,
    lang: "fr",
    submittedAt: serverTimestamp(),
    lastModified: Date.now(),
    ...overrides,
  };
}

function request(uid, type, extra = {}) {
  return {
    uid,
    type,
    status: "PENDING",
    requestedAt: serverTimestamp(),
    email: `${uid}@mail.example`,
    ...extra,
  };
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-noctulist",
    firestore: { rules: readFileSync(here("../firestore.rules"), "utf8"), host: "127.0.0.1", port: 8080 },
    storage: { rules: readFileSync(here("../storage.rules"), "utf8"), host: "127.0.0.1", port: 9199 },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  const now = Date.now();
  await seed(async (db) => {
    await setDoc(doc(db, path("metadata/config")), { createdAt: now });
    await setDoc(doc(db, path("members/admin1")), { role: "admin", email: "admin1@team.example" });
    await setDoc(doc(db, path("volunteers/secretVolunteerId123")), { name: "Léa", nfcCardUid: "04AABBCC" });
    await setDoc(doc(db, path("accounts/VOLUNTEER:secretVolunteerId123")), { balance: 10 });
    await setDoc(doc(db, path("volunteerPortal/vol1")), { firstName: "Léa", wallet: { balance: 10 } });
    await setDoc(doc(db, path("volunteerPortal/vol2")), { firstName: "Max" });
    await setDoc(doc(db, path("portalBranding/default")), { associationName: "Asso" });
    await setDoc(doc(db, path(`volunteerSignupForms/${FORM}`)), {
      formId: FORM, status: "OPEN", expiresAtMillis: now + 7 * DAY, photosEnabled: true,
    });
    await setDoc(doc(db, path(`volunteerSignupForms/${FORM_NO_PHOTO}`)), {
      formId: FORM_NO_PHOTO, status: "OPEN", expiresAtMillis: now + 7 * DAY, photosEnabled: false,
    });
    await setDoc(doc(db, path(`volunteerSignupForms/${FORM_EXPIRED}`)), {
      formId: FORM_EXPIRED, status: "OPEN", expiresAtMillis: now - 1000, photosEnabled: true,
    });
  });
  await setPortal(true);
});

describe("portal accounts are not staff", () => {
  test("cannot read volunteers, accounts, members or the org config", async () => {
    const db = portal().firestore();
    await assertFails(getDoc(doc(db, path("volunteers/secretVolunteerId123"))));
    await assertFails(getDocs(collection(db, path("volunteers"))));
    await assertFails(getDoc(doc(db, path("accounts/VOLUNTEER:secretVolunteerId123"))));
    await assertFails(getDoc(doc(db, path("members/admin1"))));
    await assertFails(getDoc(doc(db, path("metadata/config"))));
  });

  test("cannot bootstrap a new organization", async () => {
    const db = portal().firestore();
    await assertFails(setDoc(doc(db, "orgs/fresh-org/members/vol1"), { role: "admin" }));
  });

  test("cannot join with a member document of its own", async () => {
    await seed((db) => setDoc(doc(db, path("metadata/config")), { bootstrapCode: "JOIN-ME" }));
    const db = portal().firestore();
    await assertFails(setDoc(doc(db, path("members/vol1")), { role: "member", bootstrapCode: "JOIN-ME" }));
  });

  test("Google staff keep their access", async () => {
    const db = staff().firestore();
    await assertSucceeds(getDoc(doc(db, path("volunteers/secretVolunteerId123"))));
    await assertSucceeds(getDocs(collection(db, path("volunteerPortal"))));
    await assertSucceeds(setDoc(doc(db, path("volunteerPortal/vol3")), { firstName: "New" }));
  });
});

describe("volunteerPortal projection", () => {
  test("owner with a verified e-mail can read it", async () => {
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), path("volunteerPortal/vol1"))));
  });

  test("unverified owner cannot", async () => {
    await assertFails(getDoc(doc(portal("vol1", false).firestore(), path("volunteerPortal/vol1"))));
  });

  test("another account cannot", async () => {
    await assertFails(getDoc(doc(portal("vol2").firestore(), path("volunteerPortal/vol1"))));
    await assertFails(getDoc(doc(anon().firestore(), path("volunteerPortal/vol1"))));
  });

  test("nobody outside staff can list or write", async () => {
    const db = portal("vol1").firestore();
    await assertFails(getDocs(collection(db, path("volunteerPortal"))));
    await assertFails(setDoc(doc(db, path("volunteerPortal/vol1")), { wallet: { balance: 999 } }));
    await assertFails(updateDoc(doc(db, path("volunteerPortal/vol1")), { firstName: "X" }));
    await assertFails(deleteDoc(doc(db, path("volunteerPortal/vol1"))));
  });

  test("everything is closed when the portal is turned off", async () => {
    await setPortal(false);
    await assertFails(getDoc(doc(portal("vol1").firestore(), path("volunteerPortal/vol1"))));
    await assertFails(getDoc(doc(anon().firestore(), path("portalBranding/default"))));
    await assertFails(getDoc(doc(anon().firestore(), path(`volunteerSignupForms/${FORM}`))));
  });

  test("a missing kill-switch document means off", async () => {
    await seed((db) => deleteDoc(doc(db, path("institutionSettings/volunteer_portal_enabled"))));
    await assertFails(getDoc(doc(portal("vol1").firestore(), path("volunteerPortal/vol1"))));
  });
});

describe("portal branding", () => {
  test("public get of the default document only", async () => {
    const db = anon().firestore();
    await assertSucceeds(getDoc(doc(db, path("portalBranding/default"))));
    await assertFails(getDoc(doc(db, path("portalBranding/other"))));
    await assertFails(getDocs(collection(db, path("portalBranding"))));
  });
});

describe("volunteerSignupForms", () => {
  test("anyone can get an open form, never list it", async () => {
    const db = anon().firestore();
    await assertSucceeds(getDoc(doc(db, path(`volunteerSignupForms/${FORM}`))));
    await assertFails(getDoc(doc(db, path(`volunteerSignupForms/${FORM_EXPIRED}`))));
    await assertFails(getDocs(collection(db, path("volunteerSignupForms"))));
  });

  test("a password account submits a valid registration once", async () => {
    const db = portal("vol1", false).firestore();
    const ref = doc(db, path(`volunteerSignupForms/${FORM}`));
    await assertSucceeds(updateDoc(ref, submission("vol1")));
    // Closed after submission: no longer readable or writable from the public side.
    await assertFails(getDoc(ref));
    await assertFails(updateDoc(doc(portal("vol2", false).firestore(), ref.path), submission("vol2")));
  });

  test("photo path must be the form's own path, and only when photos are on", async () => {
    const db = portal("vol1", false).firestore();
    await assertFails(updateDoc(doc(db, path(`volunteerSignupForms/${FORM}`)),
      submission("vol1", { photoPath: "orgs/org-a/profilePhotos/volunteers/secretVolunteerId123.jpg" })));
    await assertSucceeds(updateDoc(doc(db, path(`volunteerSignupForms/${FORM}`)),
      submission("vol1", { photoPath: `orgs/${ORG}/portalSignupPhotos/${FORM}.jpg` })));
    await assertFails(updateDoc(doc(db, path(`volunteerSignupForms/${FORM_NO_PHOTO}`)),
      submission("vol1", { photoPath: `orgs/${ORG}/portalSignupPhotos/${FORM_NO_PHOTO}.jpg` })));
  });

  test("rejects tampered submissions", async () => {
    const db = portal("vol1", false).firestore();
    const ref = doc(db, path(`volunteerSignupForms/${FORM}`));
    const bad = [
      { email: "someone-else@mail.example" },
      { submittedUid: "vol2" },
      { status: "OPEN" },
      { dateOfBirth: "12.04.1998" },
      { dateOfBirth: "1998-13-40" },
      { gender: "ROBOT" },
      { phone: "<script>" },
      { firstName: "" },
      { firstName: "   " },
      { lastName: "<img src=x>" },
      { firstName: "x".repeat(61) },
      { consent: false },
      { lang: "xx" },
      { submittedAt: Timestamp.fromMillis(Date.now() - DAY) },
      { isAdmin: true },
      { expiresAtMillis: Date.now() + 365 * DAY },
    ];
    for (const override of bad) {
      await assertFails(updateDoc(ref, submission("vol1", override)), JSON.stringify(override));
    }
  });

  test("expired forms and staff-only sign-in are refused", async () => {
    await assertFails(updateDoc(doc(portal("vol1", false).firestore(), path(`volunteerSignupForms/${FORM_EXPIRED}`)),
      submission("vol1")));
    await assertFails(updateDoc(doc(anon().firestore(), path(`volunteerSignupForms/${FORM}`)), submission("vol1")));
  });

  test("public side cannot create or delete forms", async () => {
    const db = portal("vol1").firestore();
    await assertFails(setDoc(doc(db, path("volunteerSignupForms/newFormAAAAAA01")), {
      status: "OPEN", expiresAtMillis: Date.now() + DAY,
    }));
    await assertFails(deleteDoc(doc(db, path(`volunteerSignupForms/${FORM}`))));
  });
});

describe("portalRequests", () => {
  const profile = (uid = "vol1", extra = {}) =>
    request(uid, "PROFILE", { firstName: "Léa", lastName: "Martin", phone: "+41 79 000 00 00", ...extra });

  test("owner creates one request per type", async () => {
    const db = portal("vol1").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile()));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "QR_EMAIL")));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_CLAIM")), request("vol1", "CLAIM")));
    await assertSucceeds(getDoc(doc(db, path("portalRequests/vol1_PROFILE"))));
  });

  test("owner can read a request that does not exist yet", async () => {
    await assertSucceeds(getDoc(doc(portal("vol1").firestore(), path("portalRequests/vol1_QR_EMAIL"))));
  });

  test("rejects foreign ids, bad fields and unverified accounts", async () => {
    const db = portal("vol1").firestore();
    await assertFails(setDoc(doc(db, path("portalRequests/vol2_PROFILE")), profile("vol2")));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile("vol1", { uid: "vol2" })));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "PROFILE")));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile("vol1", { firstName: "<b>" })));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile("vol1", { email: "x@y.z" })));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile("vol1", { dateOfBirth: "2000-01-01" })));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")),
      request("vol1", "QR_EMAIL", { email2: "attacker@evil.example" })));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_ADMIN")), request("vol1", "ADMIN")));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile("vol1", { status: "DONE" })));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")),
      profile("vol1", { requestedAt: Timestamp.fromMillis(Date.now() - DAY) })));
    await assertFails(setDoc(doc(portal("vol1", false).firestore(), path("portalRequests/vol1_CLAIM")),
      request("vol1", "CLAIM")));
    await assertFails(getDoc(doc(portal("vol2").firestore(), path("portalRequests/vol1_PROFILE"))));
    await assertFails(getDocs(collection(db, path("portalRequests"))));
  });

  test("renewals are throttled and blocked while processing", async () => {
    const db = portal("vol1").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "QR_EMAIL")));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "QR_EMAIL")));

    await seed((sdb) => setDoc(doc(sdb, path("portalRequests/vol1_QR_EMAIL")), {
      uid: "vol1", type: "QR_EMAIL", status: "DONE", email: "vol1@mail.example",
      requestedAt: Timestamp.fromMillis(Date.now() - 11 * 60 * 1000), processedAt: Date.now(),
    }));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "QR_EMAIL")));

    await seed((sdb) => setDoc(doc(sdb, path("portalRequests/vol1_PROFILE")), {
      uid: "vol1", type: "PROFILE", status: "PROCESSING", email: "vol1@mail.example",
      requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000),
    }));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile()));
  });

  test("a PROCESSING claim older than 5 minutes can be sent again by its owner only", async () => {
    const db = portal("vol1").firestore();
    const claimed = (processingAt) => seed((sdb) => setDoc(doc(sdb, path("portalRequests/vol1_PROFILE")), {
      uid: "vol1", type: "PROFILE", status: "PROCESSING", email: "vol1@mail.example",
      firstName: "Léa", lastName: "Martin", phone: "+41 79 000 00 00",
      requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000),
      processingAt, processingDevice: "gone-device",
    }));

    await claimed(Date.now() - 60 * 1000);
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile()));

    await claimed(Date.now() - 6 * 60 * 1000);
    await assertFails(setDoc(doc(portal("vol2").firestore(), path("portalRequests/vol1_PROFILE")), profile()));
    await assertFails(setDoc(doc(portal("vol2").firestore(), path("portalRequests/vol1_PROFILE")), profile("vol2")));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile()));
  });

  test("a stale claim is still throttled by its request time", async () => {
    const db = portal("vol1").firestore();
    await seed((sdb) => setDoc(doc(sdb, path("portalRequests/vol1_QR_EMAIL")), {
      uid: "vol1", type: "QR_EMAIL", status: "PROCESSING", email: "vol1@mail.example",
      requestedAt: Timestamp.fromMillis(Date.now() - 6 * 60 * 1000),
      processingAt: Date.now() - 6 * 60 * 1000, processingDevice: "gone-device",
    }));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "QR_EMAIL")));
  });

  test("portal accounts cannot delete requests; staff can process them", async () => {
    const db = portal("vol1").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_CLAIM")), request("vol1", "CLAIM")));
    await assertFails(deleteDoc(doc(db, path("portalRequests/vol1_CLAIM"))));
    const sdb = staff().firestore();
    await assertSucceeds(getDocs(collection(sdb, path("portalRequests"))));
    await assertSucceeds(updateDoc(doc(sdb, path("portalRequests/vol1_CLAIM")), { status: "DONE" }));
    await assertSucceeds(deleteDoc(doc(sdb, path("portalRequests/vol1_CLAIM"))));
  });

  test("closed when the portal is off", async () => {
    await setPortal(false);
    await assertFails(setDoc(doc(portal("vol1").firestore(), path("portalRequests/vol1_CLAIM")),
      request("vol1", "CLAIM")));
  });
});

describe("storage", () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46]);
  const meta = { contentType: "image/jpeg" };

  test("signup photo only for an open form that accepts photos", async () => {
    const storage = portal("vol1", false).storage();
    await assertSucceeds(uploadBytes(ref(storage, `orgs/${ORG}/portalSignupPhotos/${FORM}.jpg`), jpeg, meta));
    await assertFails(uploadBytes(ref(storage, `orgs/${ORG}/portalSignupPhotos/${FORM_NO_PHOTO}.jpg`), jpeg, meta));
    await assertFails(uploadBytes(ref(storage, `orgs/${ORG}/portalSignupPhotos/${FORM_EXPIRED}.jpg`), jpeg, meta));
    await assertFails(uploadBytes(ref(storage, `orgs/${ORG}/portalSignupPhotos/${FORM}.jpg`), jpeg,
      { contentType: "text/html" }));
    await assertFails(uploadBytes(ref(anon().storage(), `orgs/${ORG}/portalSignupPhotos/${FORM}.jpg`), jpeg, meta));
    await assertFails(getBytes(ref(storage, `orgs/${ORG}/portalSignupPhotos/${FORM}.jpg`)));
  });

  test("portal photo readable by its owner only", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`), jpeg, meta);
      await uploadBytes(ref(ctx.storage(), `orgs/${ORG}/profilePhotos/volunteers/secretVolunteerId123.jpg`), jpeg, meta);
    });
    await assertSucceeds(getBytes(ref(portal("vol1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await assertFails(getBytes(ref(portal("vol2").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await assertFails(getBytes(ref(portal("vol1", false).storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await assertFails(getBytes(ref(portal("vol1").storage(),
      `orgs/${ORG}/profilePhotos/volunteers/secretVolunteerId123.jpg`)));
    await assertFails(uploadBytes(ref(portal("vol1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`), jpeg, meta));
    await assertSucceeds(getBytes(ref(staff().storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
  });
});

describe("portalMail and portalServer", () => {
  test("members read and write the mail template only", async () => {
    const db = staff().firestore();
    await assertSucceeds(setDoc(doc(db, path("portalMail/default")), { subject: "Hi", lastModified: Date.now() }));
    await assertSucceeds(getDoc(doc(db, path("portalMail/default"))));
    await assertFails(getDoc(doc(portal("vol1").firestore(), path("portalMail/default"))));
    await assertFails(getDoc(doc(anon().firestore(), path("portalMail/default"))));
    await assertFails(setDoc(doc(portal("vol1").firestore(), path("portalMail/default")), { subject: "X" }));
  });

  test("members read the server status but nobody writes it", async () => {
    await seed((db) => setDoc(doc(db, path("portalServer/status")), { version: "1", lastRunAt: Date.now() }));
    await assertSucceeds(getDoc(doc(staff().firestore(), path("portalServer/status"))));
    await assertFails(getDoc(doc(portal("vol1").firestore(), path("portalServer/status"))));
    await assertFails(getDoc(doc(anon().firestore(), path("portalServer/status"))));
    await assertFails(setDoc(doc(staff().firestore(), path("portalServer/status")), { version: "2", lastRunAt: 1 }));
    await assertFails(deleteDoc(doc(staff().firestore(), path("portalServer/status"))));
    await assertFails(setDoc(doc(portal("vol1").firestore(), path("portalServer/status")), { version: "2" }));
  });
});
