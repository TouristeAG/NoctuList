// Security rules for the volunteer portal's Google sign-in — run with `npm test` (Firebase emulators).
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
} from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const HASH = "a".repeat(64);
const CODE = "Abcdefghijklmnopqrstuvwxyz012345_-AB";

let env;

const google = (uid, email = `${uid}@asso.example`) =>
  env.authenticatedContext(uid, {
    email,
    email_verified: true,
    firebase: { sign_in_provider: "google.com" },
  });
const portal = (uid = "vol1") =>
  env.authenticatedContext(uid, {
    email: `${uid}@mail.example`,
    email_verified: true,
    firebase: { sign_in_provider: "password" },
  });
const path = (p) => `orgs/${ORG}/${p}`;

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore(), ctx.storage()));
}

async function setSetting(key, value) {
  await seed((db) => setDoc(doc(db, path(`institutionSettings/${key}`)), { value, lastModified: Date.now() }));
}

function request(uid, type, email, extra = {}) {
  return { uid, type, status: "PENDING", requestedAt: serverTimestamp(), email, ...extra };
}

function claim(type, email, extra = {}) {
  return { type, status: "PENDING", requestedAt: serverTimestamp(), email, ...extra };
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
  await seed(async (db) => {
    await setDoc(doc(db, path("metadata/config")), { createdAt: Date.now() });
    await setDoc(doc(db, path("members/staff1")), { role: "member", email: "staff1@asso.example" });
    await setDoc(doc(db, path("volunteers/x")), { name: "Léa" });
    await setDoc(doc(db, path("volunteerPortal/vol1")), { firstName: "Léa" });
    await setDoc(doc(db, path("volunteerPortal/vol2")), { firstName: "Max" });
    await setDoc(doc(db, path("portalGoogleAccounts/g1")), {
      volunteerUid: "vol1", email: "g1@asso.example", linkedBy: "LINK_CODE", linkedAt: Date.now(),
    });
  });
  await setSetting("volunteer_portal_enabled", "true");
  await setSetting("volunteer_portal_google_enabled", "true");
  await setSetting("volunteer_portal_google_domains", "asso.example,asso-prod.example");
});

describe("volunteerPortal through a Google account", () => {
  test("linked google session reads its volunteer page", async () => {
    const db = google("g1").firestore();
    await assertSucceeds(getDoc(doc(db, path("volunteerPortal/vol1"))));
    await assertFails(getDoc(doc(db, path("volunteerPortal/vol2"))));
    await assertFails(getDocs(collection(db, path("volunteerPortal"))));
  });

  test("unlinked google session cannot read any page", async () => {
    const db = google("g2").firestore();
    await assertFails(getDoc(doc(db, path("volunteerPortal/vol1"))));
    await assertFails(getDoc(doc(db, path("volunteerPortal/g2"))));
  });

  test("domain outside the list is refused", async () => {
    await assertFails(getDoc(doc(google("g1", "g1@other.example").firestore(), path("volunteerPortal/vol1"))));
  });

  test("unverified google address is refused", async () => {
    const ctx = env.authenticatedContext("g1", {
      email: "g1@asso.example", email_verified: false, firebase: { sign_in_provider: "google.com" },
    });
    await assertFails(getDoc(doc(ctx.firestore(), path("volunteerPortal/vol1"))));
  });

  test("domain removed from the list cuts access", async () => {
    await setSetting("volunteer_portal_google_domains", "asso-prod.example");
    await assertFails(getDoc(doc(google("g1").firestore(), path("volunteerPortal/vol1"))));
    await assertSucceeds(getDoc(doc(google("g1", "g1@asso-prod.example").firestore(), path("volunteerPortal/vol1"))));
  });

  test("gmail.com and googlemail.com are refused even when listed", async () => {
    await setSetting("volunteer_portal_google_domains", "gmail.com,googlemail.com");
    await assertFails(getDoc(doc(google("g1", "g1@gmail.com").firestore(), path("volunteerPortal/vol1"))));
    await assertFails(getDoc(doc(google("g1", "g1@googlemail.com").firestore(), path("volunteerPortal/vol1"))));
    await assertFails(setDoc(doc(google("g9", "g9@gmail.com").firestore(), path("portalGoogleClaims/g9")), claim("ADMIN_MATCH", "g9@gmail.com")));
  });

  test("an empty domain list refuses everyone", async () => {
    await setSetting("volunteer_portal_google_domains", "");
    await assertFails(getDoc(doc(google("g1").firestore(), path("volunteerPortal/vol1"))));
  });

  test("option off or portal off cuts access", async () => {
    const read = () => getDoc(doc(google("g1").firestore(), path("volunteerPortal/vol1")));
    await setSetting("volunteer_portal_google_enabled", true);
    await assertSucceeds(read());
    await setSetting("volunteer_portal_google_enabled", "false");
    await assertFails(read());
    await seed((db) => setDoc(doc(db, path("institutionSettings/volunteer_portal_google_enabled")), {}));
    await assertFails(read());
    await setSetting("volunteer_portal_google_enabled", "true");
    await setSetting("volunteer_portal_enabled", "false");
    await assertFails(read());
  });
});

