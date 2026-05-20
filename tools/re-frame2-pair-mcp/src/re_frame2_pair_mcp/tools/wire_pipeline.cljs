(ns re-frame2-pair-mcp.tools.wire-pipeline
  "Named wire-shrink pipeline (rf2-ae8ie). One fn per call-site —
  every tool that returns a payload over the MCP wire routes it
  through `run-wire-pipeline`, parameterised by the payload kind.

  ## Why a named pipeline

  Before this ns each tool body re-derived its own subset of the
  shrink steps inline in its `let` binding. The ordering invariant
  (dedup-BEFORE-summary · elision-count-AFTER-dedup-BEFORE-summary ·
  summary-BEFORE-cap) lived implicitly in `snapshot-tool`'s
  let-sequence; the abbreviated trace-window / watch-epochs / get-path
  pipelines each re-derived their own subset. Three local-obvious
  copies of one rule cost nothing — until the rule changes. A named
  pipeline encodes the invariant once; a future step (say a
  `redact-interceptor` walker) lands here once instead of in three places.

  ## Ordering invariant

  The full pipeline runs in this order:

      sensitive-strip
        → path-slice          (snapshot only — :app-db sliced before summary)
        → diff-encode         (epoch records)
        → dedup               (structural sharing across the wire)
        → indicator-count     (count :rf.size/large-elided markers)
        → summary             (lazy-summary for non-app-db rich slices)
        → source-uri          (rf2-cibp8: splice :rf.source/uri onto
                               every :source-coord map; runs after
                               shrink so no URI build is wasted on
                               summary-replaced subtrees)

  ## Indicator counting (rf2-e35a5)

  When the SERVER-SIDE eval form already counted markers (snapshot +
  get-path, where `elide-wire-value` ran app-side and the count flows
  back via `:server-elided` on the opts map), the pipeline uses the
  pre-shipped count directly — the walker that inserted the marker is
  the only thing that needs to know about it. For these kinds, dedup
  never touches the slice carrying the markers (dedup re-shapes
  `:epochs` only; elision fires on `:app-db` for snapshot and on the
  scalar payload for get-path), so the server-side count is exact.

  When the count was NOT pre-shipped (`:epoch-vector` — runtime drain
  records may already carry markers from upstream
  `event_emit/elide-wire-value`), the arm falls back to walking the
  post-dedup payload.

  Cap is NOT part of the pipeline — it runs at the `invoke` boundary
  on the rendered envelope. Indicator-counting runs INSIDE this ns;
  the envelope-tail splice (`wire/with-indicators`) runs in the tool
  body around the pipeline result.

  ## Payload kinds

  - `:snapshot-map`  — per-frame snapshot map (`snapshot-tool`).
                       Full pipeline including path-slice + summary.
  - `:epoch-vector`  — vector of epoch records (`trace-window`,
                       `watch-epochs`). sensitive-strip → diff-encode
                       → dedup → indicator-count. No summary; the
                       epoch shape is already bounded by the cursor
                       `:limit`.
  - `:scalar-value`  — the literal post-`get-in` value (`get-path`).
                       The eval form already ran
                       `re-frame.core/elide-wire-value` server-side,
                       so the pipeline here is just indicator-count;
                       the value passes through. The CALLER strips
                       the value off its envelope and re-assembles
                       afterwards — the arm walks only what ships.

  Each kind returns `{:value v :indicators {:dropped N :elided N
  :path-status M}}`. `:path-status` is the per-frame path-not-found
  map on `:snapshot-map` calls when `:path` was supplied and at
  least one frame's path didn't resolve; empty / absent otherwise.

  Tools call this once and splice the result through
  `wire/with-indicators` onto their envelope — the per-tool tail
  collapses from a 20-line `let` of intermediate names to a 5-line
  pipeline call + envelope assembly.

  ## Role: orchestrator (first stop on the wire)

  This ns is the FIRST stop for any payload heading to or from the
  nREPL bridge. It is a general orchestrator: it dispatches on
  `:kind` and delegates the per-kind transforms. The orchestrator
  itself owns only the cross-kind invariants (ordering, indicator
  shape, envelope contract); per-kind elaboration lives elsewhere.

  ## Cross-file split (deliberate factoring)

  The `:snapshot-map` arm is intentionally factored out into
  `re-frame2-pair-mcp.tools.snapshot-pipeline`. Path-slicing and
  slice-mode resolution (per-slice `:modes` override · global
  `:mode` arg · the `:path`-forces-`:path-sliced` rule for
  `:app-db`) are markedly more elaborate than the other kinds
  need — `:epoch-vector` and `:scalar-value` never touch app-db
  slices and never resolve a mode. Inlining the snapshot-specific
  machinery here would dwarf the orchestrator with concerns only
  one kind cares about.

  This is a deliberate factoring choice, not accidental drift:
  see `re-frame2-pair-mcp.tools.snapshot-pipeline` for the
  `:snapshot-map` specialisation (path-slicing + slice-mode
  resolution). Readers tracing a `:kind :snapshot-map` payload
  land here first (orchestrator + ordering invariant) and then
  jump to `snapshot-pipeline` for the slice-level transforms."
  (:require [re-frame.mcp-base.elision :as base-elision]
            [re-frame2-pair-mcp.config :as config]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.source-uri :as source-uri]))

