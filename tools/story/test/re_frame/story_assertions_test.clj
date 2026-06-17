(ns re-frame.story-assertions-test
  "JVM tests for re-frame2-story Stage 5 (rf2-h8et) — `:rf.assert/*`
  vocabulary.

  Covers each of the seven canonical assertion semantics from
  /spec/007-Stories.md §Assertion vocabulary:

    1. :rf.assert/path-equals    — value at path matches
    2. :rf.assert/path-matches   — value at path validates against malli
    3. :rf.assert/sub-equals     — subscription returns expected
    4. :rf.assert/dispatched?    — event observed during play
    5. :rf.assert/state-is       — machine in given state
    6. :rf.assert/no-warnings    — no warning trace events captured
    7. :rf.assert/effect-emitted — fx-id emitted from a cascade

  Plus:
  - Record-don't-throw contract (`004-Assertions.md` §Record-don't-throw semantics).
  - `assertions-passing?` predicate.
  - The canonical seven register at boot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            ;; rf2-q651r — the three trace-bus assertions + the q651r
            ;; regression below project from the epoch tape (the SSOT).
            ;; Requiring the epoch artefact installs its late-bind hooks so
            ;; `rf/epoch-history` records a live tape under `clojure -M:test`
            ;; (the dep rides the shared `:test` alias). Without this the
            ;; facade degrades to `[]` and the regression has no tape to
            ;; read.
            [re-frame.epoch            :as epoch]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.subs             :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.loaders    :as loaders]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  ;; Clear per-frame assertion accumulators between tests.
  ;; rf2-q651r — clear the epoch tape + listeners between tests so the
  ;; tape-projected assertions read only their own freshly-captured tape.
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; THE SEVEN CANONICAL ASSERTIONS REGISTER AT BOOT
;; ===========================================================================

(deftest canonical-seven-registered
  (testing "all seven :rf.assert/* event handlers register at install-canonical-vocabulary!"
    (let [events (re-frame.registrar/registrations :event)]
      (is (contains? events :rf.assert/path-equals))
      (is (contains? events :rf.assert/path-matches))
      (is (contains? events :rf.assert/sub-equals))
      (is (contains? events :rf.assert/dispatched?))
      (is (contains? events :rf.assert/state-is))
      (is (contains? events :rf.assert/no-warnings))
      (is (contains? events :rf.assert/effect-emitted))))
  (testing ":rf.assert/schema-error is NOT a reg-event handler (rf2-5x1wt.21)
            — it is tape-evaluated, not dispatched into the frame"
    (let [events (re-frame.registrar/registrations :event)]
      (is (not (contains? events :rf.assert/schema-error))))))

(deftest canonical-assertion-ids-set-exported
  (testing "canonical-assertion-ids returns the seven dispatched handlers PLUS
            the tape-evaluated :rf.assert/schema-error (rf2-5x1wt.21)"
    (is (= 8 (count (story/canonical-assertion-ids))))
    (is (contains? (story/canonical-assertion-ids) :rf.assert/schema-error))
    (is (= assertions/canonical-assertion-ids
           (story/canonical-assertion-ids)))))

;; ===========================================================================
;; :rf.assert/path-equals
;; ===========================================================================

(deftest path-equals-pass
  (testing ":rf.assert/path-equals passes when value at path matches"
    (rf/reg-event :test/set-status
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :status] :authenticated)}))
    (story/reg-variant :story.auth/happy
      {:events [[:test/set-status]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:auth :status] :authenticated]]]})
    (let [r (async/deref-blocking (story/run-variant :story.auth/happy) 5000)]
      (is (= 1 (count (:assertions r))))
      (is (true? (-> r :assertions first :passed?)))
      (is (= :rf.assert/path-equals (-> r :assertions first :assertion))))
    (story/destroy-variant! :story.auth/happy)))

(deftest path-equals-fail
  (testing ":rf.assert/path-equals records failure on mismatch (no throw)"
    (story/reg-variant :story.auth/sad
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:auth :status] :authenticated]]]})
    (let [r (async/deref-blocking (story/run-variant :story.auth/sad) 5000)]
      (is (= 1 (count (:assertions r))))
      (is (false? (-> r :assertions first :passed?)))
      (is (= :authenticated (-> r :assertions first :expected)))
      (is (nil? (-> r :assertions first :actual))))
    (story/destroy-variant! :story.auth/sad)))

;; ===========================================================================
;; :rf.assert/path-matches
;; ===========================================================================

