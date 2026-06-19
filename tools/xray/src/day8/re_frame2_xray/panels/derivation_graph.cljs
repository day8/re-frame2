(ns day8.re-frame2-xray.panels.derivation-graph
  "Derivation-Graph tab — Xray's unified view of the EP-0014
  derivation/process algebra graph (prop-3, rf2-9ett2d; the NAMED FIRST
  CONSUMER of the structured graph accessor).

  ## The one graph

  Every declared fact and process in the host app — subscriptions, flows,
  resources, route facts, machine processes + selectors — is a node in ONE
  derivation/process graph over the frame fold ([Derivations.md] §Graph
  inspection; [EP-0014]). The five algebra-view tooling siblings each
  project one family; `re-frame.derivation.graph` (the composer, slice-7)
  stitches them into a single `{:mode :nodes :edges}` view. This panel
  renders that one graph, so a maintainer (or agent) answers \"where does
  this value come from, when is it evaluated, where does it live, who owns
  it?\" across families in one place — even though the underlying runtime
  mechanisms are subscription cache, flow registry, route slice, resource
  cache, and machine snapshots.

  ## Static vs live

  Two modes, toggled in the panel header (mirroring [Derivations.md]
  §Static and live graphs):

    - STATIC — `derivation-graph`: the registration-derived graph. Every
      registered fact/process + its registration-known `:input` /
      `:param` / `:selector` edges; a parametric sub shows the
      `:parametric` marker and contributes NO edge (the don't-execute
      rule). Process-global, frame-agnostic.
    - LIVE — `live-derivation-graph`: the graph realized in the OBSERVED
      frame at a point in time. Concrete subscription query vectors with
      realized `[:sub q]` input edges, active resource cache entries,
      live machine instances + spawned actors, the materialized route
      slice with its nav-token owner.

  ## The two superkinds are the contract

  The panel groups + classifies every node by its CLOSED superkind
  (`:derivation` | `:process`) read off `:kind` alone (EP-0014: a tool MUST
  classify every node knowing only the two superkinds). The refined kinds
  (`:resource-process`, `:route-fact`, `:machine-process`,
  `:machine-selector`) are the COLOUR axis — they tint the row's family
  accent but never gate classification. A node carrying an unknown FUTURE
  refinement still renders + classifies off its superkind.

  ## ON-BOX raw, OFF-BOX redacted

  On-box rendering shows raw value summaries (Security.md permits on-box;
  the developer is entitled to their own app's values — the TAIL-2 ruling:
  raw-on-box is the in-process truth). `derivation-graph-helpers/summarize`
  bounds value previews for display ergonomics only, NOT for privacy. The
  OFF-BOX egress boundary — where a tool ships the graph to a remote agent
  or a serialized capture — is `derivation-graph-helpers/redact-graph-for-
  egress`, which projects each node's value-bearing fields through the
  frame's `rf/elide-wire-value` policy (per-frame, fail-closed), preserving
  edge + node structure (rf2-yjarv6). This panel renders on-box, so it
  reads the RAW graph.

  ## The contributor seam — all five families

  The composer reaches each optional family through a `{family
  contributor}` map. On CLJS the consuming tool supplies it from the
  tooling siblings it statically `:require`s ([Derivations.md] §The
  graph-assembly composer — \"the optional siblings it has\"). As EP-0014's
  NAMED FIRST CONSUMER, Xray hard-deps ALL FIVE algebra-view families so
  the single graph it renders is complete (rf2-1fc459): core
  (→ `re-frame.subs.tooling`), routing (→ `re-frame.routing.tooling`),
  flows (→ `re-frame.flows.tooling`), resources
  (→ `re-frame.resources.tooling`), and machines
  (→ `re-frame.machines.tooling`). So `xray-contributors` carries all five;
  a family with no registrations in the host app simply contributes no
  nodes — the no-machines / no-resources story now holds PER-APP (an app
  that registers no machines) rather than per-tool (Xray missing the
  artefact). The tooling siblings' bodies are dev-gated
  (`interop/debug-enabled?`) + bundle-isolated; Xray itself is dev-only
  (`:devtools/preloads`), so none of these reach a production bundle. The
  Resources panel (024) + Machine Inspector (003) still read those
  families' runtime-db slices decoupled — those are SEPARATE surfaces; the
  Derivation-Graph tab needs the algebra-view projection, not the raw
  slice, so it `:require`s the tooling siblings.

  ## Read-only

  Same contract as every Xray panel — observing the graph pins nothing,
  dispatches nothing, mutates no host state. Pure hiccup; projection
  algebra lives in `derivation_graph_helpers.cljc` (JVM-portable). Frame
  isolation comes from the enclosing `[rf/frame-provider-existing {:frame :rf/xray}]`
  in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [re-frame.derivation.graph :as dgraph]
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.machines.tooling :as machines-tooling]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.routing.tooling :as routing-tooling]
            [re-frame.subs.tooling :as subs-tooling]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.derivation-graph-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- the Xray contributor map -------------------------------------------
