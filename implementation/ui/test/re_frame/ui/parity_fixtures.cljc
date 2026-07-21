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

  S4 additions (W14; rf2-yho9j — the view-shape sweep): `ui/presence`
  (the retention boundary's STRUCTURAL contribution — it renders no node
  of its own and every incoming keyed child renders exactly once), the
  RULED custom-element adversarial row (a DECLARED name that is also a
  standard HTML attribute spelling stays a PROPERTY on both emitters),
  and the `ui/html` no-sanitisation row (a hostile `javascript:` scheme
  crosses verbatim — `re-frame.ui` gates nothing).

  OUT OF SCOPE for the corpus (documented, not silently skipped):
  foreign components and `ui/raw` (JVM host-op — no JVM execution by
  contract, so there is no JVM side to compare; their boundary corpus is
  raw_foreign_boundary_{jvm,dom_cljs}_test); `ui/client-only` (the SSR
  phase flip lands S5); presence's THREE-PHASE machine and any
  `(ui/presence-phase)` read (host-bearing — retention timers and the
  first-render phase a server render reports are mounted/S5 questions,
  proven in presence_dom_cljs_test and presence_jvm_test)."
  (:require [re-frame.ui :as ui :refer [defview]]))

;; ---------------------------------------------------------------------------
;; Custom-element declaration (RULED grammar) — corpus-owned tag
;; ---------------------------------------------------------------------------

