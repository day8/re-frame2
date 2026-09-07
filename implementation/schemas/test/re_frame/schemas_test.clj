(ns re-frame.schemas-test
  "JVM smoke tests for Spec 010 — Schemas (Malli runtime validation).

  Two surfaces here:

    1. **Elision toggle**. In dev builds (per Spec 010 §Dev builds) all
       registered schemas are checked at every validation point. In
       production builds (per Spec 010 §Production builds) validation is
       compile-time-elided via a host gate — `goog.DEBUG` on CLJS, the
       JVM mirror `re-frame.interop/debug-enabled?` here. These tests
       flip the gate via `with-redefs` and assert the dev-mode trace
       fires while the prod-mode call is silent.

    2. **Error projector → :rf/public-error mapping**. Per Spec 010 + 011
       §Default projector, the runtime ships a default projector mapping
       internal trace events to the locked four-key public-error shape
       (`:status :code :message :retryable?`). The schema-validation
       failure category maps to a 400 :bad-request. These tests pin the pure
       mapping contract independently of trace emission.

  These tests exercise schemas on the JVM via the plain-atom adapter —
  the conformance fixtures cover the dispatch-time integration; this
  file covers the elision toggle (which fixtures cannot flip from EDN)
  and the projector-mapping shape (which is a separate surface from the
  trace emission)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            ;; White-box tests reach raw state through its owning namespace;
            ;; callers outside this artefact use the encapsulated facade.
            [re-frame.schemas.validator :as rf.schemas.validator]
            [re-frame.schemas.storage :as rf.schemas.storage]
            ;; Reuse the shared empty-set digest fixture.
            [re-frame.schemas.digest-parity-fixtures :as rf.schemas.digest-parity-fixtures]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.spec :as rf.spec]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

;; ---- elision toggle -------------------------------------------------------

(deftest app-db-validation-fires-when-debug-enabled
  (testing "validate-app-schema! emits :rf.error/schema-validation-failure when debug-enabled? is true"
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [traces]
      ;; Dev mode is the JVM default; validate-app-schema! should walk the
      ;; registered schemas and emit on a malformed value.
      (with-redefs [rf.interop/debug-enabled? true]
        (rf.schemas/validate-app-schema! {:count "not-an-int"} :test/handler))
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where)))
          (is (= [:count] (-> v :tags :path)))
          (is (= "not-an-int" (-> v :tags :value)))
          (is (= :test/handler (-> v :tags :failing-id))))))))

(deftest app-db-validation-elides-when-debug-disabled
  (testing "validate-app-schema! is a no-op when debug-enabled? is false (production)"
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [traces]
      ;; Production mode — the validation site elides; even with a
      ;; malformed value, no trace fires.
      (with-redefs [rf.interop/debug-enabled? false]
        (rf.schemas/validate-app-schema! {:count "not-an-int"} :test/handler))
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no schema-validation-failure trace when validation is elided"))))

(deftest well-typed-value-passes-silently
  (testing "validate-app-schema! with a conforming value emits no trace"
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [traces]
      (with-redefs [rf.interop/debug-enabled? true]
        (rf.schemas/validate-app-schema! {:count 42} :test/handler))
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "well-typed value triggers no validation-failure trace"))))

(deftest dispatch-fires-app-db-validation
  (testing "live dispatch through the runtime validates the candidate :db
            before install (rf2-uhk9ko)"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event :n/init (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "boom")}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the malformed :n/break candidate fires exactly one schema trace")
        (is (= :n/break (-> violations first :tags :failing-id))
            ":failing-id names the handler whose candidate prompted the failure")
        (is (true? (-> violations first :tags :rollback?))
            "the trace carries :rollback? true — the public
             transaction-REJECTED vocabulary (rf2-uhk9ko)")))))

;; ---- rf2-uhk9ko — candidate rejection on schema-validation failure --------
;; (formerly rf2-wkxng / rf2-6m0se install-then-rollback; the observable
;; post-condition — app-db keeps its pre-event value, :fx skipped — is
;; unchanged, but the container is now never written at all)

(deftest app-db-rejection-keeps-pre-handler-value-on-failure
  (testing "Per Spec 010 §Per-step recovery row 4 (rf2-uhk9ko): an app-db
            schema-validation failure REJECTS the candidate before install
            — app-db keeps its pre-handler value. The dispatch is treated
            as failed; the bad candidate never stands."
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event :n/init  (fn [_ _]  {:db {:n 0}}))
    (rf/reg-event :n/ok    (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "boom")}))
    (rf/dispatch-sync [:n/init])
    (is (= {:n 0} (rf/app-db-value (rf/current-frame-id)))
        "baseline post-init state")
    (rf/dispatch-sync [:n/ok])
    (is (= {:n 42} (rf/app-db-value (rf/current-frame-id)))
        "well-typed commit is durable")
    (rf/dispatch-sync [:n/break])
    (is (= {:n 42} (rf/app-db-value (rf/current-frame-id)))
        "malformed candidate was REJECTED pre-install — app-db keeps the
         pre-handler value (was {:n 42} before :n/break; would be
         {:n \"boom\"} without the candidate validation)")))

(deftest app-db-rejection-skips-fx-on-failure
  (testing "Per rf2-uhk9ko: on rejection the dispatch is 'treated as
            failed' — :fx does NOT walk. Sibling fx that would have
            fired do not run."
    (let [fx-calls (atom [])]
      (rf/reg-fx :test/note (fn [v] (swap! fx-calls conj v)))
      (rf/reg-app-schema [:n] [:int])
      (rf/reg-event :n/init
        (fn [_ _] {:db {:n 0}}))
      (rf/reg-event :n/break-with-fx
        (fn [_ _] {:db {:n "boom"}    ;; bad candidate
                   :fx [[:test/note :should-not-fire]]}))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break-with-fx])
      (is (= {:n 0} (rf/app-db-value (rf/current-frame-id)))
          "the rejected candidate never installed")
      (is (empty? @fx-calls)
          "sibling fx did not walk — dispatch treated as failed"))))

(deftest app-db-rejection-emits-no-db-changed
  (testing "Per rf2-uhk9ko (Option B — validate before install): a rejected
            candidate emits NO :rf.event/db-changed at all — the trace
            signature is EXACTLY one :rf.error/schema-validation-failure.
            (Supersedes the retired commit-then-rollback pair: forward
            db-changed → failure → :phase :rollback db-changed.)"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event :n/init  (fn [_ _]  {:db {:n 0}}))
    (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "boom")}))
    ;; rf2-bhh8my: deliberately NOT migrated to `with-trace-recorder!` — this
    ;; listener PROJECTS each event to an `[operation phase]` tuple on capture
    ;; and `reset!`s the buffer mid-body (to drop :n/init's traces before
    ;; :n/break), neither of which the raw-event recorder expresses.
    (let [events (atom [])]
      (rf/register-listener! :trace ::ord
        (fn [ev] (when (#{:rf.event/db-changed
                          :rf.error/schema-validation-failure}
                        (:operation ev))
                   (swap! events conj
                          [(:operation ev)
                           (-> ev :tags :rf.trace/phase)]))))
      (rf/dispatch-sync [:n/init])
      (reset! events [])
      (rf/dispatch-sync [:n/break])
      (rf/unregister-listener! :trace ::ord)
      ;; ONE emission: the schema-failure diagnostic. No forward commit
      ;; trace (the candidate never installed), no rollback re-emit.
      (is (= [[:rf.error/schema-validation-failure nil]]
             @events)
          "rejection signature: exactly one schema-failure trace, zero
           db-changed emissions"))))

(deftest validate-app-schema-returns-boolean
  (testing "validate-app-schema! returns true on conform (or no schemas /
            no validator), false on any failure. The router consumes this
            to decide candidate rejection (rf2-uhk9ko)."
    (rf/reg-app-schema [:n] [:int])
    (with-redefs [rf.interop/debug-enabled? true]
      (is (true?  (rf.schemas/validate-app-schema! {:n 42}))
          "conforming value returns true")
      (is (false? (rf.schemas/validate-app-schema! {:n "boom"}))
          "non-conforming value returns false")
      (is (true?  (rf.schemas/validate-app-schema! {:n 42} :some/handler))
          "conforming + event-id arity returns true")
      (is (false? (rf.schemas/validate-app-schema! {:n "boom"} :some/handler))
          "non-conforming + event-id arity returns false"))
    (with-redefs [rf.interop/debug-enabled? false]
      (is (true? (rf.schemas/validate-app-schema! {:n "boom"} :some/handler))
          "production mode (debug-enabled? false) returns true unconditionally"))))

;; ---- rf2-jwm4 — event-payload validation ---------------------------------

(deftest dispatch-validates-event-payload-pre-handler
  (testing "Per Spec 010 §step 1 (rf2-jwm4): a malformed event vector fires
            :rf.error/schema-validation-failure :where :event before the
            handler runs; the handler is NOT invoked"
    (let [calls (atom 0)]
      (rf/reg-event :user/register
        {:schema [:cat [:= :user/register]
                     [:map [:email :string] [:age :int]]]}
        (fn [{:keys [db]} [_ payload]]
          (swap! calls inc)
          {:db (update db :users (fnil conj []) payload)}))
      (with-trace-recorder! [traces]
        ;; Well-typed payload — passes; handler runs.
        (rf/dispatch-sync [:user/register {:email "alice@example.com" :age 30}])
        ;; Malformed payload — fails; handler must NOT run.
        (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
        (is (= 1 @calls)
            "handler ran exactly once — once for the well-typed payload, skipped for the bad one")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :event (-> v :tags :where)))
            (is (= :user/register (-> v :tags :failing-id)))
            (is (= :user/register (-> v :tags :schema-id)))))))))

(deftest event-payload-validation-failure-still-runs-after-pass
  (testing "Per Spec 002 §Interceptor chain execution rule 2 (rf2-i36mm):
            a schema-validation failure on the event vector (Spec 010 step
            1) suppresses the HANDLER but the interceptor chain STILL runs,
            so every :after stage fires — symmetric with the cofx-failure
            path (step 2) which uses :rf/skip-handler?. The :after pass
            must run regardless of the pre-handler failure so cleanup-on-
            :after interceptors (debug pp, Story snapshot capturer) are not
            leaked."
    (let [handler-calls (atom 0)
          before-calls  (atom 0)
          after-calls   (atom 0)]
      ;; EP-0022 reference-only flip (rf2-0adhqs.9): chains carry refs only, so
      ;; the probe interceptor is registered and referenced by id rather than
      ;; dropped inline. The closures still capture the per-test atoms.
      (rf/reg-interceptor ::after-probe
        {:before (fn [ctx] (swap! before-calls inc) ctx)
         :after  (fn [ctx] (swap! after-calls inc) ctx)})
      (rf/reg-event :user/probe
        {:schema [:cat [:= :user/probe] :int]
         :interceptors [::after-probe]}
        (fn [{:keys [db]} _] (swap! handler-calls inc) {:db db}))
      (with-trace-recorder! [traces]
        ;; Malformed payload — event-vector validation fails pre-handler.
        (rf/dispatch-sync [:user/probe "not-an-int"])
        (is (= 1 (count (filter #(= :rf.error/schema-validation-failure
                                    (:operation %))
                                @traces)))
            "the schema-validation failure fired")
        (is (zero? @handler-calls)
            "the handler was suppressed via :rf/skip-handler?")
        (is (= 1 @after-calls)
            "the :after pass STILL ran in full on the validation failure")
        (is (= 1 @before-calls)
            "the :before pass ran too — the chain executes end-to-end, the
            handler-wrapper :before is the only stage that honours
            :rf/skip-handler?")))))

(deftest event-payload-validation-elides-when-debug-disabled
  (testing "validate-event! is a no-op when debug-enabled? is false (production)"
    (let [calls (atom 0)]
      (rf/reg-event :user/strict
        {:schema [:cat [:= :user/strict] :int]}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (with-trace-recorder! [traces]
        (with-redefs [rf.interop/debug-enabled? false]
          (rf/dispatch-sync [:user/strict "not-an-int"]))
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace when debug-enabled? is false")
        (is (= 1 @calls)
            "handler runs anyway — production validation is elided")))))

;; ---- rf2-wcam — sub-return validation ------------------------------------

(deftest sub-return-validation-fires-and-replaces-with-default
  (testing "Per Spec 010 §step 6 (rf2-wcam): a sub whose return value fails
            its :schema emits :rf.error/schema-validation-failure :where :sub-return
            and the caller sees nil (default :replaced-with-default recovery)"
    (rf/reg-event :items/init (fn [_ _] {:db {:items ["a" "b" "c"]}}))
    (rf/reg-event :items/break (fn [{:keys [db]} _] {:db (assoc db :items [1 2 3])}))
    (rf/reg-sub :items
      {:schema [:vector :string]}
      (fn [db _] (:items db)))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:items/init])
      ;; Well-typed: sub returns the vec.
      (is (= ["a" "b" "c"] (rf/subscribe-once [:items])))
      (rf/dispatch-sync [:items/break])
      ;; Malformed: sub yields nil per :replaced-with-default recovery.
      (is (nil? (rf/subscribe-once [:items])))
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (pos? (count violations))
            "at least one sub-return validation failure fired")
        (let [v (first violations)]
          (is (= :sub-return (-> v :tags :where)))
          (is (= :items (-> v :tags :rf.sub/id)))
          (is (= :items (-> v :tags :schema-id)))
          ;; rf2-9cm27 — the :frame tag must ride the trace so the
          ;; violation lands in the per-frame epoch :trace-events
          ;; (epoch/capture buffers only frame-tagged traces). The
          ;; reaction recomputed on :rf/default.
          (is (= :rf/default (-> v :tags :frame))
              ":frame tag carries the reaction's frame")
          (is (= :replaced-with-default (:recovery v))))))))

