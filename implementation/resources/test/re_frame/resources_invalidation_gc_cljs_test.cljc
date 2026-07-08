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
   [re-frame.http.managed]
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
  INSIDE the reset-runtime fixture (one `use-fixtures` call).

  rf2-784223: the shared `make-reset-runtime-fixture`'s
  `:resources/reset-resources!` post-dispose hook already clears the state /
  work-ledger / timer host caches before this fixture runs, so no per-suite
  reset is repeated here. (The timer-PRIMITIVE tests below keep their own
  `timers/reset-cache!` calls — those are direct primitive assertions that
  reset just before arming a real timer, not fixture-level cache hygiene.)"
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
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
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/from-caller
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

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
  "Feed an internal FAILED reply (a non-abort transport error) for a scoped
  key, reading the LIVE entry's current work-id + generation. On an entry with
  no usable data this is a FIRST-LOAD failure → `:error`."
  [scoped-key reason]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e)
                        :error {:kind :rf.http/server-error :reason reason}}])))

(defn- abort!
  "Feed an internal FAILED reply carrying an `:rf.http/aborted` envelope (an
  intentional cancellation, not a failure — rf2-z70ujl) for a scoped key,
  reading the LIVE entry's current work-id + generation. `failed-handler`
  branches this into the ABORT / cancellation settle, never `:error`."
  [scoped-key]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e)
                        :error {:kind :rf.http/aborted :reason :user}}])))

(defn- last-schedule-for [scoped-key]
  (last (filter #(= scoped-key (:resource/key %)) @scheduled-timers)))

;; ===========================================================================
;; 1. Exact tag invalidation — scoped default + cross-scope opt-in
;; ===========================================================================

(deftest invalidate-tags-is-scoped-by-default
  (rf/reg-resource :iv/article (article-spec) article-spec-request)
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
  (rf/reg-resource :ivx/article (article-spec) article-spec-request)
  (let [sa {:user "a"} sb {:user "b"}
        ka (state/scoped-resource-key sa :ivx/article {:slug "w"})
        kb (state/scoped-resource-key sb :ivx/article {:slug "w"})]
    (ensure! :ivx/article sa "w" [:lease :a 1])
    (succeed! ka {:title "A"})
    (ensure! :ivx/article sb "w" [:lease :b 1])
    (succeed! kb {:title "B"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :b 1]}])
    ;; cross-scope is the AUDITED escape — it MUST carry :cause (rf2-7r8kgd)
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope sa :tags #{[:article "w"]} :cross-scope? true
                        :cause [:admin/cache-poisoning-response]}])
    (testing "Spec 016 §Invalidation — :cross-scope? true matches the tag in
              EVERY scope (the explicit, Xray-visible opt-in)"
      (is (some? (:invalidated-at (entry ka))) "scope A entry marked stale")
      (is (some? (:invalidated-at (entry kb))) "scope B entry ALSO marked stale"))))

