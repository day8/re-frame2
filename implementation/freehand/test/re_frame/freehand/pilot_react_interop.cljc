(ns re-frame.freehand.pilot-react-interop
  "The F5i pilot's APPLICATION — a React-library integration written the way
  an adopter would write one, using only the public door.

  The question this pilot exists to answer is the first question any real
  adopter asks: *can I use a third-party React component?* Freehand's answer
  is deliberately not \"yes, anywhere\" — it is a small set of named host
  shapes, each with a different bargain. What follows is one worked
  integration per shape that EXISTS, and a named refusal per shape that does
  not, so the day a shape lands the refusal fails and this file has to be
  rewritten rather than quietly staying green.

  ## The shapes, and what an adopter meets today

  | Shape                        | What it is for                          |
  |------------------------------|-----------------------------------------|
  | qualified host leaf          | a foreign component with value props    |
  | explicit React wrapper       | a React-owned protocol (hooks, context) |
  | registered behavior          | opaque, mutable host state over a node  |
  | the outward bridge           | a library that wants a COMPONENT value  |

  Exactly one of those four — the registered behavior — is reachable from
  the door in this tree. The other three are named by the substrate's own
  diagnostics as landing later, and this pilot asserts those diagnostics
  verbatim rather than routing around them.

  ## What that leaves an adopter with

  One shape, and it is a good one: `v/defbehavior` + `[v/behavior …]` is a
  genuinely complete imperative boundary — commit-only connection, a closed
  timing set, a bounded command roster addressed by a semantic id the caller
  authored, and a teardown a test can assert as an exact zero. The
  spreadsheet-class integration below is built entirely on it, and nothing
  about it feels like a workaround.

  What it is NOT is a React boundary. A behavior receives a DOM node, not a
  React parent, so the only way to put a third-party REACT component on the
  page from Freehand today is to open a SECOND React root inside the node
  the behavior owns. That works — `pilot-react-interop-dom-cljs-test` mounts
  a real `@xyflow/react` graph that way — and it is a workaround, with costs
  this pilot measures rather than asserts away.

  ## The four integrations in this file

    1. `acme.sheet`     a SpreadJS-class editable grid: opaque host state,
                        a reconciled config, a bounded command roster, an
                        outward intent, and a total release. THE behavior
                        shape, used for exactly what it was designed for.
    2. `acme.chart`     a React-Vega-class chart component. The shape that
                        SHOULD be a qualified leaf; reached instead through
                        a nested React root inside an opaque behavior. The
                        CLJS half lives in `pilot-react-interop-react`,
                        because a `.cljc` cannot `:require` an npm module.
    3. `acme.table`     a TanStack-Table-class HEADLESS core. The finding
                        here is that it needs no host shape at all — the
                        React adapter's whole job (hold state, re-render on
                        change) is what re-frame already is.
    4. `acme.dialog`    a Radix-class compound component. Not reachable, and
                        not reachable for a second reason beyond the missing
                        leaf: `asChild` needs to clone a Freehand child and
                        inject a ref into it, and a Freehand element is not
                        a React element an application can hold.

  Everything here is host-neutral. The imperative widget touches the DOM
  only under a `:cljs` reader conditional and only when it really has a
  node, so the SAME declarations render structurally on the JVM — where the
  behavior is an inert marker, which is itself one of the claims."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]))

;; ===========================================================================
;; INTEGRATION 1 — `acme.sheet`, a SpreadJS-class editable grid
;; ===========================================================================
;;
;; The library. A faithful stand-in for the SpreadJS / Handsontable / Monaco
;; class of widget: constructed over a node it then owns, mutated in place,
;; emitting host events through a listener the caller registers, and released
;; by an explicit `destroy`. It is deliberately NOT React — that is the whole
;; point of the class, and it is why the behavior shape exists.
;;
;; Every allocation it makes is counted, because acceptance for this pilot is
;; an EXACT retained count after unmount and not a plausible one.

(def ^:private ledger
  "What the library is holding right now. `:instances` and `:listeners` are
  the numbers a cleanup assertion reads; `:constructed` and `:destroyed` are
  cumulative, so a replayed connect/disconnect pair is visible as movement
  in the totals while the live counts stay at zero."
  (atom {:instances 0 :listeners 0 :constructed 0 :destroyed 0}))

(defn ledger-snapshot [] @ledger)

(defn reset-ledger!
  []
  (reset! ledger {:instances 0 :listeners 0 :constructed 0 :destroyed 0})
  nil)

