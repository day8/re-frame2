# Testing with ui.test

Views here are pure functions whose interaction surface is data — so most UI
testing needs no DOM, no browser, and no flake. `re-frame.ui.test` is a small
surface: **`render`**, **`attrs`**, **`text`** on the JVM structural host, and
**`with-root`**, **`flush!`**, **`flush-presence!`** on the CLJS mounted host.

The one-sentence story: **rf/ drives and reads state, test-support isolates and
waits, ui.test renders, projects, and settles.** So `ui.test` owns rendering,
node projection, and mounted settling — everything else (frames, dispatch,
fixtures) is the ordinary `rf/` and `test-support` surface you already know.

Work down the tiers and stop at the first one that answers your question. Exact
signatures live in the
[`re-frame.ui.test` API reference](../../api/re-frame.ui.test.md).

## Tier 1 — headless, frameless: the daily driver

A view that reads only its props needs **no frame and no fixture** — the shortest
correct test. `ui.test/render` runs the real compiled view on the JVM and returns
the versioned structural tree, which is plain data:

```clojure
(ns shop.ui-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.test :as ui.test]
            [shop.ui :as app]))

(deftest add-button-carries-intent
  (let [tree (ui.test/render [app/add-button {:product-id 42}]
                             {:sub-overrides {[:cart/locked?] false}})]
    (is (= [:cart/add 42]
           (-> (some #(when (= :button (:tag %)) %)
                     (tree-seq map? :children tree))
               ui.test/attrs
               :on-click)))))
```

Because handlers are event vectors, "what does this button do" is an equality
check — no click simulation, no event mocking. If the view reads no subs at all,
omit the options map entirely: `(ui.test/render [app/add-button {:product-id 42}])`.

**Traversal is ordinary Clojure.** A structural tree is deliberately plain data,
so `ui.test` ships no selector helper — you walk it with `tree-seq`:

```clojure
(tree-seq map? :children tree)          ; every node, document order (strings skipped)

;; first element by tag:
(some #(when (= :button (:tag %)) %) (tree-seq map? :children tree))
;; first node at a view boundary:
(some #(when (= :shop/product-card (:view-id %)) %) (tree-seq map? :children tree))
;; every match:
(filterv #(= :li (:tag %)) (tree-seq map? :children tree))
```

**Reads go through projections.** Use `(ui.test/attrs node)` and
`(ui.test/text node)`. Never keyword-look-up an attribute on a node directly —
`(:on-click node)` reads a node *field* that is not there and silently misses,
because events live under `:events`. `attrs` is the one attribute read that merges
an element's `:attrs` and `:events` (so `{:on-click [:cart/add 42]}` is how a
button's *intent* reads), returns a view boundary's `:props`, and is `{}` on a
fragment/html node. `text` concatenates a node's text descendants in document
order.

**`:sub-overrides` — one door, render-reads only.** When a view reads a sub you
want to pin, `:sub-overrides` (a map of query vector → value) intercepts the read
for this render. There is one conceptual owner — the dynamic var
`re-frame.ui.reactive/*sub-overrides*` — with `ui.test/render` as its JVM carriage
and Story's React context Provider as the mounted carriage; both converge on one
resolver and one validator. **Fidelity warning:** overrides affect render *reads*
only — they do not mutate app-db, and they do not make a `compute-sub` assertion
pass. To test derivation itself, use a real frame (below).

## Tier 1 — the frame tier: real subs, events, isolation

Step up to a frame when a test needs a real registered-sub derivation, an event
transition, or frame isolation. Frame scope is the programmer's ordinary bracket —
there is no frame option on `render`. Mint the frame with `rf/make-frame` (exactly
like production; seed with `[:rf/set-db {…}]`, or run your app's own init events so
fixtures cannot drift from a reachable state), bind it with `rf/with-new-frame`
(which destroys it on exit, so nothing leaks into `rf/frame-ids`), drive state with
`rf/dispatch-sync`, and assert on a **fresh** `render`:

```clojure
(deftest adding-to-the-cart-updates-the-badge
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (rf/dispatch-sync [:cart/add 42] {:frame f})
    (let [tree (ui.test/render [app/cart-badge {}])]
      (is (= "1" (ui.test/text (some #(when (= :span (:tag %)) %)
                                     (tree-seq map? :children tree))))))))
```

`with-new-frame` binds the fresh frame as the ambient scope, so the `render` reads
it without any option; `{:frame f}` on `dispatch-sync` targets that same frame (the
value or its id — both are accepted). To pin a frame you already hold without
destroying it, use `rf/with-frame`. `rf/make-frame` needs an installed adapter —
install one once through the reset fixture:

