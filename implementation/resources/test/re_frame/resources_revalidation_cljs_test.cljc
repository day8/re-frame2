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
   [re-frame.late-bind :as late-bind]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events (incl. the focus/reconnect events this test
   ;; dispatches) + the timer / work-ledger side-table fx + the internal
   ;; replies these tests dispatch through.
   [re-frame.resources]
   [re-frame.resources.revalidate-listeners :as revalidate-listeners]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.resources.work-ledger :as work-ledger]
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
  firing. Composed INSIDE the reset-runtime fixture (one `use-fixtures` call).

  rf2-784223: the shared `make-reset-runtime-fixture` fires
  `:resources/reset-resources!` in its `:post-dispose` phase, which already
  clears the state / work-ledger / timer / revalidate-listener host caches
  (and the resource/mutation registrars) BEFORE this composed fixture runs —
  so no per-suite cache reset is needed here. This fixture only resets its
  own capture atoms and installs the capturing fxs."
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
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

;; ===========================================================================
;; 1. Active-stale scan — refetch active-stale, leave fresh + owner-free alone
;; ===========================================================================

(deftest window-focused-scans-active-stale-only
  ;; Stale-by-policy is `:stale-after-ms 0` (stale the instant it loads); the
  ;; never-stale variant declares no stale policy. Both keep their owner so the
  ;; ACTIVE-OWNER gate is what differs from the inactive case, not liveness.
  (rf/reg-resource :rv/sw   (article-spec {:stale-after-ms 0}) article-spec-request) ;; goes stale at once
  (rf/reg-resource :rv/fresh (article-spec) article-spec-request)                    ;; never stale
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
  (rf/reg-resource :rc/sw (article-spec {:stale-after-ms 0}) article-spec-request)
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
  (rf/reg-resource :ca/sw (article-spec {:stale-after-ms 0}) article-spec-request)
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
  (rf/reg-resource :gn/sw (article-spec {:stale-after-ms 0}) article-spec-request)
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
                             {:resource/key k
                              :work/id (work-ledger/resource-work-id k gen-before)
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
;; 3b. Coalescing — focus + visibility do not double-refetch in-flight stale
;;     entries (rf2-wankrd). A tab-return commonly fires BOTH focus (window)
;;     and visibilitychange (document); both dispatch :rf.resource/window-
;;     focused. The active-stale scan SKIPS entries with a LIVE in-flight
;;     refetch, so back-to-back signals yield AT MOST one new generation /
;;     work item and NO abort churn.
;; ===========================================================================

(deftest back-to-back-focus-coalesces-to-one-refetch
  ;; ADVERSARIAL (rf2-wankrd): the second focus signal arrives while the
  ;; first focus's refetch is still in flight. Without coalescing it would
  ;; force a SECOND new generation, superseding + aborting the first (churn).
  (rf/reg-resource :co/sw (article-spec {:stale-after-ms 0}) article-spec-request)
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :co/sw {:slug "w"})]
    (ensure! :co/sw scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (let [gen-before (:generation (entry k))]
      (reset! aborts [])
      (rf/dispatch-sync [:rf.resource/window-focused]) ;; starts the refetch
      (let [gen-after-1 (:generation (entry k))
            wid-after-1 (:current-work (entry k))]
        (is (= (inc gen-before) gen-after-1) "first focus bumped one generation")
        (is (= :fetching (:status (entry k))) "refetch in flight (:fetching)")
        (testing "Spec 016 §Race / rf2-wankrd — a SECOND focus while the first
                  refetch is still in flight is coalesced: NO new generation,
                  the SAME work item, and NO opportunistic abort (no churn)"
          (rf/dispatch-sync [:rf.resource/window-focused])
          (let [e (entry k)]
            (is (= gen-after-1 (:generation e)) "no second generation bump")
            (is (= wid-after-1 (:current-work e)) "same in-flight work item")
            (is (= :fetching (:status e)) "still the one in-flight refetch")
            (is (empty? @aborts) "no opportunistic abort fired (no churn)")))))))

