(ns re-frame.ssr-keyword-head-contract-test
  "The ONE render-tree head grammar, pinned on the JVM emitters.

  A keyword head in a render tree is a DOM / custom element on EVERY
  host. It is never a view reference. Views are referenced by callable
  binding — the Var `rf/reg-view` defs for you, or the `(rf/view :id)`
  runtime handle.

  ## Why this test exists

  A JVM emitter that probed `(registrar/lookup :view head)` on its
  keyword branch would resolve `[:dashboard/card :revenue]` to the
  registered view server-side. No client substrate does that (Conventions
  §Render-tree shape vs runtime lookup; Cross-Spec-Interactions §237):
  Reagent's `parse-tag` runs `(name tag)`, so the same head paints
  `<card>revenue</card>` in a browser. One hiccup form, two meanings —
  the server would render a real subtree while the client painted a
  phantom, NEITHER side would say anything, and every server-side test
  would stay green.

  There is no registry probe. These tests are the corpus-wide statement
  of the rule on the two JVM emitters — the standard emitter and the
  streaming shell walker.

  ## The child spelling

  Aligning the HEAD alone would leave the two hosts emitting different
  TEXT for the identical tree:

      [:dashboard/card :revenue]  str-spelled -> <card>:revenue</card>
                                      Reagent -> <card>revenue</card>

  React hydration reconciles TEXT nodes as well as element structure, so
  that difference is the same bug one layer down.

  A keyword or symbol child is spelled by its `name` on every host:
  no leading colon, and the NAMESPACE IS DROPPED (`:a/b` paints `b`).
  That second half is the part a colon-stripping rule gets wrong; Reagent
  routes a named child through `(name x)`, it does not trim the printed
  form. Namespaced symbols follow the same arm.

  The client half of the same contract is pinned substrate-side in
  `implementation/adapters/reagent/test/re_frame/ssr_keyword_head_contract_cljs_test.cljs`,
  which asserts the SAME head paints the SAME element AND the same bytes.
  Together the two files are the cross-host proof.

  ## Posture split

  The head grammar itself is production-real and is asserted throughout
  without a posture guard. The single exception is inside
  `views-are-referenced-by-callable-head`, whose `(rf/view :id)` arm pins the
  registration-boundary annotations byte for byte. Those attributes exist
  only under `interop/debug-enabled?` (read once at namespace-load time), so
  under `-Dre-frame.debug=false` the registered handle renders the same view
  subtree without them. The annotated literal lives in a
  `(when interop/debug-enabled? …)` arm; the arm's actual claim — that BOTH
  spellings resolve the same view — is asserted in both postures: by a
  `str/includes?` triple on the shared subtree, and under the real gate by
  comparing the two renders directly, which is the stronger statement."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; The view body, held as a plain fn so the SAME implementation backs all
;; three spellings under test (keyword head, Var head, `(rf/view :id)`).
(defn card-view [card-id]
  [:div.card [:h3 (str card-id)]])

;; `tf/reset-runtime` runs `registrar/clear-all!` before EVERY test, so a
;; top-level `reg-view` would be wiped by the time a test body runs — and
;; every keyword-head assertion below would then pass vacuously, against
;; an UNREGISTERED id, proving nothing about the head grammar.
;; Re-register after the reset, inside the fixture chain, so
;; `:dashboard/card` is genuinely live for each test.
(defn- with-registered-card [test-fn]
  (rf/reg-view* :dashboard/card {} card-view)
  (test-fn))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime with-registered-card)

;; ===========================================================================
;; The standard emitter — `render-to-string`
;; ===========================================================================

