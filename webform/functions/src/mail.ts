// The "send me my QR code" e-mail. The template is what devices publish in
// `orgs/{orgId}/portalMail/default` (PortalMailTemplate, Kotlin); the server fills in the first
// name and attaches the QR image. The QR payload (the wallet id) only ever goes into that PNG.
import * as logger from "firebase-functions/logger";
import type { Firestore } from "firebase-admin/firestore";
import nodemailer from "nodemailer";
import type { Transporter } from "nodemailer";
import QRCode from "qrcode";
import { cleanEmail } from "./validate";

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
}

/** The part of a nodemailer transporter that sendMail uses. */
export type SmtpClient = Pick<Transporter, "sendMail">;

export interface MailTransport {
  db: Firestore;
  user: () => string;
  password: () => string;
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
function logoBytes(logoBase64: string): Buffer | null {
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

/** Characters that could make nodemailer read one address as several, or as a display name. */
const ADDRESS_FORBIDDEN = /[\s,;<>"()[\]\\:]/u;

/** The trimmed address when it is one plain mailbox (and a valid portal e-mail), else null. */
export function safeRecipient(raw: string): string | null {
  const address = raw.trim();
  if (ADDRESS_FORBIDDEN.test(address) || cleanEmail(address) === null) return null;
  return address;
}

const isCapture = (): boolean =>
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

/**
 * Sends through the association's Gmail account (SMTP with an app password). True once Gmail
 * accepted the message. Only error codes are logged: SMTP messages can quote addresses.
 */
export async function sendMail(msg: OutgoingMail, transport: MailTransport): Promise<boolean> {
  try {
    const to = safeRecipient(msg.to);
    const user = transport.user().trim();
    if (to === null || safeRecipient(user) === null) {
      logger.error("mail not sent", { reason: "BAD_ADDRESS" });
      return false;
    }
    const fromName = msg.fromName.replace(CONTROL, " ").trim();
    if (isCapture()) {
      await transport.db.collection("_testMail").add({
        to,
        from: { name: fromName, address: user },
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
      return true;
    }
    // Gmail shows app passwords in groups of four; the spaces are not part of it.
    const password = transport.password().replace(/\s+/g, "");
    if (password === "") {
      logger.error("mail not sent", { reason: "NO_PASSWORD" });
      return false;
    }
    const smtp = transport.smtp ?? gmail;
    await smtp(user, password).sendMail({
      from: fromName === "" ? user : { name: fromName, address: user },
      to: { name: "", address: to },
      // Pinned envelope: the message goes to this one address whatever the headers say.
      envelope: { from: user, to: [to] },
      subject: msg.subject,
      html: msg.html,
      text: msg.text,
      attachments: msg.attachments,
    });
    return true;
  } catch (e) {
    const err = e as { code?: unknown; responseCode?: unknown };
    logger.error("mail not sent", {
      reason: "SEND_FAILED",
      code: err?.code === undefined ? "" : String(err.code),
      responseCode: typeof err?.responseCode === "number" ? err.responseCode : 0,
    });
    return false;
  }
}
