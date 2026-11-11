import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as lib from "../lib/index.js";

const cases = JSON.parse(readFileSync(new URL("./validation-cases.json", import.meta.url), "utf8"));

test("shared case file is large enough", () => {
  assert.ok(cases.length >= 25);
});

cases.forEach((c, i) => {
  test(`#${i} ${c.fn} ${JSON.stringify(c.input).slice(0, 80)}`, () => {
    let actual;
    switch (c.fn) {
      case "parseSignup":
        actual = lib.parseSignup(c.input.formId, c.input.data, c.input.orgId);
        break;
      case "parseRequest":
        actual = lib.parseRequest(c.input.requestId, c.input.data);
        break;
      case "parseGoogleClaim":
        actual = lib.parseGoogleClaim(c.input.googleUid, c.input.data);
        break;
      default:
        actual = lib[c.fn](c.input);
    }
    assert.deepEqual(actual, c.expect);
  });
});
