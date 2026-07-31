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
  | fn         | passed through untouched — ordinary functions stay legal |
  | anything else | passed through untouched |

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
  interop (HD-011, its own surface), or any controlled-value restore
  (HD-019's door belongs to the arm that owns the DOM).")

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
  is all the browser sends."
  [e]
  (or (true? (.-isComposing e))
      (identical? 229 (.-keyCode e))))

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

(defn lower-prop
  "Lower one prop. Values at non-event positions, and non-lowerable values
  at event positions, come back untouched — this is the identity function
  for everything the surface does not claim."
  [k v]
  (if-not (event-prop? k)
    v
    (cond
      (vector? v) (intent-handler k v)
      (map? v)    (key-map-handler k v)
      :else       v)))

(defn lower-props
  "Walk a props map once, lowering every event position. Returns the map
  unchanged, by identity, when it holds nothing to lower — the ordinary
  case for the great majority of elements on a page, and worth not
  allocating for."
  [props]
  (if-not (some (fn [[k v]] (and (event-prop? k) (or (vector? v) (map? v)))) props)
    props
    (reduce-kv (fn [m k v] (assoc m k (lower-prop k v))) {} props)))
