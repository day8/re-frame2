(ns re-frame.bench.hicasso.hd8-witnesses
  "HD-008's arms — the composed donor arm and everything it is measured
  against, all declaring the SAME two pages (rf2-2rtt6.7).

  This is the EP-0038 stop-gate's instrument. HD-008 asks whether the
  Hicasso hypothesis survives when it is assembled out of parts that are
  already in this repository, BEFORE any API is designed: reagent-slim's
  `:f>` function-component path and its runtime hiccup interpreter for
  markup, the existing UIx `use-subscribe` spine for reactivity. Two
  rungs, because the two halves of the claim have to be priced apart:

    RUNG 1 — markup + reactivity. `:f>` boundaries, runtime hiccup,
             `use-subscribe` with the frame PINNED as a literal. ONE hook
             per boundary. No shell.
    RUNG 2 — plus the product shell: ONE frame-context hook per boundary
             (the substrate's single internal context, HD-020(a)) and
             NATIVE EVENT-VECTOR LOWERING — the author writes
             `:on-click [:hd8/touch i]` and the codec, not the author,
             turns it into a dispatching closure.

  And, since `rf2-2rtt6.29`, a FOURTH DONOR — rung 1 with ONE token
  changed:

    RUNG 1-FH — rung 1 exactly, except that the markup inside each
             boundary is built by **Freehand's already-shipped codec**
             (`re-frame.freehand.react/element`) instead of by
             reagent-slim's `as-element`. Same `:f>` boundary, same
             `use-subscribe` spine, same pinned frame, same author
             closure, same page.

  The rung-2 lowering is a MINIMAL DISPOSABLE SLICE and is deliberately
  not the shared front half — rf2-2rtt6.8 owns that. It lowers
  vector-valued `on-*` props and does nothing else, because that is the
  whole of what rung 2 is pricing.

  ## Nothing in a donor's `src/` is touched

  HD-008 says *composed*, and composition is the claim under test: every
  donor part is consumed at its published surface —
  `reagent2.impl.template/as-element`, `re-frame.adapter.uix/use-subscribe`,
  `re-frame.adapter.uix/use-current-frame`. Had the hypothesis needed a
  donor edit to become measurable, it would already have failed.

  ## The hook ladder, and why it is the readable variable

  THREE dispatch mechanisms live here, not one, and this docstring used to
  claim otherwise — *\"every arm below resolves its dispatch fn the SAME way
  — one map lookup keyed by frame\"*. What the source does:

      arm            hooks  markup                     handler        dispatch
      :floor           0    hand createElement         inert          none — one hoisted no-op, shared
      :reagent         0    Reagent hiccup (reg-view)  author closure LEXICAL, injected by reg-view
      :reagent-slim    0    slim hiccup (reg-view)     author closure LEXICAL, injected by reg-view
      :uix             2    `$` macro (compile-time)   author closure [[dispatch-for]] — deref + get, IN RENDER
      :donor-r1        1    slim hiccup + `:f>`        author closure [[dispatch-for]]
      :donor-r2        2    slim hiccup + `:f>`        CODEC-LOWERED  [[dispatch-for]], then [[lower-events]]
      :donor-fh        1    FREEHAND hiccup + `:f>`    author closure [[dispatch-for]]

  [[prime-frame!]] does run outside every measured window, so no arm pays
  to CONSTRUCT a dispatch fn during a render — that part of the old claim
  holds and is the point of the levelling. But only the three React-spine
  arms read [[frame-dispatch]], and they read it INSIDE each boundary's
  render, 300 times a mount; the two Reagent paths never touch it, because
  `reg-view` hands them a `dispatch` already bound to the frame from
  context, which is what a Reagent application contains; and the floor
  resolves nothing at all.

  What the old sentence was defending is still true and still matters: no
  arm mints a fresh OPS MAP per render, every mechanism above is one
  indirection or fewer, and the frontier comparator is never strawmanned.
  And the residual runs in the safe direction — the deref-plus-`get` is
  paid by `:uix` and both donor rungs and NOT by the Reagent paths, so it
  cannot have flattered the arms HD-008 is arguing for.

  What is left varying is the thing under test. `donor-r2 / donor-r1` is
  the product shell's price and nothing else's; `donor-r2 / uix` holds the
  hooks and the dispatch mechanism fixed — both go through
  [[dispatch-for]] — and varies the CODEC; `donor-r* / reagent*` is
  HD-008's ship comparison, and is the one pair the mechanisms differ
  across. `donor-fh / donor-r1` is the tightest pair in the file: one
  hook each, the same hook, the same pinned frame, the same author
  closure, the same `:f>` boundary, the same outer skeleton — and TWO
  DIFFERENT RUNTIME HICCUP CODECS building the markup inside the 300
  boundaries.

  ## Why every arm is written out longhand

  The comparison is only worth taking if each arm is what a competent
  author of THAT substrate would write. A shared generator producing five
  dialects would measure the generator. So the arms sit side by side
  here, and the canonical-DOM parity gate in
  [[re-frame.bench.hicasso.hd8-rows]] is what proves they still build one
  page — attribute names sorted, compared before any clock is read.

  ## The two pages

  `M` — 300 rows, each row a boundary reading its own subscription and
  carrying its own handler. Markup-dominant; `3 + 3N` elements.

  `U` — a 300-cell grid, same shape per cell. Reactivity-dominant, and
  the page the narrow and bulk write rows drive; `1 + N` elements.

  Both pages read re-frame2 subscriptions on EVERY arm — the bar's
  like-for-like condition (HD-012). No arm reads a bare ratom; validation.md
  is explicit that a bare ratom is a labelled lower bound and never a fair
  comparison.

  Normative owner: `docs/design/hicasso/decisions.md` HD-008; the standard
  bead is `rf2-2rtt6.1`."
  (:require ["react" :as react]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uixa]
            [re-frame.core :as rf]
            [re-frame.freehand.react :as freehand-react]
            [reagent2.impl.template :as slim-template]
            [uix.core :refer [$ defui]])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ===========================================================================
;; The shared data layer — one registry, read by every arm
;; ===========================================================================

(def rows-n
  "The M page's boundary count. 300 is the witness set's stated bulk size
  (validation.md §Witness set)."
  300)

(def cells-n 300)

(rf/reg-sub :hd8/row (fn [db [_ i]] (get-in db [:rows i])))
(rf/reg-sub :hd8/cell (fn [db [_ i]] (get-in db [:cells i])))

(rf/reg-event :hd8/seed
  (fn [_ [_ n]]
    {:db {:rows  (mapv #(str "r" %) (range n))
          :cells (vec (repeat n "0"))}}))

;; `:hd8/touch` is what every arm's handler dispatches. It is never fired
;; inside a measured window: the clock rows price the CONSTRUCTION of a
;; handler, which is what event-vector lowering costs, not the dispatch it
;; would perform. It IS fired once per arm outside every window by the
;; lowering correctness check in hd8-rows, so that rung 2 cannot be
;; measuring a lowering that does not work.
;; The write rows drive these rather than replacing `app-db` wholesale. An
;; application writes through events, so an event is what the write leg
;; should contain — and it is the same event, through the same queue, for
;; every reactive arm, so it cancels out of every arm-to-arm ratio.
(rf/reg-event :hd8/set
  (fn [{:keys [db]} [_ i v]] {:db (assoc-in db [:cells i] v)}))

(rf/reg-event :hd8/set-all
  (fn [{:keys [db]} [_ v]] {:db (assoc db :cells (vec (repeat cells-n v)))}))

(rf/reg-event :hd8/touch
  (fn [{:keys [db]} [_ i]]
    {:db (assoc-in db [:cells i] "T")}))

(defn m-elements
  "Three for the skeleton — section, heading, list — then three per row:
  the row, its label and its value. Written arithmetic, so the parity gate
  is against an expectation rather than against whatever mounted."
  [n]
  (+ 3 (* 3 n)))

(defn u-elements [n] (+ 1 n))

;; ===========================================================================
;; The dispatch lookup the React-spine arms share
;; ===========================================================================

(defonce ^:private frame-dispatch
  ;; frame keyword -> the frame-locked `dispatch` fn. Primed by
  ;; [[prime-frame!]] at arm construction, outside every measured window,
  ;; so no arm pays to CONSTRUCT a dispatch fn during a render.
  ;;
  ;; It does NOT make every arm pay the same lookup, and the comment here
  ;; used to say it did. Its readers are `:uix`, `:donor-r1` and
  ;; `:donor-r2`, which call [[dispatch-for]] inside each boundary's
  ;; render; the two Reagent arms take `reg-view`'s lexical `dispatch` and
  ;; never come here, and the floor's handler is [[inert]]. See the ns
  ;; docstring for the three mechanisms and which way the residual runs.
  (atom {}))

(defn prime-frame! [frame-kw]
  (swap! frame-dispatch assoc frame-kw (:dispatch (rf/capture-frame frame-kw)))
  nil)

(defn release-frame! [frame-kw]
  (swap! frame-dispatch dissoc frame-kw)
  nil)

(defn dispatch-for [frame-kw] (get @frame-dispatch frame-kw))

;; ===========================================================================
;; ARM: the React floor — the calibrator, no substrate at all
;; ===========================================================================
;;
;; No boundary, no subscription, no frame, no handler indirection: the
;; irreducible cost of asking React to build these two pages. It is the
;; only arm that could not be shipped, which is exactly why every ratio is
;; taken against it IN THE SAME ROUND — this box drifts, and an absolute
;; millisecond is not comparable between rounds.
;;
;; The floor reads its values from a plain snapshot handed in by the
;; caller, so it contains NO reactivity: `arm / floor` on the M page is
;; markup-plus-reactivity over markup alone. That is stated rather than
;; hidden. The HD-008 numbers are the ARM-TO-ARM ratios, which share this
;; denominator and cancel it.

(defn- fel
  ([tag props] (react/createElement tag props))
  ([tag props children] (react/createElement tag props children)))

;; ONE inert handler value, hoisted: the floor is the calibrator and must
;; not be billed for a closure per element that no rival's codec mints
;; per element either (every other arm's handler closure is minted by the
;; arm itself and is part of what that arm costs).
(def ^:private inert (fn [_] nil))

;; `subvec` rather than `take`: the floor must honour the witness's `n` —
;; the parity gate caught it building 300 rows for a 6-row witness — but it
;; must not be billed for a lazy sequence per mount that no rival pays.
;; `subvec` is constant time and allocates one view.
(defn- ^:private window-of [v n]
  (if (and (some? v) (< n (count v))) (subvec v 0 n) v))

(defn floor-m [{:keys [rows n]}]
  (fel "section" #js {:className "panel" :aria-label "hd8"}
       #js [(fel "h1" #js {:key "h" :className "title"} "Rows")
            (fel "ul" #js {:key "l" :className "rows" :role "list"}
                 (into-array
                   (map-indexed
                     (fn [i v]
                       (fel "li" #js {:key i :className "row" :data-index i
                                      :onClick inert}
                            #js [(fel "span" #js {:key "s" :className "label"} "row ")
                                 (fel "em" #js {:key "e" :className "n"} v)]))
                     (window-of rows n))))]))

(defn floor-u [{:keys [cells n]}]
  (fel "div" #js {:className "ugrid"}
       (into-array
         (map-indexed
           (fn [i v]
             (fel "span" #js {:key i :className "cell" :data-i i :onClick inert} v))
           (window-of cells n)))))

;; ===========================================================================
;; ARM: Reagent — stock and slim, one source, on re-frame2 subscriptions
;; ===========================================================================
;;
;; `reg-view` rather than a plain `defn`, and that is not a stylistic
;; choice: only a reg-view-registered component carries the `:contextType`
;; wiring Reagent needs to read the frame from context, and it is the form
;; that injects the lexical `subscribe` / `dispatch` bound to the
;; surrounding frame. A plain Reagent fn calling `rf/subscribe` under a
;; provider resolves no frame and the render raises. This is the shape the
;; adapters' own testbeds use, so it is what a Reagent application
;; contains.
;;
;; ONE source, TWO arms: whether these render through stock Reagent or
;; through reagent-slim is decided by the mount door and by which adapter
;; is installed (hd8-rows), never by the markup. That is what makes them
;; "the identical sub graph" the validation plan asks both Reagent paths
;; to run on.

(reg-view rg-m-row [i]
  [:li.row {:data-index i :on-click (fn [_] (dispatch [:hd8/touch i]))}
   [:span.label "row "]
   [:em.n @(subscribe [:hd8/row i])]])

(reg-view rg-m [n]
  [:section.panel {:aria-label "hd8"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range n)] ^{:key i} [rg-m-row i])]])

(reg-view rg-u-cell [i]
  [:span.cell {:data-i i :on-click (fn [_] (dispatch [:hd8/touch i]))}
   @(subscribe [:hd8/cell i])])

(reg-view rg-u [n]
  [:div.ugrid
   (for [i (range n)] ^{:key i} [rg-u-cell i])])

;; ===========================================================================
;; ARM: direct UIx — the efficiency frontier (HD-012)
;; ===========================================================================
;;
;; `$` is a MACRO expanding to `react/createElement` at compile time, so
;; this arm pays no runtime markup walk at all. That is why it is the
;; frontier comparator and why the red-zone rule (rf2-2rtt6.1, delegated
;; ruling 1) derives its thresholds from it.
;;
;; TWO hooks per boundary — the frame-context read and the subscription —
;; which is exactly HD-020's budget. `use-current-frame` is the NARROW raw
;; `useContext` read; feeding it to the two-arity `use-subscribe` is the
;; same hook count as the ambient one-arity form, one indirection less,
;; and — unlike the ambient chain — independent of which adapter happens
;; to be installed, which is what lets this arm ride all three adapter
;; runs unchanged.

(defui ux-m-row [{:keys [i]}]
  (let [frame (uixa/use-current-frame)
        v     (uixa/use-subscribe frame [:hd8/row i])
        d     (dispatch-for frame)]
    ($ :li.row {:data-index i :on-click (fn [_] (d [:hd8/touch i]))}
       ($ :span.label "row ")
       ($ :em.n v))))

(defui ux-m [{:keys [n]}]
  ($ :section.panel {:aria-label "hd8"}
     ($ :h1.title "Rows")
     ($ :ul.rows {:role "list"}
        (for [i (range n)]
          ($ ux-m-row {:key i :i i})))))

(defui ux-u-cell [{:keys [i]}]
  (let [frame (uixa/use-current-frame)
        v     (uixa/use-subscribe frame [:hd8/cell i])
        d     (dispatch-for frame)]
    ($ :span.cell {:data-i i :on-click (fn [_] (d [:hd8/touch i]))} v)))

(defui ux-u [{:keys [n]}]
  ($ :div.ugrid
     (for [i (range n)]
       ($ ux-u-cell {:key i :i i}))))

;; ===========================================================================
;; ARM: the composed donor arm, RUNG 1 — markup + reactivity
;; ===========================================================================
;;
;; reagent-slim's `:f>` head renders a plain CLJS fn as a REAL React
;; function component — cached on the fn, so the type identity is stable
;; across renders — which is what makes hooks legal in the body. The body
;; returns hiccup, interpreted at runtime by `as-element`. The
;; subscription is the UIx spine's `use-subscribe`, at its published
;; two-arity form with the frame PINNED as a literal: no context read, no
;; frame resolution, ONE hook.
;;
;; The pinning is what makes rung 1 rung 1. No application can ship a
;; frame keyword baked into every leaf; rung 2 buys the thing that removes
;; it, and the gap between the rungs is the price of that purchase.

(defn r1-m-row [i frame]
  (let [v (uixa/use-subscribe frame [:hd8/row i])
        d (dispatch-for frame)]
    [:li.row {:data-index i :on-click (fn [_] (d [:hd8/touch i]))}
     [:span.label "row "]
     [:em.n v]]))

(defn r1-m [n frame]
  [:section.panel {:aria-label "hd8"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range n)] ^{:key i} [:f> r1-m-row i frame])]])

