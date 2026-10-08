(ns day8.re-frame2-xray.theme.var-resolution-cljs-test
  "Pins that rendered value hiccup paints through `var(--rf-xray-…)`
  references, never a palette hex literal: an inline hex paints the same
  colour under both theme classes, so light mode breaks. That `tokens`
  itself is the var map is pinned in `tokens_cljs_test.cljc`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(defn- collect-style-strings
  "Every string value of every `:style` map in the hiccup `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk-node [node]
              (when (vector? node)
                (let [attrs (when (map? (second node)) (second node))]
                  (when-let [style (:style attrs)]
                    (when (map? style)
                      (doseq [[_ v] style]
                        (when (string? v)
                          (swap! out conj v)))))
                  (doseq [child (rest node)]
                    (cond
                      (vector? child) (walk-node child)
                      (seq? child)    (doseq [c child] (walk-node c)))))))]
      (walk-node tree))
    @out))

(def ^:private palette-hex-pattern
  ;; `#` + 3-to-8 hex digits: `#7C5CFF`, `#a83a3a`, `#fff`.
  #"#[0-9A-Fa-f]{3,8}\b")

(deftest edn-inspector-rendered-hiccup-has-no-palette-hex-literals
  (testing "the value renderer (`views/edn-inspector/render-node`) styles
            every value kind through CSS variables"
    (doseq [v [{:a 1 :b 2 :c [1 2 3]}      ; map + nested vec
               [:foo :bar {:baz nil}]      ; vector with primitives + map
               #{1 2 3}                    ; set
               "a string"                  ; string leaf
               :keyword                    ; keyword leaf
               42                          ; number leaf
               true                        ; boolean leaf
               nil                         ; nil leaf
               :rf/redacted                ; redacted sentinel
               {:rf.size/large-elided      ; large sentinel (spec/015)
                {:path   [:blob]
                 :bytes  1234
                 :type   :string
                 :reason :schema
                 :hint   "preview hint"
                 :handle [:rf.elision/at [:blob]]}}]]
      (let [tree (ei/render-node {:value v
                                  :panel-id :rf.xray/var-resolution-test
                                  :mount-id "test"
                                  :path []
                                  :depth 0
                                  :expansion-map {}
                                  :opts {:default-expanded-depth 2}})
            styles (collect-style-strings tree)]
        (is (seq styles)
            (str "render of " (pr-str v) " produced style strings to inspect"))
        (doseq [s styles]
          (is (not (re-find palette-hex-pattern s))
              (str "style string " (pr-str s)
                   " in render of " (pr-str v)
                   " contains a palette hex literal "
                   "(should be a var(--rf-xray-…) reference)")))))))
