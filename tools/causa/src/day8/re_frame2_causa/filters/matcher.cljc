(ns day8.re-frame2-causa.filters.matcher
  "Pattern matching for Causa's IN/OUT filter pills (rf2-ak4ms).

  Per `tools/causa/spec/018-Event-Spine.md` §7 the filter system runs
  at the DATA layer (`:rf.causa/filtered-cascades` sub) — every consumer
  (event list, scrubber, Issues counter, palette verbs) reads the
  filtered list. The matcher in this ns is the pure data primitive
  the sub composes against the raw cascade list.

  ## Pattern syntax

  Spec/018 §7 calls out four supported pattern shapes; this matcher
  implements three first-class shapes (exact / prefix-glob / namespace)
  + the substring fallback:

  | Shape       | Example          | Matches                                   |
  |-------------|------------------|-------------------------------------------|
  | exact kw    | `:auth/login`    | event-id equal to the keyword             |
  | prefix glob | `:auth/*`        | event-id whose `(str id)` starts with `:auth/` |
  | namespace   | `:order`         | event-id whose namespace = `\"order\"`    |
  | substring   | `/login`         | event-id whose `(str id)` contains `/login` |

  Patterns may be supplied as keywords (`:auth/login`, `:auth/*`) or as
  strings (`\":auth/*\"`, `\"login\"`). Strings without a leading `:`
  fall through to substring; strings with a leading `:` are normalised
  to keywords first.

  ## Match composition (spec/018 §7)

      ACTIVE = (match-any-IN) AND NOT (match-any-OUT)

  - **No IN pills**  → show everything not blacklisted (OUT only).
  - **Some IN pills** → restrict to events matching ANY IN pattern,
    minus any OUT match.

  ## Why CLJC

  The matcher is pure data; JVM tests in
  `tools/causa/test/.../filters/matcher_test.cljc` exercise the shape
  without a CLJS runtime. The CLJS pill view + filtered-cascades sub
  compose against this ns at render-time."
  (:require [clojure.string :as str]))

;; ---- pattern compilation -------------------------------------------------

(defn- glob? [s]
  (str/ends-with? s "*"))

(defn- normalise-pattern
  "Coerce a pattern (keyword or string) into the canonical match shape.
  Returns `{:kind <:exact|:prefix|:substring|:never> :pattern <data>}`.

  Per spec/018 §7 the supported pattern shapes are:

    | input form    | example          | kind       |
    |---------------|------------------|------------|
    | exact kw      | `:auth/login`    | :exact     |
    | prefix glob   | `:auth/*`        | :prefix    |
    | bare kw       | `:mouse-move`    | :exact     |
    | str with `:`  | `\":auth/*\"`    | (parses)   |
    | bare str      | `\"/login\"`     | :substring |
    | empty / nil   |                  | :never     |

  Note: a bare unqualified keyword (e.g. `:mouse-move`) compiles to
  `:exact`, NOT to a namespace matcher. The bare-keyword case is the
  natural 'block this specific event-id' input — spec/018 §7's example
  `[× :mouse-move]` lands there. Namespace-style matching is achieved
  via the glob form (`:foo/*`)."
  [pattern]
  (cond
    (keyword? pattern)
    (let [s (str pattern)]
      (if (glob? s)
        ;; `:auth/*` → prefix `":auth/"`. Drop the trailing `*`.
        {:kind :prefix :pattern (subs s 0 (dec (count s)))}
        {:kind :exact :pattern pattern}))

    (string? pattern)
    (cond
      (str/blank? pattern)
      ;; A blank pattern matches nothing — guard so a half-filled pill
      ;; doesn't silently match every event.
      {:kind :never :pattern nil}

      (str/starts-with? pattern ":")
      (recur (keyword (subs pattern 1)))

      :else
      {:kind :substring :pattern pattern})

    :else
    {:kind :never :pattern nil}))

;; ---- single-pill match ---------------------------------------------------

(defn match-event-id?
  "True iff `event-id` matches the compiled `pattern-spec` (output of
  `normalise-pattern`).

  - `:exact`      — keyword equality
  - `:prefix`     — `(str event-id)` starts with the prefix
  - `:substring`  — `(str event-id)` contains the substring
  - `:never`      — always false (blank / malformed pill)

  `event-id` may be nil (an unrouted cascade carrying no event); nil
  never matches."
  [event-id {:keys [kind pattern]}]
  (cond
    (nil? event-id) false
    (= :never kind) false
    (= :exact kind) (= event-id pattern)

    (= :prefix kind)
    (let [s (str event-id)]
      (str/starts-with? s pattern))

    (= :substring kind)
    (str/includes? (str event-id) pattern)

    :else false))

(defn match-pill?
  "True iff the pill matches `event-id`. Pills are
  `{:pattern <kw-or-str>}`; an optional `:scope` set may be threaded
  in future (event-args / source-coord / tags scopes per spec/018 §7)
  — pre-alpha we match on event-id only.

  Returns false when the pill is malformed (missing pattern)."
  [{:keys [pattern]} event-id]
  (and (some? pattern)
       (match-event-id? event-id (normalise-pattern pattern))))

;; ---- cascade-level filtering --------------------------------------------

(defn- cascade-event-id
  "Pluck the event-id from a cascade — same shape `shell/event-id-of-
  cascade` plucks (`(first (:event cascade))`). Lifted here so the
  matcher stays a self-contained pure unit; the shell delegates."
  [cascade]
  (let [ev (:event cascade)]
    (when (vector? ev)
      (first ev))))

(defn cascade-matches?
  "True iff `cascade`'s event-id matches *any* pill in `pills`.

  - Empty / nil `pills` → false (no patterns to match against).
  - Otherwise → true on first pill match."
  [cascade pills]
  (boolean
    (when (seq pills)
      (let [event-id (cascade-event-id cascade)]
        (some #(match-pill? % event-id) pills)))))

(defn keep-cascade?
  "True iff `cascade` survives the active filter per spec/018 §7:

      keep = (no-IN-pills OR matches-IN) AND NOT (matches-OUT)

  Cascades that fail the keep test drop out of `:rf.causa/filtered-
  cascades`. The L2 event list, scrubber, Issues counter, palette
  verbs all read the filtered list — one filter, every consumer."
  [cascade {:keys [in out]}]
  (let [in-ok?  (or (empty? in)
                    (cascade-matches? cascade in))
        out-hit (cascade-matches? cascade out)]
    (and in-ok? (not out-hit))))

(defn filter-cascades
  "Apply `filters` to `cascades`, returning the surviving subseq in
  order. Pure — no I/O, no atoms read."
  [cascades filters]
  (filterv #(keep-cascade? % filters) cascades))
