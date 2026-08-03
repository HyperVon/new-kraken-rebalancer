# Routed Workflow Orchestration

The project has a route-enforcing subagent launcher at
`.kilo/model-router/route-subagents`. Use it for broad workflows that define
parallel discovery or review tracks.

## Skill Mapping

Use the matching workflow preset instead of asking the user to create or edit a
manifest:

| Skill | Preset |
| :--- | :--- |
| `documentation-review` | `documentation-review` |
| `autonomous-code-optimizer` | `autonomous-code-optimizer` |
| `continuous-improvement` | `continuous-improvement` |
| `continuous-quality` | `continuous-quality` |
| `adversarial-pr-review` | `adversarial-pr-review` |
| `ai-slop-detector` | `ai-slop-detector` |
| `complex-code-comments` | `complex-code-comments` |
| `dependency-upgrade` | `dependency-upgrade` |
| `architecture-review` | `architecture-review` |
| `rules-and-skills-audit` | `rules-and-skills-audit` |
| `skill-reviewer` | `skill-reviewer` |

## Launch Contract

When a named workflow reaches its parallel-discovery step:

1. Decompose only the independent read-only discovery tracks required by the
   workflow. The preset supplies bounded scopes and specialized agent roles.
2. Plan routes and quota with the parent request as task context:

   ```bash
   ./.kilo/model-router/route-subagents \
      --workflow <preset> \
      --task "<the user's workflow request>" \
      --refresh \
      --run
   ```

3. The command prints the complete route/quota plan before launching. The user's
   request to run the named read-only workflow authorizes this bounded discovery
   fan-out; do not ask the user to hand-edit a manifest. Omit `--run` only when
   the workflow explicitly requires a separate human route decision. Keep
   `adversarial-pr-review` in plan-only mode until its review-specific approval
   gate is satisfied.
4. Keep implementation, edits, backlog integration, Gradle, browser tests, and
   final verification in the parent unless the workflow explicitly says
   otherwise. Never use `--allow-edits` for standard discovery.

The launcher selects an exact provider/model independently for every track,
uses the installed quota plugin, and applies bounded runtime failover. A raw
Kilo `Task` call remains unrouteable and must not be used as a substitute.
