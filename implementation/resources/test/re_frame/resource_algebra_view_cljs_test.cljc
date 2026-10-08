(ns re-frame.resource-algebra-view-cljs-test
  "The static and live derivation/process algebra view of resources
  ([spec/Derivations.md] §Resources expose process nodes; the
  `:rf/derivation-node` shape in [spec/Spec-Schemas.md]).
  `re-frame.resources.tooling/resource-algebra-view` lowers every registered
  resource into the canonical `:process` node — remote authority, runtime-db
  storage, a multi-trigger evaluation set, the scoped-resource-key lifecycle,
  read-fact selectors and transport commands; `resource-cache-algebra-view`
  reports one node per live cache entry keyed by its CEDN-1 byte `key-id`,
  projected through the owner's classification before it leaves for a tool.
  Both live only in the bundle-isolated tooling sibling: there is no public
  accessor on `re-frame.core` or the `re-frame.resources` facade."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.derivation.egress :as rf.derivation.egress]
   [re-frame.derivation.graph :as rf.derivation.graph]
   [re-frame.elision :as rf.elision]
   [re-frame.frame :as rf.frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the :resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.tooling :as rf.resources.tooling]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

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
  (is (= [{} nil] [(rf.resources.tooling/resource-algebra-view) (rf.resources.tooling/resource-algebra-view :nope/missing)])
      "an empty map (not nil) with none registered, and nil for an unregistered id"))

;; ---- a registered resource exposes its process node ----------------------

(deftest resource-exposes-its-full-process-node
  (testing "a reg-resource exposes the full process / runtime-db / remote node"
    (rf/reg-resource :article/by-slug (article-spec {:data-schema :app/article
                                                     :doc "an article by slug"})
                     article-spec-request)
    (let [node (rf.resources.tooling/resource-algebra-view :article/by-slug)]
      (is (has-fixed-classifications? node)
          "the fixed process / runtime-db / multi-trigger / resource-key / materialized classifications")
      (is (= {:id          :article/by-slug
              :source-form {:kind :reg-resource :id :article/by-slug}
              :output      [:runtime [rf.resources.state/resources-key :entries]]
              :authority   {:kind :remote :system :server :transport :rf.http/managed}
              :inputs      [[:param :rf.params] [:scope :rf.scope/global]]
              :commands    [{:effect  :rf.http/managed
                             :replies {:success :rf.resource.internal/succeeded
                                       :failure :rf.resource.internal/failed}}]
              :schema      :app/article
              :doc         "an article by slug"}
             (select-keys node [:id :source-form :output :authority :inputs :commands :schema :doc]))
          "the cache-entry output, remote authority, params + scope inputs, transport command, schema and doc")
      (is (= [true true true true true]
             [(contains? (set (:selectors node)) :rf/resource) (contains? (set (:selectors node)) :rf.resource/data)
              (contains? (set (:selectors node)) :rf.resource/loading?) (fn? (:derive node))
              (some? (get-in node [:source :ns]))])
          "the read facts are selectors, the :request fn an opaque :derive token, and the source coords captured"))))

(deftest zero-arity-projects-every-resource
  (testing "(resource-algebra-view) lowers every registered resource"
    (rf/reg-resource :a/x (article-spec) article-spec-request)
    (rf/reg-resource :b/y (article-spec) article-spec-request)
    (let [view (rf.resources.tooling/resource-algebra-view)]
      (is (= [#{:a/x :b/y} true (rf.resources.tooling/resource-algebra-view :a/x)]
             [(set (keys view)) (every? has-fixed-classifications? (vals view)) (:a/x view)])
          "every resource is projected, each equal to its one-arity view"))))

;; ---- the don't-execute rule on scope -------------------------------------

(deftest inline-fn-scope-is-refused-at-registration
  (testing "an inline-fn :scope is not one of the two policy shapes, so it
            never reaches the static view at all"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-missing-scope-policy"
          (rf/reg-resource :fn/scoped
                           (article-spec {:scope (fn [_route _ctx] :rf.scope/global)})
                           article-spec-request)))))

