(ns day8.re-frame2-xray.palette.fuzzy
  "Fuzzy subsequence scorer for the Xray command palette.

  Pure data; JVM-runnable so tests cover the scorer outside any
  browser dep. The algorithm follows the sublime-fuzzy / fzf-lite
  family: every query character must appear (in order, case-
  insensitively) as a subsequence of the candidate; the score
  rewards matches that land on word starts, consecutive runs, and
  prefix position, and penalises long unmatched gaps.

  ## Why we ship our own scorer

  There is no fuzzy implementation under `tools/xray/` or
  `implementation/core/`. Bringing in an npm dep would breach the
  bundle-isolation contract for the dev surface and pull a runtime
  dependency that production builds can't elide cleanly. ~60 lines
  of CLJC is the right cost.

  ## Score shape

  Returns `nil` when the candidate does not contain every query
  character as a subsequence. Otherwise returns the best `long` score over
  every placement that starts at an occurrence of the query's first
  character — so `ep` against `Open Epoch panel` scores the `Ep` of
  `Epoch`, not the `e` of `Open`. The score can be negative (gaps cost
  more than a short match earns):

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
  - Gap penalty: -1 per unmatched candidate char that sits AFTER the
    first match. Leading non-matches — before the first
    matched char — are free; every unmatched char once matching has
    begun is penalised, including the char immediately after a match
    and gaps anchored at candidate index 0 (a prefix-anchored match
    earns no free pass on its internal gaps). Note that a query
    is only a match when EVERY query char is consumed, so the final
    matched char is at-or-before the last penalised gap only when the
    candidate carries trailing chars after the full query is satisfied;
    those trailing chars are NOT walked (the loop returns on
    `(= qi q-len)`), so genuine trailing slack stays free.

  ## Tie-break

  The caller breaks equal scores: `sources/rank` sorts by score, then by
  the shorter label, then lexicographically.

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
  ;; No `^Character` hint: `contains?` needs none, and the tag would be a
  ;; JVM-only claim about a value that is a one-character STRING under
  ;; CLJS — see `char-code`. The set lookup itself is already portable,
  ;; because `\-` reads as that same one-character string.
  [ch]
  (contains? separators ch))

(defn- char-code
  "`ch`'s code unit, on either host.

  `(nth some-string idx)` hands back a `Character` on the JVM and a
  ONE-CHARACTER STRING in ClojureScript. `int` reads the code point of
  the former, but `cljs.core/int` is `(bit-or x 0)` — and JavaScript
  coerces a non-numeric string to 0 in a bitwise op. So with `int` both
  range checks below would answer FALSE for every character under
  CLJS, the camelCase and word-start bonuses would never fire in the
  browser, and the palette would rank its results by a scorer missing
  two of its four bonuses precisely where it is the only thing that
  runs."
  [ch]
  #?(:clj  (int ch)
     :cljs (.charCodeAt ^string ch 0)))

(defn- upper?
  "True when `ch` is an ASCII uppercase letter. Cheap predicate over a
  char range; avoids the platform-coupled `Character/isUpperCase` so
  the scorer stays CLJC-clean."
  [ch]
  (let [n (char-code ch)]
    (and (>= n 65) (<= n 90))))

(defn- lower?
  [ch]
  (let [n (char-code ch)]
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

(defn- lower-chars
  "`s` as a vector of each character lower-cased ON ITS OWN, so index `i`
  is always the character at index `i` of `s`. Lower-casing the whole
  string first would not keep that: `İ` (U+0130) lower-cases to two code
  units, shifting every later index."
  [s]
  (mapv #(str/lower-case (str %)) s))

(defn- score-from
  "The score of the greedy placement of `q-lc` (from `lower-chars`) that
  starts scanning `candidate` at index `start`, or nil when the query does
  not fit from there."
  [candidate c-lc q-lc start]
  (let [c-len (count c-lc)
        q-len (count q-lc)]
      (loop [ci start
             qi 0
             score 0
             last-match-idx -2]
        (cond
          (= qi q-len)
          score

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
                       ci))
              ;; Gap penalty: -1 per UNMATCHED candidate char that sits
              ;; AFTER the first match. The condition is
              ;; `(>= last-match-idx 0)` — a match has occurred —
              ;; so every post-match unmatched char is penalised
              ;; uniformly, including a prefix-anchored match's internal
              ;; gaps (first match at candidate index 0 → last-match-idx
              ;; 0) and the char immediately after a match. Leading
              ;; gaps (before the first match) stay free because
              ;; last-match-idx is still its -2 sentinel.
              (recur (inc ci)
                     qi
                     (if (>= last-match-idx 0)
                       (dec score)
                       score)
                     last-match-idx)))))))

(defn score-with-meta
  "Score `candidate` against `query`. Both args are strings.

  Returns `nil` when the query is not a case-insensitive subsequence
  of the candidate. Returns `{:score long}` otherwise — the best score
  over every start at an occurrence of the query's first character.

  Empty query short-circuits to a tiny non-zero score (`1`) — every
  candidate qualifies for empty-input mode and the caller's recency /
  boost weights dominate the order."
  [candidate query]
  (cond
    (nil? candidate) nil
    (nil? query)     nil
    (zero? (count query))
    {:score 1}

    :else
    (let [c-lc   (lower-chars candidate)
          q-lc   (lower-chars query)
          q0     (first q-lc)
          scores (keep (fn [start]
                         (when (= q0 (nth c-lc start))
                           (score-from candidate c-lc q-lc start)))
                       (range (count c-lc)))]
      (when (seq scores)
        {:score (apply max scores)}))))

