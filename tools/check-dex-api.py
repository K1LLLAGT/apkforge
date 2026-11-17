#!/usr/bin/env python3
"""Fail if an APK's dex calls java.* / javax.* APIs newer than minSdk.

D8 backports many Java 9+ APIs and buildSrc/ApiCompatTransform rewrites the rest; this
check proves nothing slipped through (jadx/ARSCLib/smali were written for desktop JVMs).

usage: check-dex-api.py <apk> <min-sdk> [sdk-dir]
"""
import os, re, subprocess, sys, tempfile, zipfile, glob
import xml.etree.ElementTree as ET

# Referenced but only on code paths the engine never runs (desktop GUI/image helpers in
# jadx/ST4/antlr tool, annotation metadata), or already guarded by the library itself.
ALLOW_PREFIXES = (
    "java/awt/", "javax/swing/", "javax/imageio/", "java/lang/instrument/",
    "javax/annotation/",
    "java/util/concurrent/Flow",          # kotlinx-coroutines / guava, API-checked at runtime
    "java/lang/ClassValue",               # kotlinx-coroutines ClassValueCtorCache (guarded)
    "java/lang/ref/Reference.reachabilityFence",  # androidx/guava, guarded by SDK_INT
    "java/lang/StringBuilder.", "java/lang/StringBuffer.",  # inherited from hidden AbstractStringBuilder
)

def main():
    apk, min_sdk = sys.argv[1], int(sys.argv[2])
    sdk = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    api_xml = sorted(glob.glob(os.path.join(sdk, "platforms", "android-*", "data", "api-versions.xml")))[-1]
    dexdump = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump")))[-1]

    def ver(v):  # "36.1" style minor SDK versions appear in newer platforms
        return int(float(v))

    classes = {}
    for c in ET.parse(api_xml).getroot().findall("class"):
        since = ver(c.get("since", "1"))
        meths = {m.get("name"): ver(m.get("since", since)) for m in c.findall("method")}
        sup = [e.get("name") for e in c.findall("extends")] + [e.get("name") for e in c.findall("implements")]
        classes[c.get("name")] = (since, meths, sup)

    def lookup(owner, meth, seen):
        if owner in seen or owner not in classes:
            return None
        seen.add(owner)
        _, meths, sup = classes[owner]
        if meth in meths:
            return meths[meth]
        found = [r for r in (lookup(s, meth, seen) for s in sup + ["java/lang/Object"]) if r is not None]
        return min(found) if found else None

    refs = set()
    pat = re.compile(r"L((?:java|javax)/[A-Za-z0-9_/$]+);\.([A-Za-z0-9_<>$]+):(\([^)]*\)\S+)")
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                path = z.extract(name, tmp)
                out = subprocess.run([dexdump, "-d", path], capture_output=True).stdout.decode("utf-8", "replace")
                refs.update(pat.findall(out))

    problems = []
    for owner, name, desc in sorted(refs):
        key = f"{owner}.{name}{desc}"
        if key.startswith(ALLOW_PREFIXES):
            continue
        if owner not in classes:
            problems.append(f"missing class  {key}")
            continue
        since = lookup(owner, name + desc, set())
        if since is None:
            problems.append(f"missing method {key}")
        elif since > min_sdk:
            problems.append(f"API {since:<3}        {key}")
    print(f"checked {len(refs)} java/javax method refs in {os.path.basename(apk)} against minSdk {min_sdk}")
    for p in problems:
        print("  " + p)
    if problems:
        print(f"FAIL: {len(problems)} API reference(s) unavailable on API {min_sdk}")
        sys.exit(1)
    print("OK")

if __name__ == "__main__":
    main()
