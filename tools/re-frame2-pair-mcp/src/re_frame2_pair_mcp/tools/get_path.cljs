(ns re-frame2-pair-mcp.tools.get-path
  "Tool: get-path — direct read-by-path against a frame's app-db
   (rf2-tygdv).

  Minimal, focused primitive. The `snapshot` tool is the right surface
  when the agent doesn't know yet which slice carries the answer;
  `get-path` is the right surface when the agent already knows the
  path. Each call is one bencode round-trip; the runtime computes
  `(get-in app-db path)` server-side so only the addressed subtree
  crosses the wire.

  Path vocabulary mirrors `get-in`: a vector of keys / indices.

  ## Batch read — the plural `paths` arg (rf2-lbm21)

  Reading N app-db paths used to need N `get-path` calls (or a
  fallback `eval-cljs`). The `paths` arg (a vector of path vectors)
  reads them all in ONE round-trip: the runtime resolves each path
  server-side and returns `{:ok? true :results {<path> {:exists? bool
  :value <subtree>}}}`. `:path` (singular) and `:paths` (plural) are
  mutually exclusive surfaces on the same tool — passing both is a
  usage error. Each value is elided server-side exactly as the
  singular surface does; the batch reuses the same elision opts and the
  same wire-pipeline `:scalar-value` arm (the whole `:results` map is
  the scalar). Extending the existing tool (rather than minting a new
  `get-paths`) keeps the catalogue, the skill allow-list, and the
  wire-vocab surface unchanged — the agent that knows `get-path` knows
  the batch form for free.

  Post-eval shrink pipeline lives in
  `re-frame2-pair-mcp.tools.wire-pipeline` (rf2-ae8ie). The eval form
  ran `re-frame.core/elide-wire-value` server-side already; the
  `:scalar-value` arm of `run-wire-pipeline` just counts the
  resulting `:rf.size/large-elided` markers."
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.reserved-frame-guard :as guard]))

(defn- elide-call-src
  "CLJS source for `(elide-wire-value <value-sym> ...)` (or a verbatim
  pass-through when elision is off). `value-sym` is the let-bound name
  carrying the resolved value; `path-src` is the source for the `:path`
  slot of the marker handle (a let-bound name or an EDN literal);
  `frame-edn` / `elision-opts` are the merged walker opts."
  [elision? value-sym path-src frame-edn elision-opts]
  (if elision?
    (str "(re-frame.core/elide-wire-value " value-sym
         "  (merge {:path " path-src " :frame " frame-edn "}"
         "         " elision-opts "))")
    value-sym))

(defn- single-path-form
  "Eval form for the singular `:path` read. Calls `snapshot` (full db for
  the frame) then `get-in` with a missing sentinel, so `path-not-found`
  is distinguishable from a path that legitimately points at nil. The
  deepest-valid-prefix loop is inlined so a stale runtime (no helper)
  still answers correctly. Elision (rf2-urjnc) + server-side marker
  count (rf2-e35a5) ride on the `:value` / `:elided-count` slots.

  `walk?` (rf2-t55hxg.13) — whether the per-slot walker fires at all. It
  is NOT `elision?`: a bare `:elision false` still walks (sensitive
  redacts, large passes); only a deliberate full-raw opt-in
  (`:elision false` AND `:include-sensitive true`) skips it."
  [snapshot-call path walk? frame-edn elision-opts]
  (let [elide-call (elide-call-src walk? "v" "path" frame-edn elision-opts)
        count-expr (if walk?
                     (str "(count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                          "               (tree-seq coll? seq elided-v)))")
                     "0")]
    (ef/emit
      (ef/rt-let
        ['db       snapshot-call
         'path     path
         'missing  (ef/rt-raw "#js {}")
         'v        (ef/rt-raw "(get-in db path missing)")
         'elided-v (ef/rt-raw elide-call)
         'n        (ef/rt-raw count-expr)]
        (ef/rt-raw
          (str "(if (identical? v missing)"
               "  {:ok? false :reason :path-not-found"
               "   :path path"
               "   :deepest-valid-prefix"
               "   (loop [acc [] cur db rem path]"
               "     (cond"
               "       (empty? rem) acc"
               "       (and (map? cur) (contains? cur (first rem)))"
               "       (recur (conj acc (first rem)) (get cur (first rem)) (rest rem))"
               "       (and (sequential? cur) (integer? (first rem))"
               "            (<= 0 (first rem) (dec (count cur))))"
               "       (recur (conj acc (first rem)) (nth (vec cur) (first rem)) (rest rem))"
               "       :else acc))}"
               "  {:ok? true :exists? true :path path :value elided-v :elided-count n})"))))))

