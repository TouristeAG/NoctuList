import { test } from "node:test";
import assert from "node:assert/strict";
import { PDFDocument, PDFName, PDFDict } from "pdf-lib";
import { layoutParagraphs, renderCertificatePdf } from "../lib/index.js";

const measure = (t) => t.length * 5;
const para = (text, extra = {}) => ({
  align: "LEFT",
  size: "NORMAL",
  bullet: false,
  runs: [{ text, b: false, i: false, u: false }],
  ...extra,
});
const lineText = (l) => l.segments.map((s) => s.text).join("");

// 1x1 transparent PNG
const PNG = Uint8Array.from(
  Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64"),
);

const base = (over = {}) => ({
  paragraphs: [para("Attestation", { size: "TITLE", align: "CENTER" }), para("Texte du corps.")],
  footer: [para("Pied de page", { size: "SMALL", align: "CENTER" })],
  logoPng: null,
  signatureImage: null,
  signer: { name: "Jeanne Dupont", title: "Présidente" },
  title: "Attestation",
  ...over,
});

test("wraps on spaces", () => {
  const lines = layoutParagraphs([para("aaaa bbbb cccc")], 50, measure);
  assert.deepEqual(lines.map((l) => lineText(l).trim()), ["aaaa bbbb", "cccc"]);
});

test("splits a word longer than the line", () => {
  const lines = layoutParagraphs([para("x".repeat(30))], 50, measure);
  assert.equal(lines.length, 3);
});

test("keeps styles across a wrap", () => {
  const p = { ...para(""), runs: [{ text: "aaaa bbbb cccc", b: true, i: false, u: false }] };
  const lines = layoutParagraphs([p], 50, measure);
  assert.equal(lines.length, 2);
  for (const l of lines) for (const s of l.segments) assert.equal(s.b, true);
});

test("renders a one-page certificate", async () => {
  const bytes = await renderCertificatePdf(base());
  assert.equal(Buffer.from(bytes.slice(0, 5)).toString(), "%PDF-");
  const doc = await PDFDocument.load(bytes);
  assert.equal(doc.getPageCount(), 1);
  const { width, height } = doc.getPage(0).getSize();
  assert.ok(Math.abs(width - 595.28) < 0.1 && Math.abs(height - 841.89) < 0.1);
});

test("renders a two-page certificate", async () => {
  const paragraphs = Array.from({ length: 80 }, (_, i) => para(`Paragraphe numéro ${i} avec un peu de texte.`));
  const doc = await PDFDocument.load(await renderCertificatePdf(base({ paragraphs })));
  assert.ok(doc.getPageCount() >= 2);
});

test("survives characters missing from the font", async () => {
  const paragraphs = [para("🎉 Œ “ok” ş"), para("日本語")];
  const bytes = await renderCertificatePdf(base({ paragraphs }));
  assert.equal(Buffer.from(bytes.slice(0, 5)).toString(), "%PDF-");
});

test("embeds the logo and the signature", async () => {
  const bytes = await renderCertificatePdf(base({ logoPng: PNG, signatureImage: PNG }));
  const doc = await PDFDocument.load(bytes);
  const xobjects = doc.getPage(0).node.Resources().lookup(PDFName.of("XObject"), PDFDict);
  let images = 0;
  for (const key of xobjects.keys()) {
    const o = xobjects.lookup(key);
    if (o.dict?.get(PDFName.of("Subtype"))?.toString() === "/Image") images++;
  }
  assert.equal(images, 2);
});
