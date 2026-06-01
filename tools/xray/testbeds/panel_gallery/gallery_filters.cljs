(ns panel-gallery.gallery-filters
  "Story coverage for the **Auto-filter pill cluster + edit popup**
  (rf2-durls redo of the rf2-kbrkx gallery against the de-singletoned
  shell + four-bucket Story model).

  The auto-filter feature spans two visual surfaces:

    1. The top ribbon's pill cluster — IN pills tint green, OUT
       pills tint magenta, each carries an `✎` edit affordance.
    2. The edit popup — modal overlay opened via the trailing
       `[ + ]` add button, via clicking an existing pill, or via
       right-clicking an event-list row (which seeds an OUT pill
       pre-populated with the row's event-id).

  Each variant renders the full Xray 4-layer chrome via
  `:panel-gallery.chrome/Shell` and seeds the ribbon + (optionally)
  opens the edit popup against the expected trigger shape.

  ## Frame discipline (de-singletoned shell — rf2-1w07r)

  The chrome's `shell-view` takes a `:frame-id` opt; the `chrome-shell`
  wrapper threads the Story per-variant frame (`(rf/current-frame-id)`)
  into it, so the ribbon + the modal it mounts read from THIS variant's
  frame. Story's runtime dispatches every `:setup` step into that same
  variant frame, so a variant seeds its pills + opens the popup with the
  CANONICAL Xray events directly — no `:rf/xray` literal, no re-dispatch
  indirection. Cells in the grid are fully isolated."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn- filters-setup
  "Build the `:setup` event vector for an auto-filter variant. Seeds the
  trace buffer + active tab, adds the declared ribbon pills (each cell
  starts with empty `:active-filters`), then runs any popup-trigger
  events — all dispatched into the variant frame the chrome cell reads.

    :trace-buffer — vector → `:rf.xray/sync-trace-buffer`
    :selected-tab — kw     → `:rf.xray/select-tab`
    :filters      — {:in [pill …] :out [pill …]} → one add-filter per pill
    :triggers     — extra event vectors (e.g. `:rf.xray/open-edit-popup`)"
  [{:keys [trace-buffer selected-tab filters triggers]}]
  (let [{:keys [in out]} filters]
    (cond-> []
      (some? trace-buffer) (conj [:rf.xray/sync-trace-buffer trace-buffer])
      selected-tab         (conj [:rf.xray/select-tab selected-tab])
      (some? filters)      (into (concat (for [pill in]  [:rf.xray/add-filter :in pill])
                                         (for [pill out] [:rf.xray/add-filter :out pill])))
      (seq triggers)       (into (vec triggers)))))

(defn register-all!
  "Register the auto-filter Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/xray-filters
    {:axis :feature
     :doc  "Xray auto-filter pills — IN / OUT pill cluster +
            edit popup per spec/018-Event-Spine §7."})

  (story/reg-story :story.xray.filters
    {:doc        "Visual gallery of the auto-filter pill cluster
                 + edit popup. Variants exercise the empty ribbon,
                 a mixed-loaded ribbon, the edit popup in :add /
                 :pill / :context trigger shapes, and the right-
                 click context-menu shortcut to the OUT-filter
                 draft — each in its own isolated frame."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/xray-filters}
     :substrates #{:reagent}})

  ;; ----- 1. Empty ribbon — no pills, just the trailing [ + ] add.
  ;; The default first-session honest empty state per spec/018 §7
  ;; 'Empty defaults'.
  (story/reg-variant :story.xray.filters/empty
    {:doc        "Top ribbon with no filter pills — the default
                 first-session honest empty state. Only the
                 trailing `[ + ]` add affordance is visible
                 alongside the nav cluster + frame picker + mode
                 pill + right icons."
     :setup      (filters-setup
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :epoch
                    :filters      {:in [] :out []}})
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. Mixed loaded — 3 IN + 5 OUT pills with diverse
  ;; pattern shapes (keyword, glob, substring). Exercises the
  ;; pill cluster's visual contract under realistic load.
  (story/reg-variant :story.xray.filters/mixed-loaded
    {:doc        "Top ribbon with 3 IN pills (keyword, glob,
                 namespace) + 5 OUT pills spanning diverse
                 patterns (event-id, glob, substring). Pins the
                 pill cluster's visual contract under a realistic
                 mixed load — green IN tint vs magenta OUT tint,
                 with `✎` edit affordances on each."
     :setup      (filters-setup
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :epoch
                    :filters
                    {:in  [{:pattern :cart/add}
                           {:pattern ":auth/*"}
                           {:pattern ":order/cart/*"}]
                     :out [{:pattern :mouse-move}
                           {:pattern :anim-frame}
                           {:pattern ":telemetry/*"}
                           {:pattern "presence"}
                           {:pattern ":heartbeat"}]}})
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 3. Edit popup open via the trailing `[ + ]` add
  ;; affordance — `:source :add :mode :in` so the popup arrives
  ;; empty + IN default, no Delete button.
  (story/reg-variant :story.xray.filters/edit-popup-add
    {:doc        "Edit popup open via the trailing `[ + ]` add
                 affordance. Trigger `{:source :add :mode :in}` —
                 popup arrives empty + IN default; no `[Delete]`."
     :setup      (filters-setup
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :epoch
                    :filters      {:in [] :out []}
                    :triggers
                    [[:rf.xray/open-edit-popup {:source :add :mode :in}]]})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. Edit popup open via pill click — `:source :pill`
  ;; with pre-populated draft + `[Delete]` button visible.
  (story/reg-variant :story.xray.filters/edit-popup-edit-existing
    {:doc        "Edit popup open via clicking an existing pill.
                 Trigger `{:source :pill :mode :in :idx 0 :pill
                 {:pattern :auth/*}}` — popup pre-populated with
                 `:auth/*`, IN selected, `[Delete]` visible."
     :setup      (filters-setup
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :epoch
                    :filters      {:in  [{:pattern :auth/*}]
                                   :out []}
                    :triggers
                    [[:rf.xray/open-edit-popup
                      {:source :pill
                       :mode   :in
                       :idx    0
                       :pill   {:pattern :auth/*}}]]})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. Right-click context-menu shortcut — `:rf.xray/hide-
  ;; event-type` opens the popup with `:source :context :mode :out`
  ;; pre-populated with the row's event-id (OUT-filter draft).
  (story/reg-variant :story.xray.filters/right-click-menu
    {:doc        "Edit popup open via the right-click event-row
                 context-menu shortcut (`:rf.xray/hide-event-
                 type`). Trigger `{:source :context :mode :out
                 :pill {:pattern :mouse-move}}` — popup pre-
                 populated with the row's event-id + OUT default;
                 no `[Delete]` (it's an Add)."
     :setup      (filters-setup
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :epoch
                    :filters      {:in [] :out []}
                    :triggers
                    [[:rf.xray/hide-event-type :mouse-move]]})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.xray.filters/all
    {:doc      "All five auto-filter variants in one grid, each
                mounting the shell in its own isolated frame — the
                pill cluster + edit-popup states render side-by-side
                with no shared-state bleed."
     :layout   :variants-grid
     :story    :story.xray.filters
     :columns  1
     :tags     #{:dev}}))

(register-all!)
