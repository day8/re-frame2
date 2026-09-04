(ns re-frame.mcp-base.envelope
  "Cross-MCP response-envelope helpers.

  These helpers operate on wire vocabulary shared by the MCP pair:

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
            #?(:clj [clojure.edn :as edn])
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab])
  #?(:cljs (:require [cljs.reader])))

;; ---------------------------------------------------------------------------
;; Indicator-field splice — the MUST-level 'omit when zero' rule.
;; ---------------------------------------------------------------------------

(defn with-indicators
  "Splice the cross-MCP indicator-field slots onto a tool's envelope
  map, enforcing the MUST-level 'omit when zero' rule from
  Conventions §Cross-MCP indicator-field vocabulary and Spec 009
  §Indicator field on tool responses.

  Tree-payload emitters route their envelope through this helper so the
  indicator-key and omit-when-zero rules stay consistent.

  `counts`:
    :dropped — count of records classified as sensitive and dropped at
               the wire boundary (including fail-closed malformed
               stamps). Emitted
               under `rf.mcp-base.vocab/dropped-sensitive-key` when positive.
    :elided  — count of leaves replaced with the
               `:rf.size/large-elided` marker (from
               `elision/count-elided-markers`). Emitted under
               `rf.mcp-base.vocab/elided-large-key` when positive.

  A zero / nil / absent count omits its slot entirely (the 'omit when
  zero' MUST). Returns `envelope` unchanged when both counts are
  zero — identity-preserving on the common path."
  [envelope {:keys [dropped elided]}]
  (cond-> envelope
    (pos? (or dropped 0)) (assoc rf.mcp-base.vocab/dropped-sensitive-key dropped)
    (pos? (or elided  0)) (assoc rf.mcp-base.vocab/elided-large-key      elided)))

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
  boundary-step marker that a consumer can skip re-walking."
  (into [] (mapcat marker-key-prefixes) [rf.mcp-base.vocab/cache-hit-key rf.mcp-base.vocab/overflow-key]))

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

;; ---------------------------------------------------------------------------
;; Structural confirmation — the CLOSED single-key wrapper, not just the
;; leading token.
;;
;; The leading-token pre-filter above proves only the FIRST key. That is
;; NOT the invariant the fast-path skip actually relies on: "a marker is
;; sub-cap by construction" holds only for a COMPLETE, CLOSED, single-key
;; marker envelope. A mixed wrapper such as
;;   `{:rf.mcp/overflow {:limit :reached} :unexpected "<8K body>"}`
;; leads with a real marker key yet carries an arbitrary top-level sibling.
;; Classifying it as an already-bounded marker lets a reserved-key-shaped
;; or malformed handler result BYPASS cap enforcement (and skip cache
;; bookkeeping) rather than being measured/replaced — rf2-j538f7.20. So
;; after the cheap pre-filter matches, we PARSE the whole rendered text and
;; require a closed single-key marker map.
;;
;; The read reuses cursor.cljc's proven, host-agnostic "one form, EOF-
;; exhausted, tagged-literals-rejected" technique: wrap as
;; `[<text> <eof-sentinel>]` and require the read to yield EXACTLY
;; `[<one-form> <eof-sentinel>]`. A trailing EDN form pushes the count past
;; 2; an injected `]` truncates the read before the sentinel; any tagged
;; literal (built-in `#inst`/`#uuid` or custom `#js`/`#foo`) throws. All
;; three fall through to "not a marker". Runs identically on `clojure.edn`
;; (JVM) and `cljs.reader` (CLJS).
;; ---------------------------------------------------------------------------

