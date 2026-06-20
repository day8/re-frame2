(ns re-frame.story.ui.author-expectations
  "Expectation-authoring UX — add EXPECTATIONS to a story so a useful story
  becomes a regression test (spec/021 §S5, spec/019).

  ## What this surface is — and is NOT

  The author picks an expectation KIND (app-db value, subscription value,
  rendered hiccup/DOM, schema behaviour, browser/a11y evidence), fills its
  operands, and the dialog shows — per row, BEFORE save — the runner cost
  and whether it `:cannot-run` under the default headless runner. On save
  the authored expectations become EXPLICIT variant DATA: a `(reg-variant
  … {:assertions […]})` form merged with the source variant's declared
  assertions, which the author copies into source. Nothing is hidden UI
  state.

  Thin UI over the pure `re-frame.story.author-expectations` substrate —
  this ns reimplements NO expectation logic, NO runner-cost model, and NO
  assertion vocabulary. It wires the draft ratom, the kind picker, the
  operand inputs, the per-row cost stripe, and the review-then-commit
  dialog.

  DISTINCT from its two siblings (which it MUST NOT conflate):
  save-current-state-as-variant (`re-frame.story.ui.save-variant`, the live
  args snapshot) and generated-failure promotion
  (`re-frame.story.ui.promotion`, a captured run artifact). All three share
  the `review-dialog` skeleton but never the same entry point.

  ## Pure / CLJS split

  Mirrors `save_variant.cljs` + `promotion.cljc`: the catalog / atom
  builders / cost projection / snippet live in the pure `.cljc` substrate;
  the dialog ratom, the keystroke transitions, and the Reagent render are
  `#?(:cljs …)`. Production builds short-circuit on `config/enabled?`."
  (:require [re-frame.story.author-expectations :as author]
            ;; `clojure.string` + `review-dialog` are consumed ONLY by the
            ;; `:cljs` dialog surface below (the pure draft transitions in
            ;; this ns need neither), so they ride the `:cljs` require — a
            ;; `:clj`-mode lint sees no dead require. `review-dialog` is
            ;; `.cljc`; its pure transitions are JVM-available, but this ns
            ;; only calls them from `:cljs` code.
            #?@(:cljs [[clojure.string :as str]
                       [reagent.core :as r]
                       [re-frame.story.config :as config]
                       [re-frame.story.registrar :as registrar]
                       [re-frame.story.review-dialog :as review-dialog]
                       [re-frame.story.ui.state :as state]
                       [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
                       [re-frame.story.theme.colors :as colors]])))

;; ===========================================================================
;; PURE: draft shape + transitions
;; ===========================================================================
;;
;; The draft carries the authored rows (each `{:row-id :kind :operands}`)
;; plus the new-variant id + doc. The transitions are pure data → data so
;; the JVM test corpus can pin the add/remove/edit semantics without a host.

(def empty-row-operands {})

(defn new-row
  "Build a fresh authored-expectation row of `kind` with a stable `row-id`.
  Pure data → data. `:operands` starts empty (the author fills them); the
  dialog seeds defaults from the kind's placeholder if it wants, but the
  draft default is empty so a blank row reads as 'fill this'."
  [row-id kind]
  {:row-id   row-id
   :kind     kind
   :operands empty-row-operands})

(defn initial-draft
  "The idle authoring draft. `:rows` is empty; `:variant-id` / `:doc` /
  `:next-row-id` populate as the author drives the flow. Pure data."
  []
  {:variant-id  nil
   :doc         nil
   :rows        []
   :next-row-id 0})

(defn add-row
  "Append a new row of `kind` to the draft, minting a stable row id from
  `:next-row-id`. Pure data → data."
  [draft kind]
  (let [rid (:next-row-id draft 0)]
    (-> draft
        (update :rows (fnil conj []) (new-row rid kind))
        (assoc :next-row-id (inc rid)))))

