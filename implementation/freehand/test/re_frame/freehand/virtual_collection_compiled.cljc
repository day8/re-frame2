(ns re-frame.freehand.virtual-collection-compiled
  "FH-CTRL-021's compiled arm — the whole vertical promoted.

  Below are [[re-frame.freehand.collection/virtual-collection]],
  [[re-frame.freehand.collection/virtual-row]],
  [[re-frame.freehand.collection/virtual-list]] and the caller from
  [[re-frame.freehand.virtual-collection-cljs-test]] with `{:compiled
  true}` added and nothing else changed: the same parameter vectors, the
  same `let`, the same `for` with the same `:let` modifier, the same
  props, the same event sites, the same slot, the same literal markup.
  The pure arithmetic is REFERRED rather than restated — `window` and
  `row-dom-id` are ordinary functions and there is nothing about them a
  lowering could change — so what differs between the two arms is exactly
  the lowering and nothing else.

  Separate namespace because a view id is derived from where a declaration
  LIVES, so two declarations of one name cannot share a namespace.
  [[re-frame.freehand.virtual-collection-parity-cljs-test]] rewrites
  exactly that one namespace component and asserts everything else is
  equal.

  ## Promoting the WHOLE vertical, not just the leaf

  The buffered-controller pilot could promote only its control, because
  its caller reads inside a `for` and the grammar refuses that
  (`:rf.ui.compile/sub-in-loop`). Nothing here has that shape: the list's
  loop reads nothing, its per-row committed handler lives on a declared
  child rather than in the loop body — which is the grammar's own
  catalogued recovery for `:rf.ui.compile/loop-capturing-handler` — and
  the caller's reads are finite lexical sites at the top of its body. So
  all three declarations promote, and the compiled arm is compiled all the
  way down rather than an interpreted caller driving one compiled leaf.

  That is the finding, not an implementation detail: a virtual collection
  is inside the compiled grammar as it stands, and promotion is a keyword.

  ## The row content stays the interpreted test's

  `item-cell` is NOT duplicated here. The slot body mounts the same
  declared child both arms mount, so the compiled parent crosses into an
  interpreted child — the ordinary case the grammar is designed to
  produce, and the one the manifest's `:crossings` roster records. A
  second copy of the row content would have made the parity comparison
  about two different views rather than one view under two lowerings.

  Dev/test scope ONLY."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.collection :refer [row-dom-id window]]
            [re-frame.freehand.virtual-collection-cljs-test :refer [item-cell]]))

(v/defview virtual-collection
  "`collection/virtual-collection`, PROMOTED — the semantic-neutral engine
  (viewport, canvas, positioned row shells), with `{:compiled true}` and no
  other change."
  {:compiled true
   :props    [:map {:closed false}
              [:row-keys :vector]
              [:row-extent :int]
              [:viewport-extent :int]
              [:scroll-offset :int]
              [:row :some]
              [:on-scroll :vector]
              [:overscan {:optional true} [:maybe :int]]
              [:attrs {:optional true} [:maybe :map]]]}
  [{:keys [row-keys row-extent viewport-extent scroll-offset row on-scroll
           overscan attrs]}]
  (let [total (count row-keys)
        w     (window {:item-count      total
                       :row-extent      row-extent
                       :viewport-extent viewport-extent
                       :scroll-offset   scroll-offset
                       :overscan        overscan})
        from  (:first w)
        to    (+ from (:count w))]
    [:div (v/spread-safe
            {:data-part         "viewport"
             :data-window-first from
             :data-window-count (:count w)
             :style             {:height   viewport-extent
                                 :overflow "auto"
                                 :position "relative"}
             :on-scroll         (conj on-scroll ::v/scroll-top)}
            attrs)
     [:div {:data-part "canvas"
            :style     {:position "relative"
                        :height   (:extent w)}}
      (for [i (range from to)
            :let [k (nth row-keys i)]]
        [:div {:key       k
               :data-part "row-shell"
               :role      "presentation"
               :style     {:position "absolute"
                           :left     0
                           :right    0
                           :top      (* i row-extent)
                           :height   row-extent}}
         (v/slot row k i total)])]]))

