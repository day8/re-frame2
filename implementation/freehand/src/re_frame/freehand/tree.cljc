(ns re-frame.freehand.tree
  "The INTERPRETED structural walk — unrestricted Hiccup from a view body
  in, the versioned semantic structural tree out.

  This is one of Freehand's two emitters, and the one that runs on both
  hosts. On the JVM it is the whole render: a declaration goes in and a
  plain, serialisable value comes out, which the SSR seam turns into
  markup and a structural test asserts against. In ClojureScript it runs
  identically and answers the same value — which is what makes the
  cross-host conformance corpus a real claim rather than a JVM claim with
  a `.cljc` extension.

  The sibling emitter, [[re-frame.freehand.react]], produces React
  elements for the browser. The two are **separate walks by design**
  (EP-0036 governing law 7): they share the pure rules in
  [[re-frame.freehand.conversion]] and they prove their agreement through
  common conformance values, but neither is implemented in terms of the
  other. Divergence between them is detected, not prevented — and the
  point of one owning contract is to give two separate implementations one
  thing to be separate against.

  ## What the walk interprets

  Everything a view body can produce, with no finite grammar and no
  compile step:

    - a **keyword head** is a DOM or custom element, with `.class#id`
      sugar, author-space attributes, handler sites, and `:key`;
    - a **declared-view head** is an internal boundary — the walk
      normalizes the call, runs the view body, and records the expansion
      inside a view-boundary node;
    - `[:<> …]` is a fragment;
    - strings and numbers are text, seqs splice, `nil`/`false`/`true`
      vanish, and adjacent text coalesces.

  ## What it does NOT own

  It does not own the canonical form. Assembling a node — coalescing
  text, composing classes, dropping absent attributes, adopting a
  fragment-rooted view — is [[re-frame.freehand.node]]'s, and a compiled
  view's emitted body calls exactly the same builders with values it
  resolved at compile time. So this walk is a **front end**: it decides
  what a FORM denotes, and hands the resulting values to the one
  canonicalizer. A declaration promoted to `{:compiled true}` swaps the
  front end and nothing else, which is why the structural output is
  unchanged by construction rather than by agreement.

  Nothing here subscribes, dispatches, mounts or schedules. A view body
  that reads state is F2's; this walk is a pure function of its argument,
  which is why two calls on one declaration are indistinguishable from
  one.

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)."
  (:require [re-frame.error :as error]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.node :as node]))

#?(:clj (set! *warn-on-reflection* true))

(def tree-version
  "The structural-tree schema version this walk emits, carried on the root
  node as `:rf.ui/tree-version`. Every consumer validates it FIRST, before
  any traversal (Spec 004B §Versioning)."
  1)

(def fragment-tag
  "The one authored spelling of a fragment head."
  :<>)

