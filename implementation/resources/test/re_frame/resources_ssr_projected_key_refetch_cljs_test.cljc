(ns re-frame.resources-ssr-projected-key-refetch-cljs-test
  "A `:serialize` entry whose own per-slot declaration RE-KEYS it is
  metadata-only and is deliberately refetched (rf2-rjq9d).

  ## The disagreement this suite closes

  A resource's `:scope` / `:params` declarations are projected into its SSR
  wire key (`project-entry-scope` at index 0, rf2-5e2ye; `project-entry-params`
  at index 2, rf2-d3pku1), and resource identity is `canonical-bytes` over the
  WHOLE key — so projecting either component CHANGES the entry's `key-id`, and
  `project-resources-runtime-db` installs the wire entry under the projected
  one. The live client never derives that identity: `route/route-resource-plan`
  and `events/ensure-handler` build the ordinary scoped key from live scope +
  params and read `(state/entry-path scoped-key)`, i.e. the RAW key-id. So the
  hydrated entry is unreachable and the client loads.

  Three answers were given to one question and they did not agree. The server
  shipped the entry WITH its data; `project-entry` reported
  `:refetch-on-client? false` (the entry was fresh and coarsely `:serialize`);
  `hydrate-refetch-plan` saw fresh usable `:data` and omitted the entry. Every
  one of those says *reuse this* — and the client could not.

  ## Why the miss is not the thing to fix

  Neither direction of \"make it addressable\" is available:

  - Keying the wire map on the RAW key-id would egress the declared slot in
    the clear. A `key-id` is a REVERSIBLE plaintext CEDN-1 encoding, which is
    exactly why rf2-5e2ye's suite asserts the raw key-id is absent from the
    whole payload slice.
  - Having the client re-derive the PROJECTED key-id and adopt the entry under
    it is worse than unavailable, it is unsafe. The per-slot substitution is
    the CONSTANT `:rf/redacted` / `:rf.size/large-elided` sentinel, NOT the
    content-addressed `{:rf/redacted <digest>}` token the coarse arm uses. The
    mapping is therefore MANY-TO-ONE on precisely the slots that carry
    principal identity: two tenants' keys project to one key (§4), and an
    adopt-by-projected-key client would read one principal's data under
    another's identity.

  So the entry is genuinely not reusable, and the fix is to say so. A re-keyed
  entry now classifies `:key-projected`: metadata-only on the wire, no `:data`,
  `:refetch-on-client? true`, present in the hydration refetch plan. The one
  load the client was always going to issue is now the intended one, and the
  ghost left behind under the projected key-id holds nothing — which is also
  what makes the many-to-one collapse harmless.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex.
  The managed-HTTP fx is stubbed with a COUNTER, so \"exactly one request\" is
  asserted rather than inferred."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as fx]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs and the :resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.classification :as classification]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as substrate]]
       :cljs [[re-frame.adapter.reagent :as substrate]])))

(def ^:private tenant-secret "tenant-SECRET-9931")
(def ^:private account-secret "acct-SECRET-4417")
(def ^:private other-tenant "tenant-SECRET-0002")

(defonce ^:private requests (atom 0))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Five owners spanning the grains this suite discriminates:

    :params/report — `:serialize`, declares `[:params :account-id]`. The
                     PARAMS arm (re-keyed since rf2-d3pku1).
    :scoped/report — `:serialize`, declares `[:scope :tenant-id]` under a
                     NAMED scope resolver, so a live `ensure` derives the
                     same scope the server entry was installed under. The
                     SCOPE arm (re-keyed since rf2-5e2ye).
    :plain/report  — declares NOTHING. The reuse control: its key is
                     byte-identical, so it stays addressable and must still
                     hydrate straight into a cache hit.
    :sealed/report — the COARSE `:sensitive?` owner.
    :bulky/report  — the COARSE `:large?` owner. Both coarse owners are here
                     to prove the new arm did not disturb them."
  []
  (reset! requests 0)
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "SSR projected-key refetch suite frame."})
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] (swap! requests inc)))
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
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- tenant-scope
  ([] (tenant-scope tenant-secret))
  ([tenant] [:rf.scope/session {:tenant-id tenant :region "au"}]))

