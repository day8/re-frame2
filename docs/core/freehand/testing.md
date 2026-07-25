# Testing

You want to prove what a view shows and what a control will dispatch **without**
mounting a browser for every check. Handlers are data; structure is data — so the
everyday test is: known re-frame state → structural tree on the JVM → equality.

> **`rf/` drives and reads state. Freehand test helpers render, project, and settle.**

Work down the tiers and **stop at the first one that answers your question.**

!!! note "API names"

    `re-frame.freehand.test` (alias `t`) is the design target. Exact fixture
    helpers land with implementation; the **contracts** below are normative for
    the guide.

## Tier 1 — headless structure (daily driver)

`t/render` runs the real view (interpreted or compiled) on the JVM and returns the
versioned structural tree — plain data:

```clojure
(ns shop.ui-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.freehand.test :as t]
            [shop.ui :as app]))

(deftest add-button-carries-intent
  (let [tree (t/render [app/add-button {:product-id 42}])]
    (is (= [:cart/add 42]
           (-> (some #(when (= :button (:tag %)) %)
                     (tree-seq map? :children tree))
               t/attrs
               :on-click)))))
```

Because handlers are event vectors, “what does this button do?” is an **equality
check** — no click simulation.

### Reading the tree

| Do | Don’t |
|---|---|
| `(tree-seq map? :children tree)` | invent a selector DSL |
| `(t/attrs node)` for props **and** event intents | `(:on-click node)` — events live under the structural schema; bare keyword miss is `nil` |
| `(t/text node)` for concatenated text | assert on host-only fields in tier 1 |

```clojure
;; first button
(some #(when (= :button (:tag %)) %) (tree-seq map? :children tree))

;; view boundary by id (shape illustrative)
(some #(when (= :shop/product-card (:view-id %)) %)
      (tree-seq map? :children tree))
```

### Sub overrides

When offered, `:sub-overrides` (query → value) pins **render reads only**:

```clojure
(t/render [app/badge {}]
          {:sub-overrides {[:cart/count] 3}})
```

They do **not** mutate app-db and do **not** make a `compute-sub` derivation test
pass. To test derivation, use a real frame (tier 2) or pure sub unit tests.

### Ground rules (tier 1)

1. Events and subs the view touches should be **`.cljc`** so they run on the JVM.
2. Tier 1 is the **structural subset**: host behaviors are markers/fallbacks;
   client-only shows fallback; presence is present/base + metadata — not a live
   animation clock.
3. One root form per `render` — wrap multi-view compositions in one `defview`.
4. These should run in a JVM watch loop in milliseconds.

## Day-one checklist

- Assert event vectors and visible structure with `t/render` (or equivalent).  
- Prefer `(t/attrs node)` for intents — not bare `(:on-click node)`.  
- Keep events/subs `.cljc` so the JVM path works.  
- Do **not** invent a structural `click!` DSL.

If tier 1 answers the question, stop. Most Freehand view tests should live here.

## Tier 2 — real frame, real events

Step up for registered-sub derivation, event transitions, or isolation.

### Final state only — put the history in `:initial-events`

When you only need a snapshot **after** a known sequence, fold setup into frame
creation. No separate `dispatch-sync`:

```clojure
(deftest cart-badge-shows-count-after-add
  (rf/with-new-frame
    [_ (rf/make-frame
        {:initial-events [[:rf/set-db {:cart #{}}]
                          [:cart/add 42]]})]
    (let [tree (t/render [app/cart-badge {}])]
      (is (= "1" (t/text (some #(when (= :span (:tag %)) %)
                               (tree-seq map? :children tree))))))))
```

`:initial-events` runs in order when the frame is minted (same idea as production
preflight seeding). Prefer this for small “given this history, the tree looks
like …” tests.

### Transition — seed, then `dispatch-sync`

When you need **before and after**, or to mimic “user did X while the app is
running,” keep a live frame and dispatch:

```clojure
(deftest adding-to-the-cart-updates-the-badge
  (rf/with-new-frame
    [f (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (let [before (t/render [app/cart-badge {}])]
      (is (= "0" (t/text (some #(when (= :span (:tag %)) %)
                               (tree-seq map? :children before)))))
    (rf/dispatch-sync [:cart/add 42] {:frame f})
    (let [after (t/render [app/cart-badge {}])]
      (is (= "1" (t/text (some #(when (= :span (:tag %)) %)
                              (tree-seq map? :children after))))))))
```

