(ns re-frame.schemas-sensitive-test
  "JVM tests for the `:sensitive?` redaction contract in schema-validation
  error traces (rf2-kj51z).

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces, the validation hot path MUST consult the registered schema's
  per-slot `:sensitive?` (and the registration-meta `:sensitive?`)
  before including the failing value in the
  `:rf.error/schema-validation-failure` trace event. When either source
  declares the slot sensitive:

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

  Structural slots (`:path`, `:failing-id`, `:spec-id`, `:reason`) ride
  unchanged — consumers need them to locate the broken slot without
  leaking user data.

  This file covers three surfaces:

    1. **Walker unit tests** — `extract-sensitive-paths-from-schema`
       recognises every Malli shape `:sensitive?` can legally live in
       (slot-level props, container-level props, nested, dispatch-
       bearing combinators).
    2. **Redaction substitution** — direct invocation of
       `validate-app-db!` / `validate-event!` / `validate-cofx!` /
       `validate-sub-return!` against a `:sensitive?`-bearing schema
       fires a trace with the redaction shape pinned.
    3. **Backward-compat** — non-sensitive validation failures emit
       unchanged (`:value`, `:explain` ride verbatim; no top-level
       `:sensitive?` stamp on the event)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            ;; Per rf2-t0hq + rf2-qyfie — the Malli adapter ns must be
            ;; required at boot to publish the late-bind hook the
            ;; default validator routes through; absent the require,
            ;; the validator soft-passes per Spec 010 §Recommended
            ;; soft-pass.
            [re-frame.schemas.malli]
            [re-frame.spec :as spec]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (schemas/reset-schema-validator!)
  (spec/clear-boundary-warned-handler-ids!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

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
      (rf/register-trace-cb! ::redact (fn [ev] (swap! traces conj ev)))
      ;; The value at [:auth :token] is an int (42) — fails :string.
      (schemas/validate-app-db! {:auth {:token 42}} :auth/init-bad)
      (rf/remove-trace-cb! ::redact)
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
      (rf/register-trace-cb! ::slot (fn [ev] (swap! traces conj ev)))
      ;; password is an int — fails :string. Since validation is
      ;; per-registered-path, the whole [:user] value fails the schema.
      ;; But schema-sensitive-at? checks if [:user] is sensitive (no)
      ;; OR a child slot of [:user] under the registered path crosses
      ;; the failing path. Since reg-app-schema validates the whole
      ;; registered slot, we need the :sensitive? to flag the WHOLE
      ;; failure when ANY slot within is sensitive.
      (schemas/validate-app-db! {:user {:name "alice" :password 99}}
                                :user/bad)
      (rf/remove-trace-cb! ::slot)
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
      (rf/register-trace-cb! ::plain (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-db! {:count "not-an-int"} :count/bad)
      (rf/remove-trace-cb! ::plain)
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

;; ---- redaction at event validation site ----------------------------------

(deftest event-validation-redacts-when-registration-sensitive
  (testing "Per Spec 010 §`:sensitive?` — handler :sensitive? true
            triggers redaction of the event-payload validation trace"
    (let [calls (atom 0)]
      (rf/reg-event-db :auth/sign-in
        {:doc        "Verify creds"
         :sensitive? true
         :spec       [:cat [:= :auth/sign-in] :string :string]}
        (fn [db _] (swap! calls inc) db))
      (let [traces (atom [])]
        (rf/register-trace-cb! ::ev (fn [ev] (swap! traces conj ev)))
        ;; Malformed payload — second arg should be string.
        (rf/dispatch-sync [:auth/sign-in "ada" 42])
        (rf/remove-trace-cb! ::ev)
        (is (= 0 @calls) "handler skipped — validation failed")
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v))
              "top-level :sensitive? stamp present — consumers filter on this (per Spec 009 hoist)")
          (is (= :rf/redacted (-> v :tags :received))
              ":received — the event vector — is the redacted sentinel")
          (is (= :rf/redacted (-> v :tags :value))
              ":value — failing-value mirror — also redacted")
          (is (= :rf/redacted (-> v :tags :explain))
              ":explain — Malli explanation re-leaks the values")
          (is (not (contains? (:tags v) :event))
              ":event slot is gone (rf2-4fbsd) — consumers reach for :received")
          (is (not (contains? (:tags v) :malli-error))
              ":malli-error slot is gone (rf2-4fbsd) — consumers reach for :explain")
          ;; Structural slots survive.
          (is (= :event (-> v :tags :where)))
          (is (= :auth/sign-in (-> v :tags :event-id)))
          (is (= :auth/sign-in (-> v :tags :failing-id)))
          (is (= :auth/sign-in (-> v :tags :spec-id))))))))

