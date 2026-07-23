(ns re-frame.freehand.compiler.check
  "The READ-ONLY compile checker — discovery before commitment.

  `{:compiled true}` is a one-line change to a declaration, which makes
  it a tempting thing to try and see. Trying and seeing is a poor way to
  learn a finite language: the build stops at the first form outside
  `:re-frame.freehand/v1`, and an author who reached that failure by
  editing has already changed the declaration before finding out whether
  the change was available. So the order is inverted. The checker runs
  the SAME analyzer the build runs, against the declaration as it stands
  today, and answers in data:

      {:view-id          :app.people/people-list
       :source           {:file \"src/app/people.cljc\" :line 42 :column 1}
       :current-lowering :interpreted
       :target-grammar   :re-frame.freehand/v1
       :compile-eligible? false
       :findings         [{:id       :rf.ui.compile/markup-returning-map
                           :source   {:line 47 :column 5}
                           :form     (map person-item people)
                           :reason   :markup-hidden-from-analyzer
                           :recovery [:make-template-visible
                                      :extract-declared-child
                                      :keep-interpreted]}]}

  Changing the declaration is then the FINAL step, not the discovery
  mechanism.

  ## Three laws

  1. **It never writes.** No file is opened for writing, no emitter runs,
     no registry is contributed to, and nothing is printed. A checker
     that edited source would make its own advice unfalsifiable, and one
     that emitted code would no longer be answering a question about the
     declaration in front of you.
  2. **It never recommends.** There is no percentage, no \"hot 5%\", and
     no promotion recommender — an explicit EP-0036 non-goal. Eligibility
     is a property of the body; whether compiling it is *worth* it is a
     measurement and a judgement, and neither is this namespace's.
  3. **It invents no diagnostics.** Findings carry the analyzer's OWN
     `:rf.ui.compile/*` ids and the recovery ladders
     [[re-frame.freehand.compiler.grammar/recovery]] already serves the
     build. One roster, two consumers: a checker with its own id set
     would be a second grammar to drift.

  The recovery list is the payload. `:id` says which rule stopped you and
  `:reason` says what kind of thing went wrong, but `:recovery` is the
  ordered ladder of things you can actually do to the source in front of
  you, most specific first, drawn from
  [[re-frame.freehand.compiler.grammar/recoveries]] — whose values are
  the sentences a tool renders. Its last rung is always
  `:keep-interpreted`, because declining to compile is a first-class
  answer: the interpreted mode has no finite grammar, so every body the
  compiler refuses is a body that already runs.

  ## Hosts

  The ANALYSIS is JVM-only. Head classification resolves symbols through
  the host compiler exactly as macro expansion does, which happens on the
  JVM for both targets — the same reason
  [[re-frame.freehand.compiler]] is JVM-only. This namespace is `.cljc`
  so that it loads everywhere, and the REPORT VOCABULARY below —
  [[reasons]], [[reason]], [[finding]], [[report]] — is host-neutral, so
  a ClojureScript tool can render a report it received over the wire
  without a second copy of the vocabulary.

  A `.cljc` view, though, is not host-uniform: `#?(:clj … :cljs …)` makes
  the browser build compile a DIFFERENT branch than the one the JVM reader
  elides to, and it is the `:cljs` branch a SPA author is most likely to
  false-green, because the JVM never reads it. The reader is no help here —
  its `:clj` platform feature is always present, so `:read-cond :allow`
  resolves every `#?(:clj … :cljs …)` to the `:clj` branch whatever
  `:features` it is handed. So the checker reads with conditionals
  PRESERVED (see [[read-opts]]) and selects each target's branch ITSELF
  (see [[resolve-conditionals]]), analyzing every branch a compile would
  visit. Only the FORMS differ across the branches: head classification
  still resolves on the JVM for both targets, so a `:cljs`-only form
  outside the grammar is analyzed and found, never silently elided. See
  [[read-declarations]] and [[findings-for]].

  Selection follows the conditional wherever a conditional may legally
  stand, because an uncovered PLACEMENT is the same false-green in a new
  syntactic position: a preserved conditional the walk does not descend
  into survives as a reader object where the browser build has a form, and
  a declaration the walk does not recognise is not reported at all. So
  [[resolve-conditionals]] descends every collection kind including SETS,
  and discovery ([[declaration-for]]) runs on the resolved BRANCHES, so a
  `v/defview` written under `#?(:clj … :cljs …)` is found.

  A PURE `.cljs` source is the case that has no answer, and it is refused
  rather than approximated (see [[refuse-cljs-source!]]). Resolution needs
  either a namespace loaded on the JVM — which a `.cljs` namespace never is
  — or a genuine ClojureScript analyzer environment, which exists only
  INSIDE a running ClojureScript compile; a standalone read is not one, and
  cannot be handed one. Loading a stand-in JVM namespace of the same name
  would answer about that namespace instead, which is the false-green this
  checker exists to prevent. So `.cljs` is refused with the two workflows
  that do answer: make the declaration `.cljc` — whose `:cljs` branch the
  checker DOES analyze — or let the build answer, which is the same
  analyzer and the same diagnostics.

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../../spec/004D-Freehand-Compiled-Grammar.md)
  §The read-only checker."
  (:require [re-frame.freehand.compiler.grammar :as grammar]
            #?@(:clj [[clojure.java.io :as io]
                      [clojure.string :as str]
                      [re-frame.freehand :as v]
                      [re-frame.freehand.compiler.analyze :as ana]
                      [re-frame.freehand.compiler.env :as env]
                      [re-frame.freehand.fingerprint :as fingerprint]]))
  #?(:clj (:import [clojure.lang LineNumberingPushbackReader])))

