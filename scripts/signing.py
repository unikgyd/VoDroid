"""Create a persistent local signing key or upload it through an authenticated gh CLI."""
import argparse
import base64
import json
import os
import secrets
import shutil
import subprocess
from pathlib import Path
from project import ROOT, REPOSITORY

DIRECTORY = ROOT / "signing"
CREDENTIALS = DIRECTORY / "credentials.json"
SECRET_NAMES = ("VODROID_KEYSTORE_BASE64", "VODROID_STORE_PASSWORD", "VODROID_KEY_ALIAS", "VODROID_KEY_PASSWORD")


def local_values():
    if not CREDENTIALS.is_file():
        raise RuntimeError("Signing key missing. Run: python scripts/signing.py init")
    data = json.loads(CREDENTIALS.read_text(encoding="utf-8"))
    return {"VODROID_KEYSTORE_FILE": str(DIRECTORY / "release.p12"), "VODROID_STORE_PASSWORD": data["storePassword"],
            "VODROID_KEY_ALIAS": data["alias"], "VODROID_KEY_PASSWORD": data["keyPassword"]}


def init():
    if CREDENTIALS.exists() or (DIRECTORY / "release.p12").exists():
        raise RuntimeError("Signing files already exist; they will not be overwritten")
    DIRECTORY.mkdir(exist_ok=True)
    password = secrets.token_urlsafe(36)
    env = dict(os.environ, VODROID_INIT_PASSWORD=password)
    java_home = os.environ.get("JAVA_HOME")
    keytool = str(Path(java_home) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")) if java_home else shutil.which("keytool")
    if not keytool:
        raise RuntimeError("Set JAVA_HOME to JDK 17")
    subprocess.run([keytool, "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(DIRECTORY / "release.p12"),
        "-alias", "vodroid-release", "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA", "-validity", "10000",
        "-dname", "CN=unikgyd, OU=VoDroid, O=VoDroid project", "-storepass:env", "VODROID_INIT_PASSWORD", "-keypass:env", "VODROID_INIT_PASSWORD"], env=env, check=True)
    with os.fdopen(os.open(CREDENTIALS, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as stream:
        json.dump({"alias": "vodroid-release", "storePassword": password, "keyPassword": password}, stream, indent=2)
    os.chmod(DIRECTORY / "release.p12", 0o600)
    print("Signing key created in ignored signing/. Back up this folder; credentials were not printed.")


def upload():
    gh = shutil.which("gh")
    if not gh:
        raise RuntimeError("Install GitHub CLI and run gh auth login, then retry this command")
    values = local_values()
    subprocess.run([gh, "auth", "status"], check=True)
    payloads = {"VODROID_KEYSTORE_BASE64": base64.b64encode(Path(values["VODROID_KEYSTORE_FILE"]).read_bytes()).decode(),
                **{key: values[key] for key in SECRET_NAMES[1:]}}
    for key, value in payloads.items():
        subprocess.run([gh, "secret", "set", key, "--repo", REPOSITORY], input=value, text=True, check=True)
    print("Four signing secrets configured for " + REPOSITORY)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "upload"))
    args = parser.parse_args()
    try:
        init() if args.command == "init" else upload()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
