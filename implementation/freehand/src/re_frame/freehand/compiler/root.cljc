(ns re-frame.freehand.compiler.root
  "Root identity + mount-surface compiler half (S1c, rf2-vxgfnd.3; the
  root-identity-and-mount contract).

  What lives here:

    - the ROOT-ID grammar: authored validation (qualified keyword | vector
      of qualified keyword + scalar disambiguators), the derivation default
      (the mounted view's registered id, `[view-id disambiguator]` on
      double-mount), the deterministic root-id SLUG and the
      `identifierPrefix` default `\"rf2-\" + slug + \"-\"`;
    - the STATIC TOP-REGION scan over the analyzed root-form AST: the
      mounted view (exactly one for derivation), the static frame plans
      (`frame-root` `:id` + `config-fingerprint`), props-shape /
      static-props extraction;
    - ROOT DESCRIPTOR v1 assembly (`:rf.root/schema-version 1`) — the
      compile-time static subset of the Stage-5 Root Manifest; S1 emits
      descriptors only (manifests land S5);
    - LAYER-1 duplicate detection (build tier): the per-build root-site
      index (`:rf.error/duplicate-root-id`) and the frame-plan
      config-fingerprint index (`:rf.error/frame-payload-conflict`);
    - the macro bodies for `v/mount` / `v/create-root` / `v/render!` /
      `v/hydrate-root` (JVM-only — they run at CLJS macro expansion).

  ## Build scope of Layer 1 ([S1-CONFIRM] item 3 — resolved for S1)

  Macro expansion has no entry-point-closure visibility (shadow-cljs
  assigns modules after analysis), so S1 implements the STRICTER
  whole-build projection: every root site expanded by one compiler JVM
  lands in one index, and a duplicate root-id arriving from a DIFFERENT
  file fails the build. Sites re-expanded from the SAME file replace their
  entry (watch-mode/hot-reload tolerance — a moved line is not a second
  site); a genuine same-file duplicate therefore passes Layer 1 and is
  caught by the Layer-3 live-root registry at runtime, before any render.
  Entry-closure scoping (which would additionally ADMIT cross-entry reuse)
  is deferred until the first multi-entry consumer lands, per the
  contract's own [S1-CONFIRM] note — whole-build cannot admit a duplicate
  entry-closure would reject, so no consumer written against S1 breaks
  when the scope narrows.

  Pure analysis fns are host-neutral (resolution is injected via the env,
  the S1b pattern) so the conformance suites run under both
  `clojure -M:test` and the node runner; only the macro bodies are
  JVM-gated."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.fingerprint :as fingerprint]
            [re-frame.freehand.root-id :as root-id]
            #?@(:clj [[re-frame.registrar :as registrar]
                      [re-frame.freehand.compiler :as compiler]
                      [re-frame.freehand.compiler.emit-cljs :as emit-cljs]
                      [re-frame.freehand.compiler.emit-jvm :as emit-jvm]])))

;; ---------------------------------------------------------------------------
;; Root-id grammar (contract §1)
;; ---------------------------------------------------------------------------

(defn- fail
  ([id msg] (fail id msg nil))
  ([id msg data] (throw (env/compile-error id msg data))))

(def scalar-disambiguator?
  "The disambiguator grammar: keyword, string, or integer (contract §1.2).
  One implementation, in [[re-frame.freehand.root-id]] — the client tier
  asks the same question of the same opts map."
  root-id/scalar-disambiguator?)

(defn validate-authored-root-id!
  "Authored `:root-id` shapes (contract §1.1): a qualified keyword
  (canonical: `:page/shop`) or a vector of a qualified keyword plus scalar
  disambiguators. Anything else is a compile error — identity opts are
  literals, so the shape is always statically checkable."
  [where root-id]
  (when-not (root-id/authored-root-id? root-id)
    (fail :rf.ui.compile/bad-root-id
          (str where ": :root-id must be a qualified keyword (canonical: "
               ":page/shop) or a vector of a qualified keyword plus scalar "
               "disambiguators (e.g. [:shop/product-panel :left]); got "
               (pr-str root-id))
          {:root-id root-id}))
  root-id)

;; The slug and the `identifierPrefix` default it seeds are the SAME
;; function the client tier computes for a root nobody compiled, so they
;; live in `re-frame.freehand.root-id` and are read here. Two
;; implementations that agree today are two implementations that can
;; disagree tomorrow — and a compile-time prefix that disagreed with the
;; runtime one would break `use-id` hydration in a way neither tier could
;; see alone.

(def root-id-slug
  "The ONE deterministic, INJECTIVE slug (contract §1) —
  [[re-frame.freehand.root-id/root-id-slug]]."
  root-id/root-id-slug)

(def default-identifier-prefix
  "`\"rf2-\" + root-id-slug + \"-\"` (contract §3) — the `identifierPrefix`
  default fed to the React root."
  root-id/default-identifier-prefix)

;; ---------------------------------------------------------------------------
;; Top-region scan (contract §5/§6)
;; ---------------------------------------------------------------------------

