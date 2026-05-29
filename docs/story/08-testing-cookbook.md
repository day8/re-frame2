# 8. Testing cookbook

> **What this is.** A flat recipe sheet for testing every layer of a
> re-frame2 app — a handler, a subscription, a pure view, a machine, a
> route, a schema boundary, a whole frame, a trace, an inline plan, and a
> Story variant. Each recipe is a minimal *runnable* example plus the one
> sentence that matters: **which verb, which assertion, which runner**,
> and where `:cannot-run` bites. Copy a recipe, swap your ids in, ship the
> test.

This page assumes you already know *why* the seam exists — the pure side
of a re-frame2 app (handlers, subs, machine transitions, effect maps) is
testable on the JVM with no browser, no mocks, no `act()`. If that claim
isn't intuitive yet, read [Guide 13 — Testing](../guide/13-testing.md)
first; it argues the seam. This page is the cookbook the guide chapter
points at — the ten worked recipes, side by side, so the default choice
for any layer is one lookup away.

One vocabulary spans all of it. The assertion atom `[:rf.assert/id & args]`
is the same whether it fires in a unit test, an inline plan, or a Story
variant; the run-result shape is the same whether a registered variant or
an inline map produced it. There is **no second testing vocabulary** — the
whole point of the substrate is that you learn the atoms once and reuse
them everywhere. The normative contract is
[`017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md);
this page is the consumer extract.

## The two surfaces, and when to reach for each

There are two ways to drive behaviour under test, and they share a result
vocabulary so you can move between them without a translation table.

| Surface | Reach for it when | Require |
|---|---|---|
| **Substrate** — `make-frame` / `dispatch-sync` / `compute-sub` / `machine-transition`, plus `re-frame.test-support` + `re-frame.test-helpers` | testing ONE layer in isolation: a handler, a sub, a transition, a view, a route round-trip, a schema boundary | `[re-frame.core :as rf]` `[re-frame.test-support :as ts]` |
| **Story** — `story/run` / `story/is` / `story/explain`, inline plans + registered variants | testing a *flow* (several settled dispatches), or curating a flow as a navigable, replayable artifact | `[re-frame.story :as story]` |

Substrate recipes (handler, sub, view, machine, route, schema) are the
bulk of what you write — they run on the JVM under `clojure -M:test` in
milliseconds. Story recipes (inline plan, variant) are for flow-shaped
tests and for behaviour you want to *keep* as a browsable artifact. Both
sit on the same runtime; neither is a mock.

Throughout, `rf/get-frame-db` reads a frame's current `app-db` value (no
deref), and `rf/with-new-frame` makes an isolated frame and tears it down
on exit. Each test wants its own frame — isolation is a property of the
setup, not a discipline you have to remember (Guide 13 §Isolation).

---

## Recipe 1 — A handler (event)

A `reg-event-db` handler is `(db, event) → db`, pure. Pull it out of the
registry and call it. No frame, no dispatch, no runtime.

```clojure
(deftest counter-inc
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))]
    (is (= 6 (:count (handler {:count 5} [:counter/inc]))))))
```

A `reg-event-fx` handler returns an **effect map** — so you assert on the
*shape of the effects it asks for*, never on a side effect performed:

```clojure
(deftest login-asks-for-http
  (let [handler (:handler-fn (rf/handler-meta :event :user/login))
        result  (handler {:db {}} [:user/login {:email "a@b.c" :password "x"}])]
    (is (true? (get-in result [:db :auth/loading?])))
    (is (= :rf.http/managed (ffirst (:fx result))))))
```

- **Verb:** plain `clojure.test` `is` over the returned db / effect map.
- **Assertion:** none needed — the handler is a function; you assert on its
  return value directly.
- **Runner:** JVM. No frame.
- **`:cannot-run`:** not applicable — there is no runner to refuse.

---

## Recipe 2 — A subscription

A sub's derivation is a pure function of `app-db`, and `compute-sub` runs
it with no reactive cache. Prefer driving real state through events (it
survives a db-shape rename) over a hard-coded literal db:

```clojure
(deftest pending-todos-sub
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todos/add {:id 1 :status :pending}])
    (rf/dispatch-sync [:todos/add {:id 2 :status :done}])
    (is (= 1 (count (rf/compute-sub [:pending-todos] (rf/get-frame-db f)))))))