(defn remove-row
  "Drop the row with `row-id` from the draft. Pure data → data."
  [draft row-id]
  (update draft :rows (fn [rows] (vec (remove #(= row-id (:row-id %)) rows)))))

(defn set-operand
  "Set `field`'s raw operand string on the row `row-id`. Pure data → data.
  The raw string is stored verbatim (parsing happens at projection time via
  `author/parse-operands`) so the input value the author sees is never
  clobbered mid-keystroke."
  [draft row-id field value]
  (update draft :rows
          (fn [rows]
            (mapv (fn [row]
                    (if (= row-id (:row-id row))
                      (assoc-in row [:operands field] value)
                      row))
                  rows))))

(defn set-variant-id
  "Set the new-variant id on the draft. `id` may be a keyword or a raw
  string the author is typing. Pure data → data."
  [draft id]
  (assoc draft :variant-id id))

(defn set-doc
  "Set the new-variant docstring on the draft. Pure data → data."
  [draft doc]
  (assoc draft :doc doc))

(defn draft-atoms
  "Project the draft's ready rows into canonical assertion atoms. Pure data
  → vector. Re-exposed here so the snippet generator + tests read one
  helper rather than re-walking the rows."
  [draft]
  (:atoms (author/draft-summary draft)))

;; ===========================================================================
;; CLJS-ONLY: dialog ratom + lifecycle
;; ===========================================================================

#?(:cljs
   (def initial-dialog-state
     "Idle author-expectations dialog state. `:open?` flips true on `open!`;
      `:source-id` / `:draft` populate as the author drives the flow."
     {:open?     false
      :source-id nil
      :draft     (initial-draft)}))

#?(:cljs
   (defonce ^{:doc "Reagent ratom holding the author-expectations dialog state."}
     dialog-atom
     (r/atom initial-dialog-state)))

#?(:cljs
   (defn- existing-assertions
     "The source variant body's already-declared `:assertions` (or
      `:expect :assertions`), so the snippet merges authored atoms onto them
      additively (the round-trip). Reads the resolved body via the Story
      registrar; nil → empty."
     [source-id]
     (let [body (registrar/handler-meta :variant source-id)]
       (or (:assertions body)
           (get-in body [:expect :assertions])
           []))))

#?(:cljs
   (defn open!
     "Open the authoring dialog against `source-variant-id`. Seeds an empty
      draft with a default `expects-<ms>` id under the source's story
      namespace (when the source is a qualified keyword). No-op when Story
      is disabled."
     [source-variant-id]
     (when config/enabled?
       (let [now-ms (.now js/Date)
             def-id (review-dialog/default-variant-id-with-prefix
                      source-variant-id now-ms author/default-id-prefix)]
         (reset! dialog-atom
                 {:open?     true
                  :source-id source-variant-id
                  :draft     (-> (initial-draft)
                                 (set-variant-id def-id))})
         nil))))

#?(:cljs
   (defn close! []
     (reset! dialog-atom initial-dialog-state)
     nil))

#?(:cljs
   (defn- update-draft! [f & args]
     (apply swap! dialog-atom update :draft f args)))

#?(:cljs (defn add-row!    [kind]            (update-draft! add-row kind)))
#?(:cljs (defn remove-row! [row-id]          (update-draft! remove-row row-id)))
#?(:cljs (defn set-operand! [row-id field v] (update-draft! set-operand row-id field v)))
#?(:cljs (defn set-doc!     [doc]            (update-draft! set-doc doc)))

#?(:cljs
   (defn set-variant-id!
     "Per-keystroke new-variant-id edit. Parses the raw input into a keyword
      best-effort; stores the raw string on parse failure so the input value
      the author sees is not clobbered mid-keystroke."
     [s]
     (update-draft! set-variant-id
                    (or (review-dialog/parse-variant-id-string s) s))))

;; ===========================================================================
;; CLJS-ONLY: styling
;; ===========================================================================

