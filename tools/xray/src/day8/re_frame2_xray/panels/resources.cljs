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
            [day8.re-frame2-xray.host-registry :as host-registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.resources-helpers :as h]
            [day8.re-frame2-xray.panels.reply-envelope :as reply]
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

;; ---- §1b NAMED SCOPE RESOLVERS (EP-0016 D3) -----------------------------
;;
;; The static `(rf/registrations :resource-scope)` registry: each named
;; resolver's id + its DECLARED inputs (the `[:db path]` sources that decide a
;; resource identity, paths summarized) + the whole-db-sugar cost flag. Per
;; Spec 016 §Named resource-scope resolvers. The LIVE per-resolution input
;; values + resolved scope surface in the resolution timeline (§5b) off the
;; egress-projected `:rf.resource/scope-resolved` trace — NEVER here (a static
;; declaration carries no PII).

(defn- scope-resolver-row-view [row]
  (let [sid (:scope-id row)
        testid (str "rf-xray-resources-scope-resolver-row-"
                    (when sid (-> sid str (subs 1))))]
    [:div {:data-testid testid
           :data-scope-id (str sid)
           :style {:display "flex" :align-items "baseline" :gap "10px"
                   :flex-wrap "wrap" :padding "3px 0"
                   :font-family mono-stack :font-size "12px"}}
     [:span {:data-testid (str testid "-id")
             :style {:color mode-accent :font-weight 600 :min-width "10rem"}}
      (str sid)]
     (if (seq (:inputs row))
       (into [:span {:data-testid (str testid "-inputs")
                     :style {:color (:text-tertiary tokens)}}
              "inputs: "]
             (interpose " "
               (for [in (:inputs row)]
                 [:span {:style {:color (:text-primary tokens)}}
                  (str (:name in) "←" (:source in) " " (get-in in [:path :preview]))])))
       [:span {:style {:color (:text-tertiary tokens)}} "no declared inputs"])
     (when (:whole-db? row)
       [:span {:data-testid (str testid "-whole-db")
               :style {:color (:warning tokens)}}
        "whole-db cost"])]))

(defn- scope-resolvers-section [rows]
  (section
    {:first? false :testid "rf-xray-resources-scope-resolvers"}
    (section-caption "Named scope resolvers" "rf-xray-resources-scope-resolvers-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-scope-resolvers-body"
                   :style {:display "flex" :flex-direction "column" :gap "2px"}}]
            (for [row rows]
              ^{:key (str (:scope-id row))} (scope-resolver-row-view row)))
      (empty-caption "No named resource-scope resolvers registered."
                     "rf-xray-resources-scope-resolvers-empty"))))

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
        "refresh-error " (get-in row [:refresh-error :preview])])
     ;; EP-0021 — the infinite-feed surface (R1/R2/R3): page count, the
     ;; runtime-owned cursor / terminal flag, and the THIRD error channel
     ;; (`:page-error` — a load-more failure that kept the feed). Rendered only
     ;; for an `:infinite?` entry. Per Xray spec 024 §Panel sections (§2 Live
     ;; instances — the infinite-feed surface).
     (when (:infinite? row)
       [:<>
        [:span {:data-testid (str testid "-pages")
                :style {:color (:text-tertiary tokens)}}
         "pages " (:page-count row)]
        (if (:terminal? row)
          [:span {:data-testid (str testid "-terminal")
                  :style {:color (:text-tertiary tokens)}} "terminal"]
          [:<>
           [:span {:style {:color (:text-tertiary tokens)}} "cursor"]
           (summary-chip (:cursor row) (str testid "-cursor"))])
        (when (:page-error row)
          [:span {:data-testid (str testid "-page-error")
                  :style {:color (:error tokens)}}
           "page-error " (get-in row [:page-error :preview])])])]))

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
      (str (get-in row [:resource/key :resource-id]))]
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
     ;; EP-0021 — an infinite-feed work record's page index. A LIVE positive
     ;; index is a load-more APPEND in flight (`:fetching-next?`); index 0 is a
     ;; page-0 first-load / whole-feed refetch. Per Spec 016 §Causal event —
     ;; load-more (R2). Absent (nil) for a non-infinite work record.
     (when (integer? (:page-index row))
       [:span {:data-testid (str testid "-page-index")
               :style {:color (:text-tertiary tokens)}}
        (if (and (not (:terminal? row)) (pos? (:page-index row)))
          (str "load-more p" (:page-index row))
          (str "page " (:page-index row)))])
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

;; ---- §3b WHAT IS STILL RUNNING? (EP-0011 live-work) ---------------------
;;
;; The cross-family "what is the app waiting on, and why" view (Trace-Consumer
;; §"What is still running?"): the live (non-terminal) work-ledger rows joined
;; to their latest reply-envelope trace phase + emitting op, UNIFORM across
;; HTTP / resources / mutations / routing / machines / timers. The projection
;; (`reply/live-work`) is computed in the composite data sub; this view
;; renders it. SILENT-BY-DEFAULT: renders nothing when there is no live work
;; (the quiet baseline — a settled app shows no dashboard).