(defn live-instances [] (:instances @ledger))
(defn live-listeners [] (:listeners @ledger))

#?(:cljs
   (defn- render-grid!
     "Paint the instance's rows into the node it owns. The widget owns every
      descendant of that node — which is exactly what `{:opaque true}`
      declares on the Freehand side."
     [inst]
     (let [node  (:node inst)
           state @(:state inst)]
       (set! (.-innerHTML node) "")
       (let [table (js/document.createElement "table")]
         (.setAttribute table "class" "acme-sheet")
         (doseq [[i row] (map-indexed vector (:rows state))]
           (let [tr (js/document.createElement "tr")]
             (.setAttribute tr "data-row" (str i))
             (doseq [[j cell] (map-indexed vector row)]
               (let [td (js/document.createElement "td")]
                 (.setAttribute td "data-col" (str j))
                 (when (= [i j] (:cursor state))
                   (.setAttribute td "data-cursor" "true"))
                 (set! (.-textContent td) (str cell))
                 (.appendChild tr td)))
             (.appendChild table tr)))
         (.appendChild node table))
       nil)))

#?(:cljs
   (defn create-sheet!
     "`new acme.Sheet(node, options)` — the library's constructor. Takes the
      node, paints itself into it, installs ONE host listener, and answers an
      opaque instance handle."
     [node {:keys [rows read-only?]}]
     (let [state    (atom {:rows (or rows []) :cursor [0 0] :read-only? read-only?})
           inst     {:node node :state state :sink (atom nil)}
           listener (fn [e]
                      (let [td (.-target e)]
                        (when (and (some? td) (= "TD" (.-tagName td)))
                          (let [r (js/parseInt (.getAttribute (.-parentNode td) "data-row") 10)
                                c (js/parseInt (.getAttribute td "data-col") 10)]
                            (swap! state assoc :cursor [r c])
                            (render-grid! inst)
                            (when-let [sink @(:sink inst)]
                              (sink {:row r :col c :value (.-textContent td)}))))))
           inst     (assoc inst :listener listener)]
       (.addEventListener node "click" listener)
       (swap! ledger (fn [l] (-> l
                                 (update :instances inc)
                                 (update :listeners inc)
                                 (update :constructed inc))))
       (render-grid! inst)
       inst)))

#?(:cljs
   (defn set-rows!
     [inst rows]
     (swap! (:state inst) assoc :rows rows)
     (render-grid! inst)
     nil))

#?(:cljs
   (defn on-cell-activated!
     "Register the ONE outward callback the library offers."
     [inst f]
     (reset! (:sink inst) f)
     nil))

#?(:cljs
   (defn sheet-export
     "A one-shot imperative operation with a result — the exact case the
      bounded command channel exists for."
     [inst {:keys [separator]}]
     (->> (:rows @(:state inst))
          (map (fn [row] (str/join (or separator ",") (map str row))))
          (str/join "\n"))))

#?(:cljs
   (defn focus-cell!
     [inst [r c]]
     (swap! (:state inst) assoc :cursor [r c])
     (render-grid! inst)
     nil))

#?(:cljs
   (defn destroy-sheet!
     "`instance.destroy()` — release everything the constructor took."
     [inst]
     (.removeEventListener (:node inst) "click" (:listener inst))
     (reset! (:sink inst) nil)
     (set! (.-innerHTML (:node inst)) "")
     (swap! ledger (fn [l] (-> l
                               (update :instances dec)
                               (update :listeners dec)
                               (update :destroyed inc))))
     nil))

;; ---------------------------------------------------------------------------
;; The Freehand side — ONE registered behavior
;; ---------------------------------------------------------------------------
;;
;; `:timing :layout`, because a grid measures its own column widths and a
;; measurement that lands after paint is visibly wrong for one frame.
;;
;; `{:opaque true}`, because the widget owns every descendant of its node.
;; Declaring that is what turns "children here would be silently overwritten"
;; into a loud refusal at the use site.
;;
;; NOTE what is NOT in `:config`: the constructor, the instance, the
;; listener, the outward callback. `:config` is data through and through, so
;; the behavior's job is exactly "read data, drive the host" — which is the
;; boundary a chart or grid library wants anyway.