(defn- run-snapshot-map
  "Full snapshot pipeline. Sequences:

    scrub-sensitive
      → slice-app-db (path-slice or full)
      → diff-encode-epochs
      → dedup-epochs
      → count elision markers (from `:server-elided` opt; rf2-e35a5)
      → summarise other slices

  Returns `{:value snapshot :indicators {:dropped N :elided N
  :path-status M :resolved-modes M}}`.

  Per rf2-e35a5: `:server-elided` rides in on opts when the
  `snapshot` eval form counted markers app-side. Dedup never
  touches `:app-db` (only `:epochs`), so the server-side count is
  exact post-pipeline. Falls back to a tree-walk when the opt is
  missing (older eval-form shape)."
  [snapshot {:keys [incl? mode dedup? path slice-mode slice-modes server-elided]}]
  (let [app-db-mode           (pipeline/resolve-slice-mode :app-db slice-modes slice-mode)
        [scrubbed dropped]    (sensitive/scrub-snapshot-sensitive snapshot incl?)
        [sliced path-status]  (pipeline/slice-app-db-in-snapshot scrubbed path app-db-mode)
        diff-encoded          (pipeline/diff-encode-epochs-in-snapshot sliced mode)
        deduped               (pipeline/dedup-epochs-in-snapshot diff-encoded dedup?)
        ;; :elided-large counts upstream-pre-elided markers per
        ;; Spec 009 §Indicator field (rf2-8cntr).
        elided                (if (some? server-elided)
                                server-elided
                                (base-elision/count-elided-markers deduped))
        {summarised  :snapshot
         other-modes :resolved-modes} (pipeline/summarise-other-slices-in-snapshot
                                        deduped slice-modes slice-mode)
        resolved-modes        (assoc other-modes :app-db
                                     (cond
                                       path :path-sliced
                                       :else app-db-mode))]
    {:value      summarised
     :indicators {:dropped        dropped
                  :elided         elided
                  :path-status    path-status
                  :resolved-modes resolved-modes
                  :app-db-mode    app-db-mode}}))

(defn- run-epoch-vector
  "Abbreviated pipeline for vectors of epoch records (`trace-window`,
  `watch-epochs`). The cursor `:limit` bounds the input; no summary
  pass — the epoch shape is already bounded.

  Sequences: strip-sensitive → diff-encode → dedup → count.

  Returns `{:value deduped :indicators {:dropped N :elided N
  :count <pre-dedup-encoded-count>}}`. `:count` is the raw
  post-encode count tools surface as the `:count` slot on the
  envelope."
  [epochs {:keys [incl? mode dedup?]}]
  (let [[kept dropped] (sensitive/strip-sensitive epochs incl?)
        encoded        (dedup/diff-encode-epochs kept mode)
        deduped        (dedup/dedup-value encoded dedup?)
        ;; :elided-large counts upstream-pre-elided markers per
        ;; Spec 009 §Indicator field (rf2-8cntr) — shared by
        ;; `trace-window` and `watch-epochs`.
        elided         (base-elision/count-elided-markers deduped)]
    {:value      deduped
     :indicators {:dropped dropped
                  :elided  elided
                  :count   (count encoded)}}))

