(ns re-frame.bench.hicasso.arm1.dogfood-uix
  "THE DOGFOOD SCREEN — RAW UIx, THE CONTROL (HD-002 tier 1; the charter's
  v0 gate 'a dogfood list+form screen preferred over raw UIx by its
  authors' names this file as the thing preference is measured against).

  The same screen on the shipping UIx spine, written the way this repo's
  own UIx examples teach it (`examples/substrates/uix/login/core.cljs`):
  `$` + `defui`, one ambient `use-subscribe` per read, `dispatch` off
  `use-frame`, `:on-change` on controlled inputs, shorthand closures at
  the one-expression event positions. The control is kept as good as its
  authors can make it, deliberately — a comparator quoted from memory, or
  written as a strawman, would invalidate the preference case it anchors
  (rf2-2rtt6.67). The one shared piece is the state layer
  (`re-frame.bench.hicasso.front.dogfood`): the events and subscriptions
  are identical by construction, so what differs between the renderings
  is the view layer and nothing else. The intents are NOT shared — each
  rendering writes its own event positions, and that they agree is
  proved rather than arranged (`arm1_dogfood_dom_cljs_test`).

  ## Three placement costs — stated as costs, not as impossibilities

  An earlier revision of this docstring called these three things the
  rendering *cannot express*. That was too strong, and the merged-PR
  audit of #7395 was right to catch it. React's rules constrain WHERE a
  read may be written, not WHETHER the value can be had; each item below
  is available to a UIx author at a stated structural price. The price
  is the finding.

  1. **A conditional read costs a component.** [[row]] reads the draft on
     every render including a completed row's, because a hook may not sit
     inside a `when`. A UIx author who wants the collector's behaviour
     lifts the editable input into its own `defui` and renders THAT
     conditionally — the hook is then unconditional inside it. The value
     is obtainable; the branch has to become a component boundary, with
     its own element, fiber and reconciliation. This is the same trade
     the grouped rendering's docstring prices on the Hicasso side (\"a
     conditional *child boundary* owning the draft — buys the edge back
     and costs a second `defview`\"), so the two surfaces are being
     charged symmetrically.
  2. **A helper-donated read costs the helper its plainness.**
     [[filter-button]] takes the current filter and the dispatch as
     arguments because a hook may not run inside a *plain* function. It
     may run inside another HOOK: `use-filter-button-props` would be
     legal here, since [[filters]] calls it three times unconditionally.
     The price is that the helper joins the caller's hook set and
     inherits the call-site discipline — unconditional, fixed count, and
     **not inside a variable-length `for`**, which is why [[todo-list]]'s
     per-row read has no hook-shaped route at all and must be the
     component in item 1.
  3. **Intent-as-data is genuinely absent.** Every handler is a closure
     that reaches into the event for `.-value`, and each one is a place
     the wrong event id can be written without anything noticing. There
     is no UIx spelling that makes a handler a value equality can
     compare; this one is a real absence rather than a placement cost.

  And one thing it must know that the data key-map knows for it: **React's
  synthetic keyboard event drops `isComposing`** — the property is not in
  React's `KeyboardEventInterface`, so reading it off the event a handler
  is handed answers `undefined` however plainly the browser set it, and
  an IME's composing Enter would commit a half-composed draft. The gate
  must read the NATIVE event ([[ime-gated]] — the same law
  `front.intent/composing?` centralises for every data key-map, measured
  at `arm1_controlled_grid_dom_cljs_test/reacts-synthetic-keyboard-event-drops-is-composing`).
  This file's first cut read the synthetic event and carried exactly that
  bug; the intent-parity witness's composing probe is what a regression
  reds against.

  What the rendering needs beyond React itself: nothing. No macro, no
  codec, no lowering — which is raw UIx's own half of the preference
  case, stated in
  `docs/design/hicasso/studio/the-dogfood-preference-case.md` beside the
  costs above.

  Not a third runtime: this comparator spine's substrate is the one the
  retired grouped rendering rides too, and the DOM this file builds is
  asserted identical to the collector's — elements AND dispatched
  intents — by `arm1_dogfood_dom_cljs_test`."
  (:require [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.front.dogfood :as d]
            [uix.core :refer [$ defui]]))

