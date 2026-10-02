# Test a view

A [view](../glossary.md#view) returns [hiccup](../glossary.md#hiccup), which is plain data, so a view test is a function call and a tree walk. It checks what the view is responsible for: the structure it returns, the text it shows for a given state, and which event each control dispatches.

Many wrong screens are data bugs whose cause is upstream of the view. If the assertion is really "the filter is right" or "the count is correct", write a [subscription test](subscriptions.md); if it is "the state changed correctly", write a [handler test](event-handlers.md). Write a view test for the view's own structure, text and wiring. Many views need none.

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
;; Add [re-frame.test-helpers :as th] to this namespace's :require list.
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

A UIx `defui` that reads `use-sub` or `use-frame` uses React hooks, so test it by
mounting it in a browser. This small counter shows the whole sequence: seed state,
mount under a frame boundary, dispatch, check the DOM, and clean up.

Run this namespace in a shadow-cljs `:browser-test` build. The generated scaffold's
`:test` build targets Node, where there is no DOM. In an app, require your own
events, subs and component instead of the inline counter below.

```clojure
(ns my-app.counter-view-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [uix.core :refer [$ defui]]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as ts]))

(rf/reg-event :recipe.counter/inc
  (fn [{:keys [db]} _]
    {:db (update db :recipe.counter/value inc)}))

(rf/reg-sub :recipe.counter/value
  (fn [db _] (:recipe.counter/value db)))

(defui counter []
  (let [n                  (uix-adapter/use-sub [:recipe.counter/value])
        {:keys [dispatch]} (uix-adapter/use-frame)]
    ($ :div
       ($ :span {:data-testid "counter-value"} n)
       ($ :button {:on-click #(dispatch [:recipe.counter/inc])} "+1"))))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :init-fn #(rf/dispatch-sync [:rf/set-db {:recipe.counter/value 0}])}))

(deftest counter-updates-on-dispatch
  (let [node     (.createElement js/document "div")
        root     (uix-adapter/client-root)
        act-prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        value    #(.-textContent
                    (.querySelector node "[data-testid='counter-value']"))]
    (.appendChild js/document.body node)
    (try
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      ;; Mount and settle React.
      (uix-adapter/flush-views!
        #(uix-adapter/render! root
           ($ uix-adapter/frame-provider {:frame :rf/default} ($ counter))
           node))
      (is (= "0" (value)))

      ;; Run the event and settle the resulting render.
      (uix-adapter/flush-views! #(rf/dispatch-sync [:recipe.counter/inc]))
      (is (= "1" (value)))

      (finally
        (try
          (uix-adapter/flush-views! #(uix-adapter/unmount! root))
          (finally
            (.remove node)
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) act-prev)))))))
```

`dispatch-sync` commits app-db before returning; `flush-views!` wraps React's
`act()` so the DOM is committed too. The fixture owns the frame, and the test
owns the mounted root and DOM node. Its `finally` unmounts the root, removes the
node and restores React's previous test-environment flag even if an assertion or
render fails.

This checks the view's response to a dispatch. To check the button's wiring,
click it and wait for the DOM: its ordinary `dispatch` queues the event instead
of running it synchronously. The shipped
[`uix_component_recipe_dom_cljs_test.cljs`](../../../implementation/adapters/uix/test/re_frame/adapter/uix_component_recipe_dom_cljs_test.cljs)
includes that asynchronous click test, a bounded `poll-until` wait and reusable
mount helpers.

## When you want more than hiccup

- **Rendered markup.** When the assertion is about the HTML string a view produces, use `render-to-string`; see [`re-frame.ssr`](../../api/re-frame.ssr.md).
- **A real DOM.** When a Reagent view needs React mounted (a ref, a portal, an imperative child), follow [section 4](#4-uix-hook-components-mount-it-for-real) using the Reagent adapter's `client-root`, `render!`, `unmount!` and `flush-views!`, and a hiccup tree in place of UIx's `$` element.
- **A view's states.** "Show this view empty, loading, failed and loaded" is a job for [Story](../observability.md#tools-that-read-the-trace-stream): named variants in isolated frames, which can be promoted into tests.
