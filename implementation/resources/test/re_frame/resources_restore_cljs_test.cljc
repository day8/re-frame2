(ns re-frame.resources-restore-cljs-test
  "Epoch-restore reconcile for the Resources artefact (Spec 016 §Restore and
  replay parts 2/4/5 — EP-0003 §9 restore conformance fixtures).

  Epoch restore installs the UNPROJECTED captured snapshot wholesale, so
  `reconcile-on-restore` does everything the SSR hydrate reconcile does
  (recompute the reverse indexes, orphan SSR owners, clear `:current-work`)
  PLUS settle every mid-flight entry to its last STABLE status and record every
  non-terminal work-ledger row and pending mutation as DANGLING, so a
  pre-restore in-flight reply is suppressed by the ordinary work-id +
  generation check. Restore never eagerly refetches, never rewinds the
  host-side generation allocator, and never re-reads the live clock for a
  durable field."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.interop :as rf.interop]
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
  "A runtime-db carrying `entries` (given as `{scoped-key entry}`) and an
  optional work `ledger` (given as `{work-id record}`), re-keyed to the
  runtime's byte-identity shapes."
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
  "Install a restored routing slice naming `nav-token` as live — the token the
  restore's owner reconcile compares route owners against (Spec 016 §Restore
  and replay part 4)."
  [runtime-db nav-token]
  (assoc-in runtime-db [:rf.runtime/routing :current :nav-token] nav-token))

(def ^:private gkey
  (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"}))

(defn- loaded-entry [owners]
  (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
          :loaded-at 1 :stale-at 9.0e15 :owners owners}))

(defn- restored-entry
  "The reconciled entry for `gkey` in `runtime-db`."
  [runtime-db]
  (get-in runtime-db [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey)]))

;; ===========================================================================
;; 1. Settle entries to last stable (Spec 016 §Restore part 2)
;; ===========================================================================

(deftest restored-entries-settle-to-last-stable
  ;; A restored in-flight entry settles to its last stable status, keeping what
  ;; it last knew; an already-stable one keeps its status. Either way the
  ;; vanished :current-work pointer is cleared.
  (doseq [[label fields [status data error]]
          [["a :loading entry that never loaded → :idle, never stranded :loading"
            {:status :loading :data nil :current-work [:rf.work/resource gkey 3]}
            [:idle nil nil]]
           ["a :fetching entry → :loaded, keeping its last-known-good data"
            {:status :fetching :data {:title "kept"} :loaded-at 1000 :stale-at 9.0e15
             :current-work [:rf.work/resource gkey 4]}
            [:loaded {:title "kept"} nil]]
           ["a :loading entry carrying a first-load :error envelope → :error, retaining it"
            {:status :loading :data nil :error {:kind :rf.http/http-5xx}
             :current-work [:rf.work/resource gkey 5]}
            [:error nil {:kind :rf.http/http-5xx}]]
           ["a :loading entry that already holds data → :loaded, keeping it"
            {:status :loading :data {:title "kept"} :current-work [:rf.work/resource gkey 6]}
            [:loaded {:title "kept"} nil]]
           ["an already-stable :loaded entry keeps its status"
            {:status :loaded :data {:x 1} :loaded-at 1000 :stale-at 9.0e15}
            [:loaded {:x 1} nil]]
           ["…as does :error"
            {:status :error :error {:kind :x}}
            [:error nil {:kind :x}]]
           ["…and :idle"
            {:status :idle}
            [:idle nil nil]]]]
    (let [se (restored-entry (rf.resources.ssr/reconcile-on-restore
                               (runtime-db-with {gkey (entry (assoc fields :resource-id :article/by-slug))})
                               :app/main))]
      (is (= [status data error nil] ((juxt :status :data :error :current-work) se)) label))))

