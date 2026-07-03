(ns re-frame.resources-managed-http-cljs-test
  "Managed-HTTP transport for resources (rf2-p19360, Spec 016 §EP-0003
  slice 5 — the transport↔events boundary).

  These JVM+CLJS unit tests pin the slice's semantics:

    1. reply-addressing rejection — an app `:request` MUST NOT supply the
       runtime-owned `:request-id` / `:on-success` / `:on-failure`
       (a loud `:rf.error/resource-reserved-request-key`);
    2. runtime-owned reply addressing — lowering supplies `:request-id` +
       the internal `:on-success` / `:on-failure` reply targets from the
       scoped resource key + generation;
    3. Spec 014 keys pass through — `:decode` / `:accept` / `:retry` ride
       the top level of the managed-HTTP args UNCHANGED (transport retry
       belongs to managed HTTP);
    4. the REAL reply shape — the managed-HTTP transport appends
       `{:status :ok :value <data>}` / `{:status :error :error
       <envelope>}` as the LAST arg of the internal reply event, and the
       runtime reads the decoded data / failure envelope from there;
    5. generation/stale suppression via the real reply shape — a late
       reply carrying a superseded work-id / generation NEVER overwrites
       newer data, even through the full transport reply shape;
    6. dedupe/join while in flight — a second ensure joins (no new
       generation), attaching the owner.

  The transport seam is exercised two ways: `build-managed-args` directly
  (the pure lowering shape + rejection), and end-to-end by overriding the
  `:rf.http/managed` fx with a capturing stub that synthesises the
  transport's reply-event-append shape so the reply handlers run against
  the genuine 3-element event the live transport produces."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting requires: register the :rf.resource/*
   ;; events + subs + the generation cofx/fx these tests dispatch.
   [re-frame.resources]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.state :as state]
   [re-frame.resources.transport.http :as http]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing reply stub below.
   ;; rf2-rak684 — the teardown-abort regression tests seed + assert the REAL
   ;; managed-HTTP in-flight registry (NOT a captured no-op abort), so the
   ;; abort-by-request-id seam (`:http/abort-in-flight!`) runs end-to-end.
   [re-frame.http.managed]
   [re-frame.http.registry :as http-registry]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport that REPLAYS the real reply-append shape ----------
;;
;; The live managed-HTTP transport APPENDS its result to the runtime-
;; supplied `:on-success` / `:on-failure` event vector as the LAST arg
;; (Spec 014 §Reply addressing — `build-reply-event` does `(conj value
;; reply-payload)`). To exercise the runtime's reply handlers against that
;; EXACT 3-element shape without a live Fetch, the stub captures the args and
;; exposes a helper that dispatches the configured reply with the appended
;; transport result.

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override :rf.http/managed with a capturing no-op (binary fx-handler
  signature). The reply is replayed explicitly by the test via
  `reply-success!` / `reply-failure!` so the 3-element internal reply event
  matches what the live transport produces.

  rf2-784223: the shared `make-reset-runtime-fixture`'s
  `:resources/reset-resources!` post-dispose hook already clears the resource
  state cache before this fixture runs — no per-suite reset is repeated here."
  [f]
  (reset! last-managed-args nil)
  ;; rf2-rak684 — the in-flight registry is module-level host state; clear any
  ;; entry a prior test seeded so the teardown-abort regressions start clean.
  (http-registry/clear-all-in-flight!)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f)
  (http-registry/clear-all-in-flight!))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (rf/runtime-db-value frame-id)))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- reply-success!
  "Dispatch the captured `:on-success` reply with the transport's success
  result appended as the LAST arg — exactly the shape the live managed-HTTP
  transport produces (Spec 014 §Reply addressing)."
  [args data]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))

