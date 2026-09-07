(ns re-frame.story.ui.multi-substrate
  "Multi-substrate side-by-side rendering. Per `002-Runtime.md` §Substrate hooks
  + `005-SOTA-Features.md` §Multi-substrate side-by-side rendering.

  When a variant declares `:substrates #{:reagent :uix}` the
  canvas renders the variant against each substrate side-by-side. Per
  `002-Runtime.md` §Substrate hooks — substrate-portability gaps are the point — failures
  surface **inline** as a red overlay on the offending cell with the
  error message, rather than auto-skipping.

  ## Substrate registry

  Story doesn't add a new framework registry — substrate-rendering hooks
  are looked up in `substrate->render-fn` here. Story installs `:reagent`;
  hosts that load UIx, Hicasso or another authoring layer register its
  renderer with `register-substrate!`.

  A substrate here is an AUTHORING LAYER — which registered render fn
  embeds the subject — and not the adapter `rf/init!` installed. The two
  are different axes, which `re-frame.story.schemas/SubstrateSet`'s
  docstring sets out; `:hicasso` is the member that makes the difference
  visible, because a Hicasso deck's authoring layer is Hicasso's whichever
  adapter sits beneath it — Hicasso's own `re-frame.hicasso.substrate`
  (`:kind :rf.adapter/hicasso`, rf2-hvr5h), or Reagent's, or UIx's.

  ## The `:hicasso` recipe (rf2-2dbpd)

  Story ships no installer for it, for exactly the reason it ships none
  for `:uix`: the renderer's one dependency is the HOST's, and Story core
  stays free of it. Five lines at boot are the whole of it.

      (ns my.app.story-boot
        (:require [re-frame.core :as rf]
                  [re-frame.hicasso :as h]
                  [re-frame.story :as story]))

      (story/register-substrate! :hicasso
        (fn [_variant-id view-id args]
          (if-let [head (:hicasso/component (rf/handler-meta {:source :store :kind :view :id view-id}))]
            (h/as-element [head args])
            [:div (str \":component \" (pr-str view-id)
                       \" is not registered as a hicasso view\")])))

  Four facts about those lines, and each is a reason there are so few.

  **`h/defview` publishes the keyword a story names** (rf2-5qaf4). The
  declaration registers one `:view` entry under `(keyword \"<ns>\" \"<sym>\")`
  carrying the minted head at `:hicasso/component`. It is an AUTHORING-TIME
  ALIAS — debug-gated, and with NO `:handler-fn`, so `rf/view` answers nil
  for it and this render fn reads the slot instead. A story therefore names
  a Hicasso view exactly as it names a Reagent one, and `:component` stays
  a keyword everywhere.

  **Resolution is LATE, per render, and that is not incidental to the
  shape.** Re-evaluating a `defview` replaces the entry behind the same id
  with a fresh head, so a hot reload of the view's namespace reaches the
  story with no story change. A head captured once at boot would paint the
  stale one after every save.

  **The element is minted fresh and its TYPE is stable, so there is no
  cache to keep.** `h/defview` already mints ONE `React.memo` wrapper per
  head at definition time and every element made from that head rides it
  (`re-frame.hicasso.impl.codec/memoize-boundary!` + `element-type`), so a
  fresh element per pass re-renders the boundary and never remounts it.
  `h/as-component` is the wrong door here on both counts: it allocates a
  component per call — a new element type every render, which React
  responds to by unmounting the subtree — and it exists to decode
  camelCase props from a NATIVE parent, work Story does not need, since it
  already holds the kebab-keyword CLJS args map the boundary body wants.

  **No frame plumbing appears, because none is needed.** The canvas
  already wraps the subject in `[rf/frame-provider {:frame variant-id} …]`
  (`re-frame.story.ui.canvas`), whose provider is
  `re-frame.adapter.context/frame-context` — the single React context
  every React-shaped adapter reads. A Hicasso boundary spliced into that
  Reagent tree resolves the VARIANT's frame from it, with no second root,
  no second state owner and no props ABI. (`h/mount!` is likewise the
  wrong door: it makes a root, and the canvas is already inside one.)

  Hicasso stories hand-author `:argtypes`. Auto-derivation reads the
  view's `[:rf/props :schema]` `reg-view` metadata and `defview` carries
  no props-schema slot — deferred by rf2-1gy4e until someone asks for
  auto-Controls, rather than invented here.

  ### The limit that WAS here is gone, and it was never the crossing
  ### (rf2-phabt)

  This section used to record a live limit: *a Hicasso boundary crossed
  into from a Reagent parent — which is what the canvas is — paints once
  and does not re-render on a write into its own frame.* Read literally
  that said a hicasso story could render and not respond to its own
  dispatches, which would have made the substrate a demo rather than a
  place to author.

  It was an artefact of the measurement, not a defect in the bridge. The
  original comparison varied the mounting route AND the frame id at once;
  the actual cause was `re-frame.hicasso.impl.collector/acquire-cell!`
  REUSING a cell without rebuilding its attachment, so the notification
  never reached a body that had already painted correctly. Fixed in
  Hicasso, and both outward doors — `h/as-element` and a memoized
  `h/as-component` — repaint on a write today. The two rows that measured
  it are back, green, in
  `re-frame.story.ui.hicasso-substrate-dom-cljs-test`
  ([[a-write-repaints-a-crossed-boundary]] and its `as-component` pair).

  So a hicasso story renders, takes its args, resolves its variant frame,
  reads it AND responds to writes into it. Nothing here works around
  anything: there is no second reactivity path for one substrate, because
  none was ever needed.

  ## Grid layout

  Each substrate renders into its own bordered cell with a small header
  showing the substrate's name. Cells lay out in a CSS grid that
  auto-flows responsive to width. Failures render as a red banner above
  the (empty) cell body.

  ## Bundle isolation

  Inside the Story bundle. DCE under `:advanced` with `:rf.story/enabled?`
  off. Adapter `:require`s are NOT hard-required from this ns — the
  substrate registry is a runtime atom and the host app populates it
  via `register-substrate!`. Story core consequently does not pull
  UIx into the classpath."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.story.args :as rf.story.args]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.theme.typography :as rf.story.theme.typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as rf.story.theme.colors]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:grid       {:display "grid"
                :grid-template-columns "repeat(auto-fit, minmax(280px, 1fr))"
                :gap "12px"
                :padding "12px"
                :background (:bg-canvas rf.story.theme.colors/tokens)
                :flex "1"
                :overflow "auto"}
   :cell       {:background (:bg-2 rf.story.theme.colors/tokens)
                :border "1px solid #3c3c3c"
                :border-radius "4px"
                :display "flex"
                :flex-direction "column"
                :min-height "160px"
                :color (:text-primary rf.story.theme.colors/tokens)
                :font-family mono-stack
                :font-size (:caption rf.story.theme.typography/type-scale)
                :position "relative"}
   :cell-head  {:padding "6px 10px"
                :background (:bg-2 rf.story.theme.colors/tokens)
                :border-bottom "1px solid #444"
                :color (:info rf.story.theme.colors/tokens)
                :font-weight "bold"
                :font-size (:micro rf.story.theme.typography/type-scale)
                :letter-spacing "0.5px"
                :text-transform "uppercase"
                :border-radius "4px 4px 0 0"}
   :cell-body  {:padding "10px"
                :flex 1}
   :error-cell {:background (:danger-bg rf.story.theme.colors/tokens)
                :border "1px solid #be4040"
                :color (:danger rf.story.theme.colors/tokens)
                :border-radius "4px"
                :font-family mono-stack
                :font-size (:caption rf.story.theme.typography/type-scale)}
   :error-head {:padding "6px 10px"
                :background "#7a2727"
                :font-weight "bold"
                :font-size (:micro rf.story.theme.typography/type-scale)
                :letter-spacing "0.5px"
                :text-transform "uppercase"
                :border-radius "4px 4px 0 0"}
   :error-body {:padding "10px"
                :white-space "pre-wrap"
                :word-break "break-word"}})

;; ---- substrate registry --------------------------------------------------

(defonce
  ^{:doc "Substrate-id → render-fn map. Render-fn signature:
         `(fn [variant-id view-id args] hiccup-or-react-element)`.
         The fn renders the view registered under `view-id` against
         the named substrate, threading `args` into the component.

         Pre-populated with `:reagent` which uses `re-frame.core/view`
         + plain reagent. `:uix` and `:hicasso` entries plug in via
         `register-substrate!` from the host app — the `:hicasso`
         renderer is written out in this namespace's ns docstring."}
  substrate->render-fn
  (atom {}))

(defn register-substrate!
  "Register a substrate render fn under `substrate-id`. The host app
  calls this once at boot for each substrate it wants Story to render
  against. The Reagent substrate registers automatically (see
  `install-reagent-substrate!` below).

  `render-fn` takes `(variant-id view-id args)` and returns a hiccup
  vector (Reagent) or a `react/createElement`-style React element (UIx,
  and Hicasso via `h/as-element`). Story's grid renders the result inside
  a `:div` cell, and the single pane splices it into the canvas tree —
  Reagent passes a React element in child position through untouched, so
  the two return shapes are interchangeable at every call site."
  [substrate-id render-fn]
  (swap! substrate->render-fn assoc substrate-id render-fn)
  nil)

(defn unregister-substrate!
  [substrate-id]
  (swap! substrate->render-fn dissoc substrate-id)
  nil)

(defn registered-substrates
  "Return the set of registered substrate ids. Used by the canvas
  switcher to enumerate the substrates a variant declares."
  []
  (set (keys @substrate->render-fn)))

;; ---- Reagent built-in substrate -----------------------------------------

(defn- reagent-render
  "Default `:reagent` substrate render fn. Looks up the view via
  `re-frame.core/view` (the framework's late-bind view lookup) and
  renders it inside a hiccup vector."
  [_variant-id view-id eff-args]
  (let [resolved (rf/view view-id)]
    (if resolved
      [resolved eff-args]
      [:div {:style {:color (:text-secondary rf.story.theme.colors/tokens) :font-style "italic"}}
       (str ":component " (pr-str view-id) " is not registered as a view")])))

(defn install-reagent-substrate!
  "Register the default `:reagent` substrate. Idempotent. Called from
  `re-frame.story.canonical` boot."
  []
  (register-substrate! :reagent reagent-render))

(defn render-view
  "Render `view-id` under `substrate` with `eff-args`, via the registered
  substrate render fn — the clean reusable seam the `render-variant`
  host-render hook consumes. Returns the substrate's render
  result (a hiccup vector for `:reagent`); a missing substrate yields an
  inline diagnostic hiccup rather than throwing, so the render verb's
  caller always sees *something*. `(fn [variant-id view-id args] …)` is the
  substrate-render contract.

  **There are TWO registry lookups in this namespace and they are not
  redundant.** `safe-render-cell` carries its own, because the two answer
  at different levels. This fn returns a render RESULT — a fragment whose
  caller decides where it lands — so a missing substrate degrades to a
  bare inline diagnostic, shaped like the missing-VIEW one
  `reagent-render` returns above it. `safe-render-cell` returns a whole
  grid CELL, chrome included, so its miss has to be a full error cell,
  and it spends that cell's body naming the `register-substrate!` call
  the author is missing. Neither can delegate to the other without losing
  exactly that: this one would hand the host hook a red bordered cell it
  is not in a grid to justify, and that one would find its error branch
  unreachable, taking the remediation off the canvas with it.

  The split is a COVERAGE split too, which is the easier half to trip
  over. The `:uix` arms in `story/ui/render_shell_cljs_test.cljs` drive
  the canvas grid, so they reach `safe-render-cell` and never arrive
  here; this fn is covered on its own terms in
  `story_multi_substrate_cljs_test.cljs` (rf2-nfwbt). A test that
  exercises one copy settles nothing about the other."
  [substrate variant-id view-id eff-args]
  (if-let [render-fn (get @substrate->render-fn substrate)]
    (render-fn variant-id view-id eff-args)
    [:div {:style {:color (:text-secondary rf.story.theme.colors/tokens) :font-style "italic"}}
     (str "substrate :" (name substrate) " is not registered")]))

;; ---- shared decorate-and-render seam -----------------------------------
;;
;; The canvas single-pane path AND the `render-variant` host hook
;; (`re-frame.story.canonical/render-host-scope`) paint the SAME variant
;; through the SAME decorate-and-render seam, so a decorated variant looks
;; identical on the live canvas and through `render-variant`. The decorator
;; REFS both consume are the compiled plan's already-merged
;; `[:world :decorators]` (the single merge authority, spec/017 §305-306),
;; so the registered + inline paths agree too.

(def ^:private decorator-error-styles
  {:wrap   {:background (:danger-bg rf.story.theme.colors/tokens)
            :border "1px solid #be4040"
            :color (:danger rf.story.theme.colors/tokens)
            :padding "8px"
            :margin-top "8px"
            :font-family mono-stack
            :font-size (:caption rf.story.theme.typography/type-scale)
            :border-radius "3px"}
   :uncoated {:margin-top "8px"
              :padding "8px"
              :background (:bg-canvas rf.story.theme.colors/tokens)
              :border "1px dashed #555"}})

(defn safe-decorated-view
  "Wrap `view-hiccup` with the `:hiccup`-kind `hiccup-decorators`, catching
  any exception a `:wrap` fn throws so a decorator failure renders an inline
  error block (with the bare view beneath it) rather than bubbling into a
  render-tree crash that blanks the shell (`002-Runtime.md` §Substrate hooks / §Error projection — failures
  render inline rather than aborting). `effective-args` is the resolved args
  map every `:wrap` fn receives as `[body effective-args]`.

  The ONE decorate primitive both the canvas single-pane path and the
  `render-variant` host hook call, so the two agree on the decorated tree."
  [view-hiccup hiccup-decorators effective-args]
  (try
    (rf.story.decorators/apply-hiccup-decorators hiccup-decorators view-hiccup effective-args)
    (catch :default e
      [:div {:style (:wrap decorator-error-styles)}
       [:div "Decorator wrap threw — variant rendered without decorators."]
       [:div {:style {:margin-top "4px"}} (str (.-message e))]
       (when-let [ids (seq (keep :id hiccup-decorators))]
         [:div {:style {:margin-top "4px" :color (:danger rf.story.theme.colors/tokens)}}
          (str "decorators in stack: " (str/join ", " (map pr-str ids)))])
       ;; Render the variant body itself uncoated so the user still sees
       ;; *something* — the page never blanks on a decorator failure.
       [:div {:style (:uncoated decorator-error-styles)} view-hiccup]])))

(defn render-decorated-view
  "Render `view-id` under `substrate` with `eff-args` (via `render-view`),
  then wrap the result with the variant's `:hiccup` decorators resolved from
  `decorator-refs` (the compiled plan's `[:world :decorators]`). This is the
  single decorate-and-render seam: the `render-variant` host hook calls it so
  a render-variant render of a decorated variant paints the SAME tree the
  live canvas paints.

  `decorator-refs` is a vector of `[decorator-id & args]` refs (raw refs, as
  the plan carries them); only the `:hiccup`-kind decorators wrap the view —
  `:frame-setup` / `:fx-override` decorators are frame concerns applied at
  allocate time, not view-wrapping. A nil/empty `decorator-refs` is
  render-transparent (the bare view)."
  [substrate variant-id view-id eff-args decorator-refs]
  (let [view-hiccup (render-view substrate variant-id view-id eff-args)]
    (if (seq decorator-refs)
      (let [hiccup-decorators (:hiccup (rf.story.decorators/resolve-decorator-refs decorator-refs))]
        (safe-decorated-view view-hiccup hiccup-decorators eff-args))
      view-hiccup)))

;; ---- failure-tolerant cell render ---------------------------------------

(defn- safe-render-cell
  "Render `view-id` under `substrate` inside a try/catch boundary. Per
  `002-Runtime.md` §Substrate hooks a substrate failure surfaces inline rather than
  aborting the whole grid.

  The registry lookup and the missing-substrate diagnostic below are the
  CELL-level pair. `render-view` above carries the fragment-level pair
  for the `render-variant` host hook, and its docstring says why the two
  are kept apart rather than folded together. A test that drives
  `multi-substrate-grid` exercises this copy and leaves that one
  untouched.

  Returns a Reagent component."
  [variant-id substrate view-id eff-args]
  (let [render-fn (get @substrate->render-fn substrate)]
    (cond
      (nil? render-fn)
      [:div {:style (:error-cell styles)}
       [:div {:style (:error-head styles)}
        (str (name substrate))]
       [:div {:style (:error-body styles)}
        (str "substrate :" (name substrate)
             " is not registered. "
             "Call (rf.story.ui.multi-substrate/register-substrate! "
             ":" (name substrate) " render-fn) "
             "at app boot.")]]

      :else
      ;; Reagent's error boundary mechanism (r/create-class with
      ;; :component-did-catch) is the standard way to isolate render
      ;; errors. Each cell wraps in such a boundary so a throw in one
      ;; substrate doesn't blank out its neighbours.
      [(r/create-class
         {:display-name (str "rf-story-substrate-" (name substrate))
          :component-did-catch
          (fn [_this _error _info]
            ;; The error is captured in state below; this hook just
            ;; prevents the error from propagating up the React tree.
            nil)
          :get-derived-state-from-error
          (fn [error]
            #js {:error (str error)})
          :reagent-render
          (fn [variant-id substrate view-id eff-args]
            (try
              [:div {:style (:cell styles)}
               [:div {:style (:cell-head styles)} (name substrate)]
               [:div {:style (:cell-body styles)}
                (render-fn variant-id view-id eff-args)]]
              (catch :default e
                [:div {:style (:error-cell styles)}
                 [:div {:style (:error-head styles)}
                  (str (name substrate) " — render error")]
                 [:div {:style (:error-body styles)}
                  (str e)]])))})
       variant-id substrate view-id eff-args])))

;; ---- pure: substrate set resolution -------------------------------------

(defn resolve-substrate-set
  "Per `001-Authoring.md` §Registration macros: the variant's effective substrate set is
  `(or (:substrates variant-body) (:substrates story-body) #{<host>})`.
  Pure data → data; JVM-testable.

  `host-substrate` is the shell's default (typically `:reagent`)."
  [variant-body story-body host-substrate]
  (or (when (seq (:substrates variant-body)) (:substrates variant-body))
      (when (seq (:substrates story-body))   (:substrates story-body))
      #{host-substrate}))

(defn single-render-substrate
  "Pick the ONE substrate a single-tree render paints under, given the
  variant's declared substrate set and the host default. Pure data → data.

  The side-by-side grid renders every declared substrate; a single-tree
  render — the `render-variant` host hook — has to choose one, and the
  choice is constrained by a rule this ns had no way to state before
  rf2-3afns: **never paint under a substrate the variant did not declare.**

  - Exactly one declared → that one. This is the whole point: a variant
    declaring `#{:uix}` paints under `:uix`.
  - More than one declared → `host-default` when the variant actually
    declared it (the overwhelmingly common `#{:reagent :uix}` case, whose
    behaviour is therefore unchanged), otherwise the name-sorted first of
    the declared set. Sorted rather than `first`, because `first` over a
    set of two is not a decision, it is whatever the hash order was.
  - Nothing declared → `host-default`.

  Choosing a single tree for a MULTI-substrate variant is a policy this
  fn pins, not a feature: `render-variant` returns one render result and
  always did. The grid remains the surface that shows all of them."
  [substrates host-default]
  (let [declared (set substrates)]
    (cond
      (empty? declared)                  host-default
      (= 1 (count declared))             (first declared)
      (contains? declared host-default)  host-default
      :else                              (first (sort-by name declared)))))

;; ---- the multi-substrate grid component ---------------------------------

(defn multi-substrate-grid
  "Top-level component: render `variant-id` against each substrate in
  its `:substrates` set, side-by-side. The canvas component delegates
  here when a variant's substrate set has more than one entry.

  Each cell renders inside a Reagent error boundary so a throw in one
  substrate's render doesn't take down its neighbours (`002-Runtime.md` §Substrate hooks).

  Note: per-substrate decorator stacks (`002-Runtime.md` §Decorator composition order)
  are not yet threaded through here — `safe-render-cell` renders the
  raw view-id under each substrate. When that work lands, resolve the
  decorator pack here and thread it into each cell's render."
  [variant-id]
  (let [shell        @rf.story.ui.state/shell-state-atom
        variant-body (rf.story.registrar/handler-meta :variant variant-id)
        story-id     (rf.story.args/parent-story-id variant-id)
        story-body   (when story-id (rf.story.registrar/handler-meta :story story-id))
        substrates   (resolve-substrate-set variant-body story-body
                                            (or (:substrate shell) :reagent))
        view-id      (or (:component variant-body) (:component story-body))
        eff-args     (rf.story.args/resolve-args
                       variant-id
                       {:active-modes   (:active-modes shell)
                        :cell-overrides (get-in shell [:cell-overrides variant-id])})]
    ;; The multi-substrate grid is the canvas's labelled landmark when a
    ;; variant declares ≥2 substrates. `role="group"` + `aria-label`
    ;; exposes the substrate-comparison surface as a group of cells; each
    ;; cell is a `role="region"` with the substrate name as its accessible
    ;; label (set in `safe-render-cell` below), so the cells read as
    ;; labelled regions in landmark navigation.
    [:div {:style       (:grid styles)
           :role        "group"
           :aria-label  (str "Multi-substrate render — "
                             (str/join ", "
                               (map name (sort-by name substrates))))}
     (for [substrate (sort-by name substrates)]
       [:div {:key         (name substrate)
              :role        "region"
              :aria-label  (str (name substrate) " substrate cell")}
        (safe-render-cell variant-id substrate view-id eff-args)])]))
