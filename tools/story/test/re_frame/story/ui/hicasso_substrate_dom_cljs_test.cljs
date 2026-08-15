(ns re-frame.story.ui.hicasso-substrate-dom-cljs-test
  "rf2-2dbpd — THE `:hicasso` SUBSTRATE RENDER FN, and the Reagent-parent
  crossing it stands on.

  ## The spike this file was written to settle

  rf2-5czki's survey found that Hicasso needs no new plumbing to paint in
  Story's canvas — `h/as-element` mints a React element from a boundary
  head, and the canvas already wraps the subject in
  `[rf/frame-provider {:frame variant-id} …]`, whose provider is the one
  React context every React-shaped adapter reads. But it also found that
  NOTHING IN THE REPOSITORY WITNESSED THAT CROSSING: every `h/as-component`
  / `h/as-element` boundary crossing had a Hicasso, native or UIx parent,
  and Reagent was not among them. The ruling named it the one material
  uncertainty and made proving it a precondition of the registration.

  This namespace is that proof, and then the registration's own coverage.
  `crossing-paints-under-a-reagent-parent` is the spike stated as a row:
  a `h/defview` boundary, spliced into a REAGENT hiccup tree under
  `rf/frame-provider`, mounted through `reagent.dom.client` into a real
  DOM, painting its own markup and reading a subscription from the frame
  the Reagent provider scoped.

  ## The render fn under test is the SHIPPED RECIPE

  Story ships no `install-hicasso-substrate!` — ruled (rf2-1gy4e
  placement 1): `:hicasso` is host-registered exactly as `:uix` is, so
  Story core never names Hicasso and `tools/story/deps.edn` is untouched.
  [[hicasso-render]] below is therefore the CONSUMER's five lines, and it
  is byte-for-byte the recipe written out in
  `re-frame.story.ui.multi-substrate`'s ns docstring. It is registered
  here through the public `story/register-substrate!` — no private seam,
  no stub.

  Three decisions ride in those five lines, all ruled on rf2-2dbpd:

  - **resolve LATE, per render**, off `(rf/handler-meta :view id)` — so
    re-evaluating a `defview` (which replaces the registrar entry behind
    the same id) reaches the story with no story change;
  - **read `:hicasso/component`**, because the alias entry deliberately
    carries no `:handler-fn` and `rf/view` answers nil for it (rf2-5qaf4);
  - **mint the element directly** with `h/as-element` rather than bridging
    through `h/as-component`. `element-type-is-stable-across-renders` is
    the evidence: `defview` already mints ONE `React.memo` wrapper per
    head at definition time, so a fresh element per pass rides a stable
    type and the boundary re-renders instead of remounting. A memoized
    `as-component` would re-implement that machinery one layer up.

  ## KNOWN GAP — a crossed boundary is DEAF to writes (rf2-phabt)

  The spike settled what it was asked to settle and found one more thing
  on the way, which is filed rather than fixed here (`implementation/**`
  is outside this bead's fence):

  **a boundary crossed into from a Reagent parent paints once, correctly,
  and then never re-renders on a write into its own frame.** Body-run
  counts say it plainly — the body is not re-invoked, so this is a
  missing NOTIFICATION rather than a stale read.

  It was measured with a live control, which is what makes the zero
  readable: same boundary, same subscription, same Reagent adapter, same
  drain, only the mounting route varying. Under `h/mount!` it repaints —
  that row is [[a-write-repaints-a-boundary-under-hicassos-own-root]],
  green, below. Crossed in with `h/as-element` it is deaf; crossed in
  with a memoized `h/as-component` it is deaf in exactly the same way, so
  the outward-bridge DOOR is not the variable and rf2-2dbpd's
  pre-authorized fallback B is not the remedy. Ruled out with it: the
  adapter, the drain, the provider object (both routes provide over the
  same `re-frame.adapter.context/frame-context`), frame resolution across
  the crossing (both crossed routes read the VARIANT frame's value on
  their first paint), and React reconciliation
  ([[a-reagent-parent-rerender-does-not-remount-the-boundary]] is green).

  The two red routes were run and then REMOVED rather than shipped red or
  pinned as a change-detector; rf2-phabt carries them verbatim. What
  stays here is the live half, because a control with nothing to control
  is still the row that says the harness can see a repaint at all.

  **What this gap does and does not cost.** Everything this file proves
  green is unaffected: registry dispatch, late resolution, degradation,
  the stable element type, and a crossed boundary observing its variant
  frame at render. What is blocked is INTERACTIVITY — a hicasso story
  whose subject re-renders on its own writes — which is rf2-kttom's
  concern, not this bead's.

  ## Two lanes, one file

  `-dom-cljs-test$` puts this namespace in the `:browser-test` build
  (real React, real DOM) AND in `:node-test` (whose `cljs-test$` matches
  the same suffix). The rows that need a fiber self-gate on `(browser?)`
  and say so in Node rather than passing quietly; the registry, resolution
  and degradation rows need no DOM and run in both."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.machines :as machines]
            [re-frame.registrar :as rf-registrar]
            [re-frame.story :as story]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.multi-substrate :as multi-substrate]
            [re-frame.story.ui.state :as state]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [re-frame.subs :as subs]))

