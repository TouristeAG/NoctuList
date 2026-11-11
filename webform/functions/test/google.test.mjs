import { test } from "node:test";
import assert from "node:assert/strict";
import * as lib from "../lib/index.js";

test("gmail.com and googlemail.com are never allowed domains", () => {
  assert.deepEqual(lib.parseDomains("gmail.com, @GoogleMail.com,asso.example"), ["asso.example"]);
  assert.equal(lib.domainAllowed("lea@gmail.com", ["gmail.com"]), false);
  assert.equal(lib.domainAllowed("lea@googlemail.com", ["googlemail.com"]), false);
  assert.equal(lib.domainAllowed("lea@asso.example", ["asso.example"]), true);
});
