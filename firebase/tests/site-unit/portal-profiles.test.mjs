import { test } from "node:test";
import assert from "node:assert/strict";

const { chooseProfile, profileKinds, profilesSettled, profileStoreKey } = await import("../site/portal-common.js");

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
  assert.deepEqual(profileKinds({ google: true, verified: true }), ["volunteer", "guest"]);
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