(deftest compute-sub-validates-return-value
  (testing "compute-sub validates the return against :schema — the pure
            test-time path mirrors the live reactive path"
    (rf/reg-sub :nums
      {:schema [:vector :int]}
      (fn [db _] (:nums db)))
    (with-trace-recorder! [traces]
      (is (= [1 2 3] (#'re-frame.subs/compute-sub [:nums] {:nums [1 2 3]})))
      (is (nil? (#'re-frame.subs/compute-sub [:nums] {:nums ["bad"]}))
          "compute-sub yields nil on validation failure")
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one trace from the malformed compute-sub call")))))

;; ---- EP-0017 recordable-cofx `:schema` validation ------------------------
;;
;; EP-0017 RETIRED the ctx-mutating `inject-cofx` injection-time validation
;; (the old `:rf.error/schema-validation-failure :where :cofx` shape, with
;; `inject-cofx`). The LIVE cofx schema-validation contract is the
;; recordable-value path: a recordable coeffect declared on the handler's
;; `:rf.cofx/requires` is delivered FLAT into the coeffects map, and its value
;; (supplied / replayed / generated) is validated against the `reg-cofx`
;; registration's `:schema` by `re-frame.cofx/validate-recordable-value!`. A
;; malformed value emits `:rf.error/cofx-value-invalid` (a PRODUCTION hard
;; error — an out-of-contract durable value is corrupt causal state) and THROWS
;; during context assembly, so the handler is NOT invoked (recovery
;; `:no-recovery`). The deftests below replace the disabled `inject-cofx`
;; tests this file used to carry; the schemas conformance fixture
;; `schema-cofx-validates.edn` exercises the same path through the corpus
;; runner.

(deftest recordable-cofx-value-invalid-fires-and-skips-handler
  (testing "EP-0017 (replaces rf2-7leq): a recordable cofx whose supplied
            value fails its `reg-cofx` `:schema` emits
            :rf.error/cofx-value-invalid and the handler is NOT invoked"
    ;; PROVIDED recordable fact — its value rides the dispatch token's
    ;; `:rf.cofx` map (no supplier). The handler declares it via
    ;; `:rf.cofx/requires`; delivery validates the supplied value against
    ;; `:schema`, fails (42 is not a :string), and THROWS before the handler.
    (rf/reg-cofx :app-version/v
      {:recordable? true :provided? true :schema :string})
    (let [calls (atom 0)]
      (rf/reg-event :cap/seed
        {:rf.cofx/requires [:app-version/v]}
        (fn [_cofx _]
          (swap! calls inc)
          {:db {:app-version "should-not-stash"}}))
      (with-trace-recorder! [traces]
        ;; The recordable-value failure throws out of dispatch-sync (a hard
        ;; error in dev AND prod); the trace fires BEFORE the throw.
        (try
          (rf/dispatch-sync [:cap/seed] {:rf.cofx {:app-version/v 42}})
          (catch clojure.lang.ExceptionInfo _))
        (is (= 0 @calls)
            "handler was skipped because the recordable cofx :schema failed")
        (let [violations (filter #(= :rf.error/cofx-value-invalid
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :app-version/v (-> v :tags :rf.cofx/id)))
            (is (= :cap/seed (-> v :tags :failing-id)))
            (is (= 42 (-> v :tags :value)))
            ;; :recovery is hoisted to the top-level trace event
            ;; (Spec 009 §Core fields hoist contract), not under :tags.
            (is (= :no-recovery (:recovery v)))))))))

(deftest recordable-cofx-value-valid-flows-to-handler
  (testing "a conforming recordable cofx value flows through to the handler —
            no :rf.error/cofx-value-invalid trace, handler runs"
    (rf/reg-cofx :app-version/well
      {:recordable? true :provided? true :schema :string})
    (let [seen-version (atom nil)]
      (rf/reg-event :cap/seed-good
        {:rf.cofx/requires [:app-version/well]}
        (fn [{:keys [app-version/well]} _]
          (reset! seen-version well)
          {}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:cap/seed-good] {:rf.cofx {:app-version/well "1.4.5"}})
        (is (= "1.4.5" @seen-version)
            "handler ran and saw the well-typed recordable cofx value")
        (is (empty? (filter #(= :rf.error/cofx-value-invalid (:operation %))
                            @traces))
            "no cofx-value-invalid trace fires for a conforming value")))))

(deftest recordable-cofx-value-invalid-redacts-sensitive-value
  (testing "EP-0017 / rf2-hdi6wr — a recordable cofx whose :schema marks a slot
            {:sensitive? true} redacts the value-bearing slots through the
            shared `redact-validation-tags` seam: the trace's :value scrubs to
            :rf/redacted and :sensitive? true is stamped (never the raw secret)"
    ;; The cofx value is a map with a sensitive `:token` leaf; the failing
    ;; value (an :int where a :string is required) must not egress verbatim.
    (rf/reg-cofx :auth/creds
      {:recordable? true :provided? true
       :schema [:map [:token {:sensitive? true} :string]]})
    (rf/reg-event :cap/seed-secret
      {:rf.cofx/requires [:auth/creds]}
      (fn [_ _] {}))
    (with-trace-recorder! [traces]
      (try
        (rf/dispatch-sync [:cap/seed-secret]
                          {:rf.cofx {:auth/creds {:token 42}}})
        (catch clojure.lang.ExceptionInfo _))
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v) "the recordable-cofx violation fired")
        ;; :sensitive? is hoisted to the top-level trace event (Spec 009),
        ;; not under :tags.
        (is (true? (:sensitive? v))
            ":sensitive? true is stamped (the schema marked a slot sensitive)")
        (is (= :rf/redacted (-> v :tags :value))
            "the value-bearing :value slot scrubbed to :rf/redacted — the raw
             {:token 42} never egressed off-box")))))

;; ---- rf2-xp2o3 — fx-args validation (Spec 010 step 5) --------------------

(deftest fx-args-validation-fires-and-skips-only-the-offending-fx
  (testing "Per Spec 010 §step 5 (rf2-xp2o3): an fx whose args fail its :schema
            emits :rf.error/schema-validation-failure :where :fx-args; the
            offending fx is skipped, sibling fx in the same :fx vector
            continue to run (recovery: :skipped)"
    (let [bad-fx-calls  (atom 0)
          good-fx-calls (atom 0)]
      (rf/reg-fx :my/notify
        {:schema [:map [:level :keyword] [:message :string]]}
        (fn [_ctx _args] (swap! bad-fx-calls inc)))
      (rf/reg-fx :my/log
        (fn [_ctx _args] (swap! good-fx-calls inc)))
      (rf/reg-event :ui/announce
        (fn [_ _]
          {:fx [[:my/notify {:level "error"          ;; bad: needs keyword
                             :message "boom"}]
                [:my/log    "anything"]]}))           ;; sibling — must still run
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:ui/announce])
        (is (= 0 @bad-fx-calls)
            "the offending fx handler was skipped — its body did NOT run")
        (is (= 1 @good-fx-calls)
            "the sibling fx in the same :fx vector still ran (cascade continues)")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :fx-args (-> v :tags :where)))
            (is (= :my/notify (-> v :tags :failing-id)))
            (is (= :my/notify (-> v :tags :rf.fx/id)))
            (is (= :my/notify (-> v :tags :schema-id)))
            (is (= :ui/announce (-> v :tags :event-id))
                "the originating event-id threads through to the fx-args trace")
            ;; rf2-9cm27 — the :frame tag must ride the trace so the
            ;; violation lands in the per-frame epoch :trace-events
            ;; (epoch/capture buffers only frame-tagged traces). The
            ;; dispatch ran on :rf/default.
            (is (= :rf/default (-> v :tags :frame))
                ":frame tag carries the in-flight cascade's frame")
            (is (= :skipped (:recovery v))
                "fx-args failure recovery is :skipped per Spec 010 row 5")))
        (let [handled (filter #(= :rf.fx/handled (:operation %)) @traces)]
          (is (= 1 (count handled))
              ":rf.fx/handled fires only for the sibling that actually ran"))))))

(deftest fx-args-validation-passes-when-conforming
  (testing "well-typed fx args flow through to the fx handler — no trace, handler runs"
    (let [calls (atom 0)
          seen (atom nil)]
      (rf/reg-fx :my/email
        {:schema [:map [:to :string]]}
        (fn [_ctx args]
          (swap! calls inc)
          (reset! seen args)))
      (rf/reg-event :user/welcome
        (fn [_ _]
          {:fx [[:my/email {:to "alice@example.com"}]]}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:user/welcome])
        (is (= 1 @calls) "fx handler ran exactly once")
        (is (= {:to "alice@example.com"} @seen) "fx handler saw the well-typed args")
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no schema-validation-failure trace fires for a conforming fx-args")))))

(deftest fx-args-validation-elides-when-debug-disabled
  (testing "validate-fx! is a no-op when debug-enabled? is false (production)"
    (let [calls (atom 0)]
      (rf/reg-fx :strict/fx
        {:schema [:map [:x :int]]}
        (fn [_ctx _args] (swap! calls inc)))
      (rf/reg-event :strict/trigger
        (fn [_ _]
          {:fx [[:strict/fx {:x "not-an-int"}]]}))
      (with-trace-recorder! [traces]
        (with-redefs [rf.interop/debug-enabled? false]
          (rf/dispatch-sync [:strict/trigger]))
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace when debug-enabled? is false")
        (is (= 1 @calls)
            "fx handler runs anyway — production validation is elided")))))

(deftest fx-args-validation-direct-call-shape
  (testing "validate-fx! returns true on pass, false on fail; emits the canonical
            :where :fx-args trace with the locked tag shape"
    (with-trace-recorder! [traces]
      ;; Direct call — exercises the validate-fx! fn itself, not the integration.
      (is (true? (rf.schemas/validate-fx! :my/fx :ev/origin {:x 1} {:schema [:map [:x :int]]}))
          "well-typed args pass")
      (is (false? (rf.schemas/validate-fx! :my/fx :ev/origin {:x "bad"} {:schema [:map [:x :int]]}))
          "malformed args fail")
      (is (true? (rf.schemas/validate-fx! :my/fx :ev/origin {:x 1} {}))
          "no :schema → soft pass")
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations)))
        (let [v (first violations)]
          (is (= :fx-args   (-> v :tags :where)))
          (is (= :my/fx     (-> v :tags :rf.fx/id)))
          (is (= :my/fx     (-> v :tags :failing-id)))
          (is (= :my/fx     (-> v :tags :schema-id)))
          (is (= :ev/origin (-> v :tags :event-id)))
          (is (= {:x "bad"} (-> v :tags :rf.fx/args)))
          (is (= {:x "bad"} (-> v :tags :value)))
          (is (= {:x "bad"} (-> v :tags :received)))
          (is (= :skipped   (:recovery v)))
          ;; rf2-9cm27 — a DIRECT call (4-arity, no frame) carries NO
          ;; :frame tag. The runtime callers pass the in-flight frame via
          ;; the optional trailing arity; direct callers (probe, unit
          ;; tests) do not, exactly like validate-event!'s 3-arity.
          (is (not (contains? (:tags v) :frame))
              "direct 4-arity call emits no :frame (runtime callers supply it)"))))))

;; ---- rf2-9cm27: fx / sub validation traces carry :frame ------------------
;;
;; CORRECTNESS. validate-fx! / validate-sub! must stamp a :frame tag on their
;; :rf.error/schema-validation-failure trace — exactly like validate-event!
;; (rf2-lo28u) and validate-app-schema! already do. (The cofx surface carries
;; :frame on the EP-0017 recordable path's :rf.error/cofx-value-invalid trace —
;; see recordable-cofx-value-invalid-attributes-named-frame below — not on a
;; :where :cofx trace; the injection-time validate-cofx! path was retired with
;; `inject-cofx`.) re-frame.epoch.capture/capture-event! buffers a trace into
;; the in-flight cascade ONLY when the trace's tags carry the cascade's
;; :frame; an untagged violation reaches the global trace stream but is
;; SILENTLY DROPPED from the per-frame epoch :trace-events, so the Xray Issues
;; / Schema-timeline lens (which reads :trace-events) is blind to it.
;;
;; The :rf/default-frame assertions on the end-to-end tests above
;; (fx-args-validation-fires-and-skips-only-the-offending-fx /
;;  sub-return-validation-fires-and-replaces-with-default) prove the tag
;; lands. These tests prove the tag carries the ACTUAL in-flight frame, not
;; a hardcoded default — a NAMED frame's dispatch / reaction must attribute
;; the violation to that frame (mirrors
;; schema-fires-only-on-the-frame-it-registers-against for :where :app-db).

(deftest recordable-cofx-value-invalid-attributes-named-frame
  (testing "EP-0017 (replaces rf2-9cm27 cofx case) — a recordable-cofx
            :schema failure on a NAMED frame stamps that frame's id on the
            :rf.error/cofx-value-invalid trace (not :rf/default), so the epoch
            capture buffers it into the right per-frame cascade."
    (rf/make-frame {:id :test/cofx-frame})
    (rf/reg-cofx :probe/cofx
      {:recordable? true :provided? true :schema :string})
    (rf/reg-event :probe/cofx-seed
      {:rf.cofx/requires [:probe/cofx]}
      (fn [_ _] {}))
    (with-trace-recorder! [traces]
      (try
        (rf/dispatch-sync [:probe/cofx-seed]
                          {:frame :test/cofx-frame
                           :rf.cofx {:probe/cofx 42}})   ;; int, not string
        (catch clojure.lang.ExceptionInfo _))
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v) "the recordable-cofx violation fired")
        (is (= :test/cofx-frame (-> v :tags :frame))
            ":frame tag carries the named in-flight cascade frame")))))