;; ---------------------------------------------------------------------------
;; The subject — ordinary Hicasso views, declared at namespace load
;; ---------------------------------------------------------------------------
;;
;; Declared at the top level, which is where `defview` belongs and also
;; where its registrar alias is published: the entry is written during
;; namespace LOAD, before any fixture runs. `reset-all!` clears the
;; framework registrar, so the aliases are snapshotted below and folded
;; back before each test — the same problem (and the same shape of answer)
;; `re-frame.hicasso.view-alias-registry-cljs-test` solves by pinning its
;; fixture baseline after the declarations.

(defonce ^:private !card-runs (atom 0))

(h/defview hicasso-card
  "An ordinary boundary. It reads a subscription, so the frame it resolved
  is observable on screen rather than only in a cell table."
  [props]
  (swap! !card-runs inc)
  [:article {:class "hic-card" :data-test "hicasso-card"}
   (str (:label props) "/" (h/sub [::counter]))])

(h/defview hicasso-panel
  "A SECOND boundary, so a row can tell one hicasso view from another
  rather than merely from Reagent."
  [props]
  [:aside {:data-test "hicasso-panel"} (str "panel:" (:label props))])

(def ^:private card-id  ::hicasso-card)
(def ^:private panel-id ::hicasso-panel)

(def ^:private alias-entries
  "The registrar entries `h/defview` published at NAMESPACE LOAD, captured
  before any fixture can clear them. `reset-all!` folds them back.

  Snapshotting the WHOLE entry rather than re-deriving it keeps this
  helper honest about what it restores: whatever `defview` actually wrote
  is what each test reads, so a change to the alias shape reaches these
  rows instead of being papered over by a hand-built stand-in."
  {card-id  (rf/handler-meta :view card-id)
   panel-id (rf/handler-meta :view panel-id)})

;; ---------------------------------------------------------------------------
;; THE RECIPE UNDER TEST — the consumer's five lines
;; ---------------------------------------------------------------------------

(defn- hicasso-render
  "The `:hicasso` substrate render fn, exactly as a host writes it (and
  exactly as `multi-substrate`'s ns docstring writes it out)."
  [_variant-id view-id args]
  (if-let [head (:hicasso/component (rf/handler-meta :view view-id))]
    (h/as-element [head args])
    [:div (str ":component " (pr-str view-id)
               " is not registered as a hicasso view")]))

;; ---- fixture --------------------------------------------------------------

(declare register-probes!)

