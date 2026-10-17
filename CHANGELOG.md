# Changelog

This file lists the changes in the latest release. Earlier releases, and the full notes for every
version, live in [`docs/releases/`](docs/releases/): one file per minor version (`v1_0.md`,
`v1_1.md`, ...), with each patch release grouped inside its minor version's file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [1.3.0] - 2026-10-17

Full notes: [docs/releases/v1_3.md](docs/releases/v1_3.md)

### Added
- Income fees, with fixed or percentage amounts and a preview of what reaches the account after
  fees and deductions.
- Paid-later expenses: record what you paid now and what you owe, with a person and optional due
  date.
- Multiple type, account and category selections in transaction filters.
- Arabic interface, Arabic and Persian digit input, and right-to-left text handling. Money remains
  displayed with Latin digits.
- A GitHub Star link and attribution in About.

### Changed
- Full-screen loading uses the Aristonis brand loader, and startup shows one theme-aware splash
  with the MyWallet logo and copyright.
- Category filters show matching parts of entries, including income deductions. A saved entry
  outside the active filter offers a Show action.

### Fixed
- Deductions can use subcategories and keep their category links when saved and restored.
- Income titles stay readable beside deduction details.

### Notes
- The database schema is unchanged from 1.2.0. Export a backup from Settings before updating.
- Backups from 1.2 restore in 1.3. New backups use format 3 and cannot be restored in 1.2.

[1.3.0]: https://github.com/aristonis/MyWallet/compare/v1.2.0...v1.3.0
