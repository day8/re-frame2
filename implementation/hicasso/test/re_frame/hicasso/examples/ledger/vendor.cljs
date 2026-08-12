(ns re-frame.hicasso.examples.ledger.vendor
  "THE BLESSED FOREIGN VIRTUALIZER (rf2-hic-047).

  `specification.md` §7's *Large collections* row answers the job with a
  *blessed foreign virtualizer recipe*, and a recipe needs something
  foreign to be a recipe ABOUT. This namespace is that something: a real
  windowing component, written in **raw React and nothing else**.

  ## It is FOREIGN, and that is mechanical rather than claimed

  Its only dependency is `[\"react\" :as react]`. It knows nothing of
  `re-frame.hicasso`, `re-frame.core`, the native tier or the test kit,
  and `ledger.surface-cljs-test` reads that off the analyzer's own
  dependency graph rather than off this sentence. So everything the
  ledger does with it, it does through the declared door — which is the
  whole point of the screen.

  It is a **stand-in for the npm package** for the reason
  `imperative-sdk-dom-cljs-test` states about its own vendor: the
  artefact may not take an npm dependency for a witness. It is written to
  the contract the two virtualizers a consumer would actually reach for
  publish, and it is written to have the properties that make
  virtualization HARD rather than the zero properties that make a mock
  easy:

  1. it renders **only a window** — the DOM holds tens of rows for a
     model of any size, so every identity, focus and accessibility
     question below is a real one;
  2. it owns **its own scroll offset**, in React state, and the
     application cannot see it;
  3. it **recycles nothing by itself** — the consumer supplies the key,
     so the identity of a row across a scroll is the consumer's decision
     and can therefore be got wrong.

  ## Why *blessed*, in three testable properties

  The spec's adjective is doing work. A virtualizer is blessed for a
  Hicasso screen when:

  | property | why it matters | what it would break |
  |---|---|---|
  | the consumer supplies the **key** | identity across a scroll is a model fact, and only the consumer knows the model | slot-keyed wrappers move focus and caret to a different record on every scroll |
  | its own wrappers are **`role=\"presentation\"`** | `role=\"grid\"` owns `role=\"row\"`, and a virtualizer inserts two divs between them | the rows stop being the grid's rows and the whole table's semantics collapse |
  | it can be told to **keep a row mounted** | React destroys the focused node the moment it leaves the window | focus is lost mid-interaction, silently, on an ordinary scroll |

  All three are real library features rather than inventions for this
  file. `@tanstack/react-virtual` hands the consumer `virtualItem.key`
  and takes a `rangeExtractor` that can force an index to stay in the
  rendered range; `react-window` does neither, which is exactly why the
  recipe names the property rather than the package.

  ## The ABI

      <VirtualRows
        count           = {n}         total rows in the MODEL
        rowHeight       = {px}
        viewportHeight  = {px}
        overscan        = {n}         extra rows rendered each side
        pinnedIndex     = {i | -1}    keep this row mounted, wherever it is
        renderRow       = {(index, offsetPx) => ReactNode}
        onWindow        = {(from, to) => void} />

  `renderRow` is **value-first**: it is handed the row's model index and
  the pixel offset it must position itself at, and there is no event
  anywhere in its arguments. That is HD-024's motivating shape, and it is
  why the ledger writes `h/hfn` at that position and not an intent
  vector. `onWindow` is value-first for the same reason.

  The rows position THEMSELVES, at the offset they are given, inside a
  spacer of the model's full height. That is `@tanstack/react-virtual`'s
  contract rather than `react-window`'s `style` handoff, and it is the
  one that leaves the key in the consumer's hands."
  (:require ["react" :as react]))

