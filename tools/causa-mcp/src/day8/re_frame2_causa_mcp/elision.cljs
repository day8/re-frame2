(ns day8.re-frame2-causa-mcp.elision
  "Spec/004 §6 size-elision wire-marker integration at the Causa-MCP
  boundary (W-6 of rf2-8xzoe; tranche bead rf2-8xzoe.10).

  ## What this gates

  Spec/004 §6 mandates that every tool emitting a tree-typed payload
  routes the value through the framework's size-elision walker
  (`re-frame.core/elide-wire-value`) before it crosses the MCP stdio
  trust boundary. The walker substitutes over-threshold leaves with a
  canonical `{:rf.size/large-elided {...}}` marker carrying a re-fetch
  handle (`[:rf.elision/at <path>]`); the small siblings ride the wire
  verbatim. A single 100KB base64 PDF on `[:user :uploaded-pdf]` then
  costs marker-bytes, not payload-bytes.

  ## Where the gate fires for causa-mcp

  The tree-typed surface — direct-read tools and per-record epoch
  slices — is the canonical set: `get-app-db`, `get-app-db-diff`,
  `get-machine-state`, `get-epoch-history` (per-record `:db-before` /
  `:db-after`), `subscribe` payloads carrying rich coeffect / effect
  slots. Each tool inlines `elision-opts-edn` into the CLJS eval form
  it ships over nREPL so the walker actually runs in the consumer
  app's runtime (the `[:rf/elision]` registry lives in app-db; the
  Node process can't reach it directly). The marker-count then rides
  back on the same round-trip and `apply-to-result` stamps the
  `:elided-large` envelope counter at the MCP boundary.

  ## Sibling to `privacy.cljs` (B-1 of the same epic)

  B-1 (rf2-8xzoe.11) lands the spec/009 default-suppress filter at
  the same MCP egress codepath. W-6 here is the size-elision
  counterpart. The two boundary wrappers share an envelope shape
  (`apply-to-result` is the per-call site, indicator counters are
  unqualified envelope slots — `:dropped-sensitive` and
  `:elided-large` — and ride together so an agent reads both axes on
  the same response). The composition cascade is normative
  (spec/004 §6 — sensitive wins; `(and sensitive? large?) → ::drop`,
  no marker emitted); the walker itself enforces it server-side, so
  the MCP-boundary wrapper just counts whatever markers came back.

  ## Cross-server arg vocabulary

  The opt-in escape hatch is `:include-large?` — fixed cross-server
  (pair2-mcp + story-mcp + causa-mcp), parallel to
  `:include-sensitive?`. An agent learns the slot name once and
  recognises it everywhere. `parse-include-large` centralises the
  boolean parse so a string `\"true\"` / `\"yes\"` / `\"1\"` from
  the MCP wire collapses to the same boolean the helper here
  expects.

  ## Why this ns delegates to `re-frame.mcp-base`

  The base nss are the shared spec/004 + spec/009 primitives across
  the MCP triplet:

  - `re-frame.mcp-base.elision/count-elided-markers` — the canonical
    walker that counts `{:rf.size/large-elided ...}` markers in a
    wire-bound payload (rf2-9fz64; per Conventions §Cross-MCP
    indicator-field vocabulary, MUST-level per rf2-2499j).
  - `re-frame.mcp-base.vocab` — the constants catalogue: marker key
    (`:rf.size/large-elided`), walker opts (`:rf.size/include-large?`,
    `:rf.size/include-sensitive?`), envelope-slot key
    (`:elided-large`).
  - `re-frame.mcp-base.args/parse-boolean` — the cross-MCP
    accept-shape contract (rf2-vw4sq).

  Same call shapes the pair2-mcp + story-mcp sibling boundary
  wrappers use; behaviour stays byte-identical across the triplet
  per the cross-MCP conformance gate (rf2-zvv65).

  ## MUSTs honoured

  - MUST 15 — every tree-typed tool declares the `:include-large?`
    slot and the `:elided-large` indicator field; the default
    elision-policy is on. `apply-to-result` is the wrapper those
    tools call when they land.
  - MUST 17 — `re-frame.core/elide-wire-value` is the single
    normative emission site; per-tool reimplementation prohibited.
    This ns NEVER emits the marker itself — it inlines the walker
    call into the eval form (`elision-opts-edn`) and downstream
    only counts markers the walker produced.
  - MUST 18 — sensitive-wins composition; the cascade lives in the
    framework walker, this ns just ships the opts and counts the
    resulting markers (no separate marker-emission path here).
  - MUST 19, `:include-large?` half — the opt-in slot name is fixed
    cross-MCP (parallel to `:include-sensitive?`)."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Marker count — direct re-export of the base walker.
;;
;; The canonical tree-walk lives in `re-frame.mcp-base.elision` so the
;; MCP triplet shares one implementation. Causa-mcp re-exports it
;; under the local ns so per-tool dispatchers `:require` a single ns
;; for elision concerns (matches the privacy.cljs `sensitive-event?` /
;; `strip-sensitive` re-export pattern).
;; ---------------------------------------------------------------------------

(def count-elided-markers
  "Walk `v` and count every `{:rf.size/large-elided ...}` marker it
  contains. The walker is shallow at the marker boundary — once a
  marker is found, its body is NOT recursed into (marker bodies are
  scalar metadata, not tree-shaped). Returns an integer ≥ 0.

  Direct re-export of `re-frame.mcp-base.elision/count-elided-markers`
  — the cross-MCP single implementation per the marker-shape
  contract (rf2-9fz64)."
  base-elision/count-elided-markers)

;; ---------------------------------------------------------------------------
;; Argument parsing — `:include-large?` is the cross-MCP opt-in.
;;
;; Parallel to privacy.cljs's `parse-include-sensitive`. The two args
;; ride together on every tree-typed tool's surface; the parsers share
;; the `base-args/parse-boolean` core so accept-shapes (boolean,
;; lower-case string, keyword) stay byte-identical across the slots.
;; ---------------------------------------------------------------------------

(def ^:const include-large-default
  "Default posture for `:include-large?` is `false` — spec/004 §6
  defaults markers ON so over-threshold leaves elide. The constant is
  reified so the test corpus + downstream tool dispatchers reference
  the same identity rather than re-typing the literal. Parallel to
  `privacy/include-sensitive-default`."
  false)

(defn parse-include-large
  "Resolve the cross-server `:include-large?` MCP arg from a raw
  arguments object. Accepts:

    - a JS args object (the MCP SDK shape) — looked up via
      `(j/get args \"include-large?\")`.
    - a CLJS map — looked up via `(get args :include-large?)` or
      the stringified key (whichever the upstream dispatcher hands us).
    - `nil` / `js/undefined`.

  Recognised-value parsing (boolean passthrough, string `\"true\"` /
  `\"false\"` / `\"yes\"` / `\"no\"` / `\"1\"` / `\"0\"`, keyword
  `:true` / `:false`) delegates to
  `re-frame.mcp-base.args/parse-boolean` — the cross-MCP accept-shape
  contract (rf2-vw4sq).

  Returns a boolean. Unrecognised / absent inputs collapse to the
  spec/004 §6 default-on posture (`false`)."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :include-large?)
                  (get args "include-large?"))

              :else
              ;; JS object from the MCP wire.
              (j/get args "include-large?"))]
    (base-args/parse-boolean raw include-large-default)))

