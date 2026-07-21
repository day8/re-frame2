(ns re-frame.ui.render-static-strip-jvm-test
  "rf2-zgezz (98.1 slice c — audit obligations #3 + #4): `render-static`'s
  host-root view-evidence strip. Two focused JVM proofs on the seam itself
  (`re-frame.ui.tree/strip-host-root-annotation` + `emit-static-html`), with the
  SSR emitter stubbed so no artefact is needed.

  #3 PROVENANCE — the strip removes ONLY the compiler-generated markers, scoped by
  the out-of-band `:rf.ui/host-root-annotation` provenance metadata, and PRESERVES
  programmer-authored attributes of the same spelling (nested OR an authored own
  value on the host root, under the rf2-x1nbv collision law). The pre-fix blunt
  recursive `dissoc` deleted authored nested attrs.

  #4 DEBUG-DISABLED BYPASS — when `interop/debug-enabled?` is false the compiler
  emitted no annotation and no provenance, so `emit-static-html` skips the strip
  ENTIRELY: the serialiser receives the SAME tree object (identical root/children
  identities), not a clone."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as interop]
            [re-frame.ui.tree :as tree]))

(def ^:private strip #'re-frame.ui.tree/strip-host-root-annotation)

(def ^:private canon
  {:data-rf2-source-coord "app.probe:widget:1:2"
   :data-rf-view ":app.probe/widget"})

;; ---------------------------------------------------------------------------
;; #3 PROVENANCE
;; ---------------------------------------------------------------------------

(deftest strip-removes-only-the-generated-markers
  (testing "a host root whose values equal the canonical annotation is stripped"
    (let [node (with-meta {:tag :div
                           :attrs {:data-rf2-source-coord "app.probe:widget:1:2"
                                   :data-rf-view ":app.probe/widget"
                                   :class "card"}}
                          {:rf.ui/host-root-annotation canon})
          out  (strip node)]
      (is (= {:class "card"} (:attrs out))
          "the two compiler markers are removed; the authored :class survives")))

  (testing "an AUTHORED own value on the host root (differs from canonical) survives"
    (let [node (with-meta {:tag :div
                           :attrs {:data-rf-view "authored-view" :class "card"}}
                          {:rf.ui/host-root-annotation canon})
          out  (strip node)]
      (is (= {:data-rf-view "authored-view" :class "card"} (:attrs out))
          "the rf2-x1nbv collision law is honoured — authored value preserved")))

  (testing "a NESTED authored attribute (no provenance metadata) is untouched"
    (let [tree (with-meta
                 {:tag :section
                  :attrs {:data-rf2-source-coord "app.probe:widget:1:2"
                          :data-rf-view ":app.probe/widget"}
                  :children [{:tag :span
                              :attrs {:data-rf-view "nested-authored"}}]}
                 {:rf.ui/host-root-annotation canon})
          out  (strip tree)]
      (is (nil? (:attrs out))
          "the host root's markers are gone (both matched canonical)")
      (is (= {:data-rf-view "nested-authored"}
             (:attrs (first (:children out))))
          "RED before provenance — the recursive dissoc deleted this authored attr"))))

;; ---------------------------------------------------------------------------
;; #4 DEBUG-DISABLED BYPASS
;; ---------------------------------------------------------------------------

(deftest debug-disabled-bypasses-the-strip-walk-entirely
  (let [tree     (with-meta {:rf.ui/tree-version 1
                             :tag :div
                             :attrs {:data-rf-view ":app.probe/widget"
                                     :data-rf2-source-coord "app.probe:widget:1:2"}
                             :children [{:tag :p :attrs {:data-rf-view "nested"}}]}
                            {:rf.ui/host-root-annotation canon})
        captured (atom ::unset)]
    (with-redefs [tree/resolve-ssr-emitter (fn [] (fn [t] (reset! captured t) "<div></div>"))]
      (testing "debug OFF — the emitter receives the very same tree object"
        (with-redefs [interop/debug-enabled? false]
          (tree/emit-static-html tree)
          (is (identical? tree @captured)
              "no clone/walk — the strip is bypassed when debug is disabled")))
      (testing "debug ON — the emitter receives a stripped (walked) tree"
        (with-redefs [interop/debug-enabled? true]
          (tree/emit-static-html tree)
          (is (not (identical? tree @captured))
              "the strip ran, producing a new tree")
          (is (nil? (:attrs @captured))
              "the host root's canonical markers were removed"))))))
