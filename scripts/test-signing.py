#!/usr/bin/env python3
"""Exercise APK signing with a disposable key and an isolated output directory."""

import base64
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SECRET_NAMES = (
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)


def main() -> None:
    source = ROOT / "out/release/androidAlignedUnsignedApk.dest/app.aligned.apk"
    if not source.is_file():
        sys.exit("Build the APKs with scripts/build-apks.sh before testing signing")
    original_digest = hashlib.sha256(source.read_bytes()).digest()
    env = {name: value for name, value in os.environ.items() if name not in SECRET_NAMES}
    with tempfile.TemporaryDirectory(prefix="nfssaf-sign-test-") as temporary:
        work = Path(temporary)
        scripts = work / "scripts"
        scripts.mkdir()
        for name in ("sign-release.py", "verify-apks.py"):
            shutil.copyfile(ROOT / "scripts" / name, scripts / name)
        unsigned = work / source.relative_to(ROOT)
        unsigned.parent.mkdir(parents=True)
        unsigned.symlink_to(source)
        runtime = work / "runtime"
        runtime.mkdir()
        env["RUNNER_TEMP"] = str(runtime)
        output = work / "out/artifacts/release/nfs-saf-release-universal.apk"

        def invoke(values: dict[str, str], *, success: bool, check_config: bool = False) -> str:
            command = [sys.executable, str(scripts / "sign-release.py")]
            if check_config:
                command.append("--check-config")
            result = subprocess.run(command, env=env | values, capture_output=True, text=True)
            assert (result.returncode == 0) == success, "Unexpected signing exit status"
            assert not list(runtime.iterdir()), "Temporary signing material was not cleaned up"
            assert output.exists() == (success and not check_config), "Unexpected release artifact"
            log = result.stdout + result.stderr
            for name in (SECRET_NAMES[0], SECRET_NAMES[1], SECRET_NAMES[3]):
                value = values.get(name, "")
                if len(value) > 8:
                    assert value not in log, "Signing input was exposed in output"
            return log

        log = invoke({}, success=False, check_config=True)
        assert all(name in log for name in SECRET_NAMES)
        invoke({"ANDROID_KEYSTORE_BASE64": "YWJj"}, success=False, check_config=True)
        passwords = {
            "ANDROID_KEYSTORE_PASSWORD": secrets.token_urlsafe(32),
            "ANDROID_KEY_PASSWORD": secrets.token_urlsafe(32),
            "ANDROID_KEY_ALIAS": "disposable-test-key",
        }
        invoke(passwords | {"ANDROID_KEYSTORE_BASE64": "%invalid-base64%"}, success=False)
        invoke(passwords | {"ANDROID_KEYSTORE_BASE64": "  \n "}, success=False)

        key = work / "disposable.jks"
        key_args = [
            "-keystore",
            str(key),
            "-storepass:env",
            "ANDROID_KEYSTORE_PASSWORD",
            "-alias",
            passwords["ANDROID_KEY_ALIAS"],
        ]
        subprocess.run(
            [
                "keytool",
                "-genkeypair",
                "-storetype",
                "JKS",
                *key_args,
                "-keypass:env",
                "ANDROID_KEY_PASSWORD",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=Disposable NFS SAF signing test",
            ],
            env=env | passwords,
            check=True,
            capture_output=True,
        )
        values = passwords | {
            "ANDROID_KEYSTORE_BASE64": base64.b64encode(key.read_bytes()).decode("ascii")
        }
        invoke(values, success=True, check_config=True)
        invoke(values | {"ANDROID_KEYSTORE_PASSWORD": secrets.token_urlsafe(32)}, success=False)
        invoke(values | {"ANDROID_KEY_ALIAS": "missing-alias"}, success=False)
        invoke(values | {"ANDROID_KEY_PASSWORD": secrets.token_urlsafe(32)}, success=False)
        invoke(values, success=True)

        certificate = subprocess.run(
            ["keytool", "-exportcert", *key_args],
            env=env | passwords,
            check=True,
            capture_output=True,
        ).stdout
        expected = hashlib.sha256(certificate).hexdigest()
        apksigner = Path(env["ANDROID_HOME"]) / "build-tools/36.1.0/apksigner"
        verified = subprocess.run(
            [str(apksigner), "verify", "--print-certs", str(output)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        assert f"certificate SHA-256 digest: {expected}" in verified, "Wrong signing identity"
        checksum = output.with_name("SHA256SUMS").read_text()
        assert checksum == f"{hashlib.sha256(output.read_bytes()).hexdigest()}  {output.name}\n"
        assert hashlib.sha256(source.read_bytes()).digest() == original_digest, (
            "Unsigned input modified"
        )
    print("PASS signing: missing/partial/invalid credentials, wrong store/key passwords and alias,")
    print("valid signature identity, checksum, unchanged input, and temporary-key cleanup")


if __name__ == "__main__":
    main()
