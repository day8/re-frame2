(ns re-frame-pair2-mcp.tools.wire-pipeline
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
  `with-redacted` walker) lands here once instead of in three places.

  ## Ordering invariant

  The full pipeline runs in this order:

      sensitive-strip
        → path-slice          (snapshot only — :app-db sliced before summary)
        → diff-encode         (epoch records)
        → dedup               (structural sharing across the wire)
        → indicator-count     (count :rf.size/large-elided markers AFTER
                               dedup so the count reflects the elision
                               footprint of the post-shrink payload)
        → summary             (lazy-summary for non-app-db rich slices)

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
  - `:scalar-value`  — single value (`get-path`). The eval form
                       already ran `re-frame.core/elide-wire-value`
                       server-side, so the pipeline here is just
                       indicator-count; the value passes through.

  Each kind returns `{:value v :indicators {:dropped N :elided N
  :path-status M}}`. `:path-status` is the per-frame path-not-found
  map on `:snapshot-map` calls when `:path` was supplied and at
  least one frame's path didn't resolve; empty / absent otherwise.

  Tools call this once and splice the result through
  `wire/with-indicators` onto their envelope — the per-tool tail
  collapses from a 20-line `let` of intermediate names to a 5-line
  pipeline call + envelope assembly."
  (:require [re-frame.mcp-base.elision :as base-elision]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.sensitive :as sensitive]
            [re-frame-pair2-mcp.tools.snapshot-pipeline :as pipeline]))

(defn- run-snapshot-map
  "Full snapshot pipeline. Sequences:

    scrub-sensitive
      → slice-app-db (path-slice or full)
      → diff-encode-epochs
      → dedup-epochs
      → count elision markers (after dedup, before summary)
      → summarise other slices

  Returns `{:value snapshot :indicators {:dropped N :elided N
  :path-status M :resolved-modes M}}`."
  [snapshot {:keys [incl? mode dedup? path slice-mode slice-modes]}]
  (let [app-db-mode           (pipeline/resolve-slice-mode :app-db slice-modes slice-mode)
        [scrubbed dropped]    (sensitive/scrub-snapshot-sensitive snapshot incl?)
        [sliced path-status]  (pipeline/slice-app-db-in-snapshot scrubbed path app-db-mode)
        diff-encoded          (pipeline/diff-encode-epochs-in-snapshot sliced mode)
        deduped               (pipeline/dedup-epochs-in-snapshot diff-encoded dedup?)
        elided                (base-elision/count-elided-markers deduped)
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
        elided         (base-elision/count-elided-markers deduped)]
    {:value      deduped
     :indicators {:dropped dropped
                  :elided  elided
                  :count   (count encoded)}}))

(defn- run-scalar-value
  "Minimal pipeline for `get-path` results. The eval form already ran
  `re-frame.core/elide-wire-value` server-side, so the pipeline here
  is indicator-count only. The value passes through unchanged.

  Returns `{:value v :indicators {:elided N}}`."
  [v _opts]
  {:value      v
   :indicators {:elided (base-elision/count-elided-markers v)}})

(defn run-wire-pipeline
  "Single named pipeline for every MCP tool that returns a tree-typed
  payload. Dispatches on `:kind`; returns `{:value v :indicators M}`.

  Encodes the wire-shrink ordering invariant once — see the namespace
  docstring for the full ordering and per-kind step list.

  Opts:

  - `:kind`         one of `:snapshot-map`, `:epoch-vector`, `:scalar-value`.
  - `:incl?`        sensitive opt-in flag (true ⇒ no drop).
  - `:mode`         epochs-mode keyword (`:diff` / `:full`).
  - `:dedup?`       boolean — run structural dedup at the wire boundary.
  - `:path`         path vector (snapshot path-slicing).
  - `:slice-mode`   global `:summary` / `:full` mode for non-app-db slices.
  - `:slice-modes`  per-slice override map.

  Unknown `:kind` is a programming error — the dispatch is closed
  to four cases. Returns `{:value v :indicators {:elided 0}}` as a
  defensive identity for an unrecognised kind."
  [payload {:keys [kind] :as opts}]
  (case kind
    :snapshot-map (run-snapshot-map payload opts)
    :epoch-vector (run-epoch-vector payload opts)
    :scalar-value (run-scalar-value payload opts)
    {:value payload :indicators {:elided 0}}))
