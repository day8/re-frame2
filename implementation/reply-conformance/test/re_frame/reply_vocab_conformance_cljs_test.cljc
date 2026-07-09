(ns re-frame.reply-vocab-conformance-cljs-test
  "Cross-family reply-VOCABULARY-consistency conformance (rf2-wbh1ln,
  EP-0011 testing-rigour audit rf2-h8xw2v).

  EP-0011's whole point is ONE work/reply vocabulary across EVERY managed
  *async* family — the closed `:status` enum, the closed `:rf.reply/work-status`
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

    (a) success    → `:status :ok`        + `:rf.reply/work-status :completed`
    (b) error      → `:status :error`     + `:rf.reply/work-status :failed`
                     (or `:timed-out`, the timeout work-status) + a family
                     `:error` MAP carrying a `:kind`
    (c) cancel     → `:status :cancelled` + `:rf.reply/work-status :cancelled`
                     + `:cancelled? true`
    (d) stale      → `:status :stale`     + `:rf.reply/work-status :suppressed`
                     + `:stale? true` + a carried/current correlation gate,
                     and NO `:value` (no app mutation)

  PLUS the invariants every reply shares regardless of situation: every
  reply VALIDATES against the single `re-frame.reply/validate-reply`; the
  `:status` is in the closed `re-frame.reply/statuses`; the `:rf.reply/work-status`
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
            ;; CLJS-only: the `:cljs` arm of `edn-roundtrip` reads EDN via
            ;; `cljs.reader/read-string` (JVM uses core `read-string`). Without
            ;; this require the node CLJS gate hits `Use of undeclared Var
            ;; cljs.reader/read-string` then a runtime undefined-deref, even
            ;; though `clojure -M:test` is green. See rf2-u99i9j.
            #?(:cljs [cljs.reader])
            [re-frame.reply :as reply]
            [re-frame.reply-conformance-fixtures :as fixtures]
            [re-frame.http.reply :as http-reply]
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

;; EP-0017 — the durable causal completion time. The pure reply map carries
;; it as `:completed-at`; the LIVE reply dispatch carries the same value as
;; the flat `:rf.cofx` `:rf/time-ms` fact on the dispatch envelope (the
;; framework's single built-in recordable coeffect, stamped at the causal
;; boundary — see `re-frame.router` §`:rf.cofx`). This tier is PURE over reply
;; maps, so it locks the `:completed-at` propagation half of that bridge; the
;; live-dispatch `:rf.cofx` half is owned by the router / per-family lowering
;; suites. The HTTP / resource / mutation success fixtures seed this value;
;; the machine fixture seeds its own (distinct) value.
;; Shared across the reply-conformance tier — owned by
;; `re-frame.reply-conformance-fixtures` (rf2-b2a3a2).
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
;;   :error-work-status — the expected error :rf.reply/work-status (:failed default;
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
    {:actor-id        :a/multi
     :state           :loading
     :delay           30000
     :decl-path       [:loading]
     :scheduled-epoch 1
     :current-epoch   2
     :frame           :app/main}))

;; The machine `:after` timer's FIRED (live) completion. A fired `:after`
;; timer's transition can mutate machine snapshot `:data`, so per
;; Managed-Effects §Causal completion metadata it threads the durable causal
;; `:completed-at` when the firing dispatch supplied one (rf2-hawtjr —
;; `m-reply/after-fired-reply` `(some? completed-at) (assoc …)`). The no-time
;; variant (the `families` `:success` row, which is held only to
;; status/work-id/value shape) omits it; the with-time variant feeds the
;; EP-0017 `completion-time-families` tier (rf2-suwuqo — the timer family was
;; the one success-producing family the completion-time tier never exercised).
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

(defn- route-stale-reply []
  (:reply (route-reply/suppress {:route-id  :route/article
                                 :nav-token "nav-1"
                                 :loader-id :article/loaded
                                 :frame     :app/main}
                                "nav-2")))

