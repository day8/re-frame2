(ns re-frame.schemas-reg-side-effects-test
  "JVM tests for the dev-only side effect `reg-app-schema` performs on
  (re-)registration (rf2-ee38b.6):

    `:rf.schema/violation` hot-reload trace — Spec 010 §Schema migration
    on hot-reload + Spec 009 error-catalogue row `:rf.schema/violation`.
    When a re-registration CHANGES the schema at a `(frame-id, path)` and
    the live `app-db` value at that path fails the NEW schema, the runtime
    emits a `:warning` trace so dev panels can highlight the stale slice.
    The live app keeps running (`:logged-and-skipped`).

  EP-0015 §8 (rf2-d2r3um): `reg-app-schema` NO LONGER populates the
  durable elision registry from `:large?` / `:sensitive?` per-slot flags —
  durable app-db egress classification rides the EP-0025 commit-plane
  classification effects (a `reg-event` returns `:sensitive` / `:large`
  alongside `:db`; `re-frame.elision/apply-classification-effects`,
  `:source :effect`, exercised in the core/ssr suites). It is NOT installed
  by `re-frame.frame-classification` anymore — EP-0025 retired the durable
  app-db classification install there (a `make-frame` `:sensitive {:app-db …}`
  block / a `:large` frame key now fail loud with
  `:rf.error/bad-frame-classification`). Schema `:sensitive?` still drives
  THIS file's hot-reload-trace redaction
  (`violation-redacts-mismatching-value-when-new-schema-sensitive`),
  consulting the schema directly rather than any elision-registry feed.

  The effect is gated on `interop/debug-enabled?` and DCE'd in
  production; the JVM test build is always dev-enabled."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ;; rf2-u9bjgr / rf2-kzghnz — compiled `m/schema` objects exercise
            ;; the opaque-schema fail-closed redaction arm of the hot-reload
            ;; `:rf.schema/violation` path. Malli is on the schemas test
            ;; classpath (the artefact deps on metosin/malli).
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Load-bearing beyond its alias: loading the facade is what
            ;; publishes the Malli validate/explain hooks (rf2-v96fh) and
            ;; binds core's `reg-app-schema` re-export through late-bind.
            ;; clj-kondo reports the ALIAS unused here; the require is not.
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- set-app-db!
  "Set the live app-db value for `frame-id` (defaults to :rf/default).
  EP-0001 (rf2-adwcv6): writes the app-db PARTITION via `frame/swap-frame-db!`
  — `app-db-container` is now a read-only projection over the one physical
  frame-state container."
  ([db] (set-app-db! :rf/default db))
  ([frame-id db]
   (rf.frame/swap-frame-db! frame-id (constantly db))))

(defn- capture
  "Run `body-fn` collecting trace events whose `:operation` is
  `operation`; return the captured vector."
  [operation body-fn]
  (with-trace-recorder! [traces]
    (body-fn)
    (filterv #(= operation (:operation %)) @traces)))

;; ===========================================================================
;; :rf.schema/violation hot-reload trace
;; ===========================================================================

(deftest violation-fires-when-rereg-changes-schema-and-live-value-fails
  (testing "rf2-ee38b.6 — re-registering a path with a NEW schema the
            live app-db value violates emits :rf.schema/violation with
            the five spec'd tags"
    ;; Register the original schema, then plant a live value that the
    ;; new schema will reject.
    (rf/reg-app-schema [:count] :int)
    (set-app-db! {:count "not-an-int"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         ;; Re-register with a DIFFERENT schema. The live
                         ;; value "not-an-int" still fails :int (which it
                         ;; also failed under the old schema) — but the
                         ;; trigger is the schema CHANGE + current-value
                         ;; mismatch against the NEW schema.
                         (rf/reg-app-schema [:count] [:int {:min 0}])))]
      (is (= 1 (count violations)) "exactly one violation trace")
      (let [{:keys [op-type recovery tags] :as ev} (first violations)]
        (is (= :warning op-type) "op-type is :warning per Spec 009 catalogue")
        (is (= [:count] (:path tags)))
        (is (= :int (:pre-reload-schema tags)))
        (is (= [:int {:min 0}] (:post-reload-schema tags)))
        (is (= "not-an-int" (:mismatching-value tags)))
        (is (= :rf/default (:frame tags)))
        ;; `:recovery` is hoisted to the top-level envelope by
        ;; `trace/build-event` (Spec 009 §Core fields hoist contract).
        (is (= :logged-and-skipped recovery)
            (str ":recovery hoisted to top level; envelope=" (pr-str ev)))))))

(deftest violation-suppressed-when-schema-unchanged
  (testing "rf2-ee38b.6 — a no-op re-eval (identical schema) does NOT
            emit a violation, even if the live value fails the schema"
    (rf/reg-app-schema [:count] :int)
    (set-app-db! {:count "not-an-int"})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] :int)))]
      (is (empty? violations)
          "identical schema re-eval is a silent swap! — nothing to flag"))))