describe("portalRequests through a Google account", () => {
  const email = "g1@asso.example";
  const profile = () => request("vol1", "PROFILE", email, { firstName: "Léa", lastName: "Martin", phone: "+41 79 000 00 00" });

  test("google session sends PROFILE, QR_EMAIL, GOOGLE_UNLINK for its volunteer", async () => {
    const db = google("g1").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_PROFILE")), profile()));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")), request("vol1", "QR_EMAIL", email)));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_UNLINK")), request("vol1", "GOOGLE_UNLINK", email)));
    await assertSucceeds(getDoc(doc(db, path("portalRequests/vol1_PROFILE"))));
    await assertSucceeds(getDoc(doc(db, path("portalRequests/vol1_GOOGLE_LINK"))));
    await assertFails(getDoc(doc(db, path("portalRequests/vol2_PROFILE"))));
    await assertFails(getDoc(doc(db, path("portalRequests/g1_PROFILE"))));
    await assertFails(getDocs(collection(db, path("portalRequests"))));
  });

  test("but not CLAIM or GOOGLE_LINK", async () => {
    const db = google("g1").firestore();
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_CLAIM")), request("vol1", "CLAIM", email)));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_LINK")),
      request("vol1", "GOOGLE_LINK", email, { codeHash: HASH })));
  });

  test("google session cannot act for another volunteer or with another address", async () => {
    const db = google("g1").firestore();
    await assertFails(setDoc(doc(db, path("portalRequests/vol2_QR_EMAIL")), request("vol2", "QR_EMAIL", email)));
    await assertFails(setDoc(doc(db, path("portalRequests/g1_QR_EMAIL")), request("g1", "QR_EMAIL", email)));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_QR_EMAIL")),
      request("vol1", "QR_EMAIL", "vol1@mail.example")));
    await assertFails(setDoc(doc(google("g2").firestore(), path("portalRequests/vol1_QR_EMAIL")),
      request("vol1", "QR_EMAIL", "g2@asso.example")));
  });

  test("google session renewals are throttled like the others", async () => {
    const db = google("g1").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_UNLINK")), request("vol1", "GOOGLE_UNLINK", email)));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_UNLINK")), request("vol1", "GOOGLE_UNLINK", email)));
    await seed((sdb) => setDoc(doc(sdb, path("portalRequests/vol1_GOOGLE_UNLINK")), {
      uid: "vol1", type: "GOOGLE_UNLINK", status: "DONE", email,
      requestedAt: Timestamp.fromMillis(Date.now() - 2 * 60 * 1000),
    }));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_UNLINK")), request("vol1", "GOOGLE_UNLINK", email)));
  });

  test("closed when the Google option is off", async () => {
    await setSetting("volunteer_portal_google_enabled", "false");
    await assertFails(setDoc(doc(google("g1").firestore(), path("portalRequests/vol1_QR_EMAIL")),
      request("vol1", "QR_EMAIL", email)));
  });

  test("password session sends GOOGLE_LINK with a 64-hex codeHash", async () => {
    const db = portal("vol1").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_LINK")),
      request("vol1", "GOOGLE_LINK", "vol1@mail.example", { codeHash: HASH })));
    await assertSucceeds(getDoc(doc(db, path("portalRequests/vol1_GOOGLE_LINK"))));
    await assertSucceeds(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_UNLINK")),
      request("vol1", "GOOGLE_UNLINK", "vol1@mail.example")));
  });

  test("rejects a bad codeHash", async () => {
    const db = portal("vol1").firestore();
    const link = (extra) => setDoc(doc(db, path("portalRequests/vol1_GOOGLE_LINK")),
      request("vol1", "GOOGLE_LINK", "vol1@mail.example", extra));
    await assertFails(link({}));
    await assertFails(link({ codeHash: "A".repeat(64) }));
    await assertFails(link({ codeHash: "a".repeat(63) }));
    await assertFails(link({ codeHash: HASH, expiresAt: Date.now() }));
    await assertFails(setDoc(doc(db, path("portalRequests/vol1_GOOGLE_UNLINK")),
      request("vol1", "GOOGLE_UNLINK", "vol1@mail.example", { codeHash: HASH })));
  });
});

