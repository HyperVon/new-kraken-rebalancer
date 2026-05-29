## Description

Briefly describe what this PR does and why.

Fixes # (issue)

## Type of Change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to
  change)
- [ ] Refactor / code cleanup
- [ ] Documentation update
- [ ] CI / build configuration change

## Testing

Describe how you tested this change:

- [ ] Tested in **dry-run mode** (`dryRun: true`) — no live orders placed
- [ ] Tested with **live trading** (if applicable, describe the scenario)
- [ ] Unit tests added or updated
- [ ] Existing tests pass (`./gradlew test`)

## Checklist

- [ ] My code follows the existing style and conventions of this project
- [ ] I have reviewed my own diff and removed any debug code or unnecessary
  changes
- [ ] No API keys, credentials, or sensitive data are included in this PR
- [ ] The `rebalancer-config.json` file is **not** committed (it is in
  `.gitignore`)
- [ ] I have updated documentation if needed (README, ALGORITHM.md, inline
  comments)
- [ ] Any new configuration fields are reflected in
  `rebalancer-config-template.json`

## Notes for Reviewer

Anything the reviewer should pay particular attention to, known limitations, or
follow-up work planned:
