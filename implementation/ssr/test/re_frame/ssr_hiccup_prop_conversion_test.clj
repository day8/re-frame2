(ns re-frame.ssr-hiccup-prop-conversion-test
  "rf2-3x7nj.13.1 and rf2-slr59 — the two hiccup BODY walkers
  (`render-to-string` and the streaming `render-shell`) paint what the
  hydrating Reagent-tier client paints, not the author's attribute map
  verbatim.

  Every expected string below was taken from the supported adapters' prop
  conversion (stock Reagent 2.0.1 `dash-to-prop-name` / `class-names`, which
  reagent-slim copies) fed through the installed react-dom 19.3.0's
  `renderToStaticMarkup` — never from another local table. Two spellings
  differ from react-dom's bytes without differing in the DOM: this emitter
  writes a presence attribute bare (`readOnly`) where react-dom writes
  `readOnly=\"\"`, and it keeps the author's attribute order where react-dom
  moves `checked` / `value` to the end of an `<input>`.

  Each row runs through BOTH walkers, because they share one conversion
  (`re-frame.ssr.emit/dom-element-props`) and a repair landing on one of them
  only is the drift this pins."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.streaming :as rf.ssr.streaming]))

(defn- both-walkers
  "`[render-to-string-html render-shell-html]` for `hiccup`."
  [hiccup]
  [(rf.ssr.emit/render-to-string hiccup {})
   (:shell-html (rf.ssr.streaming/render-shell hiccup))])

(defn- check-rows [rows]
  (doseq [[hiccup expected] rows]
    (let [[sync-html shell-html] (both-walkers hiccup)]
      (is (= expected sync-html)
          (str "render-to-string " (pr-str hiccup)))
      (is (= expected shell-html)
          (str "render-shell " (pr-str hiccup))))))

(deftest names-and-values-convert-the-way-the-client-does
  (testing "rf2-3x7nj.13.1 — each of these was painted verbatim before, as an
            attribute the browser does not recognise, a keyword's print, or a
            class vector's EDN; React neither patches nor reports the
            difference at hydration"
    (check-rows
      [;; kebab names → Reagent's camel prop → react-dom's attribute
       [[:input {:read-only true :tab-index 0}]    "<input readOnly tabindex=\"0\">"]
       [[:label {:html-for "e"} "Email"]          "<label for=\"e\">Email</label>"]
       [[:svg {:view-box "0 0 10 10"}]            "<svg viewBox=\"0 0 10 10\"></svg>"]
       [[:td {:col-span 2} "x"]                   "<td colSpan=\"2\">x</td>"]
       ;; rf2-u0xpc — Reagent's prop is `panose1`, which react-dom writes
       ;; verbatim (its alias row is keyed on `panose-1`)
       [[:svg {:panose-1 "2 0 0 0"}]              "<svg panose1=\"2 0 0 0\"></svg>"]
       ;; React spellings, as keywords and as a string key
       [[:div {:className "c"}]                   "<div class=\"c\"></div>"]
       [[:div.x {:className "c"}]                 "<div class=\"x c\"></div>"]
       [[:label {:htmlFor "e"}]                   "<label for=\"e\"></label>"]
       [[:div {"className" "c"}]                  "<div class=\"c\"></div>"]
       ;; keyword values → `name`
       [[:button {:type :button} "Cancel"]        "<button type=\"button\">Cancel</button>"]
       [[:input {:type :checkbox}]                "<input type=\"checkbox\">"]
       [[:div {:data-state :open}]                "<div data-state=\"open\"></div>"]
       ;; class collections → joined, nil and false dropped
       [[:div {:class ["a" nil false "b"]}]       "<div class=\"a b\"></div>"]
       [[:div.x {:class [:a nil false :b]}]       "<div class=\"x a b\"></div>"]
       [[:div {:class :active}]                   "<div class=\"active\"></div>"]])))

(deftest a-converted-handler-name-is-still-stripped
  (testing "rf2-3x7nj.13.1 — the regression this conversion could introduce.
            `:onc-lick` was painted as the harmless `onc-lick=`; converted it
            is `oncLick`, which the HTML parser reads as `onclick`, a live
            handler. It is stripped only because the strip reads the
            CONVERTED name"
    (check-rows
      [[[:div {:onc-lick "alert(1)" :id "ok"}]    "<div id=\"ok\"></div>"]])))

(deftest already-correct-names-are-unchanged
  (testing "controls — names the client paints as authored stay as they were"
    (check-rows
      [[[:div {:class "a"}]                       "<div class=\"a\"></div>"]
       [[:label {:for "e"}]                       "<label for=\"e\"></label>"]
       [[:div {:id "i" :data-x "1" :aria-label "l"}]
        "<div id=\"i\" data-x=\"1\" aria-label=\"l\"></div>"]
       ;; Reagent camelCases these and react-dom's alias table maps them
       ;; straight back to the hyphenated DOM name.
       [[:line {:stroke-width 2 :http-equiv "x" :accept-charset "u"}]
        "<line stroke-width=\"2\" http-equiv=\"x\" accept-charset=\"u\"></line>"]
       ;; A string key skips Reagent's step; it has no react-dom alias.
       [[:div {"hx-post" "/x"}]                   "<div hx-post=\"/x\"></div>"]]))

  (testing "the attribute-name grammar still refuses a hostile key"
    (doseq [render [#(rf.ssr.emit/render-to-string % {})
                    #(rf.ssr.streaming/render-shell %)]]
      (let [thrown (try (render [:div {(keyword "onclick=alert(1) x") "y"}])
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/ssr-invalid-attribute-name
               (:rf.error/id (ex-data thrown))))))))

(deftest a-custom-element-keeps-its-names
  (testing "a hyphenated tag keeps its author names verbatim (the two
            supported adapters already disagree there, and web components
            conventionally read kebab attributes), while its keyword VALUES
            and its class collection convert as on any DOM tag"
    (check-rows
      [[[:my-el {:some-prop "x" :state :open :class ["a" "b"]}]
        "<my-el some-prop=\"x\" state=\"open\" class=\"a b\"></my-el>"]])))

(deftest the-kebab-inner-html-spelling-is-not-the-raw-html-channel
  (testing "`:dangerously-set-inner-html` converts, by Reagent's own rule, to
            `dangerouslySetInnerHtml` — NOT React's `dangerouslySetInnerHTML`
            — so the client paints it as an ordinary unknown attribute and
            never as content. Pinned here so nobody reads the kebab spelling
            as a way to reach the raw-HTML channel"
    (doseq [html (both-walkers [:div {:dangerously-set-inner-html {:__html "<b>x</b>"}}])]
      (is (str/starts-with? html "<div dangerouslySetInnerHtml=\"") html)
      (is (str/ends-with? html "\"></div>") html))))

(defn- option-view [value label]
  [:option {:value value} label])

(deftest form-controls-take-react-dom-special-forms
  (testing "rf2-slr59 — a textarea's value is its text body"
    (check-rows
      [[[:textarea {:value "abc"}]                "<textarea>abc</textarea>"]
       [[:textarea {:default-value "abc"}]        "<textarea>abc</textarea>"]
       [[:textarea {:value "a<b"}]                "<textarea>a&lt;b</textarea>"]
       ;; the rf2-s7l5 leading-LF compensation applies to a :value body too
       [[:textarea {:value "\nabc"}]              "<textarea>\n\nabc</textarea>"]
       ;; the client takes the value and never reads the child
       [[:textarea {:value "v"} "child"]          "<textarea>v</textarea>"]
       ;; with no value the child is the body, as before
       [[:textarea "child"]                       "<textarea>child</textarea>"]]))

  (testing "rf2-slr59 — a select's value marks its matching options"
    (check-rows
      [[[:select {:value "b"} [:option {:value "a"} "A"] [:option {:value "b"} "B"]]
        "<select><option value=\"a\">A</option><option value=\"b\" selected>B</option></select>"]
       ;; matched on the option's text when it has no value
       [[:select {:default-value "B"} [:option "A"] [:option "B"]]
        "<select><option>A</option><option selected>B</option></select>"]
       ;; a collection names several (a `multiple` select)
       [[:select {:multiple true :value ["a" "c"]}
         [:option {:value "a"} "A"] [:option {:value "b"} "B"] [:option {:value "c"} "C"]]
        (str "<select multiple><option value=\"a\" selected>A</option>"
             "<option value=\"b\">B</option><option value=\"c\" selected>C</option></select>")]
       ;; through an optgroup and a `for`, with a keyword value
       [[:select {:value :b} [:optgroup {:label "g"} (for [v ["a" "b"]] [:option {:value v} v])]]
        (str "<select><optgroup label=\"g\"><option value=\"a\">a</option>"
             "<option value=\"b\" selected>b</option></optgroup></select>")]
       ;; options produced by a component, matched on a number
       [[:select {:value 2} [option-view 1 "one"] [option-view 2 "two"]]
        "<select><option value=\"1\">one</option><option value=\"2\" selected>two</option></select>"]
       ;; while the select has a value an option's own :selected is ignored
       [[:select {:value "b"} [:option {:value "a" :selected true} "A"] [:option {:value "b"} "B"]]
        "<select><option value=\"a\">A</option><option value=\"b\" selected>B</option></select>"]
       ;; without one it stands
       [[:select [:option {:value "a"} "A"] [:option {:value "b" :selected true} "B"]]
        "<select><option value=\"a\">A</option><option value=\"b\" selected>B</option></select>"]]))

  (testing "rf2-slr59 — an input's default props write value / checked"
    (check-rows
      [[[:input {:default-value "x" :default-checked true}]  "<input value=\"x\" checked>"]
       [[:input {:value "v" :default-value "x"}]             "<input value=\"v\">"]
       ;; react-dom drops both default props on every other element
       [[:div {:default-value "x" :default-checked true}]    "<div></div>"]])))
