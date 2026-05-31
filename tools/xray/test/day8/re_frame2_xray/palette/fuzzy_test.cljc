(ns day8.re-frame2-xray.palette.fuzzy-test
  "Tests for the palette's fuzzy subsequence scorer (rf2-wm7z4).

  Pure-data CLJC: every assertion runs equally under JVM clojure.test
  and the cljs node-test runtime. The scorer is the perceived-
  quality lever for the whole palette — these tests pin its scoring
  rules so a future tweak can be challenged against concrete
  examples rather than vibes."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.palette.fuzzy :as fuzzy]))

(deftest empty-query-matches-everything-with-tiny-score
  (testing "empty query is the 'show everything' mode — the caller's
            recency / boost weights dominate the order"
    (is (= 1 (:score (fuzzy/score-with-meta "Open Time travel panel" ""))))
    (is (= 1 (:score (fuzzy/score-with-meta "anything" ""))))
    (is (nil? (:first-match (fuzzy/score-with-meta "anything" ""))))))

(deftest nil-inputs-are-safe
  (testing "nil candidate or nil query returns nil — defensive guard"
    (is (nil? (fuzzy/score-with-meta nil "foo")))
    (is (nil? (fuzzy/score-with-meta "foo" nil)))
    (is (nil? (fuzzy/score-with-meta nil nil)))))

(deftest non-matching-query-returns-nil
  (testing "missing chars → nil; partial match is not a match"
    (is (nil? (fuzzy/score "event-detail" "xyz")))
    (is (nil? (fuzzy/score "event-detail" "evtz"))
        "the t exists but z does not — whole query must match"))

  (testing "out-of-order chars → nil; subsequence must preserve order"
    (is (nil? (fuzzy/score "event-detail" "deve"))
        "d comes after e in candidate but query asks for de-ve — fail")))

(deftest case-insensitive-match
  (testing "uppercase query matches lowercase candidate"
    (is (some? (fuzzy/score "event-detail" "EV"))))
  (testing "lowercase query matches CamelCase candidate"
    (is (some? (fuzzy/score "EventDetail" "ed")))))

(deftest prefix-bonus-beats-mid-string-match
  (testing "match at index 0 outranks the same letters mid-string"
    (let [prefix-s (fuzzy/score "event-detail" "ev")
          mid-s    (fuzzy/score "the-event-feed" "ev")]
      (is (> prefix-s mid-s)
          "prefix should win — 'ev' as the start of 'event-detail' is
           a stronger signal than 'ev' inside 'the-event-feed'"))))

(deftest word-start-bonus-on-separator
  (testing "matched char following `-` scores higher than matched char
            inside a word run"
    ;; 'fl' at word starts vs inside a word
    (let [word-start (fuzzy/score "first-line" "fl")
          inside     (fuzzy/score "filling" "fl")]
      (is (> word-start inside)))))

(deftest camelcase-boundary-bonus
  (testing "uppercase-after-lowercase matched char gets the camel bonus"
    ;; ED in EventDetail are both camelcase boundaries
    (let [boundary (fuzzy/score "EventDetail" "ED")
          flat     (fuzzy/score "Editable" "Ed")]
      (is (> boundary flat)
          "ED on the EventDetail camel boundaries should beat Ed at
           the start of Editable"))))

(deftest consecutive-match-run-bonus
  (testing "consecutive matched chars score higher than scattered ones"
    (let [run        (fuzzy/score "abcdef" "abc")
          scattered  (fuzzy/score "axbxcx" "abc")]
      (is (> run scattered)
          "abc as a tight run scores higher than abc scattered"))))

(deftest indices-track-match-positions
  (testing "the per-char indices vector reflects where each query
            char landed in the candidate"
    (let [m (fuzzy/score-with-meta "event-detail" "edt")]
      ;; 'event-detail' indices: e=0 v=1 e=2 n=3 t=4 -=5 d=6 e=7 t=8 …
      ;; 'edt' matches e@0, d@6, t@8 (the t at index 4 is skipped because
      ;; the matcher takes the FIRST d after 0 then the FIRST t after 6).
      (is (= [0 6 8] (:indices m))
          "e at 0, then d at 6, then t at 8 in 'event-detail'"))))

(deftest first-match-tracks-leading-position
  (testing "first-match is the index of the first query char in the
            candidate — drives tie-break in the caller"
    (is (= 0 (:first-match (fuzzy/score-with-meta "event-detail" "ev"))))
    ;; 'the-event-detail' — the first 'e' is at index 2 ('t','h','e'),
    ;; so the greedy matcher takes 'e' at 2 then 'v' at 5.
    (is (= 2 (:first-match (fuzzy/score-with-meta "the-event-detail" "ev"))))
    (is (nil? (:first-match (fuzzy/score-with-meta "anything" ""))))))

(deftest match-predicate-mirrors-score-nil
  (testing "(match? c q) is (some? (score c q))"
    (is (true?  (fuzzy/match? "event-detail" "ev")))
    (is (false? (fuzzy/match? "event-detail" "xyz")))
    (is (true?  (fuzzy/match? "anything" ""))
        "empty query → match")))

(deftest gap-penalty-anchored-at-index-0
  ;; rf2-pwxhj — a prefix-anchored match (first matched char at
  ;; candidate index 0) must STILL accrue the -1/char internal-gap
  ;; penalty. Pre-fix the `(pos? last-match-idx)` guard let an
  ;; index-0-anchored match escape every gap penalty entirely.
  ;; Gap chars are plain letters (`x`), NOT separators — a separator
  ;; ('_', '-', …) would hand the following matched char a word-start
  ;; bonus and mask the gap penalty under test.
  (testing "internal gaps after an index-0 match ARE penalised"
    (let [tight  (fuzzy/score "ad"     "ad")   ;; a@0 prefix+word-start, d@1 run
          gappy  (fuzzy/score "axxxd"  "ad")]  ;; a@0 prefix+word-start, 3 gaps, d@4
      (is (> tight gappy)
          "the gappy candidate must rank BELOW the tight one — the three
           'x' chars between the index-0 match and the d each cost -1")
      ;; tight = 1+12(prefix)+8(word-start) (a) + 1+4(run) (d) = 26
      ;; gappy = 1+12+8 (a) - 3 (gaps) + 1 (d, no run) = 19
      (is (= 26 tight))
      (is (= 19 gappy)
          "a@0 prefix+word-start (21), then -1 per 'x' (×3), then d non-run (+1)"))))

(deftest gap-penalty-first-char-after-match-not-skipped
  ;; rf2-pwxhj — the FIRST unmatched char immediately after a match
  ;; must be penalised. Pre-fix the `gap-since-match?` latch was set one
  ;; iteration too late, so the first gap char after any match escaped.
  (testing "query 'bd' vs 'xbxxd' penalises BOTH gap chars after b@1"
    (let [tight (fuzzy/score "xbd"    "bd")    ;; b@1, d@2 run — no gap
          gappy (fuzzy/score "xbxxd"  "bd")]   ;; b@1, gaps at 2,3, d@4
      (is (> tight gappy)
          "both x's between b and d cost -1 each — neither escapes")
      ;; tight = 1 (b, no bonus) + 1+4 (d, run) = 6
      ;; gappy = 1 (b) - 2 (two gap x's) + 1 (d, no run) = 0
      (is (= 6 tight))
      (is (= 0 gappy)
          "b (+1), two internal gaps (-2), d non-run (+1) → 0")))

  (testing "leading gaps — before the first match — stay free"
    ;; Chars before the first matched char must NOT be penalised; the
    ;; gap penalty starts only once matching has begun. Both candidates
    ;; match 'b' mid-string (so neither earns prefix/word-start), and
    ;; the extra leading 'z's in the second must NOT lower its score.
    (is (= (fuzzy/score "zb"   "b")
           (fuzzy/score "zzzb" "b"))
        "extra leading slack before the first match must not cost
         anything — leading gaps are free")))

(deftest gap-penalty-ranks-tight-over-spread
  ;; The ranking the spec intends: an evenly-spread subsequence match
  ;; ranks below a tighter one even when both start at the same place.
  (testing "spread-out subsequence ranks below the tight run"
    ;; Plain-letter gaps ('x') so the penalty isn't masked by a
    ;; separator-driven word-start bonus on the following char.
    (let [tight   (fuzzy/score "abc"     "abc")
          spread  (fuzzy/score "axbxc"   "abc")]
      (is (> tight spread)
          "the two 'x' gaps each cost -1, so the tight run wins"))))

(deftest representative-palette-queries
  (testing "representative shortcuts the user is likely to type"
    ;; 'evdt' → event-detail wins by a wide margin
    (let [evdt-event-detail (fuzzy/score "Open Event detail panel" "evdt")
          evdt-trace        (fuzzy/score "Open Trace panel" "evdt")]
      (is (some? evdt-event-detail))
      (is (or (nil? evdt-trace) (> evdt-event-detail evdt-trace))))

    ;; 'cl' → 'Clear trace buffer' should match
    (is (some? (fuzzy/score "Clear trace buffer" "cl")))))