;; ---------------------------------------------------------------------------
;; Eval-form composition — render walker opts as EDN for inlining.
;;
;; The walker runs in the consumer app's runtime (the `[:rf/elision]`
;; registry lives in app-db). The MCP server inlines the opts map
;; into the eval form it ships over nREPL. Two knobs:
;;
;;   - `:rf.size/include-large?`     — `false` ⇒ emit markers (default)
;;   - `:rf.size/include-sensitive?` — `false` ⇒ redact sensitive
;;
;; The polarity is inverted from the MCP-arg booleans (the MCP arg is
;; `:include-large? true` to PASS THROUGH; the walker opt is
;; `:rf.size/include-large? true` for the same posture). The helper
;; encodes the inversion so call-sites pass the agent-facing boolean
;; without re-doing the flip.
;;
;; Cross-server identical to pair2-mcp's `tools/elision/elision-opts-edn`
;; (rf2-urjnc). Same key names, same polarity, same EDN shape.
;; ---------------------------------------------------------------------------

(defn elision-opts-edn
  "Render the walker's opts map as an EDN string for inlining into a
  CLJS eval form sent over nREPL.

  Arguments:

  - `include-large?`      — the MCP-arg boolean. `false` ⇒ walker
                            emits markers (the default; spec/004 §6
                            posture). `true` ⇒ walker passes large
                            values through unmodified.
  - `include-sensitive?`  — the MCP-arg boolean. `false` ⇒ walker
                            substitutes the `:rf/redacted` sentinel at
                            declared-sensitive leaves (the default;
                            spec/009 §Privacy posture). `true` ⇒
                            walker passes sensitive values through.

  Both args default off-box-safe per the Tool-Pair §Direct-read
  privacy posture contract (`spec/004-Wire-Pipeline.md §Direct-read
  privacy — normative MUST`).

  Returns the EDN string of `{:rf.size/include-large? <bool>
  :rf.size/include-sensitive? <bool>}` — ready to splice into the
  walker call inside a server-side eval form. The keys are
  cross-MCP normative (per `re-frame.mcp-base.vocab`).

  Single-arity form preserves the off-box-safe default for
  `include-sensitive?` so legacy call-sites don't need to spell it
  out. Both-args form is the canonical surface."
  ([include-large?]
   (elision-opts-edn include-large? false))
  ([include-large? include-sensitive?]
   (pr-str {base-vocab/include-large-opt     (boolean include-large?)
            base-vocab/include-sensitive-opt (boolean include-sensitive?)})))

