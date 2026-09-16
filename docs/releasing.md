# Signed builds and GitHub Actions

[Android builds](../.github/workflows/android.yml) only builds, verifies,
signs and uploads universal ARM64+x86-64 APKs with Mill. The independent
[Tests workflow](../.github/workflows/tests.yml) runs storage unit tests,
compiler contracts, network timeouts, native ASan/UBSan checks and disposable-key
signing tests. It also compiles the Android instrumentation APK and an unsigned
release fixture for signing tests. Neither workflow waits for the other.
Live NFS and emulator tests run separately; see [the verified matrix](testing.md).

Both workflows use the Android SDK/NDK, CMake, Ninja and Clang already installed
on [GitHub's Ubuntu 24.04 runner](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md).
`build.mill` selects the pinned SDK and NDK versions. Dependency downloads are
cached; tests never receive the production signing secrets or publish APKs.

| Trigger | Debug APK | Release APK |
| --- | --- | --- |
| Push or manual run | Signed with a generated debug key | Signed with the configured release key |
| Pull request | Signed with a generated debug key | Compiled and checked; no release artifact is uploaded |

Signing fails when release secrets are missing or invalid; an unsigned release
is never uploaded. Each artifact is a single `.apk` that downloads directly,
using [upload-artifact's `archive: false`](https://github.com/actions/upload-artifact#upload-an-individual-file-unzipped).
SHA-256 checksums appear in the build summary; artifacts are retained for 30
days. The workflow creates Actions artifacts, not GitHub Releases or tags.

## Configure the release key once

Use an existing release key if one already identifies this app. Otherwise,
generate a new key locally, outside the checkout:

```sh
umask 077
mkdir -p "$HOME/.local/share/nfs-saf-signing"
keytool -genkeypair -v -storetype JKS \
  -keystore "$HOME/.local/share/nfs-saf-signing/release.jks" \
  -alias nfs-saf -keyalg RSA -keysize 4096 -validity 10000 \
  -dname 'CN=NFS SAF'
```

`keytool` prompts for passwords. Keep a secure backup of the keystore, alias
and passwords; future APK updates must retain the app's signing identity.
Do not commit them or share them in issue comments. See
[Android app signing](https://developer.android.com/studio/publish/app-signing).

Add these **repository Actions secrets** in
[repository settings](https://github.com/rupansh/NFS-SAF/settings/secrets/actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded bytes of the JKS/PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Alias of the signing key, such as `nfs-saf` |
| `ANDROID_KEY_PASSWORD` | Private-key password; may match the store password |

Alternatively, use the authenticated GitHub CLI. This sends the keystore via
stdin and prompts interactively for passwords, without putting them in shell
history or process arguments:

```sh
base64 < "$HOME/.local/share/nfs-saf-signing/release.jks" | \
  gh secret set ANDROID_KEYSTORE_BASE64 --repo rupansh/NFS-SAF
gh secret set ANDROID_KEY_ALIAS --repo rupansh/NFS-SAF --body nfs-saf
gh secret set ANDROID_KEYSTORE_PASSWORD --repo rupansh/NFS-SAF
gh secret set ANDROID_KEY_PASSWORD --repo rupansh/NFS-SAF
```

If you reused a key, substitute its keystore path and alias. The
[CLI encrypts secrets locally before uploading](https://cli.github.com/manual/gh_secret_set).

## How signing works

Signing secrets are scoped only to the signing step; pull requests receive
none. `scripts/sign-release.py` decodes the
keystore into a private temporary directory outside the workspace, supplies
passwords to `apksigner` through environment references, and removes the
temporary directory on success or ordinary failure. Key material does not
enter Mill's task inputs, dependency caches or artifact paths.

Only a successfully verified, signed APK is copied into the release artifact
directory. Verification checks the signature, ZIP/16 KiB ELF alignment, packaged ABIs
and disabled debuggability. No release key is generated automatically by CI.
Workflow actions are pinned to commit hashes and the job has read-only
repository permissions. Review code before pushing to this repository:
repository pushes can access its signing secrets.

To sign locally, provide the same four environment variables using your secret
manager, set `ANDROID_HOME`, then run:

```sh
scripts/build-apks.sh
python3 scripts/sign-release.py
```

The generated debug key is disposable and may change between CI runners.
Debug builds and release builds share an application ID but use different
certificates, so switching requires uninstalling and loses saved connections.
Before a versioned release, update `androidVersionCode` and `androidVersionName`
in the shared `NfsAppModule` in `build.mill`.
