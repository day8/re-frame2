(ns re-frame.machine-schema-arity-test
  "rf2-genufr + rf2-wgmipl — the single registration home, the fail-loud
  guard, and the public `reg-machine` event-vector `:schema` arity.

  Background. A machine carrying a `:data-schema` must flow through the single
  registration home so the `:rf/machine?` / `:rf/machine` registration-metadata
  stamp runs — the `:where :machine-data` post-commit walker resolves the
  `:data-schema` THROUGH `(machine-meta id)`, so without the stamp the schema
  validates nothing.

  Before rf2-genufr, the direct `(reg-event id meta (make-machine-handler
  spec))` path did not stamp it — the author had to hand-stamp the meta.
  `make-machine-handler` is now the fail-loud guard: a `:data-schema`-bearing
  spec reaching it outside the single registration home raises. The single home
  (`reg-machine*` and its event-`:schema` arity) stamps the meta.

  EP-0025 (rf2-398kql): the home formerly ran a SECOND `:data-schema` side-
  effect — `register-data-schema-marks!`, the schema→marks redaction bridge.
  That bridge is REMOVED; durable machine `:data` egress classification is
  frame-owned (`reg-frame` `:sensitive` / `:large {:app-db …}`). The privacy-
  redaction tests that pinned the bridge are removed with it — the frame-owned
  redaction surface is pinned by `machine-data-schema-redaction-test`.

  Contract under test:

   1. **Auto-stamp / live validation via the event-:schema arity.** A machine
      registered via `(reg-machine* id {:schema EventSchema} machine)` — the
      blessed replacement for the hand-stamped direct path; per rf2-wvh95f F2
      the opts metadata map is the canonical MIDDLE slot — validates its
      `:data-schema` (it was inert under the bare direct path).

   2. **Event-vector :schema arity.** The `:schema` on the opts map validates
      the dispatched OUTER event vector at the `:where :event` boundary
      (rejecting a malformed vector BEFORE the handler runs), while the
      `:data-schema` validates the machine's `:data`. Both live together.

   3. **Fail-loud guard.** The bare `(reg-event id meta
      (make-machine-handler spec))` path on a `:data-schema`-bearing spec now
      RAISES `:rf.error/machine-schema-requires-reg-machine` rather than
      silently no-opping. A schema-LESS spec stays legal on the bare path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            ;; The schemas artefact ships the registered-validator hot path the
            ;; `:where :machine-data` / `:where :event` boundaries route through;
            ;; the `.malli` adapter ns publishes Malli validate/explain into the
            ;; late-bind table.
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
  sub-events admit :any; the trailing `[:? :any]` admits a managed-HTTP reply.

  NOTE this fixture is intentionally PERMISSIVE: tests (1)+(2) below dispatch
  ad-hoc inner sub-events (`[:noop]`, `[:auth.login/break]`) to exercise the
  registration-arity + redaction machinery, so the fixture must admit them. The
  login examples' SHIPPED schema is STRICTER (no `[:vector :any]` fallback) —
  that corrected shape and its malformed-submit rejection are pinned separately
  by `login-example-event-schema-rejects-malformed-submit` below (rf2-thhp91)."
  [:cat [:= :auth.login/flow]
   [:or
    [:cat [:= :auth.login/submit] Credentials]
    [:vector :any]]
   [:? :any]])

