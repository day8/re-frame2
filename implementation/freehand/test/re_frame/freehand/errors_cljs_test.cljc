(ns re-frame.freehand.errors-cljs-test
  "FH-ERROR-001 (the abandoned-candidate half), FH-ERROR-002, FH-ERROR-003 —
  the error-boundary LAW, driven directly on both hosts exactly as
  `shell-cljs-test` drives the atomic shell.

  The boundary is host-agnostic: the JVM structural host and the browser
  class boundary both drive this same state machine, so its laws — capture,
  the once-per-generation safe intent, reset, the two failure
  representations, and a failed candidate that publishes nothing — are proven
  once here and hold on both hosts. The React containment itself (a class
  boundary in a real DOM) is the browser-correctness suite's; this proves the
  law the class boundary drives."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.set :as set]
            [re-frame.error-emit :as error-emit]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.errors :as eb]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Seams — count what each promotion fans out
;; ---------------------------------------------------------------------------

(defn- with-intent-and-egress-counts
  "Run `thunk` with the safe-intent dispatch captured and an always-on error
  listener registered, and return `{:intents [event…] :egress [record…]}` —
  the two channels a promotion fans out. The safe intent rides
  `router/dispatch!` (the boundary appends the summary and dispatches into
  its frame); the private egress rides the always-on error-emit listener
  registry."
  [thunk]
  (let [intents (atom [])
        egress  (atom [])]
    (error-emit/register-error-listener! ::recorder (fn [r] (swap! egress conj r)))
    (with-redefs [router/dispatch! (fn ([e] (swap! intents conj e))
                                     ([e _opts] (swap! intents conj e)))]
      (thunk))
    (error-emit/unregister-error-listener! ::recorder)
    {:intents @intents :egress @egress}))

(defn- boom [] (ex-info "a child render threw" {:secret "must-not-leak"}))

;; ===========================================================================
;; FH-ERROR-001 (abandoned-candidate half) — a failed candidate publishes
;; NOTHING: the atomic shell bundle (F2a) is absent.
;; ===========================================================================

(def error-001 (conf/fixture :FH-ERROR-001))

(deftest fh-error-001-a-failed-candidate-publishes-nothing
  (testing "Per FH-ERROR-001: a render body caught by the boundary's
            containment is DROPPED — its candidate is never committed, so the
            cell is still `:new`, owns no dependencies, and carries no
            evidence. The bundle F2a would publish is absent, which is the
            law that makes speculative rendering safe."
    (let [{:keys [lifecycle dependency-count evidence]} (:abandoned error-001)
          c (cell/cell :app/child)
          b (eb/boundary eb/boundary-view-id :rk)
          outcome (eb/contain b
                              (fn [] (cell/with-capture (cell/candidate c nil)
                                       (fn [] (throw (boom)))))
                              {:phase :render :view-id :app/child :frame-id nil})]
      (is (= :contained (:status outcome))
          "the throw was contained, not propagated")
      (is (= lifecycle (cell/lifecycle c))
          "the cell never became connected on a candidate that threw")
      (is (= dependency-count (count (cell/dependencies c)))
          "no dependency was published")
      (is (= evidence (cell/evidence c))
          "and no evidence bundle was published"))))

;; ===========================================================================
;; FH-ERROR-002 — reset, and the once-per-generation safe intent.
;; ===========================================================================

(def error-002 (conf/fixture :FH-ERROR-002))

(defn- context-002 []
  (let [{:keys [view-id phase frame-id]} error-002]
    {:view-id view-id :phase phase :frame-id frame-id}))

(defn- drive-failures!
  "Simulate `n` repeated failures within ONE generation: capture the throw
  and promote after each, exactly as a StrictMode / HMR host re-rendering a
  failed boundary would. The once-per-generation gate is what must collapse
  these to one fired intent."
  [b n]
  (dotimes [_ n]
    (eb/capture! b (assoc (context-002) :exception (boom)))
    (eb/promote! b {:on-error (:on-error error-002)
                    :frame-id (:frame-id error-002)
                    :time     0})))

