# Testing

You want to prove what a view shows and what a control will dispatch **without**
mounting a browser for every check. Handlers are data; structure is data — so the
everyday test is: known re-frame state → structural tree on the JVM → equality.

> **`rf/` drives and reads state. `re-frame.freehand.test` renders and projects.**

Work down the tiers and **stop at the first one that answers your question.**

## Tier 1 — headless structure (daily driver)

`t/render` runs the real view — interpreted or compiled — and returns the versioned
structural tree as plain data:

```clojure
(ns shop.ui-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.freehand.test :as t]
            [shop.ui :as app]))

(deftest add-button-carries-intent
  (let [tree   (t/render [app/add-button {:product-id 42}])
        button (t/find tree #(= :button (:tag %)))]
    (is (= "Add" (t/text button)))
    (is (= [:cart/add 42] (:on-click (t/attrs button))))))
```

Because handlers are event vectors, “what does this button do?” is an **equality
check** — no DOM, no click simulation, no flake.

The same declaration answers the same tree on the JVM and in ClojureScript, so a
`.cljc` structural test is a cross-host claim rather than a JVM claim wearing a
`.cljc` extension.

### Reading the tree

Six names, and that is the whole surface. `t/find` and `t/find-all` are
conveniences over ordinary Clojure traversal; there is deliberately no selector
language.

| Do | Don’t |
|---|---|
| `(t/find tree pred)` — first match in document order, or `nil` | invent a selector DSL |
| `(t/find-all tree pred)` — every match, in document order | |
| `(t/attrs node)` for attributes **and** event intents | `(:on-click node)` — a keyword lookup reads node **fields**, so that is a miss |
| `(t/text node)` for concatenated text | assert on host-only fields in tier 1 |
| `(tree-seq map? :children tree)` when you want the raw walk | |

`nil` threads through a missed match, so `(t/attrs (t/find tree p))` nil-puns
rather than throwing. Your predicate is handed **node maps only** — text content is
a host string, not a node, so a membership test like `#(contains? % :rf.ui/presence)`
reads the same on both hosts. Reach for the raw walk when you want the leaves
themselves.

```clojure
(t/find tree #(= :button (:tag %)))                ; by element tag
(t/find tree #(= :shop/product-card (:view-id %))) ; by view boundary
(count (t/find-all tree #(= :li (:tag %))))
```

`find` shadows `clojure.core/find`, so require the namespace under an alias rather
than `:refer`-ing it.

### A view that reads state renders inside `t/with-render`

`v/sub` is legal only during an active declared render, and `t/render` is a walk
rather than a host — so a view whose body subscribes is refused outside the
bracket with `:rf.error/view-read-outside-render`:

```clojure
(deftest the-badge-shows-the-basket-count
  (rf/dispatch-sync [:basket/add 42])
  (let [tree (t/with-render (t/render [app/basket-badge {}]))]
    (is (= "1" (t/text tree)))))
```

Inside the bracket the view renders **as written** — rewriting it to take a
one-shot read would be testing something other than the view. The render it opens
is never committed, so the reads inside resolve and probe but acquire nothing: no
ref-count, no watch, no disposal obligation survives. Render as often as you like.

There are no sub overrides. Drive the real state with `rf/dispatch-sync` and read
a fresh tree; to test a derivation, unit-test the subscription.

### Ground rules (tier 1)

1. Events and subs the view touches should be **`.cljc`** so they run on the JVM.
2. Tier 1 is the **structural subset**: host behaviors are markers/fallbacks;
   client-only shows fallback; presence is present/base + metadata — not a live
   animation clock.
3. One root form per `render` — wrap multi-view compositions in one `defview`.
4. These should run in a JVM watch loop in milliseconds.

## Recap

