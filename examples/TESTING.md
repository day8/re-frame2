# Testing the examples

> How the `examples/` tree stays working.

The `examples/` tree is **test-free**: no `*.spec.cjs` lives under it. The examples
still have to keep working, so their coverage comes from gates that sit *outside*
the tree. This page maps those gates and the two conventions a new example follows.

Read it alongside [`README.md`](README.md) (the example layout) and each example's
own `README.md` (what it demonstrates). For *which* gate runs in *which* CI tier,
see [`../TESTING.md`](../TESTING.md).

## How the examples are covered

| Command | What it checks | Lives in |
|---|---|---|
| `npm run test:examples-compile` | Compiles every declared `:examples/*` shadow-cljs build and fails on any error **or warning**. The build list is derived from `shadow-cljs.edn`, so a newly declared example is swept automatically. | [`implementation/scripts/check-examples-compile.cjs`](../implementation/scripts/check-examples-compile.cjs) |
| `npm run test:cljs` | The shadow-cljs `:node-test` bundle, run on Node. `:ns-regexp` is `cljs-test$`, so it picks up every `*-cljs-test` wrapper — including the **example wrappers**, which require their example's `core` ns and drive its events, subs and fixtures headlessly (no DOM). This is where the example wrappers actually run. | [`implementation/package.json` `test:cljs`](../implementation/package.json) |
| `npm run test:browser` | The shadow-cljs `:browser-test` bundle in one Chromium page. `:ns-regexp` is narrowed to `-dom-cljs-test$`, so it runs **only** the DOM-dependent wrappers (those that mount real React via `react-dom/client`). The example wrappers do **not** mount the DOM, so they keep the plain `-cljs-test` suffix and run under `test:cljs`, not here. | [`implementation/scripts/serve-and-run-browser-tests.cjs`](../implementation/scripts/serve-and-run-browser-tests.cjs) |
| `npm run test:script-policy` | Static scanners over the example tree: `check-examples-assets.cjs` (shared stylesheet asset presence + WCAG palette-contrast / focus-ring contracts on each `index.html`) and `check-reagent-slim-boundary.cjs` (the stock Reagent tree never requires `reagent2.*` / the slim adapter). | [`examples/scripts/`](scripts/) |

`test:examples-compile` and the `test:script-policy` scanners add nothing under
`examples/` — they keep the test-free policy intact.

Real-regression coverage for framework behaviour lives in the framework gates —
`test:cljs`, `test:xray-feature-gate`, `test:bundle-isolation`, `test:perf-bundle`,
and mcp-conformance — not here.

## The design-led examples

Three examples exist to prove *polished visuals + interaction* on each substrate:
the Reagent [`notebook`](reagent/notebook/), the UIx
[`dashboard_uix`](uix/dashboard_uix/), and the Helix
[`process_monitor_helix`](helix/process_monitor_helix/). They share the
"Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css).

