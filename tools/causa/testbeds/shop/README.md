# `tools/causa/testbeds/shop/`

Shop testbed (rf2-yhxk3) — **THE** canonical Causa demo. One rich multi-frame
demo app exercising every Causa lens. Replaces and absorbs both the prior
`cart_total/` (the 3pm tutorial hero) and `multi_frame_causa/` (the three-frame
scaffolding from #1468) — pre-alpha posture, no overlap, no shims.

## The 6 layers, mapped to Causa's lenses

| Layer | Wiring | Causa lens that lights up |
|-------|--------|---------------------------|
| **1. Multi-frame** | Three named frames (`:cart-frame`, `:checkout-frame`, `:admin-frame`), each with its own events, subs, app-db slot, panel | Frame picker; cascade-scoped L2 list |
| **2. HTTP** | `:cart/load-catalogue` issues managed-HTTP against `/api/products`; dev toggle picks next outcome (200 / 500 / timeout) | Trace lens (`:rf.http/*` rows); Issues ribbon (failures) |
| **3. Machine** | `:checkout/flow` state machine: `cart-empty → ready → paying → confirmed | failed`; guard `cart-non-empty?`; entry actions `charge-card!`, `fire-confirmation!` | Machines lens (state chart + transition history); Trace lens (`:rf.machine/guard-evaluated`, `:rf.machine/action-ran` from #1469) |
| **4. Flow** | `:total-due` derived view chains through `:cart/subtotal-WRONG` + tax + shipping. The deliberately-wrong sub reads `:cart/snapshot` (cart-frame's `[:checkout :snapshot]`) instead of `:cart/items` — the 3pm tutorial beat preserved from `cart_total/` | Views lens (the bug is one click away on the dependency edge) |
| **5. Non-trivial app-db** | Nested per-frame shape; rich enough that App-DB Diff has multiple cluster sections per cascade | App-db Diff lens (slice-centric) |
| **6. Issues source** | (a) `CartItem` Malli schema + admin 'submit broken item' button (cross-frame write of `:qty -1`, post-handler rollback); (b) `:admin/refund` throws when `:refund-throw?` is on | Issues ribbon (`:rf.error/schema-validation-failure`, `:rf.error/handler-exception`) |

## The three frames

| Frame              | Slot in app-db                                                       | Events                                                              |
|--------------------|----------------------------------------------------------------------|---------------------------------------------------------------------|
| `:cart-frame`      | `[:cart]` + `[:checkout :snapshot]` + `[:checkout :tax-rate]` + `[:checkout :shipping]` | `:cart/load-catalogue`, `:cart/add-item`, `:cart/remove-item`, `:cart/clear`, `:cart/send-to-checkout`, `:cart/clear-snapshot`, `:cart/set-http-outcome` |
| `:checkout-frame`  | `[:checkout :card]`, `[:rf/machines :checkout/flow]`                 | `:checkout/pay`, `:checkout/reset`, `:checkout/flow` (machine)                                                                   |
| `:admin-frame`     | `[:admin]`                                                            | `:admin/audit`, `:admin/refund`, `:admin/toggle-refund-throw`, `:admin/submit-broken-item`                                       |

The cart-frame owns the entire shopping projection (items + catalogue +
snapshot + tax + shipping) so the `:total-due` sub chain resolves under
one frame. Subscriptions are per-frame (Spec 006); the deliberately-wrong
sub MUST read its upstream from the same frame's app-db. The cross-frame
machine envelope (`:cart/send-to-checkout` → `:checkout/flow
[:checkout.flow/start items]` on `:checkout-frame`) carries items into
the machine's `:data` slot via the `:seed-from-cart` entry action; the
checkout-frame's view reads that local slot to render its summary.

## Cross-frame coordination

Clicking **Send to checkout** on `:cart-frame` dispatches
`:cart/send-to-checkout`. The handler:

1. Writes the snapshot LOCALLY (cart-frame) into `[:checkout :snapshot]` so
   the deliberately-wrong sub has something to lock onto.
2. Clears `[:cart :items]` locally.
3. Fans out `[:checkout/flow [:checkout.flow/start items]]` to
   `:checkout-frame` via the `::dispatch-to-frame` bridge fx (the reserved
   `:dispatch` is intra-frame by contract — Spec 002 §Dispatches issued from
   inside a handler body). The machine transitions cart-empty → ready; the
   `:seed-from-cart` entry action seeds the machine's `:data` slot with the
   items so the checkout-frame's view can render the summary without a
   cross-frame app-db read.

Reverse direction — when `:checkout/reset` fires on `:checkout-frame`, it
dispatches `:checkout.flow/reset` intra-frame to the machine AND
`:cart/clear-snapshot` cross-frame to `:cart-frame` so the next add-to-cart
resumes from a clean snapshot.

## The 3pm tutorial beat — preserved

`:cart/subtotal-WRONG` reads `:cart/snapshot` (`[:checkout :snapshot]` on
cart-frame) instead of `:cart/items`. After a Send-to-checkout click, the
snapshot is non-empty; if the user adds items to the live cart, the displayed
total stays locked to the snapshot's total. The one-token fix swaps
`:cart/subtotal-WRONG` → `:cart/subtotal-CORRECT` in the `:total-due` sub.

The tutorial chapter ([docs/causa/05-click-to-source.md](../../../../docs/causa/05-click-to-source.md))
walks the cascade end-to-end: tester drops a screenshot → coord on the wire →
Subscriptions panel → dependency edge → wrong upstream slot → one-line fix.

## Files

- `core.cljs` — the 6 layers wired up; each layer reads as a small section
  with a clear comment header. ~500 LoC.
- `fixtures.cljs` — deterministic per-frame event vectors for the Playwright
  smoke and future Story variants (cart seed, send-to-checkout hop, happy-path
  pay, HTTP-500 cascade, schema violation, refund throw).
- `index.html` — minimal static host with the standard `[data-rf-causa-host]`
  aside so the Causa preload auto-mounts inline.
- `spec.cjs` — Playwright smoke asserting the cascade-rich UX works end-to-end
  through all six layers.
- `api/products.json` — static catalogue payload the 200-success path fetches
  via live Fetch.

## Running

From `implementation/`:

```bash
npx shadow-cljs watch :examples/shop
```

The Causa preload (`day8.re-frame2-causa.preload`) is wired so the panel
auto-mounts inline on every dev build. Open the served page and walk the
lenses in order: Event → App-db → Views → Trace → Machines → Issues.

The testbed is picked up by the examples orchestrator:

```bash
cd implementation
npm run test:examples                            # full sweep
node ../examples/scripts/serve-and-run-examples-tests.cjs --filter shop
```

## Build target

`:examples/shop` (defined in
[`implementation/shadow-cljs.edn`](../../../../implementation/shadow-cljs.edn)).
The Causa preload is wired in `:devtools/:preloads`; the
`api/products.json` static asset is staged into the served output by the
orchestrator's `extraFiles` entry.

## Cross-references

- [`docs/causa/index.md`](../../../../docs/causa/index.md) — the welcome
  page; the 3pm scenario sidebar points here.
- [`docs/causa/05-click-to-source.md`](../../../../docs/causa/05-click-to-source.md)
  — the chapter that walks the deliberately-wrong sub end-to-end on this
  testbed.
- [`docs/causa/09-app-db-diff.md`](../../../../docs/causa/09-app-db-diff.md) —
  references this testbed's `:cart/items` ↔ `:checkout/snapshot` drift as the
  canonical App-DB Diff example.
- [`spec/002-Frames.md`](../../../../spec/002-Frames.md) §Dispatches issued
  from inside a handler body — the cross-frame `:dispatch` contract the
  bridge fx exercises.
- [`spec/005-StateMachines.md`](../../../../spec/005-StateMachines.md) — the
  machine substrate the `:checkout/flow` exercises.
- [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md) —
  the trace bus Causa consumes; the `:rf.machine/guard-evaluated` /
  `:rf.machine/action-ran` traces (rf2-2nwfd / #1469) the Machines lens
  surfaces.
- [`spec/010-Schemas.md`](../../../../spec/010-Schemas.md) §Validation order
  — the schema rejection step the broken-item button exercises.
- [`spec/014-HTTP.md`](../../../../spec/014-HTTP.md) — the managed-HTTP
  contract the catalogue fetch + HTTP-failure toggle exercise.
