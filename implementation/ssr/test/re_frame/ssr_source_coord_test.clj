(ns re-frame.ssr-source-coord-test
  "Server-side (JVM) dev-mode view annotation, at the reg-view REGISTRATION
  boundary (rf2-8vi4q).

  ## What this pins

  A registered view reached through its CALLABLE head — `[(rf/view :id) …]`
  or a Var, the shape isomorphic pages actually compose with — renders on
  the server WITH both dev-mode annotations: `data-rf2-source-coord`
  (Spec 006 §Source-coord annotation) and `data-rf-view` (Spec 006 §View
  tagging contract). The client stamps the same two at React render time,
  so the server markup byte-matches the dev client render and a dev SSR
  page hydrates as a clean ADOPTION rather than an attribute mismatch.

  ## History

  This namespace used to pin the INTERIM absence rf2-j81hs created: it had
  deleted the emitter's keyword-view branch (a keyword head is a DOM
  element on every host) — the branch that carried the old emitter-side
  injection — leaving no server render annotated. rf2-8vi4q closes that
  gap by moving annotation to the registration boundary on BOTH hosts (a
  debug-gated wrapper on the registered `:handler-fn`,
  `re-frame.views.jvm-source-coord-annotation`), and deletes the orphaned
  emitter fns. So the assertions here flipped from `not annotated` to
  `annotated`, and the production-gate arm is reinstated at the new site.

  A keyword head is still NOT a view and still NOT annotated — it is a DOM
  element, unchanged by this bead (`keyword-head-*` below).

  ## Posture split (rf2-lwtlk)

  The annotations this namespace is named for are DEV-ONLY BY DESIGN: the
  wrapper `re-frame.views/jvm-source-coord-annotation` is installed at
  `reg-view` REGISTRATION time behind `interop/debug-enabled?`, which the JVM
  reads once at namespace-load time.  Under the real
  `-Dre-frame.debug=false` gate no view is ever wrapped, so no server markup
  carries `data-rf2-source-coord` or `data-rf-view`.  That is the elision
  working, not a defect.

  So every assertion ABOUT an annotation — present or absent — lives inside a
  `(when interop/debug-enabled? …)` arm marked `rf2-lwtlk`, kept verbatim.
  The negative ones travel with the positive ones deliberately: \"the outer
  view did not stamp itself\", \"a fragment root is not annotated\", \"a
  keyword head is not annotated\" all pass VACUOUSLY under the gate, where
  nothing stamps anything, so leaving them outside the arm would report a
  green that proved nothing.

  What is posture-independent, and therefore what this namespace now
  contributes to `scripts/test-ssr-prod-gate.sh`, is the RENDER: which
  element each head shape resolves to and what it emits.  A Form-2 view's
  inner output is the thing rendered; a fragment root emits its children
  unwrapped; a keyword head is an element, not a view; a nested view-ref root
  renders the inner view.  Those hold in both postures and are asserted
  outside the arm.  `production-build-emits-no-annotation` gains a REAL-gate
  arm (`when-not interop/debug-enabled?`) so the production posture is
  witnessed by the gate itself rather than only by a `with-redefs` rebind,
  which cannot reach a load-time gate (rf2-9c2jf)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.views.jvm-source-coord-annotation :as jvm-annot]))

(use-fixtures :each tf/reset-runtime)

(defn- source-coord? [html] (str/includes? html "data-rf2-source-coord="))
(defn- view-id?      [html] (str/includes? html "data-rf-view="))
(defn- both-annotations? [html] (and (source-coord? html) (view-id? html)))

;; ---------------------------------------------------------------------------
;; A registered view reached through a callable head IS annotated
;; ---------------------------------------------------------------------------

