(ns re-frame.schemas-sensitive-test
  "JVM tests for the `:sensitive?` redaction contract in schema-validation
  error traces (rf2-kj51z).

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces, the validation hot path MUST consult the registered schema's
  per-slot `:sensitive?` before including the failing value in the
  `:rf.error/schema-validation-failure` trace event. The handler/cofx/
  sub registration-meta `:sensitive?` annotation has been removed —
  the schema-walker is now the sole driver. When the schema declares
  the slot sensitive:

    1. The failing value (`:value` / `:received`) is replaced with the
       framework-reserved `:rf/redacted` sentinel.
    2. The Malli explainer output (`:explain`) is redacted — it
       carries the failing value verbatim.
    3. The trace event's TOP-LEVEL `:sensitive?` field is stamped
       `true` so consumers route on it. (Per Spec 009 §Trace-event
       field: `:sensitive?` at the top level, rf2-isdwf — the
       schemas-side emit-site stamps `:tags :sensitive? true`; the
       runtime's `emit-error!` promotes it to the top-level slot per
       Spec 009 line 1175 'hoisted to top-level, not :tags'.)

  Structural slots (`:path`, `:failing-id`, `:schema-id`, `:reason`) ride
  unchanged — consumers need them to locate the broken slot without
  leaking user data.

  This file covers three surfaces:

    1. **Walker unit tests** — `extract-sensitive-paths-from-schema`
       recognises every Malli shape `:sensitive?` can legally live in
       (slot-level props, container-level props, nested, dispatch-
       bearing combinators).
    2. **Redaction substitution** — direct invocation of
       `validate-app-schema!` / `validate-event!` /
       `validate-sub!` against a `:sensitive?`-bearing schema
       fires a trace with the redaction shape pinned. (The cofx surface's
       redaction now rides the EP-0017 `:rf.error/cofx-value-invalid` path
       in the core artefact — the injection-time `validate-cofx!` was
       retired per rf2-nkf4l3.)
    3. **Backward-compat** — non-sensitive validation failures emit
       unchanged (`:value`, `:explain` ride verbatim; no top-level
       `:sensitive?` stamp on the event)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            ;; Per rf2-v96fh (schema implies validation) requiring
            ;; `re-frame.schemas` above already loads `re-frame.schemas.malli`
            ;; for its ns-load side effect (publishes the validate/explain
            ;; late-bind hooks the default validator routes through), so the
            ;; validator is LIVE without this explicit require — kept as a
            ;; harmless, explicit statement of the Malli dependency
            ;; (rf2-a5kzs finding 4).
            [re-frame.schemas.malli]
            ;; rf2-u9bjgr — compiled `m/schema` objects exercise the
            ;; opaque-schema fail-closed redaction arm. Malli is on the
            ;; schemas test classpath (the artefact deps on metosin/malli).
            [malli.core :as m]
            ;; rf2-jqx2at — direct unit tests on the internal `:path`-tag
            ;; sanitiser (`sanitize-sensitive-path`) which is NOT re-exported
            ;; through the `re-frame.schemas` facade (validate-app-schema!'s
            ;; private redaction helper).
            [re-frame.schemas.walker :as walker]
            [re-frame.schemas.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---- walker unit tests ----------------------------------------------------

(deftest extract-no-sensitive-slots
  (testing "a schema with no :sensitive? props produces no entries"
    (is (= {} (schemas/extract-sensitive-paths-from-schema
                [:map [:name :string]] [])))
    (is (= {} (schemas/extract-sensitive-paths-from-schema :string [])))
    (is (= {} (schemas/extract-sensitive-paths-from-schema :int [:a :b])))))

(deftest extract-slot-level-sensitive
  (testing "the slot's per-slot props carry :sensitive? true"
    (let [schema [:map
                  [:user :string]
                  [:password {:sensitive? true} :string]]]
      (is (= {[:password] {:sensitive? true :source :schema}}
             (schemas/extract-sensitive-paths-from-schema schema []))))))

(deftest extract-honours-base-path
  (testing "base-path is prepended to every discovered slot path"
    (let [schema [:map
                  [:password {:sensitive? true} :string]]]
      (is (= {[:auth :password] {:sensitive? true :source :schema}}
             (schemas/extract-sensitive-paths-from-schema schema [:auth]))))))

(deftest extract-container-level-sensitive
  (testing "the schema's OWN props (container-level) claim the base-path"
    ;; `(reg-app-schema [:auth :token] [:string {:sensitive? true}])`
    (is (= {[:auth :token] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:string {:sensitive? true}] [:auth :token])))))

(deftest extract-nested-map
  (testing "nested :map carries the path through every level"
    (let [schema [:map
                  [:user
                   [:map
                    [:profile
                     [:map
                      [:ssn {:sensitive? true} :string]]]]]]]
      (is (= {[:user :profile :ssn] {:sensitive? true :source :schema}}
             (schemas/extract-sensitive-paths-from-schema schema []))))))

(deftest extract-multiple-sensitive-slots
  (testing "multiple sensitive slots in the same schema produce one entry each"
    (let [schema [:map
                  [:username :string]
                  [:password  {:sensitive? true} :string]
                  [:totp-code {:sensitive? true} :string]
                  [:email :string]]]
      (is (= {[:password]  {:sensitive? true :source :schema}
              [:totp-code] {:sensitive? true :source :schema}}
             (schemas/extract-sensitive-paths-from-schema schema []))))))

(deftest extract-positional-combinator-descends
  (testing ":vector / :or / :and descend at the same base-path"
    ;; A :vector with sensitive props on its inner type's container.
    (is (= {[:tokens] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:vector [:string {:sensitive? true}]] [:tokens])))))

;; ---- schema-has-sensitive? -----------------------------------------------

(deftest schema-has-sensitive-slot-level
  (testing "schema-has-sensitive? returns true when ANY slot carries
            :sensitive? — emit-sites carry the whole registered value
            in the trace, so a sensitive child slot still leaks
            unredacted"
    (let [schema [:map [:password {:sensitive? true} :string]]]
      (is (true? (schemas/schema-has-sensitive? schema))
          "slot-level :sensitive? — conservative redact"))))

(deftest schema-has-sensitive-container-level
  (testing "a container-level :sensitive? on a schema registered at a path
            triggers redaction"
    (let [schema [:string {:sensitive? true}]]
      (is (true? (schemas/schema-has-sensitive? schema))))))

(deftest schema-has-sensitive-nested
  (testing "a nested :sensitive? slot deep inside a map also triggers redaction"
    (let [schema [:map
                  [:user [:map
                          [:profile [:map
                                     [:ssn {:sensitive? true} :string]]]]]]]
      (is (true? (schemas/schema-has-sensitive? schema))))))

(deftest schema-has-sensitive-no-match
  (testing "no :sensitive? anywhere → false"
    (let [schema [:map [:user :string] [:age :int]]]
      (is (false? (schemas/schema-has-sensitive? schema))))
    (is (false? (schemas/schema-has-sensitive? :int)))
    (is (false? (schemas/schema-has-sensitive? [:vector :string])))))

;; ---- redaction at app-db validation site ----------------------------------

(deftest app-db-validation-redacts-sensitive-slot
  (testing "Per Spec 010 §`:sensitive?` — a failing app-db value at a
            :sensitive? slot emits a trace whose :value and :explain
            are the :rf/redacted sentinel and whose :tags are stamped
            :sensitive? true"
    ;; A schema where the WHOLE registered slot is marked sensitive
    ;; (container-level :sensitive?).
    (rf/reg-app-schema [:auth :token] [:string {:sensitive? true}])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::redact (fn [ev] (swap! traces conj ev)))
      ;; The value at [:auth :token] is an int (42) — fails :string.
      (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (rf/unregister-listener! :trace ::redact)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired")
        (let [v (first violations)]
          (is (true? (:sensitive? v))
              "top-level :sensitive? true — consumers can filter (hoisted from :tags per Spec 009 §Trace-event field: `:sensitive?` at the top level)")
          (is (= :rf/redacted (-> v :tags :value))
              ":value is the :rf/redacted sentinel — original value scrubbed")
          (is (= :rf/redacted (-> v :tags :explain))
              ":explain is also redacted — Malli's explanation re-leaks the value")
          ;; Structural slots remain visible.
          (is (= [:auth :token] (-> v :tags :path))
              ":path stays visible — consumers need it to locate the slot")
          (is (= :auth/init-bad (-> v :tags :failing-id))
              ":failing-id stays visible — the handler is not sensitive")
          (is (= :app-db (-> v :tags :where)))
          (is (string? (-> v :tags :reason))
              ":reason — human-readable explanation, no value"))))))

(deftest app-db-validation-redacts-slot-level-sensitive
  (testing "Slot-level :sensitive? on a child entry inside a registered
            map schema covers that slot's failures"
    ;; reg-app-schema covers [:user], the :password child is sensitive.
    (rf/reg-app-schema [:user]
                       [:map
                        [:name     :string]
                        [:password {:sensitive? true} :string]])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::slot (fn [ev] (swap! traces conj ev)))
      ;; password is an int — fails :string. Since validation is
      ;; per-registered-path, the whole [:user] value fails the schema.
      ;; But schema-sensitive-at? checks if [:user] is sensitive (no)
      ;; OR a child slot of [:user] under the registered path crosses
      ;; the failing path. Since reg-app-schema validates the whole
      ;; registered slot, we need the :sensitive? to flag the WHOLE
      ;; failure when ANY slot within is sensitive.
      (schemas/validate-app-schema! {:user {:name "alice" :password 99}}
                                :user/bad)
      (rf/unregister-listener! :trace ::slot)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "a trace fired")
        (is (true? (:sensitive? v))
            "the registered schema contains a :sensitive? slot — the failure is redacted (top-level stamp per Spec 009 hoist)")
        (is (= :rf/redacted (-> v :tags :value)))
        (is (= :rf/redacted (-> v :tags :explain)))))))

(deftest app-db-validation-non-sensitive-passes-through-verbatim
  (testing "Backward-compat — a schema with no :sensitive? props emits
            unchanged traces; :value and :explain ride verbatim"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::plain (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-schema! {:count "not-an-int"} :count/bad)
      (rf/unregister-listener! :trace ::plain)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no top-level :sensitive? stamp on non-sensitive validation")
        (is (not (contains? (:tags v) :sensitive?))
            ":tags :sensitive? also absent — the stamp lives at top-level only")
        (is (= "not-an-int" (-> v :tags :value))
            ":value rides verbatim — legacy behaviour preserved")
        (is (some? (-> v :tags :explain))
            ":explain is present (Malli's structural explanation)")))))

;; ---- redaction when the sensitive slot is nested in a collection ---------
;; Per rf2-g5auo — Malli's explainer reports a value-relative `:in` path
;; carrying COLLECTION INDICES (`[1 :token]`) / `:map-of` keys
;; (`["a" :secret]`), while the walker's decl paths are INDEX-FREE
;; (`[:token]`, `[:secret]`) because positional/keyed containers descend
;; at the same base-path. Before the fix the `schema-sensitive-at?`
;; prefix match failed in BOTH directions for collection-nested slots, so
;; the failing value shipped VERBATIM in the trace's :value / :explain and
;; the top-level :sensitive? stamp was absent — exactly the leak the
;; feature exists to prevent. These tests fail before the alignment fix
;; and pass after.