(defn r1-u-cell [i frame]
  (let [v (uixa/use-subscribe frame [:hd8/cell i])
        d (dispatch-for frame)]
    [:span.cell {:data-i i :on-click (fn [_] (d [:hd8/touch i]))} v]))

(defn r1-u [n frame]
  [:div.ugrid
   (for [i (range n)] ^{:key i} [:f> r1-u-cell i frame])])

;; ===========================================================================
;; ARM: the composed donor arm, RUNG 2 — plus the product shell
;; ===========================================================================
;;
;; Two additions over rung 1 and nothing else:
;;
;;   (a) ONE FRAME-CONTEXT HOOK per boundary — the substrate's single
;;       internal context, read once (HD-020(a)). The literal frame
;;       keyword disappears from the source, which is the whole point: the
;;       author writes a component, not a wiring diagram.
;;   (b) NATIVE EVENT-VECTOR LOWERING — the author writes
;;       `:on-click [:hd8/touch i]`, a plain data event vector, and the
;;       codec turns it into a dispatching closure while building the
;;       element.
;;
;; The `:f>` path, the runtime hiccup and the `use-subscribe` spine are
;; rung 1's, unchanged.

(def ^:private on-prefix "on-")

(defn- event-prop?
  "Is `k` an event prop? A keyword whose name starts `on-`. Deliberately
  the cheapest test that is correct for hiccup's convention: the slice is
  minimal by charter, and a richer classification would be measuring
  rf2-2rtt6.8's shared front half rather than this rung."
  [k]
  (and (keyword? k) (str/starts-with? (name k) on-prefix)))