;;
;; ALL FIVE algebra-view families Xray statically `:require`s (see ns
;; docstring). The composer composes every family into the single
;; DerivationGraph this panel renders (rf2-1fc459 — the named first consumer
;; shows every EP-0014 family). A family with no registrations in the host
;; app contributes no nodes (the no-machines / no-resources story still
;; holds per-app, not per-tool).

(def xray-contributors
  "The `{family contributor}` map for `re-frame.derivation.graph`, built
  from the five tooling siblings Xray statically `:require`s. `:subs` lives
  in core (always present); `:flows`, `:routes`, `:resources`, and
  `:machines` are Xray hard deps (rf2-1fc459). The `:machines` contributor
  carries the `machine-selector-targets` extractor so the graph draws
  precise machine→selector edges (rf2-4qmiij)."
  {:subs      {:static-fn  subs-tooling/sub-algebra-view
               :live-fn    subs-tooling/sub-cache-algebra-view
               :live-shape :map}
   :flows     {:static-fn  flows-tooling/flow-algebra-view
               :live-fn    flows-tooling/flow-algebra-view
               :live-shape :map}
   :resources {:static-fn  resources-tooling/resource-algebra-view
               :live-fn    resources-tooling/resource-cache-algebra-view
               :live-shape :map}
   :routes    {:static-fn  routing-tooling/route-algebra-view
               :live-fn    routing-tooling/route-slice-algebra-view
               :live-shape :node}
   :machines  {:static-fn         machines-tooling/machine-algebra-view
               :live-fn           machines-tooling/machine-instance-algebra-view
               :live-shape        :map
               :selector-targets  machines-tooling/machine-selector-targets}})

(def ^:private mode-accent (:magenta tokens))     ; violet — the algebra lens

;; ---- family accents ------------------------------------------------------
;;
;; Colour, not contract — the family tint reads families apart at a glance.

(defn- family-accent [family]
  (case family
    :subs      (:accent tokens)         ; GitHub-blue
    :flows     (:green tokens)
    :resources (:info tokens)
    :routes    (:syntax-number tokens)  ; orange
    :machines  (:magenta-pink tokens)
    (:text-tertiary tokens)))

(defn- role-colour [role]
  (case role
    :input    (:accent tokens)
    :param    (:info tokens)
    :selector (:magenta-pink tokens)
    (:text-tertiary tokens)))

;; ---- shared primitives ---------------------------------------------------

(defn- section-caption [label testid]
  [:div {:data-testid testid
         :style {:color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "10px" :font-weight 600
                 :text-transform "uppercase" :letter-spacing "0.5px"
                 :margin-bottom "8px"}}
   label])

(defn- section [{:keys [first? testid]} caption body]
  [:section {:data-testid testid
             :style (cond-> {:padding "12px 16px"}
                      (not first?)
                      (assoc :border-top (str "1px solid " (:border-subtle tokens))))}
   caption body])

