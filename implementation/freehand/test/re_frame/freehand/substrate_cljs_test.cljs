(ns re-frame.freehand.substrate-cljs-test
  "rf2-vo8fb — the Freehand adapter's HEADLESS conformance: the contract shape,
  the two-phase disposal ordering law, and the bounded `flush-render!`
  convergence driver.

  These are the claims that need no DOM. The adapter map's shape is a value; the
  disposal outcome policy is a pure decision over two failure slots; and
  `flush-render!` is `react-dom/flushSync`, which runs its callback and commits
  synchronously whether or not anything is mounted — so its branch logic is
  exercisable under `test:freehand` / `test:cljs` rather than only in a browser.

  The DOM half — init → mount → subscribe → dispatch → automatic re-render,
  `flush-render!` returning with the page settled, and disposal draining live
  roots — is `re-frame.freehand.substrate-dom-cljs-test`, and it is the half that
  proves the whole point of the adapter: on plain-atom that repaint never
  arrives, because its derived value is not `IWatchable`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.substrate :as fh-substrate]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.root :as root]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter fh-substrate/adapter})
  (fn [f]
    (root/reset-registry!)
    (try (f) (finally (root/reset-registry!) (cell/flush!)))))

(defn- ex-of [thunk]
  (try (thunk) nil (catch cljs.core/ExceptionInfo e e)))

(defn- error-id [e]
  (:rf.error/id (ex-data e)))

;; ===========================================================================
;; The adapter value — the closed contract plus the canonical discriminator
;; ===========================================================================

