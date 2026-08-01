(ns re-frame.resources-ssr-wire-key-scope-declaration-cljs-test
  "A `:serialize` owner's `:scope`-rooted declaration is honoured in the SSR
  DURABLE wire key (rf2-5e2ye) — the last carrier of that value that disagreed.

  ## The asymmetry this suite closes

  `classification/instance-declaration-paths` has always lowered a
  `:scope`-rooted declaration to the entry's ABSOLUTE `[… :resource/key 0 …]`
  path exactly as it lowers a `:params`-rooted one to `[… :resource/key 2 …]`,
  so the epoch / registry walk over the runtime-db honoured BOTH, and since
  rf2-dl7bz `trace-egress/redact-key-declarations` honours both on every trace
  and every tool key. But `ssr/project-entry` projected ONLY index 2. So a
  resource declaring

    (rf/reg-resource :tenant/report
      {:sensitive [[:scope :tenant-id]] …} …)

  with NO coarse `:sensitive?` root prop classifies `:serialize`, and the
  resolved tenant id rode RAW inside `:resource/key` in the hydration payload —
  while the SAME bytes redacted in the durable epoch export and on every trace
  row. One value, three carriers, two rules applied: the rf2-irwsq shape.

  ## Build posture: this one is ALWAYS-ON

  rf2-dl7bz's trace / tool arm is DEV-ONLY — every caller sits behind
  `interop/debug-enabled?` or bundle isolation. This one is NOT. The projection
  under test is the body behind `:ssr/extend-runtime-db-projection`, called from
  `re-frame.ssr.payload-policy/project-runtime-db` on every SSR render, and its
  output ships in the `:rf/hydration-payload` to EVERY VISITOR of every page in
  a production bundle. There is no debug gate anywhere on that path. That
  difference is the whole reason this half of the family mattered more than the
  half that could be reasoned about as a tool-console guarantee.

  ## The re-key is the mechanism, not a hazard — §2 pins it

  `state/key-id` is `canonical-bytes` over the WHOLE `[scope resource-id
  params]` vector, so projecting EITHER component necessarily changes it. That
  is not a new consequence of this change: `project-resources-runtime-db` re-keys
  each wire entry on the key-id of its own PROJECTED `:resource/key`, the coarse
  `:redact` / `:omit` digests have always re-keyed BOTH components that way, and
  the params arm has since rf2-d3pku1. §2 pins the invariant that actually has
  to hold — the wire MAP key and the wire ENTRY's own `:resource/key` are one
  value — and §4 pins that the client's `recompute-indexes` round-trip still
  lands on it. Closing the scope asymmetry extends an accepted cost by one
  index; it does not introduce one.

  ## No over-redaction — every assertion has a two-sided control

  Over-redaction is a DEFECT on this surface, not a safe default (rf2-dl7bz's
  second finding: a declaration-only owner's whole reply body tokenized and its
  undeclared siblings vanished). §3 is the negative side: an owner declaring
  nothing rides BYTE-identical with an UNCHANGED key-id, `:rf.scope/global` is
  untouched, the scope TIER keyword survives, an undeclared sibling of a
  declared identity slot stays readable, and the resource-id survives at
  position 1 so every per-key join still lands.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the :resource registrar kind, and
   ;; publishes the trace-egress hooks the epoch tool-pair consults.
   [re-frame.resources]
   [re-frame.resources.classification :as classification]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.trace-egress :as trace-egress]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(def ^:private tenant-secret "tenant-SECRET-99")
