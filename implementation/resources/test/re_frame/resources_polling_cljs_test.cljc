(ns re-frame.resources-polling-cljs-test
  "Active-owner POLLING for the Resources artefact (rf2-byl7bk.2 / EP-0020).
  Per Spec 016 §Polling.

  Polling is the third member of the cache-freshness timer family (beside
  `:stale-after-ms` and `:gc-after-ms`): a resource declares `:poll-interval-ms`
  and, while an entry has at least one active owner and the tab is visible, the
  runtime re-runs its load every N ms by event. It reuses two landed,
  battle-tested substrates wholesale — the host advisory timer side-table
  (`re-frame.resources.timers`, the new `:poll` kind beside `:stale` / `:gc`)
  and the focus/reconnect scan-and-refetch core (the `:rf.resource/refetch`
  causal path + the `entry-revalidation-in-flight?` coalescing gate).

  These JVM+CLJS unit tests pin the EP-0020 contract (the recommended cut —
  resource-level `:poll-interval-ms`, unconditional active-owner tick,
  default-pause-when-hidden, `:poll` cause):

    1. ARMING — a settled active-owner poll-enabled entry arms a `:poll` timer
       at `:poll-interval-ms` (and only when actively owned + client platform);
       a resource with no policy / an owner-free settle arms none;
    2. UNCONDITIONAL TICK — a poll-fired re-check refetches by the INTERVAL,
       not gated on `:stale?` (cause `:poll`, never an owner) AND re-arms;
    3. OWNER-RELEASE STOPS — the last owner releasing cancels the poll timer
       (poll-only; stale/GC stay armed) AND a poll-fired on an owner-free entry
       refetches nothing + does not re-arm;
    4. HIDDEN-TAB PAUSE — `:hidden?` pauses the tick (no refetch) but re-arms;
    5. IN-FLIGHT COALESCING — a poll tick that finds live in-flight work skips
       the refetch (no overlap on a slow endpoint) but re-arms;
    6. FOCUS/RECONNECT COEXISTENCE — focus + poll do not double-fetch (the
       in-flight gate makes the overlap idempotent);
    7. LATE-POLL-REPLY SUPPRESSION — a poll reply carrying a superseded
       generation is suppressed (never overwrites the live entry);
    8. INVALIDATION RESETS THE CLOCK — a refetch (poll / invalidation) that
       settles reschedules the poll (cancel-then-arm), not stacks;
    9. BACKGROUND POLL FAILURE — a failed poll tick keeps prior data + keeps
       polling (the next tick still fires)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.identity :as identity]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events (incl. the internal poll-fired event + the timer /
   ;; cancel-poll-timers fx + the internal replies these tests dispatch).
   [re-frame.resources]
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
;;
;; We do NOT let a real wall-clock timer fire — instead we CAPTURE
;; :rf.resource/schedule-timers args (to assert the poll timer ARMS with the
;; right delay) and DISPATCH :rf.resource.internal/poll-fired directly (to
;; assert the FIRING behaviour), exactly as the focus tests dispatch
;; :rf.resource/window-focused directly. This keeps the suite deterministic +
;; node-runtime-fast (no real timers, no Playwright).

(def ^:private aborts (atom []))
(def ^:private scheduled-timers (atom []))
(def ^:private cancelled-poll (atom []))

(defn- capturing-fixture
  "Override the real :rf.http/managed + :rf.http/managed-abort fxs with
  capturing no-ops; CAPTURE :rf.resource/schedule-timers (so poll arming is
  asserted WITHOUT a real timer firing) and :rf.resource/cancel-poll-timers
  (so the owner-release poll-stop is asserted). Composed INSIDE the
  reset-runtime fixture (one `use-fixtures` call)."
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
  (reset! cancelled-poll [])
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  (rf/reg-fx :rf.resource/cancel-poll-timers (fn [_ctx args] (swap! cancelled-poll conj args) nil))
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
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e) :data data}])))

(defn- fail!
  "Feed an internal FAILED reply for a scoped key (a background refresh
  failure — the entry keeps prior data + records :refresh-error)."
  [scoped-key error]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e)
                        :error {:kind :rf.http/server-error :reason error}}])))

(defn- poll-fired!
  "Dispatch the internal poll-fired re-check for a scoped key (as the host
  `:poll` timer thunk would, carrying the host-read `:hidden?` flag)."
  ([scoped-key] (poll-fired! scoped-key false))
  ([scoped-key hidden?]
   (rf/dispatch-sync [:rf.resource.internal/poll-fired
                      {:resource/key scoped-key :hidden? hidden?}])))