;; ---------------------------------------------------------------------------
;; The report vocabulary — host-neutral
;; ---------------------------------------------------------------------------

(def reasons
  "Diagnostic id -> the REASON the checker reports for it: a short,
  unqualified keyword naming the KIND of thing that stopped the body.

  A reason is not a second id. `:id` is the machine discriminator and is
  the only thing a tool should branch on; `:reason` groups ids into the
  handful of categories an author actually reasons in — \"the analyzer
  cannot see this markup\", \"this head is a runtime value\", \"this
  reactive site is not finite\" — so several ids can and do share one.
  It is a projection the CHECKER makes for readers; the build itself
  reports ids and messages and has no use for it.

  Deliberately unqualified, per the shape in
  `docs/design/freehand/codex-design.md` §Checker-first workflow: a
  reason is a member of this closed roster, not a keyword in the
  framework's reserved `:rf.*` scheme, and nothing emits it at runtime."
  {:rf.ui.compile/dynamic-head         :head-is-a-runtime-value
   :rf.ui.compile/unresolved-head      :head-does-not-resolve
   :rf.ui.compile/bad-tag              :head-is-not-an-element-name
   :rf.ui.compile/keyword-child        :keyword-in-content-position
   :rf.ui.compile/markup-returning-map :markup-hidden-from-analyzer
   :rf.ui.compile/lazy-seq-child       :markup-hidden-from-analyzer
   :rf.ui.compile/unkeyed-list-item    :list-row-carries-no-key
   :rf.ui.compile/constant-list-key    :list-key-does-not-vary
   :rf.ui.compile/nested-for-body      :iteration-is-not-lexically-flat
   :rf.ui.compile/sub-in-loop          :reactive-site-is-not-finite
   :rf.ui.compile/frame-in-loop        :reactive-site-is-not-finite
   :rf.ui.compile/void-children        :void-element-carries-children
   :rf.ui.compile/duplicate-id-sugar   :element-id-declared-twice
   :rf.ui.compile/id-sugar-conflict    :element-id-declared-twice
   :rf.ui.compile/unsupported-form     :form-outside-the-grammar})

(def unclassified-reason
  "The reason for an id nobody has written a category for.

  Total, like [[grammar/recovery]], and for the same reason: a checker
  that threw on an id it did not recognise would turn every new analyzer
  diagnostic into a tool outage. `:form-outside-the-grammar` is the
  honest general statement — the id and the ladder still say which rule
  and what to do."
  :form-outside-the-grammar)

(defn reason
  "The reason keyword for diagnostic `id`. Total over ids."
  [id]
  (get reasons id unclassified-reason))

(defn finding
  "One finding: `id` refused `form`, which sits at `source`.

  Five fields, and they are the whole contract — a stable id, the
  coordinates, the offending form, the reason, and the recovery ladder.
  There is deliberately no message: a sentence is stable in meaning and
  never in bytes, so the renderable prose lives in the closed
  [[grammar/recoveries]] roster where a tool can look it up and a test
  can pin it."
  [id form source]
  {:id       id
   :source   source
   :form     form
   :reason   (reason id)
   :recovery (grammar/recovery id)})