(defn batch-paths-form
  "Eval form for the plural `:paths` batch read (rf2-lbm21). Resolves
  the frame's db ONCE, then folds over the paths — each gets the same
  `get-in`-with-missing-sentinel + elision treatment as the singular
  form. Returns `{:results {<path> {:exists? bool :value <elided>}}
  :elided-count N}`; the `:results` map preserves the caller's path
  vectors as keys so the agent can correlate hits without re-deriving
  order. The marker `:path` handle is the per-iteration path `p` so a
  drill-down via singular `get-path` lands on the right slot.

  `walk?` (rf2-t55hxg.13) — see `single-path-form`: the walker fires
  unless the caller opted into full-raw egress."
  [snapshot-call paths walk? frame-edn elision-opts]
  (let [elide-call (elide-call-src walk? "raw-v" "p" frame-edn elision-opts)]
    (ef/emit
      (ef/rt-let
        ['db      snapshot-call
         'missing (ef/rt-raw "#js {}")
         'results (ef/rt-raw
                    (str "(reduce"
                         "  (fn [acc p]"
                         "    (let [raw-v (get-in db p missing)]"
                         "      (if (identical? raw-v missing)"
                         "        (assoc acc p {:exists? false :value nil})"
                         "        (assoc acc p {:exists? true :value " elide-call "}))))"
                         "  {} " (pr-str (vec paths)) ")"))]
        (ef/rt-raw
          (str "{:ok? true :results results"
               " :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
               "                              (tree-seq coll? seq results)))}"))))))

