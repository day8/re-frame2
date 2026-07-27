(ns re-frame.freehand.collection
  "The FIXED-SIZE VIRTUAL COLLECTION — the fourth Freehand control witness
  (rf2-drpa3.182.11), and the one where the interesting question is not
  what the control renders but what it does NOT.

  A list of ten thousand rows is the ordinary application shape that
  breaks a naive substrate three separate ways: it mounts ten thousand
  boundaries, it subscribes ten thousand times, and — once somebody adds
  virtualization by hand — it grows a second state system for the scroll
  position, loses keyboard focus every time the focused row leaves the
  window, and lies to a screen reader about how many rows there are. This
  control is the witness that none of those is inherent.

  It is deliberately SMALL, and the smallness is the claim:

  | | |
  |---|---|
  | [[window]] | the visible window, as pure arithmetic |
  | [[reveal-offset]] | the smallest scroll that puts a row wholly on screen |
  | [[row-dom-id]] | the browser-facing address of a rendered row |
  | [[reconcile-scroll]] | the guarded `:layout` write that lands the offset |
  | [[virtual-collection]] | the ENGINE — viewport, canvas, positioned row shells |
  | [[virtual-list]] | a thin LISTBOX over the engine |
  | [[virtual-row]] | the listbox's own row — not a call-site surface |

  Three of the seven are pure functions of scalars, which is where the
  correctness lives. Nothing here schedules, measures, throttles, observes
  a resize, or owns a frame; the one imperative entry is a guarded write of
  one property on one node.

  ## Two layers, because virtualization is not a widget

  [[virtual-collection]] is the ENGINE and it is SEMANTIC-NEUTRAL: it owns
  the scroll host, the full-height canvas, the keyed positioned row shells
  and the window arithmetic, and it names no ARIA role at all. Its row slot
  receives the row's KEY, its ABSOLUTE INDEX and the collection's TOTAL —
  the three facts virtualization makes hard to state honestly — and what
  those facts are spelled AS is the caller's.

  [[virtual-list]] is the listbox built on it: `role=\"listbox\"`, options,
  selection, `aria-activedescendant`, and nothing else. It contains no
  second window arithmetic and no second scroll host; it decorates the
  engine's viewport through the ordinary forwarding seam and supplies the
  engine's row slot.

  The split is not anticipation, it is arithmetic against evidence. W3C's
  own listbox pattern sends a collection of INTERACTIVE rows to the grid
  pattern instead, whose cell and focus contract is materially different —
  so a virtual list hard-coded to `option` cannot host an editing grid
  without either a second engine or dishonest semantics. Mature
  virtualizers split at exactly this line (TanStack Virtual keeps ranges
  and keys independent of markup; react-window ships `List` and `Grid` as
  distinct widgets over one mechanics class), and so does this.

  What is deliberately NOT here: variable row heights, a columns model,
  sorting, a grid framework, and any `:semantics` option pretending to
  implement whole ARIA interaction patterns from a keyword. A first-party
  grid is a separate decision that evidence has not yet demanded; an
  application that needs grid semantics supplies them through the slot.

  ## The call site

      [coll/virtual-list
       {:id              \"inbox\"
        :row-keys        (v/sub [:inbox/ids])
        :row-extent      32
        :viewport-extent 640
        :scroll-offset   (v/sub [:inbox/scroll])
        :active-index    (v/sub [:inbox/active])
        :on-scroll       [:inbox/scrolled]
        :on-key          [:inbox/key-pressed]
        :on-activate     [:inbox/opened]
        :row             (v/render-fn [id _index _total] [inbox-row {:id id}])}]

  and `inbox-row` is an ordinary declared view that reads its own item:

      (v/defview inbox-row
        {:props [:map [:id :some]]}
        [{:keys [id]}]
        (let [m (v/sub [:inbox/message id])]
          [:span (:subject m)]))

  That is the whole port of a re-com `v-box` of rows, and it is short
  because the control asks for values and emits intent, exactly like every
  other props-only view. It owns no record, so it needs no `:control`
  address, no generation and no release.

  ## The five things it deliberately refuses

  **A second state system.** The scroll offset the control renders from is
  the caller's `:scroll-offset` prop and nothing else. `:on-scroll` carries
  `::v/scroll-top` — a closed member of the payload roster — so the host's
  `scrollTop` reaches an ordinary event and lands in app-db, where a tool
  can read it, an epoch can carry it and a snapshot can restore it. The
  control keeps no host slot, no ref, no local atom and no controller
  record: app-db is the single home, and the viewport is RECONCILED to it
  rather than consulted alongside it. That reconciliation
  ([[reconcile-scroll]]) is the one place a host property is written, it
  runs before paint, and it writes only when the node disagrees — so a
  restored snapshot lands on a viewport that follows it, while an ordinary
  user scroll moves no host state at all.

  **Row content in its own props.** The control never receives an item. It
  receives `:row-keys` — the ordered vector of IDENTITIES — and a `:row`
  render-fn whose body mounts the caller's declared row view. So a row's
  content is read by the row's OWN boundary and an edit to one row
  invalidates one boundary. The alternative spelling, handing the control
  a vector of item maps, makes every keystroke anywhere in the collection
  publish a new vector and re-render the whole visible window; it is not
  discouraged here, there is nowhere to put it.

  **Moving DOM focus to a row.** The viewport is the focus holder
  (`role=\"listbox\"`, `tabindex 0`) and the active row is named by
  `aria-activedescendant`. Roving focus — moving `document.activeElement`
  onto the active row — is the pattern that virtualization breaks: the
  focused element is unmounted the moment it scrolls out of the window,
  focus falls to `<body>`, and the next keystroke goes nowhere. Here the
  element that holds focus is the one element that is never windowed.

  **Lying about the size.** Every rendered row carries `aria-posinset` for
  its true absolute position and `aria-setsize` for the true total, and
  the canvas carries the true full extent. The DOM contains the window;
  the accessibility tree and the scrollbar state the collection. Both are
  true at once, which is the honest way to say that only a window is
  rendered.

  **Measuring anything.** Rows are a FIXED extent supplied by the caller.
  There is no measurement pass, no `ResizeObserver`, no dynamic window and
  no cache of measured heights, so [[window]] is arithmetic rather than a
  scheduler.

  ## What it is not

  It is not a data grid. There is no column model, no sorting, no
  filtering, no grouping, no cell editing, no row virtualization on the
  horizontal axis, no sticky header and no selection framework — selection
  is an index the application owns and an intent the control emits. There
  is no variable-height engine: a collection whose rows differ in height
  is outside this control, not a configuration of it. And the canvas is a
  real element of `item-count * row-extent` pixels, so a collection large
  enough to exceed the browser's maximum element height is outside it too.

  ## Its relationship to the virtual-table PILOT — SETTLED (rf2-86i64)

  `re-frame.freehand.pilot-virtual-table`, under `test/`, is a windowed
  table written as CONSUMER code to answer a different question: can the
  public surface express a virtual table with no substrate machinery at
  all? It answered yes, and it is not enrolled in the conformance index —
  it cites no `FH-` id. But it answered by writing a SECOND
  implementation, and that was one too many.

  rf2-86i64 settled it: this namespace is the sole survivor and the
  pilot's component half is retired, while the pilot's APPLICATIONS live
  on as callers of what is here. One engine, several honest uses. The
  ruling brought two of the pilot's boundaries across rather than leaving
  them to die with it:

  - **the semantic-neutral engine**, because the pilot's own editing grid
    is an existing consumer that cannot honestly be a listbox — it is
    `role=\"grid\"` over a hundred controlled cells. That is why
    [[virtual-collection]] exists and why it names no role.
  - **the controlled `:scroll-offset`**, because the pilot's
    `restore-scroll` moved a LIVE viewport to a persisted offset before
    paint, which also buys time-travel of a mounted viewport. This control
    originally had no such seam and pushed that direction to an
    application effect — a strictly weaker answer, said so, and now has
    the seam instead.

  The pilot's deletion is sequenced behind this file: its applications are
  rewritten as callers and its discriminating browser evidence is migrated
  onto these declarations FIRST, and only then does the second
  implementation go.

  Normative owner: [`spec/004-Views.md` §Controller state is ordinary
  frame data](../../../../../spec/004-Views.md#controller-state-is-ordinary-frame-data);
  proven by FH-CTRL-021."
  (:require [re-frame.freehand :as v]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The window, as pure arithmetic
;; ---------------------------------------------------------------------------

(defn- clamp [lo hi x] (max lo (min hi x)))

(defn window
  "The VISIBLE WINDOW over a fixed-extent collection — a pure function of
  five integers, answering three.

      (window {:item-count 10000 :row-extent 32
               :viewport-extent 640 :scroll-offset 0 :overscan 4})
      ;;=> {:first 0 :count 24 :extent 320000}

  | in | |
  |---|---|
  | `:item-count` | how many items the collection holds |
  | `:row-extent` | the FIXED height of one row, in pixels |
  | `:viewport-extent` | the height of the scroll host, in pixels |
  | `:scroll-offset` | how far the host is scrolled |
  | `:overscan` | rows to render beyond each edge; optional, default 0 |

  | out | |
  |---|---|
  | `:first` | the absolute index of the first rendered row |
  | `:count` | how many rows are rendered |
  | `:extent` | the full scrollable height, `item-count * row-extent` |

  **`:count` does not depend on `:item-count`.** It is bounded above by
  `ceil(viewport/extent) + 1 + 2*overscan` for every collection size, which
  is the whole cost claim of the control stated as arithmetic: ten items
  and ten million items render the same number of rows, so the work per
  frame is a property of the viewport rather than of the data.

  **Total over nonsense.** A negative or absent count, extent, viewport or
  offset is read as its floor rather than throwing, an offset beyond the
  end is clamped to the last full screen, and an empty collection or a
  zero-height viewport answers `:count 0` — a window is a fact about how
  much fits, and there is no arrangement of integers for which the honest
  answer is an exception.

  Pure, so the law is proven by CALLING it, on both hosts, with no host,
  no frame and no mount."
  [{:keys [item-count row-extent viewport-extent scroll-offset overscan]}]
  (let [n        (max 0 (or item-count 0))
        extent   (max 1 (or row-extent 1))
        viewport (max 0 (or viewport-extent 0))
        over     (max 0 (or overscan 0))
        canvas   (* n extent)]
    (if (or (zero? n) (zero? viewport))
      {:first 0 :count 0 :extent canvas}
      (let [offset    (clamp 0 (max 0 (- canvas viewport)) (or scroll-offset 0))
            raw-first (quot offset extent)
            ;; ceil((offset + viewport) / extent), in integers.
            raw-last  (inc (quot (dec (+ offset viewport)) extent))
            from      (clamp 0 n (- raw-first over))
            to        (clamp 0 n (+ raw-last over))]
        {:first  from
         :count  (max 0 (- to from))
         :extent canvas}))))

(defn reveal-offset
  "The SMALLEST scroll offset that puts row `index` wholly inside the
  viewport — the arithmetic behind 'the keyboard moved past the edge, so
  the list scrolled'.

      (reveal-offset {:item-count 10000 :row-extent 32
                      :viewport-extent 640 :scroll-offset 0} 25)
      ;;=> 192

  A row already whole on screen answers the CURRENT offset unchanged,
  which is what stops arrow-key navigation inside the window from jerking
  the list. A row above the window is brought to the top edge, a row below
  it to the bottom edge, and the answer is clamped into the scrollable
  range like any other offset.

  It is a function rather than a behaviour of the control because the
  decision is the application's: which key moves the active row, whether
  it wraps, and whether moving it scrolls at all are policy, and a control
  that owned them would be a keyboard framework. The application's handler
  calls this against COMMITTED state and returns the new offset in the
  same event as the new active index, so the two settle as one epoch."
  [{:keys [item-count row-extent viewport-extent scroll-offset]} index]
  (let [n        (max 0 (or item-count 0))
        extent   (max 1 (or row-extent 1))
        viewport (max 0 (or viewport-extent 0))
        max-off  (max 0 (- (* n extent) viewport))
        offset   (clamp 0 max-off (or scroll-offset 0))
        i        (clamp 0 (max 0 (dec n)) (or index 0))
        top      (* i extent)
        bottom   (+ top extent)]
    (clamp 0 max-off
           (cond
             (< top offset)                (long top)
             (> bottom (+ offset viewport)) (long (- bottom viewport))
             :else                          (long offset)))))

(defn row-dom-id
  "The browser-facing address of the row at absolute `index` in the list
  identified by `list-id`.

  Two addresses exist for one row and they are deliberately different
  things. The `:key` is the row's IDENTITY — the caller's stable item id,
  what React reconciles on, and what survives a reorder. This is the row's
  POSITION, and it is what `aria-activedescendant` and a stylesheet need,
  because both address the document rather than the collection. Deriving
  either from the other would make a reorder rename a DOM id, or make an
  accessibility relationship depend on a domain key's spelling.

  Exposed because `aria-activedescendant` names an id the caller cannot
  otherwise compute, and a test that wants to find the active row should
  ask the same function the control answers with."
  [list-id index]
  (str list-id "-row-" index))


;; ---------------------------------------------------------------------------
;; The one direction render cannot carry: state back onto a live viewport
;; ---------------------------------------------------------------------------
;;
;; `:scroll-offset` decides which rows are rendered, and a render can do that
;; much on its own. What a render CANNOT do is move a host property: a freshly
;; mounted viewport sits at scrollTop 0 while the canvas paints rows 96-124 at
;; 3072px, and the box shows blank. The same hole swallows a time-travel
;; restore — the window value moves, the mounted viewport does not follow —
;; which is why the offset is CONTROLLED here rather than a cache trailing the
;; DOM.
;;
;; Writing the offset back onto the node is host work, and `v/behavior`'s
;; `:layout` timing is the one seam the substrate sanctions for it: before
;; paint, because a restore that ran after paint would show the wrong rows for
;; one frame.

(defn- reconcile-scroll-offset!
  "Move `node`'s `scrollTop` to `offset`, but ONLY when they differ — so a
  write that would change nothing fires no spurious scroll event, and the
  behavior never overwrites an offset the viewport already holds. The
  ordinary user scroll is exactly that case: the DOM already carries the
  value app-db has just learned, so the guard makes it a no-op and the
  user's thumb is never fought."
  [node offset]
  #?(:cljs
     (let [offset (max 0 (long (or offset 0)))]
       (when (not= (.-scrollTop node) offset)
         (set! (.-scrollTop node) offset))))
  nil)

(v/defbehavior reconcile-scroll
  "Write the collection's `:scroll-offset` back onto the viewport, before
  paint.

  `:config` carries `{:scroll-offset n}` — DATA, the offset alone.
  `:connect` restores it into a freshly mounted viewport; `:update` follows
  it when the value MOVES while the DOM has not, which is the time-travel
  and programmatic-set case. Both write through
  [[reconcile-scroll-offset!]], so an ordinary user scroll — where app-db is
  merely trailing the DOM — moves no host state at all.

  An offset past the end of the collection needs no clamping here: the host
  clamps its own `scrollTop`, the resulting `scroll` event carries the real
  value into the application's own handler, and the next config is the
  corrected one. The offset self-corrects through the ordinary path rather
  than through arithmetic duplicated at the seam.

  Attaching this is imperative host work and the cost is deliberate and
  small — one node, one property, two lifecycle entries, no memory to
  release. It is not a promotion cost: the compiled grammar admits the
  behavior form, so a virtual collection compiles with the reconciliation in
  place exactly as it did without."
  {:timing  :layout
   :connect (fn [{:keys [node config]}]
              (reconcile-scroll-offset! node (:scroll-offset config))
              nil)
   :update  (fn [{:keys [node config]}]
              (reconcile-scroll-offset! node (:scroll-offset config))
              nil)})

;; ---------------------------------------------------------------------------
;; The engine — mechanics, and no semantics at all
;; ---------------------------------------------------------------------------

(v/defview virtual-collection
  "(v/defview) The VIRTUALIZATION ENGINE: a scroll host, a canvas of the
  collection's full height, and the keyed positioned shells of the visible
  window. It names no ARIA role, and that is the whole point of it.

  | prop | |
  |---|---|
  | `:row-keys` | REQUIRED — the ordered vector of stable item identities |
  | `:row-extent` | REQUIRED — the FIXED row height, in pixels |
  | `:viewport-extent` | REQUIRED — the scroll host's height, in pixels |
  | `:scroll-offset` | REQUIRED — the CONTROLLED scroll position |
  | `:row` | REQUIRED — a `v/render-fn` of `[row-key index total]` |
  | `:on-scroll` | REQUIRED — the scroll intent; `::v/scroll-top` is appended |
  | `:overscan` | optional — rows rendered beyond each edge |
  | `:attrs` | optional — attributes for the viewport, through `v/spread-safe` |

  ## `:scroll-offset` is CONTROLLED, in both directions

  It is not a cache of what the host happens to hold. The render picks the
  window from it, and [[reconcile-scroll]] lands it on the live viewport
  before paint whenever the two disagree. Both directions matter and each
  one alone is a bug: render alone leaves a freshly mounted viewport at
  scrollTop 0 under rows painted at 3072px, and the DOM alone leaves the
  offset somewhere no epoch, snapshot or tool can see. Together they make
  the offset ordinary application state that a `restore-epoch` can move on
  a MOUNTED viewport, not merely a window value.

  There is no opt-out. One prop must not carry two meanings, and the
  un-reconciled mode is the blank-viewport bug rather than a lighter
  configuration of the same idea.

  ## Why it is semantic-neutral

  Virtualization mechanics and widget semantics are different layers, and
  a control that fuses them can only ever serve one widget. A virtual list
  hard-coded to `role=\"listbox\"` cannot host an editing grid — W3C's own
  listbox pattern sends collections of interactive rows to the GRID
  pattern, whose cell, focus and keyboard contract is materially different
  — so fusing them buys a second engine the first time a second widget
  turns up. [[virtual-list]] is that first widget, built on this in a
  hundred lines; a grid would be another, and neither is inside here.

  So the engine emits mechanics and nothing else: `data-part` names, the
  positioning, the canvas extent and the two window-evidence attributes.
  The ROLE, the selection, the keyboard and the accessible position are
  supplied by whoever calls it — through `:attrs` for the viewport, and
  through the row slot for the rows.

  `:attrs` is a MAP rather than the loose leftovers of the props, and that
  is the grammar's doing rather than a taste: a render-slot callback is
  legal only as a literal call-site prop value, so a wrapper that folded
  its own props into a `merge` could not also hand this one a `:row`. The
  explicit seam is the better shape anyway — it separates what the engine
  IS configured by from what it merely carries to an element.

  ## The row slot's three arguments

  `:row` is invoked with the row's KEY, its ABSOLUTE INDEX and the
  collection's TOTAL. Those three are exactly the facts virtualization
  makes hard to state honestly — the DOM holds forty rows and the
  collection holds ten thousand — so the engine hands them over rather
  than deciding what they are called. `aria-posinset`/`aria-setsize`
  spells them for a listbox and `aria-rowindex`/`aria-rowcount` for a
  grid, and choosing between those is a semantic decision the engine has
  no business making.

  ## What it renders

  Three parts, and no others: `viewport` (the scroll host), `canvas` (the
  full-height box the rows are positioned inside), and `row-shell` (one
  per rendered row — the absolutely positioned box the slot's content
  renders inside). The shell is `role=\"presentation\"` because it is
  geometry rather than meaning: the semantic element is the slot's, so the
  accessibility tree sees the caller's row and not this box.

  The viewport additionally carries `data-window-first` and
  `data-window-count`, which are EVIDENCE rather than parts: they state
  the window the render decided so a test, a screen recording or a tool
  can read it off one element instead of counting children."
  {:props [:map {:closed false}
           [:row-keys :vector]
           [:row-extent :int]
           [:viewport-extent :int]
           [:scroll-offset :int]
           [:row :some]
           [:on-scroll :vector]
           [:overscan {:optional true} [:maybe :int]]
           [:attrs {:optional true} [:maybe :map]]]}
  [{:keys [row-keys row-extent viewport-extent scroll-offset row on-scroll
           overscan attrs]}]
  (let [total (count row-keys)
        w     (window {:item-count      total
                       :row-extent      row-extent
                       :viewport-extent viewport-extent
                       :scroll-offset   scroll-offset
                       :overscan        overscan})
        from  (:first w)
        to    (+ from (:count w))]
    [v/behavior {:use reconcile-scroll :config {:scroll-offset scroll-offset}}
     [:div (v/spread-safe
             {:data-part         "viewport"
              :data-window-first from
              :data-window-count (:count w)
              :style             {:height   viewport-extent
                                  :overflow "auto"
                                  :position "relative"}
              :on-scroll         (conj on-scroll ::v/scroll-top)}
             attrs)
      [:div {:data-part "canvas"
             :style     {:position "relative"
                         :height   (:extent w)}}
       (for [i (range from to)
             :let [k (nth row-keys i)]]
         [:div {:key       k
                :data-part "row-shell"
                :role      "presentation"
                :style     {:position "absolute"
                            :left     0
                            :right    0
                            :top      (* i row-extent)
                            :height   row-extent}}
          (v/slot row k i total)])]]]))

;; ---------------------------------------------------------------------------
;; The listbox — one widget over the engine
;; ---------------------------------------------------------------------------

(v/defview virtual-row
  "(v/defview) [[virtual-list]]'s OWN row — the addressed, selectable
  `option` the caller's row content renders inside. It is the listbox's
  implementation and not a call-site surface: a caller reaches it only
  through `:row`.

  It is a declared child rather than markup inlined into a loop, and that
  is a requirement rather than a style. A row carries a committed event
  site (`:on-click`) that closes over the row's own identity, and the
  compiled grammar refuses a handler capturing a loop binding
  (`:rf.ui.compile/loop-capturing-handler`) precisely because a per-row
  committed slot needs a per-row instance. Its own catalogued recovery is
  to extract a declared child view and pass the binding as a prop, which
  is exactly this — so the control is inside the compiled grammar as it
  stands, and promotion is a keyword rather than a rewrite.

  It says three true things about a collection the DOM does not contain:
  `aria-posinset` is the row's ABSOLUTE position, `aria-setsize` is the
  collection's true size, and `aria-selected` is the caller's selection.
  A virtualized list that omitted them would tell a screen reader it holds
  forty rows. All three are LISTBOX spellings of the three arguments
  [[virtual-collection]]'s row slot hands over, which is the whole of what
  makes this the semantic layer and that one the mechanical layer.

  It carries no positioning: the engine's shell owns where the row IS, and
  this owns what it MEANS."
  {:props [:map {:closed false}
           [:dom-id :string]
           [:index :int]
           [:item-count :int]
           [:row-key :some]
           [:row :some]
           [:active? :boolean]
           [:on-activate {:optional true} [:maybe :vector]]]}
  [{:keys [dom-id index item-count row-key row active? on-activate]}]
  [:div {:id            dom-id
         :role          "option"
         :data-part     "row"
         :aria-posinset (inc index)
         :aria-setsize  item-count
         :aria-selected (if active? "true" "false")
         :style         {:height "100%"}
         :on-click      (when on-activate (conj on-activate row-key))}
   (v/slot row row-key index item-count)])

(v/defview virtual-list
  "(v/defview) A fixed-extent virtual LISTBOX — [[virtual-collection]]
  wearing `role=\"listbox\"`, and nothing more than that.

  | prop | |
  |---|---|
  | `:id` | REQUIRED — the DOM id the row addresses derive from |
  | `:row-keys` | REQUIRED — the ordered vector of stable item identities |
  | `:row-extent` | REQUIRED — the FIXED row height, in pixels |
  | `:viewport-extent` | REQUIRED — the scroll host's height, in pixels |
  | `:scroll-offset` | REQUIRED — the caller's scroll position |
  | `:row` | REQUIRED — a `v/render-fn` of `[row-key index total]` |
  | `:on-scroll` | REQUIRED — the scroll intent; `::v/scroll-top` is appended |
  | `:overscan` | optional — rows rendered beyond each edge |
  | `:active-index` | optional — the caller's active row, by position |
  | `:on-key` | optional — the key intent; `::v/key` is appended |
  | `:on-activate` | optional — the row intent; the row's key is appended |
  | anything else | forwarded to the scroll host through `v/spread-safe` |

  ## It is a wrapper, and the emphasis is on how LITTLE it is

  It runs no window arithmetic, owns no scroll host, positions nothing and
  measures nothing — all of that is the engine's, and a second copy of any
  of it here would be the two-implementations problem this control was
  consolidated to end. What it adds is exactly the listbox pattern:
  `role=\"listbox\"` and `tabindex 0` on the engine's viewport, `option`
  rows carrying the accessible position and the selection,
  `aria-activedescendant`, and a key intent.

  It reads [[window]] once, to decide whether the active row is on screen.
  That is the same pure function the engine renders from, called a second
  time — one arithmetic used twice, not a second arithmetic.

  ## What it renders

  Four semantic parts under one `data-component` scope, and no others:
  `viewport`, `canvas` and `row-shell` are the engine's, and `row` is this
  layer's `option`. The roster is a deliberate public subset — a caller's
  stylesheet reaches those four and nothing else — per Spec 004 §Theming
  and semantic parts.

  Everything inside a row is the caller's. The control emits no text, no
  cell, no divider and no chrome, because a list that owned its row markup
  would be un-adaptable exactly where every design system differs.

  ## Where the state is

  Entirely in the application's frame. `:scroll-offset` and
  `:active-index` are values the caller reads out of app-db and passes
  down; `:on-scroll` and `:on-key` are ordinary intents the caller
  registers. There is no host slot, no ref and no controller record, which
  is what makes the scroll position an epoch-carried,
  snapshot-restorable, tool-readable application fact rather than a number
  living in a host node that no re-frame reader can see — and the engine's
  guarded reconciliation is what makes the restore land on a MOUNTED
  viewport rather than only on the next window it picks.

  ## Reading it back

  The DOM holds the window; `aria-setsize` and the canvas height hold the
  collection. `(count (find-all tree #(= \"row\" (:data-part (attrs %)))))`
  is therefore the deterministic count of rendered rows — a fact a test
  asserts by equality, with no timing and no instrumentation — and it is
  equal to `(:count (window …))` by construction."
  {:props [:map {:closed false}
           [:id :string]
           [:row-keys :vector]
           [:row-extent :int]
           [:viewport-extent :int]
           [:scroll-offset :int]
           [:row :some]
           [:on-scroll :vector]
           [:overscan {:optional true} [:maybe :int]]
           [:active-index {:optional true} [:maybe :int]]
           [:on-key {:optional true} [:maybe :vector]]
           [:on-activate {:optional true} [:maybe :vector]]]}
  [{:keys [id row-keys row-extent viewport-extent scroll-offset row on-scroll
           overscan active-index on-key on-activate]
    :as   props}]
  (let [w         (window {:item-count      (count row-keys)
                           :row-extent      row-extent
                           :viewport-extent viewport-extent
                           :scroll-offset   scroll-offset
                           :overscan        overscan})
        rendered? (and (some? active-index)
                       (<= (:first w) active-index)
                       (< active-index (+ (:first w) (:count w))))]
    [virtual-collection
     {:row-keys        row-keys
      :row-extent      row-extent
      :viewport-extent viewport-extent
      :scroll-offset   scroll-offset
      :overscan        overscan
      :on-scroll       on-scroll
      :attrs           (merge (dissoc props :row-keys :row-extent :viewport-extent
                                      :scroll-offset :row :on-scroll :overscan
                                      :active-index :on-key :on-activate)
                              {:role                  "listbox"
                               :tabindex              0
                               :data-component        "rf-virtual-list"
                               :aria-activedescendant (when rendered?
                                                        (row-dom-id id active-index))
                               :on-key-down           (when on-key
                                                        (conj on-key ::v/key))})
      :row             (v/render-fn [k i total]
                         [virtual-row {:dom-id      (row-dom-id id i)
                                       :index       i
                                       :item-count  total
                                       :row-key     k
                                       :row         row
                                       :active?     (= i active-index)
                                       :on-activate on-activate}])}]))
