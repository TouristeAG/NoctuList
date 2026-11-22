// Requests written by signed-in portal accounts: PROFILE and GUEST_PROFILE (edit name, phone and
// photo), CLAIM (link the open profiles of a verified address), QR_EMAIL and GUEST_QR_EMAIL (mail the
// wallet QR code), CERTIFICATE_EMAIL (mail the volunteer certificate PDF) and GOOGLE_UNLINK (remove a Google account the holder linked; GOOGLE_LINK is
// answered by google.ts). Mirrors
// VolunteerPortalService.processRequest, handleClaim and complete (Kotlin); devices only step in
// after their 2 minute grace period, or when a claim of ours is older than 5 minutes.
import * as logger from "firebase-functions/logger";
import { FieldValue, type DocumentReference, type Firestore } from "firebase-admin/firestore";
import {
  associationName,
  boolOf,
  claim,
  guestOwnerOf,
  guestPortalEnabled,
  institutionSetting,
  isBlankValue as isBlank,
  millisOf,
  patchedProjection,
  portalEnabled,
  sha256,
  stringOf,
  temporaryGuestPortalEnabled,
  touchStatus,
  type Fields,
} from "./common";
import { unlinkGoogle } from "./google";
import {
  assemble,
  dayOf,
  defaultTemplate,
  displayName,
  parseTemplate,
  type CertJob,
  type CertJobType,
  type Category,
} from "./certificate";
import { renderCertificatePdf } from "./certificatePdf";
import {
  buildCertificateMail,
  buildQrMail,
  certificateFileName,
  logoBytes,
  parseMailTemplate,
  safeRecipient,
  sendMail,
  type MailTransport,
} from "./mail";
import { kotlinTrim, parseRequest, type PortalRequest } from "./validate";
import { randomUUID } from "node:crypto";
import {
  guestPhotoPath,
  portalPhotoPath,
  portalUploadPath,
  profilePhotosOn,
  readJpeg,
  volunteerPhotoPath,
  type PhotoBucket,
} from "./signup";

type Data = Record<string, unknown>;

export type RequestReason =
  | ""
  | "INVALID"
  | "NO_ACCESS"
  | "NOT_ALLOWED"
  | "NO_EMAIL"
  | "NO_TEMPLATE"
  | "NOTHING_TO_CERTIFY"
  | "SEND_FAILED"
  | "SAVE_FAILED"
  | "LINKED_ELSEWHERE"
  | "ADMIN_LINK"
  | "NOT_FOUND"
  | "ENTRY_USED";

export interface RequestResult {
  status: "DONE" | "REJECTED";
  reason: RequestReason;
}

const STATUS_PENDING = "PENDING";
const MAX_LABEL = 80;
const DONE: RequestResult = { status: "DONE", reason: "" };
const rejected = (reason: Exclude<RequestReason, "">): RequestResult => ({ status: "REJECTED", reason });

/**
 * Only pending requests, never GOOGLE_LINK: that one waits for the Google claim carrying its code
 * (google.ts), which answers it. A PROCESSING claim, however old, is left to devices (their own clock
 * judges its age); the volunteer can also send the request again once it is 5 minutes old.
 */
const isClaimable = (d: Data): boolean => d.status === STATUS_PENDING && d.type !== "GOOGLE_LINK";

const GUEST_TYPES: readonly unknown[] = ["GUEST_PROFILE", "GUEST_QR_EMAIL"];
const TEMP_TYPES: readonly unknown[] = ["TEMP_GUEST_NAME"];

/** Thrown when a claimed request must go back to PENDING for later (a device, or the server again). */
class Deferred extends Error {
  readonly code = "DEFERRED";
}

/** The owner of a portal account, as on devices: `portalEnabled && portalUid == uid`. */
const isOwner = (d: Fields, uid: string): boolean =>
  boolOf(d.get("portalEnabled")) && stringOf(d.get("portalUid")) === uid;

const errorCode = (e: unknown): string => {
  const code = (e as { code?: unknown })?.code;
  return code === undefined ? "" : String(code);
};

