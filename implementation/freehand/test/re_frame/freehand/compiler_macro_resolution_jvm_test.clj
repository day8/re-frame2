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
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.analyze :as analyze]
            [re-frame.freehand.compiler.emit-jvm :as emit-jvm]
            [re-frame.freehand.compiler.emit-react :as emit-react]
            [re-frame.freehand.compiler.env :as env]
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
;; re-frame.freehand/{sub,slot} (and aliases the ns `v`), so every spelling
;; resolves through the production resolver — NOT an injected stub — and drives
;; both the defview route (analyze) and the root-entry route (root/analyze-root).

(defn- with-referred-cljs-env
  "Populate a real CLJS compiler state where cljs.user REFERS
  re-frame.freehand/sub and /slot and aliases the ns as `v`, then
  call `(f aenv)` with a live analyzer env over that ns. Resolution runs through
  production `cljs.analyzer.api/resolve` — there is no injected `:resolver`."
  [f]
  (binding [cljs-env/*compiler* (cljs-env/default-compiler-env)]
    (swap! cljs-env/*compiler* update :cljs.analyzer/namespaces merge
           {'re-frame.freehand  {:name 're-frame.freehand
                           :defs {'sub  {:name 're-frame.freehand/sub}
                                  'slot {:name 're-frame.freehand/slot}}}
            'cljs.user    {:name 'cljs.user
                           ;; `(:require [re-frame.freehand :as v :refer [sub slot]])`
                           :requires {'v 're-frame.freehand 're-frame.freehand 're-frame.freehand}
                           :uses     {'sub 're-frame.freehand 'slot 're-frame.freehand}
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
                            re-frame.freehand/sub   re-frame.freehand/sub}]
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
        (doseq [verb '[sub]]
          (let [e   (referred-env aenv verb)
                ast (analyze/analyze e [verb {}])]
            (is (= :view (:op ast)) (str verb))
            (is (= (keyword "cljs.user" (name verb)) (:view-id ast)) (str verb)))))
      (testing "aliased / fully-qualified / referred verbs in an unrelated view stay reserved"
        (let [e (referred-env aenv nil)]
          (doseq [form '[[v/sub {}] [re-frame.freehand/sub {}]]]
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
      (doseq [form '[[sub {}] [re-frame.freehand/sub {}]]]
        (is (= :rf.ui.compile/unsupported-form
               (compile-error-id #(root/analyze-root (referred-env aenv nil) 'v/mount form)))
            (pr-str form)))
      (testing "a legal element root still analyzes through the root compiler"
        (is (= :element
               (:op (:ast (root/analyze-root (referred-env aenv nil) 'v/mount '[:div "x"])))))))))

;; ---------------------------------------------------------------------------
;; self-Var: a defview DEFINES its current-namespace Var, and a recursive head
;; REFERENCES it
;; ---------------------------------------------------------------------------
;;
;; The .274 analyzer proofs above stop at classification (the self head is an
;; internal :view). This block drives the REAL declaration door and the REAL
;; emitters, and compiles the result to REAL JavaScript through cljs.compiler.
;;
;; Two halves, and they were fixed in two changes. (1) Both live emitters
;; emitted the raw authored `:sym` for a self boundary, so in a ns that REFERS
;; re-frame.freehand/{sub,frame} a compiled `(defview sub [] [sub {}])` MOUNTED
;; the authoring verb instead of recursing on itself. (2) The `(def …)` had the
;; same split identity, and it did NOT depend on recursion: the shadow was
;; completed by the React emitter and only when the body happened to mention its
;; own name, so a NON-recursive `(defview sub …)` still compiled its definition
;; against the authoring Var and left `cljs.user.sub` undefined (rf2-rr26cq,
;; #6886 audit). The shadow now lands at the DECLARATION boundary, for every
;; declaration in both lowerings.
;;
;; These rows diff the emitted JS bytes; the counterfactual below pins the delta
;; so the discriminator is a compiled fact, not a reasoned AST shape.

(def ^:private probe-file "app/probe.cljc")
(def ^:private probe-meta {:line 12 :column 3})

(defn- declaration-form
  "The REAL `v/defview` expansion of `(v/defview <verb> {:compiled true} [_]
  <body>)` in the referred cljs.user namespace — the PRODUCTION declaration
  door, `re-frame.freehand/expand-defview`, which is what the `defview` macro
  calls and where the same-named-refer shadow is completed. Answers the `(def
  …)` form the macro would have returned."
  [aenv verb body]
  (v/expand-defview nil aenv probe-meta probe-file 'cljs.user verb
                    (list {:compiled true} '[_] body)))

(defn- interpreted-declaration-form
  "The same production door with NO `{:compiled true}` — the paved path. It
  emits the same `(def …)`, so it has the same Var to define and the same
  same-named refer standing in front of it."
  [aenv verb body]
  (v/expand-defview nil aenv probe-meta probe-file 'cljs.user verb
                    (list '[_] body)))

(defn- self-react-body
  "The React realisation of `(defview <verb> [] [<verb> {}])` in the referred
  cljs.user namespace — the self view named `verb` recurses on a bare `[verb {}]`
  self head."
  [aenv verb]
  (let [e   (referred-env aenv verb)
        ast (analyze/analyze e [verb {}])]
    (emit-react/emit-react-body e [] ast)))

(defn- self-jvm-body
  "The JVM structural realisation of the same self view."
  [aenv verb]
  (let [e   (referred-env aenv verb)
        ast (analyze/analyze e [verb {}])]
    (emit-jvm/emit-structural-body e [] ast)))

(defn- mount-heads
  "Every head symbol handed to a `…/mount` call in `form` — the structural
  `node/mount` and the compiled `compiled-react/mount` both name the self
  component call's target as their first argument."
  [form]
  (let [hits (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x) (symbol? (first x))
                  (str/ends-with? (name (first x)) "mount"))
         (swap! hits conj (second x)))
       x)
     form)
    @hits))

(defn- compile-js
  "Real cljs.compiler JavaScript for `form` analyzed in cljs.user (warnings
  silenced — the point is the munged target, not the diagnostic)."
  [aenv form]
  (binding [cljs-analyzer/*cljs-warnings*
            (zipmap (keys cljs-analyzer/*cljs-warnings*) (repeat false))]
    (cljs-comp/emit-str
     (cljs-analyzer/analyze (assoc aenv :context :expr) form))))

(deftest real-cljs-self-head-emits-and-defines-current-namespace-var
  ;; Accept rows, driven through the PRODUCTION declaration door. Reverting either
  ;; emitter to the raw authored `:sym` re-captures re_frame.freehand.<verb> in the
  ;; emitted JavaScript; dropping the declaration's `:uses` shadow completion leaves
  ;; the `(def …)` clobbering the authoring Var and the qualified head undefined
  ;; (the split identity). Every row below fails then.
  (doseq [verb '[sub slot]]
    (let [self-fqn  (symbol "cljs.user" (name verb))
          author-re (re-pattern (str "re_frame\\.freehand\\." (name verb) "\\b"))
          def-re    (re-pattern (str "cljs\\.user\\." (name verb) "\\s*="))
          head-re   (re-pattern (str "mount\\(cljs\\.user\\." (name verb) ","))]
      (testing (str "CLJS: (v/defview " verb " …) DEFINES and recurses on the current-ns Var")
        (with-referred-cljs-env
          (fn [aenv]
            (let [js (compile-js aenv (declaration-form aenv verb [verb {}]))]
              (is (re-find def-re js)
                  "the def defines the current-namespace Var")
              (is (re-find head-re js)
                  "the recursive mount head references the current-namespace Var")
              (is (not (re-find author-re js))
                  "zero authoring-Var reference — not clobbered, not captured")))))
      (testing (str "CLJS: the emitted self mount head is the current-ns fqn (" verb ")")
        (with-referred-cljs-env
          (fn [aenv]
            (let [heads (mount-heads (self-react-body aenv verb))]
              (is (seq heads) "a self component call is emitted")
              (is (every? #(= self-fqn %) heads)
                  "every self head is cljs.user/<verb>, never the bare refer")
              (is (not-any? #(= verb %) heads)
                  "the raw authored spelling never reaches the mount head")))))
      (testing (str "JVM: the structural self call targets the current-ns fqn (" verb ")")
        (with-referred-cljs-env
          (fn [aenv]
            (let [heads (mount-heads (self-jvm-body aenv verb))]
              (is (seq heads) "a self component call is emitted")
              (is (every? #(= self-fqn %) heads)
                  "every JVM self call head is cljs.user/<verb>")
              (is (not-any? #(= verb %) heads)
                  "the raw authored spelling never reaches the JVM head"))))))))

(deftest a-bare-authored-self-head-would-capture-the-authoring-var
  ;; The counterfactual the fix defeats: a bare authored head compiles to the
  ;; REFERRED authoring Var — the exact defect. Pins the JS delta so a regression
  ;; is legible, not silent.
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [verb '[sub slot]]
        (is (= (str "re_frame.freehand." (name verb)) (compile-js aenv verb))
            "a bare head resolves through the refer to the authoring Var")
        (is (= (str "cljs.user." (name verb))
               (compile-js aenv (symbol "cljs.user" (name verb))))
            "the qualified head resolves to the current-namespace Var")))))

(deftest a-non-recursive-colliding-declaration-still-defines-the-current-ns-var
  ;; The #6886 audit row, and the one the shipped fix originally missed. A
  ;; `(v/defview sub …)` whose body never mentions `sub` is STILL a declaration
  ;; of `sub` in cljs.user: whether the body happens to recurse cannot decide
  ;; which Var the definition lands in. Before the repair this compiled its
  ;; `(def …)` against the referred authoring Var — clobbering
  ;; re_frame.freehand.sub and leaving cljs.user.sub undefined.
  (with-referred-cljs-env
    (fn [aenv]
      (let [js (compile-js aenv (declaration-form aenv 'sub [:div "x"]))]
        (is (re-find #"cljs\.user\.sub\s*=" js)
            "a non-recursive colliding declaration defines the current-namespace Var")
        (is (not (re-find #"re_frame\.freehand\.sub\b" js))
            "and emits zero authoring-Var definition or reference")
        (is (= "cljs.user.sub" (compile-js aenv 'sub))
            "after the declaration the bare name resolves to the declared view,
             not through the refer — ordinary def-shadows-refer semantics")))))

(deftest an-interpreted-declaration-defines-its-current-ns-var-too
  ;; The audit's "check the interpreted declaration path too if it shares the
  ;; same `(def …)` resolution boundary" — it does. An interpreted `v/defview`
  ;; has no analysis and no emitter, but it emits the SAME `(def …)`, so it had
  ;; the same split identity and no emitter-hosted repair could ever have
  ;; reached it. This is the row that says the shadow belongs at the
  ;; declaration.
  (doseq [verb '[sub slot]]
    (with-referred-cljs-env
      (fn [aenv]
        (let [js (compile-js aenv (interpreted-declaration-form aenv verb [:div "x"]))]
          (is (re-find (re-pattern (str "cljs\\.user\\." (name verb) "\\s*=")) js)
              (str verb " — the interpreted declaration defines the current-namespace Var"))
          (is (not (re-find (re-pattern (str "re_frame\\.freehand\\." (name verb) "\\b")) js))
              (str verb " — and emits zero authoring-Var definition or reference")))))))

(deftest a-declaration-leaves-unrelated-refers-and-heads-untouched
  ;; Preserve unrelated behavior: the shadow is exactly one name wide. A view
  ;; declared under a name that collides with NOTHING drops no refer, and an
  ;; ordinary head keeps its authored spelling.
  (with-referred-cljs-env
    (fn [aenv]
      (let [form (declaration-form aenv 'panel [:div "x"])]
        (is (= "re_frame.freehand.sub" (compile-js aenv 'sub))
            "declaring `panel` does not drop the unrelated referred `sub`")
        (is (= "re_frame.freehand.slot" (compile-js aenv 'slot))
            "nor the unrelated referred `slot`")
        (is (some? form) "the declaration still expands"))))
  (with-referred-cljs-env
    (fn [aenv]
      (let [e   (referred-env aenv 'panel)
            ast (analyze/analyze e [:div "x"])]
        (emit-react/emit-react-body e [] ast)
        (is (= "re_frame.freehand.sub" (compile-js aenv 'sub))
            "and the EMITTER mutates no analyzer state at all — the shadow is
             the declaration's, not an emitter side effect")))))

