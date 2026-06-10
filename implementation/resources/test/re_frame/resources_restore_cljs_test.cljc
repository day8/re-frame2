(ns re-frame.resources-restore-cljs-test
  "Epoch-restore reconcile for the Resources artefact (rf2-7r5mc2, Spec 016
  §Restore and replay parts 2/4/5 — EP-0003 slice 8 / §9 restore conformance
  fixtures).

  Epoch restore installs the UNPROJECTED captured frame-state snapshot
  WHOLESALE — unlike SSR hydration, which installs the server PROJECTION
  (`:current-work` already stripped on the wire, no non-terminal work-ledger
  rows). So `reconcile-on-restore` does everything the SSR hydrate reconcile
  does (recompute reverse indexes from `:entries`, orphan SSR owners, clear the
  transient `:current-work` pointer) PLUS the two restore-specific settles the
  wire projection had already applied:

    1. settle every mid-flight `:loading` / `:fetching` entry to its last
       STABLE status (`:loaded` if data, `:error` if a failed first load,
       `:idle` if it never loaded) — never left stranded pointing at a vanished
       attempt (part 2);
    2. record every restored NON-terminal work-ledger row as DANGLING (terminal
       `:suppressed` / `:dangling`) so a pre-restore in-flight reply is
       suppressed by the ordinary work-id + generation check (part 2).

  These JVM+CLJS unit tests pin the restore reconcile contract. They map onto
  the EP-0003 §9 restore conformance fixtures:

    - epoch restore settles non-terminal restored work-ledger rows to dangling,
      clears the entry's `:current-work`, and settles the entry to its last
      stable status;
    - the generation allocator is monotonic across restore (a post-restore
      allocation strictly exceeds any pre-restore generation — the host-side
      allocator part 1, exercised here against `state/generation-cache`);
    - a pre-restore in-flight reply that lands after restore is suppressed by
      the work-id + generation check (the dangled terminal row + cleared
      `:current-work` are what make the suppression structural);
    - owner reconciliation revives route owners the restored routing names live
      and orphans SSR / stale-nav owners;
    - the `:tag-index` / `:owner-index` are recomputed from `:entries` and never
      trusted from the snapshot;
    - restore does not eagerly refetch (a settled entry's freshness is a later
      live-owner decision — nothing here re-fetches)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.late-bind :as late-bind]
   ;; load-bearing side-effecting require: the façade publishes the restore
   ;; reconcile hook + registers the resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- entry
  "A durable entry under a scoped key, mirroring the runtime's durable shape."
  [{:keys [resource-id status data error loaded-at stale-at invalidated-at
           generation current-work tags owners refresh-error]
    :or   {status :loaded generation 1 tags #{} owners #{}}}]
  (merge (state/empty-entry resource-id)
         {:status         status
          :data           data
          :error          error
          :loaded-at      loaded-at
          :stale-at       stale-at
          :invalidated-at invalidated-at
          :generation     generation
          :current-work   current-work
          :tags           tags
          :active-owners  owners
          :refresh-error  refresh-error}))

(defn- runtime-db-with
  "A runtime-db carrying a `:rf.runtime/resources :entries` map (and optional
  `:rf.runtime/work-ledger`)."
  ([entries] (runtime-db-with entries nil))
  ([entries ledger]
   (cond-> {state/resources-key {:entries entries :tag-index {} :owner-index {}}}
     ledger (assoc state/work-ledger-key ledger))))

(def ^:private gkey
  (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"}))

;; ===========================================================================
;; 1. Settle mid-flight entries to last stable (Spec 016 §Restore part 2)
;; ===========================================================================

(deftest settle-loading-entry-with-no-data-to-idle
  (testing "a restored :loading entry that never loaded (no data, no error) settles to :idle"
    (let [e   (entry {:resource-id :article/by-slug :status :loading :data nil
                      :current-work [:rf.work/resource gkey 3]})
          out (ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries gkey])]
      (is (= :idle (:status se)) "loading-with-no-data → :idle, never stranded :loading")
      (is (nil? (:current-work se)) "the vanished current-work pointer is cleared"))))

(deftest settle-fetching-entry-with-data-to-loaded
  (testing "a restored :fetching entry (background refresh in flight) keeps its
            last-known-good data and settles to :loaded"
    (let [e   (entry {:resource-id :article/by-slug :status :fetching
                      :data {:title "kept"} :loaded-at 1000 :stale-at 9.0e15
                      :current-work [:rf.work/resource gkey 4]})
          out (ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries gkey])]
      (is (= :loaded (:status se)) "fetching-with-data → :loaded (keep last-known-good)")
      (is (= {:title "kept"} (:data se)) "the last-known-good data is preserved")
      (is (nil? (:current-work se)) "the vanished current-work pointer is cleared"))))

(deftest settle-loading-entry-with-error-to-error
  (testing "a restored :loading entry carrying a first-load :error envelope (no
            data) settles to :error"
    (let [err {:kind :rf.http/http-5xx}
          e   (entry {:resource-id :article/by-slug :status :loading :data nil
                      :error err :current-work [:rf.work/resource gkey 5]})
          out (ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries gkey])]
      (is (= :error (:status se)) "loading-with-error-and-no-data → :error")
      (is (= err (:error se)) "the first-load error envelope is retained")
      (is (nil? (:current-work se))))))

