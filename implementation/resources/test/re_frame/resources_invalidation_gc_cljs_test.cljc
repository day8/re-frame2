(ns re-frame.resources-invalidation-gc-cljs-test
  "Invalidation / owner-liveness / GC / stale-timer behaviour for the
  Resources artefact (rf2-nbjewi, Spec 016 §EP-0003 slice 6).

  These JVM+CLJS unit tests pin the slice-6 contract:

    1. EXACT TAG INVALIDATION — scoped by DEFAULT (a match needs the entry's
       scope to equal :scope); CROSS-SCOPE is opt-in (:cross-scope? true) and
       Xray-visible; matched active-owner entries refetch, matched ownerless
       entries are left stale / GC-eligible; on a successful (re)load the tag
       index for the key is REPLACED (old tags removed); one decision summary
       + per-entry detail; no-match distinction (match in another scope vs no
       tag anywhere);
    2. ACTIVE OWNERS (liveness leases) + owner-index + :rf.resource/release-
       owner — release drops the owner from the entry + index + work record;
       an in-flight attempt is aborted ONLY when NO owner remains (a shared
       request is not cancelled because one lease went away); causes never
       create liveness;
    3. CLEAR-SCOPE — removes the scope's entries, recomputes indexes,
       cancels their timers, and SUPPRESSES a late reply by the entry-vanish
       + monotone-generation boundary (a recreated entry gets a higher
       generation so the old reply's work-id can never re-match);
    4. STALE / GC TIMERS — freshness is derived from DURABLE timestamps (not
       \"the timer fired on time\"); the timer handler RE-CHECKS the live
       entry / owners / generation before writing (never writes a stale
       decision); timers live in a host SIDE TABLE (not frame-state); a fired
       GC removes an entry ONLY if still owner-free + idle; frame destroy
       cancels all the frame's timers (composed into the single
       :resources/on-frame-destroyed! hook); inactive entries GC after
       :gc-after-ms;
    5. :rf.resource/remove cancels the removed instance's timers."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + the timer / work-ledger side-table fx + the
   ;; internal re-check events these tests dispatch through.
   [re-frame.resources]
   [re-frame.resources.events :as events]
   [re-frame.resources.state :as state]
   [re-frame.resources.timers :as timers]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
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
  firing (the timer-table primitive is tested directly elsewhere). Composed
  INSIDE the reset-runtime fixture (one `use-fixtures` call)."
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
  (state/reset-cache!)
  (work-ledger/reset-cache!)
  (timers/reset-cache!)
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  ;; capture the schedule-timers arming (the real fx arms host timers; here we
  ;; record the args so the test stays deterministic — no wall clock)
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
;; 1. Exact tag invalidation — scoped default + cross-scope opt-in
;; ===========================================================================

(deftest invalidate-tags-is-scoped-by-default
  (rf/reg-resource :iv/article (article-spec))
  (let [sa {:user "a"} sb {:user "b"}
        ka (state/scoped-resource-key sa :iv/article {:slug "w"})
        kb (state/scoped-resource-key sb :iv/article {:slug "w"})]
    (ensure! :iv/article sa "w" [:lease :a 1])
    (succeed! ka {:title "A"})
    (ensure! :iv/article sb "w" [:lease :b 1])
    (succeed! kb {:title "B"})
    ;; release both owners so the invalidation marks stale WITHOUT refetching
    ;; (a refetch would satisfy + clear the invalidation, masking the test)
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :b 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope sa :tags #{[:article "w"]}}])
    (testing "Spec 016 §Invalidation — scoped by DEFAULT: only the in-scope
              entry is marked stale; the other scope is untouched (no
              cross-scope leak)"
      (is (some? (:invalidated-at (entry ka))) "scope A entry marked stale")
      (is (nil?  (:invalidated-at (entry kb))) "scope B entry NOT invalidated"))))

(deftest invalidate-tags-cross-scope-opt-in
  (rf/reg-resource :ivx/article (article-spec))
  (let [sa {:user "a"} sb {:user "b"}
        ka (state/scoped-resource-key sa :ivx/article {:slug "w"})
        kb (state/scoped-resource-key sb :ivx/article {:slug "w"})]
    (ensure! :ivx/article sa "w" [:lease :a 1])
    (succeed! ka {:title "A"})
    (ensure! :ivx/article sb "w" [:lease :b 1])
    (succeed! kb {:title "B"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :b 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope sa :tags #{[:article "w"]} :cross-scope? true}])
    (testing "Spec 016 §Invalidation — :cross-scope? true matches the tag in
              EVERY scope (the explicit, Xray-visible opt-in)"
      (is (some? (:invalidated-at (entry ka))) "scope A entry marked stale")
      (is (some? (:invalidated-at (entry kb))) "scope B entry ALSO marked stale"))))

;; ---- rf2-pvdae1: scoped invalidate-tags fails closed without a scope -------
;; The re-frame event loop catches a handler throw and surfaces it as
;; :rf.error/handler-exception (it does NOT rethrow to dispatch-sync's
;; caller), so the throw is asserted at the fn boundary (calling
;; invalidate-tags-handler directly), exactly as the mutation suite asserts
;; its validation throws. The dispatch-path no-mutation is observed
;; separately.

(defn- invalidate-cofx
  "A minimal cofx for a direct invalidate-tags-handler call: a runtime-db
  value under :rf.db/runtime + a frame id."
  [runtime-db]
  {:rf.db/runtime runtime-db :rf.frame/id :rf/default})

(deftest invalidate-tags-scoped-without-scope-fails-closed
  (testing "rf2-pvdae1 / Spec 016 §Invalidation — a SCOPED (default)
            invalidate-tags with NO :scope is a loud
            :rf.error/resource-invalidate-scope-required (never a silent
            nil-scope match that invalidates nothing or the wrong set)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalidate-scope-required"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags {:tags #{[:article "w"]}}]))))
  (testing "an explicitly nil :scope (not merely absent) is ALSO rejected
            — fail-closed is about the absence of a concrete scope"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalidate-scope-required"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags {:scope nil :tags #{[:article "w"]}}])))))

(deftest invalidate-tags-cross-scope-without-scope-allowed
  ;; the ONLY scope-agnostic path: :cross-scope? true with no :scope is
  ;; permitted (scope-agnostic by construction). Proven end-to-end through
  ;; the dispatch path (no throw → the matched entries are marked stale).
  (rf/reg-resource :ivxn/article (article-spec))
  (let [sa {:user "a"} sb {:user "b"}
        ka (state/scoped-resource-key sa :ivxn/article {:slug "w"})
        kb (state/scoped-resource-key sb :ivxn/article {:slug "w"})]
    (ensure! :ivxn/article sa "w" [:lease :a 1])
    (succeed! ka {:title "A"})
    (ensure! :ivxn/article sb "w" [:lease :b 1])
    (succeed! kb {:title "B"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :b 1]}])
    (testing "rf2-pvdae1 — cross-scope? true with NO :scope is allowed
              (scope-agnostic) and invalidates the tag in every scope"
      (rf/dispatch-sync [:rf.resource/invalidate-tags
                         {:tags #{[:article "w"]} :cross-scope? true}])
      (is (some? (:invalidated-at (entry ka))) "scope A entry marked stale")
      (is (some? (:invalidated-at (entry kb))) "scope B entry marked stale"))))

;; ---- rf2-hosnba: invalidate-tags scope routes through canonicalize-scope ---

(deftest invalidate-tags-rejects-reserved-scope-typo
  (testing "rf2-hosnba / rf2-lzv9xc — a reserved-namespace scope typo
            (:rf.scope/glabal) reaching invalidate-tags fails closed through
            the shared state/canonicalize-scope path (never a silent wrong
            cache scope), surfaced as :rf.error/resource-invalid-scope"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags
             {:scope :rf.scope/glabal :tags #{[:article "w"]}}])))))

(deftest invalidate-tags-rejects-host-scope
  (testing "rf2-hosnba / rf2-lzv9xc — a host / non-EDN scope value reaching
            invalidate-tags is rejected through the shared path
            (:rf.error/resource-non-edn-params)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags
             {:scope {:cb (fn [])} :tags #{[:article "w"]}}])))))

(deftest invalidate-tags-normalizes-singleton-global-scope
  ;; rf2-hosnba / rf2-vv87xz — the historical [:rf.scope/global] singleton
  ;; spelling normalizes to the canonical bare :rf.scope/global so it matches
  ;; the SAME entries an explicit-global resource stored under.
  (rf/reg-resource :ivg/article (article-spec)) ;; :rf.scope/global policy
  (let [k (state/scoped-resource-key :rf.scope/global :ivg/article {:slug "w"})]
    (ensure! :ivg/article :rf.scope/global "w" [:lease :g 1])
    (succeed! k {:title "G"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :g 1]}])
    (testing "the singleton-vector global spelling matches the bare-global key"
      (rf/dispatch-sync [:rf.resource/invalidate-tags
                         {:scope [:rf.scope/global] :tags #{[:article "w"]}}])
      (is (some? (:invalidated-at (entry k)))
          "entry under bare :rf.scope/global was matched via the normalized
           singleton-vector scope"))))

(deftest clear-scope-rejects-reserved-scope-typo
  (testing "rf2-hosnba / rf2-lzv9xc — clear-scope routes its scope through
            the shared state/canonicalize-scope path; a reserved-namespace
            typo (:rf.scope/glabal) fails closed (a typo can never silently
            clear the WRONG scope — a cross-tenant data wipe)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (events/clear-scope-handler
            (invalidate-cofx {})
            [:rf.resource/clear-scope {:scope :rf.scope/glabal :cause :logout}])))))

(deftest invalidate-tags-refetches-active-leaves-inactive-stale
  (rf/reg-resource :ivr/article (article-spec))
  (let [scope {:user "u"}
        kact  (state/scoped-resource-key scope :ivr/article {:slug "active"})
        kin   (state/scoped-resource-key scope :ivr/article {:slug "inactive"})]
    ;; active-owner entry
    (ensure! :ivr/article scope "active" [:route :r 1])
    (succeed! kact {:title "Active"})
    ;; inactive entry (owner released)
    (ensure! :ivr/article scope "inactive" [:lease :x 1])
    (succeed! kin {:title "Inactive"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope scope :tags #{[:article "active"] [:article "inactive"]}}])
    (testing "Spec 016 §Invalidation 3-4 — the active-owner entry refetches
              (→ :fetching, prior data kept); the inactive entry is left
              stale / GC-eligible (NOT refetched)"
      (is (= :fetching (:status (entry kact))) "active entry refetched")
      (is (= {:title "Active"} (:data (entry kact))) "prior data kept on refetch")
      (is (= :loaded (:status (entry kin))) "inactive entry NOT refetched")
      (is (some? (:invalidated-at (entry kin))) "inactive entry left stale"))))

(deftest successful-load-replaces-tag-index
  (rf/reg-resource :tagrep/article
                   (article-spec {:tags (fn [{:keys [slug]} data]
                                          ;; tags depend on the DATA's version
                                          #{[:article slug] [:rev (:rev data)]})}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :tagrep/article {:slug "w"})]
    (ensure! :tagrep/article scope "w" [:lease :t 1])
    (succeed! k {:rev 1})
    (testing "first load produces version-1 tags"
      (is (= #{[:article "w"] [:rev 1]} (:tags (entry k))))
      (is (= #{k} (get-in (runtime-db) (conj (state/tag-index-path) [:rev 1])))))
    ;; refetch → data now rev 2 → tags REPLACED
    (rf/dispatch-sync [:rf.resource/refetch {:resource :tagrep/article :scope scope
                                             :params {:slug "w"}}])
    (succeed! k {:rev 2})
    (testing "Spec 016 §Invalidation — on a successful (re)load the tag index
              for the key is REPLACED with the new data's tags; the OLD tag is
              removed (stale list/detail relationships stop receiving
              invalidations)"
      (is (= #{[:article "w"] [:rev 2]} (:tags (entry k))) "entry tags replaced")
      (is (= #{k} (get-in (runtime-db) (conj (state/tag-index-path) [:rev 2])))
          "new tag indexed")
      (is (nil? (get-in (runtime-db) (conj (state/tag-index-path) [:rev 1])))
          "OLD tag removed from the index (not accumulated)"))))

;; ===========================================================================
;; 2. Active owners — release aborts ONLY orphaned in-flight work
;; ===========================================================================

(deftest release-owner-does-not-abort-shared-in-flight
  (rf/reg-resource :sh/article (article-spec))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :sh/article {:slug "w"})]
    ;; two owners ensure the SAME in-flight key (dedupe joins)
    (ensure! :sh/article scope "w" [:route :r 1])
    (ensure! :sh/article scope "w" [:lease :x 1])
    (is (= #{[:route :r 1] [:lease :x 1]} (:active-owners (entry k))))
    (let [wid (:current-work (entry k))]
      (reset! aborts [])
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:route :r 1]}])
      (testing "Spec 016 §Race — releasing ONE owner of a shared in-flight
                request does NOT abort it (a remaining owner still needs it)"
        (is (= #{[:lease :x 1]} (:active-owners (entry k))) "one owner dropped")
        (is (= [] @aborts) "no abort emitted (work still owned)")
        (is (= #{[:lease :x 1]} (:owners (work-ledger/get-record (runtime-db) wid)))
            "work record owners updated"))
      (testing "releasing the LAST owner orphans the in-flight attempt →
                opportunistic abort (best-effort; stale suppression is the
                real boundary)"
        (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 1]}])
        (is (empty? (:active-owners (entry k))) "entry now owner-free")
        (is (= [wid] @aborts) "orphaned in-flight attempt aborted")
        (is (= :abort-requested
               (:status (work-ledger/get-record (runtime-db) wid)))
            "work row moved to :abort-requested")))))

;; ===========================================================================
;; 3. clear-scope — suppress late reply by entry-vanish + generation
;; ===========================================================================

(deftest clear-scope-suppresses-late-reply
  (rf/reg-resource :clr/article (article-spec))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :clr/article {:slug "w"})]
    (ensure! :clr/article scope "w" [:lease :c 1])
    (let [stale-wid (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource/clear-scope {:scope scope :cause :logout}])
      (is (nil? (entry k)) "entry removed by clear-scope")
      (testing "Spec 016 §clear-scope — a late reply for the cleared entry's
                work-id is SUPPRESSED (the entry it would write into is gone;
                the generation/work-id check finds no live entry)"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key k :work-id stale-wid :generation 1
                            :data {:title "Late"}}])
        (is (nil? (entry k)) "late reply did NOT resurrect / write the entry"))
      (testing "a recreated entry in the same scope gets a HIGHER generation
                (monotone host-side allocator), so the old reply's work-id can
                never re-match (anti-recycling)"
        (ensure! :clr/article scope "w" [:lease :c 2])
        (is (= 2 (:generation (entry k))) "recreated entry on a fresh generation")
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key k :work-id stale-wid :generation 1
                            :data {:title "ZombieLate"}}])
        (is (not= {:title "ZombieLate"} (:data (entry k)))
            "the pre-clear reply never writes the recreated entry")))))

;; ===========================================================================
;; 4. Stale / GC timers — side table + re-check before write + frame destroy
;; ===========================================================================

(deftest succeeded-arms-stale-and-gc-timers
  (rf/reg-resource :tm/article (article-spec {:stale-after-ms 60000 :gc-after-ms 300000}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :tm/article {:slug "w"})]
    (reset! scheduled-timers [])
    (ensure! :tm/article scope "w" [:lease :tm 1])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Stale and GC scheduling — a successful load emits one
              :rf.resource/schedule-timers fx carrying the durable delays
              derived from the resource policy"
      (is (= 1 (count @scheduled-timers)))
      (let [args (first @scheduled-timers)]
        (is (= k (:resource-key args)))
        (is (= 60000 (:stale-delay-ms args)))
        (is (= 300000 (:gc-delay-ms args)))))))

(deftest no-policy-arms-no-timers
  (rf/reg-resource :np/article (article-spec)) ;; no :stale-after-ms / :gc-after-ms
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :np/article {:slug "w"})]
    (reset! scheduled-timers [])
    (ensure! :np/article scope "w" [:lease :np 1])
    (succeed! k {:title "W"})
    (testing "a resource declaring no stale / GC policy arms NO timers (no
              schedule-timers fx)"
      (is (= [] @scheduled-timers)))))

(deftest timer-side-table-is-host-side-not-frame-state
  (testing "Spec 016 §Stale and GC scheduling — timers live in a host SIDE
            TABLE keyed by [frame-id resource-key kind], NOT in frame-state"
    (timers/reset-cache!)
    (let [k [:rf.scope/global :t/x {:id 1}]]
      ;; arm a real (long) timer so it does not fire during the test
      (timers/schedule! :rf/default k timers/gc-kind 1000000)
      (is (contains? @timers/timer-table [:rf/default k timers/gc-kind])
          "the timer handle lives in the module-level side table")
      ;; the durable runtime-db carries NO timer handle
      (is (not (contains? (runtime-db) :rf.runtime/resources-timers))
          "no timer state leaked into runtime-db")
      (timers/cancel! :rf/default k timers/gc-kind)
      (is (not (contains? @timers/timer-table [:rf/default k timers/gc-kind]))
          "cancel! drops the handle"))))

(deftest timer-reschedule-cancels-prior
  (testing "Spec 016 — a re-load reschedules (cancel-then-arm), not accumulate"
    (timers/reset-cache!)
    (let [k [:rf.scope/global :t/y {:id 1}]]
      (timers/schedule! :rf/default k timers/stale-kind 1000000)
      (let [h1 (get @timers/timer-table [:rf/default k timers/stale-kind])]
        (timers/schedule! :rf/default k timers/stale-kind 1000000)
        (let [h2 (get @timers/timer-table [:rf/default k timers/stale-kind])]
          (is (not= h1 h2) "a fresh handle replaced the prior one")
          (is (= 1 (count (filter (fn [[[_ rk kind] _]]
                                    (and (= rk k) (= kind timers/stale-kind)))
                                  @timers/timer-table)))
              "exactly one live stale timer per [key kind]")))
      (timers/cancel-for-key! :rf/default k))))

(deftest gc-fired-rechecks-before-removing
  (rf/reg-resource :gc/article (article-spec {:gc-after-ms 1000}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gc/article {:slug "w"})]
    ;; load + release owner → owner-free + idle → GC-eligible
    (ensure! :gc/article scope "w" [:lease :gc 1])
    (succeed! k {:title "W"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gc 1]}])
    (testing "Spec 016 §Stale and GC scheduling — a fired GC timer RE-CHECKS:
              an owner-free + idle entry is removed"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource-key k}])
      (is (nil? (entry k)) "GC removed the inactive entry"))))

(deftest gc-fired-skips-when-owner-reattached
  (rf/reg-resource :gck/article (article-spec {:gc-after-ms 1000}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gck/article {:slug "w"})]
    (ensure! :gck/article scope "w" [:lease :gck 1])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Stale and GC scheduling — a fired GC timer RE-CHECKS
              owner sets after wake; an entry with a live owner is NOT removed
              (the timer is advisory — it never writes a stale decision)"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource-key k}])
      (is (some? (entry k)) "owned entry kept (GC skipped)")
      (is (= :loaded (:status (entry k)))))))

(deftest gc-fired-skips-when-in-flight
  (rf/reg-resource :gcf/article (article-spec {:gc-after-ms 1000}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gcf/article {:slug "w"})]
    ;; ensure without ever succeeding → in flight (owner released, still
    ;; :current-work)
    (ensure! :gcf/article scope "w" [:lease :gcf 1])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gcf 1]}])
    (is (some? (:current-work (entry k))) "still in flight")
    (testing "Spec 016 §Stale and GC scheduling — a fired GC timer RE-CHECKS
              the generation / in-flight pointer; an in-flight entry is NOT
              removed even when owner-free"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource-key k}])
      (is (some? (entry k)) "in-flight entry kept (GC skipped)"))))

(deftest stale-fired-rechecks-durable-fact-no-write
  (rf/reg-resource :sf/article (article-spec {:stale-after-ms 60000}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :sf/article {:slug "w"})]
    (ensure! :sf/article scope "w" [:lease :sf 1])
    (succeed! k {:title "W"})
    (let [before (entry k)]
      (testing "Spec 016 §Stale and GC scheduling — the stale-timer re-check
                derives freshness from the DURABLE :stale-at; it writes NO
                durable change (the :stale? sub derives staleness; the timer
                is advisory)"
        (rf/dispatch-sync [:rf.resource.internal/stale-fired {:resource-key k}])
        (is (= before (entry k)) "stale-fired made no durable entry change")))))

