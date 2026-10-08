(ns re-frame.story.render-cljs-test
  "Tests for `render-variant` and the workshop-superset plan slots
  (`tools/story/spec/017-Testing-Story.md` §Args, controls, and
  `render-variant`). `prepare-render` is pure data → data, so every test
  runs on the JVM and on node without a host: bodies and view metadata come
  through explicit `:lookup` / `:view-lookup` maps, and the host-render path
  runs against a fake `:render-host` hook.

  Named `-cljs-test` so the `:node-test` build selects it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.render :as rf.story.render]))

;; The late-bind hooks map also carries the canonical-vocabulary shims, so
;; drop only `:render-host` and restore the snapshot afterwards rather than
;; clearing it.
(use-fixtures :each
  (fn [t]
    (let [snapshot @rf.story.late-bind/hooks]
      (swap! rf.story.late-bind/hooks dissoc :render-host)
      (try (t) (finally (reset! rf.story.late-bind/hooks snapshot))))))

(def ^:private malli-validator
  {:validate (fn [schema value] (m/validate schema value))
   :explain  (fn [schema value] (m/explain schema value))})

(defn- prepare
  ([target lookup] (prepare target lookup nil))
  ([target lookup extra]
   (rf.story.render/prepare-render target (merge {:lookup lookup} extra))))

(def ^:private button
  {:story.button/primary {:component :view.button/primary :args {:label "Go"}}})

(def ^:private button-view-meta
  {:rf/props [:map
              [:label :string]
              [:size {:optional true} :keyword]]})

(deftest workshop-vocabulary-flows-through-the-plan
  (let [p (rf.story.plan/variant-plan
            :story.button/primary
            {:lookup {:story.button/primary
                      {:component  :view.button/primary
                       :args       {:label "Go"}
                       :argtypes   {:label {:control :text}}
                       :decorators [[:decorator.theme/dark]]
                       :modes      #{:mode/dark}
                       :substrates #{:reagent :uix}
                       :viewport   :viewport/mobile
                       :background :background/dark}}})]
    (is (= {:component      :view.button/primary
            :argtypes       {:label {:control :text}}
            :decorators     [[:decorator.theme/dark]]
            :modes          #{:mode/dark}
            :substrates     #{:reagent :uix}
            :viewport       :viewport/mobile
            :background     :background/dark
            :effective-args {:label "Go"}}
           (select-keys (:world p) [:component :argtypes :decorators :modes :substrates
                                    :viewport :background :effective-args])))))

(deftest control-overrides-update-effective-args
  (let [r (prepare :story.button/primary
                   {:story.button/primary {:component :view.button/primary
                                           :args      {:label "Go" :size :md}}}
                   {:control-overrides {:label "Stop"}})]
    (is (= {:label "Stop" :size :md} (:effective-args r))
        "the override wins; un-overridden args persist")
    (is (= {:label "Stop" :size :md} (get-in r [:render-inputs :effective-args])))))

(deftest control-overrides-perturb-plan-hash
  (let [hash #(:plan-hash (prepare :story.button/primary button
                                   {:control-overrides %}))]
    (is (not= (hash nil) (hash {:label "Stop"})))
    (is (= (hash nil) (hash {:label "Go"}))
        "an override equal to the plan value leaves the hash alone")))

(deftest valid-effective-args-prepare-with-ok-validation
  (is (= :ok (get-in (prepare :story.button/primary button
                              {:view-lookup   {:view.button/primary button-view-meta}
                               :validator-fns malli-validator})
                     [:validation :status]))))

(deftest render-variant-renders-via-host-hook
  (let [seen (atom nil)]
    (rf.story.render/install-render-host!
      (fn [inputs] (reset! seen inputs) [:fake-rendered (:view inputs)]))
    (let [r (rf.story.render/render-variant :story.button/primary {:lookup button})]
      (is (= {:status         :rendered
              :frame          :story.button/primary
              :effective-args {:label "Go"}
              :rendered       [:fake-rendered :view.button/primary]}
             (select-keys r [:status :frame :effective-args :rendered])))
      (is (string? (:plan-hash r)))
      (is (= [{:label "Go"} :story.button/primary]
             [(:effective-args @seen) (:frame @seen)])
          "the host received the prepared render inputs"))))

