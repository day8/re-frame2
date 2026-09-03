(ns re-frame.routing.match
  "Route-pattern parsing, validation, and URL-against-pattern matching
  for re-frame.routing.

  Pattern compilation validates and parses once to derive rank, regex,
  capture names, and optional-group metadata. `match-against` applies that
  compiled form and decodes captures. Per Spec 012 §Route ranking algorithm
  and §Bidirectional URL ↔ params.

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [re-frame.error :as rf.error]
            [re-frame.routing.url :as rf.routing.url]))

;; ---- registration ---------------------------------------------------------

(defn segment-end
  "Scan forward from `start` in `pattern` (length `n`) until a
  segment-boundary char is hit; return the index of that boundary (or
  `n` if none). The boundary set is always {/, {, }}; the 4-arity
  additionally treats `?` as a boundary when `?-boundary?` is truthy.
  Pure helper used by the param / splat / static branches of
  `parse-pattern`. The 3-arity (defaults `?-boundary?` to true) suits
  param / splat scanners; the static-segment branch passes false so a
  `?` inside a static segment doesn't truncate the static run."
  ([^String pattern n start] (segment-end pattern n start true))
  ([^String pattern n start ?-boundary?]
   (loop [idx start]
     (cond
       (>= idx n) idx
       (let [c (.charAt pattern idx)]
         (or (= c \/) (= c \{) (= c \})
             (and ?-boundary? (= c \?)))) idx
       :else (recur (inc idx))))))

