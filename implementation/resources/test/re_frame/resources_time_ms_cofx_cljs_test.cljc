(ns re-frame.resources-time-ms-cofx-cljs-test
  "EP-0017 cofx contract for the resource / mutation TIME-consuming handlers
  (rf2-601ife + rf2-rl27r2).

  Two findings from the EP-0017 cofx review wave:

    rf2-601ife — every resource / mutation handler that consumes the
    framework-stamped causal time fact `:rf/time-ms` (for a replay-relevant
    freshness decision or a durable timestamp) MUST DECLARE it via
    `:rf.cofx/requires` so it is DELIVERED FLAT (`(:rf/time-ms coeffects)`)
    under EP-0017 declared-only delivery — never reached through the whole
    `:rf.cofx` token. This suite pins that `handler-meta` exposes the
    `:rf/time-ms` requirement for each such event, and that the durable
    time-bearing writes still land when the time fact is delivered flat (the
    runtime stages exactly the declared leaves).

    rf2-rl27r2 — a FAILED / CANCELLED resource completion is still a
    managed-async completion with a reply token, so its causal completion time
    rides the reply token's `:rf/time-ms` and is carried as `:completed-at`
    onto the canonical reply AND into the terminal work-ledger outcome —
    symmetric with the success path + with mutation replies. This suite pins
    that the resource failure / abort paths preserve `:completed-at`.

  Canonical contract: `spec/009-Instrumentation.md` / `spec/Spec-Schemas.md`
  (the `:rf.cofx/requires` declaration), `spec/016-Resources.md` (the resource
  reply / work-ledger time facts), `spec/Managed-Effects.md` §The uniform
  reply envelope. The recordable-cofx delivery contract is in
  `re-frame.cofx/deliver-declared-cofx`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* + :rf.mutation/* events with the meta under test.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   ;; production HTTP fx surface (the transport feature probe); the actual
   ;; fetch is overridden by the capturing no-op below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a capturing no-op so ensure /
  refetch / execute land deterministically without a live fetch."
  [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

(defn- runtime-db [] (rf/runtime-db-value :rf/default))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
(defn- record [wid] (work-ledger/get-record (runtime-db) wid))

(defn- article-spec []
  {:scope          :rf.scope/global
   :params-schema  [:map [:slug :string]]
   :stale-after-ms 60000
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}})
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})

(defn- mutation-spec []
  {:scope   :rf.scope/global
   :request (fn [_args _ctx] {:request {:method :post :url "/api/save"}})})

;; ===========================================================================
;; rf2-601ife — handler-meta declares :rf/time-ms for the time-consuming
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
   :rf.resource.internal/aborted
   :rf.resource.internal/stale-fired
   :rf.mutation/execute
   :rf.mutation.internal/succeeded
   :rf.mutation.internal/failed])

(deftest time-consuming-handlers-declare-time-ms-cofx
  (testing "rf2-601ife: every time-consuming resource / mutation handler
            DECLARES `:rf/time-ms` in `:rf.cofx/requires`, so handler-meta
            exposes the dependency and the runtime delivers it FLAT (EP-0017
            declared-only delivery — nothing implicit, including `:rf/time-ms`)"
    (doseq [event-id time-consuming-resource-events]
      (let [meta     (rf/handler-meta :event event-id)
            requires (:rf.cofx/requires meta)]
        (is (some? meta) (str event-id " is registered"))
        (is (contains? (set requires) :rf/time-ms)
            (str event-id " declares :rf/time-ms in :rf.cofx/requires (was "
                 (pr-str requires) ")"))))))

(deftest load-causing-handlers-declare-generation-and-time
  (testing "rf2-601ife / rf2-abyycr: a load-causing event declares BOTH the
            recordable generation-allocation cofx AND the causal `:rf/time-ms`"
    (doseq [event-id [:rf.resource/ensure :rf.resource/refetch :rf.mutation/execute]]
      (let [requires (set (:rf.cofx/requires (rf/handler-meta :event event-id)))]
        (is (contains? requires :rf.resource/generation-allocation)
            (str event-id " declares the generation-allocation cofx"))
        (is (contains? requires :rf/time-ms)
            (str event-id " declares the time cofx"))))))

(deftest succeeded-loaded-at-from-flat-time-ms
  (testing "rf2-601ife: the success reply handler reads the DELIVERED FLAT
            `:rf/time-ms` (declared via :rf.cofx/requires) for the durable
            :loaded-at — scripting the reply token's :rf.cofx :rf/time-ms
            lands it flat and the durable write picks it up"
    (rf/reg-resource :tm/article (article-spec))
    (let [scoped-key   (state/scoped-resource-key :rf.scope/global :tm/article {:slug "w"})
          completed-at 1781078400456]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :tm/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :tm 1]}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid :generation 1
                            :data {:title "Welcome"}}]
                          {:rf.cofx {:rf/time-ms completed-at}}))
      (is (= completed-at (:loaded-at (entry scoped-key)))
          "durable :loaded-at is the declared-flat causal :rf/time-ms")
      (is (= (+ completed-at 60000) (:stale-at (entry scoped-key)))
          ":stale-at is :loaded-at + :stale-after-ms"))))

(deftest started-at-from-flat-time-ms
  (testing "rf2-601ife: the ensure handler reads the DELIVERED FLAT
            `:rf/time-ms` for the durable work-ledger :started-at"
    (rf/reg-resource :tm/started (article-spec))
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :tm/started {:slug "w"})
          started-at 1781000000000]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :tm/started :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :tm 2]}]
                        {:rf.cofx {:rf/time-ms started-at}})
      (let [wid (:current-work (entry scoped-key))]
        (is (= started-at (:started-at (record wid)))
            "the work-ledger :started-at is the declared-flat causal :rf/time-ms")))))

(deftest invalidate-at-from-flat-time-ms
  (testing "rf2-601ife: the invalidate-tags handler reads the DELIVERED FLAT
            `:rf/time-ms` for the durable :invalidated-at. An OWNERLESS entry
            is left-stale (an active-owner entry would refetch, transitioning
            to :fetching and clearing :invalidated-at — correct framework
            behaviour, not the fact under test), so the durable :invalidated-at
            persists and pins the causal time."
    (rf/reg-resource :tm/inv (article-spec))
    (let [scoped-key     (state/scoped-resource-key :rf.scope/global :tm/inv {:slug "w"})
          loaded-at      1781000000000
          invalidated-at 1781000099999]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :tm/inv :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :tm 3]}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid :generation 1
                            :data {:title "Welcome"}}]
                          {:rf.cofx {:rf/time-ms loaded-at}}))
      ;; release the owner so the entry is left-stale (not refetched) on invalidate
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :tm 3]}])
      (rf/dispatch-sync [:rf.resource/invalidate-tags
                         {:scope :rf.scope/global :tags #{[:article "w"]}
                          :cause [:test :inv]}]
                        {:rf.cofx {:rf/time-ms invalidated-at}})
      (is (= invalidated-at (:invalidated-at (entry scoped-key)))
          "durable :invalidated-at is the declared-flat causal :rf/time-ms"))))

;; ===========================================================================
;; rf2-rl27r2 — failure / cancellation replies carry the causal :completed-at.
;; ===========================================================================

(deftest failure-reply-carries-completed-at
  (testing "rf2-rl27r2: a first-load FAILURE settles the work row terminal
            :failed carrying the reply token's causal :completed-at (delivered
            flat) alongside the error envelope — symmetric with success"
    (rf/reg-resource :fail/article (article-spec))
    (let [scoped-key   (state/scoped-resource-key :rf.scope/global :fail/article {:slug "w"})
          completed-at 1781111111111]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :fail/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :fail 1]}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/failed
                           {:resource/key scoped-key :work/id wid :generation 1
                            :error {:kind :rf.http/http-5xx :status 503}}]
                          {:rf.cofx {:rf/time-ms completed-at}})
        (is (= :failed (:status (record wid))))
        (is (= completed-at (:completed-at (:outcome (record wid))))
            "the failed terminal outcome carries the causal :completed-at")
        (is (= {:kind :rf.http/http-5xx :status 503} (:error (:outcome (record wid))))
            "the error envelope still rides")))))

(deftest abort-reply-carries-completed-at
  (testing "rf2-rl27r2: a CANCELLED completion (an :rf.http/aborted failure
            reply) settles the work row terminal :cancelled carrying the
            reply token's causal :completed-at"
    (rf/reg-resource :ab2/article (article-spec))
    (let [scoped-key   (state/scoped-resource-key :rf.scope/global :ab2/article {:slug "w"})
          completed-at 1781222222222]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :ab2/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :ab2 1]}])
      (let [wid (:current-work (entry scoped-key))]
        ;; an :rf.http/aborted failure envelope branches into cancellation
        (rf/dispatch-sync [:rf.resource.internal/failed
                           {:resource/key scoped-key :work/id wid :generation 1
                            :error {:kind :rf.http/aborted :reason :actor-destroyed}}]
                          {:rf.cofx {:rf/time-ms completed-at}})
        (is (= :cancelled (:status (record wid))))
        (is (= completed-at (:completed-at (:outcome (record wid))))
            "the cancelled terminal outcome carries the causal :completed-at")))))

(deftest standalone-aborted-handler-carries-completed-at
  (testing "rf2-rl27r2: the standalone :rf.resource.internal/aborted handler
            settles the work row :cancelled carrying the reply token's causal
            :completed-at (previously dropped — only :reason rode)"
    (rf/reg-resource :ab3/article (article-spec))
    (let [scoped-key   (state/scoped-resource-key :rf.scope/global :ab3/article {:slug "w"})
          completed-at 1781333333333]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :ab3/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :ab3 1]}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/aborted
                           {:resource/key scoped-key :work/id wid :generation 1}]
                          {:rf.cofx {:rf/time-ms completed-at}})
        (is (= :cancelled (:status (record wid))))
        (is (= {:reason :aborted :completed-at completed-at}
               (:outcome (record wid)))
            "the cancelled outcome carries :reason AND the causal :completed-at")))))

(deftest stale-suppressed-failure-carries-completed-at
  (testing "rf2-rl27r2: a SUPPRESSED (superseded) failure reply records the
            causal :completed-at in its terminal :suppressed outcome too"
    (rf/reg-resource :sf/article (article-spec))
    (let [scoped-key   (state/scoped-resource-key :rf.scope/global :sf/article {:slug "w"})
          completed-at 1781444444444]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :sf/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :sf 1]}])
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