(deftest violation-suppressed-on-first-registration
  (testing "rf2-ee38b.6 — the FIRST registration of a path never emits a
            violation (there is no pre-reload schema to compare against),
            even when the live value already fails"
    (set-app-db! {:count "not-an-int"})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] :int)))]
      (is (empty? violations) "first registration has no prior schema"))))

(deftest violation-suppressed-when-live-value-validates
  (testing "rf2-ee38b.6 — when the schema changes but the live value
            still satisfies the NEW schema, no violation fires"
    (rf/reg-app-schema [:count] :int)
    (set-app-db! {:count 5})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] [:int {:min 0}])))]
      (is (empty? violations) "live value 5 satisfies [:int {:min 0}]"))))

;; ===========================================================================
;; rf2-4thn — a REGISTERED nil token is a PRESENT declaration
;; ===========================================================================
;;
;; Spec 010 §The `:schema` value is opaque + the rf2-6eh5h law pinned by
;; `re-frame.schemas-presence-test`: only an ABSENT declaration means "no
;; schema"; a PRESENT falsey token is handed verbatim to the configured
;; validator. The hot-reload change detector used to read only
;; `[frame-id path :schema]` and gate on `(some? prior-schema)`, so a real
;; registry entry carrying a nil token was indistinguishable from no entry at
;; all — re-registering that path with a schema the live value violates
;; emitted nothing, and the schema-evolution diagnostic that exists precisely
;; to explain state predating the edited schema went silent.
;;
;; The validator below is a legitimate custom validator under Spec 010: the
;; schema value is opaque to re-frame and the validator owns its own token
;; vocabulary — `nil` among them. `nil` REJECTS here (rather than accepting)
;; so the two suppression controls below are non-vacuous against an
;; over-broad fix: were the presence gate always true, or the changed-schema
;; comparison dropped, each control would emit.

(defn- nil-token-validator!
  "Install a custom validator whose opaque token vocabulary includes `nil`:
  `nil` rejects every value, `:needs-int` accepts only integers, any other
  token accepts."
  []
  (rf.schemas/set-schema-validator!
    (fn [schema value]
      (cond
        (nil? schema)         false
        (= :needs-int schema) (int? value)
        :else                 true))))

