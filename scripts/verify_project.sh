#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check_android_sources.py
python3 scripts/verify_project.py
python3 scripts/audit_android_build.py
python3 scripts/check_reference_integrity.py

python3 scripts/test_rooms_schema.py
