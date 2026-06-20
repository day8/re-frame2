(ns re-frame.story.ui.docs
  "The `:docs` mode pane — read-only AutoDocs-equivalent for one variant.
  Per spec/008.

  The pane composes six sections, in the order Storybook 8's MDX/AutoDocs
  page presents them:

      ┌──────────────────────────────────────────────────────┐
      │  Header — variant id · parent story · tags chips      │
      ├──────────────────────────────────────────────────────┤
      │  Prose — markdown from a :prose workspace referencing │
      │           this variant (if any)                       │
      ├──────────────────────────────────────────────────────┤
      │  Args  — table of every resolved arg with default +  │
      │           :doc (from the variant's :argtypes entry)   │
      ├──────────────────────────────────────────────────────┤
      │  Decorators — ordered :hiccup / :frame-setup /        │
      │           :fx-override stack                          │
      ├──────────────────────────────────────────────────────┤
      │  Parameters — :modes / :substrates / :platforms       │
      │           (the variant-level metadata that isn't args  │
      │           or decorators or tags)                      │
      ├──────────────────────────────────────────────────────┤
      │  Tags — clickable chips that toggle the sidebar       │
      │           `:tag-filter` for the matching tag          │
      └──────────────────────────────────────────────────────┘

  Read-only — no editing. Args overrides live in the Canvas / Controls
  pane. Switching from `:docs` back to `:dev` (Canvas) restores any
  override the user had previously typed in the controls editor.

  ## Pure-data prose lookup

  Prose blocks come from `:layout :prose` workspaces — Story v1 has no
  per-variant `:prose` slot (a deliberate Stage-2 schema choice; prose
  belongs to a workspace, not a variant). For the docs view we walk
  every registered `:prose` workspace and collect the `:body` strings
  of any `:type :prose` content item whose neighbour `:type :variant`
  item references this variant. The walk is pure data → data and
  JVM-testable via `prose-for-variant`.

  ## Elision

  The whole namespace is CLJS-side rendering plus one `.cljc` pure
  helper (`prose-for-variant`) so JVM tests can cover the workspace
  walk. The shell's `main-pane` only reaches `docs-view` via the
  `(when config/enabled? ...)`-gated mount call, so production builds
  never invoke it — closure DCEs the lot."
  (:require [re-frame.story.budgets    :as budgets]
            [re-frame.story.predicates :as pred]
            [re-frame.story.plan       :as plan]
            [re-frame.story.registrar  :as registrar]
            [re-frame.story.ui.evidence-spine :as spine]
            [re-frame.story.ui.markdown :as md]
            [re-frame.story.ui.test-mode.pure :as test-pure]
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.story.args :as args]
                       [re-frame.story.decorators :as decorators]
                       [re-frame.story.ui.state :as state]])
            [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- pure: prose lookup -------------------------------------------------

(defn prose-for-variant
  "Walk every registered `:layout :prose` workspace, find any whose
  `:content` references `variant-id`, and return a vector of
  `{:workspace-id ... :body ...}` entries for each prose block that
  sits in the same workspace.

  Pure data → data; JVM-testable. The walk is O(W·C) over the workspace
  count × content-item count — fine for dev-time use.

  A prose block belongs in the variant's docs view when its host
  workspace's `:content` list also references the variant somewhere.
  Storybook's MDX prose is per-component; re-frame2 collapses that to
  per-workspace, and a workspace that mentions the variant earns its
  prose."
  [variant-id]
  (let [workspaces (registrar/registrations :workspace)]
    (vec
      (for [[wid body] (sort-by key workspaces)
            :when     (and (= :prose (:layout body))
                           (some (fn [item]
                                   (and (= :variant (:type item))
                                        (= variant-id (:id item))))
                                 (:content body)))
            item      (:content body)
            :when     (= :prose (:type item))]
        {:workspace-id wid
         :body         (:body item)}))))

;; ---- pure: row composition -----------------------------------------------

(defn args-rows
  "Compose the rows of the args table.

  Each row is `{:key <arg-key> :value <default> :doc <string-or-nil>}`.
  The doc string comes from the variant's (or parent story's)
  `:argtypes` entry — Storybook's `argTypes.<key>.description` —
  resolved per the same merge precedence as `controls/resolve-argtypes`
  (variant beats story).

  Pure data → data so the JVM test fixture can cover the merge."
  [variant-id resolved-args]
  (let [vb       (registrar/handler-meta :variant variant-id)
        story-id (when (and (keyword? variant-id) (namespace variant-id))
                   (keyword (namespace variant-id)))
        sb       (when story-id (registrar/handler-meta :story story-id))
        atypes   (merge (:argtypes sb) (:argtypes vb))]
    (vec
      (for [[k v] (sort-by key (or resolved-args {}))]
        {:key   k
         :value v
         :doc   (let [entry (get atypes k)]
                  (cond
                    (map? entry)    (or (:doc entry) (:description entry))
                    (string? entry) entry
                    :else           nil))}))))

(defn decorator-rows
  "Compose the rows of the decorator-stack table from a
  `resolve-decorators` pack. Each row is

      {:section :hiccup|:frame-setup|:fx-override
       :id      <decorator-id>
       :doc     <:doc on body, or nil>}

  Stage 6's `:errors` slot is shown in a dedicated row group at the
  bottom (with `:section :error`)."
  [pack]
  (let [{:keys [hiccup frame-setup fx-override errors]} (or pack {})]
    (vec
      (concat
        (for [d hiccup]
          {:section :hiccup :id (:id d) :doc (:doc (:body d))})
        (for [d frame-setup]
          {:section :frame-setup :id (:id d) :doc (:doc (:body d))})
        (for [d fx-override]
          {:section :fx-override :id (:id d) :doc (:doc (:body d))})
        (for [e errors]
          {:section :error :id (:id e) :doc (str (:reason e))})))))

(defn parameter-rows
  "Compose the rows of the parameters table — the variant-level
  metadata that's neither args nor decorators nor tags. Per spec/008
  §Parameters this maps to `:modes` / `:substrates` / `:platforms` on
  the variant body, plus the story-level defaults.

  Each row is `{:key :modes|:substrates|:platforms
                 :value <set-or-vector-as-string>}`."
  [variant-id]
  (let [vb       (registrar/handler-meta :variant variant-id)
        story-id (when (and (keyword? variant-id) (namespace variant-id))
                   (keyword (namespace variant-id)))
        sb       (when story-id (registrar/handler-meta :story story-id))
        get-eff  (fn [k] (or (get vb k) (get sb k)))]
    (vec
      (keep
        (fn [k]
          (let [v (get-eff k)]
            (when (seq v) {:key k :value v})))
        [:modes :substrates :platforms]))))