(deftest path-matches-pass
  (testing ":rf.assert/path-matches validates against malli"
    (rf/reg-event :test/set-count
      (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (story/reg-variant :story.malli/ok
      {:events [[:test/set-count]]
       :play-script [[:dispatch-sync [:rf.assert/path-matches [:n] :int]]]})
    (let [r (async/deref-blocking (story/run-variant :story.malli/ok) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.malli/ok)))

(deftest path-matches-fail
  (testing ":rf.assert/path-matches records failure with explanation on schema mismatch"
    (rf/reg-event :test/set-bad
      (fn [{:keys [db]} _] {:db (assoc db :n "not a number")}))
    (story/reg-variant :story.malli/bad
      {:events [[:test/set-bad]]
       :play-script [[:dispatch-sync [:rf.assert/path-matches [:n] :int]]]})
    (let [r (async/deref-blocking (story/run-variant :story.malli/bad) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.malli/bad)))

;; ===========================================================================
;; :rf.assert/sub-equals
;; ===========================================================================

(deftest sub-equals-pass
  (testing ":rf.assert/sub-equals passes when sub returns expected"
    (rf/reg-event :test/init
      (fn [{:keys [db]} _] {:db (assoc db :counter 7)}))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    (story/reg-variant :story.sub/v
      {:events [[:test/init]]
       :play-script [[:dispatch-sync [:rf.assert/sub-equals [:counter] 7]]]})
    (let [r (async/deref-blocking (story/run-variant :story.sub/v) 5000)]
      (is (true? (-> r :assertions first :passed?)))
      (is (= 7 (-> r :assertions first :actual))))
    (story/destroy-variant! :story.sub/v)))

(deftest sub-equals-fail
  (testing ":rf.assert/sub-equals records the mismatch"
    (rf/reg-event :test/init2
      (fn [{:keys [db]} _] {:db (assoc db :counter 3)}))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    (story/reg-variant :story.sub/bad
      {:events [[:test/init2]]
       :play-script [[:dispatch-sync [:rf.assert/sub-equals [:counter] 7]]]})
    (let [r (async/deref-blocking (story/run-variant :story.sub/bad) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.sub/bad)))

(deftest sub-equals-runtime-db-projection
  ;; rf2-pecaxy regression: a `:rf.assert/sub-equals` over a sub that
  ;; projects RUNTIME-DB state (the idiomatic machine-snapshot shape) must
  ;; resolve the live value — NOT nil. Pre-fix the play-runner handed
  ;; `compute-sub` the bare app-db `:db` cofx, so a `:runtime-db` sub read
  ;; app-db and returned nil. The fix passes the full frame-state value
  ;; `{:rf.db/app … :rf.db/runtime …}` so `compute-sub` resolves the
  ;; runtime-db partition the sub belongs to.
  (testing ":rf.assert/sub-equals resolves a runtime-db-projection sub (not nil)"
    ;; Seed a machine snapshot into the runtime-db partition (EP-0001).
    (rf/reg-event :test/seed-machine-sub
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :traffic-light]
                                  {:state :red})}))
    ;; A runtime-db sub projecting the snapshot's :state — the idiomatic
    ;; shape an app sub uses to read machine state reactively.
    (subs/reg-runtime-sub :traffic-light/state
      (fn [rt _] (get-in rt [:rf.runtime/machines :snapshots :traffic-light :state])))
    (story/reg-variant :story.sub/runtime
      {:events [[:test/seed-machine-sub]]
       :play-script [[:dispatch-sync [:rf.assert/sub-equals [:traffic-light/state] :red]]]})
    (let [r (async/deref-blocking (story/run-variant :story.sub/runtime) 5000)
          a (-> r :assertions first)]
      (is (true? (:passed? a))
          "the runtime-db-projection sub resolved its live value through sub-equals")
      (is (= :red (:actual a))
          "actual is the live runtime-db value, not nil (the pre-pecaxy bug)"))
    (story/destroy-variant! :story.sub/runtime)))

;; ===========================================================================
;; :rf.assert/dispatched?
;; ===========================================================================

(deftest dispatched-pass
  (testing ":rf.assert/dispatched? passes when an earlier event in :play-script fired"
    (rf/reg-event :test/click
      (fn [{:keys [db]} _] {:db (assoc db :clicked? true)}))
    (story/reg-variant :story.dispatched/v
      {:events []
       :play-script [[:dispatch-sync [:test/click]]
                [:dispatch-sync [:rf.assert/dispatched? [:test/click]]]]})
    (let [r       (async/deref-blocking (story/run-variant :story.dispatched/v) 5000)
          asserts (:assertions r)
          last-a  (last asserts)]
      (is (true? (:passed? last-a)) "the dispatched? assertion saw the test/click event"))
    (story/destroy-variant! :story.dispatched/v)))

(deftest dispatched-fail
  (testing ":rf.assert/dispatched? records a fail when no matching event was dispatched"
    (story/reg-variant :story.dispatched/no
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/dispatched? [:never/fired]]]]})
    (let [r (async/deref-blocking (story/run-variant :story.dispatched/no) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.dispatched/no)))

;; ===========================================================================
;; :rf.assert/state-is
;; ===========================================================================

(deftest state-is-pass
  (testing ":rf.assert/state-is passes when machine snapshot matches"
    ;; Seed a tiny machine snapshot manually into the runtime-db partition
    ;; (EP-0001 rf2-vzld77: machine snapshots are durable runtime-db state).
    (rf/reg-event :test/seed-machine
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :traffic-light]
                                  {:state :red})}))
    (story/reg-variant :story.machine/red
      {:events [[:test/seed-machine]]
       :play-script [[:dispatch-sync [:rf.assert/state-is :traffic-light :red]]]})
    (let [r (async/deref-blocking (story/run-variant :story.machine/red) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.machine/red)))