(def ^:private account-secret "acct-SECRET-4417")

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Four owners spanning the grains this suite discriminates:

    :tenant/report  — `:serialize` (NO coarse prop) + a `[:scope :tenant-id]`
                      declaration. THE SUBJECT.
    :both/report    — declares on BOTH components, to prove the two arms
                      compose inside one key rather than one winning.
    :plain/report   — declares NOTHING. The byte-identity control.
    :sealed/report  — the COARSE `:sensitive?` owner, to prove the per-slot arm
                      leaves the coarse whole-component digests alone."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "SSR wire-key scope-declaration suite frame."})
  (rf/reg-resource :tenant/report
    {:scope         :rf.scope/global
     :sensitive     [[:scope :tenant-id]]
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/tenant"}}))
  (rf/reg-resource :both/report
    {:scope         :rf.scope/global
     :sensitive     [[:scope :tenant-id] [:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/both"}}))
  (rf/reg-resource :plain/report
    {:scope         :rf.scope/global
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}}))
  (rf/reg-resource :sealed/report
    {:scope         :rf.scope/global
     :sensitive?    true
     :sensitive     [[:scope :tenant-id]]
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter #?(:clj plain-atom/adapter :cljs reagent-adapter/adapter)
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- session-scope
  "The `[tier {identity}]` tuple a `:scope`-rooted declaration has to reach
  THROUGH. `:roles` is deliberately a LIST: Clojure `=` collapses
  `(= [:a :b] '(:a :b))` but CEDN-1 `canonical-bytes` never does (`l(…)` vs
  `v[…]`), so an UNNECESSARY walk of this component would reconstruct the list
  as a vector and silently change the entry's `key-id` — the rf2-wgutc2 identity
  break the `registry-classifies-under?` gate exists to prevent. It is what
  makes §3's byte-identity control load-bearing rather than decorative."
  []
  [:rf.scope/session {:tenant-id tenant-secret
                      :region    "au"
                      :roles     '(:admin :ops)}])

(defn- key-for
  "A key under the `[tier {identity}]` session scope — the shape a
  `:scope`-rooted declaration has to reach THROUGH."
  ([resource-id] (key-for resource-id {:page 3}))
  ([resource-id params]
   (state/scoped-resource-key (session-scope) resource-id params)))

(defn- install-entry!
  "Write a durable `:loaded` entry for `scoped-key` into the frame's runtime-db
  AND reconcile the per-frame elision registry, so the SSR durable projection
  has the lowered declaration to read — the same two steps a real resource
  commit folds into one atomic transition. Returns the key."
  [frame-id scoped-key]
  (let [resource-id (second scoped-key)]
    (frame/swap-runtime-db!
      frame-id
      (fn [rdb]
        (-> (or rdb {})
            (assoc-in (state/entry-path scoped-key)
                      (assoc (state/empty-entry resource-id scoped-key)
                             :status :loaded
                             :data   {:total 1}))
            (classification/reconcile-registry registry/resource-meta)))))
  scoped-key)

(defn- wire-entries
  "The projected `:entries` map exactly as it rides the `:rf/hydration-payload`'s
  `:rf/runtime-db` slice — keyed on the byte key-id of each entry's PROJECTED
  `:resource/key`."
  [frame-id]
  (get-in (ssr/project-resources-runtime-db
            (frame/frame-runtime-db-value frame-id) frame-id)
          [state/resources-key :entries]))

(defn- wire-entry-for
  "The single wire entry whose key names `resource-id`."
  [frame-id resource-id]
  (some (fn [[k-id e]]
          (when (= (second (:resource/key e)) resource-id) [k-id e]))
        (wire-entries frame-id)))

(defn- wire-key
  "The key the SSR projection PRODUCED for `resource-id`, read off
  `projection-metadata`'s `:projected-key`.

  It is read from the metadata rather than from the wire row because a re-keyed
  `:serialize` entry no longer HAS a wire row: rf2-rjq9d withholds it, since an
  unaddressable row installs on the client as an ownerless duplicate that
  nothing can reach and nothing can collect. The projection is unchanged — the
  registry-driven per-slot walk still runs and still produces this key — so
  every assertion in this suite about what the projection does to a key is
  unaffected. Only the carrier moved.

  For an entry that DOES ride, this is byte-identical to the wire row's own
  `:resource/key`, which `the-wire-map-key-is-the-key-id-of-the-projected-key`
  pins directly against the map key."
  [frame-id resource-id]
  (some (fn [m] (when (= resource-id (second (:resource/key m))) (:projected-key m)))
        (ssr/projection-metadata
          frame-id 5000
          (get-in (frame/frame-runtime-db-value frame-id) (state/entries-path)))))

(defn- leaks?
  "Whether `secret` survives ANYWHERE in `v` — the plaintext test."
  [secret v]
  (str/includes? (pr-str v) secret))

;; ===========================================================================
;; 1. THE LEAK. A `:scope`-rooted declaration must not ride raw in the SSR
;;    hydration wire key.
;;
;;    This is the assertion the sibling trace suite
;;    (`resources_trace_key_declarations_egress_cljs_test` §4) could only make
;;    about the PARAMS component, because the scope half did not exist yet.
;; ===========================================================================

(deftest scope-rooted-declaration-does-not-ride-raw-in-the-ssr-wire-key
  (testing "rf2-5e2ye — a resource declaring {:sensitive [[:scope :tenant-id]]}
            with NO coarse :sensitive? prop classifies :serialize, and its
            resolved tenant id must NOT ride raw in the hydration payload"
    (let [k (install-entry! :rf/default (key-for :tenant/report))
          w (wire-key :rf/default :tenant/report)]
      (is (some? w) "premise: the SSR projection produced a wire key")
      (is (leaks? tenant-secret k) "premise: the RAW key carries the tenant id")
      (is (not (leaks? tenant-secret w))
          (str "the declared [:scope :tenant-id] must not survive the SSR wire "
               "key — got " (pr-str w)))
      (is (= :rf/redacted (get-in w [0 1 :tenant-id]))
          "…it is the redaction sentinel, reached index-free through the
           [tier {identity}] tuple"))))

(deftest the-whole-hydration-payload-slice-is-free-of-the-declared-scope
  (testing "not only the entry's own :resource/key copy — the tenant id must be
            absent from the ENTIRE projected slice, map keys included (the byte
            key-id is a REVERSIBLE plaintext CEDN-1 encoding, so a stale raw
            key-id in the wire MAP key would leak just as loudly)"
    (install-entry! :rf/default (key-for :tenant/report))
    ;; the COARSE owner is the filler: it shares this suite's session scope but
    ;; digests it, so the slice is non-empty WITHOUT the secret riding by
    ;; design. `:plain/report` cannot serve here — it declares nothing, so its
    ;; scope rides verbatim, which is exactly what
    ;; `an-undeclared-owners-key-rides-byte-identical` asserts.
    (install-entry! :rf/default (key-for :sealed/report))
    (let [slice (ssr/project-resources-runtime-db
                  (frame/frame-runtime-db-value :rf/default) :rf/default)
          w     (wire-key :rf/default :tenant/report)]
      (is (seq (get-in slice [state/resources-key :entries]))
          "premise: the slice is not empty — rf2-rjq9d withholds the declaring
           owner's row, so this claim would otherwise be satisfied by a slice
           with nothing in it at all")
      (is (not (leaks? tenant-secret slice))
          (str "the tenant id survived somewhere in the wire slice: "
               (pr-str slice)))
      (is (not (leaks? tenant-secret [w (state/key-id w)]))
          (str "…nor in the projected key or its key-id, which is where it
                would ride if the row were sent — " (pr-str w))))))

(deftest both-components-of-one-key-are-projected
  (testing "an owner declaring on BOTH components has both redacted in the ONE
            wire key — the two arms compose rather than one winning"
    (install-entry! :rf/default (key-for :both/report
                                         {:account-id account-secret :page 3}))
    (let [w (wire-key :rf/default :both/report)]
      (is (= :rf/redacted (get-in w [0 1 :tenant-id])) "the scope arm fired")
      (is (= :rf/redacted (get-in w [2 :account-id])) "the params arm fired")
      (is (not (leaks? tenant-secret w)))
      (is (not (leaks? account-secret w)))
      (is (= :both/report (nth w 1)) "the resource-id survives at position 1")
      (is (= 3 (get-in w [2 :page])) "the undeclared param sibling still rides")
      (is (= "au" (get-in w [0 1 :region]))
          "the undeclared scope sibling still rides"))))

;; ===========================================================================
;; 2. THE RE-KEYING INTERACTION — the bead's Q1, pinned rather than argued.
;;
;;    `state/key-id` is `canonical-bytes` over the WHOLE key vector, so
;;    projecting index 0 CHANGES it. The invariant that has to hold is not
;;    "the key-id is stable" — it is "the wire MAP key and the wire ENTRY's own
;;    :resource/key are ONE value". `project-resources-runtime-db` maintains
;;    that by construction, and always has: the coarse :redact / :omit digests
;;    re-key both components, and the params arm re-keys index 2.
;; ===========================================================================

(deftest the-wire-map-key-is-the-key-id-of-the-projected-key
  (testing "rf2-5e2ye Q1 — every wire entry is keyed on the byte key-id of its
            OWN projected :resource/key, so a projected component can never
            leave the map key and the in-entry copy disagreeing"
    (install-entry! :rf/default (key-for :tenant/report))
    (install-entry! :rf/default (key-for :both/report
                                         {:account-id account-secret :page 3}))
    (install-entry! :rf/default (key-for :plain/report))
    (install-entry! :rf/default (key-for :sealed/report))
    (is (= 2 (count (wire-entries :rf/default)))
        "premise: two of the four rows ride — the two re-keyed by a per-slot
         declaration are withheld (rf2-rjq9d), so a `doseq` over the wire is
         not a `doseq` over nothing")
    (doseq [[k-id e] (wire-entries :rf/default)]
      (is (= k-id (state/key-id (:resource/key e)))
          (str "wire map key must equal key-id of the entry's own projected "
               ":resource/key — entry " (pr-str (:resource/key e)))))))

(deftest projecting-the-scope-changes-the-key-id-exactly-as-params-does
  (testing "rf2-5e2ye Q1 — the honest statement of the interaction: the raw
            key's key-id is NOT a wire map key once a component projects, and
            that is true of the SCOPE arm for exactly the same reason it is
            already true of the PARAMS arm and of the coarse digests"
    (let [scope-k  (install-entry! :rf/default (key-for :tenant/report))
          plain-k  (install-entry! :rf/default (key-for :plain/report))
          sealed-k (install-entry! :rf/default (key-for :sealed/report))
          wired    (wire-entries :rf/default)]
      (is (not (contains? wired (state/key-id scope-k)))
          "a declared SCOPE re-keys the entry — the raw key-id is gone")
      (is (not (contains? wired (state/key-id sealed-k)))
          "…as the coarse whole-component digests always have")
      (is (contains? wired (state/key-id plain-k))
          "…while an UNDECLARED key keeps its byte identity exactly"))))

;; ===========================================================================
;; 3. NO OVER-REDACTION. The two-sided control.
;; ===========================================================================

(deftest an-undeclared-owners-key-rides-byte-identical
  (testing "a resource declaring NOTHING must ride its key back byte-identical
            with an UNCHANGED key-id — the declaration-existence gate is what
            preserves the CEDN-1 identity a cache-key round-trip depends on.

            The LIST-valued `:roles` slot is what gives this teeth: `=` cannot
            see a list↔vector collapse but `canonical-bytes` can, so an
            ungated walk would change the key-id here while every `=`
            assertion still passed (rf2-wgutc2)"
    (let [k (install-entry! :rf/default (key-for :plain/report))
          w (wire-key :rf/default :plain/report)]
      (is (seq? (get-in k [0 1 :roles]))
          "premise: the canonical scoped key PRESERVES the list kind")
      (is (= k w) "the whole key is unchanged")
      (is (= (pr-str k) (pr-str w)) "…byte-for-byte, not merely `=`")
      (is (seq? (get-in w [0 1 :roles]))
          "…the list is still a LIST — no walker reconstruction happened")
      (is (= (state/key-id k) (state/key-id w)) "…so its key-id is unchanged")
      (is (leaks? tenant-secret w)
          "and its scope rides VERBATIM — nothing here scrubs by shape"))))

(deftest the-declared-key-keeps-everything-not-declared
  (testing "the scope TIER keyword, the undeclared identity sibling, and the
            resource-id all survive, so every attribution / join a consumer
            makes still lands"
    (install-entry! :rf/default (key-for :tenant/report))
    (let [w (wire-key :rf/default :tenant/report)]
      (is (= :rf.scope/session (get-in w [0 0]))
          "the scope TIER keyword survives (attribution)")
      (is (= "au" (get-in w [0 1 :region]))
          "an undeclared sibling of the scope identity stays readable")
      (is (= :tenant/report (nth w 1)) "the resource-id survives at position 1")
      (is (= 3 (get-in w [2 :page]))
          "the params component is untouched — nothing is declared there"))))

(deftest a-global-scope-is-never-walked
  (testing "`:rf.scope/global` is a bare keyword, not a [tier {identity}] tuple.
            An owner that declares nothing under :scope must leave it exactly
            as it found it"
    (let [k (install-entry! :rf/default
                            (state/scoped-resource-key
                              :rf.scope/global :plain/report {:page 3}))
          w (wire-key :rf/default :plain/report)]
      (is (= :rf.scope/global (nth w 0)))
      (is (= k w))
      (is (= (state/key-id k) (state/key-id w))))))

(deftest a-key-declaration-does-not-promote-the-entry-to-a-coarse-claim
  (testing "rf2-dl7bz's second finding, on this surface: a declaration naming
            only the KEY must not promote the entry to a coarse claim. Its
            SIBLINGS are the proof — an owner that declares nothing under
            :data must still ride its body verbatim, so the walk is gated on
            the declaration and not on the owner"
    (install-entry! :rf/default (key-for :plain/report))
    (let [[_ e] (wire-entry-for :rf/default :plain/report)]
      (is (= {:total 1} (:data e))
          "the body rides: nothing here is declared, and nothing is swallowed")
      (is (= :loaded (:status e))))))

(deftest a-key-declaration-withholds-the-entry-from-the-wire
  (testing "rf2-rjq9d — the DECLARING owner's own entry does not ride AT ALL,
            and the reason is reachability rather than privacy. Projecting a key
            component re-keys the entry (§2 below), and the live client derives
            the RAW key, so a shipped row would be unaddressable: dead payload
            beside the duplicate the client loads anyway, and — because the
            per-slot substitution is a CONSTANT sentinel rather than a
            content-addressed digest — a row two principals can collapse onto.
            Shipping it EMPTY was the first answer, and it left an ownerless row
            in the client's cache that nothing addresses and nothing collects.
            So it is withheld.

            The end-to-end statement of that contract (hydrate reconcile, live
            route/ensure, the exactly-one-request count, the zero-ghost control)
            lives in `resources_ssr_projected_key_refetch_cljs_test`"
    (install-entry! :rf/default (key-for :tenant/report))
    (install-entry! :rf/default (key-for :plain/report))
    (is (nil? (wire-entry-for :rf/default :tenant/report))
        "no wire row names the re-keyed owner, under any key")
    (is (some? (wire-entry-for :rf/default :plain/report))
        "…while the undeclared sibling still rides, so this is withholding and
         not an empty projection")
    (let [m (some (fn [m] (when (= :tenant/report (second (:resource/key m))) m))
                  (ssr/projection-metadata
                    :rf/default 5000
                    (get-in (frame/frame-runtime-db-value :rf/default)
                            (state/entries-path))))]
      (is (= :key-projected (:disposition m))
          "the server-side metadata still announces what the server knew")
      (is (true? (:refetch-on-client? m))
          "…including that the client will have to fetch it"))))

;; ===========================================================================
;; 4. THE CLIENT ROUND-TRIP STILL LANDS (the bead's proof standard).
;; ===========================================================================

(deftest recompute-indexes-round-trips-over-the-projected-entries
  (testing "rf2-5e2ye — the client rebuilds its reverse indexes from the
            INSTALLED :entries rather than trusting the wire, so the index
            members must be the projected map keys and every member must
            resolve back to an entry"
    (install-entry! :rf/default (key-for :tenant/report))
    (install-entry! :rf/default (key-for :plain/report))
    ;; the COARSE owner is what keeps a re-keyed row in this round-trip:
    ;; `:tenant/report`'s row is withheld (rf2-rjq9d), and a round-trip over
    ;; nothing but byte-identical keys would not exercise the projected map key
    ;; at all.
    (install-entry! :rf/default (key-for :sealed/report))
    (let [wired    (wire-entries :rf/default)
          subtree  (state/recompute-indexes {:entries wired})
          members  (into #{} cat (vals (:owner-index subtree)))]
      (is (not (contains? wired (state/key-id (key-for :sealed/report))))
          "premise: the coarse row IS re-keyed — its raw key-id is not a map key")
      (is (= (set (keys wired))
             (set (keys (:entries subtree))))
          "install is lossless — one entry in, one entry out")
      (doseq [m members]
        (is (contains? wired m)
            (str "index member " (pr-str m) " must resolve to an installed entry")))
      (doseq [[k-id e] (:entries subtree)]
        (is (= k-id (state/key-id (:resource/key e)))
            "…and the round-tripped entry still agrees with its own key")))))

;; ===========================================================================
;; 5. ONE VALUE, THREE CARRIERS, ONE RULE. The agreement that stops a fourth
;;    answer appearing later.
;; ===========================================================================

(deftest the-ssr-wire-key-agrees-with-the-trace-key-on-the-scope-component
  (testing "rf2-5e2ye — the registry-driven SSR projection and the spec-derived
            trace projection (`redact-key-declarations`, rf2-dl7bz) are TWO
            derivations of ONE answer. They were already byte-equal on the
            params component; this is the statement that they now are on the
            scope component too"
    (let [k     (install-entry! :rf/default (key-for :tenant/report))
          wire  (wire-key :rf/default :tenant/report)
          trace (:resource/key
                  (trace-egress/project-resource-trace-egress
                    {:rf.frame/id :rf/default :resource/key k} :rf/default))]
      (is (= (pr-str (nth wire 0)) (pr-str (nth trace 0)))
          (str "the SSR wire key's SCOPE component must be BYTE-equal to the "
               "trace key's — wire " (pr-str (nth wire 0))
               " vs trace " (pr-str (nth trace 0)))))))

(deftest the-two-derivations-agree-on-a-key-declared-on-both-components
  (testing "…and they agree on the WHOLE key when both components are declared"
    (let [k     (install-entry! :rf/default
                                (key-for :both/report
                                         {:account-id account-secret :page 3}))
          wire  (wire-key :rf/default :both/report)
          trace (:resource/key
                  (trace-egress/project-resource-trace-egress
                    {:rf.frame/id :rf/default :resource/key k} :rf/default))]
      (is (= (pr-str wire) (pr-str trace))
          (str "wire " (pr-str wire) " vs trace " (pr-str trace))))))

;; ===========================================================================
;; 6. THE COARSE ARM IS UNCHANGED — the two arms compose by grain.
;; ===========================================================================

(deftest a-coarse-sensitive-owner-still-tokenizes-both-components
  (testing "a COARSE :sensitive? owner's key still redacts to the opaque
            content-addressed tokens, subsuming the per-slot surface. The
            per-slot scope arm must not change what the coarse arm produces"
    (let [k (install-entry! :rf/default (key-for :sealed/report))
          w (wire-key :rf/default :sealed/report)]
      (is (contains? (nth w 0) :rf/redacted) "the WHOLE scope is one token")
      (is (contains? (nth w 2) :rf/redacted) "…and so is the whole params")
      (is (= :sealed/report (nth w 1)) "the resource-id survives")
      (is (not (leaks? tenant-secret w)))
      (is (= w (ssr/project-scoped-key k :redact nil))
          "byte-for-byte what `project-scoped-key` alone produces"))))

;; ===========================================================================
;; 7. THE rf2-dl7bz DEFERRAL IS STILL INTACT. `project-scoped-key` was NOT
;;    widened by this change either.
;;
;;    The standing statement lives in
;;    `resources_trace_key_declarations_egress_cljs_test` §5
;;    (`project-scoped-key-still-defers-on-serialize`); this is the local
;;    restatement, so a future reader of THIS suite sees the fence too.
;; ===========================================================================

(deftest project-scoped-key-still-defers-on-serialize-for-a-scope-declaration
  (testing "rf2-dl7bz ruling — the per-slot answer is the REGISTRY's on this
            path (it has the entry's key-id and the live frame). The shared
            `project-scoped-key` still rides a `:serialize` key verbatim and
            still ignores its spec, for a :scope declaration exactly as for a
            :params one. This test reds if anyone moves the arm into the
            shared projection"
    (let [k    (key-for :tenant/report)
          spec (registry/resource-meta :tenant/report)]
      (is (= k (ssr/project-scoped-key k :serialize spec))
          "`:serialize` still rides VERBATIM through `project-scoped-key`")
      (is (= (ssr/project-scoped-key k :serialize spec)
             (ssr/project-scoped-key k :serialize nil))
          "…and it still ignores the spec argument, exactly as documented"))))

;; ===========================================================================
;; 8. THE UNIT, DIRECTLY. `project-entry-scope` is the co-equal sibling of
;;    `project-entry-params` — same gate, same frame-scoping, different index.
;; ===========================================================================

(deftest project-entry-scope-is-frame-scoped-and-declaration-gated
  (testing "the two guards `project-entry-params` carries, on the scope arm"
    (let [k      (install-entry! :rf/default (key-for :tenant/report))
          key-id (state/key-id k)
          scope  (nth k 0)]
      (is (= :rf/redacted
             (get-in (classification/project-entry-scope
                       scope key-id :rf/default :rf.egress/ssr-hydration)
                     [1 :tenant-id]))
          "under a live frame with the declaration lowered, the slot redacts")
      (is (= scope (classification/project-entry-scope
                     scope key-id nil :rf.egress/ssr-hydration))
          "a nil frame rides the scope VERBATIM — the registry is frame-scoped")
      (let [plain-k (install-entry! :rf/default (key-for :plain/report))]
        (is (= (nth plain-k 0)
               (classification/project-entry-scope
                 (nth plain-k 0) (state/key-id plain-k)
                 :rf/default :rf.egress/ssr-hydration))
            "an UNDECLARED offset rides the scope VERBATIM — no walk, no
             list↔vector collapse, byte identity preserved")))))
