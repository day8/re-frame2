(ns day8.re-frame2-causa.panels.time-travel-cljs-test
  "CLJS-side wiring tests for Causa's Time Travel scrubber panel
  (Phase 3, rf2-t53ze).

  ## Three contracts under test (in addition to the pure-data tests
  in `time_travel_helpers_cljs_test.cljc`)

  1. **Registry wires the subs / events** under the `:rf.causa/*`
     namespace. The composite `:rf.causa/time-travel` sub returns the
     panel's render data; the pin/unpin/select events write into
     :rf/causa's app-db (not the host's).

  2. **Reset-to-pinned uses reset-frame-db!**, NOT restore-epoch (per
     spec/002-Time-Travel.md §Why reset-frame-db! not restore-epoch).
     Asserted via stubbed `:rf.causa.fx/*` effects — the test fixture
     captures the routed effect map and asserts that
     `:rf.causa.fx/reset-frame-db!` fires (and `:rf.causa.fx/restore-
     epoch` does NOT) when `:rf.causa/reset-to-pinned` is dispatched.

  3. **Pins survive ring-buffer eviction** — the panel still renders
     a detached chip when the pin's epoch-id is no longer in the
     cached history, and the chip's Reset button still calls
     reset-frame-db! with the pin's stored :frame-db.

  ## Pure hiccup

  Same approach as `event_detail_cljs_test.cljs` — we walk the view's
  hiccup tree by `data-testid` rather than mounting to a DOM. Keeps
  the suite fast + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.time-travel :as time-travel]
            [day8.re-frame2-causa.panels.time-travel-events :as time-travel-events]
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- effect-capturing test harness --------------------------------------
;;
;; The two write paths route through `:rf.causa.fx/restore-epoch` and
;; `:rf.causa.fx/reset-frame-db!`. The registry registers thin
;; delegations to rf/restore-epoch / rf/reset-frame-db!. In tests we
;; *replace* those reg-fx handlers with capture fns so the assertion
;; is "the right effect map landed" — no need to wire the epoch
;; artefact onto the test classpath.

(defonce ^:private captured-effects (atom []))

(defn- install-capture-fx! []
  (reset! captured-effects [])
  ;; Per re-frame v2's reg-fx contract the handler is (fn [ctx args] ...).
  ;; We capture `args` (the value passed via :fx [[fx-id args]]) so the
  ;; assertion can match against the registry's handler returns.
  (rf/reg-fx :rf.causa.fx/restore-epoch
    (fn [_ctx args] (swap! captured-effects conj [:rf.causa.fx/restore-epoch args])))
  (rf/reg-fx :rf.causa.fx/reset-frame-db!
    (fn [_ctx args] (swap! captured-effects conj [:rf.causa.fx/reset-frame-db! args]))))

(defn- captured [] @captured-effects)

;; ---- fixture history + register helpers ---------------------------------

(defn- mk-record
  "Build a minimal `:rf/epoch-record` map. `dispatch-id` rides on the
  first trace event per rf2-g6ih4."
  [epoch-id db-after dispatch-id]
  {:epoch-id     epoch-id
   :frame        :rf/default
   :committed-at 0
   :event-id     :host/dispatched
   :trigger-event [:host/dispatched]
   :db-before    {}
   :db-after     db-after
   :trace-events (if dispatch-id
                   [{:id 1 :op-type :event :operation :event/dispatched
                     :tags {:dispatch-id dispatch-id}}]
                   [])})

(defn- seed-history!
  "Seed Causa's app-db's :epoch-history slot with `records`. Mirrors
  what `:rf.causa/epoch-recorded` would do on each settle. This
  avoids depending on the epoch artefact being on the test classpath
  — the panel reads from the cached slot, not from rf/epoch-history
  directly."
  [records]
  (registry/register-causa-handlers!)
  (install-capture-fx!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/seed-history-for-test (vec records)])))

;; The registry doesn't ship a :rf.causa/seed-history-for-test event —
;; we register one locally for the suite so seeding goes through the
;; normal app-db-write path.
(defn- register-seed-event! []
  (rf/reg-event-db :rf.causa/seed-history-for-test
    (fn [db [_ records]]
      (assoc db :epoch-history (vec records)))))

;; ---- hiccup walker (mirrors event_detail_cljs_test.cljs) ----------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

;; ---- (1) wiring ----------------------------------------------------------

