(ns day8.re-frame2-causa.palette.subs
  "Subscriptions for the Causa command palette (rf2-wm7z4).

  ## Sub tree

  - `:rf.causa/palette-open?`   — boolean. Drives the modal mount.
  - `:rf.causa/palette-query`   — current input text.
  - `:rf.causa/palette-cursor`  — selected row index (0-based).
  - `:rf.causa/palette-index`   — full source-aggregator output.
    Recomputes when its input subs (trace-buffer, frame-ids,
    handler set) change.
  - `:rf.causa/palette-results` — ranked rows for the current query.
    Recomputes only when query OR index changes.
  - `:rf.causa/palette-active-item` — convenience: results[cursor].

  The `palette-index` sub deliberately pulls the registrar +
  frame-ids snapshot inside the sub body (i.e. NOT via `:<-`) — the
  registrar / frame registry are atoms outside re-frame's reactive
  graph, so a `:<-` dependency on them would never recompute.
  Recomputation happens whenever `:trace-buffer` writes (which is
  every dispatch) — i.e. roughly the same cadence as the trace
  panel's redraws — and the cost is bounded by `build-index` (linear
  in source-item count). Acceptable because the modal is closed
  most of the time; when open the user is actively typing.

  ## Sidebar items reference

  The panel list is the same one the sidebar renders (shell.cljs).
  Importing the shell's `sidebar-items` directly would create a
  shell→palette→shell cycle once we wire `Modal` into the shell;
  instead the palette holds its own canonical list and the shell
  reads from it. The list is small (~16 entries) and the
  duplication cost is negligible compared to the cycle-break."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.palette.sources :as sources]))

;; ---- canonical panel list ------------------------------------------------
;;
;; Single source of truth for both the sidebar (shell.cljs) and the
;; palette source aggregator. Mirrors `shell/sidebar-items` minus the
;; sidebar's `:dormant?` marker (the palette surfaces every panel
;; regardless of activity — a Hydration row shows up even when the
;; dormant glyph would otherwise hide it).
(def palette-panels
  [{:id :event-detail :label "Event detail"}
   {:id :time-travel  :label "Time travel"}
   {:id :app-db       :label "App-db"}
   {:id :causality    :label "Causality"}
   {:id :subs         :label "Subscriptions"}
   {:id :fx           :label "Effects"}
   {:id :trace        :label "Trace"}
   {:id :machines     :label "Machines"}
   {:id :flows        :label "Flows"}
   {:id :routes       :label "Routes"}
   {:id :performance  :label "Performance"}
   {:id :issues       :label "Issues"}
   {:id :schemas      :label "Schemas"}
   {:id :hydration    :label "Hydration"}
   {:id :mcp-server   :label "MCP"}])

(defn- handler-entries
  "Flat seq of `{:id :kind :doc :file :line}` rows from the framework
  registrar. Kinds covered: :event, :sub, :fx, :cofx — the four the
  user typically wants to jump to. The registrar query is dirt-cheap
  (transient walk over an in-memory map) so the cost lives in the
  downstream fuzzy pass."
  []
  (let [kinds [:event :sub :fx :cofx]]
    (->> kinds
         (mapcat
           (fn [kind]
             (->> (rf/registrations kind)
                  (map (fn [[id meta]]
                         {:id   id
                          :kind kind
                          :doc  (:doc meta)
                          :file (:file meta)
                          :line (:line meta)})))))
         vec)))

(defn install!
  "Install the palette's subs. Idempotent under re-frame's replace-
  in-place registrar semantics."
  []

  (rf/reg-sub :rf.causa/palette-open?
    (fn [db _query]
      (boolean (get db :palette-open? false))))

  (rf/reg-sub :rf.causa/palette-query
    (fn [db _query]
      (or (get db :palette-query) "")))

  (rf/reg-sub :rf.causa/palette-cursor
    (fn [db _query]
      (max 0 (or (get db :palette-cursor) 0))))

  ;; Layer-2 — aggregates the searchable corpus. Depends on
  ;; `:rf.causa/trace-buffer` (recent events) so it re-fires on every
  ;; trace push; the registrar / frame snapshot lookups happen inside
  ;; the body and ride that same recompute cadence.
  (rf/reg-sub :rf.causa/palette-index
    :<- [:rf.causa/trace-buffer]
    (fn [buffer _query]
      ;; The sub fn does not receive `db`; we reach into the registrar
      ;; (a process-global atom) + the framework's frame registry
      ;; via the public rf wrappers.
      (sources/build-index
        {:panels             palette-panels
         :trace-buffer       buffer
         :frame-ids          (rf/frame-ids)
         :handlers           (handler-entries)})))

  ;; Layer-3 — ranked results for the current query.
  (rf/reg-sub :rf.causa/palette-results
    :<- [:rf.causa/palette-index]
    :<- [:rf.causa/palette-query]
    (fn [[index query] _query]
      (sources/rank index query)))

  (rf/reg-sub :rf.causa/palette-active-item
    :<- [:rf.causa/palette-results]
    :<- [:rf.causa/palette-cursor]
    (fn [[results cursor] _query]
      (when (and (seq results) (< cursor (count results)))
        (nth results cursor))))

  nil)
