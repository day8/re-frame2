(ns re-frame.ui.test-tier3-dom-cljs-test
  "Mounted Tier-3 contract for re-frame.ui.test: real React ownership,
  native scoped CSS queries, deterministic flush, and total teardown."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
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
    (let [before (client/live-root-ids)
          ex     (try
                   (uit/with-root [_root [query-fixture]]
                     (throw (ex-info "body failed" {:kind ::body-failed})))
                   nil
                   (catch cljs.core/ExceptionInfo e e))]
      (is (= ::body-failed (:kind (ex-data ex))))
      (is (= before (client/live-root-ids)) "exception exit releases the live root"))))

(deftest with-root-tears-down-a-root-whose-first-render-fails
  (when (browser?)
    (let [before (client/live-root-ids)
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
      (is (= before (client/live-root-ids))
          "a registered root is reclaimed even when its first render throws"))))

(deftest with-root-preserves-flush-failure-and-still-tears-down
  (when (browser?)
    (let [before (client/live-root-ids)
          ex     (try
                   (with-redefs [reactive/flush-pending!
                                 #(throw (ex-info "flush failed"
                                                  {:kind ::flush-failed}))]
                     (uit/with-root [_root [query-fixture]]
                       (uit/flush!)))
                   nil
                   (catch cljs.core/ExceptionInfo e e))]
      (is (= ::flush-failed (:kind (ex-data ex))))
      (is (= before (client/live-root-ids))
          "flush failure cannot strand the mounted root"))))

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
