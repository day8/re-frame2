(ns re-frame.ssr.emit-ui-tree-cljs-test
  "The S5 tree->HTML serialiser `re-frame.ssr.ui-tree/emit-ui-tree` (Spec 004B
  §The SSR consumption boundary).

  The load-bearing proofs are the TWO-ID DISCIPLINE and the version gate: a
  tree whose root `:rf.ui/tree-version` is wrong throws the SSR-seam id
  `:rf.error/ssr-ui-tree-version-unsupported` (operational deploy skew), and a
  node PAST the gate that is structurally malformed throws the shared
  `:rf.error/ui-tree-malformed` (a code bug). Each gate assertion checks the
  thrown id together with its ex-data.

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns). The raw-text and
  newline-compensation rows the three SSR paths share are pinned once, on the
  JVM, by `re-frame.ssr-emit-test` and `re-frame.ssr-hiccup-newline-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

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

(defn- malformed
  "The `:rf.error/ui-tree-malformed` ex-data a tree consumer must throw,
  narrowed to the keys a row pins."
  [extra]
  (merge {:rf.error/id :rf.error/ui-tree-malformed} extra))

(defn- pinned
  "`d` narrowed to the keys of `expected`, for a whole-value comparison."
  [expected d]
  (select-keys d (keys expected)))

;; ---------------------------------------------------------------------------
;; The version gate — the SSR-seam id, validated FIRST
;; ---------------------------------------------------------------------------

(deftest version-gate-throws-the-new-id-with-got-and-supported
  (testing "missing / non-integer / unsupported root version -> the SSR-seam id"
    (doseq [[label root expected-got]
            [["missing version" {:tag :div}                       nil]
             ["string \"1\""     {:rf.ui/tree-version "1" :tag :div} "1"]
             ["version 2"        {:rf.ui/tree-version 2  :tag :div} 2]]]
      (let [expected {:rf.error/id :rf.error/ssr-ui-tree-version-unsupported
                      :got         expected-got
                      :supported   #{1}}]
        (is (= expected
               (pinned expected (caught-ex-data #(rf.ssr.ui-tree/emit-ui-tree root))))
            label)))))

;; ---------------------------------------------------------------------------
;; Malformed nodes PAST the gate — the SHARED id
;; ---------------------------------------------------------------------------

(deftest malformed-node-throws-the-shared-id-not-the-version-id
  (testing "multiple discriminators on a node past the version gate"
    (let [expected (malformed {:got [:tag :html] :path [:children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div
                                                          :children [{:tag :span :html "x"}]}))))))))

  (testing "no discriminator and no :children"
    (let [expected (malformed {:got [] :path [:children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :children [{:not-a-node 1}]}))))))))

  (testing "a non-string, non-map node (a bare keyword child)"
    (let [d (caught-ex-data
              #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :children [:nope]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))))

  (testing "a non-string :html is malformed"
    (let [d (caught-ex-data
              #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :children [{:html 42}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))))))

(deftest textarea-effective-child-stream-is-validated
  ;; The EFFECTIVE child stream (after splicing fragments and view
  ;; boundaries) is validated against the textarea host child contract, so a
  ;; trusted-HTML leaf cannot slip in through a transparent wrapper.
  (testing "a sole {:html s} child under <textarea> throws the shared id"
    (let [expected (malformed {:value [{:html "<b>x</b>"}]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :textarea :children [{:html "<b>x</b>"}]}))))))))
  (testing "trusted markup nested through a transparent FRAGMENT is rejected at the spliced leaf"
    (let [expected (malformed {:path [:children 0 :children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :textarea
                               :children [{:children [{:html "<b>x</b>"}]}]}))))))))
  (testing "trusted markup nested through a VIEW BOUNDARY is rejected"
    (let [expected (malformed {:path [:children 0 :children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :textarea
                               :children [{:view-id :my/view
                                           :children [{:html "<b>x</b>"}]}]}))))))))
  (testing "a structural element child is rejected (React renders [object Object])"
    (let [expected (malformed {:path [:children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :textarea :children [{:tag :span :children ["x"]}]}))))))))
  (testing "more than one effective child is rejected (React allows at most one)"
    (let [expected (malformed {:path [:children 1]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["a" "b"]})))))
          "locates the surplus (second) child"))
    ;; a fragment does not hide the count — two spliced children still reject
    (let [d (caught-ex-data
              #(rf.ssr.ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :children [{:children ["a" "b"]}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))))
  (testing ":value / :default-value plus an authored child is rejected"
    (let [expected (malformed {:path [:children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :textarea :attrs {:value "v"} :children ["c"]})))))))
    (let [d (caught-ex-data
              #(rf.ssr.ui-tree/emit-ui-tree
                 (v1 {:tag :textarea :attrs {:default-value "v"} :children ["c"]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          ":default-value maps to value and rejects the pair identically")))
  (testing "the valid shapes still emit unchanged — value, sole text, spliced text"
    (is (= "<textarea>plain</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "plain"}})))
        ":value alone is valid")
    (is (= "<textarea>hi</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["hi"]})))
        "a sole string child is valid")
    (is (= "<textarea>hi</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :textarea :children [{:children ["hi"]}]})))
        "a single string spliced through a fragment is valid")
    (is (= "<textarea>hi</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :textarea :children [{:view-id :v :children ["hi"]}]})))
        "a single string spliced through a view boundary is valid")))

;; ---------------------------------------------------------------------------
;; Emission — the serialisation half of the conversion table
;; ---------------------------------------------------------------------------

(deftest emits-elements-attrs-and-text
  (testing "element with sorted attrs and escaped text"
    (is (= "<div class=\"box\" id=\"main\">hi</div>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div
                                             :attrs {:id "main" :class "box"}
                                             :children ["hi"]})))))
  (testing "full 5-char text escaping (&#x27; for apostrophe, matching React)"
    (is (= "<p>&lt;b&gt; &amp; &quot; &#x27;</p>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :p :children ["<b> & \" '"]}))))))

(deftest emits-conversion-table-name-rows
  (testing ":for/:class verbatim, :tab-index collapses, :view-box aliases"
    (is (= "<label for=\"x\" tabindex=\"3\"></label>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :label :attrs {:for "x" :tab-index 3}}))))
    (is (= "<svg viewBox=\"0 0 1 1\"></svg>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :svg :attrs {:view-box "0 0 1 1"}}))))
    (is (= "<a xlink:href=\"#a\"></a>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :a :attrs {:xlink-href "#a"}})))))
  (testing "data-* / aria-* names verbatim"
    (is (= "<div data-fooBar=\"1\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:data-fooBar "1"}}))))))

(defn- caught-error
  "Run `f`; return `[error-id message]` for whatever it throws, or
  `[::emitted output]` when it returns, so a failure shows the markup."
  [f]
  (try
    [::emitted (f)]
    (catch #?(:clj Throwable :cljs :default) e
      [(:rf.error/id (ex-data e)) (ex-message e)])))

(deftest attribute-names-are-gated-like-the-hiccup-tier
  ;; `escape-html` covers attribute VALUES only, so a name is written into
  ;; the tag as it stands. Each key below breaks out of the tag when written
  ;; unchecked: the first ends in a live `onmouseover` handler, the second
  ;; closes the tag and opens an `<img>` whose `onerror` runs, and the third
  ;; shows the `data-*` pass-through is a naming rule, not an exemption.
  (doseq [attribute-key [(keyword "title\" onmouseover=\"alert(1)")
                         (keyword "x><img src=x onerror=alert(1) z")
                         (keyword "data-a><b")]]
    (let [tree-error   (caught-error
                         #(rf.ssr.ui-tree/emit-ui-tree
                            (v1 {:tag :div :attrs {attribute-key "v"}})))
          hiccup-error (caught-error
                         #(rf.ssr.html-helpers/attr-string {attribute-key "v"}))]
      (testing (str "emit-ui-tree refuses " (pr-str (name attribute-key)))
        (is (= :rf.error/ssr-invalid-attribute-name (first tree-error))
            (str "the name must be refused, not written into the markup; got "
                 (pr-str tree-error)))
        (is (= hiccup-error tree-error)
            "the same id and the same message as the hiccup tier"))))
  (testing "control: a grammar-legal name still emits"
    (is (= "<div data-ok=\"v\" title=\"t\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :div :attrs {:data-ok "v" :title "t"}}))))))

(deftest tag-names-are-gated-like-the-hiccup-tier
  ;; A tag name is written into the markup unescaped, once in the opening
  ;; tag and again in the closing one. Each `:tag` below breaks out of the
  ;; tag when written unchecked: the first closes the element and opens a
  ;; `<b>` on both sides of the content, the second carries a live handler,
  ;; and the third a quote.
  (doseq [tag [(keyword "div><b")
               (keyword "div onclick=alert(1)")
               (keyword "a\"b")]]
    (let [hiccup-error (caught-ex-data
                         #(rf.ssr.emit/render-to-string [tag "marker"] {}))
          ;; The hiccup tier's own `:tag-name` for the same input: equal only
          ;; when both tiers refused it under the same grammar.
          expected     {:rf.error/id :rf.error/invalid-tag-name
                        :tag-name    (:tag-name hiccup-error)
                        :source      tag}]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag tag :children ["marker"]})))))
          (str "emit-ui-tree refuses " (pr-str (name tag)) " as the hiccup tier does"))))
  (testing "a malformed :tag on a nested node is refused"
    (let [expected {:rf.error/id :rf.error/invalid-tag-name :tag-name "span><i"}]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :div
                               :children [{:tag (keyword "span><i") :children ["m"]}]}))))))))
  (testing "a tree :tag is an element name, never hiccup `.class#id` shorthand"
    (is (= :rf.error/invalid-tag-name
           (:rf.error/id (caught-ex-data
                           #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div.box})))))
        ":div.box must be refused, not split into a tag and attributes"))
  (testing "controls: custom-element and camelCase SVG names still emit"
    (doseq [[tag expected] [[:my-widget     "<my-widget>m</my-widget>"]
                            [:foreignObject "<foreignObject>m</foreignObject>"]]]
      (is (= expected (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag tag :children ["m"]})))
          (str (pr-str tag) " is a grammar-legal element name")))))

