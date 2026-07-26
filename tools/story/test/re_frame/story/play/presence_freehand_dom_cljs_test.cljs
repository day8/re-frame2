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
  bridge settles at a SYNCHRONOUS React commit. A crossing that advanced the
  clock but committed nothing would pass every headless assertion in the rung
  and leave a dismissed toast on screen forever. So this file asserts against
  `document`.

  ## Both seatings, because Story ships the OTHER one

  The behavioural pair runs under two seated substrate adapters, and the
  second is the one that matters:

  - `v/adapter` — a pure Freehand process.
  - `re-frame.adapter.reagent/adapter` — **the shipped Story topology.** Every
    Story testbed (`tools/story/testbeds/*/core.cljs`) seats Reagent, because
    Story's own shell is a Reagent application; a Freehand variant then mounts
    inside that process. Spec 006 calls the mixed shape legitimate, and it is
    the only shape a Story user actually gets.

  A proof that only ever seated `v/adapter` proved the law under a topology
  nobody ships (merged-PR audit #7037). The bridge originally settled through
  `re-frame.substrate.adapter/flush-render!`, which DISPATCHES on the seated
  adapter; under Reagent that lands on `(fn [f] (f) (reagent.core/flush))`,
  which drains Reagent's own queues and never enters `react-dom/flushSync`
  when no Reagent component is dirty — and a presence removal makes none
  dirty, because it removes a retained key by calling a plain React `useState`
  setter inside the Freehand root. The bridge now reads Freehand's own
  `flush-render!` off `v/adapter`, so the boundary no longer depends on what
  the process seated; these arms are what forced that and what hold it.

  ## Why the Reagent arms drive presence through PROPS

  A Freehand view's SUBSCRIPTION reads are inert on the ratom substrates, and
  that is a separate defect upstream of Story. Core's observation port
  activates a derived value by adding a watch and dereferencing it
  (`re-frame.substrate.observation/build-node-handle!`), but a stock Reagent
  `Reaction` deref'd outside `*ratom-context*` takes the non-reactive branch
  of its own `-deref` and never captures its sources — so it never notifies,
  and the ViewCell it feeds is never marked dirty. Measured here: under
  Reagent, a `(v/sub …)`-driven boundary renders once and never again, so no
  retained child can be produced at all.

  So the Reagent arms build the retained child the way `v/mount` documents
  as idempotent-per-root: re-mounting the same root-id into the same container
  RE-RENDERS it. Presence keys arrive as props, a re-mount drops one, and the
  boundary retains it. Nothing about the `[:flush-presence]` step changes —
  it still travels `re/run!` → the run loop → `presence/advance!` → the
  shipped bridge — and `the-props-driven-fixture-behaves-the-same-on-a-
  freehand-process` runs the identical fixture under `v/adapter`, so the ONLY
  variable between the two green arms is the seated adapter.

  ## What each arm reads, and why there are two reads

  `gone-at-verb-return` is the tight one. It snapshots `document` at the
  instant the SHIPPED host verb returns, through a passthrough wrapper around
  the verb the bridge itself registered — so it measures the bridge's own
  stated law (*advance the presence clock and return with the DOM settled*)
  at exactly the boundary that law names. Nothing about the dispatch is
  hand-written: the wrapper delegates to the registered bridge and hands its
  return value straight back.

  The read in the run callback is the looser one, and it is here because it
  is what a Story AUTHOR observes. Both caught the unfixed bridge — the child
  was still painted at the callback too — but only the tight read is
  GUARANTEED to: the run loop yields a `setTimeout` 0 after a
  `:flush-presence` step (`runner/async-yield-step-types`), and React's own
  scheduler task is a macrotask that can win that race on a different engine
  or a different day. Keep both, and treat the tight one as the evidence.

  ## The red/green pair

  Both behavioural tests run the SAME script shape against the SAME retained
  child and differ in ONE step:

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
            ;; The adapter EVERY shipped Story testbed seats. Already a Story
            ;; dependency (`day8/re-frame2-reagent`, main `:deps`) because the
            ;; Story shell is itself a Reagent application.
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.presence-runtime :as presence-rt]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.story :as story]
            [re-frame.story.late-bind :as late-bind]
            [re-frame.story.play.presence :as story-presence]
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

