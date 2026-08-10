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
;; :re-frame.hicasso/direct-view-call -- a view called like a function
;; ---------------------------------------------------------------------------

(h/defview self-calling-view [{:keys [depth]}]
  (if (zero? depth)
    [:li "leaf"]
    [:ul (self-calling-view {:depth (dec depth)})]))

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

;; A plain fn literal that nothing here calls during the body -- roster item 3.
(h/defview read-in-a-deferred-fn [_]
  (js/setTimeout (fn [] (h/sub [:todo/current])) 100)
  [:div "later"])

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

;; The RETURNED ROOT vector is itself the illegal head -- the children-position
;; rule cannot see this one (merged-PR audit #7784).
(h/defview fn-literal-at-the-root [_]
  [(fn [] [:span "hi"])])

(h/defview fn-literal-at-the-root-through-a-let [{:keys [x]}]
  (let [y (inc x)]
    [(fn [] [:span y])]))

;; ---------------------------------------------------------------------------
;; A STRING head and a QUALIFIED-KEYWORD head are both native tags: the runtime
;; accepts them (`codec/hiccup-tag?`) and reads them through `name`, so these
;; really are nameless <button>s (merged-PR audit #7784).
;; ---------------------------------------------------------------------------

(h/defview nameless-string-head-button [_]
  [:div ["button" {:on-click [:panel/close]}]])

(h/defview nameless-qualified-keyword-button [_]
  [:div [:app/button {:on-click [:panel/close]}]])
