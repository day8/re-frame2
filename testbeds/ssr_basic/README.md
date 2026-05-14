# `testbeds/ssr-basic`

The **SSR hydration baseline**. Server-rendered HTML + a serialised
`:rf/hydration-payload` arrive baked into the static `index.html` (the
shape the JVM-side `render-to-string` + payload builder would emit per
[`spec/011-SSR.md`](../../spec/011-SSR.md)). The browser-side `run`:

1. Reads the EDN payload from `<script id="__rf_payload">`.
2. Dispatches `[:rf/hydrate payload]` (replace-app-db policy, locked).
3. Renders the registered root view against the now-seeded app-db.
4. Calls `re-frame.ssr/verify-hydration!` with the resolved render tree
   so the runtime can compare hashes and emit
   `:rf.ssr/hydration-mismatch` on disagreement.

| `data-testid` | What it carries / proves |
|---|---|
| `ssr-basic` | Root marker — visible from first byte (pre-rendered). |
| `not-hydrated` / `hydrated` | Discriminator on `:rf/hydration` presence in app-db. The static HTML ships the `not-hydrated` marker; first render after `:rf/hydrate` swaps to `hydrated`. |
| `count` | Seeded via payload's `:rf/app-db {:count 7}`. Click `inc` mutates → re-render → count++. |
| `title` | Seeded via payload's `:rf/app-db {:title "seeded"}`. Click `set-title` writes `"hydrated"`. |
| `resp-status` / `resp-ct` / `resp-cookies-count` / `resp-cookie-name` | Materialise the payload's `:rf/response` slice — proves per-request `:rf.server/*` accumulator round-trips through the wire into the view layer. |

The trace listener registered at boot mirrors every `:rf.ssr/*` and
`:rf/hydrate` trace event onto `window.__rf_trace_events()` so Playwright
can assert against the hydration handshake without poking at internal
cljs vars.

## What this surface tests (per spec/011-SSR.md)

| Spec section | Behaviour exercised |
|---|---|
| §Hydration is a defined protocol | Payload arrives, `:rf/hydrate` replaces app-db, client renders. |
| §Payload scope (canonical boundary) | The four required keys plus optional `:rf/response`; nothing else on the wire. |
| §The `:rf/hydrate` event | `:replace-app-db` policy carries the server's slice verbatim; `[:rf/hydration]` metadata stashed. |
| §Hydration-mismatch detection | `verify-hydration!` invoked post first-render; no mismatch when server-hash is nil (baseline). |
| §`:rf.ssr/check-version` (rf2-69ad2) | Fx dispatched as part of `:rf/hydrate`'s `:fx`; trace event surfaces via the listener. |
| §Response storage substrate | The payload-carried `:rf/response` shape exercises the canonical `{:status :headers :cookies :redirect}` accumulator. |
| §Frames are per-request | `:rf/frame-id` in the payload demonstrates the frame-id round-trip — server's frame becomes the addressing target on hydrate. |

## What this surface deliberately omits

- The mismatch path. The payload's `:rf/render-hash` is `nil`, so
  `verify-hydration!` no-ops cleanly. Mismatch detection is exercised
  by [`testbeds/ssr_hydration_mismatch/`](../ssr_hydration_mismatch/).
- Multi-frame hydration. Exercised by [`testbeds/ssr_multi_frame/`](../ssr_multi_frame/).
- The JVM-side render. Spec 011's hiccup → HTML emitter and FNV-1a
  hash are pinned by `implementation/ssr/test/re_frame/` (the JVM
  conformance corpus and end-to-end tests).

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/ssr-basic
# Or via the orchestrator:
npm run test:examples
```

Shadow-cljs build id is `testbeds/ssr-basic`; output lands in
`implementation/out/testbeds/ssr-basic/`. The orchestrator
(`examples/scripts/serve-and-run-examples-tests.cjs`) stages this
testbed's `index.html` next to `main.js` and serves the directory
at `/ssr-basic/`.

## Cross-references

- [`spec/011-SSR.md` §Hydration is a defined protocol](../../spec/011-SSR.md) — the round-trip contract.
- [`spec/011-SSR.md` §Payload scope](../../spec/011-SSR.md) — the canonical four payload keys.
- [`spec/011-SSR.md` §Response storage substrate](../../spec/011-SSR.md) — the per-request side-channel substrate the `:rf/response` slot serialises.
- [`examples/reagent/ssr/core.cljc`](../../examples/reagent/ssr/core.cljc) — the JVM-side `handle-request` reference; the static index.html is the byte-shape this function would emit.
- [`implementation/ssr/test/re_frame/ssr_end_to_end_test.clj`](../../implementation/ssr/test/re_frame/ssr_end_to_end_test.clj) — the JVM-side end-to-end coverage; this testbed pairs with it on the browser side.