(defn lower-events
  "THE LOWERING SLICE. Walk `props` once; every vector-valued `on-*` entry
  becomes a closure dispatching that vector through `dispatch`. Everything
  else passes through untouched.

  Disposable by construction and scoped to this bead: it does not parse
  tags, it does not cache, it does not descend into children, and it lives
  in the bench lane. It exists only so the clock can say what the shell
  costs."
  [dispatch props]
  (reduce-kv (fn [m k v]
               (assoc m k (if (and (vector? v) (event-prop? k))
                            (fn [_e] (dispatch v))
                            v)))
             {}
             props))

(defn r2-m-row [i]
  (let [frame (uixa/use-current-frame)
        v     (uixa/use-subscribe frame [:hd8/row i])
        d     (dispatch-for frame)]
    [:li.row (lower-events d {:data-index i :on-click [:hd8/touch i]})
     [:span.label "row "]
     [:em.n v]]))

(defn r2-m [n]
  [:section.panel {:aria-label "hd8"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range n)] ^{:key i} [:f> r2-m-row i])]])

(defn r2-u-cell [i]
  (let [frame (uixa/use-current-frame)
        v     (uixa/use-subscribe frame [:hd8/cell i])
        d     (dispatch-for frame)]
    [:span.cell (lower-events d {:data-i i :on-click [:hd8/touch i]}) v]))

