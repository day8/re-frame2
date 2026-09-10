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
    - function-valued prop values,
    - reserved prototype-pollution keys (`__proto__` / `constructor` /
      `prototype`),
    - JSX source-coord props (`:_jsxFileName` / `:_jsxLineNumber` /
      `:_jsxColumnNumber`, rf2-fa4ly) — React DevTools internals that
      have no HTML wire representation. Matched CASE-SENSITIVELY against
      exactly those three documented names rather than by a broad
      underscore prefix, so an app's own underscore-led prop still
      surfaces the grammar error (rf2-ounh), and
    - React's two STRUCTURAL SLOTS, `:key` and `:ref` (rf2-gw87) —
      reconciliation identity and an instance handle, consumed by React
      before the host sees them and serialised by react-dom/server as
      neither. Unlike the handler and prototype rosters this one matches
      CASE-SENSITIVELY, because React extracts the two slots by exact JS
      property name, and
    - React's two CONTENT CHANNELS, `:children` and
      `:dangerouslySetInnerHTML` (rf2-dgyi) — the slots React takes an
      element's CONTENT from, likewise consumed before the host sees them
      and likewise serialised as neither, likewise case-sensitive. A
      SEPARATE roster from the structural slots: those are identity,
      these are content. Dropping them discards nothing that was being
      rendered, because this emitter rendered no content for either — the
      raw-HTML one reached the wire as the escaped EDN print of its
      `{:__html …}` map,

  matching react-dom/server behaviour. The filter is the per-attribute
  prop-name position in the locked emitter composition order, so it runs
  ahead of the attribute-name grammar gate (rf2-vl8ir) — a stripped prop
  never reaches `validate-attr-name!`.

  These exercise `re-frame.ssr.html-helpers/attr-string` directly (the
  single shared per-attribute emission point used by the main emitter,
  the head emitter, and the streaming emitter)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.ssr.head.emit :as rf.ssr.head.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
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

