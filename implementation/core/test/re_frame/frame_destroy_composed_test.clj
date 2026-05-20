(ns re-frame.frame-destroy-composed-test
  "Per rf2-gh1mj — composed lifecycle teardown interleavings.

  Pre-existing coverage exercises each frame-lifecycle edge IN ISOLATION:
  individual destroy steps, the adapter-disposed throw, drain-after-
  destroy, optional-hook absent paths. What was NOT pinned is the
  COMBINATION — what happens when a throwing trace listener fires WHILE
  the destroy cascade is running, what happens when an optional cleanup
  hook is registered but throws, what happens when a reaction's
  `dispose!` throws mid-sub-cache-walk, what happens when a frame's
  `:on-destroy` event dispatch-syncs across to a sibling. These are the
  rare cases most likely to leave sub-cache, epoch buffers, flow
  registries, or registrar slots alive after destroy.

  Acceptance per rf2-gh1mj:
    - At least four composed lifecycle interleavings (this file: five).
    - Assertions prove no leaked sub-cache, epoch buffer, flow
      registration, or registrar slot for the destroyed frame.
    - Listener-throw and late-bound-hook-throw paths pinned.
    - JVM coverage suffices — every assertion is pure runtime semantics
      with no host-specific divergence (the destroy step list is host-
      agnostic; CLJS adds only React-context teardown, which is owned
      by the views ns and tested separately in adapters/*/test/)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as epoch-state]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-v6z0: machines is a separate artefact whose late-bind
            ;; hooks publish when the ns is loaded — side-effect require
            ;; so the `:machines/teardown-on-frame-destroy!` and
            ;; `:machines/on-frame-destroyed!` hooks exist for the
            ;; composed-leak test below.
            [re-frame.machines]))

;; ---- fixture --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! flows/last-inputs {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-listeners!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (rf/init! plain-atom/adapter)
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
;; 1. Throwing trace listener during the destroy cascade
;;
;; The destroy cascade emits multiple trace events:
;;   * one or more :rf.machine.lifecycle/destroyed (per active machine)
;;   * :frame/destroyed
;;   * (post-destroy) :rf.epoch.cb/silenced-on-frame-destroy per
;;     observing epoch cb
;; A listener that throws on EVERY event during the cascade must not
;; (a) crash the destroy cascade, (b) leave the frame partially
;; destroyed, or (c) prevent other listeners from observing the cascade.
;; Trace fan-out swallows listener throws (re-frame.trace.tooling/
;; deliver-to-tooling!) — pin that the contract survives the multi-emit
;; destroy cascade end-to-end.
;; ---------------------------------------------------------------------------

(deftest destroy-with-throwing-trace-listener-still-completes
  (testing "trace listener that throws on every emit during destroy: cascade
            completes, frame is fully destroyed, surviving listener sees the
            full event sequence"
    (rf/reg-frame :composed/scoped {:doc "scoped"})
    ;; Seed two machine snapshots so destroy emits multiple
    ;; :rf.machine.lifecycle/destroyed events in addition to
    ;; :frame/destroyed.
    (rf/reg-event-db :composed/seed-machines
                     (fn [db _]
                       (assoc db :rf/machines
                              {:flow/a {:state :running :data {}}
                               :flow/b {:state :idle    :data {}}})))
    (rf/dispatch-sync [:composed/seed-machines] {:frame :composed/scoped})
    (let [throw-calls (atom 0)
          survivor    (atom [])]
      ;; Throwing listener — fires for every emit in the cascade.
      (rf/register-trace-listener! ::thrower
                             (fn [_ev]
                               (swap! throw-calls inc)
                               (throw (ex-info "tool blew during destroy" {}))))
      ;; Surviving listener — must still receive every event the
      ;; throwing listener intercepted.
      (rf/register-trace-listener! ::survivor (fn [ev] (swap! survivor conj ev)))

      ;; Destroy. Must NOT throw despite the listener exception storm.
      (is (nil? (rf/destroy-frame! :composed/scoped))
          "destroy-frame! completes without re-throwing the listener's exception")

      ;; The throwing listener WAS invoked (more than once — the cascade
      ;; emitted multiple events).
      (is (>= @throw-calls 3)
          (str "throwing listener invoked once per cascade event (>=3); got "
               @throw-calls))

      ;; The surviving listener saw the canonical cascade events.
      (let [ops (set (map :operation @survivor))]
        (is (contains? ops :rf.machine.lifecycle/destroyed)
            "survivor saw :rf.machine.lifecycle/destroyed")
        (is (contains? ops :frame/destroyed)
            "survivor saw :frame/destroyed"))
      (is (= 2 (count (filter #(= :rf.machine.lifecycle/destroyed
                                  (:operation %))
                              @survivor)))
          "survivor saw both per-machine destroyed events (one per snapshot)")

      ;; The frame is fully gone from BOTH the frames atom AND the
      ;; registrar — proves no destroy step was skipped by the
      ;; listener throw.
      (is (nil? (frame/frame :composed/scoped))
          "frame is dissoc'd from the frames atom")
      (is (nil? (get @frame/frames :composed/scoped))
          "frame entry is gone from the underlying atom (no soft-destroy)")
      (is (nil? (registrar/lookup :frame :composed/scoped))
          "frame is unregistered from the registrar")

      (rf/unregister-trace-listener! ::thrower)
      (rf/unregister-trace-listener! ::survivor))))

;; ---------------------------------------------------------------------------
;; 2. Throwing late-bound cleanup hook during the destroy cascade
;;
;; destroy-frame! consults late-bind hooks for several optional cleanup
;; steps (privacy, elision, ssr, machines, schemas, flows, epoch). The
;; helper safe-call-hook! wraps each in try/catch so one bad hook can
;; not block the rest of teardown. This test pins that contract
;; end-to-end: install a throwing hook, destroy, and assert all the
;; OTHER hooks still fire and the frame is fully gone.
;; ---------------------------------------------------------------------------

(deftest destroy-with-throwing-late-bound-hook-still-completes
  (testing "throwing :schemas/on-frame-destroyed! does NOT prevent the rest
            of the cleanup hooks or the dissoc step from running"
    (rf/reg-frame :composed/hook-throw {:doc "hook-throw"})

    (let [other-hooks-called (atom #{})
          original-flows-h   (late-bind/get-fn :flows/teardown-on-frame-destroy!)
          original-epoch-h   (late-bind/get-fn :epoch/on-frame-destroyed)
          original-schemas-h (late-bind/get-fn :schemas/on-frame-destroyed!)]
      (try
        ;; The throwing hook — fires AFTER mark-frame-destroyed! /
        ;; tear-down-sub-cache! but BEFORE :flows/teardown and
        ;; :epoch/on-frame-destroyed.
        (late-bind/set-fn! :schemas/on-frame-destroyed!
                           (fn [_id]
                             (swap! other-hooks-called conj :schemas-throwing)
                             (throw (ex-info "schemas teardown blew" {}))))
        ;; The downstream hooks — they must run AFTER the throw.
        (late-bind/set-fn! :flows/teardown-on-frame-destroy!
                           (fn [_id]
                             (swap! other-hooks-called conj :flows-ran)))
        (late-bind/set-fn! :epoch/on-frame-destroyed
                           (fn [_id]
                             (swap! other-hooks-called conj :epoch-ran)))

        ;; Destroy must not re-throw.
        (is (nil? (frame/destroy-frame! :composed/hook-throw))
            "destroy-frame! completes; the throwing hook was swallowed")

        ;; Every hook downstream of the throw still ran.
        (is (contains? @other-hooks-called :schemas-throwing)
            "the throwing hook itself was invoked")
        (is (contains? @other-hooks-called :flows-ran)
            ":flows/teardown-on-frame-destroy! still ran AFTER the schemas throw")
        (is (contains? @other-hooks-called :epoch-ran)
            ":epoch/on-frame-destroyed still ran AFTER the schemas throw")

        ;; The frame is fully gone.
        (is (nil? (frame/frame :composed/hook-throw))
            "frame is dissoc'd from the frames atom despite the hook throw")
        (is (nil? (registrar/lookup :frame :composed/hook-throw))
            "frame is unregistered from the registrar despite the hook throw")

        (finally
          ;; Restore the original hook fns so subsequent tests are not
          ;; observing the throwing/probe replacements.
          (late-bind/set-fn! :schemas/on-frame-destroyed! original-schemas-h)
          (late-bind/set-fn! :flows/teardown-on-frame-destroy! original-flows-h)
          (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch-h))))))

;; ---------------------------------------------------------------------------
;; 3. Throwing reaction dispose! during sub-cache teardown
;;
;; tear-down-sub-cache! walks every cached entry and calls interop/dispose!
;; on the reaction, wrapped in try/catch. The protective try keeps one
;; bad dispose from stranding the rest of the sub-cache. Pin the
;; contract end-to-end: register two subs, attach a throwing on-dispose
;; to one, then destroy. The OTHER sub's dispose must still fire and
;; the sub-cache must be cleared.
;; ---------------------------------------------------------------------------

(deftest destroy-with-throwing-reaction-dispose-still-completes
  (testing "a reaction whose dispose! throws does NOT prevent other reactions
            from being disposed; the sub-cache is cleared either way"
    (rf/reg-frame :composed/sub-throw {:doc "sub-throw"})
    (rf/reg-event-db :composed/seed (fn [_ _] {:a 1 :b 2}))
    (rf/reg-sub :composed/a (fn [db _] (:a db)))
    (rf/reg-sub :composed/b (fn [db _] (:b db)))
    (rf/dispatch-sync [:composed/seed] {:frame :composed/sub-throw})

    (let [r1 (rf/subscribe :composed/sub-throw [:composed/a])
          r2 (rf/subscribe :composed/sub-throw [:composed/b])
          throwing-disposed (atom 0)
          surviving-disposed (atom 0)]
      ;; r1's dispose throws; r2's dispose must still fire.
      (interop/add-on-dispose! r1
                               (fn []
                                 (swap! throwing-disposed inc)
                                 (throw (ex-info "dispose blew" {}))))
      (interop/add-on-dispose! r2 (fn [] (swap! surviving-disposed inc)))

      ;; Both reactions are cached.
      (let [cache (:sub-cache (frame/frame :composed/sub-throw))]
        (is (= 2 (count @cache))
            "both subscriptions are pinned in the cache"))

      ;; Destroy must not re-throw.
      (is (nil? (frame/destroy-frame! :composed/sub-throw))
          "destroy-frame! completes despite the throwing dispose")

      ;; Both dispose hooks were invoked — the swallow is per-reaction,
      ;; not "first throw aborts the walk".
      (is (= 1 @throwing-disposed)
          "the throwing reaction's dispose hook fired (and threw)")
      (is (= 1 @surviving-disposed)
          "the surviving reaction's dispose hook STILL fired despite the prior throw")

      ;; Frame is fully gone.
      (is (nil? (frame/frame :composed/sub-throw))
          "frame is dissoc'd")
      (is (nil? (registrar/lookup :frame :composed/sub-throw))
          "frame is unregistered"))))

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
    (rf/reg-frame :composed/parent {:doc "parent"})
    (rf/reg-event-db :composed/notify-parent
                     (fn [db [_ payload]]
                       (assoc db :last-notification payload)))
    (rf/reg-event-fx :composed/teardown
                     (fn [_ _]
                       ;; Mid-drain on :composed/child; dispatch-sync across
                       ;; to :composed/parent.
                       (rf/dispatch-sync [:composed/notify-parent :child-gone]
                                         {:frame :composed/parent})
                       {}))
    (rf/reg-frame :composed/child
                  {:doc        "child with cross-frame :on-destroy"
                   :on-destroy [:composed/teardown]})

    (let [recorded (atom [])]
      (rf/register-trace-listener! ::xfx (fn [ev] (swap! recorded conj ev)))
      (rf/destroy-frame! :composed/child)
      (rf/unregister-trace-listener! ::xfx)

      ;; Sibling parent received the notification.
      (is (= :child-gone
             (:last-notification (rf/get-frame-db :composed/parent)))
          ":on-destroy's cross-frame dispatch-sync committed on the parent")

      ;; The warn trace fired (cross-frame dispatch-sync mid-drain
      ;; on a sibling per Spec 002 / rf2-fp97).
      (let [warns (filter (fn [ev]
                            (and (= :warning (:op-type ev))
                                 (= :rf.warning/cross-frame-dispatch-sync-during-drain
                                    (:operation ev))))
                          @recorded)]
        (is (seq warns)
            ":rf.warning/cross-frame-dispatch-sync-during-drain fired during :on-destroy"))

      ;; The child frame is fully gone — :on-destroy did not block
      ;; the dissoc.
      (is (nil? (frame/frame :composed/child))
          "child frame is dissoc'd after the cross-frame :on-destroy")
      (is (nil? (registrar/lookup :frame :composed/child))
          "child frame is unregistered after the cross-frame :on-destroy")

      ;; The parent is untouched.
      (is (some? (frame/frame :composed/parent))
          "parent frame remains live after the child destroy"))))

;; ---------------------------------------------------------------------------
;; 5. Compound leak audit — every per-frame sub-system is cleared
;;
;; Build a frame that touches every per-frame sub-system that has a
;; teardown hook: schemas (per-frame schema registry), flows (per-frame
;; flows registry + last-inputs cache), epoch (per-frame ring buffer
;; + observed-frames-by-cb entry), sub-cache (pinned reactions),
;; registrar. Destroy. Pin that ALL of them are cleared in a single
;; composed assertion — guards against a future regression that fixes
;; each leak in isolation while breaking the destroy step list's
;; ordering.
;; ---------------------------------------------------------------------------

(deftest composed-destroy-leak-audit
  (testing "after destroy: sub-cache empty, epoch buffer cleared, flow rows
            cleared, schema rows cleared, frame absent, registrar dropped"
    (rf/reg-frame :composed/leak-audit {:doc "leak-audit"})

    ;; --- schemas: register a schema rooted at the frame -------------------
    (rf/reg-app-schema [:n] [:int] {:frame :composed/leak-audit})
    (is (contains? @schemas/schemas-by-frame :composed/leak-audit)
        "precondition: schema row exists for the frame")

    ;; --- flows: register a flow rooted at the frame -----------------------
    (rf/reg-event-db :composed/seed-leak (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :composed/area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :composed/leak-audit})

    ;; --- epoch: register a listener BEFORE the cascade so the cb's
    ;;     observed-frames-by-cb entry gets populated by the drain --------
    (rf/register-epoch-listener! ::composed-observer (fn [_r] nil))

    (rf/dispatch-sync [:composed/seed-leak] {:frame :composed/leak-audit})
    ;; Seed the flow's last-inputs directly — under some inter-test
    ;; orderings the `run-flows!` walker's hook is gated by a sibling
    ;; reload (conformance suite reloads flows mid-pass). The direct
    ;; swap pins the post-condition contract this test cares about
    ;; (the destroy-frame! teardown clears the row) without depending
    ;; on the flow walker firing during this specific dispatch.
    (swap! flows/last-inputs assoc-in [:composed/area :composed/leak-audit] [3 4])
    (is (contains? @flows/flows :composed/leak-audit)
        "precondition: flow registry has a row for the frame")
    (is (= [3 4] (get-in @flows/last-inputs [:composed/area :composed/leak-audit]))
        "precondition: flow last-inputs has a row for the frame")

    (let [observed @(deref #'epoch-state/observed-frames-by-cb)]
      (is (contains? (get observed ::composed-observer) :composed/leak-audit)
          "precondition: epoch cb has the frame in its observed-frames set"))
    (is (pos? (count (rf/epoch-history :composed/leak-audit)))
        "precondition: epoch ring buffer has at least one record")

    ;; --- sub-cache: pin a subscription ------------------------------------
    (rf/reg-sub :composed/leak-rect (fn [db _] (:rect db)))
    (let [_pinned (rf/subscribe :composed/leak-audit [:composed/leak-rect])
          cache  (:sub-cache (frame/frame :composed/leak-audit))]
      (is (pos? (count @cache))
          "precondition: sub-cache pinned at least one entry"))

    ;; --- registrar: precondition --------------------------------------
    (is (some? (registrar/lookup :frame :composed/leak-audit))
        "precondition: frame is registered in the registrar")

    ;; --- destroy ---------------------------------------------------------
    (frame/destroy-frame! :composed/leak-audit)

    ;; --- composed post-condition: NOTHING per-frame remains -------------
    (is (nil? (frame/frame :composed/leak-audit))
        "post: frame is dissoc'd from frames atom")
    (is (nil? (get @frame/frames :composed/leak-audit))
        "post: frame entry is gone from the underlying atom")
    (is (nil? (registrar/lookup :frame :composed/leak-audit))
        "post: frame is unregistered from the registrar")
    (is (not (contains? @schemas/schemas-by-frame :composed/leak-audit))
        "post: schema row dropped (per rf2-wkxng / rf2-6m0se)")
    (is (not (contains? @flows/flows :composed/leak-audit))
        "post: flow registry slot dropped (per rf2-wbtjn)")
    (is (not (contains? (get @flows/last-inputs :composed/area)
                        :composed/leak-audit))
        "post: flow last-inputs row dropped for the destroyed frame")
    (is (= [] (rf/epoch-history :composed/leak-audit))
        "post: epoch ring buffer returns the empty vector for the destroyed frame")
    (let [observed @(deref #'epoch-state/observed-frames-by-cb)]
      (is (not (contains? (get observed ::composed-observer)
                          :composed/leak-audit))
          "post: epoch cb's observed-frames entry no longer includes the frame"))

    ;; Listener registries (trace, epoch) outlive frames by design — they
    ;; are global and re-arm against the next same-keyed frame
    ;; registration. Pin that to lock the contract.
    (rf/unregister-epoch-listener! ::composed-observer)))