(defn scan-root-ast
  "Walk an analyzed root-form AST through the top-region wrappers only
  (element / fragment / frame-root / frame-provider children — the analyzer
  already guarantees `:frame-root` nodes exist nowhere else) collecting, in
  document order: `:views` (top-region internal-view nodes) and `:plans`
  (`{:frame-id :config :config-fingerprint}` per `frame-root`). A
  `frame-provider` contributes NO plan (its frame is a runtime reference,
  not statically extractable — root-identity contract §6) but the walk
  descends THROUGH it, so a `frame-root` nested under a top-region
  `frame-provider` is still extracted."
  [ast]
  (let [acc (volatile! {:views [] :plans []})]
    (letfn [(walk [n]
              (case (:op n)
                :view       (vswap! acc update :views conj n)
                :frame-root (do (vswap! acc update :plans conj
                                        {:frame-id (:frame-id n)
                                         :config   (:config n)
                                         :config-fingerprint
                                         (fingerprint/config-fingerprint
                                          (:frame-id n) (:config n))})
                                (run! walk (:children n)))
                (:element :fragment :frame-provider) (run! walk (:children n))
                nil))]
      (walk ast))
    @acc))

(defn check-plan-set!
  "Reject two plans for ONE frame-id with differing config fingerprints
  inside one root form (the intra-form arm of the Layer-1 rule; contract
  §7); dedupe identical `[frame-id fingerprint]` pairs (the ratified
  idempotent no-op), keeping document order of first sighting."
  [where plans]
  (doseq [[fid group] (group-by :frame-id plans)]
    (let [fps (distinct (map :config-fingerprint group))]
      (when (< 1 (count fps))
        (error/throw-error!
         :rf.error/frame-payload-conflict where
         (str "one root form carries two static frame plans for frame "
              (pr-str fid) " with DIFFERING config fingerprints — one "
              "frame, one plan: frame config belongs in ONE boot/root "
              "site (align the configs, or drop the duplicate frame-root)")
         {:recovery :align-frame-plan-config
          :extra {:frame-id fid :fingerprints (vec fps)}}))))
  ;; dedupe on the [frame-id fingerprint] identity pair, KEEPING :config
  ;; (the preflight thunk needs the source forms) and document order
  (let [seen (volatile! #{})]
    (filterv (fn [{:keys [frame-id config-fingerprint]}]
               (let [k [frame-id config-fingerprint]]
                 (if (contains? @seen k)
                   false
                   (do (vswap! seen conj k) true))))
             plans)))

(defn analyze-root
  "Analyze a LITERAL root form (`:top-region? true`):
  -> `{:ast .. :views .. :plans ..}` (plans deduped, conflict-checked,
  document order). Non-vector root forms are a compile error — the
  compiler must see the root to keep the AST closed and extract frame
  plans."
  [e where form]
  (when-not (vector? form)
    (fail :rf.ui.compile/runtime-root-form
          (str where ": the root form must be a LITERAL vector at the call "
               "site — the compiler must see the root to keep the AST "
               "closed and extract frame plans. A runtime-assembled tree "
               "is not v1 grammar (runtime-chosen components are v/view / "
               "v/element [WAVE-2]; v/raw covers a runtime React "
               "element); a control form at root position wraps in a view "
               "(conservative S1 pin)")
          {:form form}))
  (let [ast (ana/analyze (assoc e :top-region? true) form)
        {:keys [views plans]} (scan-root-ast ast)]
    {:ast ast :views views :plans (check-plan-set! where plans)}))

;; ---------------------------------------------------------------------------
;; Identity resolution (contract §1)
;; ---------------------------------------------------------------------------

(defn validate-disambiguator! [where d]
  (when-not (scalar-disambiguator? d)
    (fail :rf.ui.compile/bad-disambiguator
          (str where ": :disambiguator must be a scalar compile-time "
               "literal (keyword, string, or integer); got " (pr-str d))
          {:disambiguator d}))
  d)

(defn resolve-root-identity
  "-> `{:root-id .. :provenance :authored|:derived}`. Authored wins;
  otherwise the root-id derives from the mounted view's registered id
  (+ `[view-id disambiguator]` when supplied). Derivation requires exactly
  one top-region internal view — zero (bare DOM / foreign root) or more
  than one (fragment of two views) is the contract-pinned compile error."
  [where {:keys [root-id disambiguator]} views]
  (if (some? root-id)
    {:root-id (validate-authored-root-id! where root-id)
     :provenance :authored}
    (let [n (count views)]
      (when (not= 1 n)
        (fail :rf.ui.compile/no-single-mounted-view
              (str where ": root form has no single mounted view — author "
                   ":root-id (found " n " top-region internal views; "
                   "derivation needs exactly one)")
              {:view-count n :view-ids (mapv :view-id views)}))
      (let [vid (:view-id (first views))]
        (when (some? disambiguator)
          (validate-disambiguator! where disambiguator))
        {:root-id    (root-id/derive-root-id vid disambiguator)
         :provenance :derived}))))

;; ---------------------------------------------------------------------------
;; Props extraction (contract §5) + Root Descriptor v1 (contract §2)
;; ---------------------------------------------------------------------------

(defn- literal-edn? [v]
  (cond
    (ana/literal-scalar? v) true
    (map? v)    (every? (fn [[k v']] (and (literal-edn? k) (literal-edn? v'))) v)
    (vector? v) (every? literal-edn? v)
    (set? v)    (every? literal-edn? v)
    :else false))

(defn props-shape-entry
  "`:props-shape` / `:static-props` for the mounted view's call-site props
  map (contract §5): every value a literal EDN datum -> `:literal` +
  verbatim `:static-props`; any non-literal expression -> `:dynamic`, no
  static props recorded (no guessing)."
  [view-node]
  (let [entries (get-in view-node [:props :entries])]
    (if (every? (fn [en] (and (nil? (:marker en)) (literal-edn? (:value en))))
                entries)
      {:props-shape  :literal
       :static-props (into {} (map (juxt :k :value)) entries)}
      {:props-shape :dynamic})))

(defn root-descriptor
  "Assemble Root Descriptor v1 (`:rf.root/schema-version 1`) — the named,
  versioned compile-time static SUBSET of the Stage-5 Root Manifest (same
  key family; the manifest is a strict superset; readers ignore unknown
  keys; additive keys do not bump the version). `:view-id` /
  `:props-shape` / `:static-props` are present iff the root form has
  exactly one top-region mounted view (an authored-id root may legally
  mount zero or several); `:root-id-provenance` is dev-only and never
  reaches shipped manifests. Identity keys are omitted when `root-id` is
  nil (the `render!` descriptor-base — the client completes identity from
  its Root, fixed at `create-root`).

  This is Root Descriptor v1 (Spec 004C §2): the per-root static facts, baked
  at expansion into the emitted CLJS and stored on the client live-root
  registry entry. Every field is a per-root static fact resolvable at the
  mount site's own expansion — the descriptor carries no whole-build
  aggregate."
  [{:keys [root-id provenance views plans ast]}]
  (let [view (when (= 1 (count views)) (first views))]
    (cond-> {:rf.root/schema-version 1
             :frame-plans (mapv #(select-keys % [:frame-id :config-fingerprint])
                                plans)
             :template-fingerprint (fingerprint/template-fingerprint ast)}
      (some? root-id)   (assoc :root-id root-id)
      (some? provenance) (assoc :root-id-provenance provenance)
      view (assoc :view-id (:view-id view))
      view (merge (props-shape-entry view)))))

;; ---------------------------------------------------------------------------
;; Layer 1 — the build-tier duplicate/conflict indexes (contract §7)
;; ---------------------------------------------------------------------------

;; The Layer-1 / descriptor aggregates are keyed PER build id in the one
;; build-scoped authority (`re-frame.freehand.compiler.build`, rf2-df9873) — no
;; process-global atom to cross-contaminate between the builds one daemon
;; compiles in parallel. Consumers read the ambient build's aggregate through
;; these accessors (a pure function of the current build inputs): a
;; recompiled / edited / renamed / removed source replaces its whole prior
;; contribution atomically, so a deleted root or plan cannot linger to raise
;; a FALSE cross-file duplicate/conflict.

(defn build-roots
  "The ambient build's Layer-1 root-site index: root-id ->
  {:file :line :provenance}. Cross-NAMESPACE duplicates fail the build;
  same-namespace re-registration replaces (watch-mode re-expansion tolerance
  — Layer 3 backstops same-namespace duplicates at runtime)."
  ([] (build/aggregate build/roots))
  ([build-state] (build/accepted-aggregate build/roots build-state)))

(defn build-plans
  "The ambient build's Layer-1 frame-plan index: frame-id ->
  {:config-fingerprint :file :line}. Two plans for one frame-id with
  differing fingerprints from different namespaces fail the build
  (:rf.error/frame-payload-conflict)."
  ([] (build/aggregate build/plans))
  ([build-state] (build/accepted-aggregate build/plans build-state)))

(defn build-descriptors
  "The ambient build's compile-emitted Root Descriptor index: root-id ->
  descriptor — the build-artefact side of \"S1 emits descriptors\" (S5
  tooling / Xray read compile output through here; the runtime copy rides the
  live-root registry, dev-only)."
  ([] (build/aggregate build/descriptors))
  ([build-state] (build/accepted-aggregate build/descriptors build-state)))

(defn reset-build-registries!
  "Hard-clear every build-scoped compiler registry (views, the Layer-1
  root/plan indexes, the descriptor index, compile-time custom-elements)
  and the contribution ledger — the clean-build / test-isolation boundary.
  Delegates to the one build-scoped model; correctness across a watch
  session comes from per-source replacement on `build/begin-build!`, not
  from this wipe (rf2-vxgfnd.16 AC6)."
  []
  (build/reset-build!)
  nil)

