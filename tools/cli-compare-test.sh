#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
SOURCE=build/smoke/app_debug
[[ -d "$SOURCE" ]] || { echo "missing smoke project: $SOURCE" >&2; exit 1; }
java_count=$(find "$SOURCE/jadx" -type f -name '*.java' 2>/dev/null | wc -l)

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp -a "$SOURCE" "$tmp/a"
cp -a "$SOURCE" "$tmp/b"

mapfile -t smali_files < <(find "$tmp/b/apktool" -type f -name '*.smali' | sort)
(( ${#smali_files[@]} >= 2 )) || { echo "need at least two smali files" >&2; exit 1; }

identical_output=$(cli/apkforge compare "$tmp/a" "$tmp/b" -y)
grep -Eq '^smali[[:space:]]+identical [0-9]+[[:space:]]+changed 0[[:space:]]+only-A 0[[:space:]]+only-B 0$' \
  <<<"$identical_output" || { echo "unexpected identical comparison:" >&2; echo "$identical_output" >&2; exit 1; }

printf '\n# compare test change\n' >>"${smali_files[0]}"
rm "${smali_files[1]}"
set +e
changed_output=$(cli/apkforge compare "$tmp/a" "$tmp/b" -y)
changed_status=$?
set -e
(( changed_status == 1 )) || { echo "expected mismatch exit 1, got $changed_status" >&2; exit 1; }
grep -Eq '^smali[[:space:]]+identical [0-9]+[[:space:]]+changed 1[[:space:]]+only-A 1[[:space:]]+only-B 0$' \
  <<<"$changed_output" || { echo "unexpected changed comparison:" >&2; echo "$changed_output" >&2; exit 1; }
grep -Fqx "A: a (Java files: $java_count)" <<<"$changed_output"
grep -Fqx "B: b (Java files: $java_count)" <<<"$changed_output"
grep -Eq '^  - smali:' <<<"$changed_output"

echo "CLI compare test OK"
