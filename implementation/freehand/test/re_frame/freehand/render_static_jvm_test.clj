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
            [re-frame.freehand.behavior-views :as bv]
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

;; A compiled view attaching a server-reachable v/behavior — a live host
;; lifecycle (connect / command / disconnect over a real node) a pure :server
;; render owns no node for. rf2-drpa3.116's adversary: before the capability
;; walks learned the behavior marker, this folded to inert HTML that silently
;; dropped the attachment. `bv/probe` is the conformance corpus's registered
;; behavior, reused rather than re-declared.
(v/defview static-behavior-card
  {:compiled true}
  [_]
  [:section [v/behavior {:use bv/probe :target :static/probe} [:div.node "x"]]])

;; A compiled parent whose only child is the behavior-bearing view — the
;; behavior capability is NESTED, visible only through the transitive closure.
(v/defview static-behavior-parent
  {:compiled true}
  [_]
  [static-behavior-card {}])

;; A view whose ONLY behavior lives in a v/client-only CLIENT arm — the JVM
;; render produces only the capability-free fallback, so the behavior is never
;; server-reachable and render-static must RENDER, not refuse. The negative
;; control for the server-reachability rule (rf2-drpa3.116). Interpreted, because
;; v/client-only is a browser-only subtree the compiled grammar does not admit —
;; the interpreted render is where a client-only arm is legitimately dropped, so
;; it is exactly where the "renders, does not refuse" control belongs.
(v/defview client-only-behavior-card
  [_]
  [:section
   (v/client-only {:fallback [:div.fallback "server"]}
     [v/behavior {:use bv/probe :target :static/probe} [:div.node "x"]])])

;; ---------------------------------------------------------------------------
;; The PAVED PATH (rf2-drpa3.159) — the default `v/defview`, with no options map
;; at all. An interpreted declaration has no analysis, no finite grammar and no
;; manifest, so there are no build-time facts about it and there never can be.
;; Its half of no-silent-elision is proved by the RENDER instead.
;; ---------------------------------------------------------------------------

;; The capability-free paved-path view. `plain-card` and `static-card` above are
;; the SAME body under the two spellings, which is what makes the attributed
;; result assertable: promotion is a one-line change, so it must not be a
;; rendering change.
(v/defview plain-card
  [{:keys [title]}]
  [:div.card [:h2 title] [:p "static"]])

;; An interpreted view carrying a COMMITTED HANDLER — the capability the HTML
;; fold drops silently (the payload that would reinstall it is never emitted).
(v/defview plain-interactive-card
  [{:keys [label]}]
  [:button {:on-click [:clicked]} label])

;; An interpreted view reading state DIRECTLY.
(v/defview plain-reads-state
  [_]
  [:output (v/sub [:basket/total])])

;; An interpreted parent whose only child is the interactive interpreted view —
;; the capability is NESTED, and the render sees it through the built tree.
(v/defview plain-parent
  [_]
  [:section [plain-interactive-card {:label "x"}]])

;; An INTERPRETED view attaching a v/behavior — an interpreted body carries no
;; analysis, so this half of no-silent-elision is proved at RENDER: the fold
;; refuses to drop the live host boundary the interpreted tree actually carries.
(v/defview plain-behavior-card
  [_]
  [:section [v/behavior {:use bv/probe :target :static/probe} [:div.node "x"]]])

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