(def ^:private freehand-commit!
  "Freehand's OWN synchronous commit boundary, read off the PUBLISHED adapter
  value rather than off whatever adapter the process installed — the same slot
  the bridge reaches for, and for the same reason.

  HARNESS ONLY. Reaching for it here settles the SETUP; the thing under test
  is the `[:flush-presence]` step, and that always travels `re/run!`."
  (:flush-render! v/adapter))

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
  "Presence children from a SUBSCRIPTION — the shape a Freehand application
  writes, and the shape the `v/adapter` arms drive."
  [_]
  (let [ids (v/sub [:toasts])]
    [:div#story-presence-stack
     (v/presence {:timeout-ms retention-ms}
       (for [k ids]
         [toast {:key k :label k}]))]))

(v/defview prop-toaster
  "Presence children from PROPS — the same boundary, re-rendered by a
  re-mount rather than by a moving subscription. See the ns docstring
  §Why the Reagent arms drive presence through PROPS."
  [{:keys [ids]}]
  [:div#story-presence-stack
   (v/presence {:timeout-ms retention-ms}
     (for [k ids]
       [toast {:key k :label k}]))])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a Promise — used ONLY to drive the MOUNTS, the
  way every Freehand DOM suite does. The presence advance under test is
  deliberately NOT act-driven: it settles through Freehand's own synchronous
  commit, and wrapping it here would prove the harness rather than the bridge.

  The act-environment flag is RESTORED once React settles. Leaving it armed
  would make every subsequent update outside an act boundary — which is every
  update this file cares about — emit React's `not wrapped in act(...)`
  warning, so the lane's console would fill with noise about the exact thing
  the design says not to do here."
  [thunk]
  (let [prior (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        restore! (fn [] (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior))]
    (try
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      (-> (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
          (.then (fn [v] (restore!) v)
                 (fn [e] (restore!) (throw e))))
      (catch :default e
        (restore!)
        (js/Promise.reject e)))))

(defn- setup!
  "Everything that is true of NEITHER seating. Which adapter is installed is
  the variable this file exists to vary, so seating is each test's own first
  act — see `seat!`."
  []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (presence-rt/reset-clock!)
  ;; Test-only determinism: the logical advance is the SOLE removal driver.
  ;; The BRIDGE deliberately leaves the wall clock armed — see `presence-host`
  ;; — so a suite that wants determinism disables it here, not there.
  (presence-rt/set-wall-clock! false)
  ;; An `rf/init!` guarded only by a catch would silently keep whatever a
  ;; sibling namespace left installed, and a Freehand root mounted over
  ;; someone else's substrate is a different experiment from the one a given
  ;; test claims to run. So: destroy first, always, and seat explicitly.
  (try (rf/destroy-adapter!) (catch :default _ nil))
  (reset! re/run-state {})
  nil)

(defn- seat!
  "Install `adapter-value` as THE process adapter and build the variant frame
  over it. Returns the adapter's canonical `:kind`, which every test pins — an
  arm that silently ran on the other substrate would prove the opposite of
  what it claims."
  [adapter-value]
  (rf/init! adapter-value)
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
  (substrate/current-adapter))

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
  "Dispatch into the variant frame and return with the Freehand DOM settled —
  the substrate's own synchronous commit, which is what a Story `:dispatch`
  step reaches through the settled-boundary ladder."
  [event]
  (substrate/flush-render! (fn [] (router/dispatch-sync! event {:frame variant-frame}))))

