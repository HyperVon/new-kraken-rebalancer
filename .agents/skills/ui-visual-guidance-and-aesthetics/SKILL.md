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

Define **what good looks like** for the Kraken Rebalancer UI.

The application is a financial control surface, not a marketing site and not a
generic SaaS dashboard. Optimize first for:

1. **Clarity**
2. **Trust**
3. **Operational awareness**
4. **Safety**
5. **Fast scanning**
6. **Consistency**
7. **Accessibility**
8. **Visual polish**

Aesthetic novelty comes after those goals.

The desired character is:

> **Calm, precise, high-signal financial tooling with restrained premium glass
> styling.**

The UI should feel modern without becoming flashy, noisy, game-like, or
decorative at the expense of comprehension.

---

## Boundaries

| Skill | Owns |
| :--- | :--- |
| **ui-visual-guidance-and-aesthetics** | Design principles, aesthetics, hierarchy, design decisions, visual acceptance criteria |
| [ui-visual-review](../ui-visual-review/SKILL.md) | Capture and visually critique the actual running UI |
| [ui-visual-implement](../ui-visual-implement/SKILL.md) | Implement approved visual changes and verify screenshots |
| [ui-manual-qa](../ui-manual-qa/SKILL.md) | Functional interaction and browser QA |
| [ktor-html-views](../ktor-html-views/SKILL.md) | SSR markup, HTMX, and CSS implementation |
| [frontend-js-development](../frontend-js-development/SKILL.md) | Charts, client DOM behavior, and Kotlin/JS |
| [user-guide](../user-guide/SKILL.md) | End-user documentation and explanatory copy |

This skill may be loaded by the review or implementation skill as their
**visual design contract**.

Do not use this skill as a substitute for inspecting the actual UI when a
visual review is requested.

---

## 1. Design north star

### Operator-first, not decoration-first

Every prominent element should answer at least one of these questions:

- What is my portfolio worth?
- What changed?
- Is anything wrong?
- Is the portfolio outside tolerance?
- What did the rebalancer do?
- What will it do?
- Am I in Simulation, Dry Run, or Live Trading?
- Is the displayed data current?
- What action is available to me?

If an element consumes significant visual attention but answers none of these,
question whether it belongs.

### Calm by default

Normal operation should look calm.

Reserve strong visual emphasis for:

- primary portfolio information,
- meaningful deviations,
- warnings,
- failed operations,
- stale data,
- destructive actions when relevant,
- and especially **LIVE TRADING**.

Do not make the entire page glow merely because the design system supports
glows.

Scarcity gives emphasis meaning.

### Safety state is part of the visual hierarchy

The distinction between:

- **SIMULATION**
- **DRY RUN**
- **LIVE TRADING**

is operationally important and must remain obvious.

Likewise, Dashboard **STREAM / STALE** indicates data-stream health, not trading
mode.

Never visually merge these concepts.

Never allow attractive styling to make LIVE TRADING look routine or ambiguous.

---

## 2. Existing visual language

The current design language is **Refined Glass**.

Default to evolving it rather than replacing it.

Important implementation anchors include:

- `view/css/CssTheme`
- `view/css/LayoutStyles`
- `view/css/ComponentStyles`
- `view/css/FormStyles`
- `view/css/TableStyles`
- `view/css/NavigationStyles`
- `view/css/History*Styles`
- `MediaQueries`
- shared `CssClass` constants

Current typography:

- **Outfit** — headings and major figures
- **Inter** — application UI
- **Roboto Mono** — machine-like data where monospacing improves scanning

Current visual vocabulary includes:

- deep near-black blue background,
- cool-blue translucent surfaces,
- subtle cyan borders,
- restrained glass blur,
- blue primary actions,
- green success,
- amber warning,
- red danger / live-trading consequence,
- purple as a secondary accent or data color,
- soft elevation and limited glow.

### Rule

**Use design tokens before inventing values.**

Prefer modifying or extending `CssTheme` when a visual concept is reusable.

Avoid introducing isolated hard-coded:

- colors,
- border radii,
- shadows,
- glow strengths,
- typography sizes,
- spacing,
- or animation timings

unless the value has a genuinely unique semantic purpose.

---

## 3. Visual hierarchy