(deftest fx-args-validation-frame-tag-attributes-named-frame
  (testing "rf2-9cm27 — an fx-args validation failure on a NAMED frame
            stamps that frame's id on the trace (not :rf/default)."
    (rf/make-frame {:id :test/fx-frame})
    (rf/reg-fx :probe/fx
      {:schema [:map [:x :int]]}
      (fn [_ctx _args] nil))
    (rf/reg-event :probe/fx-seed
      (fn [_ _] {:fx [[:probe/fx {:x "bad"}]]}))   ;; string, not int
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:probe/fx-seed] {:frame :test/fx-frame})
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "the fx-args violation fired")
        (is (= :fx-args (-> v :tags :where)))
        (is (= :test/fx-frame (-> v :tags :frame))
            ":frame tag carries the named in-flight cascade frame")))))

(deftest sub-return-validation-frame-tag-attributes-named-frame
  (testing "rf2-9cm27 — a sub-return validation failure on a NAMED frame
            stamps the reaction's frame id on the trace (not :rf/default)."
    (rf/make-frame {:id :test/sub-frame})
    (rf/reg-event :probe/sub-break (fn [{:keys [db]} _] {:db (assoc db :items [1 2 3])})) ;; ints, not strings
    (rf/reg-sub :probe/items
      {:schema [:vector :string]}
      (fn [db _] (:items db)))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:probe/sub-break] {:frame :test/sub-frame})
      ;; Subscribe within the named frame so the reaction recomputes there.
      (is (nil? (rf/subscribe-once [:probe/items] {:frame :test/sub-frame}))
          "malformed sub yields nil per :replaced-with-default recovery")
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "the sub-return violation fired")
        (is (= :sub-return (-> v :tags :where)))
        (is (= :test/sub-frame (-> v :tags :frame))
            ":frame tag carries the reaction's named frame")))))

;; ---- G2 (rf2-rbbmt): direct-call shape for event / cofx / sub ------------
;;
;; validate-fx! has a dedicated direct-invocation shape test above
;; (fx-args-validation-direct-call-shape). The three sibling fns were
;; exercised ONLY through live dispatch — no direct call asserting the
;; boolean return contract (true on pass / false on fail / true on the
;; no-`:schema` soft-pass arm at validate.cljc:250) plus the locked tag
;; shape. These mirror the fx shape test for symmetry across the four
;; meta-bearing wrappers.

(deftest event-payload-validation-direct-call-shape
  (testing "rf2-rbbmt — validate-event! returns true on pass, false on
            fail, true on the no-:schema soft-pass arm; emits the
            canonical :where :event trace with the locked tag shape"
    (with-trace-recorder! [traces]
      (is (true? (rf.schemas/validate-event! :user/strict [:user/strict 7]
                                          {:schema [:cat [:= :user/strict] :int]}))
          "well-typed event vector passes")
      (is (false? (rf.schemas/validate-event! :user/strict [:user/strict "bad"]
                                           {:schema [:cat [:= :user/strict] :int]}))
          "malformed event vector fails")
      (is (true? (rf.schemas/validate-event! :user/strict [:user/strict "bad"] {}))
          "no :schema on meta → soft pass (validate.cljc:250 true arm)")
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one trace — only the malformed call with a :schema fired")
        (let [v (first violations)]
          (is (= :event       (-> v :tags :where)))
          (is (= :user/strict (-> v :tags :event-id)))
          (is (= :user/strict (-> v :tags :failing-id)))
          (is (= :user/strict (-> v :tags :schema-id)))
          (is (= [:user/strict "bad"] (-> v :tags :value)))
          (is (= [:user/strict "bad"] (-> v :tags :received)))
          (is (= :no-recovery (:recovery v))))))))

;; NB the cofx-validation-direct-call-shape test was REMOVED per rf2-nkf4l3:
;; the injection-time `validate-cofx!` fn it pinned was retired (EP-0017). The
;; live cofx schema contract is `re-frame.cofx/validate-recordable-value!` →
;; `:rf.error/cofx-value-invalid` (a production hard error), covered by
;; recordable-cofx-value-invalid-attributes-named-frame above and the cofx
;; satisfaction tests in the core artefact.

(deftest sub-return-validation-direct-call-shape
  (testing "rf2-rbbmt — validate-sub! returns true on pass, false on
            fail, true on the no-:schema soft-pass arm; emits the
            canonical :where :sub-return trace with the locked tag shape"
    (with-trace-recorder! [traces]
      (is (true? (rf.schemas/validate-sub! :items [:items] ["a" "b"]
                                        {:schema [:vector :string]}))
          "well-typed sub return passes")
      (is (false? (rf.schemas/validate-sub! :items [:items] [1 2]
                                         {:schema [:vector :string]}))
          "malformed sub return fails")
      (is (true? (rf.schemas/validate-sub! :items [:items] [1 2] {}))
          "no :schema on meta → soft pass (validate.cljc:250 true arm)")
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations)))
        (let [v (first violations)]
          (is (= :sub-return  (-> v :tags :where)))
          (is (= :items       (-> v :tags :rf.sub/id)))
          (is (= :items       (-> v :tags :failing-id)))
          (is (= :items       (-> v :tags :schema-id)))
          (is (= [:items]     (-> v :tags :rf.sub/query-v)))
          (is (= [1 2]        (-> v :tags :value)))
          (is (= [1 2]        (-> v :tags :received)))
          (is (= :replaced-with-default (:recovery v))))))))

;; ---- G3 (rf2-rbbmt): production-elision symmetry for sub ----------
;;
;; debug-enabled?=false elision is pinned for app-db, event, and fx but NOT
;; for validate-sub! (the cofx elision pin was removed with validate-cofx! per
;; rf2-nkf4l3). The bodies share the outer `(if interop/debug-enabled? ... true)`
;; gate, so a refactor of one wrapper that broke its gate would slip past the
;; suite. This direct-call pin closes that asymmetry for the sub surface.

(deftest sub-return-validation-elides-when-debug-disabled
  (testing "rf2-rbbmt — validate-sub! is a no-op (returns true, emits
            nothing) when debug-enabled? is false (production)"
    (with-trace-recorder! [traces]
      (with-redefs [rf.interop/debug-enabled? false]
        (is (true? (rf.schemas/validate-sub! :items [:items] [1 2]
                                          {:schema [:vector :string]}))
            "production gate returns true unconditionally — even on a
             value that would fail in dev"))
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no validation trace when debug-enabled? is false"))))

(deftest fx-args-validation-redacts-when-sensitive
  (testing "validate-fx! consults the schema tree for `:sensitive?` props
            (the fx-meta `:sensitive?` annotation has been removed); on
            redaction it scrubs `:value`/`:received`/`:explain`/`:rf.fx/args`
            and stamps `:sensitive? true`. Per rf2-4fbsd the earlier
            `:malli-error` duplicate slot is gone."
    (with-trace-recorder! [traces]
      ;; Sensitivity is now path-marked on the schema slot (the handler/
      ;; fx-meta `:sensitive?` annotation has been removed); a `:sensitive?
      ;; true` prop on the failing slot's schema drives redaction.
      (is (false? (rf.schemas/validate-fx! :my/secret
                                        :ev/origin
                                        {:token 42}
                                        {:schema [:map [:token {:sensitive? true} :string]]})))
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations)))
        (let [v (first violations)]
          ;; `:sensitive?` is hoisted from `:tags` to the top-level per
          ;; Spec 009 §Trace-event field `:sensitive?` (rf2-isdwf).
          (is (true? (:sensitive? v))
              "top-level :sensitive? stamp consumers filter on")
          (is (= :rf/redacted (-> v :tags :value)))
          (is (= :rf/redacted (-> v :tags :received)))
          (is (= :rf/redacted (-> v :tags :rf.fx/args)))
          (is (= :rf/redacted (-> v :tags :explain)))
          (is (not (contains? (:tags v) :malli-error))
              ":malli-error slot is gone (rf2-4fbsd)")
          ;; Non-redacted slots survive redaction.
          (is (= :my/secret (-> v :tags :rf.fx/id)))
          (is (= :my/secret (-> v :tags :failing-id)))
          (is (= :fx-args   (-> v :tags :where))))))))

(deftest fx-args-validation-late-bind-hook-published
  (testing "the :schemas/validate-fx! late-bind hook IS published — the
            schemas artefact's contract surface includes this fn alongside
            the four siblings"
    (let [resolved (re-frame.late-bind/get-fn :schemas/validate-fx!)]
      (is (some? resolved) "hook resolves to a fn when schemas is loaded")
      (is (= rf.schemas/validate-fx! resolved) "hook points at the public fn"))))

;; ---- error projector → :rf/public-error mapping --------------------------