(defn- watch-verb-return!
  "Wrap the INSTALLED `:flush-presence!` verb — the shipped bridge, already
  registered by `presence-host/install!` — in a passthrough that calls
  `observe!` the instant the verb returns, and hands the verb's own return
  value straight back.

  An OBSERVER, not a second door: the step still travels `re/run!` →
  `run-loop!` → `presence/advance!` → this wrapper → the shipped bridge. It
  exists because the bridge's law is stated about its RETURN, and the run
  callback is a macrotask later — far enough away that React's own scheduler
  can settle the DOM on the bridge's behalf and hide a bridge that settled
  nothing."
  [observe!]
  (let [shipped (story-presence/presence-flush-fn)]
    (assert (some? shipped) "the shipped bridge must be installed before wrapping it")
    (story-presence/install-presence-flush!
      (fn [ms]
        (let [ret (shipped ms)]
          (observe!)
          ret)))))

(defn- skip! [why]
  (is true (str "a real Freehand mount needs a DOM host — " why)))

(defn- retained!
  "The state every behavioural arm starts from: `b` still in the DOM, reading
  `:unmounting`, its removal pending on the logical clock."
  [container k mounted]
  (is (some? (toast-el container "b"))
      "b is RETAINED in the DOM while exiting, not removed")
  (is (= "unmounting" (phase-of container "b"))
      "and its own (v/presence-phase) read reaches :unmounting")
  (is (= 1 (presence-rt/pending-count))
      "exactly one exit is retained on the logical clock")
  (k container mounted))