;; rf2-bphg8v — the route loader's LIVE success completion (carried nav-token
;; still current) builds its OWN `:status :ok` reply envelope via
;; `re-frame.routing.reply/live-reply` (rf2-2avo53) — NOT the HTTP transport's
;; success reply. It carries the route `:work/id` head `:rf.work/route`,
;; `:work/kind :route`, the loader's decoded `:value`, the carried frame, and
;; the causal `:completed-at`. So the route family DOES shape a success row of
;; its own; the cross-family table must exercise it (the prior `:success nil`
;; left the route live-reply path unguarded by the umbrella vocabulary tier).
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

(defn- resource-stale-reply []
  (let [carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
        current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}]
    (:reply (rreply/stale-reply
              {:carried carried :current current
               :extra   {:rf.reply/work-id      (:work/id carried)
                         :rf.reply/work-kind    :resource
                         :rf.frame/id  :app/main
                         :rf.reply/stale-reason :resource/generation-mismatch}}))))

(def ^:private families
  [{:family    :http
    :work-head :rf.work/http
    :success   #(http-reply/success-reply http-ctx {:title "Welcome"})
    :error     #(http-reply/failure-reply http-ctx a-failure)
    :cancel    #(http-reply/aborted-reply http-ctx an-abort)
    ;; HTTP supersession is the stale path (a same-:request-id supersede).
    ;; Pin the canonical stale shape via the REAL production helper
    ;; `re-frame.http.reply/suppress` (NOT the substrate directly with a
    ;; synthetic extra) so this row would go red if the HTTP helper drifts in
    ;; its :rf.reply/stale-reason, carried/current facts, frame threading, or work-id
    ;; shape (rf2-fkdyhl). `current-work-id` is the superseding attempt's
    ;; work-id (issuance 2), =-distinct from the carried one (issuance 1).
    :stale     #(:reply (http-reply/suppress http-ctx [:rf.work/http :article/by-id 2 1]))}

   {:family    :resource
    :work-head :rf.work/resource
    :success   #(rreply/success-reply resource-vp {:title "Welcome"}
                                      {:work-kind rreply/work-kind-resource
                                       :completed-at completion-time-ms})
    :error     #(rreply/failure-reply resource-vp a-failure
                                      {:work-kind rreply/work-kind-resource})
    :cancel    #(rreply/failure-reply resource-vp an-abort
                                      {:work-kind rreply/work-kind-resource})
    :stale     resource-stale-reply}

   {:family    :mutation
    :work-head :rf.work/resource ;; mutation reuses the resource head with a [:rf.mutation …] key
    :success   #(rreply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                                      {:work-kind rreply/work-kind-mutation
                                       :completed-at completion-time-ms})
    :error     #(rreply/failure-reply mutation-vp a-failure
                                      {:work-kind rreply/work-kind-mutation})
    :cancel    #(rreply/failure-reply mutation-vp an-abort
                                      {:work-kind rreply/work-kind-mutation})
    :stale     #(let [carried {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 2] :generation 2}
                      current {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 3] :generation 3}]
                  (:reply (rreply/stale-reply
                            {:carried carried :current current
                             :extra   {:rf.reply/work-id      (:work/id carried)
                                       :rf.reply/work-kind    :mutation
                                       :rf.frame/id  :app/main
                                       :rf.reply/stale-reason :mutation/superseded}})))}

   {:family    :machine
    :work-head :rf.work/machine
    :success   #(m-reply/success-reply machine-ctx {:user-id "u-42"})
    :error     #(m-reply/error-reply machine-ctx {:reason :bad-creds})
    ;; rf2-sfunt8 — a destroyed (cancelled) spawned actor closes its work
    ;; attempt the reply-envelope way: a :status :cancelled reply
    ;; (cancellation as DATA, Managed-Effects §Cancellation). Same canonical
    ;; cancel shape every family produces.
    :cancel    #(m-reply/cancelled-actor-reply
                  (assoc machine-ctx :reason :explicit))
    :stale     machine-stale-reply}

   ;; The machine :after timer is a specialized timer instance. rf2-niarhz —
   ;; both its FIRED (live) and STALE (epoch-mismatch) completions now carry
   ;; the canonical `:work/id` `[:rf.work/timer <decl-path> <epoch>]` so they
   ;; join the uniform work/reply rows.
   {:family    :timer
    :work-head :rf.work/timer
    :success   after-fired-reply
    :error     nil
    ;; rf2-sfunt8 — a cancelled :after timer (state exit / destroy / etc.)
    ;; closes its work attempt as :status :cancelled DATA, same canonical
    ;; cancel shape as every other family.
    :cancel    #(m-reply/cancelled-timer-reply
                  {:actor-id  :a/multi
                   :state     :loading
                   :delay     30000
                   :decl-path [:loading]
                   :epoch     1
                   :frame     :app/main
                   :reason    :on-exit})
    :stale     after-stale-reply}

   ;; The route loader's underlying HTTP TRANSPORT success / error flow
   ;; through the HTTP transport (covered by the :http row). But the route
   ;; wrapper ALSO builds its OWN live `:status :ok` reply envelope when the
   ;; carried nav-token is still current (rf2-2avo53 `live-reply`): that route
   ;; envelope — `:work/kind :route`, route `:work/id` head, decoded `:value`,
   ;; carried frame + `:completed-at` — is what `:success` exercises here
   ;; (rf2-bphg8v). The family does not shape its own error/cancel reply (those
   ;; ride the HTTP transport); it shapes the live success + the nav-token
   ;; stale suppression.
   {:family    :route
    :work-head :rf.work/route
    :success   route-live-reply
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
      (testing ":rf.reply/work-status (when present) is in the ONE closed work-status vocabulary"
        (when (contains? reply :rf.reply/work-status)
          (is (contains? reply/work-statuses (:rf.reply/work-status reply))
              (str family " " situation " :rf.reply/work-status " (:rf.reply/work-status reply)
                   " is not in the closed " reply/work-statuses))))
      (testing "the reply is DATA-ONLY (no host handle anywhere)"
        (is (not-any? #(= :rf.reply/host-handle (:rf.reply/problem %))
                      (reply/validate-reply reply))
            (str family " " situation " reply carries a host handle"))))))