(deftest restore-does-not-re-read-the-live-clock-for-durable-entry-timestamps
  ;; EP-0010 §Restore/Replay: restore MUST NOT re-read ambient world facts to
  ;; freshen durable state. The reconcile's only live-clock read is stubbed to a
  ;; sentinel, and every durable timestamp still equals the snapshot's.
  (let [ka (rf.resources.state/scoped-resource-key :rf.scope/global :a {})
        kb (rf.resources.state/scoped-resource-key :rf.scope/global :b {})]
    (with-redefs [rf.interop/epoch-now-ms (constantly 9999999999999)]
      (let [es (get-in (rf.resources.ssr/reconcile-on-restore
                         (runtime-db-with
                           {ka (entry {:resource-id :a :status :loaded :data {:x 1}
                                       :loaded-at 1000 :stale-at 2000})
                            kb (entry {:resource-id :b :status :loaded :data {:y 2}
                                       :loaded-at 1000 :stale-at 8000 :invalidated-at 1500})})
                         :app/main)
                       [rf.resources.state/resources-key :entries])]
        (is (= [[1000 2000 nil] [1000 8000 1500]]
               (mapv #((juxt :loaded-at :stale-at :invalidated-at) (es (rf.resources.state/key-id %)))
                     [ka kb])))))))

;; ===========================================================================
;; 2. Dangle non-terminal work and pending mutations (Spec 016 §Restore part 2)
;; ===========================================================================

(defn- work-row
  [work-id status]
  (-> (rf.resources.work-ledger/work-record
        {:work-id work-id :frame-id :app/main :resource/key gkey
         :generation (nth work-id 2) :transport :rf.http/managed
         :started-at 1000})
      (assoc :status status)))

(deftest non-terminal-rows-settled-to-dangling-suppressed
  ;; Every restored non-terminal row settles terminal :suppressed with a
  ;; :dangling outcome, so a late reply is suppressed; a terminal row is left alone.
  (let [live     {[:rf.work/resource gkey 3] :running
                  [:rf.work/resource gkey 4] :queued
                  [:rf.work/resource gkey 5] :abort-requested}
        w-done   [:rf.work/resource gkey 2]
        done-row (rf.resources.work-ledger/mark-terminal (work-row w-done :completed)
                                                         :completed {:ok true})
        ledger   (assoc (into {} (map (fn [[wid st]] [wid (work-row wid st)])) live)
                        w-done done-row)
        out      (get (rf.resources.ssr/reconcile-on-restore
                        (runtime-db-with {gkey (entry {:resource-id :article/by-slug
                                                       :status :loading :data nil})}
                                         ledger)
                        :app/main)
                      rf.resources.state/work-ledger-key)
        row      (fn [wid] (get out (rf.resources.work-ledger/work-id-id wid)))]
    (doseq [wid (keys live)]
      (is (= [:suppressed :dangling] ((juxt :status (comp :reason :outcome)) (row wid)))
          (pr-str wid)))
    (is (= done-row (row w-done)) "a completed row rides through unchanged")))

(defn- mutation-instance
  "A durable mutation instance under instance-id (defaults to a :pending
  instance pointing at a work id)."
  [{:keys [mutation-id instance-id status generation work-id scope params]
    :or   {mutation-id :comment/add status :pending generation 3}}]
  (-> (rf.resources.mutation-runtime/empty-instance mutation-id instance-id
        {:scope scope :params params :generation generation
         :work-id work-id :started-at 1000})
      (assoc :status status)))

(defn- pending-instance [instance-id]
  (mutation-instance {:instance-id instance-id
                      :work-id [:rf.work/resource [:rf.mutation instance-id 3] 3]}))

(defn- mutations-map
  "Key `{<instance-id> <instance>}` the runtime way — on the CEDN-1 byte
  `key-id` of each instance id."
  [m]
  (into {} (map (fn [[iid inst]] [(rf.resources.mutation-runtime/instance-key-id iid) inst])) m))

(defn- instance-in [runtime-db instance-id]
  (get-in runtime-db [rf.resources.mutation-runtime/mutations-key
                      (rf.resources.mutation-runtime/instance-key-id instance-id)]))

(def ^:private live-clock 9999999999999)

