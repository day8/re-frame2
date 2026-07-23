(ns re-frame.freehand.compiler.grammar
  "`:re-frame.freehand/v1` — the finite, versioned grammar a declaration
  opts into with `{:compiled true}`, and the recovery ladder every
  rejection outside it names.

  Two facts live here, and nowhere else:

  1. **What v1 admits.** [[admitted-ops]] is the closed roster of analyzed
     node kinds a compiled body may lower to. A grammar that is finite is
     a grammar you can *name*: the version keyword is not decoration, it
     is the promise that this roster does not quietly grow under a
     running codebase. It grows by ruling, in a new version.
  2. **What a rejection tells you to do.** [[recovery]] is total over
     diagnostic ids, and its last rung is always `:keep-interpreted`.
     That rung is always available and always correct, because the
     interpreted mode has NO finite grammar — every body the compiler
     refuses is a body the interpreter accepts, and demotion is the same
     one-line change as promotion. A compiler that can only say \"no\"
     teaches authors to avoid it; one that says \"no, and here is the
     ladder\" teaches them where compilation stops and why.

  There is deliberately no severity dial, no `:strict?`, and no
  per-project allowance. A form is in the grammar or it is not, and the
  answer is the same in every build.

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [re-frame.freehand.compiler.env :as env]))

(def version
  "The grammar version `{:compiled true}` selects. Carried on every
  compiled declaration's manifest and in the ex-data of every rejection,
  so a diagnostic says which language refused the form."
  :re-frame.freehand/v1)

(def admitted-ops
  "The CLOSED roster of analyzed node kinds `:re-frame.freehand/v1`
  lowers. Anything the analyzer produces outside this set is refused by
  [[check!]] before an emitter ever sees it — the emitters therefore have
  no unknown-node arm, which is what makes escaping structural rather
  than defensive."
  #{:text :nothing :expr :element :fragment :view :for :if :let :letfn :case
    :presence :slot})

(def ^:private op-rejections
  "Analyzed node kinds outside v1, each with the sentence that names what
  the form asks for and the ladder that gets the author out. Every one is
  a real Freehand surface whose compiled lowering lands with its own
  slice; refusing it now is honest, and silently mis-lowering it is not."
  {:foreign        {:what "a foreign component boundary"
                    :recovery [:extract-declared-child :keep-interpreted]}
   :raw            {:what "an embedded host React element"
                    :recovery [:extract-declared-child :keep-interpreted]}
   :html           {:what "the trusted-HTML escaping bypass"
                    :recovery [:keep-interpreted]}
   :client-only    {:what "a browser-only subtree"
                    :recovery [:extract-declared-child :keep-interpreted]}
   :error-boundary {:what "an error boundary"
                    :recovery [:extract-declared-child :keep-interpreted]}
   :frame-root     {:what "a frame root"
                    :recovery [:scope-the-frame-above-the-view :keep-interpreted]}
   :frame-provider {:what "a frame provider"
                    :recovery [:scope-the-frame-above-the-view :keep-interpreted]}
   :hook-prefix    {:what "host effects in the view body"
                    :recovery [:keep-interpreted]}})

(def ^:private id-recoveries
  "The ladder for the analyzer's own rejections. Ordered most specific
  first — a reader takes the first rung they can reach."
  {:rf.ui.compile/dynamic-head        [:use-a-literal-head :extract-declared-child]
   :rf.ui.compile/unresolved-head     [:use-a-literal-head]
   :rf.ui.compile/keyword-child       [:pass-computed-value]
   :rf.ui.compile/markup-returning-map [:make-template-visible :extract-declared-child]
   :rf.ui.compile/lazy-seq-child      [:make-template-visible :pass-computed-value]
   :rf.ui.compile/unkeyed-list-item   [:key-each-row :extract-declared-child]
   :rf.ui.compile/constant-list-key   [:key-each-row]
   :rf.ui.compile/nested-for-body     [:make-template-visible]
   :rf.ui.compile/sub-in-loop         [:extract-declared-child]
   :rf.ui.compile/frame-in-loop       [:extract-declared-child]
   :rf.ui.compile/unsupported-form    [:make-template-visible :pass-computed-value
                                       :extract-declared-child]
   :rf.ui.compile/void-children       [:pass-computed-value]
   :rf.ui.compile/bad-tag             [:use-a-literal-head]
   :rf.ui.compile/duplicate-id-sugar  [:use-a-literal-head]
   :rf.ui.compile/id-sugar-conflict   [:use-a-literal-head]})

(def recoveries
  "The CLOSED roster of legal recoveries, each one a thing an author can
  actually do to the source in front of them.

  `:keep-interpreted` is last and always present — a compiled view that
  refuses to compile is a view that runs, unchanged, without the marker."
  {:make-template-visible
   "write the structure lexically in the template, where the compiler can see it"
   :pass-computed-value
   "compute the value outside the template and pass it into visible structure"
   :extract-declared-child
   "extract a declared child view — it may stay interpreted"
   :use-a-literal-head
   "name the element or view literally; heads are not runtime values"
   :key-each-row
   "give every row of the list a :key that varies with the row"
   :scope-the-frame-above-the-view
   "scope the frame above this view rather than inside it"
   :keep-interpreted
   "drop {:compiled true} and keep this view interpreted"})

(defn recovery
  "The recovery ladder for diagnostic `id` — a vector of [[recoveries]]
  keys, most specific first, always ending in `:keep-interpreted`.

  Total by construction: an id nobody has written a specific ladder for
  still answers the rung that is always available."
  [id]
  (conj (get id-recoveries id []) :keep-interpreted))

(defn check!
  "Refuse an analyzed body that reaches outside `:re-frame.freehand/v1`.

  Walks the AST and raises on the FIRST node whose `:op` is not admitted,
  naming the form's kind, the grammar that refused it, and the ladder.
  Returns `ast` unchanged when the whole body is inside the roster — so
  an emitter downstream can be written against the closed set with no
  unknown-node arm, which is the property that makes 'no hidden
  interpreted fallback' a structural fact rather than an assertion."
  [e view-id ast]
  (letfn [(walk [n]
            (cond
              (map? n)
              (do (when-let [op (:op n)]
                    (when-not (contains? admitted-ops op)
                      (let [row (get op-rejections op)]
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   (str (or (:what row) (str "the " (name op) " form"))
                                        " is outside " version " — the finite grammar "
                                        "{:compiled true} selects. " view-id
                                        " cannot be compiled while its body asks for it. "
                                        "There is no interpreted fallback inside compiled "
                                        "markup: the compiled tier lowers what it can see, "
                                        "and says so when it cannot.")
                                   {:re-frame.freehand/grammar version
                                    :op       op
                                    :view     view-id
                                    :recovery (conj (or (:recovery row) [])
                                                    :keep-interpreted)}))))
                  (run! (fn [[_ v]] (walk v)) n))

              (vector? n) (run! walk n)))]
    (walk ast))
  ast)
