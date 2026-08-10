(ns re-frame.hicasso.impl.intent
  "INTENT LOWERING — deliverable 3 of the Wave-1 shared front half
  (rf2-2rtt6.8). Ergonomics-as-data: the author writes what should
  happen, and the front half, not the author, turns it into the closure
  the browser calls.

      [:button {:on-click [:todo/toggle id]} \"done\"]
      [:input  {:on-input [:todo.ui/edit id ::h/value]}]
      [:form   {:on-submit [:todo/create]}]
      [:input  {:on-key-down {\"Enter\"  [:todo/commit id]
                              \"Escape\" [:todo.ui/cancel id]}}]
      [:a      {:href \"#\" :on-click [::h/prevent [:todo/show-done]]}]

  authoring.md's whole event surface, and nothing beyond it. Four
  behaviours, dispatched on the *shape* of the value at an `on-*` prop
  so that there is no roster of blessed prop names to keep in step with
  the DOM:

  | value      | lowered to |
  |------------|------------|
  | vector     | a closure dispatching that intent |
  | map        | a composition-gated key-map |
  | [[callback]] | one form; the POSITION selects its contract — see below |
  | fn         | passed through untouched — ordinary functions stay legal |
  | anything else | passed through untouched |

  ## ONE callback form, and the position selects the contract (HD-024)

  When a vector is not enough, the predecessor asks the author to pick
  from a roster of FOUR forms, each with a different contract — one
  returning an event vector, one whose return is ignored, one that is
  pure and runs during a foreign render, one that passes a function
  through by identity — and then adds a FIFTH rule about *where the
  roster reaches*. Outside that reach the failure is not even the
  library's: a roster carrier handed to a raw `#js` prop is a marker
  object rather than a function, so the author gets the engine's own
  `TypeError`, \"naming nothing you wrote\".

  Hicasso ships **one** form, [[callback]] (`h/fn`), and it is an
  ORDINARY FUNCTION. The contract comes from the position, because the
  runtime already knows every position it walks:

  | Position | How it is recognised | Contract |
  |---|---|---|
  | a native `:on-*` prop | [[event-prop?]] — the same two-shape rule as an intent vector | **event**: a returned VECTOR is dispatched; any other return is ignored |
  | a `defhost` `:callbacks` entry | the declaration already says `:event`, `:handler` or `:render` — never inferred from an `on*` name | as declared; `:handler` is the return-ignored contract |
  | any other prop position (a slot, a render prop) | not an event position | **render**: pure. The return is the render output and is NOT dispatched; dispatching *from inside the call* is a loud error naming the position |
  | `:ref` | [[ref-position?]] | React's own: commit phase, the node as the argument, the return as the detach cleanup. Excluded from lowering entirely |
  | anywhere Hicasso does not walk — a raw `#js` prop, a value handed to a foreign API | it is not a position | it is a plain function and it simply runs; the return is ignored |

  That last row is the deletion. There is no carrier object, so there is
  nothing that can fail to be callable, so the fifth rule has nothing to
  govern.

  **THE DECLARATION GOVERNS EVERY CARRIER AT ITS POSITION, not just the
  callback.** A declared position accepts four carriers — the one callback
  form, an intent vector, a key-map, and an ordinary value — and the row
  above is a law about the POSITION, so the contract has to be the outer
  question and the carrier the inner one.
  [[lower-declared-prop]] is written that way on purpose: dispatch on the
  contract, then on the value. Reading the value first is how a
  declaration that says `:handler` ends up silently dispatching a bare
  intent, and how a `:render` position dispatches during the foreign
  component's render — the value quietly selecting the contract, which is
  the exact defect the ruling deletes. A dispatching carrier at
  `:handler` or at `:render` is therefore
  `:rf.error/hicasso-intent-at-a-non-event-contract`, named at the
  position, rather than a contract silently overridden.

  **A Hicasso view's own props map is not a position — it is data in
  transit.** An intent vector handed to a view is not lowered there
  either; the view puts it on an element and *that* position lowers it.
  A callback travels the same way, and a callback a view simply *calls*
  is ordinary Clojure with no foreign ABI in between, so there is nothing
  to protect. Which is also why the render row is not free-floating
  policy: it bites exactly where something other than Hicasso invokes the
  callback.

  **`raw-fn`'s identity passthrough is not v0, and costs nothing to
  omit**: [[re-frame.hicasso.impl.codec/convert-prop-value]]
  already passes functions to React by identity, deliberately, so that
  `React.memo` and every downstream bail-out that compares handler
  identity keep working. The behaviour the predecessor spells as a fourth
  roster form is the default here. The census agrees with the omission —
  zero foreign components across 85 idiomatic files.

  ## The argument law: the vector spelling is EVENT-FIRST

  One law, and it covers every feature of the vector spelling that reads
  the event — `::h/prevent`, `::h/value`, `::h/checked`, `:on-submit`'s
  auto-prevent and the key-map's `.key` lookup alike: **a vector or a
  key-map takes the DOM event from argument ONE.** That is what every
  native position hands it, and what an event-first foreign contract
  (`(on-draft event)`) hands it too.

  A value-first foreign invoker — `(on-pick value event)`, the date
  picker's `(on-change date)` — has no event at argument one, and there is
  nothing to guess: the vector cannot know which of the library's
  arguments is the event, and a runtime that went looking for one would be
  reintroducing inference at the exact seam HD-011 forbids it. **`h/fn` is
  that spelling**, and it needs no rule of its own, because the one form
  receives every argument the invoker passed, in order.

  What the law buys is that the violation is LOUD.
  [[event-arg!]] checks the one property the closure is about to read and
  raises `:rf.error/hicasso-intent-needs-the-event` naming the position,
  the intent, and the argument that actually arrived. Without it the
  author gets `value.preventDefault is not a function` — the engine's own
  `TypeError`, naming nothing they wrote, which is the failure class the
  whole ruling exists to delete.

  It is paid only by the closures that read the event. An intent carrying
  no marker and no prevent never touches its argument, so it costs
  nothing, and it is correct under ANY invoker contract — which is why the
  overwhelmingly common `[:todo/toggle id]` at a declared position needs
  no law at all.

  ## The frame, and why the closure closes over it

  HD-020(a): a boundary resolves its frame **once**, from the substrate's
  single internal context, and binds it ambiently for the render's
  dynamic extent so inlined helpers and generated callbacks resolve it
  without hooks of their own. [[*dispatch*]] is that ambient binding —
  the frame-locked `dispatch` the substrate hands back for this
  boundary's frame.

  Lowering reads it **at lowering time**, during the render, and the
  generated closure closes over the value. That is the load-bearing part:
  the browser invokes these callbacks long after the render's dynamic
  extent has unwound, and a closure that read an ambient binding *when
  the click happened* would find nothing there. Reading it eagerly is
  also the cheaper of the two — one read per lowered props map instead of
  one per event.

  A vector at an event position with no ambient frame is a loud error,
  never a silently inert handler.

  [[hframe]] (`h/frame` in the authoring surface) is the AUTHOR-facing
  half of the same binding — the one door an author has to the frame
  identity the lowering reads implicitly. See its docstring; the design
  record is `docs/design/hicasso/studio/hframe-design.md` (rf2-841vn).

  A RENDER callback ([[render-callback]]) re-establishes that ambient
  context for its own invocation, out of what it captured when it was
  lowered, so a row built inside a foreign `renderRow` belongs to the
  boundary that SUPPLIED the callback — the only frame that can own it.
  The render-position refusal is scoped to the invocation and not to
  everything the invocation lowered: poison while the call is running,
  forward to the owner after it returns (rf2-2rtt6.74).

  ## The marker roster and its one pure materializer

  Two markers, both of which the ruled surface names:
  `:re-frame.hicasso/value` (authoring.md's `::h/value`) and
  `:re-frame.hicasso/checked` — the controlled pair HD-010's owned-literal
  merge law and HD-019's controlled door both speak of. They are ordinary
  qualified keywords; the namespace segment names the product namespace
  HD-017 forbids *creating* before P2, which costs a keyword nothing.

  [[materialize]] is the one pure materializer: it substitutes markers at
  the intent vector's top level, which is the shape the corpus writes
  (`[:todo.ui/edit id ::h/value]`), and does not walk nested structure —
  a per-event deep walk to serve a shape nobody writes would be paid on
  every keystroke of every controlled field.

  **The static/dynamic split is decided once, at lowering time.** An
  intent carrying no marker lowers to a closure that dispatches a vector
  it already holds; only a marker-carrying intent pays a materialization
  per event. Deciding this per render rather than per event is why the
  controlled path costs one allocation per keystroke and the ordinary
  button path costs none.

  ## The policy defaults

  - **`:on-submit` auto-prevents** (authoring.md, census-weighted), and
    the opt-out for the rare form that wants a real submission is the
    existing `h/fn` escape — a callback is handed the event, so the event
    is the callback's.
  - **Anywhere else, prevention is opted IN by one reserved head**:
    `[::h/prevent [:conduit/show-your-feed]]`. It is a head rather than
    metadata because **metadata does not participate in `=`**, and
    HD-021's headless door sells itself on intent vectors assertable by
    equality: `(= [:app/go] ^{::h/prevent? true} [:app/go])` is `true`,
    so the one axis the annotation carried was the one axis a structural
    test — or a log, or a snapshot — could not see. `preventDefault`
    changes the event's `canceled` flag; it is behaviour, and behaviour a
    spelling hides from the product's own testing story is a defect
    rather than a taste. A click cannot simply auto-prevent the way
    submit does: a modifier-click on a real link must still open a tab,
    so the anchor-acting-as-a-button needs an explicit opt-in and this is
    its spelling (HD-026).
  - **The key-map is composition-gated, centrally.** A key event arriving
    mid-composition commits nothing: `isComposing`, or the legacy
    keyCode-229 signal that is all some IMEs on some browsers ever send.
    authoring.md pins the case that must not fire — a composing Enter —
    and the gate is written over the whole map rather than that one key,
    because during composition every keystroke belongs to the IME and a
    per-key exception list is a second place for the law to rot.
  - Each key-map branch is lowered **once per render** into a plain map
    of key-string → handler, so an event is one `.-key` lookup and no
    allocation.

  ## The navigate head (rf2-2rtt6.54)

  The SECOND reserved head, and the router-owned counterpart of
  `::h/prevent`: `[::h/navigate {…}]` at an event position is the click
  half of [[re-frame.hicasso.impl.route-link/route-link]]. The
  route-link view puts it on the anchor it builds, carrying the whole
  click decision AS DATA — the render-captured frame, the routing-owned
  dispatch payload, the native-attrs verdict, and the caller's veto:

      [:a {:href \"/profile/jane\"
           :on-click [::h/navigate {:frame    :app/frame
                                    :payload  [:rf.route/url-requested {…}]
                                    :native?  false
                                    :veto     nil}]}]

  Same school as the prevent head (HD-026): behaviour in the vector where
  `=` can see it, a closed grammar — [[unwrap-navigate]] is the whole of
  it, and it asserts the map's EXACT key set ([[navigate-keys]]) rather
  than the presence of the keys it happens to know — classified once at
  lowering, loud on every malformed form. The
  click LAW is not restated here: the lowered closure hands the event to
  routing's own `:routing/activate-link!` late-bound seam — the same one
  decision `rf/route-link`, `ui/route-link` and Freehand's `v/route-link`
  all run — so caller-veto-first, modifier-click deferral, native-anchor
  deferral and `preventDefault`-then-dispatch stay routing's law, stated
  once. A hook that vanished between render and click (dev hot-reload of
  the routing artefact) degrades to native navigation — the browser
  follows the real `href` — after running the veto, exactly as Freehand's
  seam degrades.

  The `:veto` slot is where the two heads compose, and the composition is
  the existing grammar: `[::h/prevent [:app/event]]` there lowers to the
  ordinary prevent closure, `activate-link!` runs it FIRST, sees
  `defaultPrevented`, and stands down — the navigation is cancelled and
  the app intent dispatched instead. That is the cancelable-navigation
  case the prevent head was built for, reached with no new machinery. A
  BARE intent vector at the veto slot is refused loudly: a route click
  already produces the one routing intent, and a second un-prevented
  intent site on the same click would be one user action yielding two
  semantic events (Freehand's route-link law, kept).

  This namespace deliberately does not implement `defhost`/`[:>]`
  interop (HD-011, its own surface), or any controlled-value restore.
  HD-019's door belongs to whatever owns the DOM element, which is the
  emitter rather than the lowering:
  [[re-frame.hicasso.impl.controlled]] wraps the handler this
  namespace produced, after it has produced it, and nothing about the
  lowering changes because of it (rf2-fki5d)."
  (:require [re-frame.frame :as frame]
            [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.late-bind :as late-bind]))

;; ---------------------------------------------------------------------------
;; The ambient frame (HD-020(a))
;; ---------------------------------------------------------------------------

(def ^:dynamic *dispatch*
  "The frame-locked `dispatch` for the boundary currently rendering,
  bound for the render's dynamic extent. `nil` outside a render, which is
  what makes an intent lowered outside a boundary a loud error."
  nil)

(def ^:dynamic *frame*
  "The frame KEYWORD for the boundary currently rendering, bound for the
  render's dynamic extent alongside [[*dispatch*]]. `nil` outside a
  render. [[re-frame.hicasso.impl.route-link/route-link]] reads it
  to capture the render frame into its navigate vector — a browser click
  fires long after the render's dynamic extent has unwound, so the frame
  must travel as data (the same render-time capture Freehand's
  `v/route-link` performs with `require-current-frame!`)."
  nil)

(def ^:private ambient-frame-refusal
  "The detail this arm hands core's refusal tier (rf2-2rtt6.122). One
  module-level constant, so the prose — which is nearly all of it — is
  built once at load and never per body. [[with-frame]] stamps the
  rendering boundary's own frame onto a copy of it (rf2-nqj22); that
  `assoc` is the whole per-extent cost, and it buys the one property a
  shared constant cannot express: which frame THIS body has.

  The sentence has to be the one an author can act on, because the whole
  point of the tier is that the generic `:rf.error/no-frame-context` advice
  — establish a scope — is WRONG here: a body always sits under a frame
  boundary, so following it would change nothing and the boundary would go
  on quietly not re-rendering.

  IT HAS TO ADDRESS THREE DOORS, NOT TWO (rf2-hnrww). The ambient scope is
  one door with three consumers — a read, a dispatch, and a CARRY — and
  this text was written for the first two. An author who trips the refusal
  through `rf/capture-frame` is doing neither: `capture-frame` is Spec
  002's *one public carry primitive*, and the reason its 0-arity resolves
  ambiently is to CAPTURE, not to read and not to dispatch. Advice that
  says \"read through the collector, dispatch through an intent\" names
  nothing they can write. So the carry gets its own sentence, and it is a
  different sentence — not the collector and not an intent, but the
  1-arity, which never consults the resolver at all."
  {:substrate :hicasso
   :extent    'hicasso/boundary-render
   :recovery  :read-through-the-boundary-collector
   :reason    (str "Read through the boundary's own collector (`sub`), which is what "
                   "makes a read an EDGE the boundary re-renders on, and dispatch "
                   "through an intent at a handler position (which lowers to this "
                   "boundary's frame-locked dispatch) or through an explicitly framed "
                   "call. An ambient subscribe here would resolve the boundary's frame "
                   "and then mutate the subscription cache during the render phase "
                   "while contributing ZERO collector edges — leaving a boundary that "
                   "never re-renders when that subscription moves, which is HD-002 "
                   "clause (a)'s forbidden class. It used to succeed silently under "
                   "some adapters and throw under others; now it refuses under all "
                   "of them. "
                   "CARRYING a frame out of the render is a THIRD thing, and its "
                   "recovery is neither of the above: `(rf/capture-frame)` resolves "
                   "ambiently, so it refuses here, but `(rf/capture-frame <frame-id>)` "
                   "never consults the resolver at all. Write "
                   "`(rf/capture-frame (h/frame))` — h/frame is this substrate's own "
                   "deterministic read of the rendering boundary's frame id, and the "
                   "composition is refusal-immune by construction.")})

(defn with-frame
  "Run `body-fn` with the boundary's render context bound ambiently. Two
  doors: an ARM binds both the frame keyword and its frame-locked
  `dispatch` (the 3-arity — the one [[route-link]] and the navigate head
  need); a lowering test that only drives closures may bind the dispatch
  alone (the 2-arity), and anything frame-keyword-dependent it lowers
  stays a loud error rather than a silent guess.

  THE THIRD VAR IS CORE'S REFUSAL TIER (rf2-2rtt6.122). This extent is
  exactly the render extent HD-002 clause (a) governs, so it is exactly
  where ambient `rf/subscribe` / `rf/dispatch` must stop resolving. It is
  bound HERE, fused into the binding this function already performs, rather
  than through `frame/call-with-ambient-frame-refused` around the call:
  `binding` pushes and pops its whole set once, so the fence costs the arm
  no additional frame — the general seam exists for substrates that are not
  already binding something.

  It covers all three doors deliberately. A body is the obvious one, but
  the error boundary's fallback and the presence tray's retained children
  are author-written render-phase code walked under this same binding, and
  an ambient read is exactly as invisible to the collector there.

  An explicitly carried frame is untouched, in this extent as everywhere:
  `{:frame <id>}` never consults the ambient resolver, and `rf/with-frame`
  naming THIS boundary's frame still answers inside a body. The refusal
  deletes the ambient FIND, not the carrying.

  A BODY HAS ONE FRAME, AND NOW BY CONSTRUCTION (rf2-nqj22). The one thing
  the sentence above did not cover: an `rf/with-frame :b` ENCLOSING a
  boundary that renders `:a`. Core was behaving exactly as EP-0002 says —
  `:b` was carried, so `:b` answered — but the boundary is rendering `:a`,
  and the collector's reads, the lowered intents and the presence tray all
  target `:a`. One body, two frames, chosen by which spelling the author
  reached for, and silent. It is reachable from any host that renders a
  Hicasso tree inside a scope: a `flushSync` mount under a `with-frame`, an
  SSR host wrapping `renderToString`, a test fixture that root-binds an
  ambient frame. So this extent tells core WHICH frame it is rendering
  (`:extent-frame`) and core refuses a carried stamp that names another —
  the only carry that stops working is the one that was already wrong.
  The 2-arity names no frame, so it declares none and nothing is refused
  there: an extent with no frame of its own has nothing to be mismatched
  against."
  ([dispatch body-fn] (with-frame nil dispatch body-fn))
  ([frame-kw dispatch body-fn]
   (binding [*frame*                       frame-kw
             *dispatch*                    dispatch
             frame/*ambient-frame-refusal* (cond-> ambient-frame-refusal
                                             frame-kw (assoc :extent-frame frame-kw))]
     (body-fn))))

;; `fail!` is `re-frame.hicasso.impl.error`'s — one constructor for the whole
;; package, and the ambient view and source coordinate come with it
;; (rf2-hic-007). The eight lines that stood here were one of six identical
;; copies.

(defn- require-dispatch
  "The frame-locked dispatch this lowering needs, or the loud refusal.

  **The message offers TWO readings, because the runtime cannot tell
  them apart** (rf2-2rtt6.103). `*dispatch*` being `nil` says only that
  no render window is binding one; it does not say why, and the two
  whys want opposite repairs:

  1. The form really was lowered outside any boundary — a declaration,
     a `defhost` fallback, a module-level `def`. Lower it in a body.
  2. The form was lowered inside a function some foreign component
     invokes LATER, after the render window that bound the dispatch has
     unwound. **A function prop on a `[:>]` crossing is exactly this**:
     the escape carries no declaration, so no position claims the slot
     and nothing installs the render wrapper that captures the owner's
     dispatch and forwards to it. From where the author sits they ARE
     inside a boundary's render — they wrote the crossing in a body — so
     a message offering only reading 1 reads as a framework bug.

  Naming `defhost`'s `:callbacks {… :render}` is what makes reading 2
  actionable: a declared `:render` slot is the position that owns the
  frame, and declaring it is the whole of the repair."
  [intent]
  (or *dispatch*
      (fail! :rf.error/hicasso-intent-outside-boundary
             'front.intent/lower-prop
             (str "No frame-locked dispatch is bound for this render. Intent "
                  (pr-str intent) " was lowered with no ambient frame. Either the "
                  "form is outside any boundary's render — a declaration, a "
                  "fallback, a module-level def — in which case lower it inside a "
                  "body; or it is inside a function a foreign component invokes "
                  "after that render returned, which is what a function prop on a "
                  "[:>] crossing is: the escape carries no declaration, so nothing "
                  "claims the slot and nothing forwards to the owner. Declare the "
                  "crossing instead — defhost with :callbacks {<the prop> :render} "
                  "— and the position owns the frame.")
             :lower-intents-inside-a-boundary-render
             {:intent intent})))

;; ---------------------------------------------------------------------------
;; The author-facing frame read — `h/frame` (rf2-841vn)
;; ---------------------------------------------------------------------------

(defn hframe
  "**`h/frame` in the authoring surface.** The frame id KEYWORD of the
  boundary currently rendering. A plain function call, legal anywhere in
  a body, and a loud error outside a render extent.

  Spelled `hframe` here for exactly the reason
  [[re-frame.hicasso/hfn]] is spelled `hfn`: the product
  name is qualified (`h/frame`), and a bare `frame` would shadow the
  `re-frame.frame` alias that this namespace — and every other namespace
  in the arm — already carries.

  ## What it is FOR, and the honest layering (design §6)

  It serves ONE author: someone at a foreign edge who must hand a
  dispatching closure to a caller they do not control — an SDK attach
  ref, a value-first callback on a foreign component, a `defhost`
  callback slot. What that author captures is not time; it is **frame
  identity at the one moment it is knowable**.

  Hand-written async *work* is a different problem and this is not its
  answer. An fx handler already receives the frame id in its ctx and
  `:dispatch-later` already expresses delay as data; a `setTimeout` in a
  view that dispatches is mis-layered, and no primitive here is designed
  for it. `h/frame` does make that spelling *work* where it used to fail
  — that softening is stated on the design record rather than argued
  away, and the compensation is that every guide row putting `h/frame` on
  the page puts `:fx` first.

  ## The carry spelling is COMPOSITION, not a second primitive

      (rf/capture-frame (h/frame))

  Hicasso contributes the deterministic frame READ; core keeps
  `capture-frame` as what Spec 002 calls *the ONE public carry
  primitive*. The two-step spelling, against the adapters' one-step
  `(rf/capture-frame)`, is the taught asymmetry, and it has a one-
  sentence reason: **ambient frame lookup is what Hicasso's stricter body
  discipline withdraws** (rf2-2rtt6.122 — see [[ambient-frame-refusal]]).

  THE LOAD-BEARING FACT is narrower than it looks. The refusal deletes
  the ambient FIND, never the carrying, so `rf/with-frame` and
  `{:frame <id>}` both still answer inside a body — but neither is the
  author-facing answer, because both *presuppose knowing the frame id*,
  which is exactly what a reusable view mounted under N frames cannot
  know. What makes the composition work is that **`capture-frame`'s
  1-arity never consults the resolver at all**, so
  `(rf/capture-frame (h/frame))` is refusal-immune by construction, and
  `h/frame` supplies the id the carrying spellings presuppose.

  ## Why it reads [[*frame*]] and not the runtime's render slot

  Load-bearing, and the difference is visible in one position. The
  rf2-2rtt6.74 render-position work rebinds [[*frame*]] to the
  **supplying** boundary while a foreign component invokes a render
  callback ([[render-callback]]), where the runtime's own `rstate.frame`
  is nil — the foreign render runs outside the arm's render pass. So
  reading the binding answers the OWNER's frame inside a render callback
  for free, and answers it immune to tree position, adapter, renderer and
  timing. [[re-frame.hicasso.impl.route-link/route-link]] is the
  internal consumer already doing exactly this.

  ## NOT a tracked read, and it must not become one

  Reads become collector edges because they are sub-KEYS. The frame is
  not one: it is render-constant per boundary, resolved once by the shell
  and bound ambiently. This is one dynamic-var read. It appends nothing
  to the collector scratch, registers no edge and takes no React hook —
  so the ≤2-hook shell ledger is untouched, and the frame-prop shell
  variant answers identically because the same ambient is bound either
  way. Reactivity needs no edge: a frame change is a context change or a
  remount, and React propagates context to consumers ahead of the memo
  comparator.

  ## SSR

  Server frames are per-request gensyms destroyed in the render's
  `finally`, and the body runs on Node through this same path — so this
  answers the per-request frame id and per-request isolation holds by
  construction. **The value is process-local identity, carried in
  closures, and must never be placed in markup**: two same-process
  renders take two gensyms, so rendering it would break the render-twice
  determinism the SSR witnesses assert."
  []
  (or *frame*
      (fail! :rf.error/hicasso-frame-outside-boundary
             'front.intent/hframe
             (str "h/frame was called with no Hicasso render extent in scope. It "
                  "answers the frame of the boundary currently rendering, so it is "
                  "legal only during a boundary body — or inside a render callback "
                  "that boundary supplied, where it answers the SUPPLYING "
                  "boundary's frame. Where the call came from decides the fix. "
                  "Hand-written async work belongs in the EVENT layer, which "
                  "already has the frame: an fx handler receives it in its ctx, and "
                  ":dispatch-later expresses the delay as data. An event handler is "
                  "already running under its own frame and should take it from "
                  "there rather than read it here. And code that already knows "
                  "which frame it means should say so: (rf/capture-frame <frame-id>) "
                  "needs no scope at all.")
             :read-the-frame-inside-a-boundary-render
             {})))

;; ---------------------------------------------------------------------------
;; The one callback form (HD-024)
;; ---------------------------------------------------------------------------

(def ^:private callback-marker "hicassoFn")

(defn callback
  "**The one callback form.** Marks `f` so the position it is written at
  can impose a contract on it — and returns `f` ITSELF, an ordinary
  function, which is the whole of the deletion.

  The predecessor's roster carriers are marker OBJECTS, so handing one to
  a position the library does not walk produces the engine's own
  `TypeError` naming nothing the author wrote. There is nothing here that
  can fail to be callable: outside every walked position this value is a
  plain function whose return is ignored, which is a defensible contract
  rather than a crash.

  `h/fn` in the authoring surface; see
  [[re-frame.hicasso/hfn]]. Marking mutates the function
  object, which is safe because a callback written in a body is minted
  fresh per render — and it is one own-property read to test, with no
  registry and no map."
  [f]
  (unchecked-set f callback-marker true)
  f)

(defn callback?
  "Is `v` the one callback form? One own-property read behind a `fn?`
  guard, so a string or a number at a prop position costs one type test."
  [v]
  (and (fn? v) (true? (unchecked-get v callback-marker))))

(defn- event-callback
  "**Event position.** A returned VECTOR is dispatched; anything else is
  ignored, so the same form serves the live-event case (files, geometry,
  filters) and the imperative one without the author choosing between two
  spellings.

  The ambient dispatch is captured at LOWERING time, as everywhere else
  in this namespace — the browser invokes the callback long after the
  render's dynamic extent has unwound. It is captured rather than
  *required*, so a callback that never returns an intent is legal with no
  frame in scope; returning one there is the loud error, and it names the
  position.

  **The census-weighted policy defaults do NOT apply here, deliberately.**
  `:on-submit`'s auto-prevent is a property of the *data* spelling: an
  intent vector never sees the event, so the runtime must decide for it.
  A callback is handed the event, so the event is the callback's — it
  calls `.preventDefault` itself, and the runtime does not reach in after
  the body has run to second-guess it. One rule: whoever holds the event
  owns it.

  **Every argument the invoker passes reaches the body**, the same way
  [[render-callback]] forwards them. A native DOM event position calls
  with one argument and that is the overwhelming case, but this is also
  the wrapper a `defhost` `:callbacks` entry declared `:event` gets, and
  a foreign component's live invoker calls with whatever its own contract
  says — `(on-change value event)`, `(on-select item index)`. Accepting
  exactly `[e]` would silently drop everything after the first, or raise
  an arity error naming nothing the author wrote, against a form whose
  parameter vector is arbitrary by construction."
  [k f]
  (let [dispatch *dispatch*]
    (fn hicasso-event-callback [& args]
      (let [result (apply f args)]
        (when (vector? result)
          (if dispatch
            (dispatch result)
            (fail! :rf.error/hicasso-intent-outside-boundary
                   'front.intent/lower-prop
                   (str "A callback at " (pr-str k) " returned the intent "
                        (pr-str result) " with no frame-locked dispatch in scope. "
                        "Callbacks that dispatch are only legal at a position "
                        "lowered inside a boundary's render.")
                   :lower-intents-inside-a-boundary-render
                   {:position k :intent result})))
        nil))))

(defn- render-callback
  "**Render position.** The callback is invoked by whatever holds it —
  a slot, a foreign component's render prop — DURING a render, so its
  return is the render output and is emphatically not an intent. What is
  forbidden is dispatching from inside it, and the diagnostic names the
  POSITION rather than the form the author chose, because under one form
  the form is never the answer to \"what did I get wrong?\".

  **Enforcement is INVOCATION-SCOPED: poison while the call is running,
  forward to the owner once it has returned.** Poisoning the ambient
  dispatch for the call's dynamic extent and leaving it at that conflates
  two different things, because lowering captures the same var the poison
  replaced. The row a `renderRow` prop exists to build —

      [:li {:on-click [:row/pick (:id row)]} (:title row)]

  — closes over the poison and raises at the USER'S CLICK, for a click:
  a legitimate event position that merely happened to be LOWERED during a
  render (rf2-2rtt6.74). The law is \"dispatching from inside the call\",
  and a closure that will dispatch later is not that.

  **The row above is written as hiccup, and hiccup is not what this
  position returns.** This function ends in a bare `(apply f args)`, so a
  `:render` return crosses UNCONVERTED: a string renders, a vector reaches
  React and is refused there. Something has to turn the row into an
  element, and the something is
  [[re-frame.hicasso.impl.codec/as-element]] — which is INTERNAL.
  Nothing on the authoring surface reaches it, so **the recovery this
  paragraph describes has no spelling an author can write today**; that
  gap is `rf2-2rtt6.120`, and it is a real gap rather than a missing
  sentence. In particular there is **no `h/as-element`**: an earlier
  revision of this docstring illustrated the row with one, and four design
  documents copied the spelling out of here before anyone checked that it
  resolved.

  So the wrapper captures the ambient dispatch AND frame at LOWERING
  time — the supplying boundary's, because the wrapper is minted during
  that boundary's eager `with-frame` + `as-element` walk — and each
  invocation mints a fresh GATE over them, binds it as [[*dispatch*]] for
  the call, and arms it in a `finally`. Call-active, the gate raises;
  call-complete, it forwards to the owner. That is a TEMPORAL
  discrimination, which is the law's own line, rather than a second
  \"capturable\" binding — which would let the synchronous
  lower-and-fire-NOW case succeed silently and weaken the law it was
  meant to preserve.

  [[require-dispatch]], [[intent-handler]] and [[event-callback]] are
  UNCHANGED: they still capture [[*dispatch*]], which inside a callback is
  now the gate, and that one substitution is the whole of the repair.

  [[*frame*]] is rebound to the owner's for the same extent, so a
  [[re-frame.hicasso.impl.route-link/route-link]] in a row body
  pins its navigation to the boundary that SUPPLIED the callback. That is
  the only frame that can own it: the foreign component has no frame of
  its own, and frames are isolated contexts.

  A callback lowered with no owner in scope at all still poisons while it
  runs, and a handler lowered inside it raises the ordinary
  `:rf.error/hicasso-intent-outside-boundary` when it fires — loud, never
  a silently inert handler. `.preventDefault`-style side effects are
  untouched."
  [k f]
  (let [owner-dispatch *dispatch*
        owner-frame    *frame*]
    (fn hicasso-render-callback [& args]
      ;; `armed?` is false for the call's dynamic extent and set in the
      ;; `finally` below, so ARMED means the invocation has RETURNED and
      ;; the gate now forwards rather than raises.
      (let [armed? (volatile! false)
            gate   (fn hicasso-render-gate [event]
                     (cond
                       (not @armed?)
                       (fail! :rf.error/hicasso-dispatch-in-render-position
                              'front.intent/lower-prop
                              (str "A callback at " (pr-str k) " dispatched " (pr-str event)
                                   " while it was running. " (pr-str k) " is a RENDER "
                                   "position: it is invoked during a render, so it must be "
                                   "pure. Move the dispatch to an event position, or to an "
                                   "event handler that owns the work.")
                              :dispatch-from-an-event-position
                              {:position k :event event})

                       owner-dispatch (owner-dispatch event)

                       :else
                       (fail! :rf.error/hicasso-intent-outside-boundary
                              'front.intent/lower-prop
                              (str "A handler lowered inside the callback at " (pr-str k)
                                   " dispatched " (pr-str event) " after the render "
                                   "returned, but the callback itself was lowered with no "
                                   "frame-locked dispatch in scope. A render callback's "
                                   "handlers fire into the frame of the boundary that "
                                   "SUPPLIED it, and there was none.")
                              :lower-intents-inside-a-boundary-render
                              {:position k :event event})))]
        (binding [*frame* owner-frame *dispatch* gate]
          (try
            (apply f args)
            (finally (vreset! armed? true))))))))

;; ---------------------------------------------------------------------------
;; The argument law — the vector spelling is EVENT-FIRST (HD-024)
;; ---------------------------------------------------------------------------

(defn- event-arg!
  "Answer `e` when it is the DOM event this closure needs, and raise a
  diagnostic naming the POSITION when it is not. `slot` is the one
  property the closure is about to read off it — `preventDefault`,
  `target`, `key` — so the check is the read that was going to happen
  anyway, and the message can say which capability was missing rather
  than asserting a type.

  See the namespace docstring §The argument law. The whole point is that
  a value-first foreign invoker produces THIS error, naming the position
  and pointing at `h/fn`, instead of the engine's
  `value.preventDefault is not a function` — which names nothing the
  author wrote and is the failure class HD-024 exists to delete."
  [k form e slot]
  (if (and (some? e) (some? (unchecked-get e slot)))
    e
    (fail! :rf.error/hicasso-intent-needs-the-event
           'front.intent/lower-prop
           (str "The intent " (pr-str form) " at " (pr-str k) " reads the DOM "
                "event's `" slot "`, but the first argument its invoker passed "
                "was " (pr-str e) ", which has none. The vector spelling is "
                "EVENT-FIRST: it takes the event from argument one, which is "
                "what every native position and every event-first foreign "
                "contract hands it. A value-first invoker — (on-pick value "
                "event) — has no event there, and nothing can guess which of "
                "its arguments is one. Write an h/fn instead: the one form "
                "receives every argument the invoker passed, in order.")
           :write-an-h-fn-at-a-value-first-position
           {:position k :form form :argument e :needed slot})))

;; ---------------------------------------------------------------------------
;; The marker roster and its one pure materializer
;; ---------------------------------------------------------------------------

(def value-marker
  "authoring.md's `::h/value` — the event target's current value."
  :re-frame.hicasso/value)

(def checked-marker
  "`::h/checked` — the event target's current checked state."
  :re-frame.hicasso/checked)

(def prevent-head
  "`::h/prevent` — the FIRST reserved intent HEAD. `[::h/prevent [:app/go]]`
  at an event position dispatches `[:app/go]` and calls `.preventDefault`
  first. See [[unwrap-prevent]] for the closed grammar, and the policy
  defaults above for why it is a head rather than metadata."
  :re-frame.hicasso/prevent)

(def navigate-head
  "`::h/navigate` — the SECOND reserved intent HEAD, minted by
  [[re-frame.hicasso.impl.route-link/route-link]] and carrying a
  route-link's whole click decision as data. See the namespace docstring
  §The navigate head, and [[unwrap-navigate]] for the closed grammar."
  :re-frame.hicasso/navigate)

(def ^:private marker-readers
  "The roster, as marker → the reader that pulls its value off the event
  target. A map rather than a chain of comparisons, because it is both
  the roster and the dispatch — and because `identical?` is the wrong
  test for keywords in ClojureScript: literals are shared constants only
  when the build interns them, so an identity comparison that works under
  `:advanced` silently fails in the test build."
  {value-marker   (fn [target] (.-value target))
   checked-marker (fn [target] (.-checked target))})

(defn markers?
  "Does this intent carry a marker at its top level? Answered once, at
  lowering time, so the per-event path never asks."
  [intent]
  (boolean (some marker-readers intent)))

(defn materialize
  "THE ONE PURE MATERIALIZER. Substitute the markers in `intent` with
  values pulled from the DOM event's target. Top level only — see the
  namespace docstring."
  [intent e]
  (let [target (.-target e)]
    (mapv (fn [x] (if-some [read-value (marker-readers x)] (read-value target) x))
          intent)))

;; ---------------------------------------------------------------------------
;; The composition gate
;; ---------------------------------------------------------------------------

(defn composing?
  "Is this key event part of an in-flight IME composition? `isComposing`
  where the browser sets it, and the legacy keyCode-229 signal where it
  is all the browser sends.

  **Both signals are read off the NATIVE event**, and that is not
  defensive tidiness — it is the difference between a live fence and a
  dead one. React does not hand a handler the browser's event: it hands
  it a synthetic one built by copying an enumerated interface, and
  `KeyboardEventInterface` lists key, code, location, the modifier flags,
  repeat, locale, `charCode`, `keyCode` and `which`. **`isComposing` is
  not on that list**, so on React it reads `undefined` however plainly the
  browser set it, and the modern half of this gate would never fire —
  leaving only the legacy signal, on browsers that happen to send it.
  Measured at
  `arm1_controlled_grid_dom_cljs_test/reacts-synthetic-keyboard-event-drops-is-composing`.

  A raw DOM event has no `nativeEvent`, so it falls back to itself and
  the node-side unit tests read exactly as they did."
  [e]
  (let [native (or (.-nativeEvent e) e)]
    (or (true? (.-isComposing native))
        (identical? 229 (.-keyCode native)))))

;; ---------------------------------------------------------------------------
;; Lowering
;; ---------------------------------------------------------------------------

(def ^:private re-event-prop
  "`:on-click` (the taught kebab spelling) or `:onClick` (the camel one a
  migrating author writes). Two shapes, one rule, no roster of DOM event
  names to maintain."
  #"^on-[a-z]|^on[A-Z]")

(defn event-prop?
  "Is `k` an event position?"
  [k]
  (boolean (and (or (keyword? k) (string? k))
                (re-find re-event-prop (name k)))))

(defn- prevent-by-default? [k]
  (let [n (name k)]
    (or (= n "on-submit") (= n "onSubmit"))))

(defn prevent-head?
  "Is `v` the `::h/prevent` decorator? One `=` against the vector's first
  element. Asked once per lowered position, never per event."
  [v]
  (and (vector? v) (= prevent-head (nth v 0 nil))))

(defn navigate-head?
  "Is `v` the `::h/navigate` decorator? Same one-compare shape as
  [[prevent-head?]]."
  [v]
  (and (vector? v) (= navigate-head (nth v 0 nil))))

(defn- reserved-head?
  "Is `v` a vector carrying either reserved head? The roster is a LIST —
  two entries — which is what keeps the grammar closed."
  [v]
  (or (prevent-head? v) (navigate-head? v)))

(defn- unwrap-prevent
  "CLASSIFY AND UNWRAP. `v` is the decorator; answer the intent inside it,
  refusing anything that is not exactly one inner intent vector.

  **The grammar is closed, and this is the whole of it.** `[::h/prevent
  INTENT]` — two forms, the second a non-empty vector that is not itself a
  decorator. Everything else is
  `:rf.error/hicasso-malformed-prevent`, named at the position it was
  written at. There is no options map, no second decorator, no modifier
  language: one reserved head in the same tiny roster as `::h/value`, so
  the thing that keeps it closed is that the roster is a list rather than
  a convention.

  It is deliberately NOT a walker. The decorator is recognised at ONE
  known position — a vector at an event position, which lowering already
  had in its hand — and the answer is a classification taken once per
  render. Nothing descends into the intent, and the inner vector stays
  ordinary data all the way to `dispatch`."
  [k v]
  (let [inner (nth v 1 nil)]
    (when-not (and (= 2 (count v))
                   (vector? inner)
                   (seq inner)
                   (not (reserved-head? inner)))
      (fail! :rf.error/hicasso-malformed-prevent
             'front.intent/lower-prop
             (str "The " (pr-str prevent-head) " decorator at " (pr-str k)
                  " wraps EXACTLY ONE intent vector; this one "
                  (cond
                    (not= 2 (count v))     (str "carries " (dec (count v))
                                                " forms after the head")
                    (reserved-head? inner) "wraps another decorator, and it does not nest"
                    (not (vector? inner))  (str "wraps " (pr-str inner)
                                                ", which is not an intent vector")
                    :else                  "wraps the empty vector, which names no event")
                  ". Write [" (pr-str prevent-head) " [:my-event …]].")
             :wrap-exactly-one-intent-vector
             {:position k :form v}))
    inner))

(declare intent-handler)

(defn- lower-veto
  "Lower the `:veto` slot of a navigate vector into the pre-navigation
  callback routing runs first — a plain one-argument fn, or nil — or
  refuse it loudly. The roster is CLOSED, and it is the route-click law
  that closes it: nil, the `::h/prevent` decorator (the DECLARATIVE veto —
  its lowered closure calls `.preventDefault` and dispatches the wrapped
  intent, and `activate-link!` stands down on `defaultPrevented`, so the
  navigation is cancelled and the app intent takes its place), the one
  callback form, or a plain fn (both the IMPERATIVE veto — whoever holds
  the event owns it, HD-024). A BARE intent vector is refused: the click
  already produces the one routing intent, and an un-prevented second
  intent on the same click is one user action yielding two semantic
  events. [[re-frame.hicasso.impl.route-link/route-link]] refuses
  the same forms at RENDER, where the author's stack is live; this refusal
  is the lowering's own, because a navigate vector is in-band data anyone
  can write."
  [k veto]
  (cond
    (nil? veto)            nil
    (prevent-head? veto)   (intent-handler k veto)
    (callback? veto)       veto
    (fn? veto)             veto
    :else
    (fail! :rf.error/hicasso-malformed-navigate
           'front.intent/lower-prop
           (str "The " (pr-str navigate-head) " decorator at " (pr-str k)
                " carries the veto " (pr-str veto) ", which is outside the "
                "closed roster: nil, [" (pr-str prevent-head) " [:my-event …]] "
                "(cancel the navigation and dispatch this instead), h/fn, or a "
                "plain function. A bare intent vector is refused — the click "
                "already produces the one routing intent; wrap the vector in "
                (pr-str prevent-head) " to veto the navigation, or move the "
                "reaction behind the routing event.")
           :veto-with-prevent-a-callback-or-nothing
           {:position k :veto veto})))

(def ^:private navigate-keys
  "The navigate map's key set, and it is CLOSED: `:frame`, `:payload`,
  `:native?`, `:veto` — those four, no fewer and no more (HD-027).

  CLOSED means a COUNT and not just presence. A grammar that checks only
  the keys it knows is fail-open by construction: under four bare
  presence tests a map missing `:veto` passes — and `:veto` is the slot
  that decides whether the click can be cancelled, so its absence is the
  difference between an uncancelable navigation and a promised one — and
  so does a map carrying a fifth key, which the lowering then silently
  drops: the author wrote something and nothing happened, which is the
  failure class every loud error in this namespace exists to delete.

  `(== 4 (count m))` AND four `contains?` admit exactly the maps
  `(= navigate-keys (set (keys m)))` admits, and allocate nothing to do
  it, so that is what [[unwrap-navigate]] asks on the hot path. This set
  is read by the REFUSAL, which runs once, at the error: building it per
  call was ~156 ns/element of the census page's walk, one
  `PersistentHashSet` per link per render (`rf2-jr0tg`, costed on
  `walk_vs_reagent_app`)."
  #{:frame :payload :native? :veto})

(defn- unwrap-navigate
  "CLASSIFY AND UNWRAP the navigate decorator. `[::h/navigate {…}]` —
  exactly two forms, the second a map whose keys are EXACTLY
  [[navigate-keys]]: `:frame` (a keyword), `:payload` (a non-empty
  vector), `:native?` (a boolean), and `:veto` (the [[lower-veto]]
  roster, `nil` included — which is why its presence is asked and its
  value never is). Everything else is
  `:rf.error/hicasso-malformed-navigate`, named at the position. Answers
  the validated map; like [[unwrap-prevent]] it is not a walker — the
  payload stays ordinary data all the way to routing.

  The key check RUNS ONCE PER LINK PER RENDER, so it allocates nothing:
  the count closes the roster that the four `contains?` open, admitting
  exactly what [[navigate-keys]] as a set admits. The set itself is built
  only in the failure branch, where it names what the author actually
  wrote."
  [k v]
  (let [m  (nth v 1 nil)
        {:keys [frame payload native?]} m]
    (when-not (and (= 2 (count v))
                   (map? m)
                   (== 4 (count m))
                   (contains? m :frame)
                   (contains? m :payload)
                   (contains? m :native?)
                   (contains? m :veto)
                   (keyword? frame)
                   (vector? payload)
                   (seq payload)
                   (boolean? native?))
      (let [ks (when (map? m) (set (keys m)))]
        (fail! :rf.error/hicasso-malformed-navigate
               'front.intent/lower-prop
               (str "The " (pr-str navigate-head) " decorator at " (pr-str k)
                    " wraps EXACTLY ONE map carrying :frame (a keyword), :payload "
                    "(a non-empty event vector), :native? (a boolean) and :veto — "
                    "those four keys and no others; this one "
                    (cond
                      (not= 2 (count v))  (str "carries " (dec (count v)) " forms after the head")
                      (not (map? m))      (str "wraps " (pr-str m) ", which is not a map")
                      (not= navigate-keys ks)
                      (str "carries " (pr-str (vec (sort ks)))
                           (when-some [missing (seq (sort (remove ks navigate-keys)))]
                             (str ", so it is missing " (pr-str (vec missing))))
                           (when-some [extra (seq (sort (remove navigate-keys ks)))]
                             (str ", and nothing reads " (pr-str (vec extra)))))
                      (not (keyword? frame))  "names no :frame keyword"
                      (not (and (vector? payload) (seq payload))) "carries no :payload event vector"
                      :else               "answers no boolean at :native?")
                    ". route-link mints this form; hand-written ones must carry all "
                    "four slots and nothing else.")
               :carry-frame-payload-native-and-veto
               {:position k :form v})))
    m))

(defn- navigate-handler
  "Lower one navigate vector into the closure the browser will call. The
  veto is lowered HERE, at lowering time — a prevent veto needs the
  ambient dispatch, which is gone by click time — and the click decision
  itself stays routing's: the closure hands the event to the
  `:routing/activate-link!` late-bound seam (caller veto first, modifier
  and native deferral, `preventDefault` + dispatch to the captured frame).
  When the hook is unbound at click time — the routing artefact
  hot-reloaded away between render and click — the closure runs the veto
  and otherwise stands aside, so the browser follows the anchor's real
  `href`: native navigation, never a throw at a detached click.
  ([[re-frame.hicasso.impl.route-link/route-link]] already proved
  routing present at RENDER, so absence here is transient by
  construction.)"
  [k v]
  (let [{:keys [frame payload native? veto]} (unwrap-navigate k v)
        veto-fn (lower-veto k veto)]
    (fn hicasso-navigate [e]
      (if-some [activate (late-bind/get-fn :routing/activate-link!)]
        (activate e veto-fn frame payload native?)
        (when veto-fn (veto-fn e)))
      nil)))

(defn- intent-handler
  "Lower one intent vector into the closure the browser will call. The
  three axes — prevent, markers, dispatch — are all resolved here, so the
  event path is the shortest one this intent can have.

  Classification comes FIRST, so the markers compose inside a prevented
  intent: `[::h/prevent [:filter/set ::h/value]]` is unwrapped before
  [[markers?]] is ever asked, and the materializer then sees the ordinary
  intent it has always seen. The navigate head is classified here too —
  the same one-compare, once per render — and its whole lowering lives in
  [[navigate-handler]].

  **Only the branches that read the event carry the argument law**
  ([[event-arg!]], and the namespace docstring's §The argument law): each
  checks the one property its own first read needs, and the `:else`
  branch — an intent with no marker and no prevent, which is the
  overwhelming case — never touches its argument at all, so it costs
  nothing and is correct under any invoker contract."
  [k v]
  (if (navigate-head? v)
    (navigate-handler k v)
    (let [decorated (prevent-head? v)
          intent    (if decorated (unwrap-prevent k v) v)
          dispatch  (require-dispatch intent)
          prevent   (or decorated (prevent-by-default? k))
          dynamic   (markers? intent)]
      (cond
        (and prevent dynamic)       (fn [e] (event-arg! k v e "preventDefault")
                                            (.preventDefault e)
                                            (dispatch (materialize intent e)))
        prevent                     (fn [e] (event-arg! k v e "preventDefault")
                                            (.preventDefault e)
                                            (dispatch intent))
        dynamic                     (fn [e] (event-arg! k v e "target")
                                            (dispatch (materialize intent e)))
        :else                       (fn [_e] (dispatch intent))))))

(defn- key-map-handler
  "Lower a data key-map into one closure over a plain map of key-string →
  handler. The map is built once per render; an event costs one
  composition test and one lookup.

  The argument law applies here too, and the property it needs is `key`:
  without the check a key-map handed a value-first invoker's first
  argument would find no `.key`, look nothing up, and do NOTHING — the
  silently dead handler every loud error in this namespace exists to
  delete."
  [k key-map]
  (let [lowered (reduce-kv (fn [m key-name v]
                             (assoc m key-name (cond
                                                 (vector? v) (intent-handler k v)
                                                 (fn? v)     v
                                                 :else       nil)))
                           {}
                           key-map)]
    (fn [e]
      (event-arg! k key-map e "key")
      (when-not (composing? e)
        (when-some [h (get lowered (.-key e))]
          (h e))))))

(defn ref-position?
  "`:ref` is the one prop position whose contract is neither Hicasso's to
  select nor the same for both phases: React invokes it in the COMMIT
  phase with the node, and whatever it returns is the detach cleanup. So
  it is excluded from callback lowering entirely and keeps its own
  declared contract (HD-016 callback refs, HD-022's reserved vector) —
  wrapping it would forbid a dispatch that is legitimate there and would
  change the identity React uses to decide whether to re-attach."
  [k]
  (or (= :ref k) (= "ref" k)))

(defn position-contract
  "The contract a prop position imposes on the one callback form. An
  event position is the only one the attribute grammar can name by
  itself; everything else Hicasso walks is a render position, and a
  `defhost` declaration overrides this by saying so
  ([[lower-declared-prop]])."
  [k]
  (cond
    (ref-position? k) :ref
    (event-prop? k)   :event
    :else             :render))

(defn lower-prop
  "Lower one prop. Values at non-event positions, and non-lowerable values
  at event positions, come back untouched — this is the identity function
  for everything the surface does not claim.

  The one callback form is claimed at EVERY position, because that is
  what makes the position the thing that selects the contract."
  [k v]
  (if (event-prop? k)
    (cond
      (vector? v)   (intent-handler k v)
      (map? v)      (key-map-handler k v)
      (callback? v) (event-callback k v)
      :else         v)
    (if (and (callback? v) (not (ref-position? k)))
      (render-callback k v)
      v)))

(defn- refuse-dispatching-carrier!
  "An intent vector or a key-map at a position the declaration gave the
  `:handler` or `:render` contract. Both carriers ARE a dispatch and
  nothing else, and neither contract dispatches, so there is no reading
  of the value that could satisfy the declaration — which is why this is
  a refusal at lowering rather than a wrapper that does less."
  [k v contract]
  (fail! :rf.error/hicasso-intent-at-a-non-event-contract
         'front.intent/lower-declared-prop
         (str "The declaration gives " (pr-str k) " the " (pr-str contract)
              " contract, and it was handed " (pr-str v) ". "
              (if (keyword-identical? :handler contract)
                (str "A :handler's return is ignored and Hicasso dispatches "
                     "nothing from it, so a carrier whose entire content is a "
                     "dispatch has no meaning at that contract. ")
                (str ":render is a PURE position — it is invoked during the "
                     "foreign component's own render, where dispatching is "
                     ":rf.error/hicasso-dispatch-in-render-position. "))
              "Declare " (pr-str k) " :event if what happens there is an "
              "event, or write an h/fn that does the " (pr-str contract)
              " work. The contract comes from the position, so the value "
              "never gets to overrule it.")
         :declare-the-position-event-or-write-an-h-fn
         {:position k :contract contract :value v}))

(defn lower-declared-prop
  "Lower one prop whose contract a `defhost` declaration named — the
  position table's second row. Kept separate from [[lower-prop]] so the
  declaration is what decides, rather than the prop's spelling: the
  predecessor's own rule is that a host's callback contract is 'a finite
  map from EXACT prop names to `:event` or `:handler`; never inferred
  from an `on*` name', and that rule survives here because the position,
  not the value, carries the contract.

  **The contract is the OUTER dispatch and the carrier the inner one**,
  which is the whole law made structural. Dispatching on the value first
  is how a declared `:handler` ends up silently dispatching a bare
  intent, and how a declared `:render` dispatches during the foreign
  component's render: the value selecting the contract, which is the
  defect HD-024 exists to delete. So each contract states what it accepts
  and refuses the rest:

  | Contract | `h/fn` | intent vector / key-map | anything else |
  |---|---|---|---|
  | `:event`   | the event wrapper — a returned vector dispatches | lowered exactly as at a native event position | crosses untouched |
  | `:handler` | the function itself, by identity | **refused** ([[refuse-dispatching-carrier!]]) | crosses untouched |
  | `:render`  | the render wrapper — dispatching inside is loud | **refused** | crosses untouched |

  `:event` keeps the vector and key-map conveniences because dispatching
  is exactly what that contract means; the vector's own argument law
  applies there unchanged ([[event-arg!]]). An ordinary unmarked function
  crosses untouched at every contract — `raw-fn`'s passthrough is the
  default here, and a plain fn is claimed by no position."
  [k v contract]
  (case contract
    :event   (cond
               (callback? v) (event-callback k v)
               (vector? v)   (intent-handler k v)
               (map? v)      (key-map-handler k v)
               :else         v)
    :handler (cond
               (callback? v)             v
               (or (vector? v) (map? v)) (refuse-dispatching-carrier! k v contract)
               :else                     v)
    :render  (cond
               (callback? v)             (render-callback k v)
               (or (vector? v) (map? v)) (refuse-dispatching-carrier! k v contract)
               :else                     v)
    (fail! :rf.error/hicasso-unknown-callback-contract
           'front.intent/lower-declared-prop
           (str "A declaration gave " (pr-str k) " the callback contract "
                (pr-str contract) ". The contracts are :event, :handler and :render.")
           :declare-event-handler-or-render
           {:position k :contract contract})))

(defn lower-props
  "Walk a props map once, lowering every position that carries something
  to lower. Returns the map unchanged, by identity, when it holds nothing
  — the ordinary case for the great majority of elements on a page, and
  worth not allocating for."
  [props]
  (if-not (some (fn [[k v]] (or (callback? v)
                                (and (event-prop? k) (or (vector? v) (map? v)))))
                props)
    props
    (reduce-kv (fn [m k v] (assoc m k (lower-prop k v))) {} props)))
