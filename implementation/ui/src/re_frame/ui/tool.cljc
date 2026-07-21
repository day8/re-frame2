(ns re-frame.ui.tool
  "The DEV-ONLY public projection tier over the compiled-view evidence model
  (rf2-vxgfnd.95.6; Spec 004 §View identity and the instrumentation surface;
  04-debugging §1/§5). Five frozen read-only projections a debugging consumer
  (Xray, Story, Pair) reads WITHOUT touching private React state or the
  reactive scheduler:

    `view-manifest`     — the versioned public projection of a view's compiler
                          MANIFEST (what CAN happen): source, per-prop docs/
                          defaults, prop slots + schema, render-slot + interop
                          sites, capability bits, fingerprints, site counts.
    `mounted-views`     — the committed CONNECTED-INSTANCE records (what DID
                          happen): occurrence identity, owning root, live
                          connection state, honest hide-vs-unmount lifecycle
                          labels, and the bounded render evidence per incarnation.
    `explain-render`    — the render CAUSES for a view's live incarnations: the
                          bounded cause set, occurrence/epoch counters, the moving
                          observation targets, and explicit loss accounting.
    `view-dependencies` — the reactive dependency SITES a view declares
                          (subscriptions) with literal-vs-`:dynamic`
                          query-shape honesty.
    `view-event-sites`  — the event-handler SITES a view declares, distinguishing
                          LITERAL (`:vector`) and NORMALIZED (`:options`) event
                          vectors from opaque `:dynamic` handlers — never claiming
                          a raw callback's internals are inspectable.

  THE SHAPES ARE VERSIONED. Every projection stamps `:rf.ui.tool/version`
  (`schema-version`) so a consumer built against an older head reads the version
  and degrades rather than mis-parsing. The projections are DETERMINISTIC,
  SERIALIZABLE, and READ-ONLY: they mint no evidence, retain nothing, and egress
  no ViewCell / React object — only bounded plain data.

  DEV-ONLY. Every entry point is compile-time gated on `interop/debug-enabled?`
  and returns nil (or an empty envelope) under `:advanced` + goog.DEBUG=false;
  the whole tier DCEs out of production (05 §4; G-7/G-11). Manifests live in the
  process-global `:view` registry both hosts populate (`re-frame.ui.runtime`/
  `re-frame.ui.tree`); the connected-instance evidence lives in the
  `re-frame.ui.tool.evidence` retention engine this tier extends."
  (:require [re-frame.interop          :as interop]
            [re-frame.registrar        :as registrar]
            [re-frame.ui.tool.evidence :as evidence]))

#?(:clj (set! *warn-on-reflection* true))

(def schema-version
  "The versioned tool-evidence public-shape contract version. Bumped whenever the
  evidence schema changes incompatibly, so a consumer reads `:rf.ui.tool/version`
  and reconciles rather than mis-parsing an evolved shape. v2 dropped a removed
  view-lifetime dependency site kind and its `:site-counts` entry. v3 versions the
  S6 committed-instance evidence schema (rf2-vxgfnd.98.1 / EP-0033 §S6 view-evidence
  delta): the DEBUG-only per-commit committed-instance record the reactive substrate
  now mints and exposes through `re-frame.ui.reactive/commit-record` — integer
  `:render-key`, per-observation `:observations`, `:root-incarnation` (the opaque
  per-mount root-incarnation token the raw record carries, nil before
  `attach-root!` — NOT a serializable authored root-id; the tool projection
  resolves it to the authored root-id it surfaces downstream via
  `re-frame.ui.tool.evidence`), `:generation`,
  `:connection`, and the per-commit `:rf.view/causes` vector (the six shipped kinds
  :mount / :subscription / :story-override / :local-state / :hmr / :disposed, plus
  the :foreign-or-react honesty fallback). Consumers that pin this version exactly
  (Xray, Story, Pair) move in lockstep so none degrades to `[]` on a mismatch."
  3)

;; ---- manifest access (both hosts register `:view` into the registrar) --------

(defn- manifest-of
  "The compiler manifest registered for `view-id` (dev), or nil. Both the CLJS
  runtime and the JVM tree register the manifest under the process-global `:view`
  registry key `:rf.ui/manifest`."
  [view-id]
  (:rf.ui/manifest (registrar/lookup :view view-id)))

;; ---- literal/dynamic query honesty -------------------------------------------

(defn- literal-form?
  "True when `x` is pure literal DATA — a scalar, or a collection built wholly of
  literals, or a `(quote …)` — with NO free symbol and NO call. A sub query
  that satisfies this is exactly the authored runtime shape and
  is safe to project verbatim; anything with a free symbol (a captured local) or
  a call is `:dynamic` and must NOT be presented as the literal value."
  [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (char? x) (string? x) (keyword? x)) true
    (symbol? x) false
    (vector? x) (every? literal-form? x)
    (set? x)    (every? literal-form? x)
    (map? x)    (every? (fn [[k v]] (and (literal-form? k) (literal-form? v))) x)
    (seq? x)    (= 'quote (first x))
    :else       false))

