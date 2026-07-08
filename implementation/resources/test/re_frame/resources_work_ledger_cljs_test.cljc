(ns re-frame.resources-work-ledger-cljs-test
  "Work-ledger substrate behaviour for the Resources artefact (rf2-afpdkn,
  Spec 016 §EP-0003 slice 3 — the resource-owned frame WORK LEDGER).

  These JVM+CLJS unit tests pin the work-ledger substrate this slice
  implements:

    1. a serializable `[:rf.runtime/work-ledger]` record is written on each
       load-causing attempt, keyed by work id, carrying NO host handles
       (serializable EDN for SSR / Xray);
    2. the resource entry points at its current work (`:current-work` =
       the record's `:work/id`);
    3. host handles live in a side table keyed by `[frame-id work-id]`
       (host-side, NOT serialized — mirrors the generation allocator);
    4. owner release updates ledger rows; abort is opportunistic (a
       best-effort `:rf.http/managed-abort` fx, never relied on);
    5. stale suppression by work-id + generation is mandatory (a late reply
       for a superseded work id never overwrites + settles the old row
       terminal :suppressed);
    6. terminal rows are pruned on the linked entry's next successful
       transition (bounded per-key tail kept for Xray);
    7. frame destroy cleans the side tables (durable records may persist;
       transient host handles are dropped);
    8. dedupe joins the existing record (owner attached, cause appended, no
       new generation / record)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   [re-frame.reply :as reply]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + the work-ledger side-table fx these tests
   ;; dispatch through.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch + abort are overridden by capturing no-ops below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport + abort (decouples ledger tests from HTTP) -------

(def ^:private last-managed-args (atom nil))
(def ^:private aborts (atom []))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed + :rf.http/managed-abort fxs with
  capturing no-ops so the ledger writes are deterministic and no real fetch
  / abort fires. Composed INSIDE the reset-runtime fixture.

  rf2-784223: the shared `make-reset-runtime-fixture`'s
  `:resources/reset-resources!` post-dispose hook already clears the resource
  state + work-ledger host caches before this fixture runs — no per-suite
  reset is repeated here."
  [f]
  (reset! last-managed-args nil)
  (reset! aborts [])
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  ;; rf2-sxyrzk — managed-abort args is the frame-QUALIFIED transport
  ;; request-id (`[:rf.req <frame-id> <work-id>]`, `managed-request-id`), NOT
  ;; the bare work-id. The managed-HTTP in-flight registry keys by request-id
  ;; PROCESS-GLOBALLY (Spec 014), so the abort must carry the same qualified
  ;; token the lower registered or it would miss the request (or, across
  ;; frames, resolve a sibling frame's colliding request).
  (rf/reg-fx :rf.http/managed-abort (fn [_ctx request-id] (swap! aborts conj request-id) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- record
  "The serializable work record under a work-id in a frame."
  ([work-id] (record :rf/default work-id))
  ([frame-id work-id]
   (work-ledger/get-record (runtime-db frame-id) work-id)))

(defn- ledger
  ([] (ledger :rf/default))
  ([frame-id] (get-in (runtime-db frame-id) [:rf.runtime/work-ledger])))

(defn- req
  "The frame-QUALIFIED managed-HTTP transport request-id the runtime aborts
  by (`managed-request-id`, rf2-sxyrzk) — the token captured into `@aborts`
  for a `work-id` issued in `frame-id` (default `:rf/default`). The abort
  carries this, NOT the bare work-id, so it matches the registered token."
  ([work-id] (req :rf/default work-id))
  ([frame-id work-id] (work-ledger/managed-request-id frame-id work-id)))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

;; ===========================================================================
;; 1. ensure writes a serializable work record keyed by work id
;; ===========================================================================

(deftest ensure-writes-work-record
  (rf/reg-resource :wl/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :wl/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :wl/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]
                        :cause [:route-entry :route/article 1]}])
    (let [e   (entry scoped-key)
          wid (:current-work e)
          r   (record wid)]
      (testing "the entry points at its current work id"
        (is (some? wid))
        (is (= [:rf.work/resource scoped-key 1] wid)))
      (testing "a serializable work record exists, keyed by work id (Spec 016
                §Frame work ledger)"
        (is (some? r))
        (is (= wid (:work/id r)))
        (is (= :resource (:work/kind r)))
        (is (= :rf/default (:work/frame r)))
        (is (= scoped-key (:resource/key r)))
        (is (= 1 (:generation r)))
        (is (= :running (:status r)))
        (is (= #{[:route :r 1]} (:owners r)))
        (is (= [[:route-entry :route/article 1]] (:causes r)))
        (is (number? (:started-at r))))
      (testing "the work record carries NO host handles (serializable EDN
                for SSR / Xray)"
        (is (work-ledger/serializable-record? r))
        (is (not (contains? r :abort-controller)))
        (is (not (contains? r :promise)))
        (is (not (contains? r :abort-fn)))))))

