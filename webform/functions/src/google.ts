// Google account links: `portalGoogleClaims/{googleUid}` written by the portal page after a Google
// sign-in. Same decisions as PortalGoogleLinkProcessor and the Google region of
// VolunteerPortalService (Kotlin); devices only step in after their 2 minute grace period.
// Holders are volunteers and, while the guest space is on, permanent guests; holders sharing a
// portal uid are one person, and a link is written on every holder of the account.
import * as logger from "firebase-functions/logger";
import type { DocumentReference, DocumentSnapshot, Firestore, Transaction } from "firebase-admin/firestore";
import {
  SERVER_SOURCE,
  boolOf,
  claim,
  guestPortalEnabled,
  institutionSetting,
  isPermanentGuest,
  millisOf,
  patchedProjection,
  portalEnabled,
  sha256,
  stringOf,
  touchStatus,
  type Fields,
} from "./common";
import { kotlinTrim, parseGoogleClaim, parseRequest, type PortalGoogleClaim, type PortalRequest } from "./validate";

type Data = Record<string, unknown>;

export type GoogleReason =
  | ""
  | "INVALID"
  | "DOMAIN"
  | "NO_MATCH"
  | "AMBIGUOUS"
  | "CODE"
  | "ALREADY_LINKED"
  | "TAKEN"
  | "SAVE_FAILED";

interface GoogleResult {
  status: "DONE" | "REJECTED";
  reason: GoogleReason;
}

export const LINKED_BY_ADMIN = "ADMIN";
export const LINKED_BY_VOLUNTEER = "VOLUNTEER";
/** PortalGoogleLinkProcessor.LINK_CODE_VALIDITY_MS: a link code is accepted this long after `requestedAt`. */
export const LINK_CODE_VALIDITY_MS = 10 * 60 * 1000;

const STATUS_PENDING = "PENDING";
const DONE: GoogleResult = { status: "DONE", reason: "" };
const rejected = (reason: Exclude<GoogleReason, "">): GoogleResult => ({ status: "REJECTED", reason });

/** Only pending claims; a PROCESSING one, however old, is left to devices (as in requests.ts). */
const isClaimable = (d: Data): boolean => d.status === STATUS_PENDING;

export type HolderKind = "VOLUNTEER" | "GUEST";

/**
 * PortalHolder (Kotlin): the fields of a volunteer or permanent guest document the decisions need.
 * [id] is the volunteer id or the guest nanoId (the document id); it never reaches a log.
 */
export interface PortalHolderDoc {
  kind: HolderKind;
  id: string;
  /** The roster address, the one the holder's e-mail account uses. */
  email: string;
  portalEnabled: boolean;
  portalUid: string;
  portalGoogleEmail: string;
  portalGoogleUid: string;
  portalGoogleLinkedBy: string;
}

export type GoogleDecision =
  /** [openedUid]: the e-mail account the server found for an access with no portalUid yet. */
  | { kind: "LINK"; holder: PortalHolderDoc; linkedBy: string; openedUid?: string }
  | { kind: "ALREADY_LINKED" }
  | { kind: "REJECT"; reason: Exclude<GoogleReason, "" | "INVALID" | "SAVE_FAILED"> };

const isBlank = (s: string): boolean => kotlinTrim(s) === "";

export function holderOf(kind: HolderKind, id: string, d: Fields): PortalHolderDoc {
  return {
    kind,
    id,
    email: stringOf(d.get("email")),
    portalEnabled: boolOf(d.get("portalEnabled")),
    portalUid: stringOf(d.get("portalUid")),
    portalGoogleEmail: stringOf(d.get("portalGoogleEmail")),
    portalGoogleUid: stringOf(d.get("portalGoogleUid")),
    portalGoogleLinkedBy: stringOf(d.get("portalGoogleLinkedBy")),
  };
}

/** Consumer Google addresses, never an association domain (PortalGoogleLinkProcessor, rules). */
const CONSUMER_DOMAINS = ["gmail.com", "googlemail.com"];

/**
 * FirebaseEmailDomainPolicy.parseStoredList: lowercase, trimmed, with a dot, no duplicates; then
 * PortalGoogleLinkProcessor.usableDomains: gmail.com and googlemail.com dropped.
 */
