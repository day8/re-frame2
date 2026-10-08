(ns re-frame.resources-ssr-projected-key-refetch-cljs-test
  "An entry the SSR projection RE-KEYS does not ride the hydration wire, and
  leaves no row behind on the client — whether a `:serialize` owner's own
  per-slot `:scope` / `:params` declaration re-keyed it or the coarse
  `:redact` / `:omit` tokenisation did.

  Resource identity is `canonical-bytes` over the WHOLE key, so projecting
  either component changes the entry's key-id, while the live client
  (`route/route-resource-plan`, `events/ensure-handler`) derives the RAW key.
  Neither repair is available: keying the wire on the raw key-id would egress
  the declared slot (a key-id is reversible plaintext), and adopting by the
  projected key-id is many-to-one on exactly the slots that name a principal
  (a constant sentinel per slot, a 32-bit digest per coarse component). So a
  re-keyed entry is withheld, reported `:refetch-on-client? true`, and dropped
  by the hydrate reconcile when an older render shipped it anyway.

  Every claim is stated as ABSENCE of the row over an exact key-id set, so
  over-withholding fails as loudly as under-withholding and no assertion can be
  satisfied by a nil row.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex.
  The managed-HTTP fx is stubbed with a COUNTER, so \"exactly one request\" is
  asserted rather than inferred."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs and the :resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as substrate]]
       :cljs [[re-frame.adapter.reagent :as substrate]])))

(def ^:private tenant-secret "tenant-SECRET-9931")
(def ^:private account-secret "acct-SECRET-4417")

