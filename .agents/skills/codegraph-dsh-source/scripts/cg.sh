#!/usr/bin/env bash
# codegraph 包装脚本：固定 DSH 官方源码路径，分析类子命令自动带 -p。
#
# 用法与 codegraph 一致，只是不需要再传 -p：
#   ./cg.sh query "SettingsForms" -l 5
#   ./cg.sh explore "settings persisted to cordis.patch.yml"
#   ./cg.sh node "SettingsForms"
#   ./cg.sh node --file packages/settings/settings/src/index.ts --offset 378 --limit 60
#
# 维护类命令（status/sync/index/unlock/init/daemon）不在这里包装，直接调 codegraph：
#   "C:/Users/ZDY/scoop/persist/nvm/nodejs/v22.23.2/codegraph.cmd" sync "E:/github/deepseek-harness"
set -u

CG="C:/Users/ZDY/scoop/persist/nvm/nodejs/v22.23.2/codegraph.cmd"
SRC="E:/github/deepseek-harness"

if [ "$#" -eq 0 ]; then
  echo "usage: cg.sh <query|explore|context|node|callers|callees|impact|files> [args...]" >&2
  exit 2
fi

sub="$1"
shift

case "$sub" in
  query|explore|context|node|callers|callees|impact|files)
    exec "$CG" "$sub" -p "$SRC" "$@"
    ;;
  *)
    echo "cg.sh 只包装分析类子命令；维护命令请直接调用 codegraph（status/sync/index/unlock/init）。" >&2
    exit 2
    ;;
esac
