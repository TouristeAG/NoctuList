const test = require("node:test");
const assert = require("node:assert");
const { run, buildAuth, guardStdout } = require("../noctulist-gcp.cjs");

const P = "my-proj-123";
function make(handler) {
  const calls = [];
  const deps = {
    token: async () => "tok",
    sleep: async () => {},
    request: async (method, url, body, headers) => {
      calls.push({ method, url, body, headers });
      return handler({ method, url, body, headers }, calls);
    },
  };
  return { deps, calls };
}
const ok = (body) => ({ status: 200, body });

test("everyCallExceptServiceUsageSetsUserProject", async () => {
  const h = (r) => {
    if (r.url.includes("getIamPolicy")) return ok({ etag: "e", bindings: [] });
    if (r.url.includes("setIamPolicy")) return ok({});
    if (r.url.includes("billingAccounts")) return ok({ billingAccounts: [] });
    if (r.url.includes("billingInfo")) return ok({ billingEnabled: false });
    if (r.url.includes("batchEnable")) return ok({ name: "operations/x", done: true });
    return ok({ projectNumber: "42" });
  };
  for (const argv of [
    ["lib", "billing-status", "--project", P],
    ["lib", "ensure-role", "--project", P, "--member", "user:a@b.c", "--role", "roles/editor"],
    ["lib", "project-number", "--project", P],
  ]) {
    const { deps, calls } = make(h);
    await run(argv, deps);
    assert.ok(calls.length > 0);
    for (const c of calls) assert.strictEqual(c.headers["x-goog-user-project"], P);
  }
  const { deps, calls } = make(h);
  await run(["lib", "enable-apis", "--project", P, "--apis", "a.googleapis.com"], deps);
  for (const c of calls) assert.strictEqual(c.headers["x-goog-user-project"], undefined);
});

test("ensureRoleAddsMissingBindingOnce", async () => {
  let policy = { etag: "E1", bindings: [{ role: "roles/editor", members: ["user:z@z.z"] }] };
  const { deps, calls } = make((r) => {
    if (r.url.endsWith(":getIamPolicy")) return ok(policy);
    policy = r.body.policy;
    return ok(policy);
  });
  const argv = ["lib", "ensure-role", "--project", P, "--member", "user:a@b.c", "--role", "roles/run.invoker"];
  const out = JSON.parse(await run(argv, deps));
  assert.deepStrictEqual(out, { ok: true, data: { changed: true } });
  const sets = calls.filter((c) => c.url.endsWith(":setIamPolicy"));
  assert.strictEqual(sets.length, 1);
  assert.strictEqual(sets[0].body.policy.etag, "E1");
  assert.ok(sets[0].body.policy.bindings.some((b) => b.role === "roles/run.invoker" && b.members.includes("user:a@b.c")));
  calls.length = 0;
  const out2 = JSON.parse(await run(argv, deps));
  assert.deepStrictEqual(out2, { ok: true, data: { changed: false } });
  assert.strictEqual(calls.filter((c) => c.url.endsWith(":setIamPolicy")).length, 0);
});

test("ensureRoleRetriesOnEtagConflict", async () => {
  let sets = 0;
  const { deps } = make((r) => {
    if (r.url.endsWith(":getIamPolicy")) return ok({ etag: "e", bindings: [] });
    sets++;
    return sets === 1 ? { status: 409, body: { error: { code: 409, status: "ABORTED", message: "x" } } } : ok({});
  });
  const out = JSON.parse(await run(["lib", "ensure-role", "--project", P, "--member", "user:a@b.c", "--role", "roles/editor"], deps));
  assert.deepStrictEqual(out, { ok: true, data: { changed: true } });
  assert.strictEqual(sets, 2);
});

test("enableApisPollsUntilDone", async () => {
  let polls = 0;
  const { deps } = make((r) => {
    if (r.method === "POST") return ok({ name: "operations/abc", done: false });
    polls++;
    return ok({ name: "operations/abc", done: polls >= 3 });
  });
  const out = JSON.parse(await run(["lib", "enable-apis", "--project", P, "--apis", "a.googleapis.com,b.googleapis.com"], deps));
  assert.deepStrictEqual(out, { ok: true, data: { enabled: ["a.googleapis.com", "b.googleapis.com"] } });
  assert.strictEqual(polls, 3);
});

