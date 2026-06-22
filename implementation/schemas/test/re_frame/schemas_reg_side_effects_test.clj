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
  app-db classification install there (a `reg-frame` `:sensitive {:app-db …}`
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
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.substrate.adapter :as substrate-adapter]))

(use-fixtures :each tf/reset-runtime)

(defn- set-app-db!
  "Set the live app-db value for `frame-id` (defaults to :rf/default).
  EP-0001 (rf2-adwcv6): writes the app-db PARTITION via `frame/swap-frame-db!`
  — `app-db-container` is now a read-only projection over the one physical
  frame-state container."
  ([db] (set-app-db! :rf/default db))
  ([frame-id db]
   (frame/swap-frame-db! frame-id (constantly db))))

(defn- capture
  "Run `body-fn` collecting trace events whose `:operation` is
  `operation`; return the captured vector."
  [operation body-fn]
  (let [traces (atom [])
        cb-id  (keyword (gensym "capture"))]
    (rf/register-listener! :trace cb-id (fn [ev] (swap! traces conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace cb-id)))
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
    (rf/reg-app-schema [:count] {:schema :int})
    (set-app-db! {:count "not-an-int"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         ;; Re-register with a DIFFERENT schema. The live
                         ;; value "not-an-int" still fails :int (which it
                         ;; also failed under the old schema) — but the
                         ;; trigger is the schema CHANGE + current-value
                         ;; mismatch against the NEW schema.
                         (rf/reg-app-schema [:count] {:schema [:int {:min 0}]})))]
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
    (rf/reg-app-schema [:count] {:schema :int})
    (set-app-db! {:count "not-an-int"})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] {:schema :int})))]
      (is (empty? violations)
          "identical schema re-eval is a silent swap! — nothing to flag"))))

(deftest violation-suppressed-on-first-registration
  (testing "rf2-ee38b.6 — the FIRST registration of a path never emits a
            violation (there is no pre-reload schema to compare against),
            even when the live value already fails"
    (set-app-db! {:count "not-an-int"})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] {:schema :int})))]
      (is (empty? violations) "first registration has no prior schema"))))

(deftest violation-suppressed-when-live-value-validates
  (testing "rf2-ee38b.6 — when the schema changes but the live value
            still satisfies the NEW schema, no violation fires"
    (rf/reg-app-schema [:count] {:schema :int})
    (set-app-db! {:count 5})
    (let [violations (capture :rf.schema/violation
                       (fn [] (rf/reg-app-schema [:count] {:schema [:int {:min 0}]})))]
      (is (empty? violations) "live value 5 satisfies [:int {:min 0}]"))))

(deftest violation-redacts-mismatching-value-when-new-schema-sensitive
  (testing "rf2-ee38b.6 — when the NEW schema declares the slot
            sensitive, :mismatching-value is redacted to :rf/redacted so
            the hot-reload trace does not re-leak a credential"
    (rf/reg-app-schema [:token] {:schema :int})
    (set-app-db! {:token "super-secret"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         (rf/reg-app-schema [:token]
                                            {:schema [:string {:sensitive? true :min 32}]})))]
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
      (rf/reg-app-schema [:token] {:schema :int})
      (set-app-db! {:token secret})
      (let [violations (capture :rf.schema/violation
                         (fn []
                           (rf/reg-app-schema
                             [:token]
                             {:schema (m/schema [:int {:sensitive? true}])})))]
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

(deftest violation-is-per-frame
  (testing "rf2-ee38b.6 — the violation check reads the live app-db of
            the registration's frame, not :rf/default"
    (rf/reg-frame :tenant/a {})
    (rf/reg-app-schema [:count] {:schema :int :frame :tenant/a})
    (set-app-db! :tenant/a {:count "bad"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         (rf/reg-app-schema [:count] {:schema [:int {:min 0}]
                                                      :frame :tenant/a})))]
      (is (= 1 (count violations)))
      (is (= :tenant/a (-> violations first :tags :frame))))))