(defn- default-error-projector
  "The default projector contract per Spec 011 §Default projector. The
  runtime-resident registry-backed projector isn't yet wired (rf2-6528),
  but the mapping is locked. This fn implements the table verbatim so
  these tests pin the mapping contract.

  Returns the locked four-key public-error shape:
    {:status :code :message :retryable?}

  In dev mode (:dev-error-detail? true) the public shape carries an
  additional :details key with the original trace event."
  ([trace-event] (default-error-projector trace-event {}))
  ([trace-event {:keys [dev-error-detail?]}]
   (let [base (case (:operation trace-event)
                :rf.error/no-such-handler
                {:status 404 :code :not-found
                 :message "Page not found" :retryable? false}

                :rf.error/schema-validation-failure
                {:status 400 :code :bad-request
                 :message "Invalid input" :retryable? false}

                :rf.error/handler-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/sub-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/fx-handler-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/drain-depth-exceeded
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                ;; default — generic 500
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false})]
     (cond-> base
       dev-error-detail? (assoc :details trace-event)))))

(deftest projector-maps-schema-failure-to-bad-request
  (testing "schema-validation-failure projects to a locked 400 :bad-request"
    (let [trace-event {:operation :rf.error/schema-validation-failure
                       :op-type   :error
                       :tags      {:where :event :event-id :user/register
                                   :received [:user/register {:age "no"}]}
                       :recovery  :no-recovery}
          public      (default-error-projector trace-event)]
      (is (= 400 (:status public)))
      (is (= :bad-request (:code public)))
      (is (= "Invalid input" (:message public)))
      (is (false? (:retryable? public))
          "schema validation failure is NOT retryable — the input is the bug")
      (is (= #{:status :code :message :retryable?} (set (keys public)))
          "prod-mode public shape is the locked four keys only — no :details leak"))))

(deftest projector-includes-details-in-dev
  (testing "with :dev-error-detail? true the public shape carries the original trace under :details"
    (let [trace-event {:operation :rf.error/schema-validation-failure
                       :tags      {:where :app-db :path [:user] :value "bad"}}
          public      (default-error-projector trace-event {:dev-error-detail? true})]
      (is (= 400 (:status public)))
      (is (= :bad-request (:code public)))
      (is (contains? public :details)
          ":details carries the original trace event in dev mode")
      (is (= trace-event (:details public))
          ":details is the trace event verbatim — full internal detail"))))

(deftest projector-falls-back-to-generic-500
  (testing "an unknown error category projects to the locked generic-500 shape"
    (let [trace-event {:operation :rf.error/something-unmapped
                       :tags      {}}
          public      (default-error-projector trace-event)]
      (is (= 500 (:status public)))
      (is (= :internal-error (:code public)))
      (is (= "Something went wrong" (:message public)))
      (is (false? (:retryable? public))))))

(deftest projector-maps-no-such-handler-to-404
  (testing ":rf.error/no-such-handler in routing context projects to 404"
    (let [trace-event {:operation :rf.error/no-such-handler
                       :tags      {:url "/no-such-page"}}
          public      (default-error-projector trace-event)]
      (is (= 404 (:status public)))
      (is (= :not-found (:code public))))))

(deftest projector-output-shape-is-stable
  (testing "every projection returns the locked four keys (no extras in prod)"
    (doseq [op [:rf.error/no-such-handler
                :rf.error/schema-validation-failure
                :rf.error/handler-exception
                :rf.error/sub-exception
                :rf.error/fx-handler-exception
                :rf.error/drain-depth-exceeded
                :rf.error/some-future-category]]
      (let [public (default-error-projector {:operation op :tags {}})]
        (is (= #{:status :code :message :retryable?} (set (keys public)))
            (str "projection for " op " must carry exactly the four locked keys"))
        (is (integer? (:status public)))
        (is (keyword? (:code public)))
        (is (string? (:message public)))
        (is (boolean? (:retryable? public)))))))

;; ---- rf2-xfa2 — frame-scoped app-db schemas ------------------------------

(deftest reg-app-schema-defaults-to-current-frame
  (testing "Per Spec 010 §Per-frame schemas — reg-app-schema with no opts
            registers against (current-frame), which is :rf/default outside
            (with-frame ...)."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (is (= [:map [:id :uuid]] (rf.schemas/app-schema-at [:user]))
        "schema is visible from the active frame's lookup")
    (is (= [:map [:id :uuid]] (rf.schemas/app-schema-at [:user] :rf/default))
        "schema is visible from explicit :rf/default lookup")
    (is (= {[:user] [:map [:id :uuid]]} (rf.schemas/app-schemas))
        "app-schemas returns the active frame's schema set")
    (is (= {[:user] [:map [:id :uuid]]} (rf.schemas/app-schemas :rf/default))
        "app-schemas with explicit :rf/default returns the same map")))

(deftest reg-app-schema-explicit-frame-opt-isolates-schemas
  (testing "Per Spec 010 §Per-frame schemas — :frame opt registers against
            a named frame; sibling frames don't see that schema."
    (rf/make-frame {:id :test/story})
    (rf/reg-app-schema [:user] {:frame :rf/default} [:map [:id :uuid]])
    (rf/reg-app-schema [:user] {:frame :test/story} [:map [:nick :string]])
    (is (= [:map [:id :uuid]]   (rf.schemas/app-schema-at [:user] :rf/default))
        "default frame keeps its own schema at [:user]")
    (is (= [:map [:nick :string]] (rf.schemas/app-schema-at [:user] :test/story))
        "story frame has its own (different) schema at [:user]")
    (is (= {[:user] [:map [:id :uuid]]}     (rf.schemas/app-schemas :rf/default)))
    (is (= {[:user] [:map [:nick :string]]} (rf.schemas/app-schemas {:frame :test/story})))))

(deftest sibling-frame-schemas-do-not-fire-on-each-others-dispatches
  (testing "Per Spec 010 §Per-frame schemas — validate-app-schema! only walks the
            schemas registered against THIS dispatch's frame; a malformed
            commit on frame A must not fire a schema-validation-failure for
            a schema registered against frame B."
    (rf/make-frame {:id :test/main})
    (rf/make-frame {:id :test/other})
    ;; Schema only on :test/other; commit happens on :test/main.
    (rf/reg-app-schema [:n] {:frame :test/other} [:int])
    (rf/reg-event :n/break-on-main (fn [{:keys [db]} _] {:db (assoc db :n "not-an-int")}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:n/break-on-main] {:frame :test/main})
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "no schema fires on :test/main because the schema lives on :test/other"))))

(deftest schema-fires-only-on-the-frame-it-registers-against
  (testing "Per Spec 010 §Per-frame schemas — a malformed commit on the same
            frame the schema is registered against DOES fire the failure trace."
    (rf/make-frame {:id :test/main})
    (rf/reg-app-schema [:n] {:frame :test/main} [:int])
    (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "not-an-int")}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:n/break] {:frame :test/main})
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the schema registered against :test/main fires when :test/main commits a violation")
        (is (= :test/main (-> violations first :tags :frame))
            ":frame tag carries the failing frame's id")))))

(deftest app-schemas-with-keyword-and-opts-arities-agree
  (testing "(app-schemas frame-id) is documented as sugar for
            (app-schemas {:frame frame-id}); both must return the same map."
    (rf/make-frame {:id :test/k})
    (rf/reg-app-schema [:k] {:frame :test/k} [:int])
    (is (= (rf.schemas/app-schemas :test/k)
           (rf.schemas/app-schemas {:frame :test/k}))
        "keyword form == opts-map form")))

;; ---- rf2-0z1z — app-schemas-digest --------------------------------------

(deftest app-schemas-digest-is-canonical-wire-form
  (testing "Per Spec 010 §Digest algorithm — the digest is the literal
            prefix \"sha256:\" followed by 16 lowercase hex characters."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (let [d (rf.schemas/app-schemas-digest)]
      (is (string? d))
      (is (re-matches #"sha256:[0-9a-f]{16}" d)
          "digest is exactly \"sha256:\" + 16 lowercase hex chars"))))

(deftest app-schemas-digest-is-stable
  (testing "Per Spec 010 §Digest algorithm — registering the same schema
            set produces the same digest (cross-runtime byte-stable)."
    (rf/reg-app-schema [:user]  [:map [:id :uuid]])
    (rf/reg-app-schema [:todos] [:vector :string])
    (let [d1 (rf.schemas/app-schemas-digest)]
      ;; Re-register the SAME schemas — last-write-wins, but the map is
      ;; structurally identical, so the digest must not move.
      (rf/reg-app-schema [:todos] [:vector :string])
      (rf/reg-app-schema [:user]  [:map [:id :uuid]])
      (is (= d1 (rf.schemas/app-schemas-digest))
          "byte-identical schema set → byte-identical digest"))))

(deftest app-schemas-digest-changes-on-schema-change
  (testing "A schema-set change perturbs the digest. Two different schema
            sets must produce distinct digests."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (let [before (rf.schemas/app-schemas-digest)]
      (rf/reg-app-schema [:user] [:map [:id :string]])
      (is (not= before (rf.schemas/app-schemas-digest))
          "tightening / changing a schema flips the digest"))))

(deftest app-schemas-digest-frame-isolated
  (testing "Per Spec 010 §Per-frame schemas — two frames with different
            schema sets produce different digests; a frame with no schemas
            has a stable empty-set digest distinct from any non-empty
            frame's digest."
    (rf/make-frame {:id :test/a})
    (rf/make-frame {:id :test/b})
    (rf/reg-app-schema [:user] {:frame :test/a} [:map [:id :uuid]])
    ;; :test/b has no schemas registered.
    (let [da (rf.schemas/app-schemas-digest :test/a)
          db (rf.schemas/app-schemas-digest :test/b)]
      (is (not= da db)
          "frames with different schema sets have different digests")
      (is (= db (rf.schemas/app-schemas-digest :test/b))
          "the empty-schema digest is stable across calls")
      (is (= db (rf.schemas/app-schemas-digest {:frame :test/b}))
          "keyword-sugar arity equals opts-map arity"))))

(deftest app-schemas-digest-keyword-and-opts-arities-agree
  (testing "(app-schemas-digest frame-id) is sugar for
            (app-schemas-digest {:frame frame-id}); both must return the
            same string."
    (rf/make-frame {:id :test/d})
    (rf/reg-app-schema [:k] {:frame :test/d} [:int])
    (is (= (rf.schemas/app-schemas-digest :test/d)
           (rf.schemas/app-schemas-digest {:frame :test/d}))
        "keyword form == opts-map form")))

(deftest app-schemas-digest-empty-set-is-defined
  (testing "Empty schema set has a defined, stable digest (the SHA-256 of
            the empty string, prefixed). Hosts with no schemas registered
            still get a usable digest, not nil."
    (rf/make-frame {:id :test/empty})
    (let [d (rf.schemas/app-schemas-digest :test/empty)]
      (is (string? d))
      (is (re-matches #"sha256:[0-9a-f]{16}" d))
      ;; rf2-rbbmt dedup D3 — the empty-set digest literal is single-
      ;; sourced from the parity fixtures (the namespace's own docstring
      ;; declares it the source of truth) rather than re-pinned here.
      (is (= (:expected rf.schemas.digest-parity-fixtures/empty-set) d)
          "empty schema set hashes the empty concatenation per Spec 010"))))

(deftest app-schemas-digest-independent-of-registration-order
  (testing "Per Spec 010 §Digest algorithm step 4 — lines are sorted
            lexicographically before final hashing, so the registration
            order of schemas must not affect the digest."
    (rf/make-frame {:id :test/o1})
    (rf/make-frame {:id :test/o2})
    ;; Same schemas, different registration order.
    (rf/reg-app-schema [:user]  {:frame :test/o1} [:map [:id :uuid]])
    (rf/reg-app-schema [:todos] {:frame :test/o1} [:vector :string])
    (rf/reg-app-schema [:todos] {:frame :test/o2} [:vector :string])
    (rf/reg-app-schema [:user]  {:frame :test/o2} [:map [:id :uuid]])
    (is (= (rf.schemas/app-schemas-digest :test/o1)
           (rf.schemas/app-schemas-digest :test/o2))
        "same schema set, different registration order → same digest")))

;; ---- rf2-froe — the validator-install seam --------------------------------
;;
;; Per Spec 010 §Non-Malli validators (rf2-froe) the validator and
;; explainer fns are pluggable through `(set-schema-fns! {:validate ...})` /
;; `(set-schema-fns! {:explain ...})`. Default delegates to Malli; apps
;; that want to drop the ~24 KB gzipped Malli surface (rf2-qnxf bundle
;; audit) substitute another fn (or `nil` for no-op).

(deftest default-validator-delegates-to-malli
  (testing "Per Spec 010 §Non-Malli validators — out of the box the
            validator delegates to Malli; apps that never install a
            validator of their own get the same behaviour they had
            before the seam landed."
    (rf/reg-app-schema [:n] [:int])
    (with-trace-recorder! [traces]
      ;; Malformed value triggers the default Malli validate to return
      ;; falsey -> trace fires.
      (rf.schemas/validate-app-schema! {:n "not-an-int"} :test/handler)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "default Malli validator catches the type mismatch")))))