(defn- app-db-failure-trace
  "Helper: register `schema` at `path`, validate `db` (which must fail
  the schema), and return the single schema-validation-failure trace."
  [path schema db failing-id]
  (rf/reg-app-schema path schema)
  (let [traces (atom [])
        kw     (keyword "rf2-g5auo" (name (gensym "listen")))]
    (rf/register-listener! :trace kw (fn [ev] (swap! traces conj ev)))
    (schemas/validate-app-schema! db failing-id)
    (rf/unregister-listener! :trace kw)
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest app-db-validation-redacts-sensitive-slot-nested-in-vector
  (testing "rf2-g5auo — a :sensitive? slot inside a :vector element redacts
            even though Malli's :in carries the element index"
    ;; [:items] => vector of maps; the per-element :token is sensitive.
    ;; The second element's :token is an int (99) — fails :string.
    (let [v (app-db-failure-trace
              [:items]
              [:vector [:map [:token {:sensitive? true} :string]]]
              {:items [{:token "ok"} {:token 99}]}
              :items/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — the index segment no longer blocks the match")
      (is (= :rf/redacted (-> v :tags :value))
          ":value redacted — the raw 99 (and the rest of the vector) no longer leaks")
      (is (= :rf/redacted (-> v :tags :explain))
          ":explain redacted — Malli's explanation re-carries the value verbatim"))))

(deftest app-db-validation-redacts-sensitive-slot-nested-in-map-of
  (testing "rf2-g5auo — a :sensitive? slot inside a :map-of value redacts
            even though Malli's :in carries the map key"
    (let [v (app-db-failure-trace
              [:by-id]
              [:map-of :string [:map [:secret {:sensitive? true} :string]]]
              {:by-id {"a" {:secret 99}}}
              :by-id/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — the map-of key segment no longer blocks the match")
      (is (= :rf/redacted (-> v :tags :value)))
      (is (= :rf/redacted (-> v :tags :explain))))))

(deftest app-db-validation-redacts-sensitive-slot-nested-in-sequential
  (testing "rf2-g5auo — :sequential behaves identically to :vector"
    (let [v (app-db-failure-trace
              [:log]
              [:sequential [:map [:pw {:sensitive? true} :string]]]
              {:log [{:pw 1}]}
              :log/bad)]
      (is (some? v))
      (is (true? (:sensitive? v)))
      (is (= :rf/redacted (-> v :tags :value)))
      (is (= :rf/redacted (-> v :tags :explain))))))

(deftest app-db-validation-redacts-sensitive-slot-nested-deeply
  (testing "rf2-g5auo — a sensitive slot under a map → vector → map chain
            redacts (mixed map-key + collection-index :in segments)"
    (let [v (app-db-failure-trace
              [:accounts]
              [:map [:items [:vector [:map [:tok {:sensitive? true} :string]]]]]
              {:accounts {:items [{:tok 99}]}}
              :accounts/bad)]
      (is (some? v))
      (is (true? (:sensitive? v)))
      (is (= :rf/redacted (-> v :tags :value)))
      (is (= :rf/redacted (-> v :tags :explain))))))

(deftest app-db-validation-redacts-scalar-sensitive-collection-element
  (testing "rf2-g5auo — a :vector whose ELEMENT type is itself sensitive
            (container-level :sensitive? on the element schema) redacts"
    (let [v (app-db-failure-trace
              [:tokens]
              [:vector [:string {:sensitive? true}]]
              {:tokens ["ok" 99]}
              :tokens/bad)]
      (is (some? v))
      (is (true? (:sensitive? v)))
      (is (= :rf/redacted (-> v :tags :value)))
      (is (= :rf/redacted (-> v :tags :explain))))))

(deftest app-db-validation-collection-non-sensitive-not-over-redacted
  (testing "rf2-g5auo — no regression: a collection failure where NO slot is
            sensitive still rides verbatim (the alignment must not
            over-redact)"
    (let [v (app-db-failure-trace
              [:rows]
              [:vector [:map [:name :string]]]
              {:rows [{:name 99}]}
              :rows/bad)]
      (is (some? v))
      (is (not (contains? v :sensitive?))
          "no :sensitive? stamp — nothing in the schema is sensitive")
      (is (not= :rf/redacted (-> v :tags :value))
          ":value rides verbatim — alignment didn't spuriously redact"))))

(deftest app-db-validation-collection-sibling-narrowed-value-verbatim-whole-explain-redacted
  (testing "rf2-g5auo + rf2-oh4se + rf2-3qam7b — PER-SLOT DECISION SCOPING
            through a collection. A failure at a NON-sensitive slot (:age)
            inside a collection element whose CONFORMING sibling (:secret) is
            sensitive: the LEAF-NARROWED `:value` slot (the failing :age leaf,
            \"no\") rides verbatim — the precise-narrowing win — but the
            WHOLE-PAYLOAD `:explain` slot (the whole vector, conforming :secret
            included) redacts under the root check, else the conforming
            sensitive sibling egresses"
    ;; :secret is sensitive AND conforms; :age is non-sensitive AND fails.
    (let [v (app-db-failure-trace
              [:people]
              [:vector [:map
                        [:secret {:sensitive? true} :string]
                        [:age :int]]]
              {:people [{:secret "SECRET-OK-9f3a" :age "no"}]}
              :people/bad)]
      (is (some? v))
      ;; Narrowed slot — only the failing :age leaf, verbatim.
      (is (= "no" (-> v :tags :value))
          ":value (narrowed to the failing [:people 0 :age] leaf) rides verbatim")
      ;; Whole-payload slot — carries the conforming :secret; redacts.
      (is (= :rf/redacted (-> v :tags :explain))
          ":explain (whole vector) redacted — it carries the conforming sensitive :secret")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — a whole-payload slot redacted")
      (is (not (str/includes? (pr-str (:tags v)) "SECRET-OK-9f3a"))
          "the conforming sensitive sibling does NOT egress anywhere in the tags"))))

;; ---- :set-element value leaks via the :path tag (rf2-ss06u.1) -------------
;; Malli reports a :set failure's :in segment as the failing ELEMENT VALUE
;; itself (a set has no positional index) — e.g. :in = ({:token 99 :ssn
;; "..."} :token). validate-app-schema! concats the raw :in into the
;; structural :path tag, which Spec 010 declares unredacted; for a :set that
;; ships the ENTIRE failing element map (sibling secrets included) verbatim
;; in :path, even though :value / :explain ARE correctly redacted. The fix
;; scrubs the :set-element segment to :rf/redacted while keeping navigable
;; :vector / :map-of / :tuple index/key segments. These fail before the fix
;; (the secret rides in :path) and pass after.

(deftest app-db-validation-set-path-carries-no-secret
  (testing "rf2-ss06u.1 — a :set of sensitive maps emits a :path tag with NO
            verbatim element value; the sensitive element (and its sibling
            secrets) never ship in :path"
    (let [v (app-db-failure-trace
              [:members]
              [:set [:map [:token {:sensitive? true} :string] [:ssn :string]]]
              {:members #{{:token 123456789 :ssn "078-05-1120"}}}
              :members/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      ;; The structural :path must NOT carry the element value. The
      ;; :set-element segment is scrubbed to the sentinel; the surrounding
      ;; navigable segments survive.
      (is (= [:members :rf/redacted :token] (-> v :tags :path))
          ":path's :set-element segment is the :rf/redacted sentinel")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "078-05-1120"))
          "the sibling :ssn secret does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "123456789"))
          "the sensitive :token value does NOT appear in :path")
      ;; Belt-and-braces: NO secret anywhere in the whole tag map.
      (is (not (str/includes? (pr-str (:tags v)) "078-05-1120"))
          "the secret does NOT appear anywhere in the emitted tags")
      (is (not (str/includes? (pr-str (:tags v)) "123456789"))
          "the sensitive token does NOT appear anywhere in the emitted tags"))))

(deftest app-db-validation-vector-path-stays-navigable
  (testing "rf2-ss06u.1 regression — :vector :path keeps its integer index
            (the navigable locator), only the value-bearing :set segment is
            scrubbed; :path remains a get-in locator for :vector"
    (let [v (app-db-failure-trace
              [:items]
              [:vector [:map [:token {:sensitive? true} :string]]]
              {:items [{:token "ok"} {:token 99}]}
              :items/bad)]
      (is (some? v))
      (is (= [:items 1 :token] (-> v :tags :path))
          ":path keeps the navigable vector index (1)")
      (is (= :rf/redacted (-> v :tags :value))))))

(deftest app-db-validation-map-of-path-stays-navigable
  (testing "rf2-ss06u.1 regression — :map-of :path keeps its map key (the
            navigable locator)"
    (let [v (app-db-failure-trace
              [:by-id]
              [:map-of :string [:map [:secret {:sensitive? true} :string]]]
              {:by-id {"a" {:secret 99}}}
              :by-id/bad)]
      (is (some? v))
      (is (= [:by-id "a" :secret] (-> v :tags :path))
          ":path keeps the navigable map-of key (\"a\")")
      (is (= :rf/redacted (-> v :tags :value))))))

(deftest app-db-validation-non-sensitive-set-path-rides-verbatim
  (testing "rf2-ss06u.1 regression — a NON-sensitive :set failure is not
            spuriously scrubbed (sanitisation only runs on sensitive
            failures); legacy :path behaviour preserved"
    (let [v (app-db-failure-trace
              [:tags2]
              [:set [:map [:name :string]]]
              {:tags2 #{{:name 99}}}
              :tags2/bad)]
      (is (some? v))
      (is (not (contains? v :sensitive?))
          "no :sensitive? stamp — nothing in the schema is sensitive")
      (is (not= :rf/redacted (-> v :tags :value))
          ":value rides verbatim — no spurious redaction"))))

;; ---- sensitive SCALAR collection elements / map-of KEYS in :path (rf2-612mri)
;; Two residual scalar-leak shapes the rf2-ss06u.1 :set-element scrub did NOT
;; cover, because the value-bearing scalar is the KEY (`:map-of`) or rides in
;; the FAIL-CLOSED tail under an ambiguous wrapper (`:orn`/`:multi`):
;;
;;   (a) `[:map-of [:string {:sensitive? true}] …]` — Malli reports the key
;;       VALUE verbatim as the `:in` key segment (`["secret-token-123" :age]`),
;;       and the prior `:map-of` branch KEPT every key (a navigable locator),
;;       so a secret-as-key shipped raw in `:path` / `:reason`.
;;   (b) `[:orn [:tokens [:set [:string {:sensitive? true}]]]]` — the `:orn`
;;       is multi-branch, so the walk drops to the fail-closed tail with the
;;       set element value as the remaining segment (`[123456789]`); the prior
;;       scalar-keep tail KEPT it, leaking the scalar secret in `:path` /
;;       `:reason`.
;;
;; The fix scrubs a `:map-of` key whose KEY SCHEMA declares `:sensitive?`, and
;; fail-closes EVERY tail segment (scalars included) past an unresolvable op,
;; while non-sensitive `:map-of` keys / `:vector` / `:tuple` indices stay
;; navigable. These fail before the fix (the secret rides in :path / :reason)
;; and pass after.

(deftest app-db-validation-map-of-sensitive-key-scrubbed-from-path
  (testing "rf2-612mri (a) — a :map-of with a :sensitive? KEY schema and a
            failing value child scrubs the secret key from :path AND :reason;
            the secret never appears anywhere in the emitted tags"
    (let [v (app-db-failure-trace
              [:by-token]
              [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
              {:by-token {"secret-token-123" {:age "not-an-int"}}}
              :by-token/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — the key schema is sensitive")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      ;; The sensitive KEY is scrubbed; the navigable inner :age segment
      ;; (a real :map key, not the secret) survives so :path stays locatable
      ;; down to the failing slot.
      (is (= [:by-token :rf/redacted :age] (-> v :tags :path))
          ":path's sensitive :map-of key segment is the :rf/redacted sentinel")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "secret-token-123"))
          "the secret key does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "secret-token-123"))
          "the secret key does NOT appear in the generated :reason text")
      ;; Belt-and-braces: NO secret anywhere in the whole tag map.
      (is (not (str/includes? (pr-str (:tags v)) "secret-token-123"))
          "the secret key does NOT appear anywhere in the emitted tags"))))

(deftest app-db-validation-orn-wrapped-set-sensitive-scalar-scrubbed
  (testing "rf2-612mri (b) — an :orn-wrapped :set of :sensitive? SCALARS scrubs
            the scalar element from :path AND :reason via the fail-closed tail;
            the scalar secret never appears anywhere in the emitted tags"
    (let [v (app-db-failure-trace
              [:tokens]
              [:orn [:tokens [:set [:string {:sensitive? true}]]]]
              {:tokens #{123456789}}
              :tokens/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — the set element schema is sensitive")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      ;; The :orn is ambiguous, so the walk fail-closes the tail — the
      ;; scalar set element is scrubbed (it is value-bearing, not a locator).
      (is (= [:tokens :rf/redacted] (-> v :tags :path))
          ":path's fail-closed scalar set-element segment is the :rf/redacted sentinel")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "123456789"))
          "the sensitive scalar element does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "123456789"))
          "the sensitive scalar element does NOT appear in the generated :reason text")
      (is (not (str/includes? (pr-str (:tags v)) "123456789"))
          "the sensitive scalar element does NOT appear anywhere in the emitted tags"))))

(deftest app-db-validation-non-sensitive-map-of-key-stays-navigable
  (testing "rf2-612mri regression — a :map-of whose KEY schema is NOT sensitive
            (only the nested VALUE slot is) keeps the navigable map key in
            :path; the fix scrubs sensitive KEYS only, never plain keys"
    (let [v (app-db-failure-trace
              [:by-id]
              [:map-of :string [:map [:secret {:sensitive? true} :string]]]
              {:by-id {"a" {:secret 99}}}
              :by-id/bad)]
      (is (some? v))
      (is (true? (:sensitive? v))
          "the nested :secret value slot is sensitive — redaction still runs")
      (is (= [:by-id "a" :secret] (-> v :tags :path))
          ":path keeps the navigable plain map-of key (\"a\") — no over-redaction")
      (is (= :rf/redacted (-> v :tags :value))))))

;; ---- sensitive :map-of KEY that is OPAQUE / nested-opaque (rf2-6ijdgh) -------
;; The rf2-612mri fix covered a VECTOR-form `:sensitive?` key
;; (`[:string {:sensitive? true}]`), whose sensitivity the pure-data walker sees
;; directly. The OPAQUE-key intersection was missed: a COMPILED `m/schema` value
;; (or a vector wrapper hiding one, e.g. `[:and (m/schema …)]`) used as the
;; `:map-of` KEY carries a `:sensitive?` flag Malli honours but the walker cannot
;; introspect — so `schema-has-sensitive?` on the key returns false. Before the
;; fix, `align-in-path`'s `:map-of` branch dropped the key and descended only the
;; VALUE schema, leaving `leaf-sensitive? = false`, so `:path`/`:reason` were
;; NEVER sanitised even though `:value`/`:explain` WERE redacted fail-closed (via
;; `schema-has-opaque-child?`): the secret KEY shipped VERBATIM in `:path` /
;; `:reason` — a fail-OPEN privacy leak. Verified on Malli 0.20.1 (JVM). The fix
;; ORs `schema-has-opaque-child?` into BOTH the align-in-path key-position
;; fallback (so the leaf resolves sensitive) AND the sanitize-sensitive-path
;; key-scrub gate (so the key is scrubbed) — deep whole-structure scan, not just
;; the value slot. These fail before the fix and pass after.

(deftest app-db-validation-opaque-map-of-sensitive-key-scrubbed-from-path
  (testing "rf2-6ijdgh — a :map-of with a COMPILED (m/schema) :sensitive? KEY and
            a failing value child scrubs the secret key from :path AND :reason;
            the secret never appears ANYWHERE in the whole emitted tag map
            (deep whole-structure scan, not just the redacted :value slot)"
    (let [v (app-db-failure-trace
              [:by-token]
              [:map-of (m/schema [:string {:sensitive? true}]) [:map [:age :int]]]
              {:by-token {"SECRET-KEY-XYZ" {:age "not-an-int"}}}
              :by-token/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — the opaque key fails closed")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      ;; The sensitive opaque KEY is scrubbed to the sentinel; the navigable
      ;; inner :age segment (a real :map key, not the secret) survives.
      (is (= [:by-token :rf/redacted :age] (-> v :tags :path))
          ":path's opaque sensitive :map-of key segment is the :rf/redacted sentinel")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "SECRET-KEY-XYZ"))
          "the secret key does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "SECRET-KEY-XYZ"))
          "the secret key does NOT appear in the generated :reason text")
      ;; Belt-and-braces: a DEEP whole-structure scan of the entire tag map —
      ;; the leak lived in :path/:reason while :value/:explain looked redacted.
      (is (not (str/includes? (pr-str (:tags v)) "SECRET-KEY-XYZ"))
          "the secret key does NOT appear ANYWHERE in the emitted tags"))))

(deftest app-db-validation-nested-opaque-map-of-sensitive-key-scrubbed-from-path
  (testing "rf2-6ijdgh — a :map-of whose KEY is a VECTOR WRAPPER hiding a compiled
            m/schema (`[:and (m/schema [:string {:sensitive? true}])]`) — the
            root is walkable EDN but the nested opaque child's :sensitive? is
            invisible to the walker — still scrubs the secret key everywhere"
    (let [v (app-db-failure-trace
              [:by-token]
              [:map-of [:and (m/schema [:string {:sensitive? true}])] [:map [:age :int]]]
              {:by-token {"NESTED-SECRET-ABC" {:age "not-an-int"}}}
              :by-token/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — the nested-opaque key fails closed")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      (is (= [:by-token :rf/redacted :age] (-> v :tags :path))
          ":path's nested-opaque sensitive :map-of key segment is the :rf/redacted sentinel")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "NESTED-SECRET-ABC"))
          "the secret key does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "NESTED-SECRET-ABC"))
          "the secret key does NOT appear in the generated :reason text")
      (is (not (str/includes? (pr-str (:tags v)) "NESTED-SECRET-ABC"))
          "the secret key does NOT appear ANYWHERE in the emitted tags"))))

;; ---- sensitive :map-of KEY nested under an ambiguous :or / :and wrapper -----
;; (rf2-jqx2at). `sanitize-sensitive-path` treated `:and` / `:or` as SINGLE-child
;; transparent wrappers and followed ONLY the first child. When a sensitive
;; `:map-of` KEY schema lives in a LATER branch — an `:or` whose non-sensitive
;; branch is first, or an `:and` whose sensitive conjunct is not first — the walk
;; descended the WRONG branch, classified the failing `:in` key segment as a
;; navigable `:map-of` locator (its key schema was the WRONG branch's
;; non-sensitive one), and KEPT it. So the raw sensitive key shipped VERBATIM in
;; `:path` and `:reason` (and thus every serialized trace tag) even though
;; `:value` / `:explain` were correctly redacted — the same scalar-key leak class
;; as rf2-612mri, but hidden behind a multi-branch wrapper. `:and` / `:or` are
;; MULTI-child ambiguous wrappers: with more than one child the branch that
;; produced the failing `:in` cannot be identified from the path alone (an `:or`
;; value matched some ONE branch; an `:and` value is constrained by ALL), so the
;; walk now fails CLOSED on the whole remaining tail (like `:multi` / `:orn`),
;; scrubbing the secret key. The degenerate single-child case stays an
;; unambiguous, precise descent (navigable locators preserved). These fail before
;; the fix (the secret key rides in `:path` / `:reason`) and pass after.

;; -- direct `sanitize-sensitive-path` unit tests (deterministic; independent of
;;    Malli's `:in` shape) --

(deftest sanitize-fails-closed-on-multi-child-or
  (testing "rf2-jqx2at — a sensitive :map-of key reached under a MULTI-child :or
            is scrubbed via the fail-closed tail (the ambiguous :or cannot pick
            a branch, so every remaining segment is scrubbed)"
    (let [schema [:or
                  [:map-of :int :int]                                     ;; branch 0 — non-sensitive key
                  [:map-of [:string {:sensitive? true}] [:map [:age :int]]]] ;; branch 1 — sensitive key
          in     ["secret-token-123" :age]
          out    (walker/sanitize-sensitive-path schema in)]
      (is (= [:rf/redacted :rf/redacted] out)
          "every tail segment past the ambiguous :or is scrubbed to the sentinel")
      (is (not (some #{"secret-token-123"} out))
          "the sensitive :map-of key does NOT survive in the sanitized path"))))

(deftest sanitize-fails-closed-on-multi-child-and
  (testing "rf2-jqx2at — a sensitive :map-of key reached under a MULTI-child :and
            (sensitive conjunct not first) is scrubbed via the fail-closed tail"
    (let [schema [:and
                  [:map-of :string [:map [:age :int]]]                    ;; conjunct 0 — non-sensitive key
                  [:map-of [:string {:sensitive? true}] [:map [:age :int]]]] ;; conjunct 1 — sensitive key
          in     ["secret-token-xyz" :age]
          out    (walker/sanitize-sensitive-path schema in)]
      (is (= [:rf/redacted :rf/redacted] out))
      (is (not (some #{"secret-token-xyz"} out))
          "the sensitive :map-of key does NOT survive in the sanitized path"))))

(deftest sanitize-single-child-and-or-descend-precisely
  (testing "rf2-jqx2at regression — a DEGENERATE single-child :and / :or is
            unambiguous, so the walk still descends precisely and KEEPS the
            navigable :map key (the fail-closed rule must not over-redact the
            unambiguous case)"
    (is (= [:k] (walker/sanitize-sensitive-path [:or  [:map [:k :int]]] [:k]))
        "single-child :or descends into its one branch and keeps the map key")
    (is (= [:k] (walker/sanitize-sensitive-path [:and [:map [:k :int]]] [:k]))
        "single-child :and descends into its one branch and keeps the map key")))

(deftest sanitize-scrubs-opaque-map-of-key
  (testing "rf2-6ijdgh — a :map-of whose KEY is a COMPILED m/schema (opaque to the
            pure-data walker, so schema-has-sensitive? on it is false) is scrubbed
            via the opaque-aware gate; the navigable inner :age key is kept"
    (let [schema [:map-of (m/schema [:string {:sensitive? true}]) [:map [:age :int]]]
          out    (walker/sanitize-sensitive-path schema ["SECRET-KEY-XYZ" :age])]
      (is (= [:rf/redacted :age] out)
          "the opaque sensitive :map-of key is scrubbed; the :age locator survives")
      (is (not (some #{"SECRET-KEY-XYZ"} out))
          "the secret key does NOT survive in the sanitized path"))))

(deftest sanitize-scrubs-nested-opaque-map-of-key
  (testing "rf2-6ijdgh — a :map-of KEY that is a vector wrapper hiding a compiled
            m/schema (`[:and (m/schema …)]`) is scrubbed via the recursive
            opaque-aware (schema-has-opaque-child?) gate"
    (let [schema [:map-of [:and (m/schema [:string {:sensitive? true}])] [:map [:age :int]]]
          out    (walker/sanitize-sensitive-path schema ["NESTED-SECRET-ABC" :age])]
      (is (= [:rf/redacted :age] out))
      (is (not (some #{"NESTED-SECRET-ABC"} out))
          "the nested-opaque secret key does NOT survive in the sanitized path"))))

(deftest schema-sensitive-at?-true-for-opaque-map-of-key
  (testing "rf2-6ijdgh — schema-sensitive-at? (the leaf decision that gates path
            sanitisation) is TRUE at a failing value under an opaque sensitive
            :map-of key; before the fix align-in-path descended only the VALUE
            schema and returned false, so the sanitiser never ran"
    (is (true? (walker/schema-sensitive-at?
                 [:map-of (m/schema [:string {:sensitive? true}]) [:map [:age :int]]]
                 ["SECRET-KEY-XYZ" :age]))
        "compiled m/schema key → leaf sensitive (fails closed)")
    (is (true? (walker/schema-sensitive-at?
                 [:map-of [:and (m/schema [:string {:sensitive? true}])] [:map [:age :int]]]
                 ["NESTED-SECRET-ABC" :age]))
        "nested-opaque [:and (m/schema …)] key → leaf sensitive (fails closed)")
    ;; Regression guard: a NON-sensitive, NON-opaque :map-of key must stay a
    ;; navigable locator (only the nested value is sensitive here) — no
    ;; over-redaction of the key.
    (is (false? (walker/schema-sensitive-at?
                  [:map-of :string [:map [:plain :int]]]
                  ["a" :plain]))
        "plain :map-of key with a fully non-sensitive value → NOT leaf-sensitive")))

;; -- end-to-end via validate-app-schema! (:path / :reason / whole-tags egress) --

(deftest app-db-validation-or-wrapped-map-of-sensitive-key-scrubbed
  (testing "rf2-jqx2at — a :map-of with a :sensitive? KEY schema nested under a
            MULTI-child :or (non-sensitive branch FIRST) scrubs the secret key
            from :path AND :reason; the secret never appears anywhere in tags"
    (let [v (app-db-failure-trace
              [:secrets]
              [:or
               [:map-of :int :int]
               [:map-of [:string {:sensitive? true}] [:map [:age :int]]]]
              {:secrets {"secret-token-abc" {:age "not-an-int"}}}
              :secrets/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — a sensitive key schema is in the :or")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "secret-token-abc"))
          "the secret key does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "secret-token-abc"))
          "the secret key does NOT appear in the generated :reason text")
      (is (not (str/includes? (pr-str (:tags v)) "secret-token-abc"))
          "the secret key does NOT appear anywhere in the emitted tags"))))

(deftest app-db-validation-and-wrapped-map-of-sensitive-key-scrubbed
  (testing "rf2-jqx2at — a :map-of with a :sensitive? KEY schema nested under a
            MULTI-child :and (sensitive conjunct NOT first) scrubs the secret key
            from :path AND :reason; the secret never appears anywhere in tags"
    (let [v (app-db-failure-trace
              [:secrets]
              [:and
               [:map-of :string [:map [:age :int]]]
               [:map-of [:string {:sensitive? true}] [:map [:age :int]]]]
              {:secrets {"secret-token-xyz" {:age "not-an-int"}}}
              :secrets/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — a sensitive key schema is in the :and")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      (is (not (str/includes? (pr-str (-> v :tags :path)) "secret-token-xyz"))
          "the secret key does NOT appear in :path")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "secret-token-xyz"))
          "the secret key does NOT appear in :reason")
      (is (not (str/includes? (pr-str (:tags v)) "secret-token-xyz"))
          "the secret key does NOT appear anywhere in the emitted tags"))))

(deftest app-db-validation-non-sensitive-or-key-stays-navigable
  (testing "rf2-jqx2at regression — a fully NON-sensitive multi-child :or failure
            is not stamped :sensitive? and its value rides verbatim
            (sanitize-sensitive-path is not invoked on a non-sensitive failure,
            so a plain :map-of key is untouched — no over-redaction)"
    (let [v (app-db-failure-trace
              [:data]
              [:or [:map-of :int :int] [:map-of :string :int]]
              {:data {"plain-key" "not-an-int"}}
              :data/bad)]
      (is (some? v) "a trace fired")
      (is (not (contains? v :sensitive?))
          "no :sensitive? stamp — nothing in the :or is sensitive")
      (is (not= :rf/redacted (-> v :tags :value))
          ":value rides verbatim — a non-sensitive failure is not scrubbed"))))

;; ---- ancestor-sensitive container wrapped by :and/:multi/:orn (rf2-ss06u.2)
;; When a slot is declared {:sensitive? true} as a CONTAINER and the failing
;; leaf lives under a transparent-but-unrecognised wrapper op
;; (:and / :or / :multi / :orn), align-in-path's :else fallback discards the
;; consumed-ancestor sensitivity — schema-has-sensitive? on the LEFTOVER
;; subtree returns false, so NOTHING is redacted and the trace is NOT stamped
;; :sensitive?. The failing value (and explain / humanized) ship VERBATIM —
;; a direct value leak. The fix carries the consumed prefix through the
;; fallback so a descendant failure under a sensitive ancestor is still
;; redacted + stamped. These fail before the fix and pass after.

(deftest app-db-validation-redacts-under-and-ancestor-sensitive
  (testing "rf2-ss06u.2 — a sensitive container wrapping an :and whose inner
            leaf fails redacts the value and stamps :sensitive?"
    (let [secret "SECRET-AND-9f3a"
          v (app-db-failure-trace
              [:root]
              [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
              {:root {:s {:k secret}}}
              :and/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present (consumed-ancestor sensitivity)")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      (is (not (str/includes? (pr-str (:tags v)) secret))
          "the raw secret does NOT appear anywhere in the emitted tags"))))

(deftest app-db-validation-redacts-under-multi-ancestor-sensitive
  (testing "rf2-ss06u.2 — sensitive container wrapping a :multi"
    (let [secret "SECRET-MULTI-deadbeef"
          v (app-db-failure-trace
              [:root]
              [:map [:s {:sensitive? true}
                     [:multi {:dispatch :t} [:a [:map [:t :keyword] [:k :int]]]]]]
              {:root {:s {:t :a :k secret}}}
              :multi/bad)]
      (is (some? v))
      (is (true? (:sensitive? v)))
      (is (= :rf/redacted (-> v :tags :value)))
      (is (not (str/includes? (pr-str (:tags v)) secret))
          "the raw secret does NOT leak"))))

(deftest app-db-validation-redacts-under-orn-ancestor-sensitive
  (testing "rf2-ss06u.2 — sensitive container wrapping an :orn"
    (let [secret "SECRET-ORN-cafe"
          v (app-db-failure-trace
              [:root]
              [:map [:s {:sensitive? true} [:orn [:a [:map [:k :int]]]]]]
              {:root {:s {:k secret}}}
              :orn/bad)]
      (is (some? v))
      (is (true? (:sensitive? v)))
      (is (= :rf/redacted (-> v :tags :value)))
      (is (not (str/includes? (pr-str (:tags v)) secret))))))

(deftest app-db-validation-redacts-under-or-ancestor-sensitive
  (testing "rf2-ss06u.2 — sensitive container wrapping an :or"
    (let [secret "SECRET-OR-1234"
          v (app-db-failure-trace
              [:root]
              [:map [:s {:sensitive? true} [:or [:map [:k :int]]]]]
              {:root {:s {:k secret}}}
              :or/bad)]
      (is (some? v))
      (is (true? (:sensitive? v)))
      (is (= :rf/redacted (-> v :tags :value)))
      (is (not (str/includes? (pr-str (:tags v)) secret))))))

(deftest schema-sensitive-at-ancestor-under-and-multi-orn
  (testing "rf2-ss06u.2 — schema-sensitive-at? returns true for a leaf under
            a sensitive ancestor wrapped by :and/:multi/:orn (the
            consumed-ancestor prefix must carry through align-in-path's
            fallback)"
    (is (true? (schemas/schema-sensitive-at?
                 [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
                 [:s :k]))
        ":and ancestor")
    (is (true? (schemas/schema-sensitive-at?
                 [:map [:s {:sensitive? true}
                        [:multi {:dispatch :t} [:a [:map [:t :keyword] [:k :int]]]]]]
                 [:s :k]))
        ":multi ancestor")
    (is (true? (schemas/schema-sensitive-at?
                 [:map [:s {:sensitive? true} [:orn [:a [:map [:k :int]]]]]]
                 [:s :k]))
        ":orn ancestor")
    ;; A sensitive SIBLING outside the consumed prefix must NOT taint the
    ;; failing slot (the precise-narrowing win is preserved).
    (is (false? (schemas/schema-sensitive-at?
                  [:map
                   [:s {:sensitive? true} [:and [:map [:k :int]]]]
                   [:other [:and [:map [:j :int]]]]]
                  [:other :j]))
        "a sibling's sensitivity does not taint a failure under a non-sensitive sibling")))

;; ---- walker unit tests for the :in-path alignment (rf2-g5auo) -------------

(deftest schema-sensitive-at-aligns-collection-index-segments
  (testing "rf2-g5auo — schema-sensitive-at? matches an index-bearing :in
            path against the walker's index-free decl path"
    ;; :vector-of-map, :in = [1 :token]
    (is (true? (schemas/schema-sensitive-at?
                 [:vector [:map [:token {:sensitive? true} :string]]]
                 [1 :token])))
    ;; :map-of value, :in = ["a" :secret]
    (is (true? (schemas/schema-sensitive-at?
                 [:map-of :string [:map [:secret {:sensitive? true} :string]]]
                 ["a" :secret])))
    ;; :tuple, :in = [1]
    (is (true? (schemas/schema-sensitive-at?
                 [:tuple :int [:string {:sensitive? true}]]
                 [1])))
    ;; deep mixed path, :in = [:items 0 :tok]
    (is (true? (schemas/schema-sensitive-at?
                 [:map [:items [:vector [:map [:tok {:sensitive? true} :string]]]]]
                 [:items 0 :tok])))
    ;; non-sensitive collection failure stays false
    (is (false? (schemas/schema-sensitive-at?
                  [:vector [:map [:name :string]]]
                  [0 :name])))
    ;; sibling-sensitive does not taint the failing non-sensitive leaf
    (is (false? (schemas/schema-sensitive-at?
                  [:vector [:map
                            [:secret {:sensitive? true} :string]
                            [:age :int]]]
                  [0 :age])))))

;; ---- :tuple element-precision — no sibling taint (rf2-ss06u.4) ------------
;; A bare :tuple's elements are HETEROGENEOUS — each position carries its own
;; schema, so the integer index IS the discriminating segment (the positional
;; analogue of a :map key). Marking ONE tuple position {:sensitive? true} must
;; NOT redact a failure at a DIFFERENT, non-sensitive position. The prior
;; behaviour emitted the element flag at the index-free tuple base-path and
;; align-in-path dropped the tuple index, collapsing all positions onto the
;; base-path so any one sensitive element tainted every sibling (privacy-SAFE
;; over-redaction, but a precision divergence from the rf2-oh4se no-sibling-
;; taint contract the :vector path holds — schemas_sensitive_test.clj:563-568).
;; The fix makes the walker emit position-pinned decl-paths ((conj base i))
;; and align-in-path KEEP the tuple index, so each position is independent —
;; mirroring the :vector / :map-of map-key discriminator with the index as the
;; tuple's discriminator. These assert the false (sibling) cases that returned
;; true before the fix; the true (self / ancestor / descendant) cases pin the
;; redaction direction is unchanged.

(deftest schema-sensitive-at-tuple-position-precise
  (testing "rf2-ss06u.4 — a bare :tuple's sensitivity is element-precise: a
            failure at a NON-sensitive sibling position is NOT redacted, while
            the declared-sensitive position (and ancestor/descendant) still is"
    (let [s0 [:tuple [:string {:sensitive? true}] :int]]   ;; element 0 sensitive
      ;; SELF — the sensitive position fails → redact.
      (is (true? (schemas/schema-sensitive-at? s0 [0]))
          "the declared-sensitive position 0 redacts")
      ;; SIBLING — the non-sensitive position fails → must NOT redact.
      (is (false? (schemas/schema-sensitive-at? s0 [1]))
          "element 0 sensitive must NOT taint a failure at the non-sensitive element 1"))
    (let [s1 [:tuple :int [:string {:sensitive? true}]]]   ;; element 1 sensitive
      (is (true? (schemas/schema-sensitive-at? s1 [1]))
          "the declared-sensitive position 1 redacts")
      (is (false? (schemas/schema-sensitive-at? s1 [0]))
          "element 1 sensitive must NOT taint a failure at the non-sensitive element 0"))
    ;; ANCESTOR / DESCENDANT — a tuple element that is itself a container with
    ;; a nested sensitive slot: a failure at the slot, at the whole element, or
    ;; at the whole tuple all redact (the value carries the secret); a failure
    ;; at the OTHER element does not.
    (let [s [:tuple [:map [:tok {:sensitive? true} :string]] :int]]
      (is (true?  (schemas/schema-sensitive-at? s [0 :tok])) "exact nested slot")
      (is (true?  (schemas/schema-sensitive-at? s [0]))      "ancestor of the secret")
      (is (true?  (schemas/schema-sensitive-at? s []))       "whole tuple carries the secret")
      (is (false? (schemas/schema-sensitive-at? s [1]))      "non-sensitive sibling element 1"))))

(deftest extract-tuple-emits-position-pinned-paths
  (testing "rf2-ss06u.4 — the walker emits a tuple element flag at its
            POSITION-pinned path ((conj base i)), not the index-free tuple
            base-path; this is what gives the sibling precision"
    (is (= {[0] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:tuple [:string {:sensitive? true}] :int] [])))
    (is (= {[1] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:tuple :int [:string {:sensitive? true}]] [])))
    ;; base-path threads through.
    (is (= {[:pt 0] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:tuple [:string {:sensitive? true}] :int] [:pt])))))

(deftest app-db-validation-tuple-sibling-narrowed-value-verbatim-whole-explain-redacted
  (testing "rf2-ss06u.4 + rf2-oh4se + rf2-3qam7b — PER-SLOT DECISION SCOPING on
            a :tuple. element 0 is {:sensitive?} :string (CONFORMING \"ok\");
            element 1 is :int supplied a string → only element 1 fails. The
            LEAF-NARROWED `:value` slot (the failing element 1, \"not-an-int\")
            rides verbatim — the position-precise narrowing win — but the
            WHOLE-PAYLOAD `:explain` slot (the whole tuple, conforming element 0
            included) redacts under the root check"
    (let [v (app-db-failure-trace
              [:point]
              [:tuple [:string {:sensitive? true}] :int]
              {:point ["SECRET-TUP-ok" "not-an-int"]}
              :point/bad)]
      (is (some? v) "a trace fired")
      ;; Narrowed slot — only the failing element 1, verbatim.
      (is (= "not-an-int" (-> v :tags :value))
          ":value (narrowed to the failing element 1) rides verbatim")
      ;; Whole-payload slot — carries the conforming element 0; redacts.
      (is (= :rf/redacted (-> v :tags :explain))
          ":explain (whole tuple) redacted — it carries the conforming sensitive element 0")
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp present — a whole-payload slot redacted")
      (is (not (str/includes? (pr-str (:tags v)) "SECRET-TUP-ok"))
          "the conforming sensitive element does NOT egress anywhere in the tags"))))

(deftest app-db-validation-tuple-sensitive-element-still-redacts
  (testing "rf2-ss06u.4 — no regression: when the SENSITIVE tuple element fails,
            the value is redacted and stamped (the precision fix must not
            under-redact the declared position)"
    ;; element 0 is {:sensitive?} :string but supplied an int → element 0 fails.
    (let [v (app-db-failure-trace
              [:point]
              [:tuple [:string {:sensitive? true}] :int]
              {:point [99 7]}
              :point/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v))
          "the sensitive element 0 failed — top-level :sensitive? stamp present")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted"))))

;; ---- redaction at event validation site ----------------------------------

(deftest event-validation-ignores-handler-meta-sensitive
  (testing "The handler-meta `:sensitive?` annotation has been removed.
            Event-payload validation traces are NOT redacted by
            handler-meta sensitivity; event vectors aren't `:map`-
            shaped so per-slot walker doesn't run either."
    (let [calls (atom 0)]
      (rf/reg-event :auth/sign-in
        {:doc        "Verify creds"
         :sensitive? true                                      ;; ignored — annotation removed
         :schema     [:cat [:= :auth/sign-in] :string :string]}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::ev (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/sign-in "ada" 42])
        (rf/unregister-listener! :trace ::ev)
        (is (= 0 @calls) "handler skipped — validation failed")
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (not (true? (:sensitive? v)))
              "no top-level :sensitive? stamp — handler-meta annotation removed")
          (is (= [:auth/sign-in "ada" 42] (-> v :tags :received))
              ":received rides verbatim — no redaction")
          ;; Structural slots survive.
          (is (= :event (-> v :tags :where)))
          (is (= :auth/sign-in (-> v :tags :event-id)))
          (is (= :auth/sign-in (-> v :tags :failing-id)))
          (is (= :auth/sign-in (-> v :tags :schema-id))))))))

(deftest event-validation-non-sensitive-passes-through-verbatim
  (testing "Backward-compat — a handler without :sensitive? emits the
            unredacted trace (legacy behaviour for non-sensitive
            handlers)"
    (rf/reg-event :user/register
      {:schema [:cat [:= :user/register]
                   [:map [:email :string] [:age :int]]]}
      (fn [{:keys [db]} [_ payload]] {:db (update db :users (fnil conj []) payload)}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::reg (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
      (rf/unregister-listener! :trace ::reg)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no top-level :sensitive? stamp on non-sensitive handler")
        (is (not (contains? (:tags v) :sensitive?))
            ":tags :sensitive? also absent — the stamp lives at top-level only")
        (is (= [:user/register {:email "carol@example.com" :age "no"}]
               (-> v :tags :received))
            ":received rides verbatim")
        (is (= [:user/register {:email "carol@example.com" :age "no"}]
               (-> v :tags :value))
            ":value rides verbatim")))))

;; ---- rf2-a5kzs (finding 1) — event-schema per-slot :sensitive? redaction --
;; Pre-fix `validate-event!` passed `walk-schema? false`, so a per-slot
;; `:sensitive?` inside the event schema (e.g. a `:cat` payload map) was
;; IGNORED and the failing payload leaked verbatim via :received / :value /
;; :explain to trace listeners / off-box consumers. The fix flips the walk on
;; for events (the event schema's `:cat`/`:catn` payload commonly IS map-shaped)
;; so a per-slot or container-level :sensitive? drives the redaction exactly as
;; on app-db / cofx / fx / sub surfaces. These fail before the flip and pass
;; after; the existing handler-meta test above pins that a NON-sensitive event
;; schema still rides verbatim (no over-redaction).

(deftest event-validation-redacts-sensitive-cat-payload-slot
  (testing "rf2-a5kzs — a failing event whose :cat payload map carries a
            per-slot {:sensitive? true} slot redacts :received / :value /
            :explain and stamps :sensitive? true"
    (let [secret "hunter2-DO-NOT-LEAK"
          calls  (atom 0)]
      (rf/reg-event :auth/login
        {:schema [:cat [:= :auth/login]
                  [:map
                   [:user :string]
                   ;; :password is sensitive AND wrong type (int) → fails.
                   [:password {:sensitive? true} :int]]]}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::login (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/login {:user "ada" :password secret}])
        (rf/unregister-listener! :trace ::login)
        (is (= 0 @calls) "handler skipped — validation failed")
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a trace fired")
          (is (true? (:sensitive? v))
              "top-level :sensitive? stamp — event schema declares a sensitive slot")
          (is (= :rf/redacted (-> v :tags :received)) ":received redacted")
          (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
          (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
          (is (not (str/includes? (pr-str (:tags v)) secret))
              "the raw secret does NOT appear anywhere in the emitted tags")
          ;; Structural slots survive.
          (is (= :event (-> v :tags :where)))
          (is (= :auth/login (-> v :tags :event-id))))))))

(deftest event-validation-redacts-container-level-sensitive-payload
  (testing "rf2-a5kzs — a container-level {:sensitive? true} on the event
            payload schema also drives redaction"
    (let [secret "TOKEN-leak-check"]
      (rf/reg-event :auth/token
        ;; The whole payload (element 1 of the :cat) is sensitive; supply an
        ;; int where :string is expected so it fails.
        {:schema [:cat [:= :auth/token] [:string {:sensitive? true}]]}
        (fn [{:keys [db]} _] {:db db}))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::tok (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/token 12345])
        (rf/unregister-listener! :trace ::tok)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v)) "container-level :sensitive? triggers redaction")
          (is (= :rf/redacted (-> v :tags :received)))
          (is (= :rf/redacted (-> v :tags :value))))))))

(deftest event-validation-non-sensitive-cat-still-verbatim
  (testing "rf2-a5kzs — no over-redaction: an event schema with a payload map
            that has NO :sensitive? slot still rides verbatim after the walk
            is enabled"
    (rf/reg-event :user/update
      {:schema [:cat [:= :user/update] [:map [:name :string] [:age :int]]]}
      (fn [{:keys [db]} _] {:db db}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::upd (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/update {:name "bob" :age "old"}])
      (rf/unregister-listener! :trace ::upd)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no :sensitive? stamp — nothing in the event schema is sensitive")
        (is (= [:user/update {:name "bob" :age "old"}] (-> v :tags :received))
            ":received rides verbatim — the walk did not spuriously redact")))))

;; ---- per-slot scoping: sensitive SIBLING, non-sensitive failure ----------
;; rf2-k0ew8n / rf2-4q681i / rf2-3qam7b. The shared `run-validation` path
;; (event / cofx / fx / sub) carries the WHOLE checked value in EVERY
;; value-bearing slot — `:value` / `:received` / `:explain` / `:rf.fx/args` /
;; `:rf.sub/query-v` are all the whole event-vector / cofx / fx-args /
;; sub-return value; NOTHING here is narrowed to the failing leaf (only the
;; app-db `:value` slot narrows). So a CONFORMING `:sensitive?` SIBLING (a
;; valid token next to a failing :count) rides INSIDE those whole-payload
;; slots.
;;
;; The earlier rf2-k0ew8n / rf2-4q681i shape decided redaction with the
;; LEAF-PRECISE `schema-sensitive-at?` (sibling-blind), so a non-sensitive
;; failing sibling cleared redaction and the CONFORMING sensitive sibling
;; egressed verbatim to Xray / Pair / off-box — the rf2-3qam7b leak. Per
;; PER-SLOT DECISION SCOPING (Mike's rf2-me69cb ruling) the redaction scope
;; MUST match the carried-value scope: a whole-payload slot uses the ROOT
;; `schema-has-sensitive?` check. So on these surfaces a sensitive sibling
;; ANYWHERE in the schema redacts the WHOLE value — the price of not
;; narrowing. (The app-db `:value` slot, which IS narrowed, keeps the
;; leaf-precise no-sibling-taint win — see `validate-app-schema!`'s tests
;; above.) These tests assert the conforming sensitive sibling is ABSENT
;; from every egressed slot.

;; EP-0017 (replaces the disabled rf2-oa2dun inject-cofx tests): the LIVE cofx
;; `:schema` path is the recordable-value check
;; (`re-frame.cofx/validate-recordable-value!` → `:rf.error/cofx-value-invalid`).
;; It routes its off-box `:value` slot through the SAME shared
;; `redact-validation-tags` seam (rf2-hdi6wr), so a `:sensitive?`-bearing
;; recordable cofx schema redacts identically. The recordable path carries the
;; WHOLE recordable value in `:value` (it is not narrowed), so a sensitive slot
;; ANYWHERE in the schema redacts the whole value. (It does not carry a
;; `:received` slot — only `:value`.)
(deftest recordable-cofx-conforming-sensitive-sibling-redacted-whole-value
  (testing "rf2-3qam7b (EP-0017 recordable path) — a recordable-cofx schema
            with a CONFORMING sensitive sibling (:token) AND a non-sensitive
            failing sibling (:count): the whole recordable value rides :value,
            so a sensitive sibling redacts the WHOLE value. The conforming
            sensitive sibling must be ABSENT from every egressed slot."
    (rf/reg-cofx :auth/ctx
      {:recordable? true :provided? true
       :schema [:map
                [:token {:sensitive? true} :string]
                [:count :int]]})
    (rf/reg-event :auth/use-ctx
      {:rf.cofx/requires [:auth/ctx]}
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::ctx (fn [ev] (swap! traces conj ev)))
      ;; :token "SECRET-COFX-tok" CONFORMS (sensitive sibling); :count fails
      ;; (string, not int) — the failing slot is the NON-sensitive sibling.
      (try
        (rf/dispatch-sync [:auth/use-ctx]
                          {:rf.cofx {:auth/ctx {:token "SECRET-COFX-tok"
                                                :count "not-an-int"}}})
        (catch clojure.lang.ExceptionInfo _))
      (rf/unregister-listener! :trace ::ctx)
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v) "a recordable-cofx validation failure was traced")
        (is (true? (:sensitive? v))
            ":sensitive? stamped — a whole-payload slot carries the conforming sensitive sibling")
        (is (= :rf/redacted (-> v :tags :value)) ":value (whole cofx) redacted")
        (is (not (str/includes? (pr-str (:tags v)) "SECRET-COFX-tok"))
            "the conforming sensitive sibling :token is ABSENT from every egressed slot")))))

(deftest recordable-cofx-sensitive-slot-failure-still-redacts
  (testing "rf2-k0ew8n (EP-0017 recordable path) — redaction still fires when
            the FAILING slot itself is sensitive (no privacy regression)"
    (rf/reg-cofx :auth/ctx2
      {:recordable? true :provided? true
       :schema [:map
                [:token {:sensitive? true} :string]
                [:count :int]]})
    (rf/reg-event :auth/use-ctx2
      {:rf.cofx/requires [:auth/ctx2]}
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::ctx2 (fn [ev] (swap! traces conj ev)))
      ;; :token fails (int, not string) — the FAILING slot IS sensitive.
      (try
        (rf/dispatch-sync [:auth/use-ctx2]
                          {:rf.cofx {:auth/ctx2 {:token 1234 :count 3}}})
        (catch clojure.lang.ExceptionInfo _))
      (rf/unregister-listener! :trace ::ctx2)
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            ":sensitive? stamped — the failing slot (:token) is sensitive")
        (is (= :rf/redacted (-> v :tags :value)) ":value redacted")))))

