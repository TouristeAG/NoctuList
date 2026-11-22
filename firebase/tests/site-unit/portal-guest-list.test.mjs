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

const { reviewSummary, guestListReviewEdit } = await import("../site/portal-common.js");

const addReview = {
  reviewKey: "0123456789abcdef", source: "FORM", kind: "ADD", listName: "Groove Collective", venueName: "Groove",
  contactEmail: "band@mail.example", emergencyPhone: "+41 79 1", notes: "", requestedBy: "",
  people: [{ name: "Ann Doe", accessIds: ["acc-bs"] }, { name: "Bob", accessIds: [] }],
};
const removeReview = {
  reviewKey: "fedcba9876543210", source: "PORTAL", kind: "REMOVE", listName: "Les Amis", venueName: "Groove",
  contactEmail: "", requestedBy: "Marie Dupont", removeGuests: [{ guestKey: "a1b2c3d4e5f60007", name: "Hugo L" }],
};

test("reviewSummary: title, count, source", () => {
  assert.deepEqual(reviewSummary(addReview), { title: "Groove Collective", count: 2, sourceKey: "reviewFromForm" });
  assert.deepEqual(reviewSummary(removeReview), { title: "Les Amis", count: 1, sourceKey: "reviewFromPortal" });
  assert.equal(reviewSummary({ ...removeReview, listName: " " }).title, "Marie Dupont");
  assert.equal(reviewSummary({ ...addReview, listName: "", requestedBy: "" }).title, "band@mail.example");
  assert.deepEqual(reviewSummary({}), { title: "", count: 0, sourceKey: "reviewFromForm" });
});

test("guestListReviewEdit: ACCEPT cleans the people", () => {
  const r = guestListReviewEdit({
    review: addReview, accept: true,
    people: [{ name: "  Ann   Doe ", accessIds: ["acc-bs", "acc-bs", "acc-vip"] }, { name: "  ", accessIds: ["acc-vip"] }, { name: "Bob" }],
    contactEmail: " Band@Mail.example ", phone: " +41 79 555 44 33 ", notes: " Late ",
  });
  assert.deepEqual(r, {
    action: "ACCEPT", reviewKey: "0123456789abcdef",
    people: [{ name: "Ann Doe", accessIds: ["acc-bs", "acc-vip"] }, { name: "Bob", accessIds: [] }],
    contactEmail: "band@mail.example", phone: "+41 79 555 44 33", notes: "Late",
  });
  assert.deepEqual(guestListReviewEdit({ review: removeReview, accept: true, people: [{ name: "x" }] }), { action: "ACCEPT", reviewKey: "fedcba9876543210" });
});

test("guestListReviewEdit: REJECT carries only action and reviewKey", () => {
  assert.deepEqual(guestListReviewEdit({ review: addReview, accept: false, people: [{ name: "<b>" }], contactEmail: "nope" }),
    { action: "REJECT", reviewKey: "0123456789abcdef" });
  assert.deepEqual(guestListReviewEdit({ review: removeReview, accept: false }), { action: "REJECT", reviewKey: "fedcba9876543210" });
});

test("guestListReviewEdit: invalid name or nobody left gives null", () => {
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [{ name: "<b>" }] }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [{ name: "x".repeat(61) }] }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [{ name: " " }] }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [] }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: Array.from({ length: 101 }, () => ({ name: "A" })) }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [{ name: "Ann" }], phone: "call me" }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [{ name: "Ann" }], contactEmail: "nope" }), null);
  assert.equal(guestListReviewEdit({ review: addReview, accept: true, people: [{ name: "Ann" }], notes: "x".repeat(501) }), null);
});