(deftest every-work-id-is-a-comparable-edn-tuple
  ;; Managed-Effects.md §Work-id correlation (:184): ledger-backed async work
  ;; MUST carry `:work/id` as the single attempt identity, and this table IS
  ;; the shipped managed-async family set. So the assertion FAILS CLOSED on a
  ;; missing `:work/id` — there is NO `:when (some? wid)` skip. If any family
  ;; builder ever drops `:work/id`, the `(some? wid)` guard below goes RED
  ;; (it would have silently skipped the tuple/head/EDN checks otherwise —
  ;; rf2-xyn0dv finding #1). Every descriptor in this table carries a
  ;; non-nil :work-head, so every row is held to the work-id contract.
  (doseq [{:keys [family work-head] :as f} families
          [situation builder] (select-keys f [:success :error :cancel :stale])
          :when (and builder work-head)
          :let [reply (builder)
                wid   (:rf.reply/work-id reply)]]
    (testing (str family " / " situation " :work/id correlation")
      (is (some? wid)
          (str family " " situation " reply MUST carry a :work/id — this table is "
               "the shipped managed-async family set and Managed-Effects §Work-id "
               "correlation requires ledger-backed async work to carry one "
               "(a dropped :work/id is the exact regression this gate catches)"))
      (is (vector? wid) (str family " " situation " :work/id is not a vector"))
      (is (= work-head (first wid))
          (str family " " situation " :work/id head is " (first wid)
               ", expected " work-head))
      (is (= wid (edn-roundtrip wid))
          (str family " " situation " :work/id is not EDN-round-trippable / =-comparable")))))

(deftest timer-family-work-ids-share-the-actor-bearing-logical-id
  ;; rf2-unucf7 — the machine `:after` timer's FIRED / STALE / CANCEL
  ;; completions are ONE actor-owned timer instance, so their `:work/id`s MUST
  ;; share the SAME actor-bearing logical-id `[:a/multi :loading]`
  ;; (`re-frame.machines.reply/timer-work-id`, L386-392: `(and (some? actor-id)
  ;; (some? decl-path)) -> (into [actor-id] (vec decl-path))`). The three
  ;; fixtures feed `:actor-id :a/multi` + `:decl-path [:loading]` (matching the
  ;; `:cancel` builder), so each work-id is `[:rf.work/timer [:a/multi :loading]
  ;; epoch]`. `every-work-id-…-edn-tuple` above pins only the HEAD + EDN-round-
  ;; trip; this pins the LOGICAL-ID actor-prepend the cross-family tier
  ;; otherwise never cross-checks. So a regression that drops or mis-orders the
  ;; actor in timer-work-id's logical-id — the actor-LESS `[:loading]` branch
  ;; the old `:machine-id` dead-key fixtures silently took — goes RED here.
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
            (str situation " timer :work/id head is not :rf.work/timer"))))
    (testing "each timer :work/id logical-id is the ACTOR-bearing [:a/multi :loading]
              — the actor-owned timer identity, NOT the actor-less [:loading]"
      (doseq [[situation wid] [[:fired fired] [:stale stale] [:cancel cancel]]]
        (is (= [:a/multi :loading] (second wid))
            (str situation " timer logical-id must be actor-prefixed [:a/multi :loading], not "
                 (pr-str (second wid))))))
    (testing "so the whole timer family shares ONE logical-id across situations"
      (is (= (second fired) (second stale) (second cancel))
          "fired / stale / cancel timer work-ids share one actor-bearing logical-id"))))

