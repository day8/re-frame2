(ns re-frame.schemas-walker-opaque-warning-test
  "JVM tests for `:rf.warning/schema-walker-opaque` — the one-time
  process-lifecycle warning that fires from `reg-app-schema` /
  `reg-app-schemas` when the registered schema is NOT a Malli vector
  form.

  Background — Spec 010 §The `:schema` value is opaque to re-frame: the
  schemas-walker (`re-frame.schemas.walker`) is pure data and handles
  only vector-form Malli EDN. Compiled `m/schema` values are treated as
  opaque leaves; per-slot `:sensitive?` / `:large?` flags inside an
  opaque value are silently skipped. This warning is the discoverability
  nudge that surfaces this misconfiguration once per process.

  Keyword schemas do NOT warn. A bare
  keyword is non-vector but is a valid Malli schema (primitive `:int` /
  `:string` OR registry ref `:my/user-schema`); a keyword cannot carry
  per-slot props, so the walker provably skips nothing on a primitive —
  warning on every keyword would be a frequent false positive on the common
  case. The predicate cannot cheaply distinguish primitive from
  registry-ref keywords without a registry consult (forbidden by Spec
  010 §opaque), so the keyword case is suppressed entirely.

  Symmetric with `:rf.warning/schema-validator-unavailable`
  — same emit-site, same warn-once-per-process pattern, same
  test-fixture cache-clear story."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- warnings-of
  "Filter the recorded events to the given operation keyword."
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
         @recorded))

;; ---- positive paths -------------------------------------------------------
;;
;; Only genuinely opaque NON-keyword values (compiled m/schema-like
;; maps) warn. Keyword schemas — primitive AND registry-ref — are
;; suppressed; see the negative-path
;; section below.

(deftest warning-fires-when-schema-is-compiled-map-object
  (testing "reg-app-schema with a compiled m/schema-like map value
            (opaque to the walker) emits the warning exactly once"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:cart] {:malli/schema :some-compiled-form})
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "exactly one warning fires on the first reg-app-schema call")
        (is (= :compiled-schema-object (-> warns first :tags :schema-kind)))
        (is (= [:cart] (-> warns first :tags :path)))))))

(deftest warning-fires-once-across-multiple-opaque-calls
  (testing "subsequent reg-app-schema calls with opaque (compiled-map)
            schemas within the same process do NOT re-emit the warning
            (process-lifecycle one-shot)"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:a] {:malli/schema :a})
      (rf/reg-app-schema [:b] {:malli/schema :b})
      (rf/reg-app-schema [:c] {:malli/schema :c})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))
          "three registrations -> exactly one warning"))))

(deftest warning-fires-once-from-reg-app-schemas-bulk
  (testing "bulk reg-app-schemas with opaque schemas fires the warning
            once across all entries"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schemas {[:user]    {:malli/schema :user}
                           [:cart]    {:malli/schema :cart}
                           [:session] {:malli/schema :session}})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))))))

(deftest warning-carries-actionable-reason
  (testing ":tags includes a :reason string that names the
            ONE supported shape (register the vector form) and does NOT
            recommend the non-existent registration-meta `:sensitive?`
            fallback"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:user] {:malli/schema :user})
      (let [warns  (warnings-of recorded :rf.warning/schema-walker-opaque)
            tags   (-> warns first :tags)
            reason (:reason tags)]
        (is (string? reason))
        (is (re-find #"vector form" reason)
            ":reason names the vector-form fix (the supported shape)")
        ;; The reason must not steer
        ;; users to USE handler/cofx/sub registration-meta `:sensitive?`
        ;; as a workaround — there is no such annotation and the redactor
        ;; deliberately ignores it (sensitivity is path-targeted). It is
        ;; fine (and intended) for the reason to NAME the fallback only to
        ;; say it has been removed.
        (is (re-find #"(?i)removed" reason)
            ":reason states the registration-meta fallback was removed")
        (is (not (re-find #"(?i)(use|via) .{0,40}registration[- ]?(level|meta)"
                          reason))
            "no positive recommendation to USE the registration-meta fallback")))))

(deftest warning-fires-when-vector-form-schema-nests-an-opaque-child
  (testing "a VECTOR-FORM schema (introspectable at its root)
            that embeds a compiled m/schema value as a NESTED child (a
            :map slot's tail) also emits the warning; a root-only
            `schema-opaque?` check would miss this and stay silent"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:token]
                         [:map [:secret {} {:malli/schema :compiled}]])
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "a nested opaque child triggers the warning exactly once")
        (is (= [:token] (-> warns first :tags :path)))))))

