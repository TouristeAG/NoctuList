// Renders the volunteer certificate as a PDF. The text is already assembled and resolved
// (certificate.ts); this file only lays it out and draws it. Nothing here is logged or stored.
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import fontkit from "@pdf-lib/fontkit";
import { PDFDocument, rgb, type PDFFont, type PDFPage, type PDFImage } from "pdf-lib";
import type { ResolvedParagraph } from "./certificate";

export interface LaidSegment {
  text: string;
  b: boolean;
  i: boolean;
  u: boolean;
  width: number;
}

export interface LaidLine {
  /** Offset from the left edge of the text area (indent, or the offset that centres or right-aligns the line). */
  x: number;
  size: number;
  /** Space above the line (paragraph spacing, only on the first line of a paragraph). */
  gapBefore: number;
  /** True on the first line of a bullet paragraph, where the bullet is drawn. */
  bullet: boolean;
  /** The line is stretched to the full width (justified paragraph, not its last line). */
  justify: boolean;
  /** Last line of its paragraph. */
  last: boolean;
  segments: LaidSegment[];
}

export type Measure = (text: string, bold: boolean, italic: boolean, size: number) => number;

const SIZE_PT = { SMALL: 9, NORMAL: 11, LARGE: 14, TITLE: 22 } as const;
const LEADING = 1.5;
const PARAGRAPH_GAP = 1.1; // of the font size
const TITLE_GAP_AFTER = 28;
const DATE_GAP_BEFORE = 24;
const LOGO_GAP = 14;
const RULE_HEIGHT = 50; // the rule sits about 24 pt above the title
const SIGNATURE_GAP = 44;
const BULLET_INDENT = 14;

type Token = { text: string; b: boolean; i: boolean; u: boolean; space: boolean } | { br: true };

function tokenize(p: ResolvedParagraph): Token[] {
  const out: Token[] = [];
  for (const r of p.runs) {
    for (const part of r.text.split(/(\n|[^\S\n]+)/)) {
      if (part === "") continue;
      if (part === "\n") out.push({ br: true });
      else out.push({ text: part, b: r.b, i: r.i, u: r.u, space: /^\s+$/.test(part) });
    }
  }
  return out;
}

/** Breaks paragraphs into lines of at most `width` points. Breaks on spaces; a word longer than a line is cut by character. */
export function layoutParagraphs(paragraphs: ResolvedParagraph[], width: number, measure: Measure): LaidLine[] {
  const lines: LaidLine[] = [];
  paragraphs.forEach((p, index) => {
    const size = SIZE_PT[p.size];
    const prevTitle = index > 0 && paragraphs[index - 1].size === "TITLE";
    const indent = p.bullet ? BULLET_INDENT : 0;
    const avail = width - indent;
    const rows: LaidSegment[][] = [];
    const hardBreak: boolean[] = [];
    let cur: LaidSegment[] = [];
    let curWidth = 0;
    let pendingSpace: { text: string; b: boolean; i: boolean; u: boolean } | null = null;

    const flush = (hard: boolean) => {
      rows.push(cur);
      hardBreak.push(hard);
      cur = [];
      curWidth = 0;
      pendingSpace = null;
    };
    const add = (text: string, t: { b: boolean; i: boolean; u: boolean }) => {
      const last = cur[cur.length - 1];
      const merged = last && last.b === t.b && last.i === t.i && last.u === t.u ? last.text + text : null;
      if (merged !== null) {
        const w = measure(merged, t.b, t.i, size);
        curWidth += w - last.width;
        last.text = merged;
        last.width = w;
      } else {
        const w = measure(text, t.b, t.i, size);
        cur.push({ text, b: t.b, i: t.i, u: t.u, width: w });
        curWidth += w;
      }
    };

    for (const tok of tokenize(p)) {
      if ("br" in tok) {
        flush(true);
        continue;
      }
      if (tok.space) {
        if (cur.length > 0) pendingSpace = { text: tok.text, b: tok.b, i: tok.i, u: tok.u };
        continue;
      }
      const spaceW = pendingSpace ? measure(pendingSpace.text, pendingSpace.b, pendingSpace.i, size) : 0;
      const wordW = measure(tok.text, tok.b, tok.i, size);
      if (curWidth + spaceW + wordW <= avail) {
        if (pendingSpace) add(pendingSpace.text, pendingSpace);
        pendingSpace = null;
        add(tok.text, tok);
        continue;
      }
      if (cur.length > 0) flush(false);
      pendingSpace = null;
      if (wordW <= avail) {
        add(tok.text, tok);
        continue;
      }
      // Longer than a whole line: cut by character.
      let piece = "";
      for (const ch of Array.from(tok.text)) {
        if (piece !== "" && measure(piece + ch, tok.b, tok.i, size) > avail) {
          add(piece, tok);
          flush(false);
          piece = "";
        }
        piece += ch;
      }
      if (piece !== "") add(piece, tok);
    }
    if (cur.length > 0 || rows.length === 0) flush(true);
    // The very last row ends the paragraph; hard breaks also end a justified block.
    rows.forEach((segs, r) => {
      const isLast = r === rows.length - 1 || hardBreak[r];
      const lineWidth = segs.reduce((s, x) => s + x.width, 0);
      let x = indent;
      if (p.align === "CENTER") x = indent + (avail - lineWidth) / 2;
      else if (p.align === "RIGHT") x = indent + (avail - lineWidth);
      lines.push({
        x,
        size,
        gapBefore: r === 0 && index > 0 ? Math.max(size * PARAGRAPH_GAP, prevTitle ? TITLE_GAP_AFTER : 0) : 0,
        bullet: p.bullet && r === 0,
        justify: p.align === "JUSTIFY" && !isLast,
        last: isLast,
        segments: segs,
      });
    });
  });
  return lines;
}

