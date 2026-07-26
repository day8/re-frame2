(ns re-frame.freehand.trusted-markup-ssr-jvm-test
  "rf2-rrosy — `v/html` on the two STRUCTURAL rendering paths, proven as
  RENDERED OUTPUT.

  This suite exists because of the shape of the bug it closes, and the shape
  dictates the oracle. `v/html` was recognised by the analyzer, validated by
  three position rules, recorded on the compiled manifest's `:html-sites`
  roster and RECOMMENDED by name in the recovery text of every refused
  `dangerouslySetInnerHTML` prop spelling — and lowered by neither emitter.
  Every analyzer assertion, every manifest assertion and every diagnostic
  assertion about it passed. So an assertion at any of those tiers proves
  nothing here: the only oracle that can tell a working bypass from a
  recognised-and-dropped one is the MARKUP, read end to end.

  The two paths, and why both:

    - INTERPRETED structural — `tree/render` over an ordinary declaration.
      This is Freehand's paved path (`{:compiled true}` is opt-in), and the
      donor port covered it not at all: the donor's two arms are the compiled
      emitters, and the interpreted structural walk had no trusted-markup
      constructor.
    - COMPILED structural — the same body promoted, lowered by
      `emit-jvm` into the same canonicaliser slot.

  Both are then serialised by `re-frame.ssr`, which is the half of the
  structural claim that was already complete: the serialiser is NOT donor
  code and already wrote `{:html s}` leaves verbatim, textarea refusal and
  leading-LF compensation included. So this suite is where the new arms meet
  the finished serialiser.

  The markup is pinned as WHOLE strings. The claim is about what did and did
  not get escaped, and a per-substring assertion cannot see an extra
  entity arrive beside a correct one.

  `re-frame.ssr` is a TEST-ONLY dependency of this artefact — the production
  door takes no compile-time require on it (Spec 011 §the wall).

  The BROWSER counterpart, over the same declarations, is
  `re-frame.freehand.trusted-markup-dom-cljs-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.trusted-markup-views :as views]
            [re-frame.freehand.tree :as tree]
            [re-frame.ssr :as ssr]))

(def ^:private markup "<b>bold</b> &amp; <i>italic</i>")

(defn- structural
  "The structural tree one census declaration renders."
  ([view] (structural view {}))
  ([view props] (tree/render [(get views/by-name view) props])))

(defn- rendered
  "What one census declaration's BODY produced — the boundary's children,
  which is the part the two modes must agree on. The boundary itself records
  the declaration's own `:view-id`, and the interpreted and compiled twins
  are two declarations."
  ([view] (rendered view {}))
  ([view props] (:children (structural view props))))

(defn- html
  "The server markup one census declaration serialises to."
  ([view] (html view {}))
  ([view props] (ssr/emit-ui-tree (structural view props))))

;; ---------------------------------------------------------------------------
;; The tree — trusted markup is the `{:html s}` leaf, on both paths
;; ---------------------------------------------------------------------------

(deftest trusted-markup-builds-the-html-leaf-on-both-structural-paths
  (testing "The node shape is the contract 004B pins (§The node schema): a
            map whose discriminating field is `:html`, carried as the
            element's SOLE child. Asserted as the WHOLE element rather than by
            digging for the leaf, so an element that also kept a positional
            child — React's children-vs-innerHTML conflict — cannot pass."
    (doseq [view [:markup-body :markup-body-compiled]]
      (is (= [{:tag :article
               :attrs {:class "body"}
               :children [{:html markup}]}]
             (rendered view {:markup markup}))
          (str view " — the element carries one trusted-markup leaf and nothing else")))))

(deftest the-two-structural-paths-build-the-identical-tree
  (testing "Promotion is a one-line change to a declaration and must not
            change the value it produces. Asserted over every pair, because
            the interpreted arm and the compiled arm are separate code — one
            reads the sole-child position off already-evaluated forms, the
            other off the analyzed AST — and they meet only at the
            canonicaliser's `:html` slot."
    (is (seq views/modes) "the pair table loaded")
    ;; The whole props roster the table's declarations read, so one sweep
    ;; drives every pair: the `keyed-markup` pair takes its key and its
    ;; markup from thunks (see `views/recorder`) and the rest ignore them.
    (let [props {:markup markup :lang "en"
                 :k (constantly "k1") :m (constantly markup)}]
      (doseq [[interpreted compiled] views/modes]
        (is (= (rendered interpreted props) (rendered compiled props))
            (str interpreted " / " compiled " — one tree, either mode"))))))

(deftest the-structural-paths-evaluate-the-key-before-the-trusted-markup
  (testing "rf2-rrosy #6980 audit — the authored order, on the two STRUCTURAL
            paths. `:key` is a prop, so an interpreted walk evaluates it with
            the rest of the map BEFORE it looks at the child position; a
            compiled twin that reversed the two would diverge in exactly the
            ways side effects and exceptions can be observed. The defect the
            audit found was the React emitter's, but the claim is the
            declaration's, so it is pinned on every path the declaration has."
    (doseq [view [:keyed-markup :keyed-markup-compiled]]
      (let [[log props] (views/recorder)]
        (structural view props)
        (is (= [:key :html] @log)
            (str view " — the key expression ran first, and each ran once"))))))

(deftest a-throwing-structural-key-pre-empts-the-trusted-markup
  (testing "The exception-prefix half of the order claim: what has already
            happened when the render fails. A key that throws means the
            markup expression never runs — on both structural paths."
    (doseq [view [:keyed-markup :keyed-markup-compiled]]
      (let [[log props] (views/recorder {:k #(throw (ex-info "key boom" {}))})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key boom"
                              (structural view props))
            (str view " — the authored key expression is what threw"))
        (is (= [] @log)
            (str view " — and the trusted-markup expression never ran"))))))

(deftest a-throwing-structural-markup-runs-after-the-key
  (testing "The other prefix. A markup expression that throws throws AFTER
            the key has been evaluated, because the key is part of the props
            map that precedes the child — so the key's side effect is
            already recorded when the render fails."
    (doseq [view [:keyed-markup :keyed-markup-compiled]]
      (let [[log props] (views/recorder {:m #(throw (ex-info "markup boom" {}))})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"markup boom"
                              (structural view props))
            (str view " — the authored markup expression is what threw"))
        (is (= [:key] @log)
            (str view " — and the key had already been evaluated, once"))))))

;; ---------------------------------------------------------------------------
;; The markup — verbatim, and the control that makes "verbatim" mean something
;; ---------------------------------------------------------------------------

(deftest trusted-markup-reaches-the-server-markup-verbatim
  (testing "The rendered-output proof. `<b>` arrives as an ELEMENT, and the
            already-escaped `&amp;` in the author's string is NOT
            double-escaped — the serialiser writes the leaf and runs no
            escape pass over it."
    (doseq [view [:markup-body :markup-body-compiled]]
      (is (= (str "<article class=\"body\">" markup "</article>")
             (html view {:markup markup}))
          (str view " — the string is the markup")))))

(deftest the-same-string-as-an-ordinary-child-is-fully-escaped
  (testing "The control, and it is the row that makes every row above a
            claim about `v/html` rather than a claim that the serialiser
            emits strings. One declaration differs from its neighbour by the
            call alone, and the call is the entire difference in the output."
    (doseq [view [:escaped-body :escaped-body-compiled]]
      (is (= (str "<article class=\"body\">"
                  "&lt;b&gt;bold&lt;/b&gt; &amp;amp; &lt;i&gt;italic&lt;/i&gt;"
                  "</article>")
             (html view {:markup markup}))
          (str view " — no call, no bypass: every angle bracket and the "
               "ampersand are escaped")))))

(deftest the-bypass-is-scoped-to-the-element-that-owns-it
  (testing "Trusted markup is an ELEMENT's content, so it changes nothing
            about its siblings or its ancestors. The two ordinary strings
            beside it escape in the same render that writes the markup
            verbatim — which is what makes the set of bypasses in a document
            exactly the set of visible calls."
    (doseq [view [:markup-nested :markup-nested-compiled]]
      (is (= (str "<div class=\"page\">"
                  "<h1 class=\"title\">&lt;not markup&gt;</h1>"
                  "<article class=\"body\">" markup "</article>"
                  "<footer class=\"foot\">&lt;also not markup&gt;</footer>"
                  "</div>")
             (html view {:markup markup}))
          (str view " — one bypass, and only where it was written")))))

(def ^:private section-markup
  "`[attribute-chunk content]` for a serialised `<section>`."
  #"^<section ([^>]*)>(.*)</section>$")

(deftest ordinary-props-survive-beside-the-markup
  (testing "The content channel and the props channel are separate: a
            literal class, `#id` sugar, a runtime attribute and a `data-*`
            pass-through all reach the element that carries trusted markup,
            and the markup is still the element's content verbatim. On the
            compiled path the props are build-time literals and the markup a
            render-time write, so this is also the row that would catch a
            write landing in the wrong channel.

            The attribute chunk is asserted as a SET rather than a string,
            and the reason is the shape the DOM suites need rather than
            anything this path suffers from. The two modes classify `:lang`
            differently — a literal for one, a runtime value for the other —
            so the structural tree's `:attrs` map comes out in different
            orders on the two paths (`(:id :lang :data-kind :class)` against
            `(:id :data-kind :class :lang)`). It cannot reach this string:
            the SSR seam emits a PINNED TOTAL ORDER sorted by attribute name
            (004B §Emission is pure), so the served markup here is in fact
            byte-identical across the two modes. It DOES reach the browser,
            where React writes props in insertion order — which is why the
            mounted twins compare an attribute set too (rf2-z0b76, an
            ACCEPTED cross-mode divergence: 004D §The portability law).
            Asserting the set keeps this row the same shape as those, and a
            set is still total, so nothing extra can arrive here unseen."
    (doseq [view [:markup-with-props :markup-with-props-compiled]]
      (let [[_ attrs content] (re-matches section-markup
                                          (html view {:markup markup :lang "en"}))]
        (is (= markup content)
            (str view " — trusted markup is the element's content, verbatim"))
        (is (= #{"class=\"prose\"" "id=\"post\"" "lang=\"en\"" "data-kind=\"body\""}
               (set (str/split (str attrs) #" ")))
            (str view " — and exactly the four authored attributes ride beside it"))))))

(deftest a-literal-site-serialises-like-a-dynamic-one
  (testing "A literal string is the site the compiler can settle whole — it
            records `:static? true` and is serialisable — and that is a
            MANIFEST fact, not a rendering one. The output is the same."
    (doseq [view [:literal-markup :literal-markup-compiled]]
      (is (= "<div class=\"static\"><em>fixed</em></div>" (html view))
          (str view " — the literal reaches the document as markup")))))

;; ---------------------------------------------------------------------------
;; No sanitizer is implied, and the substrate says so
;; ---------------------------------------------------------------------------

(deftest a-hostile-string-is-written-verbatim-because-there-is-no-sanitizer
  (testing "THE ROW THAT MUST NOT GO GREEN BY ACCIDENT. Freehand does not
            sanitise and neither does SSR — no allowlist, no tag or attribute
            filter, no `javascript:` gate, no purifier pass — and this suite
            asserts that rather than leaving it to a docstring. A reader who
            expects a filter here should find this test and learn otherwise;
            a slice that ADDS one has to come here and change it, which is
            the review the addition deserves (004D §Trusted markup — what
            `v/html` does not do)."
    (let [hostile (str "<script>steal()</script>"
                       "<img src=x onerror=\"steal()\">"
                       "<a href=\"javascript:steal()\">go</a>")]
      (doseq [view [:markup-body :markup-body-compiled]]
        (is (= (str "<article class=\"body\">" hostile "</article>")
               (html view {:markup hostile}))
            (str view " — every hostile construct reaches the document exactly "
                 "as written: the verb asserts TRUST, it does not establish it"))))))

;; ---------------------------------------------------------------------------
;; The runtime string check — shared, so both modes answer the same
;; ---------------------------------------------------------------------------

(defn- refusal
  "The diagnostic id `thunk` raises, or `::rendered`."
  [thunk]
  (try (thunk) ::rendered
       (catch clojure.lang.ExceptionInfo ex
         (:rf.error/id (ex-data ex)))))

(deftest a-non-string-markup-value-is-refused-on-both-structural-paths
  (testing "A LITERAL non-string is a compile error, but the argument is
            usually an expression and an expression is a value only the
            render knows. The check is therefore SHARED — one function in the
            canonicaliser both front ends fill the `:html` slot of — so
            `(v/html (:body article))` answers the same diagnostic in both
            modes when the field turns out to be nil."
    (doseq [view [:markup-body :markup-body-compiled]
            bad  [nil 42 {:html "<b>x</b>"} ["<b>x</b>"]]]
      (is (= :rf.error/ui-tree-malformed
             (refusal #(structural view {:markup bad})))
          (str view " — " (pr-str bad) " is refused, not rendered")))))