(ui/custom-element :parity-widget {:properties #{:accent-color}})

;; S4-B adversarial declaration (rf2-yho9j): `:tab-index` is a standard HTML
;; attribute spelling, but DECLARING it makes it a PROPERTY — the declaration is
;; the sole classifier, never an attribute-name heuristic. Both emitters must
;; therefore keep it OUT of markup.
(ui/custom-element :parity-attrish {:properties #{:tab-index}})

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

(defview conditional-root
  "rf2-hac8p (98.1 slice c): the view's HOST ROOT is a conditional. Each concrete
  DOM arm is a single compiler-owned element, so BOTH emitters must annotate
  whichever arm renders IDENTICALLY — the host-root view-evidence annotation
  (`data-rf2-source-coord` + `data-rf-view`) is inherently cross-emitter, so a
  one-sided conditional-root descent diverges here (the exact class that reopened
  the bead). The `branches` view exercises conditionals MID-TREE; this exercises
  them at the ROOT."
  [{:keys [big?]}]
  (if big? [:div.cr-big "big"] [:section.cr-small "small"]))

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
      ^{:rf.ui/suppress
        {:rf.ui.compile/a11y-click-non-interactive
         "parity fixture: this row pins keyed-for lowering and the exact
          host-to-host HTML, so the element must stay an <li> with a handler"}}
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

(defview spread-trusted
  "ui/spread + ui/html COMPOSE (rf2-29s75): a runtime-built prop map and a
  trusted-markup sole child on ONE element — BOTH take effect on BOTH emitters.
  The overrides deliberately COLLIDE with a base prop (:title) and add to the
  sugar class, so the spread merge is exercised alongside the raw markup. The
  CLJS emitter used to drop the markup here while the JVM emitter kept it."
  [{:keys [extra markup]}]
  [:div.raw (ui/spread {:title "base title" :data-a "b" :class "base"} extra)
   (ui/html markup)])

(defview safe-spread-trusted
  "ui/spread-safe + ui/html — the sibling spread form, which already composed
  (its branch builds owned props through element-prop-pairs). Pinned here so
  the two spread forms are proven to agree with each other AND across hosts."
  [{:keys [attrs markup]}]
  [:div.safe-raw (ui/spread-safe {:class "owned" :data-owned "o"} attrs)
   (ui/html markup)])

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
   [:ul (for [k ks]
          ^{:rf.ui/suppress
            {:rf.ui.compile/a11y-click-non-interactive
             "parity fixture: pins a capture-free event vector shared across
              rows, and the HTML side pins no-handler-attributes-ever"}}
          [:li {:key k :on-click [:list/refresh]} k])]])

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
;; Library-shaped grammar rows (rf2-kxork)
;;
;; These three rows USED to be named `pp-*` and documented as the
;; "component-library PROOF PACK … donated to the parity corpus", which read as
;; though this corpus covered `re-frame.ui.proof-pack.library`. IT NEVER DID.
;; They are independent re-implementations with materially different markup and
;; props, so the framing was an assertions-decoupled-from-reality claim: the
;; corpus asserted on these while the real library went unrendered. The claim is
;; retired here; the rows survive on their own grammar merit, under names that
;; describe what they actually are.
;;
;; The real library is now RENDERED AND EXERCISED — all six views, on a real
;; React root — by `re-frame.ui.proof-pack.library-dom-cljs-test`. It cannot be
;; a member of THIS corpus: the corpus is dual-emitter `.cljc` (every row must
;; also render on the JVM), the library is `.cljs`, and four of its six views
;; are host-bearing (`ui/sub`, `ui/local` + `ui/handler`, `ui/effect :connect`)
;; and so have no JVM side to compare.
;;
;; A fourth row, `pp-controlled-input`, was RETIRED outright rather than
;; renamed: its shape (a literal `:value` co-present with an `:on-input`
;; handler, plus `:read-only`) is already covered exactly by `form-controls`
;; rows 1 and 3, so nothing is left uncovered by its removal.
;; ---------------------------------------------------------------------------

(defview inline-slot-render-fn
  "Grammar row: a list rendering each row through a render-fn constructed
  INLINE at the `ui/slot` call site — the complement of `slot-consumer`, which
  carries its render-fn as a PROP. Keys/occurrences preserved; JVM structure
  faithful. NOT the proof-pack library's `list-cell` (that view takes `render`
  as a prop; it is exercised in library-dom-cljs-test)."
  [{:keys [rows]}]
  [:ul.slot-list
   (for [[i r] (map-indexed vector rows)]
     [:li.slot-cell {:key (:id r)}
      (ui/slot (ui/render-fn [idx row] [:span.slot-body (str idx ":" (:name row))]) i r)])])

(defview safe-spread-controlled
  "Grammar row: `ui/spread-safe` over a CONTROLLED owned map — the clause
  `safe-spread-trusted` does not reach, since its owned map carries no
  `:value`. Owned props win, aria-*/data-* pass through, and the literal
  `:value` co-present with the handler keeps the sync door across the spread.
  NOT the proof-pack library's `safe-form-control` (that view sources its value
  from `ui/sub`, not a prop; it is exercised in library-dom-cljs-test)."
  [{:keys [value attrs]}]
  [:input.safe-form (ui/spread-safe {:type :text :value value :read-only true
                                     :on-input [:corpus/typed :rf.ui/value]}
                                    attrs)])

(defview schema-described-markup
  "Grammar row: the corpus's only view carrying a LITERAL props schema, pinning
  that a schema-described view renders identically on both hosts (the schema is
  manifest/validation metadata and must not perturb output). NOT the proof-pack
  library's `schema-described`, whose schema is `{:closed true}` over `:label`
  ALONE and whose markup is a single `<label>` — this row's `:hint` prop does
  not exist there and would violate that closed schema."
  {:props [:map
           [:label {:doc "the field label"} :string]
           [:hint {:doc "optional helper text" :optional true} :string]]}
  [{:keys [label hint]}]
  [:div.described
   [:span.described-label label]
   (when hint [:small.described-hint hint])])

;; ---------------------------------------------------------------------------
;; S4 corpus additions (W14; rf2-yho9j) — the S4 surfaces that HAVE two
;; emitter sides to compare. Presence's retention machine, `ui/raw` and the
;; foreign head do not (see the ns docstring); what IS dual-emitter is the
;; STRUCTURAL contribution each S4 form makes to the rendered output.
;; ---------------------------------------------------------------------------

(defview toast-row
  "A presence child as a compiled VIEW — the shape S4-C's a11y roster blesses
  (a keyed child view can read its own phase and stamp its own exit a11y). It
  deliberately does NOT read (ui/presence-phase): the phase a FIRST render
  reports is host-specific (the JVM structural subset says :present; a client
  render enters through :mounting), so it is not a corpus claim."
  [{:keys [toast]}]
  [:li.toast [:span.msg (:msg toast)]])

(defview presence-toasts
  "ui/presence over keyed compiled child views. The corpus claim is that the
  boundary contributes NO NODE OF ITS OWN and passes every incoming keyed child
  through exactly once: on the JVM it is a fragment carrying the `:rf.ui/presence`
  diagnostic marker, which normalization N strips; on the client it is a
  component whose per-child phase Provider is invisible in markup. Both sides
  must therefore land on the same children in the same order."
  [{:keys [toasts]}]
  (ui/presence {:timeout-ms 300}
    (for [t toasts]
      [toast-row {:key (:id t) :toast t}])))

(defview presence-inline
  "Presence nested MID-TREE over inline keyed literal markup (the second legal
  child shape). Non-focusable content, so the S4-C `a11y-presence-exit-interactive`
  check stays silent — the corpus is not the place to exercise a diagnostic."
  [{:keys [items]}]
  [:section.presence-host
   [:h4 "notes"]
   [:ul.notes
    (ui/presence {:timeout-ms 120}
      (for [i items]
        [:li.note {:key (:id i) :data-note (:id i)} (:label i)]))]
   [:p.after "after"]])

(defview custom-element-attrish
  "S4-B adversarial classification: `:tab-index` is DECLARED on
  `:parity-attrish`, so it is a PROPERTY (lowered to the camelCase JS property
  name) and neither emitter writes it into markup; the undeclared `:role` and
  `:data-k` stay ordinary attributes on both hosts."
  [{:keys [idx]}]
  [:div.attrish
   [:parity-attrish {:tab-index idx :role "note" :data-k "keep"}]])

;; NOTE (rf2-x1nbv): the host-root AUTHORED-OWN-VALUE-WINS collision law is pinned
;; cross-host by the dedicated per-host tests (authored_collision_cljs_test +
;; authored_collision_jvm_test) asserting IDENTICAL authored values, NOT by a
;; corpus fixture. A collision fixture authors `data-rf-view` /
;; `data-rf2-source-coord` — the annotation spelling — which is an elision
;; sentinel: in the advanced/prod build the compiler annotation is DCE'd but the
;; AUTHORED attr legitimately survives, so the prod-elision corpus's blunt
;; `strip-view-evidence` (which removes those names from the JVM truth) would
;; report a false divergence. Keeping the law out of the shared corpus avoids that.

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
   :conditional-root conditional-root
   :lists           lists
   :fragments       fragments
   :booleans        bool-attrs
   :form-controls   form-controls
   :svg-shapes      svg-shapes
   :math-shapes     math-shapes
   :custom-elements custom-elements
   :spread-view     spread-view
   :trusted         trusted
   :spread-trusted      spread-trusted
   :safe-spread-trusted safe-spread-trusted
   :handlers        handlers
   :with-defaults   with-defaults
   :as-props        as-props
   :children-flow   children-flow
   :slot-consumer   slot-consumer
   :slot-empty      slot-empty
   ;; rf2-kxork — library-shaped grammar rows (formerly the `pp-*` stand-ins)
   :inline-slot-render-fn    inline-slot-render-fn
   :safe-spread-controlled   safe-spread-controlled
   :schema-described-markup  schema-described-markup
   ;; S4 (W14, rf2-yho9j)
   :presence-toasts       presence-toasts
   :presence-inline       presence-inline
   :ce-attrish            custom-element-attrish
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
   {:id :conditional-root-true  :view :conditional-root :props {:big? true}}
   {:id :conditional-root-false :view :conditional-root :props {:big? false}}
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
   ;; rf2-29s75 — spread + trusted markup on one element, both emitters
   {:id :spread-trusted       :view :spread-trusted  :props {:extra {:title "override & <wins>" :class "x" :tab-index 9}
                                                             :markup "<b>bold & raw</b><i>i</i>"}}
   {:id :safe-spread-trusted  :view :safe-spread-trusted :props {:attrs {:aria-label "raw region" :data-c "c" :class "caller"}
                                                                 :markup "<em>em & raw</em>"}}
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
   ;; rf2-kxork — library-shaped grammar rows (formerly the `pp-*` stand-ins)
   {:id :inline-slot-render-fn :view :inline-slot-render-fn :props {:rows [{:id 1 :name "a & <b>"}
                                                                          {:id 2 :name "second"}]}}
   {:id :safe-spread-controlled :view :safe-spread-controlled :props {:value "v & <x>"
                                                                      :attrs {:aria-label "field"
                                                                              :data-testid "sc"
                                                                              :class "extra"}}}
   {:id :schema-described-markup :view :schema-described-markup :props {:label "Name" :hint "your full name"}}
   ;; --- S4 (W14; rf2-yho9j) — the dual-emitter S4 rows -------------------
   {:id :presence-two         :view :presence-toasts :props {:toasts [{:id 1 :msg "a & <b>"}
                                                                      {:id 2 :msg "second"}]}}
   {:id :presence-empty       :view :presence-toasts :props {:toasts []}}
   {:id :presence-inline      :view :presence-inline :props {:items [{:id "n1" :label "one"}
                                                                     {:id "n2" :label "two & <three>"}]}}
   {:id :ce-declared-attr-name :view :ce-attrish     :props {:idx "3"}}
   {:id :trusted-hostile      :view :trusted         :props {:markup "<a href=\"javascript:alert(1)\" target=\"_blank\">click</a><b>bold & raw</b>"}}
   {:id :page                 :view :page            :props {:items items-3 :on? true}}])
