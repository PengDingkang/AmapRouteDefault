#!/usr/bin/env bash
# Read-only access check; repository permissions.push is not a token permission check.
set -euo pipefail

: "${GH_TOKEN:?A GitHub token is required}"
repository="${1:?An owner/repository argument is required}"
if [[ ! "$repository" =~ ^[[:alnum:]_.-]+/[[:alnum:]_.-]+$ ]]; then
  echo 'Expected a repository in owner/name format' >&2
  exit 1
fi

# Installation tokens such as GITHUB_TOKEN need not report user-level push rights.
# Contents:write is configured separately; actual writes enforce that permission.
if ! gh api --method GET "repos/$repository" --silent; then
  echo "Cannot access $repository. Check the repository name and token access; see the GitHub API error above." >&2
  exit 1
fi
echo "Repository accessible: $repository"
