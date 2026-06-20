(ns re-frame.story.ui.explain-panel
  "The Explain panel — a read-only provenance + lowering surface over
  the `story/explain` data (spec/020 §4 / spec/017 §Explain API).

  ## What it is — and is NOT

  This panel answers ONE question for the focused variant: *where did
  this plan come from, and how was it lowered?* It renders the
  `:explain` map the pure plan compiler attaches
  (`re-frame.story.plan/explain`): the source + parent chains, composed
  fragments/checks, field-level merge decisions, strict-conflict
  winners/losers, arg substitutions + effective-args, and the network /
  sub-override / runner lowering.

  It is a **Story-owned** surface. Per spec/020 §4 it is net-new Story
  UI over explain data and does NOT depend on Xray — it must NOT pull
  any `day8.re-frame2-xray.*` symbol onto the production/story bundle.
  Provenance + lowering is Story's job; app-db diffing, trace, and
  time-travel are Xray's (spec/020 §1.2). The Explain panel is the
  static-plan companion to Xray's runtime diagnostics — they sit in the
  same RHS inspector rail but never share code.

  ## Render what's there — never fabricate

  The panel renders the slots spec/020 §4 / spec/017 §Explain API list.
  Some slots are only populated when the variant exercises that feature
  (`:network` is nil unless the variant authors routes; `:sub-overrides`
  is nil unless it pins subs; `:compose` / `:strict-conflicts` are empty
  unless it composes fragments). Where a slot is absent in the CURRENT
  explain output the panel renders it as a graceful 'not available' /
  empty state — it never invents data the compiler didn't emit. Slots
  the spec marks as future (`:plan-hash` inputs, evidence projections)
  are intentionally NOT rendered: they are not shipped explain data.

  ## Pure / CLJS split

  Mirrors `docs.cljc`: the section-projection logic is pure `.cljc`
  (`explain-sections` + the per-section shapers) so the JVM corpus can
  pin the projection without a host; the React rendering + the
  raw-EDN/copy-to-clipboard affordances are `#?(:cljs ...)`.

  ## Reachability

  - **Inspector**: the shell's RHS inspector rail mounts
    `explain-panel` as a labelled section (gated on a focused variant),
    alongside the Xray embed + Controls.
  - **Command palette**: a synthetic `:command` entry (`Explain
    variant`) opens the panel + scrolls it into view.

  ## Elision

  CLJS rendering reaches `explain-panel` only via the
  `config/enabled?`-gated shell mount, so production builds never invoke
  it; closure DCEs the lot. The pure `.cljc` helpers carry no DOM /
  Reagent dep."
  (:require [clojure.string :as str]
            [re-frame.story.plan :as plan]
            ;; The theme requires are CLJS-only — `typography`/`colors` are
            ;; referenced solely from the `#?(:cljs (def ^:private styles …))`
            ;; block. Keeping them inside the `:cljs` reader conditional avoids
            ;; an unused-namespace warning in the `.cljc` clj view.
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.story.config :as config]
                       [re-frame.story.review-dialog :as review-dialog]
                       [re-frame.story.ui.state :as state]
                       [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
                       [re-frame.story.theme.colors :as colors]])))

;; ---------------------------------------------------------------------------
;; The panel-visibility slot + the scroll anchor id. Shared with the
;; shell (RHS mount) + the command palette (open-and-scroll command).
;; ---------------------------------------------------------------------------

(def panel-key
  "The `:panel-visibility` slot key the shell + command palette toggle
  to show/hide the Explain section."
  :explain)

(def anchor-id
  "DOM id on the Explain RHS section so the command palette can scroll
  it into view after opening it."
  "story-explain-panel")

;; ---------------------------------------------------------------------------
;; Pure: compute the explain map for a variant (catching compile errors)
;; ---------------------------------------------------------------------------