(defn variant-tags
  "Return the sorted vector of tags on `variant-id`. Falls back to the
  parent story's `:tags` set if the variant body declares none. Used
  by both the docs header chips and the bottom-of-page tag picker."
  [variant-id]
  (let [vb       (registrar/handler-meta :variant variant-id)
        story-id (pred/parent-story-id variant-id)
        sb       (when story-id (registrar/handler-meta :story story-id))
        ts       (or (:tags vb) (:tags sb) #{})]
    (vec (sort ts))))

;; The header chips call `pred/parent-story-id` directly.

;; ---- pure: status / fidelity / schema / evidence excerpt ----------------
;;
;; spec/022 §1 + §2 — Docs mode adds fidelity badges, world-input /
;; runner-requirement chips, a view-arg schema table, a current test-status
;; summary, and a SPARSE evidence excerpt that links into Inspector/Xray. The
;; contract that load-bears this whole section: Docs and Test MUST agree
;; because they read the SAME plan/result. So:
;;
;;   - the status verdict is `test-pure/run-status` over the SAME unified
;;     run-result Test mode wrote (the `evidence-spine/result-for` slot) —
;;     docs never re-runs the variant nor re-derives the verdict;
;;   - fidelity / world-input / runner-requirement come from the variant's
;;     compiled PLAN (`plan/variant-plan`) — the same plan the runner builds,
;;     so the badges match what a run would rest on;
;;   - the evidence excerpt is a SPARSE projection of the SAME spine the
;;     evidence-spine display renders (`spine/spine-spans`), capped to a couple
;;     of beats — a curated pointer, NOT a second evidence renderer (spec/022
;;     §2). The deep beat tree / app-db diff stays in Inspector/Xray, reached
;;     through the spine's focus links.
;;
;; All four are pure data → data so the JVM test corpus covers the shaping
;; without booting Reagent / the runtime / Xray.

(defn variant-plan-quietly
  "Compile `variant-id` into its normalized plan (`plan/variant-plan`),
  returning nil rather than throwing when compilation fails (an unknown
  variant, a malformed `:extends` chain, an invalid view-arg / sub-override
  value). Pure data → data. Docs is a read-only projection — a variant whose
  plan does not compile still renders its header / prose / args; the
  status-and-fidelity surface just degrades to 'plan unavailable' rather than
  taking the whole pane down."
  [variant-id]
  (try
    (plan/variant-plan variant-id)
    (catch #?(:clj Exception :cljs :default) _ nil)))

(def fidelity-rungs
  "The fidelity-ladder rungs (highest → lowest, spec/017 §View-state
  subscription overrides → `plan/compute-fidelity`). Pure data so the badge
  renderer can order + label the `:fidelity` SET a plan carries. A reviewer
  reads the rungs to know which evidence a variant's render rests on:
  `:real-setup` (real events) beats `:db-seed` (a schema-checked app-db seed)
  beats `:sub-overrides` (pinned sub values, legitimate when labelled)."
  [{:rung :real-setup    :label "real setup"    :strength :high}
   {:rung :db-seed       :label "db seed"       :strength :mid}
   {:rung :sub-overrides :label "sub overrides" :strength :low}])

(defn fidelity-badges
  "Project a plan's `:fidelity` SET into ordered badge rows (highest-fidelity
  rung first). Pure data → data. Each row is `{:rung … :label … :strength …
  :present? <bool>}` — the renderer tints a present rung by strength and
  greys an absent one, so the ladder reads as a whole (a variant resting only
  on `:sub-overrides` visibly lacks the higher rungs). An empty `:fidelity`
  set (the render-the-view-as-mounted variant) marks every rung absent."
  [plan]
  (let [present (or (:fidelity plan) #{})]
    (mapv (fn [rung] (assoc rung :present? (contains? present (:rung rung))))
          fidelity-rungs)))

(defn world-input-chips
  "Project a plan's `:world` slot into the world-input chips Docs surfaces
  (spec/022 §1). Pure data → data. Each chip is `{:key … :label … :present?
  <bool>}` for the world inputs a variant may declare — setup events, a
  script, a db-seed, sub-overrides, and network stubs. A chip is `:present?`
  only when the variant actually declares that input, so the row reads as 'what
  drives this variant's world' rather than a fixed checklist. Returns only the
  PRESENT chips (a docs surface stays terse — absent world inputs are simply
  not shown)."
  [plan]
  (let [world (:world plan)
        chips [{:key :setup         :label "setup events" :present? (boolean (seq (:setup plan)))}
               {:key :script        :label "script"       :present? (boolean (seq (:script plan)))}
               {:key :db-seed       :label "db seed"      :present? (some? (:db-seed world))}
               {:key :sub-overrides :label "sub overrides"
                :present? (boolean (seq (get-in world [:render :sub-overrides])))}
               {:key :network       :label "network stubs"
                :present? (boolean (seq (:network world)))}]]
    (filterv :present? chips)))

(defn required-runner-tokens
  "The sorted vector of runner-requirement capability tokens a plan carries
  (`plan`'s `:required-runner` SET — the tokens a unit needs, spec/017
  §Runner requirements). Pure data → data. Docs renders these as chips so a
  reader sees what the variant's evidence requires of a runner BEFORE opening
  Test mode. Empty when the plan needs no special capability."
  [plan]
  (vec (sort (:required-runner plan))))

(defn status-summary
  "Project the SAME unified run-result Test mode wrote into the compact
  status summary Docs shows (spec/022 §1 — a current test-status summary).
  Pure data → data. THE docs↔Test agreement point: the verdict is
  `test-pure/run-status` over the result + its `aggregate-summary` fold, the
  EXACT projection Test mode's summary pill uses — so a tape-floor `:fail` /
  a `:cannot-run` refusal reads identically in Docs and Test, and Docs can
  never disagree with Test about whether a variant is green.

  `result` is the variant's unified run-result (or nil — never run yet);
  `summary` is its `aggregate-summary` fold (pass/fail/cannot-run/total).
  Returns:

      {:status :pass|:fail|:error|:cannot-run|:pending
       :passed <n> :failed <n> :cannot-run <n> :total <n>
       :ran?   <bool>}                ; a run has executed (vs :pending)

  A nil result is `:pending` with zero counts — Docs renders 'not run yet'
  and points at Test mode rather than fabricating a verdict."
  [result summary]
  (let [{:keys [passed failed cannot-run total]} (or summary {})
        status (test-pure/run-status result summary)]
    {:status     status
     :passed     (or passed 0)
     :failed     (or failed 0)
     :cannot-run (or cannot-run 0)
     :total      (or total 0)
     :ran?       (boolean (and result (not= :pending status)))}))

(defn view-arg-schema-rows
  "Project a plan's `[:world :view-args-schema]` into the view-arg schema
  table rows (spec/022 §1 — a view-arg schema table). Pure data → data.
  Distinct from the Args table (which shows resolved DEFAULT values): the
  schema table shows the view's declared INPUT contract — which props the
  rendered view accepts, and whether each is required.

  Reads a top-level Malli `[:map …]` schema's entries; each row is
  `{:key … :required? <bool> :schema <entry-schema>}` (a non-`{:optional
  true}` entry is required, mirroring `plan/validate-effective-args`'
  required-key floor). A non-`[:map]` schema (or no schema on file) yields an
  empty vector — Docs omits the section rather than inventing a table."
  [plan]
  (let [schema (get-in plan [:world :view-args-schema])]
    (if (and (vector? schema) (= :map (first schema)))
      (let [entries (filter vector? (rest schema))]
        (mapv (fn [[k props child :as entry]]
                (let [;; a [:map [k child]] entry has no props; [k {…} child] has
                      props?    (map? props)
                      required? (not (and props? (:optional props)))
                      ent-sch   (if props? child props)]
                  {:key       k
                   :required? required?
                   :schema    (if (= 3 (count entry)) ent-sch (or ent-sch props))}))
              entries))
      [])))

(def evidence-excerpt-beat-cap
  "How many narrative beats the SPARSE docs evidence excerpt surfaces inline
  (spec/022 §2 — 'one or two narrative beats'). Docs is curated documentation,
  not a debug log: the excerpt shows AT MOST this many beats; the rest live in
  the evidence-spine display / Inspector, reached through the focus link.

  Single source of truth: `re-frame.story.budgets/evidence-gesture-budget`
  `:excerpt-beats` (X1 — the failure→evidence budget; spec/018 §10)."
  (:excerpt-beats budgets/evidence-gesture-budget))

(defn evidence-excerpt
  "Project the SAME spine the evidence-spine display renders into a SPARSE
  docs excerpt (spec/022 §2). Pure data → data. NOT a second evidence
  renderer — a curated pointer: it reuses `spine/spine-spans` (the canonical
  projection over the run's `:narrative`), flattens to the run's beats, and
  caps the inline excerpt to `evidence-excerpt-beat-cap` beats. Each excerpt
  beat carries only the compact, readable fields (its trigger / epoch /
  strength / summary + focus coords) — never the full beat tree, app-db diff,
  or trace dump (those stay in Inspector/Xray, reached via the focus link).

  Returns:

      {:available? <bool>           ; the run retained a non-empty narrative
       :beats      [<beat> …]       ; AT MOST the cap, the run's leading beats
       :beat-count <int>            ; total beats in the run
       :more       <int>}           ; beats beyond the excerpt (link to see all)

  An empty / nil narrative is `{:available? false :beats [] :beat-count 0
  :more 0}` — Docs renders an honest 'no evidence yet' line pointing at Test
  mode, never a fabricated excerpt."
  [narrative]
  (let [spans (spine/spine-spans narrative)
        beats (vec (mapcat :beats spans))
        n     (count beats)]
    {:available? (pos? n)
     :beats      (vec (take evidence-excerpt-beat-cap beats))
     :beat-count n
     :more       (max 0 (- n evidence-excerpt-beat-cap))}))

;; ---- CLJS-side rendering -------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap          {:flex             "1"
                      :overflow         "auto"
                      :padding          "20px 28px"
                      :background       (:bg-canvas colors/tokens)
                      :color            (:text-primary colors/tokens)
                      :font-family      sans-stack
                      :font-size        (:body typography/type-scale)
                      :line-height      "1.5"}
      :h1            {:font-family      mono-stack
                      :font-size        (:display typography/type-scale)
                      :font-weight      "bold"
                      :color            "white"
                      :margin           "0 0 4px 0"}
      :sub           {:color            (:text-secondary colors/tokens)
                      :font-family      mono-stack
                      :font-size        (:caption typography/type-scale)
                      :margin-bottom    "10px"}
      :section       {:margin-top       "24px"}
      :section-h     {:font-weight      "bold"
                      :color            (:text-secondary colors/tokens)
                      :text-transform   "uppercase"
                      :font-size        (:micro typography/type-scale)
                      :letter-spacing   "0.5px"
                      :margin-bottom    "8px"
                      :border-bottom    "1px solid #444"
                      :padding-bottom   "4px"}
      :doc-blurb     {:color            (:text-secondary colors/tokens)
                      :font-style       "italic"
                      :margin           "4px 0 12px 0"}
      :prose-block   {:padding          "12px 16px"
                      :margin-bottom    "8px"
                      :background       (:bg-2 colors/tokens)
                      :border-left      "3px solid #0e639c"
                      :color            (:text-primary colors/tokens)
                      :font-family      sans-stack}
      :prose-source  {:color            (:text-secondary colors/tokens)
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :margin-bottom    "4px"}
      :table         {:width            "100%"
                      :border-collapse  "collapse"
                      :font-family      mono-stack
                      :font-size        (:caption typography/type-scale)}
      :th            {:text-align       "left"
                      :padding          "6px 8px"
                      :background       (:bg-2 colors/tokens)
                      :color            (:text-secondary colors/tokens)
                      :border-bottom    "1px solid #444"
                      :text-transform   "uppercase"
                      :font-size        (:micro typography/type-scale)
                      :letter-spacing   "0.5px"}
      :td            {:padding          "6px 8px"
                      :border-bottom    "1px solid #2d2d30"
                      :color            (:text-primary colors/tokens)
                      :vertical-align   "top"}
      :td-key        {:color            (:info colors/tokens)
                      :white-space      "nowrap"}
      :td-value      {:color            (:tag-experimental-fg colors/tokens)
                      :font-family      mono-stack
                      :white-space      "pre-wrap"}
      :td-doc        {:color            (:text-secondary colors/tokens)
                      :font-style       "italic"}
      :section-h-row {:background       (:bg-3 colors/tokens)
                      :color            (:text-secondary colors/tokens)
                      :text-transform   "uppercase"
                      :font-size        (:micro typography/type-scale)
                      :letter-spacing   "0.5px"}
      :chip-row      {:display          "flex"
                      :flex-wrap        "wrap"
                      :gap              "6px"
                      :margin-top       "4px"}
      :chip          {:padding          "3px 9px"
                      :background       (:bg-3 colors/tokens)
                      :color            (:text-primary colors/tokens)
                      :border           "none"
                      :border-radius    "10px"
                      :cursor           "pointer"
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :user-select      "none"}
      :chip-active   {:background       (:accent-amber colors/tokens)
                      :color            "white"}
      :empty         {:color            (:text-tertiary colors/tokens)
                      :font-style       "italic"
                      :padding          "6px 0"}
      ;; ---- status / fidelity / evidence excerpt (spec/022) ----
      :badge-row     {:display          "flex"
                      :flex-wrap        "wrap"
                      :gap              "6px"
                      :align-items      "center"
                      :margin-bottom    "8px"}
      :status-pill   {:padding          "3px 10px"
                      :border-radius    "10px"
                      :font-family      mono-stack
                      :font-size        (:caption typography/type-scale)
                      :font-weight      "bold"
                      :text-transform   "uppercase"
                      :letter-spacing   "0.5px"}
      :status-pass   {:background       (:success-bg colors/tokens)
                      :color            (:success colors/tokens)}
      :status-fail   {:background       (:danger-bg colors/tokens)
                      :color            (:danger colors/tokens)}
      :status-error  {:background       (:danger-bg colors/tokens)
                      :color            (:danger colors/tokens)
                      :border           (str "1px solid " (:danger colors/tokens))}
      :status-cannot {:background       (:warning-bg colors/tokens)
                      :color            (:warning colors/tokens)}
      :status-pending {:background      (:bg-3 colors/tokens)
                       :color           (:text-tertiary colors/tokens)}
      :status-counts {:color            (:text-secondary colors/tokens)
                      :font-family      mono-stack
                      :font-size        (:caption typography/type-scale)}
      :label-key     {:color            (:text-tertiary colors/tokens)
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :text-transform   "uppercase"
                      :letter-spacing   "0.4px"
                      :margin-right     "2px"}
      :badge         {:padding          "2px 8px"
                      :border-radius    "8px"
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :background       (:bg-3 colors/tokens)
                      :color            (:text-secondary colors/tokens)
                      :border           (str "1px solid " (:border-subtle colors/tokens))}
      :badge-high    {:background       (:success-bg colors/tokens)
                      :color            (:success colors/tokens)}
      :badge-mid     {:background       (:info-bg colors/tokens)
                      :color            (:info colors/tokens)}
      :badge-low     {:background       (:warning-bg colors/tokens)
                      :color            (:warning colors/tokens)}
      :badge-absent  {:background       "transparent"
                      :color            (:text-tertiary colors/tokens)
                      :border           (str "1px dashed " (:border-subtle colors/tokens))
                      :opacity          "0.7"}
      :runner-chip   {:padding          "2px 8px"
                      :border-radius    "3px"
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :background       (:bg-3 colors/tokens)
                      :color            (:text-primary colors/tokens)}
      :excerpt-beat  {:display          "flex"
                      :flex-direction   "column"
                      :gap              "3px"
                      :padding          "8px 10px"
                      :margin-bottom    "6px"
                      :border-radius    "4px"
                      :background       (:bg-2 colors/tokens)
                      :border-left      (str "3px solid " (:border-subtle colors/tokens))}
      :excerpt-h     {:display          "flex"
                      :align-items      "baseline"
                      :gap              "8px"
                      :flex-wrap        "wrap"}
      :excerpt-trig  {:font-family      mono-stack
                      :font-size        (:caption typography/type-scale)
                      :color            (:text-primary colors/tokens)
                      :word-break       "break-all"}
      :excerpt-epoch {:font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :color            (:text-tertiary colors/tokens)}
      :excerpt-link  {:font-family      sans-stack
                      :font-size        (:caption typography/type-scale)
                      :background       (:bg-3 colors/tokens)
                      :color            (:info colors/tokens)
                      :border           (str "1px solid " (:border-default colors/tokens))
                      :border-radius    "6px"
                      :padding          "3px 10px"
                      :cursor           "pointer"
                      :user-select      "none"}
      :excerpt-more  {:color            (:text-tertiary colors/tokens)
                      :font-style       "italic"
                      :font-size        (:caption typography/type-scale)
                      :margin-top       "4px"}}))

#?(:cljs
   (defn- pretty-value
     "Render a default-arg value for the args table. Strings show
     quoted; everything else uses `pr-str` so keywords / maps / vectors
     are visibly distinguishable from strings."
     [v]
     (cond
       (nil? v)    "nil"
       (string? v) (pr-str v)
       :else       (pr-str v))))

#?(:cljs
   (defn- header
     "Render the variant header — id, parent story, tags as chips that
     forward-link to the sidebar tag filter."
     [variant-id]
     (let [shell      @state/shell-state-atom
           tag-filter (or (:tag-filter shell) #{})
           tags       (variant-tags variant-id)
           vb         (registrar/handler-meta :variant variant-id)
           story-id   (pred/parent-story-id variant-id)
           sb         (when story-id (registrar/handler-meta :story story-id))
           vdoc       (:doc vb)
           sdoc       (:doc sb)]
       [:div
        [:h1 {:style (:h1 styles)}
         (str variant-id)]
        [:div {:style (:sub styles)
               :data-test "story-docs-parent-story"}
         (if story-id
           (str "parent story: " story-id)
           "no parent story registered")]
        (when (seq tags)
          [:div {:style     (:chip-row styles)
                 :data-test "story-docs-header-tags"}
           (for [tag tags]
             ^{:key tag}
             [:button
              {:style    (merge (:chip styles)
                                (when (contains? tag-filter tag)
                                  (:chip-active styles)))
               :data-test       "story-docs-tag-chip"
               :data-docs-tag   (name tag)
               :aria-pressed    (if (contains? tag-filter tag) "true" "false")
               :title           (str "Toggle tag filter for " tag)
               :on-click
               (fn [_]
                 (state/swap-state! state/toggle-tag-filter tag))}
              (str tag)])])
        (when (or (seq vdoc) (seq sdoc))
          [:div {:style     (:doc-blurb styles)
                 :data-test "story-docs-doc-blurb"}
           (or vdoc sdoc)])])))

#?(:cljs
   (defn- prose-section
     "Render the prose blocks pulled from `:prose` workspaces that
     reference this variant. Renders nothing when no prose is found —
     deliberately not a 'no prose' placeholder, the page reads cleaner
     without it (the args table is the primary docs surface)."
     [variant-id]
     (let [entries (prose-for-variant variant-id)]
       (when (seq entries)
         [:div {:style     (:section styles)
                :data-test "story-docs-prose-section"}
          [:div {:style (:section-h styles)} "Prose"]
          (for [[i {:keys [workspace-id body]}] (map-indexed vector entries)]
            ^{:key i}
            [:div {:data-test "story-docs-prose-block"}
             [:div {:style (:prose-source styles)}
              (str "from " workspace-id)]
             [:div {:style     (:prose-block styles)
                    :data-test "story-docs-prose-rendered"}
              (md/parse body)]])]))))

#?(:cljs
   (defn- args-section
     "Args table — every resolved arg with default + :doc (when an
     `:argtypes` entry exists)."
     [variant-id]
     (let [shell    @state/shell-state-atom
           eff-args (args/resolve-args
                      variant-id
                      {:active-modes (:active-modes shell)
                       ;; :docs is read-only — overrides are deliberately
                       ;; left out so the table reflects the variant's
                       ;; declared shape, not the user's transient edits.
                       :cell-overrides nil})
           rows     (args-rows variant-id eff-args)]
       [:div {:style     (:section styles)
              :data-test "story-docs-args-section"}
        [:div {:style (:section-h styles)} "Args"]
        (if (empty? rows)
          [:div {:style (:empty styles)} "no args resolved on this variant"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-args-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "key"]
             [:th {:style (:th styles)} "default"]
             [:th {:style (:th styles)} "doc"]]]
           [:tbody
            (for [{:keys [key value doc]} rows]
              ^{:key key}
              [:tr {:data-test "story-docs-args-row"
                    :data-arg-key (str key)}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (str key)]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (pretty-value value)]
               [:td {:style (merge (:td styles) (:td-doc styles))}
                (or doc "—")]])]])])))