(defn- reply-failure!
  "Dispatch the captured `:on-failure` reply with the transport's failure
  result appended as the LAST arg — the live transport shape."
  [args failure]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

;; ===========================================================================
;; 1. Reply-addressing rejection (the runtime OWNS reply addressing)
;; ===========================================================================

(deftest reserved-reply-keys-rejected
  (testing "Spec 016 §Transport — build-managed-args rejects an app :request
            that supplies the runtime-owned reply-addressing keys"
    (doseq [reserved [:request-id :on-success :on-failure]]
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-reserved-request-key"
            (http/build-managed-args
              {:http-args    {:request {:url "/x"} reserved :sneaky}
               :request-id   [:rf.work/resource [:rf.scope/global :r {}] 1]
               :work-id      [:rf.work/resource [:rf.scope/global :r {}] 1]
               :resource/key [:rf.scope/global :r {}]
               :scope        :rf.scope/global
               :frame-id     :rf/default
               :generation   1}))
          (str reserved " is rejected")))))

(deftest reserved-reply-keys-rejected-end-to-end
  (testing "Spec 016 §Transport — an ensure whose :request supplies a
            reserved reply key is rejected during lowering: the bypass of
            stale suppression is unrepresentable, so the transport is NEVER
            lowered (no managed-HTTP request reaches the wire)"
    (rf/reg-resource :rr/article
                     (article-spec {})
                     (fn [{:keys [slug]} _]
                       {:request {:method :get :url (str "/a/" slug)}
                        ;; an app trying to grab the reply target
                        :on-success [:my/handler]}))
    ;; The reject throws inside the event handler; the cascade routes a
    ;; handler throw into an interceptor-error (it does not re-throw through
    ;; dispatch-sync). The observable fail-closed guarantee: no managed-HTTP
    ;; request was lowered. (The throw shape itself is unit-pinned by
    ;; `reserved-reply-keys-rejected` against `build-managed-args`.)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rr/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :rr 1]}])
    (is (nil? @last-managed-args)
        "a :request with a reserved reply key never reaches the transport")))

;; ===========================================================================
;; 2. Runtime-owned reply addressing + Spec 014 key passthrough
;; ===========================================================================

(deftest lowering-supplies-reply-addressing-and-passes-spec014-keys
  (rf/reg-resource :lo/article
                   (article-spec {})
                   (fn [{:keys [slug]} _]
                     ;; Spec 014 top-level keys MUST pass through
                     {:request {:method :get :url (str "/a/" slug)}
                      :decode  :app/article
                      :accept  identity
                      :retry   {:on #{:rf.http/http-5xx}
                                :max-attempts 3}}))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :lo/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :lo/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :lo 1]}])
    (let [args @last-managed-args]
      (testing "the runtime supplies :request-id + the internal reply targets"
        (is (some? (:request-id args)))
        (is (= [:rf.resource.internal/succeeded] (subvec (:on-success args) 0 1)))
        (is (= [:rf.resource.internal/failed]    (subvec (:on-failure args) 0 1)))
        (testing "the reply payload carries the verification identity (Spec 016
                  §Transport — verify frame + work-id + generation)"
          (let [vp (nth (:on-success args) 1)]
            (is (= scoped-key (:resource/key vp)))
            (is (= (:current-work (entry scoped-key)) (:work/id vp)))
            (is (= 1 (:generation vp)))
            (is (= :rf.scope/global (:scope vp)))
            (is (= :rf/default (:rf.frame/id vp))))))
      (testing "Spec 014 :decode / :accept / :retry ride the top level UNCHANGED
                (transport retry belongs to managed HTTP)"
        (is (= :app/article (:decode args)))
        (is (fn? (:accept args)))
        (is (= {:on #{:rf.http/http-5xx} :max-attempts 3} (:retry args)))
        (is (= {:method :get :url "/a/w"} (:request args))))
      (testing "the runtime did NOT leak the reserved keys into the app
                :request envelope"
        (is (not (contains? (:request args) :request-id)))))))

;; ===========================================================================
;; 3. The REAL transport reply shape (data in arg 3, not arg 2)
;; ===========================================================================

(deftest success-reply-reads-decoded-value-from-transport-result
  (rf/reg-resource :sv/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sv/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :sv/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :sv 1]}])
    (is (= :loading (:status (entry scoped-key))))
    (testing "Spec 014/016 — the decoded data arrives in the APPENDED
              transport result ({:status :ok :value …}), not inline"
      (reply-success! @last-managed-args {:title "Welcome"})
      (let [e (entry scoped-key)]
        (is (= :loaded (:status e)))
        (is (= {:title "Welcome"} (:data e)))
        (is (= #{[:article "w"]} (:tags e)))
        (is (nil? (:current-work e)))))))

