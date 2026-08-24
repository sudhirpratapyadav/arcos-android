#!/bin/bash
# Sets this repo's GitHub description, homepage and topics.
#
# These are not stored in the repository — they live in GitHub's own metadata and
# can only be set through the REST API or the web UI. They matter mainly for
# search: someone looking for a way to drive a P3-DX will search "rosaria" or
# "aria" long before they search "arcos".
#
# Needs a token with permission to administer the repo:
#   - fine-grained PAT: Repository permissions -> Administration: Read and write
#   - classic PAT:      "repo" scope
#
# Usage:
#   GITHUB_TOKEN=ghp_xxx ./tools/set-repo-metadata.sh
set -e

REPO="${REPO:-sudhirpratapyadav/arcos-android}"
: "${GITHUB_TOKEN:?set GITHUB_TOKEN to a token that can administer $REPO}"

API="https://api.github.com/repos/$REPO"
AUTH="Authorization: Bearer $GITHUB_TOKEN"
VERSION="X-GitHub-Api-Version: 2022-11-28"

export REPO
export DESCRIPTION="Drive a Pioneer 3-DX from an Android phone. Pure-Java ARCOS protocol implementation - no ROS, no ARIA, no Linux PC. USB-serial, TCP/MobileSim and built-in simulator transports, plus a joystick demo app."

echo "== description"
curl -sS -X PATCH "$API" \
  -H "$AUTH" -H "Accept: application/vnd.github+json" -H "$VERSION" \
  -d "$(python3 -c "import json,os;print(json.dumps({'description':os.environ['DESCRIPTION'],'homepage':'https://github.com/'+os.environ['REPO']}))" )" \
  -o /dev/null -w "  HTTP %{http_code}\n"

# Topics must be lowercase, digits and hyphens only. Ordered roughly by how
# likely someone is to search for them.
echo "== topics"
curl -sS -X PUT "$API/topics" \
  -H "$AUTH" -H "Accept: application/vnd.github+json" -H "$VERSION" \
  -d '{"names":[
        "pioneer-3dx","p3dx","rosaria","aria","ariacoda","arcos",
        "mobile-robots","robotics","robot-control","teleoperation",
        "differential-drive","adept-mobilerobots",
        "android","android-library","java","usb-serial","usb-otg",
        "serial-protocol"
      ]}' \
  -o /dev/null -w "  HTTP %{http_code}\n"

echo
echo "Done. Check https://github.com/$REPO"