(deftest callable-head-view-is-annotated-server-side
  (testing "rf2-8vi4q — a view reached through its callable head (the shape
            every isomorphic page uses) renders with BOTH annotations on
            its root DOM element."
    (rf/reg-view ^{:rf/id :ssr-coord-test/banner} banner-view []
      [:h1 "hi"])

    ;; SEMANTIC, posture-independent (rf2-lwtlk): both head shapes resolve to
    ;; the same registered view, and it renders ITS OWN root carrying ITS OWN
    ;; content — the "not swallowed" property below, stated without reference
    ;; to the dev attributes that happen to sit on that root in dev posture.
    (testing "both head shapes render the registered view's own <h1> root"
      (doseq [[label html] [["Var head"
                             (ssr/render-to-string [banner-view] {})]
                            ["`(rf/view :id)` head"
                             (ssr/render-to-string
                               [(rf/view :ssr-coord-test/banner)] {})]]]
        (is (str/starts-with? html "<h1") label)
        (is (str/ends-with? html ">hi</h1>") label)))

    ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (testing "Var head"
        (is (both-annotations? (ssr/render-to-string [banner-view] {}))))

      (testing "`(rf/view :id)` head"
        (is (both-annotations?
              (ssr/render-to-string [(rf/view :ssr-coord-test/banner)] {}))))

      (testing "the data-rf-view value is `(str id)`"
        (is (str/includes? (ssr/render-to-string [banner-view] {})
                           "data-rf-view=\":ssr-coord-test/banner\"")))

      (testing "the view genuinely rendered its own root — not swallowed"
        (is (str/starts-with? (ssr/render-to-string [banner-view] {}) "<h1 "))))))

;; ---------------------------------------------------------------------------
;; Exact byte shape for a programmatic (coordless) registration — degrade
;; ---------------------------------------------------------------------------

(deftest coordless-programmatic-registration-degrades-to-question-marks
  (testing "rf2-8vi4q — a `reg-view*` with no macro-captured coords still
            annotates, degrading the source-coord to <ns>:<sym>:?:? so both
            hosts match on the programmatic path too."
    (rf/reg-view* :ssr-coord-test/prog {} (fn [] [:div "prog"]))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/prog)] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk): a programmatic
      ;; registration renders at all, and renders its own root and body.
      ;; The gate removes the ATTRIBUTES, never the element.
      (is (str/starts-with? html "<div") (pr-str html))
      (is (str/ends-with? html ">prog</div>") (pr-str html))

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). The exact
      ;; byte shape of the `?:?` degrade is the whole point of this deftest
      ;; and is a statement about the dev annotation dialect.
      (when interop/debug-enabled?
        (is (= (str "<div data-rf2-source-coord=\"ssr-coord-test:prog:?:?\""
                    " data-rf-view=\":ssr-coord-test/prog\">prog</div>")
               html)
            (str "coordless degrade must produce exactly the client shape; got: "
                 (pr-str html)))))))

;; ---------------------------------------------------------------------------
;; Author-supplied attribute values are PRESERVED
;; ---------------------------------------------------------------------------

(deftest author-supplied-annotation-values-are-preserved
  (testing "rf2-8vi4q — an author who set data-rf2-source-coord / data-rf-view
            on the root keeps THEIR value; the wrapper only fills a missing
            key. Mirrors the client `inject-source-coord-attr` preservation."
    (rf/reg-view* :ssr-coord-test/authored {}
                  (fn [] [:div {:data-rf-view "author-owned"
                                :id           "x"} "a"]))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/authored)] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk): the author's OWN attribute
      ;; map is emitted whatever the posture — `data-rf-view` here is an
      ;; author-owned attribute that merely shares a name with the framework's,
      ;; and `:id` is an ordinary one. Neither is elided by the gate.
      (is (str/includes? html "data-rf-view=\"author-owned\"")
          "the author's data-rf-view value survives")
      (is (str/includes? html "id=\"x\"")
          "the author's other attributes survive alongside it")

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). Both of these
      ;; are about the WRAPPER: that it declined to overwrite, and that it
      ;; filled the key the author left out. Under the gate no wrapper exists,
      ;; so "did not overwrite" would pass vacuously.
      (when interop/debug-enabled?
        (is (not (str/includes? html "data-rf-view=\":ssr-coord-test/authored\""))
            "the wrapper did not overwrite it")
        (is (source-coord? html)
            "the missing key IS still filled by the wrapper")))))

