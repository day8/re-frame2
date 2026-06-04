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
            ;; Per rf2-t0hq + rf2-qyfie — the Malli adapter ns must be
            ;; required at boot to publish the late-bind hook the
            ;; default validator routes through; absent the require,
            ;; the validator soft-passes per Spec 010 §Recommended
            ;; soft-pass.
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