;; ---- named-resolver enrichment ({:from-db <id>}) -------------------------

(deftest from-db-scope-carries-named-resolver-enrichment
  (testing "a {:from-db <id>} scope reports the reference verbatim + the resolver's declared inputs"
    ;; The named-resolver enrichment (Derivations §Named-resolver enrichment):
    ;; the resolver id + its declared [:db <rf-path>] inputs are STATIC facts
    ;; (declared, not executed), even while the params stay generic.
    (rf/reg-resource-scope :session/current-tenant
                           {:inputs {:tenant-id [:db [:session :tenant-id]]}}
                           (fn [{:keys [tenant-id]} _ctx]
                             [:rf.scope/session {:tenant-id tenant-id}]))
    (rf/reg-resource :tenant/article
                     (article-spec {:scope {:from-db :session/current-tenant}})
                     article-spec-request)
    (let [node (rf.resources.tooling/resource-algebra-view :tenant/article)]
      (is (= [[[:param :rf.params] [:scope {:from-db :session/current-tenant}]]
              {:id :session/current-tenant :inputs [[:db [:session :tenant-id]]]}]
             [(:inputs node) (select-keys (:scope-resolver node) [:id :inputs])])
          "the reference is reported verbatim, with the resolver's declared inputs as static facts"))))

;; ---- the live cache view -------------------------------------------------

(deftest live-cache-view-empty-when-no-entries
  (testing "(resource-cache-algebra-view frame) is {} for a frame with no entries"
    (is (= {} (rf.resources.tooling/resource-cache-algebra-view :rf/default)))))

