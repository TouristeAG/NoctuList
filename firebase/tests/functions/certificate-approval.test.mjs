// Integration tests for certificateApprovals (an admin approved a certificate, or generated one from a
// volunteer's panel): the server mails it exactly once. Run with `npm run test:functions`: same setup as
// certificate.test.mjs (emulators, mail captured to `_testMail`). The captured mail keeps attachment
// sizes, not bytes, so the emulator capture also stores what was handed to the PDF renderer in
// `_testCertificate` (same gate as the mail capture: emulator with PORTAL_MAIL_TRANSPORT=capture).
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { onApprovalCreated } from "../../../webform/functions/lib/index.js";

const PROJECT = "demo-noctulist";
const TEMPLATE_SENTENCE = "Phrase du modele publie";
const ADMIN_SENTENCE = "Phrase ecrite par l'admin pour cette attestation";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run through `npm run test:functions`: the emulators must be running.");
}

let app;
let db;
let counter = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(what, check, timeoutMs = 30_000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await sleep(250);
  }
}

const run = (text) => ({ text, b: false, i: false, u: false });
const paragraph = (text) => ({ align: "LEFT", size: "NORMAL", bullet: false, runs: [run(text)] });

/** A fresh organization per test, with a published template. */
async function newOrg({ published = true, mailBody = "Bonjour {firstName}, voici ton attestation pour {association}." } = {}) {
  counter += 1;
  const org = `org-appr-${counter}-${Date.now()}`;
  const now = Date.now();
  await db.doc(`orgs/${org}/metadata/config`).set({ createdAt: now });
  await db.doc(`orgs/${org}/portalBranding/default`).set({ associationName: "Asso Test", logoDataUri: "", lastModified: now });
  if (published) {
    await db.doc(`orgs/${org}/portalMail/certificate`).set({
      template: {
        version: 1,
        language: "fr",
        place: "Lausanne",
        blocks: { intro: { one: [{ align: "LEFT", size: "NORMAL", bullet: false, runs: [{ text: TEMPLATE_SENTENCE, var: "", gender: [], b: false, i: false, u: false }] }] } },
        mail: { fromName: "Comité", fromAddress: "", replyTo: "", subject: "Ton attestation, {firstName}", body: mailBody },
        signer: { name: "Jean Dupont", title: "Président", imageBase64: "" },
      },
      association: "Asso Test",
      logoBase64: "",
      jobTypes: [],
      updatedAt: now,
    });
  }
  return org;
}

function newUid(prefix) {
  counter += 1;
  return `${prefix}${String(counter).padStart(4, "0")}${Date.now().toString(36)}`;
}

async function addVolunteer(org, overrides = {}) {
  counter += 1;
  const id = `vol${String(counter).padStart(4, "0")}${Date.now().toString(36)}`.padEnd(21, "x").slice(0, 21);
  const data = {
    id, name: "Léa", lastNameAbbreviation: "MARTIN", email: "lea@mail.example", phone: "079 000 00 00",
    dateOfBirth: "1998-04-12", gender: "FEMALE", currentRank: "NOVA", isActive: true, lastModified: 1000,
    isAdmin: false, portalEnabled: true, portalUid: "", ...overrides,
  };
  await db.doc(`orgs/${org}/volunteers/${id}`).set(data);
  return data;
}

const text = (sentence = ADMIN_SENTENCE) => ({
  body: [paragraph(sentence)],
  footer: [paragraph("Pied de page")],
  title: "Attestation de bénévolat",
  signer: { name: "Jean Dupont", title: "Président", imageBase64: "" },
  approvedBy: "Admin",
  approvedAt: Date.now(),
});

/** A request the admin device approved: PENDING_REVIEW -> APPROVED with an approvalId. */
async function approvedRequest(org, uid, approvalId, extra = {}) {
  const ref = db.doc(`orgs/${org}/portalRequests/${uid}_CERTIFICATE_EMAIL`);
  const data = {
    uid, type: "CERTIFICATE_EMAIL", status: "APPROVED", approvalId, approvedAt: Date.now(),
    email: `${uid.toLowerCase()}@auth.example`, from: "2024-01-01", to: "2024-12-31", jobTypes: ["Bar"],
    requestedAt: Date.now(), ...extra,
  };
  if (data.approvalId === null) delete data.approvalId;
  await ref.set(data);
  return ref;
}

async function writeApproval(org, id, fields) {
  const ref = db.doc(`orgs/${org}/certificateApprovals/${id}`);
  await ref.set(fields);
  return ref;
}

const requestApproval = (requestId, sentence) => ({ kind: "REQUEST", requestId, ...text(sentence) });
const directApproval = (email, extra = {}) => ({ kind: "DIRECT", email, firstName: "Paul", lastName: "DURAND", ...text(), ...extra });