;; The login examples' SHIPPED outer-event schema (rf2-thhp91). This is the
;; corrected shape now registered on the :auth.login/flow machine in all three
;; login examples (examples/reagent/login, examples/uix/login_uix,
;; examples/helix/login_helix). Kept here as the executable spec of that shape
;; so its malformed-submit rejection is a real regression gate (the examples
;; tree is test-free, so the schema contract is pinned in the framework suite).
(def ^:private LoginExampleEvent
  [:cat [:= :auth.login/flow]
   [:or
    ;; STRICT :submit branch — a :tuple (NOT :cat): the outer :cat consumes the
    ;; nested sub-event vector as a SINGLE element, so the branch matches that
    ;; one element AS a vector. No permissive [:vector :any] fallback.
    [:tuple [:= :auth.login/submit] Credentials]
    ;; Framework-internal sub-events: an enumerated head + a framework-controlled
    ;; tail (the reply payload rides the OUTER trailing [:? :any]).
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

(def ^:private AuthLoginData
  "Machine `:data` schema: a sensitive token + a plain attempt counter."
  [:map
   [:attempts {:default 0} :int]
   [:token    {:sensitive? true} [:maybe :string]]
   [:error    [:maybe :string]]])

;; ---- (1) auto-stamp / live :data-schema via the event-:schema arity --------

(deftest event-schema-arity-makes-data-schema-live
  (testing "a machine registered via (reg-machine* id {:schema ...} machine)
            carries the :rf/machine? / :rf/machine meta (was inert under the
            bare direct path) and its :data-schema validates"
    (let [spec {:initial     :idle
                :data        {:attempts 0 :token nil :error nil}
                :data-schema AuthLoginData
                :actions     {:break (fn [_] {:data {:attempts "nope" :token nil :error nil}})}
                :states      {:idle {:on {:auth.login/break {:target :idle :action :break}}}}}]
      (machines/reg-machine* flow-id {:schema AuthLoginEvent} spec)
      ;; The machine meta is stamped — machine-meta reads the spec + :data-schema
      ;; back (the inert-schema bug: machine-meta returned nil on the old path).
      (let [meta (machines/machine-meta flow-id)]
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

;; NOTE (EP-0025, rf2-398kql): the two privacy-redaction tests that pinned the
;; schema→marks bridge — `event-schema-arity-registers-redaction-marks` (a
;; `:sensitive?` `:data-schema` slot is bridged into the marks table) and
;; `event-schema-arity-sensitive-slot-redacted-at-egress` (it redacts at trace
;; egress) — are REMOVED. The bridge is gone; durable machine `:data` egress
;; classification is frame-owned, and the redaction surface is pinned by
;; `re-frame.machine-data-schema-redaction-test` (frame-declared snapshot paths).

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
      (machines/reg-machine* flow-id
        {:schema StrictEvent}
        {:initial     :idle
         :data        {:attempts 0 :token nil :error nil}
         :data-schema AuthLoginData
         :actions     {:clear (fn [_] {:data {:error nil}})}
         :states      {:idle       {:on {:auth.login/submit {:target :submitting
                                                            :action :clear}}}
                       :submitting {}}})
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

;; ---- (3b) the login examples' SHIPPED event schema rejects malformed submit -
;;
;; Regression gate for rf2-thhp91: the login examples (reagent / uix / helix)
;; previously registered `AuthLoginEvent` with a permissive `[:vector :any]`
;; fallback that re-admitted a `:submit` whose Credentials failed, so a
;; malformed submit (short password / bad email) passed the `:where :event`
;; boundary and drove the transition + login HTTP effect. This pins the
;; corrected `LoginExampleEvent` shape end-to-end: malformed submit is rejected
;; at the boundary and does NOT transition; valid submit + the framework reply
;; sub-events still pass. The examples tree is test-free, so this is where the
;; shipped schema's contract is enforced.

(deftest login-example-event-schema-rejects-malformed-submit
  (testing "the login examples' shipped LoginExampleEvent rejects a malformed
            :submit at the :where :event boundary (no machine transition, no
            login HTTP effect), while a valid submit + framework reply events
            still pass"
    (machines/reg-machine* flow-id
      {:schema LoginExampleEvent}
      {:initial     :idle
       :data        {:attempts 0 :token nil :error nil}
       :data-schema AuthLoginData
       :actions     {:clear (fn [_] {:data {:error nil}})}
       :states      {:idle       {:on {:auth.login/submit {:target :submitting
                                                           :action :clear}}}
                     :submitting {:on {:auth.login/success {:target :authed}
                                       :auth.login/failure {:target :error-shown}}}
                     :authed       {}
                     :error-shown  {}}})
    ;; (a) short password — rejected at the boundary; the machine stays :idle.
    (let [traces (collect-event-traces!
                   #(rf/dispatch-sync
                      [flow-id [:auth.login/submit {:email "a@b.com" :password "short"}]]))]
      (is (<= 1 (count traces))
          "a :where :event boundary trace fired for the malformed short-password submit")
      (is (not= :submitting (mtest/machine-state flow-id))
          "the malformed submit did NOT transition the machine (no login effect issued)"))
    ;; (b) bad email — also rejected; never reaches :submitting (the rejected
    ;; submits never reached the handler, so the machine is still un-bootstrapped
    ;; / never transitioned — the no-transition claim, not a specific state).
    (let [traces (collect-event-traces!
                   #(rf/dispatch-sync
                      [flow-id [:auth.login/submit {:email "noat" :password "longenough"}]]))]
      (is (<= 1 (count traces))
          "a :where :event boundary trace fired for the bad-email submit")
      (is (not= :submitting (mtest/machine-state flow-id))
          "the bad-email submit did NOT transition the machine"))
    ;; (c) valid submit passes the boundary and transitions to :submitting.
    (rf/dispatch-sync
      [flow-id [:auth.login/submit {:email "a@b.com" :password "longenough"}]])
    (is (= :submitting (mtest/machine-state flow-id))
        "a well-formed submit passes the boundary and transitions")
    ;; (d) the framework reply event (success + trailing payload) still passes —
    ;; the corrected schema must not reject the managed-HTTP reply addressing.
    (let [traces (collect-event-traces!
                   #(rf/dispatch-sync
                      [flow-id [:auth.login/success] {:kind :ok :value {:token "t"}}]))]
      (is (zero? (count traces))
          "the framework success-reply event passes the boundary (no validation failure)")
      (is (= :authed (mtest/machine-state flow-id))
          "the reply event drove the transition to :authed"))))

;; ---- (4) fail-loud guard on the bare unstamped-with-schema direct path -----

(deftest bare-direct-path-with-data-schema-fails-loud
  (testing "the bare (reg-event id meta (make-machine-handler spec)) path on
            a :data-schema-bearing spec RAISES :rf.error/machine-schema-requires-
            reg-machine rather than silently no-opping"
    (let [ex (try
               (machines/make-machine-handler
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
    (is (fn? (machines/make-machine-handler
               {:initial :idle
                :data    {:n 0}
                :states  {:idle {}}}))
        "make-machine-handler returns a handler-fn for a schema-less spec")))

;; ---- single-home invariants ------------------------------------------------

(deftest opts-must-not-carry-reserved-machine-meta
  (testing "supplying the framework-owned :rf/machine? / :rf/machine keys in
            opts is rejected — the home stamps them"
    (let [ex (try
               (machines/reg-machine* :rf.machine-arity/reserved
                 {:rf/machine? true}
                 {:initial :idle :states {:idle {}}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "reserved meta in opts threw")
      (is (= :rf.error/machine-reserved-meta-in-opts
             (:rf.error/id (ex-data ex)))))))

(deftest two-arity-reg-machine-still-works
  (testing "the existing 2-arity (reg-machine* id machine) is unchanged — it
            stamps the meta so the :data-schema is LIVE"
    (machines/reg-machine* :rf.machine-arity/plain
      {:initial     :idle
       :data        {:attempts 0 :token nil :error nil}
       :data-schema AuthLoginData
       :states      {:idle {}}})
    (is (some? (machines/machine-meta :rf.machine-arity/plain))
        "2-arity still stamps machine-meta")
    (is (= AuthLoginData (:data-schema (machines/machine-meta :rf.machine-arity/plain)))
        "2-arity stamps the :data-schema so it round-trips (validation is LIVE)")))
