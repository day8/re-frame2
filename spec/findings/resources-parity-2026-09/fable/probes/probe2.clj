;; probe2 — writes: mutation instance state, optimistic apply across three
;; entries, populate-from-reply, declared invalidation with a request ledger,
;; :reply-to ordering, failure rollback, contested rollback under overlap,
;; two instances in flight, and a planted missing-invalidation fault.
(ns probe2
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
(defn- out [n label v] (println (str "P2." n " " label " => " (pr-str v))))
(defmacro step [n label & body]
  `(try (out ~n ~label (do ~@body))
        (catch Throwable t# (println (str "P2." ~n " THREW " (.getMessage t#) " " (pr-str (ex-data t#)))))))

(def SESSION [:rf.scope/session {:username "alice"}])
(defn entry [scope rid params]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path (rf.resources.state/scoped-resource-key scope rid params))))
(defn art [] (let [e (entry :rf.scope/global :p2/article {:slug "hello"})] [(:status e) (get-in e [:data :article :favorited]) (get-in e [:data :article :favoritesCount]) (:revision e)]))
(defn lst [] (let [e (entry :rf.scope/global :p2/list {})] [(:status e) (mapv (juxt :slug :favorited :favoritesCount) (get-in e [:data :articles]))]))
(defn feed [] (let [e (entry SESSION :p2/feed {})] [(:status e) (mapv (juxt :slug :favorited :favoritesCount) (get-in e [:data :articles]))]))
(defn reply-ok! [args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
(defn reply-fail! [args failure] (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))
(defn reqs [] (mapv (fn [a] [(get-in a [:request :method]) (get-in a [:request :url])]) @ledger))
(defn last-args [] (peek @ledger))
(defn args-for [method url] (last (filter #(and (= method (get-in % [:request :method])) (= url (get-in % [:request :url]))) @ledger)))
(defn mstate [inst] (select-keys (or (rf/mutation-state {:instance inst :frame :rf/default}) {}) [:status :optimistic? :error :affected-keys]))

(defn flip [favorited? a] (assoc a :favorited favorited? :favoritesCount ((if favorited? inc dec) (:favoritesCount a))))
(defn patch-fn [favorited? slug]
  (fn [data]
    (cond (:article data)  (update data :article #(if (= slug (:slug %)) (flip favorited? %) %))
          (:articles data) (update data :articles (fn [as] (mapv #(if (= slug (:slug %)) (flip favorited? %) %) as)))
          :else data)))

(def fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(fixture
 (fn []
   (rf.http.registry/clear-all-in-flight!)
   (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! ledger conj args) nil))
   (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
   (rf/reg-event :p2/replied (fn [_ [_ slug reply]] (swap! replied conj [slug (count @ledger) (:status reply)]) {}))

   (rf/reg-resource :p2/article {:scope :rf.scope/global :params-schema [:map [:slug :string]] :tags (fn [{:keys [slug]} _] #{[:article slug]})}
     (fn [{:keys [slug]} _] {:request {:method :get :url (str "/api/articles/" slug)}}))
   (rf/reg-resource :p2/list {:scope :rf.scope/global :params-schema [:map] :tags (fn [_ data] (into #{[:article-list]} (map (fn [a] [:article (:slug a)])) (:articles data)))}
     (fn [_ _] {:request {:method :get :url "/api/articles"}}))
   (rf/reg-event :p2/set-auth (fn [{:keys [db]} [_ auth]] {:db (assoc db :auth auth)}))
   (rf/reg-resource-scope :p2/session {:inputs {:username [:db [:auth :user :username]]}}
     (fn [{:keys [username]} _ctx] (when username [:rf.scope/session {:username username}])))
   (rf/dispatch-sync [:p2/set-auth {:token "t" :user {:username "alice"}}])
   (step 0 "registration with a LITERAL scope vector is refused (fail-closed); only :rf.scope/global or {:from-db id} allowed"
     (try (rf/reg-resource :p2/literal-scope {:scope SESSION :params-schema [:map]} (fn [_ _] {:request {:method :get :url "/x"}})) :ACCEPTED
          (catch Throwable t (:rf.error/id (ex-data t)))))
   (rf/reg-resource :p2/feed {:scope {:from-db :p2/session} :params-schema [:map] :tags (fn [_ data] (into #{[:feed]} (map (fn [a] [:article (:slug a)])) (:articles data)))}
     (fn [_ _] {:request {:method :get :url "/api/articles/feed"}}))

   (defn reg-fav! [id favorited? invalidates-fn]
     (rf/reg-mutation id
       {:params-schema   [:map [:slug :string]]
        :optimistic-tags (fn [{:keys [slug]}] [{:scope :rf.scope/global :tags #{[:article slug]} :patch (patch-fn favorited? slug)}
                                               {:scope {:from-db :p2/session} :tags #{[:feed]} :patch (patch-fn favorited? slug)}])
        :populates       (fn [{:keys [slug]} result] {{:resource :p2/article :params {:slug slug} :scope :rf.scope/global} result})
        :invalidates     invalidates-fn
        :on-conflict     :invalidate}
       (fn [{:keys [slug]} _] {:request {:method (if favorited? :post :delete) :url (str "/api/articles/" slug "/favorite")}})))
   (def full-invalidates (fn [{:keys [slug]} _] [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}} {:scope {:from-db :p2/session} :tags #{[:feed]}}]))
   (reg-fav! :p2/favorite true full-invalidates)
   (reg-fav! :p2/unfavorite false full-invalidates)
   ;; planted fault: forgets the list
   (reg-fav! :p2/favorite-forgets-list true (fn [{:keys [slug]} _] [{:scope :rf.scope/global :tags #{[:article slug]}} {:scope {:from-db :p2/session} :tags #{[:feed]}}]))

   (let [A {:slug "hello" :favorited false :favoritesCount 1} B {:slug "second" :favorited false :favoritesCount 0}]
     (step 1 "load article, list (owned by route) and feed (owned by route)"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :p2/article :scope :rf.scope/global :params {:slug "hello"} :owner [:route :article 1]}])
           (reply-ok! (last-args) {:article A})
           (rf/dispatch-sync [:rf.resource/ensure {:resource :p2/list :scope :rf.scope/global :params {} :owner [:route :home 1]}])
           (reply-ok! (last-args) {:articles [A B]})
           (rf/dispatch-sync [:rf.resource/ensure {:resource :p2/feed :params {} :owner [:route :home 1]}])
           (reply-ok! (last-args) {:articles [A]})
           {:article (art) :list (lst) :feed (feed) :ledger (reqs)}))
     (step 2 "execute favorite -> optimistic apply visible in all three, POST issued, instance pending"
       (do (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/favorite :params {:slug "hello"} :instance [:fav "hello"] :cause [:click :probe] :reply-to [:p2/replied "hello"]}])
           {:article (art) :list (lst) :feed (feed) :new-req (last (reqs)) :mstate (mstate [:fav "hello"])}))
     (step 3 "reply ok -> populate detail, invalidate list+feed (owned -> refetch), reply-to fires after"
       (let [before (count @ledger)]
         (reply-ok! (args-for :post "/api/articles/hello/favorite") {:article (assoc A :favorited true :favoritesCount 2)})
         {:article (art) :list-status (first (lst)) :feed-status (first (feed))
          :refetches-issued (subvec (reqs) before) :mstate (mstate [:fav "hello"]) :reply-to-log @replied}))
     (step 4 "answer the refetches with server truth"
       (do (reply-ok! (args-for :get "/api/articles") {:articles [(assoc A :favorited true :favoritesCount 2) B]})
           (reply-ok! (args-for :get "/api/articles/feed") {:articles [(assoc A :favorited true :favoritesCount 2)]})
           {:article (art) :list (lst) :feed (feed)}))
     (step 5 "failure rollback, no conflict: execute unfavorite, reply 500 -> restored verbatim"
       (do (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/unfavorite :params {:slug "hello"} :instance [:fav "hello"] :cause [:click :probe]}])
           (let [after-apply {:article (art) :feed (feed)}]
             (reply-fail! (args-for :delete "/api/articles/hello/favorite") {:kind :rf.http/http-5xx :status 500})
             {:after-optimistic-apply after-apply :after-rollback {:article (art) :list (lst) :feed (feed)} :mstate (mstate [:fav "hello"])
              :refetch-issued? (> (count @ledger) 0)})))
     (step 6 "contested rollback: A=unfavorite in flight; B=favorite-again lands ok first (revision moves); then A fails -> invalidate, not restore"
       (let [ledger-before (count @ledger)]
         (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/unfavorite :params {:slug "hello"} :instance [:fav-a "hello"] :cause [:click :a]}])
         (let [args-a (last-args) after-a (art)]
           (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/favorite :params {:slug "hello"} :instance [:fav-b "hello"] :cause [:click :b]}])
           (let [args-b (last-args) after-b (art)]
             (reply-ok! args-b {:article (assoc A :favorited true :favoritesCount 2)})
             (let [after-b-ok (art) ledger-mid (count @ledger)]
               (reply-fail! args-a {:kind :rf.http/http-5xx :status 500})
               (let [after-a-fail (art) refetch (subvec (reqs) ledger-mid)]
                 (when-let [ra (args-for :get "/api/articles/hello")]
                   (when (> (count @ledger) ledger-mid) (reply-ok! ra {:article (assoc A :favorited true :favoritesCount 2)})))
                 {:after-a-apply after-a :after-b-apply after-b :after-b-ok after-b-ok :after-a-fail after-a-fail
                  :refetch-after-conflict refetch :final (art) :mstate-a (mstate [:fav-a "hello"]) :mstate-b (mstate [:fav-b "hello"])}))))))
     (step 7 "two instances in flight at once are independent"
       (do (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/favorite :params {:slug "second"} :instance [:fav "second"] :cause [:click :x]}])
           (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/unfavorite :params {:slug "hello"} :instance [:fav "hello"] :cause [:click :y]}])
           {:second (mstate [:fav "second"]) :hello (mstate [:fav "hello"]) :list (lst)}))
     (step 8 "PLANTED FAULT: a favorite that forgets [:article-list]; after reply the list must NOT refetch -> assertion goes red"
       (do (reply-ok! (args-for :post "/api/articles/second/favorite") {:article (assoc B :favorited true :favoritesCount 1)})
           (reply-ok! (args-for :delete "/api/articles/hello/favorite") {:article (assoc A :favorited false :favoritesCount 1)})
           (doseq [a (filter #(= :get (get-in % [:request :method])) (drop-while #(not= :post (get-in % [:request :method])) @ledger))] nil)
           (let [before (count @ledger)]
             (rf/dispatch-sync [:rf.mutation/execute {:mutation :p2/favorite-forgets-list :params {:slug "hello"} :instance [:fav-forget "hello"] :cause [:click :z]}])
             (reply-ok! (last-args) {:article (assoc A :favorited true :favoritesCount 2)})
             (let [issued (subvec (reqs) (inc before))
                   list-refetched? (some #(= "/api/articles" (second %)) issued)]
               {:issued-after-reply issued :list-refetched? (boolean list-refetched?)
                :verdict (if list-refetched? :FAULT-MISSED :FAULT-DETECTED)}))))
     (step 9 "public projections: rf/mutation-state row keys, :rf/mutation sub keys"
       {:row-keys (sort (keys (or (rf/mutation-state {:instance [:fav "hello"] :frame :rf/default}) {})))
        :sub (try @(rf/subscribe [:rf/mutation {:instance [:fav "hello"]}]) (catch Throwable t (str "THREW " (.getMessage t))))})
     (step 10 "final ledger" (reqs))
     (println "DONE"))))
