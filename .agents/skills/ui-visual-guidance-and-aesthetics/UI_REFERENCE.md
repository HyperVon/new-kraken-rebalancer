# UI Visual Guidance — detailed reference

Load this file after `SKILL.md` when the task needs concrete visual criteria,
screen-specific rules, responsive acceptance details, or the full design brief
template. `SKILL.md` remains the short, always-relevant contract.

## Visual language

The current direction is **Refined Glass**: deep near-black blue background,
cool-blue translucent surfaces, subtle cyan borders, restrained blur, blue
primary actions, green success, amber warning, red danger/live consequence,
purple secondary data accent, soft elevation, and limited glow.

Use existing tokens and components before adding values. Relevant anchors are
`CssTheme`, `LayoutStyles`, `ComponentStyles`, `FormStyles`, `TableStyles`,
`NavigationStyles`, `History*Styles`, `MediaQueries`, and shared `CssClass`
constants. Current type roles are Outfit for headings/major figures, Inter for
application UI, and Roboto Mono only where machine-oriented alignment helps.

Do not add isolated colors, radii, shadows, glow strengths, type sizes,
spacing, or animation timings unless the value has a genuinely unique
semantic purpose.

## Detailed visual rules

### Hierarchy and rhythm

- Use the reading order `identity/safety → primary information/task →
  supporting metrics/controls → analysis → secondary information`.
- Within each region label elements Primary, Secondary, Supporting, or Metadata
  and style them accordingly. One dominant element per region is enough.
- Use spacing to communicate `same item < same group < different group <
  different section`; keep a small coherent scale.
- Cards create meaningful grouping. Do not card every metric, form group, table,
  or chart, and avoid nested cards when spacing is sufficient.
- Use size, weight, placement, spacing, and surface prominence before effects.

### Typography and data

- Headings establish structure; keep utility-app heading sizes modest.
- Financial numbers use tabular numerals where alignment matters, consistent
  precision, predictable currency formatting, signs, subdued units, and table
  alignment. Only the most important value should be display-sized.
- Small uppercase labels are for short metadata, not whole screens.
- Monospace is for timestamps, identifiers, and other precise machine metadata,
  not as decoration.

### Surfaces and color

- Glass must remain readable, separated from its background, and consistent;
  blur/transparency must not obscure content.
- Elevation communicates `page background < passive surface < important or
  interactive surface < temporary overlay`.
- Avoid extreme transparency, competing gradients, thick luminous borders,
  nested glows, heavy background blur, and glow on routine components.
- Semantic roles are blue = normal primary/simulation/application accent,
  green = success/positive, amber = caution/dry run/attention, red =
  danger/failure/live consequence, muted slate = secondary/inactive.
- Never use semantic color decoratively or as the only state cue. Pair it with
  a label, icon, sign, shape, position, or status text.

### Actions and forms

- Use one clear primary action per region; secondary actions remain visible but
  do not compete. Destructive actions explain consequence and use confirmation
  when the action is consequential or irreversible.
- Define default, hover, active, focus-visible, and disabled states; never make
  interactivity discoverable only through hover.
- Settings are organized by user intent: section, short explanation, related
  controls, and nearby consequence/validation. Preserve values on validation
  failure and distinguish required from optional fields.
- Simulation and Dry Run need consequence-aware treatment and retain the
  existing safety-card concept unless an explicit redesign improves clarity.

### Dashboard and history

Dashboard priority is: mode/identity, portfolio value/condition, freshness,
cash/crypto allocation and deviation, asset detail, recent activity, metadata.
Keep total portfolio value the hero; do not add generic widgets or equal KPI
cards merely to fill space.

History priority is summary, trends/comparisons, chart analysis, then trade
evidence. Charts and tables should answer different questions rather than
duplicate the same information in large forms.

Financial tables use right-aligned numeric columns, tabular numerals,
consistent decimals, left-aligned identifiers, quiet separators, clear headers,
adequate row height, and restrained status badges. Use badges for categorical
states that need attention; routine success rows should stay quiet.

Charts must state the decision or question they support. Prioritize series,
axis context, comparison, annotation, and compact controls over decorative
chrome. Distinguish series beyond subtle color changes, keep zoom/range/
comparison controls compact, and avoid reanimating whole charts on SSE updates.

### Responsive contract

Use the existing 640px, 768px, 1024px, and approximately 1280px breakpoints.
Evaluate narrow phone, tablet, 1280px laptop, 1440px desktop, and wide desktop.
For repeatable evidence use:
[`capture_screenshots.py`](../docs-screenshot-refresh/scripts/capture_screenshots.py)
profiles `phone` 390px, `tablet` 768px, `laptop` 1280px, `desktop` 1440px,
and `wide` 1920px. Name the profile supporting each visual claim.