`test:examples-compile` sweeps their builds like any other, and
`check-examples-assets` covers the shared design-system contracts. The live render
itself — a nonblank page, a running tick/update loop, filter/selection interaction,
no horizontal overflow at a narrow viewport — is a **by-eye check**: open the page
and look when you touch that example's markup, CSS, or dataflow. The
[`dashboard_uix` accessibility + responsive notes](uix/dashboard_uix/README.md#accessibility--responsive--what-to-copy)
spell out the shape to look for.

## Convention: example mount-isolation — defer DOM mount to `mount!`

The example wrappers all compile into **one** `:node-test` bundle and share **one**
re-frame registry; the DOM-dependent tests compile into **one** `:browser-test`
bundle, **one** Chromium page, sharing **one** `#app` mount point. So an example
must not touch the DOM just by being loaded:

> Do no DOM mount side effects at namespace-load time. Defer `create-root` (or
> your substrate's equivalent) to the example's `mount!` fn.

`run` is the bundle entry point, wired as the `:init-fn` of the example's
shadow-cljs build in
[`implementation/shadow-cljs.edn`](../implementation/shadow-cljs.edn) (e.g.
`:examples/counter` → `:init-fn counter.core/run`). shadow-cljs resolves the symbol
inside the compiled bundle, so it needs no `^:export`.

A mount at ns-load instead would have a co-required example race `create-root` on
the same shared `#app`, leaking one example's mount into another's tests. This is
enforced by code review.

### Boot shape: `boot!`, `mount!`, `frame-provider`

A TodoMVC-style example splits its boot into two fns, called in order by `run`:

- **`boot!`** runs once, before the first render: install the adapter
  (`rf/init!`) and any host listeners. Listener installs are made idempotent
  (remove-then-add the same Var) so a repeated `run` or a reload never stacks
  duplicates.
- **`mount!`** creates the React root **lazily** on first call (not at ns-load)
  and renders. The render root is a `frame-provider {:id … :initial-events …}`:
  on first mount it creates the app frame, applies its config, and runs
  `:initial-events` once to seed app-db; on a later mount it reuses the frame
  and skips re-seeding, so state survives.

The React root lives in a `defonce` atom and `mount!` carries `^:dev/after-load`
— that pair **is the hot-reload requirement**. `defonce` keeps the one root across
reloads (React rejects a second `create-root` on a live node), and
`^:dev/after-load` makes shadow re-run `mount!` after each reload so the edited
views re-render against the surviving root and frame.

The shape every example uses (Reagent shown; UIx and Helix do the same):

```clojure
;; ns-load: no DOM side effects. The atom holds the React root once mount! makes it.
(defonce react-root (atom nil))

(def app-frame :rf/default)            ; an id we pick; no framework privilege

;; ^:dev/after-load: shadow re-runs this on every reload. defonce root + lazy
;; create-root means the root is made once and reused across reloads.
(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                ;; frame-provider creates + seeds the frame on first mount,
                ;; reuses it (no re-seed) on reload.
                [rf/frame-provider {:id             app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))

(defn- boot! []                        ; install adapter + listeners, once
  (rf/init! reagent-adapter/adapter))

(defn run []                           ; shadow :init-fn — runs once, at page load
  (boot!)
  (mount!))
```

[`examples/reagent/todomvc/core.cljs`](reagent/todomvc/core.cljs) is the fullest
worked version (it adds an idempotent `hashchange` listener in `boot!` and a
`:url-bound? true` frame in `mount!`);
[`examples/reagent/counter/core.cljs`](reagent/counter/core.cljs) is the minimal one.

## Convention: prefix every registered id

The `:node-test` bundle co-loads every example wrapper, so it shares the re-frame
registries (handlers, subs, machines, schemas, …) across all of them. Two examples
that both register `:login` would fight over the same slot. So each example
**prefixes every id with its own stem**:

- `realworld.core` registers `:auth/login`, `:articles/load`, `:comments/submit`, …
- `nine-states.core` registers `:nine-states.app/initialise`, `:new-todo/submit`, …

Each `*-cljs-test` wrapper installs a `test-support/make-reset-runtime-fixture`
per-test fixture, so every test runs against a fresh frame — but the
*registrations* are shared across the bundle, and prefix discipline is what keeps
them from colliding.

### Two deliberate id-shares

Some sibling examples register the **same** ids on purpose, because the
id-identity *is* the demonstration — byte-for-byte identical dataflow proving
parity across a boundary. These are the two blessed parity exceptions; each
example's own README links here for the canonical statement.

#### Exception 1 — the stock/slim counter `:counter/*` id share

[`reagent/counter`](reagent/counter/) and
[`reagent-slim/counter_slim_and_fast`](reagent-slim/counter_slim_and_fast/) share
the `:counter/*` event + sub ids, proving the two Reagent bridges run identical
dataflow.

#### Exception 2 — the cross-substrate Reagent/UIx/Helix id share

The Reagent, UIx, and Helix counter/login triplets share their event, sub, fx,
machine, and schema ids, proving one dataflow across three reactive substrates.

Both are safe because each side is a **separate standalone build** that never
shares a JS runtime, and the bundle-isolation gates
(`npm run test:bundle-isolation`, `npm run test:reagent-slim:bundle-isolation`)
keep those builds split. Views are never shared — each substrate's views carry
their own namespace. If two of these examples were ever co-loaded into one frame
image, the shared ids must be disambiguated first (distinct `:images` per frame,
or prefixed stems); a naive co-load fails loud at frame creation rather than
clobbering silently.

## Adding a tested example

1. **Mount in `mount!`, not at ns-load** — per the convention above.
2. **Prefix every registered id** with the example's folder stem.
3. **A wrapper test ns** at
   `implementation/adapters/<substrate>/test/re_frame/<example>_cljs_test.cljs`
   (the `-cljs-test` suffix is how the `:node-test` build picks it up, via its
   `cljs-test$` regex). It `:require`s the example's production `core` ns — so its
   handlers, subs, views, and machines register at ns-load — and drives them with
   fixtures and canned stubs defined in the wrapper itself. It runs headlessly on
   Node under `test:cljs`, so it must not mount the DOM. The example source stays
   test-free.

If a wrapper genuinely needs a live React root (a real `react-dom/client` mount),
give it the `-dom-cljs-test` suffix instead — `:browser-test`'s `-dom-cljs-test$`
regex then runs it under `test:browser` in Chromium, and `:node-test`'s broader
`cljs-test$` still matches it too. The example wrappers don't need this; they assert
against app-db and subs directly.

A standalone `:examples/*` build needs no test wiring at all to get compile
coverage — declaring it in `shadow-cljs.edn` is enough for `test:examples-compile`
to sweep it.

## Adapter smokes are not an example surface

The three adapter browser smokes (Reagent / UIx / Helix) live with the adapters
they test — see
[`../implementation/adapters/TESTING.md`](../implementation/adapters/TESTING.md).
They reuse the shared Playwright assertion matchers in
[`scripts/spec-helpers.cjs`](scripts/spec-helpers.cjs), which stay in this tree as
the single home for every hand-rolled Playwright spec in the repo. Do not add a
`*.spec.cjs` under `examples/`. To add a smoke, follow
[`../implementation/adapters/TESTING.md` §Adding a new adapter smoke](../implementation/adapters/TESTING.md#adding-a-new-adapter-smoke).
