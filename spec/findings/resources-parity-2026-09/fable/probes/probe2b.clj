;; probe2b — follow-ups to probe2: (a) a REAL missing-invalidation fault (the
;; mutation forgets the session-scoped feed, which carries no member tag for the
;; article) so the detection assertion must go red; (b) what state the affected
;; reads are in at the moment :reply-to fires; (c) the trace records a
;; maintainer (or Xray) would read to connect "which write staled which read".
(ns probe2b
  (:require [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.resources]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.schemas]
            [re-frame.test-support :as rf.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ledger (atom []))
(def replied (atom []))
(def traces (atom []))
(defn- out [n label v] (println (str "P2b." n " " label " => " (pr-str v))))
(defmacro step [n label & body]
  `(try (out ~n ~label (do ~@body))
        (catch Throwable t# (println (str "P2b." ~n " THREW " (.getMessage t#) " " (pr-str (ex-data t#)))))))
(def SESSION [:rf.scope/session {:username "alice"}])
(defn entry [scope rid params]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path (rf.resources.state/scoped-resource-key scope rid params))))
(defn st [scope rid params] (let [e (entry scope rid params)] [(:status e) (:revision e) (:stale? e)]))
(defn reply-ok! [args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
(defn reqs [] (mapv (fn [a] [(get-in a [:request :method]) (get-in a [:request :url])]) @ledger))
(defn last-args [] (peek @ledger))
(defn args-for [method url] (last (filter #(and (= method (get-in % [:request :method])) (= url (get-in % [:request :url]))) @ledger)))
(defn flip [f? a] (assoc a :favorited f? :favoritesCount ((if f? inc dec) (:favoritesCount a))))
(defn patch-fn [f? slug] (fn [data] (cond (:article data) (update data :article #(if (= slug (:slug %)) (flip f? %) %))
                                          (:articles data) (update data :articles (fn [as] (mapv #(if (= slug (:slug %)) (flip f? %) %) as)))
                                          :else data)))
(def fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(fixture
 (fn []
   (rf.http.registry/clear-all-in-flight!)
   (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! ledger conj args) nil))
   (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
   (rf/reg-event :p2b/set-auth (fn [{:keys [db]} [_ auth]] {:db (assoc db :auth auth)}))
   (rf/reg-event :p2b/replied (fn [_ [_ slug reply]]
                                (swap! replied conj {:slug slug :ledger (count @ledger) :reply (:status reply)
                                                     :list (st :rf.scope/global :p2b/list {}) :feed (st SESSION :p2b/feed {})}) {}))
   (rf/reg-resource-scope :p2b/session {:inputs {:username [:db [:auth :user :username]]}}
     (fn [{:keys [username]} _] (when username [:rf.scope/session {:username username}])))
   (rf/dispatch-sync [:p2b/set-auth {:user {:username "alice"}}])
   (rf/reg-resource :p2b/article {:scope :rf.scope/global :params-schema [:map [:slug :string]] :tags (fn [{:keys [slug]} _] #{[:article slug]})}
     (fn [{:keys [slug]} _] {:request {:method :get :url (str "/api/articles/" slug)}}))
   ;; list carries member tags (as Conduit's does); feed carries ONLY [:feed] — no member tags
   (rf/reg-resource :p2b/list {:scope :rf.scope/global :params-schema [:map] :tags (fn [_ data] (into #{[:article-list]} (map (fn [a] [:article (:slug a)])) (:articles data)))}
     (fn [_ _] {:request {:method :get :url "/api/articles"}}))
   (rf/reg-resource :p2b/feed {:scope {:from-db :p2b/session} :params-schema [:map] :tags (fn [_ _] #{[:feed]})}
     (fn [_ _] {:request {:method :get :url "/api/articles/feed"}}))
   (defn reg-fav! [id invalidates]
     (rf/reg-mutation id
       {:params-schema [:map [:slug :string]]
        :optimistic-tags (fn [{:keys [slug]}] [{:scope :rf.scope/global :tags #{[:article slug]} :patch (patch-fn true slug)}
                                               {:scope {:from-db :p2b/session} :tags #{[:feed]} :patch (patch-fn true slug)}])
        :populates (fn [{:keys [slug]} result] {{:resource :p2b/article :params {:slug slug} :scope :rf.scope/global} result})
        :invalidates invalidates}
       (fn [{:keys [slug]} _] {:request {:method :post :url (str "/api/articles/" slug "/favorite")}})))
   (reg-fav! :p2b/favorite (fn [{:keys [slug]} _] [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}} {:scope {:from-db :p2b/session} :tags #{[:feed]}}]))
   (reg-fav! :p2b/favorite-forgets-feed (fn [{:keys [slug]} _] [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}]))

   (let [A {:slug "hello" :favorited false :favoritesCount 1}]
     (rf/dispatch-sync [:rf.resource/ensure {:resource :p2b/article :scope :rf.scope/global :params {:slug "hello"} :owner [:route :a 1]}])
     (reply-ok! (last-args) {:article A})
     (rf/dispatch-sync [:rf.resource/ensure {:resource :p2b/list :scope :rf.scope/global :params {} :owner [:route :home 1]}])
     (reply-ok! (last-args) {:articles [A]})
     (rf/dispatch-sync [:rf.resource/ensure {:resource :p2b/feed :params {} :owner [:route :home 1]}])
     (reply-ok! (last-args) {:articles [A]})
     (step 1 "baseline loaded; ledger" {:ledger (reqs) :feed (st SESSION :p2b/feed {})})
     (step 2 "correct mutation: at :reply-to time, what do list and feed read, and how many requests are in the ledger?"
       (do (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2b/favorite :params {:slug "hello"} :instance [:fav 1] :cause [:click :probe] :reply-to [:p2b/replied "hello"]}])
           (reply-ok! (args-for :post "/api/articles/hello/favorite") {:article (assoc A :favorited true :favoritesCount 2)})
           {:reply-to-saw (last @replied) :ledger-now (count @ledger) :issued-after-reply (subvec (reqs) 4)}))
     (do (reply-ok! (args-for :get "/api/articles") {:articles [(assoc A :favorited true :favoritesCount 2)]})
         (reply-ok! (args-for :get "/api/articles/feed") {:articles [(assoc A :favorited true :favoritesCount 2)]}))
     (step 3 "REAL PLANTED FAULT: mutation forgets the session feed descriptor -> feed must NOT refetch and must NOT be marked stale; assertion goes red"
       (let [before (count @ledger)]
         (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2b/favorite-forgets-feed :params {:slug "hello"} :instance [:fav 2] :cause [:click :probe]}])
         (reply-ok! (last-args) {:article (assoc A :favorited true :favoritesCount 3)})
         (let [issued (subvec (reqs) (inc before)) feed-refetched? (boolean (some #(= "/api/articles/feed" (second %)) issued))]
           {:issued-after-reply issued :feed-state (st SESSION :p2b/feed {}) :feed-refetched? feed-refetched?
            :verdict (if feed-refetched? :FAULT-MISSED :FAULT-DETECTED)})))
     (step 4 "diagnosis surface: the mutation row's :affected-keys (optimistic reach) vs its declared invalidation reach"
       {:affected-keys (:affected-keys (rf/mutation-state {:instance [:fav 2] :frame :rf/default}))
        :declared-invalidates-note "the registration's :invalidates fn is app code; see step 5 for what the framework exposes"})
     (step 5 "diagnosis surface: handler-meta for the mutation (what an agent/Xray can read without executing)"
       (let [m (try (rf/handler-meta :mutation :p2b/favorite-forgets-feed) (catch Throwable t {:THREW (.getMessage t)}))]
         (if (map? m) (into {} (map (fn [[k v]] [k (if (fn? v) :fn v)]) m)) m)))
     (println "DONE"))))
