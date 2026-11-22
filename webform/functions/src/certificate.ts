// TypeScript copy of shared/src/commonMain/kotlin/com/eventmanager/app/portal/certificate/*.
// It must give the same result as the Kotlin assembler: both are checked against
// test/certificate-cases.json and test/certificate-default-fr.json.

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

export type Category = "MANDATE" | "SHIFT" | "PHOTO" | "GRAPHIC_EVENT" | "GRAPHIC_ASSOCIATION" | "OTHER" | "MEETING";

/** Declaration order is the order of the blocks in the document. */
const CATEGORIES: Category[] = ["MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT", "GRAPHIC_ASSOCIATION", "OTHER", "MEETING"];

export function categoryOf(
  config: { isOrionJob?: boolean; isShiftJob?: boolean; novaJobType?: string } | undefined,
): Category {
  if (!config) return "OTHER";
  if (config.isOrionJob === true) return "MANDATE";
  switch (config.novaJobType) {
    case "MEETING":
      return "MEETING";
    case "PHOTOGRAPHER_VIDEOGRAPHER":
      return "PHOTO";
    case "GRAPHIC_DESIGNER_EVENT":
      return "GRAPHIC_EVENT";
    case "GRAPHIC_DESIGNER_ASSOCIATION":
      return "GRAPHIC_ASSOCIATION";
    default:
      return config.isShiftJob === false ? "OTHER" : "SHIFT";
  }
}

// ---------------------------------------------------------------------------------------------
// Parsing

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

// ---------------------------------------------------------------------------------------------
// Dates

const MONTHS: Record<string, string[]> = {
  fr: ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"],
  en: ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"],
  de: ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"],
  it: ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"],
  es: ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"],
};

const zurich = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Europe/Zurich",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** Day as YYYYMMDD in the association time zone (Europe/Zurich). */
export function dayOf(millis: number): number {
  const parts = zurich.formatToParts(new Date(millis));
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return get("year") * 10000 + get("month") * 100 + get("day");
}

const lang = (l: string) => (Object.prototype.hasOwnProperty.call(MONTHS, l) ? l : "en");
const yearOf = (day: number) => Math.trunc(day / 10000);
const monthIndex = (day: number) => Math.trunc(day / 100) % 100;
const monthName = (day: number, l: string) => MONTHS[l][monthIndex(day) - 1];
const withYear = (l: string, month: string, year: number) => (l === "es" ? `${month} de ${year}` : `${month} ${year}`);

export function longDate(day: number, language: string): string {
  const l = lang(language);
  const d = day % 100;
  const m = monthName(day, l);
  const y = yearOf(day);
  switch (l) {
    case "fr":
      return `${d === 1 ? "1er" : d} ${m} ${y}`;
    case "de":
      return `${d}. ${m} ${y}`;
    case "es":
      return `${d} de ${m} de ${y}`;
    case "it":
      return `${d === 1 ? "1°" : d} ${m} ${y}`;
    default:
      return `${d} ${m} ${y}`;
  }
}

export function monthYear(day: number, language: string): string {
  const l = lang(language);
  return withYear(l, monthName(day, l), yearOf(day));
}

export function period(firstDay: number, lastDay: number, language: string): string {
  const l = lang(language);
  const sameYear = yearOf(firstDay) === yearOf(lastDay);
  const sameMonth = sameYear && monthIndex(firstDay) === monthIndex(lastDay);
  const first = monthName(firstDay, l);
  const last = monthName(lastDay, l);
  if (sameMonth) {
    const my = withYear(l, first, yearOf(firstDay));
    switch (l) {
      case "fr":
      case "es":
        return `en ${my}`;
      case "en":
        return `in ${my}`;
      case "de":
        return `im ${my}`;
      default:
        return `a ${my}`;
    }
  }
  let between: string;
  let and: string;
  switch (l) {
    case "fr":
      [between, and] = ["entre", "et"];
      break;
    case "en":
      [between, and] = ["between", "and"];
      break;
    case "de":
      [between, and] = ["zwischen", "und"];
      break;
    case "it":
      [between, and] = ["tra", "e"];
      break;
    default:
      [between, and] = ["entre", "y"];
  }
  return sameYear
    ? `${between} ${first} ${and} ${withYear(l, last, yearOf(lastDay))}`
    : `${between} ${withYear(l, first, yearOf(firstDay))} ${and} ${withYear(l, last, yearOf(lastDay))}`;
}