(defonce ^:private requests (atom 0))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Six owners spanning the grains this suite discriminates:

    :params/report — `:serialize`, declares `[:params :account-id]`. The
                     PARAMS arm.
    :scoped/report — `:serialize`, declares `[:scope :tenant-id]` under a
                     NAMED scope resolver, so a live `ensure` derives the
                     same scope the server entry was installed under. The
                     SCOPE arm.
    :plain/report  — declares NOTHING: it stays addressable and must still
                     hydrate straight into a cache hit.
    :stale/report  — declares nothing either, and is installed STALE, so the
                     refetch plan is non-empty and a drop that reached too far
                     fails rather than passing vacuously.
    :sealed/report — the COARSE `:sensitive?` owner.
    :bulky/report  — the COARSE `:large?` owner."
  []
  (reset! requests 0)
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "SSR projected-key refetch suite frame."})
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] (swap! requests inc)))
  (rf/reg-event ::seed (fn [{:keys [db]} _] {:db (assoc db :tenant tenant-secret)}))
  (rf/dispatch-sync [::seed])
  (rf/reg-resource-scope :t/tenant
    {:inputs {:tenant [:db [:tenant]]}}
    (fn [{:keys [tenant]} _ctx]
      (when tenant [:rf.scope/session {:tenant-id tenant :region "au"}])))
  (rf/reg-resource :params/report
    {:scope         :rf.scope/global
     :sensitive     [[:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/params"}}))
  (rf/reg-resource :scoped/report
    {:scope         {:from-db :t/tenant}
     :sensitive     [[:scope :tenant-id]]
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/scoped"}}))
  (rf/reg-resource :plain/report
    {:scope         :rf.scope/global
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}}))
  (rf/reg-resource :stale/report
    {:scope         :rf.scope/global
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/stale"}}))
  (rf/reg-resource :sealed/report
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}}))
  (rf/reg-resource :bulky/report
    {:scope         :rf.scope/global
     :large?        true
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/bulky"}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private params-key
  (delay (rf.resources.state/scoped-resource-key
           :rf.scope/global :params/report
           {:account-id account-secret :page 3})))

(defn- scoped-key-for []
  (rf.resources.state/scoped-resource-key
    [:rf.scope/session {:tenant-id tenant-secret :region "au"}] :scoped/report {:page 3}))

(defn- global-key [resource-id]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource-id {:page 3}))

(defn- key-ids [& ks] (into #{} (map rf.resources.state/key-id) ks))

(defn- install-entry!
  "Write a `:loaded` entry for `scoped-key` AND reconcile the per-frame elision
  registry — the two steps a real resource commit folds into one transition.
  `stale?` sets a `:stale-at` in the past against both clocks this suite reads
  (the fixed 5000 of the metadata helper and the live epoch millis
  `hydrate-resources!` uses)."
  ([scoped-key] (install-entry! scoped-key false))
  ([scoped-key stale?]
   (rf.frame/swap-runtime-db!
     :rf/default
     (fn [rdb]
       (-> (or rdb {})
           (assoc-in (rf.resources.state/entry-path scoped-key)
                     (cond-> (assoc (rf.resources.state/empty-entry (second scoped-key) scoped-key)
                                    :status    :loaded
                                    :data      {:total 1}
                                    :loaded-at 1000)
                       stale? (assoc :stale-at 2000)))
           (rf.resources.classification/reconcile-registry rf.resources.registry/resource-meta))))
   scoped-key))

(defn- install-all! []
  (install-entry! @params-key)
  (install-entry! (scoped-key-for))
  (install-entry! (global-key :plain/report))
  (install-entry! (global-key :stale/report) true)
  (install-entry! (global-key :sealed/report))
  (install-entry! (global-key :bulky/report)))

(defn- runtime-db [] (rf.frame/frame-runtime-db-value :rf/default))

(defn- wire-slice []
  (rf.resources.ssr/project-resources-runtime-db (runtime-db) :rf/default))

(defn- metadata-by-resource
  "`projection-metadata` keyed by resource-id."
  []
  (into {}
        (map (fn [m] [(second (:resource/key m)) m]))
        (rf.resources.ssr/projection-metadata
          :rf/default 5000
          (get-in (runtime-db) (rf.resources.state/entries-path)))))

(defn- boot-client!
  "Simulate the client boot: replace the durable resource subtree with EXACTLY
  what rides the wire, then run the hydrate reconcile. Returns the refetch plan."
  []
  (let [slice (wire-slice)]
    (rf.frame/swap-runtime-db!
      :rf/default
      (fn [rdb] (assoc (or rdb {}) rf.resources.state/resources-key (get slice rf.resources.state/resources-key))))
    (rf.resources.ssr/hydrate-resources! :rf/default)))

(defn- live-entry
  "The read `route-resource-plan` and `ensure-handler` both perform: the entry
  at the ordinary scoped key the client derives."
  [scoped-key]
  (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- planned-resources [plan]
  (into #{} (map :resource-id) plan))

(defn- leaks? [secret v]
  (str/includes? (pr-str v) secret))

(defn- durable-entries []
  (get-in (runtime-db) (rf.resources.state/entries-path)))

(defn- rows-for
  "Every DURABLE row naming `resource-id`, as `[key-id entry]` pairs."
  [resource-id]
  (filterv (fn [[_ e]] (= resource-id (second (:resource/key e)))) (durable-entries)))

;; ---- a payload from an OLDER render ----------------------------------------
;;
;; Cached HTML rendered by an earlier deploy is routinely served to a newer JS
;; bundle, so these are the literal wire keys a projection that shipped the
;; re-keyed arms installs them under, written out so the forgery cannot drift
;; with the current projector. The two COARSE keys carry a digest the current
;; projector does NOT produce: the client's drop must read the SHAPE the
;; substitution leaves, never compare against a token it recomputed.

(def ^:private legacy-wire-keys
  [[:rf.scope/global :params/report {:account-id :rf/redacted :page 3}]
   [[:rf.scope/session {:tenant-id :rf/redacted :region "au"}] :scoped/report {:page 3}]
   [{:rf/redacted "0bs0lete"} :sealed/report {:rf/redacted "0bs0lete"}]
   [{:rf/redacted "0bs0lete"} :bulky/report {:rf/redacted "0bs0lete"}]])

(defn- legacy-row
  "One wire row as an earlier render emitted it, under the projected key.
  `data` nil is a METADATA-ONLY row, the shape `hydrate-refetch-plan` would
  name; a data-carrying row is fresh-with-usable-data and is excluded from the
  plan on freshness whether or not the addressability filter exists."
  [wire-key data]
  [(rf.resources.state/key-id wire-key)
   (cond-> (assoc (rf.resources.state/empty-entry (second wire-key) wire-key)
                  :status :loaded :loaded-at 1000)
     (some? data) (assoc :data data))])

(defn- slice-with-legacy-rows
  "The current wire slice PLUS the four rows an earlier render would have included."
  [data]
  (update-in (wire-slice) [rf.resources.state/resources-key :entries]
             into (map #(legacy-row % data)) legacy-wire-keys))

;; ===========================================================================

(deftest projection-metadata-accounts-for-every-entry
  ;; The server-side record of every projection decision, the withheld rows
  ;; included, each named by its RAW key.
  (install-all!)
  (let [row (fn [k disposition withheld? refetch? freshness]
              {:resource/key       k
               :status             :loaded
               :disposition        disposition
               :withheld?          withheld?
               :refetch-on-client? refetch?
               :freshness          freshness})]
    (is (= {:params/report (row @params-key :key-projected true true :fresh)
            :scoped/report (row (scoped-key-for) :key-projected true true :fresh)
            :plain/report  (row (global-key :plain/report) :serialized false false :fresh)
            :stale/report  (row (global-key :stale/report) :serialized false true :stale)
            :sealed/report (row (global-key :sealed/report) :redacted true true :fresh)
            :bulky/report  (row (global-key :bulky/report) :omitted true true :fresh)}
           (into {}
                 (map (fn [[rid m]]
                        [rid (select-keys m [:resource/key :status :disposition :withheld?
                                             :refetch-on-client? :freshness])]))
                 (metadata-by-resource))))))

(deftest the-wire-carries-exactly-the-addressable-rows
  ;; An exact set, so withholding one row too many fails as loudly as
  ;; withholding none. Neither a raw identity nor a token of either kind rides:
  ;; a 32-bit digest of a low-entropy identity is enumerable.
  (install-all!)
  (let [slice   (wire-slice)
        entries (get-in slice [rf.resources.state/resources-key :entries])]
    (is (= (key-ids (global-key :plain/report) (global-key :stale/report))
           (set (keys entries)))
        (pr-str (mapv (comp :resource/key second) entries)))
    (is (not (leaks? "rf/redacted" slice)) (pr-str slice))
    (is (not (leaks? tenant-secret slice)))
    (is (not (leaks? account-secret slice)))))

(deftest hydration-plans-only-the-stale-row-and-reuses-the-fresh-one
  ;; The plan names only what the server marked refetch-on-client AND shipped,
  ;; and the fresh addressable row is a cache hit — the no-double-fetch win.
  (install-all!)
  (is (= #{:stale/report} (planned-resources (boot-client!))))
  (reset! requests 0)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :plain/report :params {:page 3}}])
  (is (= 0 @requests) "a fresh addressable hydrated entry is a cache hit")
  (is (= {:total 1} (:data (live-entry (global-key :plain/report))))
      "…serving the SSR data"))

(deftest the-plan-refuses-an-unaddressable-row-it-is-handed-directly
  ;; `hydrate-refetch-plan` is a published entry point a host may call on a
  ;; slice it has not reconciled, so the property must hold for the FUNCTION.
  (install-all!)
  (is (every? #(rf.resources.ssr/entry-needs-refetch? (second (legacy-row % nil)) 5000)
              legacy-wire-keys)
      "premise: only addressability can keep the forged rows out of the plan")
  (is (= #{:stale/report}
         (planned-resources (rf.resources.ssr/hydrate-refetch-plan (slice-with-legacy-rows nil) 5000)))))

(deftest no-unaddressable-row-survives-hydration
  ;; After hydrate and an ensure of every re-keyed arm, the durable cache is
  ;; EXACTLY the six key-ids a live client derives. Each arm costs one request:
  ;; a reachable hydrated row would fresh-skip and send none.
  (install-all!)
  (boot-client!)
  (doseq [q [{:resource :params/report
              :params   {:account-id account-secret :page 3}}
             {:resource :scoped/report :params {:page 3}}
             {:resource :sealed/report :params {:page 3}}
             {:resource :bulky/report :params {:page 3}}]]
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure q])
    (is (= 1 @requests) (str "exactly one request for " (:resource q))))
  (is (= (key-ids @params-key (scoped-key-for) (global-key :plain/report)
                  (global-key :stale/report) (global-key :sealed/report)
                  (global-key :bulky/report))
         (set (keys (durable-entries))))
      (pr-str (mapv (comp :resource/key second) (durable-entries))))
  (is (not (leaks? "rf/redacted" (mapv (comp :resource/key second) (durable-entries))))
      "no surviving row's key carries a substitution of either kind"))

(deftest a-payload-from-an-older-render-leaves-no-row-behind
  ;; The forged rows carry their DATA, the strongest form of a row the client
  ;; must refuse; the drop is the hydrate reconcile's, not the projection's.
  (install-all!)
  (let [forged (get (slice-with-legacy-rows {:total 1}) rf.resources.state/resources-key)]
    (is (= 6 (count (:entries forged))) "premise: the payload carries the four re-keyed rows")
    (rf.frame/swap-runtime-db!
      :rf/default
      (fn [rdb] (assoc (or rdb {}) rf.resources.state/resources-key forged)))
    (rf.resources.ssr/hydrate-resources! :rf/default)
    (is (= (key-ids (global-key :plain/report) (global-key :stale/report))
           (set (keys (durable-entries))))
        "only the addressable rows survive — nothing was adopted under a derived key")
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :params/report
                        :params   {:account-id account-secret :page 3}}])
    (is (= 1 @requests) "one intentional load")
    (is (= [(rf.resources.state/key-id @params-key)] (mapv first (rows-for :params/report)))
        "and one row")))