(deftest work-record-byte-keyed-address-is-canonical
  ;; rf2-hgy5kf (EP-0012) — the work record lives at ONE address: the
  ;; byte-keyed `work-ledger/record-path` (`[:rf.runtime/work-ledger
  ;; (work-id-id work-id)]`). The stale `[work-ledger-key work-id]` VECTOR
  ;; address (the removed `state/work-record-path` shape) holds NOTHING — a
  ;; test reading through it would silently miss the live row, making any
  ;; `(when rec …)` assertion vacuous. This pins the one home so the drift
  ;; cannot reappear.
  (rf/reg-resource :wlbk/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :wlbk/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :wlbk/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]}])
    (let [wid (:current-work (entry scoped-key))
          rdb (runtime-db)]
      (testing "the byte-keyed work-ledger API reads the live row"
        (is (some? (work-ledger/get-record rdb wid)))
        (is (= :running (:status (work-ledger/get-record rdb wid)))))
      (testing "the row is stored under the CEDN-1 byte work-id-id, NOT the
                work-id vector"
        (is (contains? (:rf.runtime/work-ledger rdb) (work-ledger/work-id-id wid)))
        (is (not (contains? (:rf.runtime/work-ledger rdb) wid)))
        (is (string? (work-ledger/work-id-id wid))))
      (testing "the removed stale [work-ledger-key work-id] vector address
                holds nothing (the dead shape EP-0012 eliminates)"
        (is (nil? (get-in rdb [state/work-ledger-key wid])))))))

(deftest work-record-started-at-deadline-at-from-token-time-ms
  ;; rf2-uuzj88 / EP-0010 §Resources, Mutations, And Work-Ledger Timestamps:
  ;; the durable work-ledger `:started-at` is the TRIGGERING TOKEN'S
  ;; `:time-ms` (the causal world input), and `:deadline-at` is
  ;; `:started-at` + the configured `:timeout-ms` policy — NOT an ambient
  ;; clock read in the reducer. Scripting the dispatch's `:rf.cofx`
  ;; pins both; the same token mints the same row (replay-stable).
  (rf/reg-resource :wlt/article (article-spec {:timeout-ms 5000}) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :wlt/article {:slug "w"})
        t1 1781078400123]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :wlt/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]}]
                      {:rf.cofx {:rf/time-ms t1}})
    (let [r (record (:current-work (entry scoped-key)))]
      (testing ":started-at is EXACTLY the triggering token :time-ms (not now)"
        (is (= t1 (:started-at r))))
      (testing ":deadline-at is :started-at + the :timeout-ms policy"
        (is (= (+ t1 5000) (:deadline-at r))))))
  ;; a resource declaring NO timeout policy has a nil :deadline-at, and its
  ;; :started-at still tracks the token (replay-stable, no ambient read).
  (rf/reg-resource :wlnt/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :wlnt/article {:slug "w"})
        t2 1781079000000]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :wlnt/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]}]
                      {:rf.cofx {:rf/time-ms t2}})
    (let [r (record (:current-work (entry scoped-key)))]
      (testing ":started-at tracks the token even with no timeout policy"
        (is (= t2 (:started-at r))))
      (testing "no :timeout-ms policy => nil :deadline-at"
        (is (nil? (:deadline-at r)))))))

;; ===========================================================================
;; 2. host handles live in the side table keyed by [frame-id work-id]
;; ===========================================================================

(deftest host-handle-in-side-table-not-runtime-db
  (rf/reg-resource :hh/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :hh/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :hh/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:lease :hh 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (testing "a host-side side-table slot exists for [frame-id work-id]
                (host-side, NOT runtime-db — Spec 016 §Frame work ledger)"
        (is (some? (work-ledger/get-handle :rf/default wid)))
        (is (= :rf.http/managed (:transport (work-ledger/get-handle :rf/default wid)))))
      (testing "the side table is NOT inside runtime-db (no host handles ride
                the durable wire)"
        (is (nil? (get-in (runtime-db) [:rf.runtime/work-ledger :handles])))
        ;; the record in runtime-db is host-handle-free
        (is (work-ledger/serializable-record? (record wid)))))))

;; ===========================================================================
;; 3. succeeded settles the record :completed + prunes terminal rows
;; ===========================================================================