(deftest already-stable-entries-keep-status
  (testing "a :loaded / :error / :idle entry is already stable — its status is
            unchanged (only :current-work is cleared)"
    (let [loaded (entry {:resource-id :a :status :loaded :data {:x 1}
                         :loaded-at 1000 :stale-at 9.0e15})
          errd   (entry {:resource-id :b :status :error :error {:kind :x}})
          idle   (entry {:resource-id :c :status :idle})
          ka (state/scoped-resource-key :rf.scope/global :a {})
          kb (state/scoped-resource-key :rf.scope/global :b {})
          kc (state/scoped-resource-key :rf.scope/global :c {})
          out (ssr/reconcile-on-restore (runtime-db-with {ka loaded kb errd kc idle}) :app/main)
          es  (get-in out [state/resources-key :entries])]
      (is (= :loaded (:status (es ka))))
      (is (= :error  (:status (es kb))))
      (is (= :idle   (:status (es kc)))))))

(deftest settle-entry-to-last-stable-is-pure
  (testing "settle-entry-to-last-stable: the three in-flight resolutions + pass-through"
    (is (= :idle   (:status (ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loading :data nil})))))
    (is (= :loaded (:status (ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :fetching :data {:x 1}})))))
    (is (= :loaded (:status (ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loading :data {:x 1}})))))
    (is (= :error  (:status (ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loading :data nil
                                      :error {:kind :x}})))))
    (is (= :loaded (:status (ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loaded :data {:x 1}})))))))

;; ===========================================================================
;; 2. Dangle non-terminal work-ledger rows (Spec 016 §Restore part 2)
;; ===========================================================================

(defn- work-row
  [work-id status]
  (-> (work-ledger/work-record
        {:work-id work-id :frame-id :app/main :resource-key gkey
         :generation (nth work-id 2) :transport :rf.http/managed
         :started-at 1000})
      (assoc :status status)))

(deftest non-terminal-rows-settled-to-dangling-suppressed
  (testing "every restored non-terminal work-ledger row settles to terminal
            :suppressed with a :dangling outcome (so a late reply is suppressed)"
    (let [w-running [:rf.work/resource gkey 3]
          w-queued  [:rf.work/resource gkey 4]
          w-abort   [:rf.work/resource gkey 5]
          ledger    {w-running (work-row w-running :running)
                     w-queued  (work-row w-queued :queued)
                     w-abort   (work-row w-abort :abort-requested)}
          rdb (runtime-db-with {gkey (entry {:resource-id :article/by-slug
                                             :status :loading :data nil})}
                               ledger)
          out (ssr/reconcile-on-restore rdb :app/main)
          out-ledger (get out state/work-ledger-key)]
      (doseq [wid [w-running w-queued w-abort]]
        (let [row (get out-ledger wid)]
          (is (= :suppressed (:status row))
              (str wid " settled to terminal :suppressed"))
          (is (work-ledger/terminal? (:status row))
              (str wid " is now terminal"))
          (is (= :dangling (get-in row [:outcome :reason]))
              (str wid " outcome marks it dangling")))))))