(defn explain-for
  "Return `{:explain <map>}` for `variant-id`, or `{:error <msg>}` when
  the plan compiler throws (a missing arg, an unknown variant, an
  unresolved strict conflict, a schema violation). Pure-ish — reads the
  Story side-table through the compiler's default `:lookup`. Never
  throws; a compile failure is surfaced to the user as an error state
  rather than blanking the panel.

  `nil` variant-id yields `{:error ...}` so the caller renders the
  no-variant empty state via a separate branch — this fn is only called
  with a concrete id."
  [variant-id]
  (try
    {:explain (plan/explain variant-id)}
    (catch #?(:clj Throwable :cljs :default) e
      {:error (or #?(:clj (.getMessage ^Throwable e)
                     :cljs (.-message e))
                  (str e))})))

;; ---------------------------------------------------------------------------
;; Pure: per-section projection
;;
;; Each section descriptor is data → data so the JVM corpus can assert
;; the full ordered inventory + the present?/empty distinction without
;; a host. The CLJS renderer dispatches on `:render-as` to paint each.
;;
;; `:render-as` vocabulary:
;;   :chain      — an ordered vector of ids rendered as a breadcrumb
;;   :kv         — a {k v} map rendered as a key/value table
;;   :pairs      — a vector of {:key :value} maps rendered as a 2-col table
;;   :steps      — an ordered vector of step/check/assertion forms
;;   :compose    — the composed fragment/check vector
;;   :conflicts  — the strict-conflict trail
;;   :network    — the network lowering ({:routes :lowered-to})
;;   :sub-ovr    — the sub-override lowering ({:overrides :validation})
;;   :runner     — the required-runner capability set
;;   :badges     — a set rendered as inline chips (platforms / tags / fidelity)
;; ---------------------------------------------------------------------------

(defn- present?
  "True when an explain slot carries renderable content. Empty
  collections + nil read as absent → the section renders its
  'not available' empty state. `false` / `0` are not explain values so
  the simple seq/some? test is sufficient."
  [v]
  (cond
    (nil? v)        false
    (coll? v)       (seq v)
    :else           true))

(defn explain-sections
  "Project an `:explain` map into the ordered vector of section
  descriptors the panel renders (spec/020 §4 / spec/017 §Explain API).

  Each descriptor:

      {:id        stable id (DOM + test hook)
       :label     human section label
       :render-as render dispatch keyword (see ns comment)
       :present?  whether the slot carries content
       :value     the raw slot value (nil/empty when absent)
       :note      optional 'when this is empty it means …' hint}

  The ordering is provenance → composition → args → lowering →
  execution → metadata, which is the order a reviewer reads a plan:
  *where it came from*, *what merged*, *what the args resolved to*,
  *how features lowered*, *what runs*, *what it's tagged*.

  Pure data → data; JVM-testable. Renders EVERY spec-listed slot —
  absent ones carry `:present? false` so the renderer paints an honest
  empty state rather than dropping the section (a dropped section would
  read as 'this variant has no source chain' which is never true)."
  [explain]
  (let [e (or explain {})
        section (fn [id label render-as value & [note]]
                  {:id        id
                   :label     label
                   :render-as render-as
                   :present?  (present? value)
                   :value     value
                   :note      note})]
    [;; ---- provenance ----
     (section "source-chain" "Source chain" :chain (:source-chain e)
              "the extends root → variant chain the plan composed from")
     (section "parent-chain" "Parent chain" :chain (:parent-chain e)
              "no parents → this variant declares no :extends")
     (section "compose" "Composed fragments / checks" :compose (:compose e)
              "no :compose directive on this variant")
     ;; ---- composition decisions ----
     (section "merge" "Merge decisions" :kv (:merge e)
              "the per-slot merge strategy the compiler applied")
     (section "strict-conflicts" "Strict conflicts" :conflicts (:strict-conflicts e)
              "no composed-fragment conflicts to resolve")
     ;; ---- args ----
     (section "args" "Args" :kv (:args e)
              "no args resolved on this variant")
     (section "substitutions" "Arg substitutions" :pairs (:substitutions e)
              "no [:arg …] placeholders were substituted")
     (section "effective-args" "Effective args" :kv (:effective-args e)
              "the post-substitution args fed to the view")
     (section "view-args-validation" "View-args validation" :kv (:view-args-validation e)
              "no view-args schema on file for this variant")
     ;; ---- lowering ----
     (section "network" "Network lowering" :network (:network e)
              "this variant authors no :network routes")
     (section "sub-overrides" "Sub-override lowering" :sub-ovr (:sub-overrides e)
              "this variant pins no view-state subscription overrides")
     (section "fidelity" "Fidelity" :badges (:fidelity e)
              "real-setup fidelity — no overrides lower the evidence rung")
     ;; ---- execution ----
     (section "setup-order" "Setup order" :steps (:setup-order e)
              "no :setup / :events steps")
     (section "script-order" "Script order" :steps (:script-order e)
              "no :script / :play steps")
     (section "checks" "Checks" :steps (:checks e)
              "no inherited or composed checks")
     (section "assertions" "Assertions" :steps (:assertions e)
              "no terminal assertions")
     (section "required-runner" "Runner requirements" :runner (:required-runner e)
              "app-db-only — resolves to the :headless runner")
     ;; ---- metadata ----
     (section "platforms" "Platforms" :badges (:platforms e)
              "defaults to #{:client}")
     (section "tags" "Tags" :badges (:tags e)
              "no tags on this variant or its parents")
     (section "source" "Source coords" :kv (:source e)
              "no source coordinates captured at registration")]))