(deftest custom-validator-is-invoked-instead-of-malli
  (testing "Per Spec 010 §Non-Malli validators — a `:validate` install swaps
            in any (fn [schema value] truthy?); the framework calls it on
            every validation site instead of the default."
    (let [calls (atom [])
          ;; Mock validator: fail on any value containing the substring
          ;; \"bad\"; pass everything else. Records every (schema, value)
          ;; pair it sees so the test can assert the call.
          custom (fn [schema value]
                   (swap! calls conj [schema value])
                   (not (and (string? value) (str/includes? value "bad"))))]
      (rf.schemas/set-schema-fns! {:validate custom})
      (rf/reg-app-schema [:label] :string)
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:label "hello"} :h/ok)        ;; passes
        (rf.schemas/validate-app-schema! {:label "totally-bad"} :h/no)  ;; fails
        (is (= 2 (count @calls))
            "custom validator was invoked for both validation calls")
        (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "exactly one trace fired — for the value the custom validator rejected")
          (is (= "totally-bad" (-> violations first :tags :value))))))))

(deftest nil-validator-disables-validation-on-every-surface
  (testing "Per Spec 010 §Non-Malli validators (rf2-rbbmt dedup D1) —
            installing an explicit nil `:validate` disables validation
            entirely; every meta-bearing validate-*! fn AND the app-db
            walker return true without inspecting the schema, and no
            trace fires. Parameterised over the surfaces that previously
            duplicated the no-op assertion as separate deftests."
    (rf.schemas/set-schema-fns! {:validate nil})
    (with-trace-recorder! [traces]
      (rf/reg-app-schema [:n] [:int])
      ;; Each malformed value would fire a trace under the default
      ;; (Malli) validator; with nil installed every call short-circuits
      ;; to true and emits nothing.
      (is (true? (rf.schemas/validate-app-schema! {:n "bad"} :test/h))
          "app-db walk: nil validator → true, no trace")
      (is (true? (rf.schemas/validate-event! :ev/x [:ev/x "bad"]
                                          {:schema [:cat [:= :ev/x] :int]}))
          "event: nil validator → true")
      (is (true? (rf.schemas/validate-sub! :sub/x [:sub/x] [1] {:schema [:vector :string]}))
          "sub-return: nil validator → true")
      (is (true? (rf.schemas/validate-fx! :fx/x :ev/o {:x "bad"} {:schema [:map [:x :int]]}))
          "fx-args: nil validator → true")
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "nil validator: no validation, no trace, no surprise — on any surface"))))

(deftest nil-validator-disables-validation-end-to-end-via-dispatch
  (testing "Per Spec 010 §Non-Malli validators — the nil-validator no-op
            also holds through a live dispatch: the handler runs even on
            a payload that would fail the registered :schema, and no
            pre-handler validation trace fires."
    (rf.schemas/set-schema-fns! {:validate nil})
    (let [calls (atom 0)]
      (rf/reg-event :user/strict
        {:schema [:cat [:= :user/strict] :int]}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:user/strict "not-an-int"])
        (is (= 1 @calls)
            "handler ran — nil validator means no pre-handler check")
        (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                            @traces))
            "no validation trace fires when validator is nil")))))

(deftest set-schema-fns-bundle-installs-both-fns
  (testing "Per Spec 010 §Non-Malli validators (rf2-13meg) — the bundle
            setter set-schema-fns! installs validate and explain
            atomically. Apps that want a custom explainer alongside
            their custom validator use this form."
    (let [validate-calls (atom 0)
          explain-calls  (atom 0)
          v-fn (fn [_s v] (swap! validate-calls inc) (= v :good))
          e-fn (fn [s v]  (swap! explain-calls inc) {:my-explanation [s v]})]
      (rf.schemas/set-schema-fns! {:validate v-fn :explain e-fn})
      (rf/reg-app-schema [:k] :keyword)
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:k :good}   :h/pass)
        (rf.schemas/validate-app-schema! {:k :nope}   :h/fail)
        (is (= 2 @validate-calls) "custom validate fn ran for both calls")
        (is (= 1 @explain-calls)  "custom explain fn ran only on the failure path")
        (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (is (= {:my-explanation [:keyword :nope]}
                 (-> violations first :tags :explain))
              "the trace's :explain key carries the custom explainer's output"))))))

(deftest set-schema-fns-installs-all-three-atomically
  (testing "rf2-13meg — the honest bundle setter set-schema-fns! installs
            validator, explainer, AND printer in one atomic call. The
            name says it sets the whole schema-language-fn bundle, not
            just the validator."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:explained true})
          p-fn (fn [_] "::BUNDLE-PRINTER::")]
      (rf.schemas/set-schema-fns! {:validate v-fn :explain e-fn :print p-fn})
      (is (= v-fn @rf.schemas.validator/validator-fn) "validator installed")
      (is (= e-fn @rf.schemas.validator/explainer-fn) "explainer installed")
      (is (= p-fn @rf.schemas.validator/printer-fn)   "printer installed")
      (is (= "::BUNDLE-PRINTER::" (rf.schemas.validator/run-printer :int))
          "printer reaches the hot path"))))

(deftest a-validate-only-install-leaves-explainer-and-printer-untouched
  (testing "rf2-13meg / rf2-kuky.39 — an install writes ONLY the keys it
            carries. `{:validate f}` swaps the validator and leaves the
            explainer and printer at their defaults, which is what makes
            a per-fn setter unnecessary: the subset IS the single-purpose
            call."
    (let [default-explainer @rf.schemas.validator/explainer-fn
          default-printer   @rf.schemas.validator/printer-fn
          v-fn (fn [_ _] true)]
      (rf.schemas/set-schema-fns! {:validate v-fn})
      (is (= v-fn @rf.schemas.validator/validator-fn) "validator installed")
      (is (= default-explainer @rf.schemas.validator/explainer-fn)
          "explainer untouched — an omitted key is not a write")
      (is (= default-printer @rf.schemas.validator/printer-fn)
          "printer untouched — an omitted key is not a write"))))

(deftest an-explain-only-install-leaves-the-validator-untouched
  (testing "an `:explain` install swaps just the explainer; the validator
            (default Malli) keeps catching real failures."
    (let [explained (atom nil)
          custom-e  (fn [_s v] (reset! explained v) {:custom-said v})]
      ;; Validator stays at its default (Malli); only the explainer is
      ;; substituted.
      (rf.schemas/set-schema-fns! {:explain custom-e})
      (rf/reg-app-schema [:n] [:int])
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:n "broken"} :h/oops)
        (is (= "broken" @explained) "custom explainer ran with the bad value")
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (= {:custom-said "broken"} (-> v :tags :explain))))))))

(deftest nil-explainer-fires-trace-with-nil-explain
  (testing "rf2-ynjts.12 — when the installed `:explain` is nil, the
            VALIDATOR still catches failures and the trace still fires;
            run-explainer's nil arm returns nil so the trace's :explain
            slot is nil (the explainer seam is independent of the
            validator seam). This pins the documented 'nil = no
            explanation attached' contract — the failure path must not
            depend on a registered explainer to emit, and a custom
            validator with no explainer is a supported substitute-Malli
            configuration. Covers run-explainer's nil branch
            (validator.cljc) at every meta-bearing emit site plus the
            app-db walk."
    ;; Custom validator that fails everything; explainer nilled. The
    ;; default Malli explainer is replaced by nil so the failure branch
    ;; threads `nil` through `:explain` rather than a Malli explanation.
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] false)})
    (rf.schemas/set-schema-fns! {:explain nil})
    (rf/reg-app-schema [:n] [:int])
    (with-trace-recorder! [traces]
      ;; app-db walk emit site.
      (is (false? (rf.schemas/validate-app-schema! {:n "bad"} :h/app-db)))
      ;; the three meta-bearing emit sites (run-validation core).
      (is (false? (rf.schemas/validate-event! :ev/x [:ev/x "bad"]
                                           {:schema [:cat [:= :ev/x] :int]})))
      (is (false? (rf.schemas/validate-sub! :sub/x [:sub/x] [1]
                                         {:schema [:vector :string]})))
      (is (false? (rf.schemas/validate-fx! :fx/x :ev/o {:x "bad"}
                                        {:schema [:map [:x :int]]})))
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 4 (count violations))
            "every emit site fired its trace even with no explainer registered")
        (doseq [v violations]
          (is (contains? (:tags v) :explain)
              "the :explain key is present at every emit site")
          (is (nil? (-> v :tags :explain))
              ":explain is nil — run-explainer's nil-explainer arm returned nil"))))))

(deftest installing-default-schema-fns-restores-defaults
  (testing "`(set-schema-fns! default-schema-fns)` brings the framework
            default back — the whole of what a reset verb used to do, as
            an ordinary install of a public value. After it, the default
            Malli behaviour resumes."
    ;; Install a sabotage validator: passes everything (so a known-bad
    ;; value would slip past).
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] true)})
    (rf/reg-app-schema [:n] [:int])
    ;; First confirm the sabotage is in effect.
    (with-trace-recorder! [traces]
      (rf.schemas/validate-app-schema! {:n "bad"} :h/sabotage)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "sabotage validator passes everything — no trace"))
    ;; Reset back to default Malli, retry the bad value, expect a trace.
    (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
    (with-trace-recorder! [traces]
      (rf.schemas/validate-app-schema! {:n "bad"} :h/back-to-default)
      (is (= 1 (count (filter #(= :rf.error/schema-validation-failure (:operation %))
                              @traces)))
          "default Malli validator is back in place — bad value catches"))))

(deftest validate-with-registered-fn-bypasses-debug-gate
  (testing "Per rf2-r2uh integration — validate-with-registered-fn is the
            public seam the boundary-validation interceptor will call. It
            does NOT consult interop/debug-enabled? (the boundary
            interceptor runs in production by design); it routes through
            the registered validator the same way the dev hot path does."
    (rf.schemas/set-schema-fns! {:validate (fn [_ v] (= v :good))})
    (with-redefs [rf.interop/debug-enabled? false]
      (is (true?  (rf.schemas/validate-with-registered-fn :keyword :good))
          "valid value passes — debug gate ignored")
      (is (false? (rf.schemas/validate-with-registered-fn :keyword :bad))
          "invalid value fails — debug gate ignored"))))

(deftest validator-set-via-public-api-is-visible-on-schemas-ns
  (testing "A `:validate` install on the public `re-frame.schemas` door
            flows through to the namespace's validator-fn atom."
    (let [my-fn (fn [_ _] :sentinel)]
      (rf.schemas/set-schema-fns! {:validate my-fn})
      (is (= my-fn @rf.schemas.validator/validator-fn)
          "the atom carries the fn the user registered"))))

;; ---- rf2-pk8ur — the `:print` public-surface contract ---------------------
;;
;; Per Spec 010 §Schema digest line 491 (rf2-wla45) the printer fn is the
;; third leg of the validator-surface seam: substitute-Malli ports register
;; their own (validate, explain, print) triple so the digest reflects the
;; port's own serialisation contract rather than the framework's Malli-EDN
;; default. The artefact-side contract — atom swap, default fallback,
;; hot-path read — is locked by `printer_seam_test.clj`.
;;
;; This file pins the PUBLIC-SURFACE contract: a `:print` install on the
;; public `re-frame.schemas` door reaches the artefact's printer atom +
;; run-printer hot path + digest pipeline. Parallel to `validator-set-via-
;; public-api-is-visible-on-schemas-ns` above; closes the rf2-kp835 Phase-1
;; audit gap (the public symbol had no end-to-end caller exercising the
;; wiring).

