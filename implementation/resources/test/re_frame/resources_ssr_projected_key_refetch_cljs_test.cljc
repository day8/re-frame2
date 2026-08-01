(ns re-frame.resources-ssr-projected-key-refetch-cljs-test
  "A `:serialize` entry whose own per-slot declaration RE-KEYS it does not ride
  the hydration wire, and leaves no row behind on the client (rf2-rjq9d).

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
  entry classifies `:key-projected`, reports `:refetch-on-client? true`, and
  does NOT ride: the row is withheld from the wire, and one arriving from an
  older render is dropped by the hydrate reconcile.

  ## Why emptying the row was not enough (the AUDIT-REOPEN)

  The first fix shipped the row metadata-only and this suite asserted that any
  surviving ghost carried no `:data`. That assertion PERMITS the defect it was
  written for. An emptied row is still a row: it installs, it survives the
  reconcile, and it sits in `:entries` beside the entry `ensure` writes under
  the raw key — reachable by nothing, and collectable by nothing either, since
  GC is timer-driven (`events/gc-fired-handler`) and hydration arms no timers.
  The bead's criterion is *no persistent unreachable duplicate*, and a data-less
  duplicate is still a duplicate. The refetch plan named it too, so the plan
  carried an identity no live derivation reproduces.

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

(defn- durable-entries []
  (get-in (runtime-db) (state/entries-path)))

(defn- rows-for
  "Every DURABLE row naming `resource-id`, as `[key-id entry]` pairs."
  [resource-id]
  (filterv (fn [[_ e]] (= resource-id (second (:resource/key e)))) (durable-entries)))

(defn- coarse-wire-key-id
  "The key-id the COARSE `:redact` / `:omit` projection installs a global-scope
  entry under. Derived through the production projector rather than restated, so
  this suite pins the coarse arm's PRESENCE without pinning its digest."
  [resource-id]
  (state/key-id (ssr/project-scoped-key (global-key resource-id) :redact nil)))

;; ---- a payload from BEFORE the withholding (the version-skew forgery) ------
;;
;; A hydration payload is HTML, and cached HTML rendered by an earlier deploy is
;; routinely served to a newer JS bundle — so "the server no longer ships it" is
;; not on its own a guarantee about the client's cache. These two keys are the
;; literal wire keys the pre-fix projection installed the declaring arms under
;; (the constant sentinel substituted into the declared slot, nested inside the
;; component). They are written out rather than derived, so the forgery states
;; the BYTES an older render emitted and cannot drift with today's projector.

(def ^:private legacy-params-wire-key
  [:rf.scope/global :params/report {:account-id :rf/redacted :page 3}])

(def ^:private legacy-scope-wire-key
  [[:rf.scope/session {:tenant-id :rf/redacted :region "au"}] :scoped/report {:page 3}])

(defn- legacy-row
  "One wire row as a PRE-#7354 render emitted it: under the projected key, and
  carrying its `:data`. Carrying data is the point — it is the strongest form
  of the row this client must refuse."
  [wire-key]
  [(state/key-id wire-key)
   (assoc (state/empty-entry (second wire-key) wire-key)
          :status :loaded :data {:total 1} :loaded-at 1000)])

(defn- wire-slice-including-withheld
  "Today's wire slice PLUS the two rows an older render would have included."
  []
  (update-in (wire-slice) [state/resources-key :entries]
             (fn [entries]
               (into entries [(legacy-row legacy-params-wire-key)
                              (legacy-row legacy-scope-wire-key)]))))

;; ===========================================================================
;; 1. THE ROW DOES NOT RIDE.
;;
;;    Both arms — a `:params`-rooted declaration and a `:scope`-rooted one —
;;    re-key the entry, and a re-keyed row is WITHHELD rather than emptied.
;;    Asserting `(not (contains? e :data))` is what let the ghost through: it
;;    is satisfied by a nil `e`, so it can neither distinguish "shipped empty"
;;    from "not shipped" nor notice a row that persists.
;; ===========================================================================

(deftest a-params-declaration-re-keys-the-entry-and-the-row-does-not-ride
  (testing "rf2-rjq9d — the PARAMS arm: the entry is not addressable by the
            key the client derives, so it is not sent under any key"
    (install-all!)
    (let [wired (wire-entries)
          m     (:params/report (metadata-by-resource))]
      (is (not (contains? wired (state/key-id @params-key)))
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
  (testing "rf2-rjq9d — the SCOPE arm, which #7255 extended the re-key to.
            Same identity break, same answer"
    (install-all!)
    (let [wired (wire-entries)
          m     (:scoped/report (metadata-by-resource))]
      (is (not (contains? wired (state/key-id (scoped-key-for))))
          "premise: the raw key-id is NOT a wire map key")
      (is (empty? (wire-rows :scoped/report))
          (str "…and no row rides under the projected key either: "
               (pr-str (wire-rows :scoped/report))))
      (is (= :key-projected (:disposition m)))
      (is (true? (:refetch-on-client? m))))))

(deftest the-wire-carries-exactly-the-addressable-rows
  (testing "rf2-rjq9d — the two-sided control on WITHHOLDING, stated as an
            exact set. Five durable entries, three wire rows: withholding one
            row too many fails here just as loudly as withholding none"
    (install-all!)
    (is (= #{(state/key-id (global-key :plain/report))
             (coarse-wire-key-id :sealed/report)
             (coarse-wire-key-id :bulky/report)}
           (set (keys (wire-entries))))
        (str "the wire carries exactly the rows a live client can address — "
             (pr-str (mapv (comp :resource/key second) (wire-entries)))))
    (is (= 5 (count (durable-entries)))
        "…while the server's own cache still holds all five: the withholding
         is a projection decision, not an eviction")))

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
      (is (= 1 (count (wire-rows :sealed/report)))
          "the coarse rows still RIDE — their substitution is a
           content-addressed digest, one-to-one and derivable in principle,
           so withholding must not reach them")
      (is (= 1 (count (wire-rows :bulky/report))))
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
;; 2. THE HYDRATE PLAN NAMES ONLY IDENTITIES THE CLIENT HAS.
;;
;;    The bead's core defect was two independent deciders, one saying "reuse"
;;    and the other unable to. The AUDIT-REOPEN found the correction had left
;;    the plan naming the PROJECTED identity — a `:resource/key` the route
;;    slice would carry into `:rf.resource/refetch` and that no live derivation
;;    reproduces. A plan entry nobody can act on is not a plan entry.
;; ===========================================================================

(deftest the-refetch-plan-names-no-identity-the-client-cannot-derive
  (testing "rf2-rjq9d — the re-keyed arms are ABSENT from the plan (their row
            never arrives), while the coarse arms stay present: the plan must
            not become empty, only truthful"
    (install-all!)
    (let [plan  (boot-client!)
          by-id (plan-by-resource plan)]
      (is (not (contains? by-id :params/report))
          "the params arm is not planned — there is no hydrated row to plan,
           and its projected key names a fetch nobody could issue")
      (is (not (contains? by-id :scoped/report)) "…nor the scope arm")
      (is (contains? by-id :sealed/report)
          "the coarse redaction is still planned — the control that stops this
           passing by planning nothing")
      (is (contains? by-id :bulky/report))
      (is (not (contains? by-id :plain/report))
          "and the addressable fresh entry is still absent — no double-fetch")
      (is (empty? (filterv (fn [p] (= :rf/redacted (get-in p [:resource/key 2 :account-id])))
                           plan))
          (str "no plan row names a per-slot-projected key — " (pr-str plan))))))

(deftest the-plan-and-the-projection-metadata-cannot-disagree
  (testing "the invariant the bead asks for, restated for a contract that
            WITHHOLDS: an entry is planned iff the server marked it
            refetch-on-client AND shipped it. The gap between the two sets is
            not slack — it is exactly the withheld set, asserted as such"
    (install-all!)
    (let [meta-by-id (metadata-by-resource)
          marked     (into #{} (comp (filter (fn [[_ m]] (true? (:refetch-on-client? m))))
                                     (map key))
                           meta-by-id)
          withheld   (into #{} (comp (filter (fn [[_ m]] (= :key-projected (:disposition m))))
                                     (map key))
                           meta-by-id)
          planned    (set (keys (plan-by-resource (boot-client!))))]
      (is (= #{:params/report :scoped/report :sealed/report :bulky/report} marked)
          "premise: four of the five owners are marked refetch-on-client")
      (is (= #{:params/report :scoped/report} withheld)
          "premise: exactly the two re-keyed owners are withheld")
      (is (= (set/difference marked withheld) planned)
          (str "planned must be marked-minus-withheld — refetch-on-client? "
               (pr-str marked) ", withheld " (pr-str withheld)
               ", planned " (pr-str planned)))
      (is (empty? (set/difference planned marked))
          "and nothing is planned that the server did not mark: the two
           deciders still cannot disagree in the dangerous direction"))))

(deftest the-plan-refuses-an-unaddressable-row-it-is-handed-directly
  (testing "rf2-rjq9d — `hydrate-refetch-plan` is a published entry point a
            host may call on a payload slice it has not reconciled, so the
            property must hold for the FUNCTION, not only for the path that
            drops the row first"
    (install-all!)
    (let [forged (get (wire-slice-including-withheld) state/resources-key)
          plan   (ssr/hydrate-refetch-plan {state/resources-key forged} 5000)]
      (is (seq (:entries forged))
          "premise: the forged slice really does carry rows")
      (is (some (fn [[_ e]] (= :params/report (second (:resource/key e))))
                (:entries forged))
          "premise: including an unaddressable one")
      (is (not (contains? (plan-by-resource plan) :params/report))
          "…which the plan refuses to name")
      (is (contains? (plan-by-resource plan) :sealed/report)
          "while a coarse row handed to it the same way is still planned"))))

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
  (testing "rf2-rjq9d — the client issues ONE load, under the raw key, and NO
            unreachable row is left standing beside the answer. The assertion
            this replaces read `every? (not (contains? e :data))` over the
            ghosts, which is true of a ghost that persists — the exact defect
            the bead forbids"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :params/report
                        :params   {:account-id account-secret :page 3}}])
    (is (= 1 @requests) "exactly one request — not zero, and not two")
    (is (some? (live-entry @params-key))
        "the entry now exists under the identity the client derives")
    (is (= [(state/key-id @params-key)] (mapv first (rows-for :params/report)))
        (str "…and it is the ONLY row for this resource: no unreachable "
             "duplicate persists beside it — "
             (pr-str (mapv (comp :resource/key second) (rows-for :params/report)))))))

(deftest the-scope-arm-costs-exactly-one-intentional-request
  (testing "the same end-to-end statement for a `:scope`-rooted declaration,
            whose live scope comes from the NAMED resolver rather than the
            ensure payload — including the same no-duplicate claim, which the
            audit found had only ever been made for the params arm"
    (install-all!)
    (boot-client!)
    (reset! requests 0)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :scoped/report :params {:page 3}}])
    (is (= 1 @requests))
    (is (some? (live-entry (scoped-key-for)))
        "the resolved session scope lands on the raw key, as the client
         derives it")
    (is (= [(state/key-id (scoped-key-for))] (mapv first (rows-for :scoped/report)))
        (str "…and it is the only row for this resource — "
             (pr-str (mapv (comp :resource/key second) (rows-for :scoped/report)))))))

(deftest no-unaddressable-row-survives-hydration
  (testing "rf2-rjq9d — the bead's criterion stated ONCE over the whole cache
            rather than per resource: after hydrate and after both declaring
            arms are ensured, the durable `:entries` map is EXACTLY the four
            key-ids a live client can derive. An exact set is what makes this
            two-sided — a surviving ghost adds a key and fails, and an
            over-eager drop removes one and fails just as loudly"
    (install-all!)
    (boot-client!)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :params/report
                        :params   {:account-id account-secret :page 3}}])
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :scoped/report :params {:page 3}}])
    (is (= #{(state/key-id @params-key)
             (state/key-id (scoped-key-for))
             (state/key-id (global-key :plain/report))
             (coarse-wire-key-id :sealed/report)
             (coarse-wire-key-id :bulky/report)}
           (set (keys (durable-entries))))
        (str "the client's cache holds exactly the addressable rows — "
             (pr-str (mapv (comp :resource/key second) (durable-entries)))))
    (is (every? (fn [[_ e]] (not (leaks? "rf/redacted" (get-in e [:resource/key 2]))))
                (rows-for :params/report))
        "and no surviving row's params carry the constant sentinel")))

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
;;    what withholding guarantees is that there is no surviving row to collapse
;;    ONTO. The previous statement — one row, carrying nothing — was the weaker
;;    of the two available, and it is the one that let the ghost stand.
;; ===========================================================================