(deftest reserved-attr-keys-are-refused-where-the-hiccup-tier-drops-them
  ;; React takes `key`, `ref`, `children` and `dangerouslySetInnerHTML` off
  ;; the props object and writes none of them as an attribute, and the tree
  ;; holds each somewhere other than `:attrs`, so each is refused here. The
  ;; hiccup tier drops the same keys instead (spec/011 §XSS names both
  ;; dispositions), and that is the control: it must go on dropping. One
  ;; keyword per slot, plus the qualified, string and symbol spellings.
  (doseq [attribute-key [:key :ref :children :dangerouslySetInnerHTML
                         :x/key "key" 'key]]
    (testing (str "emit-ui-tree refuses " (pr-str attribute-key) " in :attrs")
      (let [expected (malformed {:path [:children 0] :value attribute-key})]
        (is (= expected
               (pinned expected
                       (caught-ex-data
                         #(rf.ssr.ui-tree/emit-ui-tree
                            (v1 {:tag :div
                                 :children [{:tag :span :attrs {:id "a" attribute-key "v"}}]}))))))))
    (testing (str "control: the hiccup tier drops " (pr-str attribute-key))
      (is (= "" (rf.ssr.html-helpers/attr-string {attribute-key "v"})))))
  (testing "control: a different name, or one qualified onto an ordinary slot, still emits"
    (is (= "<div Key=\"k\" data-ref=\"r\" title=\"t\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :div :attrs {:Key "k" :data-ref "r" :x/title "t"}}))))))

(deftest a-key-canonicalising-onto-a-reserved-slot-is-refused
  ;; The refusal reads the React prop name a key canonicalises to, not the
  ;; key as written: the kebab and hyphen-collapsed spellings reach the slot
  ;; through the conversion table, and the upper-case acronym spelling
  ;; `dangerously-set-inner-HTML` — the one Fresco resolves to React's slot —
  ;; through the case-insensitive second lookup.
  (doseq [attribute-key [:dangerously-set-inner-html
                         :dangerouslysetinnerhtml
                         :dangerously-set-inner-HTML
                         "dangerously-set-inner-HTML"]]
    (testing (str "emit-ui-tree refuses " (pr-str attribute-key) " in :attrs")
      (let [expected (malformed {:path [:children 0] :value attribute-key})]
        (is (= expected
               (pinned expected
                       (caught-ex-data
                         #(rf.ssr.ui-tree/emit-ui-tree
                            (v1 {:tag :div
                                 :children [{:tag :span :attrs {:id "a" attribute-key "v"}}]})))))))))
  (testing "control: the same spelling under `data-` is an ordinary attribute"
    (is (= "<div data-dangerously-set-inner-html=\"v\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :div :attrs {:data-dangerously-set-inner-html "v"}})))))
  (testing "control: case decides no refusal of an ordinary slot, and no emitted name"
    (is (= "<div data-dangerously-set-inner-HTML=\"v\" tab-Index=\"1\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :div :attrs {:data-dangerously-set-inner-HTML "v"
                                    :tab-Index                       1}}))))))

(deftest emits-boolean-classes
  (testing "boolean attr: true -> presence, false -> omitted"
    (is (= "<input disabled=\"\">"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :input :attrs {:disabled true :checked false}})))))
  (testing "booleanish: true/false -> \"true\"/\"false\", never omitted"
    (is (= "<div contentEditable=\"false\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:content-editable false}})))))
  (testing "overloaded: true -> presence, false -> omitted, other -> value"
    (is (= "<a download=\"\"></a>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :a :attrs {:download true}}))))
    (is (= "<a download=\"file.txt\"></a>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :a :attrs {:download "file.txt"}})))))
  (testing "aria-* values always stringify, never omitted"
    (is (= "<div aria-hidden=\"false\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:aria-hidden false}}))))))

(deftest emits-style-in-pinned-order
  (testing ":style map -> css declaration, sorted by property name"
    (is (= "<div style=\"color:red;margin-top:0\"></div>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :attrs {:style {:margin-top "0"
                                                                       :color "red"}}}))))))

(deftest emits-void-elements-self-closed
  (is (= "<img src=\"a.png\">"
         (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :img :attrs {:src "a.png"}})))))

(deftest void-element-children-are-refused
  ;; react-dom/server throws for children on a void element; a serialiser
  ;; that self-closes the tag and drops them would hide a malformed tree.
  (testing "a text child on <br> fails loud at the element"
    (let [expected (malformed {:path [] :value ["lost"]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :br :children ["lost"]}))))))))
  (testing "a nested void element with an element child"
    (let [expected (malformed {:path [:children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :div
                               :children [{:tag :img :attrs {:src "a.png"}
                                           :children [{:tag :span :children ["x"]}]}]}))))))))
  (testing "control: an EMPTY children vector is no children"
    (is (= "<br>" (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :br :children []}))))))

