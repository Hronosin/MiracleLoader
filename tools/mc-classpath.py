#!/usr/bin/env python3
"""Prints the compile class path of one Minecraft version, as Prism Launcher has it on disk.

    mc-classpath.py <PrismLauncher data dir> <minecraft version>

Reads Prism's own metadata (meta/net.minecraft/<version>.json, plus the LWJGL component it
requires) and maps every declared library to its file under libraries/. Only the jars that
version actually uses end up on the class path, so leftovers from other instances (an old Forge,
say) can't leak in. Prints nothing and exits 1 if the metadata isn't there.
"""
import json
import os
import sys


def maven_path(name):
    """group:artifact:version[:classifier][@ext] -> group/path/artifact/version/artifact-version[-classifier].ext"""
    ext = "jar"
    if "@" in name:
        name, ext = name.split("@", 1)
    parts = name.split(":")
    if len(parts) < 3:
        return None
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    file = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{ext}"
    return os.path.join(*group.split("."), artifact, version, file)


def library_names(meta):
    for lib in meta.get("libraries", []):
        name = lib.get("name")
        if name:
            yield name


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    prism, version = sys.argv[1], sys.argv[2]
    meta_file = os.path.join(prism, "meta", "net.minecraft", f"{version}.json")
    if not os.path.isfile(meta_file):
        return 1
    with open(meta_file) as f:
        meta = json.load(f)

    names = list(library_names(meta))
    for req in meta.get("requires", []):
        uid, ver = req.get("uid"), req.get("suggests") or req.get("equals")
        if uid and ver:
            dep = os.path.join(prism, "meta", uid, f"{ver}.json")
            if os.path.isfile(dep):
                with open(dep) as f:
                    names.extend(library_names(json.load(f)))

    libs = os.path.join(prism, "libraries")
    jars, seen = [], set()
    for name in names:
        rel = maven_path(name)
        if not rel or rel in seen:
            continue
        seen.add(rel)
        path = os.path.join(libs, rel)
        if os.path.isfile(path) and "natives" not in os.path.basename(path):
            jars.append(path)
    print(":".join(jars))
    return 0


if __name__ == "__main__":
    sys.exit(main())