// ---------------------------------------------------------------------------------------------
// Defaults

interface Texts {
  title: string;
  introOne: string;
  introMany: string;
  mandate: string;
  shiftOne: string;
  shiftMany: string;
  shiftManyOneRole: string;
  photoOne: string;
  photoMany: string;
  graphicEventOne: string;
  graphicEventMany: string;
  graphicAssociation: string;
  otherOne: string;
  otherMany: string;
  otherManyOneRole: string;
  meetingOne: string;
  meetingMany: string;
  closing: string;
  dateLine: string;
  mailSubject: string;
  mailBody: string;
}

const LANGUAGES = ["fr", "en", "de", "it", "es"];

const TEXTS: Record<string, Texts> = {
  fr: {
    title: "Attestation de bénévolat",
    introOne: "{association} atteste que {firstName} {lastName} s'est [engagé|engagée|engagéx] bénévolement au sein de l'association.",
    introMany: "{association} atteste que {firstName} {lastName} s'est [engagé|engagée|engagéx] bénévolement au sein de l'association {period}.",
    mandate: "{firstName} a exercé le mandat de {role} {period}.",
    shiftOne: "Le {date}, {firstName} a participé bénévolement à un événement de {association} en tant que {role}.",
    shiftMany: "{firstName} a participé bénévolement à {count} événements de {association} {period}, dans les rôles suivants :",
    shiftManyOneRole: "{firstName} a participé bénévolement à {count} événements de {association} {period}, en tant que {role}.",
    photoOne: "Le {date}, {firstName} a assuré la couverture photo et vidéo d'un événement de {association}.",
    photoMany: "{firstName} a assuré la couverture photo et vidéo de {count} événements de {association} {period}.",
    graphicEventOne: "Le {date}, {firstName} a réalisé les visuels d'un événement de {association}.",
    graphicEventMany: "{firstName} a réalisé les visuels de {count} événements de {association} {period}.",
    graphicAssociation: "{firstName} a contribué à l'identité graphique de {association} {period}.",
    otherOne: "Le {date}, {firstName} a mené une mission pour {association} en tant que {role}.",
    otherMany: "{firstName} a mené {count} missions pour {association} {period}, dans les rôles suivants :",
    otherManyOneRole: "{firstName} a mené {count} missions pour {association} {period}, en tant que {role}.",
    meetingOne: "Le {date}, {firstName} a participé à une réunion de {association}.",
    meetingMany: "{firstName} a participé à {count} réunions de {association} {period}.",
    closing: "Nous remercions chaleureusement {firstName} pour son engagement.",
    dateLine: "Fait à {place}, le {today}",
    mailSubject: "Ton attestation de bénévolat",
    mailBody: "Bonjour {firstName}, voici ton attestation de bénévolat pour {association}. Elle est jointe à cet e-mail.",
  },
  en: {
    title: "Volunteer certificate",
    introOne: "{association} certifies that {firstName} {lastName} has volunteered with the association.",
    introMany: "{association} certifies that {firstName} {lastName} has volunteered with the association {period}.",
    mandate: "{firstName} held the role of {role} {period}.",
    shiftOne: "On {date}, {firstName} volunteered at an event of {association} as {role}.",
    shiftMany: "{firstName} volunteered at {count} events of {association} {period}, in the following roles:",
    shiftManyOneRole: "{firstName} volunteered at {count} events of {association} {period}, as {role}.",
    photoOne: "On {date}, {firstName} provided photo and video coverage of an event of {association}.",
    photoMany: "{firstName} provided photo and video coverage of {count} events of {association} {period}.",
    graphicEventOne: "On {date}, {firstName} designed the visuals for an event of {association}.",
    graphicEventMany: "{firstName} designed the visuals for {count} events of {association} {period}.",
    graphicAssociation: "{firstName} contributed to the graphic identity of {association} {period}.",
    otherOne: "On {date}, {firstName} carried out a task for {association} as {role}.",
    otherMany: "{firstName} carried out {count} tasks for {association} {period}, in the following roles:",
    otherManyOneRole: "{firstName} carried out {count} tasks for {association} {period}, as {role}.",
    meetingOne: "On {date}, {firstName} took part in a meeting of {association}.",
    meetingMany: "{firstName} took part in {count} meetings of {association} {period}.",
    closing: "We warmly thank {firstName} for their commitment.",
    dateLine: "Issued in {place}, on {today}",
    mailSubject: "Your volunteer certificate",
    mailBody: "Hello {firstName}, here is your volunteer certificate for {association}. It is attached to this e-mail.",
  },
  de: {
    title: "Bestätigung über ehrenamtliches Engagement",
    introOne: "{association} bestätigt, dass sich {firstName} {lastName} ehrenamtlich im Verein engagiert hat.",
    introMany: "{association} bestätigt, dass sich {firstName} {lastName} {period} ehrenamtlich im Verein engagiert hat.",
    mandate: "{firstName} hat {period} das Amt als {role} ausgeübt.",
    shiftOne: "Am {date} hat sich {firstName} ehrenamtlich bei einer Veranstaltung von {association} als {role} engagiert.",
    shiftMany: "{firstName} hat sich {period} ehrenamtlich bei {count} Veranstaltungen von {association} engagiert, und zwar in folgenden Funktionen:",
    shiftManyOneRole: "{firstName} hat sich {period} ehrenamtlich bei {count} Veranstaltungen von {association} engagiert, und zwar als {role}.",
    photoOne: "Am {date} hat {firstName} eine Veranstaltung von {association} fotografisch und filmisch begleitet.",
    photoMany: "{firstName} hat {period} {count} Veranstaltungen von {association} fotografisch und filmisch begleitet.",
    graphicEventOne: "Am {date} hat {firstName} die Grafiken für eine Veranstaltung von {association} gestaltet.",
    graphicEventMany: "{firstName} hat {period} die Grafiken für {count} Veranstaltungen von {association} gestaltet.",
    graphicAssociation: "{firstName} hat {period} zum grafischen Auftritt von {association} beigetragen.",
    otherOne: "Am {date} hat {firstName} für {association} eine Aufgabe als {role} übernommen.",
    otherMany: "{firstName} hat {period} für {association} {count} Aufgaben übernommen, und zwar in folgenden Funktionen:",
    otherManyOneRole: "{firstName} hat {period} für {association} {count} Aufgaben übernommen, und zwar als {role}.",
    meetingOne: "Am {date} hat {firstName} an einer Sitzung von {association} teilgenommen.",
    meetingMany: "{firstName} hat {period} an {count} Sitzungen von {association} teilgenommen.",
    closing: "Wir danken {firstName} herzlich für das Engagement.",
    dateLine: "Ausgestellt in {place}, am {today}",
    mailSubject: "Deine Bestätigung über ehrenamtliches Engagement",
    mailBody: "Hallo {firstName}, hier ist deine Bestätigung über dein ehrenamtliches Engagement bei {association}. Sie ist dieser E-Mail angehängt.",
  },
  it: {
    title: "Attestazione di volontariato",
    introOne: "{association} attesta che {firstName} {lastName} ha svolto attività di volontariato nell'associazione.",
    introMany: "{association} attesta che {firstName} {lastName} ha svolto attività di volontariato nell'associazione {period}.",
    mandate: "{firstName} ha ricoperto l'incarico di {role} {period}.",
    shiftOne: "In data {date}, {firstName} ha svolto attività di volontariato a un evento di {association} come {role}.",
    shiftMany: "{firstName} ha svolto attività di volontariato a {count} eventi di {association} {period}, con i seguenti ruoli:",
    shiftManyOneRole: "{firstName} ha svolto attività di volontariato a {count} eventi di {association} {period}, come {role}.",
    photoOne: "In data {date}, {firstName} ha curato la copertura fotografica e video di un evento di {association}.",
    photoMany: "{firstName} ha curato la copertura fotografica e video di {count} eventi di {association} {period}.",
    graphicEventOne: "In data {date}, {firstName} ha realizzato la grafica di un evento di {association}.",
    graphicEventMany: "{firstName} ha realizzato la grafica di {count} eventi di {association} {period}.",
    graphicAssociation: "{firstName} ha contribuito all'identità grafica di {association} {period}.",
    otherOne: "In data {date}, {firstName} ha svolto un incarico per {association} come {role}.",
    otherMany: "{firstName} ha svolto {count} incarichi per {association} {period}, con i seguenti ruoli:",
    otherManyOneRole: "{firstName} ha svolto {count} incarichi per {association} {period}, come {role}.",
    meetingOne: "In data {date}, {firstName} ha partecipato a una riunione di {association}.",
    meetingMany: "{firstName} ha partecipato a {count} riunioni di {association} {period}.",
    closing: "Ringraziamo calorosamente {firstName} per il suo impegno.",
    dateLine: "Fatto a {place}, il {today}",
    mailSubject: "La tua attestazione di volontariato",
    mailBody: "Ciao {firstName}, ecco la tua attestazione di volontariato per {association}. La trovi in allegato a questa e-mail.",
  },
  es: {
    title: "Certificado de voluntariado",
    introOne: "{association} certifica que {firstName} {lastName} ha realizado tareas de voluntariado en la asociación.",
    introMany: "{association} certifica que {firstName} {lastName} ha realizado tareas de voluntariado en la asociación {period}.",
    mandate: "{firstName} ejerció el cargo de {role} {period}.",
    shiftOne: "El {date}, {firstName} realizó tareas de voluntariado en un evento de {association}, en calidad de {role}.",
    shiftMany: "{firstName} realizó tareas de voluntariado en {count} eventos de {association} {period}, con las siguientes funciones:",
    shiftManyOneRole: "{firstName} realizó tareas de voluntariado en {count} eventos de {association} {period}, en calidad de {role}.",
    photoOne: "El {date}, {firstName} se encargó de la cobertura fotográfica y de vídeo de un evento de {association}.",
    photoMany: "{firstName} se encargó de la cobertura fotográfica y de vídeo de {count} eventos de {association} {period}.",
    graphicEventOne: "El {date}, {firstName} diseñó los elementos gráficos de un evento de {association}.",
    graphicEventMany: "{firstName} diseñó los elementos gráficos de {count} eventos de {association} {period}.",
    graphicAssociation: "{firstName} contribuyó a la identidad gráfica de {association} {period}.",
    otherOne: "El {date}, {firstName} llevó a cabo una misión para {association} como {role}.",
    otherMany: "{firstName} llevó a cabo {count} misiones para {association} {period}, con las siguientes funciones:",
    otherManyOneRole: "{firstName} llevó a cabo {count} misiones para {association} {period}, como {role}.",
    meetingOne: "El {date}, {firstName} participó en una reunión de {association}.",
    meetingMany: "{firstName} participó en {count} reuniones de {association} {period}.",
    closing: "Agradecemos sinceramente a {firstName} su compromiso.",
    dateLine: "Expedido en {place}, a {today}",
    mailSubject: "Tu certificado de voluntariado",
    mailBody: "Hola {firstName}, aquí tienes tu certificado de voluntariado para {association}. Va adjunto a este correo.",
  },
};

