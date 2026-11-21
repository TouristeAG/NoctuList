import { test } from "node:test";
import assert from "node:assert/strict";

const { guestListGroups, guestListCounts } = await import("../site/portal-common.js");

const g = (firstName, lastName, listName, extra = {}) => ({ firstName, lastName, listName, venueName: "Groove", entryUsed: false, ...extra });

test("guestListGroups: by list, then by name, with an accent-blind search", () => {
  const guests = [g("Zoé", "A", "Friends"), g("Ann", "B", "Band"), g("Éric", "C", "Band"), g("Bob", "D", "Friends")];
  const groups = guestListGroups(guests, "");
  assert.deepEqual(groups.map((x) => x.listName), ["Band", "Friends"]);
  assert.deepEqual(groups[0].guests.map((x) => x.firstName), ["Ann", "Éric"]);
  assert.deepEqual(guestListGroups(guests, "eric").flatMap((x) => x.guests.map((y) => y.firstName)), ["Éric"]);
  assert.deepEqual(guestListGroups(guests, "zz"), []);
  assert.deepEqual(guestListGroups(undefined, ""), []);
});

test("guestListCounts: entered over total", () => {
  assert.deepEqual(guestListCounts([g("A", "", "L", { entryUsed: true }), g("B", "", "L")]), { entered: 1, total: 2 });
  assert.deepEqual(guestListCounts(undefined), { entered: 0, total: 0 });
});
