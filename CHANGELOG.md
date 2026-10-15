# Changelog

This file lists the changes in the latest release. Earlier releases, and the full notes for every
version, live in [`docs/releases/`](docs/releases/): one file per minor version (`v1_0.md`,
`v1_1.md`, ...), with each patch release grouped inside its minor version's file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [1.2.0] - 2026-10-15

Full notes: [docs/releases/v1_2.md](docs/releases/v1_2.md)

### Added
- Debts and loans: lend, borrow, repay with interest, write off, and five debt kinds for debts you
  already had and things bought or earned on credit. Each debt has its own page and history.
- Home shows what is in your pocket next to your balance, with what you are owed and what you owe.
- Your own deductions (tax, family share, bills) filled in on each income by its category, with the
  amount you receive shown before saving.
- Credit card accounts with a limit, owed and available amounts.
- Transfer fees, with a default fee per account, and refunds that lower spending.
- Spending breakdown and net worth charts in Tracking, and CSV export of the period on screen.
- Account pages and read-only entry pages.
- About, Privacy, and Support and FAQ pages in Settings.

### Changed
- New look: colour palette, cards, rows, navigation bar and screen transitions.
- Entries in another currency keep the rate of their day, so past totals no longer move with
  today's rate.
- The app is licensed under Apache 2.0.

### Fixed
- Screens stay readable at 200% text size, and the last row of each list stays clear of the add
  button.
- The system bars follow the app theme; TalkBack announces section titles as headings.
- Percent fees above 100 are refused, and a one-account transfer explains what is needed.
- Interrupted CSV saves are reported, and backups are checked before they are written.

### Notes
- Database change: 1.2.0 updates the database on first launch and keeps every record. Export a
  backup from Settings before updating.
- Backups from 1.1 restore in 1.2; a 1.2 backup cannot be restored in 1.1.

[1.2.0]: https://github.com/aristonis/MyWallet/compare/v1.1.1...v1.2.0
