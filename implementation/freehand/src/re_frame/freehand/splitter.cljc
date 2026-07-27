(ns re-frame.freehand.splitter
  "The first-party SPLITTER — a resizable pane divider, and the Freehand
  control kit's pointer witness.

  `re-frame.freehand.controls` holds the kit's FORM controls: both read a
  `form/field` projection and both live inside the controlled-input door.
  A splitter reads no form and enters no door. What it is a witness for is
  the other half of DC-04 — a control whose gesture is a POINTER DRAG, and
  whose keyboard path has to be the same control rather than a second one
  bolted to the side of it.

      [split/splitter {:split      (v/sub [:layout/split])
                       :on-start   [:layout/split-started]
                       :on-preview [:layout/split-moved]
                       :on-commit  [:layout/split-committed]
                       :on-cancel  [:layout/split-cancelled]}]

  and the four handlers behind it, which is the whole application:

      (rf/reg-event :layout/split-started
        (fn [{:keys [db]} _] {:db (update db :layout/split split/start)}))
      (rf/reg-event :layout/split-moved
        (fn [{:keys [db]} [_ at]] {:db (update db :layout/split split/move at)}))
      (rf/reg-event :layout/split-committed
        (fn [{:keys [db]} [_ at]] {:db (update db :layout/split split/commit at)}))
      (rf/reg-event :layout/split-cancelled
        (fn [{:keys [db]} _] {:db (update db :layout/split split/cancel)}))

  ## Two clocks, and the application picks where the boundary is

  A pointer offers moves at the host's rate — 60, 120, 240 a second, more
  under coalescing — and a splitter that turned each one into a domain
  event would make every drag a burst of reducer work whose size is a
  property of the user's hardware.

  So an OFFER is not an INTENT. Every offer is settled first — clamped to
  `:min`/`:max` and quantized to `:step` — and an intent is produced only
  where the settled value DIFFERS from the one being rendered. Offers that
  land inside the current step, or past a bound already reached, produce
  nothing at all.

  The application then chooses the stream it wants, by wiring or not
  wiring one prop:

  | `:on-preview` | what app-db sees during a drag |
  |---|---|
  | wired | one intent per accepted step — the split tracks the pointer |
  | absent | NOTHING — the pane moves once, at `:on-commit` |

  Both are ordinary and neither is a mode: the second is what a large
  layout wants, where re-laying out mid-drag is the expensive thing.
  `:on-commit` carries the settled value either way, so a call site that
  wires only `:on-commit` is complete.

  No throttle, no scheduler, no timing verb, and no vocabulary for one.
  The reduction is arithmetic — settle, then compare — which is why it is
  a pure function you can call ([[settle]]) rather than a policy you have
  to configure.

  ## The keyboard is the same control, not a second one

  Everything directional goes through two pure functions and the
  application never sees which device produced a value: [[key-intent]]
  turns a key into a move, [[intent-at]] applies it, and both end at the
  same [[settle]] the pointer's offers end at, under the same bounds and
  the same right-to-left mirror. A keystroke and a one-step drag leave
  app-db in the same state, and that equality is the row this control
  exists to prove (FH-CTRL-019).

  The one asymmetry is real rather than an oversight: **a keystroke is a
  whole gesture.** It has no start and no end to report, so it produces
  the terminal intent and nothing else, while a drag reports
  `:on-start`, its accepted moves, and then `:on-commit` or `:on-cancel`.
  That is also why there is no blur handler and nothing to flush at one —
  see §The absences below.

  Only the ARROW keys are physical, so only they are mirrored under
  `:rtl?`. `PageUp`/`PageDown`/`Home`/`End` name the VALUE — smaller,
  larger, minimum, maximum — and a mirrored `Home` would be a bug in
  every writing direction.

  ## Where the state is, and what capture is for

  There is no local state system, no host slot and no controller record.
  The whole of a gesture lives in two places that already existed.

  **`:split` is ordinary application data** — `{:at … :baseline …
  :dragging? …}` at an ordinary path, moved by the ordinary transitions
  below and persisted by whatever the application persists with.
  `:baseline` is what a cancel restores, and it is application data
  because the restore is an application decision.

  **Pointer capture is ROUTING, not authority.** `setPointerCapture` on
  `pointerdown` is what makes every later move for that pointer arrive at
  this element wherever on screen the pointer travels — which is why this
  control adds no `window` or `document` listener, and therefore has
  nothing to remove at unmount. A hand-rolled splitter's
  `mousemove`-on-window plus a private `dragging?` flag is the shape that
  keeps dragging after the window loses focus, and the shape whose
  cleanup an unmount hook has to be invented for.

  It is taken BEST-EFFORT, and that follows from calling it routing: a
  host that declines capture — an emulated pointer, an engine without the
  API — degrades to the ordinary bubbling path, and every law below still
  holds, because none of them was ever decided by the capture.

  **Liveness is decided in the HANDLER, against committed state.** That
  is the kit's existing rule (`controls/buffered-field` §The fence) and
  it applies here for the same reason: an accepted offer and the frame it
  is accepted against are a tick apart, so a preview dispatched just
  before a cancel legitimately lands just after it. [[move]] is inert
  unless the value says a gesture is live, so a late offer changes
  nothing and a cancel BEATS it rather than racing it.

  What the view decides is arithmetic and nothing else: settle the offer,
  compare it with the split on screen, dispatch only on a difference. So
  an application ending a drag for its own reasons — a route leaving, a
  layout replaced, an `Escape` of its own — works for free: clearing
  `:dragging?` IS the end of the gesture, and no phantom drag can move
  the value afterwards.

  ## The absences, which are part of the contract

  - **No blur handler.** A drag's liveness is the capture, which a blur
    does not touch, and a keystroke is already complete when it ends.
    There is nothing pending at a blur, so there is nothing to flush.
  - **No unmount event, and no cleanup hook to hang one on.** The control
    holds nothing that outlives its node: the capture dies with the
    element and the value belongs to the owner. Cleanup follows the
    OWNER — the route, the workflow, the record — exactly as
    `controls/release` does for a form.
  - **No `Escape` during a drag.** The cancel a browser reports is
    `pointercancel`, and it is wired. An application that wants a key to
    abandon a drag as well dispatches its own cancel event from its own
    key handler — which works, completely, because `:dragging?` is
    ordinary application state and [[cancel]] is an ordinary function.
    Owning a second cancellation vocabulary here would buy nothing the
    application cannot already spell.

  ## Skins are replaceable

  One element, one part id ([[parts]]) under the `re-frame/splitter`
  component scope, and children are the caller's. Everything visual — the
  grip, the hit area, the cursor, the highlight while dragging — is
  ordinary CSS against `[data-component=\"re-frame/splitter\"]` and
  `[data-dragging]`, or ordinary children. This is a control, not a
  widget catalogue.

  ## Coming from re-com

  re-com's `h-split`/`v-split` own the panes, the layout and the
  percentage, and hand you `:on-split-change`. This owns none of that:
  the layout is yours, the value is yours, and the splitter is one child
  you place between two panes. The mapping is small — `:split-perc` is
  `:at` as a 0–1 fraction, `:on-split-change` is `:on-commit`, and
  `:margin`/`:width`/`:class` are CSS on your own element — and
  deliberately not an API-compatible one. There is no `h-split` here to
  wrap your panes, because a layout DSL is what re-frame2 is trying not
  to have.

  Normative owner:
  [`spec/004-Views.md` §The first-party control kit](../../../../../spec/004-Views.md#the-first-party-control-kit)."
  (:require [re-frame.freehand :as v]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Bounds
;; ---------------------------------------------------------------------------

(def default-bounds
  "The bounds a splitter uses when the caller names none: the whole track,
  in one-percent steps, ten percent to a page.

  `:step` is the control's QUANTUM and both paths use it — an arrow key
  moves one, and a pointer offer is accepted only when it crosses one. A
  single quantum is what makes \"a keystroke and a one-step drag agree\"
  a statement about the control rather than a coincidence of two
  independently-rounded numbers."
  {:min 0.0 :max 1.0 :step 0.01 :page-step 0.1})

(defn bounds
  "`b` folded over [[default-bounds]] — the one place a caller's partial
  bounds map is completed, so nothing downstream has to ask whether a key
  is there."
  [b]
  (merge default-bounds b))

;; ---------------------------------------------------------------------------
;; Settling — the one arithmetic every path ends at
;; ---------------------------------------------------------------------------

(defn- round6
  "`x` to six decimal places, as a double on both hosts.

  Quantizing in floating point produces `0.43000000000000005`, which is
  not equal to `0.43` and prints as neither. Six places is far below any
  `:step` a layout uses and far above the error a handful of additions
  introduces, and it is what lets a settled value be compared with `=`
  and pinned in a fixture."
  [x]
  (/ (Math/round (* 1.0 x 1e6)) 1e6))

(defn settle
  "The RAW position `x` as the split it actually names: clamped into
  `:min`/`:max` and quantized to `:step`.

      (settle 0.4237 {:min 0.0 :max 1.0 :step 0.01})   ;=> 0.42
      (settle 1.4    {:min 0.0 :max 0.8 :step 0.01})   ;=> 0.8

  Total: every real number answers a split inside the bounds. `bs` is a
  complete bounds map (see [[bounds]]).

  This is the whole of the two-clock reduction. A pointer's offers are
  settled and compared against the split on screen, so an offer that
  settles to the value already rendered produces no intent — which is
  most of them, and is why a drag across a pane costs one intent per step
  rather than one per frame."
  [x {:keys [step] lo :min hi :max :as _bs}]
  (let [x (cond (< x lo) lo (> x hi) hi :else x)]
    (if (and step (pos? step))
      (let [n (Math/round (* 1.0 (/ (- x lo) step)))
            v (round6 (+ lo (* n step)))]
        (cond (< v lo) lo (> v hi) hi :else v))
      (round6 x))))

;; ---------------------------------------------------------------------------
;; The keyboard law, as a pure function
;; ---------------------------------------------------------------------------

(defn key-intent
  "The KEYBOARD LAW, as a pure function of the key and the geometry.
  Answers a move — `[:step ±1]`, `[:page ±1]`, `[:to :min]`, `[:to :max]`
  — or `nil` for every key the splitter does not claim.

      (key-intent \"ArrowRight\" {:orientation :horizontal})            ;=> [:step 1]
      (key-intent \"ArrowRight\" {:orientation :horizontal :rtl? true}) ;=> [:step -1]
      (key-intent \"ArrowRight\" {:orientation :vertical})              ;=> nil
      (key-intent \"Home\"       {:orientation :horizontal :rtl? true}) ;=> [:to :min]

  `:orientation` is the axis the split MOVES ALONG: `:horizontal` for
  panes side by side, `:vertical` for panes stacked. A splitter claims
  only its own axis's arrows, so `ArrowUp` on a horizontal split is the
  page's to scroll with.

  **Only the arrows are mirrored.** `ArrowRight` moves the separator
  right, which in a right-to-left layout GROWS the leading pane and so
  decreases the split — one mirror, applied to the direction rather than
  to the value. `PageUp`, `PageDown`, `Home` and `End` name the value
  itself, so they read the same in both writing directions; mirroring
  `Home` would send it to the maximum, which is a bug no reviewer catches
  and no left-to-right keyboard reproduces.

  Public and pure so the table is proven by CALLING it, with no browser
  and no host event — the same discipline `controls/key-intent` holds the
  composing-Enter rule to."
  [k {:keys [orientation rtl?]}]
  (let [across? (not= :vertical orientation)
        sign    (if (and across? rtl?) -1 1)]
    (case k
      "ArrowLeft"  (when across? [:step (- sign)])
      "ArrowRight" (when across? [:step sign])
      "ArrowUp"    (when-not across? [:step -1])
      "ArrowDown"  (when-not across? [:step 1])
      "PageUp"     [:page -1]
      "PageDown"   [:page 1]
      "Home"       [:to :min]
      "End"        [:to :max]
      nil)))

(defn intent-at
  "The split a [[key-intent]] move names, applied to `at` under `bs` — or
  `nil` where `intent` is nil.

  It ends at [[settle]], which is the point: the keyboard reaches
  positions through the same clamp and the same quantum a pointer offer
  does, so the two paths cannot drift apart by rounding differently."
  [intent at {:keys [step page-step] lo :min hi :max :as bs}]
  (when-let [[kind arg] intent]
    (settle (case kind
              :step (+ at (* arg step))
              :page (+ at (* arg page-step))
              :to   (case arg :min lo :max hi))
            bs)))

;; ---------------------------------------------------------------------------
;; The pointer's geometry
;; ---------------------------------------------------------------------------

(defn fraction-at
  "The RAW fraction of `rect` that the point `{:x :y}` names, along the
  axis `:orientation` gives — mirrored under `:rtl?` on the horizontal
  axis, which is the SAME mirror [[key-intent]] applies to an arrow.

  Unclamped and unquantized: a point outside the rect answers a fraction
  outside 0–1, and [[settle]] is what decides. Keeping the two apart is
  what lets a bound be proven as a bound rather than inferred from a
  clamp that already happened.

  `nil` where the rect has no extent along that axis — a zero-width
  track names no fraction, and answering `0` would be a position the user
  did not ask for."
  [{:keys [x y]} {:keys [left top width height]} {:keys [orientation rtl?]}]
  (let [across? (not= :vertical orientation)
        span    (if across? width height)]
    (when (and span (pos? span))
      ;; `1.0 *` before the division, not after: on the JVM `(/ 20 100)`
      ;; is the ratio 1/5, which no ClojureScript run can produce and no
      ;; fixture can pin across both hosts.
      (let [f (/ (* 1.0 (- (if across? x y) (if across? left top))) span)]
        (if (and across? rtl?) (- 1.0 f) f)))))

;; ---------------------------------------------------------------------------
;; The value — ordinary application data, moved by ordinary functions
;; ---------------------------------------------------------------------------

(defn init
  "Open a split value at `at`.

      (split/init 0.5)  ;=> {:at 0.5 :baseline 0.5 :dragging? false}

  `:at` is what is rendered, `:baseline` is what a cancel restores, and
  `:dragging?` is the application's half of the gesture fence. Three
  keys, all readable, all ordinary — a subscription over any of them is a
  plain `reg-sub`, and the whole value serializes."
  [at]
  {:at (round6 at) :baseline (round6 at) :dragging? false})

(defn start
  "Begin a gesture: mark it live and take the current `:at` as the
  baseline a cancel would restore.

  Idempotent, because a `pointerdown` while a drag is already live is a
  second finger rather than a new gesture, and re-baselining there would
  quietly make the first finger's movement unrestorable."
  [s]
  (if (:dragging? s) s (assoc s :dragging? true :baseline (:at s))))

(defn move
  "Move a LIVE gesture to `at`.

  A move arriving when no gesture is live changes nothing — the pure half
  of the two-owner fence. That is not defensive coding: an accepted offer
  and the frame it is accepted against are a tick apart, so a preview
  dispatched just before a cancel legitimately lands just after it, and
  the handler has to be the thing that decides."
  ([s at] (move s at default-bounds))
  ([s at bs] (if (:dragging? s) (assoc s :at (settle at (bounds bs))) s)))

(defn commit
  "End the gesture at `at`, keeping it: `:at` and `:baseline` both settle
  there and the gesture is no longer live.

  It commits from a non-live state too, which is what makes a keystroke a
  whole gesture — one call, no start to pair with, the same terminal the
  pointer reaches."
  ([s at] (commit s at default-bounds))
  ([s at bs]
   (let [v (settle at (bounds bs))]
     (assoc s :at v :baseline v :dragging? false))))

(defn cancel
  "End the gesture, restoring the baseline it started from.

  Ending twice is ending once, and cancelling a gesture that never
  started restores the baseline the value already had — so an application
  may cancel from a route change, an Escape of its own, or a lost
  connection without asking whether a drag is in flight."
  [s]
  (assoc s :at (:baseline s) :dragging? false))

;; ---------------------------------------------------------------------------
;; The control
;; ---------------------------------------------------------------------------

(def component-id
  "The `data-component` scope every part id below is addressed under."
  "re-frame/splitter")

(def parts
  "The splitter's PUBLIC part roster — a deliberate subset, and here a
  subset of one, because the control renders one element and everything
  inside it is the caller's children.

  A part id is API: a stylesheet reaching `[data-part=\"separator\"]`
  breaks silently if this is renamed, exactly as a prop does."
  #{"separator"})

#?(:cljs
   (defn- rect-of
     "The bounding rect of `el`, as an ordinary map [[fraction-at]] reads."
     [el]
     (let [r (.getBoundingClientRect el)]
       {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)})))

#?(:cljs
   (defn- track-fraction
     "The raw fraction the pointer event `e` names on the TRACK — the
     element the separator is a child of.

     The track is the caller's, always: a split layout is a container
     holding a pane, this separator and another pane, and measuring the
     parent is measuring exactly the box the two panes divide. The control
     therefore owns no layout, no pane sizing and no measurement prop, and
     a caller who wants a different track puts the splitter in it."
     [e geom]
     (let [el (.-currentTarget e)]
       (some-> (.-parentElement el)
               rect-of
               (as-> r (fraction-at {:x (.-clientX e) :y (.-clientY e)} r geom))))))

#?(:cljs
   (defn- capture!
     "Ask the host to route this pointer's later events to `el`.

     BEST EFFORT, and that is the design rather than defensive coding.
     Capture is how the moves REACH this element — it is what replaces a
     `window` listener — and it is never what decides whether one is
     accepted. `setPointerCapture` raises on a pointer id the host does
     not consider active, so a host that declines simply leaves the
     gesture on the ordinary bubbling path, where every law still holds."
     [el pointer-id]
     (try (.setPointerCapture el pointer-id) (catch :default _ nil))
     nil))

#?(:cljs
   (defn- release!
     "Hand the routing back. Raises where there is nothing to release, and
     the host releases implicitly at the end of the gesture anyway, so the
     call is promptness rather than correctness."
     [el pointer-id]
     (try (.releasePointerCapture el pointer-id) (catch :default _ nil))
     nil))

(defn- percent
  "A fraction as the whole-number percentage `aria-valuenow` wants."
  [x]
  (Math/round (* 1.0 100 x)))

(v/defview splitter
  "(v/defview) A resizable pane divider: the split value in, four
  semantic intents out, and the same value reachable by pointer and by
  keyboard.

      [split/splitter {:split      (v/sub [:layout/split])
                       :bounds     {:min 0.2 :max 0.8}
                       :on-start   [:layout/split-started]
                       :on-preview [:layout/split-moved]
                       :on-commit  [:layout/split-committed]
                       :on-cancel  [:layout/split-cancelled]}]

  | prop | |
  |---|---|
  | `:split` | REQUIRED — the value [[init]] opens: `{:at … :baseline … :dragging? …}` |
  | `:on-commit` | REQUIRED — the settled split is appended |
  | `:on-start` | optional — a POINTER gesture began; a keystroke has none |
  | `:on-preview` | optional — one per accepted move; ABSENT means app-db sees nothing until the commit |
  | `:on-cancel` | optional — the browser cancelled the gesture |
  | `:orientation` | optional — `:horizontal` (default) or `:vertical`, the axis the split moves along |
  | `:rtl?` | optional — mirror the arrows and the pointer axis |
  | `:bounds` | optional — over [[default-bounds]] |
  | children | the caller's grip markup, rendered inside |
  | anything else | forwarded to the element through `v/spread-safe` |

  It renders ONE element: a `role=\"separator\"` with `tabindex`, its
  ARIA value trio, the `data-component`/`data-part` address a stylesheet
  reaches it through, and `data-dragging` while a drag is live.

  **`aria-orientation` is the separator's own, not the split's.** Panes
  side by side are divided by a separator that stands UP, so a
  `:horizontal` split renders `aria-orientation=\"vertical\"`. The two
  words disagree because they describe different objects, and getting it
  backwards is the single most common defect in a hand-written splitter —
  invisible to everyone except the screen-reader user, who is told the
  wrong axis on every focus.

  Every handler is on this one element, because `setPointerCapture`
  routes the whole drag here; there is no `window` listener, so there is
  nothing to remove and no unmount hook to remove it in.

  A caller cannot pass any of the pointer or key handlers: they are the
  component's own families and `v/spread-safe` refuses them in every
  build, in every spelling. `aria-*`, `data-*`, `:class` and `:style`
  reach the element normally."
  {:props [:map {:closed false}
           [:split :map]
           [:on-commit :vector]
           [:on-start {:optional true} [:maybe :vector]]
           [:on-preview {:optional true} [:maybe :vector]]
           [:on-cancel {:optional true} [:maybe :vector]]
           [:orientation {:optional true} :keyword]
           [:rtl? {:optional true} :boolean]
           [:bounds {:optional true} [:maybe :map]]]}
  [{:keys [split on-start on-preview on-commit on-cancel
           orientation rtl? children]
    :as   props}]
  (let [bs        (bounds (:bounds props))
        geom      {:orientation (or orientation :horizontal) :rtl? (boolean rtl?)}
        at        (:at split)
        dragging? (boolean (:dragging? split))
        across?   (not= :vertical (:orientation geom))]
    [:div
     (v/spread-safe
       {:data-component    component-id
        :data-part         "separator"
        :data-dragging     (when dragging? "true")
        :role              "separator"
        :tab-index         0
        :aria-orientation  (if across? "vertical" "horizontal")
        :aria-valuenow     (percent at)
        :aria-valuemin     (percent (:min bs))
        :aria-valuemax     (percent (:max bs))

        ;; The capture and the focus are taken in the SAME synchronous turn
        ;; the intent is produced in, because both are only available here:
        ;; a capture taken a tick later has already missed moves, and a
        ;; `div` is not focused by a pointer press on every engine. The
        ;; default is prevented so the press selects no text underneath.
        :on-pointer-down   (v/event [e]
                             #?(:cljs
                                (let [el (.-currentTarget e)]
                                  (.preventDefault e)
                                  (capture! el (.-pointerId e))
                                  (.focus el)
                                  on-start)
                                :clj on-start))

        ;; An OFFER, and the whole of the two-clock reduction. The test is
        ;; ARITHMETIC — settle it, compare it with the split on screen —
        ;; and nothing else: whether the gesture is still live is the
        ;; receiving handler's decision against committed state, which is
        ;; what lets a cancel beat a preview that was already in flight.
        :on-pointer-move   (v/event [e]
                             #?(:cljs
                                (when on-preview
                                  (let [nxt (some-> (track-fraction e geom) (settle bs))]
                                    (when (and nxt (not= nxt at))
                                      (conj on-preview nxt))))
                                :clj nil))

        ;; The terminal. It carries the settled split, so a call site that
        ;; wired no preview still commits the position the user released
        ;; at rather than the one they started from.
        :on-pointer-up     (v/event [e]
                             #?(:cljs
                                (when dragging?
                                  (release! (.-currentTarget e) (.-pointerId e))
                                  (conj on-commit (or (some-> (track-fraction e geom) (settle bs))
                                                      at)))
                                :clj nil))

        ;; The browser taking the pointer — a scroll, a pinch, a palm
        ;; rejection. The one ending the user did not ask for, and the
        ;; only one the control can tell apart from a release.
        :on-pointer-cancel (v/event [e]
                             #?(:cljs
                                (when dragging?
                                  (release! (.-currentTarget e) (.-pointerId e))
                                  on-cancel)
                                :clj nil))

        ;; A keystroke is a WHOLE gesture: it produces the terminal intent
        ;; and nothing else. Arrows are ignored while a drag is live —
        ;; the pointer owns the split until it lets go.
        :on-key-down       (v/event [e]
                             (let [k #?(:cljs (.-key e) :clj (:key e))]
                               (when-not dragging?
                                 (when-let [nxt (intent-at (key-intent k geom) at bs)]
                                   (when (not= nxt at)
                                     #?(:cljs (.preventDefault e))
                                     (conj on-commit nxt))))))}
       (dissoc props :split :on-start :on-preview :on-commit :on-cancel
                     :orientation :rtl? :bounds :children))
     children]))
