#!/usr/bin/env bash
#
# Installs the OIE engine jars this plugin builds against into the local
# Maven repository from an engine checkout or distribution. Other build
# dependencies resolve from Maven Central.
#
# Usage:
#   ENGINE_DIR=/path/to/engine ./scripts/install-engine-jars.sh
#   mvn clean package -Dmirth.version=4.6.0
#
# If ENGINE_DIR is unset, defaults to ../oie relative to this repo.
# Override the installed version with VERSION=x.y.z (default 4.6.0).

set -euo pipefail

VERSION="${VERSION:-4.6.0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENGINE_DIR="${ENGINE_DIR:-$(cd "$SCRIPT_DIR/../../oie" 2>/dev/null && pwd || true)}"

if [[ -z "${ENGINE_DIR}" || ! -d "${ENGINE_DIR}" ]]; then
    echo "error: engine directory not found." >&2
    echo "  set ENGINE_DIR to point at your OIE engine checkout, e.g.:" >&2
    echo "    ENGINE_DIR=/path/to/engine $0" >&2
    exit 1
fi

# artifact:candidate-path[:fallback-path...] — engine jars used by modules/tests
# depend on (see the root pom dependencyManagement).
declare -a JARS=(
    "mirth-server:server-lib/mirth-server.jar:server/setup/server-lib/mirth-server.jar"
    "mirth-client-core:server-lib/mirth-client-core.jar:server/setup/server-lib/mirth-client-core.jar"
    "donkey-server:server-lib/donkey/donkey-server.jar:server/setup/server-lib/donkey/donkey-server.jar:donkey/setup/donkey-server.jar"
    "donkey-model:server-lib/donkey/donkey-model.jar:donkey/setup/donkey-model.jar:server/setup/server-lib/donkey/donkey-model.jar"
    "mirth-crypto:server-lib/mirth-crypto.jar:server/setup/server-lib/mirth-crypto.jar"
)

resolve_jar() {
    local spec="$1" path
    local -a parts
    IFS=':' read -ra parts <<< "${spec}"
    for path in "${parts[@]:1}"; do
        if [[ -f "${ENGINE_DIR}/${path}" ]]; then
            echo "${ENGINE_DIR}/${path}"
            return 0
        fi
    done
    return 1
}

for entry in "${JARS[@]}"; do
    if ! resolve_jar "${entry}" >/dev/null; then
        echo "error: could not find a jar for ${entry%%:*} under ${ENGINE_DIR}" >&2
        echo "  use an extracted OIE distribution or build the engine's setup jars first." >&2
        exit 1
    fi
done

for entry in "${JARS[@]}"; do
    artifact="${entry%%:*}"
    jar_path="$(resolve_jar "${entry}")"
    echo "installing ${artifact}-${VERSION} from ${jar_path}"
    mvn -B -ntp -q org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
        -Dfile="${jar_path}" \
        -DgroupId=com.mirth.connect \
        -DartifactId="${artifact}" \
        -Dversion="${VERSION}" \
        -Dpackaging=jar
done

echo "done. ${#JARS[@]} jars installed at version ${VERSION}."