(deftest terminal-rows-ride-through-unchanged
  (testing "already-terminal work-ledger rows are not re-touched by restore"
    (let [w-done [:rf.work/resource gkey 2]
          row    (work-ledger/mark-terminal (work-row w-done :completed)
                                            :completed {:ok true})
          rdb (runtime-db-with {gkey (entry {:resource-id :article/by-slug
                                             :status :loaded :data {:x 1}
                                             :loaded-at 1 :stale-at 9.0e15})}
                               {w-done row})
          out (ssr/reconcile-on-restore rdb :app/main)]
      (is (= row (get-in out [state/work-ledger-key w-done]))
          "a completed row is unchanged"))))

(deftest dangle-non-terminal-work-returns-dangled-ids
  (testing "dangle-non-terminal-work! returns the dangled work-ids + leaves
            terminal rows alone"
    (let [w1 [:rf.work/resource gkey 3]
          w2 [:rf.work/resource gkey 2]
          ledger {w1 (work-row w1 :running)
                  w2 (work-ledger/mark-terminal (work-row w2 :completed) :completed {})}
          [rdb' dangled] (ssr/dangle-non-terminal-work!
                          {state/work-ledger-key ledger})]
      (is (= [w1] dangled) "only the non-terminal row is dangled")
      (is (= :suppressed (get-in rdb' [state/work-ledger-key w1 :status])))
      (is (= :completed (get-in rdb' [state/work-ledger-key w2 :status]))))))

;; ===========================================================================
;; 3. Owner reconciliation: orphan SSR, keep route (Spec 016 §Restore part 4)
;; ===========================================================================

(deftest restore-orphans-ssr-owners-keeps-route-owners
  (testing "SSR owners orphan on restore (a settled server render); route /
            machine / lease owners ride through to their own subsystem reconcile"
    (let [e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15
                      :owners #{[:ssr "req-9" "nav-1"]
                                [:route :route/article "nav-1"]
                                [:machine :checkout/flow "inst-1"]}})
          out (ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          owners (get-in out [state/resources-key :entries gkey :active-owners])]
      (is (not (contains? owners [:ssr "req-9" "nav-1"])) "SSR owner orphaned")
      (is (contains? owners [:route :route/article "nav-1"]) "route owner survives")
      (is (contains? owners [:machine :checkout/flow "inst-1"]) "machine owner survives")
      (is (not (contains? (get-in out [state/resources-key :owner-index])
                          [:ssr "req-9" "nav-1"]))
          "the orphaned SSR owner is absent from the recomputed owner-index"))))

;; ===========================================================================
;; 4. Indexes recomputed from entries, never trusted (Spec 016 §Restore part 5)
;; ===========================================================================

(deftest restore-recomputes-indexes-from-entries
  (testing "the reverse indexes are rebuilt from the reconciled entries, the
            (deliberately wrong) snapshot indexes discarded"
    (let [e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15
                      :tags #{[:article "x"]}
                      :owners #{[:route :route/article "nav-1"]}})
          rdb {state/resources-key {:entries     {gkey e}
                                    :tag-index   {[:bogus] #{:nope}}
                                    :owner-index {[:bogus] #{:nope}}}}
          out (ssr/reconcile-on-restore rdb :app/main)
          sub (get out state/resources-key)]
      (is (= {[:article "x"] #{gkey}} (:tag-index sub))
          "tag-index recomputed from the entry's :tags; bogus snapshot index discarded")
      (is (= {[:route :route/article "nav-1"] #{gkey}} (:owner-index sub))
          "owner-index recomputed from the entry's owners; bogus snapshot index discarded"))))

;; ===========================================================================
;; 5. The generation allocator is monotonic across restore (part 1)
;; ===========================================================================

(deftest generation-allocator-monotonic-across-restore
  (testing "the host-side generation allocator is NOT frame-state — restore
            cannot rewind it, so a post-restore allocation strictly exceeds any
            pre-restore generation (anti-recycling, part 1)"
    ;; Pre-restore: the live timeline minted up to generation 7.
    (state/commit-generation! :app/main 7)
    ;; The captured snapshot is from an earlier epoch where generation was 3.
    ;; reconcile-on-restore touches ONLY durable frame-state — never the host
    ;; allocator — so the high-water mark stays at 7.
    (let [snapshot-entry (entry {:resource-id :article/by-slug :status :fetching
                                 :data {:x 1} :loaded-at 1 :stale-at 9.0e15
                                 :generation 3
                                 :current-work [:rf.work/resource gkey 3]})]
      (ssr/reconcile-on-restore (runtime-db-with {gkey snapshot-entry}) :app/main)
      (is (= 7 (state/generation-snapshot :app/main))
          "restore did not rewind the host-side allocator")
      ;; the next allocation strictly exceeds the pre-restore generation 7 AND
      ;; the restored snapshot's generation 3 → a pre-restore reply carrying
      ;; generation 3 can never match a freshly-minted live entry.
      (is (= 8 (state/next-generation (state/generation-snapshot :app/main)))
          "the next minted generation strictly exceeds every pre-restore generation"))))

;; ===========================================================================
;; 6. Restore does not eagerly refetch / no-op cases / hook published
;; ===========================================================================

(deftest restore-does-not-eagerly-refetch
  (testing "reconcile-on-restore settles entries but issues NO refetch fx —
            freshness is a later live-owner decision (part 3)"
    (let [stale (entry {:resource-id :article/by-slug :status :loaded
                        :data {:x 1} :loaded-at 1 :stale-at 2})  ;; stale
          out (ssr/reconcile-on-restore (runtime-db-with {gkey stale}) :app/main)]
      ;; the reconcile returns a runtime-db, never an fx vector / dispatch plan
      (is (map? out))
      (is (contains? out state/resources-key))
      (is (= {:x 1} (get-in out [state/resources-key :entries gkey :data]))
          "the stale entry keeps its data; restore double-fetches nothing"))))

(deftest restore-noop-without-resources
  (testing "a runtime-db with no resource entries AND no work-ledger rows is
            returned unchanged (a resource-free restore)"
    (let [rdb {:rf.runtime/machines {:snapshots {}}}]
      (is (= rdb (ssr/reconcile-on-restore rdb :app/main))))))

(deftest restore-never-crosses-scopes
  (testing "entries under different scopes stay isolated through the restore reconcile"
    (let [ka (state/scoped-resource-key [:rf.scope/session {:user "a"}] :article/by-slug {:slug "x"})
          kb (state/scoped-resource-key [:rf.scope/session {:user "b"}] :article/by-slug {:slug "x"})
          ea (entry {:resource-id :article/by-slug :status :fetching :data {:owner "a"}
                     :loaded-at 1 :stale-at 9.0e15 :tags #{[:article "x"]}
                     :current-work [:rf.work/resource ka 2]})
          eb (entry {:resource-id :article/by-slug :status :loaded :data {:owner "b"}
                     :loaded-at 1 :stale-at 9.0e15 :tags #{[:article "x"]}})
          out (ssr/reconcile-on-restore (runtime-db-with {ka ea kb eb}) :app/main)
          es  (get-in out [state/resources-key :entries])]
      (is (= {:owner "a"} (:data (es ka))) "scope-a data stays under scope-a's key")
      (is (= :loaded (:status (es ka))) "scope-a fetching → loaded (kept data)")
      (is (= {:owner "b"} (:data (es kb))) "scope-b data stays under scope-b's key")
      (is (= #{ka kb} (get-in out [state/resources-key :tag-index [:article "x"]]))
          "the shared tag maps to both scoped keys, never collapsed"))))

(deftest reconcile-on-restore-hook-published
  (testing "the :resources/reconcile-on-restore hook is published by the façade"
    (is (some? (late-bind/get-fn :resources/reconcile-on-restore)))
    (is (= ssr/reconcile-on-restore
           (late-bind/get-fn :resources/reconcile-on-restore)))))