const PLACEHOLDER = /\{(\w+)\}|\[([^\]|]*)\|([^\]|]*)\|([^\]|]*)\]/g;

function runsOf(template: string, bold = false): CertRun[] {
  const out: CertRun[] = [];
  const plain = (text: string): CertRun => ({ text, var: "", gender: [], b: bold, i: false, u: false });
  let last = 0;
  for (const m of template.matchAll(PLACEHOLDER)) {
    const start = m.index ?? 0;
    if (start > last) out.push(plain(template.substring(last, start)));
    if (m[1] !== undefined) {
      out.push({ text: "", var: m[1], gender: [], b: bold, i: false, u: false });
    } else {
      out.push({ text: "", var: "", gender: [m[2], m[3], m[4]], b: bold, i: false, u: false });
    }
    last = start + m[0].length;
  }
  if (last < template.length) out.push(plain(template.substring(last)));
  return out;
}

function para(template: string, align: Align = "LEFT", size: Size = "NORMAL", bold = false): CertParagraph {
  return { align, size, bullet: false, runs: runsOf(template, bold) };
}

const only = (variable: string) => para(`{${variable}}`);
const emptyBlock = (): CertBlock => ({ one: [], many: [], manyOneRole: [], line: [] });
const fixed = (paragraph: CertParagraph): CertBlock => ({ ...emptyBlock(), one: [paragraph] });

