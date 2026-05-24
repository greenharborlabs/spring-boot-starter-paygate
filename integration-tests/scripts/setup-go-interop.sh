#!/usr/bin/env bash
#
# setup-go-interop.sh - Build the small Go helper used by PLAYBOOK.md section 9.
#
set -euo pipefail

TARGET_DIR="${GO_INTEROP_DIR:-/tmp/paygate-go-interop}"

if ! command -v go > /dev/null 2>&1; then
  echo "FAIL: Go toolchain not found. Install Go 1.21+ and rerun this script."
  exit 1
fi
if ! command -v python3 > /dev/null 2>&1; then
  echo "FAIL: python3 not found. Install python3 and rerun this script."
  exit 1
fi

mkdir -p "$TARGET_DIR"

python3 - "$TARGET_DIR" <<'PY'
from pathlib import Path
import sys

target = Path(sys.argv[1])

(target / "main.go").write_text(r'''package main

import (
	"encoding/base64"
	"fmt"
	"os"

	"gopkg.in/macaroon.v2"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, "Usage: %s <command> [args...]\n", os.Args[0])
		fmt.Fprintf(os.Stderr, "Commands:\n")
		fmt.Fprintf(os.Stderr, "  verify <base64-macaroon>   Deserialize and print macaroon fields\n")
		fmt.Fprintf(os.Stderr, "  mint <root-key> <id>        Mint a macaroon and print base64\n")
		os.Exit(1)
	}

	switch os.Args[1] {
	case "verify":
		if len(os.Args) < 3 {
			fmt.Fprintln(os.Stderr, "Missing macaroon argument")
			os.Exit(1)
		}
		raw, err := base64.StdEncoding.DecodeString(os.Args[2])
		if err != nil {
			fmt.Fprintf(os.Stderr, "Base64 decode error: %v\n", err)
			os.Exit(1)
		}
		var m macaroon.Macaroon
		if err := m.UnmarshalBinary(raw); err != nil {
			fmt.Fprintf(os.Stderr, "Macaroon unmarshal error: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Location: %s\n", m.Location())
		fmt.Printf("ID (hex): %x\n", m.Id())
		fmt.Printf("ID (len): %d\n", len(m.Id()))
		fmt.Printf("Signature (hex): %x\n", m.Signature())
		fmt.Printf("Caveats: %d\n", len(m.Caveats()))
		for i, c := range m.Caveats() {
			fmt.Printf("  Caveat[%d]: %s\n", i, string(c.Id))
		}
		fmt.Println("OK: Go successfully deserialized Java macaroon")

	case "mint":
		if len(os.Args) < 4 {
			fmt.Fprintln(os.Stderr, "Usage: mint <root-key> <identifier>")
			os.Exit(1)
		}
		rootKey := []byte(os.Args[2])
		id := []byte(os.Args[3])
		m, err := macaroon.New(rootKey, id, "l402", macaroon.V2)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Mint error: %v\n", err)
			os.Exit(1)
		}
		raw, err := m.MarshalBinary()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Marshal error: %v\n", err)
			os.Exit(1)
		}
		fmt.Print(base64.StdEncoding.EncodeToString(raw))

	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", os.Args[1])
		os.Exit(1)
	}
}
''', encoding="utf-8")

(target / "go.mod").write_text('''module paygate-go-interop

go 1.21

require gopkg.in/macaroon.v2 v2.1.0
''', encoding="utf-8")
PY

(
  cd "$TARGET_DIR"
  go mod tidy
  go build -o paygate-go-interop .
)

echo "Go interop helper ready: $TARGET_DIR/paygate-go-interop"
