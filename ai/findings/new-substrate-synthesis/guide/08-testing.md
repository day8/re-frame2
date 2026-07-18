# 08 — Testing

Views here are pure functions whose interaction surface is data — so most UI testing
needs no DOM, no browser, and no flake. Work down this list; stop at the first tier
that answers your question.

**Tier 1 is the default.** Tier 2 is unchanged re-frame2 dataflow testing. Tier 3 is
for when the DOM itself is under test.

## Test namespace setup

`rf/make-frame` needs an installed substrate adapter. Install one once through the
reset fixture, and keep the ambient frame clear because each test below owns its own
throwaway frame:

```clojure
(ns shop.ui-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.test :as ui.test]
            [shop.ui :as app]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :ambient-frame nil}))
```

This is the JVM adapter for headless tests, not a second view programming model. In a
mounted CLJS test namespace, use the same fixture with `ui/adapter` and `:async? true`.

## Tier 1 — headless view tests (daily driver)

`ui.test/render` runs the real view against a real frame on the JVM — real
subscriptions, real registrations, no React:

Guide 08's enrolled fixture covers the Tier-1 deftest, intent projection,
dispatch-to-sub loop, seeded-state render, and sub-override door. The selector and
literal-root fences below, plus the Tier-2 and Tier-3 examples, remain prospective
pipeline enrolment.

```clojure
(deftest add-button-carries-intent
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{} :catalog fixture-catalog}]]})]
    (let [tree (ui.test/render [product-card {:product (product 42)}] {:frame frame})]
      (is (= [:cart/add 42] (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
      (is (= "Add to cart"  (-> tree (ui.test/find :button) ui.test/text))))))
```

### Assert structure and intent

The rendered tree is data. Because handlers are event vectors, "what does this button
do" is an equality check — no click simulation, no event mocking.

### Selectors are a small closed grammar

```clojure
(ui.test/find tree :button)                 ; unqualified keyword — element tag
(ui.test/find tree :shop/product-card)      ; qualified keyword — a view id (or pass the Var)
(ui.test/find tree {:data-testid "save"})   ; attr map — every entry matches, by value
(ui.test/find-all tree :li)                 ; all matches, in document order
```

View-id selectors match the view's boundary marker, so fragment-rooted views are
findable. Attr-map values compare by `rf=`, and handler slots hold event vectors as
data — so `{:on-click [:cart/add 42]}` finds a button by its *intent*. A predicate fn
is the escape for anything the data forms cannot say. `find` returns the node or
`nil`.

### Reads go through projections

Use `(ui.test/attrs node)` and `(ui.test/text node)`. Never keyword-look-up an
attribute on a node: `(:on-click node)` reads a node field that is not there and
silently misses.

### Drive state with real events

```clojure
(ui.test/dispatch! frame [:cart/add 42])
```

Real dispatch plus a drain to fixed point — then re-render and assert. Loading and
error states are app-db values you install or events you dispatch. Stub a sub when
you need to:

```clojure
(ui.test/render [view] {:frame frame :sub-overrides {[:cart/locked?] true}})
```

### One constructor, one grammar

There is no test-only way to make a frame: `rf/make-frame` with `:initial-events`,
exactly like production. Seed with `[:rf/set-db {…}]`, or better, run your app's own
init events so fixtures cannot drift from what the app boots. `rf/with-new-frame`
destroys the caller-owned frame after the body returns or throws.

`render` also takes a **literal root form** — same grammar as `mount`, tightened:
a test root mounts exactly *one* view. Need multi-view composition? Wrap it in one
`defview` and render that.

### Two ground rules

1. Events/subs your view touches must be `.cljc` (they run on the JVM here).
2. Tier 1 renders the *structural subset*: `local` shows its initial value (calling
   its setter is a typed error pointing at Tier 3); effects do not run; refs are
   absent. State transitions and host behaviour are Tier-3 subjects by design.

These run in your JVM watch loop in milliseconds and never flake on timing.

## Tier 2 — dataflow tests

Handlers, subs (`compute-sub`), machines, fx: test them as the pure functions they
are. The view tier assumes this tier exists — do not test business logic through
views. See the [core testing guide](../../../../docs/core/testing/index.md).

## Tier 3 — mounted tests, when the DOM is the point

For focus, IME, foreign widgets — things only a real mount exercises — use
`with-root`, `query`, and `flush!`.

