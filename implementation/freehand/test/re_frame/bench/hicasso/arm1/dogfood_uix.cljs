(ns re-frame.bench.hicasso.arm1.dogfood-uix
  "THE DOGFOOD SCREEN — RENDERING 3 OF 3: raw UIx (HD-002 tier 1, the
  comparator).

  The same screen on the shipping UIx spine: `$` instead of hiccup,
  `use-subscribe` per read, `use-frame` for dispatch, and every event
  position a hand-written closure. This is the control, and the point of
  writing it out in full is that a comparator quoted from memory is not a
  comparator.

  Three things this rendering cannot do, and they are the finding rather
  than a complaint:

  1. **No conditional read.** `row` reads the draft on every render
     including a completed row's, because a hook may not sit inside a
     `when`. The collector rendering reads it only when the row is
     editable.
  2. **No helper-donated read.** `filter-button` takes the current filter
     as an argument for the same reason the grouped rendering's does — a
     hook may not run inside a plain function called from a body.
  3. **No intent as data.** Every handler is a fresh closure that reaches
     into the event to pull `.-value`, and each one is a place the wrong
     event id can be written without anything noticing. The other two
     renderings hand the same vectors to the shared
     `front.dogfood/row-intents`.

  What it *does* have is three hooks in `row` where the two Hicasso
  renderings have two in the shell — and the shell's two do not grow with
  the read count, while these do (HD-020's budget, and why the scalar
  tier is exempt from it as a control rather than admitted to it as a
  product).

  Not a third runtime: the grouped rendering rides this same comparator
  spine's substrate, and the DOM all three build is asserted identical by
  `arm1_dogfood_dom_cljs_test`."
  (:require [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.front.dogfood :as d]
            [uix.core :refer [$ defui]]))

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
          {:type      "text"
           :value     draft
           :on-input  (fn [e] (dispatch [:dogfood/edit-draft d/new-draft-key
                                         (.. e -target -value)]))
           :on-key-down (fn [e]
                          (when-not (or (true? (.-isComposing e))
                                        (identical? 229 (.-keyCode e)))
                            (case (.-key e)
                              "Enter"  (dispatch [:dogfood/create])
                              "Escape" (dispatch [:dogfood/cancel d/new-draft-key])
                              nil)))})
       ($ :button.add {:type "submit"} "Add"))))

(defn- filter-button [id label current dispatch]
  ($ :button.filter {:type         "button"
                     :data-filter  (name id)
                     :data-current (str (= id current))
                     :on-click     (fn [_] (dispatch [:dogfood/set-filter id]))}
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
                          :on-click (fn [_] (dispatch [:dogfood/toggle id]))}
          "✓")
       ($ :span.label (:title todo))
       (when-not (:done? todo)
         ($ :input.draft
            {:type      "text"
             :value     draft
             :on-input  (fn [e] (dispatch [:dogfood/edit-draft id (.. e -target -value)]))
             :on-key-down (fn [e]
                            (when-not (or (true? (.-isComposing e))
                                          (identical? 229 (.-keyCode e)))
                              (case (.-key e)
                                "Enter"  (dispatch [:dogfood/commit id])
                                "Escape" (dispatch [:dogfood/cancel id])
                                nil)))}))
       ($ :button.remove {:type     "button"
                          :on-click (fn [_] (dispatch [:dogfood/remove id]))}
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
  its own, so the canonical-DOM comparison against the two Hicasso
  renderings is unaffected."
  [frame-kw]
  ($ uixa/frame-provider {:frame frame-kw}
     ($ screen {})))
