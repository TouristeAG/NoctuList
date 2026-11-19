"use strict";
// NoctuList Google helper. Reuses the Firebase CLI sign-in (firebase-tools lib dir passed as argv[0]).
// Prints exactly one JSON line. Never prints the access token.
const path = require("node:path");

const RE = {
  project: /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/,
  member: /^(serviceAccount|user|group):[^\s;]+$/,
  role: /^roles\/[A-Za-z.]+$/,
  api: /^[a-z0-9.-]+\.googleapis\.com$/,
  billing: /^billingAccounts\/[A-Z0-9-]+$/,
};
const MAIL_ACCOUNT_ID = "noctulist-mail";
const POLL_MS = 2000;
const POLL_MAX_MS = 5 * 60 * 1000;

class GoogleError extends Error {
  constructor(status, reason, message, service) {
    super(message);
    this.info = { status, reason, message, service: service || "" };
  }
}
const invalid = (m) => new GoogleError(0, "INVALID_ARGUMENT", m, "");

function fromResponse(res) {
  const e = res.body && res.body.error;
  if (!e) return new GoogleError(res.status, "HTTP_" + res.status, "Request failed with status " + res.status, "");
  const d = (e.details || []).find((x) => x && x.reason) || (e.details || [])[0] || {};
  return new GoogleError(
    typeof e.code === "number" ? e.code : res.status,
    d.reason || e.status || "UNKNOWN",
    String(e.message || ""),
    (d.metadata && d.metadata.service) || "",
  );
}

function parseArgs(rest) {
  const o = {};
  for (let i = 0; i < rest.length; i++) {
    if (rest[i].startsWith("--")) o[rest[i].slice(2)] = rest[i + 1] !== undefined ? rest[++i] : "";
  }
  return o;
}

function need(v, re, name) {
  if (typeof v !== "string" || !re.test(v)) throw invalid("Invalid " + name);
  return v;
}
function list(v, re, name) {
  if (!v) throw invalid("Missing " + name);
  const items = v.split(",").filter(Boolean);
  if (items.length === 0) throw invalid("Missing " + name);
  items.forEach((x) => need(x, re, name));
  return items;
}

async function run(argv, deps) {
  try {
    return JSON.stringify({ ok: true, data: await dispatch(argv, deps) });
  } catch (e) {
    const info = e instanceof GoogleError ? e.info : { status: 0, reason: "INTERNAL", message: scrub(String(e && e.message || e)), service: "" };
    return JSON.stringify({ ok: false, error: info });
  }
}
function scrub(s) { return s.replace(/ya29\.[\w.-]+/g, "[redacted]").replace(/Bearer\s+\S+/gi, "Bearer [redacted]"); }

