// Unit tests of the QR e-mail builder (no emulator needed). Run after `npm run build`.
import { test } from "node:test";
import assert from "node:assert/strict";
import * as lib from "../lib/index.js";

const TOKEN = "%%NOCTULIST_FIRST_NAME%%";
const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
const template = (overrides = {}) => ({
  subject: "Ton QR code",
  html: `<p>Salut ${TOKEN},</p><img src="cid:qrcode"><p>${TOKEN}</p>`,
  text: `Salut ${TOKEN}`,
  logoBase64: "",
  ...overrides,
});

test("first name is escaped in html and raw in text, every token replaced", async () => {
  const mail = await lib.buildQrMail(template(), "Léa & <Co> $& \"x\"", "walletId0123456789abc");
  assert.equal(mail.html, "<p>Salut Léa &amp; &lt;Co&gt; $&amp; &quot;x&quot;,</p><img src=\"cid:qrcode\"><p>Léa &amp; &lt;Co&gt; $&amp; &quot;x&quot;</p>");
  assert.equal(mail.text, "Salut Léa & <Co> $& \"x\"");
  assert.equal(mail.subject, "Ton QR code");
});

test("qr code is a 600 px PNG shown inline and attached, and its payload is nowhere else", async () => {
  const payload = "walletId0123456789abc";
  const mail = await lib.buildQrMail(template(), "Léa", payload);
  const inline = mail.attachments.find((a) => a.cid === "qrcode");
  assert.ok(inline);
  assert.equal(inline.contentType, "image/png");
  assert.equal(inline.contentDisposition, "inline");
  assert.deepEqual([...inline.content.subarray(0, 4)], [0x89, 0x50, 0x4e, 0x47]);
  assert.equal(inline.content.readUInt32BE(16), 600, "width");
  assert.equal(inline.content.readUInt32BE(20), 600, "height");
  const download = mail.attachments.find((a) => a.contentDisposition === "attachment");
  assert.ok(download && download.cid === undefined);
  assert.ok(!`${mail.subject}${mail.html}${mail.text}`.includes(payload));
  assert.ok(!mail.attachments.some((a) => a.filename.includes(payload)));
});

test("logo attached only when logoBase64 is a PNG", async () => {
  const none = await lib.buildQrMail(template(), "Léa", "id");
  assert.equal(none.attachments.some((a) => a.cid === "logo"), false);
  const withLogo = await lib.buildQrMail(template({ logoBase64: PNG }), "Léa", "id");
  const logo = withLogo.attachments.find((a) => a.cid === "logo");
  assert.ok(logo);
  assert.deepEqual(logo.content, Buffer.from(PNG, "base64"));
  const notPng = await lib.buildQrMail(template({ logoBase64: Buffer.from("<svg/>").toString("base64") }), "Léa", "id");
  assert.equal(notPng.attachments.some((a) => a.cid === "logo"), false);
});

test("fallback template in English, with or without a first name", async () => {
  const mail = await lib.buildQrMail(null, "Max <b>", "id");
  assert.equal(mail.subject, "Your volunteer QR code");
  assert.ok(mail.html.includes("Hello Max &lt;b&gt;,"));
  assert.ok(mail.html.includes("cid:qrcode"));
  assert.ok(mail.text.startsWith("Hello Max <b>,"));
  const anonymous = await lib.buildQrMail(null, "  ", "id");
  assert.ok(anonymous.html.includes("<p>Hello,</p>"));
  assert.ok(anonymous.text.startsWith("Hello,"));
  for (const m of [mail, anonymous]) assert.ok(!`${m.subject}${m.html}${m.text}`.includes("\u2014"), "no em dash");
});

test("subject loses control characters", async () => {
  const mail = await lib.buildQrMail(template({ subject: "QR\r\nBcc: x@y.z" }), "Léa", "id");
  assert.equal(mail.subject, "QR Bcc: x@y.z");
});

test("parseMailTemplate accepts only the published shape", () => {
  assert.deepEqual(lib.parseMailTemplate({ ...template(), schema: 1, lastModified: 1 }), template());
  assert.deepEqual(lib.parseMailTemplate({ ...template({ logoBase64: undefined }), schema: 1 }), template());
  assert.equal(lib.parseMailTemplate(undefined), null);
  assert.equal(lib.parseMailTemplate({ ...template(), schema: 2 }), null);
  assert.equal(lib.parseMailTemplate({ ...template({ subject: " " }), schema: 1 }), null);
  assert.equal(lib.parseMailTemplate({ ...template({ html: 3 }), schema: 1 }), null);
});

test("safeRecipient keeps one plain mailbox only", () => {
  assert.equal(lib.safeRecipient("  Lea.Martin@Mail.example "), "Lea.Martin@Mail.example");
  assert.equal(lib.safeRecipient("o'brien@mail.example"), "o'brien@mail.example");
  for (const bad of [
    "", "lea", "a@b.example, c@d.example", "a@b.example;c@d.example", "Lea <a@b.example>",
    "\"a@b\"@c.example", "a b@c.example", "a@b.example\r\nBcc: c@d.example", "a@b@c.example", "a@(x)b.example",
  ]) {
    assert.equal(lib.safeRecipient(bad), null, bad);
  }
});

const outgoing = (overrides = {}) => ({
  subject: "QR", html: "<p>QR</p>", text: "QR", attachments: [],
  to: "lea@mail.example", fromName: "Asso", ...overrides,
});

test("smtp options bound every wait so a stall fails within the function timeout", () => {
  const options = lib.smtpOptions("asso@mail.example", "secret");
  assert.equal(options.service, "gmail");
  assert.deepEqual(options.auth, { user: "asso@mail.example", pass: "secret" });
  assert.equal(options.connectionTimeout, 10_000);
  assert.equal(options.greetingTimeout, 10_000);
  assert.equal(options.socketTimeout, 20_000);
});

test("sendMail answers false when the SMTP transport throws (SEND_FAILED)", async () => {
  const calls = [];
  const transport = {
    db: null,
    user: () => "asso@mail.example",
    password: () => "abcd efgh ijkl mnop",
    smtp: (user, password) => ({
      sendMail: async (message) => {
        calls.push({ user, password, message });
        throw Object.assign(new Error("Greeting never received"), { code: "ETIMEDOUT" });
      },
    }),
  };
  assert.equal(await lib.sendMail(outgoing(), transport), false);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].password, "abcdefghijklmnop");
  assert.deepEqual(calls[0].message.envelope, { from: "asso@mail.example", to: ["lea@mail.example"] });
});

test("sendMail answers true once the SMTP transport accepted the message", async () => {
  const transport = {
    db: null,
    user: () => "asso@mail.example",
    password: () => "secret",
    smtp: () => ({ sendMail: async () => ({ accepted: ["lea@mail.example"] }) }),
  };
  assert.equal(await lib.sendMail(outgoing(), transport), true);
});

test("logo image removed from the html when no logo is attached", async () => {
  const html = `<p>${TOKEN}</p><td><img src="cid:logo" \n     alt="Logo" \n     style="max-width: 120px;"></td><img src="cid:qrcode">`;
  const none = await lib.buildQrMail(template({ html }), "Léa", "id");
  assert.equal(none.html, "<p>Léa</p><td></td><img src=\"cid:qrcode\">");
  const withLogo = await lib.buildQrMail(template({ html, logoBase64: PNG }), "Léa", "id");
  assert.ok(withLogo.html.includes("src=\"cid:logo\""));
});
