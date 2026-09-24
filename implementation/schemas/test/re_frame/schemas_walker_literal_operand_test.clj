(ns re-frame.schemas-walker-literal-operand-test
  "JVM tests for the operator-aware schema-opacity walk.

  The hazard: a `re-frame.schemas.walker/schema-has-opaque-child?` that
  recursed into EVERY vector tail as if it were a child schema would break on
  literal-bearing Malli operators. Their tail is DATA, not a nested schema, so
  any non-keyword/non-fn/non-symbol literal would reach the opaque catch-all
  and be mis-classified as an opaque compiled child. `[:= 42]`, `[:enum 1 2]`,
  `[:> 10]`, `[:re \"x\"]`, `[:cat [:= :id] [:= 42]]` would all report
  `schema-has-opaque-child? => true`, which would stamp ordinary literal/enum
  validation failures `:sensitive?`, redact their inspectable values, and
  emit a spurious `:rf.warning/schema-walker-opaque` at registration.

  The design: an operator-aware child-schema projection recurses only real
  child-schema positions; known literal/config operands are treated as data
  (not recursed); a genuinely opaque value in a true schema-bearing position
  and an unknown operator shape still fail closed. Classification is structural
  (no Malli/validator introspection) and identical on CLJ + CLJS — the shared
  corpus lives in `re-frame.schemas.walker-literal-operand-fixtures` and is
  asserted on both runtimes (`...-cljs-test` is the CLJS half).

  Every assertion below would fail against an operator-blind walker; the
  fail-closed cases pin the guarantees the operator-aware walk must
  preserve."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.schemas.walker-literal-operand-fixtures :as rf.schemas.walker-literal-operand-fixtures]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- warnings-of [recorded operation]
  (filterv (fn [ev] (and (= :warning (:op-type ev))
                         (= operation (:operation ev))))
           @recorded))

;; ---- pure walker: shared cross-host corpus --------------------------------

(deftest literal-operands-are-data-not-opaque
  (testing "literal/config operands (`:=` value, `:enum`
            members, comparator bounds, `:re` pattern) and their enclosing
            structural forms are fully walkable, so schema-has-opaque-child?
            is FALSE (an operator-blind walk would return true for every one)"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/not-opaque-forms]
      (is (false? (rf.schemas/schema-has-opaque-child? s))
          (str "literal/walkable form must NOT be opaque: " (pr-str s))))))

(deftest nested-compiled-values-still-fail-closed
  (testing "a genuinely opaque (compiled m/schema) value in a
            REAL child-schema position (root, :map slot, container element,
            :cat/:tuple element, :multi/:orn branch, :map-of key/value,
            combinator child) fails closed (true)"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/opaque-forms]
      (is (true? (rf.schemas/schema-has-opaque-child? s))
          (str "nested compiled value must fail closed: " (pr-str s))))))

(deftest unknown-operator-shapes-fail-closed
  (testing "an unclassified operator shape cannot be proven
            walkable, so it fails closed (true)"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/unknown-op-forms]
      (is (true? (rf.schemas/schema-has-opaque-child? s))
          (str "unknown operator shape must fail closed: " (pr-str s))))))

(deftest local-registry-forms-fail-closed
  (testing "a Malli local `{:registry ...}` hides its referenced
            shapes (and their per-slot flags) behind keyword references the
            walk resolves nowhere, so it fails closed (true). The CLJS half
            asserts the SAME corpus, so the classification cannot diverge by
            host"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/local-registry-forms]
      (is (true? (rf.schemas/schema-has-opaque-child? s))
          (str "local-registry form must fail closed: " (pr-str s))))))

(deftest registry-ref-forms-fail-closed
  (testing "an EXPLICIT `[:ref ...]` names a schema held in a
            registry the pure-data walk never consults, so the referenced
            shape's per-slot `:sensitive?` flags live where the walk cannot
            reach them. It fails closed (true), exactly as a local
            `{:registry ...}` does. Were `:ref` in
            `opacity-literal-ops`, every form here would classify
            walkable-and-flag-free and ship its failing value VERBATIM.
            The CLJS half asserts the SAME corpus, so the classification
            cannot diverge by host"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/registry-ref-forms]
      (is (true? (rf.schemas/schema-has-opaque-child? s))
          (str "explicit [:ref ...] form must fail closed: " (pr-str s))))))

(deftest bare-keyword-reference-stays-walkable
  (testing "the carve-out the `[:ref ...]` classification RELIES ON: a
            BARE keyword schema stays walkable, because a registry reference
            (`::user`) cannot be told from a primitive (`:string`) without a
            Malli-registry consult. Only the EXPLICIT `[:ref ...]` vector form
            — which CAN be told — fails closed. Red here means the
            classification over-reached into the keyword case"
    (doseq [s [:string :int :keyword :fixture/user :my/user-schema]]
      (is (false? (rf.schemas/schema-has-opaque-child? s))
          (str "bare keyword must stay walkable: " (pr-str s))))))