(defn- retain-via-subscription!
  "Mount the subscription-driven boundary, dispatch the dismissal, and hand
  `k` the retained state."
  [k]
  (let [container (host-node!)]
    (-> (act #(v/mount [toaster {}] container {:frame variant-frame}))
        (.then (fn [mounted]
                 (dispatch-and-settle! [:toasts/seed])
                 (is (some? (toast-el container "b")) "b mounted")
                 (dispatch-and-settle! [:toasts/dismiss])
                 (retained! container k mounted))))))

(defn- retain-via-props!
  "Mount the props-driven boundary with both keys, RE-MOUNT it with one — the
  idempotent-per-root re-render `v/mount` documents — and hand `k` the
  retained state."
  [k]
  (let [container (host-node!)]
    (-> (act #(v/mount [prop-toaster {:ids ["a" "b"]}] container {:frame variant-frame}))
        (.then (fn [_]
                 (is (some? (toast-el container "b")) "b mounted")
                 (act #(v/mount [prop-toaster {:ids ["a"]}] container {:frame variant-frame}))))
        (.then (fn [mounted]
                 (retained! container k mounted))))))

(defn- cleanup! [container mounted]
  (try (v/unmount! mounted) (catch :default _ nil))
  (.remove container)
  nil)

;; ---------------------------------------------------------------------------
;; The two arms, written once and driven under both seatings
;; ---------------------------------------------------------------------------

(defn- flush-presence-arm!
  "GREEN: the runner's step takes the retained child off the screen, and had
  already done so by the time the shipped verb returned."
  [retain! done]
  (retain!
    (fn [container mounted]
      (let [gone-at-verb-return (atom ::verb-never-reached)]
        (watch-verb-return! #(reset! gone-at-verb-return (nil? (toast-el container "b"))))
        (re/run! variant-frame "flushing"
                 {:name   "flushing"
                  :script [[:flush-presence]]}
                 (fn [state]
                   (is (= :pass (:status state))
                       "the run passed — the step was reached and did not refuse")
                   (is (true? @gone-at-verb-return)
                       "the SHIPPED bridge RETURNED with the Freehand DOM settled:
                        the retained child was already off the page at the instant
                        the host verb handed control back, not a scheduler tick
                        later")
                   (is (nil? (toast-el container "b"))
                       "the retained child LEFT THE DOM: the advance fired the
                        exit AND the commit boundary committed the unmount")
                   (is (some? (toast-el container "a"))
                       "and the present sibling is untouched by b's removal")
                   (is (zero? (presence-rt/pending-count))
                       "the exit fired exactly once, leaving nothing pending")
                   (cleanup! container mounted)
                   (done)))))))

(defn- no-step-control-arm!
  "RED CONTROL: the same script without the step leaves the child exactly
  where it was."
  [retain! done]
  (retain!
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
                 (done))))))

;; ===========================================================================
;; Seating 1 — a pure Freehand process
;; ===========================================================================

(deftest a-flush-presence-step-removes-the-retained-child-from-the-dom
  (testing "rf2-gzmg0 — the SHIPPED bridge, driven by the SHIPPED run loop:
            a `[:flush-presence]` script step advances the framework clock,
            fires the retained exit, and the DOM is committed before the verb
            returns. Read back off `document`, because a crossing that
            advanced the clock and committed nothing would pass every
            counter-based arm of this rung."
    (if-not (browser?)
      (skip! "the browser job runs the removal assertion")
      (async done
        (is (= :rf.adapter/freehand (seat! v/adapter))
            "this arm runs on a pure Freehand process")
        (flush-presence-arm! retain-via-subscription! done)))))

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
        (is (= :rf.adapter/freehand (seat! v/adapter))
            "this arm runs on a pure Freehand process")
        (no-step-control-arm! retain-via-subscription! done)))))

(deftest the-props-driven-fixture-behaves-the-same-on-a-freehand-process
  (testing "The PARITY control for the Reagent seating below: the identical
            props-driven fixture, under `v/adapter`. It is what makes the
            seated adapter the only variable between the two green arms — a
            Reagent red could otherwise be blamed on the re-mount fixture
            rather than on the boundary the bridge settles at."
    (if-not (browser?)
      (skip! "the browser job runs the removal assertion")
      (async done
        (is (= :rf.adapter/freehand (seat! v/adapter))
            "this arm runs on a pure Freehand process")
        (flush-presence-arm! retain-via-props! done)))))

;; ===========================================================================
;; Seating 2 — THE SHIPPED STORY TOPOLOGY (rf2-gzmg0, merged-PR audit #7037)
;; ===========================================================================

(deftest a-flush-presence-step-removes-the-child-under-the-shipped-story-adapter
  (testing "The SAME proof under the adapter Story actually ships. Every Story
            testbed seats `re-frame.adapter.reagent/adapter`; a Freehand
            variant then mounts inside that Reagent process. Reagent's
            `:flush-render!` is `(fn [f] (f) (reagent.core/flush))`, which
            drains Reagent's own queues and — with no Reagent component dirty,
            which a presence removal never makes one — never enters
            `react-dom/flushSync`. A bridge that settled through the PROCESS
            adapter therefore returns with the retained child still painted
            and the clock already reporting zero pending: green clock over
            stale DOM, the one failure this rung exists to exclude. This arm
            is what fails when the bridge reaches for the dispatched
            `flush-render!` instead of Freehand's own."
    (if-not (browser?)
      (skip! "the browser job runs the removal assertion")
      (async done
        (is (= :rf.adapter/reagent (seat! reagent-adapter/adapter))
            "this arm runs on the SHIPPED Story topology — Reagent seated,
             Freehand root mounted inside it")
        (flush-presence-arm! retain-via-props! done)))))

(deftest without-the-step-the-child-stays-under-the-shipped-story-adapter
  (testing "The negative control for the Reagent seating, so its green cannot
            be an artefact of the mixed-adapter mount."
    (if-not (browser?)
      (skip! "the browser job runs the retention assertion")
      (async done
        (is (= :rf.adapter/reagent (seat! reagent-adapter/adapter))
            "this arm runs on the SHIPPED Story topology")
        (no-step-control-arm! retain-via-props! done)))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-bridge-arms-the-seam
  (testing "Non-vacuity, and the claim the whole rung rests on: the bridge
            arms the `:flush-presence!` seam, and a `[:flush-presence]` step
            reaches THAT fn. A suite whose bridge never loaded would report
            `:cannot-run` above rather than a removal, so pin the install
            here where the failure names itself.

            `install!` is the same call the namespace makes at LOAD time (a
            bare `:require` is the whole integration); it is called
            explicitly because `teardown!` drops the hook between tests so
            this suite cannot leak a presence host into the consolidated
            bundle."
    (presence-host/install!)
    (is (some? (story-presence/presence-flush-fn))
        "the bridge armed the seam the run loop reads")
    (is (some? (late-bind/get-fn :flush-presence!))
        "under the documented hook key")))
