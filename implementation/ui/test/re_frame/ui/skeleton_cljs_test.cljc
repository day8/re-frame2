(ns re-frame.ui.skeleton-cljs-test
  "S1a classpath + build-id probe (rf2-vxgfnd.1), retargeted at the S1b
  compiler surface (rf2-vxgfnd.2).

  The :require forms are half the assertion — a missing or unloadable
  namespace fails this file at CLJS compile / JVM load time. The deftest
  pins real S1b surface so the requires are load-bearing and a green run
  is non-vacuous.

  Runs under three gates: the focused :node-test-ui build (`npm run
  test:ui`), the always-on :node-test gate, and this artefact's own JVM
  :test alias (`clojure -M:test` from implementation/ui/)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui :as ui]
            [re-frame.ui.compiler]
            [re-frame.ui.rules :as rules]
            #?(:cljs [re-frame.ui.runtime])))

(deftest ui-artefact-loads-with-real-surface
  (is (fn? ui/sub)
      "the re-frame.ui public surface loads on the cross-artefact classpath")
  (is (contains? rules/void-tags :br)
      "the conversion rule table is loaded"))
