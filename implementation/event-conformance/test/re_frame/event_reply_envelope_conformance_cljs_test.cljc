(ns re-frame.event-reply-envelope-conformance-cljs-test
  "Conformance for delivering managed-effect replies through the event model.

  `reply-conformance` owns the pure reply vocabulary and laws; family suites own
  their lowering. This integration suite proves the remaining boundary:
  composing the reply machinery with the ordinary `reg-event` dispatch pipeline.

    - A completed `:rf/reply-to` target appends one canonical reply map to the
      event vector, and the ordinary `reg-event` pipeline receives it with normal
      coeffects/effects semantics (the natural-success case).

    - A STALE completion is UNIVERSALLY non-delivering (rf2-j538f7.14): the
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
  reply itself would be caught. A second row that only built the `suppress`
  outcome and then asserted nothing had happened was removed (rf2-6r9j.97): it
  invoked no delivery code, so its zero-call and unchanged-app-db assertions
  followed from the test rather than from the runtime. The pure `suppress` laws
  — universal `:deliver? false`, the stale envelope's shape, the absent
  `:value` — are `re-frame.reply-cljs-test`'s, asserted there across every
  target shape; this suite does not restate them."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.events :as events]
            [re-frame.image :as image]
            [re-frame.live-frame :as lf]
            [re-frame.registrar :as registrar]
            [re-frame.reply :as reply]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private completed-at-ms 1781078400456)

(def ^:private canonical-reply
  {:status       :ok
   :value        {:article {:id 42 :title "Welcome"}}
   :work/id      [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1]
   :work/kind    :resource
   :rf.reply/work-status  :completed
   :rf.frame/id  :rf/default
   :completed-at completed-at-ms})

