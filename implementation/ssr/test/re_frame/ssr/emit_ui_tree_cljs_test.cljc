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

(deftest trusted-html-child-under-textarea-is-rejected
  ;; rf2-ib4fd — a hand-written trusted-markup (`{:html …}`) child beneath a
  ;; <textarea> is host-divergent: react-dom/server 19.2 rejects
  ;; dangerouslySetInnerHTML on a textarea (its content is value/defaultValue
  ;; or a text child). The compiler rejects the source shape; this seam is the
  ;; runtime defence for a manually-authored tree — it fails loud through the
  ;; SHARED malformed-tree path rather than emitting a body React would reject.
  (testing "a sole {:html s} child under <textarea> throws the shared id"
    ;; RED-BEFORE lever: the shipped serialiser emitted the markup verbatim
    ;; ("<textarea><b>x</b></textarea>"), diverging from React 19.2.
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :children [{:html "<b>x</b>"}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "a textarea trusted-markup child is the shared tree-consumer id")
      (is (= [{:html "<b>x</b>"}] (:value d))
          "ex-data carries the offending children")))
  (testing "a leading-LF {:html …} child under <textarea> is rejected, NOT "
           "leading-LF-compensated — trusted-HTML compensation is pre/listing only"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :children [{:html "\n<b>x</b>"}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))))
  (testing "an ordinary <textarea> body still emits — only :html children reject"
    (is (= "<textarea>hi</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["hi"]})))
        "a string child is fine")
    (is (= "<textarea>plain</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "plain"}})))
        ":value content is fine")))

(deftest textarea-effective-child-stream-is-validated
  ;; rf2-ib4fd (residual) — #6517's direct `{:html …}` check inspected only the
  ;; textarea's IMMEDIATE children, so a trusted-HTML leaf spliced in through a
  ;; transparent fragment or view boundary slipped through and emitted verbatim.
  ;; This seam validates the EFFECTIVE child stream (after splicing) against the
  ;; textarea host child contract, failing loud at the ACTUAL offending path.
  (testing "trusted markup nested through a transparent FRAGMENT is rejected"
    ;; RED-BEFORE lever: the fragment spliced {:html …} into the textarea and the
    ;; shipped serialiser emitted "<textarea><b>x</b></textarea>".
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea
                      :children [{:children [{:html "<b>x</b>"}]}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [:children 0 :children 0] (:path d))
          "the diagnostic locates the SPLICED leaf, not the textarea")))
  (testing "trusted markup nested through a VIEW BOUNDARY is rejected"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea
                      :children [{:view-id :my/view
                                  :children [{:html "<b>x</b>"}]}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [:children 0 :children 0] (:path d)))))
  (testing "a structural element child is rejected (React renders [object Object])"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :children [{:tag :span :children ["x"]}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [:children 0] (:path d)))))
  (testing "more than one effective child is rejected (React allows at most one)"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["a" "b"]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [:children 1] (:path d)) "locates the surplus (second) child"))
    ;; a fragment does not hide the count — two spliced children still reject
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :children [{:children ["a" "b"]}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))))
  (testing ":value / :default-value plus an authored child is rejected"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :attrs {:value "v"} :children ["c"]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [:children 0] (:path d))))
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :attrs {:default-value "v"} :children ["c"]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          ":default-value maps to value and rejects the pair identically")))
  (testing "the valid shapes still emit unchanged — value, sole text, spliced text"
    (is (= "<textarea>plain</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "plain"}})))
        ":value alone is valid")
    (is (= "<textarea>hi</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["hi"]})))
        "a sole string child is valid")
    (is (= "<textarea>hi</textarea>"
           (ui-tree/emit-ui-tree
             (v1 {:tag :textarea :children [{:children ["hi"]}]})))
        "a single string spliced through a fragment is valid")
    (is (= "<textarea>hi</textarea>"
           (ui-tree/emit-ui-tree
             (v1 {:tag :textarea :children [{:view-id :v :children ["hi"]}]})))
        "a single string spliced through a view boundary is valid")))

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

;; ---------------------------------------------------------------------------
;; Raw-text elements — <script>/<style> content (rf2-2dh3b)
;;
;; ANCHOR (rf2-2dh3b): react-dom/server 19.2 emits <script>/<style> text as HTML
;; RAW TEXT — the parser decodes no entities inside them, so the content is NOT
;; sent through `escape-html`; only an embedded closing-tag sequence is rewritten
;; to a context-safe spelling (a JS `s` unicode escape for </script, a CSS
;; `\73 ` escape for </style) so the raw-text parser cannot terminate early. This
;; is the raw-text EXCEPTION to Spec 004B §Children, text, and escaping's blanket
;; 5-char escaping row; the expected strings below are byte-pinned against
;; react-dom/server 19.2 (renderToStaticMarkup). It is NOT a sanitiser: the tree
;; is already-rendered, server-authored content (the ns trust contract), exactly
;; what react-dom/server itself emits raw.
;; ---------------------------------------------------------------------------

(deftest raw-text-script-style-is-not-html-escaped
  (testing "ordinary ampersand / less-than / greater-than in <script> stays LITERAL"
    ;; RED-BEFORE lever: the shipped `escape-html` path emitted
    ;; "<script>a &amp; b &lt; c &gt; d</script>" — a corrupted script body.
    (is (= "<script>a & b < c > d</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children ["a & b < c > d"]}))))
    (is (= "<script>if (a && b) {}</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children ["if (a && b) {}"]})))))
  (testing "ordinary ampersand / less-than in <style> stays LITERAL"
    (is (= "<style>a & b < c > d</style>"
           (ui-tree/emit-ui-tree (v1 {:tag :style :children ["a & b < c > d"]})))))
  (testing "a childless raw-text element still emits an explicit close tag"
    (is (= "<script></script>" (ui-tree/emit-ui-tree (v1 {:tag :script}))))
    (is (= "<style></style>" (ui-tree/emit-ui-tree (v1 {:tag :style}))))))

(deftest raw-text-closing-sequences-get-context-safe-spellings
  (testing "<script> content: (<|</)script -> the s/S becomes \\u0073 / \\u0053"
    ;; RED-BEFORE lever: the escape path emitted the entity spellings
    ;; "&lt;/script&gt;" whose entities stay LITERAL inside raw text — the DOM
    ;; would carry the text </script> and terminate the element early.
    (is (= "<script>var x = '</\\u0073cript>';</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children ["var x = '</script>';"]}))))
    (is (= "<script>a<\\u0073cript>b</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children ["a<script>b"]})))
        "an OPENING <script in content is escaped too, matching React")
    (is (= "<script>a</\\u0053CRIPT>b</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children ["a</SCRIPT>b"]})))
        "case is preserved except the escaped s/S; uppercase S -> \\u0053")
    (is (= "<script>a</\\u0053cRiPt>b</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children ["a</ScRiPt>b"]})))
        "mixed-case suffix preserved verbatim"))
  (testing "<style> content: (<|</)style -> the s/S becomes \\73 / \\53 (CSS escape)"
    (is (= "<style>.x{content:'</\\73 tyle>'}</style>"
           (ui-tree/emit-ui-tree (v1 {:tag :style :children [".x{content:'</style>'}"]}))))
    (is (= "<style>a</\\53 TYLE>b</style>"
           (ui-tree/emit-ui-tree (v1 {:tag :style :children ["a</STYLE>b"]}))))))

(deftest raw-text-own-lever-p-still-escapes
  (testing "VACUITY probe: the SAME text in a NON-raw-text element IS escaped"
    ;; The fix is narrow: only <script>/<style> bypass entity escaping. If the
    ;; branch mis-fired for ordinary elements this would drop the entities.
    (is (= "<p>a &amp; b &lt; c &gt; d</p>"
           (ui-tree/emit-ui-tree (v1 {:tag :p :children ["a & b < c > d"]})))
        "an ordinary <p> keeps full 5-char escaping")
    (is (= "<div>&lt;/script&gt;</div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :children ["</script>"]})))
        "and </script> in a <div> is inert escaped text, not a raw-text escape")))

;; ---------------------------------------------------------------------------
;; Raw-text elements honor the ui/html trusted-markup child (rf2-0spji)
;;
;; ANCHOR (rf2-0spji): the raw-text fast path (rf2-2dh3b) ran
;; `(str/join (:children el))` over ALL children, so a `{:html s}` child — the
;; `ui/html` trusted-markup bypass, which the CLJS emitter lowers to React
;; `dangerouslySetInnerHTML` and the JVM tree records as `{:html s}` — was
;; STRINGIFIED to its printed EDN map instead of emitting its trusted body. The
;; sole `{:html s}` child must emit `s` VERBATIM (react-dom/server pushes
;; `dangerouslySetInnerHTML.__html` raw — no entity escape, no closing-sequence
;; rewrite), byte-identical to the general `:html` node path; any other
;; structural child fails loud rather than leaking as EDN text.
;; ---------------------------------------------------------------------------

(deftest raw-text-honors-ui-html-trusted-child
  (testing "a sole {:html s} child under <script> emits the trusted body, NOT the printed map"
    ;; RED-BEFORE lever (rf2-0spji): the shipped fast path emitted the literal
    ;; EDN "<script>{:html \"const x=1;\"}</script>".
    (is (= "<script>const x=1;</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children [{:html "const x=1;"}]})))))
  (testing "a sole {:html s} child under <style> likewise emits its trusted body"
    (is (= "<style>.x{color:red}</style>"
           (ui-tree/emit-ui-tree (v1 {:tag :style :children [{:html ".x{color:red}"}]})))))
  (testing "the :html body is VERBATIM — the trusted bypass, NEITHER escaped NOR closing-sequence-rewritten"
    ;; Contrast the STRING path: "</script>" as a string CHILD is rewritten to
    ;; "</\\u0073cript>" (raw-text-closing-sequences-*). The :html trusted bypass
    ;; writes it verbatim — exactly as react-dom pushes dangerouslySetInnerHTML
    ;; and as the general :html node path (writes-trusted-html-verbatim) does.
    (is (= "<script>var s = '</script>';</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children [{:html "var s = '</script>';"}]}))))
    (is (= "<script>if (1 < 2 && 3) {}</script>"
           (ui-tree/emit-ui-tree (v1 {:tag :script :children [{:html "if (1 < 2 && 3) {}"}]})))
        "< and & stay literal — the :html body is never 5-char escaped")))

(deftest raw-text-structural-child-fails-loud-not-stringified
  (testing "an element child under <script> is the SHARED malformed id, not stringified EDN"
    ;; RED-BEFORE lever: the fast path stringified it to
    ;; "<script>{:tag :b, :children [\"x\"]}</script>".
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :script :children [{:tag :b :children ["x"]}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "a structural child in a raw-text element is the SHARED malformed id")
      (is (= [] (:path d)) "locates the offending raw-text element (the root here)")))
  (testing "a non-string :html under <style> is the SAME shared malformed id, located at the child"
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree (v1 {:tag :style :children [{:html 42}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (= [:children 0] (:path d)) "the reused :html row locates the non-string body")))
  (testing "a body mixing string content with a structural child is malformed"
    ;; React forbids both `children` and `dangerouslySetInnerHTML`; a mixed body
    ;; likewise fails loud rather than half-stringifying.
    (let [d (caught-ex-data
              #(ui-tree/emit-ui-tree
                 (v1 {:tag :script :children ["const x=1;" {:html "y"}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))))))

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
                             {:tag :option :attrs {:value "b"} :children ["B"]}]})))))
  (testing "a MULTIPLE select's :value is a COLLECTION, and selects every option it names"
    ;; The one tree attribute value that is not a scalar: a `<select multiple>`'s
    ;; selection is the list of chosen option values. Comparing the collection
    ;; itself against each option marked NOTHING, so a server render dropped the
    ;; whole selection and its hydrating client immediately disagreed with it.
    (is (= (str "<select multiple=\"\">"
                "<option selected=\"\" value=\"a\">A</option>"
                "<option value=\"b\">B</option>"
                "<option selected=\"\" value=\"c\">C</option>"
                "</select>")
           (ui-tree/emit-ui-tree
             (v1 {:tag :select
                  :attrs {:multiple true :value ["a" "c"]}
                  :children [{:tag :option :attrs {:value "a"} :children ["A"]}
                             {:tag :option :attrs {:value "b"} :children ["B"]}
                             {:tag :option :attrs {:value "c"} :children ["C"]}]})))))
  (testing "and the EMPTY selection selects none of them"
    (is (= (str "<select multiple=\"\">"
                "<option value=\"a\">A</option>"
                "<option value=\"b\">B</option>"
                "</select>")
           (ui-tree/emit-ui-tree
             (v1 {:tag :select
                  :attrs {:multiple true :value []}
                  :children [{:tag :option :attrs {:value "a"} :children ["A"]}
                             {:tag :option :attrs {:value "b"} :children ["B"]}]}))))))

;; ---------------------------------------------------------------------------
;; Newline-eating elements — leading-LF compensation (rf2-z05di)
;;
;; ANCHOR (rf2-z05di): HTML parsing eats the FIRST LF immediately after
;; <pre>/<listing>/<textarea>, so react-dom/server 19.2 prefixes one
;; compensating LF when the element's content is a SINGLE STRING beginning with
;; LF (its `typeof children === 'string'` guard) — making the intended content
;; survive the parse round-trip. Multiple/element children are left untouched.
;; The expected strings are byte-pinned against react-dom/server 19.2
;; (renderToStaticMarkup); "\n\n" below is a real doubled newline.
;; ---------------------------------------------------------------------------

(deftest leading-newline-compensated-for-pre-and-listing
  (testing "<pre> single text child beginning with LF gets the doubled LF"
    ;; RED-BEFORE lever: the shipped serialiser emitted "<pre>\nhello</pre>",
    ;; which parses to a DOM with one FEWER newline than the tree authored.
    (is (= "<pre>\n\nhello</pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\nhello"]})))))
  (testing "each extra authored LF survives (one is eaten, the rest remain)"
    (is (= "<pre>\n\n\nhello</pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\n\nhello"]})))))
  (testing "pre child text is still HTML-escaped alongside the compensation"
    (is (= "<pre>\n\n&lt;a&gt; &amp; b</pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\n<a> & b"]})))))
  (testing "<listing> is a newline-eating element too"
    (is (= "<listing>\n\nhello</listing>"
           (ui-tree/emit-ui-tree (v1 {:tag :listing :children ["\nhello"]}))))))