(defn get-path-tool [conn raw-args]
  (let [build-id  (wire/arg-build conn raw-args)
        frame     (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        path      (args/parse-path-arg (wire/arg raw-args :path))
        paths     (args/parse-paths-arg (wire/arg raw-args :paths))
        ;; rf2-qef58 — wholesale-read backstop (built once; consulted in
        ;; the `cond` below). nil when the read is allowed.
        refused   (guard/get-path-refusal frame path paths)
        ;; rf2-c2dtu — when the `--allow-sensitive-reads` boot gate is OFF,
        ;; the per-call `:elision false` arg is overridden so the walker
        ;; still fires. rf2-p1qli: single intention-naming predicate
        ;; `raw-state-allowed?` (positive sense — true when operator
        ;; opted in at launch); the gate-off branch forces elision true.
        elision?  (if (raw-state/raw-state-allowed?)
                    (args/parse-bool-arg raw-args :elision)
                    true)
        ;; rf2-vflrg — `:include-sensitive` threads into the walker's
        ;; `:rf.size/include-sensitive?` opt. Off-box default per
        ;; Tool-Pair §`Direct-read privacy posture for sub-cache and
        ;; get-path`: declared-sensitive slots redact unless the caller
        ;; opts in explicitly. The shared `parse-bool-arg` table
        ;; (rf2-c4fmh) gates the default at `false`.
        ;;
        ;; rf2-c2dtu — when the `--allow-sensitive-reads` boot gate is OFF,
        ;; the per-call `:include-sensitive true` arg is dropped before
        ;; reaching the walker.
        incl?     (if (raw-state/raw-state-allowed?)
                    (args/parse-bool-arg raw-args :include-sensitive)
                    false)
        ;; rf2-suoj2 — `elision-opts-edn` takes walker-aligned
        ;; `include-large?` polarity directly. MCP `elision` true =
        ;; emit markers = `:include-large?` false; hence `(not elision?)`.
        elision-opts  (elision/elision-opts-edn (not elision?) incl?)
        ;; rf2-t55hxg.13 — fail-CLOSED: the walker runs UNLESS the caller
        ;; opted into BOTH raw axes (`:elision false` ⇒ include-large? true
        ;; AND `:include-sensitive true` ⇒ incl? true). A bare `:elision
        ;; false` still walks — `elision-opts` overlays include-large? true
        ;; so large passes, but include-sensitive? stays false so a frame-
        ;; declared-sensitive slot still redacts to `:rf/redacted`. Pre-fix
        ;; the walker gated on `elision?` alone, so `:elision false` leaked
        ;; sensitive slots off-box. The `:elision` echo below still reports
        ;; the caller's large-slot intent.
        walk?         (elision/walk-required? (not elision?) incl?)
        snapshot-call (if frame
                        (ef/rt-call 'snapshot frame)
                        (ef/rt-call 'snapshot))
        frame-edn     (if frame
                        (pr-str frame)
                        (ef/emit (ef/rt-call 'current-frame)))
        run-eval
        ;; Shared eval-then-shrink tail: both branches run a server-side
        ;; form, strip the literal value(s) through the wire-pipeline
        ;; `:scalar-value` arm (rf2-6by5s), and rebuild the envelope.
        ;; `pluck` lifts the literal value the pipeline should count;
        ;; `rebuild` reassembles the envelope from the pipelined value.
        (fn [form pluck rebuild]
          (probe/eval-after-runtime-signalled!
            conn build-id form :get-path-failed
            (fn [envelope]
              (let [server-elided (when (:ok? envelope) (:elided-count envelope))
                    {:keys [value indicators]}
                    (wp/run-wire-pipeline
                      (pluck envelope)
                      {:kind :scalar-value
                       :server-elided server-elided})
                    {:keys [elided]} indicators
                    rebuilt (wire/with-indicators
                              (rebuild (dissoc envelope :elided-count) value)
                              {:elided elided})]
                ;; rf2-wdxyx3 finding 2 — a known-tool runtime
                ;; failure (`:ok? false`, e.g. a singular
                ;; `:path-not-found` miss) MUST ride back as an
                ;; `isError` envelope, per the published wire
                ;; contract (spec/001-Wire-Protocol.md, API.md
                ;; §isError) and the dispatch/read-sub
                ;; no-silent-success parity model. An `ok-text`
                ;; wrapper let the failure cache as a normal success
                ;; (cache eligibility keys off `isError`, not
                ;; `:ok?`) and read as green by the MCP host. The
                ;; `:wholesale-read-of-reserved-frame` refusal is
                ;; built upstream as its own `err-text` and never
                ;; reaches this tail.)
                (if (false? (:ok? rebuilt))
                  (wire/err-text rebuilt)
                  (wire/ok-text rebuilt))))))]
    (cond
      ;; rf2-qef58 — server-side backstop: refuse a WHOLESALE root read
      ;; (`path: []`, or a `[]` element in a batch `paths`) of a reserved
      ;; `:rf/*` tool frame (esp. `:rf/xray`) BEFORE the eval round-trip.
      ;; The skill steers (rf2-ihqpn) away from this; the guard enforces
      ;; it so a stray full read can't blow the context window. A sliced
      ;; read (any non-`[]` path) of a tool frame stays allowed.
      (some? refused)
      (js/Promise.resolve refused)

      ;; rf2-lbm21 — `:path` and `:paths` are mutually exclusive surfaces.
      (and (some? path) (some? paths))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :path-and-paths-both-supplied
                        :hint "pass EITHER path (singular, one read) OR paths (plural, batch read), not both"}))

      ;; Batch read (rf2-lbm21): N paths, one round-trip. The whole
      ;; `:results` map is the scalar the pipeline counts elision over.
      (some? paths)
      (if (empty? paths)
        (js/Promise.resolve
          (wire/err-text {:ok? false :reason :empty-paths
                          :hint "usage: get-path {paths '[[:cart :items] [:user :id]]' [frame :rf/default]}"}))
        (run-eval
          (batch-paths-form snapshot-call paths walk? frame-edn elision-opts)
          :results
          (fn [envelope' results]
            (cond-> (assoc envelope' :results results :elision elision?)
              frame (assoc :frame frame)))))

      (nil? path)
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-path
                        :hint "usage: get-path {path '[:cart :items 0 :sku]' [frame :rf/default]} — or paths '[[:a :b] [:c]]' for a batch read"}))

      ;; Singular read (rf2-tygdv): one path, one round-trip.
      ;; rf2-6by5s — `:path-not-found` results carry no `:value` slot, so
      ;; the pipeline runs over nil and reports zero elision; the
      ;; `:value` slot is only re-attached on a hit.
      :else
      (run-eval
        (single-path-form snapshot-call path walk? frame-edn elision-opts)
        (fn [envelope] (when (:ok? envelope) (:value envelope)))
        (fn [envelope' value]
          (let [ok? (:ok? envelope')]
            (cond-> envelope'
              ok?   (assoc :value value :elision elision?)
              frame (assoc :frame frame))))))))