const roleLine = (): CertParagraph[] => [
  {
    align: "LEFT",
    size: "NORMAL",
    bullet: true,
    runs: [
      { text: "", var: "role", gender: [], b: true, i: false, u: false },
      { text: ": ", var: "", gender: [], b: false, i: false, u: false },
      { text: "", var: "description", gender: [], b: false, i: false, u: false },
    ],
  },
];

export function defaultTemplate(language: string, associationName: string): CertificateTemplate {
  const l = LANGUAGES.includes(language) ? language : "en";
  const t = TEXTS[l];
  const blocks: Record<string, CertBlock> = {
    header: fixed(para("{association}", "CENTER", "LARGE", true)),
    title: fixed(para(t.title, "CENTER", "TITLE", true)),
    intro: { ...emptyBlock(), one: [para(t.introOne)], many: [para(t.introMany)], manyOneRole: [para(t.introMany)] },
    MANDATE: { ...emptyBlock(), one: [para(t.mandate), only("description")] },
    SHIFT: {
      one: [para(t.shiftOne), only("description")],
      many: [para(t.shiftMany), only("roles")],
      manyOneRole: [para(t.shiftManyOneRole), only("description")],
      line: roleLine(),
    },
    PHOTO: { ...emptyBlock(), one: [para(t.photoOne)], many: [para(t.photoMany)] },
    GRAPHIC_EVENT: { ...emptyBlock(), one: [para(t.graphicEventOne)], many: [para(t.graphicEventMany)] },
    GRAPHIC_ASSOCIATION: { ...emptyBlock(), one: [para(t.graphicAssociation)], many: [para(t.graphicAssociation)] },
    OTHER: {
      one: [para(t.otherOne), only("description")],
      many: [para(t.otherMany), only("roles")],
      manyOneRole: [para(t.otherManyOneRole), only("description")],
      line: roleLine(),
    },
    MEETING: { ...emptyBlock(), one: [para(t.meetingOne)], many: [para(t.meetingMany)] },
    closing: fixed(para(t.closing)),
    dateLine: fixed(para(t.dateLine)),
    // Optional: one empty paragraph, dropped when rendering.
    footer: fixed({
      align: "CENTER",
      size: "SMALL",
      bullet: false,
      runs: [{ text: "", var: "", gender: [], b: false, i: false, u: false }],
    }),
  };
  return {
    version: 1,
    language: l,
    place: "",
    signer: { name: "", title: "", imageBase64: "" },
    mail: { fromName: associationName, fromAddress: "", replyTo: "", subject: t.mailSubject, body: t.mailBody },
    blocks,
  };
}

