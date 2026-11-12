import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";

const data = new Map();
globalThis.localStorage = {
  getItem: (k) => (data.has(k) ? data.get(k) : null),
  setItem: (k, v) => void data.set(k, String(v)),
  removeItem: (k) => void data.delete(k),
};

const { clearQr, readCachedQr, storeQr } = await import("../site/portal-qr.js");

const ORG = "org1";
const ENTRY = { uid: "u1", payload: "NL1:abc", name: "Ada Lovelace", association: "Les Noctambules" };

beforeEach(() => data.clear());

test("storeThenRead returns the stored object", () => {
  storeQr(ORG, ENTRY);
  assert.deepEqual(readCachedQr(ORG), ENTRY);
  assert.deepEqual(readCachedQr(ORG, "u1"), ENTRY);
});

test("readWithOtherUidIsNull", () => {
  storeQr(ORG, ENTRY);
  assert.equal(readCachedQr(ORG, "other"), null);
});

test("clearRemoves", () => {
  storeQr(ORG, ENTRY);
  clearQr(ORG);
  assert.equal(readCachedQr(ORG), null);
});

test("corruptJsonIsNull", () => {
  data.set(`noctulist.portalQr.${ORG}`, "{not json");
  assert.equal(readCachedQr(ORG), null);
});
