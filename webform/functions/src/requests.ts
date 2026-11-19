// Requests written by signed-in portal accounts: PROFILE (edit name, phone and photo), CLAIM (link an
// existing roster entry by e-mail), QR_EMAIL (mail the wallet QR code) and GOOGLE_UNLINK (remove a
// Google account the volunteer linked; GOOGLE_LINK is answered by google.ts). Mirrors
// VolunteerPortalService.processRequest, handleClaim and complete (Kotlin); devices only step in
// after their 2 minute grace period, or when a claim of ours is older than 5 minutes.
import * as logger from "firebase-functions/logger";
import type { DocumentReference, Firestore } from "firebase-admin/firestore";
import { associationName, claim, millisOf, patchedProjection, portalEnabled, sha256, touchStatus } from "./common";
import { unlinkGoogle } from "./google";
import { buildQrMail, parseMailTemplate, safeRecipient, sendMail, type MailTransport } from "./mail";
import { kotlinTrim, parseRequest, type PortalRequest } from "./validate";
import { randomUUID } from "node:crypto";
import {
  portalPhotoPath,
  portalUploadPath,
  profilePhotosOn,
  readJpeg,
  volunteerPhotoPath,
  type PhotoBucket,
} from "./signup";

type Data = Record<string, unknown>;
/** A volunteer document, read whole or through a query. */
type Fields = { get(field: string): unknown };

export type RequestReason =
  | ""
  | "INVALID"
  | "NO_ACCESS"
  | "NOT_ALLOWED"
  | "NO_EMAIL"
  | "SEND_FAILED"
  | "SAVE_FAILED"
  | "LINKED_ELSEWHERE"
  | "ADMIN_LINK";

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

/** stringOf (FirestoreChangeApplier): null is "", other values are their text. */
const stringOf = (v: unknown): string => (v === null || v === undefined ? "" : typeof v === "string" ? v : String(v));

/** boolOf (FirestoreChangeApplier): booleans, "true" in any case, non-zero numbers. */
function boolOf(v: unknown): boolean {
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v.toLowerCase() === "true";
  if (typeof v === "number") return Number.isFinite(v) && Math.trunc(v) !== 0;
  return false;
}

const isBlank = (v: unknown): boolean => kotlinTrim(stringOf(v)) === "";

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
  try {
    if (!(await portalEnabled(db, orgId))) {
      logger.info("request skipped", { orgId, requestId, reason: "PORTAL_OFF" });
      return;
    }
    const ref = db.doc(`orgs/${orgId}/portalRequests/${requestId}`);
    const claimed = await claim(ref, isClaimable);
    if (claimed === null) return;
    result = await processClaimed(db, bucket, mail, orgId, requestId, claimed);
    await complete(ref, result);
  } catch (e) {
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
      return updateProfile(db, bucket, orgId, requestId, request);
    case "CLAIM":
      return handleClaim(db, orgId, requestId, request);
    case "QR_EMAIL":
      return sendQr(db, mail, orgId, request);
    case "GOOGLE_UNLINK": {
      const reason = await unlinkGoogle(db, orgId, requestId, request.uid);
      return reason === "" ? DONE : rejected(reason);
    }
    case "GOOGLE_LINK":
      // Never claimed (isClaimable): answered through its Google claim.
      return rejected("INVALID");
  }
}

/** `complete` (Kotlin): the answer the portal page waits for. */
async function complete(ref: DocumentReference, result: RequestResult): Promise<void> {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now() }, { merge: true });
}

const volunteers = (db: Firestore, orgId: string) => db.collection(`orgs/${orgId}/volunteers`);

/** A photo moved onto the profile: its Storage path, download URL and portal version. */
interface SavedPhoto {
  path: string;
  url: string;
  version: string;
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
): Promise<RequestResult> {
  try {
    return await saveProfile(db, bucket, orgId, requestId, request);
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
): Promise<RequestResult> {
  const projectionRef = db.doc(`orgs/${orgId}/volunteerPortal/${request.uid}`);
  let photo: SavedPhoto | null = null;
  let ownerId = "";
  if (request.photo) {
    const linked = await volunteers(db, orgId).where("portalUid", "==", request.uid).get();
    const owner = linked.docs.find((d) => isOwner(d, request.uid));
    if (owner === undefined) return rejected("NO_ACCESS");
    // Photos turned off, nothing uploaded or not a JPEG: the whole edit is refused.
    if (!(await profilePhotosOn(db, orgId))) return rejected("INVALID");
    const bytes = await readJpeg(bucket, portalUploadPath(orgId, request.uid));
    if (bytes === null) return rejected("INVALID");
    try {
      photo = await savePhoto(bucket, orgId, owner.id, request.uid, bytes);
    } catch (e) {
      logger.error("photo not saved", { orgId, requestId, code: errorCode(e) });
      return rejected("SAVE_FAILED");
    }
    ownerId = owner.id;
  }
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(volunteers(db, orgId).where("portalUid", "==", request.uid));
      const owner = linked.docs.find((d) => isOwner(d, request.uid));
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
      if (photo !== null) Object.assign(fields, { hasPhoto: true, photoVersion: photo.version });
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
  volunteerId: string,
  uid: string,
  bytes: Buffer,
): Promise<SavedPhoto> {
  const path = volunteerPhotoPath(orgId, volunteerId);
  const token = randomUUID();
  await bucket.file(path).save(bytes, {
    contentType: "image/jpeg",
    resumable: false,
    metadata: { metadata: { firebaseStorageDownloadTokens: token } },
  });
  await bucket.file(portalPhotoPath(orgId, uid)).save(bytes, { contentType: "image/jpeg", resumable: false });
  const url =
    `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(path)}` +
    `?alt=media&token=${token}`;
  return { path, url, version: sha256(url).slice(0, 16) };
}

/**
 * `handleClaim` (Kotlin): an account already linked is done; otherwise exactly one roster entry
 * with portal access, this e-mail and no linked account gets the link.
 */
async function handleClaim(
  db: Firestore,
  orgId: string,
  requestId: string,
  request: PortalRequest,
): Promise<RequestResult> {
  const all = await volunteers(db, orgId).select("email", "portalEnabled", "portalUid").get();
  if (all.docs.some((d) => isOwner(d, request.uid))) return DONE;
  const sameEmail = (d: Fields): boolean =>
    boolOf(d.get("portalEnabled")) && kotlinTrim(stringOf(d.get("email"))).toLowerCase() === request.email;
  const candidates = all.docs.filter(sameEmail);
  const free = candidates.filter((d) => isBlank(d.get("portalUid")));
  if (candidates.length === 0) return rejected("NO_ACCESS");
  if (free.length !== 1) return rejected("LINKED_ELSEWHERE");
  const ref = free[0].ref;
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(volunteers(db, orgId).where("portalUid", "==", request.uid));
      if (linked.docs.some((d) => isOwner(d, request.uid))) return DONE;
      const snap = await tx.get(ref);
      // Changed since the scan: answer as a failed save, the volunteer can ask again.
      if (!snap.exists || !sameEmail(snap) || !isBlank(snap.get("portalUid"))) {
        return rejected("SAVE_FAILED");
      }
      tx.update(ref, {
        portalUid: request.uid,
        lastModified: Math.max(Date.now(), (millisOf(snap.get("lastModified")) ?? 0) + 1),
      });
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
