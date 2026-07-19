(ns re-frame.ssr.emit-ui-tree-cljs-test
  "The S5 tree->HTML serialiser `re-frame.ssr/emit-ui-tree` (rf2-3omxp,
  spec contract Spec 004B §The SSR consumption boundary).

  The load-bearing proofs here are the TWO-ID DISCIPLINE and the version
  gate: the SAME tree throws the NEW id `:rf.error/ssr-ui-tree-version-
  unsupported` when only its root `:rf.ui/tree-version` is wrong (an
  operational deploy-skew condition), and the SHARED id
  `:rf.error/ui-tree-malformed` when a node PAST the gate is structurally
  malformed (a code bug). Every gate assertion inspects the thrown
  `:rf.error/id` AND its ex-data — not merely that an exception was thrown —
  because the whole point is WHICH id.

  The conversion-table copies the serialiser carries (`re-frame.ssr`'s
  Independence rule forbids requiring `re-frame.ui` in production) are
  pinned byte-for-byte against their `re-frame.ui.rules` source. The UI
  compiler is a TEST-ONLY dependency here.

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns): `clojure -M:test` from
  `implementation/ssr` (JVM) and `npm run test:cljs` (node). A CLJS class
  does not throw on `(inc nil)`, so the serialiser throws EXPLICITLY via the
  canonical builder — the ids and ex-data are identical on both hosts."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.ui-tree :as ui-tree]
            [re-frame.ssr :as ssr]
            [re-frame.ui.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- v1
  "Stamp a root node map as version-1."
  [node]
  (assoc node :rf.ui/tree-version 1))

(defn- caught-ex-data
  "Run `f`; return the ex-data of whatever it throws, or `::no-throw`."
  [f]
  (try
    (f)
    ::no-throw
    (catch #?(:clj Throwable :cljs :default) e
      (ex-data e))))

;; ---------------------------------------------------------------------------
;; The version gate — the NEW id, validated FIRST, with its own lever
;; ---------------------------------------------------------------------------

(deftest version-gate-throws-the-new-id-with-got-and-supported
  (testing "missing / non-integer / unsupported root version -> the SSR-seam id"
    (doseq [[label root expected-got]
            [["missing version" {:tag :div}                       nil]
             ["version 0"        {:rf.ui/tree-version 0  :tag :div} 0]
             ["string \"1\""     {:rf.ui/tree-version "1" :tag :div} "1"]
             ["version 2"        {:rf.ui/tree-version 2  :tag :div} 2]]]
      (let [d (caught-ex-data #(ui-tree/emit-ui-tree root))]
        (is (map? d)
            (str label ": expected a thrown ex-info, got " (pr-str d)))
        (is (= :rf.error/ssr-ui-tree-version-unsupported (:rf.error/id d))
            (str label ": must throw the NEW version-gate id"))
        (is (contains? d :got) (str label ": ex-data carries :got"))
        (is (= expected-got (:got d))
            (str label ": :got is the RECEIVED value"))
        (is (= #{1} (:supported d))
            (str label ": :supported is #{1}"))))))

(deftest version-gate-own-lever-same-tree-only-the-version-differs
  (testing "one structurally-identical tree emits at v1 and throws at v2"
    (let [good (v1 {:tag :div :children ["ok"]})
          bad  (assoc good :rf.ui/tree-version 2)]
      (is (= "<div>ok</div>" (ui-tree/emit-ui-tree good)))
      (let [d (caught-ex-data #(ui-tree/emit-ui-tree bad))]
        (is (= :rf.error/ssr-ui-tree-version-unsupported (:rf.error/id d)))
        (is (= 2 (:got d)))))))

(deftest version-gate-fires-before-any-emission
  (testing "a v2 tree whose body would be perfectly emittable still throws"
    ;; If the gate ran AFTER emission (or not first) this would emit markup.
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree {:rf.ui/tree-version 2
                                      :tag :div
                                      :children [{:tag :span :children ["x"]}]}))]
      (is (= :rf.error/ssr-ui-tree-version-unsupported (:rf.error/id d))))))

;; ---------------------------------------------------------------------------
;; Malformed nodes PAST the gate — the SHARED id (a distinct failure class)
;; ---------------------------------------------------------------------------

(deftest malformed-node-throws-the-shared-id-not-the-version-id
  (testing "multiple discriminators on a node past the version gate"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree (v1 {:tag :div
                                          :children [{:tag :span :html "x"}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "a malformed node is the SHARED tree-consumer id, not the version id")
      (is (= [:tag :html] (:got d)) "reports the offending discriminator set")
      (is (= [:children 0] (:path d)) "locates the node by root-relative path")))

  (testing "no discriminator and no :children"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree (v1 {:tag :div :children [{:not-a-node 1}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [] (:got d)))
      (is (= [:children 0] (:path d)))))

  (testing "a non-string, non-map node (a bare keyword child)"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree (v1 {:tag :div :children [:nope]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))))

  (testing "a non-string :html is malformed"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree (v1 {:tag :div :children [{:html 42}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))))))