;; ---------------------------------------------------------------------------
;; Result-envelope shape — `:elided-large` counter (cross-MCP).
;;
;; Parallel to `privacy/stamp-dropped-sensitive`. The slot is
;; unqualified per Conventions §Cross-MCP indicator-field vocabulary;
;; the counter is omitted when zero so the zero-elision common path
;; stays minimal on the wire.
;; ---------------------------------------------------------------------------

(defn stamp-elided-large
  "Splice the `:elided-large` counter onto `envelope` iff `elided` is
  positive. Returns the envelope unchanged when nothing was elided —
  the zero-elision common path carries no counter so the agent
  surface stays minimal (a missing slot reads as zero, same
  convention as `privacy/stamp-dropped-sensitive` and pair2-mcp's
  `wire/stamp-indicator-fields`).

  `envelope` is the per-call result map a tool dispatcher is about
  to serialise to the MCP wire. `elided` is the integer return of
  `count-elided-markers` (or the accumulated total across a
  multi-slice call)."
  [envelope elided]
  (cond-> envelope
    (and (number? elided) (pos? elided))
    (assoc base-vocab/elided-large-key elided)))

;; ---------------------------------------------------------------------------
;; Per-tool boundary wrapper.
;;
;; Tree-typed tools call this once at the end of their body, with the
;; already-walked value (the eval form ran the walker server-side and
;; returned the post-elision payload) + the in-progress envelope. The
;; helper counts markers, stamps the `:elided-large` counter when
;; non-zero, and writes the value back into the envelope at
;; `value-key`. Same cross-tool one-liner shape `privacy/apply-to-result`
;; uses, so the boundary site reads uniformly across tools — `value-key`
;; varies (`:db` for `get-app-db`, `:diff` for `get-app-db-diff`,
;; `:state` for `get-machine-state`, etc.); the call shape doesn't.
;;
;; The walker already ran server-side, so the value carries markers in
;; place. We do NOT re-walk to substitute — that would violate MUST 17
;; (single normative emission site). We only COUNT markers for the
;; envelope indicator.
;;
;; ## Optional `:server-elided` opt
;;
;; Sibling to pair2-mcp's rf2-e35a5 optimisation: when the eval form
;; pre-counts markers server-side and ships the integer back on the
;; same nREPL round-trip, the dispatcher passes `:server-elided <n>`
;; and skips the local walk. Cheaper for large payloads where the
;; walk dominates the wrapper cost. Zero is a valid server count
;; (not a sentinel — `(some? n)` semantics).
;; ---------------------------------------------------------------------------

(defn apply-to-result
  "Apply the spec/004 §6 size-elision boundary stamp to `value` and
  write the result back into `envelope` under `value-key`. Returns
  the updated envelope with the `:elided-large` counter spliced in
  when non-zero. The single call-site every tree-typed tool uses
  before returning — MUST 15 of the causa-mcp inventory.

  Arguments:
    - `envelope`   — the per-call result map (will be updated).
    - `value-key`  — the slot in `envelope` the post-walk value goes
                     into (e.g. `:db` for `get-app-db`, `:diff` for
                     `get-app-db-diff`, `:state` for
                     `get-machine-state`, `:events` for `subscribe`
                     drain batches).
    - `value`      — the already-walked payload (the eval form ran
                     `re-frame.core/elide-wire-value` server-side;
                     the marker substitution is already in place).
    - `opts`       — optional map. Recognised keys:
                       `:server-elided` — integer; when supplied,
                         used verbatim instead of re-walking
                         `value` to count markers (rf2-e35a5 sibling
                         optimisation). Zero is honoured, not
                         treated as a fall-back trigger.

  Returns the envelope with `value-key` set to `value` and
  `:elided-large` stamped when at least one marker was present.

  The zero-arity-opts shape `(apply-to-result envelope value-key
  value)` falls back to walking `value` locally with
  `count-elided-markers`."
  ([envelope value-key value]
   (apply-to-result envelope value-key value nil))
  ([envelope value-key value {:keys [server-elided]}]
   (let [elided (if (some? server-elided)
                  server-elided
                  (count-elided-markers value))]
     (-> envelope
         (assoc value-key value)
         (stamp-elided-large elided)))))
