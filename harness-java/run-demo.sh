#!/usr/bin/env bash
# Build without Maven (plain javac, JDK 21+) and run the demo.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p target/demo-classes
javac -d target/demo-classes src/main/java/si/openagentix/harness/*.java
java -cp target/demo-classes si.openagentix.harness.Demo "$@"