(defn- live-work-row-view [row]
  (let [testid (str "rf-xray-resources-live-work-row-" (hash (:work-id row)))]
    [:div {:data-testid testid
           :data-work-kind (str (some-> (:work-kind row) name))
           :style {:display     "flex"
                   :align-items "baseline"
                   :gap         "10px"
                   :flex-wrap   "wrap"
                   :padding     "3px 0"
                   :font-family mono-stack
                   :font-size   "12px"}}
     [:span {:data-testid (str testid "-kind")
             :style {:color mode-accent :font-weight 600 :min-width "6rem"}}
      (str (some-> (:work-kind row) name))]
     [:span {:data-testid (str testid "-status")
             :style {:color (:green tokens) :font-weight 600}}
      (str (some-> (:status row) name))]
     (when (:latest-phase row)
       [:span {:data-testid (str testid "-phase")
               :style {:color (:text-primary tokens)}}
        "phase " (name (:latest-phase row))])
     (when (:latest-op row)
       [:span {:data-testid (str testid "-op")
               :style {:color (:text-tertiary tokens)}}
        (str (:latest-op row))])
     (when (:attempt row)
       [:span {:style {:color (:text-tertiary tokens)}} "attempt " (:attempt row)])
     (when (seq (:owners row))
       [:span {:style {:color (:text-tertiary tokens)}}
        "owners " (count (:owners row))])
     [:span {:data-testid (str testid "-id")
             :style {:color (:text-tertiary tokens)}}
      (str (:work-id row))]]))

(defn- live-work-section [{:keys [live-work stale-tally]}]
  ;; quiet when nothing is running AND nothing has been suppressed.
  (when (or (seq live-work) (seq stale-tally))
    (section
      {:first? false :testid "rf-xray-resources-live-work"}
      (section-caption "What is still running?" "rf-xray-resources-live-work-caption")
      [:<>
       (if (seq live-work)
         (into [:div {:data-testid "rf-xray-resources-live-work-body"
                      :style {:display "flex" :flex-direction "column" :gap "2px"}}]
               (for [row live-work]
                 ^{:key (str (:work-id row))} (live-work-row-view row)))
         (empty-caption "No live managed-async work in this frame."
                        "rf-xray-resources-live-work-empty"))
       ;; the active-effects tally headline — live count per family.
       (when (seq stale-tally)
         (into [:div {:data-testid "rf-xray-resources-stale-tally"
                      :style {:display "flex" :gap "10px" :flex-wrap "wrap"
                              :margin-top "8px" :font-family mono-stack
                              :font-size "11px" :color (:text-tertiary tokens)}}
                [:span {:style {:font-weight 600}} "suppressed: "]]
               (for [[kind n] (sort-by (comp str key) stale-tally)]
                 ^{:key (str kind)}
                 [:span {:data-testid (str "rf-xray-resources-stale-tally-"
                                           (some-> kind name))
                         :style {:color (:warning tokens)}}
                  (str (some-> kind name) " " n)])))])))

;; ---- §3c STALE RACES (EP-0011 stale-suppression / supersession) ---------
;;
;; The cross-family stale-races view (Trace-Consumer §stale-races / Resources
;; panel §uniform reply-envelope reads): every work/reply attempt arc grouped
;; by `:work/id`, showing the arcs that hit the stale-suppression correctness
;; boundary (carried ≠ current — one attempt superseded another). The
;; projection (`reply/races-by-work-id`) is computed in the composite data
;; sub. SILENT-BY-DEFAULT: only the SUPPRESSED arcs render; renders nothing
;; when no arc was suppressed.

(defn- stale-race-row-view [arc]
  (let [testid (str "rf-xray-resources-stale-race-row-" (hash (:work-id arc)))]
    [:div {:data-testid testid
           :data-work-kind (str (some-> (:work-kind arc) name))
           :style {:display     "flex"
                   :align-items "baseline"
                   :gap         "10px"
                   :flex-wrap   "wrap"
                   :padding     "3px 0"
                   :font-family mono-stack
                   :font-size   "12px"}}
     [:span {:data-testid (str testid "-kind")
             :style {:color mode-accent :font-weight 600 :min-width "6rem"}}
      (str (some-> (:work-kind arc) name))]
     [:span {:data-testid (str testid "-terminal")
             :style {:color (:warning tokens) :font-weight 600}}
      (str (some-> (:terminal-status arc) name))]
     [:span {:style {:color (:text-tertiary tokens)}}
      "phases " (str/join " " (sort (map name (:phases arc))))]
     [:span {:data-testid (str testid "-id")
             :style {:color (:text-tertiary tokens)}}
      (str (:work-id arc))]]))

(defn- stale-races-section [stale-races]
  (let [suppressed (->> (vals stale-races)
                        (filter :suppressed?)
                        (sort-by (comp str :work-id)))]
    ;; quiet when no arc hit the stale-suppression boundary.
    (when (seq suppressed)
      (section
        {:first? false :testid "rf-xray-resources-stale-races"}
        (section-caption "Stale races" "rf-xray-resources-stale-races-caption")
        (into [:div {:data-testid "rf-xray-resources-stale-races-body"
                     :style {:display "flex" :flex-direction "column" :gap "2px"}}]
              (for [arc suppressed]
                ^{:key (str (:work-id arc))} (stale-race-row-view arc)))))))

