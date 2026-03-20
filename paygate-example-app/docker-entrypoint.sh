#!/usr/bin/env bash
set -euo pipefail

# If using LNbits backend and no API key is set, auto-create a wallet.
if [ "${PAYGATE_BACKEND:-}" = "lnbits" ] && [ -z "${PAYGATE_LNBITS_API_KEY:-}" ]; then
    LNBITS_URL="${PAYGATE_LNBITS_URL:-http://lnbits:5000}"
    echo "==> PAYGATE_BACKEND=lnbits but PAYGATE_LNBITS_API_KEY is empty."
    echo "    Will attempt to auto-create a wallet via $LNBITS_URL"

    # Wait for LNbits to become healthy
    MAX_ATTEMPTS=30
    ATTEMPT=0
    echo "==> Waiting for LNbits at $LNBITS_URL/api/v1/health ..."
    until curl -sf "$LNBITS_URL/api/v1/health" > /dev/null 2>&1; do
        ATTEMPT=$((ATTEMPT + 1))
        if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
            echo "ERROR: LNbits not reachable after $MAX_ATTEMPTS attempts. Proceeding without API key."
            break
        fi
        sleep 2
    done

    if [ "$ATTEMPT" -lt "$MAX_ATTEMPTS" ]; then
        echo "    LNbits is healthy."
        echo "==> Creating wallet 'paygate-auto' ..."
        RESPONSE=$(curl -sf -X POST "$LNBITS_URL/api/v1/wallet" \
            -H "Content-Type: application/json" \
            -d '{"name":"paygate-auto"}' 2>&1) || true

        if [ -n "$RESPONSE" ]; then
            # Extract adminkey from JSON without jq/python — expects "adminkey": "..."
            ADMIN_KEY=$(echo "$RESPONSE" | grep -o '"adminkey" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
            if [ -n "$ADMIN_KEY" ]; then
                export PAYGATE_LNBITS_API_KEY="$ADMIN_KEY"
                echo "    Wallet created. PAYGATE_LNBITS_API_KEY set (${#ADMIN_KEY} chars)."
            else
                echo "ERROR: Could not extract adminkey from LNbits response. Proceeding without API key."
                echo "    Response: $RESPONSE"
            fi
        else
            echo "ERROR: Wallet creation request failed. Proceeding without API key."
        fi
    fi
fi

exec java -jar /app/app.jar "$@"
