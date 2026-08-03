(ns re-frame.bench.hicasso.clock-views
  "THE CANDIDATE'S CLOCK WITNESSES — Hicasso Arm 1 on rf2-2rtt6.2's
  witness shapes, plus the per-keystroke witness for every arm (rf2-0qj9w).

  ## Why this namespace exists

  Every clock figure the programme has published is about the DONORS.
  `p0_converge_app` builds `:reagent-subs` and `:uix-subs` segments and a
  floor; `p0_arms` builds the same three; the Hicasso candidate appears in
  neither. The candidate's only measured axes are hook count (2, at
  React's own dispatcher) and per-read retained heap. This namespace is the
  view half of closing that: the candidate rendering the SAME page the
  donors render, so a ratio between them is a substrate ratio.

  ## The M1 page is `p0-reagent-views`', element for element

  Read [[m1-cell]] beside `p0-reagent-views/m1-cell` and
  `p0-uix-views/m1-cell`: same tag sugar, same class names, same `data-i`
  attribute, same text, one `[:p0/cell i]` read per boundary. The harness
  gates canonical-DOM equality across every arm before it reads a clock,
  so this docstring is not what proves it.

  `into` rather than a lazy `for` in [[m1]], for `p0-hicasso/lad-grid`'s
  reason: the codec is eager either way, but a witness should not be the
  thing that depends on it.

  ## The per-keystroke witness, and the one place the arms differ

  [[kb-form]] is validation.md's stated per-keystroke shape: **a 4-field
  form and a 100-cell grid**. Four controlled text fields over
  `[:p0/draft i]` sit above 100 sub-reading boundaries, and a sample types
  one physical key into ONE of them. A keystroke installs a whole new
  app-db, so all 104 layer-1 subscriptions recompute and exactly one
  boundary's value changes — the narrow-localisation question, met through
  a real input event rather than through a harness write.

  ## The recompute census, because 'which boundaries re-ran' is not the
  ## question validation.md asks

  Its per-keystroke budget requires **sub-recompute localisation — which
  subs recompute, not merely which boundaries re-run** — and a DOM
  read-back cannot answer that. [[register-subs!]] therefore registers
  both of the witness's layer-1 queries behind [[tick!]], and the driver
  arms the census for one WARM-UP keystroke per arm and reads the counts
  back. Warm-up, so no measured sample carries the census's cost; a real
  keypress, so the count is of the path the row publishes.

  The counter is a nil check on a disarmed atom the rest of the time, and
  it sits inside every arm of every row this entry drives, so it is
  common-mode and cancels in a ratio. The census is a GATE rather than a
  diagnostic: a substrate arm that does not recompute exactly
  `kb-cells-n + kb-fields-n` layer-1 subs, or a floor arm that recomputes
  any at all, refuses the row.

  **The handler is a plain callback on every arm, and that is deliberate.**
  HD-012 states the bar over VIEW WORK; rf2-2rtt6.3 measured the event
  drain at 11–16% of a write on this substrate. Routing the candidate
  through `runtime/dispatch!` while the donors write `replace-app-db!`
  directly would price Hicasso's event pipeline against the donors' bare
  write and then call the difference view work. So all three substrate
  arms share one handler, [[write-draft!]] — Hicasso's behind
  `intent/callback`, HD-024's one callback form, which returns the
  function itself.

  React's `onChange` fires on the native `input` event, and Hicasso's
  `:on-change` lowers to the same prop: `intent/lower-prop` claims every
  `^on-` position and `codec/prop-name` camelCases it to `onChange`. Same
  prop, same event, same handler.

  ## The keystroke floor holds its draft in React, and it is NOT a lower
  ## bound — measured

  [[kb-floor]] is the only arm that does not write app-db: it is the
  floor, so it has no substrate to write to, and its drafts live in a
  `useState`. Everywhere else in this lane the floor is the cheapest arm
  on the page, and the first instinct is to label this one a lower bound
  too. **Run 1 of `clock_run.cjs` measured the opposite**: all three
  substrate arms read BELOW it (0.87–0.92× floor), because a `useState`
  write re-renders the whole tree top-down while a subscription write
  re-renders only the boundary whose value moved.

  So it is a CALIBRATOR and never a bound in either direction — the same
  page, the same commit, no reactive graph — and a substrate arm reading
  under 1.0× here is localisation showing up on the clock rather than an
  anomaly.

  Owner: rf2-2rtt6.1 (standard); these witnesses rf2-0qj9w."
  (:require ["react" :as react]
            [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.bench.hicasso.p0-uix-views :as ux]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [uix.core :refer [$ defui]])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]
                   [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; The keystroke witness's shape, as arithmetic
;; ---------------------------------------------------------------------------

(def kb-cells-n
  "100 boundaries — validation.md's per-keystroke budget names `a 4-field
  form and a 100-cell grid`, and this is the grid half."
  100)

(def kb-fields-n
  "4 fields — the FORM half of the same budget line. It was one field
  until rf2-0qj9w; a single field cannot exercise the property the row is
  about, which is that a keystroke into one field moves exactly one
  field's value while every layer-1 subscription on the page recomputes."
  4)

(defn kb-elements
  "One for the form, one per field, one for the list, then three per row.
  Arithmetic, so the mount gate is against a WRITTEN expectation rather
  than against whatever the mount happened to produce."
  [n]
  (+ 2 kb-fields-n (* 3 n)))

;; ---------------------------------------------------------------------------
;; The recompute census
;; ---------------------------------------------------------------------------

(def ^:private census
  "`nil` when disarmed; a JS object of query-name -> recompute count when
  armed. A plain object rather than a map in an atom: [[tick!]] runs
  inside a subscription computation and must not allocate."
  (atom nil))

(defn- tick!
  "Count one recompute of `q`, if the census is armed."
  [q]
  (when-some [c @census]
    (unchecked-set c q (inc (or (unchecked-get c q) 0))))
  nil)

(defn census-start!
  "Arm the census. Called from the driver, in a WARM-UP sample."
  []
  (reset! census #js {})
  nil)

(defn census-take!
  "Disarm and answer the counts."
  []
  (let [c @census]
    (reset! census nil)
    (or c #js {})))

(defn register-subs!
  "The witness's TWO layer-1 queries, both behind the census.

  `:p0/cell` is registered here rather than left to
  `p0-reagent-views/register!` because the census has to see it: the grid
  is 100 of the 104 subscriptions a keystroke recomputes, and a census
  that could only see the fields would answer the easy quarter of
  validation.md's question. It delegates to `v/cell-value`, so there is
  still exactly one body for that computation.

  `:p0/draft` is INDEXED and keeps a query id of its own rather than
  taking a reserved cell index: an index would make a field's read
  indistinguishable from a grid boundary's in every census this lane
  takes — including this one.

  A function as well as a load-time call: the run installs and destroys
  an adapter once per segment and re-registers on every segment entry,
  and a re-register overwrites with the identical handler."
  []
  (rf/reg-sub :p0/cell  (fn [db [_ i]] (tick! "p0/cell")  (v/cell-value db i)))
  (rf/reg-sub :p0/draft (fn [db [_ i]] (tick! "p0/draft") (get-in db [:draft i] "")))
  nil)

(register-subs!)

(defn seed
  "The keystroke witness's app-db — the grid's cells plus empty drafts."
  [n]
  (assoc (v/seed-cells n 0) :draft (vec (repeat kb-fields-n ""))))

(defn write-draft!
  "THE ONE HANDLER, shared by all three substrate arms.

  `replace-app-db!` and not `dispatch-sync`, for the reason
  `p0-converge-app/subs-bulk-arm` gives: the bar is stated over view work
  and the event drain is priced on its own row. Installing a whole new
  app-db is what makes every one of the witness's 104 layer-1
  subscriptions recompute while exactly one value changes.

  **The drafts CARRY FORWARD, the cells are rebuilt** — `write-cells!`'s
  rule in `clock_app`, for its reason. The grid is re-installed from
  [[seed]] so that all 100 cell subscriptions recompute; the four field
  values are read out of the standing app-db and only field `i` is
  replaced, so the other three keep what earlier samples typed. Resetting
  them would move four values per keystroke instead of one, and the row
  would stop measuring what its name says. The driver reads ALL FOUR
  fields back, so a handler that smeared into a neighbour is a refusal
  rather than a footnote."
  [i e]
  (let [s      (.. e -target -value)
        drafts (or (:draft (rf/app-db-value v/subs-frame))
                   (vec (repeat kb-fields-n "")))]
    (frame/replace-app-db! v/subs-frame
                           (assoc (v/seed-cells kb-cells-n 0)
                                  :draft (assoc drafts i s))))
  nil)

;; ---------------------------------------------------------------------------
;; HICASSO — the candidate
;; ---------------------------------------------------------------------------

(defview m1-cell
  "One Hicasso boundary, one `sub` read. `p0-reagent-views/m1-cell`'s
  page, through the ambient collector."
  [{:keys [i]}]
  (let [v (sub [:p0/cell i])]
    [:li.row
     [:span.lbl "cell "]
     [:span.cell {:data-i i} (str v)]]))

(defn m1
  "The list, as a PLAIN FUNCTION returning hiccup rather than a `defview`
  — `p0-hicasso/lad-grid`'s rule, for its reason: a `defview` here would
  mint one more boundary per root that reads nothing, and every boundary
  census in this lane would answer `B + roots`. `mount/root!` takes hiccup
  and the codec walks it."
  [n]
  (into [:ul.grid {:role "list"}]
        (map (fn [i] [m1-cell {:key i :i i}]))
        (range n)))

(defview kb-field
  "One controlled field. `:value` is a subscription read and `:on-change`
  is [[write-draft!]] behind HD-024's one callback form."
  [{:keys [i]}]
  [:input.draft {:type      "text"
                 :data-i    (str "draft-" i)
                 :value     (sub [:p0/draft i])
                 :on-change (intent/callback (fn [e] (write-draft! i e)))}])

(defn kb-form
  "The keystroke page: four fields above the grid."
  [n]
  (into [:form.kbform]
        (conj (mapv (fn [i] [kb-field {:key i :i i}]) (range kb-fields-n))
              (m1 n))))

;; ---------------------------------------------------------------------------
;; REAGENT — the denominator
;; ---------------------------------------------------------------------------

(reg-view ^{:rf/id :p0/kb-field} r-kb-field
  [i]
  [:input.draft {:type      "text"
                 :data-i    (str "draft-" i)
                 :value     @(rf/subscribe [:p0/draft i])
                 :on-change (fn [e] (write-draft! i e))}])

(reg-view ^{:rf/id :p0/kb-form} r-kb-form
  [n]
  (into [:form.kbform]
        (conj (mapv (fn [i] ^{:key i} [r-kb-field i]) (range kb-fields-n))
              [v/m1-subs n])))

(defn r-kb-root [n]
  [rf/frame-provider {:frame v/subs-frame} [r-kb-form n]])

;; ---------------------------------------------------------------------------
;; UIx — the co-instrumented comparator
;; ---------------------------------------------------------------------------

(defui u-kb-field [{:keys [i]}]
  ($ :input.draft {:type      "text"
                   :data-i    (str "draft-" i)
                   :value     (uixa/use-subscribe [:p0/draft i])
                   :on-change (fn [e] (write-draft! i e))}))

(defui u-kb-form [{:keys [n]}]
  (apply $ :form.kbform
         (conj (mapv (fn [i] ($ u-kb-field {:key i :i i})) (range kb-fields-n))
               ($ ux/m1 {:n n}))))

(defn u-kb-root [n]
  ($ uixa/frame-provider {:frame v/subs-frame}
     ($ u-kb-form {:n n})))

;; ---------------------------------------------------------------------------
;; The floor — hand-built, no substrate, a CALIBRATOR
;; ---------------------------------------------------------------------------

(defn- spin!
  "Burn `ms` of main-thread time inside the handler. The positive
  control's whole mechanism, and it is a busy loop rather than a sleep
  because a sleep is not work and the counters this run reads count work.

  `js/Date.now` and not `performance.now`: the loop needs a coarse clock
  it can call millions of times, and the quantity it is producing is
  50 ms rather than a measurement."
  [ms]
  (when (pos? ms)
    (let [end (+ (js/Date.now) ms)]
      (while (< (js/Date.now) end) nil)))
  nil)

(def ^:private kb-floor
  "The same page, hand-built with `react/createElement`, its drafts in a
  `useState`. No subscription, no frame, no reactive graph — the
  irreducible cost of asking React to keep these fields controlled and
  this list on screen.

  **A CALIBRATOR and never a bound in either direction** (rf2-0qj9w). It
  does not write app-db, so it recomputes no subscriptions at all — and
  the instinct that therefore makes it a lower bound is the one run 1
  refuted: a `useState` write re-renders the whole tree top-down while a
  subscription write re-renders one boundary, and all three substrate
  arms read BELOW it. An arm under 1.0x here is localisation showing up
  on the clock, not an anomaly.

  `busy` is the positive control's knob. At 0 this is the floor; at 50 it
  is `:ctl-50ms`, which spends fifty milliseconds inside the handler so
  the instrument has a change its own arithmetic predicts.

  ONE `useState` over a four-slot array rather than four hooks, so the
  floor's hook count does not move with the field count and the arm stays
  the same shape it was at one field."
  (fn kb-floor-fn [^js props]
    (let [n      (unchecked-get props "n")
          busy   (unchecked-get props "busy")
          state  (react/useState (fn [] (into-array (repeat kb-fields-n ""))))
          drafts (aget state 0)
          put!   (aget state 1)]
      ;; Children as VARARGS rather than as an array: an array child needs
      ;; a `key` per entry and `v/m1-floor` — which is the M1 floor
      ;; unchanged, so that the two floors are one page — does not carry
      ;; one. Varargs is React's own escape from that and costs the page
      ;; nothing.
      (apply react/createElement
             "form" #js {:className "kbform"}
             (conj
               (mapv (fn [i]
                       (react/createElement
                         "input" #js {:className "draft"
                                      :type      "text"
                                      :data-i    (str "draft-" i)
                                      :value     (aget drafts i)
                                      :onChange  (fn [e]
                                                   (let [s (.. e -target -value)]
                                                     (spin! busy)
                                                     (put! (fn [prev]
                                                             (let [nx (.slice prev)]
                                                               (aset nx i s)
                                                               nx)))))}))
                     (range kb-fields-n))
               (v/m1-floor (vec (repeat n 0))))))))

(defn kb-floor-element
  "The floor's element, at `n` boundaries and `busy` milliseconds of
  deliberate handler cost."
  [n busy]
  (react/createElement kb-floor #js {:n n :busy busy}))
