#!/usr/bin/env bash
set -euo pipefail

exec java -jar /app/app.jar "$@"