(deftest invalidate-tags-invalidated-at-is-the-event-time-ms
  ;; rf2-258p1z / EP-0010 §Resources, Mutations, And Work-Ledger Timestamps:
  ;; the durable :invalidated-at is the INVALIDATION EVENT'S :time-ms (the
  ;; causal world input), NOT an ambient clock read in the reducer. Scripting
  ;; the dispatch's :rf.cofx pins the value; replaying the SAME token
  ;; rewrites the SAME :invalidated-at (replay-stable, not the live clock).
  (rf/reg-resource :ivt/article (article-spec) article-spec-request)
  (let [sa {:user "a"}
        ka (state/scoped-resource-key sa :ivt/article {:slug "w"})
        t1 1781078400123
        t2 1781078999999]
    (ensure! :ivt/article sa "w" [:lease :a 1])
    (succeed! ka {:title "A"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope sa :tags #{[:article "w"]}}]
                      {:rf.cofx {:rf/time-ms t1}})
    (testing ":invalidated-at is EXACTLY the supplied token :time-ms (not now)"
      (is (= t1 (:invalidated-at (entry ka)))))
    ;; re-invalidate with a DIFFERENT scripted time — the durable fact tracks
    ;; the causal token, so it moves to the new token's :time-ms (proving it
    ;; is read off the token, not a fresh clock read).
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope sa :tags #{[:article "w"]}}]
                      {:rf.cofx {:rf/time-ms t2}})
    (testing "replaying with a new scripted token rewrites :invalidated-at to
              that token's :time-ms (read off the token, not the clock)"
      (is (= t2 (:invalidated-at (entry ka)))))))

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
  ;; rf2-7r8kgd: cross-scope is the AUDITED escape, so it MUST still carry
  ;; :cause even when it carries no :scope (scope-agnostic ≠ cause-free).
  (rf/reg-resource :ivxn/article (article-spec) article-spec-request)
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
              (scope-agnostic) and invalidates the tag in every scope (with
              :cause, the audited escape, per rf2-7r8kgd)"
      (rf/dispatch-sync [:rf.resource/invalidate-tags
                         {:tags #{[:article "w"]} :cross-scope? true
                          :cause [:migration/article-schema-v2]}])
      (is (some? (:invalidated-at (entry ka))) "scope A entry marked stale")
      (is (some? (:invalidated-at (entry kb))) "scope B entry marked stale"))))

;; ---- rf2-7r8kgd: cross-scope MUST carry :cause (the audited escape) ---------

(deftest invalidate-tags-cross-scope-without-cause-fails-closed
  (testing "rf2-7r8kgd / Spec 016 §The cross-scope lattice — a :cross-scope?
            true invalidate-tags with NO :cause is a loud
            :rf.error/resource-cross-scope-cause-required (never a silent
            unaudited cross-scope sweep). Cross-scope is the audited escape; it
            MUST carry the privacy-relevant :cause evidence."
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-cross-scope-cause-required"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags
             {:tags #{[:article "w"]} :cross-scope? true}]))))
  (testing "an explicitly nil :cause (not merely absent) is ALSO rejected —
            fail-closed is about the absence of :cause evidence"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-cross-scope-cause-required"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags
             {:scope {:user "a"} :tags #{[:article "w"]}
              :cross-scope? true :cause nil}]))))
  (testing "cross-scope WITH a :cause is accepted (the audited escape clears
            the gate) — no throw"
    (is (map?
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags
             {:tags #{[:article "w"]} :cross-scope? true
              :cause [:admin/manual-purge]}])))))

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