(v/defbehavior sheet
  "A SpreadJS-class editable grid over one node."
  {:timing  :layout
   :opaque  true
   :connect (fn [{:keys [node config dispatch]}]
              #?(:cljs
                 (let [inst (create-sheet! node config)]
                   ;; The host's outward event leaves as an ordinary event
                   ;; INTENT. The behavior does not decide anything — it
                   ;; conveys, and the re-frame handler that receives it sees
                   ;; the committed frame.
                   (on-cell-activated! inst
                     (fn [{:keys [row col value]}]
                       (dispatch [:invoice/cell-activated row col value])))
                   inst)
                 :clj (do node config dispatch nil)))
   :update  (fn [{:keys [config memory]}]
              #?(:cljs (when memory (set-rows! memory (:rows config))))
              memory)
   :disconnect (fn [{:keys [memory]}]
                 #?(:cljs (when memory (destroy-sheet! memory)))
                 nil)
   :commands {:export     (fn [{:keys [memory args dispatch]}]
                            #?(:cljs
                               (when memory
                                 (dispatch [:invoice/exported (sheet-export memory args)])))
                            memory)
              :focus-cell (fn [{:keys [memory args]}]
                            #?(:cljs (when memory (focus-cell! memory (:at args))))
                            memory)}})

;; ---------------------------------------------------------------------------
;; The application that uses it
;; ---------------------------------------------------------------------------

(def rows-path [:invoice :rows])

(rf/reg-sub :invoice/rows (fn [db _] (get-in db rows-path)))
(rf/reg-sub :invoice/last-export (fn [db _] (get-in db [:invoice :last-export])))
(rf/reg-sub :invoice/last-activation (fn [db _] (get-in db [:invoice :last-activation])))

(rf/reg-event :invoice/seeded
  (fn [{:keys [db]} [_ rows]]
    {:db (assoc-in db rows-path rows)}))

(rf/reg-event :invoice/cell-activated
  (fn [{:keys [db]} [_ row col value]]
    {:db (assoc-in db [:invoice :last-activation] {:row row :col col :value value})}))

(rf/reg-event :invoice/exported
  (fn [{:keys [db]} [_ csv]]
    {:db (assoc-in db [:invoice :last-export] csv)}))

;; The command is DATA an ordinary handler returns. Nothing in the view
;; reaches a host, nothing holds an instance, and the address is the semantic
;; id the use site authored — not a ref, not a query, not a position.
(rf/reg-event :invoice/export-requested
  (fn [_ [_ separator]]
    {:fx [[:re-frame.freehand.host/command
           {:target :invoice/sheet
            :op     :export
            :args   {:separator separator}}]]}))

(rf/reg-event :invoice/cell-focus-requested
  (fn [_ [_ at]]
    {:fx [[:re-frame.freehand.host/command
           {:target :invoice/sheet :op :focus-cell :args {:at at}}]]}))

(v/defview invoice-sheet
  "The use site. Everything visible here is data: an id, a semantic target,
  and a config the substrate records verbatim in the structural tree."
  [{:keys [read-only?]}]
  [:section.invoice
   [v/behavior {:use    sheet
                :target :invoice/sheet
                :config {:rows       (v/sub [:invoice/rows])
                         :read-only? (boolean read-only?)}}
    [:div.sheet-host]]])

(v/defview invoice-page
  "The sheet with an ordinary Freehand toolbar above it — the shape that
  proves the boundary is only around the node the widget owns, not around
  the region."
  [_]
  [:main.invoice-page
   [:button.export {:on-click [:invoice/export-requested ","]} "Export"]
   [invoice-sheet {}]])

(v/defview two-sheets
  "Two live instances under DISTINCT semantic ids — the ordinary
  multi-instance case, and the decoy arm of the command law."
  [_]
  [:section.pair
   [v/behavior {:use sheet :target :invoice/sheet :config {:rows [["a"]]}}
    [:div.sheet-host {:data-id "primary"}]]
   [v/behavior {:use sheet :target :invoice/draft :config {:rows [["z"]]}}
    [:div.sheet-host {:data-id "decoy"}]]])

(v/defview control-page
  "The CONTROL: the same markup with no behavior at all, so a zero after
  teardown means a release rather than a counter that was never written."
  [_]
  [:section.invoice
   [:div.sheet-host]])

