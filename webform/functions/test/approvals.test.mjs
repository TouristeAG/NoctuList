// Unit tests of parseApproval (no emulator needed). Run after `npm run build`.
import { test } from "node:test";
import assert from "node:assert/strict";
import * as lib from "../lib/index.js";

const run = (text) => ({ text, b: false, i: false, u: false });
const paragraph = (text) => ({ align: "LEFT", size: "NORMAL", bullet: false, runs: [run(text)] });
const signer = () => ({ name: "Jean Dupont", title: "Président", imageBase64: "" });
const common = () => ({
  body: [paragraph("Texte écrit par l'admin")],
  footer: [],
  title: "Attestation",
  signer: signer(),
  approvedBy: "Admin",
  approvedAt: 1_700_000_000_000,
});
const request = (extra = {}) => ({ kind: "REQUEST", requestId: "uid1_CERTIFICATE_EMAIL", ...common(), ...extra });
const direct = (extra = {}) => ({ kind: "DIRECT", email: "lea@mail.example", firstName: "Léa", lastName: "MARTIN", ...common(), ...extra });

test("accepts a request approval and a direct approval", () => {
  assert.equal(lib.parseApproval(request()).kind, "REQUEST");
  assert.equal(lib.parseApproval(request()).requestId, "uid1_CERTIFICATE_EMAIL");
  const d = lib.parseApproval(direct());
  assert.equal(d.kind, "DIRECT");
  assert.equal(d.email, "lea@mail.example");
});

test("accepts a timestamp-like approvedAt", () => {
  assert.ok(lib.parseApproval(request({ approvedAt: { toMillis: () => 1 } })));
  assert.ok(lib.parseApproval(request({ approvedAt: new Date() })));
});

test("rejects missing fields", () => {
  for (const key of ["body", "footer", "title", "signer", "approvedBy", "approvedAt", "kind"]) {
    const raw = request();
    delete raw[key];
    assert.equal(lib.parseApproval(raw), null, key);
  }
  const noId = request();
  delete noId.requestId;
  assert.equal(lib.parseApproval(noId), null);
  for (const key of ["email", "firstName", "lastName"]) {
    const raw = direct();
    delete raw[key];
    assert.equal(lib.parseApproval(raw), null, key);
  }
});

test("rejects wrong types", () => {
  assert.equal(lib.parseApproval(null), null);
  assert.equal(lib.parseApproval("x"), null);
  assert.equal(lib.parseApproval([]), null);
  assert.equal(lib.parseApproval(request({ kind: "OTHER" })), null);
  assert.equal(lib.parseApproval(request({ body: "text" })), null);
  assert.equal(lib.parseApproval(request({ title: 4 })), null);
  assert.equal(lib.parseApproval(request({ requestId: "" })), null);
  assert.equal(lib.parseApproval(request({ approvedAt: "yesterday" })), null);
  assert.equal(lib.parseApproval(request({ signer: { name: "a", title: "b" } })), null);
  assert.equal(lib.parseApproval(request({ body: [{ ...paragraph("x"), align: "MIDDLE" }] })), null);
  assert.equal(lib.parseApproval(request({ body: [{ ...paragraph("x"), size: "HUGE" }] })), null);
  assert.equal(lib.parseApproval(request({ body: [{ ...paragraph("x"), bullet: "yes" }] })), null);
  assert.equal(lib.parseApproval(request({ body: [{ ...paragraph("x"), runs: [{ text: "x", b: 1, i: false, u: false }] }] })), null);
  assert.equal(lib.parseApproval(request({ body: [{ ...paragraph("x"), runs: [{ b: false, i: false, u: false }] }] })), null);
  assert.equal(lib.parseApproval(direct({ email: 5 })), null);
});

test("enforces the size limits", () => {
  const big = (n) => [paragraph("x".repeat(n))];
  assert.ok(lib.parseApproval(request({ body: big(150_000) })));
  assert.equal(lib.parseApproval(request({ body: big(200_001) })), null);
  // body and footer count together
  assert.equal(lib.parseApproval(request({ body: big(110_000), footer: big(110_000) })), null);
  assert.ok(lib.parseApproval(request({ signer: { ...signer(), imageBase64: "A".repeat(200_000) } })));
  assert.equal(lib.parseApproval(request({ signer: { ...signer(), imageBase64: "A".repeat(200_001) } })), null);
});
