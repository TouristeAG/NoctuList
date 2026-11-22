// Volunteer certificate request: the pure parts (grouping of job types, default period and
// selection, request fields). No Firebase and no DOM here, so they can be unit tested in Node.

import { isWaiting } from "./portal-common.js?v=guest-3";

/** Categories in the order of the certificate, as published by the devices. */
export const CATEGORIES = ["MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT", "GRAPHIC_ASSOCIATION", "OTHER", "MEETING"];

const byName = (a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);

/** The request rules refuse job type names over 80 characters, so such types stay out of the sheet. */
export const MAX_TYPE_NAME = 80;

function listOf(types) {
  return Array.isArray(types) ? types.filter((x) => x && typeof x.name === "string" && x.name.length <= MAX_TYPE_NAME) : [];
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

/** A last name that is only an initial (fewer than 2 letters, or ending with a dot) cannot go on a certificate. */
export function needsFullName(lastName) {
  const name = String(lastName ?? "").trim();
  return name.replace(/[^\p{L}]/gu, "").length < 2 || name.endsWith(".");
}

/**
 * Fields of the PROFILE request sent from the name step (all three already cleaned), or null when
 * one is missing. The phone comes from the page, or from the field shown when the page has none.
 */
export function profileFields(firstName, lastName, phone) {
  return firstName && lastName && phone && !needsFullName(lastName) ? { firstName, lastName, phone } : null;
}

const PERIOD_DATE_RE = /^20\d{2}-\d{2}-\d{2}$/;

/** Both dates are YYYY-MM-DD from 2000 on, from is not after to, and to is not after today. */
export function periodValid(from, to, todayIso) {
  return PERIOD_DATE_RE.test(String(from)) && PERIOD_DATE_RE.test(String(to)) && from <= to && to <= todayIso;
}

/** Fields of the CERTIFICATE_EMAIL request. */
export function requestFields(from, to, selection) {
  return { from, to, jobTypes: [...selection].sort() };
}

/**
 * What the volunteer sees of the CERTIFICATE_EMAIL request:
 * "none", "waiting" (sent, the server has not looked at it yet), "review" (the team has to
 * validate it, or it was validated and is being sent), "sent", "refused" (the team said no) or
 * "failed" (rejected by the server, or stuck before the review). Internal reasons such as
 * SEND_FAILED after a first attempt stay hidden: the volunteer only sees "review".
 */
export function certificateState(request, now = Date.now()) {
  const status = request?.status;
  if (!status) return "none";
  if (status === "DONE") return "sent";
  if (status === "REJECTED") return request.reason === "REFUSED" ? "refused" : "failed";
  if (status === "PENDING_REVIEW" || status === "APPROVED") return "review";
  if (status === "PROCESSING" && (request.reviewAt || request.approvalId)) return "review";
  if (status === "PENDING" || status === "PROCESSING") return isWaiting({ type: "CERTIFICATE_EMAIL", ...request }, now) ? "waiting" : "failed";
  return "none";
}

/** The note of a refusal, as plain text (empty when there is none). */
export function refusalNote(request) {
  return typeof request?.note === "string" ? request.note.trim() : "";
}