(defn report
  "Assemble a checker report from a declaration's identity and its
  `findings`.

  `:compile-eligible?` is exactly \"no findings\" — it is derived here
  rather than passed in, so a report cannot claim eligibility while
  carrying a reason it is not."
  [{:keys [view-id source lowering findings]}]
  {:view-id           view-id
   :source            source
   :current-lowering  lowering
   :target-grammar    grammar/version
   :compile-eligible? (empty? findings)
   :findings          (vec findings)})

;; ---------------------------------------------------------------------------
;; The analysis — JVM
;; ---------------------------------------------------------------------------

#?(:clj
   (do

(defn- coords
  "`{:line … :column …}` for `form` — its own reader coordinates when the
  reader gave it any, else the declaration's.

  Clojure's reader anchors lists and not vectors, so a refused
  `(map person-item people)` locates itself and a refused `[tag {} …]`
  inherits its declaration's line. Falling back keeps the field present
  and never invents a position: the worst answer is \"somewhere in this
  declaration\", which is where the form is."
  [form declaration-source]
  (let [m (meta form)]
    (select-keys (if (integer? (:line m)) m declaration-source) [:line :column])))

(defn- findings-for-branch
  "Run the analyzer over ONE reader-conditional `body` of a declaration
  and answer its findings.

  The analyzer is FAIL-FAST — it refuses at the first form outside the
  grammar, which is also where the build would stop — so a refused
  branch yields one finding and a clean one yields none. Re-run after
  taking a rung; the ladder is walked one step at a time by construction,
  not by the checker rationing what it knows.

  Nothing here emits: the analyzer is run for its refusals, its AST is
  discarded, and neither emitter is reached."
  [{:keys [view-id vname params children-policy ns-sym source resolver]} body]
  (try
    (let [e0  (-> (env/make-env {:host            :clj
                                 :ns-sym          ns-sym
                                 :self            vname
                                 :self-id         view-id
                                 :resolver        resolver
                                 :source          source
                                 :template-anchor (fingerprint/digest "sta1-" body)})
                  (assoc :self-children? (not= :none children-policy)
                         :self-closed-keys nil))
          _   (ana/reject-reactive-binding! e0 params)
          e   (-> e0
                  (env/with-locals (set (env/binding-syms (first params))))
                  (assoc :hooks-region? true))
          ast (ana/analyze-view-body e body)]
      (grammar/check! e view-id ast)
      [])
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)
            id   (:rf.ui.compile/error data)]
        (when-not id
          ;; not a grammar refusal — the checker has no opinion about it
          (throw ex))
        (let [form (if (contains? data :form) (:form data) (last body))]
          [(finding id form (coords form source))])))))

(defn- findings-for
  "The findings for a declaration, across the reader-conditional branches
  a `.cljc` compile actually visits.

  A `{:compiled true}` `.cljc` view compiles to BOTH a JVM structural tree
  and a CLJS React tree, each from its OWN reader-conditional branch, and
  both must be inside the grammar for the compile to stand. The declaration
  itself is the branch the JVM reader selected (`#{:clj}`); `:cljs-branch`,
  present only when the two branches read DIFFERENTLY, carries what the
  browser build's branch (`#{:cljs}`) BINDS and RENDERS — the branch an
  author is most likely to false-green, because the JVM reader never elides
  to it. It is MERGED over the declaration rather than substituted for its
  body alone, because a conditional around the whole declaration diverges
  the params too, and a body analyzed against the other branch's params
  would be refused (or excused) for the wrong reason.

  Both branches are analyzed JVM-side with the SAME head resolution either
  target uses — resolution is JVM for both hosts, so only the FORMS the
  reader produced differ. One finding is reported: the first ineligible
  form across the branches, JVM branch first, keeping the
  one-refusal-at-a-time law."
  [{:keys [body cljs-branch] :as declaration}]
  (vec (take 1 (concat (when body        (findings-for-branch declaration body))
                       (when cljs-branch (findings-for-branch (merge declaration cljs-branch)
                                                              (:body cljs-branch)))))))