#?(:cljs
   (def ^:private styles
     {:section    {:margin "8px 0 0 0"}
      :label      {:font-family    mono-stack
                   :font-size      (:micro typography/type-scale)
                   :color          (:text-tertiary colors/tokens)
                   :margin         "0 0 3px 0"
                   :text-transform "uppercase"
                   :letter-spacing "0.04em"}
      :picker-row {:display "flex" :gap "6px" :flex-wrap "wrap" :margin-bottom "6px"}
      :kind-chip  {:font-family   mono-stack
                   :font-size     (:micro typography/type-scale)
                   :background    (:bg-2 colors/tokens)
                   :color         (:text-secondary colors/tokens)
                   :border        "1px solid #444"
                   :border-radius "10px"
                   :padding       "2px 9px"
                   :cursor        "pointer"
                   :user-select   "none"}
      :row        {:display       "flex"
                   :flex-direction "column"
                   :gap           "4px"
                   :padding       "6px 8px"
                   :margin        "4px 0"
                   :background    (:bg-2 colors/tokens)
                   :border        "1px solid #3a3a44"
                   :border-radius "4px"}
      :row-head   {:display "flex" :align-items "baseline" :gap "8px"}
      :row-kind   {:font-family mono-stack
                   :font-weight "bold"
                   :color       (:info colors/tokens)
                   :font-size   (:caption typography/type-scale)}
      :row-doc    {:flex "1 1 auto"
                   :color (:text-tertiary colors/tokens)
                   :font-size (:nano typography/type-scale)
                   :font-style "italic"
                   :white-space "nowrap" :overflow "hidden" :text-overflow "ellipsis"}
      :remove-btn {:background "transparent"
                   :border     "none"
                   :color      (:text-tertiary colors/tokens)
                   :cursor     "pointer"
                   :font-size  (:caption typography/type-scale)
                   :padding    "0 4px"}
      :operand-row {:display "flex" :align-items "baseline" :gap "6px"}
      :operand-lbl {:font-family mono-stack
                    :font-size (:nano typography/type-scale)
                    :color (:text-tertiary colors/tokens)
                    :width "84px" :text-align "right" :flex "0 0 auto"}
      :operand-in  {:padding       "3px 6px"
                    :background    "#0e0e10"
                    :color         "white"
                    :border        "1px solid #444"
                    :border-radius "3px"
                    :font-family   mono-stack
                    :font-size     (:caption typography/type-scale)
                    :flex          "1 1 auto"
                    :box-sizing    "border-box"}
      :operand-err {:color (:danger colors/tokens)
                    :font-size (:nano typography/type-scale)
                    :font-family mono-stack}
      ;; per-row cost stripe — the honesty floor (cost BEFORE save)
      :cost        {:display "flex" :align-items "baseline" :gap "8px"
                    :font-family mono-stack
                    :font-size (:nano typography/type-scale)
                    :padding "2px 0 0 0"}
      :cost-ok     {:color (:success colors/tokens)}
      :cost-cannot {:color (:warning colors/tokens)}
      :doc-input   {:padding       "5px 8px"
                    :background    (:bg-2 colors/tokens)
                    :color         "white"
                    :border        "1px solid #444"
                    :border-radius "3px"
                    :font-family   sans-stack
                    :font-size     (:body-tight typography/type-scale)
                    :width         "100%"
                    :box-sizing    "border-box"}
      :banner      {:padding       "8px 10px"
                    :margin        "8px 0 0 0"
                    :background    "#22242c"
                    :color         "#c8c8d0"
                    :border        "1px solid #3a3a44"
                    :border-radius "3px"
                    :font-family   mono-stack
                    :font-size     (:micro typography/type-scale)
                    :line-height   "1.5"}
      :banner-warn {:background "#332a2a" :color "#d8b48f" :border "1px solid #704a40"}
      :empty       {:color (:text-tertiary colors/tokens)
                    :font-style "italic"
                    :font-size (:micro typography/type-scale)
                    :padding "6px 0"}}))

;; ===========================================================================
;; CLJS-ONLY: per-row cost stripe + operand inputs
;; ===========================================================================