(deftest warning-fires-when-schema-carries-a-local-registry
  (testing "a VECTOR-FORM schema carrying a Malli LOCAL
            `{:registry ...}` warns, with :schema-kind :local-registry. The
            form is walkable at its root and its child is a bare keyword the
            walker treats as a flag-free primitive, so a root-and-child check
            alone would call the whole shape introspectable and stay SILENT
            while every `:sensitive?` declared inside the registry is
            invisible"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:auth]
                         [:schema {:registry {::user [:map [:pw {:sensitive? true} :string]]}}
                          ::user])
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "a local-registry schema triggers the warning exactly once")
        (is (= :local-registry (-> warns first :tags :schema-kind))
            ":schema-kind names the local registry, not the compiled-object
             or :unknown arm")
        (is (= [:auth] (-> warns first :tags :path)))
        (is (re-find #":registry" (-> warns first :tags :reason))
            ":reason names the local registry so the nudge is actionable")))
    ;; The `:registry` props key is op-INDEPENDENT — Malli honours it on any
    ;; vector form — so a `:map` carrying one hides its referenced shapes
    ;; exactly as `:schema` does. The classification keys on the PROPS rather
    ;; than on the op, so the same check catches both.
    (rf.schemas/clear-walker-opaque-warned!)
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:creds]
                         [:map {:registry {::pw [:string {:sensitive? true}]}}
                          [:pw ::pw]])
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "a :map-borne local registry warns too — the check is on the
             props, not the op")
        (is (= :local-registry (-> warns first :tags :schema-kind)))))))

;; ---- negative paths (no warning) ------------------------------------------

(deftest warning-suppressed-when-schema-is-vector-form
  (testing "reg-app-schema with a vector-form Malli schema (introspectable)
            does NOT emit the warning"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:user] [:map [:id :int] [:name :string]])
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "vector-form schema -> no warning"))))

(deftest warning-suppressed-on-primitive-keyword-schemas
  (testing "primitive keyword schemas (`:int` / `:string`
            / `:boolean` / `:any`) are valid Malli schemas that cannot
            carry per-slot props; the walker provably skips nothing, so
            registering one does NOT emit the false-positive warning"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:age]    :int)
      (rf/reg-app-schema [:name]   :string)
      (rf/reg-app-schema [:active] :boolean)
      (rf/reg-app-schema [:misc]   :any)
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "primitive keyword schemas -> no spurious 'per-slot flags
           skipped' nudge"))))

(deftest warning-suppressed-on-registry-ref-keyword-schemas
  (testing "registry-ref keyword schemas (`:my/user-schema`)
            also do NOT warn: they are indistinguishable from primitive
            keywords without a forbidden registry consult, so the
            keyword case is suppressed entirely. The advanced registry-
            ref-hides-per-slot-flags shape is covered by the walker
            docstring's discoverability caveat"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:user] :my/user-schema)
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "registry-ref keyword schema -> no warning"))))

;; ---- cache-clear semantics ------------------------------------------------

(deftest cache-clear-allows-warning-to-fire-again
  (testing "clear-walker-opaque-warned! resets the one-shot so a
            subsequent reg-app-schema fires the warning anew (test-fixture
            isolation)"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:first] {:malli/schema :first})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque))))
      (rf.schemas/clear-walker-opaque-warned!)
      (rf/reg-app-schema [:second] {:malli/schema :second})
      (is (= 2 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))
          "after cache clear the warning fires again"))))
