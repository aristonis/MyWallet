# MyWallet

An offline personal finance tracker for Android. You record your income, spending and transfers
by hand, across as many accounts and currencies as you use, and the app shows where the money went.

There is no sign-up and no server. The app does not ask for network access, so your records stay on
your phone unless you export a backup yourself.

## What it does

- Accounts of any kind (cash, card, bank, savings), each in its own currency with an opening
  balance. Archive the ones you no longer use.
- Income, expenses and transfers, including transfers between currencies. Edit or delete any entry
  and undo a delete.
- One base currency for every total. You set the exchange rates yourself, either in Settings or
  right on the transaction form, which also shows what an amount is worth in your base currency.
- Income and expense categories with sub-categories.
- Tracking by day, week, month, year, all time or any date range, with income, spending, net and a
  breakdown by category and sub-category.
- The transaction history filters by the same dates.
- Backup of everything to one JSON file, and restore from it.
- Light, dark or system theme, right-to-left layouts and large text.

## Install

Download the APK from the [releases page](https://github.com/aristonis/MyWallet/releases) and open
it on your phone. You may need to allow installs from your browser or file manager first. The app
needs Android 9 or newer.

A new version installs over the old one and keeps your data. Export a backup from Settings before
you update anyway.

## Build from source

You need JDK 17 and the Android SDK (compile SDK 37).

```sh
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. To run the tests:

```sh
# JVM tests for all three modules
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest

# Device tests (needs a running emulator or a connected phone)
./gradlew :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

The code is split into three Gradle modules. `domain` is plain Kotlin with the model and the
business rules, and depends on nothing Android. `data` holds the Room database, the backup format
and the money formatting. `app` is the Jetpack Compose UI with its view models and Hilt wiring.

## How it was built

AI coding agents helped write the code. The author planned and reviewed each change, and each one
passed the automated tests before it went into a release.

## Release notes

[CHANGELOG.md](CHANGELOG.md) covers the latest release. The full notes for each version are in
[docs/releases](docs/releases).

## License

MIT. See [LICENSE](LICENSE).