```clojure
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil}))
```

`plain-atom` is the headless JVM adapter for these tests, not a second view
programming model.

**Two ground rules:**

1. Events and subs your view touches must be `.cljc` — they run on the JVM here.
2. Tier 1 renders the *structural subset*: `local` shows its initial value
   (calling its setter is a typed error pointing at the mounted host), effects do
   not run, refs are absent. A `render` mounts exactly one view — wrap multi-view
   compositions in one `defview`.

These run in your JVM watch loop in milliseconds and never flake on timing.

## Dataflow tests — the tier the view tier assumes

Handlers, subs, machines, fx: test them as the pure functions they are. Do not
test business logic through views. See the
[core testing guide](../testing/index.md). (`poll-until` waits for an observable on
a live scheduler; `flush!` deterministically settles a mounted tree — they are not
the same tool.)

## Mounted tests — when the DOM is the point

For focus, IME, foreign widgets — things only a real mount exercises — use
`with-root`, `flush!`, and native DOM queries in a browser/jsdom namespace (the
same reset fixture with `ui/adapter` and `:async? true`).

```clojure
(deftest count-updates-in-the-dom
  (async done
    (-> (ui.test/with-root [container [ui/frame-root {:id :app
                                                      :initial-events [[:app/init]]}
                                       [app/counter]]]
          (-> (ui.test/flush! #(rf/dispatch-sync [:count/inc] {:frame :app}))
              (.then (fn []
                       (is (= "1" (.-textContent
                                   (.querySelector container ".count"))))))))
        (.then (fn [_] (done))
               (fn [e] (is false (str e)) (done))))))
```

- `with-root` owns one real React mount with total teardown on every exit, binds
  the connected DOM **container**, and returns a Promise — **await it** before
  asserting or starting another mounted operation.
- Query the bound container with native `.querySelector` / `.querySelectorAll`, and
  read ordinary DOM properties and events on the elements it returns.
- When a test *writes*, put the write inside `flush!`'s thunk —
  `(ui.test/flush! #(rf/dispatch-sync event {:frame f}))`: it runs inside React
  `act`, and the returned Promise settles when framework and React reach a fixed
  point. `flush!` is the only test flush — no second idiom, no `setTimeout` settle,
  no "wait a tick" folklore.
- Native DOM mechanics that are already host-owned — focus, selection, a foreign
  component's raw callback — use ordinary platform APIs. There is no gesture DSL.

Presence transitions advance with `ui.test/flush-presence!`, the
[presence](presence.md) twin of `flush!` — a fake clock, never a wall-clock sleep.
The zero-arity form advances to quiescence, firing every pending exit;
`(flush-presence! ms)` advances the logical clock by `ms` and fires only the exits
that come due. `flush!` / `flush-presence!` are the mounted host only — a JVM
Tier-1 checkpoint is a fresh `render` after a synchronous `rf/dispatch-sync`.

## What you can trust without testing it yourself

The substrate's own CI pins ownership under concurrent React, browser/JVM tree
parity, dev/prod behavioural equivalence, bundle absence, and performance budgets.
Your tests cover *your* app; they get to assume the substrate. (For tooling and
agents, the dev-only `re-frame.ui.tool` namespace exposes the same evidence Xray
reads — view manifests, mounted views, render explanations.)

## When it goes wrong

| If you write | What you see | The fix |
|---|---|---|
| A keyword lookup for an attribute — `(:on-click node)` | `nil` (a silent field miss) | Read through `(ui.test/attrs node)` — events live under `:events` |
| A second mounted operation before the first settles | `:rf.error/ui-test-overlapping-act` | Await every `with-root` / `flush!` Promise |
| `flush!` inside an open event drain | `:rf.error/flush-in-open-epoch` | Flush after the dispatch, not from inside a handler |
| An unknown `render` option (e.g. `:frame`, `:props`) | `:rf.error/ui-test-bad-opts` | `:sub-overrides` is the only option; props ride in the form, frame scope is `rf/with-new-frame` / `rf/with-frame` |

## Rules of thumb

- If you are simulating a click to check a dispatch, assert the vector on the tree
  instead — the frameless tier. Simulate interactions only when the DOM mechanics
  are under test.
- If a view is hard to set up, it is reading too much — narrow its subs. That is a
  design smell surfacing in the test, which is the point of tests.
- Business logic belongs in dataflow tests; and on the retained adapters — the
  default, non-experimental choice — view testing goes through the hiccup helpers
  in the [core testing guide](../testing/views.md) instead of `ui.test`.
