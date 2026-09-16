#!/usr/bin/env python3
"""Sign the release without putting key material in Mill caches or command arguments."""

import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

SECRETS = (
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)


def credentials() -> bytes:
    missing = [name for name in SECRETS if not os.environ.get(name)]
    if missing:
        raise RuntimeError("Missing required GitHub Actions signing secrets: " + ", ".join(missing))
    try:
        return base64.b64decode("".join(os.environ[SECRETS[0]].split()), validate=True)
    except (ValueError, binascii.Error):
        raise RuntimeError("ANDROID_KEYSTORE_BASE64 is not valid base64") from None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check-config", action="store_true", help="Check required secrets before CI builds"
    )
    args = parser.parse_args()
    key_data = credentials()
    if not key_data:
        raise RuntimeError("The decoded release keystore is empty")
    if args.check_config:
        print("Required release signing secrets are configured")
        return
    root = Path(__file__).resolve().parent.parent
    apk = root / "out/release/androidAlignedUnsignedApk.dest/app.aligned.apk"
    if not apk.is_file():
        raise RuntimeError("Build the release first: ./mill release.androidAlignedUnsignedApk")
    apksigner = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.1.0/apksigner"
    # Keep the keystore outside the workspace, upload paths and dependency caches.
    with tempfile.TemporaryDirectory(
        prefix="nfssaf-sign-", dir=os.environ.get("RUNNER_TEMP")
    ) as temp:
        temp_dir = Path(temp)
        key = temp_dir / "release.keystore"
        with os.fdopen(os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as file:
            file.write(key_data)
        signed = temp_dir / "signed.apk"
        subprocess.run(
            [
                str(apksigner),
                "sign",
                "--ks",
                str(key),
                "--ks-key-alias",
                os.environ["ANDROID_KEY_ALIAS"],
                "--ks-pass",
                "env:ANDROID_KEYSTORE_PASSWORD",
                "--key-pass",
                "env:ANDROID_KEY_PASSWORD",
                "--v4-signing-enabled",
                "false",
                "--out",
                str(signed),
                str(apk),
            ],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            [
                sys.executable,
                str(root / "scripts/verify-apks.py"),
                "--release",
                str(signed),
                "--signed-release",
            ],
            check=True,
            capture_output=True,
        )
        dest = root / "out/artifacts/release"
        dest.mkdir(parents=True, exist_ok=True)
        output = dest / "nfs-saf-release-universal.apk"
        shutil.copyfile(signed, output)
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        (dest / "SHA256SUMS").write_text(f"{digest}  {output.name}\n")
    print("Signed and verified: out/artifacts/release/nfs-saf-release-universal.apk")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError:
        # Do not dump argv, child logs or environment containing signing inputs.
        sys.exit(
            "Release signing/verification failed. Check the keystore, alias and passwords; no release was published."
        )
    except (RuntimeError, KeyError, OSError) as error:
        sys.exit(str(error))
