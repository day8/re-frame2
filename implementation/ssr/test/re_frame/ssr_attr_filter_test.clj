(ns re-frame.ssr-attr-filter-test
  "Spec 011 §XSS at output boundaries — rule rf2-dwds9 (+ rf2-1uex4): the
  SSR static-markup emitter MUST strip, at attribute-emit time:

    - `on*` event-handler props. Detection is CASE-INSENSITIVE and covers
      both the framework-shaped structural spellings (camelCase `on[A-Z]…`
      and kebab `on-…`) and the WHATWG canonical all-lowercase HTML
      event-handler names. So `:on-click` / `:onClick` / `:onclick` /
      `:onload` / `:onerror` / `:ONLOAD` / `:OnClick` ALL filter out,
      while non-handler keys (`:online` / `:once` / `:only` / `:on`)
      round-trip (rf2-1uex4 — HTML attribute names are case-insensitive,
      so the canonical lowercase + arbitrary `on`-prefix casings were the
      live XSS hole the camelCase/kebab-only regex missed).
    - function-valued prop values, and
    - reserved prototype-pollution keys (`__proto__` / `constructor` /
      `prototype`),

  matching react-dom/server behaviour. The filter is the per-attribute
  prop-name position in the locked emitter composition order, so it runs
  ahead of the attribute-name grammar gate (rf2-vl8ir) — a stripped prop
  never reaches `validate-attr-name!`.

  These exercise `re-frame.ssr.html-helpers/attr-string` directly (the
  single shared per-attribute emission point used by the main emitter,
  the head emitter, and the streaming emitter)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.emit :as rf.ssr.emit]))

(deftest attr-string-strips-event-handler-props
  (testing "rf2-dwds9 — structural `on*` handler spellings the re-frame
            hiccup adapters and react-dom/server recognise are dropped at
            emit time: camelCase `on[A-Z]…` and kebab `on-…`."
    (testing ":on-click (kebab) is stripped"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string {:on-click "alert(1)" :id "x"}))))

    (testing ":onClick (camelCase) is stripped"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string {:onClick "alert(1)" :id "x"}))))

    (testing ":onMouseDown (camelCase, multi-word) is stripped"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string {:onMouseDown "alert(1)" :id "x"}))))

    (testing ":onCustomEvent (camelCase, framework-shaped non-HTML name)
              is stripped by the structural matcher"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string {:onCustomEvent "alert(1)" :id "x"}))))

    (testing "`true`-valued on* boolean prop is also stripped (no bare attr)"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string {:on-load true :id "x"}))))

    (testing "a map whose every entry is a stripped on* prop yields the
              empty string — no stray leading space"
      (is (= "" (rf.ssr.html-helpers/attr-string {:on-click "f" :onScroll "g"})))))

  (testing "rf2-1uex4 — canonical all-lowercase HTML event-handler names
            are stripped. HTML attribute names are case-insensitive, so the
            browser fires `onclick`/`onload`/`onerror` identically; these
            are the canonical (and attacker-preferred) spellings the old
            camelCase/kebab-only regex MISSED, emitting a live handler on
            the wire."
    (doseq [k [:onclick :onload :onerror :onmouseover :onsubmit :onfocus]]
      (testing (str k " (lowercase canonical) is stripped")
        (is (= " id=\"x\""
               (rf.ssr.html-helpers/attr-string {k "steal()" :id "x"}))
            (str (name k) " must not survive to wire output")))))

  (testing "rf2-1uex4 — arbitrary casings of a real handler name are
            stripped (attribute names are case-insensitive)"
    (doseq [k [:ONLOAD :OnClick :OnLoad :ONCLICK :onCLICK]]
      (testing (str k " (mixed/upper casing) is stripped")
        (is (= " id=\"x\""
               (rf.ssr.html-helpers/attr-string {k "steal()" :id "x"}))
            (str (name k) " must not survive to wire output")))))

  (testing "rf2-1uex4 — the allowlist does NOT over-reach onto innocuous
            keys that merely begin with the letters `on`. `online` / `once`
            / `only` / `on` / `data-on` are legitimate attributes and MUST
            round-trip (a blind `starts-with? \"on\"` would eat them)."
    (let [out (rf.ssr.html-helpers/attr-string {:data-on "ok" :one "1" :once "2"
                                 :online "3" :only "4" :on "5"})]
      (is (str/includes? out "data-on=\"ok\""))
      (is (str/includes? out "one=\"1\""))
      (is (str/includes? out "once=\"2\""))
      (is (str/includes? out "online=\"3\""))
      (is (str/includes? out "only=\"4\""))
      (is (str/includes? out "on=\"5\"")))))

(deftest attr-string-strips-function-valued-props
  (testing "rf2-dwds9 — function-valued props have no HTML serialisation
            and are dropped (a fn can only be a handler/callback)"
    (is (= " id=\"x\""
           (rf.ssr.html-helpers/attr-string {:title (fn [_] :handler) :id "x"})))

    (testing "fn value is stripped even when the key itself is innocuous"
      (is (= ""
             (rf.ssr.html-helpers/attr-string {:data-cb (fn [] nil)}))))))

(deftest attr-string-drops-prototype-pollution-keys
  (testing "rf2-dwds9 — reserved prototype-pollution keys are dropped
            before they reach the host createElement-equivalent"
    (doseq [k ["__proto__" "constructor" "prototype"]]
      (testing (str "`" k "` is dropped")
        (is (= " id=\"x\""
               (rf.ssr.html-helpers/attr-string {(keyword k) "polluted" :id "x"}))
            (str k " must not survive to wire output"))))

    (testing "the match is case-insensitive on the normalised name"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string {(keyword "Constructor") "polluted" :id "x"}))))))