// ---------------------------------------------------------------------------------------------
// Fonts

const FILES = {
  "400": ["nunito-400.ttf"],
  "700": ["nunito-700.ttf"],
  "400i": ["nunito-latin-400-italic.woff", "nunito-latin-ext-400-italic.woff"],
  "700i": ["nunito-latin-700-italic.woff", "nunito-latin-ext-700-italic.woff"],
} as const;
type FaceKey = keyof typeof FILES;

const fontBytes = new Map<string, Promise<Uint8Array>>();
function loadFile(name: string): Promise<Uint8Array> {
  let p = fontBytes.get(name);
  if (!p) {
    p = readFile(fileURLToPath(new URL(`../assets/${name}`, import.meta.url)));
    fontBytes.set(name, p);
  }
  return p;
}

interface Face {
  fonts: PDFFont[];
  has: ((cp: number) => boolean)[];
}

const faceKey = (b: boolean, i: boolean): FaceKey => (b ? (i ? "700i" : "700") : i ? "400i" : "400");

/** Consecutive characters that the same font of the face draws. */
function pieces(face: Face, text: string): { font: PDFFont; text: string }[] {
  const out: { font: PDFFont; text: string }[] = [];
  for (const ch of Array.from(text)) {
    const cp = ch.codePointAt(0)!;
    let idx = face.has.findIndex((h) => h(cp));
    if (idx < 0) idx = 0;
    const last = out[out.length - 1];
    if (last && last.font === face.fonts[idx]) last.text += ch;
    else out.push({ font: face.fonts[idx], text: ch });
  }
  return out;
}

async function loadFaces(doc: PDFDocument): Promise<Record<FaceKey, Face>> {
  doc.registerFontkit(fontkit);
  const faces = {} as Record<FaceKey, Face>;
  for (const key of Object.keys(FILES) as FaceKey[]) {
    const fonts: PDFFont[] = [];
    const has: ((cp: number) => boolean)[] = [];
    for (const name of FILES[key]) {
      const bytes = await loadFile(name);
      const kit = fontkit.create(bytes as Buffer);
      has.push((cp) => kit.hasGlyphForCodePoint(cp));
      fonts.push(await doc.embedFont(bytes, { subset: true }));
    }
    faces[key] = { fonts, has };
  }
  return faces;
}

