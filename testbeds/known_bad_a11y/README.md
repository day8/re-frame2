# `testbeds/known-bad-a11y`

A Story-mode testbed with TWO variants registered against one parent
Story:

- `:story.a11y/known-bad` — a variant body packing five deliberate
  axe-core violations into the smallest DOM that triggers each rule.
- `:story.a11y/known-good` — the same shape but with proper a11y
  attributes. Zero violations expected.

A consumer (Story's a11y panel) reads the surface to verify the
load-bearing rf2-qgms1 fix (PR #1080): axe-core's scan is scoped to
the `data-rf-story-variant-root` wrapper, so violations report
**only** the user-authored variant tree, never Story's own chrome
(sidebar, toolbar, panels, title bar).

## The five violations on the bad variant

| Violation | Element | Missing | axe-core rule | Impact |
|---|---|---|---|---|
| 1 | `<img data-testid="bad-img">` | `alt` attribute | `image-alt` | critical |
| 2 | `<button data-testid="bad-button">` | text content / `aria-label` | `button-name` | critical |
| 3 | `<input data-testid="bad-input">` | associated `<label>` | `label` | critical |
| 4 | `<a data-testid="bad-link">` | text content / `aria-label` | `link-name` | serious |
| 5 | `<p data-testid="bad-contrast">` | sufficient contrast (`#eaeaea` on `#ffffff`) | `color-contrast` | serious |

Each violation is one DOM element with one missing attribute or value
— the minimum that triggers its rule. No other behaviour, no other
content. A consumer asserting on the panel's violation count expects
**at least five** entries on the bad variant (axe-core may surface
additional related violations like `region` or `landmark-one-main`;
these are bonuses, not the contract).

## The good variant — the control

Same five elements with corrected a11y:

- `<img alt="...">` — accessible name.
- `<button>Click me</button>` — text content as accessible name.
- `<label htmlFor="…"><input id="…"></label>` — label association.
- `<a>Home</a>` — text content as accessible name.
- `<p style="color: #000; background: #fff">` — WCAG AA contrast.

A consumer asserts **zero** violations on the good variant. This is
the rf2-qgms1 regression test: if the scan over-scopes into Story's
chrome (sidebar, toolbar, panels, title bar) it would surface the
chrome's own a11y posture and the good variant would report N≥1
violations.

## Why this surface tests rf2-qgms1

Per rf2-qgms1's fix (PR #1080): `axe-core`'s scan is scoped via the
`context` parameter to the variant-root selector
`[data-rf-story-variant-root='<variant-id>']`. The selector is
stamped by Story's canvas / workspace components on the immediate
wrapper around the user-authored decorated view.

This testbed proves the contract:

1. **Bad variant produces ≥5 violations** — proves axe-core ran
   against the variant body.
2. **Good variant produces 0 violations** — proves axe-core did NOT
   reach into Story's chrome (where there are inevitable cosmetic
   a11y items the framework owns and apps should not flag).
3. **Switching between the two leaves no stale state** — proves
   `drop-frame-state!` is wired correctly on variant teardown.

The contract is **pairwise**: each individual assertion is necessary
but not sufficient. If the good variant reports 5 violations and the
bad variant reports 10, the scan is mis-scoped (it includes the
chrome AND the variant). The regression is the cross-product.

## What's deliberately *missing*

- **No interaction state in the variants.** The five violations are
  pure DOM; no events, no subs. A consumer's panel result depends
  only on the rendered HTML, not on dispatch state.
- **No play sequence.** The `:rf.assert/no-warnings` assertion could
  be wired here but would conflate two contracts (axe-core scan
  scope + warning channel routing). The play wiring lives in the
  Story integration tests; this surface is the visual contract.
- **No mode axis interaction.** Modes (dark/light/sepia) could
  produce contrast-axis violations on the good variant; the surface
  stays mode-agnostic to keep the pairwise contract clean.
- **No additional axe-core rules.** Five rules across two impact
  levels is the smallest cover that exercises Story's surface
  formatting (the panel groups by impact, then by rule).
- **No global-args layered config.** Per `counter_with_stories/`
  Story configures `:global-args {:locale :en}`; this surface
  mirrors the convention so the variant doesn't accidentally
  inherit a different locale that affects axe-core's lang-attr
  rules.

## Test scenarios from rf2-fe84r this surface enables

**Story (18)**:
- **A11y panel surfaces violations on known-bad variant (cross-ref
  rf2-qgms1 — scope to variant)** — the load-bearing scenario this
  surface unblocks. The panel must report ≥5 violations on
  `:story.a11y/known-bad` and 0 on `:story.a11y/known-good`. Story
  chrome's own a11y posture (sidebar icons, panel tabs, etc.) must
  not appear in either report.
- Variants render under each substrate — both variants register
  with `:substrates #{:reagent}`; the surface re-renders cleanly on
  variant switch.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/known-bad-a11y
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/known-bad-a11y`; output lands
in `implementation/out/testbeds/known-bad-a11y/`. Open the page,
select either variant in the sidebar, open the a11y panel, click
Run.

## Cross-references

- [`tools/story/spec/007-Mode-Tabs.md`](../../tools/story/spec/007-Mode-Tabs.md) — the a11y mode tab + panel placement.
- [`tools/story/src/re_frame/story/ui/a11y.cljs`](../../tools/story/src/re_frame/story/ui/a11y.cljs) — the panel that consumes this surface (`variant-root-selector` scopes axe-core to `[data-rf-story-variant-root='<id>']`).
- **rf2-qgms1 / PR #1080** — the bug fix this surface regression-tests against.
- **rf2-su313** — the upstream decision to keep the axe-core CDN dependency despite the third-party-egress concern (the surface depends on this call standing).