(deftest state-is-fail
  (testing ":rf.assert/state-is records the mismatch when state differs"
    (rf/reg-event :test/seed-machine2
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :traffic-light]
                                  {:state :green})}))
    (story/reg-variant :story.machine/mismatch
      {:events [[:test/seed-machine2]]
       :play-script [[:dispatch-sync [:rf.assert/state-is :traffic-light :red]]]})
    (let [r (async/deref-blocking (story/run-variant :story.machine/mismatch) 5000)]
      (is (false? (-> r :assertions first :passed?)))
      (is (= :red   (-> r :assertions first :expected)))
      (is (= :green (-> r :assertions first :actual))))
    (story/destroy-variant! :story.machine/mismatch)))

;; ===========================================================================
;; :rf.assert/no-warnings  (Stage 5 trace-bus accumulator)
;; ===========================================================================

(deftest no-warnings-pass-when-silent
  (testing ":rf.assert/no-warnings passes when no warning was emitted"
    (story/reg-variant :story.warn/silent
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/no-warnings]]]})
    (let [r (async/deref-blocking (story/run-variant :story.warn/silent) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.warn/silent)))

;; ===========================================================================
;; :rf.assert/effect-emitted  (Stage 5 trace-bus accumulator + fx-stub log)
;; ===========================================================================

(deftest effect-emitted-fail-when-no-fx
  (testing ":rf.assert/effect-emitted records a fail when no fx fired"
    (story/reg-variant :story.fx/none
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/effect-emitted :http]]]})
    (let [r (async/deref-blocking (story/run-variant :story.fx/none) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.fx/none)))

;; ===========================================================================
;; Record-don't-throw contract — `004-Assertions.md` §Record-don't-throw semantics
;; ===========================================================================

(deftest record-not-throw-on-failure
  (testing "a failing assertion never throws; the play sequence continues"
    (rf/reg-event :test/touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (story/reg-variant :story.contract/v
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:nope] :unexpected]]
                [:dispatch-sync [:test/touch]]
                [:dispatch-sync [:rf.assert/path-equals [:touched] true]]]})      ; pass
    (let [r (async/deref-blocking (story/run-variant :story.contract/v) 5000)]
      ;; Both assertions recorded — sequence did NOT halt on the first
      ;; failure.
      (is (= 2 (count (:assertions r))))
      (is (false? (-> r :assertions first :passed?)))
      (is (true?  (-> r :assertions second :passed?)))
      (is (true?  (-> r :app-db :touched))
          ":test/touch fired between the two assertions"))
    (story/destroy-variant! :story.contract/v)))

;; ===========================================================================
;; assertions-passing? — the cljs.test adapter predicate
;; ===========================================================================

(deftest assertions-passing-vacuously-true-on-empty
  (testing "an empty assertions list passes vacuously (/spec/007-Stories.md §Story-as-test duality)"
    (story/reg-variant :story.empty/v {:events [] :play-script []})
    (let [r (async/deref-blocking (story/run-variant :story.empty/v) 5000)]
      (is (true? (story/assertions-passing? r))
          "a variant with no :play-script still 'passes' for cljs.test integration")
      (is (empty? (:assertions r))))
    (story/destroy-variant! :story.empty/v)))

(deftest assertions-passing-true-on-all-pass
  (testing "passing? returns true when every assertion has :passed? true"
    (rf/reg-event :test/n (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (story/reg-variant :story.all-pass/v
      {:events [[:test/n]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 42]]
                [:dispatch-sync [:rf.assert/path-matches [:n] :int]]]})
    (let [r (async/deref-blocking (story/run-variant :story.all-pass/v) 5000)]
      (is (true? (story/assertions-passing? r)))
      ;; Also accepts the assertions vector directly:
      (is (true? (story/assertions-passing? (:assertions r)))))
    (story/destroy-variant! :story.all-pass/v)))

