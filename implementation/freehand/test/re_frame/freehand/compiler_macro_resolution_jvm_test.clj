(ns re-frame.freehand.compiler-macro-resolution-jvm-test
  "Real CLJS-analyzer resolution proofs for the expression macro barrier."
  (:require [cljs.analyzer :as cljs-analyzer]
            [cljs.analyzer.api :as cljs-api]
            [cljs.compiler :as cljs-comp]
            [cljs.core]
            [cljs.env :as cljs-env]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.compiler.analyze :as analyze]
            [re-frame.freehand.compiler.emit-cljs :as emit-cljs]
            [re-frame.freehand.compiler.emit-jvm :as emit-jvm]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.compiler.header :as header]
            [re-frame.freehand.compiler.root :as root]))

(defmacro user-binder
  [[binding init] then else]
  `(let [~binding ~init] (if ~binding ~then ~else)))

(defn- compile-error-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (:rf.ui.compile/error (ex-data e)))))

(defn- with-real-cljs-env [f]
  (binding [cljs-env/*compiler* (cljs-env/default-compiler-env)]
    ;; A real CLJS compile interns core/user macros into analyzer state. Build
    ;; that exact authority rather than injecting a synthetic :macro flag.
    (cljs-analyzer/intern-macros 'cljs.core)
    (cljs-analyzer/intern-macros
     're-frame.freehand.compiler-macro-resolution-jvm-test)
    (let [cljs-e (cljs-api/empty-env)]
      (cljs-api/analyze cljs-e '(def ordinary-call (fn [x] x)))
      (let [base (env/make-env {:host :cljs :cljs-env cljs-e
                                :ns-sym 'cljs.user})
            resolve* (fn [sym]
                       (case sym
                         sub   {:fqn 're-frame.freehand/sub :meta {}}
                         (env/resolve-sym base sym)))
            e (env/make-env {:host :cljs :cljs-env cljs-e
                             :ns-sym 'cljs.user
                             :self 'probe :self-id :cljs.user/probe
                             :resolver resolve*})]
        (f base e)))))

(deftest real-cljs-analyzer-macro-authority-is-preserved
  (with-real-cljs-env
    (fn [base _]
      (testing "core and user binder macros carry the analyzer's top-level flag"
        (is (true? (get-in (env/resolve-sym base 'if-let) [:meta :macro])))
        (is (true?
             (get-in
              (env/resolve-sym
               base
               're-frame.freehand.compiler-macro-resolution-jvm-test/user-binder)
              [:meta :macro]))))
      (testing "a real analyzed ordinary function is not over-classified"
        (is (not (true? (get-in (env/resolve-sym base 'ordinary-call)
                                [:meta :macro]))))))))

(deftest real-cljs-binder-macros-fail-while-transparent-expressions-lower
  (with-real-cljs-env
    (fn [_ e]
      ;; A USER binder macro that expands to the SAME let + if is still opaque —
      ;; only the four core forms if-let/when-let/if-some/when-some are admitted
      ;; (rf2-u53yy.4), by RESOLVED core identity (the host resolver confirms the
      ;; var), never by raw spelling or "looks like a binder". A bare threading
      ;; step below `->` also stays rejected.
      (doseq [form
              ['[:div {:title
                       (re-frame.freehand.compiler-macro-resolution-jvm-test/user-binder
                        [x maybe] (sub [:q x]) nil)}]
               '[:div {:title (-> [:q] sub)}]]]
        (is (= :rf.ui.compile/unsupported-form
               (compile-error-id #(analyze/analyze e form)))
            (pr-str form)))
      ;; The admitted core if-let desugars into the analyzer's own let + if, so
      ;; the (sub …) in a branch lowers to its indexed manifest site.
      (let [e*  (assoc e :sites (atom {:events [] :subs [] :htmls []}))
            ast (analyze/analyze e* '[:div {:title (if-let [x maybe] (sub [:q x]) nil)}])]
        (is (= 1 (count (:subs @(:sites e*))))
            "the admitted if-let lowers the branch (sub …) to one manifest site")
        (is (re-find #"re-frame.freehand.reactive/sub-read" (pr-str ast))
            "the lowered runtime site is present in the desugared let + if"))
      ;; rf2-u53yy.4 audit repair — under REAL CLJS resolution (:host :cljs) the
      ;; some?-variant's generated nil test is the HOST-QUALIFIED core `not=`
      ;; (`cljs.core/not=`), un-shadowable by a user local AND a plain function
      ;; (cljs.core `some?`/`nil?` are MACROS the grammar would reject); the branch
      ;; is `if`, never a generated `when`. Both keep core semantics under a shadow
      ;; and both survive this real-resolution re-analysis without rejection.
      (let [ast (analyze/analyze e '[:div {:title (if-some [x maybe] x "none")}])]
        (is (re-find #"cljs\.core/not=" (pr-str ast))
            "if-some tests the host-qualified plain-function cljs.core/not= (un-shadowable, re-analysis-safe)")
        (is (not (re-find #"\(when " (pr-str ast)))
            "no generated `when` — the branch is the special form `if`"))
      (is (= :rf.ui.compile/unsupported-form
             (compile-error-id
              #(analyze/analyze
                e '[:div {:title
                           (re-frame.freehand.compiler-macro-resolution-jvm-test/user-binder)}])))
          "an opaque zero-arg invocation cannot inject an invisible site")
      (is (= :rf.ui.compile/unsupported-form
             (compile-error-id
              #(analyze/reject-reactive-binding!
                e '[{:keys [x] :or {x (-> [:q] sub)}}])))
          "real CLJS macro resolution fences a manufactured default call")
      (doseq [form
              ['[:div {:title (ordinary-call (sub [:q]))}]
               '[:div {:title (or (sub [:q]) "")}]
               '[:div {:title (when (sub [:q]) "ready")}]
               '[:div {:title (cond (sub [:q]) "ready" :else "waiting")}]
               '[:div {:title (-> (sub [:q]) ordinary-call)}]]]
        (let [e*  (assoc e :sites (atom {:events [] :subs [] :htmls []}))
              ast (analyze/analyze e* form)]
          (is (= 1 (count (:subs @(:sites e*)))) (pr-str form))
          (is (re-find #"re-frame.freehand.reactive/sub-read" (pr-str ast))
              (pr-str form)))))))

(deftest real-cljs-destructuring-scope-follows-host-evaluation-order
  ;; rf2-vxgfnd.268 — the ordered-scope correction under REAL CLJS analyzer
  ;; resolution (bare sub resolves to the reactive var; core macro
  ;; authority is genuine, not an injected :macro flag). Destructuring binds
  ;; SEQUENTIALLY, so a bare reactive var in a default is shadowed ONLY by a
  ;; same-pattern local bound EARLIER. Reverting the ordered scope re-accepts
  ;; the escape rows (the dzyqis over-accept) and re-rejects the earlier-shadow
  ;; rows (the older whole-pattern-blind call scan's under-shadow).
  (with-real-cljs-env
    (fn [_ e]
      (testing "a self / later-bound shadow does not cover an earlier default"
        (doseq [argv ['[{:keys [sub] :or {sub sub}}]
                      '[{:keys [f sub] :or {f sub}}]]]
          (is (= :rf.ui.compile/unsupported-form
                 (compile-error-id #(analyze/reject-reactive-binding! e argv)))
              (pr-str argv))))
      (testing "an EARLIER-bound local genuinely shadows the reactive var"
        (doseq [argv ['[{:keys [sub f] :or {f sub}}]
                      '[{:keys [sub f] :or {f (sub :fallback)}}]
                      '[{sub :s x sub}]]]
          (is (nil? (compile-error-id #(analyze/reject-reactive-binding! e argv)))
              (pr-str argv)))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.274 — self-head precedence + real CLJS authoring-head routing
;; ---------------------------------------------------------------------------
;;
;; The .266 pure-analyzer fixtures inject Q5 resolution, and its real-host proof
;; (defview_grammar_jvm_test) covers CLJ macroexpansion + fully-qualified
;; spellings only. Neither exercised production `cljs.analyzer.api/resolve` with
;; REFERRED / ALIASED spellings, nor the separate CLJS root-entry compiler. This
;; harness stands up a REAL CLJS analyzer namespace state where cljs.user refers
;; re-frame.freehand/{sub,frame} (and aliases the ns `v`), so every spelling
;; resolves through the production resolver — NOT an injected stub — and drives
;; both the defview route (analyze) and the root-entry route (root/analyze-root).

(defn- with-referred-cljs-env
  "Populate a real CLJS compiler state where cljs.user REFERS re-frame.freehand/sub,
  frame (+ frame-root/frame-provider) and aliases the ns as `v`, then
  call `(f aenv)` with a live analyzer env over that ns. Resolution runs through
  production `cljs.analyzer.api/resolve` — there is no injected `:resolver`."
  [f]
  (binding [cljs-env/*compiler* (cljs-env/default-compiler-env)]
    (swap! cljs-env/*compiler* update :cljs.analyzer/namespaces merge
           {'re-frame.freehand  {:name 're-frame.freehand
                           :defs {'sub            {:name 're-frame.freehand/sub}
                                  'frame          {:name 're-frame.freehand/frame}
                                  'frame-root     {:name 're-frame.freehand/frame-root}
                                  'frame-provider {:name 're-frame.freehand/frame-provider}}}
            'cljs.user    {:name 'cljs.user
                           ;; `(:require [re-frame.freehand :as v :refer [...]])`
                           :requires {'v 're-frame.freehand 're-frame.freehand 're-frame.freehand}
                           :uses     {'sub 're-frame.freehand
                                      'frame 're-frame.freehand 'frame-root 're-frame.freehand
                                      'frame-provider 're-frame.freehand}
                           :defs {}}})
    (f (assoc (cljs-api/empty-env)
              :ns (get-in @cljs-env/*compiler*
                          [:cljs.analyzer/namespaces 'cljs.user])))))

(defn- referred-env
  "An analyzer env over the referred cljs.user; `self-sym` (or nil) is the view
  currently being compiled."
  [aenv self-sym]
  (cond-> (env/make-env {:host :cljs :cljs-env aenv :ns-sym 'cljs.user})
    self-sym (assoc :self self-sym
                    :self-id (keyword "cljs.user" (name self-sym))
                    :self-children? false :self-closed-keys nil)))

(deftest real-cljs-referred-verb-resolution-is-genuine
  ;; Sanity: production cljs.analyzer.api/resolve — not an injected resolver —
  ;; resolves every referred / aliased / fully-qualified spelling to the
  ;; re-frame.freehand authoring vars. The rows below route through THIS resolution.
  (with-referred-cljs-env
    (fn [aenv]
      (let [base (referred-env aenv nil)]
        (doseq [[sym fqn] '{sub               re-frame.freehand/sub
                            v/sub            re-frame.freehand/sub
                            re-frame.freehand/sub   re-frame.freehand/sub
                            frame             re-frame.freehand/frame
                            re-frame.freehand/frame re-frame.freehand/frame}]
          (is (= fqn (:fqn (env/resolve-sym base sym))) (str sym)))
        (is (nil? (env/resolve-sym base 'not-a-thing))
            "an unresolvable spelling is genuinely unresolved (no stub)")))))

(deftest real-cljs-self-head-outranks-reservation-defview-route
  ;; rf2-vxgfnd.274 defview route under REAL CLJS resolution. The REFERRED
  ;; spelling is the self-recursive case (a view named `sub` recursing on a bare
  ;; [sub …] head) — it classifies as an internal view. The ALIASED and
  ;; FULLY-QUALIFIED spellings in an UNRELATED view (no :self) stay reserved
  ;; typed rejects, never foreign components. A local shadow outranks self.
  (with-referred-cljs-env
    (fn [aenv]
      (testing "a referred self head classifies as an internal view"
        (doseq [verb '[sub frame]]
          (let [e   (referred-env aenv verb)
                ast (analyze/analyze e [verb {}])]
            (is (= :view (:op ast)) (str verb))
            (is (= (keyword "cljs.user" (name verb)) (:view-id ast)) (str verb)))))
      (testing "aliased / fully-qualified / referred verbs in an unrelated view stay reserved"
        (let [e (referred-env aenv nil)]
          (doseq [form '[[v/sub {}] [frame]
                         [v/frame {}] [re-frame.freehand/sub {}]]]
            (is (= :rf.ui.compile/unsupported-form
                   (compile-error-id #(analyze/analyze e form)))
                (pr-str form)))))
      (testing "a local shadow of the self spelling is a dynamic head (tier 1 wins)"
        (let [e (referred-env aenv 'sub)]
          (is (= :rf.ui.compile/dynamic-head
                 (compile-error-id #(analyze/analyze e '(let [sub identity] [sub {}])))))))
      (testing "the reservation stays narrow — a direct (sub …) still indexes one site"
        (let [e (referred-env aenv 'panel)]
          (analyze/analyze e '[:div (sub [:q])])
          (is (= 1 (count (:subs @(:sites e))))
              "a direct compiler-owned (sub …) call still lowers to a manifest site"))))))

(deftest real-cljs-root-entry-reserves-reactive-verbs
  ;; rf2-vxgfnd.274 root-entry route. The mount/render!/hydrate-root compiler
  ;; (root/analyze-root) REUSES analyze/analyze, so the reservation holds at the
  ;; root too: a public authoring verb head at root can never become a :foreign
  ;; component. Bypassing the canonical-FQN reservation makes each [verb …] root
  ;; compile :foreign and fail these rows (the root-compilation mutation
  ;; fixture); a legal element root is untouched. (Roots carry no :self, so the
  ;; self-precedence tier never applies here — only the reservation does.)
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [form '[[sub {}] [re-frame.freehand/frame] [frame]]]
        (is (= :rf.ui.compile/unsupported-form
               (compile-error-id #(root/analyze-root (referred-env aenv nil) 'v/mount form)))
            (pr-str form)))
      (testing "a legal element root still analyzes through the root compiler"
        (is (= :element
               (:op (:ast (root/analyze-root (referred-env aenv nil) 'v/mount '[:div "x"])))))))))

;; ---------------------------------------------------------------------------
;; rf2-rr26cq — emit a recursive self head against the current-namespace Var
;; ---------------------------------------------------------------------------
;;
;; The .274 analyzer proofs above stop at classification (the self head is an
;; internal :view). But the CLJS emitter splits the render fn (`<v>$render`)
;; from the view's own `(def <v> …)`, so the render fn FORWARD-references the
;; view before its def. A self head emitted as the raw authored spelling
;; therefore resolves through the same-named `:refer` — cljs.analyzer/resolve-var
;; checks `:uses` (refers) BEFORE `:defs` — and captures the public authoring
;; Var (`re-frame.freehand/sub`) in the emitted JavaScript. The fix carries the
;; canonical `:fqn` (the current-namespace Var) for the self component node and
;; forward-`declare`s it. These rows drive the REAL emitters and compile the
;; emitted self head to REAL JavaScript through cljs.compiler.

(defn- self-emit-args
  "Package emit args for `(defview <verb> [] [<verb> {}])` exactly as
  `compiler/defview**` does — the self view named `verb` recurses on a bare
  `[verb {}]` self head in the referred cljs.user namespace."
  [aenv verb]
  (let [e   (referred-env aenv verb)
        ast (analyze/analyze e [verb {}])]
    {:vname verb
     :self-fqn (symbol "cljs.user" (name verb))
     :view-id (keyword "cljs.user" (name verb))
     :display-name (str "cljs.user/" (name verb))
     :docstring nil
     :header (header/parse-header [])
     :slots []
     :ast ast
     :manifest {:view-id (keyword "cljs.user" (name verb)) :sites {} :children? false}
     :closed-keys nil
     :children? false}))

(defn- jsx-tag-syms
  "The symbol component heads the CLJS emitter placed in jsx-runtime `js*`
  calls (`(0,<rt>.jsx)(<tag>,<props>)`); the tag is the 3rd js* argument after
  the template and the runtime alias."
  [form]
  (let [hits (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x) (= 'js* (first x)) (string? (second x))
                  (str/includes? (second x) ".jsx"))
         (let [tag (nth x 3 nil)]
           (when (symbol? tag) (swap! hits conj tag))))
       x)
     form)
    @hits))

(defn- call-head-syms
  "Every seq head symbol whose NAME matches `verb` — on the JVM emit form the
  only such head is the self component call `(cljs.user/<verb> {…})`."
  [form verb]
  (let [hits (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x) (symbol? (first x)) (= (name (first x)) (name verb)))
         (swap! hits conj (first x)))
       x)
     form)
    @hits))

(defn- emit-var-js
  "Real cljs.compiler JavaScript for a var-reference symbol in cljs.user
  (warnings silenced — the point is the munged target, not the diagnostic)."
  [aenv sym]
  (binding [cljs-analyzer/*cljs-warnings*
            (zipmap (keys cljs-analyzer/*cljs-warnings*) (repeat false))]
    (cljs-comp/emit-str
     (cljs-analyzer/analyze (assoc aenv :context :expr) sym))))

(deftest real-cljs-self-head-emits-against-current-namespace-var
  ;; rf2-rr26cq accept rows. Reverting the emitter to the raw authored `:sym`
  ;; re-captures `re-frame.freehand/<verb>` in the emitted JavaScript and drops the
  ;; forward declaration, failing every row below.
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [verb '[sub frame]]
        (let [self-fqn  (symbol "cljs.user" (name verb))
              args      (self-emit-args aenv verb)
              cljs-form (emit-cljs/emit-defview args)
              jvm-form  (emit-jvm/emit-defview args)
              cljs-heads (jsx-tag-syms cljs-form)]
          (testing (str "CLJS: the self head is the current-namespace fqn (" verb ")")
            (is (seq cljs-heads) "a self component call is emitted")
            (is (every? #(= self-fqn %) cljs-heads)
                (str "every self head is cljs.user/" verb ", never the bare refer"))
            (is (not-any? #(= verb %) cljs-heads)
                "the raw authored spelling never reaches a jsx head"))
          (testing (str "CLJS: the view forward-declares its own Var (" verb ")")
            (is (some #(and (seq? %) (= 'clojure.core/declare (first %))
                            (= verb (second %)))
                      cljs-form)
                "a (declare <verb>) precedes the render fn")
            (is (some #(and (seq? %) (= 'def (first %)) (= verb (second %)))
                      cljs-form)
                "the def still targets the bare current-namespace name"))
          (testing (str "CLJS: the emitted self head compiles to cljs.user." (name verb))
            (doseq [h cljs-heads]
              (let [js (emit-var-js aenv h)]
                (is (= (str "cljs.user." (name verb)) js)
                    (str "self head " h " munges to the current-namespace Var"))
                (is (not (str/includes? js (str "re_frame.freehand." (name verb))))
                    "zero authoring-Var reference in the emitted JavaScript"))))
          (testing (str "JVM: the self call targets the current-namespace fqn (" verb ")")
            (let [jvm-heads (call-head-syms jvm-form verb)]
              (is (seq jvm-heads) "a self component call is emitted")
              (is (every? #(= self-fqn %) jvm-heads)
                  (str "every JVM self call head is cljs.user/" verb)))))))))

(deftest a-bare-authored-self-head-would-capture-the-authoring-var
  ;; The counterfactual the fix defeats: were the emitter to keep the raw
  ;; authored spelling, that bare head compiles to the REFERRED authoring Var —
  ;; the exact defect rf2-rr26cq closes. This pins the JavaScript delta so a
  ;; regression is legible, not silent.
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [verb '[sub frame]]
        (is (= (str "re_frame.freehand." (name verb)) (emit-var-js aenv verb))
            (str "a bare " verb " head resolves through the refer to the authoring Var"))
        (is (= (str "cljs.user." (name verb))
               (emit-var-js aenv (symbol "cljs.user" (name verb))))
            (str "the qualified " verb " head resolves to the current-namespace Var"))))))

;; ---------------------------------------------------------------------------
;; rf2-eukmp — the WHOLE production do-form, not isolated symbols
;; ---------------------------------------------------------------------------
;;
;; PR #6053 fixed the recursive JSX HEAD (self-fqn) but left the view's own
;; declare/def on the raw bare name. The focused rows above compile EXTRACTED
;; reference symbols in isolation and check the raw def is bare — a FALSE GREEN:
;; a bare `(def sub …)` in a ns referring re-frame.freehand/sub resolves the def NAME
;; through the same-named `:refer` (cljs.analyzer/resolve-var ranks `:uses` above
;; `:defs` and IGNORES `:excludes`), so the WHOLE form both clobbers the public
;; authoring Var (`re_frame.freehand.sub = …`) and leaves the qualified self head
;; (`cljs.user.sub`) undefined — the split identity. The row below analyzes AND
;; compiles the ENTIRE emitted `(declare/defn/def)` do-form as one unit through
;; real cljs.analyzer + cljs.compiler. Against pre-fix output every assertion is
;; RED (the def targets re-frame.freehand/<verb>, the JS assigns re_frame.freehand.<verb>).

(defn- analyze-whole-form
  "Analyze the COMPLETE production do-form in the referred cljs.user ns (warnings
  silenced) and compile it to JS as ONE unit — never extracted symbols in
  isolation. `emit-defview` has already run for `do-form`, so any same-named
  refer shadowing it completes is live in the analyzer state this reads."
  [aenv do-form]
  (binding [cljs-analyzer/*cljs-warnings*
            (zipmap (keys cljs-analyzer/*cljs-warnings*) (repeat false))]
    (let [ast       (cljs-analyzer/analyze (assoc aenv :context :statement) do-form)
          def-names (atom [])]
      (walk/postwalk
       (fn [x]
         (when (and (map? x) (= :def (:op x))) (swap! def-names conj (:name x)))
         x)
       ast)
      {:def-names @def-names :js (cljs-comp/emit-str ast)})))

(defn- munged-ref?
  "True iff `js` references the exact munged Var `pre.<verb>` at an identifier
  boundary (so `re_frame.freehand.frame` does not spuriously match
  `re_frame.freehand.frames`, nor `cljs.user.sub` match `cljs.user.sub$render`)."
  [js pre verb]
  (boolean (re-find (re-pattern (str (java.util.regex.Pattern/quote (str pre "." (name verb)))
                                     "(?![A-Za-z0-9_$])"))
                    js)))

(deftest real-cljs-recursive-defview-whole-form-defines-current-ns-var
  ;; rf2-eukmp acceptance. Reverting the emitter's canonical-Var alignment
  ;; (leaving the def/declare to resolve through the refer) fails every row.
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [verb '[sub frame]]
        (let [self-fqn      (symbol "cljs.user" (name verb))
              authoring-fqn (symbol "re-frame.freehand" (name verb))
              args          (self-emit-args aenv verb)
              do-form       (emit-cljs/emit-defview args)
              {:keys [def-names js]} (analyze-whole-form aenv do-form)]
          (testing (str "whole form: the view def defines the current-ns Var (" verb ")")
            (is (some #(= self-fqn %) def-names)
                (str "a def in the whole form targets cljs.user/" verb))
            (is (not-any? #(= authoring-fqn %) def-names)
                (str "no def targets the authoring Var re-frame.freehand/" verb)))
          (testing (str "whole form: emitted JS assigns the current-ns Var, never the authoring Var (" verb ")")
            (is (str/includes? js (str "cljs.user." (name verb) " ="))
                (str "the emitted JS assigns cljs.user." (name verb)))
            (is (not (str/includes? js (str "re_frame.freehand." (name verb) " =")))
                (str "the emitted JS never assigns (clobbers) re_frame.freehand." (name verb))))
          (testing (str "whole form: def + recursive head share ONE current-ns Var, authoring Var untouched (" verb ")")
            (is (munged-ref? js "cljs.user" verb)
                (str "the recursive head references cljs.user." (name verb)))
            (is (not (munged-ref? js "re_frame.freehand" verb))
                (str "zero re_frame.freehand." (name verb)
                     " reference anywhere in the whole emitted form"))))))))

(deftest a-non-recursive-defview-emits-no-self-declaration
  ;; Preserve unrelated behavior: a view that does NOT reference itself emits no
  ;; forward declaration and no fqn rewrite — the self-head machinery is inert.
  (with-referred-cljs-env
    (fn [aenv]
      (let [e    (referred-env aenv 'panel)
            ast  (analyze/analyze e [:div "x"])
            args {:vname 'panel :self-fqn 'cljs.user/panel :view-id :cljs.user/panel
                  :display-name "cljs.user/panel" :docstring nil
                  :header (header/parse-header []) :slots []
                  :ast ast
                  :manifest {:view-id :cljs.user/panel :sites {} :children? false}
                  :closed-keys nil :children? false}
            form (emit-cljs/emit-defview args)]
        (is (not-any? #(and (seq? %) (= 'clojure.core/declare (first %))) form)
            "a non-recursive view emits no (declare …)")))))
