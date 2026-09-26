# Test a view

A view test checks what a view is responsible for: the structure it returns, the text it shows for a given state, and which handler is attached to which button. A [view](../glossary.md#view) is a function that returns [hiccup](../glossary.md#hiccup), plain data, so the test is a function call and a tree walk. It needs no browser, no JSDOM and no `act()`, and runs on the JVM in milliseconds.

Sections 1–3 cover views written as plain functions (Reagent-style hiccup). A UIx `defui` that calls `use-sub` or `use-frame` is a React hook component: hooks only run inside React's render, so there is no tree to walk without mounting it in a browser. Its recipe is [section 4](#4-uix-hook-components-mount-it-for-real).

Most wrong screens are data bugs. A view holds no state and decides nothing, so the cause is usually the [subscription](subscriptions.md) or [handler](event-handlers.md) upstream, and those have cheaper tests ([Views](../views.md#troubleshooting) covers the diagnosis). Keep view tests for structure, text and wiring.

The tools are `re-frame.test-helpers`, pure functions over hiccup [listed in the API reference](../../api/re-frame.test-helpers.md), plus the `re-frame.test-support` fixture:

```clojure
(ns my-app.views-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.test-helpers :as th]
            [re-frame.substrate.plain-atom :as plain-atom]   ;; the JVM / headless adapter
            [my-app.counter :as counter]))   ;; the app namespace under test

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                  :init-fn #(rf/dispatch-sync [:counter/init])}))
```

Given an `:adapter`, `make-reset-runtime-fixture` installs it before each test and makes the `:rf/default` frame current for the test body. `:init-fn` runs after that, before the test, which makes it the place to seed state. The fixture also snapshots and restores the registrar around every test. A purely presentational test (section 1) needs neither key, and `(ts/make-reset-runtime-fixture {})` is enough; the connected tests below rely on the `:rf/default` frame.

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

`find-by-testid` returns the first node carrying that `:data-testid`, and `text-content` concatenates the string leaves under it. `find-all-by-testid`, `find-by-testid-prefix`, `find-by-attr`, `attrs` and `children` cover lists and custom attributes ("every node whose testid starts with `row-`").

A view that renders another view returns a component reference such as `[cart-line item]`, a vector whose head is a function rather than a tag. The finders and `text-content` expand those references as they walk, so assertions reach through child views. When you want the fully expanded tree as a value, to bind once for several assertions or walk by hand, call `th/expand-tree`.

??? info "Coming from React Testing Library?"

    `find-by-testid` and `text-content` correspond to `getByTestId` and `textContent`, but the "render" was a plain function call, so there is no JSDOM to set up or clean up. The query API is smaller because you are walking a value, not a live document.

## 2. Views that subscribe: the reset fixture

A presentational view takes data as arguments. A connected view subscribes and dispatches, so it needs a [frame](../glossary.md#frame) in scope, and the reset fixture at the top of the page provides `:rf/default`. The test dispatches, calls the view and walks the tree:

```clojure
(deftest counter-increments-in-the-view
  (rf/dispatch-sync [:counter/inc])
  (rf/dispatch-sync [:counter/inc])
  (is (= "2" (th/text-content
               (th/find-by-testid (counter/main) "counter-display")))))
```

`dispatch-sync` drains before it returns, so calling `counter/main` afterwards returns the updated tree. The two dispatches are the action under test. When a view needs state built before the action (a populated cart, a signed-in user), seed it in the fixture's `:init-fn` with `dispatch-sync`, so the body holds only the interaction being tested.

Because the fixture snapshots and restores the registrar around every test, one test's registrations can't leak into the next, which is the trap [Test an event handler](event-handlers.md#4-the-trap-frames-dont-isolate-registrations) describes.

## 3. Drive the wiring

`th/invoke-handler` finds the function attached to a node's event attribute and calls it, so the test proves the button is wired and not just present:

```clojure
(deftest inc-button-is-wired
  (let [btn (th/find-by-testid (th/expand-tree (counter/main)) "counter-inc")]
    (th/invoke-handler btn :on-click))          ;; runs the attached fn, which dispatches
  (is (ts/poll-until
        #(= "1" (th/text-content
                  (th/find-by-testid (counter/main) "counter-display")))
        {:label "counter reached 1"})))
```

`invoke-handler` throws `:rf.error/invoke-handler-missing` when the node has no function under that key, because a missing handler is usually the bug you are looking for.

The wait uses `ts/poll-until` rather than an immediate walk. The `:on-click` calls a plain `dispatch`, which queues the event instead of draining it, so the test polls the view until the condition holds or a deadline passes; a timeout throws `:rf.error/poll-until-timeout`. The same form covers any asynchronous change that shows up in the view: an HTTP reply, a machine `:after` transition, a scheduled event. On the JVM `poll-until` is synchronous; on CLJS it returns a `js/Promise`, so compose it with `cljs.test/async`. After a `dispatch-sync`, walking the tree immediately is enough.

## 4. UIx hook components: mount it for real

A UIx `defui` that reads `use-sub` or `use-frame` can't be called as a function, because hooks run only inside React's render, so the test mounts it in a browser: mount inside a frame boundary, drive it, let React commit, read the DOM, unmount. The file below is the test re-frame2 runs in its own browser lane, [`uix_component_recipe_dom_cljs_test.cljs`](../../../implementation/adapters/uix/test/re_frame/adapter/uix_component_recipe_dom_cljs_test.cljs), shown verbatim:

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
  (let [n                  (rf.adapter.uix/use-sub [:recipe.counter/value])
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

**The fixture.** `make-reset-runtime-fixture` with `:adapter` installs the UIx adapter and makes `:rf/default` current before every test, then disposes the adapter and drops the frame after it. `:async? true` is there because one test is asynchronous. The test scopes that frame into the tree with `frame-provider {:frame :rf/default}`. Your app's `frame-root {:id :rf/default …}` also works in that position: it reuses the fixture's frame without replaying `:initial-events`, which is why the seed lives in `:init-fn`.

**`flush-views!` for changes the test drives.** It wraps React's `act()`, so the mount inside it is committed when it returns, and so is the re-render caused by a `dispatch-sync` inside it. Each adapter has its own `flush-views!`, as [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md#what-carries-over-what-doesnt) shows.

**`poll-until` for a real click.** The view's `dispatch` queues the event and the router drains it on a later turn, so `act()` can't wait for it. The test polls the DOM with `poll-until`, as in section 3, under `cljs.test/async`. React's `act()` checks the global `IS_REACT_ACT_ENVIRONMENT` flag; the recipe sets it while `flush-views!` drives React and clears it while waiting for an update React schedules itself, which is what Testing Library's `waitFor` does. `mount!` records the flag's previous value and `unmount!` restores it, so the rest of the suite sees the value it started with. The file's last test checks that restore with a sentinel value, so it holds even on a runner whose flag is already `true`.

**Unconditional teardown.** `unmount!` runs in a `finally` (or the promise's `.finally`), so a failed assertion never leaves a root mounted. A `mount!` whose render throws restores the flag and removes its node before rethrowing, `unmount!` does both in its own `finally` even when React's unmount or an effect cleanup throws, and the async test calls `done` whatever teardown does. The fixture's `:after` disposes the frame and the adapter.

Run it in a browser build. In re-frame2's tree the `-dom-cljs-test` suffix puts the file in the `:browser-test` lane (`npm run test:browser` from `implementation/`), and a JVM test in the UIx adapter checks that this page's block matches that file byte for byte. In your own project the generated scaffold's `:test` build is a Node target with no DOM, which suits the [handler](event-handlers.md) and [subscription](subscriptions.md) tests that make up most of a suite. A component test like this one needs a shadow-cljs `:browser-test` target and a browser to run it.

## When you want more than hiccup

- **Rendered markup.** When the assertion is about the HTML string a view produces (attribute serialisation, SSR output), use `render-to-string`; see [`re-frame.ssr`](../../api/re-frame.ssr.md).
- **A real DOM.** When a Reagent view needs React mounted (a ref, a portal, an imperative child), use the loop from [section 4](#4-uix-hook-components-mount-it-for-real) with your adapter's `flush-views!` in place of UIx's. For a Reagent view this is the exception.
- **A view's states.** "Show this view empty, loading, failed and loaded" is a job for [Story](../observability.md#tools-that-read-the-trace-stream): named variants in isolated frames, which can be promoted into tests.

## When not to test a view

Every test is code you maintain, and a view test that re-checks upstream logic costs more than it catches. If the assertion is really "the sort order is right" or "the total is correct", write a [subscription test](subscriptions.md): it is cheaper and fails at the function that owns the logic. If it is "the state changed correctly", write a [handler test](event-handlers.md). Write a view test when the thing under test is the view's own structure, text or wiring. Many views are simple enough to need none.
