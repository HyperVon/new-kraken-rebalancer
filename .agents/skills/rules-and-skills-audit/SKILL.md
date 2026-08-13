---
name: rules-and-skills-audit
description: Audit agent rules, skills, and operating guidance for redundancy, conflicts, unclear triggering, stale assumptions, and consolidation opportunities. Use for repository-agnostic or cross-repository reviews of AGENTS.md files, SKILL.md files, harness rules, and agent instructions when asked to rationalize, merge, prune, or improve them. Prefer a repository-provided reviewer for domain-specific content enrichment; use this skill alongside it only when structural consolidation is explicitly requested.
---

# Rules and Skills Audit

Audit relevant guidance before proposing changes. Treat rules as policy and skills as reusable task workflows; do not assume two files are redundant merely because they share a topic.

## Procedure

1. Discover candidate guidance with `rg --files`, including nested `AGENTS.md`, `OPERATING.md`, `CLAUDE.md`, every `SKILL.md`, `.cursorrules`, `.cursor/rules/**/*.mdc`, `.github/copilot-instructions.md`, and guidance under `.codex/` if present. Include other harness-specific instruction files found nearby. Respect nested guidance and determine its scope before comparing it.
2. Build a compact inventory: file, purpose, scope, explicit trigger, dependencies, and notable rules or workflows. Record the files actually read and disclose any candidates skipped.
3. Compare candidates for duplicated instructions or checklists; overlapping triggers; broad workflows that subsume narrow ones; contradictory commands, versions, thresholds, or policy; stale facts or unreachable references; orphaned skills or index entries; and missing boundaries between central rules and task-specific skills. Treat harness projections and deliberate safety reinforcement as intentional when they name a canonical source and remain aligned. Ignore illustrative placeholders inside fenced examples when checking links.
4. For each finding, cite exact paths and headings (or line numbers), classify it as `duplicate`, `merge candidate`, `scope/trigger issue`, `stale/inaccurate`, `conflict`, or `improvement`, and state the evidence. Distinguish true duplication from intentional reinforcement.
5. Rank proposed changes by impact and risk. Prefer focused skills with precise descriptions, shared canonical guidance, and references over repeating policy in every skill.
6. Do not delete, merge, or rewrite existing guidance without explicit approval. If asked to implement approved findings, preserve local conventions, update affected cross-references, and validate each modified skill.

## Optional parallel audit

For a broad guidance tree, use the `rules-and-skills-audit` preset from
`.agents/runtime-router/adapters/kilo/route_subagents.py` for canonical rules/operating norms, domain
skills, harness/projections, and cross-link/index health. Give each worker a
bounded path set. The parent resolves duplicate or conflicting findings and
owns all edits. If route selection is unavailable, stay parent-owned; never
launch a role-only worker.

## Report format

Provide:

- **Inventory summary** — files reviewed and overall health.
- **Findings** — evidence-backed items with affected paths and recommended action.
- **Keep separate** — apparent overlaps justified by distinct scope or audience.
- **Proposed consolidation plan** — ordered, reversible steps; include a migration map for any merge.
- **No-change conclusion** — state this explicitly if no material improvement is supported.

Avoid recommendations based only on file names. Do not suggest cosmetic edits unless they improve triggering, correctness, maintainability, or agent behavior.
