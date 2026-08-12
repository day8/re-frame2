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
    renamed. The macro names are the prototype's too — `hfn` rather than
    `fn`, `hframe` rather than `frame` — because rf2-hic-001 renames
    nothing. The authoring surface those spellings were chosen FOR is
    `h/fn` and `h/frame`; closing that gap is a naming decision and
    belongs to the bead that owns naming, not to a mechanical move.
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
  #?(:cljs (:require-macros [re-frame.hicasso :refer [defview defhost hfn]])))

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
  that never completed."
     [sym & more]
     (let [doc       (when (string? (first more)) (first more))
           [argv & body] (if doc (rest more) more)
           view-name (str (ns-name *ns*) "/" sym)
           coord     (source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~view-name ~coord))
            (try
              (re-frame.hicasso.impl.collector/mint-view!
                ~view-name
                ;; ANONYMOUS — see the docstring's rf2-jan2 section. A
                ;; named `fn` binds its own name for its own body, so any
                ;; name derived from `sym` shadows the author's helper of
                ;; that name.
                (fn ~argv ~@body))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))

#?(:clj
   (defmacro hfn
     "**The one callback form** (HD-024). `h/fn` in the authoring surface;
  spelled `hfn` here because `h/fn` is qualified in the product and a
  bare `fn` would shadow `cljs.core/fn` on a `:refer`.

  Expands to nothing but a marked `fn`:

      (hfn [e] (js/Array.from (.. e -target -files)) …)
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
                    :on-change (hfn [date & _] [:task/set-due date])}]

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

  The callback is an [[hfn]] and not an intent vector because
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
        :render-row (h/hfn [i]
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

     (def ^{:doc "Associate a DOM container, a frame keyword and a hiccup
  tree; returns the handle [[render!]] and [[unmount!]] take. HD-021(b)'s
  whole execution contract. [[re-frame.hicasso.impl.mount/root!]]."}
       root! impl-mount/root!)

     (def ^{:doc "Re-render a mounted root in place, synchronously, and
  answer its handle — **the hot-reload door** (rf2-e2al):

      (defn ^:dev/after-load reload! []
        (h/render! @!root [app {}]))

  React reconciles the new tree against the one on the page, so the
  reloaded view code meets its own DOM. Calling [[root!]] again would
  `createRoot` a second time and replace the tree instead, discarding
  every node, subscription and scrap of component state.
  [[re-frame.hicasso.impl.mount/render!]]."}
       render! impl-mount/render!)

     (def ^{:doc "Take THIS root down — [[root!]]'s inverse, and
  idempotent. Unmounts the root and leaves everything else exactly where
  it was: the sibling roots' subscriptions and frames, and the container
  you handed [[root!]], which React empties but does not remove.
  [[re-frame.hicasso.impl.mount/unmount!]]."}
       unmount! impl-mount/unmount!)))