;; ---------------------------------------------------------------------------
;; Emission — the serialisation half of the conversion table
;; ---------------------------------------------------------------------------

(deftest emits-elements-attrs-and-text
  (testing "element with sorted attrs and escaped text"
    (is (= "<div class=\"box\" id=\"main\">hi</div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div
                                      :attrs {:id "main" :class "box"}
                                      :children ["hi"]})))))
  (testing "full 5-char text escaping (&#x27; for apostrophe, matching React)"
    (is (= "<p>&lt;b&gt; &amp; &quot; &#x27;</p>"
           (ui-tree/emit-ui-tree (v1 {:tag :p :children ["<b> & \" '"]}))))))

(deftest emits-conversion-table-name-rows
  (testing ":for/:class verbatim, :tab-index collapses, :view-box aliases"
    (is (= "<label for=\"x\" tabindex=\"3\"></label>"
           (ui-tree/emit-ui-tree (v1 {:tag :label :attrs {:for "x" :tab-index 3}}))))
    (is (= "<svg viewBox=\"0 0 1 1\"></svg>"
           (ui-tree/emit-ui-tree (v1 {:tag :svg :attrs {:view-box "0 0 1 1"}}))))
    (is (= "<a xlink:href=\"#a\"></a>"
           (ui-tree/emit-ui-tree (v1 {:tag :a :attrs {:xlink-href "#a"}})))))
  (testing "data-* / aria-* names verbatim"
    (is (= "<div data-fooBar=\"1\"></div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:data-fooBar "1"}}))))))

(deftest emits-boolean-classes
  (testing "boolean attr: true -> presence, false -> omitted"
    (is (= "<input disabled=\"\">"
           (ui-tree/emit-ui-tree (v1 {:tag :input :attrs {:disabled true :checked false}})))))
  (testing "booleanish: true/false -> \"true\"/\"false\", never omitted"
    (is (= "<div contentEditable=\"false\"></div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:content-editable false}})))))
  (testing "overloaded: true -> presence, false -> omitted, other -> value"
    (is (= "<a download=\"\"></a>"
           (ui-tree/emit-ui-tree (v1 {:tag :a :attrs {:download true}}))))
    (is (= "<a download=\"file.txt\"></a>"
           (ui-tree/emit-ui-tree (v1 {:tag :a :attrs {:download "file.txt"}})))))
  (testing "aria-* values always stringify, never omitted"
    (is (= "<div aria-hidden=\"false\"></div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:aria-hidden false}}))))))

(deftest emits-style-in-pinned-order
  (testing ":style map -> css declaration, sorted by property name"
    (is (= "<div style=\"color:red;margin-top:0\"></div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:style {:margin-top "0"
                                                                 :color "red"}}}))))))

(deftest emits-void-elements-self-closed
  (is (= "<br>" (ui-tree/emit-ui-tree (v1 {:tag :br}))))
  (is (= "<img src=\"a.png\">"
         (ui-tree/emit-ui-tree (v1 {:tag :img :attrs {:src "a.png"}})))))

(deftest drops-events-and-keys
  (testing "events never serialise into HTML; :key has no HTML presence"
    (is (= "<button>Go</button>"
           (ui-tree/emit-ui-tree (v1 {:tag :button
                                      :events {:on-click [:go]}
                                      :key 7
                                      :children ["Go"]}))))))

(deftest ignores-reserved-diagnostic-keys
  (testing "node-level :rf.ui/* diagnostic keys never emit"
    (is (= "<div>x</div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div
                                      :rf.ui/presence {:phase :present}
                                      :rf.ui/boundary :client-only
                                      :children ["x"]}))))))

(deftest splices-fragments-and-erases-view-boundaries
  (testing "fragment root splices its children with no wrapper"
    (is (= "<span>a</span><span>b</span>"
           (ui-tree/emit-ui-tree (v1 {:children [{:tag :span :children ["a"]}
                                                 {:tag :span :children ["b"]}]})))))
  (testing "view boundary is erased; its children splice, its :props are ignored"
    (is (= "<span>x</span>"
           (ui-tree/emit-ui-tree (v1 {:view-id :my/view
                                      :props {:whatever 1}
                                      :children [{:tag :span :children ["x"]}]}))))))

(deftest writes-trusted-html-verbatim
  (testing ":html node content is NOT escaped"
    (is (= "<div><b>raw</b></div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :children [{:html "<b>raw</b>"}]}))))))

(deftest custom-element-property-props-omitted
  (testing "property-classified props never reach markup; attributes do"
    (is (= "<my-widget id=\"w\"></my-widget>"
           (ui-tree/emit-ui-tree (v1 {:tag :my-widget
                                      :attrs {:help-text "hi" :id "w"}
                                      :rf.ui/property-props #{:help-text}}))))))

(deftest form-control-special-forms
  (testing ":default-value serialises as value"
    (is (= "<input value=\"d\">"
           (ui-tree/emit-ui-tree (v1 {:tag :input :attrs {:default-value "d"}})))))
  (testing ":value on :textarea serialises as the text child, not an attribute"
    (is (= "<textarea>hello</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "hello"}})))))
  (testing ":value on :select serialises as selected on the matching option"
    (is (= (str "<select>"
                "<option value=\"a\">A</option>"
                "<option selected=\"\" value=\"b\">B</option>"
                "</select>")
           (ui-tree/emit-ui-tree
             (v1 {:tag :select
                  :attrs {:value "b"}
                  :children [{:tag :option :attrs {:value "a"} :children ["A"]}
                             {:tag :option :attrs {:value "b"} :children ["B"]}]}))))))

(deftest doctype-opt
  (is (= "<!DOCTYPE html><html></html>"
         (ui-tree/emit-ui-tree (v1 {:tag :html}) {:doctype? true}))))

(deftest facade-re-export-is-the-same-fn
  (testing "re-frame.ssr/emit-ui-tree is the serialiser"
    (is (= "<div>hi</div>"
           (ssr/emit-ui-tree (v1 {:tag :div :children ["hi"]}))))))

;; ---------------------------------------------------------------------------
;; Anti-drift: the carried conversion-table copies == their ui.rules source
;; ---------------------------------------------------------------------------

(deftest carried-conversion-table-matches-the-ui-rules-source
  (testing "the seam's copies are byte-for-byte the re-frame.ui.rules source"
    (is (= rules/standard-names           ui-tree/standard-names))
    (is (= rules/dom-attr-aliases         ui-tree/dom-attr-aliases))
    (is (= rules/void-tags                ui-tree/void-tags))
    (is (= rules/boolean-attrs            ui-tree/boolean-attrs))
    (is (= rules/booleanish-attrs         ui-tree/booleanish-attrs))
    (is (= rules/overloaded-boolean-attrs ui-tree/overloaded-boolean-attrs))
    (is (= rules/property-only-attrs      ui-tree/property-only-attrs)))
  (testing "escaping matches (React's escapeTextForBrowser, `'` -> &#x27;)"
    (is (= (rules/escape-html "<a>&\"'x")
           (ui-tree/escape-html "<a>&\"'x")))))
