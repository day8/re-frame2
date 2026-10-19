(ns re-frame.resources-request-decoration-cljs-test
  "Request decoration for resource reads and mutations (Spec 016 §Request
  decoration belongs to the managed-HTTP seam). Both lower through the one
  `:rf.http/managed` fx, so a `reg-http-interceptor` the frame names in its
  `:http-interceptors` decorates every request the frame issues with no
  per-resource or per-mutation opt-in. The interceptor reads the carried
  frame's app-db, and the decorated header value never reaches a trace row
  (Spec 016 §Security).

  A stub transport replaces only the wire: the real `:rf.http/managed` walks
  the frame's request-side chain, and the transport records the decorated
  request it sends and answers it with the body the test set."
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
   [re-frame.http.stub-transport :as rf.http.stub-transport]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private sent (atom []))
(def ^:private next-body (atom nil))

(defn- decorating-transport-fixture
  "Send every request the test issues through a stub transport that records
  the request the real request-side interceptor chain decorated, and answers
  it inline with `next-body`."
  [f]
  (reset! sent [])
  (reset! next-body nil)
  ;; keeps the empty-chain precondition local to this fixture
  (rf.http.managed/clear-all-http-interceptors!)
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (rf.http.stub-transport/with-transport
    (rf.http.stub-transport/route-transport
      (fn [wire] (swap! sent conj wire) {:body @next-body}))
    f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  decorating-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- instance [instance-id] (get-in (runtime-db) (rf.resources.mutation-runtime/instance-path instance-id)))

(defn- last-request
  "The request the transport last received, as the chain decorated it."
  []
  (last @sent))

(defn- answer-with!
  "Answer the next request with `data` as its body."
  [data]
  (reset! next-body data))

(defn- method-url-auth [request]
  ((juxt :method :url #(get-in % [:headers "Authorization"])) request))

;; The canonical auth-header decoration interceptor: reads the bearer token
;; from the CARRIED frame's app-db, `(rf/app-db-value (:frame ctx))`, never an
;; ambient db, and returns ctx unchanged when no token is present.
(defn- reg-auth-interceptor! []
  (rf/reg-http-interceptor :test/auth
    {:before (fn [ctx]
               (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
                 (cond-> ctx
                   token (assoc-in [:request :headers "Authorization"]
                                   (str "Token " token)))))})
  (rf/make-frame {:id :rf/default :http-interceptors [:test/auth]}))

(defn- auth-header [] (get-in (last-request) [:headers "Authorization"]))

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
  (answer-with! {:title "Welcome"})
  (ensure-article! :rd/article)
  (is (= [:get "/api/articles/w" "Token abc123"]
         (method-url-auth (last-request)))
      "the lowered read ran through the frame's :before chain: the domain request, decorated with the header")
  (is (= [:loaded {:title "Welcome"}]
         ((juxt :status :data) (entry (rf.resources.state/scoped-resource-key :rf.scope/global :rd/article {:slug "w"}))))
      "and the decorated read settles end to end"))

(deftest mutation-receives-same-frame-decoration
  (seed-token! "xyz789")
  (reg-auth-interceptor!)
  (rf/reg-mutation :md/save (save-spec) save-spec-request)
  (answer-with! {:slug "w"})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :md/save :params {:slug "w"} :instance :md/save-1}])
  (is (= [:put "/api/articles/w" "Token xyz789"]
         (method-url-auth (last-request)))
      "the write lowers through the same seam, so the same interceptor decorates it")
  (is (= :success (:status (instance :md/save-1))) "and the decorated write settles the instance"))

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
      (answer-with! {:title "Welcome"})
      (ensure-article! :rl/article)
      (answer-with! {:slug "w"})
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :ml/save :params {:slug "w"} :instance :ml/save-1}])
      (finally (rf.trace.tooling/unregister-listener! k)))
    (is (= ["Token abc123" true] [(auth-header) (boolean (seq @seen))])
        "FIXTURE — the decoration applied and the read and write lifecycles were traced")
    (is (empty? (filterv #(re-find #"abc123|Authorization" (pr-str %)) @seen))
        "neither the bearer token nor the Authorization header appears in any resource or mutation trace row")))
