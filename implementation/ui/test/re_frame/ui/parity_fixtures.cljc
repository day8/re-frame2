(ns re-frame.ui.parity-fixtures
  "The S1f PARITY CORPUS v0 (rf2-vxgfnd.6; 07 §4/§5) — one fixture set
  exercising EVERY template-grammar form the S1 compiler ships, compiled
  by BOTH emitters from this single .cljc source:

    - as CLJS (shadow :node-test), rendered against REAL React via
      react-dom/server and parsed into semantic nodes;
    - as Clojure (the CLJS compiler's own JVM at macro time, and the
      plain JVM test suite), rendered to node-schema-v1 trees and
      projected through normalization N (re-frame.ui.semantic).

  Parity = NORMALIZED STRUCTURAL EQUIVALENCE over N's semantic space —
  byte-identical HTML is NOT the contract.

  Grammar coverage: DOM elements + .class#id sugar; internal views
  (props maps, :or defaults, present-nil, namespaced slots, :as
  materialization, children forwarding); fragments (incl. keyed,
  fragment-rooted and nil-rooted views); keyed `for` (single + multi
  pair, :let/:when/:while modifiers); control forms let/letfn/if/
  if-not/when/when-not/cond/case/do; text/number/nil/false children
  (coalescing + JS ToString incl. the ECMA exponent windows); handlers
  (literal vectors + placeholders, options maps, dynamic
  classification) — dropped by N but pinned handler-attr-free in HTML;
  `ui/html`; `ui/spread`; custom elements (RULED property
  classification); SVG (aliases, xlink, foreignObject reversion);
  MathML (annotation-xml HTML island); the boolean/booleanish/
  overloaded/aria/data value classes; the form-control special forms;
  void elements; :style (px rule, unitless, custom properties, vendor
  prefixes, keyword values); :class (string/vector/flag-map orders,
  sugar-first merge).

  OUT OF SCOPE at S1 (documented, not silently skipped): foreign
  components and `ui/raw` (JVM host-op — no JVM execution by contract),
  `ui/client-only` + `ui/presence` (land S2/S3), `sub`/`lease` reads
  (S2). Their compile grammar is pinned by the S1b suites."
  (:require [re-frame.ui :as ui :refer [defview]]))

;; ---------------------------------------------------------------------------
;; Custom-element declaration (RULED grammar) — corpus-owned tag
;; ---------------------------------------------------------------------------

(ui/custom-element :parity-widget {:properties #{:accent-color}})

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(defview static-kitchen
  "Fully static subtree: sugar, nested elements, style rows (px /
  unitless / zero / custom property / keyword value / vendor prefix),
  escaping in text + attr values, void elements, boolean statics."
  []
  [:div#kitchen.panel.wide
   {:style {:padding 16 :opacity 0.5 :margin 0 :z-index 3
            :--main-color "#f00" :flex-direction :column
            :-webkit-line-clamp 3 :border-top "1px solid #ccc"}}
   [:h2.title "Static & <sound>"]
   [:p "Text with " [:em "emphasis"] " and 'quotes' & \"doubles\"."]
   [:a {:href "/docs?a=1&b=2" :title "Docs & guides"} "Docs"]
   [:hr]
   [:img {:src "/x.png" :alt "an <img>"}]
   [:br]
   [:input {:type :text :default-value "d&v"}]
   [:footer {:aria-label "footer" :tab-index 0 :data-fooBar "mixedCase"}
    "(c) 2026 <re-frame2>"]])

(defview class-forms
  "Class composition: sugar-first merge, vectors in vector order,
  flag maps in lexicographic order, dynamic class values."
  [{:keys [flags extra on?]}]
  [:div.base.zed {:class ["v1" nil :v2]}
   [:span.s {:class flags} "flags"]
   [:span {:class extra} "dyn"]
   [:span.t {:class {:beta true :alpha on? :gamma false}} "lex"]])

(defview text-forms
  "Text/number children: JS ToString (integral doubles, negatives,
  ECMA exponent windows), nil/false/true dropped, adjacent runs
  coalesced, empty strings dropped."
  [{:keys [n msg show?]}]
  [:p.txt
   "n=" n " d=" 2.5 " i=" 1.0 " z=" -0.0 " s=" 0.0001 " b=" 1.0E21
   " t=" 1.5E-7
   (when show? " shown")
   (if show? nil false)
   "" msg])

