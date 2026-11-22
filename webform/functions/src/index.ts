// Cloud Functions of the volunteer portal: Firestore triggers only, never retried, no HTTP endpoint.
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as logger from "firebase-functions/logger";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import type { MailTransport } from "./mail";
import { onRequestDocument } from "./requests";
import { onSignupDocument } from "./signup";
import { onOutboxDocument } from "./outbox";
import { onGoogleClaimDocument } from "./google";
import { onApprovalCreated } from "./approvals";

export { cleanName, cleanPhone, cleanEmail, parseSignup, parseRequest, parseGoogleClaim } from "./validate";
export type { SignupSubmission, PortalRequest, PortalRequestType, PortalGoogleClaim, PortalGoogleClaimType } from "./validate";
export { buildQrMail, buildCertificateMail, certificateFileName, parseMailTemplate, safeRecipient, sendMail, sendRawMail, smtpOptions } from "./mail";
export type { MailMode, MailTemplate, MailTransport, SendResult, OutgoingMail, QrMail, RawOutgoingMail, SmtpClient } from "./mail";
export { parseOutbox, rewriteHeaders, onOutboxDocument, MAX_RAW_LENGTH } from "./outbox";
export { delegatedToken, gmailSendRaw, MailSendError } from "./workspace";
export { parseTemplate } from "./certificate";
export type { CertificateTemplate, ResolvedParagraph, ResolvedRun } from "./certificate";
export { layoutParagraphs, renderCertificatePdf } from "./certificatePdf";
export type { LaidLine, LaidSegment, CertificatePdfInput } from "./certificatePdf";
export { parseApproval, onApprovalCreated, MAX_TEXT_JSON, MAX_SIGNER_IMAGE } from "./approvals";
export type { Approval, ApprovalCommon } from "./approvals";
export { domainAllowed, parseDomains } from "./google";

const REGION = defineString("REGION", { default: "europe-west1" });
/** The association's Gmail address that sends the QR e-mails. */
const GMAIL_USER = defineString("GMAIL_USER");
/** "app_password" (Gmail SMTP) or "workspace" (Gmail API with domain-wide delegation, no password). */
const MAIL_MODE = defineString("MAIL_MODE", { default: "app_password" });
/** Workspace mode: From address, blank for GMAIL_USER. Must be a send-as alias of GMAIL_USER, else Gmail rewrites From. */
const MAIL_FROM = defineString("MAIL_FROM", { default: "" });
/** Workspace mode: the delegated noctulist-mail@<project>.iam.gserviceaccount.com. */
const MAIL_SA = defineString("MAIL_SA", { default: "" });
/** App password of that account (Secret Manager), bound to the request function only. */
const GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");

function transport(db: Firestore): MailTransport {
  return {
    db,
    mode: () => {
      const mode = MAIL_MODE.value().trim().toLowerCase();
      if (mode === "workspace" || mode === "app_password") return mode;
      logger.warn("unknown MAIL_MODE, using app_password", { length: mode.length });
      return "app_password";
    },
    user: () => GMAIL_USER.value(),
    from: () => MAIL_FROM.value(),
    sa: () => MAIL_SA.value(),
    // Not read in workspace mode, where the app writes "unused".
    password: () => GMAIL_APP_PASSWORD.value(),
  };
}

/** Initialised on first use, so importing this module (unit tests) has no side effect. */
function admin() {
  // firebase-functions may hold a named app of its own: look for the default one.
  if (!getApps().some((app) => app.name === "[DEFAULT]")) initializeApp();
  return { db: getFirestore(), bucket: getStorage().bucket() };
}

export const onSignupWritten = onDocumentWritten(
  { document: "orgs/{orgId}/volunteerSignupForms/{formId}", region: REGION, retry: false },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db, bucket } = admin();
    await onSignupDocument(db, bucket, event.params.orgId, event.params.formId, after.data());
  },
);

export const onPortalRequestWritten = onDocumentWritten(
  {
    document: "orgs/{orgId}/portalRequests/{requestId}",
    region: REGION,
    retry: false,
    // Room for the SMTP timeouts (mail.ts) and the Firestore work around them.
    timeoutSeconds: 120,
    secrets: [GMAIL_APP_PASSWORD],
  },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db, bucket } = admin();
    const mail = transport(db);
    await onRequestDocument(db, bucket, mail, event.params.orgId, event.params.requestId, after.data());
  },
);

/** Certificates an admin approved or generated: rendered and mailed once (approvals.ts). */
export const onCertificateApprovalCreated = onDocumentCreated(
  {
    document: "orgs/{orgId}/certificateApprovals/{approvalId}",
    region: REGION,
    retry: false,
    timeoutSeconds: 120,
    secrets: [GMAIL_APP_PASSWORD],
  },
  async (event) => {
    const snap = event.data;
    if (!snap?.exists) return;
    const { db } = admin();
    await onApprovalCreated(db, transport(db), event.params.orgId, event.params.approvalId, snap.data());
  },
);

/** QR e-mails from devices without a Gmail account of their own (SiteMailRelay, Kotlin). */
export const onMailOutboxWritten = onDocumentWritten(
  {
    document: "orgs/{orgId}/mailOutbox/{mailId}",
    region: REGION,
    retry: false,
    timeoutSeconds: 120,
    secrets: [GMAIL_APP_PASSWORD],
  },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db } = admin();
    const mail = transport(db);
    await onOutboxDocument(db, mail, event.params.orgId, event.params.mailId, after.data());
  },
);

/** Google account links claimed by the portal page after a Google sign-in. */
export const onPortalGoogleClaimWritten = onDocumentWritten(
  { document: "orgs/{orgId}/portalGoogleClaims/{googleUid}", region: REGION, retry: false },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return;
    const { db } = admin();
    await onGoogleClaimDocument(db, event.params.orgId, event.params.googleUid, after.data());
  },
);