(deftest drops-events-and-keys
  (testing "events never serialise into HTML; :key has no HTML presence;
            node-level :rf.ui/* diagnostic keys never emit"
    (is (= "<button>Go</button>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :button
                                             :events {:on-click [:go]}
                                             :key 7
                                             :rf.ui/presence {:phase :present}
                                             :rf.ui/boundary :client-only
                                             :children ["Go"]}))))))

(deftest splices-fragments-and-erases-view-boundaries
  (testing "fragment root splices its children with no wrapper"
    (is (= "<span>a</span><span>b</span>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:children [{:tag :span :children ["a"]}
                                                        {:tag :span :children ["b"]}]})))))
  (testing "view boundary is erased; its children splice, its :props are ignored"
    (is (= "<span>x</span>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:view-id :my/view
                                             :props {:whatever 1}
                                             :children [{:tag :span :children ["x"]}]}))))))

(deftest writes-trusted-html-verbatim
  (testing ":html node content is NOT escaped"
    (is (= "<div><b>raw</b></div>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :div :children [{:html "<b>raw</b>"}]}))))))

;; ---------------------------------------------------------------------------
;; Raw-text elements — <script>/<style> content. The string-content rows
;; (verbatim body, closing-sequence rewrite) are shared with both hiccup
;; emitters and pinned on all three paths in `re-frame.ssr-emit-test`.
;; ---------------------------------------------------------------------------

(deftest raw-text-script-style-is-not-html-escaped
  (testing "a childless raw-text element still emits an explicit close tag"
    (is (= "<script></script>" (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :script}))))))

(deftest raw-text-honors-ui-html-trusted-child
  (testing "a sole {:html s} child emits its trusted body VERBATIM — neither
            escaped nor closing-sequence-rewritten, exactly as react-dom pushes
            dangerouslySetInnerHTML — never the printed map"
    (is (= "<script>var s = '</script>';</script>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :script :children [{:html "var s = '</script>';"}]}))))))

(deftest raw-text-structural-child-fails-loud-not-stringified
  (testing "an element child under <script> is the SHARED malformed id, not stringified EDN"
    (let [expected (malformed {:path []})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree
                          (v1 {:tag :script :children [{:tag :b :children ["x"]}]}))))))))
  (testing "a non-string :html under <style> is the SAME shared malformed id, located at the child"
    (let [expected (malformed {:path [:children 0]})]
      (is (= expected
             (pinned expected
                     (caught-ex-data
                       #(rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :style :children [{:html 42}]}))))))))
  (testing "a body mixing string content with a structural child is malformed"
    (let [d (caught-ex-data
              #(rf.ssr.ui-tree/emit-ui-tree
                 (v1 {:tag :script :children ["const x=1;" {:html "y"}]})))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))))))

(deftest custom-element-property-props-omitted
  (testing "property-classified props never reach markup; attributes do"
    (is (= "<my-widget id=\"w\"></my-widget>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :my-widget
                                             :attrs {:help-text "hi" :id "w"}
                                             :rf.ui/property-props #{:help-text}}))))))

(deftest form-control-special-forms
  (testing ":default-value serialises as value"
    (is (= "<input value=\"d\">"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :input :attrs {:default-value "d"}})))))
  (testing ":value on :textarea serialises as the text child, not an attribute"
    (is (= "<textarea>hello</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "hello"}})))))
  (testing ":value on :select serialises as selected on the matching option"
    (is (= (str "<select>"
                "<option value=\"a\">A</option>"
                "<option selected=\"\" value=\"b\">B</option>"
                "</select>")
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :select
                  :attrs {:value "b"}
                  :children [{:tag :option :attrs {:value "a"} :children ["A"]}
                             {:tag :option :attrs {:value "b"} :children ["B"]}]})))))
  (testing "a MULTIPLE select's :value is a COLLECTION, and selects every option it names"
    ;; Comparing the collection itself against each option would mark
    ;; NOTHING, dropping the whole selection from the server render.
    (is (= (str "<select multiple=\"\">"
                "<option selected=\"\" value=\"a\">A</option>"
                "<option value=\"b\">B</option>"
                "<option selected=\"\" value=\"c\">C</option>"
                "</select>")
           (rf.ssr.ui-tree/emit-ui-tree
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
           (rf.ssr.ui-tree/emit-ui-tree
             (v1 {:tag :select
                  :attrs {:multiple true :value []}
                  :children [{:tag :option :attrs {:value "a"} :children ["A"]}
                             {:tag :option :attrs {:value "b"} :children ["B"]}]}))))))

;; ---------------------------------------------------------------------------
;; Newline-eating elements — leading-LF compensation. HTML parsing eats the
;; FIRST LF after <pre>/<listing>/<textarea>, so react-dom/server 19.2 prefixes
;; one compensating LF when the content is a SINGLE STRING beginning with LF.
;; The rows the three SSR paths share are pinned in
;; `re-frame.ssr-hiccup-newline-test`; these are the tree's own arms.
;; ---------------------------------------------------------------------------

(deftest leading-newline-compensated-for-pre-and-listing
  (testing "<pre> single text child beginning with LF gets the doubled LF"
    (is (= "<pre>\n\nhello</pre>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\nhello"]})))))
  (testing "pre child text is still HTML-escaped alongside the compensation"
    (is (= "<pre>\n\n&lt;a&gt; &amp; b</pre>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\n<a> & b"]}))))))

(deftest leading-newline-compensated-for-textarea-value
  (testing ":value on <textarea> beginning with LF gets the doubled LF"
    (is (= "<textarea>\n\nhello</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "\nhello"}})))))
  (testing "textarea :value is RCDATA-escaped alongside the compensation"
    (is (= "<textarea>\n\n&lt;a&gt;&amp;</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :attrs {:value "\n<a>&"}})))))
  (testing "a single string child (no :value) is compensated the same way"
    (is (= "<textarea>\n\nhi</textarea>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :textarea :children ["\nhi"]}))))))

(deftest leading-newline-own-lever-only-a-single-lf-string-child
  (testing "a leading CR (\\r) is NOT a newline-eating trigger — matches React"
    (is (= "<pre>\r\nhello</pre>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\r\nhello"]})))))
  (testing "MULTIPLE children ⇒ no compensation (React's single-string guard)"
    (is (= "<pre>\nab</pre>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :pre :children ["\na" "b"]}))))))

(deftest leading-newline-compensated-for-sole-trusted-html-child
  ;; React compensates a single string body for a string child AND for
  ;; `dangerouslySetInnerHTML.__html`, so a sole `{:html "\n…"}` child is
  ;; compensated too.
  (testing "<pre> sole {:html s} child beginning with LF gets React's compensating LF"
    (is (= "<pre>\n\n<b>x</b></pre>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :pre :children [{:html "\n<b>x</b>"}]})))))
  (testing "VACUITY: a :html body NOT beginning with LF gets no compensation"
    (is (= "<pre><b>x</b></pre>"
           (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag :pre :children [{:html "<b>x</b>"}]}))))))

(deftest opts-contract
  ;; `:doctype?` is the ONLY option, default off; other keys are ignored —
  ;; neither rejected nor honoured (Spec 004B §The SSR consumption boundary).
  (let [tree (v1 {:tag :html})]
    (testing ":doctype? true prefixes the doctype"
      (is (= "<!DOCTYPE html><html></html>"
             (rf.ssr.ui-tree/emit-ui-tree tree {:doctype? true}))))
    (testing "default (arity-1) emits no doctype"
      (is (= "<html></html>"
             (rf.ssr.ui-tree/emit-ui-tree tree))))
    (testing "unknown keys are ignored — not rejected, not honoured"
      (is (= "<!DOCTYPE html><html></html>"
             (rf.ssr.ui-tree/emit-ui-tree tree {:doctype? true :emit-hash? true :bogus 1}))))))