(deftest failure-reply-reads-envelope-from-transport-result
  (rf/reg-resource :fe/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :fe/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :fe/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :fe 1]}])
    (testing "Spec 016 §Status semantics — a first-load failure settles
              :error from the APPENDED transport failure envelope"
      (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
      (let [e (entry scoped-key)]
        (is (= :error (:status e)))
        (is (nil? (:data e)))
        (is (= {:kind :rf.http/http-5xx :status 503} (:error e)))))))

(deftest background-refresh-failure-keeps-data-via-transport-shape
  (rf/reg-resource :bg/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :bg/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :bg/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :bg 1]}])
    (reply-success! @last-managed-args {:title "Welcome"})
    (rf/dispatch-sync [:rf.resource/refetch
                       {:resource :bg/article :scope :rf.scope/global
                        :params {:slug "w"}}])
    (is (= :fetching (:status (entry scoped-key))))
    (testing "Spec 016 §Status semantics — a background-refresh failure
              (via the transport shape) returns to :loaded, keeps prior
              :data, records :refresh-error"
      (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
      (let [e (entry scoped-key)]
        (is (= :loaded (:status e)))
        (is (= {:title "Welcome"} (:data e)))
        (is (= {:kind :rf.http/http-5xx :status 503} (:refresh-error e)))
        (is (nil? (:error e)))))))

;; ===========================================================================
;; 4. Stale-reply suppression via the real reply shape (the correctness
;;    boundary — a stale reply MUST NEVER overwrite newer data)
;; ===========================================================================

(deftest stale-transport-reply-suppressed
  (rf/reg-resource :sp/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sp/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :sp/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :sp 1]}])
    (let [gen1-args @last-managed-args]
      ;; a newer refetch forces generation 2 (the prior gen-1 work is now stale)
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :sp/article :scope :rf.scope/global
                          :params {:slug "w"}}])
      (is (= 2 (:generation (entry scoped-key))))
      (testing "Spec 016 §Cancellation is opportunistic; stale suppression is
                mandatory — the STALE gen-1 transport success reply (via the
                full reply shape) NEVER overwrites the newer entry"
        (reply-success! gen1-args {:stale "data"})
        (let [e (entry scoped-key)]
          (is (not= {:stale "data"} (:data e)) "stale reply did not write")
          (is (= 2 (:generation e)) "entry generation unchanged")
          (is (= :loading (:status e)) "still in flight on the current gen"))
        (testing "the CURRENT gen-2 reply lands normally"
          (reply-success! @last-managed-args {:fresh "data"})
          (is (= {:fresh "data"} (:data (entry scoped-key)))))))))

;; ===========================================================================
;; 4b. managed-HTTP ABORT replies are CANCELLATION, not failure (rf2-z70ujl)
;;     — an intentional abort routes through the same :on-failure reply but
;;     must NOT populate :error / :refresh-error or a :failed ledger row; the
;;     work row settles :cancelled and the entry settles to a non-error state.
;;     Tests use the REAL managed-HTTP failure shape ({:kind :rf.http/aborted}).
;; ===========================================================================

(defn- aborted-failure
  "The real managed-HTTP abort failure envelope (Spec 014 §Aborts) the
  transport dispatches through the :on-failure reply for an intentional
  cancellation (`:user` / `:actor-destroyed` / `:timeout`). `:request-id-
  superseded` is NOT used here — the transport suppresses that reply entirely."
  [request-id reason]
  {:kind :rf.http/aborted :request-id request-id :reason reason :actor-id nil})

