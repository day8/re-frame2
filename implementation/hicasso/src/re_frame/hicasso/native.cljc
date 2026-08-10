(ns re-frame.hicasso.native
  "**THE NATIVE TIER** (rf2-hic-030) — explicit React, past the fence.

  Most applications never require this namespace. Ordinary Hicasso —
  hiccup, `h/sub` where you read, events as data — is the product; this
  is the escape hatch for the thin slice that is not, and it is
  deliberately too small to become a second component framework.

      (ns app.hot-row
        (:require [re-frame.hicasso.native :as n]))

      (n/defcomponent hot-row
        {:server :render}
        [^js props]
        (n/$ :button {:class \"hot-row\" :on-click (.-onOpen props)}
             (.-label props)))

  ## The two-languages fence, which is the whole architecture

  `[...]` is ALWAYS interpreted hiccup. `n/$` is ALWAYS native React.
  Neither form ever changes the other's meaning, and the enforcement is
  structural rather than disciplinary: [[$]] is an ordinary macro that
  reads its **own form** and nothing else. It never analyses a `defview`
  body, so there is no code path by which a native decision could reach
  a hiccup vector, and none by which hiccup semantics could reach a
  native prop — the same source shape means opposite things on the two
  sides, and the pair `[:div {:on-click [:x/go]}]` (lowered) against
  `(n/$ :div {:on-click [:x/go]})` (refused) is the fence measured in one
  breath.

  Past the fence there is **no intent lowering, no class-collection
  merge, no style-map conversion, no keyword-value conversion, no
  controlled-field repair and no deep conversion**. Prop values pass by
  identity. Where a native API wants a JavaScript object — React style
  objects included — hand it one.

  ## What the macro normalises, and it is only spelling

  A ClojureScript props map's KEYS are lowered to React slot names by
  [[re-frame.hicasso.impl.slot/prop-name]] — the same pure `.cljc`
  function the hiccup codec and the `[:>]` migration codemod ask. That
  is the whole of the shared rule and there is no second copy of it: the
  macro calls [[prop-slots]] on a literal map at expansion, and
  [[props*]] calls the same [[prop-slots]] on a dynamic map at runtime,
  so the two paths cannot answer differently about a key. A raw
  JavaScript object is not renamed at all; it already carries React's
  own names.

  ## The props operand is decided SYNTACTICALLY, and that is load-bearing

  A React element is itself a JavaScript object, so a macro that
  inspected a runtime value to decide *props or child* would classify a
  dynamic element as props. [[$]] therefore treats exactly four written
  forms as the props operand — `nil`, a literal ClojureScript map, a
  `#js` object literal, and the explicit `(n/props expression)` marker —
  and **every other trailing form is a child**. The cost is one rule the
  author has to know; the return is that no runtime value can be
  misclassified, ever.

  ## Refusals

  Every refusal is minted by [[re-frame.hicasso.impl.error/fail!]], so
  it carries rf2-hic-007's shape whole — id, the fn that refused, the
  reason, an actionable recovery, and the ambient view and coordinate
  when a declaration extent is open. Five ids, all of them reserved for
  this tier by the complaint register before it existed:

  | Id | Raised when |
  |---|---|
  | `:rf.error/hicasso-native-map-as-child` | a ClojureScript map reaches child position — the unmarked dynamic props operand |
  | `:rf.error/hicasso-native-hiccup-child` | a hiccup vector reaches child position, where brackets have no meaning |
  | `:rf.error/hicasso-native-intent-in-prop` | an event vector is written at a native callback slot |
  | `:rf.error/hicasso-native-children-in-props` | `:children` is written in a props map, which has one child channel |
  | `:rf.error/hicasso-native-slot-collision` | two source keys normalise to one React slot |

  The first two fire at EXPANSION for a literal and at RUNTIME for a
  dynamic value; the runtime half is `debug-enabled?`-gated, so under
  `:advanced` + `goog.DEBUG=false` the per-child check folds away and
  React's own diagnostics stand in its place. The last three are
  properties of a props map, so they hold on both paths unconditionally
  — a slot collision resolved by map order is a wrong program that no
  build should quietly run.

  ## Dependency isolation

  This namespace is separately reachable and nothing in `re-frame.hicasso`
  requires it, so an application that never asks for the native tier
  carries none of it. [[tier-sentinel]] is the unique string a bundle
  scan looks for; it is used as the marker property name on every
  component [[defcomponent]] mints, so it is REACHABLE rather than a
  dead literal an optimiser could delete out from under the proof."
  (:require [re-frame.hicasso.impl.error :as error]
            [re-frame.hicasso.impl.slot :as slot])
  #?(:clj (:require [re-frame.source-coords :as source-coords]))
  #?(:cljs (:require ["react" :as react]
                     [re-frame.interop :as interop]))
  #?(:cljs (:require-macros [re-frame.hicasso.native :refer [$ props defcomponent]])))

