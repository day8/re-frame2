(ns linearlite.baseline-test
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.routing :as rf.routing]
            [linearlite.core]))

(def ^:private last-managed-args (atom nil))

;; Reset clears these registry kinds; each fresh frame needs the app registrations.
(def ^:private resource-kind-snapshots
  (select-keys @rf.registrar/kind->id->metadata [:resource :mutation]))

(swap! rf.registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))

(defn- init! []
  (reset! last-managed-args nil)
  (doseq [[kind id->meta] resource-kind-snapshots
          [id meta] id->meta]
    (rf.registrar/register! kind id meta))
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "linearlite default app frame."}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :init-fn init!}))

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(def ^:private board-query
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(defn- entry []
  (get-in (runtime-db)
          (rf.resources.state/entry-path
            (rf.resources.state/scoped-resource-key :rf.scope/global :linearlite/board {}))))

(defn- board-data []
  (rf/compute-sub [:rf.resource/data board-query] (rf/frame-state-value :rf/default)))

(defn- issues [] (:issues (board-data)))

(defn- issue-by-id [id] (some #(when (= id (:id %)) %) (issues)))

(defn- mutation-state [instance]
  (rf/compute-sub [:rf/mutation {:instance instance}] (rf/frame-state-value :rf/default)))

(defn- reply-success! [data]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value data})
                    {:frame :rf/default}))

(defn- reply-failure! [failure]
  (rf/dispatch-sync (conj (:on-failure @last-managed-args) {:status :error :error failure})
                    {:frame :rf/default}))

(def ^:private demo-board
  {:issues [{:id "srv-1" :title "Alpha" :status :backlog}
            {:id "srv-2" :title "Beta" :status :in-progress}]})

(defn- load-board! []
  (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
  (reply-success! demo-board)
  (reset! last-managed-args nil))

(deftest board-route-entry-ensures-the-board-under-the-route-owner
  (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
  (let [nav-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token])
        e (entry)]
    (is (= [:loading true]
           [(:status e) (contains? (:active-owners e) [:route :linearlite.app/board nav-token])]))
    (reply-success! demo-board)
    (is (= [:loaded demo-board] [(:status (entry)) (board-data)]))))

(deftest create-issue-applies-optimistically-before-the-reply
  (load-board!)
  (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
  (let [tmp (some #(when (= "Gamma" (:title %)) %) (issues))
        ms (mutation-state [:create (:id tmp)])]
    (is (= [:backlog 3 true true]
           [(:status tmp) (count (issues)) (:pending? ms) (:optimistic? ms)]))))

(deftest successful-write-commits-the-server-board-via-populates
  (load-board!)
  (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
  (let [tmp-id (:id (some #(when (= "Gamma" (:title %)) %) (issues)))
        srv-board {:issues (conj (:issues demo-board)
                                 {:id "srv-3" :title "Gamma" :status :backlog})}]
    (reply-success! srv-board)
    (is (= srv-board (board-data)))
    (is (nil? (issue-by-id tmp-id)))
    (is (= [true false] ((juxt :success? :optimistic?) (mutation-state [:create tmp-id]))))))

(deftest successful-edit-title-commits-the-new-title
  (load-board!)
  (rf/dispatch-sync [:linearlite/commit-edit "srv-2" "Beta!"])
  (is (= "Beta!" (:title (issue-by-id "srv-2"))))
  (reply-success! {:issues [{:id "srv-1" :title "Alpha" :status :backlog}
                            {:id "srv-2" :title "Beta!" :status :in-progress}]})
  (is (= ["Beta!" true]
         [(:title (issue-by-id "srv-2")) (:success? (mutation-state [:edit "srv-2"]))])))

(deftest failed-create-rolls-the-optimistic-card-back-out
  (load-board!)
  (rf/dispatch-sync [:linearlite/create-issue "Doomed"])
  (let [tmp-id (:id (some #(when (= "Doomed" (:title %)) %) (issues)))]
    (reply-failure! {:kind :rf.http/http-5xx :status 503})
    (is (= demo-board (board-data)))
    (is (= [true false] ((juxt :error? :optimistic?) (mutation-state [:create tmp-id]))))))

(deftest failed-change-status-snaps-the-card-back-to-its-prior-column
  (load-board!)
  (rf/dispatch-sync [:linearlite/change-status "srv-1" :done])
  (is (= [:done true]
         [(:status (issue-by-id "srv-1")) (:optimistic? (mutation-state [:status "srv-1"]))]))
  (reply-failure! {:kind :rf.http/http-5xx :status 503})
  (is (= demo-board (board-data)))
  (is (true? (:error? (mutation-state [:status "srv-1"])))))

(deftest failed-edit-title-reverts-to-the-prior-title
  (load-board!)
  (rf/dispatch-sync [:linearlite/commit-edit "srv-2" "Wrong"])
  (is (= "Wrong" (:title (issue-by-id "srv-2"))))
  (reply-failure! {:kind :rf.http/http-5xx :status 503})
  (is (= demo-board (board-data))))

(def ^:private parked-replies (atom []))

(defn- install-canned-parkers! []
  (doseq [fx-id [:rf.http/managed-canned-success :rf.http/managed-canned-failure]]
    (rf.fx/reg-fx fx-id
      (fn [_ctx args]
        (swap! parked-replies conj {:fx-id fx-id :after-ms (:after-ms args)
                                    :args (dissoc args :after-ms)})
        nil))))

(defn- ask-the-demo-stub! [request]
  (reset! parked-replies [])
  ((rf.registrar/handler :fx :linearlite.demo/http-stub)
   {:frame :rf/default}
   {:request request :decode :json
    :on-success [:linearlite.test/reply] :on-failure [:linearlite.test/reply]})
  @parked-replies)

(defn- change-status-request [id status]
  {:method :put :url (str "/api/issues/" id) :body {:status status}})

(defn- server-board []
  (:value (:args (first (ask-the-demo-stub! {:method :get :url "/api/board"})))))

(defn- server-issue [board id]
  (some #(when (= id (:id %)) %) (:issues board)))

(deftest armed-demo-backend-answers-a-classified-503
  (install-canned-parkers!)
  (rf/dispatch-sync [:linearlite/set-fail-next-write true])
  (let [before (server-board)
        parked (ask-the-demo-stub! (change-status-request "srv-1" :done))
        {:keys [fx-id after-ms args]} (first parked)]
    (is (= [1 :rf.http/managed-canned-failure true :rf.http/http-5xx 503]
           [(count parked) fx-id (pos? after-ms) (:kind args) (get-in args [:tags :status])]))
    (is (= before (server-board)))))

(deftest unarmed-demo-backend-answers-success-and-commits
  (install-canned-parkers!)
  (let [original (:status (server-issue (server-board) "srv-1"))
        target (if (= :done original) :backlog :done)
        parked (ask-the-demo-stub! (change-status-request "srv-1" target))
        {:keys [fx-id after-ms args]} (first parked)]
    (is (= [1 :rf.http/managed-canned-success true target]
           [(count parked) fx-id (pos? after-ms) (:status (server-issue (:value args) "srv-1"))]))
    (ask-the-demo-stub! (change-status-request "srv-1" original))))
