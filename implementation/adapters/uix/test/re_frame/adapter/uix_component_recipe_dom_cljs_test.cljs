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