// ---------------------------------------------------------------------------------------------
// Assembler

export interface CertJobType {
  name: string;
  category: Category;
  description: string;
}
export interface CertPerson {
  firstName: string;
  lastName: string;
  /** Name of the Gender enum, or "" when unknown. */
  gender: string;
}
export interface CertJob {
  jobTypeName: string;
  day: number;
}
export interface CertSelection {
  fromDay: number;
  toDay: number;
  todayDay: number;
  jobTypes: string[];
}
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
export interface CertAssembly {
  body: ResolvedParagraph[];
  footer: ResolvedParagraph[];
}

/** Same set as Kotlin Char.isWhitespace (Java whitespace plus the Unicode space separators, NBSP included). */
function isWhitespace(c: string): boolean {
  const code = c.charCodeAt(0);
  return (code >= 9 && code <= 13) || (code >= 28 && code <= 31) || /^\p{Z}$/u.test(c);
}

function trimWhile(s: string, drop: (c: string) => boolean, start: boolean, end: boolean): string {
  let from = 0;
  let to = s.length;
  if (start) while (from < to && drop(s[from])) from++;
  if (end) while (to > from && drop(s[to - 1])) to--;
  return s.substring(from, to);
}

const trim = (s: string) => trimWhile(s, isWhitespace, true, true);
const isBlank = (s: string) => trim(s) === "";

