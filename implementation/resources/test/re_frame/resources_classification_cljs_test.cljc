(ns re-frame.resources-classification-cljs-test
  "EP-0015 §6 / EP-0025 resource / mutation OWNER-classification. Pins the
  contract that resource durable state projects through the merged frame-owned
  classification + `project-egress` REGISTRY-DRIVEN (Spec 015 §Resource and
  mutation durable classification / Spec 016 §SSR and hydration):

    1. WHOLE-ENTRY coarse `:sensitive?` / `:large?` (the degenerate root-prop
       case, EP-0015 issue 11) → redact / omit; sensitive wins over large;
    2. the canonical fine-grained owner surface is the PROJECTION-RELATIVE
       `:sensitive` / `:large` path declarations on the spec (EP-0025), LOWERED
       per instance into the per-frame elision registry (`reconcile-registry`,
       `:source :resource`); the per-slot `:data-schema` / `:params-schema`
       props VALIDATE only — they do NOT drive durable classification
       (rf2-fuqcob);
    3. the SSR egress READS the registry (rf2-d3pku1, the routing / machines
       standard model): `project-entry-data` / `project-entry-params` walk the
       entry value through `project-egress` seeded at the lowered absolute path
       — the redundant family-private `project-data` / `project-params`
       re-derivation is REMOVED. A frame-classified sub-path is unioned with the
       resource declaration (defense in depth);
    4. the load-bearing Spec 016 rules are preserved (only the durable
       `:entries` slice rides; metadata-only redacted/omitted hydration;
       scoped-key privacy).

  CLJC so the JVM run (`clojure -M:test`) — the load-bearing gate — exercises
  it; the schemas artefact is a test-only dep, so the shared walker hooks are
  bound here."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.elision :as elision]
   [re-frame.frame :as frame]
   [re-frame.privacy :as privacy]
   ;; the unit under test + the SSR projection that consumes it.
   [re-frame.resources.classification :as classification]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   ;; load-bearing side-effecting requires: the façade registers the
   ;; :resource registrar kind; schemas binds the shared walker hooks.
   [re-frame.resources]
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
   (let [spec (merge {:scope         :rf.scope/global
                      :params-schema [:map [:slug :string]]
                      :request       (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})}
                     overrides)]
     (rf/reg-resource id (dissoc spec :request) (:request spec)))))

(defn- entry
  [{:keys [resource-id status data loaded-at stale-at]
    :or   {status :loaded loaded-at 1000 stale-at 9.0e15}}]
  (merge (state/empty-entry resource-id)
         {:status status :data data :loaded-at loaded-at :stale-at stale-at}))

;; rf2-9e0tyq — the runtime keys `:entries` on the byte `key-id` and stamps
;; each entry's `:resource/key`; this helper re-keys the natural
;; `{scoped-key-vector entry}` form callers write into the runtime's shape.
(defn- runtime-db-with [entries]
  {state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(state/key-id sk) (assoc e :resource/key sk)]))
                                       entries)
                        :tag-index {} :owner-index {}}})

;; rf2-d3pku1 — the SSR egress is now REGISTRY-DRIVEN. To exercise an end-to-end
;; SSR projection, LOWER each entry's resource classification into the live
;; frame's per-frame elision registry (`reconcile-registry` → the frame's
;; runtime-db `[:rf.runtime/elision]`), exactly as a resource handler does at
;; commit (`with-classification-lowering`) and as the routing SSR egress test
;; installs route decls. Then project under that frame (`rf/with-frame`), which
;; the SSR projector resolves as the current frame.
(defn- ssr-project
  "Project `runtime-db`'s resource `:entries` for SSR under `frame-id`, having
  first lowered the resource classification into the frame's registry (the live
  durable home of `[:rf.runtime/elision]`). Returns the projection map."
  [frame-id runtime-db]
  (let [lowered (classification/reconcile-registry runtime-db registry/resource-meta)]
    (when-let [reg (get lowered :rf.runtime/elision)]
      (elision/swap-elision-slot! frame-id (constantly reg)))
    (rf/with-frame frame-id
      (ssr/project-resources-runtime-db lowered))))

;; The projection map is byte-keyed; the projected SCOPED KEY (verbatim or
;; redacted) rides as the wire entry's `:resource/key`. Return it as the
;; first element so the privacy/distinctness assertions inspect it.
(defn- only-wire-entry [proj]
  (let [we (val (first (get-in proj [state/resources-key :entries])))]
    [(:resource/key we) we]))