(deftest invalidate-tags-rejects-singleton-global-scope
  ;; rf2-hosnba / rf2-bwwk6l — the historical [:rf.scope/global] singleton
  ;; spelling is NO LONGER an accepted global alias; an invalidate-tags scope
  ;; carrying it fails closed (the global scope IS the bare keyword). The
  ;; back-compat normalize that silently collapsed it is removed pre-alpha.
  (testing "the wrapped singleton-vector global spelling is rejected
            fail-closed at the shared scope-validation boundary"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags
             {:scope [:rf.scope/global] :tags #{[:article "w"]}}])))))

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
  (rf/reg-resource :ivr/article (article-spec) article-spec-request)
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
                                          #{[:article slug] [:rev (:rev data)]})})
                   article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :tagrep/article {:slug "w"})]
    (ensure! :tagrep/article scope "w" [:lease :t 1])
    (succeed! k {:rev 1})
    (testing "first load produces version-1 tags"
      (is (= #{[:article "w"] [:rev 1]} (:tags (entry k))))
      ;; rf2-9e0tyq — tag-index members are the byte key-id.
      (is (= #{(state/key-id k)} (get-in (runtime-db) (conj (state/tag-index-path) [:rev 1])))))
    ;; refetch → data now rev 2 → tags REPLACED
    (rf/dispatch-sync [:rf.resource/refetch {:resource :tagrep/article :scope scope
                                             :params {:slug "w"}}])
    (succeed! k {:rev 2})
    (testing "Spec 016 §Invalidation — on a successful (re)load the tag index
              for the key is REPLACED with the new data's tags; the OLD tag is
              removed (stale list/detail relationships stop receiving
              invalidations)"
      (is (= #{[:article "w"] [:rev 2]} (:tags (entry k))) "entry tags replaced")
      (is (= #{(state/key-id k)} (get-in (runtime-db) (conj (state/tag-index-path) [:rev 2])))
          "new tag indexed")
      (is (nil? (get-in (runtime-db) (conj (state/tag-index-path) [:rev 1])))
          "OLD tag removed from the index (not accumulated)"))))

;; ===========================================================================
;; 2. Active owners — release aborts ONLY orphaned in-flight work
;; ===========================================================================

(deftest release-owner-does-not-abort-shared-in-flight
  (rf/reg-resource :sh/article (article-spec) article-spec-request)
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
        ;; rf2-sxyrzk — abort carries the frame-QUALIFIED transport request-id
        ;; (`managed-request-id`), NOT the bare work-id (the managed-HTTP
        ;; registry keys by request-id process-globally; a bare work-id would
        ;; collide across frames).
        (is (= [(work-ledger/managed-request-id :rf/default wid)] @aborts)
            "orphaned in-flight attempt aborted (by qualified request-id)")
        (is (= :abort-requested
               (:status (work-ledger/get-record (runtime-db) wid)))
            "work row moved to :abort-requested")))))

;; ---- rf2-v4ygg5: abort-requested / terminal work is NON-joinable ----------
;; A re-ensure after the last owner released (→ :abort-requested) or after a
;; direct/internal aborted settle (→ terminal :cancelled) must NOT dedupe onto
;; the doomed/dead prior attempt — it must start a FRESH generation. (Stale
;; :current-work pointers survive both paths; the LINKED RECORD'S status is
;; the joinability boundary, not the pointer.)

(deftest re-ensure-after-owner-release-does-not-join-abort-requested
  (rf/reg-resource :nj/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :nj/article {:slug "w"})]
    ;; load attempt in flight, single owner
    (ensure! :nj/article scope "w" [:route :r 1])
    (let [wid1 (:current-work (entry k))
          gen1 (:generation (entry k))]
      (is (= :running (:status (work-ledger/get-record (runtime-db) wid1))))
      ;; the LAST owner releases → the work record is marked :abort-requested
      ;; (opportunistic abort issued) but the entry still POINTS at wid1.
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:route :r 1]}])
      (is (= :abort-requested (:status (work-ledger/get-record (runtime-db) wid1)))
          "released-last-owner work is abort-requested")
      (is (= wid1 (:current-work (entry k)))
          "entry still points at the abort-requested work (stale pointer)")
      (testing "rf2-v4ygg5 — an immediate re-ensure does NOT join the
                abort-requested work; it starts a FRESH generation + work id"
        (ensure! :nj/article scope "w" [:route :r 2])
        (let [e (entry k)]
          (is (= (inc gen1) (:generation e)) "fresh generation (no dedupe onto dead work)")
          (is (not= wid1 (:current-work e)) "a new work id, not the abort-requested one")
          (is (= :running (:status (work-ledger/get-record (runtime-db) (:current-work e))))
              "the new attempt is live")
          (is (contains? (:active-owners e) [:route :r 2])
              "the new owner is attached to the fresh attempt"))))))

(deftest re-ensure-after-internal-aborted-settle-does-not-join-terminal
  (rf/reg-resource :njt/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :njt/article {:slug "w"})]
    (ensure! :njt/article scope "w" [:lease :a 1])
    (let [wid1 (:current-work (entry k))
          gen1 (:generation (entry k))]
      ;; a direct internal aborted settle marks the work TERMINAL :cancelled
      ;; (the aborted-handler makes no entry write — the pointer dangles).
      (rf/dispatch-sync [:rf.resource.internal/aborted
                         {:resource/key k :work/id wid1 :generation gen1}])
      (is (= :cancelled (:status (work-ledger/get-record (runtime-db) wid1)))
          "the directly-aborted work row is terminal :cancelled")
      (testing "rf2-v4ygg5 — a subsequent ensure does NOT join the terminal
                work; it starts a fresh generation"
        (ensure! :njt/article scope "w" [:lease :a 2])
        (let [e (entry k)]
          (is (= (inc gen1) (:generation e)) "fresh generation")
          (is (not= wid1 (:current-work e)) "a new live work id"))))))