(deftest violation-fires-when-the-prior-registered-schema-token-is-nil
  (testing "rf2-4thn — re-registering a path whose PRIOR registered token was
            nil emits the violation. Registration presence is the presence of
            the registry ENTRY, not the truthiness of the token it stores."
    (nil-token-validator!)
    (rf/reg-app-schema [:count] nil)
    (set-app-db! {:count :bad})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] :needs-int)))]
      (is (= 1 (count violations))
          "exactly one violation trace — the nil token was a real prior
           registration, so the schema CHANGED")
      (let [{:keys [op-type recovery tags] :as ev} (first violations)]
        (is (= :warning op-type) "op-type is :warning per Spec 009 catalogue")
        (is (= [:count] (:path tags)))
        (is (contains? tags :pre-reload-schema)
            (str ":pre-reload-schema is STAMPED even though the token is nil;"
                 " envelope=" (pr-str ev)))
        (is (nil? (:pre-reload-schema tags))
            "and it carries the EXACT prior token, verbatim")
        (is (= :needs-int (:post-reload-schema tags)))
        (is (= :bad (:mismatching-value tags))
            "the new token is a bare keyword — provably flag-free and not
             opaque — so the live value is not redacted")
        (is (= :rf/default (:frame tags)))
        (is (= :logged-and-skipped recovery))))))

(deftest violation-suppressed-on-first-registration-of-a-nil-token
  (testing "rf2-4thn — ABSENCE is still distinguished from a present-nil
            entry: the FIRST registration of a path never emits, even when
            the token is nil and the live value fails it"
    (nil-token-validator!)
    (set-app-db! {:count :bad})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] nil)))]
      (is (empty? violations)
          "no prior registry entry — nothing changed, nothing to flag"))))

(deftest violation-suppressed-when-a-nil-token-is-re-registered-unchanged
  (testing "rf2-4thn — a nil -> nil re-eval stays silent: the entry is
            present, but the schema did not CHANGE"
    (nil-token-validator!)
    (rf/reg-app-schema [:count] nil)
    (set-app-db! {:count :bad})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] nil)))]
      (is (empty? violations)
          "identical token re-eval is a silent swap!, exactly as for a
           truthy token"))))

;; CONFIRM-BY-REVERT (rf2-4thn): restoring the `(some? prior-schema)` gate in
;; `maybe-emit-schema-violation!` (in place of the `prior-registered?`
;; presence bit captured at the call site) makes
;; `violation-fires-when-the-prior-registered-schema-token-is-nil` fail — the
;; capture goes to 0 violations — while both suppression controls above and
;; every truthy-token case stay green. That is the branch these three reach.

(deftest violation-redacts-mismatching-value-when-new-schema-sensitive
  (testing "rf2-ee38b.6 — when the NEW schema declares the slot
            sensitive, :mismatching-value is redacted to :rf/redacted so
            the hot-reload trace does not re-leak a credential"
    (rf/reg-app-schema [:token] :int)
    (set-app-db! {:token "super-secret"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         (rf/reg-app-schema [:token]
                                            [:string {:sensitive? true :min 32}])))]
      (is (= 1 (count violations)))
      (let [{:keys [sensitive? tags]} (first violations)]
        (is (= :rf/redacted (:mismatching-value tags))
            "sensitive slot's live value is scrubbed")
        ;; `:sensitive?` is hoisted to the top-level envelope by
        ;; `trace/build-event`.
        (is (true? sensitive?) ":sensitive? hoisted to top level")
        (is (= [:token] (:path tags)) "structural :path is kept")))))

