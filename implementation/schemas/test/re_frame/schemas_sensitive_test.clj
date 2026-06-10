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
       `validate-app-schema!` / `validate-event!` / `validate-cofx!` /
       `validate-sub!` against a `:sensitive?`-bearing schema
       fires a trace with the redaction shape pinned.
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
      (rf/register-listener! ::redact (fn [ev] (swap! traces conj ev)))
      ;; The value at [:auth :token] is an int (42) — fails :string.
      (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (rf/unregister-listener! ::redact)
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
      (rf/register-listener! ::slot (fn [ev] (swap! traces conj ev)))
      ;; password is an int — fails :string. Since validation is
      ;; per-registered-path, the whole [:user] value fails the schema.
      ;; But schema-sensitive-at? checks if [:user] is sensitive (no)
      ;; OR a child slot of [:user] under the registered path crosses
      ;; the failing path. Since reg-app-schema validates the whole
      ;; registered slot, we need the :sensitive? to flag the WHOLE
      ;; failure when ANY slot within is sensitive.
      (schemas/validate-app-schema! {:user {:name "alice" :password 99}}
                                :user/bad)
      (rf/unregister-listener! ::slot)
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
      (rf/register-listener! ::plain (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-schema! {:count "not-an-int"} :count/bad)
      (rf/unregister-listener! ::plain)
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
    (rf/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (schemas/validate-app-schema! db failing-id)
    (rf/unregister-listener! kw)
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

(deftest app-db-validation-collection-sibling-sensitive-not-over-redacted
  (testing "rf2-g5auo + rf2-oh4se — a failure at a NON-sensitive slot inside
            a collection element must NOT inherit a sibling slot's
            sensitivity (the precise-narrowing win still holds through
            collections)"
    ;; :secret is sensitive, :age is not; only :age fails (string, not int).
    (let [v (app-db-failure-trace
              [:people]
              [:vector [:map
                        [:secret {:sensitive? true} :string]
                        [:age :int]]]
              {:people [{:secret "ok" :age "no"}]}
              :people/bad)]
      (is (some? v))
      (is (not (contains? v :sensitive?))
          "the failing :age slot is not sensitive; its sibling :secret doesn't taint it")
      (is (not= :rf/redacted (-> v :tags :value))
          ":value rides verbatim — only the failing :age leaf is in the path"))))

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

(deftest app-db-validation-tuple-sibling-not-over-redacted
  (testing "rf2-ss06u.4 + rf2-oh4se — END-TO-END: a :tuple with one sensitive
            and one non-sensitive element, where ONLY the non-sensitive sibling
            fails, must ride VERBATIM (no :sensitive? stamp, no :rf/redacted) —
            mirrors the g5auo :vector no-sibling-taint contract"
    ;; element 0 is {:sensitive?} :string (valid value "ok");
    ;; element 1 is :int but supplied a string → only element 1 fails.
    (let [v (app-db-failure-trace
              [:point]
              [:tuple [:string {:sensitive? true}] :int]
              {:point ["ok" "not-an-int"]}
              :point/bad)]
      (is (some? v) "a trace fired")
      (is (not (contains? v :sensitive?))
          "the failing element 1 is not sensitive; its sensitive sibling at element 0 doesn't taint it")
      (is (not= :rf/redacted (-> v :tags :value))
          ":value rides verbatim — only the non-sensitive element 1 failed"))))

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
      (rf/reg-event-db :auth/sign-in
        {:doc        "Verify creds"
         :sensitive? true                                      ;; ignored — annotation removed
         :schema     [:cat [:= :auth/sign-in] :string :string]}
        (fn [db _] (swap! calls inc) db))
      (let [traces (atom [])]
        (rf/register-listener! ::ev (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/sign-in "ada" 42])
        (rf/unregister-listener! ::ev)
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
    (rf/reg-event-db :user/register
      {:schema [:cat [:= :user/register]
                   [:map [:email :string] [:age :int]]]}
      (fn [db [_ payload]] (update db :users (fnil conj []) payload)))
    (let [traces (atom [])]
      (rf/register-listener! ::reg (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
      (rf/unregister-listener! ::reg)
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
      (rf/reg-event-db :auth/login
        {:schema [:cat [:= :auth/login]
                  [:map
                   [:user :string]
                   ;; :password is sensitive AND wrong type (int) → fails.
                   [:password {:sensitive? true} :int]]]}
        (fn [db _] (swap! calls inc) db))
      (let [traces (atom [])]
        (rf/register-listener! ::login (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/login {:user "ada" :password secret}])
        (rf/unregister-listener! ::login)
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
      (rf/reg-event-db :auth/token
        ;; The whole payload (element 1 of the :cat) is sensitive; supply an
        ;; int where :string is expected so it fails.
        {:schema [:cat [:= :auth/token] [:string {:sensitive? true}]]}
        (fn [db _] db))
      (let [traces (atom [])]
        (rf/register-listener! ::tok (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/token 12345])
        (rf/unregister-listener! ::tok)
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
    (rf/reg-event-db :user/update
      {:schema [:cat [:= :user/update] [:map [:name :string] [:age :int]]]}
      (fn [db _] db))
    (let [traces (atom [])]
      (rf/register-listener! ::upd (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/update {:name "bob" :age "old"}])
      (rf/unregister-listener! ::upd)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no :sensitive? stamp — nothing in the event schema is sensitive")
        (is (= [:user/update {:name "bob" :age "old"}] (-> v :tags :received))
            ":received rides verbatim — the walk did not spuriously redact")))))

;; ---- path-targeted sensitivity: sensitive SIBLING, non-sensitive failure --
;; rf2-k0ew8n (finding 1). BEFORE: the shared run-validation path decided
;; redaction with the COARSE `schema-has-sensitive?` (true if ANY slot in the
;; schema is sensitive), so adding one sensitive field to a payload hid an
;; UNRELATED non-sensitive failure from Xray / Pair / trace debugging. AFTER:
;; run-validation derives the failing `:in` path from the explainer and asks
;; the PATH-TARGETED `schema-sensitive-at?` whether THAT slot is sensitive —
;; matching the precise model `validate-app-schema!` already used.
;;
;; SCOPE NOTE (rf2-k0ew8n follow-up): path-targeting is exact when the
;; schema ROOT is a navigable container (`:map` / `:tuple` / index-bearing)
;; — the cofx / fx-args / sub-return surfaces, whose schemas validate a map
;; value directly, get the precise behaviour below. EVENT schemas are
;; `:cat`-rooted (`[:cat [:= :id] PayloadSchema]`); the walker treats `:cat`
;; as a parent-path-collapsing wrapper (NOT position-bearing like `:tuple`),
;; so `align-in-path` cannot resolve the `:cat` element index and the check
;; degrades FAIL-SAFE (conservatively redacts) when any sibling is sensitive
;; — identical to the app-db path's no-extractable-`:in` fallback. Making
;; `:cat` / `:catn` position-bearing in the walker (so event payloads also
;; get exact path-targeting) is a coordinated walker + elision-coordinate
;; change tracked as a follow-up bead, NOT bundled here.

(deftest cofx-validation-sensitive-sibling-non-sensitive-failure-verbatim
  (testing "rf2-k0ew8n — a cofx schema (map-rooted) with a sensitive sibling
            (:token) AND a non-sensitive failing sibling (:count) rides the
            non-sensitive failure VERBATIM — path-targeting at a navigable
            root no longer over-redacts the unrelated failure"
    (rf/reg-cofx :auth/ctx
      {:schema [:map
                [:token {:sensitive? true} :string]
                [:count :int]]}
      ;; :token conforms; :count fails (string, not int) — failing slot is
      ;; the NON-sensitive sibling.
      (fn [ctx] (assoc-in ctx [:coeffects :auth/ctx]
                          {:token "t" :count "not-an-int"})))
    (rf/reg-event-fx :auth/use-ctx
      [(rf/inject-cofx :auth/ctx)]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! ::ctx (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth/use-ctx])
      (rf/unregister-listener! ::ctx)
      (let [v (first (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                   (= :cofx (-> % :tags :where)))
                             @traces))]
        (is (some? v) "a cofx validation failure was traced")
        (is (not (true? (:sensitive? v)))
            "no :sensitive? stamp — the failing slot (:count) is not sensitive")
        (is (not= :rf/redacted (-> v :tags :value))
            ":value rides verbatim — the sensitive sibling did not over-redact")
        (is (= {:token "t" :count "not-an-int"} (-> v :tags :value))
            "the non-sensitive failing value is reported verbatim for debugging")))))

(deftest cofx-validation-sensitive-slot-failure-still-redacts
  (testing "rf2-k0ew8n — the path-targeted check still REDACTS when the
            FAILING slot itself is sensitive (no privacy regression)"
    (rf/reg-cofx :auth/ctx2
      {:schema [:map
                [:token {:sensitive? true} :string]
                [:count :int]]}
      ;; :token fails (int, not string) — the FAILING slot IS sensitive.
      (fn [ctx] (assoc-in ctx [:coeffects :auth/ctx2]
                          {:token 1234 :count 3})))
    (rf/reg-event-fx :auth/use-ctx2
      [(rf/inject-cofx :auth/ctx2)]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! ::ctx2 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth/use-ctx2])
      (rf/unregister-listener! ::ctx2)
      (let [v (first (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                   (= :cofx (-> % :tags :where)))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            ":sensitive? stamped — the failing slot (:token) is sensitive")
        (is (= :rf/redacted (-> v :tags :value)) ":value redacted")))))

(deftest sub-validation-sensitive-sibling-non-sensitive-failure-verbatim
  (testing "rf2-k0ew8n — a sub-return schema (map-rooted) with a sensitive
            sibling AND a non-sensitive failing sibling rides the
            non-sensitive failure verbatim (path-targeting at a navigable
            root)"
    (rf/reg-sub :auth/view
      {:schema [:map
                [:token {:sensitive? true} :string]
                [:count :int]]}
      (fn [_ _] {:token "t" :count "not-an-int"}))
    (let [traces (atom [])]
      (rf/register-listener! ::view (fn [ev] (swap! traces conj ev)))
      @(rf/subscribe [:auth/view])
      (rf/unregister-listener! ::view)
      (let [v (first (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                   (= :sub-return (-> % :tags :where)))
                             @traces))]
        (is (some? v) "a sub-return validation failure was traced")
        (is (not (contains? v :sensitive?))
            "no :sensitive? stamp — the failing slot (:count) is not sensitive")
        (is (not= :rf/redacted (-> v :tags :value))
            ":value rides verbatim — the sensitive sibling did not over-redact")))))

(deftest event-validation-cat-root-fails-safe
  (testing "rf2-k0ew8n SCOPE — an event schema is `:cat`-rooted; the walker
            does not treat `:cat` as position-bearing, so a non-sensitive
            failing sibling under a sensitive payload degrades FAIL-SAFE
            (conservatively redacts) rather than leaking. Documented
            limitation; exact event-surface path-targeting is the follow-up
            `:cat`-walker bead. The privacy contract is upheld either way."
    (rf/reg-event-db :auth/profile
      {:schema [:cat [:= :auth/profile]
                [:map
                 [:password {:sensitive? true} :string]
                 [:age :int]]]}
      (fn [db _] db))
    (let [traces (atom [])]
      (rf/register-listener! ::prof (fn [ev] (swap! traces conj ev)))
      ;; :password conforms; :age fails — the failing slot is NON-sensitive,
      ;; but the `:cat` root blocks path resolution, so fail-safe redaction
      ;; fires (the sensitive sibling is present).
      (rf/dispatch-sync [:auth/profile {:password "pw" :age "old"}])
      (rf/unregister-listener! ::prof)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            "fail-safe: the `:cat` root blocks exact path-targeting, so a
             sensitive sibling still triggers conservative redaction")
        (is (= :rf/redacted (-> v :tags :received))
            ":received redacted — privacy upheld under the fail-safe path")))))

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

;; ---- redaction at cofx validation site -----------------------------------

(deftest cofx-validation-ignores-meta-sensitive
  (testing "The handler-meta `:sensitive?` annotation has been removed.
            Cofx-meta `:sensitive?` no longer triggers cofx-validation
            redaction — the schema-walker now drives the decision
            exclusively. With a plain `:string` spec (no per-slot
            sensitive prop) the failure rides verbatim."
    (rf/reg-cofx :auth/credentials
      {:doc "Inject the user's auth token"
       :sensitive? true   ;; ignored — annotation removed
       :schema :string}
      (fn [ctx] (assoc-in ctx [:coeffects :auth/credentials] 42)))
    (rf/reg-event-fx :auth/use-creds
      [(rf/inject-cofx :auth/credentials)]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! ::cf (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth/use-creds])
      (rf/unregister-listener! ::cf)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (true? (:sensitive? v)))
            "no top-level :sensitive? stamp — schema has no per-slot :sensitive? prop")
        (is (= 42 (-> v :tags :value)))
        (is (= 42 (-> v :tags :received)))
        (is (= :cofx (-> v :tags :where))
            "structural :where slot survives")
        (is (= :auth/credentials (-> v :tags :rf.cofx/id))
            "structural :rf.cofx/id survives")))))