#?(:cljs
   (defn- cost-stripe
     "Render the per-row runner-cost / `:cannot-run` stripe — the honesty
      floor shown BEFORE save. Reads `author/row-cost` (which reads the
      EXISTING requirement registry); shows the cheapest runner that proves
      the expectation and, when it needs more than the default headless
      runner, an explicit `cannot run headless` flag with the missing
      tokens. Never hides the cost."
     [row]
     (let [{:keys [required cheapest-runner cannot-run? missing]} (author/row-cost row)]
       [:div {:style       (:cost styles)
              :data-test    "story-author-expectation-cost"
              :data-cannot-run (str (boolean cannot-run?))
              :data-runner  (str cheapest-runner)}
        [:span {:style (if cannot-run? (:cost-cannot styles) (:cost-ok styles))}
         (if cannot-run? "⚠ " "✓ ")]
        [:span "runner: " (if cheapest-runner (name cheapest-runner) "none")]
        (when (seq required)
          [:span {:style {:color (:text-tertiary colors/tokens)}}
           "needs " (pr-str required)])
        (when cannot-run?
          [:span {:style (:cost-cannot styles)
                  :data-test "story-author-expectation-cannot-run"}
           "cannot run headless — missing " (pr-str missing)])])))

#?(:cljs
   (defn- operand-input
     "Render one operand input for an authored row, with its parse error
      stripe when the current raw value does not parse."
     [row {:keys [field label placeholder]} error]
     [:div {:style (:operand-row styles)
            :data-test "story-author-expectation-operand"
            :data-field (name field)}
      [:span {:style (:operand-lbl styles)} (str label ":")]
      [:input {:type        "text"
               :style       (:operand-in styles)
               :data-test   "story-author-expectation-operand-input"
               :aria-label  (str label " operand")
               :value       (or (get-in row [:operands field]) "")
               :placeholder placeholder
               :on-change   (fn [e] (set-operand! (:row-id row) field (.. e -target -value)))}]
      (when error
        [:span {:style (:operand-err styles)
                :data-test "story-author-expectation-operand-error"}
         error])]))

#?(:cljs
   (defn- expectation-row
     "Render one authored-expectation row: the kind header + remove control,
      its operand inputs (with per-field parse errors), and the per-row cost
      stripe (cost BEFORE save)."
     [row]
     (let [desc   (author/kind-descriptor (:kind row))
           parsed (author/parse-operands row)
           errors (:errors parsed)]
       [:div {:style     (:row styles)
              :data-test "story-author-expectation-row"
              :data-kind (name (:kind row))
              :data-row-id (str (:row-id row))}
        [:div {:style (:row-head styles)}
         [:span {:style (:row-kind styles)} (:label desc)]
         [:span {:style (:row-doc styles)} (:doc desc)]
         [:button {:style     (:remove-btn styles)
                   :type      "button"
                   :data-test "story-author-expectation-remove"
                   :aria-label "Remove this expectation"
                   :on-click  (fn [_] (remove-row! (:row-id row)))}
          "×"]]
        ;; `operand-input` / `cost-stripe` are pure render fns (no local
        ;; state), so they are CALLED directly — their hiccup expands inline
        ;; into this row rather than hiding behind a component boundary (so a
        ;; directly-invoked `expectation-row` fully realises for tests). The
        ;; React `:key` rides on the returned hiccup vector via `with-meta`
        ;; (the meta DOES transfer because the value already IS a vector).
        (doall
          (for [spec (:operands desc)]
            (with-meta (operand-input row spec (get errors (:field spec)))
                       {:key (name (:field spec))})))
        (cost-stripe row)])))

;; ===========================================================================
;; CLJS-ONLY: the authoring controls block (above the snippet)
;; ===========================================================================

