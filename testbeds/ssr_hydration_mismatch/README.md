# `testbeds/ssr-hydration-mismatch`

The deliberate-mismatch surface. The static `index.html` bakes server
markup whose text diverges from the client's render: the root's first
child says `rendered on the server` where the client's root view renders
`rendered on the client`. The browser-side `run` seeds the frame with
`ssr/hydrate!`, then hydrates that markup through the Reagent adapter's
`render!` with `{:hydrate? true}`. React meets the divergent text, recovers
by re-rendering the root on the client, and calls the root's
`onRecoverableError` — the adoption reporter the framework installs on
every hydrating root, which emits `:rf.ssr/hydration-mismatch` per
[`spec/011-SSR.md` §Hydration-mismatch detection](../../spec/011-SSR.md).
The reporter then hands the error to the root's `:on-recoverable-error`,
which logs it as a console warning: without one, React's default report
raises it through `reportError` as an uncaught page error, and here the
recovery is the expected outcome.

The trace listener routes the captured trace through
`::record-mismatch` so the dev-mode banner re-renders with the
structured trace visible inline.

| `data-testid` | What it carries / proves |
|---|---|
| `ssr-hydration-mismatch` | Root marker. |
| `render-origin` | The divergent node: `rendered on the server` in the static markup, `rendered on the client` once React has re-rendered the root. |
| `hydrated` / `not-hydrated` | Discriminator on the `[:rf.runtime/ssr :hydration]` metadata presence in runtime-db (read via a `reg-runtime-sub`). |
| `mismatch-banner` / `mismatch-banner-empty` | Before the recovery the `-empty` variant is rendered; afterwards the banner with the structured trace replaces it. |
| `mismatch-where` | The door that hydrated the root: `re-frame.substrate.spine/make-render`. |
| `mismatch-recovery` | `:warned-and-replaced` — React has already re-rendered the root on the client. |
| `mismatch-error` | React's own recoverable-error message (the trace's `:error`). |
| `count` / `inc` | After the recovery the page remains interactive — warn-and-replace is degraded-but-running, not crash. |

## What this surface tests (per spec/011-SSR.md)

| Spec section | Behaviour exercised |
|---|---|
| §Hydration-mismatch detection | A hydrating root reports the divergence React recovers from as `:rf.ssr/hydration-mismatch`, with React's message (`:error`), `:where` and `:recovery :warned-and-replaced`. |
| §Client flow | `ssr/hydrate!` seeds the frame from `__rf_payload` before the hydrating render. |
| §The `:rf/hydrate` event | The hydration metadata lands in runtime-db at `[:rf.runtime/ssr :hydration]` through the framework-registered handler — user code didn't have to participate. |

## What the adoption channel does not cover

React reports text and element divergences, the ones it recovers from.
An attribute-only divergence draws React's development `console.error`
and nothing else: React does not patch it and calls no
`onRecoverableError`, so no `:rf.ssr/hydration-mismatch` fires. This
surface diverges in text so the trace fires.

## Running

This surface is a dev / Xray observation target, staged by the Xray
feature-matrix scenario "hydration mismatch debugger". It is not staged
by any smoke gate (the adapter-smoke orchestrator serves only the 3
adapter smokes under `implementation/adapters/<name>/testbed/`).

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
# open http://127.0.0.1:8080/ — React recovers from the divergent text,
# :rf.ssr/hydration-mismatch fires and the mismatch-banner renders it.
```

## Cross-references

- [`spec/011-SSR.md` §Hydration-mismatch detection](../../spec/011-SSR.md) — the contract.
- [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md) — the `:rf.ssr/hydration-mismatch` taxonomy entry.
- [`implementation/adapters/reagent/test/re_frame/adapter_hydrate_root_options_dom_cljs_test.cljs`](../../implementation/adapters/reagent/test/re_frame/adapter_hydrate_root_options_dom_cljs_test.cljs) — the browser-lane coverage of the adoption reporter; this testbed is its observation target.
- sibling: [`testbeds/ssr_basic/`](../ssr_basic/) — the no-mismatch baseline.
