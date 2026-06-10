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
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + the work-ledger side-table fx these tests
   ;; dispatch through.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch + abort are overridden by capturing no-ops below.
   [re-frame.http-managed]
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
  / abort fires. Composed INSIDE the reset-runtime fixture."
  [f]
  (reset! last-managed-args nil)
  (reset! aborts [])
  (state/reset-cache!)
  (work-ledger/reset-cache!)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  ;; managed-abort args is the request-id (= work-id) directly
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
  ([frame-id] (rf/runtime-db-value frame-id)))

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

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :request       (fn [{:keys [slug]} _ctx]
                            {:request {:method :get :url (str "/api/articles/" slug)}})
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

;; ===========================================================================
;; 1. ensure writes a serializable work record keyed by work id
;; ===========================================================================

(deftest ensure-writes-work-record
  (rf/reg-resource :wl/article (article-spec))
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

;; ===========================================================================
;; 2. host handles live in the side table keyed by [frame-id work-id]
;; ===========================================================================

(deftest host-handle-in-side-table-not-runtime-db
  (rf/reg-resource :hh/article (article-spec))
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
  (rf/reg-resource :sc/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sc/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sc/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :sc 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id wid :generation 1
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
  (rf/reg-resource :pr/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :pr/article {:slug "w"})]
    ;; run several attempts so terminal rows accumulate, then assert the
    ;; ledger is bounded (default tail = 3 terminal rows per key)
    (dotimes [_ 6]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :pr/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (let [wid (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key scoped-key :work-id wid
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
  (rf/reg-resource :fa/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :fa/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fa/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fa 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource-key scoped-key :work-id wid :generation 1
                          :error {:kind :rf.http/http-5xx :status 503}}])
      (testing "a failed first load settles the work row terminal :failed with
                the error envelope as its outcome (Xray summary)"
        (is (= :failed (:status (record wid))))
        (is (= {:error {:kind :rf.http/http-5xx :status 503}} (:outcome (record wid)))))
      (testing "the host handle is cleared"
        (is (nil? (work-ledger/get-handle :rf/default wid)))))))

(deftest aborted-settles-record-cancelled
  (rf/reg-resource :ab/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ab/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ab/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ab 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/aborted
                         {:resource-key scoped-key :work-id wid :generation 1}])
      (testing "an aborted attempt settles the work row terminal :cancelled +
                clears the handle (entry untouched — the verification gate
                handles its settle)"
        (is (= :cancelled (:status (record wid))))
        (is (nil? (work-ledger/get-handle :rf/default wid)))))))

;; ===========================================================================
;; 5. stale suppression is mandatory; abort is opportunistic
;; ===========================================================================

(deftest stale-reply-suppressed-and-row-terminal
  (rf/reg-resource :ss/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ss/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ss/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ss 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      ;; a newer refetch supersedes (generation 2) — opportunistic abort fires
      (rf/dispatch-sync [:rf.resource/refetch {:resource :ss/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (testing "Spec 016 §Cancellation is opportunistic — supersession fires a
                best-effort :rf.http/managed-abort for the old work id"
        (is (contains? (set @aborts) wid1)))
      (testing "the OLD work row is marked terminal :suppressed (:superseded)"
        (is (= :suppressed (:status (record wid1))))
        (is (= :superseded (get-in (record wid1) [:outcome :reason]))))
      (testing "Spec 016 §stale suppression (mandatory) — the OLD-generation
                reply never mutates the newer entry"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key scoped-key :work-id wid1 :generation 1
                            :data {:stale "data"}}])
        (let [e (entry scoped-key)]
          (is (not= {:stale "data"} (:data e)) "stale reply did not write")
          (is (= 2 (:generation e)) "entry generation unchanged"))))))

;; ===========================================================================
;; 6. dedupe joins the existing record (no new generation / record)
;; ===========================================================================

(deftest dedupe-joins-existing-record
  (rf/reg-resource :dd/article (article-spec))
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
  (rf/reg-resource :ro/article (article-spec))
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
        (is (not (contains? (set @aborts) wid)) "shared request not aborted")
        (is (= #{[:lease :x 2]} (:owners (record wid))) "owner dropped from row")
        (is (= :running (:status (record wid))) "row still running"))
      (testing "releasing the LAST owner orphans the attempt → opportunistic
                abort + :abort-requested row (Spec 016 §Race)"
        (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :x 2]}])
        (is (contains? (set @aborts) wid) "orphaned request best-effort aborted")
        (is (= :abort-requested (:status (record wid))))))))

;; ===========================================================================
;; 8. clear-scope / remove settle in-flight rows + opportunistic abort
;; ===========================================================================

(deftest clear-scope-cancels-in-flight-rows
  (rf/reg-resource :cs/article (article-spec {:scope :rf.scope/from-caller}))
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
        (is (contains? (set @aborts) wid))))))

(deftest remove-cancels-in-flight-row
  (rf/reg-resource :rm/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :rm/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rm 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource/remove {:resource :rm/article :scope :rf.scope/global
                                              :params {:slug "w"}}])
      (testing "Spec 016 §Events — remove settles the in-flight row :cancelled
                + best-effort aborts"
        (is (= :cancelled (:status (record wid))))
        (is (contains? (set @aborts) wid))))))

;; ===========================================================================
;; 9. frame destroy cleans the side tables (durable records may persist)
;; ===========================================================================

(deftest frame-destroy-clears-side-tables
  (rf/reg-resource :fd/article (article-spec))
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
      (let [r (work-ledger/work-record {:work-id wid :frame-id :f :resource-key scoped-key
                                        :generation 4 :transport :rf.http/managed
                                        :started-at 1})]
        (is (= wid (:work/id r)))
        (is (not (contains? r :stale-key)))))))
