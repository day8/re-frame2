(ns re-frame.story.play.presence-freehand-dom-cljs-test
  "The BEHAVIOURAL proof of Story's presence rung on Freehand (rf2-gzmg0):
  a real `(v/presence …)` boundary, mounted with `v/mount` into a real
  `document`, holding a real retained child — and a Story `[:flush-presence]`
  script step that takes it off the screen.

  ## Why this file exists rather than another headless arm

  Story's seams exist to make a RUNNER deterministic, so the claim worth
  proving is not 'the bridge calls a function'. Every other arm of the rung
  reads a counter: `presence-cljs-test` proves the grammar and the hook
  against a stub, and `presence-real-clock-cljs-test` proves the shipped
  bridge reaches the framework's real exit scheduler by watching
  `pending-count` fall. Both would stay green if the retained DOM never
  moved — the removal callback fires either way, and only React decides
  whether the subtree actually leaves the page.

  That gap is exactly where the donor→Freehand crossing could have gone
  wrong. The donor's verb settled at an awaited React `act`; the Freehand
  bridge settles at the substrate's SYNCHRONOUS commit
  (`re-frame.substrate.adapter/flush-render!` → Freehand's `flushSync`
  override). A crossing that advanced the clock but committed nothing would
  pass every headless assertion in the rung and leave a dismissed toast on
  screen forever. So this file asserts against `document`.

  ## The red/green pair

  Both tests run the SAME script shape against the SAME retained child and
  differ in ONE step:

    with    `[[:flush-presence]]`  → the retained child LEAVES the DOM
    without `[[:dispatch …]]`      → the retained child is STILL THERE

  so neither verdict can be an artefact of the harness. The retention is a
  CLOCK, not a queue, which is the whole reason the rung exists: no amount
  of dispatching, draining or yielding removes that child.

  Naming convention (rf2-2hrj8): the `-dom-cljs-test$` suffix opts this file
  into the `:browser-test` build (Playwright + Chromium, real
  `react-dom/client`). `:node-test` also loads it — its `cljs-test$` regex
  matches the suffix — where the mounting branch self-gates on `(browser?)`
  and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.presence-runtime :as presence-rt]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.story :as story]
            [re-frame.story.late-bind :as late-bind]
            ;; The OPTIONAL bridge under test — required, not hand-rolled, so
            ;; this suite is an acceptance test of the ONE canonical
            ;; integration path (rf2-36biz).
            [re-frame.story.play.presence-host :as presence-host]
            [re-frame.story.play.runner-events :as re]
            [re-frame.substrate.adapter :as substrate]))

(def ^:private variant-frame :story.presence-freehand/frame)

(def ^:private retention-ms
  "The boundary's terminal retention bound. Large enough that no wall-clock
  timer can plausibly fire during the test — every removal here is the
  LOGICAL clock's, which is the determinism the rung exists for."
  60000)

;; ---------------------------------------------------------------------------
;; The views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview toast
  [{:keys [label]}]
  (let [phase (v/presence-phase)]
    [:div.rf-toast {:data-label  label
                    :data-phase  (name phase)
                    :aria-hidden (when (= :unmounting phase) true)}
     label]))

