(ns re-frame.mcp-base.descriptor-manifest
  "Shared MCP tool-descriptor manifest generator + drift-check, a
  companion to the API-governance keystone.

  THE PROBLEM. Each MCP server has ONE truth — its tool registry (the
  ordered `tools` / `tool-registry` vector that bundles every tool's
  name, descriptor, handler, …). The `tools/list` wire surface is a
  projection of that registry; so are the hand-maintained
  `test/fixtures/tool-names.json` lists that the Node stdio-roundtrip
  and the cross-server conformance harness compare against. Projections
  drift: a tool added / removed / renamed in the registry silently
  diverges from the committed projection until a same-language unit
  test (if one exists) catches it. A generated, CI-guarded MANIFEST
  prevents the drift the way the keystone's `spec/api-manifest.edn`
  prevents public-API drift.

  THE ARTEFACT. Each server commits a `tool-descriptors.edn` manifest —
  the deterministic, byte-stable projection of its registry's descriptor
  set. One row per tool:

      {:name             \"<wire-name>\"
       :description      \"<one-line semantics>\"
       :input-keys       [<sorted inputSchema property keys, as strings>]
       :gated-input-keys [<sorted subset of :input-keys the default profile gates off>]
       :required         [<sorted inputSchema :required keys, as strings>]
       :output?          <bool — does the tool declare an outputSchema?>
       :annotations      [<sorted annotation-hint keys, as strings>]
       :typicalTokens    <int response-payload token hint, or nil>}

  WHY THIS SHAPE (not the whole descriptor). The full descriptor maps
  carry deeply-nested JSON-Schema fragments whose exact serialisation
  (key order inside nested property maps, optional `:description` prose)
  is volatile and not the contract this gate guards. The gate's job is
  the CATALOGUE shape: which tools exist, what they're called, the
  top-level input-property surface, which of those inputs are REQUIRED
  vs optional, whether each declares structured output + annotation
  hints, and the response-size budget hint AI clients read to pick
  size-conscious args. That is precisely the surface that goes stale
  when a tool is added / removed / renamed, its input surface changes,
  an argument silently flips required↔optional, or a token-budget hint
  drifts — the drift this gate targets. The full descriptor's wire
  validity is already pinned by the MCP-SDK conformance harness
  (`tools/mcp-conformance/`); this manifest pins the catalogue identity.

  REQUIRED + TYPICALTOKENS. `:required` and `:typicalTokens`
  are part of the projection so the gate guards the live API-semantics
  facets alongside `:input-keys` / `:output?` / `:annotations`: without
  them a tool could silently move an argument between required and
  optional, or drift its token-budget hint, without tripping drift.
  `:required` is
  the SORTED subset of input keys the descriptor's `:inputSchema
  :required` marks mandatory (empty when none); `:typicalTokens` is the
  integer ballpark of the response payload size (nil when a tool
  declares no hint — forward-compatible, the slot still renders so the
  row shape is uniform). Both are read from the RAW registry descriptor
  exactly like the other facets. The `:required`-subset-of-`:input-keys`
  relation is ENFORCED by `descriptor->row` (it throws on a descriptor
  whose required keys are absent from its properties) — nothing in the
  raw shape forces it, since the two slots are read from independent
  descriptor sources.

  WIRE-SPLICED INPUTS. re-frame2-pair-mcp splices the
  universal `max-tokens` (and, for read tools, `cache`) knobs onto every
  descriptor's `:inputSchema :properties` at `tools/list` time, AFTER
  the raw registry descriptor (`tools/descriptors.cljs` —
  `with-budget-knob` / `with-cache-knob`). Those splices are
  DETERMINISTIC — they depend only on the descriptor + the static
  `registry/cacheable?` predicate, not on any operator flag — so the
  pair-mcp generator applies them BEFORE projecting, and the manifest's
  `:input-keys` reflect the ACTUAL `tools/list` input surface rather
  than a raw shape that hides the universal knobs. (story-mcp bakes its
  `max-tokens` knob into each descriptor at def-time via
  `schemas/with-max-tokens`, so its raw descriptor already carries it —
  no generator-side splice needed.)

  GATED INPUTS. A descriptor input key can be present on a
  PRIVILEGED `tools/list` profile yet stripped from the DEFAULT one by an
  operator-only gate. story-mcp's `:include-sensitive` knob is the live
  case: six value-surfacing tools bake it into their static descriptor,
  but `registry/tool-descriptors` strips it from the default wire surface
  whenever the `--allow-sensitive-reads` gate is closed (the closed-by-
  default posture). Projecting the raw descriptor would put
  `:include-sensitive` in `:input-keys`, so the governance artefact would
  advertise an input the DEFAULT surface deliberately hides — the
  manifest disagreeing with the surface it claims to guard.

  The row models that explicitly with `:gated-input-keys` — the SORTED
  subset of `:input-keys` that the default profile gates OFF. `:input-keys`
  stays the FULL union (every input that can appear on any supported
  deterministic profile), so no surface is lost; `:gated-input-keys` names
  which of those are hidden by default. The default `tools/list` surface
  is therefore `(:input-keys row)` minus `(:gated-input-keys row)`, and the
  privileged/gate-open surface is `:input-keys` verbatim — both derivable
  from one byte-stable row. A tool that gates no input (every pair-mcp
  tool; story-mcp's non-sensitive tools) carries `:gated-input-keys []`,
  so the slot renders uniformly on every row like `:required`. The set of
  gated keys is config-INDEPENDENT (it is the static slot set the server
  KNOWS it gates, not a read of the live gate atom), so the manifest stays
  deterministic. Generators pass the gated-key set as the optional 3rd arg
  to `build-manifest` (story-mcp passes `#{\"include-sensitive\"}`; pair-mcp
  passes nothing → the empty default). A drift in which inputs are gated —
  a tool newly gating an input, or a gate lifted — trips the gate.

  DRIFT-CHECK. `check` regenerates the manifest in memory from the live
  registry and compares it to the committed file (LF-normalised). Any
  difference — a tool added, removed, or renamed in the registry; a
  changed input-key surface; a changed gated-input subset; an argument
  flipped required↔optional; a flipped output?/annotations flag; a
  drifted token-budget hint — fails.
  This is the PRIMARY drift-guard: it goes red in CI until the manifest
  is regenerated. Adding / removing / renaming an MCP tool then goes RED
  until `... --check`'s sibling generator is re-run.

  CLJC — ONE serialiser, two platforms. story-mcp's registry is
  JVM-loadable (`.cljc`); re-frame2-pair-mcp's is ClojureScript-only
  (a Node script). Each server runs its OWN generator on its OWN
  platform (it must — only there can it `require` its registry), but
  BOTH route through the `render-edn` / `check` here so the committed
  EDN is byte-identical regardless of which platform produced it. The
  rendering is LF-pinned for the same reason the keystone's is: a
  Windows author and a Linux CI must agree byte-for-byte."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---------------------------------------------------------------------------
