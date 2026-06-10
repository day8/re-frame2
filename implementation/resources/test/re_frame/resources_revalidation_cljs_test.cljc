(ns re-frame.resources-revalidation-cljs-test
  "Focus / reconnect active-stale revalidation for the Resources artefact
  (rf2-vtblcq, Spec 016 §EP-0003 slice 10 — the first public-beta gate item).

  These JVM+CLJS unit tests pin the slice-10 contract (Spec 016 §Stale and
  GC scheduling / §Deferred slices: focus/reconnect revalidation as resource
  EVENTS, not subscription-driven fetching):

    1. ACTIVE-STALE SCAN — `:rf.resource/window-focused` /
       `:rf.resource/network-reconnected` scan the frame's cache entries and
       refetch ONLY those that are BOTH active (a live `:active-owner` lease)
       AND stale-by-policy (`state/entry-stale?` against the durable
       timestamps); fresh entries and owner-free entries are LEFT ALONE;
    2. CAUSE, NEVER OWNER — a focus/reconnect-triggered refetch carries cause
       `:focus` / `:reconnect` and attaches NO owner (it never creates
       liveness — Spec 016 §Active owners and causes);
    3. GENERATION / STALE-SUPPRESSION respected — the refetch lowers through
       the ordinary refetch path (force-new generation), so it is just
       another cause; a late reply from a superseded generation is suppressed
       exactly as for any refetch; the refetch is background (prior data kept,
       status `:fetching`);
    4. FRAME-DESTROY TEARDOWN — the host focus/online listeners live in a
       module-level side table keyed by frame-id, cancelled on frame destroy
       via the single `:resources/on-frame-destroyed!` hook (composed with the
       work-ledger + timer + generation host-cache release — not a second
       teardown path)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events (incl. the focus/reconnect events this test
   ;; dispatches) + the timer / work-ledger side-table fx + the internal
   ;; replies these tests dispatch through.
   [re-frame.resources]
   [re-frame.resources.revalidate-listeners :as revalidate-listeners]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as timers]
   [re-frame.resources.work-ledger :as work-ledger]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch + abort are overridden by capturing no-ops below.
   [re-frame.http-managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport + abort + timer-schedule (deterministic) ---------

(def ^:private aborts (atom []))
(def ^:private scheduled-timers (atom []))

(defn- capturing-fixture
  "Override the real :rf.http/managed + :rf.http/managed-abort fxs with
  capturing no-ops, and CAPTURE :rf.resource/schedule-timers so the
  succeeded-handler's arming is asserted WITHOUT a real wall-clock timer
  firing. Composed INSIDE the reset-runtime fixture (one `use-fixtures` call)."
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
  (state/reset-cache!)
  (work-ledger/reset-cache!)
  (timers/reset-cache!)
  (revalidate-listeners/reset-cache!)
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (rf/runtime-db-value frame-id)))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/from-caller
           :params-schema [:map [:slug :string]]
           :request       (fn [{:keys [slug]} _ctx]
                            {:request {:method :get :url (str "/api/articles/" slug)}})
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(defn- ensure! [resource scope slug owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope scope :params {:slug slug}
                      :owner owner}]))

(defn- succeed!
  "Feed an internal success reply for a scoped key, reading the LIVE entry's
  current work-id + generation (the per-frame generation allocator is
  monotone, so a hardcoded generation would be stale-suppressed once more
  than one resource has loaded in the frame)."
  [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource-key scoped-key :work-id (:current-work e)
                        :generation (:generation e) :data data}])))

;; ===========================================================================
;; 1. Active-stale scan — refetch active-stale, leave fresh + owner-free alone
;; ===========================================================================

