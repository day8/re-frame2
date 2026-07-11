(ns re-frame.ui.compiler.root
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
    - the macro bodies for `ui/mount` / `ui/create-root` / `ui/render!` /
      `ui/hydrate-root` (JVM-only — they run at CLJS macro expansion).

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
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.fingerprint :as fingerprint]
            #?@(:clj [[re-frame.ui.compiler :as compiler]
                      [re-frame.ui.compiler.emit-cljs :as emit-cljs]])))

;; ---------------------------------------------------------------------------
;; Root-id grammar (contract §1)
;; ---------------------------------------------------------------------------

(defn- fail
  ([id msg] (fail id msg nil))
  ([id msg data] (throw (env/compile-error id msg data))))

(defn- qualified-kw? [x]
  (and (keyword? x) (some? (namespace x))))

(defn scalar-disambiguator?
  "The disambiguator grammar: keyword, string, or integer (contract §1.2)."
  [x]
  (or (keyword? x) (string? x) (integer? x)))

(defn validate-authored-root-id!
  "Authored `:root-id` shapes (contract §1.1): a qualified keyword
  (canonical: `:page/shop`) or a vector of a qualified keyword plus scalar
  disambiguators. Anything else is a compile error — identity opts are
  literals, so the shape is always statically checkable."
  [where root-id]
  (when-not (or (qualified-kw? root-id)
                (and (vector? root-id)
                     (<= 2 (count root-id))
                     (qualified-kw? (first root-id))
                     (every? scalar-disambiguator? (rest root-id))))
    (fail :rf.ui.compile/bad-root-id
          (str where ": :root-id must be a qualified keyword (canonical: "
               ":page/shop) or a vector of a qualified keyword plus scalar "
               "disambiguators (e.g. [:shop/product-panel :left]); got "
               (pr-str root-id))
          {:root-id root-id}))
  root-id)

(defn- slug-str [s]
  (str/replace s #"[^A-Za-z0-9_-]" "-"))

(defn- kw-slug [k]
  (if-let [ns* (namespace k)]
    (str (slug-str ns*) "-" (slug-str (name k)))
    (slug-str (name k))))

(defn root-id-slug
  "The ONE deterministic slug (contract §1): keyword ->
  `namespace \"-\" name` (namespace absent -> name); vector -> element
  slugs joined by `\"--\"`; any character outside `[A-Za-z0-9_-]`
  normalised to `-`. `:page/shop` -> \"page-shop\";
  `[:shop/app :left]` -> \"shop-app--left\"."
  [root-id]
  (if (vector? root-id)
    (str/join "--" (map #(if (keyword? %) (kw-slug %) (slug-str (str %)))
                        root-id))
    (kw-slug root-id)))

(defn default-identifier-prefix
  "`\"rf2-\" + root-id-slug + \"-\"` (contract §3) — the `identifierPrefix`
  default fed to the React root."
  [root-id]
  (str "rf2-" (root-id-slug root-id) "-"))

;; ---------------------------------------------------------------------------
;; Top-region scan (contract §5/§6)
;; ---------------------------------------------------------------------------

(defn scan-root-ast
  "Walk an analyzed root-form AST through the top-region wrappers only
  (element / fragment / frame-root children — the analyzer already
  guarantees `:frame-root` nodes exist nowhere else) collecting, in
  document order: `:views` (top-region internal-view nodes) and `:plans`
  (`{:frame-id :config :config-fingerprint}` per `frame-root`)."
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
                (:element :fragment) (run! walk (:children n))
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
               "is not v1 grammar (runtime-chosen components are ui/view / "
               "ui/element [WAVE-2]; ui/raw covers a runtime React "
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
        (if (some? disambiguator)
          {:root-id [vid (validate-disambiguator! where disambiguator)]
           :provenance :derived}
          {:root-id vid :provenance :derived})))))

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
  its Root, fixed at `create-root`)."
  [{:keys [root-id provenance views plans ast build-digest]}]
  (let [view (when (= 1 (count views)) (first views))]
    (cond-> {:rf.root/schema-version 1
             :frame-plans (mapv #(select-keys % [:frame-id :config-fingerprint])
                                plans)
             :template-fingerprint (fingerprint/template-fingerprint ast)
             :build-digest build-digest}
      (some? root-id)   (assoc :root-id root-id)
      (some? provenance) (assoc :root-id-provenance provenance)
      view (assoc :view-id (:view-id view))
      view (merge (props-shape-entry view)))))

;; ---------------------------------------------------------------------------
;; Layer 1 — the build-tier duplicate/conflict indexes (contract §7)
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Layer-1 root-site index: root-id -> {:file :line :provenance}.
  Cross-FILE duplicates fail the build; same-file re-registration replaces
  (watch-mode re-expansion tolerance — Layer 3 backstops same-file
  duplicates at runtime). See the ns docstring §Build scope."}
  build-roots
  (atom {}))

