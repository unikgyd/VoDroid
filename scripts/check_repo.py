"""Check the publication index without reading local credentials or device evidence."""
import hashlib
import re
import sys
from project import ROOT, NATIVE, git, version


def main():
    version()
    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if hashlib.sha256(wrapper.read_bytes()).hexdigest() != "7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172":
        raise RuntimeError("Gradle 8.14.3 wrapper checksum mismatch")
    properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    if "distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531" not in properties:
        raise RuntimeError("Gradle distribution checksum is missing")
    modules = (ROOT / ".gitmodules").read_text()
    count = 0
    for record in git("ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, commit, stage = metadata.decode().split()
        name = raw_path.decode("utf-8")
        parts = name.split("/")
        if stage != "0" or parts[0] in {"artifacts", "references", "signing", ".gradle", ".kotlin", ".codex", ".agents", ".aws"} or "build" in parts:
            raise RuntimeError("Local or unresolved file in index: " + name)
        if name.endswith((".apk", ".aab", ".zip", ".jks", ".keystore", ".p12", ".pfx", ".key", ".pcap", ".pcapng", ".log")) or parts[-1] in {"local.properties", "credentials.json", ".env", "signing.properties"}:
            raise RuntimeError("Private/generated file in index: " + name)
        if mode == "160000":
            if NATIVE.get(name) != commit or "path = " + name not in modules:
                raise RuntimeError("Unexpected or unconfigured Git link: " + name)
            count += 1
            continue
        path = ROOT / name
        if not path.is_file():
            raise RuntimeError("Indexed file missing: " + name)
        content = path.read_bytes()
        if len(content) > 2 * 1024 * 1024:
            raise RuntimeError("Review large indexed file: " + name)
        if b"\0" not in content and re.search(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,})", content):
            raise RuntimeError("Possible private credential in: " + name)
    if count != len(NATIVE):
        raise RuntimeError("Initialize and stage both pinned native dependencies")
    for public, asset in [("LICENSE", "LICENSE.txt"), ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.txt")]:
        if (ROOT / public).read_text().strip() != (ROOT / "app/src/main/assets" / asset).read_text().strip():
            raise RuntimeError("APK legal asset differs from " + public)
    print("Publication index, two pinned dependencies, wrapper checksums and legal assets passed.")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        sys.exit(str(error))
