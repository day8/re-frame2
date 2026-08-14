(ns re-frame.bench.hicasso.arm1.dogfood-collector
  "THE DOGFOOD SCREEN — RENDERING 1 OF 3: the ambient collector (HD-002
  tier 3, the challenger).

  One list, one controlled field per editable thing, and subscription
  reads written wherever the value is used: in a `let`, inside a `when`,
  inside a helper. That is the whole of the tier's authoring claim, and
  the row below is where it earns or loses its place — the draft
  subscription is read **only when the row is editable**, so a completed
  row holds one edge and an editable row holds two. No other tier in the
  tournament can write that.

  The state layer is the shared front half's
  (`re-frame.bench.hicasso.front.dogfood`) — one app-db shape, one event
  set, one subscription set under every rendering. **The intents are
  not shared**: each rendering writes its own event positions in its own
  spelling, because that is precisely what the preference case asks the
  authors to compare, and a hoisted helper both hid this file's authoring
  from the count and could not catch a correct vector wired to the wrong
  position. Agreement on the events is proved instead — DOM parity AND
  dispatched-intent parity, by `arm1_dogfood_dom_cljs_test`.

  ## The landed-surface audit (rf2-2rtt6.67)

  Checked against every authoring spelling that has landed since this
  file was written: `h/fn` (HD-024), `:&` (HD-023), the `::h/prevent`
  head (HD-026), the `::h/navigate` head (HD-027), and presence as
  data (HD-025). **None is needed on this screen** — every event position
  takes a literal intent vector, nothing forwards a props remainder, no
  anchor acts as a button (the one submit position takes the
  census-weighted auto-prevent), nothing routes, and nothing animates an
  exit. That matches the tier-1 roster's finding on the spine shapes
  (rf2-2rtt6.57) and is stated as an absence rather than a pass; the
  preference case
  (`docs/design/hicasso/studio/the-dogfood-preference-case.md`) carries
  it as evidence.

  Mounted by `arm1_dogfood_dom_cljs_test` — and mounting it is what
  started the six-week K7 clock (HD-014)."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.front.dogfood :as d])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(defview head [_]
  (let [remaining (sub [:dogfood/remaining])]
    [:header.head
     [:h1.title "todos"]
     [:span.remaining {:data-remaining remaining} (str remaining " left")]]))

(defview new-item [_]
  [:form.new {:on-submit [:dogfood/create]}
   [:input.new-input {:type        "text"
                      :value       (sub [:dogfood/draft d/new-draft-key])
                      :on-input    [:dogfood/edit-draft d/new-draft-key :re-frame.hicasso/value]
                      :on-key-down {"Enter"  [:dogfood/create]
                                    "Escape" [:dogfood/cancel d/new-draft-key]}}]
   [:button.add {:type "submit"} "Add"]])

(defn- filter-button
  "A plain function call — it inlines into the enclosing boundary and
  mints no boundary of its own (HD-016). The `sub` it donates is the
  collector-contingent case authoring.md names: legal here, and only
  here, because this tier tracks reads wherever they happen."
  [id label]
  [:button.filter {:type      "button"
                   :data-filter (name id)
                   :data-current (str (= id (sub [:dogfood/filter])))
                   :on-click  [:dogfood/set-filter id]}
   label])

(defview filters [_]
  [:nav.filters
   (filter-button :all "All")
   (filter-button :active "Active")
   (filter-button :done "Done")])

(defview row [{:keys [id]}]
  (let [todo (sub [:dogfood/todo id])]
    [:li.row {:data-id id :data-done (str (boolean (:done? todo)))}
     [:button.toggle {:type "button" :on-click [:dogfood/toggle id]} "✓"]
     [:span.label (:title todo)]
     (when-not (:done? todo)
       [:input.draft {:type        "text"
                      :value       (sub [:dogfood/draft id])
                      :on-input    [:dogfood/edit-draft id :re-frame.hicasso/value]
                      :on-key-down {"Enter"  [:dogfood/commit id]
                                    "Escape" [:dogfood/cancel id]}}])
     [:button.remove {:type "button" :on-click [:dogfood/remove id]} "x"]]))

(defview todo-list [_]
  [:ul.list {:role "list"}
   (for [id (sub [:dogfood/visible-ids])]
     [row {:key id :id id}])])

(defview screen [_]
  [:div.dogfood
   [head {}]
   [new-item {}]
   [filters {}]
   [todo-list {}]])