Every screen should have a clear reading order.

A useful hierarchy is:

```text
Page identity / safety state
        ↓
Primary information or task
        ↓
Important supporting metrics / controls
        ↓
Detailed analysis
        ↓
Historical / secondary information
```

Avoid treating every card as equally important.

### One dominant element per region

A dashboard full of equally bright cards has no hierarchy.

Within each major section, identify:

- **Primary**
- **Secondary**
- **Supporting**
- **Metadata**

and style accordingly.

Use differences in:

- size,
- weight,
- spacing,
- surface prominence,
- color,
- and placement

before adding decorative effects.

---

## 4. Typography

Typography should make scanning nearly automatic.

### Headings

Use headings to establish structure, not decoration.

Prefer:

- strong page title,
- clear section title,
- modest subsection title,
- quiet metadata.

Avoid excessive heading sizes on a utility application.

### Numeric values

Financial values deserve strong typographic handling.

Use:

- tabular numerals where alignment matters,
- consistent decimal precision,
- predictable currency formatting,
- meaningful sign indicators,
- subdued units,
- and appropriate alignment in tables.

The most important value on a page may be large.

Every value does not need to be large.

### Uppercase

Small uppercase text works well for short metadata and labels.

Do not turn large portions of the interface into uppercase text.

### Monospace

Use monospace selectively for information such as:

- timestamps,
- identifiers,
- precise machine-oriented metadata.

Do not use monospace merely to make the interface look technical.

---

## 5. Spacing and layout rhythm

Whitespace is structural information.

Use spacing to communicate:

```text
same item < same group < different group < different section
```

Elements that belong together should be visually closer than unrelated
elements.

Maintain a small, coherent spacing scale.

Avoid arbitrary values introduced for individual components.

### Cards

Cards should create meaningful grouping.

Do not create a card simply because an element exists.

Avoid:

- cards inside cards inside cards,
- every metric becoming a card,
- excessive borders between already-separated regions.

When spacing alone communicates grouping, prefer spacing.

---

## 6. Glass, shadows, borders, and glow

Glass is the project's **surface treatment**, not the product.

Use it with restraint.

### Good glass

A good surface:

- remains readable,
- has sufficient separation from its background,
- communicates elevation,
- does not obscure content,
- and uses blur/transparency consistently.

### Avoid

- extreme transparency,
- multiple competing gradients,
- thick luminous borders,
- strong glow on every component,
- excessive background blur,
- nested glowing containers,
- ornamental shine unrelated to interaction or state.

### Elevation

Elevation should correspond to meaning.

A useful conceptual hierarchy:

```text
Page background
    < passive surface
    < interactive / important surface
    < temporary overlay
```

Do not increase shadow simply to make a component feel "more modern."

---

## 7. Color semantics

Color must have a job.

Primary semantic colors:

| Color role | Meaning |
| :--- | :--- |
| Blue | Normal primary interaction / simulation / application accent |
| Green | Success / positive state |
| Amber | Caution / dry run / attention |
| Red | Danger / failure / live-trading consequence |
| Muted slate | Secondary or inactive information |

Do not reuse danger or success colors decoratively.

### Never rely on color alone

Pair important colors with at least one additional cue:

- label,
- icon,
- sign,
- shape,
- pattern,
- position,
- status text.

Examples:

```text
+2.4% ↑
-1.7% ↓
DRY RUN
FAILED
STALE
```

A user should still understand the state if color perception is reduced.

---

## 8. Buttons and actions

Actions require obvious hierarchy.

### Primary

Use one clear primary action when the page has a principal next step.

Avoid multiple equally prominent primary buttons in the same region.

### Secondary

Secondary actions should remain visible without competing with primary actions.

### Destructive

Destructive actions should:

- look distinct when consequence matters,
- not masquerade as normal primary actions,
- communicate what will happen,
- and require appropriate confirmation for consequential irreversible actions.

A rarely used destructive action may remain visually quiet until interaction,
provided its meaning is still clear.

### Controls

Interactive controls must have recognizable:

- default,
- hover,
- active,
- focus-visible,
- disabled

states.

Do not communicate interactivity through hover alone.

---

## 9. Forms and Settings

Settings pages should optimize for **understanding consequences**, not merely
field density.

