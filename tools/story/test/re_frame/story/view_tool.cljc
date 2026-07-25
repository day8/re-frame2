(ns re-frame.story.view-tool
  "Story's honest consumer of the compiled-view tool tier (`re-frame.ui.tool`,
  rf2-vxgfnd.95.6; bead rf2-vxgfnd.95.8 — S3 Story + Pair consumers).

  Story renders VARIANTS (Storybook-class stories). When a variant's active
  view is a compiled `re-frame.ui` view, Story can SHOW the SAME S3 evidence
  Xray and Pair read — WITHOUT owning any app resource, minting evidence, or
  egressing a private handle. Every read here is one of the five frozen
  `re-frame.ui.tool` projections (`view-manifest` / `view-dependencies` /
  `view-event-sites` / `mounted-views` / `explain-render`); Story adds only
  honest SHAPING + LABELLING:

    - view IDENTITY + the manifest projection (what CAN happen), keyed on the
      variant plan's active view-id (`[:world :component]`);
    - the reactive dependency + event SITES the view declares;
    - the committed CONNECTED-INSTANCE records + render CAUSES for the view's
      live incarnations (the S3 occurrence / cause / loss semantics), tolerated
      as explicitly UNAVAILABLE rather than a fabricated emptiness;
    - the observation-target OVERRIDES in effect for the variant, each labelled
      `:owned? false` — Story OBSERVES a view through the shared override door
      (`ui.test/render {:sub-overrides …}` on the JVM, the
      `re-frame.adapter.sub-override-context` Provider on CLJS); it never
      acquires or releases the reactive handle behind an override. Core lowers a
      `:sub-overrides` HIT to a callback-free STATIC `:story-override` handle,
      `:owned? false` (`re-frame.substrate.observation` / `re-frame.ui.reactive`
      §override door), so the label is the CORE contract Story surfaces — not a
      claim Story invents.

  ABSENCE is tolerated EXPLICITLY at every read (the S3 discipline): an
  uncompiled variant view (a plain Reagent component) has no manifest, so its
  reads are nil and `view-evidence` reports `:compiled-view? false`; no installed
  evidence tool yields an empty-but-versioned `mounted-views`/`explain-render`
  envelope, projected as an empty `:mounted`/`:render` — never a fabricated
  connected instance; a production build nil-gates the whole tier, surfaced as
  `:tier-available? false`. A projection stamped an unexpected
  `:rf.ui.tool/version` is surfaced as `:version-mismatch?` rather than
  mis-parsed — the versioned-degrade contract the tier promises consumers.

  TEST-SCOPED. `re-frame.ui` (`day8/re-frame2-ui`) is in-tree donor code that
  never publishes, so Story's shipped jar must not depend on it; this consumer
  lives under the `:test` alias (which pins the donor tree on the JVM classpath
  by `:local/root` — the top-level `:node-test` build already carries `ui/src`). It is the PROOF the
  tool projections are useful beyond one UI, which is exactly what the bead asks.
  Pure `.cljc` (data → data) so both the JVM `clojure -M:test` gate and the
  node `npm run test:cljs` gate exercise it.

  Anti-over-engineering: NO second runtime model, NO bespoke Story subscription
  ownership, NO new render host — the reads are the framework's own projections;
  Story only shapes + labels them."
  (:require [re-frame.ui.tool :as tool]))

(def consumed-schema-version
  "The `re-frame.ui.tool` evidence-schema version THIS consumer was written to
  parse — a CONSUMER-OWNED literal, deliberately NOT `tool/schema-version`.
  Binding it to the producer's own var makes ANY producer bump silently
  'understood', so an evolved shape would be mis-parsed as the shape we know —
  the version boundary would be nominal. A projection stamped a DIFFERENT
  `:rf.ui.tool/version` is surfaced as `:version-mismatch?` and DEGRADES (every
  shape-dependent field suppressed), never mis-parsed. Currently 3, matching
  `re-frame.ui.tool/schema-version` 3; bump in lockstep only when Story is taught
  the new shape."
  3)

(defn- version-of [projection]
  (:rf.ui.tool/version projection))

;; ---------------------------------------------------------------------------
;; Observation overrides — honest non-ownership (`:owned? false`)
;; ---------------------------------------------------------------------------

(defn observation-overrides
  "Project the variant's resolved `:sub-overrides` map (`{query-v value}`, or
  nil) as Story OBSERVATION targets — one row per exact query vector, each
  labelled `:owned? false`.

  This is HONEST NON-OWNERSHIP: an override does not own the view's real
  subscription. Core lowers a `:sub-overrides` HIT to a `:story-override`
  observation target whose commit acquires a callback-free STATIC handle with
  `:owned? false` (`re-frame.ui.reactive` §override door /
  `re-frame.substrate.observation`); Story neither acquires nor releases the
  view's reactive resource — it observes a pinned value. `nil`/empty overrides
  project `[]`.

  The pinned VALUE is Story-author design data (the variant's pin), NOT an
  app-db read, so it rides verbatim as `:value`. The rows are sorted by the
  `pr-str` of their query vector so the projection is deterministic."
  [sub-overrides]
  (->> (or sub-overrides {})
       (sort-by (comp pr-str key))
       (mapv (fn [[query-v value]]
               {:query  query-v
                :value  value
                :kind   :story-override
                :owned? false}))))

;; ---------------------------------------------------------------------------
;; view-evidence — the Story-facing projection of the tool tier for one view
;; ---------------------------------------------------------------------------