(deftest focus-plus-visibility-coalesces-to-one-refetch
  ;; ADVERSARIAL (rf2-wankrd): the canonical tab-return — focus (window) AND
  ;; visibilitychange (document) both translate to :rf.resource/window-focused.
  ;; Exactly one active-stale key MUST produce at most one new generation /
  ;; work item and no abort churn.
  (rf/reg-resource :cv/sw (article-spec {:stale-after-ms 0}) article-spec-request)
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :cv/sw {:slug "w"})]
    (ensure! :cv/sw scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (let [gen-before (:generation (entry k))]
      (reset! aborts [])
      ;; both host signals lower to the SAME resource event (the listener
      ;; carries no policy — the handler is the coalescing point)
      (rf/dispatch-sync [:rf.resource/window-focused]) ;; window focus
      (rf/dispatch-sync [:rf.resource/window-focused]) ;; document visibilitychange→visible
      (let [e   (entry k)
            rec (work-ledger/get-record (runtime-db) (:current-work e))]
        (is (= (inc gen-before) (:generation e))
            "focus+visibility together bumped exactly ONE generation")
        (is (= :fetching (:status e)) "one in-flight refetch")
        (is (= :running (:status rec)) "the single work record is still live")
        (is (empty? @aborts) "no abort churn from the second signal")))))

