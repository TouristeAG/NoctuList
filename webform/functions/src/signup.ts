// Registration links answered on the public page. Mirrors VolunteerPortalService.processSignup
// and finishSignup (Kotlin); devices only step in after their 2 minute grace period, or when a
// claim of ours is older than 5 minutes.
import * as logger from "firebase-functions/logger";
import type { DocumentReference, Firestore } from "firebase-admin/firestore";
import type { Storage } from "firebase-admin/storage";
import { claim, institutionSetting, isStale, minimalProjection, portalEnabled, sha256, touchStatus } from "./common";
import { volunteerId } from "./nanoid";
import { parseSignup, type SignupSubmission } from "./validate";

type PhotoBucket = ReturnType<Storage["bucket"]>;

type Data = Record<string, unknown>;
type Outcome = "CREATED" | "LINKED" | "ALREADY_LINKED" | "INVALID";

const STATUS_SUBMITTED = "SUBMITTED";
/** Same cap as the Storage rules for the signup upload. */
const MAX_PHOTO_BYTES = 3 * 1024 * 1024;

const isClaimable = (d: Data): boolean => d.status === STATUS_SUBMITTED || isStale(d);
const isBlank = (v: unknown): boolean => typeof v !== "string" || v.trim() === "";

export const signupPhotoPath = (orgId: string, formId: string): string =>
  `orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg`;
export const volunteerPhotoPath = (orgId: string, id: string): string =>
  `orgs/${orgId.trim()}/profilePhotos/volunteers/${id.trim()}.jpg`;
export const portalPhotoPath = (orgId: string, uid: string): string => `orgs/${orgId}/portalPhotos/${uid}.jpg`;

/**
 * Handles one write of `orgs/{orgId}/volunteerSignupForms/{formId}`. The trigger also fires on
 * our own claim and delete, and on device claims: anything but a fresh answer or an abandoned
 * claim is ignored before reading or writing anything.
 */
export async function onSignupDocument(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  formId: string,
  current: Data | undefined,
): Promise<void> {
  if (current === undefined || !isClaimable(current)) return;
  if (!(await portalEnabled(db, orgId))) {
    logger.info("signup skipped", { orgId, formId, reason: "PORTAL_OFF" });
    return;
  }
  const ref = db.doc(`orgs/${orgId}/volunteerSignupForms/${formId}`);
  const claimed = await claim(ref, isClaimable);
  if (claimed === null) return;
  try {
    const outcome = await processClaimed(db, bucket, orgId, formId, ref, claimed);
    await touchStatus(db, orgId);
    logger.info("signup processed", { orgId, formId, reason: outcome });
  } catch (e) {
    // Left in PROCESSING: a device takes it over once the claim is 5 minutes old.
    // Only the error code is logged; messages can quote document content.
    const code = (e as { code?: unknown })?.code;
    logger.error("signup failed", { orgId, formId, reason: "FAILED", code: code === undefined ? "" : String(code) });
  }
}

async function processClaimed(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  formId: string,
  ref: DocumentReference,
  claimed: Data,
): Promise<Outcome> {
  // Validated as the answer it was, whatever claim it carried.
  const submission = parseSignup(formId, { ...claimed, status: STATUS_SUBMITTED }, orgId);
  if (submission === null) {
    // Unreadable answer: drop it rather than retrying forever.
    await finish(bucket, ref, orgId, formId);
    return "INVALID";
  }
  const photo = await readSignupPhoto(db, bucket, orgId, submission.photoPath);
  const volunteers = await db
    .collection(`orgs/${orgId}/volunteers`)
    .select("email", "portalUid", "profilePhotoPath", "profilePhotoUrl")
    .get();
  let outcome: Outcome;
  if (volunteers.docs.some((d) => d.get("portalUid") === submission.uid)) {
    outcome = "ALREADY_LINKED";
  } else {
    const sameEmail = volunteers.docs.find(
      (d) => typeof d.get("email") === "string" &&
        (d.get("email") as string).trim().toLowerCase() === submission.email &&
        isBlank(d.get("portalUid")),
    );
    if (sameEmail !== undefined) {
      // Same person already on the roster: link instead of creating a duplicate profile.
      // Reading the portal still requires verifying this e-mail address, so nothing is published.
      await link(db, bucket, orgId, sameEmail.ref, submission, photo);
      outcome = "LINKED";
    } else {
      await create(db, bucket, orgId, submission, photo);
      outcome = "CREATED";
    }
  }
  await finish(bucket, ref, orgId, formId);
  return outcome;
}