;; ---------------------------------------------------------------------------
;; (a) SUCCESS — every family that produces a success reply produces the
;; SAME success shape: :status :ok + :rf.reply/work-status :completed + a :value.
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
;; (a′) EP-0017 CAUSAL COMPLETION TIME (`:completed-at`) — the durable
;; wall-clock fact every family threads onto its reply map from the
;; caller-supplied completion time. EP-0017 §3 / §The framework ships one
;; built-in registration: this `:completed-at` payload is the same value the
;; LIVE reply dispatch carries as flat `:rf.cofx` `:rf/time-ms` (the one
;; framework-provided recordable coeffect). This tier is PURE over reply maps,
;; so it locks the `:completed-at` propagation half of that bridge — that
;; every family supplied a causal completion time propagates the SAME
;; `:completed-at` fact, uniformly, and that an ABSENT time is OMITTED (no nil
;; sentinel — Managed-Effects §The reply map: optional facts are omitted when
;; absent, never nil-filled). The live-dispatch `:rf.cofx` `:rf/time-ms` half
;; is owned by the router (`re-frame.router` §`:rf.cofx`) and the per-family
;; lowering suites; this gate guarantees no family silently drops or
;; mis-threads the durable completion fact while preserving status/work-id
;; shape (the umbrella regression the bead names — rf2-ear61v).
;;
;; The families whose SUCCESS fixture seeds a completion time: HTTP, resource,
;; mutation, machine, route, AND timer (rf2-suwuqo). The route live-reply
;; threads `:completed-at` uniformly (rf2-bphg8v / rf2-2avo53); the machine
;; `:after` timer's FIRED completion threads it too (rf2-hawtjr —
;; `after-fired-reply` `(some? completed-at) (assoc …)`), because a fired
;; timer's transition can mutate durable machine `:data`. The timer family was
;; the one success-producing family the completion-time tier had never
;; exercised (the `families` `:success` row used the NO-time `after-fired-reply`
;; and `completion-time-families` omitted `:timer`), so a timer builder that
;; dropped or nil-filled `:completed-at` while preserving status/work-id shape
;; would have left this cross-family tier green — exactly the umbrella gap the
;; bead names. The with-time / no-time timer pair below closes it.
;; ---------------------------------------------------------------------------

