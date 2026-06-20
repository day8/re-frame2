(ns re-frame.resources-request-decoration-cljs-test
  "Request decoration for resource reads + mutations (rf2-ih4hq7, EP-0016
  slice 7 — Spec 016 §Request decoration belongs to the managed-HTTP seam,
  EP-0016 Rider 3).

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$`
  regex. Cross-host so the decoration seam behaves identically server- and
  client-side.

  ## The decoration contract under test (EP-0016 Rider 3, validation-plan
  ## item 13: \"Resource and mutation managed-HTTP requests receive
  ## configured request decoration\")

  Resources and mutations BOTH lower through the same Spec 014 managed-HTTP
  fx (`:rf.http/managed`) — `re-frame.resources.transport.http/build-managed-args`
  is the single lowering for reads and writes alike. So a frame-registered
  `reg-http-interceptor` decorates EVERY request the frame issues with NO
  per-resource / per-mutation opt-in. This suite pins exactly that, plus the
  doctrine's riders:

    1. a resource read's lowered `:rf.http/managed` request runs through the
       per-frame `:before` interceptor chain — an auth interceptor that
       stamps an `Authorization` header from frame app-db decorates the
       read (the domain `:request` declared NO header);
    2. a mutation's lowered request runs through the SAME chain — one seam,
       reused by reads and writes (the favorite/save mutations of the
       dogfood need no per-mutation auth);
    3. the interceptor reads frame state via `(rf/app-db-value (:frame ctx))`
       (the EP-0002 carried-frame-correct read), NOT an ambient db, and
       returns `ctx` UNCHANGED when the decoration does not apply (no token);
    4. decoration COMPOSES with stale discipline — a decorated stale reply
       (superseded work-id / generation) NEVER overwrites newer data; the
       header stamping does not touch the runtime-owned reply addressing
       (`:request-id` / `:on-success` / `:on-failure`) the stale-suppression
       boundary rides on;
    5. base-URL decoration (a `:before` that rewrites `:url`) reaches the
       transport — the final post-`:before` request is what ships.

  ## Test seam

  The decoration seam IS the production `:rf.http/managed` per-frame
  interceptor chain (`re-frame.http.middleware`). To exercise it without a
  live fetch we override `:rf.http/managed` with a stub that runs the REAL
  request-side chain (`http-test-support/run-request-chain`) — so the
  registered interceptors genuinely fire on the request the resource /
  mutation runtime lowered — captures the post-`:before` decorated request,
  and exposes the runtime-owned reply addressing so the test can replay the
  internal reply (the genuine 3-element transport-append shape) and watch
  the entry / instance settle. This is the same `run-request-chain` the
  canned-stub path and the real Fetch/HttpClient transport walk."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.mutation-runtime :as mstate]
   [re-frame.resources.test-support]
   ;; production HTTP fx + interceptor surface — loading it publishes the
   ;; transport feature probe (`:http/abort-on-actor-destroy`) the resource
   ;; lowering consults AND registers the per-frame interceptor chain.
   [re-frame.http.managed :as http-managed]
   ;; the request-side chain walker `run-request-chain` is HTTP test
   ;; scaffolding (rf2-w59es5) — it lives in http-test-support alongside the
   ;; canned-stub fxs, not the production machine-wrapper.
   [re-frame.http.test-support :as http-test-support]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- a transport stub that runs the REAL :before chain --------------------
;;
;; The override REPLACES the wire (Fetch / HttpClient), NOT the managed
;; pipeline: it walks the genuine per-frame request-side interceptor chain
;; (`run-request-chain`) so the registered decoration interceptors fire on
;; exactly the request the resource / mutation runtime lowered, captures the
;; post-`:before` decorated request + the runtime-owned reply addressing, and
;; the test replays the reply explicitly (the 3-element internal-reply append
;; shape the live transport produces).

(def ^:private last-decorated (atom nil))

(defn- decorating-transport-fixture
  "Override :rf.http/managed with a stub that runs the real request-side
  interceptor chain, then captures `{:request <post-:before request>
  :on-success <addr> :on-failure <addr>}`. rf2-784223: the shared reset
  fixture clears resource + interceptor state before this fixture runs."
  [f]
  (reset! last-decorated nil)
  ;; the per-frame HTTP-interceptor chain is a `defonce` atom NOT cleared by
  ;; the shared resource reset hook — drop it so each test starts with an
  ;; empty chain (otherwise a prior test's interceptor leaks forward).
  (http-managed/clear-all-http-interceptors!)
  (rf/reg-fx :rf.http/managed
             (fn [frame-ctx args]
               (let [ctx (http-test-support/run-request-chain frame-ctx args)]
                 (reset! last-decorated
                         {:request    (:request ctx)
                          :on-success (:on-success args)
                          :on-failure (:on-failure args)}))
               nil))
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  decorating-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (rf/runtime-db-value :rf/default))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
(defn- instance [instance-id] (get-in (runtime-db) (mstate/instance-path instance-id)))

(defn- reply-success! [addr-key data]
  (rf/dispatch-sync (conj (get @last-decorated addr-key) {:kind :success :value data})))

;; The canonical EP-0016 auth-header decoration interceptor (Spec 016
;; §Request decoration). Reads the bearer token from the carried frame's
;; app-db — `(rf/app-db-value (:frame ctx))`, NOT an ambient db — and stamps
;; an `Authorization` header. Returns ctx UNCHANGED when no token is present.
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

;; The DOMAIN request only — no auth header here. Decoration is the
;; frame's managed-HTTP policy, applied by the interceptor.
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

;; ===========================================================================
;; 1. A RESOURCE READ's lowered request is decorated by the frame interceptor
;; ===========================================================================

(deftest resource-read-receives-frame-decoration
  (seed-token! "abc123")
  (reg-auth-interceptor!)
  (rf/reg-resource :rd/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :rd/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rd/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :rd 1]}])
    (testing "Spec 016 §Request decoration — the resource's lowered
              :rf.http/managed request ran through the per-frame :before
              chain; the auth interceptor stamped the Authorization header
              the domain :request never declared"
      (is (= "Token abc123" (auth-header))
          "the resource read carries the frame-policy auth header")
      (is (= {:method :get :url "/api/articles/w"
              :headers {"Authorization" "Token abc123"}}
             (:request @last-decorated))
          "the domain request is intact, decorated with the header"))
    (testing "the decorated read still settles end-to-end (decoration
              composes with the runtime-owned reply addressing)"
      (reply-success! :on-success {:title "Welcome"})
      (let [e (entry k)]
        (is (= :loaded (:status e)))
        (is (= {:title "Welcome"} (:data e)))))))

;; ===========================================================================
;; 2. A MUTATION's lowered request is decorated by the SAME frame interceptor
;; ===========================================================================

(deftest mutation-receives-same-frame-decoration
  (seed-token! "xyz789")
  (reg-auth-interceptor!)
  (rf/reg-mutation :md/save (save-spec) save-spec-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :md/save :params {:slug "w"} :instance :md/save-1}])
  (testing "Spec 016 §Request decoration — a mutation lowers through the SAME
            managed-HTTP seam, so the SAME frame interceptor decorates the
            write with NO per-mutation opt-in"
    (is (= "Token xyz789" (auth-header))
        "the mutation write carries the frame-policy auth header")
    (is (= {:method :put :url "/api/articles/w" :body {:slug "w"}
            :headers {"Authorization" "Token xyz789"}}
           (:request @last-decorated))
        "the domain write body is intact, decorated with the header"))
  (testing "the decorated write settles the instance (composes end-to-end)"
    (reply-success! :on-success {:slug "w"})
    (is (= :success (:status (instance :md/save-1))))))

;; ===========================================================================
;; 3. Decoration that does not apply returns ctx UNCHANGED (EP-0002 read,
;;    no ambient-db fallback, no header when no token)
;; ===========================================================================

(deftest decoration-no-op-when-token-absent
  ;; NO token seeded — the interceptor must read the (empty) frame app-db and
  ;; leave the request untouched (the `cond->` no-token branch).
  (reg-auth-interceptor!)
  (rf/reg-resource :na/article (article-spec) article-spec-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :na/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:lease :na 1]}])
  (testing "Spec 016 §Request decoration — the interceptor reads frame state
            via app-db-value (:frame ctx); with no token it returns ctx
            UNCHANGED (no Authorization header, no ambient-db fallback)"
    (is (nil? (auth-header)) "no header stamped when the frame carries no token")
    (is (= {:method :get :url "/api/articles/w"} (:request @last-decorated))
        "the bare domain request reaches the transport unchanged")))

;; ===========================================================================
;; 4. Decoration COMPOSES with stale suppression — a decorated stale reply
;;    NEVER overwrites newer data (the header stamping leaves the
;;    runtime-owned reply-addressing / work-id / generation boundary intact)
;; ===========================================================================

(deftest decoration-composes-with-stale-suppression
  (seed-token! "tok")
  (reg-auth-interceptor!)
  (rf/reg-resource :sp/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :sp/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :sp/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :sp 1]}])
    (is (= "Token tok" (auth-header)) "gen-1 request decorated")
    (let [gen1-success (:on-success @last-decorated)]
      ;; a forced refetch supersedes gen 1 (gen 2 is now the live work)
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :sp/article :scope :rf.scope/global
                          :params {:slug "w"}}])
      (is (= 2 (:generation (entry k))))
      (is (= "Token tok" (auth-header)) "gen-2 request also decorated")
      (testing "Spec 016 §Stale suppression is mandatory — the STALE,
                fully-decorated gen-1 reply NEVER overwrites the newer entry;
                decoration did not perturb the work-id / generation identity"
        (rf/dispatch-sync (conj gen1-success {:kind :success :value {:stale "data"}}))
        (let [e (entry k)]
          (is (not= {:stale "data"} (:data e)) "stale decorated reply did not write")
          (is (= 2 (:generation e)) "entry still on the newer generation")
          (is (= :loading (:status e)) "gen-2 still in flight"))
        (testing "the CURRENT gen-2 decorated reply lands normally"
          (reply-success! :on-success {:fresh "data"})
          (is (= {:fresh "data"} (:data (entry k)))))))))

;; ===========================================================================
;; 5. Base-URL / url-rewriting decoration reaches the transport
;; ===========================================================================

(deftest base-url-decoration-reaches-transport
  (rf/reg-http-interceptor :test/base-url
    {:frame  :rf/default
     :before (fn [ctx]
               (update-in ctx [:request :url] #(str "https://api.example.com" %)))})
  (rf/reg-resource :bu/article (article-spec) article-spec-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :bu/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:lease :bu 1]}])
  (testing "Spec 016 §Request decoration — a base-URL :before rewrites the
            domain url; the FINAL post-:before request is what ships"
    (is (= "https://api.example.com/api/articles/w"
           (get-in @last-decorated [:request :url]))
        "the resource read's url carries the base-URL prefix")))

;; ===========================================================================
;; 6. The decorated bearer-token VALUE does NOT leak into any trace row
;;    (Spec 016 §Security, Privacy, And Observability — the adversarial
;;    complement of validation rule 13; rf2-9htz7c)
;; ===========================================================================
;;
;; §Security: "Request decoration must not leak sensitive header values into
;; trace rows. Traces should report that an auth interceptor applied, not the
;; bearer token itself." The contract is structural: the resource / mutation
;; runtime lowers the DOMAIN request (pre-decoration) into the trace stream;
;; the header is stamped LATER, inside the managed-HTTP `:before` chain (the
;; transport seam) — runtime-owned addressing, not a trace facet. The positive
;; tests above prove the header IS stamped onto the lowered `:rf.http/managed`
;; request; THIS test is the adversarial complement: it records the full
;; `:rf.resource/*` + `:rf.mutation/*` lifecycle trace stream across a decorated
;; read AND a decorated mutation and asserts the bearer-token VALUE never
;; appears in ANY emitted trace row's serialized form. A regression that started
;; echoing the post-`:before` decorated request (Authorization header and all)
;; into a trace facet would pass every existing decoration test silently — this
;; pins the §Security non-leak against exactly that.

(defn- record-resource+mutation-traces!
  "Run `body-fn`; return the vector of every `:rf.resource/*` / `:rf.mutation/*`
  trace row emitted during it (the lifecycle + lower-fx + settlement ops the
  privacy-leak clause governs)."
  [body-fn]
  (let [seen (atom [])
        k    ::leak-recorder
        resource-or-mutation-op?
        (fn [op]
          (when (keyword? op)
            (let [ns* (namespace op)]
              (contains? #{"rf.resource" "rf.resource.internal"
                           "rf.mutation" "rf.mutation.internal"}
                         ns*))))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (resource-or-mutation-op? (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

(deftest decorated-bearer-token-does-not-leak-into-trace-rows
  (let [token "abc123"]
    (seed-token! token)
    (reg-auth-interceptor!)
    (rf/reg-resource :rl/article (article-spec) article-spec-request)
    (rf/reg-mutation :ml/save (save-spec) save-spec-request)
    (let [rows (record-resource+mutation-traces!
                 (fn []
                   ;; a decorated READ — lower + settle
                   (rf/dispatch-sync [:rf.resource/ensure
                                      {:resource :rl/article :scope :rf.scope/global
                                       :params {:slug "w"} :owner [:lease :rl 1]}])
                   (reply-success! :on-success {:title "Welcome"})
                   ;; a decorated MUTATION — lower + settle
                   (rf/dispatch-sync [:rf.mutation/execute
                                      {:mutation :ml/save :params {:slug "w"}
                                       :instance :ml/save-1}])
                   (reply-success! :on-success {:slug "w"})))]
      (testing "the test actually exercised the decorated lifecycle (the
                interceptor DID stamp the header on the lowered request — so
                the token genuinely entered the seam this trace stream watches)"
        (is (= (str "Token " token) (auth-header))
            "the decoration applied (precondition for a meaningful leak check)")
        (is (seq rows) "resource + mutation lifecycle trace rows were captured"))
      (testing "Spec 016 §Security — the bearer-token VALUE appears in NO
                emitted resource/mutation trace row (the decorated request,
                Authorization header and all, is never echoed into a trace
                facet; traces carry the DOMAIN request pre-decoration only)"
        (let [offenders (filterv (fn [ev] (re-find (re-pattern token) (pr-str ev))) rows)]
          (is (empty? offenders)
              (str "the bearer token '" token "' leaked into " (count offenders)
                   " trace row(s); §Security prohibits sensitive header values "
                   "in trace rows"))))
      (testing "and the literal Authorization-header SPELLING (the stamped
                facet) is likewise absent from every trace row"
        (is (empty? (filterv (fn [ev] (re-find #"Authorization" (pr-str ev))) rows))
            "no trace row carries the stamped Authorization header")))))