async function dispatch(argv, deps) {
  const command = argv[1];
  const a = parseArgs(argv.slice(2));
  const commands = {
    "check-auth": null, "project-number": 1, "test-permissions": 1, "enable-apis": 1, "ensure-role": 1,
    "billing-status": 1, "link-billing": 1, "functions-status": 1, "storage-status": 1,
    "rules-status": 1, "hosting-status": 1, "ensure-mail-sa": 1,
  };
  if (!Object.hasOwn(commands, command)) throw new GoogleError(0, "UNKNOWN_COMMAND", "Unknown command", "");
  if (command === "check-auth") {
    await deps.token();
    return { email: deps.email ? await deps.email() : "" };
  }

  const id = need(a.project, RE.project, "project");
  const call = async (method, url, body, withProject = true) => {
    const headers = { authorization: "Bearer " + (await deps.token()), "content-type": "application/json" };
    if (withProject) headers["x-goog-user-project"] = id;
    return deps.request(method, url, body, headers);
  };
  const okCall = async (...args) => {
    const r = await call(...args);
    if (r.status < 200 || r.status >= 300) throw fromResponse(r);
    return r.body || {};
  };
  const P = `projects/${id}`;

  async function enableApis(apis) {
    const op = await okCall("POST", `https://serviceusage.googleapis.com/v1/${P}/services:batchEnable`, { serviceIds: apis }, false);
    if (!op.done && !op.name) throw new GoogleError(0, "BAD_RESPONSE", "Service Usage answered without an operation", "serviceusage.googleapis.com");
    let cur = op;
    let waited = 0;
    while (!cur.done) {
      if (waited >= POLL_MAX_MS) throw new GoogleError(0, "TIMEOUT", "Timed out enabling APIs", "");
      await deps.sleep(POLL_MS);
      waited += POLL_MS;
      cur = await okCall("GET", `https://serviceusage.googleapis.com/v1/${cur.name || op.name}`, undefined, false);
    }
    if (cur.error) {
      throw new GoogleError(cur.error.code || 0, cur.error.status || "OPERATION_FAILED", String(cur.error.message || ""), "serviceusage.googleapis.com");
    }
    return apis;
  }

  async function projectNumber() {
    const r = await okCall("GET", `https://cloudresourcemanager.googleapis.com/v1/${P}`);
    return String(r.projectNumber);
  }

  // getIamPolicy + merge + setIamPolicy, one retry on 409/ABORTED. base = resource URL.
  async function ensureBinding(base, role, member) {
    for (let attempt = 0; ; attempt++) {
      const policy = await okCall("POST", `${base}:getIamPolicy`, { options: { requestedPolicyVersion: 3 } });
      const bindings = policy.bindings || [];
      let b = bindings.find((x) => x.role === role && !x.condition);
      if (b && (b.members || []).includes(member)) return false;
      if (b) b.members = [...(b.members || []), member];
      else bindings.push({ role, members: [member] });
      policy.bindings = bindings;
      policy.version = Math.max(3, policy.version || 0);
      const r = await call("POST", `${base}:setIamPolicy`, { policy });
      if (r.status >= 200 && r.status < 300) return true;
      const aborted = r.status === 409 || (r.body && r.body.error && r.body.error.status === "ABORTED");
      if (aborted && attempt === 0) continue;
      throw fromResponse(r);
    }
  }

  switch (command) {
    case "project-number":
      return { number: await projectNumber() };
    case "test-permissions": {
      const perms = list(a.permissions, /^[A-Za-z0-9._]+$/, "permissions");
      const r = await okCall("POST", `https://cloudresourcemanager.googleapis.com/v1/${P}:testIamPermissions`, { permissions: perms });
      const granted = r.permissions || [];
      return { granted, missing: perms.filter((p) => !granted.includes(p)) };
    }
    case "enable-apis":
      return { enabled: await enableApis(list(a.apis, RE.api, "apis")) };
    case "ensure-role": {
      const member = need(a.member, RE.member, "member");
      const role = need(a.role, RE.role, "role");
      return { changed: await ensureBinding(`https://cloudresourcemanager.googleapis.com/v1/${P}`, role, member) };
    }
    case "billing-status": {
      const info = await okCall("GET", `https://cloudbilling.googleapis.com/v1/${P}/billingInfo`);
      const accounts = [];
      let token = "";
      do {
        const r = await okCall("GET", "https://cloudbilling.googleapis.com/v1/billingAccounts" + (token ? "?pageToken=" + encodeURIComponent(token) : ""));
        for (const x of r.billingAccounts || []) if (x.open === true) accounts.push({ name: x.name, displayName: x.displayName || "" });
        token = r.nextPageToken || "";
      } while (token);
      return { enabled: info.billingEnabled === true, accounts };
    }
    case "link-billing": {
      const acct = need(a["billing-account"], RE.billing, "billing account");
      const r = await okCall("PUT", `https://cloudbilling.googleapis.com/v1/${P}/billingInfo`, { billingAccountName: acct });
      return { enabled: r.billingEnabled === undefined ? true : r.billingEnabled === true };
    }
    case "functions-status": {
      const codebase = a.codebase || "";
      const out = [];
      let token = "";
      do {
        const r = await okCall("GET", `https://cloudfunctions.googleapis.com/v2/${P}/locations/-/functions` + (token ? "?pageToken=" + encodeURIComponent(token) : ""));
        for (const f of r.functions || []) {
          if ((f.labels || {})["firebase-functions-codebase"] !== codebase) continue;
          const parts = String(f.name).split("/");
          const li = parts.indexOf("locations");
          out.push({
            name: parts[parts.length - 1],
            region: li >= 0 ? parts[li + 1] : "",
            state: f.state || "",
            messages: (f.stateMessages || []).map((m) => m.message).filter(Boolean),
          });
        }
        token = r.nextPageToken || "";
      } while (token);
      return { functions: out };
    }
    case "storage-status": {
      const r = await call("GET", `https://firebasestorage.googleapis.com/v1beta/${P}/buckets`);
      if (r.status === 404) return { bucket: null };
      if (r.status < 200 || r.status >= 300) throw fromResponse(r);
      const b = ((r.body || {}).buckets || [])[0];
      return { bucket: b ? String(b.name).split("/").pop() : null };
    }
    case "rules-status": {
      const r = await okCall("GET", `https://firebaserules.googleapis.com/v1/${P}/releases`);
      const names = (r.releases || []).map((x) => x.name);
      return {
        firestore: names.includes(`${P}/releases/cloud.firestore`),
        storage: names.some((n) => String(n).startsWith(`${P}/releases/firebase.storage/`)),
      };
    }
    case "hosting-status": {
      const r = await okCall("GET", `https://firebasehosting.googleapis.com/v1beta1/${P}/sites`);
      const sites = r.sites || [];
      const site = sites.find((s) => s.type === "DEFAULT_SITE") || sites[0];
      if (!site) return { live: false, url: "" };
      const ch = await call("GET", `https://firebasehosting.googleapis.com/v1beta1/${site.name}/channels/live`);
      if (ch.status !== 404 && (ch.status < 200 || ch.status >= 300)) throw fromResponse(ch);
      return { live: ch.status === 200 && !!(ch.body && ch.body.release), url: site.defaultUrl || "" };
    }
    case "ensure-mail-sa": {
      const number = await projectNumber();
      const email = `${MAIL_ACCOUNT_ID}@${id}.iam.gserviceaccount.com`;
      const saBase = `https://iam.googleapis.com/v1/${P}/serviceAccounts`;
      let sa;
      const got = await call("GET", `${saBase}/${email}`);
      if (got.status === 404) {
        const made = await call("POST", saBase, { accountId: MAIL_ACCOUNT_ID, serviceAccount: { displayName: "NoctuList mail" } });
        if (made.status >= 200 && made.status < 300) sa = made.body || {};
        else if (made.status === 409) sa = await okCall("GET", `${saBase}/${email}`);
        else throw fromResponse(made);
      } else if (got.status >= 200 && got.status < 300) sa = got.body || {};
      else throw fromResponse(got);
      await enableApis(["iamcredentials.googleapis.com"]);
      await ensureBinding(`${saBase}/${email}`, "roles/iam.serviceAccountTokenCreator", `serviceAccount:${number}-compute@developer.gserviceaccount.com`);
      return { email, clientId: sa.oauth2ClientId || "" };
    }
  }
}

