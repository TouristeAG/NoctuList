// Shared by every portal trigger. Mirrors the device side (VolunteerPortalService, PortalProjection).
import { createHash } from "node:crypto";
import type { DocumentReference, Firestore } from "firebase-admin/firestore";
import { kotlinTrim } from "./validate";

export const SERVER_VERSION = "1.0.0";
export const STATUS_PROCESSING = "PROCESSING";
export const PROJECTION_SCHEMA = 2;
/** GuestPortalProjectionBuilder.SCHEMA. */
export const GUEST_PROJECTION_SCHEMA = 1;
const MAX_LABEL = 80;

type Data = Record<string, unknown>;

export const sha256 = (text: string): string => createHash("sha256").update(text, "utf8").digest("hex");

/** Same reading as the rules' portalOn: the switch is on only when explicitly "true". */
export async function portalEnabled(db: Firestore, orgId: string): Promise<boolean> {
  const snap = await db.doc(`orgs/${orgId}/institutionSettings/volunteer_portal_enabled`).get();
  const value = snap.exists ? snap.get("value") : undefined;
  return value === "true" || value === true;
}

/**
 * `guest_portal_enabled` under the portal switch, as the rules' guestPortalOn: the permanent
 * guests' space. Volunteers are never affected by it.
 */
export async function guestPortalEnabled(db: Firestore, orgId: string): Promise<boolean> {
  if (!(await portalEnabled(db, orgId))) return false;
  return (await institutionSetting(db, orgId, "guest_portal_enabled")) === "true";
}

/** `temp_guest_portal_enabled` (the portal switch is checked by the caller). */
export async function temporaryGuestPortalEnabled(db: Firestore, orgId: string): Promise<boolean> {
  return (await institutionSetting(db, orgId, "temp_guest_portal_enabled")) === "true";
}

/** stringOf (FirestoreChangeApplier): null is "", other values are their text. */
export const stringOf = (v: unknown): string =>
  v === null || v === undefined ? "" : typeof v === "string" ? v : String(v);

/** boolOf (FirestoreChangeApplier): booleans, "true" in any case, non-zero numbers. */
export function boolOf(v: unknown): boolean {
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v.toLowerCase() === "true";
  if (typeof v === "number") return Number.isFinite(v) && Math.trunc(v) !== 0;
  return false;
}

/** Kotlin `isBlank()` of a Firestore value. */
export const isBlankValue = (v: unknown): boolean => kotlinTrim(stringOf(v)) === "";

/** A document read whole or through a query. */
export type Fields = { get(field: string): unknown };

/** Guest.isPermanentPortalGuest (Kotlin): neither a temporary guest nor a volunteer benefit. */
export const isPermanentGuest = (d: Fields): boolean =>
  !boolOf(d.get("isTemporaryGuest")) && !boolOf(d.get("isVolunteerBenefit"));

/**
 * guestOwner (Kotlin): the published permanent guest of [uid], the most recently modified one,
 * as GuestPortalPublisher picks it. Null when there is none.
 */
export function guestOwnerOf<T extends Fields>(docs: readonly T[], uid: string): T | null {
  let best: T | null = null;
  let bestAt = -Infinity;
  for (const d of docs) {
    if (!isPermanentGuest(d) || !boolOf(d.get("portalEnabled")) || stringOf(d.get("portalUid")) !== uid) continue;
    const at = millisOf(d.get("lastModified")) ?? 0;
    // maxByOrNull keeps the first of equal values.
    if (best === null || at > bestAt) {
      best = d;
      bestAt = at;
    }
  }
  return best;
}

/** Plain string value of `institutionSettings/{key}`, or "" when unset. */
export async function institutionSetting(db: Firestore, orgId: string, key: string): Promise<string> {
  const snap = await db.doc(`orgs/${orgId}/institutionSettings/${key}`).get();
  const value = snap.exists ? snap.get("value") : undefined;
  return typeof value === "string" ? value : value === true ? "true" : "";
}

/** What devices publish as the portal title: the branding, else the e-mail association name. */
export async function associationName(db: Firestore, orgId: string): Promise<string> {
  const branding = await db.doc(`orgs/${orgId}/portalBranding/default`).get();
  const name = branding.exists ? branding.get("associationName") : undefined;
  if (typeof name === "string" && name.trim() !== "") return name;
  return institutionSetting(db, orgId, "email_association_name");
}

/** firestoreMillisOf (Kotlin): numbers, numeric strings, Dates and Timestamps; null otherwise. */
export function millisOf(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === "number") return Number.isFinite(value) ? Math.trunc(value) : null;
  if (typeof value === "string") {
    const n = Number(value);
    return value.trim() !== "" && Number.isFinite(n) ? Math.trunc(n) : null;
  }
  if (value instanceof Date) return value.getTime();
  const toMillis = (value as { toMillis?: unknown }).toMillis;
  if (typeof toMillis === "function") return (toMillis as () => number).call(value);
  return null;
}

