(ns re-frame.realworld-markdown-cljs-test
  "Shared CommonMark output preserves content while raw HTML and unsafe URL schemes remain inert."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing is]]
            [realworld-shared.markdown :as md]))

(defn- elements
  [tree]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [node]
        (when (and (vector? node) (keyword? (first node)))
          (swap! acc conj node))
        node)
      tree)
    @acc))

(defn- tags
  [tree]
  (into #{} (map first) (elements tree)))

(defn- attrs-of
  [tree]
  (into []
        (keep (fn [node]
                (when (map? (second node))
                  (second node))))
        (elements tree)))

(defn- strings-of
  [tree]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [node]
        (when (string? node) (swap! acc conj node))
        node)
      tree)
    (str/join " " @acc)))

;; The parser also rejects schemes; pin our sanitization layer directly.
(deftest safe-url-predicate-allowlist
  (testing "allowlisted absolute schemes + relative navigation are safe"
    (is (= [] (remove md/safe-url? ["https://example.com" "http://example.com" "mailto:a@b.c"
                                    "/relative/path" "#fragment" "./sibling" "../parent"]))))
  (testing "script-bearing schemes are rejected (incl. casing / whitespace tricks)"
    (is (= [] (filter md/safe-url? ["javascript:alert(1)" "JavaScript:alert(1)" "  javascript:alert(1)"
                                    "vbscript:msgbox(1)" "data:text/html,<script>alert(1)</script>"
                                    "file:///etc/passwd" "" nil])))))

(deftest no-dangerously-set-inner-html
  (testing "no emitted attribute map carries :dangerouslySetInnerHTML for ANY input"
    (doseq [src ["# heading\n\nplain **text**"
                 "<script>alert(1)</script>"
                 "<div onclick=\"x\">raw block</div>"
                 "[x](javascript:alert(1))"
                 "![y](data:text/html;base64,PHNjcmlwdD4=)"]]
      (let [tree (md/render src)]
        (is (not (some #(contains? % :dangerouslySetInnerHTML) (attrs-of tree))))))))

(deftest script-injection-is-inert
  (testing "a <script> injection never becomes a live :script element"
    (let [tree (md/render "Hello\n\n<script>window.__pwned = true</script>\n\nbye")]
      (is (= [false true]
             [(contains? (tags tree) :script) (str/includes? (strings-of tree) "window.__pwned")]))))

  (testing "raw inline HTML with an attribute is inert (no live element)"
    (let [tree (md/render "before <b onclick=\"boom()\">x</b> after")]
      (is (= [false true]
             [(boolean (some #(contains? % :onclick) (attrs-of tree)))
              (str/includes? (strings-of tree) "onclick")])))))

(deftest img-onerror-injection-is-inert
  (testing "a raw <img onerror=...> injection never becomes a live :img with onerror"
    (let [tree (md/render "<img src=x onerror=alert(1)>")]
      (is (= [true true]
             [(not (some #(contains? % :onerror) (attrs-of tree)))
              (str/includes? (strings-of tree) "onerror")])))))

(defn- attr-values
  [tree k]
  (into [] (keep #(get % k)) (attrs-of tree)))

(deftest unsafe-link-href-never-reaches-a-live-attribute
  (testing "a javascript: link never yields a live :href; text survives"
    (let [tree (md/render "click [here](javascript:alert(document.cookie))")]
      (is (= [true true]
             [(not (some #(str/includes? (str %) "javascript:") (attr-values tree :href)))
              (str/includes? (strings-of tree) "here")]))))

  (testing "vbscript: and data: links never yield a live :href"
    (doseq [scheme ["vbscript:msgbox(1)" "data:text/html,<script>alert(1)</script>"]]
      (let [tree (md/render (str "[x](" scheme ")"))]
        (is (empty? (filter #(or (str/includes? (str %) "vbscript:")
                                 (str/includes? (str %) "data:"))
                            (attr-values tree :href)))))))

  (testing "a safe http(s) / relative / mailto href IS preserved on an anchor"
    (doseq [[href expect] [["https://example.com" "https://example.com"]
                           ["/relative/path"      "/relative/path"]
                           ["mailto:a@b.c"        "mailto:a@b.c"]
                           ["#anchor"             "#anchor"]]]
      (let [tree    (md/render (str "[x](" href ")"))
            anchors (filter #(= :a (first %)) (elements tree))]
        (is (some (fn [a] (= expect (:href (second a)))) anchors))))))

(deftest unsafe-image-src-never-reaches-a-live-attribute
  (testing "a javascript:/data: image src never yields a live :src"
    (doseq [src ["javascript:alert(1)" "data:text/html,<script>alert(1)</script>"]]
      (let [tree (md/render (str "![alt](" src ")"))]
        (is (empty? (filter #(or (str/includes? (str %) "javascript:")
                                 (str/includes? (str %) "data:"))
                            (attr-values tree :src))))))))

(deftest renders-gfm-table
  (testing "a GFM table renders as a real table structure"
    (let [tree (md/render (str "| A | B |\n"
                               "| --- | --- |\n"
                               "| 1 | 2 |\n"))
          ts   (tags tree)
          text (strings-of tree)]
      (is (= [true true true]
             [(contains? ts :table)
              (or (contains? ts :th) (contains? ts :td))
              (every? #(str/includes? text %) ["A" "1"])])))))

(deftest renders-nested-list
  (testing "a nested unordered list renders nested :ul structure"
    (let [tree (md/render (str "- top\n"
                               "  - nested-a\n"
                               "  - nested-b\n"))
          uls  (filter #(= :ul (first %)) (elements tree))
          text (strings-of tree)]
      (is (= [true true]
             [(>= (count uls) 2) (every? #(str/includes? text %) ["top" "nested-a" "nested-b"])])))))

(deftest renders-image
  (testing "an image renders as a real :img with src + alt"
    (let [tree (md/render "![a kitten](https://example.com/kitten.png)")
          imgs (filter #(= :img (first %)) (elements tree))]
      (is (some (fn [i] (= ["https://example.com/kitten.png" "a kitten"]
                           ((juxt :src :alt) (second i))))
                imgs)))))

(deftest renders-baseline-shapes
  (testing "headings, emphasis, code, and blockquote render"
    (let [tree (md/render (str "# Title\n\n"
                               "A **bold** and *italic* and `code` span.\n\n"
                               "> a quote\n"))
          ts   (tags tree)]
      (is (= [] (remove ts [:h1 :strong :em :code :blockquote])))))

  (testing "a blank / nil source renders an empty fragment without throwing"
    (is (= [[:<>] [:<>] [:<>]] (map md/render [nil "" "   \n  "])))))