(deftest registry-installs-time-travel-subs
  (testing "register-causa-handlers! installs the Phase 3 subs"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/time-travel)))
    (is (some? (registrar/handler :sub :rf.causa/epoch-history)))
    (is (some? (registrar/handler :sub :rf.causa/selected-epoch-id)))
    (is (some? (registrar/handler :sub :rf.causa/pinned-snapshots)))
    (is (some? (registrar/handler :sub :rf.causa/target-frame)))))

(deftest registry-installs-time-travel-events
  (testing "register-causa-handlers! installs the Phase 3 events"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :event :rf.causa/select-epoch)))
    (is (some? (registrar/handler :event :rf.causa/clear-selected-epoch)))
    (is (some? (registrar/handler :event :rf.causa/pin-current)))
    (is (some? (registrar/handler :event :rf.causa/unpin)))
    (is (some? (registrar/handler :event :rf.causa/rename-pin)))
    (is (some? (registrar/handler :event :rf.causa/reset-to-epoch)))
    (is (some? (registrar/handler :event :rf.causa/reset-to-pinned)))
    (is (some? (registrar/handler :event :rf.causa/epoch-recorded)))))

(deftest time-travel-sub-defaults
  (testing ":rf.causa/time-travel returns sane defaults on a fresh frame"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/time-travel])]
        (is (= :rf/default (:target-frame data)))
        (is (= [] (:history data)))
        (is (= [] (:pins data)))
        (is (= [] (:chip-states data)))
        (is (nil? (:selected-epoch-id data)))
        (is (false? (:cap-reached? data)))))))

;; ---- (2) select epoch writes to Causa frame, not host -------------------

(deftest select-epoch-writes-to-causa-frame
  (testing ":rf.causa/select-epoch lands on :rf/causa, not :rf/default"
    (registry/register-causa-handlers!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-epoch :e-42]))
    (is (= :e-42 (:selected-epoch-id (frame/frame-app-db-value :rf/causa))))
    (is (nil? (:selected-epoch-id (frame/frame-app-db-value :rf/default))))))

;; ---- (3) pin captures the 4-tuple via the live event --------------------

(deftest pin-current-captures-the-four-tuple-via-event
  (testing "dispatching :rf.causa/pin-current eager-copies :db-after off
            the cached history and stores the 4-tuple"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {:counter 1} 100)
                    (mk-record :e-2 {:counter 2} 200)])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-2 :label "after-tick"}])
      (let [pins @(rf/subscribe [:rf.causa/pinned-snapshots])
            pin  (first pins)]
        (is (= 1 (count pins)))
        (is (= :e-2 (:epoch-id pin)))
        (is (= {:counter 2} (:frame-db pin)) "eager copy of :db-after")
        (is (= 200 (:dispatch-id pin)))
        (is (= "after-tick" (:label pin)))))))

(deftest pin-cap-enforced-at-32
  (testing "the 33rd pin drops the oldest and surfaces the overflow toast"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    ;; Seed 33 epochs and pin every one.
    (let [records (mapv (fn [i] (mk-record (keyword (str "e-" i)) {:n i} i))
                        (range 1 34))]
      (seed-history! records)
      (rf/with-frame :rf/causa
        (doseq [i (range 1 34)]
          (rf/dispatch-sync
            [:rf.causa/pin-current {:eid   (keyword (str "e-" i))
                                    :label (str "pin-" i)}]))
        (let [pins @(rf/subscribe [:rf.causa/pinned-snapshots])
              causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= h/default-pin-cap (count pins))
              "pin store capped at 32")
          (is (= :e-2 (:epoch-id (first pins)))
              "oldest (:e-1) was dropped")
          (is (= :e-33 (:epoch-id (last pins)))
              "newest (:e-33) retained")
          (is (some? (:pin-overflow-toast causa-db))
              "overflow toast slot populated"))))))

;; ---- (4) reset-to-pinned uses reset-frame-db!, NOT restore-epoch --------