async function capturedMailsTo(address) {
  const snap = await db.collection("_testMail").where("to", "==", address).get();
  return snap.docs.map((d) => d.data());
}

async function waitMailTo(address) {
  const [mail] = await waitFor(`mail to ${address}`, async () => {
    const mails = await capturedMailsTo(address);
    return mails.length > 0 ? mails : null;
  });
  return mail;
}

const gone = (ref) => waitFor(`${ref.path} to be deleted`, async () => !(await ref.get()).exists);

/** A mail body too large for Firestore: the capture write fails, which is how a failed send looks here. */
const HUGE_BODY = "x".repeat(600_000);

before(async () => {
  app = initializeApp({ projectId: PROJECT }, "certificate-approval-tests");
  db = getFirestore(app);
});

after(async () => {
  if (app) await deleteApp(app);
});

describe("onCertificateApprovalCreated: DIRECT", () => {
  test("sends a direct certificate", async () => {
    const org = await newOrg();
    const email = `direct${Date.now().toString(36)}@mail.example`;
    const ref = await writeApproval(org, "a1", directApproval(email));
    const mail = await waitMailTo(email);
    assert.ok(mail.subject.includes("Paul"));
    const pdf = mail.attachments.find((a) => a.contentType === "application/pdf");
    assert.ok(pdf && pdf.size > 0, "PDF attached");
    assert.equal(pdf.filename, "attestation-benevolat-paul-durand.pdf");
    await gone(ref);
    await sleep(1000);
    assert.equal((await capturedMailsTo(email)).length, 1);
  });

  test("direct send failure keeps the approval as FAILED", async () => {
    const org = await newOrg({ mailBody: HUGE_BODY });
    const email = `directfail${Date.now().toString(36)}@mail.example`;
    const ref = await writeApproval(org, "a1", directApproval(email));
    const doc = await waitFor("FAILED", async () => {
      const d = (await ref.get()).data();
      return d?.status === "FAILED" ? d : null;
    });
    assert.equal(doc.reason, "SEND_FAILED");
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("direct approval with a bad address keeps the approval as FAILED", async () => {
    const org = await newOrg();
    const ref = await writeApproval(org, "a1", directApproval("not an address"));
    const doc = await waitFor("FAILED", async () => {
      const d = (await ref.get()).data();
      return d?.status === "FAILED" ? d : null;
    });
    assert.equal(doc.reason, "NO_EMAIL");
  });

  test("direct approval sends once", async () => {
    const org = await newOrg();
    const email = `directonce${Date.now().toString(36)}@mail.example`;
    // The real trigger and two extra deliveries of the same event race for the claim.
    process.env.FUNCTIONS_EMULATOR = "true";
    process.env.PORTAL_MAIL_TRANSPORT = "capture";
    const mail = { db, mode: () => "app_password", user: () => "test@example.org", from: () => "", sa: () => "", password: () => "x" };
    const fields = directApproval(email);
    const ref = await writeApproval(org, "a1", fields);
    await Promise.all([
      onApprovalCreated(db, mail, org, "a1", fields),
      onApprovalCreated(db, mail, org, "a1", fields),
    ]);
    await waitMailTo(email);
    await gone(ref);
    await sleep(2500);
    assert.equal((await capturedMailsTo(email)).length, 1);
  });
});

describe("onCertificateApprovalCreated: REQUEST", () => {
  test("sends the approved text", async () => {
    const org = await newOrg();
    const uid = newUid("apprOk");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email });
    const requestRef = await approvedRequest(org, uid, "ap1");
    const ref = await writeApproval(org, "ap1", requestApproval(requestRef.id, ADMIN_SENTENCE));

    const mail = await waitMailTo(email);
    assert.ok(mail.attachments.some((a) => a.contentType === "application/pdf" && a.size > 0), "PDF attached");
    const rendered = await waitFor("rendered text", async () => {
      const snap = await db.collection("_testCertificate").where("to", "==", email).get();
      return snap.empty ? null : snap.docs[0].data();
    });
    const rendered_text = JSON.stringify(rendered);
    assert.ok(rendered_text.includes(ADMIN_SENTENCE), "the admin's sentence is rendered");
    assert.ok(!rendered_text.includes(TEMPLATE_SENTENCE), "the template text is not");

    await gone(ref);
    const done = await waitFor("DONE", async () => {
      const d = (await requestRef.get()).data();
      return d?.status === "DONE" ? d : null;
    });
    assert.equal(typeof done.processedAt, "number");
    assert.equal(done.approvalId, undefined);
  });

  test("sends once for two approvals", async () => {
    const org = await newOrg();
    const uid = newUid("apprTwo");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email });
    const requestRef = await approvedRequest(org, uid, "good");
    const stale = await writeApproval(org, "stale", requestApproval(requestRef.id, "Ancien texte"));
    const good = await writeApproval(org, "good", requestApproval(requestRef.id, ADMIN_SENTENCE));
    await waitMailTo(email);
    await gone(good);
    await gone(stale);
    await sleep(1500);
    assert.equal((await capturedMailsTo(email)).length, 1);
    const snap = await db.collection("_testCertificate").where("to", "==", email).get();
    assert.equal(snap.size, 1);
    assert.ok(JSON.stringify(snap.docs[0].data()).includes(ADMIN_SENTENCE));
  });

  test("ignores an approval when the request is not APPROVED", async () => {
    const org = await newOrg();
    const uid = newUid("apprNot");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email });
    const requestRef = await approvedRequest(org, uid, "ap1", { status: "PENDING_REVIEW", approvalId: null });
    const ref = await writeApproval(org, "ap1", requestApproval(requestRef.id));
    await gone(ref);
    await sleep(1000);
    assert.equal((await capturedMailsTo(email)).length, 0);
    assert.equal((await requestRef.get()).data().status, "PENDING_REVIEW");
  });

  test("uses the profile address at send time", async () => {
    const org = await newOrg();
    const uid = newUid("apprAddr");
    const oldEmail = `${uid.toLowerCase()}@old.example`;
    const newEmail = `${uid.toLowerCase()}@new.example`;
    const v = await addVolunteer(org, { portalUid: uid, email: oldEmail });
    const requestRef = await approvedRequest(org, uid, "ap1", { email: oldEmail });
    await db.doc(`orgs/${org}/volunteers/${v.id}`).update({ email: newEmail });
    await writeApproval(org, "ap1", requestApproval(requestRef.id));
    await waitMailTo(newEmail);
    await sleep(1000);
    assert.equal((await capturedMailsTo(oldEmail)).length, 0);
  });

  test("send failure goes back to review", async () => {
    const org = await newOrg({ mailBody: HUGE_BODY });
    const uid = newUid("apprFail");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email });
    const requestRef = await approvedRequest(org, uid, "ap1");
    const ref = await writeApproval(org, "ap1", requestApproval(requestRef.id));
    const doc = await waitFor("PENDING_REVIEW", async () => {
      const d = (await requestRef.get()).data();
      return d?.status === "PENDING_REVIEW" ? d : null;
    });
    assert.equal(doc.reason, "SEND_FAILED");
    await gone(ref);
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("a profile without access goes back to review", async () => {
    const org = await newOrg();
    const uid = newUid("apprNoAcc");
    const requestRef = await approvedRequest(org, uid, "ap1");
    const ref = await writeApproval(org, "ap1", requestApproval(requestRef.id));
    const doc = await waitFor("PENDING_REVIEW", async () => {
      const d = (await requestRef.get()).data();
      return d?.status === "PENDING_REVIEW" ? d : null;
    });
    assert.equal(doc.reason, "NO_ACCESS");
    await gone(ref);
  });

  test("a profile without a valid address goes back to review", async () => {
    const org = await newOrg();
    const uid = newUid("apprNoMail");
    await addVolunteer(org, { portalUid: uid, email: "" });
    const requestRef = await approvedRequest(org, uid, "ap1");
    await writeApproval(org, "ap1", requestApproval(requestRef.id));
    const doc = await waitFor("PENDING_REVIEW", async () => {
      const d = (await requestRef.get()).data();
      return d?.status === "PENDING_REVIEW" ? d : null;
    });
    assert.equal(doc.reason, "NO_EMAIL");
  });

  test("rejects an oversized approval", async () => {
    const org = await newOrg();
    const uid = newUid("apprBig");
    const email = `${uid.toLowerCase()}@profile.example`;
    await addVolunteer(org, { portalUid: uid, email });
    const requestRef = await approvedRequest(org, uid, "ap1");
    const ref = await writeApproval(org, "ap1", { ...requestApproval(requestRef.id), body: [paragraph("x".repeat(250_000))] });
    const doc = await waitFor("PENDING_REVIEW", async () => {
      const d = (await requestRef.get()).data();
      return d?.status === "PENDING_REVIEW" ? d : null;
    });
    assert.equal(doc.reason, "INVALID");
    await gone(ref);
    assert.equal((await capturedMailsTo(email)).length, 0);
  });

  test("the volunteer id appears nowhere on the request or the approval", async () => {
    const org = await newOrg();
    const uid = newUid("apprId");
    const email = `${uid.toLowerCase()}@profile.example`;
    const v = await addVolunteer(org, { portalUid: uid, email });
    const requestRef = await approvedRequest(org, uid, "ap1");
    await writeApproval(org, "ap1", requestApproval(requestRef.id));
    await waitMailTo(email);
    const done = await waitFor("DONE", async () => {
      const d = (await requestRef.get()).data();
      return d?.status === "DONE" ? d : null;
    });
    assert.ok(!JSON.stringify(done).includes(v.id));
  });
});
