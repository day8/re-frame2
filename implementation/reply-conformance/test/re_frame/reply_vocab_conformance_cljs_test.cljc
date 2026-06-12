(ns re-frame.reply-vocab-conformance-cljs-test
  "Cross-family reply-VOCABULARY-consistency conformance (rf2-wbh1ln,
  EP-0011 testing-rigour audit rf2-h8xw2v).

  EP-0011's whole point is ONE work/reply vocabulary across EVERY managed
  *async* family — the closed `:status` enum, the closed `:work/status`
  enum, the `:work/id` correlation tuple, the canonical `:status :stale`
  suppression shape — so tools read ONE stream and apps classify
  completion ONE way. Each family already validates its OWN reply slice
  against the shared `re-frame.reply/validate-reply` (the per-family
  `*-reply-lowering-*` suites), and the substrate test
  (`re-frame.reply-test`) pins the closed sets ONCE. But until this suite
  NO test compared ACROSS families to prove they produce the SAME shapes
  for the SAME situations.

  That gap is exactly what rf2-mn4j89 exploited: machines (`:after`),
  routing, and the timer-probe produced the canonical `:status :stale` on
  their stale trace while resources / mutations emitted a bespoke
  `:rf.resource/stale-suppressed` WITHOUT `:rf.reply/status :stale` — a
  cross-family INconsistency no per-family test could catch (each family
  only ever looked at itself). The stale-reply remediation has since
  landed across families (machines #3908 + resources #3911); this is the
  umbrella regression GUARD that turns the one-vocabulary EP claim into a
  table-driven gate so the divergence cannot silently return.

  ## What this suite is

  A table of the FIVE managed-async families (HTTP, resources, mutations,
  machines, routing), each as a descriptor naming the situation→reply
  builders it supports (`:success`, `:error`, `:cancel`, `:stale`). One
  set of cross-family assertions then proves EVERY family, for EVERY
  situation it supports, produces the canonical shared shape:

    (a) success    → `:status :ok`        + `:work/status :completed`
    (b) error      → `:status :error`     + `:work/status :failed`
                     (or `:timed-out`, the timeout work-status) + a family
                     `:error` MAP carrying a `:kind`
    (c) cancel     → `:status :cancelled` + `:work/status :cancelled`
                     + `:cancelled? true`
    (d) stale      → `:status :stale`     + `:work/status :suppressed`
                     + `:stale? true` + a carried/current correlation gate,
                     and NO `:value` (no app mutation)

  PLUS the invariants every reply shares regardless of situation: every
  reply VALIDATES against the single `re-frame.reply/validate-reply`; the
  `:status` is in the closed `re-frame.reply/statuses`; the `:work/status`
  (when present) is in the closed `re-frame.reply/work-statuses`; the
  `:work/id` is a `[:rf.work/* …]` tuple, `=`-comparable and EDN-round-
  trippable; and the reply is DATA-ONLY (no host handle anywhere).

  Pure-fn conformance over the per-family reply builders — no runtime
  fixture. The families sit ABOVE several artefacts (core, http,
  resources, machines, routing), so this lives in its own cross-artefact
  `reply-conformance/` surface (the precedent is `security/`), not any
  single family's test tree. Runs on the `npm run test:cljs` node gate
  (ns matches `cljs-test$`) AND the JVM gate
  (`implementation/reply-conformance/deps.edn` `:test`).

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as reply]
            [re-frame.http-reply :as http-reply]
            [re-frame.resources.reply :as rreply]
            [re-frame.machines.reply :as m-reply]
            [re-frame.routing.reply :as route-reply]))

;; ---------------------------------------------------------------------------
;; EDN round-trip — the work-id must be `=`-comparable and serializable. On
;; the JVM `read-string` parses EDN; on CLJS we round-trip through the EDN
;; reader. (`pr-str` of a `:rf.work/*` tuple is plain EDN — keywords, vectors,
;; maps, strings, numbers — so both arms reconstruct an `=`-equal value.)
;; ---------------------------------------------------------------------------

(defn- edn-roundtrip [x]
  #?(:clj  (read-string (pr-str x))
     :cljs (cljs.reader/read-string (pr-str x))))

;; ---------------------------------------------------------------------------
;; Shared fixtures threaded into the per-family builders. Each family's
;; verification-payload / context shape differs (that is the family's job);
;; this table normalizes the OUTPUT (the reply map) for cross-family
;; comparison.
;; ---------------------------------------------------------------------------

(def ^:private http-ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at 1781078400456})

(def ^:private resource-vp
  {:work/id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :resource-key [:rf.scope/global :article/by-slug {:slug "w"}]
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
   :completed-at      1781078400888})

;; A family error MAP carrying a :kind — the closed reply-map contract
;; demands every family error rides this shape (a loose scalar is rejected).
(def ^:private a-failure {:kind :rf.http/http-5xx :status 503 :body "down"})

