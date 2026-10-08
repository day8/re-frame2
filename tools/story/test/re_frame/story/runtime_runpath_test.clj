(ns re-frame.story.runtime-runpath-test
  "End-to-end run-path wiring, driven through `rf.story/run` on the JVM
  (the future resolves synchronously): requirement selection and
  validation, the browser-tier assertion executors, run-opts threading
  into plan compilation, and the unified verdict's error and failure
  routes. The unit coverage of each piece lives with that piece; this
  suite proves the wiring through the run path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story     :as rf.story]
            [re-frame.story.late-bind  :as rf.story.late-bind]
            [re-frame.story.play.browser :as rf.story.play.browser]))

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  ;; A stray :render-hiccup host would make the no-host :cannot-run case pass.
  (swap! rf.story.late-bind/hooks dissoc :render-hiccup)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :rp/set-status (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
  (rf/reg-event :rp/set-value  (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target
  ([target] (.get ^java.util.concurrent.CompletableFuture (rf.story/run target)))
  ([target opts] (.get ^java.util.concurrent.CompletableFuture (rf.story/run target opts))))

(defn- record [result assertion-id]
  (first (filter #(= assertion-id (:assertion %)) (:assertions result))))

;; ---- requirements selection / validation ---------------------------------

(deftest unmet-requirement-surfaces-cannot-run
  (testing "a terminal :rf.assert/visual-snapshot (requires :pixels) under the
            default :headless runner is :cannot-run, never a false pass"
    (let [result (run-target {:setup      [[:dispatch [:rp/set-status :ready]]]
                              :assertions [[:rf.assert/visual-snapshot]]})]
      (is (= :cannot-run (:status result)))
      (is (some #(contains? (set (:missing %)) :pixels) (:cannot-run result))
          "a refusal attributes the missing :pixels token")
      (is (= :headless (:runner result)))
      (is (contains? (set (:required-runner result)) :pixels)))))

(deftest auto-selects-cheapest-capable-runner
  (let [headless (run-target {:script [[:dispatch [:rp/set-status :loaded]]
                                       [:assert [:rf.assert/path-equals [:status] :loaded]]]}
                             {:runner :auto})]
    (is (= :headless (:runner headless))
        "an app-db-only plan escalates no further than :headless under :auto"))
  (rf.story.late-bind/set-fn! :render-hiccup (fn [_frame] [:div "ok"]))
  (let [hiccup (run-target {:assertions [[:rf.assert/a11y-structural]]}
                           {:runner :auto})]
    (is (= :hiccup (:runner hiccup))
        "a :hiccup-structure requirement escalates to the cheapest capable runner :hiccup")
    (is (contains? (set (:required-runner hiccup)) :hiccup-structure))))

;; ---- browser-tier executors routed into the run path ---------------------

(deftest a11y-structural-evaluates-at-hiccup
  (doseq [[label tree status passed?]
          [["an :img missing :alt fails the run (never a no-op skip)"
            [:div [:img {:src "/k.png"}]] :fail false]
           ["a structurally-clean tree passes"
            [:div [:img {:src "/k.png" :alt "a kitten"}] [:button "Go"]] :pass true]]]
    (testing label
      (rf.story.late-bind/set-fn! :render-hiccup (fn [_frame] tree))
      (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                         [:assert [:rf.assert/a11y-structural]]]}
                               {:runner :hiccup})
            rec    (record result :rf.assert/a11y-structural)]
        (is (= [status passed? status] [(:status result) (:passed? rec) (:status rec)]))))))

(deftest a11y-structural-cannot-run-without-a-hiccup-tree
  ;; The :hiccup runner passes the preflight capability check; with no
  ;; :render-hiccup host the executor's own tree guard refuses.
  (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                     [:assert [:rf.assert/a11y-structural]]]}
                           {:runner :hiccup})]
    (is (= :cannot-run (:status result)))
    (is (= {:status :cannot-run :cannot-run? true :passed? false}
           (select-keys (record result :rf.assert/a11y-structural)
                        [:status :cannot-run? :passed?])))))

(deftest visual-snapshot-cannot-run-without-a-real-browser
  ;; :browser passes preflight for :pixels; the JVM has no real browser, so
  ;; the executor refuses.
  (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                     [:assert [:rf.assert/visual-snapshot]]]}
                           {:runner :browser})]
    (is (= :cannot-run (:status result)))
    (is (= :cannot-run (:status (record result :rf.assert/visual-snapshot))))))