;; ---- §4 ROUTE / RESOURCE GRAPH -----------------------------------------

(defn- freshness-colour [freshness]
  ;; rf2-m5u3gt — the per-resource live freshness chip colour.
  (case freshness
    :fresh   (:success tokens)
    :stale   (:warning tokens)
    :loading mode-accent
    :error   (:error tokens)
    (:text-tertiary tokens)))   ; :idle / :none / unknown

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
      ;; rf2-m5u3gt — the LIVE active-route badge.
      (when (:current? node)
        [:span {:data-testid (str testid "-current")
                :style {:color (:success tokens) :font-weight 600}}
         "● active"])
      (when (:ssr-wait? node)
        [:span {:data-testid (str testid "-ssr-wait")
                :style {:color (:warning tokens)}} "SSR wait point"])
      ;; rf2-m5u3gt — the live unsettled blocking wait points on the active route.
      (when (seq (:blocking-live node))
        [:span {:data-testid (str testid "-blocking-live")
                :style {:color (:warning tokens)}}
         (str "waiting: " (str/join "," (map str (:blocking-live node))))])]
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
              (when (seq (:after res)) (str " after " (str/join "," (map str (:after res)))))
              ;; rf2-m5u3gt — the per-resource live freshness chip.
              (when-let [live (:live res)]
                (when (not= :none (:freshness live))
                  [:span {:style {:color (freshness-colour (:freshness live))
                                  :margin-left "4px"}}
                   (str "· " (name (:freshness live))
                        (when (pos? (:active-work live))
                          (str " (" (:active-work live) " work)")))]))]))]))

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

;; ---- §6b SCOPE RESOLUTION TIMELINE (EP-0016 D3) -------------------------
;;
;; The `:rf.resource/scope-resolved` rows: which named resolver ran, the
;; declared input names, the resolved scope (PRIVACY-summarized), and the
;; fail-closed nil evidence (`resolved-nil?` — the scope-requiring site got nil,
;; never an implicit global). Per Spec 016 §Named resource-scope resolvers.

(defn- scope-resolution-row-view [row]
  [:div {:data-testid (str "rf-xray-resources-scope-resolution-row-" (:id row))
         :data-resolved-nil (str (:resolved-nil? row))
         :style {:display "flex" :gap "8px" :align-items "baseline"
                 :padding "2px 0" :font-family mono-stack :font-size "11px"}}
   [:span {:style {:color (if (:resolved-nil? row)
                            (:warning tokens)
                            (:text-primary tokens))
                   :font-weight 600 :min-width "9rem"}}
    (if (:resolved-nil? row) "resolved nil" "scope resolved")]
   [:span {:style {:color mode-accent}} (str (:scope-id row))]
   (when (seq (:inputs row))
     [:span {:style {:color (:text-tertiary tokens)}}
      "inputs " (str/join "," (map name (:inputs row)))])
   (when (:whole-db? row)
     [:span {:style {:color (:warning tokens)}} "whole-db"])
   (if (:resolved-nil? row)
     [:span {:data-testid (str "rf-xray-resources-scope-resolution-failclosed-" (:id row))
             :style {:color (:warning tokens)}}
      "fail-closed (no global fallback)"]
     [:span {:style {:color (:text-tertiary tokens)}}
      "→ " (get-in row [:scope :preview])])])

(defn- scope-resolution-section [rows]
  (section
    {:first? false :testid "rf-xray-resources-scope-resolution"}
    (section-caption "Scope resolution timeline" "rf-xray-resources-scope-resolution-caption")
    (if (seq rows)
      (into [:div {:data-testid "rf-xray-resources-scope-resolution-body"
                   :style {:display "flex" :flex-direction "column" :gap "1px"}}]
            (for [row rows]
              ^{:key (str (:id row))} (scope-resolution-row-view row)))
      (empty-caption "No named-scope resolutions in this epoch."
                     "rf-xray-resources-scope-resolution-empty"))))

;; ---- §6c MUTATION CONTINUATIONS + DESCRIPTOR EVIDENCE (EP-0016 D1/D2) ----
;;
;; The descriptor-level invalidation evidence (per-descriptor resolved scope +
;; fail-closed `:unresolved` + Rider-1 `:populate-exempt`) off the mutation
;; settlement traces, and the call-site `:reply-to` continuation dispatch
;; (`:rf.mutation/replied`). The doctrine made visible: "reply-to is for
;; workflow; populate/patch/invalidate are for cache" (Spec 016 §Mutation
;; completion continuations / §Trace evidence for invalidation).

(defn- descriptor-chip [d]
  [:span {:style {:color (:text-tertiary tokens)}}
   "["
   (if (:cross-scope? d)
     [:span {:style {:color (:warning tokens)}} "cross-scope"]
     [:span {:style {:color (:text-primary tokens)}} (get-in d [:scope :preview])])
   " " (str/join " " (map pr-str (:tags d)))
   (when (:refetch-populated? d)
     [:span {:style {:color (:info tokens)}} " refetch-populated"])
   ;; the per-descriptor :exempt-keys (rf2-fi6tda.7) — the populated keys THIS
   ;; pass spared. Visible per-chip so a mixed plan (one descriptor opting in,
   ;; another sparing the same key) is debuggable without the collapsed union.
   (when (seq (:exempt-keys d))
     [:span {:style {:color (:info tokens)}}
      " " (count (:exempt-keys d)) " exempt"])
   "]"])

