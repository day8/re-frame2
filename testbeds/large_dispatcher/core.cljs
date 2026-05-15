(ns large-dispatcher.core
  "Shared framework-behavior testbed — events whose payloads exceed the
  wire-elision threshold. A consumer (Causa, Story, pair2-mcp)
  observes the runtime's wire-boundary walker substitute a value with
  the `:rf.size/large-elided` marker (per [spec/009 §Size elision in
  traces] / [API.md §`rf/elide-wire-value`]).

  Path-D elision is schema-first: `:large?` on a Malli app-schema slot
  is the declaration path. Unschema'd large values deliberately warn
  but do not auto-elide.

  This surface exercises one warning-only control and three
  schema-declared marker paths:

    Button A · Unschema'd large value (warning-only)
      — handler writes a 20 KiB string to `[:auto-large-value]`. The
        walker emits `:rf.warning/large-value-unschema'd` but leaves
        the value inline because no schema declared the path.

    Button B · Schema-declared flat slot
      — handler writes a small (200 byte) value to
        `[:declared-large-value]`. Elision fires regardless of size
        because the registered app-schema marks the slot `:large?`.

    Button C · Second schema-declared flat slot
      — same outcome as Button B on `[:fx-declared-value]`; it keeps a
        second deterministic path for consumers that assert multiple
        marker rows.

    Button D · Schema-driven (registered at boot)
      — the surface registers a Malli app-schema with `:large? true`
        on the `:schema-large-value` slot at boot. Any write to that
        path triggers elision on emit. Button D writes a 200-byte
        value to the schema-declared slot.

  This is NOT a tutorial. The bodies are minimal. The point is to
  produce deterministic warning-only and schema-elided shapes a
  consumer can assert against."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loads the schemas artefact's late-bind hooks (rf2-p7va).
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; The canonical "large" payload — 20 KiB above the 16 KiB threshold
;; ----------------------------------------------------------------------------
;;
;; `pr-str` of this string exceeds the framework's warning threshold.
;; Path-D schema-first elision leaves it inline and emits a warning so
;; users can add `{:large? true}` to the app-schema slot.

(def kib-20-string
  ;; 20480 chars = 20 KiB. pr-str adds 2 quote chars; effective size
  ;; in the wire shape is 20482 bytes — comfortably above 16 KiB.
  (apply str (repeat 20480 \X)))

;; A small "control" payload — 200 chars. Used by the declared and
;; schema paths to prove elision fires REGARDLESS of size when the
;; path is nominated.
(def chars-200-string
  (apply str (repeat 200 \Y)))

;; ----------------------------------------------------------------------------
;; Malli app-schema with :large? on one slot — exercises the
;; schema-driven nomination path
;; ----------------------------------------------------------------------------

(def SchemaLarge
  [:map
   [:schema-large-value {:large? true :hint "Nested schema-declared slot"}
    [:maybe :string]]])

(rf/reg-app-schemas
  {[:declared-large-value]
   [:maybe [:string {:large? true :hint "Flat schema-declared slot"}]]
   [:fx-declared-value]
   [:maybe [:string {:large? true :hint "Second flat schema-declared slot"}]]
   [:schema-bag]
   SchemaLarge})

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {;; The four slots one button per. All start as nil; clicks
     ;; commit the appropriate large or small value.
     :auto-large-value     nil
     :declared-large-value nil
     :fx-declared-value    nil
     :schema-bag           {:schema-large-value nil}
     ;; A counter per button — tracks how many times each was
     ;; clicked. Allows a Playwright spec to confirm the handler
     ;; body actually ran (the elision is wire-boundary; the
     ;; handler always sees the unredacted value).
     :click-count          {:auto 0 :declared 0 :fx 0 :schema 0}}))

;; ----------------------------------------------------------------------------
;; Button A — unschema'd warning-only path
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::write-auto-large
  (fn [db _ev]
    ;; HOT PATH — commits a 20 KiB value to an undeclared path. The
    ;; schema-first walker warns about unschema'd large values but does
    ;; not substitute a marker unless a schema declares the path.
    (-> db
        (assoc :auto-large-value kib-20-string)
        (update-in [:click-count :auto] inc))))

;; ----------------------------------------------------------------------------
;; Button B — schema-declared flat slot
;; ----------------------------------------------------------------------------
;;
;; The schema marks `[:declared-large-value]` as `:large? true`; the
;; small payload proves declaration, not byte size, drives elision.

(rf/reg-event-db ::write-declared-large
  (fn [db _ev]
    (-> db
        (assoc :declared-large-value chars-200-string)
        (update-in [:click-count :declared] inc))))

;; ----------------------------------------------------------------------------
;; Button C — second schema-declared flat slot
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::write-fx-declared-large
  (fn [db _ev]
    (-> db
        (assoc :fx-declared-value chars-200-string)
        (update-in [:click-count :fx] inc))))