(deftest first-load-abort-settles-non-error-not-failure
  (rf/reg-resource :ab1/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :ab1/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :ab1/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :ab1 1]}])
    (is (= :loading (:status (entry k))))
    (let [wid (:current-work (entry k))]
      (testing "rf2-z70ujl — a FIRST-load abort reply (the real
                {:kind :rf.http/aborted} envelope) settles to a non-error
                stable state: NOT :error, NO :error envelope, no usable data"
        (reply-failure! @last-managed-args (aborted-failure wid :user))
        (let [e (entry k)]
          (is (not= :error (:status e)) "aborted first-load did NOT settle :error")
          (is (= :idle (:status e)) "settled to a non-error stable :idle state")
          (is (nil? (:error e)) "no error envelope written")
          (is (nil? (:refresh-error e)) "no refresh-error written")
          (is (nil? (:data e)) "no data (the load was cancelled)")
          (is (nil? (:current-work e)) "current-work cleared")))
      (testing "the work row settles terminal :cancelled (not :failed)"
        (let [rec (work-ledger/get-record (runtime-db) wid)]
          (is (= :cancelled (:status rec)) "work row :cancelled")
          (is (= :aborted (:reason (:outcome rec)))))))))

(deftest refresh-abort-preserves-prior-loaded-data
  (rf/reg-resource :ab2/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :ab2/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :ab2/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :ab2 1]}])
    (reply-success! @last-managed-args {:title "Loaded"})
    (rf/dispatch-sync [:rf.resource/refetch
                       {:resource :ab2/article :scope :rf.scope/global
                        :params {:slug "w"}}])
    (is (= :fetching (:status (entry k))))
    (let [wid (:current-work (entry k))
          ;; the revision the in-flight refetch sits at (load START does not bump
          ;; :revision; entry-start-load). An optimistic snapshot taken here would
          ;; record this revision (rf2-mx0w2o).
          rev-before (state/entry-revision (entry k))]
      (testing "rf2-z70ujl — a background-REFRESH abort returns to :loaded,
                PRESERVES prior :data, and records NO :refresh-error (a
                cancelled refresh leaves the last-known-good value intact)"
        (reply-failure! @last-managed-args (aborted-failure wid :actor-destroyed))
        (let [e (entry k)]
          (is (= :loaded (:status e)) "returned to :loaded")
          (is (= {:title "Loaded"} (:data e)) "prior data preserved")
          (is (nil? (:refresh-error e)) "no refresh-error (abort is not a failure)")
          (is (nil? (:error e)))
          (is (nil? (:current-work e)) "current-work cleared")))
      (testing "rf2-mx0w2o — the accepted-cancellation SETTLE bumps :revision
                (an authoritative durable write that cleared :current-work), so
                a later optimistic rollback whose snapshot sat at the in-flight
                revision DETECTS the conflict instead of resurrecting the stale
                in-flight :current-work pointer"
        (is (= (inc rev-before) (state/entry-revision (entry k)))
            "the abort settle moved the per-entry write identity"))
      (testing "the work row settles terminal :cancelled"
        (is (= :cancelled (:status (work-ledger/get-record (runtime-db) wid))))))))

(deftest stale-abort-reply-cannot-mutate-newer-entry
  (rf/reg-resource :ab3/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :ab3/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :ab3/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :ab3 1]}])
    (let [gen1-args @last-managed-args
          gen1-wid  (:current-work (entry k))]
      ;; supersede with a forced refetch → gen 2 is the live work
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :ab3/article :scope :rf.scope/global
                          :params {:slug "w"}}])
      (is (= 2 (:generation (entry k))))
      (testing "rf2-z70ujl — a STALE (gen-1) abort reply NEVER mutates the
                newer gen-2 entry (the live-entry-for-reply boundary
                suppresses it); the gen-2 in-flight attempt is untouched"
        (reply-failure! gen1-args (aborted-failure gen1-wid :user))
        (let [e (entry k)]
          (is (= 2 (:generation e)) "entry still on gen 2")
          (is (= :loading (:status e)) "gen-2 attempt still in flight")
          (is (some? (:current-work e)) "gen-2 work pointer intact"))
        (testing "rf2-jzh5gq — STALE VALIDATION WINS over the natural status:
                  once the reply no longer correlates with a live target the
                  STALE gen-1 work row settles :suppressed, NOT an accepted
                  :cancelled (a stale abort cannot be an accepted cancellation
                  — there is no live target to cancel; the :aborted outcome is
                  kept as a diagnostic)"
          (let [rec (work-ledger/get-record (runtime-db) gen1-wid)]
            (is (= :suppressed (:status rec)))
            (is (= :aborted (:outcome (:outcome rec)))
                "the abort nature is retained as a diagnostic outcome")))))))

