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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as ts]))

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
  (let [n                  (uix-adapter/use-subscribe [:recipe.counter/value])
        {:keys [dispatch]} (uix-adapter/use-frame)]
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
  (ts/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :init-fn #(rf/dispatch-sync [:recipe.counter/init])
     :async?  true}))

;; -- Local helpers -----------------------------------------------------------
;; React's act() — which `flush-views!` wraps — asks the test environment to
;; declare itself. It is on while the test drives React through
;; `flush-views!`, and stood down while the test waits for an update that
;; arrives on React's own schedule (`wait-for` below) — the discipline
;; Testing Library's `waitFor` follows.

(defn- act-environment! [on?]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) on?))

(defn- wait-for
  "A bounded wait for `pred` to hold in the DOM; resolves when it does,
   rejects with `:rf.error/poll-until-timeout` when it never does."
  [pred label]
  (act-environment! false)
  (.finally (ts/poll-until pred {:label label})
            #(act-environment! true)))

(defn- mount!
  "Render `element` under `:rf/default` into a fresh node on the page, inside
   `flush-views!`, so the tree is committed when this returns."
  [element]
  (let [node (.createElement js/document "div")
        root (uix-dom/create-root node)]
    (.appendChild js/document.body node)
    (act-environment! true)
    (uix-adapter/flush-views!
      #(uix-dom/render-root
         ($ uix-adapter/frame-provider {:frame :rf/default} element)
         root))
    {:node node :root root}))

(defn- unmount! [{:keys [node root]}]
  (uix-adapter/flush-views! #(uix-dom/unmount-root root))
  (.remove node))

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
        (uix-adapter/flush-views! #(rf/dispatch-sync [:recipe.counter/inc]))
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
                        (unmount! mounted)
                        (done))))))))
