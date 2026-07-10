(ns re-frame.reply-vocab-conformance-cljs-test
  "Cross-implementation conformance for the uniform managed-async reply
  vocabulary.

  A descriptor matrix exercises HTTP, resource, mutation, machine, machine
  timer, and route reply builders for the situations each implementation
  supports (`:success`, `:error`, `:cancel`, and `:stale`). Shared assertions
  require the canonical envelope shapes:

    (a) success    → `:status :ok`        + `:rf.reply/work-status :completed`
    (b) error      → `:status :error`     + `:rf.reply/work-status :failed`
                     (or `:timed-out`, the timeout work-status) + a family
                     `:error` MAP carrying a `:kind`
    (c) cancel     → `:status :cancelled` + `:rf.reply/work-status :cancelled`
                     + `:cancelled? true`
    (d) stale      → `:status :stale`     + `:rf.reply/work-status :suppressed`
                     + `:stale? true` + a carried/current correlation gate,
                     and NO `:value` (no app mutation)

  The suite also checks the invariants shared by every reply: each reply
  validates through `re-frame.reply/validate-reply`; the
  `:status` is in the closed `re-frame.reply/statuses`; the `:rf.reply/work-status`
  (when present) is in the closed `re-frame.reply/work-statuses`; the
  `:rf.reply/work-id` is a `[:rf.work/* …]` tuple that is EDN-round-trippable;
  and the reply is data-only. It separately compares stale correlation and
  causal `:completed-at` propagation across implementations.

  This is pure conformance over builders, with no runtime fixture. It lives in
  a separate test artefact because its dependencies cross core, HTTP,
  resources, machines, and routing. Reply-target mapping and its functor laws
  are owned by the core substrate and timer-probe suites, not this matrix. The
  `.cljc` namespace runs in both the CLJS node gate and the JVM test alias.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope."
  (:require [clojure.test :refer [deftest is testing]]
            ;; The CLJS reader arm below requires the namespace explicitly;
            ;; the JVM arm uses clojure.core/read-string.
            #?(:cljs [cljs.reader])
            [re-frame.reply :as reply]
            [re-frame.reply-conformance-fixtures :as fixtures]
            [re-frame.http.reply :as http-reply]
            [re-frame.resources.reply :as rreply]
            [re-frame.machines.reply :as m-reply]
            [re-frame.routing.reply :as route-reply]))

;; ---------------------------------------------------------------------------
;; Work ids are portable data, so both readers must reconstruct an equal value.
;; ---------------------------------------------------------------------------

(defn- edn-roundtrip [x]
  #?(:clj  (read-string (pr-str x))
     :cljs (cljs.reader/read-string (pr-str x))))

;; ---------------------------------------------------------------------------
;; Builder inputs remain family-specific; the matrix compares their reply maps.
;; ---------------------------------------------------------------------------

;; This tier checks only reply-map `:completed-at` propagation. Router and
;; family lowering suites own live dispatch-envelope `:rf.cofx` coverage.
(def ^:private completion-time-ms fixtures/completion-time-ms)

(def ^:private http-ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at completion-time-ms})

(def ^:private resource-vp
  {:work/id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :resource/key [:rf.scope/global :article/by-slug {:slug "w"}]
   :scope        :rf.scope/global
   :generation   4
   :rf.frame/id  :app/main})

(def ^:private mutation-vp
  {:work/id     [:rf.work/resource [:rf.mutation :form/save-1] 2]
   :instance-id :form/save-1
   :mutation-id :article/save
   :scope       :rf.scope/global
   :generation  2
   :rf.frame/id :app/main})

(def ^:private machine-ctx
  {:actor-id          :auth/flow#1
   :parent-id         :auth/main
   :work-bearing-path [:authenticating]
   :frame             :app/main
   :completed-at      completion-time-ms})

;; Family errors must be maps with a :kind; loose scalar errors are invalid.
(def ^:private a-failure {:kind :rf.http/http-5xx :status 503 :body "down"})