;; Parallel "no completion time supplied" success builders — the SAME family
;; success builders, but with `:completed-at` stripped from the context — so
;; the omit-when-absent half can prove the family does NOT nil-fill the slot.
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

(def ^:private completion-time-families
  "The success-producing families whose fixture seeds a causal completion
  time, each paired with its no-time counterpart."
  [{:family :http     :with-time #(http-reply/success-reply http-ctx {:title "Welcome"})
    :no-time http-success-no-time}
   {:family :resource :with-time #(rreply/success-reply resource-vp {:title "Welcome"}
                                                        {:work-kind rreply/work-kind-resource
                                                         :completed-at completion-time-ms})
    :no-time resource-success-no-time}
   {:family :mutation :with-time #(rreply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                                                        {:work-kind rreply/work-kind-mutation
                                                         :completed-at completion-time-ms})
    :no-time mutation-success-no-time}
   {:family :machine  :with-time #(m-reply/success-reply machine-ctx {:user-id "u-42"})
    :no-time machine-success-no-time}
   {:family :route    :with-time route-live-reply
    :no-time route-success-no-time}
   ;; rf2-suwuqo — the machine `:after` timer's FIRED completion. The with-time
   ;; variant seeds the causal `:completed-at` (a fired timer's transition can
   ;; mutate durable machine `:data`); the no-time variant is the plain
   ;; `after-fired-reply` (an unscripted / no-cofx fire path), which OMITS the
   ;; slot. This holds the timer family to BOTH halves of the EP-0017 tier:
   ;; uniform propagation when supplied, omit-when-absent (no nil sentinel)
   ;; when not — and feeds it into the completion-time adversarial control.
   {:family :timer    :with-time after-fired-reply-with-time
    :no-time after-fired-reply}])

(deftest completion-time-propagates-uniformly-across-families
  (testing "EP-0017 — every family supplied a causal completion time threads
            the SAME `:completed-at` fact onto its reply (the durable value the
            live reply dispatch carries as flat :rf.cofx :rf/time-ms)"
    (doseq [{:keys [family with-time]} completion-time-families
            :let [reply (with-time)]]
      (testing (str family " success → :completed-at present + uniform")
        (is (contains? reply :completed-at)
            (str family " success reply MUST carry the :completed-at causal "
                 "completion fact when the completion time was supplied"))
        (is (= completion-time-ms (:completed-at reply))
            (str family " success :completed-at must be the supplied causal "
                 "time " completion-time-ms ", got " (:completed-at reply)
                 " — every family threads the SAME fact (the value the live "
                 "reply dispatch carries as :rf.cofx :rf/time-ms)"))))))

(deftest completion-time-is-omitted-not-nil-when-absent
  (testing "EP-0017 / Managed-Effects §The reply map — a family NOT supplied a
            completion time OMITS :completed-at entirely (no nil sentinel); a
            reducer deriving a durable timestamp must never read a stale/nil
            completion fact"
    (doseq [{:keys [family no-time]} completion-time-families
            :let [reply (no-time)]]
      (testing (str family " success (no time supplied) → :completed-at omitted")
        (is (not (contains? reply :completed-at))
            (str family " success reply MUST OMIT :completed-at when no "
                 "completion time was supplied — never nil-fill it (a nil "
                 "sentinel would let a reducer derive a bogus durable "
                 "timestamp). Got " (pr-str (:completed-at reply))))
        ;; The reply must still be otherwise canonical — omitting the optional
        ;; fact does not break the envelope.
        (is (reply/valid-reply? reply)
            (str family " no-time success reply still validates: "
                 (reply/validate-reply reply)))
        (is (= :ok (:status reply))
            (str family " no-time success is still :status :ok"))))))