(deftest keyword-head-is-an-element-not-a-view
  (testing "a namespaced keyword head that NAMES a registered
            view still emits a custom element. The keyword's `name` is the
            tag (matching `parse-tag`'s `(name tag)` on every client
            substrate); its namespace is dropped; the trailing argument is
            a text child.

            The text child is spelled by the keyword's `name`
            too: no leading colon, namespace dropped. These are the exact
            bytes Reagent + react-dom/server paints for the same tree
            (pinned as a cross-host equality in the CLJS twin)."
    (is (= "<card>revenue</card>"
           (rf.ssr.emit/render-to-string [:dashboard/card :revenue] nil))))

  (testing "the registration is genuinely present — this test would be
            vacuous if `:dashboard/card` were simply unregistered, which
            is exactly the shape that would let a registry probe hide"
    (is (some? (rf/view :dashboard/card))
        "card-view must be registered for the assertion above to mean
         anything: the point is that a REGISTERED id still emits an
         element."))

  (testing "an unregistered keyword head emits the identical element —
            registration state does not change the head's meaning, which
            is the whole content of the one-grammar rule"
    (is (= "<card>revenue</card>"
           (rf.ssr.emit/render-to-string [:never-registered/card :revenue] nil)))))

(deftest scalar-children-are-spelled-by-name
  (testing "a keyword or symbol CHILD is spelled by its `name`:
            no leading colon, namespace dropped. Aligning the head meaning
            alone would leave the same raw tree producing different TEXT on
            the two hosts — and React hydration reconciles text nodes, not
            just elements.

            Every expectation here is the output of Reagent +
            react-dom/server for the same tree; the CLJS twin asserts
            the same strings from the other side."
    (are [expected tree] (= expected (rf.ssr.emit/render-to-string tree nil))
      "<div>revenue</div>" [:div :revenue]
      "<div>b</div>"       [:div :a/b]
      "<div>leaf</div>"    [:div :ns.deep/leaf]
      "<div>sym</div>"     [:div 'sym]
      "<div>b</div>"       [:div 'a/b]
      "<div>revenue growth</div>" [:div :revenue " " :growth]
      "<div>1a</div>"      [:div 1 :a]))

  (testing "the namespace is DROPPED, not rendered — the case a
            colon-stripping rule gets wrong. `:a/b` paints `b`,
            never `a/b`, because Reagent routes a named child through
            `(name x)` rather than trimming the printed form."
    (is (= "<div>b</div>" (rf.ssr.emit/render-to-string [:div :a/b] nil)))
    (is (not= "<div>a/b</div>" (rf.ssr.emit/render-to-string [:div :a/b] nil))))

  (testing "escaping still applies to the NAME — spelling a child by
            `name` must not become an escape bypass"
    (is (= "<div>x&lt;y</div>" (rf.ssr.emit/render-to-string [:div :x<y] nil))))

  (testing "string, number, nil and boolean children keep their ordinary
            spelling"
    (are [expected tree] (= expected (rf.ssr.emit/render-to-string tree nil))
      "<div>plain</div>" [:div "plain"]
      "<div>9</div>"     [:div 9]
      "<div></div>"      [:div nil]
      "<div></div>"      [:div true]))

  (testing "the streaming walker agrees — its scalar arm delegates to the
            standard emitter, so this is a delegation pin, not a second
            implementation"
    (doseq [tree [[:div :revenue] [:div :a/b] [:div 'a/b] [:dashboard/card :revenue]]]
      (is (= (rf.ssr.emit/render-to-string tree nil)
             (:shell-html (rf.ssr.streaming/render-shell tree)))
          (str "emitter/walker divergence on " (pr-str tree))))))

(deftest keyword-head-carries-ordinary-element-syntax
  (testing "the element branch is the ORDINARY element branch:
            a keyword head that happens to name a view still gets
            `.class`/`#id` sugar, an attrs map, and child recursion. It is
            not a special case, it is the same case."
    (is (= "<card class=\"revenue\" data-x=\"1\"><b>hi</b></card>"
           (rf.ssr.emit/render-to-string [:dashboard/card.revenue {:data-x "1"} [:b "hi"]] nil)))))

(deftest views-are-referenced-by-callable-head
  (testing "the two supported spellings both RESOLVE the view
            (the head-grammar point: a callable head is a view, a keyword
            head is an element), so code moving off keyword refs
            has somewhere to land. On top of that, the REGISTERED
            handle `(rf/view :id)` carries the dev-mode registration-
            boundary annotations, while a bare fn Var that never went
            through registration does not — a distinction the raw
            `card-view` fn (a test-only stand-in) makes visible."
    (testing "a bare fn Var resolves the view — UNANNOTATED (it is the raw
              render fn, not the registered handle)"
      (is (= "<div class=\"card\"><h3>:revenue</h3></div>"
             (rf.ssr.emit/render-to-string [card-view :revenue] nil))))

    ;; Dev-instrumentation arm (see ns docstring). The registered
    ;; handle carries the annotations only under `interop/debug-enabled?`.
    (when rf.interop/debug-enabled?
      (testing "`(rf/view :id)` — the REGISTERED handle — resolves the view AND
                carries both dev annotations. In idiomatic usage
                the symbol `reg-view` defs IS `(rf/view id)`, so THIS is what a
                real Var reference emits."
        (is (= (str "<div class=\"card\""
                    " data-rf2-source-coord=\"dashboard:card:?:?\""
                    " data-rf-view=\":dashboard/card\">"
                    "<h3>:revenue</h3></div>")
               (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))))

    (testing "both spellings resolve to the SAME view subtree — the class and
              child agree; the registered handle merely adds the debug-gated
              annotation attributes on the root"
      (is (str/includes? (rf.ssr.emit/render-to-string [card-view :revenue] nil)
                         "class=\"card\""))
      (is (str/includes? (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)
                         "class=\"card\""))
      (is (str/includes? (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)
                         "<h3>:revenue</h3>")))

    ;; The REAL-gate arm. With no annotation wrapper installed the
    ;; two spellings are not merely "the same subtree modulo attributes", they
    ;; are byte-identical — which is the head-grammar claim in its purest
    ;; form, and is only observable in this posture.
    (when-not rf.interop/debug-enabled?
      (testing "under -Dre-frame.debug=false the registered handle and the bare
                Var render byte-identically — nothing but the debug-gated
                attributes ever separated them"
        (is (= (rf.ssr.emit/render-to-string [card-view :revenue] nil)
               (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))
        (is (= "<div class=\"card\"><h3>:revenue</h3></div>"
               (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))))))

;; ===========================================================================
;; The streaming shell walker — `render-shell`
;; ===========================================================================

(deftest streaming-walker-keyword-head-is-an-element
  (testing "the shell walker obeys the same one grammar. It has its
            OWN keyword branch, so a `(registrar/lookup :view head)` probe
            there would leave the streaming path diverging from every
            client substrate even with the standard emitter aligned."
    (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell [:dashboard/card :revenue])]
      (is (= "<card>revenue</card>" shell-html))))

  (testing "and still recurses through the element into nested children,
            so a suspense boundary underneath a keyword head is reachable"
    (let [{:keys [shell-html continuations]}
          (rf.ssr.streaming/render-shell
            [:dashboard/card
             [:rf/suspense-boundary {:id :b1 :fallback [:span "loading"]}
              [card-view :revenue]]])]
      (is (= 1 (count continuations))
          "the boundary under an element head must still be recorded")
      (is (str/starts-with? shell-html "<card>")
          "the element head is emitted, not resolved away")))

  (testing "callable heads resolve in the walker exactly as in the
            standard emitter"
    (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell [card-view :revenue])]
      (is (= "<div class=\"card\"><h3>:revenue</h3></div>" shell-html)))))

;; ===========================================================================
;; Unrecognised reserved `:rf/*` heads fail loud
;; ===========================================================================

(defn- head-error
  "Render `tree` and return the thrown ex-data, or nil if it did not throw."
  [render-fn tree]
  (try (render-fn tree) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest unrecognised-reserved-rf-head-fails-loud
  (testing "with every keyword head an element, a misspelt
            reserved head would otherwise paint a phantom element and say
            nothing: `:rf/suspense-boundry` has a `name` that passes the
            DOM tag grammar. That is the very failure the one grammar
            removes, displaced by one keystroke, so the reserved scheme
            fails loud."
    (let [data (head-error #(rf.ssr.emit/render-to-string % nil)
                           [:rf/suspense-boundry {:id :x} [:p "hi"]])]
      (is (some? data) "an unrecognised :rf/* head must throw")
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data))
          "reuses the existing malformed-head id rather than minting a
           near-duplicate — the head genuinely has no HTML interpretation")
      (is (= :use-a-recognised-reserved-head-or-an-unreserved-keyword
             (:recovery data))
          "the reserved-head ARM is distinguished from the malformed-head
           arm by its :recovery, which is what the Spec 009 row promises")
      (is (= :rf/suspense-boundry (:head data)))
      (is (some? (:element data)))))

  (testing "the dotted `:rf.<area>/*` sub-namespaces are reserved too"
    (is (some? (head-error #(rf.ssr.emit/render-to-string % nil) [:rf.ssr/nope]))))

  (testing "the streaming walker rejects it identically — it has its
            own keyword branch, so a one-sided guard would re-fork the
            hosts"
    (let [data (head-error #(:shell-html (rf.ssr.streaming/render-shell %))
                           [:rf/suspense-boundry {:id :x}])]
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data)))
      (is (= :use-a-recognised-reserved-head-or-an-unreserved-keyword
             (:recovery data)))))

  (testing "the RECOGNISED reserved heads are untouched — the guard must
            not swallow the markers it sits next to"
    (testing ":<> fragment still splices"
      (is (= "<p>a</p><p>b</p>"
             (rf.ssr.emit/render-to-string [:<> [:p "a"] [:p "b"]] nil))))

    (testing ":rf/suspense-boundary still raises its OWN distinct error
              from the standard emitter, not the reserved-head one"
      (is (= :rf.error/ssr-suspense-boundary-outside-stream
             (:rf.error/id (head-error #(rf.ssr.emit/render-to-string % nil)
                                       [:rf/suspense-boundary {:id :b}])))))

    (testing ":rf/suspense-boundary still WORKS in the streaming walker"
      (let [{:keys [continuations]}
            (rf.ssr.streaming/render-shell
              [:rf/suspense-boundary {:id :b1 :fallback [:span "…"]}
               [card-view :revenue]])]
        (is (= 1 (count continuations))))))

  (testing "an ORDINARY namespaced keyword is NOT reserved — the guard is
            scoped to `:rf/*` and must not capture app namespaces, which
            are exactly the heads that render as custom elements"
    (is (= "<card>revenue</card>"
           (rf.ssr.emit/render-to-string [:dashboard/card :revenue] nil)))
    (is (= "<widget></widget>"
           (rf.ssr.emit/render-to-string [:rfid/widget] nil))
        "`:rfid/widget` starts with `rf` but is NOT the reserved scheme —
         the check must match the `rf` namespace exactly or an `rf.` dotted
         prefix, not a bare string prefix")))

(deftest both-emitters-agree-on-the-same-head
  (testing "the two JVM emitters are separate implementations;
            pin that they produce the SAME bytes for the same head,
            so an edit to one cannot silently re-fork them"
    (doseq [tree [[:dashboard/card :revenue]
                  [:never-registered/card :revenue]
                  [card-view :revenue]
                  [(rf/view :dashboard/card) :revenue]]]
      (is (= (rf.ssr.emit/render-to-string tree nil)
             (:shell-html (rf.ssr.streaming/render-shell tree)))
          (str "emitter/walker divergence on " (pr-str tree))))))
