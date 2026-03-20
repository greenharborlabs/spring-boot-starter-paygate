package main

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"os"

	"gopkg.in/macaroon.v2"
)

func main() {
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		fmt.Fprintln(os.Stderr, "ERROR: no input")
		os.Exit(1)
	}

	raw, err := base64.StdEncoding.DecodeString(scanner.Text())
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: base64 decode: %v\n", err)
		os.Exit(1)
	}

	var m macaroon.Macaroon
	if err := m.UnmarshalBinary(raw); err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: unmarshal: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("OK\n")
	fmt.Printf("Version: %d\n", m.Version())
	fmt.Printf("ID (len): %d\n", len(m.Id()))
	fmt.Printf("ID (hex): %x\n", m.Id())
	fmt.Printf("Location: %s\n", m.Location())
	fmt.Printf("Caveats: %d\n", len(m.Caveats()))
	for i, c := range m.Caveats() {
		fmt.Printf("  Caveat[%d]: %s\n", i, string(c.Id))
	}
	fmt.Printf("Sig (hex): %x\n", m.Signature())
}
