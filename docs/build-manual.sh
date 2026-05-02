#!/usr/bin/env bash
# 一键生成 FLUX 客服更新工具使用手册 docx。
#
# 流程：
#   1. node generate-manual.js               JS 内容 -> 朴素 docx
#   2. python3 convert_style.py              套 FLUX 标准样式（来自 flux-convert-style skill）
#   3. python3 postprocess-manual.py         横向->纵向、修对齐、还原代码段
#
# 输出：docs/FLUX客服更新工具使用手册.docx（覆盖式）
#
# 用法：
#   cd docs && ./build-manual.sh
#   或：bash docs/build-manual.sh

set -euo pipefail

DOCS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${DOCS_DIR}/.." && pwd)"
SKILL_DIR="${HOME}/.claude/skills/flux-convert-style"
TEMPLATE="${SKILL_DIR}/references/templates/flux_standard_v6/template.docx"
CONVERT_PY="${SKILL_DIR}/scripts/convert_style.py"
POSTPROCESS_PY="${DOCS_DIR}/postprocess-manual.py"
OUTPUT="${DOCS_DIR}/FLUX客服更新工具使用手册.docx"

TITLE="FLUX 客服更新工具 使用手册"
SUBTITLE="FLUX 客服 FTP 部署工具"
AUTHOR="FLUX 项目组"
VERSION="V$(grep -E '^[[:space:]]*version[[:space:]]*=' "${PROJECT_ROOT}/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"

echo "==> 前置检查"
command -v node >/dev/null 2>&1 || { echo "缺少 node。装一下：brew install node" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3" >&2; exit 1; }
[ -f "${TEMPLATE}" ] || { echo "FLUX 模板不存在: ${TEMPLATE}" >&2; exit 1; }
[ -f "${CONVERT_PY}" ] || { echo "convert_style.py 不存在: ${CONVERT_PY}" >&2; exit 1; }
[ -f "${POSTPROCESS_PY}" ] || { echo "postprocess-manual.py 不存在: ${POSTPROCESS_PY}" >&2; exit 1; }
python3 -c "import docx" 2>/dev/null || { echo "缺少 python-docx。装一下：python3 -m pip install --user python-docx" >&2; exit 1; }

if [ ! -d "${DOCS_DIR}/node_modules/docx" ]; then
    echo "==> 首次运行：安装 docx npm 包"
    (cd "${DOCS_DIR}" && npm install docx >/dev/null 2>&1)
fi

echo "==> [1/3] node generate-manual.js"
(cd "${DOCS_DIR}" && node generate-manual.js 2>/dev/null) | grep -v '^Generated' || true

echo "==> [2/3] convert_style.py（套 FLUX 模板）  version=${VERSION}"
python3 "${CONVERT_PY}" \
    "${OUTPUT}" \
    --template "${TEMPLATE}" \
    --title "${TITLE}" \
    --subtitle "${SUBTITLE}" \
    --version "${VERSION}" \
    --author "${AUTHOR}" \
    --output "${OUTPUT}" >/dev/null

echo "==> [3/3] postprocess-manual.py"
python3 "${POSTPROCESS_PY}" "${OUTPUT}"

SIZE=$(du -h "${OUTPUT}" | awk '{print $1}')
echo
echo "完成：${OUTPUT}"
echo "      ${SIZE}    ${VERSION}"
echo "      用 Word 打开后，如弹出「是否更新域」请点「是」，目录会自动生成实际页码。"