(deftest sub-validation-conforming-sensitive-sibling-redacted-whole-value
  (testing "rf2-3qam7b — a sub-return schema with a CONFORMING sensitive
            sibling (:token) AND a non-sensitive failing sibling (:count):
            the sub-return surface carries the WHOLE return value in every
            value-bearing slot, so the redaction scopes to the ROOT check and
            the WHOLE value redacts; the conforming sensitive sibling must be
            ABSENT from every egressed slot"
    (rf/reg-sub :auth/view
      {:schema [:map
                [:token {:sensitive? true} :string]
                [:count :int]]}
      (fn [_ _] {:token "SECRET-SUB-tok" :count "not-an-int"}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::view (fn [ev] (swap! traces conj ev)))
      @(rf/subscribe [:auth/view])
      (rf/unregister-listener! :trace ::view)
      (let [v (first (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                   (= :sub-return (-> % :tags :where)))
                             @traces))]
        (is (some? v) "a sub-return validation failure was traced")
        (is (true? (:sensitive? v))
            ":sensitive? stamped — a whole-payload slot carries the conforming sensitive sibling")
        (is (= :rf/redacted (-> v :tags :value)) ":value (whole return) redacted")
        (is (= :rf/redacted (-> v :tags :received)) ":received (whole return) redacted")
        (is (not (str/includes? (pr-str (:tags v)) "SECRET-SUB-tok"))
            "the conforming sensitive sibling :token is ABSENT from every egressed slot")))))

