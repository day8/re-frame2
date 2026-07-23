(ns re-frame.freehand.pilot-virtual-table-compiled
  "The virtual-table pilot, PROMOTED — the same three declarations with
  `{:compiled true}` added and nothing else changed.

  Promotion is a one-line change per declaration, so the honest way to
  prove it is to promote the declarations and render both. The diff
  between this file and [[re-frame.freehand.pilot-virtual-table]]'s
  corresponding declarations is the option map, and the parity suite
  renders the two against the same rows and compares the trees.

  ## Why all THREE, and not just the table

  D010: an interpreted `v/render-fn` body answers MARKUP and a compiled
  one answers a NODE, so a compiled `v/slot` handed interpreted content
  lands on the general \"a compiled view produced a vector where a child
  was expected\" refusal. Caller and seam promote TOGETHER or not at all.
  For a virtual table that means the table, the caller that supplies the
  row content, and the declared child the row content mounts — the whole
  column of the composition, which is exactly the unit an author has to
  reason about when deciding whether a hot table is worth compiling.

  ## What is NOT promoted here

  The scroll STATE and the arithmetic are not declarations at all — they
  are a pure function and two ordinary re-frame handlers, shared verbatim
  by both modes, which is why promotion cannot move them. And an
  AUTO-SIZING table — one that measures its own viewport instead of being
  told its height — has no compiled form to promote: `v/behavior` is the
  substrate's only before-paint home and the compiled grammar admits no
  behavior form. The pilot's table is compilable precisely because it
  declares its geometry rather than measuring it.

  A separate namespace, because a view id is derived from where a
  declaration LIVES: two declarations of one name cannot share one."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.pilot-virtual-table :as ui]))

(v/defview data-table
  "The pilot's windowed table, compiled."
  {:compiled true
   :props    [:map
              [:table-key :any]
              [:rows :any]
              [:row-h :int]
              [:viewport-h :int]
              [:label :string]
              [:row {:optional true} :any]
              [:row-key {:optional true} :any]
              [:overscan {:optional true} :int]]}
  [{:keys [table-key rows row row-h viewport-h label row-key overscan]}]
  (let [total  (count rows)
        top    (v/sub [:acme.ui.table/scroll-top table-key])
        key-of (or row-key :id)
        w      (ui/window {:total      total
                           :row-h      row-h
                           :viewport-h viewport-h
                           :scroll-top top
                           :overscan   overscan})
        start  (:start w)
        end    (:end w)]
    [:div {:data-component ui/component-id
           :data-part      "root"
           :style          {:--acme-table-row-h      (str row-h "px")
                            :--acme-table-viewport-h (str viewport-h "px")}}
     [:div {:data-part     "viewport"
            :role          "grid"
            :aria-label    label
            :aria-rowcount total
            :style         {:height viewport-h :overflow-y "auto"}
            :on-scroll     (v/event [e]
                             [:acme.ui.table/scrolled table-key (ui/scroll-offset e)])}
      [:div {:data-part "canvas"
             :style     {:height (* total row-h) :position "relative"}}
       (for [i (range start end)
             :let [r (nth rows i)]]
         [:div {:key           (key-of r)
                :data-part     "row"
                :role          "row"
                :aria-rowindex (inc i)
                :data-row-key  (str (key-of r))
                :style         {:position "absolute"
                                :top      (* i row-h)
                                :height   row-h}}
          (v/slot row r i)])]]]))

(v/defview ledger-row-cells
  "The caller's row content, compiled."
  {:compiled true
   :props    [:map [:record :any] [:index :int]]}
  [{:keys [record index]}]
  [:span.acme-row-body
   [:span.acme-cell {:role "gridcell" :data-col "index"} (str index)]
   [:span.acme-cell {:role "gridcell" :data-col "code"} (:code record)]
   [:span.acme-cell {:role "gridcell" :data-col "amount"} (str (:amount record))]])

(v/defview ledger
  "The caller, compiled — supplying a COMPILED render-fn to a COMPILED
  seam, which is the crossing law's requirement."
  {:compiled true
   :props    [:map [:rows :any]]}
  [{:keys [rows]}]
  [data-table {:table-key  ui/ledger-key
               :rows       rows
               :row-key    :id
               :row-h      ui/row-h
               :viewport-h ui/viewport-h
               :label      "Q3 ledger"
               :row        (v/render-fn [r i]
                             [ledger-row-cells {:record r :index i}])}])