(deftest owner-release-orphan-abort-does-not-set-entry-error
  ;; the end-to-end orphan path: an in-flight first load whose last owner is
  ;; released emits a best-effort abort; when that abort reply lands it must
  ;; NOT surface as a resource error (rf2-z70ujl acceptance).
  (rf/reg-resource :ab4/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :ab4/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :ab4/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :ab4 1]}])
    (let [args @last-managed-args
          wid  (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :ab4 1]}])
      (testing "rf2-z70ujl — when the orphaned in-flight attempt's abort reply
                lands it settles cancellation, NOT a user-visible error"
        (reply-failure! args (aborted-failure wid :user))
        (let [e (entry k)]
          (is (not= :error (:status e)) "orphan abort did not set :error status")
          (is (nil? (:error e)) "no error envelope on the entry")
          (is (empty? (:active-owners e)) "still owner-free"))))))

;; ===========================================================================
;; 4c. cross-frame reply isolation (rf2-eu2ifi) — a reply whose stamped
;;     :rf.frame/id does not match the RECEIVING frame is rejected without
;;     mutating that frame's entry or ledger; the verification payload carries
;;     the qualified frame stamp the reply handlers compare against.
;; ===========================================================================

(defn- reply-into-frame!
  "Dispatch a success reply (the real 3-element transport shape) INTO
  `frame-id`, with the verification `payload` (which carries `:rf.frame/id`)."
  [frame-id payload data]
  (rf/dispatch-sync (conj [(nth (:on-success @last-managed-args) 0) payload]
                          {:status :ok :value data})
                    {:frame frame-id}))

(deftest lowering-stamps-receiving-frame-into-reply-payload
  (rf/reg-resource :ff/article (article-spec) article-spec-request)
  (let [fa :ff/frame-a]
    (rf/reg-frame fa {:doc "frame stamp frame A"})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :ff/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :ff 1]}]
                      {:frame fa})
    (testing "rf2-eu2ifi — the reply verification payload stamps the issuing
              frame's qualified :rf.frame/id (so the receiving handler can
              compare it)"
      (let [vp (nth (:on-success @last-managed-args) 1)]
        (is (= fa (:rf.frame/id vp)) "payload carries the issuing frame stamp")))
    (frame/destroy-frame! fa)))

(deftest cross-frame-reply-rejected-without-mutating-receiving-frame
  (rf/reg-resource :xf/article (article-spec) article-spec-request)
  (let [fa :xf/frame-a
        fb :xf/frame-b
        k  (state/scoped-resource-key :rf.scope/global :xf/article {:slug "w"})]
    (rf/reg-frame fa {:doc "xframe A"})
    (rf/reg-frame fb {:doc "xframe B"})
    ;; both frames issue the SAME resource at the SAME generation (gen 1) —
    ;; the collision case the bare-work-id correlation could not tell apart.
    (rf/dispatch-sync [:rf.resource/ensure {:resource :xf/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :a 1]}]
                      {:frame fa})
    (let [a-payload (nth (:on-success @last-managed-args) 1)]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :xf/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :b 1]}]
                        {:frame fb})
      (is (= 1 (:generation (entry fa k))) "frame A entry on gen 1")
      (is (= 1 (:generation (entry fb k))) "frame B entry on gen 1 (same gen — collision case)")
      (testing "rf2-eu2ifi — frame A's reply (payload stamped :rf.frame/id = A)
                dispatched INTO frame B is REJECTED: frame B's entry is not
                mutated (no cross-frame write even at the same work-id/gen)"
        (reply-into-frame! fb a-payload {:title "A-data"})
        (let [eb (entry fb k)]
          (is (not= {:title "A-data"} (:data eb)) "frame B entry NOT written by frame A's reply")
          (is (= :loading (:status eb)) "frame B still in flight (reply rejected)")
          (is (nil? (:data eb)) "frame B has no data")))
      (testing "frame A's reply dispatched into its OWN frame settles normally
                (the frame stamp matches the receiving frame)"
        (reply-into-frame! fa a-payload {:title "A-data"})
        (is (= {:title "A-data"} (:data (entry fa k))) "frame A settled by its own reply")
        (is (= :loaded (:status (entry fa k))))
        (is (= :loading (:status (entry fb k))) "frame B still independently in flight")))
    (frame/destroy-frame! fa)
    (frame/destroy-frame! fb)))

