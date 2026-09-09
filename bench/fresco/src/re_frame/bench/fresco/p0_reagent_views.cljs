(ns re-frame.bench.hicasso.p0-reagent-views
  "THE SHIP BAR'S DENOMINATOR — Reagent reading re-frame2 subscriptions,
  and the two arms that make that number mean something (rf2-2rtt6.2).

  HD-012 states the bar as `mount AND bulk view-work ≤ 1.0× Reagent,
  LIKE-FOR-LIKE — both sides reading re-frame2 subscriptions`. Every
  Reagent row in this repo before today read a bare `reagent.core/atom`
  through an `r/cursor`, which is Reagent's own idiom but is NOT the same
  amount of framework: it pays no subscription graph, no query cache, no
  frame. A candidate measured against it would be measured against a
  denominator no re-frame2 application has. This namespace supplies the
  one the bar actually names.

  ## The four arms, and what each is for

  | arm | what it is | what it is FOR |
  |---|---|---|
  | `:floor` | the same DOM, hand-built with `react/createElement`, no substrate | the per-round CALIBRATOR — this box drifts further across rounds than several of the effects being measured, so every published figure is a ratio to the floor measured in THAT round |
  | `:reagent-subs` | Reagent views reading `@(rf/subscribe [:p0/cell i])` | **the denominator.** The bar's `1.0×` is this arm |
  | `:reagent-ratom` | Reagent views reading an `r/cursor` over an `r/atom` | a labelled LOWER BOUND, never the bar. `subs − ratom` is the price of the reactive system, stated rather than argued |
  | `:ctl-2x` | the floor at exactly twice the boundaries | the POSITIVE CONTROL. Element count doubles by arithmetic, so the prediction is 2.00× and it is written down before the run |

  `:ctl-2x` is `:parity-exempt?` because it builds a different page ON
  PURPOSE — that is what makes it a control — and folding it into the
  canonical-DOM equality would turn the fairness gate into a permanent
  failure.

  ## Why one page can host all four

  The missing Reagent-on-subs arm was expensive for the predecessor
  programme for a reason that does not apply here: its own view substrate
  was inert under a ratom adapter, so a page could not host it and a
  re-frame-reading Reagent arm at once, and the design called for `two
  adapter phases bridged by the floor`. Every arm below is Reagent or
  nothing. One `(rf/init! reagent/adapter)` serves all four, the floor
  has no substrate to conflict with, and the whole two-phase problem
  disappears. That is worth saying because it is the reason this bead is
  a day's work rather than the week the donor budgeted.

  ## The markup is one markup

  Read the three declarations beside each other: the tag sugar, the
  class names, the attribute order and the text are the same, because the
  suite gates canonical-DOM equality across every arm before it reads a
  clock. Hiccup is a shared dialect, so the Reagent arms are not a
  translation of the floor so much as the same page under a different
  reader — and that is exactly the condition that makes a ratio a
  SUBSTRATE ratio rather than a serialiser ratio.

  ## What is idiomatic here, and why it has to be

  A strawman denominator would make the whole comparison worthless, so
  the `:reagent-subs` arm is written the way a re-frame2 Reagent
  application is actually written: `reg-view` boundaries (a plain Reagent
  `defn` carries no `:contextType`, so its `subscribe` would resolve no
  frame and the render would raise `:rf.error/no-frame-context`),
  `^{:key i}` metadata on seq children (Reagent's own spelling), one
  subscription read per boundary, `reagent.dom.client` for the mount, and
  `rf/frame-provider` at the root. Nothing is pre-adapted and no React
  class is hand-built.

  The `:reagent-ratom` arm is the counterpart written the way a plain
  Reagent application is: a FORM-2 component minting one `r/cursor` per
  mounted occurrence, which is the reason a single-cell write re-renders
  one component rather than three hundred."
  (:require ["react" :as react]
            [re-frame.core :as rf]
            [reagent.core :as r])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; The shape, as arithmetic
;; ---------------------------------------------------------------------------

(def cells-n
  "300 boundaries — the make-or-break bulk shape from the charter's use-case
  A3, and the mount witness M1's size."
  300)

(def fields-n
  "12 fields — the ordinary form the charter's use-case A1 calls `the shape
  most applications are made of`."
  12)

(defn m1-elements
  "One for the list, then three per row: the row, its label and its cell.
  Arithmetic, so the gate is against a WRITTEN expectation rather than
  against whatever the mount happened to produce."
  [n]
  (+ 1 (* 3 n)))

(defn m2-elements
  "Three for the skeleton — the form, the fieldset and the submit button —
  then four per field: the wrapper, the label, the input and the error."
  [fields]
  (+ 3 (* 4 fields)))

(defn field-value [i v] (str "value " v "-" i))
(defn field-error [i] (if (even? i) "" (str "field " i " is required")))

;; ---------------------------------------------------------------------------
;; The subscriptions and the frames
;; ---------------------------------------------------------------------------

(defn cell-value
  "`:p0/cell`'s COMPUTATION, as a named function.

  It is named rather than inlined into the registration below so that a
  witness which must register the query behind something — the keystroke
  witness registers it behind a recompute census (rf2-0qj9w) — reaches
  ONE body instead of copying it. A copied `(get-in db [:cells i])` is
  the drift class this lane already pays for deliberately in its ARMS,
  and there is no reason to pay it for an accessor."
  [db i]
  (get-in db [:cells i]))

(defn register!
  "The sub graph: ONE layer-1 subscription, keyed by index, one read per
  boundary — the first rung of the HD-002 1/3/7/20 ladder.

  Called at load, exactly as it was when rf2-2rtt6.2 published, so this
  arm's behaviour is unchanged. It is a FUNCTION as well because the
  witness-convergence run (rf2-a4x1o) installs and destroys an adapter
  once per segment and calls it again on every segment entry. A
  re-register overwrites with the identical handler, so calling it twice
  and calling it once are the same registry."
  []
  (rf/reg-sub :p0/cell (fn [db [_ i]] (cell-value db i)))
  nil)

(register!)

(def subs-frame
  "One frame for the `:reagent-subs` arm, created once at boot. A frame is
  application state, not mount state: views mount and unmount against it
  exactly as they do in a running application, and minting one per mount
  would leak 3,000 frames across a six-round run and price frame
  construction as though it were view work."
  :p0/subs)

(defn seed-cells [n v] {:cells (vec (repeat n v))})

;; ---------------------------------------------------------------------------
;; M1 — the 300-boundary sub-reading list
;; ---------------------------------------------------------------------------

(reg-view ^{:rf/id :p0/cell-view} m1-cell
  "One boundary, one subscription read. The unit the bar is about."
  [i]
  (let [v @(rf/subscribe [:p0/cell i])]
    [:li.row
     [:span.lbl "cell "]
     [:span.cell {:data-i i} (str v)]]))

(reg-view ^{:rf/id :p0/m1} m1-subs
  [n]
  [:ul.grid {:role "list"}
   (for [i (range n)]
     ^{:key i} [m1-cell i])])

;; -- the ratom counterpart --------------------------------------------------

(defonce ratom-cells (r/atom []))

(defn ratom-watch-count
  "How many reactions are watching [[ratom-cells]] right now — the ratom
  family's live-reference census, and the count neither of the lane's
  built-in residue counters can supply.

  Every mounted [[m1-cell-ratom]] / [[m2-field-ratom]] occurrence derefs an
  `r/cursor` over [[ratom-cells]]. Reagent backs each cursor with a cached
  Reaction that adds itself to the atom's watch map while anything reads it
  and removes itself when its last watcher is disposed, so a ratom arm
  whose unmount returned normally WITHOUT releasing its root shows up here
  as a count that never came back down: rooted in this namespace-level
  atom — outside the frame's sub-cache and off `document.body` — its
  detached tree would re-render on every later bulk write. `^clj` because
  [[ratom-cells]] is a ClojureScript deftype instance: the hint keeps
  `:advanced` treating this access exactly like Reagent's own
  `add-w`/`remove-w` instead of minting an extern for it."
  []
  (count (.-watches ^clj ratom-cells)))

(defn m1-cell-ratom
  "A FORM-2 component: the outer call mints a `cursor` once per mounted
  occurrence and the returned render fn dereferences only that cursor.
  Reagent's own fine-grained reactive leaf, and the counterpart of
  [[m1-cell]]'s `subscribe`."
  [i]
  (let [c (r/cursor ratom-cells [i])]
    (fn [i]
      [:li.row
       [:span.lbl "cell "]
       [:span.cell {:data-i i} (str @c)]])))

(defn m1-ratom
  [n]
  [:ul.grid {:role "list"}
   (for [i (range n)]
     ^{:key i} [m1-cell-ratom i])])

;; -- the floor --------------------------------------------------------------

(defn- el
  ([tag props] (react/createElement tag props))
  ([tag props children] (react/createElement tag props children)))

(defn- m1-row-el [i v]
  (el "li" #js {:key i :className "row"}
      #js [(el "span" #js {:key "l" :className "lbl"} "cell ")
           (el "span" #js {:key "c" :className "cell" :data-i i} (str v))]))

(defn m1-floor
  "The same list, hand-built. No boundary, no reactive wrapper, no props
  map, no attribute walk — the irreducible cost of asking React to build
  this page, which is why `arm − floor` is a substrate's OWN overhead
  rather than a reconciliation both arms pay identically."
  [cells]
  (el "ul" #js {:className "grid" :role "list"}
      (into-array (map-indexed m1-row-el cells))))

;; ---------------------------------------------------------------------------
;; M2 — the ordinary form, on subs
;; ---------------------------------------------------------------------------

(reg-view ^{:rf/id :p0/field-view} m2-field
  [i]
  (let [v @(rf/subscribe [:p0/cell i])]
    [:div.field
     [:label.lbl {:for (str "f" i)} (str "Field " i)]
     [:input.inp {:id        (str "f" i)
                  :name      (str "f" i)
                  :type      "text"
                  :value     (field-value i v)
                  :read-only true}]
     [:p.err (field-error i)]]))

(reg-view ^{:rf/id :p0/m2} m2-subs
  [fields]
  [:form.p0form
   [:fieldset.fields
    (for [i (range fields)]
      ^{:key i} [m2-field i])]
   [:button.submit {:type "submit" :disabled true} "Submit"]])

(defn m2-field-ratom
  [i]
  (let [c (r/cursor ratom-cells [i])]
    (fn [i]
      [:div.field
       [:label.lbl {:for (str "f" i)} (str "Field " i)]
       [:input.inp {:id        (str "f" i)
                    :name      (str "f" i)
                    :type      "text"
                    :value     (field-value i @c)
                    :read-only true}]
       [:p.err (field-error i)]])))

(defn m2-ratom
  [fields]
  [:form.p0form
   [:fieldset.fields
    (for [i (range fields)]
      ^{:key i} [m2-field-ratom i])]
   [:button.submit {:type "submit" :disabled true} "Submit"]])

(defn- m2-field-el [i v]
  (el "div" #js {:key i :className "field"}
      #js [(el "label" #js {:key "l" :className "lbl" :htmlFor (str "f" i)}
               (str "Field " i))
           (el "input" #js {:key       "i"
                            :className "inp"
                            :id        (str "f" i)
                            :name      (str "f" i)
                            :type      "text"
                            :value     (field-value i v)
                            :readOnly  true})
           (el "p" #js {:key "p" :className "err"} (field-error i))]))

(defn m2-floor
  [cells]
  (el "form" #js {:className "p0form"}
      #js [(el "fieldset" #js {:key "f" :className "fields"}
               (into-array (map-indexed m2-field-el cells)))
           (el "button" #js {:key "b" :className "submit" :type "submit" :disabled true}
               "Submit")]))

;; ---------------------------------------------------------------------------
;; The root the subs arm renders
;; ---------------------------------------------------------------------------

(defn subs-root
  "`rf/frame-provider` is not ceremony added for the bench — it is how a
  re-frame2 Reagent application supplies the frame its `reg-view`
  boundaries resolve `subscribe` against, and leaving it out would
  measure a page that cannot render."
  [view arg]
  [rf/frame-provider {:frame subs-frame} [view arg]])