;; ===========================================================================
;; 3. clear-scope — suppress late reply by entry-vanish + generation
;; ===========================================================================

(deftest clear-scope-suppresses-late-reply
  (rf/reg-resource :clr/article (article-spec) article-spec-request)
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
                           {:resource/key k :work/id stale-wid :generation 1
                            :data {:title "Late"}}])
        (is (nil? (entry k)) "late reply did NOT resurrect / write the entry"))
      (testing "a recreated entry in the same scope gets a HIGHER generation
                (monotone host-side allocator), so the old reply's work-id can
                never re-match (anti-recycling)"
        (ensure! :clr/article scope "w" [:lease :c 2])
        (is (= 2 (:generation (entry k))) "recreated entry on a fresh generation")
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key k :work/id stale-wid :generation 1
                            :data {:title "ZombieLate"}}])
        (is (not= {:title "ZombieLate"} (:data (entry k)))
            "the pre-clear reply never writes the recreated entry")))))

;; ===========================================================================
;; 4. Stale / GC timers — side table + re-check before write + frame destroy
;; ===========================================================================

(deftest succeeded-arms-stale-and-gc-timers
  (rf/reg-resource :tm/article (article-spec {:stale-after-ms 60000 :gc-after-ms 300000}) article-spec-request)
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
        (is (= k (:resource/key args)))
        (is (= 60000 (:stale-delay-ms args)))
        (is (= 300000 (:gc-delay-ms args)))))))

(deftest no-explicit-policy-arms-default-gc-timer
  ;; rf2-bbpu11 (Option A) — absent :gc-after-ms no longer means "arm
  ;; nothing"; it normalizes AT REGISTRATION to the framework's finite
  ;; default (300000ms), so a settle DOES arm a GC timer even though this
  ;; resource declares no explicit policy. :stale-after-ms is unaffected by
  ;; this bead — it keeps its own independent absent-default (never
  ;; time-stale) — so :stale-delay-ms stays nil.
  (rf/reg-resource :np/article (article-spec) article-spec-request) ;; no :stale-after-ms / :gc-after-ms
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :np/article {:slug "w"})]
    (reset! scheduled-timers [])
    (ensure! :np/article scope "w" [:lease :np 1])
    (succeed! k {:title "W"})
    (testing "a resource declaring no explicit GC policy still arms the
              DEFAULT GC timer (no more silent infinite lingering); stale
              stays unarmed (its own absent-default is never-time-stale)"
      (is (= 1 (count @scheduled-timers)))
      (let [args (first @scheduled-timers)]
        (is (= k (:resource/key args)))
        (is (nil? (:stale-delay-ms args)))
        (is (= 300000 (:gc-delay-ms args)))))))

(deftest gc-after-ms-never-arms-no-gc-timer
  ;; rf2-bbpu11 — the explicit, auditable opt-out: a resource that declares
  ;; :gc-after-ms :never arms NO GC timer at all (the owner-free entry
  ;; lingers indefinitely, by declared intent rather than by omission).
  (rf/reg-resource :npn/article (article-spec {:gc-after-ms :never}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :npn/article {:slug "w"})]
    (reset! scheduled-timers [])
    (ensure! :npn/article scope "w" [:lease :npn 1])
    (succeed! k {:title "W"})
    (testing ":gc-after-ms :never arms no timer at all (no schedule-timers fx)"
      (is (= [] @scheduled-timers)))))

