// Integration tests for the mail outbox Cloud Function (QR e-mails handed over by devices without
// a Gmail account). Run with `npm run test:functions`: mail is captured to `_testMail`.
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";

const PROJECT = "demo-noctulist";

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

function unique(prefix) {
  counter += 1;
  return `${prefix}-${counter}-${Date.now().toString(36)}`;
}

const RAW = (to) =>
  "From: noreply@eventmanager.app\r\n" +
  `To: ${to}\r\n` +
  "Bcc: hidden@mail.example\r\n" +
  "Subject: QR\r\n" +
  "MIME-Version: 1.0\r\n" +
  "Content-Type: text/plain; charset=UTF-8\r\n" +
  "\r\n" +
  "walletQrPayload\r\n";

async function handOver(org, overrides = {}) {
  const id = unique("mail");
  const to = overrides.to ?? `${unique("lea")}@mail.example`;
  const ref = db.doc(`orgs/${org}/mailOutbox/${id}`);
  await ref.set({
    schema: 1,
    status: "PENDING",
    to,
    raw: RAW(to),
    fromName: "Asso Test",
    createdBy: "admin1",
    createdAt: Date.now(),
    ...overrides,
  });
  const answer = await waitFor(`answer of ${id}`, async () => {
    const snap = await ref.get();
    const status = snap.get("status");
    return status === "DONE" || status === "REJECTED" ? snap.data() : null;
  });
  return { to, answer };
}

before(async () => {
  app = initializeApp({ projectId: PROJECT }, "outbox-tests");
  db = getFirestore(app);
});

after(async () => {
  await deleteApp(app);
});

describe("mailOutbox", () => {
  test("sends the device's message from the association account and forgets it", async () => {
    const org = unique("org-ob");
    const { to, answer } = await handOver(org);
    assert.equal(answer.status, "DONE");
    assert.equal(answer.reason, "");
    assert.equal(answer.raw, undefined, "message removed once answered");
    const [mail] = await waitFor("captured mail", async () => {
      const snap = await db.collection("_testMail").where("to", "==", to).get();
      return snap.empty ? null : snap.docs.map((d) => d.data());
    });
    assert.equal(mail.from.name, "Asso Test");
    assert.ok(mail.raw.startsWith("From: \"Asso Test\" <"));
    assert.ok(mail.raw.includes(`\r\nTo: ${to}\r\n`));
    assert.ok(!mail.raw.includes("hidden@mail.example"));
    assert.ok(!mail.raw.includes("noreply@eventmanager.app"));
    assert.ok(mail.raw.endsWith("\r\n\r\nwalletQrPayload\r\n"));
    const status = await db.doc(`orgs/${org}/portalServer/status`).get();
    assert.ok(status.exists, "server presence written");
  });

  test("rejects a recipient that is not one plain mailbox", async () => {
    const org = unique("org-ob");
    const { answer } = await handOver(org, { to: "a@b.example, c@d.example" });
    assert.equal(answer.status, "REJECTED");
    assert.equal(answer.reason, "BAD_ADDRESS");
    assert.equal(answer.raw, undefined);
  });

  test("rejects an unknown schema", async () => {
    const org = unique("org-ob");
    const { answer } = await handOver(org, { schema: 2 });
    assert.equal(answer.status, "REJECTED");
    assert.equal(answer.reason, "INVALID");
  });
});
