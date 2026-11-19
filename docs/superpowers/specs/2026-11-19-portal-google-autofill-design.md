# Automatic Google accounts and portal access for new volunteers

Date: 2026-11-19. Branch `feature/portal-google-autofill` (from `main`).

## Why

The association gives every volunteer a Google Workspace address built the same way
(`prénom.nom@asso.ch`, spaces turned into `-`). Today the admin types that address by hand on
each profile, and opens portal access one volunteer at a time. The owner wants both to happen on
their own for new volunteers.

## Goals

1. A team setting: turn on automatic Google accounts and write the rule that builds the address.
   When on, every portal access opened from now on gets its Google account linked directly
   (no confirmation step).
2. A team setting: open portal access automatically for every new volunteer, whether added by hand
   in the app or registered through the online signup link.

## Non-goals

- Creating the account in Google Workspace. The app only links the address; the account has to
  exist in the Google admin console. Until the volunteer signs in once, the link shows "pending"
  as it does today.
- Volunteers who already exist. Nothing is applied backwards.
- Volunteers that arrive by sync (Firestore, Google Sheets) or Drive import. Only the add dialog
  and the signup link count as "new".

## Anti-goals

- A wrong address replacing one the admin or the volunteer set. The rule only fills an empty
  Google account.
- A silent failure that looks like success: if the rule cannot produce a usable address, the
  field stays empty and the profile shows it as today, so the admin can fill it in.

## Approaches considered

- **A. Template with placeholders (chosen by the owner).** One text field such as
  `{prénom}.{nom}@asso.ch` with a live preview. Covers any convention.
- B. Fixed presets (`prénom.nom`, `p.nom`, ...) plus a domain picker. Simpler, but associations
  with another convention are stuck.
- C. Presets that fill the template. Most comfortable, more UI for a setting set once.

## Design

### Settings (team-wide, synced like the other portal settings)

New `InstitutionSettingsKeys`, stored in `SettingsManager` and exported in the institution
settings map like `VOLUNTEER_PORTAL_GOOGLE_*`:

- `VOLUNTEER_PORTAL_AUTO_ACCESS` (bool, default false): "Open portal access for every new volunteer".
  Shown in the portal options of the Online settings page.
- `VOLUNTEER_PORTAL_GOOGLE_AUTO` (bool, default false) and `VOLUNTEER_PORTAL_GOOGLE_TEMPLATE`
  (string, default empty). Shown under the existing Google sign-in options, only when Google
  sign-in is on. The template field shows a live preview on a sample name
  ("Jean Pierre D'Amico" → `jean-pierre.damico@asso.ch`) and an error when the template is
  invalid or, in "internal accounts only" mode, its domain is not one of the verified domains.

### Template rules (pure function, unit tested)

- Placeholders: `{prénom}`, `{nom}`, `{p}` (first letter of first name), `{n}` (first letter of
  last name). Also accept `{prenom}` without accent. Placeholders are case-insensitive.
- First name = `Volunteer.name`, last name = `Volunteer.lastNameAbbreviation`.
- Each value is cleaned: trimmed, lowercased, accents removed (é→e, ü→u, ß→ss, æ→ae, ø→o...),
  apostrophes removed, runs of spaces and underscores turned into one `-`, any other character
  outside `[a-z0-9.-]` removed, leading/trailing `-` dropped.
- The result must contain exactly one `@`, a non-empty local part and the template's domain, and
  pass `PortalInputValidator.cleanEmail`. Otherwise no address (null).
- A template is valid if rendering the sample name gives an address.

### When the rule applies

After any portal access is opened, if Google sign-in is on, automatic Google accounts are on,
the volunteer has no `portalGoogleEmail` yet, and the rule gives an address, the service calls
the existing `assignGoogleAccount(volunteer, address)`. Its checks stay in force: domain allowed,
not the same as the roster e-mail, not already used by another volunteer. Any error code is
logged and the field stays empty (homonyms are filled in by hand).

Access paths covered:

- "Create access" button on a profile (`grantAccess`, Created or ExistingAccount).
- Add volunteer dialog with portal access (`addVolunteerWithPortalAccess`).
- Signup link (`processSignup`), both for a new profile and when it links an existing profile
  with the same e-mail.

The password result dialog and its "choose your password" mail are unchanged.

### Automatic portal access for new volunteers

- Add volunteer dialog: when the setting is on, the "Create access" switch is hidden and access is
  always opened if the volunteer has an e-mail. Without an e-mail the volunteer is saved without
  access, as today with the switch off. When the setting is off the switch stays as it is now.
- Signup link: volunteers already get their own access. No change besides the Google rule above.

## Left to the builder

- Exact copy (French and English), following the no-AI-sounding-copy and no-redundant-subtitles
  rules.
- Where the template helper lives (suggested: `portal/PortalGoogleAddressTemplate.kt`).
- Tests: template cleaning and validation, service applying the rule on grant and on signup,
  respecting an existing Google address, and swallowing TAKEN/DOMAIN errors.