;; ===========================================================================
;; 5. ensure dedupe / join while in flight (attach owner, no new generation)
;; ===========================================================================

(deftest ensure-while-in-flight-joins-and-dedupes
  (rf/reg-resource :dj/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :dj/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :dj/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]}])
    (let [gen1 (:generation (entry scoped-key))
          args-after-first @last-managed-args]
      (reset! last-managed-args nil)
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :dj/article :scope :rf.scope/global
                          :params {:slug "w"} :owner [:lease :x 2]}])
      (testing "Spec 016 §Race — a second ensure while in flight JOINS: no
                new generation, no second transport lowering, owner attached"
        (let [e (entry scoped-key)]
          (is (= gen1 (:generation e)) "no new generation on dedupe/join")
          (is (contains? (:active-owners e) [:route :r 1]))
          (is (contains? (:active-owners e) [:lease :x 2])))
        (is (nil? @last-managed-args)
            "the join did NOT lower a second managed-HTTP request"))
      (testing "the single in-flight reply satisfies the joined owners"
        (reply-success! args-after-first {:title "Joined"})
        (is (= :loaded (:status (entry scoped-key))))
        (is (= {:title "Joined"} (:data (entry scoped-key))))))))

;; ===========================================================================
;; 6. OUT-OF-CASCADE teardown aborts the underlying managed-HTTP request
;;    (rf2-rak684)
;;
;;    The in-cascade lifecycle events (:rf.resource/remove, clear-scope,
;;    refetch supersession, owner-release) abort the underlying managed-HTTP
;;    request by emitting :rf.http/managed-abort via `work-ledger/abort-fx`
;;    (covered by the suites above + the work-ledger suite). The OUT-of-cascade
;;    lifecycle paths run no cascade:
;;
;;      - `clear-resource`  (registry/dispose-resource-runtime! →
;;                           work-ledger/opportunistic-abort!)
;;      - frame destroy     (resources/release-resources-host-caches! →
;;                           work-ledger/release-frame! → abort-slot!)
;;
;;    Before rf2-rak684 these only fired the side-table `:abort-fn` (nil for
;;    managed HTTP, whose AbortController is host-owned), so they dropped the
;;    Resources-side work handle while leaving the underlying managed request
;;    ALIVE. The fix routes them through `work-ledger/abort-handle!`, which —
;;    for a managed-HTTP slot — fires the abort-by-request-id seam
;;    (`re-frame.http.registry/abort-in-flight!`) through the published
;;    `:http/abort-in-flight!` late-bind hook, by the SAME frame-qualified
;;    request-id (`[:rf.req <frame-id> <work-id>]`) the lower registered.
;;
;;    These regressions seed + assert the REAL managed-HTTP in-flight registry
;;    (NOT a captured no-op abort), so the seam runs end-to-end and the abort
;;    must leave NO live HTTP in-flight entry.
;; ===========================================================================

(defn- seed-in-flight!
  "Seed the REAL managed-HTTP in-flight registry under `request-id`, mimicking
  what the live transport's `run-attempt!` does (`record-in-flight!` with an
  `:abort-fn` whose firing clears the registry — production's abort-fn closure
  reaches `finalise-failure!` → `clear-in-flight!`). The `:abort-fn` records the
  abort reason into `recorder` so the test can assert WHICH reason fired and
  that exactly one abort happened. Returns the recorder atom."
  [request-id recorder]
  (http-registry/record-in-flight!
    request-id nil
    {:abort-fn (fn [reason]
                 (swap! recorder conj [request-id reason])
                 (http-registry/clear-in-flight! request-id))})
  recorder)

