// Cloud Functions of the volunteer portal: Firestore triggers only, never retried, no HTTP endpoint.
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { onRequestDocument } from "./requests";
import { onSignupDocument } from "./signup";
import { onOutboxDocument } from "./outbox";

export { cleanName, cleanPhone, cleanEmail, parseSignup, parseRequest, parseGoogleClaim } from "./validate";
export type { SignupSubmission, PortalRequest, PortalRequestType, PortalGoogleClaim, PortalGoogleClaimType } from "./validate";
export { buildQrMail, parseMailTemplate, safeRecipient, sendMail, sendRawMail, smtpOptions } from "./mail";
export type { MailTemplate, MailTransport, OutgoingMail, QrMail, RawOutgoingMail, SmtpClient } from "./mail";
export { parseOutbox, rewriteHeaders, MAX_RAW_LENGTH } from "./outbox";

const REGION = defineString("REGION", { default: "europe-west1" });
/** The association's Gmail address that sends the QR e-mails. */
const GMAIL_USER = defineString("GMAIL_USER");
/** App password of that account (Secret Manager), bound to the request function only. */
const GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");

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
    const mail = { db, user: () => GMAIL_USER.value(), password: () => GMAIL_APP_PASSWORD.value() };
    await onRequestDocument(db, bucket, mail, event.params.orgId, event.params.requestId, after.data());
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
    const mail = { db, user: () => GMAIL_USER.value(), password: () => GMAIL_APP_PASSWORD.value() };
    await onOutboxDocument(db, mail, event.params.orgId, event.params.mailId, after.data());
  },
);