/**
 * Same protocol as GitLiveFirestoreGateway.claimDocument: in one transaction, read the document,
 * and only if it is still claimable mark it PROCESSING by the server. Returns the data as it was
 * before the claim, or null when someone else holds it or it is gone.
 */
export async function claim(ref: DocumentReference, isClaimable: (d: Data) => boolean): Promise<Data | null> {
  return ref.firestore.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const data = snap.data() as Data;
    if (!isClaimable(data)) return null;
    tx.set(ref, { status: STATUS_PROCESSING, processingAt: Date.now(), processingDevice: "server" }, { merge: true });
    return data;
  });
}

/** `portalServer/status`, read by the app settings to show that automatic processing is on. */
export async function touchStatus(db: Firestore, orgId: string): Promise<void> {
  await db.doc(`orgs/${orgId}/portalServer/status`).set({ version: SERVER_VERSION, lastRunAt: Date.now() });
}

export interface MinimalProjectionInput {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  dateOfBirth: string;
  gender: string;
  /** First 16 hex characters of sha256(photo path), or "" without a published photo. */
  photoVersion: string;
  currency: string;
  association: string;
}

export interface Projection {
  schema: number;
  payload: string;
  contentHash: string;
  updatedAt: number;
}

/**
 * The first page of a brand-new volunteer, in the schema 2 format of PortalProjectionBuilder:
 * no shifts, an inactive timeline without benefits, an empty wallet. Never carries the volunteer id.
 * A device republishes the full page as soon as it sees the volunteer.
 */
export function minimalProjection(v: MinimalProjectionInput, now = Date.now()): Projection {
  const hasPhoto = v.photoVersion !== "";
  // Key order matters for the hash and follows PortalProjectionBuilder.build.
  const payload = JSON.stringify({
    firstName: v.firstName.trim(),
    lastName: v.lastName.trim(),
    email: v.email.trim(),
    phone: v.phone.trim(),
    dateOfBirth: v.dateOfBirth.trim(),
    gender: v.gender,
    hasPhoto,
    photoVersion: hasPhoto ? v.photoVersion : "",
    shifts: [],
    olderShifts: 0,
    lastShiftFloor: 0,
    timeline: [{ from: 0, active: false, summary: null, benefits: [], uniqueEntriesRemaining: null }],
    wallet: { balance: 0, currency: v.currency.trim() || "CHF", transfers: [] },
    // A new volunteer is never an admin, so only a missing e-mail forbids the QR mail.
    qrEmailAllowed: v.email.trim() !== "",
    mustSetPassword: false,
    association: v.association.trim().slice(0, MAX_LABEL),
  });
  return { schema: PROJECTION_SCHEMA, payload, contentHash: sha256(payload), updatedAt: now };
}

export interface MinimalGuestProjectionInput {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  venueName: string;
  /** First 16 hex characters of sha256(photo path), or "" without a published photo. */
  photoVersion: string;
  currency: string;
  association: string;
  /** googleLoginOn (Kotlin): Google sign-in on with an account rule that admits someone. */
  googleLinkAllowed: boolean;
}

/**
 * The first page of a brand-new permanent guest, in the format of GuestPortalProjectionBuilder:
 * no invitations, no discount, an empty wallet, no Google link. Never carries the nanoId.
 * A device republishes the full page as soon as it sees the guest.
 */
export function minimalGuestProjection(g: MinimalGuestProjectionInput, now = Date.now()): Projection {
  const hasPhoto = g.photoVersion !== "";
  // Key order matters for the hash and follows GuestPortalProjectionBuilder.build.
  const payload = JSON.stringify({
    firstName: g.firstName.trim(),
    lastName: g.lastName.trim(),
    email: g.email.trim(),
    phone: g.phone.trim(),
    hasPhoto,
    photoVersion: hasPhoto ? g.photoVersion : "",
    venueName: g.venueName.trim().slice(0, MAX_LABEL),
    invitations: 0,
    barDiscountPercent: 0,
    wallet: { balance: 0, currency: g.currency.trim() || "CHF", transfers: [] },
    // A new guest is never an admin, so only a missing e-mail forbids the QR mail.
    qrEmailAllowed: g.email.trim() !== "",
    mustSetPassword: false,
    association: g.association.trim().slice(0, MAX_LABEL),
    googleEmail: "",
    googleLinkedBy: "",
    googleLinkAvailable: g.googleLinkAllowed,
  });
  return { schema: GUEST_PROJECTION_SCHEMA, payload, contentHash: sha256(payload), updatedAt: now };
}

/**
 * The published page with the new profile fields, or null when there is none or it cannot be
 * read (a device republishes it anyway once the roster entry changes). Key order is kept.
 */
export function patchedProjection(data: Data | undefined, fields: Data, now: number): Data | null {
  if (data === undefined || typeof data.payload !== "string") return null;
  let payload: unknown;
  try {
    payload = JSON.parse(data.payload);
  } catch {
    return null;
  }
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return null;
  const text = JSON.stringify({ ...(payload as Data), ...fields });
  return { payload: text, contentHash: sha256(text), updatedAt: now };
}
