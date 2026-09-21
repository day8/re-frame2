;; probe3b — follow-ups to probe3 with the feed carrying a [:feed] tag: unscoped
;; invalidate-tags (loud?), scoped invalidate reaching only the named principal,
;; the full subscription value while the scope is unresolved (cold boot), and
;; an error-sink capture so a refused handler is not mistaken for silence.
(ns probe3b
  (:require [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.resources]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.schemas]
            [re-frame.test-support :as rf.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [clojure.string :as str]))

(def ledger (atom []))
(def errors (atom []))
(defn- out [n label v] (println (str "P3b." n " " label " => " (pr-str v))))
(defmacro step [n label & body]
  `(try (out ~n ~label (do ~@body))
        (catch Throwable t# (println (str "P3b." ~n " THREW " (.getMessage t#) " " (pr-str (ex-data t#)))))))
(defn entry [scope rid params]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path (rf.resources.state/scoped-resource-key scope rid params))))
(defn reply-ok! [args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
(defn reqs [] (mapv (fn [a] [(get-in a [:request :method]) (get-in a [:request :url])]) @ledger))
(defn last-args [] (peek @ledger))
(defn sub-feed [] (try @(rf/subscribe [:rf/resource {:resource :p3b/feed :params {}}]) (catch Throwable t {:THREW (.getMessage t) :data (ex-data t)})))
(def fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(fixture
 (fn []
   (rf.http.registry/clear-all-in-flight!)
   (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! ledger conj args) nil))
   (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
   (rf/reg-event :p3b/set-auth (fn [{:keys [db]} [_ auth]] {:db (assoc db :auth auth)}))
   (rf/reg-resource-scope :p3b/viewer {:inputs {:username [:db [:auth :user :username]] :token [:db [:auth :token]]}}
     (fn [{:keys [username token]} _] (cond username [:rf.scope/viewer {:username username}] (str/blank? token) [:rf.scope/viewer :anonymous] :else nil)))
   (rf/reg-resource :p3b/feed {:scope {:from-db :p3b/viewer} :params-schema [:map] :tags (fn [_ _] #{[:feed]})}
     (fn [_ _] {:request {:method :get :url "/api/articles/feed"}}))
   ;; error sink: does re-frame2 route a refused handler to an observability sink instead of throwing?
   (try (rf/register-observability-sink! :probe/errors
          (fn [rec] (swap! errors conj {:keys (vec (sort (keys rec))) :kind (:kind rec) :rf.error/id (:rf.error/id rec) :id (:id rec) :where (:where rec)})))
        (rf/configure! {:observability {:errors [{:sink :probe/errors}]}})
        (catch Throwable t (println "sink setup THREW" (.getMessage t) (pr-str (ex-data t)))))

   (rf/dispatch-sync [:p3b/set-auth {:token "a" :user {:username "alice"}}])
   (rf/dispatch-sync [:rf.resource/ensure {:resource :p3b/feed :params {} :owner [:route :home 1]}])
   (reply-ok! (last-args) {:articles [{:slug "alices"}]})
   (rf/dispatch-sync [:p3b/set-auth {:token nil :user nil}])
   (rf/dispatch-sync [:rf.resource/ensure {:resource :p3b/feed :params {} :owner [:route :home 2]}])
   (reply-ok! (last-args) {:articles [{:slug "public"}]})
   (step 1 "baseline: alice and anonymous feed entries loaded; ledger" {:ledger (reqs) :alice (:status (entry [:rf.scope/viewer {:username "alice"}] :p3b/feed {})) :anon (:status (entry [:rf.scope/viewer :anonymous] :p3b/feed {}))})
   (step 2 "UNSCOPED invalidate-tags {:tags #{[:feed]}} -> thrown? error-sink record? any refetch?"
     (let [before (count @ledger) e0 (count @errors)
           r (try (rf/dispatch-sync [:rf.resource/invalidate-tags {:tags #{[:feed]}}]) :DISPATCHED (catch Throwable t {:THREW (.getMessage t) :id (:rf.error/id (ex-data t))}))]
       {:dispatch r :new-requests (subvec (reqs) before) :errors-recorded (subvec @errors e0)
        :alice (:status (entry [:rf.scope/viewer {:username "alice"}] :p3b/feed {})) :anon (:status (entry [:rf.scope/viewer :anonymous] :p3b/feed {}))}))
   (step 3 "cross-scope explicit opt-in {:cross-scope? true} -> reaches both scopes (owned -> refetch)?"
     (let [before (count @ledger)
           r (try (rf/dispatch-sync [:rf.resource/invalidate-tags {:tags #{[:feed]} :cross-scope? true :cause [:manual :probe]}]) :DISPATCHED (catch Throwable t {:THREW (.getMessage t) :id (:rf.error/id (ex-data t))}))]
       {:dispatch r :new-requests (subvec (reqs) before)
        :alice (:status (entry [:rf.scope/viewer {:username "alice"}] :p3b/feed {})) :anon (:status (entry [:rf.scope/viewer :anonymous] :p3b/feed {}))}))
   (doseq [a (filter #(= :get (get-in % [:request :method])) (drop 2 @ledger))] (try (reply-ok! a {:articles [{:slug "refetched"}]}) (catch Throwable _ nil)))
   (step 4 "SCOPED invalidate for alice only -> alice refetches (owned), anonymous untouched"
     (let [before (count @ledger)]
       (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope [:rf.scope/viewer {:username "alice"}] :tags #{[:feed]}}])
       {:new-requests (subvec (reqs) before) :alice (:status (entry [:rf.scope/viewer {:username "alice"}] :p3b/feed {})) :anon (:status (entry [:rf.scope/viewer :anonymous] :p3b/feed {}))}))
   (step 5 "COLD BOOT (token, no user): full :rf/resource sub value while the scope resolver returns nil"
     (do (rf/dispatch-sync [:p3b/set-auth {:token "t" :user nil}])
         (sub-feed)))
   (step 6 "COLD BOOT: ensure with unresolved scope -> thrown? error-sink? requests?"
     (let [before (count @ledger) e0 (count @errors)
           r (try (rf/dispatch-sync [:rf.resource/ensure {:resource :p3b/feed :params {} :owner [:route :home 3]}]) :DISPATCHED (catch Throwable t {:THREW (.getMessage t) :id (:rf.error/id (ex-data t))}))]
       {:dispatch r :new-requests (- (count @ledger) before) :errors-recorded (subvec @errors e0)}))
   (step 7 "resolve-resource-scope is pure and fails closed: alice / anonymous / unresolved"
     {:alice (rf/resolve-resource-scope {:auth {:user {:username "alice"}}} :p3b/viewer)
      :anon (rf/resolve-resource-scope {:auth {:token nil}} :p3b/viewer)
      :unresolved (rf/resolve-resource-scope {:auth {:token "t"}} :p3b/viewer)})
   (step 8 "all error-sink records seen" @errors)
   (println "DONE")))
(System/exit 0)
