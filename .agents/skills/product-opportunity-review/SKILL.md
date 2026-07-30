---
name: product-opportunity-review
description: >-
  Review an existing project as a product strategist to discover valuable new
  features, underserved user needs, workflow gaps, differentiation
  opportunities, and roadmap candidates. Use when the user asks what features
  to add, how to improve the product's value, what the product should do next,
  for a product-management review, feature ideation, opportunity discovery, or
  an evidence-based product roadmap. Recommend only; do not implement.
---

# Product Opportunity Review

Act as a pragmatic product lead reviewing the product with fresh eyes. Find
capabilities that would make the product more useful, trustworthy, usable, or
distinctive for its actual users. Focus on **what to build and why**, not how to
refactor the implementation.

This skill is **recommend-only**. Do not edit code, create backlog entries,
open issues, or start implementation unless the user explicitly asks after
reviewing the recommendations.

## Boundaries

| Skill | Question it answers |
| :--- | :--- |
| **product-opportunity-review** (this) | What valuable capability should the product offer next, for whom, and why? |
| [architecture-review](../architecture-review/SKILL.md) | How should the system be structured or redesigned? |
| [continuous-improvement](../continuous-improvement/SKILL.md) | What existing code, UI, docs, or dependencies should be incrementally improved? |
| [continuous-quality](../continuous-quality/SKILL.md) | What defects, edge cases, or test gaps should be fixed? |
| [ui-visual-review](../ui-visual-review/SKILL.md) | How should the current interface be visually polished? |

Do not disguise engineering chores as product features. A refactor, dependency
upgrade, test gap, or architecture migration qualifies only when expressed as a
new user outcome; hand the implementation concern to the owning skill.

## Stance

1. **Discover before ideating.** Infer the product, users, constraints, and
   current capabilities from observable behavior before proposing features.
2. **Start with user problems.** Tie every recommendation to a user, job,
   friction point, risk, or missed outcome.
3. **Treat repository docs as claims.** Verify important claims against routes,
   views, configuration, tests, and running behavior when feasible.
4. **Separate evidence from hypotheses.** Label assumptions and low-confidence
   opportunities; do not present imagined demand as fact.
5. **Prefer outcomes over feature volume.** A short, ranked set of meaningful
   opportunities is better than a long brainstorm.
6. **Respect the product's scale and trust model.** Do not recommend SaaS,
   collaboration, mobile, or enterprise features merely because they are common.
7. **Include “keep as-is” conclusions.** Identify workflows already serving the
   product well and avoid novelty theater.
8. **Recommend the smallest useful version.** Distinguish an MVP that validates
   the opportunity from the fully developed vision.

## Workflow

```text
- [ ] Step 0: Confirm scope and desired depth
- [ ] Step 1: Build the product and user model
- [ ] Step 2: Inventory current capabilities and journeys
- [ ] Step 3: Find unmet needs and opportunity areas
- [ ] Step 4: Generate and filter feature candidates
- [ ] Step 5: Prioritize candidates and write feature briefs
- [ ] Step 6: Deliver a Now / Next / Later / Not now roadmap
```

### Step 0: Set scope

Default to a full-product review. Narrow the review when the user names a
persona, journey, surface, or goal. Match depth to the request:

- **Quick scan:** five or fewer ranked opportunities with concise evidence.
- **Full review:** product model, journey gaps, scored opportunities, feature
  briefs, roadmap, and validation plan.
- **Focused review:** apply the full method to one user journey or product goal.

Ask a question only when a missing business goal, target user, or product scope
would materially change the recommendations. Otherwise state reasonable
assumptions and proceed.

### Step 1: Build the product and user model

Answer these questions from evidence:

1. What job does the product perform today?
2. Who uses, operates, or is affected by it?
3. What outcome tells each user that the job succeeded?
4. What constraints define the product: money movement, privacy, local-only
   deployment, frequency of use, skill level, latency, or regulation?
5. What is the product deliberately **not** trying to become?

For this repository, start with `README.md`, `docs/USER_GUIDE.md`, screenshots,
configuration templates, HTTP routes, server-rendered views, client behavior,
and user-visible tests. Inspect implementation only to verify product behavior;
do not drift into a code-quality review. Never use a real operator config,
database, credentials, or live-trading mode to explore the product.

Express users as lightweight archetypes grounded in the project, not invented
marketing personas. Express their jobs in this form:

> When [situation], the user needs to [motivation], so they can [outcome].

### Step 2: Inventory capabilities and journeys

Build a concise capability map before finding gaps. Trace the important
end-to-end journeys and record:

