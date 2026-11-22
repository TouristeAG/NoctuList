import { test } from "node:test";
import assert from "node:assert/strict";

const {
  canSend, certificateState, refusalNote, defaultPeriod, defaultSelection, groupTypes, needsFullName, periodValid, profileFields, requestFields, typesInPeriod,
} = await import("../site/portal-certificate.js");

const TYPES = [
  { name: "Réunion", category: "MEETING", days: [20260301] },
  { name: "Bar", category: "SHIFT", days: [20260110, 20260214] },
  { name: "Affiche", category: "GRAPHIC_EVENT", days: [20260105] },
  { name: "Accueil", category: "SHIFT", days: [20260320] },
  { name: "Comité", category: "MANDATE", days: [20251201, 20260401] },
];

test("groups types in document order", () => {
  const groups = groupTypes(TYPES);
  assert.deepEqual(groups.map((g) => g.category), ["MANDATE", "SHIFT", "GRAPHIC_EVENT", "MEETING"]);
  const shift = groups.find((g) => g.category === "SHIFT");
  assert.deepEqual(shift.types.map((x) => x.name), ["Accueil", "Bar"]);
  assert.deepEqual(shift.types[1], { name: "Bar", firstDay: 20260110, lastDay: 20260214, days: [20260110, 20260214] });
});

test("meetings are unchecked by default", () => {
  const sel = defaultSelection(TYPES);
  assert.equal(sel.has("Réunion"), false);
  assert.deepEqual([...sel].sort(), ["Accueil", "Affiche", "Bar", "Comité"]);
});

test("default period runs from the first job to today", () => {
  assert.deepEqual(defaultPeriod(TYPES, "2026-11-22"), { from: "2025-12-01", to: "2026-11-22" });
});

test("types outside the period are excluded", () => {
  const inside = typesInPeriod(TYPES, "2026-01-01", "2026-02-28");
  assert.deepEqual([...inside].sort(), ["Affiche", "Bar"]);
  assert.equal(typesInPeriod(TYPES, "2026-03-20", "2026-03-20").has("Accueil"), true);
});

test("cannot send an empty selection", () => {
  assert.equal(canSend(new Set(), new Set(["Bar"])), false);
  assert.equal(canSend(new Set(["Bar"]), new Set(["Affiche"])), false);
  assert.equal(canSend(new Set(["Bar", "Affiche"]), new Set(["Bar"])), true);
});

test("needsFullName", () => {
  assert.equal(needsFullName("D."), true);
  assert.equal(needsFullName("D"), true);
  assert.equal(needsFullName("Du"), false);
  assert.equal(needsFullName("Li"), false);
  assert.equal(needsFullName("Dupont"), false);
  assert.equal(needsFullName("O'"), true);
  assert.equal(needsFullName(""), true);
  assert.equal(needsFullName(undefined), true);
});

test("request fields are sorted and dated", () => {
  assert.deepEqual(requestFields("2026-01-01", "2026-02-28", new Set(["Bar", "Affiche"])),
    { from: "2026-01-01", to: "2026-02-28", jobTypes: ["Affiche", "Bar"] });
});

test("period validity", () => {
  const today = "2026-11-22";
  assert.equal(periodValid("2026-01-01", "2026-11-22", today), true);
  assert.equal(periodValid("2026-01-01", "2026-01-01", today), true);
  assert.equal(periodValid("", "2026-01-01", today), false);
  assert.equal(periodValid("2026-01-01", "", today), false);
  assert.equal(periodValid("1999-12-31", "2026-01-01", today), false);
  assert.equal(periodValid("2026-02-01", "2026-01-01", today), false);
  assert.equal(periodValid("2026-01-01", "2026-11-23", today), false);
  assert.equal(periodValid("2026-1-1", "2026-01-01", today), false);
});

test("job types with a name over 80 characters are left out", () => {
  const long = { name: "x".repeat(81), category: "SHIFT", days: [20260101] };
  const ok = { name: "y".repeat(80), category: "SHIFT", days: [20260101] };
  assert.deepEqual(groupTypes([long, ok])[0].types.map((x) => x.name), [ok.name]);
  assert.equal(defaultSelection([long, ok]).has(long.name), false);
  assert.equal(typesInPeriod([long, ok], "2026-01-01", "2026-01-02").has(long.name), false);
});

test("profile fields need a first name, a full last name and a phone", () => {
  assert.deepEqual(profileFields("Anna", "Dupont", "+41 79 000 00 00"), { firstName: "Anna", lastName: "Dupont", phone: "+41 79 000 00 00" });
  assert.equal(profileFields("Anna", "Dupont", null), null);
  assert.equal(profileFields(null, "Dupont", "+41 79 000 00 00"), null);
  assert.equal(profileFields("Anna", "D.", "+41 79 000 00 00"), null);
});

test("certificate state of the request", () => {
  const now = Date.parse("2026-11-22T12:00:00Z");
  const at = (min) => now - min * 60_000;
  assert.equal(certificateState(undefined, now), "none");
  assert.equal(certificateState({ status: "PENDING", requestedAt: at(1) }, now), "waiting");
  assert.equal(certificateState({ status: "PENDING" }, now), "waiting");
  assert.equal(certificateState({ status: "PROCESSING", requestedAt: at(2), processingAt: at(1) }, now), "waiting");
  assert.equal(certificateState({ status: "PENDING_REVIEW", reviewAt: at(1) }, now), "review");
  assert.equal(certificateState({ status: "PENDING_REVIEW", reason: "SEND_FAILED", processingAt: at(30) }, now), "review");
  assert.equal(certificateState({ status: "APPROVED", approvalId: "a" }, now), "review");
  assert.equal(certificateState({ status: "PROCESSING", approvalId: "a", processingAt: at(20) }, now), "review");
  assert.equal(certificateState({ status: "DONE" }, now), "sent");
  assert.equal(certificateState({ status: "REJECTED", reason: "REFUSED", note: "x" }, now), "refused");
  assert.equal(certificateState({ status: "REJECTED", reason: "NO_EMAIL" }, now), "failed");
  assert.equal(certificateState({ status: "REJECTED" }, now), "failed");
  assert.equal(certificateState({ status: "PROCESSING", processingAt: at(30) }, now), "failed");
});

test("refusal note is plain trimmed text, empty when missing", () => {
  assert.equal(refusalNote({ note: "  Dates <b>fausses</b>  " }), "Dates <b>fausses</b>");
  assert.equal(refusalNote({ note: "" }), "");
  assert.equal(refusalNote({}), "");
  assert.equal(refusalNote({ note: 3 }), "");
});