/** Replaces what no font of the family can draw with `?`, and drops control characters. */
function clean(text: string, faces: Record<FaceKey, Face>): string {
  let out = "";
  for (const ch of Array.from(text)) {
    const cp = ch.codePointAt(0)!;
    if (ch === "\n" || /\s/.test(ch)) {
      out += ch === "\n" ? "\n" : " ";
    } else if (cp < 32 || (cp >= 0x7f && cp < 0xa0)) {
      continue;
    } else if (faces["400"].has.some((h) => h(cp)) || faces["400i"].has.some((h) => h(cp))) {
      out += ch;
    } else {
      out += "?";
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Rendering

export interface CertificatePdfInput {
  paragraphs: ResolvedParagraph[];
  footer: ResolvedParagraph[];
  logoPng: Uint8Array | null;
  signatureImage: Uint8Array | null;
  signer: { name: string; title: string };
  title: string;
}

const PAGE_W = 595.28;
const PAGE_H = 841.89;
const MARGIN = 70.87; // 25 mm
const FOOTER_BASE = 38; // distance from the page bottom to the last footer line
const INK = rgb(0.1, 0.1, 0.12);
const GREY = rgb(0.45, 0.45, 0.48);
const RULE = rgb(0.75, 0.75, 0.78);

type Item =
  | { kind: "line"; line: LaidLine }
  | { kind: "rule"; height: number }
  | { kind: "image"; image: PDFImage; w: number; h: number; x: number; gapBefore: number }
  | { kind: "signer"; lines: LaidLine[]; gapBefore: number; afterImage: boolean };

const lineHeight = (l: LaidLine) => l.size * LEADING;

async function embedImage(doc: PDFDocument, bytes: Uint8Array | null): Promise<PDFImage | null> {
  if (!bytes || bytes.length === 0) return null;
  try {
    return await doc.embedPng(bytes);
  } catch {
    try {
      return await doc.embedJpg(bytes);
    } catch {
      return null;
    }
  }
}

export async function renderCertificatePdf(input: CertificatePdfInput): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  doc.setTitle(input.title);
  doc.setAuthor("");
  doc.setProducer("");
  doc.setCreator("");
  doc.setSubject("");
  doc.setKeywords([]);
  const faces = await loadFaces(doc);

  const measure: Measure = (text, b, i, size) =>
    pieces(faces[faceKey(b, i)], text).reduce((s, p) => s + p.font.widthOfTextAtSize(p.text, size), 0);
  const prep = (ps: ResolvedParagraph[]): ResolvedParagraph[] =>
    ps.map((p) => ({ ...p, runs: p.runs.map((r) => ({ ...r, text: clean(r.text, faces) })) }));

  const textW = PAGE_W - 2 * MARGIN;
  const paragraphs = prep(input.paragraphs);
  const titleAt = paragraphs.findIndex((p) => p.size === "TITLE");
  const headerCount = titleAt > 0 ? titleAt : 0;
  const items: Item[] = [];

  const logo = await embedImage(doc, input.logoPng);
  if (logo) {
    const scale = Math.min(48 / logo.height, 200 / logo.width);
    const w = logo.width * scale;
    const h = logo.height * scale;
    const align = paragraphs[0]?.align ?? "LEFT";
    const x = align === "CENTER" ? (textW - w) / 2 : align === "RIGHT" ? textW - w : 0;
    items.push({ kind: "image", image: logo, w, h, x, gapBefore: 0 });
  }
  const logoGap = logo ? LOGO_GAP : 0;

  const headerLines = layoutParagraphs(paragraphs.slice(0, headerCount), textW, measure);
  headerLines.forEach((line, k) => {
    items.push({ kind: "line", line: k === 0 && logo ? { ...line, gapBefore: logoGap } : line });
  });
  if (headerCount > 0) items.push({ kind: "rule", height: RULE_HEIGHT });
  const bodyLines = layoutParagraphs(paragraphs.slice(headerCount), textW, measure);
  // The last paragraph (the date line) is set apart from the text above it.
  let dateIdx = bodyLines.length - 1;
  while (dateIdx > 0 && bodyLines[dateIdx].gapBefore === 0) dateIdx--;
  bodyLines.forEach((line, k) => {
    const gap = k === dateIdx && k > 0 ? Math.max(line.gapBefore, DATE_GAP_BEFORE) : line.gapBefore;
    items.push({ kind: "line", line: { ...line, gapBefore: gap } });
  });

  // Signature, right after the last line of the body.
  const sig = await embedImage(doc, input.signatureImage);
  const signerLines: LaidLine[] = [];
  const signerName = clean(input.signer.name, faces).trim();
  const signerTitle = clean(input.signer.title, faces).trim();
  const plain = (text: string, b: boolean): ResolvedParagraph => ({
    align: "RIGHT",
    size: "NORMAL",
    bullet: false,
    runs: [{ text, b, i: false, u: false }],
  });
  if (signerName) signerLines.push(...layoutParagraphs([plain(signerName, true)], textW, measure));
  if (signerTitle) signerLines.push(...layoutParagraphs([plain(signerTitle, false)], textW, measure));
  if (sig || signerLines.length > 0) {
    let imgItem: Item | null = null;
    if (sig) {
      const scale = Math.min(120 / sig.width, 60 / sig.height);
      const w = sig.width * scale;
      imgItem = { kind: "image", image: sig, w, h: sig.height * scale, x: textW - w, gapBefore: 0 };
    }
    if (imgItem) items.push(imgItem);
    items.push({ kind: "signer", lines: signerLines, gapBefore: SIGNATURE_GAP, afterImage: imgItem !== null });
  }

  const footerLines = layoutParagraphs(
    prep(input.footer).map((p) => ({ ...p, size: "SMALL" as const })),
    textW,
    measure,
  );
  const footerH = footerLines.reduce((s, l) => s + lineHeight(l), 0);
  const bottomLimit = FOOTER_BASE + footerH + (footerLines.length > 0 ? 18 : 0);
  const top = PAGE_H - MARGIN;

  const drawLine = (page: PDFPage, line: LaidLine, topY: number, left: number, color = INK) => {
    const baseline = topY - line.size * 1.08;
    if (line.bullet) {
      const bullet = faces["400"].has[0]("•".codePointAt(0)!) ? "•" : "-";
      page.drawText(bullet, { x: left, y: baseline, size: line.size, font: faces["400"].fonts[0], color });
    }
    let spaceExtra = 0;
    if (line.justify) {
      const spaces = line.segments.reduce((n, s) => n + (s.text.match(/ /g)?.length ?? 0), 0);
      const used = line.segments.reduce((s, x) => s + x.width, 0);
      const target = textW - line.x;
      if (spaces > 0 && used < target) spaceExtra = (target - used) / spaces;
    }
    let x = left + line.x;
    for (const seg of line.segments) {
      const face = faces[faceKey(seg.b, seg.i)];
      const start = x;
      const words = spaceExtra > 0 ? seg.text.split(/( )/) : [seg.text];
      for (const word of words) {
        if (word === "") continue;
        for (const piece of pieces(face, word)) {
          if (piece.text.trim() !== "") {
            page.drawText(piece.text, { x, y: baseline, size: line.size, font: piece.font, color });
          }
          x += piece.font.widthOfTextAtSize(piece.text, line.size);
        }
        if (word === " ") x += spaceExtra;
      }
      if (seg.u) {
        page.drawLine({ start: { x: start, y: baseline - 1.6 }, end: { x, y: baseline - 1.6 }, thickness: 0.6, color });
      }
    }
  };

  const drawFooter = (page: PDFPage) => {
    let y = FOOTER_BASE + footerH;
    for (const l of footerLines) {
      drawLine(page, l, y, MARGIN, GREY);
      y -= lineHeight(l);
    }
  };

  let page = doc.addPage([PAGE_W, PAGE_H]);
  drawFooter(page);
  let y = top;
  const newPage = () => {
    page = doc.addPage([PAGE_W, PAGE_H]);
    drawFooter(page);
    y = top;
  };

  for (const item of items) {
    if (item.kind === "line") {
      const h = item.line.gapBefore + lineHeight(item.line);
      if (y - h < bottomLimit && y < top) newPage();
      // A paragraph gap is not kept at the top of a page.
      const gap = y === top ? 0 : item.line.gapBefore;
      drawLine(page, item.line, y - gap, MARGIN);
      y -= gap + lineHeight(item.line);
    } else if (item.kind === "rule") {
      const mid = item.height / 2;
      if (y - item.height < bottomLimit && y < top) newPage();
      page.drawLine({
        start: { x: MARGIN, y: y - mid },
        end: { x: PAGE_W - MARGIN, y: y - mid },
        thickness: 0.5,
        color: RULE,
      });
      y -= item.height;
    } else if (item.kind === "image") {
      // Keep the signature image with the signer lines that follow it.
      const next = items[items.indexOf(item) + 1];
      const follow = next && next.kind === "signer" ? next.gapBefore + next.lines.reduce((s, l) => s + lineHeight(l), 0) : 0;
      if (y - item.h - follow - (item.gapBefore + SIGNATURE_GAP) < bottomLimit && y < top && next?.kind === "signer") newPage();
      const gap = next?.kind === "signer" && y !== top ? SIGNATURE_GAP : item.gapBefore;
      page.drawImage(item.image, { x: MARGIN + item.x, y: y - gap - item.h, width: item.w, height: item.h });
      y -= gap + item.h + 4;
    } else {
      const afterImage = item.afterImage;
      const h = item.lines.reduce((s, l) => s + lineHeight(l), 0);
      if (!afterImage && y - h - item.gapBefore < bottomLimit && y < top) newPage();
      if (!afterImage && y !== top) y -= item.gapBefore;
      for (const l of item.lines) {
        drawLine(page, l, y, MARGIN);
        y -= lineHeight(l);
      }
    }
  }
  return doc.save();
}