(deftest assertions-passing-false-on-any-fail
  (testing "passing? returns false when any assertion failed"
    (rf/reg-event :test/n2 (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (story/reg-variant :story.any-fail/v
      {:events [[:test/n2]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 999]]]})  ; fail
    (let [r (async/deref-blocking (story/run-variant :story.any-fail/v) 5000)]
      (is (false? (story/assertions-passing? r))))
    (story/destroy-variant! :story.any-fail/v)))

;; ===========================================================================
;; The assertion record carries the canonical fields
;; ===========================================================================

(deftest record-shape
  (testing "an assertion record carries :assertion :payload :passed? :elapsed-ms :reason"
    (rf/reg-event :test/init3 (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
    (story/reg-variant :story.shape/v
      {:events [[:test/init3]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:x] 1]]]})
    (let [r (async/deref-blocking (story/run-variant :story.shape/v) 5000)
          a (first (:assertions r))]
      (is (= :rf.assert/path-equals  (:assertion a)))
      (is (= [[:x] 1]                (:payload a)))
      (is (true?                     (:passed? a)))
      (is (number?                   (:elapsed-ms a)))
      (is (string?                   (:reason a))))
    (story/destroy-variant! :story.shape/v)))

;; ===========================================================================
;; read-assertions — public alias for the per-frame accumulator
;; ===========================================================================

(deftest read-assertions-public
  (testing "story/read-assertions returns the live accumulator"
    (rf/reg-event :test/q (fn [{:keys [db]} _] {:db (assoc db :q :ok)}))
    (story/reg-variant :story.read/v
      {:events [[:test/q]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:q] :ok]]]})
    (let [_ (async/deref-blocking (story/run-variant :story.read/v) 5000)
          a (story/read-assertions :story.read/v)]
      (is (= 1 (count a)))
      (is (true? (:passed? (first a)))))
    (story/destroy-variant! :story.read/v)))

;; ===========================================================================
;; assertion-event? — play-runner discriminator
;; ===========================================================================

(deftest assertion-event-discriminator
  (testing "assertion-event? recognises :rf.assert/* but not other namespaces"
    (is (true?  (assertions/assertion-event? [:rf.assert/path-equals [:x] 1])))
    (is (true?  (assertions/assertion-event? [:rf.assert/no-warnings])))
    (is (false? (assertions/assertion-event? [:auth/login])))
    (is (false? (assertions/assertion-event? [:rf.story/something])))
    (is (false? (assertions/assertion-event? nil)))
    (is (false? (assertions/assertion-event? [])))))

;; ===========================================================================
;; :rf.assert/schema-error — the EXPECTED schema-violation declaration
;; (rf2-5x1wt.21, spec/017 §Schema rule). Pure selector parsing — the
;; declared expectation's surface selector MUST mirror the projected
;; violation's selector so the multiset matcher pairs them.
;; ===========================================================================

(deftest schema-error-recognised-and-known
  (testing ":rf.assert/schema-error is recognised but is NOT one of the seven
            dispatched handlers"
    (is (assertions/assertion-id-known? :rf.assert/schema-error))
    (is (assertions/schema-error? [:rf.assert/schema-error {:where :event :event :x}]))
    (is (assertions/schema-error? [:rf.assert/schema-error]))
    (is (not (assertions/schema-error? [:rf.assert/path-equals [:k] 1])))))

(deftest schema-error-selector-mirrors-violation-selector
  (testing "the declared expectation's selector matches evidence/violation-selector
            key-for-key per surface (so the matcher pairs them)"
    ;; :event (+ optional :path)
    (is (= [:event :checkout/submit]
           (assertions/schema-error-selector {:where :event :event :checkout/submit})))
    (is (= [:event :checkout/submit [:cart]]
           (assertions/schema-error-selector {:where :event :event :checkout/submit
                                              :path [:cart]})))
    ;; :cofx / :fx-args
    (is (= [:cofx :load/session]
           (assertions/schema-error-selector {:where :cofx :cofx :load/session})))
    (is (= [:fx-args :http/get]
           (assertions/schema-error-selector {:where :fx-args :fx-args :http/get})))
    ;; :sub-return / :app-db / :machine-data
    (is (= [:sub-return :auth/state [:auth/state]]
           (assertions/schema-error-selector {:where :sub-return :sub-return :auth/state
                                              :query-v [:auth/state]})))
    (is (= [:app-db [:auth] [:auth :token]]
           (assertions/schema-error-selector {:where :app-db :registered-path [:auth]
                                              :path [:auth :token]})))
    (is (= [:machine-data :checkout/fsm :entry]
           (assertions/schema-error-selector {:where :machine-data :machine-id :checkout/fsm
                                              :phase :entry}))))
  (testing "a bare / where-less spec selects the [:any] wildcard"
    (is (= [:any] (assertions/schema-error-selector {})))
    (is (= [:any] (assertions/schema-error-selector {:event :x}))))
  (testing "an unrecognised surface keys by [:where failing-id] (open fallback)"
    (is (= [:custom/surface :some-id]
           (assertions/schema-error-selector {:where :custom/surface :failing-id :some-id})))))