(deftest attr-string-drops-jsx-source-coord-props
  (testing "rf2-ounh — React DevTools' \"View source\" gesture reads
            `_jsxFileName` / `_jsxLineNumber` / `_jsxColumnNumber` off the
            rendered element. The framework does not emit them, but user
            code, JSX-compiled third-party libraries and hand-stamped
            DevTools shims can, and they have no HTML wire representation.
            The class landed with the reference stripper on 2026-05-27
            (rf2-fa4ly) as a defensive rider on a DevTools commit, and
            went untested until this deftest."
    (testing "each of the three documented names is dropped"
      (doseq [k ["_jsxFileName" "_jsxLineNumber" "_jsxColumnNumber"]]
        (is (= " id=\"x\""
               (rf.ssr.html-helpers/attr-string {(keyword k) "v" :id "x"}))
            (str k " must not survive to wire output"))))

    (testing "THE ORDERING PROPERTY, and it is the assertion with the real
              value here. The filter sits UPSTREAM of the attribute-name
              grammar gate, so a JSX source-coord prop is silently dropped
              instead of surfacing as `:rf.error/ssr-invalid-attribute-name`
              — the leading underscore fails the conservative HTML5 grammar
              `[A-Za-z][A-Za-z0-9_:-]*`. If the filter ever moved BELOW
              `validate-attr-name!` the behaviour would regress from
              silently-clean to throwing, and nothing else in the lane
              would notice."
      (is (= "<div></div>"
             (rf.ssr.emit/render-to-string
               [:div {:_jsxFileName     "app.cljs"
                      :_jsxLineNumber   12
                      :_jsxColumnNumber 3}]
               {}))
          "a fully JSX-stamped element emits clean markup and does NOT throw")
      (is (= "" (rf.ssr.html-helpers/attr-string {:_jsxFileName "app.cljs"}))
          "a props map that is ONLY a source-coord prop yields the empty
           string, not a stray leading space"))

    (testing "surviving attributes on the same element are untouched"
      (is (= " id=\"x\" class=\"c\""
             (rf.ssr.html-helpers/attr-string
               (array-map :_jsxFileName "app.cljs" :id "x" :class "c"))))
      (is (= "<div id=\"a\">x</div>"
             (rf.ssr.emit/render-to-string
               [:div (array-map :_jsxLineNumber 12 :id "a") "x"] {}))))

    (testing "the match is CASE-SENSITIVE against exactly those three
              names, and that is the half pinning the design decision. The
              matcher deliberately names the three documented spellings
              (per `@babel/plugin-transform-react-jsx-source`) rather than
              a broad underscore prefix, so an app's own underscore-led
              prop is NOT swallowed here — it still reaches the grammar
              gate and still surfaces the error, which is what tells the
              author their attribute name is unservable."
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/ssr-invalid-attribute-name"
            (rf.ssr.html-helpers/attr-string {:_JsxFileName "app.cljs"}))
          "a case VARIANT of a rostered name is not in the set, so it falls
           through to the grammar gate")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/ssr-invalid-attribute-name"
            (rf.ssr.html-helpers/attr-string {:_appPrivate "v"}))
          "an app's own underscore-prefixed prop is not swallowed by a
           prefix match"))

    (testing "the filter does not over-reach onto grammar-legal names that
              merely contain the word"
      (doseq [[k expected] {:data-jsx-file "data-jsx-file=\"v\""
                            :jsxFileName   "jsxFileName=\"v\""}]
        (is (str/includes? (rf.ssr.html-helpers/attr-string {k "v"}) expected)
            (str k " is an ordinary attribute and must round-trip"))))))

(deftest attr-string-drops-reacts-structural-slots
  (testing "rf2-gw87 — `:key` and `:ref` are React's two STRUCTURAL SLOTS.
            React reads them off the props map itself and hands the host
            neither, so react-dom/server serialises neither. This emitter
            passed both straight through. Measured before the fix:
            `[:div {:key \"k\"}]` served `<div key=\"k\">` and a string
            `[:div {:ref \"R\"}]` served `<div ref=\"R\">`."
    (testing ":key is dropped"
      (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {:key "k" :id "x"})))
      (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {:key 1 :id "x"}))
          "a numeric :key — the `for`-loop spelling — drops too")
      (is (= "" (rf.ssr.html-helpers/attr-string {:key "k"}))
          "a props map that is ONLY a :key yields the empty string, not a
           stray leading space"))

    (testing ":ref is dropped whatever its value"
      (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {:ref "R" :id "x"}))
          "a STRING ref — illegal in React, and the arm that was reachable
           past the fn? filter")
      (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {:ref (fn [_]) :id "x"}))
          "a function-valued ref was already dropped by the fn? arm and
           still is"))

    (testing "the match is CASE-SENSITIVE, unlike the handler and
              prototype-pollution rosters. React extracts these two slots
              by exact JS property name, so `:Key` reaches the host as an
              ordinary unknown prop and the client paints it — stripping it
              here would be the same server/client divergence pointed the
              other way."
      (is (str/includes? (rf.ssr.html-helpers/attr-string {:Key "k"}) "Key=\"k\""))
      (is (str/includes? (rf.ssr.html-helpers/attr-string {:REF "r"}) "REF=\"r\"")))

    (testing "the filter does not over-reach onto names that merely start
              with or contain the two words"
      (doseq [[k expected] {:keygen        "keygen=\"v\""
                            :data-key      "data-key=\"v\""
                            :aria-keyshortcuts "aria-keyshortcuts=\"v\""
                            :referrerpolicy "referrerpolicy=\"v\""
                            :data-ref      "data-ref=\"v\""}]
        (is (str/includes? (rf.ssr.html-helpers/attr-string {k "v"}) expected)
            (str k " is an ordinary attribute and must round-trip"))))))