(def ^:private params-key
  (delay (state/scoped-resource-key
           :rf.scope/global :params/report
           {:account-id account-secret :page 3})))

(defn- scoped-key-for
  ([] (scoped-key-for tenant-secret))
  ([tenant] (state/scoped-resource-key (tenant-scope tenant) :scoped/report {:page 3})))

(defn- global-key [resource-id]
  (state/scoped-resource-key :rf.scope/global resource-id {:page 3}))

(defn- install-entry!
  "Write a fresh `:loaded` entry for `scoped-key` AND reconcile the per-frame
  elision registry, so the durable projection has the lowered declaration to
  read — the two steps a real resource commit folds into one transition."
  [scoped-key]
  (frame/swap-runtime-db!
    :rf/default
    (fn [rdb]
      (-> (or rdb {})
          (assoc-in (state/entry-path scoped-key)
                    (assoc (state/empty-entry (second scoped-key) scoped-key)
                           :status    :loaded
                           :data      {:total 1}
                           :loaded-at 1000))
          (classification/reconcile-registry registry/resource-meta))))
  scoped-key)

(defn- install-all! []
  (install-entry! @params-key)
  (install-entry! (scoped-key-for))
  (install-entry! (global-key :plain/report))
  (install-entry! (global-key :sealed/report))
  (install-entry! (global-key :bulky/report)))

(defn- runtime-db [] (frame/frame-runtime-db-value :rf/default))

(defn- wire-slice []
  (ssr/project-resources-runtime-db (runtime-db) :rf/default))

(defn- wire-entries []
  (get-in (wire-slice) [state/resources-key :entries]))

(defn- wire-rows
  "Every wire entry whose key names `resource-id` (a SEQ, because §4 asks how
  MANY survive a many-to-one projection)."
  [resource-id]
  (filterv (fn [[_ e]] (= resource-id (second (:resource/key e))))
           (wire-entries)))

(defn- wire-entry-for [resource-id]
  (second (first (wire-rows resource-id))))

(defn- metadata-by-resource
  "`projection-metadata` keyed by resource-id. The metadata names each entry by
  its RAW `:resource/key`, so this reads the pre-projection identity."
  []
  (into {}
        (map (fn [m] [(second (:resource/key m)) m]))
        (ssr/projection-metadata
          :rf/default 5000
          (get-in (runtime-db) (state/entries-path)))))

(defn- boot-client!
  "Simulate the client boot: replace the durable resource subtree with EXACTLY
  what rides the wire, then run the hydrate reconcile. Returns the refetch
  plan. Everything else about the frame (the registrations, the elision
  registry) is what the client's own `reg-resource` calls give it."
  []
  (let [slice (wire-slice)]
    (frame/swap-runtime-db!
      :rf/default
      (fn [rdb] (assoc (or rdb {}) state/resources-key (get slice state/resources-key))))
    (ssr/hydrate-resources! :rf/default)))