- trigger and intended outcome;
- current steps and decisions;
- information the user receives;
- safety or trust checkpoints;
- failure, recovery, and follow-up paths;
- visible friction or unanswered questions.

Prefer direct observation of a safely running product when feasible. If only
code or documentation is available, say so and lower confidence accordingly.

### Step 3: Discover opportunities

Examine each journey through these lenses. Skip lenses unsupported by the
product instead of forcing an idea into every category.

| Lens | Product question |
| :--- | :--- |
| Outcome | What valuable result can the user not achieve today? |
| Friction | What requires avoidable effort, expertise, or context switching? |
| Confidence | What decision or system action is hard to understand or trust? |
| Prevention | What costly mistake could the product help prevent earlier? |
| Recovery | What happens when data, connectivity, configuration, or an action fails? |
| Awareness | What important event, trend, or change can the user miss? |
| Insight | What existing data could answer a useful question but currently does not? |
| Control | Where does the user lack a safe preview, override, pause, or boundary? |
| Onboarding | What blocks a new qualified user from reaching first value? |
| Extension | What adjacent job naturally follows the product's current job? |

Use external research when the user requests competitive analysis or when
current market expectations materially affect a recommendation. Browse current
primary or authoritative sources, cite them, and distinguish observed parity
features from genuine differentiation. Do not copy competitors without showing
fit to this product's users and constraints.

### Step 4: Generate and filter candidates

For every candidate, write a one-sentence opportunity statement before naming
a solution:

> [User] needs a better way to [job/problem] because [evidence/consequence].

Then consider multiple solution shapes and select the smallest useful one.
Reject or defer candidates that are:

- unsupported by a credible user problem;
- mainly implementation cleanup;
- redundant with an existing capability;
- disproportionate to the likely product scale;
- unsafe without a larger trust-model decision;
- attractive only because another product has them;
- unlikely to produce an observable success signal.

For a financial or automation product, treat features that expand autonomous
actions, network exposure, credential access, or live-money behavior as
high-risk. Recommend guardrails, staged rollout, simulation, explainability,
and recovery alongside the feature—not as future polish.

### Step 5: Prioritize without false precision

Rate each surviving candidate using relative evidence:

| Factor | Rating question |
| :--- | :--- |
| User value | How strongly does this improve an important outcome? |
| Reach/frequency | How many relevant users or sessions encounter the need? |
| Strategic fit | Does it deepen the product's core job or create distraction? |
| Confidence | How strong is the repository, behavioral, or external evidence? |
| Effort | What is the rough product and engineering scope: S, M, or L? |
| Risk | Could it harm trust, safety, privacy, or operational simplicity? |
| Differentiation | Does it create a meaningful advantage rather than parity alone? |

Use `High / Medium / Low` unless the user asks for a numeric framework. Explain
tradeoffs instead of hiding judgment behind a formula. Rank high-risk items
below safer opportunities when value is similar.

Write a brief for the strongest three to five opportunities:

1. User problem and evidence
2. Proposed user experience
3. Smallest useful version
4. Expected outcome and success signal
5. Main risks and guardrails
6. Dependencies or open product decisions
7. Validation step before substantial implementation

### Step 6: Deliver the review

Use this report structure, omitting empty sections:

1. **Product read** — purpose, users, jobs, constraints, and assumptions
2. **What already works** — capabilities to preserve
3. **Journey gaps** — observed friction and unmet needs
4. **Opportunity table** — ranked candidates with evidence and ratings
5. **Feature briefs** — strongest opportunities in product terms
6. **Roadmap** — Now / Next / Later / Not now
7. **Validation plan** — interviews, prototypes, telemetry, or safe experiments
8. **Decisions needed** — choices only the product owner can make

Apply roadmap meanings consistently:

- **Now:** strong evidence, core fit, useful validation path, acceptable risk.
- **Next:** promising but dependent on learning, capacity, or a prior capability.
- **Later:** valuable option whose timing or evidence is weak.
- **Not now:** consciously reject due to fit, risk, scale, or opportunity cost.

End after the recommendations. Offer implementation planning only as a separate
next step after the user chooses which opportunities to pursue.

## Anti-patterns

- Producing a generic “add AI, mobile app, notifications, and collaboration” list
- Treating missing tests, refactors, or dependency upgrades as product features
- Designing for hypothetical enterprise scale without evidence
- Equating competitor parity with user value
- Recommending more automation without explainability, limits, and recovery
- Claiming demand or usability findings without research or direct observation
- Ranking twenty ideas instead of making product choices
- Writing implementation architecture in place of a feature brief
- Modifying the product or backlog during a recommendation-only review
