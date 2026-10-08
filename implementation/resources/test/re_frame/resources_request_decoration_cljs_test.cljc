(ns re-frame.resources-request-decoration-cljs-test
  "Request decoration for resource reads and mutations (Spec 016 §Request
  decoration belongs to the managed-HTTP seam). Both lower through the one
  `:rf.http/managed` fx, so a frame-registered `reg-http-interceptor`
  decorates every request the frame issues with no per-resource or
  per-mutation opt-in. The interceptor reads the carried frame's app-db and
  leaves the request alone when it does not apply; decoration leaves the
  runtime-owned reply addressing, and so stale suppression, intact; and the
  decorated header value never reaches a trace row (Spec 016 §Security).

  The `:rf.http/managed` stub replaces only the wire: it walks the real
  per-frame request-side chain (`rf.http.test-support/run-request-chain`),
  captures the decorated request and the reply addressing, and the test
  replays the reply in the live transport's append shape."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources.test-support]
   ;; production HTTP fx + interceptor surface: publishes the transport feature
   ;; probe the resource lowering consults and the per-frame interceptor chain
   [re-frame.http.managed :as rf.http.managed]
   [re-frame.http.test-support :as rf.http.test-support]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-decorated (atom nil))

(defn- decorating-transport-fixture
  "Override :rf.http/managed with a stub that runs the real request-side
  interceptor chain, then captures `{:request <post-:before request>
  :on-success <addr> :on-failure <addr>}`."
  [f]
  (reset! last-decorated nil)
  ;; keeps the empty-chain precondition local to this fixture
  (rf.http.managed/clear-all-http-interceptors!)
  (rf.fx/reg-fx :rf.http/managed
             (fn [frame-ctx args]
               (let [ctx (rf.http.test-support/run-request-chain frame-ctx args)]
                 (reset! last-decorated
                         {:request    (:request ctx)
                          :on-success (:on-success args)
                          :on-failure (:on-failure args)}))
               nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  decorating-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- instance [instance-id] (get-in (runtime-db) (rf.resources.mutation-runtime/instance-path instance-id)))

(defn- reply-success! [data]
  (rf/dispatch-sync (conj (:on-success @last-decorated) {:status :ok :value data})))

;; The canonical auth-header decoration interceptor: reads the bearer token
;; from the CARRIED frame's app-db, `(rf/app-db-value (:frame ctx))`, never an
;; ambient db, and returns ctx unchanged when no token is present.
(defn- reg-auth-interceptor! []
  (rf/reg-http-interceptor :test/auth
    {:frame  :rf/default
     :before (fn [ctx]
               (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
                 (cond-> ctx
                   token (assoc-in [:request :headers "Authorization"]
                                   (str "Token " token)))))}))

(defn- auth-header [] (get-in @last-decorated [:request :headers "Authorization"]))

(defn- article-spec []
  {:scope         :rf.scope/global
   :params-schema [:map [:slug :string]]
   :tags          (fn [{:keys [slug]} _v] #{[:article slug]})})

;; the DOMAIN request only: decoration is the frame's managed-HTTP policy
(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- save-spec []
  {:params-schema [:map [:slug :string]]
   :invalidates   (fn [{:keys [slug]} _r] #{[:article slug]})})

(def ^:private save-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug)
               :body  {:slug slug}}}))

(defn- seed-token! [token]
  (rf/reg-event :test/login (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] token)}))
  (rf/dispatch-sync [:test/login]))

(defn- ensure-article! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope :rf.scope/global
                      :params {:slug "w"} :owner [:app resource 1]}]))

;; ===========================================================================