(defn- reset-all! []
  (story/clear-all!)
  (rf-registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! reagent-adapter/adapter) (catch :default _ nil))
  ;; The framework `:rf/machine` sub, re-registered after the clear — the
  ;; lifecycle machine cannot resolve without it and the canvas parks at
  ;; `:pre-mount` forever.
  (subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (canvas/reset-first-rendered!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  ;; Fold the load-time aliases back over the cleared registrar.
  (doseq [[id entry] alias-entries]
    (rf-registrar/register! :view id entry))
  ;; Start every case from a KNOWN-EMPTY `:hicasso` slot: the degradation
  ;; row wants it absent, every other row registers it.
  (multi-substrate/unregister-substrate! :hicasso)
  (reset! !card-runs 0)
  (register-probes!))

(defn- restore-registry!
  "`substrate->render-fn` is a `defonce` atom that `story/clear-all!` does
  not touch, so a `:hicasso` entry left here would leak into every
  namespace that runs after this one."
  []
  (multi-substrate/unregister-substrate! :hicasso))

(use-fixtures :each {:before reset-all! :after restore-registry!})

;; ---- probe registrations --------------------------------------------------

(defn- reagent-probe-view
  "A REAGENT view, so every row can say WHICH authoring layer painted.
  The two markers are mutually exclusive by construction."
  [_args]
  [:div {:data-test "reagent-view-render"} "rendered under reagent"])

(defn- register-probes! []
  (rf/reg-event :hicsub/bump
    (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf/reg-sub ::counter (fn [db _] (or (:n db) 0)))
  ;; A Reagent view registered under a DIFFERENT id, reachable only
  ;; through `rf/view`. A hicasso variant must never resolve to it.
  (rf/reg-view* :views/reagent-probe reagent-probe-view)
  (story/reg-story* :story.hicasso {:doc "rf2-2dbpd witness story"})
  (story/reg-variant* :story.hicasso/card
    {:doc        "One declared substrate, and it is the native one."
     :component  card-id
     :substrates #{:hicasso}
     :args       {:label "alpha"}
     :loaders    [[:noop/loader]]})
  (story/reg-variant* :story.hicasso/panel
    {:doc        "A second hicasso view under the same substrate."
     :component  panel-id
     :substrates #{:hicasso}
     :args       {:label "beta"}
     :loaders    [[:noop/loader]]})
  (story/reg-variant* :story.hicasso/missing-view
    {:doc        "Names a view no registrar entry answers for."
     :component  :views/nobody-registered-this
     :substrates #{:hicasso}
     :loaders    [[:noop/loader]]}))

;; ---- helpers --------------------------------------------------------------

(def ^:private canvas-inner @#'canvas/canvas-inner)

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle!
  "The ratom host's drain, and it is two acts rather than one — the pair
  `re-frame.bench.hicasso.arm1.ratom-activation-dom-cljs-test` spells out.

  `r/flush` runs the reactions the write enqueued, which is what turns an
  activated node's recompute into the `notify-w` a Hicasso cell's watch
  rides (a re-frame subscription under the ratom family IS a bare
  `reagent.ratom/Reaction`). The empty `flushSync` then lets the sync-lane
  `onStoreChange` that raised commit — `impl.mount/settle!`'s shape,
  spelled here so this file needs no impl namespace."
  []
  (r/flush)
  (react-dom/flushSync (fn [] nil))
  nil)

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(defn- ready-tree
  "Drive `variant-id`'s lifecycle to `:ready`, then render the canvas's
  inner tree and expand it to plain hiccup. Without `:ready` + the
  first-rendered sentinel the canvas paints a skeleton, which carries
  NEITHER marker and would fail every assertion for the wrong reason."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (loaders/mount! variant-id)
  (loaders/start-loaders! variant-id)
  (loaders/finish-loaders! variant-id)
  (loaders/finish-events! variant-id)
  (canvas/mark-variant-rendered! variant-id)
  (e2e/expand-tree (canvas-inner variant-id)))

(defn- find-react-element
  "Depth-first search for the first React element in an expanded hiccup
  tree. `expand-tree` leaves a React element untouched (it is neither a
  vector nor a seq), so this is how the boundary's element is located
  inside the canvas's Reagent tree."
  [tree]
  (cond
    (react/isValidElement tree) tree
    (or (vector? tree) (seq? tree)) (some find-react-element tree)
    :else nil))

(defn- rendered-under-reagent? [tree]
  (some? (e2e/find-by-test-id tree "reagent-view-render")))

;; ===========================================================================
;; 1 · THE SPIKE — a Hicasso boundary paints inside a REAGENT tree
;; ===========================================================================

(deftest crossing-paints-under-a-reagent-parent
  (testing "rf2-2dbpd's mandated spike. Nothing in the repository witnessed
            a Hicasso boundary rendering inside a REAGENT tree: the
            crossing is designed and documented, and every witnessed parent
            was Hicasso, native or UIx. This mounts one — a `h/defview`
            head, minted to an element by `h/as-element`, spliced into a
            Reagent hiccup vector under `rf/frame-provider`, committed
            through `reagent.dom.client` into a real DOM. If this row
            cannot be made green the registration is worthless and the
            answer is a Hicasso finding, not a Story workaround."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [variant-id :story.hicasso/card
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (rf/make-frame {:id variant-id})
        (rf/dispatch-sync [:hicsub/bump 7] {:frame variant-id})
        (try
          (react-dom/flushSync
            (fn []
              (rdc/render root
                [rf/frame-provider {:frame variant-id}
                 [:div.reagent-parent
                  (h/as-element [hicasso-card {:label "alpha"}])]])))
          (let [el (.querySelector mount-node "[data-test=\"hicasso-card\"]")]
            (is (some? el)
                "THE CROSSING PAINTS — a Hicasso boundary rendered its own
                 markup inside a Reagent parent, with no second root and no
                 mount door called")
            (is (= "alpha/7" (some-> el .-textContent))
                "and it resolved the VARIANT's frame from the Reagent
                 `frame-provider` above it: `alpha` is the props map that
                 crossed, `7` is a subscription read in that frame. A
                 boundary that resolved a frame of its own would read the
                 default frame's 0."))
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

(deftest a-write-repaints-a-boundary-under-hicassos-own-root
  (testing "THE LIVE HALF OF THE KNOWN GAP, and the reason the gap's zero
            can be read at all (see this namespace's §KNOWN GAP).

            The same boundary, the same subscription, the same Reagent
            adapter, the same write and the same drain — mounted through
            Hicasso's OWN root door rather than spliced into a Reagent
            tree. It repaints. So when the crossed counterpart does not,
            the difference is the CROSSING and not the substrate, the
            harness, the drain or the adapter — a zero with no live
            control beside it settles none of those.

            It also keeps this file honest as the gap is worked: the day
            the crossing repaints, this row is what says the two are
            finally the same measurement."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [frame-id  ::own-root-frame
            container (make-mount-node!)]
        (rf/make-frame {:id frame-id})
        (rf/dispatch-sync [:hicsub/bump 1] {:frame frame-id})
        (let [handle (h/mount! container {:frame frame-id}
                               [hicasso-card {:label "ctl"}])]
          (try
            (is (= "ctl/1"
                   (some-> (.querySelector container "[data-test=\"hicasso-card\"]")
                           .-textContent))
                "mounted, and read its frame")
            (let [runs-at-mount @!card-runs]
              (rf/dispatch-sync [:hicsub/bump 42] {:frame frame-id})
              (settle!)
              (is (> @!card-runs runs-at-mount)
                  "the write re-ran the body — the notification channel is
                   alive on this adapter, with this drain")
              (is (= "ctl/42"
                     (some-> (.querySelector container "[data-test=\"hicasso-card\"]")
                             .-textContent))
                  "and the readout moved"))
            (finally
              (try (h/unmount! handle) (catch :default _ nil)))))))))

(deftest a-reagent-parent-rerender-does-not-remount-the-boundary
  (testing "the identity decision, measured on a fiber. The render fn mints
            a FRESH element every pass and keeps no cache, which is only
            safe because `defview` mints one stable `React.memo` wrapper
            per head at definition time and every element rides that type.
            Drive the Reagent parent to re-render and the boundary must
            RE-RENDER, never remount — a remount is what a per-render
            `h/as-component` would have produced, and it would have been
            invisible on screen.

            REMOUNT IS MEASURED ON THE DOM NODE'S IDENTITY, which is the
            reading React itself cannot fake: a remount discards the
            subtree's host instances and builds new ones, so the `<article>`
            object would not survive. A text-only assertion would be green
            either way, which is exactly how this class of defect hides.

            AND THE RE-RENDER IS DRIVEN FROM INSIDE THE TREE, by a Reagent
            ratom, because a second top-level `rdc/render` CANNOT measure
            this and would report a remount every time. Reagent's own
            source says why, in a comment above `reagent.dom.client/render`:
            each call builds a fresh `comp` fn and `reagent-root` does
            `createElement(comp)` on it, *\"re-created on every render call
            to ensure React will consider it a new component always\"* — a
            new component TYPE per call, so React discards the whole tree
            by construction. That is Reagent's root door behaving as
            designed and says nothing about the crossing; a ratom write is
            what a Reagent parent re-rendering actually looks like."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [variant-id :story.hicasso/card
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)
            !label     (r/atom "alpha")
            parent     (fn []
                         [:div.reagent-parent
                          [:i (str "parent:" @!label)]
                          (hicasso-render variant-id card-id {:label @!label})])
            card-node  #(.querySelector mount-node "[data-test=\"hicasso-card\"]")
            relabel!   (fn [label]
                         (react-dom/flushSync
                           (fn [] (reset! !label label) (r/flush))))]
        (rf/make-frame {:id variant-id})
        (rf/dispatch-sync [:hicsub/bump 3] {:frame variant-id})
        (try
          (react-dom/flushSync
            (fn []
              (rdc/render root
                [rf/frame-provider {:frame variant-id} [parent]])))
          (let [first-node       (card-node)
                runs-after-mount @!card-runs]
            (is (some? first-node) "mounted")
            (relabel! "beta")
            (relabel! "gamma")
            (is (= "gamma/3" (some-> (card-node) .-textContent))
                "the boundary re-rendered with the new props")
            (is (identical? first-node (card-node))
                "and it did NOT remount across two further parent renders —
                 the very same DOM node, so one stable element type carried
                 three renders")
            (is (> @!card-runs runs-after-mount)
                "it really did re-render — the node survived because React
                 reconciled it, not because the render was skipped"))
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

;; ===========================================================================
;; 2 · the PUBLIC registry — the render fn is on the canvas's default path
;; ===========================================================================

(deftest the-registered-hicasso-render-fn-is-what-the-single-pane-reaches
  (testing "rf2-3afns put the substrate registry ON the canvas single-pane
            path, which is what makes registering into it worth anything.
            A variant declaring `:substrates #{:hicasso}` must reach the
            fn registered under `:hicasso` — and must NOT fall through to
            `rf/view`, which answers nil for a hicasso alias and would
            degrade to the missing-view diagnostic."
    (story/register-substrate! :hicasso hicasso-render)
    (let [tree (ready-tree :story.hicasso/card)
          el   (find-react-element tree)]
      (is (some? el)
          "the canvas tree carries a React element — the `:hicasso` render
           fn ran and minted one")
      (is (not (rendered-under-reagent? tree))
          "and nothing painted under Reagent")
      (is (identical? (unchecked-get hicasso-card "hicassoMemo") (.-type el))
          "the element's TYPE is the head's own stable memo wrapper, so the
           boundary React reconciles is the one `defview` minted")
      (is (= {:label "alpha"} (unchecked-get (.-props el) "rfProps"))
          "and Story's resolved args crossed as the boundary's props map —
           kebab keywords, by identity, with no camelCase round trip")
      (story/destroy-variant! :story.hicasso/card))))

(deftest two-hicasso-views-are-two-elements
  (testing "the registry entry resolves the variant's OWN `:component`,
            not merely 'some hicasso view'. Two variants under one
            substrate must mint two different element types."
    (story/register-substrate! :hicasso hicasso-render)
    (let [card  (find-react-element (ready-tree :story.hicasso/card))
          panel (find-react-element (ready-tree :story.hicasso/panel))]
      (is (identical? (unchecked-get hicasso-card "hicassoMemo") (.-type card)))
      (is (identical? (unchecked-get hicasso-panel "hicassoMemo") (.-type panel)))
      (is (not (identical? (.-type card) (.-type panel))))
      (story/destroy-variant! :story.hicasso/card)
      (story/destroy-variant! :story.hicasso/panel))))

(deftest element-type-is-stable-across-renders
  (testing "THE IDENTITY DECISION, stated where it can be checked without a
            fiber: two renders of the same story mint two DIFFERENT
            elements with the SAME type. That is the whole basis for
            returning a direct element mint and keeping no cache — the
            stability React needs is already in the head."
    (story/register-substrate! :hicasso hicasso-render)
    (let [a (hicasso-render :story.hicasso/card card-id {:label "one"})
          b (hicasso-render :story.hicasso/card card-id {:label "two"})]
      (is (not (identical? a b)) "a fresh element per pass")
      (is (identical? (.-type a) (.-type b)) "riding one stable type"))))

(deftest resolution-is-late-so-a-hot-reload-reaches-the-story
  (testing "the render fn reads `(rf/handler-meta :view id)` on EVERY
            render rather than capturing a head. Re-evaluating a `defview`
            replaces the registrar entry behind the same id; the story
            must pick the new head up with no story change. Simulated the
            way a reload works — a second entry written under the same id."
    (story/register-substrate! :hicasso hicasso-render)
    (let [before (.-type (hicasso-render :story.hicasso/card card-id {}))]
      (rf-registrar/register! :view card-id
        (assoc (get alias-entries card-id) :hicasso/component hicasso-panel))
      (let [after (.-type (hicasso-render :story.hicasso/card card-id {}))]
        (is (not (identical? before after))
            "the story followed the replaced entry")
        (is (identical? (unchecked-get hicasso-panel "hicassoMemo") after)
            "to the NEW head, resolved at render time")))))

;; ===========================================================================
;; 3 · degradation — the two misses, each at its own level
;; ===========================================================================

(deftest a-hicasso-id-that-resolves-to-nothing-degrades-inline
  (testing "`:component` naming a view no registrar entry answers for. The
            render fn returns the same style of inline diagnostic
            `reagent-render` returns — a FRAGMENT-level miss, not a grid
            cell — and names the id so the author knows what to fix."
    (story/register-substrate! :hicasso hicasso-render)
    (let [tree (ready-tree :story.hicasso/missing-view)
          text (e2e/text-nodes tree)]
      (is (nil? (find-react-element tree))
          "nothing was minted — there was no head to mint from")
      (is (re-find #"is not registered as a hicasso view" text))
      (is (re-find #"views/nobody-registered-this" text)
          "and it names WHICH view")
      (story/destroy-variant! :story.hicasso/missing-view))))

(deftest a-hicasso-variant-with-no-registered-substrate-says-so
  (testing "the host never called `register-substrate!`. The single pane
            must say so — loudly, at the fragment level — rather than
            silently painting Reagent, which is the defect rf2-3afns
            closed and the one this substrate must not reopen."
    (is (not (contains? @multi-substrate/substrate->render-fn :hicasso))
        "precondition: `:hicasso` absent from the registry")
    (let [tree (ready-tree :story.hicasso/card)
          text (e2e/text-nodes tree)]
      (is (re-find #"is not registered" text))
      (is (re-find #"hicasso" text) "and it names WHICH substrate")
      (is (not (rendered-under-reagent? tree))
          "it did NOT fall back to Reagent — falling back silently is the
           bug, not the remedy")
      (story/destroy-variant! :story.hicasso/card))))

(deftest rf-view-still-answers-nil-for-a-hicasso-alias
  (testing "the premise the whole render fn is built on (rf2-5qaf4): the
            alias entry carries NO `:handler-fn`, so `rf/view`'s *returns
            the registered render fn* contract stays honest and answers
            nil. If this ever became non-nil the `:reagent` substrate would
            start painting hicasso heads as Reagent components — a plain
            function in head position, which Hicasso refuses loudly and
            Reagent would call with the wrong ABI."
    (is (nil? (rf/view card-id)))
    (is (some? (rf/handler-meta :view card-id))
        "the entry EXISTS — the nil above is the shape, not an absence")
    (is (nil? (:handler-fn (rf/handler-meta :view card-id))))
    (is (identical? hicasso-card
                    (:hicasso/component (rf/handler-meta :view card-id)))
        "and `:hicasso/component` is the very value the `def` binds")))
