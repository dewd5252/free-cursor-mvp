#!/usr/bin/env bash
set -euo pipefail

DATASET_DIR="${1:-dataset}"
ARCHIVE_NAME="${2:-free_cursor_dataset.tar.gz}"

if [[ ! -d "$DATASET_DIR" ]]; then
  echo "Dataset directory not found: $DATASET_DIR" >&2
  exit 1
fi

tar -czvf "$ARCHIVE_NAME" "$DATASET_DIR"
sha256sum "$ARCHIVE_NAME" > "${ARCHIVE_NAME}.sha256"

echo "Archive: $ARCHIVE_NAME"
echo "Checksum: ${ARCHIVE_NAME}.sha256"