;; ---------------------------------------------------------------------------
;; The shared rule — one implementation, two hosts, two call times
;; ---------------------------------------------------------------------------
;;
;; Everything in this section is host-neutral on purpose. The `:clj` side
;; runs it during macro expansion, over literal FORMS; the `:cljs` side
;; runs it at render time, over VALUES. A copied algorithm here would be
;; the exact defect `impl/slot` was extracted to delete, one layer up.

(def ^:private children-slot
  "The one React slot a native props map may not carry. Children are the
  trailing operands of [[$]] and there is one channel for them, so a
  `:children` prop is an author writing into a door that does not exist
  rather than a second, quieter way in."
  "children")

(defn- event-slot?
  "Does this React slot name a callback position? `onClick`, `onChange`,
  `onPointerDown` — React's own `on` + capital convention, which for an
  intrinsic element is not a guess but the ABI.

  Asked ONLY of a prop whose value is already an intent-shaped vector,
  so the regular expression never runs on the ordinary key."
  [s]
  (some? (re-find #"^on[A-Z]" s)))

(defn- intent-shaped?
  "A vector whose head is a keyword — the shape of a re-frame event
  vector, and the one vector shape worth refusing at a callback slot.

  Deliberately not *every* vector: prop values pass by identity here, so
  `{:items [1 2 3]}` at a ClojureScript component head is ordinary data
  and refusing it would be the native tier inventing a semantics it just
  promised not to have."
  [v]
  (and (vector? v) (keyword? (first v))))

(defn prop-slots
  "**THE shared props rule.** Answer `[[react-slot value] …]` for the
  ClojureScript props map `m`, in source order, refusing the three
  things a native props map may not say.

  `where` is the symbol that goes on any refusal — [[$]] when a literal
  map is lowered at expansion, [[props*]] when a dynamic one is
  converted at render. Public because it is the rule the macro and the
  runtime SHARE rather than reproduce, and because the parity corpus
  drives it directly: a fixture that could only reach it through one of
  the two callers would be pinning that caller, not the rule."
  [m where]
  (loop [kvs   (seq m)
         pairs (transient [])
         seen  (transient {})]
    (if-some [kv (first kvs)]
      (let [k (key kv)
            v (val kv)
            s (slot/prop-name k)]
        (cond
          (= children-slot s)
          (error/fail! :rf.error/hicasso-native-children-in-props where
                       (str "The native props map carries " (pr-str k)
                            ", but a native form has one child channel: children are "
                            "the trailing operands of `n/$`.")
                       :pass-children-after-the-props-operand
                       {:prop k})

          (get seen s)
          (error/fail! :rf.error/hicasso-native-slot-collision where
                       (str "The native props map carries both " (pr-str (get seen s))
                            " and " (pr-str k) ", which normalise to the one React slot "
                            (pr-str s) " — so map order, not the source, would pick the winner.")
                       :keep-one-spelling-per-react-slot
                       {:slot s :prop k :collides-with (get seen s)})

          (and (intent-shaped? v) (event-slot? s))
          (error/fail! :rf.error/hicasso-native-intent-in-prop where
                       (str "An event vector is written at the native callback slot "
                            (pr-str s) ". Past the native fence nothing lowers it — React "
                            "would be handed the vector itself.")
                       :write-a-function-at-a-native-callback
                       {:prop k :slot s :value v})

          :else
          (recur (next kvs) (conj! pairs [s v]) (assoc! seen s k))))
      (persistent! pairs))))

(defn check-child!
  "Answer `c`, or refuse it. The two ClojureScript carriers that cannot
  be a React child are exactly the two the fence is about: a map, which
  is what an unmarked dynamic props operand becomes, and a vector, which
  is hiccup written where brackets have no meaning.

  Everything else passes — strings, numbers, nil, React elements, and
  ClojureScript seqs, which are ES6-iterable and are therefore React
  children already."
  [c where]
  (cond
    (map? c)
    (error/fail! :rf.error/hicasso-native-map-as-child where
                 (str "A ClojureScript map reached a native child position. `n/$` "
                      "classifies its operands syntactically, so a dynamic map written "
                      "in the props position is a CHILD — an element is a JavaScript "
                      "object too, and guessing would misclassify one.")
                 :mark-the-props-operand-with-n-props
                 {:child c})

    (vector? c)
    (error/fail! :rf.error/hicasso-native-hiccup-child where
                 (str "A ClojureScript vector reached a native child position. Hiccup is "
                      "not interpreted past the native fence — square brackets have no "
                      "meaning here.")
                 :nest-n-dollar-or-convert-with-h-as-element
                 {:child c})

    :else c))

;; ---------------------------------------------------------------------------
;; The runtime — CLJS only, because construction is `React.createElement`
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def tier-sentinel
       "**The native tier's production sentinel** (rf2-hic-034 owns the
  proof; this bead plants it).

  A unique string that appears in a bundle if and only if this namespace
  is reachable from it. It is the marker property name [[component]]
  stamps rather than a bare literal, so `:advanced` cannot delete it
  while leaving the tier in — a sentinel an optimiser can elide proves
  nothing about the build it was supposed to describe."
       "rf2:hicasso-native-tier")

     (defn- checked
       "The per-child fence, dev-gated. Under `:advanced` +
  `goog.DEBUG=false` the whole call folds to `c` and [[check-child!]]
  becomes unreachable, so the hot path pays nothing and React's own
  \"Objects are not valid as a React child\" stands in its place."
       [c]
       (if interop/debug-enabled?
         (check-child! c 're-frame.hicasso.native/$)
         c))

     (defn el
       "`React.createElement`, reached from an [[$]] expansion.

  The macro knows the child count statically, so the common shapes are
  fixed arities and only six-or-more children pay for an array. Not a
  consumer surface: write [[$]], which is the form the fence is defined
  on."
       ([type props] (react/createElement type props))
       ([type props a] (react/createElement type props (checked a)))
       ([type props a b] (react/createElement type props (checked a) (checked b)))
       ([type props a b c]
        (react/createElement type props (checked a) (checked b) (checked c)))
       ([type props a b c d & more]
        (.apply (.-createElement react) nil
                (.concat #js [type props (checked a) (checked b) (checked c) (checked d)]
                         (into-array (map checked more))))))

     (defn props*
       "Convert a DYNAMIC props operand. A ClojureScript map is converted
  shallowly under [[prop-slots]] — the same rule the macro applies to a
  literal — and everything else, which is to say a JavaScript object,
  passes by identity because it already carries React's own names.

  The carrier is prototype-free. A literal map's keys are source the
  author wrote; a dynamic map's keys are DATA, and a key spelled
  `__proto__` on an ordinary object literal writes the prototype rather
  than a property. `Object.create(nil)` makes that unrepresentable at no
  cost, and React reads own properties, so nothing downstream notices."
       [x]
       (if (map? x)
         (let [o (js/Object.create nil)]
           (doseq [[s v] (prop-slots x 're-frame.hicasso.native/props)]
             (unchecked-set o s v))
           o)
         x))

     (defn component
       "Mint what [[defcomponent]] `def`s: the author's function, stamped
  with its `displayName` and the tier marker, and answered by identity.

  **Allocation, never a lookup by name**, and that is the HMR contract
  (rf2-hic-015) rather than an implementation detail. A reload
  re-evaluates the module, this runs again, and the element type at that
  position is a NEW object — so React replaces the subtree outright and
  the default expectation across a save is a clean remount. A component's
  name is an address, not an identity, exactly as `defview`'s is and as a
  frame's public keyword is. Caching by name here would preserve identity
  across a reload and quietly contradict the recorded contract."
       [component-name server f]
       (set! (.-displayName f) component-name)
       (unchecked-set f tier-sentinel #js {:name component-name :server (name server)})
       f)

     (defn marker
       "The tier marker [[component]] stamped, or nil. The seam every ABI
  helper and every embedding direction reads to recognise a native head —
  and the reason a raw `react/memo` wrapper loses it."
       [x]
       (when (some? x) (unchecked-get x tier-sentinel)))))

;; ---------------------------------------------------------------------------
;; The macros
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- js-literal?
     "Is `form` a `#js` tagged literal? Answered by class name rather than
  by requiring `cljs.tagged-literals`, which would put ClojureScript on
  this artefact's JVM classpath for one predicate."
     [form]
     (and (some? form)
          (= "cljs.tagged_literals.JSValue" (.getName (class form))))))

#?(:clj
   (defn- macro-target
     "Resolve `sym`, as written in the consumer's namespace, to the
  fully-qualified symbol it names — using the analyser's own alias maps
  rather than a name match, so `(n/props …)`, `(props …)` under a
  `:refer`, and the fully-spelled form are one answer and a consumer's
  unrelated `props` is not."
     [env sym]
     (if-some [ns-map (:ns env)]
       (if-some [alias (namespace sym)]
         (symbol (name (get (:requires ns-map) (symbol alias) (symbol alias)))
                 (name sym))
         (when-some [full (or (get (:use-macros ns-map) (symbol (name sym)))
                              (get (:uses ns-map) (symbol (name sym))))]
           (symbol (name full) (name sym))))
       sym)))

