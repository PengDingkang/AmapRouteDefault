#!/usr/bin/env bash
# Sync only module metadata. Credentials are resolved by gh's Git credential helper.
set -euo pipefail

: "${GH_TOKEN:?XPOSED_REPO_TOKEN is required}"
: "${XPOSED_REPOSITORY:?Xposed repository is required}"
: "${METADATA_DIR:?Prepared metadata directory is required}"
: "${GITHUB_OUTPUT:?GitHub output file is required}"
[[ "$XPOSED_REPOSITORY" =~ ^[[:alnum:]_.-]+/[[:alnum:]_.-]+$ ]]

files=(README.md README.en.md SCOPE SOURCE_URL SUMMARY)
for file in "${files[@]}"; do test -s "$METADATA_DIR/$file"; done
gh api "repos/$XPOSED_REPOSITORY" --jq '.permissions.push' | grep -qx true
gh auth setup-git --hostname github.com
export GIT_TERMINAL_PROMPT=0

sync_dir="$(mktemp -d)"
trap 'rm -rf "$sync_dir"' EXIT
url="https://github.com/$XPOSED_REPOSITORY.git"
git ls-remote --heads "$url" > "$sync_dir/heads"
if grep -q $'\trefs/heads/main$' "$sync_dir/heads"; then
  git clone --depth 1 --branch main "$url" "$sync_dir/repo"
else
  git init -b main "$sync_dir/repo"
  git -C "$sync_dir/repo" remote add origin "$url"
fi
git -C "$sync_dir/repo" config user.name 'github-actions[bot]'
git -C "$sync_dir/repo" config user.email '41898282+github-actions[bot]@users.noreply.github.com'
for file in "${files[@]}"; do cp "$METADATA_DIR/$file" "$sync_dir/repo/$file"; done
git -C "$sync_dir/repo" add -- "${files[@]}"
if ! git -C "$sync_dir/repo" diff --cached --quiet; then
  git -C "$sync_dir/repo" commit -m 'Update module metadata'
  git -C "$sync_dir/repo" push origin main
fi
printf 'commit=%s\n' "$(git -C "$sync_dir/repo" rev-parse HEAD)" >> "$GITHUB_OUTPUT"
