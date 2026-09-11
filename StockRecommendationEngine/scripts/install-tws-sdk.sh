#!/usr/bin/env bash
set -euo pipefail
# Use the official downloaded IBJts directory; do not redistribute SDK binaries.
sdk_root=${1:?Usage: bash scripts/install-tws-sdk.sh /path/to/IBJts}
version=10.45.01
if [[ "$(tr -d '\r\n' < "$sdk_root/API_VersionNum.txt")" != "API_Version=$version" ]]; then
  echo "Expected official TWS API $version" >&2
  exit 1
fi
project_root=$(cd "$(dirname "$0")/.." && pwd)
"$project_root/mvnw" -f "$project_root/pom.xml" org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$sdk_root/source/JavaClient/TwsApi.jar" \
  -DgroupId=com.interactivebrokers -DartifactId=tws-api -Dversion="$version" \
  -Dpackaging=jar -DgeneratePom=true
