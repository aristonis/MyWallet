# Changelog

This file lists the changes in the latest release. Earlier releases, and the full notes for every
version, live in [`docs/releases/`](docs/releases/): one file per minor version (`v1_0.md`,
`v1_1.md`, ...), with each patch release grouped inside its minor version's file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.1.0] - 2026-10-08

Full notes: [docs/releases/v1_1.md](docs/releases/v1_1.md)

### Added
- Tracking shows any day, week, month or year: step with the arrows, or tap the dates to jump
  straight to a month or year.
- A custom date range, picked from the calendar button in the top bar, on Tracking and Transactions.
- Income broken down by category next to spending, and categories that open to show their
  sub-categories.
- The same period and date-range filter on the Transactions list, which still opens on everything.
- A screen showing the current period moves to the new one when the date changes.
- Exchange rates on the transaction form: see and change the rate where you type the amount, see
  the amount's value in your base currency, and for a transfer between two currencies see the rate
  between them and how much arrives.
- Settings switch "Save rates I enter on transactions" (on by default).

### Changed
- New launcher icon.
- Reports and the transaction list read only the dates on screen, so long histories load faster.

### Notes
- Upgrading from 1.0 keeps every record; the database updates itself on first launch. Export a
  backup from Settings before updating anyway.
- Known issue: an entry saved with a date outside a narrowed Transactions list stays out of view
  until the list covers that date.

[Unreleased]: https://github.com/aristonis/MyWallet/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/aristonis/MyWallet/compare/v1.0.0...v1.1.0