#?(:cljs
   (defn- decorators-section
     "Decorator stack — three groups (hiccup / frame-setup / fx-override)
     in apply-order. Story-level decorators come first per
     `resolve-decorators` semantics."
     [variant-id]
     (let [shell @state/shell-state-atom
           ;; Thread the active-modes into `resolve-decorators`
           ;; so the plan recompile substitutes `[:arg]` keys resolvable
           ;; only through a mode layer. Mirrors `args-section` (docs is
           ;; read-only — overrides deliberately excluded; the variant's
           ;; declared shape, mode-aware, is what the docs surface shows).
           pack (decorators/resolve-decorators
                  variant-id
                  {:active-modes (:active-modes shell) :cell-overrides nil})
           rows (decorator-rows pack)]
       [:div {:style     (:section styles)
              :data-test "story-docs-decorators-section"}
        [:div {:style (:section-h styles)} "Decorators"]
        (if (empty? rows)
          [:div {:style (:empty styles)}
           "no decorators on this variant"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-decorators-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "kind"]
             [:th {:style (:th styles)} "id"]
             [:th {:style (:th styles)} "doc"]]]
           [:tbody
            (for [[i {:keys [section id doc]}] (map-indexed vector rows)]
              ^{:key i}
              [:tr {:data-test     "story-docs-decorator-row"
                    :data-section  (name section)}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (name section)]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (str id)]
               [:td {:style (merge (:td styles) (:td-doc styles))}
                (or doc "—")]])]])])))

