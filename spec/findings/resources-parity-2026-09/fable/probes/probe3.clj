;; probe3 — scope as identity: viewer switch during work with a delayed reply,
;; logout clear-scope, cold boot with an unresolved token, confirmed anonymous,
;; and three deliberate leak attempts (no scope on registration; wrong-scope
;; read; unscoped invalidate-tags).
(ns probe3
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
(defn- out [n label v] (println (str "P3." n " " label " => " (pr-str v))))
(defmacro step [n label & body]
  `(try (out ~n ~label (do ~@body))
        (catch Throwable t# (println (str "P3." ~n " THREW " (.getMessage t#) " " (pr-str (ex-data t#)))))))
(defn entry [scope rid params]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path (rf.resources.state/scoped-resource-key scope rid params))))
(defn reply-ok! [args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
(defn reqs [] (mapv (fn [a] [(get-in a [:request :method]) (get-in a [:request :url])]) @ledger))
(defn last-args [] (peek @ledger))
(defn sub-feed [] (try (select-keys @(rf/subscribe [:rf/resource {:resource :p3/feed :params {}}]) [:status :has-data? :data :scope])
                       (catch Throwable t {:THREW (.getMessage t) :data (ex-data t)})))
(def fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(fixture
 (fn []
   (rf.http.registry/clear-all-in-flight!)
   (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! ledger conj args) nil))
   (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
   (rf/reg-event :p3/set-auth (fn [{:keys [db]} [_ auth]] {:db (assoc db :auth auth)}))

   (rf/reg-resource-scope :p3/viewer
     {:inputs {:username [:db [:auth :user :username]] :token [:db [:auth :token]]}}
     (fn [{:keys [username token]} _ctx]
       (cond username [:rf.scope/viewer {:username username}]
             (str/blank? token) [:rf.scope/viewer :anonymous]
             :else nil)))
   (rf/reg-resource :p3/feed {:scope {:from-db :p3/viewer} :params-schema [:map]}
     (fn [_ _] {:request {:method :get :url "/api/articles/feed"}}))

   (step 1 "DELIBERATE LEAK 1: register a resource with NO :scope"
     (try (rf/reg-resource :p3/unscoped {:params-schema [:map]} (fn [_ _] {:request {:method :get :url "/x"}})) :REGISTERED-SILENTLY
          (catch Throwable t {:refused (.getMessage t) :error-id (:rf.error/id (ex-data t)) :keys (sort (keys (or (ex-data t) {})))})))
   (step 2 "alice logged in; ensure feed via from-db scope -> request in flight; resolved scope"
     (do (rf/dispatch-sync [:p3/set-auth {:token "tok-a" :user {:username "alice"}}])
         (rf/dispatch-sync [:rf.resource/ensure {:resource :p3/feed :params {} :owner [:route :home 1]}])
         {:ledger (reqs) :alice-entry-status (:status (entry [:rf.scope/viewer {:username "alice"}] :p3/feed {})) :sub (sub-feed)}))
   (step 3 "switch principal to bob by db write only (no logout), Alice's reply still pending: what does the sub read?"
     (do (rf/dispatch-sync [:p3/set-auth {:token "tok-b" :user {:username "bob"}}])
         {:sub-as-bob (sub-feed) :ledger-count (count @ledger)}))
   (step 4 "release Alice's delayed reply -> lands under Alice's key only; bob's sub unchanged"
     (do (reply-ok! (last-args) {:articles [{:slug "alices-secret"}]})
         {:alice-entry (select-keys (entry [:rf.scope/viewer {:username "alice"}] :p3/feed {}) [:status :data])
          :bob-entry (entry [:rf.scope/viewer {:username "bob"}] :p3/feed {})
          :sub-as-bob (sub-feed)}))
   (step 5 "DELIBERATE LEAK 2: explicit wrong-scope read (bob asks with alice's scope literal) -> what does it return?"
     (select-keys (or (rf/resource-state {:resource :p3/feed :params {} :scope [:rf.scope/viewer {:username "alice"}] :frame :rf/default}) {:nil true}) [:status :data :nil]))
   (step 6 "logout as alice would: clear-scope alice -> entry gone; no requests issued"
     (let [before (count @ledger)]
       (rf/dispatch-sync [:rf.resource/clear-scope {:scope [:rf.scope/viewer {:username "alice"}]}])
       {:alice-entry (entry [:rf.scope/viewer {:username "alice"}] :p3/feed {}) :new-requests (- (count @ledger) before)}))
   (step 7 "bob ensures -> own request; reply; bob reads own data"
     (do (rf/dispatch-sync [:rf.resource/ensure {:resource :p3/feed :params {} :owner [:route :home 2]}])
         (reply-ok! (last-args) {:articles [{:slug "bobs"}]})
         {:sub-as-bob (sub-feed) :ledger (reqs)}))
   (step 8 "COLD BOOT: token present, user unresolved -> resolver nil: ensure and sub fail closed?"
     (do (rf/dispatch-sync [:p3/set-auth {:token "tok-c" :user nil}])
         (let [before (count @ledger)
               ensure-result (try (rf/dispatch-sync [:rf.resource/ensure {:resource :p3/feed :params {} :owner [:route :home 3]}]) :DISPATCHED
                                  (catch Throwable t {:THREW (.getMessage t) :error-id (:rf.error/id (ex-data t))}))]
           {:ensure ensure-result :new-requests (- (count @ledger) before) :sub (sub-feed)})))
   (step 9 "confirmed anonymous (blank token, no user) -> scope [:viewer :anonymous]; ensure works"
     (do (rf/dispatch-sync [:p3/set-auth {:token nil :user nil}])
         (let [before (count @ledger)]
           (rf/dispatch-sync [:rf.resource/ensure {:resource :p3/feed :params {} :owner [:route :home 4]}])
           (reply-ok! (last-args) {:articles [{:slug "public"}]})
           {:new-requests (- (count @ledger) before) :sub (sub-feed)
            :anon-entry-status (:status (entry [:rf.scope/viewer :anonymous] :p3/feed {}))})))
   (step 10 "DELIBERATE LEAK 3: invalidate-tags with no scope -> loud error?"
     (try (rf/dispatch-sync [:rf.resource/invalidate-tags {:tags #{[:feed]}}]) :ACCEPTED-SILENTLY
          (catch Throwable t {:refused (.getMessage t) :error-id (:rf.error/id (ex-data t))})))
   (step 11 "scoped invalidate reaches only the named scope: invalidate bob's feed tag while anon entry stays"
     (let [before (count @ledger)]
       (rf/dispatch-sync [:p3/set-auth {:token "tok-b" :user {:username "bob"}}])
       (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope [:rf.scope/viewer {:username "bob"}] :tags #{[:feed]}}])
       {:new-requests (subvec (reqs) before)
        :bob-status (:status (entry [:rf.scope/viewer {:username "bob"}] :p3/feed {}))
        :anon-status (:status (entry [:rf.scope/viewer :anonymous] :p3/feed {}))}))
   (step 12 "final ledger" (reqs))
   (println "DONE")))