(defn- mutation-invalidation-row-view [row]
  [:div {:data-testid (str "rf-xray-resources-mutation-invalidation-row-" (:id row))
         :data-mutation (str (:mutation row))
         :style {:display "flex" :gap "8px" :align-items "baseline" :flex-wrap "wrap"
                 :padding "2px 0" :font-family mono-stack :font-size "11px"}}
   [:span {:style {:color (:orange tokens) :font-weight 600 :min-width "9rem"}}
    "mutation invalidate"]
   [:span {:style {:color mode-accent}} (str (:mutation row))]
   [:span {:style {:color (:text-tertiary tokens)}}
    (str (:descriptor-count row) " descriptors")]
   (into [:span {:style {:display "flex" :gap "6px" :flex-wrap "wrap"}}]
         (for [d (:dispatched row)]
           ^{:key (str (get-in d [:scope :preview]) (:tags d))} (descriptor-chip d)))
   (when (seq (:unresolved row))
     [:span {:data-testid (str "rf-xray-resources-mutation-invalidation-unresolved-" (:id row))
             :style {:color (:warning tokens)}}
      "unresolved (fail-closed): " (str/join " " (map str (:unresolved row)))])
   (when (seq (:populate-exempt row))
     [:span {:style {:color (:info tokens)}}
      (count (:populate-exempt row)) " populate-exempt"])])

(defn- continuation-status-colour
  "Reply-status → token for a `:reply-to` continuation row (the accepted reply
  status, NOT a resource status): `:ok` green, `:error` red, `:cancelled`
  muted-warning, else accent."
  [status]
  (case status
    :ok        (:green tokens)
    :error     (:error tokens)
    :cancelled (:warning tokens)
    mode-accent))

(defn- continuation-row-view [row]
  [:div {:data-testid (str "rf-xray-resources-continuation-row-" (:id row))
         :data-mutation (str (:mutation row))
         :data-status (str (some-> (:status row) name))
         :style {:display "flex" :gap "8px" :align-items "baseline" :flex-wrap "wrap"
                 :padding "2px 0" :font-family mono-stack :font-size "11px"}}
   [:span {:style {:color (continuation-status-colour (:status row)) :font-weight 600 :min-width "9rem"}}
    "reply-to → " (some-> (:status row) name)]
   [:span {:style {:color mode-accent}} (str (:mutation row))]
   [:span {:style {:color (:text-primary tokens)}} (pr-str (:target row))]
   (when (:work-id row)
     [:span {:style {:color (:text-tertiary tokens)}} "work " (pr-str (:work-id row))])])

(defn- continuations-section [{:keys [continuations mutation-invalidations]}]
  (section
    {:first? false :testid "rf-xray-resources-continuations"}
    (section-caption "Mutation continuations + scoped invalidation"
                     "rf-xray-resources-continuations-caption")
    [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
     ;; the descriptor-level invalidation evidence (cache consequence)
     (if (seq mutation-invalidations)
       (into [:div {:data-testid "rf-xray-resources-mutation-invalidation-body"
                    :style {:display "flex" :flex-direction "column" :gap "1px"}}]
             (for [row mutation-invalidations]
               ^{:key (str (:id row))} (mutation-invalidation-row-view row)))
       (empty-caption "No scoped mutation invalidations in this epoch."
                      "rf-xray-resources-mutation-invalidation-empty"))
     ;; the call-site :reply-to continuation dispatch (workflow)
     (if (seq continuations)
       (into [:div {:data-testid "rf-xray-resources-continuations-body"
                    :style {:display "flex" :flex-direction "column" :gap "1px"}}]
             (for [row continuations]
               ^{:key (str (:id row))} (continuation-row-view row)))
       (empty-caption "No :reply-to continuations dispatched in this epoch."
                      "rf-xray-resources-continuations-empty"))]))

;; ---- §6d OPTIMISTIC MUTATIONS (EP-0019) ---------------------------------
;;
;; The optimistic-mutation lifecycle: each `:rf.mutation/optimistic-applied`
;; paired by `:snapshot-id` with its terminal settle. The bug-class question
;; "I saw an optimistic apply — did it COMMIT or ROLL BACK, and was there a
;; conflict?" answered in one row: the apply (its snapshot + affected keys),
;; the `:outcome` chip (`:pending` in flight / `:reconciled` committed /
;; `:rolled-back`), and on rollback the per-key restored-vs-conflict
;; disposition + the resolved `:on-conflict` rule. The force-clobber warnings
;; (an `:on-conflict :force` restore OVER a concurrent authoritative write)
;; render loud beneath. Per Spec 016 §Optimistic mutations / §Surfacing to
;; tooling + Xray spec 024 §The optimistic-mutation lifecycle.

(defn- optimistic-outcome-colour
  "Outcome → token for an optimistic-apply row. `:reconciled` (committed —
  the happy path) green, `:rolled-back` amber (the inverse won), `:pending`
  (still in flight, optimistic value live on the cache) accent."
  [outcome]
  (case outcome
    :reconciled  (:green tokens)
    :rolled-back (:warning tokens)
    :pending     mode-accent
    (:text-tertiary tokens)))

