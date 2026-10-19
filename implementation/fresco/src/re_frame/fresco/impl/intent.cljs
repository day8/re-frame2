(ns re-frame.fresco.impl.intent
  "Intent lowering: the front half turns what an author writes at an
  `on-*` prop into the closure the browser calls. It dispatches on the
  SHAPE of the value, so there is no roster of DOM event names to keep in
  step with the platform:

      [:button {:on-click [:todo/toggle id]} \"done\"]
      [:input  {:on-input [:todo.ui/edit id ::h/value]}]
      [:form   {:on-submit [:todo/create]}]
      [:input  {:on-key-down {\"Enter\" [:todo/commit id] \"Escape\" [:todo.ui/cancel id]}}]
      [:a      {:href \"#\" :on-click [::h/prevent [:todo/show-done]]}]

  | value | at an `on*` prop | at any other prop |
  |---|---|---|
  | vector | a closure dispatching that intent (`intent-handler`) | data |
  | map | a composition-gated key-map (`key-map-handler`) | data |
  | an event carrier, `h/event` (`callback`) | `event-callback` | `event-callback` |
  | a render carrier, `h/render-fn` (`render-carrier`) | `render-callback` | `render-callback` |
  | anything else | passed through untouched | passed through untouched |

  A carrier means the same thing at every prop: its KIND selects its
  wrapper, never the prop's spelling. `:ref` is React's and is excluded
  from lowering. The contract is
  `implementation/fresco/spec/authoring-contract.md` §Intents.

  Owns the ambient render context (`*frame*`, `*dispatch*`, `with-frame`);
  the two carriers and their wrappers; the marker roster (`::h/value`,
  `::h/checked`) and its one pure materializer; the two reserved heads
  (`::h/prevent`, and the navigate head `route-link` mints); the
  composition gate; and the lowering doors `lower-prop` and `lower-props`.
  It does not implement the `defhost` / `[:>]` crossing
  (`re-frame.fresco.impl.codec`) or the controlled-value restore
  (`re-frame.fresco.impl.controlled`), which wraps the handler produced
  here after it has been produced.

  Three laws every var below assumes. The frame is read ONCE, at lowering
  time, and the closure closes over it, because a browser event fires
  long after the render's dynamic extent has unwound (HD-020(a)). The
  vector spelling is EVENT-FIRST: whatever reads the event takes it from
  argument one, and a value-first invoker is refused by name rather than
  left to the engine's `TypeError` (HD-024). And behaviour lives in the
  vector where `=` can see it — a reserved head, never metadata (HD-026,
  HD-027).

  Design record, for the reasoning behind the contract:
  docs/design/fresco/decisions.md HD-020, HD-026 and HD-027."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.error :refer [fail!]]
            [re-frame.fresco.impl.frames :as rf.fresco.impl.frames]
            [re-frame.late-bind :as rf.late-bind]))

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
  render's dynamic extent beside `*dispatch*`; `nil` outside a render.
  `re-frame.fresco.impl.route-link/route-link` reads it at render time,
  because a browser click fires after the extent has unwound and the
  frame must travel as data."
  nil)

(def ^:private ambient-frame-refusal
  "The detail this substrate hands core's refusal tier
  (`re-frame.frame/*ambient-frame-refusal*`). One module-level constant,
  built once at load; `with-frame` stamps the rendering boundary's frame
  onto a copy as `:extent-frame`, and that `assoc` is the whole
  per-extent cost. The reason names the two recoveries a refused
  operation has — the collector for a read, an intent for a dispatch —
  and says that a carry needs neither, because core admits the pure
  doors to the declared frame (spec/002-Frames.md §The refusal tier)."
  {:substrate :fresco
   :extent    'fresco/boundary-render
   :recovery  :read-through-the-boundary-collector
   :reason    (str "Read through the boundary's own collector (`sub`), which is what "
                   "makes a read an EDGE the boundary re-renders on, and dispatch "
                   "through an intent at a handler position (which lowers to this "
                   "boundary's frame-locked dispatch) or through an explicitly framed "
                   "call. An ambient subscribe here would resolve the boundary's frame "
                   "and then mutate the subscription cache during the render phase "
                   "while contributing ZERO collector edges — leaving a boundary that "
                   "never re-renders when that subscription moves, which is HD-002 "
                   "clause (a)'s forbidden class, and it refuses under every "
                   "adapter alike. "
                   "Carrying the frame out of the render needs neither: "
                   "`(rf/capture-frame)` and `(rf/current-frame-id)` answer this "
                   "boundary's own frame inside a body, because a capture and an "
                   "identity read make no edge and no mutation.")})

(defn with-frame
  "Run `body-fn` with the boundary's render context bound ambiently. The
  3-arity binds the frame keyword and its frame-locked `dispatch` — the
  runtime's door; the 2-arity binds the dispatch alone, for a lowering
  test that only drives closures, and anything frame-dependent it lowers
  stays a loud error rather than a silent guess. Both bind core's refusal
  tier as well, so ambient `rf/subscribe` and `rf/dispatch` refuse for
  the extent (HD-002 clause (a)), while an explicitly carried frame —
  `{:frame <id>}`, an `rf/with-frame` naming THIS frame — still answers:
  the refusal deletes the ambient FIND, not the carrying. The 3-arity
  also declares its frame as `:extent-frame`, which does two things in
  core: a carried stamp naming a DIFFERENT frame is refused, because one
  body would otherwise read against `:b` while its lowered intents target
  `:a`; and the pure doors — `rf/current-frame-id` and zero-arity
  `rf/capture-frame` — answer the declared frame, because neither reads
  nor dispatches. The 2-arity declares none, refuses no carry and admits
  no door.

  The tier is fused into this `binding` rather than wrapped around the
  call because `binding` pushes its whole set once, so the fence costs no
  extra frame. Every author-written render-phase walk comes through here
  — a body, the error boundary's fallback, the presence tray's retained
  children — since an ambient read is exactly as invisible to the
  collector in each. Contract: spec/002-Frames.md §The refusal tier;
  docs/design/fresco/decisions.md HD-020(a)."
  ([dispatch body-fn] (with-frame nil dispatch body-fn))
  ([frame-kw dispatch body-fn]
   (binding [*frame*                       frame-kw
             *dispatch*                    dispatch
             rf.frame/*ambient-frame-refusal* (cond-> ambient-frame-refusal
                                             frame-kw (assoc :extent-frame frame-kw))]
     (body-fn))))

(defn- require-dispatch
  "The frame-locked dispatch this lowering needs, or
  `:rf.error/fresco-intent-outside-boundary`. The message offers TWO
  readings because a nil `*dispatch*` cannot tell them apart and they
  want opposite repairs: the form was lowered outside any boundary (lower
  it in a body), or inside a function a foreign component invokes after
  the render unwound — a plain function returning Hiccup at a foreign
  render prop is exactly this, and the repair is the render carrier,
  `h/render-fn`, which carries the supplying frame into the call. Drop
  the second reading and that author, who did write the crossing in a
  body, reads the error as a framework bug."
  [intent]
  (or *dispatch*
      (fail! :rf.error/fresco-intent-outside-boundary
             're-frame.fresco.impl.intent/lower-prop
             (str "No frame-locked dispatch is bound for this render. Intent "
                  (pr-str intent) " was lowered with no ambient frame. Either the "
                  "form is outside any boundary's render — a declaration, a "
                  "fallback, a module-level def — in which case lower it inside a "
                  "body; or it is inside a function a foreign component invokes "
                  "after that render returned, such as a plain function returning "
                  "Hiccup at a render prop, which carries no frame. Write that "
                  "function as h/render-fn instead: it carries the frame of the "
                  "view that supplied it.")
             {:intent intent})))

;; ---------------------------------------------------------------------------
;; The two carriers
;; ---------------------------------------------------------------------------
;;
;; A carrier is an ordinary function marked with its KIND, and the kind
;; selects its wrapper at every prop. The prop's spelling selects nothing,
;; so a carrier moved between props keeps its effect, and no declaration
;; is needed to correct a vendor's spelling.

(def ^:private carrier-marker "frescoFn")

(def ^:private render-kind "render")

(defn callback
  "The event carrier, `h/event`: marks `f` and returns `f` ITSELF — an
  ordinary function, so where Fresco does not walk it simply runs and
  nothing can fail to be callable. Lowered at any prop, a returned vector
  is dispatched (`event-callback`). Marking mutates the function object,
  which is safe because a carrier written in a body is minted fresh per
  render, and costs one own-property read to test."
  [f]
  (unchecked-set f carrier-marker "event")
  f)

(defn render-carrier
  "The render carrier, the function `h/render-fn` expands around: marks
  `f` and returns `f` ITSELF. Lowered at any prop it carries the supplying
  boundary's frame into each call (`render-callback`). The conversion of
  its returned Hiccup is the macro's, inside `f`, so the carrier returns a
  React element wherever it is called."
  [f]
  (unchecked-set f carrier-marker render-kind)
  f)

(defn callback?
  "Is `v` a carrier, of either kind? One own-property read behind a `fn?`
  guard, so a string or a number at a prop position costs one type test."
  [v]
  (and (fn? v) (string? (unchecked-get v carrier-marker))))

(defn render-carrier?
  "Is `v` the render carrier?"
  [v]
  (and (fn? v) (= render-kind (unchecked-get v carrier-marker))))

(defn- event-callback
  "The event carrier's wrapper: a returned VECTOR is dispatched, anything
  else is ignored, and every argument the invoker passes reaches the body
  — a foreign live invoker may pass several, and a wrapper fixed at `[e]`
  would drop them or raise an arity error naming nothing the author
  wrote. The dispatch is captured at lowering time, and captured rather
  than required: a carrier that never returns an intent is legal with no
  frame in scope, and returning one there is the loud error naming the
  prop. The data spelling's policy defaults do not apply, because a
  carrier holds the event and so owns `.preventDefault`."
  [k f]
  (let [dispatch *dispatch*]
    (fn fresco-event-callback [& args]
      (let [result (apply f args)]
        (when (vector? result)
          (if dispatch
            (dispatch result)
            (fail! :rf.error/fresco-intent-outside-boundary
                   're-frame.fresco.impl.intent/lower-prop
                   (str "A callback at " (pr-str k) " returned the intent "
                        (pr-str result) " with no frame-locked dispatch in scope. "
                        "Callbacks that dispatch are only legal at a position "
                        "lowered inside a boundary's render.")
                   {:position k :intent result})))
        nil))))

(defn- render-callback
  "The render carrier's wrapper. Whatever holds it invokes it DURING a
  render, so the return is render output and is not dispatched; the
  carrier's own body has already converted its Hiccup to an element:

      (h/render-fn [i]
        [:li {:on-click [:row/pick (nth ids i)]} (str (nth ids i))])

  The wrapper captures the render context at lowering time — `*frame*`,
  `*dispatch*` and core's refusal tier, the SUPPLYING boundary's, the
  only frame that can own the row — and re-establishes all three per
  invocation. So an intent lowered inside fires into that frame, and the
  body's own discipline holds inside the carrier exactly as in the body:
  `rf/current-frame-id` and zero-arity `rf/capture-frame` answer the
  supplying boundary's frame, while an ambient `rf/subscribe` refuses
  rather than resolving the foreign component's context and contributing
  no edge. Lowered with no owner in scope it rebinds nil for each, and a
  handler lowered inside raises the ordinary
  `:rf.error/fresco-intent-outside-boundary`. A dispatch from INSIDE the
  call is not policed; React's render-phase warnings are the report."
  [_k f]
  (let [owner-dispatch *dispatch*
        owner-frame    *frame*
        owner-refusal  rf.frame/*ambient-frame-refusal*]
    (fn fresco-render-callback [& args]
      (binding [*frame*                       owner-frame
                *dispatch*                    owner-dispatch
                rf.frame/*ambient-frame-refusal* owner-refusal]
        (apply f args)))))

(defn- lower-carrier
  "Lower the carrier `f` at prop `k` by its own kind. The prop names the
  position in a diagnostic and selects nothing."
  [k f]
  (if (render-carrier? f) (render-callback k f) (event-callback k f)))

;; ---------------------------------------------------------------------------
;; The argument law — the vector spelling is EVENT-FIRST (HD-024)
;; ---------------------------------------------------------------------------

(defn- event-arg!
  "Answer `e` when it carries `slot` — the one property the closure is
  about to read (`preventDefault`, `target`, `key`) — and raise
  `:rf.error/fresco-intent-needs-the-event`, naming the position, the
  intent and what arrived, when it does not. The check is the read that
  was going to happen anyway; what it buys is that a value-first invoker
  produces THIS error, pointing at `h/event`, instead of the engine's
  `value.preventDefault is not a function` (HD-024, the argument law)."
  [k form e slot]
  (if (and (some? e) (some? (unchecked-get e slot)))
    e
    (fail! :rf.error/fresco-intent-needs-the-event
           're-frame.fresco.impl.intent/lower-prop
           (str "The intent " (pr-str form) " at " (pr-str k) " reads the DOM "
                "event's `" slot "`, but the first argument its invoker passed "
                "was " (pr-str e) ", which has none. The vector spelling is "
                "EVENT-FIRST: it takes the event from argument one, which is "
                "what every native position and every event-first foreign "
                "contract hands it. A value-first invoker — (on-pick value "
                "event) — has no event there, and nothing can guess which of "
                "its arguments is one. Write an h/event instead: the event "
                "carrier receives every argument the invoker passed, in order.")
           {:position k :form form :argument e :needed slot})))

;; ---------------------------------------------------------------------------
;; The marker roster and its one pure materializer
;; ---------------------------------------------------------------------------

(def value-marker
  "authoring.md's `::h/value` — the event target's current value."
  :re-frame.fresco/value)

(def checked-marker
  "`::h/checked` — the event target's current checked state."
  :re-frame.fresco/checked)

(def prevent-head
  "`::h/prevent` — the FIRST reserved intent HEAD. `[::h/prevent [:app/go]]`
  at an event position calls `.preventDefault` and dispatches `[:app/go]`;
  `unwrap-prevent` holds the closed grammar. A head rather than metadata
  because metadata does not participate in `=` (HD-026)."
  :re-frame.fresco/prevent)

(def navigate-head
  "The SECOND reserved intent HEAD: `[navigate-head {:frame :payload
  :native? :veto}]` at an event position is a route-link's whole click
  decision as data. An implementation keyword, not a public marker,
  because `re-frame.fresco.impl.route-link/route-link` mints the form
  and no author writes it; a structural test reads it through
  `navigate-head?`. Design record: docs/design/fresco/decisions.md
  HD-027."
  ::navigate)

(defn- target-value
  "The event target's current value: `.value`, except on the two controls
  where `.value` is not it. A `<select multiple>` answers its SELECTION as
  a vector of option values, `[]` when nothing is picked — `.value` there
  is only the first selected option, and `::h/value` already means the
  control's current value, so this is a correction; fed back as `:value`
  it round-trips, because the codec hands React an array. An
  `<input type=\"file\">` is refused
  (`:rf.error/fresco-file-input-value-marker`): `.value` there is the
  `C:\\fakepath\\` fiction naming one file of many, and the guide keeps
  file inputs off the marker's surface on purpose, so answering with a
  `FileList` would widen the marker onto a control the design excludes.
  The asymmetry is argued at spec/009-Instrumentation.md, the row for that
  id, and docs/design/fresco/product/dispositions.md (Select (multiple),
  File input).

  `.files` is asked FIRST because it is the platform's own discriminator —
  a `FileList` on a file input, `null` on every other input — and this is
  the one control that must not reach `.value` at all. The select test is
  `.multiple` AND `.selectedOptions`, because `<input type=\"email\"
  multiple>` carries `.multiple` and has no selection to read, while only a
  `<select>` has `.selectedOptions`; `.multiple` is asked first because it
  is `undefined` off those controls, so a text field reaches `.value` on
  one extra property read and no allocation."
  [target]
  (when (.-files target)
    (fail! :rf.error/fresco-file-input-value-marker
           're-frame.fresco.impl.intent/target-value
           (str "::h/value on a file input reads C:\\fakepath\\ followed by the "
                "FIRST selected file's name — a path nothing can open, naming "
                "one file out of however many were chosen. A file input has no "
                "value the model can carry; the platform owns the selection. "
                "Write an h/event at the handler and read `.files` off the event "
                "target: [:input {:type :file :on-change (h/event [e] [:app/upload "
                "(js/Array.from (.. e -target -files))])}].")
           {:value (.-value target)}))
  (if (.-multiple target)
    (if-some [options (.-selectedOptions target)]
      (mapv (fn [option] (.-value option)) (array-seq options))
      (.-value target))
    (.-value target)))

(def ^:private marker-readers
  "The roster, as marker → the reader that pulls its value off the event
  target. A map rather than a chain of comparisons, because it is both
  the roster and the dispatch — and because `identical?` is the wrong
  test for keywords in ClojureScript: literals are shared constants only
  when the build interns them, so an identity comparison that works under
  `:advanced` silently fails in the test build."
  {value-marker   target-value
   checked-marker (fn [target] (.-checked target))})

(defn markers?
  "Does this intent carry a marker at its top level? Answered once, at
  lowering time, so the per-event path never asks."
  [intent]
  (boolean (some marker-readers intent)))

(defn materialize
  "Substitute the markers in `intent` with values read off the DOM event's
  target. Pure, and top level only: the corpus writes
  `[:todo.ui/edit id ::h/value]`, and a deep walk per event to serve a
  shape nobody writes would be paid on every keystroke of every controlled
  field."
  [intent e]
  (let [target (.-target e)]
    (mapv (fn [x] (if-some [read-value (marker-readers x)] (read-value target) x))
          intent)))

;; ---------------------------------------------------------------------------
;; The composition gate
;; ---------------------------------------------------------------------------

(defn composing?
  "Is this key event part of an in-flight IME composition? `isComposing`
  where the browser sets it, and the legacy keyCode-229 signal where that
  is all it sends. Both are read off the NATIVE event: React's synthetic
  keyboard event copies an enumerated interface that omits `isComposing`,
  so a gate reading the synthetic event is dead on its modern half
  (docs/design/fresco/studio/the-dogfood-preference-case.md). A raw DOM
  event has no `nativeEvent` and falls back to itself."
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
  "Is `v` a `[navigate-head {…}]` vector? One `=` against the vector's
  first element, the same shape as `prevent-head?`. The door a structural
  test reads a route-link's click decision through."
  [v]
  (and (vector? v) (= navigate-head (nth v 0 nil))))

(defn- reserved-head?
  "Is `v` a vector carrying either reserved head? The roster is a LIST —
  two entries — which is what keeps the grammar closed."
  [v]
  (or (prevent-head? v) (navigate-head? v)))

(defn- unwrap-prevent
  "Answer the intent inside the `::h/prevent` decorator `v`, refusing
  anything that is not exactly `[::h/prevent INTENT]` with a non-empty
  inner vector that is not itself a decorator
  (`:rf.error/fresco-malformed-prevent`, naming the position). The
  grammar is closed and this is all of it. Not a walker: the
  classification is taken once per render at the one position lowering
  already holds, and the inner vector stays ordinary data all the way to
  `dispatch`. Design record: docs/design/fresco/decisions.md HD-026."
  [k v]
  (let [inner (nth v 1 nil)]
    (when-not (and (= 2 (count v))
                   (vector? inner)
                   (seq inner)
                   (not (reserved-head? inner)))
      (fail! :rf.error/fresco-malformed-prevent
             're-frame.fresco.impl.intent/lower-prop
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
             {:position k :form v}))
    inner))

(declare intent-handler)

(defn- lower-veto
  "Lower the `:veto` slot of a navigate vector into the pre-navigation
  callback routing runs first — a plain one-argument fn, or nil. The
  `::h/prevent` decorator is the DECLARATIVE veto: its lowered closure
  calls `.preventDefault` and dispatches the wrapped intent, and
  `activate-link!` stands down on `defaultPrevented`, so the app intent
  takes the navigation's place. A callback or plain fn is the IMPERATIVE
  veto — whoever holds the event owns it (HD-024). The roster is closed
  at RENDER by `route-link`'s `on-click-roster!`, which mints the only
  navigate vector there is, so nothing is re-checked here. Design record:
  docs/design/fresco/decisions.md, HD-027."
  [k veto]
  (cond
    (nil? veto)          nil
    (prevent-head? veto) (intent-handler k veto)
    :else                veto))

(defn- unwrap-navigate
  "The map inside `[navigate-head {…}]` — `:frame`, `:payload`, `:native?`
  and `:veto` (docs/design/fresco/decisions.md HD-027). Not validated:
  `route-link` mints this form and nothing else writes it, so a render
  pays nothing per link to re-read a map the library constructed. Not a
  walker — the payload stays ordinary data all the way to routing."
  [_k v]
  (nth v 1 nil))

(defn- navigate-handler
  "Lower one navigate vector into the closure the browser will call. The
  veto is lowered HERE, at lowering time, because a prevent veto needs the
  ambient dispatch, which is gone by click time; the click decision stays
  routing's — the closure hands the event to the `:routing/activate-link!`
  late-bound seam. When that hook is unbound at click time (the routing
  artefact hot-reloaded away between render and click) the closure runs
  the veto and otherwise stands aside, so the browser follows the anchor's
  real `href` rather than throwing at a detached click; `route-link`
  proved routing present at RENDER, so absence here is transient. Design
  record: docs/design/fresco/decisions.md HD-027.

  The frame is pinned HERE as well, and for the same reason. The vector
  carries the frame keyword, because it is data; the closure carries the
  `capture-frame` bundle of the incarnation live at lowering
  (`frames/pinned-ops`), and routing dispatches through it, so a click
  fired after that incarnation is destroyed refuses with
  `:rf.error/frame-destroyed` like every other lowered callback, never
  navigating a successor seated under the same id."
  [k v]
  (let [{:keys [frame payload native? veto]} (unwrap-navigate k v)
        frame-api (rf.fresco.impl.frames/pinned-ops frame)
        veto-fn   (lower-veto k veto)]
    (fn fresco-navigate [e]
      (if-some [activate (rf.late-bind/get-fn :routing/activate-link!)]
        (activate e veto-fn frame-api payload native?)
        (when veto-fn (veto-fn e)))
      nil)))

(defn- intent-handler
  "Lower one intent vector into the closure the browser will call. The
  navigate head is classified first and `navigate-handler` owns it;
  otherwise prevent, markers and dispatch are all resolved HERE, once per
  render, so the event path is the shortest this intent can have.
  Classification precedes marker analysis so the markers compose inside a
  prevented intent (HD-026). Only the branches that read the event carry
  the argument law (`event-arg!`); the `:else` branch — no marker, no
  prevent, the overwhelming case — never touches its argument, so it costs
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
  handler, built once per render; an event costs one composition test and
  one lookup. The argument law applies through `key`: without it a
  value-first invoker's first argument would find no `.key`, and the
  handler would silently do nothing.

  THE ARM ORDER IS THE CONTRACT, not a formatting choice. `callback?` is
  `fn?` plus one marker read, so `fn?` is true of a carrier TOO: asked
  first it claims the carrier and installs it raw, and the intent vector
  an event carrier returns is dropped on the floor — no error, no
  dispatch. A branch's carrier is lowered by the SAME `lower-carrier` as
  a bare prop, so this is one rule reused and not a second one."
  [k key-map]
  (let [lowered (reduce-kv (fn [m key-name v]
                             (assoc m key-name (cond
                                                 (vector? v)   (intent-handler k v)
                                                 (callback? v) (lower-carrier k v)
                                                 (fn? v)       v
                                                 :else         nil)))
                           {}
                           key-map)]
    (fn [e]
      (event-arg! k key-map e "key")
      (when-not (composing? e)
        (when-some [h (get lowered (.-key e))]
          (h e))))))

(defn ref-position?
  "`:ref` is the one prop whose contract is not Fresco's: React invokes it
  in the COMMIT phase with the node, and its return is the detach
  cleanup. Excluded from carrier lowering entirely — wrapping it would
  change the identity React uses to decide whether to re-attach."
  [k]
  (or (= :ref k) (= "ref" k)))

(defn lower-prop
  "Lower one prop. A carrier is lowered by its own kind at every prop but
  `:ref`; a vector or map is an intent only at an `on*` prop, and data
  everywhere else; everything else comes back untouched — this is the
  identity function for everything the surface does not claim."
  [k v]
  (cond
    (callback? v)   (if (ref-position? k) v (lower-carrier k v))
    (event-prop? k) (cond
                      (vector? v) (intent-handler k v)
                      (map? v)    (key-map-handler k v)
                      :else       v)
    :else           v))

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
