(ns re-frame.bench.hicasso.arm1.dogfood-grouped
  "THE DOGFOOD SCREEN — RENDERING 2 OF 3: grouped `use-subs` (HD-002 tier
  2, the product default).

  The same screen and the same shared state layer, with this rendering's
  own event positions written out (as every rendering's now are). One fixed
  site per boundary receives the complete query collection and the body
  destructures the snapshot; the hook count does not move with the
  collection's size, which is the whole reason this tier — and not the
  scalar spine — is the default.

  Two differences from the collector rendering are visible in the source
  and are the substance of the diff judgement:

  1. **[[row]] declares both queries and reads the draft
     unconditionally**, where the collector rendering reads it only for
     an editable row. A completed row therefore holds two edges here and
     one there. The ruled alternative — a conditional *child boundary*
     owning the draft — buys the edge back and costs a second `defview`;
     the judgement page prices both, and this file takes the spelling a
     working author actually writes.
  2. **[[filters]] cannot delegate its read to the helper**, because a
     helper's read has no fixed site to belong to. The current filter is
     read once at the boundary's own site and passed down as an ordinary
     argument, which is how *every* helper-donated read is written under
     this tier.

  Nothing else moves. The DOM is asserted identical to the other two
  renderings by `arm1_dogfood_dom_cljs_test`.

  **Historical for the preference question (rf2-2rtt6.67).** The operator
  ruled this surface below the usability bar (2026-07-31), so the
  charter-v0 preference case is collector vs raw UIx and this rendering
  is not in it. It stays because it is still the measured control the
  collector's conditional read is priced against
  (`the-collectors-conditional-read-costs-fewer-edges-than-the-declaration`),
  and for no other reason."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [use-subs]]
            [re-frame.bench.hicasso.front.dogfood :as d])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(defview head [_]
  (let [{:keys [remaining]} (use-subs {:remaining [:dogfood/remaining]})]
    [:header.head
     [:h1.title "todos"]
     [:span.remaining {:data-remaining remaining} (str remaining " left")]]))

(defview new-item [_]
  (let [{:keys [draft]} (use-subs {:draft [:dogfood/draft d/new-draft-key]})]
    [:form.new {:on-submit [:dogfood/create]}
     [:input.new-input {:type        "text"
                        :value       draft
                        :on-input    [:dogfood/edit-draft d/new-draft-key :re-frame.hicasso/value]
                        :on-key-down {"Enter"  [:dogfood/create]
                                      "Escape" [:dogfood/cancel d/new-draft-key]}}]
     [:button.add {:type "submit"} "Add"]]))

(defn- filter-button
  "The read moved out: a helper under this tier takes the value it needs
  as an argument, because there is no fixed hook site inside a helper for
  a read to occupy."
  [id label current]
  [:button.filter {:type         "button"
                   :data-filter  (name id)
                   :data-current (str (= id current))
                   :on-click     [:dogfood/set-filter id]}
   label])

(defview filters [_]
  (let [{:keys [current]} (use-subs {:current [:dogfood/filter]})]
    [:nav.filters
     (filter-button :all "All" current)
     (filter-button :active "Active" current)
     (filter-button :done "Done" current)]))

(defview row [{:keys [id]}]
  (let [{:keys [todo draft]} (use-subs {:todo  [:dogfood/todo id]
                                        :draft [:dogfood/draft id]})]
    [:li.row {:data-id id :data-done (str (boolean (:done? todo)))}
     [:button.toggle {:type "button" :on-click [:dogfood/toggle id]} "✓"]
     [:span.label (:title todo)]
     (when-not (:done? todo)
       [:input.draft {:type        "text"
                      :value       draft
                      :on-input    [:dogfood/edit-draft id :re-frame.hicasso/value]
                      :on-key-down {"Enter"  [:dogfood/commit id]
                                    "Escape" [:dogfood/cancel id]}}])
     [:button.remove {:type "button" :on-click [:dogfood/remove id]} "x"]]))

(defview todo-list [_]
  (let [{:keys [ids]} (use-subs {:ids [:dogfood/visible-ids]})]
    [:ul.list {:role "list"}
     (for [id ids]
       [row {:key id :id id}])]))

(defview screen [_]
  [:div.dogfood
   [head {}]
   [new-item {}]
   [filters {}]
   [todo-list {}]])
