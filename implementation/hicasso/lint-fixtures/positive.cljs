(ns positive
  "POSITIVE FIXTURES — every form below MUST trip exactly one Hicasso lint
  check (rf2-hic-022). One section per check, in the order the export's
  README documents them.

  This file is deliberately WRONG code. It is not on any shadow-cljs source
  path and not on any classpath: `lint-fixtures/` sits beside `src/` and
  `test/` rather than inside either, so nothing can compile it by accident,
  and `.clj-kondo/config.edn` excludes the directory from the repo's own
  lint run — otherwise the repo would lint its own counter-examples and go
  red on purpose.

  `re_frame/hicasso/lint_export_test.clj` is what reads it. Every form
  carries the finding type it must produce, so a row that stops firing is
  read as a broken rule rather than as tidy code."
  (:require [re-frame.hicasso :as h]))

;; ---------------------------------------------------------------------------
;; :re-frame.hicasso/merge-not-a-map — `:&` carrying a literal non-map
;; ---------------------------------------------------------------------------

(h/defview merge-carrying-a-vector [_]
  [:div {:& [:a :b]} "x"])

(h/defview merge-carrying-a-string [_]
  [:div {:& "class-string"} "x"])

(h/defview merge-carrying-a-keyword [_]
  [:input {:& :caller}])

;; ---------------------------------------------------------------------------
;; :re-frame.hicasso/deferred-read — a read inside the one callback form
;; ---------------------------------------------------------------------------

(h/defview read-inside-a-callback [_]
  [:button {:on-click (h/hfn [_e] [:todo/toggle (h/sub [:todo/current])])}
   "toggle"])

(h/defview grouped-read-inside-a-callback [_]
  [:button {:on-click (h/hfn [_e]
                        (let [{:keys [id]} (h/use-subs {:id [:todo/current]})]
                          [:todo/toggle id]))}
   "toggle"])

;; ---------------------------------------------------------------------------
;; :re-frame.hicasso/parked-read — a read parked in a mutable reference
;; (rf2-djxr item 3(b))
;; ---------------------------------------------------------------------------

(h/defview parked-delay [{:keys [cache]}]
  (reset! cache (delay (h/sub [:todo/expensive])))
  [:div "parked"])

(h/defview parked-closure [{:keys [cache]}]
  (reset! cache (fn [] (h/sub [:todo/expensive])))
  [:div "parked"])

(h/defview parked-in-a-volatile [{:keys [cache]}]
  (vreset! cache (delay (h/use-subs {:x [:todo/expensive]})))
  [:div "parked"])

;; ---------------------------------------------------------------------------
;; :re-frame.hicasso/unkeyed-mapped-child — mapped children with no `:key`
;; ---------------------------------------------------------------------------

(h/defview unkeyed-for [{:keys [ids]}]
  [:ul (for [id ids] [:li {:class "row"} id])])

;; A literal string at position 1 cannot be a props map, so this element
;; provably writes no attributes at all — and therefore no `:key`.
(h/defview unkeyed-for-without-props [{:keys [ids]}]
  [:ul (for [_id ids] [:li "static"])])

(h/defview unkeyed-map-fn-literal [{:keys [ids]}]
  [:ul (map (fn [id] [:li {:class "row"} id]) ids)])

(h/defview unkeyed-mapv-fn-literal [{:keys [ids]}]
  [:ul (mapv (fn [id] [:li {:class "row"} id]) ids)])

;; ---------------------------------------------------------------------------
;; :re-frame.hicasso/nameless-interactive-element — no children, no name
;; ---------------------------------------------------------------------------

(h/defview nameless-button [_]
  [:button {:class "icon-close" :on-click [:panel/close]}])

(h/defview nameless-anchor [_]
  [:a {:href "/help"}])

(h/defview nameless-button-with-a-selector [_]
  [:button.icon#close {:on-click [:panel/close]}])

;; ---------------------------------------------------------------------------
;; :re-frame.hicasso/function-in-head-position — a function LITERAL as head
;; ---------------------------------------------------------------------------

(h/defview fn-literal-in-head [_]
  [:div [(fn [] [:span "hi"])]])

(h/defview anon-literal-in-head [_]
  [:div [#(vector :span "hi")]])

(h/defview callback-form-in-head [_]
  [:div [(h/hfn [_e] [:noop])]])