(deftest fh-error-002-the-safe-intent-fires-exactly-once-per-generation
  (testing "Per FH-ERROR-002: under REPEATED failures within one generation
            the safe `:on-error` intent and the private egress record fire
            EXACTLY ONCE — an exact count, not `at least one`. A repeated
            capture while already failed is a no-op, and the once-per-
            generation gate yields the report only on the first promotion."
    (let [{:keys [repeated-failures intents-per-generation egress-records-per-generation]} error-002
          b (eb/boundary eb/boundary-view-id (:reset-key-1 error-002))
          {:keys [intents egress]}
          (with-intent-and-egress-counts #(drive-failures! b repeated-failures))]
      (is (= (:generation (:after-first-capture error-002)) (eb/generation b))
          "all repeated failures stayed in one generation")
      (is (= intents-per-generation (count intents))
          "exactly one safe intent for the whole generation")
      (is (= egress-records-per-generation (count egress))
          "exactly one private egress record for the whole generation")
      (is (= (conj (vec (:on-error error-002))
                   (:summary (eb/failure b)))
             (first intents))
          "and the intent is the author's prefix with the safe summary appended"))))

(deftest fh-error-002-a-reset-clears-the-failure-and-a-new-generation-reports-again
  (testing "Per FH-ERROR-002: a changed :reset-key by rf= clears the captured
            failure and re-mounts the child (`:clear`); a same key does not.
            A new failure generation, after the reset and another throw,
            reports EXACTLY ONCE again — so exactly two intents bracket one
            reset, never one and never three."
    (let [{:keys [repeated-failures total-intents-after-one-reset total-egress-after-one-reset]} error-002
          b (eb/boundary eb/boundary-view-id (:reset-key-1 error-002))
          {:keys [intents egress]}
          (with-intent-and-egress-counts
            (fn []
              ;; Generation 1: repeated failures → one report.
              (drive-failures! b repeated-failures)
              (is (= (:status (:after-first-capture error-002)) (eb/status b)))
              ;; A same-key reconcile does not reset.
              (is (= (:did-reset? (:after-same-key error-002))
                     (eb/reset-to! b (:reset-key-1 error-002))))
              ;; A changed key clears the failure and re-mounts.
              (is (= (:did-reset? (:after-reset error-002))
                     (eb/reset-to! b (:reset-key-2 error-002))))
              (is (= (:status (:after-reset error-002)) (eb/status b)))
              ;; Generation 2: repeated failures again → one more report.
              (drive-failures! b repeated-failures)
              (is (= (:generation (:after-recapture error-002)) (eb/generation b)))))]
      (is (= total-intents-after-one-reset (count intents))
          "exactly two intents across the two generations bracketing one reset")
      (is (= total-egress-after-one-reset (count egress))
          "and exactly two private egress records"))))

;; ===========================================================================
;; FH-ERROR-003 — the safe summary and the private egress field sets.
;; ===========================================================================

(def error-003 (conf/fixture :FH-ERROR-003))

(defn- deep-keys
  "Every map key anywhere in `x` — so a forbidden slot cannot hide nested."
  [x]
  (cond
    (map? x)  (into (set (keys x)) (mapcat deep-keys (vals x)))
    (coll? x) (into #{} (mapcat deep-keys x))
    :else     #{}))

(defn- capture-one!
  "Capture one failure carrying `extra` evidence flags and return the
  boundary's failure record."
  [extra]
  (let [{:keys [view-id phase frame-id]} error-003
        b (eb/boundary (:boundary-view-id error-003) :rk)]
    (eb/capture! b (merge {:exception       (boom)
                           :phase           phase
                           :view-id         view-id
                           :frame-id        frame-id
                           :component-stack "at broken-page\n at workspace-page"}
                          extra))))

(deftest fh-error-003-the-safe-summary-is-a-closed-bounded-envelope
  (testing "Per FH-ERROR-003: the safe public summary an application
            receives has EXACTLY the closed field set — a stable diagnostic
            id, the failing and boundary view ids, the phase, the frame id, a
            fingerprint, and D020 evidence — and nothing host-shaped."
    (let [summary (:summary (capture-one! {:complete? true}))]
      (is (= (:safe-summary-keys error-003) (set (keys summary))))
      (is (= (:diagnostic-id error-003) (:diagnostic-id summary)))
      (is (= (:view-id error-003) (:view-id summary)))
      (is (= (:boundary-view-id error-003) (:boundary-view-id summary)))
      (is (= (:phase error-003) (:phase summary)))
      (is (= (:frame-id error-003) (:frame-id summary)))
      (is (string? (:fingerprint summary)))
      (is (= (:basis error-003) (:basis (:evidence summary))))
      (is (= (:evidence-keys-complete error-003) (set (keys (:evidence summary))))
          "complete evidence carries scope/basis/completeness, no loss")
      (is (empty? (set/intersection (:forbidden-keys error-003)
                                            (deep-keys summary)))
          "the summary carries no exception, ex-data, props, app-db, or event history"))))

(deftest fh-error-003-incomplete-evidence-states-its-loss
  (testing "Per FH-ERROR-003: a production-capped occurrence states its
            completeness honestly — `:complete? false` adds a `:loss` naming
            why, so a consumer never mistakes capped evidence for full."
    (let [summary (:summary (capture-one! {:complete? false
                                           :loss {:reason :production-policy}}))
          evidence (:evidence summary)]
      (is (false? (:complete? evidence)))
      (is (= (:evidence-keys-incomplete error-003) (set (keys evidence))))
      (is (= {:reason :production-policy} (:loss evidence))))))

(deftest fh-error-003-the-private-egress-record-is-closed-and-leaks-nothing
  (testing "Per FH-ERROR-003: the private frame egress record has EXACTLY the
            closed top-level field set, rides the always-on category, carries
            the OPAQUE exception and a capped component stack an off-box
            shipper needs, and carries NO app-db or event history anywhere —
            the automatic capture EP-0036 / D019 reject."
    (let [failure (capture-one! {:complete? false :loss {:reason :production-policy}})
          record  (eb/egress-record failure 1234)]
      (is (= (:egress-record-keys error-003) (set (keys record)))
          "the record's field set is exactly the closed egress set")
      (is (= (:egress-category error-003) (:error record))
          "it rides the always-on view-render-failed category")
      (is (= (:frame-id error-003) (:frame record)))
      (is (= 1234 (:time record)))
      (is (= (:recovery error-003) (:recovery record)))
      (is (some? (:exception record))
          "the opaque host exception is present — the private channel keeps it")
      (is (string? (:component-stack record))
          "and the capped host stack an off-box shipper needs")
      (is (= (:safe-summary-keys error-003) (set (keys (:summary record))))
          "the nested summary is the same bounded envelope")
      (is (empty? (set/intersection (:forbidden-keys error-003)
                                            (deep-keys record)))
          "no app-db, no event history, no ex-data, no props anywhere in the record"))))

;; ===========================================================================
;; Non-vacuity probe — the boundary's containment is doing REAL work.
;; ===========================================================================

(deftest non-vacuity-the-throwing-control-actually-throws
  (testing "Non-vacuity: the child body the containment tests rely on DOES
            throw when run outside the boundary, and the fixtures carry real
            expectations — so a green containment result is contained work,
            never a body that quietly did nothing or an empty fixture."
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (throw (boom)))
        "the control body throws")
    (is (seq (:safe-summary-keys error-003)) "the FH-ERROR-003 fixture is non-empty")
    (is (pos? (:repeated-failures error-002)) "the FH-ERROR-002 fixture drives real repeats")
    ;; A boundary that has NOT captured reports nothing — the gate is a gate.
    (let [b (eb/boundary eb/boundary-view-id :rk)]
      (is (nil? (eb/take-report! b)) "a clear boundary yields no report")
      (is (nil? (eb/promote! b {:on-error [:x] :frame-id nil :time 0}))
          "and promotes nothing"))))