#?(:cljs
   (defn- parameters-section
     "Parameters — `:modes` / `:substrates` / `:platforms` on the
     variant body, falling back to the parent story. Per spec/008
     §Parameters re-frame2 collapses Storybook's free-form
     `:parameters` map into these three explicit slots."
     [variant-id]
     (let [rows (parameter-rows variant-id)]
       [:div {:style     (:section styles)
              :data-test "story-docs-parameters-section"}
        [:div {:style (:section-h styles)} "Parameters"]
        (if (empty? rows)
          [:div {:style (:empty styles)}
           "no :modes / :substrates / :platforms declared"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-parameters-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "key"]
             [:th {:style (:th styles)} "value"]]]
           [:tbody
            (for [{:keys [key value]} rows]
              ^{:key key}
              [:tr {:data-test "story-docs-parameter-row"
                    :data-param-key (name key)}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (str key)]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (pr-str value)]])]])])))

#?(:cljs
   (defn- tags-section
     "Bottom tag picker — clickable chips that forward-link into the
     sidebar tag filter. Re-uses the controls-panel chip style so the
     surface feels familiar; selecting a chip dispatches
     `state/toggle-tag-filter` and the sidebar re-renders with the
     constrained tree."
     [variant-id]
     (let [shell      @state/shell-state-atom
           tag-filter (or (:tag-filter shell) #{})
           tags       (variant-tags variant-id)]
       [:div {:style     (:section styles)
              :data-test "story-docs-tags-section"}
        [:div {:style (:section-h styles)} "Tags"]
        (if (empty? tags)
          [:div {:style (:empty styles)}
           "no tags on this variant"]
          [:div {:style     (:chip-row styles)
                 :data-test "story-docs-tags-row"}
           (for [tag tags]
             ^{:key tag}
             [:button
              {:style          (merge (:chip styles)
                                      (when (contains? tag-filter tag)
                                        (:chip-active styles)))
               :data-test      "story-docs-tag-link"
               :data-docs-tag  (name tag)
               :aria-pressed   (if (contains? tag-filter tag) "true" "false")
               :title          (str "Toggle tag filter for " tag)
               :on-click
               (fn [_]
                 (state/swap-state! state/toggle-tag-filter tag))}
              (str tag)])])])))