(deftest cofx-validation-redacts-when-schema-container-sensitive
  (testing "A container-level :sensitive? on the cofx :schema also triggers
            redaction even when the cofx-meta doesn't carry the flag"
    (rf/reg-cofx :secret-blob
      {:schema [:string {:sensitive? true}]}
      (fn [ctx] (assoc-in ctx [:coeffects :secret-blob] 99))) ; int, fails :string
    (rf/reg-event-fx :use-secret
      [(rf/inject-cofx :secret-blob)]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-listener! ::cb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:use-secret])
      (rf/unregister-listener! ::cb)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
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
    (rf/reg-event-db :secrets/init (fn [_ _] {:secrets ["a-secret"]}))
    (rf/reg-event-db :secrets/break (fn [db _] (assoc db :secrets [1 2 3])))
    (rf/reg-sub :secrets
      {:schema [:vector [:string {:sensitive? true}]]}
      (fn [db _] (:secrets db)))
    (let [traces (atom [])]
      (rf/register-listener! ::sr (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:secrets/init])
      ;; First subscribe materialises; well-typed.
      (rf/subscribe-once [:secrets])
      (rf/dispatch-sync [:secrets/break])
      ;; Resubscribe; malformed return — fails.
      (rf/subscribe-once [:secrets])
      (rf/unregister-listener! ::sr)
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
    (rf/reg-event-db :tokens/init  (fn [_ _]   {:tokens {"user-42-token" "ok"}}))
    (rf/reg-event-db :tokens/break (fn [db _] (assoc-in db
                                                       [:tokens "user-42-token"]
                                                       99))) ; int — fails :string
    (rf/reg-sub :token-for
      {:schema [:string {:sensitive? true}]}
      (fn [db [_ token]] (get-in db [:tokens token])))
    (let [traces (atom [])]
      (rf/register-listener! ::qv (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:tokens/init])
      ;; Well-typed return on the first call — no validation failure.
      (rf/subscribe-once [:token-for "user-42-token"])
      (rf/dispatch-sync [:tokens/break])
      ;; Malformed return on the second call — fires the sub-return failure.
      (rf/subscribe-once [:token-for "user-42-token"])
      (rf/unregister-listener! ::qv)
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
    (rf/reg-event-db :widgets/init  (fn [_ _]   {:widgets {:w1 "ok"}}))
    (rf/reg-event-db :widgets/break (fn [db _] (assoc-in db [:widgets :w1] 99)))
    (rf/reg-sub :widget
      {:schema :string}                                  ; no :sensitive?
      (fn [db [_ wid]] (get-in db [:widgets wid])))
    (let [traces (atom [])]
      (rf/register-listener! ::plain (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:widgets/init])
      (rf/subscribe-once [:widget :w1])
      (rf/dispatch-sync [:widgets/break])
      (rf/subscribe-once [:widget :w1])
      (rf/unregister-listener! ::plain)
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
      (rf/register-listener! ::hum-plain (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (rf/unregister-listener! ::hum-plain)
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
      (rf/register-listener! ::hum-redact (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (rf/unregister-listener! ::hum-redact)
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
        (rf/register-listener! ::no-leak (fn [ev] (swap! traces conj ev)))
        (schemas/validate-app-schema! {:auth {:token secret}} :auth/init-bad)
        (rf/unregister-listener! ::no-leak)
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
      (rf/reg-event-db :secrets/init  (fn [_ _] {:secrets [secret]}))
      (rf/reg-event-db :secrets/break (fn [db _] (assoc db :secrets [1 2 3])))
      (rf/reg-sub :secrets
        {:schema [:vector [:string {:sensitive? true}]]}
        (fn [db _] (:secrets db)))
      (let [traces (atom [])]
        (rf/register-listener! ::sr-hum (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:secrets/init])
        (rf/subscribe-once [:secrets])          ;; well-typed first pass
        (rf/dispatch-sync [:secrets/break])
        (rf/subscribe-once [:secrets])          ;; malformed return — fails
        (rf/unregister-listener! ::sr-hum)
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
    (rf/reg-event-db :widgets/init  (fn [_ _] {:widgets {:w1 "ok"}}))
    (rf/reg-event-db :widgets/break (fn [db _] (assoc-in db [:widgets :w1] 99)))
    (rf/reg-sub :widget
      {:schema :string}                                  ;; no :sensitive?
      (fn [db [_ wid]] (get-in db [:widgets wid])))
    (let [traces (atom [])]
      (rf/register-listener! ::sr-plain (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:widgets/init])
      (rf/subscribe-once [:widget :w1])
      (rf/dispatch-sync [:widgets/break])
      (rf/subscribe-once [:widget :w1])
      (rf/unregister-listener! ::sr-plain)
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
      (rf/register-listener! ::both (fn [ev] (swap! traces conj ev)))
      ;; Value is a long string but is an int (42) here — actually let's
      ;; make it a wrong type to force a validation failure regardless
      ;; of how :large? would behave at runtime.
      (schemas/validate-app-schema! {:user {:secret-pdf 42}} :doc/bad)
      (rf/unregister-listener! ::both)
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
      (rf/register-listener! ::prod (fn [ev] (swap! traces conj ev)))
      (with-redefs [re-frame.interop/debug-enabled? false]
        (schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad))
      (rf/unregister-listener! ::prod)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "no validation trace fires when debug-enabled? is false — redaction is moot"))))