(def ^:private where
  "The raising site every walk diagnostic names."
  're-frame.freehand.tree/render)

(defn- malformed!
  [reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    where
    reason
    {:recovery :no-recovery :extra extra}))

(defn- shape
  "A bounded SHAPE summary of an offending value — never the value itself
  (Spec 015 §Data-Classification)."
  [x]
  (error/diag-value-summary x))

(declare ^:private walk)

(defn- children
  "The canonical children vector for markup `forms`."
  [forms]
  (node/walked-children walk where forms))

;; ---------------------------------------------------------------------------
;; Error-boundary containment
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Error boundaries and error egress. On the structural host a
;; boundary IS a try around the child walk: [[re-frame.freehand.errors/contain]]
;; runs the guarded child, and a render-class throw is CAPTURED — the boundary
;; node holds the FALLBACK subtree instead of the child's, while every sibling
;; node the surrounding walk built keeps its place. The child that threw
;; published nothing (the atomic shell's law); this is what replaces it.
;;
;; The fallback is walked OUTSIDE the guard: a fallback that itself throws
;; propagates to the next outer boundary rather than being caught here (D019
;; §Boundary semantics 6). The safe intent and the private egress are the
;; MOUNTED host's to fire (they dispatch and promote, which a pure structural
;; walk does not do); this walk proves containment, and the law's own suite
;; proves the once-per-generation intent and the egress record.

(defn- mount-error-boundary
  "Mount `v/error-boundary` on the structural host: contain the guarded
  child's walk, and answer the boundary node holding the child subtree on
  success or the fallback subtree on a caught render throw."
  [view args]
  (let [{:keys [key keyed? props]} (descriptor/normalize-call view args)
        {:keys [fallback] :as opts} (eb/read-opts props)
        child   (first (:children opts))
        b       (eb/boundary eb/boundary-view-id (:reset-key opts))
        ;; WHICH view threw is not passed in: the mount seam below notes it
        ;; as the throw unwinds through it, so a failure several levels under
        ;; the guarded child is attributed to the view that actually threw.
        outcome (eb/contain b #(children [child]) {:phase :render})
        kids    (case (:status outcome)
                  :ok        (:result outcome)
                  :contained (children [fallback]))]
    ;; `:fallback` is dropped from the record for the reason `:children` is
    ;; (see [[re-frame.freehand.node/boundary]]): it is a markup FORM, not a
    ;; value. Recorded verbatim it would put an unwalked template — whose head
    ;; is a view DESCRIPTOR in the documented `{:fallback [broken-page {}]}`
    ;; shape — into a slot the node schema says holds data, and the tree would
    ;; stop printing and reading back. Nothing is lost: `read-opts` REQUIRES a
    ;; fallback, so its presence proves nothing a test could not already
    ;; assume, and a contained boundary shows the walked fallback subtree as
    ;; its children.
    (node/boundary eb/boundary-view-id (dissoc props :fallback) keyed? key kids)))

;; ---------------------------------------------------------------------------
;; Element nodes
;; ---------------------------------------------------------------------------

(defn- with-attrs
  "Fold one authored attribute map INTO `m` — the argument map
  [[re-frame.freehand.node/element]] takes, already carrying everything
  the walk knew before it read the attributes.

  Folding into that map rather than building a second one and merging is
  the difference between one map and nine: `merge` conjes entry by entry,
  so a six-slot literal merged onto a one-slot map allocated the whole
  ladder of intermediate maps on every element in the tree.

  Every value here is only known now, so they all ride `:dyn` — the
  interpreted front end has no compile step in which to settle one.
  `:key` is structural and `:class` composes after the sugar classes.

  The `#id` conflict is judged by the emitted SLOT
  ([[re-frame.freehand.conversion/id-slot-key?]]) rather than by the raw
  key, because the sugar and an authored id are two spellings of one
  attribute and the grammar removes that ambiguity rather than ranking it.
  Asked only when there IS sugar: with no sugar there is no second
  spelling, and `:x/id` is an ordinary qualified attribute that keeps its
  authored name.

  PRESENCE decides it, never truth. The authored key IS the second
  spelling — the generic nil-attribute law says what an id VALUE of nil
  emits, and it cannot say whether the element was given an id twice. The
  compiled analyzer reads `(keys m)` and has no value to consult, so a
  truth test here would accept `[:div#sugar {:id nil}]` that
  `{:compiled true}` refuses, and adding one word to a declaration would
  turn a rendering element into a compile error."
  [m tag sugar-id attrs]
  (reduce-kv
    (fn [m k raw]
      (when-some [refusal (conv/attr-key-refusal k)]
        (malformed!
          (str "The element " tag " carries " k ". " refusal)
          {:attr k}))
      (when (and sugar-id (conv/id-slot-key? k))
        (malformed!
          (str "The element " tag " spells its id twice — once as #" sugar-id
               " sugar and once as " k ". Two id spellings on one element is an "
               "ambiguity; keep one." (when (not= :id k)
                                        (str " " k " is :id written differently: a "
                                             "namespace is dropped on the way to the DOM, "
                                             "so both land in the same attribute.")))
          {:attr k :value (shape raw)}))
      (cond
        (= :key k)   m
        (= :class k) (assoc m :class raw)
        (= :style k) (assoc m :style raw)
        :else        (update m :dyn assoc k raw)))
    m
    attrs))

(defn- element-attrs
  "Split an element's authored props map into the attributes it folds and the
  `v/spread-safe` caller map it may be carrying.

  `(v/spread-safe owned caller)` answers the owned map with the GUARDED caller
  riding under [[re-frame.freehand.node/caller-attrs-key]], because the fold —
  caller under owned, `:class` composing owned-first — belongs to the one
  canonicaliser rather than to either front end. The compiled emitter hands
  the same two values to the same slot from the other side."
  [authored]
  [(dissoc authored node/caller-attrs-key)
   (get authored node/caller-attrs-key)])

(defn- element-node
  [tag-kw args]
  (when (namespace tag-kw)
    (malformed!
      (str "The element head " tag-kw " is namespaced. An element tag is an unqualified "
           "keyword — its namespace would be silently dropped on the way to the DOM.")
      {:value (shape tag-kw)}))
  (let [{:keys [tag classes id]} (conv/parse-tag tag-kw)
        _         (when (= "" (name tag))
                    (malformed!
                      (str "The element head " tag-kw " carries only .class#id sugar and no tag.")
                      {:value (shape tag-kw)}))
        attrs?    (map? (first args))
        [authored
         caller]  (if attrs? (element-attrs (first args)) [nil nil])
        kid-forms (if attrs? (rest args) args)]
    (node/element
      (with-attrs (cond-> {:tag      tag
                           :sugar    classes
                           :attrs    (cond-> {} id (assoc :id id))
                           :dyn      {}
                           :key?     (contains? authored :key)
                           :key-val  (:key authored)
                           :children (when (seq kid-forms) #(children kid-forms))}
                    (some? caller) (assoc :caller caller))
                  tag id authored))))

;; ---------------------------------------------------------------------------
;; Presence — the keyed enter/exit structural node
;; ---------------------------------------------------------------------------

(defn presence
  "The JVM/SSR structural node for `(v/presence {:timeout-ms n} …)`: a fragment
  carrying the `:rf.ui/presence {:phase :present :timeout-ms n}` diagnostic
  marker (§004B — presence metadata exposed structurally; a droppable reserved
  key, stripped by semantic normalization). The JVM structural host has no
  lifecycle, so every retained child renders `:present`.

  This is the ONE lowering target the compiled JVM emitter and the interpreted
  structural walk both build, so the structural projection of a presence
  boundary is identical across modes. `xs` are already-evaluated child VALUES —
  the compiled emitter's emitted child nodes, or the interpreted walk's walked
  child nodes — canonicalised here through [[re-frame.freehand.node/children]].
  `:children` is retained even when empty, like a fragment: it is the node's
  discriminator."
  [timeout-ms & xs]
  {:rf.ui/presence {:phase :present :timeout-ms timeout-ms}
   :children (vec (apply node/children xs))})

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------

(defn- walk
  "One markup vector -> one node."
  [form]
  (let [head (first form)]
    (cond
      (= fragment-tag head)
      ;; Key PRESENCE, not key truth — the question [[element-node]] and the
      ;; React walk already ask. `(:key attrs)` answers nil for an explicit
      ;; nil key and for no key at all, and those are different authored
      ;; facts: React string-coerces a supplied key, so an explicit nil is
      ;; the identity "null". `contains?` is the honest question.
      (let [args  (rest form)
            attrs (when (map? (first args)) (first args))]
        (node/fragment (contains? attrs :key)
                       (:key attrs)
                       (children (if attrs (rest args) args))))

      ;; A presence boundary: `v/presence` returns `[presence-tag opts & kids]`
      ;; in interpreted markup. Its children are lowered HERE — walked into
      ;; structural nodes — and handed to the shared [[presence]] builder, so an
      ;; interpreted `(v/presence …)` and a compiled one produce the SAME node.
      (= descriptor/presence-tag head)
      (apply presence (:timeout-ms (second form)) (children (drop 2 form)))

      :else
      (case (descriptor/classify-head head)
        :element (element-node head (rest form))
        ;; Trailing children are the CALLER's markup and are lowered HERE,
        ;; before the boundary is mounted — not left as forms for the callee
        ;; to walk. That is what makes the crossing symmetric: a boundary
        ;; receives already-structural children whichever mode wrote them, so
        ;; a compiled body splicing `children` and an interpreted body
        ;; splicing `children` are doing the same thing to the same value.
        :view    (let [args (rest form)]
                   (if (descriptor/error-boundary? head)
                     ;; A boundary contains the child walk, so its children are
                     ;; NOT pre-lowered here — the guard has to run around the
                     ;; walk itself, not around an already-built subtree.
                     (mount-error-boundary head args)
                     (node/mount head (cons (first args) (children (rest args))))))
        :host    (malformed!
                   (str "A declared host descriptor is a legal vector head, but the v1 node set "
                        "carries no host variant — a foreign boundary's structural and SSR "
                        "policy is declared at the host boundary, and that declaration lands "
                        "with the host-lifecycle slice.")
                   {:value (shape head)})))))

;; ---------------------------------------------------------------------------
;; The mount seam — one call, either mode
;; ---------------------------------------------------------------------------
;;
;; Extending the seam HERE, rather than on the descriptor itself, is what keeps
;; `re-frame.freehand.node` below the public door: the builders a compiled body
;; calls are reachable through the one require a declaration already has, and
;; the walk a mounted INTERPRETED child needs arrives with the structural
;; renderer that is doing the mounting.
;;
;; Both arms assemble the identical boundary node from the identical normalized
;; call. Only the middle step differs — run the body and walk what it returned,
;; or invoke the compiled body, which returns the node directly.

(extend-type #?(:clj re_frame.freehand.descriptor.ViewDescriptor :cljs descriptor/ViewDescriptor)
  node/IMount
  (-mount [view args]
    (let [{:keys [key keyed? props]} (descriptor/normalize-call view args)
          props*  (conv/forward-children props)
          view-id (:view-id (descriptor/describe view))
          ;; The OCCURRENCE SEAM. A render-class throw passing through here
          ;; is this view's, and an enclosing boundary has no other way to
          ;; learn whose it was — the throwable carries no view identity, and
          ;; a boundary that guessed would name the guarded child or itself.
          ;; The note is against THIS throw, and is dropped if an inner
          ;; occurrence already made one for it.
          kids    (try
                    (if-let [structural (descriptor/structural-body view)]
                      (node/children (structural props*))
                      (children [((descriptor/render-body view) props*)]))
                    (catch #?(:clj Throwable :cljs :default) e
                      (eb/note-failing-view! e view-id)
                      (throw e)))]
      (node/boundary view-id props keyed? key kids))))

;; ---------------------------------------------------------------------------
;; Render entry
;; ---------------------------------------------------------------------------

(defn render
  "Interpret `form` and answer its versioned structural tree.

  The return is always the ROOT node — a map — carrying
  `:rf.ui/tree-version`. A form that denotes text, several nodes, or
  nothing is rooted in a fragment, which is the node variant whose whole
  job is to hold a run of children; interior nodes carry no version and
  inherit their tree's.

      (render [:section.panel {:id \"main\"} [:h2 \"Details\"]])
      ;; => {:tag :section
      ;;     :attrs {:class \"panel\" :id \"main\"}
      ;;     :children [{:tag :h2 :children [\"Details\"]}]
      ;;     :rf.ui/tree-version 1}

  A compiled view mounted anywhere under `form` renders through its own
  compiled body — the walk never interprets a compiled template, and a
  compiled body never walks a form.

  The value is plain Clojure data — maps and strings, no wrapper types, no
  metadata-carried contract — so it prints and reads back losslessly, and
  `(tree-seq map? :children tree)` is the whole traversal API."
  [form]
  (binding [node/*ns-context* nil]
    (let [kids (children [form])
          root (if (and (= 1 (count kids)) (map? (first kids)))
                 (first kids)
                 {:children kids})]
      (assoc root :rf.ui/tree-version tree-version))))

;; ---------------------------------------------------------------------------
;; The server render path — `v/render-static`'s JVM tree -> HTML seam
;; ---------------------------------------------------------------------------
;;
;; Spec 011 §Freehand server render. `v/render-static` (the door macro over
;; `re-frame.freehand.compiler.root/render-static-form`) compiles the LITERAL
;; root form to the versioned JVM structural tree and folds it to an inert HTML
;; string. The macro emits a call to
;;
;;   (emit-static-html (render-root-tree (fn [] <compiled root node>)))
;;
;; so the two halves of the render live HERE, in the interpreted structural
;; renderer that is already reachable through the one require a Freehand view has
;; — and `re-frame.freehand` never statically requires `re-frame.ssr` (the SSR
;; serialiser is resolved LATE, at render time). That is the whole point of the
;; seam: a documented render-static call in a namespace that requires only
;; `re-frame.freehand` COMPILES and RENDERS, and a missing SSR artefact is the
;; ruled, typed `:rf.error/ssr-artefact-missing` rather than a raw
;; `ClassNotFoundException` at compile time.

(defn render-root-tree
  "Run a compiled ROOT-FORM tree `thunk` and stamp the versioned structural
  tree — the literal-root-form sibling of [[render]] (which walks an interpreted
  form). `re-frame.freehand/render-static` renders the server tree through here
  before [[emit-static-html]] folds it to an inert HTML string; the root node is
  the single mounted view's boundary the root form produced.

  Binds the HTML namespace context (mirroring [[render]]) so the tree's top
  element derives its `:ns` from the HTML default. There is no reactive read to
  scope: render-static's no-silent-elision proof (Spec 004C §3, EP-0034 §2)
  rejects a runtime-requiring capability — including `v/sub` — anywhere in the
  root's server-reachable closure at BUILD time, so this render is a pure fold."
  [thunk]
  (binding [node/*ns-context* nil]
    (assoc (thunk) :rf.ui/tree-version tree-version)))

#?(:clj
   (defn resolve-ssr-emitter
     "Late-resolve `re-frame.ssr/emit-ui-tree` on the JVM — the SSR artefact's
     pure structural-tree -> HTML serialiser (Spec 004B §The SSR consumption
     boundary). Returns the resolved var (loading `re-frame.ssr` on demand if it
     is on the classpath but not yet required), or nil when the artefact is
     absent.

     `re-frame.freehand` takes NO static require on `re-frame.ssr` (Spec 011
     §wall — no `ui → ssr` static require), so a server namespace that requires
     only `re-frame.freehand` still renders: the emitter is loaded HERE, at
     render time. Extracted as its own seam so the artefact-absent branch is
     drivable in a test."
     []
     (try
       (requiring-resolve 're-frame.ssr/emit-ui-tree)
       (catch java.io.FileNotFoundException _ nil))))

#?(:clj
   (defn emit-static-html
     "Fold a version-1 JVM structural `tree` to an inert HTML string through the
     SSR artefact's `re-frame.ssr/emit-ui-tree`, resolved LATE (see
     [[resolve-ssr-emitter]]). This is the runtime seam
     `re-frame.freehand/render-static` emits a call to instead of naming
     `re-frame.ssr/emit-ui-tree` directly — a direct emit would compile a hard
     `re-frame.ssr` reference into the caller's namespace, so a documented
     render-static call in a namespace that required only `re-frame.freehand`
     would fail to COMPILE with a raw `ClassNotFoundException: re-frame.ssr`.

     When the `day8/re-frame2-ssr` artefact is absent from the server classpath
     this raises the ruled, typed `:rf.error/ssr-artefact-missing` naming the
     artefact — never a raw host exception, never a silent fallback."
     [tree]
     (if-let [emit (resolve-ssr-emitter)]
       (emit tree)
       (error/throw-error!
        :rf.error/ssr-artefact-missing
        'v/render-static
        (str "v/render-static requires day8/re-frame2-ssr on the classpath; add "
             "it to deps and require re-frame.ssr at app boot.")
        {:recovery :add-ssr-artefact
         :extra {:maven "day8/re-frame2-ssr" :require-ns "re-frame.ssr"}}))))