#?(:clj
   (defn descriptor-index
     "The Root Descriptor v1 index projected for READING (the S5 tooling /
     Xray / ui.test compiler-side read; Spec 004C §2): every stored per-root
     descriptor. Outside a compiler binding, callers pass the explicit
     retained Shadow build-state; no global latest build exists. Its CLIENT
     counterpart is `re-frame.freehand.client/descriptor-index`, which reads the
     same per-root static descriptors from the live-root registry."
     ([]
       ;; Reads must not mix effective open-pass descriptor rows with the
       ;; accepted set. Compiler diagnostics may use `build-descriptors`;
       ;; tooling sees one coherent snapshot.
       (into {} (build/committed-aggregate build/descriptors)))
     ([build-state]
      (into {} (build-descriptors build-state)))))

(defn- site-str [{:keys [file line]}]
  (str (or file "<unknown-file>") (when line (str ":" line))))

(defn register-root-site!
  "Index one root site's statically resolved root-id (Layer 1), owned by its
  declaring namespace `ns-sym`. A second site in a DIFFERENT namespace with
  an equal root-id is the build-tier `:rf.error/duplicate-root-id` — thrown
  through the canonical builder, with the both-derived didactic fix per the
  contract. Same-namespace re-registration REPLACES (watch-mode re-expansion
  tolerance — a moved / renamed / deleted site never conflicts with its own
  ghost).

  On a real Shadow build PASS (rf2-u53yy.1 S4) the whole-build roots registry is
  harvested at compile-finish from the disk-cache-durable SYNTHETIC per-namespace
  analyzer-map descriptor — root sites are CALL SITES with no def to carry
  var-meta (unlike views, S2), so this macro stamps the site there via
  `build/stamp-root-plan-site!` and does NOT contribute a per-source slice row (a
  warm cache-hit source never re-runs it). The cross-namespace duplicate-root-id
  law then runs at compile-finish over the carrier
  (`build/harvest-root-plan-registries`, a compile-finish error is still a
  build-time error). Off that path (plain-JVM / SSR, REPL — no compile-finish
  analyzer harvest) the per-source slice contribution + this expansion-time
  cross-namespace check apply; the check-then-write is ONE atomic transition, so
  parallel compilation cannot miss a duplicate or drop a concurrent write."
  [where root-id provenance ns-sym coords]
  (if (build/shadow-build-pass?)
    (build/stamp-root-plan-site!
     ns-sym :roots
     {:root-id root-id :row (assoc coords :provenance provenance)})
    (when-let [{:keys [conflict]}
               (build/contribute-checked!
                build/roots ns-sym root-id (assoc coords :provenance provenance)
                ;; any OTHER namespace's row for this root-id is the conflict
                (fn [existing] existing))]
      (error/throw-error!
       :rf.error/duplicate-root-id where
       (str "two root sites in one build resolve to root-id "
            (pr-str root-id) " — " (site-str conflict) " and "
            (site-str coords) ". Root-ids are page-unique identity; "
            (if (= :derived (:provenance conflict) provenance)
              (str "both ids derived from the same view — add "
                   ":disambiguator or author :root-id")
              "author distinct :root-id values"))
       {:recovery :make-root-ids-unique
        :extra {:root-id    root-id
                :provenance [(:provenance conflict) provenance]
                :sites      [(dissoc conflict :provenance) coords]}})))
  nil)