```

`compute-sub` is pure: same `(query-v, db)`, same answer. Composed subs
(`:<-`) compute transitively — inputs first, layer on top — with no
reactive machinery.

- **Verb:** `is` over the `compute-sub` return.
- **Assertion:** none needed at the substrate level. Inside a Story
  variant the equivalent is `[:rf.assert/sub-equals [:pending-todos] 1]`,
  which evaluates the sub through `compute-sub` against the frame's
  app-db.
- **Runner:** JVM.
- **`:cannot-run`:** not applicable. **Note the honesty rule:** a Story
  `:sub-overrides` value does NOT satisfy `:rf.assert/sub-equals` — overrides
  feed the render path only, never `compute-sub`, so subscription
  correctness is always proven by real state, never by an override
  (017 §View-state subscription overrides).

---

## Recipe 3 — A pure view

A view-fn is a function; hiccup is a vector. Call it and walk the tree
with `re-frame.test-helpers`. This catches the two bugs state assertions
miss: state-correct-but-view-broken, and wrong-frame dispatch.

```clojure
(:require [re-frame.test-helpers :as h])

(deftest counter-view-shows-and-fires
  (rf/with-new-frame [f (rf/make-frame {:on-create [:counter/init]})]
    (rf/dispatch-sync [:counter/inc])
    (let [tree  (counter-view {:n (:n (rf/get-frame-db f))})
          label (h/find-by-testid tree "counter-label")
          btn   (h/find-by-testid tree "counter-inc")]
      (is (= "Count: 1" (h/text-content label)))   ; view shows the value
      (h/invoke-handler btn :on-click nil)          ; fire the click
      (is (= 2 (:n (rf/get-frame-db f)))))))         ; dispatch landed
```

`find-by-testid` anchors on a stable `:data-testid`; `text-content` reads
what the user sees; `invoke-handler` pulls `:on-click` off the attrs and
calls it. The walker expands nested function-components, so a parent view
gets fully walked from one call.

- **Verb:** `is` over hiccup-walk results.
- **Assertion:** none needed — you assert on the returned hiccup.
- **Runner:** JVM. Hiccup is data; React never mounts. For *markup*
  assertions (`<button disabled>`) reach for `render-to-string` instead
  (Guide 20 — Server-side); for genuine click-through-the-DOM behaviour
  (focus, keyboard, scroll, second-mount bugs) reach for Playwright or a
  CLJS-mounted test — few of those, and they earn their slowness.
- **`:cannot-run`:** in a Story variant, a `:rf.assert/dom-*` /
  `:rf.assert/visual-snapshot` / `:rf.assert/a11y` assertion under the
  default `:headless` runner records `:cannot-run` (see Recipe 11) rather
  than under-flushing and passing falsely.

---

## Recipe 4 — A machine (FSM)

Machines test at three depths, each with a sharper failure signal. Most
days you write Depth 1 only.

**Depth 1 — the pure transition.** No frame, no app-db, no router:

```clojure
(deftest login-submit-transitions
  (let [{s ::result/snap} (rf/machine-transition login-flow
                                                 {:state :idle :data {:attempts 0}}
                                                 [:auth.login/submit {:email "a@b.c"}])]
    (is (= :submitting (:state s)))))
```

**Depth 2 — the unregistered handler fn.** `make-machine-handler` turns
the machine into an event-handler fn — tests that the machine lifts its
effects into the handler protocol.

**Depth 3 — registered in a test frame.** The full integration: register,
dispatch, assert against `app-db`.

```clojure
(rf/with-new-frame [f (rf/make-frame {})]
  (rf/reg-machine :auth.login/flow login-flow)
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit {:email "a@b.c"}]])
  (is (= :submitting (get-in (rf/get-frame-db f)
                             [:rf/runtime :machines :snapshots :auth.login/flow :state]))))
```

- **Verb:** `is` over the snapshot / db. Inside a Story variant the
  terminal assertion is `[:rf.assert/state-is :auth.login/flow :submitting]`.
- **Assertion (Story):** `:rf.assert/state-is`.
- **Runner:** JVM at all three depths. `machine-transition` is a pure
  function.
- **`:cannot-run`:** not applicable.

The depths exist so failure *localises*: red Depth 1 = transition logic
wrong; green Depth 1 + red Depth 2 = effect-lifting boundary broken; green
Depth 2 + red Depth 3 = frame wiring wrong.

---

## Recipe 5 — A route

Routes round-trip: `rf/route-url` builds a URL from a route id + params,
and `rf/match-url` is its inverse. Test the round-trip and assert it's
lossless.

```clojure
(deftest article-route-round-trips
  (rf/reg-route :route/article {:path "/articles/:id" :params [:map [:id :string]]})
  (let [url (rf/route-url :route/article {:id "intro"})]
    (is (= "/articles/intro" url))
    (let [m (rf/match-url url)]
      (is (= :route/article (:route-id m)))
      (is (= {:id "intro"}  (:params m))))))