;; ---- registration warning -------------------------------------------------

(deftest literal-vector-forms-do-not-warn-walker-opaque
  (testing "registering ordinary literal-bearing vector-form
            schemas (`[:= 42]`, `[:enum ...]`, `[:cat [:= id] [:= v]]`) emits
            NO :rf.warning/schema-walker-opaque"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:answer]  [:= 42])
      (rf/reg-app-schema [:code]    [:enum 200 404])
      (rf/reg-app-schema [:label]   [:enum "a" "b"])
      (rf/reg-app-schema [:bound]   [:> 0])
      (rf/reg-app-schema [:pattern] [:re "a.*z"])
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "literal-bearing vector forms are fully walkable — no opaque nudge"))))

(deftest nested-compiled-child-still-warns-walker-opaque
  (testing "the once-per-process opaque-walker warning fires
            for a vector-form schema that nests a real compiled child"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:token]
                         [:map [:secret (m/schema [:string {:sensitive? true}])]])
      (is (= 1 (count (warnings-of recorded :rf.warning/schema-walker-opaque)))
          "a nested compiled child triggers the walker-opaque warning"))))

(deftest root-registry-ref-warns-walker-opaque-as-unknown
  (testing "registering a ROOT `[:ref ...]` emits the opaque
            nudge once. `:schema-kind` is `:unknown` — the ref shape has no
            kind of its own; the `:reason` string is what names it"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:home] [:ref :fixture/user])
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "a root [:ref ...] triggers the walker-opaque warning exactly once")
        (is (= :unknown (-> warns first :tags :schema-kind))
            ":schema-kind is :unknown — there is no :registry-ref kind")
        (is (= [:home] (-> warns first :tags :path)))
        (is (str/includes? (-> warns first :tags :reason) ":ref")
            "the :reason string names the [:ref ...] shape — it carries the
             explanation the :schema-kind roster deliberately does not")))))

(deftest nested-registry-ref-warns-walker-opaque-as-unknown
  (testing "a NESTED `[:ref ...]` leaves the root a plain vector
            form, so it warns with `:schema-kind :unknown`, exactly as the
            nested-compiled and nested-local-registry cases do"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:account] [:map [:home [:ref :fixture/user]]])
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "a nested [:ref ...] triggers the walker-opaque warning once")
        (is (= :unknown (-> warns first :tags :schema-kind))
            "a nested ref leaves the ROOT a plain vector form → :unknown")))))

(deftest map-entry-keyed-ref-does-not-warn-walker-opaque
  (testing "the CONTROL for the two tests above. A `:map` entry
            whose KEY is the keyword `:ref` is ordinary walkable data, not the
            reference FORM, so it must emit NO warning. Red here means the
            classification keyed on the keyword rather than on the operator
            position"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:opts] [:map [:ref {:optional true} :string]])
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "an entry KEY spelled :ref is data — no opaque nudge"))))

;; ---- validation egress: dev validate path ---------------------------------

