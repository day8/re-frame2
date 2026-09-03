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
      allocator part 1, exercised here against `rf.resources.state/generation-cache`);
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
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.interop :as rf.interop]
   [re-frame.late-bind :as rf.late-bind]
   ;; load-bearing side-effecting require: the façade publishes the restore
   ;; reconcile hook + registers the resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- entry
  "A durable entry under a scoped key, mirroring the runtime's durable shape."
  [{:keys [resource-id status data error loaded-at stale-at invalidated-at
           generation current-work tags owners refresh-error]
    :or   {status :loaded generation 1 tags #{} owners #{}}}]
  (merge (rf.resources.state/empty-entry resource-id)
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
  `:rf.runtime/work-ledger`). rf2-9e0tyq: re-keys the natural
  `{scoped-key-vector entry}` form into the runtime's byte-`key-id` shape
  (stamping each entry's `:resource/key`), and re-keys the ledger from
  `{work-id-vector record}` to the byte `work-id-id` shape."
  ([entries] (runtime-db-with entries nil))
  ([entries ledger]
   (cond-> {rf.resources.state/resources-key
            {:entries (into {}
                            (map (fn [[sk e]]
                                   [(rf.resources.state/key-id sk) (assoc e :resource/key sk)]))
                            entries)
             :tag-index {} :owner-index {}}}
     ledger (assoc rf.resources.state/work-ledger-key
                   (into {} (map (fn [[wid r]] [(rf.resources.work-ledger/work-id-id wid) r])) ledger)))))

(defn- with-live-nav-token
  "Install a restored routing slice naming `nav-token` as live
  (`[:rf.runtime/routing :current :nav-token]`) into `runtime-db` — the
  nav-token restore's owner reconcile compares restored route owners against
  (Spec 016 §Restore and replay part 4)."
  [runtime-db nav-token]
  (assoc-in runtime-db [:rf.runtime/routing :current :nav-token] nav-token))

(def ^:private gkey
  (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"}))

;; ===========================================================================
;; 1. Settle mid-flight entries to last stable (Spec 016 §Restore part 2)
;; ===========================================================================

(deftest settle-loading-entry-with-no-data-to-idle
  (testing "a restored :loading entry that never loaded (no data, no error) settles to :idle"
    (let [e   (entry {:resource-id :article/by-slug :status :loading :data nil
                      :current-work [:rf.work/resource gkey 3]})
          out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey)])]
      (is (= :idle (:status se)) "loading-with-no-data → :idle, never stranded :loading")
      (is (nil? (:current-work se)) "the vanished current-work pointer is cleared"))))

(deftest settle-fetching-entry-with-data-to-loaded
  (testing "a restored :fetching entry (background refresh in flight) keeps its
            last-known-good data and settles to :loaded"
    (let [e   (entry {:resource-id :article/by-slug :status :fetching
                      :data {:title "kept"} :loaded-at 1000 :stale-at 9.0e15
                      :current-work [:rf.work/resource gkey 4]})
          out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey)])]
      (is (= :loaded (:status se)) "fetching-with-data → :loaded (keep last-known-good)")
      (is (= {:title "kept"} (:data se)) "the last-known-good data is preserved")
      (is (nil? (:current-work se)) "the vanished current-work pointer is cleared"))))

(deftest settle-loading-entry-with-error-to-error
  (testing "a restored :loading entry carrying a first-load :error envelope (no
            data) settles to :error"
    (let [err {:kind :rf.http/http-5xx}
          e   (entry {:resource-id :article/by-slug :status :loading :data nil
                      :error err :current-work [:rf.work/resource gkey 5]})
          out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey)])]
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
          ka (rf.resources.state/scoped-resource-key :rf.scope/global :a {})
          kb (rf.resources.state/scoped-resource-key :rf.scope/global :b {})
          kc (rf.resources.state/scoped-resource-key :rf.scope/global :c {})
          out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {ka loaded kb errd kc idle}) :app/main)
          es  (get-in out [rf.resources.state/resources-key :entries])]
      ;; rf2-9e0tyq — entries are keyed on the byte key-id.
      (is (= :loaded (:status (es (rf.resources.state/key-id ka)))))
      (is (= :error  (:status (es (rf.resources.state/key-id kb)))))
      (is (= :idle   (:status (es (rf.resources.state/key-id kc))))))))

(deftest settle-entry-to-last-stable-is-pure
  (testing "settle-entry-to-last-stable: the three in-flight resolutions + pass-through"
    (is (= :idle   (:status (rf.resources.ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loading :data nil})))))
    (is (= :loaded (:status (rf.resources.ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :fetching :data {:x 1}})))))
    (is (= :loaded (:status (rf.resources.ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loading :data {:x 1}})))))
    (is (= :error  (:status (rf.resources.ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loading :data nil
                                      :error {:kind :x}})))))
    (is (= :loaded (:status (rf.resources.ssr/settle-entry-to-last-stable
                              (entry {:resource-id :a :status :loaded :data {:x 1}})))))))

(deftest restore-does-not-re-read-the-live-clock-for-durable-entry-timestamps
  ;; rf2-wshzsp — ADVERSARIAL guard for EP-0010 §Restore/Replay: "Restore
  ;; installs a durable frame-state value. It MUST NOT re-read ambient world
  ;; facts to freshen that state during install ... restored resource entries do
  ;; not re-read the live clock during install." The structural restore tests
  ;; prove the durable timestamps SURVIVE (they are passed through, not
  ;; recomputed); this one PROVES it adversarially — it stubs the ONLY live-clock
  ;; read in the reconcile (`rf.interop/epoch-now-ms`) to a sentinel NOTHING durable
  ;; should ever stamp, then asserts every restored entry's :loaded-at /
  ;; :stale-at / :invalidated-at is the SNAPSHOT's value, untouched by the
  ;; sentinel. A regression that freshened any durable timestamp from the live
  ;; install clock would stamp the sentinel and fail loudly here.
  (testing "the live install clock (rf.interop/epoch-now-ms) does NOT freshen any durable restored
            entry timestamp — they ride through equal to the snapshot's"
    (let [sentinel    9999999999999  ; the install-clock value nothing durable may stamp
          ;; one of each freshness shape: a loaded entry with a future stale-at,
          ;; a stale-by-window entry, and an explicitly invalidated one — all
          ;; ALREADY stable so the settle is a no-op and only the timestamps matter.
          loaded      (entry {:resource-id :a :status :loaded :data {:x 1}
                              :loaded-at 1000 :stale-at 2000})
          invalidated (entry {:resource-id :b :status :loaded :data {:y 2}
                              :loaded-at 1000 :stale-at 8000 :invalidated-at 1500})
          ka (rf.resources.state/scoped-resource-key :rf.scope/global :a {})
          kb (rf.resources.state/scoped-resource-key :rf.scope/global :b {})]
      (with-redefs [rf.interop/epoch-now-ms (constantly sentinel)]
        (let [out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {ka loaded kb invalidated}) :app/main)
              es  (get-in out [rf.resources.state/resources-key :entries])
              ;; rf2-9e0tyq — entries are keyed on the byte key-id.
              ea (es (rf.resources.state/key-id ka))
              eb (es (rf.resources.state/key-id kb))]
          (is (= 1000 (:loaded-at ea)) ":loaded-at is the snapshot's, NOT the install clock")
          (is (= 2000 (:stale-at  ea)) ":stale-at is the snapshot's, NOT the install clock")
          (is (= 1000 (:loaded-at eb)) ":loaded-at (invalidated entry) is the snapshot's")
          (is (= 8000 (:stale-at  eb)) ":stale-at (invalidated entry) is the snapshot's")
          (is (= 1500 (:invalidated-at eb)) ":invalidated-at is the snapshot's, NOT the install clock")
          (is (not-any? #{sentinel}
                        (mapcat (juxt :loaded-at :stale-at :invalidated-at) (vals es)))
              "NO durable entry timestamp equals the stubbed install clock — restore re-read it nowhere durable"))))))

