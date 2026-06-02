#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
# Picsou bare-metal bootstrap helper.
#
# The Docker entrypoint handles secret generation automatically for
# containerised installs. This script is the equivalent for operators
# running the JAR directly (e.g. bare-metal, systemd, Nix).
#
# It prints an .env.local file to stdout with freshly-generated secrets
# (only for env vars that are not already set in your shell). Redirect
# it to a file and source it before `mvn spring-boot:run`:
#
#     ./scripts/picsou-init.sh > .env.local
#     set -a; source .env.local; set +a
#     mvn spring-boot:run
#
# Re-running is safe: secrets already present in your environment are
# preserved — only missing ones are generated. Nothing is written to
# disk except via shell redirection; this script has no side effects.
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

gen_if_unset() {
    local var_name="$1"
    local gen_args="$2"
    if [ -n "${!var_name:-}" ]; then
        return 0
    fi
    # shellcheck disable=SC2086
    local value
    value=$(openssl $gen_args)
    printf '%s=%s\n' "$var_name" "$value"
}

echo "# Picsou bare-metal secrets — generated $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "# Safe to commit? NO. These values protect your entire install."
echo "# Rotating JWT_SECRET logs out every user; rotating CRYPTO_ENCRYPTION_KEY"
echo "# renders every stored exchange API secret undecryptable. Treat as vault-tier."
echo

gen_if_unset "JWT_SECRET"            "rand -base64 48"
gen_if_unset "CRYPTO_ENCRYPTION_KEY" "rand -base64 32"
gen_if_unset "POSTGRES_PASSWORD"     "rand -base64 24"
