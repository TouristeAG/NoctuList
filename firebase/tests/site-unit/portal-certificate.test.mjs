import { test } from "node:test";
import assert from "node:assert/strict";

const {
  canSend, defaultPeriod, defaultSelection, groupTypes, needsFullName, requestFields, typesInPeriod,
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
  assert.equal(needsFullName("Du"), true);
  assert.equal(needsFullName("Dupont"), false);
  assert.equal(needsFullName("O'"), true);
  assert.equal(needsFullName(""), true);
  assert.equal(needsFullName(undefined), true);
});

test("request fields are sorted and dated", () => {
  assert.deepEqual(requestFields("2026-01-01", "2026-02-28", new Set(["Bar", "Affiche"])),
    { from: "2026-01-01", to: "2026-02-28", jobTypes: ["Affiche", "Bar"] });
});