(defn window-from
  "The row window `[from to]`, inclusive both ends, that a scroll offset
  of `scroll-top` puts on screen — widened by `overscan` each side and
  clamped to the model.

  A pure function of five numbers, so `ledger.l0-cljs-test` can hold the
  arithmetic without a DOM — the one part of a virtualizer that is worth
  testing away from the engine, because an off-by-one here is invisible
  on screen and fatal to every count the DOM suite makes."
  [scroll-top {:keys [row-height viewport-height overscan total]}]
  (let [first-visible (js/Math.floor (/ scroll-top row-height))
        visible-rows  (js/Math.ceil (/ viewport-height row-height))
        from          (max 0 (- first-visible overscan))
        to            (min (dec total) (+ first-visible visible-rows overscan))]
    [from (max from to)]))

(defn virtual-rows
  "The component. An ordinary React function component — React calls it
  with one props object, and every hook in it is React's own.

  ## The scroll offset is state, and it is the VENDOR's

  A virtualizer cannot compute a window without knowing where the
  viewport is scrolled to, and that is DOM state: the platform owns it,
  the user moves it directly, and no application event precedes it. So it
  lives here, in `useState`, exactly as the specification's *native
  host-local animation/drag state* row licenses.

  It is emphatically NOT a second owner of anything the application
  knows. The ledger holds no scroll offset, reads none, and would not
  change behaviour if this component decided to window differently — the
  application's only interest in the window is the status line, and it
  learns that through `onWindow` like any other outward callback."
  [^js props]
  (let [total       (.-count props)
        row-height  (.-rowHeight props)
        pinned      (.-pinnedIndex props)
        render-row  (.-renderRow props)
        on-window   (.-onWindow props)
        viewport    (react/useRef nil)
        st          (react/useState 0)
        scroll-top  (aget st 0)
        set-scroll! (aget st 1)
        [from to]   (window-from scroll-top
                                 {:row-height      row-height
                                  :viewport-height (.-viewportHeight props)
                                  :overscan        (.-overscan props)
                                  :total           total})]
    ;; THE SCROLL LISTENER IS A PLAIN DOM ONE, and deliberately not an
    ;; `onScroll` prop.
    ;;
    ;; `@tanstack/react-virtual` does the same thing, through its
    ;; `observeElementOffset` option, and for the same reason: a
    ;; virtualizer wants the platform's own scroll notifications, on the
    ;; element it owns, without React's synthetic event system between it
    ;; and them. It also happens to be the version a test can drive — the
    ;; first two runs of `virtualized-dom-cljs-test` set `scrollTop`,
    ;; dispatched a real `scroll` event, watched the assertion on
    ;; `scrollTop` pass, and then found the window unmoved, because React
    ;; never delivered the event to an `onScroll` prop.
    (react/useEffect
      (fn []
        (let [^js node (.-current viewport)
              listener (fn [_] (set-scroll! (.-scrollTop node)))]
          (.addEventListener node "scroll" listener)
          (fn [] (.removeEventListener node "scroll" listener))))
      #js [])
    (react/useEffect
      (fn []
        (when on-window (on-window from to))
        js/undefined)
      #js [from to])
    (react/createElement
      "div"
      #js {:className "ledger-viewport"
           ;; PRESENTATION, and not decoration. `role="grid"` owns
           ;; `role="row"`, and these two divs sit between them; without
           ;; this the rows are no longer the grid's rows.
           :role      "presentation"
           :ref       viewport
           :style     #js {:height (.-viewportHeight props) :overflowY "auto"}}
      (react/createElement
        "div"
        #js {:className "ledger-spacer"
             :role      "presentation"
             :style     #js {:height (* total row-height) :position "relative"}}
        (into-array
          (concat
            (for [i (range from (inc to))]
              (render-row i (* i row-height)))
            ;; The PIN. One row, rendered at its true offset — so it is
            ;; off screen and in the document — whenever the window has
            ;; left it behind. `rangeExtractor` is the real feature this
            ;; models; the ledger uses it to keep the row the user is
            ;; typing into alive across a scroll that would otherwise
            ;; delete the node their caret is in.
            (when (and (number? pinned) (<= 0 pinned) (or (< pinned from) (> pinned to)))
              [(render-row pinned (* pinned row-height))])))))))
