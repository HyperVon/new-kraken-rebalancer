# Shell Non-Interactive Strategy

<!-- Canonical base; this file covers both OpenCode and Kilo shell guidance. -->

**Context:** OpenCode's shell environment is strictly **non-interactive**. It lacks a TTY/PTY, meaning any command that waits for user input, confirmation, or launches a UI (editor/pager) will hang indefinitely and timeout.

**Goal:** Use non-interactive commands for authorized work while preserving the
same scope checks, confirmation boundaries, and host-verification defaults as an
interactive session.

## Cognitive & Behavioral Standards

The shell has no TTY, so commands that wait for input or launch a UI cannot be
used. Non-interactive execution does not grant permission to widen a command's
scope, suppress a safety check, or confirm an unreviewed action.

**Goal:** Complete clear, authorized work unattended and ask the user when scope,
authorization, or an irreversible choice is unresolved.

**Key Behaviors:**

1. **Process Continuity:**
   - Continue after tool output when the next action is within the user's request.
   - Stop and ask when a required decision or destructive authorization is missing.
   - Use bounded waits for commands that may hang; do not auto-answer unknown prompts.

2. **Scoped Non-Interactive Flags:**
   - Use a command-specific non-interactive option only for a reviewed, authorized operation.
   - Never add `--force`, `-f`, `--yes`, or `-y` merely to suppress a confirmation.

3. **Environment Rigor:**
   - Treat the shell as headless and use bounded commands with explicit inputs where appropriate.
   - Preserve SSH host-key verification and fail closed when trusted host information is absent.

## 1. Core Mandates

1. **Assume `CI=true`**: Act as if running in a headless CI/CD pipeline.
2. **No Editors/Pagers**: `vim`, `nano`, `less`, `more`, `man` are BANNED.
3. **Confirm scope before changes**: inspect paths and targets; do not bypass a destructive confirmation.
4. **Use Tools**: Prefer `Read`/`Write`/`Edit` tools over shell manipulation (`sed`, `echo`, `cat`).
5. **No Interactive Modes**: Never use `-i` or `-p` flags that require user input.

## 2. Environment Variables (Auto-Set)

These environment variables help prevent interactive prompts:

| Variable | Value | Purpose |
| ---------- | ------- | --------- |
| `CI` | `true` | General CI detection |
| `DEBIAN_FRONTEND` | `noninteractive` | Apt/dpkg prompts |
| `GIT_TERMINAL_PROMPT` | `0` | Git auth prompts |
| `GIT_EDITOR` | `true` | Block git editor |
| `GIT_PAGER` | `cat` | Disable git pager |
| `PAGER` | `cat` | Disable system pager |
| `GCM_INTERACTIVE` | `never` | Git credential manager |
| `HOMEBREW_NO_AUTO_UPDATE` | `1` | Homebrew updates |
| `PIP_NO_INPUT` | `1` | Pip prompts |

## 3. Command Reference

### Package Managers

| Operation | Non-interactive approach |
| ---------- | ------------------------ |
| **NPM in a locked repo** | `npm ci` |
| **Yarn** | `yarn install --non-interactive` when supported by the installed Yarn version |
| **Pip** | `pip install --no-input <package>` for an explicitly authorized install |
| **APT** | Use `apt-get install -y <package>` only for a specifically authorized package install |
| **Homebrew** | `HOMEBREW_NO_AUTO_UPDATE=1 brew install <package>` only for an authorized install |

Avoid initialization or bulk-upgrade commands unless they are explicitly part of
the task. Do not use a blanket yes setting to answer prompts.

### Git Operations

| Action | Non-interactive approach |
| ------ | ------------------------ |
| **Commit** | `git commit -m "msg"` only when committing is authorized |
| **Merge / pull** | Use `--no-edit` only when the operation is authorized and its scope is clear |
| **Rebase** | `git rebase <branch>` only when authorized; do not use interactive rebase |
| **Stage** | `git add <explicit-path>`; inspect the staged diff before committing |
| **Log** | `git --no-pager log -n 10` |
| **Diff** | `git --no-pager diff` |

### System & Files

| Tool | Safe non-interactive guidance |
| ---- | ----------------------------- |
| **RM / CP / MV** | Review exact paths; do not add force flags to bypass overwrite or deletion checks |
| **Unzip** | Avoid overwrite options unless replacing those exact files is authorized |
| **Tar** | Extract only into a reviewed destination |
| **SSH / SCP** | Use `-o BatchMode=yes -o StrictHostKeyChecking=yes`; require a trusted `known_hosts` entry |
| **Curl** | `curl -fsSL <url>` for an explicitly requested fetch |
| **Wget** | `wget -q <url>` for an explicitly requested fetch |

### Docker

| Action | Interactive (BAD) | Non-Interactive (GOOD) |
| -------- | ------------------- | ------------------------ |
| **Run** | `docker run -it image` | `docker run image` |
| **Exec** | `docker exec -it container bash` | `docker exec container cmd` |
| **Build** | `docker build .` | `docker build --progress=plain .` |
| **Compose** | `docker-compose up` | `docker-compose up -d` |

### Python/Node REPLs

