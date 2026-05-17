(ns day8.re-frame2-causa.palette.fuzzy
  "Fuzzy subsequence scorer for the Causa command palette
  (rf2-wm7z4).

  Pure data; JVM-runnable so tests cover the scorer outside any
  browser dep. The algorithm follows the sublime-fuzzy / fzf-lite
  family: every query character must appear (in order, case-
  insensitively) as a subsequence of the candidate; the score
  rewards matches that land on word starts, consecutive runs, and
  prefix position, and penalises long unmatched gaps.

  ## Why we ship our own scorer

  Verified-redundant (rf2-wm7z4 worker dispatch): there is no
  existing fuzzy implementation under `tools/causa/` or
  `implementation/core/`. Bringing in an npm dep would breach the
  bundle-isolation contract for the dev surface and pull a runtime
  dependency that production builds can't elide cleanly. ~60 lines
  of CLJC is the right cost.

  ## Score shape

  Returns `nil` when the candidate does not contain every query
  character as a subsequence. Returns a `long` ≥ 0 otherwise:

  - Base: each matched char contributes +1.
  - Word-start bonus: +8 when the char follows a separator
    (`-`, `_`, `/`, `.`, `:`, ` `) or sits at index 0.
  - CamelCase bonus: +6 when the matched char is uppercase and the
    preceding char is lowercase (e.g. `EventDetail` — `E` and `D`
    both qualify).
  - Run bonus: +4 for each consecutive char immediately following
    another matched char. Rewards tight typing (`evdet` → matches
    `event-detail` with a run on `ev` and `det`).
  - Prefix bonus: +12 when the very first char matches at index 0.
  - Gap penalty: -1 per unmatched candidate char that sits BETWEEN
    matched chars (trailing/leading non-matches are free).

  ## Tie-break

  Equal scores: pick the candidate with the earlier first-match
  index, then the shorter candidate. `score-with-meta` returns the
  raw score AND the first-match index so the caller can sort
  deterministically.

  ## Boundary split (spec/007-UX-IA.md §Indexed sources)

  The spec calls for splits on camelCase / kebab-case / namespace
  boundaries. The scorer already rewards matches on those boundaries
  via the word-start / camelCase bonuses; the caller does not need
  to pre-segment the candidate. A query like `evdt` against
  `event-detail` scores higher than `event-detail` does against a
  query like `vntd` precisely because the matched `e`+`d` land on
  boundaries."
  (:require [clojure.string :as str]))

(def ^:private separators
  "Characters that mark word boundaries for the word-start bonus.
  Mirrors the spec's camelCase / kebab-case / namespace-boundary
  split: `-` (kebab), `_` (snake), `/` `.` `:` (namespace + path),
  ` ` (prose). Stored as a set for O(1) lookup."
  #{\- \_ \/ \. \: \space \tab \> \<})

(defn- separator?
  [^Character ch]
  (contains? separators ch))

(defn- upper?
  "True when `ch` is an ASCII uppercase letter. Cheap predicate over a
  char range; avoids the platform-coupled `Character/isUpperCase` so
  the scorer stays CLJC-clean (CLJS has the same range check)."
  [ch]
  (let [n (int ch)]
    (and (>= n 65) (<= n 90))))

(defn- lower?
  [ch]
  (let [n (int ch)]
    (and (>= n 97) (<= n 122))))

(defn- word-start?
  "True when `idx` sits at a boundary: index 0, or the preceding char
  is a separator, or the current char is uppercase and the preceding
  char is lowercase (camelCase boundary)."
  [candidate idx]
  (cond
    (zero? idx) true
    :else
    (let [prev (nth candidate (dec idx))
          curr (nth candidate idx)]
      (or (separator? prev)
          (and (upper? curr) (lower? prev))))))

(defn score-with-meta
  "Score `candidate` against `query`. Both args are strings.

  Returns `nil` when the query is not a case-insensitive subsequence
  of the candidate. Returns `{:score long :first-match int :indices
  [int ...]}` otherwise. The `:indices` vector is the per-char match
  positions in the candidate — useful for caller-side highlight
  rendering once the v1.0 styling pass lands.

  Empty query short-circuits to a tiny non-zero score (`1`) with no
  first-match — every candidate qualifies for empty-input mode and
  the caller's recency / boost weights dominate the order."
  [candidate query]
  (cond
    (nil? candidate) nil
    (nil? query)     nil
    (zero? (count query))
    {:score 1 :first-match nil :indices []}

    :else
    (let [c-lc (str/lower-case candidate)
          q-lc (str/lower-case query)
          c-len (count c-lc)
          q-len (count q-lc)]
      (loop [ci 0
             qi 0
             score 0
             last-match-idx -2
             first-match nil
             gap-since-match? false
             indices (transient [])]
        (cond
          (= qi q-len)
          {:score       score
           :first-match first-match
           :indices     (persistent! indices)}

          (= ci c-len)
          nil

          :else
          (let [cc-lc (nth c-lc ci)
                qc-lc (nth q-lc qi)
                cc    (nth candidate ci)]
            (if (= cc-lc qc-lc)
              (let [run?       (= ci (inc last-match-idx))
                    prefix?    (and (zero? ci) (zero? qi))
                    word-st?   (word-start? candidate ci)
                    camel-bnd? (and (upper? cc)
                                    (pos? ci)
                                    (lower? (nth candidate (dec ci))))
                    bonus      (cond-> 1
                                 prefix?     (+ 12)
                                 word-st?    (+ 8)
                                 camel-bnd?  (+ 6)
                                 run?        (+ 4))]
                (recur (inc ci)
                       (inc qi)
                       (+ score bonus)
                       ci
                       (or first-match ci)
                       false
                       (conj! indices ci)))
              (recur (inc ci)
                     qi
                     (if (and gap-since-match? (pos? last-match-idx))
                       (dec score)
                       score)
                     last-match-idx
                     first-match
                     (pos? last-match-idx)
                     indices))))))))

(defn score
  "Convenience: return just the score (`long` or `nil`)."
  [candidate query]
  (when-let [m (score-with-meta candidate query)]
    (:score m)))

(defn match?
  "True iff `query` is a case-insensitive subsequence of `candidate`.
  Cheap pre-filter when the caller does not need the score."
  [candidate query]
  (boolean (score-with-meta candidate query)))
