(ns re-frame.resources-managed-http-cljs-test
  "Managed-HTTP transport for resources (Spec 016 §Transport — the
  transport↔events boundary). The runtime owns reply addressing (an app
  `:request` supplying `:request-id` / `:on-success` / `:on-failure` is
  refused) and passes Spec 014's `:decode` / `:accept` / `:retry` through; the
  reply handlers read the transport result appended as the LAST arg; an abort
  reply is cancellation, not failure; a reply stamped for another frame is
  rejected; an ensure in flight joins; and an out-of-cascade teardown aborts
  the underlying managed request.

  The seam is exercised through `build-managed-args` directly and end to end
  through a capturing `:rf.http/managed` stub whose captured args the test
  replays in the live transport's append shape (Spec 014 §Reply addressing)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   ;; load-bearing side-effecting requires: register the :rf.resource/*
   ;; events + subs + the generation cofx/fx these tests dispatch.
   [re-frame.resources]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.transport.http :as rf.resources.transport.http]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the teardown-abort tests seed and assert the REAL in-flight registry
   [re-frame.http.managed]
   [re-frame.http.registry :as rf.http.registry]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  ;; the in-flight registry is module-level host state
  (rf.http.registry/clear-all-in-flight!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f)
  (rf.http.registry/clear-all-in-flight!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (:rf.db/runtime (rf/frame-state-value frame-id)) (rf.resources.state/entry-path scoped-key))))

(defn- reply-success! [args data]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))

(defn- reply-failure! [args failure]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(defn- work-record [work-id] (rf.resources.work-ledger/get-record (runtime-db) work-id))

(defn- article-spec []
  {:scope         :rf.scope/global
   :params-schema [:map [:slug :string]]
   :tags          (fn [{:keys [slug]} _data] #{[:article slug]})})

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- article-key [resource]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource {:slug "w"}))

(defn- ensure-article!
  "Ensure the registered `resource` under `owner` in `frame-id`; returns its key."
  ([resource] (ensure-article! resource [:app resource 1]))
  ([resource owner] (ensure-article! resource owner :rf/default))
  ([resource owner frame-id]
   (rf/dispatch-sync [:rf.resource/ensure {:resource resource :scope :rf.scope/global
                                           :params {:slug "w"} :owner owner}]
                     {:frame frame-id})
   (article-key resource)))

(defn- refetch-article! [resource]
  (rf/dispatch-sync [:rf.resource/refetch {:resource resource :scope :rf.scope/global :params {:slug "w"}}]))

(def ^:private http-503 {:kind :rf.http/http-5xx :status 503})

;; ===========================================================================
;; the runtime owns reply addressing; Spec 014 keys pass through
;; ===========================================================================

(deftest reserved-reply-keys-rejected
  (doseq [reserved [:request-id :on-success :on-failure]]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-reserved-request-key"
          (rf.resources.transport.http/build-managed-args
            {:http-args    {:request {:url "/x"} reserved :sneaky}
             :request-id   [:rf.work/resource [:rf.scope/global :r {}] 1]
             :work-id      [:rf.work/resource [:rf.scope/global :r {}] 1]
             :resource/key [:rf.scope/global :r {}]
             :scope        :rf.scope/global
             :frame-id     :rf/default
             :generation   1}))
        (str reserved " is rejected"))))

(deftest reserved-reply-keys-rejected-end-to-end
  ;; the handler throw is routed to the runtime error path, so the observable
  ;; fail-closed guarantee is that no request reaches the transport
  (rf/reg-resource :rr/article
                   (article-spec)
                   (fn [{:keys [slug]} _]
                     {:request {:method :get :url (str "/a/" slug)}
                      :on-success [:my/handler]}))
  (ensure-article! :rr/article)
  (is (nil? @last-managed-args) "a :request with a reserved reply key never reaches the transport"))