(defn- last-schedule-for
  "The most-recent captured schedule-timers args for a scoped key (nil if
  none)."
  [scoped-key]
  (last (filter #(= scoped-key (:resource/key %)) @scheduled-timers)))

;; ===========================================================================
;; 1. Arming — a settled active-owner poll-enabled entry arms a :poll timer
;; ===========================================================================

(deftest poll-enabled-active-owner-arms-poll-timer-on-settle
  (rf/reg-resource :pl/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :pl/poll {:slug "w"})]
    (ensure! :pl/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Polling — a poll-enabled, actively-owned entry arms a
              :poll timer at :poll-interval-ms when it settles :loaded"
      (let [args (last-schedule-for k)]
        (is (some? args) "schedule-timers emitted for the settled entry")
        (is (= 5000 (:poll-delay-ms args)) "poll delay = :poll-interval-ms")))))

(deftest no-poll-policy-arms-no-poll-timer
  (rf/reg-resource :pl/nopoll (article-spec {:stale-after-ms 1000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :pl/nopoll {:slug "w"})]
    (ensure! :pl/nopoll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Polling — a resource with no :poll-interval-ms arms no
              poll timer (the stale timer still arms)"
      (let [args (last-schedule-for k)]
        (is (some? args) "schedule-timers still emitted (stale policy present)")
        (is (nil? (:poll-delay-ms args)) "no poll delay (no poll policy)")
        (is (= 1000 (:stale-delay-ms args)) "stale delay armed as usual")))))

(deftest owner-free-settle-arms-no-poll-timer
  ;; A poll never pins an owner-free entry: if the entry settles with no active
  ;; owner (the load was caused without a lease, or the owner released before
  ;; the reply landed), no poll timer arms.
  (rf/reg-resource :pl/of (article-spec {:poll-interval-ms 5000 :gc-after-ms 9000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :pl/of {:slug "w"})]
    (ensure! :pl/of scope "w" [:lease :x 1])
    ;; release the owner BEFORE the reply lands → settles owner-free
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Polling — an owner-free settle arms no poll timer (a
              poll never pins an owner-free entry); GC still arms"
      (is (empty? (:active-owners (entry k))) "entry is owner-free at settle")
      (let [args (last-schedule-for k)]
        (is (nil? (:poll-delay-ms args)) "no poll timer armed for an owner-free entry")
        (is (= 9000 (:gc-delay-ms args)) "GC timer armed as usual")))))

