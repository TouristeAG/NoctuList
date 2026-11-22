import { test } from "node:test";
import assert from "node:assert/strict";

const { splitShifts } = await import("../site/portal-common.js");

test("splitShifts: a shift stays upcoming until its pastFrom (the synced day change)", () => {
  const friday = 1_000_000;
  const shift = { date: friday, pastFrom: friday + 30 * 3_600_000 };
  const at3 = splitShifts([shift], friday + 27 * 3_600_000);
  assert.deepEqual(at3.upcoming, [shift]);
  assert.deepEqual(at3.past, []);
  const at6 = splitShifts([shift], shift.pastFrom);
  assert.deepEqual(at6.past, [shift]);
});

test("splitShifts: an older page without pastFrom falls back to the shift date", () => {
  const shift = { date: 5000 };
  assert.deepEqual(splitShifts([shift], 4999).upcoming, [shift]);
  assert.deepEqual(splitShifts([shift], 5000).past, [shift]);
  assert.deepEqual(splitShifts(null, 1), { past: [], upcoming: [] });
});