(deftest reset-to-pinned-calls-reset-frame-db-not-restore-epoch
  (testing "dispatching :rf.causa/reset-to-pinned fires :rf.causa.fx/
            reset-frame-db! against the pin's stored :frame-db, NOT
            :rf.causa.fx/restore-epoch (per spec §Why reset-frame-db!
            not restore-epoch)"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {:counter 7} 1)])
    (rf/with-frame :rf/causa
      ;; Pin :e-1 with its db-after = {:counter 7}.
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-1 :label "checkpoint"}])
      (reset! captured-effects [])  ;; reset capture after pin
      (rf/dispatch-sync [:rf.causa/reset-to-pinned :e-1]))
    (let [effects (captured)]
      (is (= 1 (count effects))
          "exactly one effect fired")
      (let [[fx-id fx-arg] (first effects)]
        (is (= :rf.causa.fx/reset-frame-db! fx-id)
            "the value-direct path is reset-frame-db!, NOT restore-epoch")
        (is (= :rf/default (:frame-id fx-arg))
            "fires against the target host frame")
        (is (= {:counter 7} (:frame-db fx-arg))
            "uses the pin's eager :frame-db, not the live one")))))

(deftest reset-to-epoch-calls-restore-epoch
  (testing "dispatching :rf.causa/reset-to-epoch fires :rf.causa.fx/
            restore-epoch (the ring-buffer path that surfaces the six
            restore failure modes)"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {:counter 7} 1)])
    (rf/with-frame :rf/causa
      (reset! captured-effects [])
      (rf/dispatch-sync [:rf.causa/reset-to-epoch :e-1]))
    (let [effects (captured)]
      (is (= 1 (count effects)))
      (let [[fx-id fx-arg] (first effects)]
        (is (= :rf.causa.fx/restore-epoch fx-id))
        (is (= :rf/default (:frame-id fx-arg)))
        (is (= :e-1 (:epoch-id fx-arg)))))))

;; ---- (4b) restore-epoch failure surfacing (rf2-o94sp, audit 4b) ---------

(deftest restore-epoch-failure-records-failure-and-clears-selection
  (testing "audit rf2-i0veg §4b — when the production :rf.causa.fx/
            restore-epoch fx body runs with rf/restore-epoch returning
            false (any of its six failure modes), the fx writes the
            failure into the module-scope `restore-epoch-last-result`
            atom and dispatches the bump-tick + clear-selected-epoch
            cleanup events. The `:rf.causa/last-restore-failure` sub
            then surfaces a {:frame-id <kw> :epoch-id <opaque>} map.

            We invoke the production fx body directly (rather than
            routing through :rf.causa/reset-to-epoch's :fx vector)
            because the framework's effect-handler dispatch path
            captures the fx-fn at registration time and a
            `with-redefs` over `rf/restore-epoch` after the fact
            doesn't reach the registered closure's reference. Calling
            the fx body directly via the production fn-pointer in the
            module is the same assertion target — the fx body does
            what it claims when its inner call to rf/restore-epoch
            yields false."
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {:counter 7} 1)])
    (reset! time-travel-events/restore-epoch-last-result nil)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-epoch :e-1])
      (is (= :e-1 @(rf/subscribe [:rf.causa/selected-epoch-id]))
          "selection landed before the restore attempt")
      ;; Invoke the production fx body via the publicly-exposed
      ;; `restore-epoch-fx-fn` symbol the panel ns now exports
      ;; (rf2-o94sp). With-redef the underlying restore-epoch
      ;; implementation to surface the failure path.
      (with-redefs [rf/restore-epoch (fn [_frame-id _epoch-id] false)]
        (time-travel-events/restore-epoch-fx-fn
          {} {:frame-id :rf/default :epoch-id :e-1}))
      ;; Drain the cleanup events the fx scheduled.
      (rf/dispatch-sync [:rf.causa/bump-restore-epoch-tick])
      (rf/dispatch-sync [:rf.causa/clear-selected-epoch])
      ;; The fx wrote the boolean directly to the atom — assert.
      (is (= {:ok? false :frame-id :rf/default :epoch-id :e-1}
             @time-travel-events/restore-epoch-last-result)
          "fx atom records the failure boolean + identifiers")
      (is (nil? @(rf/subscribe [:rf.causa/selected-epoch-id]))
          "selection cleared after restore failure")
      (is (= {:frame-id :rf/default :epoch-id :e-1}
             @(rf/subscribe [:rf.causa/last-restore-failure]))
          "last-restore-failure sub surfaces the failure shape"))))

