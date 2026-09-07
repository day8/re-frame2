# Story — Tutorial: CLJS-unit testing your variants (rf2-cb3ha)

> The recommended end-user testing recipe for a Story-using app:
> drive `run-variant` from a `cljs.test` deftest, assert against the
> resolved result map's `:status`, `:app-db` and `:assertions` slots,
> without a browser for headless cases. This is the tutorial for
> end-user variant testing; reach for [`Tutorial-Playwright.md`](Tutorial-Playwright.md)
> only when a browser-only surface (real-pointer events, viewport
> sizing, file-upload dialogs, multi-tab flows) is genuinely
> required.

## Audience and scope

This recipe is for a re-frame2 app that uses Story to author its
variants and now wants automated regression coverage. It covers:

- Wiring a `deftest` against a Story variant.
- Driving the four-phase variant lifecycle via `run-variant`.
- Asserting against the `:app-db` and `:assertions` slots in the
  returned result map.
- Sharing fixtures across tests (registrar reset, canonical-vocab
  reinstall, per-test variant register).
- Choosing between in-variant `:script` assertions vs.
  out-of-variant `is` assertions.

What this recipe is **not**: a guide to writing Story's own internal
test suite. The shipped test corpus under `tools/story/test/` is
gate-grade; this is the tutorial-shaped on-ramp for end-user app
code.