(defn register-plan-site!
  "Index one site's static frame plan (Layer 1), owned by its declaring
  namespace `ns-sym`. A plan for the same frame-id with a DIFFERING config
  fingerprint from a different namespace is the build-tier
  `:rf.error/frame-payload-conflict` (same-namespace re-registration replaces
  — watch tolerance; matching fingerprints are the ratified idempotent no-op).

  On a real Shadow build PASS (rf2-u53yy.1 S4) the plan site is stamped onto the
  SYNTHETIC per-namespace analyzer-map descriptor
  (`build/stamp-root-plan-site!`) rather than the per-source slice, and the
  cross-namespace frame-payload-conflict law runs at compile-finish over the
  carrier — the same decoupling S4 gives roots. Off that path (plain-JVM / SSR,
  REPL) the per-source slice contribution + this expansion-time check apply; the
  check-then-write is ONE atomic transition."
  [where {:keys [frame-id config-fingerprint]} ns-sym coords]
  (if (build/shadow-build-pass?)
    (build/stamp-root-plan-site!
     ns-sym :plans
     {:frame-id frame-id
      :row {:config-fingerprint config-fingerprint
            :file (:file coords) :line (:line coords)}})
    (when-let [{:keys [conflict]}
               (build/contribute-checked!
                build/plans ns-sym frame-id
                {:config-fingerprint config-fingerprint
                 :file (:file coords) :line (:line coords)}
                (fn [existing]
                  (when (and existing
                             (not= (:config-fingerprint existing) config-fingerprint))
                    existing)))]
      (error/throw-error!
       :rf.error/frame-payload-conflict where
       (str "two root sites in one build carry static frame plans for "
            "frame " (pr-str frame-id) " with DIFFERING config "
            "fingerprints — " (site-str conflict) " and " (site-str coords)
            ". One frame, one plan: keep the frame's config in ONE "
            "boot/root site, or align the configs")
       {:recovery :align-frame-plan-config
        :extra {:frame-id     frame-id
                :fingerprints [(:config-fingerprint conflict) config-fingerprint]
                :sites        [(dissoc conflict :config-fingerprint) coords]}})))
  nil)

(defn register-descriptor!
  "Index one root site's compile-emitted Root Descriptor (the build-artefact the
  S5 tooling / Xray read through `descriptor-index`), keyed by `root-id`, owned by
  its declaring namespace `ns-sym`.

  On a real Shadow build PASS (rf2-u53yy.1 S5) the descriptor rides the SAME
  SYNTHETIC per-namespace analyzer-map carrier as roots/plans (S4), under a
  `:descriptors` sub-key, via `build/stamp-root-plan-site!` — so a warm cache-hit
  source's descriptor is RESTORED from the disk cache without the macro re-running,
  and the whole-build descriptor index is harvested from the carrier at
  compile-finish. It contributes NO per-source slice row on that path. Every
  descriptor is registered at a mount / hydrate site that ALSO registers a root site
  under the same `root-id`, so the cross-namespace `:rf.error/duplicate-root-id`
  law (`register-root-site!` off the Shadow path, `harvest-root-plan-registries` at
  compile-finish on it) is the descriptor's conflict check too — no separate one is
  needed. Off the Shadow path (plain-JVM / SSR, REPL — no compile-finish harvest)
  the per-source slice contribution applies, the read path `descriptor-index` uses."
  [root-id descriptor ns-sym]
  (if (build/shadow-build-pass?)
    (build/stamp-root-plan-site!
     ns-sym :descriptors {:root-id root-id :row descriptor})
    (build/contribute! build/descriptors ns-sym root-id descriptor))
  nil)

;; ---------------------------------------------------------------------------
;; Root opts (contract §3)
;; ---------------------------------------------------------------------------