;; ---- 1. view-manifest --------------------------------------------------------

(defn- literal-map-schema-entries
  "When `schema` is a literal Malli `[:map …]`, return `{prop-key {…}}` carrying
  each child entry's `:doc`/`:default`/`:optional?`/`:schema`. nil for a registry
  reference keyword, an `[:and …]`/`[:or …]` wrapper, or a runtime schema object —
  those are left to the raw `:props-schema`. A bounded walk over one literal map,
  not a general schema interpreter."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (into {}
          (keep (fn [entry]
                  (when (and (vector? entry) (keyword? (first entry)))
                    (let [[k a b] entry
                          props   (when (map? a) a)
                          child   (if props b a)]
                      [k (cond-> {}
                           (:doc props)               (assoc :doc (:doc props))
                           (contains? props :default) (assoc :default (:default props))
                           (some? props)              (assoc :optional? (boolean (:optional props)))
                           (some? child)              (assoc :schema child))]))))
          ;; a `[:map {map-props} …]` puts an optional properties map first
          (drop-while map? (rest schema)))))

(defn- project-props
  "One source of truth for a view's props: each declared prop slot merged with the
  per-prop docs/defaults/schema derivable from a literal `[:map …]` props schema
  (readiness P1-5 — component libraries consume THIS instead of a parallel
  args-description system). Slots with no literal schema entry carry key/slot/index
  alone (honest — no fabricated doc)."
  [prop-slots props-schema]
  (let [by-key (literal-map-schema-entries props-schema)]
    (mapv (fn [{:keys [key slot index]}]
            (merge {:key key :slot slot :index index}
                   (get by-key key)))
          prop-slots)))

(defn- project-manifest
  [m]
  (let [sites (:sites m)]
    {:rf.ui.tool/version   schema-version
     :view-id              (:view-id m)
     :display-name         (:display-name m)
     :doc                  (:doc m)
     :source               (:source m)
     :props                (project-props (:prop-slots m) (:props-schema m))
     :props-schema         (:props-schema m)
     :open-props?          (boolean (:open-props? m))
     :children?            (boolean (:children? m))
     :as?                  (boolean (:as? m))
     :capabilities         (:capabilities m)
     :render-slots         (mapv #(select-keys % [:sid :source-coord :inline?])
                                 (:slots sites))
     :interop-sites        (mapv #(select-keys % [:sid :kind :order])
                                 (:react sites))
     ;; The compile-tier a11y findings (S4-C, Spec 004 §Compile-tier warnings).
     ;; SUPPRESSED findings ride along carrying their reason — a suppression is
     ;; an inspectable fact, not an erasure — so a tool can show what an author
     ;; silenced and why. Site ids are compiler-minted; nothing re-derives them.
     :diagnostics          (mapv #(select-keys % [:sid :id :tag :suppressed? :reason])
                                 (:diagnostics sites))
     :template-fingerprint (:template-fingerprint m)
     :hook-signature       (:hook-signature m)
     :site-counts          {:subs         (count (:subs sites))
                            :events       (count (:events sites))
                            :effects      (count (:effects sites))
                            :dispatch-fns (count (:dispatch-fns sites))
                            :frame-ops    (count (:frame-ops sites))
                            :render-slots (count (:slots sites))
                            :interop      (count (:react sites))
                            :locals       (count (:locals sites))
                            :htmls        (count (:htmls sites))
                            :diagnostics  (count (:diagnostics sites))}}))

(defn view-manifest
  "The versioned public projection of the compiled view `view-id`'s manifest —
  what CAN happen, useful BEFORE mount. Carries the view's source coord, per-prop
  docs/defaults/schema (the single source of truth replacing parallel
  args-description systems), declared render-slot + interop sites, capability bits,
  fingerprints, and site counts. Returns nil when `view-id` is not a registered
  compiled view, or in a production build."
  [view-id]
  (when interop/debug-enabled?
    (when-some [m (manifest-of view-id)]
      (project-manifest m))))

;; ---- 2. mounted-views --------------------------------------------------------

(defn mounted-views
  "The committed CONNECTED-INSTANCE records for every incarnation the evidence
  tier currently retains — occurrence identity, owning root, live connection
  state, honest hide-vs-unmount lifecycle labels, and the bounded render evidence.
  A currently-connected cell reads `:connection :connected`; an Activity-hidden
  cell the tool deliberately retains reads `:connection :disconnected` with the
  qualified retroactive labels (never a fabricated `:unmounted`). Speculative
  renders publish nothing. An empty (but versioned) envelope when no evidence tool
  is installed; nil in a production build."
  []
  (when interop/debug-enabled?
    {:rf.ui.tool/version schema-version
     :views              (or (evidence/instance-records) [])}))

;; ---- 3. explain-render -------------------------------------------------------

