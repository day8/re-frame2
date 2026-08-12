(ns re-frame.hicasso.native
  "**THE NATIVE TIER** (rf2-hic-030) — explicit React, past the fence.

  Most applications never require this namespace. Ordinary Hicasso —
  hiccup, `h/sub` where you read, events as data — is the product; this
  is the escape hatch for the thin slice that is not, and it is
  deliberately too small to become a second component framework.

      (ns app.hot-row
        (:require [re-frame.hicasso.native :as n]))

      (n/defcomponent hot-row
        {:server :render}                 ; runs on the server too
        [^js props]
        (n/$ :button {:class \"hot-row\" :on-click (.-onOpen props)}
             (.-label props)))

  The declaration map carries `:server` and nothing else, its key and
  its value are both refused off their rosters at the declaration, and
  the policy **decides what the component contributes to a server
  render** (rf2-hic-046): `:render` mints the author's own function as
  the element type and it runs everywhere, `:client-only` — the default
  — mints a gate that contributes nothing to the server response and
  renders the component once the client has adopted it. See
  [[component]] for the mechanism and [[declared-server]] for the
  roster.

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
  when a declaration extent is open. Seven ids — five reserved for this
  tier by the complaint register before it existed, plus the two the
  declaration door minted when it gained its rosters (rf2-u9lk):

  | Id | Raised when |
  |---|---|
  | `:rf.error/hicasso-native-map-as-child` | a ClojureScript map reaches child position — the unmarked dynamic props operand |
  | `:rf.error/hicasso-native-hiccup-child` | a hiccup vector reaches child position, where brackets have no meaning |
  | `:rf.error/hicasso-native-intent-in-prop` | an event vector is written at a native callback slot |
  | `:rf.error/hicasso-native-children-in-props` | `:children` is written in a props map, which has one child channel |
  | `:rf.error/hicasso-native-slot-collision` | two source keys normalise to one React slot |
  | `:rf.error/hicasso-native-unknown-option` | a `defcomponent` declaration map carries a key outside `#{:server}` |
  | `:rf.error/hicasso-native-bad-server-policy` | `:server` carries a value outside `#{:client-only :render}` |

  The first two fire at EXPANSION for a literal and at RUNTIME for a
  dynamic value; the runtime half is `debug-enabled?`-gated, so under
  `:advanced` + `goog.DEBUG=false` the per-child check folds away and
  React's own diagnostics stand in its place. The last three are
  properties of a props map, so they hold on both paths unconditionally
  — a slot collision resolved by map order is a wrong program that no
  build should quietly run.

  ## The two hooks, and the frame they join

  An island is a place in the application, not a second application, so
  [[use-sub]] and [[use-frame]] join the frame the surrounding tree is
  already in — the same React context the boundary shell reads, and the
  only channel either hook has. There is no argument, option map or
  spelling by which a native read reaches a sibling frame, and that is
  what makes an island cheap to reason about: it can see exactly what
  the hiccup around it can see.

  They are also the whole of the hook surface, deliberately. React's own
  hooks are used directly, by `[\"react\" :as react]` interop, and nothing
  here wraps `useState`, `useEffect`, `useRef` or `useTransition` —
  what React cannot supply is the frame, so the frame is all these
  supply. [[use-sub]]'s docstring carries the external-store ceiling
  React's own documentation states, which is the one honest caveat on
  the pair: a commit observed through the hook is a BLOCKING update and
  no part of this surface is transition-aware.

  ## The ABI helpers, and the two directions across the boundary

  [[memo]] and [[lazy]] are `react/memo` and `react/lazy` with the tier
  [[marker]] carried across, and they are the whole helper surface: React
  19 hands a function component its `ref` as an ordinary prop, so refs
  need no helper and get none. Neither helper touches props or children —
  the ABI is one raw JavaScript props object at every rung, and a second
  \"fast\" one does not appear here or anywhere else in the tier.

  Crossing the boundary is likewise not this namespace's invention in
  either direction. **Inward** — hiccup mounting an island — a native
  component is a foreign React component like any other, so it enters
  through the seams that already exist: `h/defhost` for a named crossing,
  the `[:>]` escape for a one-off. Neither knows this tier exists, and
  that is what keeps clause 6 true: an interpreted-only bundle cannot
  reach the native runtime through a door that never names it.
  **Outward** — a native parent mounting a Hicasso view — is
  `h/as-component`, and it lives on the interpreted door for the mirror
  reason: a UIx or JavaScript parent must not have to require this
  namespace, and therefore ship it, to cross.

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
                     [re-frame.adapter.context :as adapter-context]
                     [re-frame.hicasso.impl.codec :as codec]
                     [re-frame.hicasso.impl.collector :as collector]
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

     (def ^:private component-options
       "Every key an [[defcomponent]] declaration map may carry.

  ONE today, and the roster exists precisely because it is one: a
  declaration read for `:server` and silent about everything else
  accepted `{:ssr :render}` — the spelling `defhost` carried until
  rf2-mo4o — and `{:sever :render}`, and stamped Client-only in both
  cases while the author read the source and saw a policy (rf2-u9lk)."
       #{:server})

     (def ^:private server-policies
       "Every value `:server` may take. These are `defhost`'s two as well;
  what that door has and this one deliberately does not is the sibling
  `:fallback` option. Both doors gate the Client-only arm the same way
  ([[mint-server-gate]]), so the difference is not mechanism but
  AUTHORING SITE: a `defhost` crossing stands where the author is
  writing React and has no hiccup to hand, while a native island is
  reached from a `defview` body or an `n/$` parent, where markup in the
  region is ordinary source written where it renders. A second
  declaration grammar for it would buy nothing."
       #{:client-only :render})

     (defn declared-server
       "The `:server` policy `decl` carries, VALIDATED — the two rosters
  `mint-host!` has, at the one declaration door in the package that had
  neither (rf2-u9lk).

  Absent means `:client-only`: the conservative answer, so an author who
  writes nothing and an author who writes the default explicitly get the
  same one, and `:render` — the assertion *this component is safe to run
  on the server* — is never reached by omission.

  **Called at LOAD, from inside [[defcomponent]]'s expansion, and not at
  macroexpansion.** Two things follow from that and both are the reason.
  `error/fail!`'s ambient `:view` and `:source` come from the ledger the
  emitted `declaring!` has just written, so a refusal raised here names
  the file and line of the declaration; a refusal raised during
  expansion would carry neither. And this package has no JVM test lane
  at all, so a compile-time refusal would be witnessed by nothing this
  repo runs.

  ## And it is now READ (rf2-hic-046)

  The value is stamped on the tier marker, where a tool reads it, and
  [[component]] branches on it: `:render` answers the author's own
  function and `:client-only` answers a gate. Until rf2-hic-046 it was
  recorded and consulted by nothing, so an undeclared island — nominally
  Client-only — rendered into the server response exactly as a declared
  `:render` one did, which is the silent matrix enlargement the default
  exists to prevent (merged-PR audit #7839)."
       [component-name decl]
       (doseq [k (keys decl)]
         (when-not (contains? component-options k)
           (error/fail! :rf.error/hicasso-native-unknown-option
                        're-frame.hicasso.native/defcomponent
                        (str "n/defcomponent " component-name " was declared with "
                             (pr-str k) ", which is not an option. A native "
                             "declaration carries :server and nothing else. The "
                             "`defhost` door takes the same :server policy with "
                             "a sibling :fallback, which is the key most often "
                             "borrowed here. Reading past an option it does not "
                             "know is how a policy comes to be set and never "
                             "applied.")
                        :declare-the-server-policy
                        {:component component-name :option k
                         :options component-options})))
       (let [policy (get decl :server :client-only)]
         (if (contains? server-policies policy)
           policy
           (error/fail! :rf.error/hicasso-native-bad-server-policy
                        're-frame.hicasso.native/defcomponent
                        (str "n/defcomponent " component-name " declares :server "
                             (pr-str policy) ". The policy is :client-only — the "
                             "default, meaning the component is not run on the "
                             "server and contributes nothing to the response — "
                             "or :render, meaning it is safe to run there and "
                             "does. There is no third value, and `defhost`'s "
                             "sibling :fallback option has no counterpart "
                             "here: a native island is reached from hiccup, so "
                             "markup for the region is written where it "
                             "renders rather than declared.")
                        :declare-client-only-or-render
                        {:component component-name :server policy}))))

     (defn- mint-server-gate
       "The one component a `:client-only` declaration mints — the
  author's function behind its server policy, and the whole of the
  Client-only mechanism.

  `defhost`'s gate exactly ([[re-frame.hicasso.impl.codec/mint-host-gate!]]),
  down to the hook: [[re-frame.hicasso.impl.codec/adopted?]] is READ
  here rather than reimplemented, because one policy concept with two
  implementations is the shape a second copy gets subtly wrong — the
  `useSyncExternalStore` triple is compared by identity and a fresh
  closure per render re-subscribes every island on every render. Audit
  #7839 asked for the two-value matrix and no second policy mechanism;
  this is the first half taken by not building the second.

  So the hook answers `false` while React is producing server bytes AND
  again on hydration's first client pass, and `true` afterwards and on
  the very first pass of a fresh `createRoot` mount. Three consequences,
  none of which needs a server walk that knows about the policy:

  1. the server response carries nothing where the island sits;
  2. hydration's first pass renders the same nothing, so there is no
     mismatch for React to reconcile — the two agree BY CONSTRUCTION;
  3. a fresh client mount never consults a server snapshot, so the
     island renders on its very first pass and nothing flashes.

  **No `:fallback`, deliberately** — the sibling door has one and this
  one does not ([[server-policies]]). The gate renders `nil`, and an
  author who wants markup in the region writes it in the enclosing
  hiccup, where it is ordinary Hicasso and not a second declaration
  grammar.

  The gate hands its own props straight through, `ref` included, so the
  ABI a `:client-only` island sees is the one a `:render` island sees:
  one raw JavaScript props object, children at `.-children`."
       [component-name f]
       (let [gate (fn [props]
                    (when (codec/adopted?)
                      (react/createElement f props)))]
         (set! (.-displayName gate) component-name)
         gate))

     (defn component
       "Mint what [[defcomponent]] `def`s: the element type for this
  island, stamped with its `displayName` and the tier marker.

  **`server` decides which element type that is** (rf2-hic-046). Under
  `:render` it is the author's own function — HD-011's zero-wrapper,
  zero-fiber, zero-hook shape, one tree on the server, on hydration and
  on a fresh mount, so no snapshot pair, no adoption event and NO
  REMOUNT. Under `:client-only` it is [[mint-server-gate]], which costs
  one fiber and one hook and contributes nothing to a server response.
  The price is the ruled default's, and an island that is safe on the
  server says so and pays neither — which is why the ABI's own headline
  example writes `{:server :render}`.

  The tier marker goes on whichever type is answered, so [[marker]],
  the ABI helpers and every seam that recognises a native head read the
  same object React reconciles on. The author's function carries the
  display name too, so a gated island shows its own name at both fibers
  rather than an anonymous one below its gate.

  I9's two-hook ceiling is untouched: it is a statement about Hicasso's
  BOUNDARY shells, and a gate is not a boundary — it holds no
  subscription and reads no frame. `defhost` priced the identical fiber
  the identical way (rf2-2rtt6.85).

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
       (let [head (if (keyword-identical? :render server)
                    f
                    (mint-server-gate component-name f))]
         (unchecked-set head tier-sentinel
                        #js {:name component-name :server (name server)})
         head))

     (defn marker
       "The tier marker [[component]] stamped, or nil. The seam every ABI
  helper and every embedding direction reads to recognise a native head —
  and the reason a raw `react/memo` wrapper loses it."
       [x]
       (when (some? x) (unchecked-get x tier-sentinel)))

     ;; ----------------------------------------------------------------
     ;; The two ABI helpers (rf2-hic-032)
     ;; ----------------------------------------------------------------
     ;;
     ;; TWO, and refs make a third that needs no code. React 19 hands a
     ;; function component its `ref` as an ordinary prop, so `:ref` is
     ;; already the one ABI at the one slot and a `forwardRef` helper
     ;; would be a wrapper that preserved what nothing was taking away.
     ;;
     ;; What `react/memo` and `react/lazy` DO take away is the marker.
     ;; Both answer a NEW object — a memo record, a lazy record — and the
     ;; marker is an own property of the component they were handed, so
     ;; the display name and the declared server policy stop at the
     ;; wrapper and Xray names an anonymous boundary where an island
     ;; should be. Neither changes the props or children ABI at all: a
     ;; memo passes props through untouched and a lazy resolves TO the
     ;; component. So the whole of the repair is carrying the two stamps
     ;; across, and these helpers are deliberately nothing else.

     (defn- carry!
       "Copy `f`'s display name and tier marker onto `wrapper`, and answer
  the wrapper. Both are absent for a component this tier did not mint —
  a UIx `defui`, a handwritten React function — and the copy is silently
  a no-op there rather than a refusal: UIx is a supported authoring
  route, so memoising one of its components through here is legal and
  simply carries nothing, which is exactly what it had."
       [wrapper f]
       (when-some [d (unchecked-get f "displayName")]
         (unchecked-set wrapper "displayName" d))
       (when-some [m (marker f)]
         (unchecked-set wrapper tier-sentinel m))
       wrapper)

     (defn memo
       "**`React.memo`, with the marker intact.**

      (n/defcomponent quote-cell* [^js props] …)
      (def quote-cell (n/memo quote-cell*))

  Identical semantics to `react/memo` — a shallow props comparison, or
  the `props=` you supply, and React's own bail-out — and the ONE
  difference is that the memo record carries [[marker]] and the display
  name forward. `(react/memo quote-cell*)` works at runtime and is the
  defect: Xray shows an anonymous boundary, and the seams that recognise
  a native head no longer do.

  Declared at top level, never in a render. A memo record IS the element
  type React reconciles on, so one minted inside a body is a fresh type
  per pass — no bail-out, and a remount instead. That is `React.memo`'s
  own law, not an addition.

  **It does not survive a hot reload, and must not.** A save
  re-evaluates the module, [[component]] allocates a fresh function, this
  allocates a fresh memo around it, and React replaces the subtree. A
  clean remount across a save is the designed conduct (rf2-hic-015); a
  helper that preserved identity here would be fighting the contract."
       ([f] (carry! (react/memo f) f))
       ([f props=] (carry! (react/memo f props=) f)))

     (defn lazy
       "**`React.lazy`, with the marker intact and the loader unwrapped.**

      (def chart-loadable (lazy/loadable app.charts.island/heavy-chart))
      (def heavy-chart (n/lazy #(lazy/load chart-loadable)))

  `load` is `React.lazy`'s contract with one knot untied: a thunk
  returning a promise, which resolves **to the component** rather than to
  a `#js {:default component}` module record. Every ClojureScript code
  splitter already resolves to the value — `shadow.lazy/load` does — so
  the module wrapper would be a shape the author invents for React and
  then never reads. This puts it on once, where it is React's business.

  Suspend conduct is React's whole: while the promise is pending the
  nearest Suspense host shows its fallback, and the arrival commits the
  component with the props the parent wrote before it existed. A
  suspension here hides a committed sibling rather than releasing it —
  the `useSyncExternalStore` subscription survives the fallback and the
  arrival's registration is the same object, measured in
  [[re-frame.hicasso.lazy-boundary-dom-cljs-test]] and established for
  suspension generally in `activity_suspense_dom_cljs_test`.

  ## A rejection is TERMINAL, and `:reset-key` does not undo it

  A rejection throws into the render and the nearest `h/boundary` catches
  it — but changing that boundary's `:reset-key` retries the BOUNDARY and
  not the chunk. React 19's `lazyInitializer` calls `load` only while its
  payload is uninitialised; a rejection settles that payload as rejected,
  and every read afterwards re-throws the cached error without going near
  the loader. The payload belongs to `react/lazy`, and neither this tier
  nor React's public API can reset it. **This docstring claimed the
  opposite until rf2-hic-041 measured it**, and the paint is the reason
  it survived: a fallback that stays put looks the same whether React
  re-threw a cached rejection or fetched again and failed again.

  So the retry a failed chunk needs is a NEW HEAD — the same allocation a
  hot reload performs, which is why the retry fact and the HMR fact have
  one witness between them. The retryable path is the MODULE GATE:
  `shadow.lazy/load` is an ordinary function, calling it again really
  does fetch again, and the arrival state belongs in app-db where an
  ordinary retry intent can reach it. Gate that event on `:loading` AND
  `:loaded`, or warming the module from a hover will fetch it twice.

  ## The marker, and the one field that cannot be known yet

  A lazy head is a head before its component exists, so its marker is
  minted here and its `:name` is filled in when the payload arrives —
  the SAME object throughout, so a seam that read it early sees the name
  appear rather than being handed a second marker to reconcile. Before
  arrival the honest answer is that the head has no name, and that is
  what it says.

  **`:server` is `client-only` and is not the inner component's to
  override.** The server never sent the chunk, so no policy the component
  declares can make bytes exist: the region is Client-only whatever
  `n/defcomponent` said.

  **Nothing stands in for it on the server unless a DECLARATION says so.**
  The `defhost` that crosses to this head emits server bytes only where
  it was given a `:fallback`, and React's own Suspense fallback is not
  that fallback — it is a prop at a ReactNode slot, and a Client-only
  region renders nothing at all, slot included. A page that wants a
  skeleton in its server response declares one.

  Declared at top level, never in a render — `React.lazy`'s own law.
  Minting inside a body allocates a fresh identity per pass, so the
  fallback flashes and the load re-triggers on every render of the
  parent."
       [load]
       (let [m     #js {:name nil :server "client-only"}
             lazy* (react/lazy
                     (fn []
                       (.then (load)
                              (fn [component]
                                (when-some [inner (marker component)]
                                  (unchecked-set m "name" (unchecked-get inner "name")))
                                #js {:default component}))))]
         (unchecked-set lazy* tier-sentinel m)
         lazy*))

     ;; ----------------------------------------------------------------
     ;; The two hooks (rf2-hic-031)
     ;; ----------------------------------------------------------------
     ;;
     ;; Two, and there will not be a third by accident. Everything else a
     ;; native component needs is React's own — `useState`, `useEffect`,
     ;; `useRef`, `useTransition` — reached by direct `["react"]` interop,
     ;; and Hicasso wraps none of it. What React cannot supply is the
     ;; frame, so that is exactly what these two supply and no more.
     ;;
     ;; Both take their frame from [[re-frame.hicasso.impl.collector/resolve-frame!]],
     ;; which is the boundary shell's own resolution: ONE React context,
     ;; no dynamic-var tier, no `:rf/default` floor. An island therefore
     ;; reads the frame its own subtree is mounted under and there is no
     ;; spelling — not an argument, not an option map — by which either
     ;; hook reaches a sibling frame.

     (defn use-frame
       "**Frame-locked operations, in hook position.** `rf/capture-frame`'s
  bundle for the frame this island is mounted in:

      {:frame         :watchlist
       :dispatch      (fn ([event] [event opts]))
       :dispatch-sync (fn ([event] [event opts]))
       :subscribe     (fn [query-v])}

      (n/defcomponent col-resizer
        [^js props]
        (let [{:keys [dispatch]} (n/use-frame)]
          (n/$ :div {:on-pointer-up (fn [_] (dispatch [:col/commit]))})))

  It is `capture-frame` and nothing wider — no options map, no explicit
  frame arity — because Hicasso does not duplicate core's frame doors.
  For a named frame there is no hook tax: `(rf/capture-frame frame-id)`
  takes one directly.

  ## Reference-stable, and pinned to an INCARNATION

  The map is the Hicasso runtime's own memo row
  ([[re-frame.hicasso.impl.collector/frame-row]]), so repeated renders
  under one frame hand back the identical object — safe in `useEffect`
  deps, safe as a memoised child's prop, safe to pull `:dispatch` off
  and close over.

  What it is keyed on is the load-bearing part. A frame's public keyword
  is an ADDRESS, not an object: destroy `:watchlist` and create another
  under the same id and the ops captured against the first belong to the
  first forever (rf2-hic-013). The row carries the incarnation it was
  minted under and is replaced when that incarnation is superseded, so
  the next render gets ops pinned to the successor while a callback
  still holding the predecessor's is refused by core's own fence
  (`:rf.error/frame-destroyed`) rather than silently writing whoever
  occupies the address now. **A memo keyed on the frame KEYWORD would
  pass every stability test and fail exactly this one**, because the
  keyword is `=` across a reincarnation.

  Rendering outside every frame refuses with
  `:rf.error/no-frame-context`."
       []
       (let [frame-kw (collector/resolve-frame!
                        (react/useContext adapter-context/frame-context)
                        're-frame.hicasso.native/use-frame)]
         (:ops (collector/frame-row frame-kw))))

     (defn use-sub
       "**Read a subscription from a native component.** A real React
  hook, so React's rules of hooks are the rules — top level of the
  component, unconditional, one call per read:

      (n/defcomponent ticker
        [^js props]
        (n/$ :span nil (n/use-sub [:quote/price (.-symbol props)])))

  It is the native tier's counterpart to `h/sub`, and the two obey
  different laws on purpose. `h/sub` is an ambient collector legal
  inside a `when`, inside a `for` and inside an inlined helper, because
  a `defview` body is not a component and its reads are recorded where
  they happen. Past the fence there is no body and no ambient extent —
  there is a fiber — so a read is a hook and a conditional read is a
  bug React itself names.

  ## One runtime, not a second one

  The hook hands `useSyncExternalStore` the very `subscribe` and
  `getSnapshot` a boundary reading this one key would be handed
  ([[re-frame.hicasso.impl.collector/hook-entry]]). So an island's read
  builds the same cell, takes the same reader membership, is woken by
  the same commit, is counted by the same residue census, and is named
  by the same `re-frame.hicasso.tool` rosters Xray consumes — which is
  why nothing in the tool tier had to learn that hooks exist.

  A steady-state re-render performs **no re-subscribe**: an unchanged
  key hits the same cached entry, so `subscribe` is identical and React
  never calls it again. Unmount releases exactly what mount acquired,
  and React StrictMode's double mount is the acquire/release pair run
  twice rather than a leak, because the only global write is inside
  `subscribe` and React calls it at commit and nowhere else.

  Two calls in one component are two subscriptions, where a `defview`
  body's several `h/sub` reads are ONE. That is React's arithmetic and
  not a choice: a store subscription is a hook, so `n` reads cost `n` of
  them, and the tool tier will show `n` single-edge boundaries rather
  than one with `n` edges. Nothing is incorrect about it and the cost is
  a hook cell each; it is simply the shape, and an island reading a
  dozen keys is an island that wanted a `defview`.

  ## The external-store ceiling, stated rather than papered over

  React's own `useSyncExternalStore` documentation is explicit that
  **external-store mutations cannot be non-blocking Transition
  updates**: React may restart a transition as blocking when the
  snapshot changes. A re-frame2 commit observed through this hook is
  therefore a BLOCKING update, and wrapping the dispatch in
  `startTransition` does not make it otherwise. Reads stay tear-free
  under a transition — that is witnessed — but no part of this surface
  is transition-aware and none of it should be described that way.

  React likewise discourages SUSPENDING on a value read from an
  external store, because an update can then replace visible content
  with a fallback. So `n/use-sub` is not the door to a promise-driven
  resource: prepare the resource at the route, model an explicit
  pending state, or put React Suspense in a native island that owns its
  own resource.

  Rendering outside every frame refuses with
  `:rf.error/no-frame-context`."
       [query-v]
       (let [frame-kw  (collector/resolve-frame!
                         (react/useContext adapter-context/frame-context)
                         're-frame.hicasso.native/use-sub)
             ;; The refusal above sits between the two hooks deliberately.
             ;; Without a frame there is no read set, so there is nothing
             ;; for the store hook to subscribe to and nothing to invent;
             ;; and a render that throws never reaches React's hook
             ;; reconciliation, so no count can disagree with a previous
             ;; render's.
             sub-key   [frame-kw query-v]
             ^js entry (collector/hook-entry sub-key)]
         ;; The epoch, not the value: `getSnapshot` answers one monotone
         ;; number, React compares it with `Object.is`, and the value is
         ;; read after — from the same synchronous instant, since a
         ;; render is a turn and nothing can commit inside one. This is
         ;; the shell's own arrangement, and the third argument is the
         ;; same closure for the same reason it is there.
         (react/useSyncExternalStore (.-subscribe entry) (.-snapshot entry)
                                     (.-snapshot entry))
         (collector/hook-read sub-key)))))

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
  omitting it means Client-only. It carries nothing else, and both the
  key and the value are checked against their rosters at the
  declaration, where the author's stack still points at it
  ([[declared-server]]).

  **The policy decides what a server render contains** (rf2-hic-046).
  `:render` is the author asserting *this component is safe to run on
  the server*, and under it the island IS the element type: it renders
  into the server response, hydrates against those bytes and never
  remounts. `:client-only` — the default — contributes NOTHING to the
  response and appears once the client has adopted the page, which is
  what makes an island reaching for `window` safe to write without
  saying anything. One mechanism serves both arms ([[component]]), and
  neither needs a server entry of Hicasso's own: the consumer calls
  `react-dom/server` and the policy is honoured by rendering.

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
           coord    (source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~cname ~coord))
            (try
              ;; `declared-server` VALIDATES and answers, so the read and
              ;; the two rosters are one call and cannot disagree — and it
              ;; is an argument, so it runs inside this `try`, after
              ;; `declaring!` has put the coordinate on the ledger and
              ;; before `declared!` takes it off again.
              (component ~cname (declared-server ~cname ~decl) (fn ~argv ~@body))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))
