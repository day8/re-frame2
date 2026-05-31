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
            [re-frame.ssr.html-helpers :as html]
            [re-frame.ssr.emit :as emit]))

(deftest attr-string-strips-event-handler-props
  (testing "rf2-dwds9 — structural `on*` handler spellings the re-frame
            hiccup adapters and react-dom/server recognise are dropped at
            emit time: camelCase `on[A-Z]…` and kebab `on-…`."
    (testing ":on-click (kebab) is stripped"
      (is (= " id=\"x\""
             (html/attr-string {:on-click "alert(1)" :id "x"}))))

    (testing ":onClick (camelCase) is stripped"
      (is (= " id=\"x\""
             (html/attr-string {:onClick "alert(1)" :id "x"}))))

    (testing ":onMouseDown (camelCase, multi-word) is stripped"
      (is (= " id=\"x\""
             (html/attr-string {:onMouseDown "alert(1)" :id "x"}))))

    (testing ":onCustomEvent (camelCase, framework-shaped non-HTML name)
              is stripped by the structural matcher"
      (is (= " id=\"x\""
             (html/attr-string {:onCustomEvent "alert(1)" :id "x"}))))

    (testing "`true`-valued on* boolean prop is also stripped (no bare attr)"
      (is (= " id=\"x\""
             (html/attr-string {:on-load true :id "x"}))))

    (testing "a map whose every entry is a stripped on* prop yields the
              empty string — no stray leading space"
      (is (= "" (html/attr-string {:on-click "f" :onScroll "g"})))))

  (testing "rf2-1uex4 — canonical all-lowercase HTML event-handler names
            are stripped. HTML attribute names are case-insensitive, so the
            browser fires `onclick`/`onload`/`onerror` identically; these
            are the canonical (and attacker-preferred) spellings the old
            camelCase/kebab-only regex MISSED, emitting a live handler on
            the wire."
    (doseq [k [:onclick :onload :onerror :onmouseover :onsubmit :onfocus]]
      (testing (str k " (lowercase canonical) is stripped")
        (is (= " id=\"x\""
               (html/attr-string {k "steal()" :id "x"}))
            (str (name k) " must not survive to wire output")))))

  (testing "rf2-1uex4 — arbitrary casings of a real handler name are
            stripped (attribute names are case-insensitive)"
    (doseq [k [:ONLOAD :OnClick :OnLoad :ONCLICK :onCLICK]]
      (testing (str k " (mixed/upper casing) is stripped")
        (is (= " id=\"x\""
               (html/attr-string {k "steal()" :id "x"}))
            (str (name k) " must not survive to wire output")))))

  (testing "rf2-1uex4 — the allowlist does NOT over-reach onto innocuous
            keys that merely begin with the letters `on`. `online` / `once`
            / `only` / `on` / `data-on` are legitimate attributes and MUST
            round-trip (a blind `starts-with? \"on\"` would eat them)."
    (let [out (html/attr-string {:data-on "ok" :one "1" :once "2"
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
           (html/attr-string {:title (fn [_] :handler) :id "x"})))

    (testing "fn value is stripped even when the key itself is innocuous"
      (is (= ""
             (html/attr-string {:data-cb (fn [] nil)}))))))

(deftest attr-string-drops-prototype-pollution-keys
  (testing "rf2-dwds9 — reserved prototype-pollution keys are dropped
            before they reach the host createElement-equivalent"
    (doseq [k ["__proto__" "constructor" "prototype"]]
      (testing (str "`" k "` is dropped")
        (is (= " id=\"x\""
               (html/attr-string {(keyword k) "polluted" :id "x"}))
            (str k " must not survive to wire output"))))

    (testing "the match is case-insensitive on the normalised name"
      (is (= " id=\"x\""
             (html/attr-string {(keyword "Constructor") "polluted" :id "x"}))))))

(deftest attr-string-normal-attrs-still-emit
  (testing "the filter does not over-reach — ordinary attrs round-trip"
    (let [out (html/attr-string {:id "main" :class "a b" :data-x "1"})]
      (is (str/includes? out "id=\"main\""))
      (is (str/includes? out "class=\"a b\""))
      (is (str/includes? out "data-x=\"1\""))))

  (testing "stripped props are filtered BEFORE the grammar gate — a prop
            that would otherwise throw `:rf.error/ssr-invalid-attribute-name`
            is silently dropped rather than raising"
    (is (= " id=\"x\""
           (html/attr-string {(keyword "onClick=alert(1) data-x") "v"
                              :id "x"})))))

(deftest render-to-string-strips-lowercase-handlers-end-to-end
  (testing "rf2-1uex4 — the canonical lowercase `on*` payload an attacker
            splats into `:custom-attrs` does NOT survive through the public
            emitter. The verified repro was `[:img {:src \"x\" :onerror
            \"alert(document.cookie)\"}]` rendering the live handler on a
            void element; the wire output MUST carry no `onerror`."
    (let [out (emit/render-to-string
               [:img {:src "x" :onerror "alert(document.cookie)"}] {})]
      (is (str/includes? out "src=\"x\"") "the legitimate attr survives")
      (is (not (str/includes? (str/lower-case out) "onerror"))
          "the lowercase event-handler attr must be stripped from the wire")
      (is (not (str/includes? out "alert(document.cookie)"))
          "no live handler payload on the wire"))

    (testing "uppercase casing through the emitter is also stripped"
      (let [out (emit/render-to-string
                 [:img {:src "x" :ONLOAD "steal()"}] {})]
        (is (not (str/includes? (str/lower-case out) "onload")))
        (is (not (str/includes? out "steal()")))))))
