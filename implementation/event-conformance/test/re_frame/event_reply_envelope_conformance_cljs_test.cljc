(ns re-frame.event-reply-envelope-conformance-cljs-test
  "EP-0011 reply-envelope DISPATCH event-model conformance (rf2-lve3qj).

  ## Why this tier

  EP-0011 / Managed-Effects property 9: a managed async completion is an
  ordinary CAUSAL EVENT — one `:rf/reply-to` target completed with one
  canonical reply map, appended as the FINAL event argument, then
  dispatched through the normal event pipeline. `implementation/event-
  conformance` is the umbrella regression-lock on the PUBLIC event model
  (the one-form `reg-event` contract, EP-0017 recordable coeffects, EP-0023
  dispatch isolation), but it carried no EP-0011-shaped reply event case.

  The pure reply substrate (`re-frame.reply`) is pinned in
  `reply-conformance/` (the cross-family vocabulary + functor-law tiers),
  and each family's reply slice is pinned in its `*-reply-lowering-*` suite.
  But NONE of those prove, in this public event-model umbrella surface, that
  a completed `:rf/reply-to` event is HANDLED by the single public
  `reg-event` path with the reply map visible as the final argument and
  ordinary coeffects/effects behaviour. A future event-model regression
  could keep the pure reply substrate green while drifting that integration
  contract — and this surface would not catch it.

  ## What this tier proves

  A canonical reply map (`:status` + `:value`/`:error`, `:work/id`,
  `:work/kind`, `:rf.reply/work-status`, `:rf.frame/id`, `:completed-at`) is built,
  a `:rf/reply-to` target is COMPLETED with `re-frame.reply/complete`, and
  the resulting event is dispatched through the PUBLIC `rf/dispatch-sync` /
  `reg-event` pipeline. The handler — an ordinary `reg-event` — sees the
  full event vector with the reply map in FINAL position, reads the
  canonical reply facts off it, and commits ordinary effects (`{:db …}`).
  No retired `reg-event-db` / `reg-event-fx` / `reg-event-ctx`, no callback,
  no ad-hoc `:work-id` vocabulary is introduced — the reply event is just an
  event.

  `.cljc`, dual-runtime: the shadow-cljs `:node-test` build
  (`npm run test:cljs`, ns matches `cljs-test$`) AND the JVM
  `clojure -M:test` runner both pick it up. Harness mirrors the sibling
  model-conformance suite — the shared `make-reset-runtime-fixture` wraps
  every body in `(with-frame :rf/default …)` so ambient `dispatch-sync`
  resolves.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply envelope
  + §Reply delivery is an ordinary causal event (property 9) + EP-0018 §The
  one-form event model."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.reply :as reply]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; A CANONICAL reply map — the uniform envelope a managed-async family lowers
;; its completion onto (Managed-Effects §The reply map). It validates against
;; the shared `re-frame.reply/validate-reply` (asserted below), so this is the
;; SAME shape a real HTTP / resource completion carries — not a synthetic stub.
;; ---------------------------------------------------------------------------

(def ^:private completed-at-ms 1781078400456)

(def ^:private canonical-reply
  {:status       :ok
   :value        {:article {:id 42 :title "Welcome"}}
   :work/id      [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1]
   :work/kind    :resource
   :rf.reply/work-status  :completed
   :rf.frame/id  :rf/default
   :completed-at completed-at-ms})

;; ---------------------------------------------------------------------------
;; (1) A completed :rf/reply-to event is an ORDINARY reg-event dispatch — the
;;     reply map rides as the FINAL argument and the single public reg-event
;;     handler sees the full event vector + commits ordinary effects.
;; ---------------------------------------------------------------------------

(deftest reply-completion-is-an-ordinary-reg-event-dispatch
  (testing "EP-0011 property 9: a managed async completion is a CAUSAL EVENT —
            re-frame.reply/complete appends the canonical reply map as the final
            arg of the :rf/reply-to target, and that event is handled by the
            ONE public reg-event path with normal coeffects-in / effects-out"
    (is (reply/valid-reply? canonical-reply)
        (str "the fixture reply must be a canonical uniform envelope: "
             (reply/validate-reply canonical-reply)))
    (let [seen-event (atom ::unset)
          seen-cofx  (atom ::unset)
          ;; The :rf/reply-to target — the public short form (an event-vector
          ;; prefix). A family hands this to the substrate; it is data.
          target     [:article/loaded {:id 42}]]
      (rf/reg-sub :evt-reply/last-title (fn [db _] (:title db)))
      (rf/reg-event :article/loaded
        (fn [coeffects event]
          ;; The handler is an ordinary reg-event — coeffects IN, effects OUT.
          (reset! seen-event event)
          (reset! seen-cofx coeffects)
          (let [reply (peek event)]
            ;; Commit an ordinary {:db …} effect derived from the reply value.
            {:db (assoc (:db coeffects) :title (get-in reply [:value :article :title]))})))

      ;; Build the completed event via the SHARED substrate helper, then
      ;; dispatch it through the PUBLIC pipeline — exactly the path a family's
      ;; completion reducer drives.
      (let [completed (reply/complete target canonical-reply)]
        (is (= [:article/loaded {:id 42} canonical-reply] completed)
            "complete appends the reply map as the FINAL event argument")
        (rf/dispatch-sync completed))

      (testing "the reg-event handler saw the FULL event vector with the reply in final position"
        (is (= [:article/loaded {:id 42} canonical-reply] @seen-event)
            "the handler's event arg is the completed vector, reply map last")
        (let [delivered (peek @seen-event)]
          (testing "the canonical reply facts are preserved on the delivered event arg"
            (is (= :ok (:status delivered))             ":status preserved")
            (is (= {:article {:id 42 :title "Welcome"}} (:value delivered)) ":value preserved")
            (is (nil? (:error delivered))               "no :error on an :ok reply")
            (is (= [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1]
                   (:work/id delivered))                ":work/id tuple preserved")
            (is (= :rf.work/resource (first (:work/id delivered)))
                ":work/id head is the family head")
            (is (= :resource (:work/kind delivered))    ":work/kind preserved")
            (is (= :completed (:rf.reply/work-status delivered)) ":rf.reply/work-status preserved")
            (is (= :rf/default (:rf.frame/id delivered)) ":rf.frame/id preserved")
            (is (= completed-at-ms (:completed-at delivered)) ":completed-at (EP-0017) preserved")
            (is (contains? reply/statuses (:status delivered))
                ":status is in the ONE closed status vocabulary")
            (is (contains? reply/work-statuses (:rf.reply/work-status delivered))
                ":rf.reply/work-status is in the ONE closed work-status vocabulary"))))

      (testing "the reply event got ORDINARY event-model semantics — a coeffects
                map IN (the reg-event-fx shape), not a bare db, and the {:db …}
                effect committed"
        (is (map? @seen-cofx)         "the handler received the coeffects MAP")
        (is (contains? @seen-cofx :db) "`:db` delivered IN the coeffects map")
        (is (= :rf/default (:rf.frame/id @seen-cofx))
            "the ambient frame id is delivered as :rf.frame/id")
        (is (= "Welcome" @(rf/subscribe [:evt-reply/last-title]))
            "the {:db …} effect derived from the reply value committed")))))

;; ---------------------------------------------------------------------------
;; (2) A STALE reply (the suppression boundary) is ALSO an ordinary reg-event
;;     dispatch when the framework/tool authority opts into delivery — the
;;     canonical :status :stale envelope rides as the final arg, MUTATES NO app
;;     value, and is read off the event like any other reply. (The app-target
;;     non-delivery default is the families'/runtime's job; this tier proves the
;;     EVENT-MODEL shape of a delivered stale envelope, the same single path.)
;; ---------------------------------------------------------------------------

(deftest a-stale-reply-envelope-is-also-an-ordinary-event
  (testing "EP-0011 §Stale suppression — the canonical :status :stale envelope is
            an ordinary causal event when delivered (framework/tool authority):
            it rides as the final arg, carries NO :value, and the one reg-event
            path reads it off the event"
    (let [target  (reply/with-stale-authority [:article/loaded {:id 42}])
          ;; The substrate's suppress boundary produces the canonical stale
          ;; reply; a framework/tool target opts into delivery so the event is
          ;; actually dispatchable (an app target's stale reply is NOT delivered
          ;; — that non-delivery is enforced elsewhere; here we exercise the
          ;; delivered-event SHAPE).
          carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
          current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
          {:keys [reply]} (reply/suppress
                            (assoc target :dispatch-stale? true)
                            carried current
                            {:work/id     (:work/id carried)
                             :work/kind   :resource
                             :rf.frame/id :rf/default})
          seen-event (atom ::unset)]
      (is (reply/valid-reply? reply)
          (str "the suppressed reply must be a canonical stale envelope: "
               (reply/validate-reply reply)))
      (rf/reg-event :article/loaded
        (fn [coeffects event]
          (reset! seen-event event)
          ;; A stale reply MUST mutate no app state — the handler is a no-op db
          ;; write (an ordinary effects map).
          {:db (:db coeffects)}))
      (let [completed (reply/complete (assoc target :dispatch-stale? true) reply)]
        (rf/dispatch-sync completed))
      (let [delivered (peek @seen-event)]
        (is (= :stale (:status delivered))    "the delivered envelope is :status :stale")
        (is (= :suppressed (:rf.reply/work-status delivered)) ":rf.reply/work-status :suppressed")
        (is (true? (:stale? delivered))       "the :stale? marker rides")
        (is (some? (:rf.reply/stale-reason delivered)) "a :rf.reply/stale-reason rides")
        (is (not (contains? delivered :value))
            "a stale reply carries NO :value — it mutates no app state")
        (is (contains? reply/statuses (:status delivered))
            ":stale is in the ONE closed status vocabulary")))))