;; ===========================================================================
;; 2. Dangle non-terminal work-ledger rows (Spec 016 §Restore part 2)
;; ===========================================================================

(defn- work-row
  [work-id status]
  (-> (rf.resources.work-ledger/work-record
        {:work-id work-id :frame-id :app/main :resource/key gkey
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
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main)
          out-ledger (get out rf.resources.state/work-ledger-key)]
      (doseq [wid [w-running w-queued w-abort]]
        ;; rf2-9e0tyq — the ledger is keyed on the byte work-id-id.
        (let [row (get out-ledger (rf.resources.work-ledger/work-id-id wid))]
          (is (= :suppressed (:status row))
              (str wid " settled to terminal :suppressed"))
          (is (rf.resources.work-ledger/terminal? (:status row))
              (str wid " is now terminal"))
          (is (= :dangling (get-in row [:outcome :reason]))
              (str wid " outcome marks it dangling")))))))

(deftest terminal-rows-ride-through-unchanged
  (testing "already-terminal work-ledger rows are not re-touched by restore"
    (let [w-done [:rf.work/resource gkey 2]
          row    (rf.resources.work-ledger/mark-terminal (work-row w-done :completed)
                                            :completed {:ok true})
          rdb (runtime-db-with {gkey (entry {:resource-id :article/by-slug
                                             :status :loaded :data {:x 1}
                                             :loaded-at 1 :stale-at 9.0e15})}
                               {w-done row})
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main)]
      (is (= row (get-in out [rf.resources.state/work-ledger-key (rf.resources.work-ledger/work-id-id w-done)]))
          "a completed row is unchanged"))))

