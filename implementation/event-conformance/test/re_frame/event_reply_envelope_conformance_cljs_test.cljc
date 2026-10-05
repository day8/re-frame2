(ns re-frame.event-reply-envelope-conformance-cljs-test
  "Conformance for delivering managed-effect replies through the event model.

  `reply-conformance` owns the pure reply vocabulary and laws, core owns
  `complete`, and family suites own their lowering. This integration suite
  proves the remaining boundary: composing the reply machinery with the
  ordinary `reg-event` dispatch pipeline.

    - A STALE completion is UNIVERSALLY non-delivering: the
      `suppress` outcome always carries `:deliver? false`, so an app reply target
      NEVER receives a stale envelope through the event pipeline. A framework/tool
      OBSERVER that wants to see a stale reply reads `(:reply outcome)` and
      dispatches it on its OWN authority — an explicit `complete` + dispatch,
      structurally separate from any target field, with nothing capability-bearing
      riding the target. Both teeth hold: authorised observation reaches exactly
      its handler, AND the suppress boundary asserts app non-delivery — yet the
      stale reply is a well-formed envelope, so non-delivery is not confused with
      malformed data.

  The stale case routes to an EXPLICIT non-default frame carrying its OWN image
  handler PLUS a same-id default-registrar sentinel, so neither a targeting
  fall-through (to the ambient default frame) nor a resolution fall-through (to
  the default registrar) can hide behind an ambient dispatch. Its non-delivery
  tooth is the PAIR of call-count reads that straddle the observer's dispatch —
  zero before, exactly one after — so a runtime that app-delivered the stale
  reply itself would be caught. A row that only built the `suppress` outcome
  and then asserted nothing had happened would invoke no delivery code, so its
  zero-call and unchanged-app-db assertions would follow from the test rather
  than from the runtime. The pure `suppress` laws
  — universal `:deliver? false`, the stale envelope's shape and identity
  facts, the absent `:value` — are `re-frame.reply-cljs-test`'s and the
  security suite's; this suite does not restate them."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.events :as rf.events]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Image resolution stores the registration descriptor plus its selection keys
;; (mirrors `event_frame_isolation_conformance`): a frame's OWN image handler,
;; distinct from a same-id default-registrar sentinel. Routing the stale event
;; to an image-loaded, non-default frame lets the sentinel expose BOTH a wrong
;; frame target (the write lands in the ambient default frame) AND a wrong
;; handler resolution (the write is `:global`, not `:image`).
(defn- event-desc
  [provenance-ns id handler-fn]
  (merge (rf.events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

;; THE observer's own trusted path, as a test-local helper. `suppress` is
;; universally non-delivering (`:deliver? false`), so an app reply target
;; never receives a stale envelope. A framework/tool
;; OBSERVER that WANTS to see one reads the stale `:reply` off the outcome and
;; dispatches it on its OWN authority — this helper is that explicit
;; self-dispatch (`complete` + `dispatch-sync`). Nothing capability-bearing rides
;; the target; the observation path is structurally separate from the
;; (non-)delivery decision. Test-only: the managed-effect runtime owns real stale
;; lowering (and never app-delivers a stale reply); this invents no production API.
(defn- observe-stale-reply!
  [{stale-reply :reply} reply-target dispatch-options]
  (rf/dispatch-sync (rf.reply/complete reply-target stale-reply) dispatch-options))

(deftest an-observer-self-dispatches-a-stale-reply-on-its-own-authority
  (testing "a framework/tool OBSERVER can dispatch a stale reply as an ordinary
            event on its OWN authority — reaching exactly its handler on an
            explicit non-default frame — while the suppress outcome itself is
            (universally) non-delivering"
    ;; Same-id GLOBAL sentinel on the default registrar. A resolution
    ;; fall-through (default registrar instead of the frame's image generation)
    ;; or a targeting fall-through (the ambient default frame instead of the
    ;; explicit one) runs THIS handler and stamps `:global`.
    (rf/reg-event :article/loaded
      (fn [{:keys [db]} _] {:db (assoc db :delivered-by :global)}))
    (let [seen-event         (atom ::unset)
          handler-call-count (atom 0)
          ;; The explicit frame's OWN image handler for the same id.
          image-registrations
          [(event-desc "evt.reply.stale" :article/loaded
             (fn [{:keys [db]} event]
               (swap! handler-call-count inc)
               (reset! seen-event event)
               {:db (assoc db :delivered-by :image)}))]
          event-image
          (rf.image/image {:id :evt.reply/stale-img
                        :select-ns {:include ["evt.reply.stale"]}})
          _ (rf.live-frame/make-frame {:id :evt.reply/frame :images [event-image]}
                           image-registrations)
          ;; A PLAIN app-shaped target — nothing capability-bearing rides it.
          reply-target [:article/loaded {:id 42}]
          ;; The carried/current stale-GATE maps are data-only LEDGER
          ;; correlation, so they keep the bare `:work/id` spelling. Reading
          ;; that bare fact and placing it under `:rf.reply/work-id` in `extra`
          ;; IS the record -> envelope hop, spelled out.
          carried-correlation
          {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4]
           :generation 4}
          current-correlation
          {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5]
           :generation 5}
          suppression-outcome
          (rf.reply/suppress reply-target carried-correlation current-correlation
            {:rf.reply/work-id   (:work/id carried-correlation)
             :rf.reply/work-kind :resource
             :rf.frame/id        :evt.reply/frame})]
      (testing "TOOTH — app non-delivery: the suppress outcome is universally
                non-delivering, so the ONLY way the stale reply reaches a handler
                is a deliberate observer self-dispatch"
        (is (zero? @handler-call-count) "nothing has been dispatched yet"))
      (testing "TOOTH — authorised observation: the observer self-dispatches the
                stale reply on its OWN authority (explicit complete + dispatch)"
        (observe-stale-reply! suppression-outcome reply-target
                              {:frame :evt.reply/frame}))
      (testing "the stale envelope reached EXACTLY the intended handler, once,
                with ORDINARY event-model shape — reply appended last"
        (is (= 1 @handler-call-count) "the target handler ran exactly once")
        (is (= [:article/loaded {:id 42} (:reply suppression-outcome)] @seen-event)
            "the handler saw the full completed event, the canonical stale reply last")
        (let [delivered-reply (peek @seen-event)]
          (testing "TOOTH — the delivered stale envelope grows NO top-level bare
                    ledger alias. The carried gate map keeps its bare
                    `:work/id`; the envelope does not inherit it."
            (is (not (contains? delivered-reply :work/id))
                "no top-level bare :work/id alias on the stale reply envelope")
            (is (not (contains? delivered-reply :work/kind))
                "no top-level bare :work/kind alias on the stale reply envelope"))))
      (testing "the observer's dispatch was routed to the EXPLICIT frame via ITS
                OWN image — not the ambient default frame, nor the default registrar"
        (is (= :image (:delivered-by (rf/app-db-value :evt.reply/frame)))
            "the explicit frame's OWN image handler ran (resolved through its generation)")
        (is (nil? (:delivered-by (rf/app-db-value :rf/default)))
            "targeting did NOT fall through to the ambient default frame")))))