(deftest event-validation-cat-root-conforming-sensitive-sibling-redacted-whole-received
  (testing "rf2-4q681i + rf2-3qam7b — an event schema is `:cat`-rooted, and
            the event surface carries the WHOLE event vector in every
            value-bearing slot (`:received` / `:value` / `:explain`). When a
            CONFORMING sensitive payload slot (:password) rides next to a
            non-sensitive failing slot (:age), the whole-payload slots redact
            under the ROOT check — the conforming secret rides INSIDE the whole
            event vector, so the leaf-precise `[1 :age]` decision (sibling-blind)
            would have leaked it (the rf2-3qam7b leak this fix closes). The
            conforming sensitive sibling MUST be absent from every egressed slot."
    (let [secret "pw-MUST-NOT-LEAK"]
      (rf/reg-event :auth/profile
        {:schema [:cat [:= :auth/profile]
                  [:map
                   [:password {:sensitive? true} :string]
                   [:age :int]]]}
        (fn [{:keys [db]} _] {:db db}))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::prof (fn [ev] (swap! traces conj ev)))
        ;; :password conforms (a string, the sensitive sibling); :age fails
        ;; (a string, not int). The failing slot is NON-sensitive, but the
        ;; whole event vector (carrying the conforming :password) rides every
        ;; value-bearing slot.
        (rf/dispatch-sync [:auth/profile {:password secret :age "old"}])
        (rf/unregister-listener! :trace ::prof)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v))
              ":sensitive? stamped — a whole-payload slot carries the conforming sensitive :password")
          (is (= :rf/redacted (-> v :tags :received))
              ":received (whole event vector) redacted")
          (is (= :rf/redacted (-> v :tags :value))
              ":value (whole event vector) redacted")
          (is (not (str/includes? (pr-str (:tags v)) secret))
              "the conforming sensitive :password is ABSENT from every egressed slot"))))))