(defonce ^{:doc "Layer-1 frame-plan index: frame-id -> {:config-fingerprint
  :file :line}. Two plans for one frame-id with differing fingerprints from
  different files fail the build (:rf.error/frame-payload-conflict)."}
  build-plans
  (atom {}))

(defonce ^{:doc "Compile-emitted Root Descriptors, root-id -> descriptor —
  the build-artefact side of \"S1 emits descriptors\" (S5 tooling / Xray
  read compile output through this index; the runtime copy rides the
  live-root registry, dev-only)."}
  build-descriptors
  (atom {}))

(defn reset-build-registries!
  "Test support: wipe the Layer-1 indexes + descriptor index."
  []
  (reset! build-roots {})
  (reset! build-plans {})
  (reset! build-descriptors {})
  nil)

(defn- site-str [{:keys [file line]}]
  (str (or file "<unknown-file>") (when line (str ":" line))))

(defn register-root-site!
  "Index one root site's statically resolved root-id (Layer 1). A second
  site in a DIFFERENT file with an equal root-id is the build-tier
  `:rf.error/duplicate-root-id` — thrown through the canonical builder,
  with the both-derived didactic fix per the contract."
  [where root-id provenance coords]
  (let [existing (get @build-roots root-id)]
    (when (and existing
               (:file existing) (:file coords)
               (not= (:file existing) (:file coords)))
      (error/throw-error!
       :rf.error/duplicate-root-id where
       (str "two root sites in one build resolve to root-id "
            (pr-str root-id) " — " (site-str existing) " and "
            (site-str coords) ". Root-ids are page-unique identity; "
            (if (= :derived (:provenance existing) provenance)
              (str "both ids derived from the same view — add "
                   ":disambiguator or author :root-id")
              "author distinct :root-id values"))
       {:recovery :make-root-ids-unique
        :extra {:root-id    root-id
                :provenance [(:provenance existing) provenance]
                :sites      [(dissoc existing :provenance) coords]}}))
    (swap! build-roots assoc root-id (assoc coords :provenance provenance))
    nil))

(defn register-plan-site!
  "Index one site's static frame plan (Layer 1). A plan for the same
  frame-id with a DIFFERING config fingerprint from a different file is
  the build-tier `:rf.error/frame-payload-conflict` (same-file
  re-registration replaces — watch tolerance; matching fingerprints are
  the ratified idempotent no-op)."
  [where {:keys [frame-id config-fingerprint]} coords]
  (let [existing (get @build-plans frame-id)]
    (when (and existing
               (not= (:config-fingerprint existing) config-fingerprint)
               (:file existing) (:file coords)
               (not= (:file existing) (:file coords)))
      (error/throw-error!
       :rf.error/frame-payload-conflict where
       (str "two root sites in one build carry static frame plans for "
            "frame " (pr-str frame-id) " with DIFFERING config "
            "fingerprints — " (site-str existing) " and " (site-str coords)
            ". One frame, one plan: keep the frame's config in ONE "
            "boot/root site, or align the configs")
       {:recovery :align-frame-plan-config
        :extra {:frame-id     frame-id
                :fingerprints [(:config-fingerprint existing)
                               config-fingerprint]
                :sites        [(dissoc existing :config-fingerprint) coords]}}))
    (swap! build-plans assoc frame-id {:config-fingerprint config-fingerprint
                                       :file (:file coords)
                                       :line (:line coords)})
    nil))

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

