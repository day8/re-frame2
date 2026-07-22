# Test a view

You've tested the [handler](event-handlers.md) and the [pipeline run](pipeline-runs.md). Now the view: the right structure comes out, the right text shows for a given state, and the right handler is wired to the right button.

No browser for this one either. A [view](../glossary.md#view) is a pure function that returns [hiccup](../glossary.md#hiccup) — plain data — so a view test is a function call and a tree walk, and it runs on the JVM in milliseconds.

> **A view test calls the function and walks the returned data — no DOM, no JSDOM, no `act()`.**

One honest framing before the recipe: **most "view bugs" are data bugs.** A view holds no state and decides nothing, so when the screen is wrong, the culprit is nearly always the [subscription](subscriptions.md) or [handler](event-handlers.md) upstream — pure functions with cheaper tests ([Views](../views.md#when-things-go-wrong) makes the case). A view test is for what a view genuinely *owns*: its structure, its text, and its wiring. That's the whole list.

The toolkit is `re-frame.test-helpers` — pure walks over hiccup, [catalogued in the API reference](../../api/re-frame.test-helpers.md) — alongside the `re-frame.test-support` fixtures you already use:

```clojure
(ns my-app.views-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.test-helpers :as th]
            [my-app.counter :as counter]))   ;; the app namespace under test

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter  my-app/test-adapter    ;; your substrate adapter
                                  :init-fn  counter/install!}))     ;; reg-event / reg-sub / views
```

`make-reset-runtime-fixture` seats the ambient `:rf/default` frame when given an `:adapter`, runs your registrations once via `:init-fn`, and snapshots/restores the registrar around every test. A purely presentational test (§1) needs neither key — an adapter-less `(ts/make-reset-runtime-fixture {})` suffices — but the connected tests below rely on the seated frame.

## 1. Call it, walk it

Give the nodes you'll assert on a stable address at the view site — `th/testid` builds an attrs map carrying `:data-testid` — then call the view like any function and read the tree it returns:

```clojure
;; The view under test — a plain function of its arguments.
(defn price-row [{:keys [label price]}]
  [:tr (th/testid "price-row")
   [:td label]
   [:td (th/testid "price-cell") "$" price]])

(deftest price-row-shows-the-price
  (let [tree (price-row {:label "Widget" :price "12.50"})]
    (is (= "$12.50" (th/text-content (th/find-by-testid tree "price-cell"))))))
```

`find-by-testid` returns the first node carrying that `:data-testid`; `text-content` collects the string leaves under it. Their generic siblings — `find-by-attr`, `find-all-by-testid`, `find-by-testid-prefix`, `attrs`, `children` — cover lists and custom attributes ("every node whose testid starts with `row-`").

What about a view that renders *another* view? It comes back as a *component reference* — `[cart-line item]`, a vector whose head is a function, not a tag. The finders and `text-content` expand those references as they walk, so asserting *through* a child view just works. And when you want the fully-expanded tree as a value — to `let`-bind once for several assertions, or to walk by hand — `th/expand-tree` is the same expansion as a standalone step.

??? info "Coming from React Testing Library?"

    `find-by-testid` / `text-content` are `getByTestId` / `textContent` — except the "render" was a plain function call, so there's no JSDOM to stand up and nothing to clean up. The query API is deliberately smaller: you're walking a value, not a live document.

## 2. Views that subscribe: the reset fixture

A presentational view takes data as arguments. A *connected* view subscribes and dispatches, so it needs a [frame](../glossary.md#frame) in scope. There's no bespoke view-fixture macro — the reset fixture at the top already gives you one: `:adapter` seats the ambient `:rf/default` frame and `:init-fn` runs your registrations. The view test is then just *dispatch, call the view, walk the tree*:

```clojure
(deftest counter-increments-in-the-view
  (rf/dispatch-sync [:counter/inc])
  (rf/dispatch-sync [:counter/inc])
  (is (= "2" (th/text-content
               (th/find-by-testid (counter/main) "counter-display")))))
```

`dispatch-sync` drains before the assertion, so calling `counter/main` returns the freshly-rendered tree; `find-by-testid` + `text-content` read the value under test. The two dispatches in that body are the *action under test* — the counter incrementing is the point. When a view instead needs state built *before* the action (a populated cart, a signed-in user), seed it once — a `dispatch-sync` in the `:init-fn`, or an `:ambient-frame`-scoped `make-frame` with `:initial-events [[:cart/seed-items …]]` per the [construction script](../frames.md#seeding-initial-state) `make-frame` takes. The body then holds only the interaction being tested.

The trap this composition already closes is the one [Test an event handler](event-handlers.md#4-the-trap-frames-dont-isolate-registrations) warns about: `:install` registrations land in the process-global registrar, and `make-reset-runtime-fixture` snapshots/restores it around every test, so one test's registrations can't leak into the next.

## 3. Drive the wiring

The last thing a view owns is the connection from a node to its dispatch. `th/invoke-handler` finds the handler attached at an event attribute and calls it — so the test proves the button is wired, not just present:

```clojure
(deftest inc-button-is-wired
  (let [btn (th/find-by-testid (th/expand-tree (counter/main)) "counter-inc")]
    (th/invoke-handler btn :on-click))          ;; runs the attached fn — the dispatch fires
  (is (ts/poll-until
        #(= "1" (th/text-content
                  (th/find-by-testid (counter/main) "counter-display")))
        {:label "counter reached 1"})))
```

Two details carry this test.

First, `invoke-handler` **throws** when the node has no handler under that key. A missing handler is almost always the bug you're hunting, so it refuses to pass silently.

Second, the settle uses `ts/poll-until`, not a straight walk. The invoked `:on-click` fires a plain `dispatch`, which queues rather than draining synchronously, so the test polls the re-rendered view against a bounded deadline (loud timeout carrying `:rf.error/poll-until-timeout`). The same form covers any async settle whose outcome is *visible in the view* — an HTTP reply, a machine `:after` transition, a scheduled event. On CLJS, `poll-until` returns a `js/Promise` — compose it with `cljs.test/async`. For a synchronous run, walking the tree straight after `dispatch-sync` is enough.

## When you want more than hiccup

Three neighbouring tools pick up where the tree walk stops:

- **Rendered markup** — when the assertion is about the HTML *string* a view produces (attribute serialisation, SSR output), `render-to-string` is the complementary path; see [`re-frame.ssr`](../../api/re-frame.ssr.md).
- **A real DOM** — when you genuinely need React mounted (a ref, a portal, an imperative child), use your adapter and settle updates with its `flush-views!` test helper; [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md#what-carries-over-what-doesnt) covers the shape. This is the rare case, not the default.
- **A view's *states*** — "show this view empty, loading, error, and loaded" is not a tree-walk job; it's [Story](../observability.md#the-tools-four-presentations-zero-second-truths)'s whole purpose: named variants in isolated frames, promotable into tests.

## When not to test a view

A confession to close on: I don't write many view tests. There, I said it.

Every test you write is a ball and chain you must forevermore drag about, so each one has to pay its way — and a view test that re-proves upstream logic doesn't. If the assertion is really "the sort order is right" or "the total is correct", that's a [subscription test](subscriptions.md) — cheaper, and it fails at the function that owns the logic. If it's "the state changed correctly", that's a [handler test](event-handlers.md). A view test earns its keep only when the thing under test is the view's own contribution: structure, text, wiring.

Most views are boring enough — deliberately — that they need no test at all. Boring is the goal.
