(ns re-frame.hicasso
  "HICASSO — the public door (rf2-hic-001).

  Everything an author writes against lives here, and everything below is
  `re-frame.hicasso.impl.*`. The intended spelling is one alias:

      (ns my.app
        (:require [re-frame.hicasso :as h]))

      (h/defview todo-row [{:keys [id]}]
        (let [todo (h/sub [:todo/by-id id])]
          [:li {:on-click [:todo/toggle id]} (:text todo)]))

  ## Provenance — this package is the prototype, MOVED

  The runtime was measured as `re-frame.bench.hicasso.{front,arm1}.*` in
  `implementation/freehand/test/re_frame/bench/hicasso/`. rf2-hic-001
  copied it here **mechanically**: namespaces renamed, nothing else. The
  bench tree still stands, still runs, and is pinned file-by-file by
  `frozen-sources.edn` beside this source root, so \"the package is the
  prototype\" is a checked claim rather than a remembered one.

  Two consequences of the move are worth naming, because neither is a
  decision this bead took:

  - **`arm1/lang.clj`'s three macros live in THIS namespace.** They are
    the authoring surface, so this is where the bead puts them; their
    bodies are the prototype's, with only the emitted target namespaces
    renamed. The macro names WERE the prototype's too — `hfn` rather than
    `fn`, `hframe` rather than `frame` — because rf2-hic-001 renamed
    nothing and left the gap to the bead that owns naming.

    **Half that gap is now closed.** `hfn` is `event`, taught and exported
    alike as `h/event` (naming-ledger row 1, operator ruling; swept by
    rf2-hic-066). `hframe` still stands: row 18 RETIRES the verb rather
    than respelling it, in favour of core's `rf/current-frame-id` and a
    zero-arity `rf/capture-frame` — and that one waits on a seam, because
    zero-arity `rf/capture-frame` refuses inside a Hicasso body today.
  - **Every var below keeps its prototype name.** This namespace adds no
    behaviour: each `def` is an alias, and the value on the right is the
    one the bench suites measured.

  ## The marker keywords need no export

  `::h/value`, `::h/prevent`, `::h/navigate`, `::h/mounting`,
  `::h/unmounting`, `::h/revision`, `::h/checked` and `::h/clear` already
  read `:re-frame.hicasso/…` in the prototype — the package's name was
  anticipated when they were minted. Alias this namespace as `h` and the
  auto-resolved spelling the guide teaches resolves for the first time,
  with no keyword changing value.

  ## What this door does NOT carry — the optional modules (rf2-hic-053)

  `presence` used to be a var here and is now
  [[re-frame.hicasso.motion/presence]], required separately:

      (:require [re-frame.hicasso :as h]
                [re-frame.hicasso.motion :as motion])

  There is no alias left behind, and that is the point rather than an
  omission. An optional module has to be **absent when unused** — no
  reachable code in an application that never asked for it — and one
  `:require` here would put the retention machine into every bundle that
  ever touched the door. So the door names no optional module, and
  `hicasso/scripts/check_optional_module_reachability.py` fails if one
  reappears. `::h/mounting` and `::h/unmounting` stay in the marker set
  above: they are the vocabulary the motion module reads, and moving the
  namespace is not renumbering the keywords (naming-ledger row 31)."
  ;; The macro side reaches core's `re-frame.source-coords` for the one
  ;; thing a defining macro cannot do portably by hand: pick the right
  ;; `:file` for the coordinate it captures. Under CLJS the analyzer never
  ;; binds Clojure's `*file*` during expansion, so a naive read bakes the
  ;; `"NO_SOURCE_PATH"` sentinel into every declaration; core solved that
  ;; once, absolutises the classpath-relative path while it is there, and
  ;; is already this package's only dependency (rf2-hic-007).
  #?(:clj (:require [re-frame.source-coords :as source-coords]))
  #?(:cljs
     (:require [re-frame.hicasso.impl.boundary :as impl-boundary]
               [re-frame.hicasso.impl.codec :as impl-codec]
               [re-frame.hicasso.impl.collector :as impl-collector]
               [re-frame.hicasso.impl.intent :as impl-intent]
               [re-frame.hicasso.impl.mount :as impl-mount]
               [re-frame.hicasso.impl.portal :as impl-portal]
               [re-frame.hicasso.impl.route-link :as impl-route-link]
               [re-frame.hicasso.impl.state :as impl-state]))
  #?(:cljs (:require-macros [re-frame.hicasso :refer [defview defhost event]])))

