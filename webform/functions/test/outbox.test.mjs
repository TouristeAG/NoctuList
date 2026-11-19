// Unit tests of the mail outbox parsing and header rewrite (no emulator needed). Run after `npm run build`.
import { test } from "node:test";
import assert from "node:assert/strict";
import * as lib from "../lib/index.js";

const RAW =
  "From: noreply@eventmanager.app\r\n" +
  "To: lea@mail.example\r\n" +
  "Cc: other@mail.example,\r\n" +
  " third@mail.example\r\n" +
  "Bcc: hidden@mail.example\r\n" +
  "Subject: =?UTF-8?B?VG9uIFFS?=\r\n" +
  "MIME-Version: 1.0\r\n" +
  "Content-Type: multipart/related; boundary=\"b\"\r\n" +
  "\r\n" +
  "--b\r\nFrom: not a header\r\n\r\nbody\r\n--b--\r\n";
const NOW = new Date(Date.UTC(2026, 10, 11, 9, 30, 0));

test("rewriteHeaders sets sender and recipient and drops every other address header", () => {
  const out = lib.rewriteHeaders(RAW, "asso@gmail.com", "Asso Test", "lea@mail.example", NOW);
  const [head, ...rest] = out.split("\r\n\r\n");
  const lines = head.split("\r\n");
  assert.equal(lines[0], "From: \"Asso Test\" <asso@gmail.com>");
  assert.equal(lines[1], "To: lea@mail.example");
  assert.equal(lines[2], "Date: Wed, 11 Nov 2026 09:30:00 +0000");
  assert.match(lines[3], /^Message-ID: <[0-9a-f-]{36}@gmail\.com>$/);
  assert.ok(!head.includes("noreply@eventmanager.app"));
  assert.ok(!head.toLowerCase().includes("cc:"));
  assert.ok(!head.includes("third@mail.example"), "folded Cc line dropped too");
  assert.ok(head.includes("Subject: =?UTF-8?B?VG9uIFFS?="));
  assert.ok(head.includes("Content-Type: multipart/related; boundary=\"b\""));
  assert.equal(rest.join("\r\n\r\n"), "--b\r\nFrom: not a header\r\n\r\nbody\r\n--b--\r\n", "body untouched");
});

test("rewriteHeaders encodes a non-ASCII sender name and strips control characters", () => {
  const out = lib.rewriteHeaders(RAW, "asso@gmail.com", "Café\r\nBcc: x@y.z", "lea@mail.example", NOW);
  const first = out.split("\r\n")[0];
  assert.match(first, /^From: =\?UTF-8\?B\?[A-Za-z0-9+/=]+\?= <asso@gmail\.com>$/);
  const encoded = first.match(/B\?(.+)\?=/)[1];
  assert.equal(Buffer.from(encoded, "base64").toString("utf8"), "Café Bcc: x@y.z");
  assert.equal(lib.rewriteHeaders(RAW, "asso@gmail.com", " ", "lea@mail.example", NOW).split("\r\n")[0], "From: asso@gmail.com");
});

test("rewriteHeaders keeps LF-only messages working", () => {
  const out = lib.rewriteHeaders("From: a@b.example\nSubject: x\n\nbody\n", "asso@gmail.com", "", "lea@mail.example", NOW);
  assert.ok(out.includes("Subject: x\r\n"));
  assert.ok(out.endsWith("body\n"));
  assert.ok(!out.includes("a@b.example"));
});

test("parseOutbox accepts only the shape devices write", () => {
  const ok = { schema: 1, status: "PENDING", to: "lea@mail.example", raw: RAW, fromName: "Asso", createdBy: "u", createdAt: 1 };
  assert.deepEqual(lib.parseOutbox(ok), { to: "lea@mail.example", raw: RAW, fromName: "Asso" });
  assert.equal(lib.parseOutbox(undefined), null);
  assert.equal(lib.parseOutbox({ ...ok, schema: 2 }), null);
  assert.equal(lib.parseOutbox({ ...ok, raw: "" }), null);
  assert.equal(lib.parseOutbox({ ...ok, raw: "no header end" }), null);
  assert.equal(lib.parseOutbox({ ...ok, raw: "x".repeat(lib.MAX_RAW_LENGTH) + "\r\n\r\n" }), null);
  assert.equal(lib.parseOutbox({ ...ok, to: 3 }), null);
  assert.equal(lib.parseOutbox({ ...ok, fromName: "x".repeat(200) }).fromName.length, 80);
});

test("sendRawMail pins the envelope to the one recipient", async () => {
  const calls = [];
  const transport = {
    db: null,
    mode: () => "app_password", from: () => "", sa: () => "",
    user: () => "asso@gmail.com",
    password: () => "abcd efgh ijkl mnop",
    smtp: (user, password) => ({ sendMail: async (m) => { calls.push({ user, password, m }); return {}; } }),
  };
  const ok = await lib.sendRawMail({ to: "lea@mail.example", fromName: "Asso", build: (from) => `From: ${from}\r\n\r\nx` }, transport);
  assert.deepEqual(ok, { sent: true, failure: "" });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].password, "abcdefghijklmnop");
  assert.deepEqual(calls[0].m.envelope, { from: "asso@gmail.com", to: ["lea@mail.example"] });
  assert.equal(calls[0].m.raw, "From: asso@gmail.com\r\n\r\nx");
  assert.equal((await lib.sendRawMail({ to: "a@b.example, c@d.example", fromName: "", build: () => "x" }, transport)).sent, false);
});

test("outboxStoresFailureCode", async () => {
  const sets = [];
  const db = { runTransaction: async (fn) => fn({ get: async () => ({ exists: true, data: () => doc }), set: () => {} }) };
  const ref = { firestore: db, set: async (data) => { sets.push(data); } };
  db.doc = () => ref;
  const doc = { schema: 1, to: "lea@mail.example", raw: "From: x\r\n\r\nbody", fromName: "A", status: "PENDING" };
  const transport = {
    db, mode: () => "app_password", from: () => "", sa: () => "",
    user: () => "asso@gmail.com", password: () => "pw",
    smtp: () => ({ sendMail: async () => { throw Object.assign(new Error("Invalid login: 535"), { responseCode: 535 }); } }),
  };
  await lib.onOutboxDocument(db, transport, "org1", "m1", doc);
  const answer = sets.find((s) => s.status === "REJECTED");
  assert.ok(answer);
  assert.equal(answer.reason, "SEND_FAILED");
  assert.equal(answer.failure, "smtp:535");
  assert.equal(JSON.stringify(answer).includes("body"), false);
});
