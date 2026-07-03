(ns re-frame.resources-lifecycle-trace-ops-cljs-test
  "Coverage for the lifecycle trace ops the `:rf.resource/*` trace family
  enumerates in Spec 016 §Xray and AI tooling — the rows the Xray
  lifecycle timeline / AI-Audit consume (rf2-ktuhuq):

    1. `:rf.resource/registered`     — one row per FIRST-TIME `reg-resource`
                                       (frame-agnostic; first-time-only,
                                       symmetric with :rf.route/registered);
    2. `:rf.resource/owner-attached` — a NEW owner lease landing on an entry,
                                       both on a fresh load (`:joined-in-flight?`
                                       false) and on a dedupe join (true), and
                                       NOT re-emitted for an already-present
                                       owner (symmetric with :owner-released);
    3. `:rf.resource/hydrate-refetch`— one per hydration refetch-plan entry
                                       (the per-entry decision; distinct from
                                       the ordinary refetch the route slice
                                       then dispatches).

  Also pins the reconcile decision: the runtime emits exactly one
  suppression op (`:rf.resource/stale-suppressed`) — the spec/Xray
  `:rf.resource/work-suppressed` op was folded into it and is never
  emitted. And it pins that `:rf.resource/cache-hit` IS emitted on a
  fresh-skip ensure (an `ensure` of an already-`:loaded`, still-fresh
  entry serves the cached value — no fetch, no in-flight join) — the
  fresh-skip behaviour (rf2-hsa0sv).

  Per Spec 016 §Xray and AI tooling / §Active owners and causes."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the generation cofx/fx these tests
   ;; dispatch.
   [re-frame.resources]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport + trace recorder ---------------------------------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a capturing no-op so ensure's
  :loading entry write + lower-fx are deterministic and no real fetch fires."
  [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

(defn- record-resource-traces!
  "Run `body-fn` with a trace listener installed; return the vector of every
  `:rf.resource/*`-operation trace event emitted during it (in capture
  order). The listener is unregistered in a `finally`."
  [body-fn]
  (let [seen (atom [])
        k    ::resource-trace-recorder]
    (trace-tooling/register-listener!
      k (fn [ev]
          (when (and (keyword? (:operation ev))
                     (= "rf.resource" (namespace (:operation ev))))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- ops
  "The set of distinct :operation keywords across captured trace events."
  [traces]
  (into #{} (map :operation) traces))

(defn- by-op
  "Captured events for a given :operation, in capture order."
  [traces op]
  (filterv #(= op (:operation %)) traces))

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

(defn- entry [scoped-key]
  (get-in (rf/runtime-db-value :rf/default) (state/entry-path scoped-key)))

;; ===========================================================================
;; 1. :rf.resource/registered — first-time-only, frame-agnostic
;; ===========================================================================

(deftest reg-resource-emits-registered-trace
  (testing "reg-resource fires :rf.resource/registered with the resource id +
            the static policy summary (Spec 016 §Xray and AI tooling)"
    (let [traces (record-resource-traces!
                   #(rf/reg-resource :rt/article
                                     (article-spec {:stale-after-ms 60000
                                                    :gc-after-ms    300000})
                                     article-spec-request))
          evs    (by-op traces :rf.resource/registered)]
      (is (= 1 (count evs))
          "exactly one :rf.resource/registered for a fresh reg-resource")
      (let [tags (:tags (first evs))]
        (is (= :rt/article      (:resource-id tags))    ":resource-id in tags")
        (is (= :rf.scope/global (:scope-policy tags))   ":scope-policy in tags")
        (is (= 60000            (:stale-after-ms tags)) ":stale-after-ms in tags")
        (is (= 300000           (:gc-after-ms tags))    ":gc-after-ms in tags")))))

(deftest registered-fires-first-time-only
  (testing "re-registration does NOT re-emit :rf.resource/registered (the
            cross-kind :rf.registry/handler-replaced trace is the hot-reload
            signal) — symmetric with :rf.route/registered / :rf.flow/registered"
    (rf/reg-resource :rt/once (article-spec) article-spec-request)
    (let [traces (record-resource-traces!
                   #(rf/reg-resource :rt/once (article-spec {:stale-after-ms 1}) article-spec-request))]
      (is (empty? (by-op traces :rf.resource/registered))
          "re-registration emits no :rf.resource/registered"))))

;; ===========================================================================
;; 2. :rf.resource/owner-attached — a new lease landing on an entry
;; ===========================================================================

(deftest owner-attached-on-fresh-load
  (rf/reg-resource :oa/article (article-spec) article-spec-request)
  (testing "an ensure that fresh-loads AND attaches a NEW owner emits
            :rf.resource/owner-attached (:joined-in-flight? false)"
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :oa/article {:slug "w"})
          traces (record-resource-traces!
                   #(rf/dispatch-sync
                      [:rf.resource/ensure {:resource :oa/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :oa 1]}]))
          evs    (by-op traces :rf.resource/owner-attached)]
      (is (= 1 (count evs)) "one owner-attached on a fresh load with an owner")
      (let [tags (:tags (first evs))]
        (is (= [:lease :oa 1] (:owner tags))           ":owner in tags")
        (is (= scoped-key     (:resource/key tags))    ":resource/key in tags")
        (is (false?           (:joined-in-flight? tags)) "fresh load: not a join")))))

(deftest owner-attached-on-dedupe-join
  (rf/reg-resource :oa/join (article-spec) article-spec-request)
  (testing "a second ensure that JOINS an in-flight request but attaches a NEW
            owner emits :rf.resource/owner-attached (:joined-in-flight? true)"
    (rf/dispatch-sync
      [:rf.resource/ensure {:resource :oa/join :scope :rf.scope/global
                            :params {:slug "w"} :owner [:route :r 1]}])
    (let [traces (record-resource-traces!
                   #(rf/dispatch-sync
                      [:rf.resource/ensure {:resource :oa/join :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :x 2]}]))
          evs    (by-op traces :rf.resource/owner-attached)]
      (is (seq (by-op traces :rf.resource/deduped)) "the second ensure deduped")
      (is (= 1 (count evs)) "one owner-attached for the NEW owner on the join")
      (let [tags (:tags (first evs))]
        (is (= [:lease :x 2] (:owner tags))            "the newly-joined owner")
        (is (true?           (:joined-in-flight? tags)) "join: :joined-in-flight? true")))))

(deftest owner-attached-not-re-emitted-for-existing-owner
  (rf/reg-resource :oa/same (article-spec) article-spec-request)
  (testing "re-ensuring with an owner ALREADY on the entry does NOT re-emit
            owner-attached (the lease did not change)"
    (rf/dispatch-sync
      [:rf.resource/ensure {:resource :oa/same :scope :rf.scope/global
                            :params {:slug "w"} :owner [:lease :same 1]}])
    (let [traces (record-resource-traces!
                   #(rf/dispatch-sync
                      [:rf.resource/ensure {:resource :oa/same :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :same 1]}]))]
      (is (empty? (by-op traces :rf.resource/owner-attached))
          "no owner-attached when the owner was already present"))))

(deftest owner-attached-absent-when-no-owner
  (rf/reg-resource :oa/none (article-spec) article-spec-request)
  (testing "an ownerless ensure (e.g. a focus/reconnect refetch) emits no
            owner-attached (a CAUSE, not an owner — Spec 016 §Active owners
            and causes)"
    (let [traces (record-resource-traces!
                   #(rf/dispatch-sync
                      [:rf.resource/ensure {:resource :oa/none :scope :rf.scope/global
                                            :params {:slug "w"} :cause [:manual :x]}]))]
      (is (empty? (by-op traces :rf.resource/owner-attached))
          "no owner → no owner-attached"))))

