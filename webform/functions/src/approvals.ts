// Certificates an admin approved (kind REQUEST, linked to a portal request) or generated from a
// volunteer's panel (kind DIRECT): `orgs/{orgId}/certificateApprovals/{approvalId}` holds the final
// text, this module renders it and mails it exactly once. Triggers are at-least-once, so the send is
// guarded by a transaction: the request goes APPROVED (with this approvalId) -> PROCESSING, or a
// DIRECT approval goes from no status -> PROCESSING. The volunteer id is never read into the
// approval, the request or a log.
import * as logger from "firebase-functions/logger";
import { FieldValue, type DocumentReference, type Firestore } from "firebase-admin/firestore";
import { associationName, STATUS_PROCESSING, stringOf } from "./common";
import { parseTemplate, type ResolvedParagraph } from "./certificate";
import { renderCertificatePdf } from "./certificatePdf";
import { buildCertificateMail, certificateFileName, isCapture, logoBytes, safeRecipient, sendMail, type MailTransport } from "./mail";
import { isOwner } from "./requests";
import { kotlinTrim } from "./validate";

type Data = Record<string, unknown>;

export const MAX_TEXT_JSON = 200_000;
export const MAX_SIGNER_IMAGE = 200_000;
const MAX_LABEL = 80;

export interface ApprovalCommon {
  body: ResolvedParagraph[];
  footer: ResolvedParagraph[];
  title: string;
  signer: { name: string; title: string; imageBase64: string };
  approvedBy: string;
  approvedAt: unknown;
}
export type Approval =
  | (ApprovalCommon & { kind: "REQUEST"; requestId: string })
  | (ApprovalCommon & { kind: "DIRECT"; email: string; firstName: string; lastName: string });

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const ALIGNS = ["LEFT", "CENTER", "RIGHT", "JUSTIFY"];
const SIZES = ["SMALL", "NORMAL", "LARGE", "TITLE"];

function paragraphs(raw: unknown): ResolvedParagraph[] | null {
  if (!Array.isArray(raw)) return null;
  for (const p of raw) {
    if (!isObj(p) || !ALIGNS.includes(p.align as string) || !SIZES.includes(p.size as string) || typeof p.bullet !== "boolean") return null;
    if (!Array.isArray(p.runs)) return null;
    for (const r of p.runs) {
      if (!isObj(r) || typeof r.text !== "string") return null;
      if (typeof r.b !== "boolean" || typeof r.i !== "boolean" || typeof r.u !== "boolean") return null;
    }
  }
  return raw as ResolvedParagraph[];
}

const isTime = (v: unknown): boolean =>
  (typeof v === "number" && Number.isFinite(v)) || v instanceof Date || (isObj(v) && typeof v.toMillis === "function");

/** The approval as a device writes it, or null when a field is missing, of the wrong type or too big. */
export function parseApproval(raw: unknown): Approval | null {
  if (!isObj(raw)) return null;
  const body = paragraphs(raw.body);
  const footer = paragraphs(raw.footer);
  if (body === null || footer === null) return null;
  if (JSON.stringify(body).length + JSON.stringify(footer).length > MAX_TEXT_JSON) return null;
  const signer = raw.signer;
  if (!isObj(signer) || typeof signer.name !== "string" || typeof signer.title !== "string" || typeof signer.imageBase64 !== "string") return null;
  if (signer.imageBase64.length > MAX_SIGNER_IMAGE) return null;
  if (typeof raw.title !== "string" || typeof raw.approvedBy !== "string" || !isTime(raw.approvedAt)) return null;
  const common: ApprovalCommon = {
    body,
    footer,
    title: raw.title,
    signer: { name: signer.name, title: signer.title, imageBase64: signer.imageBase64 },
    approvedBy: raw.approvedBy,
    approvedAt: raw.approvedAt,
  };
  if (raw.kind === "REQUEST") {
    return typeof raw.requestId === "string" && raw.requestId !== "" ? { ...common, kind: "REQUEST", requestId: raw.requestId } : null;
  }
  if (raw.kind === "DIRECT") {
    if (typeof raw.email !== "string" || typeof raw.firstName !== "string" || typeof raw.lastName !== "string") return null;
    return { ...common, kind: "DIRECT", email: raw.email, firstName: raw.firstName, lastName: raw.lastName };
  }
  return null;
}

type Reason = "INVALID" | "NO_ACCESS" | "NO_EMAIL" | "SEND_FAILED";

