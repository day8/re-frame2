(ns re-frame.frame-initial-events-cljs-test
  "EP-0027 — `:initial-events` frame construction engine. Pins the accepted
  EP-0027 §Specification end-to-end against the reference implementation:

    * the NORMALIZER + RUNNER — top-level `make-frame` / `reg-frame` with
      `:initial-events` runs the setup steps SYNCHRONOUSLY, in order, BEFORE the
      constructor returns (exactly the hand-written `dispatch-sync` loop, written
      as data); a map step's `:opts` (a deterministic clock) is honoured;
    * the STRICT shape — a BARE top-level event vector, a `:frame` in a step's
      `:opts`, an unknown step-map, an empty/non-vector step event each fail the
      RIGHT `:rf.error/*` discriminator BEFORE any step runs (no frame left
      registered);
    * RETIREMENT — a supplied `:on-create` / `:initial-db` fails loud
      (`:rf.error/on-create-retired` / `:rf.error/initial-db-retired`);
    * RESET — `reset-frame!` re-dispatches the recorded `:initial-events`
      (durable frame config, best-effort, no snapshot);
    * RE-REGISTRATION — an idempotent re-`reg-frame` RE-RECORDS but does NOT
      REPLAY the setup (durable app-db preserved);
    * TEARDOWN — a setup step that THROWS at runtime tears down the partial frame
      (no live half-frame is left) and the error names the `:step-index`;
    * the HANDLER-TIME guard — constructing a frame inside an event handler fails
      `:rf.error/frame-construction-in-handler`.

  Each fail-loud assertion checks the `:rf.error/id` discriminator, NEVER the
  message bytes (Spec 009 §The thrown-error shape rule 3).

  `:rf/set-db` (the framework-standard app-db seed event) lands in a PARALLEL
  bead (events / image registry). To stay decoupled from that merge order, this
  ns registers a LOCAL stand-in seed event `:test/set-db` with the same
  `{:db new-db}` shape and uses it as the construction db-seed step; the contract
  under test is the `:initial-events` engine, not `:rf/set-db` itself.

  `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core         :as rf]
            [re-frame.frame        :as frame]
            [re-frame.live-frame   :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Fixture — reset the runtime (registrar, frames, trace listeners) and install
;; the plain-atom adapter so the constructed frames are runnable.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- err-data
  "The full `ex-data` of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- reg-test-events!
  "Register the local stand-in seed event + the small setup events the cases
  dispatch. `:test/set-db` mirrors `:rf/set-db` ({:db new-db}); the others write
  incrementally so a multi-step scenario reads as a script."
  []
  (rf/reg-event :test/set-db (fn [_ [_ new-db]] {:db new-db}))
  (rf/reg-event :test/inc    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/reg-event :test/add    (fn [{:keys [db]} [_ k v]] {:db (assoc db k v)}))
  (rf/reg-event :test/stamp-time
    ;; Reads the deterministic clock the step's `:opts {:rf.cofx {:rf/time-ms …}}`
    ;; supplies, proving the ordinary dispatch-sync opt surface is honoured. The
    ;; recordable `:rf/time-ms` is delivered FLAT into the coeffect map ONLY on
    ;; declaration (EP-0017 §5 declared-only delivery).
    {:rf.cofx/requires [:rf/time-ms]}
    (fn [{:keys [db rf/time-ms]} _]
      {:db (assoc db :stamped-at time-ms)}))
  (rf/reg-event :test/boom
    ;; A HANDLER-body throw: re-frame's dispatch-sync TRACES it as
    ;; :rf.error/handler-exception and RECOVERS (does not re-raise), so a setup
    ;; step that hits this is the EP's "traces-and-recovers" case — the frame is
    ;; left alive in the resulting state, exactly as after the manual loop.
    (fn [_ _] (throw (ex-info "setup blew up" {:kind :boom}))))
  (rf/reg-event :test/needs-missing-cofx
    ;; Requires a cofx that is NOT registered: cofx resolution throws OUT of
    ;; dispatch-sync (:rf.error/unregistered-cofx, raised through throw-error!),
    ;; so a setup step that hits this is the EP's "throws at runtime" case — the
    ;; partial frame is torn down and the step is named. (The EP's canonical
    ;; trigger is a `[:rf/set-db x]` bad-arg, which likewise throws through
    ;; throw-error!; that event lands in a PARALLEL bead, so this ns exercises
    ;; the SAME throw-out-of-dispatch-sync teardown path with a framework-stable
    ;; trigger.)
    {:rf.cofx/requires [:test/unregistered-cofx]}
    (fn [{:keys [db]} _] {:db (assoc db :ran true)})))

;; ===========================================================================
;; 1. The normalizer + runner — synchronous, in order, before return
;; ===========================================================================

(deftest reg-frame-initial-events-runs-sync-in-order-before-return
  (testing "reg-frame :initial-events dispatches each step synchronously, in
            order, draining each before the next — app-db is fully settled by
            the time reg-frame returns"
    (reg-test-events!)
    (rf/reg-frame :boot/seq
      {:initial-events [[:test/set-db {:n 0 :log []}]
                        [:test/inc]
                        [:test/inc]
                        [:test/add :marker :done]]})
    (let [db (rf/app-db-value :boot/seq)]
      (is (= 2 (:n db)) "the two :test/inc steps ran in order after the seed")
      (is (= :done (:marker db)) "the trailing :test/add step ran last")
      (is (= [] (:log db)) "the seed step replaced app-db wholesale"))))

(deftest make-frame-initial-events-runs-sync-before-return
  (testing "top-level make-frame :initial-events settles synchronously — an
            immediate app-db read sees the constructed state"
    (reg-test-events!)
    (lf/make-frame {:id :mk/main
                    :initial-events [[:test/set-db {:n 5}]
                                     [:test/inc]]})
    (is (= 6 (:n (rf/app-db-value :mk/main)))
        "make-frame's :initial-events ran before the constructor returned")))

(deftest initial-events-map-step-opts-honoured
  (testing "a map step {:event … :opts {:rf.cofx {:rf/time-ms …}}} passes the
            ordinary dispatch-sync opts — the deterministic clock is observable"
    (reg-test-events!)
    (rf/reg-frame :clock/main
      {:initial-events [[:test/set-db {}]
                        {:event [:test/stamp-time]
                         :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}]})
    (is (= 1781078400123 (:stamped-at (rf/app-db-value :clock/main)))
        "the map step's :rf.cofx clock reached the handler via dispatch-sync opts")))

(deftest initial-events-absent-or-empty-means-no-setup
  (testing "omitting :initial-events and supplying [] both mean no setup — the
            frame is created with an empty app-db"
    (reg-test-events!)
    (rf/reg-frame :empty/a {:doc "no key"})
    (rf/reg-frame :empty/b {:initial-events []})
    (is (= {} (rf/app-db-value :empty/a)) "no :initial-events key ⇒ app-db {}")
    (is (= {} (rf/app-db-value :empty/b)) "[] :initial-events ⇒ app-db {}")))

;; ===========================================================================
;; 2. Provenance — setup dispatches carry :source :rf.frame/init + :step-index
;; ===========================================================================

(deftest initial-events-carry-construction-provenance
  (testing "each setup dispatch carries :source :rf.frame/init (top-level on the
            :rf.event/dispatched trace), distinguishing frame-init events from
            ordinary runtime events"
    (reg-test-events!)
    (let [dispatched (atom [])]
      (rf/register-listener! :trace ::prov
        (fn [ev]
          (when (= :rf.event/dispatched (:operation ev))
            (swap! dispatched conj ev))))
      (rf/reg-frame :prov/main
        {:initial-events [[:test/set-db {:n 0}]
                          [:test/inc]]})
      (rf/unregister-listener! :trace ::prov)
      (let [sources (->> @dispatched (map :source) (filter #{:rf.frame/init}))]
        (is (= 2 (count sources))
            "both setup dispatches carried :source :rf.frame/init"))
      ;; A subsequent RUNTIME dispatch is NOT frame-init — proving the tag
      ;; discriminates construction from runtime.
      (reset! dispatched [])
      (rf/register-listener! :trace ::prov2
        (fn [ev]
          (when (= :rf.event/dispatched (:operation ev))
            (swap! dispatched conj ev))))
      (rf/dispatch-sync [:test/inc] {:frame :prov/main :source :test})
      (rf/unregister-listener! :trace ::prov2)
      (is (not= :rf.frame/init (:source (first @dispatched)))
          "an ordinary runtime dispatch is NOT tagged :source :rf.frame/init"))))

;; ===========================================================================
;; 3. Strict-shape preflight — each bad shape fails the right id, no frame left
; ===========================================================================

(deftest bare-top-level-event-vector-is-invalid
  (testing "a bare event vector at the TOP LEVEL fails :rf.error/initial-events-bare-event"
    (reg-test-events!)
    (is (= :rf.error/initial-events-bare-event
           (err-id #(rf/reg-frame :bad/bare {:initial-events [:test/set-db {:n 0}]})))
        "[:test/set-db {…}] at top level is rejected (wrap as [[…]])")
    (is (nil? (frame/frame :bad/bare))
        "no frame is left registered when preflight validation throws")))

(deftest frame-in-step-opts-is-rejected
  (testing "a :frame in a map step's :opts fails :rf.error/initial-events-bad-opts"
    (reg-test-events!)
    (is (= :rf.error/initial-events-bad-opts
           (err-id #(rf/reg-frame :bad/frame-opt
                      {:initial-events [{:event [:test/set-db {}]
                                         :opts  {:frame :somewhere-else}}]})))
        ":frame is forced to the constructed frame and may not be supplied")
    (is (nil? (frame/frame :bad/frame-opt)) "no frame left registered")))

(deftest non-map-step-opts-is-rejected
  (testing "a non-map :opts fails :rf.error/initial-events-bad-opts"
    (reg-test-events!)
    (is (= :rf.error/initial-events-bad-opts
           (err-id #(rf/reg-frame :bad/opts-shape
                      {:initial-events [{:event [:test/set-db {}] :opts :nope}]}))))))

(deftest unknown-step-shape-is-rejected
  (testing "a step that is neither event vector nor map fails :rf.error/initial-events-bad-step"
    (reg-test-events!)
    (is (= :rf.error/initial-events-bad-step
           (err-id #(rf/reg-frame :bad/step {:initial-events [42]})))
        "a number step is rejected")
    (is (= :rf.error/initial-events-bad-step
           (err-id #(rf/reg-frame :bad/step2 {:initial-events :not-a-vector})))
        "a non-vector top-level value is rejected as bad-step")
    (is (nil? (frame/frame :bad/step)) "no frame left registered")))

(deftest empty-step-event-is-rejected
  (testing "an empty step vector / a map step with missing-or-empty :event fails
            :rf.error/initial-events-bad-event"
    (reg-test-events!)
    (is (= :rf.error/initial-events-bad-event
           (err-id #(rf/reg-frame :bad/empty {:initial-events [[]]})))
        "an empty event vector is not a valid step")
    (is (= :rf.error/initial-events-bad-event
           (err-id #(rf/reg-frame :bad/no-event
                      {:initial-events [{:opts {:rf.cofx {}}}]})))
        "a map step with no :event is rejected")
    (is (= :rf.error/initial-events-bad-event
           (err-id #(rf/reg-frame :bad/event-kw
                      {:initial-events [{:event :not-a-vector}]})))
        "a map step whose :event is not a vector is rejected")))

;; ===========================================================================
;; 4. Retirement — :on-create / :initial-db fail loud
;; ===========================================================================

(deftest on-create-is-retired
  (testing ":on-create supplied to construction fails :rf.error/on-create-retired"
    (reg-test-events!)
    (is (= :rf.error/on-create-retired
           (err-id #(rf/reg-frame :ret/oc {:on-create [:test/inc]})))
        "reg-frame rejects :on-create")
    (is (= :rf.error/on-create-retired
           (err-id #(lf/make-frame {:id :ret/oc2 :on-create [:test/inc]})))
        "make-frame rejects :on-create (it flows through to reg-frame's guard)")
    (is (nil? (frame/frame :ret/oc)) "no frame left registered")))

(deftest initial-db-is-retired
  (testing ":initial-db supplied to construction fails :rf.error/initial-db-retired"
    (reg-test-events!)
    (is (= :rf.error/initial-db-retired
           (err-id #(rf/reg-frame :ret/idb {:initial-db {:n 0}})))
        "reg-frame rejects :initial-db")
    (is (= :rf.error/initial-db-retired
           (err-id #(lf/make-frame {:id :ret/idb2 :initial-db {:n 0}})))
        "make-frame rejects :initial-db (no longer an image-selection key)")
    (is (nil? (frame/frame :ret/idb)) "no frame left registered")))

;; ===========================================================================
;; 5. Reset — reset-frame! re-dispatches the recorded :initial-events
;; ===========================================================================

(deftest reset-frame-replays-recorded-initial-events
  (testing "reset-frame! re-dispatches the recorded :initial-events through the
            CURRENT handlers (best-effort; durable config replayed)"
    (reg-test-events!)
    (rf/reg-frame :reset/main
      {:initial-events [[:test/set-db {:n 0}]
                        [:test/inc]]})
    (is (= 1 (:n (rf/app-db-value :reset/main))) "construction settled to n=1")
    ;; Mutate away from the constructed state.
    (rf/dispatch-sync [:test/inc] {:frame :reset/main})
    (rf/dispatch-sync [:test/inc] {:frame :reset/main})
    (is (= 3 (:n (rf/app-db-value :reset/main))) "runtime moved it to n=3")
    ;; Reset re-runs the recorded setup: seed {:n 0} then one :test/inc ⇒ n=1.
    (frame/reset-frame! :reset/main)
    (is (= 1 (:n (rf/app-db-value :reset/main)))
        "reset-frame! replayed the recorded :initial-events, back to n=1")))

;; ===========================================================================
;; 6. Re-registration — re-records but does NOT replay (durable state preserved)
;; ===========================================================================

(deftest re-registration-re-records-but-does-not-replay
  (testing "an idempotent re-reg-frame under the same id RE-RECORDS the new
            :initial-events but does NOT replay it — durable app-db is preserved"
    (reg-test-events!)
    (rf/reg-frame :remount/main
      {:initial-events [[:test/set-db {:n 0}]
                        [:test/inc]]})
    (is (= 1 (:n (rf/app-db-value :remount/main))) "first creation ran setup")
    ;; Move durable state.
    (rf/dispatch-sync [:test/inc] {:frame :remount/main})
    (is (= 2 (:n (rf/app-db-value :remount/main))) "runtime moved to n=2")
    ;; Re-register with a DIFFERENT :initial-events.
    (rf/reg-frame :remount/main
      {:initial-events [[:test/set-db {:n 999}]]})
    (is (= 2 (:n (rf/app-db-value :remount/main)))
        "re-registration did NOT replay the new setup — durable app-db preserved")
    ;; The recording IS replaced: a subsequent reset replays the NEW setup.
    (frame/reset-frame! :remount/main)
    (is (= 999 (:n (rf/app-db-value :remount/main)))
        "reset-frame! after re-reg replays the RE-RECORDED setup (n=999)")))

;; ===========================================================================
;; 7. Teardown — a throwing setup step destroys the partial frame, names the step
;; ===========================================================================

(deftest throwing-setup-step-tears-down-partial-frame
  (testing "a setup step that THROWS at runtime (out of dispatch-sync) tears down
            the partially-created frame (no live half-frame) and the error names
            the :step-index + :event"
    (reg-test-events!)
    (let [data (err-data
                 #(rf/reg-frame :teardown/main
                    {:initial-events [[:test/set-db {:n 0}]
                                      [:test/needs-missing-cofx]
                                      [:test/inc]]}))]
      (is (= :rf.error/initial-events-step-failed (:rf.error/id data))
          "the throwing step raises :rf.error/initial-events-step-failed")
      (is (= 1 (:step-index data)) "the error names the failing step's 0-based index")
      (is (= [:test/needs-missing-cofx] (:event data)) "the error names the failing event")
      (is (nil? (frame/frame :teardown/main))
          "the partially-created frame was torn down — no live half-frame is left"))))

(deftest trace-and-recover-setup-step-leaves-frame-alive
  (testing "a setup step whose failure dispatch-sync TRACES-and-recovers (a
            handler-body throw) does NOT tear down — the frame is left alive in
            the resulting state, exactly as after the manual loop"
    (reg-test-events!)
    ;; The :test/boom handler throws, but dispatch-sync traces it as
    ;; :rf.error/handler-exception and recovers (no re-raise). Construction
    ;; completes; the seed before it survives, the step after it still runs.
    (rf/reg-frame :recover/main
      {:initial-events [[:test/set-db {:n 0}]
                        [:test/boom]
                        [:test/inc]]})
    (is (some? (frame/frame :recover/main))
        "the frame is LEFT ALIVE — a traced-and-recovered step is not a teardown")
    (is (= 1 (:n (rf/app-db-value :recover/main)))
        "the seed + the trailing :test/inc ran; the recovered :test/boom left no db change")))

;; ===========================================================================
;; 8. The handler-time construction guard
;; ===========================================================================

(deftest frame-construction-in-handler-fails-loud
  (testing "constructing a frame INSIDE an event handler fails
            :rf.error/frame-construction-in-handler"
    (reg-test-events!)
    (let [caught (atom nil)]
      ;; A handler that tries to reg-frame a child mid-cascade. The construction
      ;; throw propagates out of the handler; capture it inside the handler so we
      ;; can assert its id without depending on how dispatch-sync surfaces it.
      (rf/reg-event :handler/makes-frame
        (fn [{:keys [db]} _]
          (reset! caught
                  (err-id #(rf/reg-frame :child/in-handler
                             {:initial-events [[:test/set-db {:n 0}]]})))
          {:db db}))
      (rf/reg-frame :parent/main {:initial-events [[:test/set-db {}]]})
      (rf/dispatch-sync [:handler/makes-frame] {:frame :parent/main})
      (is (= :rf.error/frame-construction-in-handler @caught)
          "a reg-frame inside a handler fails loud")
      (is (nil? (frame/frame :child/in-handler))
          "no half-registered child frame is left behind"))))