;; Image resolution stores the registration descriptor plus its selection keys
;; (mirrors `event_frame_isolation_conformance`): a frame's OWN image handler,
;; distinct from a same-id default-registrar sentinel. Routing the stale event
;; to an image-loaded, non-default frame lets the sentinel expose BOTH a wrong
;; frame target (the write lands in the ambient default frame) AND a wrong
;; handler resolution (the write is `:global`, not `:image`).
(defn- event-desc
  [provenance-ns id handler-fn]
  (merge (events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

;; THE observer's own trusted path, as a test-local helper (DESIGN,
;; rf2-j538f7.14). `suppress` is universally non-delivering (`:deliver? false`),
;; so an app reply target never receives a stale envelope. A framework/tool
;; OBSERVER that WANTS to see one reads the stale `:reply` off the outcome and
;; dispatches it on its OWN authority — this helper is that explicit
;; self-dispatch (`complete` + `dispatch-sync`). Nothing capability-bearing rides
;; the target; the observation path is structurally separate from the
;; (non-)delivery decision. Test-only: the managed-effect runtime owns real stale
;; lowering (and never app-delivers a stale reply); this invents no production API.
(defn- observe-stale-reply!
  [{stale-reply :reply} reply-target dispatch-options]
  (rf/dispatch-sync (reply/complete reply-target stale-reply) dispatch-options))

(deftest reply-completion-is-an-ordinary-reg-event-dispatch
  (testing "a completed reply target is an ordinary event with the reply last"
    (is (reply/valid-reply? canonical-reply)
        (str "the fixture reply must be a canonical uniform envelope: "
             (reply/validate-reply canonical-reply)))
    (let [seen-event      (atom ::unset)
          seen-coeffects  (atom ::unset)
          reply-target    [:article/loaded {:id 42}]]
      (rf/reg-sub :evt-reply/last-title (fn [db _] (:title db)))
      (rf/reg-event :article/loaded
        (fn [coeffects event]
          (reset! seen-event event)
          (reset! seen-coeffects coeffects)
          (let [delivered-reply (peek event)]
            {:db (assoc (:db coeffects)
                        :title
                        (get-in delivered-reply [:value :article :title]))})))

      (let [completed-event (reply/complete reply-target canonical-reply)]
        (is (= [:article/loaded {:id 42} canonical-reply] completed-event)
            "complete appends the reply map as the FINAL event argument")
        (rf/dispatch-sync completed-event))

      (testing "the reg-event handler saw the FULL event vector with the reply in final position"
        (is (= [:article/loaded {:id 42} canonical-reply] @seen-event)
            "the handler's event arg is the completed vector, reply map last")
        (let [delivered-reply (peek @seen-event)]
          (testing "the canonical reply facts are preserved on the delivered event arg"
            (is (= :ok (:status delivered-reply)) ":status preserved")
            (is (= {:article {:id 42 :title "Welcome"}} (:value delivered-reply))
                ":value preserved")
            (is (nil? (:error delivered-reply)) "no :error on an :ok reply")
            (is (= [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1]
                   (:work/id delivered-reply))
                ":work/id tuple preserved")
            (is (= :rf.work/resource (first (:work/id delivered-reply)))
                ":work/id head is the family head")
            (is (= :resource (:work/kind delivered-reply)) ":work/kind preserved")
            (is (= :completed (:rf.reply/work-status delivered-reply))
                ":rf.reply/work-status preserved")
            (is (= :rf/default (:rf.frame/id delivered-reply)) ":rf.frame/id preserved")
            (is (= completed-at-ms (:completed-at delivered-reply))
                ":completed-at (EP-0017) preserved")
            (is (contains? reply/statuses (:status delivered-reply))
                ":status is in the ONE closed status vocabulary")
            (is (contains? reply/work-statuses (:rf.reply/work-status delivered-reply))
                ":rf.reply/work-status is in the ONE closed work-status vocabulary"))))

      (testing "the reply event got ORDINARY event-model semantics — a coeffects
                map IN (the reg-event-fx shape), not a bare db, and the {:db …}
                effect committed"
        (is (map? @seen-coeffects) "the handler received the coeffects MAP")
        (is (contains? @seen-coeffects :db) "`:db` delivered IN the coeffects map")
        (is (= :rf/default (:rf.frame/id @seen-coeffects))
            "the ambient frame id is delivered as :rf.frame/id")
        (is (= "Welcome" @(rf/subscribe [:evt-reply/last-title]))
            "the {:db …} effect derived from the reply value committed")))))

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
    (is (some? (registrar/lookup :event :article/loaded))
        "the same-id global sentinel is genuinely armed on the default registrar")
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
          (image/image {:id :evt.reply/stale-img
                        :select-ns {:include ["evt.reply.stale"]}})
          _ (lf/make-frame {:id :evt.reply/frame :images [event-image]}
                           image-registrations)
          ;; A PLAIN app-shaped target — nothing capability-bearing rides it.
          reply-target [:article/loaded {:id 42}]
          carried-correlation
          {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4]
           :generation 4}
          current-correlation
          {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5]
           :generation 5}
          suppression-outcome
          (reply/suppress reply-target carried-correlation current-correlation
            {:rf.reply/work-id (:work/id carried-correlation)
                     :work/kind        :resource
                     :rf.frame/id      :evt.reply/frame})]
      (testing "TOOTH — app non-delivery: the suppress outcome is universally
                non-delivering, so the ONLY way the stale reply reaches a handler
                is a deliberate observer self-dispatch"
        (is (false? (:deliver? suppression-outcome))
            "the suppress boundary never authorises app delivery of a stale reply")
        (is (reply/valid-reply? (:reply suppression-outcome))
            (str "the suppressed reply must be a canonical stale envelope: "
                 (reply/validate-reply (:reply suppression-outcome))))
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
          (is (= :stale (:status delivered-reply))
              "the delivered envelope is :status :stale")
          (is (= :suppressed (:rf.reply/work-status delivered-reply))
              ":rf.reply/work-status :suppressed")
          (is (true? (:stale? delivered-reply)) "the :stale? marker rides")
          (is (some? (:rf.reply/stale-reason delivered-reply))
              "a :rf.reply/stale-reason rides")
          (is (not (contains? delivered-reply :value))
              "a stale reply carries NO :value — it mutates no app state")
          (is (contains? reply/statuses (:status delivered-reply))
              ":stale is in the ONE closed status vocabulary")))
      (testing "the observer's dispatch was routed to the EXPLICIT frame via ITS
                OWN image — not the ambient default frame, nor the default registrar"
        (is (= :image (:delivered-by (rf/app-db-value :evt.reply/frame)))
            "the explicit frame's OWN image handler ran (resolved through its generation)")
        (is (not= :global (:delivered-by (rf/app-db-value :evt.reply/frame)))
            "resolution did NOT fall through to the same-id default-registrar sentinel")
        (is (nil? (:delivered-by (rf/app-db-value :rf/default)))
            "targeting did NOT fall through to the ambient default frame")
        (is (nil? registrar/*generation*)
            "the image generation binding unwound after the dispatch")))))