(deftest resource-read-receives-frame-decoration
  (seed-token! "abc123")
  (reg-auth-interceptor!)
  (rf/reg-resource :rd/article (article-spec) article-spec-request)
  (ensure-article! :rd/article)
  (is (= {:method :get :url "/api/articles/w" :headers {"Authorization" "Token abc123"}}
         (:request @last-decorated))
      "the lowered read ran through the frame's :before chain: the domain request, decorated with the header")
  (reply-success! {:title "Welcome"})
  (is (= [:loaded {:title "Welcome"}]
         ((juxt :status :data) (entry (rf.resources.state/scoped-resource-key :rf.scope/global :rd/article {:slug "w"}))))
      "and the decorated read settles end to end"))

(deftest mutation-receives-same-frame-decoration
  (seed-token! "xyz789")
  (reg-auth-interceptor!)
  (rf/reg-mutation :md/save (save-spec) save-spec-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :md/save :params {:slug "w"} :instance :md/save-1}])
  (is (= {:method :put :url "/api/articles/w" :body {:slug "w"} :headers {"Authorization" "Token xyz789"}}
         (:request @last-decorated))
      "the write lowers through the same seam, so the same interceptor decorates it")
  (reply-success! {:slug "w"})
  (is (= :success (:status (instance :md/save-1))) "and the decorated write settles the instance"))

(deftest decoration-no-op-when-token-absent
  (reg-auth-interceptor!)
  (rf/reg-resource :na/article (article-spec) article-spec-request)
  (ensure-article! :na/article)
  (is (= {:method :get :url "/api/articles/w"} (:request @last-decorated))
      "with no token in the frame's app-db the bare domain request reaches the transport unchanged"))

(deftest decoration-composes-with-stale-suppression
  (seed-token! "tok")
  (reg-auth-interceptor!)
  (rf/reg-resource :sp/article (article-spec) article-spec-request)
  (let [k (rf.resources.state/scoped-resource-key :rf.scope/global :sp/article {:slug "w"})]
    (ensure-article! :sp/article)
    (let [gen1-header  (auth-header)
          gen1-success (:on-success @last-decorated)]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :sp/article :scope :rf.scope/global :params {:slug "w"}}])
      (is (= ["Token tok" "Token tok" 2] [gen1-header (auth-header) (:generation (entry k))])
          "a forced refetch supersedes gen 1, and both requests are decorated")
      (rf/dispatch-sync (conj gen1-success {:status :ok :value {:stale "data"}}))
      (is (= [nil 2 :loading] ((juxt :data :generation :status) (entry k)))
          "the stale, fully decorated gen-1 reply never writes over the newer generation")
      (reply-success! {:fresh "data"})
      (is (= {:fresh "data"} (:data (entry k))) "the current gen-2 reply lands normally"))))

(deftest decorated-bearer-token-does-not-leak-into-trace-rows
  ;; the runtime lowers the DOMAIN request into the trace stream and the header
  ;; is stamped later, inside the managed-HTTP chain; a regression echoing the
  ;; decorated request into a trace facet would pass every test above
  (seed-token! "abc123")
  (reg-auth-interceptor!)
  (rf/reg-resource :rl/article (article-spec) article-spec-request)
  (rf/reg-mutation :ml/save (save-spec) save-spec-request)
  (let [seen (atom [])
        k    ::leak-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (contains? #{"rf.resource" "rf.resource.internal" "rf.mutation" "rf.mutation.internal"}
                                  (when (keyword? (:operation ev)) (namespace (:operation ev))))
                   (swap! seen conj ev))))
    (try
      (ensure-article! :rl/article)
      (reply-success! {:title "Welcome"})
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :ml/save :params {:slug "w"} :instance :ml/save-1}])
      (reply-success! {:slug "w"})
      (finally (rf.trace.tooling/unregister-listener! k)))
    (is (= ["Token abc123" true] [(auth-header) (boolean (seq @seen))])
        "FIXTURE — the decoration applied and the read and write lifecycles were traced")
    (is (empty? (filterv #(re-find #"abc123|Authorization" (pr-str %)) @seen))
        "neither the bearer token nor the Authorization header appears in any resource or mutation trace row")))