(def ^:private marker-keys
  "The exact set of top-level keys a wire-bounded marker envelope may
  carry — the two the cache + cap boundary steps emit. A closed
  single-key map whose sole key is one of these (with a map body) is a
  marker; anything else is ordinary / malformed payload."
  #{rf.mcp-base.vocab/cache-hit-key rf.mcp-base.vocab/overflow-key})

(defn- reject-marker-tag!
  "Throw on any tagged literal encountered while reading marker text. A
  genuine marker envelope is pure data (keywords, maps, strings, numbers);
  a tagged literal is a smuggled host object / malformed payload, never a
  marker. Caught by `read-one-closed-form` → `::invalid`."
  []
  (throw (ex-info "marker text carried a tagged literal — not a marker" {})))

(def ^:private marker-no-tag-readers
  "Reject the BUILT-IN `#inst` / `#uuid` tags, which have registered
  readers on both hosts and would otherwise bypass `:default`. Mirrors
  cursor.cljc's `no-tag-readers`."
  {'inst (fn [_] (reject-marker-tag!))
   'uuid (fn [_] (reject-marker-tag!))})

(def ^:private marker-eof-sentinel
  "Appended after the rendered text to assert the reader consumed the
  WHOLE wrapped string (the string-host analog of an `:eof` read
  sentinel). Qualified + unguessable so attacker text reproducing this
  literal still cannot pass the exhaustion check."
  ::marker-eof-sentinel)

(defn- read-one-closed-form
  "Read `text` as EXACTLY ONE EDN form — tagged literals rejected, EOF
  exhausted (a trailing form ⇒ reject), `]`-injection defeated — via
  cursor.cljc's wrap-and-sentinel technique. Returns the sole form, or
  `::invalid` on any read failure / trailing content / tagged literal.
  Never throws."
  [text]
  (try
    (let [opts    {:readers marker-no-tag-readers
                   :default (fn [_ _] (reject-marker-tag!))}
          wrapped (str "[" text " " (pr-str marker-eof-sentinel) "]")
          forms   #?(:clj  (edn/read-string opts wrapped)
                     :cljs (cljs.reader/read-string opts wrapped))]
      (if (and (vector? forms)
               (= 2 (count forms))
               (= marker-eof-sentinel (nth forms 1)))
        (nth forms 0)
        ::invalid))
    (catch #?(:clj Throwable :cljs :default) _ ::invalid)))

(defn- closed-marker-envelope?
  "Structural confirmation that `text` renders a COMPLETE, CLOSED,
  single-key `:rf.mcp/*` marker envelope: exactly one top-level key drawn
  from `marker-keys`, with a map body. This proves the invariant the
  fast-path skip relies on — the leading-token pre-filter proves only the
  FIRST key, whereas this proves the WHOLE wrapper is closed (no extra
  top-level sibling, no trailing form, no tagged literal, no non-map
  root/body). A reserved-key-shaped or malformed payload therefore cannot
  inherit the marker exemption and bypass cap enforcement (rf2-j538f7.20).
  Additive body fields remain allowed — only the OUTER wrapper must be
  closed."
  [text]
  (let [form (read-one-closed-form text)]
    (and (map? form)
         (= 1 (count form))
         (let [[k body] (first form)]
           (and (contains? marker-keys k)
                (map? body))))))

;; ---------------------------------------------------------------------------
;; Size bound — the BODY dimension of "sub-cap by construction".
;;
;; Closure alone does not bound the marker's SIZE. A COMPLETE, CLOSED,
;; single-key `{:rf.mcp/overflow {:blob <100 KB>}}` passes both gates above
;; (leading token + closed single-key map body) yet its rendered text is
;; arbitrarily large. Classifying it as a marker lets the fast-path skip
;; egress an over-budget payload un-capped — a reserved-`:rf.mcp/*`-namespace
;; handler result (the same abnormal precondition rf2-j538f7.20's sibling
;; case required) bypassing cap enforcement. rf2-vd1uyn.
;;
;; The invariant the skip actually relies on is "a marker is sub-cap BY
;; CONSTRUCTION". We enforce it DIRECTLY against the documented convention
;; cap (`rf.mcp-base.overflow/default-max-tokens`): a marker candidate whose rendered
;; text estimates over the default cap is NOT sub-cap by construction, so it
;; is not a marker and falls through to normal cap enforcement. The two real
;; markers are tiny fixed-shape maps (a few hundred chars ≈ ~100 tokens), so
;; they pass with vast headroom; only an over-budget reserved-key-shaped
;; payload is caught. Anything this fn calls a marker is therefore GUARANTEED
;; under the default cap — skipping the cap step on it can never leak an
;; over-default-cap body. The O(1) `count` also short-circuits an adversarial
;; 100 KB input before the O(n) `closed-marker-envelope?` read.
;; ---------------------------------------------------------------------------

(defn- marker-sized?
  "Is `text` small enough to be a genuine, sub-cap-by-construction marker —
  i.e. does its rendered-text token estimate stay within the documented
  default cap (`rf.mcp-base.overflow/default-max-tokens`)? A single-key reserved-`:rf.mcp/*`
  wrapper whose BODY pushes the rendered text over the default cap is NOT a
  marker (rf2-vd1uyn); it continues through cap enforcement like any payload."
  [text]
  (<= (rf.mcp-base.overflow/token-estimate text) rf.mcp-base.overflow/default-max-tokens))

(defn marker-text?
  "Is `text` (the rendered EDN text of a response's first content slot)
  a wire-bounded `:rf.mcp/*` marker envelope — a COMPLETE, CLOSED,
  single-key marker map whose BODY is within the default cap?

  Returns true for `:rf.mcp/cache-hit` / `:rf.mcp/overflow` markers —
  the two envelopes the cache + cap steps emit themselves. The
  consumer reads its own platform's content text (`:text` from a
  Clojure map, `j/get :text` from a JS object) and passes the string
  here; this fn owns the shared recognition logic across hosts.

  Three gates, all required:

    1. A cheap leading-token PRE-FILTER — the EXACT marker key must be
       the first top-level key (not merely a prefix of it): a lookalike
       leading key such as `:rf.mcp/overflowed` or
       `:rf.mcp/cache-hit-extra` is NOT a marker (see §Exact-key match
       in envelope.md).
    2. A SIZE bound — the rendered text must estimate within the
       documented default cap (`marker-sized?`). Closure proves the
       wrapper is closed but NOT small; a single-key
       `{:rf.mcp/overflow {…100 KB…}}` is closed yet over-budget.
       Bounding the body is the BODY dimension of \"sub-cap by
       construction\": an over-budget single-key marker is NOT a marker
       and continues through cap enforcement (rf2-vd1uyn).
    3. A structural CONFIRMATION — the whole `text` must parse to a
       closed single-key map whose sole key is a marker key and whose
       body is a map. The pre-filter proves only the FIRST key; this
       proves the OUTER wrapper is closed. A mixed wrapper with an
       unexpected top-level sibling
       (`{:rf.mcp/overflow {...} :unexpected \"<big>\"}`), a trailing
       EDN form, a tagged literal, or a non-map root/body is NOT a
       marker and continues through cap enforcement (rf2-j538f7.20).
       Additive fields inside the body remain allowed — only the OUTER
       wrapper must be closed.

  Gates 1-3 together make the invariant the fast-path skip relies on TRUE:
  anything this fn calls a marker is a closed single-key `:rf.mcp/*` map
  GUARANTEED under the default cap.

  Nil-safe: a nil / non-string `text` is not a marker."
  [text]
  (boolean
    (and (string? text)
         (marker-sized? text)
         (some #(exact-marker-prefix? text %) marker-prefixes)
         (closed-marker-envelope? text))))