(deftest event-validation-cat-root-sensitive-slot-failure-still-redacts
  (testing "rf2-4q681i — no privacy regression: when the SENSITIVE `:cat`
            payload slot itself fails, the value is still redacted and
            stamped (the precision fix must not under-redact the declared
            position)"
    (let [secret 123456789]                 ;; an int — fails the :string slot
      (rf/reg-event :auth/profile2
        {:schema [:cat [:= :auth/profile2]
                  [:map
                   [:password {:sensitive? true} :string]
                   [:age :int]]]}
        (fn [{:keys [db]} _] {:db db}))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::prof2 (fn [ev] (swap! traces conj ev)))
        ;; :password fails (int, not string) — the FAILING slot IS sensitive.
        (rf/dispatch-sync [:auth/profile2 {:password secret :age 30}])
        (rf/unregister-listener! :trace ::prof2)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v))
              ":sensitive? stamped — the failing slot (:password) is sensitive")
          (is (= :rf/redacted (-> v :tags :received)) ":received redacted")
          (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
          (is (not (str/includes? (pr-str (:tags v)) (str secret)))
              "the raw secret does NOT appear anywhere in the emitted tags"))))))

(deftest event-validation-catn-root-conforming-sensitive-sibling-redacted-whole-received
  (testing "rf2-4q681i + rf2-3qam7b — `:catn` behaves like `:cat`: the whole
            event vector rides every value-bearing slot, so a CONFORMING
            sensitive payload slot (:password) next to a non-sensitive failing
            slot (:age) redacts under the ROOT check; the conforming secret is
            absent from every egressed slot"
    (let [secret "catn-pw-DO-NOT-LEAK"]
      (rf/reg-event :auth/profile-n
        {:schema [:catn
                  [:id [:= :auth/profile-n]]
                  [:payload [:map
                             [:password {:sensitive? true} :string]
                             [:age :int]]]]}
        (fn [{:keys [db]} _] {:db db}))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::profn (fn [ev] (swap! traces conj ev)))
        ;; :age fails (non-sensitive); :password conforms (sensitive sibling).
        (rf/dispatch-sync [:auth/profile-n {:password secret :age "old"}])
        (rf/unregister-listener! :trace ::profn)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v))
              ":sensitive? stamped — a whole-payload slot carries the conforming sensitive :password")
          (is (= :rf/redacted (-> v :tags :received))
              ":received (whole event vector) redacted")
          (is (not (str/includes? (pr-str (:tags v)) secret))
              "the conforming sensitive :password is ABSENT from every egressed slot"))))))

;; ---- walker unit tests for the rf2-4q681i :cat/:catn position-bearing fix --

(deftest extract-cat-emits-position-pinned-paths
  (testing "rf2-4q681i — the walker emits a :cat element flag at its
            POSITION-pinned path ((conj base i)), not the index-free :cat
            base-path; this is what gives the event-payload sibling precision"
    ;; element 1 (the payload) is a sensitive :string.
    (is (= {[1] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:cat [:= :id] [:string {:sensitive? true}]] [])))
    ;; per-slot flag inside a :cat payload MAP claims the position + key.
    (is (= {[1 :tok] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:cat [:= :id] [:map [:tok {:sensitive? true} :string] [:age :int]]] [])))
    ;; base-path threads through.
    (is (= {[:ev 1] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:cat [:= :id] [:string {:sensitive? true}]] [:ev])))))

(deftest extract-catn-emits-position-pinned-paths
  (testing "rf2-4q681i — `:catn` is position-bearing too; an entry-level OR a
            schema-level :sensitive? flag claims the element's POSITION-pinned
            path (the decorative name is NOT a path segment — Malli reports the
            integer index in :in)"
    ;; entry-level flag on the :catn entry props.
    (is (= {[1] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:catn [:id [:= :id]] [:tok {:sensitive? true} :string]] [])))
    ;; per-slot flag inside a :catn payload MAP claims position + key.
    (is (= {[1 :pw] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:catn [:id [:= :id]]
              [:payload [:map [:pw {:sensitive? true} :string] [:age :int]]]] [])))))