(defn check-declaration
  "Check ONE declaration against `:re-frame.freehand/v1` WITHOUT
  compiling it, and answer the report.

  The declaration arrives as data — the parts `v/defview` itself parses:

      {:view-id         :app.people/people-list
       :vname           'people-list      ; for self-recursive heads
       :ns-sym          'app.people       ; heads resolve through it
       :params          '[{:keys [people]}]
       :body            '([:ul (map person-item people)])   ; the :clj branch
       :cljs-branch     nil               ; the :cljs branch, when it DIVERGES
       :compiled?       false             ; what the declaration says TODAY
       :children-policy :optional
       :source          {:file \"src/app/people.cljc\" :line 42 :column 1}
       :resolver        <fn>}             ; optional; tests only

  `:cljs-branch` is supplied by [[read-declarations]] only for a `.cljc`
  view whose `#?(:clj … :cljs …)` branches read differently. It carries the
  branch-owned parts alone — `:params`, `:children-policy`, `:body` — and
  is merged over the declaration to analyze the CLJS target (see
  [[findings-for]]), so a `:cljs`-only ineligible form is found rather than
  false-greened.

  A declaration that already carries `{:compiled true}` is checked the
  same way and reports `:current-lowering :compiled` — the question
  \"is this still eligible?\" is the same question."
  [{:keys [view-id source compiled?] :as declaration}]
  (report {:view-id  view-id
           :source   source
           :lowering (if compiled? :compiled :interpreted)
           :findings (findings-for declaration)}))

(def ^:private read-opts
  "Reader options for a source file. `:read-eval` is refused separately
  (see [[read-declarations]]).

  `:read-cond :preserve` — NOT `:allow` — is the load-bearing choice. The
  JVM reader's platform feature `:clj` is always present and cannot be
  turned off, so `:read-cond :allow` (with any `:features` set) resolves
  `#?(:clj … :cljs …)` to the `:clj` branch every time: the `:cljs` branch
  the browser build compiles is elided before the analyzer can see it, and
  the checker false-greens it. Preserving the conditionals instead hands
  BOTH branches to [[resolve-conditionals]], which selects each target's
  branch itself — a selection the platform feature cannot override."
  {:eof ::eof :read-cond :preserve})

(defn- source-namespace
  "The namespace symbol declared by the file's leading `(ns …)` form."
  [form path]
  (or (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
        (second form))
      (throw (ex-info (str "re-frame.freehand check: " path
                           " does not begin with an (ns …) form, so there is no "
                           "namespace to resolve its declarations against.")
                      {:file (str path)}))))

(defn- live-namespace
  "The LOADED namespace `ns-sym` names.

  Head classification is resolution — a symbol head is an internal view,
  a foreign component, or an error according to what it resolves to — so
  the checker reads the same namespace the compiler would. Refusing to
  guess is the point: checking against an unloaded namespace would
  report every symbol head as unresolved and call the view ineligible
  for a reason that is about the checker, not the code."
  [ns-sym path]
  (or (find-ns ns-sym)
      (throw (ex-info (str "re-frame.freehand check: namespace " ns-sym " (" path
                           ") is not loaded. Heads are classified by resolution, so "
                           "load it first — (require '" ns-sym ") — and check again.")
                      {:file (str path) :ns ns-sym}))))

(defn- declaration-at
  "The declaration data for a `v/defview` form read out of `path`."
  [path ns-sym form]
  (let [[_ vname & more] form
        {:keys [opts params body]}
        (or (v/parse-defview-args more)
            (throw (ex-info (str "re-frame.freehand check: " ns-sym "/" vname " in " path
                                 " does not match (v/defview name docstring? opts? "
                                 "[props] body …), so there is no declaration to check.")
                            {:file (str path) :view vname})))
        m (meta form)]
    {:view-id         (keyword (str ns-sym) (str vname))
     :vname           vname
     :ns-sym          ns-sym
     :params          params
     :body            body
     :compiled?       (get opts :compiled false)
     :children-policy (get opts :children-policy :optional)
     :source          (cond-> {:file (str path)}
                        (:line m)   (assoc :line (:line m))
                        (:column m) (assoc :column (:column m)))}))

(defn- has-reader-conditional?
  "Does `form` contain a `#?`/`#?@` reader conditional anywhere within it?
  The identity fast-path for [[resolve-conditionals]]: a form with none is
  returned untouched, so an ordinary (conditional-free) declaration keeps
  its exact objects and metadata and the analysis is byte-for-byte what it
  always was."
  [form]
  (cond
    (reader-conditional? form) true
    (map? form)  (boolean (some has-reader-conditional? (mapcat identity form)))
    (coll? form) (boolean (some has-reader-conditional? form))
    :else        false))

(defn- select-branch
  "The branch of reader conditional `rc` that `features` selects — MY
  selection, so `#{:cljs}` really does choose `:cljs` (unlike the reader,
  whose platform set always carries `:clj`). `:default` matches last, as
  the reader's own rule. `::elide` when nothing matches: the conditional
  contributes nothing to that target."
  [rc features]
  (loop [pairs (partition 2 (:form rc))]
    (if-let [[feature form] (first pairs)]
      (if (or (contains? features feature) (= :default feature))
        form
        (recur (next pairs)))
      ::elide)))

(declare resolve-conditionals)

(defn- resolve-children
  "Resolve conditionals across a sequence of sibling forms, SPLICING a
  matched `#?@` and dropping an elided conditional — the two things a
  conditional does that a plain form cannot. Returns a vector."
  [xs features]
  (into []
        (mapcat
         (fn [x]
           (if (reader-conditional? x)
             (let [branch (select-branch x features)]
               (cond
                 (= ::elide branch) []
                 (:splicing? x)     (resolve-children (seq branch) features)
                 :else              [(resolve-conditionals branch features)]))
             [(resolve-conditionals x features)])))
        xs))

(defn- resolve-conditionals
  "Return `form` with every reader conditional resolved for `features`,
  preserving each rebuilt collection's metadata (the reader anchors lists
  with `{:line :column}`, and a finding's coordinates ride that metadata).

  This is how the JVM-side checker reaches the `:cljs` branch: the file is
  read with conditionals PRESERVED (see [[read-opts]]), and the target's
  branch is selected HERE, past the reach of the reader's `:clj` platform
  feature. `form` is not itself a splicing conditional — those are handled
  in sibling context by [[resolve-children]], the reader allowing `#?@` only
  inside a collection.

  EVERY collection kind is descended into, sets included. A kind left out is
  not a smaller fix, it is the same false-green in a new position: the
  conditional survives the walk, and the analyzer is handed a reader object
  where the browser build has `#{v/sub}`."
  [form features]
  (cond
    (not (has-reader-conditional? form))
    form

    (reader-conditional? form)
    (let [branch (select-branch form features)]
      (when-not (= ::elide branch) (resolve-conditionals branch features)))

    (map? form)
    (with-meta (into (empty form)
                     (map (fn [[k v]] [(resolve-conditionals k features)
                                       (resolve-conditionals v features)]))
                     form)
               (meta form))

    (set? form)
    (with-meta (into (empty form) (resolve-children form features)) (meta form))

    (vector? form)
    (with-meta (resolve-children form features) (meta form))

    (seq? form)
    (with-meta (apply list (resolve-children form features)) (meta form))

    :else
    form))

(defn- defview-form?
  "Is `form` a `v/defview` declaration, read in `ns-obj`? A head counts when
  it RESOLVES to the var there — an alias, a `:refer`, or the qualified name
  all read the same."
  [ns-obj form]
  (and (seq? form)
       (symbol? (first form))
       (= #'v/defview (try (ns-resolve ns-obj (first form))
                           (catch Exception _ nil)))))