(deftest attr-string-normal-attrs-still-emit
  (testing "the filter does not over-reach — ordinary attrs round-trip"
    (let [out (rf.ssr.html-helpers/attr-string {:id "main" :class "a b" :data-x "1"})]
      (is (str/includes? out "id=\"main\""))
      (is (str/includes? out "class=\"a b\""))
      (is (str/includes? out "data-x=\"1\""))))

  (testing "stripped props are filtered BEFORE the grammar gate — a prop
            that would otherwise throw `:rf.error/ssr-invalid-attribute-name`
            is silently dropped rather than raising"
    (is (= " id=\"x\""
           (rf.ssr.html-helpers/attr-string {(keyword "onClick=alert(1) data-x") "v"
                              :id "x"})))))

(deftest attr-string-serialises-style-map
  (testing "rf2-l6h6a — a map-valued `:style` serialises to a CSS declaration
            string (matching react-dom/server's `pushStyleAttribute`), NOT the
            EDN print of the map. Before the fix `{:margin \"0 1em\"}` rendered
            the literal `style=\"{:margin &quot;0 1em&quot;}\"`, and React 19
            logged a hydration attribute mismatch on every SSR app's first load."
    (testing "the reported repro: a single string-valued declaration"
      (is (= " style=\"margin:0 1em\""
             (rf.ssr.html-helpers/attr-string {:style {:margin "0 1em"}})))
      (is (not (str/includes? (rf.ssr.html-helpers/attr-string {:style {:margin "0 1em"}})
                              "{:margin"))
          "the raw EDN map text must never reach the wire"))

    (testing "camelCase property names → kebab CSS names (React's rule)"
      (is (= " style=\"margin-top:4px\""
             (rf.ssr.html-helpers/attr-string {:style {:marginTop "4px"}}))))

    (testing "an already-kebab property name is unchanged"
      (is (= " style=\"background-color:red\""
             (rf.ssr.html-helpers/attr-string {:style {:background-color "red"}}))))

    (testing "a keyword value renders bare (its name)"
      (is (= " style=\"display:flex\""
             (rf.ssr.html-helpers/attr-string {:style {:display :flex}}))))

    (testing "a numeric value on a non-unitless property gets a px suffix"
      (is (= " style=\"width:10px\""
             (rf.ssr.html-helpers/attr-string {:style {:width 10}}))))

    (testing "a numeric value of 0 is bare (no px)"
      (is (= " style=\"margin:0\""
             (rf.ssr.html-helpers/attr-string {:style {:margin 0}}))))

    (testing "a numeric value on a unitless property is bare (no px)"
      (is (= " style=\"flex-grow:1\""
             (rf.ssr.html-helpers/attr-string {:style {:flex-grow 1}})))
      (is (= " style=\"z-index:100\""
             (rf.ssr.html-helpers/attr-string {:style {:z-index 100}}))))

    (testing "nil / boolean / empty-string entries are omitted entirely"
      (is (= " style=\"color:red\""
             (rf.ssr.html-helpers/attr-string {:style {:color "red" :top nil
                                        :bottom false :left ""}}))))

    (testing "CSS custom properties (--foo) pass through verbatim, no px"
      (is (= " style=\"--gap:8\""
             (rf.ssr.html-helpers/attr-string {:style {:--gap 8}}))))

    (testing "the CSS string is attribute-escaped (double-quote in a value)"
      (is (= " style=\"font-family:&quot;My Font&quot;, sans-serif\""
             (rf.ssr.html-helpers/attr-string {:style {:font-family "\"My Font\", sans-serif"}}))))

    (testing "a STRING :style value is already CSS and rides through untouched"
      (is (= " style=\"margin:0 1em\""
             (rf.ssr.html-helpers/attr-string {:style "margin:0 1em"}))))

    (testing "multiple declarations join with `;` in map order"
      (is (= " style=\"margin:0;padding:4px\""
             (rf.ssr.html-helpers/attr-string {:style (array-map :margin 0 :padding "4px")}))))))

(deftest render-to-string-serialises-style-map-through-full-emit
  (testing "rf2-l6h6a — the style-map → CSS serialisation survives the FULL
            `render-to-string` emit composition (not just `attr-string` in
            isolation), so the wire markup for a `:style` map is CSS and the
            server/client render agree (no hydration attribute mismatch)."
    (let [html-out (rf.ssr.emit/render-to-string
                     [:div {:style {:margin "0 1em" :color :red}} "hi"] {})]
      (is (= "<div style=\"margin:0 1em;color:red\">hi</div>" html-out))
      (is (not (str/includes? html-out "{:margin"))
          "no raw EDN map text on the wire — the hydration-mismatch defect is gone"))))

(deftest render-to-string-strips-lowercase-handlers-end-to-end
  (testing "rf2-1uex4 — the canonical lowercase `on*` payload an attacker
            splats into `:custom-attrs` does NOT survive through the public
            emitter. The verified repro was `[:img {:src \"x\" :onerror
            \"alert(document.cookie)\"}]` rendering the live handler on a
            void element; the wire output MUST carry no `onerror`."
    (let [out (rf.ssr.emit/render-to-string
               [:img {:src "x" :onerror "alert(document.cookie)"}] {})]
      (is (str/includes? out "src=\"x\"") "the legitimate attr survives")
      (is (not (str/includes? (str/lower-case out) "onerror"))
          "the lowercase event-handler attr must be stripped from the wire")
      (is (not (str/includes? out "alert(document.cookie)"))
          "no live handler payload on the wire"))

    (testing "uppercase casing through the emitter is also stripped"
      (let [out (rf.ssr.emit/render-to-string
                 [:img {:src "x" :ONLOAD "steal()"}] {})]
        (is (not (str/includes? (str/lower-case out) "onload")))
        (is (not (str/includes? out "steal()")))))))