;; ===========================================================================
;; INTEGRATION 3 — `acme.table`, a TanStack-Table-class HEADLESS core
;; ===========================================================================
;;
;; This one is the finding that is NOT a gap.
;;
;; A headless table library is a pure function: options and state in, a row
;; model out. Its React adapter exists to supply the two things React itself
;; cannot: somewhere to keep the state, and a way to re-run the computation
;; when that state moves. re-frame already IS both of those, so there is
;; nothing for a host shape to do — the core belongs in a subscription, and
;; the rows it answers are ordinary Freehand markup.
;;
;; `@tanstack/table-core` is not in this repo's dependency tree and adding an
;; npm dependency for a pilot is a decision, not an implementation detail. So
;; the core below is a LOCAL stand-in with the same ABI — `(core-row-model
;; options state) -> {:rows … :header …}`, pure, framework-free — and the
;; claim it supports is about the SHAPE of the integration, which is what the
;; pilot is judging.

(defn core-row-model
  "The headless core: options + state in, a row model out. No framework, no
  host, no identity — the same call answers the same value on both hosts."
  [{:keys [columns data]} {:keys [sort-by-key desc?]}]
  (let [sorted (if sort-by-key
                 (let [s (sort-by #(get % sort-by-key) data)]
                   (vec (if desc? (reverse s) s)))
                 (vec data))]
    {:header (mapv (fn [{:keys [key label]}] {:key key :label label}) columns)
     :rows   (mapv (fn [row]
                     {:id    (:id row)
                      :cells (mapv (fn [{:keys [key]}] {:key key :value (get row key)})
                                   columns)})
                   sorted)}))

(def ^:private table-columns
  [{:key :name  :label "Name"}
   {:key :owed  :label "Owed"}])

(rf/reg-sub :ledger/table
  (fn [db _]
    (core-row-model {:columns table-columns
                     :data    (get-in db [:ledger :rows] [])}
                    (get-in db [:ledger :sort] {}))))

(rf/reg-event :ledger/seeded
  (fn [{:keys [db]} [_ rows]] {:db (assoc-in db [:ledger :rows] rows)}))

(rf/reg-event :ledger/sorted
  (fn [{:keys [db]} [_ k]]
    {:db (update-in db [:ledger :sort]
                    (fn [{:keys [sort-by-key desc?]}]
                      {:sort-by-key k :desc? (and (= k sort-by-key) (not desc?))}))}))

(v/defview headless-table
  "A headless table core rendered as ORDINARY Freehand markup. No behavior,
  no leaf, no bridge, no wrapper — the integration needed no host shape at
  all, which is the honest answer for this whole class of library."
  [_]
  (let [{:keys [header rows]} (v/sub [:ledger/table])]
    [:table.ledger
     [:thead
      [:tr (for [{:keys [key label]} header]
             ^{:key key} [:th {:on-click [:ledger/sorted key]} label])]]
     [:tbody
      (for [{:keys [id cells]} rows]
        ^{:key id}
        [:tr {:data-row-id (str id)}
         (for [{:keys [key value]} cells]
           ^{:key key} [:td {:data-col (name key)} (str value)])])]]))

;; ===========================================================================
;; Refusal probes — the shapes that are NOT reachable
;; ===========================================================================
;;
;; Each of these is a use site an adopter would write on their first day.
;; They are declared here rather than inline in the suite so the refusal is
;; measured against a real declaration, and so the day a shape lands the
;; declaration is already written and the assertion around it fails loudly.

(def foreign-leaf
  "A hand-built value shaped like the third legal vector head.

  There is no public verb that mints one — `re-frame.freehand.descriptor`
  reserves the `:re-frame.freehand/host` marker and says the authoring
  surface `lands with its own slice`. This map is the pilot reaching PAST the
  door on purpose, so the refusal it meets is the substrate's own and not a
  spelling mistake."
  {:re-frame.freehand/host true
   :component              "AcmeChart"})

(v/defview chart-as-a-leaf
  "The obvious spelling for a React chart component with value props, and
  the one an adopter reaches for first."
  [_]
  [:figure.chart
   [foreign-leaf {:spec "bar" :data [1 2 3]}]])

(v/defview opaque-host-with-children
  "The refusal that keeps an opaque behavior honest: children under a node
  the host owns would be rendered and then overwritten."
  [_]
  [v/behavior {:use sheet :target :invoice/sheet}
   [:div.sheet-host [:span "the widget would erase this"]]])

(v/defview behavior-over-a-view
  "A behavior's child is ONE element. A declared view denotes a group, so
  there is nothing to attach to — the arm an adopter meets when they try to
  wrap a component in a behavior instead of a node."
  [_]
  [v/behavior {:use sheet :target :invoice/sheet}
   [control-page {}]])
