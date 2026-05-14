# `testbeds/ssr-multi-frame`

Three frames coexist on the page (`:counter/a`, `:counter/b`, `:log`),
each receiving its OWN `:rf/hydrate` dispatch from a per-frame slice
of the baked payload. Per [`spec/011-SSR.md` §Frames are per-request](../../spec/011-SSR.md)
+ [`spec/002-Frames.md` §What lives in a frame](../../spec/002-Frames.md):
each frame owns its own `app-db`, router queue, and signal-graph
cache; the hydration protocol operates frame-by-frame.

The static `index.html` bakes ONE payload script carrying a map of
`{frame-id → per-frame-payload}`; the browser-side `run` walks it
and dispatches `[:rf/hydrate slice]` against each frame.

| `data-testid` | What it carries / proves |
|---|---|
| `panel-A`, `panel-B`, `panel-log` | Per-frame panel containers. |
| `n-A`, `n-B` | The seeded `:n` from each counter frame's payload slice (`10`, `99`). |
| `entries-count` | The `:log` frame's payload-seeded entry count (`2`). |
| `hyd-A`, `hyd-B`, `hyd-log` | `true` once each frame's `:rf/hydration` metadata lands. |
| `hash-A`, `hash-B`, `hash-log` | The payload-supplied `:rf/render-hash` per frame (`aaaa1111`, `bbbb2222`, `cccc3333`). |
| `summary-{a,b,log}-hash` | Cross-frame readout via `rf/subscribe-once frame-id`. |
| `summary-all-distinct` | `true` iff the three per-frame `:server-hash` values are pairwise distinct — proves no cross-frame bleed. |
| `inc-A`, `inc-B` | Per-frame post-hydration interactivity; clicking `inc-A` bumps `:counter/a`'s `:n` only — `:counter/b` and `:log` are untouched. |

## What this surface tests (per spec/011-SSR.md and spec/002-Frames.md)

| Spec section | Behaviour exercised |
|---|---|
| Spec 011 §Frames are per-request | Three independent frames hydrate from three distinct payload slices; the three `app-db`s never cross-contaminate. |
| Spec 011 §The `:rf/hydrate` event | The standard `:rf/hydrate` handler is reused per frame; the framework's `:frame` opt routes the dispatch to the right router queue. |
| Spec 002 §What lives in a frame | Per-frame app-db + router queue + sub cache isolation survives the hydration round-trip. |
| Spec 002 §Routing the dispatch envelope | `[:rf/hydrate slice]` with `{:frame fid}` lands on `fid`'s queue, never `:rf/default`'s. |

## What this surface deliberately omits

- Hash-mismatch detection per frame. Each frame's payload carries a
  symbolic hash (`aaaa1111`, …) — `verify-hydration!` isn't called
  here. Sibling [`testbeds/ssr_hydration_mismatch/`](../ssr_hydration_mismatch/)
  exercises the mismatch path on a single frame.
- Cross-frame `:dispatch` fan-out. That's the
  [`testbeds/multi_frame/`](../multi_frame/) surface's concern;
  conflating the two would dilute what this surface proves.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/ssr-multi-frame
# Or via the orchestrator:
npm run test:examples
```

Build id `testbeds/ssr-multi-frame`; served at
`/testbed-ssr-multi-frame/`.

## Cross-references

- [`spec/011-SSR.md` §Frames are per-request](../../spec/011-SSR.md) — the per-request frame contract.
- [`spec/002-Frames.md` §What lives in a frame](../../spec/002-Frames.md) — the per-frame isolation contract.
- Sibling: [`testbeds/multi_frame/`](../multi_frame/) — the cross-frame `:dispatch` fan-out surface (NOT SSR).
- Sibling: [`testbeds/ssr_basic/`](../ssr_basic/) — the single-frame hydration baseline.
- Sibling: [`testbeds/ssr_hydration_mismatch/`](../ssr_hydration_mismatch/) — the deliberate-mismatch surface.
