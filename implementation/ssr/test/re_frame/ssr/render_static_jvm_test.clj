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

;; rf2-zgezz #3 — a view with a PROGRAMMER-AUTHORED nested attribute spelled like
;; the host-root annotation. render-static must strip only the compiler-generated
;; host-root markers, preserving this authored nested value.
(ui/defview nested-authored-card
  [_]
  [:div.card [:span {:data-rf-view "authored-nested"} "x"]])

;; ---------------------------------------------------------------------------
;; Fixtures for the no-silent-elision proof (rf2-uv7n6 repair 1). All compiled
;; top-to-bottom so their view-static facts are indexed when render-static below
;; expands against them.
;; ---------------------------------------------------------------------------

;; A DIRECTLY interactive view — a committed event handler in its body is a
;; live-runtime capability a pure :server static render cannot honour.
(ui/defview interactive-card
  [{:keys [label]}]
  [:button {:on-click [:clicked]} label])

;; A static parent whose ONLY child is the interactive view — the capability is
;; NESTED, so it is only visible through the transitive closure.
(ui/defview static-parent
  [_]
  [interactive-card {:label "x"}])

;; A live (interactive) leaf used only inside a client-only island below.
(ui/defview live-widget
  [_]
  [:button {:on-click [:go]} "live"])

;; A static view carrying a client-only island: its browser-only subtree is the
;; interactive `live-widget`, guarded by a capability-free fallback. render-static
;; renders ONLY the fallback (a static root never flips), so the island is legal.
(ui/defview island-card
  [_]
  [:div [:h2 "static"] (ui/client-only {:fallback [:span "loading"]} [live-widget])])

;; A dedicated static root for the Layer-1 registration proof (repair 2) — kept
;; distinct from static-card so its derived root-id is exercised by exactly one
;; test and cannot collide with the sibling render-static suites.
(ui/defview dup-probe
  [_]
  [:main "probe"])

(defn- caught-id
  "Run `f`; -> the thrown compile-error id (`:rf.ui.compile/error` ex-data),
  or ::no-throw. `macroexpand-1` wraps a macro-expansion ExceptionInfo in a
  CompilerException (phase :macro-syntax-check), so unwrap `.getCause` — the
  same shape the error-roster suite's `expand-ex` handles; a direct
  `render-static-form` call throws the ExceptionInfo unwrapped."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (:rf.ui.compile/error (ex-data e)))
       (catch Exception e
         (let [c (.getCause e)]
           (when (instance? clojure.lang.ExceptionInfo c)
             (:rf.ui.compile/error (ex-data c)))))))

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
                      ;; The compiler emits the DEV host-root view-evidence
                      ;; annotation into the structural tree (rf2-hac8p, gated on
                      ;; interop/debug-enabled?), but render-static is the pure,
                      ;; non-hydrating static path — `emit-static-html` strips it
                      ;; before serialisation, so the static markup is free of
                      ;; every rf annotation too
                      "data-rf2-source-coord" "data-rf-view"]]
        (is (not (str/includes? html marker))
            (str "render-static output must not carry the hydration/adoption "
                 "marker " (pr-str marker) " — it is the non-hydrating static path"))))))