(defn- optimistic-outcome-label
  [outcome]
  (case outcome
    :reconciled  "reconciled (committed)"
    :rolled-back "rolled back"
    :pending     "pending (optimistic)"
    (str (some-> outcome name))))

(defn- optimistic-mutation-row-view [row]
  (let [testid (str "rf-xray-resources-optimistic-row-" (:id row))]
    [:div {:data-testid testid
           :data-mutation (str (:mutation row))
           :data-outcome (str (some-> (:outcome row) name))
           :style {:display "flex" :flex-direction "column" :gap "2px"
                   :padding "3px 0" :font-family mono-stack :font-size "11px"}}
     [:div {:style {:display "flex" :gap "8px" :align-items "baseline" :flex-wrap "wrap"}}
      [:span {:data-testid (str testid "-outcome")
              :style {:color       (optimistic-outcome-colour (:outcome row))
                      :font-weight 600 :min-width "9rem"}}
       (optimistic-outcome-label (:outcome row))]
      [:span {:style {:color mode-accent}} (str (:mutation row))]
      (when (:instance row)
        [:span {:style {:color (:text-tertiary tokens)}} (pr-str (:instance row))])
      (when (:generation row)
        [:span {:style {:color (:text-tertiary tokens)}} "gen " (:generation row)])
      [:span {:style {:color (:text-tertiary tokens)}}
       (count (:affected-keys row)) " key(s)"]
      (when (seq (:target-unresolved row))
        [:span {:data-testid (str testid "-unresolved")
                :style {:color (:warning tokens)}}
         "unresolved (fail-closed): "
         (str/join " " (map str (:target-unresolved row)))])]
     ;; the optimistically-patched scoped keys (the snapshot's affected set)
     (when (seq (:affected-keys row))
       (into [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"
                            :padding-left "12px"}}
              [:span {:style {:color (:text-tertiary tokens)}} "affected"]]
             (for [k (:affected-keys row)]
               ^{:key (str (:resource-id k) (get-in k [:params :preview]))}
               (summary-chip (:scope k) nil))))
     ;; ON RECONCILE — the committed keys (authoritative write owned them)
     (when (= :reconciled (:outcome row))
       [:div {:data-testid (str testid "-committed")
              :style {:padding-left "12px" :color (:green tokens)}}
        (count (:committed row)) " committed"
        (when (seq (:reconciliation-refetches row))
          [:span {:style {:color (:info tokens) :margin-left "6px"}}
           (count (:reconciliation-refetches row)) " refetched"])])
     ;; ON ROLLBACK — the conflict-aware per-key disposition + on-conflict rule
     (when (= :rolled-back (:outcome row))
       [:div {:data-testid (str testid "-rollback")
              :style {:display "flex" :flex-direction "column" :gap "1px"
                      :padding-left "12px"}}
        [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap"}}
         [:span {:style {:color (:text-tertiary tokens)}}
          "on-conflict " (str (some-> (:on-conflict row) name))]
         [:span {:style {:color (:green tokens)}}
          (count (:restored row)) " restored"]
         (when (seq (:conflicted row))
           [:span {:data-testid (str testid "-conflicted")
                   :style {:color (:error tokens)}}
            (count (:conflicted row)) " conflicted"])
         (when (seq (:refetched row))
           [:span {:style {:color (:info tokens)}}
            (count (:refetched row)) " refetched"])]
        ;; per-key restored-vs-conflict disposition (the truthful evidence)
        (into [:div {:style {:display "flex" :flex-direction "column" :gap "1px"}}]
              (for [d (:dispositions row)]
                ^{:key (str (get-in d [:resource/key :resource-id])
                            (get-in d [:resource/key :params :preview]))}
                [:span {:style {:color (if (:conflict d)
                                         (:warning tokens)
                                         (:text-tertiary tokens))}}
                 (str (get-in d [:resource/key :resource-id]))
                 (if (:restored d) " · restored" " · refetch")
                 (when (:conflict d)
                   (str " · conflict (" (some-> (:on-conflict d) name) ")"))]))])]))

(defn- optimistic-force-clobber-row-view [row]
  [:div {:data-testid (str "rf-xray-resources-optimistic-clobber-row-" (:id row))
         :data-mutation (str (:mutation row))
         :style {:display "flex" :gap "8px" :align-items "baseline" :flex-wrap "wrap"
                 :padding "2px 0" :font-family mono-stack :font-size "11px"
                 :color (:error tokens)}}
   [:span {:style {:font-weight 600 :min-width "9rem"}} "⚠ force clobber"]
   [:span {:style {:color mode-accent}} (str (:mutation row))]
   (when (:instance row)
     [:span {:style {:color (:text-tertiary tokens)}} (pr-str (:instance row))])
   [:span {:style {:color (:warning tokens)}}
    (count (:forced-keys row)) " key(s) clobbered"]
   (when (:recovery row)
     [:span {:style {:color (:text-tertiary tokens)}}
      "recovery " (str (some-> (:recovery row) name))])])