| Tool | Interactive (BAD) | Non-Interactive (GOOD) |
| ------ | ------------------- | ------------------------ |
| **Python** | `python` | `python -c "code"` or `python script.py` |
| **Node** | `node` | `node -e "code"` or `node script.js` |
| **IPython** | `ipython` | Never use - always `python -c` |

## 4. Banned Commands (Will Always Hang)

These commands **will hang indefinitely** - never use them:

- **Editors**: `vim`, `vi`, `nano`, `emacs`, `pico`, `ed`
- **Pagers**: `less`, `more`, `most`, `pg`
- **Manual pages**: `man`
- **Interactive git**: `git add -p`, `git rebase -i`, `git commit` (without -m)
- **REPLs**: `python`, `node`, `irb`, `ghci` (without script/command)
- **Interactive shells**: `bash -i`, `zsh -i`

## 5. Handling Prompts

When a command has no suitable non-interactive option, do not feed blind `yes`
responses or credentials to it. Use a heredoc only when the exact inputs and
effects are understood and authorized:

```bash
./configure.sh <<EOF
option1
option2
EOF
```

If an unavoidable confirmation controls a destructive or privileged action, stop
and ask rather than bypassing it.

### Timeout

```bash
timeout 30 ./potentially_hanging_script.sh
```

## 6. Best Practices

1. Inspect command targets and side effects before execution.
2. Use `--help` or official documentation to find supported non-interactive options; do not assume force/yes flags are safe.
3. Prefer OpenCode tools over shell commands for file operations.
4. Set a timeout for commands that might unexpectedly block; check and report the actual exit status.

---

## 7. Advanced Instruction Patterns (Cognitive Optimization)

### The Problem: Implicit Constraints

Large Language Models (LLMs) often struggle with:

1. **Negative constraints**: Inverting or ignoring "don't do X" instructions.
2. **Turn termination**: Stopping after tool execution instead of auto-continuing.
3. **Context weighting**: Failing to prioritize authoritative instructions over general knowledge.

### Strategy 1: Explicit Action Framing (BAD vs GOOD)

Explicit examples can clarify intent, but examples for commands that change or
delete state must include authorization and scope constraints. Non-interactive
is not synonymous with safe.

### Strategy 2: Process Continuity

In non-interactive environments, the agent must drive the process forward.

**The Rule:** Continue when the next step is clear and authorized. Ask before
acting when the user's intent or the target scope remains ambiguous.

**Pattern:**

```text
1. Execute command (e.g., git status)
2. Analyze output
3. Explicitly state next step: "Status is clean. Next: I will run tests."
4. Execute next step immediately
```

### Strategy 3: Context Hierarchy

When instructions conflict (e.g., generic docs vs this specific strategy), establish precedence:

1. **Cite the Authority:** "Per shell_strategy.md..."
2. **Follow the Specifics:** Rules in this file override general model training or other documentation.

### Strategy 4: Applying These Patterns Beyond Shell

The cognitive strategies used here (Explicit Action Framing) apply to all coding tasks:

**Instead of:**

```markdown
Do not use logging.getLogger()
Don't create CLI code here
```

**Use:**

```markdown
ALWAYS USE: config.logging_config.get_logger()
USE THIS REPO FOR: API backend only
```

By framing instructions as "Actionable Positive Constraints", you reduce hallucination and improve compliance across all models.

## 8. Context Mode Output Routing

This repository registers the optional Kilo Context Mode plugin in
`.kilo/kilo.json`. When the plugin is available:

1. Use `ctx_batch_execute` for three or more related inspection commands or
   verbose build, test, lint, Git, API, or repository output.
2. Use `ctx_execute` / `ctx_execute_file` to derive concise diagnostics from
   large output, and `ctx_search` to recall indexed results instead of replaying
   raw logs into the conversation.
3. Return exit status, a short summary, relevant failures, and file/line
   references; do not echo full logs when a bounded result is sufficient.
4. Keep exact `Read` output in the active context when an edit needs precise
   source text. Context Mode summarizes data; it does not replace exact edit
   context or final verification.
5. Keep Gradle execution serial per clone. Context Mode reduces output volume;
   it does not make concurrent Gradle builds safe.
6. Never index credentials, secrets, live databases, or private runtime data.
   Use disposable or read-only inputs for database and log analysis.

If Context Mode is unavailable in another harness, use bounded native commands
and continue without assuming the `ctx_*` tools exist. The standard Gradle,
test, and shell safety rules remain authoritative.

## 9. Rehydra-safe user questions (OpenCode)

This project's OpenCode configuration loads `@rehydra/opencode`. A controlled
check in this workspace found that the built-in `functions.question` tool fails
with `Attempted to assign to readonly property` while Rehydra is loaded, but
works after Rehydra is removed and the config reload is confirmed. This is an
observed compatibility issue; it does not establish which component owns the
underlying defect.

Until a compatible OpenCode/Rehydra fix is identified and verified:

1. Ask the user directly in ordinary assistant messages; do not call
   `functions.question`.
2. Keep Rehydra enabled during ordinary work to preserve its PII/secret
   protection. Change its configuration only for a user-requested, controlled
   diagnostic, then restore it immediately.
3. Do not assume MCP Code Mode fixes this path: it routes configured MCP
   servers, while `functions.question` is a built-in tool.
