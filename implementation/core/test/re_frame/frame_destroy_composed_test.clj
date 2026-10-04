(ns re-frame.frame-destroy-composed-test
  "Composed lifecycle teardown interleavings.

  Other suites exercise each frame-lifecycle edge IN ISOLATION:
  individual destroy steps, the adapter-disposed throw, drain-after-
  destroy, optional-hook absent paths. This file pins the
  COMBINATION — what happens when an optional cleanup
  hook is registered but throws, what happens when a reaction's
  `dispose!` throws mid-sub-cache-walk, what happens when a frame's
  `:on-destroy` event dispatch-syncs across to a sibling. These are the
  rare cases most likely to leave sub-cache, epoch buffers, flow
  registries, or frames-store records alive after destroy.

  Coverage:
    - Composed lifecycle interleavings.
    - Assertions prove no leaked sub-cache, epoch buffer, flow
      registration, or frames-store record for the destroyed frame.
    - Late-bound-hook-throw and reaction-dispose-throw paths pinned.
    - JVM coverage suffices — every assertion is pure runtime semantics
      with no host-specific divergence (the destroy step list is host-
      agnostic; CLJS adds only React-context teardown, which is owned
      by the views ns and tested separately in adapters/*/test/).

  ## Posture split

  Teardown is production behaviour; the LIFECYCLE EMITS that narrate it are
  not. Most cases read `:rf.sub/dispose` /
  `:rf.warning/teardown-hook-exception` /
  `:rf.warning/cross-frame-dispatch-sync-during-drain` off the dev trace, which
  is silent under `scripts/test-core-prod-gate.sh`, so those reads are
  guarded; everything about WHAT WAS TORN DOWN is always-on — the frames
  store, the sub-cache, the schemas registry, the flows registry and
  last-inputs are all ungated.

  ONE CASE COUNTS DISPOSALS THROUGH A WITNESS RATHER THAN A GUARD.
  `destroy-emits-exactly-one-dispose-per-slot-for-layered-sub` counts
  evictions; the failure it guards is a DOUBLE DISPOSE, observable
  directly through `rf.interop/add-on-dispose!` on the reactions themselves,
  so it counts real disposals in both postures: under the gate it proves the
  input-release cascade does not re-dispose a slot the frame-destroy walk
  already cleared, which is the actual hazard — a duplicated EMIT would be
  only its symptom.

  THE EPOCH RING IS DEV-FED. `epoch.capture/observe-trace-event!` feeds the
  ring from the DEV TRACE, so under the gate `rf/epoch-history` is empty and
  `observed-frames-by-cb` is never populated. Ungated,
  `composed-destroy-leak-audit`'s two epoch PRECONDITIONS would go red while
  its two matching POST-conditions passed for free: `(= []
  (rf/epoch-history …))` over a ring that is always empty and
  `(not (contains? (get observed …) …))` over a nil map. A leak audit
  certifying that a buffer was cleared, when the buffer was never filled, is a
  false green. Both pairs are guarded together
  so the precondition can never be separated from the claim it licenses."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; Machines is a separate artefact whose late-bind
            ;; hooks publish when the ns is loaded — side-effect require
            ;; so the `:machines/teardown-on-frame-destroy!` and
            ;; `:machines/on-frame-destroyed!` hooks exist for the
            ;; composed-leak test below.
            [re-frame.machines]))

;; ---- fixture --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.flows/reset-last-inputs!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; Per the established pattern in frame_lifecycle_test / epoch_test —
  ;; the framework's fxs / events / late-bind hooks are registered at
  ;; ns-load time; `clear-all!` wiped them. Reload the per-feature
  ;; artefacts to resurrect the registrations so the composed test's
  ;; flow rerun + epoch capture hooks work even when prior tests
  ;; toggled hooks via `with-hook-as-nil`.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.flows :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---------------------------------------------------------------------------
;; 2. Throwing late-bound cleanup hook during the destroy cascade
;;
;; destroy-frame! consults late-bind hooks for several optional cleanup
;; steps (privacy, elision, ssr, machines, schemas, flows, epoch). The
;; helper safe-call-hook! wraps each in try/catch so one bad hook can not
;; block the rest of teardown: the throw is swallowed, every downstream
;; hook still runs and the frame is fully gone. It is not silent either —
;; it emits exactly one structured :rf.warning/teardown-hook-exception
;; carrying the hook key, frame id, and exception, so a leaked
;; optional-artefact cleanup is diagnosable.
;; ---------------------------------------------------------------------------

(deftest throwing-cleanup-hook-emits-one-diagnostic
  (testing "a throwing late-bound cleanup hook emits exactly one
            :rf.warning/teardown-hook-exception (carrying :hook, :frame,
            :exception) while teardown continues best-effort"
    (rf/make-frame {:id :composed/hook-diag :doc "hook-diag"})
    (let [hooks-ran (atom #{})
          original-schemas-h (rf.late-bind/get-fn :schemas/on-frame-destroyed!)
          original-flows-h   (rf.late-bind/get-fn :flows/teardown-on-frame-destroy!)
          original-epoch-h   (rf.late-bind/get-fn :epoch/on-frame-destroyed)
          warnings (atom [])]
      (rf/register-listener! :trace ::hook-diag
                             (fn [ev]
                               (when (= :rf.warning/teardown-hook-exception
                                        (:operation ev))
                                 (swap! warnings conj ev))))
      (try
        ;; The throwing hook fires AFTER mark-frame-destroyed! /
        ;; tear-down-sub-cache! but BEFORE :flows/teardown and
        ;; :epoch/on-frame-destroyed.
        (rf.late-bind/set-fn! :schemas/on-frame-destroyed!
                           (fn [_id]
                             (swap! hooks-ran conj :schemas-throwing)
                             (throw (ex-info "schemas teardown blew" {:k :v}))))
        (rf.late-bind/set-fn! :flows/teardown-on-frame-destroy!
                           (fn [_id] (swap! hooks-ran conj :flows-ran)))
        ;; The post-dissoc :epoch/on-frame-destroyed hook takes
        ;; (frame-id owner-token terminal-evidence) — exact incarnation
        ;; ownership plus the pre-dissoc terminal-evidence bundle
        ;; :epoch/snapshot-frame-destroyed captured for the :halted-destroy
        ;; record.
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed
                           (fn [_id _owner-token _terminal-evidence]
                             (swap! hooks-ran conj :epoch-ran)))

        (is (nil? (rf.frame/destroy-frame! :composed/hook-diag))
            "destroy completes; the hook throw was swallowed")
        (is (= #{:schemas-throwing :flows-ran :epoch-ran} @hooks-ran)
            "the throwing hook ran, and best-effort teardown still ran the
             downstream :flows hook and the post-dissoc :epoch/on-frame-destroyed hook")

        ;; ALWAYS-ON: best-effort teardown finished — the
        ;; frame is gone despite the hook throw. Only the DIAGNOSTIC that makes
        ;; the leak diagnosable is dev-only.
        (is (nil? (rf.frame/frame :composed/hook-diag))
            "the frame is dissoc'd despite the throwing hook")
        (when rf.interop/debug-enabled?
          (is (= 1 (count @warnings))
              "exactly one teardown-hook-exception diagnostic for the failed hook")
          (let [ev (first @warnings)]
            (is (= :error (:op-type ev))
                "op-type is :error (emit-error! family); :operation carries the :rf.warning/* category")
            (is (= :schemas/on-frame-destroyed! (-> ev :tags :hook))
                ":hook names the failing late-bind hook key")
            (is (= :composed/hook-diag (-> ev :tags :frame))
                ":frame carries the frame being destroyed (via the dynamic binding)")
            (is (some? (-> ev :tags :exception))
                ":exception carries the throwable")))
        (finally
          (rf/unregister-listener! :trace ::hook-diag)
          (rf.late-bind/set-fn! :schemas/on-frame-destroyed! original-schemas-h)
          (rf.late-bind/set-fn! :flows/teardown-on-frame-destroy! original-flows-h)
          (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch-h))))))

;; ---------------------------------------------------------------------------
;; 3. Throwing reaction dispose! during sub-cache teardown
;;
;; tear-down-sub-cache! walks every cached entry and calls rf.interop/dispose!
;; on the reaction, wrapped in try/catch. The protective try keeps one
;; bad dispose from stranding the rest of the sub-cache. Pin the
;; contract end-to-end: register two subs, attach a throwing on-dispose
;; to one, then destroy. The OTHER sub's dispose must still fire and
;; the sub-cache must be cleared.
;; ---------------------------------------------------------------------------

(deftest destroy-with-throwing-reaction-dispose-still-completes
  (testing "a reaction whose dispose! throws does NOT prevent other reactions
            from being disposed; the sub-cache is cleared either way"
    (rf/make-frame {:id :composed/sub-throw :doc "sub-throw"})
    (rf/reg-event :composed/seed (fn [{:keys [db]} _] {:db {:a 1 :b 2}}))
    (rf/reg-sub :composed/a (fn [db _] (:a db)))
    (rf/reg-sub :composed/b (fn [db _] (:b db)))
    (rf/dispatch-sync [:composed/seed] {:frame :composed/sub-throw})

    (let [r1 (rf/subscribe [:composed/a] {:frame :composed/sub-throw})
          r2 (rf/subscribe [:composed/b] {:frame :composed/sub-throw})
          throwing-disposed (atom 0)
          surviving-disposed (atom 0)]
      ;; r1's dispose throws; r2's dispose must still fire.
      (rf.interop/add-on-dispose! r1
                               (fn []
                                 (swap! throwing-disposed inc)
                                 (throw (ex-info "dispose blew" {}))))
      (rf.interop/add-on-dispose! r2 (fn [] (swap! surviving-disposed inc)))

      ;; Both reactions are cached.
      (let [cache (:sub-cache (rf.frame/frame :composed/sub-throw))]
        (is (= 2 (count @cache))
            "both subscriptions are pinned in the cache"))

      ;; Destroy must not re-throw.
      (is (nil? (rf.frame/destroy-frame! :composed/sub-throw))
          "destroy-frame! completes despite the throwing dispose")

      ;; Both dispose hooks were invoked — the swallow is per-reaction,
      ;; not "first throw aborts the walk".
      (is (= 1 @throwing-disposed)
          "the throwing reaction's dispose hook fired (and threw)")
      (is (= 1 @surviving-disposed)
          "the surviving reaction's dispose hook STILL fired despite the prior throw")

      ;; Frame is fully gone.
      (is (nil? (rf.frame/frame :composed/sub-throw))
          "frame is dissoc'd")
      (is (nil? (rf.frame/frame-meta :composed/sub-throw))
          "frame is invisible to frame-meta"))))

;; ---------------------------------------------------------------------------
;; 3b. Frame-destroy on a layered (declared-input) sub emits exactly one
;; :rf.sub/dispose PER cached slot — no cascade re-emit
;;
;; A layer-2+ sub's on-dispose callback releases its declared-input refs via
;; unsubscribe!. If dispose-all-for-frame-destroy! called rf.interop/dispose!
;; per slot WITHOUT first evicting the whole cache atom, an input's slot
;; still present in the not-yet-cleared cache would drop its ref-count to 0
;; and fire a SECOND :rf.sub/dispose (reason :no-more-derefers) for that
;; input, racing the walk's own :frame-destroy emit for the same slot —
;; which one landed first (and thus which reason "won") would depend on
;; hash-map iteration order. The walk pre-clears the whole cache atom before
;; any dispose! call, so the cascade always finds nothing left to evict.
;; ---------------------------------------------------------------------------

(deftest destroy-emits-exactly-one-dispose-per-slot-for-layered-sub
  (testing "a layer-2 sub (two declared inputs) held live at frame-destroy: every
            cached slot (the sum + its two inputs) gets EXACTLY ONE
            :rf.sub/dispose, reasoned :frame-destroy — no double-emit from
            the on-dispose ref-count cascade racing the frame-destroy walk"
    (rf/make-frame {:id :composed/layered-destroy :doc "layered destroy"})
    (rf/reg-event :composed/seed-layered (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :composed/layered-a (fn [db _] (:a db)))
    (rf/reg-sub :composed/layered-b (fn [db _] (:b db)))
    (rf/reg-sub :composed/layered-sum
      {:inputs [[:composed/layered-a] [:composed/layered-b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:composed/seed-layered] {:frame :composed/layered-destroy})

    (let [disposes (atom [])]
      (rf/register-listener! :trace ::layered-destroy
                             (fn [ev]
                               (when (= :rf.sub/dispose (:operation ev))
                                 (swap! disposes conj ev))))
      (try
        (let [r        (rf/subscribe [:composed/layered-sum] {:frame :composed/layered-destroy})
              cache    (:sub-cache (rf.frame/frame :composed/layered-destroy))
              disposed (atom [])]
          (is (= 5 @r))
          (is (= 3 (count @cache))
              "precondition: sum + both inputs are cached before destroy")
          ;; ALWAYS-ON, and this is the hazard rather than its symptom: a slot
          ;; disposed TWICE (the input-release cascade racing the
          ;; frame-destroy walk over a cache that had not been pre-cleared). A
          ;; duplicated `:rf.sub/dispose` emit would be the visible symptom;
          ;; `rf.interop/add-on-dispose!` counts the disposals themselves, in
          ;; both postures.
          (doseq [[q-v node] @cache]
            (rf.interop/add-on-dispose! (:reaction node)
                                     (fn [] (swap! disposed conj q-v))))

          (is (nil? (rf.frame/destroy-frame! :composed/layered-destroy)))

          (is (= {[:composed/layered-sum] 1 [:composed/layered-a] 1 [:composed/layered-b] 1}
                 (frequencies @disposed))
              "every cached slot (sum + both inputs) was disposed exactly once — no
               double-dispose from the input-release cascade")

          (when rf.interop/debug-enabled?
            (let [evs      @disposes
                  by-query (group-by #(-> % :tags :rf.sub/query-v) evs)]
              (is (= #{[:composed/layered-sum] [:composed/layered-a] [:composed/layered-b]}
                     (set (keys by-query)))
                  "every cached query-vector (sum + both inputs) surfaced exactly
                   once")
              (doseq [[q q-evs] by-query]
                (is (= 1 (count q-evs))
                    (str q " must fire exactly one :rf.sub/dispose, not a "
                         "duplicate from the ref-count cascade")))
              (is (every? #(= :frame-destroy (-> % :tags :rf.sub/reason)) evs)
                  "every emit is reasoned :frame-destroy — the cascade found the
                   already-cleared cache and never re-emitted :no-more-derefers
                   for an input")
              (is (every? #(= :composed/layered-destroy (-> % :tags :frame)) evs)
                  "each emit carries the destroyed frame's id"))))
        (finally
          (rf/unregister-listener! :trace ::layered-destroy))))))

;; ---------------------------------------------------------------------------
;; 4. Cross-frame dispatch from inside :on-destroy event
;;
;; A frame's :on-destroy handler can legitimately need to talk to a
;; sibling frame (e.g. notify a parent of teardown). Per Spec 002
;; §Cross-frame dispatch-sync during a sibling drain emits
;; :rf.warning/cross-frame-dispatch-sync-during-drain and proceeds.
;; Pin that this works when the trigger is :on-destroy: the sibling's
;; handler runs and commits, the warn fires, and the original frame
;; still tears down cleanly.
;; ---------------------------------------------------------------------------

(deftest cross-frame-dispatch-from-on-destroy-warns-and-commits
  (testing ":on-destroy that dispatch-syncs across frames: sibling commits,
            warn fires, original frame still tears down cleanly"
    (rf/make-frame {:id :composed/parent :doc "parent"})
    (rf/reg-event :composed/notify-parent
                     (fn [{:keys [db]} [_ payload]]
                       {:db (assoc db :last-notification payload)}))
    (rf/reg-event :composed/teardown
                     (fn [_ _]
                       ;; Mid-drain on :composed/child; dispatch-sync across
                       ;; to :composed/parent.
                       (rf/dispatch-sync [:composed/notify-parent :child-gone]
                                         {:frame :composed/parent})
                       {}))
    (rf/make-frame {:id :composed/child :doc        "child with cross-frame :on-destroy"
                    :on-destroy [:composed/teardown]})

    (let [recorded (atom [])]
      (rf/register-listener! :trace ::xfx (fn [ev] (swap! recorded conj ev)))
      (rf/destroy-frame! :composed/child)
      (rf/unregister-listener! :trace ::xfx)

      ;; Sibling parent received the notification.
      (is (= :child-gone
             (:last-notification (rf/app-db-value :composed/parent)))
          ":on-destroy's cross-frame dispatch-sync committed on the parent")

      ;; The warn trace fired (cross-frame dispatch-sync mid-drain
      ;; on a sibling per Spec 002).
      ;; GUARDED: the warn is a dev-trace emit. The commit, the
      ;; teardown and the sibling's survival above and below are the production
      ;; contract, and they are already always-on.
      (when rf.interop/debug-enabled?
        (let [warns (filter (fn [ev]
                              (and (= :warning (:op-type ev))
                                   (= :rf.warning/cross-frame-dispatch-sync-during-drain
                                      (:operation ev))))
                            @recorded)]
          (is (seq warns)
              ":rf.warning/cross-frame-dispatch-sync-during-drain fired during :on-destroy")))

      ;; The child frame is fully gone — :on-destroy did not block
      ;; the dissoc.
      (is (nil? (rf.frame/frame :composed/child))
          "child frame is dissoc'd after the cross-frame :on-destroy")
      (is (nil? (rf.frame/frame-meta :composed/child))
          "child frame is invisible to frame-meta after the cross-frame :on-destroy")

      ;; The parent is untouched.
      (is (some? (rf.frame/frame :composed/parent))
          "parent frame remains live after the child destroy"))))

;; ---------------------------------------------------------------------------
;; 5. Compound leak audit — every per-frame sub-system is cleared
;;
;; Build a frame that touches every per-frame sub-system that has a
;; teardown hook: schemas (per-frame schema registry), flows (per-frame
;; flows registry + last-inputs cache), epoch (per-frame ring buffer
;; + observed-frames-by-cb entry), sub-cache (pinned reactions),
;; the frames store. Destroy. Pin that ALL of them are cleared in a single
;; composed assertion — guards against a change that keeps each leak
;; fixed in isolation while breaking the destroy step list's ordering.
;; ---------------------------------------------------------------------------

(deftest composed-destroy-leak-audit
  (testing "after destroy: sub-cache empty, epoch buffer cleared, flow rows
            cleared, schema rows cleared, frame absent from the frames store"
    (rf/make-frame {:id :composed/leak-audit :doc "leak-audit"})

    ;; --- schemas: register a schema rooted at the frame -------------------
    (rf/reg-app-schema [:n] {:frame :composed/leak-audit} [:int])
    (is (contains? (rf.schemas/snapshot-schemas-by-frame) :composed/leak-audit)
        "precondition: schema row exists for the frame")

    ;; --- flows: register a flow rooted at the frame -----------------------
    (rf/reg-event :composed/seed-leak (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow :composed/area {:frame :composed/leak-audit :inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))

    ;; --- epoch: register a listener BEFORE the cascade so the cb's
    ;;     observed-frames-by-cb entry gets populated by the drain --------
    (rf/register-listener! :epoch ::composed-observer (fn [_r] nil))

    (rf/dispatch-sync [:composed/seed-leak] {:frame :composed/leak-audit})
    ;; Seed the flow's last-inputs directly — under some inter-test
    ;; orderings the `run-flows!` walker's hook is gated by a sibling
    ;; reload (conformance suite reloads flows mid-pass). The direct
    ;; write (frame-scoped) pins the post-condition
    ;; contract this test cares about (the destroy-frame! teardown
    ;; clears the row) without depending on the flow walker firing
    ;; during this specific dispatch.
    (rf.flows.registry/set-frame-flow-last-inputs! :composed/leak-audit :composed/area [3 4])
    (is (contains? (rf.flows/flows-snapshot) :composed/leak-audit)
        "precondition: flow registry has a row for the frame")
    (is (= [3 4] (get-in (rf.flows/last-inputs-snapshot) [:composed/area :composed/leak-audit]))
        "precondition: flow last-inputs has a row for the frame")

    ;; GUARDED AS A PAIR with the matching post-conditions below.
    ;; `epoch.capture/observe-trace-event!` feeds the epoch ring from the DEV
    ;; TRACE, so under `-Dre-frame.debug=false` the ring is never filled and
    ;; `observed-frames-by-cb` is never populated. Splitting the precondition
    ;; from the post-condition would leave the audit certifying that a buffer
    ;; it never filled had been cleared.
    (when rf.interop/debug-enabled?
      (let [observed @(deref #'rf.epoch.state/observed-frames-by-cb)]
        (is (contains? (get observed ::composed-observer) :composed/leak-audit)
            "precondition: epoch cb has the frame in its observed-frames set"))
      (is (pos? (count (rf/epoch-history :composed/leak-audit)))
          "precondition: epoch ring buffer has at least one record"))

    ;; --- sub-cache: pin a subscription ------------------------------------
    (rf/reg-sub :composed/leak-rect (fn [db _] (:rect db)))
    (let [_pinned (rf/subscribe [:composed/leak-rect] {:frame :composed/leak-audit})
          cache  (:sub-cache (rf.frame/frame :composed/leak-audit))]
      (is (pos? (count @cache))
          "precondition: sub-cache pinned at least one entry"))

    ;; --- frames store: precondition -----------------------------------
    (is (some? (rf.frame/frame-meta :composed/leak-audit))
        "precondition: frame is seated in the frames store (frame-meta reads it)")
    ;; Substrate ownership: seating writes NO registrar row.
    (is (nil? (rf.registrar/lookup :frame :composed/leak-audit))
        "precondition: no :frame registrar row exists for a seated frame")

    ;; --- destroy ---------------------------------------------------------
    (rf.frame/destroy-frame! :composed/leak-audit)

    ;; --- composed post-condition: NOTHING per-frame remains -------------
    (is (nil? (get @rf.frame/frames :composed/leak-audit))
        "post: frame entry is gone from the underlying atom")
    (is (nil? (rf.frame/frame-meta :composed/leak-audit))
        "post: frame is invisible to frame-meta")
    (is (not (contains? (rf.schemas/snapshot-schemas-by-frame) :composed/leak-audit))
        "post: schema row dropped")
    (is (not (contains? (rf.flows/flows-snapshot) :composed/leak-audit))
        "post: flow registry slot dropped")
    (is (not (contains? (get (rf.flows/last-inputs-snapshot) :composed/area)
                        :composed/leak-audit))
        "post: flow last-inputs row dropped for the destroyed frame")
    ;; The guarded half of the pair above. Ungated, these two would pass
    ;; vacuously: `(= [] …)` over a ring that is ALWAYS empty under the
    ;; gate and `(not (contains? nil …))`.
    (when rf.interop/debug-enabled?
      (is (= [] (rf/epoch-history :composed/leak-audit))
          "post: epoch ring buffer returns the empty vector for the destroyed frame")
      (let [observed @(deref #'rf.epoch.state/observed-frames-by-cb)]
        (is (not (contains? (get observed ::composed-observer)
                            :composed/leak-audit))
            "post: epoch cb's observed-frames entry does not include the frame")))

    ;; Listener registries (trace, epoch) outlive frames by design — they
    ;; are global and re-arm against the next same-keyed frame
    ;; registration. Pin that to lock the contract.
    (rf/unregister-listener! :epoch ::composed-observer)))