(def identity-opt-keys #{:root-id :disambiguator :identifier-prefix})
(def host-opt-keys #{:on-uncaught-error :on-caught-error :on-recoverable-error})

(def mount-opt-keys (into identity-opt-keys host-opt-keys))
(def create-root-opt-keys (into #{:root-id :identifier-prefix} host-opt-keys))
(def hydrate-opt-keys host-opt-keys)

(defn parse-root-opts!
  "Validate a root opts map at compile time: literal map, CLOSED key set,
  identity opts as compile-time literals (they feed the descriptor and
  Layer-1 duplicate detection). Host-tier callback values stay opaque
  runtime expressions. Returns opts."
  [where opts allowed]
  (when-not (map? opts)
    (fail :rf.ui.compile/bad-root-opts
          (str where ": the root opts map must be a LITERAL map at the "
               "call site — identity opts (:root-id / :disambiguator / "
               ":identifier-prefix) are compile-time literals; host "
               "callbacks may be runtime expressions INSIDE the literal "
               "map")
          {:opts opts}))
  (let [unknown (remove allowed (keys opts))]
    (when (seq unknown)
      (fail :rf.ui.compile/bad-root-opts
            (str where ": unknown root opt" (when (next unknown) "s") " "
                 (str/join ", " (map pr-str unknown))
                 " — the opts map is CLOSED; " where " accepts "
                 (str/join ", " (map pr-str (sort-by str allowed))))
            {:unknown (vec unknown)})))
  (when (and (contains? opts :root-id) (contains? opts :disambiguator))
    (fail :rf.ui.compile/bad-root-opts
          (str where ": :disambiguator is only meaningful when :root-id "
               "is absent — it modifies DERIVATION; an authored :root-id "
               "is already verbatim identity. Keep one")
          {:root-id (:root-id opts) :disambiguator (:disambiguator opts)}))
  (when (contains? opts :root-id)
    (validate-authored-root-id! where (:root-id opts)))
  (when (contains? opts :disambiguator)
    (validate-disambiguator! where (:disambiguator opts)))
  (when (contains? opts :identifier-prefix)
    (when-not (string? (:identifier-prefix opts))
      (fail :rf.ui.compile/bad-root-opts
            (str where ": :identifier-prefix must be a literal string "
                 "(fed to React's identifierPrefix); got "
                 (pr-str (:identifier-prefix opts)))
            {:identifier-prefix (:identifier-prefix opts)})))
  opts)

;; ---------------------------------------------------------------------------
;; Macro bodies (JVM only — CLJS macro expansion)
;; ---------------------------------------------------------------------------

#?(:clj
   (do

(def ^:private goog-debug (with-meta 'js/goog.DEBUG {:tag 'boolean}))

(defn- dbg-quoted
  "Emit `x` as quoted data under the goog.DEBUG gate (production carries
  no descriptors/site coords — I-12)."
  [x]
  `(when ~goog-debug (quote ~x)))

(defn- expand-env!
  "The mount-surface entry points are CLIENT entry points; expanding for
  the JVM host is a compile error (JVM structural rendering is
  ui.test/render (S1d) / render-static (S5))."
  [where menv]
  (when-not (:ns menv)
    (fail :rf.ui.compile/client-entry-on-jvm
          (str where " is a client entry point — there is no DOM on the "
               "JVM. JVM structural rendering is ui.test/render (S1d) / "
               "render-static (S5)")
          {:where where}))
  (env/make-env {:host :cljs :cljs-env menv :ns-sym (-> menv :ns :name)}))

(defn- source-coords [form cljs?]
  (let [m (meta form)]
    (cond-> {:file (if cljs?
                     (try @(requiring-resolve 'cljs.analyzer/*cljs-file*)
                          (catch Exception _ *file*))
                     *file*)}
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(defn- anchored
  "Run `thunk`; a compile error escaping it gains the call form's source
  coordinates (:file/:line/:column) in its ex-data — the S1e file:line
  anchoring, mirroring the `re-frame.freehand.compiler` expansion wrapper.
  Existing ex-data wins on collisions; id, message and cause are
  preserved."
  [coords thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (throw
         (if (and (:rf.ui.compile/error data) (nil? (:file data)))
           (ex-info (ex-message ex) (merge coords data) ex)
           ex))))))

(defn- print-warnings! [e where]
  (doseq [w @(:warnings e)]
    (binding [*out* *err*]
      (println (str "WARNING re-frame.freehand [" where "] " (:id w) ": " (:msg w))))))

(defn- react-opts-form [prefix opts]
  `(re-frame.freehand.client/root-options
    ~prefix
    ~(:on-uncaught-error opts)
    ~(:on-caught-error opts)
    ~(:on-recoverable-error opts)))

(defn- plans-thunk-form
  "The preflight thunk: evaluates each plan's config EXPRESSIONS exactly
  when preflight runs (never at S1, where no preflight hook is installed —
  the S2 frame wiring installs one). nil when the root form carries no
  plans."
  [plans]
  (when (seq plans)
    `(fn []
       [~@(map (fn [{:keys [frame-id config-fingerprint config]}]
                 {:frame-id frame-id
                  :config-fingerprint config-fingerprint
                  :config config})
               plans)])))

(defn mount-form
  "`(v/mount root-form dom-node opts)` — the one-shot client mount:
  create-root + frame preflight + render!, idempotent per root."
  [form menv root-form dom-node opts]
  (let [coords (source-coords form (some? (:ns menv)))
        ns-sym (-> menv :ns :name)]
    (anchored coords
      (fn []
        (let [e      (expand-env! 'v/mount menv)
              opts   (parse-root-opts! 'v/mount opts mount-opt-keys)
              {:keys [ast views plans]} (analyze-root e 'v/mount root-form)
              _      (print-warnings! e 'v/mount)
              {:keys [root-id provenance]} (resolve-root-identity
                                            'v/mount opts views)
              prefix (or (:identifier-prefix opts)
                         (default-identifier-prefix root-id))
              _      (register-root-site! 'v/mount root-id provenance
                                          ns-sym coords)
              _      (doseq [p plans]
                       (register-plan-site! 'v/mount p ns-sym coords))
              desc   (root-descriptor {:root-id root-id :provenance provenance
                                       :views views :plans plans :ast ast})
              _      (register-descriptor! root-id desc ns-sym)
              body   (emit-cljs/emit-inline ast 'rf-ui-root)]
          `(re-frame.freehand.client/mount*
            {:root-id ~root-id
             :provenance ~provenance
             :identifier-prefix ~prefix
             :site ~(dbg-quoted coords)
             :descriptor ~(dbg-quoted desc)}
            ~dom-node
            (fn [] ~body)
            ~(react-opts-form prefix opts)
            ~(plans-thunk-form plans)))))))

