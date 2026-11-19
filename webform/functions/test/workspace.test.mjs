// Unit tests of the keyless Workspace transport (fetch injected, no real Google endpoint).
import { test } from "node:test";
import assert from "node:assert/strict";
import * as lib from "../lib/index.js";

const SA = "noctulist-mail@proj.iam.gserviceaccount.com";
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const b64 = (s) => Buffer.from(s).toString("base64url");

/** Fake Google: records calls; signJwt returns a "jwt" whose payload is the posted claims. */
function fakeGoogle({ exchange } = {}) {
  const calls = [];
  const f = async (url, init = {}) => {
    calls.push({ url: String(url), init });
    const u = String(url);
    if (u.startsWith("http://metadata.google.internal/")) return json(200, { access_token: "meta-token", expires_in: 3000 });
    if (u.includes(":signJwt")) return json(200, { keyId: "k", signedJwt: `h.${b64(JSON.parse(init.body).payload)}.s` });
    if (u === "https://oauth2.googleapis.com/token") return exchange ? exchange(init) : json(200, { access_token: "user-token", expires_in: 3599 });
    throw new Error(`unexpected ${u}`);
  };
  return { f, calls, signs: () => calls.filter((c) => c.url.includes(":signJwt")) };
}

test("signJwtClaimsAreExact", async () => {
  const g = fakeGoogle();
  const t = await lib.delegatedToken(SA, "admin@example.org", { fetch: g.f, now: () => 1_700_000_000_500 });
  assert.equal(t, "user-token");
  const [meta, sign, exchange] = g.calls;
  assert.equal(meta.url, "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token");
  assert.equal(meta.init.headers["Metadata-Flavor"], "Google");
  assert.equal(sign.url, `https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${SA}:signJwt`);
  assert.equal(sign.init.method, "POST");
  assert.equal(sign.init.headers.Authorization, "Bearer meta-token");
  const claims = JSON.parse(JSON.parse(sign.init.body).payload);
  assert.deepEqual(claims, {
    iss: SA, sub: "admin@example.org", scope: "https://www.googleapis.com/auth/gmail.send",
    aud: "https://oauth2.googleapis.com/token", iat: 1_700_000_000, exp: 1_700_003_600,
  });
  assert.equal(claims.exp - claims.iat, 3600);
  assert.equal(exchange.init.body, `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${encodeURIComponent(`h.${b64(JSON.stringify(claims))}.s`)}`);
});

test("tokenIsCachedUntilNearExpiry", async () => {
  const g = fakeGoogle();
  let now = 5_000_000_000_000;
  const deps = { fetch: g.f, now: () => now };
  await lib.delegatedToken(SA, "cache@example.org", deps);
  now += 10 * 60_000;
  await lib.delegatedToken(SA, "cache@example.org", deps);
  assert.equal(g.signs().length, 1);
  await lib.delegatedToken(SA, "other@example.org", deps);
  assert.equal(g.signs().length, 2, "cache is per subject");
  now += 49 * 60_000 + 30_000; // 59 min 30 s after the first token
  await lib.delegatedToken(SA, "cache@example.org", deps);
  assert.equal(g.signs().length, 3);
});

test("oauth error becomes a delegation failure code", async () => {
  const g = fakeGoogle({ exchange: () => json(400, { error: "unauthorized_client", error_description: "Client is unauthorized" }) });
  await assert.rejects(
    lib.delegatedToken(SA, "err@example.org", { fetch: g.f, now: () => 1 }),
    (e) => e instanceof lib.MailSendError && e.code === "delegation:unauthorized_client",
  );
});

test("gmailSendRaw posts base64url raw and reports status and reason", async () => {
  const seen = [];
  const ok = async (url, init) => { seen.push({ url, init }); return json(200, { id: "1" }); };
  await lib.gmailSendRaw("tok", "admin@example.org", Buffer.from("From: a\r\n\r\nx~?>"), ok);
  assert.equal(seen[0].url, "https://gmail.googleapis.com/gmail/v1/users/admin@example.org/messages/send");
  assert.equal(seen[0].init.headers.Authorization, "Bearer tok");
  assert.equal(JSON.parse(seen[0].init.body).raw, Buffer.from("From: a\r\n\r\nx~?>").toString("base64url"));
  const bad = async () => json(403, { error: { code: 403, message: "secret text", errors: [{ reason: "insufficientPermissions" }] } });
  await assert.rejects(
    lib.gmailSendRaw("tok", "admin@example.org", Buffer.from("x"), bad),
    (e) => e instanceof lib.MailSendError && e.code === "gmail:403:insufficientPermissions" && !e.message.includes("secret"),
  );
});

test("every call carries an abort signal", async () => {
  const g = fakeGoogle();
  await lib.delegatedToken(SA, "signal@example.org", { fetch: g.f, now: () => 9, timeoutMs: 5000 });
  assert.equal(g.calls.length, 3);
  for (const c of g.calls) assert.ok(c.init.signal instanceof AbortSignal);
  const seen = [];
  await lib.gmailSendRaw("t", "a@example.org", Buffer.from("x"), async (u, init) => { seen.push(init); return json(200, {}); });
  assert.ok(seen[0].signal instanceof AbortSignal);
});