(deftest render-variant-does-not-run-script-or-expect
  (rf.story.render/install-render-host! (fn [_] [:rendered]))
  ;; The script's event has a counting handler and the variant's frame is
  ;; live, so a render that dispatched the script would be counted.
  (let [handled (atom 0)
        seated? (some? (rf/current-adapter))]
    (try (rf/init! rf.substrate.plain-atom/adapter)
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
    (rf/reg-event :should/not-run (fn [_ _] (swap! handled inc) {}))
    (rf/make-frame {:id :story.button/primary})
    (try
      (let [r (rf.story.render/render-variant
                :story.button/primary
                {:lookup {:story.button/primary
                          {:component  :view.button/primary
                           :args       {:label "Go"}
                           :script     [[:dispatch [:should/not-run]]]
                           :assertions [[:rf.assert/path-equals [:x] 1]]}}})]
        (is (= :rendered (:status r)))
        (testing "the plan carries the script and expect, visible but not run"
          (is (= [[:dispatch [:should/not-run]]] (get-in r [:plan :script])))
          (is (= [[:rf.assert/path-equals [:x] 1]] (get-in r [:plan :expect :assertions]))))
        (is (zero? @handled) "render is not a run")
        (testing "control: the counter sees the event dispatched on purpose"
          (rf/dispatch-sync (second (first (get-in r [:plan :script]))) {:frame (:frame r)})
          (is (= 1 @handled))))
      (finally
        (rf/destroy-frame! :story.button/primary)
        (rf/clear :event :should/not-run)
        (when-not seated? (rf/destroy-adapter!))))))

(deftest render-variant-no-host-is-cannot-run
  (let [r (rf.story.render/render-variant :story.button/primary {:lookup button})]
    (is (= {:status           :cannot-run
            :required-runner  #{:hiccup-structure}
            :available-runner #{}
            :reason           :no-render-host
            :frame            :story.button/primary}
           (select-keys r [:status :required-runner :available-runner :reason :frame])))
    (is (string? (:plan-hash r)))))

(deftest render-variant-invalid-args-skips-host
  (let [called (atom false)]
    (rf.story.render/install-render-host! (fn [_] (reset! called true) [:rendered]))
    (let [r (rf.story.render/render-variant
              :story.button/primary
              {:lookup            button
               :view-lookup       {:view.button/primary button-view-meta}
               :validator-fns     malli-validator
               :control-overrides {:label 42}})]
      (is (= [:invalid-args :story.button/primary :invalid]
             [(:status r) (:frame r) (get-in r [:validation :status])]))
      (is (false? @called) "the host never ran"))))

(deftest render-variant-host-throw-is-error-with-the-prepared-slots
  ;; The host threw after prepare-render succeeded, so the prepared plan
  ;; context rides the :error result (spec/017 §Args).
  (rf.story.render/install-render-host!
    (fn [_] (throw (ex-info "boom" {:rf.error/id :test/boom}))))
  (let [r (rf.story.render/render-variant :story.button/primary {:lookup button})]
    (is (= {:status :error :frame :story.button/primary :effective-args {:label "Go"}}
           (select-keys r [:status :frame :effective-args])))
    (is (= :test/boom (get-in r [:error :data :rf.error/id])))
    (is (string? (:plan-hash r)))
    (is (map? (:plan r)))))

(deftest render-variant-unknown-variant-is-error
  (let [r (rf.story.render/render-variant :story.nope/missing {:lookup {}})]
    (is (= :rf.error/story-unknown-variant (get-in r [:error :data :rf.error/id])))
    (is (= {:status :error :frame :story.nope/missing} (dissoc r :error))
        "plan construction threw, so no plan slots ride the result")))

(deftest render-variant-accepts-inline-plan-map
  (rf.story.render/install-render-host! (fn [inputs] [:rendered (:view inputs)]))
  (is (= {:status         :rendered
          :frame          :story.inline/v
          :effective-args {:label "Inline"}
          :rendered       [:rendered :view.button/primary]}
         (select-keys (rf.story.render/render-variant
                        {:variant/id :story.inline/v
                         :component  :view.button/primary
                         :args       {:label "Inline"}})
                      [:status :frame :effective-args :rendered]))))

(deftest decorators-are-view-wrapping-fx-overrides-are-separate
  (let [r (prepare :story.button/primary
                   {:story.button/primary {:component    :view.button/primary
                                           :args         {:label "Go"}
                                           :decorators   [[:decorator.theme/dark]]
                                           :fx-overrides {:http/get :stub.http/ok}}})]
    (is (= [[:decorator.theme/dark]] (get-in r [:render-inputs :decorators])))
    (is (= [[:decorator.theme/dark]] (get-in r [:plan :world :decorators])))
    (is (= {:http/get :stub.http/ok} (get-in r [:plan :world :frame :fx-overrides]))
        "fx-overrides are a frame slot, not a render-input decorator")))

(deftest sub-overrides-reflect-control-overrides
  (testing "a sub-override value driven by an [:arg key] re-resolves against
            the post-control effective args"
    (rf.story.render/install-render-host! (fn [inputs] inputs))
    (let [r (rf.story.render/render-variant
              :story.login/error
              {:lookup            {:story.login/error
                                   {:component     :view.login/form
                                    :args          {:message "Invalid password"}
                                    :sub-overrides {[:login/state] :error
                                                    [:login/error] [:arg :message]}}}
               :control-overrides {:message "Account locked"}})]
      (is (= {[:login/state] :error
              [:login/error] "Account locked"}
             (get-in r [:rendered :sub-overrides]))))))