(defn r2-u [n]
  [:div.ugrid
   (for [i (range n)] ^{:key i} [:f> r2-u-cell i])])

;; ===========================================================================
;; The donor codecs' public doors — one call each, so the crossings are visible
;; ===========================================================================

(defn slim-element
  "Interpret donor hiccup into a React element through reagent-slim's
  published `as-element`. The single point at which the composed arm
  touches the markup donor, kept as one call so the crossing is visible
  rather than scattered."
  [hiccup]
  (slim-template/as-element hiccup))

(defn fh-element
  "Interpret donor hiccup into a React element through FREEHAND's published
  `re-frame.freehand.react/element` — the second runtime hiccup codec this
  repository already ships, and the whole of what the fourth arm varies.

  Consumed at its published surface, exactly as the slim donor is: nothing
  under `implementation/freehand/src` is touched, and the donor freeze
  (`rf2-2rtt6.29` fences) stands.

  `element` is the door for a form with NO declared Freehand boundary above
  it, so the walk threads no candidate and an `:on-*` carrying a bare
  function is attached as itself (`re-frame.freehand.events/unsited`) —
  which is precisely what `as-element` does with the same handler, and is
  what makes the two arms comparable. A Freehand APPLICATION renders inside
  a declared boundary and would additionally record an event SITE per
  handler, so this arm prices Freehand-the-CODEC exactly and
  Freehand-the-SUBSTRATE from below. That is stated on the row rather than
  left for a reader to discover."
  [hiccup]
  (freehand-react/element hiccup))

