---
name: review-feedback-resolution
description: >-
  Resolve incoming review feedback (PR comments, code/architecture/security
  review findings, or audits) against repository evidence: disposition each item
  and apply only accepted fixes. Use when a review has returned comments to act
  on. Do not use to find defects (use code-review, security-review, or
  architecture-review); this skill receives findings, it does not generate them.
---

# Review Feedback Resolution

## Authority and boundary

This skill **receives** review output and decides what to do with it. It does not
replace `code-review`, which **finds** defects and produces findings. Keep the two
roles separate:

| Skill | Owns |
| :--- | :--- |
| [code-review](../code-review/SKILL.md) / [security-review](../security-review/SKILL.md) / [architecture-review](../architecture-review/SKILL.md) | Finding defects and producing review findings |
| **review-feedback-resolution** | Receiving findings, evaluating each, resolving accepted items, rejecting unsupported ones |

Use this skill after a review has returned comments — from a PR, a code review, an
architecture review, a security review, or an audit. It is the resolution step,
not the discovery step.

## Freeze the review context

Before judging any comment, record the review point so dispositions stay anchored
to what was actually reviewed:

- **revision** — the commit/SHA the review targeted.
- **branch/diff** — the exact branch and diff range under review.
- **relevant contracts** — public interfaces, specs, schemas, or local rules the
  comment appeals to.
- **applicable tests** — which checks should pass after a change.

For a GitHub PR, record the PR's head SHA and the diff range at review time
(e.g. `gh pr view <n> --json headRefOid,baseRefOid`). If the PR has been
force-pushed or updated since the review, note that the frozen context no
longer matches the current head and re-freeze before dispositioning.

Re-reading the diff at a different revision than the reviewer saw produces wrong
dispositions. Confirm the working tree matches the reviewed revision before acting.

## Disposition every comment independently

Evaluate each comment on its own evidence. Do not let one obviously-wrong comment
poison the rest, and do not accept a batch just because most of it is sound. Assign
exactly one disposition per item:

| Disposition | Meaning |
| :--- | :--- |
| `accepted` | The suggestion is valid and in scope; make the smallest safe change. |
| `rejected-with-evidence` | The suggestion is wrong, redundant, or out of scope; explain why using repository evidence. |
| `already-resolved` | The concern is already handled in the current code or an in-flight change. |
| `duplicate` | It repeats another comment; resolve once and link the pair. |
| `needs-clarification` | The comment is ambiguous or missing the evidence needed to act; ask a precise question. |
| `deferred` | Valid but out of scope for this change; record it as a tracked follow-up. |

### Reconcile dispositions as a set

After assigning independent dispositions, evaluate all `accepted` items together
before implementation. Two individually plausible suggestions may still require
incompatible changes.

- Identify the source-of-truth contract, repository policy, or authorized
  decision that controls the conflict.
- When one suggestion conflicts with that authority, retain the supported item
  and mark the other `rejected-with-evidence`.
- When the controlling contract or intended behavior is genuinely unclear, mark
  the conflicting items `needs-clarification` and do not implement either one.
- Do not invent a hybrid compromise that satisfies neither contract.
- Keep valid but out-of-scope architectural work `deferred`; recording it in the
  report does not authorize creating an issue or another remote follow-up artifact.

Maintain three separated streams and do not blur them:

- **review feedback** — what the reviewer said and your disposition of it;
- **implementation decision** — the change you choose to make;
- **final verification** — the checks that prove the change is safe.

## Resolve accepted items

For each `accepted` item:

- make the **smallest safe change** that addresses the concern;
- **preserve scope boundaries** — do not widen the change to unrelated cleanup;
- run the **appropriate verification** (the applicable tests named in the frozen
  context) and record the result.

For each `rejected-with-evidence` item, state the precise reason with a repository
anchor (file/line, contract, or test) so the rejection is auditable, not an
opinion.

## Side effects and approval boundary

Assessment mode is read-only by default. Do not modify code, reply to reviewers,
update branches, or apply accepted fixes unless the user explicitly requests
implementation of selected accepted items. Resolving whether feedback is valid and
implementing the resolution are separate steps:

1. **evaluate** the review feedback against the frozen context;
2. **decide** whether each item is valid and what disposition it receives;
3. **implement** accepted changes only as a separate, explicitly authorized action.

Preserve the ownership model: `code-review` finds defects, this skill evaluates
incoming feedback, and implementation is a distinct authorized step — not an
automatic consequence of resolving a comment.

## Anti-patterns

- **Do not blindly obey reviewers.** A review comment is an input, not an order.
- **Do not assume reviewers are correct.** Verify the claim against the frozen
  context and current source.
- **Do not expand a localized issue into an architecture rewrite** without evidence.
  A naming nit does not justify restructuring a module.
- **Do not lose rejected items.** Every comment gets a disposition, even "no
  change"; silence is not a resolution.

## Output

Return a resolution report with:

- the frozen review context (revision, branch/diff, contracts, tests);
- a per-comment table: comment, disposition, evidence, action taken or reason;
- the verification result for every applied change;
- a list of `deferred` or `needs-clarification` items still open.

Completion means every comment has a disposition and every accepted change is
verified. It does not mean every comment was applied.
