(ns re-frame.ssr.ring.headers
  "Header materialisation for the Ring host adapter.

  re-frame.ssr stores headers internally as an ordered vector of
  `[name value]` pairs (case-insensitive name match). Ring accepts
  headers as a map of name → string OR name → vector-of-strings;
  multiple values under one name go via a vector. We collapse repeated
  pairs into vectors so multi-valued headers (Set-Cookie, Vary,
  Link, ...) round-trip correctly."
  (:require [clojure.string :as str]
            [re-frame.interop :as interop]
            [re-frame.ssr.ring.cookie :as cookie]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

(defn merge-pair-into-header-map
  "Fold a `[name value]` header pair into the accumulating Ring headers
  map. The accumulator normally carries `nil`, `string`, or `vector`
  values — the SSR runtime's `set-header`/`append-header` fxs gate
  header NAMES (RFC 7230 token grammar) and VALUES (CR/LF/NUL) but do
  NOT coerce a value to a string, so a non-string scalar (number,
  keyword, boolean) can reach this fold verbatim. The first occurrence
  `assoc`s that scalar in; a repeated name must still collapse it into
  a multi-value vector. The `:else` arm handles that case the same way
  the `string?` arm does (existing scalar → `[existing v]`); without it
  the `cond` would return `nil`, silently wiping the ENTIRE accumulated
  header map mid-fold (Content-Type, Set-Cookie, everything folded so
  far) — a silent-failure header-loss bug.

  ## Dev-gated non-string warning (rf2-b0jlr)

  The fold tolerates a non-string value (the `:else` arm above) so a
  scalar that slipped past the fx-boundary name/value gates can't take
  down the whole header map. But a non-string value here is host-
  dependent and almost certainly a CALLER bug — Ring's `:headers`
  contract is string OR vector-of-strings, and a number / keyword /
  boolean reaching the wire is undefined behaviour at the servlet
  layer. Rather than silently coercing (`(str v)`) or silently passing
  it through, we surface it: a dev-gated `:warning` trace naming the
  offending header key and the value's type. Production builds elide
  the whole check (the `interop/debug-enabled?` gate + `trace/emit!`'s
  own Closure-DCE gate), keeping the hot fold branchless on the wire.
  Aligns with the no-silent-swallow posture (cf. the redirect-no-target
  warning in `pipeline`)."
  [m [k v]]
  (when (and interop/debug-enabled? (not (string? v)))
    (trace/emit! :warning :rf.ssr/ssr-non-string-header-value
                 {:where      :ssr-ring/merge-pair-into-header-map
                  :header     k
                  :value-type (some-> v class .getName)
                  :reason     (str "header " (pr-str k) " carries a non-string "
                                   "value of type "
                                   (or (some-> v class .getName) "nil")
                                   " — Ring header values must be strings (or a "
                                   "vector of strings); the fold passes it through "
                                   "verbatim, but this is host-dependent and almost "
                                   "certainly a caller bug")
                  :recovery   :warned-and-passed-through}))
  (let [existing (get m k)]
    (cond
      (nil? existing)        (assoc m k v)
      (vector? existing)     (assoc m k (conj existing v))
      :else                  (assoc m k [existing v]))))

(defn append-set-cookies
  "For every cookie map in the response's :cookies vector, append one
  Set-Cookie header to the headers map. Returns the updated headers
  map."
  [headers-map cookies]
  (reduce
    (fn [m c]
      (merge-pair-into-header-map m ["Set-Cookie"
                                     (cookie/cookie->set-cookie-header c)]))
    headers-map
    cookies))

(defn headers->ring-map+default-content-type
  "Collapse an ordered vec-of-[name value] pairs into Ring's
  `{name string-or-vec}` shape, defaulting `Content-Type` to
  `content-type` (case-insensitive) in the SAME single pass when the
  pairs don't already declare one (rf2-uj9z8). Folds each pair into the
  accumulator AND lower-cases each name once to flag whether
  `Content-Type` was seen, appending the default at the end iff not and
  iff `content-type` is non-nil.

  Case-insensitive Content-Type detection (rf2-depii) covers every
  casing variant (`CONTENT-TYPE`, `CoNtEnT-TyPe`, …) per RFC 7230 §3.2
  (header names are tokens; tokens are case-insensitive) — a mixed-case
  caller header must NOT get a duplicate default appended.

  Ordering: the PER-NAME ordering of multi-valued headers (multiple
  `Set-Cookie` entries) is preserved by `merge-pair-into-header-map`'s
  `conj`. The ACROSS-NAME ordering is the JDK's HAMT iteration order —
  stable but not first-seen order; Ring servers don't promise cross-name
  header order on the wire either."
  [pairs content-type]
  (let [step (fn [[m saw-ct?] [k v :as pair]]
               [(merge-pair-into-header-map m pair)
                (or saw-ct?
                    (= "content-type" (str/lower-case (str k))))])
        [m saw-ct?] (reduce step [{} false] pairs)]
    (if (or saw-ct? (nil? content-type))
      m
      (assoc m "Content-Type" content-type))))
