# `testbeds/non-trivial-app-db`

A Reagent-mounted surface whose `app-db` carries 55 leaves across 6
top-level keys at depths 1 through 5. Six buttons drive six
structurally distinct diff shapes. A consumer (Causa, Story,
re-frame2-pair-mcp) uses this surface to verify its diff visualisation
renders realistic state without collapsing detail or re-rendering
unchanged subtrees.

## The app-db shape

```
:user      ← 4 leaves @ depth 2
:settings  ← 8 leaves @ depth 3
:cart      ← 12 leaves @ depth 4 (vector-of-maps)
:catalog   ← 18 leaves @ depth 5
:session   ← 8 leaves @ depth 2
:metrics   ← 5 scalar leaves @ depth 1
```

Total: 55 leaves, max depth 5. Values are picked to render legibly:
short strings, small numbers, no multi-line text. The shape is
realistic-looking — a SaaS-style app with a user profile, config
tree, multi-item cart, nested catalogue, session state, and a flat
metrics dashboard. (`core.cljs` carries the literal `initial-db`.)

## The six diff shapes

| Button | `data-testid` | Path | Depth | Shape |
|---|---|---|---|---|
| 1 | `toggle-theme` | `[:settings :theme]` | 2 | Scalar swap (`:dark`↔`:light`). |
| 2 | `toggle-notifications` | `[:settings :notifications]` | 3 | Map merge — TWO sibling keys flip simultaneously. |
| 3 | `add-cart-item` | `[:cart :items]` | 2→item | Vector append — new index appears, existing indices unchanged. |
| 4 | `bump-first-item-qty` | `[:cart :items 0 :qty]` + `[:cart :items 0 :price :amount]` | 4 | Vector swap-by-index — drill into a vector element, change a leaf AND a descendant subtree's leaf. |
| 5 | `register-new-sku` | `[:catalog :categories :books :groups :tech :skus]` | 5 | Set add at the deepest path on the surface. |
| 6 | `revoke-write-and-collapse-sidebar` | `[:session :auth :scopes]` + `[:session :ui :sidebar-open?]` | 3 | Two sibling subtrees of one top-level slice change in one drain. |

Each click produces ONE structural diff shape with a known path. A
diff renderer that conflates `[:settings :theme]` with the whole
`:settings` map (button 1 vs 2) fails immediately. A renderer that
doesn't drill into vector indices conflates buttons 3 and 4. A
renderer that collapses past depth 3 fails button 5.

## DOM mirrors

The whole snapshot is rendered as a pretty-printed pre-formatted
block under `data-testid="app-db-pr-str"`. A Playwright spec can
assert the exact before/after pair around any click without needing
to walk the trace stream.

## Why this many leaves

A canonical demo counter has ~3 leaves; a realistic app has hundreds.
Diff renderers that work on the counter shape often fail above ~20
leaves because their visual hierarchy assumes a flat structure. 55
leaves is the smallest count that exercises:

- **Vertical compression at depth 5** (the `:catalog` slice forces
  the renderer to collapse 3+ levels above the changed leaf without
  losing the breadcrumb).
- **Sibling-key diffs at all three of "scalar, collection, set"**
  (button 6 covers two of three; button 1 + button 5 cover the third).
- **Vector-as-collection vs vector-as-index** (buttons 3 + 4).
- **Multiple top-level slices touched in one drain** (button 6).
- **Top-level slices that DON'T change** (`:metrics` and `:user`
  never change on any click — the renderer must visibly leave them
  alone).

## What's deliberately *missing*

- **No primitives larger than ~50 chars per leaf.** Large values are
  the `large_dispatcher/` testbed's job. This surface keeps every
  leaf small so the renderer's elision contract doesn't kick in.
- **No `:sensitive?` on any slice.** Privacy markers are the
  `sensitive_dispatcher/` testbed's job; including one here would
  conflate diff rendering with redaction rendering.
- **No state-machine snapshots in app-db.** Per [spec/005] the
  machine snapshot lives in a separate registry (`[:rf/machines …]`);
  surface deliberately uses only the plain-app-db diffing path.
- **No `:rf/elision` declarations.** The elision walker is exercised
  by the `large_dispatcher/` testbed; this surface keeps the diff
  renderer's job pure (every value rides the wire verbatim).

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- Trace panel populates on first dispatch — each button produces a
  visible `:event/db-changed` trace with `:tags :app-db-before` and
  `:tags :app-db-after` carrying the 55-leaf shape.
- Time-travel scrub forward/back mutates visible UI — scrubbing 6
  clicks back-and-forth exercises the diff renderer at every depth.
- `:event/db-changed` trace event for app-db changes — the surface
  produces one per click with a structurally distinct shape.

**Story (18)**:
- Variants render under each substrate — Story mounts of this
  surface verify the variant renderer doesn't choke on the 55-leaf
  app-db (a common bug in early Story chrome before rf2-qgms1).
- Snapshot identity reproducible across runs — the deterministic
  6-click sequence produces a bit-identical snapshot on replay; the
  surface's `:metrics` slice carries pure counters with no clock
  state, no random values, no input dependencies.

**Cross-cutting (6)**:
- Subscribe → re-render → trace ordering preserved — each of the
  6 top-level slice subs re-runs only when its slice changes (the
  diff structure is sub-aligned).

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/non-trivial-app-db
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/non-trivial-app-db`; output
lands in `implementation/out/testbeds/non-trivial-app-db/`.

## Cross-references

- [`spec/009-Instrumentation.md` §Trace event for app-db changes](../../spec/009-Instrumentation.md) — the `:event/db-changed` shape this surface's clicks emit, with `:tags :app-db-before` / `:tags :app-db-after` consumers diff against.
- [`spec/Spec-Schemas.md` §`:rf/epoch-record`](../../spec/Spec-Schemas.md) — the epoch record's `:db-before` / `:db-after` slots carrying the 55-leaf shape per cascade.
- [`spec/Tool-Pair.md` §Time-travel](../../spec/Tool-Pair.md) — the re-frame2-pair-mcp time-travel contract a diff visualisation rides.