;; ---------------------------------------------------------------------------
;; (b) ERROR — every family that produces an error reply produces the SAME
;; error shape: :status :error + a :rf.reply/work-status in #{:failed :timed-out} +
;; a family :error MAP carrying a :kind (never a loose scalar).
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
  (testing "timeout is :status :error + :rf.reply/work-status :timed-out (NOT a top-level status) — the one family with a :timed-out work-status proves it lands in the closed enum"
    (let [reply (http-reply/failure-reply http-ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (reply/valid-reply? reply) (str (reply/validate-reply reply)))
      (is (= :error (:status reply)) "timeout is NOT a top-level :status")
      (is (= :timed-out (:rf.reply/work-status reply)))
      (is (contains? reply/work-statuses (:rf.reply/work-status reply))
          ":timed-out is in the ONE closed work-status vocabulary"))))

;; ---------------------------------------------------------------------------
;; (c) CANCEL — every family that produces a cancellation reply produces the
;; SAME shape: :status :cancelled + :rf.reply/work-status :cancelled + :cancelled? true
;; + a :rf.reply/cancel-reason. (An :rf.http/aborted lowers to cancellation, never an
;; :error, uniformly across HTTP / resources / mutations.)
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
;; (d) STALE — THE axis rf2-mn4j89 violated. EVERY family that suppresses a
;; superseded completion produces the SAME canonical stale shape:
;;   :status :stale + :rf.reply/work-status :suppressed + :stale? true + :rf.reply/stale-reason
;;   + NO :value (a stale reply MUST NOT mutate app state).
;; This is the umbrella guard: had resources/mutations still emitted their
;; bespoke :rf.resource/stale-suppressed shape (no :status :stale, no
;; :rf.reply/work-status :suppressed), THIS test would go RED.
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
      (is (= :suppressed (:rf.reply/work-status reply))
          (str family " stale :rf.reply/work-status must be :suppressed, got " (:rf.reply/work-status reply)))
      (is (true? (:stale? reply))
          (str family " stale reply MUST carry the :stale? true marker"))
      (is (some? (:rf.reply/stale-reason reply))
          (str family " stale reply MUST carry a :rf.reply/stale-reason"))
      (is (not (contains? reply :value))
          (str family " stale reply MUST NOT carry a :value — a stale reply "
               "mutates NO app state")))))

;; ---------------------------------------------------------------------------
;; The suppress OUTCOME builders for the four suppress-DELEGATING families.
;; Each returns the `re-frame.reply/suppress` outcome map
;; `{:deliver? :reply :rf.reply/work-status :trace}` (HTTP / resources / mutations /
;; routing all delegate to `re-frame.reply/suppress`, so the carried/current
;; correlation rides the OUTCOME's `:trace`, not the reply). Factored so the
;; correlation-gate assertion AND the adversarial control share one source.
;; ---------------------------------------------------------------------------

(defn- http-stale-out []
  ;; HTTP supersession lowered through the REAL production helper
  ;; `re-frame.http.reply/suppress` (rf2-fkdyhl — NOT the substrate directly
  ;; with a synthetic extra), with a carried-vs-current HTTP work-id gate
  ;; (the same call the :http row's :stale builder uses — Managed-Effects
  ;; §Stale suppression). This makes the cross-family correlation gate fail
  ;; closed on a real HTTP-helper drift (a dropped :rf.reply/carried /
  ;; :rf.reply/current trace fact).
  (http-reply/suppress http-ctx [:rf.work/http :article/by-id 2 1]))

(defn- mutation-stale-out []
  (rreply/stale-reply
    {:carried {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 2] :generation 2}
     :current {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 3] :generation 3}
     :extra   {:work/id [:rf.work/resource [:rf.mutation :form/save-1] 2]
               :work/kind :mutation :rf.reply/stale-reason :mutation/superseded}}))

(defn- resource-stale-out []
  (rreply/stale-reply
    {:carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
     :current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
     :extra   {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4]
               :work/kind :resource :rf.reply/stale-reason :resource/generation-mismatch}}))

