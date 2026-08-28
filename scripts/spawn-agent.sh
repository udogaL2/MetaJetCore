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
#   MJC_ROLE_DELIVERY=flag     (по умолчанию) — добавляет --agent <role>.
#                              Работает, если обёртка пробрасывает "$@".
#                              Проверить: ./scripts/check-wrapper.sh
#
#   MJC_ROLE_DELIVERY=message  — флаг не добавляется; роль придётся выдать первой
#                              строкой сообщения:
#                                "Прочитай .claude/agents/<role>.md — это твоя роль
#                                 на всю сессию, следуй ей."
#                              Список tools при этом НЕ применяется.

set -euo pipefail

LAUNCH="${MJC_LAUNCH:-claude}"
ROLE_DELIVERY="${MJC_ROLE_DELIVERY:-flag}"

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

if [[ ! -f ".claude/agents/${role}.md" ]]; then
    echo "no agent definition: .claude/agents/${role}.md" >&2
    echo "run from the project root" >&2
    exit 2
fi

# Никогда не отдавать агенту API-ключ: он молча уводит сессию с подписки на API-биллинг.
unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN

export CLAUDE_CODE_SESSION_NAME="$name"
export CLAUDE_CODE_AGENT="$role"          # только метка в реестре сессий
export ANTHROPIC_MODEL="${model:-$default_model}"

printf '\033]0;%s\007' "$name"            # заголовок вкладки терминала

echo "role=$role  name=$name  model=$ANTHROPIC_MODEL  delivery=$ROLE_DELIVERY"

if [[ "$ROLE_DELIVERY" == "flag" ]]; then
    exec $LAUNCH --agent "$role"
else
    cat <<MSG

  Режим message: роль флагом не передана. Первой строкой отправь агенту:

      Прочитай .claude/agents/${role}.md — это твоя роль на всю сессию, следуй ей.

MSG
    exec $LAUNCH
fi
