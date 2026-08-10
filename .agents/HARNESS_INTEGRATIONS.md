# Optional harness integrations

This file is loaded only when the task uses the named harness integration.
The always-on project rules remain in .agents/AGENTS.md and OPERATING.md.

## Cursor Cloud environment

Cloud VM deltas only. Canonical setup is the README Getting Started section;
the dry-run-and-simulation skill owns mode flags.

- Temurin 25 is the default java; no JAVA_HOME is required for ./gradlew.
- For simulation, copy rebalancer-config-template.json to
  rebalancer-config.json, set simulation=true, and run ./gradlew run in the
  background. Poll /api/health until 200; first boot may block on seeding.
  Prefer an isolated RUN_DIR plus fatJar for UI QA and screenshots.
- ./gradlew build covers Gradle gates; also run markdownlint when editing docs.
- Settings saves restart the rebalance loop; manual config edits require a
  restart. Kotlin, SSR, and frontend changes require restarting ./gradlew run.
- Parse every numeric trading field and allocation row strictly; reject
  missing, non-finite, malformed, or mismatched values rather than defaulting
  or truncating.

## Kilo Agent Manager integration

These are optional Kilo-specific hooks under .kilo/. They are not application
requirements or the canonical project workflow. Other agent tools use the
standard Gradle/README workflows and shared .agents guidance.

- The optional local .kilo/setup-script prepares Gradle classes in the selected
  worktree without reading .env, application config, databases, logs, or
  runtime data.
- .kilo/run-script builds the fat JAR and starts an isolated local simulation
  for Agent Manager Run. It copies only the config template into a private
  temporary directory, forces simulation=true and dryRun=true, and uses a
  temporary database.
- The default port is 8080 and the JVM property kraken.server.port can change
  it. The hook probes 18080–19079, skips occupied ports, and accepts an
  explicit valid unused KILO_AGENT_PORT.
- The hook polls only local /api/health, suppresses build/application output,
  emits only generic readiness/failure, and terminates/reaps only its own
  child process and temporary directory.
- Agent Manager may copy root .env and .env.* into managed worktrees. Keep
  those placeholder-only or use a separate operator-managed secret mechanism.
  Never commit rebalancer-config.json, credentials, private keys, account data,
  or runtime logs.
- Bring worktree changes back with Agent Manager Apply, a normal merge, or a
  PR. Do not use shared git stash or autostash across worktrees.