(deftest printer-set-via-public-api-is-visible-on-schemas-ns
  (testing "rf2-pk8ur — a `:print` install on the public door reaches the
            schemas artefact's printer-fn atom. Parallels the `:validate`
            and `:explain` end-to-end pins above."
    (let [my-fn (fn [_schema-value] "::PUBLIC-SURFACE::")]
      (rf.schemas/set-schema-fns! {:print my-fn})
      (is (= my-fn @rf.schemas.validator/printer-fn)
          "the atom carries the printer the public-surface caller registered")
      (is (= "::PUBLIC-SURFACE::" (rf.schemas.validator/run-printer :int))
          "run-printer's hot path reaches the public-surface registration"))))

(deftest printer-set-via-public-api-flips-digest-bytes
  (testing "rf2-pk8ur — the canonical end-to-end use of the public surface:
            a custom printer registered through the `:print` key changes
            the digest pipeline's output. This is what a non-Malli port
            (a Zod port, a clojure.spec port) does at boot — and what the
            existing 0-caller audit on the public symbol failed to
            exercise. Spec 010 §Schema digest line 491: 'two ports using
            different schema languages produce different digests by
            construction'."
    (rf/reg-app-schema [:n] :int)
    (let [default-digest (rf.schemas/app-schemas-digest)]
      (rf.schemas/set-schema-fns! {:print (fn [_] "::CUSTOM-PORT::")})
      (let [custom-digest (rf.schemas/app-schemas-digest)]
        (is (re-matches #"^sha256:[0-9a-f]{16}$" custom-digest)
            "digest is still the wire-form '\"sha256:\" + 16-hex'")
        (is (not= default-digest custom-digest)
            "registering a different printer through the public surface
             produces a different digest — the bytes the printer emits
             are what the digest pipeline hashes")))))

(deftest printer-set-via-public-api-nil-restores-default
  (testing "rf2-pk8ur — installing a nil `:print` on the public door
            reinstalls the default EDN canonicaliser. The digest is
            never undefined for a present schema set, even after a
            port-specific printer has been registered and then
            withdrawn. Mirrors the artefact-side
            `set-schema-fns!-nil-print-coerces-to-default` test."
    (rf.schemas/set-schema-fns! {:print (fn [_] "::TRANSIENT::")})
    (is (= "::TRANSIENT::" (rf.schemas.validator/run-printer :int)))
    (rf.schemas/set-schema-fns! {:print nil})
    (is (= ":int" (rf.schemas.validator/run-printer :int))
        "nil through the public surface falls back to default-edn-print")))

(deftest printer-set-via-public-set-schema-fns-installs-printer
  (testing "rf2-pk8ur + rf2-13meg — the public rf/set-schema-fns! bundle
            setter installs a `:print` printer alongside `:validate` /
            `:explain` atomically. End-to-end pin of the documented
            one-call substitute-Malli boot pattern via the public surface."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:explained true})
          p-fn (fn [_] "::FROM-PUBLIC-BUNDLE::")]
      (rf.schemas/set-schema-fns! {:validate v-fn :explain e-fn :print p-fn})
      (is (= v-fn @rf.schemas.validator/validator-fn))
      (is (= e-fn @rf.schemas.validator/explainer-fn))
      (is (= p-fn @rf.schemas.validator/printer-fn))
      (is (= "::FROM-PUBLIC-BUNDLE::" (rf.schemas.validator/run-printer :int))
          "the printer installed via the bundle setter reaches the hot path"))))

;; ---- rf2-l4ljvr / rf2-kuky.39 — capture and reinstate a bundle -----------
;;
;; The validator/explainer/printer BUNDLE companion to the registry's
;; snapshot-schemas-by-frame / restore-schemas-by-frame! (tested below).
;; rf2-l4ljvr first closed the asymmetry — before it there was only a verb
;; that restored the framework DEFAULT, so a test wanting to capture a
;; custom bundle and later reinstate it hand-rolled the read from raw
;; `@validator-fn` derefs (the routing/ssr/ssr-ring `with-stub-validator`
;; fixtures). rf2-kuky.39 then dropped the dedicated snapshot/restore verbs:
;; `schema-fns` is the read, `set-schema-fns!` is the install, and the pair
;; round-trips, so capture-and-reinstate is a `let` over a value and
;; consumers still never touch the raw atoms.

(deftest schema-fns-captures-live-bundle
  (testing "rf2-l4ljvr — `schema-fns` returns the live
            validator/explainer/printer triple in set-schema-fns! shape"
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:explained true})
          p-fn (fn [_] "::SNAPSHOT-ME::")]
      (rf.schemas/set-schema-fns! {:validate v-fn :explain e-fn :print p-fn})
      (let [snap (rf.schemas/schema-fns)]
        (is (= #{:validate :explain :print} (set (keys snap)))
            "snapshot is the {:validate :explain :print} bundle shape")
        (is (= v-fn (:validate snap)) "captures the live validator")
        (is (= e-fn (:explain snap))  "captures the live explainer")
        (is (= p-fn (:print snap))    "captures the live printer")))))

(deftest installing-a-captured-bundle-reinstates-it
  (testing "rf2-l4ljvr — a captured bundle faithfully round-trips through
            the installer: read a custom bundle, mutate to a different one,
            install the captured value and it is reinstated (all three fns
            + the run-printer hot path)"
    (let [v1 (fn [_ _] true)
          e1 (fn [_ _] {:reason :first})
          p1 (fn [_] "::FIRST::")]
      ;; Install bundle 1 and snapshot it.
      (rf.schemas/set-schema-fns! {:validate v1 :explain e1 :print p1})
      (let [snap (rf.schemas/schema-fns)]
        ;; Mutate to a completely different bundle.
        (rf.schemas/set-schema-fns! {:validate (fn [_ _] false)
                             :explain  (fn [_ _] {:reason :second})
                             :print    (fn [_] "::SECOND::")})
        (is (= "::SECOND::" (rf.schemas.validator/run-printer :int))
            "mid-state: the second bundle is live")
        ;; Restore bundle 1.
        (let [ret (rf.schemas/set-schema-fns! snap)]
          (is (= v1 @rf.schemas.validator/validator-fn) "validator restored")
          (is (= e1 @rf.schemas.validator/explainer-fn) "explainer restored")
          (is (= p1 @rf.schemas.validator/printer-fn)   "printer restored")
          (is (= "::FIRST::" (rf.schemas.validator/run-printer :int))
              "run-printer's hot path observes the restored printer")
          (is (= snap ret)
              "the install returns the bundle it installed"))))))

(deftest installing-a-bundle-with-nil-print-coerces-to-default
  (testing "rf2-l4ljvr + rf2-ee38b.6 — installing a bundle whose :print is
            nil coerces it to default-edn-print (the printer-never-nil
            invariant run-printer relies on)"
    ;; A hand-built bundle with an explicit nil :print (`schema-fns`
    ;; never produces nil :print, but the install path must stay safe).
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] true)
                             :explain  nil
                             :print    nil})
    (is (some? @rf.schemas.validator/printer-fn)
        "printer-fn is never nil after restore — coerced to the default")
    (is (= ":int" (rf.schemas.validator/run-printer :int))
        "run-printer reaches the default EDN canonicaliser, no read-site guard")))

(deftest snapshot-restore-bundle-composes-with-registry-pair
  (testing "rf2-l4ljvr — the bundle snapshot/restore pair composes with
            the registry snapshot/restore pair: capturing+restoring BOTH
            reinstates the whole schema runtime (per-frame registry AND the
            pluggable bundle) through the encapsulated API, no raw atoms"
    (let [v-fn (fn [_ _] true)
          p-fn (fn [_] "::COMPOSED::")]
      ;; Establish a known runtime: a custom bundle + a registered schema.
      (rf.schemas/set-schema-fns! {:validate v-fn :print p-fn})
      (rf/reg-app-schema [:n] [:int])
      ;; Capture BOTH the bundle and the registry.
      (let [bundle-snap   (rf.schemas/schema-fns)
            registry-snap (rf.schemas/snapshot-schemas-by-frame)]
        ;; Tear the whole runtime down to a different state.
        (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
        (rf.schemas/clear-schemas-by-frame!)
        (is (not= v-fn @rf.schemas.validator/validator-fn) "bundle was reset away")
        (is (= {} @rf.schemas.storage/schemas-by-frame)  "registry was cleared")
        ;; Restore BOTH through the encapsulated API.
        (rf.schemas/set-schema-fns! bundle-snap)
        (rf.schemas/restore-schemas-by-frame! registry-snap)
        (is (= v-fn @rf.schemas.validator/validator-fn) "bundle validator restored")
        (is (= "::COMPOSED::" (rf.schemas.validator/run-printer :int))
            "bundle printer restored on the hot path")
        (is (= [:int] (rf.schemas/app-schema-at [:n]))
            "registry schema restored — the two pairs compose")))))

;; ---- rf2-kuky.39 — the validator port as a value --------------------------
;;
;; Three names carry the whole port: `set-schema-fns!` installs,
;; `schema-fns` reads, `default-schema-fns` is the framework's own bundle.
;; These pin the properties that let the other six names go.

(deftest default-schema-fns-is-a-plain-three-key-bundle
  (testing "rf2-kuky.39 — `default-schema-fns` is an ordinary map carrying
            exactly the three keys the installer accepts, so it can be
            passed straight back to `set-schema-fns!` and destructured by
            a port that wants to wrap one of the defaults."
    (is (map? rf.schemas/default-schema-fns))
    (is (= #{:validate :explain :print} (set (keys rf.schemas/default-schema-fns)))
        "exactly the installer's key set — no extras, none missing")
    (is (every? fn? (vals rf.schemas/default-schema-fns))
        "every default is a callable fn; the framework default never nils a key")))

(deftest installing-default-schema-fns-restores-using-default-validator?
  (testing "rf2-kuky.39 — `using-default-validator?` (the
            :rf.warning/schema-validator-unavailable discriminator) answers
            true again after installing `default-schema-fns`, because the
            value carries the SAME fn objects the atoms were seeded with.
            An equal-but-distinct fn would fail this — the check is
            `identical?` — which is why the defaults are exposed as a value
            rather than rebuilt by the caller."
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] true)})
    (is (false? (rf.schemas.validator/using-default-validator?))
        "a custom validator is not the framework default")
    (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
    (is (true? (rf.schemas.validator/using-default-validator?))
        "installing the defaults value restores the identity, not just the shape")))

(deftest an-omitted-key-differs-from-an-explicit-nil
  (testing "rf2-kuky.39 — the distinction the installer is built on: an
            OMITTED key leaves the live registration alone, while an
            EXPLICIT nil writes nil (disabling that fn). Collapsing the two
            would make a partial install unsafe."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:explained true})]
      (rf.schemas/set-schema-fns! {:validate v-fn :explain e-fn})
      ;; Omit :validate entirely — it must survive.
      (rf.schemas/set-schema-fns! {:explain nil})
      (is (= v-fn (:validate (rf.schemas/schema-fns)))
          "the omitted :validate key kept its prior value")
      (is (nil? (:explain (rf.schemas/schema-fns)))
          "the explicit nil :explain disabled the explainer")
      ;; Now nil the validator explicitly.
      (rf.schemas/set-schema-fns! {:validate nil})
      (is (nil? (:validate (rf.schemas/schema-fns)))
          "an explicit nil :validate disables validation"))))

(deftest schema-fns-round-trips-through-the-installer
  (testing "rf2-kuky.39 — `(set-schema-fns! (schema-fns))` is a no-op, which
            is what makes let + finally the whole of test isolation."
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] false)
                                 :explain  nil
                                 :print    (fn [_] "::ROUND-TRIP::")})
    (let [before (rf.schemas/schema-fns)]
      (is (= before (rf.schemas/set-schema-fns! before))
          "installing the read value returns that same value")
      (is (= before (rf.schemas/schema-fns))
          "and leaves the live state untouched"))))

