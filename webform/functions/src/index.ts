// Cloud Functions of the volunteer portal: Firestore triggers only, never retried, no HTTP endpoint.
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { defineString } from "firebase-functions/params";
import { onSignupDocument } from "./signup";

export { cleanName, cleanPhone, cleanEmail, parseSignup, parseRequest } from "./validate";
export type { SignupSubmission, PortalRequest, PortalRequestType } from "./validate";

const REGION = defineString("REGION", { default: "europe-west1" });

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
