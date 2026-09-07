(ns re-frame.hicasso
  "HICASSO — the public door.

  Everything an author writes against lives here, and everything below is
  `re-frame.hicasso.impl.*`. The intended spelling is one alias:

      (ns my.app
        (:require [re-frame.hicasso :as h]))

      (h/defview todo-row [{:keys [id]}]
        (let [todo (h/sub [:todo/by-id id])]
          [:li {:on-click [:todo/toggle id]} (:text todo)]))

  ## What this namespace is

  **The three authoring macros live HERE**, because this is the authoring
  surface. The frame doors are core's: `rf/current-frame-id` and
  zero-arity `rf/capture-frame` answer the rendering boundary's frame
  inside a body, and this door duplicates neither.

  **Every other var below is an ALIAS.** This namespace adds no
  behaviour: each `def` names a value `re-frame.hicasso.impl.*` owns.

  ## The marker keywords need no export

  `::h/value`, `::h/prevent`, `::h/revision`, `::h/checked` and
  `::h/clear` read `:re-frame.hicasso/…`. Alias this
  namespace as `h` and the auto-resolved spelling the guide teaches
  resolves, with no keyword changing value.

  ## What this door does NOT carry — the optional modules

  `presence` is `re-frame.hicasso.motion/presence`, required
  separately:

      (:require [re-frame.hicasso :as h]
                [re-frame.hicasso.motion :as motion])

  There is no alias left behind, and that is the point rather than an
  omission. An optional module has to be **absent when unused** — no
  reachable code in an application that never asked for it — and one
  `:require` here would put the retention machine into every bundle that
  ever touched the door. So the door names no optional module, and
  `hicasso/scripts/check_optional_module_reachability.py` fails if one
  reappears. The presence override keys are the motion module's own
  vocabulary and spell its namespace: `::motion/mounting` /
  `::motion/unmounting`, i.e. `:re-frame.hicasso.motion/…` — naming-ledger
  row 31's ruled respelling, executed under rf2-hg3q. They are not `::h/…`
  keywords and are not in the marker set above; the codec still recognises
  them, and refuses one written out of a presence tray's reach."
  ;; The macro side reaches core's `re-frame.source-coords` for the one
  ;; thing a defining macro cannot do portably by hand: pick the right
  ;; `:file` for the coordinate it captures. Under CLJS the analyzer never
  ;; binds Clojure's `*file*` during expansion, so a naive read bakes the
  ;; `"NO_SOURCE_PATH"` sentinel into every declaration; core solved that
  ;; once, absolutises the classpath-relative path while it is there, and
  ;; is already this package's only dependency.
  #?(:clj (:require [re-frame.source-coords :as rf.source-coords]))
  #?(:cljs
     (:require [re-frame.hicasso.impl.boundary :as rf.hicasso.impl.boundary]
               [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
               [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
               [re-frame.hicasso.impl.intent]
               [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
               [re-frame.hicasso.impl.portal :as rf.hicasso.impl.portal]
               [re-frame.hicasso.impl.route-link :as rf.hicasso.impl.route-link]
               [re-frame.hicasso.impl.state :as rf.hicasso.impl.state]))
  #?(:cljs (:require-macros [re-frame.hicasso :refer [defview defhost event]])))

;; ---------------------------------------------------------------------------
;; The three macros
;; ---------------------------------------------------------------------------
;;
;; This file is a `.cljc` only because a CLJS namespace cannot otherwise
;; hand its macros to a consumer, and its `:clj` side is these three macros
;; and nothing else — no runtime namespace is required on the JVM, so there
;; is no JVM render path for a reader to mistake for one. A `.cljc` that
;; carried more would invite a JVM-side implementation this package does
;; not have.

#?(:clj
   (defmacro defview
     "Mint a boundary — a real React function component (HD-016).

  `argv` is the ordinary one-props-map argument vector, so destructuring
  reads as it does in any Clojure fn. In hiccup the resulting var is a
  legal head: `[todo-row {:key id :id id}]`. A plain function in head
  position is a loud error rather than a silent embedding, which is what
  makes the head's identity stable by construction and leaves the codec's
  stable-component-head cache with nothing to do.

  ## What a value at an `on-*` prop may be — FOUR shapes

  A body writes its handlers as DATA, and the SHAPE of the value at an
  `on-*` position selects the behaviour — so there is no roster of
  blessed prop names, and `:on-click` and `:onClick` read the same:

      [:li    {:on-click [:todo/toggle id]}]               ;; a vector
      [:input {:on-key-down {\"Enter\"  [:todo/commit id]    ;; a map
                             \"Escape\" [:todo.ui/cancel id]}}]
      [:input {:on-change (h/event [e] …)}]                  ;; the one callback
      [:div   {:on-focus  a-plain-fn}]                     ;; a plain fn

  - **A vector is an intent**, dispatched into this boundary's frame.
    `::h/value` and `::h/checked` substitute the event target's current
    value at dispatch time, and `::h/prevent` is the reserved head that
    calls `.preventDefault` before dispatching the intent it wraps —
    `[::h/prevent [:filter/show-done]]`, the opt-in an anchor acting as
    a button needs, since `:on-submit` is the only position that
    prevents by default.
  - **A map is a KEY MAP** — the key exactly as the browser spells it
    (`\"Enter\"`, `\"Escape\"`) → an intent vector or a function. It is
    lowered ONCE per render into a plain string→handler map, so an event
    costs one lookup and no allocation, and it is **composition-gated
    centrally**: a keystroke arriving mid-IME-composition commits
    nothing. That gate is the half a hand-written `.key` test does not
    have, which is why the key map is the spelling to reach for — the
    hand-written version yields an application that works and is wrong
    for every user who composes.
  - **`event` (`h/event`) is the one callback form**, for when the event
    itself is wanted. At an `on-*` position a returned vector is
    dispatched and any other return is ignored.
  - **A plain function is passed through untouched**, reaching React by
    identity so `React.memo` and every handler-identity bail-out keep
    working.

  The vector and the key map are EVENT-FIRST: both read the DOM event
  from argument one, and a position whose invoker passes something else
  — a value-first foreign callback — refuses loudly at the position
  rather than doing nothing. Which contract a position imposes on a
  callback, and what a `defhost` declaration changes about it, is
  `re-frame.hicasso.impl.intent`'s position table.

  ## The fn this expands to is ANONYMOUS, and that is the contract

  The expansion binds NO name inside your body, so every symbol a body
  resolves is one you wrote. The ordinary extract-a-helper spelling is
  therefore safe at the ordinary spelling:

      (defn todo-row-body [props] …)
      (h/defview todo-row [props] (todo-row-body props))

  Naming the emitted fn after `sym` would break exactly that: a named
  `fn` binds its own name for its own body, so the pair above would
  expand to `(fn todo-row-body [p] (todo-row-body p))` and recurse until
  the stack overflowed — under React exactly as under the test kit,
  reporting `Maximum call stack size exceeded` and naming neither the
  view nor the macro that shadowed the helper.

  Anonymity rather than a gensym or a reserved prefix, because those make
  the collision improbable where this makes it unrepresentable: a fn with
  no name has nothing to shadow with, and it costs nothing. The
  identifiers this macro decides are the `\"<ns>/<sym>\"` view name and
  the coordinate below, both computed here and both passed as VALUES —
  `mint-view!` stamps the view name as `displayName` on the body and on
  the component, and Spec 009 keys its render measure `rf:render:<view
  name>` off the same string. The emitted fn's own symbol feeds none of
  them; it would reach nothing but a stack frame.

  ## Hooks do not belong in the body — a law, and why not a refusal

  A body is dynamically composed: its branches, its `for`s and its
  early returns are all free to follow the data it reads. React's rules
  of hooks are about CALL SEQUENCE, so a hook written in a body would
  make its own order depend on a Hicasso data path — a subscription
  answering one row fewer, and the sequence moves. Hook-intensive
  behaviour belongs in a separately defined React island — a UIx `defui`
  or a raw React function component mounted through `h/defhost` — where
  React's rules apply to source the author controls. [spec §5, Rung 3]

  Nothing enforces this at runtime and nothing here intends to. The
  macro reads no body — that is the contract above, not an omission —
  so a refusal would need a compiler that analyses one, which
  `lanes/hot-path-architecture.md` refuses outright. React is the
  enforcement: a hook whose call order moves fails in React's own
  words, at the boundary that moved it.

  The shell's own two hooks are unaffected and are the whole budget (I9,
  HD-020(b)); `hook-budget-cljs-test` counts them at React's dispatcher.

  ## The source coordinate

  The expansion opens a declaration extent around the mint, carrying the
  `:ns` / `:file` / `:line` / `:column` this macro read off its own form.
  `re-frame.hicasso.impl.error/fail!` resolves it back by view name, so
  every refusal raised while this boundary's body runs says WHERE the
  boundary was written without a single call site passing anything.

  Both halves sit inside `(when re-frame.interop/debug-enabled? …)`, so
  under `:advanced` + `goog.DEBUG=false` the Closure compiler removes the
  calls and the coordinate map — file string included — and the `def` is
  the bare mint inside an empty `try`.

  The extent CLOSES IN A `finally`, so a mint that refuses does not leave
  its own name ambient for whatever runs next. A declaration refusal is
  routinely caught — by a module loader, or by an HMR runtime whose
  mounted page carries on rendering — and the slot then named a `def`
  that never completed.

  ## The name is also REGISTERED — an AUTHORING-TIME ALIAS FOR FORWARD
  RESOLUTION ONLY

  The declaration publishes one entry in re-frame's `:view` registrar,
  under `(keyword \"<ns>\" \"<sym>\")` — the id `rf/reg-view` derives
  from its own symbol, one convention for both substrates — carrying the
  coordinate above and the minted head at `:handler-fn` — the ONE
  executable slot every substrate's `:view` entry uses, so
  `(rf/view id)` answers this boundary exactly as it answers a Reagent
  or a UIx head (rf2-kuky.60). What comes back is `identical?` to what
  the `def` bound: `re-frame.views/view-head` returns a `:view` slot it
  did not build exactly as stored, so nothing wraps, composes or
  componentises a boundary that already is a React component. Mount it
  the way this docstring documents — `[head props]` inside a body,
  `h/as-element` / `h/as-component` from outside — never as a hiccup
  render fn in a Reagent tree. Registration is debug-gated, so
  `(rf/view id)` for a Hicasso view is nil in a release build; that is
  the documented answer.

  **Forward resolution is the whole of it.** The entry exists so that a
  tool holding a keyword the author WROTE — a story naming its subject,
  an editor jumping to source — reaches the view they meant. It mints no
  runtime identity: a MOUNTED boundary is still keyed by its read set
  and still unnamed, and the tool tier
  (`re-frame.hicasso.tool`) is unchanged. The refusals that say so are
  NOT overturned by this, because they answer the BACKWARD question
  *which view is this runtime boundary?* and this answers the forward
  one, where the author already knows and is naming it in source.

  The name itself is not new. It is the `displayName` React DevTools
  shows, the `rf:render:<name>` User-Timing id Spec 009 keys off, and
  the string a refusal is attributed with; only lookup was missing.
  Registration adds RESOLVABILITY, not identity.

  It rides the SAME `debug-enabled?` gate as the coordinate, so under
  `:advanced` + `goog.DEBUG=false` nothing registers and a production
  Hicasso app still holds no registry at runtime — the entry serves
  tools. Re-evaluating a declaration replaces the entry behind the same
  id — the registrar's own behaviour, with nothing added for it beyond
  the slot naming where its executable identity lives, so that the
  replacement a tool is told about is the one that happened.
  `re-frame.hicasso.impl.collector/publish-view-alias!` carries the
  slot's shape and why it is written there rather than through
  `rf/reg-view*`."
     [sym & more]
     (let [doc       (when (string? (first more)) (first more))
           [argv & body] (if doc (rest more) more)
           view-name (str (ns-name *ns*) "/" sym)
           ;; The registrar id, derived by `reg-view`'s own
           ;; rule (`core-reg-view-macro/expand-reg-view`) so the two
           ;; substrates spell one convention. No `^{:rf/id …}` override
           ;; here: a `defview` has exactly one name and this is it.
           view-id   (keyword (str (ns-name *ns*)) (str sym))
           coord     (rf.source-coords/coords-form (meta &form) *file* (ns-name *ns*))
           ;; The registration slot IS the coordinate map — `reg-view`
           ;; merges its coords into the slot's top level and tools read
           ;; them back from there — plus the author's `:doc`, without
           ;; which the registrar would dev-warn `:rf.warning/missing-doc`
           ;; against a view that IS documented.
           slot      (cond-> coord doc (assoc :doc doc))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~view-name ~coord))
            (try
              (let [head# (re-frame.hicasso.impl.collector/mint-view!
                            ~view-name
                            ;; ANONYMOUS — see the docstring's section on
                            ;; naming. A named `fn` binds its own name
                            ;; for its own body, so any name derived from
                            ;; `sym` shadows the author's helper of that
                            ;; name.
                            (fn ~argv ~@body))]
                ;; Inside the extent, so a refusal the
                ;; registration raised would name this declaration; after
                ;; the mint, because the head is what the entry carries.
                ;; The head is still what the `def` binds: registration is
                ;; a side effect and returns nothing to it.
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.collector/publish-view-alias!
                    ~view-id ~slot head#))
                head#)
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))

#?(:clj
   (defmacro event
     "**The one callback form** (HD-024). `h/event` in the authoring
  surface, and `event` here — the same name, since the door is reached
  qualified. Naming-ledger row 1 settles the name: not `fn`, because a
  bare `fn` shadows `cljs.core/fn` for anyone who `:refer`s it; and not
  `handler`, a cross-adaptor false friend — `handler` means
  return-ignored imperative work in the established vocabulary, and this
  form's contract is `event`.

  **The name states ONE of the two contracts this form can carry**, and
  which one is selected by POSITION rather than by the name — the same
  rule at a native tag, a `defhost` and a `[:>]` crossing:

  | Position | Contract |
  |---|---|
  | an `on*`-spelled prop | **event** — a returned VECTOR is dispatched; any other return is ignored |
  | any other walked prop | **render** — pure; the return is the render output and is NOT dispatched. Handlers lowered inside the call belong to the boundary that supplied it |

  A `defhost` may override the spelling for one prop with
  `{:callbacks {:on-render-item :render}}`, for the on*-named render
  props some vendors ship. `:ref` is React's own and is excluded from
  lowering; where Hicasso does not walk, this is a plain function whose
  return is ignored (`re-frame.hicasso.impl.intent` carries the table).

  Expands to nothing but a marked `fn`:

      (event [e] (js/Array.from (.. e -target -files)) …)
      ;; =>
      (re-frame.hicasso.impl.intent/callback (fn [e] …))

  The value is an ORDINARY FUNCTION, so nothing can fail to be callable
  where Hicasso does not walk. Argument in docs/design/hicasso/decisions.md,
  HD-024."
     [argv & body]
     `(re-frame.hicasso.impl.intent/callback (fn ~argv ~@body))))

#?(:clj
   (defmacro defhost
     "**The interop door — the one-line declaration (HD-011).** Name the
  crossing to a foreign React component once; use the resulting var as a
  hiccup head anywhere, indistinguishable from a view:

      (defhost date-picker DatePicker)

      [date-picker {:selected  due-date
                    :on-change (event [date & _] [:task/set-due date])}]

  **Callback contracts are inferred from the prop's spelling, exactly as
  at a native tag**: an `on*` prop is an event position (an intent
  vector, a key-map or an `event` dispatches), any other prop that
  takes an `event` is a render position (pure; the return is the
  render output, and an intent inside it fires into this boundary's
  frame), and a plain function crosses untouched anywhere. Two shapes:

      (defhost name component)
      (defhost name component opts)

  each with an optional docstring in SECOND position. `opts` carries four
  keys, every one optional:

  - `:callbacks` — an OVERRIDE, `{prop :event|:render}`, written only
    where the spelling infers the wrong contract: the on*-named render
    props some vendors ship, `{:callbacks {:on-render-item :render}}`,
    where the event wrapper's nil return would blank the UI.
  - `:slots` — the set of ReactNode positions (a modal's title, a
    `Suspense` fallback). Hiccup there lowers under this boundary's
    frame; at an undeclared prop a vector is DATA, because a vector is a
    legal value and nothing can infer a markup position. A slot may not
    be `:key`/`:ref`, a declared callback, or spelled twice.
  - `:server` — `:client-only` (the DEFAULT: the region renders nothing
    on the server and on hydration's first pass, and the component mounts
    once the markup is adopted; a fresh mount never flashes) or `:render`,
    the ASSERTION that the component is safe on the server, under which
    no gate is minted and one tree renders everywhere — the only policy
    under which a crossing's children reach the server response.
  - `:fallback` — Client-only's placeholder, inert hiccup reused at every
    site; a boundary head inside it is refused
    (`:rf.error/hicasso-host-fallback-boundary-head`), and it is refused
    beside `:render`.

      (defhost modal Modal {:slots #{:title :footer}})
      (defhost chart Chart {:fallback [:div.chart-skeleton]})
      (defhost themed (.-Provider theme-context) {:server :render})

  Refused at the declaration: a `nil` component
  (`:rf.error/hicasso-host-no-component`), and — as
  `:rf.error/hicasso-bad-host-declaration`, the fault named in the
  reason — non-map options, an option outside the four, a contract
  outside the two, a malformed `:slots` set, and any form after `opts`,
  which is dropped rather than merged. A policy set and never applied is
  the defect every one of these refusals exists to delete. The date-picker
  callback above is an `event` rather than a vector because
  react-datepicker calls `onChange(date, event)`, VALUE-first; the vector
  spelling is EVENT-first and would raise
  `:rf.error/hicasso-intent-needs-the-event`. Like `defview` this is
  not a compiler: it expands to a `def` of the head
  `re-frame.hicasso.impl.codec/mint-host!` mints. Argument in
  docs/design/hicasso/decisions.md, HD-011.

  Positional flexibility is deliberately NOT the repair. A docstring
  after the component binds to `opts` (the probe reads only the first
  form of the tail, which is the component symbol) and pushes the real
  options map into the discarded tail, so it is refused at whichever of
  the two guards it reaches first rather than accommodated.

  The declaration extent (`declaring!` … `declared!`) is what puts the
  offending declaration's file and line on a refusal `mint-host!` raises
  at namespace load, where no render is on the stack; it is
  `debug-enabled?`-gated. It closes in a `finally` because a refusing
  `defhost` is the ordinary way a declaration throws under HMR, and
  without the `finally` every later refusal would inherit this
  declaration's `:view` and `:source`. The refusal on its way out is
  unaffected: `fail!` builds the whole ex-data before it throws."
     [sym & more]
     (let [doc         (when (string? (first more)) (first more))
           forms       (if doc (rest more) more)
           [component opts] forms
           ;; THE TAIL, refused rather than dropped: the destructure
           ;; above is fixed-width, so everything past `opts` would
           ;; vanish silently. Detected here so the extra forms are quoted
           ;; rather than evaluated; raised at load, inside the extent, so
           ;; the refusal carries the declaration's coordinate.
           extra       (seq (drop 2 forms))
           host-name   (str (ns-name *ns*) "/" sym)
           coord       (rf.source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~host-name ~coord))
            (try
              ~(if extra
                 `(re-frame.hicasso.impl.codec/refuse-host-extra-forms!
                    ~host-name '~(vec extra))
                 `(re-frame.hicasso.impl.codec/mint-host!
                    ~host-name ~component ~(or opts {})))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))

;; ---------------------------------------------------------------------------
;; The vars — aliases, every one naming a value `impl.*` owns
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^{:doc "**The ambient collector** — read a subscription's value
  from anywhere inside a body, including inside a `when`, a `for` or an
  inlined helper. The edge is recorded where the read happens, so a branch
  not taken contributes no edge.
  `re-frame.hicasso.impl.collector/sub`."}
       sub rf.hicasso.impl.collector/sub)

     (def ^{:doc "`h/error-boundary` — the runtime's own error boundary
  (HD-020(c)); takes `:fallback`, `:reset-key` and `:on-error`.

  Named for React's own term of art, which is also what the naming ledger
  rules (row 12). A bare `boundary` would be the wrong word twice over: every minted `defview` is already *a boundary* here,
  and React has a second kind — the Suspense boundary — that this one is
  not. `re-frame.hicasso.impl.boundary/boundary`."}
       error-boundary rf.hicasso.impl.boundary/boundary)

     (def ^{:doc "`h/reg-state` — the instance-key sugar (HD-009). Mints one
  parametric subscription and one setter event under `[:ui ::concern ikey]`,
  and nothing else. `re-frame.hicasso.impl.state/reg-state`."}
       reg-state rf.hicasso.impl.state/reg-state)

     (def ^{:doc "`h/portal` — **hiccup into `createPortal`**.
  A legal hiccup head taking `:target`, the DOM container the subtree
  renders into, and optionally `:fallback`, markup for the portal's own
  tree position while the page is server-rendered:

      [h/portal {:target js/document.body}
       [:div.toast {:on-click [:toast/dismiss]} \"saved\"]]

  Three facts, and nothing else to learn. **Events bubble through the
  REACT tree**, so an `:on-click` on a hiccup ancestor sees clicks inside
  the toast although its DOM node sits on `body`, and intents fire into
  the writing boundary's frame exactly as they do in an ordinary child.
  **A changed `:target` is a remount**, because React reconciles a portal
  position by its container — so keep the target stable rather than
  computing one per render. **It is client-only**: `createPortal` needs a
  container that already exists, a server render has none, so the
  portalled subtree is absent from the response and `:fallback` is what a
  caller puts at the tree position instead.

  A `:target` that is not a DOM container — overwhelmingly a lookup that
  answered nothing — is React's own *Target container is not a DOM
  element* at the client render.

  The raw mechanism, for containers the application does not own.
  Anchoring, dismissal and focus conduct are the overlay module's.
  `re-frame.hicasso.impl.portal/portal`."}
       portal rf.hicasso.impl.portal/portal)

     (def ^{:doc "One real anchor, as data — href and click decision taken
  whole from routing's late-bound seams. A plain function, not a boundary:
  it mints no boundary and adds no hook.
  `re-frame.hicasso.impl.route-link/route-link`."}
       route-link rf.hicasso.impl.route-link/route-link)

     (def ^{:doc "`h/as-element` — **the one explicit hiccup→ReactNode
  conversion**. Answers the React element a hiccup form
  lowers to, under the frame of the boundary currently rendering:

      [virtual-list
       {:item-count (count ids)
        :render-row (h/event [i]
                      (h/as-element
                        [:li.row {:on-click [:feed/open (nth ids i)]}
                         (str (nth ids i))]))}]

  **It exists because a `:render` return crosses UNCONVERTED.** A
  declared `:render` position is invoked by the foreign component during
  its own render, and the wrapper ends in a bare call
  (`re-frame.hicasso.impl.intent/render-callback`) — so a string
  renders and a returned hiccup vector reaches React, which refuses it
  (*\"Objects are not valid as a React child\"*). This is the spelling
  that turns the row into an element, and the row keeps its intents:
  they fire later, on the user's click, into the frame of the boundary
  that SUPPLIED the callback.

  The other two places it is the answer are the two places a declaration
  cannot reach. A `[:>]` escape has no `:slots`, so one element crosses
  a prop with `(h/as-element [:h2 \"Tasks\"])`; and past the native
  fence a hiccup vector is refused outright, so a native subtree takes
  one Hicasso-rendered child the same way. Where the crossing IS
  declared, prefer the declaration — `defhost`'s `:slots` lowers those
  positions for every use site at once, and children have never needed a
  conversion at all.

  **Explicit rather than inferred, and that is the design.** Nothing in
  the codec asks whether a value 'looks like' hiccup: at an undeclared
  foreign prop a vector is data, because whether it is markup is a fact
  about the foreign ABI and only the author holds it. This is what makes
  every conversion at a crossing visible in the source.

  Legal wherever a render extent is open — a body, or a render callback
  the body supplied. Outside every extent it converts markup as usual,
  and an intent written in that markup stays the loud
  `:rf.error/hicasso-intent-outside-boundary` it is everywhere else.
  `re-frame.hicasso.impl.codec/as-element`."}
       as-element rf.hicasso.impl.codec/as-element)

     (def ^{:doc "`h/as-component` — **the outward bridge**.
  Answers a real React component for a hiccup head, so a React parent —
  UIx, Reagent or plain JavaScript — mounts a minted Hicasso
  view under the frame it is already in:

      (def article-card* (h/as-component article-card))

  Declared once at top level, beside the view. The parent's props arrive
  as the view's ordinary props map (`articleId` → `:article-id`),
  children at `:children`, values as this shallow decode finds them — a
  Reagent parent's `[:>]` converts first, so names round-trip across the
  crossing and values do not. The frame comes from React context: ANY
  frame, written by any React-shaped adapter, rather than a Hicasso
  root — and no second root, state owner or props ABI appears anywhere.
  Sits HERE rather than on the native tier because a UIx or JavaScript
  parent must not have to require the native namespace — and therefore
  ship it — to cross inward.
  `re-frame.hicasso.impl.codec/as-component`."}
       as-component rf.hicasso.impl.codec/as-component)

     ;; ---- the root lifecycle: mount, re-render, tear down ----------------
     ;;
     ;; Every door here is ROOT-SCOPED, which is why `release!` is not
     ;; among them. `hydrate!` adopts the tree `impl.mount/tree` shapes,
     ;; and `re-frame.hicasso.server` emits that same tree by CALLING it —
     ;; React derives a `useId` from tree position as well as from the
     ;; prefix, so a second function deciding the root's shape for either
     ;; half hydrates into a text mismatch (docs/design/hicasso/product/
     ;; dispositions.md HS-11).

     (def ^{:doc "`h/mount!` — **the root door**: ensure a frame,
  associate it with a DOM container and one root view, and answer the
  handle `render!` and `unmount!` take. HD-021(b)'s whole execution
  contract.

      (h/mount! (js/document.getElementById \"app\")
                {:frame          :rf/default
                 :initial-events [[:counter/initialise]]}
                [counter])

  `(node config view)`, row 20's shape, and the `config` carries three
  keys — one required and two optional.

  **`:frame`** is the frame keyword this root scopes, and mounting
  ENSURES it: the frame is created if it does not exist, or JOINED as it
  stands if another root already uses it. Nothing else in the package
  makes a frame, so a consumer's boot line names the id once here rather
  than twice — to `rf/make-frame` and again to the root door.

  **`:initial-events`** is an ordered vector of ordinary event vectors,
  dispatched synchronously into the frame **when this mount CREATES it**,
  and never when it joins one. They drain in order before this returns,
  so the first paint is the seeded one rather than an empty frame filled
  in a moment later. It is core's own `:initial-events` (EP-0027) reaching
  `rf/make-frame` untouched — the vocabulary `rf/frame-root` and
  `re-frame.adapter.uix/frame-root` already ENSURE with — so its shape and
  its errors are core's, not a second spelling minted here. Seed from the
  mount that creates the frame; a joining root omits it. Initial state
  arrives through events, and there is no separate `:db` seed option.

  **`:identifier-prefix`** is React's own `identifierPrefix`, handed to
  `createRoot` untouched. It exists because `useId` numbers
  every root from the same start, so a page mounting two roots gives them
  distinct prefixes or watches their generated ids collide. A page with
  one root names none. No default, no coercion, no validation: React owns
  the option and this is a pass-through to it. `hydrate!` takes the same
  key, and a hydrating root must be handed the SAME string its server
  render used.

  ## Why this is a `defn` where its siblings are aliases

  `hydrate!`'s reason exactly, and the two doors are the same case seen
  twice: `impl.mount/root!` is `(container frame-kw hiccup opts)` — the
  impl tier's positional shape — and row 20 keeps the guide's config map,
  because a config map is what lets `:initial-events` and
  `:identifier-prefix` join without an arity each. So the adaptation is
  three lines here and impl keeps one caller shape for its own witnesses
  to drive. `impl.mount/root!` keeps its own name for the same reason
  `hydrate!`'s impl keeps `hydrate-root!`.
  `re-frame.hicasso.impl.mount/root!`,
  `re-frame.hicasso.impl.mount/ensure-frame!`."}
       mount!
       ;; `config` reaches `root!` as the opts map WHOLE, the arrangement
       ;; `hydrate!` below already has: impl reads the keys it owns and a
       ;; config key added later needs no edit here.
       (fn mount! [container config hiccup]
         (rf.hicasso.impl.mount/root! container (:frame config) hiccup config)))

     (def ^{:doc "`h/hydrate!` — **adopt a container's existing
  server-rendered DOM** rather than replacing it; `mount!`'s hydrating
  twin, and the client half of every SSR route:

      (h/hydrate! (js/document.getElementById \"app\")
                  {:frame :app/main :identifier-prefix \"main\"}
                  [views/page {}])

  `(node config view)`, and the config carries `:frame` — the frame
  keyword this root scopes — and optionally `:identifier-prefix`.
  Returns the handle `render!` and `unmount!` take, unchanged.

  **State comes first, and it is a different door.** This adopts DOM;
  `re-frame.ssr/hydrate!` installs the server's app-db through
  `:rf/hydrate` and must run BEFORE this, so the first client render
  sees the state the server rendered from:

      (ssr/hydrate! {:frame :app/main})   ;; 1. state
      (h/hydrate! node {:frame :app/main} [views/page {}])   ;; 2. DOM

  **Hand `:identifier-prefix` the same string the server render used.**
  React numbers `useId` per root and prefixes it with this option, so a
  hydrating root given a different prefix — or none, where the server
  had one — resolves every id in the tree differently from the bytes it
  is adopting. `re-frame.hicasso.server/render` takes the same key.

  **It returns BEFORE adoption finishes.** React adopts concurrently
  and nothing here forces it synchronously, so the DOM on the next line
  is still the server's. A witness waits for the adoption window to
  close rather than for a flush.

  Root-scoped, like every door in this section: each hydrating root owns
  its own container, prefix, adoption window and recoverable-error
  stream, so one root's mismatch is reported against that root and
  cannot silence a sibling's.
  ## Why this one is a `defn` where its siblings are aliases

  Every other var here is an alias, because the impl name and the door
  name mean the same call. This door does not: `impl.mount/hydrate-root!`
  is `(container frame-kw hiccup opts)` — the impl tier's positional
  shape — while naming-ledger row 20 keeps the guide's `(node config
  view)` config map, because a config map is what lets
  `:identifier-prefix` join without a second arity. So the adaptation is
  three lines here rather than a second signature down in impl, and impl
  keeps one caller shape for its own witnesses to drive.
  `re-frame.hicasso.impl.mount/hydrate-root!`."}
       hydrate!
       ;; `config` reaches `hydrate-root!` as the opts map WHOLE rather
       ;; than re-built from `:identifier-prefix` — impl reads the keys
       ;; it owns and a config key added later needs no edit here.
       (fn hydrate! [container config hiccup]
         (rf.hicasso.impl.mount/hydrate-root! container (:frame config) hiccup config)))

     (def ^{:doc "Re-render a mounted root in place, synchronously, and
  answer its handle — **the hot-reload door**:

      (defn ^:dev/after-load reload! []
        (h/render! @!root [app {}]))

  React reconciles the new tree against the one on the page, so the
  reloaded view code meets its own DOM. Calling `mount!` again would
  `createRoot` a second time and replace the tree instead, discarding
  every node, subscription and scrap of component state.
  `re-frame.hicasso.impl.mount/render!`."}
       render! rf.hicasso.impl.mount/render!)

     (def ^{:doc "Take THIS root down — `mount!`'s inverse, and
  idempotent. Unmounts the root and leaves everything else exactly where
  it was: the sibling roots' subscriptions and frames, and the container
  you handed `mount!`, which React empties but does not remove.
  `re-frame.hicasso.impl.mount/unmount!`."}
       unmount! rf.hicasso.impl.mount/unmount!)))