;; HTTP, resources, and mutations lower an intentional abort as cancellation.
(def ^:private an-abort {:kind :rf.http/aborted :reason :user})

;; ---------------------------------------------------------------------------
;; Each descriptor supplies the situations produced by one implementation:
;;   :family        — the family keyword (diagnostics)
;;   :work-head     — the expected `[:rf.work/* …]` head keyword
;;   :success       — () → success reply map, or nil if N/A
;;   :error         — () → error reply map, or nil if N/A
;;   :error-work-status — the expected error :rf.reply/work-status (:failed default;
;;                    HTTP timeout family also proves :timed-out separately)
;;   :cancel        — () → cancelled reply map, or nil if N/A
;;   :stale         — () → stale reply map, or nil if N/A
;;
;; A nil builder means that implementation does not produce a reply for the
;; situation. For example, route errors and cancellation remain HTTP transport
;; replies rather than route-family envelopes.
;; ---------------------------------------------------------------------------

(defn- machine-stale-reply []
  (m-reply/stale-spawn-reply (assoc machine-ctx :current-generation nil)))

(defn- after-stale-reply []
  (m-reply/after-stale-reply
    {:actor-id        :a/multi
     :state           :loading
     :delay           30000
     :decl-path       [:loading]
     :scheduled-epoch 1
     :current-epoch   2
     :frame           :app/main}))

;; The paired timer builders exercise presence and omission of `:completed-at`.
(defn- after-fired-reply []
  (m-reply/after-fired-reply
    {:actor-id   :a/multi
     :state      :loading
     :delay      30000
     :decl-path  [:loading]
     :epoch      1
     :frame      :app/main}))

(defn- after-fired-reply-with-time []
  (m-reply/after-fired-reply
    {:actor-id     :a/multi
     :state        :loading
     :delay        30000
     :decl-path    [:loading]
     :epoch        1
     :frame        :app/main
     :completed-at completion-time-ms}))

;; A current route loader builds a route-family success envelope after its
;; HTTP transport completes.
(def ^:private route-ctx
  {:route-id     :route/article
   :nav-token    "nav-1"
   :loader-id    :article/loaded
   :frame        :app/main
   :completed-at completion-time-ms})

(defn- route-live-reply []
  (route-reply/live-reply route-ctx {:title "Welcome"}))

(defn- route-success-no-time []
  (route-reply/live-reply (dissoc route-ctx :completed-at) {:title "Welcome"}))

;; ---------------------------------------------------------------------------
;; Companion builders omit the completion time so the matrix can distinguish
;; omission from a nil sentinel.
;; ---------------------------------------------------------------------------

(defn- http-success-no-time []
  (http-reply/success-reply (dissoc http-ctx :completed-at) {:title "Welcome"}))

(defn- resource-success-no-time []
  (rreply/success-reply resource-vp {:title "Welcome"}
                        {:work-kind rreply/work-kind-resource}))

(defn- mutation-success-no-time []
  (rreply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                        {:work-kind rreply/work-kind-mutation}))

(defn- machine-success-no-time []
  (m-reply/success-reply (dissoc machine-ctx :completed-at) {:user-id "u-42"}))

;; ---------------------------------------------------------------------------
;; HTTP, resource, mutation, and route delegate stale classification to a
;; suppress outcome. Each builder returns the
;; `re-frame.reply/suppress` outcome map `{:deliver? :reply :rf.reply/work-status
;; :trace}`; the matrix derives the stale reply from its `:reply` slot. The
;; `:extra` maps use reply-envelope identity keys, while the nested carried and
;; current verification payloads retain their bare work-ledger keys.
;; ---------------------------------------------------------------------------

(defn- http-stale-out []
  ;; The current work id names the superseding HTTP attempt.
  (http-reply/suppress http-ctx [:rf.work/http :article/by-id 2 1]))

