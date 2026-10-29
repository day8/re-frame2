(ns day8.re-frame2-xray.palette.fuzzy-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-xray.palette.fuzzy :as fuzzy]))

(defn- score [candidate query]
  (:score (fuzzy/score-with-meta candidate query)))

(deftest non-matching-query-returns-nil
  (is (nil? (score "event-detail" "evtz")) "every query char must match")
  (is (nil? (score "event-detail" "deve")) "in order"))

(deftest word-start-bonus-on-separator
  (is (> (score "first-line" "fl")
         (score "filling" "fl"))))

(deftest camelcase-boundary-bonus
  ;; Also guards `char-code` on CLJS: `cljs.core/int` of a one-character
  ;; string is 0, which would zero every camelCase bonus in the browser.
  (is (> (score "EventDetail" "ED")
         (score "Editable" "Ed"))))

(deftest gap-penalty-anchored-at-index-0
  ;; Gaps after an index-0 match still cost -1 each. `x` is not a separator,
  ;; so no word-start bonus masks the penalty.
  (is (= 26 (score "ad" "ad")))       ; a 1+12+8, d 1+4 (run)
  (is (= 19 (score "axxxd" "ad"))))   ; a 21, three gaps -3, d 1

(deftest gap-penalty-first-char-after-match-not-skipped
  ;; The first gap char after a match is penalised too; the leading `x`
  ;; before the first match stays free.
  (is (= 6 (score "xbd" "bd")))       ; b 1, d 1+4 (run)
  (is (= 0 (score "xbxxd" "bd"))))    ; b 1, two gaps -2, d 1

(deftest the-best-start-wins
  ;; `ep` scores the `Ep` of `Epoch` (word start 1+8, run 1+4), not the
  ;; `e` of `Open` followed by a gap to a later `p`.
  (is (= 14 (score "Open Epoch panel" "ep"))))

(deftest a-character-that-lower-cases-to-two-code-units-does-not-shift-the-scan
  ;; `İ` (U+0130) lower-cases to two code units; a whole-string lower-case
  ;; would index past the candidate's end on a full scan.
  (is (nil? (score "event :user/İsim" "zz")))
  (is (some? (score "event :user/İsim" "sim"))))