(deftest timer-side-table-is-host-side-not-frame-state
  (testing "Spec 016 §Stale and GC scheduling — timers live in a host SIDE
            TABLE keyed by [frame-id resource-key kind], NOT in frame-state"
    (timers/reset-cache!)
    ;; rf2-9e0tyq — the side-table key's resource-key element is the CEDN-1
    ;; byte `key-id` (so a list- and a vector-params resource never share a
    ;; timer slot). The dispatched recheck event still carries the vector.
    (let [k    [:rf.scope/global :t/x {:id 1}]
          k-id (state/key-id k)]
      ;; arm a real (long) timer so it does not fire during the test
      (timers/schedule! :rf/default k timers/gc-kind 1000000)
      (is (contains? @timers/timer-table [:rf/default k-id timers/gc-kind])
          "the timer handle lives in the module-level side table")
      ;; the durable runtime-db carries NO timer handle
      (is (not (contains? (runtime-db) :rf.runtime/resources-timers))
          "no timer state leaked into runtime-db")
      (timers/cancel! :rf/default k timers/gc-kind)
      (is (not (contains? @timers/timer-table [:rf/default k-id timers/gc-kind]))
          "cancel! drops the handle"))))

(deftest timer-reschedule-cancels-prior
  (testing "Spec 016 — a re-load reschedules (cancel-then-arm), not accumulate"
    (timers/reset-cache!)
    (let [k    [:rf.scope/global :t/y {:id 1}]
          k-id (state/key-id k)]
      (timers/schedule! :rf/default k timers/stale-kind 1000000)
      (let [h1 (get @timers/timer-table [:rf/default k-id timers/stale-kind])]
        (timers/schedule! :rf/default k timers/stale-kind 1000000)
        (let [h2 (get @timers/timer-table [:rf/default k-id timers/stale-kind])]
          (is (not= h1 h2) "a fresh handle replaced the prior one")
          (is (= 1 (count (filter (fn [[[_ rk kind] _]]
                                    (and (= rk k-id) (= kind timers/stale-kind)))
                                  @timers/timer-table)))
              "exactly one live stale timer per [key kind]")))
      (timers/cancel-for-key! :rf/default k))))

(deftest gc-fired-rechecks-before-removing
  (rf/reg-resource :gc/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gc/article {:slug "w"})]
    ;; load + release owner → owner-free + idle → GC-eligible
    (ensure! :gc/article scope "w" [:lease :gc 1])
    (succeed! k {:title "W"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gc 1]}])
    (testing "Spec 016 §Stale and GC scheduling — a fired GC timer RE-CHECKS:
              an owner-free + idle entry is removed"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (nil? (entry k)) "GC removed the inactive entry"))))

(deftest first-load-error-arms-gc-and-is-collected-after-release
  ;; rf2-ar9pcx — a FIRST load that FAILS settles `:error` with `:current-work
  ;; nil`. BEFORE the fix, GC timers were armed only on a SUCCESSFUL settle, so
  ;; an owner-free errored entry was never reaped (an unbounded cache leak for
  ;; the frame's life). The `:error` settle MUST now arm a GC timer (mirroring
  ;; the success-path arming), and once owner-free + idle the fired GC re-check
  ;; collects it.
  (rf/reg-resource :gce/article (article-spec {:gc-after-ms 5000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gce/article {:slug "w"})]
    (reset! scheduled-timers [])
    (ensure! :gce/article scope "w" [:lease :gce 1])
    (fail! k :transient-500)   ;; first load fails → :error (no usable data)
    (testing "rf2-ar9pcx / Spec 016 §Stale and GC scheduling — a first-load
              `:error` settle arms the GC timer (so the errored entry can be
              reaped) — it was never armed before this fix"
      (let [e (entry k)]
        (is (= :error (:status e)) "first-load failure settled :error")
        (is (nil? (:current-work e)) "no in-flight work after the error settle"))
      (let [args (last-schedule-for k)]
        (is (some? args) "schedule-timers emitted on the :error settle")
        (is (= 5000 (:gc-delay-ms args)) "GC timer armed at :gc-after-ms")
        (is (nil? (:poll-delay-ms args)) "no poll timer for an errored entry")))
    (testing "the owner releases → owner-free + idle + :error; the fired GC
              re-check collects the errored entry (no longer leaked)"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gce 1]}])
      (is (empty? (:active-owners (entry k))) "entry now owner-free")
      (is (= :error (:status (entry k))) "still :error, idle (no current-work)")
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (nil? (entry k)) "GC reaped the owner-free errored entry"))))

