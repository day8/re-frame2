(ns re-frame.schemas-failure-paths-test
  "JVM tests for the precise / sensitivity-aware failure-path contract
  on `validate-app-db!` (rf2-oh4se).

  Pre-rf2-oh4se, `validate-app-db!` always emitted the registered
  schema root as `:path` and applied coarse whole-schema redaction
  whenever any nested slot in the schema declared `:sensitive?`. The
  audit (rf2-x8x4p) flagged two consequences:

    1. **Imprecise locator.** A consumer reading the trace had to walk
       the registered schema by hand to find the failing leaf — the
       trace itself only pointed at the registration root.
    2. **Over-broad redaction.** A failure at a non-sensitive sibling
       slot (e.g. `[:user :name]`) suffered redaction because the same
       schema declared a separate slot (e.g. `[:user :password]`)
       sensitive. The sensitive sibling's value did not appear in the
       failing leaf — but the whole `:user` map was shipped and
       redacted regardless.

  rf2-oh4se's fix:

    - Derive the failing leaf path from the Malli explainer's `:in`
      slot (the navigation path through the failing VALUE, not the
      schema-walk `:path` slot which encodes branch dispatch values).
    - Emit `:path` as the FULL leaf path
      (`(concat registered-path explain-in-path)`); emit
      `:registered-path` as the registration root for tooling that
      still wants the registration anchor.
    - Apply sensitivity targeted at the failing leaf:
      ancestor-sensitive OR descendant-sensitive at the leaf counts;
      a sibling-sensitive flag on an UN-failing slot does NOT trigger
      redaction.
    - Conservative fallback: when the explainer is absent / non-Malli
      / returns no extractable `:in`, fall back to the registered
      root as `:path` and the whole-schema sensitivity check.

  Backward-compat: `:explain` and the structural slots
  (`:failing-id`, `:where`, `:frame`, `:recovery`, `:reason`) ride
  the trace verbatim under both the precise and fallback paths."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(defn- capture-trace
  "Run `body-fn` while collecting :rf.error/schema-validation-failure
  trace events; return the vector of captured events."
  [body-fn]
  (let [traces (atom [])
        cb-id  (keyword (gensym "capture"))]
    (rf/register-trace-cb! cb-id (fn [ev] (swap! traces conj ev)))
    (try
      (body-fn)
      (finally (rf/remove-trace-cb! cb-id)))
    (filterv #(= :rf.error/schema-validation-failure (:operation %))
             @traces)))

;; ---- :path is the failing leaf, not the registration root ----------------

(deftest path-is-leaf-when-explainer-reports-in
  (testing "rf2-oh4se — :path is the registered path concat'd with the
            explainer's :in (the failing value's navigation path)"
    (rf/reg-app-schema [:user] [:map [:id :int] [:email :string]])
    (let [traces (capture-trace
                   #(schemas/validate-app-db!
                      {:user {:id "not-an-int" :email "alice@example.com"}}
                      :user/set-bad))]
      (is (= 1 (count traces)))
      (let [v (first traces)]
        (is (= [:user :id] (-> v :tags :path))
            ":path is the leaf — registered root [:user] + :in [:id]")
        (is (= [:user] (-> v :tags :registered-path))
            ":registered-path carries the registration anchor")
        (is (= :user/set-bad (-> v :tags :failing-id)))
        (is (= :app-db (-> v :tags :where)))))))

(deftest path-includes-leaf-in-reason-string
  (testing "the human-readable :reason carries the leaf path so the
            elision-probe substring stays distinctive per surface"
    (rf/reg-app-schema [:user] [:map [:age :int]])
    (let [traces (capture-trace
                   #(schemas/validate-app-db! {:user {:age "old"}} :u/bad))
          v      (first traces)]
      (is (.contains ^String (-> v :tags :reason) "[:user :age]")
          ":reason names the leaf path"))))

(deftest path-falls-back-to-registered-root-when-in-is-empty
  (testing "when the registered schema is itself the failing slot
            (Malli :in []), :path equals the registration root"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (capture-trace
                   #(schemas/validate-app-db! {:count "x"} :c/bad))
          v      (first traces)]
      (is (= [:count] (-> v :tags :path))
          ":path is the registered root — no leaf to narrow to")
      (is (= [:count] (-> v :tags :registered-path))))))

(deftest path-narrows-on-deeply-nested-failure
  (testing "deeply nested failures resolve the full leaf path through
            multiple :map levels"
    (rf/reg-app-schema [:root]
                       [:map
                        [:a [:map
                             [:b [:map
                                  [:c [:map [:d :int]]]]]]]])
    (let [traces (capture-trace
                   #(schemas/validate-app-db!
                      {:root {:a {:b {:c {:d "not-an-int"}}}}}
                      :r/bad))
          v      (first traces)]
      (is (= [:root :a :b :c :d] (-> v :tags :path))
          ":path is the full deep leaf"))))

;; ---- sensitivity is path-targeted, not whole-schema ----------------------

