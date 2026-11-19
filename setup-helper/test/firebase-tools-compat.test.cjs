const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const lib = path.join(__dirname, "..", "node_modules", "firebase-tools", "lib");

test("firebase-tools exposes the internals the helper relies on", () => {
  assert.strictEqual(typeof require(path.join(lib, "requireAuth")).requireAuth, "function");
  const apiv2 = require(path.join(lib, "apiv2"));
  assert.strictEqual(typeof apiv2.getAccessToken, "function");
  assert.ok(apiv2.Client);
  assert.strictEqual(typeof require(path.join(lib, "auth")).getGlobalDefaultAccount, "function");
});
