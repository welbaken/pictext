#!/usr/bin/env bash
# 图读一键验证：算法单测 + Android debug 构建。
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x ./gradlew ]]; then
  ./gradlew :core:test :app:assembleDebug --console=plain
else
  gradle :core:test :app:assembleDebug --console=plain
fi
echo "OK: :core:test 与 :app:assembleDebug 通过"