(deftest schema-error-expectation-projection
  (testing "schema-error-expectation carries the atom, spec, and selector"
    (let [a   [:rf.assert/schema-error {:where :event :event :x}]
          exp (assertions/schema-error-expectation a)]
      (is (= a (:atom exp)))
      (is (= {:where :event :event :x} (:spec exp)))
      (is (= [:event :x] (:selector exp))))))

;; ===========================================================================
;; evaluate-no-warnings — the FAIL branch (rf2-bmpn2)
;;
;; Of the seven canonical evaluators, no-warnings' FAILING branch (warnings
;; present) was the only one untested at any layer: the JVM
;; `no-warnings-pass-when-silent` covers only the empty/pass path, and the
;; evidence/result projection tests exercise the :warnings SLOT but never
;; the evaluator's :count / :actual / reason projection. The evaluator is
;; now a pure fn over the tape-projected warning records (rf2-q651r), so we
;; reach the fail branch directly via the established var-quote seam.
;; ===========================================================================

(def ^:private evaluate-no-warnings @#'assertions/evaluate-no-warnings)

(deftest evaluate-no-warnings-fail-branch
  (testing "warnings present → :passed? false, with :count / :actual / reason"
    ;; Two projected warning records (the shape `evidence/warnings` yields:
    ;; `{:operation … :category … :epoch-id … :trace-id …}`).
    (let [warning-records [{:operation :rf.warning/slow-sub :category :perf
                            :epoch-id 1 :trace-id 10}
                           {:operation :rf.warning/deprecated :category :api
                            :epoch-id 2 :trace-id 20}]
          out (evaluate-no-warnings warning-records [])]
      (is (false? (:passed? out))
          "the empty? test inverts: warnings present → fail")
      (is (= 2 (:count out))
          ":count projects the number of warning records")
      (is (= [:rf.warning/slow-sub :rf.warning/deprecated] (:actual out))
          ":actual is the (mapv :operation warnings) projection — the ops only")
      (is (re-find #"2 warning" (:reason out))
          ":reason names the count"))))

(deftest evaluate-no-warnings-pass-branch-pure
  (testing "no warnings → :passed? true, :count 0, empty :actual (the pure pass arm)"
    (let [out (evaluate-no-warnings [] [])]
      (is (true? (:passed? out)))
      (is (= 0 (:count out)))
      (is (= [] (:actual out)))
      (is (re-find #"no warning" (:reason out))))))

;; ===========================================================================
;; Causal pure-fn gaps — causal-bounds + causal-effect-surface (rf2-e0rpy)
;;
;; These pure projection fns back the rf2-5x1wt.31 causal assertions and
;; had NO direct unit test — only indirect result_test coverage that never
;; reached three branches: causal-bounds :exactly, causal-bounds :min over
;; :caused, and causal-effect-surface :sub-over-:view precedence.
;; ===========================================================================

(deftest causal-bounds-exactly-shorthand
  (testing ":exactly pins BOTH bounds to n, regardless of the per-id default"
    (is (= {:min 2 :max 2}
           (assertions/causal-bounds :rf.assert/caused {:exactly 2}))
        ":exactly on :caused pins min=max=2 (overriding the {:min 1} default)")
    (is (= {:min 0 :max 0}
           (assertions/causal-bounds :rf.assert/no-cascade-rerender {:exactly 0}))
        ":exactly 0 on :no-cascade-rerender pins both to 0")
    (is (= {:min 3 :max 3}
           (assertions/causal-bounds :rf.assert/no-cascade-rerender {:exactly 3}))
        ":exactly overrides the no-cascade default {:min 0 :max 0}")))

(deftest causal-bounds-min-override-on-caused
  (testing ":min override on :rf.assert/caused — 'caused at least n times'"
    (is (= {:min 3}
           (assertions/causal-bounds :rf.assert/caused {:min 3}))
        ":min 3 overrides the default {:min 1}; :max stays absent (unbounded)")
    (is (= {:min 1}
           (assertions/causal-bounds :rf.assert/caused {}))
        "default for :caused is {:min 1} (the contrast — no override)")
    (is (= {:min 2 :max 5}
           (assertions/causal-bounds :rf.assert/caused {:min 2 :max 5}))
        "both :min and :max may override on :caused")))

(deftest causal-effect-surface-sub-over-view-precedence
  (testing ":sub takes precedence over :view when BOTH are present"
    (is (= [:sub :a]
           (assertions/causal-effect-surface {:sub :a :view :b}))
        "a spec naming both measures the sub recompute (sub wins)")
    (is (= [:sub :a]   (assertions/causal-effect-surface {:sub :a})))
    (is (= [:view :b]  (assertions/causal-effect-surface {:view :b})))
    (is (= [:any]      (assertions/causal-effect-surface {}))
        "neither :sub nor :view → the cause's total recompute+render count")))

;; ===========================================================================
;; rf2-q651r — SSOT regression: an in-script [:assert [:rf.assert/no-warnings]]
;; AGREES with the run-result :warnings slot (no atom/tape divergence).
;;
;; PRE-fix the three trace-bus assertions read the `trace-accumulators`
;; atom — a SECOND capture path fed by the play listener, which routed
;; `:op-type :error` events (e.g. :rf.error/no-such-handler) into the
;; warnings accumulator. So a play that dispatched an unregistered event
;; recorded a "warning" in the atom (the in-script [:no-warnings] FAILED)
;; while the run-result :warnings slot — projected from the tape, keyed on
;; `:op-type :warning` — stayed EMPTY (the slot said pass). The atom and the
;; slot DISAGREED. POST-fix both read the tape projection, so they AGREE.
;; ===========================================================================

(deftest no-warnings-agrees-with-warnings-slot-on-error-only-run
  (testing "an error-only run: the in-script [:no-warnings] verdict matches the
            tape-projected :warnings slot (the SSOT — no atom divergence)"
    ;; Dispatching an unregistered event emits `:op-type :error`
    ;; (:rf.error/no-such-handler), NOT `:op-type :warning`. The tape's
    ;; :warnings projection (and therefore the run-result slot) stays empty;
    ;; the in-script no-warnings assertion reads the SAME projection.
    (story/reg-variant :story.ssot/error-only
      {:events []
       :play-script [[:dispatch-sync [:no/such-handler]]
                     [:dispatch-sync [:rf.assert/no-warnings]]]})
    (let [r        (async/deref-blocking
                     (story/run-variant :story.ssot/error-only) 5000)
          no-warn  (->> (:assertions r)
                        (filter #(= :rf.assert/no-warnings (:assertion %)))
                        last)]
      (is (zero? (count (:warnings r)))
          "the run-result :warnings slot is empty — an :error op is not a :warning")
      (is (true? (:passed? no-warn))
          "the in-script [:no-warnings] PASSES — it reads the same tape projection")
      (is (= (count (:warnings r)) (:count no-warn))
          "the assertion :count AGREES with the run-result :warnings slot count
           — one SSOT, no atom/tape divergence"))
    (story/destroy-variant! :story.ssot/error-only)))

(deftest dispatched?-projects-from-tape-trigger-events
  (testing ":rf.assert/dispatched? reads the epoch-tape :trigger-event
            projection (the SSOT), matching both a literal vector and a
            bare keyword head — including a re-dispatched (nested) event"
    (rf/reg-event :ssot/outer (fn [_ _] {:fx [[:dispatch [:ssot/inner 42]]]}))
    (rf/reg-event :ssot/inner (fn [{:keys [db]} [_ n]] {:db (assoc db :inner n)}))
    (story/reg-variant :story.ssot/dispatched
      {:events []
       :play-script [[:dispatch-sync [:ssot/outer]]
                     [:dispatch-sync [:rf.assert/dispatched? [:ssot/inner 42]]]
                     [:dispatch-sync [:rf.assert/dispatched? :ssot/inner]]
                     [:dispatch-sync [:rf.assert/dispatched? [:never/fired]]]]})
    (let [r       (async/deref-blocking
                    (story/run-variant :story.ssot/dispatched) 5000)
          by-need (->> (:assertions r)
                       (filter #(= :rf.assert/dispatched? (:assertion %)))
                       (map (juxt :expected :passed?))
                       (into {}))]
      (is (true?  (get by-need [:ssot/inner 42]))
          "the re-dispatched event's trigger-event epoch is on the tape (literal match)")
      (is (true?  (get by-need :ssot/inner))
          "a bare keyword needle matches the trigger-event's head id")
      (is (false? (get by-need [:never/fired]))
          "an event never dispatched does not appear on the tape → fail"))
    (story/destroy-variant! :story.ssot/dispatched)))

;; ===========================================================================
;; rf2-ynjts.21 — `dispatched-events` projection: the PRIVACY filter +
;; the assertion-event exclusion (the two branches the behavioural
;; `dispatched?-projects-from-tape-trigger-events` test above does not reach).
;;
;; `dispatched-events` is the SSOT for `:rf.assert/dispatched?` and the
;; loaders' vector-form `:loaders-complete-when`. Its docstring (Spec 009
;; §Privacy) makes two correctness promises beyond "project the trigger
;; events in tape order":
;;
;;   1. PRIVACY: the `:trigger-event` of an epoch flagged
;;      `:rf.epoch/sensitive?` is DROPPED while Story's local-render egress
;;      profile redacts (`:rf.egress/local-redacted` — the default, EP-0015
;;      rf2-3t26eh), so a sensitive event vector never lands raw on an
;;      assertion record's `:actual` (which serialises into the test-mode
;;      pane, MCP `read-assertions`, and JSON-log egress). Under the
;;      trusted-local `:rf.egress/local-raw` profile, sensitive
;;      trigger-events pass through.
;;   2. ASSERTION-EVENT EXCLUSION: an `[:assert …]` checkpoint dispatches its
;;      wrapped `:rf.assert/*` atom, committing an epoch whose `:trigger-event`
;;      is that verdict — NOT behaviour-under-test. Those are excluded so a
;;      `[:rf.assert/dispatched? :rf.assert/path-equals]` can never falsely
;;      pass on a verdict the runner itself dispatched.
;;
;; Both are PURE projection branches over the tape, so the tests inject a
;; synthetic tape through the private `frame-tape` var (the established
;; `@#'` / `with-redefs` idiom in this file) — deterministic, host-free, no
;; live dispatch needed. The egress profile is restored in a `finally`
;; so it cannot poison a sibling test (the fixture does not reset it).
;; ===========================================================================

(defn- with-egress-profile
  "Run `thunk` with Story's local-render egress profile bound to `profile`,
  restoring the prior value afterwards (the assertions fixture does not
  reset it, so a `set-egress-profile!` must be unwound by hand)."
  [profile thunk]
  (let [prev @config/session-egress-profile]
    (config/set-egress-profile! profile)
    (try (thunk)
         (finally (config/set-egress-profile! prev)))))

(defn- with-tape
  "Run `thunk` with the private `frame-tape` projection redefed to return
  `tape` for any frame (`with-redefs-fn` takes the `#'`-var directly, so a
  private fn in another ns is redefable without importing it)."
  [tape thunk]
  (with-redefs-fn {#'assertions/frame-tape (constantly tape)} thunk))

(deftest dispatched-events-drops-sensitive-trigger-when-redacting
  (testing "an epoch flagged :rf.epoch/sensitive? has its :trigger-event
            DROPPED while the profile redacts (Spec 009 §Privacy) — a
            sensitive event vector must not reach an assertion record's
            :actual"
    ;; A two-epoch tape: one ordinary, one carrying a sensitive payload.
    (let [tape [{:trigger-event [:auth/login]}
                {:trigger-event [:auth/submit {:password "hunter2"}]
                 :rf.epoch/sensitive? true}]]
      (with-tape tape
        (fn []
          (with-egress-profile :rf.egress/local-redacted
            (fn []
              (let [events (assertions/dispatched-events :any-frame)]
                (is (= [[:auth/login]] events)
                    "the sensitive epoch's trigger-event is filtered out — only
                     the non-sensitive event projects")
                (is (not-any? #(= :auth/submit (first %)) events)
                    "the sensitive payload [:auth/submit {:password …}] never
                     appears in the projection")))))))))

(deftest dispatched-events-keeps-sensitive-trigger-under-local-raw
  (testing "under :rf.egress/local-raw the sensitive epoch's :trigger-event
            PASSES THROUGH — the operator opted in (Spec 009 §Privacy)"
    (let [tape [{:trigger-event [:auth/login]}
                {:trigger-event [:auth/submit {:password "hunter2"}]
                 :rf.epoch/sensitive? true}]]
      (with-tape tape
        (fn []
          (with-egress-profile :rf.egress/local-raw
            (fn []
              (let [events (assertions/dispatched-events :any-frame)]
                (is (= [[:auth/login] [:auth/submit {:password "hunter2"}]] events)
                    "both trigger-events project under the raw profile — the drop
                     is gated on the profile, not unconditional")))))))))

(deftest dispatched-events-excludes-assertion-trigger-events
  (testing "an :rf.assert/* trigger-event is EXCLUDED — a verdict the runner
            dispatched is not behaviour-under-test, so [:rf.assert/dispatched?
            <an-assertion-id>] can never falsely pass on it"
    ;; A tape mixing real behaviour with the assertion-checkpoint epochs the
    ;; runner commits when it dispatches each [:assert …] atom.
    (let [tape [{:trigger-event [:cart/add-item {:sku "A"}]}
                {:trigger-event [:rf.assert/path-equals [:cart] {}]}
                {:trigger-event [:cart/checkout]}
                {:trigger-event [:rf.assert/dispatched? [:cart/add-item]]}]]
      (with-tape tape
        (fn []
          (with-egress-profile :rf.egress/local-redacted
            (fn []
              (let [events (assertions/dispatched-events :any-frame)]
                (is (= [[:cart/add-item {:sku "A"}] [:cart/checkout]] events)
                    "only the two real events project; both :rf.assert/* verdict
                     trigger-events are dropped")
                (is (not-any? #(= "rf.assert" (namespace (first %))) events)
                    "no :rf.assert/* head survives the projection")))))))))

(deftest dispatched-events-empty-on-host-free-tape
  (testing "an empty tape (a production Story jar / host without the epoch
            artefact, where the late-bound facade degrades to []) projects
            no events — the host-free floor, not a throw"
    (with-tape []
      (fn []
        (is (= [] (assertions/dispatched-events :any-frame)))))))

(deftest effect-emitted-projects-from-tape-effects
  (testing ":rf.assert/effect-emitted reads the epoch-tape :effects projection
            (the SSOT) for a real (non-stubbed) user fx"
    (rf/reg-fx :ssot.fx/real {:platforms #{:client :server}} (fn [_ _] nil))
    (rf/reg-event :ssot/emit-real (fn [_ _] {:fx [[:ssot.fx/real {:url "x"}]]}))
    (story/reg-variant :story.ssot/effect
      {:events []
       :play-script [[:dispatch-sync [:ssot/emit-real]]
                     [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/real]]
                     [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/never]]]})
    (let [r       (async/deref-blocking
                    (story/run-variant :story.ssot/effect) 5000)
          by-need (->> (:assertions r)
                       (filter #(= :rf.assert/effect-emitted (:assertion %)))
                       (map (juxt :expected :passed?))
                       (into {}))]
      (is (some #(= :ssot.fx/real (:fx-id %)) (:effects r))
          "the user fx is on the run-result :effects slot (the tape projection)")
      (is (true?  (get by-need :ssot.fx/real))
          "effect-emitted sees the fx via the SAME tape :effects projection")
      (is (false? (get by-need :ssot.fx/never))
          "an fx never emitted → fail"))
    (story/destroy-variant! :story.ssot/effect)))

;; ===========================================================================
;; rf2-luzky — fold coverage: a STUBBED fx is answerable from the stub-call
;; log SSOT, not the removed `trace-accumulators` side-table / dropped
;; `tap-stub-event!` mirror.
;;
;; A stubbed fx lands on the epoch tape under its REWRITTEN stub id
;; (`:rf.story.fx-stub/<dec>+<fx>`), not its original id — so the tape
;; :effects projection alone can't answer `[:rf.assert/effect-emitted
;; <original-fx>]`. `emitted-fx` unions the tape effects with
;; `fx-stubs/observed-fx-ids` (the stub-call log, read via the
;; `:stub-observed-fx-ids` late-bind hook). This proves dropping the
;; `tap-stub-event!` dev-mirror lost no coverage: the original-fx fact is
;; still answerable from the canonical stub-call log.
;; ===========================================================================

(deftest effect-emitted-projects-stubbed-fx-from-stub-log
  (testing ":rf.assert/effect-emitted sees a force-fx-stub'd fx via the
            stub-call log SSOT (the original fx-id, not the rewritten stub id)"
    (rf/reg-fx :ssot.fx/http {:platforms #{:client :server}} (fn [_ _] nil))
    (rf/reg-event :ssot/login (fn [_ _] {:fx [[:ssot.fx/http {:url "/login"}]]}))
    (story/reg-variant :story.ssot/stubbed
      {:decorators  [[:rf.story/force-fx-stub :ssot.fx/http {:status :ok}]]
       :events      []
       :play-script [[:dispatch-sync [:ssot/login]]
                     [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/http]]
                     [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/never]]]})
    (let [r       (async/deref-blocking
                    (story/run-variant :story.ssot/stubbed) 5000)
          by-need (->> (:assertions r)
                       (filter #(= :rf.assert/effect-emitted (:assertion %)))
                       (map (juxt :expected :passed?))
                       (into {}))]
      (is (contains? (assertions/emitted-fx :story.ssot/stubbed) :ssot.fx/http)
          "emitted-fx answers the ORIGINAL fx-id from the stub-call log union")
      (is (true?  (get by-need :ssot.fx/http))
          "effect-emitted PASSES for the stubbed fx's original id")
      (is (false? (get by-need :ssot.fx/never))
          "an fx never emitted (stubbed or otherwise) → fail"))
    (story/destroy-variant! :story.ssot/stubbed)))