(deftest schema-sensitive-at-cat-position-precise
  (testing "rf2-4q681i — a :cat element's sensitivity is element-precise: a
            failure at a NON-sensitive sibling position is NOT redacted, while
            the declared-sensitive position (and its ancestor/descendant) is —
            mirrors the :tuple position-precision contract (rf2-ss06u.4)"
    (let [s [:cat [:= :id] [:map [:pw {:sensitive? true} :string] [:age :int]]]]
      ;; SELF / DESCENDANT — the sensitive payload slot fails → redact.
      (is (true?  (schemas/schema-sensitive-at? s [1 :pw])) "exact sensitive slot")
      (is (true?  (schemas/schema-sensitive-at? s [1]))     "ancestor of the secret")
      ;; SIBLING — the non-sensitive payload slot fails → must NOT redact.
      (is (false? (schemas/schema-sensitive-at? s [1 :age]))
          ":pw sensitive must NOT taint a failure at the non-sensitive :age")
      ;; the id position (element 0) carries no secret.
      (is (false? (schemas/schema-sensitive-at? s [0])) "the id position is not sensitive"))
    ;; whole-element sensitivity: element 1 entirely sensitive.
    (let [s [:cat [:= :id] [:string {:sensitive? true}]]]
      (is (true?  (schemas/schema-sensitive-at? s [1])) "the sensitive payload position redacts")
      (is (false? (schemas/schema-sensitive-at? s [0])) "the non-sensitive id position does not"))))

;; ---- rf2-a5kzs / rf2-o69h5 — shared validation-failure redaction seam ----
;; The production boundary interceptor (`re-frame.spec`) builds its own event-
;; failure tags and previously emitted them verbatim. The fix routes them
;; through the `:schemas/redact-validation-tags` seam so a sensitive event
;; payload is redacted at the boundary exactly as the dev-time step-1 path.
;; Per rf2-o69h5 the SAME seam is now the single redactor every off-namespace
;; validation-failure emit site shares (machine-data / sub-override /
;; flow-output / boundary). These test the seam directly (the schemas-owned
;; redaction surface).

(deftest redact-validation-tags-redacts-when-schema-sensitive
  (testing "rf2-a5kzs — redact-validation-tags scrubs the value-bearing boundary
            tags and stamps :sensitive? when the event schema is sensitive"
    (let [secret "boundary-secret-9f3a"
          schema [:cat [:= :auth/login]
                  [:map [:password {:sensitive? true} :string]]]
          tags   {:where      :event
                  :event-id   :auth/login
                  :failing-id :auth/login
                  :schema-id  :auth/login
                  :received   [:auth/login {:password secret}]
                  :value      [:auth/login {:password secret}]
                  :explain    {:value secret}
                  :source     :boundary
                  :recovery   :no-recovery}
          out    (schemas/redact-validation-tags schema tags)]
      (is (= :rf/redacted (:received out)) ":received redacted")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (true? (:sensitive? out)) ":sensitive? stamped")
      (is (not (str/includes? (pr-str out) secret))
          "the secret does not survive anywhere in the redacted boundary tags")
      ;; Structural slots survive.
      (is (= :event (:where out)))
      (is (= :auth/login (:event-id out)))
      (is (= :boundary (:source out))))))

(deftest redact-validation-tags-rides-verbatim-when-not-sensitive
  (testing "rf2-a5kzs — redact-validation-tags is a no-op when the event schema
            has no :sensitive? slot (boundary parity with the dev-time path)"
    (let [schema [:cat [:= :api/strict] :int]
          tags   {:where    :event
                  :event-id :api/strict
                  :received [:api/strict "not-an-int"]
                  :value    [:api/strict "not-an-int"]
                  :source   :boundary}
          out    (schemas/redact-validation-tags schema tags)]
      (is (= tags out) "tags ride back unchanged — nothing sensitive to redact")
      (is (not (contains? out :sensitive?))))))

;; ---- redaction at the recordable-cofx validation site --------------------
;;
;; EP-0017 replaced the injection-time cofx-validation site with the
;; recordable-value path (`re-frame.cofx/validate-recordable-value!` →
;; `:rf.error/cofx-value-invalid`). It routes its off-box `:value` slot through
;; the SAME `redact-validation-tags` seam, so the schema-walker still drives
;; redaction exclusively. The two tests below replace the disabled rf2-oa2dun
;; `inject-cofx` tests for this surface.

(deftest recordable-cofx-ignores-meta-sensitive
  (testing "The registration-meta `:sensitive?` annotation has been removed.
            A bare `:sensitive?` on the cofx registration meta no longer
            triggers redaction — the schema-walker drives the decision
            exclusively. With a plain `:string` schema (no per-slot sensitive
            prop) the recordable-value failure rides verbatim."
    (rf/reg-cofx :auth/credentials
      {:doc "A recordable auth-token coeffect"
       :recordable? true :provided? true
       :sensitive? true   ;; ignored — annotation removed
       :schema :string})
    (rf/reg-event :auth/use-creds
      {:rf.cofx/requires [:auth/credentials]}
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::cf (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:auth/use-creds]
                          {:rf.cofx {:auth/credentials 42}})  ;; int, fails :string
        (catch clojure.lang.ExceptionInfo _))
      (rf/unregister-listener! :trace ::cf)
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (true? (:sensitive? v)))
            "no :sensitive? stamp — schema has no per-slot :sensitive? prop")
        (is (= 42 (-> v :tags :value)))
        (is (= :auth/credentials (-> v :tags :rf.cofx/id))
            "structural :rf.cofx/id survives")))))

(deftest recordable-cofx-redacts-when-schema-container-sensitive
  (testing "A container-level :sensitive? on the recordable-cofx :schema also
            triggers redaction even when the registration meta doesn't carry
            the flag"
    (rf/reg-cofx :secret-blob
      {:recordable? true :provided? true
       :schema [:string {:sensitive? true}]})
    (rf/reg-event :use-secret
      {:rf.cofx/requires [:secret-blob]}
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::cb (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:use-secret]
                          {:rf.cofx {:secret-blob 99}})  ;; int, fails :string
        (catch clojure.lang.ExceptionInfo _))
      (rf/unregister-listener! :trace ::cb)
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            "container-level :sensitive? on the schema triggered redaction (top-level stamp per Spec 009 hoist)")
        (is (= :rf/redacted (-> v :tags :value)))))))

;; ---- redaction at sub-return validation site -----------------------------