const NETWORK_FAILURE = /ENOTFOUND|ECONNRESET|ETIMEDOUT|EAI_AGAIN|ECONNREFUSED|fetch failed|socket hang up/i;
// A lost connection is not a refused sign-in: the admin must not be asked to sign in again for it.
const isNetworkFailure = (e) => NETWORK_FAILURE.test(`${(e && e.code) || ""} ${(e && e.message) || ""} ${(e && e.cause && (e.cause.code || e.cause.message)) || ""}`);
const networkDown = (m) => new GoogleError(0, "NETWORK", scrub(String(m || "Network unavailable")), "");
const notLoggedIn = (m) => new GoogleError(401, "NOT_LOGGED_IN", scrub(String(m || "Not signed in")), "");

// Builds token/email providers from firebase-tools modules (requireAuth, apiv2, auth).
function buildAuth(mods) {
  let cache = null;
  const account = () => {
    const acct = mods.auth.getGlobalDefaultAccount();
    if (!acct) throw notLoggedIn("Not signed in to Firebase");
    return acct;
  };
  return {
    token: async () => {
      if (cache) return cache;
      try {
        const acct = account();
        await mods.requireAuth({ user: acct.user, tokens: acct.tokens });
        const t = await mods.apiv2.getAccessToken();
        cache = typeof t === "string" ? t : t && t.access_token;
        if (!cache) throw new Error("No access token");
        return cache;
      } catch (e) {
        if (e instanceof GoogleError) throw e;
        if (isNetworkFailure(e)) throw networkDown(e && e.message);
        throw notLoggedIn(e && e.message);
      }
    },
    email: async () => {
      const acct = account();
      return (acct.user && acct.user.email) || "";
    },
  };
}

// Sends console output to stderr so stdout carries only the JSON line; returns the real stdout writer.
function guardStdout() {
  const write = process.stdout.write.bind(process.stdout);
  const toErr = (...a) => process.stderr.write(a.map((x) => (typeof x === "string" ? x : require("node:util").inspect(x))).join(" ") + "\n");
  for (const k of ["log", "info", "debug", "warn"]) console[k] = toErr;
  return write;
}

async function main() {
  const writeOut = guardStdout();
  const lib = process.argv[2];
  if (!lib) throw new Error("Missing firebase-tools lib directory");
  const authApi = buildAuth({
    get requireAuth() { return require(path.join(lib, "requireAuth")).requireAuth; },
    get apiv2() { return require(path.join(lib, "apiv2")); },
    get auth() { return require(path.join(lib, "auth")); },
  });
  const deps = {
    ...authApi,
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    request: async (method, url, body, headers) => {
      const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
      const text = await res.text();
      let parsed = {};
      try { parsed = text ? JSON.parse(text) : {}; } catch { parsed = {}; }
      return { status: res.status, body: parsed };
    },
  };
  writeOut((await run(process.argv.slice(2), deps)) + "\n");
}

module.exports = { run, buildAuth, guardStdout };
if (require.main === module) {
  main().catch((e) => {
    process.stderr.write(scrub(String((e && e.message) || e)) + "\n");
    process.exit(1);
  });
}