(defn- optimistic-mutations-section [{:keys [optimistic-mutations optimistic-force-clobbers]}]
  (section
    {:first? false :testid "rf-xray-resources-optimistic"}
    (section-caption "Optimistic mutations" "rf-xray-resources-optimistic-caption")
    [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
     (if (seq optimistic-mutations)
       (into [:div {:data-testid "rf-xray-resources-optimistic-body"
                    :style {:display "flex" :flex-direction "column" :gap "3px"}}]
             (for [row optimistic-mutations]
               ^{:key (str (:id row))} (optimistic-mutation-row-view row)))
       (empty-caption "No optimistic mutations in this epoch."
                      "rf-xray-resources-optimistic-empty"))
     ;; the loud :force-over-a-concurrent-write clobber warnings
     (when (seq optimistic-force-clobbers)
       (into [:div {:data-testid "rf-xray-resources-optimistic-clobber-body"
                    :style {:display "flex" :flex-direction "column" :gap "1px"}}]
             (for [row optimistic-force-clobbers]
               ^{:key (str (:id row))} (optimistic-force-clobber-row-view row))))]))

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
  Subscribes to `:rf.xray/resources-tab-data` and renders the sections
  top → bottom: static registry, named scope resolvers (EP-0016 D3), live
  instances, work ledger, route/resource graph, lifecycle timeline,
  invalidation graph, scope resolution timeline (EP-0016 D3), mutation
  continuations + scoped invalidation (EP-0016 D1/D2), optimistic mutations
  (EP-0019 — the apply→reconcile/rollback lifecycle), cache growth, scope
  audit + lints. When the host has no resources registered AND no live
  instances, renders the silent-by-default caption."
  []
  (let [{:keys [silent? registry scope-resolvers instances work route-graph
                live-work stale-races stale-tally
                timeline invalidations scope-resolutions mutation-invalidations
                continuations optimistic-mutations optimistic-force-clobbers
                cache-growth audit]}
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
        (scope-resolvers-section scope-resolvers)
        (instances-section instances)
        (work-ledger-section work)
        ;; EP-0011 uniform reply-envelope reads — "what is still running?"
        ;; (live work + active-effects tally) and the cross-family stale-races
        ;; view. Silent-by-default: each renders nothing when its data is
        ;; empty (quiet baseline for a settled app).
        (live-work-section {:live-work live-work :stale-tally stale-tally})
        (stale-races-section stale-races)
        (route-graph-section route-graph)
        (timeline-section timeline)
        (invalidation-section invalidations)
        (scope-resolution-section scope-resolutions)
        (continuations-section {:continuations continuations
                                :mutation-invalidations mutation-invalidations})
        (optimistic-mutations-section
          {:optimistic-mutations optimistic-mutations
           :optimistic-force-clobbers optimistic-force-clobbers})
        (cache-growth-section cache-growth)
        (audit-section audit)])]))

;; ---- production value sources --------------------------------------------
;;
;; The raw values the production data subs read. Shared with the
;; test-override seam (`install-test-overrides!` below) so each override
;; branch — `(or override (real …))` — lives in ONE place (the seam),
;; not duplicated across production and test surfaces (rf2-e8330v).

;; Host-registry reads (`:resource` / `:resource-scope` / `:route`) go through
;; `host-registry/*` (the generation-bypassing default-realm form), NOT bare
;; `(rf/registrations …)`: these run inside the Resources subs' COMPUTATION, and
;; Xray seats in its OWN image-loaded `:rf/xray` frame, so the sub build binds
;; the registrar to Xray's image generation — a bare read would resolve through
;; Xray's OWN image (no host `:resource*` ids) and lose the host registry. See
;; `day8.re-frame2-xray.host-registry`.
(defn- registered-resources-value []
  (host-registry/registrations :resource))

(defn- registered-scope-resolvers-value []
  (host-registry/registrations :resource-scope))

(defn- resource-entries-value [target-runtime-db]
  (when (map? target-runtime-db)
    (get-in target-runtime-db h/entries-rel-path)))

(defn- resource-work-ledger-value [target-runtime-db]
  (when (map? target-runtime-db)
    (get target-runtime-db h/work-ledger-key)))

