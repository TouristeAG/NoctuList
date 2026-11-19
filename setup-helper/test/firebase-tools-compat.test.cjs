const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const lib = path.join(__dirname, "..", "node_modules", "firebase-tools", "lib");

test("firebase-tools exposes the internals the helper relies on", () => {
  assert.strictEqual(typeof require(path.join(lib, "requireAuth")).requireAuth, "function");
  const apiv2 = require(path.join(lib, "apiv2"));
  assert.strictEqual(typeof apiv2.getAccessToken, "function");
  assert.ok(apiv2.Client);
  const auth = require(path.join(lib, "auth"));
  assert.strictEqual(typeof auth.getGlobalDefaultAccount, "function");
  // requireAuth only uses the signed-in user when the caller supplies options.user and options.tokens.
  assert.match(require("node:fs").readFileSync(path.join(lib, "requireAuth.js"), "utf8"), /options\.user/);
});