(deftest browser-rows-without-their-evidence-cannot-run-in-a-browser
  (testing "with a browser available, an a11y checkpoint on a frame axe never
            scanned and a visual-snapshot with no captured pixels each record
            :cannot-run naming the missing evidence"
    (let [saved @rf.story.play.browser/a11y-reader]
      (try
        (reset! rf.story.play.browser/a11y-reader (fn [_frame-id] nil))
        (with-redefs [rf.story.play.browser/browser-available? (constantly true)]
          (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                             [:assert [:rf.assert/a11y]]
                                             [:assert [:rf.assert/visual-snapshot]]]}
                                   {:runner :browser})
                row    #(select-keys (record result %) [:status :missing-evidence])]
            (is (= {:status :cannot-run :missing-evidence #{:a11y}} (row :rf.assert/a11y)))
            (is (= {:status :cannot-run :missing-evidence #{:pixels}} (row :rf.assert/visual-snapshot)))
            (is (= :cannot-run (:status result)))))
        (finally (reset! rf.story.play.browser/a11y-reader saved))))))

;; ---- run opts thread into plan compilation -------------------------------
;;
;; The executed `[:arg …]` substitution and the reported `:effective-args`
;; must come from the same layers, or a cell override or active mode would
;; run a different scenario than the result claims.

(deftest run-opts-thread-into-arg-substitution
  (rf.story/reg-mode :Mode.test/m {:args {:value "from-mode"}})
  (doseq [[vid args opts expected]
          [[:story.opts/moded nil {:active-modes [:Mode.test/m]} "from-mode"]
           [:story.opts/precedence {:value "static"}
            {:active-modes [:Mode.test/m] :cell-overrides {:value "override"}} "override"]]]
    (rf.story/reg-variant vid (cond-> {:script [[:dispatch-sync [:rp/set-value [:arg :value]]]]}
                                args (assoc :args args)))
    (let [result (run-target vid opts)]
      (is (= [expected expected]
             [(get-in result [:app-db :value]) (get-in result [:effective-args :value])])
          (str vid ": executed == reported")))))

;; ---- error and failure routes into the unified verdict -------------------

(deftest plan-error-after-prior-ready-run-reports-structured-error-not-stale-db
  (testing "a plan-construction failure on the second run of a variant the
            first run drove to :ready reports the structured error and none
            of the prior run's app-db"
    (rf.story/reg-variant
      :story.stale/v
      {:script [[:dispatch-sync [:rp/set-value "old"]]
                [:assert [:rf.assert/path-equals [:value] "old"]]]})
    (let [first-result (run-target :story.stale/v)]
      (is (= [:pass "old"] [(:status first-result) (get-in first-result [:app-db :value])])))
    (rf.story/reg-variant
      :story.stale/v
      {:script [[:dispatch-sync [:rp/set-value [:arg :missing]]]]})
    (let [result (run-target :story.stale/v)]
      (is (= :error (:status result)))
      (is (= [[:rf.error/story-missing-arg false]]
             (mapv (juxt :assertion :passed?) (:assertions result)))
          "one structured record, never an opaque :rf.error/exception")
      (is (= {} (:app-db result)) "the frame-free default, never the stale {:value \"old\"}"))))

(defn- step-failed-records [result]
  (filterv #(= :rf.error/story-play-step-failed (:assertion %)) (:assertions result)))

(deftest failed-wait-until-fails-the-unified-result
  (testing "a wait-until that never holds records no assertion of its own, yet
            reads :fail on the unified result"
    (rf.story/reg-variant :story.step-fail/wait-until
      {:script [[:dispatch [:rp/set-value 1]]
                [:wait-until [:db [:value] 99]]]})
    (let [result (run-target :story.step-fail/wait-until)
          [rec]  (step-failed-records result)]
      (is (= :fail (:status result)))
      (is (= [:wait-until [:db [:value] 99]] (first (:payload rec)))
          "the record names the step that failed")
      (is (= :fail (:status rec)))))
  (testing "an assertion that fails is counted once, from its own record"
    (rf.story/reg-variant :story.step-fail/assert
      {:script [[:dispatch [:rp/set-value 1]]
                [:assert [:rf.assert/path-equals [:value] 99]]]})
    (let [result (run-target :story.step-fail/assert)]
      (is (= :fail (:status result)))
      (is (= [:rf.assert/path-equals] (mapv :assertion (:assertions result)))))))

(deftest every-executed-play-reaches-the-unified-result
  (testing "a failure in an earlier auto-play is not dropped because a later
            one passed"
    (rf.story/reg-variant :story.step-fail/two-plays
      {:plays [{:name      "waits"
                :auto-run? true
                :script    [[:wait-until [:db [:value] 99]]]}
               {:name      "passes"
                :auto-run? true
                :script    [[:dispatch [:rp/set-value 1]]
                            [:assert [:rf.assert/path-equals [:value] 1]]]}]})
    (let [result (run-target :story.step-fail/two-plays)]
      (is (= :fail (:status result)))
      (is (= 1 (count (step-failed-records result)))))))

;; An unresolved decorator ref refuses the run before any phase: a bare
;; `[:force-fx-stub …]` names no registered decorator, so without the refusal
;; the real effect would fire and the run would read :pass.

(def ^:private real-calls (atom 0))

(defn- reg-real-effect! []
  (reset! real-calls 0)
  (rf/reg-fx :rp/http-get (fn [_ _] (swap! real-calls inc)))
  (rf/reg-event :rp/fetch (fn [{:keys [db]} _]
                            {:db (assoc db :status :pending)
                             :fx [[:rp/http-get {:url "/x"}]]})))

(deftest unresolved-decorator-refuses-the-run-before-any-effect
  (testing "registered variant"
    (reg-real-effect!)
    (rf.story/reg-variant :story.decor/bare-stub
      {:decorators [[:force-fx-stub :rp/http-get {}]]
       :setup      [[:rp/fetch]]
       :script     [[:dispatch [:rp/fetch]]]})
    (let [result (run-target :story.decor/bare-stub)]
      (is (= :error (:status result)))
      (is (zero? @real-calls) "neither :setup nor the script ran")
      (is (= [:rf.error/decorator-unknown]
             (mapv :rf.error (:decorator-errors (record result :rf.error/story-decorator-unresolved)))))))
  (testing "inline plan, whose frame is never allocated"
    (reg-real-effect!)
    (let [result (run-target {:variant/id :probe/inline-bare-stub
                              :decorators [[:force-fx-stub :rp/http-get {}]]
                              :setup      [[:rp/fetch]]})]
      (is (= :error (:status result)))
      (is (zero? @real-calls))
      (is (= [:rf.error/story-decorator-unresolved] (mapv :assertion (:assertions result)))))))