(deftest dangled-instance-settled-at-is-the-restore-causal-time-not-the-live-clock
  ;; A dangled instance's :settled-at is durable frame-state, so it comes from
  ;; the restore's CAUSAL time (the epoch's :committed-at), never the install
  ;; clock (EP-0010 §Time). A terminal instance is left alone.
  (let [restore-time 1781078400777
        settled      (mutation-instance {:instance-id :inst-s :status :success})
        rdb          {rf.resources.mutation-runtime/mutations-key
                      (mutations-map {:inst-1 (pending-instance :inst-1) :inst-s settled})}]
    (with-redefs [rf.interop/epoch-now-ms (constantly live-clock)]
      (let [out (rf.resources.ssr/reconcile-on-restore rdb :app/main {:restore-time-ms restore-time})]
        (is (= [:error restore-time] ((juxt :status :settled-at) (instance-in out :inst-1))))
        (is (= settled (instance-in out :inst-s)) "the terminal instance is untouched")))))

(deftest dangling-mutation-without-restore-time-warns-loudly
  ;; With no causal :restore-time-ms the dangle can only stamp the live clock
  ;; (the pure-unit path has none), so it says so on a warning trace: a
  ;; production restore that forgot to thread the causal time is loud.
  (let [rdb      {rf.resources.mutation-runtime/mutations-key
                  (mutations-map {:inst-1 (pending-instance :inst-1)})}
        restore! (fn [runtime-db & opts]
                   (let [seen (atom [])
                         k    ::live-clock-warn-recorder]
                     (rf.trace.tooling/register-listener!
                       k (fn [ev] (when (= :rf.resource/restore-settled-at-from-live-clock
                                           (:operation ev))
                                    (swap! seen conj ev))))
                     [(try (apply rf.resources.ssr/reconcile-on-restore runtime-db :app/main opts)
                           (finally (rf.trace.tooling/unregister-listener! k)))
                      @seen]))]
    (with-redefs [rf.interop/epoch-now-ms (constantly live-clock)]
      (let [[out seen] (restore! rdb)]
        (is (= live-clock (:settled-at (instance-in out :inst-1))) "the live-clock fallback")
        (is (= [[:inst-1]] (mapv (comp :dangled-mutations :tags) seen))
            "exactly one warning, naming the instance whose :settled-at is unstable")))
    (is (empty? (second (restore! rdb {:restore-time-ms 1781078400777})))
        "a causal restore time suppresses the warning")
    (is (empty? (second (restore! {rf.resources.mutation-runtime/mutations-key {}})))
        "nothing dangled, nothing stamped from the live clock, no warning")))

