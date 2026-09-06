# Test a view

You've tested the [handler](event-handlers.md) and the [pipeline run](pipeline-runs.md). Now the view: the right structure comes out, the right text shows for a given state, and the right handler is wired to the right button.

No browser for this one either. A [view](../glossary.md#view) is a pure function that returns [hiccup](../glossary.md#hiccup) — plain data — so a view test is a function call and a tree walk, and it runs on the JVM in milliseconds.

> **A view test calls the function and walks the returned data — no DOM, no JSDOM, no `act()`.**

That is the Reagent view, and §1–§3 are about it. A UIx `defui` that calls `use-subscribe` or `use-frame` is a React *hook* component: hooks only run inside React's render, so there is no tree to walk without mounting one, and mounting one means a browser. Its recipe is [§4](#4-uix-hook-components-mount-it-for-real) — as small a loop, in a different place.

One honest framing before the recipe: **most "view bugs" are data bugs.** A view holds no state and decides nothing, so when the screen is wrong, the culprit is nearly always the [subscription](subscriptions.md) or [handler](event-handlers.md) upstream — pure functions with cheaper tests ([Views](../views.md#troubleshooting) makes the case). A view test is for what a view genuinely *owns*: its structure, its text, and its wiring. That's the whole list.

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

## 4. UIx hook components: mount it for real

Everything above calls a view as a function. A UIx `defui` that reads `use-subscribe` or `use-frame` can't be called that way — hooks run only inside React's render — so the test mounts it, for real, in a browser. The loop stays small: mount inside a frame boundary, drive it, settle React, read the DOM, unmount. This is the whole of it, and it is the test re-frame2 runs in its own browser lane, [`uix_component_recipe_dom_cljs_test.cljs`](../../../implementation/adapters/uix/test/re_frame/adapter/uix_component_recipe_dom_cljs_test.cljs), shown verbatim:

```clojure
(ns re-frame.adapter.uix-component-recipe-dom-cljs-test
  "A UIx component test, end to end: mount a hook component inside a frame
   boundary, drive it, settle React, read the real DOM, tear everything down.

   This file is the recipe `docs/core/testing/views.md` shows verbatim. Copy
   it into your app, replace the inline counter with a require of your own
   events / subs / views, and name the namespace for the build that runs it.
   It needs a browser — React mounts here for real. In re-frame2's own tree
   the `-dom-cljs-test` suffix puts it in the `:browser-test` lane
   (`npm run test:browser` from `implementation/`)."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [uix.core :refer [$ defui]]
            [uix.dom :as uix-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

;; -- The app under test ------------------------------------------------------
;; In your project these live in your events / subs / views namespaces —
;; require those instead. The ids carry a prefix of their own so this file can
;; sit in a test bundle beside other apps without colliding with theirs.

(rf/reg-event :recipe.counter/init
  (fn [_ _] {:db {:recipe.counter/value 0}}))

(rf/reg-event :recipe.counter/inc
  (fn [{:keys [db]} _] {:db (update db :recipe.counter/value inc)}))

(rf/reg-sub :recipe.counter/value
  (fn [db _] (:recipe.counter/value db)))

(defui counter []
  (let [n                  (rf.adapter.uix/use-subscribe [:recipe.counter/value])
        {:keys [dispatch]} (rf.adapter.uix/use-frame)]
    ($ :div
       ($ :span {:data-testid "counter-value"} n)
       ($ :button {:data-testid "counter-inc"
                   :on-click     #(dispatch [:recipe.counter/inc])}
          "+1"))))

;; -- Fixture -----------------------------------------------------------------
;; `:adapter` installs the UIx adapter and seats the `:rf/default` frame before
;; each test, and disposes the adapter and drops the frame after it. `:init-fn`
;; seeds the state the view needs. `:async? true` is the map-form fixture an
;; `(async done …)` test requires.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     :init-fn #(rf/dispatch-sync [:recipe.counter/init])
     :async?  true}))

;; -- Local helpers -----------------------------------------------------------
;; React's act() — which `flush-views!` wraps — asks the test environment to
;; declare itself. It is on while the test drives React through
;; `flush-views!`, and stood down while the test waits for an update that
;; arrives on React's own schedule (`wait-for` below) — the discipline
;; Testing Library's `waitFor` follows. The flag is a global, so `mount!`
;; captures the value it finds and `unmount!` puts that value back in a
;; `finally` — the recipe leaves the suite's act environment exactly as it
;; found it, even when a render or a teardown throws.

(defn- act-environment! [on?]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) on?))

(defn- wait-for
  "A bounded wait for `pred` to hold in the DOM; resolves when it does,
   rejects with `:rf.error/poll-until-timeout` when it never does."
  [pred label]
  (act-environment! false)
  (.finally (rf.test-support/poll-until pred {:label label})
            #(act-environment! true)))

(defn- mount!
  "Render `element` under `:rf/default` into a fresh node on the page, inside
   `flush-views!`, so the tree is committed when this returns. Captures the
   act-environment flag as it stood; `unmount!` restores it. A render that
   throws restores the flag and removes the node before rethrowing, so a
   failed mount leaves nothing behind."
  [element]
  (let [act-prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        node     (.createElement js/document "div")
        root     (uix-dom/create-root node)]
    (.appendChild js/document.body node)
    (try
      (act-environment! true)
      (rf.adapter.uix/flush-views!
        #(uix-dom/render-root
           ($ rf.adapter.uix/frame-provider {:frame :rf/default} element)
           root))
      {:node node :root root :act-prev act-prev}
      (catch :default e
        (act-environment! act-prev)
        (.remove node)
        (throw e)))))

(defn- unmount! [{:keys [node root act-prev]}]
  (try
    (rf.adapter.uix/flush-views! #(uix-dom/unmount-root root))
    (finally
      ;; Even when React's unmount or an effect cleanup throws, the node
      ;; leaves the page and the act flag goes back to what `mount!` found.
      (.remove node)
      (act-environment! act-prev))))

(defn- by-testid [node id]
  (.querySelector node (str "[data-testid=\"" id "\"]")))

(defn- text [node id]
  (.-textContent (by-testid node id)))

;; -- The tests ---------------------------------------------------------------

(deftest counter-shows-the-value-and-updates-on-dispatch
  (if-not (exists? js/document)
    (is true "no DOM here — the browser lane runs this test")
    (let [{:keys [node] :as mounted} (mount! ($ counter))]
      (try
        (is (= "0" (text node "counter-value")))
        ;; Drive the dataflow and settle React in one step: `dispatch-sync`
        ;; runs the event now, and act() commits the re-render before
        ;; `flush-views!` returns.
        (rf.adapter.uix/flush-views! #(rf/dispatch-sync [:recipe.counter/inc]))
        (is (= "1" (text node "counter-value")))
        (finally
          (unmount! mounted))))))

(deftest the-plus-one-button-is-wired
  (if-not (exists? js/document)
    (is true "no DOM here — the browser lane runs this test")
    (async done
      (let [{:keys [node] :as mounted} (mount! ($ counter))]
        ;; A real click. The view's `dispatch` queues the event and the router
        ;; drains it on the next turn, so the settle is a bounded wait on the
        ;; DOM — the same shape as any async settle whose outcome is visible
        ;; in the view.
        (.click (by-testid node "counter-inc"))
        (-> (wait-for #(= "1" (text node "counter-value")) "counter reached 1")
            (.then (fn [_] (is (= "1" (text node "counter-value")))))
            (.catch (fn [e] (is false (str "the +1 click never reached the DOM: " e))))
            (.finally (fn []
                        ;; Teardown cannot cost the suite its `done`: a throw
                        ;; out of `unmount!` is reported as a failure, and
                        ;; `done` runs regardless.
                        (try
                          (unmount! mounted)
                          ;; The restore is part of the recipe's contract: the
                          ;; suite sees the act flag this test found on entry.
                          (is (= (:act-prev mounted)
                                 (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
                              "unmount! restores the act-environment flag mount! captured")
                          (catch :default e
                            (is false (str "teardown threw: " e)))
                          (finally (done))))))))))

(deftest mount-unmount-hands-back-the-act-flag-it-found
  (if-not (exists? js/document)
    (is true "no DOM here — the browser lane runs this test")
    ;; The regression pin for the restore itself. A runner whose flag already
    ;; sits at `true` would let a teardown that merely forces `true` pass by
    ;; coincidence — so plant a sentinel the runner would never set, run one
    ;; mount/unmount round trip, and demand the sentinel back.
    (let [ambient (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
      (try
        (act-environment! "recipe-sentinel")
        (unmount! (mount! ($ counter)))
        (is (= "recipe-sentinel" (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
            "mount!/unmount! restore the exact pre-existing act-flag value")
        (finally
          (act-environment! ambient))))))
```

Four things carry it.

**The fixture owns the runtime.** `make-reset-runtime-fixture` with `:adapter` is the fixture from §2 — it installs the UIx adapter and seats `:rf/default` before every test, and disposes the adapter and drops the frame after — with `:async? true` because one test is asynchronous. The test scopes that frame into the tree with `frame-provider {:frame :rf/default}`. Your app's `frame-root {:id :rf/default …}` works in that position too: it reuses the fixture's frame without replaying `:initial-events`, which is why the seed lives in `:init-fn`.

**`flush-views!` settles what you drive.** It wraps React's `act()`: the mount inside it is committed by the time it returns, and so is the re-render a `dispatch-sync` inside it causes. That is the settle for state the test pushes in — and it is per-adapter-require, as [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md#what-carries-over-what-doesnt) tabulates.

**A real click settles on the router's clock, not React's.** The view's `dispatch` queues the event and the router drains it on the next turn, so no `act()` can settle it. The wait is `poll-until` on the DOM — the same bounded settle as §3 — composed with `cljs.test/async`. React's `act()` asks the environment to declare itself (`IS_REACT_ACT_ENVIRONMENT`); the recipe keeps it on while `flush-views!` drives React and stands it down while the test waits for an update that lands on React's own schedule, the discipline Testing Library's `waitFor` follows. And because the flag is a global, `mount!` captures the value it finds and `unmount!` puts it back — the suite around this test sees the act environment it started with. The file's last test pins that promise: it plants a sentinel value, runs one mount/unmount round trip, and demands the sentinel back, so the check holds even on a runner whose flag already sat at `true`. That is all the small helpers encode.

**Teardown is unconditional.** `unmount!` runs in a `finally` — or the promise's `.finally` — so a red assertion never leaves a root mounted on the page, and the helpers hold that line when React itself misbehaves: a `mount!` whose render throws restores the flag and removes its node before rethrowing, `unmount!` removes and restores through its own `finally` even when the unmount or an effect cleanup throws, and the async test's `done` runs no matter what teardown does. The fixture's `:after` takes care of the frame and the adapter.

Run it in a browser build. In re-frame2's tree the `-dom-cljs-test` suffix puts the file in the `:browser-test` lane (`npm run test:browser` from `implementation/`), and the adapter's `clojure -M:test` pins this page's block to that file byte for byte, so what you read here is what runs. In your own project the generated scaffold's `:test` build is a Node target with no DOM — the right default, since [handler](event-handlers.md) and [subscription](subscriptions.md) tests stay the bulk of what you write — and a component test like this one needs a shadow-cljs `:browser-test` target and a browser to open it in.

## When you want more than hiccup

Three neighbouring tools pick up where the tree walk stops:

- **Rendered markup** — when the assertion is about the HTML *string* a view produces (attribute serialisation, SSR output), `render-to-string` is the complementary path; see [`re-frame.ssr`](../../api/re-frame.ssr.md).
- **A real DOM** — when a Reagent view genuinely needs React mounted (a ref, a portal, an imperative child), the loop is [§4](#4-uix-hook-components-mount-it-for-real)'s with your adapter's `flush-views!` in place of UIx's — the name is shared, the require is per-adapter, as [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md#what-carries-over-what-doesnt) tabulates. For a Reagent view this is the rare case, not the default.
- **A view's *states*** — "show this view empty, loading, error, and loaded" is not a tree-walk job; it's [Story](../observability.md#the-tools-four-presentations-zero-second-truths)'s whole purpose: named variants in isolated frames, promotable into tests.

## When not to test a view

A confession to close on: I don't write many view tests. There, I said it.

Every test you write is a ball and chain you must forevermore drag about, so each one has to pay its way — and a view test that re-proves upstream logic doesn't. If the assertion is really "the sort order is right" or "the total is correct", that's a [subscription test](subscriptions.md) — cheaper, and it fails at the function that owns the logic. If it's "the state changed correctly", that's a [handler test](event-handlers.md). A view test earns its keep only when the thing under test is the view's own contribution: structure, text, wiring.

Most views are boring enough — deliberately — that they need no test at all. Boring is the goal.