export function parseDomains(raw: string): string[] {
  const out: string[] = [];
  for (const part of raw.split(/[,;\n\r]/)) {
    // normalizeDomain (Kotlin): trim, lowercase, one leading "@" off, trim, trailing dots off.
    let domain = kotlinTrim(part).toLowerCase();
    if (domain.startsWith("@")) domain = domain.slice(1);
    domain = kotlinTrim(domain).replace(/\.+$/, "");
    if (domain !== "" && domain.includes(".") && !out.includes(domain) && !CONSUMER_DOMAINS.includes(domain)) {
      out.push(domain);
    }
  }
  return out;
}

/** PortalGoogleLinkProcessor.accountAllowed: any address with a domain when [anyAccount], else domainAllowed. */
export function accountAllowed(email: string, domains: readonly string[], anyAccount: boolean): boolean {
  if (!anyAccount) return domainAllowed(email, domains);
  const clean = kotlinTrim(email);
  const at = clean.lastIndexOf("@");
  return at >= 0 && clean.slice(at + 1) !== "";
}

export function domainAllowed(email: string, domains: readonly string[]): boolean {
  const clean = kotlinTrim(email).toLowerCase();
  const at = clean.lastIndexOf("@");
  const domain = at < 0 ? "" : clean.slice(at + 1);
  return domain !== "" && !CONSUMER_DOMAINS.includes(domain) && domains.some((d) => kotlinTrim(d).toLowerCase() === domain);
}

export function sameAddress(a: string, b: string): boolean {
  const left = kotlinTrim(a).toLowerCase();
  return left !== "" && left === kotlinTrim(b).toLowerCase();
}

/** True when the alias target is a holder that still carries [googleUid]; anything else is stale. */
export function isAliasValid(googleUid: string, aliasTarget: string | null, holders: readonly PortalHolderDoc[]): boolean {
  return (
    aliasTarget !== null &&
    !isBlank(aliasTarget) &&
    holders.some((v) => v.portalUid === aliasTarget && v.portalGoogleUid === googleUid)
  );
}

const hasPortal = (v: PortalHolderDoc): boolean => v.portalEnabled && !isBlank(v.portalUid);
const sameHolder = (a: PortalHolderDoc, b: PortalHolderDoc): boolean => a.kind === b.kind && a.id === b.id;

/** PortalGoogleLinkProcessor.accountOf: holders with open access on the portal account [uid]. */
export function accountOf(uid: string, holders: readonly PortalHolderDoc[]): PortalHolderDoc[] {
  return isBlank(uid) ? [] : holders.filter((h) => h.portalEnabled && h.portalUid === uid);
}

/** PortalGoogleLinkProcessor.primary: the volunteer when the account has one, else the first holder. */
export function primary(account: readonly PortalHolderDoc[]): PortalHolderDoc {
  return account.find((h) => h.kind === "VOLUNTEER") ?? account[0];
}

/**
 * PortalGoogleLinkProcessor.adminMatch. A match already linked by the volunteer keeps VOLUNTEER,
 * so the volunteer can still remove it from the portal.
 */
