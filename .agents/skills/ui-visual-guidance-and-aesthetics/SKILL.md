---
name: ui-visual-guidance-and-aesthetics
description: >-
  Define the visual design direction, hierarchy, aesthetics, interaction quality,
  responsive behavior, accessibility expectations, and component consistency
  for Kraken Rebalancer UI work. Use when deciding how a new or changed UI
  should look, creating a visual design brief, evaluating whether proposed UI
  follows modern Web UI principles, or supplying design criteria to
  ui-visual-review or ui-visual-implement. This skill defines what good looks
  like; it does not capture screenshots or implement UI code.
---

# UI Visual Guidance & Aesthetics

Define what good looks like for the Kraken Rebalancer UI. This is a financial
control surface, not a marketing site or generic SaaS dashboard. Optimize for
clarity, trust, operational awareness, safety, scanning, consistency,
accessibility, and restrained polish—in that order.

## Load boundary

Read [UI_REFERENCE.md](UI_REFERENCE.md) when the task needs detailed visual
rules, screen-specific acceptance criteria, the responsive/documentation
contract, the full accessibility and state catalog, or the design-brief
template. Keep this core in the initial context; load the reference on demand.

This skill defines visual intent and acceptance criteria. It does not replace
[ui-visual-review](../ui-visual-review/SKILL.md), which owns running and
inspecting the UI, or [ui-visual-implement](../ui-visual-implement/SKILL.md),
which owns source edits and screenshot verification.

## Boundaries

| Skill | Owns |
| :--- | :--- |
| ui-visual-guidance-and-aesthetics | Design principles, hierarchy, aesthetics, decisions, acceptance criteria |
| ui-visual-review | Actual UI boot, screenshots, visual critique |
| ui-visual-implement | Approved visual implementation and screenshot verification |
| ui-manual-qa | Functional interaction and browser QA |
| ktor-html-views | SSR markup, HTMX, and CSS implementation |
| frontend-js-development | Charts, client DOM behavior, and Kotlin/JS |
| user-guide | End-user documentation and explanatory copy |

## Non-negotiable safety and quality contract

- Keep the persistent trading-mode plate on Dashboard, Settings, and History.
- Keep SIMULATION, DRY RUN, and LIVE TRADING distinct and obvious.
- Keep STREAM / STALE separate from trading mode; freshness is not a trading
  mode.
- Preserve the total portfolio value as the Dashboard hero and keep
  Cash/Crypto allocation subordinate unless an explicit redesign is approved.
- Keep normal operation calm; reserve strong emphasis for deviations, warnings,
  failures, stale data, destructive actions, and especially LIVE TRADING.
- Never rely on color alone for a critical state. Use labels, signs, shapes,
  icons, positions, or status text too.
- Treat WCAG 2.2 AA as the floor. Preserve keyboard/focus behavior,
  reduced-motion support, readable contrast, and native semantics.
- Do not introduce new ARIA attributes, roles, or accessibility-only copy
  unless accessibility work is requested or the feature cannot function
  correctly without it.
- Do not hide safety state or critical actions at narrow widths.
- Reuse the dark Refined Glass direction and existing design tokens unless the
  user explicitly approves a design-system migration.

Repeated safety guidance is intentional; do not remove it merely because a
neighboring implementation or QA skill also mentions the boundary.

## Fast design decision model

For every prominent element, ask whether it answers at least one operator
question:

1. What is the portfolio worth and what changed?
2. Is anything wrong, outside tolerance, stale, or unsafe?
3. What did the rebalancer do and what will it do?
4. What mode is active?
5. What action is available?

If an element consumes significant attention without answering one, remove it,
reduce it, or justify its role.

Use this priority before styling:

    identity and safety
      -> primary information or task
      -> supporting metrics and controls
      -> detailed analysis
      -> secondary metadata

Within a region identify Primary, Secondary, Supporting, and Metadata. Use
hierarchy, spacing, typography, and placement before decorative effects. One
dominant element per region is enough.

## Project baseline

The current visual language is Refined Glass: dark near-black blue, cool-blue
translucent surfaces, subtle cyan borders, restrained blur, blue primary
actions, green success, amber warning, red danger/live consequence, muted
secondary data, soft elevation, and limited glow.

Use existing CssTheme, LayoutStyles, ComponentStyles, FormStyles, TableStyles,
NavigationStyles, History styles, MediaQueries, and shared CssClass constants
before inventing values. The detailed reference lists typography roles,
surface rules, screen guidance, breakpoints, screenshot profiles, and
repository-specific responsive decisions.

## Workflow

For a request such as “modernize this page”, “design this panel”, “improve the
design system”, or “evaluate this UI direction”:

1. Identify the user task, operational importance, safety sensitivity, and
   whether the change is local or design-system-wide.
2. Read the relevant current view, CSS/theme tokens, responsive rules, shared
   classes, and client rendering code. Do not design from generic memory.
3. Load UI_REFERENCE.md for the concrete rules needed by the task.
4. Choose an existing pattern to reuse, extend, or intentionally replace.
5. Establish primary/secondary/supporting/metadata hierarchy before colors,
   shadows, radii, or effects.
6. Define default, hover, active, focus, disabled, loading, empty, partial,
   stale, error, and success states, plus narrow and wide behavior.
7. Apply the rubric below; resolve any critical category scored zero.
8. Produce the brief and hand off to the owning review or implementation skill.

## Visual quality rubric

For substantial designs, score 0–2 for hierarchy, information density,
consistency, state clarity, safety clarity, typography, color semantics,
responsive behavior, accessibility, interaction feedback, visual restraint, and
data readability.

Do not recommend a design with 0 in state clarity, safety clarity, responsive
behavior, accessibility, or data readability. A visually attractive design
with one of those failures is unsuccessful.

## Design brief

When this is the primary skill, return concrete, observable criteria:

    # Visual design brief
    ## Goal
    ## Existing pattern
    ## Hierarchy
    ## Layout
    ## Component treatment
    ## States
    ## Color & emphasis
    ## Accessibility
    ## Responsive behavior
    ## Remove / simplify
    ## Acceptance criteria

The brief must say what to retain, change, remove, and verify. “Make it cleaner”
is insufficient; name the grouping, ordering, visual emphasis, state behavior,
responsive behavior, and acceptance evidence.

## Handoff

- Use ui-visual-review when the question requires judging the rendered UI.
  Provide the brief; that skill owns booting, screenshots, image reading, and
  findings.
- Use ui-visual-implement after a direction is approved or an implementation
  brief is explicit. It owns source changes and fresh screenshot verification.
- Use ktor-html-views or frontend-js-development for implementation details
  within their respective boundaries.
- Treat a fundamental replacement of Refined Glass as a design-system
  migration. Define palette, typography, surfaces, radii, elevation, semantic
  colors, density, responsive rules, and migration sequence before local edits.

## Completion checklist

- [ ] The design answers an operational user question.
- [ ] Hierarchy and the primary element are explicit.
- [ ] Existing tokens/components were considered first.
- [ ] Mode and data-freshness states remain distinct and obvious.
- [ ] Semantic color retains its meaning and is not the only cue.
- [ ] Loading, empty, partial, stale, error, and success states were considered.
- [ ] Keyboard, focus, semantics, contrast, motion, and target size are defined.
- [ ] Phone, tablet, laptop, desktop, and wide behavior was considered as needed.
- [ ] Decorative cards, badges, icons, glow, and animation were challenged.
- [ ] Concrete acceptance criteria were handed to the appropriate downstream skill.