/**
 * Handles one write of `orgs/{orgId}/portalRequests/{requestId}`. The trigger also fires on our
 * own claim and result, on device claims and on deletes: anything but a pending request is
 * ignored before reading or writing anything.
 */
export async function onRequestDocument(
  db: Firestore,
  bucket: PhotoBucket,
  mail: MailTransport,
  orgId: string,
  requestId: string,
  current: Data | undefined,
): Promise<void> {
  if (current === undefined || !isClaimable(current)) return;
  let result: RequestResult;
  const ref = db.doc(`orgs/${orgId}/portalRequests/${requestId}`);
  try {
    if (!(await portalEnabled(db, orgId))) {
      logger.info("request skipped", { orgId, requestId, reason: "PORTAL_OFF" });
      return;
    }
    const wait = await waitReason(db, orgId, current.type);
    if (wait !== null) {
      logger.info("request skipped", { orgId, requestId, reason: wait });
      return;
    }
    const claimed = await claim(ref, isClaimable);
    if (claimed === null) return;
    result = await processClaimed(db, bucket, mail, orgId, requestId, claimed);
    await complete(ref, result);
  } catch (e) {
    if (e instanceof Deferred) {
      // Changed between the check and the claim: hand it back untouched. The write wakes this
      // trigger again, which then skips it before claiming.
      await release(ref).catch(() => undefined);
      logger.info("request skipped", { orgId, requestId, reason: e.message });
      return;
    }
    // Left in PROCESSING: a device takes it over once the claim is 5 minutes old.
    // Only the error code is logged; messages can quote document content.
    logger.error("request failed", { orgId, requestId, reason: "FAILED", code: errorCode(e) });
    return;
  }
  // The request is answered: a failure here must not count it as failed.
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger.error("server status not written", { orgId, requestId, code: errorCode(e) });
  }
  logger.info("request processed", { orgId, requestId, status: result.status, reason: result.reason });
}

/**
 * Why a request waits without being claimed, or null to process it now. Guest requests wait while
 * the guest space is off (as on devices); a guest QR mail waits for the published guest mail
 * template (`portalMail/guest`), since there is no guest fallback text on the server and the
 * volunteer template is never mailed to guests: a device sends it after its grace period.
 */
async function waitReason(db: Firestore, orgId: string, type: unknown): Promise<string | null> {
  if (TEMP_TYPES.includes(type)) return (await temporaryGuestPortalEnabled(db, orgId)) ? null : "TEMP_SPACE_OFF";
  if (!GUEST_TYPES.includes(type)) return null;
  if (!(await guestPortalEnabled(db, orgId))) return "GUEST_SPACE_OFF";
  if (type === "GUEST_QR_EMAIL" && (await publishedGuestTemplate(db, orgId)) === null) return "NO_TEMPLATE";
  return null;
}

/** The guest QR mail devices render from the guest e-mail settings (Guest theme), or null. */
async function publishedGuestTemplate(db: Firestore, orgId: string) {
  return parseMailTemplate((await db.doc(`orgs/${orgId}/portalMail/guest`).get()).data());
}

/** Undoes our claim: the request is PENDING again, without the claim fields. */
async function release(ref: DocumentReference): Promise<void> {
  await ref.set(
    { status: STATUS_PENDING, processingAt: FieldValue.delete(), processingDevice: FieldValue.delete() },
    { merge: true },
  );
}

