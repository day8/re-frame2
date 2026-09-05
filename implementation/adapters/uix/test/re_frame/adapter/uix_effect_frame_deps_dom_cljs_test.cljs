(ns re-frame.adapter.uix-effect-frame-deps-dom-cljs-test
  "rf2-tug6 — an imperative `use-effect` listener follows the frame its
  component is rendered under, across a PROVIDER SWAP.

  ## The defect

  The canonical outer/inner recipe in `implementation/adapters/uix/README.md`
  installs a DOM listener from `use-effect` and dispatches from it through the
  ops map `use-frame` returns. The effect closed over TWO reactive values —
  the domain prop and `dispatch` — and named only the prop in its deps vector.

  `use-frame`'s bundle is reference-stable for one resolved frame INCARNATION
  and a NEW map once the surrounding `frame-provider` retargets
  (`re-frame.adapter.use-frame/use-frame`'s render-phase memo is keyed on the
  frame AND its incarnation token). So a provider swap re-renders the mounted
  component with a B-locked `dispatch` in hand — and React, told only that the
  prop is unchanged, does NOT re-run the effect. The listener installed under
  A stays installed, and the next DOM event dispatches into A.

  That is an isolation break, not staleness: frames are isolated contexts, so
  a write into still-live A raises nothing at all. It just lands in the frame
  the UI has navigated away from. (Had A been destroyed instead, the capture
  fence would have dropped it and emitted the destroyed-frame error — the
  louder half, and the less serious one.)

  ## What is pinned, and why in this shape

  Both deftests drive the REAL swap path: one mount, one component instance,
  the provider's `:frame` prop changed and the tree re-rendered in place. That
  is the trap this suite exists to avoid — a test that remounts, or that
  renders a second instance, exercises nothing, because a fresh mount runs its
  effect afresh and passes with or without the dependency. Each therefore
  asserts the DOM element is `identical?` across the swap before drawing any
  conclusion from what the listener did.

  The negative control is a second component carrying the ORIGINAL deps
  vector, marked `^:lint/disable` so UIx's exhaustive-deps linter reads the
  omission as deliberate rather than warning on it. It is the control this
  suite would otherwise be missing: without it, a green here could equally
  mean the fix works or that the swap never reached the component. It fails
  in the specific way the bead describes — the second event lands in A again,
  and B never hears it.

  The recipe component is compiled here with its real spelling
  (`uix/use-effect` + `rf.adapter.uix/use-frame` + a UIx `use-ref` on a DOM
  node), so UIx's own `::missing-deps` analysis guards the dependency list at
  compile time as well.

  WHY `dispatch-sync` AND NOT `dispatch`. The two come off the same ops map
  and are re-created together, so the closure capture and the deps obligation
  are identical; `dispatch-sync` lets the assertion read the frame's app-db
  immediately after the event instead of waiting out a router macrotask
  (`re-frame.interop/next-tick` is deliberately NOT a microtask, so `act()`
  does not drain it). The README teaches `dispatch`; nothing about the
  dependency differs.

  TOOTH: put the recipe component's deps back to `[tile-id]` and
  `imperative-effect-follows-the-frame-across-a-provider-swap` fails exactly
  where the negative control below already fails.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` (ns-regexp
  `-dom-cljs-test$`) discovers it; `:node-test`'s `cljs-test$` regex also
  matches, where both deftests self-gate on `(browser?)` and no-op cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.uix/adapter}))

;; ---- lane gate -------------------------------------------------------------
;;
;; Local rather than borrowed: the shared React suite's equivalents are
;; private, and this file forwards nothing to it.

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- with-browser-act
  "Skip under `:node-test` (no DOM) and when `act()` is unreachable;
  otherwise opt the runner into React's act environment and call `(f act-fn)`."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (if-let [act-fn (and (exists? (.-act React)) (.-act React))]
      (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (f act-fn))
      (is true "act() not reachable from this runner; skipping"))))

;; ---- the two frames and the one event --------------------------------------

(def ^:private frame-a :rf.uix-effect-frame-deps/frame-a)
(def ^:private frame-b :rf.uix-effect-frame-deps/frame-b)

(defn- hits
  "Every tile-id the named frame has been told finished, in order."
  [frame-kw]
  (:hits (rf/app-db-value frame-kw)))

;; ---- probes ----------------------------------------------------------------
;;
;; `effect-log` records the effect's setup/cleanup calls so the balance
;; assertion reads what React actually did rather than inferring it.

(def ^:private effect-log (atom []))

(defui tile-inner
  "The README's canonical imperative-lifecycle recipe, compiled."
  [{:keys [tile-id]}]
  (let [ref                     (uix/use-ref)
        {:keys [dispatch-sync]} (rf.adapter.uix/use-frame)]
    (uix/use-effect
      (fn []
        (let [el       @ref
              listener (fn [_evt] (dispatch-sync [::finished tile-id]))]
          (swap! effect-log conj :setup)
          (.addEventListener el "animationend" listener)
          (fn cleanup []
            (swap! effect-log conj :cleanup)
            (.removeEventListener el "animationend" listener))))
      [tile-id dispatch-sync])
    ($ :div {:ref ref :class "tile"})))

(defui stale-tile-inner
  "The recipe as it read before rf2-tug6 — identical but for the deps vector,
  which names the domain prop alone. `^:lint/disable` because the omission is
  the subject here, not a mistake for UIx's linter to report."
  [{:keys [tile-id]}]
  (let [ref                     (uix/use-ref)
        {:keys [dispatch-sync]} (rf.adapter.uix/use-frame)]
    (uix/use-effect
      (fn []
        (let [el       @ref
              listener (fn [_evt] (dispatch-sync [::finished tile-id]))]
          (swap! effect-log conj :setup)
          (.addEventListener el "animationend" listener)
          (fn cleanup []
            (swap! effect-log conj :cleanup)
            (.removeEventListener el "animationend" listener))))
      ^:lint/disable [tile-id])
    ($ :div {:ref ref :class "tile"})))

;; ---- the shared drive ------------------------------------------------------

(defn- run-provider-swap!
  "Mount `component` (one instance, prop `{:tile-id 7}`) under a provider
  targeting `frame-a`, fire the event, retarget the SAME tree at `frame-b`,
  fire it again. Returns the observations; asserts the in-place update itself,
  because every later conclusion depends on it.

  The two frames and the `::finished` handler are created here so each deftest
  gets them fresh from the reset fixture.

  The ambient `:rf/default` dynamic scope the fixture installs is cleared for
  the duration: `use-frame` resolves the dynamic-var tier BEFORE the provider
  tier, so leaving it bound masks the provider outright and both renders
  resolve the same frame — the suite would then pass while proving nothing
  (the rf2-4mi2zj masking note, and the same `binding` the shared React
  suite's provider rows take)."
  [act-fn component]
  (reset! effect-log [])
  (rf/reg-event ::finished
                (fn [{:keys [db]} [_ tile-id]]
                  {:db (update db :hits (fnil conj []) tile-id)}))
  (rf/make-frame {:id frame-a :doc "rf2-tug6 — provider target A"})
  (rf/make-frame {:id frame-b :doc "rf2-tug6 — provider target B"})
  (binding [rf.frame/*current-frame* nil]
   (let [mount-node (.createElement js/document "div")
         root       (react-dom-client/createRoot mount-node)
         render!    (fn [frame-kw]
                      (act-fn (fn []
                                (.render root ($ rf.adapter.uix/frame-provider
                                                 {:frame frame-kw}
                                                 ($ component {:tile-id 7}))))))
         element    #(.querySelector mount-node ".tile")
         fire!      (fn [el] (act-fn (fn [] (.dispatchEvent el (js/Event. "animationend")))))]
     (try
       (render! frame-a)
       (let [el-under-a (element)]
         ;; Control: the listener is installed and routes to the mounting
         ;; frame. Without this row a suite where the listener never attached
         ;; would pass the isolation assertions vacuously.
         (fire! el-under-a)
         (let [after-mount {:log @effect-log :a (hits frame-a) :b (hits frame-b)}]

           ;; THE SWAP. Same component type, same position, same key, same
           ;; domain prop — only the provider's target changes.
           (render! frame-b)
           (let [el-under-b (element)]
             (is (identical? el-under-a el-under-b)
                 (str "the provider swap UPDATED the mounted instance in place. "
                      "A remount here would run the effect afresh and make every "
                      "assertion below vacuous"))
             (fire! el-under-b)
             {:after-mount after-mount
              :log         @effect-log
              :a           (hits frame-a)
              :b           (hits frame-b)})))
       (finally
         (try (.unmount root) (catch :default _ nil)))))))

;; ---- the pin ---------------------------------------------------------------

(deftest imperative-effect-follows-the-frame-across-a-provider-swap
  (testing "naming `dispatch` in the deps vector re-installs the listener on
            the frame the component is now rendered under — the post-swap
            event reaches B alone, exactly once"
    (with-browser-act
      (fn [act-fn]
        (let [{:keys [after-mount log a b]} (run-provider-swap! act-fn tile-inner)]
          (is (= [7] (:a after-mount))
              "control: before the swap the listener routes to the mounting frame")
          (is (nil? (:b after-mount))
              "control: and B has heard nothing yet")

          (is (= [7] b)
              "after the swap the event reaches B")
          (is (= [7] a)
              (str "and A is untouched by it — still holding only the control "
                   "hit. A second entry here is the isolation break: a write "
                   "into the frame the UI has navigated away from"))
          (is (= [:setup :cleanup :setup] log)
              (str "the effect re-ran ONCE and its cleanup was balanced — the "
                   "old listener was removed, so one event produces one "
                   "dispatch rather than two")))))))

(deftest omitting-the-frame-dep-keeps-dispatching-into-the-previous-frame
  (testing "the negative control: with the domain prop alone in the deps
            vector the effect never re-runs, and the listener installed under
            A keeps writing into A after the provider has moved to B"
    (with-browser-act
      (fn [act-fn]
        (let [{:keys [after-mount log a b]} (run-provider-swap! act-fn stale-tile-inner)]
          (is (= [7] (:a after-mount))
              "control: the listener starts out routed to the mounting frame")

          (is (= [:setup] log)
              "the effect did NOT re-run across the swap — React was told only
               that the unchanged prop was unchanged")
          (is (= [7 7] a)
              "so the post-swap event landed in A a second time")
          (is (nil? b)
              (str "and B — the frame the component is now rendered under — "
                   "never heard it. This is what the recipe's deps vector "
                   "prevents (rf2-tug6)")))))))