(deftest non-sensitive-sibling-failure-not-redacted
  (testing "rf2-oh4se — a failure at a non-sensitive sibling does NOT
            trigger redaction merely because another slot in the same
            schema is :sensitive?. The over-broad redaction was the
            symptom the bead fix targets."
    (rf/reg-app-schema [:user]
                       [:map
                        [:name     :string]
                        [:password {:sensitive? true} :string]])
    ;; :name is the failing leaf (int, not string); :password is
    ;; correct. The failing leaf [:user :name] is NOT sensitive; the
    ;; sibling [:user :password] is sensitive but does not appear in
    ;; the failing value. No redaction.
    (let [traces (capture-trace
                   #(schemas/validate-app-db!
                      {:user {:name 42 :password "secret-pw"}}
                      :u/bad-name))
          v      (first traces)]
      (is (not (contains? v :sensitive?))
          "no top-level :sensitive? stamp — failing leaf is non-sensitive")
      (is (not (contains? (:tags v) :sensitive?))
          ":tags :sensitive? also absent")
      (is (= 42 (-> v :tags :value))
          ":value rides verbatim — sibling-sensitive does not redact a
          non-sensitive leaf")
      (is (= [:user :name] (-> v :tags :path)))
      (is (some? (-> v :tags :explain))
          ":explain rides verbatim — the explainer output is the leaf's,
          not the whole schema's"))))

(deftest sensitive-leaf-failure-redacted
  (testing "the failing leaf IS the sensitive slot — redaction fires"
    (rf/reg-app-schema [:user]
                       [:map
                        [:name     :string]
                        [:password {:sensitive? true} :string]])
    ;; :password is the failing leaf (int, not string).
    (let [traces (capture-trace
                   #(schemas/validate-app-db!
                      {:user {:name "alice" :password 99}}
                      :u/bad-pw))
          v      (first traces)]
      (is (true? (:sensitive? v))
          "top-level :sensitive? stamp on the failing leaf's redaction")
      (is (= :rf/redacted (-> v :tags :value)))
      (is (= :rf/redacted (-> v :tags :explain)))
      (is (= [:user :password] (-> v :tags :path))
          ":path stays visible — structural slot survives redaction"))))

(deftest ancestor-sensitive-redacts
  (testing "a failure deep inside a sensitive container redacts — the
            failing slot is part of a sensitive subtree"
    (rf/reg-app-schema [:auth]
                       [:map {:sensitive? true}
                        [:token :string]
                        [:expiry :int]])
    ;; :token is the failing leaf (int, not string); the container
    ;; [:auth] is sensitive, so the whole subtree is sensitive.
    (let [traces (capture-trace
                   #(schemas/validate-app-db!
                      {:auth {:token 42 :expiry 9999}}
                      :auth/bad))
          v      (first traces)]
      (is (true? (:sensitive? v))
          "ancestor-sensitive — the failing leaf inherits sensitivity")
      (is (= :rf/redacted (-> v :tags :value))))))

(deftest descendant-sensitive-redacts
  (testing "a failure at a container whose descendant is sensitive
            redacts — the failing value carries the sensitive child"
    ;; The whole [:user] map fails because it's not even a map.
    (rf/reg-app-schema [:user]
                       [:map
                        [:name     :string]
                        [:password {:sensitive? true} :string]])
    (let [traces (capture-trace
                   #(schemas/validate-app-db! {:user "wholly-bogus"} :u/bad))
          v      (first traces)]
      (is (true? (:sensitive? v))
          "descendant-sensitive — the failing value is the whole map and
          contains the sensitive child slot's value")
      (is (= :rf/redacted (-> v :tags :value))))))

(deftest both-sensitive-and-clean-failures-handled-independently
  (testing "two registered schemas; one's failure is sensitive, the
            other's is not — each trace is independently classified"
    (rf/reg-app-schema [:auth] [:map [:token {:sensitive? true} :string]])
    (rf/reg-app-schema [:count] [:int])
    (let [traces (capture-trace
                   #(schemas/validate-app-db!
                      {:auth {:token 42} :count "not-an-int"}
                      :bulk/bad))]
      (is (= 2 (count traces)))
      (let [by-path (group-by #(-> % :tags :registered-path) traces)
            auth-v  (first (get by-path [:auth]))
            cnt-v   (first (get by-path [:count]))]
        (is (true? (:sensitive? auth-v))
            ":auth failure carries sensitive redaction")
        (is (= :rf/redacted (-> auth-v :tags :value)))
        (is (not (contains? cnt-v :sensitive?))
            ":count failure is plain — no redaction")
        (is (= "not-an-int" (-> cnt-v :tags :value)))))))

;; ---- conservative fallback ----------------------------------------------

(deftest fallback-uses-whole-schema-sensitivity-when-explainer-absent
  (testing "Per rf2-oh4se — when no explainer can extract a leaf path
            (e.g. explainer returns nil; non-Malli validator with no
            structured explanation), :path falls back to the registered
            root and the sensitivity check falls back to the
            whole-schema `schema-has-sensitive?` rule (conservative)."
    ;; Register a validator/explainer pair where validate fails but
    ;; explain returns no `:in` data — simulates a non-Malli or
    ;; structurally-different explainer.
    (schemas/set-schema-validator! {:validate (fn [_ _] false)
                                    :explain  (fn [_ _] nil)})
    (try
      (rf/reg-app-schema [:user]
                         [:map [:password {:sensitive? true} :string]])
      (let [traces (capture-trace
                     #(schemas/validate-app-db! {:user {:password "pw"}}
                                                :u/bad))
            v      (first traces)]
        (is (= 1 (count traces)))
        (is (= [:user] (-> v :tags :path))
            ":path falls back to the registered root when no leaf
            extractable")
        (is (true? (:sensitive? v))
            "conservative fallback — whole-schema sensitivity wins when
            the failing leaf cannot be narrowed")
        (is (= :rf/redacted (-> v :tags :value))
            "conservative fallback — value redacted under whole-schema
            sensitivity"))
      (finally
        (schemas/reset-schema-validator!)))))

(deftest fallback-non-sensitive-rides-verbatim
  (testing "fallback path with a non-sensitive schema still emits the
            verbatim value — the fallback only conserves the privacy
            posture, not the failure shape"
    (schemas/set-schema-validator! {:validate (fn [_ _] false)
                                    :explain  (fn [_ _] nil)})
    (try
      (rf/reg-app-schema [:count] [:int])
      (let [traces (capture-trace
                     #(schemas/validate-app-db! {:count "x"} :c/bad))
            v      (first traces)]
        (is (= [:count] (-> v :tags :path)))
        (is (= "x" (-> v :tags :value)))
        (is (not (contains? v :sensitive?))))
      (finally
        (schemas/reset-schema-validator!)))))

;; ---- registered-path always present --------------------------------------

(deftest registered-path-always-present
  (testing ":registered-path is stamped on every emit-site regardless of
            whether leaf narrowing succeeded — tooling can pivot on it"
    (rf/reg-app-schema [:user] [:map [:age :int]])
    (let [traces (capture-trace
                   #(schemas/validate-app-db! {:user {:age "x"}} :u/bad))
          v      (first traces)]
      (is (= [:user] (-> v :tags :registered-path))
          ":registered-path is the registration anchor — distinct from
          the failing leaf [:user :age]")
      (is (= [:user :age] (-> v :tags :path))))))

;; ---- walker: schema-sensitive-at? unit tests -----------------------------

(deftest sensitive-at-empty-path-equals-whole-schema-check
  (testing "(schema-sensitive-at? schema []) ≡ schema-has-sensitive? —
            an empty path means 'the failing slot IS the schema'"
    (let [sens     [:map [:p {:sensitive? true} :string]]
          not-sens [:map [:p :string]]]
      (is (true?  (schemas/schema-sensitive-at? sens     [])))
      (is (true?  (schemas/schema-sensitive-at? sens     nil)))
      (is (false? (schemas/schema-sensitive-at? not-sens [])))
      (is (false? (schemas/schema-sensitive-at? not-sens nil))))))

(deftest sensitive-at-leaf-direct-match
  (testing "the failing leaf is itself flagged :sensitive?"
    (let [schema [:map
                  [:name     :string]
                  [:password {:sensitive? true} :string]]]
      (is (true?  (schemas/schema-sensitive-at? schema [:password])))
      (is (false? (schemas/schema-sensitive-at? schema [:name]))))))

(deftest sensitive-at-ancestor-flagged
  (testing "an ancestor along the path carries :sensitive? — leaf
            inherits sensitivity"
    (let [schema [:map {:sensitive? true}
                  [:token :string]
                  [:expiry :int]]]
      (is (true? (schemas/schema-sensitive-at? schema [:token])))
      (is (true? (schemas/schema-sensitive-at? schema [:expiry]))))))

(deftest sensitive-at-descendant-flagged
  (testing "a descendant of the failing slot is :sensitive? — the
            failing slot's value would carry the sensitive child"
    (let [schema [:map
                  [:user [:map
                          [:name     :string]
                          [:password {:sensitive? true} :string]]]]]
      (is (true? (schemas/schema-sensitive-at? schema [:user]))
          "failing at :user — its value contains the sensitive :password")
      (is (true? (schemas/schema-sensitive-at? schema [:user :password])))
      (is (false? (schemas/schema-sensitive-at? schema [:user :name]))
          "sibling-only sensitive — non-sensitive leaf stays clean"))))

(deftest sensitive-at-unrelated-path
  (testing "a sensitive declaration on an unrelated branch does not
            taint the failing leaf"
    (let [schema [:map
                  [:public  :string]
                  [:auth    [:map [:token {:sensitive? true} :string]]]]]
      (is (false? (schemas/schema-sensitive-at? schema [:public]))
          ":public is on a different branch from :auth/:token"))))
