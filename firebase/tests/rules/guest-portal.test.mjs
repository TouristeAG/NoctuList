// Security rules for the guest spaces (permanent guests and temporary guest groups) — run with `npm test`.
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  collection,
  doc,
  getDoc,
  getDocs,
  setDoc,
  deleteDoc,
} from "firebase/firestore";
import { ref, uploadBytes } from "firebase/storage";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const ORG = "org-a";
const HOUR = 60 * 60 * 1000;

let env;

const portal = (uid = "g1", verified = true) =>
  env.authenticatedContext(uid, {
    email: `${uid}@mail.example`,
    email_verified: verified,
    firebase: { sign_in_provider: "password" },
  });
const google = (uid, email = `${uid}@asso.example`) =>
  env.authenticatedContext(uid, {
    email,
    email_verified: true,
    firebase: { sign_in_provider: "google.com" },
  });
const staff = (uid = "staff1") =>
  env.authenticatedContext(uid, {
    email: `${uid}@team.example`,
    email_verified: true,
    firebase: { sign_in_provider: "google.com" },
  });
const anon = () => env.unauthenticatedContext();
const path = (p) => `orgs/${ORG}/${p}`;

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore(), ctx.storage()));
}

async function setSetting(key, value) {
  await seed((db) => setDoc(doc(db, path(`institutionSettings/${key}`)), { value, lastModified: Date.now() }));
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
    await setDoc(doc(db, path("members/staff1")), { role: "member", email: "staff1@team.example" });
    await setDoc(doc(db, path("guestPortal/g1")), { name: "Ana" });
    await setDoc(doc(db, path("guestPortal/g2")), { name: "Bob" });
    await setDoc(doc(db, path("guestPortalQr/g1")), { schema: 1, payload: "n1", name: "Ana" });
    await setDoc(doc(db, path("guestPortalQr/g2")), { schema: 1, payload: "n2", name: "Bob" });
    await setDoc(doc(db, path("temporaryGuestPortal/t1")), { groupName: "Band", expiresAtMillis: Date.now() + HOUR });
    await setDoc(doc(db, path("temporaryGuestPortal/t2")), { groupName: "Crew", expiresAtMillis: Date.now() + HOUR });
    await setDoc(doc(db, path("temporaryGuestPortal/tOld")), { groupName: "Old", expiresAtMillis: Date.now() - HOUR });
    await setDoc(doc(db, path("temporaryGuestPortalQr/t1")), { schema: 1, items: { k1: { payload: "p", name: "X" } } });
    await setDoc(doc(db, path("temporaryGuestPortalQr/t2")), { schema: 1, items: {} });
    await setDoc(doc(db, path("temporaryGuestPortalQr/tOld")), { schema: 1, items: {} });
    await setDoc(doc(db, path("portalGoogleAccounts/gg1")), {
      volunteerUid: "g1", email: "gg1@asso.example", linkedBy: "LINK_CODE", linkedAt: Date.now(),
    });
  });
  await setSetting("volunteer_portal_enabled", "true");
  await setSetting("guest_portal_enabled", "true");
  await setSetting("temp_guest_portal_enabled", "true");
});

const ALL = [
  ["guestPortal", "g1", "g2"],
  ["guestPortalQr", "g1", "g2"],
  ["temporaryGuestPortal", "t1", "t2"],
  ["temporaryGuestPortalQr", "t1", "t2"],
];

describe("guest spaces: owner access", () => {
  for (const [col, mine, other] of ALL) {
    test(`${col}: owner reads own document only, never lists or writes`, async () => {
      const db = portal(mine).firestore();
      await assertSucceeds(getDoc(doc(db, path(`${col}/${mine}`))));
      await assertFails(getDoc(doc(db, path(`${col}/${other}`))));
      await assertFails(getDocs(collection(db, path(col))));
      await assertFails(setDoc(doc(db, path(`${col}/${mine}`)), { name: "hack" }));
      await assertFails(deleteDoc(doc(db, path(`${col}/${mine}`))));
      await assertFails(getDoc(doc(anon().firestore(), path(`${col}/${mine}`))));
    });

    test(`${col}: members keep full access`, async () => {
      const db = staff().firestore();
      await assertSucceeds(getDocs(collection(db, path(col))));
      await assertSucceeds(setDoc(doc(db, path(`${col}/${mine}`)), { name: "ok", expiresAtMillis: Date.now() + HOUR }));
    });
  }
});

