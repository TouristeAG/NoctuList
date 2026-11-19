// QR e-mails the app hands to the site when the device has no Gmail account of its own:
// `orgs/{orgId}/mailOutbox/{id}` holds the finished MIME message built by the app (same one it
// would give the Gmail API). The server only sets the sender and the recipient, sends it with the
// association's Gmail and drops the message from the document once answered. Mirrors SiteMailRelay
// (Kotlin), which writes the document and waits for the answer.
import * as logger from "firebase-functions/logger";
import { randomUUID } from "node:crypto";
import { FieldValue, type Firestore } from "firebase-admin/firestore";
import { claim, touchStatus } from "./common";
import { safeRecipient, sendRawMail, type MailTransport } from "./mail";

type Data = Record<string, unknown>;

export const OUTBOX_SCHEMA = 1;
/** Same bound as the rules: well under the 1 MiB Firestore document limit. */
export const MAX_RAW_LENGTH = 900_000;
const MAX_LABEL = 80;

export type OutboxReason = "" | "INVALID" | "BAD_ADDRESS" | "SEND_FAILED";

/** The answer written to the document: `failure` is a short non-secret code of a failed send. */
interface OutboxAnswer {
  reason: OutboxReason;
  failure: string;
}

export interface OutboxMail {
  to: string;
  raw: string;
  fromName: string;
}

/** The fields the rules let a team device write, or null when anything is off. */
export function parseOutbox(data: Data | undefined): OutboxMail | null {
  if (data === undefined || data.schema !== OUTBOX_SCHEMA) return null;
  const { to, raw, fromName } = data;
  if (typeof to !== "string" || typeof raw !== "string") return null;
  if (raw.length === 0 || raw.length > MAX_RAW_LENGTH) return null;
  // The header block must end before the body, or there is no message to send.
  if (headerEnd(raw) < 0) return null;
  return { to, raw, fromName: typeof fromName === "string" ? fromName.slice(0, MAX_LABEL) : "" };
}

function headerEnd(raw: string): number {
  const crlf = raw.indexOf("\r\n\r\n");
  if (crlf >= 0) return crlf;
  return raw.indexOf("\n\n");
}

/** Headers the server always writes itself: who sends, who receives, and nobody else. */
const REPLACED = new Set(["from", "sender", "to", "cc", "bcc", "date", "message-id", "return-path"]);
const CONTROL = /[\u0000-\u001F\u007F]+/g;

/** RFC 2047 encoded-word when the name is not plain ASCII, else a quoted string. */
function displayName(name: string): string {
  const clean = name.replace(CONTROL, " ").trim();
  if (clean === "") return "";
  if (/^[\x20-\x7E]*$/.test(clean)) return `"${clean.replace(/["\\]/g, "\\$&")}"`;
  return `=?UTF-8?B?${Buffer.from(clean, "utf8").toString("base64")}?=`;
}

/**
 * The app's message with its own From, To, Cc, Bcc, Date and Message-ID dropped (folded lines
 * included) and the server's put first. The body is left byte for byte.
 */
export function rewriteHeaders(raw: string, from: string, fromName: string, to: string, now: Date): string {
  const end = headerEnd(raw);
  const eol = raw.startsWith("\r\n", end) ? "\r\n" : "\n";
  const lines = raw.slice(0, end).split(/\r?\n/);
  const kept: string[] = [];
  let dropping = false;
  for (const line of lines) {
    if (/^[ \t]/.test(line)) {
      if (!dropping) kept.push(line);
      continue;
    }
    const name = line.slice(0, Math.max(0, line.indexOf(":"))).trim().toLowerCase();
    dropping = REPLACED.has(name);
    if (!dropping) kept.push(line);
  }
  const shown = displayName(fromName);
  const domain = from.slice(from.lastIndexOf("@") + 1);
  const head = [
    `From: ${shown === "" ? from : `${shown} <${from}>`}`,
    `To: ${to}`,
    `Date: ${now.toUTCString().replace("GMT", "+0000")}`,
    `Message-ID: <${randomUUID()}@${domain}>`,
    ...kept,
  ];
  return head.join("\r\n") + "\r\n" + raw.slice(end + eol.length);
}

const isClaimable = (d: Data): boolean => d.status === "PENDING";

const errorCode = (e: unknown): string => {
  const code = (e as { code?: unknown })?.code;
  return code === undefined ? "" : String(code);
};

/**
 * Handles one write of `orgs/{orgId}/mailOutbox/{id}`. Only PENDING documents are claimed; the
 * trigger also fires on our own claim and answer and on the device deleting the document.
 */
export async function onOutboxDocument(
  db: Firestore,
  mail: MailTransport,
  orgId: string,
  id: string,
  current: Data | undefined,
): Promise<void> {
  if (current === undefined || !isClaimable(current)) return;
  const ref = db.doc(`orgs/${orgId}/mailOutbox/${id}`);
  let answer: OutboxAnswer;
  try {
    const claimed = await claim(ref, isClaimable);
    if (claimed === null) return;
    answer = await sendClaimed(mail, claimed);
    const { reason, failure } = answer;
    // The message (QR code, address) is not kept once answered.
    await ref.set(
      { status: reason === "" ? "DONE" : "REJECTED", reason, failure, processedAt: Date.now(), raw: FieldValue.delete() },
      { merge: true },
    );
  } catch (e) {
    logger.error("outbox mail failed", { orgId, id, code: errorCode(e) });
    return;
  }
  try {
    await touchStatus(db, orgId);
  } catch (e) {
    logger.error("server status not written", { orgId, id, code: errorCode(e) });
  }
  logger.info("outbox mail processed", { orgId, id, reason: answer.reason, failure: answer.failure });
}

async function sendClaimed(mail: MailTransport, claimed: Data): Promise<OutboxAnswer> {
  const parsed = parseOutbox(claimed);
  if (parsed === null) return { reason: "INVALID", failure: "" };
  const to = safeRecipient(parsed.to);
  if (to === null) return { reason: "BAD_ADDRESS", failure: "" };
  const result = await sendRawMail(
    { to, fromName: parsed.fromName, build: (from, now) => rewriteHeaders(parsed.raw, from, parsed.fromName, to, now) },
    mail,
  );
  return result.sent ? { reason: "", failure: "" } : { reason: "SEND_FAILED", failure: result.failure };
}