#?(:cljs
   (defn- status-pill-style
     "Pick the status-pill style for a `:test`-agreed verdict."
     [status]
     (case status
       :pass       (:status-pass styles)
       :fail       (:status-fail styles)
       :error      (:status-error styles)
       :cannot-run (:status-cannot styles)
       (:status-pending styles))))

#?(:cljs
   (defn- status-pill-text
     "Human-readable pill text for a verdict — mirrors Test mode's
     summary-pill wording so the two surfaces read the same."
     [{:keys [status passed failed cannot-run total]}]
     (case status
       :pass       (str passed " passed")
       :error      "error"
       :cannot-run (str cannot-run " cannot-run of " total)
       :pending    "not run yet"
       (str failed " failed of " total))))

#?(:cljs
   (defn- badge-strength-style
     [strength]
     (case strength
       :high (:badge-high styles)
       :mid  (:badge-mid styles)
       :low  (:badge-low styles)
       nil)))

#?(:cljs
   (defn- status-fidelity-section
     "Status + fidelity + world-input + runner-requirement summary (spec/022
     §1). The status verdict is `status-summary` over the SAME unified
     run-result Test mode wrote (`spine/result-for`) — Docs and Test agree by
     reading one result, not two. Fidelity badges / world-input chips /
     runner-requirement chips come from the variant's compiled PLAN, the same
     plan the runner builds. Read-only — a curated status snapshot, not a
     control surface; the Re-run affordance lives in Test mode."
     [variant-id plan]
     (let [result   (spine/result-for variant-id)
           summary  (state/aggregate-summary (:assertions result))
           status   (status-summary result summary)
           badges   (fidelity-badges plan)
           worlds   (world-input-chips plan)
           runners  (required-runner-tokens plan)]
       [:div {:style     (:section styles)
              :data-test "story-docs-status-section"}
        [:div {:style (:section-h styles)} "Status & fidelity"]
        ;; status pill — the docs↔Test agreement point.
        [:div {:style (:badge-row styles)}
         [:span {:style       (merge (:status-pill styles) (status-pill-style (:status status)))
                 :data-test   "story-docs-status-pill"
                 :data-status (name (:status status))}
          (status-pill-text status)]
         (when (:ran? status)
           [:span {:style     (:status-counts styles)
                   :data-test "story-docs-status-counts"}
            (str "✓ " (:passed status) "  ✗ " (:failed status)
                 "  ⊘ " (:cannot-run status))])]
        ;; fidelity ladder — every rung, present tinted by strength.
        [:div {:style (:badge-row styles)}
         [:span {:style (:label-key styles)} "fidelity"]
         (for [{:keys [rung label strength present?]} badges]
           ^{:key (name rung)}
           [:span {:style       (merge (:badge styles)
                                       (if present?
                                         (badge-strength-style strength)
                                         (:badge-absent styles)))
                   :data-test   "story-docs-fidelity-badge"
                   :data-rung   (name rung)
                   :data-present (str (boolean present?))}
            label])]
        ;; world inputs — only the ones the variant declares.
        (when (seq worlds)
          [:div {:style (:badge-row styles)}
           [:span {:style (:label-key styles)} "world"]
           (for [{:keys [key label]} worlds]
             ^{:key (name key)}
             [:span {:style     (:badge styles)
                     :data-test "story-docs-world-chip"
                     :data-world (name key)}
              label])])
        ;; runner requirements — what the variant's evidence needs of a runner.
        (when (seq runners)
          [:div {:style (:badge-row styles)}
           [:span {:style (:label-key styles)} "runner"]
           (for [token runners]
             ^{:key (str token)}
             [:span {:style     (:runner-chip styles)
                     :data-test "story-docs-runner-chip"}
              (str token)])])])))

