(ns re-frame.freehand.render-static-jvm-test
  "`v/render-static` — the Freehand server render path (rf2-drpa3.112; Spec 004C
  §3, Spec 011 §The server render on the Freehand paved path). It compiles the
  LITERAL root form to the versioned JVM structural tree and folds it to an INERT
  HTML string, NON-hydrating — no Root Manifest, no hydration payload, no phase
  flip.

  The suite lives in the Freehand artefact (its natural home; render-static IS
  the JVM structural render entry, the browser mounts with v/mount /
  v/hydrate-root) with `day8/re-frame2-ssr` as a TEST-ONLY dep: render-static's
  emitted code folds the JVM tree to HTML through `re-frame.ssr/emit-ui-tree`
  reached by LATE resolution (`re-frame.freehand.tree/emit-static-html`), so
  `re-frame.freehand` never statically requires `re-frame.ssr` (Spec 011 §wall —
  the Independence rule), and exercising the end-to-end render needs both
  artefacts on one classpath, which this alias provides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.root :as root]
            [re-frame.freehand.node :as node]
            [re-frame.freehand.tree :as tree]
            [re-frame.ssr :as ssr]))

;; Compiled views (pure functions of props — no runtime capability, so no frame
;; is needed for the static render). Compiled top-to-bottom, so each view's
;; view-static facts are indexed (and its var + descriptor exist) when the
;; render-static forms below expand against them.
(v/defview static-card
  {:compiled true}
  [{:keys [title]}]
  [:div.card [:h2 title] [:p "static"]])

;; A DIRECTLY state-reading view — `v/sub` is a live-runtime read a pure :server
;; static render cannot honour. The bead's "a view that reads state" adversary.
(v/defview reads-state
  {:compiled true}
  [_]
  [:output (v/sub [:basket/total])])

;; A DIRECTLY interactive view — a committed event handler in its body is a
;; live-runtime capability too.
(v/defview interactive-card
  {:compiled true}
  [{:keys [label]}]
  [:button {:on-click [:clicked]} label])

;; A static parent whose ONLY child is the interactive view — the capability is
;; NESTED, so it is visible only through the transitive closure.
(v/defview static-parent
  {:compiled true}
  [_]
  [interactive-card {:label "x"}])

(defn- caught-id
  "Run `f`; -> the thrown compile-error id (`:rf.ui.compile/error` ex-data),
  or ::no-throw. `macroexpand-1` wraps a macro-expansion ExceptionInfo in a
  CompilerException (phase :macro-syntax-check), so unwrap `.getCause`; a direct
  `render-static-form` call throws the ExceptionInfo unwrapped."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (:rf.ui.compile/error (ex-data e)))
       (catch Exception e
         (let [c (.getCause e)]
           (when (instance? clojure.lang.ExceptionInfo c)
             (:rf.ui.compile/error (ex-data c)))))))

;; A clojure.test RUN binds *ns* to the runner, not this ns, so a
;; `macroexpand-1` of a render-static form with an internal-VIEW head cannot
;; resolve the head. Drive the REAL macro body (root/render-static-form, the
;; shipped JVM path) with *ns* rebound to THIS namespace so the view heads
;; resolve — exactly what the compiler does when the file compiles in-ns.
(defn- expand-render-static
  "Expand render-static's macro body against THIS namespace; returns the emitted
  form or throws the compile-error ExceptionInfo (the real JVM render-static path)."
  [root-form]
  (binding [*ns* (find-ns 're-frame.freehand.render-static-jvm-test)]
    (root/render-static-form nil nil root-form)))

(defn- render-static-error-id [root-form]
  (try (expand-render-static root-form) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.ui.compile/error (ex-data e)))))

(defn- capture-render-static
  "Drive render-static's macro body; -> `{:form <expansion>}` on success, or
  `{:id <compile-error-id> :msg <message>}` on a compile-error throw."
  [root-form]
  (try {:form (expand-render-static root-form)}
       (catch clojure.lang.ExceptionInfo e
         {:id (:rf.ui.compile/error (ex-data e)) :msg (ex-message e)})))