(v/defview toaster
  [_]
  (let [ids (v/sub [:toasts])]
    [:div#story-presence-stack
     (v/presence {:timeout-ms retention-ms}
       (for [k ids]
         [toast {:key k :label k}]))]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a Promise — used ONLY to drive the MOUNT, the
  way every Freehand DOM suite does. The presence advance under test is
  deliberately NOT act-driven: it settles through the substrate's own
  synchronous commit, and wrapping it here would prove the harness rather
  than the bridge."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- setup! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (presence-rt/reset-clock!)
  ;; Test-only determinism: the logical advance is the SOLE removal driver.
  ;; The BRIDGE deliberately leaves the wall clock armed — see `presence-host`
  ;; — so a suite that wants determinism disables it here, not there.
  (presence-rt/set-wall-clock! false)
  ;; Seat FREEHAND's own adapter, explicitly. A `rf/init!` guarded only by a
  ;; catch would silently keep whatever a sibling namespace left installed,
  ;; and a Freehand root mounted over someone else's substrate is a different
  ;; experiment from the one this file claims to run.
  (try (rf/destroy-adapter!) (catch :default _ nil))
  (rf/init! v/adapter)
  (reset! re/run-state {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (rf/make-frame {:id variant-frame :doc "Freehand presence rung test frame"})
  (rf/reg-sub :toasts (fn [db _] (:toasts db)))
  (rf/reg-event :toasts/seed    (fn [{:keys [db]} _] {:db (assoc db :toasts ["a" "b"])}))
  (rf/reg-event :toasts/dismiss (fn [{:keys [db]} _] {:db (assoc db :toasts ["a"])}))
  (rf/reg-event :toasts/noop    (fn [{:keys [db]} _] {:db db}))
  ;; Re-arm the bridge. Requiring it installed the hook once, at ns load;
  ;; `teardown!` drops it so this suite cannot leak a presence host into the
  ;; consolidated bundle, and `install!` is public for exactly this case.
  (presence-host/install!)
  nil)

(defn- teardown! []
  (try (rf/destroy-adapter!) (catch :default _ nil))
  (presence-rt/reset-clock!)
  (presence-rt/set-wall-clock! true)
  ;; The late-bind hook registry is process-global; a leaked host would arm
  ;; every LATER test's `[:flush-presence]` step.
  (swap! late-bind/hooks dissoc :flush-presence!)
  nil)

(use-fixtures :each {:before setup! :after teardown!})

(defn- host-node! []
  (let [node (js/document.createElement "div")]
    (.appendChild js/document.body node)
    node))

(defn- toast-el [container label]
  (.querySelector container (str ".rf-toast[data-label='" label "']")))

(defn- phase-of [container label]
  (some-> (toast-el container label) (.getAttribute "data-phase")))

(defn- dispatch-and-settle!
  "Dispatch into the variant frame and return with the Freehand DOM SETTLED —
  the substrate's own synchronous commit, which is what a Story `:dispatch`
  step reaches through the settled-boundary ladder."
  [event]
  (substrate/flush-render! (fn [] (router/dispatch-sync! event {:frame variant-frame}))))

(defn- skip! [why]
  (is true (str "a real Freehand mount needs a DOM host — " why)))

(defn- retained-child-on-screen!
  "Mount the boundary, dismiss `b`, and hand `k` the container with `b`
  RETAINED: still in the DOM, reading `:unmounting`, its removal pending on
  the logical clock. Every test below starts from exactly this state."
  [k]
  (let [container (host-node!)]
    (-> (act #(v/mount [toaster {}] container {:frame variant-frame}))
        (.then (fn [mounted]
                 (dispatch-and-settle! [:toasts/seed])
                 (is (some? (toast-el container "b")) "b mounted")
                 (dispatch-and-settle! [:toasts/dismiss])
                 (is (some? (toast-el container "b"))
                     "b is RETAINED in the DOM while exiting, not removed")
                 (is (= "unmounting" (phase-of container "b"))
                     "and its own (v/presence-phase) read reaches :unmounting")
                 (is (= 1 (presence-rt/pending-count))
                     "exactly one exit is retained on the logical clock")
                 (k container mounted))))))

(defn- cleanup! [container mounted]
  (try (v/unmount! mounted) (catch :default _ nil))
  (.remove container)
  nil)

;; ===========================================================================
;; GREEN — the runner's step takes the retained child off the screen
;; ===========================================================================

(deftest a-flush-presence-step-removes-the-retained-child-from-the-dom
  (testing "rf2-gzmg0 — the SHIPPED bridge, driven by the SHIPPED run loop:
            a `[:flush-presence]` script step advances the framework clock,
            fires the retained exit, and the substrate commits the removal
            before the run reports. Read back off `document`, because a
            crossing that advanced the clock and committed nothing would pass
            every counter-based arm of this rung."
    (if-not (browser?)
      (skip! "the browser job runs the removal assertion")
      (async done
        (retained-child-on-screen!
          (fn [container mounted]
            (re/run! variant-frame "flushing"
                     {:name   "flushing"
                      :script [[:flush-presence]]}
                     (fn [state]
                       (is (= :pass (:status state))
                           "the run passed — the step was reached and did not refuse")
                       (is (nil? (toast-el container "b"))
                           "the retained child LEFT THE DOM: the advance fired the
                            exit AND the substrate committed the unmount")
                       (is (some? (toast-el container "a"))
                           "and the present sibling is untouched by b's removal")
                       (is (zero? (presence-rt/pending-count))
                           "the exit fired exactly once, leaving nothing pending")
                       (cleanup! container mounted)
                       (done)))))))))

;; ===========================================================================
;; RED — the same script without the step leaves it exactly where it was
;; ===========================================================================

(deftest without-the-step-the-retained-child-stays-on-screen
  (testing "The NEGATIVE CONTROL for the test above, differing in ONE step.
            Retention is a CLOCK, not a queue: a script that dispatches and
            settles through every rung of the settled-boundary ladder still
            leaves the retained child on screen, because nothing advanced the
            logical clock. Without this, 'the child is gone' could be an
            artefact of the mount, the teardown or the harness rather than of
            the `[:flush-presence]` step."
    (if-not (browser?)
      (skip! "the browser job runs the retention assertion")
      (async done
        (retained-child-on-screen!
          (fn [container mounted]
            (re/run! variant-frame "not-flushing"
                     {:name   "not-flushing"
                      :script [[:dispatch [:toasts/noop]]]}
                     (fn [state]
                       (is (= :pass (:status state))
                           "the run itself is fine — it simply never flushed presence")
                       (is (some? (toast-el container "b"))
                           "the retained child is STILL on screen: no step advanced
                            the presence clock, and no other rung can")
                       (is (= "unmounting" (phase-of container "b"))
                           "still reading :unmounting, still awaiting its timeout")
                       (is (= 1 (presence-rt/pending-count))
                           "its exit is still pending")
                       (cleanup! container mounted)
                       (done)))))))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-bridge-is-installed-by-requiring-it
  (testing "Non-vacuity, and the claim the whole rung rests on: merely
            REQUIRING `re-frame.story.play.presence-host` installs the
            `:flush-presence!` hook. A suite whose bridge never loaded would
            report `:cannot-run` above rather than a removal, so pin the
            install here where the failure names itself."
    (is (some? (late-bind/get-fn :flush-presence!))
        "the load-time install armed the hook")))
