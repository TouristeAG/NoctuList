// Google account links: `portalGoogleClaims/{googleUid}` written by the portal page after a Google
// sign-in. Same decisions as PortalGoogleLinkProcessor and the Google region of
// VolunteerPortalService (Kotlin); devices only step in after their 2 minute grace period.
import * as logger from "firebase-functions/logger";
import type { DocumentReference, Firestore, Transaction } from "firebase-admin/firestore";
import { SERVER_SOURCE, claim, institutionSetting, millisOf, patchedProjection, portalEnabled, sha256, touchStatus } from "./common";
import { kotlinTrim, parseGoogleClaim, parseRequest, type PortalGoogleClaim, type PortalRequest } from "./validate";

type Data = Record<string, unknown>;
type Fields = { get(field: string): unknown };

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

/** The fields of a volunteer document the decisions need. */
export interface GoogleVolunteer {
  id: string;
  /** The roster address, the one the volunteer's e-mail account uses. */
  email: string;
  portalEnabled: boolean;
  portalUid: string;
  portalGoogleEmail: string;
  portalGoogleUid: string;
  portalGoogleLinkedBy: string;
}

export type GoogleDecision =
  /** [openedUid]: the e-mail account the server found for an access with no portalUid yet. */
  | { kind: "LINK"; volunteer: GoogleVolunteer; linkedBy: string; openedUid?: string }
  | { kind: "ALREADY_LINKED" }
  | { kind: "REJECT"; reason: Exclude<GoogleReason, "" | "INVALID" | "SAVE_FAILED"> };

const stringOf = (v: unknown): string => (v === null || v === undefined ? "" : typeof v === "string" ? v : String(v));

function boolOf(v: unknown): boolean {
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v.toLowerCase() === "true";
  if (typeof v === "number") return Number.isFinite(v) && Math.trunc(v) !== 0;
  return false;
}

const isBlank = (s: string): boolean => kotlinTrim(s) === "";