(defview branches
  "Every control form: if / if-not / when / when-not / cond / case / do
  / let / letfn."
  [{:keys [kind n]}]
  (let [big? (> n 10)]
    [:div.branches
     (if big? [:p.big "big"] [:p.small "small"])
     (if-not big? [:p.notbig "notbig"] nil)
     (when big? [:p.wb "when-big"])
     (when-not big? [:p.wnb "when-not-big"])
     (cond
       (= kind :a) [:div.a "A"]
       (= kind :b) [:div.b "B"]
       :else       [:div.c "C"])
     (case kind
       :a [:span.ca "case-a"]
       :b [:span.cb "case-b"]
       [:span.cd "case-default"])
     (do [:span.done "do"])
     (letfn [(lbl [x] (str "#" x))]
       [:span.lf (lbl n)])]))

(defview list-row
  "Row view for keyed list-over-view fixtures."
  [{:keys [item]}]
  [:li.row {:class {:hot (:hot? item)} :data-priority (:priority item)}
   [:span.lbl (:label item)]
   (when (:hot? item) [:span.flag "HOT"])])

(defview lists
  "Keyed for: over elements, over views, multi-pair (nested iteration
  flattened), :let/:when/:while modifiers, empty list, capture-free
  event vector shared across rows."
  [{:keys [items groups caps]}]
  [:section.lists
   [:ul.els
    (for [i items]
      [:li {:key (:id i) :on-click [:list/refresh]} (:label i)])]
   [:ul.views
    (for [i items]
      [list-row {:key (:id i) :item i}])]
   [:ul.pairs
    (for [g groups x (:xs g)]
      [:li {:key (str (:id g) "-" x)} (:id g) ":" x])]
   [:ul.mods
    (for [i items
          :let [n (:label i)]
          :when (:hot? i)]
      [:li {:key (:id i)} "hot " n])]
   [:ul.while
    (for [c caps :while (< c 3)]
      [:li {:key c} c])]
   [:ul.empty
    (for [i []]
      [:li {:key i} i])]])

(defview fragment-rooted
  "A view whose root is a fragment — the boundary splices."
  [{:keys [a b]}]
  [:<> [:dt a] [:dd b]])

(defview nil-rooted
  "A view that renders nothing unless :show? — nil root, spliced away."
  [{:keys [show?]}]
  (when show? [:p.maybe "shown"]))

(defview fragments
  "Fragments mid-tree, keyed fragments in a for, fragment/nil-rooted
  views in child position."
  [{:keys [pairs show?]}]
  [:div.frags
   [:<> [:span.f1 "one"] [:span.f2 "two"]]
   [:dl
    (for [p pairs]
      [:<> {:key (:k p)} [:dt (:k p)] [:dd (:v p)]])]
   [:dl.viewfrag [fragment-rooted {:a "ay" :b "bee"}]]
   [nil-rooted {:show? show?}]
   [:p.after "after"]])

(defview bool-attrs
  "The boolean value classes: boolean attrs true/false (+ the corrected
  boolean-only `hidden` incl. the \"until-found\" string), booleanish
  strings, overloaded booleans, `muted` (corrected row 7 — it DOES
  serialise), aria-* always-stringify, data-* stringify."
  [{:keys [on? dl]}]
  [:div.bools
   [:button {:disabled on?} "b1"]
   [:button {:disabled (not on?)} "b2"]
   [:p {:hidden on?} "hidden-bool"]
   [:p {:hidden "until-found"} "hidden-string"]
   [:span {:content-editable on? :spell-check (not on?)}]
   [:span {:draggable on?} "drag"]
   [:a {:href "/f" :download dl} "dl"]
   [:a {:href "/g" :download (not on?)} "dl2"]
   [:video {:muted on? :controls on? :plays-inline on?}]
   [:details {:open on?} [:summary "s"]]
   [:span {:aria-hidden (not on?) :aria-checked "mixed" :data-on on?
           :data-idx 7}
    "aria"]])

