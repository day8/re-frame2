(ns re-frame.resources-time-ms-cofx-cljs-test
  "EP-0017 cofx contract for the resource / mutation TIME-consuming handlers.

  Two properties:

    Declared time — every resource / mutation handler that consumes the
    framework-stamped causal time fact `:rf/time-ms` (for a replay-relevant
    freshness decision or a durable timestamp) MUST DECLARE it via
    `:rf.cofx/requires` so it is DELIVERED FLAT (`(:rf/time-ms coeffects)`)
    under EP-0017 declared-only delivery — never reached through the whole
    `:rf.cofx` token. This suite pins that `handler-meta` exposes the
    `:rf/time-ms` requirement for each such event. The durable time-bearing
    writes that consume the flat fact are pinned beside each write: the
    `:loaded-at` / `:stale-at` in the runtime suite, the work-ledger
    `:started-at` in the work-ledger suite, and `:invalidated-at` in the
    invalidation suite.

    Failure completion time — a FAILED / CANCELLED resource completion is still a
    managed-async completion with a reply token, so its causal completion time
    rides the reply token's `:rf/time-ms` and is carried as `:completed-at`
    onto the canonical reply AND into the terminal work-ledger outcome —
    symmetric with the success path + with mutation replies. The accepted
    failure and abort paths are pinned by the work-ledger suite's
    `failed-settles-record-terminal` / `aborted-settles-record-cancelled`;
    this suite pins the SUPPRESSED (superseded) failure.

  Canonical contract: `spec/009-Instrumentation.md` / `spec/Spec-Schemas.md`
  (the `:rf.cofx/requires` declaration), `spec/016-Resources.md` (the resource
  reply / work-ledger time facts), `spec/Managed-Effects.md` §The uniform
  reply envelope. The recordable-cofx delivery contract is in
  `re-frame.cofx/deliver-declared-cofx`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* + :rf.mutation/* events with the meta under test.
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   ;; production HTTP fx surface (the transport feature probe); the actual
   ;; fetch is overridden by the capturing no-op below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a capturing no-op so ensure /
  refetch / execute land deterministically without a live fetch."
  [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- record [wid] (rf.resources.work-ledger/get-record (runtime-db) wid))

(defn- article-spec []
  {:scope          :rf.scope/global
   :params-schema  [:map [:slug :string]]
   :stale-after-ms 60000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- mutation-spec []
  {:scope   :rf.scope/global})

(def ^:private mutation-spec-request
  (fn [_args _ctx] {:request {:method :post :url "/api/save"}}))

;; ===========================================================================
;; handler-meta declares :rf/time-ms for the time-consuming
;; handlers, delivered flat under EP-0017 declared-only delivery.
;; ===========================================================================

(def ^:private time-consuming-resource-events
  "Every resource / mutation event whose handler consumes the causal
  `:rf/time-ms` fact — for a freshness decision or a durable timestamp."
  [:rf.resource/ensure
   :rf.resource/refetch
   :rf.resource/invalidate-tags
   :rf.resource/window-focused
   :rf.resource/network-reconnected
   :rf.resource.internal/succeeded
   :rf.resource.internal/failed
   :rf.resource.internal/stale-fired
   :rf.mutation/execute
   :rf.mutation.internal/succeeded
   :rf.mutation.internal/failed])

(deftest time-consuming-handlers-declare-time-ms-cofx
  (testing "every time-consuming resource / mutation handler
            DECLARES `:rf/time-ms` in `:rf.cofx/requires`, so handler-meta
            exposes the dependency and the runtime delivers it FLAT (EP-0017
            declared-only delivery — nothing implicit, including `:rf/time-ms`)"
    (doseq [event-id time-consuming-resource-events]
      (let [meta     (rf/handler-meta {:source :store :kind :event :id event-id})
            requires (:rf.cofx/requires meta)]
        (is (some? meta) (str event-id " is registered"))
        (is (contains? (set requires) :rf/time-ms)
            (str event-id " declares :rf/time-ms in :rf.cofx/requires (was "
                 (pr-str requires) ")"))))))

(deftest load-causing-handlers-declare-generation-allocation
  (testing "a load-causing event declares the recordable generation-allocation
            cofx (its `:rf/time-ms` is pinned with every time-consuming event
            above)"
    (doseq [event-id [:rf.resource/ensure :rf.resource/refetch :rf.mutation/execute]]
      (let [requires (set (:rf.cofx/requires (rf/handler-meta {:source :store :kind :event :id event-id})))]
        (is (contains? requires :rf.resource/generation-allocation)
            (str event-id " declares the generation-allocation cofx"))))))

;; ===========================================================================
;; failure / cancellation replies carry the causal :completed-at.
;; ===========================================================================

(deftest stale-suppressed-failure-carries-completed-at
  (testing "a SUPPRESSED (superseded) failure reply records the
            causal :completed-at in its terminal :suppressed outcome too"
    (rf/reg-resource :sf/article (article-spec) article-spec-request)
    (let [scoped-key   (rf.resources.state/scoped-resource-key :rf.scope/global :sf/article {:slug "w"})
          completed-at 1781444444444]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :sf/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:app :sf 1]}])
      (let [wid1 (:current-work (entry scoped-key))]
        ;; supersede with a newer generation (the old work-id is now stale)
        (rf/dispatch-sync [:rf.resource/refetch {:resource :sf/article :scope :rf.scope/global
                                                 :params {:slug "w"}}])
        ;; the stale (old-generation) failure reply is suppressed
        (rf/dispatch-sync [:rf.resource.internal/failed
                           {:resource/key scoped-key :work/id wid1 :generation 1
                            :error {:kind :rf.http/http-5xx :status 503}}]
                          {:rf.cofx {:rf/time-ms completed-at}})
        (is (= :suppressed (:status (record wid1))))
        (is (= completed-at (:completed-at (:outcome (record wid1))))
            "the suppressed terminal outcome carries the causal :completed-at")))))