(deftest dangle-non-terminal-work-returns-dangled-ids
  (testing "dangle-non-terminal-work! returns the dangled work-ids + leaves
            terminal rows alone"
    (let [w1 [:rf.work/resource gkey 3]
          w2 [:rf.work/resource gkey 2]
          ledger {w1 (work-row w1 :running)
                  w2 (rf.resources.work-ledger/mark-terminal (work-row w2 :completed) :completed {})}
          [rdb' dangled] (rf.resources.ssr/dangle-non-terminal-work!
                          {rf.resources.state/work-ledger-key ledger})]
      (is (= [w1] dangled) "only the non-terminal row is dangled")
      (is (= :suppressed (get-in rdb' [rf.resources.state/work-ledger-key w1 :status])))
      (is (= :completed (get-in rdb' [rf.resources.state/work-ledger-key w2 :status]))))))

;; ===========================================================================
;; 2b. Dangle PENDING mutation instances on restore (rf2-o3d1uf, part 2)
;; ===========================================================================
;;
;; A restored :pending mutation instance retains :current-work + :generation.
;; The mutation reply gate (`live-instance-for-reply`) checks the INSTANCE's
;; :current-work (NOT the resource entry's), so WITHOUT reconciling the
;; instance a late pre-restore mutation reply would still match and
;; patch/populate/invalidate post-restore resource state. The reconcile
;; terminally-settles the pending instance to :error/:dangling-on-restore and
;; clears :current-work so the gate suppresses the late reply.

(defn- mutation-instance
  "A durable mutation instance under instance-id, mirroring empty-instance +
  overrides (defaults to a :pending instance pointing at work-id)."
  [{:keys [mutation-id instance-id status generation work-id scope params]
    :or   {mutation-id :comment/add status :pending generation 3}}]
  (-> (rf.resources.mutation-runtime/empty-instance mutation-id instance-id
        {:scope scope :params params :generation generation
         :work-id work-id :started-at 1000})
      (assoc :status status)))

(defn- mutations-map
  "Build a `:rf.runtime/mutations` map keyed the runtime way — on the CEDN-1
  byte `key-id` of each instance id (rf2-8iciw8), each instance carrying its
  own kind-preserving `:instance/id`. Takes `{<instance-id> <instance>}` pairs
  (instance ids as the LOGICAL key) and rekeys to the byte storage key."
  [m]
  (into {} (map (fn [[iid inst]] [(rf.resources.mutation-runtime/instance-key-id iid) inst])) m))

(deftest instance-dangled-settles-pending-and-clears-current-work
  (testing "instance-dangled: a :pending instance settles terminal :error with
            the :dangling-on-restore envelope and CLEARS :current-work"
    (let [inst (mutation-instance {:instance-id :inst-1
                                   :work-id [:rf.work/resource [:rf.mutation :inst-1 3] 3]})
          out  (rf.resources.mutation-runtime/instance-dangled inst 9999)]
      (is (= :error (:status out)) "pending → terminal :error")
      (is (= :dangling-on-restore (:reason (:error out))))
      (is (nil? (:current-work out)) ":current-work cleared (the suppression gate)")
      (is (rf.resources.mutation-runtime/terminal? (:status out)) "the instance is now terminal")))
  (testing "a TERMINAL instance rides through unchanged"
    (let [done (mutation-instance {:instance-id :inst-2 :status :success})]
      (is (= done (rf.resources.mutation-runtime/instance-dangled done 9999))))))

(deftest dangle-pending-mutations-settles-and-returns-ids
  (testing "dangle-pending-mutations! settles every pending instance + clears
            current-work; terminal instances untouched; returns the dangled ids"
    (let [pending (mutation-instance {:instance-id :inst-p
                                      :work-id [:rf.work/resource [:rf.mutation :inst-p 3] 3]})
          settled (mutation-instance {:instance-id :inst-s :status :success})
          rdb {rf.resources.mutation-runtime/mutations-key (mutations-map {:inst-p pending :inst-s settled})}
          [rdb' dangled] (rf.resources.ssr/dangle-pending-mutations! rdb 9999)]
      (is (= [:inst-p] dangled) "only the pending instance is dangled (kind-preserving :instance/id)")
      (is (= :error (get-in rdb' [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-p) :status])))
      (is (nil? (get-in rdb' [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-p) :current-work])))
      (is (= :success (get-in rdb' [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-s) :status]))
          "the terminal instance is untouched")))
  (testing "no mutation instances → no-op"
    ;; EP-0019 Q3 — the return is now `[rdb dangled rolled-back-keys]`.
    (is (= [{} [] []] (rf.resources.ssr/dangle-pending-mutations! {} 9999)))))

(deftest reconcile-on-restore-dangles-pending-mutation-instances
  (testing "reconcile-on-restore reconciles the :rf.runtime/mutations slice too"
    (let [pending (mutation-instance {:instance-id :inst-1
                                      :work-id [:rf.work/resource [:rf.mutation :inst-1 3] 3]})
          rdb {rf.resources.mutation-runtime/mutations-key (mutations-map {:inst-1 pending})}
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main)]
      (is (= :error (get-in out [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-1) :status])))
      (is (nil? (get-in out [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-1) :current-work]))))))

(deftest dangled-instance-settled-at-is-the-restore-causal-time-not-the-live-clock
  ;; rf2-wshzsp (the option-1 CORRECTNESS fix) — a dangled-on-restore mutation
  ;; instance's durable :settled-at is a frame-state field, so per EP-0010 §Time
  ;; + §Restore/Replay it MUST be sourced from the restore's CAUSAL time (the
  ;; restored epoch's :committed-at, threaded as :restore-time-ms) — NOT the live
  ;; install clock (`now-ms`). Sourcing a durable write from an ambient read at
  ;; install is the exact shape the EP's restore clause warns against; this pins
  ;; the replay-stable source.
  (testing "the dangled instance's durable :settled-at equals the causal
            :restore-time-ms, NOT the stubbed live install clock"
    (let [restore-time 1781078400777   ; the restore's causal time (epoch :committed-at)
          live-sentinel 9999999999999  ; the (wrong) ambient install clock
          pending (mutation-instance {:instance-id :inst-1
                                      :work-id [:rf.work/resource [:rf.mutation :inst-1 3] 3]})
          rdb {rf.resources.mutation-runtime/mutations-key (mutations-map {:inst-1 pending})}]
      (with-redefs [rf.interop/epoch-now-ms (constantly live-sentinel)]
        (let [out (rf.resources.ssr/reconcile-on-restore rdb :app/main {:restore-time-ms restore-time})
              inst (get-in out [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-1)])]
          (is (= :error (:status inst)) "the pending instance is terminally dangled")
          (is (= restore-time (:settled-at inst))
              ":settled-at is the causal :restore-time-ms — replay-stable")
          (is (not= live-sentinel (:settled-at inst))
              ":settled-at is NOT the live install clock (now-ms)")))))
  (testing "fallback: with NO :restore-time-ms (the pure-unit 2-arity, no token)
            the dangle stamps the live clock — the no-causal-time path is intact"
    (let [live-sentinel 9999999999999
          pending (mutation-instance {:instance-id :inst-1
                                      :work-id [:rf.work/resource [:rf.mutation :inst-1 3] 3]})
          rdb {rf.resources.mutation-runtime/mutations-key (mutations-map {:inst-1 pending})}]
      (with-redefs [rf.interop/epoch-now-ms (constantly live-sentinel)]
        (let [out (rf.resources.ssr/reconcile-on-restore rdb :app/main)]
          (is (= live-sentinel (get-in out [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :inst-1) :settled-at]))
              "no causal time supplied → the unit path falls back to the live clock"))))))

(deftest dangling-mutation-without-restore-time-warns-loudly
  ;; rf2-nftz2s §4 — when a PENDING mutation instance is terminally dangled on
  ;; restore but NO causal :restore-time-ms was supplied, its durable :settled-at
  ;; is stamped from the live install clock — a replay-determinism hazard. The
  ;; live-clock fallback is KEPT (the pure-unit path has no causal time), but it
  ;; is no longer SILENT: a :rf.resource/restore-settled-at-from-live-clock
  ;; warning fires so a production restore that forgot to thread :restore-time-ms
  ;; is loud, not a quiet footgun (no-silent-swallow).
  (let [pending (mutation-instance {:instance-id :inst-1
                                    :work-id [:rf.work/resource [:rf.mutation :inst-1 3] 3]})
        rdb {rf.resources.mutation-runtime/mutations-key (mutations-map {:inst-1 pending})}
        recorder (fn []
                   (let [seen (atom [])
                         k    ::live-clock-warn-recorder]
                     (rf.trace.tooling/register-listener!
                       k (fn [ev] (when (= :rf.resource/restore-settled-at-from-live-clock
                                           (:operation ev))
                                    (swap! seen conj ev))))
                     [seen (fn [] (rf.trace.tooling/unregister-listener! k))]))]
    (testing "dangling a pending mutation with NO :restore-time-ms warns loudly"
      (let [[seen unregister!] (recorder)]
        (try (rf.resources.ssr/reconcile-on-restore rdb :app/main)
             (finally (unregister!)))
        (is (= 1 (count @seen))
            "exactly one restore-settled-at-from-live-clock warning fired")
        (is (= [:inst-1] (:dangled-mutations (:tags (first @seen))))
            "the warning names the dangled instance whose :settled-at is unstable")))
    (testing "supplying a causal :restore-time-ms suppresses the warning (the
              durable stamp folds the causal token — replay-stable)"
      (let [[seen unregister!] (recorder)]
        (try (rf.resources.ssr/reconcile-on-restore rdb :app/main {:restore-time-ms 1781078400777})
             (finally (unregister!)))
        (is (empty? @seen)
            "no live-clock warning when the causal restore time is threaded")))
    (testing "no warning when NO pending mutations are dangled (nothing durable
              was stamped from the live clock)"
      (let [[seen unregister!] (recorder)]
        (try (rf.resources.ssr/reconcile-on-restore {rf.resources.mutation-runtime/mutations-key {}} :app/main)
             (finally (unregister!)))
        (is (empty? @seen)
            "an empty restore stamps no durable :settled-at, so no warning")))))

(deftest late-pre-restore-mutation-reply-is-suppressed-end-to-end
  (rf/reg-resource :article/by-slug
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags    (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (testing "ADVERSARIAL acceptance (rf2-o3d1uf): a pre-restore mutation success
            reply that lands AFTER restore is SUPPRESSED — it does NOT patch /
            populate / invalidate the post-restore resource entry"
    (let [fid       :restore/mutation-frame
          ;; a loaded resource entry the (pre-restore) mutation would have patched
          loaded    (entry {:resource-id :article/by-slug :status :loaded
                            :data {:title "post-restore"} :loaded-at 1 :stale-at 9.0e15
                            :generation 9})
          instance-id [:rf.mutation/instance :article/edit 3]
          work-id   (rf.resources.work-ledger/resource-work-id [:rf.mutation instance-id] 3)
          ;; the captured snapshot: a PENDING mutation instance pointing at work-id
          pending   (mutation-instance {:mutation-id :article/edit
                                        :instance-id instance-id
                                        :generation 3 :work-id work-id
                                        :scope :rf.scope/global :params {:slug "x"}})
          ;; the runtime-db that restore is about to install (reconciled first)
          snapshot  (-> (runtime-db-with {gkey loaded})
                        (assoc rf.resources.mutation-runtime/mutations-key (mutations-map {instance-id pending}))
                        (assoc-in (rf.resources.work-ledger/record-path work-id)
                                  (-> (rf.resources.work-ledger/work-record
                                        {:work-id work-id :frame-id fid
                                         :resource/key [:rf.mutation instance-id]
                                         :generation 3 :transport :rf.http/managed
                                         :started-at 1000})
                                      (assoc :work/kind :mutation))))
          reconciled (rf.resources.ssr/reconcile-on-restore snapshot fid)]
      (rf/make-frame {:id fid :doc "restore mutation suppression frame"})
      ;; install the reconciled snapshot as the live frame-state
      (rf.frame/replace-runtime-db! fid reconciled)
      (testing "post-reconcile the pending instance is terminal + current-work cleared"
        (is (= :error (get-in reconciled [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id instance-id) :status])))
        (is (nil? (get-in reconciled [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id instance-id) :current-work]))))
      ;; NOW the late pre-restore mutation SUCCESS reply lands (carrying the
      ;; pre-restore work-id + generation that the snapshot instance held).
      (rf/dispatch-sync
        [:rf.mutation.internal/succeeded
         {:instance-id instance-id :mutation-id :article/edit
          :work/id work-id :generation 3 :scope :rf.scope/global}
         {:status :ok :value {:title "STALE pre-restore write"}}]
        {:frame fid})
      (let [post (rf.frame/frame-runtime-db-value fid)
            e    (get-in post [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey)])]
        (is (= {:title "post-restore"} (:data e))
            "the resource entry is UNCHANGED — the stale reply did not patch/populate it")
        (is (= :error (get-in post [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id instance-id) :status]))
            "the dangled instance stays terminal :error — the stale reply did not revive it"))
      (rf.frame/destroy-frame! fid))))

;; ===========================================================================
;; 3. Owner reconciliation: orphan SSR, keep route (Spec 016 §Restore part 4)
;; ===========================================================================

(deftest restore-orphans-ssr-owners-keeps-live-nav-route-owners
  (testing "SSR owners orphan on restore (a settled server render); a route
            owner the restored routing slice names LIVE rides through (along
            with machine / app owners) to its own subsystem reconcile"
    (let [e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15
                      :owners #{[:ssr "req-9" "nav-1"]
                                [:route :route/article "nav-1"]
                                [:machine :checkout/flow "inst-1"]}})
          ;; the restored routing slice considers nav-1 live — the route owner
          ;; names the same nav-token, so it revives (Spec 016 §Restore part 4).
          rdb (with-live-nav-token (runtime-db-with {gkey e}) "nav-1")
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main)
          owners (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey) :active-owners])]
      (is (not (contains? owners [:ssr "req-9" "nav-1"])) "SSR owner orphaned")
      (is (contains? owners [:route :route/article "nav-1"])
          "live-nav route owner survives (its nav-token is the one routing names live)")
      (is (contains? owners [:machine :checkout/flow "inst-1"]) "machine owner survives")
      (is (not (contains? (get-in out [rf.resources.state/resources-key :owner-index])
                          [:ssr "req-9" "nav-1"]))
          "the orphaned SSR owner is absent from the recomputed owner-index")
      (is (contains? (get-in out [rf.resources.state/resources-key :owner-index])
                     [:route :route/article "nav-1"])
          "the live-nav route owner IS in the recomputed owner-index"))))

(deftest restore-orphans-stale-nav-route-owners
  (testing "a route owner whose nav-token is NOT the one the restored routing
            slice considers live is released as a stale-nav orphan (Spec 016
            §Restore part 4) — it must not pin its entry alive forever"
    (let [stale [:route :route/article "nav-OLD"]
          live  [:route :route/article "nav-NEW"]
          e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15
                      :owners #{stale live [:machine :checkout/flow "inst-1"]}})
          ;; the restored routing slice considers nav-NEW live; nav-OLD is the
          ;; navigation the timeline has already left.
          rdb (with-live-nav-token (runtime-db-with {gkey e}) "nav-NEW")
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main)
          owners (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey) :active-owners])]
      (is (not (contains? owners stale))
          "the stale-nav route owner (nav-OLD ≠ live nav-NEW) is orphaned")
      (is (contains? owners live)
          "the live-nav route owner (nav-NEW = live) survives")
      (is (contains? owners [:machine :checkout/flow "inst-1"])
          "the machine owner is untouched")
      (is (not (contains? (get-in out [rf.resources.state/resources-key :owner-index]) stale))
          "the orphaned stale-nav route owner is absent from the recomputed owner-index")
      (is (contains? (get-in out [rf.resources.state/resources-key :owner-index]) live)
          "the surviving live-nav route owner is present in the owner-index"))))

;; rf2-64bdnk — on RESTORE, a missing/nil live nav-token means NO route owner
;; is live (the OPPOSITE of hydration, where a nil token is "can't compare
;; yet"). Spec 016 §Restore part 4: route owners revive ONLY IF the restored
;; routing names the same live nav-token; absent routing slice / no :current /
;; nil nav-token → every route owner orphans.

(deftest restore-orphans-route-owners-when-no-live-nav-token
  (testing "rf2-64bdnk — restore with no live nav-token orphans ALL route
            owners across the three absent-token shapes; machine/app owners
            survive; the owner-index is recomputed without the orphans"
    (doseq [[label rdb-fn]
            [["absent routing slice"
              (fn [rdb] rdb)] ;; runtime-db-with carries no :rf.runtime/routing
             ["routing slice without :current"
              (fn [rdb] (assoc rdb :rf.runtime/routing {:pending-navigation {:x 1}}))]
             ["routing :current with nil nav-token"
              (fn [rdb] (assoc-in rdb [:rf.runtime/routing :current :nav-token] nil))]]]
      (testing label
        (let [route-owner [:route :route/article "nav-anything"]
              e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                          :loaded-at 1 :stale-at 9.0e15
                          :owners #{[:ssr "req-9" "nav-1"]
                                    route-owner
                                    [:machine :checkout/flow "inst-1"]
                                    [:app :dashboard 7]}})
              out (rf.resources.ssr/reconcile-on-restore (rdb-fn (runtime-db-with {gkey e})) :app/main)
              owners (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey) :active-owners])]
          (is (not (contains? owners route-owner))
              "the route owner is ORPHANED (no live nav-token names it live)")
          (is (not (contains? owners [:ssr "req-9" "nav-1"])) "SSR owner orphaned")
          (is (contains? owners [:machine :checkout/flow "inst-1"]) "machine owner survives")
          (is (contains? owners [:app :dashboard 7]) "app owner survives")
          (is (not (contains? (get-in out [rf.resources.state/resources-key :owner-index]) route-owner))
              "the orphaned route owner is absent from the recomputed owner-index"))))))