async function processClaimed(
  db: Firestore,
  bucket: PhotoBucket,
  mail: MailTransport,
  orgId: string,
  requestId: string,
  claimed: Data,
): Promise<RequestResult> {
  const request = parseRequest(requestId, claimed);
  if (request === null) return rejected("INVALID");
  switch (request.type) {
    case "PROFILE":
      return updateProfile(db, bucket, orgId, requestId, request, "VOLUNTEER");
    case "GUEST_PROFILE":
      await checkGuestSpace(db, orgId);
      return updateProfile(db, bucket, orgId, requestId, request, "GUEST");
    case "CLAIM":
      return handleClaim(db, orgId, requestId, request);
    case "QR_EMAIL":
      return sendQr(db, mail, orgId, request);
    case "GUEST_QR_EMAIL":
      await checkGuestSpace(db, orgId);
      return sendGuestQr(db, mail, orgId, request);
    case "CERTIFICATE_EMAIL":
      return sendCertificate(db, mail, orgId, request);
    case "GOOGLE_UNLINK": {
      const reason = await unlinkGoogle(db, orgId, requestId, request.uid);
      return reason === "" ? DONE : rejected(reason);
    }
    case "TEMP_GUEST_NAME":
      if (!(await temporaryGuestPortalEnabled(db, orgId))) throw new Deferred("TEMP_SPACE_OFF");
      return renameTemporaryGuest(db, orgId, requestId, request);
    case "GOOGLE_LINK":
      // Never claimed (isClaimable): answered through its Google claim.
      return rejected("INVALID");
  }
}

/** The key a temporary page shows for a guest: SHA-256 hex of its nanoId, first 16 characters. */
const guestKeyOf = (nanoId: string): string => sha256(nanoId).slice(0, 16);

/**
 * `renameTemporaryGuest` (Kotlin): first and last name of one temporary guest of the account, found
 * by its page key among the open temporary guests carrying the uid. A used entry keeps its name.
 * The page and the QR item get the new name in the same transaction (devices republish anyway).
 */
async function renameTemporaryGuest(
  db: Firestore,
  orgId: string,
  requestId: string,
  request: PortalRequest,
): Promise<RequestResult> {
  const pageRef = db.doc(`orgs/${orgId}/temporaryGuestPortal/${request.uid}`);
  const qrRef = db.doc(`orgs/${orgId}/temporaryGuestPortalQr/${request.uid}`);
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(guests(db, orgId).where("portalUid", "==", request.uid));
      const target = linked.docs.find(
        (d) => boolOf(d.get("isTemporaryGuest")) && boolOf(d.get("portalEnabled")) &&
          guestKeyOf(stringOf(d.get("nanoId")) || d.id) === request.guestKey,
      );
      if (target === undefined) return rejected("NOT_FOUND");
      if ((millisOf(target.get("temporaryEntryValidatedAt")) ?? 0) > 0) return rejected("ENTRY_USED");
      const page = await tx.get(pageRef);
      const qr = await tx.get(qrRef);
      const now = Date.now();
      // An empty field keeps the current value; last names are upper-cased like everywhere else.
      const firstName = request.firstName || stringOf(target.get("name"));
      const lastName = request.lastName ? request.lastName.toUpperCase() : stringOf(target.get("lastNameAbbreviation"));
      const renamed = { ...request, firstName, lastName };
      tx.update(target.ref, {
        name: firstName,
        lastNameAbbreviation: lastName,
        lastModified: Math.max(now, (millisOf(target.get("lastModified")) ?? 0) + 1),
      });
      const patch = renamedPage(page.data(), renamed, now);
      if (patch !== null) tx.update(pageRef, patch);
      const item = (qr.data()?.items as Data | undefined)?.[request.guestKey ?? ""];
      if (item !== undefined && item !== null && typeof item === "object") {
        tx.update(qrRef, { [`items.${request.guestKey}.name`]: `${firstName} ${lastName}`.trim() });
      }
      return DONE;
    });
  } catch (e) {
    logger.error("name not saved", { orgId, requestId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}

/** The temporary page with the guest's new name, or null when it cannot be read. Key order is kept. */
function renamedPage(data: Data | undefined, request: PortalRequest, now: number): Data | null {
  if (data === undefined || typeof data.payload !== "string") return null;
  let payload: { groups?: unknown };
  try {
    payload = JSON.parse(data.payload);
  } catch {
    return null;
  }
  if (!Array.isArray(payload?.groups)) return null;
  for (const group of payload.groups as Data[]) {
    if (!Array.isArray(group?.guests)) continue;
    for (const g of group.guests as Data[]) {
      if (g?.guestKey === request.guestKey) {
        g.firstName = request.firstName;
        g.lastName = request.lastName;
      }
    }
  }
  const text = JSON.stringify(payload);
  return { payload: text, contentHash: sha256(text), updatedAt: now };
}

/** `complete` (Kotlin): the answer the portal page waits for. */
async function complete(ref: DocumentReference, result: RequestResult): Promise<void> {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now() }, { merge: true });
}