(defn- live-entry
  "The read `route-resource-plan` and `ensure-handler` both perform: derive the
  ordinary scoped key from live scope + params, then `(state/entry-path k)`."
  [scoped-key]
  (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- plan-by-resource [plan]
  (into {} (map (fn [p] [(:resource-id p) p])) plan))

(defn- leaks? [secret v]
  (str/includes? (pr-str v) secret))

;; ===========================================================================
;; 1. THE CLASSIFICATION NOW MATCHES REACHABILITY.
;;
;;    Both arms — a `:params`-rooted declaration and a `:scope`-rooted one —
;;    re-key the entry, so both ship metadata-only and both report
;;    refetch-on-client.
;; ===========================================================================

(deftest a-params-declaration-re-keys-the-entry-and-it-ships-metadata-only
  (testing "rf2-rjq9d — the PARAMS arm: the entry is not addressable by the
            key the client derives, so it does not pretend to be reusable"
    (install-all!)
    (let [wired (wire-entries)
          e     (wire-entry-for :params/report)
          m     (:params/report (metadata-by-resource))]
      (is (not (contains? wired (state/key-id @params-key)))
          "premise: the raw key-id is NOT a wire map key — the entry re-keyed")
      (is (not (contains? e :data))
          "so its data is dropped: unreachable data is dead payload")
      (is (= :key-projected (:disposition m))
          "…and the metadata names WHY, rather than claiming :serialized")
      (is (true? (:refetch-on-client? m))
          "…and admits the client must fetch it")
      (is (= :fresh (:freshness m))
          "freshness is still reported honestly — the entry IS fresh; it is
           reachability, not staleness, that makes it unusable"))))

(deftest a-scope-declaration-re-keys-the-entry-and-it-ships-metadata-only
  (testing "rf2-rjq9d — the SCOPE arm, which #7255 extended the re-key to.
            Same identity break, same answer"
    (install-all!)
    (let [wired (wire-entries)
          e     (wire-entry-for :scoped/report)
          m     (:scoped/report (metadata-by-resource))]
      (is (not (contains? wired (state/key-id (scoped-key-for))))
          "premise: the raw key-id is NOT a wire map key")
      (is (not (contains? e :data)))
      (is (= :key-projected (:disposition m)))
      (is (true? (:refetch-on-client? m))))))

(deftest an-undeclared-owner-is-still-serialized-and-still-reusable
  (testing "the two-sided control: over-classification is a defect here. An
            owner declaring nothing keeps its byte identity, so it stays
            addressable and its data must still ride"
    (install-all!)
    (let [wired (wire-entries)
          e     (wire-entry-for :plain/report)
          m     (:plain/report (metadata-by-resource))]
      (is (contains? wired (state/key-id (global-key :plain/report)))
          "its key-id is unchanged — nothing re-keyed it")
      (is (= {:total 1} (:data e)) "so its data rides, as it always has")
      (is (= :serialized (:disposition m)))
      (is (false? (:refetch-on-client? m))
          "the no-double-fetch win SSR exists for is untouched"))))

(deftest the-coarse-arms-are-unchanged
  (testing "a per-slot KEY declaration is a third way to become metadata-only;
            it must not restate or disturb the two coarse ways"
    (install-all!)
    (let [m       (metadata-by-resource)
          sealed  (wire-entry-for :sealed/report)
          bulky   (wire-entry-for :bulky/report)]
      (is (= :redacted (:disposition (:sealed/report m))))
      (is (true? (:refetch-on-client? (:sealed/report m))))
      (is (= :rf/redacted (:data sealed)) "the redaction sentinel, as before")
      (is (= :omitted (:disposition (:bulky/report m))))
      (is (true? (:refetch-on-client? (:bulky/report m))))
      (is (not (contains? bulky :data)) "the data key is dropped, as before"))))

(deftest the-declared-slot-still-does-not-ride
  (testing "the privacy the declaration asked for is unchanged by dropping the
            data — this suite must not be able to pass by weakening rf2-5e2ye"
    (install-all!)
    (let [slice (wire-slice)]
      (is (not (leaks? tenant-secret slice)))
      (is (not (leaks? account-secret slice))))))

;; ===========================================================================
;; 2. THE HYDRATE PLAN AGREES WITH THE PROJECTION METADATA.
;;
;;    The bead's core defect: two independent deciders, one saying "reuse" and
;;    the other unable to.
;; ===========================================================================

(deftest the-refetch-plan-names-every-re-keyed-entry
  (testing "rf2-rjq9d — `hydrate-refetch-plan` must not omit an entry that
            `project-entry` marked refetch-on-client. It reaches the same
            answer independently: a re-keyed entry arrives with no `:data`"
    (install-all!)
    (let [by-id (plan-by-resource (boot-client!))]
      (is (contains? by-id :params/report) "the params arm is planned")
      (is (contains? by-id :scoped/report) "the scope arm is planned")
      (is (= :no-data (:reason (:params/report by-id)))
          "…for the reason that is actually true of the hydrated entry")
      (is (= :no-data (:reason (:scoped/report by-id))))
      (is (not (contains? by-id :plain/report))
          "and the addressable entry is still absent — no double-fetch"))))

(deftest the-plan-and-the-projection-metadata-cannot-disagree
  (testing "the invariant the bead asks for, stated over ALL five owners:
            an entry is in the hydration refetch plan iff the server marked it
            refetch-on-client"
    (install-all!)
    (let [marked  (into #{} (comp (filter (fn [[_ m]] (true? (:refetch-on-client? m))))
                                  (map key))
                        (metadata-by-resource))
          planned (set (keys (plan-by-resource (boot-client!))))]
      (is (= marked planned)
          (str "refetch-on-client? " (pr-str marked)
               " vs planned " (pr-str planned))))))

;; ===========================================================================
;; 3. THE LIVE READ. Server projection -> hydrate reconcile -> route/ensure.
;; ===========================================================================

(deftest a-re-keyed-entry-is-a-miss-under-the-identity-the-client-derives
  (testing "the mechanism, asserted directly against the read
            `route-resource-plan` and `ensure-handler` both perform"
    (install-all!)
    (boot-client!)
    (is (nil? (live-entry @params-key))
        "the params arm is unreachable by its raw scoped key")
    (is (nil? (live-entry (scoped-key-for)))
        "…and so is the scope arm")
    (is (some? (live-entry (global-key :plain/report)))
        "…while the undeclared control is exactly where the client looks")))

(deftest a-re-keyed-entry-costs-exactly-one-intentional-request
  (testing "rf2-rjq9d — the client issues ONE load, under the raw key, and the
            unreachable row it hydrated beside it holds no data to compete
            with the answer"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :params/report
                        :params   {:account-id account-secret :page 3}}])
    (is (= 1 @requests) "exactly one request — not zero, and not two")
    (is (some? (live-entry @params-key))
        "the entry now exists under the identity the client derives")
    (let [ghosts (filterv (fn [[k-id e]]
                            (and (= :params/report (second (:resource/key e)))
                                 (not= k-id (state/key-id @params-key))))
                          (get-in (runtime-db) (state/entries-path)))]
      (is (every? (fn [[_ e]] (not (contains? e :data))) ghosts)
          (str "no unreachable row may carry data beside the live entry — "
               (pr-str (mapv (comp :data second) ghosts)))))))

(deftest the-scope-arm-costs-exactly-one-intentional-request
  (testing "the same end-to-end statement for a `:scope`-rooted declaration,
            whose live scope comes from the NAMED resolver rather than the
            ensure payload"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :scoped/report :params {:page 3}}])
    (is (= 1 @requests))
    (is (some? (live-entry (scoped-key-for)))
        "the resolved session scope lands on the raw key, as the client
         derives it")))

(deftest an-addressable-entry-still-hydrates-into-a-cache-hit
  (testing "the control that stops this suite passing by making everything
            refetch: the undeclared owner's hydrated entry is reused with NO
            request at all"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :plain/report :params {:page 3}}])
    (is (= 0 @requests) "a fresh addressable hydrated entry is a cache hit")
    (is (= {:total 1} (:data (live-entry (global-key :plain/report))))
        "…serving the SSR data, which is the whole point of hydration")))

;; ===========================================================================
;; 4. THE MANY-TO-ONE PROJECTION, AND WHY DROPPING THE DATA IS WHAT MAKES IT
;;    SAFE.
;;
;;    The per-slot substitution is a CONSTANT sentinel, not a content-addressed
;;    digest (that is `redact-value`'s job, on the coarse arm — and its
;;    docstring names collapse as the hazard the digest avoids). So distinct
;;    principals collapse onto one wire key. That collapse predates this fix;
;;    what this fix guarantees is that the surviving row carries nothing.
;; ===========================================================================

(deftest two-principals-collapse-onto-one-wire-key-carrying-no-data
  (testing "rf2-rjq9d — two entries differing ONLY in the declared identity
            slot project to ONE key. This is why no client may adopt a
            hydrated entry by its projected key-id: the mapping back is
            many-to-one on exactly the slot that names the principal"
    (install-entry! (scoped-key-for tenant-secret))
    (install-entry! (scoped-key-for other-tenant))
    (let [live (filterv (fn [[_ e]] (= :scoped/report (second (:resource/key e))))
                        (get-in (runtime-db) (state/entries-path)))
          rows (wire-rows :scoped/report)]
      (is (= 2 (count live)) "premise: two DISTINCT durable entries")
      (is (= 1 (count rows))
          "…project to one wire row — the sentinel is not content-addressed")
      (is (not (contains? (second (first rows)) :data))
          "and that row carries NO data, so neither tenant's value can ever
           be read under the other's identity")
      (is (not (leaks? tenant-secret rows)))
      (is (not (leaks? other-tenant rows))))))