(v/defview virtual-row
  "`collection/virtual-row`, PROMOTED — the addressed, selectable `option`,
  with `{:compiled true}` and no other change."
  {:compiled true
   :props    [:map {:closed false}
              [:dom-id :string]
              [:index :int]
              [:item-count :int]
              [:row-key :some]
              [:row :some]
              [:active? :boolean]
              [:on-activate {:optional true} [:maybe :vector]]]}
  [{:keys [dom-id index item-count row-key row active? on-activate]}]
  [:div {:id            dom-id
         :role          "option"
         :data-part     "row"
         :aria-posinset (inc index)
         :aria-setsize  item-count
         :aria-selected (if active? "true" "false")
         :style         {:height "100%"}
         :on-click      (when on-activate (conj on-activate row-key))}
   (v/slot row row-key index item-count)])

(v/defview virtual-list
  "`collection/virtual-list`, PROMOTED — the thin listbox wrapper, with
  `{:compiled true}` and no other change. The engine it mounts and the row
  it supplies are the promoted twins above, so the compiled arm is compiled
  through every boundary."
  {:compiled true
   :props    [:map {:closed false}
              [:id :string]
              [:row-keys :vector]
              [:row-extent :int]
              [:viewport-extent :int]
              [:scroll-offset :int]
              [:row :some]
              [:on-scroll :vector]
              [:overscan {:optional true} [:maybe :int]]
              [:active-index {:optional true} [:maybe :int]]
              [:on-key {:optional true} [:maybe :vector]]
              [:on-activate {:optional true} [:maybe :vector]]]}
  [{:keys [id row-keys row-extent viewport-extent scroll-offset row on-scroll
           overscan active-index on-key on-activate]
    :as   props}]
  [virtual-collection
   {:row-keys        row-keys
    :row-extent      row-extent
    :viewport-extent viewport-extent
    :scroll-offset   scroll-offset
    :overscan        overscan
    :on-scroll       on-scroll
    :attrs           (merge (dissoc props :row-keys :row-extent :viewport-extent
                                    :scroll-offset :row :on-scroll :overscan
                                    :active-index :on-key :on-activate)
                            {:role                  "listbox"
                             :tabindex              0
                             :data-component        "rf-virtual-list"
                             :aria-activedescendant (when active-index
                                                      (row-dom-id id active-index))
                             :on-key-down           (when on-key
                                                      (conj on-key ::v/key))})
    :row             (v/render-fn [k i total]
                       [virtual-row {:dom-id      (row-dom-id id i)
                                     :index       i
                                     :item-count  total
                                     :row-key     k
                                     :row         row
                                     :active?     (= i active-index)
                                     :on-activate on-activate}])}])

(v/defview inbox
  "`virtual-collection-cljs-test/inbox`, PROMOTED — the caller, with
  `{:compiled true}` and one symbol changed: the list it names is the
  promoted twin above. The three reads, the three intents and the slot are
  the caller's own text.

  The fixture values it reads are ordinary `def`-level constants rather
  than literals, exactly as in the interpreted arm, so the two arms are
  driven by one fixture."
  {:compiled true}
  [{:keys [list-id row-extent viewport-extent overscan]}]
  [virtual-list
   {:id              list-id
    :row-keys        (v/sub [:inbox/ids])
    :row-extent      row-extent
    :viewport-extent viewport-extent
    :scroll-offset   (v/sub [:inbox/scroll])
    :active-index    (v/sub [:inbox/active])
    :overscan        overscan
    :on-scroll       [:inbox/scrolled]
    :on-key          [:inbox/key-pressed]
    :on-activate     [:inbox/opened]
    :row             (v/render-fn [k _index _total] [item-cell {:item-key k}])}])
