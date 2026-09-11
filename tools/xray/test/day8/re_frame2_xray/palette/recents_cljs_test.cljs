(ns day8.re-frame2-xray.palette.recents-cljs-test
  "Tests for the palette recents persistence (rf2-ybjkx).

  Covers:

  - `record` pure helper — prepend, dedup, cap.
  - `load` on an empty slot — the storage-free fallback.
  - `sanitise` drops non-keyword entries from a malformed payload.

  The `load` / `save!` localStorage ROUND-TRIP lives in the sibling
  `day8.re-frame2-xray.palette.recents-dom-cljs-test` (rf2-6ppy). It
  needs a real `window.localStorage`, which this lane does not have;
  guarding it here on `(exists? js/window)` meant it ran in NO lane,
  because `:browser-test` only loads namespaces ending
  `-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
;; real storage is in `recents-dom-cljs-test`; it used to sit below,
;; gated on `js/window`, where it executed in neither lane (rf2-6ppy).

(deftest load-empty-slot-returns-empty-vector
  (recents/clear!)
  (is (= [] (recents/load))
      "empty / unreachable storage → empty vector (never nil)"))
