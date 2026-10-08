(ns re-frame.story.view-args-test
  "Production-path gate for the ONE shared view-args-schema resolver.

  A test that threads an explicit `:view-lookup` / `:lookup` (the
  host-free pure path) cannot see a consumer that reads the BARE
  registrar body rather than the compiled plan — a CI blind spot. These
  tests instead exercise the PRODUCTION path:

    - a REGISTERED variant (written to the Story side-table);
    - a REGISTERED `:component` view carrying its props schema under
      `:rf/props` (written to the framework `:view` registrar);
    - compiled via the DEFAULT side-table lookup — NO `:lookup` /
      `:view-lookup` arg.

  Proving: a registered `:rf/props`-declared variant resolves its
  view-args schema off the compiled plan's `[:world :view-args-schema]`,
  through the shared resolver, with the canonical `[:rf/props :schema]`
  first-match order and NO `:spec` key (not a schema key; MIGRATION §M-54).

  Runs on the JVM — `view-args.cljc` + `plan.cljc` + both registrars are
  JVM-runnable, so the DEFAULT lookup works under `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.config    :as rf.story.config]
            [re-frame.story.view-args :as rf.story.view-args]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.registrar       :as rf.registrar]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Each test wipes both registrars (the Story side-table + the framework
;; `:view` registrar) so the DEFAULT lookup sees only what the test
;; registers. The shared resolver memoizes on the Story side-table's
;; mutation-tick, so a `clear-all!` (which bumps the tick) also invalidates
;; the resolver cache between tests.

(defn reset-fixture [test-fn]
  (rf.story.registrar/clear-all!)
  (rf.registrar/clear-kind! :view)
  (test-fn))

(use-fixtures :each reset-fixture)

(defn- reg-view-meta!
  "Register a `:view` slot carrying `metadata` (a props-schema map) on the
  framework registrar — the slot the plan compiler's default view-lookup
  reads. A `:handler-fn` stub keeps the slot well-formed; the resolver
  only reads the schema slots."
  [view-id metadata]
  (rf.registrar/register! :view view-id
                                 (assoc metadata :handler-fn (fn [_] nil))))

;; ---- the compiled-plan resolver (PRODUCTION path, DEFAULT lookup) --------

(deftest compiled-resolver-reads-the-registered-views-schema-slot
  (testing "a REGISTERED variant resolves its view-args schema off the
            compiled plan via the DEFAULT side-table lookup — no :lookup /
            :view-lookup, the framework `:view` registrar path the
            production runtime takes — in [:rf/props :schema] first-match
            order. Each row has its own view and variant id: registering a
            view does not bump the Story mutation tick the resolver's memo
            is keyed on, registering a variant does."
    (doseq [[label view-id view-meta variant-id args expected]
            [[":rf/props"
              :views/widget {:rf/props [:map [:label :string] [:count :int]]}
              :story.prod/ok {:label "Hi" :count 3}
              [:map [:label :string] [:count :int]]]
             [":rf/props wins over :schema"
              :views/dual {:rf/props [:map [:a :string]] :schema [:map [:b :string]]}
              :story.prod/dual {:a "x"}
              [:map [:a :string]]]
             [":schema is the fallback location when there is no :rf/props"
              :views/schemaed {:schema [:map [:title :string]]}
              :story.prod/schemaed {:title "T"}
              [:map [:title :string]]]
             [":spec is not a schema key, so a :spec-only view resolves nil"
              :views/specced {:spec [:map [:x :string]]}
              :story.prod/specced {:x "v"}
              nil]
             ["a view carrying no schema slot resolves nil"
              :views/bare {}
              :story.prod/bare {:x 1}
              nil]
             ["a variant with no :component resolves nil"
              nil nil
              :story.prod/plain {:x 1}
              nil]]]
      (testing label
        (when view-id (reg-view-meta! view-id view-meta))
        (rf.story.registrar/reg-variant* variant-id
                                         (cond-> {:args args :setup []}
                                           view-id (assoc :component view-id)))
        (is (= expected (rf.story.view-args/compiled-view-args-schema variant-id)))))))

(deftest compiled-resolver-resolves-extends-inherited-component
  (testing "an :extends-INHERITED :component resolves its schema — a
            bare-body read ((:component variant-body)) would miss it; the
            compiled plan resolves the chain"
    (reg-view-meta! :views/base {:rf/props [:map [:label :string]]})
    ;; The parent declares :component; the child inherits it via :extends
    ;; and declares no :component of its own. A bare-body read of the child
    ;; would find no :component → no schema. The compiled plan resolves the
    ;; parent chain, so the schema flows down.
    (rf.story.registrar/reg-variant* :story.prod/parent
                            {:component :views/base
                             :args      {:label "P"}
                             :setup    []})
    (rf.story.registrar/reg-variant* :story.prod/child
                            {:extends :story.prod/parent
                             :args    {:label "C"}
                             :setup  []})
    (is (= [:map [:label :string]]
           (rf.story.view-args/compiled-view-args-schema :story.prod/child))
        "the inherited :component's props schema resolves off the compiled plan")))

(def ^:private button-props
  "The 001-Authoring.md flagship button view's props schema."
  [:map [:label :string] [:variant [:enum :primary :secondary :danger]]
   [:size [:int {:min 8 :max 64}]] [:disabled? :boolean]])

(deftest compiled-resolver-resolves-a-variant-that-leaves-props-to-its-story
  (testing "the flagship authoring pattern — required props on
            the story's :args, a variant overriding one — resolves its schema.
            The schema is the component's :rf/props, so the read does not
            depend on which layer supplies the args"
    (reg-view-meta! :views/button {:rf/props button-props})
    (rf.story.registrar/reg-story* :story.ui.button
                                   {:component :views/button
                                    :args      {:label "Go" :variant :primary
                                                :size 16 :disabled? false}})
    (rf.story.registrar/reg-variant* :story.ui.button/danger {:args {:variant :danger}})
    (is (= button-props
           (rf.story.view-args/compiled-view-args-schema :story.ui.button/danger)))
    (testing "and args that genuinely miss a required prop on every layer do
              not erase the schema they were validated against"
      (rf.story.registrar/reg-story* :story.ui.unlabelled
                                     {:component :views/button
                                      :args      {:variant :primary :size 16
                                                  :disabled? false}})
      (rf.story.registrar/reg-variant* :story.ui.unlabelled/danger
                                       {:args {:variant :danger}})
      (is (= button-props
             (rf.story.view-args/compiled-view-args-schema
               :story.ui.unlabelled/danger))))))

(deftest compiled-resolver-resolves-a-variant-whose-steps-read-story-or-global-args
  (testing "a valid variant whose steps substitute an [:arg k] that only
            its story or the globals supply resolves its schema — the read
            compiles with the same ambient arg layers a run of the variant
            does, so the substitution cannot throw
            :rf.error/story-missing-arg ahead of the schema"
    (let [props [:map [:label :string]]]
      (reg-view-meta! :views/labelled {:rf/props props})
      (rf.story.registrar/reg-story* :story.yfwfa
                                     {:component :views/labelled
                                      :args      {:label "Go"}})
      (rf.story.registrar/reg-variant* :story.yfwfa/setup
                                       {:setup [[:dispatch [:app/set-label [:arg :label]]]]})
      (is (= props (rf.story.view-args/compiled-view-args-schema :story.yfwfa/setup))
          "a story-supplied arg read by :setup")
      (testing "and an arg only the globals supply"
        (rf.story.registrar/reg-story* :story.yfwfa-global {:component :views/labelled})
        (rf.story.registrar/reg-variant* :story.yfwfa-global/setup
                                         {:setup [[:dispatch [:app/set-label [:arg :label]]]]})
        (try
          (rf.story.config/set-global-args! {:label "Global"})
          (is (= props (rf.story.view-args/compiled-view-args-schema
                         :story.yfwfa-global/setup)))
          (finally
            (rf.story.config/set-global-args! {}))))
      (testing "a change to the global args is a new memo slot, not a stale one"
        (rf.story.registrar/reg-story* :story.yfwfa-late {:component :views/labelled})
        (rf.story.registrar/reg-variant* :story.yfwfa-late/setup
                                         {:setup [[:dispatch [:app/set-label [:arg :label]]]]})
        (try
          (is (nil? (rf.story.view-args/compiled-view-args-schema :story.yfwfa-late/setup))
              "no layer supplies :label, so the plan cannot compile: a best-effort nil, no throw")
          (rf.story.config/set-global-args! {:label "Late"})
          (is (= props (rf.story.view-args/compiled-view-args-schema :story.yfwfa-late/setup))
              "the globals now supply it; the cached nil is not reused")
          (finally
            (rf.story.config/set-global-args! {})))))))

;; ---- memoization invalidates on registrar mutation ----------------------

(deftest compiled-resolver-memo-invalidates-on-reregistration
  (testing "the per-(variant-id, mutation-tick) memo invalidates when the
            view is hot-reloaded with a new props schema"
    (reg-view-meta! :views/hot {:rf/props [:map [:a :string]]})
    (rf.story.registrar/reg-variant* :story.prod/hot
                            {:component :views/hot
                             :args      {:a "x"}
                             :setup    []})
    (is (= [:map [:a :string]]
           (rf.story.view-args/compiled-view-args-schema :story.prod/hot)))
    ;; Re-register the variant (bumps the Story side-table mutation-tick),
    ;; pointing at a view with a different schema → the memo invalidates.
    (reg-view-meta! :views/hot {:rf/props [:map [:a :string] [:b :int]]})
    (rf.story.registrar/reg-variant* :story.prod/hot
                            {:component :views/hot
                             :args      {:a "x" :b 1}
                             :setup    []})
    (is (= [:map [:a :string] [:b :int]]
           (rf.story.view-args/compiled-view-args-schema :story.prod/hot))
        "the resolver re-reads the compiled plan after the tick bump")))