#?(:cljs
   (defn- kind-picker
     "The kind picker — one chip per authorable expectation kind. Clicking a
      chip appends a fresh row of that kind. Grouped only by render order;
      each chip carries its surface as a data attr so tests can pin
      coverage."
     []
     [:div {:data-test "story-author-expectation-kind-picker"}
      [:div {:style (:label styles)} "add expectation"]
      [:div {:style (:picker-row styles)}
       (for [{:keys [kind label surface]} author/expectation-kinds]
         ^{:key (name kind)}
         [:span {:style       (:kind-chip styles)
                 :data-test   "story-author-expectation-kind-chip"
                 :data-kind   (name kind)
                 :data-surface (name surface)
                 :role        "button"
                 :tab-index   "0"
                 :title       (get author/surface-labels surface)
                 :on-click    (fn [_] (add-row! kind))}
          (str "+ " label)])]]))

#?(:cljs
   (defn- coverage-banner
     "The before-save honesty banner: how many expectations are authored vs
      ready, which acceptance surfaces they span, the single cheapest runner
      that would prove the whole draft, and the explicit list of rows that
      `:cannot-run` headless. Reads the pure `author/draft-summary`."
     [draft]
     (let [{:keys [count ready cheapest-runner cannot-run-rows surfaces]}
           (author/draft-summary draft)
           any-cannot? (seq cannot-run-rows)]
       [:div {:style     (merge (:banner styles) (when any-cannot? (:banner-warn styles)))
              :data-test "story-author-expectation-summary"
              :data-count (str count)
              :data-ready (str ready)
              :data-cheapest-runner (str cheapest-runner)
              :data-cannot-run-count (str (clojure.core/count cannot-run-rows))}
        [:div {:style {:font-weight "bold" :margin-bottom "4px"}}
         (str ready " of " count " expectation"
              (when (not= count 1) "s") " ready"
              (when cheapest-runner
                (str " — cheapest runner that proves all: " (name cheapest-runner))))]
        (when (seq surfaces)
          [:div {:data-test "story-author-expectation-surfaces"}
           "surfaces: "
           (str/join " · "
                     (keep #(get author/surface-labels %)
                           (sort surfaces)))])
        (when any-cannot?
          [:div {:data-test "story-author-expectation-cannot-run-summary"
                 :style {:margin-top "4px"}}
           (str (clojure.core/count cannot-run-rows)
                " expectation"
                (when (not= 1 (clojure.core/count cannot-run-rows)) "s")
                " cannot run under the default headless runner "
                "(browser / DOM / reactive evidence) — visible here BEFORE save, "
                "never discovered only at run time.")])])))

#?(:cljs
   (defn- authoring-controls
     "The authoring block the dialog renders ABOVE the review-dialog snippet:
      the kind picker, the authored rows (each with operand inputs + the
      per-row cost stripe), the docstring input, and the coverage banner."
     [draft]
     [:div {:data-test "story-author-expectation-controls"}
      (kind-picker)
      (if (seq (:rows draft))
        [:div {:data-test "story-author-expectation-rows"}
         (for [row (:rows draft)]
           ^{:key (:row-id row)}
           [expectation-row row])]
        [:div {:style (:empty styles)
               :data-test "story-author-expectation-empty"}
         "No expectations yet — pick a kind above to start authoring."])
      [:div {:style (:section styles)}
       [:div {:style (:label styles)} "doc (optional)"]
       [:input {:type        "text"
                :data-test   "story-author-expectation-doc-input"
                :style       (:doc-input styles)
                :aria-label  "New variant docstring"
                :value       (or (:doc draft) "")
                :placeholder "Why these expectations matter…"
                :on-change   (fn [e] (set-doc! (.. e -target -value)))}]]
      (coverage-banner draft)]))

;; ===========================================================================
;; CLJS-ONLY: the dialog
;; ===========================================================================

#?(:cljs
   (defn build-snippet
     "Build the `(reg-variant …)` snippet for the current dialog state. Pure
      over the deref'd dialog map — the authored expectations become explicit
      `:assertions` variant DATA, merged with the source variant's declared
      assertions (the additive round-trip)."
     [{:keys [source-id draft] :as _dialog}]
     (author/gen-expectations-snippet
       {:variant-id (or (:variant-id draft) :story.expectations/example)
        :extends    source-id
        :existing   (existing-assertions source-id)
        :authored   (draft-atoms draft)
        :doc        (when (and (string? (:doc draft)) (seq (str/trim (:doc draft))))
                      (str/trim (:doc draft)))})))