describe("portalGoogleClaims", () => {
  const email = "g2@asso.example";

  test("claims: owner creates ADMIN_MATCH with its own email", async () => {
    const db = google("g2").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
    await assertSucceeds(getDoc(doc(db, path("portalGoogleClaims/g2"))));
  });

  test("owner creates LINK_CODE with a valid code only", async () => {
    const db = google("g2").firestore();
    const send = (extra) => setDoc(doc(db, path("portalGoogleClaims/g2")), claim("LINK_CODE", email, extra));
    await assertFails(send({}));
    await assertFails(send({ code: "short" }));
    await assertFails(send({ code: "x".repeat(32) + "<" }));
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email, { code: CODE })));
    await assertFails(send({ code: CODE, volunteerUid: "vol2" }));
    await assertSucceeds(send({ code: CODE }));
  });

  test("other email refused", async () => {
    await assertFails(setDoc(doc(google("g2").firestore(), path("portalGoogleClaims/g2")),
      claim("ADMIN_MATCH", "vol2@mail.example")));
  });

  test("other uid refused", async () => {
    await assertFails(setDoc(doc(google("g2").firestore(), path("portalGoogleClaims/g3")),
      claim("ADMIN_MATCH", email)));
    await seed((db) => setDoc(doc(db, path("portalGoogleClaims/g3")), claim("ADMIN_MATCH", "g3@asso.example")));
    await assertFails(getDoc(doc(google("g2").firestore(), path("portalGoogleClaims/g3"))));
  });

  test("list refused", async () => {
    await assertFails(getDocs(collection(google("g2").firestore(), path("portalGoogleClaims"))));
  });

  test("bad status, type, time, domain or password session refused", async () => {
    const db = google("g2").firestore();
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email, { status: "DONE" })));
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN", email)));
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")),
      claim("ADMIN_MATCH", email, { requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000) })));
    await assertFails(setDoc(doc(google("g2", "g2@other.example").firestore(), path("portalGoogleClaims/g2")),
      claim("ADMIN_MATCH", "g2@other.example")));
    await assertFails(setDoc(doc(portal("g2").firestore(), path("portalGoogleClaims/g2")),
      claim("ADMIN_MATCH", "g2@mail.example")));
    await setSetting("volunteer_portal_google_enabled", "false");
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
  });

  test("renewal before 1 minute refused", async () => {
    const db = google("g2").firestore();
    await assertSucceeds(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
    await seed((sdb) => setDoc(doc(sdb, path("portalGoogleClaims/g2")), {
      type: "ADMIN_MATCH", status: "REJECTED", email, requestedAt: Timestamp.fromMillis(Date.now() - 2 * 60 * 1000),
    }));
    await assertSucceeds(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("LINK_CODE", email, { code: CODE })));
  });

  test("renewal refused while PROCESSING younger than 5 minutes", async () => {
    const db = google("g2").firestore();
    const processing = (processingAt) => seed((sdb) => setDoc(doc(sdb, path("portalGoogleClaims/g2")), {
      type: "ADMIN_MATCH", status: "PROCESSING", email,
      requestedAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000), processingAt,
    }));
    await processing(Date.now() - 60 * 1000);
    await assertFails(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
    await processing(Date.now() - 6 * 60 * 1000);
    await assertSucceeds(setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
  });

  test("members can list and process claims", async () => {
    await seed((db) => setDoc(doc(db, path("portalGoogleClaims/g2")), claim("ADMIN_MATCH", email)));
    const db = google("staff1").firestore();
    await assertSucceeds(getDocs(collection(db, path("portalGoogleClaims"))));
    await assertSucceeds(setDoc(doc(db, path("portalGoogleClaims/g2")), { status: "DONE" }, { merge: true }));
  });
});

describe("portalGoogleAccounts", () => {
  test("owner reads its own alias only", async () => {
    await assertSucceeds(getDoc(doc(google("g1").firestore(), path("portalGoogleAccounts/g1"))));
    await assertSucceeds(getDoc(doc(google("g2").firestore(), path("portalGoogleAccounts/g2"))));
    await assertFails(getDoc(doc(google("g2").firestore(), path("portalGoogleAccounts/g1"))));
    await assertFails(getDoc(doc(portal("g1").firestore(), path("portalGoogleAccounts/g1"))));
    await assertFails(getDocs(collection(google("g1").firestore(), path("portalGoogleAccounts"))));
  });

  test("nobody but members writes portalGoogleAccounts", async () => {
    const alias = { volunteerUid: "vol2", email: "g2@asso.example", linkedBy: "ADMIN_MATCH", linkedAt: Date.now() };
    await assertFails(setDoc(doc(google("g2").firestore(), path("portalGoogleAccounts/g2")), alias));
    await assertFails(setDoc(doc(google("g1").firestore(), path("portalGoogleAccounts/g1")), alias));
    await assertFails(setDoc(doc(portal("vol2").firestore(), path("portalGoogleAccounts/g2")), alias));
    await assertSucceeds(setDoc(doc(google("staff1").firestore(), path("portalGoogleAccounts/g2")), alias));
  });
});