(defview form-controls
  "Form-control special forms: input value/checked as attributes,
  default-value/default-checked -> value/checked, textarea value ->
  text child, select value -> selected on the matching option."
  [{:keys [txt on? pick]}]
  [:form.controls
   [:input {:value txt :read-only true :on-input [:form/typed :rf.ui/value]}]
   [:input {:type :checkbox :checked on? :read-only true
            :on-change [:form/toggled :rf.ui/checked]}]
   [:input {:type :text :default-value "dv"}]
   [:input {:type :checkbox :default-checked on?}]
   [:textarea {:value txt :read-only true}]
   [:select {:default-value pick :disabled true}
    [:option {:value "a"} "Alpha"]
    [:option {:value "b"} "Beta"]
    [:option "gamma"]]
   [:label {:for "field-1"} "label"]
   [:input#field-1 {:max-length 5 :name "f1"}]])

(defview svg-shapes
  "SVG namespace + aliases: viewBox (camel survives), stroke-width
  (SVG's own hyphen), xlink:href, numeric attr values, clipPath camel
  tag, fill-opacity, foreignObject children reverting to HTML."
  [{:keys [w]}]
  [:svg {:view-box "0 0 24 24" :width w :class "icon"}
   [:defs [:clipPath {:id "cp"} [:rect {:x 0 :y 0.5 :width w :height 4}]]]
   [:path {:d "M0 0" :stroke-width 2 :fill-opacity 0.5
           :stroke-dasharray "3 1"}]
   [:use {:xlink-href "#i" :xml-lang "en"}]
   [:text {:text-anchor :middle} "svg text"]
   [:foreignObject {:x 1 :y 1 :width w :height 8}
    [:div.fo {:tab-index 2} "HTML island <in> svg"]]])

(defview math-shapes
  "MathML namespace + the annotation-xml HTML island."
  []
  [:math
   [:semantics
    [:mrow [:mi "x"] [:mo "+"] [:mn 1]]
    [:annotation-xml {:encoding "text/html"}
     [:div.m-island "html in math"]]]])

(defview custom-elements
  "RULED custom-element classification: the declared property
  (:accent-color -> accentColor, omitted from markup by N and — per the
  recorded React-SSR divergence — stripped from the parsed side by the
  harness), undeclared names as attributes, booleans per DOM rules,
  undeclared elements all-attributes."
  [{:keys [accent flag]}]
  [:div.hosts
   [:parity-widget {:accent-color accent :data-x "d" :disabled flag
                    :label "wide & <deep>"}]
   [:plain-element {:foo "bar" :count 3}]])

(defview spread-view
  "ui/spread — the one sanctioned runtime prop-map conversion: merge
  semantics (overrides win), sugar-first class, name conversion, event
  entries routed to handlers (never markup)."
  [{:keys [extra]}]
  [:div.card (ui/spread {:tab-index 3 :class "base" :data-a "b"
                         :on-click [:card/clicked]}
                        extra)
   [:span "inner"]])

(defview trusted
  "ui/html — the single escaping bypass, sole-child position."
  [{:keys [markup]}]
  [:div.content (ui/html markup)])

(defview handlers
  "Handler grammar: literal vectors (+ placeholders), options maps,
  dynamic handler expressions classified by value (vector / nil), a
  capture-free vector in a loop. N drops :events; the HTML side pins
  no-handler-attributes-ever."
  [{:keys [id on? ks]}]
  [:div.handlers
   [:button {:on-click [:cart/add id]} "add"]
   [:input {:on-input [:form/typed :email :rf.ui/value] :default-value ""}]
   [:input {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]
            :default-checked false}]
   [:button {:on-click {:event [:cart/remove id] :prevent-default true
                        :capture true}}
    "remove"]
   [:button {:on-click (if on? [:mode/a] [:mode/b])} "dyn-vec"]
   [:button {:on-click (when on? [:mode/only])} "dyn-nil"]
   [:ul (for [k ks] [:li {:key k :on-click [:list/refresh]} k])]])

(defview with-defaults
  "Q2 :or defaults — absent slot takes the default, present-nil stays
  nil on BOTH hosts."
  [{:keys [a b] :or {a "A" b "B"}}]
  [:div.defs [:span.a a] [:span.b b]])

(defview as-props
  ":as materialization + namespaced slots — encode/decode round-trip."
  [{:cart/keys [item] :keys [plain] :as all}]
  [:div.asv
   [:span.item (str item)]
   [:span.plain plain]
   [:span.n (count all)]])

(defview accepts-children
  "Children-declaring view."
  [{:keys [title children]}]
  [:section.acc [:h3 title] [:div.body children]])

(defview children-flow
  "Children forwarding — structural on the JVM, one slot on the client."
  [{:keys [t]}]
  [accepts-children {:title t}
   [:p "one"]
   [:p "two & <three>"]])

(defview page
  "Composition case — several corpus views under one root."
  [{:keys [items on?]}]
  [:main.page
   [:header [:h1 "Parity & Sons"]]
   [with-defaults {:a "x"}]
   [children-flow {:t "kids"}]
   [:ul (for [i items] [list-row {:key (:id i) :item i}])]
   [bool-attrs {:on? on? :dl "file.txt"}]])

;; ---------------------------------------------------------------------------
;; Compiled render slots (S3, rf2-ri0k6n) — the v-table shape end to end
;; ---------------------------------------------------------------------------

(defview slot-table
  "The LIBRARY seam: it owns the rows and iterates, invoking the caller's row
  renderer per row through ui/slot. The renderer arrives as a compiled
  ui/render-fn value (an opaque prop here); ui/slot proves it and renders its
  output as an ordinary keyed child. A nil renderer renders an empty cell."
  [{:keys [rows render]}]
  [:table.slotted
   [:tbody
    (for [[i r] (map-indexed vector rows)]
      [:tr {:key (:id r)}
       [:td.idx (str i)]
       [:td.body (ui/slot render i r)]])]])

(defview slot-consumer
  "The CONSUMER: supplies the row renderer as a compiled render-fn whose body
  is lexically visible HERE and therefore compiled (both emitters). The body
  is a PURE render fragment of its (index row) args — the v-table row-renderer
  shape end to end."
  [{:keys [rows]}]
  [slot-table {:rows rows
               :render (ui/render-fn [index row]
                         [:span.cell (str index "=" (:name row))])}])

(defview slot-empty
  "A nil slot renders nothing (the empty-cell arm)."
  [{:keys [rows]}]
  [slot-table {:rows rows :render nil}])

(defview slot-bad-arity-consumer
  "rf2-ckviw end-to-end fixture: the consumer supplies a ONE-parameter renderer,
  but slot-table invokes `(ui/slot render i r)` with TWO args. The mismatch is not
  statically visible here (a prop-carried render-fn), so the carrier records arity
  1 and the slot seam's `check-slot-arity!` fails didactically BEFORE invocation on
  both hosts — never the native quirk (JS drops the surplus arg; the JVM throws
  ArityException). NOT a member of `views`/`cases` — it throws on render by design."
  [{:keys [rows]}]
  [slot-table {:rows rows
               :render (ui/render-fn [only-row]
                         [:span.cell (:name only-row)])}])

;; ---------------------------------------------------------------------------
;; Component-library PROOF PACK (readiness §7; EP-0035) — representative
;; library-shaped components donated to the parity corpus as a rolling consumer
;; (the substrate takes no re-com build dependency). These prove CROSS-HOST
;; STRUCTURAL equivalence of the component-library shapes; the behavioural
;; guarantees ride the owning gates (controlled-input sync door / IME / caret =
;; G-8; multi-writer local = G-15; safe-spread deny = G-17; slot purity = G-16;
;; manifest + production absence = G-7/G-11; single-view import = G-18).
;; ---------------------------------------------------------------------------

(defview pp-controlled-input
  "Proof pack — reusable controlled text input (the event-prefix control's
  STRUCTURAL shell; the synchronous door + IME/caret behaviour is G-8). A
  literal :value co-present with the handler is the controlled site."
  [{:keys [value]}]
  [:input.pp-input {:type :text :value value :read-only true
                    :on-input [:pp/typed :rf.ui/value]}])

(defview pp-list-cell
  "Proof pack — a reusable list rendering each row through an INLINE compiled
  render slot (keys/occurrences preserved; JVM structure faithful)."
  [{:keys [rows]}]
  [:ul.pp-list
   (for [[i r] (map-indexed vector rows)]
     [:li.pp-cell {:key (:id r)}
      (ui/slot (ui/render-fn [idx row] [:span.pp-body (str idx ":" (:name row))]) i r)])])

(defview pp-safe-form-control
  "Proof pack — a form control forwarding caller attrs through the spread-safe
  policy onto its owned input: owned props win, aria-*/data-* pass through, the
  controlled site is retained."
  [{:keys [value attrs]}]
  [:input.pp-form (ui/spread-safe {:type :text :value value :read-only true
                                   :on-input [:pp/typed :rf.ui/value]}
                                  attrs)])

(defview pp-schema-described
  "Proof pack — a schema-described component: its literal props schema drives the
  view-manifest projection (Story/docs/Xray) and is proven production-absent
  under G-7/G-11. Structurally it is ordinary markup."
  {:props [:map
           [:label {:doc "the field label"} :string]
           [:hint {:doc "optional helper text" :optional true} :string]]}
  [{:keys [label hint]}]
  [:div.pp-described
   [:span.pp-label label]
   (when hint [:small.pp-hint hint])])

;; ---------------------------------------------------------------------------
;; Cases — pure data; the SINGLE corpus both hosts consume
;; ---------------------------------------------------------------------------

(def items-3
  [{:id 1 :label "escape <b>&\"bold\"</b> 'q'" :hot? true :priority :high}
   {:id 2 :label "ampers&nd" :hot? false :priority :med}
   {:id 3 :label "plain" :hot? false :priority :low}])

(def views
  "case :view keyword -> the compiled view (host realisation)."
  {:static-kitchen  static-kitchen
   :class-forms     class-forms
   :text-forms      text-forms
   :branches        branches
   :lists           lists
   :fragments       fragments
   :booleans        bool-attrs
   :form-controls   form-controls
   :svg-shapes      svg-shapes
   :math-shapes     math-shapes
   :custom-elements custom-elements
   :spread-view     spread-view
   :trusted         trusted
   :handlers        handlers
   :with-defaults   with-defaults
   :as-props        as-props
   :children-flow   children-flow
   :slot-consumer   slot-consumer
   :slot-empty      slot-empty
   :pp-controlled-input   pp-controlled-input
   :pp-list-cell          pp-list-cell
   :pp-safe-form-control  pp-safe-form-control
   :pp-schema-described   pp-schema-described
   :page            page})

(def cases
  "The corpus: {:id case-kw :view view-kw :props edn}. Props are pure
  EDN — the same value renders the CLJS realisation (encoded per the
  props ABI) and the JVM realisation (the map itself)."
  [{:id :static-kitchen       :view :static-kitchen  :props {}}
   {:id :class-static         :view :class-forms     :props {:flags ["f1" nil :f2] :extra "solo" :on? true}}
   {:id :class-map-dyn        :view :class-forms     :props {:flags {:zz true :aa true :mm false} :extra ["ex1" :ex2] :on? false}}
   {:id :text-small           :view :text-forms      :props {:n 3 :msg "tail" :show? true}}
   {:id :text-edges           :view :text-forms      :props {:n -7.0 :msg "e&<t>" :show? false}}
   {:id :branches-a-big       :view :branches        :props {:kind :a :n 42}}
   {:id :branches-b-small     :view :branches        :props {:kind :b :n 1}}
   {:id :branches-default     :view :branches        :props {:kind :z :n 11}}
   {:id :lists-full           :view :lists           :props {:items [{:id 1 :label "a" :hot? true :priority :high}
                                                                     {:id 2 :label "b" :hot? false :priority :low}]
                                                             :groups [{:id "g1" :xs [1 2]} {:id "g2" :xs [3]}]
                                                             :caps [0 1 2 3 0]}}
   {:id :lists-escape         :view :lists           :props {:items [{:id 9 :label "esc <&> \"q\"" :hot? true :priority :high}]
                                                             :groups [] :caps []}}
   {:id :fragments-on         :view :fragments       :props {:pairs [{:k "k1" :v "v1"} {:k "k2" :v "v2"}] :show? true}}
   {:id :fragments-off        :view :fragments       :props {:pairs [] :show? false}}
   {:id :booleans-on          :view :booleans        :props {:on? true :dl "file.txt"}}
   {:id :booleans-off         :view :booleans        :props {:on? false :dl false}}
   {:id :form-controls-b      :view :form-controls   :props {:txt "typed & <kept>" :on? true :pick "b"}}
   {:id :form-controls-text   :view :form-controls   :props {:txt "line" :on? false :pick "gamma"}}
   {:id :svg-small            :view :svg-shapes      :props {:w 24}}
   {:id :math                 :view :math-shapes     :props {}}
   {:id :custom-elements      :view :custom-elements :props {:accent "#00f" :flag true}}
   {:id :custom-elements-off  :view :custom-elements :props {:accent "red" :flag false}}
   {:id :spread-override      :view :spread-view     :props {:extra {:class "x" :tab-index 9}}}
   {:id :spread-add           :view :spread-view     :props {:extra {:data-b "c" :title "t & t"}}}
   {:id :trusted              :view :trusted         :props {:markup "<b>bold & raw</b><i>i</i>"}}
   {:id :handlers-on          :view :handlers        :props {:id 42 :on? true :ks ["k1" "k2"]}}
   {:id :handlers-off         :view :handlers        :props {:id 7 :on? false :ks []}}
   {:id :defaults-absent      :view :with-defaults   :props {}}
   {:id :defaults-present-nil :view :with-defaults   :props {:a "x" :b nil}}
   {:id :as-props             :view :as-props        :props {:cart/item 7 :plain "p"}}
   {:id :children-flow        :view :children-flow   :props {:t "T"}}
   {:id :slot-vtable          :view :slot-consumer   :props {:rows [{:id 1 :name "a & <b>"}
                                                                    {:id 2 :name "second"}
                                                                    {:id 3 :name "third"}]}}
   {:id :slot-empty           :view :slot-empty      :props {:rows [{:id 1 :name "x"}
                                                                    {:id 2 :name "y"}]}}
   {:id :pp-controlled-input  :view :pp-controlled-input :props {:value "typed & <kept>"}}
   {:id :pp-list-cell         :view :pp-list-cell       :props {:rows [{:id 1 :name "a & <b>"}
                                                                       {:id 2 :name "second"}]}}
   {:id :pp-safe-form-control :view :pp-safe-form-control :props {:value "v & <x>"
                                                                  :attrs {:aria-label "field"
                                                                          :data-testid "pp"
                                                                          :class "extra"}}}
   {:id :pp-schema-described  :view :pp-schema-described :props {:label "Name" :hint "your full name"}}
   {:id :page                 :view :page            :props {:items items-3 :on? true}}])