(defn- resource-stale-out []
  (rreply/stale-reply
    {:carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
     :current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
     :extra   {:rf.reply/work-id      [:rf.work/resource [:rf.scope/global :r {}] 4]
               :rf.reply/work-kind    :resource
               :rf.frame/id           :app/main
               :rf.reply/stale-reason :resource/generation-mismatch}}))

(defn- mutation-stale-out []
  (rreply/stale-reply
    {:carried {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 2] :generation 2}
     :current {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 3] :generation 3}
     :extra   {:rf.reply/work-id      [:rf.work/resource [:rf.mutation :form/save-1] 2]
               :rf.reply/work-kind    :mutation
               :rf.frame/id           :app/main
               :rf.reply/stale-reason :mutation/superseded}}))

(defn- route-stale-out []
  (route-reply/suppress {:route-id  :route/article
                         :nav-token "nav-1"
                         :loader-id :article/loaded
                         :frame     :app/main}
                        "nav-2"))

(def ^:private families
  [{:family          :http
    :work-head       :rf.work/http
    :success         #(http-reply/success-reply http-ctx {:title "Welcome"})
    :success-no-time http-success-no-time
    :error           #(http-reply/failure-reply http-ctx a-failure)
    :cancel          #(http-reply/failure-reply http-ctx an-abort)
    ;; HTTP supersession is a stale completion of the earlier request attempt.
    :stale-out       http-stale-out
    :stale           #(:reply (http-stale-out))}

   {:family          :resource
    :work-head       :rf.work/resource
    :success         #(rreply/success-reply resource-vp {:title "Welcome"}
                                            {:work-kind rreply/work-kind-resource
                                             :completed-at completion-time-ms})
    :success-no-time resource-success-no-time
    :error           #(rreply/failure-reply resource-vp a-failure
                                            {:work-kind rreply/work-kind-resource})
    :cancel          #(rreply/failure-reply resource-vp an-abort
                                            {:work-kind rreply/work-kind-resource})
    :stale-out       resource-stale-out
    :stale           #(:reply (resource-stale-out))}

   {:family          :mutation
    :work-head       :rf.work/resource ;; mutation reuses the resource head with a [:rf.mutation …] key
    :success         #(rreply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                                            {:work-kind rreply/work-kind-mutation
                                             :completed-at completion-time-ms})
    :success-no-time mutation-success-no-time
    :error           #(rreply/failure-reply mutation-vp a-failure
                                            {:work-kind rreply/work-kind-mutation})
    :cancel          #(rreply/failure-reply mutation-vp an-abort
                                            {:work-kind rreply/work-kind-mutation})
    :stale-out       mutation-stale-out
    :stale           #(:reply (mutation-stale-out))}

   {:family          :machine
    :work-head       :rf.work/machine
    :success         #(m-reply/success-reply machine-ctx {:user-id "u-42"})
    :success-no-time machine-success-no-time
    :error           #(m-reply/error-reply machine-ctx {:reason :bad-creds})
    ;; Destroying an actor closes its work attempt with cancellation data.
    :cancel          #(m-reply/cancelled-actor-reply
                        (assoc machine-ctx :reason :explicit))
    ;; Machine replies carry their stale gate directly in `:correlation`.
    :stale           machine-stale-reply}

   ;; A machine :after timer is the concrete timer implementation in this tier.
   {:family          :timer
    :work-head       :rf.work/timer
    ;; Use paired fired completions to test completion-time presence and absence.
    :success         after-fired-reply-with-time
    :success-no-time after-fired-reply
    :error           nil
    ;; State exit closes an outstanding timer with cancellation data.
    :cancel          #(m-reply/cancelled-timer-reply
                        {:actor-id  :a/multi
                         :state     :loading
                         :delay     30000
                         :decl-path [:loading]
                         :epoch     1
                         :frame     :app/main
                         :reason    :on-exit})
    ;; Timer replies carry their epoch gate directly in `:correlation`.
    :stale           after-stale-reply}

   ;; Route owns the current-navigation success envelope and stale nav-token
   ;; suppression; transport errors and cancellation remain HTTP replies.
   {:family          :route
    :work-head       :rf.work/route
    :success         route-live-reply
    :success-no-time route-success-no-time
    :error           nil
    :cancel          nil
    :stale-out       route-stale-out
    :stale           #(:reply (route-stale-out))}])

;; ---------------------------------------------------------------------------
;; Universal envelope invariants across all supported situations.
;; ---------------------------------------------------------------------------

(deftest every-reply-validates-against-the-one-shared-contract
  (doseq [{:keys [family] :as f} families
          [situation builder] (select-keys f [:success :error :cancel :stale])
          :when builder
          :let [reply (builder)]]
    (testing (str family " / " situation)
      (is (reply/valid-reply? reply)
          (str family " " situation " reply MUST validate against the shared "
               "re-frame.reply/validate-reply: " (reply/validate-reply reply)))
      (testing ":status is in the closed status vocabulary"
        (is (contains? reply/statuses (:status reply))
            (str family " " situation " :status " (:status reply)
                 " is not in the closed " reply/statuses)))
      (testing ":rf.reply/work-status is in the closed work-status vocabulary"
        (when (contains? reply :rf.reply/work-status)
          (is (contains? reply/work-statuses (:rf.reply/work-status reply))
              (str family " " situation " :rf.reply/work-status " (:rf.reply/work-status reply)
                   " is not in the closed " reply/work-statuses))))
      (testing "the reply contains no host handles"
        (is (not-any? #(= :rf.reply/host-handle (:rf.reply/problem %))
                      (reply/validate-reply reply))
            (str family " " situation " reply carries a host handle"))))))

(deftest every-work-id-is-a-comparable-edn-tuple
  ;; Every descriptor supplies a work-id head. Missing ids fail explicitly
  ;; instead of skipping the tuple and round-trip assertions.
  (doseq [{:keys [family work-head] :as f} families
          [situation builder] (select-keys f [:success :error :cancel :stale])
          :when (and builder work-head)
          :let [reply (builder)
                wid   (:rf.reply/work-id reply)]]
    (testing (str family " / " situation " :rf.reply/work-id correlation")
      (is (some? wid)
          (str family " " situation " reply must carry :rf.reply/work-id"))
      (is (vector? wid) (str family " " situation " reply work id is not a vector"))
      (is (= work-head (first wid))
          (str family " " situation " reply work-id head is " (first wid)
               ", expected " work-head))
      (is (= wid (edn-roundtrip wid))
          (str family " " situation " reply work id is not EDN-round-trippable")))))

(deftest timer-family-work-ids-share-the-actor-bearing-logical-id
  ;; Fired, stale, and cancelled replies describe one actor-owned timer, so
  ;; their work ids share the actor-prefixed logical id.
  (let [fired  (:rf.reply/work-id (after-fired-reply))
        stale  (:rf.reply/work-id (after-stale-reply))
        cancel (:rf.reply/work-id (m-reply/cancelled-timer-reply
                           {:actor-id  :a/multi
                            :state     :loading
                            :delay     30000
                            :decl-path [:loading]
                            :epoch     1
                            :frame     :app/main
                            :reason    :on-exit}))]
    (testing "all three timer completions carry the :rf.work/timer head"
      (doseq [[situation wid] [[:fired fired] [:stale stale] [:cancel cancel]]]
        (is (= :rf.work/timer (first wid))
            (str situation " timer reply work-id head is not :rf.work/timer"))))
    (testing "each timer work id uses the actor-bearing logical id"
      (doseq [[situation wid] [[:fired fired] [:stale stale] [:cancel cancel]]]
        (is (= [:a/multi :loading] (second wid))
            (str situation " timer logical-id must be actor-prefixed [:a/multi :loading], not "
                 (pr-str (second wid))))))
    (testing "the timer family shares one logical id across situations"
      (is (= (second fired) (second stale) (second cancel))
          "fired / stale / cancel timer work-ids share one actor-bearing logical-id"))))

;; ---------------------------------------------------------------------------
;; Success shape: :status :ok, completed work status, and a value.
;; ---------------------------------------------------------------------------

(deftest success-shape-is-consistent-across-families
  (doseq [{:keys [family success]} families
          :when success
          :let [reply (success)]]
    (testing (str family " success → canonical :ok / :completed")
      (is (= :ok (:status reply))
          (str family " success :status must be :ok, got " (:status reply)))
      (is (= :completed (:rf.reply/work-status reply))
          (str family " success :rf.reply/work-status must be :completed, got " (:rf.reply/work-status reply)))
      (is (contains? reply :value)
          (str family " success reply MUST carry a :value (the decoded result)"))
      (is (nil? (:error reply))
          (str family " success reply MUST NOT carry an :error")))))

;; ---------------------------------------------------------------------------
;; Causal completion time: every success builder propagates a supplied
;; `:completed-at` unchanged and omits the field when none was supplied. This
;; tier owns only the reply-map comparison; router and family lowering suites
;; own the matching live dispatch-envelope `:rf.cofx` checks.
;; ---------------------------------------------------------------------------

(deftest completion-time-propagates-uniformly-across-families
  (testing "every family propagates the supplied causal completion time"
    (doseq [{:keys [family success]} families
            :let [reply (success)]]
      (testing (str family " success → :completed-at present + uniform")
        (is (contains? reply :completed-at)
            (str family " success reply MUST carry the :completed-at causal "
                 "completion fact when the completion time was supplied"))
        (is (= completion-time-ms (:completed-at reply))
            (str family " success :completed-at must be the supplied causal "
                 "time " completion-time-ms ", got " (:completed-at reply)))))))

(deftest completion-time-is-omitted-not-nil-when-absent
  (testing "a family not supplied a completion time omits :completed-at"
    (doseq [{:keys [family success-no-time]} families
            :let [reply (success-no-time)]]
      (testing (str family " success (no time supplied) → :completed-at omitted")
        (is (not (contains? reply :completed-at))
            (str family " success reply MUST OMIT :completed-at when no "
                 "completion time was supplied — never nil-fill it (a nil "
                 "sentinel would let a reducer derive a bogus durable "
                 "timestamp). Got " (pr-str (:completed-at reply))))
        ;; Omitting the optional fact must not invalidate the envelope.
        (is (reply/valid-reply? reply)
            (str family " no-time success reply still validates: "
                 (reply/validate-reply reply)))
        (is (= :ok (:status reply))
            (str family " no-time success is still :status :ok"))))))

;; ---------------------------------------------------------------------------
;; Error shape: :status :error, a terminal failure work status, and a
;; family-specific error map with a :kind.
;; ---------------------------------------------------------------------------

(deftest error-shape-is-consistent-across-families
  (doseq [{:keys [family error]} families
          :when error
          :let [reply (error)]]
    (testing (str family " error → canonical :error + family-error map")
      (is (= :error (:status reply))
          (str family " error :status must be :error, got " (:status reply)))
      (is (contains? #{:failed :timed-out} (:rf.reply/work-status reply))
          (str family " error :rf.reply/work-status must be :failed (or :timed-out), got "
               (:rf.reply/work-status reply)))
      (is (map? (:error reply))
          (str family " error :error must be a family-error MAP (never a loose scalar)"))
      (is (some? (:kind (:error reply)))
          (str family " error :error map MUST carry a :kind")))))

(deftest http-timeout-is-error-plus-timed-out-work-status
  (testing "timeout is an error reply with :timed-out work status"
    (let [reply (http-reply/failure-reply http-ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (reply/valid-reply? reply) (str (reply/validate-reply reply)))
      (is (= :error (:status reply)) "timeout is NOT a top-level :status")
      (is (= :timed-out (:rf.reply/work-status reply)))
      (is (contains? reply/work-statuses (:rf.reply/work-status reply))
          ":timed-out is in the closed work-status vocabulary"))))

;; ---------------------------------------------------------------------------
;; Cancellation shape: cancelled status and work status, the boolean marker,
;; and a cancellation reason.
;; ---------------------------------------------------------------------------

(deftest cancel-shape-is-consistent-across-families
  (doseq [{:keys [family cancel]} families
          :when cancel
          :let [reply (cancel)]]
    (testing (str family " cancel → canonical :cancelled")
      (is (= :cancelled (:status reply))
          (str family " cancel :status must be :cancelled, got " (:status reply)))
      (is (= :cancelled (:rf.reply/work-status reply))
          (str family " cancel :rf.reply/work-status must be :cancelled, got " (:rf.reply/work-status reply)))
      (is (true? (:cancelled? reply))
          (str family " cancel reply MUST carry the :cancelled? true marker"))
      (is (some? (:rf.reply/cancel-reason reply))
          (str family " cancel reply MUST carry a :rf.reply/cancel-reason")))))

;; ---------------------------------------------------------------------------
;; Stale shape across every implementation:
;;   :status :stale + :rf.reply/work-status :suppressed + :stale? true + :rf.reply/stale-reason
;;   and no :value, because a stale completion must not mutate app state.
;; ---------------------------------------------------------------------------

(deftest stale-shape-is-consistent-across-EVERY-family
  ;; Unlike the other situations, stale suppression is universal.
  (doseq [{:keys [family stale]} families]
    (is (some? stale)
        (str family " MUST lower a stale completion onto the shared envelope "
             "(stale suppression is the universal correctness boundary)")))
  (doseq [{:keys [family stale]} families
          :when stale
          :let [reply (stale)]]
    (testing (str family " stale → canonical :stale / :suppressed")
      (is (reply/valid-reply? reply)
          (str family " stale reply must validate: " (reply/validate-reply reply)))
      (is (= :stale (:status reply))
          (str family " stale :status must be :stale (NOT a bespoke shape), got "
               (:status reply)))
      (is (= :suppressed (:rf.reply/work-status reply))
          (str family " stale :rf.reply/work-status must be :suppressed, got " (:rf.reply/work-status reply)))
      (is (true? (:stale? reply))
          (str family " stale reply MUST carry the :stale? true marker"))
      (is (some? (:rf.reply/stale-reason reply))
          (str family " stale reply MUST carry a :rf.reply/stale-reason"))
      (is (not (contains? reply :value))
          (str family " stale reply MUST NOT carry a :value — a stale reply "
               "mutates NO app state")))))

(deftest stale-replies-carry-a-carried-and-current-correlation-gate
  ;; Delegating implementations expose carried/current facts on the suppress
  ;; outcome trace. Machine and timer replies carry their gates inline.
  (testing "machine spawn-stale carries the carried/current generation gate in :correlation"
    (let [reply (machine-stale-reply)
          corr  (:correlation reply)]
      (is (= 1 (-> corr :generation :carried)) "carried generation parsed off the actor id")
      (is (nil? (-> corr :generation :current)) "current generation gone (no live counterpart)")))
  (testing "machine :after-stale carries the carried/current path+epoch gate in :correlation"
    (let [reply (after-stale-reply)
          corr  (:correlation reply)]
      (is (= {:path [:loading] :rf/after-epoch 1} (:carried corr)) "carried path + scheduled epoch")
      (is (= {:path [:loading] :rf/after-epoch 2} (:current corr)) "current path + advanced epoch")))
  (testing "all suppress-delegating families carry carried/current trace facts"
    (doseq [{:keys [family stale-out]} families
            :when stale-out
            :let  [out (stale-out)]]
      (is (false? (:deliver? out))
          (str family " stale suppression MUST NOT deliver the app target"))
      (is (= :suppressed (:rf.reply/work-status out))
          (str family " stale suppression outcome is :rf.reply/work-status :suppressed"))
      (is (some? (get-in out [:trace :rf.reply/carried]))
          (str family " stale trace carries the :rf.reply/carried correlation"))
      (is (some? (get-in out [:trace :rf.reply/current]))
          (str family " stale trace carries the :rf.reply/current correlation")))))

;; ---------------------------------------------------------------------------
;; Negative fixture for a stale outcome that lacks carried/current trace facts.
;; ---------------------------------------------------------------------------

(defn- strip-correlation-trace
  "Remove the correlation facts while leaving the stale outcome otherwise intact."
  [out]
  (update out :trace dissoc :rf.reply/carried :rf.reply/current))

(deftest stale-correlation-gate-fails-closed-on-a-non-conforming-family
  (testing "a stale outcome can remain canonical apart from missing correlation facts"
    (doseq [[family out] [[:http     (strip-correlation-trace (http-stale-out))]
                          [:mutation (strip-correlation-trace (mutation-stale-out))]]]
      ;; The outcome still suppresses delivery and carries canonical statuses.
      (is (false? (:deliver? out))
          (str family " control outcome still suppresses delivery"))
      (is (= :suppressed (:rf.reply/work-status out))
          (str family " control outcome is still :rf.reply/work-status :suppressed"))
      (is (= :stale (get-in out [:reply :status]))
          (str family " control reply is still :status :stale"))
      ;; The missing facts are the only condition rejected by the correlation gate.
      (is (nil? (get-in out [:trace :rf.reply/carried]))
          (str family " control DROPPED :rf.reply/carried — the gate's "
               ":rf.reply/carried assertion would FAIL on this outcome"))
      (is (nil? (get-in out [:trace :rf.reply/current]))
          (str family " control DROPPED :rf.reply/current — the gate's "
               ":rf.reply/current assertion would FAIL on this outcome")))))

;; ---------------------------------------------------------------------------
;; Negative fixtures for dropped and nil-filled completion times.
;; ---------------------------------------------------------------------------

(defn- drop-completion-time
  "Remove `:completed-at` from an otherwise-canonical success reply."
  [reply]
  (dissoc reply :completed-at))

(defn- nil-fill-completion-time
  "Add a nil `:completed-at` sentinel to a no-time success reply."
  [reply]
  (assoc reply :completed-at nil))

(deftest completion-time-gate-fails-closed-on-a-non-conforming-family
  (testing "a success reply that DROPS :completed-at is detected — the
            propagation gate's `(= completion-time-ms (:completed-at reply))`
            and `(contains? reply :completed-at)` would go RED on it"
    (doseq [{:keys [family success]} families
            :let [bad (drop-completion-time (success))]]
      ;; The reply remains a valid :ok envelope because completion time is optional.
      (is (= :ok (:status bad))
          (str family " control reply is still :status :ok"))
      (is (reply/valid-reply? bad)
          (str family " control reply still validates: " (reply/validate-reply bad)))
      ;; The propagation comparison rejects the missing supplied fact.
      (is (not (contains? bad :completed-at))
          (str family " control DROPPED :completed-at — the propagation gate's "
               "`contains?` + value assertions would FAIL on this reply"))))
  (testing "a success reply that NIL-FILLS :completed-at is detected — the
            omit-when-absent gate's `(not (contains? reply :completed-at))`
            would go RED on a present-but-nil slot"
    (doseq [{:keys [family success-no-time]} families
            :let [bad (nil-fill-completion-time (success-no-time))]]
      (is (contains? bad :completed-at)
          (str family " control NIL-FILLED :completed-at (present-but-nil) — the "
               "omit-when-absent gate's `(not (contains? …))` assertion would "
               "FAIL on this reply, catching the nil-sentinel anti-pattern"))
      (is (nil? (:completed-at bad))
          (str family " control's :completed-at is the nil sentinel the gate "
               "forbids")))))