(deftest window-focused-scans-active-stale-only
  ;; Stale-by-policy is `:stale-after-ms 0` (stale the instant it loads); the
  ;; never-stale variant declares no stale policy. Both keep their owner so the
  ;; ACTIVE-OWNER gate is what differs from the inactive case, not liveness.
  (rf/reg-resource :rv/sw   (article-spec {:stale-after-ms 0})) ;; goes stale at once
  (rf/reg-resource :rv/fresh (article-spec))                    ;; never stale
  (let [scope    {:user "u"}
        k-as     (state/scoped-resource-key scope :rv/sw    {:slug "active-stale"})
        k-af     (state/scoped-resource-key scope :rv/fresh {:slug "active-fresh"})
        k-is     (state/scoped-resource-key scope :rv/sw    {:slug "inactive-stale"})]
    ;; active + stale
    (ensure! :rv/sw scope "active-stale" [:route :r 1])
    (succeed! k-as {:title "AS"})
    ;; active + fresh (no stale policy → never stale)
    (ensure! :rv/fresh scope "active-fresh" [:route :r 1])
    (succeed! k-af {:title "AF"})
    ;; inactive + stale (owner released after load)
    (ensure! :rv/sw scope "inactive-stale" [:lease :x 1])
    (succeed! k-is {:title "IS"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
    (is (empty? (:active-owners (entry k-is))) "inactive-stale entry has no owner")
    (testing "Spec 016 §Deferred slices — one window-focus scan refetches ONLY
              the active-owner STALE entry (background → :fetching, prior data
              kept); the active-FRESH entry and the INACTIVE-stale entry are
              left alone"
      (rf/dispatch-sync [:rf.resource/window-focused])
      (is (= :fetching (:status (entry k-as))) "active-stale refetched")
      (is (= {:title "AS"} (:data (entry k-as))) "prior data kept (background)")
      (is (= :loaded (:status (entry k-af))) "active-fresh NOT refetched")
      (is (= :loaded (:status (entry k-is))) "inactive-stale NOT refetched (owner gate)"))))

(deftest network-reconnected-refetches-active-stale
  (rf/reg-resource :rc/sw (article-spec {:stale-after-ms 0}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :rc/sw {:slug "w"})]
    (ensure! :rc/sw scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Deferred slices — network reconnect refetches the
              active-owner stale entry in the background"
      (rf/dispatch-sync [:rf.resource/network-reconnected])
      (is (= :fetching (:status (entry k))) "active-stale entry refetched on reconnect")
      (is (= {:title "W"} (:data (entry k))) "prior data kept"))))

;; ===========================================================================
;; 2. Cause, never owner — the refetch records :focus / :reconnect, no owner
;; ===========================================================================

(deftest focus-refetch-is-cause-not-owner
  (rf/reg-resource :ca/sw (article-spec {:stale-after-ms 0}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :ca/sw {:slug "w"})]
    (ensure! :ca/sw scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (rf/dispatch-sync [:rf.resource/window-focused])
    (testing "Spec 016 §Active owners and causes — a focus-triggered refetch
              records the :focus CAUSE on the live work record but attaches NO
              new owner (it never creates liveness)"
      (let [e   (entry k)
            rec (work-ledger/get-record (runtime-db) (:current-work e))]
        (is (= #{[:route :r 1]} (:active-owners e))
            "owner set unchanged — focus added no owner")
        (is (some #{:focus} (:causes rec))
            "the :focus cause is recorded on the refetch work record")))))

;; ===========================================================================
;; 3. Generation / stale-suppression respected
;; ===========================================================================

(deftest focus-refetch-bumps-generation-and-suppresses-stale-reply
  (rf/reg-resource :gn/sw (article-spec {:stale-after-ms 0}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :gn/sw {:slug "w"})]
    (ensure! :gn/sw scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (let [gen-before (:generation (entry k))
          old-wid    (:current-work (entry k))] ;; nil after succeed (settled)
      (is (nil? old-wid) "no in-flight work after the first load settled")
      (rf/dispatch-sync [:rf.resource/window-focused])
      (let [e (entry k)]
        (testing "Spec 016 — the focus refetch forces a NEW generation"
          (is (= (inc gen-before) (:generation e)) "generation bumped")
          (is (= :fetching (:status e)) "refetching in background"))
        (testing "Spec 016 §Cancellation is opportunistic; stale suppression is
                  mandatory — a late reply carrying the PRE-focus generation is
                  suppressed (never overwrites the post-focus entry)"
          (rf/dispatch-sync [:rf.resource.internal/succeeded
                             {:resource-key k
                              :work-id (work-ledger/resource-work-id k gen-before)
                              :generation gen-before :data {:title "Zombie"}}])
          (is (not= {:title "Zombie"} (:data (entry k)))
              "the pre-focus-generation reply was suppressed"))))))

(deftest empty-frame-focus-is-harmless-noop
  (testing "Spec 016 — a focus signal on a frame with no resource entries is a
            harmless no-op (no refetch, no error)"
    (rf/dispatch-sync [:rf.resource/window-focused])
    (rf/dispatch-sync [:rf.resource/network-reconnected])
    (is (nil? (get-in (runtime-db) (state/entries-path)))
        "no entries materialised by an empty-frame scan")))

;; ===========================================================================
;; 4. Pure active-stale-scan selection unit
;; ===========================================================================

(deftest active-stale-scan-selection
  (testing "Spec 016 — the active-stale scan selects ONLY active + stale
            entries (fresh active, stale inactive, and fresh inactive excluded)"
    (let [now    1000
          scope  {:user "u"}
          mk     (fn [slug] (state/scoped-resource-key scope :sc/x {:slug slug}))
          ;; active + stale (stale-at in the past)
          e-as   (assoc (state/empty-entry :sc/x)
                        :status :loaded :data {:v 1}
                        :active-owners #{[:route :r 1]} :stale-at 500)
          ;; active + fresh (stale-at in the future)
          e-af   (assoc (state/empty-entry :sc/x)
                        :status :loaded :data {:v 1}
                        :active-owners #{[:route :r 1]} :stale-at 5000)
          ;; inactive + stale (no owner)
          e-is   (assoc (state/empty-entry :sc/x)
                        :status :loaded :data {:v 1}
                        :active-owners #{} :stale-at 500)
          rdb    {:rf.runtime/resources
                  {:entries {(mk "as") e-as (mk "af") e-af (mk "is") e-is}}}
          ;; the scan is private; exercise it through the handler's pure path
          ;; by reading the public events ns var via the dispatched event would
          ;; need a frame — instead assert through the handler-level behaviour
          ;; in the integration tests above. Here we assert the selection
          ;; predicate directly via state/entry-stale? + active-owner gate.
          eligible (into #{}
                         (keep (fn [[kk e]]
                                 (when (and (seq (:active-owners e))
                                            (state/entry-stale? e now))
                                   kk)))
                         (:entries (:rf.runtime/resources rdb)))]
      (is (= #{(mk "as")} eligible)
          "only the active + stale entry is selected"))))

;; ===========================================================================
;; 5. Frame-destroy cancels the focus/reconnect listeners (single hook)
;; ===========================================================================

(deftest frame-destroy-cancels-revalidation-listeners
  (let [fa :rv/frame-a]
    (rf/reg-frame fa {:doc "frame-destroy revalidation-listener frame"})
    ;; install the per-frame listener side-table slot directly (the host
    ;; addEventListener arm is CLJS/DOM-only; the side-table bookkeeping is the
    ;; platform-neutral part frame-destroy must clear)
    (swap! revalidate-listeners/listener-table assoc fa {:focus :h :visibility :h :online :h})
    (is (contains? @revalidate-listeners/listener-table fa)
        "listener slot recorded for frame A")
    (testing "Spec 016 [Runtime-Subsystems] clause 5 — frame destroy drops the
              frame's focus/reconnect listeners via the single
              :resources/on-frame-destroyed! teardown hook (composed, not a
              second path)"
      (frame/destroy-frame! fa)
      (is (not (contains? @revalidate-listeners/listener-table fa))
          "frame A's listener slot dropped on destroy"))))

(deftest remove-revalidation-listeners-is-idempotent
  (testing "Spec 016 — remove for a frame with no installed listeners is a
            harmless no-op (idempotent + JVM-safe)"
    (revalidate-listeners/remove-revalidation-listeners! :rv/no-such-frame)
    (is (not (contains? @revalidate-listeners/listener-table :rv/no-such-frame)))))