export function googleVolunteerOf(id: string, d: Fields): GoogleVolunteer {
  return {
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

/** True when the alias target is a volunteer that still carries [googleUid]; anything else is stale. */
export function isAliasValid(googleUid: string, aliasTarget: string | null, volunteers: readonly GoogleVolunteer[]): boolean {
  return (
    aliasTarget !== null &&
    !isBlank(aliasTarget) &&
    volunteers.some((v) => v.portalUid === aliasTarget && v.portalGoogleUid === googleUid)
  );
}

const hasPortal = (v: GoogleVolunteer): boolean => v.portalEnabled && !isBlank(v.portalUid);

/**
 * PortalGoogleLinkProcessor.adminMatch. A match already linked by the volunteer keeps VOLUNTEER,
 * so the volunteer can still remove it from the portal.
 */
export function adminMatch(
  c: PortalGoogleClaim,
  volunteers: readonly GoogleVolunteer[],
  domains: readonly string[],
  aliasTarget: string | null,
  anyAccount = false,
): GoogleDecision {
  if (!accountAllowed(c.email, domains, anyAccount)) return { kind: "REJECT", reason: "DOMAIN" };
  if (isAliasValid(c.googleUid, aliasTarget, volunteers)) return { kind: "ALREADY_LINKED" };
  const candidates = volunteers.filter((v) => hasPortal(v) && sameAddress(v.portalGoogleEmail, c.email));
  if (candidates.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  if (candidates.length > 1) return { kind: "REJECT", reason: "AMBIGUOUS" };
  const match = candidates[0];
  const linkedBy = match.portalGoogleLinkedBy === LINKED_BY_VOLUNTEER ? LINKED_BY_VOLUNTEER : LINKED_BY_ADMIN;
  return { kind: "LINK", volunteer: match, linkedBy };
}

/** The volunteer's e-mail account in Firebase Auth, or null when the address has none. */
export interface EmailAccount {
  uid: string;
  /** The account signs in with a password, as a portal account does. */
  password: boolean;
}

export type AccountLookup = (email: string) => Promise<EmailAccount | null>;

/**
 * Server only (devices cannot look up an account): an admin match on an access that is open but
 * not tied to an account yet. Reopening an access, or opening one for an address that already has
 * an account, leaves portalUid empty until the volunteer signs in with that e-mail (CLAIM). The
 * admin typed this Google address on this volunteer, so the server ties the access to the account
 * of the roster address itself, with the same uniqueness checks as CLAIM.
 */
export async function adminMatchUnopened(
  c: PortalGoogleClaim,
  volunteers: readonly GoogleVolunteer[],
  findAccount: AccountLookup,
): Promise<GoogleDecision> {
  const candidates = volunteers.filter(
    (v) => v.portalEnabled && isBlank(v.portalUid) && sameAddress(v.portalGoogleEmail, c.email),
  );
  if (candidates.length === 0) return { kind: "REJECT", reason: "NO_MATCH" };
  if (candidates.length > 1) return { kind: "REJECT", reason: "AMBIGUOUS" };
  const match = candidates[0];
  const email = kotlinTrim(match.email).toLowerCase();
  if (email === "") return { kind: "REJECT", reason: "NO_MATCH" };
  // handleClaim: exactly one entry with portal access may use this address.
  if (volunteers.some((v) => v.id !== match.id && v.portalEnabled && sameAddress(v.email, email))) {
    return { kind: "REJECT", reason: "NO_MATCH" };
  }
  const account = await findAccount(email);
  if (account === null || !account.password || isBlank(account.uid)) return { kind: "REJECT", reason: "NO_MATCH" };
  if (volunteers.some((v) => v.portalUid === account.uid)) return { kind: "REJECT", reason: "NO_MATCH" };
  const linkedBy = match.portalGoogleLinkedBy === LINKED_BY_VOLUNTEER ? LINKED_BY_VOLUNTEER : LINKED_BY_ADMIN;
  return { kind: "LINK", volunteer: match, linkedBy, openedUid: account.uid };
}

/** PortalGoogleLinkProcessor.linkCode; [requestedAtMillis] is the request's `requestedAt`. */
export function linkCode(
  c: PortalGoogleClaim,
  request: PortalRequest | null,
  requestedAtMillis: number | null,
  now: number,
  volunteers: readonly GoogleVolunteer[],
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
  const owner = volunteers.find((v) => hasPortal(v) && v.portalUid === request.uid);
  if (owner === undefined) return { kind: "REJECT", reason: "NO_MATCH" };
  // Retry after the link was already written (stopped before answering).
  if (aliasTarget === owner.portalUid && owner.portalGoogleUid === c.googleUid) return { kind: "ALREADY_LINKED" };
  if (!isBlank(owner.portalGoogleEmail)) return { kind: "REJECT", reason: "ALREADY_LINKED" };
  if (!accountAllowed(c.email, domains, anyAccount)) return { kind: "REJECT", reason: "DOMAIN" };
  const others = volunteers.filter((v) => v.id !== owner.id);
  if (others.some((v) => v.portalGoogleUid === c.googleUid)) return { kind: "REJECT", reason: "TAKEN" };
  if (others.some((v) => sameAddress(v.portalGoogleEmail, c.email))) return { kind: "REJECT", reason: "TAKEN" };
  return { kind: "LINK", volunteer: owner, linkedBy: LINKED_BY_VOLUNTEER };
}

/** PortalGoogleLinkProcessor.canUnlink: null when allowed. */
export function canUnlink(owner: GoogleVolunteer | null): "NO_ACCESS" | "ADMIN_LINK" | null {
  if (owner === null) return "NO_ACCESS";
  if (owner.portalGoogleLinkedBy === LINKED_BY_ADMIN) return "ADMIN_LINK";
  return null;
}

const errorCode = (e: unknown): string => {
  const code = (e as { code?: unknown })?.code;
  return code === undefined ? "" : String(code);
};

const volunteersOf = (db: Firestore, orgId: string) => db.collection(`orgs/${orgId}/volunteers`);
const aliasRef = (db: Firestore, orgId: string, googleUid: string) =>
  db.doc(`orgs/${orgId}/portalGoogleAccounts/${googleUid}`);
const projectionRef = (db: Firestore, orgId: string, portalUid: string) =>
  db.doc(`orgs/${orgId}/volunteerPortal/${portalUid}`);

const GOOGLE_FIELDS = ["email", "portalEnabled", "portalUid", "portalGoogleEmail", "portalGoogleUid", "portalGoogleLinkedBy"];

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
  const [all, domainSetting, anyAccount, aliasSnap] = await Promise.all([
    volunteersOf(db, orgId).select(...GOOGLE_FIELDS).get(),
    institutionSetting(db, orgId, "volunteer_portal_google_domains"),
    anyAccountOn(db, orgId),
    aliasRef(db, orgId, googleUid).get(),
  ]);
  const volunteers = all.docs.map((d) => googleVolunteerOf(d.id, d));
  const domains = parseDomains(domainSetting);
  const rawTarget = aliasSnap.exists ? aliasSnap.get("volunteerUid") : undefined;
  let aliasTarget = typeof rawTarget === "string" ? rawTarget : null;
  if (aliasTarget !== null && !isAliasValid(googleUid, aliasTarget, volunteers)) {
    // Revoked, deleted or relinked volunteer: the alias must not keep answering for them.
    await aliasRef(db, orgId, googleUid).delete();
    aliasTarget = null;
  }
  let linkRequest: DocumentReference | null = null;
  let decision: GoogleDecision;
  if (c.type === "ADMIN_MATCH") {
    decision = adminMatch(c, volunteers, domains, aliasTarget, anyAccount);
    if (decision.kind === "REJECT" && decision.reason === "NO_MATCH") {
      decision = await adminMatchUnopened(c, volunteers, findAccount);
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
    decision = linkCode(c, request, requestedAt, now, volunteers, domains, aliasTarget, anyAccount);
  }
  let result: GoogleResult;
  switch (decision.kind) {
    case "REJECT":
      return rejected(decision.reason);
    case "ALREADY_LINKED":
      result = DONE;
      break;
    case "LINK":
      result = await writeLink(db, orgId, c, decision.volunteer, decision.linkedBy, aliasTarget, decision.openedUid);
      break;
  }
  if (result.status === "DONE" && linkRequest !== null) {
    await linkRequest.set({ status: "DONE", reason: "", processedAt: Date.now() }, { merge: true });
  }
  return result;
}

const sameGoogleFields = (a: GoogleVolunteer, b: GoogleVolunteer): boolean =>
  a.portalEnabled === b.portalEnabled &&
  a.portalUid === b.portalUid &&
  a.portalGoogleEmail === b.portalGoogleEmail &&
  a.portalGoogleUid === b.portalGoogleUid &&
  a.portalGoogleLinkedBy === b.portalGoogleLinkedBy;

/**
 * writeGoogleLink (Kotlin), in one transaction: the alias, the three volunteer fields and
 * `lastModified`, and the published page. A previous Google account of the volunteer loses its
 * alias. Re-reads the volunteer and the alias: if either changed since the decision, nothing is
 * written and the claim is answered SAVE_FAILED.
 */
async function writeLink(
  db: Firestore,
  orgId: string,
  c: PortalGoogleClaim,
  decided: GoogleVolunteer,
  linkedBy: string,
  aliasTarget: string | null,
  openedUid?: string,
): Promise<GoogleResult> {
  try {
    const allowed = await googleLinkAllowed(db, orgId);
    // The access was open without an account: it gets the one the server found (adminMatchUnopened).
    const portalUid = openedUid ?? decided.portalUid;
    return await db.runTransaction(async (tx: Transaction) => {
      const ref = volunteersOf(db, orgId).doc(decided.id);
      const alias = aliasRef(db, orgId, c.googleUid);
      const page = projectionRef(db, orgId, portalUid);
      const previousUid = decided.portalGoogleUid;
      const previous = !isBlank(previousUid) && previousUid !== c.googleUid ? aliasRef(db, orgId, previousUid) : null;
      const [snap, aliasSnap, pageSnap, previousSnap] = await Promise.all([
        tx.get(ref),
        tx.get(alias),
        tx.get(page),
        previous !== null ? tx.get(previous) : Promise.resolve(null),
      ]);
      if (!snap.exists || !sameGoogleFields(googleVolunteerOf(snap.id, snap), decided)) return rejected("SAVE_FAILED");
      if (openedUid !== undefined) {
        // Tied to another entry since the scan (handleClaim does the same check).
        const holders = await tx.get(volunteersOf(db, orgId).where("portalUid", "==", openedUid));
        if (!holders.empty) return rejected("SAVE_FAILED");
      }
      const target = aliasSnap.exists ? aliasSnap.get("volunteerUid") : undefined;
      if ((typeof target === "string" ? target : null) !== aliasTarget) return rejected("SAVE_FAILED");
      const now = Date.now();
      // `portalGoogleEmail.trim().ifBlank { email }` (Kotlin): the address an admin typed is kept.
      const email = isBlank(decided.portalGoogleEmail) ? c.email : kotlinTrim(decided.portalGoogleEmail);
      // Only an alias that still answers for this volunteer.
      if (previous !== null && previousSnap?.exists && previousSnap.get("volunteerUid") === decided.portalUid) {
        tx.delete(previous);
      }
      tx.set(alias, { volunteerUid: portalUid, email: c.email, linkedBy, linkedAt: now });
      tx.update(ref, {
        ...(openedUid !== undefined ? { portalUid: openedUid } : {}),
        portalGoogleEmail: email,
        portalGoogleUid: c.googleUid,
        portalGoogleLinkedBy: linkedBy,
        lastModified: Math.max(now, (millisOf(snap.get("lastModified")) ?? 0) + 1),
        sourceDeviceId: SERVER_SOURCE,
      });
      const patch = patchedProjection(pageSnap.data(), googleProjectionFields(email, linkedBy, allowed), now);
      if (patch !== null) tx.update(page, patch);
      return DONE;
    });
  } catch (e) {
    logger.error("google link not saved", { orgId, code: errorCode(e) });
    return rejected("SAVE_FAILED");
  }
}

/**
 * GOOGLE_UNLINK from the portal (processRequest and clearGoogleLink, Kotlin): only a link the
 * volunteer made. Deletes the alias, blanks the three fields and patches the page in one transaction.
 */
export async function unlinkGoogle(
  db: Firestore,
  orgId: string,
  requestId: string,
  uid: string,
): Promise<"" | "NO_ACCESS" | "ADMIN_LINK" | "SAVE_FAILED"> {
  try {
    const allowed = await googleLinkAllowed(db, orgId);
    return await db.runTransaction(async (tx: Transaction) => {
      const linked = await tx.get(volunteersOf(db, orgId).where("portalUid", "==", uid));
      const doc = linked.docs.find((d) => boolOf(d.get("portalEnabled")) && stringOf(d.get("portalUid")) === uid);
      const owner = doc !== undefined ? googleVolunteerOf(doc.id, doc) : null;
      const reason = canUnlink(owner);
      if (reason !== null || doc === undefined) return reason ?? "NO_ACCESS";
      const page = projectionRef(db, orgId, uid);
      const pageSnap = await tx.get(page);
      const now = Date.now();
      if (!isBlank(owner!.portalGoogleUid)) tx.delete(aliasRef(db, orgId, owner!.portalGoogleUid));
      tx.update(doc.ref, {
        portalGoogleEmail: "",
        portalGoogleUid: "",
        portalGoogleLinkedBy: "",
        lastModified: Math.max(now, (millisOf(doc.get("lastModified")) ?? 0) + 1),
        sourceDeviceId: SERVER_SOURCE,
      });
      const patch = patchedProjection(pageSnap.data(), googleProjectionFields("", "", allowed), now);
      if (patch !== null) tx.update(page, patch);
      return "";
    });
  } catch (e) {
    logger.error("google unlink not saved", { orgId, requestId, code: errorCode(e) });
    return "SAVE_FAILED";
  }
}