;; ---------------------------------------------------------------------------
;; The three macros — `re-frame.bench.hicasso.arm1.lang`, moved
;; ---------------------------------------------------------------------------
;;
;; Bodies verbatim from the prototype; only the three emitted target
;; namespaces are renamed. The prototype kept them in a `.clj` on the
;; grounds that a `.cljc` "would invite a JVM-side implementation this arm
;; does not have". That reason survives the move: this file is a `.cljc`
;; only because a CLJS namespace cannot otherwise hand its macros to a
;; consumer, and its `:clj` side is these three macros and nothing else —
;; no runtime namespace is required on the JVM, so there is still no JVM
;; render path for a reader to mistake for one.

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
  - **[[event]] (`h/event`) is the one callback form**, for when the event
    itself is wanted. At an `on-*` position a returned vector is
    dispatched and any other return is ignored.
  - **A plain function is passed through untouched**, reaching React by
    identity so `React.memo` and every handler-identity bail-out keep
    working.

  The vector and the key map are EVENT-FIRST: both read the DOM event
  from argument one, and a position whose invoker passes something else
  — a value-first foreign callback — refuses loudly at the position
  rather than doing nothing. Which contract a position imposes on a
  callback, and what a [[defhost]] declaration changes about it, is
  [[re-frame.hicasso.impl.intent]]'s position table.

  ## The fn this expands to is ANONYMOUS, and that is the contract
  (rf2-jan2)

  The expansion binds NO name inside your body, so every symbol a body
  resolves is one you wrote. The ordinary extract-a-helper spelling is
  therefore safe at the ordinary spelling:

      (defn todo-row-body [props] …)
      (h/defview todo-row [props] (todo-row-body props))

  It was not always. The macro used to name the fn it emits
  `<sym>-body`, and a named `fn` binds its own name for its own body — so
  the pair above expanded to `(fn todo-row-body [p] (todo-row-body p))`
  and recursed until the stack overflowed, under React exactly as under
  the test kit, reporting `Maximum call stack size exceeded` and naming
  neither the view nor the macro that shadowed the helper.

  Anonymity rather than a gensym or a reserved prefix, because those make
  the collision improbable where this makes it unrepresentable: a fn with
  no name has nothing to shadow with. Nothing was spent for it. The
  identifiers this macro decides are the `\"<ns>/<sym>\"` view name and
  the coordinate below, both computed here and both passed as VALUES —
  `mint-view!` stamps the view name as `displayName` on the body and on
  the component, and Spec 009 keys its render measure `rf:render:<view
  name>` off the same string. The emitted fn's own symbol fed none of
  them; it reached nothing but a stack frame.

  ## Hooks do not belong in the body — a law, and why not a refusal
  (rf2-hic-033)

  A body is dynamically composed: its branches, its `for`s and its
  early returns are all free to follow the data it reads. React's rules
  of hooks are about CALL SEQUENCE, so a hook written in a body would
  make its own order depend on a Hicasso data path — a subscription
  answering one row fewer, and the sequence moves. Hook-intensive
  behaviour belongs in a separately defined native component, where
  React's rules apply to source the author controls: `n/defcomponent`,
  and ordinary `react/useX` interop inside it. [spec §5, Rung 3]

  Nothing enforces this at runtime and nothing here intends to. The
  macro reads no body — that is the contract above, not an omission —
  so a refusal would need a compiler that analyses one, which
  `lanes/hot-path-architecture.md` refuses outright. React is the
  enforcement: a hook whose call order moves fails in React's own
  words, at the boundary that moved it.

  The shell's own two hooks are unaffected and are the whole budget (I9,
  HD-020(b)); `hook-budget-cljs-test` counts them at React's dispatcher.

  ## The source coordinate (rf2-hic-007)

  The expansion opens a declaration extent around the mint, carrying the
  `:ns` / `:file` / `:line` / `:column` this macro read off its own form.
  [[re-frame.hicasso.impl.error/fail!]] resolves it back by view name, so
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
  RESOLUTION ONLY (rf2-5qaf4)

  The declaration publishes one entry in re-frame's `:view` registrar,
  under `(keyword \"<ns>\" \"<sym>\")` — the id `rf/reg-view` derives
  from its own symbol, one convention for both substrates — carrying the
  coordinate above and the minted head at `:hicasso/component`. It
  carries NO `:handler-fn`: a boundary is a React component rather than
  a hiccup-returning render fn, so `rf/view` answers nil for this entry
  and its *returns the registered render fn* contract stays honest.

  **Forward resolution is the whole of it.** The entry exists so that a
  tool holding a keyword the author WROTE — a story naming its subject,
  an editor jumping to source — reaches the view they meant. It mints no
  runtime identity: a MOUNTED boundary is still keyed by its read set
  and still unnamed, and the tool tier
  ([[re-frame.hicasso.tool]]) is unchanged. The refusals that say so —
  rf2-jkdy, rf2-l86mm, rf2-2og6s — are NOT overturned by this, because
  they answer the BACKWARD question *which view is this runtime
  boundary?* and this answers the forward one, where the author already
  knows and is naming it in source.

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
  [[re-frame.hicasso.impl.collector/publish-view-alias!]] carries the
  slot's shape and why it is written there rather than through
  `rf/reg-view*`."
     [sym & more]
     (let [doc       (when (string? (first more)) (first more))
           [argv & body] (if doc (rest more) more)
           view-name (str (ns-name *ns*) "/" sym)
           ;; rf2-5qaf4 — the registrar id, derived by `reg-view`'s own
           ;; rule (`core-reg-view-macro/expand-reg-view`) so the two
           ;; substrates spell one convention. No `^{:rf/id …}` override
           ;; here: a `defview` has exactly one name and this is it.
           view-id   (keyword (str (ns-name *ns*)) (str sym))
           coord     (source-coords/coords-form (meta &form) *file* (ns-name *ns*))
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
                            ;; ANONYMOUS — see the docstring's rf2-jan2
                            ;; section. A named `fn` binds its own name
                            ;; for its own body, so any name derived from
                            ;; `sym` shadows the author's helper of that
                            ;; name.
                            (fn ~argv ~@body))]
                ;; rf2-5qaf4 — inside the extent, so a refusal the
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
  qualified. It was `hfn` until naming-ledger row 1 was swept
  (rf2-hic-066): the guide had always taught `h/fn`, which is a name the
  door could never carry, because a bare `fn` shadows `cljs.core/fn` for
  anyone who `:refer`s it. `h/handler` was rejected as a cross-adaptor
  false friend — `handler` means return-ignored imperative work in the
  established vocabulary, and this form's contract is `event`.

  **The name states ONE of the three contracts this form can carry**, and
  which one is selected by POSITION rather than by the name, so the three
  travel with the name here rather than a page away (rf2-0fd3b):

  | Position | Contract |
  |---|---|
  | a native `:on-*` prop | **event** — a returned VECTOR is dispatched; any other return is ignored |
  | a `defhost` `:callbacks` entry | **as declared** — `:event`, `:handler` or `:render`, never inferred from an `on*` spelling |
  | any other walked prop | **render** — pure; the return is the render output and is NOT dispatched, and dispatching from inside the call raises `:rf.error/hicasso-dispatch-in-render-position`, named at the position |

  [[re-frame.hicasso.impl.intent]] carries the full table, including the
  two rows that are not contracts at all — `:ref`, which is React's own
  and is excluded from lowering, and everywhere Hicasso does not walk,
  where this is a plain function whose return is ignored.

  Expands to nothing but a marked `fn`:

      (event [e] (js/Array.from (.. e -target -files)) …)
      ;; =>
      (re-frame.hicasso.impl.intent/callback (fn [e] …))

  The value is an ORDINARY FUNCTION — that is the point of the ruling.
  The contract comes from the position it is written at, so there is
  nothing to choose between and nothing that can fail to be callable
  where Hicasso does not walk. See
  [[re-frame.hicasso.impl.intent]] for the position table."
     [argv & body]
     `(re-frame.hicasso.impl.intent/callback (fn ~argv ~@body))))

#?(:clj
   (defmacro defhost
     "**The interop door — the one-line declaration (HD-011).** Name the
  crossing to a foreign React component once; use the resulting var as a
  hiccup head anywhere, indistinguishable from a view:

      (defhost date-picker DatePicker
        {:callbacks {:on-change :event}})

      [date-picker {:selected  due-date
                    :on-change (event [date & _] [:task/set-due date])}]

  `opts` is optional and carries `:callbacks` — a FINITE map from exact
  prop names to `:event`, `:handler` or `:render`, never inferred from an
  `on*` spelling — `:slots`, the ReactNode positions, `:server`, the
  server policy, and `:fallback`, Client-only's placeholder markup:

      (defhost modal Modal
        {:callbacks {:on-close :event}
         :slots     #{:title :footer}})

      [modal {:on-close [:dialog/cancel]
              :title    [:h2 \"Delete article?\"]
              :footer   [:button {:on-click [:article/delete id]} \"Delete\"]}]

  **A `:slots` prop is markup, and every other prop is data.** Some of a
  library's props are ReactNode positions — a modal's title, a compound
  component's footer, `Suspense`'s fallback — and hiccup written at one
  of them is lowered under the frame of the boundary that wrote the
  crossing, so an intent inside a slot fires exactly as an intent inside
  a child does. At an UNDECLARED prop hiccup stays data: `{:title [:h2
  \"Tasks\"]}` hands the library the vector, silently, because a vector
  is a legal value at a data position and nothing can be inferred. That
  is why slots are declared and never guessed. For a one-off,
  [[as-element]] crosses a real element through any prop.

  A slot may not also be a declared callback, may not be `:key` or
  `:ref`, may not be a name the crossing can never emit (`__proto__`,
  `prototype`, `constructor`), and may not be spelled twice — all
  refused at the declaration with
  `:rf.error/hicasso-host-bad-slots`.

  **The same two positions are closed to a `:callbacks` entry**, for the
  same reason and at the same moment: `:key`/`:ref` carry no contract,
  and a contract at `__proto__`, `prototype` or `constructor` could
  never be applied, because the crossing skips those names before it
  reads any declaration. Both are refused with
  `:rf.error/hicasso-host-structural-callback`, and two spellings of one
  callback slot with `:rf.error/hicasso-host-callback-slot-collision`.
  Every question is asked of the CANONICAL slot, so `:on-empty` and
  `:onEmpty` get one answer between them.

      (defhost chart Chart
        {:fallback [:div.chart-skeleton]})

      (defhost themed (.-Provider theme-context)
        {:server :render})

  `:server :client-only` is the DEFAULT and needs no writing: the host
  region renders nothing on the server and nothing on hydration's first
  client pass, and the foreign component mounts once the markup is
  adopted. A declared `:fallback` renders that markup there instead.
  A fresh (non-hydrated) mount renders the foreign component on its
  first pass either way — the placeholder never flashes.

  `:server :render` is the other policy and it is an ASSERTION: *this
  component is safe to render on the server*. The declaration then mints
  no gate at all — the component is the element's own type — so the same
  component, the same props, the same context and the same CHILDREN
  render on the server, on hydration's first pass and on a fresh mount.
  One tree everywhere: no mismatch, no adoption swap, no remount. It is
  the only policy under which a crossing's children reach the server
  response, which is what a transparent wrapper such as a context
  provider needs. If the assertion is false the server render throws —
  `window is not defined` — loudly and at the crossing.

  There is no third policy, `:fallback` beside `:render` is refused —
  the component renders, so nothing stands in for it — and an option
  `defhost` does not know is refused rather than ignored.

  **A declared fallback is INERT MARKUP, and that is enforced.** It is
  walked into one element at the declaration and reused at every use
  site, so a `defview` or `defhost` head written there — an element
  whose body runs later — is refused with
  `:rf.error/hicasso-host-fallback-boundary-head`. Write plain hiccup,
  or declare `:server :render` and render the real subtree.

  Policy lives on the declaration, so every use site inherits it; the
  defaults, the refusals and the crossing itself are
  [[re-frame.hicasso.impl.codec/mint-host!]]'s.

  The callback is an [[event]] and not an intent vector because
  react-datepicker calls `onChange(date, event)` — VALUE-first, with no
  event at argument one — while the vector spelling is EVENT-first
  (HD-024's argument law, in
  [[re-frame.hicasso.impl.intent]]). `[:task/set-due ::h/value]`
  there raises `:rf.error/hicasso-intent-needs-the-event` naming the
  position, and the one callback form is the spelling that sees the
  library's own arguments, in order. At an EVENT-first foreign callback —
  `onDraft(event)` — the vector and its markers are legal and shorter.
  Both halves are witnessed in `arm1/host_hatch_dom_cljs_test`.

  Like [[defview]] it is not a compiler: it expands to a `def` of the
  minted head, reads no body, and captures the name and the source
  coordinate — the name for `displayName`, and both for every diagnostic
  the crossing raises.

  ## Two shapes, and a tail is REFUSED rather than dropped (rf2-3f11)

      (defhost name component)
      (defhost name component opts)

  each with an optional docstring in SECOND position — before the
  component, never after it. Anything past `opts` is refused with
  `:rf.error/hicasso-host-extra-form`, and options that are not a map
  with `:rf.error/hicasso-host-bad-options`.

  The macro used to destructure `[component opts]` off a variadic tail
  and drop everything later in silence, so

      (defhost modal Modal
        {:callbacks {:on-close :event}}
        {:slots #{:title}})   ;; <- minted; the :slots map was GONE

  read back consistent — the head simply had no slots — and the markup
  written at `:title` could never arrive. Two options maps are not
  merged and never were. [[defview]]'s `[argv & body]` has no tail to
  lose, which is what made this the one door in the family that
  swallowed one; the refusal closes the gap `mint-host!`'s own
  unknown-option message already gives the reason for — *reading past an
  option it does not know is how a policy comes to be set and never
  applied* — one layer above that guard.

  Positional flexibility is deliberately NOT the repair. A docstring
  after the component binds to `opts` (the probe reads only the first
  form of the tail, which is the component symbol) and pushes the real
  options map into the discarded tail, so it is refused at whichever of
  the two guards it reaches first rather than accommodated.

  ## The declaration extent earns its keep here (rf2-hic-007)

  `defview` opens one so that a refusal from a body can name the boundary
  it came from. `defhost` opens one for a nearer reason: the refusals
  above — an unknown option, a third `:server` value, a boundary head in a
  declared fallback — are raised by `mint-host!` DURING the expansion's
  own `def`, at namespace load, with no render anywhere on the stack. The
  extent is what puts the offending declaration's file and line on them.
  It is `debug-enabled?`-gated and elides whole under `:advanced` +
  `goog.DEBUG=false`.

  Those same refusals are why the extent closes in a `finally` here
  rather than being abandoned with the aborted `def`: a bad `defhost` is
  the ordinary way a declaration throws, and an HMR runtime catches it
  and keeps the mounted page rendering. Without the `finally` every
  refusal raised afterwards — from an event handler, a timer, anywhere —
  inherited this declaration's `:view` and `:source`. The refusal on its
  way out is unaffected: [[re-frame.hicasso.impl.error/fail!]] builds the
  whole ex-data before it throws."
     [sym & more]
     (let [doc         (when (string? (first more)) (first more))
           forms       (if doc (rest more) more)
           [component opts] forms
           ;; THE TAIL, refused rather than dropped (rf2-3f11). The
           ;; destructure above is fixed-width over a seq of arbitrary
           ;; length, so everything past `opts` used to vanish silently
           ;; — `defview`'s `[argv & body]` has no such tail to lose,
           ;; and this was the one door in the family that swallowed
           ;; one. Detected HERE, in the form, so the extra forms are
           ;; quoted rather than evaluated; raised at load, inside the
           ;; declaration extent, so the refusal carries the same
           ;; coordinate every other `defhost` refusal carries. See
           ;; `impl.codec/refuse-host-extra-forms!` for both halves of
           ;; that argument.
           extra       (seq (drop 2 forms))
           host-name   (str (ns-name *ns*) "/" sym)
           coord       (source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
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
;; The vars — aliases, every one under its prototype name
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^{:doc "**The ambient collector** — read a subscription's value
  from anywhere inside a body, including inside a `when`, a `for` or an
  inlined helper. The edge is recorded where the read happens, so a branch
  not taken contributes no edge.
  [[re-frame.hicasso.impl.collector/sub]]."}
       sub impl-collector/sub)

     (def ^{:doc "**Grouped — the control.** One fixed site takes the whole
  query collection and returns the snapshot the body destructures, so a
  boundary's edge set is a function of its declaration rather than of its
  control flow. [[re-frame.hicasso.impl.collector/use-subs]]."}
       use-subs impl-collector/use-subs)

     (def ^{:doc "`h/frame` in the authoring surface — the frame id KEYWORD
  of the boundary currently rendering, for the handful of core doors that
  take one (`rf/capture-frame`, `rf/with-frame`). Spelled `hframe` because
  a bare `frame` would shadow on a `:refer`.
  [[re-frame.hicasso.impl.intent/hframe]]."}
       hframe impl-intent/hframe)

     (def ^{:doc "`h/error-boundary` — the runtime's own error boundary
  (HD-020(c)); takes `:fallback`, `:reset-key` and `:on-error`.

  Named for React's own term of art, which is also what the naming ledger
  ruled (row 12, rf2-g8rb). A bare `boundary` would have been the wrong
  word twice over: every minted `defview` is already *a boundary* here,
  and React has a second kind — the Suspense boundary — that this one is
  not. [[re-frame.hicasso.impl.boundary/boundary]]."}
       error-boundary impl-boundary/boundary)

     (def ^{:doc "`h/reg-state` — the instance-key sugar (HD-009). Mints one
  parametric subscription and one setter event under `[:ui ::concern ikey]`,
  and nothing else. [[re-frame.hicasso.impl.state/reg-state]]."}
       reg-state impl-state/reg-state)

     (def ^{:doc "`h/portal` — **hiccup into `createPortal`** (rf2-hic-028).
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

  A `:target` that is not a DOM node — overwhelmingly a lookup that
  answered nothing — is refused with
  `:rf.error/hicasso-portal-no-target`, naming the value.

  The raw mechanism, for containers the application does not own.
  Anchoring, dismissal and focus conduct are the overlay module's.
  [[re-frame.hicasso.impl.portal/portal]]."}
       portal impl-portal/portal)

     (def ^{:doc "One real anchor, as data — href and click decision taken
  whole from routing's late-bound seams. A plain function, not a boundary:
  it mints no boundary and adds no hook.
  [[re-frame.hicasso.impl.route-link/route-link]]."}
       route-link impl-route-link/route-link)

     (def ^{:doc "`h/as-element` — **the one explicit hiccup→ReactNode
  conversion** (rf2-hic-035). Answers the React element a hiccup form
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
  ([[re-frame.hicasso.impl.intent/render-callback]]) — so a string
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
  [[re-frame.hicasso.impl.codec/as-element]]."}
       as-element impl-codec/as-element)

     (def ^{:doc "`h/as-component` — **the outward bridge** (rf2-hic-032).
  Answers a real React component for a hiccup head, so a native parent —
  `n/defcomponent`, UIx, or plain JavaScript — mounts a minted Hicasso
  view under the frame it is already in:

      (def article-card* (h/as-component article-card))

  Declared once at top level, beside the view. The parent's props arrive
  as the view's ordinary props map (`articleId` → `:article-id`),
  children at `:children`, values by identity; the frame comes from React
  context, and no second root, state owner or props ABI appears anywhere.
  Sits HERE rather than on the native tier because a UIx or JavaScript
  parent must not have to require the native namespace — and therefore
  ship it — to cross inward.
  [[re-frame.hicasso.impl.codec/as-component]]."}
       as-component impl-codec/as-component)

     ;; ---- the root lifecycle: mount, re-render, tear down ----------------
     ;;
     ;; Three doors, one handle, and every one of them ROOT-SCOPED — a page
     ;; may hold as many roots as it likes and no call here reaches a root
     ;; the caller did not name. That is the property rf2-31xm restored and
     ;; the reason `release!` is no longer among them.
     ;;
     ;; **The HYDRATING door is HERE now, and rf2-k1mp's hold is lifted**
     ;; (rf2-b6jkj). The hold was never about the door: `hydrate-root!`
     ;; was built, witnessed, and took `:identifier-prefix` exactly as
     ;; `root!` does (rf2-hic-046). What was missing was the OTHER half
     ;; of the pair — a hydrating door adopts bytes something produced,
     ;; and this package published no server-render door to produce
     ;; them. `dispositions.md` HS-11 measured what an improvised
     ;; counterpart did: the adoption closer rides as a SIBLING of the
     ;; app subtree (`impl.mount/tree`, rf2-6tmu), React derives a
     ;; `useId` from tree POSITION as well as from the prefix, and bytes
     ;; a consumer could bake hydrated into a text mismatch.
     ;;
     ;; `re-frame.hicasso.server` is HS-11's first candidate repair and
     ;; it takes it by CALLING `impl.mount/tree` — one function deciding
     ;; the root's shape for both halves — so the counterpart now exists
     ;; and emits the tree this door adopts, position for position.
     ;;
     ;; The SPELLING is naming-ledger row 13's recommendation
     ;; (`hydrate-root!`→`hydrate!`) applied as a default, which is what
     ;; the ledger header says a recommendation does before the sitting
     ;; and the route rows 21 and 23 already took (rf2-mo4o, rf2-0ckh).
     ;; The CONTRACT SHAPE is row 20's `(node config view)` with
     ;; `:frame` + `:identifier-prefix`, kept as taught.
     ;;
     ;; **Row 13's other half — `root!`→`mount!` — is taken now, and it
     ;; was never a rename** (rf2-7mtcf). The packet's instruction reads
     ;; *"rename two constructors"*, and for this one that is false: the
     ;; guide teaches `h/mount!` over row 20's config map carrying
     ;; `:frame` AND `:initial-events`, and shipped `root!` took the
     ;; frame POSITIONALLY and implemented `:initial-events` nowhere. So
     ;; the var rename alone would have published this name against every
     ;; taught call site and failed at runtime under a green compile —
     ;; new behaviour wearing a spelling's clothes. Row 20 is the half
     ;; that settles it, and it is already ruled *keep as taught*, on the
     ;; ground that `:initial-events` is **borrowed from core's
     ;; `frame-root` vocabulary rather than minted here**. So the door
     ;; below takes the contract, `impl.mount/ensure-frame!` supplies the
     ;; behaviour by calling `rf/make-frame` exactly as a consumer's own
     ;; boot line did, and inventory row HS-10 moves with the name.

     (def ^{:doc "`h/mount!` — **the root door**: ensure a frame,
  associate it with a DOM container and one root view, and answer the
  handle [[render!]] and [[unmount!]] take. HD-021(b)'s whole execution
  contract.

      (h/mount! (js/document.getElementById \"app\")
                {:frame          :rf/default
                 :initial-events [[:counter/initialise]]}
                [counter])

  `(node config view)`, row 20's shape, and the `config` carries three
  keys — one required and two optional.

  **`:frame`** is the frame keyword this root scopes, and mounting
  ENSURES it: the frame is created if it does not exist, or JOINED as it
  stands if another root already uses it. Nothing else in this arm makes
  a frame, so before rf2-7mtcf a consumer's boot line spelled the id
  twice — once to `rf/make-frame` and again to the root door.

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
  `createRoot` untouched (rf2-hic-046). It exists because `useId` numbers
  every root from the same start, so a page mounting two roots gives them
  distinct prefixes or watches their generated ids collide. A page with
  one root names none. No default, no coercion, no validation: React owns
  the option and this is a pass-through to it. [[hydrate!]] takes the same
  key, and a hydrating root must be handed the SAME string its server
  render used.

  ## Why this is a `defn` where its siblings are aliases

  [[hydrate!]]'s reason exactly, and the two doors are the same case seen
  twice: `impl.mount/root!` is `(container frame-kw hiccup opts)` — the
  prototype's positional shape — and row 20 keeps the guide's config map,
  because a config map is what lets `:initial-events` and
  `:identifier-prefix` join without an arity each. So the adaptation is
  three lines here and impl keeps one caller shape for its own witnesses
  to drive. `impl.mount/root!` keeps its own name for the same reason
  [[hydrate!]]'s impl keeps `hydrate-root!`.
  [[re-frame.hicasso.impl.mount/root!]],
  [[re-frame.hicasso.impl.mount/ensure-frame!]]."}
       mount!
       ;; `config` reaches `root!` as the opts map WHOLE, the arrangement
       ;; `hydrate!` below already has: impl reads the keys it owns and a
       ;; config key added later needs no edit here.
       (fn mount! [container config hiccup]
         (impl-mount/root! container (:frame config) hiccup config)))

     (def ^{:doc "`h/hydrate!` — **adopt a container's existing
  server-rendered DOM** rather than replacing it; [[mount!]]'s hydrating
  twin, and the client half of every SSR route (rf2-b6jkj):

      (h/hydrate! (js/document.getElementById \"app\")
                  {:frame :app/main :identifier-prefix \"main\"}
                  [views/page {}])

  `(node config view)`, and the config carries `:frame` — the frame
  keyword this root scopes — and optionally `:identifier-prefix`.
  Returns the handle [[render!]] and [[unmount!]] take, unchanged.

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
  is `(container frame-kw hiccup opts)` — the prototype's positional
  shape — and naming-ledger row 20 keeps the guide's `(node config
  view)` config map, because a config map is what lets
  `:identifier-prefix` join without a second arity. So the adaptation is
  three lines here rather than a second signature down in impl, and impl
  keeps one caller shape for its own witnesses to drive.
  [[re-frame.hicasso.impl.mount/hydrate-root!]]."}
       hydrate!
       ;; `config` reaches `hydrate-root!` as the opts map WHOLE rather
       ;; than re-built from `:identifier-prefix` — impl reads the keys
       ;; it owns and a config key added later needs no edit here.
       (fn hydrate! [container config hiccup]
         (impl-mount/hydrate-root! container (:frame config) hiccup config)))

     (def ^{:doc "Re-render a mounted root in place, synchronously, and
  answer its handle — **the hot-reload door** (rf2-e2al):

      (defn ^:dev/after-load reload! []
        (h/render! @!root [app {}]))

  React reconciles the new tree against the one on the page, so the
  reloaded view code meets its own DOM. Calling [[mount!]] again would
  `createRoot` a second time and replace the tree instead, discarding
  every node, subscription and scrap of component state.
  [[re-frame.hicasso.impl.mount/render!]]."}
       render! impl-mount/render!)

     (def ^{:doc "Take THIS root down — [[mount!]]'s inverse, and
  idempotent. Unmounts the root and leaves everything else exactly where
  it was: the sibling roots' subscriptions and frames, and the container
  you handed [[mount!]], which React empties but does not remove.
  [[re-frame.hicasso.impl.mount/unmount!]]."}
       unmount! impl-mount/unmount!)))