(deftest violation-redacts-mismatching-value-when-new-schema-opaque-and-sensitive
  (testing "rf2-u9bjgr / rf2-kzghnz — when the NEW (re-registered) schema is
            a COMPILED / OPAQUE m/schema object carrying a {:sensitive? true}
            slot, the hot-reload violation FAILS CLOSED: the pure-data walker
            cannot see the per-slot flag, so the path must redact
            :mismatching-value to :rf/redacted anyway, never leaking the live
            value. Mirrors the validate-*! opaque fail-closed posture
            (schemas_sensitive_test/app-db-validation-opaque-schema-fails-closed)."
    (let [secret "OPAQUE-HOTRELOAD-SECRET-kzghnz"]
      ;; Original schema is a plain (walkable) vector form; the live value is a
      ;; credential string. Re-register the SAME path with a COMPILED opaque
      ;; schema that (a) differs from the prior schema (so the change-gate
      ;; fires), (b) the live value fails (so a violation fires), and (c)
      ;; declares its slot {:sensitive? true} — a flag Malli honours for
      ;; validation but the walker cannot introspect through the opaque value.
      (rf/reg-app-schema [:token] :int)
      (set-app-db! {:token secret})
      (let [violations (capture :rf.schema/violation
                         (fn []
                           (rf/reg-app-schema
                             [:token]
                             (m/schema [:int {:sensitive? true}]))))]
        (is (= 1 (count violations)) "exactly one violation trace")
        (let [{:keys [sensitive? tags] :as ev} (first violations)]
          ;; FAIL CLOSED: the opaque schema cannot be walked, so the path must
          ;; redact regardless of what schema-has-sensitive? can see.
          (is (= :rf/redacted (:mismatching-value tags))
              "opaque schema fails closed: live value scrubbed to :rf/redacted")
          (is (true? sensitive?) ":sensitive? hoisted to top level (fail-closed stamp)")
          (is (= [:token] (:path tags)) "structural :path is kept")
          (is (not (str/includes? (pr-str ev) secret))
              "the secret survives nowhere in the opaque hot-reload violation trace"))))))

;; CONFIRM-BY-REVERT (rf2-kzghnz): reverting the fail-closed arm to the bare
;; `(walker/schema-has-sensitive? new-schema)` (dropping the
;; `(or … (walker/schema-opaque? new-schema))`) makes `sensitive?` false for
;; the opaque schema above, `:mismatching-value` then ships `secret` verbatim,
;; the `:rf/redacted` and `not str/includes? secret` assertions both fail, and
;; the violation egresses the credential — the leak this test pins closed.

(deftest violation-redacts-mismatching-value-when-new-schema-nested-opaque-and-sensitive
  (testing "rf2-hi0tf8 — when the NEW (re-registered) schema is a VECTOR-FORM
            (root introspectable) schema that NESTS a compiled / opaque
            m/schema value carrying a {:sensitive? true} slot, the hot-reload
            violation still FAILS CLOSED. Pre-fix: the root-only
            `schema-opaque?` disjunct saw a walkable :map form and returned
            false, `schema-has-sensitive?`'s walk silently skipped the opaque
            :secret child (no declaration recorded), so `sensitive?` computed
            false and the live value egressed verbatim — the same leak
            `violation-redacts-mismatching-value-when-new-schema-opaque-and-sensitive`
            already pins for the FULLY opaque shape."
    (let [secret "NESTED-OPAQUE-HOTRELOAD-SECRET-hi0tf8"]
      (rf/reg-app-schema [:token] :int)
      (set-app-db! {:token {:secret secret}})
      (let [violations (capture :rf.schema/violation
                         (fn []
                           (rf/reg-app-schema
                             [:token]
                             [:map
                              [:secret
                               (m/schema [:string {:sensitive? true :min 999}])]])))]
        (is (= 1 (count violations)) "exactly one violation trace")
        (let [{:keys [sensitive? tags] :as ev} (first violations)]
          (is (= :rf/redacted (:mismatching-value tags))
              "nested-opaque schema fails closed: live value scrubbed to :rf/redacted")
          (is (true? sensitive?) ":sensitive? hoisted to top level (fail-closed stamp)")
          (is (= [:token] (:path tags)) "structural :path is kept")
          (is (not (str/includes? (pr-str ev) secret))
              "the secret survives nowhere in the nested-opaque hot-reload violation trace"))))))

(deftest violation-is-per-frame
  (testing "rf2-ee38b.6 — the violation check reads the live app-db of
            the registration's frame, not :rf/default"
    (rf/make-frame {:id :tenant/a})
    (rf/reg-app-schema [:count] {:frame :tenant/a} :int)
    (set-app-db! :tenant/a {:count "bad"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         (rf/reg-app-schema [:count] {:frame :tenant/a} [:int {:min 0}])))]
      (is (= 1 (count violations)))
      (is (= :tenant/a (-> violations first :tags :frame))))))