(deftest structural-slots-drop-on-every-emitter-that-shares-the-roster
  (testing "rf2-gw87 — the whole argument for fixing `strip-prop?` rather
            than `dissoc`-ing in one emitter is that `attr-string` is the
            single per-attribute emission point every SSR surface goes
            through. A change that only demonstrated the body emitter would
            not have shown the thing that justified its own location, so
            each surface is exercised here."
    (testing "the hiccup BODY emitter (emit/render-to-string)"
      (is (= "<div></div>" (rf.ssr.emit/render-to-string [:div {:key "k"}] {})))
      (is (= "<div></div>" (rf.ssr.emit/render-to-string [:div {:ref "R"}] {})))
      (is (= "<div id=\"a\">x</div>"
             (rf.ssr.emit/render-to-string [:div {:key 1 :id "a"} "x"] {}))
          "the surviving attributes are untouched")
      (is (= "<li>1</li><li>2</li>"
             (rf.ssr.emit/render-to-string
               (into [:<>] (for [i [1 2]] [:li {:key i} i])) {}))
          "the canonical keyed-list idiom — the reachable case — is clean"))

    (testing "the STREAMING shell walker (streaming/render-shell), which
              reaches this roster through the `emit/attr-string` re-export"
      (is (= "<div></div>"
             (:shell-html (rf.ssr.streaming/render-shell [:div {:key "k"}]))))
      (is (= "<div></div>"
             (:shell-html (rf.ssr.streaming/render-shell [:div {:ref "R"}]))))
      (is (= "<main><div>x</div></main>"
             (:shell-html
               (rf.ssr.streaming/render-shell [:main [:div {:key "k"} "x"]])))
          "nested under a DOM tag, where walk-dom-tag does the merging"))

    (testing "streaming and non-streaming agree, which is the property the
              shared roster exists to guarantee"
      (doseq [tree [[:div {:key "k"}]
                    [:div {:ref "R"}]
                    [:div {:key 1 :id "a"} "x"]
                    [:main [:div {:key "k"} "x"]]
                    [:ul (for [i [1 2]] [:li {:key i} i])]]]
        (is (= (rf.ssr.emit/render-to-string tree {})
               (:shell-html (rf.ssr.streaming/render-shell tree)))
            (str "emitters disagree on " (pr-str tree)))))

    (testing "the HEAD emitter, and this one is a DELIBERATE consequence
              rather than a side effect. `strip-prop?` is shared, so
              widening it changes head output too — measured before the
              change, `{:meta [{:key \"m1\" :name \"a\"}]}` emitted
              `<meta key=\"m1\" name=\"a\">`. That is the SAME defect in the
              same direction: react-dom/server would emit no `key` on a
              `<meta>` either, and a head model built from a keyed component
              list is exactly where a stray `:key` comes from. Pinned so the
              shared-roster consequence is a recorded decision rather than
              something a future reader discovers."
      (is (= "<meta name=\"a\" content=\"b\">"
             (rf.ssr.head.emit/head-model->html
               {:meta [{:key "m1" :name "a" :content "b"}]})))
      (is (= "<link rel=\"stylesheet\" href=\"/a.css\">"
             (rf.ssr.head.emit/head-model->html
               {:link [{:key "l1" :rel "stylesheet" :href "/a.css"}]})))
      (is (= "<script src=\"/a.js\"></script>"
             (rf.ssr.head.emit/head-model->html
               {:script [{:key "s1" :src "/a.js"}]})))
      (is (= "<head><title>T</title><meta charset=\"utf-8\"></head>"
             (rf.ssr.head.emit/head-model->html
               {:title "T" :meta [{:key 1 :charset "utf-8"}]} {:wrap? true}))))

    (testing "the `:html-attrs` / `:body-attrs` bags the Ring host shell
              stamps onto `<html>` / `<body>` read the same roster — pinned
              at `attr-string`, which is the fn `ring.shell` calls"
      (is (= " lang=\"en\"" (rf.ssr.html-helpers/attr-string {:key "k" :lang "en"})))
      (is (= " class=\"c\"" (rf.ssr.html-helpers/attr-string {:ref "R" :class "c"}))))))