(deftest first-load-abort-arms-gc-and-is-collected-after-release
  ;; rf2-kz5op1 — a FIRST load that is ABORTED (an intentional cancellation,
  ;; NOT a failure — rf2-z70ujl) settles a non-error stable `:idle` with
  ;; `:current-work nil` (no usable data). The `:error` settle twin
  ;; (rf2-ar9pcx) already arms a GC timer for this shape; BEFORE this fix the
  ;; abort sibling did not, so an owner-free `:idle` entry from a cancelled
  ;; first load was NEVER reaped — the same unbounded per-frame cache leak,
  ;; via the different non-error settle path. The abort settle MUST now ALSO
  ;; arm a GC timer (mirroring the :error twin), and once owner-free the fired
  ;; GC re-check collects it.
  (rf/reg-resource :gca/article (article-spec {:gc-after-ms 5000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gca/article {:slug "w"})]
    (reset! scheduled-timers [])
    (ensure! :gca/article scope "w" [:lease :gca 1])
    (abort! k)   ;; first-load aborted → :idle (no usable data)
    (testing "rf2-kz5op1 / Spec 016 §Cancellation is opportunistic / §Stale and
              GC scheduling — a first-load ABORT settle arms the GC timer (so
              the owner-free idle entry can be reaped) — it was never armed
              before this fix"
      (let [e (entry k)]
        (is (= :idle (:status e)) "first-load abort settled :idle (no usable data)")
        (is (nil? (:current-work e)) "no in-flight work after the abort settle"))
      (let [args (last-schedule-for k)]
        (is (some? args) "schedule-timers emitted on the abort settle")
        (is (= 5000 (:gc-delay-ms args)) "GC timer armed at :gc-after-ms")
        (is (nil? (:poll-delay-ms args)) "no poll timer for an aborted entry")))
    (testing "the owner releases → owner-free + idle; the fired GC re-check
              collects the aborted entry (no longer leaked)"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gca 1]}])
      (is (empty? (:active-owners (entry k))) "entry now owner-free")
      (is (= :idle (:status (entry k))) "still :idle, no current-work")
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (nil? (entry k)) "GC reaped the owner-free aborted entry"))))