Per the project's testing direction
([`feedback_xray_story_cljs_unit_tests_not_playwright`](https://github.com/day8/re-frame2)),
Wave 1–4 migration moved 81% of Story's Playwright assertions to
CLJS-unit tests. **New end-user tests default to CLJS-unit, not
Playwright.** Headless tests cover state and event behavior; they do not
replace browser layout, real input, or pixel checks. Runtime depends on
the variant's work, including any waits or loaders.

## Prerequisites

- A Story-using app with `(story/install-canonical-vocabulary!)`
  called at boot (or relying on the auto-install gate — see
  [`001-Authoring.md`](001-Authoring.md) §Boot).
- Variants of interest registered in a CLJS namespace the test can
  require.
- A shadow-cljs `:test` build target — Story's own corpus uses
  `:node-test`; consumer apps typically wire up the same. See the
  `implementation/shadow-cljs.edn`'s `:node-test` block as a
  reference.
- `cljs.test` (shipped with ClojureScript; no extra dep needed).
- `re-frame.story.async` for promise chaining: `run-variant` always
  returns a `js/Promise`, including for a zero-loader variant.

## The starter test

Create `test/myapp/stories/counter_test.cljs` in your repo (the path
is arbitrary — shadow-cljs's `:node-test` glob picks whatever your
build config points at).

```clojure
(ns myapp.stories.counter-test
  "End-user CLJS-unit coverage for the counter variants.

   Each test drives one variant via `run-variant` and asserts
   against the returned result map's `:app-db` / `:assertions`
   slots. No browser is needed for this headless state test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.async :as async-lib]
            [re-frame.test-support :as rf-test]
            [myapp.events]   ; loads :counter/* event handlers
            [myapp.stories.counter])) ; loads :story.counter/* variants

;; ---- fixture: full reset between tests ------------------------------------
;;
;; cljs.test async bodies require MAP fixtures. The framework's :async?
;; option chooses that shape on CLJS (and the fn shape on JVM), while
;; retaining its registrar/source-store reset and teardown contract.
;; Story's own story-test/use-fixtures helper is synchronous-only today.
;; These two application namespaces expose install! thunks. In particular,
;; the Story thunk reinstalls variants after its separate side-table clear.

(use-fixtures :each
  (rf-test/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :async? true
     :init-fn (fn []
                (story/clear-all!)
                (myapp.events/install!)
                (myapp.stories.counter/install!))}))

;; ---- happy path: variant lands on the expected app-db --------------------

(deftest counter-at-five-lands-on-five
  (testing ":story.counter/at-five — loader sets :count 5; no events;
            no play; lifecycle reaches :ready"
    (async done
      (-> (story/run-variant :story.counter/at-five)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready after the four phases")
              (is (story/result-passed? result)
                  "unified verdict includes assertions and evidence failures")
              (is (= 5 (-> result :app-db :count))
                  ":counter/initialise 5 wrote :count 5")
              (story/destroy-variant! :story.counter/at-five)
              (done)))))))
```

That's the full minimum recipe — register the variant in your
source, drive it from a deftest, assert against the result map.

## How it works

### `run-variant` returns the whole shape

Story's `run-variant` runs the lifecycle and returns a promise of the
unified result map. The slots that matter for tests:

| Slot | Shape | Use |
|---|---|---|
| `:status` | `:pass` / `:fail` / `:cannot-run` / `:error` | The verdict; read with `story/result-passed?`, not lifecycle or an empty assertion list. |
| `:lifecycle` | `:pre-mount` / `:mounting` / `:loading` / `:ready` / `:error` | Mount/lifecycle progress, not the test verdict. |
| `:app-db` | the post-lifecycle `app-db` of the variant's frame | Read with `get-in` / sub-equivalents to assert structural state |
| `:assertions` | vector of `{:assertion :rf.assert/path-equals :passed? true ...}` maps | Did the in-variant `:script` assertions pass? |
| `:elapsed-ms` | number | Performance budget assertions |
| `:snapshot` | `{:variant-id ... :active-modes [...] :substrate ... :content-hash ...}` | Declared-input identity — see [`002-Runtime.md`](002-Runtime.md) §Snapshot identity |

The full shape is documented in [`002-Runtime.md`](002-Runtime.md)
§Programmatic API. Tests assert the unified verdict and the state or
individual records relevant to their regression.

`run-variant` does not render, so there is no rendered-output slot to assert
on. For DOM-shape assertions call `render-variant`, which returns its own
result map with a `:rendered` slot — see
[`017-Testing-Story.md`](017-Testing-Story.md) §Args, controls, and
`render-variant`.

### Two assertion modes — pick one (or both)

Story carries two complementary assertion styles. Both are valid;
they answer different questions:

| Mode | Where it runs | What it asserts | When to prefer |
|---|---|---|---|
| **In-variant** (`:script`) | Inside the variant's `:script` body via `:rf.assert/*` events | "Did this variant's own behaviour line up?" — record-don't-throw; the sequence continues | When the assertion is intrinsic to the variant's contract (e.g. "after three increments, `:count` is 3"). Ships with the variant; runs in every gate that drives `:script`. |
| **Out-of-variant** (`cljs.test/is`) | In the deftest body, after `run-variant` resolves | "Does this variant satisfy MY app's regression bar?" — reports to the test runner | When the assertion is consumer-side (e.g. "this variant exists", "the result map's shape matches my expectation"). Lives in the test repo, not the variant body. |

The in-variant `:script` shape rides the share URL — pasting
a `:story.counter/driven` URL into a colleague's browser runs the
same assertion sequence against their frame. Out-of-variant `is`
assertions live in your test repo; they ride your CI.

```clojure
;; In-variant — the variant body owns the assertion:
(story/reg-variant :story.counter/driven
  {:setup [[:counter/initialise 0]]
   :script
   [[:dispatch-sync [:counter/increment]]
    [:dispatch-sync [:counter/increment]]
    [:dispatch-sync [:counter/increment]]
    [:dispatch-sync [:rf.assert/sub-equals [:counter/value] 3]]]})

;; Out-of-variant — the deftest owns the assertion:
(deftest counter-driven-lands-at-three
  (async done
    (-> (story/run-variant :story.counter/driven)
        (async-lib/then
          (fn [result]
            ;; Either assertion style passes here.
            (is (= 3 (-> result :app-db :count))
                "out-of-variant: :count is 3 after three increments")
            (is (story/result-passed? result)
                "in-variant assertions and the run's evidence floor pass")
            (story/destroy-variant! :story.counter/driven)
            (done))))))
```

### Failure modes — what to assert on each

Story records four named failure shapes into `:assertions`. Tests
exercising the failure paths assert against the canonical keys per
[`004-Assertions.md`](004-Assertions.md):

| Shape | `:assertion` value | Other keys | When |
|---|---|---|---|
| Loader incomplete | `:rf.error/loader-incomplete` | `:phase :phase-1-loaders`, `:predicate` | `:loaders-complete-when` never returns truthy |
| Loader rejected | `:rf.error/exception` | `:phase :phase-1-loaders`, `:event`, `:cause` | A loader event throws |
| Schema mismatch | `:rf.error/schema-fail` | `:path`, `:expected`, `:actual` | Args don't conform to the registered schema |
| Failed assertion | the original `:rf.assert/*` keyword | `:passed? false`, `:path`, `:expected`, `:actual` | An `:rf.assert/*` event in `:script` failed |

```clojure
;; Assert on a known failure path:
(deftest counter-with-broken-loader-records-exception
  (async done
    (-> (story/run-variant :story.counter/broken-loader)
        (async-lib/then
          (fn [result]
            (let [rejection (some (fn [a]
                                    (when (and (= :rf.error/exception
                                                  (:assertion a))
                                               (= :phase-1-loaders
                                                  (:phase a)))
                                      a))
                                  (:assertions result))]
              (is (some? rejection)
                  "exception captured into :assertions, not thrown out")
              (is (false? (:passed? rejection))
                  "the assertion is failed; the test can branch on it"))
            (story/destroy-variant! :story.counter/broken-loader)
            (done))))))
```

### Sync vs. async

Always use `async` + `then` for `run-variant` on CLJS. Immediate settlement
does not change the return type. For a general keyword-or-inline-map
target, `story/run` has the same promise contract. `story/is` additionally
reports the result before its promise resolves; do not report it twice.

On the JVM, await the `CompletableFuture` with
`re-frame.story.async/deref-blocking`, or use `story/is`, which blocks and
reports. See [API §Execution verbs](API.md#execution-verbs--run--is--explain).

### Per-test reset — match the host's fixture shape

The starter uses the framework's existing async-capable fixture, with
Story setup in `:init-fn`; it does not hand-roll registrar or frame resets.
Build it after requiring your app namespaces so its stable baseline
contains their registrations. Re-register Story variants after
`story/clear-all!`, because Story's side-table is separate from the
framework registrar. The first Story registration reinstalls its canonical
vocabulary automatically.

For synchronous tests, `re-frame.story.test-support/use-fixtures` and
`with-clean-registry` remain the shorter Story-specific helpers. Their
current implementation returns only a fn fixture; it does not forward
`:async?`. A fn fixture cannot bracket a CLJS `(async done …)` test, and a
literal map fixture cannot bracket a JVM test. The framework helper's
`:async? true` selects the correct shape per host.

The in-repo counter/login testbeds have an additional co-loaded image
baseline constraint; their documented custom fixtures are not a second
recipe for a standalone consumer app.

## Driving from `:setup` vs. `:script`

Story's preferred path is to put driving events in the variant
body, not in the test. `:setup` carries the preconditions (the
events that establish the starting state); `:script` carries the
ordered behaviour-under-test (dispatches + assertions). Two reasons:

- **Reproducibility.** A variant URL pasted from the browser's
  address bar reproduces the same `:setup` + `:script` sequence on a
  colleague's machine. Events that live only in your test repo do not.
- **EDN, not code.** The variant body is pure data; it serialises
  to a Xray share-pack, to the MCP write surface, to a snapshot
  fixture. Test-side driving events serialise only as test code.

```clojure
;; Preferred — the variant body declares the driving sequence:
(story/reg-variant :story.checkout/server-error
  {:loaders [[:checkout/load-order :order-1234]]
   :setup   [[:checkout/select-payment :card]]
   :script  [[:dispatch-sync [:checkout/submit]]
             [:dispatch-sync [:rf.assert/path-equals
                              [:checkout :error] :server-error]]]})

;; The deftest asserts on the result — minimal test-side logic:
(deftest checkout-server-error-records-the-error
  (async done
    (-> (story/run-variant :story.checkout/server-error)
        (async-lib/then
          (fn [result]
            (is (= :server-error
                   (-> result :app-db :checkout :error)))
            (story/destroy-variant! :story.checkout/server-error)
            (done))))))
```

Use test-side driving (e.g. `(rf/dispatch-sync ...)` after
`run-variant` returns) only when the test exercises a scenario the
variant body deliberately doesn't carry — a fuzz-driven sequence,
a property-based generator, etc.

## Common pitfalls

- **Forgetting to reinstall Story variants.** `story/clear-all!` clears
  its side-table; call your story registration thunk in `:init-fn`.
- **Asserting on the promise instead of its result.** Every
  `run-variant` call returns a promise. Read result keys in `then`.
- **Not calling `destroy-variant!` after each test.** The variant's
  frame leaks across tests if you don't; subsequent runs of the
  same variant id still reset their state, but unused frames and their
  resources should not be kept alive. Explicit destroy after the run is
  the caller's cleanup boundary.
- **Disabling Story in the test build.** The production-app define
  `re-frame.story.config/enabled? false` elides `reg-*` calls (per
  [`005-SOTA-Features.md`](005-SOTA-Features.md) §Production
  elision). Run tests against `:node-test` (no advanced
  compilation), not against your application's production bundle.
- **Confusing in-variant and out-of-variant assertion modes.** The
  `:rf.assert/*` events in `:script` record into
  `(:assertions result)` and do NOT throw — `(is ...)` in your
  deftest reports pass/fail to the test runner. Assert on the unified
  verdict, or call `story/report-result!` on a `story/run` result for
  per-assertion and run-level reports.

## Multi-frame e2e — when one variant isn't enough

For scenarios that exercise interactions across multiple variants
(e.g. a routing flow that pivots from `:story.checkout/cart` to
`:story.checkout/confirm`), reach for the
`re-frame.story.test-helpers.e2e-multi-frame` helpers shipped under
`tools/story/test/`. These are repository test helpers, not included in
the published jar's `src` path. They illustrate how one deftest can drive
multiple frames; consumer tests own their equivalent setup and cleanup.

See the worked examples in `tools/story/test/re_frame/story/panels_e2e/`
— `variant_lifecycle_e2e_cljs_test.cljs` is the canonical reference
for the four-phase lifecycle; `reg_variant_e2e_cljs_test.cljs`
exercises registration-time concerns.

## When to graduate to Playwright

Reach for [`Tutorial-Playwright.md`](Tutorial-Playwright.md) only
when the test genuinely needs a browser:

| Surface | Why Playwright |
|---|---|
| Real-pointer events (drag, multi-finger, gesture) | DOM event synthesis differs from real input |
| Viewport sizing + media-query branches | CLJS-node has no layout engine |
| File-upload dialogs / permissions prompts | Browser-only chrome |
| Multi-tab flows / `window.open` | Browser-only |
| Visual-regression pixel diffs | Needs a real DOM + render pipeline |

For everything else — and that is the overwhelming majority of
end-user variant coverage — the CLJS-unit recipe above is the
right starting point. Choose browser coverage for the browser-only
contract, not merely because the test subject is a UI component.

## Cross-references

- [`001-Authoring.md`](001-Authoring.md) §Mental model — Args,
  frames, schema-derived controls; the conceptual frame the test
  shape lines up against.
- [`002-Runtime.md`](002-Runtime.md) §Programmatic API — the
  `run-variant` / `reset-variant` / `snapshot-identity` reference.
- [`004-Assertions.md`](004-Assertions.md) — the canonical `:rf.assert/*`
  events (seven dispatched + the tape-evaluated `:rf.assert/schema-error`)
  and record-don't-throw semantics.
- [`Tutorial-Playwright.md`](Tutorial-Playwright.md) — the
  browser-required alternative; secondary path for the surfaces
  named above.
- [`Tutorial-Embed.md`](Tutorial-Embed.md) — the companion recipe
  for embedding variants as iframes in docs sites; orthogonal to
  testing.
- [`Migration-Audit.md`](Migration-Audit.md) — the Wave 1–4
  Playwright → CLJS migration manifest; canonical lineage for why
  CLJS-unit is the default.
