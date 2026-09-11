(ns day8.re-frame2-xray.palette.recents-dom-cljs-test
  "Browser-lane half of the palette recents persistence tests (rf2-ybjkx),
  promoted out of `recents-cljs-test` under rf2-6ppy.

  WHY A SEPARATE NAMESPACE. The sibling
  `day8.re-frame2-xray.palette.recents-cljs-test` holds the pure `record`
  / `sanitise` algebra, which needs no host storage and belongs on the
  fast `:node-test` lane. The `save!` / `load` round-trip needs a real
  `window.localStorage`, which the node lane does not provide.

  Guarding those rows in the node file — `(when (and (exists? js/window)
  (.-localStorage js/window)) ...)` — made them execute in NEITHER lane:
  skipped under `:node-test` for want of storage, and never loaded by
  `:browser-test`, whose `:ns-regexp` is `.*-dom-cljs-test$`. A namespace
  must end `-dom-cljs-test` to reach the browser build at all, so the two
  round-trip rows live here, UNGUARDED, and run against real
  localStorage.

  These assertions had never executed before this namespace existed. A
  failure here is therefore evidence about `recents/save!` / `recents/load`
  arriving for the first time, not a regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame2-xray.palette.recents :as recents]))

(use-fixtures :each
  {:before (fn [] (recents/clear!))
   :after  (fn [] (recents/clear!))})

(deftest save-load-round-trip
  (recents/save! [:foo :bar])
  (is (= [:foo :bar] (recents/load))
      "browser-backed round-trip preserves the recents vector
       (most-recent-first, capped at max-recents)"))

(deftest save-caps-at-max
  (recents/save! [:a :b :c :d :e])
  (let [loaded (recents/load)]
    (is (<= (count loaded) recents/max-recents)
        "save! caps the persisted list at max-recents")))
