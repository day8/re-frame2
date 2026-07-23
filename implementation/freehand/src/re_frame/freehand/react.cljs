(ns re-frame.freehand.react
  "The INTERPRETED React emitter — unrestricted Hiccup from a view body in,
  real React elements out.

  This is Freehand's browser half. Its sibling,
  [[re-frame.freehand.tree]], answers the versioned structural tree on
  either host; this one answers `react/createElement` output, and only in
  ClojureScript.

  The two are **separate walks by design** (EP-0036 governing law 7). They
  share the pure rules in [[re-frame.freehand.conversion]] — one tag
  parser, one class composer, one style canonicaliser, one number
  formatter — but neither emitter is written in terms of the other, and
  neither is derived from the other's output. Their agreement is proven,
  not assumed: the structural corpus pins what the tree says, and a
  mounted browser assertion pins what the DOM does, over the same
  declarations.

  ## What this slice emits

    - **elements** — `.class#id` sugar merged, attributes in React's own
      CANONICAL prop spelling (`:content-editable` is `contentEditable`,
      `:stroke-width` is `strokeWidth`), `:key` lifted into the element's
      key, children passed as varargs so React needs no synthetic keys for
      a literal run;
    - **view boundaries** — a real React function component per declared
      view, cached under that view's QUALIFIED ID, so React sees a
      boundary, DevTools sees a name, and a compatible hot reload keeps
      the boundary React already mounted;
    - **fragments**, **text**, spliced seqs, and the same dropped
      `nil`/`false`/`true`.

  ## The atomic shell

  Every declared view is mounted through
  [[re-frame.freehand.shell/render]], so a boundary owns a ViewCell, reads
  its frame from the shared context, and publishes exactly one bundle per
  SELECTED render. The walk below therefore threads that render's
  CANDIDATE: a `:on-*` carrying event intent is recorded on the candidate
  through `re-frame.freehand.events/site` and becomes live — targeted at
  the committed frame — only at commit. A `:on-*` carrying a plain
  function is a site too, so its lifetime is the same site lifetime.

  An event site outside ANY declared boundary — an element handed
  straight to [[element]] with no view above it — has no candidate and so
  no commit to belong to; declarative intent there is not attached,
  because there is nothing that could own it.

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md);
  the shell's commit law is
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §The Freehand atomic shell."
  (:require ["react" :as react]
            [goog.object :as gobj]
            [re-frame.error :as error]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.error-react :as error-react]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.presence-runtime :as presence-runtime]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.tree :as tree]))

(defn- malformed!
  [reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    're-frame.freehand.react/element
    reason
    {:recovery :no-recovery :extra extra}))

(defn- shape [x] (error/diag-value-summary x))
(defn- type-name [x] (name (:type (shape x))))

;; ---------------------------------------------------------------------------
;; Props
;; ---------------------------------------------------------------------------