(defn create-root-form
  "`(v/create-root dom-node opts)` — identity is fixed HERE for the
  Root's lifetime, and there is no root form to derive from, so an
  authored `:root-id` is REQUIRED (the derivation default belongs to the
  root-form-bearing entry points; `v/mount` is the one-liner path)."
  [form menv dom-node opts]
  (let [coords (source-coords form (some? (:ns menv)))
        ns-sym (-> menv :ns :name)]
    (anchored coords
      (fn []
        (expand-env! 'v/create-root menv)
        (when (and (map? opts) (contains? opts :disambiguator))
          (fail :rf.ui.compile/bad-root-opts
                (str "v/create-root: :disambiguator modifies DERIVATION and "
                     "create-root has no root form to derive from — author "
                     ":root-id")
                {:disambiguator (:disambiguator opts)}))
        (let [opts    (parse-root-opts! 'v/create-root opts create-root-opt-keys)
              root-id (:root-id opts)]
          (when (nil? root-id)
            (fail :rf.ui.compile/missing-root-id
                  (str "v/create-root fixes root identity WITHOUT a root "
                       "form — there is no mounted view to derive from; "
                       "author :root-id (or use v/mount, the "
                       "derivation-default path)")
                  nil))
          (let [prefix (or (:identifier-prefix opts)
                           (default-identifier-prefix root-id))]
            (register-root-site! 'v/create-root root-id :authored ns-sym coords)
            `(re-frame.freehand.client/create-root*
              {:root-id ~root-id
               :provenance :authored
               :identifier-prefix ~prefix
               :site ~(dbg-quoted coords)}
              ~dom-node
              ~(react-opts-form prefix opts))))))))

(defn render-form
  "`(v/render! root root-form)` — render/re-render the literal root form
  into a Root. Identity was fixed at `create-root` (authored), so no
  identity resolution happens here; the descriptor-base is completed with
  the Root's identity at runtime (dev)."
  [form menv root root-form]
  (let [coords (source-coords form (some? (:ns menv)))
        ns-sym (-> menv :ns :name)]
    (anchored coords
      (fn []
        (let [e      (expand-env! 'v/render! menv)
              {:keys [ast views plans]} (analyze-root e 'v/render! root-form)
              _      (print-warnings! e 'v/render!)
              _      (doseq [p plans]
                       (register-plan-site! 'v/render! p ns-sym coords))
              desc   (root-descriptor {:views views :plans plans :ast ast})
              body   (emit-cljs/emit-inline ast 'rf-ui-root)]
          `(re-frame.freehand.client/render!*
            ~root
            (fn [] ~body)
            ~(plans-thunk-form plans)
            ~(dbg-quoted desc)))))))

(defn hydrate-root-form
  "`(v/hydrate-root dom-node root-form opts)` — hydrating mounts take
  identity FROM the manifest (contract §3/§4): identity opts client-side
  are a compile error; opts are host-behaviour tier only. Layer 1 indexes
  the site under its DERIVED root-id when derivable (the manifest carries
  the same id in the aligned case); the S1 runtime fails loud — manifests
  land S5."
  [form menv dom-node root-form opts]
  (let [coords (source-coords form (some? (:ns menv)))
        ns-sym (-> menv :ns :name)]
    (anchored coords
      (fn []
        (let [e (expand-env! 'v/hydrate-root menv)]
          (when-let [bad (seq (filter identity-opt-keys (keys opts)))]
            (fail :rf.ui.compile/identity-opts-at-hydrate
                  (str "v/hydrate-root: identity opt"
                       (when (next bad) "s") " " (str/join ", " (map pr-str bad))
                       " supplied client-side — hydrating mounts read root-id "
                       "and identifier-prefix FROM the server-emitted manifest "
                       "(the client must use the server's prefix or use-id "
                       "hydration breaks). Host-behaviour opts only")
                  {:conflicting-keys (vec bad)}))
          (let [opts (parse-root-opts! 'v/hydrate-root opts hydrate-opt-keys)
                {:keys [ast views plans]} (analyze-root
                                           e 'v/hydrate-root root-form)
                _    (print-warnings! e 'v/hydrate-root)]
            (when (= 1 (count views))
              (let [derived (:view-id (first views))]
                (register-root-site! 'v/hydrate-root derived :derived
                                     ns-sym coords)
                (register-descriptor!
                 derived
                 (root-descriptor {:root-id derived :provenance :derived
                                   :views views :plans plans :ast ast})
                 ns-sym)))
            (doseq [p plans]
              (register-plan-site! 'v/hydrate-root p ns-sym coords))
            (let [body (emit-cljs/emit-inline ast 'rf-ui-root)]
              `(re-frame.freehand.client/hydrate-root*
                ~dom-node
                (fn [] ~body)
                ~(plans-thunk-form plans)
                ~(react-opts-form nil opts)))))))))

(defn registered-view-static-facts
  "The render-static `{:caps :deps}` facts a compiled view carries on its
  REGISTERED manifest (`re-frame.freehand.tree/register-view!` → the `:view` registrar
  entry) — the cross-build-context resolution seam (rf2-uv7n6 hole 2). A view
  compiled in ANOTHER build (AOT / precompiled JAR) is absent from THIS build's
  `view-static` index, but its registration runs when its namespace loads, so its
  manifest (carrying `:static-facts`) is resolvable here. nil when the view is
  unregistered or its manifest predates static-facts — a genuinely UNPROVEN
  dependency the caller must fail loud on rather than treat as static-safe."
  [view-id]
  (get-in (registrar/lookup :view view-id) [:rf.ui/manifest :static-facts]))

(defn- static-capability-offender
  "The render-static transitive static-capability proof (Spec 004C §3, EP-0034
  §2 no-silent-elision): given the ROOT form's own `{:caps :deps}` facts and the
  ambient build's per-view `view-static` index, return

    - `{:view-id .. :caps ..}`     the FIRST server-reachable view (or the root
                                   form itself, `:rf.root/root-form`) carrying a
                                   runtime-requiring capability;
    - `{:view-id .. :unproven? true}`  the first referenced view whose facts are
                                   genuinely UNOBTAINABLE (absent from BOTH the
                                   ambient index and the registered manifest);
    - nil                          the whole server-reachable closure is proven
                                   static-safe.

  Cycle-safe: the closure follows direct compiled-view dependencies breadth-first
  over a `seen` set, so a self-recursive or mutually-recursive view terminates.
  Each dependency's facts resolve from the ambient index first, then from its
  registered manifest (a cross-build/AOT view). Unknown facts are NOT proof of
  static safety (rf2-uv7n6 hole 2): a dependency resolvable from NEITHER source is
  reported UNPROVEN, never silently skipped."
  [own-facts index]
  (if (seq (:caps own-facts))
    {:view-id :rf.root/root-form :caps (:caps own-facts)}
    (loop [queue (vec (:deps own-facts)), seen #{}]
      (when (seq queue)
        (let [vid   (peek queue)
              queue (pop queue)]
          (if (contains? seen vid)
            (recur queue seen)
            (if-let [{:keys [caps deps]} (or (get index vid)
                                             (registered-view-static-facts vid))]
              (if (seq caps)
                {:view-id vid :caps caps}
                (recur (into queue deps) (conj seen vid)))
              {:view-id vid :unproven? true})))))))

(defn render-static-form
  "`(v/render-static root-form)` — the pure `:server`-phase static-HTML render
  (Spec 004C §3; Spec 011 §Phase flip). JVM-ONLY, the counterpart of the client
  mount verbs: it compiles the LITERAL root form to the versioned JVM structural
  tree and folds it to an INERT HTML string through `re-frame.ssr/emit-ui-tree`
  (late-resolved — see INDEPENDENCE) — NO manifest, NO hydration payload, NO phase
  flip (a `client-only` site renders its capability-free fallback and stops there;
  there is no fallback pass to flip away from). It is the static-page path, not the
  SSR-then-hydrate path.

  Four invariants, all reusing the mount-surface machinery:

    - LITERAL ROOT FORM — `analyze-root` rejects a runtime-assembled vector with
      the SAME `:rf.ui.compile/runtime-root-form` compile error mount / render! /
      hydrate-root / ui.test-render raise (a macro sees the literal form; a fn
      cannot). This is the kind-label delta a fn could not honour.
    - NO SILENT ELISION (Spec 004C §3, EP-0034 §2) — a runtime-requiring
      capability (subs, committed handlers, effects, refs, context, foreign / lazy
      heads) anywhere in the root's SERVER-REACHABLE view closure is a loud
      `:rf.ui.compile/static-root-requires-runtime` build error with source
      coordinates, never a capability dropped from the static output. The proof is
      transitive: `compiler/view-static-facts` projects each compiled view's own
      server-reachable capabilities + direct-view dependencies into the
      `view-static` build index, and `static-capability-offender` closes over it
      cycle-safe. `use-id` is exempt (an inert deterministic id is static-safe);
      `v/client-only` stays legal (only its capability-free fallback is
      server-reachable — a static root never flips).
    - IDENTITY PARTICIPATION (§7) — with no opts the root-id derives from the
      single mounted view, so `resolve-root-identity` enforces exactly one view
      (`:rf.ui.compile/no-single-mounted-view` otherwise). The derived identity is
      RETAINED and registered into the Layer-1 build-tier duplicate-root-id index
      via `register-root-site!`, exactly like mount / render! / hydrate-root — a
      second site resolving to the same root-id is `:rf.error/duplicate-root-id`.
      Layer-2 duplicate detection (server render time, S5) is the SSR host's
      per-response registry, not the macro's.
    - INDEPENDENCE — the macro emits a call to the ui-side late-resolution seam
      `re-frame.freehand.tree/emit-static-html` (NOT a bare `re-frame.ssr/emit-ui-tree`
      reference), so `re-frame.freehand` never statically depends on `re-frame.ssr` (Spec
      011 §wall — no `ui → ssr` static require) AND the caller's namespace carries
      no compile-time `re-frame.ssr` reference: a documented render-static call in
      a namespace that requires only `re-frame.freehand` compiles and renders (the seam
      loads the emitter at render time) instead of failing with a raw
      `ClassNotFoundException`. A missing `day8/re-frame2-ssr` artefact is the
      ruled, typed `:rf.error/ssr-artefact-missing`, never a raw host exception.

  Expanding in a CLJS build is a compile error (`:rf.ui.compile/ui-render-static-jvm-only`)
  — CLJS has no structural trees (the client emitter targets React directly), and
  a CLJS expansion would emit the JVM tree + SSR serialiser into a browser bundle."
  [form menv root-form]
  ;; JVM-only. A CLJS expansion would (a) render statically in a browser — wrong —
  ;; and (b) pull the JVM tree + SSR emitter into the CLJS bundle (a ui→ssr edge).
  ;; Reject before touching the compiler, mirroring ui.test/render.
  (when (some? (:ns menv))
    (fail :rf.ui.compile/ui-render-static-jvm-only
          (str "v/render-static is the pure :server static-HTML render — it "
               "runs on the JVM/server. CLJS builds have no structural trees "
               "(the client emitter targets React directly); author render-static "
               "in a .clj / .cljc-on-JVM server render namespace, and mount in the "
               "browser with v/mount / v/hydrate-root")
          nil))
  (let [coords (source-coords form false)
        ns-sym (ns-name *ns*)]
    (anchored coords
      (fn []
        (let [e (-> (env/make-env {:host :clj :ns-sym ns-sym})
                    (env/with-locals (keys menv)))
              {:keys [ast views]} (analyze-root e 'v/render-static root-form)
              ;; Identity participation (§7): no opts ⇒ derive from the single
              ;; mounted view; reuse the exact one-view invariant + its frozen
              ;; compile id.
              {:keys [root-id provenance]} (resolve-root-identity
                                            'v/render-static {} views)]
          (print-warnings! e 'v/render-static)
          ;; No-silent-elision proof (Spec 004C §3, EP-0034 §2): a pure :server
          ;; static render cannot honour a live-runtime capability, so any
          ;; runtime-requiring capability in the root's server-reachable view
          ;; closure (subs, committed handlers, effects, refs, context, foreign /
          ;; lazy heads) is a loud BUILD error with source coordinates — never a
          ;; silently dropped capability. `v/client-only` stays legal (only its
          ;; capability-free fallback is server-reachable); `use-id` is exempt.
          (when-let [{:keys [view-id caps unproven?]}
                     (static-capability-offender
                      (compiler/view-static-facts ast @(:sites e))
                      (compiler/build-view-static))]
            (if unproven?
              ;; A referenced view whose render-static facts are UNOBTAINABLE
              ;; (absent from both the ambient index and the registered manifest)
              ;; is UNPROVEN — a loud build error, never a silently-inert render
              ;; that could drop an interactive dependency's handler (hole 2).
              (fail :rf.ui.compile/static-root-unproven-dependency
                    (str "v/render-static: the static root depends on view "
                         (pr-str view-id) " whose render-static facts are "
                         "UNPROVEN — they are absent from this build's view-static "
                         "index AND from the view's registered manifest (an "
                         "AOT/precompiled view compiled before static-facts, or a "
                         "stale registration). Unknown facts are not proof of "
                         "static safety, so render-static fails loud rather than "
                         "shipping a possibly-inert render. Recompile "
                         (pr-str view-id) " against a current re-frame.freehand, or "
                         "mount this tree in the browser with v/mount / "
                         "v/hydrate-root")
                    {:root-id root-id :unproven-view view-id})
              (fail :rf.ui.compile/static-root-requires-runtime
                    (str "v/render-static: the static root's server-reachable view "
                         "closure requires runtime capability "
                         (str/join ", " (map name (sort caps)))
                         (if (= :rf.root/root-form view-id)
                           " in the root form itself"
                           (str " (via view " (pr-str view-id) ")"))
                         " — render-static is the pure :server static-HTML path (no "
                         "subs, handlers, effects, refs, context, or foreign/lazy "
                         "heads; no manifest, no hydration, no phase flip). Mount "
                         "this tree in the browser with v/mount / v/hydrate-root, "
                         "or move the live subtree behind a v/client-only with a "
                         "capability-free fallback")
                    {:root-id root-id :offending-view view-id
                     :capabilities (vec (sort caps))})))
          ;; Layer-1 identity registration (§7): the static root participates in
          ;; the build-tier duplicate-root-id index exactly like mount / render! /
          ;; hydrate-root — the derived identity is retained, not discarded.
          (register-root-site! 'v/render-static root-id provenance ns-sym coords)
          ;; Emit a call to the ui-side late-resolution seam (NOT a bare
          ;; `re-frame.ssr/emit-ui-tree` reference) so the caller's namespace
          ;; carries no compile-time `re-frame.ssr` reference — a missing SSR
          ;; artefact is the ruled typed `:rf.error/ssr-artefact-missing`, not a
          ;; raw ClassNotFoundException. `re-frame.freehand` still takes no static
          ;; require on `re-frame.ssr` (the resolve is late, at render time).
          `(~'re-frame.freehand.tree/emit-static-html
            (~'re-frame.freehand.tree/render-root-tree
             (fn [] ~(emit-jvm/emit-node nil ast)))))))))

))