function genderIndex(gender: string): number {
  return gender === "MALE" ? 0 : gender === "FEMALE" ? 1 : 2;
}

/** Character level case mapping of Java: one code point in, one code point out. */
function upper(c: string): string {
  const u = c.toUpperCase();
  return [...u].length === 1 ? u : c;
}

function lower(c: string): string {
  if (c === "İ") return "i";
  const l = c.toLowerCase();
  return [...l].length === 1 ? l : c;
}

/** Fixes names typed in capitals only: "DUPONT" becomes "Dupont". Names with any lowercase letter are kept. */
export function displayName(raw: string): string {
  const name = trim(raw);
  if (/\p{Lowercase}/u.test(name)) return name;
  let out = "";
  let startOfWord = true;
  for (const c of name) {
    out += startOfWord ? upper(c) : lower(c);
    startOfWord = c === " " || c === "-" || c === "'";
  }
  return out;
}

const SEPARATORS = ":;,.-–—";

/** Returns null when there is nothing to certify. */
export function assemble(
  template: CertificateTemplate,
  association: string,
  person: CertPerson,
  types: CertJobType[],
  jobs: CertJob[],
  selection: CertSelection,
): CertAssembly | null {
  const wanted = new Set(selection.jobTypes);
  const kept = jobs
    .filter((j) => j.day >= selection.fromDay && j.day <= selection.toDay && j.day <= selection.todayDay && wanted.has(j.jobTypeName))
    .sort((a, b) => a.day - b.day);
  if (kept.length === 0) return null;

  const language = template.language;
  const byName = new Map<string, CertJobType>();
  for (const t of types) if (!byName.has(t.name)) byName.set(t.name, t);
  const typeOf = (job: CertJob): CertJobType =>
    byName.get(job.jobTypeName) ?? { name: job.jobTypeName, category: "OTHER", description: "" };

  const gIndex = genderIndex(person.gender);
  const first = kept[0].day;
  const last = kept[kept.length - 1].day;
  const global = new Map<string, string>([
    ["firstName", displayName(person.firstName)],
    ["lastName", displayName(person.lastName)],
    ["association", association],
    ["place", trim(template.place)],
    ["today", longDate(selection.todayDay, language)],
    ["period", period(first, last, language)],
    ["start", monthYear(first, language)],
    ["end", monthYear(last, language)],
    ["date", kept.length === 1 ? longDate(first, language) : ""],
  ]);

  function resolve(paragraph: CertParagraph, vars: Map<string, string>): ResolvedParagraph | null {
    const runs: ResolvedRun[] = paragraph.runs
      .map((run) => {
        let text: string;
        if (run.var !== "") text = vars.get(run.var) ?? "";
        else if (run.gender.length > 0) text = run.gender[gIndex] ?? "";
        else text = run.text;
        return { text, b: run.b, i: run.i, u: run.u };
      })
      .filter((r) => r.text !== "");
    if (runs.every((r) => isBlank(r.text))) return null;
    const merged: ResolvedRun[] = [];
    for (const run of runs) {
      const prev = merged[merged.length - 1];
      if (prev && prev.b === run.b && prev.i === run.i && prev.u === run.u) {
        merged[merged.length - 1] = { ...prev, text: prev.text + run.text };
      } else {
        merged.push(run);
      }
    }
    return { align: paragraph.align, size: paragraph.size, bullet: paragraph.bullet, runs: merged };
  }

  const resolveAll = (paragraphs: CertParagraph[], vars: Map<string, string>) =>
    paragraphs.flatMap((p) => resolve(p, vars) ?? []);

  const block = (key: string, variant: (b: CertBlock) => CertParagraph[], vars: Map<string, string>) => {
    const b = template.blocks[key];
    return b ? resolveAll(variant(b), vars) : [];
  };

  /** A paragraph made only of the {roles} variable. */
  const isRolesParagraph = (p: CertParagraph) =>
    p.runs.some((r) => r.var === "roles") &&
    p.runs.every((r) => r.var === "roles" || (r.var === "" && r.gender.length === 0 && isBlank(r.text)));

  /** Drops the description and the separator written just before it. */
  function withoutDescription(p: CertParagraph): CertParagraph {
    const runs = [...p.runs];
    for (let i = runs.length - 1; i >= 0; i--) {
      if (runs[i].var === "description") {
        runs.splice(i, 1);
        const before = runs[i - 1];
        if (before && before.var === "" && before.gender.length === 0) {
          runs[i - 1] = { ...before, text: trimWhile(before.text, (c) => isWhitespace(c) || SEPARATORS.includes(c), false, true) };
        }
      }
    }
    return { ...p, runs };
  }

  const body: ResolvedParagraph[] = [];
  body.push(...block("header", (b) => b.one, global));
  body.push(...block("title", (b) => b.one, global));
  body.push(...block("intro", (b) => (kept.length === 1 ? b.one : b.many), global));

  for (const category of CATEGORIES) {
    const ofCategory = kept.filter((j) => typeOf(j).category === category);
    if (ofCategory.length === 0) continue;

    // Mandates and the graphic identity read as a period: no count, and for mandates no date either.
    const local = (group: CertJob[], withType: CertJobType | null): Map<string, string> => {
      const vars = new Map(global);
      if (category !== "MANDATE" && category !== "GRAPHIC_ASSOCIATION") vars.set("count", String(group.length));
      vars.set("period", period(group[0].day, group[group.length - 1].day, language));
      if (category === "MANDATE") vars.set("date", "");
      else if (group.length === 1) vars.set("date", longDate(group[0].day, language));
      if (withType) {
        vars.set("role", withType.name);
        vars.set("description", withType.description);
      }
      return vars;
    };

    const byType = new Map<string, CertJob[]>();
    for (const job of ofCategory) {
      const group = byType.get(job.jobTypeName);
      if (group) group.push(job);
      else byType.set(job.jobTypeName, [job]);
    }

    switch (category) {
      case "MANDATE":
        for (const group of byType.values()) body.push(...block(category, (b) => b.one, local(group, typeOf(group[0]))));
        break;
      case "GRAPHIC_ASSOCIATION":
        body.push(...block(category, (b) => b.one, local(ofCategory, null)));
        break;
      case "SHIFT":
      case "OTHER":
        if (ofCategory.length === 1) {
          body.push(...block(category, (b) => b.one, local(ofCategory, typeOf(ofCategory[0]))));
        } else if (byType.size === 1) {
          body.push(...block(category, (b) => b.manyOneRole, local(ofCategory, typeOf(ofCategory[0]))));
        } else {
          const vars = local(ofCategory, null);
          const lineParagraphs = template.blocks[category]?.line ?? [];
          const lines = [...byType.values()]
            // Name ties use String order (UTF-16 code units, no locale, no case folding).
            .sort((a, b) => b.length - a.length || (a[0].jobTypeName < b[0].jobTypeName ? -1 : a[0].jobTypeName > b[0].jobTypeName ? 1 : 0))
            .flatMap((group) => {
              const type = typeOf(group[0]);
              const lineVars = local(group, type);
              return lineParagraphs.flatMap((p) => resolve(isBlank(type.description) ? withoutDescription(p) : p, lineVars) ?? []);
            });
          for (const p of template.blocks[category]?.many ?? []) {
            if (isRolesParagraph(p)) body.push(...lines);
            else {
              const r = resolve(p, vars);
              if (r) body.push(r);
            }
          }
        }
        break;
      default:
        body.push(...block(category, (b) => (ofCategory.length === 1 ? b.one : b.many), local(ofCategory, null)));
    }
  }

  body.push(...block("closing", (b) => b.one, global));
  if (!isBlank(template.place)) body.push(...block("dateLine", (b) => b.one, global));

  return { body, footer: block("footer", (b) => b.one, global) };
}