/** The signup photo, only when profile photos are on and the file is a JPEG within the size cap. */
async function readSignupPhoto(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  photoPath: string,
): Promise<Buffer | null> {
  // parseSignup only ever returns this form's own path, or "".
  if (photoPath === "") return null;
  if ((await institutionSetting(db, orgId, "profile_photos_enabled")).trim().toLowerCase() !== "true") return null;
  const file = bucket.file(photoPath);
  const [exists] = await file.exists();
  if (!exists) return null;
  const [meta] = await file.getMetadata();
  if (meta.contentType !== "image/jpeg" || Number(meta.size) >= MAX_PHOTO_BYTES) return null;
  const [bytes] = await file.download();
  const isJpeg = bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  return isJpeg && bytes.length < MAX_PHOTO_BYTES ? bytes : null;
}

/** New bytes and metadata: no download token of the uploader's file is carried over. */
async function saveJpeg(bucket: PhotoBucket, path: string, bytes: Buffer): Promise<void> {
  await bucket.file(path).save(bytes, { contentType: "image/jpeg", resumable: false });
}

async function link(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  ref: DocumentReference,
  submission: SignupSubmission,
  photo: Buffer | null,
): Promise<void> {
  const linked = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    // Linked by someone else since the scan: leave the answer for a later retry.
    if (!snap.exists || !isBlank(snap.get("portalUid"))) return null;
    tx.update(ref, { portalEnabled: true, portalUid: submission.uid, lastModified: Date.now() });
    return snap.data() as Data;
  });
  if (linked === null) throw Object.assign(new Error("link conflict"), { code: "LINK_CONFLICT" });
  // Only a roster entry without any photo gets the one from the form.
  if (photo === null || !isBlank(linked.profilePhotoPath) || !isBlank(linked.profilePhotoUrl)) return;
  const path = volunteerPhotoPath(orgId, ref.id);
  await saveJpeg(bucket, path, photo);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isBlank(snap.get("profilePhotoPath")) || !isBlank(snap.get("profilePhotoUrl"))) return;
    tx.update(ref, { profilePhotoPath: path, lastModified: Date.now() });
  });
}

async function create(
  db: Firestore,
  bucket: PhotoBucket,
  orgId: string,
  submission: SignupSubmission,
  photo: Buffer | null,
): Promise<void> {
  const id = volunteerId();
  let profilePhotoPath = "";
  if (photo !== null) {
    profilePhotoPath = volunteerPhotoPath(orgId, id);
    await saveJpeg(bucket, profilePhotoPath, photo);
    await saveJpeg(bucket, portalPhotoPath(orgId, submission.uid), photo);
  }
  const lastName = submission.lastName.toUpperCase();
  const now = Date.now();
  // Exactly the keys of GitLiveFirestoreGateway.volunteerToMap; blank photo fields are omitted.
  const volunteer: Data = {
    id,
    name: submission.firstName,
    lastNameAbbreviation: lastName,
    email: submission.email,
    phone: submission.phone,
    dateOfBirth: submission.dateOfBirth,
    gender: submission.gender,
    currentRank: null,
    isActive: true,
    lastShiftDate: null,
    lastModified: now,
    nfcCardUid: "",
    nfcCardUidHash: "",
    isAdmin: false,
    ...(profilePhotoPath !== "" ? { profilePhotoPath } : {}),
    portalEnabled: true,
    portalUid: submission.uid,
  };
  const [currency, association] = await Promise.all([
    institutionSetting(db, orgId, "currency_code"),
    associationName(db, orgId),
  ]);
  const projection = minimalProjection({
    firstName: submission.firstName,
    lastName,
    email: submission.email,
    phone: submission.phone,
    dateOfBirth: submission.dateOfBirth,
    gender: submission.gender,
    photoVersion: profilePhotoPath !== "" ? sha256(profilePhotoPath).slice(0, 16) : "",
    currency: currency.trim().toUpperCase(),
    association,
  }, now);
  const batch = db.batch();
  batch.create(db.doc(`orgs/${orgId}/volunteers/${id}`), volunteer);
  batch.set(db.doc(`orgs/${orgId}/volunteerPortal/${submission.uid}`), projection);
  await batch.commit();
}

/** What devices publish as the portal title: the branding, else the e-mail association name. */
async function associationName(db: Firestore, orgId: string): Promise<string> {
  const branding = await db.doc(`orgs/${orgId}/portalBranding/default`).get();
  const name = branding.exists ? branding.get("associationName") : undefined;
  if (typeof name === "string" && name.trim() !== "") return name;
  return institutionSetting(db, orgId, "email_association_name");
}

/** Removes the form's own upload, if any, then the answer itself. */
async function finish(bucket: PhotoBucket, ref: DocumentReference, orgId: string, formId: string): Promise<void> {
  await bucket.file(signupPhotoPath(orgId, formId)).delete({ ignoreNotFound: true }).catch(() => undefined);
  await ref.delete();
}
