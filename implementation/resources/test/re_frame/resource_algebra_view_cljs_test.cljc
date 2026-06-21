(ns re-frame.resource-algebra-view-cljs-test
  "Tests for the STATIC + LIVE derivation/process algebra view of resources
  (EP-0014 slice-4, rf2-gn9juw). Per [spec/Derivations.md] §Resources expose
  process nodes (graduated from EP-0014) and the `:rf/derivation-node` shape
  in [spec/Spec-Schemas.md].

  `re-frame.resources.tooling/resource-algebra-view` lowers every registered
  resource into the normalized PROCESS node every declared fact/process
  shares — a resource is the canonical `:process` member of the algebra (NOT
  a derivation): remote `:authority`, `:runtime-db` local storage, a
  multi-trigger evaluation set, the `:scoped-resource-key` lifecycle, with
  `:selectors` (the `:rf.resource/*` read facts) and `:commands` (the
  transport descriptors). `…/resource-cache-algebra-view` reports one node
  per live cache entry keyed by its CEDN-1 byte `key-id` (with the scoped
  resource key carried on the node's `:id`), with the realized scope/param
  edges, the entry status, the work-ledger link, and the host-transient
  in-flight handle address.

  These tests pin the registrar-derived projection: the fixed
  classifications, the declared-input lowering (params + scope; `{:from-db}`
  named-resolver enrichment; the inline-fn don't-execute marker), the
  runtime-db output address, the selectors / commands / authority, the
  opaque `:derive` request token, source coords, and the live entry view.

  Slice-4 ships NO public accessor (EP-0014 issue-1 disposition): the views
  live in the bundle-isolated `re-frame.resources.tooling` sibling, reached
  on JVM through the `re-frame.resources/resource-algebra-view` convenience
  alias, and consumed by Xray + the conformance fixtures. There is no
  `re-frame.core/resource-algebra-view` public facade export."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.elision :as elision]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the :resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.state :as state]
   [re-frame.resources.tooling :as resources-tooling]
   [re-frame.resources.work-ledger :as work-ledger]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- article-spec
  "A minimal valid resource spec (global scope, a slug param)."
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

;; The fixed classifications EVERY resource algebra node carries
;; (Derivations §Resources expose process nodes: the canonical runtime-db /
;; remote-authority PROCESS member of the algebra). `:kind` is the CLOSED
;; superkind `:process` (Spec-Schemas DerivationKind); the informative
;; `:resource-process` refinement rides on `:refinement` (matching the routes
;; `:route-fact` convention), never in `:kind`.
(def fixed-classifications
  {:kind          :process
   :refinement    :resource-process
   :storage       :runtime-db
   :evaluation    #{:on-route :on-reply :scheduled :manual}
   :lifecycle     :scoped-resource-key
   :materialized? true})

(defn- has-fixed-classifications? [node]
  (= fixed-classifications (select-keys node (keys fixed-classifications))))

;; The live node keeps the same spine EXCEPT :lifecycle, which is the map
;; form `{:kind :scoped-resource-key :owners …}` (the static node uses the bare
;; keyword) — the owners are live state. So a live node is checked against
;; the spine minus the keyword-lifecycle axis.
(def ^:private live-fixed-classifications
  (dissoc fixed-classifications :lifecycle))

(defn- has-live-fixed-classifications? [node]
  (= live-fixed-classifications
     (select-keys node (keys live-fixed-classifications))))

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-registry-returns-empty-map
  (testing "(resource-algebra-view) returns {} (not nil) when none registered"
    (is (= {} (resources-tooling/resource-algebra-view)))
    (is (map? (resources-tooling/resource-algebra-view))))
  (testing "(resource-algebra-view id) returns nil for an unregistered id"
    (is (nil? (resources-tooling/resource-algebra-view :nope/missing)))))

;; ---- a registered resource exposes its process node ----------------------

(deftest resource-exposes-its-full-process-node
  (testing "a reg-resource exposes the full process / runtime-db / remote node"
    (rf/reg-resource :article/by-slug (article-spec {:data-schema :app/article
                                                     :doc "an article by slug"})
                     article-spec-request)
    (let [node (resources-tooling/resource-algebra-view :article/by-slug)]
      (is (some? node) "the resource is present in the static view")
      (is (has-fixed-classifications? node)
          "resource carries the fixed process / runtime-db / multi-trigger / resource-key / materialized classifications")
      (is (= :article/by-slug (:id node)))
      (is (= {:kind :reg-resource :id :article/by-slug} (:source-form node)))
      (testing "output materializes the cache entries into runtime-db"
        (is (= [:runtime [state/resources-key :entries]] (:output node))))
      (testing "the remote authority axis names the external system + transport"
        (is (= {:kind :remote :system :server :transport :rf.http/managed}
               (:authority node)))
        (is (not= :remote (:storage node))
            "remote is the authority axis, NEVER the storage class (EP-0014 issue-2)"))
      (testing "declared inputs lower to params + the scope policy"
        (is (= [[:param :rf.params] [:scope :rf.scope/global]] (:inputs node))))
      (testing "the :rf.resource/* read facts are listed as selectors"
        (is (contains? (set (:selectors node)) :rf.resource/state))
        (is (contains? (set (:selectors node)) :rf.resource/data))
        (is (contains? (set (:selectors node)) :rf.resource/loading?)))
      (testing "the transport command descriptor + its reply targets"
        (is (= [{:effect  :rf.http/managed
                 :replies {:success :rf.resource.internal/succeeded
                           :failure :rf.resource.internal/failed}}]
               (:commands node))))
      (testing "the :request body fn is surfaced as an opaque :derive token"
        (is (fn? (:derive node))))
      (testing ":data-schema surfaces as the :schema fact; :doc passes through"
        (is (= :app/article (:schema node)))
        (is (= "an article by slug" (:doc node))))
      (testing "source coords captured by reg-resource surface under :source"
        (is (some? (:source node)))
        (is (some? (get-in node [:source :ns])))))))

(deftest zero-arity-projects-every-resource
  (testing "(resource-algebra-view) lowers every registered resource"
    (rf/reg-resource :a/x (article-spec) article-spec-request)
    (rf/reg-resource :b/y (article-spec) article-spec-request)
    (let [view (resources-tooling/resource-algebra-view)]
      (is (= #{:a/x :b/y} (set (keys view))))
      (is (every? has-fixed-classifications? (vals view)))
      (is (= (resources-tooling/resource-algebra-view :a/x) (:a/x view))
          "the one-arity form equals that id's entry in the all-resources map"))))

;; ---- the don't-execute rule on scope -------------------------------------

(deftest inline-fn-scope-is-marked-never-run
  (testing "an inline-fn scope is reported as the opaque :rf.scope/resolver marker (never run)"
    ;; The don't-execute rule (Derivations §The don't-execute rule): static
    ;; inspection NEVER runs a scope resolver. A throwing fn proves it.
    (rf/reg-resource :fn/scoped
                     (article-spec {:scope (fn [_route _ctx]
                                             (throw (ex-info "must not run in static view" {})))})
                     article-spec-request)
    (let [node (resources-tooling/resource-algebra-view :fn/scoped)]
      (is (= [[:param :rf.params] [:scope :rf.scope/resolver]] (:inputs node))
          "the fn scope lowers to the opaque resolver marker — the fn is never executed")
      (is (not (contains? node :scope-resolver))
          "an inline fn carries no named-resolver enrichment"))))

;; ---- named-resolver enrichment ({:from-db <id>}) -------------------------

(deftest from-db-scope-carries-named-resolver-enrichment
  (testing "a {:from-db <id>} scope reports the reference verbatim + the resolver's declared inputs"
    ;; The named-resolver enrichment (Derivations §Named-resolver enrichment):
    ;; the resolver id + its declared [:db <rf-path>] inputs are STATIC facts
    ;; (declared, not executed), even while the params stay generic.
    (rf/reg-resource-scope :session/current-tenant
                           {:inputs  {:tenant-id [:db [:session :tenant-id]]}
                            :resolve (fn [{:keys [tenant-id]} _ctx]
                                       [:rf.scope/session {:tenant-id tenant-id}])})
    (rf/reg-resource :tenant/article
                     (article-spec {:scope {:from-db :session/current-tenant}})
                     article-spec-request)
    (let [node (resources-tooling/resource-algebra-view :tenant/article)]
      (is (= [[:param :rf.params] [:scope {:from-db :session/current-tenant}]]
             (:inputs node))
          "the {:from-db <id>} reference is reported verbatim — a static fact")
      (is (= :session/current-tenant (get-in node [:scope-resolver :id])))
      (is (= [[:db [:session :tenant-id]]] (get-in node [:scope-resolver :inputs]))
          "the resolver's declared [:db <rf-path>] inputs surface as static facts"))))

;; ---- the live cache view -------------------------------------------------

(deftest live-cache-view-empty-when-no-entries
  (testing "(resource-cache-algebra-view frame) is {} for a frame with no entries"
    (is (= {} (resources-tooling/resource-cache-algebra-view :rf/default)))))

(defn- install-live-entry!
  "Write a live cache entry (and, when in flight, a linked work-ledger
  record) DIRECTLY into the frame's runtime-db — a test fixture, NOT the
  resource write-path under test. Returns the scoped key."
  [frame-id resource-id scope params {:keys [status owner in-flight?]}]
  (let [scoped-key (state/scoped-resource-key scope resource-id params)
        work-id    (when in-flight? (work-ledger/resource-work-id scoped-key 1))
        ;; rf2-9e0tyq — the runtime stamps each entry's `:resource/key`; the
        ;; live algebra view reads it for the node `:id` / `:inputs` (the
        ;; `:entries` map is keyed on the opaque byte `key-id`).
        entry      (cond-> (assoc (state/empty-entry resource-id scoped-key)
                                  :status        (or status :loaded)
                                  :data          {:slug (:slug params)}
                                  :active-owners (if owner #{owner} #{}))
                     in-flight? (assoc :current-work work-id :status :fetching))]
    (frame/swap-runtime-db!
      frame-id
      (fn [rdb]
        (cond-> (assoc-in (or rdb {}) (state/entry-path scoped-key) entry)
          in-flight?
          (work-ledger/put-record
            work-id
            (work-ledger/work-record {:work-id      work-id
                                      :frame-id     frame-id
                                      :resource/key scoped-key
                                      :generation   1
                                      :transport    :rf.http/managed
                                      :owner        owner
                                      :cause        :test/load})))))
    scoped-key))

(deftest live-entry-exposes-its-process-node
  (testing "a live cache entry projects to a process node keyed by its scoped key"
    (rf/reg-resource :article/by-slug (article-spec) article-spec-request)
    (let [scope      :rf.scope/global
          params     {:slug "welcome"}
          scoped-key (install-live-entry! :rf/default :article/by-slug scope params
                                          {:status :loaded :owner [:route :route/article 17]})
          view       (resources-tooling/resource-cache-algebra-view :rf/default)
          node       (get view (state/key-id scoped-key))]
      (is (some? node) "the live entry node is reachable by its byte key-id")
      (is (has-live-fixed-classifications? node)
          "the live node keeps the fixed process classifications (lifecycle is the map form)")
      (is (= scoped-key (:id node)) "the node id is the concrete scoped key (live fact identity)")
      (testing "the realized scope + param edges"
        (is (= [[:scope scope] [:param params]] (:inputs node))))
      (testing "the concrete entry output address"
        (is (= [:runtime (state/entry-path scoped-key)] (:output node))))
      (testing "the live lifecycle map carries the active owners"
        (is (= {:kind :scoped-resource-key :owners #{[:route :route/article 17]}}
               (:lifecycle node))))
      (testing "the entry status is surfaced"
        (is (= :loaded (:status node))))
      (testing "the remote authority is carried through from the static node"
        (is (= {:kind :remote :system :server :transport :rf.http/managed}
               (:authority node))))
      (testing "a loaded (not in-flight) entry has no work-ledger / host-transient links"
        (is (not (contains? node :work-ledger)))
        (is (not (contains? node :host-transient)))))))

(deftest live-in-flight-entry-links-work-ledger-and-host-transient
  (testing "an in-flight entry links its work-ledger record + names the host-transient handle"
    (rf/reg-resource :article/by-slug (article-spec) article-spec-request)
    (let [scope      :rf.scope/global
          params     {:slug "loading"}
          scoped-key (install-live-entry! :rf/default :article/by-slug scope params
                                          {:owner [:route :route/article 9] :in-flight? true})
          node       (get (resources-tooling/resource-cache-algebra-view :rf/default)
                          (state/key-id scoped-key))
          work-id    (work-ledger/resource-work-id scoped-key 1)]
      (is (= :fetching (:status node)))
      (testing "the work-ledger link carries the work id + a serializable record summary"
        (is (= work-id (get-in node [:work-ledger :work/id])))
        (is (= scoped-key (get-in node [:work-ledger :record :resource/key])))
        (is (= :rf.http/managed (get-in node [:work-ledger :record :transport])))
        (is (= #{[:route :route/article 9]} (get-in node [:work-ledger :record :owners]))))
      (testing "the host-transient in-flight handle address names the work id"
        (is (= [[:rf.http/in-flight work-id]] (:host-transient node)))))))

(deftest live-view-keeps-cedn-distinct-scoped-keys-distinct
  (testing "rf2-ka2nkx ADVERSARIAL: two live entries whose params differ ONLY by
            EDN collection kind (vector vs list) are Clojure-= as scoped-key
            VECTORS but CEDN-distinct (distinct byte key-ids). The cache
            algebra view must report TWO distinct nodes (one per byte-keyed
            runtime entry), NOT collapse them onto one `=`-colliding key"
    (rf/reg-resource :article/by-slug (article-spec) article-spec-request)
    (let [scope  :rf.scope/global
          pv     {:xs [1 2 3]}
          pl     {:xs '(1 2 3)}
          kv     (install-live-entry! :rf/default :article/by-slug scope pv
                                      {:status :loaded :owner [:lease :v 1]})
          kl     (install-live-entry! :rf/default :article/by-slug scope pl
                                      {:status :loaded :owner [:lease :l 1]})
          view   (resources-tooling/resource-cache-algebra-view :rf/default)]
      (is (= kv kl) "the scoped-key VECTORS are Clojure-= (the collapse routed around)")
      (is (not= (state/key-id kv) (state/key-id kl))
          "their byte key-ids differ (v[…] vs l(…))")
      (is (= 2 (count view)) "TWO distinct nodes — no =-collapse onto one map key")
      (is (= #{(state/key-id kv) (state/key-id kl)} (set (keys view)))
          "the view is keyed on the byte key-ids of BOTH entries")
      (testing "each node keeps its kind-preserving scoped-key on :id"
        (let [nv (get view (state/key-id kv))
              nl (get view (state/key-id kl))]
          (is (= kv (:id nv)) "vector node keeps its vector key")
          (is (= kl (:id nl)) "list node keeps its list key")
          (is (vector? (-> nv :id (nth 2) :xs)) "vector-params node preserves vector kind")
          (is (seq?    (-> nl :id (nth 2) :xs)) "list-params node preserves list kind")
          (is (not= (:lifecycle nv) (:lifecycle nl))
              "the two nodes carry their OWN distinct owner sets (not gated together)"))))))

;; ---- EP-0015 egress redaction of the live graph snapshot (rf2-0t0l3w) ----
;;
;; `resource-cache-algebra-view` is a TOOL-facing egress boundary (Xray,
;; re-frame2-pair-mcp, conformance fixtures consume it). EP-0015 treats
;; resource entries, work-ledger rows, scopes, params, and owner/cause
;; summaries as EGRESS RECORDS: the raw scope/params embedded in the node
;; KEY, `:id`, `:inputs`, `:output`, and `:work-ledger :record :resource/key`
;; cannot be reached by a generic value-path walk, so the tooling surface
;; must project them through the resource OWNER classification BEFORE egress.

(def ^:private secret "tenant-jwt-SECRET-9f3a")

(defn- contains-secret?
  "Deep-walk `v`; true iff the raw secret string appears ANYWHERE (leaf, key,
  scope, params, work-ledger record, :output path tail)."
  [v]
  (boolean
    (cond
      (= v secret)  true
      (map? v)      (some contains-secret? (concat (keys v) (vals v)))
      (coll? v)     (some contains-secret? v)
      :else         false)))

(deftest live-view-redacts-sensitive-params-at-tool-egress
  (testing "rf2-0t0l3w: a :sensitive? resource's scoped-key scope/params are
            projected to opaque handles in EVERY identity position — the node
            map key, :id, :inputs, :output, and the in-flight work-ledger
            record :resource/key — while resource-id identity + connectivity
            survive and no raw secret egresses"
    (rf/reg-resource :secret/article
                     (article-spec {:sensitive?    true
                                    :params-schema [:map [:auth-token :string]]})
                     article-spec-request)
    (let [scope      [:rf.scope/tenant secret]
          params     {:auth-token secret}
          scoped-key (install-live-entry! :rf/default :secret/article scope params
                                          {:owner [:route :route/article 17] :in-flight? true})
          view       (resources-tooling/resource-cache-algebra-view :rf/default)
          node       (first (vals view))]
      (is (= 1 (count view)) "exactly one live node")
      (is (some? node))
      (testing "no raw secret anywhere in the egressed view"
        (is (not (contains-secret? view))))
      (testing "the resource-id identity + graph connectivity survive projection"
        (is (= :secret/article (nth (:id node) 1))
            "resource-id preserved in the node :id 3-tuple")
        (is (= :secret/article (second (:resource/key (:record (:work-ledger node))))
               )
            "resource-id preserved in the work-ledger record :resource/key"))
      (testing "the node MAP KEY does not embed the raw secret"
        (is (not (contains-secret? (keys view))))))))

(deftest live-view-preserves-non-sensitive-identity
  (testing "rf2-0t0l3w guard: a NON-sensitive resource still rides its scope /
            params verbatim (projection must not over-redact the common case)"
    (rf/reg-resource :plain/article (article-spec) article-spec-request)
    (let [scope  :rf.scope/global
          params {:slug "welcome"}
          scoped-key (install-live-entry! :rf/default :plain/article scope params
                                          {:status :loaded :owner [:lease :p 1]})
          view   (resources-tooling/resource-cache-algebra-view :rf/default)
          node   (get view (state/key-id scoped-key))]
      (is (some? node))
      (is (= [[:scope scope] [:param params]] (:inputs node))
          "non-sensitive scope + params ride verbatim")
      (is (= scoped-key (:id node)) "non-sensitive scoped key rides verbatim"))))

(deftest live-view-redacts-derived-sensitive-scope
  (testing "rf2-0t0l3w: a resource whose {:from-db <resolver>} scope derives
            sensitivity from a frame-sensitive :db input is redacted at egress
            EVEN when the owner did not declare :sensitive? (derived-sensitivity
            inheritance, Spec 015 §Derived sensitivity)"
    ;; FRAME classification: the resolver's :db input path is sensitive.
    ;; EP-0025: classified via the commit-plane effect path (:source :effect) —
    ;; no longer a frame annotation.
    (rf/reg-frame :sens/frame {:doc "frame with a sensitive tenant-id"})
    (frame/swap-runtime-db! :sens/frame
      (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:session :tenant-id]]})))
    ;; resolver reading the frame-sensitive path (default :inherit propagates)
    (rf/reg-resource-scope :session/tenant
                           {:inputs  {:tenant-id [:db [:session :tenant-id]]}
                            :resolve (fn [{:keys [tenant-id]} _]
                                       (when tenant-id [:rf.scope/tenant tenant-id]))})
    ;; a tenant-scoped resource — NOT itself declared :sensitive?
    (rf/reg-resource :derived/article
                     (article-spec {:scope {:from-db :session/tenant}})
                     article-spec-request)
    (let [scope      [:rf.scope/tenant secret]
          params     {:slug "x"}
          scoped-key (install-live-entry! :sens/frame :derived/article scope params
                                          {:status :loaded :owner [:lease :d 1]})
          view       (resources-tooling/resource-cache-algebra-view :sens/frame)]
      (is (= 1 (count view)) "one node")
      (testing "the derived-sensitive scope is redacted at egress (no raw secret)"
        (is (not (contains-secret? view)))))))

;; ---- registry semantics --------------------------------------------------

(deftest cleared-resource-is-removed-from-the-static-view
  (testing "(clear-resource id) removes the resource from the static view"
    (rf/reg-resource :a/x (article-spec) article-spec-request)
    (rf/reg-resource :b/y (article-spec) article-spec-request)
    (is (contains? (resources-tooling/resource-algebra-view) :a/x))
    (rf/clear-resource :a/x)
    (let [view (resources-tooling/resource-algebra-view)]
      (is (not (contains? view :a/x)))
      (is (contains? view :b/y)))))

#?(:clj
   (deftest jvm-alias-mirrors-the-tooling-fn
     (testing "the JVM re-frame.resources/resource-algebra-view alias is the tooling fn"
       (rf/reg-resource :article/by-slug (article-spec) article-spec-request)
       (is (= (resources-tooling/resource-algebra-view)
              (re-frame.resources/resource-algebra-view))
           "the JVM convenience alias projects identically to the tooling sibling"))))
