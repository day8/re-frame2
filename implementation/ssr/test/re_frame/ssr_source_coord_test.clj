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
  element, unchanged by this bead (`keyword-head-*` below)."
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

    (testing "Var head"
      (is (both-annotations? (ssr/render-to-string [banner-view] {}))))

    (testing "`(rf/view :id)` head"
      (is (both-annotations?
            (ssr/render-to-string [(rf/view :ssr-coord-test/banner)] {}))))

    (testing "the data-rf-view value is `(str id)`"
      (is (str/includes? (ssr/render-to-string [banner-view] {})
                         "data-rf-view=\":ssr-coord-test/banner\"")))

    (testing "the view genuinely rendered its own root — not swallowed"
      (is (str/starts-with? (ssr/render-to-string [banner-view] {}) "<h1 ")))))

;; ---------------------------------------------------------------------------
;; Exact byte shape for a programmatic (coordless) registration — degrade
;; ---------------------------------------------------------------------------

(deftest coordless-programmatic-registration-degrades-to-question-marks
  (testing "rf2-8vi4q — a `reg-view*` with no macro-captured coords still
            annotates, degrading the source-coord to <ns>:<sym>:?:? so both
            hosts match on the programmatic path too."
    (rf/reg-view* :ssr-coord-test/prog {} (fn [] [:div "prog"]))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/prog)] {})]
      (is (= (str "<div data-rf2-source-coord=\"ssr-coord-test:prog:?:?\""
                  " data-rf-view=\":ssr-coord-test/prog\">prog</div>")
             html)
          (str "coordless degrade must produce exactly the client shape; got: "
               (pr-str html))))))

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
      (is (str/includes? html "data-rf-view=\"author-owned\"")
          "the author's data-rf-view value survives")
      (is (not (str/includes? html "data-rf-view=\":ssr-coord-test/authored\""))
          "the wrapper did not overwrite it")
      (is (source-coord? html)
          "the missing key IS still filled by the wrapper"))))

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
      (is (both-annotations? html)
          (str "Form-2 inner output must be annotated; got: " (pr-str html)))
      (is (str/starts-with? html "<section ")))))

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
      (is (= "<p>a</p><p>b</p>" html))
      (is (not (both-annotations? html))))))

(deftest nested-view-ref-root-is-not-doubly-annotated
  (testing "rf2-8vi4q — a view whose root is ANOTHER view-ref (a callable
            head) skips at the outer wrapper: the head is a fn, not a
            DOM-tag keyword. The inner view annotates its own root, so the
            single annotated element is the inner one — no double-stamp."
    (rf/reg-view* :ssr-coord-test/inner {} (fn [] [:span "in"]))
    (rf/reg-view* :ssr-coord-test/outer {}
                  (fn [] [(rf/view :ssr-coord-test/inner)]))
    (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/outer)] {})]
      (is (str/includes? html "data-rf-view=\":ssr-coord-test/inner\"")
          "the inner view annotated its own root")
      (is (not (str/includes? html "data-rf-view=\":ssr-coord-test/outer\""))
          "the outer view (callable-head root) did not stamp itself")
      (is (= 1 (count (re-seq #"data-rf-view=" html)))
          (str "exactly one annotated element; got: " (pr-str html))))))

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
    (let [{:keys [shell-html]} (streaming/render-shell [shell-view])]
      (is (both-annotations? shell-html)
          (str "streamed shell must be annotated; got: " (pr-str shell-html)))
      (is (str/includes? shell-html "data-rf-view=\":ssr-coord-test/shell\"")))))

;; ---------------------------------------------------------------------------
;; A keyword head is an element, still NOT a view, still NOT annotated
;; ---------------------------------------------------------------------------

(deftest keyword-head-is-an-element-and-is-not-annotated
  (testing "unchanged by rf2-8vi4q — a keyword head is a DOM / custom element
            on every host (rf2-j81hs), never a view, so it carries no
            annotation even when a view of the same id is registered."
    (rf/reg-view* :coord-demo/card {} (fn [_] [:div.card "x"]))
    (let [html (ssr/render-to-string [:coord-demo/card :revenue] {})]
      (is (= "<card>revenue</card>" html))
      (is (not (both-annotations? html))))))

(deftest plain-hiccup-not-annotated
  (testing "unchanged — ordinary tags were never annotated"
    (is (not (both-annotations? (ssr/render-to-string [:div [:span "x"]] {}))))))

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
    (testing "debug ON at registration — annotated (the load-bearing arm)"
      (rf/reg-view* :ssr-coord-test/gated-dev {} (fn [] [:h2 "g"]))
      (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/gated-dev)] {})]
        (is (str/starts-with? html "<h2 "))
        (is (both-annotations? html))))

    (testing "debug OFF at registration — NOT annotated"
      (with-redefs [interop/debug-enabled? false]
        (rf/reg-view* :ssr-coord-test/gated-prod {} (fn [] [:h2 "g"])))
      ;; render with debug back at its default — the wrap decision was made
      ;; at registration, so the markup is unannotated regardless.
      (let [html (ssr/render-to-string [(rf/view :ssr-coord-test/gated-prod)] {})]
        (is (= "<h2>g</h2>" html))
        (is (not (both-annotations? html)))))))

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