;; ---------------------------------------------------------------------------
;; Provenance — the strip removes ONLY the compiler-generated host-root markers,
;; preserving programmer-authored attributes of the same spelling (rf2-zgezz #3).
;; ---------------------------------------------------------------------------

(deftest render-static-preserves-authored-nested-attrs
  (testing "an authored nested data-rf-view survives the strip; the host-root
            compiler annotation is still removed"
    (let [html (ui/render-static [nested-authored-card])]
      (is (str/includes? html "data-rf-view=\"authored-nested\"")
          "RED before provenance — the recursive strip deleted this authored attr")
      (is (not (str/includes?
                html "data-rf-view=\":re-frame.ssr.render-static-jvm-test/nested-authored-card\""))
          "the compiler's host-root view id is still stripped (pure static path)")
      (is (not (str/includes? html "data-rf2-source-coord=\""))
          "no compiler source-coord leaks — only the authored nested marker remains"))))

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
  (testing "render-static's HTML equals emit-ui-tree over the same versioned tree,
            minus the DEV host-root annotation render-static strips as the pure
            non-hydrating static path (it reuses the ssr static-emit path via
            emit-static-html, builds no new renderer — rf2-hac8p)"
    (is (= (str/replace
            (ssr/emit-ui-tree
             (ui-tree/render-root-tree
              (fn [] (static-card {:title "Hello"}))))
            #"\s+data-rf(?:2-source-coord|-view)=\"[^\"]*\"" "")
           (ui/render-static [static-card {:title "Hello"}])))))

;; ===========================================================================
;; rf2-uv7n6 REPAIR 1 — no-silent-elision proof (Spec 004C §3, EP-0034 §2).
;;
;; RED before: `(ui/render-static [interactive-card {:label "x"}])` expanded and
;; returned "<button>x</button>" — the `:on-click [:clicked]` handler was
;; SILENTLY elided; `[static-parent]` did the same through a nested view.
;; GREEN after: any runtime-requiring capability in the static root's
;; server-reachable view closure is a loud `:rf.ui.compile/static-root-requires-runtime`
;; BUILD error. `ui/client-only` (capability-free fallback) + static views stay legal.
;; ===========================================================================

;; A clojure.test RUN binds *ns* to the runner, not the test ns, so a
;; `macroexpand-1` of a render-static form with an internal-VIEW head cannot
;; resolve the head. Drive the REAL macro body (root/render-static-form, the
;; shipped JVM path) with *ns* rebound to THIS namespace so the view heads
;; resolve — exactly what the compiler does when the file compiles in-ns.
(defn- expand-render-static
  "Expand render-static's macro body against THIS namespace; returns the emitted
  form or throws the compile-error ExceptionInfo (the real JVM render-static path)."
  [root-form]
  (binding [*ns* (find-ns 're-frame.ssr.render-static-jvm-test)]
    (root/render-static-form nil nil root-form)))

(defn- render-static-error-id [root-form]
  (try (expand-render-static root-form) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.ui.compile/error (ex-data e)))))

(deftest render-static-rejects-a-directly-interactive-view
  (testing "a committed event handler in the mounted view's own body is a loud
            build error, never a silently dropped capability (EP-0034 §2)"
    (is (= :rf.ui.compile/static-root-requires-runtime
           (render-static-error-id '[interactive-card {:label "x"}])))))

(deftest render-static-rejects-a-nested-interactive-view
  (testing "the capability proof is TRANSITIVE — a static parent whose only child
            is an interactive view fails through the direct-view-dependency closure
            (checking the root view alone would silently ship inert UI)"
    (is (= :rf.ui.compile/static-root-requires-runtime
           (render-static-error-id '[static-parent])))))

(deftest render-static-names-the-offending-capability-and-view
  (testing "the build error names the runtime capability and the offending view,
            so the author can find and move the live subtree"
    (let [ex  (try (expand-render-static '[static-parent])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
          msg (some-> ex ex-message)]
      (is (some? msg))
      (is (str/includes? msg "handler") "names the runtime-requiring capability")
      (is (str/includes? msg "interactive-card") "names the offending nested view"))))

(deftest render-static-allows-a-static-and-client-only-island
  (testing "a purely-static root renders (no capability in the closure)"
    (is (= "<div class=\"card\"><h2>Hi</h2><p>static</p></div>"
           (ui/render-static [static-card {:title "Hi"}]))))
  (testing "a ui/client-only island with a capability-free fallback stays legal —
            render-static renders ONLY the fallback, the browser-only interactive
            subtree never reaches the JVM tree (a static root never flips)"
    (let [html (ui/render-static [island-card])]
      (is (= "<div><h2>static</h2><span>loading</span></div>" html))
      (is (not (str/includes? html "button"))
          "the client-only interactive subtree is NOT rendered into the static output"))))

;; ===========================================================================
;; rf2-uv7n6 REPAIR 2 — Layer-1 identity registration (Spec 004C §7).
;;
;; RED before: `resolve-root-identity` ran but its result was discarded;
;; render-static never called `register-root-site!`, so its root was absent from
;; the build-tier duplicate-root-id index (unlike mount / render! / hydrate-root).
;; GREEN after: the derived identity is retained and registered.
;; ===========================================================================

(deftest render-static-registers-its-root-site
  (testing "render-static registers its DERIVED root-id into the Layer-1 build
            index (§7) — the identity resolve-root-identity produced is retained,
            not discarded"
    (ui/render-static [dup-probe])
    (let [rid   :re-frame.ssr.render-static-jvm-test/dup-probe
          roots (root/build-roots)]
      (is (contains? roots rid)
          "the render-static root site lands in the shared build-roots index")
      (is (= :derived (:provenance (get roots rid)))
          "the site is recorded with its derived provenance")))
  (testing "a SECOND site in a DIFFERENT namespace resolving to the same root-id
            is the build-tier :rf.error/duplicate-root-id — proving render-static
            participates in the same shared Layer-1 index mount / render! use"
    (let [ex (try (root/register-root-site!
                   'ui/render-static
                   :re-frame.ssr.render-static-jvm-test/dup-probe :derived
                   'some.other.server.render-ns {:file "other.clj" :line 1})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/duplicate-root-id (:rf.error/id (ex-data ex)))))))

;; ===========================================================================
;; rf2-uv7n6 REPAIR 3 — typed missing-SSR-artefact error (not ClassNotFoundException).
;;
;; RED before: the macro emitted a bare `re-frame.ssr/emit-ui-tree` call, so a
;; documented render-static call in a namespace that required only `re-frame.ui`
;; failed to COMPILE with a raw `ClassNotFoundException: re-frame.ssr`.
;; GREEN after: the macro emits a call to the ui-side late-resolution seam
;; (`re-frame.ui.tree/emit-static-html`) — the caller's namespace carries no
;; compile-time `re-frame.ssr` reference, and an absent artefact is the ruled,
;; typed `:rf.error/ssr-artefact-missing`.
;; ===========================================================================

(deftest render-static-emits-no-compile-time-ssr-reference
  (testing "the expansion folds the tree through re-frame.ui.tree/emit-static-html
            (a ui-side seam that late-resolves the emitter), NOT a bare
            re-frame.ssr/emit-ui-tree reference — so a caller requiring only
            re-frame.ui compiles rather than raising ClassNotFoundException: re-frame.ssr"
    (let [expansion (expand-render-static '[static-card {:title "x"}])
          head      (first expansion)]
      (is (= 're-frame.ui.tree/emit-static-html head)
          "render-static emits a call to the ui-side late-resolution seam")
      (is (not (str/includes? (pr-str expansion) "re-frame.ssr"))
          "the emitted code names no re-frame.ssr symbol (no compile-time ui->ssr reference)"))))

(deftest render-static-missing-ssr-artefact-is-a-typed-error
  (testing "when the day8/re-frame2-ssr artefact is absent (the emitter cannot be
            resolved), render-static raises the ruled typed
            :rf.error/ssr-artefact-missing naming the artefact — never a raw host
            exception, never a fallback"
    (let [ex (try (with-redefs [ui-tree/resolve-ssr-emitter (constantly nil)]
                    (ui/render-static [static-card {:title "x"}]))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/ssr-artefact-missing (:rf.error/id (ex-data ex))))
      (is (str/includes? (ex-message ex) "day8/re-frame2-ssr")
          "the typed error names the missing artefact coordinate")))
  (testing "with the artefact present (the normal ssr test classpath) the same
            call renders — the late resolution loads and folds through emit-ui-tree"
    (is (= "<div class=\"card\"><h2>x</h2><p>static</p></div>"
           (ui/render-static [static-card {:title "x"}])))))
