(ns re-frame2-pair-mcp.tools.epoch-egress
  "Off-box projection wrap for the pull-mode epoch tools (rf2-6wvh5).

  `trace-window` and `watch-epochs` egress full epoch records — each
  carrying `:db-before` / `:db-after` app-db snapshots (plus
  `:trigger-event` / `:trace-events`) — over the MCP wire. Before
  rf2-6wvh5 the page of records the runtime shipped rode the nREPL wire
  verbatim: the `:epoch-vector` wire-pipeline arm
  (`re-frame2-pair-mcp.tools.wire-pipeline`) only DROPS whole epochs
  STAMPED `:rf.epoch/sensitive?` and then diff-encodes / dedups the rest
  — none of which redact a declared-sensitive SLOT (e.g.
  `[:auth :password]`) sitting inside `:db-before` / `:db-after`. So a
  schema-declared `:sensitive?` slot rode off-box verbatim even with the
  `--allow-sensitive-reads` gate OFF (the published default).

  The framework forbids exactly this hand-walk. `re-frame.core`
  (core.cljc §projected egress, rf2-mrsck) names `projected-record` /
  `projected-history` the single normative off-box-egress emission site
  and states that tools egressing the epoch ring MUST route through it
  rather than walking `(epoch-history frame-id)` and re-wrapping by hand
  — \"the hand-walk is one missed `mapv projected-record` away from
  leaking un-elided data across the process boundary.\" Per Spec
  Security.md §Epoch privacy posture.

  ## What this ns does

  It builds the server-side source that wraps the let-bound page of
  records the tool egresses, routing each record through
  `re-frame.core/projected-record`. The projection runs APP-SIDE inside
  the eval form (where the frame's `[:rf/runtime :elision]` registry is
  reachable, same as the snapshot / get-path / subscribe walkers), so
  the records the MCP server receives already carry `:rf/redacted` /
  `:rf.size/large-elided` markers in their payload slots.

  ## Gate parity with snapshot / get-path / subscribe (rf2-c2dtu)

  The `--allow-sensitive-reads` boot gate (`raw-state/raw-state-allowed?`)
  governs whether a caller's `:include-sensitive true` is honoured. The
  pull-mode epoch tools mirror the gate exactly:

  - Gate OFF (default) — `include-sensitive` is forced false, so
    `project?` is true: every egressed record is routed through
    `projected-record` (off-box defaults — sensitive slots redact, large
    slots elide). A hostile per-call `:include-sensitive true` cannot
    talk an operator who did not pass `--allow-sensitive-reads` into
    shipping raw state.
  - Gate ON + caller opts in (`include-sensitive true`) — `project?` is
    false: records ship raw, exactly as subscribe ships raw on the
    operator's explicit opt-in. This is the operator's deliberate choice
    to see verbatim state.

  `projected-record` is the framework's single off-box-default emission
  site — it carries hard `:include-sensitive? false` / `:include-large?
  false` defaults and handles all four payload-bearing slots
  (`:db-before`, `:db-after`, `:trigger-event`, `:trace-events`)
  including the `:trace-events` per-event re-root (rf2-ta0y7). Using it
  directly (rather than a per-slot `elide-wire-value` hand-walk) keeps
  the projection single-sourced in the framework and removes the
  \"one missed slot\" leak surface the core docstring warns about.")

(defn project-page-src
  "CLJS source that projects a let-bound vector of epoch records for
  off-box egress. `page-sym` is the name of the let-bound page (a CLJS
  symbol or string).

  When `project?` is true (the gate-OFF / not-opted-in posture) the
  emitted source maps each record through
  `re-frame.core/projected-record` — sensitive payload slots land as
  `:rf/redacted`, large slots as `:rf.size/large-elided`. When
  `project?` is false (operator opted in via `--allow-sensitive-reads`
  AND passed `:include-sensitive true`) the page passes through
  verbatim — the raw-egress opt-in, mirroring subscribe's bare-drain
  path.

  `projected-record` returns `nil` for non-map input; the page is a
  vector of epoch-record maps, so the `mapv` is total. The fn is a pure
  data transform with no side effects."
  [page-sym project?]
  (let [p (name page-sym)]
    (if project?
      (str "(mapv re-frame.core/projected-record " p ")")
      p)))