(deftest background-refresh-abort-does-not-rearm-gc
  ;; rf2-kz5op1 guard — a BACKGROUND-refresh abort (the entry keeps prior data
  ;; and returns to `:loaded`, not `:idle`) is NOT a first-load abort: its
  ;; stale/GC timers were armed on the prior success, and `entry-abort-settled`
  ;; makes no freshness change, so the refresh-abort settle re-arms NOTHING (no
  ;; double-arm) — mirrors `background-refresh-failure-does-not-rearm-gc`.
  (rf/reg-resource :gcra/article (article-spec {:gc-after-ms 5000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gcra/article {:slug "w"})]
    (ensure! :gcra/article scope "w" [:route :r 1])
    (succeed! k {:title "W"})        ;; first load succeeds → :loaded, GC armed
    ;; a background refetch that is then aborted (entry keeps data, returns to
    ;; :loaded)
    (rf/dispatch-sync [:rf.resource/refetch {:resource :gcra/article :scope scope
                                             :params {:slug "w"}}])
    (reset! scheduled-timers [])
    (abort! k)                       ;; background refresh abort
    (testing "rf2-kz5op1 — a background-refresh abort (status :loaded, data
              kept) re-arms NO timer (the GC was already armed on the prior
              success — no double-arm from the abort path)"
      (let [e (entry k)]
        (is (= :loaded (:status e)) "background refresh abort kept :loaded")
        (is (= {:title "W"} (:data e)) "prior data kept"))
      (is (empty? (filter #(= k (:resource/key %)) @scheduled-timers))
          "no schedule-timers re-armed on the background-refresh abort"))))

(deftest background-refresh-failure-does-not-rearm-gc
  ;; rf2-ar9pcx guard — a BACKGROUND-refresh failure (the entry keeps prior data
  ;; and returns to `:loaded`) is NOT a first-load error: its stale/GC timers
  ;; were armed on the prior success, and `entry-failed` makes no freshness
  ;; change, so the refresh-failure settle re-arms NOTHING (no double-arm).
  (rf/reg-resource :gcb/article (article-spec {:gc-after-ms 5000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gcb/article {:slug "w"})]
    (ensure! :gcb/article scope "w" [:route :r 1])
    (succeed! k {:title "W"})        ;; first load succeeds → :loaded, GC armed
    ;; a background refetch that fails (entry keeps data, returns to :loaded)
    (rf/dispatch-sync [:rf.resource/refetch {:resource :gcb/article :scope scope
                                             :params {:slug "w"}}])
    (reset! scheduled-timers [])
    (fail! k :transient-503)         ;; background refresh failure
    (testing "rf2-ar9pcx — a background-refresh failure (status :loaded, data
              kept, :refresh-error) re-arms NO timer (the GC was already armed on
              the prior success — no double-arm from the failure path)"
      (let [e (entry k)]
        (is (= :loaded (:status e)) "background refresh failure kept :loaded")
        (is (= {:title "W"} (:data e)) "prior data kept")
        (is (some? (:refresh-error e)) ":refresh-error recorded"))
      (is (empty? (filter #(= k (:resource/key %)) @scheduled-timers))
          "no schedule-timers re-armed on the background-refresh failure"))))

(deftest gc-fired-skips-when-owner-reattached
  (rf/reg-resource :gck/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gck/article {:slug "w"})]
    (ensure! :gck/article scope "w" [:lease :gck 1])
    (succeed! k {:title "W"})
    (testing "Spec 016 §Stale and GC scheduling — a fired GC timer RE-CHECKS
              owner sets after wake; an entry with a live owner is NOT removed
              (the timer is advisory — it never writes a stale decision)"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (some? (entry k)) "owned entry kept (GC skipped)")
      (is (= :loaded (:status (entry k)))))))

(deftest gc-fired-skips-when-in-flight
  (rf/reg-resource :gcf/article (article-spec {:gc-after-ms 1000}) article-spec-request)
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
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (some? (entry k)) "in-flight entry kept (GC skipped)"))))

;; ---- rf2-07693y: a GC skip RESCHEDULES so a later release/settle is GC'd ---

(deftest gc-skip-while-owned-reschedules-and-collects-after-release
  (rf/reg-resource :gcr/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gcr/article {:slug "w"})]
    (ensure! :gcr/article scope "w" [:lease :gcr 1])
    (succeed! k {:title "W"})
    (reset! scheduled-timers [])
    (testing "rf2-07693y — a GC timer firing while the entry is still OWNED
              skips collection but RE-ARMS a fresh GC re-check (so a later
              release after the original deadline does not strand the entry)"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (some? (entry k)) "owned entry kept (GC skipped)")
      (is (= 1 (count @scheduled-timers)) "exactly one reschedule fx emitted")
      (let [args (first @scheduled-timers)]
        (is (= k (:resource/key args)))
        (is (= 1000 (:gc-delay-ms args)) "rescheduled with the resource's :gc-after-ms")
        (is (nil? (:stale-delay-ms args)) "stale timer NOT re-armed on a GC skip")))
    (testing "the owner releases AFTER the original deadline; the rescheduled
              GC re-check now finds the entry owner-free + idle and collects it"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gcr 1]}])
      (is (empty? (:active-owners (entry k))) "entry now owner-free")
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (nil? (entry k)) "the rescheduled GC re-check collected the now-inactive entry"))))

