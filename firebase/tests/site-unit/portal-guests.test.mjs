import { test } from "node:test";
import assert from "node:assert/strict";

const { profilesLayout, visibleLists, inviteRemaining, qrDialogClosesFor } = await import("../site/portal-common.js");

test("profilesLayout: lists go under the personal profiles", () => {
  assert.deepEqual(profilesLayout([]), { personal: [], managed: false });
  assert.deepEqual(profilesLayout(["volunteer"]), { personal: ["volunteer"], managed: false });
  assert.deepEqual(profilesLayout(["volunteer", "temporary"]), { personal: ["volunteer"], managed: true });
  assert.deepEqual(profilesLayout(["temporary"]), { personal: [], managed: true });
  assert.deepEqual(profilesLayout(["temporary", "guest", "volunteer"]), { personal: ["volunteer", "guest"], managed: true });
});

test("visibleLists: schema 2 visibleUntil, schema 1 endsAt, sorted by date", () => {
  const now = 1000;
  const lists = [
    { eventDate: 30, visibleUntil: 2000 },
    { eventDate: 10, visibleUntil: 1000 },
    { eventDate: 20, endsAt: 1500 },
    { eventDate: 5, endsAt: 999 },
    { eventDate: 1 },
  ];
  assert.deepEqual(visibleLists(lists, now).map((g) => g.eventDate), [1, 20, 30]);
  assert.deepEqual(visibleLists(undefined, now), []);
});

test("inviteRemaining: total, per access, zero means no access limit", () => {
  const invite = {
    remaining: 3,
    accesses: [{ id: "bs", max: 2, remaining: 1 }, { id: "vip", max: 0, remaining: 3 }],
  };
  const one = inviteRemaining(invite, [{ name: "A", access: ["bs"] }]);
  assert.equal(one.remaining, 2);
  assert.equal(one.accesses.bs, 0);
  assert.equal(one.ok, true);
  const twoBs = inviteRemaining(invite, [{ name: "A", access: ["bs"] }, { name: "B", access: ["bs"] }]);
  assert.equal(twoBs.accesses.bs, -1);
  assert.equal(twoBs.ok, false);
  const four = inviteRemaining(invite, ["A", "B", "C", "D"].map((name) => ({ name, access: ["vip"] })));
  assert.equal(four.remaining, -1);
  assert.equal(four.ok, false);
  const vip = inviteRemaining(invite, ["A", "B", "C"].map((name) => ({ name, access: ["vip"] })));
  assert.equal(vip.ok, true);
  assert.equal(vip.accesses.vip, 0);
  assert.equal(inviteRemaining(null, []).ok, false);
});

test("qrDialogSurvivesDeniedReload: only the profile on screen closes the QR dialog", () => {
  assert.equal(qrDialogClosesFor("volunteer", "temporary"), false);
  assert.equal(qrDialogClosesFor("temporary", "temporary"), true);
  assert.equal(qrDialogClosesFor(null, "guest"), false);
});