- Assert event vectors and visible structure with `t/render`.  
- Wrap any view that subscribes in `t/with-render`.  
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
    (let [tree (t/with-render (t/render [app/cart-badge {}]))]
      (is (= "1" (t/text (t/find tree #(= :span (:tag %)))))))))
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
    (let [before (t/with-render (t/render [app/cart-badge {}]))]
      (is (= "0" (t/text (t/find before #(= :span (:tag %)))))))
    (rf/dispatch-sync [:cart/add 42] {:frame f})
    (let [after (t/with-render (t/render [app/cart-badge {}]))]
      (is (= "1" (t/text (t/find after #(= :span (:tag %)))))))))
```

| Piece | Role |
|---|---|
| `rf/make-frame` | mint an isolated world |
| `:initial-events` | ordered history at creation (seed and/or full setup) |
| `rf/with-new-frame` | bind ambient frame; destroy on exit |
| `rf/dispatch-sync` + `{:frame f}` | further transitions on a live frame |
| fresh `t/render` | assert structure after each step you care about |

Frame scope is your **ordinary** re-frame bracket, not a `t` concept: there is no
frame option on `t/render` and no fixture.

| Prefer | When |
|---|---|
| `:initial-events` only | one final snapshot; less noise |
| `dispatch-sync` (after seed) | before/after; multi-step; “event while running” |

There is **no** structural `click!` or gesture DSL. Intent is data; behavior is
re-frame.

### Asserting a projected event

A site that carries `::v/value` dispatches something the tree cannot show you: the
tree holds `[:account/email-edited ::v/value]`, and the browser fills the marker.
`v/materialize-event` is the **one** materializer every path uses — production,
test, interpreted, compiled — so a test supplies a literal payload and asserts the
exact vector a DOM fire would produce:

```clojure
(deftest typing-carries-the-text
  (let [tree  (t/render [app/email-field {}])
        input (t/find tree #(= :input (:tag %)))]
    (is (= [:account/email-edited "mike@example.com"]
           (v/materialize-event (:on-input (t/attrs input))
                                {::v/value "mike@example.com"})))))
```

That is why general `rf/dispatch` never grew a payload arity, and why there is no
second splice path to keep in step.

### Scope

The structural walk subscribes to nothing and dispatches nothing, so the events
and subs a view under test touches must be `.cljc` — the standard re-frame
discipline. Nothing in a production bundle may `:require` `re-frame.freehand.test`;
that is a bundling rule, not a privacy one.

## Tier 3 — mounted browser

Host-bearing behaviour belongs to a real DOM, not to the structural tier:

- controlled input caret / selection / IME under contention
- focus and top-layer light-dismiss
- presence transitions on a real clock, with real CSS
- behavior connect / update / disconnect and command targeting
- third-party React protocols

`re-frame.freehand.test` has **no mounted tier**. Its six names are structural, and
there is deliberately no `with-root`, no `flush!` and no presence clock: a mounted
test is an ordinary browser test of your own, driven by your adapter and React's
own `act`, against a root you made with `v/mount` and tore down with `v/unmount!`.

Two projections on the main door are readable from such a test without reaching
into an implementation namespace — `v/active-connections` and `v/command-log`. See
[Debugging](debugging.md).

## Cross-mode parity

A structural test must not care which execution mode produced the tree for forms
in the **common subset**. Assert public meaning (tags, props, intents, keys,
presence metadata, host markers). When you promote a view with `{:compiled true}`,
**re-run the same tests unchanged** — that is test invariance.

For generative parity of library leaves, props schemas and a prop/branch corpus
support interpreted-vs-compiled equality (see
[Compilation — schemas](../advanced/compilation.md#props-schemas)).

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

## Troubleshooting

| Symptom | Fix |
|---|---|
| `(:on-click node)` is `nil` | `(t/attrs node)` — events live under attrs |
| `:rf.error/view-read-outside-render` | wrap in `t/with-render` (or the structural `render` path that opens a discardable render) |
| `find` conflicts with `clojure.core/find` | require the test ns under alias `t` |
| Tests slow / brittle | assert dataflow outside views; views for structure and intent |
| Behavior did nothing on the JVM | structural tier is inert markers — use a mounted browser test |
