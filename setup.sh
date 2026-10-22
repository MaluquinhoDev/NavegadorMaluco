#!/bin/bash
# Recria as pastas do projeto: cada "__" no nome do arquivo vira "/".
set -e
for f in *__*; do
  [ -f "$f" ] || continue
  dest="${f//__//}"
  mkdir -p "$(dirname "$dest")"
  mv "$f" "$dest"
done
echo "Estrutura recriada."
