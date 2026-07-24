(ns re-frame.freehand.compiler-macro-resolution-jvm-test
  "Real CLJS-analyzer resolution proofs for the expression macro barrier."
  (:require [cljs.analyzer :as cljs-analyzer]
            [cljs.analyzer.api :as cljs-api]
            [cljs.compiler :as cljs-comp]
            [cljs.core]
            [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.compiler.analyze :as analyze]
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

(defn- mount-head-syms
  "Every descriptor a structural emit MOUNTS whose name matches `verb`.

  A Freehand view is a descriptor mounted through one seam, never a
  callable, so the self reference rides `(node/mount <descriptor> …)`
  rather than sitting in head position. The claim under test is unchanged:
  which SYMBOL the emitter reaches for."
  [form verb]
  (let [hits (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x) (= 're-frame.freehand.node/mount (first x))
                  (symbol? (second x)) (= (name (second x)) (name verb)))
         (swap! hits conj (second x)))
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
  ;; re-captures `re-frame.freehand/<verb>` as the head, failing every row below.
  ;;
  ;; The browser half of this claim rode the deleted second CLJS emitter, whose
  ;; jsx-runtime heads it walked; what survives is the STRUCTURAL emit, which
  ;; carries the same `:fqn` rule through `*self-fqn*`.
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [verb '[sub frame]]
        (let [self-fqn  (symbol "cljs.user" (name verb))
              args      (self-emit-args aenv verb)
              jvm-form  (emit-jvm/emit-defview args)]
          (testing (str "structural: the self mount targets the current-namespace fqn (" verb ")")
            (let [jvm-heads (mount-head-syms jvm-form verb)]
              (is (seq jvm-heads) "a self boundary mount is emitted")
              (is (every? #(= self-fqn %) jvm-heads)
                  (str "every structural self mount names cljs.user/" verb))
              (is (not-any? #(= verb %) jvm-heads)
                  "the raw authored spelling never reaches a mount head"))))))))

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
;; rf2-drpa3.108 — the emitter may not name a re-frame.freehand var that does
;; not exist. `emit-defview` used to wrap the view body in
;; `re-frame.freehand.tree/view-boundary` and emit a
;; `re-frame.freehand.tree/register-view!` call; NEITHER function was ever built
;; (the donor's runtime was not transplanted with its call sites). The calls
;; only ever rode `emit-defview`'s RETURNED form — the live `v/defview` path
;; routes through `expand-defview` -> `compile-structural-view` /
;; `descriptor/declare-view`, never here — so nothing evaluated the form and the
;; tier stayed green rather than failing at load with unresolved-var errors.
;; This positively enumerates the re-frame.freehand vars the emitter names and
;; confirms each resolves, the method the bead asked for (absence of a failure
;; was never evidence the emission could run).
;; ---------------------------------------------------------------------------

(defn- plain-emit-args
  "emit-defview args for a plain (non-self) element template `[:div …]` in the
  referred cljs.user namespace — the emitter under test, driven the way
  `compiler/defview**` drives it, minus the self-recursion machinery."
  [aenv vname template]
  (let [e   (referred-env aenv vname)
        ast (analyze/analyze e template)]
    {:vname vname
     :self-fqn (symbol "cljs.user" (name vname))
     :view-id (keyword "cljs.user" (name vname))
     :display-name (str "cljs.user/" (name vname))
     :docstring nil
     :header (header/parse-header [])
     :slots []
     :ast ast
     :manifest {:view-id (keyword "cljs.user" (name vname)) :sites {} :children? false}
     :closed-keys nil
     :children? false}))

(defn- freehand-qualified-syms
  "Every namespace-qualified symbol in `form` whose namespace is one of
  re-frame.freehand's own runtime namespaces — the class the emitter must not
  name unless it resolves."
  [form]
  (let [acc (atom #{})]
    (walk/postwalk
     (fn [x]
       (when (and (symbol? x)
                  (some-> (namespace x) (.startsWith "re-frame.freehand")))
         (swap! acc conj x))
       x)
     form)
    @acc))

(deftest emit-defview-names-no-unresolvable-freehand-var
  (with-referred-cljs-env
    (fn [aenv]
      (let [form (emit-jvm/emit-defview
                  (plain-emit-args aenv 'panel '[:div [:span "hi"]]))
            fh   (freehand-qualified-syms form)]
        (is (seq fh)
            "the emitted view fn names re-frame.freehand runtime vars (node/element, node/children)")
        (doseq [s fh]
          (is (some? (requiring-resolve s))
              (str s " must resolve — the emitter may not name a var that does not exist")))
        (testing "the phantom donor wrappers are gone (rf2-drpa3.108)"
          (is (not (contains? fh 're-frame.freehand.tree/view-boundary))
              "the view-boundary wrapper that named a nonexistent fn is removed")
          (is (not (contains? fh 're-frame.freehand.tree/register-view!))
              "the register-view! call that named a nonexistent fn is removed"))))))

;; rf2-eukmp's rows analyzed and compiled the WHOLE emitted `(declare/defn/def)`
;; do-form through real cljs.analyzer + cljs.compiler, proving the view's own def
;; targeted the current-namespace Var rather than resolving its NAME through a
;; same-named `:refer`. That do-form was the deleted second CLJS emitter's, and
;; the surviving browser emitter emits no view def at all — the declaration's def
;; is `re-frame.freehand/expand-defview`'s, and it holds a descriptor. The
;; counterfactual above still pins the underlying analyzer ranking that made the
;; defect possible.
