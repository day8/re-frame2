(ns day8.re-frame2-xray.palette.recents-dom-cljs-test
  "Browser-lane half of the palette recents tests: the `save!` / `load`
  round-trip needs a real `window.localStorage`.

  `:node-test`'s `cljs-test$` selector loads `-dom-cljs-test` namespaces
  too, so the row is guarded by `ls/available?` and asserts a skip marker
  there rather than holding zero assertions."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.palette.recents :as recents]))

(use-fixtures :each
  {:before (fn [] (recents/clear!))
   :after  (fn [] (recents/clear!))})

(deftest save-load-round-trip
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (recents/save! [:foo :bar])
      (is (= [:foo :bar] (recents/load))))))