;; ===========================================================================
;; 3. :rf.resource/hydrate-refetch — one per refetch-plan entry
;; ===========================================================================

(defn- hydrated-runtime-db
  "A runtime-db carrying three hydrated entries: a fresh-with-data entry (no
  refetch), a stale-with-data entry (background refetch), and a metadata-only
  entry (no data → refetch)."
  [clock]
  (let [k-fresh [:rf.scope/global :h/fresh {:slug "f"}]
        k-stale [:rf.scope/global :h/stale {:slug "s"}]
        k-meta  [:rf.scope/global :h/meta  {:slug "m"}]]
    ;; rf2-9e0tyq — `:entries` is keyed on the byte `key-id`; each entry carries
    ;; its own `:resource/key` (the refetch plan reads it for :resource-id).
    {state/resources-key
     {:entries
      {(state/key-id k-fresh) {:resource/key k-fresh :status :loaded :data {:x 1} :loaded-at (- clock 10) :stale-at (+ clock 10000)}
       (state/key-id k-stale) {:resource/key k-stale :status :loaded :data {:y 2} :loaded-at (- clock 20000) :stale-at (- clock 1)}
       (state/key-id k-meta)  {:resource/key k-meta  :status :loaded :data nil    :loaded-at (- clock 10) :stale-at (+ clock 10000)}}}}))

