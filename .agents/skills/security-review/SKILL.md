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
6. Try to disprove each candidate finding with a minimal safe local check.
   Record the precondition, evidence for and against it, confidence, and any
   missing deployment context. Distinguish a confirmed defect from a question,
   informational supply-chain signal, or hardening suggestion.
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
- Can an agent, script, dependency, or CI job gain more authority than the
  user intended?
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
