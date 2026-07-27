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
  | [[virtual-list]] | the scroll host, the canvas, and the windowed rows |
  | [[virtual-row]] | the list's own row shell — not a call-site surface |

  Three of the five are pure functions of scalars, which is where the
  correctness lives. Nothing here schedules, measures, throttles, observes
  a resize, or owns a frame.

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
        :row             (v/render-fn [id _] [inbox-row {:id id}])}]

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
  record, which is why a re-render, a hot reload and a restored snapshot
  are all uneventful: there is nothing to reconcile.

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

  ## Its relationship to the virtual-table PILOT — UNSETTLED (rf2-86i64)

  `re-frame.freehand.pilot-virtual-table`, under `test/`, is a windowed
  table written as CONSUMER code to answer a different question: can the
  public surface express a virtual table with no substrate machinery at
  all? It answered yes, and it is not enrolled in the conformance index —
  it cites no `FH-` id.

  This namespace answers the next question, which is DC-04's: does the
  framework SHIP one, with an accepted law. So the two are a pilot and its
  promotion rather than two designs — but they are currently two
  IMPLEMENTATIONS, and that is one too many. Where they differ is written
  down rather than left for a reader to discover:

  - the pilot's window answers `{:start :end :count :skipped}` over
    `{:total :row-h :viewport-h :scroll-top}`; [[window]] answers
    `{:first :count :extent}` over the same five facts spelled the way the
    props are;
  - the pilot has no focus or keyboard law, and no residue assertion;
    those are what FH-CTRL-021 adds;
  - the pilot moves a live viewport's `scrollTop` back to a persisted
    offset through a `:layout` behavior (`restore-scroll`), which also
    buys it time-travel of a MOUNTED viewport. This control has no such
    seam and pushes that direction to an application effect — a smaller
    surface, and a strictly weaker answer.

  Which one survives, and whether the pilot's restoration seam should come
  with it, is rf2-86i64's to settle. Until it does, prefer this namespace
  for new code: it is the shipped one.

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
;; The row shell
;; ---------------------------------------------------------------------------

(v/defview virtual-row
  "(v/defview) [[virtual-list]]'s OWN row shell — the positioned,
  addressed, accessible box the caller's row content renders inside. It is
  the list's implementation and not a call-site surface: a caller reaches
  it only through `:row`.

  It is a declared child rather than markup inlined into the list's loop,
  and that is a requirement rather than a style. A row carries a committed
  event site (`:on-click`) that closes over the row's own identity, and
  the compiled grammar refuses a handler capturing a loop binding
  (`:rf.ui.compile/loop-capturing-handler`) precisely because a per-row
  committed slot needs a per-row instance. Its own catalogued recovery is
  to extract a declared child view and pass the binding as a prop, which
  is exactly this — so the control is inside the compiled grammar as it
  stands, and promotion is a keyword rather than a rewrite.

  It says three true things about a collection the DOM does not contain:
  `aria-posinset` is the row's ABSOLUTE position, `aria-setsize` is the
  collection's true size, and `aria-selected` is the caller's selection.
  A virtualized list that omitted them would tell a screen reader it holds
  forty rows."
  {:props [:map {:closed false}
           [:dom-id :string]
           [:index :int]
           [:item-count :int]
           [:row-extent :int]
           [:row-key :some]
           [:active? :boolean]
           [:on-activate {:optional true} [:maybe :vector]]]}
  [{:keys [dom-id index item-count row-extent row-key active? on-activate children]}]
  [:div {:id            dom-id
         :role          "option"
         :data-part     "row"
         :aria-posinset (inc index)
         :aria-setsize  item-count
         :aria-selected (if active? "true" "false")
         :style         {:position "absolute"
                         :left     0
                         :right    0
                         :top      (* index row-extent)
                         :height   row-extent}
         :on-click      (when on-activate (conj on-activate row-key))}
   children])

;; ---------------------------------------------------------------------------
;; The list
;; ---------------------------------------------------------------------------

