# `testbeds/ssr-hydration-mismatch`

The deliberate-mismatch surface. The payload bakes a known-wrong
`:rf/render-hash` (`"deadbeef"`) into the static `index.html`. The
browser-side `run` dispatches `:rf/hydrate` (so the bogus hash lands
in runtime-db at `[:rf.runtime/ssr :hydration :server-hash]`), renders
the view, and calls
`verify-hydration!` — which computes the client-side hash, compares,
disagrees, and emits `:rf.ssr/hydration-mismatch` per
[`spec/011-SSR.md` §Hydration-mismatch detection](../../spec/011-SSR.md).

The trace listener routes the captured trace's tags through
`::record-mismatch` so the dev-mode banner re-renders with the
structured payload visible inline.

| `data-testid` | What it carries / proves |
|---|---|
| `ssr-hydration-mismatch` | Root marker. |
| `hydrated` / `not-hydrated` | Discriminator on the `[:rf.runtime/ssr :hydration]` metadata presence in runtime-db (read via a `reg-runtime-sub`). |
| `mismatch-banner` / `mismatch-banner-empty` | Pre-mismatch the `-empty` variant is rendered; post-mismatch the banner with the structured payload replaces it. |
| `mismatch-server-hash` | The payload's `:rf/render-hash` (`"deadbeef"`). |
| `mismatch-client-hash` | The runtime's computed hash for the resolved client tree — an 8-char lowercase hex string per Spec 011 §Hydration-mismatch detection. |
| `mismatch-failing-id` | `:rf/hydrate` (the v1 body-mismatch discriminator per Spec 011 §Mismatch detection — head). |
| `mismatch-recovery` | `:warned-and-replaced` (the runtime default; warn-and-replace per [§Mismatch recovery and configuration](../../spec/011-SSR.md)). |
| `count` / `inc` | Post-mismatch the page remains interactive — warn-and-replace is degraded-but-running, not crash. |

## What this surface tests (per spec/011-SSR.md)

| Spec section | Behaviour exercised |
|---|---|
| §Hydration-mismatch detection | `verify-hydration!` reads the server hash, computes the client hash, emits `:rf.ssr/hydration-mismatch` on disagreement with structured tags (`:server-hash`, `:client-hash`, `:failing-id`, `:recovery`). |
| §Mismatch recovery and configuration | Default posture is `:warned-and-replaced` — the trace fires but the client renders against the seeded state and the page stays interactive. |
| §The `:rf/hydrate` event | The server-hash is stashed in runtime-db at `[:rf.runtime/ssr :hydration :server-hash]` automatically by the framework-registered handler — user code didn't have to participate. |

## Why a known-wrong hex string instead of a "real" mismatch

The spec's hash is an 8-character lowercase-hex FNV-1a over the
canonical-EDN serialisation of the render-tree (per Spec 011
§Hydration-mismatch detection). To force a real mismatch where the
client computes some hash and the payload carries a different one, we
need the client and server to disagree.

A static `index.html` can't compute the FNV-1a at page-build time
(no JVM in the browser). Instead, baking a constant `"deadbeef"`
guarantees disagreement against the client's structurally-correct
hash (whatever it works out to be at runtime). The trace's
`:server-hash` is literally `"deadbeef"` — the spec.cjs asserts on
this exact value. The `:client-hash` is whatever the runtime
computes; the spec.cjs asserts it's a non-nil string of the right
shape (8 lowercase-hex chars).

## Running

This surface is a dev / Xray observation target — it is **not** staged
by any smoke gate (the adapter-smoke orchestrator serves only the three
adapter smokes under `implementation/adapters/<name>/testbed/`). Build
and view it by hand.

From `implementation/`:

```bash
npm run dev -- :testbeds/ssr-hydration-mismatch
# …or a one-shot compile:
npx shadow-cljs compile :testbeds/ssr-hydration-mismatch
```

Shadow-cljs build id is `:testbeds/ssr-hydration-mismatch`; `main.js`
lands in `implementation/out/examples/testbed-ssr-hydration-mismatch/`.
To view the mismatch banner in a browser, stage this testbed's
`index.html` next to that `main.js` and serve the directory:

```bash
cp testbeds/ssr_hydration_mismatch/index.html out/examples/testbed-ssr-hydration-mismatch/
npx http-server out/examples/testbed-ssr-hydration-mismatch -p 8080
# open http://127.0.0.1:8080/ — verify-hydration! fires
# :rf.ssr/hydration-mismatch and the mismatch-banner renders its payload.
```

## Cross-references

- [`spec/011-SSR.md` §Hydration-mismatch detection](../../spec/011-SSR.md) — the contract.
- [`spec/011-SSR.md` §Mismatch recovery and configuration](../../spec/011-SSR.md) — the recovery posture (warn-and-replace).
- [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md) — the `:rf.ssr/hydration-mismatch` taxonomy entry.
- [`implementation/ssr/test/re_frame/ssr_end_to_end_test.clj`](../../implementation/ssr/test/re_frame/ssr_end_to_end_test.clj) — the JVM-side mismatch coverage; this testbed pairs with it on the browser side.
- Sibling: [`testbeds/ssr_basic/`](../ssr_basic/) — the no-mismatch baseline.
