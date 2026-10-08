(ns re-frame.resources-frame-value-target-cljs-test
  "`rf/resource-state` and `rf/mutation-state` accept a live frame VALUE as
  their `:frame` target and read the same rows as its id (Spec 002). Without
  the normalization a value would read a live row as a silent nil. The
  managed-HTTP fx is a no-op, so every ensured row stays in flight."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(defn- init! []
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :fv/session
    {:inputs {:username [:db [:username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-resource :fv/article
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}}))
  (rf/reg-resource :fv/feed
    {:scope         {:from-db :fv/session}
     :params-schema [:map [:page :int]]
     :tags          (fn [_params _data] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-mutation :fv/save
    {:params-schema [:map [:slug :string]]
     :invalidates   (fn [{:keys [slug]} _result] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :put :url (str "/api/articles/" slug) :body {:slug slug}}}))
  (rf/reg-event :fv/login
    (fn [{:keys [db]} [_ username]] {:db (assoc db :username username)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

(defn- article [slug]
  {:resource :fv/article :scope :rf.scope/global :params {:slug slug}})

(def ^:private feed {:resource :fv/feed :params {:page 1}})

(defn- session-key [username]
  (rf.resources.state/scoped-resource-key
    [:rf.scope/session {:username username}] :fv/feed {:page 1}))

(defn- seed!
  "Log `frame` in as `username`, then ensure the article, the session feed and
  one mutation instance in it, every dispatch addressed by `frame`."
  [frame username slug instance]
  (rf/dispatch-sync [:fv/login username] {:frame frame})
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :fv/article :params {:slug slug} :owner [:app :fv 1]}]
                    {:frame frame})
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :fv/feed :params {:page 1} :owner [:app :fv 1]}]
                    {:frame frame})
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :fv/save :params {:slug slug} :instance instance}]
                    {:frame frame}))

(deftest frame-value-target-reads-the-same-rows-as-its-id
  (let [fa (rf/make-frame {:id :fv/frame-a :doc "frame-value target A"})
        fb (rf/make-frame {:id :fv/frame-b :doc "frame-value target B"})]
    (is (rf.frame/frame-value? fa) "precondition: make-frame returned a frame value")
    (seed! fa "jake" "a" :fv/save-a)
    (seed! fb "abel" "b" :fv/save-b)
    (testing "resource-state"
      (let [by-id (rf/resource-state (assoc (article "a") :frame :fv/frame-a))]
        (is (some? by-id))
        (is (= by-id (rf/resource-state (assoc (article "a") :frame fa))))))
    (testing "a {:from-db} scope resolves against the value's own frame app-db"
      (let [by-id (rf/resource-state (assoc feed :frame :fv/frame-a))]
        (is (= (session-key "jake") (:resource/key by-id)))
        (is (= by-id (rf/resource-state (assoc feed :frame fa))))
        (is (= (session-key "abel") (:resource/key (rf/resource-state (assoc feed :frame fb)))))))
    (testing "mutation-state"
      (let [by-id (rf/mutation-state {:instance :fv/save-a :frame :fv/frame-a})]
        (is (some? by-id))
        (is (= by-id (rf/mutation-state {:instance :fv/save-a :frame fa})))))
    (testing "another frame stays isolated when addressed by value"
      (is (nil? (rf/resource-state (assoc (article "b") :frame fa))))
      (is (nil? (rf/mutation-state {:instance :fv/save-b :frame fa}))))
    (testing "a destroyed frame reads nil by its retained value"
      (rf.frame/destroy-frame! fa)
      (is (nil? (rf/resource-state (assoc (article "a") :frame fa))))
      (is (nil? (rf/mutation-state {:instance :fv/save-a :frame fa}))))
    (rf.frame/destroy-frame! fb)))

(deftest missing-nil-and-unknown-targets-are-unchanged
  (seed! :rf/default "jake" "a" :fv/save-a)
  (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"no-frame-context"
        (rf/resource-state (article "a"))))
  (is (nil? (rf/resource-state (assoc (article "a") :frame :fv/no-such-frame)))))
