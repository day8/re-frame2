(ns re-frame.derivation.node
  "Internal LEAF helper for the five algebra-view tooling siblings (subs /
  flows / routing / machines / resources) — the two scaffold shapes those
  siblings each lower their registrar metadata into:

    - the `:source` source-coordinate projection (Derivations §Errors and
      diagnostics) — `(source-coords meta)` / `(attach-source node meta)`;
    - the fixed-classification `node-base` spine (Derivations §The node
      shape) — `(node-base id output classification)`.

  Before rf2-2mkflj these two shapes were copy-pasted verbatim across the
  five per-feature `tooling.cljc` siblings: the same four-line
  `{:ns :file :line}` → `:source` `cond->` block lived in each, and the
  seven-key classification spine was a helper ONCE in `subs.tooling` but
  hand-inlined in the other four. A change to the `:source` shape (or a new
  spine key) had to be propagated by hand to five places, with no gate to
  catch the drift. This ns is the single place each now lives; the five
  siblings `:require` it.

  SCOPE — this is a LEAF. It carries ONLY the family-agnostic scaffold (the
  source-coord projection + the classification spine that EVERY algebra node
  shares). It deliberately does NOT unify the per-feature `node-for` /
  `declared-inputs` bodies: those are genuinely domain-specific (a schema
  AST vs a state tree vs app-db paths vs route-transition triggers), and a
  single generic algebra-view there would be harmful over-abstraction. Each
  sibling keeps its own domain mapping and decorates the shared spine with
  its own keys (`:refinement` / `:source-form` / `:inputs` / `:owner` /
  `:authority` / `:selectors` / `:commands` / `:spawns?` …) after calling
  `node-base`.

  REQUIRE GRAPH — this leaf lives in `core` (sibling to
  `re-frame.derivation.graph`) and depends on NOTHING feature-specific (it
  reads only the registrar-shaped metadata maps the siblings already hold).
  The four optional siblings (flows / routing / machines / resources) ALL
  already depend on core, so requiring this introduces no new
  artefact-to-artefact edge — exactly the play already used for
  `resources → ssr` (rf2-366u0g) and `trace → projection` (rf2-ih437c).

  BUNDLE ISOLATION — this is pure data construction (no `ex-info`, no trace
  string, no side effect) reached ONLY by the dev-only tooling siblings,
  which are themselves DCE'd from production builds. It therefore needs no
  bundle-isolation sentinel of its own: with no production-reachable caller,
  `:advanced` + `goog.DEBUG=false` drop these bodies wholesale.

  Per [spec/Derivations.md] §The node shape + §Errors and diagnostics and
  the projected Malli shapes in [Spec-Schemas §`:rf/derivation-node`].")

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Shape A — the source-coordinate projection.
;;
;; The registrar stamps `:ns` / `:file` / `:line` source coordinates onto a
;; registration's metadata (Spec 001 §Source-coordinate capture). Every
;; algebra-view sibling projects whichever of those three are present into a
;; single `:source` map (Derivations §Errors and diagnostics). The coords are
;; READ-only off the metadata the sibling already holds; nothing executable is
;; ever serialized.
;; ---------------------------------------------------------------------------

(defn source-coords
  "The `:source` map (Derivations §Errors and diagnostics) projected from a
  registration's metadata `meta`: whichever of `:ns` / `:file` / `:line` are
  present, in that fixed key order. Returns the (possibly empty) coord map —
  callers that gate on presence test `(seq …)`.

  This is the byte-identical four-line `cond->` block the five tooling
  siblings each carried verbatim before rf2-2mkflj. Flows reuses THIS leaf
  inside its frame-match gate (a different-frame same-id flow must not inherit
  the wrong source location), so the projection is exposed separately from
  `attach-source`."
  [meta]
  (cond-> {}
    (contains? meta :ns)   (assoc :ns   (:ns   meta))
    (contains? meta :file) (assoc :file (:file meta))
    (contains? meta :line) (assoc :line (:line meta))))

(defn attach-source
  "Attach the projected `:source` coords (`source-coords`) to `node` when any
  are present — the extract-and-attach idiom shared by the subs / routing /
  machines / resources siblings (flows attaches a frame-matched coord map of
  its own, built from `source-coords`). Returns `node` unchanged when the
  metadata carries no coords."
  [node meta]
  (let [source (source-coords meta)]
    (cond-> node
      (seq source) (assoc :source source))))

;; ---------------------------------------------------------------------------
;; Shape C — the fixed-classification `node-base` spine.
;;
;; Every algebra node shares the same seven-key classification spine
;; (Derivations §The node shape): its fact `:id`, its `:output` address, and
;; the five classification facts `{:kind :storage :evaluation :lifecycle
;; :materialized?}`. Each family supplies its own values for those five (a sub
;; is an ephemeral on-demand `:derivation`; a route is a runtime-db on-route
;; `:process`; …) and then decorates the spine with its domain-specific keys.
;; ---------------------------------------------------------------------------

(defn node-base
  "The fixed seven-key classification spine every algebra node shares
  (Derivations §The node shape): `:id` (the node's canonical fact identity —
  Derivations §Fact identity), `:output` (its output address), and the five
  classification facts carried in `classification`
  (`:kind` / `:storage` / `:evaluation` / `:lifecycle` / `:materialized?`).

  Returns `{:id id :kind … :output output :storage … :evaluation …
  :lifecycle … :materialized? …}`. Callers `assoc` / `cond->` their
  domain-specific keys (`:refinement` / `:source-form` / `:inputs` /
  `:owner` / …) onto the result."
  [id output {:keys [kind storage evaluation lifecycle materialized?]}]
  {:id            id
   :kind          kind
   :output        output
   :storage       storage
   :evaluation    evaluation
   :lifecycle     lifecycle
   :materialized? materialized?})
