import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as lib from "../lib/index.js";

const read = (name) => JSON.parse(readFileSync(new URL(`./${name}`, import.meta.url), "utf8"));
const cases = read("certificate-cases.json");

const text = (paragraph) => paragraph.runs.map((r) => r.text).join("");

test("shared case file is large enough", () => {
  assert.ok(cases.length >= 20);
});

for (const c of cases) {
  test(`case ${c.name}`, () => {
    if (c.template !== undefined) {
      assert.equal(c.place, undefined, "a case with a template must not carry place");
    }
    const template = c.template !== undefined
      ? lib.parseTemplate(c.template)
      : { ...lib.defaultTemplate(c.language, c.association), place: c.place };
    assert.ok(template, "template");
    const actual = lib.assembleCertificate(template, c.association, c.person, c.types, c.jobs, c.selection);
    if (c.expect === null) {
      assert.equal(actual, null);
      return;
    }
    assert.ok(actual, "assembly");
    assert.deepEqual(actual.body.map(text), c.expect.body, "body");
    assert.deepEqual(actual.footer.map(text), c.expect.footer, "footer");
    for (const [index, runs] of Object.entries(c.expect.bodyRuns ?? {})) {
      assert.deepEqual(actual.body[Number(index)].runs, runs, `runs of paragraph ${index}`);
    }
  });
}

test("default French template equals the Kotlin snapshot", () => {
  assert.deepEqual(lib.defaultTemplate("fr", "X"), read("certificate-default-fr.json"));
});

test("unknown language falls back to English", () => {
  assert.equal(lib.defaultTemplate("xx", "A").language, "en");
});

test("dates", () => {
  assert.equal(lib.longDate(20260312, "fr"), "12 mars 2026");
  assert.equal(lib.longDate(20260301, "fr"), "1er mars 2026");
  assert.equal(lib.longDate(20260312, "en"), "12 March 2026");
  assert.equal(lib.longDate(20260312, "de"), "12. März 2026");
  assert.equal(lib.longDate(20260312, "it"), "12 marzo 2026");
  assert.equal(lib.longDate(20260312, "es"), "12 de marzo de 2026");
  assert.equal(lib.monthYear(20260312, "fr"), "mars 2026");
  assert.equal(lib.monthYear(20260312, "es"), "marzo de 2026");
});

test("periods", () => {
  assert.equal(lib.period(20260305, 20261020, "fr"), "entre mars et octobre 2026");
  assert.equal(lib.period(20250405, 20260820, "fr"), "entre avril 2025 et août 2026");
  assert.equal(lib.period(20260302, 20260328, "fr"), "en mars 2026");
  assert.equal(lib.period(20260305, 20261020, "en"), "between March and October 2026");
  assert.equal(lib.period(20260302, 20260328, "de"), "im März 2026");
  assert.equal(lib.period(20260305, 20261020, "es"), "entre marzo y octubre de 2026");
  assert.equal(lib.period(20260302, 20260328, "it"), "a marzo 2026");
  assert.equal(lib.period(20250405, 20260820, "es"), "entre abril de 2025 y agosto de 2026");
});

test("dayOf uses Europe/Zurich", () => {
  assert.equal(lib.dayOf(1773358200000), 20260313);
});

test("displayName", () => {
  assert.equal(lib.displayName("DUPONT"), "Dupont");
  assert.equal(lib.displayName("  ÉLODIE-ANNE O'NEIL "), "Élodie-Anne O'Neil");
  assert.equal(lib.displayName("McDonald"), "McDonald");
});

test("categoryOf", () => {
  assert.equal(lib.categoryOf(undefined), "OTHER");
  assert.equal(lib.categoryOf({ isShiftJob: true, isOrionJob: true }), "MANDATE");
  assert.equal(lib.categoryOf({}), "SHIFT");
  assert.equal(lib.categoryOf({ novaJobType: "MEETING" }), "MEETING");
  assert.equal(lib.categoryOf({ novaJobType: "PHOTOGRAPHER_VIDEOGRAPHER" }), "PHOTO");
  assert.equal(lib.categoryOf({ novaJobType: "GRAPHIC_DESIGNER_EVENT" }), "GRAPHIC_EVENT");
  assert.equal(lib.categoryOf({ novaJobType: "GRAPHIC_DESIGNER_ASSOCIATION" }), "GRAPHIC_ASSOCIATION");
  assert.equal(lib.categoryOf({ isShiftJob: false }), "OTHER");
});

test("parseTemplate", () => {
  assert.equal(lib.parseTemplate("{"), null);
  assert.equal(lib.parseTemplate(null), null);
  assert.equal(lib.parseTemplate({ language: 3 }), null);
  assert.equal(lib.parseTemplate({ blocks: { x: { one: [{ align: "NOPE" }] } } }), null);
  assert.equal(lib.parseTemplate({ language: "de", extra: 1 }).language, "de");
  assert.deepEqual(lib.parseTemplate(JSON.stringify(lib.defaultTemplate("it", "A"))), lib.defaultTemplate("it", "A"));
});
