(ns re-frame.schemas-sensitive-test
  "JVM tests for the `:sensitive?` redaction contract in schema-validation
  error traces.

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces, the validation hot path MUST consult the registered schema's
  per-slot `:sensitive?` before including the failing value in the
  `:rf.error/schema-validation-failure` trace event. There is no
  handler/cofx/sub registration-meta `:sensitive?` annotation — the
  schema-walker is the sole driver. When the schema declares
  the slot sensitive:

    1. The failing value (`:value` / `:received`) is replaced with the
       framework-reserved `:rf/redacted` sentinel.
    2. The Malli explainer output (`:explain`) is redacted — it
       carries the failing value verbatim.
    3. The trace event's TOP-LEVEL `:sensitive?` field is stamped
       `true` so consumers route on it. (Per Spec 009 §Trace-event field, the
       schemas-side emit-site stamps `:tags :sensitive? true`; the
       runtime's `emit-error!` promotes it to the top-level slot per
       Spec 009 §Trace-event field: `:sensitive?` at the top level.)

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
       `validate-sub!` against a `:sensitive?`-bearing schema fires a trace
       with the redaction shape pinned. Recordable cofx validation uses the
       `:rf.error/cofx-value-invalid` path in core.
    3. **Non-sensitive failures** — values and explanations emit
       unchanged (`:value`, `:explain` ride verbatim; no top-level
       `:sensitive?` stamp on the event)."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas :as rf.schemas]
            ;; Compiled schemas exercise fail-closed opaque handling.
            [malli.core :as m]
            ;; White-box tests cover the internal path sanitizer.
            [re-frame.schemas.walker :as rf.schemas.walker]
            ;; Shared cross-host sanitizer corpus — the CLJS
            ;; half (re-frame.schemas-sensitive-path-cljs-test) asserts the
            ;; SAME cases so privacy behaviour cannot diverge by host.
            [re-frame.schemas.walker-sanitize-path-fixtures :as rf.schemas.walker-sanitize-path-fixtures]
            ;; Shared cross-host variable-width sequence corpus.
            [re-frame.schemas.sequence-width-fixtures :as rf.schemas.sequence-width-fixtures]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

;; ---- walker unit tests ----------------------------------------------------

(deftest extract-sensitive-paths-claims-each-flagged-slot
  (testing "the walker claims every `:sensitive? true` slot at its path
            under `base-path`, and nothing else"
    (are [schema base-path expected]
         (= expected (rf.schemas/extract-sensitive-paths-from-schema schema base-path))
      ;; no :sensitive? props anywhere → no entries
      [:map [:name :string]] []       {}
      :string                []       {}
      :int                   [:a :b]  {}
      ;; slot-level: the slot's own props carry the flag
      [:map [:user :string] [:password {:sensitive? true} :string]]
      []
      {[:password] {:sensitive? true :source :schema}}
      ;; base-path is prepended to every discovered slot path
      [:map [:password {:sensitive? true} :string]]
      [:auth]
      {[:auth :password] {:sensitive? true :source :schema}}
      ;; container-level: the schema's OWN props claim the base-path, as
      ;; `(reg-app-schema [:auth :token] [:string {:sensitive? true}])` does
      [:string {:sensitive? true}]
      [:auth :token]
      {[:auth :token] {:sensitive? true :source :schema}}
      ;; nested :map carries the path through every level
      [:map [:user [:map [:profile [:map [:ssn {:sensitive? true} :string]]]]]]
      []
      {[:user :profile :ssn] {:sensitive? true :source :schema}}
      ;; several flagged slots → one entry each
      [:map
       [:username :string]
       [:password  {:sensitive? true} :string]
       [:totp-code {:sensitive? true} :string]
       [:email :string]]
      []
      {[:password]  {:sensitive? true :source :schema}
       [:totp-code] {:sensitive? true :source :schema}}
      ;; :vector descends at the same base-path
      [:vector [:string {:sensitive? true}]]
      [:tokens]
      {[:tokens] {:sensitive? true :source :schema}}
      ;; :tuple claims each element at its POSITION-pinned path (conj base i),
      ;; not the index-free base-path — the source of tuple sibling precision
      [:tuple [:string {:sensitive? true}] :int]
      []
      {[0] {:sensitive? true :source :schema}}
      [:tuple :int [:string {:sensitive? true}]]
      []
      {[1] {:sensitive? true :source :schema}}
      [:tuple [:string {:sensitive? true}] :int]
      [:pt]
      {[:pt 0] {:sensitive? true :source :schema}})))

;; ---- schema-has-sensitive? -----------------------------------------------

(deftest schema-has-sensitive?-is-true-when-any-slot-is-flagged
  (testing "emit-sites carry the whole registered value in the trace,
            so a flag on ANY slot — slot-level, container-level or nested
            deep inside a map — makes the schema sensitive"
    (are [expected schema] (= expected (rf.schemas/schema-has-sensitive? schema))
      true  [:map [:password {:sensitive? true} :string]]
      true  [:string {:sensitive? true}]
      true  [:map [:user [:map [:profile [:map [:ssn {:sensitive? true} :string]]]]]]
      false [:map [:user :string] [:age :int]]
      false :int
      false [:vector :string])))

(deftest sensitive-extractor-hook-is-unmemoized-public-memo-kept
  (testing "the cross-artefact hook walks unmemoised, so a
            per-request schema (managed HTTP `:decode`) retains nothing; the
            public extractor keeps its never-evicted memo for registered
            schemas"
    (let [hook   (rf.late-bind/get-fn :schemas/extract-sensitive-paths-from-schema)
          schema [:map [:id [:= 7]] [:ssn {:sensitive? true} :string]]]
      (is (= {[:ssn] {:sensitive? true :source :schema}} (hook schema []))
          "the hook classifies exactly as the memoised extractor does")
      (is (not (identical? (hook schema []) (hook schema [])))
          "the hook retains nothing between calls")
      (is (identical? (rf.schemas/extract-sensitive-paths-from-schema schema [])
                      (rf.schemas/extract-sensitive-paths-from-schema schema []))
          "the public extractor still memoises"))))

;; ---- redaction at app-db validation site ----------------------------------

(deftest app-db-validation-redacts-sensitive-slot
  (testing "Per Spec 010 §`:sensitive?` — a failing app-db value at a
            :sensitive? slot emits a trace whose :value and :explain
            are the :rf/redacted sentinel and whose :tags are stamped
            :sensitive? true"
    ;; A schema where the WHOLE registered slot is marked sensitive
    ;; (container-level :sensitive?).
    (rf/reg-app-schema [:auth :token] [:string {:sensitive? true}])
    (with-trace-recorder! [traces]
      ;; The value at [:auth :token] is an int (42) — fails :string.
      (rf.schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
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

(deftest app-db-validation-non-sensitive-passes-through-verbatim
  (testing "a schema with no :sensitive? props emits
            unredacted traces; :value and :explain ride verbatim"
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [traces]
      (rf.schemas/validate-app-schema! {:count "not-an-int"} :count/bad)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no top-level :sensitive? stamp on non-sensitive validation")
        (is (not (contains? (:tags v) :sensitive?))
            ":tags :sensitive? also absent — the stamp lives at top-level only")
        (is (= "not-an-int" (-> v :tags :value))
            ":value rides verbatim")
        (is (some? (-> v :tags :explain))
            ":explain is present (Malli's structural explanation)")))))

;; ---- redaction when the sensitive slot is nested in a collection ---------
;; Malli's explainer reports a value-relative `:in` path
;; carrying COLLECTION INDICES (`[1 :token]`) / `:map-of` keys
;; (`["a" :secret]`), while the walker's decl paths are INDEX-FREE
;; (`[:token]`, `[:secret]`) because positional/keyed containers descend
;; at the same base-path. A raw `schema-sensitive-at?` prefix match
;; would fail in BOTH directions for collection-nested slots, so the
;; failing value would ship VERBATIM in the trace's :value / :explain with
;; no top-level :sensitive? stamp — exactly the leak the feature exists to
;; prevent. These tests pin the alignment.

(defn- app-db-failure-trace
  "Helper: register `schema` at `path` as the frame's only app-schema,
  validate `db` (which must fail the schema), and return the single
  schema-validation-failure trace. Clearing the registry first lets one
  test run several rows, each against its own registration alone."
  [path schema db failing-id]
  (rf.schemas/clear-schemas-by-frame!)
  (rf/reg-app-schema path schema)
  (with-trace-recorder! [traces]
    (rf.schemas/validate-app-schema! db failing-id)
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest app-db-validation-redacts-sensitive-slot-nested-in-a-collection
  (testing "a :sensitive? slot inside a collection redacts although Malli's
            :in carries the element index or map key, and :path keeps those
            navigable index / plain-key segments"
    (doseq [{:keys [desc path schema db expected-path]}
            [{:desc          ":vector element map — the index segment does not block the match"
              :path          [:items]
              :schema        [:vector [:map [:token {:sensitive? true} :string]]]
              :db            {:items [{:token "ok"} {:token 99}]}
              :expected-path [:items 1 :token]}
             {:desc          ":map-of value map — the plain map-of key stays a navigable locator"
              :path          [:by-id]
              :schema        [:map-of :string [:map [:secret {:sensitive? true} :string]]]
              :db            {:by-id {"a" {:secret 99}}}
              :expected-path [:by-id "a" :secret]}
             {:desc          ":sequential element map, as :vector"
              :path          [:log]
              :schema        [:sequential [:map [:pw {:sensitive? true} :string]]]
              :db            {:log [{:pw 1}]}
              :expected-path [:log 0 :pw]}
             {:desc          "map -> vector -> map chain (mixed map-key and index segments)"
              :path          [:accounts]
              :schema        [:map [:items [:vector [:map [:tok {:sensitive? true} :string]]]]]
              :db            {:accounts {:items [{:tok 99}]}}
              :expected-path [:accounts :items 0 :tok]}
             {:desc          ":vector whose ELEMENT schema is itself sensitive"
              :path          [:tokens]
              :schema        [:vector [:string {:sensitive? true}]]
              :db            {:tokens ["ok" 99]}
              :expected-path [:tokens 1]}
             {:desc          ":tuple whose sensitive element 0 fails — position precision does not under-redact it"
              :path          [:point]
              :schema        [:tuple [:string {:sensitive? true}] :int]
              :db            {:point [99 7]}
              :expected-path [:point 0]}]]
      (let [v (app-db-failure-trace path schema db :collection/bad)]
        (is (some? v) (str desc " — a trace fired"))
        (is (true? (:sensitive? v)) (str desc " — top-level :sensitive? stamp present"))
        (is (= :rf/redacted (-> v :tags :value)) (str desc " — :value redacted"))
        (is (= :rf/redacted (-> v :tags :explain)) (str desc " — :explain redacted"))
        (is (= expected-path (-> v :tags :path))
            (str desc " — :path keeps its navigable segments"))))))

(deftest app-db-validation-collection-non-sensitive-not-over-redacted
  (testing "a collection failure where NO slot is
            sensitive rides verbatim (the alignment must not
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
  (testing "PER-SLOT DECISION SCOPING
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

;; ---- value-bearing :in segments kept out of the :path tag ----------------
;; validate-app-schema! concats the failing :in into the structural :path tag
;; and builds :reason from it, and Spec 010 declares :path unredacted. Some
;; :in segments are VALUES, not locators, so for a sensitive schema
;; sanitize-sensitive-path scrubs each of them to :rf/redacted:
;;
;;   - a :set failure's segment is the failing ELEMENT VALUE itself (a set has
;;     no positional index), sibling secrets included;
;;   - a :map-of KEY whose key schema is :sensitive? is the secret used as
;;     the key. A compiled m/schema key, alone or hidden under a vector
;;     wrapper, carries a flag Malli honours but the walker cannot see, so
;;     align-in-path and sanitize-sensitive-path both also consult
;;     schema-has-opaque-child?: the leaf resolves sensitive and the key is
;;     scrubbed;
;;   - past a wrapper whose branch the path cannot identify (a multi-child
;;     :or / :and, :orn, :multi) every remaining segment fails closed,
;;     scalars included, because a tail scalar may be a :set element.
;;
;; Navigable segments — :vector / :tuple indices, declared :map keys, plain
;; :map-of keys — survive, so :path stays a get-in locator for those shapes.

(deftest app-db-validation-scrubs-value-bearing-path-segments
  (testing "each value-bearing :in segment of a sensitive failure is the
            :rf/redacted sentinel in :path while navigable segments survive;
            :value and :explain redact, and no secret reaches :reason or any
            other tag"
    (doseq [{:keys [desc path schema db expected-path secrets]}
            [{:desc          ":set element map — the element segment carries the sibling :ssn"
              :path          [:members]
              :schema        [:set [:map [:token {:sensitive? true} :string] [:ssn :string]]]
              :db            {:members #{{:token 123456789 :ssn "078-05-1120"}}}
              :expected-path [:members :rf/redacted :token]
              :secrets       ["078-05-1120" "123456789"]}
             {:desc          ":map-of with a :sensitive? KEY schema"
              :path          [:by-token]
              :schema        [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
              :db            {:by-token {"secret-token-123" {:age "not-an-int"}}}
              :expected-path [:by-token :rf/redacted :age]
              :secrets       ["secret-token-123"]}
             {:desc          ":map-of with a COMPILED m/schema :sensitive? KEY"
              :path          [:by-token]
              :schema        [:map-of (m/schema [:string {:sensitive? true}]) [:map [:age :int]]]
              :db            {:by-token {"SECRET-KEY-XYZ" {:age "not-an-int"}}}
              :expected-path [:by-token :rf/redacted :age]
              :secrets       ["SECRET-KEY-XYZ"]}
             {:desc          ":map-of whose KEY is a vector wrapper hiding a compiled m/schema"
              :path          [:by-token]
              :schema        [:map-of [:and (m/schema [:string {:sensitive? true}])] [:map [:age :int]]]
              :db            {:by-token {"NESTED-SECRET-ABC" {:age "not-an-int"}}}
              :expected-path [:by-token :rf/redacted :age]
              :secrets       ["NESTED-SECRET-ABC"]}
             {:desc          "sensitive :map-of KEY in the later branch of a multi-child :or"
              :path          [:secrets]
              :schema        [:or
                              [:map-of :int :int]
                              [:map-of [:string {:sensitive? true}] [:map [:age :int]]]]
              :db            {:secrets {"secret-token-abc" {:age "not-an-int"}}}
              :expected-path [:secrets :rf/redacted]
              :secrets       ["secret-token-abc"]}
             {:desc          "sensitive :map-of KEY in a later conjunct of a multi-child :and"
              :path          [:secrets]
              :schema        [:and
                              [:map-of :string [:map [:age :int]]]
                              [:map-of [:string {:sensitive? true}] [:map [:age :int]]]]
              :db            {:secrets {"secret-token-xyz" {:age "not-an-int"}}}
              :expected-path [:secrets :rf/redacted :rf/redacted]
              :secrets       ["secret-token-xyz"]}
             {:desc          ":set of sensitive SCALARS under an :orn — the element rides the fail-closed tail"
              :path          [:tokens]
              :schema        [:orn [:tokens [:set [:string {:sensitive? true}]]]]
              :db            {:tokens #{123456789}}
              :expected-path [:tokens :rf/redacted]
              :secrets       ["123456789"]}]]
      (let [v (app-db-failure-trace path schema db :segment/bad)]
        (is (some? v) (str desc " — a trace fired"))
        (is (true? (:sensitive? v)) (str desc " — top-level :sensitive? stamp present"))
        (is (= :rf/redacted (-> v :tags :value)) (str desc " — :value redacted"))
        (is (= :rf/redacted (-> v :tags :explain)) (str desc " — :explain redacted"))
        (is (= expected-path (-> v :tags :path))
            (str desc " — each value-bearing segment is the sentinel in :path"))
        (doseq [secret secrets]
          (is (not (str/includes? (pr-str (-> v :tags :reason)) secret))
              (str desc " — " secret " is not in :reason"))
          (is (not (str/includes? (pr-str (:tags v)) secret))
              (str desc " — " secret " is in no tag")))))))

(deftest app-db-validation-set-non-sensitive-leaf-sensitive-sibling-path-carries-no-secret
  (testing "a failure at a NON-sensitive leaf inside a :set
            element whose CONFORMING sibling is :sensitive? still scrubs the
            element segment: Malli's :in segment is the WHOLE element, so it
            carries the sibling secret into :path and :reason unless the
            sanitizer runs whenever the schema declares anything sensitive,
            not only when the failing leaf does"
    (let [secret "SECRET-TOKEN-A1"
          v      (app-db-failure-trace
                   [:sessions]
                   [:set [:map [:uid :int] [:token {:sensitive? true} :string]]]
                   {:sessions #{{:uid "not-an-int" :token secret}}}
                   :sessions/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v)) "top-level :sensitive? stamp present")
      (is (= [:sessions :rf/redacted :uid] (-> v :tags :path))
          ":path's :set-element segment is the :rf/redacted sentinel; the leaf key survives")
      (is (= "not-an-int" (-> v :tags :value))
          ":value (narrowed to the non-sensitive :uid leaf) still rides verbatim")
      (is (not (str/includes? (-> v :tags :reason) secret))
          "the sibling secret does NOT appear in the generated :reason text")
      (is (not (str/includes? (pr-str v) secret))
          "the sibling secret does NOT appear ANYWHERE in the whole trace event"))))

;; ---- sanitize-sensitive-path through :and / :or ---------------------------
;; A multi-child :and / :or cannot say which branch produced the failing :in
;; (an :or value matched some ONE branch; an :and value is constrained by
;; ALL), so following only the first child would read a later branch's
;; sensitive :map-of key as a navigable locator and ship it verbatim. The walk
;; fails closed on the whole remaining tail instead (the multi-child :or and
;; :and rows of the table above). A degenerate single-child :and / :or is
;; unambiguous, so it still descends precisely.

(deftest sanitize-single-child-and-or-descend-precisely
  (testing "a DEGENERATE single-child :and / :or is
            unambiguous, so the walk still descends precisely and KEEPS the
            navigable :map key (the fail-closed rule must not over-redact the
            unambiguous case)"
    (is (= [:k] (rf.schemas.walker/sanitize-sensitive-path [:or  [:map [:k :int]]] [:k]))
        "single-child :or descends into its one branch and keeps the map key")
    (is (= [:k] (rf.schemas.walker/sanitize-sensitive-path [:and [:map [:k :int]]] [:k]))
        "single-child :and descends into its one branch and keeps the map key")))

;; -- direct unit tests: the `:maybe` transparent-wrapper arm --
;;
;; `:maybe` is a genuinely single-child transparent wrapper (`[:maybe inner]`)
;; that contributes NO `:in` segment — both redaction-path walkers descend into
;; the inner schema WITHOUT consuming a path segment (the `:maybe` arms of
;; align-in-path and sanitize-sensitive-path). `:maybe` is exercised in
;; `walk-flagged-schema` (the DECL extractor) via its `:else`
;; descend-into-each-child arm, but the two REDACTION-path `:maybe` arms get
;; no coverage from the security artefact's generative redaction wrap-set
;; (`:map`/`:vector`/`:sequential`/`:map-of`/`:tuple`), whose only `:maybe`
;; occurrence is a childless malformed-schema case. These pin the
;; child-present transparent descent on both walkers.

(deftest schema-sensitive-at?-descends-maybe-transparent-wrapper
  (testing "schema-sensitive-at? aligns a path THROUGH a `:maybe`
            wrapper without consuming a segment: a `:sensitive?` leaf nested
            inside `[:maybe [:map …]]` resolves sensitive at the value path that
            skips the wrapper (align-in-path's :maybe arm)"
    ;; The shape: the sensitive slot sits under `:s` -> `:maybe` ->
    ;; `:k`. Malli's `:in` for the failing `:k` value skips the transparent
    ;; `:maybe`, so the aligned path is [:s :k] and the extracted decl-path is
    ;; likewise [:s :k] (walk-flagged-schema descends `:maybe` at the same
    ;; base-path) — they prefix-match, so the leaf resolves sensitive.
    (is (true? (rf.schemas.walker/schema-sensitive-at?
                 [:map [:s [:maybe [:map [:k {:sensitive? true} :string]]]]]
                 [:s :k]))
        "sensitive leaf under a `:maybe` wrapper → leaf sensitive at [:s :k]")
    ;; A sensitive leaf directly under a top-level `:maybe` (no outer :map):
    ;; `[:maybe [:map …]]` with :in [:k]. Align descends the `:maybe` with the
    ;; segment intact, keeps [:k], and resolves sensitive.
    (is (true? (rf.schemas.walker/schema-sensitive-at?
                 [:maybe [:map [:k {:sensitive? true} :string]]]
                 [:k]))
        "top-level `:maybe` wrapper is transparent for the leaf decision too"))
  (testing "a non-sensitive leaf beside a `:sensitive?` sibling inside
            the `:maybe` is NOT leaf sensitive — only the precise descent
            tells the two slots apart"
    ;; Without the `:maybe` arm the walk stops AT the wrapper with path
    ;; remaining, and the fail-safe fallback redacts because the leftover
    ;; subtree declares the sibling sensitive. So these rows read false only
    ;; while align-in-path descends the wrapper.
    (is (false? (rf.schemas.walker/schema-sensitive-at?
                  [:map [:s [:maybe [:map [:k :string]
                                          [:secret {:sensitive? true} :string]]]]]
                  [:s :k]))
        "nested `:maybe`: [:s :k] is not sensitive though its sibling [:s :secret] is")
    (is (false? (rf.schemas.walker/schema-sensitive-at?
                  [:maybe [:map [:k :string]
                                [:secret {:sensitive? true} :string]]]
                  [:k]))
        "top-level `:maybe`: [:k] is not sensitive though its sibling [:secret] is")))

(deftest sanitize-sensitive-path-transparent-through-maybe
  (testing "sanitize-sensitive-path descends a `:maybe` wrapper
            without consuming a segment, so navigable
            segments on either side keep their locator identity"
    ;; Map key kept: `:s` (map key, before the wrapper) and `:k` (map key,
    ;; inside the wrapper) are both navigable `get-in` locators — the `:maybe`
    ;; between them is transparent, so neither is scrubbed.
    (is (= [:s :k]
           (rf.schemas.walker/sanitize-sensitive-path
             [:map [:s [:maybe [:map [:k {:sensitive? true} :string]]]]]
             [:s :k]))
        "navigable map keys survive verbatim across a `:maybe` wrapper"))
  (testing "a value-bearing `:set` element nested under a `:maybe`
            is scrubbed: the walk descends the transparent `:maybe`, then
            the `:set` arm ALWAYS scrubs the failing element value"
    ;; Malli reports a `:set` failure's `:in` segment as the failing ELEMENT
    ;; VALUE itself (not an index); the secret element must NOT ride verbatim in
    ;; `:path`. The outer map key `:s` is kept; the set element is redacted.
    (is (= [:s :rf/redacted]
           (rf.schemas.walker/sanitize-sensitive-path
             [:map [:s [:maybe [:set [:string {:sensitive? true}]]]]]
             [:s "SECRET-SET-ELEMENT"]))
        "map key kept; set element scrubbed to the sentinel across `:maybe`")))

(deftest app-db-validation-non-sensitive-or-failure-rides-verbatim
  (testing "a fully NON-sensitive multi-child :or failure
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

;; ---- ancestor-sensitive container wrapped by :and/:multi/:orn/:or --------
;; When a slot is declared {:sensitive? true} as a CONTAINER and the failing
;; leaf lives under a transparent-but-unrecognised wrapper op
;; (:and / :or / :multi / :orn), an align-in-path :else fallback that
;; discarded the consumed-ancestor sensitivity would leave
;; schema-has-sensitive? on the LEFTOVER subtree returning false, so NOTHING
;; would be redacted and the trace would NOT be stamped :sensitive?. The
;; failing value (and explain / humanized) would ship VERBATIM — a direct
;; value leak. So the fallback carries the consumed prefix through, and a
;; descendant failure under a sensitive ancestor is redacted + stamped.

(deftest app-db-validation-redacts-under-wrapped-sensitive-ancestor
  (testing "a failing leaf under a :sensitive? container whose inner schema
            is an :and / :multi / :orn / :or wrapper redacts the value and
            stamps :sensitive? — the consumed ancestor's flag carries through
            align-in-path's fallback"
    (doseq [{:keys [desc schema value secret]}
            [{:desc   ":and"
              :schema [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
              :value  {:s {:k "SECRET-AND-9f3a"}}
              :secret "SECRET-AND-9f3a"}
             {:desc   ":multi"
              :schema [:map [:s {:sensitive? true}
                             [:multi {:dispatch :t} [:a [:map [:t :keyword] [:k :int]]]]]]
              :value  {:s {:t :a :k "SECRET-MULTI-deadbeef"}}
              :secret "SECRET-MULTI-deadbeef"}
             {:desc   ":orn"
              :schema [:map [:s {:sensitive? true} [:orn [:a [:map [:k :int]]]]]]
              :value  {:s {:k "SECRET-ORN-cafe"}}
              :secret "SECRET-ORN-cafe"}
             {:desc   ":or"
              :schema [:map [:s {:sensitive? true} [:or [:map [:k :int]]]]]
              :value  {:s {:k "SECRET-OR-1234"}}
              :secret "SECRET-OR-1234"}]]
      (let [v (app-db-failure-trace [:root] schema {:root value} :ancestor/bad)]
        (is (some? v) (str desc " ancestor — a trace fired"))
        (is (true? (:sensitive? v)) (str desc " ancestor — top-level :sensitive? stamp present"))
        (is (= :rf/redacted (-> v :tags :value)) (str desc " ancestor — :value redacted"))
        (is (= :rf/redacted (-> v :tags :explain)) (str desc " ancestor — :explain redacted"))
        (is (not (str/includes? (pr-str (:tags v)) secret))
            (str desc " ancestor — the raw secret is in no tag"))))))

(deftest schema-sensitive-at-ancestor-under-and-multi-orn
  (testing "schema-sensitive-at? returns true for a leaf under
            a sensitive ancestor wrapped by :and/:multi/:orn (the
            consumed-ancestor prefix must carry through align-in-path's
            fallback)"
    (is (true? (rf.schemas/schema-sensitive-at?
                 [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
                 [:s :k]))
        ":and ancestor")
    (is (true? (rf.schemas/schema-sensitive-at?
                 [:map [:s {:sensitive? true}
                        [:multi {:dispatch :t} [:a [:map [:t :keyword] [:k :int]]]]]]
                 [:s :k]))
        ":multi ancestor")
    (is (true? (rf.schemas/schema-sensitive-at?
                 [:map [:s {:sensitive? true} [:orn [:a [:map [:k :int]]]]]]
                 [:s :k]))
        ":orn ancestor")
    ;; A sensitive SIBLING outside the consumed prefix must NOT taint the
    ;; failing slot (the precise-narrowing win is preserved).
    (is (false? (rf.schemas/schema-sensitive-at?
                  [:map
                   [:s {:sensitive? true} [:and [:map [:k :int]]]]
                   [:other [:and [:map [:j :int]]]]]
                  [:other :j]))
        "a sibling's sensitivity does not taint a failure under a non-sensitive sibling")))

;; ---- :tuple element-precision — no sibling taint -------------------------
;; A bare :tuple's elements are HETEROGENEOUS — each position carries its own
;; schema, so the integer index IS the discriminating segment (the positional
;; analogue of a :map key). Marking ONE tuple position {:sensitive? true} must
;; NOT redact a failure at a DIFFERENT, non-sensitive position. Emitting the
;; element flag at the index-free tuple base-path and dropping the tuple index
;; in align-in-path would collapse all positions onto the base-path, so any
;; one sensitive element would taint every sibling (privacy-SAFE
;; over-redaction, but a precision divergence from the no-sibling-taint
;; contract the :vector path holds). So the walker emits position-pinned
;; decl-paths ((conj base i)) and align-in-path KEEPS the tuple index, and
;; each position is independent — mirroring the :vector / :map-of map-key
;; discriminator with the index as the tuple's discriminator. These assert
;; the false (sibling) cases; the true (self / ancestor / descendant) cases
;; pin the redaction direction.

(deftest schema-sensitive-at-tuple-position-precise
  (testing "a bare :tuple's sensitivity is element-precise: a
            failure at a NON-sensitive sibling position is NOT redacted, while
            the declared-sensitive position (and ancestor/descendant) still is"
    (let [s0 [:tuple [:string {:sensitive? true}] :int]]   ;; element 0 sensitive
      ;; SELF — the sensitive position fails → redact.
      (is (true? (rf.schemas/schema-sensitive-at? s0 [0]))
          "the declared-sensitive position 0 redacts")
      ;; SIBLING — the non-sensitive position fails → must NOT redact.
      (is (false? (rf.schemas/schema-sensitive-at? s0 [1]))
          "element 0 sensitive must NOT taint a failure at the non-sensitive element 1"))
    (let [s1 [:tuple :int [:string {:sensitive? true}]]]   ;; element 1 sensitive
      (is (true? (rf.schemas/schema-sensitive-at? s1 [1]))
          "the declared-sensitive position 1 redacts")
      (is (false? (rf.schemas/schema-sensitive-at? s1 [0]))
          "element 1 sensitive must NOT taint a failure at the non-sensitive element 0"))
    ;; ANCESTOR / DESCENDANT — a tuple element that is itself a container with
    ;; a nested sensitive slot: a failure at the slot, at the whole element, or
    ;; at the whole tuple all redact (the value carries the secret); a failure
    ;; at the OTHER element does not.
    (let [s [:tuple [:map [:tok {:sensitive? true} :string]] :int]]
      (is (true?  (rf.schemas/schema-sensitive-at? s [0 :tok])) "exact nested slot")
      (is (true?  (rf.schemas/schema-sensitive-at? s [0]))      "ancestor of the secret")
      (is (true?  (rf.schemas/schema-sensitive-at? s []))       "whole tuple carries the secret")
      (is (false? (rf.schemas/schema-sensitive-at? s [1]))      "non-sensitive sibling element 1"))))

(deftest app-db-validation-tuple-sibling-narrowed-value-verbatim-whole-explain-redacted
  (testing "PER-SLOT DECISION SCOPING on
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

;; ---- redaction at event validation site ----------------------------------

(deftest event-validation-ignores-handler-meta-sensitive
  (testing "There is no handler-meta `:sensitive?` annotation.
            Event-payload validation traces are NOT redacted by
            handler-meta sensitivity, and this event schema declares no
            sensitive slot for the walker to find."
    (let [calls (atom 0)]
      (rf/reg-event :auth/sign-in
        {:doc        "Verify creds"
         :sensitive? true                                      ;; ignored — not an annotation
         :schema     [:cat [:= :auth/sign-in] :string :string]}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:auth/sign-in "ada" 42])
        (is (= 0 @calls) "handler skipped — validation failed")
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (not (true? (:sensitive? v)))
              "no top-level :sensitive? stamp — handler meta carries no sensitivity")
          (is (= [:auth/sign-in "ada" 42] (-> v :tags :received))
              ":received rides verbatim — no redaction")
          ;; Structural slots survive.
          (is (= :event (-> v :tags :where)))
          (is (= :auth/sign-in (-> v :tags :event-id)))
          (is (= :auth/sign-in (-> v :tags :failing-id)))
          (is (= :auth/sign-in (-> v :tags :schema-id))))))))

(deftest event-validation-non-sensitive-passes-through-verbatim
  (testing "a handler without :sensitive? emits the
            unredacted trace"
    (rf/reg-event :user/register
      {:schema [:cat [:= :user/register]
                   [:map [:email :string] [:age :int]]]}
      (fn [{:keys [db]} [_ payload]] {:db (update db :users (fnil conj []) payload)}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
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

;; ---- event-schema per-slot :sensitive? redaction -------------------------
;; `validate-event!` walks the event schema (the `:cat`/`:catn` payload
;; commonly IS map-shaped), so a per-slot or container-level :sensitive?
;; drives the redaction exactly as on app-db / cofx / fx / sub surfaces.
;; Without the walk a per-slot `:sensitive?` inside the event schema (e.g. a
;; `:cat` payload map) would be IGNORED and the failing payload would leak
;; verbatim via :received / :value / :explain to trace listeners / off-box
;; consumers. The handler-meta test above pins that a NON-sensitive event
;; schema rides verbatim (no over-redaction).

(deftest event-validation-redacts-sensitive-cat-payload-slot
  (testing "a failing event whose :cat payload map carries a
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
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:auth/login {:user "ada" :password secret}])
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

;; ---- per-slot scoping: sensitive SIBLING, non-sensitive failure ----------
;; The shared `run-validation` path
;; (event / fx / sub) carries the WHOLE checked value in EVERY
;; value-bearing slot — `:value` / `:received` / `:explain` / `:rf.fx/args` /
;; `:rf.sub/query-v` are all the whole event-vector / fx-args /
;; sub-return value; NOTHING here is narrowed to the failing leaf (only the
;; app-db `:value` slot narrows). So a CONFORMING `:sensitive?` SIBLING (a
;; valid token next to a failing :count) rides INSIDE those whole-payload
;; slots.
;;
;; Deciding redaction there with the LEAF-PRECISE `schema-sensitive-at?`
;; (sibling-blind) would let a non-sensitive failing sibling clear
;; redaction, and the CONFORMING sensitive sibling would egress verbatim to
;; Xray / Pair / off-box. Per PER-SLOT DECISION SCOPING the redaction scope
;; MUST match the carried-value scope: a whole-payload slot uses the ROOT
;; `schema-has-sensitive?` check. So on these surfaces a sensitive sibling
;; ANYWHERE in the schema redacts the WHOLE value — the price of not
;; narrowing. (The app-db `:value` slot, which IS narrowed, keeps the
;; leaf-precise no-sibling-taint win — see `validate-app-schema!`'s tests
;; above.) These tests assert the conforming sensitive sibling is ABSENT
;; from every egressed slot.

;; EP-0017: the LIVE cofx
;; `:schema` path is the recordable-value check
;; (`re-frame.cofx/validate-recordable-value!` → `:rf.error/cofx-value-invalid`).
;; It routes its off-box `:value` slot through the SAME shared
;; `redact-validation-tags` seam, so a `:sensitive?`-bearing
;; recordable cofx schema redacts identically. The recordable path carries the
;; WHOLE recordable value in `:value` (it is not narrowed), so a sensitive slot
;; ANYWHERE in the schema redacts the whole value. (It does not carry a
;; `:received` slot — only `:value`.)
(deftest recordable-cofx-conforming-sensitive-sibling-redacted-whole-value
  (testing "EP-0017 recordable path — a recordable-cofx schema
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
    (with-trace-recorder! [traces]
      ;; :token "SECRET-COFX-tok" CONFORMS (sensitive sibling); :count fails
      ;; (string, not int) — the failing slot is the NON-sensitive sibling.
      (try
        (rf/dispatch-sync [:auth/use-ctx]
                          {:rf.cofx {:auth/ctx {:token "SECRET-COFX-tok"
                                                :count "not-an-int"}}})
        (catch clojure.lang.ExceptionInfo _))
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v) "a recordable-cofx validation failure was traced")
        (is (true? (:sensitive? v))
            ":sensitive? stamped — a whole-payload slot carries the conforming sensitive sibling")
        (is (= :rf/redacted (-> v :tags :value)) ":value (whole cofx) redacted")
        (is (not (str/includes? (pr-str (:tags v)) "SECRET-COFX-tok"))
            "the conforming sensitive sibling :token is ABSENT from every egressed slot")))))

(deftest sub-validation-conforming-sensitive-sibling-redacted-whole-value
  (testing "a sub-return schema with a CONFORMING sensitive
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
    (with-trace-recorder! [traces]
      @(rf/subscribe [:auth/view])
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
  (testing "an event schema is `:cat`-rooted, and
            the event surface carries the WHOLE event vector in every
            value-bearing slot (`:received` / `:value` / `:explain`). When a
            CONFORMING sensitive payload slot (:password) rides next to a
            non-sensitive failing slot (:age), the whole-payload slots redact
            under the ROOT check — the conforming secret rides INSIDE the whole
            event vector, so the leaf-precise `[1 :age]` decision (sibling-blind)
            would leak it. The
            conforming sensitive sibling MUST be absent from every egressed slot."
    (let [secret "pw-MUST-NOT-LEAK"]
      (rf/reg-event :auth/profile
        {:schema [:cat [:= :auth/profile]
                  [:map
                   [:password {:sensitive? true} :string]
                   [:age :int]]]}
        (fn [{:keys [db]} _] {:db db}))
      (with-trace-recorder! [traces]
        ;; :password conforms (a string, the sensitive sibling); :age fails
        ;; (a string, not int). The failing slot is NON-sensitive, but the
        ;; whole event vector (carrying the conforming :password) rides every
        ;; value-bearing slot.
        (rf/dispatch-sync [:auth/profile {:password secret :age "old"}])
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

;; ---- walker unit tests for :cat/:catn position-bearing paths -------------

(deftest extract-cat-emits-position-pinned-paths
  (testing "the walker emits a :cat element flag at its
            POSITION-pinned path ((conj base i)), not the index-free :cat
            base-path; this is what gives the event-payload sibling precision"
    ;; element 1 (the payload) is a sensitive :string.
    (is (= {[1] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:cat [:= :id] [:string {:sensitive? true}]] [])))
    ;; per-slot flag inside a :cat payload MAP claims the position + key.
    (is (= {[1 :tok] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:cat [:= :id] [:map [:tok {:sensitive? true} :string] [:age :int]]] [])))
    ;; base-path threads through.
    (is (= {[:ev 1] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:cat [:= :id] [:string {:sensitive? true}]] [:ev])))))

(deftest extract-catn-emits-position-pinned-paths
  (testing "`:catn` is position-bearing too; an entry-level OR a
            schema-level :sensitive? flag claims the element's POSITION-pinned
            path (the decorative name is NOT a path segment — Malli reports the
            integer index in :in)"
    ;; entry-level flag on the :catn entry props.
    (is (= {[1] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:catn [:id [:= :id]] [:tok {:sensitive? true} :string]] [])))
    ;; per-slot flag inside a :catn payload MAP claims position + key.
    (is (= {[1 :pw] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:catn [:id [:= :id]]
              [:payload [:map [:pw {:sensitive? true} :string] [:age :int]]]] [])))))

(deftest schema-sensitive-at-cat-position-precise
  (testing "a :cat element's sensitivity is element-precise: a
            failure at a NON-sensitive sibling position is NOT redacted, while
            the declared-sensitive position (and its ancestor/descendant) is —
            mirrors the :tuple position-precision contract"
    (let [s [:cat [:= :id] [:map [:pw {:sensitive? true} :string] [:age :int]]]]
      ;; SELF / DESCENDANT — the sensitive payload slot fails → redact.
      (is (true?  (rf.schemas/schema-sensitive-at? s [1 :pw])) "exact sensitive slot")
      (is (true?  (rf.schemas/schema-sensitive-at? s [1]))     "ancestor of the secret")
      ;; SIBLING — the non-sensitive payload slot fails → must NOT redact.
      (is (false? (rf.schemas/schema-sensitive-at? s [1 :age]))
          ":pw sensitive must NOT taint a failure at the non-sensitive :age")
      ;; the id position (element 0) carries no secret.
      (is (false? (rf.schemas/schema-sensitive-at? s [0])) "the id position is not sensitive"))
    ;; whole-element sensitivity: element 1 entirely sensitive.
    (let [s [:cat [:= :id] [:string {:sensitive? true}]]]
      (is (true?  (rf.schemas/schema-sensitive-at? s [1])) "the sensitive payload position redacts")
      (is (false? (rf.schemas/schema-sensitive-at? s [0])) "the non-sensitive id position does not"))))

;; ---- shared validation-failure redaction seam ----------------------------
;; The production boundary arm (`re-frame.spec`) builds its own event-
;; failure tags and routes them through the `:schemas/redact-validation-tags`
;; seam, so a sensitive event payload is redacted at the boundary exactly as
;; on the dev-time step-1 path. The SAME seam is the single redactor every
;; off-namespace validation-failure emit site shares (machine-data / sub-override /
;; flow-output / boundary). These test the seam directly (the schemas-owned
;; redaction surface).

(deftest redact-validation-tags-redacts-when-schema-sensitive
  (testing "redact-validation-tags scrubs the value-bearing boundary
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
          out    (rf.schemas/redact-validation-tags schema tags)]
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

;; ---- redaction at the recordable-cofx validation site --------------------
;;
;; Under EP-0017 the cofx-validation site is the recordable-value path
;; (`re-frame.cofx/validate-recordable-value!` →
;; `:rf.error/cofx-value-invalid`). It routes its off-box `:value` slot through
;; the SAME `redact-validation-tags` seam, so the schema-walker drives
;; redaction exclusively.

(deftest recordable-cofx-ignores-meta-sensitive
  (testing "There is no registration-meta `:sensitive?` annotation.
            A bare `:sensitive?` on the cofx registration meta does not
            trigger redaction — the schema-walker drives the decision
            exclusively. With a plain `:string` schema (no per-slot sensitive
            prop) the recordable-value failure rides verbatim."
    (rf/reg-cofx :auth/credentials
      {:doc "A recordable auth-token coeffect"
       :recordable? true :provided? true
       :sensitive? true   ;; ignored — not an annotation
       :schema :string})
    (rf/reg-event :auth/use-creds
      {:rf.cofx/requires [:auth/credentials]}
      (fn [_ _] {}))
    (with-trace-recorder! [traces]
      (try
        (rf/dispatch-sync [:auth/use-creds]
                          {:rf.cofx {:auth/credentials 42}})  ;; int, fails :string
        (catch clojure.lang.ExceptionInfo _))
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (true? (:sensitive? v)))
            "no :sensitive? stamp — schema has no per-slot :sensitive? prop")
        (is (= 42 (-> v :tags :value)))
        (is (= :auth/credentials (-> v :tags :rf.cofx/id))
            "structural :rf.cofx/id survives")))))

;; ---- redaction at sub-return validation site -----------------------------

(deftest sub-return-validation-redacts-when-sensitive
  (testing "Per Spec 010 §`:sensitive?` — schema-walker (`:vector
            [:string {:sensitive? true}]`) drives sub-return redaction of
            every value-bearing slot, `:explain-humanized` included; there is
            no handler/sub-meta `:sensitive?` annotation."
    (rf/reg-event :secrets/init (fn [_ _] {:db {:secrets ["a-secret"]}}))
    (rf/reg-event :secrets/break (fn [{:keys [db]} _] {:db (assoc db :secrets [1 2 3])}))
    (rf/reg-sub :secrets
      {:schema [:vector [:string {:sensitive? true}]]}
      (fn [db _] (:secrets db)))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:secrets/init])
      ;; First subscribe materialises; well-typed.
      (rf/subscribe-once [:secrets])
      (rf/dispatch-sync [:secrets/break])
      ;; Resubscribe; malformed return — fails.
      (rf/subscribe-once [:secrets])
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
          (is (= :rf/redacted (-> v :tags :explain-humanized))
              ":explain-humanized is present and redacted, symmetric with :explain")
          ;; Structural slots survive.
          (is (= :sub-return (-> v :tags :where)))
          (is (= :secrets (-> v :tags :rf.sub/id)))
          (is (= :replaced-with-default (:recovery v))))))))

(deftest sub-return-validation-redacts-query-v-when-sensitive
  (testing "Per Spec 010 §`:sensitive?` —
            the caller-supplied :rf.sub/query-v on a sensitive sub-return
            failure is itself redacted. :rf.sub/query-v is a value-bearing
            slot on this surface (the lookup key typically carries
            the same secret material the sub's return schema is
            gating — user ids, auth tokens, document ids); without
            redaction the failure trace re-leaks it alongside the
            return value the existing clauses scrub."
    (rf/reg-event :tokens/init  (fn [_ _]   {:db {:tokens {"user-42-token" "ok"}}}))
    (rf/reg-event :tokens/break (fn [{:keys [db]} _] {:db (assoc-in db
                                                       [:tokens "user-42-token"]
                                                       99)})) ; int — fails :string
    (rf/reg-sub :token-for
      {:schema [:string {:sensitive? true}]}
      (fn [db [_ token]] (get-in db [:tokens token])))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:tokens/init])
      ;; Well-typed return on the first call — no validation failure.
      (rf/subscribe-once [:token-for "user-42-token"])
      (rf/dispatch-sync [:tokens/break])
      ;; Malformed return on the second call — fires the sub-return failure.
      (rf/subscribe-once [:token-for "user-42-token"])
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
  (testing "a sub without :sensitive? emits :rf.sub/query-v
            verbatim on the validation-failure trace, beside the real
            humanized payload. Redaction is opt-in."
    (rf/reg-event :widgets/init  (fn [_ _]   {:db {:widgets {:w1 "ok"}}}))
    (rf/reg-event :widgets/break (fn [{:keys [db]} _] {:db (assoc-in db [:widgets :w1] 99)}))
    (rf/reg-sub :widget
      {:schema :string}                                  ; no :sensitive?
      (fn [db [_ wid]] (get-in db [:widgets wid])))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:widgets/init])
      (rf/subscribe-once [:widget :w1])
      (rf/dispatch-sync [:widgets/break])
      (rf/subscribe-once [:widget :w1])
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (not (contains? v :sensitive?))
            "no top-level :sensitive? stamp on non-sensitive sub")
        (is (= [:widget :w1] (-> v :tags :rf.sub/query-v))
            ":rf.sub/query-v rides verbatim — no redaction on non-sensitive subs")
        (is (= 99 (-> v :tags :value))
            ":value also rides verbatim")
        (is (contains? (:tags v) :explain-humanized)
            ":explain-humanized present")
        (is (not= :rf/redacted (-> v :tags :explain-humanized))
            ":explain-humanized is the real humanized payload, not the sentinel")))))

;; ---- humanized-explain redaction symmetry --------------------------------
;; Per Spec 010 §Humanize-hook §Composition with `:sensitive?` — when the
;; failing slot is sensitive the substrate redacts BOTH `:explain` AND
;; `:explain-humanized` to `:rf/redacted` (symmetric). Humanizing in the
;; central emit seam AFTER `:explain` had been overwritten with
;; `:rf/redacted` would hand the humanizer the sentinel; it would return
;; nil and `:explain-humanized` would be silently OMITTED — a contract
;; drift (Xray's violation block prefers `:explain-humanized` and would
;; fall through to a missing slot). The app-db pair below pins the
;; symmetric shape: present-and-redacted on sensitive failures,
;; real-payload on non-sensitive ones, never the raw value. The sub-return
;; halves ride in `sub-return-validation-redacts-when-sensitive` and
;; `sub-return-validation-non-sensitive-rides-query-v-verbatim` above.

(deftest non-sensitive-app-db-failure-carries-humanized-payload
  (testing "a NON-sensitive app-db validation failure (Malli
            humanizer hook loaded) carries the real :explain-humanized
            payload alongside the raw :explain"
    (rf/reg-app-schema [:auth :token] [:string])
    (with-trace-recorder! [traces]
      (rf.schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
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

(deftest sensitive-app-db-failure-humanized-leaks-no-raw-value
  (testing "the raw sensitive value never appears in
            :explain-humanized on a sensitive failure (fail-closed
            privacy proof). Use a distinctive secret so a leak would be
            unmistakable in the trace surface"
    (let [secret "TOP-SECRET-token-9f3a2"]
      ;; Schema wants an :int; the secret is a string — fails. The value
      ;; itself is the sensitive material that must not surface.
      (rf/reg-app-schema [:auth :token] [:int {:sensitive? true}])
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:auth {:token secret}} :auth/init-bad)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v)))
          (is (= :rf/redacted (-> v :tags :explain-humanized)))
          (is (not (str/includes? (pr-str (:tags v)) secret))
              "the raw secret does NOT appear anywhere in the emitted tags"))))))

;; ---- composition with :large? --------------------------------------------

(deftest sensitive-overrides-large-on-same-slot
  (testing "Per Spec 010 §`:sensitive?` + Spec 009 §Unified wire-elision
            surface — a slot carrying both :sensitive? and :large? in
            schema-validation traces redacts on sensitivity; the size
            marker would re-leak :path / :bytes and is NOT emitted"
    ;; Schema declares the slot BOTH large and sensitive.
    (rf/reg-app-schema [:user :secret-pdf]
                       [:string {:sensitive? true :large? true}])
    (with-trace-recorder! [traces]
      ;; The value is an int (42), a wrong type, to force a validation
      ;; failure regardless of how :large? would behave at runtime.
      (rf.schemas/validate-app-schema! {:user {:secret-pdf 42}} :doc/bad)
      (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                             @traces))]
        (is (some? v))
        (is (true? (:sensitive? v))
            "the slot's :sensitive? flag claims the trace (top-level stamp per Spec 009 hoist)")
        (is (= :rf/redacted (-> v :tags :value))
            "sensitive drop wins on a both-flagged slot: the sentinel, not a
             :rf.size/large-elided marker that would re-leak :path/:bytes")))))

;; ---- COMPILED / OPAQUE schema fail-closed redaction ----------------------
;;
;; A compiled `m/schema` value carries per-slot `{:sensitive? true}` props
;; Malli HONOURS for validate/explain, but the pure-data walker treats the
;; compiled value as an OPAQUE LEAF — `schema-has-sensitive?` returns false, so
;; a validation emit keyed on it alone would ship `:value` / `:received` /
;; `:explain` VERBATIM (while the equivalent VECTOR form redacts). EP-0015
;; fail-closed:
;; an opaque schema the walker cannot prove non-sensitive redacts as sensitive.

(deftest walker-opaque-predicate
  (testing "schema-opaque? is true for a compiled m/schema /
            map / fn, false for vector-form EDN and bare keywords"
    (is (true? (rf.schemas/schema-opaque?
                 (m/schema [:map [:password {:sensitive? true} :string]])))
        "compiled m/schema object is opaque")
    (is (true? (rf.schemas/schema-opaque? {:not :a-schema})) "a map is opaque")
    (is (true? (rf.schemas/schema-opaque? (fn [_] true))) "a fn is opaque")
    (is (false? (rf.schemas/schema-opaque? [:map [:k :int]]))
        "vector-form EDN is walkable, not opaque")
    (is (false? (rf.schemas/schema-opaque? :int)) "a bare keyword is not opaque")
    (is (false? (rf.schemas/schema-opaque? :my/registry-ref))
        "a registry-ref keyword is not opaque (provably-safe-or-silent caveat)")))

(deftest app-db-validation-opaque-schema-fails-closed
  (testing "a compiled app-db schema with a :sensitive? slot
            redacts the failing candidate slice fail-closed"
    (let [secret "OPAQUE-APPDB-SECRET-u9bjgr"]
      (rf/reg-app-schema [:user]
                         (m/schema [:map [:token {:sensitive? true} :string]]))
      (with-trace-recorder! [traces]
        ;; :token is a VECTOR where a :string is required → fails the schema.
        (rf.schemas/validate-app-schema! {:user {:token [secret]}} :user/bad)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a validation-failure trace fired")
          (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
          (is (not (str/includes? (pr-str v) secret))
              "the secret survives nowhere in the opaque app-db failure trace"))))))

(deftest redact-validation-tags-opaque-schema-fails-closed
  (testing "the off-namespace redact-validation-tags seam fails
            closed for a compiled / opaque schema (machine-data / sub-override /
            flow-output / boundary all route through it)"
    (let [secret   "OPAQUE-SEAM-SECRET-u9bjgr"
          compiled (m/schema [:map [:secret {:sensitive? true} :string]])
          tags     {:where    :machine-data
                    :value    {:secret secret}
                    :received {:secret secret}
                    :explain  {:value {:secret secret}}}
          out      (rf.schemas/redact-validation-tags compiled tags)]
      (is (true? (:sensitive? out)) "fail-closed: :sensitive? stamped")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:received out)) ":received redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (not (str/includes? (pr-str out) secret))
          "the secret survives nowhere through the opaque seam"))))

;; ---- NESTED opaque schema fail-closed redaction --------------------------
;;
;; The TOP-LEVEL opaque case is a compiled `m/schema` value registered
;; directly. The nested case is a VECTOR-FORM schema — introspectable at its
;; root — that embeds a compiled `m/schema` value as a CHILD somewhere
;; inside it. `walk-flagged-schema` recurses into vector-form structure but its
;; terminal `:else acc` bailout SILENTLY SKIPS a nested opaque child (no
;; declaration, no warning) exactly like it would the root, and
;; `schema-opaque?` on the ROOT is false for a vector-form schema regardless
;; of what opaque values it nests. A fail-closed composition that consulted
;; only the root would let a nested `{:sensitive? true}` slot Malli honours
;; ride every value-bearing trace tag VERBATIM. So
;; `schema-has-opaque-child?` recurses the whole tree (and `align-in-path` / `schema-sensitive-at?` also check the schema a
;; fully-resolved path ARRIVES at, since a leaf-precise app-db path can resolve
;; cleanly right onto a nested opaque slot).

(deftest schema-has-opaque-child-predicate
  (testing "a vector-form schema stays non-opaque however deep and however
            sensitive, and a bare fn or symbol is flag-free NESTED as a slot
            tail but fails closed used AS the whole schema. The compiled-child
            corpus is `schemas_walker_literal_operand_test`'s"
    (is (false? (rf.schemas/schema-has-opaque-child?
                  [:map [:id :int] [:name :string]
                   [:auth [:map [:token {:sensitive? true} :string]]]]))
        "an all-vector-form schema (however deep, however sensitive) has no
         opaque descendant")
    ;; Confirm-by-corpus: [:map [:n pos-int?]] is the EXACT shape
    ;; of the machine/data-schema-rollback conformance fixture's [:schemas
    ;; :data] schema. A bare predicate fn NESTED as a :map slot's tail cannot
    ;; carry a {props} map (there is no syntax for props on an unwrapped fn
    ;; — that requires [:fn {...} pred], itself vector-form); any
    ;; :sensitive?/:large? on the :n slot would live on the SLOT's entry
    ;; ([:n {:sensitive? true} pos-int?]), which walk-flagged-schema
    ;; sees before touching the tail. A NESTED bare fn is therefore provably
    ;; flag-free — the SAME reasoning the walker's keyword exception
    ;; applies — and must NOT be treated as opaque, or
    ;; every ordinary pos-int?/string?-style leaf in the codebase's own
    ;; conformance corpus would over-redact.
    (is (false? (rf.schemas/schema-has-opaque-child? [:map [:n pos-int?]]))
        "a nested bare predicate fn (pos-int? / string? / …) is provably
         flag-free, same as a bare keyword — not opaque")
    ;; The ACTUAL runtime shape the conformance fixture hits:
    ;; the EDN loader (`clojure.edn/read-string`, no reader-resolver) reads
    ;; `pos-int?` as a bare SYMBOL, not a live fn value; Malli resolves the
    ;; symbol to the named fn/var at validate-time (confirmed: `(m/validate
    ;; [:map [:n 'pos-int?]] {:n 0})` => false, `{:n 5}` => true). A NESTED
    ;; bare symbol is exactly as provably flag-free as a nested bare fn.
    (is (false? (rf.schemas/schema-has-opaque-child? [:map [:n 'pos-int?]]))
        "a nested bare SYMBOL (the EDN-sourced shape Malli resolves) is
         provably flag-free — not opaque")
    ;; The ROOT case does NOT get the nested exclusion: a bare
    ;; fn/symbol used AS THE WHOLE registered :schema (no wrapper at all,
    ;; e.g. re-frame.flows' {:schema (fn [v] ...)}) has no sibling {props}
    ;; position anywhere in ITS registration shape — unlike the nested-tail
    ;; case there is no entry one level up that could carry the flag — so
    ;; schema-opaque?'s fail-closed root treatment holds
    ;; here (flows_schema_validation_test/non-conforming-output-emits-
    ;; violation-but-still-writes pins exactly this via redact-validation-
    ;; tags, the seam this predicate feeds).
    (is (true? (rf.schemas/schema-has-opaque-child? pos-int?))
        "a bare fn used AS the whole schema still fails closed, matching
         schema-opaque? — the root/nested split is deliberate, not an
         oversight")
    (is (true? (rf.schemas/schema-has-opaque-child? 'pos-int?))
        "a bare symbol used AS the whole schema likewise fails closed")))

(deftest event-validation-nested-opaque-schema-fails-closed
  (testing "a VECTOR-FORM event schema (root introspectable) whose
            :cat element is a NESTED compiled m/schema value with a
            :sensitive? slot inside redacts the failing payload
            fail-closed. schema-has-sensitive? skips the opaque :cat element
            (no declaration recorded) and schema-opaque? on the ROOT
            [:cat ...] form is false, so a sensitive? built from those two
            alone would compute false and let the secret ride verbatim."
    (let [secret        "NESTED-OPAQUE-EVENT-SECRET-hi0tf8"
          nested-opaque (m/schema [:map [:password {:sensitive? true} :string]])
          schema        [:cat [:= :auth/login] nested-opaque]]
      (with-trace-recorder! [traces]
        ;; :password is a VECTOR where a :string is required -> fails the schema.
        (rf.schemas/validate-event! :auth/login [:auth/login {:password [secret]}]
                                 {:schema schema})
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a validation-failure trace fired")
          (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
          (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
          (is (= :rf/redacted (-> v :tags :received)) ":received redacted")
          (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
          (is (not (str/includes? (pr-str v) secret))
              "the secret survives nowhere in the nested-opaque event failure trace"))))))

(deftest app-db-validation-nested-opaque-schema-fails-closed
  (testing "a VECTOR-FORM app-db schema (root introspectable)
            whose failing slot's OWN tail is a nested compiled m/schema value
            declaring :sensitive? redacts fail-closed, even though the
            failing :in path resolves CLEANLY (align-in-path's :ok outcome)
            straight onto that opaque leaf. extract-sensitive-paths-from-schema
            cannot see into the opaque leaf, so an :ok branch of
            schema-sensitive-at? that consulted only it, without checking
            whether the arrival schema itself is opaque, would let the
            narrowed :value leak the secret verbatim."
    (let [secret "NESTED-OPAQUE-APPDB-SECRET-hi0tf8"]
      (rf/reg-app-schema [:token]
                         [:map [:token (m/schema [:string {:sensitive? true}])]])
      (with-trace-recorder! [traces]
        ;; :token's value is a VECTOR where the nested compiled schema
        ;; requires a :string -> fails exactly at the opaque leaf ([:token]).
        (rf.schemas/validate-app-schema! {:token {:token [secret]}} :token/bad)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a validation-failure trace fired")
          (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
          (is (= :rf/redacted (-> v :tags :value)) ":value redacted at the leaf")
          (is (not (str/includes? (pr-str v) secret))
              "the secret survives nowhere in the nested-opaque app-db failure trace"))))))

(deftest redact-validation-tags-nested-opaque-schema-fails-closed
  (testing "the off-namespace redact-validation-tags seam
            (machine-data / sub-override / flow-output / boundary) fails
            closed for a VECTOR-FORM schema that nests a compiled / opaque
            m/schema child"
    (let [secret   "NESTED-OPAQUE-SEAM-SECRET-hi0tf8"
          schema   [:map [:secret (m/schema [:string {:sensitive? true}])]]
          tags     {:where    :machine-data
                    :value    {:secret secret}
                    :received {:secret secret}
                    :explain  {:value {:secret secret}}}
          out      (rf.schemas/redact-validation-tags schema tags)]
      (is (true? (:sensitive? out)) "fail-closed: :sensitive? stamped")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:received out)) ":received redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (not (str/includes? (pr-str out) secret))
          "the secret survives nowhere through the nested-opaque seam"))))

;; ---- Malli LOCAL :registry fails closed ----------------------------------
;;
;; `[:schema {:registry {::user [:map [:pw {:sensitive? true} :string]]}} ::user]`
;; is walkable at its root (`:schema` is a transparent combinator) and its only
;; child is the bare keyword `::user`, which the walker deliberately treats as a
;; flag-free primitive rather than failing closed (`schema-opaque?` — the
;; keyword exception exists so every plain scalar failure is not over-redacted).
;; So the whole form walks to {} for BOTH flags, while Malli DOES honour the
;; `:sensitive?` inside the registry — were the form classified NOT opaque,
;; every :value / :explain slot of a real failure at [:pw] would ship the
;; secret verbatim, with no stamp and no registration nudge. Per Spec 010 §The `:schema` value is opaque
;; to re-frame, an unrecognised shape fails CLOSED; the local registry is the
;; one registry shape whose presence the walk can see, so it does.

(def ^:private local-registry-schema
  [:schema {:registry {::user [:map [:pw {:sensitive? true} :string]]}} ::user])

(deftest walker-local-registry-classified-opaque-and-values-not-walked
  (testing "a local `:registry` classifies the form opaque, and the
            registry VALUES are deliberately left unwalked"
    (is (true? (rf.schemas.walker/schema-local-registry? local-registry-schema))
        "the predicate sees a :registry on the form's own props")
    (is (true? (rf.schemas.walker/schema-local-registry?
                 [:map {:registry {::pw [:string {:sensitive? true}]}} [:pw ::pw]]))
        "the check is on the PROPS, so it is op-independent — a :map-borne
         local registry is caught by the same branch as a :schema-borne one")
    (is (false? (rf.schemas.walker/schema-local-registry?
                  [:map [:pw {:sensitive? true} :string]]))
        "an ordinary vector form carries no :registry and is untouched")
    (is (true? (rf.schemas/schema-has-opaque-child? local-registry-schema))
        "the local-registry form fails closed")
    ;; The scope limit, pinned: resolving registry references is schema
    ;; INTERPRETATION, which Spec 010 reserves for the registered validator.
    ;; Over-redaction is the documented direction — the flags stay invisible
    ;; and the whole failure redacts instead.
    (is (= {} (rf.schemas/extract-sensitive-paths-from-schema
                local-registry-schema []))
        "the registry's :sensitive? slot is NOT walked into — no declaration
         is synthesised for it")
    (is (= {} (rf.schemas/extract-large-paths-from-schema
                local-registry-schema []))
        "nor is :large? — both flags stay unwalked")))

(deftest app-db-validation-local-registry-schema-fails-closed
  (testing "a real app-db validation failure at [:pw], where the
            failing slot's `:sensitive?` is declared inside a LOCAL registry,
            redacts fail-closed"
    (let [secret "LOCAL-REGISTRY-APPDB-SECRET-amgtr"]
      (rf/reg-app-schema [:auth] local-registry-schema)
      (with-trace-recorder! [traces]
        ;; :pw is a VECTOR where the registry's ::user requires a :string ->
        ;; a genuine failure at [:pw], the slot the registry marks sensitive.
        (rf.schemas/validate-app-schema! {:auth {:pw [secret]}} :auth/bad)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a validation-failure trace fired")
          (is (true? (:sensitive? v)) "fail-closed: top-level :sensitive? stamp")
          (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
          (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
          (is (not (str/includes? (pr-str v) secret))
              "the secret survives nowhere in the local-registry failure trace"))))))

;; ---- :large? value-bearing slot elision ----------------------------------
;;
;; The validation-failure redaction path applies :large? schema metadata as
;; well as schema-has-sensitive?: a path that consulted only sensitivity
;; would ship a bad event's raw large blob through :value / :received /
;; :explain instead of eliding it. Per Spec 010 §`:large?` (validation
;; size-safety arm) the value-bearing slots elide to the
;; :rf.size/large-elided marker; sensitive wins over large (Spec 010
;; §Composition with `:large?`).

(deftest walker-has-large-predicate
  (testing "schema-has-large? mirrors schema-has-sensitive? on the
            :large? flag"
    (is (true? (rf.schemas/schema-has-large?
                 [:map [:blob {:large? true} :string]])))
    (is (true? (rf.schemas/schema-has-large?
                 [:map [:doc [:map [:payload {:large? true} :string]]]]))
        "nested :large? detected")
    (is (false? (rf.schemas/schema-has-large? [:map [:n :int]]))
        "no :large? slot → false")
    (is (false? (rf.schemas/schema-has-large?
                  [:map [:secret {:sensitive? true} :string]]))
        ":sensitive? is not :large? — the flags are independent")))

(deftest event-validation-large-slot-elides
  (testing "a :large? slot's validation failure elides :value /
            :received / :explain to a well-formed :rf.size/large-elided
            marker rather than shipping the raw blob. The marker carries the
            canonical :reason :effect provenance (EP-0025 — the commit-plane
            classification default; NOT a :frame annotation, NOT
            :reason :schema), with the REQUIRED :hint slot present"
    (let [blob (apply str (repeat 500 "Z"))]
      (with-trace-recorder! [traces]
        (rf.schemas/validate-event! :upload/save [:upload/save {:blob blob}]
                                 {:schema [:cat [:= :upload/save]
                                           [:map [:blob {:large? true} :int]]]})
        (let [v      (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                                    @traces))
              marker (-> v :tags :value :rf.size/large-elided)]
          (is (some? v) "a validation-failure trace fired")
          (is (not (contains? v :sensitive?))
              "a :large?-only failure is NOT stamped sensitive")
          (is (map? marker) ":value carries a :rf.size/large-elided marker")
          (is (= :effect (:reason marker))
              ":reason :effect — the EP-0025 canonical classification provenance default")
          (is (contains? marker :hint) ":hint slot present (REQUIRED by the contract)")
          (is (integer? (:bytes marker)) ":bytes is a byte count")
          (is (= [:rf.elision/at []] (:handle marker)) ":handle is the fetch handle")
          (is (true? (-> v :tags :large?)) ":tags :large? stamped")
          (doseq [slot [:received :explain]]
            (is (contains? (-> v :tags slot) :rf.size/large-elided)
                (str slot " elided to the size marker")))
          (is (not (str/includes? (pr-str v) blob))
              "the raw blob never rides the trace"))))))

(deftest app-db-validation-large-slot-elides
  (testing "a :large? app-db slot's candidate validation
            failure elides the value-bearing slots to the size marker"
    (let [blob (apply str (repeat 500 "Y"))]
      (rf/reg-app-schema [:upload] [:map [:blob {:large? true} :int]])
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:upload {:blob blob}} :upload/bad)
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (-> v :tags :large?)) ":tags :large? stamped")
          (is (contains? (-> v :tags :value) :rf.size/large-elided)
              ":value elided")
          (is (not (str/includes? (pr-str v) blob))
              "the raw blob never rides the app-db failure trace"))))))

(deftest large-and-sensitive-sensitive-wins
  (testing "Spec 010 §Composition with `:large?` — a slot carrying
            BOTH :large? and :sensitive? redacts on sensitivity; NO
            :rf.size/large-elided marker is emitted (it would leak :bytes)"
    (let [secret (apply str (repeat 100 "S"))]
      (with-trace-recorder! [traces]
        (rf.schemas/validate-event! :x [:x {:blob secret}]
                                 {:schema [:cat [:= :x]
                                           [:map [:blob {:large? true :sensitive? true} :int]]]})
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v))
          (is (true? (:sensitive? v)) "sensitive stamp wins")
          (is (= :rf/redacted (-> v :tags :value))
              ":value redacted, not a size marker that would re-leak :bytes")
          (is (not (-> v :tags :large?)) "no :large? stamp on a sensitive failure")
          (is (not (str/includes? (pr-str v) secret))))))))

(deftest redact-validation-tags-large-slot-elides
  (testing "the off-namespace seam elides value-bearing slots for
            a :large? (non-sensitive) schema"
    (let [blob (apply str (repeat 300 "Q"))
          tags {:where   :flow-output
                :value   {:blob blob}
                :explain {:value {:blob blob}}}
          out  (rf.schemas/redact-validation-tags
                 [:map [:blob {:large? true} :int]] tags)]
      (is (true? (:large? out)) ":large? stamped")
      (is (contains? (:value out) :rf.size/large-elided) ":value elided")
      (is (contains? (:explain out) :rf.size/large-elided) ":explain elided")
      (is (not (str/includes? (pr-str out) blob))
          "the blob survives nowhere through the seam"))))

(deftest app-db-validation-large-leaf-beside-sensitive-sibling-elides
  (testing "a failing :large? leaf in a schema that ALSO
            declares a :sensitive? sibling: the whole-payload :explain redacts
            (the sibling rides in it) and the leaf-narrowed :value elides to a
            size marker built from the LEAF — a sensitive sibling elsewhere in
            the schema must not switch the size-safety arm off for :value"
    (let [blob (apply str "BLOB-SENTINEL-19-2-" (repeat 500 "L"))]
      (rf/reg-app-schema [:doc] [:map
                                 [:token {:sensitive? true} :string]
                                 [:pdf {:large? true} :int]])
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:doc {:token "TOKEN-OK-19-2" :pdf blob}}
                                         :doc/bad)
        (let [v      (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                                    @traces))
              marker (-> v :tags :value :rf.size/large-elided)]
          (is (some? v) "a trace fired")
          (is (= [:doc :pdf] (-> v :tags :path)))
          (is (= :rf/redacted (-> v :tags :explain))
              ":explain (whole registered value) redacted — the sensitive sibling rides in it")
          (is (map? marker) ":value (the narrowed :pdf leaf) is the size marker")
          (is (= :string (:type marker))
              "the marker describes the LEAF, not the whole map carrying the sensitive sibling")
          (is (true? (-> v :tags :large?)) ":tags :large? stamped")
          (is (not (str/includes? (pr-str v) blob))
              "the large blob survives nowhere verbatim in the failure trace")
          (is (not (str/includes? (pr-str v) "TOKEN-OK-19-2"))
              "the sensitive sibling survives nowhere either"))))))

(deftest sub-return-large-elision-keeps-query-v
  (testing "the size marker replaces the CHECKED value only;
            `:rf.sub/query-v` is the subscription's lookup key, not the
            checked value, so it survives both the hot-path and the
            off-namespace seam (the `:sub-override` path)"
    (let [blob (apply str (repeat 500 "Q"))]
      (with-trace-recorder! [traces]
        (rf.schemas/validate-sub! :probe/sub [:probe/sub 42] {:pdf blob}
                                  {:schema [:map [:pdf {:large? true} :int]]})
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (some? v) "a trace fired")
          (is (= [:probe/sub 42] (-> v :tags :rf.sub/query-v))
              ":rf.sub/query-v rides verbatim")
          (is (contains? (-> v :tags :value) :rf.size/large-elided)
              ":value is still elided")
          (is (not (str/includes? (pr-str v) blob)) "the blob never rides the trace")))
      (let [out (rf.schemas/redact-validation-tags
                  [:map [:pdf {:large? true} :int]]
                  {:where :sub-override :rf.sub/query-v [:probe/sub 42] :value {:pdf blob}})]
        (is (= [:probe/sub 42] (:rf.sub/query-v out))
            "the seam leaves :rf.sub/query-v alone too")
        (is (contains? (:value out) :rf.size/large-elided) "the seam still elides :value")))))

;; ---- sensitive closed-map EXTRA KEY ---------------------------------------
;; Malli reports a `[:map {:closed true} …]` extra-key failure with the
;; CALLER-SUPPLIED EXTRA KEY VALUE itself as the `:in` segment — arbitrary user
;; data (decoded JSON keys, typos, dynamic form names, headers, hostile input)
;; that may itself be a credential. A key-not-found branch in
;; `sanitize-sensitive-path` that `(conj out seg)`-ed the unknown segment
;; before fail-closing only the tail would ship the secret key VERBATIM
;; through `:path` and `:reason` (and every serialized trace tag) even with
;; `:value` / `:explain` / `:explain-humanized` correctly redacted and the
;; top-level `:sensitive?` stamp present — a record that LOOKED redacted
;; would still carry the raw secret twice. So the unknown segment routes
;; into the same fail-closed scrub as set element values, sensitive `:map-of` keys, and unresolved wrapper
;; tails: only schema-DECLARED `:map` keys are proven structural locators. An
;; open map never emits an extra-key error, so declared keys keep their precise
;; branch and only undeclared / shape-drift segments fail closed.

;; -- pure sanitizer: shared cross-host corpus (parity anchor; the CLJS half
;;    is re-frame.schemas-sensitive-path-cljs-test) --

(deftest sanitize-closed-map-extra-key-shared-corpus
  (testing "the shared sanitize-sensitive-path corpus holds on
            the JVM (closed-map extra keys scrub; declared locators survive;
            set / :map-of / ambiguous-tail cases hold)"
    (doseq [{:keys [desc schema in expected]} rf.schemas.walker-sanitize-path-fixtures/cases]
      (is (= expected (rf.schemas.walker/sanitize-sensitive-path schema in))
          desc))))

;; -- end-to-end via validate-app-schema! (:path / :reason / whole-trace egress) --

(deftest app-db-validation-sensitive-closed-map-extra-key-scrubbed
  (testing "a sensitive closed map's undeclared EXTRA key is
            scrubbed from :path AND :reason; the secret key appears NOWHERE in
            the emitted trace (deep whole-trace scan)"
    (let [v (app-db-failure-trace
              [:profile]
              [:map {:closed true :sensitive? true} [:known :int]]
              {:profile {:known 1 "SECRET-KEY-7f93" 2}}
              :profile/bad)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v)) "top-level :sensitive? stamp present")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      (is (= [:profile :rf/redacted] (-> v :tags :path))
          ":path carries the :rf/redacted sentinel where the extra key was")
      (is (not (str/includes? (pr-str (-> v :tags :reason)) "SECRET-KEY-7f93"))
          "the secret key does NOT appear in the generated :reason text")
      (is (not (str/includes? (pr-str v) "SECRET-KEY-7f93"))
          "the secret key does NOT appear ANYWHERE in the whole trace event"))))

(deftest app-db-validation-hostile-extra-key-and-value-never-leak
  (testing "adversarial — a HOSTILE extra key (a credential-shaped
            string) and its VALUE both stay out of the whole emitted trace; the
            declared sibling's data does not leak either"
    (let [hostile-key "Bearer eyJhbGciOiJIUzI1NiJ9.HOSTILE-TOKEN-9d41"
          v           (app-db-failure-trace
                        [:profile]
                        [:map {:closed true :sensitive? true} [:known :int]]
                        {:profile {:known 7 hostile-key "HOSTILE-VALUE-31337"}}
                        :profile/hostile)]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v)) "top-level :sensitive? stamp present")
      (is (= [:profile :rf/redacted] (-> v :tags :path))
          "the hostile key is the :rf/redacted sentinel in :path")
      (is (not (str/includes? (pr-str v) "HOSTILE-TOKEN-9d41"))
          "the hostile key does NOT appear anywhere in the whole trace event")
      (is (not (str/includes? (pr-str v) "HOSTILE-VALUE-31337"))
          "the hostile key's VALUE does NOT appear anywhere in the whole trace event"))))

(deftest app-db-validation-non-sensitive-closed-map-extra-key-stays-precise
  (testing "a NON-sensitive closed map's extra-key
            failure is not stamped :sensitive? and its precise path rides
            verbatim (the sanitizer only runs on sensitive failures — no
            over-redaction of plain closed maps)"
    (let [v (app-db-failure-trace
              [:plain]
              [:map {:closed true} [:known :int]]
              {:plain {:known 1 "extra-key" 2}}
              :plain/bad)]
      (is (some? v) "a trace fired")
      (is (not (contains? v :sensitive?))
          "no :sensitive? stamp — nothing in the schema is sensitive")
      (is (= [:plain "extra-key"] (-> v :tags :path))
          "the extra key rides verbatim in :path — precise diagnostics kept"))))

;; ---- variable-width :cat / :catn -----------------------------------------
;; A regex element consumes zero or many values, so a :cat input index is not
;; its schema child index. The shared corpus pins the leak shapes and the
;; non-sensitive controls; the CLJS half asserts the same corpus.

(deftest app-db-validation-variable-width-sequence-never-leaks-sensitive-value
  (testing "a regex element before a sensitive payload does not
            misalign the redaction decision"
    (doseq [{:keys [desc schema value]} rf.schemas.sequence-width-fixtures/leak-cases]
      (let [v (app-db-failure-trace [:items] schema {:items value} :items/bad)]
        (is (some? v) (str desc " — a trace fired"))
        (is (true? (:sensitive? v)) (str desc " — :sensitive? stamped"))
        (is (= :rf/redacted (-> v :tags :value)) (str desc " — :value redacted"))
        (is (not (str/includes? (pr-str v) rf.schemas.sequence-width-fixtures/secret))
            (str desc " — the secret is in no trace slot"))))))

(deftest app-db-validation-variable-width-fix-keeps-non-sensitive-values
  (testing "control — non-sensitive failures report their value"
    (doseq [{:keys [desc schema value expected]} rf.schemas.sequence-width-fixtures/precise-cases]
      (let [v (app-db-failure-trace [:items] schema {:items value} :items/bad)]
        (is (some? v) (str desc " — a trace fired"))
        (is (= expected (-> v :tags :value)) desc)))))
