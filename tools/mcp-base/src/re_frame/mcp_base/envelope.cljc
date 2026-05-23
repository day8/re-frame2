(ns re-frame.mcp-base.envelope
  "Cross-MCP response-envelope helpers (rf2-ee38b.19).

  The MCP servers decorate their tool-response envelopes with two
  cross-cutting concerns that are pure data and identical across the
  pair:

    1. The indicator-field counters (`:dropped-sensitive` /
       `:elided-large`) — the MUST-level 'omit when zero' parity rule
       per Conventions §Cross-MCP indicator-field vocabulary and
       Spec 009 §Indicator field on tool responses.
    2. Wire-bounded marker detection — recognising the `:rf.mcp/*`
       replacement envelopes (`:rf.mcp/cache-hit`, `:rf.mcp/overflow`)
       that the cache + cap boundary steps emit themselves, so later
       boundary steps don't re-walk a sub-cap-by-construction marker.

  Both indicator KEYS already live in `vocab.cljc`; the producers of
  the counts (`elision/count-elided-markers`,
  `sensitive/strip-sensitive`) already live in the base. This ns adds
  the helper that SPLICES them onto the envelope — previously
  pair-mcp-only despite being pure data — so the single emit-path the
  spec mandates lives in one place the conformance gate can test
  directly.

  ## Cross-platform

  Pure CLJC, no transport / no host shape. `with-indicators` operates
  on a Clojure envelope map. `marker-text?` operates on the RENDERED
  text string — the consumer reads its own platform's content slot
  (`:text` from a Clojure map / `j/get :text` from a JS object) and
  passes the string here, so the shape-specific accessor stays
  consumer-side while the prefix-detection logic is shared."
  (:require [clojure.string :as str]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; Indicator-field splice — the MUST-level 'omit when zero' rule.
;; ---------------------------------------------------------------------------

(defn with-indicators
  "Splice the cross-MCP indicator-field slots onto a tool's envelope
  map, enforcing the MUST-level 'omit when zero' rule from
  Conventions §Cross-MCP indicator-field vocabulary and Spec 009
  §Indicator field on tool responses.

  Every tool that walks a tree-typed payload (`snapshot`, `get-path`,
  `trace-window`, `watch-epochs`, `subscribe`, …) routes its
  envelope-tail through here so the rule lives in one place — drift
  across emit sites can no longer silently violate the MUST.

  `counts`:
    :dropped — count of `:sensitive? true` leaves dropped at the wire
               boundary (from `sensitive/strip-sensitive`). Emitted
               under `vocab/dropped-sensitive-key` when positive.
    :elided  — count of leaves replaced with the
               `:rf.size/large-elided` marker (from
               `elision/count-elided-markers`). Emitted under
               `vocab/elided-large-key` when positive.

  A zero / nil / absent count omits its slot entirely (the 'omit when
  zero' MUST). Returns `envelope` unchanged when both counts are
  zero — identity-preserving on the common path."
  [envelope {:keys [dropped elided]}]
  (cond-> envelope
    (pos? (or dropped 0)) (assoc vocab/dropped-sensitive-key dropped)
    (pos? (or elided  0)) (assoc vocab/elided-large-key      elided)))

;; ---------------------------------------------------------------------------
;; Wire-bounded marker detection (rf2-gktyn, rf2-3z0zi).
;;
;; The `:rf.mcp/cache-hit` and `:rf.mcp/overflow` envelopes are
;; replacement results the cache + cap boundary steps emit themselves.
;; By construction they are sub-cap size — re-walking either is wasted
;; work and a cache check on a hit-marker would hash the marker, not
;; the original payload.
;;
;; Substring-match on the rendered text is the cheap detector — the
;; marker map's namespaced key is the first key of the outer map, so a
;; `starts-with?` on the trimmed text is fast and tight. We match BOTH
;; print forms the single-key namespaced marker map can take: the flat
;; form `{:rf.mcp/overflow ...` (the form CLJS `pr-str` emits and the
;; form JVM emits with `*print-namespace-maps*` false) and the
;; namespaced-map shorthand `#:rf.mcp{:overflow ...` (the form JVM
;; `pr-str` emits by default for a single-namespace map). Matching both
;; keeps the detector host- and print-setting-agnostic. A false
;; positive would require an agent-supplied payload that ALSO renders
;; with `:rf.mcp/cache-hit` / `:rf.mcp/overflow` as its leading top-
;; level key — not a realistic shape for any tool's result.
;; ---------------------------------------------------------------------------

(defn- marker-key-prefixes
  "Both print-form prefixes for a single-key namespaced marker map
  whose sole key is `marker-key` (e.g. `:rf.mcp/overflow`):

    - flat form           `{:rf.mcp/overflow`
    - namespaced-map form `#:rf.mcp{:overflow`"
  [marker-key]
  (let [flat (str "{" marker-key)
        ns'  (namespace marker-key)
        nm   (name marker-key)]
    [flat
     (if ns'
       (str "#:" ns' "{:" nm)
       flat)]))

(def marker-prefixes
  "Rendered-text prefixes of the wire-bounded `:rf.mcp/*` replacement
  envelopes (`:rf.mcp/cache-hit`, `:rf.mcp/overflow`). Includes BOTH
  the flat and the namespaced-map print forms (see the comment block
  above) so the detector works regardless of host or
  `*print-namespace-maps*`. A response whose text starts with one of
  these is a boundary-step marker that later boundary steps must NOT
  re-walk."
  (into [] (mapcat marker-key-prefixes) [vocab/cache-hit-key vocab/overflow-key]))

(defn marker-text?
  "Is `text` (the rendered EDN text of a response's first content slot)
  a wire-bounded `:rf.mcp/*` marker envelope?

  Returns true for `:rf.mcp/cache-hit` / `:rf.mcp/overflow` markers —
  the two envelopes the cache + cap steps emit themselves. The
  consumer reads its own platform's content text (`:text` from a
  Clojure map, `j/get :text` from a JS object) and passes the string
  here; this fn owns only the prefix-match logic, shared across hosts.

  Nil-safe: a nil / non-string `text` is not a marker."
  [text]
  (boolean
    (and (string? text)
         (some #(str/starts-with? text %) marker-prefixes))))
