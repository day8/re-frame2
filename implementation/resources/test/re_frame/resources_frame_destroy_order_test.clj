(ns re-frame.resources-frame-destroy-order-test
  "Frame destroy reaches a resource read's in-flight managed request twice:
  resources releases the attempt it owns, and http sweeps every request the
  frame issued. Both run after the frame is dead and neither may reply into it,
  so the teardown is the same whichever contribution comes first on the list
  (Spec 002 §Lifecycle contributions, Spec 014 §Abort on frame destroy)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.interop :as rf.interop]
            [re-frame.resources]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- with-order
  "Run `f` with the contribution `first-id` moved directly ahead of `second-id`."
  [first-id second-id f]
  (let [saved @rf.frame/lifecycle-contributions
        by-id (into {} (map (juxt :id identity)) saved)
        rest  (remove (comp #{first-id second-id} :id) saved)]
    (is (every? by-id [first-id second-id]) "FIXTURE — both artefacts contribute")
    (reset! rf.frame/lifecycle-contributions
            (conj (vec rest) (by-id first-id) (by-id second-id)))
    (try
      (f)
      (finally
        (reset! rf.frame/lifecycle-contributions saved)))))

(defn- entry [frame-id resource]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          (rf.resources.state/entry-path
            (rf.resources.state/scoped-resource-key :rf.scope/global resource {:slug "w"}))))

(defn- destroy-with-a-read-in-flight!
  "Seat `frame-id`, start a read of `resource` that never completes, destroy
  the frame, and return what the teardown left behind."
  [frame-id resource]
  (let [errors (atom [])
        traces (atom [])
        lid    (keyword (str (gensym "destroy-order-")))]
    (rf/reg-resource resource
      {:params-schema [:map [:slug :string]] :scope :rf.scope/global}
      (fn [{:keys [slug]} _ctx]
        {:request {:method :get :url (str "http://example.invalid/articles/" slug)}
         :decode  :json}))
    (rf/make-frame {:id frame-id})
    (rf.error-emit/register-error-listener! lid (fn [r] (swap! errors conj (:rf.error/id r))))
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
    (try
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_] (CompletableFuture.))]
        (rf/dispatch-sync [:rf.resource/ensure {:resource resource :scope :rf.scope/global
                                                :params {:slug "w"} :owner [:app resource]}]
                          {:frame frame-id})
        (let [wid (:current-work (entry frame-id resource))]
          (is (= [true true]
                 [(some? (rf.resources.work-ledger/get-handle frame-id wid))
                  (boolean (seq (rf.http.registry/in-flight-snapshot frame-id)))])
              "FIXTURE — the read holds an attempt handle and a live request")
          (rf/destroy-frame! frame-id)
          {:handle   (rf.resources.work-ledger/get-handle frame-id wid)
           :requests (rf.http.registry/in-flight-snapshot frame-id)
           :errors   @errors
           :aborts   (keep #(when (= :rf.http/aborted (:operation %)) (get-in % [:tags :reason]))
                           @traces)}))
      (finally
        (rf.error-emit/unregister-error-listener! lid)
        (rf.trace.tooling/unregister-listener! lid)))))

(deftest a-resource-owned-request-tears-down-cleanly-in-either-order
  (doseq [[first-id second-id frame-id resource]
          [[:resources :http :order/resources-first :order/article-a]
           [:http :resources :order/http-first :order/article-b]]]
    (testing (str first-id " before " second-id)
      (let [left (with-order first-id second-id
                   #(destroy-with-a-read-in-flight! frame-id resource))]
        (is (= [nil true []]
               [(:handle left) (empty? (:requests left)) (:errors left)])
            "no attempt handle and no live request survive, and nothing replies into the dead frame")
        (when rf.interop/debug-enabled?
          (is (= [:frame-destroyed] (vec (:aborts left)))
              "the request is aborted once, with the frame-destroy reason"))))))
