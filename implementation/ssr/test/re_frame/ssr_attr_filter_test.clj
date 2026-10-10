(ns re-frame.ssr-attr-filter-test
  "Spec 011 §XSS at output boundaries: `html-helpers/attr-string`, the one
  per-attribute emission point every SSR surface serialises through, drops
  `on*` handlers, fn values, prototype-pollution keys, JSX source-coord props,
  React's structural slots (`:key` / `:ref`) and content channels
  (`:children` / `:dangerouslySetInnerHTML`), ahead of the attribute-name
  grammar gate."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.head.emit :as rf.ssr.head.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.emit :as rf.ssr.emit]))

(deftest attr-string-strips-event-handler-props
  (testing "Structural spellings: kebab `on-…` and camelCase `on[A-Z]…`"
    (is (= " id=\"x\""
           (rf.ssr.html-helpers/attr-string {:on-click "alert(1)" :id "x"})))
    (is (= " id=\"x\""
           (rf.ssr.html-helpers/attr-string {:onCustomEvent "alert(1)" :id "x"}))))

  (testing "The canonical all-lowercase names a camelCase/kebab regex misses,
            and an arbitrary casing — HTML attribute names are
            case-insensitive"
    (doseq [k [:onclick :ONLOAD]]
      (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {k "steal()" :id "x"}))
          (str (name k) " must not survive to wire output"))))

  (testing "Keys that merely begin with `on` round-trip"
    (is (= " data-on=\"ok\" one=\"1\" once=\"2\" online=\"3\" only=\"4\" on=\"5\""
           (rf.ssr.html-helpers/attr-string {:data-on "ok" :one "1" :once "2"
                                             :online "3" :only "4" :on "5"}))))

  (testing "A stripped prop is filtered before the grammar gate, so a key that
            would otherwise throw is silently dropped"
    (is (= " id=\"x\""
           (rf.ssr.html-helpers/attr-string {(keyword "onClick=alert(1) data-x") "v"
                                             :id "x"})))))

(deftest attr-string-drops-jsx-source-coord-props
  (testing "The filter sits upstream of the grammar gate, which a leading
            underscore fails, so a JSX-stamped element emits clean markup
            rather than throwing"
    (is (= "<div></div>"
           (rf.ssr.emit/render-to-string
             [:div {:_jsxFileName     "app.cljs"
                    :_jsxLineNumber   12
                    :_jsxColumnNumber 3}]
             {}))))

  (testing "The match is case-sensitive against exactly the three documented
            names, so an app's own underscore-led prop still reaches the
            grammar gate"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-invalid-attribute-name"
          (rf.ssr.html-helpers/attr-string {:_JsxFileName "app.cljs"})))))

(deftest attr-string-drops-reacts-structural-slots
  (testing "React consumes `:key` and `:ref` and react-dom/server writes
            neither"
    (is (= "" (rf.ssr.html-helpers/attr-string {:key "k"}))
        "a props map that is ONLY a :key yields the empty string, not a
         stray leading space")
    (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {:ref "R" :id "x"}))
        "a STRING ref — the arm reachable past the fn? filter"))

  (testing "Case-sensitive: React extracts the slots by exact property name,
            so `:Key` is an ordinary prop the client paints"
    (is (= " Key=\"k\"" (rf.ssr.html-helpers/attr-string {:Key "k"})))))

(deftest structural-slots-drop-on-every-emitter-that-shares-the-roster
  (testing "The drop lives in the shared roster rather than one emitter, so
            the body and head emitters both read it"
    (is (= "<li>1</li><li>2</li>"
           (rf.ssr.emit/render-to-string
             (into [:<>] (for [i [1 2]] [:li {:key i} i])) {}))
        "the canonical keyed-list idiom")
    (is (= "<meta name=\"a\" content=\"b\">"
           (rf.ssr.head.emit/head-model->html
             {:meta [{:key "m1" :name "a" :content "b"}]}))
        "a head model built from a keyed component list")))

(deftest attr-string-drops-reacts-content-channels
  (testing "Left in, `:children` would reach the wire as an attribute and the
            raw-HTML channel as the escaped EDN of its map"
    (is (= "" (rf.ssr.html-helpers/attr-string {:children ["a" "b"]})))
    (is (= " id=\"x\""
           (rf.ssr.html-helpers/attr-string
             {:dangerouslySetInnerHTML {:__html "<b>x</b>"} :id "x"}))))

  (testing "Case-sensitive, for the same reason as the structural slots"
    (is (= " dangerouslysetinnerhtml=\"v\""
           (rf.ssr.html-helpers/attr-string {:dangerouslysetinnerhtml "v"})))))

(deftest content-channels-drop-on-every-emitter-that-shares-the-roster
  (is (= "<div id=\"a\">x</div>"
         (rf.ssr.emit/render-to-string [:div {:children "c" :id "a"} "x"] {}))
      "the body emitter drops the prop and keeps the real children")
  (is (= "<meta name=\"a\">"
         (rf.ssr.head.emit/head-model->html
           {:meta [{:dangerouslySetInnerHTML {:__html "<b>x</b>"} :name "a"}]}))))

(deftest attr-string-serialises-style-map
  (testing "A map `:style` serialises to CSS as react-dom/server's
            `pushStyleAttribute` does, not to the EDN print of the map"
    (doseq [[style expected]
            [[{:marginTop "4px"}                       "margin-top:4px"]
             [{:background-color "red"}                "background-color:red"]
             [{:display :flex}                         "display:flex"]
             [{:width 10}                              "width:10px"]
             [{:margin 0}                              "margin:0"]
             [{:flex-grow 1}                           "flex-grow:1"]
             [{:color "red" :top nil :bottom false :left ""} "color:red"]
             [{:--gap 8}                               "--gap:8"]
             [{:font-family "\"My Font\", sans-serif"} "font-family:&quot;My Font&quot;, sans-serif"]
             ["margin:0 1em"                           "margin:0 1em"]
             [(array-map :margin 0 :padding "4px")     "margin:0;padding:4px"]]]
      (is (= (str " style=\"" expected "\"")
             (rf.ssr.html-helpers/attr-string {:style style}))
          (pr-str style)))))

(deftest render-to-string-serialises-style-map-through-full-emit
  (is (= "<div style=\"margin:0 1em;color:red\">hi</div>"
         (rf.ssr.emit/render-to-string
           [:div {:style {:margin "0 1em" :color :red}} "hi"] {}))))
