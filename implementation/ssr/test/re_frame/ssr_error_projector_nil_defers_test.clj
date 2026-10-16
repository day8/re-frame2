(ns re-frame.ssr-error-projector-nil-defers-test
  "A custom error projector that returns `nil` defers: `project-error`
  applies `default-error-projector-fn` to the same trace event and records
  nothing, so an app projector handles its own cases and keeps the default's
  gated 404 and 400 arms for the rest (Spec 011 §Server error projection).
  A projector that throws, or returns a non-nil wrong shape, still takes the
  locked 500 with an `:rf.error/sanitised-on-projection` record.

  Every assertion holds in both postures: the captures read the always-on
  axis as well as the dev trace, so the namespace means the same thing under
  `clojure -M:test:prod-gate`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private locked-500
  {:status 500 :code :internal-error :message "Something went wrong" :retryable? false})

(def ^:private not-found
  {:status 404 :code :not-found :message "Page not found" :retryable? false})

(def ^:private bad-request
  {:status 400 :code :bad-request :message "Invalid input" :retryable? false})

(def ^:private unavailable
  {:status 503 :code :unavailable :message "Try again shortly." :retryable? true})

(defn- handler-exceptions-only
  "An app projector with one case of its own, returning nil for the rest."
  [trace-event]
  (when (= :rf.error/handler-exception (:operation trace-event))
    unavailable))

(defn- server-frame
  ([projector-id] (server-frame projector-id false))
  ([projector-id dev-error-detail?]
   (rf.frame/make-anon-frame-record!
     {:platform :server
      :ssr      {:public-error-id projector-id :dev-error-detail? dev-error-detail?}})))

(defn- sanitised-records
  "Run `body-fn`; return `[result n]`, `n` counting the
  `:rf.error/sanitised-on-projection` emissions on both axes."
  [body-fn]
  (let [n   (atom 0)
        tag (keyword (str *ns*) (name (gensym "capture")))
        hit (fn [op] (when (= :rf.error/sanitised-on-projection op) (swap! n inc)))]
    (rf/register-listener! :trace tag #(hit (:operation %)))
    (rf.error-emit/register-error-listener! tag #(hit (:error %)))
    (try
      [(body-fn) @n]
      (finally
        (rf/unregister-listener! :trace tag)
        (rf.error-emit/unregister-error-listener! tag)))))

(deftest a-nil-projection-is-answered-by-the-default-projector
  (rf/reg-error-projector :app/public-error handler-exceptions-only)
  (doseq [[trace-event expected]
          [[{:operation :rf.error/handler-exception :tags {}} unavailable]
           [{:operation :rf.error/no-such-handler :tags {:kind :route}} not-found]
           [{:operation :rf.error/no-such-route :tags {}} not-found]
           [{:operation :rf.error/cofx-value-invalid :tags {}} bad-request]
           [{:operation :rf.error/schema-validation-failure :tags {:where :event}} bad-request]
           ;; the default's gates still hold: an unregistered event is a 500
           [{:operation :rf.error/no-such-handler :tags {:kind :event}} locked-500]]]
    (is (= [expected 0]
           (sanitised-records
             #(rf.ssr/project-error (server-frame :app/public-error) trace-event)))
        (pr-str (:operation trace-event) (:tags trace-event)))))

(deftest dev-error-detail-rides-the-deferred-projection
  (rf/reg-error-projector :app/public-error handler-exceptions-only)
  (let [trace-event {:operation :rf.error/cofx-value-invalid :tags {:frame :f}}]
    (is (= (assoc bad-request :details trace-event)
           (rf.ssr/project-error (server-frame :app/public-error true) trace-event)))))

(deftest a-request-settles-at-the-default-status-when-the-projector-defers
  (rf/reg-error-projector :app/public-error handler-exceptions-only)
  (rf/reg-route :route/home {} "/")
  (rf/reg-event :api/ingest
    {:schema [:cat [:= :api/ingest] [:map [:qty :int]]] :boundary? true}
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :ingested payload)}))
  (rf/reg-event :load/article
    (fn [_ _] (throw (ex-info "database down" {}))))
  (let [settle! (fn [& events]
                  (let [f (server-frame :app/public-error)]
                    (sanitised-records
                      (fn []
                        (doseq [ev events] (rf/dispatch-sync ev {:frame f}))
                        (let [{:keys [response public-error]} (rf.ssr/flush-response-result! f)]
                          [(:status response) public-error])))))]
    (testing "a route miss settles at the default's 404"
      (is (= [[404 not-found] 0]
             (settle! [:rf.route/handle-url-change "/no-such-page"]))))
    (testing "a refused `:where :event` payload settles at the default's 400"
      (is (= [[400 bad-request] 0]
             (settle! [:api/ingest {:qty "not-an-int"}]))))
    (testing "the highest projected status still wins across own and deferred answers"
      (is (= [[503 unavailable] 0]
             (settle! [:load/article] [:rf.route/handle-url-change "/no-such-page"]))))))

(deftest a-throw-or-a-wrong-shape-still-takes-the-locked-500
  (testing "on a route miss the default would answer 404, so the 500 here is
            the fallback, not a deferral"
    (doseq [[projector-id projector-fn]
            [[:app/throws     (fn [_] (throw (ex-info "projector bug" {})))]
             [:app/bad-shape  (fn [_] {:wrong :shape})]
             [:app/false      (fn [_] false)]]]
      (rf/reg-error-projector projector-id projector-fn)
      (let [[public n] (sanitised-records
                         #(rf.ssr/project-error (server-frame projector-id)
                                                {:operation :rf.error/no-such-handler
                                                 :tags      {:kind :route}}))]
        (is (= locked-500 public) (str projector-id))
        (is (pos? n) (str projector-id))))))
