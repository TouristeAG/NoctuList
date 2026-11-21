import { test } from "node:test";
import assert from "node:assert/strict";

const { chooseProfile, claimReloadKey, claimRetryDelays, profileKinds, profilesSettled, profileStoreKey } = await import("../site/portal-common.js");

test("noProfileChoosesNothing", () => {
  assert.equal(chooseProfile([], "guest"), null);
  assert.equal(chooseProfile([], null), null);
});

test("oneProfileIsShownWhateverWasStored", () => {
  assert.equal(chooseProfile(["temporary"], null), "temporary");
  assert.equal(chooseProfile(["guest"], "volunteer"), "guest");
});

test("threeProfilesUseTheLastChoiceElseTheFirst", () => {
  const all = ["temporary", "volunteer", "guest"];
  assert.equal(chooseProfile(all, "guest"), "guest");
  assert.equal(chooseProfile(all, "temporary"), "temporary");
  assert.equal(chooseProfile(all, null), "volunteer");
  assert.equal(chooseProfile(all, "bogus"), "volunteer");
  assert.equal(chooseProfile(["temporary", "guest"], "volunteer"), "guest");
});

test("kindsReadPerSession", () => {
  assert.deepEqual(profileKinds({ google: false, verified: true }), ["volunteer", "guest", "temporary"]);
  // A Google session reads the temporary lists of its account (rules v18).
  assert.deepEqual(profileKinds({ google: true, verified: true }), ["volunteer", "guest", "temporary"]);
  assert.deepEqual(profileKinds({ google: true }), ["volunteer", "guest", "temporary"]);
  assert.deepEqual(profileKinds({ google: false, verified: false }), ["temporary"]);
});

test("settledOnlyOnceEveryReadAnswered", () => {
  assert.deepEqual(profilesSettled({ volunteer: "present", guest: "loading" }), { done: false, available: ["volunteer"] });
  assert.deepEqual(profilesSettled({ volunteer: "absent", guest: "denied", temporary: "present" }), { done: true, available: ["temporary"] });
  assert.deepEqual(profilesSettled({ volunteer: "absent", guest: "denied" }), { done: true, available: [] });
});

test("storeKeyPerOrg", () => {
  assert.equal(profileStoreKey("org1"), "noctulist.portalProfile.org1");
});

test("aDoneClaimReloadsTheRefusedPagesOncePerClaim", () => {
  const done = { status: "DONE", requestedAt: 1000 };
  assert.equal(claimReloadKey(null, ""), null);
  assert.equal(claimReloadKey({ status: "PENDING", requestedAt: 1000 }, ""), null);
  assert.equal(claimReloadKey({ status: "REJECTED", requestedAt: 1000 }, ""), null);
  const key = claimReloadKey(done, "");
  assert.ok(key);
  assert.equal(claimReloadKey(done, key), null);
  assert.ok(claimReloadKey({ status: "DONE", requestedAt: 2000 }, key));
  // A Firestore timestamp works like millis.
  assert.equal(claimReloadKey({ status: "DONE", requestedAt: { toMillis: () => 1000 } }, ""), key);
});

test("claimRetryDelaysAreABoundedBackoff", () => {
  const delays = claimRetryDelays();
  assert.equal(delays[0], 0);
  assert.ok(delays.length > 3);
  delays.forEach((d, i) => assert.ok(i === 0 || d >= delays[i - 1], `not monotonic at ${i}`));
  const total = delays.reduce((a, b) => a + b, 0);
  assert.ok(total >= 2 * 60_000, `too short: ${total}`);
  assert.ok(total <= 3.5 * 60_000, `too long: ${total}`);
});

const { claimCanAsk, claimWithPages } = await import("../site/portal-common.js");

test("claimCanAskRespectsTheOneMinuteRenewalAndOurOwnThrottle", () => {
  const now = 10_000_000;
  assert.equal(claimCanAsk(null, 0, now), true);
  assert.equal(claimCanAsk(null, now - 30_000, now), false, "sent from this page less than a minute ago");
  assert.equal(claimCanAsk({ status: "PENDING", requestedAt: now - 30_000 }, 0, now), false, "still waiting");
  assert.equal(claimCanAsk({ status: "DONE", requestedAt: now - 30_000 }, 0, now), false, "renewed less than a minute ago");
  assert.equal(claimCanAsk({ status: "DONE", requestedAt: now - 61_000 }, 0, now), true);
  assert.equal(claimCanAsk({ status: "REJECTED", requestedAt: now - 61_000 }, 0, now), true);
});

test("aVerifiedPasswordSessionWithPagesClaimsOncePerSession", () => {
  const now = 10_000_000;
  const base = { google: false, verified: true, claim: null, sentThisSession: false, lastSentAt: 0 };
  assert.equal(claimWithPages(base, now), true);
  assert.equal(claimWithPages({ ...base, claim: { status: "DONE", requestedAt: now - 3_600_000 } }, now), true,
    "an old DONE claim does not link a profile opened since");
  assert.equal(claimWithPages({ ...base, sentThisSession: true }, now), false, "once per session");
  assert.equal(claimWithPages({ ...base, claim: undefined }, now), false, "waits for the claim listener");
  assert.equal(claimWithPages({ ...base, claim: { status: "PENDING", requestedAt: now - 10_000 } }, now), false);
  assert.equal(claimWithPages({ ...base, claim: { status: "DONE", requestedAt: now - 20_000 } }, now), false,
    "a recent DONE is within the renewal delay");
});

test("googleAndUnverifiedSessionsNeverClaimWithPages", () => {
  const base = { claim: null, sentThisSession: false, lastSentAt: 0 };
  assert.equal(claimWithPages({ ...base, google: true, verified: true }), false);
  assert.equal(claimWithPages({ ...base, google: false, verified: false }), false);
});
