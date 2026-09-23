(ns re-frame2-pair-mcp.tools.wire-pipeline
  "Named wire-shrink pipeline. One fn per call-site —
  every tool that returns a payload over the MCP wire routes it
  through `run-wire-pipeline`, parameterised by the payload kind.

  ## Why a named pipeline

  Without it each tool body would re-derive its own subset of the
  shrink steps inline in its `let` binding, and the ordering invariant
  (dedup-BEFORE-summary · elision-count-over-what-SHIPS ·
  summary-BEFORE-cap) would live implicitly across `snapshot-tool`'s
  let-sequence and the abbreviated trace-window / watch-epochs /
  get-path pipelines — several local-obvious copies of one rule, which
  cost nothing until the rule changes. A named pipeline encodes the
  invariant once; a future step (say a `redact-interceptor` walker)
  lands here once instead of in three places.

  ## Ordering invariant

  The full pipeline runs in this order:

      sensitive-strip
        → path-slice          (snapshot only — :app-db sliced before summary)
        → diff-encode         (epoch records)
        → dedup               (structural sharing across the wire)
        → summary             (lazy-summary for non-app-db rich slices)
        → indicator-count     (count the :rf.size/large-elided markers
                               that ship; a deduped slice is counted
                               pre-dedup, since dedup pools markers)
        → source-uri          (splice :rf.mcp/source-uri onto
                               every :source-coord map; runs after
                               shrink so no URI build is wasted on
                               summary-replaced subtrees)

  ## Indicator counting

  `:elided-large` counts the markers in the payload that SHIPS (Spec
  009 §Indicator field). For `get-path` (`:scalar-value`) the eval form
  counts app-side over exactly the value it returns, and that count
  flows back via `:server-elided` on the opts map.

  `:snapshot-map` does NOT take the app-side count (rf2-3x7nj.32.8): the
  eval form walks every frame's full `:app-db`, every `:sub-cache` entry
  and every epoch, and the path slice and the summary pass then remove
  markers, so that figure over-reported for a summary or a path-sliced
  read. The arm counts over its own post-summary result instead, with a
  `:full` `:epochs` slice counted before dedup.

  `:epoch-vector` walks its post-encode, pre-dedup payload (runtime
  drain records may already carry markers from upstream
  `event_emit/elide-wire-value`, and dedup pools identical markers).

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
                       `re-frame.core/project-egress` server-side,
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
  (:require [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]
            [re-frame.mcp-base.elision :as rf.mcp-base.elision]
            [re-frame2-pair-mcp.config :as config]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.source-uri :as source-uri]))

(defn- count-shipped-markers
  "Count the `:rf.size/large-elided` markers the snapshot response
  actually SHIPS (rf2-3x7nj.32.8). `summarised` is the post-summary
  snapshot: a path-sliced `:app-db` holds only the addressed subtree and
  a summary-mode slice is a `{:rf.mcp/summary ...}` marker holding no
  value at all, so a marker outside the path or inside a summarised
  slice is not counted. A `:full` `:epochs` slice is counted over its
  PRE-dedup form (`pre-dedup`), because dedup pools identical markers
  into one cache entry — the same rule the `:epoch-vector` arm follows."
  [summarised pre-dedup epochs-full?]
  (if-not (map? summarised)
    (rf.mcp-base.elision/count-elided-markers summarised)
    (reduce-kv
      (fn [n fid fmap]
        (+ n (rf.mcp-base.elision/count-elided-markers
               (if (and epochs-full? (map? fmap) (contains? fmap :epochs))
                 (assoc fmap :epochs (get-in pre-dedup [fid :epochs]))
                 fmap))))
      0 summarised)))

(defn- run-snapshot-map
  "Full snapshot pipeline. Sequences:

    scrub-sensitive
      → slice-app-db (path-slice or full)
      → diff-encode-epochs
      → dedup-epochs
      → summarise other slices
      → count elision markers over what ships

  Returns `{:value snapshot :indicators {:dropped N :elided N
  :path-status M :resolved-modes M}}`.

  `:elided-large` counts the markers ENCOUNTERED in the response
  payload (Spec 009 §Indicator field), so it is counted AFTER the path
  slice and the summary pass, both of which remove markers
  (rf2-3x7nj.32.8). The eval form's app-side `:elided-count` is taken
  over the whole walked state — every frame's full `:app-db`, every
  `:sub-cache` entry, every epoch — so it is NOT used here: it reported
  markers a summary or a path-sliced read never contained."
  [snapshot {:keys [incl? mode dedup? path slice-mode slice-modes]}]
  (let [app-db-mode           (pipeline/resolve-slice-mode :app-db slice-modes slice-mode)
        [scrubbed dropped]    (sensitive/scrub-snapshot-sensitive snapshot incl?)
        [sliced path-status]  (pipeline/slice-app-db-in-snapshot scrubbed path app-db-mode)
        ;; Cap the full-mode `:epochs` slice to the most-recent
        ;; N records BEFORE diff-encode + dedup so those operate on the
        ;; bounded set. Only fires when `:epochs` resolves to `:full`
        ;; (the verbatim-`:db-before`/`:db-after` overflow path); a no-op
        ;; in summary / diff. Keeps the slice usable instead of letting
        ;; an unbounded 50-record history trip the global wire-cap and
        ;; replace the WHOLE snapshot with an overflow marker.
        capped                (pipeline/cap-full-epochs-in-snapshot
                                sliced slice-modes slice-mode pipeline/full-epochs-cap)
        diff-encoded          (pipeline/diff-encode-epochs-in-snapshot capped mode)
        deduped               (pipeline/dedup-epochs-in-snapshot diff-encoded dedup?)
        {summarised  :snapshot
         other-modes :resolved-modes} (pipeline/summarise-other-slices-in-snapshot
                                        deduped slice-modes slice-mode)
        ;; :elided-large counts the markers in the payload that SHIPS,
        ;; per Spec 009 §Indicator field (rf2-3x7nj.32.8).
        elided                (count-shipped-markers
                                summarised diff-encoded
                                (= :full (get other-modes :epochs)))
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
        encoded        (rf.mcp-base.diff-encode/diff-encode-epochs kept mode)
        deduped        (rf.mcp-base.dedup/dedup-value encoded dedup?)
        ;; :elided-large counts upstream-pre-elided markers per
        ;; Spec 009 §Indicator field — shared by
        ;; `trace-window` and `watch-epochs`.
        ;;
        ;; Count the markers over the PRE-dedup payload
        ;; (`encoded`), not `deduped`. `dedup-value` pools N identical
        ;; `:rf.size/large-elided` maps into ONE structural-sharing
        ;; cache entry, so walking `deduped` undercounts (1 instead of
        ;; N). The marker SET is identical pre/post dedup — dedup only
        ;; re-shapes structural references; it never drops a marker —
        ;; so counting pre-dedup is exact and the markers still ride the
        ;; wire intact. Mirrors the `:snapshot-map` arm, which already
        ;; sidesteps this by using the server `:server-elided` count
        ;; taken before dedup.
        elided         (rf.mcp-base.elision/count-elided-markers encoded)]
    {:value      deduped
     :indicators {:dropped dropped
                  :elided  elided
                  :count   (count encoded)}}))

