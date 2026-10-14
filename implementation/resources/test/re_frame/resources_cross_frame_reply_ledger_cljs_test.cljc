(ns re-frame.resources-cross-frame-reply-ledger-cljs-test
  "A reply stamped for one frame and delivered into another settles nothing in
  the receiving frame (Spec 016 §Transport — stale suppression;
  `re-frame.resources.reply-handlers/live-slot-for-reply`). Two frames can hold
  the same work-id — a work-id carries no frame, and its generation is a
  recorded allocation value — so the receiving frame's slot, ledger row and
  host handle under that work-id are its OWN live attempt, and a misrouted
  reply must leave all three as they were.

  Each case runs one family's reply handler — the scalar resource reply, the
  infinite-feed page reply and the mutation reply, each on success and on
  failure — with both frames issuing on the same recorded allocation, then
  delivers frame A's reply into frame B and, as the control, into frame A."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   [re-frame.resources]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the fetch itself is overridden by the capturing stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; ---- the three issuing families ---------------------------------------------

(def ^:private same-allocation
  "One recorded allocation handed to both frames, so both mint the same
  generation and therefore the same work-id."
  {:rf.cofx {:rf.resource/generation-allocation {:generation 1 :counter 1}}})

(defn- issue-in! [frame-id event]
  (rf/dispatch-sync event (assoc same-allocation :frame frame-id)))

(defn- article-family []
  (rf/reg-resource :xr/article
                   {:scope :rf.scope/global :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  {:issue     [:rf.resource/ensure {:resource :xr/article :scope :rf.scope/global
                                    :params {:slug "w"} :owner [:app :xr 1]}]
   :slot-path (rf.resources.state/entry-path
                (rf.resources.state/scoped-resource-key :rf.scope/global :xr/article {:slug "w"}))})

(defn- feed-family []
  (rf/reg-resource :xr/feed
                   {:scope           :rf.scope/global
                    :infinite        true
                    :params-schema   [:map [:filter :keyword]]
                    :next-page-param (fn [last-page _] (:next last-page))
                    :page->items     :items}
                   (fn [_ {:rf.resource/keys [page-index]}]
                     {:request {:method :get :url "/feed" :params {:page-index page-index}}}))
  {:issue     [:rf.resource/ensure {:resource :xr/feed :scope :rf.scope/global
                                    :params {:filter :recent} :owner [:app :xr 1]}]
   :slot-path (rf.resources.state/entry-path
                (rf.resources.state/scoped-resource-key :rf.scope/global :xr/feed {:filter :recent}))})

(defn- save-family []
  (rf/reg-mutation :xr/save
                   {:params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  {:issue     [:rf.mutation/execute {:mutation :xr/save :params {:slug "w"} :instance :xr/save-1}]
   :slot-path (rf.resources.mutation-runtime/instance-path :xr/save-1)})

(def ^:private outcomes
  {:success {:reply-key :on-success :result {:status :ok :value {:items [1] :next nil}}}
   :failure {:reply-key :on-failure :result {:status :error :error {:kind :rf.http/http-5xx :status 503}}}})

;; ---- the check ----------------------------------------------------------------

(defn- held-by
  "What `frame-id` holds for `work-id`: the durable slot at `slot-path`, the
  ledger row, and the host handle."
  [frame-id slot-path work-id]
  (let [rdb (:rf.db/runtime (rf/frame-state-value frame-id))]
    {:slot   (get-in rdb slot-path)
     :row    (rf.resources.work-ledger/get-record rdb work-id)
     :handle (rf.resources.work-ledger/get-handle frame-id work-id)}))

(defn- misrouted-reply-leaves-receiving-frame-untouched!
  [family-fn outcome]
  (let [{:keys [issue slot-path]}  (family-fn)
        {:keys [reply-key result]} (outcomes outcome)
        fa                         :xr/frame-a
        fb                         :xr/frame-b]
    (rf/make-frame {:id fa :doc "issuing frame"})
    (rf/make-frame {:id fb :doc "receiving frame"})
    (issue-in! fa issue)
    (let [reply (conj (get @last-managed-args reply-key) result)
          wid   (:work/id (second reply))]
      (issue-in! fb issue)
      (let [before (held-by fb slot-path wid)]
        (is (= [wid true :running]
               [(:current-work (:slot before)) (some? (:handle before)) (:status (:row before))])
            "FIXTURE — frame B's own live attempt holds frame A's work-id")
        (rf/dispatch-sync reply {:frame fb})
        (is (= before (held-by fb slot-path wid))
            "frame A's reply delivered into frame B leaves B's slot, ledger row and handle as they were"))
      (rf/dispatch-sync reply {:frame fa})
      (let [settled (held-by fa slot-path wid)]
        (is (= [nil nil] [(:current-work (:slot settled)) (:handle settled)])
            "CONTROL — the same reply delivered into frame A settles A's attempt and clears its handle")))
    (rf.frame/destroy-frame! fa)
    (rf.frame/destroy-frame! fb)))

(deftest cross-frame-resource-reply-settles-nothing-in-receiving-frame
  (doseq [outcome [:success :failure]]
    (testing outcome
      (misrouted-reply-leaves-receiving-frame-untouched! article-family outcome))))

(deftest cross-frame-page-reply-settles-nothing-in-receiving-frame
  (doseq [outcome [:success :failure]]
    (testing outcome
      (misrouted-reply-leaves-receiving-frame-untouched! feed-family outcome))))

(deftest cross-frame-mutation-reply-settles-nothing-in-receiving-frame
  (doseq [outcome [:success :failure]]
    (testing outcome
      (misrouted-reply-leaves-receiving-frame-untouched! save-family outcome))))
