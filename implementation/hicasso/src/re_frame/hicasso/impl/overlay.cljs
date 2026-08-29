(ns re-frame.hicasso.impl.overlay
  "THE TOP LAYER, REACHED. The impure half of
  `re-frame.hicasso.overlay`: two components that put an element into the
  browser's own top layer and take it out again. The posture and the
  vocabulary are the door's; this file is the mechanism.

  ## What it costs, stated rather than buried

  **Two React hooks — one `useContext` and one `useRef` — in each of
  these components.** The ≤2-hook budget I9 polices is the *boundary
  shell's*, and neither of these is a boundary shell: they read no
  subscription, mount no registration and take no cell.
  `collector/shell`'s dispatcher-level ledger counts exactly two there
  regardless, which
  `re-frame.hicasso.overlay-dom-cljs-test` asserts rather than assumes.
  `impl.presence-react` is the precedent and spends four; the ceiling's
  own words are that an optional capability may not add a hook *to every
  boundary*, and this adds none to any.

  - The `useContext` is the frame hook, and it is here for the reason it
    is in `impl.presence-react`: an overlay's children are hiccup DATA
    written in the parent boundary's body and lowered in THIS component's
    render, after that body's dynamic extent has unwound. Without it
    `intent/*dispatch*` is unbound when the codec walks them, and every
    intent inside an overlay raises
    `:rf.error/hicasso-intent-outside-boundary`.
  - The `useRef` is one instance cell holding this overlay's generated
    anchor ident and the ONE ref callback that ever attaches. It exists
    because a ref callback whose identity changes between renders is a ref
    React detaches and re-attaches — which for an open dialog means
    close-then-reopen on every parent render, losing focus and the top
    layer's stack position each time.

  ## Where the work happens: the ref callback, and nothing else

  There is no effect here. `showModal` / `showPopover` run in the REF
  CALLBACK, which React invokes during the commit — after the node is in
  the document and before the browser paints. That is the whole of
  \"measure before paint\": the module measures nothing at all, and the one
  thing it must do before paint is the imperative call, which is where it
  is.

  The ANCHOR CLAIM is there too, and both halves of it: the trigger's
  `anchor-name` and the panel's `position-anchor` are one mechanism, so
  they are made in one place. Keeping them together is also what keeps a
  page-wide counter's output out of every server render's bytes — see
  [[anchor-panel!]]. Splitting them is a hydration defect rather than an
  untidiness.

  The cleanup React 19 lets a ref callback return is the exact inverse,
  and it runs while the node is STILL CONNECTED — which is what makes the
  platform's own focus restoration reachable. A `<dialog>` returns focus
  to its previously-focused element when it is CLOSED, and not when it is
  merely removed from the document, so the module closes it through the
  platform's door on the way out rather than letting React drop it. That
  one line is the whole of \"focus restore\".

  ## The one keyboard handler, and why the modal alone carries it

  `showModal` makes the rest of the document inert, so the engine owns
  everything about the trap except its last inch: the wrap off the
  panel's final control goes through the document's own end-of-scope
  step, parking focus on `<body>` — nowhere — for one press. The modal
  therefore renders one `onKeyDown` prop that closes those two edges and
  nothing else; see [[wrap-tab!]] for the measurement. The popover
  renders none, because a popover is deliberately not a trap.

  It costs no idle listener: `keydown` bubbles, so React delegates it at
  the root it already owns rather than attaching anything to the element,
  and `:open?` false renders no element to hold a prop in the first
  place. It also means `:on-key-down` written on the modal ELEMENT is the
  module's, the way `:on-cancel` there already is — an author's key
  handling belongs on the controls inside, where this one yields to it.

  ## Why the open flag can never acquire a second owner

  `:open?` false renders nil, so a closed overlay has no element at all.
  The element exists only while the application says it should, and the
  platform's own dismissal is routed back as an ordinary intent rather
  than applied behind app-db's back.

  When there is nowhere to route it, the platform is told not to dismiss
  at all, in the platform's own vocabulary: `closedby=\"none\"` on the
  dialog, `popover=\"manual\"` on the popover. Both are attributes, so the
  not-dismissable case costs no JavaScript and no listener whatever.

  No frame in scope is not an error for an overlay, as it is not for
  `impl.presence-react`: without `:on-dismiss` it reads nothing, and the
  attributes above keep it single-owner. WITH `:on-dismiss` and no frame,
  an open overlay REFUSES at render with the existing
  `:rf.error/hicasso-intent-outside-boundary`, naming the intent —
  `:on-dismiss` is an intent, and the alternative is an element that tells
  the platform it may dismiss while nothing could route the dismissal,
  which is exactly the second owner. The precedent is HD-020's frameless
  ruling in `docs/design/hicasso/decisions.md`.

  ## Which event each one routes on, and why they differ

  The modal routes on `cancel`, which fires only for a close request —
  never on the way in, and never for a programmatic `close()`. The popover
  routes on `beforetoggle` rather than `toggle`, because the platform
  QUEUES a task for `toggle`: an application that reads its own open flag
  on the line after a dismissal would read the value the dismissal has not
  written yet. `beforetoggle` fires in both directions, so the popover's
  handler takes only `newState == \"closed\"` — the one filter in the file,
  and the sabotage that widens it dispatches `:on-dismiss` on OPEN.

  There is deliberately no second guard — no per-node mark set before
  the module's own `hidePopover()` to keep a teardown from being read as
  a dismissal. **No sabotage could redden one**: React does not deliver
  an event from a fiber it is in the middle of deleting, so the case
  never arises. The measurement stands where the mark would — the
  teardown rows of `re-frame.hicasso.overlay-dom-cljs-test` red the day
  that stops being true."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.hicasso.impl.intent :as intent]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Placement — a compass word, and the `position-area` it means
;; ---------------------------------------------------------------------------

(def position-areas
  "`:placement` as the author writes it, and the CSS `position-area` it
  means. Public so a witness can drive the table rather than restate it.

  `span-inline-end` reads backwards until the anchor is held in mind: it
  spans from the anchor's inline-START edge TOWARDS the end, which is what
  puts a `:bottom-start` panel's left edge under its trigger's left edge.

  **This map is the whole of the module's placement.** Nothing measures
  and nothing listens: where a panel sits is the compositor's answer to a
  CSS declaration, recomputed by the engine as the anchor moves. That is
  why anchor tracking costs nothing here — the scroll listener and the
  per-frame `getBoundingClientRect` a hand-rolled overlay spends it on are
  simply not work this module does."
  {:top           "block-start"
   :top-start     "block-start span-inline-end"
   :top-end       "block-start span-inline-start"
   :bottom        "block-end"
   :bottom-start  "block-end span-inline-end"
   :bottom-end    "block-end span-inline-start"
   :left          "inline-start"
   :left-start    "inline-start span-block-end"
   :left-end      "inline-start span-block-start"
   :right         "inline-end"
   :right-start   "inline-end span-block-end"
   :right-end     "inline-end span-block-start"})

(defn position-area
  "The `position-area` value `placement` means, or nil for no placement.

  An unrecognised value is emitted verbatim, so `:placement` also takes a
  raw `position-area` string for the cases a compass word cannot spell. A
  MISSPELT compass word therefore produces an invalid CSS value, which the
  engine drops: the panel lands in the top layer at the UA's default
  position rather than throwing."
  [placement]
  (when (some? placement)
    (or (get position-areas placement)
        (if (keyword? placement) (name placement) (str placement)))))

;; ---------------------------------------------------------------------------
;; The anchor — a DOM id in, a CSS anchor name out
;; ---------------------------------------------------------------------------

;; **Page-wide on purpose, and narrowing it would be a defect** — the one
;; row on `docs/design/hicasso/product/globals.md` where the ordinary
;; "a mutable global, therefore scope it to a root" reading introduces the
;; bug it thinks it is preventing.
;;
;; A CSS anchor name lives in ONE namespace per document, not one per React
;; root. Two roots each minting `--rf-overlay-1` put two live overlays on
;; one name, and the second claim silently steals the first's positioning.
;; The counter is page-wide because the namespace it allocates into is.
(defonce ^:private !anchor-seq (atom 0))

(defn- next-anchor-ident
  "A fresh CSS dashed-ident for one overlay INSTANCE — not for one anchor
  id. Two overlays may legitimately share a trigger, and a shared ident
  would make each one's teardown erase the other's.

  **A client lifecycle token, and never content.** Telling two overlays
  on one trigger apart at teardown is the whole of what it is for, which
  is a fact about a live document rather than about the page's meaning.
  It reaches the DOM only through [[claim-anchor!]] and
  [[anchor-panel!]], both of which run in the ref callback, so it is in
  no server render's bytes — see [[anchor-panel!]] for why that matters."
  []
  (str "--rf-overlay-" (swap! !anchor-seq inc)))

(defn- claim-anchor!
  "Give the element named by DOM id `anchor-id` the CSS anchor name
  `ident`, and answer `#js [element previous-name]` so the caller can put
  the previous name back.

  **The previous name goes to the CALLER, not onto the element.** Two
  overlays may legitimately share one trigger — a menu and a tooltip on
  the same button — and a single parking slot on the element is one slot
  for two claims: the second claim overwrites the first's memory, and the
  first release then restores an empty string instead of the author's own
  name.

  **No `:anchor` is nil; an `:anchor` that names no element REFUSES.**
  The two are not one absence, and answering nil to both would make
  the second invisible: an author who writes no anchor asks for the
  UA's default position and gets it, while an author who writes one
  asks to be positioned against a trigger and would get that same
  default in silence. A typo in a DOM id would read exactly like a
  design choice.

  It refuses from the REF CALLBACK, which is the only place the question
  can be asked: the id resolves against a document React has already
  mutated for this commit, so the same check during render would be
  reading the previous one. React routes a commit-phase throw to an
  enclosing `h/error-boundary`, and to `reportError` when there is none —
  which is why the witness in `re-frame.hicasso.overlay-dom-cljs-test`
  reads that channel rather than `cljs.test`'s stack."
  [anchor-id ident]
  (when anchor-id
    (if-some [^js el (.getElementById js/document anchor-id)]
      (let [previous (.. el -style -anchorName)]
        (set! (.. el -style -anchorName) ident)
        #js [el previous])
      (fail! :rf.error/hicasso-overlay-anchor-missing
             're-frame.hicasso.impl.overlay/claim-anchor!
             (str "An overlay's :anchor is " (pr-str anchor-id) ", and no "
                  "element in the document carries that id, so there is "
                  "nothing to position the panel against.")
             {:anchor anchor-id}))))

(defn- anchor-panel!
  "Point `panel` at the CSS anchor name `ident` — the PANEL's half of the
  claim [[claim-anchor!]] makes on the TRIGGER. The two are one mechanism
  and they live in one place.

  ## Why this is imperative

  Written declaratively, as `:style {:position-anchor …}` on the element,
  the ident would reach the SERVER BYTES. It is minted from
  `!anchor-seq`, which is page-wide on purpose and correctly so — but a
  server has no document to be page-wide with respect to, and no request
  scope to fall back on. Two `renderToString` calls over ONE immutable
  snapshot would then answer `position-anchor:--rf-overlay-5` and
  `position-anchor:--rf-overlay-6`: same input, different output, on an
  attribute hydration must match, and drifting further from a fresh
  client's counter with every request the process serves.

  Setting it here makes the bytes deterministic BY CONSTRUCTION rather
  than by discipline: there is no ident in them to disagree about. That
  is stronger than making the counter deterministic would be, because it
  removes the class rather than one member of it.

  ## Why not a deterministic name instead

  A name derived from React's own `useId` is the obvious alternative and
  it does not work here TODAY, for a reason already measured rather than
  suspected. A hydrating root carries the adoption closer as a SIBLING of
  the app subtree (`impl.mount/tree`), React derives a `useId` from tree
  POSITION as well as from the prefix, and a hand-rolled
  `renderToString` — bytes no product door baked — emits no counterpart
  to that fork, so a `useId` minted in the app subtree hydrates into a
  value the server never wrote. That is
  dispositions.md HS-11's second obstruction, green in
  `identifier-prefix-ssr-dom-cljs-test`, and it would have traded a
  drifting mismatch for a fixed one on the same attribute. It would also
  have spent a THIRD React hook in both components, which this file's
  opening argument prices deliberately at two.

  ## What it costs, which is nothing visible

  A ref callback runs during the COMMIT — after the node is in the
  document and before the browser paints — so the name is on the panel
  before the panel has ever been painted, and `show!` on the very next
  line is what makes it visible at all. There is no frame in which an
  anchored panel is painted unanchored."
  [^js panel ident]
  (set! (.. panel -style -positionAnchor) ident)
  nil)

(defn- release-anchor!
  "Put `previous` back on the element `claim-anchor!` answered — but ONLY
  while `ident` is still the name on it.

  That condition is what makes the per-instance ident mean anything. When
  the overlay that leaves is not the one that claimed last, an
  unconditional restore writes the trigger back to a name the OTHER, still
  open panel is not anchored to, and silently unanchors a live overlay.
  The residual is stated rather than chased: an out-of-order teardown can
  leave the LAST claimant's generated ident on the trigger instead of the
  author's own name. It is a dashed-ident nothing references, so it is
  inert, and tracking a stack per trigger to erase it would be a second
  ownership graph for a cosmetic difference."
  [claimed ident]
  (when-some [^js el (aget claimed 0)]
    (when (= ident (.. el -style -anchorName))
      (set! (.. el -style -anchorName) (or (aget claimed 1) ""))))
  nil)

;; ---------------------------------------------------------------------------
;; The trap's last inch — Tab at the modal's two edges
;; ---------------------------------------------------------------------------
;;
;; THE ONE THING THE ENGINE'S OWN TRAP DOES NOT DO, measured rather than
;; assumed. `showModal` makes the rest of the document inert, so Tab
;; cannot reach a control behind the panel — that half is the engine's
;; and this module adds nothing to it. But the WRAP off the panel's last
;; control goes through the document's own end-of-scope step, and for one
;; press `document.activeElement` is `<body>`: focus rests nowhere, the
;; focus ring vanishes, and in a browser with UI that step is the browser
;; UI rather than the page. Pressed in this repo's Chromium, headless and
;; headed alike, over a three-control modal starting at the first:
;;
;;     Tab       → reason, cancel, BODY, confirm, reason …
;;     Shift+Tab → BODY, cancel, reason, confirm …
;;
;; Four stops for three controls, and headed Chromium does not even do it
;; consistently — the second backward lap dropped the waypoint. A modal
;; is bought for "focus cannot Tab outside it", which the guide promises,
;; so the two edges are closed here.
;;
;; This is a WRAP AT TWO EDGES and not a focus manager. It tracks no
;; state, holds no listener while idle, and does not run for any key but
;; Tab: between the edges the engine moves focus exactly as it always
;; did. It does have to know which control IS each edge, and that much
;; order it derives on the press — see [[sequential-tab-stops]], which
;; states its own boundary, because a half-model that does not state one
;; is the failure mode here.

(def ^:private tab-stop-selector
  "Everything the engine could make a tab stop of, as a selector.

  A superset on purpose, narrowed by [[tab-stop?]] and then ORDERED by
  [[sequential-tab-stops]]. On its own it answers *which elements are
  candidates*, which is not the same question as *where Tab goes*; the
  gap between the two is what [[sequential-tab-stops]] exists to close."
  (str "a[href],area[href],button,input,select,textarea,summary,"
       "iframe,object,embed,audio[controls],video[controls],"
       "[contenteditable],[tabindex]"))

(defn- rendered?
  "Is `el` rendered — painted somewhere a user could reach it?

  `checkVisibility` is the platform's own answer and is asked for it,
  because the obvious substitute is wrong in the direction that costs a
  WRONG landing. `getClientRects()` reports a box for
  `visibility:hidden` and for the contents of a closed `<details>`
  (measured `rects: 1` for both, in this repo's Chromium), and both of
  those refuse focus — so reading rects counts a trailing control that
  is not a stop, and the cost is measured: Tab off a modal's real last
  control finds the surplus candidate at the edge instead, declines to
  wrap, and lets focus reach `<body>`.

  **`visibilityProperty` and NOT `contentVisibilityAuto`, measured
  rather than copied from the option list.** A control inside a
  `content-visibility:auto` subtree that is currently skipped STILL
  TAKES FOCUS — the engine renders it on the way in — while
  `checkVisibility({contentVisibilityAuto: true})` answers false for it.
  Passing that option would drop a real stop, and a set too SMALL puts
  the wrap at the wrong control rather than merely failing to fire it.
  `opacityProperty` is left off for the same reason: `opacity:0` is
  focusable, measured.

  The rect test stays as the fallback for an engine without the method.
  It is the weaker answer — it is exactly the reading whose two gaps are
  named above — but an engine that lacks `checkVisibility` is better
  served by it than by no test at all."
  [^js el]
  (if (fn? (.-checkVisibility el))
    (.checkVisibility el #js {"visibilityProperty" true})
    (pos? (.-length (.getClientRects el)))))

(defn- tab-stop?
  "Would Tab stop here? Four ways the answer is no, and each is asked of
  the engine in the form the engine itself uses.

  **`:disabled` the pseudo-class, and not the `.disabled` property.** The
  IDL property reflects the element's OWN attribute, so a control inside
  a `<fieldset disabled>` reports `disabled: false` while the engine
  refuses it focus — measured. The pseudo-class is the EFFECTIVE state,
  which makes it a strictly wider reading of the same question rather
  than a second rule beside it: it still matches a plainly `disabled`
  control, and it gets the fieldset's own carve-out right for free.
  Controls in a disabled fieldset's FIRST `<legend>` stay enabled, and
  they are real stops; walking up to `fieldset[disabled]` by hand would
  have dropped them, and a candidate set too SMALL aims the wrap at the
  wrong control, which [[rendered?]] explains is the worse failure.

  **`inert` is read as the attribute, because there is no pseudo-class
  for it** — `matches(':inert')` throws `SyntaxError` in this repo's
  Chromium, measured rather than assumed. `closest` is what makes the
  reading INHERITED, which is the shape inertness actually has: the
  attribute goes on the region, and every control under it stops being a
  stop.

  The other two doors are unchanged: a negative `tabIndex` is reachable
  only programmatically, and an element the engine does not render is
  reachable by nothing — see [[rendered?]]."
  [^js el]
  (and (not (.matches el ":disabled"))
       (nil? (.closest el "[inert]"))
       (not (neg? (.-tabIndex el)))
       (rendered? el)))

(defn- radio-group-of
  "The radio group `el` belongs to, or nil when it belongs to none.

  HTML groups radio buttons by FORM OWNER **and** NAME together, and a
  radio with an empty name is in no group at all. Both halves measured
  rather than read: two same-named groups inside two `<form>`s keep one
  stop each, and two unnamed radios side by side are two stops."
  [^js el]
  (when (and (.matches el "input[type='radio']")
             (not= "" (.-name el)))
    [(.-form el) (.-name el)]))

(defn- one-stop-per-radio-group
  "`stops` with every named radio group collapsed to the single element
  the engine actually sequences to.

  **A radio group is one tab stop, not one per button** — the checked
  member, or the first focusable member when none is checked. Measured in
  this repo's Chromium over a modal holding
  `[unchecked r1, checked r2, ok]`: Tab visits `r2` and `ok` and never
  `r1`.

  The choice is made over elements [[tab-stop?]] has ALREADY kept, which
  is what makes the two degenerate cases fall out rather than need
  rules: a checked-but-disabled member is gone before the vote, so the
  first surviving member takes the slot, and a group whose members are
  all disabled contributes no stop at all. Both measured."
  [stops]
  (let [chosen (into {}
                     (for [[group members] (group-by radio-group-of stops)
                           :when (some? group)]
                       [group (or (first (filter #(.-checked ^js %) members))
                                  (first members))]))]
    (filterv (fn [el]
               (if-some [group (radio-group-of el)]
                 (identical? el (get chosen group))
                 true))
             stops)))

(defn- sequential-tab-stops
  "`panel`'s tab stops IN THE ORDER TAB VISITS THEM.

  ## What this models, and what it does not

  It models the two places sequential order departs from document order
  inside an ordinary panel, each of which sends a document-order answer
  to the wrong edge:

  - a named radio group is ONE stop ([[one-stop-per-radio-group]]);
  - a positive `tabindex` sorts ahead of every `tabindex=0`, ascending,
    with document order inside each bucket.

  **It is not the platform's focus algorithm and does not try to be.**
  Shadow roots, `delegatesFocus` and scrollable-overflow focusability
  are outside the set entirely, and a control the engine skips for any
  reason not listed on [[tab-stop?]] is still counted here.

  ## The four effective non-stops, and why none may be counted

  There are four ways an element can be a candidate this scan finds and
  a stop the engine never visits: `visibility:hidden`, the contents of a
  closed `<details>`, an `inert` subtree, and a `disabled` `<fieldset>`.
  All four are excluded — the first two by [[rendered?]] asking
  `checkVisibility` instead of reading client rects, the last two by
  [[tab-stop?]] asking `:disabled` and `[inert]`.

  A surplus candidate costs a WRAP THAT DOES NOT FIRE when it cannot
  take focus, and a WRAP THAT LANDS WRONG when it can. Measured with
  `.focus()` on each: all four refuse focus, while an unchecked radio in
  a checked group ACCEPTS it — which is why the radio group is a wrong
  landing rather than a missed wrap.

  **A missed wrap is not free at the LAST candidate.** There the surplus
  is what `peek` returns, so Tab off the real last control matches no
  edge, the handler declines, and the engine's own end-of-scope step puts
  `document.activeElement` on `<body>` — the leak this whole handler
  exists to close, reached by a fourth route. Shift+Tab off the first
  stop fails the same way from the other end: the wrap AIMS at the
  surplus, `.focus()` declines, `preventDefault` is correctly withheld,
  and withholding it is what lets focus out. So `refuses focus` and
  `costs nothing` are different claims, and only the first holds.

  A trailing non-stop is ordinary markup rather than exotic: a wizard
  step the user has not arrived at is an `inert` region, and a form
  section a prior answer has not unlocked is a `disabled` `<fieldset>`,
  and either is naturally written after the buttons that lead to it.
  [[a-real-tab-wraps-past-a-trailing-inert-region]] and
  [[a-real-tab-wraps-past-a-trailing-disabled-fieldset]] pin both cases.

  A group whose checked member sits OUTSIDE `panel` needs no rule: this
  handler is the modal's alone, so everything outside the panel is
  inert, cannot be the group's stop, and the engine falls to the first
  focusable member inside — which is what scanning only `panel` already
  computes. Measured."
  [^js panel]
  (->> (array-seq (.querySelectorAll panel tab-stop-selector))
       (filterv tab-stop?)
       (one-stop-per-radio-group)
       ;; Document order is the tie-break INSIDE a tabindex bucket, so it
       ;; is carried explicitly rather than left to `sort-by` being
       ;; stable: the rule is the point, and a sort that quietly stopped
       ;; being stable would surface as a flaky wrap rather than as a
       ;; failure anyone could read.
       (map-indexed (fn [i ^js el]
                      (let [t (.-tabIndex el)]
                        [(if (pos? t) t js/Infinity) i el])))
       (sort-by (fn [[bucket i _]] [bucket i]))
       (mapv peek)))

(defn- wrap-tab!
  "Tab off the panel's last stop lands on its first; Shift+Tab off its
  first lands on its last. Every other press falls through untouched.

  **The engine confirms the landing before the default action is taken
  away.** `HTMLElement.focus()` on an element that cannot be focused —
  inert because a nested modal is open above it, which carries no
  attribute for [[tab-stop?]] to read — is a no-op that leaves
  `activeElement` where it was, so the move is attempted first and
  `preventDefault` follows only if it took. A wrap that could not land
  therefore degrades to exactly the engine's own conduct rather than to
  a Tab that does nothing.

  **That confirmation is a floor, and reading it as a licence to feed
  this a loose candidate set is the standing trap here.**
  It saves a wrap aimed at nothing. It cannot save one aimed at the
  wrong CONTROL — and it cannot save the case where a surplus candidate
  is never aimed at at all, because the surplus DISPLACES THE EDGE and
  the press off the real edge matches nothing. The second is the
  subtler failure: it happens one step before the landing this
  confirmation guards, so no amount of care here reaches it.
  [[sequential-tab-stops]] carries that account.

  A press a control inside the panel has already claimed is left alone:
  the native event's `defaultPrevented` is read rather than the synthetic
  event's copy, which React takes at construction and which a child's
  `preventDefault` during the same dispatch would not update.

  **First and last are read off SEQUENTIAL order, not document order.**
  They are different lists — a radio group is three elements and one
  stop — and taking document order for sequential sends Tab from a
  modal's last control onto an unchecked radio, and Shift+Tab from its
  checked one onto `<body>`."
  [^js e]
  (when (and (= "Tab" (.-key e))
             (not (.. e -nativeEvent -defaultPrevented)))
    (let [^js panel (.-currentTarget e)
          stops     (sequential-tab-stops panel)]
      (when (seq stops)
        (let [back? (.-shiftKey e)
              edge  (if back? (first stops) (peek stops))
              land  (if back? (peek stops) (first stops))]
          (when (identical? edge (.-target e))
            (.focus land)
            (when (identical? land (.. panel -ownerDocument -activeElement))
              (.preventDefault e)))))))
  nil)

;; ---------------------------------------------------------------------------
;; The two platform doors
;; ---------------------------------------------------------------------------

(def ^:private modal-ops
  {:tag       :dialog
   :show!     (fn [node] (when-not (.-open node) (.showModal node)))
   ;; Through `close()` rather than by letting React drop the node: a
   ;; dialog returns focus to its previously-focused element when it is
   ;; CLOSED, and not when it is merely removed from the document.
   :hide!     (fn [node] (when (.-open node) (.close node)))
   :event     :on-cancel
   ;; `cancel` fires only for a close request — never on the way in, and
   ;; never for a programmatic `close()` — so it needs no filter at all.
   :closed-only? false
   ;; The modal's alone. A popover is deliberately NOT a trap — a filter
   ;; menu a keyboard cannot tab out of is a defect — so it takes no
   ;; keyboard handler at all and `overlay-focus-dom-cljs-test` holds that
   ;; contrast.
   :key-down  wrap-tab!})

(def ^:private popover-ops
  {:tag       :div
   :show!     (fn [node] (when-not (.matches node ":popover-open") (.showPopover node)))
   :hide!     (fn [node] (when (.matches node ":popover-open") (.hidePopover node)))
   ;; `beforetoggle` and NOT `toggle`, measured rather than chosen: the
   ;; platform QUEUES a task for `toggle` and fires `beforetoggle`
   ;; synchronously, so routing on `toggle` puts app-db a macrotask behind
   ;; the screen. An application that reads its own open flag on the line
   ;; after a dismissal — every test does — reads the value the dismissal
   ;; has not written yet.
   :event     :on-before-toggle
   ;; It fires on the way IN as well as on the way out, so this one is
   ;; filtered by `newState`. The module's own `hidePopover()` fires it
   ;; too, and needs no filter of its own — see [[dismissal-handler]].
   :closed-only? true})

;; ---------------------------------------------------------------------------
;; The instance cell, and the one ref callback that ever attaches
;; ---------------------------------------------------------------------------

(defn- make-cell
  "The per-instance state: an anchor ident minted once, the trigger this
  overlay claimed, and the stable ref callback that owns both.

  **Every use of the ident is inside this callback**, which is what keeps
  it out of the server bytes — see [[anchor-panel!]]."
  [{:keys [show! hide!]}]
  (let [cell #js {"ident"    (next-anchor-ident)
                  "anchorId" nil
                  "claimed"  nil}]
    (unchecked-set
      cell "ref"
      (fn [node]
        (when node
          (let [ident   (unchecked-get cell "ident")
                claimed (claim-anchor! (unchecked-get cell "anchorId") ident)]
            (unchecked-set cell "claimed" claimed)
            ;; BOTH HALVES OF ONE CLAIM, OR NEITHER. `claim-anchor!`
            ;; answers nil for exactly one case — no `:anchor` at
            ;; all — because an `:anchor` that names no element raises
            ;; `:rf.error/hicasso-overlay-anchor-missing` rather than
            ;; returning. Writing the panel's half alone
            ;; would be a dangling reference dressed up as a claim, so
            ;; the guard stays: it reads "the author asked for no
            ;; anchor" and nothing else.
            (when claimed
              (anchor-panel! node ident)))
          (show! node)
          (fn []
            (hide! node)
            (when-some [claimed (unchecked-get cell "claimed")]
              (release-anchor! claimed (unchecked-get cell "ident")))
            (unchecked-set cell "claimed" nil)
            nil))))
    cell))

(defn- dismissal-handler
  "The function the platform's own dismissal event lands on: nil when
  there is no `:on-dismiss`, and the loud
  `:rf.error/hicasso-intent-outside-boundary` when there is one and no
  frame dispatch to route it — because a dismissal the element invites
  and nothing routes is the open flag's second owner. The precedent is
  HD-020's frameless ruling in `docs/design/hicasso/decisions.md`.

  `closed-only?` is the popover's: `beforetoggle` fires on the way IN as
  well, and an unfiltered handler would dispatch `:on-dismiss` on OPEN.
  A fresh closure per render, deliberately, so an overlay whose
  `:on-dismiss` changed cannot dispatch the previous one. The module's
  own teardown needs no filter — argued in the namespace docstring and
  measured by `re-frame.hicasso.overlay-dom-cljs-test`'s teardown rows."
  [dispatch on-dismiss closed-only?]
  (when on-dismiss
    (when-not dispatch
      (fail! :rf.error/hicasso-intent-outside-boundary
             're-frame.hicasso.impl.overlay/dismissal-handler
             (str "This overlay carries :on-dismiss " (pr-str on-dismiss)
                  " but no frame is in scope, so the platform would be told "
                  "it may dismiss while nothing could route the dismissal. "
                  "Mount the overlay under a frame — h/mount!, "
                  "rf/frame-provider or frame-root — or drop :on-dismiss if "
                  "it should not be dismissable.")
             {:intent on-dismiss}))
    (fn [e]
      (when-not (and closed-only? (not= "closed" (.-newState e)))
        (dispatch on-dismiss)))))

;; ---------------------------------------------------------------------------
;; The components
;; ---------------------------------------------------------------------------

(def ^:private own-keys
  "The keys the module reads. Everything else in the props map is an
  ordinary attribute and reaches the element unrenamed, exactly as it
  would at a native tag the author wrote by hand."
  [:open? :on-dismiss :anchor :placement :label :light-dismiss? :children])

(defn- element-attrs
  "The author's props as element attributes: the module's own keys
  removed, `extra` merged over the rest, and the two `:style` maps merged
  with the AUTHOR's keys on top — so `:style {:position-area …}` beats the
  `:placement` that produced one."
  [props extra]
  (let [style (merge (:style extra) (:style props))]
    (cond-> (merge (apply dissoc props own-keys) (dissoc extra :style))
      (seq style) (assoc :style style))))

(defn- body
  [{:keys [tag event closed-only? key-down] :as ops} dismissal-attrs js-props]
  (let [props    (or (unchecked-get js-props "rfProps") {})
        ;; Hook 1 — the frame, classified through the shared reader so a
        ;; no-provider sentinel resolves to nil ("no scope") rather than
        ;; being mistaken for a frame keyword.
        frame-kw (adapter-context/context-value->current-frame
                   (react/useContext adapter-context/frame-context))
        ;; Hook 2 — the instance cell. Filled lazily rather than through
        ;; `useRef`'s argument, which is evaluated on EVERY render: minting
        ;; the ident there would burn the counter once per render and hand
        ;; back a different anchor name each time.
        ref-cell (react/useRef nil)]
    (when (nil? (.-current ref-cell))
      (set! (.-current ref-cell) (make-cell ops)))
    (let [cell (.-current ref-cell)]
      (unchecked-set cell "anchorId" (:anchor props))
      ;; ZERO COST WHEN CLOSED. No element, so no top-layer entry, no
      ;; listener, no anchor claim and no children rendered — the body's
      ;; own subscriptions included.
      (when (:open? props)
        (let [dispatch (when frame-kw (collector/frame-dispatch frame-kw))
              area     (position-area (:placement props))
              extra    (cond-> (assoc (dismissal-attrs props)
                                      :ref   (unchecked-get cell "ref")
                                      event  (dismissal-handler dispatch (:on-dismiss props) closed-only?))
                         (some? key-down)
                         (assoc :on-key-down key-down)

                         (some? (:label props))
                         (assoc :aria-label (:label props))

                         ;; `:position-anchor` is deliberately NOT here.
                         ;; The panel's anchor name is claimed in the ref
                         ;; callback with the trigger's, so it never
                         ;; reaches a server render's bytes — argued at
                         ;; [[anchor-panel!]].
                         ;; `:position-area` is a static string the
                         ;; placement table answers and stays declarative.
                         (some? area)
                         (assoc-in [:style :position-area] area))]
          ;; THE LOWERING, inside the frame. These children were written in
          ;; the parent's body and are walked HERE, so the ambient frame the
          ;; codec's intent lowering reads has to be re-established around
          ;; this call and nowhere else.
          (intent/with-frame frame-kw dispatch
            (fn []
              (codec/as-element
                (into [tag (element-attrs props extra)] (:children props))))))))))

(defn- modal-dismissal-attrs
  "`closedby`, the platform's own word for which close requests this
  dialog honours.

  There is nowhere to route a dismissal without `:on-dismiss`, so the
  dialog is told to honour none and the open flag cannot acquire a second
  owner. With one, Escape closes by default and a backdrop click only when
  the author asked for it — a destructive confirmation must not go away on
  a stray click."
  [props]
  {:closedby (cond
               (nil? (:on-dismiss props)) "none"
               (:light-dismiss? props)    "any"
               :else                      "closerequest")})

(defn- popover-dismissal-attrs
  "`popover`, the platform's own word for the same choice. `auto` is the
  light-dismissable member of the top layer's LIFO stack; `manual` is in
  the top layer and dismisses for nothing."
  [props]
  {:popover (if (nil? (:on-dismiss props)) "manual" "auto")})

(def modal
  "See [[re-frame.hicasso.overlay/modal]]."
  (codec/mark-boundary!
    (doto (fn hicasso-modal [js-props] (body modal-ops modal-dismissal-attrs js-props))
      (unchecked-set "displayName" "hicasso/modal"))))

(def popover
  "See [[re-frame.hicasso.overlay/popover]]."
  (codec/mark-boundary!
    (doto (fn hicasso-popover [js-props] (body popover-ops popover-dismissal-attrs js-props))
      (unchecked-set "displayName" "hicasso/popover"))))
