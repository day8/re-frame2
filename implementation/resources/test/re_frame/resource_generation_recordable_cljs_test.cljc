(ns re-frame.resource-generation-recordable-cljs-test
  "The resource and mutation generation is a durable join key, so it is minted
  by the recordable `:rf.resource/generation-allocation` cofx (Spec 016
  §Restore and replay). Replay that supplies the recorded allocation
  reproduces the generation whatever the host allocator's high-water, so a
  reply accepted at record time is accepted again; a re-minted generation
  would stale-suppress it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.trace.tooling :as rf.trace.tooling]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(def ^:private reset-fixture
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each reset-fixture capturing-transport-fixture)

(defn- with-fresh-runtime
  "Run `body` inside a fresh runtime. The fixture tears the runtime down on
  return, so the body's assertions must run inside it."
  [body]
  (reset-fixture #(capturing-transport-fixture body)))

(defn- register-resource! []
  (rf/reg-resource :gen/article
    {:scope          :rf.scope/global
     :params-schema  [:map [:slug :string]]
     :stale-after-ms 60000}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}})))

(defn- ensure!
  "Ensure :gen/article, supplying a recorded allocation on the token when given."
  [allocation]
  (rf/dispatch-sync
    [:rf.resource/ensure
     {:resource :gen/article :scope :rf.scope/global
      :params {:slug "w"} :owner [:app :gen 1]}]
    (cond-> {:frame :rf/default :rf.cofx {:rf/time-ms 1781078400100}}
      allocation (assoc-in [:rf.cofx :rf.resource/generation-allocation] allocation))))

(defn- reply-success! [on-success]
  (rf/dispatch-sync (conj on-success {:status :ok :value {:title "Welcome"}})
                    {:frame :rf/default :rf.cofx {:rf/time-ms 1781078400250}}))

(defn- live-entry []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path
            (rf.resources.state/scoped-resource-key :rf.scope/global :gen/article {:slug "w"}))))

(defn- recorded-allocations
  "Run `f`; return the generation allocations recorded on its tokens."
  [f]
  (let [seen (atom [])]
    (rf.trace.tooling/register-listener!
      ::gen-rec
      (fn [ev] (when (and (= :rf.cofx/generated (:operation ev))
                          (= :rf.resource/generation-allocation (:rf.cofx/id (:tags ev))))
                 (swap! seen conj (:rf.cofx/value (:tags ev))))))
    (try (f) (finally (rf.trace.tooling/unregister-listener! ::gen-rec)))
    @seen))

(deftest generation-allocation-is-recorded-so-replay-keeps-the-reply-current
  (register-resource!)
  (let [[allocation] (recorded-allocations #(ensure! nil))
        on-success   (:on-success @last-managed-args)]
    (testing "record: a fresh allocator mints generation 1 and the reply is accepted"
      (is (= {:generation 1 :counter 1} allocation))
      (reply-success! on-success)
      (is (= [1 :loaded {:title "Welcome"}] ((juxt :generation :status :data) (live-entry)))))
    (testing "replay supplying the recorded allocation reproduces generation 1"
      (with-fresh-runtime
        (fn []
          (register-resource!)
          (rf.resources.state/commit-generation! :rf/default 10)
          (ensure! allocation)
          (is (= 1 (:generation (live-entry))))
          (reply-success! (:on-success @last-managed-args))
          (is (= [:loaded {:title "Welcome"}] ((juxt :status :data) (live-entry)))))))
    (testing "control: without the allocation the generation re-mints and the recorded reply is stale"
      (with-fresh-runtime
        (fn []
          (register-resource!)
          (rf.resources.state/commit-generation! :rf/default 10)
          (ensure! nil)
          (is (= 11 (:generation (live-entry))))
          (reply-success! on-success)
          (is (not= :loaded (:status (live-entry)))))))))

(deftest mutation-generation-allocation-is-recorded
  (rf/reg-mutation :gen/save
    {:params-schema [:map [:slug :string]]
     :scope :rf.scope/global}
    (fn [{:keys [slug]} _] {:request {:method :post
                                      :url (str "/api/save/" slug)}}))
  (is (= [{:generation 1 :counter 1}]
         (recorded-allocations
           #(rf/dispatch-sync [:rf.mutation/execute
                               {:mutation :gen/save :params {:slug "w"}}]
                              {:frame :rf/default :rf.cofx {:rf/time-ms 1781078400100}})))))