(defn codecs-differ?
  "Are the two codec doors demonstrably DIFFERENT implementations?

  `donor-fh / donor-r1` is the one figure in this instrument that would be
  unreadable if the answer were no — a ratio of 1.0 could then mean *the
  codecs cost the same* or *the arm ran one codec twice*, and nothing in a
  clock reading tells those apart. The parity gate cannot answer it either:
  its whole point is that the arms agree.

  So the difference is DEMONSTRATED, on a form the two codecs are known to
  treat differently and that no arm on this page uses: `^{:key …}` metadata
  on a hiccup vector. reagent-slim honours it (`get-react-key` reads the
  vector's meta); Freehand REFUSES it by name
  (`re-frame.freehand.node/refuse-metadata-key!` — one spelling for the key,
  in the attribute map, so the JVM structural tree cannot drop what React
  received). One accepts, one throws, and the check is fatal at boot if that
  ever stops being true — the same standing as `parity-can-fail?`."
  []
  (let [fh-refused? (try (fh-element ^{:key 1} [:li.row]) false
                         (catch :default _ true))
        slim-took?  (try (some? (slim-element ^{:key 1} [:li.row]))
                         (catch :default _ false))]
    {:ok?         (and fh-refused? slim-took?)
     :fh-refused? fh-refused?
     :slim-took?  slim-took?
     :probe       "^{:key 1} [:li.row] — slim honours vector-meta keys, Freehand refuses them"}))

;; ===========================================================================
;; ARM: the FOURTH DONOR — rung 1 with the CODEC swapped (rf2-2rtt6.29)
;; ===========================================================================
;;
;; Written below the doors rather than beside rung 1 because it is the one
;; arm on this page that names its codec inside its own body: `fh-element`
;; has to exist before `fh-m-row` closes over it, and a `declare` to buy
;; back the reading order would hide the very call the arm is about.
;;
;; HD-008's verdict is that "the residual mount deficit is the hiccup
;; interpreter rather than the spine". The arm it measured was
;; reagent-slim's interpreter, and `donor-r1 / reagent-slim` came back
;; 1.000–1.120 — INDISTINGUISHABLE from reagent-slim itself. So what the
;; row established is that *reagent-slim's* interpreter is the deficit.
;; This repository ships a SECOND runtime hiccup codec that was never
;; entered in that comparison, and rf2-2rtt6.29 enters it.
;;
;; WHAT IS HELD FIXED, AND HOW. Everything. `fh-m` and `fh-u` below are
;; `r1-m` and `r1-u` with ONE token changed — the row function — so the
;; OUTER skeleton and all 300 `:f>` boundary crossings are built by the
;; SAME `slim-element` call, through the same `expand-seq`, on the same
;; keyed seq. `fh-m-row` and `fh-u-cell` are `r1-m-row` and `r1-u-cell`
;; with their bodies wrapped in `fh-element`: the same single
;; `use-subscribe` hook at the same two-arity published form, the same
;; PINNED frame, the same `dispatch-for` lookup, the same author closure,
;; the same attribute maps, the same tags.
;;
;; So on the M page 900 of 903 elements, and on the U page 300 of 301, are
;; built by Freehand's codec in this arm and by reagent-slim's in
;; `donor-r1`. The three (M) and one (U) skeleton elements are the same
;; codec's in both arms and cancel.
;;
;; THE ONE RESIDUAL, AND ITS DIRECTION. `:f>`'s wrapper always converts
;; what the body returned through `as-element`. `r1-m-row` returns hiccup,
;; so that call does the work; `fh-m-row` returns a finished React
;; element, so that call is a pass-through — five predicate tests and a
;; return. This arm therefore pays ONE extra function call and a handful of
;; predicates per boundary that `donor-r1` does not, 300 times a mount.
;; It runs AGAINST Freehand's codec, so it cannot have flattered it.

(defn fh-m-row [i frame]
  (let [v (uixa/use-subscribe frame [:hd8/row i])
        d (dispatch-for frame)]
    (fh-element
      [:li.row {:data-index i :on-click (fn [_] (d [:hd8/touch i]))}
       [:span.label "row "]
       [:em.n v]])))

(defn fh-m [n frame]
  [:section.panel {:aria-label "hd8"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range n)] ^{:key i} [:f> fh-m-row i frame])]])

(defn fh-u-cell [i frame]
  (let [v (uixa/use-subscribe frame [:hd8/cell i])
        d (dispatch-for frame)]
    (fh-element
      [:span.cell {:data-i i :on-click (fn [_] (d [:hd8/touch i]))} v])))

(defn fh-u [n frame]
  [:div.ugrid
   (for [i (range n)] ^{:key i} [:f> fh-u-cell i frame])])
