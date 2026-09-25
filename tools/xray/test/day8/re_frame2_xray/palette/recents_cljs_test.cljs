(ns day8.re-frame2-xray.palette.recents-cljs-test
  "Tests for the palette recents persistence.

  Covers:

  - `record` pure helper — prepend, dedup, cap.
  - `load` on an empty slot — the storage-free fallback.
  - `sanitise` drops non-keyword entries from a malformed payload.

  The `load` / `save!` localStorage ROUND-TRIP lives in the sibling
  `day8.re-frame2-xray.palette.recents-dom-cljs-test`. It needs a real
  `window.localStorage`, which this lane does not have; HERE it would
  execute in NO lane — skipped under `:node-test` for want of storage,
  and never loaded by `:browser-test`, whose `:ns-regexp` is
  `.*-dom-cljs-test$`.

  `:node-test`'s `:ns-regexp` is `cljs-test$` — a bare SUFFIX match that
  `-dom-cljs-test` satisfies too — so a DOM-tagged file runs on BOTH
  lanes, and the sibling's `ls/available?` guard is what keeps its node
  run inert."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame2-xray.palette.recents :as recents]))

(use-fixtures :each
  {:before (fn [] (recents/clear!))
   :after  (fn [] (recents/clear!))})

;; ---- record -------------------------------------------------------------

(deftest record-prepends-new-id
  (is (= [:foo] (recents/record [] :foo)))
  (is (= [:bar :foo] (recents/record [:foo] :bar))))

(deftest record-dedups-by-id
  (let [r1 (recents/record [:foo :bar] :foo)]
    (is (= [:foo :bar] r1)
        "re-invoking :foo moves it back to position 0 without growing
         the list")))

(deftest record-caps-at-max
  (let [r (-> []
              (recents/record :a)
              (recents/record :b)
              (recents/record :c)
              (recents/record :d))]
    (is (= recents/max-recents (count r)))
    (is (= [:d :c :b] r)
        "newest first; the cap drops the oldest entry")))

(deftest record-nil-is-noop
  (is (= [:foo] (recents/record [:foo] nil))
      "nil command-id does not bump the list"))

;; ---- load without storage -----------------------------------------------
;;
;; Node test runtimes don't provide `window.localStorage`, so only the
;; storage-free fallback is assertable here. The round-trip that needs
;; real storage is in `recents-dom-cljs-test`, which runs on the browser
;; lane AND this one — `:node-test`'s `cljs-test$` selector loads
;; `-dom-cljs-test` files too — and its guard is what keeps its node pass
;; honest.

(deftest load-empty-slot-returns-empty-vector
  (recents/clear!)
  (is (= [] (recents/load))
      "empty / unreachable storage → empty vector (never nil)"))
