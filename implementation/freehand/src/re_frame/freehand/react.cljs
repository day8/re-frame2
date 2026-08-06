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
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.error-react :as error-react]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.node :as node]
            [re-frame.freehand.phase :as phase]
            [re-frame.freehand.presence-runtime :as presence-runtime]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.top-layer :as top-layer]
            [re-frame.freehand.tree :as tree]
            [re-frame.performance :as performance :include-macros true]))

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

(defn- style-into!
  "Write one authored `:style` value onto `o`, or MERGE a compose vector
  into it left to right. A later entry wins per React style property and
  both survive on a non-conflict, which is what makes an exact `:style`
  beside an alias projecting onto the style slot compose rather than the
  last one written winning (rf2-8jqw7). The recursion flattens the
  incrementally-built pair the folder produces, the same shape `:class`
  composes through [[conv/class-parts]]."
  [o tag v]
  (if (vector? v)
    ;; A nil contributor is ABSENT — the nil-is-absent law, carried into the
    ;; compose so an exact value beside a nil alias survives rather than
    ;; rejecting the whole element.
    (reduce (fn [o one] (if (some? one) (style-into! o tag one) o)) o v)
    (do
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
                 o v))))

(defn- react-style
  [tag v]
  (style-into! #js {} tag v))

(defn- react-control-value
  "One semantic control value in the shape React takes it. Every scalar is
  itself; the COLLECTION a native `<select multiple>`'s `value` carries
  becomes the JavaScript array React's contract requires — the one place
  an emitted control value's SHAPE differs from the semantic value the
  structural tree records, and the last step rather than a second grammar."
  [v]
  (if (vector? v) (into-array v) v))

(defn- put-attribute!
  "Write one author prop as an ATTRIBUTE — the attribute-value grammar and
  React's canonical attribute spelling."
  [o tag k raw]
  (let [select-value? (controlled/select-value-slot? tag k)
        semantic      (if select-value? (conv/select-value raw) (conv/attr-value raw))]
    (when (= ::conv/reject semantic)
      (malformed! (str "The " k " attribute on " tag " carries a " (type-name raw)
                       ", which has no attribute spelling. An attribute value is a string, a "
                       "keyword, a symbol, a number or a boolean; :class and :style have their "
                       "own richer grammars."
                       (when select-value?
                         (str " A <select>'s :value additionally takes a SEQUENTIAL collection "
                              "of them, which is what a multiple select's selection is; a set "
                              "is not one, because its order is not a value the two hosts "
                              "agree on.")))
                  {:attr k :value (shape raw)}))
    (gobj/set o (conv/react-prop-name k) (react-control-value semantic))))

(defn- put-plain-attr!
  "Write ONE non-slot author prop onto the props object: a DECLARED
  custom-element PROPERTY under its camelCase JS property name, anything
  else as the attribute [[put-attribute!]] spells.

  The property branch lives HERE because this is the single place every
  browser path arrives at — the interpreted walk's own fold, the compiled
  tier's emitted `attr!`, a `v/spread`'s forwarded entry and a
  `v/spread-safe` caller's — so one declaration means one thing on all four
  rather than four times once. A compiled element's LITERAL property is
  resolved to its camelCase name at BUILD time and never reaches here.

  The value is written VERBATIM: what a property means belongs to the
  element's own setter, which is why a map, a vector or a host object is
  legal there and refused as an attribute. The registry read is asked per
  prop rather than threaded in, because the tag test in
  [[re-frame.freehand.conversion/element-properties]] answers `nil` for
  every plain DOM element without touching the registry at all, and
  widening `put-attr!`'s arity would change the emitted compiled ABI for a
  lookup only web components ever perform."
  [o tag k raw]
  (if (contains? (conv/element-properties tag) k)
    (gobj/set o (conv/custom-element-property-name k) raw)
    (put-attribute! o tag k raw)))

(defn ^:no-doc put-class!
  "Compose `sugar` (the tag's `.class` shorthand names) with the AUTHORED
  `:class` value and write the result as `className` — or remove the
  property when the composition is empty.

  Named and shared because the compiled tier composes classes through
  THIS function rather than through a second, build-time ordering. A flag
  map mixing literal and computed entries sorts all its truthy names
  together, and that ordering is a property of the composition rule, not
  of the emitter that reached it."
  [o tag sugar raw]
  (let [parts (conv/class-parts raw)]
    (when (= ::conv/reject parts)
      (malformed! (str "The :class value on " tag " is outside the class grammar. Write a "
                       "string, a keyword, a vector of them in order, or a flag map whose "
                       "truthy entries name classes.")
                  {:attr :class :value (shape raw)}))
    ;; A composition is one to three class names, and `into` reaches for a
    ;; TRANSIENT to add them: `-as-transient` on a vector allocates a
    ;; 32-slot editable tail and clones the root, per element, to hold two
    ;; strings. `(vec nil)` pays the same on an EMPTY vector, which is what
    ;; every caller with no `.class` sugar — `put-attr!` and the caller fold
    ;; — was paying for nothing. Folding with `conj`, and not folding at all
    ;; when there is no sugar, is the same value without either (rf2-xu6rx).
    (if-let [c (conv/class-string (if (seq sugar) (reduce conj (vec sugar) parts) parts))]
      (gobj/set o "className" c)
      (gobj/remove o "className"))
    o))

(defn ^:no-doc put-style!
  "Canonicalise an authored `:style` map and write it."
  [o tag raw]
  (gobj/set o "style" (react-style tag raw))
  o)

(defn ^:no-doc put-html!
  "Write an element's TRUSTED MARKUP as React's `dangerouslySetInnerHTML`.

  Named and shared for the reason [[put-class!]] is: the compiled tier
  reaches trusted markup through THIS function, so `(v/html s)` produces
  the same props object whether the structure was resolved at build time
  or a moment ago.

  The string check and the two host refusals — `<textarea>`, and every void
  element — are [[re-frame.freehand.node/html-content!]]'s, the same call
  the structural canonicaliser makes. So all four rendering paths raise one
  sentence per law instead of four copies that could drift."
  [o tag raw]
  (gobj/set o "dangerouslySetInnerHTML"
            #js {:__html (node/html-content! 're-frame.freehand/render tag raw)})
  o)

(defn ^:no-doc put-attr!
  "Write ONE runtime-valued attribute onto a props object, under every
  rule this emitter applies to the same value: the canonical author
  spelling, the refusal roster, the nil-is-absent law and its
  controlled-slot exception, and the two keys that own a slot.

  The compiled tier resolves an element's literal attributes at build
  time and calls this for the rest, so the values a compiler cannot see
  are converted by the one function that converts them when nobody
  compiled anything.

  `multiple?` is the element's
  [[re-frame.freehand.controlled/multiple-select?]] verdict, which the
  caller settled once for the whole element — it changes only what an
  EMPTY `<select>` value is. It defaults to false, which every control but
  a multiple select is."
  ([o tag k raw] (put-attr! o tag k raw false))
  ([o tag k raw multiple?]
   (let [k (conv/attr-key k)]
     (when-some [refusal (conv/attr-key-refusal k)]
       (malformed! (str "The element " tag " carries " k ". " refusal)
                   {:attr k}))
     (cond
       (= :key k)   nil
       (nil? raw)   (when-some [empty-slot (controlled/empty-control-slot tag k multiple?)]
                      (gobj/set o (key empty-slot) (react-control-value (val empty-slot))))
       (= :class k) (put-class! o tag nil raw)
       (= :style k) (put-style! o tag raw)
       :else        (put-plain-attr! o tag k raw))
     o)))

(defn- handler-proxy
  "What this walk attaches at a handler slot: the site's STABLE PROXY when
  a candidate owns it, and — with no declared boundary above the element,
  so no commit that could own a site — [[re-frame.freehand.events/unsited]]'s
  answer, which is the caller's own function for the values that never
  wanted a site (`v/raw-fn`, `v/render-fn`, a bare fn) and nil for the
  declarative intent that has nothing to belong to."
  [cand tag controlled? slot raw]
  (if (some? cand)
    (events/site (cell/candidate-events cand)
                 (cell/next-site-key! cand)
                 raw
                 events/default-payload
                 {:tag tag :controlled? controlled? :slot slot})
    (events/unsited raw)))

(defn ^:no-doc put-caller!
  "Fold a GUARDED `(v/spread-safe owned caller)` caller map UNDER a props
  object whose OWNED props are already final.

  The ONE browser fold, reached from both front ends — the interpreted
  walk below and the compiled tier's `compiled-react/caller-spread!` — and
  the counterpart of [[re-frame.freehand.node/fold-caller]] on the
  structural host. Owned wins every collision (the deny law ran at
  `node/safe-caller-attrs`, so what can still collide is an ordinary
  attribute), with `:class` the one exception: the two class values
  COMPOSE, owned first, because a caller passing `.mt-4` is adding a class
  and not replacing the component's own. That composition runs through the
  same class rule by handing the owned `className` in as the leading part,
  so there is no second ordering to keep in step.

  `site!` records one handler entry and answers its proxy (or nil) — the
  one thing the two front ends genuinely do differently, because a
  compiled site is keyed by its proven lexical id and an interpreted one
  by this walk's ordinal."
  [o tag site! m]
  (when (some? m)
    ;; The element's declared property set, read ONCE for the whole forwarded
    ;; map — a plain DOM tag answers nil without touching the registry. It
    ;; ranks a forwarded `:on-detail`: declared, it is a property and reaches
    ;; `put-plain-attr!` below; undeclared, it is the native event this fold
    ;; has always made it (rf2-sv2oq).
    (let [declared (conv/element-properties tag)]
      (reduce-kv
        (fn [_ k raw]
          (let [k    (conv/attr-key k)
                slot (controlled/prop-slot k)]
            (cond
              (= :class k)
              (put-class! o tag (when-some [owned (gobj/get o "className")] [owned]) raw)

              ;; owned wins
              (and (some? slot) (gobj/containsKey o slot)) nil

              (conv/handler-key? declared k)
              (when-some [proxy (site! slot raw)] (gobj/set o slot proxy))

              :else (put-attr! o tag k raw)))
          nil)
        nil m)))
  o)

(defn- react-props
  "The React props object for one element: the author attribute map in
  React's CANONICAL prop spelling, with the `.class#id` sugar merged in and
  every handler site recorded on `cand` — this render's candidate — so the
  site becomes live, targeted at the committed frame, only when the render
  is selected.

  No namespace context is threaded in, because a canonical prop name does
  not depend on one — which is what stops a declared-view boundary from
  changing which attribute reaches the DOM.

  `controlled?` is the element's controlled-input door verdict, settled by
  the caller for the whole element. It is a property of the ELEMENT, and
  both writers of this element's props need it, so it is asked once and
  handed to each rather than derived here and again beside the caller
  fold — see [[element-node]]."
  [cand tag sugar-classes sugar-id attrs caller controlled?]
  (let [o (js-obj)
        ;; The element's DECLARED custom-element property set, read once for
        ;; the whole map — nil, without a registry read, for every plain DOM
        ;; element. It is needed HERE and not only in `put-plain-attr!`
        ;; because the handler/attribute fork happens above that write, and
        ;; the declaration has to rank a `:on-*` name before the fork
        ;; commits it to the event grammar.
        declared    (conv/element-properties tag)
        ;; The COLLECTION-shaped `value` verdict is a property of the
        ;; WHOLE element too, and settled here beside the door's for the
        ;; same reason: a native `<select multiple>`'s selection is a list
        ;; of option values, so its EMPTY value is the empty collection
        ;; rather than the empty string, and React reports the wrong shape
        ;; loudly. A `v/spread-safe` caller may legally carry `:multiple`,
        ;; and it folds UNDER the owned props (`put-caller!`) only after the
        ;; owned nil `value` is normalized here — so the caller is settled
        ;; into this one verdict, before that normalization (rf2-sf9n5).
        ;; EFFECTIVE source, not a union — the rule, and the tag gate that
        ;; makes it free for every element that is not a `<select>`, are
        ;; [[re-frame.freehand.controlled/multiple-select-verdict]]'s. Both
        ;; walks asked it in four lines each; it is one question.
        multi?      (controlled/multiple-select-verdict tag attrs nil caller)
        ;; The exact `:class` and any alias projecting onto the class slot
        ;; COMPOSE — sugar first, then the exact `:class`, then the aliases —
        ;; rather than the last one written winning. They are collected
        ;; exact-first and composed ONCE through the same class rule the sugar
        ;; uses, so `className` is written once and an alias never overwrites
        ;; the exact spelling (rf2-c9kus; .93's routing, one-map case).
        ;; Folded with `reduce-kv`, the traversal the rest of this file
        ;; already uses over an attribute map. `(into … (keep …) attrs)`
        ;; asked for three allocations an element that declares no alias —
        ;; which is nearly every element — never needed: a key/value seq
        ;; over the map, the lazy `keep` on top of it, and a TRANSIENT of
        ;; the one-element target vector, whose editable tail is a 32-slot
        ;; array. `reduce-kv` walks the map's own array and hands the key
        ;; and the value straight to the fold (rf2-xu6rx).
        class-forms (reduce-kv (fn [acc k raw]
                                 (if (and (not= :class k)
                                          (= :class (conv/attr-key k)))
                                   (conj acc raw)
                                   acc))
                               (if (contains? attrs :class) [(:class attrs)] [])
                               attrs)
        ;; The exact `:style` and any alias projecting onto the style slot
        ;; COMPOSE — the exact value first, then the aliases — merged property
        ;; by property rather than the last one written winning, exactly as
        ;; `:class` composes just above. Collected exact-first and written
        ;; ONCE, so the walk skips every style-slot key below and an alias
        ;; never overwrites the exact spelling (rf2-8jqw7; parallel to
        ;; rf2-c9kus).
        ;; A nil style value is ABSENT (the nil-is-absent law a conditional
        ;; `:style (when …)` relies on, which the per-key walk applied before
        ;; this collection subsumed the slot), so nils are dropped here — an
        ;; all-nil slot writes no style at all, exactly as it did before.
        style-forms (reduce-kv (fn [acc k raw]
                                 (if (and (some? raw)
                                          (not= :style k)
                                          (= :style (conv/attr-key k)))
                                   (conj acc raw)
                                   acc))
                               (if (some? (:style attrs)) [(:style attrs)] [])
                               attrs)]
    (when (or (seq sugar-classes) (seq class-forms))
      (put-class! o tag sugar-classes class-forms))
    (when (seq style-forms)
      (put-style! o tag (if (= 1 (count style-forms))
                          (first style-forms)
                          (vec style-forms))))
    (when sugar-id
      (gobj/set o "id" sugar-id))
    ;; The id-slot CARDINALITY, judged by the emitted SLOT rather than by the
    ;; raw keys — and this walk is where the raw comparison SHOWED: React
    ;; writes `:id` and `:x/id` into one JavaScript property, so a second id
    ;; spelling silently replaced the first while the structural tree
    ;; reported both. `#id` sugar occupies the slot once; beyond that the
    ;; attrs map may carry at most one id-slot key. By PRESENCE, never truth
    ;; — the compiled analyzer reads `(keys m)` and has no value to consult,
    ;; so a truth test here would accept a declaration `{:compiled true}`
    ;; refuses (rf2-5r1af, rf2-drpa3.101).
    (when-some [{:keys [message]} (conv/id-conflict tag sugar-id (keys attrs))]
      (malformed! message {:value (shape attrs)}))
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
      (cond
        (= :key k)   nil

        ;; A nil attribute is an ABSENT attribute — that is the law an
        ;; author relies on to write a conditional value — except on the
        ;; controlled slots, where absence is React's own signal for
        ;; UNCONTROLLED. An author who wrote `:value nil` declared a
        ;; controlled field with nothing in it, and the door has already
        ;; put the site on the synchronous lane for it; dropping the prop
        ;; here would hand React the opposite claim. React acts on that:
        ;; it warns the node changed control, and it keeps the value it
        ;; last rendered — so clearing a field left the old text on
        ;; screen. The exception is scoped exactly as the door is, and
        ;; read through the same slot projection (rf2-drpa3.120).
        (nil? raw)
        (when-some [empty-slot (controlled/empty-control-slot tag k multi?)]
          (gobj/set o (key empty-slot) (react-control-value (val empty-slot))))

        ;; The declaration outranks handler POSITION, so a declared
        ;; `:on-detail` falls to `put-plain-attr!` below and is set as the
        ;; `onDetail` property, while an undeclared one stays the native
        ;; event this walk has always made it (rf2-sv2oq).
        (conv/handler-key? declared k)
        (let [slot (conv/react-event-name k)]
          (when-some [proxy (handler-proxy cand tag controlled? slot raw)]
            (gobj/set o slot proxy)))

        ;; Composed once above, exact-first with any alias, so the walk skips
        ;; every class-slot key here (rf2-c9kus).
        (= :class k)
        nil

        ;; Likewise composed once above, so the walk skips every style-slot
        ;; key here rather than writing (and last-wins-overwriting) each
        ;; (rf2-8jqw7).
        (= :style k)
        nil

        :else (put-plain-attr! o tag k raw)))
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
  [(cond
     (descriptor/error-boundary? view) :error-boundary
     (descriptor/behavior? view)       :behavior
     :else                             (:lowering (descriptor/describe view)))
   (:view-cell (descriptor/manifest view))])

(defn- boundary-body
  "The body THIS emitter runs for `view`: the compiled tier's React
  lowering when the declaration carries one, and the interpreted walk's
  render body when it does not.

  Exactly one of the two exists per declaration — the lowerings are
  exclusive — so this is a selection, never a fallback. Both are read
  through the same slot, which is what gives the compiled tier the same
  hot-reload seam the interpreted tier has rather than a second one."
  [view]
  (or (descriptor/react-body view) (descriptor/render-body view)))

(defn- publish-body!
  "Publish `view`'s body into a stable boundary's `slot`, and advance
  that slot's BODY REVISION when the body is genuinely new.

  Identity is the test, because a redefinition mints a new body closure
  and re-walking an unchanged tree does not. So one render pass that
  reaches the same boundary at several call sites republishes nothing and
  advances nothing — the revision moves at the RELOAD seam, and a live
  candidate is never made stale by an ordinary walk."
  [slot view]
  (let [body (boundary-body view)]
    (when-not (identical? body (:body @slot))
      (vswap! slot (fn [s] (-> s (assoc :body body) (update :revision inc)))))
    nil))

(defn- interpreted-component
  "The React function component an INTERPRETED declaration lowers to: one
  atomic shell around whatever body the boundary's `slot` currently holds.

  The body is read from the slot on every render rather than closed over,
  which is what lets a compatible reload publish a new body through a
  component React is already reconciling."
  [view-id slot]
  (let [c (fn freehand-view [js-props]
            (let [{:keys [body revision]} @slot]
              (shell/render
                view-id
                revision
                :interpreted
                (fn [cand]
                  (cell/with-capture
                    cand
                    (fn []
                      ;; The OCCURRENCE SEAM. React renders each declared
                      ;; view in its own component, so a throw arriving here
                      ;; is THIS view's — the boundary that catches it several
                      ;; fibers above has no other way to learn whose, and the
                      ;; throwable carries no view identity. The note rides the
                      ;; thrown value, which is the one thing this render and
                      ;; that catch demonstrably share: React finishes
                      ;; rendering every failed subtree of a commit before it
                      ;; runs any `componentDidCatch`, so two failures are
                      ;; routinely in flight at once. The note also carries the
                      ;; PHASE, equally unknowable at the catch: a throw while
                      ;; CALLING the body is `:render`, and a throw while the
                      ;; emitter WALKS what the body returned — a lazy child
                      ;; realised by the walk — is `:normalize`.
                      (let [form (try
                                   (body (conv/forward-children
                                           (gobj/get js-props "props")))
                                   (catch :default e
                                     (eb/note-failing-view! e view-id :render)
                                     (throw e)))]
                        (try
                          (emit cand form)
                          (catch :default e
                            (eb/note-failing-view! e view-id :normalize)
                            (throw e))))))))))]
    (gobj/set c "displayName" (performance/entry-id view-id))
    c))

(defn- lowering-unavailable!
  [view-id]
  (error/throw-error!
    :rf.error/view-lowering-unavailable
    're-frame.freehand.react/element
    (str view-id " is declared {:compiled true} but carries no browser lowering — "
         "only the host-neutral structural one. A compiled declaration acquires "
         "its React lowering when the ClojureScript compiler expands it, so this "
         "descriptor was built by a JVM expansion and handed to a browser. Render "
         "it structurally, or drop the marker to mount it interpreted; the "
         "declaration is the only thing that changes.")
    {:recovery :render-structurally-or-drop-the-compiled-marker
     :extra    {:view-id view-id}}))

(defn- compiled-component
  "The React function component a `{:compiled true}` declaration lowers
  to — the SAME atomic shell the interpreted tier gets, or no shell at
  all when the declaration's own analysis proved it needs none.

  The wrapper is chosen by the manifest's `:view-cell` verdict and by
  nothing else. That verdict is a deterministic function of the analyzed
  sites, so the component React reconciles and the fact a tool reads off
  `v/manifest` are ONE fact rather than two that could drift:

  - `:present` — the body carries a subscription, a committed handler, a
    `dispatch-fn` or a frame read, so it renders inside
    [[re-frame.freehand.shell/render]] under
    [[re-frame.freehand.cell/with-capture]]. Its reads and its event
    sites are recorded on that render's candidate and published by the
    SELECTED commit, through the very same cell an interpreted body
    drives. There is no compiled reactive path.
  - `:elided` — the analysis PROVED the body has no reactive site, so
    the ViewCell, the frame-context read, the `useSyncExternalStore`
    bridge and both layout effects are omitted. That omission is what
    the compiled tier buys, and it is earned by proof rather than
    guessed: the interpreted shell can never elide, because an
    unrestricted body offers nothing to prove it with.

  The body is read from the boundary's `slot` on every render, exactly
  as the interpreted one is, so a compatible reload publishes a new
  compiled body through a component React is already reconciling."
  [view view-id slot]
  (let [reactive? (not= :elided (:view-cell (descriptor/manifest view)))
        run       (fn [body js-props]
                    ;; The OCCURRENCE SEAM — see `interpreted-component`.
                    ;; A compiled body throws for the same reasons an
                    ;; interpreted one does (an author's expression, a
                    ;; refused prop value), and the boundary that catches
                    ;; it several fibers above still has no other way to
                    ;; learn whose render it was.
                    (try
                      (body (conv/forward-children (gobj/get js-props "props")))
                      (catch :default e
                        (eb/note-failing-view! e view-id)
                        (throw e))))
        c (if reactive?
            (fn freehand-compiled-view [js-props]
              (let [{:keys [body revision]} @slot]
                (shell/render
                  view-id
                  revision
                  :compiled
                  (fn [cand]
                    (cell/with-capture cand (fn [] (run body js-props)))))))
            ;; The `:elided` compiled path renders straight — no ViewCell, no
            ;; shell — so its `rf:render:<view-id>` measure is bracketed HERE
            ;; rather than in `shell/render`. Same `:render` bucket, same
            ;; default-off / :advanced-DCE discipline (Spec 009 §Performance
            ;; instrumentation); the reactive paths are measured once in the
            ;; shell, so an elided view is the only render that would go
            ;; uncounted without this.
            (fn freehand-compiled-view [js-props]
              (performance/mark-and-measure :render view-id
                (run (:body @slot) js-props))))]
    (gobj/set c "displayName" (performance/entry-id view-id))
    c))

(defn- behavior-component
  "The React function component `v/behavior` lowers to.

  It is an ordinary atomic shell — the decorated element's event sites are
  this boundary's to own and publish, exactly as a forwarded child's are
  under any other declared view — with the behavior's LIFECYCLE ARMS
  installed above it and the node's ref given to the element the walk
  produced.

  `cloneElement` rather than a wrapper node, deliberately: a behavior owns
  the node its AUTHOR declared, so inserting one of the substrate's own
  would change the document that both the stylesheet and the host library
  address. And the ref rather than a `querySelector`: a behavior addresses
  the node React handed it and has no way to reach any other, which is what
  keeps the boundary one node wide.

  The lifecycle hooks run BEFORE the shell's, in a fixed order no
  declaration can move: the behavior's timing arms are guarded by the
  registered `:timing` rather than selected by it, so the hook skeleton is
  the same for a `:passive` and a `:layout` behavior and swapping one for
  the other at a live site is a re-connection, not a hook-order fault."
  [view-id]
  (let [c (fn freehand-behavior [js-props]
            (let [props    (gobj/get js-props "props")
                  opts     (behaviors/read-opts props)
                  set-node (behaviors/use-attachment! opts)]
              (shell/render
                view-id
                0
                :interpreted
                (fn [cand]
                  (cell/with-capture
                    cand
                    (fn []
                      ;; A behavior owns no body of its own — it WALKS the one
                      ;; decorated element it was handed. A throw here is that
                      ;; walk realising a lazy child, so it is `:normalize`.
                      (try
                        (behaviors/attach (emit cand (:child opts)) set-node)
                        (catch :default e
                          (eb/note-failing-view! e view-id :normalize)
                          (throw e)))))))))]
    (gobj/set c "displayName" (performance/entry-id view-id))
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
      (let [slot (volatile! {:body (boundary-body view) :revision 0})
            c    (cond
                   ;; An error boundary is not an ordinary function component
                   ;; — a function component cannot catch a descendant's
                   ;; render throw. It lowers to the React class boundary the
                   ;; error-boundary law drives (capture, the
                   ;; once-per-generation intent, reset, the private egress),
                   ;; built once and cached like any other declared view's
                   ;; component.
                   (descriptor/error-boundary? view)
                   (error-react/boundary-component element)

                   ;; A behavior boundary has no body to walk either: it
                   ;; CONTAINS one declared element and owns that element's
                   ;; imperative lifecycle, so it lowers to the component
                   ;; that holds the node's ref and the timing arms.
                   (descriptor/behavior? view)
                   (behavior-component view-id)

                   ;; A COMPILED declaration carries no interpreted body to
                   ;; walk. Its markup was resolved at build time into the
                   ;; React lowering the slot now holds, and the wrapper
                   ;; around it is the one its own manifest verdict names.
                   (descriptor/structural-body view)
                   (do (when (nil? (:body @slot)) (lowering-unavailable! view-id))
                       (compiled-component view view-id slot))

                   :else
                   (interpreted-component view-id slot))]
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
        authored  (if attrs? (first args) {})
        ;; `(v/spread-safe owned caller)` answers the OWNED map with the guarded
        ;; caller riding under the reserved carrier key, because the fold — caller
        ;; under owned, `:class` composing owned-first — belongs to one place and
        ;; not to either front end. Left in the attribute map it is not an
        ;; attribute at all: it reached the plain-attribute path and refused the
        ;; whole element, so `v/spread-safe` did not render in an interpreted
        ;; browser view. Split through the ONE splitter the structural walk uses,
        ;; so an authored key of the same name is refused identically on both.
        [attrs
         caller]  (node/split-caller authored)
        kid-forms (if attrs? (rest args) args)
        ;; The controlled-input door is a property of the WHOLE element:
        ;; presence of a `value`/`checked` SLOT is the test — values are
        ;; irrelevant (an explicit nil is still controlled), and the slot is
        ;; what an alias resolves to, so `:x/value` counts exactly as
        ;; `:value` does. BOTH writers of this element's props need the
        ;; verdict, so it is settled once here.
        ;;
        ;; It used to be settled twice — once inside `react-props` and once
        ;; beside the caller fold — and the second was paid by every element
        ;; whether or not it had a caller at all, because the argument was
        ;; built before `put-caller!` could decline it. A CPU profile of the
        ;; interpreted walk in a production browser put 4.30% of a W1 mount
        ;; under `controlled-props?`, split 2.29 / 2.01 between the two
        ;; (rf2-xu6rx). One question per element, asked once.
        controlled? (controlled/controlled-props? (keys attrs))
        ;; The DOM top layer's desired-state pair never reaches React as a
        ;; prop — an emitter drops the namespace that is the whole of its
        ;; meaning — so it is withheld from the props and installed as the
        ;; commit-time host call instead.
        props     (top-layer/install! (react-props cand tag classes id (top-layer/without attrs)
                                                   caller controlled?)
                                      tag attrs)
        props     (put-caller! props tag
                               (fn [slot raw] (handler-proxy cand tag controlled? slot raw))
                               caller)
        ;; `(v/html s)` is the element's CONTENT and reaches React as
        ;; `dangerouslySetInnerHTML`, so there are no positional children beside
        ;; it and React's children-vs-innerHTML conflict cannot arise. The
        ;; position law is the structural walk's and the compiled analyzer's,
        ;; spelled identically: the SOLE child, directly. Anything else reaches
        ;; `children` below and is refused by the ONE shared sentence.
        markup    (when (and (= 1 (count kid-forms))
                             (node/trusted-markup? (first kid-forms)))
                    (node/trusted-markup-string (first kid-forms)))
        props     (cond-> props markup (put-html! tag markup))
        kids      (if markup [] (children cand kid-forms))]
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

(defn- client-only-node
  "A `(v/client-only …)` boundary in interpreted markup — `[client-only-tag
  opts child]`. BOTH arms are walked into React elements HERE, under the
  candidate the enclosing body is rendering on, and handed to the
  phase-conditional boundary that picks one.

  Walking both is what makes the client subtree's event sites belong to the
  body that wrote them: the boundary renders later, in its own component,
  where there is no candidate to record against. Building an element is not
  rendering it — the arm the phase does not select is a value React never
  looks at — so the unselected arm costs one vdom construction and runs
  nothing."
  [cand form]
  (phase/boundary (emit cand (:fallback (second form)))
                  (emit cand (nth form 2))))

;; ---------------------------------------------------------------------------
;; The host crossing — the browser half of `v/defhost`
;; ---------------------------------------------------------------------------

(defn- host-props
  "The React props object for one host crossing: the ordinary plane
  SHALLOWLY and EXACTLY as authored, then the declared callback positions.

  No `conv/attr-key`, no `.class#id` sugar, no controlled-input door and no
  attribute grammar — none of that is a fact about a foreign component's
  props. `:selected` reaches React as `selected`, and a name the library
  spells `onChange` is written `:onChange`. D022: \"There is no automatic
  case conversion, deep Clojure-to-JavaScript conversion, callback
  inference, or per-prop conversion language.\"

  Both planes are named by `descriptor/host-prop-name`, the SINGLE crossing
  projection, and the two planes cannot collide on one name: the ordinary
  plane has had the declared positions removed from it, and every key in
  either plane is an unqualified keyword — the exactness
  `descriptor/inexact-host-prop-names` enforces on the caller's map, which
  a `:map-props` adapter's answer inherits by carrying the caller's own
  keys (`descriptor/host-prop-name-drift`). So this writer never has to ask
  what a name might already mean."
  [cand ordinary callbacks]
  (let [o (js-obj)]
    (reduce-kv (fn [_ k v] (gobj/set o (descriptor/host-prop-name k) v) nil)
               nil ordinary)
    (reduce-kv
      (fn [_ k carrier]
        (gobj/set o (descriptor/host-prop-name k)
                  (if (some? cand)
                    ;; The site key is this walk's ordinal, exactly as an
                    ;; element handler's is — so the position keeps ONE proxy
                    ;; across re-renders and gets D008's committed body, frame
                    ;; retargeting, abandoned-render silence and retirement.
                    (events/site (cell/candidate-events cand)
                                 (cell/next-site-key! cand)
                                 carrier)
                    ;; No declared boundary above this host, so no commit a
                    ;; site could belong to. The element path degrades the same
                    ;; way: hand over the authored function, with none of the
                    ;; identity claims a site would have carried.
                    (events/callback-fn carrier)))
        nil)
      nil callbacks)
    o))

(defn- host-element
  "The real React element for a host crossing — the registered component,
  its props, and the caller's children as ordinary React children in that
  component's own tree (so the tree's context reaches them)."
  [cand head {:keys [props callbacks] kid-forms :children}]
  (let [entry     (descriptor/host-entry head)
        host-id   (:host-id entry)
        component (:component entry)
        mapper    (:map-props entry)
        ordinary  (if mapper (mapper props) props)]
    (when (nil? component)
      (malformed!
        (str "The host " host-id " registered no React component. A host declaration "
             "carries its component only on a ClojureScript expansion — there is no "
             "React on the JVM — so this descriptor was built by a Clojure load of "
             "the declaring namespace and cannot cross in the browser. Require the "
             "declaring namespace from ClojureScript.")
        {:host host-id}))
    (when-not (map? ordinary)
      (malformed!
        (str "The :map-props adapter on " host-id " answered a "
             (type-name ordinary) ". It prepares the ordinary-props MAP and must "
             "answer one — it is not a place to build the element.")
        {:host host-id}))
    ;; The adapter's KEY SET is the caller's — the one law the answer owes,
    ;; enforced here because this is the only place both maps exist. It is
    ;; the whole law: `props` has already had `:key` and the declared
    ;; positions removed, `:children` and every inexact spelling refused at
    ;; the call, so an answer carrying the caller's own keys cannot name a
    ;; reserved fact or an unspellable one, and there is no filter left to
    ;; make total.
    (when mapper
      (let [{:keys [created dropped]} (descriptor/host-prop-name-drift props ordinary)]
        (when (or (seq created) (seq dropped))
          (malformed!
            (str "The :map-props adapter on " host-id " answered a different KEY SET "
                 "than the call authored"
                 (when (seq created) (str " — created " (pr-str created)))
                 (when (seq dropped)
                   (str (if (seq created) ", dropped " " — dropped ") (pr-str dropped)))
                 ". The adapter prepares the ordinary plane's VALUES, which is why "
                 "it exists at all: it builds what the authored map could not hold. "
                 "The names stay the caller's, so it answers the caller's OWN keys. "
                 "A name it invents is a rename no call site can see — it reaches "
                 "React under a name nobody authored, and it puts :key, :children "
                 "and every declared callback position back within reach of a plane "
                 "they were withheld from. A name it drops leaves the structural "
                 "tree, which records the AUTHORED props, reporting a prop React "
                 "never received. Prepare the value under the caller's key; a host "
                 "whose props want renaming renames them inside the registered "
                 "React component, where it is ordinary React code.")
            {:host host-id :created created :dropped dropped}))))
    (apply react/createElement
           component
           (host-props cand ordinary callbacks)
           (children cand kid-forms))))

(defn- host-node
  "A `v/defhost` crossing in interpreted markup.

  The SSR policy is realised by the SAME phase-conditional boundary
  `v/client-only` uses, because it is the same fact: the registered React
  component never ran on the server, so the hydration-phase render must
  produce what the server produced — the declared fallback, or nothing —
  and the post-hydration render produces the component. Reusing
  `phase/boundary` is what keeps a host from being a second, quieter
  hydration protocol."
  [cand head form]
  (let [entry    (descriptor/host-entry head)
        call     (descriptor/normalize-host-call head (rest form))
        ssr      (:ssr entry)
        el       (host-element cand head call)
        outer    (if (= :fallback (descriptor/host-ssr-spelling ssr))
                   (phase/boundary (emit cand (:fallback ssr)) el)
                   (phase/boundary nil el))]
    ;; The key rides the OUTER element, because that is the one that sits in
    ;; the parent's children array and is what React reconciles siblings by.
    ;; Keying the inner host element instead would key a value React only
    ;; ever sees as the phase boundary's single child — legal, and useless.
    ;; Key PRESENCE, not key truth: `normalize-host-call` reports `:keyed?`
    ;; because React string-coerces a supplied key, so an explicit nil is the
    ;; identity "null" and a different authored fact from no key at all.
    (if (:keyed? call)
      (react/cloneElement outer #js {:key (:key call)})
      outer)))

(defn- node
  [cand form]
  (let [head (first form)]
    (cond
      (= tree/fragment-tag head)
      (fragment-node cand (rest form))

      (= descriptor/presence-tag head)
      (presence-node cand form)

      (= descriptor/client-only-tag head)
      (client-only-node cand form)

      :else
      (case (descriptor/classify-head head)
        :element (element-node cand head (rest form))
        ;; A nested boundary is a React ELEMENT this walk does not enter:
        ;; the child renders later, in its own component, under its own
        ;; candidate. That is why a candidate can never collect a
        ;; descendant boundary's sites.
        :view    (boundary-node head (rest form))
        :host    (host-node cand head form)))))

(defn- collect
  "Fold one child value into the children accumulator.

  `walk?` is the LANGUAGE BOUNDARY. The interpreted front end passes true:
  a vector is markup, and it is walked into a React element. The compiled
  tier's child seam ([[child-elements]]) passes false: a compiled body
  resolved its structure at build time and produces no markup, so a runtime
  vector is a value with nowhere to go — D010 gives the compiled tier no
  dynamic-markup valve and no interpreter to walk one — and it is refused by
  the SAME [[re-frame.freehand.node/opaque-child!]] sentence the structural
  children canonicalizer raises, never interpreted here. That is what keeps
  a compiled view from acquiring an interpreter through its browser
  lowering (rf2-drpa3.130)."
  ([cand acc form] (collect cand true acc form))
  ([cand walk? acc form]
   (cond
     (nil? form)     acc
     (boolean? form) acc
     (string? form)  (conj acc form)
     (number? form)  (conj acc (conv/js-number-str form))
     (conv/child-run? form) (reduce (fn [a f] (collect cand walk? a f)) acc form)
     ;; Trusted markup that reached the CHILD fold is trusted markup with no
     ;; element to own it — `element-node` reads the legal position off
     ;; `kid-forms` and never routes it here. Refused by the SHARED sentence
     ;; the structural walk raises, so a `(v/html …)` beside a sibling, inside
     ;; a run, or at a view's root answers one diagnostic in every mode on
     ;; every host.
     (node/trusted-markup? form)
     (node/refuse-orphan-trusted-markup! 're-frame.freehand.react/element form)
     (vector? form)
     (if walk?
       ;; `^{:key …}` metadata is refused by the SHARED sentence the structural
       ;; walk raises, not by a second one here — one source has one answer,
       ;; and a key that reached React through a spelling the JVM tree drops
       ;; would be exactly the divergence this walk exists to not have.
       (do (node/refuse-metadata-key! 're-frame.freehand.react/element form)
           (conj acc (node cand form)))
       (node/opaque-child! 're-frame.freehand/render form))
     (seq? form)     (reduce (fn [a f] (collect cand walk? a f)) acc form)
     ;; A React element in a child position is markup a COMPILED body
     ;; already lowered — the shape a compiled parent forwards to an
     ;; interpreted child. It is finished work, so it passes through
     ;; untouched: this walk has nothing left to decide about it, and
     ;; entering it is not possible in any case.
     (react/isValidElement form) (conj acc form)
     :else
     (if walk?
       (malformed!
         (str "A view body produced a " (type-name form) " where a child was expected. "
              "A child is markup (a vector), text (a string or a number), a seq of "
              "children, or nothing (nil / false).")
         {:value (shape form)})
       (node/opaque-child! 're-frame.freehand/render form)))))

(defn- children
  ([cand forms] (children cand true forms))
  ([cand walk? forms]
   (reduce (fn [acc f] (collect cand walk? acc f)) [] forms)))

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

;; ---------------------------------------------------------------------------
;; The two seams the compiled tier reaches this walk through
;; ---------------------------------------------------------------------------
;;
;; A compiled body resolved its markup at build time, but two things stay
;; runtime facts even then: what an arbitrary value in a child position
;; denotes, and how a declared boundary is crossed. Both already have one
;; implementation here, and the compiled tier calls it rather than
;; growing a second — which is what makes cross-mode agreement a
;; structural property instead of a pair of walks somebody has to keep in
;; step.

(defn ^:no-doc child-elements
  "Classify ONE runtime value in a COMPILED body's child position, and
  answer the React children it denotes, in order.

  A compiled body resolved its markup at build time, so a runtime value
  here is text, a number, nothing, an already-lowered React child, or a
  run of those — never a template. A runtime VECTOR is refused by name:
  D010 gives the compiled tier no dynamic-markup valve and no interpreter
  to walk one, so a Hiccup value in a compiled child position raises the
  same [[re-frame.freehand.node/opaque-child!]] refusal the structural
  children canonicalizer raises, rather than being silently interpreted
  here (rf2-drpa3.130). No candidate is threaded, because with no walk
  there is nothing to open or record against."
  [form]
  (children nil false [form]))

(defn ^:no-doc mount-view
  "Cross a declared boundary from a compiled body. `args` is the call's
  props map followed by its children, exactly as the interpreted walk
  hands them over — so one `normalize-call` and one stable component
  cache serve both modes."
  [view args]
  (boundary-node view args))