```

`match-url` returns `{:route-id :params :query :fragment :validation-failed?
:validation-error}` for the first matching route, or `nil` for no match.
When a route declares `:params` / `:query` Malli schemas, a malformed URL
surfaces `:validation-failed? true` + a `:validation-error` explanation —
assert on that for the rejection path:

```clojure
(deftest article-route-rejects-bad-params
  (rf/reg-route :route/n {:path "/n/:x" :params [:map [:x :int]]})
  (is (:validation-failed? (rf/match-url "/n/not-an-int"))))
```

- **Verb:** `is` over `route-url` / `match-url`.
- **Assertion:** none needed. `route-url` throws
  `:rf.error/route-url-validation` when *your* params don't conform (a
  caller bug, not user input) — assert the throw with `(is (thrown? ...))`
  for that case.
- **Runner:** JVM. Routing is pure data.
- **`:cannot-run`:** not applicable.

---

## Recipe 6 — A schema check

A registered app-db / event / sub schema makes a malformed value a
*runtime failure*, not a silent corruption. Test the boundary two ways.

**Substrate — assert the value is accepted / rejected by your schema.**
Register the schema, dispatch a real event, and read the resulting db; a
violation emits a `:rf.error/schema-validation-failure` trace.

**Story — declare the expected violation.** Inside a Story variant or
inline plan, `:rf.assert/schema-error` is the *only* schema author surface
(there is deliberately no `:rf.assert/no-schema-errors` — a schema-clean
run is the knob-free **floor**). It declares an EXPECTED violation that the
run-result matches against the projected epoch-tape evidence:

```clojure
(story/is
  {:setup  [[:dispatch [:app/init]]]
   :script [[:dispatch [:checkout/submit {:bad :payload}]]]
   :assertions [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]}
  {:runner :headless})
```

- **Verb:** `story/is` (reports each assertion to `clojure.test`).
- **Assertion:** `:rf.assert/schema-error` (requires the `:schema`
  capability token — satisfied by `:headless`).
- **Runner:** `:headless`.
- **The floor, exactly:** any emitted `:rf.error/schema-validation-failure`
  **fails the run** unless *exactly* consumed by a declared
  `:rf.assert/schema-error` (a pure multiset match by surface selector).
  An *unexpected* violation fails the run; a *missing* expected violation
  also fails (the declared expectation matched nothing). Rollback cannot
  hide a violation — the floor reads the retained epoch tape, not the final
  app-db (017 §Schema rule).
- **`:cannot-run`:** not applicable — `:schema` is a headless-floor token.

---

## Recipe 7 — A frame integration test

For the integration-shaped test — several events in sequence, assert at
the end — drive the whole cascade through `dispatch-sync` and override the
one impure domino. `dispatch-sync` **drains synchronously to fixed point**,
so the assertion on the next line sees fully-committed state — no `act()`,
no flush to await.

```clojure
(deftest auth-happy-path
  (rf/with-new-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/email-changed "alice@example.com"])
    (rf/dispatch-sync [:auth/login-pressed]
                      {:fx-overrides {:rf.http/managed
                                      (fn [_ _] {:status 200 :body {:user/id 42}})}})
    (is (= :authed (get-in (rf/get-frame-db f) [:auth :state])))))
```

`:fx-overrides` **redirects** an effect for the length of one dispatch —
the exact dispatch shape the real `:rf.http/managed` would produce lands
in your fn. It's a redirect, not a mock; the seam, used as designed. For
route-level HTTP replies use `rf/with-managed-request-stubs` (Guide 13
§Stubbing without mocking); in a Story variant the equivalent is the
first-class `:network` slot:

```clojure
{:network {[:get  "/api/session"] {:reply {:ok {:user/id 42}}}
           [:post "/api/login"]   {:reply {:failure {:kind :rf.http/http-4xx :status 401}}}}}
