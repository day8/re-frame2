# `tools/causa/testbeds/multi_frame_causa/`

Multi-frame Causa testbed (rf2-2vgog) — three richly-domain'd frames coexisting on one page, with one deliberate cross-frame coordination scenario. The point: exercise *Causa's* frame picker, scope-per-frame, cascade isolation, and cross-frame causality against three named frames a Causa user would actually want to inspect.

This testbed is distinct from the top-level [`testbeds/multi_frame/`](../../../../testbeds/multi_frame/) (rf2-kzcim) — that one is three minimal counters proving the framework's per-frame partitioning contract, shared across Causa / Story / pair2-mcp consumers. This Causa-owned variant is domain-rich and panel-rich, designed for the Causa devtools workflow.

## The three frames

| Frame              | Events                                                  | Subs                                                       | Panel             |
|--------------------|---------------------------------------------------------|------------------------------------------------------------|-------------------|
| `:cart-frame`      | `:cart/add-item`, `:cart/remove-item`, `:cart/clear`, `:cart/send-to-checkout` | `:cart/items`, `:cart/total`                               | Cart UI           |
| `:checkout-frame`  | `:checkout/start`, `:checkout/pay`, `:checkout/reset`   | `:checkout/items`, `:checkout/total`, `:checkout/status`   | Checkout UI       |
| `:admin-frame`     | `:admin/refund`, `:admin/audit`                         | `:admin/transactions`, `:admin/refund-count`, `:admin/audit-count` | Admin panel       |

Each frame's `app-db` carries only its own slot. No shared root, no app-global keys.

## The cross-frame coordination scenario

Clicking **Send to checkout** on `:cart-frame` dispatches `:cart/send-to-checkout`. The handler snapshots `:cart/items`, clears the local slot, and fans out `[:checkout/start <items>]` to `:checkout-frame` via a tiny bridge fx (`::dispatch-to-frame`) that calls `rf/dispatch` with explicit `{:frame :checkout-frame}` opts.

Reserved `:dispatch` is intra-frame by contract — same pattern as the shared multi-frame testbed.

## What Causa users observe

- **Frame picker** — `ribbon-frame-picker` enumerates all three frames after any cross-frame click.
- **Per-frame scoping** — selecting a frame in the picker scopes the L2 event list + downstream panels to that frame's cascade.
- **Cascade isolation** — events fired against `:admin-frame` don't appear in `:cart-frame`'s or `:checkout-frame`'s L2 list.
- **Cross-frame causality** — the Send-to-checkout click produces an envelope-rooted sequence: the originating `:cart/send-to-checkout` (on `:cart-frame`) and the resulting `:checkout/start` (on `:checkout-frame`) land in the same drain.

## Files

- `core.cljs` — registers the three frames, their events / subs / views; mounts the root component with three `rf/frame-provider`-wrapped panels.
- `fixtures.cljs` — deterministic per-frame event vectors (cart seed, cross-frame handoff, admin traffic) for the Playwright smoke and future Story variants.
- `index.html` — static host with the standard `[data-rf-causa-host]` aside so the Causa preload auto-mounts inline.
- `spec.cjs` — Playwright smoke asserting initial-state, frame-scoped isolation, the cross-frame hop, Causa shell mount, and frame picker enumeration.

## Running

From `implementation/`:

```bash
npx shadow-cljs watch :examples/multi-frame-causa
```

Browse to `http://localhost:9630/build/multi-frame-causa/dashboard` (or open the served `index.html` from `out/examples/multi-frame-causa/`). Press **Ctrl+Shift+C** to toggle Causa.

From repo root, the testbed is also picked up by the examples orchestrator:

```bash
cd implementation
npm run test:examples              # full sweep including this testbed
npm run test:examples:realworld -- --filter multi-frame-causa
```

## Build target

`:examples/multi-frame-causa` (defined in [`implementation/shadow-cljs.edn`](../../../../implementation/shadow-cljs.edn)). The Causa preload (`day8.re-frame2-causa.preload`) is wired so the shell auto-mounts inline on every dev build.

## Cross-references

- [`spec/002-Frames.md` §What lives in a frame](../../../../spec/002-Frames.md) — the per-frame `app-db` / router-queue / sub-cache partitioning contract.
- [`spec/002-Frames.md` §Dispatches issued from inside a handler body](../../../../spec/002-Frames.md) — the cross-frame `:dispatch` semantics this testbed exercises via the bridge fx.
- [`tools/causa/spec/018-Event-Spine.md` §3 Frame dropdown](../../spec/018-Event-Spine.md) — the frame-picker contract this testbed populates with three frames.
- [`testbeds/multi_frame/`](../../../../testbeds/multi_frame/) — the sibling shared framework-behavior surface (minimal counters, three consumers).