describe("guest spaces: switches", () => {
  test("guest_portal_enabled off closes guestPortal and guestPortalQr", async () => {
    await setSetting("guest_portal_enabled", "false");
    const db = portal("g1").firestore();
    await assertFails(getDoc(doc(db, path("guestPortal/g1"))));
    await assertFails(getDoc(doc(db, path("guestPortalQr/g1"))));
    await assertSucceeds(getDoc(doc(portal("t1").firestore(), path("temporaryGuestPortal/t1"))));
  });

  test("guest_portal_enabled absent closes guestPortal", async () => {
    await seed((db) => deleteDoc(doc(db, path("institutionSettings/guest_portal_enabled"))));
    await assertFails(getDoc(doc(portal("g1").firestore(), path("guestPortal/g1"))));
  });

  test("temp_guest_portal_enabled off closes both temporary collections", async () => {
    await setSetting("temp_guest_portal_enabled", "false");
    const db = portal("t1").firestore();
    await assertFails(getDoc(doc(db, path("temporaryGuestPortal/t1"))));
    await assertFails(getDoc(doc(db, path("temporaryGuestPortalQr/t1"))));
    await assertSucceeds(getDoc(doc(portal("g1").firestore(), path("guestPortal/g1"))));
  });

  test("volunteer portal off closes all four", async () => {
    await setSetting("volunteer_portal_enabled", "false");
    for (const [col, mine] of ALL) {
      await assertFails(getDoc(doc(portal(mine).firestore(), path(`${col}/${mine}`))));
    }
  });

  test("QR switch off closes both QR collections only", async () => {
    await setSetting("volunteer_portal_qr_enabled", "false");
    await assertFails(getDoc(doc(portal("g1").firestore(), path("guestPortalQr/g1"))));
    await assertFails(getDoc(doc(portal("t1").firestore(), path("temporaryGuestPortalQr/t1"))));
    await assertSucceeds(getDoc(doc(portal("g1").firestore(), path("guestPortal/g1"))));
    await assertSucceeds(getDoc(doc(portal("t1").firestore(), path("temporaryGuestPortal/t1"))));
  });
});

describe("guest spaces: unverified password sessions", () => {
  test("accepted on both temporary collections", async () => {
    const db = portal("t1", false).firestore();
    await assertSucceeds(getDoc(doc(db, path("temporaryGuestPortal/t1"))));
    await assertSucceeds(getDoc(doc(db, path("temporaryGuestPortalQr/t1"))));
    await assertFails(getDoc(doc(db, path("temporaryGuestPortal/t2"))));
  });

  test("refused on the permanent guest collections", async () => {
    const db = portal("g1", false).firestore();
    await assertFails(getDoc(doc(db, path("guestPortal/g1"))));
    await assertFails(getDoc(doc(db, path("guestPortalQr/g1"))));
  });

  test("Google sessions never read the temporary collections", async () => {
    const db = google("t1").firestore();
    await assertFails(getDoc(doc(db, path("temporaryGuestPortal/t1"))));
    await assertFails(getDoc(doc(db, path("temporaryGuestPortalQr/t1"))));
  });
});

describe("temporary guest pages expire", () => {
  test("expired page and its QR are refused", async () => {
    const db = portal("tOld", false).firestore();
    await assertFails(getDoc(doc(db, path("temporaryGuestPortal/tOld"))));
    await assertFails(getDoc(doc(db, path("temporaryGuestPortalQr/tOld"))));
  });
});

describe("guest spaces through a linked Google account", () => {
  beforeEach(async () => {
    await setSetting("volunteer_portal_google_enabled", "true");
    await setSetting("volunteer_portal_google_domains", "asso.example");
  });

  test("alias reads guestPortal and guestPortalQr of its portal uid", async () => {
    const db = google("gg1").firestore();
    await assertSucceeds(getDoc(doc(db, path("guestPortal/g1"))));
    await assertSucceeds(getDoc(doc(db, path("guestPortalQr/g1"))));
    await assertFails(getDoc(doc(db, path("guestPortal/g2"))));
    await assertFails(getDocs(collection(db, path("guestPortal"))));
  });

  test("unlinked Google account reads nothing", async () => {
    await assertFails(getDoc(doc(google("gg9").firestore(), path("guestPortal/g1"))));
  });

  test("guest switch off closes the alias too", async () => {
    await setSetting("guest_portal_enabled", "false");
    await assertFails(getDoc(doc(google("gg1").firestore(), path("guestPortal/g1"))));
  });
});

describe("storage: guest photo upload", () => {
  const upload = (ctx, name) =>
    uploadBytes(ref(ctx.storage(), `orgs/${ORG}/portalPhotoUploads/${name}.jpg`),
      new Uint8Array([0xff, 0xd8, 0xff]), { contentType: "image/jpeg" });

  beforeEach(async () => {
    await setSetting("profile_photos_enabled", "true");
  });

  test("a permanent guest uploads its own photo", async () => {
    await assertSucceeds(upload(portal("g1"), "g1"));
    await assertFails(upload(portal("g1"), "g2"));
  });

  test("a linked Google guest uploads through its alias", async () => {
    await assertSucceeds(upload(google("gg1"), "g1"));
  });
});

describe("read budget with every setting document present", () => {
  const readAll = async (db) => {
    await assertSucceeds(getDoc(doc(db, path("guestPortal/g1"))));
    await assertSucceeds(getDoc(doc(db, path("guestPortalQr/g1"))));
    await assertSucceeds(getDoc(doc(db, path("volunteerPortalQr/g1"))));
  };

  beforeEach(async () => {
    await seed((db) => setDoc(doc(db, path("volunteerPortalQr/g1")), { schema: 1, payload: "V", name: "Ana" }));
    await setSetting("volunteer_portal_google_enabled", "true");
    await setSetting("volunteer_portal_qr_enabled", "true");
  });

  test("domain mode, any_account and QR settings explicit", async () => {
    await setSetting("volunteer_portal_google_any_account", "false");
    await setSetting("volunteer_portal_google_domains", "asso.example");
    await readAll(google("gg1").firestore());
  });

  test("any-account mode, every setting explicit", async () => {
    await setSetting("volunteer_portal_google_any_account", "true");
    await setSetting("volunteer_portal_google_domains", "");
    await readAll(google("gg1", "gg1@gmail.com").firestore());
  });
});