(deftest attr-string-drops-reacts-content-channels
  (testing "rf2-dgyi — `:children` and `:dangerouslySetInnerHTML` are the two
            slots React takes an element's CONTENT from. Both were reaching
            the wire as DOM attributes. Measured before the fix:
            `{:children \"v\"}` emitted ` children=\"v\"`, and the raw-HTML
            channel emitted the escaped EDN PRINT of its map —
            ` dangerouslySetInnerHTML=\"{:__html &quot;<b>x</b>&quot;}\"` —
            which is the measurement that settles the design question. This
            emitter rendered NO content for either prop, so dropping them
            discards nothing that was being rendered; it removes the
            attribute half of a divergence and leaves the content half
            exactly where it already was."
    (testing ":children is dropped"
      (is (= " id=\"x\"" (rf.ssr.html-helpers/attr-string {:children "v" :id "x"})))
      (is (= "" (rf.ssr.html-helpers/attr-string {:children "v"}))
          "a props map that is ONLY :children yields the empty string, not a
           stray leading space")
      (is (= "" (rf.ssr.html-helpers/attr-string {:children ["a" "b"]}))
          "a VECTOR of children — the shape a hiccup caller's children slot
           actually holds — dropped too, rather than printed as EDN"))

    (testing ":dangerouslySetInnerHTML is dropped, in the `{:__html …}` shape
              React defines and in any other"
      (is (= " id=\"x\""
             (rf.ssr.html-helpers/attr-string
               {:dangerouslySetInnerHTML {:__html "<b>x</b>"} :id "x"})))
      (is (= "" (rf.ssr.html-helpers/attr-string
                  {:dangerouslySetInnerHTML {:__html "<b>x</b>"}})))
      (is (not (str/includes?
                 (rf.ssr.html-helpers/attr-string
                   {:dangerouslySetInnerHTML {:__html "<b>x</b>"} :id "x"})
                 ":__html"))
          "the raw EDN map text must never reach the wire — the defect was
           not merely a stray attribute NAME but a printed map as its VALUE"))

    (testing "REAL children still render — dropping the prop does not touch
              the children slot, which is also React's own precedence rule
              (variadic children beat `props.children`)"
      (is (= "<div>real</div>"
             (rf.ssr.emit/render-to-string [:div {:children "v"} "real"] {}))))

    (testing "the match is CASE-SENSITIVE, like the structural slots and for
              the same reason: React extracts both channels by exact JS
              property name, so these reach the host as ordinary unknown
              props that the CLIENT paints — stripping them here would be the
              same server/client divergence pointed the other way"
      (is (str/includes? (rf.ssr.html-helpers/attr-string {:Children "v"})
                         "Children=\"v\""))
      (is (str/includes? (rf.ssr.html-helpers/attr-string
                           {:dangerouslysetinnerhtml "v"})
                         "dangerouslysetinnerhtml=\"v\"")
          "the all-lowercase spelling is NOT React's reserved prop"))

    (testing "the filter does not over-reach onto names that merely contain
              or start with the two words"
      (doseq [[k expected] {:data-children  "data-children=\"v\""
                            :aria-children  "aria-children=\"v\""
                            :childrenish    "childrenish=\"v\""}]
        (is (str/includes? (rf.ssr.html-helpers/attr-string {k "v"}) expected)
            (str k " is an ordinary attribute and must round-trip"))))))

(deftest content-channels-drop-on-every-emitter-that-shares-the-roster
  (testing "rf2-dgyi — the same four-surface obligation rf2-gw87 carried: the
            argument for editing shared `strip-prop?` rather than
            `dissoc`-ing in one emitter is that `attr-string` is the single
            per-attribute emission point every SSR surface goes through, so a
            change demonstrating only one has not shown the thing that
            justified its own location."
    (testing "the hiccup BODY emitter (emit/render-to-string)"
      (is (= "<div></div>" (rf.ssr.emit/render-to-string [:div {:children "v"}] {})))
      (is (= "<div></div>"
             (rf.ssr.emit/render-to-string
               [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}] {})))
      (is (= "<div id=\"a\">x</div>"
             (rf.ssr.emit/render-to-string [:div {:children "c" :id "a"} "x"] {}))
          "the surviving attributes and the real children are untouched"))

    (testing "the STREAMING shell walker (streaming/render-shell), which
              reaches this roster through the `emit/attr-string` re-export"
      (is (= "<div></div>"
             (:shell-html (rf.ssr.streaming/render-shell [:div {:children "v"}]))))
      (is (= "<div></div>"
             (:shell-html (rf.ssr.streaming/render-shell
                            [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}])))))

    (testing "body and streaming emitters agree byte-for-byte, which is the
              property the shared roster exists to guarantee"
      (doseq [tree [[:div {:children "v"}]
                    [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}]
                    [:div {:children "v" :id "a"} "x"]
                    [:main [:div {:children "v"} "x"]]]]
        (is (= (rf.ssr.emit/render-to-string tree {})
               (:shell-html (rf.ssr.streaming/render-shell tree)))
            (str "emitters disagree on " (pr-str tree)))))

    (testing "the HEAD emitter — a DELIBERATE consequence, pinned rather than
              discovered later. `strip-prop?` is shared, so widening it
              changes head output too. Measured BEFORE this change:
              `{:meta [{:children \"v\" :name \"a\"}]}` emitted
              `<meta children=\"v\" name=\"a\">`, and `<link>` / `<script>`
              the same. That is the SAME defect in the same direction —
              react-dom/server emits no `children` attribute on a `<meta>`
              either — and on a VOID element `<meta>`/`<link>` the prop could
              not be honoured as children even in principle, which is part of
              why dropping rather than honouring is right here."
      (is (= "<meta name=\"a\">"
             (rf.ssr.head.emit/head-model->html
               {:meta [{:children "v" :name "a"}]})))
      (is (= "<meta name=\"a\">"
             (rf.ssr.head.emit/head-model->html
               {:meta [{:dangerouslySetInnerHTML {:__html "<b>x</b>"} :name "a"}]})))
      (is (= "<link rel=\"stylesheet\" href=\"/a.css\">"
             (rf.ssr.head.emit/head-model->html
               {:link [{:children "v" :rel "stylesheet" :href "/a.css"}]})))
      (is (= "<script src=\"/a.js\"></script>"
             (rf.ssr.head.emit/head-model->html
               {:script [{:children "v" :src "/a.js"}]}))))

    (testing "the `:html-attrs` / `:body-attrs` bags the Ring host shell
              stamps onto `<html>` / `<body>` read the same roster — pinned
              at `attr-string`, which is the fn `ring.shell` calls. (The
              shell itself lives in the ssr-ring artefact and is exercised by
              that suite; this row pins the shared function it calls.)"
      (is (= " lang=\"en\""
             (rf.ssr.html-helpers/attr-string {:children "v" :lang "en"})))
      (is (= " class=\"c\""
             (rf.ssr.html-helpers/attr-string
               {:dangerouslySetInnerHTML {:__html "<b>x</b>"} :class "c"}))))))

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