(deftest gc-skip-while-in-flight-reschedules-and-collects-after-settle
  (rf/reg-resource :gci/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :gci/article {:slug "w"})]
    ;; in flight + owner-free (owner released while loading)
    (ensure! :gci/article scope "w" [:lease :gci 1])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :gci 1]}])
    (is (some? (:current-work (entry k))) "in flight, owner-free")
    (reset! scheduled-timers [])
    (testing "rf2-07693y — a GC timer firing while the entry is IN-FLIGHT skips
              but RE-ARMS a fresh GC re-check"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (some? (entry k)) "in-flight entry kept (GC skipped)")
      (is (= 1 (count @scheduled-timers)) "one reschedule fx emitted")
      (is (= 1000 (:gc-delay-ms (first @scheduled-timers)))))
    (testing "the in-flight work settles (a stale/aborted reply clears
              :current-work via the work-row terminal); once owner-free + idle
              the rescheduled GC re-check collects it"
      ;; settle the work to a non-error idle state via an abort reply (clears
      ;; :current-work without re-attaching an owner) — the entry is now
      ;; owner-free + idle (GC-eligible)
      (let [wid (:current-work (entry k))]
        (rf/dispatch-sync [:rf.resource.internal/failed
                           {:resource/key k :work/id wid
                            :generation (:generation (entry k))
                            :rf.frame/id :rf/default}
                           {:status :cancelled :error {:kind :rf.http/aborted :reason :user}}]))
      (is (nil? (:current-work (entry k))) "work settled — no longer in flight")
      (is (empty? (:active-owners (entry k))) "owner-free")
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (nil? (entry k)) "the rescheduled GC re-check collected the now-idle entry"))))

(deftest gc-skip-no-entry-does-not-reschedule
  (rf/reg-resource :gcn/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [k (state/scoped-resource-key {:user "u"} :gcn/article {:slug "gone"})]
    (reset! scheduled-timers [])
    (testing "rf2-07693y — a GC timer firing for an entry that no longer exists
              (removed / cleared) does NOT reschedule (nothing to collect)"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (nil? (entry k)))
      (is (empty? @scheduled-timers) "no reschedule fx for a vanished entry"))))

(deftest stale-fired-rechecks-durable-fact-no-write
  (rf/reg-resource :sf/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :sf/article {:slug "w"})]
    (ensure! :sf/article scope "w" [:lease :sf 1])
    (succeed! k {:title "W"})
    (let [before (entry k)]
      (testing "Spec 016 §Stale and GC scheduling — the stale-timer re-check
                derives freshness from the DURABLE :stale-at; it writes NO
                durable change (the :stale? sub derives staleness; the timer
                is advisory)"
        (rf/dispatch-sync [:rf.resource.internal/stale-fired {:resource/key k}])
        (is (= before (entry k)) "stale-fired made no durable entry change")))))

(deftest frame-destroy-cancels-resource-timers
  (rf/reg-resource :fd/article (article-spec {:stale-after-ms 60000 :gc-after-ms 300000}) article-spec-request)
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
  (rf/reg-resource :rmt/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (state/scoped-resource-key scope :rmt/article {:slug "w"})]
    (ensure! :rmt/article scope "w" [:lease :rmt 1])
    (succeed! k {:title "W"})
    ;; arm a real long timer so remove has something to cancel. rf2-9e0tyq —
    ;; the timer side-table key's resource-key element is the byte key-id.
    (timers/schedule! :rf/default k timers/gc-kind 1000000)
    (is (contains? @timers/timer-table [:rf/default (state/key-id k) timers/gc-kind]))
    (testing "Spec 016 §Events / §Stale and GC scheduling — :rf.resource/remove
              evicts the instance AND cancels its advisory timers"
      (rf/dispatch-sync [:rf.resource/remove {:resource :rmt/article :scope scope
                                              :params {:slug "w"}}])
      (is (nil? (entry k)) "instance removed")
      (is (not (contains? @timers/timer-table [:rf/default (state/key-id k) timers/gc-kind]))
          "its GC timer cancelled"))))