```

- **Verb:** `dispatch-sync` + `is`; or `story/run` / `story/is` for the
  Story-flow equivalent. `ts/dispatch-sequence` reads a list of events as
  one intention.
- **Assertion:** `ts/assert-path-equals [:auth :state] :authed` mirrors
  `:rf.assert/path-equals` — one vocabulary across unit and Story tests.
- **Runner:** JVM (substrate) / `:headless` (Story).
- **`:cannot-run`:** not applicable headless.

---

## Recipe 8 — A trace-based assertion

When you want to assert *control flow* — "this event fired during the
cascade" — without pinning every intermediate state, use the trace. In a
Story variant or inline plan that is `:rf.assert/dispatched?` (an event was
observed) and `:rf.assert/effect-emitted` (an fx was requested):

```clojure
(story/is
  {:script [[:dispatch [:cart/checkout]]
            [:assert [:rf.assert/dispatched?   [:analytics/track]]]
            [:assert [:rf.assert/effect-emitted :rf.http/managed]]]}
  {:runner :headless})
```

`:rf.assert/no-warnings` asserts the run produced no warning trace events —
the cheap "nothing went sideways" guard. At the substrate level, a tiny
recorder interceptor (Guide 13 §Stubbing without mocking) collects events
into an atom for the same control-flow assertions outside Story.

- **Verb:** `story/is` (or `story/run` for the raw result).
- **Assertion:** `:rf.assert/dispatched?`, `:rf.assert/effect-emitted`,
  `:rf.assert/no-warnings` — all `:trace` / `:app-db` / `:effects` tokens,
  satisfied by `:headless`.
- **Runner:** `:headless`. These project from the retained epoch tape —
  the same `:trace-summary` / `:effects` / `:warnings` slots the
  run-result carries.
- **`:cannot-run`:** not applicable headless.

---

## Recipe 9 — An inline plan

An inline plan is an executable plan **map** that is not registered as a
Story variant — the registry-free flow test. `story/run` and `story/is`
accept a map target as readily as a keyword; the map runs against a fresh
anonymous frame that is torn down on resolve, returning the SAME unified
run-result a registered variant returns.

```clojure
(deftest checkout-flow-inline
  (story/is
    {:setup  [[:dispatch [:cart/add {:sku "A"}]]]
     :script [[:dispatch [:checkout/submit]]
              [:assert [:rf.assert/path-equals [:checkout :state] :submitted]]]
     :checks [:check/no-runtime-errors]}
    {:runner :headless}))