(defn- only-projection-metadata
  "The single per-entry projection metadata map for a one-entry `runtime-db`.

  rf2-4bjep — the observation point for an entry whose ROW is withheld. A coarse
  `:redact` / `:omit` key is re-keyed on both components, so like a
  per-slot-declared `:serialize` key it is not addressable by anything the live
  client derives, and its row does not ride. What the projection DECIDED is
  unchanged and fully reported: `:disposition`, `:projected-key`, `:withheld?`."
  [runtime-db]
  (first (ssr/projection-metadata
           nil 5000 (get-in runtime-db [state/resources-key :entries]))))

(defn- ssr-projected-key
  "The key the SSR projection PRODUCED for the single entry in `runtime-db`.

  Read from `projection-metadata` rather than from the wire row, because a
  `:serialize` entry re-keyed by its own per-slot declaration is WITHHELD from
  the wire (rf2-rjq9d): shipping it left an unaddressable, ownerless row in the
  client's cache. The per-slot projection itself is unchanged, so the co-equality
  this suite asserts — the params surface is projected exactly as the data
  surface is — is read off the carrier that survives."
  [frame-id runtime-db]
  (let [lowered (classification/reconcile-registry runtime-db registry/resource-meta)]
    (when-let [reg (get lowered :rf.runtime/elision)]
      (elision/swap-elision-slot! frame-id (constantly reg)))
    (rf/with-frame frame-id
      (first (map :projected-key
                  (ssr/projection-metadata
                    frame-id 5000 (get-in lowered (state/entries-path))))))))

;; ===========================================================================
;; 1. whole-entry-disposition — the coarse root-prop owner classification
;; ===========================================================================

(deftest whole-entry-disposition-reads-coarse-root-prop
  (testing "EP-0015 issue 11: the coarse whole-entry :sensitive? / :large?
            claims are the degenerate ROOT-PROP classification unit"
    (is (= :serialize (classification/whole-entry-disposition {})))
    (is (= :serialize (classification/whole-entry-disposition nil))
        "an unregistered / nil spec carries no coarse claim → serialize")
    (is (= :redact (classification/whole-entry-disposition {:sensitive? true})))
    (is (= :omit   (classification/whole-entry-disposition {:large? true})))
    (testing "sensitive wins over large (the conservative shape)"
      (is (= :redact (classification/whole-entry-disposition
                       {:sensitive? true :large? true}))))))

;; ===========================================================================
;; 2. spec-declaration-marks — the projection-relative declaration axis-split
;;    (the shape the per-instance lowering re-roots). fuqcob: schema props do
;;    NOT contribute (no union — the schema validates, it does not classify).
;; ===========================================================================

(deftest spec-declaration-marks-split-across-projections
  (testing "EP-0025 — a spec's projection-relative :sensitive / :large
            declarations split into the :data and :params projections (the
            head segment selects the projection; a bare-rooted path defaults
            to data; a :scope-rooted path rides params)"
    (let [spec {:sensitive [[:data :ssn] [:params :account-id] [:bare-data] [:scope :tenant]]
                :large     [[:data :avatar-bytes] [:params :cursor]]}
          {:keys [data params]} (classification/spec-declaration-marks spec)]
      (is (contains? (:sensitive data) [:ssn]) ":data-rooted sensitive → data projection (head stripped)")
      (is (contains? (:sensitive data) [:bare-data]) "bare-rooted sensitive → data projection (shorthand)")
      (is (contains? (:large data) [:avatar-bytes]) ":data-rooted large → data projection")
      (is (contains? (:sensitive params) [:account-id]) ":params-rooted sensitive → params projection")
      (is (contains? (:sensitive params) [:scope :tenant]) ":scope-rooted sensitive → params projection (whole path)")
      (is (contains? (:large params) [:cursor]) ":params-rooted large → params projection"))))