(defn- react-style
  [tag v]
  (when-not (map? v)
    (malformed! (str "The :style value on " tag " is a " (type-name v)
                     "; :style is a map of CSS property to value.")
                {:attr :style :value (shape v)}))
  (reduce-kv (fn [o k x]
               (if (nil? x)
                 o
                 (let [css-name (name k)]
                   (gobj/set o (conv/react-style-name css-name)
                             (conv/css-value css-name x))
                   o)))
             #js {} v))

(defn- put-attr!
  [o tag k raw]
  (let [semantic (conv/attr-value raw)]
    (when (= ::conv/reject semantic)
      (malformed! (str "The " k " attribute on " tag " carries a " (type-name raw)
                       ", which has no attribute spelling. An attribute value is a string, a "
                       "keyword, a symbol, a number or a boolean; :class and :style have their "
                       "own richer grammars.")
                  {:attr k :value (shape raw)}))
    (gobj/set o (conv/react-prop-name k) semantic)))

(defn- react-props
  "The React props object for one element: the author attribute map in
  React's CANONICAL prop spelling, with the `.class#id` sugar merged in and
  every handler site recorded on `cand` — this render's candidate — so the
  site becomes live, targeted at the committed frame, only when the render
  is selected.

  No namespace context is threaded in, because a canonical prop name does
  not depend on one — which is what stops a declared-view boundary from
  changing which attribute reaches the DOM."
  [cand tag sugar-classes sugar-id attrs]
  (let [o (js-obj)]
    (when-let [c (conv/class-string sugar-classes)]
      (gobj/set o "className" c))
    (when sugar-id
      (gobj/set o "id" sugar-id))
    ;; The key is read in its canonical author spelling: an alias of a
    ;; slot-owning key is that key written differently, so `:x/class`
    ;; composes into `className` beside the sugar rather than overwriting
    ;; it, and a namespaced `:style` reaches the style grammar rather than
    ;; the flat attribute path (rf2-drpa3.93).
    (doseq [[k raw] attrs
            :let    [k (conv/attr-key k)]]
      (when-some [refusal (conv/attr-key-refusal k)]
        (malformed! (str "The element " tag " carries " k ". " refusal)
                    {:attr k}))
      ;; The `#id` conflict, judged by the emitted SLOT rather than by the
      ;; raw key — and this walk is where the raw comparison SHOWED: React
      ;; writes `:id` and `:x/id` into one JavaScript property, so the
      ;; aliased pair silently replaced the sugar while the structural tree
      ;; reported both. Asked only when there IS sugar to conflict with.
      (when (and sugar-id (some? raw) (conv/id-slot-key? k))
        (malformed! (str "The element " tag " spells its id twice — once as #" sugar-id
                         " sugar and once as " k ". Two id spellings on one element is an "
                         "ambiguity; keep one."
                         (when (not= :id k)
                           (str " " k " is :id written differently: a namespace is dropped "
                                "on the way to the DOM, so both land in the same "
                                "attribute.")))
                    {:attr k :value (shape raw)}))
      (cond
        (= :key k)   nil
        (nil? raw)   nil

        (conv/handler-key? k)
        (if (some? cand)
          (when-some [proxy (events/site (cell/candidate-events cand)
                                         (cell/next-site-key! cand)
                                         raw)]
            (gobj/set o (conv/react-event-name k) proxy))
          ;; No boundary above this element, so no commit can own the
          ;; site. A plain function is still the caller's own callback and
          ;; is attached; declarative intent has nothing to belong to.
          (when (fn? raw)
            (gobj/set o (conv/react-event-name k) raw)))

        (= :class k)
        (let [parts (conv/class-parts raw)]
          (when (= ::conv/reject parts)
            (malformed! (str "The :class value on " tag " is outside the class grammar. Write a "
                             "string, a keyword, a vector of them in order, or a flag map whose "
                             "truthy entries name classes.")
                        {:attr :class :value (shape raw)}))
          (if-let [c (conv/class-string (into (vec sugar-classes) parts))]
            (gobj/set o "className" c)
            (gobj/remove o "className")))

        (= :style k)
        (gobj/set o "style" (react-style tag raw))

        :else (put-attr! o tag k raw)))
    ;; Key PRESENCE, not key truth. React string-coerces whatever key it is
    ;; given, so an explicit `nil` is the ordinary identity "null" — and a
    ;; different authored fact from no key at all, which matters wherever a
    ;; keyed reconciler is watching (`(v/presence …)` DROPS a keyless child).
    ;; `(:key attrs)` answers nil for both, so the question is `contains?`.
    ;; A key whose value is `js/undefined` stays absent, which is React's own
    ;; rule and what the presence keyless advisory describes.
    (when (contains? attrs :key)
      (gobj/set o "key" (:key attrs)))
    o))

;; ---------------------------------------------------------------------------
;; Declared views become real React components
;; ---------------------------------------------------------------------------

(declare ^:private emit)
(declare element)

(def ^:private components
  "Qualified view id -> the ONE stable React boundary this emitter mounts
  that view through:

      {:signature <the shell signature below>
       :component <the React component type React reconciles on>
       :slot      #<volatile {:body <render body> :revision <int>}>}

  Keyed by the QUALIFIED VIEW ID, deliberately, and not by descriptor
  identity. A declared view is a VALUE, so a hot reload mints a fresh
  descriptor object; keying on that object would mint a fresh React
  component TYPE for every redefinition, and a changed type is React's
  instruction to unmount the old boundary and mount a new one. The reload
  would then reseed everything below it — an uncontrolled input's text,
  a scroll offset, focus — which is precisely the occurrence `v/mount`
  reuses the host root to preserve. The view id is what `v/mount` already
  keys root identity on, and it is what \"the same view\" means.

  ONE entry per view id, REPLACED rather than appended to: a long dev
  session's reload generations retain one component, one render body and
  two small values, never one set per generation."
  (atom {}))

(defn- shell-signature
  "The React HOOK SKELETON this emitter will give `view` — the axis on
  which a redefinition is COMPATIBLE, and so the axis that decides
  stable shell versus clean remount.

  An interpreted view body calls no React hooks. It is unrestricted
  Clojure that produces markup, and every hook a Freehand boundary owns
  belongs to [[re-frame.freehand.shell/render]] — one `useContext`, one
  `useRef`, one `useSyncExternalStore`, two `useLayoutEffect`s — in a
  fixed order no edit to a body can move. An interpreted body edit,
  however large, therefore cannot change the hook skeleton, and reusing
  the boundary across it is not a bet: it is the only answer that does
  not throw away state React was willing to keep.

  What DOES change the skeleton is a change of LOWERING. The compiled
  tier renders through its own shell, and its capability-elision verdict
  omits the ViewCell — and with it every hook above — for a view with no
  reactive site; a containment boundary is not a function component at
  all but the React CLASS the error-boundary law drives. Promotion
  between modes is a real authoring edit (`{:compiled true}` added to a
  declaration, then a reload), so those static declaration facts are the
  whole signature. A signature change mints a new component and React
  remounts the boundary ONCE, cleanly, rather than running a different
  hook order against the old Fiber's hook state."
  [view]
  [(if (descriptor/error-boundary? view)
     :error-boundary
     (:lowering (descriptor/describe view)))
   (:view-cell (descriptor/manifest view))])

(defn- publish-body!
  "Publish `view`'s render body into a stable boundary's `slot`, and
  advance that slot's BODY REVISION when the body is genuinely new.

  Identity is the test, because a redefinition mints a new body closure
  and re-walking an unchanged tree does not. So one render pass that
  reaches the same boundary at several call sites republishes nothing and
  advances nothing — the revision moves at the RELOAD seam, and a live
  candidate is never made stale by an ordinary walk."
  [slot view]
  (let [body (descriptor/render-body view)]
    (when-not (identical? body (:body @slot))
      (vswap! slot (fn [s] (-> s (assoc :body body) (update :revision inc)))))
    nil))

(defn- interpreted-component
  "The React function component an INTERPRETED declaration lowers to: one
  atomic shell around whatever body the boundary's `slot` currently holds.

  The body is read from the slot on every render rather than closed over,
  which is what lets a compatible reload publish a new body through a
  component React is already reconciling."
  [view view-id slot]
  (when (descriptor/structural-body view)
    ;; A compiled declaration has no interpreted body to walk. Its React
    ;; lowering — direct jsx calls over the same analyzed template — is the
    ;; compiled tier's other emitter, and it lands with the slice that owns
    ;; the ViewCell it renders inside. Until then this path is a LOUD
    ;; refusal rather than a walk of `nil`: a compiled view that quietly
    ;; rendered nothing in a browser would be the worst possible way to
    ;; learn the lowering is missing.
    (error/throw-error!
      :rf.error/view-lowering-unavailable
      're-frame.freehand.react/element
      (str view-id " is declared {:compiled true} and the compiled tier's "
           "React lowering is not built yet — the structural lowering is. "
           "Render it structurally, or drop the marker to mount it "
           "interpreted; the declaration is the only thing that changes.")
      {:recovery :render-structurally-or-drop-the-compiled-marker
       :extra    {:view-id view-id}}))
  (let [c (fn freehand-view [js-props]
            (let [{:keys [body revision]} @slot]
              (shell/render
                view-id
                revision
                (fn [cand]
                  (cell/with-capture
                    cand
                    (fn []
                      ;; The OCCURRENCE SEAM. React renders each declared
                      ;; view in its own component, so a throw arriving here
                      ;; is THIS view's body's — the boundary that catches it
                      ;; several fibers above has no other way to learn whose,
                      ;; and the throwable carries no view identity.
                      (try
                        (emit cand
                              (body (conv/forward-children
                                      (gobj/get js-props "props"))))
                        (catch :default e
                          (eb/note-failing-view! view-id)
                          (throw e)))))))))]
    (gobj/set c "displayName" (str view-id))
    c))

(defn- component-for
  [view]
  (let [view-id (:view-id (descriptor/describe view))
        sig     (shell-signature view)
        entry   (get @components view-id)]
    (if (and (some? entry) (= sig (:signature entry)))
      ;; The COMPATIBLE reload. React is handed the component type it
      ;; already mounted, so the Fiber — and the ViewCell, the DOM nodes
      ;; and the uncommitted browser state below it — survives; what
      ;; changes is the body the next render runs.
      (do (publish-body! (:slot entry) view)
          (:component entry))
      (let [slot (volatile! {:body (descriptor/render-body view) :revision 0})
            c    (if (descriptor/error-boundary? view)
                   ;; An error boundary is not an ordinary function component
                   ;; — a function component cannot catch a descendant's
                   ;; render throw. It lowers to the React class boundary the
                   ;; error-boundary law drives (capture, the
                   ;; once-per-generation intent, reset, the private egress),
                   ;; built once and cached like any other declared view's
                   ;; component.
                   (error-react/boundary-component element)
                   (interpreted-component view view-id slot))]
        (swap! components assoc view-id {:signature sig :component c :slot slot})
        c))))

(defn ^:no-doc boundary-cache
  "The stable-boundary cache projected as `{view-id {:signature :revision}}`
  — a RETENTION and reload seam for tests, never application API. One entry
  per view id, whatever a session's reload count, is the invariant; the
  revision is what the hot-reload fence rides on."
  []
  (persistent!
    (reduce-kv (fn [m view-id {:keys [signature slot]}]
                 (assoc! m view-id {:signature signature :revision (:revision @slot)}))
               (transient {})
               @components)))

(defn ^:no-doc reset-boundaries!
  "Drop every cached boundary — a test-isolation seam only, mirroring
  [[re-frame.freehand.root/reset-registry!]]. A suite that mounts a view
  id another suite also uses calls this between runs so a boundary from
  the earlier run cannot masquerade as this one's reload."
  []
  (reset! components {})
  nil)

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------
;; This walk carries NO namespace context, and its sibling
;; [[re-frame.freehand.tree]] does. That asymmetry is the contract, not an
;; oversight: `:ns` is a field of the structural node, so the structural
;; walk must know where a node sits, while React infers the SVG and MathML
;; namespaces from the tag itself and takes canonical prop names that are
;; canonical everywhere. Context threaded here would be context that
;; decided nothing — and worse, context a declared-view boundary cannot
;; carry: a boundary becomes a real React component, so the body runs in a
;; fresh call with no walk above it. A rule that needed the context would
;; be a rule that changed meaning when an author extracted a view.
;;
;; It does thread `cand` — THIS render's candidate — which is what every
;; event site is recorded on, and which is nil only for a walk that has no
;; declared boundary above it. Unlike a namespace context, the candidate
;; decides something: whether declarative event intent has a commit to
;; belong to.

(declare ^:private children)

(defn- element-node
  [cand tag-kw args]
  (when (namespace tag-kw)
    (malformed! (str "The element head " tag-kw " is namespaced. An element tag is an unqualified "
                     "keyword — its namespace would be silently dropped on the way to the DOM.")
                {:value (shape tag-kw)}))
  (let [{:keys [tag classes id]} (conv/parse-tag tag-kw)
        attrs?    (map? (first args))
        attrs     (if attrs? (first args) {})
        kid-forms (if attrs? (rest args) args)
        props     (react-props cand tag classes id attrs)
        kids      (children cand kid-forms)]
    (when (and (seq kids) (contains? conv/children-rejected-tags tag))
      (malformed! (str "The element " tag " cannot have children — it is a void element, and "
                       "React throws rather than render one. Put the content in an attribute, "
                       "or use an element that takes children.")
                  {:tag tag :children-count (count kids)}))
    (apply react/createElement (name tag) props kids)))

(defn- fragment-node
  [cand args]
  (let [attrs? (map? (first args))
        attrs  (when attrs? (first args))
        kids   (children cand (if attrs? (rest args) args))]
    (apply react/createElement
           react/Fragment
           ;; Key PRESENCE — see `react-props`.
           (when (contains? attrs :key) #js {:key (:key attrs)})
           kids)))

(defn- boundary-node
  [view args]
  (let [{:keys [key keyed? props]} (descriptor/normalize-call view args)
        js-props (js-obj "props" props)]
    ;; Key PRESENCE — see `react-props`. `normalize-call` reports it, because
    ;; the stripped `:key` value alone cannot tell an explicit nil from an
    ;; absent key.
    (when keyed?
      (gobj/set js-props "key" key))
    (react/createElement (component-for view) js-props)))

(defn- presence-node
  "A `(v/presence …)` boundary in interpreted markup — `[presence-tag opts &
  kids]`. Its keyed children are walked into React elements HERE and handed as
  one array to the shared retention runtime, the SAME lowering target the
  compiled React emitter emits, so the retention behaviour is one implementation
  across modes. The children carry their own React `.-key`, which is the
  identity the runtime tracks."
  [cand form]
  (let [opts (second form)
        kids (children cand (drop 2 form))]
    (presence-runtime/presence-boundary (:timeout-ms opts) (into-array kids))))

(defn- node
  [cand form]
  (let [head (first form)]
    (cond
      (= tree/fragment-tag head)
      (fragment-node cand (rest form))

      (= descriptor/presence-tag head)
      (presence-node cand form)

      :else
      (case (descriptor/classify-head head)
        :element (element-node cand head (rest form))
        ;; A nested boundary is a React ELEMENT this walk does not enter:
        ;; the child renders later, in its own component, under its own
        ;; candidate. That is why a candidate can never collect a
        ;; descendant boundary's sites.
        :view    (boundary-node head (rest form))
        :host    (malformed!
                   (str "A declared host descriptor is a legal vector head, but the React "
                        "emitter cannot cross one yet — the host boundary and its three host "
                        "shapes land with the host-lifecycle slice.")
                   {:value (shape head)})))))

(defn- collect [cand acc form]
  (cond
    (nil? form)     acc
    (boolean? form) acc
    (string? form)  (conj acc form)
    (number? form)  (conj acc (conv/js-number-str form))
    (conv/child-run? form) (reduce (fn [a f] (collect cand a f)) acc form)
    (vector? form)  (conj acc (node cand form))
    (seq? form)     (reduce (fn [a f] (collect cand a f)) acc form)
    :else
    (malformed!
      (str "A view body produced a " (type-name form) " where a child was expected. "
           "A child is markup (a vector), text (a string or a number), a seq of "
           "children, or nothing (nil / false).")
      {:value (shape form)})))

(defn- children
  [cand forms]
  (reduce (fn [acc f] (collect cand acc f)) [] forms))

(defn- emit
  [cand form]
  (let [kids (children cand [form])]
    (case (count kids)
      0 nil
      1 (first kids)
      (apply react/createElement react/Fragment nil kids))))

(defn element
  "Interpret `form` and answer the React element it denotes — or `nil` when
  it denotes nothing, or a fragment when it denotes several things.

      (element [my-panel {:title \"Details\"}])

  Pure: it builds elements and mounts nothing. Handing the result to
  `react-dom/client` is what puts it on a page.

  There is no boundary above this call, so no candidate: the declared
  views inside the form each open their own when React renders them."
  [form]
  (emit nil form))
