// The "send me my QR code" e-mail. The template is what devices publish in
// `orgs/{orgId}/portalMail/default` (PortalMailTemplate, Kotlin); the server fills in the first
// name and attaches the QR image. The QR payload (the wallet id) only ever goes into that PNG.
import * as logger from "firebase-functions/logger";
import type { Firestore } from "firebase-admin/firestore";
import nodemailer from "nodemailer";
import type { Transporter } from "nodemailer";
import QRCode from "qrcode";
import MailComposer from "nodemailer/lib/mail-composer";
import { cleanEmail } from "./validate";
import { delegatedToken, gmailSendRaw, MailSendError } from "./workspace";

/** PORTAL_FIRST_NAME_TOKEN (Kotlin): replaced by the volunteer's first name. */
export const PORTAL_FIRST_NAME_TOKEN = "%%NOCTULIST_FIRST_NAME%%";
export const MAIL_TEMPLATE_SCHEMA = 1;
const QR_WIDTH = 600;
const QR_FILE = "qr_code.png";
/** Larger logos are left out rather than mailed. */
const MAX_LOGO_BYTES = 512 * 1024;
const MAX_SUBJECT = 200;

export interface MailTemplate {
  subject: string;
  html: string;
  text: string;
  logoBase64: string;
}

export interface MailAttachment {
  filename: string;
  content: Buffer;
  contentType: string;
  contentDisposition: "inline" | "attachment";
  cid?: string;
}

export interface QrMail {
  subject: string;
  html: string;
  text: string;
  attachments: MailAttachment[];
}

export interface OutgoingMail extends QrMail {
  /** One plain address, already checked with safeRecipient. */
  to: string;
  /** Display name of the sender, "" for the bare address. */
  fromName: string;
  /**
   * Address shown in From, when it passes safeRecipient; else the Gmail account's. Gmail only keeps
   * it for a "send as" alias of the account. The envelope always stays on the account.
   */
  fromAddress?: string;
  /** Reply-To, only when it passes safeRecipient. */
  replyTo?: string;
}

/** The part of a nodemailer transporter that sendMail uses. */
export type SmtpClient = Pick<Transporter, "sendMail">;

/** What a send answers: `failure` is a short non-secret code, "" once sent. */
export interface SendResult {
  sent: boolean;
  failure: string;
}

export type MailMode = "app_password" | "workspace";

export interface MailTransport {
  db: Firestore;
  mode: () => MailMode;
  /** Envelope and sending user (the Gmail account, or the Workspace user that is impersonated). */
  user: () => string;
  /** Workspace mode only: the From address, blank for the user. */
  from: () => string;
  /** Workspace mode only: the delegated `noctulist-mail@<project>.iam.gserviceaccount.com`. */
  sa: () => string;
  /** Never read in workspace mode. */
  password: () => string;
  /** Workspace calls; the real ones when absent (tests pass fakes). */
  workspace?: { token: typeof delegatedToken; send: typeof gmailSendRaw };
  /** Workspace calls only (tests): the fetch to use and the per-call timeout. */
  fetch?: typeof fetch;
  timeoutMs?: number;
  /** SMTP client for these credentials; the Gmail one when absent (tests pass a fake). */
  smtp?: (user: string, password: string) => SmtpClient;
}

/**
 * Gmail SMTP with bounded waits: a stalled connection fails within seconds, well inside the
 * function timeout, so the request is answered SEND_FAILED instead of staying in PROCESSING.
 */
export function smtpOptions(user: string, password: string) {
  return {
    service: "gmail",
    auth: { user, pass: password },
    connectionTimeout: 10_000,
    greetingTimeout: 10_000,
    socketTimeout: 20_000,
  };
}

/** Neutral English mail used until a device has published `portalMail/default`. */
function fallbackTemplate(withName: boolean): MailTemplate {
  const greeting = withName ? `Hello ${PORTAL_FIRST_NAME_TOKEN},` : "Hello,";
  return {
    subject: "Your volunteer QR code",
    html:
      "<!DOCTYPE html><html><body style=\"font-family: Arial, sans-serif; color: #222;\">" +
      `<p>${greeting}</p>` +
      "<p>Here is your volunteer QR code.</p>" +
      "<p><img src=\"cid:qrcode\" alt=\"QR code\" width=\"300\" height=\"300\"></p>" +
      "<p>It is also attached to this e-mail.</p>" +
      "</body></html>",
    text: `${greeting}\n\nHere is your volunteer QR code. It is attached to this e-mail.\n`,
    logoBase64: "",
  };
}

