# Testing with ui.test

Views here are pure functions whose interaction surface is data — so most UI
testing needs no DOM, no browser, and no flake. `re-frame.ui.test` has two tiers
that share nothing; work down the list and stop at the first tier that answers
your question.

**Tier 1 — headless — is the daily driver.** Tier 2 is unchanged re-frame2
dataflow testing. Tier 3 is for when the DOM itself is under test. Exact
signatures for everything below live in the
[`re-frame.ui.test` API reference](../../api/re-frame.ui.test.md).

## Test namespace setup

`rf/make-frame` needs an installed substrate adapter. Install one once through the
reset fixture, and keep the ambient frame clear because each test owns its own
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

`plain-atom` is the headless JVM adapter for these tests, not a second view
programming model. In a mounted CLJS test namespace, use the same fixture with
`ui/adapter` and `:async? true`.

## Tier 1 — headless view tests

`ui.test/render` runs the real view against a real frame **on the JVM** — real
subscriptions, real registrations, no React — and returns the versioned structural
tree, which is plain data:

```clojure
(deftest add-button-carries-intent
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (let [tree (ui.test/render [app/product-card {:product (product 42)}]
                               {:frame frame})]
      (is (= [:cart/add 42] (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
      (is (= "Add to cart"  (-> tree (ui.test/find :button) ui.test/text))))))
```

Because handlers are event vectors, "what does this button do" is an equality
check — no click simulation, no event mocking.

**Selectors are a small closed grammar:**

```clojure
(ui.test/find tree :button)                 ; unqualified keyword — element tag
(ui.test/find tree :shop/product-card)      ; qualified keyword — a view id (or the Var)
(ui.test/find tree {:data-testid "save"})   ; attr map — every entry matches, by value
(ui.test/find-all tree :li)                 ; all matches, in document order
```

Attr-map values compare by `rf=`, and handler slots hold event vectors as data —
so `{:on-click [:cart/add 42]}` finds a button by its *intent*. A predicate fn is
the escape for anything the data forms cannot say. `find` returns the node or
`nil`, and a found node is itself a valid tree, so finds compose.

**Reads go through projections.** Use `(ui.test/attrs node)` and
`(ui.test/text node)`. Never keyword-look-up an attribute on a node — `(:on-click
node)` reads a node *field* that is not there and silently misses.

**Drive state with real events:**

```clojure
(ui.test/dispatch! frame [:cart/add 42])
```

`dispatch!` takes the frame (value or id) and the event: a real dispatch plus a
drain to fixed point — then re-render and assert. Loading and error states are
app-db values you install or events you dispatch. When you need to pin a
presentation state directly, stub a sub:

```clojure
(ui.test/render [app/cart-badge] {:frame frame
                                  :sub-overrides {[:cart/locked?] true}})
```

**One constructor, one grammar.** There is no test-only way to make a frame:
`rf/make-frame` with `:initial-events`, exactly like production. Seed with
`[:rf/set-db {…}]` — or better, run your app's own init events so fixtures cannot
drift from a state the real app can reach. `rf/with-new-frame` destroys the
caller-owned frame after the body returns or throws. `render` also accepts a
**literal root form** (the same grammar `ui/mount` takes), and a test root mounts
exactly one view — wrap multi-view compositions in one `defview`.

**Two ground rules:**

1. Events and subs your view touches must be `.cljc` — they run on the JVM here.
2. Tier 1 renders the *structural subset*: `local` shows its initial value
   (calling its setter is a typed error pointing at Tier 3), effects do not run,
   refs are absent. State transitions and host behaviour are Tier-3 subjects by
   design.

These run in your JVM watch loop in milliseconds and never flake on timing.

## Tier 2 — dataflow tests

Handlers, subs, machines, fx: test them as the pure functions they are. The view
tier assumes this tier exists — do not test business logic through views. See the
[core testing guide](../testing/index.md).

## Tier 3 — mounted tests, when the DOM is the point

For focus, IME, foreign widgets — things only a real mount exercises — use
`with-root`, `query`, and `flush!` in a browser/jsdom test namespace.

```clojure
(deftest count-updates-in-the-dom
  (async done
    (-> (ui.test/with-root [root [ui/frame-root {:id :app
                                                 :initial-events [[:app/init]]}
                                  [app/counter]]]
          (-> (ui.test/flush! #(ui.test/dispatch! :app [:count/inc]))
              (.then (fn []
                       (is (= "1" (.-textContent (ui.test/query root ".count"))))))))
        (.then (fn [_] (done))
               (fn [e] (is false (str e)) (done))))))
```

- `with-root` owns one real React mount with total teardown on every exit, and
  returns a Promise — **await it** before asserting or starting another mounted
  operation.
- `query` answers a **native CSS selector** against the mounted DOM — the Tier-3
  counterpart of `find`, sharing nothing with the Tier-1 grammar.
- When a test *writes*, put the write inside `flush!`'s thunk: it runs inside
  React `act`, and the returned Promise settles when framework and React reach a
  fixed point. There is no second flush idiom, no `setTimeout` settle, and no
  "wait a tick" folklore.
- Native DOM mechanics that are already host-owned — focus, selection, a foreign
  component's raw callback — use ordinary platform APIs. There is no library
  gesture language.

Presence transitions advance with `ui.test/flush-presence!`, the
[presence](presence.md) twin of `flush!` — a fake clock, never a wall-clock sleep.
The zero-arity form advances to quiescence, firing every pending exit;
`(flush-presence! ms)` advances the logical clock by `ms` and fires only the exits
that come due. On the JVM both arities are a no-op (the structural render has no
lifecycle), so a `.cljc` test body calls them on either host.

## What you can trust without testing it yourself

The substrate's own CI pins ownership under concurrent React, browser/JVM tree
parity, dev/prod behavioural equivalence, bundle absence, and performance budgets.
Your tests cover *your* app; they get to assume the substrate. (For tooling and
agents, the dev-only `re-frame.ui.tool` namespace exposes the same evidence Xray
reads — view manifests, mounted views, render explanations.)

## When it goes wrong

| If you write | What you see | The fix |
|---|---|---|
| A CSS string to `find`, or a structural tree to `query` | Typed error `:rf.error/ui-test-tier-mismatch` naming the other tier | Tier 1 speaks data selectors; Tier 3 speaks CSS |
| A second mounted operation before the first settles | `:rf.error/ui-test-overlapping-act` | Await every `with-root` / `flush!` Promise |
| `flush!` inside an open event drain | `:rf.error/flush-in-open-epoch` | Flush after the dispatch, not from inside a handler |
| An unsupported selector value | `:rf.error/ui-test-bad-selector` naming the closed grammar | Keyword, view id/Var, attr map, or predicate fn |

## Rules of thumb

- If you are simulating a click to check a dispatch, assert the vector on the tree
  instead — Tier 1. Simulate interactions only when the DOM mechanics are under
  test.
- If a view is hard to set up, it is reading too much — narrow its subs. That is a
  design smell surfacing in the test, which is the point of tests.
- When not: business logic belongs in Tier-2 dataflow tests, and on the retained
  adapters — the default, non-experimental choice — view testing goes through the
  hiccup helpers in the [core testing guide](../testing/views.md) instead of
  `ui.test`.