/** The guest space was turned off after the claim: the request waits again. */
async function checkGuestSpace(db: Firestore, orgId: string): Promise<void> {
  if (!(await guestPortalEnabled(db, orgId))) throw new Deferred("GUEST_SPACE_OFF");
}

const volunteers = (db: Firestore, orgId: string) => db.collection(`orgs/${orgId}/volunteers`);
const guests = (db: Firestore, orgId: string) => db.collection(`orgs/${orgId}/guests`);

type HolderKind = "VOLUNTEER" | "GUEST";

/** Where one kind of profile lives and which profile of a portal account a request edits. */
interface ProfileKind {
  collection: (db: Firestore, orgId: string) => ReturnType<Firestore["collection"]>;
  page: string;
  photoPath: (orgId: string, id: string) => string;
  /** The owner among the profiles carrying the uid, or undefined. */
  owner: <T extends Fields & { id: string }>(docs: readonly T[], uid: string) => T | undefined;
}

const PROFILE_KINDS: Record<HolderKind, ProfileKind> = {
  VOLUNTEER: {
    collection: volunteers,
    page: "volunteerPortal",
    photoPath: volunteerPhotoPath,
    owner: (docs, uid) => docs.find((d) => isOwner(d, uid)),
  },
  GUEST: {
    collection: guests,
    page: "guestPortal",
    photoPath: guestPhotoPath,
    owner: (docs, uid) => guestOwnerOf(docs, uid) ?? undefined,
  },
};

/** A photo moved onto the profile: its Storage path, download URL and portal version. */
interface SavedPhoto {
  path: string;
  url: string;
  version: string;
  /** Also copied as the account photo `portalPhotos/{uid}.jpg`. */
  account: boolean;
}

/** A volunteer of the portal account [uid] has a profile photo, which the account then shows. */
async function volunteerHasPhoto(db: Firestore, orgId: string, uid: string): Promise<boolean> {
  if (!(await profilePhotosOn(db, orgId))) return false;
  const linked = await volunteers(db, orgId).where("portalUid", "==", uid).get();
  return linked.docs.some(
    (d) => isOwner(d, uid) && (!isBlank(d.get("profilePhotoPath")) || !isBlank(d.get("profilePhotoUrl"))),
  );
}

/**
 * Name, last name, phone and, when the request says so, the photo waiting in
 * `portalPhotoUploads/{uid}.jpg`. Admin rights and NFC cards are never touched. The published
 * page gets the same fields in the same transaction. The upload is always removed afterwards.
 */
async function updateProfile(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  requestId: string,
  request: PortalRequest,
  kind: HolderKind,
): Promise<RequestResult> {
  try {
    return await saveProfile(db, bucket, orgId, requestId, request, kind);
  } finally {
    if (request.photo) {
      await bucket.file(portalUploadPath(orgId, request.uid)).delete({ ignoreNotFound: true }).catch(() => undefined);
    }
  }
}