(deftest spec-declaration-marks-ignores-schema-props
  (testing "fuqcob — spec-declaration-marks reads ONLY the projection-relative
            :sensitive / :large declarations; a co-present :data-schema /
            :params-schema's per-slot props do NOT contribute (the schema
            validates, it does not classify durably)"
    (let [spec {:sensitive [[:data :ssn]]
                :data-schema   [:map [:token {:sensitive? true} :string] [:title :string]]
                :params-schema [:map [:account-id {:sensitive? true} :string] [:slug :string]]}
          {:keys [data]} (classification/spec-declaration-marks spec)]
      (is (contains? (:sensitive data) [:ssn]) "projection-relative sensitive :data path is present")
      (is (not (contains? (:sensitive data) [:token])) "schema-prop sensitive slot is NOT present (no union)"))))

(deftest reg-resource-rejects-malformed-classification-declaration
  (testing "EP-0025 fail-loud-input — reg-resource rejects a malformed
            :sensitive / :large declaration at the registration boundary"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"malformed|resource-bad-spec|classification"
          (rf/reg-resource :rf.test/bad-decl
            {:scope         :rf.scope/global
             :params-schema [:map [:slug :string]]
             :sensitive     {:data [:ssn]}}   ;; a MAP, not a vector-of-paths
            (fn [_ _] {:request {:method :get :url "/x"}})))
        "a non-vector :sensitive axis is rejected at reg-resource")))

;; ===========================================================================
;; 2b. registry-driven egress projectors — the routing / machines standard
;;     model (rf2-d3pku1). `project-entry-data` / `project-entry-params` walk
;;     the entry value through project-egress seeded at the lowered absolute
;;     offset, reading the per-frame elision registry (NOT re-deriving from
;;     the spec). Frameless rides verbatim.
;; ===========================================================================

(deftest project-entry-data-frameless-rides-verbatim
  (testing "rf2-d3pku1: a nil-frame projection rides the data VERBATIM — the
            registry is frame-scoped, and the coarse disposition is the separate
            frame-independent authority (matches machines / routing
            fail-open-frameless)"
    (is (= {:title "X" :token "tok"}
           (classification/project-entry-data
             {:title "X" :token "tok"} "k-1" nil :rf.egress/ssr-hydration)))))

(deftest project-entry-data-redacts-registry-lowered-sensitive-slot
  (reg! :acct/profile {:sensitive [[:data :ssn]]})
  (testing "rf2-d3pku1: after the resource declaration is lowered into the
            frame's registry at the entry's absolute :data path,
            project-entry-data walks the bare :data through project-egress seeded
            at that offset and redacts the declared :ssn slot; a sibling rides"
    (rf/make-frame {:id :reg/frame})
    (let [k       (state/scoped-resource-key :rf.scope/global :acct/profile {:slug "x"})
          k-id    (state/key-id k)
          rdb     (runtime-db-with {k (entry {:resource-id :acct/profile
                                              :data {:ssn "123-45-6789" :name "Alice"}})})
          lowered (classification/reconcile-registry rdb registry/resource-meta)]
      (elision/swap-elision-slot! :reg/frame (constantly (get lowered :rf.runtime/elision)))
      (let [data      (get-in lowered [state/resources-key :entries k-id :data])
            projected (classification/project-entry-data
                        data k-id :reg/frame :rf.egress/off-box-tool)]
        (is (= :rf/redacted (:ssn projected))
            "the registry-lowered :data :ssn slot is redacted")
        (is (= "Alice" (:name projected)) "the undeclared sibling rides verbatim")
        (is (not (str/includes? (pr-str projected) "123-45-6789"))
            "the raw value does not ride")))))

(deftest project-entry-data-elides-registry-lowered-large-slot
  (reg! :report/card {:large [[:data :blob]]})
  (testing "rf2-d3pku1 / rf2-260yhk: a registry-lowered :data :large declaration
            ELIDES its slot to the :rf.size/large-elided marker at egress"
    (rf/make-frame {:id :reg/large})
    (let [big     (apply str (repeat 500 "x"))
          k       (state/scoped-resource-key :rf.scope/global :report/card {:slug "r"})
          k-id    (state/key-id k)
          rdb     (runtime-db-with {k (entry {:resource-id :report/card
                                              :data {:blob big :title "public"}})})
          lowered (classification/reconcile-registry rdb registry/resource-meta)]
      (elision/swap-elision-slot! :reg/large (constantly (get lowered :rf.runtime/elision)))
      (let [data      (get-in lowered [state/resources-key :entries k-id :data])
            projected (classification/project-entry-data
                        data k-id :reg/large :rf.egress/off-box-tool)]
        (is (contains? (:blob projected) :rf.size/large-elided)
            "the registry-lowered :data :blob slot is elided to the size marker")
        (is (= "public" (:title projected)) "the undeclared sibling rides verbatim")
        (is (not (str/includes? (pr-str projected) big))
            "the raw large value does NOT ride")))))

