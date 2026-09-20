# Google Drive shift import

Admins on a **Firebase** organization can create many shifts at once from a Google Doc or Google Sheet (planning documents, meeting minutes). The feature is **off by default** and **Desktop-only** in this release.

It uses the **signed-in user's** Google account — not the Sheets service account. The service account cannot see personal Drive files.

## What the user sees

1. Settings → Firebase → **Import shifts from Google documents** (org admin).
2. Sign out and sign in again so Google can grant the extra scopes.
3. Admin → Shifts → Add a shift → **Import shifts from Google Drive**.
4. Pick a recent Doc/Sheet, or paste a document URL.
5. Review the matched volunteers, drop visitors, then import.

## Cloud Console (per institution)

Each organization uses its own Web OAuth client. Before turning the setting on, the institution's Google Cloud project must:

1. Enable these APIs: **Google Drive**, **Google Docs**, **Google Sheets**, **People**.
2. Add these scopes to the OAuth consent screen:
   - `https://www.googleapis.com/auth/documents.readonly` (sensitive)
   - `https://www.googleapis.com/auth/spreadsheets.readonly` (sensitive)
   - `https://www.googleapis.com/auth/contacts.readonly` (sensitive)
   - `https://www.googleapis.com/auth/drive.metadata.readonly` (restricted — recent-files list only)
3. Keep the existing localhost redirect URIs (`http://localhost:8889/Callback`, `8888`, `8765`, `9090`).

If an institution has not enabled those APIs, Google Sign-In still works: NoctuList retries with identity-only scopes and shows a warning in Settings.

## Scope sensitivity

| Scope | Google class | Used for |
|-------|----------------|----------|
| `documents.readonly` | Sensitive | Read a Doc by URL or after picking it |
| `spreadsheets.readonly` | Sensitive | Read a Sheet the same way |
| `contacts.readonly` | Sensitive | Phone / birthday / split name from Contacts (better matching) |
| `drive.metadata.readonly` | Restricted | List recently modified Docs and Sheets |

Restricted scopes need a third-party security assessment only when the OAuth consent screen is **External** and published. **Internal** (Workspace) and **Testing** consent screens skip that review.

The recent-files tab disappears when `drive.metadata.readonly` is missing. URL paste still works with the Docs/Sheets scopes.

## How matching works

- Smart chips (`@` contact chips) supply an email (Docs also supply a display name).
- Plain `@Name` text is collected when the name is capitalised.
- Contacts are enriched (best-effort) via the People API.
- Each mention is scored against the volunteer roster: unique email/phone first, then birthday, full name, abbreviation, and weaker name overlap. Duplicate emails in the roster stop being decisive.

## Android

The import UI is wired, but Android Firebase Sign-In does not yet keep a refreshable Google credential. The entry point stays hidden until that lands.