(deftest restore-epoch-fx-dispatches-cleanup-events-to-causa-frame
  (testing "restore-epoch cleanup events explicitly target :rf/causa"
    (let [dispatched (atom [])]
      (with-redefs [rf/restore-epoch (fn [_frame-id _epoch-id] false)
                    rf/dispatch*    (fn
                                      ([event]
                                       (swap! dispatched conj [event nil]))
                                      ([event opts]
                                       (swap! dispatched conj
                                              [event (select-keys opts [:frame])])))]
        (time-travel-events/restore-epoch-fx-fn
          {} {:frame-id :rf/default :epoch-id :e-1}))
      (is (= [[[:rf.causa/bump-restore-epoch-tick] {:frame :rf/causa}]
              [[:rf.causa/clear-selected-epoch] {:frame :rf/causa}]]
             @dispatched)))))

(deftest restore-epoch-success-does-not-record-failure
  (testing "audit rf2-i0veg §4b corollary — when rf/restore-epoch
            returns true (success), :rf.causa/last-restore-failure
            stays nil; the failure-surfacing path is only walked on
            actual failures"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {:counter 7} 1)])
    (reset! time-travel-events/restore-epoch-last-result nil)
    (with-redefs [rf/restore-epoch (fn [_frame-id _epoch-id] true)]
      (time-travel-events/restore-epoch-fx-fn
        {} {:frame-id :rf/default :epoch-id :e-1}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/bump-restore-epoch-tick])
      (is (= true (:ok? @time-travel-events/restore-epoch-last-result))
          "fx atom records the success boolean")
      (is (nil? @(rf/subscribe [:rf.causa/last-restore-failure]))
          "no failure recorded on successful restore"))))

;; ---- (5) pins survive ring-buffer eviction ------------------------------

(deftest pinned-snapshots-survive-history-eviction
  (testing "after the underlying epoch ages out of cached history, the
            pin still appears in :rf.causa/pinned-snapshots (the pin
            store is independent of history). The chip-state surfaces
            :attached false."
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    ;; Seed history with :e-old then pin it; then re-seed history with
    ;; a newer-only set so :e-old has aged out of the cache. The pin
    ;; survives.
    (seed-history! [(mk-record :e-old {:state :authed} 99)])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-old :label "before-login"}]))
    ;; Re-seed (simulates ring-buffer roll forward — epoch artefact
    ;; would do this via the cb pump).
    (seed-history! [(mk-record :e-new {:state :anon} 200)])
    (rf/with-frame :rf/causa
      (let [data    @(rf/subscribe [:rf.causa/time-travel])
            pins    (:pins data)
            chips   (:chip-states data)
            detached (some (fn [cs] (when-not (:attached cs) cs)) chips)]
        (is (= 1 (count pins)) "pin store unchanged by history roll-forward")
        (is (= :e-old (:epoch-id (first pins))))
        (is (some? detached) "chip-states surfaces a detached chip")
        (is (= "before-login" (:label (:pin detached))))
        ;; And reset-to-pinned still works — the pin's :frame-db is
        ;; the value-direct handle.
        (reset! captured-effects [])
        (rf/dispatch-sync [:rf.causa/reset-to-pinned :e-old])
        (let [[[fx-id fx-arg]] (captured)]
          (is (= :rf.causa.fx/reset-frame-db! fx-id)
              "reset-to-pinned works after eviction — pin held the value")
          (is (= {:state :authed} (:frame-db fx-arg))))))))

;; ---- (6) unpin / rename via events --------------------------------------

(deftest unpin-via-event-removes-from-store
  (testing ":rf.causa/unpin drops the pin"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {} 1) (mk-record :e-2 {} 2)])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-1 :label "a"}])
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-2 :label "b"}])
      (rf/dispatch-sync [:rf.causa/unpin :e-1])
      (let [pins @(rf/subscribe [:rf.causa/pinned-snapshots])]
        (is (= 1 (count pins)))
        (is (= :e-2 (:epoch-id (first pins))))))))

(deftest rename-pin-via-event-rewrites-only-label
  (testing ":rf.causa/rename-pin rewrites only :label"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {:x 1} 1)])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-1 :label "old"}])
      (rf/dispatch-sync [:rf.causa/rename-pin :e-1 "new"])
      (let [pin (first @(rf/subscribe [:rf.causa/pinned-snapshots]))]
        (is (= "new" (:label pin)))
        (is (= :e-1  (:epoch-id pin)))
        (is (= {:x 1} (:frame-db pin)))
        (is (= 1     (:dispatch-id pin)))))))

;; ---- (7) view renders ---------------------------------------------------