```

The author-facing vocabulary is `:setup` (preconditions), `:script`
(ordered behaviour under test — each `[:dispatch event-vector]` settles to
fixed point), `:assertions` (terminal) and `[:assert atom]` (mid-script
checkpoint). An inline plan MAY `:compose` a **registered** check or
fragment; a missing one fails cleanly with a structured
`:rf.error/story-compose-unknown` result (no frame allocated, no exception
escapes).

- **Verb:** `story/is` (reports per assertion) or `story/run` (returns a
  promise/future of the run-result).
- **Assertion:** any `:rf.assert/*` atom, in `:assertions` or as an
  in-script `[:assert ...]` checkpoint.
- **Runner:** `:headless` by default; pass `{:runner :dom}` / `:browser`
  for DOM / pixel assertions.
- **`:cannot-run`:** under the default fixed-runner `:headless`, an
  assertion whose required token the runner lacks (a DOM step, a visual
  snapshot) records `:cannot-run` — a distinct third status, never a silent
  pass — and the variant is `:cannot-run` if that's its only unmet
  expectation (Recipe 11).
- **The metamorphic guarantee:** an inline plan and a registered variant
  describing the *same* behaviour produce equal final app-db + assertion
  records after `story/canonicalize`, and share a `run-hash`. So a quick
  inline plan can be promoted to a curated variant without changing what it
  proves (017 §Inline plan / §Canonicalization).

---

## Recipe 10 — A Story variant

A Story variant is the curated, navigable, replayable form of a flow test
— register it once, and it's browsable in the Story UI, runnable in CI,
and consumable by `story/is` in `clojure.test`. The body is data-shaped;
functions live behind registered ids.

```clojure
(story/reg-variant :story.checkout/submits
  {:setup      [[:dispatch [:cart/add {:sku "A"}]]]
   :script     [[:dispatch [:checkout/submit]]]
   :checks     [:check/no-runtime-errors]
   :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]
   :tags       #{:test}})

;; in a test ns:
(deftest checkout-submits
  (story/is :story.checkout/submits {:runner :headless}))
```

Shared behaviour prefixes are explicit fragments (`story/reg-fragment`),
reusable always-on expectations are checks (`story/reg-check`), and
`:extends` specialises a *parent's context* — never its behaviour: setup
and world context flow down, terminal assertions and script steps stay
local (017 §Composition). `story/explain :story.checkout/submits` shows the
resolved plan — source chain, merge decisions, final setup/script order,
runner requirements — so composition is never hidden global state.

- **Verb:** `story/is` (test gate) / `story/run` (raw result) /
  `story/explain` (the resolved plan) / `story/render-variant` (the
  workshop view from the *same* plan — controls and tests drive one plan,
  not two paths).
- **Assertion:** any `:rf.assert/*` atom, plus inheritable `:checks`.
- **Runner:** `:headless` by default; the Story UI renders via
  `render-variant`, not a browser-tier test run.
- **`:cannot-run`:** same as the inline plan — a richer-tier assertion
  refuses under `:headless` rather than faking the proof.

---

## Recipe 11 — `:cannot-run`: the honest refusal

`:cannot-run` is the third result status, alongside `:pass` and `:fail`.
It is what a runner returns when an assertion or step requires evidence it
**cannot supply** — a `:headless` runner asked to evaluate a DOM click, a
visual snapshot, or an axe-style a11y scan. The runner refuses; it never
fakes a proof.

```clojure
;; A visual snapshot under the default :headless runner — refused, not faked.
(story/run
  {:script     [[:dispatch [:checkout/open]]]
   :assertions [[:rf.assert/visual-snapshot "checkout-modal"]]}
  {:runner :headless})
;; => {:status :cannot-run
;;     :required-runner  #{:pixels}
;;     :available-runner #{:app-db :effects :schema :trace :pure-subs}
;;     :missing          #{:pixels}
;;     :reason :runner-lacks-capability
;;     :runner :headless
;;     :unit [:rf.assert/visual-snapshot "checkout-modal"]}
```

Capabilities are a **set of tokens** advertised by each concrete runner —
`:headless` provides `:app-db :effects :schema :trace :pure-subs`,
`:hiccup` adds `:hiccup-structure`, `:dom` adds `:dom`, `:browser` adds
`:pixels :a11y-engine`. Each assertion / step declares the tokens it needs;
a runner is *valid* iff its token set is a superset.

Two policies decide what happens to a token the chosen runner lacks:

- **fixed-runner (default):** the caller's `:runner` (or `:headless`) runs
  the whole plan; one unprovable assertion refuses *per-requirement* — a
  single `:visual-snapshot` does NOT drag a 95%-headless variant to
  `:browser`.
- **auto / escalate** (`{:runner :auto}` or `{:escalate true}`): pick the
  *cheapest* concrete runner whose tokens satisfy ALL requirements; if none
  can, the whole run is `:cannot-run` with `:reason :no-runner-satisfies`.

The aggregation rule is stated once: precedence is
`:error > :fail > :cannot-run > :pass`, so a real failure is never masked
by a refusal, and a variant whose *only* unmet expectation is a refusal is
itself `:cannot-run` — never a silent pass. To actually *run* a DOM /
visual assertion, select the richer runner (`{:runner :browser}`) and a
CI gate that provides it (017 §Runner model / §`:cannot-run`).

---

## The shared run-result

Every verb — `story/run`, `story/is`, an inline plan, a registered variant
— returns or reports the same unified run-result. The slots you read most:

| Slot | What it carries |
|---|---|
| `:status` | `:pass` \| `:fail` \| `:cannot-run` \| `:error` — the one verdict field |
| `:assertions` | per-assertion records (`:assertion` id, `:status`, `:passed?`, `:expected`, `:actual`) |
| `:checks` | check records grouping their assertions under the check id |
| `:app-db` | the final app-db value |
| `:schema-violations` / `:warnings` / `:effects` | projected from the one retained epoch tape |
| `:narrative` | the scrubbable two-level projection (script steps over epoch beats) |
| `:plan-hash` / `:run-hash` | canonical fingerprints (determinism, diff, the metamorphic relation) |

These are **projections from one epoch tape**, not a second capture path —
so Story UI, CI, `clojure.test`, agents, and the golden/diff tools cannot
disagree about what happened. `story/canonicalize` strips the volatile
fields so two runs of the same behaviour compare equal regardless of frame
ids, timings, and run-specific stamps.

## Where to go next

- [Guide 13 — Testing](../guide/13-testing.md) — the seam argued from the
  ground up: isolation, the substrate helpers, JVM vs CLJS, and the rule
  for when a view test isn't worth writing.
- [Story API — Play scripts](api/play-script.md) — the `:script` step
  grammar and the canonical assertion vocabulary in reference form.
- [Story API — Runtime](api/runtime.md) — `run` / `is` / `explain` /
  `render-variant`, the lifecycle, and the registry-query family.
- [`017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md)
  — the normative contract: plan compiler, runner-capability model, schema
  floor, evidence projection, and canonicalization.