describe("staff rights", () => {
  test("staff google account linked as volunteer keeps member rights", async () => {
    await seed((db) => setDoc(doc(db, path("portalGoogleAccounts/staff1")), {
      volunteerUid: "vol1", email: "staff1@asso.example", linkedBy: "ADMIN_MATCH", linkedAt: Date.now(),
    }));
    const db = google("staff1").firestore();
    await assertSucceeds(getDoc(doc(db, path("volunteerPortal/vol1"))));
    await assertSucceeds(getDoc(doc(db, path("volunteers/x"))));
    await assertSucceeds(getDocs(collection(db, path("volunteers"))));
  });

  test("linked google account without member doc gets no staff rights", async () => {
    const db = google("g1").firestore();
    await assertFails(getDoc(doc(db, path("volunteers/x"))));
    await assertFails(getDocs(collection(db, path("volunteerPortal"))));
    await assertFails(setDoc(doc(db, path("members/g1")), { role: "admin" }));
    await assertFails(setDoc(doc(db, path("members/g1")), { role: "member" }));
    await assertFails(setDoc(doc(db, path("metadata/config")), { createdAt: Date.now() }));
    await assertFails(setDoc(doc(db, path("institutionSettings/volunteer_portal_google_domains")), { value: "evil.example" }));
    await assertFails(setDoc(doc(db, path("volunteerPortal/vol1")), { firstName: "Hacked" }));
  });
});

describe("storage", () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46]);
  const meta = { contentType: "image/jpeg" };

  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`), jpeg, meta);
      await uploadBytes(ref(ctx.storage(), `orgs/${ORG}/portalPhotos/vol2.jpg`), jpeg, meta);
    });
  });

  test("google session reads its portal photo", async () => {
    await assertSucceeds(getBytes(ref(google("g1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
  });

  test("not another one", async () => {
    await assertFails(getBytes(ref(google("g1").storage(), `orgs/${ORG}/portalPhotos/vol2.jpg`)));
    await assertFails(getBytes(ref(google("g2").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await assertFails(getBytes(ref(google("g2").storage(), `orgs/${ORG}/portalPhotos/.jpg`)));
    await assertFails(getBytes(ref(portal("g1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await assertFails(uploadBytes(ref(google("g1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`), jpeg, meta));
  });

  // Documented trade-off: Storage may read only 2 Firestore documents, so it checks the alias alone.
  test("still reads it with the option off while the alias exists", async () => {
    await setSetting("volunteer_portal_google_enabled", "false");
    await assertSucceeds(getBytes(ref(google("g1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await seed((db) => deleteDoc(doc(db, path("portalGoogleAccounts/g1"))));
    await assertFails(getBytes(ref(google("g1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
  });

  test("staff google account with no alias still reads photos", async () => {
    await assertSucceeds(getBytes(ref(google("staff1").storage(), `orgs/${ORG}/portalPhotos/vol1.jpg`)));
    await assertSucceeds(getBytes(ref(google("staff1").storage(), `orgs/${ORG}/portalPhotos/vol2.jpg`)));
  });

  test("google session uploads a new photo under its volunteer's name only", async () => {
    const upload = (ctx, name, m = meta) =>
      uploadBytes(ref(ctx.storage(), `orgs/${ORG}/portalPhotoUploads/${name}`), jpeg, m);
    await assertFails(upload(google("g1"), "vol1.jpg"));
    await setSetting("profile_photos_enabled", "true");
    await assertSucceeds(upload(google("g1"), "vol1.jpg"));
    await assertFails(upload(google("g1"), "vol2.jpg"));
    await assertFails(upload(google("g1"), "g1.jpg"));
    await assertFails(upload(google("g2"), "vol1.jpg"));
    await assertFails(upload(google("g2"), "g2.jpg"));
    await assertFails(upload(google("g1"), "vol1.jpg", { contentType: "text/html" }));
    await assertFails(getBytes(ref(google("g1").storage(), `orgs/${ORG}/portalPhotoUploads/vol1.jpg`)));
    await setSetting("profile_photos_enabled", "false");
    await assertFails(upload(google("g1"), "vol1.jpg"));
  });
});