Group settings by user intent.

Prefer:

```text
Section
  Short explanation
  Related controls
  Consequence / validation where needed
```

Avoid long undifferentiated forms.

For important settings:

- explain consequences near the control,
- show current state,
- show validation close to the input,
- preserve entered values after validation errors,
- distinguish required from optional information.

Simulation and Dry Run deserve stronger treatment because they change the
safety characteristics of the application.

Keep their existing safety-card concept unless an explicit redesign improves
clarity.

---

## 10. Dashboard guidance

The Dashboard should answer the most important questions within seconds.

Recommended visual priority:

```text
1. Trading mode + application identity
2. Portfolio value / current portfolio condition
3. Data freshness
4. Cash / crypto allocation and deviation
5. Asset-level performance / allocation detail
6. Recent rebalancer activity
7. Secondary metadata
```

Preserve the current hierarchy where the total portfolio value is the visual
hero rather than reverting to a wall of equal KPI cards.

Do not add generic dashboard widgets simply to make the page look fuller.

Avoid metrics that are interesting but not operationally useful.

---

## 11. History guidance

History is an analytical surface.

Favor information density over ornamental card layouts.

Recommended hierarchy:

```text
Summary
   ↓
Major trends / comparisons
   ↓
Detailed chart analysis
   ↓
Trade-level evidence
```

Charts and tables should visually complement one another.

Do not duplicate the exact same information in multiple large visualizations
unless each representation answers a different question.

---

## 12. Tables

Financial tables should prioritize comparison and scanning.

Prefer:

- right-aligned numeric columns,
- tabular numerals,
- consistent decimals,
- left-aligned text identifiers,
- quiet separators,
- clear header hierarchy,
- adequate row height,
- restrained status badges.

Avoid putting every cell inside a pill.

### Status density

Badges are useful when a categorical state needs attention.

Do not convert routine values into badges.

The existing quiet success-dot approach is preferable to repeating a large
`SUCCESS` badge on every normal row.

Exceptional states deserve stronger treatment than normal states.

---

## 13. Charts and data visualization

Charts should answer questions, not decorate empty space.

Before adding a chart, identify the question:

> What decision or understanding becomes easier with this visualization?

If there is no good answer, use text or a table instead.

### Chart hierarchy

Prioritize:

1. important series,
2. useful axis context,
3. comparison,
4. annotation,
5. controls,
6. decorative chart chrome.

Avoid excessive:

- grid lines,
- borders,
- tooltips,
- gradients,
- legends,
- point markers.

### Series

Series must be distinguishable beyond subtle variations of the same color.

Critical information should not require color perception alone.

### Controls

Zoom, range, view, and comparison controls should feel like one compact
toolset.

Do not let toolbar chrome dominate the chart.

### Animation

Data updates should feel stable.

Avoid repeatedly animating entire charts on SSE updates.

Users should be able to maintain visual orientation while values change.

---

## 14. Responsive behavior

Design **mobile-first**, but remember this is primarily a data-heavy operator
interface and must work especially well at laptop and desktop sizes.

Current project breakpoints already include:

- 640px
- 768px
- 1024px
- laptop-specific handling around 1280px

Evaluate at minimum:

- narrow phone,
- tablet,
- ~1280px laptop,
- ~1440px desktop,
- wider desktop.

For repeatable evidence, use the shared
[`capture_screenshots.py`](../docs-screenshot-refresh/scripts/capture_screenshots.py)
profiles: `phone` (390px), `tablet` (768px), `laptop` (1280px), `desktop`
(1440px), and `wide` (1920px). A visual claim should name the profile(s) that
support it rather than relying on a one-off browser viewport.

### Responsive priority

When width decreases:

1. preserve meaning,
2. preserve actions,
3. preserve labels,
4. reflow layout,
5. reduce decorative spacing,
6. only then hide genuinely secondary information.

Never hide safety mode or critical state to make a header fit.

### Avoid

- tiny text as the solution to insufficient width,
- clipped labels,
- overlapping controls,
- compressed unreadable charts,
- accidental horizontal page scrolling.

Dense tables may use deliberate horizontal scrolling when there is no
responsible reflow alternative, but make that behavior obvious and contained.