(deftest frame-destroy-cancels-resource-timers
  (rf/reg-resource :fd/article (article-spec {:stale-after-ms 60000 :gc-after-ms 300000}))
  (let [fa :fd/frame-a
        k  (state/scoped-resource-key {:user "u"} :fd/article {:slug "w"})]
    (rf/reg-frame fa {:doc "frame-destroy timer frame"})
    ;; arm two long timers in frame A directly via the side-table primitive
    (timers/schedule! fa k timers/stale-kind 1000000)
    (timers/schedule! fa k timers/gc-kind 1000000)
    (is (= 2 (count (filter (fn [[[fid _ _] _]] (= fid fa)) @timers/timer-table)))
        "two timers armed for frame A")
    (testing "Spec 016 §Stale and GC scheduling — frame destroy cancels ALL
              of the frame's resource timers (composed into the single
              :resources/on-frame-destroyed! teardown hook, not a second
              teardown path)"
      (frame/destroy-frame! fa)
      (is (= 0 (count (filter (fn [[[fid _ _] _]] (= fid fa)) @timers/timer-table)))
          "frame A's timers cancelled + dropped on destroy"))))

;; ===========================================================================
;; 5. remove cancels the instance's timers
;; ===========================================================================

(deftest remove-cancels-instance-timers
  (rf/reg-resource :rmt/article (article-spec {:gc-after-ms 1000}))
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :rmt/article {:slug "w"})]
    (ensure! :rmt/article scope "w" [:lease :rmt 1])
    (succeed! k {:title "W"})
    ;; arm a real long timer so remove has something to cancel
    (timers/schedule! :rf/default k timers/gc-kind 1000000)
    (is (contains? @timers/timer-table [:rf/default k timers/gc-kind]))
    (testing "Spec 016 §Events / §Stale and GC scheduling — :rf.resource/remove
              evicts the instance AND cancels its advisory timers"
      (rf/dispatch-sync [:rf.resource/remove {:resource :rmt/article :scope scope
                                              :params {:slug "w"}}])
      (is (nil? (entry k)) "instance removed")
      (is (not (contains? @timers/timer-table [:rf/default k timers/gc-kind]))
          "its GC timer cancelled"))))