(defn- run-scalar-value
  "Minimal pipeline for the literal post-`get-in` value. The eval form
  already ran `re-frame.core/elide-wire-value` server-side, so the
  pipeline here is indicator-count only. The value passes through
  unchanged.

  The contract is the SCALAR — the value the caller intends to ship
  on the wire — NOT the envelope around it. `get-path` strips the
  `:value` slot off its result map, runs it through here, and
  rebuilds the envelope. That way the indicator count reflects only
  what's actually being shipped — a `:path-not-found` envelope (no
  `:value` slot) bypasses the walk entirely.

  Per rf2-e35a5 the count is sourced from `:server-elided` on opts
  when the eval form counted markers app-side (the common path).
  Falls back to a local walk only when the opt is missing — a
  defensive seam for tests or a degraded eval-form shape.

  Returns `{:value v :indicators {:elided N}}`."
  [v {:keys [server-elided]}]
  ;; :elided-large counts upstream-pre-elided markers per
  ;; Spec 009 §Indicator field (rf2-8cntr).
  {:value      v
   :indicators {:elided (if (some? server-elided)
                          server-elided
                          (base-elision/count-elided-markers v))}})

(defn run-wire-pipeline
  "Single named pipeline for every MCP tool that returns a tree-typed
  payload. Dispatches on `:kind`; returns `{:value v :indicators M}`.

  Encodes the wire-shrink ordering invariant once — see the namespace
  docstring for the full ordering and per-kind step list.

  Opts:

  - `:kind`           one of `:snapshot-map`, `:epoch-vector`, `:scalar-value`.
  - `:incl?`          sensitive opt-in flag (true ⇒ no drop).
  - `:mode`           epochs-mode keyword (`:diff` / `:full`).
  - `:dedup?`         boolean — run structural dedup at the wire boundary.
  - `:path`           path vector (snapshot path-slicing).
  - `:slice-mode`     global `:summary` / `:full` mode for non-app-db slices.
  - `:slice-modes`    per-slice override map.
  - `:server-elided`  integer count of `:rf.size/large-elided` markers
                      inserted server-side (rf2-e35a5). When present,
                      the `:snapshot-map` and `:scalar-value` arms use
                      this instead of re-walking the payload. Missing
                      ⇒ falls back to a local walk.

  Unknown `:kind` throws — the dispatch is closed to three cases and
  silently degrading would mask a programmer typo / a new-payload
  contributor who forgot to register the arm. The post-eval shrink
  pipeline is a fixed surface; a dynamic-dispatch fallback has no
  legitimate use.

  After the per-kind arm returns, the source-URI decorator (rf2-cibp8)
  splices `:rf.source/uri` onto every `:source-coord`-bearing map in
  the result. Runs last so the decoration walks only the
  post-shrink tree — summary-replaced slices ship as `{:rf.mcp/summary
  ...}` markers (no `:source-coord` inside), so the walk is short."
  [payload {:keys [kind] :as opts}]
  (let [{:keys [value] :as out}
        (case kind
          :snapshot-map (run-snapshot-map payload opts)
          :epoch-vector (run-epoch-vector payload opts)
          :scalar-value (run-scalar-value payload opts)
          (throw (ex-info "run-wire-pipeline: unknown :kind"
                          {:kind  kind
                           :valid #{:snapshot-map :epoch-vector :scalar-value}})))]
    (assoc out :value (source-uri/decorate value (config/get-editor)))))
