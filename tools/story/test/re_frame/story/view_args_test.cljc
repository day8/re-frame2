(ns re-frame.story.view-args-test
  "Production-path regression gate for the ONE shared view-args-schema
  resolver (rf2-din8u phase-1 / rf2-p5ivc (b) / rf2-ayu6n / rf2-vnedo).

  THE CI BLIND SPOT this closes. The pre-ruling tests for view-args
  resolution all threaded an explicit `:view-lookup` / `:lookup` (the
  host-free pure path), which masked the divergence: the consumers
  (`controls/resolve-argtypes`, `schema-validation/resolve-component-
  schema`) read the BARE registrar body, not the compiled plan. These
  tests instead exercise the PRODUCTION path:

    - a REGISTERED variant (written to the Story side-table);
    - a REGISTERED `:component` view carrying its props schema under
      `:rf/props` (written to the framework `:view` registrar);
    - compiled via the DEFAULT side-table lookup — NO `:lookup` /
      `:view-lookup` arg.

  Proving: a registered `:rf/props`-declared variant resolves its
  view-args schema off the compiled plan's `[:world :view-args-schema]`,
  through the shared resolver, with the canonical `[:rf/props :schema]`
  first-match order and NO `:spec` key (dead post-M-54).

  Pure JVM + CLJS — `view-args.cljc` + `plan.cljc` + both registrars are
  JVM-runnable, so the DEFAULT lookup works under `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.view-args :as view-args]
            [re-frame.story.registrar :as registrar]
            [re-frame.registrar       :as framework-registrar]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Each test wipes both registrars (the Story side-table + the framework
;; `:view` registrar) so the DEFAULT lookup sees only what the test
;; registers. The shared resolver memoizes on the Story side-table's
;; mutation-tick, so a `clear-all!` (which bumps the tick) also invalidates
;; the resolver cache between tests.

(defn reset-fixture [test-fn]
  (registrar/clear-all!)
  (framework-registrar/clear-kind! :view)
  (test-fn))

(use-fixtures :each reset-fixture)

(defn- reg-view-meta!
  "Register a `:view` slot carrying `metadata` (a props-schema map) on the
  framework registrar — the slot the plan compiler's default view-lookup
  reads. A `:handler-fn` stub keeps the slot well-formed; the resolver
  only reads the schema slots."
  [view-id metadata]
  (framework-registrar/register! :view view-id
                                 (assoc metadata :handler-fn (fn [_] nil))))

;; ---- the key picker (rf2-ayu6n: stale :spec dropped) ---------------------

(deftest view-args-schema-keys-drop-spec
  (testing "the canonical key order is [:rf/props :schema] — :spec is gone"
    (is (= [:rf/props :schema] view-args/view-args-schema-keys))))

(deftest view-args-schema-first-match-no-composition
  (testing ":rf/props wins outright over :schema (no composition)"
    (is (= [:map [:a :string]]
           (view-args/view-args-schema {:rf/props [:map [:a :string]]
                                        :schema   [:map [:b :string]]}))))
  (testing ":schema is the fallback location when :rf/props is absent"
    (is (= [:map [:b :string]]
           (view-args/view-args-schema {:schema [:map [:b :string]]}))))
  (testing "a view carrying ONLY :spec resolves NO schema (dead post-M-54)"
    (is (nil? (view-args/view-args-schema {:spec [:map [:c :string]]})))))

;; ---- the compiled-plan resolver (PRODUCTION path, DEFAULT lookup) --------

(deftest compiled-resolver-reads-rf-props-off-default-lookup
  (testing "a REGISTERED :rf/props-declared variant resolves its schema off
            the compiled plan via the DEFAULT side-table lookup"
    (let [schema [:map [:label :string] [:count :int]]]
      (reg-view-meta! :views/widget {:rf/props schema})
      (registrar/reg-variant* :story.prod/ok
                              {:component :views/widget
                               :args      {:label "Hi" :count 3}
                               :events    []})
      ;; No :lookup / :view-lookup — the DEFAULT side-table + framework
      ;; `:view` registrar path the production runtime takes.
      (is (= schema (view-args/compiled-view-args-schema :story.prod/ok))))))

(deftest compiled-resolver-rf-props-wins-over-schema-on-default-lookup
  (testing ":rf/props wins over :schema on the registered view, end-to-end"
    (reg-view-meta! :views/dual {:rf/props [:map [:a :string]]
                                 :schema   [:map [:b :string]]})
    (registrar/reg-variant* :story.prod/dual
                            {:component :views/dual
                             :args      {:a "x"}
                             :events    []})
    (is (= [:map [:a :string]]
           (view-args/compiled-view-args-schema :story.prod/dual)))))

(deftest compiled-resolver-schema-fallback-on-default-lookup
  (testing "a view carrying its schema under :schema (no :rf/props) still
            resolves — :schema is the post-M-54 fallback location"
    (reg-view-meta! :views/schemaed {:schema [:map [:title :string]]})
    (registrar/reg-variant* :story.prod/schemaed
                            {:component :views/schemaed
                             :args      {:title "T"}
                             :events    []})
    (is (= [:map [:title :string]]
           (view-args/compiled-view-args-schema :story.prod/schemaed)))))

(deftest compiled-resolver-spec-only-view-resolves-nil
  (testing "a view whose ONLY schema slot is the dead :spec key resolves no
            schema on the production path (rf2-ayu6n — :spec is dead)"
    (reg-view-meta! :views/specced {:spec [:map [:x :string]]})
    (registrar/reg-variant* :story.prod/specced
                            {:component :views/specced
                             :args      {:x "v"}
                             :events    []})
    (is (nil? (view-args/compiled-view-args-schema :story.prod/specced)))))

(deftest compiled-resolver-resolves-extends-inherited-component
  (testing "an :extends-INHERITED :component resolves its schema — the
            divergence the bare-body resolvers (reading (:component
            variant-body)) missed (rf2-din8u compiled-plan invariant)"
    (reg-view-meta! :views/base {:rf/props [:map [:label :string]]})
    ;; The parent declares :component; the child inherits it via :extends
    ;; and declares no :component of its own. A bare-body read of the child
    ;; would find no :component → no schema. The compiled plan resolves the
    ;; parent chain, so the schema flows down.
    (registrar/reg-variant* :story.prod/parent
                            {:component :views/base
                             :args      {:label "P"}
                             :events    []})
    (registrar/reg-variant* :story.prod/child
                            {:extends :story.prod/parent
                             :args    {:label "C"}
                             :events  []})
    (is (= [:map [:label :string]]
           (view-args/compiled-view-args-schema :story.prod/child))
        "the inherited :component's props schema resolves off the compiled plan")))

(deftest compiled-resolver-matches-compiled-plan-slot
  (testing "the resolver returns EXACTLY the plan's [:world :view-args-schema]
            — one source of truth, not a re-derivation"
    (let [schema [:map [:label :string]]]
      (reg-view-meta! :views/same {:rf/props schema})
      (registrar/reg-variant* :story.prod/same
                              {:component :views/same
                               :args      {:label "Hi"}
                               :events    []})
      ;; The shared resolver and the controls/schema-validation panels MUST
      ;; agree because they read the same compiled slot.
      (is (= schema (view-args/compiled-view-args-schema :story.prod/same))))))

(deftest compiled-resolver-unregistered-variant-is-nil
  (testing "an unregistered variant resolves nil (best-effort tooling read —
            no throw)"
    (is (nil? (view-args/compiled-view-args-schema :story.prod/nope)))))

(deftest compiled-resolver-no-component-is-nil
  (testing "a registered variant with no :component resolves nil"
    (registrar/reg-variant* :story.prod/plain
                            {:args {:x 1} :events []})
    (is (nil? (view-args/compiled-view-args-schema :story.prod/plain)))))

(deftest compiled-resolver-view-without-schema-is-nil
  (testing "a registered :component view carrying NO schema slot resolves nil"
    (reg-view-meta! :views/bare {})
    (registrar/reg-variant* :story.prod/bare
                            {:component :views/bare
                             :args      {:x 1}
                             :events    []})
    (is (nil? (view-args/compiled-view-args-schema :story.prod/bare)))))

;; ---- memoization invalidates on registrar mutation ----------------------

(deftest compiled-resolver-memo-invalidates-on-reregistration
  (testing "the per-(variant-id, mutation-tick) memo invalidates when the
            view is hot-reloaded with a new props schema"
    (reg-view-meta! :views/hot {:rf/props [:map [:a :string]]})
    (registrar/reg-variant* :story.prod/hot
                            {:component :views/hot
                             :args      {:a "x"}
                             :events    []})
    (is (= [:map [:a :string]]
           (view-args/compiled-view-args-schema :story.prod/hot)))
    ;; Re-register the variant (bumps the Story side-table mutation-tick),
    ;; pointing at a view with a different schema → the memo invalidates.
    (reg-view-meta! :views/hot {:rf/props [:map [:a :string] [:b :int]]})
    (registrar/reg-variant* :story.prod/hot
                            {:component :views/hot
                             :args      {:a "x" :b 1}
                             :events    []})
    (is (= [:map [:a :string] [:b :int]]
           (view-args/compiled-view-args-schema :story.prod/hot))
        "the resolver re-reads the compiled plan after the tick bump")))
