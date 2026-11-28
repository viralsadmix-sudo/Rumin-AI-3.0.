# Rumin AI permanent Android release signing

The Android app must always be signed with the same private release key for in-place updates. Never commit the keystore or paste its passwords into chat.

## Create the key on Windows

Open PowerShell in a private folder and run:

```powershell
keytool -genkeypair -v -keystore rumin-release.jks -alias rumin-release -keyalg RSA -keysize 4096 -validity 10000
```

Choose strong passwords and keep the keystore plus passwords in at least two secure backups. Losing this key means future APKs cannot update installations signed with it.

Create the Base64 value and copy it directly to the clipboard (do not print it in a shared terminal or chat):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\rumin-release.jks")) | Set-Clipboard
```

## Add GitHub Actions secrets

Open the repository on GitHub, then **Settings → Secrets and variables → Actions → New repository secret**. Add these four secrets:

- `RUMIN_KEYSTORE_BASE64`: paste the Base64 value copied above
- `RUMIN_KEYSTORE_PASSWORD`: keystore password
- `RUMIN_KEY_ALIAS`: `rumin-release`
- `RUMIN_KEY_PASSWORD`: key password

Do not add these as normal repository variables. Do not commit `rumin-release.jks`.

The workflow now refuses to publish a main-branch release unless all four secrets are present. Feature-branch builds may still use the ephemeral debug key for development artifacts; those are not official update APKs.

## First-install migration warning

Android only allows an in-place update when the package ID and signing certificate match and the new version code is higher. If the currently installed Rumin APK was signed by a different temporary CI key, it will need one final uninstall/reinstall migration before updates can work. Back up any app data first; the current manifest has app backup disabled. Once the permanent key is used for the installed build, later releases can update over the top without uninstalling.

## In-app updater

The Settings page has an update check and download/install action. It reads the latest GitHub Release APK, checks the package name and version code, downloads it to the app cache, then opens Android's package installer. Android may ask the user to allow installs from Rumin; the user must approve the system installer. Updates are not silently installed.