(defn- resource-routing-slice-value [target-runtime-db]
  (when (map? target-runtime-db)
    (get target-runtime-db h/routing-key)))

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
    - `:rf.xray/resource-routing-slice` — the live routing-runtime subtree
      (`[:rf.runtime/routing]`) from `:rf.xray/target-frame-runtime-db`,
      backing the LIVE route/resource graph (current route + nav-token +
      unsettled-blocking set — rf2-m5u3gt). Test override.
    - `:rf.xray/resources-tab-data` — the view-facing composite over the
      registry + entries + ledger + route registry + trace buffer + routing
      slice; the single read the Panel subscribes. Its `:route-graph` joins
      the static route plan against the live instance/work rows + routing
      slice (per-resource freshness rollup, the active route flagged
      `:current?` with its live `:blocking-live` wait points).

  The test-only override seam (six `:rf.xray/set-*-override-for-test`
  events + companion `*-override` subs) is NOT installed here —
  production registration carries no `-for-test` ids. Tests opt into it
  via `install-test-overrides!` (rf2-e8330v / xxo3zz F3).

  Read-only: NO event registered here dispatches a `:rf.resource/*`
  event — inspection never becomes an owner (Spec 016)."
  []

  ;; Production data subs --------------------------------------------------

  (rf/reg-sub :rf.xray/registered-resources
    :<- [:rf.xray/trace-buffer]
    (fn [_buffer _]
      (registered-resources-value)))

  ;; Named resource-scope resolvers (rf2-hls77w, EP-0016 D3 / Spec 016
  ;; §Named resource-scope resolvers). The static `:resource-scope` registry
  ;; read decoupled, exactly like `:registered-resources` above.
  (rf/reg-sub :rf.xray/registered-scope-resolvers
    :<- [:rf.xray/trace-buffer]
    (fn [_buffer _]
      (registered-scope-resolvers-value)))

  (rf/reg-sub :rf.xray/resource-entries
    :<- [:rf.xray/target-frame-runtime-db]
    (fn [target-runtime-db _]
      (resource-entries-value target-runtime-db)))

  (rf/reg-sub :rf.xray/resource-work-ledger
    :<- [:rf.xray/target-frame-runtime-db]
    (fn [target-runtime-db _]
      (resource-work-ledger-value target-runtime-db)))

  (rf/reg-sub :rf.xray/resource-sub-reads
    (fn [_db _]
      ;; Empty by default; the host/runtime never populates app-db with
      ;; observed sub-reads, so the production value is the empty vector.
      ;; The test seam supplies fixtures via the override slot.
      []))

  ;; The routing-runtime slice backing the LIVE route/resource graph
  ;; (rf2-m5u3gt). Reads `[:rf.runtime/routing]` off the target frame's
  ;; runtime-db (decoupled, like the Routing tab's current-route sub) and
  ;; surfaces the live `:current` route slice (carrying `:route-id` + `:nav-token`)
  ;; + the per-nav-token unsettled-blocking set.
  (rf/reg-sub :rf.xray/resource-routing-slice
    :<- [:rf.xray/target-frame-runtime-db]
    (fn [target-runtime-db _]
      (resource-routing-slice-value target-runtime-db)))

  ;; View-facing composite -------------------------------------------------

  (rf/reg-sub :rf.xray/resources-tab-data
    :<- [:rf.xray/registered-resources]
    :<- [:rf.xray/resource-entries]
    :<- [:rf.xray/resource-work-ledger]
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/resource-sub-reads]
    :<- [:rf.xray/resource-routing-slice]
    :<- [:rf.xray/registered-scope-resolvers]
    (fn [[registrations entries ledger trace-buffer sub-reads routing-slice
          scope-resolvers] _]
      (let [now-ms        (.now js/Date)
            routes-map    (host-registry/registrations :route)
            registry-rows (h/project-registry registrations routes-map)
            instance-rows (h/project-instances entries now-ms)
            work-rows     (h/project-work-ledger ledger)
            resolver-rows (h/project-scope-resolvers scope-resolvers)]
        {:silent?       (and (empty? registry-rows) (empty? instance-rows)
                             (empty? resolver-rows))
         :registry      registry-rows
         ;; rf2-hls77w (EP-0016 D3): the named resource-scope resolver
         ;; registry — id + declared inputs (paths summarized) + whole-db
         ;; cost flag. Per-resolution input VALUES + resolved scope surface
         ;; only via the egress-projected :rf.resource/scope-resolved trace.
         :scope-resolvers resolver-rows
         :instances     instance-rows
         :work          work-rows
         ;; The UNIFORM reply-envelope reads (rf2-zqefg3.7). These read the
         ;; canonical EP-0011 work/reply facts (`:work/id` / `:work/kind` /
         ;; reply `:status` / stale-suppression carried+current) the SAME way
         ;; for every async family — so "what is still running?" and the
         ;; stale-races view are cross-family, not resource-private. The
         ;; resource ledger is the only family writing the ledger today; as
         ;; HTTP / route / machine / timer families write their own ledger
         ;; rows + emit their reply-envelope trace ops, these surfaces pick
         ;; them up with no panel change (one vocabulary, many families).
         :live-work     (reply/live-work ledger trace-buffer)
         :stale-races   (reply/races-by-work-id trace-buffer)
         :stale-tally   (reply/stale-tally-by-kind trace-buffer)
         :route-graph   (let [current (h/routing-current routing-slice)]
                          (h/project-route-graph
                            routes-map
                            {:instance-rows instance-rows
                             :work-rows     work-rows
                             :current       current
                             ;; rf2-cduftx F2 — scope the live wait points to
                             ;; the CURRENT route's nav-token bucket only.
                             ;; Flattening all coexisting nav-token buckets
                             ;; let an OLD token's unsettled key bleed onto
                             ;; the active route's `:blocking-live`, falsely
                             ;; reporting it as blocked (Spec 024 §Route/
                             ;; resource graph: per-nav-token unsettled set).
                             :blocking-keys (h/routing-blocking-keys
                                              routing-slice (:nav-token current))}))
         :timeline      (h/lifecycle-timeline trace-buffer)
         :invalidations (h/invalidation-graph trace-buffer)
         ;; EP-0016 D3 (slice 8): the named-scope-resolver RESOLUTION timeline
         ;; — `:rf.resource/scope-resolved` rows (which resolver ran, resolved
         ;; scope summarized, fail-closed nil evidence).
         :scope-resolutions (h/scope-resolutions trace-buffer)
         ;; EP-0016 D2 (slice 8): the descriptor-level invalidation evidence off
         ;; the mutation settlement traces (resolved scope per descriptor +
         ;; fail-closed `:unresolved` + Rider-1 `:populate-exempt`).
         :mutation-invalidations (h/mutation-invalidation-evidence trace-buffer)
         ;; EP-0016 D1 (slice 8): the call-site `:reply-to` continuation
         ;; dispatch evidence (`:rf.mutation/replied` — phase 6, after cache
         ;; consequences + instance settlement).
         :continuations (h/mutation-continuations trace-buffer)
         ;; EP-0019 (slice 4b): the optimistic-mutation lifecycle — each
         ;; `:rf.mutation/optimistic-applied` paired by `:snapshot-id` with its
         ;; terminal settle (`:reconciled` commit / `:rolled-back`), so a
         ;; developer SEES an optimistic apply, its snapshot, and whether it
         ;; committed or rolled back (with the conflict outcome). The
         ;; `:optimistic-force-clobbers` are the loud `:force`-restored-over-a-
         ;; concurrent-write warnings.
         :optimistic-mutations (h/optimistic-lifecycle trace-buffer)
         :optimistic-force-clobbers (h/optimistic-force-clobbers trace-buffer)
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

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) ---------------------

(defn install-test-overrides!
  "Install the Resources panel's test-only override seam — the six
  `:rf.xray/set-*-override-for-test` events + companion `*-override`
  subs, then RE-register the production data subs to layer each override
  read on top (`(or override (real …))`). Tests opt in by calling this
  AFTER `register-xray-handlers!` (typically via
  `test-support/install-test-overrides!`). **Test-only — never call
  from production.**"
  []
  (rf/reg-event :rf.xray/set-registered-resources-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :registered-resources-override)
          (assoc db :registered-resources-override ov))}))
  (rf/reg-sub :rf.xray/registered-resources-override
    (fn [db _] (get db :registered-resources-override)))

  (rf/reg-event :rf.xray/set-registered-scope-resolvers-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :registered-scope-resolvers-override)
          (assoc db :registered-scope-resolvers-override ov))}))
  (rf/reg-sub :rf.xray/registered-scope-resolvers-override
    (fn [db _] (get db :registered-scope-resolvers-override)))

  (rf/reg-event :rf.xray/set-resource-entries-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :resource-entries-override)
          (assoc db :resource-entries-override ov))}))
  (rf/reg-sub :rf.xray/resource-entries-override
    (fn [db _] (get db :resource-entries-override)))

  (rf/reg-event :rf.xray/set-resource-work-ledger-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :resource-work-ledger-override)
          (assoc db :resource-work-ledger-override ov))}))
  (rf/reg-sub :rf.xray/resource-work-ledger-override
    (fn [db _] (get db :resource-work-ledger-override)))

  (rf/reg-event :rf.xray/set-resource-sub-reads-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :resource-sub-reads-override)
          (assoc db :resource-sub-reads-override ov))}))
  (rf/reg-sub :rf.xray/resource-sub-reads-override
    (fn [db _] (get db :resource-sub-reads-override)))

  (rf/reg-event :rf.xray/set-resource-routing-slice-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov) (dissoc db :resource-routing-slice-override)
          (assoc db :resource-routing-slice-override ov))}))
  (rf/reg-sub :rf.xray/resource-routing-slice-override
    (fn [db _] (get db :resource-routing-slice-override)))

  ;; Override-aware re-registration of the production data subs.
  (rf/reg-sub :rf.xray/registered-resources
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/registered-resources-override]
    (fn [[_buffer override] _]
      (or override (registered-resources-value))))

  (rf/reg-sub :rf.xray/registered-scope-resolvers
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/registered-scope-resolvers-override]
    (fn [[_buffer override] _]
      (or override (registered-scope-resolvers-value))))

  (rf/reg-sub :rf.xray/resource-entries
    :<- [:rf.xray/target-frame-runtime-db]
    :<- [:rf.xray/resource-entries-override]
    (fn [[target-runtime-db override] _]
      (if (some? override) override (resource-entries-value target-runtime-db))))

  (rf/reg-sub :rf.xray/resource-work-ledger
    :<- [:rf.xray/target-frame-runtime-db]
    :<- [:rf.xray/resource-work-ledger-override]
    (fn [[target-runtime-db override] _]
      (if (some? override) override (resource-work-ledger-value target-runtime-db))))

  (rf/reg-sub :rf.xray/resource-sub-reads
    :<- [:rf.xray/resource-sub-reads-override]
    (fn [override _] (or override [])))

  (rf/reg-sub :rf.xray/resource-routing-slice
    :<- [:rf.xray/target-frame-runtime-db]
    :<- [:rf.xray/resource-routing-slice-override]
    (fn [[target-runtime-db override] _]
      (if (some? override) override (resource-routing-slice-value target-runtime-db))))
  nil)