---

## 15. Accessibility is part of aesthetics

Accessible UI usually looks more deliberate because states and hierarchy are
clearer.

Treat **WCAG 2.2 AA as the floor**, not a separate cleanup phase.

Require:

- semantic HTML before ARIA workarounds,
- full keyboard operation,
- logical tab order,
- visible keyboard focus,
- sufficient text contrast,
- sufficient non-text/control contrast,
- adequately sized and spaced pointer targets,
- responsive reflow,
- accessible names for controls,
- labels for form inputs,
- state communicated by more than color,
- useful error identification,
- reduced-motion support,
- no keyboard traps.

The project already supports `prefers-reduced-motion`; preserve it.

### Native semantics first

Prefer:

```text
button
a
input
select
label
table
th
nav
header
main
```

over recreating those controls from generic elements.

Use ARIA to provide missing semantics, not to replace correct native HTML.

For this repository, also follow the project invariant in
`.agents/OPERATING.md`: do not introduce new ARIA attributes, roles, or
accessibility-only copy unless the user explicitly requests accessibility work
or the scoped feature cannot function correctly without them.

### Dynamic data

Do not make assistive technology announce every SSE refresh.

Reserve announcements for meaningful state changes such as:

- disconnected or stale data,
- operation completion,
- validation failure,
- important status changes.

---

## 16. Motion

Motion should explain:

- state change,
- relationship,
- appearance or disappearance,
- confirmation,
- or hierarchy.

It should not exist simply because animation is available.

Prefer short, subtle transitions.

Good candidates:

- hover state,
- pressed state,
- expanding details,
- status transition,
- unobtrusive data updates.

Avoid:

- looping decorative movement,
- large parallax effects,
- springy financial metrics,
- bouncing notifications,
- animated gradients,
- repeated attention-seeking pulse effects.

A repeating pulse should communicate a genuinely useful live/status condition,
not general visual excitement.

---

## 17. Loading, empty, error, and stale states

A polished interface designs these deliberately.

For every significant UI region consider:

```text
loading
empty
success
partial data
stale
error
disabled
```

### Loading

Prefer stable placeholders or localized progress indicators.

Avoid blocking the entire page when only one region is refreshing.

Avoid layout jumps when loaded content replaces placeholders.

### Empty

An empty state should explain:

1. what is empty,
2. whether that is normal,
3. what happens next,
4. what the user can do, if anything.

### Error

Errors should explain:

- what failed,
- what remains safe,
- whether data may be stale,
- and what recovery action is available.

Do not reduce serious errors to a red border with no explanation.

### Stale

Stale data is not equivalent to failed data.

Keep stale state clearly identifiable while preserving the last usable
information when safe to do so.

---

## 18. Microcopy

Prefer concise operational language.

Good:

```text
Portfolio within tolerance
Last updated 18s ago
Simulation
Dry Run
Live Trading
No trades this cycle
Reset zoom
Save settings
```

Avoid unnecessary marketing language:

```text
Unlock powerful portfolio insights
Supercharge your trading journey
Experience next-generation portfolio optimization
```

The application should sound like trustworthy financial software, not a
landing page.

---

## 19. Iconography

Icons should improve recognition.

Do not add icons simply because blank space exists.

Rules:

- use one icon family/style,
- keep stroke weights visually consistent,
- accompany unfamiliar icons with text,
- do not use an icon as the sole indication of a critical state,
- decorative icons should not compete with data.

Avoid "icon soup" where nearly every label receives an unrelated symbol.

---

## 20. Modern-UI anti-patterns

Do **not** blindly reproduce fashionable patterns.

### Generic AI-dashboard styling

Avoid:

- giant empty hero areas,
- floating glowing orbs,
- gratuitous neon,
- excessive gradient text,
- every component inside a rounded card,
- meaningless sparkles,
- huge decorative icons,
- overuse of glassmorphism,
- fake sophistication through animation.

### Excessive pillification

Not everything should be a pill.

Use pills primarily for:

- compact status,
- filters,
- small categorical state,
- tightly scoped controls.

### Card proliferation

A card is not a default HTML element.

Do not wrap every heading, metric, form group, table, and chart in another card.

### Too many accents