(v/defview virtual-list
  "(v/defview) A fixed-extent virtual collection: a scroll host, a canvas
  of the collection's full height, and the rows of the visible window.

  | prop | |
  |---|---|
  | `:id` | REQUIRED — the DOM id the row addresses derive from |
  | `:row-keys` | REQUIRED — the ordered vector of stable item identities |
  | `:row-extent` | REQUIRED — the FIXED row height, in pixels |
  | `:viewport-extent` | REQUIRED — the scroll host's height, in pixels |
  | `:scroll-offset` | REQUIRED — the caller's scroll position |
  | `:row` | REQUIRED — a `v/render-fn` of `[row-key index]` |
  | `:on-scroll` | REQUIRED — the scroll intent; `::v/scroll-top` is appended |
  | `:overscan` | optional — rows rendered beyond each edge |
  | `:active-index` | optional — the caller's active row, by position |
  | `:on-key` | optional — the key intent; `::v/key` is appended |
  | `:on-activate` | optional — the row intent; the row's key is appended |
  | anything else | forwarded to the scroll host through `v/spread-safe` |

  ## What it renders

  Three semantic parts under one `data-component` scope, and no others:
  `viewport` (the scroll host, which is also the focus holder), `canvas`
  (the full-height box the rows are positioned inside), and `row`. The
  roster is a deliberate public subset — a caller's stylesheet reaches
  those three and nothing else — per Spec 004 §Theming and semantic parts.

  Everything inside a row is the caller's. The control emits no text, no
  cell, no divider and no chrome, because a list that owned its row markup
  would be un-adaptable exactly where every design system differs.

  ## Where the state is

  Entirely in the application's frame. `:scroll-offset` and
  `:active-index` are values the caller reads out of app-db and passes
  down; `:on-scroll` and `:on-key` are ordinary intents the caller
  registers. The control writes nothing anywhere, which is what makes the
  scroll position an epoch-carried, snapshot-restorable, tool-readable
  application fact rather than a number living in a host node that no
  re-frame reader can see.

  A caller who wants the browser's own scroll position to follow app-db
  after a keyboard move sets `scrollTop` from an ordinary effect on the
  element `:id` names. That direction is the application's, deliberately:
  writing to the host from inside a render is how a scroll controller
  starts fighting the user.

  ## Reading it back

  The DOM holds the window; `aria-setsize` and the canvas height hold the
  collection. `(count (find-all tree #(= \"row\" (:data-part (attrs %)))))`
  is therefore the deterministic count of rendered rows — a fact a test
  asserts by equality, with no timing and no instrumentation — and it is
  equal to `(:count (window …))` by construction.

  The viewport additionally carries `data-window-first` and
  `data-window-count`, which are EVIDENCE rather than parts: they state
  the window the render decided so a test, a screen recording or a tool
  can read it off one element instead of counting children. They are two
  small attributes on one node per list, and they are what makes 'which
  window is on screen' answerable without instrumenting the substrate."
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
  (let [total (count row-keys)
        w     (window {:item-count      total
                       :row-extent      row-extent
                       :viewport-extent viewport-extent
                       :scroll-offset   scroll-offset
                       :overscan        overscan})
        from  (:first w)
        to    (+ from (:count w))]
    [:div (v/spread-safe
            {:id                    id
             :role                  "listbox"
             :tabindex              0
             :data-component        "rf-virtual-list"
             :data-part             "viewport"
             :data-window-first     from
             :data-window-count     (:count w)
             :aria-activedescendant (when active-index (row-dom-id id active-index))
             :style                 {:height   viewport-extent
                                     :overflow "auto"
                                     :position "relative"}
             :on-scroll             (conj on-scroll ::v/scroll-top)
             :on-key-down           (when on-key (conj on-key ::v/key))}
            (dissoc props :id :row-keys :row-extent :viewport-extent :scroll-offset
                    :row :on-scroll :overscan :active-index :on-key :on-activate))
     [:div {:data-part "canvas"
            :style     {:position "relative"
                        :height   (:extent w)}}
      (for [i (range from to)
            :let [k (nth row-keys i)]]
        [virtual-row {:key         k
                      :dom-id      (row-dom-id id i)
                      :index       i
                      :item-count  total
                      :row-extent  row-extent
                      :row-key     k
                      :active?     (= i active-index)
                      :on-activate on-activate}
         (v/slot row k i)])]]))