;; ---------------------------------------------------------------------------
;; Pure: small string shapers shared by the renderer + the test corpus
;; ---------------------------------------------------------------------------

(defn chain-label
  "Render a source/parent chain vector as a `a → b → c` breadcrumb
  string. Empty chain → the empty-state marker is the caller's job;
  this returns \"\" so the test corpus can assert the join shape."
  [chain]
  (str/join "  →  " (map pr-str (or chain []))))

(defn conflict-summary
  "One-line human summary of a single strict-conflict entry — the
  winning source beat the losing sources by `:rule`. Pure so the JVM
  corpus can pin the phrasing."
  [{:keys [field key winner winning-source losing-sources rule]}]
  (str (pr-str field) " / " (pr-str key)
       " — " (pr-str (or winning-source :?)) " wins"
       (when (seq losing-sources)
         (str " over " (str/join ", " (map pr-str losing-sources))))
       " (" (name (or rule :?)) ")"
       (when (some? winner) (str " = " (pr-str winner)))))

(defn raw-edn
  "Pretty-ish EDN string of the explain map for the raw-EDN view +
  copy-to-clipboard. `pr-str` keeps it parseable + paste-ready for
  agent use; the renderer wraps it in a scrollable `<pre>`."
  [explain]
  (pr-str explain))