#?(:cljs
   (defn- schema-section
     "View-arg schema table (spec/022 §1) — the rendered view's declared
     INPUT contract (which props it accepts + which are required), distinct
     from the Args table's resolved defaults. Renders nothing when the view
     has no `:view-args-schema` on file — Docs omits the section rather than
     inventing an empty table."
     [_variant-id plan]
     (let [rows (view-arg-schema-rows plan)]
       (when (seq rows)
         [:div {:style     (:section styles)
                :data-test "story-docs-schema-section"}
          [:div {:style (:section-h styles)} "View-arg schema"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-schema-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "prop"]
             [:th {:style (:th styles)} "required"]
             [:th {:style (:th styles)} "schema"]]]
           [:tbody
            (for [{:keys [key required? schema]} rows]
              ^{:key (str key)}
              [:tr {:data-test     "story-docs-schema-row"
                    :data-prop-key (str key)
                    :data-required (str (boolean required?))}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (str key)]
               [:td {:style (merge (:td styles) (:td-doc styles))}
                (if required? "required" "optional")]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (pr-str schema)]])]]]))))

#?(:cljs
   (defn- excerpt-beat-row
     "Render ONE sparse excerpt beat — the trigger event, its epoch, the
     evidence-strength tags, and the compact summary chips, plus a single
     'Open in Inspector' focus link. Reuses the evidence-spine's
     `strength`/`summary` projection (decorated by `spine/spine-spans`) and
     its `focus-beat!` side effect — Docs LINKS into the spine/Xray, it never
     rebuilds the beat tree."
     [variant-id beat]
     [:div {:style       (:excerpt-beat styles)
            :data-test   "story-docs-evidence-beat"
            :data-epoch-id (str (:epoch-id beat))}
      [:div {:style (:excerpt-h styles)}
       [:span {:style (:excerpt-trig styles)}
        (if-let [te (:trigger-event beat)]
          (pr-str (if (vector? te) (first te) te))
          "(no trigger event)")]
       [:span {:style (:excerpt-epoch styles)}
        (str "epoch " (:epoch-id beat))]]
      (let [{:keys [direct? attributed?]} (:strength beat)
            {:keys [precise?]}            (:focus beat)]
        [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap" :align-items "center"}}
         (for [{:keys [k label count]} (:summary beat)]
           ^{:key (name k)}
           [:span {:style     (:badge styles)
                   :data-test "story-docs-evidence-chip"
                   :data-slot (name k)}
            (str label " " count)])
         (when (and (not direct?) attributed?)
           [:span {:style     (merge (:badge styles) (:badge-low styles))
                   :data-test "story-docs-evidence-attributed"}
            "attributed only"])
         ;; Per-beat link into Xray — reuses the evidence-spine's host-facing
         ;; `focus-beat!` focus seam. Docs LINKS to the detailed diagnostics;
         ;; it never inlines the beat tree / app-db diff.
         [:button {:style     (:excerpt-link styles)
                   :data-test "story-docs-evidence-focus"
                   :data-precise (str (boolean precise?))
                   :title     (if precise?
                                "Focus this beat's epoch in the Xray Inspector"
                                "Open the Xray Inspector (no epoch pin for this beat)")
                   :on-click  (fn [e]
                                (.stopPropagation e)
                                (spine/focus-beat! variant-id beat spine/default-focus-panel))}
          "Inspect in Xray"]])]))