(defn- install-live-entry!
  "Write a live cache entry (and, when in flight, a linked work-ledger
  record) DIRECTLY into the frame's runtime-db — a test fixture, NOT the
  resource write-path under test. Returns the scoped key."
  [frame-id resource-id scope params {:keys [status owner in-flight?]}]
  (let [scoped-key (rf.resources.state/scoped-resource-key scope resource-id params)
        work-id    (when in-flight? (rf.resources.work-ledger/resource-work-id scoped-key 1))
        ;; The runtime stamps each entry's `:resource/key`; the
        ;; live algebra view reads it for the node `:id` / `:inputs` (the
        ;; `:entries` map is keyed on the opaque byte `key-id`).
        entry      (cond-> (assoc (rf.resources.state/empty-entry resource-id scoped-key)
                                  :status        (or status :loaded)
                                  :data          {:slug (:slug params)}
                                  :active-owners (if owner #{owner} #{}))
                     in-flight? (assoc :current-work work-id :status :fetching))]
    (rf.frame/swap-runtime-db!
      frame-id
      (fn [rdb]
        (cond-> (assoc-in (or rdb {}) (rf.resources.state/entry-path scoped-key) entry)
          in-flight?
          (rf.resources.work-ledger/put-record
            work-id
            (rf.resources.work-ledger/work-record {:work-id      work-id
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
          view       (rf.resources.tooling/resource-cache-algebra-view :rf/default)
          node       (get view (rf.resources.state/key-id scoped-key))]
      (is (has-live-fixed-classifications? node)
          "the live node, reachable by its byte key-id, keeps the fixed classifications (lifecycle is the map form)")
      (is (= {:id        scoped-key
              :inputs    [[:scope scope] [:param params]]
              :output    [:runtime (rf.resources.state/entry-path scoped-key)]
              :lifecycle {:kind :scoped-resource-key :owners #{[:route :route/article 17]}}
              :status    :loaded
              :authority {:kind :remote :system :server :transport :rf.http/managed}}
             (select-keys node [:id :inputs :output :lifecycle :status :authority]))
          "the concrete scoped key, its realized edges and entry address, the active owners, status and authority")
      (is (= [false false] [(contains? node :work-ledger) (contains? node :host-transient)])
          "a loaded entry has no work-ledger or host-transient links"))))

(deftest live-in-flight-entry-links-work-ledger-and-host-transient
  (testing "an in-flight entry links its work-ledger record + names the host-transient handle"
    (rf/reg-resource :article/by-slug (article-spec) article-spec-request)
    (let [scope      :rf.scope/global
          params     {:slug "loading"}
          scoped-key (install-live-entry! :rf/default :article/by-slug scope params
                                          {:owner [:route :route/article 9] :in-flight? true})
          node       (get (rf.resources.tooling/resource-cache-algebra-view :rf/default)
                          (rf.resources.state/key-id scoped-key))
          work-id    (rf.resources.work-ledger/resource-work-id scoped-key 1)]
      (is (= [:fetching work-id {:resource/key scoped-key :transport :rf.http/managed :owners #{[:route :route/article 9]}}
              [[:rf.http/in-flight work-id]]]
             [(:status node) (get-in node [:work-ledger :work/id])
              (select-keys (get-in node [:work-ledger :record]) [:resource/key :transport :owners])
              (:host-transient node)])
          "the work-ledger link carries the work id and a record summary, and the host-transient handle names it"))))

(deftest live-view-keeps-cedn-distinct-scoped-keys-distinct
  (testing "ADVERSARIAL: two live entries whose params differ ONLY by
            EDN collection kind (vector vs list) are Clojure-= as scoped-key
            VECTORS but CEDN-distinct (distinct byte key-ids). The cache
            algebra view must report TWO distinct nodes (one per byte-keyed
            runtime entry), NOT collapse them onto one `=`-colliding key"
    (rf/reg-resource :article/by-slug (article-spec) article-spec-request)
    (let [scope  :rf.scope/global
          pv     {:xs [1 2 3]}
          pl     {:xs '(1 2 3)}
          kv     (install-live-entry! :rf/default :article/by-slug scope pv
                                      {:status :loaded :owner [:app :v 1]})
          kl     (install-live-entry! :rf/default :article/by-slug scope pl
                                      {:status :loaded :owner [:app :l 1]})
          view   (rf.resources.tooling/resource-cache-algebra-view :rf/default)]
      (is (= [true false] [(= kv kl) (= (rf.resources.state/key-id kv) (rf.resources.state/key-id kl))])
          "FIXTURE — the scoped-key vectors are Clojure-= while their byte key-ids differ")
      (let [nv (get view (rf.resources.state/key-id kv))
            nl (get view (rf.resources.state/key-id kl))]
        (is (= [2 true true true false]
               [(count view) (vector? (-> nv :id (nth 2) :xs)) (seq? (-> nl :id (nth 2) :xs))
                (= [kv kl] [(:id nv) (:id nl)]) (= (:lifecycle nv) (:lifecycle nl))])
            "two nodes keyed on both byte key-ids, each keeping its kind-preserving key and its own owners")))))

;; ---- EP-0015 egress redaction of the live graph snapshot -----------------
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
  (testing "a :sensitive? resource's scoped-key scope/params are
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
          view       (rf.resources.tooling/resource-cache-algebra-view :rf/default)
          node       (first (vals view))]
      (is (= [1 false :secret/article :secret/article]
             [(count view) (contains-secret? view) (nth (:id node) 1)
              (second (:resource/key (:record (:work-ledger node))))])
          "one node, no raw secret anywhere (map keys included), the resource id surviving in :id and the work-ledger record"))))

(deftest live-view-no-derived-sensitivity-inheritance
  (testing "EP-0025: a resource whose {:from-db <resolver>} scope
            derives from a frame-sensitive :db input is NOT auto-redacted —
            there is no derived-sensitivity propagation (no input→output
            inheritance, Spec 015 §No propagation, no taint). Confirm-by-revert:
            the OWNER's coarse :sensitive? claim still redacts the whole key."
    ;; FRAME classification: the resolver's :db input path is sensitive
    ;; (commit-plane effect path, :source :effect).
    (rf/make-frame {:id :sens/frame :doc "frame with a sensitive tenant-id"})
    (rf.frame/swap-runtime-db! :sens/frame
      (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:session :tenant-id]]})))
    ;; resolver reading the frame-sensitive path — NO propagation.
    (rf/reg-resource-scope :session/tenant
                           {:inputs {:tenant-id [:db [:session :tenant-id]]}}
                           (fn [{:keys [tenant-id]} _]
                             (when tenant-id [:rf.scope/tenant tenant-id])))
    ;; a tenant-scoped resource NOT declared :sensitive? — the derived scope is
    ;; NOT inherited as sensitive (the secret rides; fail-open).
    (rf/reg-resource :derived/article
                     (article-spec {:scope {:from-db :session/tenant}})
                     article-spec-request)
    (let [scope  [:rf.scope/tenant secret]
          params {:slug "x"}]
      (install-live-entry! :sens/frame :derived/article scope params
                           {:status :loaded :owner [:app :d 1]})
      (let [view (rf.resources.tooling/resource-cache-algebra-view :sens/frame)]
        (is (= [1 true] [(count view) (contains-secret? view)])
            "the raw derived-scope secret rides: no propagation")))))

;; ---- entries whose projected keys collide -------------------------------
;;
;; A `:sensitive?` resource's scope + params project to CONTENT-FREE shape
;; tokens, so two live entries whose scope + params merely share a shape
;; project to one key. The view reports one node per live entry all the
;; same, and so does the graph composed from it.

(defn- contains-any?
  "Deep-walk `v`; true iff any string in `needles` equals a leaf or occurs
  inside a string leaf or map key."
  [needles v]
  (boolean
    (cond
      (string? v) (some #(str/includes? v %) needles)
      (map? v)    (some #(contains-any? needles %) (concat (keys v) (vals v)))
      (coll? v)   (some #(contains-any? needles %) v)
      :else       false)))

(def ^:private resources-contributor
  {:resources {:static-fn  rf.resources.tooling/resource-algebra-view
               :live-fn    rf.resources.tooling/resource-cache-algebra-view
               :live-shape :map}})

(defn- resource-node-ids [graph]
  (filter #(and (vector? %) (= :resource (first %))) (keys (:nodes graph))))

(deftest live-view-keeps-same-shaped-sensitive-entries-distinct
  (testing "two live entries of one :sensitive? resource, scope + params of
            one shape, project to EQUAL content-free keys; the view keeps
            BOTH nodes with their own status and owners, and no raw value or
            content digest egresses"
    (rf/reg-resource :app/profile
                     (article-spec {:sensitive?    true
                                    :params-schema [:map [:account :string]]})
                     article-spec-request)
    (let [scope-a  {:tenant "tenant-alpha-SECRET"}
          scope-b  {:tenant "tenant-beta-SECRET"}
          params-a {:account "acct-alpha-SECRET"}
          params-b {:account "acct-beta-SECRET"}
          _        (install-live-entry! :rf/default :app/profile scope-a params-a
                                        {:status :loaded :owner [:app :alpha 1]})
          _        (install-live-entry! :rf/default :app/profile scope-b params-b
                                        {:owner [:app :beta 2] :in-flight? true})
          raw      ["tenant-alpha-SECRET" "tenant-beta-SECRET"
                    "acct-alpha-SECRET" "acct-beta-SECRET"]
          digests  (map (fn [v] (:rf/redacted (rf.resources.ssr/redact-value v :omit)))
                        [scope-a scope-b params-a params-b])
          view     (rf.resources.tooling/resource-cache-algebra-view :rf/default)
          nodes    (vals view)]
      (is (= 4 (count (set digests))) "FIXTURE — the four content digests are distinct")
      (is (= [2 2 #{:loaded :fetching} #{#{[:app :alpha 1]} #{[:app :beta 2]}} true #{0 1}]
             [(count view) (count (set (map :id nodes))) (set (map :status nodes))
              (set (map #(get-in % [:lifecycle :owners]) nodes)) (every? #(= :app/profile (nth (:id %) 1)) nodes)
              (set (map #(get-in % [:id 2 :rf.resource/collision]) nodes))])
          "one node per entry, with distinct ids differing only by a collision ordinal, each keeping its own status and owners")
      (let [in-flight (first (filter :work-ledger nodes))
            wid       (get-in in-flight [:work-ledger :work/id])]
        (is (= [(:id in-flight) (:id in-flight) [[:rf.http/in-flight wid]]]
               [(get-in in-flight [:work-ledger :record :resource/key]) (nth wid 1) (:host-transient in-flight)])
            "the in-flight node's work-ledger positions carry its own id"))
      (let [graph   (rf.derivation.graph/live-derivation-graph :rf/default resources-contributor)
            shipped (rf.derivation.egress/project-graph graph :rf/default)]
        (is (= [false false 2 #{:loaded :fetching} 2 false false]
               [(contains-any? raw view) (contains-any? digests view) (count (resource-node-ids graph))
                (set (map #(get-in graph [:nodes % :status]) (resource-node-ids graph)))
                (count (resource-node-ids shipped)) (contains-any? raw shipped) (contains-any? digests shipped)])
            "no raw value or content digest egresses, and the live graph, shipped off-box too, keeps both resource nodes")))))

(deftest live-view-keeps-distinct-plain-entries-verbatim
  (testing "control: two entries of a NON-sensitive resource keep their
            verbatim scoped keys as node ids, in the view and in the graph"
    (rf/reg-resource :plain/profile
                     (article-spec {:params-schema [:map [:account :string]]})
                     article-spec-request)
    (let [ka    (install-live-entry! :rf/default :plain/profile {:tenant "a"} {:account "x"}
                                     {:status :loaded :owner [:app :a 1]})
          kb    (install-live-entry! :rf/default :plain/profile {:tenant "b"} {:account "y"}
                                     {:status :error :owner [:app :b 2]})
          view  (rf.resources.tooling/resource-cache-algebra-view :rf/default)
          graph (rf.derivation.graph/live-derivation-graph :rf/default resources-contributor)]
      (is (= [#{(rf.resources.state/key-id ka) (rf.resources.state/key-id kb)} ka kb #{[:resource ka] [:resource kb]}]
             [(set (keys view)) (:id (get view (rf.resources.state/key-id ka))) (:id (get view (rf.resources.state/key-id kb)))
              (set (resource-node-ids graph))])))))

;; ---- registry semantics --------------------------------------------------

(deftest cleared-resource-is-removed-from-the-static-view
  (testing "(clear-resource id) removes the resource from the static view"
    (rf/reg-resource :a/x (article-spec) article-spec-request)
    (rf/reg-resource :b/y (article-spec) article-spec-request)
    (is (contains? (rf.resources.tooling/resource-algebra-view) :a/x) "FIXTURE")
    (rf/clear :resource :a/x)
    (is (= #{:b/y} (set (keys (rf.resources.tooling/resource-algebra-view)))))))

#?(:clj
   (deftest facade-publishes-no-algebra-view-alias
     ;; The absence pin. Both
     ;; views ship NO public accessor (Derivations §Resources expose process
     ;; nodes): the `defn`s stay in `re-frame.resources.tooling` and the facade
     ;; re-exports neither, so `re-frame.derivation.graph` reaches them by
     ;; `requiring-resolve` and CLJS tools by a direct `:require`.
     (is (= [nil nil true true]
            [(ns-resolve 're-frame.resources 'resource-algebra-view)
             (ns-resolve 're-frame.resources 'resource-cache-algebra-view)
             (some? (ns-resolve 're-frame.resources.tooling 'resource-algebra-view))
             (some? (ns-resolve 're-frame.resources.tooling 'resource-cache-algebra-view))])
         "the facade re-exports neither view; the tooling sibling publishes both")))
