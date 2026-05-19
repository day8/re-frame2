(ns re-frame.story.review-dialog
  "Shared 'review-then-commit modal' primitive for Story's save-as flows
  (rf2-7jpky; extract from rf2-dd5ze audit P0 #3).

  Two save-as flows ship today:

  - record-as-`:play` — `re-frame.story.recorder` captures a trace of
    dispatched events and surfaces the generated `(reg-variant ...)`
    EDN form in a modal for the user to copy + paste into source.
  - snapshot-args-as-`:args` — `re-frame.story.save-variant` captures
    the live effective args and surfaces the same shape.

  Both flows share the same UX skeleton:

  1. Capture user state into a snapshot (events / args / whatever).
  2. Build an EDN snippet from the snapshot.
  3. Open a modal with the snippet + an editable target-variant-id
     input. The snippet re-generates on every keystroke as the user
     edits the id.
  4. On commit, copy to clipboard. Source is never written directly.

  Storybook 9 writes directly to source files, which entangles the
  playground with Prettier configuration, source-control conflicts, and
  the project's editor settings. Story emits the snippet for the user
  to paste — the elegant escape hatch.

  ## What this ns owns

  Pure data + transitions (this `.cljc`):

  - `initial-state`                    — the idle dialog state map.
  - `open` / `close` / `set-draft-id`  — pure transitions.
  - `default-variant-id-with-prefix`   — derive a sensible default
                                          new-variant id from a source
                                          variant id + wall-clock ms +
                                          a per-flow prefix
                                          (`recorded-` / `saved-`).
  - `parse-variant-id-string`          — best-effort string → keyword
                                          parser the UI's id-input uses.

  The thin `.cljs` adapter `re-frame.story.review-dialog.cljs` (same ns;
  reader-conditional split below) owns the Reagent `r/atom` factory,
  the shared `copy-to-clipboard!` helper, and the `review-dialog`
  hiccup renderer parameterised by `{:title :hint :snippet :draft-id
  :source-id :on-edit-id :on-copy :on-discard :on-close
  :data-test-prefix}`.

  ## Why a shared ns

  Pre-extraction the two flows carried parallel inline copies of the
  same shape — same modal styling, same `open?` / `draft-id` ratom,
  same `set-draft-id!` string-parsing helper, same `copy-to-clipboard!`
  shim — drifted by ~120 LoC. The recorder did NOT factor the dialog
  state machine into `.cljc`, so JVM tests couldn't pin its
  transitions. Extracting here delivers:

  - One JVM-testable dialog state machine.
  - One copy-to-clipboard shim.
  - One renderer the next save-as-anything flow (save-as-workspace,
    save-as-snapshot, save-as-mode) consumes in ~20 LoC of glue.

  ## Dialog state shape

  The dialog state map carries:

  - `:open?`     — bool; true while the modal is visible.
  - `:draft-id`  — the user's editable target-variant-id. May be a
                   keyword (parses cleanly) or a string (raw input
                   pre-parse). `nil` before first open.
  - `:source-id` — the source variant id the snapshot was taken
                   against. Rides into `:extends` in the generated
                   snippet. `nil` before first open.
  - `:context`   — a flow-specific map the caller stashes alongside
                   the dialog state (e.g. recorder stashes
                   `{:events [...]}`; save-variant stashes
                   `{:args {...}}`). The renderer is presentational —
                   it doesn't read `:context`; the caller builds the
                   snippet string from it before passing the snippet
                   to the renderer."
  (:require [clojure.string :as str]
            #?(:cljs [reagent.core :as r])
            [re-frame.story.theme.typography :refer [mono-stack]]))

;; ---------------------------------------------------------------------------
;; Pure: initial state + transitions
;; ---------------------------------------------------------------------------

(def initial-state
  "The dialog's idle state map. `:open?` flips true on `open`; the
  `:draft-id`, `:source-id`, `:context` slots populate as the user
  drives the flow."
  {:open?     false
   :draft-id  nil
   :source-id nil
   :context   nil})

(defn default-variant-id-with-prefix
  "Derive a sensible default id for the new variant given a source
  variant id, a wall-clock millis stamp, and a per-flow prefix
  (`\"recorded\"` for the recorder; `\"saved\"` for save-variant).

  Pure data → keyword. Returns nil when `source-variant-id` is not a
  qualified keyword — caller's preview falls back to a placeholder
  literal (e.g. `:story.recorded/example`).

  Examples:

      (default-variant-id-with-prefix :story.counter/happy-path
                                      1700000000000 \"saved\")
      ;; => :story.counter/saved-NNNNN

      (default-variant-id-with-prefix :story.counter/happy-path
                                      1700000000000 \"recorded\")
      ;; => :story.counter/recorded-NNNNN"
  [source-variant-id now-ms prefix]
  (when (qualified-keyword? source-variant-id)
    (let [suffix (mod (long now-ms) 1000000)]
      (keyword (namespace source-variant-id)
               (str prefix "-" suffix)))))

(defn parse-variant-id-string
  "Best-effort parse of an id-input string into a keyword. Handles the
  leading `:` (the user types `\":foo/bar\"` because that's the printed
  form) and the embedded `/` (qualified vs unqualified). Returns nil
  when parsing fails — the caller stores the raw string on failure so
  the input value the user sees doesn't get clobbered mid-keystroke.

  Pure data → keyword | nil.

  Examples:

      (parse-variant-id-string \":story.counter/saved-1\")
      ;; => :story.counter/saved-1

      (parse-variant-id-string \"story.counter/saved-1\")
      ;; => :story.counter/saved-1

      (parse-variant-id-string \"plain\")
      ;; => :plain

      (parse-variant-id-string \"\")
      ;; => nil

      (parse-variant-id-string nil)
      ;; => nil"
  [s]
  (when (and (string? s) (seq s))
    (try
      (let [stripped (cond-> s
                       (str/starts-with? s ":")
                       (subs 1))]
        (when (seq stripped)
          (if (str/includes? stripped "/")
            (let [[ns nm] (str/split stripped #"/" 2)]
              (when (and (seq ns) (seq nm))
                (keyword ns nm)))
            (keyword stripped))))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn open
  "Pure: return the dialog state for opening against `source-id` with
  `context` stashed and a default draft-id derived from `now-ms` +
  `prefix`. The context is opaque to the dialog — callers stash flow-
  specific data (events / args / etc.) for the snippet generator to
  consume on render.

  `prefix` is the per-flow tag for the auto-derived default id
  (`\"recorded\"` for the recorder, `\"saved\"` for save-variant)."
  [_state source-id context now-ms prefix]
  {:open?     true
   :source-id source-id
   :context   context
   :draft-id  (default-variant-id-with-prefix source-id now-ms prefix)})

(defn close
  "Pure: return the dialog state for closing. Returns the idle state —
  `:draft-id` / `:source-id` / `:context` clear so the next open starts
  fresh."
  [_state]
  initial-state)

(defn set-draft-id
  "Pure: replace the draft-id in the dialog state. `id` may be a
  keyword (already-parsed) or a string (raw input the user is typing).
  The pure transition just stores whatever the caller passes; the
  string-parsing helper `parse-variant-id-string` is exposed
  separately so callers can attempt parsing before swapping."
  [state id]
  (assoc state :draft-id id))

(defn parse-and-set-draft-id
  "Convenience: parse `s` (the raw input string) into a keyword
  best-effort, then swap into `state`'s `:draft-id`. On parse failure,
  stores the raw string so the input value the user sees doesn't get
  clobbered mid-keystroke.

  Pure data → state. Equivalent to:

      (set-draft-id state (or (parse-variant-id-string s) s))"
  [state s]
  (set-draft-id state (or (parse-variant-id-string s) s)))

;; ---------------------------------------------------------------------------
;; Snippet-format helper — `indent-after` moved to predicates leaf
;;
;; The two save-as flows render multi-line EDN where successive items
;; (event vectors / map kv pairs) align directly under the opening
;; bracket/brace of their body key — e.g.
;;
;;     (story/reg-variant :id
;;       {:play [[:counter/inc]
;;               [:counter/dec]]})
;;
;; Per rf2-ar0t9: `indent-after` lives in `re-frame.story.predicates`
;; so producers (recorder, save-variant) don't have to `:require`
;; review-dialog (the consumer) for a 4-line helper. The dep direction
;; recorder → review-dialog was upside-down.
;; ---------------------------------------------------------------------------
;; CLJS-only: Reagent ratom factory + adapter glue
;;
;; The pure transitions above produce new state maps; the CLJS side
;; swaps a Reagent `r/atom` around them so the modal re-renders on
;; every keystroke. The `make-dialog-atom` factory returns a fresh
;; ratom for each call site — the recorder and save-variant flows
;; carry their own ratom each so their states stay independent.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn make-dialog-atom
     "Create a fresh Reagent ratom seeded with `initial-state`. Each
     call site (recorder, save-variant) owns its own ratom so the
     dialog states stay independent."
     []
     (r/atom initial-state)))

#?(:cljs
   (defn swap-open!
     "Swap `dialog-atom` into the opened state for `source-id` +
     `context` + the wall-clock `now-ms` + a per-flow `prefix`. Use
     when you have `now-ms` already (e.g. tests); the impure caller
     `swap-open-now!` reads the clock for you."
     [dialog-atom source-id context now-ms prefix]
     (swap! dialog-atom open source-id context now-ms prefix)))

#?(:cljs
   (defn swap-open-now!
     "Swap `dialog-atom` into the opened state for `source-id` +
     `context` using the current wall-clock for the default-id seed."
     [dialog-atom source-id context prefix]
     (swap-open! dialog-atom source-id context (.now js/Date) prefix)))

#?(:cljs
   (defn swap-close!
     "Swap `dialog-atom` into the idle (closed) state."
     [dialog-atom]
     (swap! dialog-atom close)))

#?(:cljs
   (defn swap-parse-and-set-draft-id!
     "Swap `dialog-atom` into the new draft-id state for raw input
     `s`. Parses the input best-effort into a keyword; stores the raw
     string on parse failure."
     [dialog-atom s]
     (swap! dialog-atom parse-and-set-draft-id s)))

;; ---------------------------------------------------------------------------
;; CLJS-only: copy-to-clipboard shim
;;
;; Wraps `navigator.clipboard.writeText`. Single helper both save-as
;; flows consume — pre-extract each flow carried its own try/catch
;; copy. Returns nil; failures are swallowed silently (no clipboard
;; on JSDOM / older browsers / non-secure contexts is the expected
;; case).
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn copy-to-clipboard!
     "Copy `text` to the system clipboard via the navigator API.
     No-op on hosts without `navigator.clipboard`. Swallows failures
     silently — the modal still surfaces the snippet for manual copy."
     [text]
     (try
       (when (and (exists? js/navigator) (.-clipboard js/navigator))
         (.writeText (.-clipboard js/navigator) text))
       (catch :default _ nil))
     nil))

;; ---------------------------------------------------------------------------
;; CLJS-only: presentational modal renderer
;;
;; Parameterised by display strings + the snippet + callbacks. Returns
;; nil when the dialog is closed so the caller can render it
;; unconditionally next to the other shell mounts. The renderer is
;; intentionally state-light — the caller threads the snippet (re-
;; rendered on every keystroke) so flow-specific snippet generation
;; stays in the flow's own ns.
;; ---------------------------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:modal-back   {:position "fixed"
                     :top "0" :left "0" :right "0" :bottom "0"
                     :background "rgba(0,0,0,0.55)"
                     :z-index 1700
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"}
      :modal        {:width          "640px"
                     :max-width      "90vw"
                     :max-height     "80vh"
                     :background     "#1e1e1e"
                     :color          "#ddd"
                     :border         "1px solid #444"
                     :border-radius  "6px"
                     :padding        "16px"
                     :font-family    mono-stack
                     :font-size      "12px"
                     :display        "flex"
                     :flex-direction "column"
                     :gap            "12px"
                     :box-shadow     "0 12px 32px rgba(0,0,0,0.7)"
                     :overflow       "hidden"}
      :modal-title  {:font-weight "bold"
                     :color       "#9cdcfe"
                     :font-size   "13px"}
      :id-input     {:padding       "6px 8px"
                     :background    "#252526"
                     :color         "white"
                     :border        "1px solid #444"
                     :border-radius "3px"
                     :font-family   mono-stack
                     :font-size     "12px"
                     :width         "100%"
                     :box-sizing    "border-box"}
      :snippet      {:background    "#0e0e10"
                     :color         "#dcdcaa"
                     :padding       "10px"
                     :border        "1px solid #333"
                     :border-radius "4px"
                     :white-space   "pre"
                     :overflow      "auto"
                     :max-height    "44vh"
                     :font-family   mono-stack
                     :font-size     "11px"
                     :line-height   "1.45"
                     :flex          "1 1 auto"}
      :btn-row      {:display         "flex"
                     :gap             "8px"
                     :justify-content "flex-end"}
      :btn          {:padding       "5px 12px"
                     :background    "#0e639c"
                     :color         "white"
                     :border        "none"
                     :border-radius "3px"
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     "11px"}
      :btn-muted    {:padding       "5px 12px"
                     :background    "transparent"
                     :color         "#cccccc"
                     :border        "1px solid #444"
                     :border-radius "3px"
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     "11px"}
      :hint         {:color       "#9a9a9a"
                     :font-style  "italic"
                     :font-size   "10px"}}))

#?(:cljs
   (defn review-dialog
     "Render the review-then-commit modal. Returns nil when `:open?` is
     false on `dialog-state`; otherwise returns the hiccup tree.

     `dialog-state` is the dialog map (`{:open? :draft-id :source-id
     :context}`); the caller derefs its ratom and threads the value
     in.

     `opts`:

     - `:title`             — string title; goes in the modal header.
     - `:hint`              — string/hiccup hint below the header.
     - `:snippet`           — string; the generated EDN snippet.
                              Re-rendered on every keystroke by the
                              caller before passing in.
     - `:placeholder-id`    — keyword fallback for the input's
                              `:default-value` when `:draft-id` is
                              nil (e.g. `:story.recorded/example` /
                              `:story.saved/example`).
     - `:placeholder-input` — string `:placeholder` for the input.
     - `:on-edit-id`        — `(fn [raw-string])` called on every
                              keystroke with the input's raw value.
                              Caller typically pipes through
                              `swap-parse-and-set-draft-id!`.
     - `:on-copy`           — `(fn [])` invoked when the user clicks
                              'copy to clipboard'.
     - `:on-discard`        — optional `(fn [])` — when provided, a
                              'discard' button renders left of
                              'copy'. Recorder uses (discards the
                              captured events); save-variant does not.
     - `:on-export`         — optional `(fn [])` — when provided, an
                              extra `[Export as :play-script]` button
                              renders left of 'copy'. The recorder's
                              save-dialog wires this through to the
                              play-script export dialog (rf2-x9zsr);
                              save-variant does not (no recording to
                              export).
     - `:on-close`          — `(fn [])` invoked on backdrop-click + on
                              the 'close' button.
     - `:data-test-prefix`  — string prefix for all `:data-test`
                              attrs in the rendered hiccup (e.g.
                              `\"story-recorder\"` /
                              `\"story-save-variant\"`)."
     [dialog-state
      {:keys [title hint snippet placeholder-id placeholder-input
              on-edit-id on-copy on-discard on-export on-close data-test-prefix]}]
     (when (:open? dialog-state)
       (let [draft-id     (:draft-id dialog-state)
             effective-id (or draft-id placeholder-id)
             dtest        (fn [suffix] (str data-test-prefix "-" suffix))]
         [:div {:style     (:modal-back styles)
                :data-test (dtest "dialog")
                :on-click  (fn [e]
                             (when (= (.-target e) (.-currentTarget e))
                               (on-close)))}
          [:div {:style    (:modal styles)
                 :on-click (fn [e] (.stopPropagation e))}
           [:div {:style (:modal-title styles)} title]
           (when hint
             [:div {:style (:hint styles)} hint])
           [:input
            {:type          "text"
             :style         (:id-input styles)
             :data-test     (dtest "id-input")
             :default-value (pr-str effective-id)
             :on-change     (fn [e] (on-edit-id (.. e -target -value)))
             :placeholder   placeholder-input}]
           [:pre {:style     (:snippet styles)
                  :data-test (dtest "snippet")}
            snippet]
           [:div {:style (:btn-row styles)}
            (when on-discard
              [:button
               {:style     (:btn-muted styles)
                :data-test (dtest "discard")
                :on-click  (fn [_] (on-discard))}
               "discard"])
            (when on-export
              [:button
               {:style     (:btn-muted styles)
                :data-test (dtest "export")
                :title     "Export the recording as a :play-script (rich DSL)"
                :on-click  (fn [_] (on-export))}
               "export as :play-script"])
            [:button
             {:style     (:btn styles)
              :data-test (dtest "copy")
              :on-click  (fn [_] (on-copy))}
             "copy to clipboard"]
            [:button
             {:style     (:btn-muted styles)
              :data-test (dtest "close")
              :on-click  (fn [_] (on-close))}
             "close"]]]]))))