;; ----------------------------------------------------------------------------
;; Button D — schema-driven (boot-time declaration on a Malli slot)
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::write-schema-large
  (fn [db _ev]
    ;; HOT PATH — writes a small payload to a path the schema
    ;; declares :large? true. The runtime read the schema at boot
    ;; (per `populate-elision-from-schemas!`) and seeded
    ;; [:rf/elision :declarations [:schema-bag :schema-large-value]]
    ;; with :source :schema. Elision fires on this path regardless
    ;; of value size.
    (-> db
        (assoc-in [:schema-bag :schema-large-value] chars-200-string)
        (update-in [:click-count :schema] inc))))

;; ----------------------------------------------------------------------------
;; Reset
;; ----------------------------------------------------------------------------

(rf/reg-event-fx ::reset
  (fn [_ctx _ev]
    {:fx [[:dispatch [::initialise]]]}))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :auto-len      (fn [db _] (count (str (:auto-large-value db)))))
(rf/reg-sub :declared-len  (fn [db _] (count (str (:declared-large-value db)))))
(rf/reg-sub :fx-len        (fn [db _] (count (str (:fx-declared-value db)))))
(rf/reg-sub :schema-len    (fn [db _] (count (str (get-in db [:schema-bag :schema-large-value])))))
(rf/reg-sub :auto-count    (fn [db _] (get-in db [:click-count :auto])))
(rf/reg-sub :declared-count (fn [db _] (get-in db [:click-count :declared])))
(rf/reg-sub :fx-count      (fn [db _] (get-in db [:click-count :fx])))
(rf/reg-sub :schema-count  (fn [db _] (get-in db [:click-count :schema])))

;; Read the elision-declarations slot directly so the view shows the
;; registrar's view of what's been nominated. A spec asserts this
;; reads the same shape as `(rf/elision-declarations frame-id)`.
(rf/reg-sub :elision-decls
  (fn [db _] (get-in db [:rf/elision :declarations])))

(reg-view buttons []
  (let [auto-len       @(subscribe [:auto-len])
        declared-len   @(subscribe [:declared-len])
        fx-len         @(subscribe [:fx-len])
        schema-len     @(subscribe [:schema-len])
        auto-count     @(subscribe [:auto-count])
        declared-count @(subscribe [:declared-count])
        fx-count       @(subscribe [:fx-count])
        schema-count   @(subscribe [:schema-count])
        decls          @(subscribe [:elision-decls])]
    [:div {:data-testid "large-dispatcher"
           :style       {:font-family "sans-serif" :padding "1em"}}
     [:h1 "large-dispatcher testbed"]
     [:p "Four nomination paths for the wire-boundary elision walker.
          Each click commits a value to a path whose elision is
          governed by a different mechanism — the trace surface and
          the MCP wire should substitute "
         [:code ":rf.size/large-elided"]
         " on the appropriate slot."]

     [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
      [:button {:data-testid "write-auto"
                :on-click    #(dispatch [::write-auto-large])}
       "A · unschema'd large warning"]
      [:button {:data-testid "write-declared"
                :on-click    #(dispatch [::write-declared-large])}
       "B · schema-declared flat slot"]
      [:button {:data-testid "write-fx-declared"
                :on-click    #(dispatch [::write-fx-declared-large])}
       "C · second schema-declared flat slot"]
      [:button {:data-testid "write-schema"
                :on-click    #(dispatch [::write-schema-large])}
       "D · schema-driven (:large? on Malli slot)"]
      [:button {:data-testid "reset"
                :on-click    #(dispatch [::reset])}
       "Reset"]]

     [:p {:style {:margin-top "1em" :color "#666" :white-space :pre-wrap}}
      "auto-len="     [:span {:data-testid "auto-len"}     auto-len]
      "  (= 20480 after click — handler sees full value)"           "\n"
      "declared-len=" [:span {:data-testid "declared-len"} declared-len]
      "  (= 200 — small payload, declared elides anyway)"           "\n"
      "fx-len="       [:span {:data-testid "fx-len"}       fx-len]
      "  (= 200 — small payload, fx-declared elides anyway)"        "\n"
      "schema-len="   [:span {:data-testid "schema-len"}   schema-len]
      "  (= 200 — small payload, schema-declared elides anyway)"    "\n\n"
      "click-count="
      [:span "auto="   [:span {:data-testid "auto-count"}   auto-count]
             " declared=" [:span {:data-testid "declared-count"} declared-count]
             " fx=" [:span {:data-testid "fx-count"} fx-count]
             " schema=" [:span {:data-testid "schema-count"} schema-count]]]

     [:h3 {:style {:margin-top "1em"}} "elision declarations"]
     [:pre {:data-testid "elision-decls"
            :style       {:white-space :pre-wrap :font-size "0.9em"
                          :background  "#f5f5f5" :padding "0.5em"}}
      (pr-str decls)]]))

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  ;; Populate the elision registry from any registered schemas
  ;; carrying :large? marks. Per [API.md §`populate-elision-from-
  ;; schemas!`] this walks the app-schema registry and writes
  ;; `{:large? true :source :schema}` slots into the elision
  ;; declarations map. The schema-driven path's declaration enters
  ;; the registry without an explicit handler dispatch.
  (rf/populate-elision-from-schemas!)
  (rdc/render react-root [root]))