#?(:cljs
   (defn author-dialog
     "Render the author-expectations modal. Visible iff `:open?` on the
      dialog ratom. The author picks expectation kinds, fills operands, sees
      the per-row + draft-level runner cost / `:cannot-run` BEFORE save, and
      copies the `(reg-variant … {:assertions […]})` form into source
      (source is never written directly).

      Public so tests can render the dialog hiccup directly after seeding the
      dialog ratom."
     []
     (fn []
       (let [dialog @dialog-atom]
         (when (:open? dialog)
           (let [{:keys [source-id draft]} dialog
                 snippet (build-snippet dialog)]
             (review-dialog/review-dialog
               {:open?     true
                :draft-id  (:variant-id draft)
                :source-id source-id}
               {:title             "Add expectations to this story"
                :hint              [:div
                                    [:div (str "Authoring expectations onto "
                                               (pr-str source-id)
                                               ". Saved expectations become EXPLICIT "
                                               ":assertions data on a new variant (via "
                                               ":extends), merged with any already declared "
                                               "— not hidden UI state. This is DISTINCT from "
                                               "save-current-state and failure promotion.")]
                                    (authoring-controls draft)]
                :snippet           snippet
                :placeholder-id    :story.expectations/example
                :placeholder-input ":story.your-story/expects-flow"
                :on-edit-id        set-variant-id!
                :on-copy           (fn [] (review-dialog/copy-to-clipboard! snippet))
                :on-close          close!
                :data-test-prefix  "story-author-expectation"})))))))

;; ===========================================================================
;; CLJS-ONLY: the entry-point button (controls panel)
;; ===========================================================================

#?(:cljs
   (def ^:private button-styles
     {:button          {:padding       "4px 8px"
                        :background    (:bg-2 colors/tokens)
                        :color         (:text-primary colors/tokens)
                        :border        (str "1px solid " (:info colors/tokens))
                        :border-radius "3px"
                        :cursor        "pointer"
                        :font-size     (:micro typography/type-scale)
                        :margin-top    "8px"
                        :font-family   mono-stack}
      :button-disabled {:padding       "4px 8px"
                        :background    (:bg-2 colors/tokens)
                        :color         "#777"
                        :border        "1px solid #444"
                        :border-radius "3px"
                        :cursor        "not-allowed"
                        :font-size     (:micro typography/type-scale)
                        :margin-top    "8px"
                        :font-family   mono-stack}}))

#?(:cljs
   (defn author-expectations-button
     "Render the 'add expectations…' button. Opens the authoring dialog
      against `variant-id`. Disabled when no variant is focused. Lives on the
      controls panel beside 'save as new variant…' — the two are distinct
      flows sharing a home (spec/019 §3, spec/021 §S5).

      Public so tests can introspect the button hiccup without driving the
      full controls panel."
     [variant-id]
     (let [enabled? (some? variant-id)]
       [:button
        {:style     (if enabled? (:button button-styles) (:button-disabled button-styles))
         :disabled  (not enabled?)
         :data-test "story-author-expectation-button"
         :title     (if enabled?
                      "Author expectations onto this story (becomes a regression test)"
                      "Select a variant to author expectations")
         :on-click  (fn [_] (when enabled? (open! variant-id)))}
        "add expectations…"])))

;; ===========================================================================
;; CLJS-ONLY: palette action entry-point
;; ===========================================================================

#?(:cljs
   (defn open-for-focused-variant!
     "Open the authoring dialog against the shell's focused variant. The
      command-palette action target. No-op (returns nil) when no variant is
      focused, so the palette entry needs no extra guard."
     []
     (when config/enabled?
       (when-let [vid (:selected-variant (state/get-state))]
         (open! vid)))))