(deftest event-validation-non-sensitive-passes-through-verbatim
  (testing "Backward-compat — a handler without :sensitive? emits the
            unredacted trace (legacy behaviour for non-sensitive
            handlers)"
    (rf/reg-event-db :user/register
      {:spec [:cat [:= :user/register]
                   [:map [:email :string] [:age :int]]]}
      (fn [db [_ payload]] (update db :users (fnil conj []) payload)))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::reg (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
      (rf/remove-trace-cb! ::reg)
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

(deftest cofx-validation-redacts-when-cofx-meta-sensitive
  (testing "Per Spec 010 §`:sensitive?` — cofx :sensitive? true triggers
            redaction of the cofx-validation trace"
    (rf/reg-cofx :auth/credentials
      {:doc "Inject the user's auth token"
       :sensitive? true
       :spec :string}
      (fn [ctx] (assoc-in ctx [:coeffects :auth/credentials] 42))) ; int, not string
    (rf/reg-event-fx :auth/use-creds
      [(rf/inject-cofx :auth/credentials)]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cf (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth/use-creds])
      (rf/remove-trace-cb! ::cf)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            "top-level :sensitive? stamp on cofx validation (per Spec 009 hoist)")
        (is (= :rf/redacted (-> v :tags :value)))
        (is (= :rf/redacted (-> v :tags :received)))
        (is (= :rf/redacted (-> v :tags :explain)))
        (is (= :cofx (-> v :tags :where))
            "structural :where slot survives")
        (is (= :auth/credentials (-> v :tags :cofx-id))
            "structural :cofx-id survives")))))

(deftest cofx-validation-redacts-when-schema-container-sensitive
  (testing "A container-level :sensitive? on the cofx :spec also triggers
            redaction even when the cofx-meta doesn't carry the flag"
    (rf/reg-cofx :secret-blob
      {:spec [:string {:sensitive? true}]}
      (fn [ctx] (assoc-in ctx [:coeffects :secret-blob] 99))) ; int, fails :string
    (rf/reg-event-fx :use-secret
      [(rf/inject-cofx :secret-blob)]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:use-secret])
      (rf/remove-trace-cb! ::cb)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            "container-level :sensitive? on the schema triggered redaction (top-level stamp per Spec 009 hoist)")
        (is (= :rf/redacted (-> v :tags :value)))))))

;; ---- redaction at sub-return validation site -----------------------------

(deftest sub-return-validation-redacts-when-sensitive
  (testing "Per Spec 010 §`:sensitive?` — sub :sensitive? true triggers
            redaction of the sub-return validation trace"
    (rf/reg-event-db :secrets/init (fn [_ _] {:secrets ["a-secret"]}))
    (rf/reg-event-db :secrets/break (fn [db _] (assoc db :secrets [1 2 3])))
    (rf/reg-sub :secrets
      {:sensitive? true
       :spec [:vector :string]}
      (fn [db _] (:secrets db)))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::sr (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:secrets/init])
      ;; First subscribe materialises; well-typed.
      (rf/subscribe-value [:secrets])
      (rf/dispatch-sync [:secrets/break])
      ;; Resubscribe; malformed return — fails.
      (rf/subscribe-value [:secrets])
      (rf/remove-trace-cb! ::sr)
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
          (is (= :secrets (-> v :tags :sub-id)))
          (is (= :replaced-with-default (:recovery v))))))))

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
      (rf/register-trace-cb! ::both (fn [ev] (swap! traces conj ev)))
      ;; Value is a long string but is an int (42) here — actually let's
      ;; make it a wrong type to force a validation failure regardless
      ;; of how :large? would behave at runtime.
      (schemas/validate-app-db! {:user {:secret-pdf 42}} :doc/bad)
      (rf/remove-trace-cb! ::both)
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
      (rf/register-trace-cb! ::prod (fn [ev] (swap! traces conj ev)))
      (with-redefs [re-frame.interop/debug-enabled? false]
        (schemas/validate-app-db! {:auth {:token 42}} :auth/init-bad))
      (rf/remove-trace-cb! ::prod)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "no validation trace fires when debug-enabled? is false — redaction is moot"))))