(deftest succeeded-completes-and-prunes-record
  (rf/reg-resource :sc/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sc/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sc/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :sc 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id wid :generation 1
                          :data {:title "W"}}])
      (testing "Spec 016 §Ledger row retention — a terminal :completed row is
                pruned on the linked entry's successful transition (bounded
                per-key tail kept)"
        ;; with a single attempt + tail of 3, the row is retained as the tail
        (is (or (nil? (record wid))
                (= :completed (:status (record wid))))))
      (testing "the host handle for the settled attempt is cleared"
        (is (nil? (work-ledger/get-handle :rf/default wid)))))))

(deftest succeeded-prunes-old-terminal-rows-beyond-tail
  (rf/reg-resource :pr/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :pr/article {:slug "w"})]
    ;; run several attempts so terminal rows accumulate, then assert the
    ;; ledger is bounded (default tail = 3 terminal rows per key)
    (dotimes [_ 6]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :pr/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid
                            :generation (:generation (entry scoped-key))
                            :data {:n (rand)}}])))
    (testing "Spec 016 §Ledger row retention and identity — terminal rows are
              bounded (a small per-key tail), not unbounded growth"
      (let [terminal-rows (->> (vals (ledger))
                               (filter (fn [r] (and (= scoped-key (:resource/key r))
                                                    (work-ledger/terminal? (:status r))))))]
        (is (<= (count terminal-rows) work-ledger/default-terminal-tail)
            "terminal rows for the key are pruned to the bounded tail")))))

;; ===========================================================================
;; 4. failed / aborted settle the record terminal + clear the handle
;; ===========================================================================

(deftest failed-settles-record-terminal
  (rf/reg-resource :fa/article (article-spec) article-spec-request)
  (let [scoped-key   (state/scoped-resource-key :rf.scope/global :fa/article {:slug "w"})
        completed-at  1781649764112]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fa/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fa 1]}])
    (let [wid (:current-work (entry scoped-key))]
      ;; rf2-rl27r2: a failure reply is a managed-async completion with a reply
      ;; token, so it carries causal completion time — script the reply token's
      ;; `:rf.cofx` `:rf/time-ms` (delivered flat as the declared `:rf/time-ms`
      ;; cofx) and assert it is preserved on both the canonical reply and the
      ;; terminal work-ledger outcome (it was previously dropped).
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource/key scoped-key :work/id wid :generation 1
                          :error {:kind :rf.http/http-5xx :status 503}}]
                        {:rf.cofx {:rf/time-ms completed-at}})
      (testing "a failed first load settles the work row terminal :failed with
                the error envelope AND the causal :completed-at as its outcome
                (Xray summary; rf2-rl27r2)"
        (is (= :failed (:status (record wid))))
        (is (= {:error {:kind :rf.http/http-5xx :status 503}
                :completed-at completed-at}
               (:outcome (record wid)))))
      (testing "the host handle is cleared"
        (is (nil? (work-ledger/get-handle :rf/default wid)))))))

(deftest aborted-settles-record-cancelled
  (rf/reg-resource :ab/article (article-spec) article-spec-request)
  (let [scoped-key   (state/scoped-resource-key :rf.scope/global :ab/article {:slug "w"})
        completed-at  1781649764112]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ab/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ab 1]}])
    (let [wid (:current-work (entry scoped-key))]
      ;; rf2-rl27r2: a cancellation is a completion — script the reply token's
      ;; causal `:rf/time-ms` and assert the terminal :cancelled outcome carries
      ;; the same :completed-at.
      (rf/dispatch-sync [:rf.resource.internal/aborted
                         {:resource/key scoped-key :work/id wid :generation 1}]
                        {:rf.cofx {:rf/time-ms completed-at}})
      (testing "an aborted attempt settles the work row terminal :cancelled
                (carrying the causal :completed-at, rf2-rl27r2) + clears the
                handle (entry untouched — the verification gate handles its
                settle)"
        (is (= :cancelled (:status (record wid))))
        (is (= {:reason :aborted :completed-at completed-at}
               (:outcome (record wid))))
        (is (nil? (work-ledger/get-handle :rf/default wid)))))))