(deftest restore-no-live-token-emits-owner-release-trace
  (testing "rf2-64bdnk — a route owner released because no live nav-token names
            it emits a :rf.resource/owner-released trace row (Spec 016 part 4)"
    (let [route-owner [:route :route/article "nav-X"]
          e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15 :owners #{route-owner}})
          seen (atom [])
          k    ::owner-release-recorder]
      (rf.trace.tooling/register-listener!
        k (fn [ev] (when (= :rf.resource/owner-released (:operation ev))
                     (swap! seen conj ev))))
      (try
        (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
        (finally (rf.trace.tooling/unregister-listener! k)))
      (is (some #(= route-owner (:owner (:tags %))) @seen)
          "an owner-released row names the orphaned route owner")
      (is (some #(= :stale-nav-orphan (:reason (:tags %))) @seen)
          "the release reason is the stale-nav/no-live-token orphan"))))

;; ===========================================================================
;; 3c. Defer restore trace rows until install succeeds (rf2-obi8rr)
;; ===========================================================================
;;
;; `reconcile-on-restore` runs INSIDE perform-restore! BEFORE the atomic
;; install, which can still fail (a destroyed-frame install returns nil). So
;; under `:defer-traces? true` the reconcile must NOT emit its
;; :rf.resource/restored / :rf.resource/owner-released success rows inline —
;; they ride back as metadata and are emitted by commit-restore-reconcile-
;; traces! only after the install succeeds. The inline (2-arity) path keeps
;; emitting (the pure unit path has no install to gate against). The
;; end-to-end "failed install emits nothing" assertion lives in the epoch
;; artefact's epoch_test.clj (it needs a real destroyed-frame restore).

(defn- restore-trace-recorder
  "Register a trace listener recording every :rf.resource/restored +
  :rf.resource/owner-released op, returning [seen-atom unregister-fn]."
  []
  (let [seen (atom [])
        k    ::restore-trace-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (contains? #{:rf.resource/restored :rf.resource/owner-released}
                                  (:operation ev))
                   (swap! seen conj ev))))
    [seen (fn [] (rf.trace.tooling/unregister-listener! k))]))

(deftest defer-traces-does-not-emit-inline
  (testing "rf2-obi8rr — reconcile-on-restore with :defer-traces? true emits NO
            :rf.resource/restored / :rf.resource/owner-released rows inline; they
            ride back as metadata for the post-install commit"
    (let [stale [:route :route/article "nav-OLD"]
          e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15 :owners #{stale}})
          rdb (with-live-nav-token (runtime-db-with {gkey e}) "nav-NEW")
          [seen unregister!] (restore-trace-recorder)
          out (try (rf.resources.ssr/reconcile-on-restore rdb :app/main {:defer-traces? true})
                   (finally (unregister!)))]
      (is (empty? @seen)
          "no restore/owner-released trace fired inline under :defer-traces? true")
      (is (seq (-> out meta (get :re-frame.resources.ssr/deferred-trace-intents)))
          "the trace intents ride back as metadata on the reconciled runtime-db")
      ;; the reconcile work still happened (the stale owner was orphaned)
      (is (not (contains? (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey) :active-owners]) stale))
          "the stale-nav owner is still reconciled (only the TRACE is deferred)"))))

