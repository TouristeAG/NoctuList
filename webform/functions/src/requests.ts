// Requests written by signed-in portal accounts: PROFILE (edit name and phone), CLAIM (link an
// existing roster entry by e-mail) and QR_EMAIL (mail the wallet QR code). Mirrors
// VolunteerPortalService.processRequest, handleClaim and complete (Kotlin); devices only step in
// after their 2 minute grace period, or when a claim of ours is older than 5 minutes.
import * as logger from "firebase-functions/logger";
import type { DocumentReference, Firestore } from "firebase-admin/firestore";
import { associationName, claim, millisOf, portalEnabled, sha256, touchStatus } from "./common";
import { buildQrMail, parseMailTemplate, safeRecipient, sendMail, type MailTransport } from "./mail";
import { kotlinTrim, parseRequest, type PortalRequest } from "./validate";

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
  | "LINKED_ELSEWHERE";

export interface RequestResult {
  status: "DONE" | "REJECTED";
  reason: RequestReason;
}

const STATUS_PENDING = "PENDING";
const MAX_LABEL = 80;
const DONE: RequestResult = { status: "DONE", reason: "" };
const rejected = (reason: Exclude<RequestReason, "">): RequestResult => ({ status: "REJECTED", reason });

/**
 * Only pending requests. A PROCESSING claim, however old, is left to devices (their own clock
 * judges its age); the volunteer can also send the request again once it is 5 minutes old.
 */
const isClaimable = (d: Data): boolean => d.status === STATUS_PENDING;

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
    result = await processClaimed(db, mail, orgId, requestId, claimed);
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
  mail: MailTransport,
  orgId: string,
  requestId: string,
  claimed: Data,
): Promise<RequestResult> {
  const request = parseRequest(requestId, claimed);
  if (request === null) return rejected("INVALID");
  switch (request.type) {
    case "PROFILE":
      return updateProfile(db, orgId, requestId, request);
    case "CLAIM":
      return handleClaim(db, orgId, requestId, request);
    case "QR_EMAIL":
      return sendQr(db, mail, orgId, request);
  }
}

/** `complete` (Kotlin): the answer the portal page waits for. */
async function complete(ref: DocumentReference, result: RequestResult): Promise<void> {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now() }, { merge: true });
}

const volunteers = (db: Firestore, orgId: string) => db.collection(`orgs/${orgId}/volunteers`);

/**
 * Name, last name and phone of the account's roster entry, nothing else: admin rights, NFC cards
 * and photos are never touched. The published page gets the same fields in the same transaction.
 */
async function updateProfile(
  db: Firestore,
  orgId: string,
  requestId: string,
  request: PortalRequest,
): Promise<RequestResult> {
  const projectionRef = db.doc(`orgs/${orgId}/volunteerPortal/${request.uid}`);
  try {
    return await db.runTransaction(async (tx) => {
      const linked = await tx.get(volunteers(db, orgId).where("portalUid", "==", request.uid));
      const owner = linked.docs.find((d) => isOwner(d, request.uid));
      if (owner === undefined) return rejected("NO_ACCESS");
      const projection = await tx.get(projectionRef);
      const now = Date.now();
      const lastName = request.lastName.toUpperCase();
      tx.update(owner.ref, {
        name: request.firstName,
        lastNameAbbreviation: lastName,
        phone: request.phone,
        lastModified: Math.max(now, (millisOf(owner.get("lastModified")) ?? 0) + 1),
      });
      const patch = patchedProjection(projection.data(), request.firstName, lastName, request.phone, now);
      if (patch !== null) tx.update(projectionRef, patch);
      return DONE;
    });
  } catch (e) {
    logger.error("profile not saved", { orgId, requestId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}

/**
 * The published page with the new profile fields, or null when there is none or it cannot be
 * read (a device republishes it anyway once the roster entry changes). Key order is kept.
 */
function patchedProjection(
  data: Data | undefined,
  firstName: string,
  lastName: string,
  phone: string,
  now: number,
): Data | null {
  if (data === undefined || typeof data.payload !== "string") return null;
  let payload: unknown;
  try {
    payload = JSON.parse(data.payload);
  } catch {
    return null;
  }
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return null;
  const text = JSON.stringify({ ...(payload as Data), firstName, lastName, phone });
  return { payload: text, contentHash: sha256(text), updatedAt: now };
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
  const sent = await sendMail({ ...message, to, fromName: association.trim().slice(0, MAX_LABEL) }, mail);
  return sent ? DONE : rejected("SEND_FAILED");
}
