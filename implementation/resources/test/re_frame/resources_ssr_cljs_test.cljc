(ns re-frame.resources-ssr-cljs-test
  "SSR / hydration for the Resources artefact (rf2-ctk2av, Spec 016 §SSR
  and hydration / §Restore and replay — EP-0003 slice 8).

  These JVM+CLJS unit tests pin the SSR slice's contract. SSR runs on the
  JVM, so the whole suite is CLJC and the JVM run (`clojure -M:test`) is
  the load-bearing gate:

    1. SERVER projection — `project-resources-runtime-db` rides ONLY the
       durable `:entries` (never the indexes; never all of runtime-db); a
       `:sensitive?` resource is REDACTED (metadata only); a `:large?`
       resource is OMITTED (no data key); `projection-metadata` records
       the serialized / redacted / omitted / fresh / stale /
       refetch-on-client decision per entry;
    2. SERVER blocking drain — `blocking-settled?` is true iff every
       blocking entry has settled; `settle-blocking-timeout` settles an
       unsettled blocking entry as a structured first-load failure +
       a route-blocking-failure record (it never hangs);
    3. CLIENT hydration — `hydrate-runtime-db` recomputes the reverse
       indexes from entries (never trusts the wire), orphans SSR owners,
       clears `:current-work`, surfaces clock skew, and NEVER crosses
       scopes;
    4. NO double-fetch — `hydrate-refetch-plan` omits fresh-with-data
       entries; includes stale (background refetch) and metadata-only
       (redacted / omitted) entries;
    5. SCOPE isolation — a hydrated entry under scope A never leaks to
       scope B; the index recompute keys on the entry's own scoped key.

  The reconcile is wired into SSR's `:rf/hydrate` handler via the
  `:resources/hydrate-runtime-db` late-bind hook; the round-trip is
  exercised through that hook end-to-end."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as late-bind]
   ;; load-bearing side-effecting requires: the façade publishes the SSR
   ;; projection + reconcile hooks + registers the resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   ;; SSR artefact — the :rf/hydrate handler that consults the reconcile hook.
   [re-frame.ssr]
   ;; the consumer of :ssr/extend-runtime-db-projection (project-runtime-db).
   [re-frame.ssr.payload-policy :as payload-policy]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register a resource id with optional spec overrides (defaults to a
  global-scope slug resource)."
  ([id] (reg! id {}))
  ([id overrides]
   (rf/clear-resource id)
   (rf/reg-resource id
     (merge {:scope         :rf.scope/global
             :params-schema [:map [:slug :string]]
             :request       (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
             :tags          (fn [{:keys [slug]} _] #{[:article slug]})}
            overrides))))

(defn- entry
  "A loaded durable entry under a scoped key, with the supplied status /
  data / timestamps. Mirrors the runtime's durable shape."
  [{:keys [resource-id status data loaded-at stale-at invalidated-at
           generation current-work tags owners refresh-error]
    :or   {status :loaded generation 1 tags #{} owners #{}}}]
  (merge (state/empty-entry resource-id)
         {:status         status
          :data           data
          :loaded-at      loaded-at
          :stale-at       stale-at
          :invalidated-at invalidated-at
          :generation     generation
          :current-work   current-work
          :tags           tags
          :active-owners  owners
          :refresh-error  refresh-error}))

(defn- runtime-db-with
  "A runtime-db carrying a `:rf.runtime/resources :entries` map."
  [entries]
  {state/resources-key {:entries entries :tag-index {} :owner-index {}}})

(def ^:private gkey
  ;; canonical global-scope key for :article/by-slug {:slug "x"}
  (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"}))

;; ===========================================================================
;; 1. SERVER projection
;; ===========================================================================

(deftest projection-rides-only-entries-never-indexes
  (reg! :article/by-slug)
  (testing "the projection carries :entries, never :tag-index / :owner-index,
            and never all of runtime-db (Spec 016 §SSR and hydration clause 4)"
    (let [e   (entry {:resource-id :article/by-slug :data {:title "X"}
                      :loaded-at 1000 :stale-at 9.0e15})
          rdb (assoc (runtime-db-with {gkey e})
                     :rf.runtime/machines {:snapshots {}}
                     :rf.runtime/routing  {:current {:route :x}})
          proj (ssr/project-resources-runtime-db rdb)]
      (is (= #{state/resources-key} (set (keys proj)))
          "only the :rf.runtime/resources subsystem key is projected")
      (is (= #{:entries} (set (keys (get proj state/resources-key))))
          "only :entries rides — indexes are recomputable-from-entries")
      (is (contains? (get-in proj [state/resources-key :entries]) gkey)))))

(deftest projection-empty-when-no-entries
  (testing "no resource entries → empty projection (the hook contributes nothing)"
    (is (= {} (ssr/project-resources-runtime-db {})))
    (is (= {} (ssr/project-resources-runtime-db (runtime-db-with {}))))))

(deftest sensitive-resource-is-redacted
  (reg! :secret/thing {:sensitive? true})
  (testing "a :sensitive? resource ships METADATA ONLY — data redacted, refresh-error dropped"
    (let [k   (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          e   (entry {:resource-id :secret/thing :data {:ssn "123-45-6789"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :refresh-error {:kind :rf.http/http-5xx}})
          proj (ssr/project-resources-runtime-db (runtime-db-with {k e}))
          we   (get-in proj [state/resources-key :entries k])]
      (is (not= {:ssn "123-45-6789"} (:data we))
          "the sensitive data must NOT ride verbatim")
      (is (nil? (:refresh-error we))
          "refresh-error is the same privacy class as data — dropped on a redacted entry")
      (is (= :loaded (:status we)) "metadata (status / timestamps) still rides"))))

(deftest large-resource-is-omitted
  (reg! :big/thing {:large? true})
  (testing "a :large? resource ships metadata only — the :data key is dropped"
    (let [k   (state/scoped-resource-key :rf.scope/global :big/thing {:slug "b"})
          e   (entry {:resource-id :big/thing :data (vec (range 10000))
                      :loaded-at 1000 :stale-at 9.0e15})
          proj (ssr/project-resources-runtime-db (runtime-db-with {k e}))
          we   (get-in proj [state/resources-key :entries k])]
      (is (not (contains? we :data)) "the large data key is omitted entirely")
      (is (= :loaded (:status we))))))

(deftest projection-metadata-records-decisions
  (reg! :article/by-slug)
  (reg! :secret/thing {:sensitive? true})
  (reg! :big/thing {:large? true})
  (testing "projection-metadata records serialized / redacted / omitted + fresh / stale + refetch-on-client"
    (let [fresh (entry {:resource-id :article/by-slug :data {:t "x"}
                        :loaded-at 1000 :stale-at 9.0e15})
          stale (entry {:resource-id :article/by-slug :data {:t "y"}
                        :loaded-at 1000 :stale-at 1500})    ;; stale vs clock 5000
          sens  (entry {:resource-id :secret/thing :data {:s 1}
                        :loaded-at 1000 :stale-at 9.0e15})
          big   (entry {:resource-id :big/thing :data [1 2 3]
                        :loaded-at 1000 :stale-at 9.0e15})
          k-fresh gkey
          k-stale (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "y"})
          k-sens  (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          k-big   (state/scoped-resource-key :rf.scope/global :big/thing {:slug "b"})
          metas   (->> (ssr/projection-metadata
                         nil 5000
                         {k-fresh fresh k-stale stale k-sens sens k-big big})
                       (into {} (map (juxt :resource-key identity))))]
      (is (= :serialized (:disposition (metas k-fresh))))
      (is (= :fresh      (:freshness   (metas k-fresh))))
      (is (false?        (:refetch-on-client? (metas k-fresh)))
          "fresh serialized → no client refetch (no double-fetch)")
      (is (= :stale      (:freshness   (metas k-stale))))
      (is (true?         (:refetch-on-client? (metas k-stale)))
          "stale serialized → background refetch on client")
      (is (= :redacted   (:disposition (metas k-sens))))
      (is (true?         (:refetch-on-client? (metas k-sens)))
          "redacted → metadata-only refetch")
      (is (= :omitted    (:disposition (metas k-big))))
      (is (true?         (:refetch-on-client? (metas k-big)))))))

;; ===========================================================================
;; 2. SERVER blocking drain + timeout
;; ===========================================================================

(def ^:private ka (state/scoped-resource-key :rf.scope/global :a {:slug "a"}))
(def ^:private kb (state/scoped-resource-key :rf.scope/global :b {:slug "b"}))
(def ^:private kc (state/scoped-resource-key :rf.scope/global :c {:slug "c"}))

(deftest blocking-settled-predicate
  (testing "blocking-settled? is true iff every blocking entry has settled"
    (let [loaded  (entry {:resource-id :a :status :loaded :data {:x 1}})
          errored (entry {:resource-id :b :status :error})
          loading (entry {:resource-id :c :status :loading})
          es      {ka loaded kb errored kc loading}]
      (is (true?  (ssr/blocking-settled? es #{ka kb}))
          ":loaded and :error are settled")
      (is (false? (ssr/blocking-settled? es #{ka kc}))
          ":loading is NOT settled")
      (is (false? (ssr/blocking-settled? es #{ka [:rf.scope/global :missing {}]}))
          "an absent (never-enqueued) blocking key is not settled")
      (is (true?  (ssr/blocking-settled? es #{}))
          "no blocking resources → trivially settled (never blocks render)"))))

(deftest blocking-timeout-settles-first-load-failure
  (testing "settle-blocking-timeout settles every unsettled blocking entry as a
            structured first-load failure + a route-blocking-failure record (never hangs)"
    (let [loading (entry {:resource-id :a :status :loading})
          loaded  (entry {:resource-id :b :status :loaded :data {:x 1}})
          es      {ka loading kb loaded}
          {:keys [entries route-blocking-failure]}
          (ssr/settle-blocking-timeout es #{ka kb} 250 :app/main)]
      (testing "the unsettled blocking entry settles to a first-load :error"
        (let [se (get entries ka)]
          (is (= :error (:status se)))
          (is (= :rf.http/timeout (:kind (:error se))))
          (is (= :ssr-blocking-timeout (:reason (:error se))))
          (is (nil? (:data se)) "first-load failure has no usable data")))
      (testing "the already-settled entry is untouched"
        (is (= :loaded (:status (get entries kb)))))
      (testing "the route-blocking-failure record names the timed-out keys + deadline"
        (is (= :rf.error/resource-ssr-blocking-timeout (:rf.error/id route-blocking-failure)))
        (is (= #{ka} (set (:timed-out route-blocking-failure))))
        (is (= 250 (:limit-ms route-blocking-failure)))))))

(deftest blocking-timeout-noop-when-all-settled
  (testing "no unsettled blocking entries → no failure record, entries unchanged"
    (let [es {ka (entry {:resource-id :a :status :loaded :data {:x 1}})}
          {:keys [entries route-blocking-failure]}
          (ssr/settle-blocking-timeout es #{ka} 250 :app/main)]
      (is (= es entries))
      (is (nil? route-blocking-failure)))))

(deftest blocking-timeout-settles-absent-blocking-key
  (testing "an absent blocking key (enqueued but never wrote an entry) settles to :error"
    (let [kmiss (state/scoped-resource-key :rf.scope/global :missing {})
          {:keys [entries route-blocking-failure]}
          (ssr/settle-blocking-timeout {} #{kmiss} 100 :app/main)]
      (is (= :error (:status (get entries kmiss))))
      (is (= #{kmiss} (set (:timed-out route-blocking-failure)))))))

;; ===========================================================================
;; 3. CLIENT hydration reconcile
;; ===========================================================================

(deftest hydrate-recomputes-indexes-from-entries
  (testing "hydrate-runtime-db rebuilds :tag-index / :owner-index from entries (never trusts the wire)"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :tags #{[:article "x"]}
                      :owners #{[:route :route/article "nav-1"]}})
          ;; arrive with deliberately-WRONG (stale) indexes — they must be discarded
          rdb {state/resources-key {:entries   {gkey e}
                                    :tag-index {[:bogus] #{:nope}}
                                    :owner-index {[:bogus] #{:nope}}}}
          out (ssr/hydrate-runtime-db rdb :app/main)
          sub (get out state/resources-key)]
      (is (= {[:article "x"] #{gkey}} (:tag-index sub))
          "tag-index recomputed from the entry's :tags, the bogus wire index discarded")
      (is (= {[:route :route/article "nav-1"] #{gkey}} (:owner-index sub))
          "owner-index recomputed from the entry's surviving owners"))))

(deftest hydrate-orphans-ssr-owners
  (testing "SSR owners orphan on hydration (they belong to a settled server render); route owners survive"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :owners #{[:ssr "req-7" "nav-1"]
                                [:route :route/article "nav-1"]}})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          owners (get-in out [state/resources-key :entries gkey :active-owners])]
      (is (not (contains? owners [:ssr "req-7" "nav-1"]))
          "the SSR owner is dropped as an orphan")
      (is (contains? owners [:route :route/article "nav-1"])
          "the route owner survives (its liveness is reconciled by routing)")
      (is (not (contains? (get-in out [state/resources-key :owner-index])
                          [:ssr "req-7" "nav-1"]))
          "the orphaned SSR owner is absent from the recomputed owner-index"))))

(deftest hydrate-clears-current-work
  (testing "the transient :current-work pointer is cleared (the attempt never crossed the wire)"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :current-work [:rf.work/resource gkey 1]})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)]
      (is (nil? (get-in out [state/resources-key :entries gkey :current-work]))))))

(deftest hydrate-preserves-entry-data
  (testing "hydrated entries are PRESERVED (data + status survive the reconcile)"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "kept"}
                      :loaded-at 1000 :stale-at 9.0e15})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)]
      (is (= {:t "kept"} (get-in out [state/resources-key :entries gkey :data])))
      (is (= :loaded (get-in out [state/resources-key :entries gkey :status]))))))

(deftest hydrate-noop-without-resources
  (testing "a runtime-db with no resource entries is returned unchanged (SSR app without resources)"
    (let [rdb {:rf.runtime/machines {:snapshots {}}}]
      (is (= rdb (ssr/hydrate-runtime-db rdb :app/main))))))

(deftest clock-skew-surfaced-when-stale-at-implausible
  (testing "clock-skew-ms returns positive skew when :stale-at lies implausibly ahead of the live clock"
    ;; window = stale-at - loaded-at = 1000; stale-at 100000 is far beyond
    ;; (clock 2000 + window 1000) = 3000 → implausible (server clock ran ahead).
    (let [e (entry {:resource-id :a :data {:x 1} :loaded-at 99000 :stale-at 100000})]
      (is (= (- 100000 2000) (ssr/clock-skew-ms e 2000))))
    (testing "a plausible stale-at returns nil (no skew)"
      (let [e (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 2000})]
        (is (nil? (ssr/clock-skew-ms e 1500)))))
    (testing "no :stale-at → nil (cannot assess)"
      (is (nil? (ssr/clock-skew-ms (entry {:resource-id :a :data {:x 1}}) 1500))))))

;; ===========================================================================
;; 4. NO double-fetch — refetch plan
;; ===========================================================================

(deftest refetch-plan-omits-fresh-includes-stale-and-metadata-only
  (testing "fresh-with-data → absent (no double-fetch); stale → present; metadata-only → present"
    (let [fresh (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 9.0e15})
          stale (entry {:resource-id :b :data {:x 2} :loaded-at 1000 :stale-at 1500})
          meta  (entry {:resource-id :c :data nil :status :loaded})  ;; redacted/omitted: no data
          rdb   (runtime-db-with {ka fresh kb stale kc meta})
          plan  (->> (ssr/hydrate-refetch-plan rdb 5000)
                     (into {} (map (juxt :resource-key identity))))]
      (is (not (contains? plan ka)) "fresh-with-data is NOT refetched (the SSR win)")
      (is (= :stale   (:reason (plan kb))))
      (is (= :no-data (:reason (plan kc)))))))

(deftest entry-needs-refetch-predicate
  (testing "entry-needs-refetch? is false ONLY for fresh-with-data"
    (is (false? (ssr/entry-needs-refetch?
                  (entry {:resource-id :a :data {:x 1} :loaded-at 1 :stale-at 9.0e15}) 100)))
    (is (true?  (ssr/entry-needs-refetch?
                  (entry {:resource-id :a :data {:x 1} :loaded-at 1 :stale-at 50}) 100))
        "stale-with-data → refetch")
    (is (true?  (ssr/entry-needs-refetch?
                  (entry {:resource-id :a :data nil}) 100))
        "no-data (metadata-only) → refetch")))

;; ===========================================================================
;; 5. SCOPE isolation
;; ===========================================================================

(deftest hydration-never-crosses-scopes
  (testing "entries under different scopes stay isolated; indexes key on each entry's own scoped key"
    (let [ka (state/scoped-resource-key [:rf.scope/session {:user "a"}] :article/by-slug {:slug "x"})
          kb (state/scoped-resource-key [:rf.scope/session {:user "b"}] :article/by-slug {:slug "x"})
          ea (entry {:resource-id :article/by-slug :data {:owner "a"}
                     :loaded-at 1000 :stale-at 9.0e15 :tags #{[:article "x"]}
                     :owners #{[:route :r "nav-a"]}})
          eb (entry {:resource-id :article/by-slug :data {:owner "b"}
                     :loaded-at 1000 :stale-at 9.0e15 :tags #{[:article "x"]}
                     :owners #{[:route :r "nav-b"]}})
          out (ssr/hydrate-runtime-db (runtime-db-with {ka ea kb eb}) :app/main)
          es  (get-in out [state/resources-key :entries])]
      (is (= {:owner "a"} (:data (es ka))) "scope-a data stays under scope-a's key")
      (is (= {:owner "b"} (:data (es kb))) "scope-b data stays under scope-b's key")
      (testing "the shared tag [:article \"x\"] maps to BOTH scoped keys, never collapsed"
        (is (= #{ka kb} (get-in out [state/resources-key :tag-index [:article "x"]]))))
      (testing "each scope's owner indexes only its own key"
        (is (= #{ka} (get-in out [state/resources-key :owner-index [:route :r "nav-a"]])))
        (is (= #{kb} (get-in out [state/resources-key :owner-index [:route :r "nav-b"]])))))))

;; ===========================================================================
;; 6. End-to-end through the :rf/hydrate reconcile hook
;; ===========================================================================

(deftest hooks-are-published
  (testing "both SSR late-bind hooks are published by the façade"
    (is (some? (late-bind/get-fn :ssr/extend-runtime-db-projection)))
    (is (some? (late-bind/get-fn :resources/hydrate-runtime-db)))
    (is (= ssr/project-resources-runtime-db
           (late-bind/get-fn :ssr/extend-runtime-db-projection)))
    (is (= ssr/hydrate-runtime-db
           (late-bind/get-fn :resources/hydrate-runtime-db)))))

(deftest project-runtime-db-merges-resource-slice
  (reg! :article/by-slug)
  (testing "SSR's project-runtime-db consults the resources hook and merges the :entries slice"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15})
          rdb (runtime-db-with {gkey e})
          ;; the SSR payload-policy is the consumer of :ssr/extend-runtime-db-projection
          proj (payload-policy/project-runtime-db rdb)]
      (is (contains? proj state/resources-key))
      (is (contains? (get-in proj [state/resources-key :entries]) gkey)))))

(deftest hydrate-event-reconciles-resource-slice
  (reg! :article/by-slug)
  (testing "the :rf/hydrate handler runs the resources reconcile hook on the installed runtime-db"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :tags #{[:article "x"]}
                      :current-work [:rf.work/resource gkey 1]
                      :owners #{[:ssr "req-1" "nav-1"]
                                [:route :route/article "nav-1"]}})
          payload {:rf/frame-id :rf/default
                   :rf/app-db   {}
                   :rf/runtime-db (runtime-db-with {gkey e})}]
      (rf/dispatch-sync [:rf/hydrate payload])
      (let [rdb (rf/runtime-db-value :rf/default)
            installed (get-in rdb [state/resources-key :entries gkey])]
        (is (= {:t "x"} (:data installed)) "entry data preserved through hydrate")
        (is (nil? (:current-work installed)) "transient current-work cleared")
        (is (not (contains? (:active-owners installed) [:ssr "req-1" "nav-1"]))
            "SSR owner orphaned during the :rf/hydrate reconcile")
        (is (contains? (:active-owners installed) [:route :route/article "nav-1"])
            "route owner survives")
        (is (= {[:article "x"] #{gkey}}
               (get-in rdb [state/resources-key :tag-index]))
            "tag-index recomputed from entries during the :rf/hydrate reconcile")))))