(deftest project-entry-params-redacts-registry-lowered-sensitive-slot
  (reg! :report/by-account {:sensitive     [[:params :account-id]]
                            :params-schema [:map [:account-id :string] [:slug :string]]})
  (testing "rf2-d3pku1: project-entry-params walks the scoped-key params component
            through project-egress seeded at the lowered :resource/key params
            offset and redacts the declared :account-id slot"
    (rf/make-frame {:id :reg/params})
    (let [k       (state/scoped-resource-key :rf.scope/global :report/by-account
                                             {:account-id "acct-secret-42" :slug "q3"})
          k-id    (state/key-id k)
          rdb     (runtime-db-with {k (entry {:resource-id :report/by-account :data {:total 99}})})
          lowered (classification/reconcile-registry rdb registry/resource-meta)]
      (elision/swap-elision-slot! :reg/params (constantly (get lowered :rf.runtime/elision)))
      (let [params    (nth k 2)
            projected (classification/project-entry-params
                        params k-id :reg/params :rf.egress/ssr-hydration)]
        (is (= privacy/redacted-sentinel (:account-id projected))
            "the registry-lowered :params :account-id slot is redacted")
        (is (= "q3" (:slug projected)) "the unmarked params sibling rides verbatim")
        (is (not (str/includes? (pr-str projected) "acct-secret-42")))))))

;; ===========================================================================
;; 3. SSR projection — the reconciliation end-to-end (registry-driven)
;; ===========================================================================

