#!/usr/bin/env bash
# 图读一键验证（与 scripts/verify.ps1 等价）：
#   ./scripts/verify.sh           单测 + 长图内存回归 + Android debug 构建
#   ./scripts/verify.sh --release 追加 release 构建
# 注意：v1-only 产物门禁（dex 常量 + R8 mapping）目前只有 PowerShell 实现，
# 在 Windows 上请跑 scripts/verify-v1-only.ps1。
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="./gradlew"
[[ -x "$GRADLE" ]] || GRADLE="gradle"

"$GRADLE" :core:test :core:longPageMemoryTest :app:assembleDebug --console=plain
echo "OK: :core:test / :core:longPageMemoryTest / :app:assembleDebug 通过"

if [[ "${1:-}" == "--release" ]]; then
  "$GRADLE" :app:assembleRelease --console=plain
  echo "OK: release 构建完成（v1-only 门禁请用 PowerShell 版 verify-v1-only.ps1）"
fi