const DEFAULT_MAIL: Record<string, { subject: string; body: string }> = {
  fr: { subject: "Ton attestation de bénévolat", body: "Bonjour {firstName}, voici ton attestation de bénévolat pour {association}. Elle est jointe à cet e-mail." },
  en: { subject: "Your volunteer certificate", body: "Hello {firstName}, here is your volunteer certificate for {association}. It is attached to this e-mail." },
  de: { subject: "Deine Bestätigung über ehrenamtliches Engagement", body: "Hallo {firstName}, hier ist deine Bestätigung über dein ehrenamtliches Engagement bei {association}. Sie ist dieser E-Mail angehängt." },
  it: { subject: "La tua attestazione di volontariato", body: "Ciao {firstName}, ecco la tua attestazione di volontariato per {association}. La trovi in allegato a questa e-mail." },
  es: { subject: "Tu certificado de voluntariado", body: "Hola {firstName}, aquí tienes tu certificado de voluntariado para {association}. Va adjunto a este correo." },
};

/** Plain replacement of the two mail variables: no `$` patterns, every occurrence. */
const fillMail = (text: string, firstName: string, association: string): string =>
  text.split("{firstName}").join(firstName).split("{association}").join(association);

const errorCode = (e: unknown): string => {
  const code = (e as { code?: unknown })?.code;
  return code === undefined ? "" : String(code);
};

/**
 * Renders and mails [approval] to [to]. The mail settings and the logo come from the published
 * template (portalMail/certificate); without it the language defaults apply (English, no logo).
 */
async function renderAndSend(
  db: Firestore,
  mail: MailTransport,
  orgId: string,
  approval: Approval,
  to: string,
  firstName: string,
  lastName: string,
): Promise<boolean> {
  const published = (await db.doc(`orgs/${orgId}/portalMail/certificate`).get()).data();
  const rawTemplate = published?.template;
  const template = rawTemplate !== null && typeof rawTemplate === "object" ? parseTemplate(rawTemplate) : null;
  const language = template?.language ?? "en";
  const defaults = DEFAULT_MAIL[language] ?? DEFAULT_MAIL.en;
  const publishedAssociation = stringOf(published?.association).trim();
  const association = (publishedAssociation !== "" ? publishedAssociation : await associationName(db, orgId)).trim();
  const logoPng = logoBytes(stringOf(published?.logoBase64));
  const signature = approval.signer.imageBase64.trim();
  let pdf: Uint8Array;
  try {
    pdf = await renderCertificatePdf({
      paragraphs: approval.body,
      footer: approval.footer,
      logoPng,
      signatureImage: signature === "" ? null : Buffer.from(signature, "base64"),
      signer: { name: approval.signer.name, title: approval.signer.title },
      title: approval.title,
    });
  } catch (e) {
    logger.error("certificate not rendered", { reason: "RENDER_FAILED", code: errorCode(e) });
    return false;
  }
  if (isCapture()) {
    // Emulator tests only (same gate as the mail capture): what was handed to the renderer, since
    // the captured mail keeps attachment sizes, not bytes.
    await db.collection("_testCertificate").add({ to, title: approval.title, body: approval.body, footer: approval.footer, capturedAt: Date.now() });
  }
  const m = template?.mail;
  const name = firstName.trim();
  const message = buildCertificateMail({
    to,
    fromName: ((m?.fromName.trim() ?? "") === "" ? association : m!.fromName).trim().slice(0, MAX_LABEL),
    fromAddress: m?.fromAddress.trim() ?? "",
    replyTo: m?.replyTo.trim() ?? "",
    subject: fillMail((m?.subject.trim() ?? "") === "" ? defaults.subject : m!.subject, name, association),
    body: fillMail((m?.body.trim() ?? "") === "" ? defaults.body : m!.body, name, association),
    pdf,
    fileName: certificateFileName(firstName, lastName, language),
    logoPng,
  });
  return (await sendMail(message, mail)).sent;
}

/** Handles the creation of `orgs/{orgId}/certificateApprovals/{approvalId}`. */
export async function onApprovalCreated(
  db: Firestore,
  mail: MailTransport,
  orgId: string,
  approvalId: string,
  raw: Data | undefined,
): Promise<void> {
  if (raw === undefined) return;
  const ref = db.doc(`orgs/${orgId}/certificateApprovals/${approvalId}`);
  try {
    if (raw.kind === "DIRECT") await sendDirect(db, mail, orgId, ref, raw);
    else await sendForRequest(db, mail, orgId, ref, approvalId, raw);
  } catch (e) {
    logger.error("certificate approval failed", { orgId, reason: "FAILED", code: errorCode(e) });
  }
}

