(ns re-frame.mcp-base.envelope
  "Cross-MCP response-envelope helpers.

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

  Both indicator KEYS live in `vocab.cljc`; the producers of
  the counts (`elision/count-elided-markers`,
  `sensitive/strip-sensitive`) live in the base. This ns adds
  the helper that SPLICES them onto the envelope — pure data shared by
  both servers — so the single emit-path the spec mandates lives in one
  place the conformance gate can test directly.

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
;; Wire-bounded marker detection.
;;
;; The `:rf.mcp/cache-hit` and `:rf.mcp/overflow` envelopes are
;; replacement results the cache + cap boundary steps emit themselves.
;; By construction they are sub-cap size — re-walking either is wasted
;; work and a cache check on a hit-marker would hash the marker, not
;; the original payload.
;;
;; Leading-token match on the rendered text is the cheap detector — the
;; marker map's namespaced key is the first key of the outer map, so a
;; tight match on the trimmed text's leading token is fast. We match
;; BOTH print forms the single-key namespaced marker map can take: the
;; flat form `{:rf.mcp/overflow ...` (the form CLJS `pr-str` emits and
;; the form JVM emits with `*print-namespace-maps*` false) and the
;; namespaced-map shorthand `#:rf.mcp{:overflow ...` (the form JVM
;; `pr-str` emits by default for a single-namespace map). Matching both
;; keeps the detector host- and print-setting-agnostic.
;;
;; EXACT key, not a prefix. A bare `starts-with?` on the
;; marker key would wrongly classify a LOOKALIKE first key whose name
;; merely begins with a marker key — e.g. `:rf.mcp/overflowed` or
;; `:rf.mcp/cache-hit-extra`. That is a correctness hole: an over-budget
;; payload whose leading key starts with `:rf.mcp/overflow` would be
;; treated as an already-bounded marker and bypass cap enforcement. So
;; after the prefix matches we require the very next character to be an
;; EDN token TERMINATOR (whitespace or a delimiter), proving the marker
;; key ended exactly there and was not merely a prefix of a longer key.
;; The two real markers always carry a non-empty map value, so the
;; terminator (the space `pr-str` writes before the value) is always
;; present — the exact-match check preserves their short-circuit.
;; ---------------------------------------------------------------------------

(defn- marker-key-prefixes
  "Both print-form prefixes for a single-key namespaced marker map
  whose sole key is `marker-key` (e.g. `:rf.mcp/overflow`):

    - flat form           `{:rf.mcp/overflow`
    - namespaced-map form `#:rf.mcp{:overflow`"
  [marker-key]
  (let [flat     (str "{" marker-key)
        key-ns   (namespace marker-key)
        key-name (name marker-key)]
    [flat
     (if key-ns
       (str "#:" key-ns "{:" key-name)
       flat)]))

(def marker-prefixes
  "Rendered-text prefixes of the wire-bounded `:rf.mcp/*` replacement
  envelopes (`:rf.mcp/cache-hit`, `:rf.mcp/overflow`). Includes BOTH
  the flat and the namespaced-map print forms (see the comment block
  above) so the detector works regardless of host or
  `*print-namespace-maps*`. A response whose leading token IS one of
  these keys (not merely starts-with — see `marker-text?`) is a
  boundary-step marker that later boundary steps must NOT re-walk."
  (into [] (mapcat marker-key-prefixes) [vocab/cache-hit-key vocab/overflow-key]))

;; An EDN keyword/symbol constituent: alphanumerics plus the punctuation
;; a keyword name/namespace may legally contain (`* + ! - _ ' ? < > = .
;; / : # & %`). A 1-char string that matches this regex CONTINUES the
;; marker key (⇒ lookalike); anything else (whitespace, `,`, or a
;; map/vector/list/string delimiter) TERMINATES it. Single regex keeps
;; the check identical across CLJ and CLJS (no host char-arithmetic).
(def ^:private key-constituent-re #"[A-Za-z0-9*+!_'?<>=./:#&%-]")

(defn- key-terminator?
  "Is the 1-char string `ch` a character that cannot continue an EDN
  keyword/symbol token — i.e. a valid terminator immediately following a
  marker key in rendered text? `nil` (end-of-string) does
  NOT count: a complete marker map always has a value after the key, so
  a key flush against EOS is a truncated / lookalike form, not a real
  marker."
  [ch]
  (boolean (and ch (not (re-matches key-constituent-re ch)))))

(defn- exact-marker-prefix?
  "Does `text` begin with marker `prefix` AND end the marker key exactly
  there — i.e. the character at index `(count prefix)` is a token
  terminator? This rejects lookalike first keys like
  `:rf.mcp/overflowed` whose name merely starts with a marker key."
  [text prefix]
  (let [plen (count prefix)]
    (and (str/starts-with? text prefix)
         (key-terminator? (when (> (count text) plen)
                            (subs text plen (inc plen)))))))

(defn marker-text?
  "Is `text` (the rendered EDN text of a response's first content slot)
  a wire-bounded `:rf.mcp/*` marker envelope?

  Returns true for `:rf.mcp/cache-hit` / `:rf.mcp/overflow` markers —
  the two envelopes the cache + cap steps emit themselves. The
  consumer reads its own platform's content text (`:text` from a
  Clojure map, `j/get :text` from a JS object) and passes the string
  here; this fn owns only the leading-token match logic, shared across
  hosts.

  Matches the EXACT marker key, not merely a prefix of it:
  a lookalike leading key such as `:rf.mcp/overflowed` or
  `:rf.mcp/cache-hit-extra` is NOT a marker. The match requires the
  marker key to be terminated by an EDN token terminator (the space
  `pr-str` writes before the marker's value), so an over-budget payload
  whose first key merely starts with a marker key still gets capped.

  Nil-safe: a nil / non-string `text` is not a marker."
  [text]
  (boolean
    (and (string? text)
         (some #(exact-marker-prefix? text %) marker-prefixes))))
