// Volunteer certificate request: the pure parts (grouping of job types, default period and
// selection, request fields). No Firebase and no DOM here, so they can be unit tested in Node.

/** Categories in the order of the certificate, as published by the devices. */
export const CATEGORIES = ["MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT", "GRAPHIC_ASSOCIATION", "OTHER", "MEETING"];

const byName = (a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);

function listOf(types) {
  return Array.isArray(types) ? types.filter((x) => x && typeof x.name === "string") : [];
}

function daysOf(type) {
  return (Array.isArray(type.days) ? type.days : []).map(Number).filter((d) => Number.isFinite(d)).sort((a, b) => a - b);
}

/** Job types by category (enum order, an unknown category counts as OTHER), types by name. */
export function groupTypes(types) {
  const groups = new Map(CATEGORIES.map((c) => [c, []]));
  for (const type of listOf(types)) {
    const days = daysOf(type);
    const category = groups.has(type.category) ? type.category : "OTHER";
    groups.get(category).push({ name: type.name, firstDay: days[0] ?? 0, lastDay: days[days.length - 1] ?? 0, days });
  }
  return CATEGORIES.filter((c) => groups.get(c).length).map((c) => ({ category: c, types: groups.get(c).sort(byName) }));
}

/** Everything but the meetings. */
export function defaultSelection(types) {
  return new Set(listOf(types).filter((x) => x.category !== "MEETING").map((x) => x.name));
}

const isoOf = (day) => {
  const s = String(day).padStart(8, "0");
  return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
};
const dayOf = (iso) => Number(String(iso).replace(/-/g, ""));

/** From the first day of any type to today (YYYY-MM-DD). */
export function defaultPeriod(types, todayIso) {
  const firsts = listOf(types).flatMap(daysOf).sort((a, b) => a - b);
  return { from: firsts.length ? isoOf(firsts[0]) : todayIso, to: todayIso };
}

/** Names of the types with at least one day between from and to (both included). */
export function typesInPeriod(types, from, to) {
  const lo = dayOf(from);
  const hi = dayOf(to);
  return new Set(listOf(types).filter((x) => daysOf(x).some((d) => d >= lo && d <= hi)).map((x) => x.name));
}

/** At least one selected type has a day in the period. */
export function canSend(selection, inPeriod) {
  return [...selection].some((name) => inPeriod.has(name));
}

/** A last name that is only an initial (or too short) cannot go on a certificate. */
export function needsFullName(lastName) {
  return String(lastName ?? "").replace(/[^\p{L}]/gu, "").length < 3;
}

/** Fields of the CERTIFICATE_EMAIL request. */
export function requestFields(from, to, selection) {
  return { from, to, jobTypes: [...selection].sort() };
}