(deftest fresh-skip-re-arms-polling-on-new-owner
  ;; rf2-k9u4h3 — an entry that settled `:loaded` while OWNER-FREE armed no poll
  ;; timer (a poll never pins an owner-free entry). A later `ensure` from a NEW
  ;; live owner serves the cached value via the fresh-skip path (the entry is
  ;; still fresh — no `:stale-after-ms`, never invalidated). BEFORE the fix that
  ;; fresh-skip attached the owner but emitted NO schedule-timers, so the
  ;; `refetchInterval` analogue never started. It MUST now (re)arm polling,
  ;; mirroring the success-path arming.
  (rf/reg-resource :fs/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :fs/poll {:slug "w"})]
    (ensure! :fs/poll scope "w" [:lease :x 1])
    ;; release the owner BEFORE the reply lands → settles owner-free (no poll)
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
    (succeed! k {:title "W"})
    (is (empty? (:active-owners (entry k))) "entry settled owner-free")
    (is (nil? (:poll-delay-ms (last-schedule-for k)))
        "no poll timer armed at the owner-free settle (precondition)")
    (reset! scheduled-timers [])
    ;; a fresh ensure from a NEW live owner — served from cache (fresh-skip)
    (ensure! :fs/poll scope "w" [:route :r 2])
    (testing "Spec 016 §Polling — a fresh-skip that attaches a new owner to a
              previously owner-free entry serves the cache AND (re)arms the poll
              timer (the entry now has an active owner)"
      (let [e (entry k)]
        (is (= :loaded (:status e)) "served from cache (no fetch — still :loaded)")
        (is (nil? (:current-work e)) "no in-flight work (fresh-skip, not a load)")
        (is (= #{[:route :r 2]} (:active-owners e)) "the new owner is leased"))
      (let [args (last-schedule-for k)]
        (is (some? args) "schedule-timers emitted for the revived entry")
        (is (= 5000 (:poll-delay-ms args)) "poll re-armed at :poll-interval-ms")))))

(deftest fresh-skip-onto-owned-entry-does-not-re-arm
  ;; Guard against double-arm: a fresh-skip onto an entry that ALREADY has an
  ;; active owner adds a second lease but its poll is already live, so the
  ;; fresh-skip re-arms nothing (the success-path settle armed it).
  (rf/reg-resource :fa/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :fa/poll {:slug "w"})]
    (ensure! :fa/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})   ;; active-owner settle → poll already armed
    (reset! scheduled-timers [])
    ;; a SECOND owner ensures the SAME fresh entry — fresh-skip, but the entry
    ;; was already owned, so no re-arm fires here.
    (ensure! :fa/poll scope "w" [:lease :x 1])
    (testing "rf2-k9u4h3 — a fresh-skip onto an already-OWNED entry adds the
              lease but re-arms NO timer (avoids double-arm; the prior settle
              already armed the poll)"
      (is (= #{[:route :r 1] [:lease :x 1]} (:active-owners (entry k)))
          "both leases attached")
      (is (empty? (filter #(= k (:resource/key %)) @scheduled-timers))
          "no schedule-timers re-armed on a fresh-skip onto an owned entry"))))

;; ===========================================================================
;; 2. Unconditional tick — refetch by interval (not :stale?-gated), re-arm
;; ===========================================================================

(deftest poll-tick-refetches-unconditionally-and-rearms
  ;; The entry is FRESH (no stale policy → never stale). The poll tick MUST
  ;; STILL refetch — the interval IS the cadence (Q3 ruling (a)), not gated on
  ;; :stale?.
  (rf/reg-resource :pt/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :pt/poll {:slug "w"})]
    (ensure! :pt/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (let [gen-before (:generation (entry k))]
      (is (nil? (:current-work (entry k))) "no in-flight work after first settle")
      (reset! scheduled-timers [])
      (poll-fired! k)
      (testing "Spec 016 §Polling Q3(a) — a poll tick refetches a FRESH
                active-owner entry (the interval is the cadence, not :stale?);
                background refetch (prior data kept, :fetching, new generation)"
        (let [e (entry k)]
          (is (= :fetching (:status e)) "poll tick started a background refetch")
          (is (= {:title "W"} (:data e)) "prior data kept (background)")
          (is (= (inc gen-before) (:generation e)) "forced a new generation")))
      (testing "Spec 016 §Polling — the tick re-arms the next poll"
        (let [args (last-schedule-for k)]
          (is (= 5000 (:poll-delay-ms args)) "next poll re-armed at the interval"))))))