(deftest stale-aborted-event-suppressed-not-cancelled
  ;; rf2-iu0z8t (EP-0011): the legacy `:rf.resource.internal/aborted` event
  ;; must honour the SAME stale-suppression boundary as succeeded/failed —
  ;; a STALE / superseded aborted event (its carried work-id + generation no
  ;; longer correlate with the live entry) settles the row :suppressed, NOT
  ;; an accepted :cancelled. Stale validation wins over the natural
  ;; cancellation status (Managed-Effects §Stale suppression).
  (rf/reg-resource :sa/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sa/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sa/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :sa 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      ;; a newer refetch supersedes (generation 2) — the gen-1 work row settles
      ;; :suppressed (superseded) and the entry advances to generation 2.
      (rf/dispatch-sync [:rf.resource/refetch {:resource :sa/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (testing "the OLD-generation aborted event NEVER overrides the :suppressed
                row with an accepted :cancelled (stale wins over cancellation)"
        (rf/dispatch-sync [:rf.resource.internal/aborted
                           {:resource/key scoped-key :work/id wid1 :generation 1}])
        (is (= :suppressed (:status (record wid1)))
            "the superseded row stays :suppressed, not flipped to :cancelled")
        (is (= 2 (:generation (entry scoped-key)))
            "the live entry's generation is untouched by the stale abort")))))

(deftest cross-frame-aborted-event-rejected
  ;; rf2-iu0z8t (EP-0011): the legacy `:rf.resource.internal/aborted` event
  ;; must verify the carried :rf.frame/id against the receiving frame, like
  ;; succeeded/failed (rf2-jzh5gq / rf2-eu2ifi). A cross-frame aborted event
  ;; (payload stamped with another frame's id) is REJECTED: it can never
  ;; settle the receiving frame's live ENTRY to an accepted cancellation
  ;; (the durable user-visible state is the correctness boundary — the work-
  ;; ledger row, like succeeded/failed cross-frame, lowers to :suppressed at
  ;; the colliding work-id, never an accepted :cancelled).
  (rf/reg-resource :cfa/article (article-spec) article-spec-request)
  (let [fa :cfa/frame-a
        fb :cfa/frame-b
        scoped-key (state/scoped-resource-key :rf.scope/global :cfa/article {:slug "w"})]
    (rf/reg-frame fa {:doc "frame A"})
    (rf/reg-frame fb {:doc "frame B"})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cfa/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :b 1]}]
                      {:frame fb})
    (let [wid-b      (:current-work (entry fb scoped-key))
          before     (entry fb scoped-key)]
      (testing "an aborted event STAMPED with frame A, dispatched into frame B,
                does NOT abort-settle frame B's live entry (no cross-frame
                durable write even at the same work-id / generation)"
        ;; payload carries :rf.frame/id = fa (the wrong frame); the work-id +
        ;; generation happen to match frame B's live attempt.
        (rf/dispatch-sync [:rf.resource.internal/aborted
                           {:resource/key scoped-key :work/id wid-b :generation 1
                            :rf.frame/id fa}]
                          {:frame fb})
        (is (= (:status before) (:status (entry fb scoped-key)))
            "frame B's entry status untouched by the cross-frame aborted event")
        (is (= wid-b (:current-work (entry fb scoped-key)))
            "frame B's :current-work pointer not cleared by the cross-frame event"))
      (testing "the rejected cross-frame event NEVER settles an accepted
                :cancelled work row (stale / cross-frame validation wins)"
        (is (not= :cancelled (:status (record fb wid-b))))))))

;; ===========================================================================
;; 5. stale suppression is mandatory; abort is opportunistic
;; ===========================================================================

(deftest stale-reply-suppressed-and-row-terminal
  (rf/reg-resource :ss/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ss/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ss/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ss 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      ;; a newer refetch supersedes (generation 2) — opportunistic abort fires
      (rf/dispatch-sync [:rf.resource/refetch {:resource :ss/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (testing "Spec 016 §Cancellation is opportunistic — supersession fires a
                best-effort :rf.http/managed-abort for the old work id"
        (is (contains? (set @aborts) (req wid1))))
      (testing "the OLD work row is marked terminal :suppressed (:superseded)"
        (is (= :suppressed (:status (record wid1))))
        (is (= :superseded (get-in (record wid1) [:outcome :reason]))))
      (testing "Spec 016 §stale suppression (mandatory) — the OLD-generation
                reply never mutates the newer entry"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid1 :generation 1
                            :data {:stale "data"}}])
        (let [e (entry scoped-key)]
          (is (not= {:stale "data"} (:data e)) "stale reply did not write")
          (is (= 2 (:generation e)) "entry generation unchanged"))))))

;; ===========================================================================
;; 6. dedupe joins the existing record (no new generation / record)
;; ===========================================================================

(deftest dedupe-joins-existing-record
  (rf/reg-resource :dd/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :dd/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]
                                            :cause [:route-entry :r 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :x 2]
                                              :cause [:event :open]}])
      (testing "Spec 016 §Race — a second ensure while in flight JOINS the
                existing work record (owner attached, cause appended, no new
                record / generation)"
        (is (= 1 (count (ledger))) "exactly one work record (no new attempt)")
        (let [r (record wid)]
          (is (= #{[:route :r 1] [:lease :x 2]} (:owners r)))
          (is (= [[:route-entry :r 1] [:event :open]] (:causes r))))))))

;; ===========================================================================
;; 7. owner release: abort only when no remaining owner needs the work
;; ===========================================================================

