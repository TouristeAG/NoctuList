import { test } from "node:test";
import assert from "node:assert/strict";

const { isStalePending, isWaiting } = await import("../site/portal-common.js");

const NOW = 1_800_000_000_000;
const MIN = 60_000;
const ts = (ms) => ({ toMillis: () => ms });

test("a fresh pending profile change is waiting", () => {
  const r = { type: "PROFILE", status: "PENDING", requestedAt: ts(NOW - 2 * MIN) };
  assert.equal(isWaiting(r, NOW), true);
  assert.equal(isStalePending(r, NOW), false);
});

test("a pending profile change nobody took for 5 minutes no longer blocks", () => {
  const r = { type: "PROFILE", status: "PENDING", requestedAt: ts(NOW - 6 * MIN) };
  assert.equal(isWaiting(r, NOW), false);
  assert.equal(isStalePending(r, NOW), true);
});

test("claim and unlink requests are given up like profile changes", () => {
  for (const type of ["CLAIM", "GOOGLE_UNLINK"]) {
    const r = { type, status: "PENDING", requestedAt: ts(NOW - 6 * MIN) };
    assert.equal(isWaiting(r, NOW), false, type);
  }
});

test("a pending request without its server time yet is waiting", () => {
  const r = { type: "PROFILE", status: "PENDING", requestedAt: null };
  assert.equal(isWaiting(r, NOW), true);
  assert.equal(isStalePending(r, NOW), false);
});

test("a QR e-mail waits 15 minutes", () => {
  assert.equal(isWaiting({ type: "QR_EMAIL", status: "PENDING", requestedAt: ts(NOW - 10 * MIN) }, NOW), true);
  assert.equal(isWaiting({ type: "QR_EMAIL", status: "PENDING", requestedAt: ts(NOW - 16 * MIN) }, NOW), false);
});

test("a Google link and Google claims keep waiting for their answer", () => {
  for (const type of ["GOOGLE_LINK", "ADMIN_MATCH", "LINK_CODE"]) {
    const r = { type, status: "PENDING", requestedAt: ts(NOW - 60 * MIN) };
    assert.equal(isWaiting(r, NOW), true, type);
    assert.equal(isStalePending(r, NOW), false, type);
  }
});

test("a processing claim waits 5 minutes, then can be sent again", () => {
  assert.equal(isWaiting({ type: "PROFILE", status: "PROCESSING", processingAt: NOW - 2 * MIN }, NOW), true);
  assert.equal(isWaiting({ type: "PROFILE", status: "PROCESSING", processingAt: NOW - 6 * MIN }, NOW), false);
});

test("answered or missing requests are not waiting", () => {
  assert.equal(isWaiting({ type: "PROFILE", status: "DONE" }, NOW), false);
  assert.equal(isWaiting({ type: "PROFILE", status: "REJECTED" }, NOW), false);
  assert.equal(isWaiting(null, NOW), false);
  assert.equal(isStalePending(null, NOW), false);
});