(defn- source-coords [form]
  (let [m (meta form)]
    (cond-> {:file (try @(requiring-resolve 'cljs.analyzer/*cljs-file*)
                        (catch Exception _ *file*))}
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(defn- print-warnings! [e where]
  (doseq [w @(:warnings e)]
    (binding [*out* *err*]
      (println (str "WARNING re-frame.ui [" where "] " (:id w) ": " (:msg w))))))

(defn- react-opts-form [prefix opts]
  `(re-frame.ui.client/root-options
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
  "`(ui/mount root-form dom-node opts)` — the one-shot client mount:
  create-root + frame preflight + render!, idempotent per root."
  [form menv root-form dom-node opts]
  (let [e      (expand-env! 'ui/mount menv)
        opts   (parse-root-opts! 'ui/mount opts mount-opt-keys)
        {:keys [ast views plans]} (analyze-root e 'ui/mount root-form)
        _      (print-warnings! e 'ui/mount)
        {:keys [root-id provenance]} (resolve-root-identity 'ui/mount opts views)
        prefix (or (:identifier-prefix opts) (default-identifier-prefix root-id))
        coords (source-coords form)
        _      (register-root-site! 'ui/mount root-id provenance coords)
        _      (doseq [p plans] (register-plan-site! 'ui/mount p coords))
        desc   (root-descriptor {:root-id root-id :provenance provenance
                                 :views views :plans plans :ast ast
                                 :build-digest (compiler/current-build-digest)})
        _      (swap! build-descriptors assoc root-id desc)
        body   (emit-cljs/emit-inline ast 'rf-ui-root)]
    `(re-frame.ui.client/mount*
      {:root-id ~root-id
       :provenance ~provenance
       :identifier-prefix ~prefix
       :site ~(dbg-quoted coords)
       :descriptor ~(dbg-quoted desc)}
      ~dom-node
      (fn [] ~body)
      ~(react-opts-form prefix opts)
      ~(plans-thunk-form plans))))

(defn create-root-form
  "`(ui/create-root dom-node opts)` — identity is fixed HERE for the
  Root's lifetime, and there is no root form to derive from, so an
  authored `:root-id` is REQUIRED (the derivation default belongs to the
  root-form-bearing entry points; `ui/mount` is the one-liner path)."
  [form menv dom-node opts]
  (expand-env! 'ui/create-root menv)
  (when (and (map? opts) (contains? opts :disambiguator))
    (fail :rf.ui.compile/bad-root-opts
          (str "ui/create-root: :disambiguator modifies DERIVATION and "
               "create-root has no root form to derive from — author "
               ":root-id")
          {:disambiguator (:disambiguator opts)}))
  (let [opts    (parse-root-opts! 'ui/create-root opts create-root-opt-keys)
        root-id (:root-id opts)]
    (when (nil? root-id)
      (fail :rf.ui.compile/missing-root-id
            (str "ui/create-root fixes root identity WITHOUT a root form — "
                 "there is no mounted view to derive from; author :root-id "
                 "(or use ui/mount, the derivation-default path)")
            nil))
    (let [prefix (or (:identifier-prefix opts)
                     (default-identifier-prefix root-id))
          coords (source-coords form)]
      (register-root-site! 'ui/create-root root-id :authored coords)
      `(re-frame.ui.client/create-root*
        {:root-id ~root-id
         :provenance :authored
         :identifier-prefix ~prefix
         :site ~(dbg-quoted coords)}
        ~dom-node
        ~(react-opts-form prefix opts)))))

(defn render-form
  "`(ui/render! root root-form)` — render/re-render the literal root form
  into a Root. Identity was fixed at `create-root` (authored), so no
  identity resolution happens here; the descriptor-base is completed with
  the Root's identity at runtime (dev)."
  [form menv root root-form]
  (let [e      (expand-env! 'ui/render! menv)
        {:keys [ast views plans]} (analyze-root e 'ui/render! root-form)
        _      (print-warnings! e 'ui/render!)
        coords (source-coords form)
        _      (doseq [p plans] (register-plan-site! 'ui/render! p coords))
        desc   (root-descriptor {:views views :plans plans :ast ast
                                 :build-digest (compiler/current-build-digest)})
        body   (emit-cljs/emit-inline ast 'rf-ui-root)]
    `(re-frame.ui.client/render!*
      ~root
      (fn [] ~body)
      ~(plans-thunk-form plans)
      ~(dbg-quoted desc))))

(defn hydrate-root-form
  "`(ui/hydrate-root dom-node root-form opts)` — hydrating mounts take
  identity FROM the manifest (contract §3/§4): identity opts client-side
  are a compile error; opts are host-behaviour tier only. Layer 1 indexes
  the site under its DERIVED root-id when derivable (the manifest carries
  the same id in the aligned case); the S1 runtime fails loud — manifests
  land S5."
  [form menv dom-node root-form opts]
  (let [e (expand-env! 'ui/hydrate-root menv)]
    (when-let [bad (seq (filter identity-opt-keys (keys opts)))]
      (fail :rf.ui.compile/identity-opts-at-hydrate
            (str "ui/hydrate-root: identity opt"
                 (when (next bad) "s") " " (str/join ", " (map pr-str bad))
                 " supplied client-side — hydrating mounts read root-id "
                 "and identifier-prefix FROM the server-emitted manifest "
                 "(the client must use the server's prefix or use-id "
                 "hydration breaks). Host-behaviour opts only")
            {:conflicting-keys (vec bad)}))
    (let [opts   (parse-root-opts! 'ui/hydrate-root opts hydrate-opt-keys)
          {:keys [ast views plans]} (analyze-root e 'ui/hydrate-root root-form)
          _      (print-warnings! e 'ui/hydrate-root)
          coords (source-coords form)]
      (when (= 1 (count views))
        (let [derived (:view-id (first views))]
          (register-root-site! 'ui/hydrate-root derived :derived coords)
          (swap! build-descriptors assoc derived
                 (root-descriptor {:root-id derived :provenance :derived
                                   :views views :plans plans :ast ast
                                   :build-digest (compiler/current-build-digest)}))))
      (doseq [p plans] (register-plan-site! 'ui/hydrate-root p coords))
      (let [body (emit-cljs/emit-inline ast 'rf-ui-root)]
        `(re-frame.ui.client/hydrate-root*
          ~dom-node
          (fn [] ~body)
          ~(plans-thunk-form plans)
          ~(react-opts-form nil opts))))))

))
