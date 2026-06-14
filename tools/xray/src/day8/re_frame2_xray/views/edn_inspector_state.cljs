(ns day8.re-frame2-xray.views.edn-inspector-state
  "Expansion-state registration for the edn-inspector widget (rf2-l42sg7).

  Extracted from `views/edn-inspector` as the first cohesive-slice carve-
  out of that over-large namespace (rf2-xxo3zz F5). This namespace owns
  ONE concern: the per-node expansion overrides the widget stores in
  re-frame app-db — the `expansion-slot` key, its `reg-sub`, the
  `toggle-node` / `set-node` / `reset-expansion` events, the pure
  `expansion-key` composer, and the `resolve-expanded?` projection.

  The split is behaviour-preserving: the same global event/sub ids
  register here, and `views/edn-inspector` re-exports the public vars
  (`expansion-slot`, `expansion-key`, `resolve-expanded?`) so existing
  call sites and tests keep resolving against the widget namespace
  unchanged.

  Why a separate namespace: the widget's expansion state is a self-
  contained app-db slice with no dependency on the renderer (no tokens,
  no hiccup). Isolating it shrinks the renderer namespace and gives the
  expansion machinery its own reviewable, testable home — the sibling
  `widths` (width-aware heuristic) and `zoom` (focus navigation) view-
  state groups remain in `views/edn-inspector` for now; this pass takes
  one clean slice only."
  (:require [re-frame.core :as rf]))

;; =========================================================================
;; expansion state — lives in :rf.xray.edn-inspector/expansion under the
;; SURROUNDING instance frame (the shell's frame-id; `:rf/xray` for the
;; production singleton). Per-frame so N shells keep independent expansion.
;; =========================================================================

(def expansion-slot
  "App-db slot holding the per-node expansion overrides. Public so
  the consuming panel's reset affordance can clear it.

  Distinct from the legacy `:rf.xray/edn-inspector-expansion` slot
  used by `edn-inspector/render` — keeping them separate lets the old
  engine and the new widget coexist during the phased rollout."
  :rf.xray.edn-inspector/expansion)

(defn expansion-key
  "Compose the per-node expansion key. Pure data, JVM-portable."
  [panel-id mount-id path]
  [panel-id mount-id (vec path)])

(rf/reg-sub expansion-slot
  (fn [db _] (get db expansion-slot)))

(rf/reg-event :rf.xray.edn-inspector/toggle-node
  (fn [{:keys [db]} [_ panel-id mount-id path rendered-expanded?]]
    ;; rf2-y59tb — first click MUST invert the currently-visible state.
    ;;
    ;; The widget renders `default-expanded` paths (top-level nodes,
    ;; depth ≤ `default-expanded-depth`) open BEFORE the user clicks,
    ;; even though no override is stored. If the reducer flipped from
    ;; a hard-coded assumption (e.g. "first click opens") it would
    ;; emit the same state the user already sees — a silent no-op on
    ;; the first click.
    ;;
    ;; The dispatch payload now carries `rendered-expanded?` — the
    ;; value `resolve-expanded?` returned for this path on the last
    ;; render (i.e. what the user currently sees). When no override
    ;; is stored the reducer flips from that visible state; when an
    ;; override IS stored it flips the override (idempotent given a
    ;; consistent dispatcher).
    {:db (let [k       (expansion-key panel-id mount-id path)
          current (get-in db [expansion-slot k])
          next?   (if (contains? current :expanded?)
                    (not (boolean (:expanded? current)))
                    (not (boolean rendered-expanded?)))]
      (assoc-in db [expansion-slot k] {:expanded? next?}))}))

(rf/reg-event :rf.xray.edn-inspector/set-node
  (fn [{:keys [db]} [_ panel-id mount-id path expanded?]]
    {:db (assoc-in db [expansion-slot (expansion-key panel-id mount-id path)]
              {:expanded? (boolean expanded?)})}))

(rf/reg-event :rf.xray.edn-inspector/reset-expansion
  (fn [{:keys [db]} _]
    {:db (dissoc db expansion-slot)}))

(defn resolve-expanded?
  "Pure projection — given the per-render expansion map, the path,
  and the default-heuristic result, return whether THIS node renders
  expanded. The operator's sticky override (if present) wins."
  [expansion-map panel-id mount-id path default?]
  (let [k        (expansion-key panel-id mount-id path)
        override (get expansion-map k)]
    (if (contains? override :expanded?)
      (boolean (:expanded? override))
      (boolean default?))))
