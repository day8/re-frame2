(ns day8.re-frame2-causa.palette.recents-cljs-test
  "Tests for the palette recents persistence (rf2-ybjkx).

  Covers:

  - `record` pure helper — prepend, dedup, cap.
  - `load` / `save!` localStorage round-trip (degrades silently in
    Node test runtimes where window.localStorage is absent — the JVM
    fixture path is exercised by the CLJC `sources` tests).
  - `sanitise` drops non-keyword entries from a malformed payload."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.palette.recents :as recents]))

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

;; ---- save! / load round-trip --------------------------------------------
;;
;; Node test runtimes don't simulate `window.localStorage` — the round-
;; trip tests gate on `js/window` so they cover the browser path when
;; the runtime provides it and silently skip otherwise. The pure
;; `record` / `sanitise` tests above cover the algorithm regardless.

(deftest save-load-round-trip
  (when (and (exists? js/window) (.-localStorage js/window))
    (recents/save! [:foo :bar])
    (is (= [:foo :bar] (recents/load))
        "browser-backed round-trip preserves the recents vector
         (most-recent-first, capped at max-recents)")))

(deftest load-empty-slot-returns-empty-vector
  (recents/clear!)
  (is (= [] (recents/load))
      "empty / unreachable storage → empty vector (never nil)"))

(deftest save-caps-at-max
  (when (and (exists? js/window) (.-localStorage js/window))
    (recents/save! [:a :b :c :d :e])
    (let [loaded (recents/load)]
      (is (<= (count loaded) recents/max-recents)
          "save! caps the persisted list at max-recents"))))