;; An :rf.http/aborted failure envelope — the cancellation shape shared by
;; HTTP / resources / mutations (an intentional abort, NOT an :error).
(def ^:private an-abort {:kind :rf.http/aborted :reason :user})

;; ---------------------------------------------------------------------------
;; THE TABLE. Each family is a descriptor:
;;   :family        — the family keyword (diagnostics)
;;   :work-head     — the expected `[:rf.work/* …]` head keyword
;;   :success       — () → success reply map, or nil if N/A
;;   :error         — () → error reply map, or nil if N/A
;;   :error-work-status — the expected error :work/status (:failed default;
;;                    HTTP timeout family also proves :timed-out separately)
;;   :cancel        — () → cancelled reply map, or nil if N/A
;;   :stale         — () → stale reply map, or nil if N/A
;;
;; A nil situation means the family does not lower that situation onto a
;; reply (e.g. a machine spawn has no cancellation reply; the route loader's
;; success/error flow THROUGH HTTP, so the family-level builder here covers
;; only the situations the family itself shapes). The assertions skip nil
;; situations — a family is only held to the shapes it produces.
;; ---------------------------------------------------------------------------

(defn- machine-stale-reply []
  (m-reply/stale-spawn-reply (assoc machine-ctx :current-generation nil)))

(defn- after-stale-reply []
  (m-reply/after-stale-reply
    {:machine-id      :a/multi
     :state           :loading
     :delay           30000
     :decl-path       [:loading]
     :scheduled-epoch 1
     :current-epoch   2
     :frame           :app/main}))

(defn- after-fired-reply []
  (m-reply/after-fired-reply
    {:machine-id :a/multi
     :state      :loading
     :delay      30000
     :decl-path  [:loading]
     :epoch      1
     :frame      :app/main}))

(defn- route-stale-reply []
  (:reply (route-reply/suppress {:route-id  :route/article
                                 :nav-token "nav-1"
                                 :loader-id :article/loaded
                                 :frame     :app/main}
                                "nav-2")))

(defn- resource-stale-reply []
  (let [carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
        current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}]
    (:reply (rreply/stale-reply
              {:carried carried :current current
               :extra   {:work/id      (:work/id carried)
                         :work/kind    :resource
                         :rf.frame/id  :app/main
                         :stale/reason :resource/generation-mismatch}}))))

(def ^:private families
  [{:family    :http
    :work-head :rf.work/http
    :success   #(http-reply/success-reply http-ctx {:title "Welcome"})
    :error     #(http-reply/failure-reply http-ctx a-failure)
    :cancel    #(http-reply/aborted-reply http-ctx an-abort)
    ;; HTTP supersession is the stale path (a same-:request-id supersede)
    ;; lowered through the shared substrate `suppress`; pin the canonical
    ;; stale shape via the substrate directly with the HTTP work-id.
    :stale     #(:reply (reply/suppress nil
                                        {:work/id (http-reply/work-id http-ctx)}
                                        {:work/id [:rf.work/http :article/by-id 2]}
                                        {:work/id      (http-reply/work-id http-ctx)
                                         :work/kind    :http
                                         :rf.frame/id  :app/main
                                         :stale/reason :rf.http/superseded}))}

   {:family    :resource
    :work-head :rf.work/resource
    :success   #(rreply/success-reply resource-vp {:title "Welcome"}
                                      {:work-kind rreply/work-kind-resource
                                       :completed-at 1781078400456})
    :error     #(rreply/failure-reply resource-vp a-failure
                                      {:work-kind rreply/work-kind-resource})
    :cancel    #(rreply/failure-reply resource-vp an-abort
                                      {:work-kind rreply/work-kind-resource})
    :stale     resource-stale-reply}

   {:family    :mutation
    :work-head :rf.work/resource ;; mutation reuses the resource head with a [:rf.mutation …] key
    :success   #(rreply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                                      {:work-kind rreply/work-kind-mutation
                                       :completed-at 1781078400456})
    :error     #(rreply/failure-reply mutation-vp a-failure
                                      {:work-kind rreply/work-kind-mutation})
    :cancel    #(rreply/failure-reply mutation-vp an-abort
                                      {:work-kind rreply/work-kind-mutation})
    :stale     #(let [carried {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 2] :generation 2}
                      current {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 3] :generation 3}]
                  (:reply (rreply/stale-reply
                            {:carried carried :current current
                             :extra   {:work/id      (:work/id carried)
                                       :work/kind    :mutation
                                       :rf.frame/id  :app/main
                                       :stale/reason :mutation/superseded}})))}

   {:family    :machine
    :work-head :rf.work/machine
    :success   #(m-reply/success-reply machine-ctx {:user-id "u-42"})
    :error     #(m-reply/error-reply machine-ctx {:reason :bad-creds})
    ;; A machine spawn has no cancellation reply (the parent/child either
    ;; finishes or is superseded → stale); cancellation is N/A.
    :cancel    nil
    :stale     machine-stale-reply}

   ;; The machine :after timer is a specialized timer instance. rf2-niarhz —
   ;; both its FIRED (live) and STALE (epoch-mismatch) completions now carry
   ;; the canonical `:work/id` `[:rf.work/timer <decl-path> <epoch>]` so they
   ;; join the uniform work/reply rows.
   {:family    :timer
    :work-head :rf.work/timer
    :success   after-fired-reply
    :error     nil
    :cancel    nil
    :stale     after-stale-reply}

   ;; The route loader's success / error flow THROUGH the HTTP transport
   ;; (covered by the :http row); the family itself shapes ONLY the
   ;; nav-token stale suppression.
   {:family    :route
    :work-head :rf.work/route
    :success   nil
    :error     nil
    :cancel    nil
    :stale     route-stale-reply}])