#?(:cljs
   (defn- evidence-excerpt-section
     "Sparse evidence excerpt (spec/022 §2). Reads the SAME run-result Test
     mode wrote (`spine/result-for`) and projects a couple of leading
     narrative beats through `evidence-excerpt` — a curated pointer into the
     evidence spine, NOT a debugging log. Detailed diagnostics (the full beat
     tree, app-db diff, trace) live in the Inspector / Xray, reached through
     the 'Open full evidence' link (`spine/open!`) and the spine's own focus
     links. When the variant has not been run (or retained no narrative) the
     section reads an honest 'no evidence yet' line pointing at Test mode."
     [variant-id]
     (let [result    (spine/result-for variant-id)
           narrative (:narrative result)
           {:keys [available? beats beat-count more]} (evidence-excerpt narrative)]
       [:div {:style     (:section styles)
              :data-test "story-docs-evidence-section"}
        [:div {:style (:section-h styles)} "Evidence"]
        (if-not available?
          [:div {:style     (:empty styles)
                 :data-test "story-docs-evidence-empty"}
           "No retained run evidence yet — run this variant in Test mode to "
           "see its narrative."]
          [:div {:data-test "story-docs-evidence-excerpt"
                 :data-beat-count (str beat-count)}
           [:div {:style (:doc-blurb styles)}
            "A sparse excerpt of the run narrative — the detailed diagnostics "
            "live in the Inspector."]
           (for [[i beat] (map-indexed vector beats)]
             ^{:key i}
             [excerpt-beat-row variant-id beat])
           [:div {:style (:excerpt-more styles)}
            (when (pos? more)
              [:span {:data-test "story-docs-evidence-more"}
               (str "+ " more " more beat" (when (not= 1 more) "s")
                    " in the full evidence spine. ")])
            [:button {:style     (:excerpt-link styles)
                      :data-test "story-docs-evidence-open"
                      :title     "Open the full evidence spine in the Inspector"
                      :on-click  (fn [_] (spine/open! variant-id nil))}
             "Open full evidence in Inspector"]]])])))

;; ---- pure: TOC entries --------------------------------------------------

(def docs-toc-entries
  "Canonical TOC entry table for the `:docs` mode pane (spec/022). Pure data
  → data so JVM tests can assert the table shape. The header section renders
  as the variant's `<h1>` and is
  intentionally NOT in the TOC list — it sits beside that h1 and would
  self-reference.

  Two conditional entries beyond `docs-prose`: `docs-schema` (dropped when
  the view declares no `:view-args-schema`) and `docs-status` (dropped when
  the variant's plan does not compile — a degenerate variant whose
  status/fidelity surface degrades to 'plan unavailable'). `docs-evidence`
  is unconditional — it always renders, reading an honest 'no evidence yet'
  line until the variant is run (so the TOC entry stays stable)."
  [{:id "docs-status"     :label "Status"      :level 2 :conditional? true}
   {:id "docs-prose"      :label "Prose"       :level 2 :conditional? true}
   {:id "docs-args"       :label "Args"        :level 2}
   {:id "docs-schema"     :label "View-arg schema" :level 2 :conditional? true}
   {:id "docs-decorators" :label "Decorators"  :level 2}
   {:id "docs-parameters" :label "Parameters"  :level 2}
   {:id "docs-evidence"   :label "Evidence"    :level 2}
   {:id "docs-tags"       :label "Tags"        :level 2}])

(defn visible-toc-entries
  "Pure data → data: prune conditional TOC entries that don't apply to this
  variant. Three conditionals:

  - `docs-prose`  — dropped when `prose-for-variant` returns empty;
  - `docs-status` — dropped when the variant's plan does not compile
                    (`variant-plan-quietly` → nil); the section then renders
                    its degraded 'plan unavailable' line without a TOC entry;
  - `docs-schema` — dropped when the view declares no `:view-args-schema`
                    (`view-arg-schema-rows` empty)."
  [variant-id]
  (let [prose?  (seq (prose-for-variant variant-id))
        plan    (variant-plan-quietly variant-id)
        status? (some? plan)
        schema? (seq (view-arg-schema-rows plan))
        drop?   {"docs-prose"  (not prose?)
                 "docs-status" (not status?)
                 "docs-schema" (not schema?)}]
    (vec
      (remove (fn [{:keys [id conditional?]}]
                (and conditional? (get drop? id false)))
              docs-toc-entries))))

#?(:cljs
   (defn- toc-pane-styles []
     {:wrap        {:position "sticky"
                    :top "16px"
                    :max-height "calc(100vh - 64px)"
                    :overflow-y "auto"
                    :width "180px"
                    :flex-shrink "0"
                    :padding "12px 12px 12px 16px"
                    :margin-left "20px"
                    :border-left (str "1px solid " (:border-subtle colors/tokens))
                    :font-family sans-stack
                    :font-size (:nano typography/type-scale)
                    :color (:text-secondary colors/tokens)}
      :label      {:text-transform "uppercase"
                   :letter-spacing "0.08em"
                   :color (:text-tertiary colors/tokens)
                   :font-size (:nano typography/type-scale)
                   :margin-bottom "6px"}
      :list       {:list-style "none"
                   :margin 0
                   :padding 0
                   :display "flex"
                   :flex-direction "column"
                   :gap "2px"}
      :item       {:padding "3px 8px"
                   :border-radius "3px"
                   :cursor "pointer"
                   :background "transparent"
                   :border "none"
                   :text-align "left"
                   :color (:text-secondary colors/tokens)
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)}
      :item-active {:background (:accent-amber-soft colors/tokens)
                    :color (:accent-amber colors/tokens)}}))

#?(:cljs
   (defn- jump-to-anchor!
     [anchor-id]
     (when (and (exists? js/document) anchor-id)
       (when-let [el (.getElementById js/document anchor-id)]
         (try
           (.scrollIntoView el #js {:behavior "smooth" :block "start"})
           (catch :default _ nil))))))

#?(:cljs
   (defn- responsive-toc?
     "TOC auto-hides below 1024px (more aggressive than Storybook's
     1200px since our RHS is already present)."
     []
     (boolean
       (when (exists? js/window)
         (try (>= (.-innerWidth js/window) 1024)
              (catch :default _ false))))))

