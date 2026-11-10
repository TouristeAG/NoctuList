// Seeds the running emulators with demo data so every public page can be previewed locally:
//   node seed.mjs            (emulators started with `npm run preview`)
// Only ever talks to the demo project on 127.0.0.1 — never to a real Firebase project.
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, setDoc } from "firebase/firestore";
import { pathToFileURL } from "node:url";

export const PROJECT_ID = "demo-noctulist";
export const ORG = "demo-org";
const HOST = "127.0.0.1";
const DAY = 24 * 60 * 60 * 1000;

const now = Date.now();

async function createAuthUser(email, password, emailVerified) {
  const base = `http://${HOST}:9099/identitytoolkit.googleapis.com/v1`;
  let res = await fetch(`${base}/accounts:signUp?key=fake-api-key`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  let body = await res.json();
  if (!res.ok) {
    if (body?.error?.message !== "EMAIL_EXISTS") throw new Error(JSON.stringify(body));
    res = await fetch(`${base}/accounts:signInWithPassword?key=fake-api-key`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, returnSecureToken: true }),
    });
    body = await res.json();
  }
  if (emailVerified) {
    await fetch(`http://${HOST}:9099/emulator/v1/projects/${PROJECT_ID}/accounts/${body.localId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
      body: JSON.stringify({ emailVerified: true }),
    }).catch(() => {});
    // The emulator's admin endpoint: projects/{p}/accounts:update with a fake owner token.
    await fetch(`http://${HOST}:9099/identitytoolkit.googleapis.com/v1/projects/${PROJECT_ID}/accounts:update`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
      body: JSON.stringify({ localId: body.localId, emailVerified: true }),
    });
  }
  return body.localId;
}

