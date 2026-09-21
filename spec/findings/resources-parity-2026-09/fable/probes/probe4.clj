;; probe4 — J1 on the JVM: the DOCUMENTED public test route from
;; docs/resources/testing.md §1, written as a new user would (with-new-frame,
;; with-request-stubs, ensure with :cause, resource-state), plus the page's
;; "three contracts worth pinning": idle before any cause, first-load failure
;; is :error with no data, a fresh ensure is a cache hit. Nothing internal.
(ns probe4
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]
            [re-frame.http.test-support :as http-test-support]
            [re-frame.test-support :as ts]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- out [n label v] (println (str "P4." n " " label " => " (pr-str v))))
(defmacro step [n label & body]
  `(try (out ~n ~label (do ~@body))
        (catch Throwable t# (println (str "P4." ~n " THREW " (.getMessage t#) " " (pr-str (ex-data t#)))))))

;; the registration exactly as docs/resources/index.md shows it
(rf/reg-resource :article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

(def fixture (ts/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(fixture
 (fn []
   (rf/with-new-frame [f (rf/make-frame {})]
     (http-test-support/with-request-stubs
       {[:get "/api/articles/intro"]   {:reply {:ok {:article {:slug "intro" :title "Welcome"}}}}
        [:get "/api/articles/broken"]  {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}
       (fn []
         (step 1 "idle before any cause (the sub, in the new frame)"
           (select-keys @(rf/subscribe [:rf/resource {:resource :article :params {:slug "intro"} :frame f}]) [:status :has-data? :loading?]))
         (step 2 "ensure with a :cause (no owner) -> stub answers inside the drain -> resource-state"
           (do (rf/dispatch-sync [:rf.resource/ensure {:resource :article :params {:slug "intro"} :cause [:manual :test/setup] :frame f}])
               (select-keys (rf/resource-state {:resource :article :params {:slug "intro"} :frame f}) [:status :has-data? :data])))
         (step 3 "the sub after the cause"
           (select-keys @(rf/subscribe [:rf/resource {:resource :article :params {:slug "intro"} :frame f}]) [:status :has-data? :loading? :fetching? :stale?]))
         (step 4 "first-load failure is :error with no data"
           (do (rf/dispatch-sync [:rf.resource/ensure {:resource :article :params {:slug "broken"} :cause [:manual :test/setup] :frame f}])
               (select-keys (rf/resource-state {:resource :article :params {:slug "broken"} :frame f}) [:status :has-data? :error])))
         (step 5 "a fresh ensure is a cache hit: ensure intro again; the stub table has no second reply queued and nothing is fetched"
           (do (rf/dispatch-sync [:rf.resource/ensure {:resource :article :params {:slug "intro"} :cause [:manual :again] :frame f}])
               (select-keys (rf/resource-state {:resource :article :params {:slug "intro"} :frame f}) [:status :has-data?])))
         (step 6 "an unstubbed url: what does the documented route do with a request the table does not answer?"
           (try (rf/dispatch-sync [:rf.resource/ensure {:resource :article :params {:slug "nope"} :cause [:manual :x] :frame f}])
                (select-keys (or (rf/resource-state {:resource :article :params {:slug "nope"} :frame f}) {}) [:status :error])
                (catch Throwable t {:THREW (.getMessage t) :id (:rf.error/id (ex-data t))})))
         (step 7 "ceremony: requires used by this file" '[re-frame.core re-frame.resources re-frame.http.managed re-frame.http.test-support re-frame.test-support re-frame.schemas])
         (println "DONE"))))))
(System/exit 0)
