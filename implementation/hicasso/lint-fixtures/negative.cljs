(ns negative
  "NEGATIVE FIXTURES — correct code that RESEMBLES each mistake, and must
  produce no Hicasso finding at all (rf2-hic-022).

  This is the half of the suite that decides whether the lint layer is worth
  shipping. A rule with only positive fixtures fires on valid code and
  nobody notices until it is annoying people, so every section below is
  written to sit as close to its counter-example as correct code can get:
  the merge that IS a map, the read inside a fn literal that runs during the
  body rather than after it, the mapped child that carries its `:key`, the
  button named four different legal ways, the head that is an ordinary view.

  Several forms here are silent because the check DECLINED to guess — a `:&`
  whose value is an expression, a `for` whose body is a call. That silence
  is the design, not a gap: see the export's README for what each check
  refuses to know."
  (:require [re-frame.hicasso :as h]))

(declare row-view sanitize icon-node props)

;; ---------------------------------------------------------------------------
;; merge-not-a-map — a map literal is right, and an expression is unknowable
;; ---------------------------------------------------------------------------

(h/defview merge-carrying-a-map-literal [_]
  [:div {:& {:class "caller" :data-x 1}} "x"])

(h/defview merge-carrying-an-expression [{:keys [attrs]}]
  [:div {:& attrs}
   [:span {:& (merge attrs {:class "y"})} "x"]])

(h/defview merge-carrying-nil [_]
  [:div {:& nil} "x"])

;; ---------------------------------------------------------------------------
;; An EVENT VECTOR is not an element. `[:a]` at `:on-click` and the anchor
;; `[:a]` are the same three characters; only position tells them apart, so
;; no element check looks inside a props map.
;; ---------------------------------------------------------------------------

(h/defview event-vectors-that-look-like-elements [_]
  [:div
   [:button {:on-click [:a]} "Save"]
   [:button {:on-click [:a :with :args]} "Save with args"]
   [:span {:on-mouse-enter [:button]} "hover"]])

;; ---------------------------------------------------------------------------
;; deferred-read — a read inside a fn literal that runs DURING the body
;; ---------------------------------------------------------------------------

;; `mapv` calls its fn during this body, so these reads are this boundary's
;; edges. An "fn literal inside a body" is NOT evidence of deferral.
(h/defview reads-inside-a-mapv [{:keys [ids]}]
  [:ul (mapv (fn [id] [:li {:key id} (h/sub [:todo/label id])]) ids)])

(h/defview reads-inside-a-for [{:keys [ids]}]
  [:ul (for [id ids] [:li {:key id} (h/sub [:todo/label id])])])

;; A callback that dispatches is exactly what the callback form is for.
(h/defview dispatch-inside-a-callback [_]
  [:button {:on-click (h/hfn [e] [:form/submit (.-value (.-target e))])}
   "Save"])

;; A read at the top of a body, then used inside a callback, is correct: the
;; READ happened during the body and the callback closes over its value.
(h/defview read-then-close-over-it [_]
  (let [id (h/sub [:todo/current])]
    [:button {:on-click (h/hfn [_e] [:todo/toggle id])} "toggle"]))

;; ---------------------------------------------------------------------------
;; parked-read — a mutable reference carrying anything but a parked read
;; ---------------------------------------------------------------------------

(h/defview reset-of-a-plain-value [{:keys [cache]}]
  (reset! cache (h/sub [:todo/current]))
  [:div "fine"])

(h/defview reset-of-a-delay-that-reads-nothing [{:keys [cache]}]
  (reset! cache (delay (sanitize "x")))
  [:div "fine"])

(h/defview reset-of-a-closure-that-reads-nothing [{:keys [cache]}]
  (reset! cache (fn [] (sanitize "x")))
  [:div "fine"])

;; ---------------------------------------------------------------------------
;; unkeyed-mapped-child — the keyed spellings, and the ones we cannot judge
;; ---------------------------------------------------------------------------

(h/defview keyed-for [{:keys [items]}]
  [:ul (for [item items] [:li {:key (:id item)} (:label item)])])

(h/defview keyed-map-fn-literal [{:keys [items]}]
  [:ul (map (fn [item] [:li {:key (:id item)} (:label item)]) items)])

;; The body is a CALL, so what it returns and whether it carries a key are
;; both unknown here. Silent by design.
(h/defview mapped-call [{:keys [items]}]
  [:ul (for [item items] (row-view item))])

;; A SYMBOL at position 1 may evaluate to a props map carrying the key, or
;; may be a child. The codec decides that at runtime with `map?` and this
;; cannot, so the commonest missing-key spelling of all is deliberately
;; silent. Being right is worth more than the catch.
(h/defview mapped-child-with-dynamic-position-1 [{:keys [items]}]
  [:ul (for [item items] [:li item])])

;; `#(…)` as the mapping function: the element expression is not written at
;; a fixed position, so the check declines rather than guessing at `%`.
(h/defview mapped-by-an-anonymous-shorthand [{:keys [items]}]
  [:ul (mapv #(vector :li {:class "row"} %) items)])

;; A head that is a view var takes its key the same way; the check reads the
;; props map, not the tag.
(h/defview keyed-view-children [{:keys [items]}]
  [:ul (for [item items] [row-view {:key (:id item) :item item}])])

;; Not children at all — a mapped seq of ordinary data.
(h/defview mapped-data [{:keys [items]}]
  [:div (str (mapv :id items))])

;; ---------------------------------------------------------------------------
;; nameless-interactive-element — every legal way to name one
;; ---------------------------------------------------------------------------

(h/defview named-buttons [_]
  [:div
   [:button {:on-click [:a]} "Save"]
   [:button {:aria-label "Close"  :on-click [:b]}]
   [:button {:aria-labelledby "panel-title"}]
   [:button {:title "Help"}]
   [:button.icon {:on-click [:c]} "Delete"]
   ;; A child we cannot judge — it may well render text.
   [:button {:on-click [:d]} icon-node]
   ;; Dynamic props: the name may be in there.
   [:button props]
   [:a {:href "/help"} "Help"]])

;; ---------------------------------------------------------------------------
;; function-in-head-position — ordinary heads, and fn literals at PROP
;; positions, which are legal everywhere
;; ---------------------------------------------------------------------------

(h/defview ordinary-heads [{:keys [item]}]
  [:div
   [row-view {:item item}]
   [:<> [:span "fragment"]]
   [:> js/Fragment {} [:span "raw"]]])

(h/defview fn-literals-at-prop-positions [_]
  [:div {:ref (fn [node] (some-> node .focus))}
   [:input {:on-change #(js/console.log %)}]
   [:button {:on-click (h/hfn [_e] [:save])} "Save"]])

;; A vector of functions is ordinary data, not hiccup: the head is a symbol.
(h/defview a-vector-of-callbacks [_]
  (let [handlers [(fn [] :a) (fn [] :b)]]
    [:div (str (count handlers))]))