async function saveProfile(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  requestId: string,
  request: PortalRequest,
  kind: HolderKind,
): Promise<RequestResult> {
  const k = PROFILE_KINDS[kind];
  const projectionRef = db.doc(`orgs/${orgId}/${k.page}/${request.uid}`);
  let photo: SavedPhoto | null = null;
  let ownerId = "";
  if (request.photo) {
    const linked = await k.collection(db, orgId).where("portalUid", "==", request.uid).get();
    const owner = k.owner(linked.docs, request.uid);
    if (owner === undefined) return rejected("NO_ACCESS");
    // Photos turned off, nothing uploaded or not a JPEG: the whole edit is refused.
    if (!(await profilePhotosOn(db, orgId))) return rejected("INVALID");
    const bytes = await readJpeg(bucket, portalUploadPath(orgId, request.uid));
    if (bytes === null) return rejected("INVALID");
    // §6.3: the account photo is the volunteer's when one of the account has a photo.
    const accountPhoto = kind === "VOLUNTEER" || !(await volunteerHasPhoto(db, orgId, request.uid));
    try {
      photo = await savePhoto(bucket, orgId, k.photoPath(orgId, owner.id), accountPhoto ? request.uid : null, bytes);
    } catch (e) {
      logger.error("photo not saved", { orgId, requestId, code: errorCode(e) });
      return rejected("SAVE_FAILED");
    }
    ownerId = owner.id;
  }
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(k.collection(db, orgId).where("portalUid", "==", request.uid));
      const owner = k.owner(linked.docs, request.uid);
      if (owner === undefined) return rejected("NO_ACCESS");
      // Relinked to another roster entry since the photo was saved: refuse, the volunteer can retry.
      if (photo !== null && owner.id !== ownerId) return rejected("SAVE_FAILED");
      const projection = await tx.get(projectionRef);
      const now = Date.now();
      const lastName = request.lastName.toUpperCase();
      tx.update(owner.ref, {
        name: request.firstName,
        lastNameAbbreviation: lastName,
        phone: request.phone,
        ...(photo !== null ? { profilePhotoPath: photo.path, profilePhotoUrl: photo.url } : {}),
        lastModified: Math.max(now, (millisOf(owner.get("lastModified")) ?? 0) + 1),
      });
      const fields: Data = { firstName: request.firstName, lastName, phone: request.phone };
      if (photo !== null && photo.account) Object.assign(fields, { hasPhoto: true, photoVersion: photo.version });
      const patch = patchedProjection(projection.data(), fields, now);
      if (patch !== null) tx.update(projectionRef, patch);
      return DONE;
    });
  } catch (e) {
    logger.error("profile not saved", { orgId, requestId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}

/**
 * Writes the photo where the app keeps it, with a fresh download token, and its portal copy.
 * The new URL changes the photo version (photoVersionOf, Kotlin), so devices and the portal
 * reload it instead of keeping the old picture cached.
 */
async function savePhoto(
  bucket: PhotoBucket,
  orgId: string,
  path: string,
  /** The account uid when the photo is also the account photo, else null. */
  uid: string | null,
  bytes: Buffer,
): Promise<SavedPhoto> {
  const token = randomUUID();
  await bucket.file(path).save(bytes, {
    contentType: "image/jpeg",
    resumable: false,
    metadata: { metadata: { firebaseStorageDownloadTokens: token } },
  });
  if (uid !== null) {
    await bucket.file(portalPhotoPath(orgId, uid)).save(bytes, { contentType: "image/jpeg", resumable: false });
  }
  const url =
    `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(path)}` +
    `?alt=media&token=${token}`;
  return { path, url, version: sha256(url).slice(0, 16), account: uid !== null };
}

/** The address a CLAIM matches: `email`, or for a temporary guest `temporaryPortalEmail` else `temporaryContactEmail`. */
function claimAddress(d: Fields): string {
  if (!boolOf(d.get("isTemporaryGuest"))) return kotlinTrim(stringOf(d.get("email"))).toLowerCase();
  const portal = stringOf(d.get("temporaryPortalEmail"));
  const address = kotlinTrim(portal) === "" ? stringOf(d.get("temporaryContactEmail")) : portal;
  return kotlinTrim(address).toLowerCase();
}

/**
 * `handleClaim` (Kotlin): links to the request's uid every open profile with a blank portal uid and
 * the verified address: volunteers, permanent guests (guest space on) and temporary groups (their
 * space on). Volunteers keep their own answers (an already linked volunteer is done, two free ones
 * are ambiguous); a guest profile of that address linked to another uid refuses the whole claim,
 * unless the account already has access, which then just skips it.
 */
async function handleClaim(
  db: Firestore,
  orgId: string,
  requestId: string,
  request: PortalRequest,
): Promise<RequestResult> {
  const uid = request.uid;
  const [allVolunteers, allGuests, guestSpace, tempSpace] = await Promise.all([
    volunteers(db, orgId).select("email", "portalEnabled", "portalUid").get(),
    guests(db, orgId)
      .select("email", "portalEnabled", "portalUid", "isTemporaryGuest", "isVolunteerBenefit",
        "temporaryPortalEmail", "temporaryContactEmail")
      .get(),
    guestPortalEnabled(db, orgId),
    temporaryGuestPortalEnabled(db, orgId),
  ]);
  const sameEmail = (d: Fields): boolean =>
    boolOf(d.get("portalEnabled")) && kotlinTrim(stringOf(d.get("email"))).toLowerCase() === request.email;
  const owner = allVolunteers.docs.some((d) => isOwner(d, uid));
  let volunteerToLink: DocumentReference | null = null;
  if (!owner) {
    const candidates = allVolunteers.docs.filter(sameEmail);
    const free = candidates.filter((d) => isBlank(d.get("portalUid")));
    if (candidates.length > 0 && free.length !== 1) return rejected("LINKED_ELSEWHERE");
    volunteerToLink = free[0]?.ref ?? null;
  }
  const claimable = (d: Fields): boolean => {
    if (!boolOf(d.get("portalEnabled"))) return false;
    if (boolOf(d.get("isTemporaryGuest"))) return tempSpace;
    if (boolOf(d.get("isVolunteerBenefit"))) return false;
    return guestSpace;
  };
  const open = allGuests.docs.filter(claimable);
  const guestOwned = open.some((d) => stringOf(d.get("portalUid")) === uid);
  const targets = open.filter((d) => stringOf(d.get("portalUid")) !== uid && claimAddress(d) === request.email);
  const foreign = targets.filter((d) => !isBlank(d.get("portalUid")));
  // Someone who already has access gets no error: the profile linked elsewhere is just skipped.
  const hasAccess = owner || guestOwned;
  if (foreign.length > 0 && !hasAccess) return rejected("LINKED_ELSEWHERE");
  const freeGuests = targets.filter((d) => isBlank(d.get("portalUid")));
  if (volunteerToLink === null && freeGuests.length === 0) return hasAccess ? DONE : rejected("NO_ACCESS");
  const guestStillFree = (d: Fields): boolean => claimable(d) && claimAddress(d) === request.email && isBlank(d.get("portalUid"));
  try {
    return await db.runTransaction(async (tx) => {
      const [linked, volunteerSnap, ...guestSnaps] = await Promise.all([
        tx.get(volunteers(db, orgId).where("portalUid", "==", uid)),
        volunteerToLink !== null ? tx.get(volunteerToLink) : Promise.resolve(null),
        ...freeGuests.map((d) => tx.get(d.ref)),
      ]);
      // Linked to this account meanwhile: the volunteer is no longer to link.
      const linkVolunteer = volunteerSnap !== null && !linked.docs.some((d) => isOwner(d, uid));
      // Changed since the scan: answer as a failed save, the holder can ask again.
      if (linkVolunteer && (!volunteerSnap.exists || !sameEmail(volunteerSnap) || !isBlank(volunteerSnap.get("portalUid")))) {
        return rejected("SAVE_FAILED");
      }
      if (guestSnaps.some((g) => !g.exists || !guestStillFree(g))) return rejected("SAVE_FAILED");
      const now = Date.now();
      const linkFields = (snap: Fields): Data => ({
        portalUid: uid,
        lastModified: Math.max(now, (millisOf(snap.get("lastModified")) ?? 0) + 1),
      });
      if (linkVolunteer) tx.update(volunteerSnap.ref, linkFields(volunteerSnap));
      for (const g of guestSnaps) tx.update(g.ref, linkFields(g));
      return DONE;
    });
  } catch (e) {
    logger.error("claim not saved", { orgId, requestId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}

/** Always to the address on the roster entry, never to the one in the request. */
async function sendQr(db: Firestore, mail: MailTransport, orgId: string, request: PortalRequest): Promise<RequestResult> {
  const linked = await volunteers(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = linked.docs.find((d) => isOwner(d, request.uid));
  if (owner === undefined) return rejected("NO_ACCESS");
  if (boolOf(owner.get("isAdmin"))) return rejected("NOT_ALLOWED");
  const email = kotlinTrim(stringOf(owner.get("email")));
  if (email === "") return rejected("NO_EMAIL");
  // An address nodemailer could read as several recipients is no usable address.
  const to = safeRecipient(email);
  if (to === null) return rejected("NO_EMAIL");
  const [template, association] = await Promise.all([
    db.doc(`orgs/${orgId}/portalMail/default`).get(),
    associationName(db, orgId),
  ]);
  // Volunteer.id is the document id; it only goes into the QR image.
  const message = await buildQrMail(parseMailTemplate(template.data()), stringOf(owner.get("name")), owner.id);
  const result = await sendMail({ ...message, to, fromName: association.trim().slice(0, MAX_LABEL) }, mail);
  return result.sent ? DONE : rejected("SEND_FAILED");
}

/**
 * GUEST_QR_EMAIL: like QR_EMAIL, on the permanent guest of the account. The QR code is the guest's
 * nanoId, the mail the published guest template with the guest's first name. An admin guest's QR doubles
 * as an admin key and is never mailed; temporary guests and volunteer benefits have no access.
 */
async function sendGuestQr(db: Firestore, mail: MailTransport, orgId: string, request: PortalRequest): Promise<RequestResult> {
  const linked = await guests(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = guestOwnerOf(linked.docs, request.uid);
  if (owner === null) return rejected("NO_ACCESS");
  if (boolOf(owner.get("isAdmin"))) return rejected("NOT_ALLOWED");
  const email = kotlinTrim(stringOf(owner.get("email")));
  if (email === "") return rejected("NO_EMAIL");
  const to = safeRecipient(email);
  if (to === null) return rejected("NO_EMAIL");
  const [template, association] = await Promise.all([publishedGuestTemplate(db, orgId), associationName(db, orgId)]);
  // Removed since the check: wait for a device rather than send the volunteer text.
  if (template === null) throw new Deferred("NO_TEMPLATE");
  const message = await buildQrMail(template, stringOf(owner.get("name")), owner.id);
  const result = await sendMail({ ...message, to, fromName: association.trim().slice(0, MAX_LABEL) }, mail);
  return result.sent ? DONE : rejected("SEND_FAILED");
}

const CERT_CATEGORIES: readonly string[] = [
  "MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT", "GRAPHIC_ASSOCIATION", "OTHER", "MEETING",
];
const CERT_TITLE_FALLBACK: Record<string, string> = {
  fr: "Attestation", en: "Certificate", de: "Bestätigung", it: "Attestazione", es: "Certificado",
};

/** The job types a device published with the certificate template; unreadable entries are left out. */
function publishedJobTypes(raw: unknown): CertJobType[] {
  if (!Array.isArray(raw)) return [];
  const out: CertJobType[] = [];
  for (const item of raw as Data[]) {
    if (item === null || typeof item !== "object" || typeof item.name !== "string") continue;
    const category = CERT_CATEGORIES.includes(item.category as string) ? (item.category as Category) : "OTHER";
    out.push({ name: kotlinTrim(item.name), category, description: stringOf(item.description) });
  }
  return out;
}

/** The volunteer's jobs as day and type; one without a usable date or type name is skipped. */
function certificateJobs(docs: readonly Fields[]): CertJob[] {
  const jobs: CertJob[] = [];
  for (const d of docs) {
    const jobTypeName = kotlinTrim(stringOf(d.get("jobTypeName")));
    const millis = millisOf(d.get("date"));
    if (jobTypeName === "" || millis === null || !Number.isFinite(new Date(millis).getTime())) continue;
    jobs.push({ jobTypeName, day: dayOf(millis) });
  }
  return jobs;
}

/** Plain replacement of the two mail variables: no `$` patterns, every occurrence. */
const fillMail = (text: string, firstName: string, association: string): string =>
  text.split("{firstName}").join(firstName).split("{association}").join(association);

/**
 * CERTIFICATE_EMAIL: the volunteer's certificate as a PDF, mailed to the profile address. The
 * volunteer's jobs are read here only; their id and the certificate text go nowhere else (no
 * Firestore write, no log). Admins are accepted: the certificate carries no identifier.
 */
async function sendCertificate(db: Firestore, mail: MailTransport, orgId: string, request: PortalRequest): Promise<RequestResult> {
  if ((await institutionSetting(db, orgId, "volunteer_portal_certificate_enabled")) !== "true") return rejected("NOT_ALLOWED");
  const published = (await db.doc(`orgs/${orgId}/portalMail/certificate`).get()).data();
  const rawTemplate = published?.template;
  const template = rawTemplate !== null && typeof rawTemplate === "object" ? parseTemplate(rawTemplate) : null;
  if (published === undefined || template === null) return rejected("NO_TEMPLATE");
  const linked = await volunteers(db, orgId).where("portalUid", "==", request.uid).get();
  const owner = linked.docs.find((d) => isOwner(d, request.uid));
  if (owner === undefined) return rejected("NO_ACCESS");
  const to = safeRecipient(kotlinTrim(stringOf(owner.get("email"))));
  if (to === null) return rejected("NO_EMAIL");

  // Volunteer.id is the document id; it only filters the jobs query.
  const jobDocs = await db.collection(`orgs/${orgId}/jobs`).where("volunteerId", "==", owner.id).get();
  const publishedAssociation = stringOf(published.association);
  const association = (publishedAssociation.trim() !== "" ? publishedAssociation : await associationName(db, orgId)).trim();
  const firstName = stringOf(owner.get("name"));
  const lastName = stringOf(owner.get("lastNameAbbreviation"));
  const assembly = assemble(
    template,
    association,
    { firstName, lastName, gender: stringOf(owner.get("gender")) },
    publishedJobTypes(published.jobTypes),
    certificateJobs(jobDocs.docs),
    {
      fromDay: Number(request.from?.replace(/-/g, "")),
      toDay: Number(request.to?.replace(/-/g, "")),
      todayDay: dayOf(Date.now()),
      jobTypes: request.jobTypes ?? [],
    },
  );
  if (assembly === null) return rejected("NOTHING_TO_CERTIFY");

  const titleParagraph = assembly.body.find((p) => p.size === "TITLE");
  const title = titleParagraph?.runs.map((r) => r.text).join("").trim() || (CERT_TITLE_FALLBACK[template.language] ?? "Attestation");
  const logoPng = logoBytes(stringOf(published.logoBase64));
  const signature = template.signer.imageBase64.trim();
  let pdf: Uint8Array;
  try {
    pdf = await renderCertificatePdf({
      paragraphs: assembly.body,
      footer: assembly.footer,
      logoPng,
      signatureImage: signature === "" ? null : Buffer.from(signature, "base64"),
      signer: { name: template.signer.name, title: template.signer.title },
      title,
    });
  } catch (e) {
    logger.error("certificate not rendered", { reason: "RENDER_FAILED", code: errorCode(e) });
    return rejected("SEND_FAILED");
  }

  const fallback = defaultTemplate(template.language, association).mail;
  const name = displayName(firstName);
  const subject = fillMail(template.mail.subject.trim() === "" ? fallback.subject : template.mail.subject, name, association);
  const body = fillMail(template.mail.body.trim() === "" ? fallback.body : template.mail.body, name, association);
  const message = buildCertificateMail({
    to,
    fromName: (template.mail.fromName.trim() === "" ? association : template.mail.fromName).trim().slice(0, MAX_LABEL),
    fromAddress: template.mail.fromAddress.trim(),
    replyTo: template.mail.replyTo.trim(),
    subject,
    body,
    pdf,
    fileName: certificateFileName(firstName, lastName, template.language),
    logoPng,
  });
  const result = await sendMail(message, mail);
  return result.sent ? DONE : rejected("SEND_FAILED");
}
