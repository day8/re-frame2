(ns re-frame.event-reply-envelope-conformance-cljs-test
  "Conformance for delivering managed-effect replies through the event model.

  `reply-conformance` owns the pure reply vocabulary and laws — including the
  FULL stale-delivery authority / forgery truth table over the unforgeable
  `StaleDeliveryCapability` (rf2-3fc89f.13); family suites own their lowering.
  This integration suite proves the remaining boundary: composing the reply
  machinery with the ordinary `reg-event` dispatch pipeline.

    - A completed `:rf/reply-to` target appends one canonical reply map to the
      event vector, and the ordinary `reg-event` pipeline receives it with normal
      coeffects/effects semantics (the natural-success case).

    - A STALE completion honours the `suppress` DELIVERY GATE — the `:deliver?`
      field of the suppression outcome (the observable projection of that
      unforgeable capability boundary). The event leg dispatches ONLY through
      `:deliver?`: a framework/tool-authorised target (capability +
      `:dispatch-stale? true`) is DELIVERING and its stale envelope reaches
      exactly its handler; an app-authored target (no capability, no opt-in) is
      NON-delivering and its handler never runs — yet the stale reply is still a
      well-formed envelope, so non-delivery is not confused with malformed data.

  Both stale cases route to an EXPLICIT non-default frame carrying its OWN image
  handler PLUS a same-id default-registrar sentinel, so neither a targeting
  fall-through (to the ambient default frame) nor a resolution fall-through (to
  the default registrar) can hide behind an ambient dispatch."
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

;; THE delivery gate, as a test-local helper (DESIGN, rf2-j538f7.4). It consumes
;; the COMPLETE suppression outcome (`{:deliver? :reply}`) as the delivery
;; AUTHORITY: it `complete`s + `dispatch-sync`s the stale event ONLY when
;; `:deliver? true`, and returns whether it dispatched. There is NO code path
;; that dispatches around `:deliver?` — so a regression that flips an authorised
;; outcome to non-delivering (while carrying the IDENTICAL stale reply) delivers
;; NOTHING and turns the positive integration RED. Test-only and explicit: the
;; managed-effect runtime owns real stale lowering; this invents no production
;; API and re-derives no authority truth table (that is `reply`'s).
(defn- deliver-through-gate!
  [{:keys [deliver? reply]} target dispatch-opts]
  (when deliver?
    (rf/dispatch-sync (reply/complete target reply) dispatch-opts))
  (boolean deliver?))

(deftest reply-completion-is-an-ordinary-reg-event-dispatch
  (testing "a completed reply target is an ordinary event with the reply last"
    (is (reply/valid-reply? canonical-reply)
        (str "the fixture reply must be a canonical uniform envelope: "
             (reply/validate-reply canonical-reply)))
    (let [seen-event (atom ::unset)
          seen-cofx  (atom ::unset)
          target     [:article/loaded {:id 42}]]
      (rf/reg-sub :evt-reply/last-title (fn [db _] (:title db)))
      (rf/reg-event :article/loaded
        (fn [coeffects event]
          (reset! seen-event event)
          (reset! seen-cofx coeffects)
          (let [reply (peek event)]
            {:db (assoc (:db coeffects) :title (get-in reply [:value :article :title]))})))

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

(deftest an-authorised-stale-reply-is-delivered-only-through-the-gate
  (testing "a framework/tool-authorised stale reply is delivered as an ordinary
            event — but ONLY because the suppress outcome is :deliver? true —
            reaching exactly its handler on an explicit non-default frame"
    ;; Same-id GLOBAL sentinel on the default registrar. A resolution
    ;; fall-through (default registrar instead of the frame's image generation)
    ;; or a targeting fall-through (the ambient default frame instead of the
    ;; explicit one) runs THIS handler and stamps `:global`.
    (rf/reg-event :article/loaded
      (fn [{:keys [db]} _] {:db (assoc db :delivered-by :global)}))
    (is (some? (registrar/lookup :event :article/loaded))
        "the same-id global sentinel is genuinely armed on the default registrar")
    (let [seen-event (atom ::unset)
          call-count (atom 0)
          ;; The explicit frame's OWN image handler for the same id.
          pool [(event-desc "evt.reply.stale" :article/loaded
                  (fn [{:keys [db]} event]
                    (swap! call-count inc)
                    (reset! seen-event event)
                    {:db (assoc db :delivered-by :image)}))]
          img  (image/image {:id :evt.reply/stale-img :select-ns {:include ["evt.reply.stale"]}})
          _    (lf/make-frame {:id :evt.reply/frame :images [img]} pool)
          ;; A FRAMEWORK/TOOL target: it carries the unforgeable stale-delivery
          ;; capability (`with-stale-authority`) AND opts in via :dispatch-stale?.
          target  (assoc (reply/with-stale-authority [:article/loaded {:id 42}])
                         :dispatch-stale? true)
          carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
          current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
          outcome (reply/suppress target carried current
                    {:rf.reply/work-id (:work/id carried)
                     :work/kind        :resource
                     :rf.frame/id      :evt.reply/frame})]
      (testing "the suppression outcome IS the delivery authority — authorised,
                so it authorises delivery"
        (is (true? (:deliver? outcome))
            "an authorised stale target (capability + :dispatch-stale? true) yields :deliver? true")
        (is (reply/valid-reply? (:reply outcome))
            (str "the suppressed reply must be a canonical stale envelope: "
                 (reply/validate-reply (:reply outcome)))))
      (testing "delivery happens ONLY through the gate-driven helper (the sole
                dispatch site, driven by :deliver?)"
        (is (true? (deliver-through-gate! outcome target {:frame :evt.reply/frame}))
            "the helper dispatched because the outcome authorises delivery"))
      (testing "the stale envelope reached EXACTLY the intended handler, once,
                with ORDINARY event-model shape — reply appended last"
        (is (= 1 @call-count) "the target handler ran exactly once")
        (is (= [:article/loaded {:id 42} (:reply outcome)] @seen-event)
            "the handler saw the full completed event, the canonical stale reply last")
        (let [delivered (peek @seen-event)]
          (is (= :stale (:status delivered))    "the delivered envelope is :status :stale")
          (is (= :suppressed (:rf.reply/work-status delivered)) ":rf.reply/work-status :suppressed")
          (is (true? (:stale? delivered))       "the :stale? marker rides")
          (is (some? (:rf.reply/stale-reason delivered)) "a :rf.reply/stale-reason rides")
          (is (not (contains? delivered :value))
              "a stale reply carries NO :value — it mutates no app state")
          (is (contains? reply/statuses (:status delivered))
              ":stale is in the ONE closed status vocabulary")))
      (testing "the event was routed to the EXPLICIT frame via ITS OWN image —
                not the ambient default frame, nor the default registrar"
        (is (= :image (:delivered-by (rf/app-db-value :evt.reply/frame)))
            "the explicit frame's OWN image handler ran (resolved through its generation)")
        (is (not= :global (:delivered-by (rf/app-db-value :evt.reply/frame)))
            "resolution did NOT fall through to the same-id default-registrar sentinel")
        (is (nil? (:delivered-by (rf/app-db-value :rf/default)))
            "targeting did NOT fall through to the ambient default frame")
        (is (nil? registrar/*generation*)
            "the image generation binding unwound after the dispatch"))
      (testing "FAIL-BEFORE ORACLE (teeth): forcing the SAME authorised reply to
                :deliver? false through the SAME helper dispatches NOTHING — the
                positive path cannot be satisfied by dispatching around the gate,
                so a regression that flips the authorised decision to
                non-delivering turns this test RED"
        (is (false? (deliver-through-gate! (assoc outcome :deliver? false)
                                           target {:frame :evt.reply/frame}))
            "a forced non-delivering outcome returns false (no dispatch)")
        (is (= 1 @call-count)
            "the handler count did NOT advance — the ONLY dispatch site consults :deliver?")))))

(deftest an-app-authored-stale-reply-is-non-delivering
  (testing "a normal app reply target (no capability, no :dispatch-stale? opt-in)
            with a superseded correlation is NON-delivering — the app handler
            never runs on ANY frame, yet the stale reply is well-formed data"
    ;; Same-id global sentinel again: a leaked delivery WOULD be observable.
    (rf/reg-event :article/loaded
      (fn [{:keys [db]} _] {:db (assoc db :delivered-by :global)}))
    (is (some? (registrar/lookup :event :article/loaded))
        "the same-id global sentinel is armed (a delivery WOULD be observable)")
    (let [seen-event (atom ::unset)
          call-count (atom 0)
          pool [(event-desc "evt.reply.app" :article/loaded
                  (fn [{:keys [db]} event]
                    (swap! call-count inc)
                    (reset! seen-event event)
                    {:db (assoc db :delivered-by :image)}))]
          img  (image/image {:id :evt.reply/app-img :select-ns {:include ["evt.reply.app"]}})
          _    (lf/make-frame {:id :evt.reply/app-frame :images [img]} pool)
          ;; A NORMAL app target: the bare public short form built from
          ;; `:rf/reply-to` data. It cannot obtain the stale-delivery capability
          ;; and does not opt in, so `suppress` is NON-delivering (no throw — the
          ;; loud-forgery path is `reply`'s truth table, not this integration).
          target  [:article/loaded {:id 42}]
          carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
          current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
          outcome (reply/suppress target carried current
                    {:rf.reply/work-id (:work/id carried)
                     :work/kind        :resource
                     :rf.frame/id      :evt.reply/app-frame})]
      (testing "the suppression outcome is NON-delivering for an app target"
        (is (false? (:deliver? outcome))
            "an app target (no capability, no :dispatch-stale?) yields :deliver? false"))
      (testing "the gate-driven helper dispatches NOTHING"
        (is (false? (deliver-through-gate! outcome target {:frame :evt.reply/app-frame}))
            "the helper returned false — it did not dispatch"))
      (testing "no handler ran and NO app-db mutated — on EITHER the explicit
                frame or the ambient default frame"
        (is (= ::unset @seen-event) "the app handler was never invoked")
        (is (zero? @call-count)     "the target handler ran zero times")
        (is (nil? (:delivered-by (rf/app-db-value :evt.reply/app-frame)))
            "the explicit frame's app-db is unmutated (no stale write leaked in)")
        (is (nil? (:delivered-by (rf/app-db-value :rf/default)))
            "the ambient default frame's app-db is unmutated"))
      (testing "non-delivery is NOT malformed data (AC #4) — the suppressed reply
                is still a valid, well-formed stale envelope"
        (let [reply (:reply outcome)]
          (is (reply/valid-reply? reply)
              (str "the non-delivered reply is still a canonical stale envelope: "
                   (reply/validate-reply reply)))
          (is (= :stale (:status reply))                    ":status :stale")
          (is (= :suppressed (:rf.reply/work-status reply)) ":rf.reply/work-status :suppressed")
          (is (true? (:stale? reply))                       "the :stale? marker rides")
          (is (some? (:rf.reply/stale-reason reply))        "a :rf.reply/stale-reason rides")
          (is (not (contains? reply :value))
              "a stale reply carries NO :value — non-delivery, not a partial write")
          (is (contains? reply/statuses (:status reply))
              ":stale is in the ONE closed status vocabulary"))))))