(defn- explain-occurrence
  [{:keys [occurrence view-id root-id connection lifecycle evidence]}]
  (let [ev evidence]
    {:occurrence   occurrence
     :view-id      view-id
     :root-id      root-id
     :connection   connection
     :lifecycle    lifecycle
     :causes       (or (:causes ev) #{})
     :render-count (or (:count ev) 0)
     :batches      (or (:batches ev) 0)
     :first-epoch  (:first-epoch ev)
     :latest-epoch (:latest-epoch ev)
     ;; two DISTINCT honesty axes over the moving targets: `:identity-exact?`
     ;; says the SHOWN targets kept their exact identity (no opaque/bounded
     ;; marker), while `:loss` says how many DISTINCT targets were omitted past
     ;; the shown cap. The shown sample is complete exactly when `:loss :dropped`
     ;; is 0; neither field ever presents a lower bound as completeness (04 §2).
     :observations {:targets         (or (:targets ev) [])
                    :identity-exact? (if (nil? ev) true (boolean (:targets-exact? ev)))}
     :loss         {:dropped (count (:dropped ev))
                    :exact?  (if (nil? ev) true (boolean (:dropped-exact? ev)))}}))

(defn explain-render
  "Why did view `view-id`'s live incarnations render? Each occurrence projects the
  bounded render CAUSES (the `:subscription`/`:hmr`/`:disposed` cause set), the occurrence
  and batch counters, the first/latest movement epochs, the moving observation
  targets, and EXPLICIT loss accounting (`:dropped` with an `:exact?` flag — a
  count is a completeness claim only when exact). With no arg, every retained
  incarnation. Occurrences are keyed by stable occurrence identity, so two
  instances of one view are distinguishable. An empty (but versioned) envelope
  with no evidence; nil in a production build."
  ([] (explain-render nil))
  ([view-id]
   (when interop/debug-enabled?
     {:rf.ui.tool/version schema-version
      :view-id            view-id
      :occurrences        (into []
                                (comp (filter #(or (nil? view-id)
                                                   (= view-id (:view-id %))))
                                      (map explain-occurrence))
                                (or (evidence/instance-records) []))})))

;; ---- 4. view-dependencies ----------------------------------------------------

(defn- project-sub-site
  [{:keys [sid query path]}]
  (if (literal-form? query)
    {:sid sid :path path :dynamic? false :query query}
    {:sid sid :path path :dynamic? true
     :query-id (when (and (vector? query) (keyword? (first query))) (first query))}))

(defn view-dependencies
  "The reactive dependency SITES the compiled view `view-id` declares — its
  `sub` sites — with query-shape honesty: a fully-literal query is
  projected verbatim (`:dynamic? false`), a query carrying a captured local is
  `:dynamic? true` (its literal `:query-id`, when present, is still shown — the
  runtime argument is not fabricated). Read from the compiler manifest, so it is
  available before mount. nil for an unregistered view or in production."
  [view-id]
  (when interop/debug-enabled?
    (when-some [m (manifest-of view-id)]
      (let [sites (:sites m)]
        {:rf.ui.tool/version schema-version
         :view-id            view-id
         :subscriptions      (mapv project-sub-site (:subs sites))}))))

;; ---- 5. view-event-sites -----------------------------------------------------

(defn- site-kind
  "The coarse honesty grouping over the fine compiler classification: a LITERAL
  event vector, a NORMALIZED options-map event vector, or an opaque DYNAMIC
  handler (`ui/event`/`ui/handler`/bare-fn/dynamic-expr — never inspectable)."
  [classification]
  (case classification
    :vector  :literal
    :options :normalized
    :dynamic))

(defn- project-event-site
  [{:keys [sid prop source-coord path classification serializable? sync? handler]}]
  (let [serial? (boolean serializable?)]
    (cond-> {:sid             sid
             :prop            prop
             :source-coord    source-coord
             :occurrence-path path
             :classification  classification
             :site-kind       (site-kind classification)
             :serializable?   serial?
             :sync?           (boolean sync?)}
      ;; A serializable site's authored event vector IS its inspectable shape
      ;; (04 §3 — literal + normalized-branch); an opaque handler shows a marker
      ;; and nothing more, never a claim about the callback's internals.
      (and serial? (not= :opaque handler)) (assoc :handler handler)
      (or (not serial?) (= :opaque handler)) (assoc :handler :opaque))))

(defn view-event-sites
  "The event-handler SITES the compiled view `view-id` declares (its `:on-*`
  handlers), each classified for HONESTY: `:site-kind :literal` for a literal
  event vector and `:normalized` for an options-map event vector — both carry
  their inspectable `:handler` event vector; `:dynamic` for a `ui/event`,
  `ui/handler`, bare fn, or dynamic expression — shown as `:handler :opaque`, the
  tier never claiming a raw callback's internals are inspectable. `:serializable?`
  and `:sync?` (the controlled-input synchronous door) ride each site. Read from
  the compiler manifest. nil for an unregistered view or in production."
  [view-id]
  (when interop/debug-enabled?
    (when-some [m (manifest-of view-id)]
      {:rf.ui.tool/version schema-version
       :view-id            view-id
       :handlers           (mapv project-event-site (:events (:sites m)))})))
