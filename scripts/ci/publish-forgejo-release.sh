#!/usr/bin/env bash
# Publish a Forgejo release for TAG with the given asset files attached.
#
#   publish-forgejo-release.sh TAG NOTES_FILE ASSET...
#
# Uses the Forgejo API with the workflow's automatic token ($FORGEJO_TOKEN), which can
# write releases on its own repository. The release is created as a draft, every asset
# is uploaded, and only then is it published. The in-app updater reads
# releases/latest, which skips drafts, so it never sees a release that is missing its
# .sha256 file.
#
# Refuses to touch a release that already exists for TAG. If an earlier run failed
# half-way, delete the leftover draft on the Releases page and re-run the job.
set -euo pipefail

tag="${1:?tag}"; notes="${2:?notes file}"; shift 2
: "${FORGEJO_TOKEN:?FORGEJO_TOKEN is not set}"
server="${GITHUB_SERVER_URL:?}"
repo="${GITHUB_REPOSITORY:?}"
api="$server/api/v1/repos/$repo"
auth=(-H "Authorization: token $FORGEJO_TOKEN")

status=$(curl -s -o /dev/null -w '%{http_code}' "${auth[@]}" "$api/releases/tags/$tag")
if [ "$status" != "404" ]; then
  echo "A release for $tag already exists (HTTP $status). Not overwriting it." >&2
  exit 1
fi

# Build the JSON body with node (always present on Forgejo's docker images).
payload=$(TAG="$tag" NOTES="$notes" node -e '
  const fs = require("fs");
  process.stdout.write(JSON.stringify({
    tag_name: process.env.TAG,
    name: "LiveWire " + process.env.TAG,
    body: fs.readFileSync(process.env.NOTES, "utf8"),
    draft: true,
    prerelease: false,
  }));')

id=$(curl -fsS "${auth[@]}" -H 'Content-Type: application/json' \
  -X POST "$api/releases" -d "$payload" | node -e '
  let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
    process.stdout.write(String(JSON.parse(s).id)); });')
echo "Created draft release $id for $tag"

for f in "$@"; do
  name=$(basename "$f")
  curl -fsS -o /dev/null "${auth[@]}" -X POST \
    -F "attachment=@$f" "$api/releases/$id/assets?name=$name"
  echo "Uploaded $name"
done

curl -fsS -o /dev/null "${auth[@]}" -H 'Content-Type: application/json' \
  -X PATCH "$api/releases/$id" -d '{"draft": false}'
echo "Published $server/$repo/releases/tag/$tag"