(deftest the-adapter-carries-the-contract-and-the-canonical-kind
  (testing "The value the door publishes as `v/adapter` is the substrate
            contract map: the nine map-shape fns, the optional tenth
            (`:flush-render!`, which this adapter OVERRIDES), and the
            canonical `:rf.adapter/freehand` discriminator every routed
            hook keys off."
    (is (map? v/adapter) "v/adapter is a map")
    (is (identical? fh-substrate/adapter v/adapter)
        "the door re-exports the one adapter value — not a copy")
    (is (= :rf.adapter/freehand (:kind v/adapter))
        "the canonical kind — routing is by this stable token, so an
         `assoc`ed copy still reaches this adapter's live hooks")
    (doseq [k [:make-state-container :read-container :replace-container!
               :subscribe-container :make-derived-value :render
               :render-to-string :register-context-provider :dispose-adapter!
               :flush-render!]]
      (is (fn? (get v/adapter k))
          (str "adapter contract fn " k " is present and fn-shaped")))
    (is (some? ((:register-context-provider v/adapter) :rf/default))
        "the provider slot answers a component value (the frame keyword lives
         in the Provider's value at render time, not in a build-time closure)")))

(deftest installing-the-adapter-makes-it-the-reported-substrate
  (testing "The fixture installs `v/adapter`, so the process reports it — and
            reports it by the canonical kind rather than as `:custom`, which is
            what a `:kind` outside the reserved `:rf.adapter/*` namespace would
            have produced."
    (is (= :rf.adapter/freehand (rf/current-adapter)))
    (is (identical? v/adapter (rf/current-adapter-spec)))))

(deftest the-adapter-overrides-the-inherited-spine-flush
  (testing "`:flush-render!` is NOT the spine's own verb. The inherited verb
            commits only work SCHEDULED inside its callback, and a ViewCell mark
            schedules nothing synchronously — so an adapter that shipped the
            inherited verb would return with cells pending. Non-vacuity for the
            settling claims below and in the DOM suite."
    (is (identical? fh-substrate/flush-render! (:flush-render! v/adapter))
        "the adapter's flush is the ViewCell-settling override")))

;; ===========================================================================
;; Disposal ordering — the dual-failure law
;; ===========================================================================
;;
;; Freehand roots are drained FIRST and the spine SECOND, and both phases are
;; attempted whatever either does. `dispose-outcome` is that ordering decision
;; extracted, so the law is checkable here rather than only through a browser
;; teardown that has to be made to fail twice.

(deftest a-clean-disposal-throws-nothing
  (is (= [:ok] (fh-substrate/dispose-outcome false nil false nil))
      "neither phase failed"))

(deftest a-spine-only-failure-is-thrown-as-itself
  (let [spine (js/Error. "spine")]
    (is (= [:throw spine] (fh-substrate/dispose-outcome false nil true spine))
        "with no drain failure the spine's own error is the rejection")))

(deftest a-root-drain-failure-stays-primary-and-carries-the-spine-failure
  (testing "The drain failure is what the caller sees — it is the phase whose
            job was to release the subscriptions — and the spine failure rides
            it as diagnostic evidence rather than replacing it or vanishing."
    (let [drain (js/Error. "drain")
          spine (js/Error. "spine")
          [outcome reason] (fh-substrate/dispose-outcome true drain true spine)]
      (is (= :throw outcome))
      (is (identical? drain reason) "the drain failure is UNCHANGED and primary")
      (is (identical? spine (.-rfFreehandAdapterCleanupError reason))
          "and the spine failure is retained on it"))))

(deftest presence-not-truthiness-decides-which-failure-is-real
  (testing "A cleanup can throw a legitimately FALSY value. A truthy test would
            discard it and report a clean disposal — the exact silent-success
            this policy is presence-decided to avoid. `false` and `nil` are real
            failures when the caller says they are PRESENT."
    (is (= [:throw false] (fh-substrate/dispose-outcome false nil true false))
        "a falsy spine-only failure is still thrown")
    (is (= [:throw nil] (fh-substrate/dispose-outcome false nil true nil))
        "including nil")
    (let [[outcome reason] (fh-substrate/dispose-outcome true false true nil)]
      (is (= :throw outcome))
      (is (false? reason)
          "a falsy DRAIN failure stays primary and is not replaced by the
           truthy-looking secondary — a primitive cannot carry the diagnostic
           property, so the secondary rides the console instead"))
    (is (= [:ok] (fh-substrate/dispose-outcome false false false false))
        "and ABSENT failures are absent however falsy the slots look")))

;; ===========================================================================
;; The root drain
;; ===========================================================================

(deftest draining-an-empty-registry-is-a-no-op
  (testing "Adapter disposal runs on every teardown, including one with nothing
            mounted. An empty registry drains zero roots and throws nothing, so
            a headless boot can install and destroy the adapter freely."
    (is (= 0 (root/drain-live-roots!)))
    (is (= #{} (root/live-root-ids)))))

;; ===========================================================================
;; flush-render! — the bounded convergence driver
;; ===========================================================================
;;
;; A dirty cell's host notification is deferred to a microtask, so a synchronous
;; forcing caller has to close the pending window itself. It then has to
;; CONVERGE, because the commit it provoked can re-dirty. And that convergence
;; has to be BOUNDED: the ambient guards each see one synchronous pass, so a
;; cell re-enrolled ACROSS passes is invisible to them and would spin this
;; synchronous call forever.

(defn- probe-cell
  "A ViewCell plus a listener the test controls, and the unsubscribe fn. Nothing
  is mounted: the registry does not care where a cell came from, and the
  convergence law is about the registry."
  [id listener]
  (let [c   (cell/cell id)
        off (cell/subscribe c listener)]
    [c off]))

(deftest a-non-quiescent-cascade-fails-loud-instead-of-spinning
  (testing "A listener that re-marks its own cell on every notification is an
            unstable notification cycle: each pass drains the registry, the
            notification re-enrols, and the registry is never quiescent. The
            driver spends its budget and raises
            `:rf.error/flush-convergence-exceeded` naming the pass budget and
            the residual pending count — rather than never returning, which is
            the one failure a synchronous caller cannot recover from."
    (let [c   (atom nil)
          off (atom nil)]
      (let [[cell* unsub] (probe-cell ::runaway
                                      (fn [] (cell/mark-dirty! @c ::re-dirty)))]
        (reset! c cell*)
        (reset! off unsub))
      (try
        (cell/mark-dirty! @c ::seed)
        (let [e (ex-of #(fh-substrate/flush-render!))]
          (is (some? e) "the flush rejected rather than returning or spinning")
          (is (= :rf.error/flush-convergence-exceeded (error-id e)))
          (is (= cell/flush-convergence-budget (:passes (ex-data e)))
              "the diagnostic names the exhausted budget")
          (is (pos? (:pending (ex-data e)))
              "and the residual pending count, so the runaway is locatable")
          (is (= 're-frame.freehand.substrate/flush-render! (:where (ex-data e)))
              "and the forcing site, so the diagnostic says who could not settle"))
        (finally
          (@off)
          (cell/flush!))))))

(deftest a-one-shot-commit-triggered-re-dirty-drains-and-returns-quiescent
  (testing "The ordinary multi-pass case, and the control for the bound above: a
            commit that re-dirties ONCE — a layout effect that dispatches — is
            drained by one further pass and the call returns with the registry
            quiescent. If the driver bailed at the budget for every cascade, or
            never re-drained at all, this would be red."
    (let [c     (atom nil)
          off   (atom nil)
          fired (atom 0)]
      (let [[cell* unsub] (probe-cell ::one-shot
                                      (fn []
                                        (when (= 1 (swap! fired inc))
                                          (cell/mark-dirty! @c ::re-dirty))))]
        (reset! c cell*)
        (reset! off unsub))
      (try
        (cell/mark-dirty! @c ::seed)
        (fh-substrate/flush-render!)
        (is (= 0 (cell/pending-count))
            "the registry is quiescent when the synchronous call returns")
        (is (= 2 @fired)
            "and it took exactly two notifications: the seed and the one-shot
             re-dirty the first commit produced")
        (finally
          (@off)
          (cell/flush!))))))

(deftest a-quiescent-registry-makes-the-flush-a-no-op
  (testing "Nothing pending means nothing to settle. The driver loops zero times
            and never touches a pass, so a `flush-render!` on a settled page
            costs a `flushSync` of an empty callback."
    (is (= 0 (cell/pending-count)) "precondition: quiescent")
    (fh-substrate/flush-render!)
    (is (= 0 (cell/pending-count)))))

(deftest a-throwing-thunk-propagates-and-publishes-nothing
  (testing "The thunk's exception is the caller's, unchanged. And because the
            pending-window close runs AFTER the thunk inside the one commit
            boundary, a thunk that threw leaves the window open rather than
            publishing a half-written render phase on its way out."
    (let [[c off] (probe-cell ::throwing (fn [] nil))]
      (try
        (cell/mark-dirty! c ::seed)
        (is (= 1 (cell/pending-count)))
        (let [e (ex-of #(fh-substrate/flush-render!
                         (fn [] (throw (ex-info "boom" {:rf.error/id ::boom})))))]
          (is (= ::boom (error-id e)) "the thunk's own error, unchanged"))
        (is (= 1 (cell/pending-count))
            "and the cell is still pending — nothing was published for it")
        (finally
          (off)
          (cell/flush!))))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-proof-is-not-vacuous
  (testing "Every claim above rests on the probe machinery really marking cells
            and the budget really being finite. Without this row a `mark-dirty!`
            that enrolled nothing would leave the convergence rows green for the
            wrong reason."
    (let [[c off] (probe-cell ::vacuity (fn [] nil))]
      (try
        (is (= 0 (cell/pending-count)) "the registry starts quiescent")
        (cell/mark-dirty! c ::seed)
        (is (= 1 (cell/pending-count)) "a mark really enrols")
        (cell/flush!)
        (is (= 0 (cell/pending-count)) "and a flush really drains")
        (finally (off) (cell/flush!))))
    (is (pos-int? cell/flush-convergence-budget) "the budget is finite")))