;; The compiled behavior rejections are a BUILD-time verdict (static-capability-
;; offender over the server-reachable {:caps :deps}), so they are captured at
;; ns-load like reads-state / static-parent, while the index still carries the
;; freshly-contributed facts (rf2-drpa3.116).
(def ^:private behavior-card-outcome   (capture-render-static '[static-behavior-card {}]))
(def ^:private behavior-parent-outcome (capture-render-static '[static-behavior-parent {}]))

;; The ambient build's `view-static` index AS IT STOOD at ns-load, for the same
;; reason: a clojure.test RUN can clear the per-build index before the deftests
;; execute, and the drift guard below compares the two publications of ONE
;; projection, which only exist together at compile time.
(def ^:private ambient-view-static (compiler/build-view-static))

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

(deftest render-static-rejects-a-compiled-behavior
  (testing "rf2-drpa3.116: a compiled `v/behavior` attachment is a live host
            lifecycle a pure :server render cannot honour — its server-reachable
            :behavior capability is a loud BUILD error, never an inert marker
            silently dropped from a non-hydrating page"
    (is (= :rf.ui.compile/static-root-requires-runtime (:id behavior-card-outcome))))
  (testing "the build error names the runtime-requiring behavior capability"
    (is (str/includes? (str (:msg behavior-card-outcome)) "behavior"))))

(deftest render-static-rejects-a-nested-compiled-behavior
  (testing "the behavior capability proof is TRANSITIVE too — a compiled parent
            whose only child is a behavior-bearing view fails through the
            direct-view-dependency closure, exactly as a nested subscription or
            handler does (rf2-drpa3.116)"
    (is (= :rf.ui.compile/static-root-requires-runtime (:id behavior-parent-outcome)))))

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
;; The PAVED PATH (rf2-drpa3.159) — the headline verb serves the headline
;; authoring path. A capability-free INTERPRETED declaration renders, with no
;; promotion and no second caller spelling.
;; ---------------------------------------------------------------------------

(deftest render-static-renders-the-interpreted-paved-path
  (testing "a capability-free `v/defview` with NO options map renders to the
            expected inert HTML through the public macro — the ordinary paved
            path, not a compiled-only island"
    (is (= "<div class=\"card\"><h2>Hello</h2><p>static</p></div>"
           (v/render-static [plain-card {:title "Hello"}]))))
  (testing "the render is a pure function of the literal props"
    (is (= "<div class=\"card\"><h2>Bye</h2><p>static</p></div>"
           (v/render-static [plain-card {:title "Bye"}]))))
  (testing "promotion is a one-line change, so it is not a rendering change: the
            interpreted declaration and its `{:compiled true}` twin — the same
            body under the two spellings — render the SAME HTML"
    (is (= (v/render-static [static-card {:title "Hello"}])
           (v/render-static [plain-card {:title "Hello"}]))))
  (testing "the paved-path output is non-hydrating exactly as the compiled one is"
    (let [html (v/render-static [plain-card {:title "x"}])]
      (doseq [marker ["data-rf-root" "data-rf-manifest" "__rf_payload"
                      "data-rf-render-hash" "data-rf-view"]]
        (is (not (str/includes? html marker)))))))

(deftest render-static-transitively-admits-interpreted-dependencies
  (testing "an interpreted dependency is ADMITTED at BUILD time — it carries no
            analysis to prove, so the macro no longer reports the ordinary paved
            path UNPROVEN and no longer tells the author to recompile a view that
            was never stale"
    (is (nil? (:id (capture-render-static '[plain-card {:title "x"}])))
        "the paved-path expansion raises no compile error")
    (is (some? (:form (capture-render-static '[plain-card {:title "x"}])))
        "the paved-path expansion emits the render")))

;; ---------------------------------------------------------------------------
;; No silent elision, the RENDER-time arm — an interpreted body's capabilities
;; become visible in the tree its render produced, and the fold refuses to drop
;; one. Loud, attributed, and never a control that silently does nothing.
;; ---------------------------------------------------------------------------

(defn- static-render-error
  "Render `thunk`; -> the thrown `:rf.error/id` + message, or ::no-throw."
  [thunk]
  (try (thunk) {:id ::no-throw}
       (catch clojure.lang.ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

(deftest render-static-rejects-an-interpreted-committed-handler
  (let [{:keys [id msg data]}
        (static-render-error #(v/render-static [plain-interactive-card {:label "x"}]))]
    (testing "a committed handler in an INTERPRETED body is a loud typed failure —
              render-static emits no hydration payload, so a folded-away handler
              would ship a control that does nothing"
      (is (= :rf.error/static-render-requires-runtime id)))
    (testing "the diagnostic names the handler slot, the element and the declaration
              the author has to change"
      (is (str/includes? msg ":on-click"))
      (is (str/includes? msg ":button"))
      (is (str/includes? msg (str ::plain-interactive-card))))
    (testing "the recovery is the build-time arm's — it is the same law"
      (is (= :mount-in-the-browser-or-move-the-live-subtree-behind-client-only
             (:recovery data)))
      (is (str/includes? msg "v/client-only")))))

(deftest render-static-rejects-a-nested-interpreted-handler
  (testing "the render-time proof reaches the whole tree, not the root view — an
            interpreted parent whose only child is interactive fails, attributed
            to the CHILD declaration that carries the handler"
    (let [{:keys [id msg]} (static-render-error #(v/render-static [plain-parent {}]))]
      (is (= :rf.error/static-render-requires-runtime id))
      (is (str/includes? msg (str ::plain-interactive-card))
          "attribution is the nearest enclosing view boundary, not the root"))))

(deftest render-static-rejects-an-interpreted-reactive-read
  (testing "a `v/sub` in an INTERPRETED body fails on its own account: render-static
            opens no declared render, so the read has no owner and is refused
            before it probes anything — never silently rendered as an empty slot"
    (is (= :rf.error/view-read-outside-render
           (:id (static-render-error #(v/render-static [plain-reads-state {}])))))))

(deftest render-static-rejects-an-interpreted-behavior
  (let [{:keys [id msg data]}
        (static-render-error #(v/render-static [plain-behavior-card {}]))]
    (testing "rf2-drpa3.116: a v/behavior in an INTERPRETED body is proved at
              RENDER — the interpreted tree carries the live host boundary and the
              fold refuses to drop it, the same law the compiled arm proves at
              build. So render-static, interpreted browser and compiled browser
              agree: the behavior is honoured or the page fails loud, never
              silently inert"
      (is (= :rf.error/static-render-requires-runtime id)))
    (testing "the diagnostic names the behavior capability and the shared recovery"
      (is (str/includes? (str msg) "behavior"))
      (is (= :mount-in-the-browser-or-move-the-live-subtree-behind-client-only
             (:recovery data)))
      (is (str/includes? (str msg) "v/client-only")))))

(deftest render-static-renders-a-behavior-only-in-a-client-only-arm
  (testing "the NEGATIVE control (rf2-drpa3.116): a behavior reachable ONLY in a
            v/client-only client arm is NOT server-reachable — the JVM emitter
            renders the capability-free fallback, so render-static admits the root
            and folds it to HTML rather than refusing. Server reachability is what
            separates a dropped live capability from a legitimately client-only one"
    (let [html (v/render-static [client-only-behavior-card {}])]
      (is (string? html) "the client-only behavior root renders, it does not refuse")
      (is (str/includes? html "server")
          "the capability-free fallback is what reaches the static output")
      (is (not (str/includes? html "node"))
          "and the client arm's behavior node never reaches the :server render"))))

;; ---------------------------------------------------------------------------
;; Cross-build / AOT resolution — the ambient index is a per-build convenience;
;; the DECLARED manifest is the durable carrier a precompiled view arrives with.
;; ---------------------------------------------------------------------------

(deftest compiled-static-facts-ride-the-declared-manifest
  (testing "a compiled declaration's manifest carries the render-static
            `{:caps :deps}` projection"
    (is (= {:caps #{} :deps #{}} (:static-facts (v/manifest static-card))))
    (is (= #{:handler} (:caps (:static-facts (v/manifest interactive-card))))))
  (testing "the manifest key and the ambient index entry are ONE projection
            published twice, so they cannot drift"
    (doseq [[view vid] [[static-card ::static-card]
                        [interactive-card ::interactive-card]
                        [static-parent ::static-parent]]]
      (let [indexed (get ambient-view-static vid)]
        (is (some? indexed) (str vid " — the ambient index carries the facts"))
        (is (= indexed (:static-facts (v/manifest view)))
            (str vid " — manifest and index publish the same value"))))))

(deftest render-static-proves-a-precompiled-view-with-an-empty-build-index
  (testing "with THIS build's view-static index empty — a view compiled in another
            build, an AOT artefact, a precompiled jar — a capability-free compiled
            dependency still proves safe, from its declared manifest"
    (with-redefs [compiler/build-view-static (constantly {})]
      (is (some? (:form (capture-render-static '[static-card {:title "x"}]))))))
  (testing "and an INTERACTIVE precompiled dependency still fails loud, with the
            capability error — an empty index is never proof of static safety"
    (with-redefs [compiler/build-view-static (constantly {})]
      (is (= :rf.ui.compile/static-root-requires-runtime
             (:id (capture-render-static '[interactive-card {:label "x"}]))))
      (is (= :rf.ui.compile/static-root-requires-runtime
             (:id (capture-render-static '[static-parent])))
          "the transitive closure resolves through declared manifests too"))))

;; ---------------------------------------------------------------------------
;; The UNPROVEN diagnostic tells the truth about WHICH route failed — the two
;; have different recoveries, and the wrong one sends an author to recompile a
;; view that is not stale.
;; ---------------------------------------------------------------------------

(deftest unproven-dependency-diagnostics-name-the-route-that-failed
  (testing "a COMPILED dependency whose manifest carries no :static-facts is a
            genuinely stale AOT artefact — the recompile recovery is the true one"
    (with-redefs [compiler/build-view-static        (constantly {})
                  root/registered-view-static-facts (constantly nil)]
      (let [{:keys [id msg]} (capture-render-static '[static-card {:title "x"}])]
        (is (= :rf.ui.compile/static-root-unproven-dependency id))
        (is (str/includes? msg "Recompile"))
        (is (str/includes? msg "COMPILED")))))
  (testing "an id naming no reachable declaration gets the REQUIRE recovery, not
            the recompile one — nothing is stale, the declaration is absent"
    (with-redefs [compiler/build-view-static (constantly {})
                  root/declared-view         (constantly nil)]
      (let [{:keys [id msg]} (capture-render-static '[static-card {:title "x"}])]
        (is (= :rf.ui.compile/static-root-unproven-dependency id))
        (is (str/includes? msg "no reachable v/defview declaration"))
        (is (not (str/includes? msg "Recompile")))))))

;; ---------------------------------------------------------------------------
;; Surface pin — render-static resolves as a MACRO on re-frame.freehand.
;; ---------------------------------------------------------------------------

(deftest render-static-is-a-freehand-macro
  (let [v (ns-resolve 're-frame.freehand 'render-static)]
    (is (some? v) "re-frame.freehand/render-static must resolve")
    (is (:macro (meta v)) "render-static is a MACRO — it enforces the literal root form")))