(deftest clear-resource-aborts-managed-http-in-flight
  ;; rf2-rak684 — clear-resource MUST abort the underlying managed-HTTP request
  ;; for an in-flight resource (Spec 016 §clear-resource MUST-dispose +
  ;; §Cancellation is opportunistic), not just clear the work-handle side-table
  ;; slot. The abort fires by the frame-qualified request-id and the HTTP
  ;; in-flight registry entry is gone afterwards.
  (rf/reg-resource :crab/article (article-spec) article-spec-request)
  (let [k (state/scoped-resource-key :rf.scope/global :crab/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :crab/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :crab 1]}])
    (let [wid        (:current-work (entry k))
          request-id (work-ledger/managed-request-id :rf/default wid)
          aborted    (seed-in-flight! request-id (atom []))]
      (testing "the managed-HTTP request is in flight + the work-handle slot
                records the frame-qualified request-id (no live :abort-fn —
                the transport owns the AbortController)"
        (is (some? (http-registry/lookup-in-flight request-id)) "request in flight")
        (let [handle (work-ledger/get-handle :rf/default wid)]
          (is (= :rf.http/managed (:transport handle)))
          (is (= request-id (:request-id handle)))
          (is (nil? (:abort-fn handle)) "managed-HTTP slot carries NO direct abort-fn")))
      (registry/clear-resource :crab/article)
      (testing "rf2-rak684 — clear-resource aborts the underlying managed-HTTP
                request by [:rf.req frame-id work-id] (not just the side-table slot)"
        (is (= [[request-id :resource-superseded]] @aborted)
            "exactly one abort fired, by the frame-qualified request-id")
        (is (nil? (http-registry/lookup-in-flight request-id))
            "no live HTTP in-flight entry remains")
        (is (nil? (work-ledger/get-handle :rf/default wid))
            "the work-handle side-table slot is dropped")))))

(deftest frame-destroy-aborts-managed-http-in-flight
  ;; rf2-rak684 — frame destroy MUST abort any in-flight managed-HTTP resource
  ;; work for the frame BEFORE dropping the generation high-water (Spec 016
  ;; [Runtime-Subsystems] clause 5). A surviving host request would otherwise
  ;; outlive the frame and could satisfy a future same-id frame's reply gate.
  (rf/reg-resource :fdab/article (article-spec) article-spec-request)
  (let [fa :fdab/frame-a
        k  (state/scoped-resource-key :rf.scope/global :fdab/article {:slug "w"})]
    (rf/reg-frame fa {:doc "teardown-abort frame"})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fdab/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fdab 1]}]
                      {:frame fa})
    (let [wid        (:current-work (entry fa k))
          request-id (work-ledger/managed-request-id fa wid)
          aborted    (seed-in-flight! request-id (atom []))]
      (testing "before destroy: request in flight, handle + generation high-water present"
        (is (some? (http-registry/lookup-in-flight request-id)) "request in flight")
        (is (some? (work-ledger/get-handle fa wid)) "work-handle slot present")
        (is (pos? (state/generation-snapshot fa)) "generation high-water present"))
      (frame/destroy-frame! fa)
      (testing "rf2-rak684 — frame destroy aborts the underlying managed-HTTP
                request by [:rf.req frame-id work-id] and leaves no live in-flight entry"
        (is (= [[request-id :resource-superseded]] @aborted)
            "exactly one abort fired, by the frame-qualified request-id")
        (is (nil? (http-registry/lookup-in-flight request-id))
            "no live HTTP in-flight entry survives frame destroy")
        (is (nil? (work-ledger/get-handle fa wid)) "host handle dropped")
        (is (zero? (state/generation-snapshot fa)) "generation high-water dropped")))))

