# Changelog

This file lists the changes in the latest release. Earlier releases, and the full notes for every
version, live in [`docs/releases/`](docs/releases/): one file per minor version (`v1_0.md`,
`v1_1.md`, ...), with each patch release grouped inside its minor version's file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [1.1.1] - 2026-10-08

Full notes: [docs/releases/v1_1.md](docs/releases/v1_1.md)

### Fixed
- With a long history, saving an entry or opening the Transactions list no longer freezes the
  screen. Transactions, Tracking and Home do their work in the background.
- An entry saved with a date outside a narrowed Transactions list now gets a message, with a Show
  button that moves the list to its date. This was the known issue in 1.1.0.
- Home no longer flashes "needs an account" while it is still loading.
- The blank strip above the bottom navigation bar is gone.
- Data the app cannot read shows a message on Home and the Transactions list instead of closing the
  app.
- Restoring a backup that holds entries this version cannot read is refused, and your current data
  stays as it was.

### Notes
- No database change: 1.1.1 installs over 1.1.0 and keeps every record. Export a backup from
  Settings before updating anyway.

[1.1.1]: https://github.com/aristonis/MyWallet/compare/v1.1.0...v1.1.1