(deftest release-owner-aborts-only-when-orphaned
  (rf/reg-resource :ro/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ro/article {:slug "w"})]
    ;; two owners on one in-flight attempt (ensure dedupes)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ro/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ro/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :x 2]}])
    (let [wid (:current-work (entry scoped-key))]
      (testing "Spec 016 §Race — releasing ONE of two owners does NOT abort
                the shared in-flight work (the other owner still needs it)"
        (rf/dispatch-sync [:rf.resource/release-owner {:owner [:route :r 1]}])
        (is (not (contains? (set @aborts) (req wid))) "shared request not aborted")
        (is (= #{[:lease :x 2]} (:owners (record wid))) "owner dropped from row")
        (is (= :running (:status (record wid))) "row still running"))
      (testing "releasing the LAST owner orphans the attempt → opportunistic
                abort + :abort-requested row (Spec 016 §Race)"
        (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 2]}])
        (is (contains? (set @aborts) (req wid)) "orphaned request best-effort aborted")
        (is (= :abort-requested (:status (record wid))))))))

;; ===========================================================================
;; 8. clear-scope / remove settle in-flight rows + opportunistic abort
;; ===========================================================================

(deftest clear-scope-cancels-in-flight-rows
  (rf/reg-resource :cs/article (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (let [scope-a {:user "a"}
        ka (state/scoped-resource-key scope-a :cs/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cs/article :scope scope-a
                                            :params {:slug "w"} :owner [:lease :a 1]}])
    (let [wid (:current-work (entry ka))]
      (rf/dispatch-sync [:rf.resource/clear-scope {:scope scope-a :cause :logout}])
      (testing "Spec 016 §clear-scope — the in-flight work row is settled
                terminal :cancelled and best-effort aborted"
        (is (= :cancelled (:status (record wid))))
        (is (= :clear-scope (get-in (record wid) [:outcome :reason])))
        (is (contains? (set @aborts) (req wid)))))))

(deftest remove-cancels-in-flight-row
  (rf/reg-resource :rm/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :rm/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rm 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource/remove {:resource :rm/article :scope :rf.scope/global
                                              :params {:slug "w"}}])
      (testing "Spec 016 §Events — remove settles the in-flight row :cancelled
                + best-effort aborts"
        (is (= :cancelled (:status (record wid))))
        (is (contains? (set @aborts) (req wid)))))))

;; ===========================================================================
;; 9. frame destroy cleans the side tables (durable records may persist)
;; ===========================================================================

(deftest frame-destroy-clears-side-tables
  (rf/reg-resource :fd/article (article-spec) article-spec-request)
  (let [fa :fd/frame-a
        scoped-key (state/scoped-resource-key :rf.scope/global :fd/article {:slug "w"})]
    (rf/reg-frame fa {:doc "teardown frame"})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fd 1]}]
                      {:frame fa})
    (let [wid (:current-work (entry fa scoped-key))]
      (testing "before destroy the host handle + generation high-water exist
                for the frame"
        (is (some? (work-ledger/get-handle fa wid)))
        (is (pos? (state/generation-snapshot fa))))
      (frame/destroy-frame! fa)
      (testing "Spec 016 [Runtime-Subsystems] clause 5 — frame destroy drops
                the TRANSIENT host handles + generation high-water for the
                frame (durable records ride the dropped frame value)"
        (is (nil? (work-ledger/get-handle fa wid)) "host handle cleared")
        (is (zero? (state/generation-snapshot fa)) "generation high-water dropped")))))

;; ===========================================================================
;; 10. work-id embeds the generation (one identity per record)
;; ===========================================================================

(deftest work-id-embeds-generation-one-identity
  (testing "Spec 016 §Ledger row retention and identity — the work id embeds
            the generation; ONE identity per record (no separate :stale-key)"
    (let [scoped-key [:rf.scope/global :r/x {:id 1}]
          wid (work-ledger/resource-work-id scoped-key 4)]
      (is (= [:rf.work/resource scoped-key 4] wid))
      ;; a constructed record has exactly :work/id as its identity — no
      ;; :stale-key synonym
      (let [r (work-ledger/work-record {:work-id wid :frame-id :f :resource/key scoped-key
                                        :generation 4 :transport :rf.http/managed
                                        :started-at 1})]
        (is (= wid (:work/id r)))
        (is (not (contains? r :stale-key)))))))

;; ===========================================================================
;; 10b. DURABLE REPLY-TARGET BOUNDARY (rf2-6kdcs9) — Managed-Effects §Work-
;;      ledger integration: a ledger row IS the reified continuation, and its
;;      `:reply-to` is the reply target made durable. `durable-reply-to`
;;      reconstructs the resource-read row's framework-internal continuation
;;      from the row's own durable facts and asserts it DATA-ONLY.
;; ===========================================================================