(deftest commit-restore-reconcile-traces-emits-deferred-rows
  (testing "rf2-obi8rr — commit-restore-reconcile-traces! emits the deferred
            :rf.resource/restored + :rf.resource/owner-released rows from the
            reconciled runtime-db's metadata"
    (let [stale [:route :route/article "nav-OLD"]
          e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15 :owners #{stale}})
          rdb (with-live-nav-token (runtime-db-with {gkey e}) "nav-NEW")
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main {:defer-traces? true})
          [seen unregister!] (restore-trace-recorder)]
      (try (rf.resources.ssr/commit-restore-reconcile-traces! out)
           (finally (unregister!)))
      (is (some #(= :rf.resource/restored (:operation %)) @seen)
          "the deferred :rf.resource/restored summary row is emitted on commit")
      (is (some #(and (= :rf.resource/owner-released (:operation %))
                      (= stale (:owner (:tags %)))) @seen)
          "the deferred per-owner :rf.resource/owner-released row is emitted on commit"))))

(deftest commit-restore-reconcile-traces-noop-without-intents
  (testing "rf2-obi8rr — commit-restore-reconcile-traces! is a no-op on a value
            carrying no deferred intents (an inline reconcile, or resource-free)"
    (let [[seen unregister!] (restore-trace-recorder)]
      (try
        ;; a plain runtime-db (no deferred-intents metadata) → nothing emitted
        (rf.resources.ssr/commit-restore-reconcile-traces! (runtime-db-with {}))
        (rf.resources.ssr/commit-restore-reconcile-traces! nil)
        (finally (unregister!)))
      (is (empty? @seen) "no intents → no trace rows"))))

(deftest inline-reconcile-still-emits-restored-trace
  (testing "rf2-obi8rr — the 1-/2-arity (direct unit) path still emits inline:
            no install to gate against"
    (let [e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15})
          [seen unregister!] (restore-trace-recorder)]
      (try (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) :app/main)
           (finally (unregister!)))
      (is (some #(= :rf.resource/restored (:operation %)) @seen)
          "the inline path emits the :rf.resource/restored summary immediately"))))

(deftest commit-hook-published
  (testing "rf2-obi8rr — the :resources/commit-restore-reconcile! hook is published"
    (is (some? (rf.late-bind/get-fn :resources/commit-restore-reconcile!)))
    (is (= rf.resources.ssr/commit-restore-reconcile-traces!
           (rf.late-bind/get-fn :resources/commit-restore-reconcile!)))))