(deftest ssr-coarse-sensitive-classifies-redact-and-withholds-the-row
  (reg! :secret/thing {:sensitive? true})
  (testing "the coarse :sensitive? root-prop claim still classifies :redact, and
            rf2-4bjep settles what that costs the ROW: the projection re-keys
            both components, so no live client can address the entry and it does
            not ride. The claim is absence of the ROW, not of its data"
    (let [k    (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          e    (entry {:resource-id :secret/thing :data {:ssn "123-45-6789"}})
          rdb  (runtime-db-with {k e})
          proj (ssr/project-resources-runtime-db rdb)
          m    (only-projection-metadata rdb)]
      (is (= :redact (classification/whole-entry-disposition
                       (rf/resource-meta :secret/thing)))
          "premise: the coarse claim still classifies :redact")
      (is (empty? (get-in proj [state/resources-key :entries]))
          (str "the row does not ride: " (pr-str proj)))
      (is (not (str/includes? (pr-str proj) "123-45-6789"))
          "so the sensitive data cannot ride")
      (is (= :redacted (:disposition m)) "and the metadata still says why")
      (is (true? (:withheld? m)))
      (is (= :loaded (:status m)) "metadata (status) is still reported"))))

(deftest ssr-coarse-large-classifies-omit-and-withholds-the-row
  (reg! :big/thing {:large? true})
  (testing "the same for the coarse :large? root-prop claim: it still classifies
            :omit, and its row is withheld for the same identity reason"
    (let [k    (state/scoped-resource-key :rf.scope/global :big/thing {:slug "b"})
          e    (entry {:resource-id :big/thing :data (vec (range 10000))})
          rdb  (runtime-db-with {k e})
          proj (ssr/project-resources-runtime-db rdb)
          m    (only-projection-metadata rdb)]
      (is (= :omit (classification/whole-entry-disposition
                     (rf/resource-meta :big/thing)))
          "premise: the coarse claim still classifies :omit")
      (is (empty? (get-in proj [state/resources-key :entries]))
          "the row does not ride, so the large payload cannot")
      (is (= :omitted (:disposition m)))
      (is (true? (:withheld? m))))))

(deftest ssr-serialize-entry-projects-through-frame-classification
  (reg! :article/by-slug)   ;; a plain (no coarse claim) resource → :serialize
  (testing "EP-0015 §6: a :serialize resource's data is PROJECTED through the
            frame-owned project-egress on SSR — a frame-classified sensitive
            sub-path is redacted even though the resource itself carries no
            coarse claim (defense in depth via the frame-owned source of truth,
            unioned with the resource registry at lookup)"
    ;; the SSR projection resolves the current frame; classify a path on it via
    ;; the commit-plane effect at the entry's absolute :data path (the frame may
    ;; additionally classify a subsystem absolute path — Spec 015 L149).
    (rf/make-frame {:id :rcfg/ssr})
    (let [k    (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"})
          k-id (state/key-id k)
          e    (entry {:resource-id :article/by-slug
                       :data {:secret "tok-xyz" :title "hello"}})]
      (frame/swap-runtime-db! :rcfg/ssr
        (fn [rt] (elision/apply-classification-effects
                   rt {:sensitive [[:rf.runtime/resources :entries k-id :data :secret]]})))
      (let [proj (rf/with-frame :rcfg/ssr
                   (ssr/project-resources-runtime-db (runtime-db-with {k e})))
            [_ we] (only-wire-entry proj)]
        (is (= privacy/redacted-sentinel (:secret (:data we)))
            "the frame-classified :secret slot is redacted on the serialized entry")
        (is (= "hello" (:title (:data we)))
            "the unclassified sibling rides verbatim")
        (is (not (str/includes? (pr-str we) "tok-xyz"))
            "no raw sensitive value rides on the serialized entry")))))

(deftest ssr-serialize-entry-redacts-owner-data-declaration-slot
  (reg! :profile/card {:sensitive [[:data :pan]]})
  (testing "EP-0025 / rf2-d3pku1 END-TO-END: a :serialize resource whose OWN
            :data-rooted :sensitive declaration is LOWERED into the registry
            redacts that slot on SSR projection — the registry-driven egress
            read fires (the standard model)"
    (rf/make-frame {:id :rcfg/owner-data})
    (let [k   (state/scoped-resource-key :rf.scope/global :profile/card {:slug "x"})
          e   (entry {:resource-id :profile/card
                      :data {:pan "4111-1111-1111-1111" :name "Alice"}})
          [_ we] (only-wire-entry (ssr-project :rcfg/owner-data (runtime-db-with {k e})))]
      (is (= :serialize (classification/whole-entry-disposition
                          (rf/resource-meta :profile/card)))
          "no coarse claim → :serialize (the per-slot mark is the fine-grained surface)")
      (is (= privacy/redacted-sentinel (get-in we [:data :pan]))
          "the owner-declared :pan slot is redacted on the serialized entry")
      (is (= "Alice" (get-in we [:data :name])) "the unmarked sibling rides verbatim")
      (is (not (str/includes? (pr-str we) "4111-1111-1111-1111"))
          "no raw owner-marked value rides on the wire"))))

(deftest ssr-serialize-entry-elides-owner-large-declaration-slot
  (reg! :report/blob {:large [[:data :blob]]})
  (testing "rf2-260yhk / rf2-d3pku1 END-TO-END: a :serialize resource whose OWN
            :data-rooted :large declaration is lowered ELIDES that slot on SSR
            projection (the registry-driven owner surface fires)"
    (rf/make-frame {:id :rcfg/owner-large})
    (let [big (apply str (repeat 1000 "q"))
          k   (state/scoped-resource-key :rf.scope/global :report/blob {:slug "r"})
          e   (entry {:resource-id :report/blob :data {:blob big :name "ok"}})
          [_ we] (only-wire-entry (ssr-project :rcfg/owner-large (runtime-db-with {k e})))]
      (is (contains? (:blob (:data we)) :rf.size/large-elided)
          "the owner-declared :blob slot is elided on the serialized entry")
      (is (= "ok" (:name (:data we))) "the unmarked sibling rides verbatim")
      (is (= :loaded (:status we)) "metadata (status) still rides")
      (is (not (str/includes? (pr-str we) big))
          "no raw large value rides on the serialized entry"))))

(deftest ssr-serialize-entry-redacts-owner-params-declaration-slot-in-key
  (reg! :report/by-account {:sensitive [[:params :account-id]]
                            :params-schema [:map
                                            [:account-id :string]
                                            [:slug :string]]})
  (testing "EP-0025 / rf2-d3pku1 END-TO-END: a :serialize resource whose OWN
            :params-rooted :sensitive declaration is lowered must NOT ride that
            slot RAW in the projected wire KEY — the params surface is co-equal
            with the data surface (Spec 016 clause 4)"
    (rf/make-frame {:id :rcfg/owner-params})
    (let [k   (state/scoped-resource-key :rf.scope/global :report/by-account
                                         {:account-id "acct-secret-42" :slug "q3"})
          e   (entry {:resource-id :report/by-account :data {:total 99}})
          rdb (runtime-db-with {k e})
          wk  (ssr-projected-key :rcfg/owner-params rdb)]
      (is (= :serialize (classification/whole-entry-disposition
                          (rf/resource-meta :report/by-account)))
          "no coarse claim → :serialize (the per-slot params mark is the surface)")
      (is (= :report/by-account (nth wk 1)) "resource-id preserved (position 1)")
      (is (= privacy/redacted-sentinel (get-in wk [2 :account-id]))
          "the owner-declared :account-id params slot is redacted in the wire key")
      (is (= "q3" (get-in wk [2 :slug])) "the unmarked params sibling rides verbatim")
      (is (empty? (get-in (ssr-project :rcfg/owner-params rdb)
                          [state/resources-key :entries]))
          ;; rf2-rjq9d — redacting a params slot re-keys the entry (identity is
          ;; the canonical bytes of the WHOLE key), and the live client derives
          ;; the RAW key, so a shipped row would be unaddressable. Shipping it
          ;; metadata-only was the first answer and left an ownerless row in the
          ;; client's cache that nothing addresses and nothing collects, so the
          ;; row is WITHHELD. The end-to-end contract is
          ;; `resources_ssr_projected_key_refetch_cljs_test`; the co-equality
          ;; this test exists for — the params surface is projected exactly as
          ;; the data surface is — is unchanged and asserted on `wk` above.
          "a re-keyed entry does not ride at all (rf2-rjq9d)")
      (is (not (str/includes? (pr-str wk) "acct-secret-42"))
          "no raw sensitive params value rides in the wire key"))))

;; ===========================================================================
;; 3b. infinite-feed per-page egress (rf2-byl7bk.3.2 / rf2-d3pku1)
;; ===========================================================================
;;
;; EP-0021 R5 / Spec 016 §Registration — :infinite: an infinite feed's
;; `:data` is the framework-owned VECTOR OF PAGES. The lowered registry decl is
;; `[… :data :field]`; `elide-wire-value`'s index-free fork matches it against
;; the indexed runtime path `[… :data <page-idx> :field]` on EVERY page — no
;; special per-page branch.

(defn- infinite-entry-with-pages
  "Build a `:loaded` infinite-feed entry for `resource-id` carrying `pages` (a
  vector of decoded page values) as its durable `:data` page vector."
  [resource-id pages]
  (merge (state/empty-infinite-entry resource-id)
         {:status      :loaded
          :data        (vec pages)
          :page-params (vec (repeat (count pages) nil))
          :loaded-at   1000
          :stale-at    9.0e15}))