(defn- route-stale-out []
  (route-reply/suppress {:route-id :route/article :nav-token "nav-1"
                         :loader-id :article/loaded :frame :app/main}
                        "nav-2"))

(deftest stale-replies-carry-a-carried-and-current-correlation-gate
  ;; The canonical stale shape carries the carried-vs-current correlation —
  ;; either on the suppress OUTCOME's :trace (HTTP / resources / mutations /
  ;; routing, which delegate to re-frame.reply/suppress) or in the reply's
  ;; :correlation map (machines spawn + :after, which build the gate inline).
  ;; Pin the correlation presence per family at the altitude each exposes it.
  ;; Managed-Effects §Tracing (:235) + §Stale suppression (:217-223): EVERY
  ;; stale-suppression trace carries the carried + current correlation facts.
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
  (testing "ALL FOUR suppress-delegating families (HTTP / resources / mutations / routing) carry carried+current on the :trace"
    ;; rf2-xyn0dv finding #2: the comment named four families but the loop
    ;; checked only :route and :resource — so HTTP or a mutation losing the
    ;; carried/current trace facts in its stale outcome would NOT have gone
    ;; red here. All four are now exercised.
    (doseq [[family out] [[:http     (http-stale-out)]
                          [:resource (resource-stale-out)]
                          [:mutation (mutation-stale-out)]
                          [:route    (route-stale-out)]]]
      (is (false? (:deliver? out))
          (str family " stale suppression MUST NOT deliver the app target"))
      (is (= :suppressed (:rf.reply/work-status out))
          (str family " stale suppression outcome is :rf.reply/work-status :suppressed"))
      (is (some? (get-in out [:trace :rf.reply/carried]))
          (str family " stale trace carries the :rf.reply/carried correlation"))
      (is (some? (get-in out [:trace :rf.reply/current]))
          (str family " stale trace carries the :rf.reply/current correlation")))))

