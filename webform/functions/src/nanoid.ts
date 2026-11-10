import { randomInt } from "node:crypto";

// Same alphabet and length as NanoIdGenerator.generateVolunteerId (Kotlin).
const ALPHABET = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
const SIZE = 21;

/** A new volunteer id: 21 URL-safe characters from a cryptographic source, without modulo bias. */
export function volunteerId(): string {
  let id = "";
  for (let i = 0; i < SIZE; i++) id += ALPHABET[randomInt(ALPHABET.length)];
  return id;
}