(defn- run-scalar-value
  "Minimal pipeline for the literal post-`get-in` value. The eval form
  already ran `re-frame.core/project-egress` server-side, so the
  pipeline here is indicator-count only. The value passes through
  unchanged.

  The contract is the SCALAR — the value the caller intends to ship
  on the wire — NOT the envelope around it. `get-path` strips the
  `:value` slot off its result map, runs it through here, and
  rebuilds the envelope. That way the indicator count reflects only
  what's actually being shipped — a `:path-not-found` envelope (no
  `:value` slot) bypasses the walk entirely.

  The count is sourced from `:server-elided` on opts
  when the eval form counted markers app-side (the common path).
  Falls back to a local walk only when the opt is missing — a
  defensive seam for tests or a degraded eval-form shape.

  Returns `{:value v :indicators {:elided N}}`."
  [v {:keys [server-elided]}]
  ;; :elided-large counts upstream-pre-elided markers per
  ;; Spec 009 §Indicator field.
  {:value      v
   :indicators {:elided (if (some? server-elided)
                          server-elided
                          (rf.mcp-base.elision/count-elided-markers v))}})

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
                      inserted server-side. When present, the
                      `:scalar-value` arm uses this instead of
                      re-walking the payload; missing ⇒ a local walk.
                      The `:snapshot-map` arm ignores it and counts what
                      it ships (rf2-3x7nj.32.8).

  Unknown `:kind` throws — the dispatch is closed to three cases and
  silently degrading would mask a programmer typo / a new-payload
  contributor who forgot to register the arm. The post-eval shrink
  pipeline is a fixed surface; a dynamic-dispatch fallback has no
  legitimate use.

  After the per-kind arm returns, the source-URI decorator
  splices `:rf.mcp/source-uri` onto every `:source-coord`-bearing map in
  the result. Runs last so the decoration walks only the
  post-shrink tree — summary-replaced slices ship as `{:rf.mcp/summary
  ...}` markers (no `:source-coord` inside), so the walk is short."
  [payload {:keys [kind] :as opts}]
  (let [{:keys [value] :as out}
        (case kind
          :snapshot-map (run-snapshot-map payload opts)
          :epoch-vector (run-epoch-vector payload opts)
          :scalar-value (run-scalar-value payload opts)
          ;; Canonical thrown-error shape per Spec 009 §The thrown-error shape:
          ;; the human sentence + a trailing `[:rf.error/<id>]` token IS the
          ;; ex-message. Load-bearing here (rf2-jquiy), but via RELAY 2, not
          ;; relay 1: all five `run-wire-pipeline` call sites sit inside an
          ;; `eval-after-runtime!` `on-value`, so this throw fires during
          ;; response shaping and meets `probe/err->result` — NOT
          ;; `server.cljs`'s `invoke-and-guard`, which only catches throws
          ;; raised before the nREPL round-trip. The two relays keep opposite
          ;; halves of the exception; `err->result` merges both since
          ;; rf2-6tzm5, which is what makes this message reach the agent at
          ;; all (before it, only the ex-data below did, and this text was
          ;; dead prose). The ex-data therefore carries the agent's branchable
          ;; slots — `:rf.error/id`, `:kind`, `:valid` — and the message
          ;; carries the sentence; `error_boundary_test` pins both.
          ;; Hand-rolled inline: `tools/` MUST NOT `:require re-frame.*`.
          (throw (ex-info (str "run-wire-pipeline got an unknown :kind "
                               (pr-str kind)
                               " — expected one of :snapshot-map, :epoch-vector, "
                               ":scalar-value"
                               " [" :rf.error/pair-mcp-unknown-wire-pipeline-kind "]")
                          {:rf.error/id :rf.error/pair-mcp-unknown-wire-pipeline-kind
                           :where    're-frame2-pair-mcp/run-wire-pipeline
                           :recovery :no-recovery
                           :reason   (str "run-wire-pipeline got an unknown :kind " (pr-str kind))
                           :kind     kind
                           :valid    #{:snapshot-map :epoch-vector :scalar-value}})))]
    (assoc out :value (source-uri/decorate value (config/get-editor)))))