;; The no-silent-elision proof and the emitted-seam shape are captured HERE, at
;; NS-LOAD, immediately after the views above compiled — while the ambient
;; build's `view-static` index still carries their freshly-contributed facts.
;; This is exactly how production render-static runs: it expands at COMPILE time,
;; in the same build the mounted views compiled in (the positive `v/render-static`
;; literals in the deftests below likewise expand at ns-load). A clojure.test RUN
;; can clear that per-build index before the deftests execute, so re-driving the
;; macro body at run time would see an empty index and falsely report every view
;; UNPROVEN — a test-harness artefact, never the compile-time contract.
(def ^:private reads-state-outcome   (capture-render-static '[reads-state {}]))
(def ^:private static-parent-outcome (capture-render-static '[static-parent]))
(def ^:private static-card-expansion (expand-render-static '[static-card {:title "x"}]))

;; ---------------------------------------------------------------------------
;; Static render correctness — a compiled root -> the expected inert HTML.
;; ---------------------------------------------------------------------------

(deftest render-static-emits-the-expected-inert-html
  (testing "a compiled root renders to the pure :server HTML string (props applied,
            view boundary erased)"
    (is (= "<div class=\"card\"><h2>Hello</h2><p>static</p></div>"
           (v/render-static [static-card {:title "Hello"}]))))
  (testing "the render is a pure function of the literal props — a different props
            value renders different HTML"
    (is (= "<div class=\"card\"><h2>Bye</h2><p>static</p></div>"
           (v/render-static [static-card {:title "Bye"}])))))

;; ---------------------------------------------------------------------------
;; Non-hydrating — no manifest / payload / adoption / dev-annotation markers.
;; ---------------------------------------------------------------------------

(deftest render-static-output-is-non-hydrating
  (testing "the static-page path emits NO hydration machinery and NO rf annotation
            (Spec 011 §The server render on the Freehand paved path)"
    (let [html (v/render-static [static-card {:title "x"}])]
      (doseq [marker ["data-rf-root" "data-rf-manifest" "__rf_payload"
                      "data-rf-render-hash" "rf.root/schema-version" "rf.ssr"
                      "data-rf2-source-coord" "data-rf-view"]]
        (is (not (str/includes? html marker))
            (str "render-static output must not carry the marker " (pr-str marker)
                 " — it is the non-hydrating static path"))))))

;; ---------------------------------------------------------------------------
;; Literal-root enforcement — a macro sees the literal form; a fn cannot. The
;; SAME compile error mount / hydrate-root raise (Spec 004C §3).
;; ---------------------------------------------------------------------------

(deftest render-static-rejects-a-runtime-assembled-root
  (testing "a NON-literal root form (a runtime symbol, not a literal vector) is a
            COMPILE-time rejection at macroexpansion — the literal-root enforcement
            a fn cannot provide"
    (is (= :rf.ui.compile/runtime-root-form
           (caught-id #(macroexpand-1 '(re-frame.freehand/render-static a-runtime-root)))))))

(deftest render-static-requires-a-single-mounted-view
  (testing "identity participation (§7): with no opts the root-id derives from
            exactly one mounted view — a bare-DOM root (no view) is rejected"
    (is (= :rf.ui.compile/no-single-mounted-view
           (render-static-error-id '[:div "no view"])))))

;; ---------------------------------------------------------------------------
;; JVM/server only — a CLJS expansion is a compile error (no structural trees in
;; the browser). Driven directly on the macro body with a CLJS `&env` (`:ns`
;; present), since macroexpand-1 on the JVM carries no :ns.
;; ---------------------------------------------------------------------------

(deftest render-static-rejects-a-cljs-expansion
  (testing "expanding render-static in a CLJS build is a loud compile error"
    (is (= :rf.ui.compile/ui-render-static-jvm-only
           (caught-id #(root/render-static-form
                        nil {:ns {:name 'app.browser}} '[static-card {:title "x"}]))))))

;; ---------------------------------------------------------------------------
;; No silent elision (Spec 004C §3, EP-0034 §2) — a runtime-requiring capability
;; in the server-reachable closure is a loud BUILD error, never a dropped one.
;; ---------------------------------------------------------------------------

(deftest render-static-rejects-a-view-that-reads-state
  (testing "a `v/sub` (a live-runtime read) in the mounted view's own body is a loud
            build error, never a silently inert render"
    (is (= :rf.ui.compile/static-root-requires-runtime (:id reads-state-outcome))))
  (testing "the build error names the runtime-requiring subscription capability"
    (is (str/includes? (str (:msg reads-state-outcome)) "sub"))))

(deftest render-static-rejects-a-nested-interactive-view
  (testing "the capability proof is TRANSITIVE — a static parent whose only child
            is an interactive view fails through the direct-view-dependency closure
            (checking the root view alone would silently ship inert UI)"
    (is (= :rf.ui.compile/static-root-requires-runtime (:id static-parent-outcome)))))

;; ---------------------------------------------------------------------------
;; The independence wall — the emitted code names NO compile-time re-frame.ssr
;; reference; it folds through the ui-side late-resolution seam.
;; ---------------------------------------------------------------------------

(deftest render-static-emits-no-compile-time-ssr-reference
  (testing "the expansion folds the tree through re-frame.freehand.tree/emit-static-html
            (a door-side seam that late-resolves the emitter), NOT a bare
            re-frame.ssr/emit-ui-tree reference — so a caller requiring only
            re-frame.freehand compiles rather than raising ClassNotFoundException"
    (is (= 're-frame.freehand.tree/emit-static-html (first static-card-expansion))
        "render-static emits a call to the door-side late-resolution seam")
    (is (not (str/includes? (pr-str static-card-expansion) "re-frame.ssr"))
        "the emitted code names no re-frame.ssr symbol (no compile-time freehand->ssr edge)")))

;; ---------------------------------------------------------------------------
;; The typed missing-SSR-artefact error (not a raw host exception).
;; ---------------------------------------------------------------------------

(deftest render-static-missing-ssr-artefact-is-a-typed-error
  (testing "when the day8/re-frame2-ssr artefact is absent (the emitter cannot be
            resolved), render-static raises the ruled typed :rf.error/ssr-artefact-missing
            naming the artefact — never a raw host exception, never a fallback"
    (let [ex (try (with-redefs [tree/resolve-ssr-emitter (constantly nil)]
                    (v/render-static [static-card {:title "x"}]))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/ssr-artefact-missing (:rf.error/id (ex-data ex))))
      (is (str/includes? (ex-message ex) "day8/re-frame2-ssr")
          "the typed error names the missing artefact coordinate")))
  (testing "with the artefact present (the normal test classpath) the same call
            renders — the late resolution loads and folds through emit-ui-tree"
    (is (= "<div class=\"card\"><h2>x</h2><p>static</p></div>"
           (v/render-static [static-card {:title "x"}])))))

;; ---------------------------------------------------------------------------
;; End-to-end shape — render-static's HTML equals emit-ui-tree over the same
;; versioned tree render-root-tree produces (it builds no new renderer).
;; ---------------------------------------------------------------------------

(deftest render-static-composes-emit-ui-tree
  (testing "render-static's HTML equals emit-ui-tree over the same versioned tree
            render-root-tree stamps — the seam reuses the pure SSR serialiser and
            builds no new renderer (a view descriptor is mounted via node/mount,
            never called directly)"
    (is (= (ssr/emit-ui-tree
            (tree/render-root-tree
             (fn [] (node/mount static-card [{:title "Hello"}]))))
           (v/render-static [static-card {:title "Hello"}])))))

;; ---------------------------------------------------------------------------
;; Surface pin — render-static resolves as a MACRO on re-frame.freehand.
;; ---------------------------------------------------------------------------

(deftest render-static-is-a-freehand-macro
  (let [v (ns-resolve 're-frame.freehand 'render-static)]
    (is (some? v) "re-frame.freehand/render-static must resolve")
    (is (:macro (meta v)) "render-static is a MACRO — it enforces the literal root form")))
