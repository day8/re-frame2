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

  [[kb-form]] is a controlled text field over `[:p0/draft]` sitting above
  100 sub-reading boundaries. A keystroke installs a whole new app-db, so
  all 101 layer-1 subscriptions recompute and exactly one boundary's value
  changes — the narrow-localisation question, met through a real input
  event rather than through a harness write.

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
  floor, so it has no substrate to write to, and its draft lives in a
  `useState`. Everywhere else in this lane the floor is the cheapest arm
  on the page, and the first instinct is to label this one a lower bound
  too. **Run 1 of `clock_run.cjs` measured the opposite**: all three
  substrate arms read BELOW it (0.87–0.92× floor), because a `useState`
  write re-renders the whole 101-element tree top-down while a
  subscription write re-renders only the boundary whose value moved.

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

(defn kb-elements
  "One for the form, one for the input, one for the list, then three per
  row. Arithmetic, so the mount gate is against a WRITTEN expectation
  rather than against whatever the mount happened to produce."
  [n]
  (+ 3 (* 3 n)))

(defn register-draft!
  "The draft subscription, registered beside `p0-reagent-views/register!`'s
  `:p0/cell`. A separate query id rather than a reserved cell index: an
  index would make the field's read indistinguishable from a grid
  boundary's in every census this lane takes.

  A function as well as a load-time call, for `register!`'s reason: the
  run installs and destroys an adapter once per segment and re-registers
  on every segment entry, and a re-register overwrites with the identical
  handler."
  []
  (rf/reg-sub :p0/draft (fn [db _] (get db :draft "")))
  nil)

(register-draft!)

(defn seed
  "The keystroke witness's app-db — the grid's cells plus an empty draft."
  [n]
  (assoc (v/seed-cells n 0) :draft ""))

(defn write-draft!
  "THE ONE HANDLER, shared by all three substrate arms.

  `replace-app-db!` and not `dispatch-sync`, for the reason
  `p0-converge-app/subs-bulk-arm` gives: the bar is stated over view work
  and the event drain is priced on its own row. Installing a whole new
  app-db is what makes every one of the witness's 101 layer-1
  subscriptions recompute while exactly one value changes."
  [e]
  (let [s (.. e -target -value)]
    (frame/replace-app-db! v/subs-frame (assoc (seed kb-cells-n) :draft s)))
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
  "The controlled field. `:value` is a subscription read and `:on-change`
  is [[write-draft!]] behind HD-024's one callback form."
  [_]
  [:input.draft {:type      "text"
                 :data-i    "draft"
                 :value     (sub [:p0/draft])
                 :on-change (intent/callback write-draft!)}])

(defn kb-form
  "The keystroke page: the field above the grid."
  [n]
  [:form.kbform
   [kb-field {}]
   (m1 n)])

;; ---------------------------------------------------------------------------
;; REAGENT — the denominator
;; ---------------------------------------------------------------------------

(reg-view ^{:rf/id :p0/kb-field} r-kb-field
  [_]
  [:input.draft {:type      "text"
                 :data-i    "draft"
                 :value     @(rf/subscribe [:p0/draft])
                 :on-change write-draft!}])

(reg-view ^{:rf/id :p0/kb-form} r-kb-form
  [n]
  [:form.kbform
   [r-kb-field nil]
   [v/m1-subs n]])

(defn r-kb-root [n]
  [rf/frame-provider {:frame v/subs-frame} [r-kb-form n]])

;; ---------------------------------------------------------------------------
;; UIx — the co-instrumented comparator
;; ---------------------------------------------------------------------------

(defui u-kb-field [_]
  ($ :input.draft {:type      "text"
                   :data-i    "draft"
                   :value     (uixa/use-subscribe [:p0/draft])
                   :on-change write-draft!}))

(defui u-kb-form [{:keys [n]}]
  ($ :form.kbform
     ($ u-kb-field {})
     ($ ux/m1 {:n n})))

(defn u-kb-root [n]
  ($ uixa/frame-provider {:frame v/subs-frame}
     ($ u-kb-form {:n n})))

;; ---------------------------------------------------------------------------
;; The floor — hand-built, no substrate, a LABELLED LOWER BOUND
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
  "The same page, hand-built with `react/createElement`, its draft in a
  `useState`. No subscription, no frame, no reactive graph — the
  irreducible cost of asking React to keep this field controlled and this
  list on screen.

  A LOWER BOUND and never a comparator: it does not write app-db, so it
  does not recompute 101 subscriptions per keystroke. Published as one.

  `busy` is the positive control's knob. At 0 this is the floor; at 50 it
  is `:ctl-50ms`, which spends fifty milliseconds inside the handler so
  the instrument has a change its own arithmetic predicts."
  (fn kb-floor-fn [^js props]
    (let [n     (unchecked-get props "n")
          busy  (unchecked-get props "busy")
          state (react/useState "")
          draft (aget state 0)
          put!  (aget state 1)]
      ;; Children as VARARGS rather than as an array: an array child needs
      ;; a `key` per entry and `v/m1-floor` — which is the M1 floor
      ;; unchanged, so that the two floors are one page — does not carry
      ;; one. Varargs is React's own escape from that and costs the page
      ;; nothing.
      (react/createElement
        "form" #js {:className "kbform"}
        (react/createElement
          "input" #js {:className "draft"
                       :type      "text"
                       :data-i    "draft"
                       :value     draft
                       :onChange  (fn [e]
                                    (let [s (.. e -target -value)]
                                      (spin! busy)
                                      (put! s)))})
        (v/m1-floor (vec (repeat n 0)))))))

(defn kb-floor-element
  "The floor's element, at `n` boundaries and `busy` milliseconds of
  deliberate handler cost."
  [n busy]
  (react/createElement kb-floor #js {:n n :busy busy}))