test("errorsAreOneJsonLine", async () => {
  const { deps } = make(() => ({
    status: 403,
    body: { error: { code: 403, status: "PERMISSION_DENIED", message: "nope",
      details: [{ reason: "SERVICE_DISABLED", metadata: { service: "cloudbilling.googleapis.com" } }] } },
  }));
  const line = await run(["lib", "billing-status", "--project", P], deps);
  assert.ok(!line.includes("\n"));
  const out = JSON.parse(line);
  assert.strictEqual(out.ok, false);
  assert.deepStrictEqual(out.error, { status: 403, reason: "SERVICE_DISABLED", message: "nope", service: "cloudbilling.googleapis.com" });
});

test("invalidArgumentsAndUnknownCommand", async () => {
  const { deps } = make(() => ok({}));
  let out = JSON.parse(await run(["lib", "project-number", "--project", "BAD"], deps));
  assert.strictEqual(out.error.reason, "INVALID_ARGUMENT");
  assert.strictEqual(out.error.status, 0);
  out = JSON.parse(await run(["lib", "nope", "--project", P], deps));
  assert.strictEqual(out.error.reason, "UNKNOWN_COMMAND");
});

test("functionsStatusFiltersCodebase", async () => {
  const { deps } = make(() => ok({ functions: [
    { name: `projects/${P}/locations/europe-west1/functions/sendMail`, state: "FAILED",
      labels: { "firebase-functions-codebase": "noctulist" }, stateMessages: [{ message: "boom" }] },
    { name: `projects/${P}/locations/us-central1/functions/other`, state: "ACTIVE", labels: {} },
  ] }));
  const out = JSON.parse(await run(["lib", "functions-status", "--project", P, "--codebase", "noctulist"], deps));
  assert.deepStrictEqual(out.data.functions, [{ name: "sendMail", region: "europe-west1", state: "FAILED", messages: ["boom"] }]);
});

test("ensureMailSaCreatesAccountWithoutKeyAndGrantsTokenCreator", async () => {
  const email = `noctulist-mail@${P}.iam.gserviceaccount.com`;
  const { deps, calls } = make((r) => {
    if (r.url.includes("cloudresourcemanager")) return ok({ projectNumber: "777" });
    if (r.url.includes("batchEnable")) return ok({ name: "operations/o", done: true });
    if (r.url.endsWith(":getIamPolicy")) return ok({ etag: "e", bindings: [] });
    if (r.url.endsWith(":setIamPolicy")) return ok({});
    if (r.method === "GET") return { status: 404, body: { error: { code: 404, status: "NOT_FOUND", message: "no" } } };
    return ok({ email, oauth2ClientId: "client-1" });
  });
  const out = JSON.parse(await run(["lib", "ensure-mail-sa", "--project", P], deps));
  assert.deepStrictEqual(out, { ok: true, data: { email, clientId: "client-1" } });
  assert.ok(!calls.some((c) => c.url.includes("/keys")));
  const sets = calls.filter((c) => c.url.endsWith(":setIamPolicy"));
  assert.strictEqual(sets.length, 1);
  assert.ok(sets[0].url.includes(`/serviceAccounts/${email}:setIamPolicy`));
  assert.deepStrictEqual(sets[0].body.policy.bindings, [{ role: "roles/iam.serviceAccountTokenCreator", members: ["serviceAccount:777-compute@developer.gserviceaccount.com"] }]);
});

test("policyVersion3ConditionalBindingSurvives", async () => {
  const cond = { role: "roles/editor", members: ["user:z@z.z"], condition: { title: "t", expression: "true" } };
  let written, asked;
  const { deps } = make((r) => {
    if (r.url.endsWith(":getIamPolicy")) { asked = r.body; return ok({ etag: "e", version: 1, bindings: [cond] }); }
    written = r.body.policy; return ok({});
  });
  await run(["lib", "ensure-role", "--project", P, "--member", "user:a@b.c", "--role", "roles/editor"], deps);
  assert.deepStrictEqual(asked, { options: { requestedPolicyVersion: 3 } });
  assert.strictEqual(written.version, 3);
  assert.deepStrictEqual(written.bindings[0], cond);
  assert.strictEqual(written.bindings.length, 2);
});