(deftest sub-return-validation-redacts-when-sensitive
  (testing "Per Spec 010 §`:sensitive?` — schema-walker (`:vector
            [:string {:sensitive? true}]`) drives sub-return redaction
            now that the handler/sub-meta `:sensitive?` annotation has
            been removed."
    (rf/reg-event :secrets/init (fn [{:keys [db]} _] {:db {:secrets ["a-secret"]}}))
    (rf/reg-event :secrets/break (fn [{:keys [db]} _] {:db (assoc db :secrets [1 2 3])}))
    (rf/reg-sub :secrets
      {:schema [:vector [:string {:sensitive? true}]]}
      (fn [db _] (:secrets db)))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::sr (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:secrets/init])
      ;; First subscribe materialises; well-typed.
      (rf/subscribe-once [:secrets])
      (rf/dispatch-sync [:secrets/break])
      ;; Resubscribe; malformed return — fails.
      (rf/subscribe-once [:secrets])
      (rf/unregister-listener! :trace ::sr)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (pos? (count violations)))
        (let [v (first violations)]
          (is (true? (:sensitive? v))
              "top-level :sensitive? stamp on sub-return validation (per Spec 009 hoist)")
          (is (= :rf/redacted (-> v :tags :value))
              ":value redacted")
          (is (= :rf/redacted (-> v :tags :received)))
          (is (= :rf/redacted (-> v :tags :explain)))
          ;; Structural slots survive.
          (is (= :sub-return (-> v :tags :where)))
          (is (= :secrets (-> v :tags :rf.sub/id)))
          (is (= :replaced-with-default (:recovery v))))))))

(deftest sub-return-validation-redacts-query-v-when-sensitive
  (testing "Per Spec 010 §`:sensitive?` + rf2-adtp2 / rf2-p2adl Q2 —
            the caller-supplied :rf.sub/query-v on a sensitive sub-return
            failure is itself redacted. :rf.sub/query-v is a value-bearing
            slot on this surface (the lookup key typically carries
            the same secret material the sub's return schema is
            gating — user ids, auth tokens, document ids); without
            redaction the failure trace re-leaks it alongside the
            return value the existing clauses scrub."
    (rf/reg-event :tokens/init  (fn [{:keys [db]} _]   {:db {:tokens {"user-42-token" "ok"}}}))
    (rf/reg-event :tokens/break (fn [{:keys [db]} _] {:db (assoc-in db
                                                       [:tokens "user-42-token"]
                                                       99)})) ; int — fails :string
    (rf/reg-sub :token-for
      {:schema [:string {:sensitive? true}]}
      (fn [db [_ token]] (get-in db [:tokens token])))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::qv (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:tokens/init])
      ;; Well-typed return on the first call — no validation failure.
      (rf/subscribe-once [:token-for "user-42-token"])
      (rf/dispatch-sync [:tokens/break])
      ;; Malformed return on the second call — fires the sub-return failure.
      (rf/subscribe-once [:token-for "user-42-token"])
      (rf/unregister-listener! :trace ::qv)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)
            v (first violations)]
        (is (some? v) "a sub-return failure fired")
        (is (true? (:sensitive? v))
            "top-level :sensitive? stamp present")
        (is (= :rf/redacted (-> v :tags :rf.sub/query-v))
            ":rf.sub/query-v — the caller-supplied lookup key — is redacted")
        (is (= :rf/redacted (-> v :tags :value)))
        (is (= :rf/redacted (-> v :tags :received)))
        (is (= :rf/redacted (-> v :tags :explain)))
        ;; The structural slots still survive — consumers can still
        ;; route on the sub-id without seeing the lookup key.
        (is (= :sub-return (-> v :tags :where)))
        (is (= :token-for  (-> v :tags :rf.sub/id)))
        (is (= :token-for  (-> v :tags :failing-id)))))))

(deftest sub-return-validation-non-sensitive-rides-query-v-verbatim
  (testing "Backward-compat — a sub without :sensitive? emits :rf.sub/query-v
            verbatim on the validation-failure trace (legacy behaviour
            preserved for non-sensitive subs). Redaction is opt-in."
    (rf/reg-event :widgets/init  (fn [{:keys [db]} _]   {:db {:widgets {:w1 "ok"}}}))
    (rf/reg-event :widgets/break (fn [{:keys [db]} _] {:db (assoc-in db [:widgets :w1] 99)}))
    (rf/reg-sub :widget
      {:schema :string}                                  ; no :sensitive?
      (fn [db [_ wid]] (get-in db [:widgets wid])))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::plain (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:widgets/init])
      (rf/subscribe-once [:widget :w1])
      (rf/dispatch-sync [:widgets/break])
      (rf/subscribe-once [:widget :w1])
      (rf/unregister-listener! :trace ::plain)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no top-level :sensitive? stamp on non-sensitive sub")
        (is (= [:widget :w1] (-> v :tags :rf.sub/query-v))
            ":rf.sub/query-v rides verbatim — no redaction on non-sensitive subs")
        (is (= 99 (-> v :tags :value))
            ":value also rides verbatim — legacy backward-compat")))))

;; ---- humanized-explain redaction symmetry (rf2-qhq3f) --------------------
;; Per Spec 010 §Humanize-hook §Composition with `:sensitive?` — when the
;; failing slot is sensitive the substrate redacts BOTH `:explain` AND
;; `:explain-humanized` to `:rf/redacted` (symmetric). Before the fix the
;; humanization happened in the central emit seam AFTER `:explain` had
;; already been overwritten with `:rf/redacted`, so the humanizer was
;; handed the sentinel, returned nil, and `:explain-humanized` was
;; silently OMITTED — a contract drift (Xray's violation block prefers
;; `:explain-humanized` and would fall through to a missing slot) and a
;; missing regression on a privacy-sensitive path. These tests pin the
;; symmetric shape: present-and-redacted on sensitive failures,
;; real-payload on non-sensitive ones, never the raw value.

(deftest non-sensitive-app-db-failure-carries-humanized-payload
  (testing "rf2-qhq3f — a NON-sensitive app-db validation failure (Malli
            humanizer hook loaded) carries the real :explain-humanized
            payload alongside the raw :explain"
    (rf/reg-app-schema [:auth :token] [:string])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::hum-plain (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (rf/unregister-listener! :trace ::hum-plain)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "a trace fired")
        (is (not (contains? v :sensitive?))
            "non-sensitive — no top-level :sensitive? stamp")
        (is (contains? (:tags v) :explain-humanized)
            ":explain-humanized present on a non-sensitive failure")
        (is (not= :rf/redacted (-> v :tags :explain-humanized))
            ":explain-humanized is the real humanized payload, not the sentinel")
        (is (some? (-> v :tags :explain))
            ":explain rides verbatim too")))))

(deftest sensitive-app-db-failure-redacts-humanized-present-not-omitted
  (testing "rf2-qhq3f — a SENSITIVE app-db validation failure emits
            :explain-humanized :rf/redacted alongside :explain
            :rf/redacted. The slot is PRESENT (the sentinel), not
            omitted — symmetric redaction per Spec 010 §Humanize-hook"
    (rf/reg-app-schema [:auth :token] [:string {:sensitive? true}])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::hum-redact (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (rf/unregister-listener! :trace ::hum-redact)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "a trace fired")
        (is (true? (:sensitive? v)))
        (is (= :rf/redacted (-> v :tags :explain))
            ":explain redacted")
        (is (contains? (:tags v) :explain-humanized)
            ":explain-humanized PRESENT — not silently omitted (the drift this fixes)")
        (is (= :rf/redacted (-> v :tags :explain-humanized))
            ":explain-humanized is the :rf/redacted sentinel — symmetric with :explain")))))

(deftest sensitive-app-db-failure-humanized-leaks-no-raw-value
  (testing "rf2-qhq3f — the raw sensitive value never appears in
            :explain-humanized on a sensitive failure (fail-closed
            privacy proof). Use a distinctive secret so a leak would be
            unmistakable in the trace surface"
    (let [secret "TOP-SECRET-token-9f3a2"]
      ;; Schema wants an :int; the secret is a string — fails. The value
      ;; itself is the sensitive material that must not surface.
      (rf/reg-app-schema [:auth :token] [:int {:sensitive? true}])
      (let [traces (atom [])]
        (rf/register-listener! :trace ::no-leak (fn [ev] (swap! traces conj ev)))
        (schemas/validate-app-schema! {:auth {:token secret}} :auth/init-bad)
        (rf/unregister-listener! :trace ::no-leak)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v)))
          (is (= :rf/redacted (-> v :tags :explain-humanized)))
          (is (not (str/includes? (pr-str (:tags v)) secret))
              "the raw secret does NOT appear anywhere in the emitted tags"))))))

(deftest sensitive-sub-return-failure-redacts-humanized-present-not-omitted
  (testing "rf2-qhq3f — the meta-bearing run-validation path (sub-return)
            also emits :explain-humanized :rf/redacted (present, not
            omitted) on a sensitive failure, and the raw value never
            surfaces in the humanized slot"
    (let [secret "sub-secret-deadbeef"]
      (rf/reg-event :secrets/init  (fn [{:keys [db]} _] {:db {:secrets [secret]}}))
      (rf/reg-event :secrets/break (fn [{:keys [db]} _] {:db (assoc db :secrets [1 2 3])}))
      (rf/reg-sub :secrets
        {:schema [:vector [:string {:sensitive? true}]]}
        (fn [db _] (:secrets db)))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::sr-hum (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:secrets/init])
        (rf/subscribe-once [:secrets])          ;; well-typed first pass
        (rf/dispatch-sync [:secrets/break])
        (rf/subscribe-once [:secrets])          ;; malformed return — fails
        (rf/unregister-listener! :trace ::sr-hum)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a sub-return failure fired")
          (is (true? (:sensitive? v)))
          (is (= :rf/redacted (-> v :tags :explain)))
          (is (contains? (:tags v) :explain-humanized)
              ":explain-humanized present on the sub-return surface too")
          (is (= :rf/redacted (-> v :tags :explain-humanized))
              ":explain-humanized redacted on the meta-bearing run-validation path")
          (is (not (str/includes? (pr-str (:tags v)) secret))
              "the raw secret does not leak through the humanized slot"))))))

(deftest non-sensitive-sub-return-failure-carries-humanized-payload
  (testing "rf2-qhq3f — backward-compat on the sub-return surface: a
            non-sensitive sub keeps the real humanized payload"
    (rf/reg-event :widgets/init  (fn [{:keys [db]} _] {:db {:widgets {:w1 "ok"}}}))
    (rf/reg-event :widgets/break (fn [{:keys [db]} _] {:db (assoc-in db [:widgets :w1] 99)}))
    (rf/reg-sub :widget
      {:schema :string}                                  ;; no :sensitive?
      (fn [db [_ wid]] (get-in db [:widgets wid])))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::sr-plain (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:widgets/init])
      (rf/subscribe-once [:widget :w1])
      (rf/dispatch-sync [:widgets/break])
      (rf/subscribe-once [:widget :w1])
      (rf/unregister-listener! :trace ::sr-plain)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?)))
        (is (contains? (:tags v) :explain-humanized)
            ":explain-humanized present")
        (is (not= :rf/redacted (-> v :tags :explain-humanized))
            ":explain-humanized is the real humanized payload on a non-sensitive sub")))))

;; ---- composition with :large? --------------------------------------------

(deftest sensitive-overrides-large-on-same-slot
  (testing "Per Spec 010 §`:sensitive?` + Spec 009 §Unified wire-elision
            surface — a slot carrying both :sensitive? and :large? in
            schema-validation traces redacts on sensitivity; the size
            marker would re-leak :path / :bytes and is NOT emitted"
    ;; Schema declares the slot BOTH large and sensitive.
    (rf/reg-app-schema [:user :secret-pdf]
                       [:string {:sensitive? true :large? true}])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::both (fn [ev] (swap! traces conj ev)))
      ;; Value is a long string but is an int (42) here — actually let's
      ;; make it a wrong type to force a validation failure regardless
      ;; of how :large? would behave at runtime.
      (schemas/validate-app-schema! {:user {:secret-pdf 42}} :doc/bad)
      (rf/unregister-listener! :trace ::both)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            "the slot's :sensitive? flag claims the trace (top-level stamp per Spec 009 hoist)")
        (is (= :rf/redacted (-> v :tags :value))
            "sensitive drop wins on a both-flagged slot")
        ;; No size marker leaked into the value slot — the redaction
        ;; sentinel sits there instead of a {:rf.size/large-elided ...}
        ;; envelope.
        (is (not (and (map? (-> v :tags :value))
                      (contains? (-> v :tags :value) :rf.size/large-elided)))
            "no :rf.size/large-elided marker — would re-leak :path/:bytes")))))

;; ---- elision: redaction is dev-time -------------------------------------

(deftest sensitive-redaction-elides-with-validation
  (testing "Per Spec 010 §Production builds + Spec 009 §Production-elision
            behaviour — the entire validation body (including the
            redaction substitution) lives behind the
            interop/debug-enabled? gate. Production builds DCE both."
    (rf/reg-app-schema [:auth :token] [:string {:sensitive? true}])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::prod (fn [ev] (swap! traces conj ev)))
      (with-redefs [re-frame.interop/debug-enabled? false]
        (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad))
      (rf/unregister-listener! :trace ::prod)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "no validation trace fires when debug-enabled? is false — redaction is moot"))))

;; ---- rf2-u9bjgr — COMPILED / OPAQUE schema fail-closed redaction ----------
;;
;; A compiled `m/schema` value carries per-slot `{:sensitive? true}` props
;; Malli HONOURS for validate/explain, but the pure-data walker treats the
;; compiled value as an OPAQUE LEAF — `schema-has-sensitive?` returns false, so
;; the pre-fix validation emit shipped `:value` / `:received` / `:explain`
;; VERBATIM (while the equivalent VECTOR form redacted). EP-0015 fail-closed:
;; an opaque schema the walker cannot prove non-sensitive redacts as sensitive.

(deftest walker-opaque-predicate
  (testing "rf2-u9bjgr — schema-opaque? is true for a compiled m/schema /
            map / fn, false for vector-form EDN and bare keywords"
    (is (true? (schemas/schema-opaque?
                 (m/schema [:map [:password {:sensitive? true} :string]])))
        "compiled m/schema object is opaque")
    (is (true? (schemas/schema-opaque? {:not :a-schema})) "a map is opaque")
    (is (true? (schemas/schema-opaque? (fn [_] true))) "a fn is opaque")
    (is (false? (schemas/schema-opaque? [:map [:k :int]]))
        "vector-form EDN is walkable, not opaque")
    (is (false? (schemas/schema-opaque? :int)) "a bare keyword is not opaque")
    (is (false? (schemas/schema-opaque? :my/registry-ref))
        "a registry-ref keyword is not opaque (provably-safe-or-silent caveat)")))

(deftest event-validation-opaque-schema-fails-closed
  (testing "rf2-u9bjgr — a COMPILED schema with a :sensitive? slot redacts the
            failing event payload fail-closed (the walker cannot see the prop,
            so we redact rather than leak); the equivalent VECTOR form already
            redacts via the walker"
    (let [secret  "OPAQUE-COMPILED-SECRET-u9bjgr"
          compiled (m/schema
                     [:cat [:= :auth/login] [:map [:password {:sensitive? true} :string]]])
          traces  (atom [])]
      (rf/register-listener! :trace ::opq (fn [ev] (swap! traces conj ev)))
      ;; The password value is a VECTOR where a :string is required, so it FAILS
      ;; the schema; the failing value carries the sentinel.
      (schemas/validate-event! :auth/login [:auth/login {:password [secret]}]
                               {:schema compiled})
      (rf/unregister-listener! :trace ::opq)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "a validation-failure trace fired")
        (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
        (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
        (is (= :rf/redacted (-> v :tags :received)) ":received redacted")
        (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
        (is (not (str/includes? (pr-str v) secret))
            "the secret survives nowhere in the opaque-schema failure trace")))))

(deftest app-db-validation-opaque-schema-fails-closed
  (testing "rf2-u9bjgr — a compiled app-db schema with a :sensitive? slot
            redacts the failing post-commit slice fail-closed"
    (let [secret "OPAQUE-APPDB-SECRET-u9bjgr"]
      (rf/reg-app-schema [:user]
                         (m/schema [:map [:token {:sensitive? true} :string]]))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::opqdb (fn [ev] (swap! traces conj ev)))
        ;; :token is a VECTOR where a :string is required → fails the schema.
        (schemas/validate-app-schema! {:user {:token [secret]}} :user/bad)
        (rf/unregister-listener! :trace ::opqdb)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a validation-failure trace fired")
          (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
          (is (not (str/includes? (pr-str v) secret))
              "the secret survives nowhere in the opaque app-db failure trace"))))))

(deftest redact-validation-tags-opaque-schema-fails-closed
  (testing "rf2-u9bjgr — the off-namespace redact-validation-tags seam fails
            closed for a compiled / opaque schema (machine-data / sub-override /
            flow-output / boundary all route through it)"
    (let [secret   "OPAQUE-SEAM-SECRET-u9bjgr"
          compiled (m/schema [:map [:secret {:sensitive? true} :string]])
          tags     {:where    :machine-data
                    :value    {:secret secret}
                    :received {:secret secret}
                    :explain  {:value {:secret secret}}}
          out      (schemas/redact-validation-tags compiled tags)]
      (is (true? (:sensitive? out)) "fail-closed: :sensitive? stamped")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:received out)) ":received redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (not (str/includes? (pr-str out) secret))
          "the secret survives nowhere through the opaque seam"))))

;; ---- rf2-hi0tf8 — NESTED opaque schema fail-closed redaction --------------
;;
;; rf2-u9bjgr closed the TOP-LEVEL opaque case (a compiled `m/schema` value
;; registered directly). It left one gap: a VECTOR-FORM schema — introspectable
;; at its root — that embeds a compiled `m/schema` value as a CHILD somewhere
;; inside it. `walk-flagged-schema` recurses into vector-form structure but its
;; terminal `:else acc` bailout SILENTLY SKIPS a nested opaque child (no
;; declaration, no warning) exactly like it would the root, but the
;; `(or schema-has-sensitive? schema-opaque?)` fail-closed composition at every
;; call site only consulted `schema-opaque?` on the ROOT, which is false for a
;; vector-form schema regardless of what opaque values it nests. A nested
;; `{:sensitive? true}` slot Malli honours therefore rode every value-bearing
;; trace tag VERBATIM. The fix: `schema-has-opaque-child?` recurses the whole
;; tree (and `align-in-path` / `schema-sensitive-at?` also check the schema a
;; fully-resolved path ARRIVES at, since a leaf-precise app-db path can resolve
;; cleanly right onto a nested opaque slot).

(deftest schema-has-opaque-child-predicate
  (testing "rf2-hi0tf8 — schema-has-opaque-child? is true for a schema that IS
            opaque (mirrors schema-opaque?) AND for a vector-form schema that
            NESTS an opaque child at any depth; false when no opaque value is
            reachable anywhere in the tree"
    (is (true? (schemas/schema-has-opaque-child?
                 (m/schema [:map [:password {:sensitive? true} :string]])))
        "a root-opaque compiled schema is caught (subsumes schema-opaque?)")
    (is (true? (schemas/schema-has-opaque-child?
                 [:map [:token (m/schema [:string {:sensitive? true}])]]))
        "a :map slot whose tail is a compiled m/schema value is caught")
    (is (true? (schemas/schema-has-opaque-child?
                 [:cat [:= :auth/login]
                  (m/schema [:map [:password {:sensitive? true} :string]])]))
        "a :cat element that is a compiled m/schema value is caught")
    (is (true? (schemas/schema-has-opaque-child?
                 [:vector (m/schema [:string {:sensitive? true}])]))
        "a homogeneous :vector element schema that is opaque is caught")
    (is (true? (schemas/schema-has-opaque-child?
                 [:multi {:dispatch :kind}
                  [:a (m/schema [:map [:secret {:sensitive? true} :string]])]]))
        "an :multi dispatch branch that is a compiled m/schema value is caught")
    (is (false? (schemas/schema-has-opaque-child?
                  [:map [:id :int] [:name :string]
                   [:auth [:map [:token {:sensitive? true} :string]]]]))
        "an all-vector-form schema (however deep, however sensitive) has no
         opaque descendant")
    (is (false? (schemas/schema-has-opaque-child? :int))
        "a bare keyword has no opaque descendant")
    ;; rf2-hi0tf8 confirm-by-corpus: [:map [:n pos-int?]] is the EXACT shape
    ;; of the machine/data-schema-rollback conformance fixture's [:schemas
    ;; :data] schema. A bare predicate fn NESTED as a :map slot's tail cannot
    ;; carry a {props} map (there is no syntax for props on an unwrapped fn
    ;; — that requires [:fn {...} pred], itself vector-form); any
    ;; :sensitive?/:large? on the :n slot would live on the SLOT's entry
    ;; ([:n {:sensitive? true} pos-int?]), which walk-flagged-schema already
    ;; sees before touching the tail. A NESTED bare fn is therefore provably
    ;; flag-free — the SAME reasoning the walker's keyword exception
    ;; (rf2-ee38b.6) already applies — and must NOT be treated as opaque, or
    ;; every ordinary pos-int?/string?-style leaf in the codebase's own
    ;; conformance corpus would over-redact.
    (is (false? (schemas/schema-has-opaque-child? [:map [:n pos-int?]]))
        "a nested bare predicate fn (pos-int? / string? / …) is provably
         flag-free, same as a bare keyword — not opaque")
    ;; rf2-hi0tf8 — the ACTUAL runtime shape the conformance fixture hits:
    ;; the EDN loader (`clojure.edn/read-string`, no reader-resolver) reads
    ;; `pos-int?` as a bare SYMBOL, not a live fn value; Malli resolves the
    ;; symbol to the named fn/var at validate-time (confirmed: `(m/validate
    ;; [:map [:n 'pos-int?]] {:n 0})` => false, `{:n 5}` => true). A NESTED
    ;; bare symbol is exactly as provably flag-free as a nested bare fn.
    (is (false? (schemas/schema-has-opaque-child? [:map [:n 'pos-int?]]))
        "a nested bare SYMBOL (the EDN-sourced shape Malli resolves) is
         provably flag-free — not opaque")
    ;; rf2-hi0tf8 — the ROOT case does NOT get the nested exclusion: a bare
    ;; fn/symbol used AS THE WHOLE registered :schema (no wrapper at all,
    ;; e.g. re-frame.flows' {:schema (fn [v] ...)}) has no sibling {props}
    ;; position anywhere in ITS registration shape — unlike the nested-tail
    ;; case there is no entry one level up that could carry the flag — so
    ;; schema-opaque?'s existing fail-closed root treatment is preserved
    ;; here (flows_schema_validation_test/non-conforming-output-emits-
    ;; violation-but-still-writes pins exactly this via redact-validation-
    ;; tags, the seam this predicate feeds)."
    (is (true? (schemas/schema-has-opaque-child? pos-int?))
        "a bare fn used AS the whole schema still fails closed, matching
         schema-opaque? — the root/nested split is deliberate, not an
         oversight")
    (is (true? (schemas/schema-has-opaque-child? 'pos-int?))
        "a bare symbol used AS the whole schema likewise fails closed")))

(deftest event-validation-nested-opaque-schema-fails-closed
  (testing "rf2-hi0tf8 — a VECTOR-FORM event schema (root introspectable) whose
            :cat element is a NESTED compiled m/schema value with a
            :sensitive? slot inside still redacts the failing payload
            fail-closed. Pre-fix: schema-has-sensitive? silently skipped the
            opaque :cat element (no declaration recorded) and schema-opaque?
            on the ROOT [:cat ...] form was false, so sensitive? computed
            false and the secret rode verbatim — the equivalent fully-opaque
            schema (event-validation-opaque-schema-fails-closed above) already
            redacted."
    (let [secret        "NESTED-OPAQUE-EVENT-SECRET-hi0tf8"
          nested-opaque (m/schema [:map [:password {:sensitive? true} :string]])
          schema        [:cat [:= :auth/login] nested-opaque]
          traces        (atom [])]
      (rf/register-listener! :trace ::nested-opq-evt (fn [ev] (swap! traces conj ev)))
      ;; :password is a VECTOR where a :string is required -> fails the schema.
      (schemas/validate-event! :auth/login [:auth/login {:password [secret]}]
                               {:schema schema})
      (rf/unregister-listener! :trace ::nested-opq-evt)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v) "a validation-failure trace fired")
        (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
        (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
        (is (= :rf/redacted (-> v :tags :received)) ":received redacted")
        (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
        (is (not (str/includes? (pr-str v) secret))
            "the secret survives nowhere in the nested-opaque event failure trace")))))

(deftest app-db-validation-nested-opaque-schema-fails-closed
  (testing "rf2-hi0tf8 — a VECTOR-FORM app-db schema (root introspectable)
            whose failing slot's OWN tail is a nested compiled m/schema value
            declaring :sensitive? redacts fail-closed, even though the
            failing :in path resolves CLEANLY (align-in-path's :ok outcome)
            straight onto that opaque leaf. Pre-fix: schema-sensitive-at?'s
            :ok branch only consulted extract-sensitive-paths-from-schema
            (which cannot see into the opaque leaf, so it found no
            declaration) and never checked whether the arrival schema itself
            was opaque — the narrowed :value leaked the secret verbatim."
    (let [secret "NESTED-OPAQUE-APPDB-SECRET-hi0tf8"]
      (rf/reg-app-schema [:token]
                         [:map [:token (m/schema [:string {:sensitive? true}])]])
      (let [traces (atom [])]
        (rf/register-listener! :trace ::nested-opq-db (fn [ev] (swap! traces conj ev)))
        ;; :token's value is a VECTOR where the nested compiled schema
        ;; requires a :string -> fails exactly at the opaque leaf ([:token]).
        (schemas/validate-app-schema! {:token {:token [secret]}} :token/bad)
        (rf/unregister-listener! :trace ::nested-opq-db)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a validation-failure trace fired")
          (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
          (is (= :rf/redacted (-> v :tags :value)) ":value redacted at the leaf")
          (is (not (str/includes? (pr-str v) secret))
              "the secret survives nowhere in the nested-opaque app-db failure trace"))))))

(deftest redact-validation-tags-nested-opaque-schema-fails-closed
  (testing "rf2-hi0tf8 — the off-namespace redact-validation-tags seam
            (machine-data / sub-override / flow-output / boundary) fails
            closed for a VECTOR-FORM schema that nests a compiled / opaque
            m/schema child"
    (let [secret   "NESTED-OPAQUE-SEAM-SECRET-hi0tf8"
          schema   [:map [:secret (m/schema [:string {:sensitive? true}])]]
          tags     {:where    :machine-data
                    :value    {:secret secret}
                    :received {:secret secret}
                    :explain  {:value {:secret secret}}}
          out      (schemas/redact-validation-tags schema tags)]
      (is (true? (:sensitive? out)) "fail-closed: :sensitive? stamped")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:received out)) ":received redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (not (str/includes? (pr-str out) secret))
          "the secret survives nowhere through the nested-opaque seam"))))

;; ---- rf2-vmhu4i — :large? value-bearing slot elision ----------------------
;;
;; The validation-failure redaction path consulted only schema-has-sensitive?;
;; it never applied :large? schema metadata. A bad event against a :large?
;; slot shipped the raw large blob through :value / :received / :explain
;; instead of eliding it. Per Spec 010 §`:large?` (validation size-safety arm)
;; the value-bearing slots elide to the :rf.size/large-elided marker; sensitive
;; still wins over large (Spec 010 §Composition with `:large?`).

(deftest walker-has-large-predicate
  (testing "rf2-vmhu4i — schema-has-large? mirrors schema-has-sensitive? on the
            :large? flag"
    (is (true? (schemas/schema-has-large?
                 [:map [:blob {:large? true} :string]])))
    (is (true? (schemas/schema-has-large?
                 [:map [:doc [:map [:payload {:large? true} :string]]]]))
        "nested :large? detected")
    (is (false? (schemas/schema-has-large? [:map [:n :int]]))
        "no :large? slot → false")
    (is (false? (schemas/schema-has-large?
                  [:map [:secret {:sensitive? true} :string]]))
        ":sensitive? is not :large? — the flags are independent")))

(deftest large-marker-fields
  (testing "rf2-vmhu4i / rf2-9wvwpa — the elided value-bearing slot carries a
            well-formed :rf.size/large-elided marker carrying the canonical
            :reason :effect provenance (EP-0025 — the commit-plane
            classification default; NOT the removed :frame annotation, NOT the
            retired :reason :schema), with the REQUIRED :hint slot present"
    (let [blob   (apply str (repeat 200 "X"))
          traces (atom [])]
      (rf/register-listener! :trace ::lgm (fn [ev] (swap! traces conj ev)))
      (schemas/validate-event! :upload/save [:upload/save {:blob blob}]
                               {:schema [:cat [:= :upload/save]
                                         [:map [:blob {:large? true} :int]]]})
      (rf/unregister-listener! :trace ::lgm)
      (let [v      (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                                  @traces))
            marker (-> v :tags :value :rf.size/large-elided)]
        (is (some? v) "a validation-failure trace fired")
        (is (map? marker) ":value carries a :rf.size/large-elided marker")
        (is (= :effect (:reason marker))
            ":reason :effect — the EP-0025 canonical classification provenance default")
        (is (contains? marker :hint) ":hint slot present (REQUIRED by the contract)")
        (is (integer? (:bytes marker)) ":bytes is a byte count")
        (is (= [:rf.elision/at []] (:handle marker)) ":handle is the fetch handle")
        (is (true? (-> v :tags :large?)) ":tags :large? stamped")
        (is (not (str/includes? (pr-str v) blob))
            "the large blob survives nowhere verbatim in the failure trace")))))

(deftest event-validation-large-slot-elides
  (testing "rf2-vmhu4i — a :large? slot's validation failure elides :value /
            :received / :explain to the size marker rather than shipping the
            raw blob"
    (let [blob   (apply str (repeat 500 "Z"))
          traces (atom [])]
      (rf/register-listener! :trace ::lg (fn [ev] (swap! traces conj ev)))
      (schemas/validate-event! :upload/save [:upload/save {:blob blob}]
                               {:schema [:cat [:= :upload/save]
                                         [:map [:blob {:large? true} :int]]]})
      (rf/unregister-listener! :trace ::lg)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "a :large?-only failure is NOT stamped sensitive")
        (doseq [slot [:value :received :explain]]
          (is (contains? (-> v :tags slot) :rf.size/large-elided)
              (str slot " elided to the size marker")))
        (is (not (str/includes? (pr-str v) blob))
            "the raw blob never rides the trace")))))

(deftest app-db-validation-large-slot-elides
  (testing "rf2-vmhu4i — a :large? app-db slot's post-commit validation
            failure elides the value-bearing slots to the size marker"
    (let [blob (apply str (repeat 500 "Y"))]
      (rf/reg-app-schema [:upload] [:map [:blob {:large? true} :int]])
      (let [traces (atom [])]
        (rf/register-listener! :trace ::lgdb (fn [ev] (swap! traces conj ev)))
        (schemas/validate-app-schema! {:upload {:blob blob}} :upload/bad)
        (rf/unregister-listener! :trace ::lgdb)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (-> v :tags :large?)) ":tags :large? stamped")
          (is (contains? (-> v :tags :value) :rf.size/large-elided)
              ":value elided")
          (is (not (str/includes? (pr-str v) blob))
              "the raw blob never rides the app-db failure trace"))))))

(deftest large-and-sensitive-sensitive-wins
  (testing "rf2-vmhu4i / Spec 010 §Composition with `:large?` — a slot carrying
            BOTH :large? and :sensitive? redacts on sensitivity; NO
            :rf.size/large-elided marker is emitted (it would leak :bytes)"
    (let [secret (apply str (repeat 100 "S"))
          traces (atom [])]
      (rf/register-listener! :trace ::ls (fn [ev] (swap! traces conj ev)))
      (schemas/validate-event! :x [:x {:blob secret}]
                               {:schema [:cat [:= :x]
                                         [:map [:blob {:large? true :sensitive? true} :int]]]})
      (rf/unregister-listener! :trace ::ls)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v)) "sensitive stamp wins")
        (is (= :rf/redacted (-> v :tags :value)) ":value redacted (not a marker)")
        (is (not (-> v :tags :large?)) "no :large? stamp on a sensitive failure")
        (is (not (and (map? (-> v :tags :value))
                      (contains? (-> v :tags :value) :rf.size/large-elided)))
            "no size marker — would re-leak :bytes")
        (is (not (str/includes? (pr-str v) secret)))))))