;; ---------------------------------------------------------------------------
;; CLJS-only rendering
;; ---------------------------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap          {:font-family   sans-stack
                      :font-size     (:body-tight typography/type-scale)
                      :color         (:text-primary colors/tokens)
                      :padding-bottom "12px"}
      :variant-id    {:font-family   mono-stack
                      :font-size     (:caption typography/type-scale)
                      :color         (:accent-amber colors/tokens)
                      :margin        "0 0 2px 0"
                      :word-break    "break-all"}
      :blurb         {:color         (:text-tertiary colors/tokens)
                      :font-size     (:caption typography/type-scale)
                      :margin-bottom "10px"
                      :line-height   (:line-height-tight typography/type-scale)}
      :section       {:margin-top    "12px"}
      :section-h     {:display       "flex"
                      :align-items   "baseline"
                      :justify-content "space-between"
                      :gap           "8px"
                      :color         (:text-secondary colors/tokens)
                      :text-transform "uppercase"
                      :font-size     (:micro typography/type-scale)
                      :letter-spacing (:label typography/letter-spacing)
                      :margin-bottom "4px"
                      :padding-bottom "3px"
                      :border-bottom (str "1px solid " (:border-subtle colors/tokens))}
      :absent-tag    {:color         (:text-tertiary colors/tokens)
                      :font-style    "italic"
                      :text-transform "none"
                      :letter-spacing "normal"
                      :font-size     (:micro typography/type-scale)}
      :empty         {:color         (:text-tertiary colors/tokens)
                      :font-style    "italic"
                      :font-size     (:caption typography/type-scale)
                      :padding       "2px 0 4px 0"}
      :chain         {:font-family   mono-stack
                      :font-size     (:caption typography/type-scale)
                      :color         (:info colors/tokens)
                      :word-break    "break-all"
                      :line-height   (:line-height-mono typography/type-scale)}
      :table         {:width         "100%"
                      :border-collapse "collapse"
                      :font-family   mono-stack
                      :font-size     (:caption typography/type-scale)}
      :td            {:padding       "3px 6px 3px 0"
                      :border-bottom (str "1px solid " (:border-subtle colors/tokens))
                      :color         (:text-primary colors/tokens)
                      :vertical-align "top"
                      :white-space   "pre-wrap"
                      :word-break    "break-word"}
      :td-key        {:color         (:info colors/tokens)
                      :white-space   "nowrap"
                      :padding-right "10px"}
      :td-val        {:color         (:tag-experimental-fg colors/tokens)}
      :list          {:margin        0
                      :padding-left  "16px"
                      :font-family   mono-stack
                      :font-size     (:caption typography/type-scale)
                      :color         (:text-primary colors/tokens)
                      :line-height   (:line-height-mono typography/type-scale)}
      :badge-row     {:display       "flex"
                      :flex-wrap     "wrap"
                      :gap           "4px"
                      :margin-top    "2px"}
      :badge         {:font-family   mono-stack
                      :font-size     (:nano typography/type-scale)
                      :background    (:bg-3 colors/tokens)
                      :color         (:text-secondary colors/tokens)
                      :border        (str "1px solid " (:border-subtle colors/tokens))
                      :border-radius "8px"
                      :padding       "1px 7px"}
      :conflict      {:font-family   mono-stack
                      :font-size     (:caption typography/type-scale)
                      :color         (:warning colors/tokens)
                      :padding       "2px 0"
                      :line-height   (:line-height-mono typography/type-scale)}
      :toolbar       {:display       "flex"
                      :gap           "6px"
                      :margin-bottom "8px"}
      :btn           {:font-family   sans-stack
                      :font-size     (:caption typography/type-scale)
                      :background    (:bg-3 colors/tokens)
                      :color         (:text-secondary colors/tokens)
                      :border        (str "1px solid " (:border-default colors/tokens))
                      :border-radius "6px"
                      :padding       "3px 9px"
                      :cursor        "pointer"
                      :user-select   "none"}
      :btn-on        {:background    (:accent-amber colors/tokens)
                      :color         (:text-on-accent colors/tokens)
                      :border        (str "1px solid " (:accent-amber-deep colors/tokens))}
      :pre           {:font-family   mono-stack
                      :font-size     (:caption typography/type-scale)
                      :background    (:bg-input colors/tokens)
                      :color         (:text-primary colors/tokens)
                      :border        (str "1px solid " (:border-subtle colors/tokens))
                      :border-radius "4px"
                      :padding       "8px"
                      :margin        0
                      :max-height    "320px"
                      :overflow      "auto"
                      :white-space   "pre-wrap"
                      :word-break    "break-word"}
      :copied        {:color         (:success colors/tokens)
                      :font-size     (:caption typography/type-scale)
                      :align-self    "center"}
      :error         {:color         (:danger colors/tokens)
                      :font-family   mono-stack
                      :font-size     (:caption typography/type-scale)
                      :background    (:danger-bg colors/tokens)
                      :border        (str "1px solid " (:danger colors/tokens))
                      :border-radius "4px"
                      :padding       "8px"
                      :white-space   "pre-wrap"
                      :word-break    "break-word"}
      :no-variant    {:color         (:text-tertiary colors/tokens)
                      :font-style    "italic"
                      :font-size     (:caption typography/type-scale)
                      :padding       "4px 0"}}))