test("notLoggedInMapping", async () => {
  const noAcct = buildAuth({ auth: { getGlobalDefaultAccount: () => undefined }, requireAuth: async () => {}, apiv2: {} });
  const revoked = buildAuth({
    auth: { getGlobalDefaultAccount: () => ({ user: { email: "a@b.c" }, tokens: {} }) },
    requireAuth: async () => { throw new Error("invalid_grant"); }, apiv2: {},
  });
  for (const a of [noAcct, revoked]) {
    const out = JSON.parse(await run(["lib", "project-number", "--project", P], { ...make(() => ok({})).deps, token: a.token }));
    assert.strictEqual(out.error.status, 401);
    assert.strictEqual(out.error.reason, "NOT_LOGGED_IN");
  }
});

test("buildAuthPassesAccountToRequireAuth", async () => {
  let seen;
  const a = buildAuth({
    auth: { getGlobalDefaultAccount: () => ({ user: { email: "a@b.c" }, tokens: { refresh_token: "r" } }) },
    requireAuth: async (o) => { seen = o; },
    apiv2: { getAccessToken: async () => "tok" },
  });
  assert.strictEqual(await a.token(), "tok");
  assert.deepStrictEqual(seen, { user: { email: "a@b.c" }, tokens: { refresh_token: "r" } });
  assert.strictEqual(await a.email(), "a@b.c");
});

test("checkAuthReturnsEmail", async () => {
  const { deps } = make(() => ok({}));
  deps.email = async () => "a@b.c";
  assert.deepStrictEqual(JSON.parse(await run(["lib", "check-auth"], deps)), { ok: true, data: { email: "a@b.c" } });
});

test("guardStdoutRoutesConsoleToStderr", () => {
  const saved = { log: console.log, info: console.info, debug: console.debug, warn: console.warn };
  const ow = process.stdout.write, oe = process.stderr.write;
  const out = [], err = [];
  process.stdout.write = (x) => { out.push(String(x)); return true; };
  process.stderr.write = (x) => { err.push(String(x)); return true; };
  let writer;
  try {
    writer = guardStdout();
    console.log("noise");
    console.warn("warn");
  } finally {
    process.stdout.write = ow; process.stderr.write = oe;
    Object.assign(console, saved);
  }
  assert.deepStrictEqual(out, []);
  assert.strictEqual(err.length, 2);
  assert.strictEqual(typeof writer, "function");
});

test("batchEnableWithoutOperationFails", async () => {
  const { deps } = make(() => ok({}));
  const out = JSON.parse(await run(["lib", "enable-apis", "--project", P, "--apis", "a.googleapis.com"], deps));
  assert.strictEqual(out.ok, false);
});

test("serviceAccountCreate409RereadsAccount", async () => {
  const email = `noctulist-mail@${P}.iam.gserviceaccount.com`;
  let gets = 0;
  const { deps } = make((r) => {
    if (r.url.includes("cloudresourcemanager")) return ok({ projectNumber: "7" });
    if (r.url.includes("batchEnable")) return ok({ name: "o", done: true });
    if (r.url.endsWith(":getIamPolicy")) return ok({ etag: "e" });
    if (r.url.endsWith(":setIamPolicy")) return ok({});
    if (r.method === "GET") return ++gets === 1 ? { status: 404, body: { error: { code: 404, status: "NOT_FOUND", message: "n" } } } : ok({ oauth2ClientId: "cid" });
    return { status: 409, body: { error: { code: 409, status: "ALREADY_EXISTS", message: "x" } } };
  });
  const out = JSON.parse(await run(["lib", "ensure-mail-sa", "--project", P], deps));
  assert.deepStrictEqual(out.data, { email, clientId: "cid" });
});