| Piece | Role |
|---|---|
| `rf/make-frame` | mint an isolated world |
| `:initial-events` | ordered history at creation (seed and/or full setup) |
| `rf/with-new-frame` | bind ambient frame; destroy on exit |
| `rf/dispatch-sync` + `{:frame f}` | further transitions on a live frame |
| fresh `t/render` | assert structure after each step you care about |

| Prefer | When |
|---|---|
| `:initial-events` only | one final snapshot; less noise |
| `dispatch-sync` (after seed) | before/after; multi-step; “event while running” |

There is **no** structural `click!` or gesture DSL. Intent is data; behavior is
re-frame.

### Projection materialization in tests

Production materializes `::v/value` / `::v/checked` / `::v/key` at the Freehand
adapter. Tests must **reuse that materializer** (helpers such as
`t/materialize` / `t/dispatch` when implemented) so value-carrying intents never
grow a second splice path. “Dispatch the vector you read from the tree” should
mean the same bytes after materialization as a DOM fire would.

### Adapter fixture

`rf/make-frame` needs an installed adapter. Install once per test namespace via the
project’s reset-runtime fixture (exact helper names land with implementation),
e.g. a headless JVM adapter for tier 1–2 and the Freehand browser adapter for
mounted tests. Do not invent a second view programming model for tests.

## Tier 3 — mounted browser

Use the real DOM for:

- controlled input caret / selection / IME under contention
- focus, top-layer light-dismiss
- presence transitions with real CSS
- behavior connect / update / disconnect and command targeting
- third-party React protocols

### Settling

| Primitive | Role |
|---|---|
| host `act` (or equivalent) | around user/DOM writes |
| production post-drain checkpoint | dirty Freehand cells publish inside that scope |
| **one** shared `settle!` / `flush!` if needed | fixed point for framework + React — **not** a family of click/query/dispatch mirrors |
| `t/flush-presence!` | fake clock for presence retention — never `Thread/sleep` |

```clojure
;; shape illustrative — await promises; no overlapping mounts
(t/with-root [container [app/counter {}]]
  (-> (t/flush! #(rf/dispatch-sync [:count/inc] {:frame :app}))
      (.then (fn []
               (is (= "1" (.-textContent
                           (.querySelector container ".count"))))))))
```

Rules of thumb:

- Await every mount/flush promise before the next mounted operation.
- Put writes **inside** the flush thunk when the harness requires it.
- Query with native DOM APIs on the bound container.
- Presence: `(t/flush-presence!)` to quiescence or `(t/flush-presence! ms)` for a
  logical advance.

```clojure
(deftest toast-exits-after-timeout
  ;; drop toast from app-db, then:
  (t/flush-presence! 300)
  ;; assert node gone / unmounting finished
  )
```

## Cross-mode parity

A structural test must not care which execution mode produced the tree for forms
in the **common subset**. Assert public meaning (tags, props, intents, keys,
presence metadata, host markers). When you promote a view with `{:compiled true}`,
**re-run the same tests unchanged** — that is test invariance.

For generative parity of library leaves, props schemas and a prop/branch corpus
support interpreted-vs-compiled equality (see
[Compilation — schemas](compilation.md#props-schemas)).

## What not to test in the view layer

| Concern | Where |
|---|---|
| Event handler pure logic | re-frame unit tests, no renderer |
| Subscription derivation | re-frame unit / `compute-sub` |
| Resource lifetime | routes, machines, owners |
| Pixel animation | browser / visual harness |
| “Click to prove dispatch” | assert the vector on the tree (tier 1) |

If a view is hard to set up, it is often **reading too much** — narrow its subs.
That smell surfacing in tests is a feature.

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| `(:on-click node)` | silent `nil` | `(t/attrs node)` |
| Second mount before first settles | overlapping act error | await promises |
| Business logic only through views | slow, brittle tests | dataflow unit tests |
| Wall-clock sleep for presence | flake | `flush-presence!` |
| Expecting tier 1 to run behaviors | markers only | mounted tier |