;; ---- rf2-r2uh — :rf.schema/at-boundary interceptor ---------------------
;;
;; Per Spec 010 §Production builds — the boundary-validation interceptor
;; runs the handler's :schema check inline in production builds (where
;; dev-time validation has been elided). Re-uses the dev-time validator
;; seam (rf2-froe) so a substituted validator covers both surfaces.
;;
;; The interceptor's dev/prod gate is `re-frame.spec/dev-mode?` — a
;; private fn wrapping `interop/debug-enabled?`. The indirection lets
;; tests rebind the boundary's dev-vs-prod decision INDEPENDENTLY of
;; the trace surface's `interop/debug-enabled?` read, so a JVM test can
;; (a) keep `debug-enabled?` true so emit-error! / emit! actually fire
;; their bodies, and (b) flip `dev-mode?` to false so the boundary
;; takes its production validation branch.
;;
;; In genuine `:advanced` + `goog.DEBUG=false` production both flags
;; resolve to false together: the boundary validates inline, but the
;; trace surface elides — so the handler-skip is silent. The tests
;; below are JVM tests that decouple the two flags to make the
;; emission observable.

(deftest boundary-interceptor-passes-valid-event-through
  (testing "Per Spec 010 §Production builds (rf2-r2uh) — a valid event
            against the handler's :schema passes through, the handler runs."
    (let [calls (atom 0)]
      (rf/reg-event :api/response
        {:schema [:cat [:= :api/response]
                     [:map [:status :int] [:body :string]]]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ [_ payload]]
          (swap! calls inc)
          {:db {:last-response payload}}))
      (with-trace-recorder! [traces]
        ;; Production build path — flip the boundary's gate without
        ;; killing the trace surface. The router's step-1
        ;; validation also fires (debug-enabled? still true on JVM)
        ;; and passes for the well-typed payload, so the chain runs
        ;; and the boundary interceptor then validates again — both
        ;; passes silently.
        (with-redefs [rf.spec/dev-mode? (constantly false)]
          (rf/dispatch-sync [:api/response {:status 200 :body "OK"}]))
        (is (= 1 @calls)
            "handler ran exactly once for the well-typed payload")
        (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                            @traces))
            "no validation-failure trace fired for the valid payload")))))

(deftest boundary-interceptor-skips-handler-on-invalid-event
  (testing "Per Spec 010 §Production builds (rf2-r2uh) — an invalid event
            against the handler's :schema causes the handler to be
            skipped. Under genuine `:advanced` + `goog.DEBUG=false` the
            router's step-1 validate-event! body elides and the
            boundary path is the only validation site; on the JVM
            test the router's step-1 also fires (debug-enabled? is
            true), but the handler-skip behaviour is what the spec
            promises in either path."
    (let [calls (atom 0)]
      (rf/reg-event :api/response
        {:schema [:cat [:= :api/response]
                     [:map [:status :int] [:body :string]]]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ [_ payload]]
          (swap! calls inc)
          {:db {:last-response payload}}))
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (rf/dispatch-sync [:api/response {:status "not-an-int" :body 42}]))
      (is (= 0 @calls)
          "handler was skipped on the malformed payload"))))

(deftest boundary-interceptor-emits-failure-trace-with-source-tag
  (testing "Per Spec 010 L149 — the boundary failure trace flows through
            the same `:rf.error/schema-validation-failure :where :event`
            path as dev-mode step-1 failures, and carries `:source
            :boundary` so consumers can distinguish the boundary
            emission from the dev step-1 emission.

            We exercise the trace shape via direct interceptor
            invocation — the dispatch path's router-side step-1
            short-circuits the chain when the schema fails, so the
            boundary :before never reaches its emit body. Direct
            invocation isolates the boundary's emission for shape
            assertion."
    (rf/reg-event :api/strict
      {:schema [:cat [:= :api/strict] :int]
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (with-trace-recorder! [traces]
      ;; dev-mode? false → boundary takes its prod branch; but
      ;; debug-enabled? stays true on the JVM so emit-error! actually
      ;; fires its body and the trace is observable.
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (let [before (:before rf/validate-at-boundary-interceptor)]
          (before {:coeffects {:event [:api/strict "not-an-int"]}})))
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired from the boundary path")
        (let [v (first violations)]
          (is (= :event (-> v :tags :where))
              ":where is :event — same path as dev-mode step-1 failures (Spec 010 L149)")
          (is (= :api/strict (-> v :tags :event-id))
              ":event-id names the boundary-validated handler")
          (is (= :api/strict (-> v :tags :failing-id)))
          (is (= :api/strict (-> v :tags :schema-id)))
          (is (= :boundary (-> v :tags :source))
              ":source :boundary tags this as the boundary emission")
          (is (= [:api/strict "not-an-int"] (-> v :tags :received))
              ":received carries the failing event vector verbatim")
          (is (= [:api/strict "not-an-int"] (-> v :tags :value))
              ":value mirrors :received per Spec 010 §`:sensitive?`")
          (is (not (contains? (:tags v) :event))
              ":event slot is gone (rf2-4fbsd) — consumers reach for :received")
          (is (string? (-> v :tags :reason))
              ":reason carries a human-readable explanation per Spec 009 §Style rubric")
          (is (= :no-recovery (:recovery v))
              ":recovery is :no-recovery — handler is not invoked"))))))

(deftest boundary-interceptor-sets-skip-handler-on-context
  (testing "Per Spec 010 §Per-step recovery step 1 — the boundary
            interceptor's :before sets :rf/skip-handler? on the context
            when validation fails, so the handler-as-interceptor (the single
            EP-0018 framework wrapper events.cljc :rf/event-handler)
            short-circuits.

            The recovery is identical to the dev-mode step-1 path
            (validate-event! returning false), so the runtime's existing
            skip mechanism carries the boundary failure through without
            additional plumbing."
    (rf/reg-event :api/strict
      {:schema [:cat [:= :api/strict] :int]
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    ;; Direct invocation of the interceptor's :before fn — gives us a
    ;; deterministic surface for asserting the recovery contract
    ;; without the dispatch's other moving parts. Production-side path
    ;; (dev-mode? false); the boundary interceptor takes its prod
    ;; branch and validates inline.
    (with-redefs [rf.spec/dev-mode? (constantly false)]
      (let [before    (:before rf/validate-at-boundary-interceptor)
            valid-ctx (before {:coeffects {:event [:api/strict 42]}})
            bad-ctx   (before {:coeffects {:event [:api/strict "not-an-int"]}})]
        (is (not (:rf/skip-handler? valid-ctx))
            ":rf/skip-handler? unset when the event conforms — handler will run")
        (is (true? (:rf/skip-handler? bad-ctx))
            ":rf/skip-handler? set when the event fails the schema — handler is skipped")))))

(deftest boundary-interceptor-honours-custom-validator
  (testing "Per Spec 010 §Boundary-validation seam (rf2-froe + rf2-r2uh) —
            the boundary interceptor routes through the registered
            validator the same way the dev-time hot path does. A
            substituted validator covers both surfaces with one
            registration."
    ;; Sentinel custom validator — passes the literal :good value,
    ;; fails everything else. Records every call. We use a
    ;; predicate that overrides Malli so we can observe routing
    ;; through the late-bind hook end-to-end.
    (let [validator-calls (atom 0)
          custom (fn [_schema value]
                   (swap! validator-calls inc)
                   (= value [:api/custom :good]))
          handler-calls (atom 0)]
      (rf.schemas/set-schema-fns! {:validate custom})
      (rf/reg-event :api/custom
        {:schema :rf/any                     ;; opaque to the custom validator
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! handler-calls inc) {}))
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        ;; Direct :before invocation so we observe the boundary's
        ;; validator call path without the router-side step-1 also
        ;; firing the same custom validator (which would double-count
        ;; the calls).
        (let [before (:before rf/validate-at-boundary-interceptor)
              ok     (before {:coeffects {:event [:api/custom :good]}})
              bad    (before {:coeffects {:event [:api/custom :bad]}})]
          (is (not (:rf/skip-handler? ok))
              "custom validator passed — boundary did not set :rf/skip-handler?")
          (is (true? (:rf/skip-handler? bad))
              "custom validator failed — boundary set :rf/skip-handler?")
          (is (>= @validator-calls 2)
              "custom validator was invoked at least once per boundary check"))))))

(deftest boundary-interceptor-noop-when-validator-is-nil
  (testing "Per Spec 010 §Non-Malli validators — set-schema-fns! {:validate nil}
            disables every validation surface, including the boundary
            interceptor. The handler runs even with a malformed payload."
    (rf.schemas/set-schema-fns! {:validate nil})
    (let [calls (atom 0)]
      (rf/reg-event :api/disabled
        {:schema [:cat [:= :api/disabled] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (with-trace-recorder! [traces]
        (with-redefs [rf.spec/dev-mode? (constantly false)]
          (rf/dispatch-sync [:api/disabled "wildly-malformed"]))
        (is (= 1 @calls)
            "handler ran — nil validator means no boundary check")
        (is (empty? (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                  (= :boundary (-> % :tags :source)))
                            @traces))
            "no boundary-emitted validation trace fires when the validator is nil")))))

(deftest boundary-interceptor-noop-in-dev-mode
  (testing "Per Spec 010 L145 — in dev builds (dev-mode? true), the
            boundary interceptor is a no-op. Dev-mode step-1
            validation in the router has already run; the boundary
            interceptor doesn't validate a second time."
    (let [calls (atom 0)]
      (rf/reg-event :api/dev
        {:schema [:cat [:= :api/dev] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (with-trace-recorder! [traces]
        ;; Dev mode (the JVM default). The router's step-1
        ;; validate-event! call fires for the malformed payload; the
        ;; boundary interceptor SHOULD NOT fire a second trace.
        (rf/dispatch-sync [:api/dev "not-an-int"])
        (is (= 0 @calls)
            "handler skipped — but by the dev-mode step-1 path, not the boundary")
        (let [boundary-violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                                (= :boundary (-> % :tags :source)))
                                          @traces)]
          (is (empty? boundary-violations)
              "no boundary-tagged trace fired — only the dev-mode step-1 trace ran"))))))

(deftest boundary-without-schema-rejected-at-registration
  (testing "Per Spec 010 §Production builds + rf2-iftj4 — a registration
            that attaches `:rf.schema/at-boundary` but carries no
            `:schema` metadata is rejected at registration time with
            `:rf.error/at-boundary-missing-schema`. Pre-rf2-iftj4 the
            warning fired at first dispatch in production builds only;
            now the misconfiguration surfaces immediately regardless of
            dev/prod gate."
    (testing "metadata :interceptors without :schema"
      (let [calls (atom 0)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/at-boundary-missing-schema"
              (rf/reg-event :api/no-schema-2
                {:interceptors [:rf.schema/at-boundary]}
                (fn [_ _] (swap! calls inc) {}))))
        (let [data (try (rf/reg-event :api/no-schema-2-data
                          {:interceptors [:rf.schema/at-boundary]}
                          (fn [_ _] {}))
                        (catch clojure.lang.ExceptionInfo e
                          (ex-data e)))]
          (is (= :rf.error/at-boundary-missing-schema (:rf.error/id data)))
          (is (= "reg-event" (:reg-fn data)))
          (is (= :api/no-schema-2-data (:id data)))
          (is (string? (:reason data)))
          (is (str/includes? (:reason data) ":rf.schema/at-boundary"))
          (is (str/includes? (:reason data) ":schema"))
          (is (= :no-recovery (:recovery data))))
        (is (= 0 @calls) "handler never invoked — registration rejected")))

    (testing "metadata-map without :schema + interceptors"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event :api/no-schema-3
              {:doc "metadata-map but no :schema"
               :interceptors [:rf.schema/at-boundary]}
              (fn [_ _] {})))))

    (testing "rejection covers a db-shaped handler and a full-context interceptor as well"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event :api/db-no-schema
              {:interceptors [:rf.schema/at-boundary]}
              (fn [{:keys [db]} _] {:db db}))))
      ;; EP-0022 reference-only flip (rf2-0adhqs.9): chains carry refs only,
      ;; so the full-context probe is registered and referenced by id alongside
      ;; the boundary ref. The missing-`:schema` rejection still fires (it runs
      ;; after the reference-shape validation, which both refs pass).
      (rf/reg-interceptor :api/ctx-probe {:before (fn [ctx] ctx)})
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event :api/ctx-no-schema
              {:interceptors [:rf.schema/at-boundary :api/ctx-probe]}
              (fn [_ _] {})))))

    (testing "registration with `:schema` + validate-at-boundary-interceptor completes silently"
      (is (= :api/with-schema
             (rf/reg-event :api/with-schema
               {:schema [:cat [:= :api/with-schema] :int]
                :interceptors [:rf.schema/at-boundary]}
               (fn [_ _] {})))
          "registration returns the event id when the metadata carries :schema"))

    (testing "registration without validate-at-boundary-interceptor is unaffected by the new check"
      (is (= :api/no-boundary
             (rf/reg-event :api/no-boundary
               (fn [_ _] {})))
          "no validate-at-boundary-interceptor, no schema, no error")
      (is (= :api/just-meta
             (rf/reg-event :api/just-meta
               {:doc "no boundary, no schema"}
               (fn [_ _] {})))
          "metadata-map without :schema is fine when validate-at-boundary-interceptor isn't attached"))))