#?(:cljs
   (defn- pretty
     "Render a scalar/collection explain value. `pr-str` for everything
     so keywords / maps / vectors stay visibly typed (a string shows
     quoted)."
     [v]
     (if (nil? v) "nil" (pr-str v))))

#?(:cljs
   (defn- kv-table
     "Render a {k v} map as a 2-column key/value table."
     [m]
     [:table {:style (:table styles)}
      [:tbody
       (for [[k v] (if (map? m) (sort-by (comp str key) m) m)]
         ^{:key (str k)}
         [:tr
          [:td {:style (merge (:td styles) (:td-key styles))} (pretty k)]
          [:td {:style (merge (:td styles) (:td-val styles))} (pretty v)]])]]))

#?(:cljs
   (defn- pairs-table
     "Render a vector of `{:key :value}` substitution maps."
     [pairs]
     [:table {:style (:table styles)}
      [:tbody
       (for [[i {:keys [key value]}] (map-indexed vector pairs)]
         ^{:key i}
         [:tr
          [:td {:style (merge (:td styles) (:td-key styles))} (pretty key)]
          [:td {:style (merge (:td styles) (:td-val styles))} (pretty value)]])]]))

#?(:cljs
   (defn- steps-list
     "Render an ordered vector of forms (setup / script / checks /
     assertions) as a numbered mono list."
     [forms]
     [:ol {:style (:list styles)}
      (for [[i form] (map-indexed vector forms)]
        ^{:key i}
        [:li (pr-str form)])]))

#?(:cljs
   (defn- badge-row
     "Render a set/coll as inline mono chips (platforms / tags /
     fidelity / runner capability tokens)."
     [items]
     [:div {:style (:badge-row styles)}
      (for [item (sort-by str items)]
        ^{:key (str item)}
        [:span {:style (:badge styles)} (pr-str item)])]))

#?(:cljs
   (defn- conflict-list
     [conflicts]
     [:div
      (for [[i c] (map-indexed vector conflicts)]
        ^{:key i}
        [:div {:style (:conflict styles)} (conflict-summary c)])]))

#?(:cljs
   (defn- network-view
     "Render the network lowering — per-route reply stubs + the managed-
     stub fx the routes lower to."
     [{:keys [routes lowered-to]}]
     [:div
      (when (seq routes)
        [kv-table routes])
      (when (seq lowered-to)
        [:div {:style (:section styles)}
         [:div {:style (:empty styles)} "lowered to:"]
         [kv-table lowered-to]])]))

#?(:cljs
   (defn- sub-ovr-view
     "Render the sub-override lowering — the resolved override map + the
     output-schema validation outcome."
     [{:keys [overrides validation]}]
     [:div
      (when (seq overrides)
        [kv-table overrides])
      (when validation
        [:div {:style (:section styles)}
         [:div {:style (:empty styles)} "validation:"]
         [kv-table validation]])]))

#?(:cljs
   (defn- render-section-body
     "Dispatch on `:render-as` to paint one section's body."
     [{:keys [render-as value]}]
     (case render-as
       :chain     [:div {:style (:chain styles)} (chain-label value)]
       :kv        [kv-table value]
       :pairs     [pairs-table value]
       :steps     [steps-list value]
       :compose   [:ol {:style (:list styles)}
                   (for [[i {:keys [kind id]}] (map-indexed vector value)]
                     ^{:key i}
                     [:li (str (name kind) " " (pr-str id))])]
       :conflicts [conflict-list value]
       :network   [network-view value]
       :sub-ovr   [sub-ovr-view value]
       :runner    [badge-row value]
       :badges    [badge-row value]
       ;; defensive: an unknown render-as falls back to raw EDN so the
       ;; section never blanks on a future slot.
       [:div {:style (:chain styles)} (pretty value)])))