(defn- event-failure-trace
  "Validate `event` against `schema` via the dev validate-event! path and
  return the single :rf.error/schema-validation-failure trace."
  [schema event]
  (with-trace-recorder! [traces]
    (rf.schemas/validate-event! :demo/e event {:schema schema})
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest non-sensitive-literal-event-failure-rides-verbatim
  (testing "a failing NON-sensitive literal event schema
            (`[:cat [:= :demo/e] [:= 42]]`) preserves the failing value and is
            NOT stamped :sensitive? (an operator-blind walk would false-flag
            [:= 42] opaque, redacting the value and stamping :sensitive? true)"
    (let [v (event-failure-trace [:cat [:= :demo/e] [:= 42]] [:demo/e 99])]
      (is (some? v) "a validation-failure trace fired")
      (is (not (contains? v :sensitive?))
          "no top-level :sensitive? stamp — nothing in the schema is sensitive")
      (is (= [:demo/e 99] (-> v :tags :value))
          ":value rides verbatim — the literal operand is data, not opaque")
      (is (= [:demo/e 99] (-> v :tags :received))
          ":received rides verbatim too")
      (is (not= :rf/redacted (-> v :tags :explain))
          ":explain is not spuriously redacted"))))

(deftest non-sensitive-enum-event-failure-rides-verbatim
  (testing "a failing NON-sensitive :enum event schema
            preserves the value and is not stamped sensitive"
    (let [v (event-failure-trace
              [:cat [:= :demo/e] [:enum "a" "b"]] [:demo/e "z"])]
      (is (some? v))
      (is (not (contains? v :sensitive?)))
      (is (= [:demo/e "z"] (-> v :tags :value))))))

(deftest sensitive-slot-still-redacts-through-event-path
  (testing "a genuinely :sensitive? payload slot is
            redacted + stamped through the event path"
    (let [v (event-failure-trace
              [:cat [:= :demo/e] [:map [:pw {:sensitive? true} :string]]]
              [:demo/e {:pw 99}])]
      (is (some? v))
      (is (true? (:sensitive? v)) ":sensitive? stamped for the marked slot")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted"))))

;; ---- validation egress: always-on redact-validation-tags -------------------
;; The boundary / off-namespace emit sites reach the walker through the pure
;; `redact-validation-tags` seam. It is host-agnostic, so the CLJS half asserts
;; the identical cases (host parity for the always-on path).

(deftest redact-validation-tags-non-sensitive-literal-rides-verbatim
  (testing "the always-on boundary redactor leaves a
            non-sensitive literal/enum schema's tags verbatim and adds no
            :sensitive? stamp"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}]
      (doseq [schema [[:cat [:= :demo/e] [:= 42]]
                      [:cat [:= :demo/e] [:enum 1 2]]
                      [:= 42]
                      [:enum "a" "b"]]]
        (let [out (rf.schemas/redact-validation-tags schema tags)]
          (is (= tags out)
              (str "non-sensitive literal schema rides verbatim: " (pr-str schema)))
          (is (not (contains? out :sensitive?))
              (str "no :sensitive? stamp for: " (pr-str schema))))))))

(deftest redact-validation-tags-sensitive-and-opaque-still-redact
  (testing "the boundary redactor fails closed for a
            genuinely :sensitive? slot AND for a nested compiled/opaque child"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}]
      (doseq [schema [[:cat [:= :demo/e] [:map [:pw {:sensitive? true} :string]]]
                      [:map [:tok (m/schema [:string {:sensitive? true}])]]
                      (m/schema [:string {:sensitive? true}])]]
        (let [out (rf.schemas/redact-validation-tags schema tags)]
          (is (true? (:sensitive? out))
              (str "sensitive/opaque schema is stamped: " (pr-str schema)))
          (is (= :rf/redacted (:value out))
              (str "sensitive/opaque schema value redacted: " (pr-str schema)))
          (is (not (str/includes? (pr-str out) "99"))
              (str "no raw value survives redaction for: " (pr-str schema))))))))

;; ---- the always-on seam for the explicit reference form -------------------
;;
;; WHY THERE IS NO dev-validate (`validate-event!`) CASE FOR `[:ref ...]`.
;; A `[:ref :fixture/user]` naming an UNREGISTERED target makes real Malli
;; THROW (`:malli.core/invalid-ref`) at schema-compilation time, and
;; `re-frame.schemas.validate/run-validation` routes a throwing validator to a
;; `:rf.error/malformed-schema` trace — a DIFFERENT category from
;; `:rf.error/schema-validation-failure`. So the dev event path cannot produce
;; a validation-failure trace for this shape at all, and registering the target
;; in Malli's DEFAULT registry to make it resolve is forbidden here (a shared
;; test process). The redaction seam itself is what matters and it is pinned
;; directly below; the always-on PRODUCTION surface — the reason `[:ref ...]`
;; fails closed — is pinned in
;; `re-frame.always-on-validation-production-test`, where the cofx path fails
;; CLOSED on the very same validator throw and so reaches the emit.

(deftest redact-validation-tags-registry-ref-redacts-and-stamps
  (testing "the always-on boundary redactor fails CLOSED for an
            explicit `[:ref ...]`: the value-bearing slots scrub to
            :rf/redacted and :sensitive? is stamped, because the referenced
            shape may declare a sensitive slot the walk cannot see"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}]
      (doseq [schema rf.schemas.walker-literal-operand-fixtures/registry-ref-forms]
        (let [out (rf.schemas/redact-validation-tags schema tags)]
          (is (true? (:sensitive? out))
              (str "[:ref ...] form is stamped sensitive: " (pr-str schema)))
          (is (= :rf/redacted (:value out))
              (str "[:ref ...] form's :value is redacted: " (pr-str schema)))
          (is (not (str/includes? (pr-str out) "99"))
              (str "no raw value survives for: " (pr-str schema))))))))

(deftest redact-validation-tags-map-entry-keyed-ref-rides-verbatim
  (testing "the CONTROL: a `:map` entry whose KEY is `:ref` is
            ordinary walkable data, so its tags ride verbatim with no stamp.
            It is what proves the classification keys on the operator
            position, not the keyword"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}
          out  (rf.schemas/redact-validation-tags
                 [:map [:ref {:optional true} :string]] tags)]
      (is (= tags out) "map entry keyed :ref rides verbatim")
      (is (not (contains? out :sensitive?)) "no :sensitive? stamp"))))