#?(:cljs
   (defn- toc-pane
     "Render the TOC pane at the docs page right edge. Tracks the
     currently-visible section via IntersectionObserver so the active
     entry highlights as the user scrolls."
     [variant-id]
     (let [active   (r/atom nil)
           observer (atom nil)]
       (r/create-class
         {:display-name "rf-story-docs-toc"
          :component-did-mount
          (fn [_]
            (when (and (exists? js/window) (.-IntersectionObserver js/window))
              (try
                (let [entries (visible-toc-entries variant-id)
                      io (js/IntersectionObserver.
                           (fn [entries-arr _obs]
                             (doseq [e (array-seq entries-arr)]
                               (when (.-isIntersecting e)
                                 (reset! active (.-id (.-target e))))))
                           #js {:rootMargin "-30% 0px -60% 0px"})]
                  (reset! observer io)
                  (doseq [{:keys [id]} entries]
                    (when-let [el (.getElementById js/document id)]
                      (.observe io el))))
                (catch :default _ nil))))
          :component-will-unmount
          (fn [_]
            (when-let [io @observer]
              (try (.disconnect io) (catch :default _ nil))
              (reset! observer nil)))
          :reagent-render
          (fn [variant-id]
            (let [s         (toc-pane-styles)
                  entries   (visible-toc-entries variant-id)
                  active-id @active]
              (when (and (responsive-toc?) (seq entries))
                [:nav {:style     (:wrap s)
                       :data-test "story-docs-toc"
                       :aria-label "Documentation table of contents"}
                 [:div {:style (:label s)} "Contents"]
                 [:ul {:style (:list s)}
                  (for [{:keys [id label]} entries]
                    ^{:key id}
                    [:li
                     [:button
                      {:style       (merge (:item s)
                                           (when (= id active-id)
                                             (:item-active s)))
                       :data-test       "story-docs-toc-item"
                       :data-toc-target id
                       :aria-current    (if (= id active-id) "location" "false")
                       :on-click        (fn [_] (jump-to-anchor! id))}
                      label]])]])))}))))

#?(:cljs
   (defn docs-view
     "Top-level `:docs` mode pane for `variant-id`.

     Renders the docs sections inside a flex layout alongside a sticky TOC
     pane on the right edge (auto-hides below 1024px). Per spec/022 the page
     also carries a Status & fidelity summary (the SAME run-result Test mode
     reads, projected through the SAME `run-status` — Docs and Test agree), a
     view-arg schema table, and a SPARSE evidence excerpt that links into the
     Inspector/Xray. The
     status + schema sections compile the variant's plan ONCE here and thread
     it down so the page does not recompile it per section. A variant whose
     plan does not compile degrades to a 'plan unavailable' status line
     (`variant-plan-quietly` → nil) without taking the pane down."
     [variant-id]
     (when variant-id
       (let [plan (variant-plan-quietly variant-id)]
         [:div {:style     {:display "flex"
                            :flex "1"
                            :overflow "auto"
                            :background (:bg-canvas colors/tokens)}
                :data-test "story-docs-layout"}
          [:section {:style     (:wrap styles)
                     :data-test "story-docs-view"
                     :aria-label "Variant documentation"}
           [header variant-id]
           [:section {:id "docs-status"}
            (if plan
              [status-fidelity-section variant-id plan]
              [:div {:style     (:empty styles)
                     :data-test "story-docs-status-unavailable"}
               "plan unavailable — this variant does not compile to a plan; "
               "fidelity and test status can't be summarised."])]
           [:section {:id "docs-prose"} [prose-section variant-id]]
           [:section {:id "docs-args"} [args-section variant-id]]
           [:section {:id "docs-schema"} [schema-section variant-id plan]]
           [:section {:id "docs-decorators"} [decorators-section variant-id]]
           [:section {:id "docs-parameters"} [parameters-section variant-id]]
           [:section {:id "docs-evidence"} [evidence-excerpt-section variant-id]]
           [:section {:id "docs-tags"} [tags-section variant-id]]]
          [toc-pane variant-id]]))))

;; ---- per-story rollup docs page ---------------------------------------
;;
;; Storybook ships `Component.docs` — a parent-level docs page that
;; aggregates every variant's docs. The rollup view is the re-frame2
;; equivalent: click a story header in the sidebar → see ALL variants' docs
;; sections stacked in one scrollable pane, with the parent story's
;; `:doc` blurb at the top.

(defn variant-ids-for-story
  "Pure data → data: return the sorted vector of variant ids registered
  under `story-id`. The sort gives the rollup page a stable order even
  when hot-reload re-registers variants in non-source-order."
  [story-id]
  (vec (sort (registrar/variants-of story-id))))

#?(:cljs
   (defn- rollup-header
     "Top of the rollup page — story id as h1, the story's :doc blurb,
     a count of variants."
     [story-id variant-ids]
     (let [sb   (registrar/handler-meta :story story-id)
           sdoc (:doc sb)]
       [:div {:data-test "story-docs-rollup-header"}
        [:h1 {:style (:h1 styles)}
         (str story-id)]
        [:div {:style (:sub styles)}
         (str (count variant-ids)
              " variant" (when (not= 1 (count variant-ids)) "s"))]
        (when (seq sdoc)
          [:div {:style (:doc-blurb styles)
                 :data-test "story-docs-rollup-doc-blurb"}
           sdoc])])))

#?(:cljs
   (defn- rollup-variant-block
     "Render one variant's docs sections in the rollup. Mirrors `docs-view`
     minus the per-variant `<h1>` (each variant gets an h2 instead — the
     rollup's h1 is the story id at the top). Carries the Status & fidelity
     summary (the same docs↔Test-agreed projection) so a reviewer scanning
     the rollup sees each variant's verdict + fidelity at a
     glance; the full SPARSE evidence excerpt stays on the single-variant
     `docs-view` to keep the rollup terse (documentation, not a debug log)."
     [variant-id]
     (let [plan (variant-plan-quietly variant-id)]
       [:div {:style     (merge (:section styles)
                                {:padding-top "16px"
                                 :border-top  "1px solid #444"})
              :data-test "story-docs-rollup-variant"
              :data-variant-id (str variant-id)}
        [:h2 {:style (merge (:h1 styles)
                            {:font-size (:body typography/type-scale)
                             :margin-top "0"})}
         (str variant-id)]
        (when plan [status-fidelity-section variant-id plan])
        [prose-section     variant-id]
        [args-section      variant-id]
        [schema-section    variant-id plan]
        [decorators-section variant-id]
        [parameters-section variant-id]
        [tags-section       variant-id]])))

#?(:cljs
   (defn docs-rollup-view
     "Top-level rollup docs pane for `story-id`.

     Aggregates every registered variant of `story-id` into a single
     scrollable pane: the parent story header at the top, then one
     `rollup-variant-block` per variant in id-sorted order.

     Mounted by the shell's `main-pane` when `(:selected-story shell)`
     is non-nil and the user hasn't selected a variant or workspace.
     Renders nothing when `story-id` is nil — defensive against a
     stale shell state slot."
     [story-id]
     (when story-id
       (let [vids (variant-ids-for-story story-id)]
         [:section {:style     (:wrap styles)
                    :data-test "story-docs-rollup"
                    :data-story-id (str story-id)
                    :aria-label "Story documentation rollup"}
          [rollup-header story-id vids]
          (if (empty? vids)
            [:div {:style     (:empty styles)
                   :data-test "story-docs-rollup-empty"}
             "no variants registered under this story"]
            (for [vid vids]
              ^{:key vid}
              [rollup-variant-block vid]))]))))
