(ns re-frame.ssr.render-static-jvm-test
  "`re-frame.ui/render-static` — the pure :server static-HTML render (rf2-oo5lb;
  Spec 004C §3, Spec 011 §Phase flip). The S5 keystone: render-static is the
  outstanding blessed re-frame.ui public that the S5 conformance gate holds
  fail-closed on. It renders a compiled root to an INERT HTML string, NON-
  hydrating — no manifest, no hydration payload, no phase flip.

  The proof home lives in the `re-frame.ssr` artefact because render-static's
  emitted code folds the JVM structural tree to HTML with
  `re-frame.ssr/emit-ui-tree`: `re-frame.ui` never statically requires
  `re-frame.ssr` (Spec 011 §wall — the Independence rule), so exercising the
  end-to-end render needs BOTH artefacts on one classpath, which the ssr test
  classpath (ui as a test dep) provides. JVM-only — render-static IS the JVM
  structural render entry (the browser mounts with ui/mount / ui/hydrate-root)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui]
            [re-frame.ui.compiler.root :as root]
            [re-frame.ui.tree :as ui-tree]
            [re-frame.ssr :as ssr]))

;; A compiled view (pure function of props — no subs, so no frame is needed for
;; the static render). Compiled top-to-bottom, so its var + `:rf.ui/view`
;; metadata exist when the render-static macro below expands against it.
(ui/defview static-card
  [{:keys [title]}]
  [:div.card [:h2 title] [:p "static"]])

(defn- caught-id
  "Run `f`; -> the thrown compile-error id (`:rf.ui.compile/error` ex-data),
  or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (:rf.ui.compile/error (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Static render correctness — a compiled root -> the expected inert HTML.
;; ---------------------------------------------------------------------------

(deftest render-static-emits-the-expected-inert-html
  (testing "a compiled root renders to the pure :server HTML string (props applied,
            view boundary erased, adjacent-safe)"
    (is (= "<div class=\"card\"><h2>Hello</h2><p>static</p></div>"
           (ui/render-static [static-card {:title "Hello"}]))))
  (testing "the render is a pure function of the literal props — a different
            props value renders different HTML"
    (is (= "<div class=\"card\"><h2>Bye</h2><p>static</p></div>"
           (ui/render-static [static-card {:title "Bye"}])))))

;; ---------------------------------------------------------------------------
;; Non-hydrating — no manifest / payload / hydration-adoption markers, no flip.
;; ---------------------------------------------------------------------------

(deftest render-static-output-is-non-hydrating
  (testing "the static-page path emits NO hydration machinery — no root manifest,
            no hydration payload, no adoption/render-hash markers (Spec 011 §Phase flip)"
    (let [html (ui/render-static [static-card {:title "x"}])]
      (doseq [marker ["data-rf-root" "data-rf-manifest" "__rf_payload"
                      "data-rf-render-hash" "rf.root/schema-version" "rf.ssr"
                      ;; compiled views carry no dev source-coord annotation on
                      ;; the JVM (register-view! does not wrap), so the static
                      ;; markup is free of every rf annotation too
                      "data-rf2-source-coord" "data-rf-view"]]
        (is (not (str/includes? html marker))
            (str "render-static output must not carry the hydration/adoption "
                 "marker " (pr-str marker) " — it is the non-hydrating static path"))))))

;; ---------------------------------------------------------------------------
;; Literal-root enforcement — a macro sees the literal form; a fn cannot. The
;; SAME compile error mount / render! / hydrate-root raise (Spec 004C §3).
;; ---------------------------------------------------------------------------

(deftest render-static-rejects-a-runtime-assembled-root
  (testing "a NON-literal root form (a runtime symbol, not a literal vector) is a
            COMPILE-time rejection at macroexpansion — the literal-root enforcement
            a fn cannot provide (the ruled fn->macro delta)"
    (is (= :rf.ui.compile/runtime-root-form
           (caught-id #(macroexpand-1 '(re-frame.ui/render-static a-runtime-root))))))
  (testing "the rejection id is the SAME one mount / render! / hydrate-root raise"
    ;; ui/render! rejects the identical way (root/analyze-root is shared).
    (is (= (caught-id #(macroexpand-1 '(re-frame.ui/render-static some-vec)))
           :rf.ui.compile/runtime-root-form))))

(deftest render-static-requires-a-single-mounted-view
  (testing "identity participation (§7): with no opts the root-id derives from
            exactly one mounted view — a bare-DOM root (no view) is rejected"
    (is (= :rf.ui.compile/no-single-mounted-view
           (caught-id #(macroexpand-1 '(re-frame.ui/render-static [:div "no view"])))))))

;; ---------------------------------------------------------------------------
;; JVM/server only — a CLJS expansion is a compile error (no structural trees in
;; the browser; a CLJS expansion would emit the JVM tree + SSR serialiser into a
;; browser bundle — a ui->ssr edge). Driven directly on the macro body with a
;; CLJS `&env` (`:ns` present), since macroexpand-1 on the JVM carries no :ns.
;; ---------------------------------------------------------------------------

(deftest render-static-rejects-a-cljs-expansion
  (testing "expanding render-static in a CLJS build is a loud compile error"
    (is (= :rf.ui.compile/ui-render-static-jvm-only
           (caught-id #(root/render-static-form
                        nil {:ns {:name 'app.browser}} '[static-card {:title "x"}]))))))

;; ---------------------------------------------------------------------------
;; Surface pin — render-static resolves as a MACRO on re-frame.ui (the fail-closed
;; S5 merge-precondition, and the fn->macro freeze delta).
;; ---------------------------------------------------------------------------

(deftest render-static-is-a-blessed-re-frame-ui-macro
  (let [v (ns-resolve 're-frame.ui 'render-static)]
    (is (some? v) "re-frame.ui/render-static must resolve (the S5 merge precondition)")
    (is (:macro (meta v)) "render-static is a MACRO — it enforces the literal root form")))

;; ---------------------------------------------------------------------------
;; End-to-end shape — the emitted code routes through the pure emit-ui-tree
;; serialiser, so the same tree emit-ui-tree produces is what render-static returns.
;; ---------------------------------------------------------------------------

(deftest render-static-composes-emit-ui-tree
  (testing "render-static's HTML equals emit-ui-tree over the same versioned tree
            (it reuses the ssr static-emit path, builds no new renderer)"
    (is (= (ssr/emit-ui-tree
            (ui-tree/render-root-tree
             (fn [] (static-card {:title "Hello"}))))
           (ui/render-static [static-card {:title "Hello"}])))))