(deftest late-pre-restore-mutation-reply-is-suppressed-end-to-end
  ;; The mutation reply gate checks the INSTANCE's :current-work, so without
  ;; dangling the restored pending instance a late pre-restore success would
  ;; still patch / populate / invalidate post-restore resource state.
  (rf/reg-resource :article/by-slug
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags    (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (let [fid         :restore/mutation-frame
        loaded      (entry {:resource-id :article/by-slug :status :loaded
                            :data {:title "post-restore"} :loaded-at 1 :stale-at 9.0e15
                            :generation 9})
        instance-id :article/edit-form
        work-id     (rf.resources.work-ledger/resource-work-id [:rf.mutation instance-id] 3)
        pending     (mutation-instance {:mutation-id :article/edit
                                        :instance-id instance-id
                                        :generation 3 :work-id work-id
                                        :scope :rf.scope/global :params {:slug "x"}})
        snapshot    (-> (runtime-db-with {gkey loaded})
                        (assoc rf.resources.mutation-runtime/mutations-key (mutations-map {instance-id pending}))
                        (assoc-in (rf.resources.work-ledger/record-path work-id)
                                  (-> (rf.resources.work-ledger/work-record
                                        {:work-id work-id :frame-id fid
                                         :resource/key [:rf.mutation instance-id]
                                         :generation 3 :transport :rf.http/managed
                                         :started-at 1000})
                                      (assoc :work/kind :mutation))))]
    (rf/make-frame {:id fid :doc "restore mutation suppression frame"})
    (rf.frame/replace-runtime-db! fid (rf.resources.ssr/reconcile-on-restore snapshot fid))
    ;; the late pre-restore SUCCESS reply, carrying the snapshot's work-id + generation
    (rf/dispatch-sync
      [:rf.mutation.internal/succeeded
       {:instance-id instance-id :mutation-id :article/edit
        :work/id work-id :generation 3 :scope :rf.scope/global}
       {:status :ok :value {:title "STALE pre-restore write"}}]
      {:frame fid})
    (let [post (rf.frame/frame-runtime-db-value fid)]
      (is (= {:title "post-restore"} (:data (restored-entry post)))
          "the resource entry is UNCHANGED — the stale reply did not patch/populate it")
      (is (= :error (:status (instance-in post instance-id)))
          "the dangled instance stays terminal :error — the stale reply did not revive it"))
    (rf.frame/destroy-frame! fid)))

;; ===========================================================================
;; 3. Owner reconciliation and its traces (Spec 016 §Restore part 4)
;; ===========================================================================

(deftest restore-reconciles-owners-against-the-live-nav-token
  ;; SSR owners orphan (a settled server render). A route owner revives only if
  ;; the restored routing names its nav-token live — on restore, unlike
  ;; hydration, a missing or nil token means NO route owner is live. Machine and
  ;; app owners ride through to their own subsystems. The owner-index is
  ;; recomputed without the orphans.
  (let [ssr     [:ssr "req-9" "nav-1"]
        machine [:machine :checkout/flow "inst-1"]
        app     [:app :dashboard 7]
        route   (fn [token] [:route :route/article token])
        no-live #{ssr (route "nav-x") machine app}]
    (doseq [[label owners rdb-fn survivors]
            [["the live nav-token's route owner revives"
              #{ssr (route "nav-1") machine} #(with-live-nav-token % "nav-1")
              #{(route "nav-1") machine}]
             ["a stale nav-token's route owner orphans beside the live one"
              #{(route "nav-OLD") (route "nav-NEW") machine} #(with-live-nav-token % "nav-NEW")
              #{(route "nav-NEW") machine}]
             ["no routing slice: no route owner is live"
              no-live identity #{machine app}]
             ["a routing slice without :current"
              no-live #(assoc % :rf.runtime/routing {:pending-navigation {:x 1}}) #{machine app}]
             ["a nil live nav-token"
              no-live #(assoc-in % [:rf.runtime/routing :current :nav-token] nil) #{machine app}]]]
      (let [out (rf.resources.ssr/reconcile-on-restore
                  (rdb-fn (runtime-db-with {gkey (loaded-entry owners)})) :app/main)]
        (is (= [survivors survivors]
               [(:active-owners (restored-entry out))
                (set (keys (get-in out [rf.resources.state/resources-key :owner-index])))])
            label)))))

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

(defn- released? [traces owner reason]
  (some #(and (= :rf.resource/owner-released (:operation %))
              (= [owner reason] ((juxt :owner :reason) (:tags %))))
        traces))

(defn- restored-row? [traces]
  (some #(= :rf.resource/restored (:operation %)) traces))

(deftest inline-reconcile-emits-restored-and-owner-released-traces
  ;; The 1-/2-arity (direct unit) path has no install to gate against, so it
  ;; emits inline: the restored summary, and a release row for the route owner
  ;; no live nav-token names.
  (let [route-owner [:route :route/article "nav-X"]
        [seen unregister!] (restore-trace-recorder)]
    (try (rf.resources.ssr/reconcile-on-restore
           (runtime-db-with {gkey (loaded-entry #{route-owner})}) :app/main)
         (finally (unregister!)))
    (is (restored-row? @seen))
    (is (released? @seen route-owner :stale-nav-orphan))))

(deftest deferred-restore-traces-emit-only-on-commit
  ;; The reconcile runs inside perform-restore! BEFORE the atomic install, which
  ;; can still fail, so under :defer-traces? its success rows ride back as
  ;; metadata and fire from commit-restore-reconcile-traces! once the install
  ;; succeeds. (A failed install emitting nothing is epoch_test.clj's to prove.)
  (let [stale [:route :route/article "nav-OLD"]
        rdb   (with-live-nav-token (runtime-db-with {gkey (loaded-entry #{stale})}) "nav-NEW")
        [inline unregister!] (restore-trace-recorder)
        out   (try (rf.resources.ssr/reconcile-on-restore rdb :app/main {:defer-traces? true})
                   (finally (unregister!)))
        [committed unregister-commit!] (restore-trace-recorder)]
    (try (rf.resources.ssr/commit-restore-reconcile-traces! out)
         (finally (unregister-commit!)))
    (is (empty? @inline) "nothing fired inline")
    (is (restored-row? @committed))
    (is (released? @committed stale :stale-nav-orphan))))

(deftest commit-restore-reconcile-traces-noop-without-intents
  (testing "commit-restore-reconcile-traces! is a no-op on a value
            carrying no deferred intents (an inline reconcile, or resource-free)"
    (let [[seen unregister!] (restore-trace-recorder)]
      (try
        (rf.resources.ssr/commit-restore-reconcile-traces! (runtime-db-with {}))
        (rf.resources.ssr/commit-restore-reconcile-traces! nil)
        (finally (unregister!)))
      (is (empty? @seen) "no intents → no trace rows"))))

;; ===========================================================================
;; 4. Indexes recomputed from entries, never trusted (Spec 016 §Restore part 5)
;; ===========================================================================

(deftest restore-recomputes-indexes-from-entries
  ;; nav-1 is named live so the route owner survives — this pins the RECOMPUTE.
  (let [e   (entry {:resource-id :article/by-slug :status :loaded :data {:x 1}
                    :loaded-at 1 :stale-at 9.0e15
                    :tags #{[:article "x"]}
                    :owners #{[:route :route/article "nav-1"]}})
        rdb (-> (runtime-db-with {gkey e})
                (assoc-in [rf.resources.state/resources-key :tag-index]   {[:bogus] #{:nope}})
                (assoc-in [rf.resources.state/resources-key :owner-index] {[:bogus] #{:nope}})
                (with-live-nav-token "nav-1"))
        sub (get (rf.resources.ssr/reconcile-on-restore rdb :app/main) rf.resources.state/resources-key)]
    (is (= {[:article "x"] #{(rf.resources.state/key-id gkey)}} (:tag-index sub))
        "tag-index recomputed from the entry's :tags; bogus snapshot index discarded")
    (is (= {[:route :route/article "nav-1"] #{(rf.resources.state/key-id gkey)}} (:owner-index sub))
        "owner-index recomputed from the entry's owners; bogus snapshot index discarded")))

;; ===========================================================================
;; 4b. Clear host transients on restore (part 5)
;; ===========================================================================
;;
;; Timer and work-ledger host handles belong to the PRE-restore timeline and
;; are not frame-state, so the wholesale install does not touch them; the
;; reconcile clears them so a stale timer or abandoned handle cannot fire
;; against the restored state.

(defn- armed?
  "Whether `frame-id` holds any [timer handle, work handle] in the host tables."
  [frame-id]
  [(boolean (some (fn [[fid _ _]] (= fid frame-id)) (keys @rf.resources.timers/timer-table)))
   (boolean (some (fn [[fid _]] (= fid frame-id)) (keys @rf.resources.work-ledger/handle-table)))])

(deftest restore-host-clear-triggers-no-eager-refetch
  (testing "restore clears transients but arms NO eager refetch / timer
            (scheduling re-arms lazily on the next live-owner touch)"
    (let [fid :restore/no-eager
          e   (entry {:resource-id :article/by-slug :status :loaded
                      :data {:x 1} :loaded-at 1 :stale-at 2})] ;; stale
      (rf.resources.ssr/reconcile-on-restore (runtime-db-with {gkey e}) fid)
      (is (= [false false] (armed? fid))))))

(deftest reconcile-host-transient-clear-fenced-to-exact-incarnation
  ;; The reconcile runs BEFORE the atomic write and addresses the frame by bare
  ;; id, so the clear fires only while the threaded :owner-token still names the
  ;; live incarnation: a STALE token (a churned successor) spares the
  ;; successor's handles. A nil token (the pure-unit path) clears unconditionally.
  (let [fid  :restore/fence
        wid  [:rf.work/resource gkey 7]
        arm! (fn []
               (rf.resources.timers/schedule! fid gkey rf.resources.timers/stale-kind 600000)
               (rf.resources.work-ledger/put-handle! fid wid {:transport :rf.http/managed :request-id wid}))
        rdb  (runtime-db-with {gkey (entry {:resource-id :article/by-slug :status :fetching :data {:x 1}
                                            :loaded-at 1 :stale-at 9.0e15 :current-work wid})})]
    (rf/make-frame {:id fid})
    (let [live-token  (rf.frame/frame-incarnation-token fid)
          ;; an incarnation token is a `:drain-lock` atom, so a fresh atom
          ;; stands in for a destroyed / successor incarnation's
          stale-token (atom :stale-incarnation)]
      (arm!)
      (rf.resources.ssr/reconcile-on-restore rdb fid {:owner-token stale-token})
      (is (= [true true] (armed? fid)) "a stale incarnation token SKIPS the clear")
      (rf.resources.ssr/reconcile-on-restore rdb fid {:owner-token live-token})
      (is (= [false false] (armed? fid)) "the live incarnation token clears")
      (arm!)
      (rf.resources.ssr/reconcile-on-restore rdb fid {:owner-token nil})
      (is (= [false false] (armed? fid)) "a nil token clears unconditionally")
      (rf.resources.timers/cancel-for-key! fid gkey))))

;; ---- the clear itself is a CALLBACK-BEARING fan-out ------------------------
;;
;; `work-ledger/release-frame!` best-effort ABORTS each slot on the way out, and
;; an abort callback is app / transport code that can churn incarnation A to a
;; same-id successor B and let B seat a handle in the SAME `[frame-id work-id]`
;; slot. So the frame's slots are DETACHED atomically first and the detached
;; values aborted after, and A's returning cleanup can never delete B's handle.

(deftest work-ledger-release-frame-detaches-before-abort-callbacks
  (let [fid      :restore/detach
        wid      [:rf.work/resource gkey 11]
        b-handle {:transport :rf.http/managed :request-id [:rf.req fid wid] :owner :B}
        aborted  (atom [])]
    ;; A's abort callback seats successor B in the very slot A is about to drop
    (rf.resources.work-ledger/put-handle! fid wid
      {:transport :rf.http/direct
       :owner     :A
       :abort-fn  (fn [reason]
                    (swap! aborted conj reason)
                    (rf.resources.work-ledger/put-handle! fid wid b-handle))})
    (rf.resources.work-ledger/release-frame! fid)
    (is (= [:resource-superseded] @aborted) "A's abort callback fired exactly once")
    (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid))
        "successor B's re-seated handle survives byte-for-byte")
    (rf.resources.work-ledger/clear-handle! fid wid)))

(deftest work-ledger-release-frame-abort-callback-reentrancy
  ;; A callback that RE-ENTERS release-frame! for the same frame terminates,
  ;; aborts each A slot exactly once, and still spares a successor handle.
  (let [fid      :restore/reentrant
        wid-1    [:rf.work/resource gkey 21]
        wid-2    [:rf.work/resource gkey 22]
        b-handle {:transport :rf.http/managed :owner :B}
        aborted  (atom [])]
    (rf.resources.work-ledger/put-handle! fid wid-1
      {:owner :A :abort-fn (fn [_]
                             (swap! aborted conj :a1)
                             (rf.resources.work-ledger/release-frame! fid)
                             ;; B seats a handle in a slot the OUTER pass still holds detached
                             (rf.resources.work-ledger/put-handle! fid wid-2 b-handle))})
    (rf.resources.work-ledger/put-handle! fid wid-2
      {:owner :A :abort-fn (fn [_] (swap! aborted conj :a2))})
    (rf.resources.work-ledger/release-frame! fid)
    (is (= 1 (count (filter #{:a1} @aborted))) "A's slot 1 aborted exactly once")
    (is (= 1 (count (filter #{:a2} @aborted))) "A's slot 2 aborted exactly once")
    (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid-2))
        "the successor handle seated during the nested fan-out survives")
    (rf.resources.work-ledger/clear-handle! fid wid-2)))

(deftest opportunistic-abort-detaches-before-abort-callback
  ;; The single-slot public abort has the same seam.
  (let [fid      :restore/one-slot
        wid      [:rf.work/resource gkey 41]
        b-handle {:transport :rf.http/managed :owner :B}]
    (rf.resources.work-ledger/put-handle! fid wid
      {:owner :A :abort-fn (fn [_] (rf.resources.work-ledger/put-handle! fid wid b-handle))})
    (is (true? (rf.resources.work-ledger/opportunistic-abort! fid wid))
        "an abort capability was found and fired")
    (is (identical? b-handle (rf.resources.work-ledger/get-handle fid wid))
        "successor B's re-seated handle survives the returning clear")
    (rf.resources.work-ledger/clear-handle! fid wid)))

;; ---- the deferred trace commit is a callback fan-out too -------------------

(deftest commit-restore-reconcile-traces-fenced-per-intent
  ;; Each deferred intent emits to trace listeners, any of which can destroy A
  ;; and seat a same-id successor B; with the captured :owner-token the commit
  ;; STOPS rather than announcing A's restore against B.
  (let [fid :restore/commit-fence
        rdb (with-live-nav-token
              (runtime-db-with {gkey (loaded-entry #{[:route :route/article "nav-OLD"]})}) "nav-NEW")]
    (rf/make-frame {:id fid})
    (let [token    (rf.frame/frame-incarnation-token fid)
          out      (rf.resources.ssr/reconcile-on-restore rdb fid {:defer-traces? true :owner-token token})
          intents  (-> out meta (get :re-frame.resources.ssr/deferred-trace-intents))
          [seen unregister!] (restore-trace-recorder)
          churned? (atom false)]
      (is (< 1 (count intents)) "premise: more than one deferred intent (a genuine fan-out)")
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
          "the remaining A-owned intents are STOPPED once the incarnation is lost"))))

(deftest commit-restore-reconcile-traces-unfenced-arities-emit-all
  ;; The control: with no token or a LIVE incarnation, every intent commits.
  (let [fid :restore/commit-live
        mk  (fn [] (rf.resources.ssr/reconcile-on-restore
                     (with-live-nav-token
                       (runtime-db-with {gkey (loaded-entry #{[:route :route/article "nav-OLD"]})})
                       "nav-NEW")
                     fid {:defer-traces? true}))]
    (rf/make-frame {:id fid})
    (let [token (rf.frame/frame-incarnation-token fid)
          n     (count (-> (mk) meta (get :re-frame.resources.ssr/deferred-trace-intents)))]
      (doseq [[label commit!] [["the 1-arity" #(rf.resources.ssr/commit-restore-reconcile-traces! %)]
                               ["a LIVE incarnation token"
                                #(rf.resources.ssr/commit-restore-reconcile-traces! % fid {:owner-token token})]
                               ["a nil token"
                                #(rf.resources.ssr/commit-restore-reconcile-traces! % fid {:owner-token nil})]]]
        (let [[seen unregister!] (restore-trace-recorder)]
          (try (commit! (mk)) (finally (unregister!)))
          (is (= n (count @seen)) label))))))

;; ===========================================================================
;; 5. The generation allocator is monotonic across restore (part 1)
;; ===========================================================================

(deftest generation-allocator-monotonic-across-restore
  ;; The host-side allocator is NOT frame-state, so restoring a snapshot from
  ;; generation 3 cannot rewind a live high-water mark of 7: a pre-restore reply
  ;; carrying generation 3 can never match a freshly minted live entry.
  (rf.resources.state/commit-generation! 7)
  (rf.resources.ssr/reconcile-on-restore
    (runtime-db-with {gkey (entry {:resource-id :article/by-slug :status :fetching
                                   :data {:x 1} :loaded-at 1 :stale-at 9.0e15
                                   :generation 3
                                   :current-work [:rf.work/resource gkey 3]})})
    :app/main)
  (is (= 7 (rf.resources.state/generation-snapshot))))

(deftest restore-noop-without-resources
  (testing "a runtime-db with no resource entries AND no work-ledger rows is
            returned unchanged (a resource-free restore)"
    (let [rdb {:rf.runtime/machines {:snapshots {}}}]
      (is (= rdb (rf.resources.ssr/reconcile-on-restore rdb :app/main))))))