If blue, green, amber, red, purple, cyan, pink, and orange all compete for
attention, semantic color loses meaning.

### Too much glow

Glow is an accent.

If every component glows, nothing is emphasized.

### Decorative density

Do not solve sparse information by adding decoration.

Improve grouping, typography, proportions, or useful information instead.

### Trend chasing

Do not add a design pattern simply because it currently appears in popular
dashboards.

Ask whether it improves this application's:

- comprehension,
- scanning,
- safety,
- interaction,
- or visual coherence.

---

## 21. Project-specific invariants

Unless the user explicitly approves a redesign that changes them:

- Keep the persistent trading-mode plate on Dashboard, Settings, and History.
- Keep STREAM / STALE conceptually separate from trading mode.
- Keep total portfolio value as the Dashboard's primary hero.
- Keep Cash / Crypto allocation visually subordinate to total portfolio value.
- Keep recent activity grouped by rebalance cycle.
- Keep Simulation / Dry Run Settings controls visually consequence-aware.
- Keep History chart controls compact.
- Keep normal trade success visually quieter than exceptional statuses.
- Keep zero-value table noise suppressed where an em dash communicates better.
- Preserve the dark Refined Glass direction across all pages.

Any proposed violation must be labeled an explicit **redesign**, with rationale
and migration scope.

## 21A. Current responsive and documentation contract

This repository-specific contract is the controlling guidance for the current
responsive UI and screenshot work. It refines the general principles above; do
not substitute generic framework defaults for these decisions.

### Header composition

- Keep the identity, mode plate, stream health, loop state, and loop action as
  deliberate groups. Use explicit grid areas at phone and tablet widths.
- Do not rely on generic `flex-wrap` to decide where a header control lands.
- On phones, keep the brand and mode plate compact on one identity row, then
  place stream health and the labeled loop action in a predictable second row.
- At tablet widths, keep identity and stream health on the first row and place
  the loop action in an intentional trailing row; on pages without stream
  health, keep identity and loop action on one row.
- At laptop and desktop widths, keep identity on the left and right-align the
  Stream, loop state, and loop action as one operational cluster. Center the
  Dashboard / History / Settings selector group in the row below; on Settings,
  keep Save Configuration independently right-aligned.
- Pause and Resume are neutral operator actions. Use visible labels alongside
  icons and reserve semantic danger styling for actual destructive risk.

### Data display

- Format values for human scanning, not source precision: currency uses two
  decimals, percentages trim insignificant zeroes, quantities retain meaningful
  crypto precision without padded zeroes, and prices/fees adapt precision to
  magnitude while preserving sub-cent values.
- Use compact local timestamps in dense trade and activity views. Preserve the
  full timestamp in a title or equivalent hover affordance when useful.
- Keep zero or unavailable economics represented by an em dash, and keep plain
  successful trades quieter than failed or estimated states.

### History and activity

- Mobile trade rows are contained detail cards with a clear time/pair/side
  header, readable economics, and a distinct status line. Do not fall back to
  page-level horizontal scrolling or long raw timestamps.
- Recent Activity remains cycle-grouped, but a cycle is a lightweight feed row,
  not a nested glass card. Do not repeat a `Cycle` badge on every row or an
  `INFO` badge on ordinary notes. Show at most one concise informational note
  per cycle before trade actions, and keep the View all history link.
- Use one consistent chart header pattern: title on the left, controls in a
  stable trailing slot, and a deliberate stacked arrangement only below the
  phone breakpoint. Hide a scrubber while it cannot pan a zoomed chart.
- Repetitive Settings Remove actions are quiet ghost controls until hovered.

### Screenshot documentation

- Keep DPR 2 captures for visual fidelity, but never embed their native pixel
  width without an explicit display width.
- Use linked HTML `<img width="...">` elements for README and User Guide
  screenshots. The link preserves access to the full-resolution capture while
  the width communicates the intended CSS-viewport scale.
- Keep README visual coverage curated: normally one desktop dashboard, one
  phone preview, one Settings view, and one History view. Put the full phone,
  tablet, laptop, desktop, and wide gallery in the User Guide.
- Verify both documents at normal 100% browser zoom after screenshot refresh;
  a high-DPI source must not visually dominate the page by accident.

---