(deftest hydrate-refetch-emits-per-plan-entry
  (testing "hydrate-refetch-plan emits one :rf.resource/hydrate-refetch per
            plan entry (stale + metadata-only), NOT for fresh-with-data, with
            the per-entry :reason (Spec 016 §Xray and AI tooling)"
    (let [clock  5000
          rdb    (hydrated-runtime-db clock)
          traces (record-resource-traces!
                   #(ssr/hydrate-refetch-plan rdb clock :rf/default))
          evs    (by-op traces :rf.resource/hydrate-refetch)
          reasons (into {} (map (juxt #(get-in % [:tags :resource-id])
                                      #(get-in % [:tags :reason]))) evs)]
      (is (= 2 (count evs)) "two refetch rows: the stale + the metadata-only entry")
      (is (= :stale   (reasons :h/stale)) "stale entry → :stale")
      (is (= :no-data (reasons :h/meta))  "metadata-only entry → :no-data")
      (is (not (contains? reasons :h/fresh)) "fresh-with-data entry has NO row")
      (is (every? #(= :hydration (get-in % [:tags :cause])) evs)
          "each row carries :cause :hydration"))))

;; ===========================================================================
;; 4. Reconcile decisions — one suppression op; cache-hit not emitted
;; ===========================================================================

(deftest only-stale-suppressed-no-work-suppressed
  (rf/reg-resource :sp/article (article-spec) article-spec-request)
  (testing "a stale/superseded reply emits :rf.resource/stale-suppressed and
            NEVER :rf.resource/work-suppressed (folded into stale-suppressed —
            there is exactly one suppression op)"
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :sp/article {:slug "w"})]
      (rf/dispatch-sync
        [:rf.resource/ensure {:resource :sp/article :scope :rf.scope/global
                              :params {:slug "w"} :owner [:lease :sp 1]}])
      (let [wid1 (:current-work (entry scoped-key))
            traces
            (record-resource-traces!
              (fn []
                ;; supersede with a refetch (gen 2), then land the OLD reply
                (rf/dispatch-sync
                  [:rf.resource/refetch {:resource :sp/article :scope :rf.scope/global
                                         :params {:slug "w"}}])
                (rf/dispatch-sync
                  [:rf.resource.internal/succeeded
                   {:resource/key scoped-key :work/id wid1 :generation 1
                    :data {:stale "data"}}])))]
        (is (seq (by-op traces :rf.resource/stale-suppressed))
            "the stale reply emitted :rf.resource/stale-suppressed")
        (is (not (contains? (ops traces) :rf.resource/work-suppressed))
            ":rf.resource/work-suppressed is never emitted")))))

;; ---------------------------------------------------------------------------
;; rf2-mn4j89 / rf2-hh8nzd / rf2-uwqs7l — the canonical :status :stale reply
;; envelope rides the PRODUCTION resource stale-suppression trace. The
;; behaviour-only test above (and the work-ledger / invalidation-GC stale
;; tests) PASSED SILENTLY while the production stale branch discarded the
;; canonical reply: it emitted a bespoke trace with carried-generation ONLY
;; and never lowered through the shared `re-frame.reply` substrate. These
;; assertions FAIL before rf2-mn4j89 and pin the fix — the SAME envelope
;; shape the machine `:rf.machine/done` stale path pins.
;; ---------------------------------------------------------------------------

(deftest stale-suppressed-trace-carries-canonical-reply-envelope
  (rf/reg-resource :rev/article (article-spec) article-spec-request)
  (testing "rf2-mn4j89 — a superseded resource reply (carried gen 1 vs current
            gen 2) is recorded :status :stale / :work/status :suppressed via
            the shared substrate, with the carried-vs-current generation pair
            on the production :rf.resource/stale-suppressed trace; the app
            target does NOT run (no entry write) and the ledger is :suppressed"
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :rev/article {:slug "w"})]
      (rf/dispatch-sync
        [:rf.resource/ensure {:resource :rev/article :scope :rf.scope/global
                              :params {:slug "w"} :owner [:lease :rev 1]}])
      (let [wid1 (:current-work (entry scoped-key))
            traces
            (record-resource-traces!
              (fn []
                ;; supersede with a refetch (mints generation 2), then land the
                ;; OLD (generation-1) reply — the REAL production stale path.
                (rf/dispatch-sync
                  [:rf.resource/refetch {:resource :rev/article :scope :rf.scope/global
                                         :params {:slug "w"}}])
                (rf/dispatch-sync
                  [:rf.resource.internal/succeeded
                   {:resource/key scoped-key :work/id wid1 :generation 1
                    :data {:stale "data"}}])))
            sup  (first (by-op traces :rf.resource/stale-suppressed))]
        (is (some? sup) ":rf.resource/stale-suppressed fired for the stale reply")
        (let [tags (:tags sup)]
          ;; bespoke facts preserved (additive, not replaced). rf2-o6c2jr —
          ;; the bare :work/id duplicate was dropped; the work identity rides
          ;; ONLY as :rf.reply/work-id (asserted below).
          (is (= scoped-key (:resource/key tags)))
          (is (not (contains? tags :work/id))
              "no bare :work/id duplicate on the stale-suppressed reply row")
          (is (= :success   (:outcome tags)) "the stale reply's natural outcome diagnostic")
          ;; CANONICAL reply-envelope vocabulary via the shared substrate
          (is (= :stale (:rf.reply/status tags))
              "the canonical :status :stale reply IS produced via re-frame.reply")
          (is (= :suppressed (:rf.reply/work-status tags))
              "the ledger terminal for a stale completion")
          (is (= :rf.resource/superseded (:rf.reply/stale-reason tags)))
          (is (= wid1 (:rf.reply/work-id tags)) "the canonical work identity")
          ;; the carried-vs-current generation pair IS the supersession gate
          (let [corr (:rf.reply/correlation tags)]
            (is (= 1 (-> corr :generation :carried))
                "carried generation off the stale reply token")
            (is (= 2 (-> corr :generation :current))
                "current generation is the LIVE entry's generation (gen 2)")
            (is (= scoped-key (:resource/key corr)))))
        ;; (2)/(5) the app target did NOT run — the entry was NOT overwritten by
        ;; the stale reply (still on gen 2, not :loaded with {:stale "data"}).
        (let [e (entry scoped-key)]
          (is (= 2 (:generation e)) "the stale reply did not touch the newer entry")
          (is (not= {:stale "data"} (:data e)) "stale data was NOT written"))
        ;; (3) the ledger row settles terminal :suppressed.
        (is (= :suppressed (:status (work-ledger/get-record
                                      (rf/runtime-db-value :rf/default) wid1)))
            "the work row settled terminal :suppressed")))))