;; ---------------------------------------------------------------------------
;; (0) Universal invariants — EVERY reply EVERY family produces, in EVERY
;; supported situation, conforms to the single shared contract.
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
      (testing ":status is in the ONE closed status vocabulary"
        (is (contains? reply/statuses (:status reply))
            (str family " " situation " :status " (:status reply)
                 " is not in the closed " reply/statuses)))
      (testing ":work/status (when present) is in the ONE closed work-status vocabulary"
        (when (contains? reply :work/status)
          (is (contains? reply/work-statuses (:work/status reply))
              (str family " " situation " :work/status " (:work/status reply)
                   " is not in the closed " reply/work-statuses))))
      (testing "the reply is DATA-ONLY (no host handle anywhere)"
        (is (not-any? #(= :rf.reply/host-handle (:rf.reply/problem %))
                      (reply/validate-reply reply))
            (str family " " situation " reply carries a host handle"))))))

(deftest every-work-id-is-a-comparable-edn-tuple
  (doseq [{:keys [family work-head] :as f} families
          [situation builder] (select-keys f [:success :error :cancel :stale])
          :when (and builder work-head)
          :let [reply (builder)
                wid   (:work/id reply)]
          ;; Defensive: skip any row that legitimately produces no :work/id
          ;; (none today — rf2-niarhz gave the machine :after timer a canonical
          ;; [:rf.work/timer …] work-id too).
          :when (some? wid)]
    (testing (str family " / " situation " :work/id correlation")
      (is (vector? wid) (str family " " situation " :work/id is not a vector"))
      (is (= work-head (first wid))
          (str family " " situation " :work/id head is " (first wid)
               ", expected " work-head))
      (is (= wid (edn-roundtrip wid))
          (str family " " situation " :work/id is not EDN-round-trippable / =-comparable")))))

;; ---------------------------------------------------------------------------
;; (a) SUCCESS — every family that produces a success reply produces the
;; SAME success shape: :status :ok + :work/status :completed + a :value.
;; ---------------------------------------------------------------------------

(deftest success-shape-is-consistent-across-families
  (doseq [{:keys [family success]} families
          :when success
          :let [reply (success)]]
    (testing (str family " success → canonical :ok / :completed")
      (is (= :ok (:status reply))
          (str family " success :status must be :ok, got " (:status reply)))
      (is (= :completed (:work/status reply))
          (str family " success :work/status must be :completed, got " (:work/status reply)))
      (is (contains? reply :value)
          (str family " success reply MUST carry a :value (the decoded result)"))
      (is (nil? (:error reply))
          (str family " success reply MUST NOT carry an :error")))))

;; ---------------------------------------------------------------------------
;; (b) ERROR — every family that produces an error reply produces the SAME
;; error shape: :status :error + a :work/status in #{:failed :timed-out} +
;; a family :error MAP carrying a :kind (never a loose scalar).
;; ---------------------------------------------------------------------------