export async function seed() {
  const env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { host: HOST, port: 8080 },
  });
  await env.clearFirestore();
  const portalUid = await createAuthUser("lea.demo@example.org", "Demo-Passw0rd!", true);

  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const org = (path) => doc(db, `orgs/${ORG}/${path}`);
    await setDoc(org("metadata/config"), { createdAt: now });
    await setDoc(org("institutionSettings/volunteer_portal_enabled"), { value: "true", lastModified: now });
    await setDoc(org("institutionSettings/profile_photos_enabled"), { value: "true", lastModified: now });

    await setDoc(org("guestForms/demoGuestForm0001"), {
      formId: "demoGuestForm0001",
      status: "OPEN",
      expiresAtMillis: now + 7 * DAY,
      venueName: "Le Terreau",
      eventName: "Nuit Mist",
      eventDateMillis: now + 3 * DAY,
      artistName: "Les Hiboux",
      maxGuests: 6,
      offeredAccessIds: JSON.stringify([
        { id: "backstage", name: "Backstage", maxRequests: 2 },
        { id: "photo", name: "Photo pit", maxRequests: 0 },
      ]),
      lastModified: now,
    });

    await setDoc(org("photoRequests/demoPhotoReq0001"), {
      requestId: "demoPhotoReq0001",
      kind: "volunteers",
      entityId: "volunteer-demo",
      displayName: "Léa M.",
      status: "OPEN",
      photoPath: "",
      uploadedAt: 0,
      createdAt: now,
      expiresAtMillis: now + 7 * DAY,
      lastModified: now,
    });

    await setDoc(org("volunteerSignupForms/demoSignupForm0001"), {
      formId: "demoSignupForm0001",
      status: "OPEN",
      expiresAtMillis: now + 7 * DAY,
      photosEnabled: true,
      associationName: "Association Démo",
      createdAt: now,
      lastModified: now,
    });

    await setDoc(org("portalBranding/default"), {
      associationName: "Association Démo",
      logoDataUri: "",
      lastModified: now,
    });

    const shift = (daysAgo, jobTypeName, venueName, shiftTime) => ({
      date: now - daysAgo * DAY,
      jobTypeName,
      venueName,
      shiftTime,
    });
    const payload = {
      firstName: "Léa",
      lastName: "MARTIN",
      email: "lea.demo@example.org",
      phone: "+41 79 123 45 67",
      dateOfBirth: "1998-04-12",
      gender: "FEMALE",
      hasPhoto: false,
      photoVersion: "",
      rank: "GALAXIE",
      active: true,
      totalShifts: 27,
      lastShiftAt: now - 4 * DAY,
      shiftsTotal: 25,
      shifts: [
        shift(4, "Bar", "Le Terreau", "BEFORE_MIDNIGHT"),
        shift(9, "Entrée", "Groove", "AFTER_MIDNIGHT"),
        shift(16, "Bar", "Le Terreau", "AFTER_MIDNIGHT"),
        shift(23, "Montage", "Groove", ""),
        shift(31, "Vestiaire", "Le Terreau", "BEFORE_MIDNIGHT"),
        shift(45, "Bar", "Groove", "AFTER_MIDNIGHT"),
        shift(60, "Réunion", "", ""),
        shift(74, "Bar", "Le Terreau", "BEFORE_MIDNIGHT"),
        shift(88, "Lumière", "Groove", ""),
        shift(102, "Bar", "Le Terreau", "AFTER_MIDNIGHT"),
      ],
      upcomingShifts: [
        { date: now + 2 * DAY, jobTypeName: "Bar", venueName: "Le Terreau", shiftTime: "BEFORE_MIDNIGHT" },
        { date: now + 9 * DAY, jobTypeName: "Entrée", venueName: "Groove", shiftTime: "AFTER_MIDNIGHT" },
      ],
      benefits: [
        {
          rank: "GALAXIE", description: "Galaxie, 3 shifts ce mois", freeEntry: true, friendInvitation: true,
          inviteCount: 1, drinkTokens: 2, barDiscount: 20, guestListAccess: true, validUntil: now + 21 * DAY,
          futureEntriesRemaining: null, futureEntryInvites: null,
        },
        {
          rank: "NOVA", description: "Nova, shift du mois", freeEntry: true, friendInvitation: false,
          inviteCount: 0, drinkTokens: 1, barDiscount: 0, guestListAccess: true, validUntil: now + 10 * DAY,
          futureEntriesRemaining: 2, futureEntryInvites: 1,
        },
      ],
      summary: {
        rank: "GALAXIE", description: "", freeEntry: true, friendInvitation: true, inviteCount: 1,
        drinkTokens: 2, barDiscount: 20, guestListAccess: true, validUntil: now + 21 * DAY,
        futureEntriesRemaining: 2, futureEntryInvites: 1,
      },
      uniqueEntriesRemaining: 2,
      wallet: {
        balance: 34.5,
        currency: "CHF",
        transfers: [
          { at: now - 1 * DAY, type: "POS_SALE", amount: -6.5, label: "2x Bière", venue: "Le Terreau" },
          { at: now - 4 * DAY, type: "SHIFT_CREDIT", amount: 15, label: "Bar", venue: "" },
          { at: now - 9 * DAY, type: "SHIFT_CREDIT", amount: 15, label: "Entrée", venue: "" },
          { at: now - 12 * DAY, type: "POS_SALE", amount: -4, label: "1x T-shirt", venue: "Groove" },
          { at: now - 16 * DAY, type: "MANUAL_ADJUSTMENT", amount: 10, label: "Bonus montage", venue: "" },
        ],
      },
      qrEmailAllowed: true,
      mustSetPassword: false,
      association: "Association Démo",
    };
    await setDoc(org(`volunteerPortal/${portalUid}`), {
      schema: 1,
      payload: JSON.stringify(payload),
      contentHash: "demo",
      updatedAt: now - 3 * 60 * 1000,
    });
  });
  await env.cleanup();
  return { portalUid };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  seed()
    .then(({ portalUid }) => {
      console.log("Seeded demo-org. Portal login: lea.demo@example.org / Demo-Passw0rd!  uid", portalUid);
      console.log("Guest form   http://127.0.0.1:5002/g/demo-org/demoGuestForm0001");
      console.log("Photo        http://127.0.0.1:5002/p/demo-org/demoPhotoReq0001");
      console.log("Signup       http://127.0.0.1:5002/s/demo-org/demoSignupForm0001");
      console.log("Portal       http://127.0.0.1:5002/v/demo-org");
    })
    .catch((error) => {
      console.error(error);
      process.exit(1);
    });
}
