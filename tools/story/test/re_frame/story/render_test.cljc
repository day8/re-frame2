(ns re-frame.story.render-test
  "Tests for `render-variant` + the workshop-superset plan slots
  (rf2-5x1wt.24).

  Per `tools/story/spec/017-Testing-Story.md` §Args, controls, and
  `render-variant` + §Storytelling superset, and
  `ai/findings/NewTestStory` §B10. The render-prep core
  (`re-frame.story.render/prepare-render`) is a pure data → data fn, so
  every test runs on both the JVM and CLJS without a host: variant bodies
  + view metadata are supplied through explicit `:lookup` / `:view-lookup`
  maps. The host-render path (`render-variant` proper) is exercised by
  installing a fake `:render-host` hook so the `:rendered` shape + the
  no-host `:cannot-run` refusal are both pinned."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.late-bind :as late-bind]
            [re-frame.story.plan :as plan]
            [re-frame.story.render :as render]))

;; ---- fixtures ------------------------------------------------------------
;;
;; The host-render hook is a process-global late-bind slot shared with the
;; canonical-vocabulary shims (`:tap-stub-event`, `:drop-assertion-
;; accumulators`). We MUST NOT `late-bind/clear!` (that wipes those shims +
;; breaks every sibling test ns). Instead we snapshot the hooks map before
;; each test (so a stray `:render-host` from a prior test is gone) and
;; restore it after — surgical, never global.

(use-fixtures :each
  (fn [t]
    (let [snapshot @late-bind/hooks]
      ;; Drop only the render-host slot so the no-host :cannot-run tests
      ;; start clean; leave every canonical shim in place.
      (swap! late-bind/hooks dissoc :render-host)
      (try (t) (finally (reset! late-bind/hooks snapshot))))))

;; ---- helpers -------------------------------------------------------------

(def ^:private malli-validator
  "The `{:validate :explain}` validator pair the render-prep threads for
  malformed-value checking — a real Malli runtime, so the JVM tests can
  pin the malformed-value path, not only required-key presence."
  {:validate (fn [schema value] (m/validate schema value))
   :explain  (fn [schema value] (m/explain schema value))})

(defn- prepare
  "Run `prepare-render` against an explicit body `lookup` + optional
  `view-lookup` / opts. The render-prep core; no host needed."
  ([target lookup] (prepare target lookup nil))
  ([target lookup view-lookup] (prepare target lookup view-lookup nil))
  ([target lookup view-lookup extra]
   (render/prepare-render
     target
     (cond-> {:lookup lookup}
       (some? view-lookup) (assoc :view-lookup view-lookup)
       (map? extra)        (merge extra)))))