## 22. Designing new components

Before creating a new component, answer:

1. What user question does it answer?
2. Why does it deserve screen space?
3. Is an existing component pattern sufficient?
4. What is its primary state?
5. What are its loading, empty, and error states?
6. What is its keyboard behavior?
7. How does it reflow?
8. What semantic color, if any, does it need?
9. Does it introduce a new visual token?
10. Can anything be removed instead?

If several answers are unclear, do not start styling yet.

---

## 23. Visual quality rubric

For substantial UI designs, score each category:

| Category | 0 | 1 | 2 |
| :--- | :--- | :--- | :--- |
| Hierarchy | Unclear | Usable | Immediately obvious |
| Information density | Wasteful/cluttered | Acceptable | Efficient and calm |
| Consistency | Ad hoc | Mostly aligned | Design-system coherent |
| State clarity | Ambiguous | Understandable | Unmistakable |
| Safety clarity | Weakened | Preserved | Reinforced |
| Typography | Noisy | Adequate | Highly scannable |
| Color semantics | Decorative/confusing | Mostly correct | Disciplined |
| Responsive behavior | Breaks | Usable | Intentionally reflows |
| Accessibility | Clear defect | Basic | Robust |
| Interaction feedback | Missing/noisy | Adequate | Clear and restrained |
| Visual restraint | Excessive/plain | Acceptable | Polished without noise |
| Data readability | Difficult | Usable | Effortless to compare |

Do not recommend a design containing a **0** in:

- state clarity,
- safety clarity,
- responsive behavior,
- accessibility,
- or data readability.

A visually attractive design with a zero in one of those categories is not a
successful design.

---

## 24. Workflow

For a request such as:

- "How should this new panel look?"
- "Modernize this page."
- "Improve the design system."
- "What should we do visually here?"
- "Design this new feature."
- "Make this UI look better."
- "Evaluate this proposed UI direction."

follow this workflow.

```text
- [ ] Step 0: Identify user task and operational importance
- [ ] Step 1: Read relevant current UI/component/theme implementation
- [ ] Step 2: Identify existing pattern to reuse or intentionally evolve
- [ ] Step 3: Establish information hierarchy before styling
- [ ] Step 4: Define states and responsive behavior
- [ ] Step 5: Apply the visual-quality rubric
- [ ] Step 6: Produce a concrete visual design brief
- [ ] Step 7: Hand off to ui-visual-review or ui-visual-implement as appropriate
```

### Step 0 — Identify the task

Determine:

- what the user is trying to accomplish,
- what information matters most,
- whether the surface controls or displays safety-sensitive behavior,
- whether this is a local component change or a design-system-level change.

### Step 1 — Inspect existing implementation

Read the relevant:

- view component,
- CSS module,
- `CssTheme` tokens,
- responsive rules,
- shared classes,
- client-side rendering code where applicable.

Do not design from generic memory when a current project pattern exists.

### Step 2 — Reuse or evolve

Prefer:

1. reuse an existing pattern,
2. extend an existing pattern,
3. add a reusable design-system concept,
4. add one-off styling only when genuinely unique.

Do not create a second visual vocabulary for one feature.

### Step 3 — Establish hierarchy

Decide what is:

- primary,
- secondary,
- supporting,
- metadata,
- exceptional.

Do this before selecting shadows, colors, or effects.

### Step 4 — Define states

Consider:

- default,
- hover,
- active,
- focus,
- disabled,
- loading,
- empty,
- partial,
- stale,
- error,
- success.

Also define narrow and wide viewport behavior.

### Step 5 — Apply rubric

Identify weak categories before recommending implementation.

Resolve any score of `0` in a critical category.

### Step 6 — Produce brief

Translate the design into observable instructions and acceptance criteria.

### Step 7 — Handoff

Use the appropriate downstream skill rather than duplicating its workflow.

---

## 25. Design brief output

When this skill is the primary skill, produce:

```markdown
# Visual design brief

## Goal
What user problem the design should solve.

## Existing pattern
Which current Kraken Rebalancer components/design tokens are relevant.

## Hierarchy
1. Primary
2. Secondary
3. Supporting
4. Metadata

## Layout
Concrete grouping, ordering, spacing, and responsive behavior.

## Component treatment
Cards, controls, tables, charts, badges, typography, surfaces.

## States
Default / hover / focus / active / disabled / loading / empty / error / stale.

## Color & emphasis
Which semantic colors are justified and where emphasis should remain neutral.

## Accessibility
Keyboard, focus, semantics, contrast, color redundancy, motion, target size.

## Responsive behavior
Phone / tablet / laptop / desktop expectations.

## Remove / simplify
Existing or proposed UI that should be removed, combined, or visually reduced.

## Acceptance criteria
Observable criteria ui-visual-review / ui-visual-implement can verify.
```

Recommendations should be concrete.

Bad:

> Make the card cleaner and more modern.

Good:

> Remove the nested inner card, promote Total Value to the section's only
> display-size number, move timestamp into muted metadata beneath the label,
> keep deviation as a compact semantic chip, and reduce the remaining metrics
> to a two-column supporting row below it.

---

## 26. Handoff rules

### To ui-visual-review

Use when the design question requires judging the currently rendered UI.

Provide the design brief as criteria for screenshot analysis.

The review skill owns:

- running the application,
- capturing screenshots,
- reading images,
- recording actual visual findings.

### To ui-visual-implement

Use when the user has approved a visual direction or supplied an explicit
implementation brief.

Implementation should prefer:

1. existing tokens,
2. existing components,
3. shared component changes,
4. new design-system tokens,
5. one-off styling only as a last resort.

The implementation skill owns:

- modifying source,
- running visual verification,
- checking acceptance criteria against fresh screenshots.

### New visual system

If the requested design fundamentally replaces Refined Glass, treat it as a
design-system migration rather than a collection of local CSS edits.

Define first:

- palette,
- typography,
- surfaces,
- radii,
- elevation,
- interactive states,
- semantic colors,
- density,
- responsive rules,
- migration sequence.

Do not leave the application with two unrelated visual systems.

---

## 27. Decision principles

When multiple designs are reasonable, prefer the option that:

1. requires less explanation,
2. makes the important state easier to identify,
3. removes rather than adds unnecessary chrome,
4. reuses existing patterns,
5. keeps semantic color meaningful,
6. works without animation,
7. survives a narrow viewport,
8. survives reduced color perception,
9. preserves keyboard usability,
10. feels calm during normal operation.

When visual attractiveness and operational clarity conflict, choose
operational clarity.

When two options are equally clear, prefer the visually simpler one.

---

## 28. Anti-patterns for agent-generated UI changes

Be particularly suspicious of UI changes that:

- add several new visual tokens for one small feature,
- add gradients without a hierarchy reason,
- introduce a new card style instead of reusing an existing surface,
- wrap previously simple content in multiple containers,
- increase font size to solve hierarchy everywhere,
- add badges to ordinary data,
- use red or green for decoration,
- add animations to static financial metrics,
- add icons to nearly every line of text,
- hide important information at common laptop widths,
- optimize only for a screenshot at one viewport,
- use placeholder marketing text instead of operational language,
- replicate common dashboard patterns without understanding the user task,
- make Simulation, Dry Run, STREAM, STALE, and Live Trading visually ambiguous.

Do not reward visual complexity simply because it looks expensive.

---

## 29. Completion checklist

- [ ] Information hierarchy is explicit.
- [ ] Design serves an operational user question.
- [ ] Refined Glass was reused or intentionally evolved.
- [ ] Existing design tokens/components were considered first.
- [ ] Semantic colors retain their meaning.
- [ ] Safety and trading-mode clarity are preserved.
- [ ] Data freshness remains distinguishable from trading mode.
- [ ] Loading, empty, error, partial, and stale states were considered.
- [ ] Keyboard and focus behavior are defined.
- [ ] Accessibility is part of the design, not deferred cleanup.
- [ ] Narrow, laptop, desktop, and wide layouts were considered.
- [ ] Motion is purposeful and reduced-motion remains supported.
- [ ] Tables and charts prioritize data comprehension.
- [ ] Decorative effects remain restrained.
- [ ] Unnecessary cards, badges, icons, and chrome were challenged.
- [ ] Concrete acceptance criteria were produced.
- [ ] The appropriate review or implementation skill receives the handoff.
