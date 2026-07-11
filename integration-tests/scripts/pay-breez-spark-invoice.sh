#!/usr/bin/env bash
#
# pay-breez-spark-invoice.sh — Pay a BOLT11 invoice through Breez SDK Spark.
#
# Output is shell-compatible:
#   PAYMENT_HASH=<64-hex>
#   PREIMAGE=<64-hex>
#   FEE_SATS=<number>
#
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <bolt11-invoice> [max-fee-sats] [completion-timeout-seconds]" >&2
  exit 2
fi

INVOICE="$1"
MAX_FEE_SATS="${2:-${BREEZ_MAX_FEE_SATS:-10}}"
COMPLETION_TIMEOUT_SECONDS="${3:-${BREEZ_COMPLETION_TIMEOUT_SECONDS:-30}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_BIN="${BREEZ_PYTHON:-python3}"
BREEZ_SDK_VERSION="${BREEZ_SDK_VERSION:-0.17.1}"
VENV_DIR="${BREEZ_SPARK_VENV:-${HOME}/.cache/paygate/breez-sdk-spark-${BREEZ_SDK_VERSION}-venv}"

if [ -z "${BREEZ_API_KEY:-}" ]; then
  echo "ERROR: BREEZ_API_KEY is required" >&2
  exit 1
fi
if [ -z "${BREEZ_MNEMONIC:-}" ]; then
  echo "ERROR: BREEZ_MNEMONIC is required" >&2
  exit 1
fi

if [ ! -x "${VENV_DIR}/bin/python" ]; then
  "${PYTHON_BIN}" -m venv "${VENV_DIR}"
fi

if ! "${VENV_DIR}/bin/python" -c "import breez_sdk_spark" >/dev/null 2>&1; then
  "${VENV_DIR}/bin/python" -m pip install -q "breez-sdk-spark==${BREEZ_SDK_VERSION}"
fi

exec "${VENV_DIR}/bin/python" "${SCRIPT_DIR}/pay_breez_spark_invoice.py" \
  --invoice "${INVOICE}" \
  --max-fee-sats "${MAX_FEE_SATS}" \
  --completion-timeout-seconds "${COMPLETION_TIMEOUT_SECONDS}"
