(ns re-frame.mcp-base.descriptor-manifest
  "Shared MCP tool-descriptor manifest generator + drift-check (rf2-sofwv —
  follow-on to the rf2-3nbl5.2 API-governance keystone).

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

      {:name        \"<wire-name>\"
       :description \"<one-line semantics>\"
       :input-keys  [<sorted inputSchema property keys, as strings>]
       :output?     <bool — does the tool declare an outputSchema?>
       :annotations [<sorted annotation-hint keys, as strings>]}

  WHY THIS SHAPE (not the whole descriptor). The full descriptor maps
  carry deeply-nested JSON-Schema fragments whose exact serialisation
  (key order inside nested property maps, optional `:description` prose)
  is volatile and not the contract this gate guards. The gate's job is
  the CATALOGUE shape: which tools exist, what they're called, the
  top-level input-property surface, whether each declares structured
  output + annotation hints. That is precisely the surface that goes
  stale when a tool is added / removed / renamed or its input surface
  changes — the drift the bead targets. The full descriptor's wire
  validity is already pinned by the MCP-SDK conformance harness
  (`tools/mcp-conformance/`); this manifest pins the catalogue identity.

  DRIFT-CHECK. `check` regenerates the manifest in memory from the live
  registry and compares it to the committed file (LF-normalised). Any
  difference — a tool added, removed, or renamed in the registry; a
  changed input-key surface; a flipped output?/annotations flag — fails.
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

(defn descriptor->row
  "Project one registry descriptor map into the stable manifest row.

  Accepts the descriptor in its on-the-registry shape: a map with
  `:name`, `:description`, `:inputSchema` (with `:properties`),
  optional `:outputSchema`, optional `:annotations`. Both servers'
  descriptors carry these slots (story-mcp lifts :outputSchema +
  :annotations via cond->; pair-mcp declares them per-tool)."
  [{:keys [name description inputSchema outputSchema annotations]}]
  {:name        name
   :description description
   :input-keys  (sorted-string-keys (:properties inputSchema))
   :output?     (some? outputSchema)
   :annotations (sorted-string-keys annotations)})

(defn build-rows
  "Project a SEQ of registry descriptor maps into the sorted vector of
  manifest rows. Rows are sorted by `:name` so the manifest order is
  insensitive to registry authoring order (catalogue IDENTITY, not
  ordering, is what this gate guards; the wire-surface ordering is
  pinned separately by the per-server order-sensitive tests)."
  [descriptors]
  (->> descriptors
       (map descriptor->row)
       (sort-by :name)
       vec))

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
  is a one-line diff."
  [row]
  (str "{:name " (render-scalar (:name row))
       " :description " (render-scalar (:description row))
       " :input-keys " (render-string-vec (:input-keys row))
       " :output? " (render-scalar (:output? row))
       " :annotations " (render-string-vec (:annotations row))
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
  serialises and `check` compares."
  [server descriptors]
  (let [rows (build-rows descriptors)]
    {:meta  {:server server :tool-count (count rows)}
     :tools rows}))

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

  ## Row-level `:changed` identity (rf2-y3qpv)

  `:added` / `:removed` only name tools whose IDENTITY entered or left
  the catalogue. When an EXISTING tool's `:description`, `:input-keys`,
  `:output?`, or `:annotations` drifts, neither set names it — the CI
  guard went red with `{:ok? false :added [] :removed []}` and the
  consumer generator could only print a generic \"tool set identical;
  descriptor changed\" message, forcing a maintainer to manually diff
  the whole manifest. `:changed` closes that gap: for every name present
  in BOTH manifests whose row differs, it carries
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