(deftest lowering-supplies-reply-addressing-and-passes-spec014-keys
  (rf/reg-resource :lo/article
                   (article-spec)
                   (fn [{:keys [slug]} _]
                     {:request {:method :get :url (str "/a/" slug)}
                      :decode  :app/article
                      :accept  identity
                      :retry   {:on #{:rf.http/http-5xx} :max-attempts 3}}))
  (let [k    (ensure-article! :lo/article)
        args @last-managed-args
        vp   (nth (:on-success args) 1)]
    (is (= [true :rf.resource.internal/succeeded :rf.resource.internal/failed]
           [(some? (:request-id args)) (first (:on-success args)) (first (:on-failure args))])
        "the runtime supplies the request id and the internal reply targets")
    (is (= {:resource/key k :work/id (:current-work (entry k)) :generation 1
            :scope :rf.scope/global :rf.frame/id :rf/default}
           (select-keys vp [:resource/key :work/id :generation :scope :rf.frame/id]))
        "the reply payload carries the frame + work-id + generation verification identity")
    (is (= [:app/article true {:on #{:rf.http/http-5xx} :max-attempts 3} {:method :get :url "/a/w"}]
           [(:decode args) (fn? (:accept args)) (:retry args) (:request args)])
        "Spec 014's :decode / :accept / :retry ride the top level unchanged")))

;; ===========================================================================
;; the reply handlers read the appended transport result
;; ===========================================================================

(deftest success-reply-reads-decoded-value-from-transport-result
  (rf/reg-resource :sv/article (article-spec) article-spec-request)
  (let [k (ensure-article! :sv/article)]
    (reply-success! @last-managed-args {:title "Welcome"})
    (is (= [:loaded {:title "Welcome"} #{[:article "w"]} nil]
           ((juxt :status :data :tags :current-work) (entry k))))))

(deftest failure-reply-reads-envelope-from-transport-result
  (rf/reg-resource :fe/article (article-spec) article-spec-request)
  (let [k (ensure-article! :fe/article)]
    (reply-failure! @last-managed-args http-503)
    (is (= [:error nil http-503] ((juxt :status :data :error) (entry k)))
        "a first-load failure settles :error from the appended envelope")))

(deftest background-refresh-failure-keeps-data-via-transport-shape
  (rf/reg-resource :bg/article (article-spec) article-spec-request)
  (let [k (ensure-article! :bg/article)]
    (reply-success! @last-managed-args {:title "Welcome"})
    (refetch-article! :bg/article)
    (is (= :fetching (:status (entry k))))
    (reply-failure! @last-managed-args http-503)
    (is (= [:loaded {:title "Welcome"} http-503 nil] ((juxt :status :data :refresh-error :error) (entry k)))
        "a refresh failure returns to :loaded, keeps the data and records :refresh-error")))

;; ===========================================================================
;; a managed-HTTP abort reply is cancellation, not failure
;; ===========================================================================

(defn- aborted-failure
  "The real managed-HTTP abort envelope (Spec 014 §Aborts) the transport
  dispatches through :on-failure for an intentional cancellation."
  [request-id reason]
  {:kind :rf.http/aborted :request-id request-id :reason reason :actor-id nil})

(deftest first-load-abort-settles-non-error-not-failure
  (rf/reg-resource :ab1/article (article-spec) article-spec-request)
  (let [k   (ensure-article! :ab1/article)
        wid (:current-work (entry k))]
    (reply-failure! @last-managed-args (aborted-failure wid :user))
    (is (= [:idle nil nil nil nil] ((juxt :status :error :refresh-error :data :current-work) (entry k)))
        "a first-load abort settles a non-error :idle with no error and no data")
    (is (= [:cancelled :aborted] ((juxt :status (comp :reason :outcome)) (work-record wid)))
        "the work row settles terminal :cancelled, not :failed")))

(deftest refresh-abort-preserves-prior-loaded-data
  (rf/reg-resource :ab2/article (article-spec) article-spec-request)
  (let [k (ensure-article! :ab2/article)]
    (reply-success! @last-managed-args {:title "Loaded"})
    (refetch-article! :ab2/article)
    (is (= :fetching (:status (entry k))))
    ;; load start does not bump :revision, so an optimistic snapshot taken now
    ;; would record this one
    (let [wid        (:current-work (entry k))
          rev-before (rf.resources.state/entry-revision (entry k))]
      (reply-failure! @last-managed-args (aborted-failure wid :actor-destroyed))
      (is (= [:loaded {:title "Loaded"} nil nil nil]
             ((juxt :status :data :refresh-error :error :current-work) (entry k)))
          "a refresh abort returns to :loaded with the last good data and no :refresh-error")
      ;; the settle is an authoritative write that cleared :current-work, so a
      ;; later rollback whose snapshot sat at the in-flight revision detects it
      (is (= [(inc rev-before) :cancelled]
             [(rf.resources.state/entry-revision (entry k)) (:status (work-record wid))])
          "the settle bumps :revision and the row settles :cancelled"))))

(deftest stale-abort-reply-cannot-mutate-newer-entry
  (rf/reg-resource :ab3/article (article-spec) article-spec-request)
  (let [k         (ensure-article! :ab3/article)
        gen1-args @last-managed-args
        gen1-wid  (:current-work (entry k))]
    (refetch-article! :ab3/article)
    (is (= 2 (:generation (entry k))) "FIXTURE — gen 2 is the live work")
    (reply-failure! gen1-args (aborted-failure gen1-wid :user))
    (is (= [2 :loading true] [(:generation (entry k)) (:status (entry k)) (some? (:current-work (entry k)))])
        "the stale gen-1 abort leaves the gen-2 attempt untouched")
    ;; stale validation wins over the natural status: with no live target
    ;; there is nothing to cancel, so the row is :suppressed, its :aborted
    ;; nature kept as a diagnostic
    (is (= [:suppressed :aborted] ((juxt :status (comp :outcome :outcome)) (work-record gen1-wid))))))

(deftest owner-release-orphan-abort-does-not-set-entry-error
  ;; releasing the last owner of an in-flight first load emits a best-effort
  ;; abort, whose reply must not surface as a resource error
  (rf/reg-resource :ab4/article (article-spec) article-spec-request)
  (let [k    (ensure-article! :ab4/article [:app :ab4 1])
        args @last-managed-args
        wid  (:current-work (entry k))]
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :ab4 1]}])
    (reply-failure! args (aborted-failure wid :user))
    (let [e (entry k)]
      (is (= [false nil true] [(= :error (:status e)) (:error e) (empty? (:active-owners e))])))))

;; ===========================================================================
;; cross-frame reply isolation, and joining an ensure in flight
;; ===========================================================================

(defn- reply-into-frame!
  "Dispatch a success reply INTO `frame-id` with the verification `payload`."
  [frame-id payload data]
  (rf/dispatch-sync (conj [(nth (:on-success @last-managed-args) 0) payload]
                          {:status :ok :value data})
                    {:frame frame-id}))

(deftest cross-frame-reply-rejected-without-mutating-receiving-frame
  (rf/reg-resource :xf/article (article-spec) article-spec-request)
  (let [fa :xf/frame-a
        fb :xf/frame-b
        k  (article-key :xf/article)]
    (rf/make-frame {:id fa :doc "xframe A"})
    (rf/make-frame {:id fb :doc "xframe B"})
    ;; both frames issue the same resource at the same generation: the
    ;; collision a bare work-id correlation cannot tell apart
    (ensure-article! :xf/article [:app :a 1] fa)
    (let [a-payload (nth (:on-success @last-managed-args) 1)]
      (ensure-article! :xf/article [:app :b 1] fb)
      (is (= [1 1] [(:generation (entry fa k)) (:generation (entry fb k))]) "FIXTURE — both on gen 1")
      (reply-into-frame! fb a-payload {:title "A-data"})
      (is (= [:loading nil] ((juxt :status :data) (entry fb k)))
          "frame A's reply dispatched into frame B is rejected, even at the same work-id and generation")
      (reply-into-frame! fa a-payload {:title "A-data"})
      (is (= [:loaded {:title "A-data"} :loading]
             [(:status (entry fa k)) (:data (entry fa k)) (:status (entry fb k))])
          "into its own frame it settles normally, frame B still independently in flight"))
    (rf.frame/destroy-frame! fa)
    (rf.frame/destroy-frame! fb)))

(deftest ensure-while-in-flight-joins-and-dedupes
  (rf/reg-resource :dj/article (article-spec) article-spec-request)
  (let [k                (ensure-article! :dj/article [:route :r 1])
        gen1             (:generation (entry k))
        args-after-first @last-managed-args]
    (reset! last-managed-args nil)
    (ensure-article! :dj/article [:app :x 2])
    (is (= [gen1 #{[:route :r 1] [:app :x 2]} nil]
           [(:generation (entry k)) (:active-owners (entry k)) @last-managed-args])
        "a second ensure in flight joins: no new generation, no second request, the owner attached")
    (reply-success! args-after-first {:title "Joined"})
    (is (= [:loaded {:title "Joined"}] ((juxt :status :data) (entry k)))
        "the single reply satisfies the joined owners")))

;; ===========================================================================
;; out-of-cascade teardown aborts the underlying managed-HTTP request
;; ===========================================================================
;;
;; `clear-resource` and frame destroy run no cascade, so they route through
;; `rf.resources.work-ledger/abort-handle!`, which for a managed-HTTP slot fires
;; the abort-by-request-id seam (`:http/abort-in-flight!`) by the same
;; frame-qualified request-id the lowering registered. A managed slot carries no
;; `:abort-fn` (the transport owns the AbortController), so dropping only the
;; side-table slot would leave the request alive.

(defn- seed-in-flight!
  "Seed the REAL in-flight registry under `request-id`, as the live transport
  does, with an `:abort-fn` that records its reason into the returned atom and
  clears the registry as production's abort does."
  [request-id]
  (let [recorder (atom [])]
    (rf.http.registry/record-in-flight!
      request-id nil
      {:abort-fn (fn [reason]
                   (swap! recorder conj [request-id reason])
                   (rf.http.registry/clear-in-flight! request-id))})
    recorder))

(deftest clear-resource-aborts-managed-http-in-flight
  (rf/reg-resource :crab/article (article-spec) article-spec-request)
  (let [k          (ensure-article! :crab/article)
        wid        (:current-work (entry k))
        request-id (rf.resources.work-ledger/managed-request-id :rf/default wid)
        aborted    (seed-in-flight! request-id)]
    (is (= [true :rf.http/managed request-id nil]
           (into [(some? (rf.http.registry/lookup-in-flight request-id))]
                 ((juxt :transport :request-id :abort-fn) (rf.resources.work-ledger/get-handle :rf/default wid))))
        "FIXTURE — in flight, the slot recording the frame-qualified request id and no direct abort-fn")
    (rf.resources.registry/clear-resource :crab/article)
    (is (= [[[request-id :resource-superseded]] nil nil]
           [@aborted (rf.http.registry/lookup-in-flight request-id) (rf.resources.work-ledger/get-handle :rf/default wid)])
        "exactly one abort fires by request id, leaving no live in-flight entry and no slot")))

(deftest frame-destroy-aborts-managed-http-in-flight
  ;; the abort happens before the generation high-water drops: a surviving
  ;; request could otherwise satisfy a same-id successor frame's reply gate,
  ;; whose work-ids collide with this incarnation's
  (rf/reg-resource :fdab/article (article-spec) article-spec-request)
  (let [fa :fdab/frame-a]
    (rf/make-frame {:id fa :doc "teardown-abort frame"})
    (let [k          (ensure-article! :fdab/article [:app :fdab 1] fa)
          wid        (:current-work (entry fa k))
          request-id (rf.resources.work-ledger/managed-request-id fa wid)
          aborted    (seed-in-flight! request-id)]
      (is (= [true true true]
             [(some? (rf.http.registry/lookup-in-flight request-id)) (some? (rf.resources.work-ledger/get-handle fa wid))
              (pos? (rf.resources.state/generation-snapshot fa))])
          "FIXTURE — in flight, with a handle and a generation high-water")
      (rf.frame/destroy-frame! fa)
      (is (= [[[request-id :resource-superseded]] nil nil 0]
             [@aborted (rf.http.registry/lookup-in-flight request-id) (rf.resources.work-ledger/get-handle fa wid)
              (rf.resources.state/generation-snapshot fa)])
          "exactly one abort fires by request id; no in-flight entry, handle or high-water survives"))))
