(ns xspike.arm-a
  "SPIKE (rf2-k97c.1) ARM A — Xray owns a STOCK-REAGENT root; the panel's
  subscription reads go through the tool-local binding in `xspike.observe`.

  The panel is the PRODUCTION App-DB inspector, copied verbatim from
  `day8.re-frame2-xray.panels.app-db-diff` with ONLY the two subscription
  reads respelled. Its body (`app-db-diff-state/state-body`), its value
  renderer (`views.edn-widget` -> `views.edn-inspector`), its subs, its
  events, its expansion machinery and its registry are the shipped ones,
  unmodified.

  THROWAWAY."
  (:require [reagent.dom.client :as rdc]
            [reagent2.dom.client :as rdc2]
            [re-frame.core :as rf]
            [xspike.observe :as obs]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]
            [day8.re-frame2-xray.shell :as xray-shell]
            [day8.re-frame2-xray.theme.tokens :refer [tokens sans-stack]])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---- style hoists: verbatim from the production panel ---------------------

(def ^:private panel-root-style
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private panel-body-host-style
  {:flex     1
   :overflow "auto"})

;; ---- the panel -----------------------------------------------------------
;;
;; DIFF AGAINST PRODUCTION (tools/xray/src/.../panels/app_db_diff.cljs):
;;   line 1 of the body:  @(rf/subscribe [:rf.xray/app-db-state])
;;                     -> (obs/<sub      [:rf.xray/app-db-state])
;;   line 2 of the body:  @(rf/subscribe [:rf.xray/app-db-current+diff])
;;                     -> (obs/<sub      [:rf.xray/app-db-current+diff])
;; Nothing else. Two touched sites, both pure substitution.

(reg-view Panel
  "ARM A copy of the production app-db tab root view."
  []
  (let [section-model     (obs/<sub [:rf.xray/app-db-state])
        selected-epoch-id (:epoch-id (obs/<sub [:rf.xray/app-db-current+diff]))]
    [:section {:data-testid            "rf-xray-app-db-diff"
               :data-rf-xray-diff-mode "full+diff"
               :style                  panel-root-style}
     [:div {:style panel-body-host-style}
      ^{:key selected-epoch-id}
      [state/state-body section-model]]]))

;; ---- the tool-owned root -------------------------------------------------

(defonce ^:private root (atom nil))
(defonce ^:private root-kind (atom nil))

(defn mounted? [] (some? @root))

(defn mount!
  "Own a STOCK-REAGENT React root at `node` and render the panel inside a
  DELIBERATE frame boundary — the epic's coupling (2). `rf/frame-provider`
  is `re-frame.views`' own component (a `:r>` interop head), not an
  adapter-routed one, so it renders under stock Reagent whatever the host
  installed.

  Measured: this works on an ELEMENT-SHAPED host (Hicasso), whose
  `:adapter/current-frame` hook reads the shared React context slot
  directly and is therefore renderer-agnostic. It FAILS on the
  reagent-slim host, whose hook is
  `re-frame.views/current-frame` -> the routed
  `:adapter/current-component` -> `reagent2.core/current-component`,
  which returns nil during a stock-Reagent render, so every `reg-view`
  in the tool raises `:rf.error/no-frame-context`."
  [node]
  (when-not @root
    (let [r (rdc/create-root node)]
      (reset! root r)
      (reset! root-kind :stock)
      (rdc/render r [rf/frame-provider {:frame xray-shell/default-frame-id}
                     [Panel]])))
  @root)

(defn mount-slim!
  "ARM A, VARIANT 3 — the same panel on a REAGENT-SLIM root. The repair
  for the failure `mount!` documents: the tool root must be built with
  the SAME ratom implementation the installed adapter published, or the
  adapter-routed hooks cannot see it. Xray already declares both
  Reagent builds (tools/xray/deps.edn), so this costs no new dependency
  — it costs a RENDERER SWITCH inside the tool, keyed on the host's
  adapter kind."
  [node]
  (when-not @root
    (let [r (rdc2/create-root node)]
      (reset! root r)
      (reset! root-kind :slim)
      (rdc2/render r [rf/frame-provider {:frame xray-shell/default-frame-id}
                      [Panel]])))
  @root)

(defn mount-leak!
  "POSITIVE CONTROL for the evidence-integrity probe (rf2-tqlmq). Same
  panel, same root, but scoped DELIBERATELY to the application frame
  `:above` instead of `:rf/xray` — the exact mistake rf2-tqlmq fixed. If
  `probe/xray-renders-in` cannot see THIS, its zeros elsewhere mean
  nothing."
  [node]
  (when-not @root
    (let [r (rdc/create-root node)]
      (reset! root r)
      (reset! root-kind :stock)
      (rdc/render r [rf/frame-provider {:frame :above} [Panel]])))
  @root)

(defn unmount! []
  (when-let [r @root]
    (let [k @root-kind]
      (reset! root nil)
      (reset! root-kind nil)
      (if (= k :slim) (rdc2/unmount r) (rdc/unmount r)))
    ;; ONE release site for the whole arm, not one per subscription.
    (obs/release-all!))
  nil)
