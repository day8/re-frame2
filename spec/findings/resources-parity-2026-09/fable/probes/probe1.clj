;; probe1 — read, reuse, refresh-error, dedupe, fresh-skip, first-load error,
;; stale-reply suppression, owner release, defaults. JVM, plain-atom substrate,
;; capturing :rf.http/managed transport with an explicit REQUEST LEDGER.
;; Every step prints one line "P1.<n> <label> => <value>". A step that throws
;; prints "P1.<n> THREW <msg> <ex-data>" and the probe continues.
(ns probe1
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
(def timers (atom []))

(defn- out [n label v] (println (str "P1." n " " label " => " (pr-str v))))
(defmacro step [n label & body]
  `(try (out ~n ~label (do ~@body))
        (catch Throwable t# (println (str "P1." ~n " THREW " (.getMessage t#) " " (pr-str (ex-data t#)))))))

(defn entry [scoped-key]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (rf.resources.state/entry-path scoped-key)))
(defn summary [e] (when e (select-keys e [:status :data :error :refresh-error :generation :active-owners :revision])))
(defn reply-ok! [args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
(defn reply-fail! [args failure] (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))
(defn reqs [] (mapv (fn [a] [(get-in a [:request :method]) (get-in a [:request :url])]) @ledger))
(defn last-args [] (peek @ledger))

(def fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(fixture
 (fn []
   (rf.http.registry/clear-all-in-flight!)
   (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! ledger conj args) nil))
   (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! timers conj args) nil))

   (rf/reg-resource :probe/article
     {:scope :rf.scope/global
      :params-schema [:map [:slug :string]]
      :tags (fn [{:keys [slug]} _data] #{[:article slug]})}
     (fn [{:keys [slug]} _ctx] {:request {:method :get :url (str "/api/articles/" slug)}}))
   (rf/reg-resource :probe/articles
     {:scope :rf.scope/global
      :params-schema [:map]
      :tags (fn [_params data] (into #{[:article-list]} (map (fn [a] [:article (:slug a)])) (:articles data)))}
     (fn [_params _ctx] {:request {:method :get :url "/api/articles"}}))
   (rf/reg-resource :probe/stale-soon
     {:scope :rf.scope/global :params-schema [:map] :stale-after-ms 1000}
     (fn [_ _] {:request {:method :get :url "/api/stale-soon"}}))

   (let [k-hello (rf.resources.state/scoped-resource-key :rf.scope/global :probe/article {:slug "hello"})
         k-list  (rf.resources.state/scoped-resource-key :rf.scope/global :probe/articles {})]

     (step 1 "views-never-fetch: resource-state before any cause (public read)"
       (rf/resource-state {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :frame :rf/default}))
     (step 2 "ensure (owner a1) -> status, ledger"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :owner [:app :a 1]}])
           {:status (:status (entry k-hello)) :ledger (reqs) :args-keys (sort (keys (last-args)))}))
     (step 3 "reply ok -> status/data (public resource-state)"
       (do (reply-ok! (last-args) {:article {:slug "hello" :title "Welcome" :favorited false :favoritesCount 1}})
           (select-keys (rf/resource-state {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :frame :rf/default}) [:status :has-data? :data])))
     (step 4 "fresh-skip: second ensure same key (owner a2) -> ledger unchanged, owners"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :owner [:app :a 2]}])
           {:ledger-count (count @ledger) :owners (:active-owners (entry k-hello)) :status (:status (entry k-hello))}))
     (step 5 "dedupe: two ensures of the list while in flight -> one request"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/articles :scope :rf.scope/global :params {} :owner [:route :home 1]}])
           (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/articles :scope :rf.scope/global :params {} :owner [:app :sidebar 1]}])
           {:ledger (reqs) :status (:status (entry k-list)) :owners (:active-owners (entry k-list))}))
     (step 6 "list reply -> loaded; tags derived from data"
       (do (reply-ok! (last-args) {:articles [{:slug "hello" :title "Welcome"} {:slug "second" :title "Second"}]})
           {:status (:status (entry k-list)) :tags (:tags (entry k-list))}))
     (step 7 "refetch article -> :fetching with data kept"
       (do (rf/dispatch-sync [:rf.resource/refetch {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :cause [:manual :probe]}])
           {:status (:status (entry k-hello)) :data-kept? (some? (:data (entry k-hello))) :ledger-count (count @ledger)}))
     (step 8 "refresh FAILS 503 -> :loaded, data kept, :refresh-error set, :error nil"
       (do (reply-fail! (last-args) {:kind :rf.http/http-5xx :status 503})
           (select-keys (entry k-hello) [:status :data :refresh-error :error])))
     (step 9 "public projection after refresh-error: resource-state keys"
       (select-keys (rf/resource-state {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :frame :rf/default})
                    [:status :has-data? :refresh-error :error :fetching?]))
     (step 10 "first-load failure 404 -> :error, no data"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "missing"} :owner [:app :m 1]}])
           (reply-fail! (last-args) {:kind :rf.http/http-4xx :status 404})
           (select-keys (entry (rf.resources.state/scoped-resource-key :rf.scope/global :probe/article {:slug "missing"})) [:status :data :error])))
     (step 11 "ensure again after first-load error -> refetch? ledger delta"
       (let [before (count @ledger)]
         (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "missing"} :owner [:app :m 2]}])
         {:new-requests (- (count @ledger) before) :status (:status (entry (rf.resources.state/scoped-resource-key :rf.scope/global :probe/article {:slug "missing"})))}))
     (step 12 "stale-reply suppression: refetch A then refetch B; reply A(old) then B(new); data must be NEW"
       (do (rf/dispatch-sync [:rf.resource/refetch {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :cause [:manual :a]}])
           (let [args-a (last-args)]
             (rf/dispatch-sync [:rf.resource/refetch {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :cause [:manual :b]}])
             (let [args-b (last-args)]
               (reply-ok! args-a {:article {:slug "hello" :title "OLD"}})
               (let [after-a (get-in (entry k-hello) [:data :article :title])]
                 (reply-ok! args-b {:article {:slug "hello" :title "NEW"}})
                 (let [after-b (get-in (entry k-hello) [:data :article :title])]
                   (reply-ok! args-a {:article {:slug "hello" :title "OLD-AGAIN"}})
                   {:after-a after-a :after-b after-b :after-late-a (get-in (entry k-hello) [:data :article :title])
                    :generation (:generation (entry k-hello))}))))))
     (step 13 "FAULT CONTROL: reply with the wrong article; an assertion on the title must go red"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "faulted"} :owner [:app :f 1]}])
           (reply-ok! (last-args) {:article {:slug "other" :title "WRONG"}})
           (let [title (get-in (entry (rf.resources.state/scoped-resource-key :rf.scope/global :probe/article {:slug "faulted"})) [:data :article :title])]
             (if (= "Expected" title) :FAULT-MISSED :FAULT-DETECTED))))
     (step 14 "defaults: entry policy fields present on a loaded entry (stale/gc)"
       (select-keys (entry k-hello) [:stale-after-ms :gc-after-ms :loaded-at :completed-at]))
     (step 15 "entry-stale? on default-policy entry at now = loaded-at + 1 day"
       (let [e (entry k-hello)
             f (ns-resolve 're-frame.resources.state 'entry-stale?)]
         {:fn-arglists (:arglists (meta f))
          :result (try (f e (+ (or (:loaded-at e) (:completed-at e) 0) 86400000)) (catch Throwable t (str "THREW " (.getMessage t))))}))
     (step 16 "entry-stale? on :stale-after-ms 1000 entry at +2s"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/stale-soon :scope :rf.scope/global :params {} :owner [:app :s 1]}])
           (reply-ok! (last-args) {:v 1})
           (let [e (entry (rf.resources.state/scoped-resource-key :rf.scope/global :probe/stale-soon {}))
                 f (ns-resolve 're-frame.resources.state 'entry-stale?)]
             {:policy (select-keys e [:stale-after-ms :gc-after-ms])
              :at+2s (try (f e (+ (or (:loaded-at e) (:completed-at e) 0) 2000)) (catch Throwable t (str "THREW " (.getMessage t))))
              :at+0.5s (try (f e (+ (or (:loaded-at e) (:completed-at e) 0) 500)) (catch Throwable t (str "THREW " (.getMessage t))))})))
     (step 17 "release both owners of hello -> owners empty, entry kept, timers scheduled (gc)"
       (do (rf/dispatch-sync [:rf.resource/release-owner {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :owner [:app :a 1]}])
           (rf/dispatch-sync [:rf.resource/release-owner {:resource :probe/article :scope :rf.scope/global :params {:slug "hello"} :owner [:app :a 2]}])
           {:owners (:active-owners (entry k-hello)) :status (:status (entry k-hello))
            :timers (mapv (fn [t] (if (map? t) (select-keys t [:kind :after-ms :delay-ms :gc-after-ms]) t)) (take-last 3 @timers))
            :timer-count (count @timers)}))
     (step 18 "leave-and-return at owner level: ensure (owner r1) in flight, release r1, reply arrives, ensure again (owner r2)"
       (do (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "leave"} :owner [:route :r 1]}])
           (let [args (last-args) k (rf.resources.state/scoped-resource-key :rf.scope/global :probe/article {:slug "leave"})]
             (rf/dispatch-sync [:rf.resource/release-owner {:resource :probe/article :scope :rf.scope/global :params {:slug "leave"} :owner [:route :r 1]}])
             (let [after-release (summary (entry k))]
               (reply-ok! args {:article {:slug "leave" :title "Late"}})
               (let [after-late (summary (entry k)) before (count @ledger)]
                 (rf/dispatch-sync [:rf.resource/ensure {:resource :probe/article :scope :rf.scope/global :params {:slug "leave"} :owner [:route :r 2]}])
                 {:after-release after-release :after-late-reply after-late
                  :return-status (:status (entry k)) :return-new-requests (- (count @ledger) before)})))))
     (step 19 "final request ledger" (reqs))
     (println "DONE"))))