(def ^:private branch-keys
  "The declaration parts a reader conditional can make target-specific: what
  the branch BINDS and what it RENDERS. Everything else — the view's id, its
  name, its source coordinates, whether it says `{:compiled true}` — is the
  declaration's identity, and one identity is what the file declares however
  many branches it was written across."
  [:params :children-policy :body])

(defn- declaration-for
  "The declaration data for top-level `form`, or nil when it declares no view.

  Discovery runs on the RESOLVED BRANCHES rather than on the form as read,
  because a whole declaration may be written under a reader conditional:

      #?(:clj  (v/defview thing [_]           [:div …])
         :cljs (v/defview thing [{:keys [tag]}] [tag {} …]))

  A preserved conditional is not a seq, so a discovery test applied to the
  form itself skips such a declaration entirely and the file reports NOTHING
  about it — the loudest false-green there is, since the author reads
  silence as \"no findings\".

  What the branches contain is still ONE declaration. Its identity is the
  JVM branch's — or the CLJS branch's, for a view that exists only in the
  browser build — and `:cljs-branch` carries the [[branch-keys]] whenever
  the CLJS branch's differ. Both are then analyzed by [[findings-for]], so a
  `:cljs`-only form outside the grammar is FOUND rather than elided by the
  reader and false-greened."
  [path ns-sym ns-obj form]
  (let [decl-for  (fn [features]
                    (let [f (resolve-conditionals form features)]
                      (when (defview-form? ns-obj f)
                        (declaration-at path ns-sym f))))
        clj-decl  (decl-for #{:clj})
        cljs-decl (decl-for #{:cljs})
        decl      (or clj-decl cljs-decl)]
    (when decl
      (cond-> decl
        (and cljs-decl (not= (select-keys cljs-decl branch-keys)
                             (select-keys decl branch-keys)))
        (assoc :cljs-branch (select-keys cljs-decl branch-keys))))))

(defn- read-declarations
  "Every `v/defview` in the file at `path`, in declaration order, as
  declaration data — each carrying both reader-conditional branches a
  `.cljc` compile visits (see [[declaration-for]]).

  The file is opened for READING and read with `*read-eval*` false: a
  checker is pointed at source an author has not yet decided to compile,
  and evaluating it to find out whether it compiles would be a strange
  way to answer the question. Reader conditionals are PRESERVED rather than
  resolved by the reader (see [[read-opts]]) so the `:cljs` branch survives
  to be analyzed. Forms are read in the file's own namespace so `::keyword`
  aliases resolve."
  [path]
  (with-open [rdr (LineNumberingPushbackReader. (io/reader (io/file path)))]
    (binding [*read-eval* false]
      (let [ns-sym (source-namespace (read read-opts rdr) path)
            ns-obj (live-namespace ns-sym path)]
        (binding [*ns* ns-obj]
          (loop [declarations []]
            (let [form (read read-opts rdr)]
              (if (= ::eof form)
                declarations
                (recur (if-let [declaration (declaration-for path ns-sym ns-obj form)]
                         (conj declarations declaration)
                         declarations))))))))))

(defn- refuse-cljs-source!
  "Refuse a pure `.cljs` `path`, naming the two workflows that answer.

  The checker's whole claim is that it runs the analyzer the BUILD runs, and
  the analyzer classifies heads by RESOLUTION. On the JVM that is a loaded
  namespace; for a ClojureScript target it is a real `cljs.analyzer`
  environment, and a real one exists only inside a running ClojureScript
  compile — a standalone read cannot receive one, and a `.cljs` namespace is
  never loaded on the JVM.

  What is left is a stand-in, and every stand-in is a lie: `(require …)` on a
  same-named JVM namespace answers about THAT namespace's vars, and
  resolving through the checker's own namespace answers about the checker.
  Either produces a confident report about code nobody wrote — the exact
  false-green [[findings-for]] exists to prevent. So the answer is a refusal
  that says what to do instead."
  [path]
  (when (.endsWith (str/lower-case (str path)) ".cljs")
    (throw (ex-info (str "re-frame.freehand check: " path " is a ClojureScript "
                         "source. Heads are classified by resolution, and a .cljs "
                         "namespace has neither a loaded JVM namespace nor — outside "
                         "a running ClojureScript compile — an analyzer environment "
                         "to resolve through, so there is no build context to check "
                         "against and the checker will not invent one. Make the "
                         "declaration .cljc and check that (both reader-conditional "
                         "branches are analyzed, including the :cljs branch the "
                         "browser build compiles), or let the build answer — it is "
                         "the same analyzer and the same diagnostics.")
                    {:file (str path) :target :cljs}))))

(defn check-file
  "Check every `v/defview` in the source file at `path` and answer one
  report per declaration, in declaration order.

  This is the discovery workflow: point it at a namespace and read which
  of its views are already inside `:re-frame.freehand/v1` and, for the
  rest, why not and what to do about it. The file is only ever read —
  see the read-only law in this namespace's docstring.

  `path` is a `.clj` or `.cljc` source whose namespace is LOADED, because
  heads are classified by resolution; a tool driving this from a REPL or a
  build already has it. A `.cljc` declaration is checked for BOTH targets —
  each reader-conditional branch a compile visits is analyzed (see
  [[findings-for]]) — which is how a CLJS-target question is answered. A
  pure `.cljs` source has no build context to answer from and is refused,
  not approximated: see [[refuse-cljs-source!]]."
  [path]
  (refuse-cljs-source! path)
  (mapv check-declaration (read-declarations path)))

))