(deftest redact-validation-tags-large-slot-elides
  (testing "rf2-vmhu4i — the off-namespace seam elides value-bearing slots for
            a :large? (non-sensitive) schema"
    (let [blob (apply str (repeat 300 "Q"))
          tags {:where   :flow-output
                :value   {:blob blob}
                :explain {:value {:blob blob}}}
          out  (schemas/redact-validation-tags
                 [:map [:blob {:large? true} :int]] tags)]
      (is (true? (:large? out)) ":large? stamped")
      (is (contains? (:value out) :rf.size/large-elided) ":value elided")
      (is (contains? (:explain out) :rf.size/large-elided) ":explain elided")
      (is (not (str/includes? (pr-str out) blob))
          "the blob survives nowhere through the seam"))))

(deftest non-large-non-sensitive-rides-verbatim
  (testing "rf2-vmhu4i — a plain (no :large? / :sensitive?) failure rides
            verbatim — the elision is precise, not a blanket marker"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::plainv (fn [ev] (swap! traces conj ev)))
      (schemas/validate-event! :api/x [:api/x {:n "nope"}]
                               {:schema [:cat [:= :api/x] [:map [:n :int]]]})
      (rf/unregister-listener! :trace ::plainv)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?)) "no :sensitive? stamp")
        (is (not (-> v :tags :large?)) "no :large? stamp")
        (is (= [:api/x {:n "nope"}] (-> v :tags :value))
            ":value rides verbatim")))))