(deftest leading-newline-compensated-for-textarea-value
  (testing ":value on <textarea> beginning with LF gets the doubled LF"
    ;; RED-BEFORE lever: emitted "<textarea>\nhello</textarea>" (one LF).
    (is (= "<textarea>\n\nhello</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "\nhello"}})))))
  (testing "textarea :value is RCDATA-escaped alongside the compensation"
    (is (= "<textarea>\n\n&lt;a&gt;&amp;</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "\n<a>&"}})))))
  (testing "a single string child (no :value) is compensated the same way"
    (is (= "<textarea>\n\nhi</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["\nhi"]}))))))

(deftest leading-newline-own-lever-only-a-single-lf-string-child
  (testing "VACUITY: no LF prefix ⇒ no compensation (the fix must not add one)"
    (is (= "<pre>hello</pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children ["hello"]})))
        "content not beginning with LF is emitted unchanged")
    (is (= "<textarea>hello</textarea>"
           (ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "hello"}})))))
  (testing "a leading CR (\\r) is NOT a newline-eating trigger — matches React"
    (is (= "<pre>\r\nhello</pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\r\nhello"]})))
        "only a leading LF is eaten by the parser, so only LF is compensated"))
  (testing "MULTIPLE children ⇒ no compensation (React's single-string guard)"
    ;; Two text children are React's `children` array, not a string — no doctoring.
    (is (= "<pre>\nab</pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\na" "b"]})))
        "a multi-child pre body is left untouched even when the first begins LF"))
  (testing "a non-newline-eating element is never compensated"
    (is (= "<div>\nhello</div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :children ["\nhello"]})))
        "the compensation is scoped to pre/listing/textarea only")))

;; ---------------------------------------------------------------------------
;; Leading-LF compensation for a sole trusted-HTML child (rf2-0spji)
;;
;; ANCHOR (rf2-0spji): React compensates the eaten leading LF for a single
;; STRING body applied to a string child AND to `dangerouslySetInnerHTML.__html`.
;; The shipped `leading-newline-compensation` only recognised a direct string
;; child, so a valid sole `{:html "\n…"}` child under <pre>/<listing> emitted a
;; single LF the parser then eats — one FEWER newline than authored.
;; ---------------------------------------------------------------------------

(deftest leading-newline-compensated-for-sole-trusted-html-child
  (testing "<pre> sole {:html s} child beginning with LF gets React's compensating LF"
    ;; RED-BEFORE lever (rf2-0spji): emitted "<pre>\n<b>x</b></pre>" (one LF).
    (is (= "<pre>\n\n<b>x</b></pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children [{:html "\n<b>x</b>"}]})))))
  (testing "<listing> compensates a sole trusted-HTML LF body the same way"
    (is (= "<listing>\n\n<i>y</i></listing>"
           (ui-tree/emit-ui-tree (v1 {:tag :listing :children [{:html "\n<i>y</i>"}]})))))
  (testing "VACUITY: a :html body NOT beginning with LF gets no compensation"
    (is (= "<pre><b>x</b></pre>"
           (ui-tree/emit-ui-tree (v1 {:tag :pre :children [{:html "<b>x</b>"}]})))
        "the fix must not add an LF when none is owed")
    (is (= "<div>\n<b>x</b></div>"
           (ui-tree/emit-ui-tree (v1 {:tag :div :children [{:html "\n<b>x</b>"}]})))
        "a non-newline-eating element is never compensated, even for a :html body")))

(deftest opts-contract
  ;; The exact opts contract (Spec 004B §The SSR consumption boundary,
  ;; API.md re-frame.ssr table): `:doctype?` is the ONLY current option,
  ;; default off; other keys are ignored — no `render-to-string` option
  ;; transfers to this seam, and there is no validation framework.
  (let [tree (v1 {:tag :html})]
    (testing ":doctype? true prefixes the doctype"
      (is (= "<!DOCTYPE html><html></html>"
             (ui-tree/emit-ui-tree tree {:doctype? true}))))
    (testing "default (arity-1) emits no doctype"
      (is (= "<html></html>"
             (ui-tree/emit-ui-tree tree))))
    (testing "nil opts emits no doctype"
      (is (= "<html></html>"
             (ui-tree/emit-ui-tree tree nil))))
    (testing ":doctype? false emits no doctype"
      (is (= "<html></html>"
             (ui-tree/emit-ui-tree tree {:doctype? false}))))
    (testing "unknown keys are ignored — not rejected, not honoured"
      (is (= "<html></html>"
             (ui-tree/emit-ui-tree tree {:emit-hash? true :bogus 1})))
      (is (= "<!DOCTYPE html><html></html>"
             (ui-tree/emit-ui-tree tree {:doctype? true :emit-hash? true :bogus 1}))))))

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
