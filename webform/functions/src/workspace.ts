// Keyless Gmail sending for Google Workspace. The function's runtime account asks IAM Credentials
// to sign a JWT for a dedicated service account that has domain-wide delegation, trades it for an
// access token of the sending user and calls the Gmail API. No password or key is stored anywhere.

/** A send failure with a short code that is safe to store and show: never a secret or message text. */
export class MailSendError extends Error {
  readonly code: string;
  constructor(code: string) {
    super(code);
    this.name = "MailSendError";
    this.code = code;
  }
}

export interface DelegationDeps {
  fetch: typeof fetch;
  /** Milliseconds since the epoch. */
  now: () => number;
}

const METADATA_TOKEN = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
const LIFETIME_SECONDS = 3600;
const REFRESH_MARGIN_MS = 60_000;
const MAX_CODE = 80;

/** Letters, digits and a few separators only, so a code can never carry free text. */
const safePart = (s: unknown, fallback: string): string => {
  const clean = typeof s === "string" ? s.replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 40) : "";
  return clean === "" ? fallback : clean;
};
const code = (c: string): string => c.slice(0, MAX_CODE);

async function jsonOf(r: Response): Promise<Record<string, unknown>> {
  try {
    const body: unknown = await r.json();
    return typeof body === "object" && body !== null ? (body as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

const cache = new Map<string, { token: string; expiresAt: number }>();

/** Access token (gmail.send) for [subject], acting through [sa]; cached until 60 s before expiry. */
export async function delegatedToken(sa: string, subject: string, deps: DelegationDeps): Promise<string> {
  const key = `${sa}|${subject}`;
  const hit = cache.get(key);
  if (hit !== undefined && deps.now() < hit.expiresAt - REFRESH_MARGIN_MS) return hit.token;

  const f = deps.fetch;
  const meta = await f(METADATA_TOKEN, { headers: { "Metadata-Flavor": "Google" } });
  const metaBody = await jsonOf(meta);
  if (!meta.ok || typeof metaBody.access_token !== "string") throw new MailSendError(code(`delegation:metadata:${meta.status}`));

  const iat = Math.floor(deps.now() / 1000);
  const claims = { iss: sa, sub: subject, scope: SEND_SCOPE, aud: TOKEN_URL, iat, exp: iat + LIFETIME_SECONDS };
  const signed = await f(`https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${encodeURIComponent(sa).replace(/%40/g, "@")}:signJwt`, {
    method: "POST",
    headers: { Authorization: `Bearer ${metaBody.access_token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ payload: JSON.stringify(claims) }),
  });
  const signedBody = await jsonOf(signed);
  if (!signed.ok || typeof signedBody.signedJwt !== "string") throw new MailSendError(code(`delegation:sign:${signed.status}`));

  const exchanged = await f(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${encodeURIComponent(signedBody.signedJwt)}`,
  });
  const body = await jsonOf(exchanged);
  if (!exchanged.ok || typeof body.access_token !== "string") {
    throw new MailSendError(code(`delegation:${safePart(body.error, `http_${exchanged.status}`)}`));
  }
  const lifetime = typeof body.expires_in === "number" && body.expires_in > 0 ? body.expires_in : LIFETIME_SECONDS;
  cache.set(key, { token: body.access_token, expiresAt: deps.now() + lifetime * 1000 });
  return body.access_token;
}

/** Sends the finished MIME message as [user] through the Gmail API. */
export async function gmailSendRaw(token: string, user: string, raw: Buffer, f: typeof fetch): Promise<void> {
  const r = await f(`https://gmail.googleapis.com/gmail/v1/users/${encodeURIComponent(user).replace(/%40/g, "@")}/messages/send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ raw: raw.toString("base64url") }),
  });
  if (r.ok) return;
  const body = await jsonOf(r);
  const error = (body.error ?? {}) as { errors?: Array<{ reason?: unknown }>; status?: unknown };
  const reason = safePart(error.errors?.[0]?.reason ?? error.status, "unknown");
  throw new MailSendError(code(`gmail:${r.status}:${reason}`));
}
