import { randomUUID } from "node:crypto";

export interface ReminderInput {
  title: string;
  dueDate: string; // ISO date, YYYY-MM-DD
  description?: string;
}

function escapeText(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,")
    .replace(/\n/g, "\\n");
}

function toBasicDate(isoDate: string): string {
  return isoDate.replace(/-/g, "");
}

function addOneDay(isoDate: string): string {
  const date = new Date(isoDate + "T00:00:00Z");
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

function nowStamp(): string {
  return new Date().toISOString().replace(/[-:]/g, "").split(".")[0] + "Z";
}

/** Builds a single-VEVENT, all-day .ics file by hand (RFC 5545) — no external lib. */
export function buildIcs(input: ReminderInput): { uid: string; ics: string } {
  const uid = randomUUID();
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Cronos//Aegis4j Showcase//PT",
    "CALSCALE:GREGORIAN",
    "BEGIN:VEVENT",
    `UID:${uid}@cronos.local`,
    `DTSTAMP:${nowStamp()}`,
    `DTSTART;VALUE=DATE:${toBasicDate(input.dueDate)}`,
    `DTEND;VALUE=DATE:${toBasicDate(addOneDay(input.dueDate))}`,
    `SUMMARY:${escapeText(input.title)}`,
  ];
  if (input.description) {
    lines.push(`DESCRIPTION:${escapeText(input.description)}`);
  }
  lines.push("END:VEVENT", "END:VCALENDAR");

  return { uid, ics: lines.join("\r\n") + "\r\n" };
}