(defn- ime-gated
  "One keydown handler over `key->intent`, refusing keys that arrive
  mid-composition. Both signals are read off the NATIVE event: React's
  synthetic drops `isComposing` (see the ns docstring), and `keyCode` 229
  is all some IMEs on some browsers ever send. Written once because an
  idiomatic author factors it once — and because during composition every
  keystroke belongs to the IME, so the gate covers the whole map."
  [dispatch key->intent]
  (fn [e]
    (let [native (or (.-nativeEvent e) e)]
      (when-not (or (true? (.-isComposing native))
                    (identical? 229 (.-keyCode native)))
        (when-some [intent (get key->intent (.-key e))]
          (dispatch intent))))))

(defui head [_props]
  (let [remaining (uixa/use-subscribe [:dogfood/remaining])]
    ($ :header.head
       ($ :h1.title "todos")
       ($ :span.remaining {:data-remaining remaining} (str remaining " left")))))

(defui new-item [_props]
  (let [draft              (uixa/use-subscribe [:dogfood/draft d/new-draft-key])
        {:keys [dispatch]} (uixa/use-frame)]
    ($ :form.new {:on-submit (fn [e]
                               (.preventDefault e)
                               (dispatch [:dogfood/create]))}
       ($ :input.new-input
          {:type        "text"
           :value       draft
           :on-change   #(dispatch [:dogfood/edit-draft d/new-draft-key
                                    (.. % -target -value)])
           :on-key-down (ime-gated dispatch
                                   {"Enter"  [:dogfood/create]
                                    "Escape" [:dogfood/cancel d/new-draft-key]})})
       ($ :button.add {:type "submit"} "Add"))))

(defn- filter-button [id label current dispatch]
  ($ :button.filter {:type         "button"
                     :data-filter  (name id)
                     :data-current (str (= id current))
                     :on-click     #(dispatch [:dogfood/set-filter id])}
     label))

(defui filters [_props]
  (let [current            (uixa/use-subscribe [:dogfood/filter])
        {:keys [dispatch]} (uixa/use-frame)]
    ($ :nav.filters
       (filter-button :all "All" current dispatch)
       (filter-button :active "Active" current dispatch)
       (filter-button :done "Done" current dispatch))))

(defui row [{:keys [id]}]
  (let [todo               (uixa/use-subscribe [:dogfood/todo id])
        draft              (uixa/use-subscribe [:dogfood/draft id])
        {:keys [dispatch]} (uixa/use-frame)]
    ($ :li.row {:data-id id :data-done (str (boolean (:done? todo)))}
       ($ :button.toggle {:type     "button"
                          :on-click #(dispatch [:dogfood/toggle id])}
          "✓")
       ($ :span.label (:title todo))
       (when-not (:done? todo)
         ($ :input.draft
            {:type        "text"
             :value       draft
             :on-change   #(dispatch [:dogfood/edit-draft id (.. % -target -value)])
             :on-key-down (ime-gated dispatch
                                     {"Enter"  [:dogfood/commit id]
                                      "Escape" [:dogfood/cancel id]})}))
       ($ :button.remove {:type     "button"
                          :on-click #(dispatch [:dogfood/remove id])}
          "x"))))

(defui todo-list [_props]
  (let [ids (uixa/use-subscribe [:dogfood/visible-ids])]
    ($ :ul.list {:role "list"}
       (for [id ids]
         ($ row {:key id :id id})))))

(defui screen [_props]
  ($ :div.dogfood
     ($ head {})
     ($ new-item {})
     ($ filters {})
     ($ todo-list {})))

(defn root
  "`frame-provider` SCOPES an already-created frame and renders no DOM of
  its own, so the canonical-DOM comparison against the collector
  rendering is unaffected."
  [frame-kw]
  ($ uixa/frame-provider {:frame frame-kw}
     ($ screen {})))
