import os
import re
import sys
import subprocess

LISTS = [
    {
        "file": "gradle.properties",
        "pattern": r"mod_version=(.+)"
    },
    {
        "file": "src/main/resources/fabric.mod.json",
        "pattern": r'"version": "(.+)"'
    }
]

STRICT_SEMVER_REGEX = r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z\.-]+))?(?:\+([0-9A-Za-z\.-]+))?$"

def is_strict_semver(version):
    return re.match(STRICT_SEMVER_REGEX, version) is not None

def is_prerelease(version):
    return '-' in version

def git_tag_exists(version):
    tag = f"v{version}"
    try:
        tags = subprocess.check_output(["git", "tag"], encoding="utf-8")
        return tag in [t.strip() for t in tags.splitlines()]
    except Exception as e:
        print(f"Error checking git tags: {e}")
        return False

def get_latest_version():
    try:
        tags = subprocess.check_output(["git", "tag"], encoding="utf-8")
        semver_tags = [t[1:] for t in tags.splitlines() if re.match(r"^v" + STRICT_SEMVER_REGEX, t)]
        if not semver_tags:
            return None
        return sorted(semver_tags, key=lambda s: list(map(int, s.split('-')[0].split('.'))))[-1]
    except Exception:
        return None

def has_uncommitted_changes():
    try:
        status = subprocess.check_output(["git", "status", "--porcelain"], encoding="utf-8")
        return bool(status.strip())
    except Exception:
        return True

def version_greater(new, old):
    def parse(v):
        return tuple(map(int, v.split('-')[0].split('.')))
    return parse(new) > parse(old)

def update_version(new_version):
    for item in LISTS:
        with open(item["file"], "r") as f:
            content = f.read()
        new_content = re.sub(item["pattern"], lambda m: m.group(0).replace(m.group(1), new_version), content)
        with open(item["file"], "w") as f:
            f.write(new_content)

def print_error(msg):
    print(f"Error: {msg}")
    sys.exit(1)

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Update mod version with strict checks.")
    parser.add_argument("new_version", help="New version (strict semver)")
    parser.add_argument("--prerelease", action="store_true", help="Allow prerelease version")
    args = parser.parse_args()

    new_version = args.new_version

    if not is_strict_semver(new_version):
        print_error("Version must be strict semver (e.g., 1.2.3 or 1.2.3-beta)")

    if is_prerelease(new_version) and not args.prerelease:
        print_error("Prerelease version requires --prerelease flag.")

    if git_tag_exists(new_version):
        print_error(f"Version v{new_version} already exists as a git tag.")

    latest = get_latest_version()
    if latest and not version_greater(new_version, latest):
        print_error(f"New version {new_version} must be greater than latest {latest}.")

    if has_uncommitted_changes():
        print_error("Uncommitted changes detected. Please commit or stash before updating version.")

    update_version(new_version)
    print(f"Updated mod version to {new_version} in all files.")