;; ---------------------------------------------------------------------------
;; Form-2 views: the inner render fn's output is annotated
;; ---------------------------------------------------------------------------

(deftest form-2-view-inner-output-is-annotated
  (testing "rf2-8vi4q — a Form-2 view (outer fn returns an inner render fn)
            has the INNER output annotated, mirroring the client Form-2
            wrapper. The emitter unwraps the inner fn; the wrapper's Form-2
            branch re-wraps so the inner hiccup gets the attributes."
    (rf/reg-view* :ssr-coord-test/form2 {}
                  (fn [] (fn [] [:section "inner"])))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/form2)] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk): the emitter unwraps the
      ;; outer fn and renders the INNER fn's hiccup. That is Form-2 support
      ;; itself, and it holds with or without the annotation wrapper.
      (is (str/starts-with? html "<section") (pr-str html))
      (is (str/ends-with? html ">inner</section>") (pr-str html))

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). The wrapper's
      ;; Form-2 branch re-wraps so the INNER hiccup gets the attributes; the
      ;; trailing space in "<section " is what proves an attribute landed.
      (when interop/debug-enabled?
        (is (both-annotations? html)
            (str "Form-2 inner output must be annotated; got: " (pr-str html)))
        (is (str/starts-with? html "<section "))))))

;; ---------------------------------------------------------------------------
;; Non-DOM roots (fragment, nested view-ref head) skip — documented exemption
;; ---------------------------------------------------------------------------

(deftest fragment-root-is-not-annotated
  (testing "rf2-8vi4q — a fragment `:<>` root is a non-DOM root: the wrapper
            skips it (the exact exemption the client walk takes), so the
            fragment's children emit unwrapped."
    (rf/reg-view* :ssr-coord-test/frag {}
                  (fn [] [:<> [:p "a"] [:p "b"]]))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/frag)] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk): a `:<>` root emits its
      ;; children and nothing else — no wrapper element is invented for it.
      (is (= "<p>a</p><p>b</p>" html))

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). "not
      ;; annotated" is vacuously true under the gate, where NOTHING is
      ;; annotated; it only says something where the wrapper exists and
      ;; deliberately skipped a non-DOM root. The `=` above already pins the
      ;; byte shape in both postures.
      (when interop/debug-enabled?
        (is (not (both-annotations? html)))))))

