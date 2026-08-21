---
name: security-review
description: >-
  Review a change, repository, service boundary, or agent workflow for
  evidence-backed security risks involving secrets, identity, authorization,
  input handling, data exposure, dependencies, paths, commands, network access,
  or destructive authority. Use for an explicit security review or a scoped
  security concern; do not apply it to an ordinary bug or failing test without a
  security-relevant boundary. Report by default; do not probe external systems.
---

# Security Review

## Contract

- **Input:** the requested review scope, relevant source and configuration,
  trust boundaries, sensitive assets, and the user's authorized test level.
- **Output:** prioritized findings with evidence, impact, exploit preconditions,
  minimal remediation, and verification steps.
- **Owner:** security reasoning at repository and agent-workflow boundaries.
- **Non-goals:** a compliance certification, an unrestricted penetration test,
  generic code review, or a substitute for a product-specific threat model.
- **Side effects:** read-only by default; do not contact external systems,
  access credentials, generate live exploit traffic, or modify files without
  explicit authority.

## Workflow

1. Confirm that security review is the task. If the request only reports an
   ordinary bug, build failure, or failing test without a security-relevant
   boundary, hand it to `systematic-debugging` instead of inventing security
   work.
2. Establish scope and authority. Identify assets, trust boundaries, actors,
   entrypoints, data sensitivity, and what actions are explicitly allowed.
3. Read the source of truth: changed files, callers, configuration, dependency
   manifests, CI, deployment settings, and existing security guidance. Do not
   infer protections from filenames or comments.
4. Trace security-relevant flows end to end:
   - untrusted input to parsing, queries, templates, commands, paths, or output;
   - identity to authentication, authorization, tenancy, and privilege checks;
   - secrets from acquisition to storage, transport, logs, errors, and cleanup;
   - sensitive data across persistence, APIs, caches, telemetry, and exports;
   - dependencies, updates, network egress, and agent authority boundaries.
5. Test only safe local properties that can support a finding: validation,
   permissions, redaction, path containment, dependency metadata, configuration
   parsing, and negative tests. Preserve sensitive values and redact evidence.

   **Safe local verification rules:**
   - Verify parsing and injection boundaries using benign sentinel values (e.g. `' OR '1'='1`, `../etc/passwd` path normalization assertions, `<script>alert(1)</script>` escaping tests in isolated units).
   - Never execute destructive payloads (e.g. `rm -rf`, DROP TABLE, credential exfiltration) even against local test environments.
   - Mock the execution sink (e.g., intercept the generated SQL string or shell command) to verify unescaped characters without executing them.
   - *Note:* The sentinel examples above are test-design guidance, not executable payloads. If the reviewed project uses content scanning tools, recommend placing test fixtures in a scanner-excluded directory.
   - *Reviewer artifact hygiene:* Never place real credentials, tokens, PII, or
     production URLs into test fixtures, eval inputs, or report excerpts you author.
     Use clearly fake placeholders (e.g., `AKIA-EXAMPLE-XXXX`, `user@example.com`).
6. Try to disprove each candidate finding with a minimal safe local check.
   Record the precondition, evidence for and against it, confidence, and any
   missing deployment context. Distinguish a confirmed defect from a question,
   informational supply-chain signal, or hardening suggestion.

   **Prove source-to-sink reachability:**
   Never report a vulnerability based solely on the presence of a sensitive function or sink (e.g., `subprocess.run`, `eval`, `innerHTML`, raw SQL query). A confirmed finding requires proving:
   1. An untrusted source exists (user input, external API, untrusted file).
   2. The data reaches the sensitive sink without sufficient validation, sanitization, parameterization, or type enforcement.
   3. Attacker-controlled parameters can meaningfully alter execution semantics.
   If input is strictly static, internal, or constrained by strong enums/whitelists, classify it as safe or a low-priority defense-in-depth note, never an active vulnerability.
7. Check security-relevant defaults and fail-open paths: fallback credentials,
   permissive authorization, weak crypto or randomness, debug leakage, unsafe
   network egress, and hidden recovery behavior. For dependencies, record
   version-matched advisories and useful provenance signals such as yanked or
   abandoned releases, install scripts, bundled binaries, or publisher
   concentration; do not inflate informational signals into vulnerabilities.
8. Rank each finding by realistic impact, exploitability, affected boundary,
   evidence strength, and remediation cost. Recommend the smallest correction
   and a regression or verification probe.
   Stop when the scope is exhausted or the next test would require external,
   destructive, credentialed, or unauthorized activity.

## Review checklist

- Are authentication and authorization decisions made at every sensitive
  boundary, including indirect or background paths?
- Can untrusted input escape intended query, command, template, path, or
  serialization boundaries?
- Are secrets absent from source, fixtures, logs, errors, artifacts, and URLs?
- Are secret tokens, signatures, and password hashes compared using constant-time comparison functions (`hmac.compare_digest`, `crypto.timingSafeEqual`) to prevent timing side-channel attacks?
- Is cryptographically secure randomness (`secrets`, `crypto.getRandomValues`) used for tokens, nonces, and session IDs instead of pseudo-random generators (`random.random()`, `Math.random()`)?
- In multi-tenant systems, is tenant isolation enforced at the database query layer (e.g. mandatory `tenant_id` filter) rather than relying on application-level routing?
- Can an agent, script, dependency, or CI job gain more authority than the
  user intended?
- **Agent and LLM workflow boundaries:**
  - *Indirect Prompt Injection:* Can untrusted external data (web pages, user uploads, issue comments, email bodies, database records) inject instructions that alter the agent's behavior or override system prompts?
  - *Tool Output Poisoning:* Are tool inputs and outputs treated as untrusted boundaries? Can untrusted tool responses trick the agent into invoking destructive tools with malicious arguments?
  - *Ambient Authority & Confused Deputy:* Does the agent or background worker run with broader privileges than required for the task? Can an unauthenticated caller trigger privileged agent operations?
  - *Sensitive Context Leakage:* Does the agent reflect private files, credentials, or internal system prompts into user-visible outputs, tool arguments, or telemetry logs?
  - *Secrets in Model Context:* Can a secret, token, or credential reach the
    model's context — via a file read, a tool result, a log line, a pasted
    prompt, or a rendered output — and then be echoed, committed, or sent
    onward?
- Are sensitive data access, retention, redaction, and error behavior explicit?
- Are dependency, supply-chain, network, and update assumptions evidenced?
- Do tests demonstrate rejection and fail-closed behavior for the risky cases?
- Is this actually a design-time threat-model request? If so, hand off to
  `threat-modeling` rather than repeating a generic vulnerability checklist.

## Report and stop condition

For each finding provide: severity, exact path and line or behavior, evidence,
affected asset and boundary, impact, preconditions, minimal remediation, and a
verification probe. Include examined areas that were not findings when that
prevents a misleading impression of coverage.

Stop and report an evidence gap when the source, deployment context, or
authorization needed to confirm a claim is unavailable. Never label a review
“secure” merely because static checks found no issue.