const CONTROL = /[\u0000-\u001F\u007F]+/g;
/** The `<img>` element that shows the `cid:logo` part, over one or several lines. */
const LOGO_IMG = /<img\b[^>]*\bsrc\s*=\s*["']?cid:logo["']?[^>]*>/gi;

export const escapeHtml = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

/** Plain replacement: no `$` patterns, every occurrence. */
const fill = (template: string, value: string): string => template.split(PORTAL_FIRST_NAME_TOKEN).join(value);

/** The published template, or null when it is missing or not in the expected shape. */
export function parseMailTemplate(data: Record<string, unknown> | undefined): MailTemplate | null {
  if (data === undefined || data.schema !== MAIL_TEMPLATE_SCHEMA) return null;
  const { subject, html, text, logoBase64 } = data;
  if (typeof subject !== "string" || subject.trim() === "") return null;
  if (typeof html !== "string" || html.trim() === "") return null;
  if (typeof text !== "string") return null;
  return { subject, html, text, logoBase64: typeof logoBase64 === "string" ? logoBase64 : "" };
}

/** The logo bytes when `logoBase64` holds a PNG of reasonable size, else null. */
export function logoBytes(logoBase64: string): Buffer | null {
  const raw = logoBase64.trim();
  if (raw === "") return null;
  const bytes = Buffer.from(raw, "base64");
  const isPng = bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  return isPng && bytes.length <= MAX_LOGO_BYTES ? bytes : null;
}

export async function buildQrMail(template: MailTemplate | null, firstName: string, qrPayload: string): Promise<QrMail> {
  const name = firstName.replace(CONTROL, " ").trim();
  const t = template ?? fallbackTemplate(name !== "");
  const qr = await QRCode.toBuffer(qrPayload, { type: "png", width: QR_WIDTH, errorCorrectionLevel: "M" });
  // Same parts as MimeEmailBuilder.qrInlineAndAttachment: shown inline and downloadable.
  const attachments: MailAttachment[] = [
    { filename: QR_FILE, content: qr, contentType: "image/png", contentDisposition: "inline", cid: "qrcode" },
    { filename: QR_FILE, content: qr, contentType: "image/png", contentDisposition: "attachment" },
  ];
  const logo = logoBytes(t.logoBase64);
  if (logo !== null) {
    attachments.push({ filename: "logo.png", content: logo, contentType: "image/png", contentDisposition: "inline", cid: "logo" });
  }
  // Without the logo part, its image would show as a broken picture.
  const html = logo === null ? t.html.replace(LOGO_IMG, "") : t.html;
  return {
    subject: fill(t.subject, name).replace(CONTROL, " ").trim().slice(0, MAX_SUBJECT),
    // QrEmailHtmlBuilder puts the holder name in raw: escape it here.
    html: fill(html, escapeHtml(name)),
    text: fill(t.text, name),
    attachments,
  };
}

/** Lowercase ASCII file name part: accents stripped, anything else a single hyphen. */
const slug = (s: string): string =>
  s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");

const CERTIFICATE_FILE_PREFIX: Record<string, string> = {
  fr: "attestation-benevolat",
  en: "volunteer-certificate",
  de: "freiwilligen-bestaetigung",
  it: "attestato-volontariato",
  es: "certificado-voluntariado",
};

/** `attestation-benevolat-{first}-{last}.pdf` in the certificate language, ASCII only. */
export function certificateFileName(firstName: string, lastName: string, language: string): string {
  const prefix = CERTIFICATE_FILE_PREFIX[language] ?? CERTIFICATE_FILE_PREFIX.fr;
  const parts = [prefix, slug(firstName), slug(lastName)].filter((p) => p !== "");
  return `${parts.join("-")}.pdf`;
}

export interface CertificateMailArgs {
  to: string;
  fromName: string;
  fromAddress: string;
  replyTo: string;
  subject: string;
  /** Plain text. */
  body: string;
  pdf: Uint8Array;
  fileName: string;
  logoPng: Uint8Array | null;
}

/** The certificate e-mail: plain text body (also as html, with the logo on top) and the PDF attached. */
export function buildCertificateMail(args: CertificateMailArgs): OutgoingMail {
  const text = args.body.replace(/\r\n?/g, "\n");
  const paragraphs = text
    .split(/\n{2,}/)
    .map((p) => `<p>${escapeHtml(p).replace(/\n/g, "<br>")}</p>`)
    .join("");
  const hasLogo = args.logoPng !== null && args.logoPng.length > 0;
  const html =
    "<!DOCTYPE html><html><body style=\"font-family: Arial, sans-serif; color: #222;\">" +
    (hasLogo ? "<p><img src=\"cid:logo\" alt=\"\" style=\"max-width: 200px; max-height: 60px;\"></p>" : "") +
    `${paragraphs}</body></html>`;
  const attachments: MailAttachment[] = [
    { filename: args.fileName, content: Buffer.from(args.pdf), contentType: "application/pdf", contentDisposition: "attachment" },
  ];
  if (hasLogo) {
    attachments.push({ filename: "logo.png", content: Buffer.from(args.logoPng!), contentType: "image/png", contentDisposition: "inline", cid: "logo" });
  }
  const replyTo = safeRecipient(args.replyTo);
  return {
    to: args.to,
    fromName: args.fromName,
    fromAddress: args.fromAddress,
    ...(replyTo === null ? {} : { replyTo }),
    subject: args.subject.replace(CONTROL, " ").trim().slice(0, MAX_SUBJECT),
    html,
    text,
    attachments,
  };
}

/** Characters that could make nodemailer read one address as several, or as a display name. */
const ADDRESS_FORBIDDEN = /[\s,;<>"()[\]\\:]/u;

/** The trimmed address when it is one plain mailbox (and a valid portal e-mail), else null. */
export function safeRecipient(raw: string): string | null {
  const address = raw.trim();
  if (ADDRESS_FORBIDDEN.test(address) || cleanEmail(address) === null) return null;
  return address;
}

export const isCapture = (): boolean =>
  // Capture is test plumbing: it is ignored outside the emulator, so a stray setting never stores mail.
  process.env.FUNCTIONS_EMULATOR === "true" && process.env.PORTAL_MAIL_TRANSPORT === "capture";

let cached: { key: string; transporter: Transporter } | null = null;

function gmail(user: string, password: string): Transporter {
  const key = `${user}\n${password}`;
  if (cached === null || cached.key !== key) {
    cached = { key, transporter: nodemailer.createTransport(smtpOptions(user, password)) };
  }
  return cached.transporter;
}

export interface RawOutgoingMail {
  /** One plain address, already checked with safeRecipient. */
  to: string;
  /** Display name of the sender, "" for the bare address (only shown in the test capture). */
  fromName: string;
  /** The finished message for this sender address and send time. */
  build: (from: string, now: Date) => string;
}

const SENT: SendResult = { sent: true, failure: "" };
const failed = (failure: string): SendResult => ({ sent: false, failure });

/** The address in the From header: MAIL_FROM in workspace mode when set, else the sending user. */
function fromAddress(transport: MailTransport, user: string): string {
  if (transport.mode() !== "workspace") return user;
  const from = transport.from().trim();
  return from === "" ? user : from;
}

/** Short code of a failed send. Only codes are used: SMTP and API messages can quote addresses. */
function failureOf(e: unknown): string {
  if (e instanceof MailSendError) return e.code;
  const err = e as { code?: unknown; responseCode?: unknown };
  if (typeof err?.responseCode === "number") return `smtp:${err.responseCode}`;
  const c = err?.code === undefined ? "" : String(err.code).replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 40);
  return `smtp:${c === "" ? "0" : c}`;
}

function logFailure(e: unknown): void {
  const err = e as { code?: unknown; responseCode?: unknown };
  logger.error("mail not sent", {
    reason: "SEND_FAILED",
    failure: failureOf(e),
    code: err?.code === undefined ? "" : String(err.code),
    responseCode: typeof err?.responseCode === "number" ? err.responseCode : 0,
  });
}

/** Sends [raw] as [user] through the Gmail API with a delegated token. */
async function sendWorkspace(transport: MailTransport, user: string, raw: Buffer): Promise<void> {
  const sa = transport.sa().trim();
  if (sa === "") throw new MailSendError("config:no_service_account");
  const ws = transport.workspace ?? { token: delegatedToken, send: gmailSendRaw };
  const f = transport.fetch ?? fetch;
  // A transport failure (fetch error, timeout) is not a MailSendError: name the phase it hit.
  const phase = (name: string) => (e: unknown): never => {
    throw e instanceof MailSendError ? e : new MailSendError(`${name}:network`);
  };
  const token = await ws.token(sa, user, { fetch: f, now: Date.now, timeoutMs: transport.timeoutMs }).catch(phase("delegation"));
  await ws.send(token, user, raw, f, transport.timeoutMs).catch(phase("gmail"));
}

/**
 * A message the app built itself (mailOutbox), sent as is through the association's Gmail with
 * the envelope pinned to [msg.to]. Same rules and logging as sendMail.
 */
export async function sendRawMail(msg: RawOutgoingMail, transport: MailTransport): Promise<SendResult> {
  try {
    const to = safeRecipient(msg.to);
    const user = transport.user().trim();
    const from = fromAddress(transport, user);
    if (to === null || safeRecipient(user) === null || safeRecipient(from) === null) {
      logger.error("mail not sent", { reason: "BAD_ADDRESS" });
      return failed("bad_address");
    }
    const raw = msg.build(from, new Date());
    if (isCapture()) {
      await transport.db.collection("_testMail").add({
        to,
        from: { name: msg.fromName.replace(CONTROL, " ").trim(), address: from },
        raw,
        size: raw.length,
        capturedAt: Date.now(),
      });
      return SENT;
    }
    if (transport.mode() === "workspace") {
      await sendWorkspace(transport, user, Buffer.from(raw, "utf8"));
      return SENT;
    }
    const password = transport.password().replace(/\s+/g, "");
    if (password === "") {
      logger.error("mail not sent", { reason: "NO_PASSWORD" });
      return failed("no_password");
    }
    const smtp = transport.smtp ?? gmail;
    await smtp(user, password).sendMail({ envelope: { from: user, to: [to] }, raw });
    return SENT;
  } catch (e) {
    logFailure(e);
    return failed(failureOf(e));
  }
}

/**
 * Sends through the association's Gmail account (SMTP with an app password, or the Gmail API
 * with Workspace delegation). Sent once Gmail accepted the message. Only error codes are logged.
 */
export async function sendMail(msg: OutgoingMail, transport: MailTransport): Promise<SendResult> {
  try {
    const to = safeRecipient(msg.to);
    const user = transport.user().trim();
    const from = fromAddress(transport, user);
    if (to === null || safeRecipient(user) === null || safeRecipient(from) === null) {
      logger.error("mail not sent", { reason: "BAD_ADDRESS" });
      return failed("bad_address");
    }
    const fromName = msg.fromName.replace(CONTROL, " ").trim();
    // The header address is the message's own when it is a plain mailbox; the envelope stays on [user].
    const header = msg.fromAddress === undefined ? null : safeRecipient(msg.fromAddress);
    const shown = header ?? from;
    const replyTo = msg.replyTo === undefined ? null : safeRecipient(msg.replyTo);
    if (isCapture()) {
      await transport.db.collection("_testMail").add({
        to,
        from: { name: fromName, address: shown },
        ...(replyTo === null ? {} : { replyTo }),
        subject: msg.subject,
        html: msg.html,
        text: msg.text,
        attachments: msg.attachments.map((a) => ({
          filename: a.filename,
          contentType: a.contentType,
          disposition: a.contentDisposition,
          cid: a.cid ?? "",
          size: a.content.length,
        })),
        capturedAt: Date.now(),
      });
      return SENT;
    }
    const message = {
      from: fromName === "" ? shown : { name: fromName, address: shown },
      to: { name: "", address: to },
      ...(replyTo === null ? {} : { replyTo }),
      subject: msg.subject,
      html: msg.html,
      text: msg.text,
      attachments: msg.attachments,
    };
    if (transport.mode() === "workspace") {
      await sendWorkspace(transport, user, await new MailComposer(message).compile().build());
      return SENT;
    }
    // Gmail shows app passwords in groups of four; the spaces are not part of it.
    const password = transport.password().replace(/\s+/g, "");
    if (password === "") {
      logger.error("mail not sent", { reason: "NO_PASSWORD" });
      return failed("no_password");
    }
    const smtp = transport.smtp ?? gmail;
    // Pinned envelope: the message goes to this one address whatever the headers say.
    await smtp(user, password).sendMail({ ...message, envelope: { from: user, to: [to] } });
    return SENT;
  } catch (e) {
    logFailure(e);
    return failed(failureOf(e));
  }
}