export function adminMatch(
  c: PortalGoogleClaim,
  holders: readonly PortalHolderDoc[],
  domains: readonly string[],
  aliasTarget: string | null,
  anyAccount = false,
): GoogleDecision {
  if (!accountAllowed(c.email, domains, anyAccount)) return { kind: "REJECT", reason: "DOMAIN" };
  if (isAliasValid(c.googleUid, aliasTarget, holders)) return { kind: "ALREADY_LINKED" };
  const candidates = holders.filter((v) => hasPortal(v) && sameAddress(v.portalGoogleEmail, c.email));
  // One person per portal account; the volunteer answers for it when there is one.
  const byUid = new Map<string, PortalHolderDoc[]>();
  for (const h of candidates) byUid.set(h.portalUid, [...(byUid.get(h.portalUid) ?? []), h]);
  const people = [...byUid.values()].map(primary);
  if (people.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  if (people.length > 1) return { kind: "REJECT", reason: "AMBIGUOUS" };
  const match = people[0];
  const linkedBy = match.portalGoogleLinkedBy === LINKED_BY_VOLUNTEER ? LINKED_BY_VOLUNTEER : LINKED_BY_ADMIN;
  return { kind: "LINK", holder: match, linkedBy };
}

/** The holder's e-mail account in Firebase Auth, or null when the address has none. */
export interface EmailAccount {
  uid: string;
  /** The account signs in with a password, as a portal account does. */
  password: boolean;
}

export type AccountLookup = (email: string) => Promise<EmailAccount | null>;

/**
 * Server only (devices cannot look up an account): an admin match on a volunteer access that is
 * open but not tied to an account yet. Reopening an access, or opening one for an address that
 * already has an account, leaves portalUid empty until the volunteer signs in with that e-mail
 * (CLAIM). The admin typed this Google address on this volunteer, so the server ties the access to
 * the account of the roster address itself, with the same uniqueness checks as CLAIM.
 */
export async function adminMatchUnopened(
  c: PortalGoogleClaim,
  holders: readonly PortalHolderDoc[],
  findAccount: AccountLookup,
): Promise<GoogleDecision> {
  const volunteers = holders.filter((h) => h.kind === "VOLUNTEER");
  const candidates = volunteers.filter(
    (v) => v.portalEnabled && isBlank(v.portalUid) && sameAddress(v.portalGoogleEmail, c.email),
  );
  if (candidates.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  if (candidates.length > 1) return { kind: "REJECT", reason: "AMBIGUOUS" };
  const match = candidates[0];
  const email = kotlinTrim(match.email).toLowerCase();
  if (email === "") return { kind: "REJECT", reason: "NO_MATCH" };
  // handleClaim: exactly one volunteer with portal access may use this address.
  if (volunteers.some((v) => v.id !== match.id && v.portalEnabled && sameAddress(v.email, email))) {
    return { kind: "REJECT", reason: "NO_MATCH" };
  }
  const account = await findAccount(email);
  if (account === null || !account.password || isBlank(account.uid)) return { kind: "REJECT", reason: "NO_MATCH" };
  if (holders.some((h) => h.portalUid === account.uid)) return { kind: "REJECT", reason: "NO_MATCH" };
  const linkedBy = match.portalGoogleLinkedBy === LINKED_BY_VOLUNTEER ? LINKED_BY_VOLUNTEER : LINKED_BY_ADMIN;
  return { kind: "LINK", holder: match, linkedBy, openedUid: account.uid };
}

/** PortalGoogleLinkProcessor.linkCode; [requestedAtMillis] is the request's `requestedAt`. */
export function linkCode(
  c: PortalGoogleClaim,
  request: PortalRequest | null,
  requestedAtMillis: number | null,
  now: number,
  holders: readonly PortalHolderDoc[],
  domains: readonly string[],
  aliasTarget: string | null,
  anyAccount = false,
): GoogleDecision {
  const codeOk =
    request !== null &&
    request.type === "GOOGLE_LINK" &&
    request.status === STATUS_PENDING &&
    c.code !== "" &&
    request.codeHash === sha256(c.code) &&
    requestedAtMillis !== null &&
    now - requestedAtMillis < LINK_CODE_VALIDITY_MS;
  if (!codeOk) return { kind: "REJECT", reason: "CODE" };
  const account = accountOf(request.uid, holders);
  if (account.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  const owner = primary(account);
  // Retry after the link was already written (stopped before answering).
  if (aliasTarget === owner.portalUid && account.some((h) => h.portalGoogleUid === c.googleUid)) {
    return { kind: "ALREADY_LINKED" };
  }
  if (account.some((h) => !isBlank(h.portalGoogleEmail))) return { kind: "REJECT", reason: "ALREADY_LINKED" };
  if (!accountAllowed(c.email, domains, anyAccount)) return { kind: "REJECT", reason: "DOMAIN" };
  const others = holders.filter((h) => !account.some((a) => sameHolder(a, h)));
  if (others.some((v) => v.portalGoogleUid === c.googleUid)) return { kind: "REJECT", reason: "TAKEN" };
  if (others.some((v) => sameAddress(v.portalGoogleEmail, c.email))) return { kind: "REJECT", reason: "TAKEN" };
  return { kind: "LINK", holder: owner, linkedBy: LINKED_BY_VOLUNTEER };
}

/** PortalGoogleLinkProcessor.canUnlink: null when allowed. */
export function canUnlink(owner: PortalHolderDoc | null): "NO_ACCESS" | "ADMIN_LINK" | null {
  if (owner === null) return "NO_ACCESS";
  if (owner.portalGoogleLinkedBy === LINKED_BY_ADMIN) return "ADMIN_LINK";
  return null;
}

const errorCode = (e: unknown): string => {
  const code = (e as { code?: unknown })?.code;
  return code === undefined ? "" : String(code);
};

const COLLECTION: Record<HolderKind, string> = { VOLUNTEER: "volunteers", GUEST: "guests" };
const PAGE: Record<HolderKind, string> = { VOLUNTEER: "volunteerPortal", GUEST: "guestPortal" };
const holderRef = (db: Firestore, orgId: string, h: { kind: HolderKind; id: string }) =>
  db.doc(`orgs/${orgId}/${COLLECTION[h.kind]}/${h.id}`);
const aliasRef = (db: Firestore, orgId: string, googleUid: string) =>
  db.doc(`orgs/${orgId}/portalGoogleAccounts/${googleUid}`);
const pageRef = (db: Firestore, orgId: string, kind: HolderKind, portalUid: string) =>
  db.doc(`orgs/${orgId}/${PAGE[kind]}/${portalUid}`);

const GOOGLE_FIELDS = ["email", "portalEnabled", "portalUid", "portalGoogleEmail", "portalGoogleUid", "portalGoogleLinkedBy"];
const GUEST_FIELDS = [...GOOGLE_FIELDS, "isTemporaryGuest", "isVolunteerBenefit", "lastModified"];

/**
 * googleHolders (Kotlin): every volunteer, plus the permanent guests while the guest space is on.
 * Guests come with their `lastModified`, so the guest page follows the guest the publisher shows.
 */
async function loadHolders(db: Firestore, orgId: string): Promise<PortalHolderDoc[]> {
  const [volunteers, guestSpace] = await Promise.all([
    db.collection(`orgs/${orgId}/volunteers`).select(...GOOGLE_FIELDS).get(),
    guestPortalEnabled(db, orgId),
  ]);
  const holders = volunteers.docs.map((d) => holderOf("VOLUNTEER", d.id, d));
  if (!guestSpace) return holders;
  const guests = await db.collection(`orgs/${orgId}/guests`).select(...GUEST_FIELDS).get();
  return [...holders, ...guestsByRecency(guests.docs.filter(isPermanentGuest)).map((d) => holderOf("GUEST", d.id, d))];
}

/** Most recently modified first (stable), so the first guest of an account is the published one. */
function guestsByRecency<T extends Fields>(docs: readonly T[]): T[] {
  return docs
    .map((d, i) => ({ d, i, at: millisOf(d.get("lastModified")) ?? 0 }))
    .sort((a, b) => b.at - a.at || a.i - b.i)
    .map((x) => x.d);
}

/** The Google sign-in option, as the rules read it (the portal switch is checked separately). */
async function googleOn(db: Firestore, orgId: string): Promise<boolean> {
  return (await institutionSetting(db, orgId, "volunteer_portal_google_enabled")) === "true";
}

/** `volunteer_portal_google_any_account`: every Google account may sign in, the domain list is ignored. */
async function anyAccountOn(db: Firestore, orgId: string): Promise<boolean> {
  return (await institutionSetting(db, orgId, "volunteer_portal_google_any_account")) === "true";
}

/** googleLoginOn (Kotlin): what the published page calls `googleLinkAvailable` before the address check. */
export async function googleLinkAllowed(db: Firestore, orgId: string): Promise<boolean> {
  const [on, any, domains] = await Promise.all([
    googleOn(db, orgId),
    anyAccountOn(db, orgId),
    institutionSetting(db, orgId, "volunteer_portal_google_domains"),
  ]);
  return on && (any || parseDomains(domains).length > 0);
}

/** The projection fields of PortalProjectionBuilder for the Google link. */
export function googleProjectionFields(portalGoogleEmail: string, linkedBy: string, allowed: boolean): Data {
  const googleEmail = kotlinTrim(portalGoogleEmail);
  return {
    googleEmail,
    googleLinkedBy: kotlinTrim(linkedBy),
    googleLinkAvailable: allowed && googleEmail === "",
  };
}

/**
 * Handles one write of `orgs/{orgId}/portalGoogleClaims/{googleUid}`. Anything but a pending claim
 * is ignored before reading or writing anything; with the portal or the Google option off the claim
 * waits. Turning the option back on does not wake it: a device (after its grace period) or a new
 * write of the claim by the page resumes it.
 */
export async function onGoogleClaimDocument(
  db: Firestore,
  orgId: string,
  googleUid: string,
  data: Data | undefined,
  findAccount: AccountLookup = async () => null,
): Promise<void> {
  if (data === undefined || !isClaimable(data)) return;
  let result: GoogleResult;
  try {
    if (!(await portalEnabled(db, orgId)) || !(await googleOn(db, orgId))) {
      logger.info("google claim skipped", { orgId, reason: "OPTION_OFF" });
      return;
    }
    const ref = db.doc(`orgs/${orgId}/portalGoogleClaims/${googleUid}`);
    const claimed = await claim(ref, isClaimable);
    if (claimed === null) return;
    result = await processClaim(db, orgId, googleUid, claimed, findAccount);
    await completeClaim(ref, result);
  } catch (e) {
    // Left in PROCESSING: a device takes it over once the claim is 5 minutes old.
    logger.error("google claim failed", { orgId, reason: "FAILED", code: errorCode(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger.error("server status not written", { orgId, code: errorCode(e) });
  }
  logger.info("google claim processed", { orgId, status: result.status, reason: result.reason });
}

/** completeClaim (Kotlin): answers the claim and blanks the one-time code, whatever the outcome. */
async function completeClaim(ref: DocumentReference, result: GoogleResult): Promise<void> {
  await ref.set({ status: result.status, reason: result.reason, processedAt: Date.now(), code: "" }, { merge: true });
}

async function processClaim(
  db: Firestore,
  orgId: string,
  googleUid: string,
  claimed: Data,
  findAccount: AccountLookup,
): Promise<GoogleResult> {
  const c = parseGoogleClaim(googleUid, claimed);
  if (c === null) return rejected("INVALID");
  const now = Date.now();
  const [holders, domainSetting, anyAccount, aliasSnap] = await Promise.all([
    loadHolders(db, orgId),
    institutionSetting(db, orgId, "volunteer_portal_google_domains"),
    anyAccountOn(db, orgId),
    aliasRef(db, orgId, googleUid).get(),
  ]);
  const domains = parseDomains(domainSetting);
  const rawTarget = aliasSnap.exists ? aliasSnap.get("volunteerUid") : undefined;
  let aliasTarget = typeof rawTarget === "string" ? rawTarget : null;
  if (aliasTarget !== null && !isAliasValid(googleUid, aliasTarget, holders)) {
    // Revoked, deleted or relinked volunteer: the alias must not keep answering for them.
    await aliasRef(db, orgId, googleUid).delete();
    aliasTarget = null;
  }
  let linkRequest: DocumentReference | null = null;
  let decision: GoogleDecision;
  if (c.type === "ADMIN_MATCH") {
    decision = adminMatch(c, holders, domains, aliasTarget, anyAccount);
    if (decision.kind === "REJECT" && decision.reason === "NO_MATCH") {
      decision = await adminMatchUnopened(c, holders, findAccount);
    }
  } else {
    const found = await db
      .collection(`orgs/${orgId}/portalRequests`)
      .where("type", "==", "GOOGLE_LINK")
      .where("codeHash", "==", sha256(c.code))
      .limit(1)
      .get();
    const doc = found.docs[0];
    linkRequest = doc?.ref ?? null;
    const request = doc !== undefined ? parseRequest(doc.id, doc.data()) : null;
    const requestedAt = doc !== undefined ? millisOf(doc.get("requestedAt")) : null;
    decision = linkCode(c, request, requestedAt, now, holders, domains, aliasTarget, anyAccount);
  }
  let result: GoogleResult;
  switch (decision.kind) {
    case "REJECT":
      return rejected(decision.reason);
    case "ALREADY_LINKED":
      result = DONE;
      break;
    case "LINK":
      // An access with no account yet is the matched volunteer alone (adminMatchUnopened).
      result = decision.openedUid !== undefined
        ? await writeLink(db, orgId, c, [decision.holder], decision.linkedBy, aliasTarget, decision.openedUid)
        : await writeLink(db, orgId, c, accountOf(decision.holder.portalUid, holders), decision.linkedBy, aliasTarget);
      break;
  }
  if (result.status === "DONE" && linkRequest !== null) {
    await linkRequest.set({ status: "DONE", reason: "", processedAt: Date.now() }, { merge: true });
  }
  return result;
}

const sameGoogleFields = (a: PortalHolderDoc, b: PortalHolderDoc): boolean =>
  a.portalEnabled === b.portalEnabled &&
  a.portalUid === b.portalUid &&
  a.portalGoogleEmail === b.portalGoogleEmail &&
  a.portalGoogleUid === b.portalGoogleUid &&
  a.portalGoogleLinkedBy === b.portalGoogleLinkedBy;

/** The address stored on [h] by a link: the one an admin typed is kept (`trim().ifBlank { email }`). */
const linkedAddress = (h: PortalHolderDoc, email: string): string =>
  isBlank(h.portalGoogleEmail) ? email : kotlinTrim(h.portalGoogleEmail);

/**
 * writeGoogleLink (Kotlin), in one transaction: the alias, the three fields and `lastModified` on
 * every holder of the account, and the published pages. A previous Google account of the account
 * loses its alias. Re-reads the holders and the alias: if any changed since the decision, nothing
 * is written and the claim is answered SAVE_FAILED.
 */
async function writeLink(
  db: Firestore,
  orgId: string,
  c: PortalGoogleClaim,
  account: readonly PortalHolderDoc[],
  linkedBy: string,
  aliasTarget: string | null,
  openedUid?: string,
): Promise<GoogleResult> {
  try {
    const allowed = await googleLinkAllowed(db, orgId);
    // The access was open without an account: it gets the one the server found (adminMatchUnopened).
    const portalUid = openedUid ?? account[0].portalUid;
    const previousUids = [...new Set(account.map((h) => h.portalGoogleUid))].filter(
      (u) => !isBlank(u) && u !== c.googleUid,
    );
    return await db.runTransaction(async (tx: Transaction) => {
      const alias = aliasRef(db, orgId, c.googleUid);
      const kinds = [...new Set(account.map((h) => h.kind))];
      const [aliasSnap, snaps, pages, previous] = await Promise.all([
        tx.get(alias),
        Promise.all(account.map((h) => tx.get(holderRef(db, orgId, h)))),
        Promise.all(kinds.map((k) => tx.get(pageRef(db, orgId, k, portalUid)))),
        Promise.all(previousUids.map((u) => tx.get(aliasRef(db, orgId, u)))),
      ]);
      const changed = snaps.some((snap, i) => !snap.exists || !sameGoogleFields(holderOf(account[i].kind, snap.id, snap), account[i]));
      if (changed) return rejected("SAVE_FAILED");
      if (openedUid !== undefined) {
        // Tied to another profile since the scan (handleClaim does the same check).
        const [v, g] = await Promise.all([
          tx.get(db.collection(`orgs/${orgId}/volunteers`).where("portalUid", "==", openedUid)),
          tx.get(db.collection(`orgs/${orgId}/guests`).where("portalUid", "==", openedUid)),
        ]);
        if (!v.empty || !g.empty) return rejected("SAVE_FAILED");
      }
      const target = aliasSnap.exists ? aliasSnap.get("volunteerUid") : undefined;
      if ((typeof target === "string" ? target : null) !== aliasTarget) return rejected("SAVE_FAILED");
      const now = Date.now();
      // Only an alias that still answers for this account.
      for (const p of previous) if (p.exists && p.get("volunteerUid") === portalUid) tx.delete(p.ref);
      tx.set(alias, { volunteerUid: portalUid, email: c.email, linkedBy, linkedAt: now });
      snaps.forEach((snap, i) => {
        tx.update(snap.ref, {
          portalGoogleEmail: linkedAddress(account[i], c.email),
          portalGoogleUid: c.googleUid,
          portalGoogleLinkedBy: linkedBy,
          ...(openedUid !== undefined ? { portalUid: openedUid } : {}),
          lastModified: Math.max(now, (millisOf(snap.get("lastModified")) ?? 0) + 1),
          sourceDeviceId: SERVER_SOURCE,
        });
      });
      // Each page shows the address of the profile it is built from (account order: volunteer
      // first, guests most recent first).
      kinds.forEach((kind, i) => {
        const shown = account.find((h) => h.kind === kind)!;
        const patch = patchedProjection(pages[i].data(), googleProjectionFields(linkedAddress(shown, c.email), linkedBy, allowed), now);
        if (patch !== null) tx.update(pages[i].ref, patch);
      });
      return DONE;
    });
  } catch (e) {
    logger.error("google link not saved", { orgId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}

/**
 * GOOGLE_UNLINK from the portal (processRequest and clearGoogleLink, Kotlin): only a link the
 * holder made. Any admin-held link on the account keeps it, whichever profile carries it. Clearing
 * reaches the account's permanent guests even with their space off (it never grants access).
 * Deletes the aliases, blanks the three fields and patches the pages in one transaction.
 */
export async function unlinkGoogle(
  db: Firestore,
  orgId: string,
  requestId: string,
  uid: string,
): Promise<"" | "NO_ACCESS" | "ADMIN_LINK" | "SAVE_FAILED"> {
  try {
    const [allowed, guestSpace] = await Promise.all([googleLinkAllowed(db, orgId), guestPortalEnabled(db, orgId)]);
    return await db.runTransaction(async (tx: Transaction) => {
      const [linkedVolunteers, linkedGuests] = await Promise.all([
        tx.get(db.collection(`orgs/${orgId}/volunteers`).where("portalUid", "==", uid)),
        tx.get(db.collection(`orgs/${orgId}/guests`).where("portalUid", "==", uid)),
      ]);
      const permanentGuests = guestsByRecency(linkedGuests.docs.filter(isPermanentGuest));
      const holders = [
        ...linkedVolunteers.docs.map((d) => holderOf("VOLUNTEER", d.id, d)),
        ...(guestSpace ? permanentGuests.map((d) => holderOf("GUEST", d.id, d)) : []),
      ];
      const account = accountOf(uid, holders);
      const accountGuests = account.length === 0 ? [] : permanentGuests;
      const everyone = [
        ...account,
        ...accountGuests.map((d) => holderOf("GUEST", d.id, d)).filter((g) => !account.some((a) => sameHolder(a, g))),
      ];
      const decider = everyone.find((h) => h.portalGoogleLinkedBy === LINKED_BY_ADMIN) ?? (account.length > 0 ? primary(account) : null);
      const reason = canUnlink(decider);
      if (reason !== null) return reason;
      const docs: DocumentSnapshot[] = [
        ...linkedVolunteers.docs.filter((d) => account.some((a) => a.kind === "VOLUNTEER" && a.id === d.id)),
        ...accountGuests,
      ];
      const pageKinds = [...new Set(everyone.map((h) => h.kind))];
      const pages = await Promise.all(pageKinds.map((k) => tx.get(pageRef(db, orgId, k, uid))));
      const now = Date.now();
      for (const googleUid of new Set(everyone.map((h) => h.portalGoogleUid))) {
        if (!isBlank(googleUid)) tx.delete(aliasRef(db, orgId, googleUid));
      }
      for (const doc of docs) {
        tx.update(doc.ref, {
          portalGoogleEmail: "",
          portalGoogleUid: "",
          portalGoogleLinkedBy: "",
          lastModified: Math.max(now, (millisOf(doc.get("lastModified")) ?? 0) + 1),
          sourceDeviceId: SERVER_SOURCE,
        });
      }
      pages.forEach((page) => {
        const patch = patchedProjection(page.data(), googleProjectionFields("", "", allowed), now);
        if (patch !== null) tx.update(page.ref, patch);
      });
      return "";
    });
  } catch (e) {
    logger.error("google unlink not saved", { orgId, requestId, code: errorCode(e) });
    return "SAVE_FAILED";
  }
}