(deftest same-frame-id-reuse-old-reply-cannot-mutate-new-frame
  ;; rf2-rak684 — the structural-safety case the teardown abort protects, and
  ;; WHY the abort (not stale suppression) is load-bearing here.
  ;;
  ;; Same-id frame re-registration is supported, and frame destroy DROPS the
  ;; destroyed frame's generation high-water (state/release-frame!), so a
  ;; re-registered frame mints generation 1 AGAIN for the same scoped key. The
  ;; work-id `[:rf.work/resource <scoped-key> <generation>]` embeds ONLY the
  ;; scoped key + generation (it is frame-LOCAL), so the OLD incarnation's
  ;; work-id and the NEW incarnation's work-id are EQUAL — and the reused frame
  ;; id makes the reply's stamped :rf.frame/id match too. The stale-suppression
  ;; gate (`live-entry-for-reply`: frame stamp + work-id + generation) therefore
  ;; CANNOT distinguish an old-incarnation reply from a new-incarnation one.
  ;;
  ;; The ONLY structural protection is that the surviving managed request is
  ;; ABORTED on frame destroy (rf2-rak684), so it never delivers a late
  ;; :on-success — an aborted request fires :on-failure (:rf.http/aborted),
  ;; never the success the new frame would otherwise have accepted. This test
  ;; models the real transport: the seeded request's abort-fn delivers the
  ;; abort's :on-failure reply (what the live transport does on cancel), and we
  ;; assert that reply does NOT mutate the re-registered frame's fresh entry.
  (rf/reg-resource :rab/article (article-spec) article-spec-request)
  (let [fr :rab/reused
        k  (state/scoped-resource-key :rf.scope/global :rab/article {:slug "w"})]
    ;; ---- first incarnation: start a load; capture the reply addressing ----
    (rf/reg-frame fr {:doc "reuse frame — first incarnation"})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rab/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rab 1]}]
                      {:frame fr})
    (let [old-payload    (nth (:on-success @last-managed-args) 1)
          old-wid        (:current-work (entry fr k))
          old-request-id (work-ledger/managed-request-id fr old-wid)
          ;; seed the REAL in-flight registry with an abort-fn that, like the
          ;; live transport's cancel path, clears the registry when fired (the
          ;; production abort-fn closure reaches finalise-failure! →
          ;; clear-in-flight!). `delivered` records that the abort fired.
          delivered      (atom [])
          _              (http-registry/record-in-flight!
                           old-request-id nil
                           {:abort-fn (fn [reason]
                                        (swap! delivered conj [old-request-id reason])
                                        (http-registry/clear-in-flight! old-request-id))})]
      (is (= 1 (:generation (entry fr k))) "first incarnation on generation 1")
      (is (some? (http-registry/lookup-in-flight old-request-id)) "old request in flight")
      ;; ---- destroy + re-register the SAME frame id ----
      (frame/destroy-frame! fr)
      (testing "rf2-rak684 — destroy aborts the surviving managed request"
        (is (= [[old-request-id :resource-superseded]] @delivered)
            "the old request was aborted on destroy")
        (is (nil? (http-registry/lookup-in-flight old-request-id))
            "no surviving in-flight request to deliver a late success"))
      (reset! last-managed-args nil)
      (rf/reg-frame fr {:doc "reuse frame — second incarnation"})
      (rf/dispatch-sync [:rf.resource/ensure {:resource :rab/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :rab 2]}]
                        {:frame fr})
      (let [new-wid     (:current-work (entry fr k))
            new-payload (nth (:on-success @last-managed-args) 1)]
        (is (= 1 (:generation (entry fr k)))
            "second incarnation mints generation 1 again (high-water was dropped)")
        (testing "WHY the abort is load-bearing: the old + new work-ids are EQUAL
                  (the work-id is frame-LOCAL — same scoped-key + gen 1) and the
                  reused frame id makes the reply's :rf.frame/id stamp match too,
                  so the stale-suppression gate CANNOT distinguish an old reply
                  from a new one. Only the destroy-time abort prevents the old
                  request from ever delivering into the re-registered frame."
          (is (= old-wid new-wid) "old + new work-ids collide")
          (is (= fr (:rf.frame/id old-payload) (:rf.frame/id new-payload))
              "both incarnations stamp the SAME (reused) frame id")
          (is (= (:work/id old-payload) (:work/id new-payload))
              "the reply verification work-ids collide too"))
        (is (= :loading (:status (entry fr k))) "second incarnation in flight")
        (testing "rf2-rak684 — the old request was aborted on destroy and is gone
                  from the registry, so NO surviving request can deliver a late
                  success into the re-registered frame (the only structural
                  protection, since the gate can't tell the replies apart)"
          (is (nil? (http-registry/lookup-in-flight old-request-id))
              "the colliding old request cannot deliver any reply"))
        (testing "the NEW incarnation's OWN reply settles it normally"
          (reply-into-frame! fr new-payload {:title "FRESH"})
          (is (= {:title "FRESH"} (:data (entry fr k))) "new reply settles the new entry")
          (is (= :loaded (:status (entry fr k)))))))
    (frame/destroy-frame! fr)))