(deftest ssr-infinite-feed-redacts-sensitive-page-field-per-page
  (reg! :feed/timeline {:infinite        true
                        :next-page-param (fn [_last _all] nil)
                        :sensitive       [[:data :author-email]]})
  (testing "rf2-byl7bk.3.2 / rf2-d3pku1 END-TO-END: an infinite feed whose
            :data-rooted :sensitive declaration is lowered redacts that field on
            EVERY page during SSR projection (the index-free decl matches every
            indexed page path)"
    (rf/make-frame {:id :rcfg/infinite})
    (let [k    (state/scoped-resource-key :rf.scope/global :feed/timeline {:slug "t"})
          e    (infinite-entry-with-pages
                 :feed/timeline
                 [{:author-email "alice@example.com" :body "hello"}
                  {:author-email "bob@example.com" :body "world"}])
          [_ we] (only-wire-entry (ssr-project :rcfg/infinite (runtime-db-with {k e})))
          pages  (:data we)]
      (is (= :serialize (classification/whole-entry-disposition
                          (rf/resource-meta :feed/timeline)))
          "no coarse claim → :serialize")
      (is (vector? pages) "the page-vector shape is preserved on the wire")
      (is (= 2 (count pages)) "every accumulated page rides")
      (is (= privacy/redacted-sentinel (get-in pages [0 :author-email]))
          "page-0's sensitive field is redacted on the wire")
      (is (= privacy/redacted-sentinel (get-in pages [1 :author-email]))
          "page-1's sensitive field is redacted on the wire")
      (is (= "hello" (get-in pages [0 :body])) "page-0's unmarked field rides verbatim")
      (is (= "world" (get-in pages [1 :body])) "page-1's unmarked field rides verbatim")
      (is (not (str/includes? (pr-str we) "alice@example.com"))
          "no raw page-0 sensitive value rides anywhere on the entry")
      (is (not (str/includes? (pr-str we) "bob@example.com"))
          "no raw page-1 sensitive value rides anywhere on the entry"))))

