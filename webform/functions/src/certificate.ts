// The certificate template as a device publishes it (portalMail/certificate). The text is assembled
// on the admin device (Kotlin, portal/certificate); the server only reads the template and renders
// the paragraphs a device sends.

export type Align = "LEFT" | "CENTER" | "RIGHT" | "JUSTIFY";
export type Size = "SMALL" | "NORMAL" | "LARGE" | "TITLE";

export interface CertRun {
  text: string;
  var: string;
  gender: string[];
  b: boolean;
  i: boolean;
  u: boolean;
}

export interface CertParagraph {
  align: Align;
  size: Size;
  bullet: boolean;
  runs: CertRun[];
}

export interface CertBlock {
  one: CertParagraph[];
  many: CertParagraph[];
  manyOneRole: CertParagraph[];
  line: CertParagraph[];
}

export interface CertificateTemplate {
  version: number;
  language: string;
  place: string;
  signer: { name: string; title: string; imageBase64: string };
  mail: { fromName: string; fromAddress: string; replyTo: string; subject: string; body: string };
  blocks: Record<string, CertBlock>;
}

const ALIGNS = ["LEFT", "CENTER", "RIGHT", "JUSTIFY"];
const SIZES = ["SMALL", "NORMAL", "LARGE", "TITLE"];

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);

class Unreadable extends Error {}

function field<T>(o: Obj, key: string, fallback: T, ok: (v: unknown) => boolean): T {
  const v = o[key];
  if (v === undefined) return fallback;
  if (!ok(v)) throw new Unreadable(key);
  return v as T;
}

const isStr = (v: unknown) => typeof v === "string";
const isBool = (v: unknown) => typeof v === "boolean";
const isInt = (v: unknown) => typeof v === "number" && Number.isInteger(v);

function objOf(v: unknown): Obj {
  if (!isObj(v)) throw new Unreadable("object");
  return v;
}

function listOf<T>(o: Obj, key: string, read: (v: unknown) => T): T[] {
  const v = o[key];
  if (v === undefined) return [];
  if (!Array.isArray(v)) throw new Unreadable(key);
  return v.map(read);
}

function readRun(raw: unknown): CertRun {
  const o = objOf(raw);
  return {
    text: field(o, "text", "", isStr),
    var: field(o, "var", "", isStr),
    gender: listOf(o, "gender", (g) => {
      if (typeof g !== "string") throw new Unreadable("gender");
      return g;
    }),
    b: field(o, "b", false, isBool),
    i: field(o, "i", false, isBool),
    u: field(o, "u", false, isBool),
  };
}

function readParagraph(raw: unknown): CertParagraph {
  const o = objOf(raw);
  return {
    align: field(o, "align", "LEFT", (v) => ALIGNS.includes(v as string)) as Align,
    size: field(o, "size", "NORMAL", (v) => SIZES.includes(v as string)) as Size,
    bullet: field(o, "bullet", false, isBool),
    runs: listOf(o, "runs", readRun),
  };
}

function readBlock(raw: unknown): CertBlock {
  const o = objOf(raw);
  return {
    one: listOf(o, "one", readParagraph),
    many: listOf(o, "many", readParagraph),
    manyOneRole: listOf(o, "manyOneRole", readParagraph),
    line: listOf(o, "line", readParagraph),
  };
}

function strings<K extends string>(raw: unknown, keys: K[]): Record<K, string> {
  const o = raw === undefined ? {} : objOf(raw);
  const out = {} as Record<K, string>;
  for (const k of keys) out[k] = field(o, k, "", isStr);
  return out;
}

/** Returns null when [raw] (a JSON string or an already parsed value) is not a readable template. */
export function parseTemplate(raw: unknown): CertificateTemplate | null {
  try {
    const value = typeof raw === "string" ? JSON.parse(raw) : raw;
    const o = objOf(value);
    const blocks: Record<string, CertBlock> = {};
    if (o.blocks !== undefined) {
      for (const [key, block] of Object.entries(objOf(o.blocks))) blocks[key] = readBlock(block);
    }
    return {
      version: field(o, "version", 1, isInt),
      language: field(o, "language", "fr", isStr),
      place: field(o, "place", "", isStr),
      signer: strings(o.signer, ["name", "title", "imageBase64"]),
      mail: strings(o.mail, ["fromName", "fromAddress", "replyTo", "subject", "body"]),
      blocks,
    };
  } catch {
    return null;
  }
}

/** A run of text already resolved by the admin device: no variable left. */
export interface ResolvedRun {
  text: string;
  b: boolean;
  i: boolean;
  u: boolean;
}
export interface ResolvedParagraph {
  align: Align;
  size: Size;
  bullet: boolean;
  runs: ResolvedRun[];
}
