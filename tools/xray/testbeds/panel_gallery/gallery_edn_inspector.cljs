(ns panel-gallery.gallery-edn-inspector
  "Story coverage for the **edn-inspector widget**
  (rf2-hp4ow — widget-isolation gallery).

  The edn-inspector widget (`day8.re-frame2-xray.views.edn-inspector`)
  is the single renderer behind every Xray surface that shows a CLJS
  value: App-DB, Trace per-event payload, Reactive sub value,
  Machines snapshot drill-in, Routing diff. Every L4 panel gallery
  therefore exercises it indirectly. This gallery exercises it
  *directly* — one variant per input shape / opts combination,
  with no surrounding panel chrome — so the widget's response to
  scalars, containers, sentinels, records, uuid/inst, diff mode,
  and the full opts surface (`:zoomable?`, `:popup-affordance?`,
  `:card?`, `:header`, `:site-id`, `:default-expanded-depth`) is
  visible in isolation.

  ## Frame isolation

  Story wraps every variant in `[frame-provider {:frame variant-id}]`.
  Each variant fires a testbed-only `:panel-gallery.edn-inspector/seed!`
  event that stores its fixture under `:panel-gallery.edn-inspector
  /fixture` in the variant frame's app-db. The registered view
  (`:panel-gallery.edn-inspector/Panel`, mounted via `panel-views`)
  subscribes to that slot, destructures `{:value :before :opts}`,
  and renders `[edn-inspector value (assoc opts :before before)]`.

  Each variant therefore observes its own fixture in isolation — no
  two variants share state, and the widget's own expansion / zoom
  slots (`:rf.xray.edn-inspector/expansion`, `…/zoom`) likewise live
  on the variant frame so two side-by-side cards don't share
  expansion overrides.

  ## Why testbed-local seed event

  The widget itself ships no public seed event for `:value` — its
  inputs come from the call site as Reagent args. The gallery would
  otherwise have to pass `:args` through Story's `args` resolver,
  which works but conflicts with the dominant convention in this
  testbed (`:rf.xray/sync-trace-buffer`,
  `:rf.xray/sync-epoch-history` — all L4 gallery namespaces seed
  via events). A small testbed-local reducer (registered here,
  idempotent under namespace reload) keeps the surface uniform
  across all eight gallery namespaces."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [panel-gallery.fixtures-edn-inspector :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

;; =========================================================================
;; testbed-local seed event + sub
;; =========================================================================
;;
;; Stored on the variant frame's app-db; the registered view subscribes
;; from inside the variant's frame-provider so reads + writes resolve to
;; the same frame.

(def fixture-slot
  ":panel-gallery.edn-inspector/fixture — variant-frame slot carrying
  the active fixture map `{:value :before :opts}`. Public so the
  gallery view and the sub can share the keyword without typo drift."
  :panel-gallery.edn-inspector/fixture)

(rf/reg-event :panel-gallery.edn-inspector/seed!
  (fn [{:keys [db]} [_ fixture]]
    {:db (assoc db fixture-slot fixture)}))

(rf/reg-sub fixture-slot
  (fn [db _] (get db fixture-slot)))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the edn-inspector widget Story surface. Idempotent
  under `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/xray-edn-inspector
    {:axis :feature
     :doc  "Xray edn-inspector widget — first-class CLJS-value
            renderer (`day8.re-frame2-xray.views.edn-inspector`)."})

  (story/reg-tag :feature/opts
    {:axis :feature
     :doc  "Xray edn-inspector widget — :opts contract demos
            (zoomable, popup-affordance, card, header, site-id,
            shallow-depth)."})

  (story/reg-story :story.xray.edn-inspector
    {:doc        "THE GEM of this testbed — the edn-inspector widget,
                 the single CLJS-value renderer behind every Xray panel
                 (App-db, Trace payloads, Reactive sub values, Machine
                 snapshots, Routing diffs). Exercised here in isolation
                 across every input shape + the full opts surface
                 (`:zoomable?`, `:popup-affordance?`, `:card?`,
                 `:header`, `:site-id`, `:default-expanded-depth`). One
                 variant per shape / opts pin; side-by-side in the
                 workspace so the renderer's response is visible at a
                 glance."
     :component  :panel-gallery.edn-inspector/Panel
     :tags       #{:dev :feature/xray-edn-inspector}
     :substrates #{:reagent}})

  ;; ----- scalars ------------------------------------------------------
  (story/reg-variant :story.xray.edn-inspector/scalar-mix
    {:doc        "Every scalar kind the widget classifies — nil,
                 booleans, ints, floats, strings (with escape),
                 simple + qualified keywords, plain + qualified
                 symbols. Pins the syntax-highlight palette."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/scalar-mix)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- maps ---------------------------------------------------------
  (story/reg-variant :story.xray.edn-inspector/map-small
    {:doc        "Three-key map. Inline-preview territory."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/map-small)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/map-medium
    {:doc        "Twelve-key flat map. Wide enough to force
                 expansion past `:max-inline-width`."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/map-medium)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/map-large
    {:doc        "Fifty-key flat map. Exercises the expanded-body
                 scroll behaviour."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/map-large)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/map-nested
    {:doc        "Six-level nested map. Exercises the depth ceiling
                 — deeper nodes render `▸ {…N keys}` summaries
                 (rf2-kbdk8)."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/map-nested)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/map-large-elided
    {:doc        "Single-key map carrying the `:rf.size/large-elided`
                 sentinel (Spec 015). Widget routes through the
                 sentinel chip renderer."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/map-large-elided)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- containers ---------------------------------------------------
  (story/reg-variant :story.xray.edn-inspector/vector-mixed
    {:doc        "Vector of mixed-scalar elements + a nested map.
                 Pins the `[ ]` bracket pair colour."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/vector-mixed)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/list-of-events
    {:doc        "List of event vectors. Pins the `( )` bracket
                 pair colour."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/list-of-events)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/lazy-seq-fib
    {:doc        "Realised prefix of an infinite fib lazy-seq.
                 Widget walks a finite seq — no force-of-tail."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/lazy-seq-fib)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/set-of-tags
    {:doc        "Eight-element keyword set. Pins the `#{ }`
                 bracket pair + unordered-iteration path."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/set-of-tags)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- record -------------------------------------------------------
  (story/reg-variant :story.xray.edn-inspector/record-instance
    {:doc        "Single `GalleryUser` defrecord. Header renders
                 `#…GalleryUser{…}`; slots lay out below."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/record-instance)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- uuid + inst (default IXrayEdnInspector formatters) -----------
  (story/reg-variant :story.xray.edn-inspector/uuid
    {:doc        "Single random UUID. Default formatter (rf2-x16b1)
                 renders compact `#uuid \"…<last-8>\"` header with
                 full canonical form on hover + expand."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/uuid-instance)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/insts
    {:doc        "Five insts spanning the default formatter's
                 relative-time bucketing — just-now, mins ago,
                 hours ago, days ago, ISO fallback (>30d), future."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/inst-instances)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- diff mode ----------------------------------------------------
  (story/reg-variant :story.xray.edn-inspector/diff-map-mixed-ops
    {:doc        "Diff pair surfacing every op in one map:
                 `:added` (new `:flash`), `:modified` (`:counter`
                 bump + nested `:name` change), `:removed`
                 (`:legacy-flag`), `:same` (untouched `:user/id`).
                 Pins the gutter-glyph + colour ladder under
                 rf2-zuh1e's `children-of-pair` walk."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-map-mixed-ops)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-vector-element-changes
    {:doc        "Diff on a vector — `:added` tail, `:modified`
                 middle element, `:same` head. Pair-walk respects
                 position."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-vector-element-changes)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-deep-children-changed
    {:doc        "Deep diff: change sits six levels down. Ancestor
                 chain force-expands; intermediate maps paint the
                 `◴` children-changed glyph in the gutter."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-deep-children-changed)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- opts demos ---------------------------------------------------
  (story/reg-variant :story.xray.edn-inspector/opts-zoomable
    {:doc        "`:zoomable? true` (rf2-h71e0; gesture rf2-zl4rs) —
                 double-click a container (or press Enter while it is
                 focused) to re-root the inspector onto it. No glyph."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-zoomable)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-popup-affordance
    {:doc        "`:popup-affordance? true` (rf2-l4625) — `↗` icon
                 button sits at the widget's top-right corner."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-popup-affordance)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-card
    {:doc        "`:card? true` (rf2-63ie5) — outer container picks
                 up inspector-card chrome (bg, border, radius)."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-card)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-header
    {:doc        "`:header \"…\"` (rf2-okq7p) — three-shade card
                 chrome with a string ribbon label."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-header)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-header-hiccup
    {:doc        "`:header [:span …]` (rf2-okq7p) — composed
                 hiccup ribbon (label + code chip)."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-header-hiccup)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-site-id
    {:doc        "`:site-id` (rf2-pvsxs) — stable site-id survives
                 a remount. Visually identical to the unkeyed
                 mount; the difference shows on remount cadence."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-site-id)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-shallow-depth
    {:doc        "`:default-expanded-depth 1` (rf2-kbdk8) — legacy
                 depth-driven behaviour. Depth ≥ 1 renders
                 `▸ {…N keys}` collapsed summaries."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-shallow-depth)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/opts-combined
    {:doc        "Composed opts — `:header` + `:popup-affordance?`
                 + `:zoomable?` together. Pins the affordance
                 layout when all three coexist."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/opts-combined)]]
     :tags       #{:dev :feature/opts}
     :substrates #{:reagent}})

  ;; ----- grammar-edge diffs (rf2-yaajg) -------------------------------
  ;;
  ;; Six additional diff variants that pin grammar edges the canonical
  ;; added/removed/modified coverage doesn't reach: set ops, boolean
  ;; toggle, vector-of-records, nil-vs-missing, near-equal floats,
  ;; deep redaction. Each variant pins ONE engine behaviour for
  ;; visual-regression isolation.

  (story/reg-variant :story.xray.edn-inspector/diff-set-mixed
    {:doc        "Set diff with both add and remove ops: `#{:a :b
                 :c}` → `#{:a :b :d}`. Sets have no positional
                 identity — `:c` paints `−`, `:d` paints `+`, both
                 from the key-side glyph treatment."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-set-mixed)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-boolean-toggle
    {:doc        "Simplest scalar diff — `{:enabled? true}` →
                 `{:enabled? false}`. Pins the `~` glyph + `←
                 was true` suffix for the boolean leaf
                 case."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-boolean-toggle)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-vector-of-maps
    {:doc        "Vector-of-records — `[{:id 1 :count 5} {:id 2
                 :count 8}]` → `[{:id 1 :count 6} {:id 2 :count
                 8}]`. The canonical app-db rows-of-records shape;
                 verifies per-element walk against per-vector-
                 index diff."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-vector-of-maps)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-nil-vs-missing
    {:doc        "`{:k nil}` vs `{}` distinction. Before carries
                 `:will-disappear 42` and `:explicit-nil nil`;
                 after replaces `:will-disappear` with `:newly-nil
                 nil`. Verifies `(contains? m k)` is the
                 discriminator — not `(some? (get m k))` — so the
                 engine paints `−` for the removed key and `+` for
                 the newly-present-but-nil key."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-nil-vs-missing)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-numeric-near-equal
    {:doc        "Near-equal floats — `1.0` vs `1.0000001`.
                 Verifies the engine does NOT fold nearly-equal
                 floats into `:same`; the `:x` row paints `~` +
                 the full `← was 1.0` suffix. `:y` stays
                 untouched as a control."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-numeric-near-equal)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-redacted-context
    {:doc        "`:rf/redacted` sentinel three levels deep —
                 `{:session {:user {:password :rf/redacted}}}` →
                 `{:session {:user {:password \"hunter2\"}}}`.
                 Verifies R8's redaction-curated suffix composes
                 with deep nesting; ancestors paint `◴` children-
                 changed; the leaf carries `← was redacted`
                 without printing the sentinel."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-redacted-context)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- additional grammar-edge diffs (rf2-r7xf7) --------------------
  ;;
  ;; Three further diff variants the rf2-yaajg six don't reach:
  ;; pure-add set diff (distinct from set-mixed's add+remove pair),
  ;; long-string truncation under diff annotation, and symbol-value
  ;; mutation (distinct from keyword mutation already covered by
  ;; the canonical map diffs). Each variant pins ONE engine
  ;; behaviour for visual-regression isolation.

  (story/reg-variant :story.xray.edn-inspector/diff-set-add
    {:doc        "Pure-add set diff: `#{:a :b}` → `#{:a :b :c}`.
                 Distinct from `diff-set-mixed`'s add+remove pair —
                 only `:c` is new and nothing is removed, so the
                 engine paints a single `+` glyph cleanly without
                 the remove signal cluttering the per-member walk."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-set-add)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-long-string-truncation
    {:doc        "Long-string mutation — `{:bio \"short\"}` →
                 `{:bio \"<>200-char essay>\"}`. Verifies the
                 widget's truncation chrome (ellipsis / hover-
                 expand / overflow handling) renders cleanly when
                 a long string also carries a `:modified` diff
                 annotation: the `~` glyph, the truncated
                 rendering, and the `← was \"short\"`
                 suffix must coexist without one clobbering the
                 other."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-long-string-truncation)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.edn-inspector/diff-symbol-value
    {:doc        "Symbol-value mutation — `{:fn-name 'old-handler}`
                 → `{:fn-name 'new-handler}`. Verifies the
                 inspector handles symbols correctly under diff
                 annotation (distinct from keyword mutation).
                 Symbols carry the `:syntax-symbol` token; under
                 modification the `~` glyph + `← was
                 old-handler` suffix must render against the
                 symbol palette without falling back to the
                 keyword or string token."
     :events     [[:panel-gallery.edn-inspector/seed! (fixtures/diff-symbol-value)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ----------------------------------------------------
  (story/reg-workspace :Workspace.xray.edn-inspector/all
    {:doc      "All edn-inspector widget variants in one auto-grid.
                Scroll to see the renderer's response across scalar
                / map (small / medium / large / nested / sentinel)
                / vector / list / lazy-seq / set / record / uuid /
                inst / diff (mixed-ops / vector / deep / set-mixed
                / boolean-toggle / vector-of-maps / nil-vs-missing
                / numeric-near-equal / redacted-context / set-add
                / long-string-truncation / symbol-value) / opts
                (zoomable / popup / card / header / header-hiccup
                / site-id / shallow-depth / combined)."
     :layout   :variants-grid
     :for      :story.xray.edn-inspector
     :columns  2
     :tags     #{:dev}}))

(register-all!)