(defn- summary-chip
  "Render a `helpers/summarize` shape as a compact, render-safe chip. NEVER
  the raw value object — always the bounded preview; a `:rf/redacted`
  sentinel (a value that arrived already redacted) renders muted."
  [{:keys [type size preview redacted?]} testid]
  [:span {:data-testid testid
          :style {:font-family mono-stack :font-size "11px"
                  :color (if redacted? (:text-tertiary tokens) (:text-primary tokens))}}
   (str preview
        (when (and size (#{:map :set :vector :seq} type))
          (str " (" size ")")))])

;; ---- mode toggle ---------------------------------------------------------

;; `dispatch` (rf2-1w07r) is the reg-view-injected frame-bound dispatcher
;; threaded down from the `Panel` body, so the deferred `:on-click` lands on
;; the surrounding `:rf/xray` instance frame (not a bare global `rf/dispatch`
;; that leaks to `:rf/default` once render unwinds and the ambient frame is
;; gone). Read-only panel: the only dispatch is the maintainer's mode toggle.
(defn- mode-toggle [dispatch mode]
  [:div {:data-testid "rf-xray-derivation-graph-mode-toggle"
         :style {:display "flex" :gap "8px" :align-items "center"}}
   (for [m [:static :live]]
     ^{:key (name m)}
     [:button {:data-testid (str "rf-xray-derivation-graph-mode-" (name m))
               :data-active (str (= mode m))
               :on-click #(dispatch [:rf.xray/set-derivation-graph-mode m])
               :style {:font-family mono-stack :font-size "11px"
                       :padding "2px 10px" :cursor "pointer"
                       :border (str "1px solid "
                                    (if (= mode m) mode-accent (:border-default tokens)))
                       :border-radius "3px"
                       :background (if (= mode m) (:bg-3 tokens) "transparent")
                       :color (if (= mode m) (:text-primary tokens) (:text-tertiary tokens))}}
      (name m)])])

;; ---- header summary ------------------------------------------------------

(defn- summary-header [dispatch {:keys [mode node-count edge-count by-superkind by-role]} mode-now]
  [:section {:data-testid "rf-xray-derivation-graph-header"
             :style {:padding "12px 16px"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :display "flex" :flex-direction "column" :gap "8px"}}
   [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
    [:span {:style {:color mode-accent :font-weight 600 :font-family sans-stack
                    :font-size "12px" :text-transform "uppercase" :letter-spacing "0.5px"}}
     "Derivation / process graph"]
    (mode-toggle dispatch mode-now)]
   [:div {:data-testid "rf-xray-derivation-graph-counts"
          :style {:display "flex" :gap "12px" :flex-wrap "wrap"
                  :font-family mono-stack :font-size "11px"
                  :color (:text-tertiary tokens)}}
    [:span {:data-testid "rf-xray-derivation-graph-mode-badge"} (str "mode " (name (or mode mode-now)))]
    [:span (str node-count " nodes")]
    [:span (str edge-count " edges")]
    [:span {:data-testid "rf-xray-derivation-graph-superkind-derivation"
            :style {:color (:accent tokens)}}
     (str (get by-superkind :derivation 0) " derivation")]
    [:span {:data-testid "rf-xray-derivation-graph-superkind-process"
            :style {:color (:magenta-pink tokens)}}
     (str (get by-superkind :process 0) " process")]
    (for [role h/edge-roles]
      ^{:key (name role)}
      (when-let [n (get by-role role)]
        [:span {:style {:color (role-colour role)}} (str n " " (name role))]))]])

;; ---- node row ------------------------------------------------------------

(defn- node-row [[node-id node] family]
  (let [skind  (h/superkind node)
        testid (str "rf-xray-derivation-graph-node-" (hash node-id))]
    [:div {:data-testid testid
           :data-superkind (name skind)
           :data-refinement (some-> (:refinement node) name)
           :data-family (name family)
           :style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                   :gap "8px" :padding "3px 0"
                   :font-family mono-stack :font-size "12px"}}
     ;; superkind dot — the CONTRACT classification (derivation vs process)
     [:span {:data-testid (str testid "-superkind")
             :style {:color (if (= :process skind) (:magenta-pink tokens) (:accent tokens))
                     :font-weight 700}}
      (if (= :process skind) "◆" "○")]
     [:span {:data-testid (str testid "-id")
             :style {:color (family-accent family) :font-weight 600 :min-width "12rem"}}
      (h/node-label node-id)]
     ;; refinement — COLOUR, not contract
     (when-let [r (:refinement node)]
       [:span {:data-testid (str testid "-refinement")
               :style {:color (:text-tertiary tokens)}}
        (name r)])
     ;; storage / evaluation / lifecycle classifications
     (when-let [s (:storage node)]
       [:span {:style {:color (:text-tertiary tokens)}} "store " (pr-str s)])
     (when-let [e (:evaluation node)]
       [:span {:style {:color (:text-tertiary tokens)}} "eval " (pr-str e)])
     (when-let [l (:lifecycle node)]
       [:span {:style {:color (:text-tertiary tokens)}}
        "owner " (pr-str (if (map? l) (:kind l) l))])
     ;; external authority split (resources) — :storage local, :authority remote
     (when-let [a (:authority node)]
       [:span {:data-testid (str testid "-authority")
               :style {:color (:warning tokens)}}
        "authority " (pr-str (:kind a))])
     ;; parametric marker — the don't-execute rule made visible
     (when (= :parametric (:inputs node))
       [:span {:data-testid (str testid "-parametric")
               :style {:color (:advisory tokens)}}
        "parametric"])
     ;; ON-BOX value summaries (raw-permitting; bounded previews)
     (for [[k summary] (:summaries node)]
       ^{:key (name k)}
       [:span {:style {:display "inline-flex" :gap "4px" :align-items "baseline"}}
        [:span {:style {:color (:text-tertiary tokens)}} (name k)]
        (summary-chip summary (str testid "-" (name k)))])]))

(defn- family-section [family entries first?]
  (section
   {:first? first? :testid (str "rf-xray-derivation-graph-family-" (name family))}
   (section-caption (str (h/family-label family) " (" (count entries) ")")
                    (str "rf-xray-derivation-graph-family-" (name family) "-caption"))
   (into [:div {:data-testid (str "rf-xray-derivation-graph-family-" (name family) "-body")
                :style {:display "flex" :flex-direction "column" :gap "1px"}}]
         (for [entry entries]
           ^{:key (pr-str (first entry))} (node-row entry family)))))

;; ---- edges section -------------------------------------------------------

(defn- edge-row [{:keys [from to role]}]
  [:div {:data-testid (str "rf-xray-derivation-graph-edge-" (hash [from to role]))
         :data-role (name role)
         :style {:display "flex" :gap "6px" :align-items "baseline"
                 :padding "1px 0" :font-family mono-stack :font-size "11px"}}
   [:span {:style {:color (role-colour role) :font-weight 600 :min-width "5rem"}}
    (name role)]
   [:span {:style {:color (:text-primary tokens)}} (h/node-label from)]
   [:span {:style {:color (:text-tertiary tokens)}} "→"]
   [:span {:style {:color (:text-primary tokens)}} (h/node-label to)]])

(defn- edges-section [edges]
  (section
   {:first? false :testid "rf-xray-derivation-graph-edges"}
   (section-caption (str "Edges (" (count edges) ")")
                    "rf-xray-derivation-graph-edges-caption")
   (if (seq edges)
     (into [:div {:data-testid "rf-xray-derivation-graph-edges-body"
                  :style {:display "flex" :flex-direction "column" :gap "1px"}}]
           (for [e edges]
             ^{:key (pr-str [(:from e) (:to e) (:role e)])} (edge-row e)))
     [:div {:data-testid "rf-xray-derivation-graph-edges-empty"
            :style {:color (:text-tertiary tokens) :font-style "italic"
                    :font-size "11px" :font-family sans-stack}}
      "No node→node edges (a graph of layer-1 readers + parametric subs has no static edges)."])))

;; ---- silent state --------------------------------------------------------

(defn- silent-state []
  [:div {:data-testid "rf-xray-derivation-graph-silent"
         :style {:padding "16px" :display "flex" :flex-direction "column" :gap "8px"
                 :color (:text-tertiary tokens) :font-family sans-stack :font-size "11px"}}
   [:span {:style {:font-style "italic"}}
    "No derivation/process nodes in the host app."]
   [:span
    "Register subscriptions, flows, resources, routes, or machines — the "
    "unified graph renders across all five algebra-view families once the "
    "host installs any of them."]])

;; ---- public view ---------------------------------------------------------

(rf/reg-view Panel
  "The Derivation-Graph tab's root view (EP-0014 prop-3). Subscribes to
  `:rf.xray/derivation-graph-tab-data` and renders the header (mode toggle +
  counts), the per-family node sections (grouped + classified by superkind),
  and the edges section. Reads the ON-BOX raw graph (Security.md permits
  on-box); off-box egress redaction is `helpers/redact-graph-for-egress`,
  exercised by tooling that ships the graph off the developer's box."
  []
  (let [{:keys [mode summary by-family edges silent?]}
        @(rf/subscribe [:rf.xray/derivation-graph-tab-data])]
    [:section {:data-testid "rf-xray-derivation-graph"
               :style {:height "100%" :display "flex" :flex-direction "column"
                       :background (:bg-2 tokens) :color (:text-primary tokens)
                       :font-family sans-stack :font-size "14px" :overflow "auto"}}
     (summary-header dispatch summary mode)
     (if silent?
       (silent-state)
       (into [:<>]
             (concat
              (map-indexed
               (fn [idx family]
                 (when-let [entries (seq (get by-family family))]
                   ^{:key (name family)}
                   (family-section family entries (zero? idx))))
               h/families)
              [^{:key "edges"} (edges-section edges)])))]))

;; ---- production value source ---------------------------------------------
;;
;; The raw assembled graph the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated (rf2-e8330v).

(defn- derivation-graph-value
  "The assembled `DerivationGraph` for `mode` over `target-frame`:
  `live-derivation-graph` in `:live` mode, else the static
  `derivation-graph` over `xray-contributors`."
  [mode target-frame]
  (if (= :live mode)
    (dgraph/live-derivation-graph target-frame xray-contributors)
    (dgraph/derivation-graph xray-contributors)))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Derivation-Graph panel's Xray-side
  registrations (all under the `:rf.xray/*` isolation prefix).

  Registers:

    - `:rf.xray/derivation-graph-mode` — `:static` | `:live` toggle slot
      (default `:static`). Settable via `:rf.xray/set-derivation-graph-mode`.
    - `:rf.xray/derivation-graph-override` — test override for the assembled
      graph (so JVM/CLJS tests drive a fixture graph without a full runtime).
    - `:rf.xray/derivation-graph` — the assembled `DerivationGraph`. In
      `:static` mode, `derivation-graph` over `xray-contributors`
      (process-global). In `:live` mode, `live-derivation-graph` for the
      OBSERVED target frame (`:rf.xray/target-frame`). Re-runs when the trace
      buffer ticks (the registration / cache surface may have changed) or the
      observed frame switches.
    - `:rf.xray/derivation-graph-tab-data` — the view-facing composite:
      summarizes node value fields for ON-BOX display, groups by family,
      classifies by superkind, and carries the summary header data. The
      single read the Panel subscribes.

  Read-only: assembling the graph reads the registrar + the observed frame's
  runtime-db decoupled; it dispatches nothing and pins nothing."
  []
  ;; ---- mode toggle ------------------------------------------------------
  (rf/reg-event :rf.xray/set-derivation-graph-mode
    (fn [{:keys [db]} [_ mode]]
      {:db (assoc db :derivation-graph-mode (if (#{:static :live} mode) mode :static))}))

  (rf/reg-sub :rf.xray/derivation-graph-mode
    (fn [db _] (get db :derivation-graph-mode :static)))

  ;; The test-only override seam (`:rf.xray/set-derivation-graph-
  ;; override-for-test` + the `*-override` sub) is NOT installed here —
  ;; production registration carries no `-for-test` ids. Tests opt into
  ;; it via `install-test-overrides!` (rf2-e8330v / xxo3zz F3).

  ;; ---- assembled graph --------------------------------------------------
  (rf/reg-sub :rf.xray/derivation-graph
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/derivation-graph-mode]
    :<- [:rf.xray/target-frame]
    (fn [[_buffer mode target-frame] _]
      (derivation-graph-value mode target-frame)))

  ;; ---- view-facing composite -------------------------------------------
  (rf/reg-sub :rf.xray/derivation-graph-tab-data
    :<- [:rf.xray/derivation-graph]
    (fn [graph _]
      (let [summarized (h/summarize-graph graph)]
        {:mode      (:mode graph)
         :silent?   (h/empty-graph? graph)
         :summary   (h/graph-summary graph)
         :by-family (h/group-by-family summarized)
         :edges     (:edges graph)})))

  ;; Register the Dynamic Derivation-Graph tab with the internal L4 tab
  ;; registry. Order 8 — after Resources (order 7), keeping the cross-feature
  ;; runtime-graph tabs adjacent. Label is the domain noun "Graph".
  (panel-registry/reg-l4-tab!
    {:id    :derivation-graph
     :label "Graph"
     :mnem  "g"
     :modes #{:dynamic}
     :order 8
     :panel Panel})

  nil)

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) ---------------------

(defn install-test-overrides!
  "Install the Derivation-Graph panel's test-only override seam — the
  `:rf.xray/set-derivation-graph-override-for-test` event + the
  `*-override` sub, then RE-register the production `:rf.xray/derivation-
  graph` sub to layer the override read on top. Tests opt in by calling
  this AFTER `register-xray-handlers!` (typically via `test-support/
  install-test-overrides!`). **Test-only — never call from production.**"
  []
  (rf/reg-event :rf.xray/set-derivation-graph-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :derivation-graph-override)
          (assoc db :derivation-graph-override ov))}))
  (rf/reg-sub :rf.xray/derivation-graph-override
    (fn [db _] (get db :derivation-graph-override)))

  (rf/reg-sub :rf.xray/derivation-graph
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/derivation-graph-mode]
    :<- [:rf.xray/target-frame]
    :<- [:rf.xray/derivation-graph-override]
    (fn [[_buffer mode target-frame override] _]
      (if (some? override)
        override
        (derivation-graph-value mode target-frame))))
  nil)