(deftest nested-view-ref-root-is-not-doubly-annotated
  (testing "rf2-8vi4q — a view whose root is ANOTHER view-ref (a callable
            head) skips at the outer wrapper: the head is a fn, not a
            DOM-tag keyword. The inner view annotates its own root, so the
            single annotated element is the inner one — no double-stamp."
    (rf/reg-view* :ssr-coord-test/inner {} (fn [] [:span "in"]))
    (rf/reg-view* :ssr-coord-test/outer {}
                  (fn [] [(rf/view :ssr-coord-test/inner)]))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/outer)] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk): a view whose root is
      ;; another view-ref resolves through to the inner view and emits the
      ;; inner view's element ONCE — no wrapper element, no duplication.
      (is (str/starts-with? html "<span") (pr-str html))
      (is (str/ends-with? html ">in</span>") (pr-str html))
      (is (= 1 (count (re-seq #"<span" html)))
          (str "the inner view rendered exactly once; got: " (pr-str html)))

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). All three
      ;; original assertions are about WHICH element carries the stamp; under
      ;; the gate no element does, so the two negatives pass vacuously and the
      ;; count is trivially 0.
      (when interop/debug-enabled?
        (is (str/includes? html "data-rf-view=\":ssr-coord-test/inner\"")
            "the inner view annotated its own root")
        (is (not (str/includes? html "data-rf-view=\":ssr-coord-test/outer\""))
            "the outer view (callable-head root) did not stamp itself")
        (is (= 1 (count (re-seq #"data-rf-view=" html)))
            (str "exactly one annotated element; got: " (pr-str html)))))))

;; ---------------------------------------------------------------------------
;; The streaming shell walker inherits annotation through the handler-fn
;; ---------------------------------------------------------------------------

(deftest streaming-shell-annotates-through-the-wrapped-handler
  (testing "rf2-8vi4q — the streaming walker carries NO annotation logic; it
            resolves callable heads through the SAME wrapped handler-fn, so a
            streamed shell is annotated identically to the sync render. The
            emitter-side asymmetry (two walkers, two copies) that rf2-j81hs
            worried about is gone: one wrapper, both paths."
    (rf/reg-view ^{:rf/id :ssr-coord-test/shell} shell-view []
      [:main "shell"])
    (let [{:keys [shell-html]} (streaming/render-shell [shell-view])
          sync-html            (ssr/render-to-string [shell-view] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk), and a STRONGER statement
      ;; of this deftest's actual claim: the streaming walker carries no
      ;; annotation logic of its own because it resolves callable heads
      ;; through the same handler-fn as the sync path. Comparing the two
      ;; renders proves "one wrapper, both paths" in EITHER posture — in dev
      ;; both are annotated identically, under the gate both are bare.
      (is (= sync-html shell-html)
          (str "streamed shell must match the sync render byte for byte; got: "
               (pr-str shell-html) " vs " (pr-str sync-html)))
      (is (str/starts-with? shell-html "<main") (pr-str shell-html))
      (is (str/ends-with? shell-html ">shell</main>") (pr-str shell-html))

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (both-annotations? shell-html)
            (str "streamed shell must be annotated; got: " (pr-str shell-html)))
        (is (str/includes? shell-html
                           "data-rf-view=\":ssr-coord-test/shell\""))))))

;; ---------------------------------------------------------------------------
;; A keyword head is an element, still NOT a view, still NOT annotated
;; ---------------------------------------------------------------------------

(deftest keyword-head-is-an-element-and-is-not-annotated
  (testing "unchanged by rf2-8vi4q — a keyword head is a DOM / custom element
            on every host (rf2-j81hs), never a view, so it carries no
            annotation even when a view of the same id is registered."
    (rf/reg-view* :coord-demo/card {} (fn [_] [:div.card "x"]))
    (let [html (ssr/render-to-string [:coord-demo/card :revenue] {})]
      ;; SEMANTIC, posture-independent (rf2-lwtlk): the keyword head is
      ;; emitted as a CUSTOM ELEMENT with its argument as a child, and the
      ;; identically-named registered view is NOT invoked (no `.card` div).
      ;; That resolution rule is the load-bearing half and is posture-free;
      ;; the `=` pins it exactly.
      (is (= "<card>revenue</card>" html))

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). Vacuous under
      ;; the gate, where nothing anywhere is annotated.
      (when interop/debug-enabled?
        (is (not (both-annotations? html)))))))

(deftest plain-hiccup-not-annotated
  (testing "unchanged — ordinary tags were never annotated"
    ;; SEMANTIC, posture-independent (rf2-lwtlk): ordinary hiccup emits
    ;; exactly itself. Without this the deftest has no residue under the
    ;; gate at all — `not annotated` is trivially true when the wrapper does
    ;; not exist.
    (is (= "<div><span>x</span></div>"
           (ssr/render-to-string [:div [:span "x"]] {})))

    ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (is (not (both-annotations? (ssr/render-to-string [:div [:span "x"]] {})))))))

