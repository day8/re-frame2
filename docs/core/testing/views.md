# Test a view

A [view](../glossary.md#view) returns [hiccup](../glossary.md#hiccup), which is plain data, so a view test is a function call and a tree walk. It checks what the view is responsible for: the structure it returns, the text it shows for a given state, and which event each control dispatches.

Most wrong screens are data bugs: a view holds no state and decides nothing, so the cause is usually upstream. If the assertion is really "the filter is right" or "the count is correct", write a [subscription test](subscriptions.md); if it is "the state changed correctly", write a [handler test](event-handlers.md). Write a view test for the view's own structure, text and wiring. Many views need none.

Sections 1–3 test the todo views from [Views](../views.md), which are `reg-view`s you can call as functions. A UIx `defui` that calls `use-sub` or `use-frame` is a React hook component and has to be mounted in a browser; [section 4](#4-uix-hook-components-mount-it-for-real) has that recipe.

The tools are `re-frame.test-helpers`, pure functions over hiccup [listed in the API reference](../../api/re-frame.test-helpers.md), plus the reset fixture from `re-frame.test-support`:

```clojure
(ns my-app.views-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.test-helpers :as th]
            [re-frame.substrate.plain-atom :as plain-atom]   ;; the JVM / headless adapter
            [my-app.todos]                                   ;; events
            [my-app.subs]                                    ;; subs
            [my-app.views :as views]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn #(rf/dispatch-sync
                 [:rf/set-db {:todos   {1 {:id 1 :title "Buy milk"     :done? false}
                                        2 {:id 2 :title "Walk the dog" :done? true}}
                              :showing :all}])}))
```

The fixture is the one [Testing](index.md#set-up-the-test-runner) sets up. It makes the `:rf/default` frame current before each test, so views can subscribe and dispatch, and then runs `:init-fn`, the place to seed state; here the built-in `:rf/set-db` event sets app-db wholesale. These tests share the fixture's `:rf/default` frame instead of making one per test, so the seed goes in `:init-fn`, the fixture's equivalent of `:initial-events`.

## 1. Call it, walk it

Give the nodes you'll assert on a stable address at the view site. `th/testid` builds an attrs map carrying `:data-testid`. Here is `todo-item` with test ids added:

```clojure
;; src/my_app/views.cljc
(rf/reg-view todo-item [{:keys [id title done?]}]
  [:li (th/testid (str "todo-" id))
   [:input (th/testid (str "toggle-" id)
                      {:type      "checkbox"
                       :checked   done?
                       :on-change #(dispatch [:todo/toggle id])})]
   [:span (th/testid (str "title-" id)) title]
   [:button (th/testid (str "delete-" id)
                       {:on-click #(dispatch [:todo/delete id])})
    "×"]])
```

Call the view like a function and read the tree it returns:

```clojure
(deftest todo-item-shows-title-and-state
  (let [tree (views/todo-item {:id 1 :title "Buy milk" :done? true})]
    (is (= "Buy milk" (th/text-content (th/find-by-testid tree "title-1"))))
    (is (true? (:checked (th/attrs (th/find-by-testid tree "toggle-1")))))))
```

`find-by-testid` returns the first node carrying that `:data-testid`, and `text-content` joins the string and number leaves under it. `find-all-by-testid`, `find-by-testid-prefix`, `find-by-attr`, `attrs` and `children` cover lists and other attributes.

??? info "Coming from React Testing Library?"

    `find-by-testid` and `text-content` correspond to `getByTestId` and `textContent`, but the "render" was a plain function call, so there is no JSDOM to set up or clean up.

## 2. Views that subscribe

`todo-footer` reads `:todo/remaining-count`, and `todo-list` is the `:todo/visible` version from [Views](../views.md#views-compute-hiccup-only), rendering a `todo-item` per todo. The test dispatches, calls the view and walks the tree:

```clojure
(deftest footer-counts-remaining
  (is (= "1 left to do" (th/text-content (views/todo-footer)))))

(deftest active-filter-hides-done-todos
  (rf/dispatch-sync [:todo/set-showing :active])
  (let [tree (views/todo-list)]
    (is (some? (th/find-by-testid tree "todo-1")))
    (is (nil?  (th/find-by-testid tree "todo-2")))))
```

`dispatch-sync` drains before it returns, so calling `todo-list` afterwards returns the updated tree. Each `[todo-item todo]` in the list is a component reference, a vector whose head is a function. The finders and `text-content` expand those references as they walk, so assertions reach through child views; `th/expand-tree` gives you the fully expanded tree as a value.

## 3. Drive the wiring

`th/invoke-handler` finds the function attached to a node's event attribute and calls it, so the test proves the checkbox is wired and not only present:

```clojure
(deftest checkbox-toggles-the-todo
  (let [box (th/find-by-testid (views/todo-list) "toggle-1")]
    (th/invoke-handler box :on-change))     ;; runs the attached fn, which dispatches
  (is (ts/poll-until
        #(= "0 left to do" (th/text-content (views/todo-footer)))
        {:label "todo 1 done"})))
```

`invoke-handler` throws `:rf.error/invoke-handler-missing` when the node has no function under that key, because a missing handler is usually the bug you are looking for. It throws `:rf.error/invoke-handler-bad-node` when it is handed something other than a hiccup vector, which usually means `find-by-testid` returned `nil` for a testid the tree doesn't carry.

The `:on-change` calls the view's `dispatch`, which queues the event instead of draining it, so the test polls until the condition holds or a deadline passes. A timeout throws `:rf.error/poll-until-timeout`. The same form covers any change that arrives later: an HTTP reply, a machine `:after` transition, a scheduled event. On the JVM `poll-until` is synchronous; on CLJS it returns a `js/Promise`, so compose it with `cljs.test/async`. After a `dispatch-sync`, walking the tree immediately is enough.

## 4. UIx hook components: mount it for real

A UIx `defui` that reads `use-sub` or `use-frame` can't be called as a function, because hooks run only inside React's render, so the test mounts it in a browser: mount inside a frame boundary, drive it, let React commit, read the DOM, unmount. The file below is the test re-frame2 runs in its own browser lane, [`uix_component_recipe_dom_cljs_test.cljs`](../../../implementation/adapters/uix/test/re_frame/adapter/uix_component_recipe_dom_cljs_test.cljs), shown verbatim. It tests a small counter rather than the todo app because it is re-frame2's own test file:

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

What each part does:

- **The fixture.** With `:adapter`, `make-reset-runtime-fixture` installs the UIx adapter and makes `:rf/default` current before each test, then disposes both afterwards. `:async? true` is there because one test is asynchronous. The test scopes the frame into the tree with `frame-provider {:frame :rf/default}`. Your app's `frame-root {:id :rf/default …}` also works there; it reuses the fixture's frame without replaying `:initial-events`, which is why the seed lives in `:init-fn`.
- **`flush-views!` for changes the test drives.** It wraps React's `act()`, so the mount inside it is committed when it returns, and so is the re-render caused by a `dispatch-sync` inside it. Each adapter has its own `flush-views!` (see [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md#what-carries-over-what-doesnt)).
- **`poll-until` for a real click.** The view's `dispatch` queues the event, so `act()` can't wait for it; the test polls the DOM instead, as in section 3. While it waits, `wait-for` turns React's act environment off, the way Testing Library's `waitFor` does, and `mount!`/`unmount!` restore the flag's previous value.

Run it in a browser build. In your own project, the generated scaffold's `:test` build is a Node target with no DOM, which suits handler and subscription tests. A component test like this one needs a shadow-cljs `:browser-test` target and a browser.

## When you want more than hiccup

- **Rendered markup.** When the assertion is about the HTML string a view produces, use `render-to-string`; see [`re-frame.ssr`](../../api/re-frame.ssr.md).
- **A real DOM.** When a Reagent view needs React mounted (a ref, a portal, an imperative child), use the recipe from [section 4](#4-uix-hook-components-mount-it-for-real) with your adapter's `flush-views!`.
- **A view's states.** "Show this view empty, loading, failed and loaded" is a job for [Story](../observability.md#tools-that-read-the-trace-stream): named variants in isolated frames, which can be promoted into tests.