(deftest cache-hit-emitted-on-fresh-ensure
  ;; no :stale-after-ms → the entry is always fresh once loaded
  ;; (entry-stale? false: neither :stale-at nor :invalidated-at set)
  (rf/reg-resource :ch/article (article-spec) article-spec-request)
  (testing "an ensure of an already-:loaded, still-fresh entry serves the
            cached value: it emits :rf.resource/cache-hit and starts NO new
            load (Spec 016 §Lifecycle is an FSM / §Restore — fresh-skip,
            rf2-hsa0sv)"
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :ch/article {:slug "w"})]
      ;; load it once, then settle to :loaded (fresh: stale-after 60s)
      (rf/dispatch-sync
        [:rf.resource/ensure {:resource :ch/article :scope :rf.scope/global
                              :params {:slug "w"} :owner [:lease :ch 1]}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync
          [:rf.resource.internal/succeeded
           {:resource/key scoped-key :work/id wid :generation 1 :data {:title "W"}}]))
      (is (= :loaded (:status (entry scoped-key))) "entry settled :loaded")
      (let [gen-before (:generation (entry scoped-key))
            data-before (:data (entry scoped-key))
            traces (record-resource-traces!
                     #(rf/dispatch-sync
                        [:rf.resource/ensure {:resource :ch/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :ch 2]}]))
            hits   (by-op traces :rf.resource/cache-hit)]
        (is (= 1 (count hits)) "one :rf.resource/cache-hit on the fresh ensure")
        (let [tags (:tags (first hits))]
          (is (= scoped-key (:resource/key tags)) ":resource/key in tags")
          (is (= [:lease :ch 2] (:owner tags))    "the newly-attached owner in tags"))
        (testing "fresh-skip starts NO new load: no fetch-started / work-started"
          (is (not (contains? (ops traces) :rf.resource/fetch-started))
              "no fetch-started on a cache-hit")
          (is (not (contains? (ops traces) :rf.resource/work-started))
              "no work-started on a cache-hit")
          (is (not (contains? (ops traces) :rf.resource/deduped))
              "a settled fresh entry is NOT a dedupe (no in-flight work)"))
        (testing "the entry is unchanged (same generation + data; owner attached)"
          (let [e (entry scoped-key)]
            (is (= gen-before (:generation e)) "no new generation")
            (is (identical? data-before (:data e)) "same data value (cache served)")
            (is (nil? (:current-work e)) "no in-flight work record")
            (is (contains? (:active-owners e) [:lease :ch 2]) "new owner attached")))
        (testing "the new owner lease is recorded as :rf.resource/owner-attached"
          (let [oa (by-op traces :rf.resource/owner-attached)]
            (is (= 1 (count oa)) "one owner-attached for the newly-attached owner")
            (is (false? (:joined-in-flight? (:tags (first oa))))
                "fresh-skip is not an in-flight join")))))))