;; ===========================================================================
;; Workshop-superset plan slots (§B10 task #1)
;; ===========================================================================

(deftest workshop-vocabulary-flows-through-the-plan
  (testing "argtypes / decorators / modes / substrates / viewport / background
            / component all ride the normalized plan's :world"
    (let [body {:component   :view.button/primary
                :args        {:label "Go"}
                :argtypes    {:label {:control :text}}
                :decorators  [[:decorator.theme/dark]]
                :modes       #{:mode/dark}
                :substrates  #{:reagent :uix}
                :viewport    :viewport/mobile
                :background  :background/dark}
          p    (plan/variant-plan :story.button/primary {:lookup {:story.button/primary body}})
          w    (:world p)]
      (is (= :view.button/primary (:component w)))
      (is (= {:label {:control :text}} (:argtypes w)))
      (is (= [[:decorator.theme/dark]] (:decorators w)))
      (is (= #{:mode/dark} (:modes w)))
      (is (= #{:reagent :uix} (:substrates w)))
      (is (= :viewport/mobile (:viewport w)))
      (is (= :background/dark (:background w)))
      (testing ":effective-args is recorded as the post-substitution args"
        (is (= {:label "Go"} (:effective-args w)))))))

;; ===========================================================================
;; prepare-render — the documented shape (§B10 task #5)
;; ===========================================================================

(deftest prepare-render-returns-prepared-shape
  (testing "a renderable variant prepares the documented slots"
    (let [body {:component :view.button/primary :args {:label "Go"}}
          r    (prepare :story.button/primary {:story.button/primary body})]
      (is (= :prepared (:status r)))
      (is (= :story.button/primary (:frame r)))
      (is (= {:label "Go"} (:effective-args r)))
      (is (string? (:plan-hash r)))
      (is (map? (:plan r)))
      (testing ":render-inputs carry what the host renderer needs"
        (let [ri (:render-inputs r)]
          (is (= :view.button/primary (:view ri)))
          (is (= {:label "Go"} (:effective-args ri)))
          (is (= :story.button/primary (:frame ri))))))))

;; ===========================================================================
;; Controls update :effective-args + render through the SAME plan (§B10 test 1)
;; ===========================================================================

(deftest control-overrides-update-effective-args
  (testing "control-overrides deep-merge on top of the plan effective args"
    (let [body {:component :view.button/primary :args {:label "Go" :size :md}}
          r    (prepare :story.button/primary {:story.button/primary body}
                        nil {:control-overrides {:label "Stop"}})]
      (is (= :prepared (:status r)))
      (testing "the override wins; un-overridden args persist (deep-merge)"
        (is (= {:label "Stop" :size :md} (:effective-args r))))
      (testing "the post-override args feed the render inputs"
        (is (= {:label "Stop" :size :md}
               (get-in r [:render-inputs :effective-args])))))))

(deftest control-overrides-perturb-plan-hash
  (testing "a control override that changes :effective-args perturbs the
            plan-hash — render tracks what is actually rendered"
    (let [body {:component :view.button/primary :args {:label "Go"}}
          lk   {:story.button/primary body}
          base (prepare :story.button/primary lk)
          ovr  (prepare :story.button/primary lk nil {:control-overrides {:label "Stop"}})]
      (is (not= (:plan-hash base) (:plan-hash ovr)))
      (testing "an override equal to the plan value does NOT change the hash"
        (let [same (prepare :story.button/primary lk nil
                            {:control-overrides {:label "Go"}})]
          (is (= (:plan-hash base) (:plan-hash same))))))))

;; ===========================================================================
;; View-arg schema failures stop render before an invalid view call (§B10 test 2)
;; ===========================================================================

(def ^:private button-view-meta
  "A registered-view metadata map carrying a props schema (the `:rf/props`
  spec-named key) — a required `:label` string + an optional `:size`."
  {:rf/props [:map
              [:label :string]
              [:size {:optional true} :keyword]]})

(deftest plan-time-missing-required-arg-fails-construction
  (testing "a required view input absent at PLAN time fails plan
            construction (the compile-time floor — render never starts)"
    ;; No :args at all → :label missing → variant-plan throws.
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"story-view-args-invalid"
          (prepare :story.button/bad
                   {:story.button/bad {:component :view.button/primary}}
                   {:view.button/primary button-view-meta})))))

(deftest control-override-that-violates-schema-is-invalid-args
  (testing "a control that drives a MISSING required arg is :invalid-args —
            render stops before the view call (post-control re-validation)"
    ;; The plan is valid (:label present); a control override nils it out,
    ;; so the POST-override effective args drop the required key.
    (let [body {:component :view.button/primary :args {:label "Go"}}
          r    (render/prepare-render
                 :story.button/primary
                 {:lookup      {:story.button/primary body}
                  :view-lookup {:view.button/primary button-view-meta}
                  :validator-fns malli-validator
                  ;; Override :label to a non-string — malformed value.
                  :control-overrides {:label 42}})]
      (is (= :invalid-args (:status r)))
      (is (= :invalid (get-in r [:validation :status])))
      (is (= [:label] (-> r :validation :malformed first :path)))
      (testing "no :render-inputs are produced (render stopped pre-view)"
        (is (not (contains? r :render-inputs)))))))

(deftest valid-effective-args-prepare-with-ok-validation
  (testing "valid post-override args carry an :ok validation outcome"
    (let [body {:component :view.button/primary :args {:label "Go"}}
          r    (render/prepare-render
                 :story.button/primary
                 {:lookup      {:story.button/primary body}
                  :view-lookup {:view.button/primary button-view-meta}
                  :validator-fns malli-validator})]
      (is (= :prepared (:status r)))
      (is (= :ok (get-in r [:validation :status]))))))

;; ===========================================================================
;; render-variant returns the documented shape + does NOT run script/expect
;; (§B10 test 3)
;; ===========================================================================

(deftest render-variant-renders-via-host-hook
  (testing "with a host hook installed, render-variant returns :rendered +
            the documented slots, and the host saw the prepared render inputs"
    (let [seen (atom nil)]
      (render/install-render-host!
        (fn [inputs] (reset! seen inputs) [:fake-rendered (:view inputs)]))
      (let [body {:component :view.button/primary :args {:label "Go"}}
            r    (render/render-variant
                   :story.button/primary
                   {:lookup {:story.button/primary body}})]
        (is (= :rendered (:status r)))
        (is (= :story.button/primary (:frame r)))
        (is (= {:label "Go"} (:effective-args r)))
        (is (string? (:plan-hash r)))
        (is (= [:fake-rendered :view.button/primary] (:rendered r)))
        (testing "the host received the render inputs, NOT a test run"
          (is (= :view.button/primary (:view @seen)))
          (is (= {:label "Go"} (:effective-args @seen))))))))

(deftest render-variant-does-not-run-script-or-expect
  (testing "render-variant prepares world + renders the view; it NEVER
            dispatches the :script or evaluates terminal :expect"
    (let [dispatched (atom [])]
      (render/install-render-host!
        (fn [inputs]
          ;; A correct host renders the view; it must not be handed (and
          ;; must not run) the plan's :script / :expect.
          (is (not (contains? inputs :script)))
          [:rendered]))
      (let [body {:component  :view.button/primary
                  :args       {:label "Go"}
                  :script     [[:dispatch [:should/not-run]]]
                  :assertions [[:rf.assert/path-equals [:x] 1]]}
            r    (render/render-variant
                   :story.button/primary
                   {:lookup {:story.button/primary body}})]
        (is (= :rendered (:status r)))
        (testing "the plan still carries the script/expect (visible, not run)"
          (is (= [[:dispatch [:should/not-run]]] (get-in r [:plan :script])))
          (is (= [[:rf.assert/path-equals [:x] 1]]
                 (get-in r [:plan :expect :assertions]))))
        (testing "nothing was dispatched (render is not a run)"
          (is (= [] @dispatched)))))))

(deftest render-variant-no-host-is-cannot-run
  (testing "without a host render hook (the bare JVM), render-variant
            returns :cannot-run — never a silent empty render"
    (let [body {:component :view.button/primary :args {:label "Go"}}
          r    (render/render-variant
                 :story.button/primary
                 {:lookup {:story.button/primary body}})]
      (is (= :cannot-run (:status r)))
      (is (= #{:hiccup-structure} (:required-runner r)))
      (is (= #{} (:available-runner r)))
      (is (= :no-render-host (:reason r)))
      (testing "the prepared plan + hash still ride the refusal"
        (is (string? (:plan-hash r)))
        (is (= :story.button/primary (:frame r)))))))

(deftest render-variant-invalid-args-skips-host
  (testing "an :invalid-args result is returned BEFORE the host hook runs"
    (let [called (atom false)]
      (render/install-render-host! (fn [_] (reset! called true) [:rendered]))
      (let [body {:component :view.button/primary :args {:label "Go"}}
            r    (render/render-variant
                   :story.button/primary
                   {:lookup      {:story.button/primary body}
                    :view-lookup {:view.button/primary button-view-meta}
                    :validator-fns malli-validator
                    :control-overrides {:label 42}})]
        (is (= :invalid-args (:status r)))
        (is (false? @called))
        (testing "the documented :invalid-args slots are present"
          (is (= :story.button/primary (:frame r)))
          (is (= :invalid (get-in r [:validation :status]))))))))

(deftest render-variant-host-throw-is-error
  (testing "a throw from the host render fn is projected to :error"
    (render/install-render-host!
      (fn [_] (throw (ex-info "boom" {:rf.error/id :test/boom}))))
    (let [body {:component :view.button/primary :args {:label "Go"}}
          r    (render/render-variant
                 :story.button/primary
                 {:lookup {:story.button/primary body}})]
      (is (= :error (:status r)))
      (is (= :test/boom (get-in r [:error :data :rf.error/id]))))))

(deftest render-variant-host-throw-carries-prepared-slots
  ;; rf2-jh42p — on the host-render-throw path, prepare-render already
  ;; produced :plan / :plan-hash / :effective-args, so the :error result
  ;; MUST thread them onto the documented shape rather than dropping them.
  ;; Pre-fix these slots are absent (this test fails); post-fix they ride.
  (testing "a host-render throw projects to :error WITH the prepared plan
            context (spec/017 §Args — :plan/:plan-hash/:effective-args)"
    (render/install-render-host!
      (fn [_] (throw (ex-info "boom" {:rf.error/id :test/boom}))))
    (let [body {:component :view.button/primary :args {:label "Go"}}
          r    (render/render-variant
                 :story.button/primary
                 {:lookup {:story.button/primary body}})]
      (is (= :error (:status r)))
      (is (= :story.button/primary (:frame r)))
      (is (= {:label "Go"} (:effective-args r)))
      (is (string? (:plan-hash r)))
      (is (map? (:plan r)))
      (testing "the threaded :plan-hash matches the prepared plan's hash"
        (let [prep (render/prepare-render
                     :story.button/primary
                     {:lookup {:story.button/primary body}})]
          (is (= (:plan-hash prep) (:plan-hash r))))))))

(deftest render-variant-unknown-variant-is-error
  (testing "an unknown keyword target throws in the compiler → :error"
    (let [r (render/render-variant :story.nope/missing {:lookup {}})]
      (is (= :error (:status r)))
      (is (= :rf.error/story-unknown-variant
             (get-in r [:error :data :rf.error/id])))
      (testing "plan-CONSTRUCTION throw carries no plan slots (none prepared)"
        ;; The frame-free shape (spec/017): plan construction threw before a
        ;; plan/effective-args existed, so only :frame + :error ride.
        (is (not (contains? r :plan)))
        (is (not (contains? r :plan-hash)))
        (is (not (contains? r :effective-args)))))))

;; ===========================================================================
;; Inline-plan map target (§B10 — render-variant for BOTH registered +
;; inline plans)
;; ===========================================================================

(deftest render-variant-accepts-inline-plan-map
  (testing "a map target is an inline plan — rendered the same as a
            registered keyword (no registration needed)"
    (render/install-render-host! (fn [inputs] [:rendered (:view inputs)]))
    (let [r (render/render-variant
              {:variant/id :story.inline/v
               :component  :view.button/primary
               :args       {:label "Inline"}})]
      (is (= :rendered (:status r)))
      (is (= :story.inline/v (:frame r)))
      (is (= {:label "Inline"} (:effective-args r)))
      (is (= [:rendered :view.button/primary] (:rendered r))))))

;; ===========================================================================
;; Runner ↔ render-variant agree on :plan-hash where inputs match
;; (§B10 test 4)
;; ===========================================================================

(deftest render-variant-plan-hash-matches-the-runner-plan-hash
  (testing "render-variant's :plan-hash == fingerprint/plan-hash over the
            normalized plan a runner consumes (behaviour-relevant inputs match)"
    (let [body {:component :view.button/primary
                :args      {:label "Go"}
                :setup     [[:dispatch [:counter/init 5]]]
                :script    [[:dispatch [:counter/inc]]]}
          lk   {:story.button/primary body}
          ;; the plan the RUNNER would compile + hash
          runner-plan (plan/variant-plan :story.button/primary {:lookup lk})
          runner-hash (fingerprint/plan-hash runner-plan)
          ;; the plan-hash render-variant reports (no control overrides →
          ;; the effective args match the runner's resolved args)
          render-prep (prepare :story.button/primary lk)]
      (is (= runner-hash (:plan-hash render-prep)))
      (testing "the runner's plan still carries the (un-run) script/expect"
        (is (= [[:dispatch [:counter/inc]]] (:script runner-plan)))))))

;; ===========================================================================
;; Decorators are view-wrapping; fx-overrides live in :fx-overrides
;; (§B10 test 5)
;; ===========================================================================

(deftest decorators-are-view-wrapping-fx-overrides-are-separate
  (testing "decorators ride :world :decorators (view wrapping); fx overrides
            ride [:world :frame :fx-overrides] — distinct surfaces"
    (let [body {:component    :view.button/primary
                :args         {:label "Go"}
                :decorators   [[:decorator.theme/dark]]
                :fx-overrides {:http/get :stub.http/ok}}
          r    (prepare :story.button/primary {:story.button/primary body})
          plan (:plan r)]
      (is (= :prepared (:status r)))
      (testing "decorators are view-wrapping render inputs"
        (is (= [[:decorator.theme/dark]] (get-in r [:render-inputs :decorators])))
        (is (= [[:decorator.theme/dark]] (get-in plan [:world :decorators]))))
      (testing "fx-overrides are a frame slot, NOT a render-input decorator"
        (is (= {:http/get :stub.http/ok}
               (get-in plan [:world :frame :fx-overrides])))
        (is (not= (get-in plan [:world :decorators])
                  (get-in plan [:world :frame :fx-overrides])))))))

;; ===========================================================================
;; sub-overrides re-resolve against the post-control effective args
;; ===========================================================================

(deftest sub-overrides-reflect-control-overrides
  (testing "a sub-override value driven by an [:arg key] re-resolves against
            the POST-control effective args (a control drives the design state)"
    (render/install-render-host! (fn [inputs] inputs))
    (let [body {:component     :view.login/form
                :args          {:message "Invalid password"}
                :sub-overrides {[:login/state] :error
                                [:login/error] [:arg :message]}}
          r    (render/render-variant
                 :story.login/error
                 {:lookup {:story.login/error body}
                  :control-overrides {:message "Account locked"}})]
      (is (= :rendered (:status r)))
      (testing "the rendered inputs carry the control-driven override value"
        (is (= {[:login/state] :error
                [:login/error] "Account locked"}
               (get-in r [:rendered :sub-overrides])))))))
