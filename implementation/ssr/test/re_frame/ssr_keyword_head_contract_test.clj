(ns re-frame.ssr-keyword-head-contract-test
  "rf2-j81hs — the ONE render-tree head grammar, pinned on the JVM emitters.

  A keyword head in a render tree is a DOM / custom element on EVERY
  host. It is never a view reference. Views are referenced by callable
  binding — the Var `rf/reg-view` defs for you, or the `(rf/view :id)`
  runtime handle.

  ## Why this test exists

  Both JVM emitters used to probe `(registrar/lookup :view head)` on
  their keyword branch, so `[:dashboard/card :revenue]` resolved to the
  registered view server-side. No client substrate does that (Conventions
  §Render-tree shape vs runtime lookup; Cross-Spec-Interactions §237):
  Reagent's `parse-tag` runs `(name tag)`, so the same head paints
  `<card>revenue</card>` in a browser. One hiccup form, two meanings —
  the server rendered a real subtree and the client painted a phantom,
  and NEITHER side said anything. That is how it shipped in the flagship
  streaming example (rf2-o4rbh) and survived every server-side test.

  Per the rf2-j81hs ruling (option (b), finishing rf2-n82bbu): the probe
  is gone. These tests are the corpus-wide statement of the rule on the
  only two emitters that ever diverged from it — the standard emitter and
  the streaming shell walker.

  ## The child spelling (rf2-53lsj)

  rf2-j81hs aligned the HEAD and stopped there, leaving the two hosts
  emitting different TEXT for the identical tree:

      [:dashboard/card :revenue]  JVM -> <card>:revenue</card>
                              Reagent -> <card>revenue</card>

  Its cross-host test acknowledged the difference and argued past it —
  \"element structure is what hydration reconciles\". React hydration
  reconciles TEXT nodes too, so that was the same bug one layer down,
  institutionalised under a name (`client-markup-matches-the-jvm-emitter`)
  that claimed more than it asserted.

  A keyword or symbol child is now spelled by its `name` on every host:
  no leading colon, and the NAMESPACE IS DROPPED (`:a/b` paints `b`).
  That second half is the part a colon-stripping fix gets wrong; Reagent
  routes a named child through `(name x)`, it does not trim the printed
  form. Namespaced symbols were measured to diverge the same way and are
  fixed by the same arm.

  The client half of the same contract is pinned substrate-side in
  `implementation/adapters/reagent/test/re_frame/ssr_keyword_head_contract_cljs_test.cljs`,
  which asserts the SAME head paints the SAME element AND the same bytes.
  Together the two files are the cross-host proof.

  ## Posture split (rf2-lwtlk)

  The head grammar itself is production-real and is asserted throughout
  without a posture guard. The single exception is inside
  `views-are-referenced-by-callable-head`, whose `(rf/view :id)` arm pins the
  registration-boundary annotations byte for byte. Those attributes exist
  only under `interop/debug-enabled?` (read once at namespace-load time), so
  under `-Dre-frame.debug=false` the registered handle renders the same view
  subtree without them. The annotated literal is kept verbatim in a
  `(when interop/debug-enabled? …)` arm; the arm's actual claim — that BOTH
  spellings resolve the same view — is restated posture-independently by
  comparing the two renders directly, which is a stronger statement than the
  `str/includes?` triple it replaces and holds in both postures."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]))

;; The view body, held as a plain fn so the SAME implementation backs all
;; three spellings under test (keyword head, Var head, `(rf/view :id)`).
(defn card-view [card-id]
  [:div.card [:h3 (str card-id)]])

;; `tf/reset-runtime` runs `registrar/clear-all!` before EVERY test, so a
;; top-level `reg-view` would be wiped by the time a test body runs — and
;; every keyword-head assertion below would then pass vacuously, against
;; an UNREGISTERED id, proving nothing about the removed registry probe.
;; (That is not hypothetical: the first draft of this file did exactly
;; that.) Re-register after the reset, inside the fixture chain, so
;; `:dashboard/card` is genuinely live for each test.
(defn- with-registered-card [test-fn]
  (rf/reg-view* :dashboard/card {} card-view)
  (test-fn))

(use-fixtures :each tf/reset-runtime with-registered-card)

;; ===========================================================================
;; The standard emitter — `render-to-string`
;; ===========================================================================

(deftest keyword-head-is-an-element-not-a-view
  (testing "rf2-j81hs — a namespaced keyword head that NAMES a registered
            view still emits a custom element. The keyword's `name` is the
            tag (matching `parse-tag`'s `(name tag)` on every client
            substrate); its namespace is dropped; the trailing argument is
            a text child.

            rf2-53lsj — the text child is spelled by the keyword's `name`
            too: no leading colon, namespace dropped. These are the exact
            bytes Reagent + react-dom/server paints for the same tree
            (measured, and pinned as a cross-host equality in the CLJS
            twin)."
    (is (= "<card>revenue</card>"
           (emit/render-to-string [:dashboard/card :revenue] nil))))

  (testing "the registration is genuinely present — this test would be
            vacuous if `:dashboard/card` were simply unregistered, which
            is exactly the shape that let the old divergence hide"
    (is (some? (rf/view :dashboard/card))
        "card-view must be registered for the assertion above to mean
         anything: the point is that a REGISTERED id still emits an
         element."))

  (testing "an unregistered keyword head emits the identical element —
            registration state does not change the head's meaning, which
            is the whole content of the one-grammar rule"
    (is (= "<card>revenue</card>"
           (emit/render-to-string [:never-registered/card :revenue] nil)))))

(deftest scalar-children-are-spelled-by-name
  (testing "rf2-53lsj — a keyword or symbol CHILD is spelled by its `name`:
            no leading colon, namespace dropped. #6378 aligned the head
            meaning and left the child spelling diverging, so the same raw
            tree still produced different TEXT on the two hosts — and
            React hydration reconciles text nodes, not just elements.

            Every expectation here was measured against Reagent +
            react-dom/server before it was written; the CLJS twin asserts
            the same strings from the other side."
    (are [expected tree] (= expected (emit/render-to-string tree nil))
      "<div>revenue</div>" [:div :revenue]
      "<div>b</div>"       [:div :a/b]
      "<div>leaf</div>"    [:div :ns.deep/leaf]
      "<div>sym</div>"     [:div 'sym]
      "<div>b</div>"       [:div 'a/b]
      "<div>revenue growth</div>" [:div :revenue " " :growth]
      "<div>1a</div>"      [:div 1 :a]))

  (testing "the namespace is DROPPED, not rendered — the case a
            colon-stripping fix would have got wrong. `:a/b` paints `b`,
            never `a/b`, because Reagent routes a named child through
            `(name x)` rather than trimming the printed form."
    (is (= "<div>b</div>" (emit/render-to-string [:div :a/b] nil)))
    (is (not= "<div>a/b</div>" (emit/render-to-string [:div :a/b] nil))))

  (testing "escaping still applies to the NAME — spelling a child by
            `name` must not become an escape bypass"
    (is (= "<div>x&lt;y</div>" (emit/render-to-string [:div :x<y] nil))))

  (testing "the classes that already agreed are unchanged"
    (are [expected tree] (= expected (emit/render-to-string tree nil))
      "<div>plain</div>" [:div "plain"]
      "<div>9</div>"     [:div 9]
      "<div></div>"      [:div nil]
      "<div></div>"      [:div true]))

  (testing "the streaming walker agrees — its scalar arm delegates to the
            standard emitter, so this is a delegation pin, not a second
            implementation"
    (doseq [tree [[:div :revenue] [:div :a/b] [:div 'a/b] [:dashboard/card :revenue]]]
      (is (= (emit/render-to-string tree nil)
             (:shell-html (streaming/render-shell tree)))
          (str "emitter/walker divergence on " (pr-str tree))))))

(deftest keyword-head-carries-ordinary-element-syntax
  (testing "rf2-j81hs — the element branch is the ORDINARY element branch:
            a keyword head that happens to name a view still gets
            `.class`/`#id` sugar, an attrs map, and child recursion. It is
            not a special case, it is the same case."
    (is (= "<card class=\"revenue\" data-x=\"1\"><b>hi</b></card>"
           (emit/render-to-string [:dashboard/card.revenue {:data-x "1"} [:b "hi"]] nil)))))

(deftest views-are-referenced-by-callable-head
  (testing "rf2-j81hs — the two supported spellings both RESOLVE the view
            (the head-grammar point: a callable head is a view, a keyword
            head is an element), so the migration away from keyword refs
            has somewhere to land. rf2-8vi4q layers on top: the REGISTERED
            handle `(rf/view :id)` carries the dev-mode registration-
            boundary annotations, while a bare fn Var that never went
            through registration does not — a distinction the raw
            `card-view` fn (a test-only stand-in) now makes visible."
    (testing "a bare fn Var resolves the view — UNANNOTATED (it is the raw
              render fn, not the registered handle)"
      (is (= "<div class=\"card\"><h3>:revenue</h3></div>"
             (emit/render-to-string [card-view :revenue] nil))))

    ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). The registered
    ;; handle carries the annotations only under `interop/debug-enabled?`.
    (when interop/debug-enabled?
      (testing "`(rf/view :id)` — the REGISTERED handle — resolves the view AND
                carries both dev annotations (rf2-8vi4q). In idiomatic usage
                the symbol `reg-view` defs IS `(rf/view id)`, so THIS is what a
                real Var reference emits."
        (is (= (str "<div class=\"card\""
                    " data-rf2-source-coord=\"dashboard:card:?:?\""
                    " data-rf-view=\":dashboard/card\">"
                    "<h3>:revenue</h3></div>")
               (emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))))

    (testing "both spellings resolve to the SAME view subtree — the class and
              child agree; the registered handle merely adds the debug-gated
              annotation attributes on the root"
      (is (str/includes? (emit/render-to-string [card-view :revenue] nil)
                         "class=\"card\""))
      (is (str/includes? (emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)
                         "class=\"card\""))
      (is (str/includes? (emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)
                         "<h3>:revenue</h3>")))

    ;; rf2-lwtlk — the REAL-gate arm. With no annotation wrapper installed the
    ;; two spellings are not merely "the same subtree modulo attributes", they
    ;; are byte-identical — which is the head-grammar claim in its purest
    ;; form, and is only observable in this posture.
    (when-not interop/debug-enabled?
      (testing "under -Dre-frame.debug=false the registered handle and the bare
                Var render byte-identically — nothing but the debug-gated
                attributes ever separated them"
        (is (= (emit/render-to-string [card-view :revenue] nil)
               (emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))
        (is (= "<div class=\"card\"><h3>:revenue</h3></div>"
               (emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))))))

;; ===========================================================================
;; The streaming shell walker — `render-shell`
;; ===========================================================================

(deftest streaming-walker-keyword-head-is-an-element
  (testing "rf2-j81hs — the shell walker obeys the same one grammar. It
            had its OWN `(registrar/lookup :view head)` probe, so fixing
            only the standard emitter would have left the streaming path
            diverging from every client substrate."
    (let [{:keys [shell-html]} (streaming/render-shell [:dashboard/card :revenue])]
      (is (= "<card>revenue</card>" shell-html))))

  (testing "and still recurses through the element into nested children,
            so a suspense boundary underneath a keyword head is reachable"
    (let [{:keys [shell-html continuations]}
          (streaming/render-shell
            [:dashboard/card
             [:rf/suspense-boundary {:id :b1 :fallback [:span "loading"]}
              [card-view :revenue]]])]
      (is (= 1 (count continuations))
          "the boundary under an element head must still be recorded")
      (is (str/starts-with? shell-html "<card>")
          "the element head is emitted, not resolved away")))

  (testing "callable heads resolve in the walker exactly as in the
            standard emitter"
    (let [{:keys [shell-html]} (streaming/render-shell [card-view :revenue])]
      (is (= "<div class=\"card\"><h3>:revenue</h3></div>" shell-html)))))

;; ===========================================================================
;; Unrecognised reserved `:rf/*` heads fail loud (rf2-j81hs §4)
;; ===========================================================================

(defn- head-error
  "Render `tree` and return the thrown ex-data, or nil if it did not throw."
  [render-fn tree]
  (try (render-fn tree) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest unrecognised-reserved-rf-head-fails-loud
  (testing "rf2-j81hs — with every keyword head now an element, a misspelt
            reserved head would otherwise paint a phantom element and say
            nothing: `:rf/suspense-boundry` has a `name` that passes the
            DOM tag grammar. That is the very failure this bead removes,
            displaced by one keystroke, so the reserved scheme fails loud."
    (let [data (head-error #(emit/render-to-string % nil)
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
    (is (some? (head-error #(emit/render-to-string % nil) [:rf.ssr/nope]))))

  (testing "the streaming walker rejects it identically — it carried its
            own keyword branch, so a one-sided guard would re-fork the
            hosts"
    (let [data (head-error #(:shell-html (streaming/render-shell %))
                           [:rf/suspense-boundry {:id :x}])]
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data)))
      (is (= :use-a-recognised-reserved-head-or-an-unreserved-keyword
             (:recovery data)))))

  (testing "the RECOGNISED reserved heads are untouched — the guard must
            not swallow the markers it sits next to"
    (testing ":<> fragment still splices"
      (is (= "<p>a</p><p>b</p>"
             (emit/render-to-string [:<> [:p "a"] [:p "b"]] nil))))

    (testing ":rf/suspense-boundary still raises its OWN distinct error
              from the standard emitter, not the reserved-head one"
      (is (= :rf.error/ssr-suspense-boundary-outside-stream
             (:rf.error/id (head-error #(emit/render-to-string % nil)
                                       [:rf/suspense-boundary {:id :b}])))))

    (testing ":rf/suspense-boundary still WORKS in the streaming walker"
      (let [{:keys [continuations]}
            (streaming/render-shell
              [:rf/suspense-boundary {:id :b1 :fallback [:span "…"]}
               [card-view :revenue]])]
        (is (= 1 (count continuations))))))

  (testing "an ORDINARY namespaced keyword is NOT reserved — the guard is
            scoped to `:rf/*` and must not capture app namespaces, which
            are exactly the heads that now render as custom elements"
    (is (= "<card>revenue</card>"
           (emit/render-to-string [:dashboard/card :revenue] nil)))
    (is (= "<widget></widget>"
           (emit/render-to-string [:rfid/widget] nil))
        "`:rfid/widget` starts with `rf` but is NOT the reserved scheme —
         the check must match the `rf` namespace exactly or an `rf.` dotted
         prefix, not a bare string prefix")))

(deftest both-emitters-agree-on-the-same-head
  (testing "rf2-j81hs — the two JVM emitters were fixed independently;
            pin that they now produce the SAME bytes for the same head,
            so a future edit to one cannot silently re-fork them"
    (doseq [tree [[:dashboard/card :revenue]
                  [:never-registered/card :revenue]
                  [card-view :revenue]
                  [(rf/view :dashboard/card) :revenue]]]
      (is (= (emit/render-to-string tree nil)
             (:shell-html (streaming/render-shell tree)))
          (str "emitter/walker divergence on " (pr-str tree))))))