;; Row projection — registry descriptor → catalogue row.
;; ---------------------------------------------------------------------------

(defn- sorted-string-keys
  "Sorted vector of a map's keys rendered as strings. `nil`/empty → []."
  [m]
  (->> (keys m)
       (map name)
       sort
       vec))

(defn- sorted-string-vec
  "Sorted vector of a seq of values rendered as strings. `nil`/empty → [].
  Used for `:inputSchema :required`, whose entries are already strings on
  both servers, but `name` is applied defensively so a keyword-shaped
  entry (a future generator authoring a `:required` as keywords) still
  renders byte-stably as a string — same coercion `sorted-string-keys`
  applies to the property keys."
  [xs]
  (->> xs
       (map name)
       sort
       vec))

(defn descriptor->row
  "Project one registry descriptor map into the stable manifest row.

  Accepts the descriptor in its on-the-registry shape: a map with
  `:name`, `:description`, `:inputSchema` (with `:properties` +
  optional `:required`), optional `:outputSchema`, optional
  `:annotations`, optional `:typicalTokens`. Both servers' descriptors
  carry these slots (story-mcp lifts :outputSchema + :annotations via
  cond->; pair-mcp declares them per-tool).

  `:required` is the SORTED subset of input keys the
  descriptor marks mandatory via `:inputSchema :required` — empty when
  the tool declares none. Guarding it surfaces an argument silently
  flipping required↔optional.

  `:typicalTokens` is the integer response-payload token
  hint, passed through verbatim (nil when a tool declares no hint —
  forward-compatible; the slot still renders so every row shares one
  shape). Guarding it surfaces a token-budget hint drifting.

  `:gated-input-keys` is the SORTED subset of `:input-keys`
  the DEFAULT `tools/list` profile gates off behind an operator-only gate
  (the intersection of the descriptor's actual input keys with the
  caller-supplied `gated-keys` set). The default surface is therefore
  `:input-keys` minus `:gated-input-keys`; the privileged/gate-open
  surface is `:input-keys` verbatim. Empty when the tool gates no input
  (the slot still renders so every row shares one shape). The 1-arity
  form gates nothing (`gated-keys` defaults to `#{}`) — the
  config-independent shape pair-mcp and the base tests use.

  `gated-keys` is a SET of input-key strings the server KNOWS it gates
  (e.g. story-mcp's `#{\"include-sensitive\"}`) — config-independent (the
  static slot set, not a read of the live gate atom), so the projection
  stays deterministic + byte-stable.

  ## Required-subset invariant

  The row contract (this ns's spec) states `:required` is a SORTED
  SUBSET of `:input-keys`. The two slots are read from INDEPENDENT
  descriptor sources — `:input-keys` from `:inputSchema :properties`,
  `:required` from `:inputSchema :required` — so nothing in the raw
  shape forces the subset relation. A descriptor that names a required
  argument absent from its advertised properties would emit an
  inconsistent row (`:input-keys [\"known\"]` with `:required
  [\"missing\"]`), blessing a mandatory argument the input surface never
  declares and breaking the default/gate-open surface derivation
  downstream. This fn ENFORCES the invariant: a descriptor whose
  required keys are not a subset of its property keys is REJECTED with an
  `ex-info` naming the offending tool + the missing keys, rather than
  emitting a self-inconsistent row."
  ([descriptor] (descriptor->row descriptor #{}))
  ([{:keys [name description inputSchema outputSchema annotations typicalTokens]} gated-keys]
   (let [input-keys (sorted-string-keys (:properties inputSchema))
         required   (sorted-string-vec (:required inputSchema))
         missing    (vec (sort (set/difference (set required) (set input-keys))))
         gated      (set (or gated-keys #{}))]
     (when (seq missing)
       (throw (ex-info (str "descriptor->row: required keys not a subset of input keys for tool "
                            (pr-str name) " — missing from :inputSchema :properties: " (pr-str missing))
                       {:tool name :required required :input-keys input-keys :missing missing})))
     {:name             name
      :description      description
      :input-keys       input-keys
      :gated-input-keys (filterv gated input-keys)
      :required         required
      :output?          (some? outputSchema)
      :annotations      (sorted-string-keys annotations)
      :typicalTokens    typicalTokens})))

(defn build-rows
  "Project a SEQ of registry descriptor maps into the sorted vector of
  manifest rows. Rows are sorted by `:name` so the manifest order is
  insensitive to registry authoring order (catalogue IDENTITY, not
  ordering, is what this gate guards; the wire-surface ordering is
  pinned separately by the per-server order-sensitive tests).

  The optional `gated-keys` set names the input keys the
  server's DEFAULT `tools/list` profile gates off (e.g. story-mcp's
  `#{\"include-sensitive\"}`); it is threaded to every row's
  `descriptor->row` so each `:gated-input-keys` is the per-tool
  intersection. Omit it (or pass `#{}`) when the server gates no input —
  the config-independent default pair-mcp uses."
  ([descriptors] (build-rows descriptors #{}))
  ([descriptors gated-keys]
   (->> descriptors
        (map #(descriptor->row % gated-keys))
        (sort-by :name)
        vec)))

;; ---------------------------------------------------------------------------
;; Deterministic EDN emission — byte-stable, LF-pinned, diff-friendly.
;; ---------------------------------------------------------------------------

(defn- render-scalar
  "pr-str a scalar (string / boolean) the same way on JVM + CLJS.
  Strings + booleans round-trip identically through `pr-str` on both
  platforms; this indirection keeps the call-site intent explicit."
  [v]
  (pr-str v))

(defn- render-string-vec
  "Render a vector of strings as `[\"a\" \"b\"]` deterministically —
  `pr-str` of a vector is element-order-stable and identical across
  platforms for string elements."
  [v]
  (pr-str v))

(defn- normalise-lf
  "Normalise CRLF line endings to bare LF so manifest text is
  byte-identical on Windows and Linux. Used both at emission
  (`render-edn`) and at comparison (`check`) so a CRLF working-tree
  checkout never trips a spurious drift."
  [s]
  (str/replace s "\r\n" "\n"))

(defn- render-row
  "Render one manifest row as a single-line EDN map with the canonical
  key order. One row per line keeps git diffs surgical — adding a tool
  is a one-line diff.

  `:typicalTokens` renders via `pr-str` (an integer, or `nil` when the
  tool declares no hint — `pr-str` of nil is the literal `nil`, which
  round-trips back to nil through `edn/read-string` in `check`)."
  [row]
  (str "{:name " (render-scalar (:name row))
       " :description " (render-scalar (:description row))
       " :input-keys " (render-string-vec (:input-keys row))
       " :gated-input-keys " (render-string-vec (:gated-input-keys row))
       " :required " (render-string-vec (:required row))
       " :output? " (render-scalar (:output? row))
       " :annotations " (render-string-vec (:annotations row))
       " :typicalTokens " (pr-str (:typicalTokens row))
       "}"))

(defn render-edn
  "Render the manifest (a `{:meta ... :tools [rows]}` map) to a
  deterministic EDN string. One tool row per line.

  `meta` is a small provenance map (`:server`, `:tool-count`, a
  generated-by doc) the caller supplies; it is rendered with a stable
  key order via an explicit array-map so it does not depend on
  platform map iteration order.

  Line endings are normalised to bare `\\n` so the output is
  byte-identical on Windows and Linux — the committed file is
  `.gitattributes`-pinned to LF for the same reason. This mirrors the
  keystone generator's LF discipline exactly."
  [{:keys [meta tools]}]
  (let [{:keys [server tool-count]} meta
        raw (str ";; GENERATED MCP tool-descriptor manifest — do NOT hand-edit.\n"
                 ";; Regenerate from the live tool registry (see the server's\n"
                 ";; descriptor-manifest generator). Drift-checked in CI: adding /\n"
                 ";; removing / renaming a tool turns the gate red until this is\n"
                 ";; regenerated. Shared serialiser: re-frame.mcp-base.descriptor-manifest.\n"
                 ";; Keystone rf2-3nbl5.2; this manifest rf2-sofwv.\n"
                 "{:meta "
                 (pr-str (array-map :server server
                                    :tool-count tool-count))
                 "\n :tools\n ["
                 (str/join "\n  " (map render-row tools))
                 "]}\n")]
    (normalise-lf raw)))

(defn build-manifest
  "Build the full manifest data structure from a seq of registry
  descriptor maps + a server id keyword/string. The value `render-edn`
  serialises and `check` compares.

  The optional `gated-keys` set names the input keys the
  server's DEFAULT `tools/list` profile gates off (story-mcp passes
  `#{\"include-sensitive\"}`); it is threaded to `build-rows` so each
  row's `:gated-input-keys` distinguishes the default surface from the
  gate-open one. Omit it (or pass `#{}`) when the server gates no input
  — the config-independent default pair-mcp uses."
  ([server descriptors] (build-manifest server descriptors #{}))
  ([server descriptors gated-keys]
   (let [rows (build-rows descriptors gated-keys)]
     {:meta  {:server server :tool-count (count rows)}
      :tools rows})))

;; ---------------------------------------------------------------------------
;; Drift-check — regenerate-in-memory vs committed.
;; ---------------------------------------------------------------------------

(defn- row-by-name
  "Index a seq of manifest rows by `:name`. Defensive against a row
  missing a `:name` (the committed EDN may be partly malformed) — such
  rows are dropped from the index rather than colliding under `nil`."
  [rows]
  (reduce (fn [m row]
            (if-let [nm (:name row)]
              (assoc m nm row)
              m))
          {}
          rows))

(defn check
  "Compare a freshly-generated manifest string against the committed
  file content. Returns a result map:

      {:ok?     <bool>
       :added   [<row name strings the generator produced the file lacks>]
       :removed [<row name strings in the file the generator no longer produces>]
       :changed [{:name <str> :old <committed-row> :new <generated-row>} ...]}

  `generated-edn` is `(render-edn (build-manifest ...))`; `committed`
  is the committed file's content string (or nil if it doesn't exist).
  Both are LF-normalised before comparison so a CRLF working-tree
  checkout on Windows does not trip a spurious drift. The added/removed
  name diffs are derived from the two manifests' tool rows so the
  failure message names exactly which tool drifted — same affordance
  the keystone's `check!` prints.

  ## Row-level `:changed` identity

  `:added` / `:removed` only name tools whose IDENTITY entered or left
  the catalogue. When an EXISTING tool's `:description`, `:input-keys`,
  `:gated-input-keys`, `:required`, `:output?`, `:annotations`, or
  `:typicalTokens` drifts, neither set names it — without a row-level
  diff the CI guard would go red with `{:ok? false :added [] :removed []}`
  and the consumer generator could only print a generic \"tool set
  identical; descriptor changed\" message, forcing a maintainer to
  manually diff the whole manifest. `:changed` closes that gap: for
  every name present in BOTH manifests whose row differs, it carries
  `{:name <str> :old <committed-row> :new <generated-row>}` so the
  failure message can name exactly which existing tool drifted and how.
  An in-sync manifest (`:ok? true`) and a missing-file result both carry
  `:changed []`."
  [generated-manifest generated-edn committed]
  (let [gen-rows  (:tools generated-manifest)
        gen-names (set (map :name gen-rows))]
    (if (nil? committed)
      {:ok? false :added (vec (sort gen-names)) :removed [] :changed []
       :missing-file? true}
      (let [ok?       (= (normalise-lf generated-edn) (normalise-lf committed))
            ;; Parse committed rows out of the EDN for the diff summary.
            ;; We read the committed EDN as data; if it's unparseable the
            ;; equality check above already failed and we report all gen
            ;; names as added (the file is structurally broken) with no
            ;; :changed rows (we can't compare against unreadable rows).
            com-rows  (try
                        (:tools (edn/read-string committed))
                        (catch #?(:clj Throwable :cljs :default) _ nil))
            com-names (set (map :name com-rows))
            gen-by    (row-by-name gen-rows)
            com-by    (row-by-name com-rows)
            ;; Names present in BOTH whose row content differs.
            changed   (->> (set/intersection gen-names com-names)
                           sort
                           (keep (fn [nm]
                                   (let [old (get com-by nm)
                                         new (get gen-by nm)]
                                     (when (not= old new)
                                       {:name nm :old old :new new}))))
                           vec)]
        {:ok?     ok?
         :added   (vec (sort (set/difference gen-names com-names)))
         :removed (vec (sort (set/difference com-names gen-names)))
         :changed changed}))))

;; ---------------------------------------------------------------------------
;; Drift-report formatting — PURE, platform-neutral report lines.
;;
;; The two server generators previously each carried a byte-identical
;; `row-slot-deltas` (with a copy of the governed-slot vector) and an
;; identical added / removed / changed / malformed traversal over a
;; `check` result — the diagnostic body the maintainer reads when a
;; drift-check trips. That duplication drifts: a governed slot added to
;; `descriptor->row` had to be mirrored into two report vectors, and any
;; wording tweak had to be applied twice or the two servers would report
;; the same drift differently. This section centralises the shared
;; diagnostic: the governed-slot vector, the per-row slot-delta helper,
;; and a pure formatter that turns a `check` result + the consumer's
;; wording into the report lines. It adds NO I/O — no file lookup, no
;; output channel, no process exit. Each server keeps its own file
;; lookup, stdout/stderr binding, OK-path wording, and exit codes, and
;; supplies the two strings that legitimately differ per server (the
;; regenerate command + the missing-file header).
;; ---------------------------------------------------------------------------

(def governed-slots
  "The catalogue-row slots (every row slot EXCEPT `:name`, whose entering
  / leaving the catalogue is the `:added` / `:removed` diff) whose
  per-tool drift the manifest governs, in canonical report order. A tool
  present in BOTH the committed and regenerated manifests but differing
  in any of these is a `:changed` row; `row-slot-deltas` names which of
  them moved. Shared so the two server generators report the SAME
  governed surface (they previously duplicated this vector)."
  [:description :input-keys :gated-input-keys :required :output? :annotations :typicalTokens])

(defn row-slot-deltas
  "Seq of `[slot old-val new-val]` for the `governed-slots` that differ
  between a tool's committed `old` row and regenerated `new` row — names
  exactly which governed slots drifted so a descriptor-only change is
  actionable without a manual whole-manifest diff."
  [old new]
  (for [slot governed-slots
        :let [o (get old slot)
              n (get new slot)]
        :when (not= o n)]
    [slot o n]))

(defn drift-report-lines
  "PURE formatter — no I/O, no file lookup, no process exit. Given a
  `check` result map for a DRIFTED manifest (the `:added` / `:removed` /
  `:changed` / `:missing-file?` shape `check` returns) and the two
  consumer-specific wording strings, return the vector of printable
  report lines. The caller prints them on its own stdout/stderr channel
  and owns the OK path + exit code — this fn is side-effect-free.

  `wording` supplies the two strings that legitimately differ per server:

      :regenerate-line   — the `Regenerate with: <command>` hint (each
                           server names its own regenerate command).
      :missing-file-line — the header printed when the committed file is
                           absent (`:missing-file?` true); each server
                           names its own regenerate command inline.

  When the file exists but drifted, the header is the shared
  `manifest differs` line. The added / removed / changed / malformed
  body is byte-identical across servers, so it lives here verbatim:

  - `:added`   — tools the registry has that the committed file lacks
                 (a new / renamed tool). A missing-file result reports
                 EVERY generated tool as added (the whole catalogue is
                 absent), so the added block also renders the full tool
                 list under the missing-file header.
  - `:removed` — tools in the committed file the registry no longer has.
  - `:changed` — existing tools whose catalogue row drifted; each row is
                 named, then its `row-slot-deltas` are printed one per
                 line as `<slot>: <old> -> <new>`.
  - malformed  — when all three are empty the tool set is identical but
                 the committed file is structurally broken or its
                 banner / meta drifted (regenerate)."
  [{:keys [added removed changed missing-file?]}
   {:keys [regenerate-line missing-file-line]}]
  (vec
   (concat
    [(if missing-file?
       missing-file-line
       "DRIFT: generated manifest differs from tool-descriptors.edn.")
     regenerate-line]
    (when (seq added)
      (cons "  Tools the registry has that the committed file lacks (new/renamed tool):"
            (map #(str "    + " %) added)))
    (when (seq removed)
      (cons "  Tools in the committed file the registry no longer has (removed/renamed tool):"
            (map #(str "    - " %) removed)))
    (when (seq changed)
      (cons "  Existing tools whose descriptor catalogue row changed (input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):"
            (mapcat (fn [{nm :name :keys [old new]}]
                      (cons (str "    ~ " nm)
                            (map (fn [[slot o n]]
                                   (str "        " (name slot) ": " (pr-str o) " -> " (pr-str n)))
                                 (row-slot-deltas old new))))
                    changed)))
    (when (and (empty? added) (empty? removed) (empty? changed))
      ["  (tool set identical; the committed file is structurally broken or its provenance banner/meta drifted — regenerate)"]))))
