(ns re-frame2-pair-mcp.tools.snapshot
  "Tool: snapshot — coarse-grained per-frame state read in one round-trip.

  Many investigate-X workflows chain 5-10 reads; each is a bencode
  round-trip plus Claude-think latency. This op composes the existing
  per-slice readers server-side and returns a per-frame map.

  Post-eval shrink pipeline lives in
  `re-frame2-pair-mcp.tools.wire-pipeline`. This tool body
  builds the eval form, awaits the runtime response, and routes the
  result through `run-wire-pipeline` with `:kind :snapshot-map`."
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.reserved-frame-guard :as guard]))

(defn snapshot-tool [conn raw-args]
  (let [build-id    (wire/arg-build conn raw-args)
        frames      (args/parse-frames-arg (wire/arg raw-args :frames))
        include     (args/parse-include-arg (wire/arg raw-args :include))
        ;; The `--allow-sensitive-reads` boot gate forces both
        ;; `:include-sensitive` to false AND `:elision` to true when
        ;; OFF (the default). The per-call args are still parsed (so the
        ;; response envelope reports the effective post-gate value), but
        ;; the gate wins. Single intention-naming predicate
        ;; `raw-state-allowed?` (positive sense — true when operator
        ;; opted in at launch).
        incl?       (if (raw-state/raw-state-allowed?)
                      (args/parse-bool-arg raw-args :include-sensitive)
                      false)
        path        (args/parse-path-arg (wire/arg raw-args :path))
        mode        (dedup/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        ;; Global lazy-summary mode: `:summary` (default)
        ;; replaces every rich slice with a tree-summary marker;
        ;; `:full` ships the full payload. Per-slice override via
        ;; `:modes` map takes precedence over the global mode.
        slice-mode  (args/parse-mode-arg (wire/arg raw-args :mode))
        slice-modes (args/parse-modes-arg (wire/arg raw-args :modes))
        dedup?      (args/parse-bool-arg raw-args :dedup)
        elision?    (if (raw-state/raw-state-allowed?)
                      (args/parse-bool-arg raw-args :elision)
                      true)
        opts        {:frames frames :include include}
        ;; Eval form composition.
        ;; The snapshot composer returns a per-frame map; we wrap each
        ;; frame's `:app-db` AND `:sub-cache` slices with
        ;; `re-frame.core/elide-wire-value` so large / sensitive slots
        ;; get the `:rf.size/large-elided` / `:rf/redacted` marker
        ;; server-side, before the EDN crosses the wire.
        ;;
        ;; The walker reads the `[:rf.runtime/elision]` registry from the
        ;; frame's durable runtime-db partition (EP-0001) — it has to run
        ;; app-side, where the registry is reachable. When elision is
        ;; disabled the eval form skips the walk entirely (a value
        ;; pass-through is cheaper than walking with
        ;; `:rf.size/include-large? true`).
        ;;
        ;; The Tool-Pair §`Direct-read privacy posture
        ;; for sub-cache and get-path` contract: BOTH the `:app-db` and
        ;; `:sub-cache` direct-read surfaces MUST honour
        ;; `:rf.size/include-sensitive?` (default false ⇒ sensitive
        ;; slots redact). The `include-sensitive` MCP arg threads into
        ;; the walker's opt of the same shape via
        ;; `elision-opts-edn`'s two-arity form. Off-box defaults apply.
        ;;
        ;; The eval form ALSO counts elision
        ;; markers server-side and returns `{:value <snap>
        ;; :elided-count N}`. The client-side wire-pipeline reads the
        ;; count from opts instead of re-walking the post-pipeline
        ;; payload — the walker is the only thing that inserts
        ;; markers, so it can hand the count back as a piggyback on
        ;; the same round-trip. Dedup never touches the `:app-db` /
        ;; `:sub-cache` slices (where elision fired) — it only
        ;; re-shapes `:epochs` — so the pre-dedup server count equals
        ;; the post-dedup client count.
        ;; `elision-opts-edn` takes the walker-aligned
        ;; `include-large?` polarity directly (no in-helper inversion).
        ;; MCP arg `elision` true = emit markers = `:include-large?` false,
        ;; hence the local `(not elision?)`.
        elision-opts-form (elision/elision-opts-edn (not elision?) incl?)
        ;; Fail-CLOSED: the per-slot walker over the app-db-
        ;; rooted `:app-db` / `:sub-cache` slices runs UNLESS the caller
        ;; opted into BOTH raw axes (`:elision false` ⇒ `include-large?
        ;; true` AND `:include-sensitive true` ⇒ `incl?`). A gate-ON
        ;; `:elision false` caller who leaves `:include-sensitive` at its
        ;; default must NOT ship raw `:app-db` / `:sub-cache` slices — a
        ;; frame-declared-sensitive slot would leak off-box. A bare
        ;; `:elision false` therefore still walks (large
        ;; passes, sensitive redacts to `:rf/redacted`). The `:epochs`
        ;; projection + `:machines` redaction already gate on the sensitive
        ;; axis (`incl?`) and are unaffected.
        walk-slices?      (elision/walk-required? (not elision?) incl?)
        ;; The `:epochs` slice ships whole `:rf/epoch-record`s
        ;; (each carrying `:db-before` / `:db-after` app-db snapshots),
        ;; NOT bare app-db slices, so it MUST route through
        ;; `re-frame.core/projected-record` — the framework's single
        ;; normative off-box-egress emission site for epoch records — NOT
        ;; the per-slot `elide-wire-value` walker that handles `:app-db` /
        ;; `:sub-cache`. Without the projection the client-side sensitive
        ;; scrub only DROPS whole epochs stamped `:rf.epoch/sensitive? true`;
        ;; it never redacts a schema-declared-sensitive SLOT (e.g.
        ;; `[:auth :token]`) sitting inside a NON-sensitive epoch's
        ;; `:db-before` / `:db-after`, so a sensitive slot would leak
        ;; off-box under the default `--allow-sensitive-reads` OFF posture
        ;; whenever the slice expanded to `:full`. The `:epochs` slice
        ;; ALWAYS projects — same posture trace-window / watch-epochs use.
        ;; `incl?` (the sensitive opt-in) does NOT bypass the projection:
        ;; it threads `{:include-sensitive? true}` INTO `projected-record`
        ;; (app-db sensitive axis only), so the orthogonal fx-args /
        ;; runtime-db / large axes and the app `:redact-fn` stay
        ;; fail-closed. An epoch record never crosses the wire as a raw
        ;; fx-arg / runtime-db payload, gate ON or OFF.
        project-epochs?   true
        ;; EP-0001 (Mike ruling #14) — the `:machines` slice is
        ;; RUNTIME-DB-partition state (machine snapshots live in the
        ;; durable runtime-db partition). Per Spec
        ;; 011 §Off-box redaction the runtime-db partition is REDACTED/OMITTED
        ;; off-box by default. So default-redact the `:machines` slice to the
        ;; framework `:rf/redacted` sentinel unless the operator opted in to
        ;; richer reads (the trusted-local `--allow-sensitive-reads` gate,
        ;; surfaced here as `incl?` — same opt-in axis the `:epochs`
        ;; projection uses). Gate OFF ⇒ `incl?` false ⇒ redact-runtime-db?
        ;; true ⇒ `:machines` egresses as `:rf/redacted`; gate ON +
        ;; `:include-sensitive true` ⇒ the live runtime-db snapshots ship
        ;; (the operator's deliberate opt-in to runtime-db diagnostics).
        redact-runtime-db? (not incl?)
        ;; The whole `walked` reduction is needed when ANY transform fires:
        ;; `:app-db`/`:sub-cache` elision (`walk-slices?`), `:epochs`
        ;; projection (`project-epochs?`), OR `:machines` runtime-db
        ;; redaction (`redact-runtime-db?`). When none fire (gate ON +
        ;; `:elision false` + `:include-sensitive true` — the full raw-egress
        ;; opt-in) the snapshot passes through verbatim.
        walk-snapshot?    (or walk-slices? project-epochs? redact-runtime-db?)
        ;; Source fragment that applies the active per-slot transforms to
        ;; one frame's slice map `fmap`. `walk-slices?` wraps `:app-db` /
        ;; `:sub-cache` through `elide-wire-value`; `project-epochs?` maps
        ;; the `:epochs` vector through `projected-record`;
        ;; `redact-runtime-db?` substitutes the `:machines` runtime-db slice
        ;; with the `:rf/redacted` sentinel. Emitted as a threaded `let` so a
        ;; frame can carry all transforms. The `opts` / `f`
        ;; (`elide-wire-value`) bindings are emitted ONLY in the
        ;; `walk-slices?` branch — `:epochs` projection + `:machines`
        ;; redaction never touch the per-slot walker. A
        ;; gate-ON `:elision false` read STILL walks the slices (sensitive
        ;; redacts, large passes); only a full-raw opt-in (`:elision false`
        ;; AND `:include-sensitive true`) emits NO `elide-wire-value`.
        ;; rf2-mtzv5m — the `:sub-cache` slice is `{query-v {:value v …}}`, so
        ;; walking it WHOLE roots every sub value at the whole-slice root,
        ;; where a route's re-rooted `[:rf.runtime/routing :current …]`
        ;; classification can never match the bare route slice a route read
        ;; sub returns. Walk PER ENTRY instead, threading each entry's
        ;; `query-v` into `elide-wire-value` as `:query-v` so a framework route
        ;; read sub (`:rf/route` / `:rf.route/query` / `:rf.route/params`)
        ;; re-seeds at its storage position (the routing-owned seed table
        ;; `elide-wire-value` consults) and a `:sensitive` route query / param
        ;; redacts — mirroring `list-subscriptions :include-values` /
        ;; `read-sub`. The `:app-db` slice has no per-sub structure, so it
        ;; still walks whole.
        slice-walk-src    (str "(let ["
                               (if walk-slices?
                                 (str " opts (merge {:frame fid} " elision-opts-form ")"
                                      " f    (fn [v] (re-frame.core/elide-wire-value v opts))"
                                      " fmap (if (contains? fmap :app-db)"
                                      "        (update fmap :app-db f) fmap)"
                                      " fmap (if (and (contains? fmap :sub-cache) (map? (:sub-cache fmap)))"
                                      "        (update fmap :sub-cache"
                                      "          (fn [sc] (reduce-kv"
                                      "            (fn [m qv entry]"
                                      "              (assoc m qv"
                                      "                (if (and (map? entry) (contains? entry :value))"
                                      "                  (update entry :value"
                                      "                    (fn [v] (re-frame.core/elide-wire-value v (assoc opts :query-v qv))))"
                                      "                  entry)))"
                                      "            {} sc))) fmap)")
                                 "")
                               (if project-epochs?
                                 (str " fmap (if (contains? fmap :epochs)"
                                      "        (update fmap :epochs"
                                      ;; Thread `incl?` so the
                                      ;; `:include-sensitive` opt-in routes
                                      ;; THROUGH the projection (app-db
                                      ;; sensitive axis only), never around it.
                                      "                (fn [es] " (egress/project-page-src "es" incl?) ")) fmap)")
                                 "")
                               (if redact-runtime-db?
                                 (str " fmap (if (contains? fmap :machines)"
                                      "        (assoc fmap :machines :rf/redacted) fmap)")
                                 "")
                               "] fmap)")
        ;; When the resolved scope is the DEFAULT `:app`
        ;; (reserved `:rf/*` tool frames excluded), piggyback the list of
        ;; tool frames that the scope dropped onto the same round-trip, so
        ;; the response can tell the agent "you can opt into these via
        ;; frames \"all\"". The runtime owns the reserved-frame predicate
        ;; (`reserved-tool-frame?`) — we filter the LIVE
        ;; registry through it app-side rather than guessing server-side.
        ;; Off the `:app` path the slot is an empty vector (no extra cost
        ;; for an explicit-scope read).
        tool-frames-form (if (= :app frames)
                           "(filterv re-frame2-pair.runtime/reserved-tool-frame? (re-frame.core/frame-ids))"
                           "[]")
        form     (if walk-snapshot?
                   (ef/emit
                     (ef/rt-let
                       ['snap (ef/rt-call 'snapshot-state opts)
                        'walked (ef/rt-raw
                                  (str "(reduce-kv"
                                       " (fn [m fid fmap]"
                                       "   (if (map? fmap)"
                                       "     (assoc m fid " slice-walk-src ")"
                                       "     (assoc m fid fmap)))"
                                       " {} snap)"))]
                       (ef/rt-raw
                         (str "{:value walked"
                              " :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                              "                              (tree-seq coll? seq walked)))"
                              " :tool-frames-excluded " tool-frames-form "}"))))
                   (ef/emit
                     (ef/rt-raw
                       (str "{:value "
                            (ef/emit (ef/rt-call 'snapshot-state opts))
                            " :elided-count 0"
                            " :tool-frames-excluded " tool-frames-form "}"))))]
    ;; Server-side backstop: refuse a WHOLESALE (`path: []`
    ;; or `mode :full` + no path) read of a reserved `:rf/*` tool frame
    ;; (esp. `:rf/xray`) BEFORE the eval round-trip. The skill steers
    ;; away from this; the guard enforces it so a stray full
    ;; read can't blow the context window. Sliced reads + the default
    ;; `:app` scope (reserved frames already excluded) pass through.
    (if-let [refused (guard/snapshot-refusal frames path mode)]
      (js/Promise.resolve refused)
      (probe/eval-after-runtime-signalled!
        conn build-id form :snapshot-failed
        (fn [resp]
                 ;; Eval-form shape: `{:value <snap>
                 ;; :elided-count N}`. Defensively fall back to the
                 ;; bare-snap shape — a degraded runtime / stubbed
                 ;; eval might return the raw per-frame map.
                 ;; Recognised via the `:elided-count` marker key,
                 ;; not via `(map? resp)`, because the bare snapshot
                 ;; IS itself a map (`{<frame-id> {...}}`).
                 (let [new-shape?    (and (map? resp) (contains? resp :elided-count))
                       snap-value    (if new-shape? (:value resp) resp)
                       server-elided (when new-shape? (:elided-count resp))
                       ;; The eval form piggybacks the reserved
                       ;; `:rf/*` tool frames the `:app` default dropped (an
                       ;; empty vector off the `:app` path / on the bare-snap
                       ;; fallback shape).
                       excluded-tf   (when new-shape? (:tool-frames-excluded resp))
                       {:keys [value indicators]}
                       (wp/run-wire-pipeline snap-value
                                             {:kind          :snapshot-map
                                              :incl?         incl?
                                              :mode          mode
                                              :dedup?        dedup?
                                              :path          path
                                              :slice-mode    slice-mode
                                              :slice-modes   slice-modes
                                              :server-elided server-elided})
                       {:keys [dropped elided path-status
                               resolved-modes app-db-mode]} indicators
                       response-mode (cond
                                       path                  :path-sliced
                                       (= :full app-db-mode) :full
                                       :else                 :summary)
                       ;; Echo the resolved frame scope.
                       ;; `:app` (the default) returns the APP frames only,
                       ;; with reserved `:rf/*` tool frames excluded; the
                       ;; returned snapshot's keys ARE those app frames, so
                       ;; echo them verbatim under `:frames`. When tool
                       ;; frames exist in the live registry but were
                       ;; excluded by the default scope, surface a note so
                       ;; the agent knows it can opt into them via
                       ;; `frames "all"` (or by naming one explicitly) —
                       ;; this is the friction-cut: the first read never
                       ;; silently overflows on tool-frame state.
                       snap-frames   (vec (keys value))
                       echo-frames   (cond
                                       (= :all frames) :all
                                       (= :app frames) snap-frames
                                       :else           (vec frames))
                       excluded-tool? (and (= :app frames) (seq excluded-tf))]
                   (wire/ok-text (wire/with-indicators
                                   (cond-> {:ok?         true
                                            :frames      echo-frames
                                            :include     include
                                            :mode        response-mode
                                            :slice-modes resolved-modes
                                            :epochs-mode mode
                                            :dedup       dedup?
                                            :elision     elision?
                                            :snapshot    value}
                                     excluded-tool?
                                     (assoc :note (str "Default scope = app frames only; excluded reserved :rf/* "
                                                       "tool frame(s): " (vec excluded-tf) ". Pass frames \"all\" "
                                                       "to include tool-frame state, or name a frame explicitly "
                                                       "(e.g. frames [\":rf/xray\"])."))
                                     path              (assoc :path path)
                                     (seq path-status) (assoc :path-not-found path-status))
                                   {:dropped dropped :elided elided}))))))))
