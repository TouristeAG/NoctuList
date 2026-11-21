import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";

const data = new Map();
globalThis.localStorage = {
  getItem: (k) => (data.has(k) ? data.get(k) : null),
  setItem: (k, v) => void data.set(k, String(v)),
  removeItem: (k) => void data.delete(k),
};

const { cachedQrEntries, clearQr, isStaleFor, missingIsFinal, readCachedQr, storeQr } = await import("../site/portal-qr.js");

const ORG = "org1";
const KEY = `noctulist.portalQr.${ORG}`;
const MINE = { payload: "NL1:abc", name: "Ada Lovelace", association: "Les Noctambules" };
const GUEST = { payload: "NL1:guest", name: "Ada L.", association: "Les Noctambules" };
const GROUP = [
  { key: "k1", payload: "NL1:t1", name: "Bob B.", association: "Les Noctambules" },
  { key: "k2", payload: "NL1:t2", name: "Cy C.", association: "Les Noctambules" },
];

beforeEach(() => data.clear());

test("storeThenRead keeps one entry per profile", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  storeQr(ORG, "u1", "guest", GUEST);
  storeQr(ORG, "u1", "temporary", GROUP);
  const cached = readCachedQr(ORG, "u1");
  assert.equal(cached.uid, "u1");
  assert.deepEqual(cached.profiles.volunteer, MINE);
  assert.deepEqual(cached.profiles.guest, GUEST);
  assert.deepEqual(cached.profiles.temporary, GROUP);
  assert.deepEqual(readCachedQr(ORG), cached);
});

test("legacyVolunteerValueIsReadAsTheVolunteerProfile", () => {
  data.set(KEY, JSON.stringify({ uid: "u1", ...MINE }));
  const cached = readCachedQr(ORG, "u1");
  assert.deepEqual(cached, { uid: "u1", profiles: { volunteer: MINE } });
  assert.deepEqual(cachedQrEntries(cached), [{ kind: "volunteer", key: "volunteer", ...MINE }]);
  // A new profile is added next to the legacy one.
  storeQr(ORG, "u1", "guest", GUEST);
  assert.deepEqual(readCachedQr(ORG, "u1").profiles, { volunteer: MINE, guest: GUEST });
});

test("readWithOtherUidIsNull", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  assert.equal(readCachedQr(ORG, "other"), null);
});

test("anotherAccountReplacesTheStoredCopy", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  storeQr(ORG, "u2", "guest", GUEST);
  assert.deepEqual(readCachedQr(ORG), { uid: "u2", profiles: { guest: GUEST } });
});

test("clearOneProfileKeepsTheOthers", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  storeQr(ORG, "u1", "guest", GUEST);
  clearQr(ORG, "volunteer");
  assert.deepEqual(readCachedQr(ORG, "u1").profiles, { guest: GUEST });
  clearQr(ORG, "guest");
  assert.equal(readCachedQr(ORG), null);
  assert.equal(data.has(KEY), false);
});

test("clearOneProfileOfAnotherAccountClearsEverything", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  storeQr(ORG, "u1", "guest", GUEST);
  clearQr(ORG, "guest", "u2");
  assert.equal(readCachedQr(ORG), null);
  storeQr(ORG, "u1", "volunteer", MINE);
  storeQr(ORG, "u1", "guest", GUEST);
  clearQr(ORG, "guest", "u1");
  assert.deepEqual(readCachedQr(ORG).profiles, { volunteer: MINE });
});

test("clearRemovesEverything", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  storeQr(ORG, "u1", "temporary", GROUP);
  clearQr(ORG);
  assert.equal(readCachedQr(ORG), null);
});

test("emptyGroupListRemovesTheProfile", () => {
  storeQr(ORG, "u1", "temporary", GROUP);
  storeQr(ORG, "u1", "temporary", []);
  assert.equal(readCachedQr(ORG), null);
});

test("entriesInProfileOrder", () => {
  storeQr(ORG, "u1", "temporary", GROUP);
  storeQr(ORG, "u1", "guest", GUEST);
  storeQr(ORG, "u1", "volunteer", MINE);
  assert.deepEqual(cachedQrEntries(readCachedQr(ORG)).map((e) => e.key), ["volunteer", "guest", "k1", "k2"]);
  assert.deepEqual(cachedQrEntries(null), []);
});

test("corruptOrInvalidValuesAreNull", () => {
  data.set(KEY, "{not json");
  assert.equal(readCachedQr(ORG), null);
  data.set(KEY, JSON.stringify({ uid: "u1", profiles: { volunteer: { payload: 3 }, temporary: [{ key: "k", payload: null }] } }));
  assert.equal(readCachedQr(ORG), null);
});

test("isStaleFor: other account is stale, Google sessions and same uid are not", () => {
  storeQr(ORG, "u1", "volunteer", MINE);
  const cached = readCachedQr(ORG);
  assert.equal(isStaleFor(cached, "other", false), true);
  assert.equal(isStaleFor(cached, "u1", false), false);
  assert.equal(isStaleFor(cached, "authUid", true), false);
  assert.equal(isStaleFor(null, "u1", false), false);
});

test("missingIsFinal: a cache-served missing doc never clears", () => {
  const snap = (exists, fromCache) => ({ exists: () => exists, metadata: { fromCache } });
  assert.equal(missingIsFinal(snap(false, false)), true);
  assert.equal(missingIsFinal(snap(false, true)), false);
  assert.equal(missingIsFinal(snap(true, false)), false);
});