(deftest durable-reply-to-derives-data-only-continuation-from-the-row
  (testing "the row's reified continuation is DERIVED from its durable facts
            (work-id / resource-key / generation / work-frame) — not a stored
            copy that could drift from the stale-suppression identity"
    (let [scoped-key [:rf.scope/global :r/x {:id 1}]
          wid        (work-ledger/resource-work-id scoped-key 4)
          r          (work-ledger/work-record {:work-id wid :frame-id :app/main
                                               :resource/key scoped-key
                                               :generation 4 :transport :rf.http/managed
                                               :started-at 1})
          target     (work-ledger/durable-reply-to r)]
      (testing "the continuation addresses the framework-internal reply handler
                carrying the verification payload the handler gates on"
        (is (= [:rf.resource.internal/succeeded
                {:work/id      wid
                 :resource/key scoped-key
                 :generation   4
                 :rf.frame/id  :app/main}]
               (:event target)))
        (is (= :append (:delivery target))))
      (testing "the reconstructed durable continuation is DATA-ONLY (no
                ephemeral ::post / ::stale-authority, no host handle) and EDN-
                serializable — it can ride the durable row / SSR / epoch wire"
        (is (true? (reply/data-only-target? target)))
        (is (work-ledger/serializable-record? target)))
      (testing "the durable continuation carries ONLY the suppression identity
                — no scope (correlation metadata, not a suppression key) and no
                :stale-key synonym (one name per fact)"
        (let [vp (second (:event target))]
          (is (not (contains? vp :scope)))
          (is (not (contains? vp :stale-key)))
          (is (= wid (:work/id vp)) "the single suppression identity")))))
  (testing "durable-reply-to FAILS LOUD if a host handle ever hid in a row fact
            (an impossible-by-construction smuggle, but the boundary asserts it)"
    ;; A record whose :resource/key carried a host handle (a fn) must never
    ;; produce a durable continuation — durable-target rejects it before the
    ;; bogus target could ride a row / transport / trace.
    (let [bad (work-ledger/work-record {:work-id [:rf.work/resource [(fn [] 1)] 4]
                                        :frame-id :app/main
                                        :resource/key [(fn [] 1)]
                                        :generation 4 :transport :rf.http/managed
                                        :started-at 1})]
      (try
        (work-ledger/durable-reply-to bad)
        (is false "expected durable-reply-to to reject a host-handle row fact")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
          (is (= :rf.reply/non-data-target (:rf.error/kind (ex-data e)))))))))

;; ===========================================================================
;; 11. ADVERSARIAL (rf2-sxyrzk / eu2ifi) — two frames issuing the SAME
;;     resource at the SAME generation get DISTINCT frame-qualified transport
;;     request-ids, so neither supersedes / aborts the other in the
;;     process-global managed-HTTP in-flight registry. Both frames settle
;;     independently; the bare frame-local work-id WOULD collide.
;; ===========================================================================

(deftest cross-frame-request-id-does-not-collide
  (rf/reg-resource :xf/article (article-spec) article-spec-request)
  ;; capture every lowered managed-HTTP args map (not just the last) so we can
  ;; inspect BOTH frames' request-ids.
  (let [all-args (atom [])]
    (rf/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (let [fa :xf/frame-a
          fb :xf/frame-b
          scoped-key (state/scoped-resource-key :rf.scope/global :xf/article {:slug "w"})]
      (rf/reg-frame fa {:doc "frame A"})
      (rf/reg-frame fb {:doc "frame B"})
      ;; both frames ensure the SAME global-scope resource — same scoped key.
      (rf/dispatch-sync [:rf.resource/ensure {:resource :xf/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :a 1]}]
                        {:frame fa})
      (rf/dispatch-sync [:rf.resource/ensure {:resource :xf/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :b 1]}]
                        {:frame fb})
      (let [wid-a (:current-work (entry fa scoped-key))
            wid-b (:current-work (entry fb scoped-key))
            req-ids (mapv :request-id @all-args)]
        (testing "each frame mints the SAME frame-local work-id at the same
                  generation — the collision the bare work-id would cause"
          (is (= [:rf.work/resource scoped-key 1] wid-a))
          (is (= [:rf.work/resource scoped-key 1] wid-b))
          (is (= wid-a wid-b) "bare work-ids are identical across frames"))
        (testing "Spec 016 §Transport — the lowered transport :request-id is
                  the frame-QUALIFIED token, DISTINCT per frame, so the
                  process-global managed-HTTP registry cannot supersede one
                  frame's in-flight request with the other's"
          (is (= 2 (count req-ids)) "both frames lowered a managed request")
          (is (contains? (set req-ids) (work-ledger/managed-request-id fa wid-a)))
          (is (contains? (set req-ids) (work-ledger/managed-request-id fb wid-b)))
          (is (apply distinct? req-ids) "the two frames' request-ids differ")
          (is (not= (set req-ids) #{wid-a})
              "the request-id is NOT the bare work-id (which would collide)"))
        (testing "both frames carry an independent live work record + host
                  handle keyed by their own [frame-id work-id]"
          (is (= :running (:status (record fa wid-a))))
          (is (= :running (:status (record fb wid-b))))
          (is (= fa (:work/frame (record fa wid-a))))
          (is (= fb (:work/frame (record fb wid-b))))
          (is (some? (work-ledger/get-handle fa wid-a)))
          (is (some? (work-ledger/get-handle fb wid-b))))
        (testing "frame A settling does NOT disturb frame B (independent
                  settlement — no stranded pending entry)"
          (rf/dispatch-sync [:rf.resource.internal/succeeded
                             {:resource/key scoped-key :work/id wid-a :generation 1
                              :rf.frame/id fa :data {:title "A"}}]
                            {:frame fa})
          (is (= {:title "A"} (:data (entry fa scoped-key))) "frame A loaded")
          (is (= :running (:status (record fb wid-b)))
              "frame B's attempt still in flight — untouched by frame A's reply")
          (is (not= :loaded (:status (entry fb scoped-key)))
              "frame B's entry not settled by frame A"))))))

