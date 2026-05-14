(ns re-frame.mcp-base.elision-test
  "Pins the elision-marker walker (rf2-9fz64). The walker counts every
  `{:rf.size/large-elided ...}` marker in a wire-bound payload — the
  count rides the response envelope as the `:elided-large` slot
  (Conventions §Cross-MCP indicator-field vocabulary, MUST-level per
  rf2-2499j). A regression here is a drift in the envelope's elision-
  indicator parity with `:dropped-sensitive`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.elision :as elision]))

(deftest count-elided-markers-walks-the-payload
  (let [marker {:rf.size/large-elided
                {:path   [:user :pdf]
                 :bytes  102400
                 :type   :string
                 :reason :declared
                 :handle [:rf.elision/at [:user :pdf]]}}]
    ;; Empty / leaf cases — nothing to count.
    (is (= 0 (elision/count-elided-markers nil)))
    (is (= 0 (elision/count-elided-markers {})))
    (is (= 0 (elision/count-elided-markers [])))
    (is (= 0 (elision/count-elided-markers "string")))
    (is (= 0 (elision/count-elided-markers 42)))
    (is (= 0 (elision/count-elided-markers {:ok? true :payload {:a 1 :b [2 3]}})))

    ;; Marker counted at every depth and shape.
    (is (= 1 (elision/count-elided-markers marker))
        "Top-level single marker counts once.")
    (is (= 1 (elision/count-elided-markers {:value marker}))
        "Marker nested in a map counts once.")
    (is (= 1 (elision/count-elided-markers [marker]))
        "Marker nested in a vector counts once.")
    (is (= 2 (elision/count-elided-markers {:a marker :b marker}))
        "Sibling markers both count.")
    (is (= 3 (elision/count-elided-markers
               {:slice1 marker
                :slice2 {:nested marker}
                :slice3 [{:deep marker}]}))
        "Markers at mixed depths all count.")

    ;; The marker BODY is not recursed into (marker bodies carry
    ;; `:handle` / `:path` / metadata, not another marker).
    (let [body-with-collision {:rf.size/large-elided
                               {:path [:a :b]
                                :bytes 100
                                :type :string
                                :reason :declared
                                :handle [:rf.elision/at [:a :b]]
                                ;; A pathological marker-shaped value
                                ;; lodged inside the body would still
                                ;; only count the OUTER marker.
                                :extra {:rf.size/large-elided
                                        {:bytes 1}}}}]
      (is (= 1 (elision/count-elided-markers body-with-collision))
          "Marker body is opaque; nested marker-shape isn't double-counted."))))

(deftest count-elided-markers-handles-lazy-seq-input
  ;; Mirror sensitive_test.clj's lazy-seq pin (rf2-cwqc8 / round-2 F17).
  ;; The walker accepts `seq?` per `elision.cljc` so a slice composed via
  ;; `concat` / `map` / `filter` (lazy seqs) must count the same as the
  ;; equivalent vector. Regression pin for rf2-jj46n / F20.
  (let [marker {:rf.size/large-elided
                {:path   [:user :pdf]
                 :bytes  102400
                 :type   :string
                 :reason :declared
                 :handle [:rf.elision/at [:user :pdf]]}}
        contents [{:slice 1} marker {:slice 2 :nested marker}]
        as-lazy  (map identity contents)]
    (is (seq? as-lazy)
        "precondition: `map` yields a lazy seq, not a vector")
    (is (= 2 (elision/count-elided-markers as-lazy))
        "lazy seqs count markers identically to the vector path")
    (is (= 2 (elision/count-elided-markers contents))
        "vector path baseline matches the lazy path")
    (is (= 2 (elision/count-elided-markers
              (filter (constantly true) contents)))
        "filter-derived lazy seq counts identically")))