;; ===========================================================================
;; 4. load-bearing Spec 016 rules preserved (the projection contract)
;; ===========================================================================

(deftest ssr-projection-rides-only-entries-slice
  (reg! :article/by-slug)
  (testing "Spec 016 §SSR: only the durable :rf.runtime/resources :entries
            slice rides — never the indexes, never all of runtime-db
            (allowlist-shaped, preserved under the reconciliation)"
    (let [k   (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"})
          e   (entry {:resource-id :article/by-slug :data {:title "X"}})
          rdb (assoc (runtime-db-with {k e})
                     :rf.runtime/machines {:snapshots {}}
                     :rf.runtime/routing  {:current {:route :x}})
          proj (ssr/project-resources-runtime-db rdb)]
      (is (= #{state/resources-key} (set (keys proj)))
          "only the resources subsystem key is projected")
      (is (= #{:entries} (set (keys (get proj state/resources-key))))
          "only :entries rides — indexes are recomputable-from-entries"))))

(deftest ssr-redacted-entry-installs-no-row-so-nothing-can-mistake-it-for-data
  (reg! :secret/thing {:sensitive? true})
  (testing "Spec 016 §SSR — the hazard was a redacted entry being read as
            fresh-with-data and never refetched. rf2-4bjep removes the hazard's
            subject rather than reclassifying it: the row does not ride, so the
            client's cache holds nothing to misclassify and the plan names
            nothing it cannot address. `entry-needs-refetch?`'s sentinel
            handling is unchanged and pinned by
            `refetch-plan-classifies-redacted-vs-omitted-vs-stale-vs-fresh`"
    (let [k    (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          e    (entry {:resource-id :secret/thing :data {:ssn "x"}})
          rdb  (runtime-db-with {k e})
          proj (ssr/project-resources-runtime-db rdb)
          m    (only-projection-metadata rdb)]
      (is (empty? (get-in proj [state/resources-key :entries]))
          "nothing rides")
      (is (empty? (get-in (ssr/hydrate-runtime-db proj nil)
                          [state/resources-key :entries]))
          "so the client installs no row for it")
      (is (empty? (ssr/hydrate-refetch-plan proj 5000))
          "and the plan names nothing")
      (is (true? (:refetch-on-client? m))
          "while the server's own metadata still says the client must fetch it
           — the fact is reported, not lost"))))

(deftest ssr-scoped-key-privacy-preserved-for-sensitive
  (reg! :secret/thing {:sensitive? true})
  (testing "Spec 016 clause 4: a sensitive resource's scope + params do NOT
            ride raw in the projected KEY (scoped-key privacy preserved under
            the reconciliation). rf2-4bjep — read off `projection-metadata`,
            since the row itself no longer rides at all"
    (let [scope [:rf.scope/session {:user "alice@example.com"}]
          k    (state/scoped-resource-key scope :secret/thing {:account-id "secret-42"})
          e    (entry {:resource-id :secret/thing :data {:ssn "x"}})
          rdb  (runtime-db-with {k e})
          wk   (:projected-key (only-projection-metadata rdb))]
      (is (= :secret/thing (nth wk 1)) "resource-id preserved (position 1)")
      (is (contains? (nth wk 0) :rf/redacted) "scope redacted in the key")
      (is (contains? (nth wk 2) :rf/redacted) "params redacted in the key")
      (let [s (pr-str wk)]
        (is (not (str/includes? s "alice@example.com")) "no raw user in the key")
        (is (not (str/includes? s "secret-42")) "no raw param in the key"))
      (let [s (pr-str (ssr/project-resources-runtime-db rdb))]
        (is (not (str/includes? s "alice@example.com")))
        (is (not (str/includes? s "secret-42")))
        (is (not (str/includes? s "rf/redacted"))
            (str "and no digest rides either — a 32-bit digest of a low-entropy "
                 "identity is enumerable, so withholding the row removes the "
                 "token's last carrier: " s))))))