;; ---------------------------------------------------------------------------
;; Production gate (rf2-wtd8z finding 3) — reinstated at the new site
;; ---------------------------------------------------------------------------
;;
;; The gate is read at REGISTRATION time (mirroring the client
;; `make-wrap-view`, which decides whether to wrap when the view is
;; registered). So the production arm must REGISTER the view under a false
;; `interop/debug-enabled?`; flipping it only around the render would not
;; un-wrap an already-wrapped handler-fn. That registration-time semantics
;; is itself the thing under test — a production SSR build reads the flag
;; false at boot, before any view registers.

(deftest production-build-emits-no-annotation
  (testing "rf2-8vi4q — a view REGISTERED under `interop/debug-enabled?`
            false (the production SSR posture) stores an unwrapped
            handler-fn, so its server markup carries neither annotation —
            symmetric with the CLJS :advanced + goog.DEBUG=false build that
            Closure DCEs the client walk. The dev arm annotates; the prod
            arm does not."
    ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). Under the real
    ;; `-Dre-frame.debug=false` gate there is no "debug ON at registration"
    ;; to have: the flag was read before this namespace loaded.
    (when interop/debug-enabled?
      (testing "debug ON at registration — annotated (the load-bearing arm)"
        (rf/reg-view* :ssr-coord-test/gated-dev {} (fn [] [:h2 "g"]))
        (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/gated-dev)] {})]
          (is (str/starts-with? html "<h2 "))
          (is (both-annotations? html)))))

    (testing "debug OFF at registration — NOT annotated"
      (with-redefs [interop/debug-enabled? false]
        (rf/reg-view* :ssr-coord-test/gated-prod {} (fn [] [:h2 "g"])))
      ;; render with debug back at its default — the wrap decision was made
      ;; at registration, so the markup is unannotated regardless.
      (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/gated-prod)] {})]
        (is (= "<h2>g</h2>" html))
        (is (not (both-annotations? html)))))

    ;; rf2-lwtlk — the REAL-gate arm, and the reason this deftest is worth
    ;; running in `scripts/test-ssr-prod-gate.sh` at all. The `with-redefs`
    ;; above rebinds the Var AFTER the framework has loaded; a load-time gate
    ;; is invisible to that (rf2-9c2jf), so it proves the wrapper's own
    ;; branch and nothing about the documented production posture. This arm
    ;; runs only when the JVM was actually started with
    ;; `-Dre-frame.debug=false`, and registers under it for real.
    (when-not interop/debug-enabled?
      (testing "-Dre-frame.debug=false on the JVM — NOT annotated, for real"
        (rf/reg-view* :ssr-coord-test/real-gate {} (fn [] [:h2 "g"]))
        (let [html (ssr/render-to-string
                     [(rf/view :ssr-coord-test/real-gate)] {})]
          (is (= "<h2>g</h2>" html)
              (str "the real production gate must elide both annotations; got: "
                   (pr-str html))))
        (rf/reg-view ^{:rf/id :ssr-coord-test/real-gate-macro} real-gate-macro []
          [:h3 "m"])
        (let [html (ssr/render-to-string [real-gate-macro] {})]
          (is (= "<h3>m</h3>" html)
              (str "…including on the macro path, which is where the coords "
                   "would have come from; got: " (pr-str html))))))))

;; ---------------------------------------------------------------------------
;; The formatter unit itself degrades — belt-and-braces on the shared dialect
;; ---------------------------------------------------------------------------

(deftest jvm-formatter-produces-the-shared-dialect
  (testing "rf2-8vi4q — the JVM formatter is byte-identical in shape to the
            CLJS one (pinned cross-host in `source-coord-parity-test`). Here
            just confirm the two value shapes directly."
    (is (= "a.b:c:1:2"
           (jvm-annot/format-source-coord :a.b/c {:line 1 :column 2})))
    (is (= "a.b:c:?:?"
           (jvm-annot/format-source-coord :a.b/c {})))
    (is (= ":a.b/c" (jvm-annot/format-view-id :a.b/c)))))
