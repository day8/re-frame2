(ns re-frame.ui.skeleton-cljs-test
  "S1a classpath + build-id probe (rf2-vxgfnd.1).

  The :require forms are half the assertion — a missing or unloadable
  stub fails this namespace at CLJS compile / JVM load time. The deftest
  pins the marker var so the requires are load-bearing and a green run
  is non-vacuous.

  Runs under three gates: the focused :node-test-ui build (`npm run
  test:ui` — the ns matches its `^re-frame\\.ui\\..+-cljs-test$`
  regexp), the always-on :node-test gate (`cljs-test$` matches), and
  this artefact's own JVM :test alias (`clojure -M:test` from
  implementation/ui/)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui :as ui]
            [re-frame.ui.compiler]
            #?(:cljs [re-frame.ui.client])))

(deftest ui-artefact-skeleton-loads
  (is (= :day8/re-frame2-ui ui/artefact-id)
      "day8/re-frame2-ui skeleton nses load on the cross-artefact classpath"))
