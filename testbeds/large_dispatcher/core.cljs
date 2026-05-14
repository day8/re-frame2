(ns large-dispatcher.core
  "Shared framework-behavior testbed — events whose payloads exceed the
  wire-elision threshold. A consumer (Causa, Story, pair2-mcp)
  observes the runtime's wire-boundary walker substitute a value with
  the `:rf.size/large-elided` marker (per [spec/009 §Size elision in
  traces] / [API.md §`rf/elide-wire-value`]).

  Three nomination paths exist per [spec/009 §Nomination — three
  entry points]:

    1. Schema-driven — `:large?` on a Malli slot in `:rf/app-schema`.
    2. fx-driven    — `:rf.size/declare-large` fx writes the slot
                      from an event handler's `:fx`. Convenience
                      wrappers: `rf/declare-large-path!` and
                      `rf/clear-large-path!`.
    3. Runtime auto-detect — values exceeding `:rf.size/threshold-
                      bytes` (default 16 KiB) get auto-flagged on
                      first wire-emit. Subsequent emits short-
                      circuit on the cached decision and emit a
                      `:rf.warning/runtime-large-elision` advisory.

  This surface exercises all three nomination paths plus one
  control:

    Button A · Auto-detect (no declaration)
      — handler writes a 20 KiB string to `[:auto-large-value]`. The
        runtime walks the app-db at first wire-emit, finds the value
        exceeds the 16 KiB threshold, flags the path, and elides on
        every subsequent emit. The `:rf.warning/runtime-large-elision`
        advisory fires once on first detection.

    Button B · Declared via REPL wrapper
      — handler calls `rf/declare-large-path!` directly. The
        declaration writes `{:large? true :source :declared}` into
        `[:rf/elision :declarations [:declared-large-value]]`. The
        handler then writes a small (200 byte) value to the path —
        elision fires regardless of size because of the declaration
        (declared wins over runtime threshold).

    Button C · Declared via fx
      — handler returns `:fx [[:rf.size/declare-large {:path ...}]]`.
        Same outcome as Button B; different entry point. The fx
        declaration is the canonical AI-discoverable shape (apps
        nominate paths from event handlers).

    Button D · Schema-driven (registered at boot)
      — the surface registers a Malli app-schema with `:large? true`
        on the `:schema-large-value` slot at boot. Any write to that
        path triggers elision on emit. Button D writes a 200-byte
        value to the schema-declared slot.

  This is NOT a tutorial. The bodies are minimal. The point is to
  produce four distinct elision triggers a consumer can assert
  against — auto-flagged + declared (REPL) + declared (fx) + schema
  — all in one surface."
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
;; `pr-str` of this string exceeds `:rf.size/threshold-bytes` (default
;; 16384). The auto-detect path measures the pr-str byte count of each
;; top-two-level subtree; any subtree at or above the threshold gets
;; flagged. Per [spec/009 §Runtime auto-detect].

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
  [:map [:schema-large-value {:large? true} :string]])

(rf/reg-app-schema [:schema-bag] SchemaLarge)

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
;; Button A — auto-detect path
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::write-auto-large
  (fn [db _ev]
    ;; HOT PATH — commits a 20 KiB value to an undeclared path. On
    ;; the first wire-emit the runtime's auto-detect walker measures
    ;; pr-str byte count, finds it above threshold, flags the path,
    ;; and elides on subsequent emits. A
    ;; :rf.warning/runtime-large-elision fires once.
    (-> db
        (assoc :auto-large-value kib-20-string)
        (update-in [:click-count :auto] inc))))

;; ----------------------------------------------------------------------------
;; Button B — declared via rf/declare-large-path! (REPL wrapper)
;; ----------------------------------------------------------------------------
;;
;; The handler calls `rf/declare-large-path!` as a side effect AND
;; commits the small payload. Per [spec/009 §fx-driven] the wrapper
;; issues a synthetic dispatch of `:rf.size/declare-large` for us —
;; same effect as the fx path, but ergonomic at the REPL.
;;
;; The declaration writes `{:large? true :source :declared}` into
;; `[:rf/elision :declarations [:declared-large-value]]`; the slot
;; is elided on every subsequent emit irrespective of size (declared
;; wins over runtime per Spec 009).

(rf/reg-event-db ::write-declared-large
  (fn [db _ev]
    (rf/declare-large-path! [:declared-large-value]
                            "Test surface — declared via REPL wrapper")
    (-> db
        (assoc :declared-large-value chars-200-string)
        (update-in [:click-count :declared] inc))))

;; ----------------------------------------------------------------------------
;; Button C — declared via :rf.size/declare-large fx
;; ----------------------------------------------------------------------------

(rf/reg-event-fx ::write-fx-declared-large
  (fn [{:keys [db]} _ev]
    ;; HOT PATH — the canonical AI-discoverable nomination shape.
    ;; The fx writes the declaration into the elision registry; the
    ;; handler's :db commits the small payload; subsequent emits
    ;; elide the path.
    {:db (-> db
             (assoc :fx-declared-value chars-200-string)
             (update-in [:click-count :fx] inc))
     :fx [[:rf.size/declare-large {:path [:fx-declared-value]
                                   :hint "Test surface — declared via fx"}]]}))

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
    {:fx [[:dispatch [::initialise]]
          ;; Clear the declared paths (the schema-derived entry
          ;; stays — it's repopulated at boot via the registered
          ;; schema; only the imperative declarations need
          ;; clearing for a clean re-run).
          [:rf.size/clear {:path [:declared-large-value]}]
          [:rf.size/clear {:path [:fx-declared-value]}]]}))

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
       "A · auto-detect (20 KiB > threshold)"]
      [:button {:data-testid "write-declared"
                :on-click    #(dispatch [::write-declared-large])}
       "B · declare-large-path! (REPL wrapper)"]
      [:button {:data-testid "write-fx-declared"
                :on-click    #(dispatch [::write-fx-declared-large])}
       "C · :rf.size/declare-large fx"]
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