As width decreases, preserve meaning, actions, labels, layout reflow, then
reduce decorative spacing, and hide only genuinely secondary information.
Never hide safety mode or critical state. Do not solve width with tiny text,
clipped labels, overlapping controls, unreadable charts, or accidental page
scrolling. Contained horizontal scrolling is acceptable for dense tables only
when responsible reflow is impossible and the behavior is obvious.

Current header behavior is intentional: phone keeps brand and mode on one
identity row, then stream health and a labeled loop action on a predictable
second row; tablet keeps identity/stream together and the loop action in an
intentional trailing row; laptop/desktop keeps identity left and Stream, loop
state, and loop action right as one operational cluster, with navigation below.
Settings Save Configuration remains independently right-aligned. Pause and
Resume are neutral labeled actions; danger styling is reserved for actual risk.

Mobile trade rows are contained detail cards with a time/pair/side header,
readable economics, and a distinct status line; do not use page-level
horizontal scrolling or long raw timestamps. Recent Activity remains
cycle-grouped but uses lightweight feed rows, at most one concise informational
note per cycle, and keeps View all history. Chart headers keep title left and
controls in a stable trailing slot; stack deliberately only below phone width.
Hide a scrubber while it cannot pan a zoomed chart. Settings Remove actions are
quiet ghost controls until hover.

For screenshots, use DPR 2 for fidelity but display the image at an explicit
width. README/User Guide should use linked HTML `<img width="...">`; keep the
README gallery curated and put the full five-profile gallery in the User Guide.
Verify both documents at normal 100% browser zoom.

### Accessibility, motion, and states

WCAG 2.2 AA is the floor. Require semantic HTML, keyboard operation, logical
tab order, visible focus, sufficient text and control contrast, adequate
pointer targets, responsive reflow, accessible names and labels, non-color
state cues, useful errors, reduced-motion support, and no keyboard traps.
Prefer native `button`, `a`, `input`, `select`, `label`, `table`, `th`, `nav`,
`header`, and `main`; use ARIA only for missing semantics. In this repository,
do not add new ARIA attributes, roles, or accessibility-only copy unless the
user requests accessibility work or the feature cannot function correctly
without them.

Do not announce every SSE refresh. Announce meaningful stale/disconnected,
completion, validation, or important status changes. Motion should explain a
state change, relationship, appearance, confirmation, or hierarchy; keep it
short and subtle, avoid looping decoration, parallax, bouncing notices,
animated gradients, and repeated pulses. Preserve `prefers-reduced-motion`.

For every significant region consider loading, empty, success, partial, stale,
error, and disabled. Keep placeholders stable, avoid layout jumps, explain
what an empty state means and what happens next, explain what failed and what
remains safe, and distinguish stale data from failed data while preserving the
last usable information when safe.

Use concise operational copy such as “Portfolio within tolerance”, “Last
updated 18s ago”, “Simulation”, “Dry Run”, “Live Trading”, “No trades this
cycle”, “Reset zoom”, and “Save settings”. Avoid marketing language. Use one
consistent icon family, accompany unfamiliar icons with text, and never make an
icon the sole indication of a critical state.

## Project invariants

Unless the user explicitly approves a redesign:

- retain the persistent trading-mode plate on Dashboard, Settings, and History;
- keep STREAM / STALE separate from trading mode;
- keep total portfolio value as Dashboard hero and Cash/Crypto subordinate;
- group recent activity by rebalance cycle;
- make Simulation/Dry Run controls consequence-aware;
- keep History controls compact;
- keep normal trade success quieter than exceptional statuses;
- suppress zero-value table noise where an em dash communicates better; and
- preserve the dark Refined Glass direction across pages.

## Anti-patterns

Reject or challenge giant hero whitespace, floating neon/glow decoration,
gradient text without hierarchy, every element in a rounded card, meaningless
sparkles, excessive glassmorphism, excessive pills/badges, too many accents,
decorative use of red/green, animation on static financial metrics, icon soup,
hidden safety state at laptop widths, one-viewport-only optimization, and
generic dashboard patterns that do not answer an operator question.

## Component questions and rubric

Before creating a component, answer: what user question it answers; why it
deserves space; which existing pattern applies; its primary state; loading,
empty, and error states; keyboard behavior; reflow; semantic color; any new
token; and what can be removed instead. If several answers are unclear, stop
before styling.

For substantial designs score 0–2 for hierarchy, information density,
consistency, state clarity, safety clarity, typography, color semantics,
responsive behavior, accessibility, interaction feedback, visual restraint,
and data readability. Do not recommend a design with 0 in state clarity,
safety clarity, responsive behavior, accessibility, or data readability.

## Detailed handoff template

```markdown
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
```

Pass the brief to `ui-visual-review` when the question requires judging the
running UI; that skill owns booting, screenshots, image reading, and findings.
Pass it to `ui-visual-implement` after approval; that skill owns source edits
and fresh screenshot verification. A fundamental replacement of Refined Glass
is a design-system migration: define palette, typography, surfaces, radii,
elevation, states, semantic colors, density, responsive rules, and migration
sequence before local edits.