;; ===========================================================================
;; rf2-wyan7e — resource-key → work-id inverse index: prune visits only the
;; settling key's rows (O(rows-for-key)) instead of scanning the whole ledger
;; (O(all-work)). These pure tests pin the index-driven prune against a
;; reference FULL-SCAN prune (the prior semantics) so the optimisation is
;; behaviour-preserving, and pin the index as a derived projection of the
;; ledger (== a full rebuild, self-healing on a wholesale-installed ledger).
;; ===========================================================================

(defn- reference-prune-full-scan
  "The PRIOR `prune-terminal-for-key` semantics, kept here as the behaviour
  oracle: a FULL ledger scan for the key's terminal rows, retaining `keep-tail`
  newest-by-:started-at. Index-free — the result the new index-driven prune
  must reproduce exactly (modulo the inverse-index sidecar key)."
  [runtime-db resource-key keep-tail]
  (let [ledger (:rf.runtime/work-ledger runtime-db)
        rk-id  (state/key-id resource-key)
        terminal-for-key
        (->> ledger
             (filter (fn [[_ r]] (and (= rk-id (state/key-id (:resource/key r)))
                                      (work-ledger/terminal? (:status r)))))
             (sort-by (fn [[_ r]] (or (:started-at r) 0)) >))
        drop-ids (->> terminal-for-key (drop keep-tail) (map key))]
    (if (seq drop-ids)
      (update runtime-db :rf.runtime/work-ledger
              (fn [l] (reduce dissoc l drop-ids)))
      runtime-db)))

(defn- record-for
  [scoped-key generation status started-at]
  {:work/id      [:rf.work/resource scoped-key generation]
   :work/kind    :resource
   :resource/key scoped-key
   :generation   generation
   :status       status
   :started-at   started-at})

(defn- build-ledger
  "Build a `:rf.runtime/work-ledger` map (byte-keyed) from a seq of records, AND
  maintain the inverse index via `put-record` (so the index is live, the
  realistic in-session shape)."
  [records]
  (reduce (fn [rdb r] (work-ledger/put-record rdb (:work/id r) r))
          {}
          records))

(deftest prune-terminal-for-key-matches-full-scan-reference
  (testing "rf2-wyan7e — the index-driven prune drops EXACTLY the rows the
            prior full-scan prune dropped, across mixed keys / statuses / tails"
    (let [ka (state/scoped-resource-key :rf.scope/global :wl/a {:id 1})
          kb (state/scoped-resource-key :rf.scope/global :wl/b {:id 2})
          ;; ka: 5 terminal (varied started-at) + 1 running; kb: 2 terminal
          records [(record-for ka 1 :completed 100)
                   (record-for ka 2 :failed    300)
                   (record-for ka 3 :completed 200)
                   (record-for ka 4 :cancelled 500)
                   (record-for ka 5 :suppressed 400)
                   (record-for ka 6 :running   600)
                   (record-for kb 1 :completed 50)
                   (record-for kb 2 :failed    70)]
          rdb (build-ledger records)]
      (doseq [keep-tail [0 1 3 10]]
        (let [new-rdb (work-ledger/prune-terminal-for-key rdb ka keep-tail)
              ref-rdb (reference-prune-full-scan rdb ka keep-tail)]
          (is (= (:rf.runtime/work-ledger ref-rdb)
                 (:rf.runtime/work-ledger new-rdb))
              (str "ledger after prune differs from full-scan reference at tail "
                   keep-tail))
          (testing "the running (non-terminal) row + the other key's rows are
                    NEVER pruned"
            (is (some? (get-in new-rdb (work-ledger/record-path
                                         [:rf.work/resource ka 6]))))
            (is (some? (get-in new-rdb (work-ledger/record-path
                                         [:rf.work/resource kb 1]))))
            (is (some? (get-in new-rdb (work-ledger/record-path
                                         [:rf.work/resource kb 2])))))
          (testing "the inverse index stays == a full rebuild from the ledger"
            (is (= (-> new-rdb work-ledger/recompute-ledger-index
                       :rf.runtime/work-ledger-by-key)
                   (:rf.runtime/work-ledger-by-key new-rdb))
                "inverse index drift vs full rebuild")))))))