(deftest poll-tick-records-poll-cause-never-owner
  (rf/reg-resource :pc/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :pc/poll {:slug "w"})]
    (ensure! :pc/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (poll-fired! k)
    (testing "Spec 016 §Active owners and causes — a poll refetch records the
              :poll CAUSE on the work record but attaches NO new owner"
      (let [e   (entry k)
            rec (work-ledger/get-record (runtime-db) (:current-work e))]
        (is (= #{[:route :r 1]} (:active-owners e)) "owner set unchanged — poll added no owner")
        (is (some #{:poll} (:causes rec)) "the :poll cause is recorded on the refetch")))))

;; ===========================================================================
;; 3. Owner-release stops polling
;; ===========================================================================

(deftest last-owner-release-cancels-poll-timer
  (rf/reg-resource :or/poll (article-spec {:poll-interval-ms 5000 :gc-after-ms 9000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :or/poll {:slug "w"})]
    (ensure! :or/poll scope "w" [:lease :x 1])
    (succeed! k {:title "W"})
    (reset! cancelled-poll [])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
    (testing "Spec 016 §Polling — the last owner releasing cancels the entry's
              :poll timer (poll-only; stale/GC stay armed for the now-inactive
              entry's GC)"
      (is (empty? (:active-owners (entry k))) "entry is owner-free after release")
      (let [args (last (filter #(some #{k} (:resource/keys %)) @cancelled-poll))]
        (is (some? args) "cancel-poll-timers emitted for the now-owner-free entry")))))

(deftest poll-tick-on-owner-free-entry-stops-no-refetch-no-rearm
  ;; Belt-and-braces: even if a poll timer fires AFTER the owner released (a
  ;; race the proactive cancel narrows but the advisory re-check must still
  ;; close), the tick refetches nothing and does not re-arm.
  (rf/reg-resource :os/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :os/poll {:slug "w"})]
    (ensure! :os/poll scope "w" [:lease :x 1])
    (succeed! k {:title "W"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
    (reset! scheduled-timers [])
    (poll-fired! k)
    (testing "Spec 016 §Polling — a poll tick on an owner-free entry is a STOP:
              no refetch, no re-arm (a poll never pins an owner-free entry)"
      (is (= :loaded (:status (entry k))) "no refetch started (still :loaded)")
      (is (nil? (:current-work (entry k))) "no in-flight work")
      (is (empty? (filter #(= k (:resource/key %)) @scheduled-timers))
          "no re-arm — polling stopped"))))

;; ===========================================================================
;; 4. Hidden-tab pause
;; ===========================================================================

(deftest hidden-tab-pauses-tick-but-rearms
  (rf/reg-resource :hd/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :hd/poll {:slug "w"})]
    (ensure! :hd/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (reset! scheduled-timers [])
    (poll-fired! k true) ;; document hidden
    (testing "Spec 016 §Polling — default-pause-when-hidden: a HIDDEN tab
              suppresses the tick (NO refetch) but RE-ARMS so polling resumes
              on tab return"
      (is (= :loaded (:status (entry k))) "hidden tick did NOT refetch")
      (is (nil? (:current-work (entry k))) "no in-flight work while hidden")
      (let [args (last-schedule-for k)]
        (is (= 5000 (:poll-delay-ms args)) "re-armed (resumes on tab return)")))))

(deftest visible-tick-after-hidden-resumes-refetch
  (rf/reg-resource :hr/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :hr/poll {:slug "w"})]
    (ensure! :hr/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (poll-fired! k true)  ;; hidden — paused
    (poll-fired! k false) ;; visible — resumes
    (testing "Spec 016 §Polling — a VISIBLE tick after a hidden pause refetches"
      (is (= :fetching (:status (entry k))) "visible tick resumed the refetch"))))

;; ===========================================================================
;; 5. In-flight coalescing — no overlap on a slow endpoint
;; ===========================================================================

(deftest poll-tick-coalesces-with-live-in-flight-work
  ;; A slow endpoint: the FIRST poll tick starts a refetch that has not yet
  ;; replied; the SECOND tick finds live in-flight work and SKIPS the refetch
  ;; (no second generation, no overlap) but RE-ARMS.
  (rf/reg-resource :if/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :if/poll {:slug "w"})]
    (ensure! :if/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (reset! aborts [])
    (poll-fired! k) ;; first tick — starts the refetch (slow: no reply yet)
    (let [gen-after-1 (:generation (entry k))
          wid-after-1 (:current-work (entry k))]
      (is (= :fetching (:status (entry k))) "first poll tick is in flight")
      (reset! scheduled-timers [])
      (poll-fired! k) ;; second tick — finds live in-flight work
      (testing "Spec 016 §Polling — a poll tick that finds a LIVE in-flight
                refetch coalesces: NO second generation, the SAME work item, NO
                abort churn (a slow endpoint never stacks overlapping requests)"
        (let [e (entry k)]
          (is (= gen-after-1 (:generation e)) "no second generation bump")
          (is (= wid-after-1 (:current-work e)) "same in-flight work item")
          (is (empty? @aborts) "no opportunistic abort (no churn)")))
      (testing "Spec 016 §Polling — a coalesced tick still RE-ARMS the next poll"
        (let [args (last-schedule-for k)]
          (is (= 5000 (:poll-delay-ms args)) "coalesced tick re-armed the poll"))))))

;; ===========================================================================
;; 6. Focus/reconnect coexistence — no double-fetch
;; ===========================================================================

(deftest focus-and-poll-do-not-double-fetch
  ;; A tab return both fires focus revalidation AND resumes the poll. The
  ;; in-flight coalescing gate makes the overlap idempotent: whichever starts
  ;; work first sets :current-work, the other becomes a no-op.
  (rf/reg-resource :fp/poll (article-spec {:poll-interval-ms 5000 :stale-after-ms 0}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :fp/poll {:slug "w"})]
    (ensure! :fp/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (let [gen-before (:generation (entry k))]
      (reset! aborts [])
      (rf/dispatch-sync [:rf.resource/window-focused]) ;; focus starts the refetch
      (poll-fired! k)                                  ;; poll finds it in flight
      (testing "Spec 016 §Polling — focus + poll together bump exactly ONE
                generation (the in-flight gate dedupes the overlap)"
        (let [e (entry k)]
          (is (= (inc gen-before) (:generation e)) "exactly one new generation")
          (is (= :fetching (:status e)) "one in-flight refetch")
          (is (empty? @aborts) "no abort churn from the overlap"))))))

;; ===========================================================================
;; 7. Late-poll-reply stale-suppression
;; ===========================================================================

(deftest late-poll-reply-is-suppressed
  (rf/reg-resource :lp/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :lp/poll {:slug "w"})]
    (ensure! :lp/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (let [gen-before (:generation (entry k))]
      (poll-fired! k) ;; poll forces a new generation
      (is (= (inc gen-before) (:generation (entry k))) "poll bumped a generation")
      (testing "Spec 016 §Cancellation is opportunistic; stale suppression is
                mandatory — a late poll reply carrying the PRE-poll generation
                is suppressed (never overwrites the post-poll entry)"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key k
                            :work/id (work-ledger/resource-work-id k gen-before)
                            :generation gen-before :data {:title "Zombie"}}])
        (is (not= {:title "Zombie"} (:data (entry k)))
            "the pre-poll-generation reply was suppressed")))))

;; ===========================================================================
;; 8. Invalidation resets the poll clock (cancel-then-arm, not stack)
;; ===========================================================================

(deftest reload-reschedules-poll-cancel-then-arm
  ;; A poll tick that settles reschedules the poll (cancel-then-arm). The
  ;; schedule-timers fx is cancel-then-arm by construction (timers/schedule!),
  ;; so a re-load emits a fresh schedule rather than stacking timers.
  (rf/reg-resource :rs/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :rs/poll {:slug "w"})]
    (ensure! :rs/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (poll-fired! k)               ;; tick → refetch in flight
    (reset! scheduled-timers [])
    (succeed! k {:title "W2"})    ;; the poll refetch settles → reschedules
    (testing "Spec 016 §Polling — a settle reschedules the poll (cancel-then-arm
              via timers/schedule!), it does not stack a second poll timer"
      (is (= {:title "W2"} (:data (entry k))) "new data landed")
      (let [args (last-schedule-for k)]
        (is (= 5000 (:poll-delay-ms args)) "poll re-armed on the new settle")))))

;; ===========================================================================
;; 9. Background poll failure keeps polling
;; ===========================================================================

(deftest background-poll-failure-keeps-prior-data-and-keeps-polling
  (rf/reg-resource :bf/poll (article-spec {:poll-interval-ms 5000}))
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :bf/poll {:slug "w"})]
    (ensure! :bf/poll scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (poll-fired! k)            ;; tick → refetch in flight
    (fail! k :transient-503)   ;; the poll refetch FAILS (background)
    (testing "Spec 016 §Polling — a background poll failure keeps prior :data +
              records :refresh-error; the entry stays usable"
      (let [e (entry k)]
        (is (= :loaded (:status e)) "entry stays :loaded (background refresh failure)")
        (is (= {:title "W"} (:data e)) "prior data kept after the failed poll")
        (is (some? (:refresh-error e)) ":refresh-error recorded")))
    (testing "Spec 016 §Polling — the NEXT poll still fires (a transient
              failure never permanently stops a monitor)"
      (reset! scheduled-timers [])
      (poll-fired! k)
      (is (= :fetching (:status (entry k))) "the next poll tick refetched again"))))

;; ===========================================================================
;; 10. Timer-substrate unit — the :poll kind cancel-then-arm + teardown
;; ===========================================================================

(deftest poll-timer-kind-is-cancelled-with-the-key
  (testing "Spec 016 §Polling — cancel-for-key! cancels the :poll kind too (so
            entry removal / clear-scope stops polling); release-frame! drops it"
    (let [fired (atom 0)]
      ;; arm a real poll timer via the substrate (long delay — we cancel before
      ;; it fires; we only assert the side-table slot is dropped)
      (timers/schedule! :pk/frame [:s :pk/r {}] timers/poll-kind 60000)
      (is (contains? @timers/timer-table [:pk/frame (identity/canonical-bytes [:s :pk/r {}]) timers/poll-kind])
          "poll timer armed in the side table")
      (timers/cancel-for-key! :pk/frame [:s :pk/r {}])
      (is (not (contains? @timers/timer-table [:pk/frame (identity/canonical-bytes [:s :pk/r {}]) timers/poll-kind]))
          "cancel-for-key! dropped the :poll slot")
      @fired)))