(deftest coalescing-does-not-block-a-fresh-revalidation
  ;; The in-flight gate must NOT permanently wedge revalidation: once the
  ;; in-flight refetch SETTLES (work cleared, entry stale again), a later
  ;; focus signal MUST start a fresh refetch.
  (rf/reg-resource :cf/sw (article-spec {:stale-after-ms 0}) article-spec-request)
  (let [scope {:user "u"}
        k (state/scoped-resource-key scope :cf/sw {:slug "w"})]
    (ensure! :cf/sw scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (rf/dispatch-sync [:rf.resource/window-focused]) ;; refetch in flight
    (let [gen-mid (:generation (entry k))]
      ;; settle the in-flight refetch (clears :current-work; stale-after 0 →
      ;; the entry is immediately stale again)
      (succeed! k {:title "W2"})
      (is (nil? (:current-work (entry k))) "in-flight work cleared on settle")
      (testing "rf2-wankrd — coalescing is per IN-FLIGHT attempt, not a latch:
                a focus AFTER the refetch settled starts a fresh revalidation"
        (rf/dispatch-sync [:rf.resource/window-focused])
        (let [e (entry k)]
          (is (= (inc gen-mid) (:generation e)) "a new generation started")
          (is (= :fetching (:status e)) "fresh refetch in flight"))))))

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

;; ===========================================================================
;; 6. Host event-target wiring (rf2-pxe0c7) — CLJS/DOM-stub only
;;
;;    `visibilitychange` is a `document` event (it never fires on `window`),
;;    and the handler reads `document.visibilityState`. These stub-DOM tests
;;    pin the ACTUAL host wiring: visibilitychange is attached to `document`
;;    (NOT window), a visible→tab-return dispatches :rf.resource/window-
;;    focused, a hidden transition does NOT, and remove / frame-destroy
;;    detaches the listener from `document`. (The node test runtime has no
;;    DOM; we install a capturing stub on `js/globalThis`, mirroring
;;    routing_history_cljs_test.)
;; ===========================================================================

#?(:cljs
   (do
     ;; A capturing window / document stub: each records its
     ;; addEventListener / removeEventListener calls in `state` and can
     ;; `dispatchEvent` to the registered listeners (keyed by target + type).
     (defn- install-dom-stub! []
       (let [state    (atom {:window {} :document {:visibilityState "visible"}})
             mk-target (fn [target-key]
                         #js {:addEventListener
                              (fn [type listener]
                                (swap! state update-in [target-key :listeners type]
                                       (fnil conj []) listener))
                              :removeEventListener
                              (fn [type listener]
                                (swap! state update-in [target-key :listeners type]
                                       (fnil (fn [xs] (vec (remove #(= % listener) xs))) [])))
                              :dispatchEvent
                              (fn [event]
                                (doseq [l (get-in @state [target-key :listeners (.-type event)] [])]
                                  (l event)))})
             window   (mk-target :window)
             document (mk-target :document)]
         ;; the visibility handler reads `(.-visibilityState js/document)` —
         ;; expose a mutable field the test flips between "visible"/"hidden".
         (set! (.-visibilityState document) "visible")
         (set! (.-window js/globalThis) window)
         (set! (.-document js/globalThis) document)
         (swap! state assoc :window-obj window :document-obj document)
         state))

     (defn- uninstall-dom-stub! []
       (js-delete js/globalThis "window")
       (js-delete js/globalThis "document"))

     (def ^:private real-browser?
       (and (exists? js/window)
            (identical? js/window js/globalThis)))

     (def ^:dynamic *dom-state* nil)))

#?(:cljs
   (deftest visibilitychange-attaches-to-document-not-window
     (when-not real-browser?
       (let [state (install-dom-stub!)]
         (binding [*dom-state* state]
           (try
             ;; capture dispatches via the late-bind router hook the listener
             ;; rides (so we observe the EXACT event the host wiring produces)
             (let [dispatched (atom [])
                   prior      (late-bind/get-fn :router/dispatch!)]
               (late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! dispatched conj [ev opts])))
               (try
                 (revalidate-listeners/install-revalidation-listeners! :rv/dom)
                 (testing "rf2-pxe0c7 — visibilitychange is attached to DOCUMENT, not window"
                   (is (seq (get-in @state [:document :listeners "visibilitychange"]))
                       "document carries the visibilitychange listener")
                   (is (empty? (get-in @state [:window :listeners "visibilitychange"]))
                       "window does NOT carry visibilitychange")
                   (is (seq (get-in @state [:window :listeners "focus"]))
                       "focus stays on window")
                   (is (seq (get-in @state [:window :listeners "online"]))
                       "online stays on window"))
                 (testing "rf2-pxe0c7 — a visibilitychange while VISIBLE dispatches window-focused"
                   (reset! dispatched [])
                   (set! (.-visibilityState (:document-obj @state)) "visible")
                   (.dispatchEvent (:document-obj @state) #js {:type "visibilitychange"})
                   (is (= [:rf.resource/window-focused] (ffirst @dispatched))
                       "visible visibilitychange dispatched :rf.resource/window-focused")
                   (is (= {:frame :rv/dom :source :revalidate} (second (first @dispatched)))
                       "dispatched at the frame the listener targets, with :revalidate source"))
                 (testing "rf2-pxe0c7 — a visibilitychange while HIDDEN does NOT dispatch"
                   (reset! dispatched [])
                   (set! (.-visibilityState (:document-obj @state)) "hidden")
                   (.dispatchEvent (:document-obj @state) #js {:type "visibilitychange"})
                   (is (empty? @dispatched) "hidden visibilitychange dispatched nothing"))
                 (testing "rf2-pxe0c7 — remove detaches the visibilitychange listener from document"
                   (revalidate-listeners/remove-revalidation-listeners! :rv/dom)
                   (is (empty? (get-in @state [:document :listeners "visibilitychange"]))
                       "document visibilitychange listener detached on remove")
                   (is (empty? (get-in @state [:window :listeners "focus"]))
                       "window focus listener detached on remove")
                   ;; a post-remove visible visibilitychange dispatches nothing
                   (reset! dispatched [])
                   (set! (.-visibilityState (:document-obj @state)) "visible")
                   (.dispatchEvent (:document-obj @state) #js {:type "visibilitychange"})
                   (is (empty? @dispatched) "detached listener fires nothing"))
                 (finally
                   (if prior
                     (late-bind/set-fn! :router/dispatch! prior)
                     (late-bind/clear-fn! :router/dispatch!)))))
             (finally
               (uninstall-dom-stub!))))))))

#?(:cljs
   (deftest frame-destroy-detaches-document-visibilitychange
     (when-not real-browser?
       (let [state (install-dom-stub!)]
         (binding [*dom-state* state]
           (try
             (let [fa :rv/dom-destroy]
               (rf/reg-frame fa {:doc "DOM-stub frame-destroy detach frame"})
               (revalidate-listeners/install-revalidation-listeners! fa)
               (is (seq (get-in @state [:document :listeners "visibilitychange"]))
                   "document visibilitychange attached for the frame")
               (testing "rf2-pxe0c7 — frame destroy detaches the document
                         visibilitychange listener via the single teardown hook"
                 (frame/destroy-frame! fa)
                 (is (empty? (get-in @state [:document :listeners "visibilitychange"]))
                     "document visibilitychange detached on frame destroy")
                 (is (not (contains? @revalidate-listeners/listener-table fa))
                     "side-table slot dropped on frame destroy")))
             (finally
               (uninstall-dom-stub!))))))))