#?(:cljs
   (defn- section-view
     "Render one explain section — header (label + absent tag) and the
     body, OR the honest empty state when the slot carries no content."
     [{:keys [id label present? note] :as section}]
     [:div {:style     (:section styles)
            :data-test "story-explain-section"
            :data-explain-section id
            :data-present (str (boolean present?))}
      [:div {:style (:section-h styles)}
       [:span label]
       (when-not present?
         [:span {:style (:absent-tag styles)} "not available"])]
      (if present?
        (render-section-body section)
        [:div {:style (:empty styles)} (or note "—")])]))

#?(:cljs
   (defn- raw-edn-pane
     "Render the raw-EDN escape hatch — a scrollable `<pre>` of the
     explain map for human reading + agent paste."
     [explain]
     [:pre {:style (:pre styles)
            :data-test "story-explain-raw-edn"}
      (raw-edn explain)]))

#?(:cljs
   (defn explain-panel
     "The Explain RHS inspector body (spec/020 §4). Reads the focused
     variant from shell state, compiles its explain map via the pure
     plan compiler (NOT Xray — provenance + lowering is Story-owned),
     and renders every spec-listed slot with graceful 'not available'
     empty states. Carries a raw-EDN toggle + copy-to-clipboard.

     States:
     - no variant focused → a quiet 'select a variant' line;
     - compile error → the structured plan-construction error;
     - otherwise → the section inventory (or the raw EDN when toggled)."
     []
     (let [show-raw? (r/atom false)
           copied?   (r/atom false)]
       (fn []
         (let [shell      @state/shell-state-atom
               variant-id (:selected-variant shell)]
           (cond
             (not variant-id)
             [:div {:style     (:no-variant styles)
                    :data-test "story-explain-no-variant"}
              "Select a variant to explain its plan."]

             :else
             (let [{:keys [explain error]} (explain-for variant-id)]
               [:div {:style     (:wrap styles)
                      :data-test "story-explain-panel"
                      :data-variant-id (str variant-id)}
                [:div {:style (:variant-id styles)} (str variant-id)]
                [:div {:style (:blurb styles)}
                 "Provenance + lowering for this plan. Read-only; "
                 "static-plan companion to the Xray runtime panels."]
                (if error
                  [:div {:style     (:error styles)
                         :data-test "story-explain-error"}
                   error]
                  [:div
                   ;; ---- toolbar: raw-EDN toggle + copy ----
                   [:div {:style (:toolbar styles)}
                    [:button
                     {:style     (merge (:btn styles)
                                        (when @show-raw? (:btn-on styles)))
                      :data-test "story-explain-toggle-raw"
                      :aria-pressed (str (boolean @show-raw?))
                      :title     "Toggle raw EDN view of the explain map"
                      :on-click  (fn [_] (swap! show-raw? not))}
                     (if @show-raw? "Sections" "Raw EDN")]
                    [:button
                     {:style     (:btn styles)
                      :data-test "story-explain-copy"
                      :title     "Copy the explain map as EDN to the clipboard"
                      :on-click  (fn [_]
                                   (review-dialog/copy-to-clipboard! (raw-edn explain))
                                   (reset! copied? true)
                                   (js/setTimeout #(reset! copied? false) 1400))}
                     "Copy EDN"]
                    (when @copied?
                      [:span {:style     (:copied styles)
                              :data-test "story-explain-copied"}
                       "copied"])]
                   (if @show-raw?
                     [raw-edn-pane explain]
                     [:div {:data-test "story-explain-sections"}
                      (for [section (explain-sections explain)]
                        ^{:key (:id section)}
                        [section-view section])])])])))))))

;; ---------------------------------------------------------------------------
;; CLJS-only: command-palette helper — open the panel + scroll it in.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn open!
     "Open the Explain RHS section (flip its `:panel-visibility` slot on)
     and scroll it into view. Wired from the command palette's
     `Explain variant` command. No-op when Story is disabled."
     []
     (when config/enabled?
       (state/swap-state! assoc-in [:panel-visibility panel-key] true)
       (js/setTimeout
         (fn []
           (when (exists? js/document)
             (when-let [el (.getElementById js/document anchor-id)]
               (try
                 (.scrollIntoView el #js {:behavior "smooth" :block "start"})
                 (catch :default _ nil)))))
         0))))
