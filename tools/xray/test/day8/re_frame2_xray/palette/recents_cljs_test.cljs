(ns day8.re-frame2-xray.palette.recents-cljs-test
  "The pure `record` algebra behind the palette's recents list. The
  `save!` / `load` round-trip needs real storage and lives in
  `recents-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.palette.recents :as recents]))

(deftest record-caps-at-max
  (is (= [:d :c :b] (reduce recents/record [] [:a :b :c :d]))
      "newest first; the cap drops the oldest entry"))
