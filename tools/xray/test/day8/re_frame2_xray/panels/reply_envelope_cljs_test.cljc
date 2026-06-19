(ns day8.re-frame2-xray.panels.reply-envelope-cljs-test
  "Pure-data tests for Xray's ONE work/reply vocabulary reading the uniform
  reply envelope (EP-0011, rf2-zqefg3.7).

  Dual-target naming (`.cljc` + `_cljs_test`):
    - Cognitect's test-runner (CLJ) picks it up via the default `.*-test$`
      regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$` regex.

  ## What's under test

    1. **closed vocabularies** — reply-statuses / work-statuses / work-kinds
       mirror the substrate's `re-frame.reply` sets EXACTLY (the
       bundle-isolation mirror is pinned, not drifting).
    2. **reply-map readers** — work-id / work-kind reading + inference from
       the work-id tuple head, across families; the `:work/kind :mutation`
       distinction; tolerant of canonical vs bare trace-tag spellings.
    3. **reply-row** — projects a uniform reply map (any family) into the ONE
       render-safe row; PRIVACY summarization; delivered? derivation; the
       five statuses.
    4. **status presentation** — the cross-surface colour class + label is
       UNIFORM across families (an HTTP :stale and a resource :stale read the
       same badge — Cross-Cutting F.11).
    5. **phase-of** — every family's trace op lowers onto ONE reply-envelope
       phase (issued / retry / cancel-requested / completed / stale-suppressed
       / delivered), incl. the suffix heuristic for a not-yet-enumerated op.
    6. **work-event-row / project-work-events** — cross-family trace rows →
       one uniform vocabulary keyed on :work/id.
    7. **stale-races** — keyed on :work/id; the cross-surface stale tally;
       races-by-work-id attempt arcs.
    8. **live-work** — work-ledger rows joined to reply status + trace cause,
       uniform across families; \"what is still running?\""
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.panels.reply-envelope :as re]))

;; ---------------------------------------------------------------------------
;; (1) closed vocabularies — the bundle-isolation mirror is pinned.
;; ---------------------------------------------------------------------------

(deftest closed-vocabularies
  (testing "the mirrored reply-status set is exactly the EP-0011 closed five"
    (is (= #{:ok :partial :error :cancelled :stale} re/reply-statuses))
    (is (re/reply-status? :ok))
    (is (re/reply-status? :stale))
    (is (not (re/reply-status? :running)))
    (is (not (re/reply-status? :completed))))
  (testing "work-statuses are the operational five (timeout is a work status,
            not a reply status; suppressed is the stale terminal)"
    (is (= #{:completed :failed :timed-out :suppressed :cancelled} re/work-statuses)))
  (testing "work-kinds cover every managed-async family"
    (is (= #{:http :resource :mutation :route :machine :timer} re/work-kinds)))
  (testing "all five reply statuses are terminal (a reply map is produced only
            at completion — :running is a ledger status, not a reply status)"
    (is (= re/reply-statuses re/terminal-reply-statuses))))

;; ---------------------------------------------------------------------------
;; (2) reply-map readers — work-id / kind reading + inference, across families.
;; ---------------------------------------------------------------------------

(deftest work-id+kind-readers
  (testing "reads the canonical :work/id (EP-0011 — the bare :work-id trace-tag
            spelling was retired; every family emits the qualified :work/id)"
    (is (= [:rf.work/http :a 1] (re/work-id-of {:work/id [:rf.work/http :a 1]})))
    (is (nil? (re/work-id-of {:work-id [:rf.work/resource :k 4]}))
        "the retired bare :work-id is no longer tolerated"))
  (testing "reads explicit :work/kind, tolerating bare spellings"
    (is (= :http (re/work-kind-of {:work/kind :http})))
    (is (= :resource (re/work-kind-of {:work-kind :resource})))
    (is (= :mutation (re/work-kind-of {:kind :mutation}))))
  (testing "infers the family from each work-id tuple head"
    (is (= :http     (re/infer-work-kind [:rf.work/http :a 1])))
    (is (= :resource (re/infer-work-kind [:rf.work/resource :k 1])))
    (is (= :route    (re/infer-work-kind [:rf.work/route :r "nav-7" :loader])))
    (is (= :machine  (re/infer-work-kind [:rf.work/machine :actor [:path] 1])))
    (is (= :timer    (re/infer-work-kind [:rf.work/timer :t 1])))
    (is (nil? (re/infer-work-kind [:something/else 1])))
    (is (nil? (re/infer-work-kind nil))))
  (testing "resolve-work-kind: explicit wins, else inferred — the :mutation
            row reuses the :rf.work/resource head but its explicit kind wins"
    (is (= :http (re/resolve-work-kind {:work/id [:rf.work/http :a 1]})))
    (is (= :mutation (re/resolve-work-kind {:work/kind :mutation
                                            :work/id  [:rf.work/resource [:rf.mutation :m1] 1]})))
    (is (= :resource (re/resolve-work-kind {:work/id [:rf.work/resource :k 1]})))))

;; ---------------------------------------------------------------------------
;; (3) reply-row — projects any family's reply map into ONE vocabulary.
;; ---------------------------------------------------------------------------

(deftest reply-row-projection
  (testing ":ok reply — value present, delivered, no error/stale/cancel"
    (let [row (re/reply-row {:status :ok :value {:title "Welcome"}
                             :work/id [:rf.work/http :article 1] :work/kind :http
                             :work/status :completed :attempt 1
                             :rf.frame/id :frame-7 :completed-at 1781078400456
                             :correlation {:request-id "req-9"}})]
      (is (= :ok (:status row)))
      (is (= :http (:work-kind row)))
      (is (= [:rf.work/http :article 1] (:work-id row)))
      (is (= :completed (:work-status row)))
      (is (= :frame-7 (:frame row)))
      (is (= 1781078400456 (:completed-at row)))
      (is (true? (:delivered? row)))
      (is (false? (:stale? row)))
      (is (false? (:cancelled? row)))
      ;; PRIVACY: value/correlation summarized, not raw
      (is (= "map" (:type (:value row))))
      (is (= "map" (:type (:correlation row))))))
  (testing ":error reply — error envelope + family kind, no value"
    (let [row (re/reply-row {:status :error
                             :error {:kind :rf.http/timeout :limit-ms 30000}
                             :work/status :timed-out
                             :work/id [:rf.work/resource :k 2]})]
      (is (= :error (:status row)))
      (is (= :resource (:work-kind row)))     ;; inferred from the head
      (is (= :timed-out (:work-status row)))
      (is (= :rf.http/timeout (:error-kind row)))
      (is (some? (:error row)))
      (is (true? (:delivered? row)))))
  (testing ":cancelled reply — cancel reason + flag"
    (let [row (re/reply-row {:status :cancelled :cancelled? true
                             :cancel/reason :actor-destroyed
                             :error {:kind :rf.http/aborted}
                             :work/id [:rf.work/http :a 1]})]
      (is (= :cancelled (:status row)))
      (is (true? (:cancelled? row)))
      (is (= :actor-destroyed (:cancel-reason row)))
      (is (true? (:delivered? row)))))
  (testing ":stale reply — NOT delivered by default; stale flag + reason; no value"
    (let [row (re/reply-row {:status :stale :stale? true
                             :stale/reason :rf.reply/correlation-mismatch
                             :work/status :suppressed
                             :work/id [:rf.work/resource :k 3]})]
      (is (= :stale (:status row)))
      (is (true? (:stale? row)))
      (is (= :rf.reply/correlation-mismatch (:stale-reason row)))
      (is (false? (:delivered? row)))         ;; the correctness boundary
      (is (nil? (:value row)))))
  (testing "an explicit :rf.reply/delivered? on a stale reply (a test/tool
            target opted into :dispatch-stale?) overrides the derived default"
    (is (true? (:delivered? (re/reply-row {:status :stale :stale? true
                                           :stale/reason :x
                                           :rf.reply/delivered? true})))))
  (testing ":partial reply — both value and error present"
    (let [row (re/reply-row {:status :partial :value {:items []}
                             :error {:kind :rf.graphql/partial-success}
                             :work/id [:rf.work/http :q 1]})]
      (is (= :partial (:status row)))
      (is (some? (:value row)))
      (is (= :rf.graphql/partial-success (:error-kind row)))
      (is (true? (:delivered? row))))))

(deftest reply-map?-predicate
  (is (re/reply-map? {:status :ok :value 1}))
  (is (re/reply-map? {:status :stale :stale? true}))
  (is (not (re/reply-map? {:status :running})))   ;; not a reply status
  (is (not (re/reply-map? {:value 1})))
  (is (not (re/reply-map? [:not :a :map]))))

;; ---------------------------------------------------------------------------
;; (4) status presentation — UNIFORM cross-surface colour class + label.
;; ---------------------------------------------------------------------------

(deftest cross-surface-status-presentation
  (testing "every reply status maps to a shared colour class + label"
    (is (= :success      (re/status-class :ok)))
    (is (= :partial      (re/status-class :partial)))
    (is (= :failure      (re/status-class :error)))
    (is (= :cancellation (re/status-class :cancelled)))
    (is (= :suppression  (re/status-class :stale)))
    (is (= "STALE" (re/status-label :stale)))
    (is (= "OK"    (re/status-label :ok))))
  (testing "an HTTP :stale and a resource :stale render the SAME badge
            (Cross-Cutting F.11 — unified cross-surface STALE)"
    (let [http-stale (re/reply-row {:status :stale :stale? true :stale/reason :x
                                    :work/id [:rf.work/http :a 1]})
          res-stale  (re/reply-row {:status :stale :stale? true :stale/reason :x
                                    :work/id [:rf.work/resource :k 1]})]
      (is (= (re/status-class (:status http-stale))
             (re/status-class (:status res-stale))
             :suppression)))))

;; ---------------------------------------------------------------------------
;; (5) phase-of — every family's op lowers onto ONE reply-envelope phase.
;; ---------------------------------------------------------------------------

(deftest phase-classification
  (testing "issuance / start across families (the landed literals)"
    (is (= :issued (re/phase-of :rf.resource/work-started)))
    (is (= :issued (re/phase-of :rf.resource/fetch-started)))
    ;; EP-0016 D1 (slice 8) — mutation issuance reuses the resource ledger.
    (is (= :issued (re/phase-of :rf.mutation/started)))
    (is (= :issued (re/phase-of :rf.machine.timer/scheduled))))
  (testing "retry / intermediate"
    (is (= :retry (re/phase-of :rf.http/retry-attempt)))
    (is (= :retry (re/phase-of :rf.resource/refetch-decision))))
  (testing "cancellation-requested across families"
    (is (= :cancel-requested (re/phase-of :rf.resource/work-abort-requested)))
    (is (= :cancel-requested (re/phase-of :rf.http/aborted-on-actor-destroy)))
    (is (= :cancel-requested (re/phase-of :rf.http/managed-abort)))
    (is (= :cancel-requested (re/phase-of :rf.machine.timer/cancelled)))
    (is (= :cancel-requested (re/phase-of :rf.machine.spawn/cancelled-on-join-resolution))))
  (testing "completion across families — :rf.http/replied is the canonical
            EP-0011 HTTP completion row (built from the reply envelope)"
    (is (= :completed (re/phase-of :rf.http/replied)))
    (is (= :completed (re/phase-of :rf.http/succeeded)))
    (is (= :completed (re/phase-of :rf.http/failed)))
    (is (= :completed (re/phase-of :rf.resource/work-completed)))
    (is (= :completed (re/phase-of :rf.resource/succeeded)))
    ;; EP-0016 D1 (slice 8) — mutation settlement (phase 5).
    (is (= :completed (re/phase-of :rf.mutation/succeeded)))
    (is (= :completed (re/phase-of :rf.mutation/failed)))
    (is (= :completed (re/phase-of :rf.machine/done))))
  (testing "stale suppression lowers to ONE phase — the landed resource op +
            the shared substrate :rf.reply/suppressed + the mutation analogue"
    (is (= :stale-suppressed (re/phase-of :rf.resource/stale-suppressed)))
    ;; EP-0016 D1 — a stale mutation reply suppresses, never fires :reply-to.
    (is (= :stale-suppressed (re/phase-of :rf.mutation/stale-suppressed)))
    (is (= :stale-suppressed (re/phase-of :rf.reply/suppressed)))
    ;; rf2-waawic — the machine `:after` timer stale completion is now
    ;; EXPLICITLY classified (the suffix heuristic catches `stale-suppress` /
    ;; `suppressed`, NOT `stale-after`), so a machines managed-async stale
    ;; completion is visible in the uniform reply-envelope view.
    (is (= :stale-suppressed (re/phase-of :rf.machine.timer/stale-after)))
    ;; routing emits the explicit literal too.
    (is (= :stale-suppressed (re/phase-of :rf.route.nav-token/stale-suppressed)))
    ;; rf2-azcmd3 — HTTP supersession's superseded-attempt stale row.
    (is (= :stale-suppressed (re/phase-of :rf.http/stale-suppressed))))
  (testing "delivery — the call-site :reply-to continuation is the mutation
            delivery row (EP-0016 D1, phase 6)"
    (is (= :delivered (re/phase-of :rf.mutation/replied)))
    (is (= :delivered (re/phase-of :rf.reply/delivered))))
  (testing "the suffix heuristic classifies a NOT-YET-enumerated family op
            (a new :rf.stream/* surface) before the table learns it"
    (is (= :issued           (re/phase-of :rf.stream/work-started)))
    (is (= :cancel-requested (re/phase-of :rf.stream/abort-requested)))
    (is (= :stale-suppressed (re/phase-of :rf.stream/stale-suppressed)))
    (is (= :completed        (re/phase-of :rf.stream/succeeded))))
  (testing "non-managed-async ops are not reply-envelope ops"
    (is (nil? (re/phase-of :rf.sub/run)))
    (is (nil? (re/phase-of :rf.event/dispatched)))
    (is (not (re/reply-trace-op? :rf.view/render)))
    (is (re/reply-trace-op? :rf.resource/work-completed))))

;; ---------------------------------------------------------------------------
;; (6) work-event-row / project-work-events — cross-family trace projection.
;; ---------------------------------------------------------------------------

(def ^:private mixed-trace
  "A trace buffer mixing resource + HTTP + machine reply-envelope rows plus
  non-managed noise — the cross-family input the uniform vocabulary reads."
  [{:id 1 :operation :rf.sub/run :tags {}}
   {:id 2 :operation :rf.resource/work-started
    :time 100 :tags {:work/id [:rf.work/resource :k 1] :rf.frame/id :f
                     :generation 1 :owner [:lease :a]}}
   {:id 3 :operation :rf.resource/fetch-started
    :time 110 :tags {:work/id [:rf.work/http :req 1] :rf.frame/id :f}}
   {:id 4 :operation :rf.resource/work-abort-requested
    :time 120 :tags {:work/id [:rf.work/resource :k 1] :reason :superseded}}
   {:id 5 :operation :rf.resource/stale-suppressed
    :time 130 :tags {:work/id [:rf.work/resource :k 1]
                     :rf.reply/carried {:work/id [:rf.work/resource :k 1] :generation 1}
                     :rf.reply/current {:work/id [:rf.work/resource :k 2] :generation 2}
                     :outcome :success}}
   ;; the canonical EP-0011 HTTP completion row, built from the reply
   ;; envelope (carries :work/kind + :status verbatim — rf2-zqefg3.2)
   {:id 6 :operation :rf.http/replied
    :time 140 :tags {:work/id [:rf.work/http :req 1] :work/kind :http :status :ok}}])

(deftest work-event-row-projection
  (testing "a resource issuance row → :issued phase, work-id + kind read"
    (let [row (re/work-event-row (nth mixed-trace 1))]
      (is (= :issued (:phase row)))
      (is (= :resource (:work-kind row)))
      (is (= [:rf.work/resource :k 1] (:work-id row)))
      (is (= :f (:frame row)))))
  (testing "an HTTP completion row → :completed phase + reply status + class"
    (let [row (re/work-event-row (nth mixed-trace 5))]
      (is (= :completed (:phase row)))
      (is (= :http (:work-kind row)))
      (is (= :ok (:status row)))
      (is (= :success (:status-class row)))))
  (testing "a cancel-requested row carries the cancel reason"
    (let [row (re/work-event-row (nth mixed-trace 3))]
      (is (= :cancel-requested (:phase row)))
      (is (true? (:cancelled? row)))
      (is (= :superseded (:cancel-reason row)))))
  (testing "a stale-suppression row carries carried + current correlation (PRIVACY
            summarized) keyed on :work/id"
    (let [row (re/work-event-row (nth mixed-trace 4))]
      (is (= :stale-suppressed (:phase row)))
      (is (true? (:stale? row)))
      (is (= [:rf.work/resource :k 1] (:work-id row)))
      (is (some? (:carried row)))
      (is (some? (:current row)))
      (is (= "map" (:type (:carried row))))))
  (testing "non-managed-async rows are dropped"
    (is (nil? (re/work-event-row (first mixed-trace)))))
  (testing "project-work-events keeps every managed row, drops the noise,
            preserves buffer order"
    (let [rows (re/project-work-events mixed-trace)]
      (is (= 5 (count rows)))                     ;; 5 managed of 6 events
      (is (= [2 3 4 5 6] (mapv :id rows))))))

(deftest production-reply-trace-vocabulary
  (testing "rf2-waawic — a machine :rf.machine/done completion row stamped with
            the canonical :work/id (rf2-niarhz) + additive :rf.reply/* facts
            joins the uniform work/reply rows"
    ;; the SHAPE finalize.cljc actually emits: canonical :work/id + :work/kind
    ;; PLUS the additive :rf.reply/* facts.
    (let [row (re/work-event-row
                {:id 10 :operation :rf.machine/done
                 :time 200 :tags {:machine-id :auth :parent-id :root :error? false
                                  :work/id   [:rf.work/machine :auth#1 [:fetch] 1]
                                  :work/kind :machine
                                  :rf.reply/status      :ok
                                  :rf.reply/work-id     [:rf.work/machine :auth#1 [:fetch] 1]
                                  :rf.reply/work-status :completed}})]
      (is (= :completed (:phase row)))
      (is (= :machine (:work-kind row)))
      ;; the canonical :work/id is the join key (Xray groups on bare :work/id)
      (is (= [:rf.work/machine :auth#1 [:fetch] 1] (:work-id row)))
      ;; status read from :rf.reply/status; work-status from :rf.reply/work-status
      (is (= :ok (:status row)))
      (is (= :completed (:work-status row)))))
  (testing "rf2-niarhz — a machine done row that carries ONLY the additive
            :rf.reply/work-id (no canonical :work/id) still joins via the
            work-id-of fallback, so an older-shaped row is not lost"
    (let [row (re/work-event-row
                {:id 11 :operation :rf.machine/done
                 :time 210 :tags {:machine-id :auth
                                  :rf.reply/status      :ok
                                  :rf.reply/work-id     [:rf.work/machine :auth#1 [:fetch] 1]
                                  :rf.reply/work-status :completed}})]
      (is (= [:rf.work/machine :auth#1 [:fetch] 1] (:work-id row)))
      (is (= :machine (:work-kind row)))
      (is (= :ok (:status row)))))
  (testing "rf2-waawic — a machine :after timer stale completion is a
            stale-suppressed row carrying carried/current gate + work-id"
    (let [row (re/work-event-row
                {:id 12 :operation :rf.machine.timer/stale-after
                 :time 220 :tags {:state :loading :delay 500
                                  :work/id [:rf.work/timer [:auth :loading] 3]
                                  :work/kind :timer
                                  :rf.reply/status       :stale
                                  :rf.reply/work-status  :suppressed
                                  :rf.reply/stale-reason :rf.machine.timer/after-epoch-mismatch
                                  :rf.reply/correlation  {:carried {:path [:auth :loading] :rf/after-epoch 3}
                                                          :current {:path [:auth :loading] :rf/after-epoch 4}}}})]
      (is (= :stale-suppressed (:phase row)))
      (is (true? (:stale? row)))
      (is (= :timer (:work-kind row)))
      (is (= [:rf.work/timer [:auth :loading] 3] (:work-id row)))
      (is (= :rf.machine.timer/after-epoch-mismatch (:stale-reason row)))))
  (testing "rf2-azcmd3 — an HTTP supersession stale row reads carried/current
            work-id correlation + the canonical join key"
    (let [row (re/work-event-row
                {:id 13 :operation :rf.http/stale-suppressed
                 :time 230 :tags {:work/id [:rf.work/http :search 1 1] :work/kind :http
                                  :rf.reply/status      :stale
                                  :rf.reply/work-status :suppressed
                                  :rf.reply/stale-reason :rf.http/request-id-superseded
                                  :rf.reply/carried {:work/id [:rf.work/http :search 1 1]}
                                  :rf.reply/current {:work/id [:rf.work/http :search 2 1]}}})]
      (is (= :stale-suppressed (:phase row)))
      (is (true? (:stale? row)))
      (is (= :http (:work-kind row)))
      (is (= [:rf.work/http :search 1 1] (:work-id row)))
      (is (some? (:carried row)))
      (is (some? (:current row)))
      (is (= :rf.http/request-id-superseded (:stale-reason row)))))
  (testing "rf2-6mfkp3 — a PRODUCTION-shaped :rf.resource/stale-suppressed row,
            EXACTLY as `re-frame.resources.events/emit-resource-stale-
            suppressed!` emits it (bespoke :resource/key/:generation/:outcome
            PLUS the additive canonical :rf.reply/* vocabulary), projects work
            id, kind, status, work-status, stale reason, and correlation —
            the gap the synthetic mixed-trace fixture (`:outcome :success`
            only) did not pin: a resource stale row could lose canonical
            status / work-status / stale-reason in Xray without a failure"
    (let [work-id [:rf.work/resource [:s :article/by-id {:id 1}] 1]
          row (re/work-event-row
                {:id 14 :operation :rf.resource/stale-suppressed
                 :time 240
                 :tags {;; the bespoke facts the resource family stamps
                        :rf.frame/id  :rf/default
                        :resource/key [:s :article/by-id {:id 1}]
                        :work/id      work-id
                        :generation   1
                        :outcome      {:reason :stale-reply}
                        ;; the additive canonical EP-0011 reply-envelope
                        ;; vocabulary (Managed-Effects §9) — the SAME shape
                        ;; the machine / HTTP families ride
                        :rf.reply/status       :stale
                        :rf.reply/work-status  :suppressed
                        :rf.reply/work-id      work-id
                        :rf.reply/stale-reason :rf.resource/superseded
                        :rf.reply/correlation  {:generation   {:carried 1 :current 2}
                                                :resource/key [:s :article/by-id {:id 1}]}
                        ;; the shared substrate carried/current gate facts
                        :rf.reply/carried {:work/id work-id :generation 1}
                        :rf.reply/current {:generation 2}}})]
      (is (= :stale-suppressed (:phase row)) "production resource stale row → :stale-suppressed phase")
      (is (true? (:stale? row)))
      (is (= :resource (:work-kind row)) "work-kind inferred from the :rf.work/resource head")
      (is (= work-id (:work-id row)) "the canonical :work/id join key")
      (is (= :rf/default (:frame row)) "frame attribution preserved")
      ;; THE coverage gap: the canonical status / work-status / stale-reason
      ;; survive the projection (read off the additive :rf.reply/* facts).
      (is (= :suppressed (:work-status row))
          "canonical :rf.reply/work-status survives the projection")
      (is (= :rf.resource/superseded (:stale-reason row))
          "canonical :rf.reply/stale-reason survives the projection")
      (is (some? (:carried row)) "carried gate summarized + present")
      (is (some? (:current row)) "current gate summarized + present"))))

;; ---------------------------------------------------------------------------
;; (6b) stale-suppressed rows preserve the canonical EP-0011 :stale STATUS.
;;
;; EP-0011 treats `:stale` as one of the five closed reply statuses; a failed
;; stale-suppression gate makes the reply outcome `:status :stale`, stamped on
;; the production row as `:rf.reply/status`. A stale-suppressed row must
;; therefore project `:status :stale` + `:status-class :suppression` — the
;; SAME cross-surface badge contract a completed row renders — read off the
;; UNAMBIGUOUS `:rf.reply/status`, NOT the bare `:status` (which on a
;; suppression row may carry a LEDGER status like :completed/:failed).
;; ---------------------------------------------------------------------------

(deftest stale-suppression-preserves-status
  (testing "a PRODUCTION-shaped resource stale-suppressed row carrying
            :rf.reply/status :stale projects :status :stale +
            :status-class :suppression"
    (let [work-id [:rf.work/resource [:s :article/by-id {:id 1}] 1]
          row (re/work-event-row
                {:id 50 :operation :rf.resource/stale-suppressed
                 :time 300
                 :tags {:rf.frame/id  :rf/default
                        :resource/key [:s :article/by-id {:id 1}]
                        :work/id      work-id
                        :generation   1
                        :outcome      {:reason :stale-reply}
                        :rf.reply/status       :stale
                        :rf.reply/work-status  :suppressed
                        :rf.reply/stale-reason :rf.resource/superseded
                        :rf.reply/carried {:work/id work-id :generation 1}
                        :rf.reply/current {:generation 2}}})]
      (is (= :stale-suppressed (:phase row)))
      (is (= :stale (:status row)) "canonical EP-0011 reply status preserved")
      (is (= :suppression (:status-class row)) "cross-surface suppression badge")
      (is (true? (:stale? row)))))
  (testing "a PRODUCTION-shaped MUTATION stale-suppressed row (resource head,
            :work/kind :mutation) preserves :status :stale + :suppression"
    (let [work-id [:rf.work/resource [:s :update-article {:id 1}] 2]
          row (re/work-event-row
                {:id 51 :operation :rf.resource/stale-suppressed
                 :time 310
                 :tags {:work/id      work-id
                        :work/kind    :mutation
                        :rf.reply/status       :stale
                        :rf.reply/work-status  :suppressed
                        :rf.reply/stale-reason :rf.resource/superseded}})]
      (is (= :stale-suppressed (:phase row)))
      (is (= :mutation (:work-kind row)))
      (is (= :stale (:status row)))
      (is (= :suppression (:status-class row)))))
  (testing "a PRODUCTION-shaped ROUTE stale-suppressed row preserves the status"
    (let [work-id [:rf.work/route :nav 3]
          row (re/work-event-row
                {:id 52 :operation :rf.route/stale-suppressed
                 :time 320
                 :tags {:work/id      work-id
                        :work/kind    :route
                        :rf.reply/status       :stale
                        :rf.reply/work-status  :suppressed
                        :rf.reply/stale-reason :rf.route/nav-superseded}})]
      (is (= :stale-suppressed (:phase row)))
      (is (= :route (:work-kind row)))
      (is (= :stale (:status row)))
      (is (= :suppression (:status-class row)))))
  (testing "a PRODUCTION-shaped HTTP supersession stale row preserves the status"
    (let [row (re/work-event-row
                {:id 53 :operation :rf.http/stale-suppressed
                 :time 330 :tags {:work/id [:rf.work/http :search 1 1] :work/kind :http
                                  :rf.reply/status      :stale
                                  :rf.reply/work-status :suppressed
                                  :rf.reply/stale-reason :rf.http/request-id-superseded
                                  :rf.reply/carried {:work/id [:rf.work/http :search 1 1]}
                                  :rf.reply/current {:work/id [:rf.work/http :search 2 1]}}})]
      (is (= :stale-suppressed (:phase row)))
      (is (= :http (:work-kind row)))
      (is (= :stale (:status row)))
      (is (= :suppression (:status-class row)))))
  (testing "a PRODUCTION-shaped MACHINE :after-timer stale row preserves the status"
    (let [row (re/work-event-row
                {:id 54 :operation :rf.machine.timer/stale-after
                 :time 340 :tags {:work/id [:rf.work/timer [:auth :loading] 3]
                                  :work/kind :timer
                                  :rf.reply/status       :stale
                                  :rf.reply/work-status  :suppressed
                                  :rf.reply/stale-reason :rf.machine.timer/after-epoch-mismatch}})]
      (is (= :stale-suppressed (:phase row)))
      (is (= :timer (:work-kind row)))
      (is (= :stale (:status row)))
      (is (= :suppression (:status-class row)))))
  (testing "the bare LEDGER :status on a suppression row is NOT misread as a
            reply status — only the unambiguous :rf.reply/status surfaces. A
            row stamping bare :status :completed but NO :rf.reply/status gets
            no reply status (the row is a suppression, not an :ok completion)"
    (let [row (re/work-event-row
                {:id 55 :operation :rf.resource/stale-suppressed
                 :time 350 :tags {:work/id [:rf.work/resource :k 1]
                                  :work/kind :resource
                                  ;; bare ledger status, NOT a reply status fact
                                  :status :completed}})]
      (is (= :stale-suppressed (:phase row)))
      (is (nil? (:status row)) "bare ledger :status :completed not misread")
      (is (nil? (:status-class row)))
      (is (true? (:stale? row)) "still a stale-suppressed row")))
  (testing "completed rows are unchanged — the four non-stale closed statuses
            still project from a :completed row"
    (doseq [[s expected-class] {:ok :success :partial :partial
                                :error :failure :cancelled :cancellation}]
      (let [row (re/work-event-row
                  {:id 56 :operation :rf.http/work-completed
                   :time 360 :tags {:work/id [:rf.work/http :req 1]
                                    :rf.reply/status s}})]
        (is (= :completed (:phase row)))
        (is (= s (:status row)) (str "completed status " s " unchanged"))
        (is (= expected-class (:status-class row)))))))

;; ---------------------------------------------------------------------------
;; (6c) Frame attribution across families (rf2-l9vb09). Two LEGITIMATE frame
;; spellings ride a managed-async reply TRACE ROW, by family:
;;   - resources / machines / mutations stamp the EP-0002 carried-frame stamp
;;     `:rf.frame/id` in `:tags` (the reply-envelope facts — Managed-Effects
;;     §The reply map; e.g. `re-frame.resources.events`);
;;   - HTTP stamps the bare `:frame` in `:tags` — the generic raw-event
;;     carve-out read by the contract-owned canonical reader
;;     `re-frame.trace/trace-event-frame` ([:tags :frame], rf2-7737vq; e.g.
;;     `re-frame.http.transport`'s `:rf.http/stale-suppressed` emit).
;; `work-event-row` prefers `:rf.frame/id`, falling back to the canonical
;; reader for the bare `[:tags :frame]`. The historical dead over-reads — bare
;; `:frame-id` in `:tags` (rf2-shaa1 dropped it; NO emit site produces it) and
;; a top-level `:frame` on the raw event (raw events carry frame ONLY under
;; `:tags`) — are gone, pinned-out here.
;;
;; The reply MAP layer (`reply-row`) is UNIFORM on `:rf.frame/id` across every
;; family — even HTTP, whose reply BUILDER maps its internal `:frame` ctx onto
;; `:rf.frame/id` on the dispatched map (`re-frame.http.reply`).
;; ---------------------------------------------------------------------------

(deftest reply-row-frame-attribution-across-families
  (testing "work-event-row reads :frame off the canonical [:tags :rf.frame/id]
            carried stamp — the resource / machine / mutation reply-row shape"
    (let [row (re/work-event-row
                {:id 60 :operation :rf.resource/work-started
                 :time 400 :tags {:work/id [:rf.work/resource :k 1]
                                  :rf.frame/id :app/main}})]
      (is (= :app/main (:frame row))
          "frame read off the EP-0002 carried-frame stamp :rf.frame/id")))
  (testing "work-event-row resolves the HTTP family's bare [:tags :frame] slot
            via the canonical raw-event reader trace-event-frame (rf2-7737vq) —
            EXACTLY as re-frame.http.transport's :rf.http/stale-suppressed emits"
    (let [row (re/work-event-row
                {:id 61 :operation :rf.http/stale-suppressed
                 :time 410 :tags {:work/id [:rf.work/http :search 1 1]
                                  :work/kind :http
                                  :rf.reply/status :stale
                                  ;; HTTP's family frame spelling — the bare
                                  ;; [:tags :frame] carve-out
                                  :frame :app/main}})]
      (is (= :app/main (:frame row))
          "HTTP's bare [:tags :frame] resolved via the canonical reader")))
  (testing ":rf.frame/id is preferred over the bare [:tags :frame] when a row
            (defensively) carried both"
    (let [row (re/work-event-row
                {:id 62 :operation :rf.resource/work-started
                 :time 420 :tags {:work/id [:rf.work/resource :k 1]
                                  :rf.frame/id :canonical/win
                                  :frame :raw/lose}})]
      (is (= :canonical/win (:frame row)))))
  (testing "the dead alias reads are gone — bare :frame-id in tags + a top-level
            :frame on the event are NOT consulted (no production row emits them)"
    (let [row (re/work-event-row
                {:id 63 :operation :rf.resource/work-started
                 :time 430
                 :frame :top-level/dead              ;; dead top-level alias
                 :tags {:work/id [:rf.work/resource :k 1]
                        :frame-id :tags-bare/dead}})] ;; dead bare-:frame-id alias
      (is (nil? (:frame row))
          "neither the dead bare :frame-id nor the top-level :frame is read")))
  (testing "reply-row (the reply-MAP projector) rides :rf.frame/id uniformly
            (incl. HTTP) and ignores the dead :frame-id / :frame aliases"
    (let [canonical (re/reply-row {:status :ok :work/id [:rf.work/http :req 1]
                                   :rf.frame/id :app/main})
          dead      (re/reply-row {:status :ok :work/id [:rf.work/http :req 1]
                                   :frame-id :bare/dead :frame :top/dead})]
      (is (= :app/main (:frame canonical))
          "reply map frame read off the canonical :rf.frame/id")
      (is (nil? (:frame dead))
          "the dead :frame-id / :frame reply-map aliases are not read"))))

;; ---------------------------------------------------------------------------
;; (7) stale-races — keyed on :work/id; cross-surface tally; attempt arcs.
;; ---------------------------------------------------------------------------

(deftest stale-races-keyed-on-work-id
  (testing "stale-suppressions surfaces every family's suppression row"
    (let [sup (re/stale-suppressions mixed-trace)]
      (is (= 1 (count sup)))
      (is (= [:rf.work/resource :k 1] (:work-id (first sup))))
      (is (= :resource (:work-kind (first sup))))))
  (testing "the cross-surface stale tally counts per work-kind — the shared
            substrate :rf.reply/suppressed row, kind inferred from the head"
    (let [trace (conj mixed-trace
                      {:id 7 :operation :rf.reply/suppressed
                       :time 150 :tags {:work/id [:rf.work/http :req 9]}})]
      (is (= {:resource 1 :http 1} (re/stale-tally-by-kind trace)))))
  (testing "races-by-work-id groups the attempt arc, keyed on :work/id —
            the resource arc reached issued → cancel-requested → suppressed
            (terminal :stale, suppressed?)"
    (let [races (re/races-by-work-id mixed-trace)
          res   (get races [:rf.work/resource :k 1])
          http  (get races [:rf.work/http :req 1])]
      (is (= #{:issued :cancel-requested :stale-suppressed} (:phases res)))
      (is (= :stale (:terminal-status res)))
      (is (true? (:suppressed? res)))
      (is (= :resource (:work-kind res)))
      ;; the HTTP arc reached :ok, not suppressed
      (is (= #{:issued :completed} (:phases http)))
      (is (= :ok (:terminal-status http)))
      (is (false? (:suppressed? http))))))

;; ---------------------------------------------------------------------------
;; (8) live-work — ledger rows joined to reply status + trace cause, uniform.
;; ---------------------------------------------------------------------------

(def ^:private ledger
  "A frame work-ledger map mixing live + terminal rows across kinds — the
  durable substrate the 'what is still running?' join reads."
  {[:rf.work/resource :k 2]
   {:work/id [:rf.work/resource :k 2] :work/kind :resource :work/frame :f
    :resource/key [:s :article/by-id {:id 1}] :generation 2 :status :running
    :owners #{[:lease :a]} :causes [[:ensure :article/by-id]] :cancellable? true
    :started-at 1000 :transport :rf.http/managed}
   [:rf.work/resource :k 1]
   {:work/id [:rf.work/resource :k 1] :work/kind :resource :work/frame :f
    :resource/key [:s :article/by-id {:id 1}] :generation 1 :status :suppressed
    :owners #{} :causes [] :outcome {:reason :stale-reply}}
   [:rf.work/http :req 5]
   {:work/id [:rf.work/http :req 5] :work/kind :http :work/frame :f
    :generation 1 :status :abort-requested :owners #{} :causes []
    :transport :rf.http/managed}})

(deftest ledger-row-projection
  (testing "a running resource row projects live, kind + owners + causes read"
    (let [row (re/ledger-row [[:rf.work/resource :k 2] (get ledger [:rf.work/resource :k 2])])]
      (is (= :resource (:work-kind row)))
      (is (= :running (:status row)))
      (is (true? (:live? row)))
      (is (= :f (:frame row)))
      (is (= [[:lease :a]] (:owners row)))
      (is (= :rf.http/managed (:transport row)))
      ;; PRIVACY: cause summarized
      (is (= "vector" (:type (first (:causes row)))))))
  (testing "kind inference fills in when the record omits :work/kind"
    (is (= :http (:work-kind (re/ledger-row [[:rf.work/http :r 1] {:status :running}])))))
  (testing "a suppressed row is terminal (not live), outcome summarized"
    (let [row (re/ledger-row [[:rf.work/resource :k 1] (get ledger [:rf.work/resource :k 1])])]
      (is (false? (:live? row)))
      (is (some? (:outcome row))))))

(deftest live-work-join
  (testing "live-work returns only non-terminal rows across families"
    (let [live (re/live-work ledger)]
      (is (= 2 (count live)))                    ;; the :running resource + :abort-requested http
      (is (= #{[:rf.work/resource :k 2] [:rf.work/http :req 5]}
             (into #{} (map :work-id) live)))))
  (testing "the tally counts live work per kind — the active-effects headline"
    (is (= {:resource 1 :http 1} (re/live-work-tally-by-kind ledger))))
  (testing "joining to the trace stream enriches each live row with its latest
            reply-envelope phase + emitting op (UNIFORM across families)"
    (let [trace [{:id 1 :operation :rf.resource/work-started :time 100
                  :tags {:work/id [:rf.work/resource :k 2]}}
                 {:id 2 :operation :rf.http/aborted-on-actor-destroy :time 110
                  :tags {:work/id [:rf.work/http :req 5] :reason :actor-destroyed}}]
          live  (re/live-work ledger trace)
          by-id (into {} (map (juxt :work-id identity)) live)]
      (is (= :issued (:latest-phase (get by-id [:rf.work/resource :k 2]))))
      (is (= :rf.resource/work-started (:latest-op (get by-id [:rf.work/resource :k 2]))))
      (is (= :cancel-requested (:latest-phase (get by-id [:rf.work/http :req 5]))))))
  (testing "the single-arity form returns live rows without the trace join"
    (let [live (re/live-work ledger)]
      (is (every? #(not (contains? % :latest-phase)) live))))
  (testing "PRODUCTION byte/string-keyed ledger — the :rf.runtime/work-ledger
            map is keyed on the opaque CEDN-1 byte `work-id-id` STRING, while
            the kind-preserving work-id VECTOR rides on the record as :work/id.
            ledger-row / live-work must read the CANONICAL vector from the
            record (not the byte key) so the row joins to a trace row keyed by
            the vector and inference works when :work/kind is present."
    (let [work-id    [:rf.work/resource [:s :article/by-id {:id 1}] 2]
          byte-key   "£opaque-byte-work-id-id"
          ;; the PRODUCTION ledger shape: byte string key → record with :work/id
          prod-ledger {byte-key
                       {:work/id work-id :work/kind :resource :work/frame :f
                        :resource/key [:s :article/by-id {:id 1}] :generation 2
                        :status :running :owners #{} :causes [] :cancellable? true
                        :transport :rf.http/managed}}
          ;; the trace row is keyed by the kind-preserving VECTOR work id
          trace      [{:id 1 :operation :rf.resource/work-abort-requested :time 100
                       :tags {:work/id work-id :reason :superseded}}]
          row        (re/ledger-row (first prod-ledger))]
      ;; ledger-row reads the canonical vector, NOT the opaque byte key
      (is (= work-id (:work-id row)) "canonical :work/id vector, not the byte key")
      (is (= :resource (:work-kind row)))
      (is (true? (:live? row)))
      ;; live-work joins the byte-keyed ledger row to the vector-keyed trace row
      (let [live  (re/live-work prod-ledger trace)
            joined (first live)]
        (is (= 1 (count live)))
        (is (= work-id (:work-id joined)))
        (is (= :cancel-requested (:latest-phase joined))
            "joined to the vector-keyed trace row")
        (is (= :rf.resource/work-abort-requested (:latest-op joined))))))
  (testing "kind inference works off the canonical vector when :work/kind is
            absent on a byte-keyed production record"
    (let [work-id  [:rf.work/http :req 7]
          byte-key "opaque"
          row      (re/ledger-row [byte-key {:work/id work-id :status :running}])]
      (is (= work-id (:work-id row)) "canonical vector, not the byte key")
      (is (= :http (:work-kind row)) "kind inferred from the vector head, not the byte key")))
  (testing "a LEGACY/nonconforming record lacking :work/id falls back to the
            map key (existing vector-keyed behavior unchanged)"
    (let [row (re/ledger-row [[:rf.work/resource :k 2] {:status :running}])]
      (is (= [:rf.work/resource :k 2] (:work-id row)))
      (is (= :resource (:work-kind row)))))
  (testing "latest-phase-by-work-id keeps the MOST-RECENT phase per work id"
    (let [trace [{:id 1 :operation :rf.resource/work-started :time 100
                  :tags {:work/id [:rf.work/resource :k 2]}}
                 {:id 2 :operation :rf.resource/work-abort-requested :time 200
                  :tags {:work/id [:rf.work/resource :k 2] :reason :superseded}}]
          idx   (re/latest-phase-by-work-id trace)]
      (is (= :cancel-requested (:phase (get idx [:rf.work/resource :k 2])))))))

;; ---------------------------------------------------------------------------
;; live? predicate — the non-terminal ledger statuses.
;; ---------------------------------------------------------------------------

(deftest live?-predicate
  (is (re/live? :running))
  (is (re/live? :queued))
  (is (re/live? :abort-requested))
  (is (not (re/live? :completed)))
  (is (not (re/live? :suppressed)))
  (is (not (re/live? :cancelled))))