(deftest prune-self-heals-on-wholesale-installed-ledger
  (testing "rf2-wyan7e — a ledger installed wholesale (hydration / restore /
            replace-frame-state!) carries NO inverse index; the first prune
            rebuilds it from ground truth and still matches the full scan"
    (let [ka (state/scoped-resource-key :rf.scope/global :wl/h {:id 1})
          ;; install the ledger DIRECTLY (no put-record), so no index sidecar
          records [(record-for ka 1 :completed 100)
                   (record-for ka 2 :completed 200)
                   (record-for ka 3 :failed    300)
                   (record-for ka 4 :running   400)]
          installed (reduce (fn [rdb r]
                              (assoc-in rdb (work-ledger/record-path (:work/id r)) r))
                            {} records)]
      (is (not (contains? installed :rf.runtime/work-ledger-by-key))
          "precondition: installed ledger has no inverse index")
      (let [new-rdb (work-ledger/prune-terminal-for-key installed ka 1)
            ref-rdb (reference-prune-full-scan installed ka 1)]
        (is (= (:rf.runtime/work-ledger ref-rdb)
               (:rf.runtime/work-ledger new-rdb))
            "self-healed prune matches the full-scan reference")
        (is (contains? new-rdb :rf.runtime/work-ledger-by-key)
            "the inverse index was rebuilt")
        (is (= (-> new-rdb work-ledger/recompute-ledger-index
                   :rf.runtime/work-ledger-by-key)
               (:rf.runtime/work-ledger-by-key new-rdb))
            "rebuilt index == full rebuild")))))

(deftest ledger-inverse-index-equals-full-rebuild-under-random-mutation
  (testing "rf2-wyan7e — across a randomised sequence of put-record /
            prune-record / prune-terminal-for-key ops, the incrementally
            maintained inverse index equals a full rebuild after EVERY op"
    (let [seed    (atom 88172645)
          nextint (fn [n]
                    (let [x (-> (* @seed 1103515245) (+ 12345) (bit-and 0x7fffffff))]
                      (reset! seed x)
                      (mod x n)))
          keys'   (mapv #(state/scoped-resource-key :rf.scope/global :wl/r {:id %})
                        (range 5))
          ;; seed the index live so put/prune keep it in step from step 0
          start   (work-ledger/recompute-ledger-index {:rf.runtime/work-ledger {}})]
      (loop [step 0, rdb start, gen 0]
        (if (= step 500)
          (is true "500 randomised ledger ops stayed in lock-step with the rebuild")
          (let [op  (nextint 3)
                k   (nth keys' (nextint (count keys')))
                rdb' (case op
                       ;; put a fresh record
                       0 (let [g (inc gen)
                               r (record-for k g
                                             (nth [:running :completed :failed :cancelled]
                                                  (nextint 4))
                                             (nextint 1000))]
                           (work-ledger/put-record rdb [:rf.work/resource k g] r))
                       ;; prune one terminal tail for the key
                       1 (work-ledger/prune-terminal-for-key rdb k (nextint 3))
                       ;; prune a specific record (if any exist for the key)
                       2 (let [g (inc (nextint (inc gen)))]
                           (work-ledger/prune-record rdb [:rf.work/resource k g])))
                full (-> rdb' work-ledger/recompute-ledger-index
                         :rf.runtime/work-ledger-by-key)]
            (is (= full (:rf.runtime/work-ledger-by-key rdb'))
                (str "inverse-index drift at step " step " op " op))
            (is (every? seq (vals (:rf.runtime/work-ledger-by-key rdb')))
                "no empty inverse-index buckets")
            (recur (inc step) rdb' (if (zero? op) (inc gen) gen))))))))
