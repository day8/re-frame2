(ns re-frame.machine-schema-arity-test
  "rf2-genufr + rf2-wgmipl — the single registration home, the fail-loud
  guard, and the public `reg-machine` event-vector `:schema` arity.

  Background. A machine carrying a `:data-schema` has TWO registration-time
  side-effects that BOTH must run or the schema is silently inert AND a
  privacy leak:

    1. the `:rf/machine?` / `:rf/machine` registration-metadata stamp — the
       `:where :machine-data` post-commit walker resolves the `:data-schema`
       THROUGH `(machine-meta id)`, so without it the schema validates
       nothing; and
    2. `register-data-schema-marks!` — bridges the schema's `:sensitive?` /
       `:large?` per-slot markers into snapshot-egress redaction.

  Before rf2-genufr, the direct `(reg-event-fx id meta (make-machine-handler
  spec))` path ran NEITHER automatically — the author had to hand-stamp the
  meta and the marks bridge never ran at all. `make-machine-handler` is now
  the fail-loud guard: a `:data-schema`-bearing spec reaching it outside the
  single registration home raises. The single home (`reg-machine*` and its
  event-`:schema` arity) runs both side-effects.

  Contract under test:

   1. **Auto-stamp / live validation via the event-:schema arity.** A machine
      registered via `(reg-machine* id machine {:schema EventSchema})` — the
      blessed replacement for the hand-stamped direct path — validates its
      `:data-schema` (it was inert under the bare direct path).

   2. **Privacy redaction.** That same machine's `:sensitive?` `:data` slot
      is redacted at trace egress — the privacy regression no longer egresses
      raw.

   3. **Event-vector :schema arity.** The `:schema` on the opts map validates
      the dispatched OUTER event vector at the `:where :event` boundary
      (rejecting a malformed vector BEFORE the handler runs), while the
      `:data-schema` validates the machine's `:data`. Both live together.

   4. **Fail-loud guard.** The bare `(reg-event-fx id meta
      (make-machine-handler spec))` path on a `:data-schema`-bearing spec now
      RAISES `:rf.error/machine-schema-requires-reg-machine` rather than
      silently no-opping. A schema-LESS spec stays legal on the bare path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.marks :as marks]
            ;; The schemas artefact ships the registered-validator hot path the
            ;; `:where :machine-data` / `:where :event` boundaries route through;
            ;; the `.malli` adapter ns publishes Malli validate/explain into the
            ;; late-bind table, plus the `:sensitive?` / `:large?` path walkers
            ;; the redaction bridge consults.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- collect-machine-data-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure`
  trace event whose `:where` is `:machine-data`."
  [f]
  (mtest/with-trace-capture traces
    (f)
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-data (-> % :tags :where)))
             @traces)))

(defn- collect-event-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure`
  trace event whose `:where` is `:event` (the outer-vector boundary)."
  [f]
  (mtest/with-trace-capture traces
    (f)
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :event (-> % :tags :where)))
             @traces)))

;; ---- fixtures -------------------------------------------------------------

(def ^:private flow-id :auth.login/flow)

