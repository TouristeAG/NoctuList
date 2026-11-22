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

test("guestListOnly: the guest list shows even without another profile", async () => {
  const { guestListOnly } = await import("../site/portal-common.js");
  const now = 1000;
  assert.equal(guestListOnly([], [{ eventDate: 1, visibleUntil: 2000 }], now), true);
  assert.equal(guestListOnly([], [{ eventDate: 1, visibleUntil: 500 }], now), false);
  assert.equal(guestListOnly(["volunteer"], [{ eventDate: 1, visibleUntil: 2000 }], now), false);
  assert.equal(guestListOnly([], undefined, now), false);
});

const { guestListAddEdits } = await import("../site/portal-common.js");

test("guestListAddEdits: one ADD per person, list, contact and notes shared", () => {
  const r = guestListAddEdits({
    people: [
      { firstName: " Ann ", lastName: "Doe", accessIds: ["bs", "bs"] },
      { firstName: "", lastName: "", accessIds: ["vip"] },
      { firstName: "Bob", lastName: "", accessIds: [] },
    ],
    listName: " Friends ", venueName: "", newList: true,
    phone: " +41 79 555 44 33 ", contactEmail: " Me@Mail.example ", notes: " After midnight ",
  });
  assert.equal(r.error, undefined);
  assert.deepEqual(r.edits, [
    { action: "ADD", listName: "Friends", venueName: "", notes: "After midnight", phone: "+41 79 555 44 33", contactEmail: "me@mail.example", firstName: "Ann", lastName: "Doe", accessIds: ["bs"] },
    { action: "ADD", listName: "Friends", venueName: "", notes: "After midnight", phone: "+41 79 555 44 33", contactEmail: "me@mail.example", firstName: "Bob", lastName: "", accessIds: [] },
  ]);
});

test("guestListAddEdits: an existing list sends no contact", () => {
  const r = guestListAddEdits({ people: [{ firstName: "Ann" }], listName: "Band", venueName: "Groove", newList: false, phone: "+41 79 1", contactEmail: "me@mail.example" });
  assert.equal("phone" in r.edits[0], false);
  assert.equal("contactEmail" in r.edits[0], false);
});

test("guestListAddEdits: errors", () => {
  const base = { people: [{ firstName: "Ann" }], listName: "Band", newList: true };
  assert.equal(guestListAddEdits({ ...base, people: [{ firstName: "", lastName: "" }] }).error, "errName");
  assert.equal(guestListAddEdits({ ...base, people: [{ firstName: "", lastName: "Doe" }] }).error, "errName");
  assert.equal(guestListAddEdits({ ...base, people: [{ firstName: "<b>" }] }).error, "errName");
  assert.equal(guestListAddEdits({ ...base, listName: " " }).error, "guestListNeedsName");
  assert.equal(guestListAddEdits({ ...base, phone: "call me" }).error, "errPhone");
  assert.equal(guestListAddEdits({ ...base, contactEmail: "nope" }).error, "errEmail");
  assert.equal(guestListAddEdits({ ...base, notes: "x".repeat(501) }).error, "notesTooLong");
});
