#!/usr/bin/env bash
# Ручной спавн агента MetaJetCore — для работы до появления плагина.
#
#   ./scripts/spawn-agent.sh <role> <name> [model]
#
#   role   implementer | researcher | reviewer | orchestrator
#   name   имя сессии = адрес для SendMessage, напр. mjc-impl-be
#   model  переопределить модель роли
#
# Конфигурация идёт через переменные окружения: окружение наследуется прозрачно любой
# обёрткой вокруг claude, даже если она не пробрасывает "$@" (docs/ARCHITECTURE.md §2.2).
#
# ИСКЛЮЧЕНИЕ — роль. Её задаёт только флаг --agent; env-переменная CLAUDE_CODE_AGENT
# роль не применяет, проверено (§2.2.1). Поэтому:
#
# Роль уезжает флагом --agent mjc-<role>, определение читается из ~/.claude/agents/.
# Это требует, чтобы обёртка пробрасывала "$@" — проверить: ./scripts/check-wrapper.sh

set -euo pipefail

LAUNCH="${MJC_LAUNCH:-claude}"

role="${1:-}"
name="${2:-}"
model="${3:-}"

if [[ -z "$role" || -z "$name" ]]; then
    echo "usage: $0 <implementer|researcher|reviewer|orchestrator> <name> [model]" >&2
    exit 2
fi

case "$role" in
    implementer|orchestrator) default_model="opus" ;;
    researcher|reviewer)      default_model="sonnet" ;;
    *) echo "unknown role: $role" >&2; exit 2 ;;
esac

# Определения ролей ставит плагин в пользовательский скоуп; без него их просто нет.
definition="$HOME/.claude/agents/mjc-${role}.md"
if [[ ! -f "$definition" ]]; then
    echo "нет определения роли: $definition" >&2
    echo "поставьте плагин и запустите IDE — он разложит роли сам," >&2
    echo "либо в IDE: Tools | MetaJetCore | Reinstall Orchestrator Skill" >&2
    exit 2
fi

# Никогда не отдавать агенту API-ключ: он молча уводит сессию с подписки на API-биллинг.
unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN

export CLAUDE_CODE_SESSION_NAME="$name"
export CLAUDE_CODE_AGENT="$role"          # только метка в реестре сессий
export ANTHROPIC_MODEL="${model:-$default_model}"

printf '\033]0;%s\007' "$name"            # заголовок вкладки терминала

echo "role=$role  name=$name  model=$ANTHROPIC_MODEL"

# Режим прав обязателен: без него агент стартует в manual mode и встанет на первом
# запросе прав в вкладке, которую никто не читает.
exec $LAUNCH --agent "mjc-${role}" --permission-mode auto