(defn- regex-escape
  "Quote a string for use as a regex literal. Portable across JVM
  (java.util.regex.Pattern/quote) and CLJS (manual escape table)."
  [s]
  #?(:clj  (java.util.regex.Pattern/quote s)
     :cljs (clojure.string/replace s
                                   #"[\\^$.|?*+()\[\]{}]"
                                   #(str "\\" %))))

;; ---- route-pattern validation -------------------------------------------
;; Spec 012's path-pattern grammar is deliberately small. Enforce it at
;; registration time so invalid patterns fail at the authoring boundary
;; rather than producing surprising matcher/URL-emitter behaviour later.

(def ^:private route-name-re
  #"^[A-Za-z][A-Za-z0-9_-]*$")

(defn- route-pattern-error!
  ;; Canonical thrown-error shape per Spec 009: :rf.error/id is the
  ;; discriminator, :where names the public surface (reg-route compiles
  ;; the pattern at registration), :recovery + :reason complete the
  ;; required slots. Per-site :route-id / :pattern / :index merge on top.
  [route-id pattern reason index]
  (rf.error/throw-error!
    :rf.error/invalid-route-pattern
    'rf/reg-route
    reason
    {:recovery :no-recovery
     :extra    (cond-> {:route-id route-id
                        :pattern  pattern}
                 (some? index) (assoc :index index))}))

(defn- valid-route-name? [s]
  (boolean (and (seq s) (re-matches route-name-re s))))

(defn- validate-route-name!
  [route-id pattern nm start kind]
  (when-not (valid-route-name? nm)
    (route-pattern-error!
      route-id pattern
      (str kind " name must be a bare identifier: [A-Za-z][A-Za-z0-9_-]*")
      start)))

(defn- reserved-literal-char? [ch]
  (or (= ch \:) (= ch \*) (= ch \{) (= ch \})
      (= ch \?)))

(defn- validate-literal-segment!
  [route-id pattern segment start]
  (cond
    (empty? segment)
    (route-pattern-error! route-id pattern "empty path segments are not allowed" start)

    (some reserved-literal-char? segment)
    (route-pattern-error!
      route-id pattern
      "literal path segments must percent-encode reserved characters (: * { } ?)"
      start)))

(defn- validate-optional-group!
  "Validate a `{...}?` optional group starting at `start`; return the
  cursor position immediately after the trailing `?`."
  [route-id pattern start]
  (let [n     (count pattern)
        close (.indexOf ^String pattern "}" start)]
    (when (neg? close)
      (route-pattern-error! route-id pattern "optional groups must close with `}?`" start))
    (when (or (>= (inc close) n)
              (not= \? (.charAt ^String pattern (inc close))))
      (route-pattern-error! route-id pattern "optional groups must end with `}?`" close))
    (when (>= (inc start) close)
      (route-pattern-error!
        route-id pattern
        "optional groups must not be empty"
        start))
    (let [body (subs pattern (inc start) close)]
      (when (or (clojure.string/includes? body "{")
                (clojure.string/includes? body "}"))
        (route-pattern-error! route-id pattern "nested optional groups are not part of the grammar" start))
      (when (clojure.string/includes? body "?")
        (route-pattern-error! route-id pattern "`?` is reserved for the optional-group suffix" start))
      (when (or (= "/" body)
                (clojure.string/includes? body "//")
                (clojure.string/ends-with? body "/"))
        (route-pattern-error! route-id pattern "optional groups may not contain empty segments" start))
      ;; The group owns a slash-prefixed sub-pattern:
      ;; `{/:slug}?`, `{/guide}?`, `{/:base}?`. The slash-OUTSIDE spelling
      ;; (`{:base}?`, a `{` directly after a `/` with a `:`-first body) is
      ;; rejected. The leading `/` lives inside the braces, making the group
      ;; a self-contained optional segment
      ;; wherever it appears (leading, e.g. `{/:base}?/about`, or trailing,
      ;; e.g. `/articles/:id{/:slug}?`).
      (when-not (= \/ (.charAt ^String body 0))
        (route-pattern-error!
          route-id pattern
          "optional groups must wrap a slash-prefixed sub-pattern, e.g. `{/:id}?` or `{/guide}?` (the `/` goes INSIDE the braces)"
          start))
      (doseq [segment (rest (clojure.string/split body #"/"))]
        (cond
          (clojure.string/starts-with? segment ":")
          (validate-route-name! route-id pattern (subs segment 1) start "param")

          (clojure.string/starts-with? segment "*")
          (route-pattern-error! route-id pattern "splats are not allowed inside optional groups" start)

          :else
          (validate-literal-segment! route-id pattern segment start))))
    (+ close 2)))

(defn validate-route-pattern!
  "Validate `pattern` against Spec 012's path-pattern grammar at the
  authoring boundary (`reg-route` calls this at registration time).
  Returns `true` on a well-formed pattern; throws
  `:rf.error/invalid-route-pattern` (canonical thrown-error shape, per
  Spec 009 — `:where 'rf/reg-route`, `:route-id`, `:pattern`, and the
  offending `:index`) on the first violation. Enforcing the grammar here
  means a malformed `:path` fails LOUDLY when the route is registered,
  naming the bad character position, rather than producing surprising
  matcher / URL-emitter behaviour later at nav time. Grammar rules
  enforced: leading `/`; no empty segments; named params (`:name`) and
  splats (`*name`) occupy a whole segment with bare-identifier names; at
  most one splat, which must be final; literal segments percent-encode
  the reserved chars (`: * { } ?`); and `{...}?` optional groups close
  with `}?`, are non-empty, non-nested, wrap a slash-prefixed sub-pattern
  (the canonical slash-inside spelling, `{/:id}?`),
  and contain no splats. A pattern may also OPEN with a leading optional
  group (`{/:base}?/about`)."
  [route-id pattern]
  (cond
    (not (string? pattern))
    (route-pattern-error! route-id pattern ":path is required and must be a string" nil)

    (empty? pattern)
    (route-pattern-error! route-id pattern ":path must not be empty" 0)

    ;; A pattern must start with `/` (a segment) OR `{` (a LEADING optional
    ;; group — the canonical slash-inside form for an optional prefix, e.g.
    ;; `{/:base}?/about`; per Spec 012 §Path-pattern grammar rule 2). The
    ;; group carries its own leading slash inside the braces,
    ;; so a leading group needs no `/` before it.
    (not (or (= \/ (.charAt ^String pattern 0))
             (= \{ (.charAt ^String pattern 0))))
    (route-pattern-error! route-id pattern ":path must start with `/` or an optional group `{/…}?`" 0)

    (= "/" pattern)
    true

    :else
    (do
      (let [n (count pattern)]
        ;; Start at 0 when the pattern opens with a leading optional group
        ;; (`{…}?`), at 1 when it opens with the usual leading `/`.
        (loop [i (if (= \{ (.charAt ^String pattern 0)) 0 1)
               splat-seen? false]
          (when (< i n)
            (let [ch (.charAt ^String pattern i)]
              (cond
                (= ch \/)
                (do
                  (when (or (= i (dec n))
                            (= \/ (.charAt ^String pattern (inc i))))
                    (route-pattern-error! route-id pattern "empty path segments are not allowed" i))
                  (recur (inc i) splat-seen?))

                (= ch \{)
                (recur (validate-optional-group! route-id pattern i) splat-seen?)

                (= ch \})
                (route-pattern-error! route-id pattern "`}` appears without a matching optional-group opener" i)

                (= ch \?)
                (route-pattern-error! route-id pattern "`?` is reserved for the optional-group suffix" i)

                (= ch \:)
                (do
                  (when-not (or (= i 1)
                                (= \/ (.charAt ^String pattern (dec i))))
                    (route-pattern-error! route-id pattern "named params must occupy a whole path segment" i))
                  (let [start (inc i)
                        end   (segment-end pattern n start)
                        nm    (subs pattern start end)]
                    (validate-route-name! route-id pattern nm start "param")
                    (recur end splat-seen?)))

                (= ch \*)
                (do
                  (when-not (or (= i 1)
                                (= \/ (.charAt ^String pattern (dec i))))
                    (route-pattern-error! route-id pattern "splats must occupy a whole path segment" i))
                  (when splat-seen?
                    (route-pattern-error! route-id pattern "at most one splat is allowed" i))
                (let [start (inc i)
                      end   (segment-end pattern n start)
                      nm    (subs pattern start end)]
                  (when-not (and (= pattern "/*") (empty? nm))
                    (validate-route-name! route-id pattern nm start "splat"))
                  (when-not (= end n)
                    (route-pattern-error! route-id pattern "splats must be the final path segment" i))
                  (recur end true)))

                :else
                (let [end (loop [j i]
                            (if (or (>= j n)
                                    (= \/ (.charAt ^String pattern j))
                                    (= \{ (.charAt ^String pattern j))
                                    (reserved-literal-char? (.charAt ^String pattern j)))
                              j
                              (recur (inc j))))
                      segment (subs pattern i end)]
                  (validate-literal-segment! route-id pattern segment i)
                  (recur end splat-seen?)))))))
      true)))

(defn canonical-route-pattern
  "Canonicalise an author-supplied `:path` pattern for registration:
  strips trailing slashes so `/cart` and `/cart/` register the same
  route (Spec 012 trailing-slash equivalence), with `/` itself
  preserved. `reg-route` runs this before parsing so the stored pattern
  is already canonical; the incoming-URL side normalises identically via
  `registry/normalize-match-path` (both are thin wrappers over the
  shared `rf.routing.url/strip-trailing-slashes`, so the two surfaces cannot
  drift). A non-string `pattern` is returned unchanged — the type error
  is caught downstream by `validate-route-pattern!`, which names the
  route."
  [pattern]
  ;; Gate on `string?` before delegating to the shared
  ;; `rf.routing.url/strip-trailing-slashes` (a non-string :path is caught by
  ;; `validate-route-pattern!`).
  (if (string? pattern)
    (rf.routing.url/strip-trailing-slashes pattern)
    pattern))

;; ---- single-pass pattern parser ------------------------------------------
;; Per Spec 012 §Route ranking algorithm + §Bidirectional URL ↔ params.
;; `parse-pattern` derives the rank tuple, the match-time regex, the
;; capture names, AND the per-optional-group lookup `route-url` uses
;; from a single left-to-right walk of the pattern string. Loop state:
;;   i      — cursor index into pattern
;;   depth  — optional-group nesting depth
;;   parts  — accumulating regex string fragments
;;   names  — captured param names left-to-right (regex-group order)
;;   group-stack — stack of open optional-group cursor indices; on '{'
;;       we push the group-open index, on '}' we pop and record the
;;       close-end position so route-url can skip past an elided group
;;   inner  — output {group-open-idx → {:inner-names [...] :close-end <pos>}}
;;   counts — {:static :named :splat :optional :total} for the rank tuple.

(defn parse-pattern
  "Single-pass parser for a Spec 012 path-pattern. Returns
  {:rank :regex :names :groups :pattern}. The leading 5 elements of
  `:rank` are the structural rank tuple (rules 1-5); `reg-route`
  appends `(- reg-index)` to form the canonical 6-tuple."
  [pattern]
  (let [n  (count pattern)
        i0 (if (and (pos? n) (= \/ (.charAt ^String pattern 0))) 1 0)]
    (loop [i           i0
           depth       0
           parts       ["^/?"]
           names       []
           inner       {}
           group-stack ()
           counts      {:static 0 :named 0 :splat 0 :optional 0 :total 0}]
      (if-not (< i n)
        (let [{:keys [static total splat optional named]} counts
              ;; Spec 012 §Route ranking algorithm rule 2: the catch-all
              ;; is EXACTLY the bare `/*` pattern — a single unnamed splat
              ;; with no other segments. A NAMED splat (`/*rest`) is a rest
              ;; param and out-ranks the catch-all, so it MUST NOT be
              ;; classified here. The bare splat carries an empty capture
              ;; name (validate-route-pattern! permits `/*` with empty nm);
              ;; a named splat records its name, so require the lone
              ;; captured name to be empty to qualify as catch-all.
              catch-all? (and (= 1 total) (= 1 splat)
                              (zero? static) (zero? named) (zero? optional)
                              (= [""] names))]
          {:regex   (re-pattern (apply str (conj parts "$")))
           :names   names
           ;; `:groups` maps each optional-group's opening '{' index to
           ;; `{:inner-names [...] :close-end <pos-after-}?>}`. route-url
           ;; reads `:inner-names` to decide whether to emit a group and
           ;; `:close-end` to skip past it when eliding.
           :groups  inner
           :pattern pattern
           ;; Spec 012 §Route ranking algorithm. The catch-all
           ;; discriminator (rule 2) is lifted AHEAD of total-length
           ;; (rule 3): the bare `/*` also matches the root URL `/` (the
           ;; splat captures the literal "/"), and a home route
           ;; `{:path "/"}` parses to total-length 0 while `/*` is
           ;; length 1 — so if total-length compared first, `/*` would
           ;; out-length the root and shadow it (rf2-1ugs5u). Putting the
           ;; catch-all bit before length demotes `/*` below `/` (and
           ;; every other concrete route). For any two NON-catch-all
           ;; patterns the catch-all bit ties (both 1), so the comparison
           ;; falls through to total-length exactly as before — only
           ;; rankings involving the bare `/*` change. Rules 4 and 5 are
           ;; boolean discriminators per
           ;; Spec 012 §Route ranking algorithm — "named params beat rest
           ;; params" / "exact routes beat optional-group routes" — not a
           ;; magnitude comparison. `(if (pos? n) 0 1)` therefore represents
           ;; only "any" versus "none", as the spec pseudocode requires.
           :rank    [static
                     (if catch-all? 0 1)
                     total
                     (if (pos? splat) 0 1)
                     (if (pos? optional) 0 1)]})
        (let [ch (.charAt ^String pattern i)]
          (cond
            (= ch \/)
            (recur (inc i) depth (conj parts "/") names inner group-stack counts)

            (= ch \:)
            (let [start (inc i)
                  end   (segment-end pattern n start)
                  nm    (subs pattern start end)
                  inner' (if (seq group-stack)
                           (update-in inner [(peek group-stack) :inner-names]
                                      (fnil conj []) nm)
                           inner)
                  counts' (cond-> counts
                            (zero? depth) (-> (update :named inc)
                                              (update :total inc)))]
              (recur end depth (conj parts "([^/]+)") (conj names nm)
                     inner' group-stack counts'))

            (= ch \*)
            (let [start (inc i)
                  end   (segment-end pattern n start)
                  nm    (subs pattern start end)
                  inner' (if (seq group-stack)
                           (update-in inner [(peek group-stack) :inner-names]
                                      (fnil conj []) nm)
                           inner)
                  counts' (cond-> counts
                            (zero? depth) (-> (update :splat inc)
                                              (update :total inc)))]
              (recur end depth (conj parts "(.+)") (conj names nm)
                     inner' group-stack counts'))

            (= ch \{)
            ;; Open optional group: push group-open index for later
            ;; inner-name collection. Seed the entry so an empty group
            ;; still gets `inner-names = []` (route-url's `every?` over
            ;; an empty seq is true → group emitted with just literal
            ;; segments).
            (recur (inc i) (inc depth) (conj parts "(?:") names
                   (assoc-in inner [i :inner-names]
                             (get-in inner [i :inner-names] []))
                   (conj group-stack i)
                   (update counts :optional inc))

            (= ch \})
            (let [i'        (inc i)
                  ?-suffix? (and (< i' n) (= \? (.charAt ^String pattern i')))
                  close-end (if ?-suffix? (inc i') i')
                  inner'    (assoc-in inner [(peek group-stack) :close-end] close-end)]
              (recur close-end
                     (dec depth)
                     (cond-> (conj parts ")") ?-suffix? (conj "?"))
                     names
                     inner'
                     (pop group-stack)
                     counts))

            :else
            (let [end (segment-end pattern n (inc i) false)
                  static-seg (subs pattern i end)
                  counts' (cond-> counts
                            (zero? depth) (-> (update :static inc)
                                              (update :total inc)))]
              (recur end depth (conj parts (regex-escape static-seg)) names
                     inner group-stack counts'))))))))

;; ---- pattern co-matchability (rf2-6gzobp) ----------------------------------
;; Spec 012 §Route ranking algorithm rule 6 scopes the MUST-warn
;; `:rf.warning/route-shadowed-by-equal-score` to routes with an equal
;; structural score ON THE SAME URL FAMILY — patterns that can actually
;; match a common URL. Equal rank alone is not ambiguity: `/x/:id` and
;; `/y/:slug` tie structurally, but no URL matches both, so rule 6's
;; registration-order tiebreak can never fire between them.
;;
;; Co-matchability is decided EXACTLY, by language intersection over
;; segment automata — NOT by comparing corresponding literal segments,
;; which has false negatives once optional groups shift positions
;; (`/a{/x}?/b` vs `/a/x{/b}?` share no literal column yet both match
;; `/a/x/b`) and once params cross positions (`/x/:id` vs `/:kind/y`
;; intersect at `/x/y`).
;;
;; The segment model mirrors the compiled-regex semantics `match-against`
;; applies, at segment granularity:
;;   - a literal segment matches exactly its canonical text (case-sensitive,
;;     %-encoding compared as written — exactly the `regex-escape`d literal);
;;   - a `:name` param matches exactly ONE arbitrary non-empty segment
;;     (`[^/]+`);
;;   - a final `*name` / bare `*` splat matches ONE OR MORE remaining
;;     segments (`(.+)`) — and, when the splat is the pattern's only atom
;;     (`/*` / `/*rest`, regex `^/?(.+)$`), also the zero-segment root URL
;;     `/` (the capture is the literal `/` — the rf2-1ugs5u root quirk);
;;   - a `{/…}?` optional group contributes its inner segments OR nothing
;;     (an epsilon branch).
;; Both patterns are canonical at registration (trailing slashes stripped by
;; `canonical-route-pattern`) and matched against identically-normalised
;; URLs (`registry/normalize-match-path`), so segment-level intersection
;; over these atoms coincides with regex-language intersection restricted
;; to the URLs `match-url` can ever receive.

(defn- optional-group-tokens
  "Tokenize a validated `{/seg…}?` group BODY (the text between the braces,
  starting with `/`) into its inner segment atoms — `[:lit text]` or
  `:param`. Splats are rejected inside groups at validation, and the body
  carries no empty segments, so the split is total."
  [body]
  (mapv (fn [seg]
          (if (clojure.string/starts-with? seg ":")
            :param
            [:lit seg]))
        (rest (clojure.string/split body #"/"))))

(defn- segment-tokens
  "Tokenize a canonical, validation-passed route pattern into
  segment-granularity atoms: `[:lit text]`, `:param`, `:splat`, or
  `[:opt [inner…]]`. Returns nil when the pattern is NOT segment-aligned —
  the degenerate (grammar-permitted but pathological) shapes where an
  optional group's boundary cuts inside a segment: raw text glued directly
  after `}?` (`{/x}?abc`), or a group opening right after a top-level `/`
  (`/a/{/x}?`, whose elided branch leaves an empty segment). Their
  languages are not unions of whole segments; `patterns-intersect?` treats
  nil as conservatively co-matchable so the Spec 012 rule-6 MUST-warn is
  never lost on them."
  [^String pattern]
  (let [n (count pattern)]
    (loop [i      (if (and (pos? n) (= \/ (.charAt pattern 0))) 1 0)
           tokens []]
      (if (>= i n)
        tokens
        (let [ch (.charAt pattern i)]
          (cond
            (= ch \/)
            ;; A `/` immediately followed by `{` yields an EMPTY segment
            ;; when the group elides — not segment-representable.
            (if (and (< (inc i) n) (= \{ (.charAt pattern (inc i))))
              nil
              (recur (inc i) tokens))

            (= ch \:)
            (recur (segment-end pattern n (inc i)) (conj tokens :param))

            (= ch \*)
            (recur (segment-end pattern n (inc i)) (conj tokens :splat))

            (= ch \{)
            (let [close (.indexOf pattern "}" i)
                  after (+ close 2)] ;; skip `}?`
              (if (and (< after n)
                       (not (or (= \/ (.charAt pattern after))
                                (= \{ (.charAt pattern after)))))
                nil ;; text glued onto `}?` — not segment-aligned
                (recur after
                       (conj tokens [:opt (optional-group-tokens
                                            (subs pattern (inc i) close))]))))

            :else
            (let [end (loop [j i]
                        (if (or (>= j n)
                                (= \/ (.charAt pattern j))
                                (= \{ (.charAt pattern j)))
                          j
                          (recur (inc j))))]
              (recur end (conj tokens [:lit (subs pattern i end)])))))))))

(defn- tokens->nfa
  "Compile segment tokens into a tiny epsilon-NFA over the segment
  alphabet. Returns `{:edges {state [[label to] …]}
  :epsilon-edges {state #{to}}
  :start 0 :accept <state> :root? <bool>}` where `label` is `[:lit text]`
  / `:param` / `:any`, `:epsilon-edges` carries the optional-group skip edges, and
  `:root?` marks the splat-only quirk (`/*` / `/*rest` additionally match
  the zero-segment root URL `/`)."
  [tokens]
  (loop [remaining-tokens (seq tokens)
         state            0
         edges            {}
         epsilon-edges    {}]
    (if-not remaining-tokens
      {:edges edges :epsilon-edges epsilon-edges :start 0 :accept state
       :root? (= [:splat] tokens)}
      (let [token (first remaining-tokens)]
        (cond
          (= :param token)
          (recur (next remaining-tokens) (inc state)
                 (update edges state (fnil conj []) [:param (inc state)])
                 epsilon-edges)

          (= :splat token)
          ;; Consume ≥1 segments then accept: pre —any→ post —any→ post.
          ;; (The splat is grammatically final, so `post` becomes :accept.)
          (let [post-state (inc state)]
            (recur (next remaining-tokens) post-state
                   (-> edges
                       (update state      (fnil conj []) [:any post-state])
                       (update post-state (fnil conj []) [:any post-state]))
                   epsilon-edges))

          (= :lit (first token))
          (recur (next remaining-tokens) (inc state)
                 (update edges state (fnil conj []) [token (inc state)])
                 epsilon-edges)

          :else ;; [:opt inner]
          (let [entry-state state
                [next-edges exit-state]
                (reduce (fn [[current-edges current-state] inner-token]
                          [(update current-edges current-state
                                   (fnil conj []) [inner-token (inc current-state)])
                           (inc current-state)])
                        [edges entry-state]
                        (second token))]
            (recur (next remaining-tokens) exit-state next-edges
                   (update epsilon-edges entry-state (fnil conj #{}) exit-state))))))))

(defn- epsilon-closure
  "The forward epsilon-closure of `state` under the NFA's optional-group
  skip edges."
  [epsilon-edges state]
  (loop [seen     #{state}
         frontier [state]]
    (if (empty? frontier)
      seen
      (let [fresh-states (remove seen (mapcat #(get epsilon-edges % #{}) frontier))]
        (recur (into seen fresh-states) (vec fresh-states))))))

(defn- labels-compatible?
  "True when some single URL segment satisfies BOTH edge labels: `:any`
  (splat) and `:param` match every non-empty segment; two literals are
  compatible iff their canonical texts are identical."
  [label-a label-b]
  (or (= :any label-a) (= :any label-b)
      (= :param label-a) (= :param label-b)
      (= label-a label-b)))

(defn patterns-intersect?
  "True when some URL path can match BOTH canonical route patterns — the
  Spec 012 §Route ranking algorithm rule-6 'same URL family' test behind
  `:rf.warning/route-shadowed-by-equal-score` (rf2-6gzobp). Decided by
  product-automaton reachability over the two patterns' segment automata
  (see the section comment above): the product walks pairs of states,
  advancing both automata one co-compatible segment at a time; a reachable
  pair of accepting states is a common URL. Exact for the segment-aligned
  grammar; the degenerate glued-group shapes (see `segment-tokens`)
  conservatively return true. Both arguments must be CANONICAL patterns
  (as produced by `canonical-route-pattern` / stored on route metadata)."
  [pattern-a pattern-b]
  (let [tokens-a (segment-tokens pattern-a)
        tokens-b (segment-tokens pattern-b)]
    (if (or (nil? tokens-a) (nil? tokens-b))
      true
      (let [nfa-a    (tokens->nfa tokens-a)
            nfa-b    (tokens->nfa tokens-b)
            close-a  (memoize #(epsilon-closure (:epsilon-edges nfa-a) %))
            close-b  (memoize #(epsilon-closure (:epsilon-edges nfa-b) %))
            ;; A state accepts when its closure reaches :accept — or, for
            ;; the splat-only `:root?` quirk, at the start state (the
            ;; zero-segment root URL `/` is also in the language).
            accept-a? (fn [state]
                        (or (contains? (close-a state) (:accept nfa-a))
                            (and (:root? nfa-a) (= state (:start nfa-a)))))
            accept-b? (fn [state]
                        (or (contains? (close-b state) (:accept nfa-b))
                            (and (:root? nfa-b) (= state (:start nfa-b)))))
            start     [(:start nfa-a) (:start nfa-b)]]
        (loop [queue   [start]
               visited #{start}]
          (if (empty? queue)
            false
            (let [[state-a state-b] (peek queue)]
              (if (and (accept-a? state-a) (accept-b? state-b))
                true
                (let [successors
                      (for [closed-state-a         (close-a state-a)
                            [label-a next-state-a] (get (:edges nfa-a) closed-state-a)
                            closed-state-b         (close-b state-b)
                            [label-b next-state-b] (get (:edges nfa-b) closed-state-b)
                            :when (labels-compatible? label-a label-b)]
                        [next-state-a next-state-b])
                      unvisited-successors (remove visited successors)]
                  (recur (into (pop queue) unvisited-successors)
                         (into visited unvisited-successors)))))))))))

;; ---- match-against --------------------------------------------------------

(defn match-against
  "Try to match url against the route's compiled pattern. Returns the
  params map (with %-decoded values) on success, nil on miss.

  Per Spec 012 §Routing failure semantics (rf2-wbvme): if any captured
  group is malformed percent-encoding (`safe-url-decode` returns nil
  for a non-nil group), the URL fails closed as a route-miss rather
  than throwing through the call site.

  Per Spec 012 §Path-pattern grammar (Optional segment group): a param
  inside an unmatched optional group is ABSENT, not nil-valued (rf2-yejde).
  An optional group that didn't participate in the match yields a nil
  regex capture; we drop such keys so the params map omits them entirely.
  This matters for routes carrying a `:params` schema: Malli `{:optional
  true}` governs KEY PRESENCE, so a present `{:slug nil}` would be rejected
  whereas an absent `:slug` validates. A legitimately empty-string capture
  (\"\") is non-nil and survives — only the regex-unmatched (nil) case drops."
  [compiled url]
  (let [{:keys [regex names]} compiled
        match-result (re-matches regex url)]
    (when match-result
      (let [groups  (if (sequential? match-result) (rest match-result) [])
            ;; Realise into a vector once — `decoded` is consumed twice
            ;; below (validity scan + zipmap), and a lazy-seq would be
            ;; walked (and `safe-url-decode` re-invoked) on each pass.
            decoded (mapv (fn [group]
                            (when group (rf.routing.url/safe-url-decode group)))
                          groups)]
        ;; A nil entry for a non-nil group means malformed %-encoding —
        ;; treat as no-match (route-miss, never throw).
        (when (every? (fn [[group decoded-value]]
                        (or (nil? group) (some? decoded-value)))
                      (map vector groups decoded))
          ;; After the malformed-%-encoding guard, any remaining nil
          ;; decoded value corresponds to a nil regex group — an unmatched
          ;; optional group. Strip those keys so the param is absent, not
          ;; nil-valued (rf2-yejde).
          (into {}
                (comp (filter (fn [[_ value]] (some? value)))
                      (map (fn [[param-name value]]
                             [(keyword param-name) value])))
                (map vector names decoded)))))))