;; ---- snapshot / restore / clear schemas-by-frame (rf2-6lka) --------------
;;
;; Per Spec 010 / rf2-h96i and schemas.cljc:748: the per-frame schema
;; registry is fixture-friendly via three test-support hooks:
;;
;;   (schemas/snapshot-schemas-by-frame)  ;; capture current state
;;   (schemas/clear-schemas-by-frame!)    ;; drop everything
;;   (schemas/restore-schemas-by-frame! s) ;; rehydrate from snapshot
;;
;; These are the fixture-style affordance the test-support reset-runtime
;; fixture relies on; a wire-up regression would surface only in user
;; tooling.

(deftest snapshot-restore-clear-round-trip
  (testing "snapshot → clear → restore round-trips the schemas-by-frame
            atom byte-for-byte and validation still works after restore"
    ;; Set up two frames and register a schema under each. Per-frame
    ;; isolation is the load-bearing contract.
    (rf/make-frame {:id :test.6lka/other :doc "second frame for round-trip test"})
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-app-schema [:label] {:frame :test.6lka/other} [:string])

    ;; 1. Snapshot.
    (let [snap (rf.schemas/snapshot-schemas-by-frame)]
      (is (map? snap) "snapshot is a map")
      (is (contains? snap :rf/default)
          "snapshot covers :rf/default")
      (is (contains? snap :test.6lka/other)
          "snapshot covers :test.6lka/other")
      ;; Schemas are keyed by their full path (a vector) inside the
      ;; per-frame map; the storage shape is {frame-id {path meta}}.
      (is (some? (get-in snap [:rf/default [:n]]))
          "snapshot retains the schema under [:rf/default [:n]]")
      (is (some? (get-in snap [:test.6lka/other [:label]]))
          "snapshot retains the schema under [:test.6lka/other [:label]]")

      ;; 2. Clear.
      (rf.schemas/clear-schemas-by-frame!)
      (is (= {} @rf.schemas.storage/schemas-by-frame)
          "clear-schemas-by-frame! emptied the atom")

      ;; 3. Restore.
      (rf.schemas/restore-schemas-by-frame! snap)
      (is (= snap @rf.schemas.storage/schemas-by-frame)
          "restore-schemas-by-frame! reproduces the atom byte-for-byte")

      ;; 4. Semantic faithfulness: validation against a restored
      ;;    schema fires exactly like it did before the round-trip.
      (with-trace-recorder! [traces]
        ;; A malformed value under [:n] on :rf/default — should fire.
        (rf.schemas/validate-app-schema! {:n "not-an-int"} :test.6lka/handler)
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "post-restore validation fires for malformed value — round-trip is semantically faithful")
          (is (= [:n] (-> violations first :tags :path))
              ":path tag identifies the registered schema"))))))

(deftest clear-empties-and-leaves-schemas-by-frame-empty
  (testing "clear-schemas-by-frame! drops all per-frame entries"
    (rf/reg-app-schema [:a] [:int])
    (rf/reg-app-schema [:b] [:string])
    (is (seq @rf.schemas.storage/schemas-by-frame)
        "pre-clear: registry is populated")
    (rf.schemas/clear-schemas-by-frame!)
    (is (empty? @rf.schemas.storage/schemas-by-frame)
        "post-clear: registry is empty")))

(deftest restore-replaces-not-merges
  (testing "restore-schemas-by-frame! REPLACES the atom (does not merge);
            schemas registered after the snapshot disappear on restore"
    ;; Capture an empty snapshot.
    (let [empty-snap (rf.schemas/snapshot-schemas-by-frame)]
      (is (= {} empty-snap)
          "fresh atom is empty (make-reset-runtime-fixture cleared it)")
      ;; Now register some schemas.
      (rf/reg-app-schema [:transient] [:int])
      (is (seq @rf.schemas.storage/schemas-by-frame)
          "post-reg: schemas present")
      ;; Restore to the empty snapshot.
      (rf.schemas/restore-schemas-by-frame! empty-snap)
      (is (= {} @rf.schemas.storage/schemas-by-frame)
          "restore replaced the atom — the transient schemas are gone, not merged"))))

;; ---- rf/reg-app-schemas (plural, rf2-jzs9) -------------------------------

(deftest reg-app-schemas-bulk-registers-map
  (testing "rf/reg-app-schemas registers every entry in the supplied {path -> schema} map"
    (rf/reg-app-schemas
      {[:auth]                  [:map [:user :string]]
       [:auth :token]           [:string]
       [:cart]                  [:map [:items [:vector :string]]]
       [:cart :items]           [:vector :string]})
    (is (= [:map [:user :string]]      (rf.schemas/app-schema-at [:auth])))
    (is (= [:string]                   (rf.schemas/app-schema-at [:auth :token])))
    (is (= [:map [:items [:vector :string]]] (rf.schemas/app-schema-at [:cart])))
    (is (= [:vector :string]           (rf.schemas/app-schema-at [:cart :items])))))

(deftest reg-app-schemas-returns-paths-registered
  (testing "rf/reg-app-schemas returns the vector of paths"
    (let [paths (rf/reg-app-schemas
                  {[:a] [:int]
                   [:b] [:int]
                   [:c] [:int]})]
      (is (= 3 (count paths)))
      (is (= #{[:a] [:b] [:c]} (set paths))
          "every input path appears in the returned vector"))))

(deftest reg-app-schemas-honours-frame-opt
  (testing "rf/reg-app-schemas applies the :frame opt to every entry"
    (rf/make-frame {:id :tenant/a})
    (rf/reg-app-schemas
      {[:auth] [:map [:user :string]]
       [:cart] [:map [:items :any]]}
      {:frame :tenant/a})
    (is (= [:map [:user :string]]
           (rf.schemas/app-schema-at [:auth] {:frame :tenant/a})))
    (is (= [:map [:items :any]]
           (rf.schemas/app-schema-at [:cart] {:frame :tenant/a})))
    (is (nil? (rf.schemas/app-schema-at [:auth]))
        "the default frame did NOT receive any of the entries")))

(deftest reg-app-schemas-empty-map-no-op
  (testing "rf/reg-app-schemas on an empty map is a no-op and returns an empty vector"
    (let [paths (rf/reg-app-schemas {})]
      (is (= [] paths))
      (is (= {} (rf.schemas/app-schemas))
          "no schemas registered on the active frame"))))

;; ---- rf2-naihn1 #2 — bulk-input false-green --------------------------------
;;
;; THE BUG (pre-fix): `reg-app-schemas` never checked that its first
;; argument is a `{path -> schema}` map. In Clojure `(keys nil)` is `nil`
;; and iterating `nil`/non-maps yields no entries, so `(reg-app-schemas
;; nil)` ran the up-front path sweep as a no-op, registered nothing, and
;; returned `[]` — INDISTINGUISHABLE from the documented `{}` no-op. A
;; boot/config/schema-loader bug passing `nil` (or any non-map) got a
;; FALSE GREEN: schema enforcement silently disabled for the whole batch.
;;
;; THE FIX: reject nil / non-map FIRST, before any store mutation, with
;; the explicit error id `:rf.error/app-schemas-bad-batch`. `{}` stays
;; the documented empty no-op (covered by the test above).
;;
;; NEGATIVE CONTROL: each rejecting case asserts the schema registry was
;; NOT mutated (the throw fires before the `swap!`), so the bad batch is
;; truly atomic-reject, not half-applied.

(deftest reg-app-schemas-rejects-nil-batch
  ;; rf2-naihn1 #2 — the load-bearing regression: pre-fix `(reg-app-schemas
  ;; nil)` returned `[]` (false green); post-fix it MUST throw.
  (testing "rf/reg-app-schemas rejects a nil first argument (not a silent no-op)"
    (let [before @rf.schemas.storage/schemas-by-frame]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf.error/app-schemas-bad-batch"
            (rf/reg-app-schemas nil))
          (str "nil batch must reject with :rf.error/app-schemas-bad-batch, "
               "not silently no-op to [] (the false-green this bead fixes)"))
      (is (= before @rf.schemas.storage/schemas-by-frame)
          (str "negative control: a rejected nil batch must NOT mutate the "
               "schema registry (throw fires before any swap!)")))))

(deftest reg-app-schemas-carries-error-id-on-nil
  (testing "the rejection ex-info carries the :rf.error/id error category"
    (let [ex (try (rf/reg-app-schemas nil) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "nil batch threw")
      (is (= :rf.error/app-schemas-bad-batch
             (:rf.error/id (ex-data ex)))
          "ex-data carries the explicit error category")
      (is (= nil (:received (ex-data ex)))
          "ex-data echoes the rejected first argument"))))

(deftest reg-app-schemas-rejects-non-map-batches
  ;; Representative non-map first arguments. Each previously iterated to
  ;; zero entries and returned [] (or, for a seq-of-pairs, would have
  ;; attempted to register garbage); all must now atomically reject.
  (testing "rf/reg-app-schemas rejects representative non-map first arguments"
    (doseq [bad [[]                          ; empty vector
                 [[:a] :int]                 ; flat vector that LOOKS like one entry
                 [[[:a] :int]]               ; a seq of [path schema] pairs
                 "schemas"                   ; a string
                 :a                          ; a keyword
                 42                          ; a number
                 #{[:a]}]]                   ; a set
      (testing (str "non-map arg " (pr-str bad))
        (let [before @rf.schemas.storage/schemas-by-frame]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo #":rf.error/app-schemas-bad-batch"
                (rf/reg-app-schemas bad))
              (str (pr-str bad) " must reject as a non-map batch"))
          (is (= before @rf.schemas.storage/schemas-by-frame)
              (str "negative control: rejected batch " (pr-str bad)
                   " must NOT mutate the schema registry")))))))

(deftest reg-app-schemas-empty-map-still-no-op-post-fix
  ;; Pin that the fix did NOT regress the documented `{}` no-op: the
  ;; empty map is a map, so it passes the new shape gate and returns [].
  (testing "rf/reg-app-schemas {} remains the documented no-op returning []"
    (is (= [] (rf/reg-app-schemas {})))
    (is (= {} (rf.schemas/app-schemas)))))

;; ---- rf2-ieu0i — :schema canonical ---------------------------------------
;;
;; Per Mike's decision at rf2-ieu0i the framework collapses the dual
;; vocabulary (`:spec` / `schema` / `validation` / `violation`) under a
;; single name — `:schema`. Alpha posture: no back-compat shims, no
;; deprecation aliases. v1→v2 rename is recorded in MIGRATION §M-54.

(deftest boundary-interceptor-reads-schema-key
  (testing "rf2-ieu0i — `:rf.schema/at-boundary` interceptor reads the
            canonical `:schema` key."
    ;; Verify the interceptor id was renamed.
    (is (= :rf.schema/at-boundary (:id rf/validate-at-boundary-interceptor))
        ":id of the boundary interceptor is :rf.schema/at-boundary (rf2-ieu0i)")
    ;; Canonical :schema path — validation reads :schema.
    (rf/reg-event :api/schema-key
      {:schema [:cat [:= :api/schema-key] :int]
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (with-redefs [rf.spec/dev-mode? (constantly false)]
      (let [before  (:before rf/validate-at-boundary-interceptor)
            valid   (before {:coeffects {:event [:api/schema-key 7]}})
            invalid (before {:coeffects {:event [:api/schema-key "no"]}})]
        (is (not (:rf/skip-handler? valid))
            ":schema metadata + valid payload → handler proceeds")
        (is (true? (:rf/skip-handler? invalid))
            ":schema metadata + invalid payload → handler skipped")))))
