(ns re-frame.resources-ssr-projected-key-refetch-cljs-test
  "An entry the SSR projection RE-KEYS does not ride the hydration wire, and
  leaves no row behind on the client — whether a `:serialize` owner's own
  per-slot `:scope` / `:params` declaration re-keyed it or the
  coarse `:redact` / `:omit` tokenisation did.

  ## Why a re-keyed entry is unreachable

  A resource's `:scope` / `:params` declarations are projected into its SSR
  wire key (`project-entry-scope` at index 0; `project-entry-params`
  at index 2), and resource identity is `canonical-bytes` over the
  WHOLE key — so projecting either component CHANGES the entry's `key-id`, and
  `project-resources-runtime-db` installs the wire entry under the projected
  one. The live client never derives that identity: `route/route-resource-plan`
  and `events/ensure-handler` build the ordinary scoped key from live scope +
  params and read `(rf.resources.state/entry-path scoped-key)`, i.e. the RAW key-id. So the
  hydrated entry is unreachable and the client loads.

  Shipping such an entry would give three answers to one question that do not
  agree. The server would ship the entry WITH its data; `project-entry` would
  report `:refetch-on-client? false` (the entry is fresh and coarsely
  `:serialize`); `hydrate-refetch-plan` would see fresh usable `:data` and omit
  the entry. Every one of those says *reuse this* — and the client cannot.

  ## Why the miss is not the thing to fix

  Neither direction of \"make it addressable\" is available:

  - Keying the wire map on the RAW key-id would egress the declared slot in
    the clear. A `key-id` is a REVERSIBLE plaintext CEDN-1 encoding, which is
    exactly why the raw key-id must be absent from the
    whole payload slice.
  - Having the client re-derive the PROJECTED key-id and adopt the entry under
    it is worse than unavailable, it is unsafe. The per-slot substitution is
    the CONSTANT `:rf/redacted` / `:rf.size/large-elided` sentinel. The mapping
    is therefore MANY-TO-ONE on precisely the slots that carry principal
    identity: two tenants' keys project to one key (§4), and an
    adopt-by-projected-key client would read one principal's data under
    another's identity.

  So the entry is genuinely not reusable, and the projection says so. A re-keyed
  entry classifies `:key-projected`, reports `:refetch-on-client? true`, and
  does NOT ride: the row is withheld from the wire, and one arriving from an
  older render is dropped by the hydrate reconcile.

  ## And the coarse arms reach the same end (§6)

  The coarse `:redact` / `:omit` arm's `{:rf/redacted <digest>}` token is
  content-addressed, which suggests ADOPTION — the client re-deriving the
  digest from its live scope + params — as a repair the per-slot arm lacks.
  It is not one, on three counts, and this suite pins
  each:

    - derivability is necessary, not sufficient. A browser reproduces a JVM
      server's digest only because `redact-value`'s two branches agree:
      `fnv-1a-32` is byte-identical across the two, pinned by the shared
      fixture in `resources-ssr-cljs-test` — which removes that obstacle without
      making adoption safe;
    - the token is not ONE-TO-ONE and not one-way. It is a 32-bit
      non-cryptographic hash, so two principals can collide (the same
      cross-principal read as §4, probabilistic rather than certain) and a
      low-entropy tenant id is recoverable from its digest by enumeration —
      which makes SHIPPING it a small egress of the identity the coarse claim
      asks to hide;
    - and adoption would buy nothing. A coarse entry is
      metadata-only by construction, so `entry-needs-refetch?` is true of every
      one of them and the client loads either way.

  So the coarse row is unreachable exactly as the per-slot
  one, and it is withheld and dropped by the same two points. §6 states that
  end-to-end; §1's and §3's exact key-id sets are what make it two-sided.

  ## Why emptying the row is not enough

  Shipping the row metadata-only, and asserting that any surviving ghost
  carries no `:data`, would PERMIT the defect. An emptied row is still a row:
  it installs, it survives the reconcile, and it sits in `:entries` beside the
  entry `ensure` writes under the raw key — reachable by nothing.
  The criterion is *no persistent unreachable duplicate*, and a data-less
  duplicate is still a duplicate. The refetch plan would name it too, and so
  carry an identity no live derivation reproduces.

  Every assertion here is therefore stated as ABSENCE of the row rather than
  absence of its contents, and §1's `the-wire-carries-exactly-the-addressable-
  rows` / §3's `no-unaddressable-row-survives-hydration` pin the EXACT key-id
  set — so over-withholding fails just as loudly as under-withholding, and no
  assertion in this suite can be satisfied vacuously by a nil row.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex.
  The managed-HTTP fx is stubbed with a COUNTER, so \"exactly one request\" is
  asserted rather than inferred."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.set :as set]
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
(def ^:private other-tenant "tenant-SECRET-0002")

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
    :plain/report  — declares NOTHING. The reuse control: its key is
                     byte-identical, so it stays addressable and must still
                     hydrate straight into a cache hit.
    :stale/report  — declares nothing either, and is installed STALE. The
                     PLAN control: withholding both coarse arms
                     empties the refetch plan of everything else, and \"the
                     plan is empty\" would satisfy every plan assertion in §2
                     vacuously. This owner is addressable AND planned, so a
                     drop that reached too far fails there rather than passing.
    :sealed/report — the COARSE `:sensitive?` owner.
    :bulky/report  — the COARSE `:large?` owner. Both coarse owners re-key on
                     BOTH components, so neither rides either (§6)."
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

(defn- tenant-scope
  ([] (tenant-scope tenant-secret))
  ([tenant] [:rf.scope/session {:tenant-id tenant :region "au"}]))

(def ^:private params-key
  (delay (rf.resources.state/scoped-resource-key
           :rf.scope/global :params/report
           {:account-id account-secret :page 3})))

(defn- scoped-key-for
  ([] (scoped-key-for tenant-secret))
  ([tenant] (rf.resources.state/scoped-resource-key (tenant-scope tenant) :scoped/report {:page 3})))

(defn- global-key [resource-id]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource-id {:page 3}))

(defn- install-entry!
  "Write a `:loaded` entry for `scoped-key` AND reconcile the per-frame elision
  registry, so the durable projection has the lowered declaration to read — the
  two steps a real resource commit folds into one transition. `stale?` sets an
  absolute `:stale-at` in the past, which is stale against both clocks this
  suite reads (the fixed 5000 the metadata helper passes, and the live epoch
  millis `hydrate-resources!` uses)."
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

(defn- wire-entries []
  (get-in (wire-slice) [rf.resources.state/resources-key :entries]))

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
        (rf.resources.ssr/projection-metadata
          :rf/default 5000
          (get-in (runtime-db) (rf.resources.state/entries-path)))))

(defn- boot-client!
  "Simulate the client boot: replace the durable resource subtree with EXACTLY
  what rides the wire, then run the hydrate reconcile. Returns the refetch
  plan. Everything else about the frame (the registrations, the elision
  registry) is what the client's own `reg-resource` calls give it."
  []
  (let [slice (wire-slice)]
    (rf.frame/swap-runtime-db!
      :rf/default
      (fn [rdb] (assoc (or rdb {}) rf.resources.state/resources-key (get slice rf.resources.state/resources-key))))
    (rf.resources.ssr/hydrate-resources! :rf/default)))

(defn- live-entry
  "The read `route-resource-plan` and `ensure-handler` both perform: derive the
  ordinary scoped key from live scope + params, then `(rf.resources.state/entry-path k)`."
  [scoped-key]
  (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- plan-by-resource [plan]
  (into {} (map (fn [p] [(:resource-id p) p])) plan))

(defn- leaks? [secret v]
  (str/includes? (pr-str v) secret))

(defn- durable-entries []
  (get-in (runtime-db) (rf.resources.state/entries-path)))

(defn- rows-for
  "Every DURABLE row naming `resource-id`, as `[key-id entry]` pairs."
  [resource-id]
  (filterv (fn [[_ e]] (= resource-id (second (:resource/key e)))) (durable-entries)))

(defn- coarse-wire-key-id
  "The key-id the COARSE `:redact` / `:omit` projection installs a global-scope
  entry under. Derived through the production projector rather than restated, so
  this suite pins the coarse arm's PRESENCE without pinning its digest."
  [resource-id]
  (rf.resources.state/key-id (rf.resources.ssr/project-scoped-key (global-key resource-id) :redact nil)))

;; ---- a payload from an OLDER render (the version-skew forgery) -------------
;;
;; A hydration payload is HTML, and cached HTML rendered by an earlier deploy is
;; routinely served to a newer JS bundle — so "the server no longer ships it" is
;; not on its own a guarantee about the client's cache. These four keys are the
;; literal wire keys a projection that ships the re-keyed arms installs them
;; under. They are written out rather than derived, so the forgery states the
;; BYTES an older render emits and cannot drift with the current projector.
;;
;; The two per-slot keys carry the constant sentinel substituted into the
;; declared slot, nested inside the component. The two COARSE keys
;; carry a whole-component `{:rf/redacted <digest>}` token whose digest is
;; DELIBERATELY not one the current projector produces: an older render can
;; carry a digest the current projector would not compute for the same value,
;; and more importantly the client's drop must be driven by the
;; SHAPE the substitution leaves, never by equality with a token it recomputed.
;; A forgery using the current digest could pass with a shape test that only
;; ever compared against `project-scoped-key`'s own output.

(def ^:private legacy-params-wire-key
  [:rf.scope/global :params/report {:account-id :rf/redacted :page 3}])

(def ^:private legacy-scope-wire-key
  [[:rf.scope/session {:tenant-id :rf/redacted :region "au"}] :scoped/report {:page 3}])

(def ^:private legacy-sealed-wire-key
  [{:rf/redacted "0bs0lete"} :sealed/report {:rf/redacted "0bs0lete"}])

(def ^:private legacy-bulky-wire-key
  [{:rf/redacted "0bs0lete"} :bulky/report {:rf/redacted "0bs0lete"}])

(defn- legacy-row
  "One wire row as an earlier render emitted it, under the projected key.

  `data` selects between the two shapes an older render can emit, and which one
  a test wants is never arbitrary. `{:total 1}` is a row carrying
  its data — the strongest form of the row a client must refuse, and so the one
  the cache controls forge. `nil` is a metadata-only row —
  the shape that reaches `hydrate-refetch-plan` and would be NAMED there.
  A data-carrying row cannot stand in for it:
  it is fresh-with-usable-data, so `entry-needs-refetch?` excludes it on
  FRESHNESS and the plan omits it whether or not the addressability filter
  exists."
  [wire-key data]
  [(rf.resources.state/key-id wire-key)
   (cond-> (assoc (rf.resources.state/empty-entry (second wire-key) wire-key)
                  :status :loaded :loaded-at 1000)
     (some? data) (assoc :data data))])

(defn- slice-with-legacy-rows
  "The current wire slice PLUS the four rows an earlier render would have included,
  in the shape `data` selects (see `legacy-row`)."
  [data]
  (update-in (wire-slice) [rf.resources.state/resources-key :entries]
             (fn [entries]
               (into entries [(legacy-row legacy-params-wire-key data)
                              (legacy-row legacy-scope-wire-key data)
                              (legacy-row legacy-sealed-wire-key data)
                              (legacy-row legacy-bulky-wire-key data)]))))

;; ===========================================================================
;; 1. THE ROW DOES NOT RIDE.
;;
;;    Both arms — a `:params`-rooted declaration and a `:scope`-rooted one —
;;    re-key the entry, and a re-keyed row is WITHHELD rather than emptied.
;;    Asserting `(not (contains? e :data))` would let a ghost through: it
;;    is satisfied by a nil `e`, so it can neither distinguish "shipped empty"
;;    from "not shipped" nor notice a row that persists.
;; ===========================================================================

(deftest a-params-declaration-re-keys-the-entry-and-the-row-does-not-ride
  (testing "the PARAMS arm: the entry is not addressable by the
            key the client derives, so it is not sent under any key"
    (install-all!)
    (let [wired (wire-entries)
          m     (:params/report (metadata-by-resource))]
      (is (not (contains? wired (rf.resources.state/key-id @params-key)))
          "premise: the raw key-id is NOT a wire map key — the entry re-keyed")
      (is (empty? (wire-rows :params/report))
          (str "…and NO row rides under the projected key either — an emptied "
               "row is still an unreachable row: " (pr-str (wire-rows :params/report))))
      (is (= :key-projected (:disposition m))
          "…while the SERVER-side metadata still names WHY, rather than
           claiming :serialized or falling silent about the entry")
      (is (true? (:refetch-on-client? m))
          "…and admits the client must fetch it")
      (is (= @params-key (:resource/key m))
          "the metadata names the RAW key — it is a diagnostic over the
           server's own entries, not a wire identity")
      (is (= :fresh (:freshness m))
          "freshness is still reported honestly — the entry IS fresh; it is
           reachability, not staleness, that makes it unusable"))))

(deftest a-scope-declaration-re-keys-the-entry-and-the-row-does-not-ride
  (testing "the SCOPE arm, which re-keys too.
            Same identity break, same answer"
    (install-all!)
    (let [wired (wire-entries)
          m     (:scoped/report (metadata-by-resource))]
      (is (not (contains? wired (rf.resources.state/key-id (scoped-key-for))))
          "premise: the raw key-id is NOT a wire map key")
      (is (empty? (wire-rows :scoped/report))
          (str "…and no row rides under the projected key either: "
               (pr-str (wire-rows :scoped/report))))
      (is (= :key-projected (:disposition m)))
      (is (true? (:refetch-on-client? m))))))

(deftest the-wire-carries-exactly-the-addressable-rows
  (testing "the two-sided control on WITHHOLDING, stated
            as an exact set. Six durable entries, two wire rows: withholding one
            row too many fails here just as loudly as withholding none"
    (install-all!)
    (is (= #{(rf.resources.state/key-id (global-key :plain/report))
             (rf.resources.state/key-id (global-key :stale/report))}
           (set (keys (wire-entries))))
        (str "the wire carries exactly the rows a live client can address — "
             (pr-str (mapv (comp :resource/key second) (wire-entries)))))
    (is (not (contains? (wire-entries) (coarse-wire-key-id :sealed/report)))
        "…and NOT under a coarse digest either: the row is absent, not merely
         emptied")
    (is (not (contains? (wire-entries) (coarse-wire-key-id :bulky/report))))
    (is (= 6 (count (durable-entries)))
        "…while the server's own cache still holds all six: the withholding
         is a projection decision, not an eviction")))

(deftest an-undeclared-owner-is-still-serialized-and-still-reusable
  (testing "the two-sided control: over-classification is a defect here. An
            owner declaring nothing keeps its byte identity, so it stays
            addressable and its data must still ride"
    (install-all!)
    (let [wired (wire-entries)
          e     (wire-entry-for :plain/report)
          m     (:plain/report (metadata-by-resource))]
      (is (contains? wired (rf.resources.state/key-id (global-key :plain/report)))
          "its key-id is unchanged — nothing re-keyed it")
      (is (= {:total 1} (:data e)) "so its data rides")
      (is (= :serialized (:disposition m)))
      (is (false? (:refetch-on-client? m))
          "the no-double-fetch win SSR exists for holds"))))

(deftest the-coarse-arms-do-not-ride-either
  (testing "a coarse `:redact` / `:omit` key is re-keyed on BOTH
            components, so its row is unaddressable exactly
            as a per-slot-declared one. It is withheld too — and the server's
            own metadata still accounts for it in full, which is what makes
            withholding a projection decision rather than a silence"
    (install-all!)
    (let [m (metadata-by-resource)]
      (is (empty? (wire-rows :sealed/report))
          (str "the coarse :sensitive? row does not ride under ANY key — "
               (pr-str (wire-rows :sealed/report))))
      (is (empty? (wire-rows :bulky/report))
          (str "…nor the coarse :large? one — " (pr-str (wire-rows :bulky/report))))
      (is (= :redacted (:disposition (:sealed/report m)))
          "the metadata still names WHY it could not ride, rather than
           falling silent about the entry")
      (is (= :omitted (:disposition (:bulky/report m))))
      (is (true? (:withheld? (:sealed/report m))))
      (is (true? (:withheld? (:bulky/report m))))
      (is (true? (:refetch-on-client? (:sealed/report m))))
      (is (true? (:refetch-on-client? (:bulky/report m))))
      (is (= (global-key :sealed/report) (:resource/key (:sealed/report m)))
          "the metadata names the RAW key — it is a diagnostic over the
           server's own entries, not a wire identity")
      (is (= (rf.resources.ssr/project-scoped-key (global-key :sealed/report) :redact nil)
             (:projected-key (:sealed/report m)))
          "…while :projected-key carries the observation point a wire row
           would carry, so the SSR and trace-egress derivations of one answer
           can be compared"))))

(deftest withholding-is-decided-by-identity-not-by-disposition
  (testing "the withholding rule is ONE exact question asked of every
            entry (did the projection preserve its key-id?), not an enumeration
            of dispositions. Stated over the whole cache, it is what stops a
            future re-keying projection shipping a ghost by default"
    (install-all!)
    (doseq [m (rf.resources.ssr/projection-metadata
                :rf/default 5000
                (get-in (runtime-db) (rf.resources.state/entries-path)))]
      (is (= (:withheld? m)
             (not= (rf.resources.state/key-id (:resource/key m))
                   (rf.resources.state/key-id (:projected-key m))))
          (str "withheld? iff the projection re-keyed it — " (pr-str m)))
      (is (= (not (:withheld? m))
             (contains? (wire-entries) (rf.resources.state/key-id (:projected-key m))))
          (str "…and the wire agrees, row for row — " (pr-str m))))))

(deftest the-declared-slot-still-does-not-ride
  (testing "the declared slot never rides — this suite must not be able to
            pass by weakening the per-slot projection"
    (install-all!)
    (let [slice (wire-slice)]
      (is (not (leaks? tenant-secret slice)))
      (is (not (leaks? account-secret slice))))))

;; ===========================================================================
;; 2. THE HYDRATE PLAN NAMES ONLY IDENTITIES THE CLIENT HAS.
;;
;;    Two independent deciders must not disagree, one saying "reuse" and the
;;    other unable to. Nor may the plan name the PROJECTED identity — a
;;    `:resource/key` the route slice would carry into `:rf.resource/refetch`
;;    and that no live derivation reproduces. A plan entry nobody can act on
;;    is not a plan entry.
;; ===========================================================================

(deftest the-refetch-plan-names-no-identity-the-client-cannot-derive
  (testing "every re-keyed arm is ABSENT from the plan
            (its row never arrives), while the STALE addressable owner stays
            present: the plan must not become empty, only truthful"
    (install-all!)
    (let [plan  (boot-client!)
          by-id (plan-by-resource plan)]
      (is (not (contains? by-id :params/report))
          "the params arm is not planned — there is no hydrated row to plan,
           and its projected key names a fetch nobody could issue")
      (is (not (contains? by-id :scoped/report)) "…nor the scope arm")
      (is (not (contains? by-id :sealed/report))
          (str "…nor the coarse redaction — planning it under its projected "
               "key would name a refetch of an identity the route slice "
               "cannot resolve: " (pr-str plan)))
      (is (not (contains? by-id :bulky/report)) "…nor the coarse omission")
      (is (= #{:stale/report} (set (keys by-id)))
          (str "the plan is exactly the stale addressable owner — the control "
               "that stops this passing by planning nothing: " (pr-str plan)))
      (is (= :stale (:reason (by-id :stale/report))))
      (is (not (contains? by-id :plain/report))
          "and the addressable fresh entry is still absent — no double-fetch")
      (is (not (leaks? "rf/redacted" plan))
          (str "no plan row names a projected key of EITHER kind — the per-slot "
               "sentinel and the coarse token are both spelled in the reserved "
               ":rf/* namespace, so this one claim covers both: " (pr-str plan))))))

(deftest the-plan-and-the-projection-metadata-cannot-disagree
  (testing "the invariant, stated for a contract that
            WITHHOLDS: an entry is planned iff the server marked it
            refetch-on-client AND shipped it. The gap between the two sets is
            not slack — it is exactly the withheld set, asserted as such"
    (install-all!)
    (let [meta-by-id (metadata-by-resource)
          marked     (into #{} (comp (filter (fn [[_ m]] (true? (:refetch-on-client? m))))
                                     (map key))
                           meta-by-id)
          withheld   (into #{} (comp (filter (fn [[_ m]] (true? (:withheld? m))))
                                     (map key))
                           meta-by-id)
          planned    (set (keys (plan-by-resource (boot-client!))))]
      (is (= #{:params/report :scoped/report :sealed/report :bulky/report :stale/report} marked)
          "premise: five of the six owners are marked refetch-on-client")
      (is (= #{:params/report :scoped/report :sealed/report :bulky/report} withheld)
          "premise: exactly the four re-keyed owners are withheld — the two
           per-slot arms and the two coarse ones")
      (is (= (set/difference marked withheld) planned)
          (str "planned must be marked-minus-withheld — refetch-on-client? "
               (pr-str marked) ", withheld " (pr-str withheld)
               ", planned " (pr-str planned)))
      (is (empty? (set/difference planned marked))
          "and nothing is planned that the server did not mark: the two
           deciders still cannot disagree in the dangerous direction"))))

(deftest the-plan-refuses-an-unaddressable-row-it-is-handed-directly
  (testing "`hydrate-refetch-plan` is a published entry point a
            host may call on a payload slice it has not reconciled, so the
            property must hold for the FUNCTION, not only for the path that
            drops the row first"
    (install-all!)
    ;; the METADATA-ONLY forgery (`nil` data) is load-bearing: it is the shape
    ;; the plan would name. A data-carrying row
    ;; would be excluded on freshness instead, and this test would pass with the
    ;; addressability filter deleted.
    (let [forged (get (slice-with-legacy-rows nil) rf.resources.state/resources-key)
          row-of (fn [rid] (some (fn [[_ e]] (when (= rid (second (:resource/key e))) e))
                                 (:entries forged)))
          plan   (rf.resources.ssr/hydrate-refetch-plan {rf.resources.state/resources-key forged} 5000)
          by-id  (plan-by-resource plan)]
      (doseq [rid [:params/report :sealed/report :bulky/report]]
        (let [row (row-of rid)]
          (is (some? row)
              (str "premise: the forged slice carries an unaddressable row for " rid))
          (is (true? (rf.resources.ssr/entry-needs-refetch? row 5000))
              (str "premise: and one the planner WOULD name — every other reason "
                   "to omit it is absent, so only addressability can be doing "
                   "the work for " rid))
          (is (not (contains? by-id rid))
              (str "…which the plan refuses to name: " rid))))
      (is (contains? by-id :stale/report)
          "while the addressable stale row handed to it the same way IS planned
           — the control that stops this passing by refusing everything"))))

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
  (testing "the client issues ONE load, under the raw key, and NO
            unreachable row is left standing beside the answer. Asserting
            `every? (not (contains? e :data))` over the ghosts would be true
            of a ghost that persists — the exact defect
            this forbids"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :params/report
                        :params   {:account-id account-secret :page 3}}])
    (is (= 1 @requests) "exactly one request — not zero, and not two")
    (is (some? (live-entry @params-key))
        "the entry now exists under the identity the client derives")
    (is (= [(rf.resources.state/key-id @params-key)] (mapv first (rows-for :params/report)))
        (str "…and it is the ONLY row for this resource: no unreachable "
             "duplicate persists beside it — "
             (pr-str (mapv (comp :resource/key second) (rows-for :params/report)))))))

(deftest the-scope-arm-costs-exactly-one-intentional-request
  (testing "the same end-to-end statement for a `:scope`-rooted declaration,
            whose live scope comes from the NAMED resolver rather than the
            ensure payload — including the same no-duplicate claim made
            for the params arm"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :scoped/report :params {:page 3}}])
    (is (= 1 @requests))
    (is (some? (live-entry (scoped-key-for)))
        "the resolved session scope lands on the raw key, as the client
         derives it")
    (is (= [(rf.resources.state/key-id (scoped-key-for))] (mapv first (rows-for :scoped/report)))
        (str "…and it is the only row for this resource — "
             (pr-str (mapv (comp :resource/key second) (rows-for :scoped/report)))))))

(deftest no-unaddressable-row-survives-hydration
  (testing "the criterion stated ONCE over the
            whole cache rather than per resource: after hydrate and after every
            re-keyed arm is ensured, the durable `:entries` map is EXACTLY the
            six key-ids a live client can derive. An exact set is what makes
            this two-sided — a surviving ghost adds a key and fails, and an
            over-eager drop removes one and fails just as loudly"
    (install-all!)
    (boot-client!)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :params/report
                        :params   {:account-id account-secret :page 3}}])
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :scoped/report :params {:page 3}}])
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :sealed/report :params {:page 3}}])
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :bulky/report :params {:page 3}}])
    (is (= #{(rf.resources.state/key-id @params-key)
             (rf.resources.state/key-id (scoped-key-for))
             (rf.resources.state/key-id (global-key :plain/report))
             (rf.resources.state/key-id (global-key :stale/report))
             (rf.resources.state/key-id (global-key :sealed/report))
             (rf.resources.state/key-id (global-key :bulky/report))}
           (set (keys (durable-entries))))
        (str "the client's cache holds exactly the addressable rows — every "
             "coarse entry sits under the key the client DERIVES, and neither "
             "coarse digest is a key at all — "
             (pr-str (mapv (comp :resource/key second) (durable-entries)))))
    (is (not (contains? (durable-entries) (coarse-wire-key-id :sealed/report)))
        "…stated again against the digest itself, so the claim cannot be read
         as being about counts")
    (is (not (contains? (durable-entries) (coarse-wire-key-id :bulky/report))))
    (is (not (leaks? "rf/redacted" (mapv (comp :resource/key second) (durable-entries))))
        "and no surviving row's key carries a substitution of either kind")))

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
;;    The per-slot substitution is a CONSTANT sentinel, so distinct principals
;;    collapse onto one wire key DETERMINISTICALLY. What withholding
;;    guarantees is that there is no surviving row to collapse ONTO. One row
;;    carrying nothing would be the weaker of the two available statements,
;;    and would let a ghost stand.
;;
;;    The coarse token is content-addressed, so it collapses two principals only
;;    on a hash collision rather than always. That is a difference of PROBABILITY
;;    and not of kind — `fnv-1a-32` is 32 bits — which is one of the three
;;    reasons §6 withholds those rows as well.
;; ===========================================================================

(deftest two-principals-that-would-collapse-onto-one-wire-key-are-both-withheld
  (testing "two entries differing ONLY in the declared identity
            slot project to ONE key. This is why no client may adopt a
            hydrated entry by its projected key-id: the mapping back is
            many-to-one on exactly the slot that names the principal. Neither
            rides, so the collapse has nothing to happen to"
    (install-entry! (scoped-key-for tenant-secret))
    (install-entry! (scoped-key-for other-tenant))
    (let [live (rows-for :scoped/report)
          rows (wire-rows :scoped/report)]
      (is (= 2 (count live)) "premise: two DISTINCT durable entries")
      (is (= 0 (count rows))
          (str "…and ZERO wire rows: the collapse is vacuous because neither "
               "principal's row is sent — " (pr-str rows)))
      (is (not (leaks? tenant-secret (wire-slice))))
      (is (not (leaks? other-tenant (wire-slice)))))))

;; ===========================================================================
;; 5. NEVER TRUST THE WIRE.
;;
;;    Withholding is a decision of the server that rendered the page, and a
;;    hydration payload is HTML: cached HTML from an earlier deploy reaches a
;;    newer bundle routinely. So the client must reach the same end state for a
;;    payload it did not produce — including one whose re-keyed rows carry
;;    their DATA.
;; ===========================================================================

(deftest a-payload-from-an-older-render-leaves-no-row-behind
  (testing "the hydrate reconcile drops a row whose key no live
            derivation reproduces, exactly as `recompute-indexes` refuses to
            trust the wire's indexes"
    (install-all!)
    (let [forged (get (slice-with-legacy-rows {:total 1}) rf.resources.state/resources-key)]
      (is (= 6 (count (:entries forged)))
          "premise: the forged payload carries the four withheld rows too")
      (is (some (fn [[_ e]] (some? (:data e)))
                (filterv (fn [[_ e]] (= :params/report (second (:resource/key e))))
                         (:entries forged)))
          "premise: and the older render's row carries its DATA")
      (rf.frame/swap-runtime-db!
        :rf/default
        (fn [rdb] (assoc (or rdb {}) rf.resources.state/resources-key forged)))
      (rf.resources.ssr/hydrate-resources! :rf/default)
      (is (empty? (rows-for :params/report))
          (str "the unaddressable row is gone after hydrate — "
               (pr-str (mapv (comp :resource/key second) (rows-for :params/report)))))
      (is (empty? (rows-for :scoped/report)))
      (is (empty? (rows-for :sealed/report))
          (str "…and so is the coarse one, whose token this payload spelled with "
               "a digest the current projector does not produce: the drop reads the "
               "SHAPE, not a recomputed value — "
               (pr-str (mapv (comp :resource/key second) (rows-for :sealed/report)))))
      (is (empty? (rows-for :bulky/report)))
      (is (= 2 (count (durable-entries)))
          "…leaving exactly the two addressable rows")
      (is (nil? (live-entry @params-key))
          "and its data never became readable under the identity the client
           derives — dropping the row is not adopting it")
      (is (nil? (live-entry (global-key :sealed/report)))
          "…nor the coarse one's"))))

(deftest an-older-payload-still-costs-exactly-one-request
  (testing "the end-to-end consequence: a client hydrating an older render's payload
            behaves identically to one hydrating a withheld payload — one
            intentional load, and nothing left over"
    (install-all!)
    (let [forged (get (slice-with-legacy-rows {:total 1}) rf.resources.state/resources-key)]
      (rf.frame/swap-runtime-db!
        :rf/default
        (fn [rdb] (assoc (or rdb {}) rf.resources.state/resources-key forged)))
      (rf.resources.ssr/hydrate-resources! :rf/default)
      (reset! requests 0)
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :params/report
                          :params   {:account-id account-secret :page 3}}])
      (is (= 1 @requests) "exactly one request")
      (is (= [(rf.resources.state/key-id @params-key)] (mapv first (rows-for :params/report)))
          "and one row"))))

;; ===========================================================================
;; 6. THE COARSE ARMS.
;;
;;    Same property, same two removal points, and the same end-to-end statement:
;;    ONE request, and NO row left standing beside the answer. A witness
;;    asserting that a ghost carries no `:data` would pass while the ghost
;;    persists, so every claim here is
;;    about the ROW.
;; ===========================================================================

(deftest a-coarse-redacted-row-costs-exactly-one-intentional-request
  (testing "the `:sensitive?` owner end to end: hydrate installs no
            coarse row, the client's own ensure writes ONE entry under the key
            it derives, and no unreachable duplicate persists beside it"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :sealed/report :params {:page 3}}])
    (is (= 1 @requests) "exactly one request — not zero, and not two")
    (is (some? (live-entry (global-key :sealed/report)))
        "the entry now exists under the identity the client derives")
    (is (= [(rf.resources.state/key-id (global-key :sealed/report))]
           (mapv first (rows-for :sealed/report)))
        (str "…and it is the ONLY row for this resource: no unreachable "
             "duplicate persists beside it — "
             (pr-str (mapv (comp :resource/key second) (rows-for :sealed/report)))))))

(deftest a-coarse-omitted-row-costs-exactly-one-intentional-request
  (testing "the same statement for the `:large?` owner, whose
            projection drops the `:data` key rather than replacing it. The two
            coarse arms differ in what they do to the data and not at all in
            what they do to the key, so both must be witnessed"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :bulky/report :params {:page 3}}])
    (is (= 1 @requests))
    (is (= [(rf.resources.state/key-id (global-key :bulky/report))]
           (mapv first (rows-for :bulky/report)))
        (str "the only row for this resource — "
             (pr-str (mapv (comp :resource/key second) (rows-for :bulky/report)))))))

(deftest a-coarse-row-was-uncollectable-which-is-why-emptying-it-is-not-enough
  (testing "removal, not an emptied row: an ownerless hydrated row
            is reachable by nothing, so the claim is made against the
            hydrated cache directly — after hydrate there is no coarse row for a
            collector to want"
    (install-all!)
    (boot-client!)
    (is (empty? (rows-for :sealed/report))
        (str "no coarse row is installed at all — so there is nothing for a "
             "collector to have to reach: "
             (pr-str (mapv (comp :resource/key second) (rows-for :sealed/report)))))
    (is (empty? (rows-for :bulky/report)))
    (is (some? (live-entry (global-key :plain/report)))
        "while the addressable control still hydrated — the drop is targeted")))

(deftest a-coarse-digest-does-not-egress-from-an-ssr-render
  (testing "a 32-bit digest of a low-entropy identity is
            enumerable, so shipping the coarse token would itself be a small
            egress of the identity the coarse claim asks to hide. Withholding
            the row removes the last carrier: the raw values are absent, and
            so are the digests"
    (install-all!)
    (let [slice (wire-slice)]
      (is (not (leaks? "rf/redacted" slice))
          (str "no `:rf/redacted` token of either kind rides the wire slice — "
               (pr-str slice)))
      (is (not (leaks? tenant-secret slice)) "…and the raw identities still do not")
      (is (not (leaks? account-secret slice))))))
