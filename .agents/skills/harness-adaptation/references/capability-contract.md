# Harness capability contract

Use this contract to adapt guidance to a harness that exists now or appears in
the future. A harness profile is evidence for one integration, not a permanent
product allowlist.

## Required questions

### Instruction discovery

- Which repository-local files or directories are discovered automatically?
- Is discovery relative to the repository root, current directory, or both?
- Do nested files override, append to, or replace parent guidance?
- What precedence do user, organization, repository, and nested rules have?
- Are linked or imported files loaded, or merely shown as text?
- Are there context-size or filename constraints relevant to the projection?

### Skill discovery

- Does the harness have a native project-local skill format?
- Which paths, frontmatter fields, and metadata files are recognized?
- Are skill bodies loaded conditionally or always?
- Can a user or agent invoke a skill explicitly?
- Can one physical skill directory be exposed safely without duplicating it?

### Lifecycle and evidence

- Does a file change apply immediately, after a reload, or only in a new task?
- What harmless observation demonstrates that instructions were loaded?
- What harmless prompt demonstrates that a named skill can be discovered?
- Which claims are documented, and which have been exercised locally?

## Normalized profile

Use this shape in an adoption report. Plain Markdown is sufficient; no runtime
registry is required.

```text
harness: <reported product or unknown>
version: <observed version or unknown>
instructions:
  discovery: <paths or manual>
  scope: <root/nested behavior or unknown>
  precedence: <known behavior or unknown>
skills:
  discovery: <paths or none/unknown>
  format: <native/compatible/projected/manual>
composition: <links/imports/projection behavior>
reload: <immediate/reload/new task/unknown>
verification: <safe observable check>
support: RUNTIME_VERIFIED | DOCUMENTED | BEST_EFFORT | UNSUPPORTED
evidence_date: <date>
```

## Unknown-harness fallback

When product-specific behavior is unavailable:

1. ask the active harness to report its project-instruction and reusable-skill
   capabilities;
2. look for primary documentation already available to the user or installed
   help output;
3. keep `AGENTS.md` as the portable human-readable entrypoint;
4. offer a manual prompt directing the harness to read `AGENTS.md`, the
   canonical project rules, operating guidance, and the one matching skill;
5. avoid persistent adapter files until their discovery behavior is known;
6. label the result `BEST_EFFORT` and give the user one verification prompt.

Example manual entrypoint:

```text
Before changing this repository, read AGENTS.md and every repository-local rule
it routes to for the files in scope. If the task matches a project skill, read
that skill before proposing or editing. Tell me which instruction and skill
files you used, and stop if their precedence conflicts.
```

This fallback makes the library usable without pretending that an unknown harness
supports persistent instructions or native skill discovery.

## Projection matrix

| Harness capability | Guidance type | Recommended projection | Example (other adopting projects) |
| :--- | :--- | :--- | :--- |
| Native skill discovery (e.g. Gemini CLI, Claude Code) | Canonical skills (`skills/`) | Native directory discovery without file duplication | `GEMINI.md`, `CLAUDE.md` |
| On-demand rule matching (e.g. Cursor `.mdc` with globs) | File-scoped guidance | Thin `.mdc` pointer with glob triggers pointing to canonical skill | — |
| Static always-injected prompt (e.g. Copilot instructions) | Invariants & routing | Compact table index of available skills, loaded only on task match | `.github/copilot-instructions.md` |

These filenames are illustrative examples from other adopting projects, not
files present in this repository.

Prefer one physical skill owner. Use a supported link only when its behavior is
documented and portable enough for the target; otherwise propose a generated
projection and a drift check.

## Adapter size budget and truncation guardrails

Keep adapter files strictly within harness injection budgets:

- **Size constraint:** Keep root adapter files (`CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`) under 40 lines and under 2 KB.
- **Pointer pattern:** Use pointer syntax (e.g. `@AGENTS.md`) rather than copying rules.
- **No full skills in root adapters:** Never copy full skill bodies into always-loaded entrypoints.
- **Truncation check:** If a harness imposes a strict character limit, project only the universal invariants and a pointer to the on-demand skill index.

## Multi-adapter consistency

When a repository contains multiple harness entrypoints:

- Every adapter must delegate to the same canonical source (the project's `AGENTS.md` and `OPERATING.md` when present).
- Never define harness-specific policy overrides that contradict canonical guidance.
- If an existing adapter has diverged (e.g. contains outdated hardcoded rules), propose replacing its body with a standard thin pointer to the canonical guidance directory.
