#!/usr/bin/env bash
set -euo pipefail

roots=(src common frontend-js)
existing=()
for root in "${roots[@]}"; do
    [[ -d "$root" ]] && existing+=("$root")
done

if [[ ${#existing[@]} -eq 0 ]]; then
    echo "No supported source roots found."
    exit 0
fi

echo "Kotlin files:"
rg --files "${existing[@]}" -g '*.kt' -g '*.kts' | wc -l | tr -d ' '

echo "Kotlin lines:"
rg --files "${existing[@]}" -g '*.kt' -g '*.kts' -0 | xargs -0 wc -l | tail -1

echo "Largest Kotlin files:"
rg --files "${existing[@]}" -g '*.kt' -g '*.kts' -0 |
    xargs -0 wc -l |
    sed '/ total$/d' |
    sort -nr |
    sed -n '1,25p'

echo "Construction hotspots:"
for pattern in 'Settings(' 'AppConfig(' 'PortfolioSnapshot(' 'TradeRecord(' 'runTest {'; do
    count=$({ rg -F "$pattern" "${existing[@]}" -g '*.kt' -g '*.kts' || true; } | wc -l | tr -d ' ')
    printf '%-24s %s\n' "$pattern" "$count"
done
