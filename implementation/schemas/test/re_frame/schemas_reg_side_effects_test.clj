(ns re-frame.schemas-reg-side-effects-test
  "JVM tests for the two dev-only side effects `reg-app-schema` performs
  on (re-)registration (rf2-ee38b.6):

    1. `:rf.schema/violation` hot-reload trace — Spec 010 §Schema
       migration on hot-reload + Spec 009 error-catalogue row
       `:rf.schema/violation`. When a re-registration CHANGES the schema
       at a `(frame-id, path)` and the live `app-db` value at that path
       fails the NEW schema, the runtime emits a `:warning` trace so dev
       panels can highlight the stale slice. The live app keeps running
       (`:logged-and-skipped`).

    2. Schema-derived elision-registry population — Spec 010 §`:large?`
       (\"at boot, and on `reg-app-schema` re-registration\") + §Registry
       feeder (rf2-c1l4d). Registering a schema with `:large?` /
       `:sensitive?` per-slot flags writes the corresponding declarations
       into the frame's `[:rf/runtime :elision …]` slots so size-elision / privacy
       redaction is live for wire emits — including those that fire BEFORE
       the first dispatch (the gap the per-dispatch router refresh leaves).

  Both effects are gated on `interop/debug-enabled?` and DCE'd in
  production; the JVM test build is always dev-enabled."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.substrate.adapter :as substrate-adapter]))

(use-fixtures :each tf/reset-runtime)

(defn- set-app-db!
  "Set the live app-db value for `frame-id` (defaults to :rf/default)."
  ([db] (set-app-db! :rf/default db))
  ([frame-id db]
   (substrate-adapter/replace-container! (frame/app-db-container frame-id) db)))

(defn- capture
  "Run `body-fn` collecting trace events whose `:operation` is
  `operation`; return the captured vector."
  [operation body-fn]
  (let [traces (atom [])
        cb-id  (keyword (gensym "capture"))]
    (rf/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! cb-id)))
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

(deftest violation-is-per-frame
  (testing "rf2-ee38b.6 — the violation check reads the live app-db of
            the registration's frame, not :rf/default"
    (rf/reg-frame :tenant/a {})
    (rf/reg-app-schema [:count] :int {:frame :tenant/a})
    (set-app-db! :tenant/a {:count "bad"})
    (let [violations (capture :rf.schema/violation
                       (fn []
                         (rf/reg-app-schema [:count] [:int {:min 0}]
                                            {:frame :tenant/a})))]
      (is (= 1 (count violations)))
      (is (= :tenant/a (-> violations first :tags :frame))))))

;; ===========================================================================
;; schema-derived elision-registry auto-population
;; ===========================================================================

(deftest reg-app-schema-populates-large-declarations
  (testing "rf2-ee38b.6 — registering a schema with a `:large?` per-slot
            flag writes the declaration into
            [:rf/runtime :elision :declarations] at registration time
            (no dispatch required)"
    (rf/reg-app-schema [:user]
                       [:map
                        [:id          :int]
                        [:uploaded    {:large? true :hint "blob"} :string]])
    (let [decls (elision/declarations :rf/default)]
      (is (= {:large? true :source :schema :hint "blob"}
             (get decls [:user :uploaded]))
          "the :large? slot is declared without any dispatch happening"))))

(deftest reg-app-schema-populates-sensitive-declarations
  (testing "rf2-ee38b.6 — registering a schema with a `:sensitive?`
            per-slot flag writes the declaration into
            [:rf/runtime :elision :sensitive-declarations] at registration time"
    (rf/reg-app-schema [:auth]
                       [:map [:token {:sensitive? true} :string]])
    (let [decls (elision/sensitive-declarations :rf/default)]
      (is (= {:sensitive? true :source :schema}
             (get decls [:auth :token]))
          "the :sensitive? slot is declared at registration time"))))

(deftest rereg-refreshes-elision-declarations
  (testing "rf2-ee38b.6 — re-registering a path with the `:large?` flag
            removed prunes the stale declaration (population is a
            refresh, not an accrete)"
    (rf/reg-app-schema [:user] [:map [:blob {:large? true} :string]])
    (is (contains? (elision/declarations :rf/default) [:user :blob])
        "declared after first registration")
    (rf/reg-app-schema [:user] [:map [:blob :string]])
    (is (not (contains? (elision/declarations :rf/default) [:user :blob]))
        "stale schema-owned declaration pruned on re-registration")))

(deftest bulk-reg-populates-elision-for-every-entry
  (testing "rf2-ee38b.6 — reg-app-schemas populates the elision registry
            for every entry"
    (rf/reg-app-schemas {[:a] [:map [:big {:large? true} :string]]
                         [:b] [:map [:secret {:sensitive? true} :string]]})
    (is (contains? (elision/declarations :rf/default) [:a :big]))
    (is (contains? (elision/sensitive-declarations :rf/default) [:b :secret]))))
