"""Build a signed release locally or in Actions. Publishing is a separate workflow step."""
import argparse
import base64
import os
import subprocess
import tempfile
from pathlib import Path
from project import ROOT, release_version
from signing import local_values, SECRET_NAMES
from package import package


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--out", default=str(ROOT / "artifacts/release"))
    args = parser.parse_args()
    name = release_version(args.tag)
    with tempfile.TemporaryDirectory(prefix="vodroid-signing-") as temporary:
        env = dict(os.environ)
        if any(os.environ.get(key) for key in SECRET_NAMES):
            if not all(os.environ.get(key) for key in SECRET_NAMES):
                raise RuntimeError("All four VODROID signing secrets are required; see docs/RELEASING.md")
            keyfile = Path(temporary) / "release.p12"
            keyfile.write_bytes(base64.b64decode(os.environ[SECRET_NAMES[0]], validate=True))
            keyfile.chmod(0o600)
            env["VODROID_KEYSTORE_FILE"] = str(keyfile)
        else:
            env.update(local_values())
        env.pop("VODROID_KEYSTORE_BASE64", None)
        command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), "--no-daemon"]
        if args.offline:
            command.append("--offline")
        command += ["-PvodroidVersionName=" + name, ":core:test", ":app:assembleRelease", ":app:lintRelease"]
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        package("release", name, args.out, include_source=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
