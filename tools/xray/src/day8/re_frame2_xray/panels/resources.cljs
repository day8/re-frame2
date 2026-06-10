(ns day8.re-frame2-xray.panels.resources
  "Resources tab — the Xray surface for declarative server-state (Spec
  016 §Xray and AI tooling). The 'where is my server state, what owns
  it, and is it stale?' lens.

  ## Sections (top → bottom, per Spec 016 §Xray and AI tooling)

      │ STATIC RESOURCE REGISTRY                                        │
      │   :article/by-slug  scope=global  stale 60s  gc 5m  routes…     │
      │ ─────────────────────────────────────────────────────────────  │
      │ LIVE INSTANCES  (per frame · scope/params SUMMARIZED)          │
      │   :article/by-slug  loaded  gen 4  owners 1  fresh             │
      │ ─────────────────────────────────────────────────────────────  │
      │ WORK LEDGER  (live attempts · host handles inaccessible)       │
      │   [resource …4]  running  cancellable  deadline                │
      │ ─────────────────────────────────────────────────────────────  │
      │ ROUTE / RESOURCE GRAPH  (blocking = SSR wait point)           │
      │ LIFECYCLE TIMELINE · INVALIDATION GRAPH · CACHE GROWTH        │
      │ SCOPE AUDIT  (every :rf.scope/global) + LINTS                  │

  ## Read-only — Xray MUST NOT become an owner by observing

  Spec 016 §Active owners and causes: opening this panel pins NOTHING.
  Every section is a pure read of `(rf/registrations …)` + the runtime-db
  cache/ledger slices + the trace buffer. The panel dispatches no
  `:rf.resource/ensure`, attaches no owner, refetches nothing, and
  extends no GC. (A future explicit 'pin this resource' debug action
  would be its own traced tool mutation, not normal inspection.)

  ## PRIVACY — summaries, never raw values

  Spec 016: params, scopes, AND data get the SAME privacy + size elision.
  Every value in every row is a `resources-helpers/summarize` shape (type
  + bounded size + redaction-aware preview), never the raw value. Scopes
  carry user/tenant/locale/impersonation ids and are summarized exactly
  like data.

  ## Decoupled from the resources artefact (bundle isolation)

  Xray does NOT `:require` `re-frame.resources.*` — resources is a
  post-v1 OPTIONAL artefact and not a hard Xray dep. The panel reads
  decoupled: the static registry via `(rf/registrations :resource)`, the
  live cache/ledger from the runtime-db partition slice the spine already
  publishes (`:rf.xray/target-frame-runtime-db`), and the trace family
  from `:rf.xray/trace-buffer`. Identical posture to the Routing tab
  (route slice) + Machine Inspector (machine snapshots).

  ## Pure hiccup

  Same contract as every Xray panel — the view is pure hiccup, no
  Reagent/UIx/Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`. Projection
  algebra lives in `resources_helpers.cljc` (JVM-portable)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.resources-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- accent -------------------------------------------------------------
;;
;; The Resources panel's accent is the cyan-leaning `:info` token —
;; distinct from Routing's GitHub-blue `:accent` so the two server-state
;; sub-domain tabs read apart at a glance.

(def ^:private mode-accent (:info tokens))

;; ---- status colour ------------------------------------------------------

(defn- status-colour
  "Resource-status → token (Spec 016 §Status semantics). `:loaded` green,
  `:loading`/`:fetching` accent (in flight), `:error` red, `:idle`
  muted."
  [status]
  (case status
    :loaded   (:green tokens)
    :loading  mode-accent
    :fetching mode-accent
    :error    (:error tokens)
    (:text-tertiary tokens)))

;; ---- shared primitives --------------------------------------------------

(defn- section-caption
  [label testid]
  [:div {:data-testid testid
         :style       {:color          (:text-tertiary tokens)
                       :font-family    sans-stack
                       :font-size      "10px"
                       :font-weight    600
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :margin-bottom  "8px"}}
   label])

(defn- section
  [{:keys [first? testid]} caption body]
  [:section {:data-testid testid
             :style       (cond-> {:padding "12px 16px"}
                            (not first?)
                            (assoc :border-top
                                   (str "1px solid " (:border-subtle tokens))))}
   caption
   body])

(defn- summary-chip
  "Render a `resources-helpers/summarize` shape as a compact, render-safe
  chip — NEVER the raw value. Redacted → muted `[redacted]`; large →
  amber `[large]`; else the bounded preview + a `(N)` size badge for
  collections."
  [{:keys [type size preview redacted? large?]} testid]
  [:span {:data-testid testid
          :style       {:font-family mono-stack
                        :font-size   "11px"
                        :color       (cond
                                       redacted? (:text-tertiary tokens)
                                       large?    (:warning tokens)
                                       :else     (:text-primary tokens))}}
   (str preview
        (when (and size (#{"map" "set" "vector" "seq"} type))
          (str " (" size ")")))])

(defn- empty-caption [text testid]
  [:div {:data-testid testid
         :style       {:color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-style  "italic"
                       :font-size   "11px"}}
   text])

;; ---- §1 STATIC RESOURCE REGISTRY ---------------------------------------

(defn- registry-row-view [row]
  (let [rid (:resource-id row)
        testid (str "rf-xray-resources-registry-row-"
                    (when rid (-> rid str (subs 1))))]
    [:div {:data-testid testid
           :data-resource-id (str rid)
           :style {:display       "flex"
                   :align-items   "baseline"
                   :gap           "10px"
                   :flex-wrap     "wrap"
                   :padding       "3px 0"
                   :font-family   mono-stack
                   :font-size     "12px"
                   :color         (:text-primary tokens)}}
     [:span {:data-testid (str testid "-id")
             :style       {:color mode-accent :font-weight 600 :min-width "10rem"}}
      (str rid)]
     [:span {:data-testid (str testid "-scope")
             :style       {:color (if (get-in row [:scope :global?])
                                    (:warning tokens)
                                    (:text-tertiary tokens))}}
      "scope=" (get-in row [:scope :label])]
     [:span {:style {:color (:text-tertiary tokens)}}
      (get-in row [:request :label])]
     (when-let [ms (:stale-after-ms row)]
       [:span {:style {:color (:text-tertiary tokens)}} "stale " ms "ms"])
     (when-let [ms (:gc-after-ms row)]
       [:span {:style {:color (:text-tertiary tokens)}} "gc " ms "ms"])
     (when (:tags? row)
       [:span {:style {:color (:text-tertiary tokens)}} "tags-fn"])
     (when (:sensitive? row)
       [:span {:data-testid (str testid "-sensitive")
               :style {:color (:warning tokens)}} "sensitive"])
     (when (seq (:declaring-routes row))
       [:span {:data-testid (str testid "-routes")
               :style {:color (:text-tertiary tokens)}}
        "routes: " (str/join " " (map str (:declaring-routes row)))])]))

(defn- registry-section [rows]
  (section
    {:first? true :testid "rf-xray-resources-registry"}
    (section-caption "Static resource registry" "rf-xray-resources-registry-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-registry-body"
                   :style {:display "flex" :flex-direction "column" :gap "2px"}}]
            (for [row rows]
              ^{:key (str (:resource-id row))} (registry-row-view row)))
      (empty-caption "No resources registered in the host app."
                     "rf-xray-resources-registry-empty"))))

;; ---- §2 LIVE INSTANCES --------------------------------------------------

(defn- instance-row-view [row]
  (let [testid (str "rf-xray-resources-instance-row-"
                    (when (:resource-id row) (-> row :resource-id str (subs 1)))
                    "-g" (:generation row))]
    [:div {:data-testid testid
           :style {:display       "flex"
                   :align-items   "baseline"
                   :gap           "10px"
                   :flex-wrap     "wrap"
                   :padding       "3px 0"
                   :font-family   mono-stack
                   :font-size     "12px"}}
     [:span {:style {:color mode-accent :font-weight 600 :min-width "10rem"}}
      (str (:resource-id row))]
     [:span {:data-testid (str testid "-status")
             :style {:color (status-colour (:status row)) :font-weight 600}}
      (str (some-> (:status row) name))]
     (when (:stale? row)
       [:span {:data-testid (str testid "-stale")
               :style {:color (:warning tokens)}} "stale"])
     [:span {:style {:color (:text-tertiary tokens)}} "gen " (:generation row)]
     [:span {:style {:color (:text-tertiary tokens)}}
      "owners " (:owner-count row)]
     (when (:gc-eligible? row)
       [:span {:data-testid (str testid "-gc")
               :style {:color (:text-tertiary tokens)}} "gc-eligible"])
     [:span {:style {:color (:text-tertiary tokens)}} "scope"]
     (summary-chip (:scope row) (str testid "-scope"))
     [:span {:style {:color (:text-tertiary tokens)}} "params"]
     (summary-chip (:params row) (str testid "-params"))
     [:span {:style {:color (:text-tertiary tokens)}} "data"]
     (summary-chip (:data row) (str testid "-data"))
     (when (:error row)
       [:span {:data-testid (str testid "-error")
               :style {:color (:error tokens)}}
        "error " (get-in row [:error :preview])])
     (when (:refresh-error row)
       [:span {:data-testid (str testid "-refresh-error")
               :style {:color (:warning tokens)}}
        "refresh-error " (get-in row [:refresh-error :preview])])]))

(defn- instances-section [rows]
  (section
    {:first? false :testid "rf-xray-resources-instances"}
    (section-caption "Live instances" "rf-xray-resources-instances-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-instances-body"
                   :style {:display "flex" :flex-direction "column" :gap "2px"}}]
            (for [row rows]
              ^{:key (str (:scoped-key row))} (instance-row-view row)))
      (empty-caption "No live resource instances in this frame."
                     "rf-xray-resources-instances-empty"))))

;; ---- §3 WORK LEDGER -----------------------------------------------------

(defn- work-row-view [row]
  (let [testid (str "rf-xray-resources-work-row-"
                    (hash (:work-id row)))]
    [:div {:data-testid testid
           :data-terminal (str (:terminal? row))
           :style {:display     "flex"
                   :align-items "baseline"
                   :gap         "10px"
                   :flex-wrap   "wrap"
                   :padding     "3px 0"
                   :font-family mono-stack
                   :font-size   "12px"
                   :opacity     (if (:terminal? row) 0.6 1)}}
     [:span {:style {:color mode-accent :font-weight 600}}
      (str (get-in row [:resource-key :resource-id]))]
     [:span {:data-testid (str testid "-status")
             :style {:color (if (:terminal? row)
                              (:text-tertiary tokens)
                              (:green tokens))
                     :font-weight 600}}
      (str (some-> (:status row) name))]
     [:span {:style {:color (:text-tertiary tokens)}} "gen " (:generation row)]
     (when (:cancellable? row)
       [:span {:style {:color (:text-tertiary tokens)}} "cancellable"])
     (when (:deadline-at row)
       [:span {:style {:color (:text-tertiary tokens)}}
        "deadline " (:deadline-at row)])
     (when (:attempt row)
       [:span {:style {:color (:text-tertiary tokens)}} "attempt " (:attempt row)])
     (when (seq (:owners row))
       [:span {:style {:color (:text-tertiary tokens)}}
        "owners " (count (:owners row))])
     (when (:outcome row)
       [:span {:style {:color (:text-tertiary tokens)}}
        "outcome " (get-in row [:outcome :preview])])]))

(defn- work-ledger-section [rows]
  (section
    {:first? false :testid "rf-xray-resources-work"}
    (section-caption "Work ledger" "rf-xray-resources-work-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-work-body"
                   :style {:display "flex" :flex-direction "column" :gap "2px"}}]
            (for [row rows]
              ^{:key (str (:work-id row))} (work-row-view row)))
      (empty-caption "No in-flight or recent resource work in this frame."
                     "rf-xray-resources-work-empty"))))

;; ---- §4 ROUTE / RESOURCE GRAPH -----------------------------------------

(defn- route-graph-row-view [node]
  (let [testid (str "rf-xray-resources-route-row-"
                    (when (:route-id node) (-> node :route-id str (subs 1))))]
    [:div {:data-testid testid
           :style {:display "flex" :flex-direction "column" :gap "2px"
                   :padding "4px 0"}}
     [:div {:style {:display "flex" :gap "10px" :align-items "baseline"
                    :font-family mono-stack :font-size "12px"}}
      [:span {:style {:color mode-accent :font-weight 600}}
       (str (:route-id node))]
      (when (:path node)
        [:span {:style {:color (:text-tertiary tokens)}} (:path node)])
      (when (:ssr-wait? node)
        [:span {:data-testid (str testid "-ssr-wait")
                :style {:color (:warning tokens)}} "SSR wait point"])]
     (into [:div {:style {:display "flex" :flex-wrap "wrap" :gap "8px"
                          :padding-left "12px" :font-family mono-stack
                          :font-size "11px"}}]
           (for [res (:resources node)]
             ^{:key (str (:resource res) (:local-id res))}
             [:span {:style {:color (if (:blocking? res)
                                     (:warning tokens)
                                     (:text-tertiary tokens))}}
              (str (:resource res))
              (if (:blocking? res) " [blocking]" " [non-blocking]")
              (when (:keep-previous? res) " keep-prev")
              (when (seq (:after res)) (str " after " (str/join "," (map str (:after res)))))]))]))

(defn- route-graph-section [nodes]
  (section
    {:first? false :testid "rf-xray-resources-route-graph"}
    (section-caption "Route / resource graph" "rf-xray-resources-route-graph-caption")
    (if (seq nodes)
      (into [:div {:data-testid "rf-xray-resources-route-graph-body"
                   :style {:display "flex" :flex-direction "column" :gap "4px"}}]
            (for [node nodes]
              ^{:key (str (:route-id node))} (route-graph-row-view node)))
      (empty-caption "No routes declare :resources."
                     "rf-xray-resources-route-graph-empty"))))

;; ---- §5 LIFECYCLE TIMELINE ---------------------------------------------

(defn- timeline-row-view [row]
  [:div {:data-testid (str "rf-xray-resources-timeline-row-" (:id row))
         :data-op     (str (:operation row))
         :style {:display "flex" :gap "8px" :align-items "baseline"
                 :padding "2px 0" :font-family mono-stack :font-size "11px"}}
   [:span {:style {:color (case (:class row)
                            :success      (:green tokens)
                            :failure      (:error tokens)
                            :suppression  (:warning tokens)
                            :invalidation (:orange tokens)
                            :gc           (:text-tertiary tokens)
                            :dedupe       (:info tokens)
                            :hydration    (:purple tokens)
                            (:text-primary tokens))
                   :font-weight 600 :min-width "9rem"}}
    (:label row)]
   [:span {:style {:color mode-accent}} (str (:resource-id row))]
   (when (:generation row)
     [:span {:style {:color (:text-tertiary tokens)}} "gen " (:generation row)])])

(defn- timeline-section [rows]
  (section
    {:first? false :testid "rf-xray-resources-timeline"}
    (section-caption "Lifecycle timeline" "rf-xray-resources-timeline-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-timeline-body"
                   :style {:display "flex" :flex-direction "column" :gap "1px"}}]
            (for [row rows]
              ^{:key (str (:id row))} (timeline-row-view row)))
      (empty-caption "No resource lifecycle events in this epoch."
                     "rf-xray-resources-timeline-empty"))))

;; ---- §6 INVALIDATION GRAPH ----------------------------------------------

(defn- invalidation-row-view [row]
  [:div {:data-testid (str "rf-xray-resources-invalidation-row-" (:id row))
         :style {:display "flex" :gap "8px" :align-items "baseline"
                 :padding "2px 0" :font-family mono-stack :font-size "11px"}}
   [:span {:style {:color (:orange tokens) :font-weight 600}} "invalidate"]
   [:span {:style {:color (:text-primary tokens)}}
    (str/join " " (map pr-str (:tags row)))]
   [:span {:data-testid (str "rf-xray-resources-invalidation-match-" (:id row))
           :style {:color (if (zero? (:match-count row))
                           (:text-tertiary tokens)
                           (:text-primary tokens))}}
    (:match-count row) " matched"]
   [:span {:style {:color (:text-tertiary tokens)}}
    (:refetched row) " refetched"]])

(defn- invalidation-section [rows]
  (section
    {:first? false :testid "rf-xray-resources-invalidation"}
    (section-caption "Invalidation / mutation graph" "rf-xray-resources-invalidation-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-invalidation-body"
                   :style {:display "flex" :flex-direction "column" :gap "1px"}}]
            (for [row rows]
              ^{:key (str (:id row))} (invalidation-row-view row)))
      (empty-caption "No invalidations in this epoch."
                     "rf-xray-resources-invalidation-empty"))))

;; ---- §7 CACHE GROWTH ----------------------------------------------------

(defn- cache-growth-section [growth]
  (section
    {:first? false :testid "rf-xray-resources-cache-growth"}
    (section-caption "Cache growth" "rf-xray-resources-cache-growth-caption")
    [:div {:style {:font-family mono-stack :font-size "11px"
                   :color (:text-primary tokens)}}
     [:div {:data-testid "rf-xray-resources-cache-growth-totals"
            :style {:color (:text-tertiary tokens) :margin-bottom "4px"}}
      (:total-entries growth) " entries · "
      (:total-gc-eligible growth) " gc-eligible · "
      (:live-work growth) " live work"]
     (into [:div {:style {:display "flex" :flex-direction "column" :gap "1px"}}]
           (for [r (:by-resource growth)]
             ^{:key (str (:resource-id r))}
             [:div {:style {:display "flex" :gap "8px"}}
              [:span {:style {:color mode-accent :min-width "10rem"}}
               (str (:resource-id r))]
              [:span {:style {:color (:text-tertiary tokens)}}
               (:entry-count r) " entries"]
              [:span {:style {:color (:text-tertiary tokens)}}
               (:owned-count r) " owned"]
              (when (pos? (:gc-eligible r))
                [:span {:style {:color (:warning tokens)}}
                 (:gc-eligible r) " gc-eligible"])]))]))

;; ---- §8 SCOPE AUDIT + LINTS --------------------------------------------

(defn- audit-section [{:keys [global-audit suspicious mismatches orphans]}]
  (section
    {:first? false :testid "rf-xray-resources-audit"}
    (section-caption "Scope audit + lints" "rf-xray-resources-audit-caption")
    [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                   :font-family mono-stack :font-size "11px"}}
     ;; the standing global-scope security-review list
     [:div {:data-testid "rf-xray-resources-audit-global"}
      [:span {:style {:color (:text-tertiary tokens)}} ":rf.scope/global resources: "]
      (if (seq global-audit)
        [:span {:style {:color (:text-primary tokens)}}
         (str/join " " (map (comp str :resource-id) global-audit))]
        [:span {:style {:color (:text-tertiary tokens) :font-style "italic"}} "none"])]
     ;; suspicious explicit-global warnings (defense-in-depth)
     (when (seq suspicious)
       (into [:div {:data-testid "rf-xray-resources-audit-suspicious"
                    :style {:display "flex" :flex-direction "column" :gap "1px"}}]
             (for [w suspicious]
               ^{:key (str (:resource-id w))}
               [:div {:style {:color (:warning tokens)}}
                "⚠ " (str (:resource-id w)) " — " (:hint w)])))
     ;; scope-mismatch lint
     (when (seq mismatches)
       (into [:div {:data-testid "rf-xray-resources-audit-mismatch"
                    :style {:display "flex" :flex-direction "column" :gap "1px"}}]
             (for [m mismatches]
               ^{:key (str (:resource-id m) (get-in m [:sub-scope :preview]))}
               [:div {:style {:color (:error tokens)}}
                "scope mismatch on " (str (:resource-id m))
                " — sub scope " (get-in m [:sub-scope :preview])
                " ≠ entry scope " (get-in m [:entry-scope :preview])])))
     ;; orphaned-owner lint
     (when (seq orphans)
       (into [:div {:data-testid "rf-xray-resources-audit-orphan"
                    :style {:display "flex" :flex-direction "column" :gap "1px"}}]
             (for [o orphans]
               ^{:key (str (:owner o))}
               [:div {:style {:color (:warning tokens)}}
                "orphaned owner " (pr-str (:owner o))
                " pins " (str (:resource-id o))])))]))

;; ---- empty (no resources artefact / no resources registered) -----------

(defn- silent-state []
  [:div {:data-testid "rf-xray-resources-silent"
         :style       {:padding        "16px"
                       :display        "flex"
                       :flex-direction "column"
                       :gap            "8px"
                       :color          (:text-tertiary tokens)
                       :font-family    sans-stack
                       :font-size      "11px"}}
   [:span {:style {:font-style "italic"}}
    "No resources registered in the host app."]
   [:span {:style {:color (:text-tertiary tokens)}}
    "Register resources via "
    [:code {:style {:color mode-accent :font-family mono-stack}}
     "re-frame.resources/reg-resource"]
    " — the registry + live-instance tables render once the host installs them."]])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Resources tab's root view (Spec 016 §Xray and AI tooling).
  Subscribes to `:rf.xray/resources-tab-data` and renders the eight
  sections top → bottom: static registry, live instances, work ledger,
  route/resource graph, lifecycle timeline, invalidation graph, cache
  growth, scope audit + lints. When the host has no resources registered
  AND no live instances, renders the silent-by-default caption."
  []
  (let [{:keys [silent? registry instances work route-graph timeline
                invalidations cache-growth audit]}
        @(rf/subscribe [:rf.xray/resources-tab-data])]
    [:section {:data-testid "rf-xray-resources"
               :style {:height         "100%"
                       :display        "flex"
                       :flex-direction "column"
                       :background     (:bg-2 tokens)
                       :color          (:text-primary tokens)
                       :font-family    sans-stack
                       :font-size      "14px"
                       :overflow       "auto"}}
     (if silent?
       (silent-state)
       [:<>
        (registry-section registry)
        (instances-section instances)
        (work-ledger-section work)
        (route-graph-section route-graph)
        (timeline-section timeline)
        (invalidation-section invalidations)
        (cache-growth-section cache-growth)
        (audit-section audit)])]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Resources panel's Xray-side registrations.

  Registers (all under the `:rf.xray/*` isolation prefix):

    - `:rf.xray/registered-resources` — `(rf/registrations :resource)`
      (process-global, frame-agnostic; the static registry). Test
      override via `:registered-resources-override`.
    - `:rf.xray/resource-entries` — the live cache entries map from the
      observed frame's RUNTIME-DB partition at
      `[:rf.runtime/resources :entries]` (EP-0001 — resource cache is
      framework-owned runtime-db state, never app-db). Sourced from
      `:rf.xray/target-frame-runtime-db`. Test override.
    - `:rf.xray/resource-work-ledger` — the live work-ledger map from
      `[:rf.runtime/work-ledger]`. Test override.
    - `:rf.xray/resource-sub-reads` — observed live subscription reads
      (`[{:resource-id :params :scope} …]`) backing the scope-mismatch
      lint. Empty by default; the host/runtime populates it (test
      override slot).
    - `:rf.xray/resources-tab-data` — the view-facing composite over the
      registry + entries + ledger + route registry + trace buffer; the
      single read the Panel subscribes.

  Read-only: NO event registered here dispatches a `:rf.resource/*`
  event — inspection never becomes an owner (Spec 016)."
  []

  ;; Test-only override slots ---------------------------------------------

  (rf/reg-event-db :rf.xray/set-registered-resources-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov) (dissoc db :registered-resources-override)
          (assoc db :registered-resources-override ov))))
  (rf/reg-sub :rf.xray/registered-resources-override
    (fn [db _] (get db :registered-resources-override)))

  (rf/reg-event-db :rf.xray/set-resource-entries-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov) (dissoc db :resource-entries-override)
          (assoc db :resource-entries-override ov))))
  (rf/reg-sub :rf.xray/resource-entries-override
    (fn [db _] (get db :resource-entries-override)))

  (rf/reg-event-db :rf.xray/set-resource-work-ledger-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov) (dissoc db :resource-work-ledger-override)
          (assoc db :resource-work-ledger-override ov))))
  (rf/reg-sub :rf.xray/resource-work-ledger-override
    (fn [db _] (get db :resource-work-ledger-override)))

  (rf/reg-event-db :rf.xray/set-resource-sub-reads-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov) (dissoc db :resource-sub-reads-override)
          (assoc db :resource-sub-reads-override ov))))
  (rf/reg-sub :rf.xray/resource-sub-reads-override
    (fn [db _] (get db :resource-sub-reads-override)))

  ;; Production data subs --------------------------------------------------

  (rf/reg-sub :rf.xray/registered-resources
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/registered-resources-override]
    (fn [[_buffer override] _]
      (or override (rf/registrations :resource))))

  (rf/reg-sub :rf.xray/resource-entries
    :<- [:rf.xray/target-frame-runtime-db]
    :<- [:rf.xray/resource-entries-override]
    (fn [[target-runtime-db override] _]
      (cond
        (some? override)         override
        (map? target-runtime-db) (get-in target-runtime-db h/entries-rel-path)
        :else                    nil)))

  (rf/reg-sub :rf.xray/resource-work-ledger
    :<- [:rf.xray/target-frame-runtime-db]
    :<- [:rf.xray/resource-work-ledger-override]
    (fn [[target-runtime-db override] _]
      (cond
        (some? override)         override
        (map? target-runtime-db) (get target-runtime-db h/work-ledger-key)
        :else                    nil)))

  (rf/reg-sub :rf.xray/resource-sub-reads
    :<- [:rf.xray/resource-sub-reads-override]
    (fn [override _] (or override [])))

  ;; View-facing composite -------------------------------------------------

  (rf/reg-sub :rf.xray/resources-tab-data
    :<- [:rf.xray/registered-resources]
    :<- [:rf.xray/resource-entries]
    :<- [:rf.xray/resource-work-ledger]
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/resource-sub-reads]
    (fn [[registrations entries ledger trace-buffer sub-reads] _]
      (let [now-ms        (.now js/Date)
            routes-map    (rf/registrations :route)
            registry-rows (h/project-registry registrations routes-map)
            instance-rows (h/project-instances entries now-ms)
            work-rows     (h/project-work-ledger ledger)]
        {:silent?       (and (empty? registry-rows) (empty? instance-rows))
         :registry      registry-rows
         :instances     instance-rows
         :work          work-rows
         :route-graph   (h/project-route-graph routes-map)
         :timeline      (h/lifecycle-timeline trace-buffer)
         :invalidations (h/invalidation-graph trace-buffer)
         :cache-growth  (h/cache-growth instance-rows work-rows)
         :audit         {:global-audit (h/global-scope-audit registry-rows)
                         :suspicious   (h/suspicious-global-warnings registry-rows)
                         :mismatches   (h/scope-mismatch-lint instance-rows sub-reads)
                         :orphans      (h/orphaned-owner-lint instance-rows trace-buffer)}})))

  ;; Register the Dynamic Resources tab with the internal L4 tab registry.
  ;; Per Mike's cohesive-sub-domain ruling (server-state earns its own L4
  ;; tab rather than piling into App-db). Order 7 — after Routes (order 6),
  ;; keeping the two cross-feature runtime-db tabs adjacent. Display label
  ;; is the plural domain noun "Resources" (all-plural-domain-noun
  ;; convention).
  (panel-registry/reg-l4-tab!
    {:id    :resources
     :label "Resources"
     :mnem  "s"
     :modes #{:dynamic}
     :order 7
     :panel Panel})

  nil)
