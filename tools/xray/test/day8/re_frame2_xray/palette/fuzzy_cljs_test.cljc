(ns day8.re-frame2-xray.palette.fuzzy-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-xray.palette.fuzzy :as fuzzy]))

(deftest non-matching-query-returns-nil
  (is (nil? (fuzzy/score "event-detail" "evtz")) "every query char must match")
  (is (nil? (fuzzy/score "event-detail" "deve")) "in order"))

(deftest word-start-bonus-on-separator
  (is (> (fuzzy/score "first-line" "fl")
         (fuzzy/score "filling" "fl"))))

(deftest camelcase-boundary-bonus
  ;; Also guards `char-code` on CLJS: `cljs.core/int` of a one-character
  ;; string is 0, which would zero every camelCase bonus in the browser.
  (is (> (fuzzy/score "EventDetail" "ED")
         (fuzzy/score "Editable" "Ed"))))

(deftest gap-penalty-anchored-at-index-0
  ;; Gaps after an index-0 match still cost -1 each. `x` is not a separator,
  ;; so no word-start bonus masks the penalty.
  (is (= 26 (fuzzy/score "ad" "ad")))       ; a 1+12+8, d 1+4 (run)
  (is (= 19 (fuzzy/score "axxxd" "ad"))))   ; a 21, three gaps -3, d 1

(deftest gap-penalty-first-char-after-match-not-skipped
  ;; The first gap char after a match is penalised too; the leading `x`
  ;; before the first match stays free.
  (is (= 6 (fuzzy/score "xbd" "bd")))       ; b 1, d 1+4 (run)
  (is (= 0 (fuzzy/score "xbxxd" "bd"))))    ; b 1, two gaps -2, d 1