(deftest hydration-parity-route-owners-ride-through-without-routing
  (testing "rf2-64bdnk parity — HYDRATION (the no-comparison-yet case) still
            rides route owners through unchanged when there is no client routing
            slice (the split: nil-token-on-hydrate ≠ nil-token-on-restore)"
    (let [route-owner [:route :route/article "nav-anything"]
          e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15
                      :owners #{[:ssr "req-9" "nav-1"] route-owner}})
          out (rf.resources.ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          owners (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey) :active-owners])]
      (is (not (contains? owners [:ssr "req-9" "nav-1"]))
          "SSR owner still orphans on hydration")
      (is (contains? owners route-owner)
          "route owner RIDES THROUGH on hydration (routing's client subsystem reconciles it)"))))

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
          ;; name nav-1 live so the route owner SURVIVES (rf2-64bdnk) — this
          ;; test pins the index RECOMPUTE, not owner orphaning; without a live
          ;; token the route owner would now correctly orphan.
          ;; rf2-9e0tyq — build via runtime-db-with (byte-keyed + :resource/key
          ;; stamped), then overwrite the indexes with deliberately-wrong
          ;; snapshot values to prove they are discarded + recomputed.
          rdb (-> (runtime-db-with {gkey e})
                  (assoc-in [rf.resources.state/resources-key :tag-index]   {[:bogus] #{:nope}})
                  (assoc-in [rf.resources.state/resources-key :owner-index] {[:bogus] #{:nope}})
                  (with-live-nav-token "nav-1"))
          out (rf.resources.ssr/reconcile-on-restore rdb :app/main)
          sub (get out rf.resources.state/resources-key)]
      ;; rf2-9e0tyq — index MEMBERS are the byte key-id, not the scoped-key vector.
      (is (= {[:article "x"] #{(rf.resources.state/key-id gkey)}} (:tag-index sub))
          "tag-index recomputed from the entry's :tags; bogus snapshot index discarded")
      (is (= {[:route :route/article "nav-1"] #{(rf.resources.state/key-id gkey)}} (:owner-index sub))
          "owner-index recomputed from the entry's owners; bogus snapshot index discarded"))))

;; ===========================================================================
;; 4b. Clear host transients on restore (rf2-nd1r9q, part 5)
;; ===========================================================================
;;
;; Host side tables (stale/GC timer handles + work-ledger host handles) belong
;; to the PRE-restore timeline and are NOT frame-state, so the wholesale
;; install does not touch them. restore-reconcile must clear them for the
;; restored frame so a stale timer / abandoned in-flight handle cannot fire
;; against the restored state — WITHOUT rewinding the generation high-water
;; mark or re-binding the revalidation listeners.

(defn- frame-timer-keys
  "The timer-table keys (`[frame-id resource-key kind]`) armed for `frame-id`."
  [frame-id]
  (filter (fn [[fid _ _]] (= fid frame-id)) (keys @rf.resources.timers/timer-table)))

(defn- work-handle-keys
  "The work-ledger handle-table keys (`[frame-id work-id]`) for `frame-id`."
  [frame-id]
  (filter (fn [[fid _]] (= fid frame-id)) (keys @rf.resources.work-ledger/handle-table)))

(deftest clear-host-transients-on-restore-clears-timers-and-handles
  (testing "rf2-nd1r9q — restore clears the frame's armed stale/GC timer handles
            and work-ledger host handles (host transients, not frame-state)"
    (let [fid :restore/transients
          rkey gkey
          wid  [:rf.work/resource gkey 4]]
      ;; arm a stale timer (long delay so it never fires during the test) and a
      ;; work-ledger host handle for the frame.
      (rf.resources.timers/schedule! fid rkey rf.resources.timers/stale-kind 600000)
      (rf.resources.work-ledger/put-handle! fid wid {:transport :rf.http/managed :request-id wid})
      (is (seq (frame-timer-keys fid)) "a stale timer is armed for the frame")
      (is (seq (work-handle-keys fid)) "a work handle is recorded for the frame")
      ;; restore the frame's snapshot (a mid-flight fetching entry)
      (let [e (entry {:resource-id :article/by-slug :status :fetching :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15 :current-work wid})]
        (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid))
      (is (empty? (frame-timer-keys fid))
          "the frame's stale/GC timer handles are GONE after restore")
      (is (empty? (work-handle-keys fid))
          "the frame's work-ledger host handles are GONE after restore")
      ;; cleanup any stray timers (none expected)
      (rf.resources.timers/cancel-for-key! fid rkey))))

(deftest restore-host-clear-preserves-generation-high-water
  (testing "rf2-nd1r9q — clearing host transients on restore does NOT rewind the
            generation high-water mark (part 1 — it must stay monotonic)"
    (let [fid :restore/gen-preserve]
      (rf.resources.state/commit-generation! fid 11)
      (rf.resources.timers/schedule! fid gkey rf.resources.timers/stale-kind 600000)
      (let [e (entry {:resource-id :article/by-slug :status :fetching :data {:x 1}
                      :loaded-at 1 :stale-at 9.0e15 :current-work [:rf.work/resource gkey 5]})]
        (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid))
      (is (= 11 (rf.resources.state/generation-snapshot fid))
          "the host-side generation high-water mark is UNTOUCHED by the host-transient clear")
      (rf.resources.timers/cancel-for-key! fid gkey))))

(deftest restore-host-clear-triggers-no-eager-refetch
  (testing "rf2-nd1r9q — restore clears transients but arms NO eager refetch /
            timer (scheduling re-arms lazily on the next live-owner touch)"
    (let [fid :restore/no-eager
          e   (entry {:resource-id :article/by-slug :status :loaded
                      :data {:x 1} :loaded-at 1 :stale-at 2})] ;; stale
      (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid)
      (is (empty? (frame-timer-keys fid))
          "no stale/GC timer is armed by restore (lazy re-arm on next ensure)")
      (is (empty? (work-handle-keys fid))
          "no work handle is created by restore (no eager refetch)"))))

(deftest clear-host-transients-on-restore-is-pure-subset
  (testing "rf2-nd1r9q — clear-host-transients-on-restore! clears timers + work
            handles but is a no-op on an unarmed frame (idempotent)"
    (is (nil? (rf.resources.ssr/clear-host-transients-on-restore! :restore/unarmed)))))

(deftest reconcile-host-transient-clear-fenced-to-exact-incarnation
  (testing "rf2-qfrh4 seam 2 — the pre-write host-transient clear fires only when
            the threaded :owner-token still names the live incarnation. A STALE
            token (a churned / destroyed incarnation) SKIPS the clear so a same-id
            successor's armed host handles are spared — the reconcile runs BEFORE
            the atomic write and addresses the frame by bare id, so without this
            fence a callback that churned A to B mid-reconcile would release B's
            live handles. A LIVE token clears as before; a nil token (the
            pure-unit path) clears unconditionally."
    (let [fid  :restore/fence
          rkey gkey
          wid  [:rf.work/resource gkey 7]
          arm! (fn []
                 (rf.resources.timers/schedule! fid rkey rf.resources.timers/stale-kind 600000)
                 (rf.resources.work-ledger/put-handle! fid wid {:transport :rf.http/managed :request-id wid}))
          e    (entry {:resource-id :article/by-slug :status :fetching :data {:x 1}
                       :loaded-at 1 :stale-at 9.0e15 :current-work wid})]
      (rf/make-frame {:id fid})
      (let [live-token  (rf.frame/frame-incarnation-token fid)
            ;; a unique reference that never names fid's live incarnation — an
            ;; incarnation token is a `:drain-lock` atom, so a fresh atom stands
            ;; in for a destroyed / successor incarnation's token (cross-host:
            ;; `(Object.)` is not a CLJS constructor).
            stale-token (atom :stale-incarnation)]
        ;; STALE token — the clear is fenced out; armed handles survive.
        (arm!)
        (is (seq (frame-timer-keys fid)) "armed a stale timer for the frame")
        (is (seq (work-handle-keys fid)) "recorded a work handle for the frame")
        (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid {:owner-token stale-token})
        (is (seq (frame-timer-keys fid))
            "a stale incarnation token SKIPS the timer clear (successor's handle spared)")
        (is (seq (work-handle-keys fid))
            "a stale incarnation token SKIPS the work-handle clear")
        ;; LIVE token — clears as before.
        (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid {:owner-token live-token})
        (is (empty? (frame-timer-keys fid)) "the live incarnation token clears the timer")
        (is (empty? (work-handle-keys fid)) "the live incarnation token clears the work handle")
        ;; nil token (pure-unit path) — clears unconditionally, as before.
        (arm!)
        (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid {:owner-token nil})
        (is (empty? (frame-timer-keys fid)) "nil token clears the timer unconditionally")
        (is (empty? (work-handle-keys fid)) "nil token clears the work handle unconditionally")
        (rf.resources.timers/cancel-for-key! fid rkey)))))

;; ---- rf2-sdeae — the clear itself is a CALLBACK-BEARING fan-out ------------
;;
;; The seam-2 fence above answers "may this clear run at all?" ONCE, before the
;; fan-out. But the fan-out is not callback-free: `rf.resources.work-ledger/release-frame!`
;; best-effort ABORTS each slot on the way out, and an abort callback is app /
;; transport code that can churn incarnation A to a same-id successor B and let
;; B record a handle in the SAME `[frame-id work-id]` slot. Read-abort-dissoc
;; therefore dissociates a slot the callback has already re-seated: A's returning
;; cleanup deletes B's live handle.
;;
;; The fix is ordering, not another gate — DETACH the frame's slots atomically
;; FIRST (one `swap-vals!`, no callback window before it), then abort the
;; DETACHED handle values. Callbacks then act on detached attempt identities and
;; can neither be re-read nor dissociated by A's tail. This is the discipline
;; `rf.resources.timers/release-frame!` already uses for the sibling timer table.

(deftest work-ledger-release-frame-detaches-before-abort-callbacks
  (testing "rf2-sdeae — an abort callback that seats a same-id successor B in the
            SAME [frame-id work-id] slot must not have B's handle deleted by A's
            returning cleanup. The abort runs on the DETACHED A handle; the slot
            B re-seats survives byte-for-byte."
    (let [fid       :restore/detach
          wid       [:rf.work/resource gkey 11]
          b-handle  {:transport :rf.http/managed :request-id [:rf.req fid wid] :owner :B}
          aborted   (atom [])]
      ;; A's slot carries an abort callback that — synchronously, at the exact
      ;; seam between the table read and the dissoc — seats successor B's handle
      ;; in the very slot key A is about to drop.
      (rf.resources.work-ledger/put-handle! fid wid
        {:transport :rf.http/direct
         :owner     :A
         :abort-fn  (fn [reason]
                      (swap! aborted conj reason)
                      (rf.resources.work-ledger/put-handle! fid wid b-handle))})
      (rf.resources.work-ledger/release-frame! fid)
      (is (= [:resource-superseded] @aborted)
          "A's abort callback fired exactly once")
      (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid))
          "successor B's re-seated handle survives byte-for-byte (not dissoc'd by A's tail)")
      (rf.resources.work-ledger/clear-handle! fid wid))))

(deftest work-ledger-release-frame-abort-callback-reentrancy
  (testing "rf2-sdeae adversarial (nested fan-out) — an abort callback that
            RE-ENTERS release-frame! for the same frame terminates, aborts each
            A slot exactly once (the outer pass already detached them), and still
            spares a successor handle seated by the nested pass."
    (let [fid      :restore/reentrant
          wid-1    [:rf.work/resource gkey 21]
          wid-2    [:rf.work/resource gkey 22]
          b-handle {:transport :rf.http/managed :owner :B}
          aborted  (atom [])]
      (rf.resources.work-ledger/put-handle! fid wid-1
        {:owner :A :abort-fn (fn [_]
                               (swap! aborted conj :a1)
                               ;; nested fan-out over the same frame
                               (rf.resources.work-ledger/release-frame! fid)
                               ;; …then B seats a handle in a slot the OUTER
                               ;; pass still holds detached.
                               (rf.resources.work-ledger/put-handle! fid wid-2 b-handle))})
      (rf.resources.work-ledger/put-handle! fid wid-2
        {:owner :A :abort-fn (fn [_] (swap! aborted conj :a2))})
      (rf.resources.work-ledger/release-frame! fid)
      (is (= 1 (count (filter #{:a1} @aborted))) "A's slot 1 aborted exactly once")
      (is (= 1 (count (filter #{:a2} @aborted))) "A's slot 2 aborted exactly once")
      (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid-2))
          "the successor handle seated during the nested fan-out survives")
      (rf.resources.work-ledger/clear-handle! fid wid-2))))

(deftest work-ledger-abort-callback-may-drop-its-own-slot
  (testing "rf2-sdeae adversarial (callback drops its own listener mid-cleanup) —
            an abort callback that clears its OWN slot and then seats a successor
            handle under the same key leaves the successor intact; the detached
            identity the callback ran against is never written back."
    (let [fid      :restore/self-drop
          wid      [:rf.work/resource gkey 31]
          b-handle {:transport :rf.http/managed :owner :B}]
      (rf.resources.work-ledger/put-handle! fid wid
        {:owner :A :abort-fn (fn [_]
                               (rf.resources.work-ledger/clear-handle! fid wid)
                               (rf.resources.work-ledger/put-handle! fid wid b-handle))})
      (rf.resources.work-ledger/release-frame! fid)
      (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid))
          "the successor handle the callback seated after self-clearing survives")
      (rf.resources.work-ledger/clear-handle! fid wid))))

(deftest opportunistic-abort-detaches-before-abort-callback
  (testing "rf2-sdeae — the single-slot public abort has the same seam: the abort
            callback runs BEFORE the drop, so a successor handle re-seated under
            the same key must not be deleted by the returning clear."
    (let [fid      :restore/one-slot
          wid      [:rf.work/resource gkey 41]
          b-handle {:transport :rf.http/managed :owner :B}]
      (rf.resources.work-ledger/put-handle! fid wid
        {:owner :A :abort-fn (fn [_] (rf.resources.work-ledger/put-handle! fid wid b-handle))})
      (is (true? (rf.resources.work-ledger/opportunistic-abort! fid wid))
          "an abort capability was found and fired")
      (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid))
          "successor B's re-seated handle survives the returning clear")
      (rf.resources.work-ledger/clear-handle! fid wid))))

;; ---- rf2-sdeae — the deferred trace commit is a callback fan-out too -------

(deftest commit-restore-reconcile-traces-fenced-per-intent
  (testing "rf2-sdeae — committing the deferred restore intents fans out to trace
            listeners, each a callback boundary that can destroy A and seat a
            same-id successor B. With the captured :owner-token carried in, the
            commit STOPS at the first intent whose listener lost the incarnation
            rather than announcing A's restore against B."
    (let [fid   :restore/commit-fence
          stale [:route :route/article "nav-OLD"]
          e     (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                        :loaded-at 1 :stale-at 9.0e15 :owners #{stale}})
          rdb   (with-live-nav-token (runtime-db-with {gkey e}) "nav-NEW")]
      (rf/make-frame {:id fid})
      (let [token (rf.frame/frame-incarnation-token fid)
            out   (rf.resources.ssr/reconcile-on-restore rdb fid {:defer-traces? true :owner-token token})
            intents (-> out meta (get :re-frame.resources.ssr/deferred-trace-intents))
            [seen unregister!] (restore-trace-recorder)
            churned? (atom false)]
        (is (< 1 (count intents))
            "the reconcile deferred MORE than one intent (a genuine fan-out)")
        ;; the FIRST emitted intent's listener destroys A and seats successor B
        (rf.trace.tooling/register-listener! ::sdeae-churn
          (fn [ev]
            (when (and (not @churned?)
                       (contains? #{:rf.resource/restored :rf.resource/owner-released}
                                  (:operation ev)))
              (reset! churned? true)
              (rf/destroy-frame! fid)
              (rf/make-frame {:id fid}))))
        (try
          (rf.resources.ssr/commit-restore-reconcile-traces! out fid {:owner-token token})
          (finally
            (rf.trace.tooling/unregister-listener! ::sdeae-churn)
            (unregister!)))
        (is (true? @churned?) "the first intent's listener churned A to B")
        (is (= 1 (count @seen))
            "the remaining A-owned intents are STOPPED once the incarnation is lost")))))

(deftest commit-restore-reconcile-traces-unfenced-arities-emit-all
  (testing "rf2-sdeae control — with no token (the pure-unit path) or a LIVE
            incarnation, every deferred intent still commits, as before."
    (let [fid   :restore/commit-live
          stale [:route :route/article "nav-OLD"]
          e     (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                        :loaded-at 1 :stale-at 9.0e15 :owners #{stale}})
          mk    (fn [] (rf.resources.ssr/reconcile-on-restore
                         (with-live-nav-token (runtime-db-with {gkey e}) "nav-NEW")
                         fid {:defer-traces? true}))]
      (rf/make-frame {:id fid})
      (let [token (rf.frame/frame-incarnation-token fid)
            n     (count (-> (mk) meta (get :re-frame.resources.ssr/deferred-trace-intents)))]
        (let [[seen unregister!] (restore-trace-recorder)]
          (try (rf.resources.ssr/commit-restore-reconcile-traces! (mk))
               (finally (unregister!)))
          (is (= n (count @seen)) "the 1-arity commits every intent unconditionally"))
        (let [[seen unregister!] (restore-trace-recorder)]
          (try (rf.resources.ssr/commit-restore-reconcile-traces! (mk) fid {:owner-token token})
               (finally (unregister!)))
          (is (= n (count @seen)) "a LIVE incarnation commits every intent"))
        (let [[seen unregister!] (restore-trace-recorder)]
          (try (rf.resources.ssr/commit-restore-reconcile-traces! (mk) fid {:owner-token nil})
               (finally (unregister!)))
          (is (= n (count @seen)) "a nil token commits every intent, as before"))))))

;; ===========================================================================
;; 5. The generation allocator is monotonic across restore (part 1)
;; ===========================================================================

(deftest generation-allocator-monotonic-across-restore
  (testing "the host-side generation allocator is NOT frame-state — restore
            cannot rewind it, so a post-restore allocation strictly exceeds any
            pre-restore generation (anti-recycling, part 1)"
    ;; Pre-restore: the live timeline minted up to generation 7.
    (rf.resources.state/commit-generation! :app/main 7)
    ;; The captured snapshot is from an earlier epoch where generation was 3.
    ;; reconcile-on-restore touches ONLY durable frame-state — never the host
    ;; allocator — so the high-water mark stays at 7.
    (let [snapshot-entry (entry {:resource-id :article/by-slug :status :fetching
                                 :data {:x 1} :loaded-at 1 :stale-at 9.0e15
                                 :generation 3
                                 :current-work [:rf.work/resource gkey 3]})]
      (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey snapshot-entry}) :app/main)
      (is (= 7 (rf.resources.state/generation-snapshot :app/main))
          "restore did not rewind the host-side allocator")
      ;; the next allocation strictly exceeds the pre-restore generation 7 AND
      ;; the restored snapshot's generation 3 → a pre-restore reply carrying
      ;; generation 3 can never match a freshly-minted live entry.
      (is (= 8 (rf.resources.state/next-generation (rf.resources.state/generation-snapshot :app/main)))
          "the next minted generation strictly exceeds every pre-restore generation"))))

;; ===========================================================================
;; 6. Restore does not eagerly refetch / no-op cases / hook published
;; ===========================================================================

(deftest restore-does-not-eagerly-refetch
  (testing "reconcile-on-restore settles entries but issues NO refetch fx —
            freshness is a later live-owner decision (part 3)"
    (let [stale (entry {:resource-id :article/by-slug :status :loaded
                        :data {:x 1} :loaded-at 1 :stale-at 2})  ;; stale
          out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey stale}) :app/main)]
      ;; the reconcile returns a runtime-db, never an fx vector / dispatch plan
      (is (map? out))
      (is (contains? out rf.resources.state/resources-key))
      (is (= {:x 1} (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey) :data]))
          "the stale entry keeps its data; restore double-fetches nothing"))))

