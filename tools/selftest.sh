#!/bin/sh
cd "$(dirname "$0")/.."
echo "### 1. 源码与模拟器一致性"; python3 tools/sim_verify.py || exit 1
echo; echo "### 2. 主流程"; python3 tools/sim_test.py || exit 1
echo; echo "### 3. 边界场景"; python3 tools/sim_edge.py
echo; echo "### 4. 还原判断"; python3 tools/sim_restore.py || exit 1
echo; echo "### 5. UI 状态机"; python3 tools/sim_ui.py || exit 1
echo; echo "### 6. 生命周期"; python3 tools/sim_lifecycle.py