(defn view-evidence
  "The Story view-evidence projection for compiled view `view-id`, with the
  variant's `sub-overrides` (`{query-v value}`, or nil) labelled as non-owning
  observation. A bounded, serializable map:

    {:view-id            <id>
     :rf.ui.tool/version <n|nil>       ; the tier version the reads carry (nil
                                       ;   when the tier is inactive/absent)
     :tier-available?    <bool>        ; false in a production build (the tier
                                       ;   is compile-time nil-gated)
     :version-mismatch?  <bool>        ; a projection stamped a version this
                                       ;   consumer does not know — degrade
     :compiled-view?     <bool>        ; is `view-id` a registered compiled view?
     :manifest           <projection|nil>   ; what CAN happen (pre-mount)
     :dependencies       <projection|nil>   ; declared subscription SITES
     :event-sites        <projection|nil>   ; declared event-handler SITES
     :mounted            [<record> ...]      ; committed CONNECTED-INSTANCE
                                       ;   records for THIS view (S3), possibly []
     :render             [<occurrence> ...]  ; render CAUSES + loss accounting
                                       ;   for THIS view's incarnations, possibly []
     :observation        {:owned? false :overrides [...]}}

  Every read tolerates ABSENCE explicitly (see the ns docstring): an uncompiled
  view → `:compiled-view? false`, nil manifest/dependencies/event-sites; no
  installed evidence tool → empty `:mounted`/`:render` (never a fabricated
  record); a production build → `:tier-available? false` throughout."
  ([view-id] (view-evidence view-id nil))
  ([view-id sub-overrides]
   (let [manifest    (tool/view-manifest view-id)
         deps        (tool/view-dependencies view-id)
         events      (tool/view-event-sites view-id)
         ;; `mounted-views` / `explain-render` are non-nil in ANY dev build
         ;; (empty-but-versioned envelopes) and nil ONLY in production, so they
         ;; are the tier-liveness discriminator manifest-absence cannot give.
         mounted     (tool/mounted-views)
         render      (tool/explain-render view-id)
         version     (some version-of [manifest deps events mounted render])
         mismatch?   (boolean (and version (not= consumed-schema-version version)))
         observation {:owned?    false
                      :overrides (observation-overrides sub-overrides)}]
     (if mismatch?
       ;; A producer stamped a version this consumer was not written against.
       ;; DEGRADE honestly: surface the mismatch + tier liveness, but SUPPRESS
       ;; every shape-dependent parse (manifest / sites / mounted / render). An
       ;; evolved shape may mean something different, so returning it as
       ;; understood would be a mis-parse — the exact hole the version boundary
       ;; exists to close. This is the explicit empty/status form the absence
       ;; path already returns, flagged `:version-mismatch? true`.
       {:view-id            view-id
        :rf.ui.tool/version version
        :tier-available?    (some? mounted)
        :version-mismatch?  true
        :compiled-view?     false
        :manifest           nil
        :dependencies       nil
        :event-sites        nil
        :mounted            []
        :render             []
        :observation        observation}
       (let [rows (filterv #(= view-id (:view-id %)) (:views mounted))]
         {:view-id            view-id
          :rf.ui.tool/version version
          :tier-available?    (some? mounted)
          :version-mismatch?  false
          :compiled-view?     (some? manifest)
          :manifest           manifest
          :dependencies       deps
          :event-sites        events
          :mounted            rows
          :render             (vec (:occurrences render))
          :observation        observation})))))

;; ---------------------------------------------------------------------------
;; JVM structural-tree vocabulary (Tier-1)
;; ---------------------------------------------------------------------------
;;
;; `re-frame.ui.test/render` runs the real compiled view against a real frame
;; on the JVM and returns the versioned public STRUCTURAL TREE (node schema v1).
;; Story projects that tree's VOCABULARY — the node kinds present and the
;; view-boundary view-ids — a pure read over plain node maps, so a Story fixture
;; asserts what the compiled view produced WITHOUT a DOM or a live root (it never
;; acquires/releases an app resource). The `:sub-overrides` render opt seeds the
;; SAME observation door `view-evidence` labels `:owned? false`, so a fixture can
;; prove a pinned value flowed through the tree without Story owning a handle.

(defn- node-kind
  "Discriminate a MAP node per the tree contract's pinned order (mirrors
  `re-frame.ui.test`'s private `node-kind`): `:tag` → :element, else `:view-id`
  → :view-boundary, else `:html` → :html, else `:children` → :fragment. A map
  carrying none of the four is :unknown (a malformed node — surfaced, never
  silently dropped)."
  [m]
  (cond
    (contains? m :tag)      :element
    (contains? m :view-id)  :view-boundary
    (contains? m :html)     :html
    (contains? m :children) :fragment
    :else                   :unknown))

(defn- node-seq
  "Depth-first (document order) seq of the MAP nodes reachable from `n` — the
  node itself, then its `:children` map nodes (text content, a string, is
  skipped: it is content, not a queryable node)."
  [n]
  (when (map? n)
    (cons n (mapcat node-seq (filter map? (:children n))))))

(defn tree-vocabulary
  "Summarise the node VOCABULARY of a `re-frame.ui.test/render` structural tree
  (JVM Tier-1). Pure over the plain node maps:

    {:rf.ui/tree-version <n|nil>       ; the tree ABI version the root stamps
     :node-count         <int>         ; total MAP nodes (text excluded)
     :kinds              {kind N …}     ; count per node kind (:element /
                                       ;   :view-boundary / :html / :fragment)
     :view-ids           #{id …}}       ; the view-boundary view-ids present

  `nil` → an empty summary. So a Story fixture asserts the JVM tree a compiled
  view produced — its structure and the views it mounts — without a DOM."
  [tree]
  (let [nodes (vec (node-seq tree))]
    {:rf.ui/tree-version (:rf.ui/tree-version tree)
     :node-count         (count nodes)
     :kinds              (frequencies (map node-kind nodes))
     :view-ids           (into #{} (keep :view-id) nodes)}))
