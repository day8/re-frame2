(ns re-frame.bench.hicasso.front.intent
  "INTENT LOWERING — deliverable 3 of the Wave-1 shared front half
  (rf2-2rtt6.8). Ergonomics-as-data: the author writes what should
  happen, and the front half, not the author, turns it into the closure
  the browser calls.

      [:button {:on-click [:todo/toggle id]} \"done\"]
      [:input  {:on-input [:todo.ui/edit id ::h/value]}]
      [:form   {:on-submit [:todo/create]}]
      [:input  {:on-key-down {\"Enter\"  [:todo/commit id]
                              \"Escape\" [:todo.ui/cancel id]}}]

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
  | a `defhost` `:callbacks` entry | the declaration already says `:event` or `:handler` — never inferred from an `on*` name | as declared; `:handler` is the return-ignored contract |
  | any other prop position (a slot, a render prop) | not an event position | **render**: pure. The return is the render output and is NOT dispatched; dispatching *from inside the call* is a loud error naming the position |
  | `:ref` | [[ref-position?]] | React's own: commit phase, the node as the argument, the return as the detach cleanup. Excluded from lowering entirely |
  | anywhere Hicasso does not walk — a raw `#js` prop, a value handed to a foreign API | it is not a position | it is a plain function and it simply runs; the return is ignored |

  That last row is the deletion. There is no carrier object, so there is
  nothing that can fail to be callable, so the fifth rule has nothing to
  govern.

  **A Hicasso view's own props map is not a position — it is data in
  transit.** An intent vector handed to a view is not lowered there
  either; the view puts it on an element and *that* position lowers it.
  A callback travels the same way, and a callback a view simply *calls*
  is ordinary Clojure with no foreign ABI in between, so there is nothing
  to protect. Which is also why the render row is not free-floating
  policy: it bites exactly where something other than Hicasso invokes the
  callback.

  **`raw-fn`'s identity passthrough is not v0, and costs nothing to
  omit**: [[re-frame.bench.hicasso.front.codec/convert-prop-value]]
  already passes functions to React by identity, deliberately, so that
  `React.memo` and every downstream bail-out that compares handler
  identity keep working. The behaviour the predecessor spells as a fourth
  roster form is the default here. The census agrees with the omission —
  zero foreign components across 85 idiomatic files.

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

  - **`:on-submit` auto-prevents** (authoring.md, census-weighted). The
    opt-out is metadata on the intent — `^{::h/prevent? false} [:ev]` —
    which costs no new prop, no new spelling, and no second calling
    convention. The same metadata opts *in* at any other event position,
    so there is one mechanism rather than a default and an override.
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

  This namespace deliberately does not implement `route-link` (routing-
  coupled, and outside this bead's three deliverables), `defhost`/`[:>]`
  interop (HD-011, its own surface), or any controlled-value restore.
  HD-019's door belongs to whatever owns the DOM element, which is the
  emitter rather than the lowering:
  [[re-frame.bench.hicasso.front.controlled]] wraps the handler this
  namespace produced, after it has produced it, and nothing about the
  lowering changes because of it (rf2-fki5d).")

;; ---------------------------------------------------------------------------
;; The ambient frame (HD-020(a))
;; ---------------------------------------------------------------------------

(def ^:dynamic *dispatch*
  "The frame-locked `dispatch` for the boundary currently rendering,
  bound for the render's dynamic extent. `nil` outside a render, which is
  what makes an intent lowered outside a boundary a loud error."
  nil)

(defn with-frame
  "Run `body-fn` with `dispatch` — the frame-locked dispatch resolved once
  for this boundary — bound ambiently. An arm's boundary shell calls this
  around the body."
  [dispatch body-fn]
  (binding [*dispatch* dispatch] (body-fn)))

(defn- fail! [id where reason recovery extra]
  (throw (ex-info (str reason " [" id "]")
                  (merge {:rf.error/id id :where where
                          :reason reason :recovery recovery}
                         extra))))

(defn- require-dispatch [intent]
  (or *dispatch*
      (throw (ex-info (str "Intent " (pr-str intent) " was lowered with no ambient frame; "
                           "event vectors are only legal inside a boundary's render. "
                           "[:rf.error/hicasso-intent-outside-boundary]")
                      {:rf.error/id :rf.error/hicasso-intent-outside-boundary
                       :where       'front.intent/lower-prop
                       :reason      "No frame-locked dispatch is bound for this render."
                       :recovery    :lower-intents-inside-a-boundary-render
                       :intent      intent}))))

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
  [[re-frame.bench.hicasso.arm1.lang/hfn]]. Marking mutates the function
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

  Enforced by poisoning the ambient dispatch for the call's dynamic
  extent: an intent lowered inside, or a direct call, both land on the
  same error id. `.preventDefault`-style side effects are untouched."
  [k f]
  (fn hicasso-render-callback [& args]
    (binding [*dispatch*
              (fn [event]
                (fail! :rf.error/hicasso-dispatch-in-render-position
                       'front.intent/lower-prop
                       (str "A callback at " (pr-str k) " dispatched " (pr-str event)
                            " while it was running. " (pr-str k) " is a RENDER "
                            "position: it is invoked during a render, so it must be "
                            "pure. Move the dispatch to an event position, or to an "
                            "event handler that owns the work.")
                       :dispatch-from-an-event-position
                       {:position k :event event}))]
      (apply f args))))

(defn- declared-callback
  "**A `defhost` `:callbacks` entry.** The declaration already carries the
  contract — `:event` or `:handler`, never inferred from an `on*` name —
  so this is the position table's third row and it needs no new
  machinery. `:handler` is the return-ignored contract, which for a form
  that is already an ordinary function is the function itself."
  [k f contract]
  (case contract
    :event   (event-callback k f)
    :handler f
    :render  (render-callback k f)
    (fail! :rf.error/hicasso-unknown-callback-contract
           'front.intent/lower-declared-prop
           (str "A declaration gave " (pr-str k) " the callback contract "
                (pr-str contract) ". The contracts are :event, :handler and :render.")
           :declare-event-handler-or-render
           {:position k :contract contract})))

;; ---------------------------------------------------------------------------
;; The marker roster and its one pure materializer
;; ---------------------------------------------------------------------------

(def value-marker
  "authoring.md's `::h/value` — the event target's current value."
  :re-frame.hicasso/value)

(def checked-marker
  "`::h/checked` — the event target's current checked state."
  :re-frame.hicasso/checked)

(def prevent-key
  "`::h/prevent?` — metadata on an intent vector, opting `.preventDefault`
  in or out against the position's default."
  :re-frame.hicasso/prevent?)

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

(defn- prevent?
  "Does the intent at `k` prevent the default action? The position's
  default, overridden either way by `^{::h/prevent? …}` on the intent."
  [k intent]
  (let [m (meta intent)]
    (if (contains? m prevent-key)
      (boolean (get m prevent-key))
      (prevent-by-default? k))))

(defn- intent-handler
  "Lower one intent vector into the closure the browser will call. The
  three axes — prevent, markers, dispatch — are all resolved here, so the
  event path is the shortest one this intent can have."
  [k intent]
  (let [dispatch (require-dispatch intent)
        prevent  (prevent? k intent)
        dynamic  (markers? intent)]
    (cond
      (and prevent dynamic)       (fn [e] (.preventDefault e) (dispatch (materialize intent e)))
      prevent                     (fn [e] (.preventDefault e) (dispatch intent))
      dynamic                     (fn [e] (dispatch (materialize intent e)))
      :else                       (fn [_e] (dispatch intent)))))

(defn- key-map-handler
  "Lower a data key-map into one closure over a plain map of key-string →
  handler. The map is built once per render; an event costs one
  composition test and one lookup."
  [k key-map]
  (let [lowered (reduce-kv (fn [m key-name v]
                             (assoc m key-name (cond
                                                 (vector? v) (intent-handler k v)
                                                 (fn? v)     v
                                                 :else       nil)))
                           {}
                           key-map)]
    (fn [e]
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

(defn lower-declared-prop
  "Lower one prop whose contract a `defhost` declaration named — the
  position table's second row. Kept separate from [[lower-prop]] so the
  declaration is what decides, rather than the prop's spelling: the
  predecessor's own rule is that a host's callback contract is 'a finite
  map from EXACT prop names to `:event` or `:handler`; never inferred
  from an `on*` name', and that rule survives here because the position,
  not the value, carries the contract."
  [k v contract]
  (cond
    (callback? v) (declared-callback k v contract)
    (vector? v)   (intent-handler k v)
    (map? v)      (key-map-handler k v)
    :else         v))

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