(deftest empty-state-renders-when-history-empty
  (testing "with no epoch history, the panel renders the empty state"
    (registry/register-causa-handlers!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [tree (time-travel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-time-travel-empty")))
        (is (nil?  (find-by-testid tree "rf-causa-time-travel-track")))))))

(deftest track-and-actions-render-when-history-populated
  (testing "with history populated, the panel renders the track + actions"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {} 1) (mk-record :e-2 {} 2)])
    (rf/with-frame :rf/causa
      (let [tree (time-travel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-time-travel-track")))
        (is (some? (find-by-testid tree "rf-causa-time-travel-slider")))
        (is (some? (find-by-testid tree "rf-causa-time-travel-actions")))
        (is (some? (find-by-testid tree "rf-causa-pin-current")))
        (is (some? (find-by-testid tree "rf-causa-reset-to-epoch")))
        (is (nil?  (find-by-testid tree "rf-causa-time-travel-empty")))))))

(deftest chip-renders-detached-when-epoch-aged-out
  (testing "the view renders a detached chip when the pin's epoch is
            no longer in the cached history"
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-old {:state :authed} 99)])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-current {:eid :e-old :label "before-login"}]))
    (seed-history! [(mk-record :e-new {} 200)])
    (rf/with-frame :rf/causa
      (let [tree (time-travel/Panel)
            chip (find-by-testid tree "rf-causa-pin-chip-:e-old")]
        (is (some? chip)
            "detached chip is still rendered (per spec §Pins on the scrubber)")))))

;; ---- (8) rf2-1barg — sync-epoch-history pumps the seed slot --------------

(deftest sync-epoch-history-event-replaces-slot
  (testing ":rf.causa/sync-epoch-history wholesale-overwrites the slot.
            Dispatched from mount.cljs/open! on first Ctrl+Shift+C so
            host dispatches that landed before Causa opened still surface
            in the panel — without this event the pre-mount records
            would be stranded in the framework's ring buffer with no
            reactive path into Causa's app-db."
    (registry/register-causa-handlers!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync
        [:rf.causa/sync-epoch-history
         [(mk-record :e-1 {:counter 6} 1)
          (mk-record :e-2 {:counter 7} 2)]])
      (let [history (:history @(rf/subscribe [:rf.causa/time-travel]))]
        (is (= 2 (count history)))
        (is (= :e-1 (:epoch-id (first history))))
        (is (= :e-2 (:epoch-id (second history))))))))

(deftest sync-epoch-history-event-handles-nil-and-empty
  (testing ":rf.causa/sync-epoch-history is defensive against the empty
            and nil seed (epoch artefact absent / fresh-boot windows)"
    (registry/register-causa-handlers!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-epoch-history nil])
      (is (= [] (:history @(rf/subscribe [:rf.causa/time-travel]))))
      (rf/dispatch-sync [:rf.causa/sync-epoch-history []])
      (is (= [] (:history @(rf/subscribe [:rf.causa/time-travel])))))))

;; ---- (9) rf2-1barg — pin-label input lives in app-db (reactive) ----------

(deftest time-travel-label-input-routes-through-app-db
  (testing ":rf.causa/time-travel-set-label-input writes to the slot
            and the sub reads it back. Pre-rf2-1barg this lived in a
            `defonce` plain atom; the view never re-rendered on
            keystrokes because plain atoms aren't a substrate-reactive
            primitive."
    (registry/register-causa-handlers!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (= "" @(rf/subscribe [:rf.causa/time-travel-label-input]))
          "default empty")
      (rf/dispatch-sync [:rf.causa/time-travel-set-label-input "checkpoint"])
      (is (= "checkpoint" @(rf/subscribe [:rf.causa/time-travel-label-input])))
      (rf/dispatch-sync [:rf.causa/time-travel-set-label-input ""])
      (is (= "" @(rf/subscribe [:rf.causa/time-travel-label-input]))))))

(deftest time-travel-label-input-surfaces-in-composite
  (testing "the composite :rf.causa/time-travel sub carries the
            :label-input slot so the actions-row reads it via a single
            reactive read (the row's `<input>` value is bound to the
            composite's :label-input)."
    (registry/register-causa-handlers!)
    (register-seed-event!)
    (install-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (seed-history! [(mk-record :e-1 {} 1)])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/time-travel-set-label-input "label-1"])
      (let [data @(rf/subscribe [:rf.causa/time-travel])]
        (is (= "label-1" (:label-input data))
            "composite surfaces label-input")))))
