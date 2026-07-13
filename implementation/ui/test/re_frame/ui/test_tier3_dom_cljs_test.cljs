(ns re-frame.ui.test-tier3-dom-cljs-test
  "Mounted Tier-3 contract for re-frame.ui.test: real React ownership,
  native scoped CSS queries, deterministic flush, and total teardown."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter ui/adapter
                                            :ambient-frame nil}))

(defview query-fixture []
  [:section {:data-scope "inside"}
   [:input {:data-role "field" :value "ready" :read-only true}]
   [:span "mounted"]])

(defview throwing-fixture [{:keys [fail!]}]
  [:div {:title (fail!)} "never commits"])

(defview strict-query-fixture []
  (ui/raw
   (React/createElement (.-StrictMode React) nil
                        (React/createElement query-fixture nil))))

(defview observed-fixture []
  [:output {:data-role "observed"} (ui/sub [::observed-value])])

(defview strict-observed-fixture []
  (ui/raw
   (React/createElement (.-StrictMode React) nil
                        (React/createElement observed-fixture nil))))

(defn commit-probe [^js props]
  (React/useLayoutEffect
   (fn []
     (swap! (.-evidence props) update :commits inc)
     js/undefined))
  nil)

(defview fixed-point-fixture [{:keys [evidence]}]
  (let [_ (swap! evidence update :renders inc)
        n (ui/sub [::fixed-point-value])
        _ (swap! evidence update :reads inc)]
    [:output {:data-role "fixed-point"}
     (str n)
     (ui/raw (React/createElement commit-probe #js {:evidence evidence}))]))

(defn- mounted-baseline []
  {:live-roots (client/live-root-ids)
   :cells      (reactive/root-cell-count)
   :containers (.-length
                (.querySelectorAll js/document "[data-rf-ui-test-root]"))})

(defn- error-id [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest with-root-mounts-queries-and-tears-down
  (when (browser?)
    (let [outside (doto (js/document.createElement "input")
                    (.setAttribute "data-role" "field"))
          before  (client/live-root-ids)]
      (.appendChild js/document.body outside)
      (try
        (uit/with-root [root [strict-query-fixture]]
          (uit/flush!)
          (let [inside (uit/query root "[data-role='field']")]
            (is (some? inside))
            (is (not (identical? outside inside)) "query is container-scoped")
            (is (= "ready" (.-value inside)))))
        (is (= before (client/live-root-ids)) "normal exit releases the live root")
        (finally
          (.remove outside))))))

(deftest with-root-preserves-body-failure-and-still-tears-down
  (when (browser?)
    (let [before (mounted-baseline)
          ex     (try
                   (uit/with-root [_root [query-fixture]]
                     (throw (ex-info "body failed" {:kind ::body-failed})))
                   nil
                   (catch cljs.core/ExceptionInfo e e))]
      (is (= ::body-failed (:kind (ex-data ex))))
      (is (= before (mounted-baseline))
          "exception exit restores root, ViewCell, and DOM-container baselines"))))

(deftest nested-with-root-restores-each-ownership-scope
  (when (browser?)
    (let [before (mounted-baseline)]
      (uit/with-root [outer [query-fixture]]
        (let [outer-state (mounted-baseline)]
          (is (= (inc (count (:live-roots before)))
                 (count (:live-roots outer-state))))
          (is (= (inc (:containers before)) (:containers outer-state)))
          (is (= "mounted" (.-textContent (uit/query outer "span"))))
          (uit/with-root [inner [query-fixture]]
            (is (= (+ 2 (count (:live-roots before)))
                   (count (:live-roots (mounted-baseline)))))
            (is (= (+ 2 (:containers before))
                   (:containers (mounted-baseline))))
            (is (= "mounted" (.-textContent (uit/query inner "span")))))
          (is (= outer-state (mounted-baseline))
              "inner teardown leaves the outer ownership scope intact")))
      (is (= before (mounted-baseline))
          "outer teardown restores every mounted ownership baseline"))))

(deftest with-root-tears-down-a-root-whose-first-render-fails
  (when (browser?)
    (let [before (mounted-baseline)
          ex     (try
                   (uit/with-root [_root [throwing-fixture
                                          {:fail! (ui/raw-fn
                                                   #(throw
                                                     (ex-info "render failed"
                                                              {:kind ::render-failed})))}]]
                     (is false "the body is unreachable"))
                   nil
                   (catch cljs.core/ExceptionInfo e e))]
      (is (= ::render-failed (:kind (ex-data ex))))
      (is (= before (mounted-baseline))
          "a failed first render restores every mounted ownership baseline"))))

(deftest with-root-preserves-flush-failure-and-still-tears-down
  (when (browser?)
    (let [before (mounted-baseline)
          ex     (try
                   (with-redefs [reactive/flush-pending!
                                 #(throw (ex-info "flush failed"
                                                  {:kind ::flush-failed}))]
                     (uit/with-root [_root [query-fixture]]
                       (uit/flush!)))
                   nil
                   (catch cljs.core/ExceptionInfo e e))]
      (is (= ::flush-failed (:kind (ex-data ex))))
      (is (= before (mounted-baseline))
          "flush failure cannot strand roots, cells, or DOM containers"))))

(deftest with-root-strictmode-teardown-releases-cells-and-observation-owners
  (when (browser?)
    (rf/reg-sub ::observed-value (fn [db _] (:value db)))
    (let [f            (uit/frame {:app-db {:value "owned"}})
          frame-id     (frame/frame-target->id f)
          cell-baseline (reactive/root-cell-count)]
      (try
        (uit/with-root [root [ui/frame-provider {:frame f}
                              [strict-observed-fixture]]]
          (uit/flush!)
          (is (= "owned" (.-textContent
                           (uit/query root "[data-role='observed']"))))
          (is (> (reactive/root-cell-count) cell-baseline)
              "the committed StrictMode tree owns a ViewCell")
          (is (some? (get @(:sub-cache (frame/frame frame-id))
                          [::observed-value]))
              "the committed view owns the real observation node"))
        (is (= cell-baseline (reactive/root-cell-count))
            "root teardown drops the StrictMode ViewCell incarnation")
        (is (nil? (get @(:sub-cache (frame/frame frame-id))
                       [::observed-value]))
            "final release disposes the observation node")
        (finally
          (rf/destroy-frame! f))))))

(deftest query-rejects-the-wrong-tier-and-non-css-input
  (when (browser?)
    (uit/with-root [root [query-fixture]]
      (is (= :rf.error/ui-test-bad-selector
             (error-id #(uit/query root :input))))
      (is (= :rf.error/ui-test-tier-mismatch
             (error-id #(uit/query {:tag :div :children []} "div")))))))

(deftest flush-rejects-an-open-event-drain
  (when (browser?)
    (rf/reg-event ::flush-during-handler
                  (fn [{:keys [db]} _]
                    {:db (assoc db :flush-error
                                (error-id uit/flush!))}))
    (let [f (uit/frame {:app-db {}})]
      (try
        (uit/dispatch! f [::flush-during-handler])
        (is (= :rf.error/flush-in-open-epoch
               (:flush-error (rf/app-db-value f))))
        (finally
          (rf/destroy-frame! f))))))

(deftest flush-rejects-a-run-after-its-frame-destroys-itself
  (when (browser?)
    (let [target (volatile! nil)
          seen   (volatile! nil)]
      (rf/reg-event ::destroy-self-then-flush
                    (fn [_ _]
                      (rf/destroy-frame! @target)
                      (vreset! seen (error-id uit/flush!))
                      nil))
      (let [f (uit/frame {:app-db {}})]
        (vreset! target f)
        (uit/dispatch! f [::destroy-self-then-flush])
        (is (= :rf.error/flush-in-open-epoch @seen)
            "the current run remains open after its frame leaves the registry")))))

(deftest mounted-dispatch-and-flush-settle-one-read-render-commit
  (when (browser?)
    (rf/reg-sub ::fixed-point-value (fn [db _] (:n db)))
    (rf/reg-event ::fixed-point-step
                  (fn [{:keys [db]} [_ remaining]]
                    (cond-> {:db (update db :n inc)}
                      (pos? remaining)
                      (assoc :fx [[:dispatch [::fixed-point-step
                                              (dec remaining)]]]))))
    (let [f        (uit/frame {:app-db {:n 0}})
          evidence (atom {:reads 0 :renders 0 :commits 0})]
      (try
        (uit/with-root [root [ui/frame-provider {:frame f}
                              [fixed-point-fixture {:evidence evidence}]]]
          (uit/flush!)
          (reset! evidence {:reads 0 :renders 0 :commits 0})

          ;; Eight queued write-side events settle synchronously. The mounted
          ;; read side is still untouched until the one explicit test flush.
          (uit/dispatch! f [::fixed-point-step 7])
          (is (= 8 (:n (rf/app-db-value f))))
          (is (= "0" (.-textContent
                       (uit/query root "[data-role='fixed-point']"))))
          (is (pos? (reactive/pending-cell-count))
              "the observed ViewCell is dirty before the forced read batch")
          (is (= {:reads 0 :renders 0 :commits 0} @evidence))

          (uit/flush!)
          (is (= "8" (.-textContent
                       (uit/query root "[data-role='fixed-point']"))))
          (is (= {:reads 1 :renders 1 :commits 1} @evidence)
              "one settled write drain produces one read, render, and commit")
          (is (zero? (reactive/pending-cell-count))))
        (finally
          (rf/destroy-frame! f))))))