(deftest restore-noop-without-resources
  (testing "a runtime-db with no resource entries AND no work-ledger rows is
            returned unchanged (a resource-free restore)"
    (let [rdb {:rf.runtime/machines {:snapshots {}}}]
      (is (= rdb (rf.resources.ssr/reconcile-on-restore rdb :app/main))))))

(deftest restore-never-crosses-scopes
  (testing "entries under different scopes stay isolated through the restore reconcile"
    (let [ka (rf.resources.state/scoped-resource-key [:rf.scope/session {:user "a"}] :article/by-slug {:slug "x"})
          kb (rf.resources.state/scoped-resource-key [:rf.scope/session {:user "b"}] :article/by-slug {:slug "x"})
          ea (entry {:resource-id :article/by-slug :status :fetching :data {:owner "a"}
                     :loaded-at 1 :stale-at 9.0e15 :tags #{[:article "x"]}
                     :current-work [:rf.work/resource ka 2]})
          eb (entry {:resource-id :article/by-slug :status :loaded :data {:owner "b"}
                     :loaded-at 1 :stale-at 9.0e15 :tags #{[:article "x"]}})
          out (rf.resources.ssr/reconcile-on-restore (runtime-db-with {ka ea kb eb}) :app/main)
          es  (get-in out [rf.resources.state/resources-key :entries])]
      ;; rf2-9e0tyq — entries + tag-index members are keyed on the byte key-id.
      (is (= {:owner "a"} (:data (es (rf.resources.state/key-id ka)))) "scope-a data stays under scope-a's key")
      (is (= :loaded (:status (es (rf.resources.state/key-id ka)))) "scope-a fetching → loaded (kept data)")
      (is (= {:owner "b"} (:data (es (rf.resources.state/key-id kb)))) "scope-b data stays under scope-b's key")
      (is (= #{(rf.resources.state/key-id ka) (rf.resources.state/key-id kb)}
             (get-in out [rf.resources.state/resources-key :tag-index [:article "x"]]))
          "the shared tag maps to both scoped keys, never collapsed"))))

(deftest reconcile-on-restore-hook-published
  (testing "the :resources/reconcile-on-restore hook is published by the façade"
    (is (some? (rf.late-bind/get-fn :resources/reconcile-on-restore)))
    (is (= rf.resources.ssr/reconcile-on-restore
           (rf.late-bind/get-fn :resources/reconcile-on-restore)))))
