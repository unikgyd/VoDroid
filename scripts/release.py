"""Build and package a locally signed ARM64 release for manual upload."""
import argparse
import os
import subprocess
from project import ROOT, DIST, release_version
from signing import local_values
from package import package


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", "--tag", dest="version", required=True, help="Release version, for example 0.4.6")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--out", default=str(DIST))
    args = parser.parse_args()
    name = release_version(args.version if args.version.startswith("v") else "v" + args.version)
    env = dict(os.environ)
    env.update(local_values())
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