(deftest error-shape-is-consistent-across-families
  (doseq [{:keys [family error]} families
          :when error
          :let [reply (error)]]
    (testing (str family " error → canonical :error + family-error map")
      (is (= :error (:status reply))
          (str family " error :status must be :error, got " (:status reply)))
      (is (contains? #{:failed :timed-out} (:work/status reply))
          (str family " error :work/status must be :failed (or :timed-out), got "
               (:work/status reply)))
      (is (map? (:error reply))
          (str family " error :error must be a family-error MAP (never a loose scalar)"))
      (is (some? (:kind (:error reply)))
          (str family " error :error map MUST carry a :kind")))))

(deftest http-timeout-is-error-plus-timed-out-work-status
  (testing "timeout is :status :error + :work/status :timed-out (NOT a top-level status) — the one family with a :timed-out work-status proves it lands in the closed enum"
    (let [reply (http-reply/failure-reply http-ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (reply/valid-reply? reply) (str (reply/validate-reply reply)))
      (is (= :error (:status reply)) "timeout is NOT a top-level :status")
      (is (= :timed-out (:work/status reply)))
      (is (contains? reply/work-statuses (:work/status reply))
          ":timed-out is in the ONE closed work-status vocabulary"))))

;; ---------------------------------------------------------------------------
;; (c) CANCEL — every family that produces a cancellation reply produces the
;; SAME shape: :status :cancelled + :work/status :cancelled + :cancelled? true
;; + a :cancel/reason. (An :rf.http/aborted lowers to cancellation, never an
;; :error, uniformly across HTTP / resources / mutations.)
;; ---------------------------------------------------------------------------

(deftest cancel-shape-is-consistent-across-families
  (doseq [{:keys [family cancel]} families
          :when cancel
          :let [reply (cancel)]]
    (testing (str family " cancel → canonical :cancelled")
      (is (= :cancelled (:status reply))
          (str family " cancel :status must be :cancelled, got " (:status reply)))
      (is (= :cancelled (:work/status reply))
          (str family " cancel :work/status must be :cancelled, got " (:work/status reply)))
      (is (true? (:cancelled? reply))
          (str family " cancel reply MUST carry the :cancelled? true marker"))
      (is (some? (:cancel/reason reply))
          (str family " cancel reply MUST carry a :cancel/reason")))))

;; ---------------------------------------------------------------------------
;; (d) STALE — THE axis rf2-mn4j89 violated. EVERY family that suppresses a
;; superseded completion produces the SAME canonical stale shape:
;;   :status :stale + :work/status :suppressed + :stale? true + :stale/reason
;;   + NO :value (a stale reply MUST NOT mutate app state).
;; This is the umbrella guard: had resources/mutations still emitted their
;; bespoke :rf.resource/stale-suppressed shape (no :status :stale, no
;; :work/status :suppressed), THIS test would go RED.
;; ---------------------------------------------------------------------------

(deftest stale-shape-is-consistent-across-EVERY-family
  ;; Every managed-async family suppresses (it is the safety boundary), so —
  ;; unlike success/error/cancel — there is NO family without a :stale path.
  ;; Assert that, then assert the canonical shape for each.
  (doseq [{:keys [family stale]} families]
    (is (some? stale)
        (str family " MUST lower a stale completion onto the shared envelope "
             "(stale suppression is the universal correctness boundary)")))
  (doseq [{:keys [family stale]} families
          :when stale
          :let [reply (stale)]]
    (testing (str family " stale → canonical :stale / :suppressed (the rf2-mn4j89 axis)")
      (is (reply/valid-reply? reply)
          (str family " stale reply must validate: " (reply/validate-reply reply)))
      (is (= :stale (:status reply))
          (str family " stale :status must be :stale (NOT a bespoke shape), got "
               (:status reply)))
      (is (= :suppressed (:work/status reply))
          (str family " stale :work/status must be :suppressed, got " (:work/status reply)))
      (is (true? (:stale? reply))
          (str family " stale reply MUST carry the :stale? true marker"))
      (is (some? (:stale/reason reply))
          (str family " stale reply MUST carry a :stale/reason"))
      (is (not (contains? reply :value))
          (str family " stale reply MUST NOT carry a :value — a stale reply "
               "mutates NO app state")))))

(deftest stale-replies-carry-a-carried-and-current-correlation-gate
  ;; The canonical stale shape carries the carried-vs-current correlation —
  ;; either on the suppress OUTCOME's :trace (HTTP / resources / mutations /
  ;; routing, which delegate to re-frame.reply/suppress) or in the reply's
  ;; :correlation map (machines spawn + :after, which build the gate inline).
  ;; Pin the correlation presence per family at the altitude each exposes it.
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
  (testing "the suppress-delegating families (HTTP / resources / mutations / routing) carry carried+current on the :trace"
    (let [route-out  (route-reply/suppress {:route-id :route/article :nav-token "nav-1"
                                            :loader-id :article/loaded :frame :app/main}
                                           "nav-2")
          res-out    (rreply/stale-reply
                       {:carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
                        :current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
                        :extra   {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4]
                                  :work/kind :resource :stale/reason :resource/generation-mismatch}})]
      (doseq [[family out] [[:route route-out] [:resource res-out]]]
        (is (false? (:deliver? out))
            (str family " stale suppression MUST NOT deliver the app target"))
        (is (= :suppressed (:work/status out))
            (str family " stale suppression outcome is :work/status :suppressed"))
        (is (some? (get-in out [:trace :rf.reply/carried]))
            (str family " stale trace carries the :rf.reply/carried correlation"))
        (is (some? (get-in out [:trace :rf.reply/current]))
            (str family " stale trace carries the :rf.reply/current correlation"))))))
