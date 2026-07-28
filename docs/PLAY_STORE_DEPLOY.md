# Play Store Deployment Guide

How to set up automated Google Play Store releases from CI.

## Overview

Every push to `master` with a new `versionCode` triggers:
1. Build signed AAB
2. Upload to Google Play `internal` track
3. Create GitHub release with APKs

---

## Secrets (GitHub → Settings → Secrets and variables → Actions)

| # | Secret name | Value | Where to get it |
|---|---|---|---|
| 1 | `PLAY_STORE_SERVICE_ACCOUNT_JSON` | GCP service account JSON | [GCP Console](https://console.cloud.google.com) → IAM → Service Accounts → Create Key (JSON) |
| 2 | `ANDROID_KEYSTORE_BASE64` | Base64-encoded keystore | `base64 -w0 upload-keystore.jks` |
| 3 | `ANDROID_KEYSTORE_PASSWORD` | Keystore password | Set when creating the keystore |
| 4 | `ANDROID_KEY_ALIAS` | Key alias | `upload` |
| 5 | `ANDROID_KEY_PASSWORD` | Key password | Set when creating the keystore (same as keystore password) |

---

## One-time setup

### 1. Google Cloud Service Account

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Navigate to **IAM & Admin** → **Service Accounts** (select correct project)
3. Click **Create Service Account**
   - Name: `play-deploy` (or anything)
   - Role: Project → Owner (temporary, remove after setup)
4. Click the new account → **Keys** → **Add Key** → **Create new key** → JSON
5. Save the downloaded JSON → contents → GitHub secret `PLAY_STORE_SERVICE_ACCOUNT_JSON`
6. Enable the [Google Play Android Developer API](https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com) in the GCP project
7. Go to [Play Console](https://play.google.com/console) → **Users and permissions** → **Invite new users**
   - Paste the service account email (from the JSON, `client_email` field)
   - Grant **Release to production** (or at minimum testing tracks)
8. Back in GCP → **IAM** → Remove the Owner role from the service account

### 2. Upload Keystore

Generate or locate your upload key:

```bash
# Generate new keystore:
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias upload \
  -storepass <PASSWORD> -keypass <PASSWORD> \
  -dname "CN=YourApp, OU=Dev, O=YourOrg, L=City, ST=State, C=NO"

# Export certificate for Play Console:
keytool -exportcert \
  -keystore upload-keystore.jks \
  -alias upload \
  -storepass <PASSWORD> \
  -file upload_cert.pem

# Get base64 for GitHub secret:
base64 -w0 upload-keystore.jks
```

Add to GitHub secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Output of `base64 -w0 upload-keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | `<PASSWORD>` |
| `ANDROID_KEY_ALIAS` | `upload` |
| `ANDROID_KEY_PASSWORD` | `<PASSWORD>` |

### 3. Register Upload Key in Play Console

1. Go to [Play Console](https://play.google.com/console) → Your app → **Protected with Play** (shield icon) → **Key management**
2. Under **"Upload key certificate"**, click **"Request upload key reset"**
3. Upload `upload_cert.pem`
4. Wait for confirmation (~48 hours for released apps, ~minutes for new/unreleased apps)

**Current upload key (as of Jul 2026):**

```
SHA1:  FB:8E:50:99:CA:72:6B:BD:A5:45:48:25:DF:F2:7C:EA:0F:6B:0A:11
SHA256: 2E:37:2A:AF:F9:F3:06:5D:80:3D:DC:D9:4C:82:5E:14:2F:34:10:4B:61:95:A1:00:FD:F6:01:83:42:93:8C:A9
```

### 4. First Manual Upload

The first release of any app **must** be uploaded manually through the Play Console web UI.
After that, all subsequent uploads can be done through CI.

```bash
./gradlew bundleRelease
# Upload app/build/outputs/bundle/release/app-release.aab in Play Console
```

---

## How to release

### Each release:

1. Bump `versionCode` in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 99  // was 98
   versionName = "1.0.99"
   ```
2. `git commit -m "release: v1.0.99"`
3. `git push origin master`
4. CI automatically:
   - Builds + tests
   - Signs AAB with upload key
   - Uploads to Play Store `internal` track
   - Creates GitHub release tagged `v1.0.99`

### Promote from internal → production:

1. Go to [Play Console](https://play.google.com/console) → your app → **Release**
2. Select the `internal` track build → **Promote** → choose beta/production
3. Fill in release notes → **Submit for review**

---

## File locations

| File | Path | Purpose |
|---|---|---|
| Keystore (local) | `~/GuessTheSongYear/upload-keystore.jks` | Upload signing key (do NOT commit) |
| PEM cert | `~/GuessTheSongYear/upload_cert.pem` | Public certificate for Play Console |
| CI workflow | `.github/workflows/build-apk.yml` | Build → sign → upload to Play Store |
| Build config | `app/build.gradle.kts` | `versionCode`, signing config |

---

## Troubleshooting

### CI fails with "Upload key mismatch"
The upload key hasn't been updated in Play Console yet. Go to the key management page and check if the new SHA1 fingerprint matches.

### "Package not found"
The app hasn't been created in Play Console, or the service account doesn't have permissions. Upload one AAB manually first.

### "Precondition check failed"
You may be trying to upload to `production` track directly. Start with `internal` track, then promote via the Console UI.

### "No signing config"
The `ANDROID_KEYSTORE_BASE64` env var isn't set. Verify the GitHub secret is present and properly base64-encoded.