#?(:clj
   (defn- props-marker?
     "Is `form` the explicit `(n/props …)` operand marker?"
     [env form]
     (and (seq? form)
          (symbol? (first form))
          (= 're-frame.hicasso.native/props (macro-target env (first form))))))

#?(:clj
   (defn- head-form
     "The `createElement` type for a written head. An unqualified keyword
  names an intrinsic element and lowers to its name; a string is an
  intrinsic or custom element verbatim; anything else is an expression
  that must evaluate to a native React component.

  There is no selector shorthand: `:div.card` is not parsed, because the
  v0 native grammar has no such production. Spell class and id as props."
     [head]
     (if (and (keyword? head) (nil? (namespace head)))
       (name head)
       head)))

#?(:clj
   (defn- literal-props
     "Lower a literal ClojureScript props map at expansion, through the
  same [[prop-slots]] the runtime path uses. An empty map emits `nil`
  rather than an empty object, so `{}` and `nil` are one element."
     [m]
     (let [pairs (prop-slots m 're-frame.hicasso.native/$)]
       (when (seq pairs)
         `(cljs.core/js-obj ~@(apply concat pairs))))))

#?(:clj
   (defn- split-operands
     "Split `n/$`'s trailing operands into `[props-form children]`.

  **Syntactic, and exhaustively so.** Four written forms are props;
  everything else is a child. Nothing here inspects a runtime value,
  which is what keeps a dynamic React element from ever being mistaken
  for a props map."
     [env operands]
     (let [x (first operands)]
       (cond
         (empty? operands)     [nil nil]
         (nil? x)              [nil (next operands)]
         (map? x)              [(literal-props x) (next operands)]
         (js-literal? x)       [x (next operands)]
         (props-marker? env x) [x (next operands)]
         :else                 [nil operands]))))

#?(:clj
   (defmacro props
     "**Mark a dynamic props operand.** The marker itself emits no
  component and no wrapper — it is how [[$]] is TOLD that a form is
  props, and its expansion is the shallow conversion and nothing else.

      (let [cell {:class \"px\" :dir \"ltr\"}]
        (n/$ :td (n/props cell) px))

  Without it the same map is a child, because the props operand is
  decided syntactically (see the namespace docstring), and a
  ClojureScript map is not a React child — so the mistake refuses with
  `:rf.error/hicasso-native-map-as-child` and the recovery names this
  form.

  A ClojureScript map converts shallowly under the shared slot rule; a
  JavaScript object passes by identity."
     [x]
     `(props* ~x)))

#?(:clj
   (defmacro $
     "**Construct a native React element.** The one native authoring
  form, and the whole of the v0 grammar:

      (n/$ head)
      (n/$ head child*)
      (n/$ head literal-props child*)
      (n/$ head (n/props dynamic-props) child*)

  Heads: an unqualified keyword is an intrinsic element, a string is an
  intrinsic or custom element verbatim, any other expression must
  evaluate to a native React component. Props: `nil`, a literal map, a
  `#js` literal, or the [[props]] marker — **every other trailing form
  is a child**. Children are trailing ReactNode values; nest with `n/$`.

  `:key` and `:ref` use React's ordinary slots. Prop values pass by
  identity: there is no intent lowering, no style-map conversion and no
  controlled repair past this fence. See the namespace docstring for the
  refusal roster and for why the props rule is syntactic."
     [head & operands]
     (let [[props-form children] (split-operands &env operands)]
       `(el ~(head-form head)
            ~props-form
            ~@(map #(check-child! % 're-frame.hicasso.native/$) children)))))

#?(:clj
   (defmacro defcomponent
     "**Define a native React function component.**

      (n/defcomponent hot-row
        {:server :render}
        [^js props]
        (n/$ :button {:class \"hot-row\"} (.-label props)))

  The ABI is **one raw JavaScript props object**; React children arrive
  at `.-children`. Nothing here allocates a ClojureScript map merely so
  a body can destructure, and there is no second \"fast\" ABI anywhere in
  the tier. Ordinary React hooks are legal in the body through direct
  `[\"react\" :as react]` interop — this is a real function component and
  React's rules of hooks are the rules.

  An optional declaration map before the argument vector carries the
  server policy — `{:server :render}` or `{:server :client-only}` — and
  omitting it means Client-only.

  Like `defview` this is not a compiler: it reads no body, expands to a
  `def`, and captures the name and the source coordinate (rf2-hic-007)
  so every refusal raised while the declaration is minted names the
  file and line that is wrong. The extent closes in a `finally`, so a
  declaration that throws does not leave its own name ambient for
  whatever an HMR runtime keeps rendering afterwards.

  **HMR conduct is `defview`'s, unchanged** (rf2-hic-015): a reload
  re-evaluates the module, [[component]] allocates a fresh function, the
  element type at that position changes, and React replaces the subtree.
  A clean remount is the default expectation across a save, never
  preservation — see [[component]]."
     [sym & more]
     (let [doc      (when (string? (first more)) (first more))
           more     (if doc (next more) more)
           decl     (when (map? (first more)) (first more))
           [argv & body] (if decl (next more) more)
           cname    (str (ns-name *ns*) "/" sym)
           coord    (source-coords/coords-form (meta &form) *file* (ns-name *ns*))
           server   (get decl :server :client-only)]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~cname ~coord))
            (try
              (component ~cname ~server (fn ~argv ~@body))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))