`with-root` owns its React root and DOM container. A frame you pass through
`frame-provider` is separate and remains yours. For Promise-backed tests, settle the
body before destroying that frame. Pass the mounted operation as a thunk so a
synchronous `with-root` preflight throw is owned too. Every exit destroys exactly
once; an original throw/rejection remains primary, and a secondary cleanup error
rides an object primary as `rfUiTestCleanupError`, matching `with-root` itself:

```clojure
(defn- attach-frame-cleanup-error!
  [primary cleanup-error]
  (let [attached?
        (try
          (js/Object.defineProperty
            primary "rfUiTestCleanupError"
            #js {:value cleanup-error :configurable true})
          true
          (catch :default _ false))]
    (when (and (not attached?) (exists? js/console))
      (.warn js/console
             "frame cleanup could not ride the primitive primary rejection"
             cleanup-error)))
  primary)

(defn- destroy-frame-after!
  [frame thunk]
  (letfn [(reject-after-current! [error]
            (-> (js/Promise.resolve nil)
                (.then (fn [] (throw error)))))
          (finish! [settlement value]
            (try
              (rf/destroy-frame! frame)
              (if (= :rejected settlement)
                (reject-after-current! value)
                (js/Promise.resolve value))
              (catch :default cleanup-error
                (if (= :rejected settlement)
                  (reject-after-current!
                    (attach-frame-cleanup-error! value cleanup-error))
                  (reject-after-current! cleanup-error)))))]
    (try
      (-> (js/Promise.resolve (thunk))
          (.then (fn [value] (finish! :fulfilled value))
                 (fn [error] (finish! :rejected error))))
      (catch :default error
        (finish! :rejected error)))))
```

Read-only:

```clojure
;; guide:target dom
(deftest search-box-really-mounts
  (async done
    (let [frame (rf/make-frame {:initial-events [[:rf/set-db {:query ""}]]})]
      (-> (destroy-frame-after!
            frame
            #(ui.test/with-root [root [ui/frame-provider {:frame frame} [search-box]]]
               (is (some? (ui.test/query root "input")))))
          (.then (fn [_] (done))
                 (fn [e] (is false (str e)) (done)))))))
```

When the test *writes*, put the write inside `flush!`'s act boundary and await every
Promise:

```clojure
;; guide:target dom
(deftest search-commits-the-latest-query
  (async done
    (let [frame (rf/make-frame {:initial-events [[:rf/set-db {:query ""}]]})]
      (-> (destroy-frame-after!
            frame
            #(ui.test/with-root
               [root [ui/frame-provider {:frame frame} [search-box]]]
               (-> (ui.test/flush!
                     #(ui.test/dispatch! frame [:search/set-query "hats"]))
                   (.then (fn []
                            (is (= "hats"
                                   (.-value (ui.test/query root "input"))))
                            :asserted)))))
          (.then (fn [body-value]
                   (is (= :asserted body-value))
                   (done))
                 (fn [error]
                   (is false (str "mounted test rejected: " error))
                   (done)))))))
```

On CLJS, both `with-root` and `flush!` are Promise boundaries. Prefer the thunk form
of `flush!` for writes. There is no second flush idiom, no `setTimeout` settle, and
no "wait for the next tick" folklore. Overlapping acts fail loudly with
`:rf.error/ui-test-overlapping-act`.

Native DOM mechanics already host-owned — focus, selection, a foreign component's raw
callback — use ordinary platform APIs. There is no library gesture language.

Presence transitions advance with `(ui.test/flush-presence!)`, the presence twin of
`flush!` — a fake clock, never a wall-clock sleep. The zero-arity form advances to
quiescence, firing every pending exit; `(flush-presence! ms)` advances the logical
clock by `ms` and fires only the exits that come due. On the JVM structural host
there is no lifecycle, so both arities are a no-op — a `.cljc` test body calls them
on either host.

## Story *(rides the migration wave — S6)*

Story variants assert on rendered data and app-db, stub subscriptions through
observation targets, enumerate a view's interaction surface as data, and reserve DOM
plays for genuinely DOM-shaped checks. Scenes mount by **view id** from the registry.

## What you can trust without testing it yourself

The library's CI pins ownership under concurrent React, browser/JVM-tree conversion
parity, dev/prod behavioural equivalence, bundle absence rosters, and performance
gates. Your tests cover *your* app; they get to assume the substrate.

## Rules of thumb

- Await every mounted operation; prefer `(flush! #(write))` to a write followed by a
  separate zero-arity flush.
- If you are simulating a click to check a dispatch, assert the vector on the tree
  instead (Tier 1) — simulate clicks only when the *DOM mechanics* are under test.
- If a view is hard to set up, it is reading too much — narrow its subs (a design
  smell surfacing in the test, which is the point of tests).