(deftest two-principals-that-would-collapse-onto-one-wire-key-are-both-withheld
  (testing "rf2-rjq9d — two entries differing ONLY in the declared identity
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
;;    payload it did not produce — including one carrying the DATA the pre-fix
;;    projection shipped.
;; ===========================================================================

(deftest a-payload-from-an-older-render-leaves-no-row-behind
  (testing "rf2-rjq9d — the hydrate reconcile drops a row whose key no live
            derivation reproduces, exactly as `recompute-indexes` refuses to
            trust the wire's indexes"
    (install-all!)
    (let [forged (get (wire-slice-including-withheld) state/resources-key)]
      (is (= 5 (count (:entries forged)))
          "premise: the forged payload carries the two withheld rows too")
      (is (some (fn [[_ e]] (some? (:data e)))
                (filterv (fn [[_ e]] (= :params/report (second (:resource/key e))))
                         (:entries forged)))
          "premise: and the pre-fix row carries its DATA")
      (frame/swap-runtime-db!
        :rf/default
        (fn [rdb] (assoc (or rdb {}) state/resources-key forged)))
      (ssr/hydrate-resources! :rf/default)
      (is (empty? (rows-for :params/report))
          (str "the unaddressable row is gone after hydrate — "
               (pr-str (mapv (comp :resource/key second) (rows-for :params/report)))))
      (is (empty? (rows-for :scoped/report)))
      (is (= 3 (count (durable-entries)))
          "…leaving exactly the three addressable rows")
      (is (nil? (live-entry @params-key))
          "and its data never became readable under the identity the client
           derives — dropping the row is not adopting it"))))

(deftest an-older-payload-still-costs-exactly-one-request
  (testing "the end-to-end consequence: a client hydrating a pre-fix payload
            behaves identically to one hydrating a withheld payload — one
            intentional load, and nothing left over"
    (install-all!)
    (let [forged (get (wire-slice-including-withheld) state/resources-key)]
      (frame/swap-runtime-db!
        :rf/default
        (fn [rdb] (assoc (or rdb {}) state/resources-key forged)))
      (ssr/hydrate-resources! :rf/default)
      (reset! requests 0)
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :params/report
                          :params   {:account-id account-secret :page 3}}])
      (is (= 1 @requests) "exactly one request")
      (is (= [(state/key-id @params-key)] (mapv first (rows-for :params/report)))
          "and one row"))))