(def ^:private Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(def ^:private AuthLoginEvent
  "Outer event-vector schema: `:submit` carries Credentials; framework-internal
  sub-events admit :any; the trailing `[:? :any]` admits a managed-HTTP reply."
  [:cat [:= :auth.login/flow]
   [:or
    [:cat [:= :auth.login/submit] Credentials]
    [:vector :any]]
   [:? :any]])

(def ^:private AuthLoginData
  "Machine `:data` schema: a sensitive token + a plain attempt counter."
  [:map
   [:attempts {:default 0} :int]
   [:token    {:sensitive? true} [:maybe :string]]
   [:error    [:maybe :string]]])

;; ---- (1) auto-stamp / live :data-schema via the event-:schema arity --------

(deftest event-schema-arity-makes-data-schema-live
  (testing "a machine registered via (reg-machine* id machine {:schema ...})
            carries the :rf/machine? / :rf/machine meta (was inert under the
            bare direct path) and its :data-schema validates"
    (let [spec {:initial     :idle
                :data        {:attempts 0 :token nil :error nil}
                :data-schema AuthLoginData
                :actions     {:break (fn [_] {:data {:attempts "nope" :token nil :error nil}})}
                :states      {:idle {:on {:auth.login/break {:target :idle :action :break}}}}}]
      (rf/reg-machine* flow-id spec {:schema AuthLoginEvent})
      ;; The machine meta is stamped — machine-meta reads the spec + :data-schema
      ;; back (the inert-schema bug: machine-meta returned nil on the old path).
      (let [meta (rf/machine-meta flow-id)]
        (is (some? meta) "machine-meta is non-nil (meta WAS stamped)")
        (is (= AuthLoginData (:data-schema meta))
            ":data-schema round-trips through machine-meta — it is LIVE"))
      ;; And it actually validates: an action returning a non-int :attempts
      ;; trips the :where :machine-data boundary (inert before the fix).
      (rf/dispatch-sync [flow-id [:noop]]) ;; bootstrap cleanly
      (let [traces (collect-machine-data-traces!
                     #(rf/dispatch-sync [flow-id [:auth.login/break]]))]
        (is (= 1 (count traces))
            "exactly one :where :machine-data trace fired — the schema is LIVE")
        (is (= flow-id (-> traces first :tags :machine-id)))))))

;; ---- (2) PRIVACY: sensitive :data slot redacted in egress ------------------

(deftest event-schema-arity-registers-redaction-marks
  (testing "a :sensitive? :data slot on a machine registered via the
            event-:schema arity is bridged into the redaction-marks table
            (the privacy leak the bare direct path skipped)"
    (rf/reg-machine* flow-id
      {:initial     :idle
       :data        {:attempts 0 :token nil :error nil}
       :data-schema AuthLoginData
       :states      {:idle {}}}
      {:schema AuthLoginEvent})
    (let [m (marks/marks-for :event flow-id)]
      (is (some? m) "a marks entry exists for the machine")
      (is (= #{[:data :token]} (set (:sensitive m)))
          ":sensitive? :data slot bridged + snapshot-rooted under [:data …]"))))

(deftest event-schema-arity-sensitive-slot-redacted-at-egress
  (testing "the privacy regression: a :sensitive? :data slot on a machine
            registered via the event-:schema arity is REDACTED at trace egress
            and never egresses raw"
    (rf/reg-machine* flow-id
      {:initial     :idle
       :data        {:attempts 0 :token nil :error nil}
       :data-schema AuthLoginData
       :states      {:idle {}}}
      {:schema AuthLoginEvent})
    (let [ev   {:operation :rf.machine/transition
                :tags      {:machine-id flow-id
                            :frame      :rf/default
                            :before     {:state :idle
                                         :data  {:attempts 0
                                                 :token   "secret-jwt-before"
                                                 :error   nil}}
                            :after      {:state :submitting
                                         :data  {:attempts 1
                                                 :token   "secret-jwt-after"
                                                 :error   nil}}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:before :data :token]))
          "sensitive token redacted (before)")
      (is (= :rf/redacted (get-in tags [:after :data :token]))
          "sensitive token redacted (after)")
      (is (= 1 (get-in tags [:after :data :attempts]))
          "plain sibling rides verbatim")
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token leaked anywhere into the projected trace"))))

;; ---- (3) the event-vector :schema validates the outer vector ---------------

(deftest event-schema-arity-validates-outer-vector
  (testing "the :schema opts key validates the dispatched OUTER event vector at
            the :where :event boundary — a malformed :submit payload is rejected
            BEFORE the handler runs; a well-formed one passes"
    ;; A STRICT event schema (no permissive `[:vector :any]` fallback) so a
    ;; bad :submit payload genuinely fails the outer-vector boundary. `:tuple`
    ;; (not `:cat`) so the nested inner-vector element is validated as a vector
    ;; rather than flattened by `:cat`'s sequence-regex semantics.
    (let [StrictEvent [:tuple [:= flow-id]
                       [:tuple [:= :auth.login/submit] Credentials]]]
      (rf/reg-machine* flow-id
        {:initial     :idle
         :data        {:attempts 0 :token nil :error nil}
         :data-schema AuthLoginData
         :actions     {:clear (fn [_] {:data {:error nil}})}
         :states      {:idle       {:on {:auth.login/submit {:target :submitting
                                                            :action :clear}}}
                       :submitting {}}}
        {:schema StrictEvent})
      ;; Malformed submit (password too short) — the :where :event boundary
      ;; rejects the vector; the machine never transitions out of :idle.
      (let [traces (collect-event-traces!
                     #(rf/dispatch-sync
                        [flow-id [:auth.login/submit {:email "a@b.com" :password "short"}]]))]
        (is (<= 1 (count traces))
            "a :where :event boundary trace fired for the malformed event vector")
        (is (not= :submitting (mtest/machine-state flow-id))
            "the malformed event did NOT drive the transition"))
      ;; Well-formed submit transitions normally — :schema accepts it.
      (rf/dispatch-sync
        [flow-id [:auth.login/submit {:email "a@b.com" :password "longenough"}]])
      (is (= :submitting (mtest/machine-state flow-id))
          "a well-formed event vector passes the :schema boundary and transitions"))))

;; ---- (4) fail-loud guard on the bare unstamped-with-schema direct path -----

(deftest bare-direct-path-with-data-schema-fails-loud
  (testing "the bare (reg-event-fx id meta (make-machine-handler spec)) path on
            a :data-schema-bearing spec RAISES :rf.error/machine-schema-requires-
            reg-machine rather than silently no-opping"
    (let [ex (try
               (rf/make-machine-handler
                 {:initial     :idle
                  :data        {:attempts 0 :token nil :error nil}
                  :data-schema AuthLoginData
                  :states      {:idle {}}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "make-machine-handler threw on the schema-bearing bare path")
      (is (= :rf.error/machine-schema-requires-reg-machine
             (:rf.error/id (ex-data ex)))
          "the fail-loud guard's error id is surfaced"))))

(deftest bare-direct-path-without-data-schema-stays-legal
  (testing "a schema-LESS spec on the bare make-machine-handler path is
            unaffected — nothing inert to leak, so the guard does NOT fire"
    (is (fn? (rf/make-machine-handler
               {:initial :idle
                :data    {:n 0}
                :states  {:idle {}}}))
        "make-machine-handler returns a handler-fn for a schema-less spec")))

;; ---- single-home invariants ------------------------------------------------

(deftest opts-must-not-carry-reserved-machine-meta
  (testing "supplying the framework-owned :rf/machine? / :rf/machine keys in
            opts is rejected — the home stamps them"
    (let [ex (try
               (rf/reg-machine* :rf.machine-arity/reserved
                 {:initial :idle :states {:idle {}}}
                 {:rf/machine? true})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "reserved meta in opts threw")
      (is (= :rf.error/machine-reserved-meta-in-opts
             (:rf.error/id (ex-data ex)))))))

(deftest two-arity-reg-machine-still-works
  (testing "the existing 2-arity (reg-machine* id machine) is unchanged — it
            stamps the meta + bridges marks just like before"
    (rf/reg-machine* :rf.machine-arity/plain
      {:initial     :idle
       :data        {:attempts 0 :token nil :error nil}
       :data-schema AuthLoginData
       :states      {:idle {}}})
    (is (some? (rf/machine-meta :rf.machine-arity/plain))
        "2-arity still stamps machine-meta")
    (is (= #{[:data :token]}
           (set (:sensitive (marks/marks-for :event :rf.machine-arity/plain))))
        "2-arity still bridges the schema redaction marks")))