(deftest stale-loaded-ensure-still-refetches
  (rf/reg-resource :ch/stale (article-spec) article-spec-request)
  (testing "a STALE :loaded entry STILL refetches on the next ensure —
            fresh-skip must NOT swallow a stale refresh (negative case,
            rf2-hsa0sv)"
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :ch/stale {:slug "w"})]
      (rf/dispatch-sync
        [:rf.resource/ensure {:resource :ch/stale :scope :rf.scope/global
                              :params {:slug "w"} :owner [:lease :st 1]}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync
          [:rf.resource.internal/succeeded
           {:resource/key scoped-key :work/id wid :generation 1 :data {:title "W"}}]))
      ;; make it stale WITHOUT auto-refetch: release the owner, then invalidate
      ;; (an inactive matched entry is marked stale but not refetched)
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :st 1]}])
      (rf/dispatch-sync [:rf.resource/invalidate-tags
                         {:scope :rf.scope/global :tags #{[:article "w"]}}])
      (is (some? (:invalidated-at (entry scoped-key))) "entry marked stale")
      (let [gen-before (:generation (entry scoped-key))
            traces (record-resource-traces!
                     #(rf/dispatch-sync
                        [:rf.resource/ensure {:resource :ch/stale :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :st 2]}]))]
        (testing "a stale ensure refetches (fetch/work-started), NOT a cache-hit"
          (is (not (contains? (ops traces) :rf.resource/cache-hit))
              "no cache-hit on a stale entry")
          (is (contains? (ops traces) :rf.resource/work-started)
              "a stale ensure starts new work"))
        (is (= (inc gen-before) (:generation (entry scoped-key)))
            "a new generation was minted (refetch)")
        (is (= :fetching (:status (entry scoped-key)))
            "stale-while-revalidate: :fetching with prior data kept")))))
