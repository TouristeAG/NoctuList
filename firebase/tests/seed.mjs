// Seeds the running emulators with demo data so every public page can be previewed locally:
//   node seed.mjs            (emulators started with `npm run preview`)
//   node seed.mjs --schema1  (old document shape, for the compatibility check)
// Only ever talks to the demo project on 127.0.0.1 — never to a real Firebase project.
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, setDoc } from "firebase/firestore";
import { pathToFileURL } from "node:url";

export const PROJECT_ID = "demo-noctulist";
export const ORG = "demo-org";
const HOST = "127.0.0.1";
// Another preview may hold the default ports: AUTH_PORT and FIRESTORE_PORT point the seed elsewhere.
const AUTH_PORT = process.env.AUTH_PORT || "9099";
const FIRESTORE_PORT = Number(process.env.FIRESTORE_PORT || 8080);
const DAY = 24 * 60 * 60 * 1000;

const now = Date.now();
// Google sign-in demo: the association's Workspace domain and the address an admin entered for Jean.
export const GOOGLE_DOMAIN = "asso.example";
export const JEAN_GOOGLE = `jean@${GOOGLE_DOMAIN}`;

async function createAuthUser(email, password, emailVerified) {
  const base = `http://${HOST}:${AUTH_PORT}/identitytoolkit.googleapis.com/v1`;
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
    await fetch(`http://${HOST}:${AUTH_PORT}/emulator/v1/projects/${PROJECT_ID}/accounts/${body.localId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
      body: JSON.stringify({ emailVerified: true }),
    }).catch(() => {});
    // The emulator's admin endpoint: projects/{p}/accounts:update with a fake owner token.
    await fetch(`http://${HOST}:${AUTH_PORT}/identitytoolkit.googleapis.com/v1/projects/${PROJECT_ID}/accounts:update`, {
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
    firestore: { host: HOST, port: FIRESTORE_PORT },
  });
  await env.clearFirestore();
  const portalUid = await createAuthUser("lea.demo@example.org", "Demo-Passw0rd!", true);
  const jeanUid = await createAuthUser("jean.demo@example.org", "Demo-Passw0rd!", true);
  // Guest spaces: Nina is a permanent guest only; the manager account is the address of two
  // artist groups and, like an account the app creates, has never verified it.
  const ninaUid = await createAuthUser("nina.demo@example.org", "Demo-Passw0rd!", true);
  const managerUid = await createAuthUser("manager.demo@example.org", "Demo-Passw0rd!", false);
  // A volunteer whose page only has an initial for the last name: the certificate asks for it in full.
  const paulUid = await createAuthUser("paul.demo@example.org", "Demo-Passw0rd!", true);

  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const org = (path) => doc(db, `orgs/${ORG}/${path}`);
    await setDoc(org("metadata/config"), { createdAt: now });
    await setDoc(org("institutionSettings/volunteer_portal_enabled"), { value: "true", lastModified: now });
    await setDoc(org("institutionSettings/profile_photos_enabled"), { value: "true", lastModified: now });
    await setDoc(org("institutionSettings/volunteer_portal_google_enabled"), { value: "true", lastModified: now });
    await setDoc(org("institutionSettings/volunteer_portal_google_domains"), { value: GOOGLE_DOMAIN, lastModified: now });
    await setDoc(org("institutionSettings/volunteer_portal_certificate_enabled"), { value: "true", lastModified: now });
    await setDoc(org("institutionSettings/guest_portal_enabled"), { value: "true", lastModified: now });
    await setDoc(org("institutionSettings/temp_guest_portal_enabled"), { value: "true", lastModified: now });

    // Roster entries the Google link is decided on: Léa links her own account from the portal,
    // Jean has an address an admin entered (signing in with it links him).
    const volunteer = (id, name, lastName, email, uid, googleEmail, linkedBy) => setDoc(org(`volunteers/${id}`), {
      id, name, lastNameAbbreviation: lastName, email, phone: "079 000 00 00", isAdmin: false, lastModified: now,
      portalEnabled: true, portalUid: uid, portalGoogleEmail: googleEmail, portalGoogleUid: "", portalGoogleLinkedBy: linkedBy,
    });
    await volunteer("volunteerdemolea0001x", "Léa", "MARTIN", "lea.demo@example.org", portalUid, "", "");
    await volunteer("volunteerdemojean001x", "Jean", "DUPONT", "jean.demo@example.org", jeanUid, JEAN_GOOGLE, "ADMIN");

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

    await setDoc(org("volunteerSignupForms/demoGuestSignup0001"), {
      formId: "demoGuestSignup0001",
      status: "OPEN",
      holderType: "GUEST",
      venueName: "Le Terreau",
      expiresAtMillis: now + 7 * DAY,
      photosEnabled: true,
      associationName: "Association Démo",
      createdAt: now,
      lastModified: now,
    });

    await setDoc(org("portalBranding/default"), {
      associationName: "Association Démo",
      logoDataUri: "",
      googleLogin: true,
      lastModified: now,
    });

    const shift = (daysAgo, jobTypeName, venueName, shiftTime) => ({
      date: now - daysAgo * DAY,
      jobTypeName,
      venueName,
      shiftTime,
    });
    const base = {
      firstName: "Léa",
      lastName: "MARTIN",
      email: "lea.demo@example.org",
      phone: "+41 79 123 45 67",
      dateOfBirth: "1998-04-12",
      gender: "FEMALE",
      hasPhoto: false,
      photoVersion: "",
    };
    const wallet = {
      balance: 34.5,
      currency: "CHF",
      transfers: [
        { at: now - 1 * DAY, type: "POS_SALE", amount: -6.5, label: "2x Bière", venue: "Le Terreau" },
        { at: now - 4 * DAY, type: "SHIFT_CREDIT", amount: 15, label: "Bar", venue: "" },
        { at: now - 9 * DAY, type: "SHIFT_CREDIT", amount: 15, label: "Entrée", venue: "" },
        { at: now - 12 * DAY, type: "POS_SALE", amount: -4, label: "1x T-shirt", venue: "Groove" },
        { at: now - 16 * DAY, type: "MANUAL_ADJUSTMENT", amount: 10, label: "Bonus montage", venue: "" },
      ],
    };
    // Job types of the certificate, with their days (YYYYMMDD), as the devices publish them.
    const dayInt = (daysAgo) => {
      const d = new Date(now - daysAgo * DAY);
      return d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate();
    };
    const certificate = {
      types: [
        { name: "Comité", category: "MANDATE", days: [dayInt(400)] },
        { name: "Bar", category: "SHIFT", days: [dayInt(45), dayInt(16), dayInt(4)] },
        { name: "Entrée", category: "SHIFT", days: [dayInt(9)] },
        { name: "Montage", category: "SHIFT", days: [dayInt(23)] },
        { name: "Affiches", category: "GRAPHIC_EVENT", days: [dayInt(100)] },
        { name: "Assemblée", category: "MEETING", days: [dayInt(30)] },
      ],
    };
    const tail = {
      certificate,
      wallet, qrEmailAllowed: true, mustSetPassword: false, association: "Association Démo",
      googleEmail: "", googleLinkedBy: "", googleLinkAvailable: true,
    };
    const benefit = (description, fields) => ({
      description, freeEntry: false, friendInvitation: false, inviteCount: 0, drinkTokens: 0, barDiscount: 0,
      guestListAccess: false, validUntil: null, futureEntriesRemaining: null, futureEntryInvites: null, ...fields,
    });
    const past = [
      shift(4, "Bar", "Le Terreau", "BEFORE_MIDNIGHT"),
      shift(9, "Entrée", "Groove", "AFTER_MIDNIGHT"),
      shift(16, "Bar", "Le Terreau", "AFTER_MIDNIGHT"),
      shift(23, "Montage", "Groove", ""),
      shift(31, "Vestiaire", "Le Terreau", "BEFORE_MIDNIGHT"),
      shift(45, "Bar", "Groove", "AFTER_MIDNIGHT"),
    ];
    let payload;
    if (process.argv.includes("--schema1")) {
      // Old shape (rank, split lists), to check that unrepublished documents still render.
      payload = {
        ...base,
        rank: "GALAXIE",
        active: true,
        totalShifts: 27,
        lastShiftAt: now - 4 * DAY,
        shiftsTotal: 25,
        shifts: past,
        upcomingShifts: [
          { date: now + 2 * DAY, jobTypeName: "Bar", venueName: "Le Terreau", shiftTime: "BEFORE_MIDNIGHT" },
        ],
        benefits: [
          { rank: "GALAXIE", ...benefit("Galaxie, 3 shifts ce mois", {
            freeEntry: true, friendInvitation: true, inviteCount: 1, drinkTokens: 2, barDiscount: 20,
            guestListAccess: true, validUntil: now + 21 * DAY }) },
        ],
        summary: { rank: "GALAXIE", ...benefit("", {
          freeEntry: true, friendInvitation: true, inviteCount: 1, drinkTokens: 2, barDiscount: 20,
          guestListAccess: true, validUntil: now + 21 * DAY }) },
        uniqueEntriesRemaining: 2,
        ...tail,
      };
    } else {
      const monthly = benefit("3 shifts ce mois", {
        freeEntry: true, friendInvitation: true, inviteCount: 1, drinkTokens: 2, barDiscount: 20,
        guestListAccess: true, validUntil: now + 21 * DAY });
      const expiring = benefit("Shift du mois, 1 jeton offert", { drinkTokens: 1, validUntil: now + 1 * DAY });
      payload = {
        ...base,
        shifts: [
          {
            date: now + 1 * DAY, jobTypeName: "Bar", venueName: "Le Terreau", shiftTime: "BEFORE_MIDNIGHT",
            gains: [benefit("", { freeEntry: true, drinkTokens: 2 })],
          },
          ...past,
        ],
        lastShiftFloor: now - 4 * DAY,
        timeline: [
          { from: 0, active: false, summary: null, benefits: [], uniqueEntriesRemaining: null },
          {
            from: now - 20 * DAY, active: true, summary: { ...monthly, drinkTokens: 3 },
            benefits: [monthly, expiring], uniqueEntriesRemaining: 2,
          },
          // A later step, to show the page keeps the current one until it starts.
          {
            from: now + 40 * DAY, active: false, summary: null, benefits: [], uniqueEntriesRemaining: null,
          },
        ],
        ...tail,
      };
    }
    await setDoc(org(`volunteerPortal/${portalUid}`), {
      schema: payload.timeline ? 2 : 1,
      payload: JSON.stringify(payload),
      contentHash: "demo",
      updatedAt: now - 3 * 60 * 1000,
    });
    const jean = {
      ...payload, firstName: "Jean", lastName: "DUPONT", email: "jean.demo@example.org",
      googleEmail: JEAN_GOOGLE, googleLinkedBy: "ADMIN", googleLinkAvailable: false,
    };
    await setDoc(org(`volunteerPortal/${jeanUid}`), {
      schema: jean.timeline ? 2 : 1,
      payload: JSON.stringify(jean),
      contentHash: "demo",
      updatedAt: now - 3 * 60 * 1000,
    });
    const paul = { ...payload, firstName: "Paul", lastName: "D.", email: "paul.demo@example.org" };
    await setDoc(org(`volunteerPortal/${paulUid}`), {
      schema: paul.timeline ? 2 : 1,
      payload: JSON.stringify(paul),
      contentHash: "demo",
      updatedAt: now - 3 * 60 * 1000,
    });
    await setDoc(org(`volunteerPortalQr/${portalUid}`), { schema: 1, payload: "demoLeaVolunteerNano01", name: "Léa MARTIN" });

    // Permanent guest pages: Nina alone, and Léa, who is also a guest (three profiles in all).
    const guestPage = (firstName, lastName, email, extra = {}) => ({
      firstName, lastName, email, phone: "+41 78 555 12 34", hasPhoto: false, photoVersion: "",
      venueName: "Le Terreau", invitations: 2, barDiscountPercent: 15,
      wallet: { balance: 12, currency: "CHF", transfers: [
        { at: now - 2 * DAY, type: "POS_SALE", amount: -8, label: "2x Mate", venue: "Le Terreau" },
        { at: now - 6 * DAY, type: "MANUAL_ADJUSTMENT", amount: 20, label: "Crédit invité", venue: "" },
      ] },
      qrEmailAllowed: true, mustSetPassword: false, association: "Association Démo",
      googleEmail: "", googleLinkedBy: "", googleLinkAvailable: true, ...extra,
    });
    const publish = (collection, uid, payload, extra = {}) => setDoc(org(`${collection}/${uid}`), {
      schema: 1, payload: JSON.stringify(payload), contentHash: "demo", updatedAt: now - 5 * 60 * 1000, ...extra,
    });
    await publish("guestPortal", ninaUid, guestPage("Nina", "ROSSI", "nina.demo@example.org"));
    await setDoc(org(`guestPortalQr/${ninaUid}`), { schema: 1, payload: "demoNinaGuestNano0001", name: "Nina ROSSI" });
    await publish("guestPortal", portalUid, guestPage("Léa", "MARTIN", "lea.demo@example.org", { invitations: 0, wallet: null }));
    await setDoc(org(`guestPortalQr/${portalUid}`), { schema: 1, payload: "demoLeaGuestNano00001", name: "Léa MARTIN" });

    // Temporary guest lists (schema 2): one that ended this morning at 8:00 (hidden), one tonight
    // with invites open and answers waiting, one in two days. Léa (volunteer) manages the second.
    const startOfDay = (millis) => { const d = new Date(millis); d.setHours(0, 0, 0, 0); return d.getTime(); };
    const visibleUntil = (eventDate) => startOfDay(eventDate) + 32 * 60 * 60 * 1000;
    const group = (eventDate, venueName, artistName, guests, extra = {}) => ({
      eventDate, venueName, artistName, visibleUntil: visibleUntil(eventDate), guests, invite: null, pending: [], ...extra,
    });
    const tempGuest = (guestKey, firstName, lastName, extra = {}) => ({
      guestKey, firstName, lastName, emergencyPhone: "+41 79 222 33 44", accessNames: [], entryUsed: false, qr: true, wallet: null, ...extra,
    });
    const groups = [
      group(now - 2 * DAY, "Groove", "Les Hiboux", [
        tempGuest("a1b2c3d4e5f60001", "Ada", "LEROY", { entryUsed: true, qr: false }),
      ]),
      group(now, "Groove", "Collège Rousseau", [
        tempGuest("a1b2c3d4e5f60005", "Ada", "LEROY", { entryUsed: true, qr: false, accessNames: ["Backstage"] }),
        tempGuest("a1b2c3d4e5f60002", "Bruno", "MEIER", { wallet: { balance: 15, currency: "CHF" }, accessNames: ["Backstage"] }),
        tempGuest("a1b2c3d4e5f60006", "Eva", "BLANC"),
      ], {
        invite: { formId: "demoInviteTpl0001", maxGuests: 6, remaining: 2, accesses: [{ id: "acc-bs", name: "Backstage", max: 3, remaining: 0 }, { id: "acc-vip", name: "VIP", max: 0, remaining: 2 }] },
        pending: [
          { responseId: "demoInviteAns0001", kind: "ADD", names: ["Farid NOOR"], guestKeys: [], status: "PENDING_REVIEW" },
          { responseId: "demoInviteAns0002", kind: "REMOVE", names: ["Eva BLANC"], guestKeys: ["a1b2c3d4e5f60006"], status: "PENDING_REVIEW" },
          { responseId: "demoInviteAns0003", kind: "ADD", names: ["Gil ROY"], guestKeys: [], status: "REJECTED" },
        ],
      }),
      group(now + 2 * DAY, "Le Terreau", "Nuit Mist", [
        tempGuest("a1b2c3d4e5f60003", "Chloé", "FAVRE"),
        tempGuest("a1b2c3d4e5f60004", "David", "KELLER", { emergencyPhone: "" }),
      ]),
    ];
    const tempPage = (contactEmail, list, deviceCreated) => ({ association: "Association Démo", contactEmail, deviceCreated, groups: list });
    const expiresAtMillis = Math.max(...groups.map((g) => g.visibleUntil));
    const tempQr = { schema: 1, items: {
      a1b2c3d4e5f60002: { payload: "demoTempNanoBruno0002", name: "Bruno MEIER" },
      a1b2c3d4e5f60003: { payload: "demoTempNanoChloe0003", name: "Chloé FAVRE" },
      a1b2c3d4e5f60004: { payload: "demoTempNanoDavid0004", name: "David KELLER" },
      a1b2c3d4e5f60006: { payload: "demoTempNanoEva00006", name: "Eva BLANC" },
    } };
    const publish2 = (uid, payload) => setDoc(org(`temporaryGuestPortal/${uid}`), {
      schema: 2, payload: JSON.stringify(payload), contentHash: "demo", updatedAt: now - 5 * 60 * 1000,
      expiresAtMillis, deviceCreated: payload.deviceCreated,
    });
    await publish2(managerUid, tempPage("manager.demo@example.org", groups, true));
    await setDoc(org(`temporaryGuestPortalQr/${managerUid}`), tempQr);
    const leaList = { ...groups[1], invite: { ...groups[1].invite, formId: "demoInviteTpl0002" } };
    await publish2(portalUid, tempPage("lea.demo@example.org", [leaList], false));
    await setDoc(org(`temporaryGuestPortalQr/${portalUid}`), { schema: 1, items: { a1b2c3d4e5f60002: tempQr.items.a1b2c3d4e5f60002, a1b2c3d4e5f60006: tempQr.items.a1b2c3d4e5f60006 } });
    // Léa (volunteer) runs tonight: full guest list of the date, every venue.
    const glGuest = (key, firstName, lastName, listName, extra = {}) => ({
      guestKey: key, firstName, lastName, listName, venueName: "Groove", accessIds: [], accessNames: [],
      entryUsed: false, emergencyPhone: "+41 79 222 33 44", ...extra,
    });
    const glPayload = { association: "Association Démo", grants: [{
      grantId: "demoGrant0001", eventDate: now, venueName: "", visibleUntil: visibleUntil(now),
      lists: [{ name: "Collège Rousseau", venueName: "Groove" }, { name: "Les Amis", venueName: "Groove" }],
      accesses: [{ id: "acc-bs", name: "Backstage", venueName: "Groove" }, { id: "acc-vip", name: "VIP", venueName: "Groove" }],
      guests: [
        glGuest("a1b2c3d4e5f60005", "Ada", "LEROY", "Collège Rousseau", { entryUsed: true, accessIds: ["acc-bs"], accessNames: ["Backstage"] }),
        glGuest("a1b2c3d4e5f60002", "Bruno", "MEIER", "Collège Rousseau", { accessIds: ["acc-bs"], accessNames: ["Backstage"] }),
        glGuest("a1b2c3d4e5f60006", "Eva", "BLANC", "Collège Rousseau"),
        glGuest("a1b2c3d4e5f60007", "Hugo", "LEMAIRE", "Les Amis", { accessIds: ["acc-vip"], accessNames: ["VIP"] }),
      ],
    }] };
    await setDoc(org(`guestListPortal/${portalUid}`), {
      schema: 1, payload: JSON.stringify(glPayload), contentHash: "demo", updatedAt: now - 60 * 1000,
      expiresAtMillis: visibleUntil(now), grantDates: [now],
    });
    // The invite template of tonight's list, bound to both demo accounts' lists for the add dialog.
    for (const [id, uid] of [["demoInviteTpl0001", managerUid], ["demoInviteTpl0002", portalUid]]) {
      await setDoc(org(`guestForms/${id}`), {
        formId: id, portalUid: uid, status: "OPEN", allowMultipleResponses: true, venueName: "Groove", eventName: "",
        eventDateMillis: now, artistName: "Collège Rousseau", offeredAccessIds: '[{"id":"acc-bs","name":"Backstage","maxRequests":3},{"id":"acc-vip","name":"VIP","maxRequests":0}]',
        maxGuests: 6, expiresAtMillis: visibleUntil(now), askEmail: false, askPhone: false, prefillEmail: "manager.demo@example.org",
        prefillPhone: "+41 79 222 33 44", lastModified: now, createdAt: now,
      });
    }
  });
  await env.cleanup();
  return { portalUid, jeanUid, ninaUid, managerUid };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  seed()
    .then(({ portalUid }) => {
      console.log("Seeded demo-org. Portal login: lea.demo@example.org / Demo-Passw0rd!  uid", portalUid);
      console.log(`Also jean.demo@example.org and paul.demo@example.org (last name "D.", same password). Google sign-in: ${JEAN_GOOGLE} is matched by the`);
      console.log(`admin address; any other @${GOOGLE_DOMAIN} account can be linked from Léa's "My account".`);
      console.log("Guest form   http://127.0.0.1:5002/g/demo-org/demoGuestForm0001");
      console.log("Photo        http://127.0.0.1:5002/p/demo-org/demoPhotoReq0001");
      console.log("Signup       http://127.0.0.1:5002/s/demo-org/demoSignupForm0001");
      console.log("Guest signup http://127.0.0.1:5002/s/demo-org/demoGuestSignup0001");
      console.log("Guest spaces: nina.demo@example.org (permanent guest), manager.demo@example.org (two groups,");
      console.log("unverified address); Léa has three profiles. Same password.");
      console.log("Portal       http://127.0.0.1:5002/v/demo-org");
    })
    .catch((error) => {
      console.error(error);
      process.exit(1);
    });
}
