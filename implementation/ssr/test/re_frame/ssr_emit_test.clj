(ns re-frame.ssr-emit-test
  "Spec 011 §XSS at output boundaries — the strip-prop rule (rf2-dwds9)
  driven through the FULL emit composition, not just `attr-string` in
  isolation. Per rf2-usio0 (testcov audit ai/findings/2026-05-21-testcov-ssr.md
  §G1).

  `ssr_attr_filter_test.clj` proves the rule at the per-attribute unit
  level (`html-helpers/attr-string` called directly). That is necessary
  but not sufficient: the emitter COMPOSES `attr-string` inside
  `emit-element` (emit.cljc:323-326) and inside the streaming walker
  (streaming.cljc:225-227), and the strip MUST run AHEAD of the
  attribute-name grammar gate (html_helpers.cljc:179-189) at that
  composed callsite. A regression that reorders the emitter composition,
  or that bypasses `attr-string` for some attr path in `emit-element` /
  `walk-dom-tag`, would pass every per-attribute test while leaking a
  hostile `on*` handler / fn-valued prop / prototype-pollution key onto
  the wire.

  These tests therefore feed hostile props through:

    1. `re-frame.ssr.emit/render-to-string` — the public non-streaming
       emitter, including the void-element branch, the registered-view
       branch, fragments, and the root-attrs (`:emit-hash?`) injection.
    2. `re-frame.ssr.streaming/render-shell` — the streaming shell walk,
       whose `walk-dom-tag` re-derives attrs via `emit/attr-string`.

  so a reorder/bypass regression at EITHER composed callsite is caught.

  JVM-only — the strip rule is platform-neutral .cljc, but the per-attr
  proof already runs on both platforms (`ssr_attr_filter_test`); driving
  the JVM emit composition here is enough to pin the composition order."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ===========================================================================
;; G1 — strip-prop XSS rule through `render-to-string` / `emit-element`
;; ===========================================================================

(deftest render-to-string-strips-event-handler-props
  (testing "rf2-usio0 / rf2-dwds9 — an `on*` event-handler prop fed
            through the FULL `render-to-string` emit composition (not
            `attr-string` in isolation) is dropped at emit time. Pins
            that the strip survives `emit-element`'s attr path."
    (testing ":on-click (kebab) is stripped through render-to-string"
      (is (= "<div id=\"x\"></div>"
             (emit/render-to-string [:div {:on-click "alert(1)" :id "x"}] {}))))

    (testing ":onClick (camelCase) is stripped through render-to-string"
      (is (= "<div id=\"x\"></div>"
             (emit/render-to-string [:div {:onClick "alert(1)" :id "x"}] {}))))

    (testing "the stripped handler value never appears in the output
              string — belt-and-braces against a partial-emit leak"
      (let [html (emit/render-to-string
                   [:div {:onMouseDown "steal()" :id "x"}] {})]
        (is (not (str/includes? html "steal"))
            "the handler body must not survive anywhere in the markup")
        (is (not (str/includes? html "onMouseDown"))
            "the handler attribute name must not survive either")))

    (testing "a div whose ONLY attr is a stripped handler emits a clean
              open tag — no stray space, no bare attr"
      (is (= "<div></div>"
             (emit/render-to-string [:div {:on-click "f"}] {}))))))

(deftest render-to-string-strips-function-valued-props
  (testing "rf2-usio0 / rf2-dwds9 — a function-valued prop has no HTML
            serialisation and is dropped through the full emit
            composition."
    (is (= "<div id=\"x\"></div>"
           (emit/render-to-string [:div {:title (fn [_] :handler) :id "x"}] {})))

    (testing "fn value is stripped even on an innocuous key name"
      (is (= "<span></span>"
             (emit/render-to-string [:span {:data-cb (fn [] nil)}] {}))))))

(deftest render-to-string-drops-prototype-pollution-keys
  (testing "rf2-usio0 / rf2-dwds9 — reserved prototype-pollution keys
            (`__proto__` / `constructor` / `prototype`) are dropped
            through `render-to-string` before they reach the host
            createElement-equivalent on hydration."
    (doseq [k ["__proto__" "constructor" "prototype"]]
      (testing (str "`" k "` is dropped through render-to-string")
        (is (= "<div id=\"x\"></div>"
               (emit/render-to-string
                 [:div {(keyword k) "polluted" :id "x"}] {}))
            (str k " must not survive to wire output via the emit path"))))

    (testing "the match is case-insensitive at the composed callsite too"
      (is (= "<div id=\"x\"></div>"
             (emit/render-to-string
               [:div {(keyword "Constructor") "polluted" :id "x"}] {}))))))

(deftest render-to-string-strips-props-on-void-element
  (testing "rf2-usio0 — the strip composes with the VOID-element branch
            of `emit-element` (emit.cljc:322-323), not just the
            open/close branch. An `on*` handler on an <input> is dropped
            and the void tag self-closes cleanly."
    (is (= "<input id=\"x\">"
           (emit/render-to-string
             [:input {:on-change "x()" :id "x"}] {})))
    (let [html (emit/render-to-string
                 [:img {:onError "alert(1)" :src "/a.png"}] {})]
      (is (str/includes? html "src=\"/a.png\"") "the legit attr survives")
      (is (not (str/includes? html "onError")) "the handler is stripped")
      (is (not (str/includes? html "alert")) "the handler body is gone"))))

(deftest render-to-string-strips-props-through-registered-view-root
  (testing "rf2-usio0 — the strip composes with the registered-view
            resolution branch (emit.cljc:296-311). A view whose ROOT
            DOM element carries a hostile handler must still emit
            stripped — the source-coord injection + view-ref indirection
            must not bypass `attr-string`."
    (rf/reg-view ^{:rf/id :test/hostile-root} hostile-root-view []
      [:div {:on-click "alert(document.cookie)" :id "v"}
       [:p "safe body"]])
    (let [html (emit/render-to-string [:test/hostile-root] {})]
      (is (str/includes? html "<p>safe body</p>")
          "the view body still renders")
      (is (str/includes? html "id=\"v\"")
          "the legit root attr survives")
      (is (not (str/includes? html "on-click"))
          "the root handler is stripped through the view-ref branch")
      (is (not (str/includes? html "alert(document.cookie)"))
          "the handler body never reaches the wire"))))

(deftest render-to-string-strips-props-when-root-attrs-injected
  (testing "rf2-usio0 — the strip survives the rf2-lxwse root-attrs
            (`:emit-hash?`) injection path. The injected
            `data-rf-render-hash` lands while the user's hostile handler
            on the SAME root element is dropped — `merge-root-attrs`
            feeds into the same `attr-string` strip."
    (let [html (emit/render-to-string
                 [:div {:onClick "alert(1)" :id "root"} [:p "x"]]
                 {:emit-hash? true})]
      (is (str/includes? html "data-rf-render-hash=")
          "the render-hash root attr was injected")
      (is (str/includes? html "id=\"root\"")
          "the legit user attr survives alongside the injected hash")
      (is (not (str/includes? html "onClick"))
          "the user's handler on the hash-bearing root is still stripped")
      (is (not (str/includes? html "alert"))
          "no handler body leaks through the injection composition"))))

(deftest render-to-string-strips-deep-nested-handler
  (testing "rf2-usio0 — the strip runs at EVERY emit-element descent, not
            only the root. A handler buried several levels deep is
            dropped — `emit-children` re-enters `emit-element` per child."
    (let [html (emit/render-to-string
                 [:div
                  [:section
                   [:ul
                    [:li {:onClick "deep()" :class "item"} "deep"]]]]
                 {})]
      (is (str/includes? html "class=\"item\"") "the deep legit attr survives")
      (is (str/includes? html ">deep</li>") "the deep text survives")
      (is (not (str/includes? html "onClick")) "the deep handler is stripped")
      (is (not (str/includes? html "deep()")) "the deep handler body is gone"))))

;; ===========================================================================
;; G1 — strip-prop XSS rule through `streaming/render-shell`'s walk
;; ===========================================================================

(deftest render-shell-strips-event-handler-props
  (testing "rf2-usio0 / rf2-dwds9 — the streaming shell walker
            (`walk-dom-tag`, streaming.cljc:225-227) re-derives attrs via
            `emit/attr-string`, so an `on*` handler on a shell DOM
            element must be stripped through the shell walk too — not
            just through the non-streaming `render-to-string`."
    (let [tree [:div {:on-click "alert(1)" :id "x"}
                [:p "shell body"]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "<p>shell body</p>")
          "the shell body renders")
      (is (str/includes? shell-html "id=\"x\"")
          "the legit attr survives the walk")
      (is (not (str/includes? shell-html "on-click"))
          "the handler is stripped through walk-dom-tag")
      (is (not (str/includes? shell-html "alert(1)"))
          "the handler body never reaches the shell HTML"))))

(deftest render-shell-strips-function-and-proto-props
  (testing "rf2-usio0 / rf2-dwds9 — fn-valued + prototype-pollution
            props are also stripped through the streaming walk."
    (let [tree [:div {:title (fn [] nil)
                      :__proto__ "polluted"
                      :id "x"}
                [:span "ok"]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "id=\"x\"") "the legit attr survives")
      (is (str/includes? shell-html "<span>ok</span>") "body renders")
      (is (not (str/includes? shell-html "__proto__"))
          "the prototype-pollution key is stripped through the walk")
      (is (not (str/includes? shell-html "polluted"))
          "the prototype-pollution value never reaches the shell"))))

(deftest render-shell-strips-handler-on-void-element
  (testing "rf2-usio0 — the streaming walk's void-element branch
            (streaming.cljc:224) also runs the strip. An `on*` handler on
            an <input> in the shell is dropped."
    (let [tree [:form
                [:input {:onChange "steal()" :name "q"}]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "name=\"q\"") "the legit attr survives")
      (is (not (str/includes? shell-html "onChange")) "the handler is stripped")
      (is (not (str/includes? shell-html "steal")) "the handler body is gone"))))

(deftest render-shell-strips-handler-buried-near-suspense-boundary
  (testing "rf2-usio0 — a hostile handler on a shell element that SITS
            ALONGSIDE a :rf/suspense-boundary is stripped through the
            walk, while the boundary still registers its continuation.
            Pins that the strip composes with the suspense-walk path,
            not only plain DOM descent."
    (let [tree [:div {:onClick "alert(1)" :id "outer"}
                [:rf/suspense-boundary
                 {:id :sb :fallback [:p "loading"]}
                 [:p "body"]]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "the boundary still registered its continuation")
      (is (str/includes? shell-html "id=\"outer\"")
          "the legit attr on the boundary-bearing element survives")
      (is (not (str/includes? shell-html "onClick"))
          "the handler on the boundary-bearing element is stripped")
      (is (not (str/includes? shell-html "alert(1)"))
          "the handler body never reaches the shell"))))