/** DIRECT: the approval is its own claim. Failure keeps it as FAILED for the admin to see. */
async function sendDirect(db: Firestore, mail: MailTransport, orgId: string, ref: DocumentReference, raw: Data): Promise<void> {
  const claimed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.get("status") !== undefined) return null;
    tx.update(ref, { status: STATUS_PROCESSING, processingAt: Date.now() });
    return snap.data() as Data;
  });
  if (claimed === null) return;
  const fail = async (reason: Reason): Promise<void> => {
    logger.info("certificate not sent", { orgId, type: "DIRECT", reason });
    await ref.update({ status: "FAILED", reason, processedAt: Date.now() });
  };
  const approval = parseApproval(claimed);
  if (approval === null || approval.kind !== "DIRECT") return fail("INVALID");
  const to = safeRecipient(kotlinTrim(approval.email));
  if (to === null) return fail("NO_EMAIL");
  let sent = false;
  try {
    sent = await renderAndSend(db, mail, orgId, approval, to, approval.firstName, approval.lastName);
  } catch (e) {
    logger.error("certificate not sent", { orgId, reason: "SEND_FAILED", code: errorCode(e) });
  }
  if (!sent) return fail("SEND_FAILED");
  await ref.delete();
}

/** REQUEST: the request moves APPROVED -> PROCESSING for this approval only, then DONE or back to review. */
async function sendForRequest(
  db: Firestore,
  mail: MailTransport,
  orgId: string,
  ref: DocumentReference,
  approvalId: string,
  raw: Data,
): Promise<void> {
  const requestId = typeof raw.requestId === "string" && raw.requestId !== "" ? raw.requestId : null;
  if (requestId === null) {
    await ref.delete();
    return;
  }
  const requestRef = db.doc(`orgs/${orgId}/portalRequests/${requestId}`);
  const approval = parseApproval(raw);
  try {
    const request = await db.runTransaction(async (tx) => {
      const snap = await tx.get(requestRef);
      if (!snap.exists) return null;
      const data = snap.data() as Data;
      if (data.type !== "CERTIFICATE_EMAIL" || data.status !== "APPROVED" || data.approvalId !== approvalId) return null;
      tx.update(requestRef, { status: STATUS_PROCESSING, processingAt: Date.now(), processingDevice: "server" });
      return data;
    });
    if (request === null) {
      logger.info("approval ignored", { orgId, type: "REQUEST", reason: "NOT_APPROVED" });
      return;
    }
    const back = async (reason: Reason): Promise<void> => {
      logger.info("certificate not sent", { orgId, uid: stringOf(request.uid), type: "REQUEST", reason });
      await requestRef.update({
        status: "PENDING_REVIEW",
        reason,
        processedAt: Date.now(),
        reviewAt: FieldValue.serverTimestamp(),
        approvalId: FieldValue.delete(),
      });
    };
    if (approval === null || approval.kind !== "REQUEST") return back("INVALID");
    const uid = stringOf(request.uid);
    // Read now: the address on the profile at send time wins over the one at request time.
    const linked = await db.collection(`orgs/${orgId}/volunteers`).where("portalUid", "==", uid).get();
    const owner = linked.docs.find((d) => isOwner(d, uid));
    if (owner === undefined) return back("NO_ACCESS");
    const to = safeRecipient(kotlinTrim(stringOf(owner.get("email"))));
    if (to === null) return back("NO_EMAIL");
    let sent = false;
    try {
      sent = await renderAndSend(db, mail, orgId, approval, to, stringOf(owner.get("name")), stringOf(owner.get("lastNameAbbreviation")));
    } catch (e) {
      logger.error("certificate not sent", { orgId, uid, reason: "SEND_FAILED", code: errorCode(e) });
    }
    if (!sent) return back("SEND_FAILED");
    logger.info("certificate sent", { orgId, uid, type: "REQUEST" });
    await requestRef.update({ status: "DONE", reason: "", processedAt: Date.now(), approvalId: FieldValue.delete() });
  } finally {
    await ref.delete().catch(() => undefined);
  }
}