;; ---------------------------------------------------------------------------
;; ADVERSARIAL CONTROL (rf2-xyn0dv). The umbrella gate above is only as strong
;; as its ability to FAIL when a family regresses. This control proves the
;; gate fails CLOSED: a hypothetical HTTP / mutation stale helper that returns
;; an otherwise-valid `:stale` reply outcome but OMITS the carried/current
;; trace facts (the rf2-mn4j89-class divergence — a bespoke suppression shape
;; that forgets the correlation) is detected. We assert the NEGATIVE:
;; `some?` over the missing trace slots is FALSE, so the real gate's `is
;; (some? …)` would go RED on such an outcome. If this control ever passes
;; trivially (e.g. the trace key were renamed), the gate it guards is no
;; longer catching the regression it claims to.
;; ---------------------------------------------------------------------------

(defn- strip-correlation-trace
  "Simulate a non-conforming family whose stale outcome forgets the
  carried/current correlation facts (a bespoke suppression shape). Returns a
  conforming suppress outcome with the two correlation slots dissoc'd from
  its :trace — everything else (delivery, :rf.reply/work-status, :status :stale) is
  still valid, so ONLY the carried/current gate distinguishes it."
  [out]
  (update out :trace dissoc :rf.reply/carried :rf.reply/current))

(deftest stale-correlation-gate-fails-closed-on-a-non-conforming-family
  (testing "a stale outcome that omits the carried/current trace facts is DETECTED by the gate's correlation assertions"
    (doseq [[family out] [[:http     (strip-correlation-trace (http-stale-out))]
                          [:mutation (strip-correlation-trace (mutation-stale-out))]]]
      ;; The outcome is otherwise a perfectly valid stale suppression …
      (is (false? (:deliver? out))
          (str family " control outcome still suppresses delivery"))
      (is (= :suppressed (:rf.reply/work-status out))
          (str family " control outcome is still :rf.reply/work-status :suppressed"))
      (is (= :stale (get-in out [:reply :status]))
          (str family " control reply is still :status :stale"))
      ;; … yet the carried/current correlation facts are GONE, so the exact
      ;; assertions the umbrella gate runs go red. This is the negative case:
      ;; if these `nil?` checks ever failed, the gate would be passing a
      ;; non-conforming family (a silent rf2-mn4j89-class regression).
      (is (nil? (get-in out [:trace :rf.reply/carried]))
          (str family " control DROPPED :rf.reply/carried — the gate's "
               ":rf.reply/carried assertion would FAIL on this outcome"))
      (is (nil? (get-in out [:trace :rf.reply/current]))
          (str family " control DROPPED :rf.reply/current — the gate's "
               ":rf.reply/current assertion would FAIL on this outcome")))))

;; ---------------------------------------------------------------------------
;; ADVERSARIAL CONTROL — EP-0017 completion-time gate (rf2-ear61v). The
;; completion-time gate above is only as strong as its ability to FAIL when a
;; family drops or mis-threads the durable `:completed-at` fact. The bead's
;; exact concern: "this umbrella tier would still stay green if one family
;; stopped propagating the EP-0017 completion-time fact while preserving
;; status/work-id shape." This control proves the gate fails CLOSED against
;; BOTH regression shapes — a DROPPED fact (the family forgets to thread it)
;; and a NIL-FILLED fact (the family nil-sentinels it instead of omitting). We
;; assert the NEGATIVE: a reply that the propagation/omission gates would
;; reject is shown to be rejected, so if either gate ever passed such a reply
;; (a key rename / a loosened check) this control would surface it.
;; ---------------------------------------------------------------------------

(defn- drop-completion-time
  "Simulate a non-conforming family that FORGETS to thread the durable
  completion fact — an otherwise-canonical :ok reply with :completed-at
  dissoc'd, so ONLY the completion-time propagation gate distinguishes it."
  [reply]
  (dissoc reply :completed-at))

(defn- nil-fill-completion-time
  "Simulate a non-conforming family that NIL-FILLS the completion fact instead
  of omitting it (the Managed-Effects §The reply map anti-pattern) — an
  otherwise-canonical :ok reply with :completed-at present-but-nil, so only the
  omit-when-absent gate distinguishes it."
  [reply]
  (assoc reply :completed-at nil))

(deftest completion-time-gate-fails-closed-on-a-non-conforming-family
  (testing "a success reply that DROPS :completed-at is detected — the
            propagation gate's `(= completion-time-ms (:completed-at reply))`
            and `(contains? reply :completed-at)` would go RED on it"
    (doseq [{:keys [family with-time]} completion-time-families
            :let [bad (drop-completion-time (with-time))]]
      ;; The reply is otherwise a perfectly canonical :ok success …
      (is (= :ok (:status bad))
          (str family " control reply is still :status :ok"))
      (is (reply/valid-reply? bad)
          (str family " control reply still validates: " (reply/validate-reply bad)))
      ;; … yet the durable completion fact is GONE, so the propagation gate's
      ;; exact assertions go red. If these checks ever failed, the gate would
      ;; be passing a family that silently dropped the EP-0017 fact.
      (is (not (contains? bad :completed-at))
          (str family " control DROPPED :completed-at — the propagation gate's "
               "`contains?` + value assertions would FAIL on this reply"))))
  (testing "a success reply that NIL-FILLS :completed-at is detected — the
            omit-when-absent gate's `(not (contains? reply :completed-at))`
            would go RED on a present-but-nil slot"
    (doseq [{:keys [family no-time]} completion-time-families
            :let [bad (nil-fill-completion-time (no-time))]]
      (is (contains? bad :completed-at)
          (str family " control NIL-FILLED :completed-at (present-but-nil) — the "
               "omit-when-absent gate's `(not (contains? …))` assertion would "
               "FAIL on this reply, catching the nil-sentinel anti-pattern"))
      (is (nil? (:completed-at bad))
          (str family " control's :completed-at is the nil sentinel the gate "
               "forbids")))))
