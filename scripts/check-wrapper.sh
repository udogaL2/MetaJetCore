#!/usr/bin/env bash
# Проверяет, пробрасывает ли команда запуска аргументы в claude.
#
#   ./scripts/check-wrapper.sh              # проверяет `claude`
#   MJC_LAUNCH=my-claude ./scripts/check-wrapper.sh
#
# От результата зависит режим передачи роли (docs/ARCHITECTURE.md §4.0):
#   пробрасывает  → режим flag,    роль через --agent, работает ограничение tools
#   не пробрасывает → режим message, роль текстом, tools не применяются
#
# Починка обёртки — одна строка: `exec claude "$@"` вместо `claude`.

set -uo pipefail

LAUNCH="${MJC_LAUNCH:-claude}"

echo "launch command: $LAUNCH"

out="$(timeout 30 $LAUNCH --version 2>&1 </dev/null || true)"

if [[ "$out" =~ [0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo "результат: аргументы ПРОБРАСЫВАЮТСЯ  ($out)"
    echo "режим: flag  — можно использовать --agent, --session-id, --resume"
    exit 0
fi

echo "результат: аргументы НЕ пробрасываются"
echo "вывод был: ${out:-<пусто или таймаут>}"
echo
echo "режим: message — роль придётся выдавать текстом, ограничение tools не применится"
echo "почини обёртку: замени вызов 'claude' на 'exec claude \"\$@\"'"
exit 1
