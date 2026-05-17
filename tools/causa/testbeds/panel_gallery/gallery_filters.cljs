(ns panel-gallery.gallery-filters
  "Story coverage for the **Auto-filter pill cluster + edit popup**
  (rf2-kbrkx follow-on to rf2-ak4ms + rf2-sszlr chrome gallery).

  The auto-filter feature spans two visual surfaces:

    1. The top ribbon's pill cluster — IN pills tint green, OUT
       pills tint magenta, each carries an `✎` edit affordance.
    2. The edit popup — modal overlay opened via the trailing
       `[ + ]` add button, via clicking an existing pill, or via
       right-clicking an event-list row (which seeds an OUT pill
       pre-populated with the row's event-id).

  Each variant renders the full Causa 4-layer chrome via
  `:panel-gallery.chrome/Shell` and seeds `:rf/causa` to drive the
  ribbon + (optionally) open the edit popup against the expected
  trigger shape.

  ## Frame discipline (same caveat as gallery-chrome)

  The chrome's `shell-view` body wraps itself in `[rf/frame-provider
  {:frame :rf/causa}]`. All seed events route through the testbed's
  `:panel-gallery.chrome/seed!` `:after-seeds` lane so the writes
  land on `:rf/causa` (where the chrome + modal read), not on the
  variant frame Story pre-allocated."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the auto-filter Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-filters
    {:axis :feature
     :doc  "Causa auto-filter pills — IN / OUT pill cluster +
            edit popup per spec/018-Event-Spine §7."})

  (story/reg-story :story.causa.filters
    {:doc        "Visual gallery of the auto-filter pill cluster
                 + edit popup. Variants exercise the empty ribbon,
                 a mixed-loaded ribbon, the edit popup in :add /
                 :pill / :context trigger shapes, and the right-
                 click context-menu shortcut to the OUT-filter
                 draft."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/causa-filters}
     :substrates #{:reagent}})

  ;; ----- 1. Empty ribbon — no pills, just the trailing [ + ] add.
  ;; The default first-session honest empty state per spec/018 §7
  ;; 'Empty defaults'.
  (story/reg-variant :story.causa.filters/empty
    {:doc        "Top ribbon with no filter pills — the default
                 first-session honest empty state. Only the
                 trailing `[ + ]` add affordance is visible
                 alongside the nav cluster + frame picker + mode
                 pill + right icons."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :filters      {:in [] :out []}}]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. Mixed loaded — 3 IN + 5 OUT pills with diverse
  ;; pattern shapes (keyword, glob, substring). Exercises the
  ;; pill cluster's visual contract under realistic load.
  (story/reg-variant :story.causa.filters/mixed-loaded
    {:doc        "Top ribbon with 3 IN pills (keyword, glob,
                 namespace) + 5 OUT pills spanning diverse
                 patterns (event-id, glob, substring). Pins the
                 pill cluster's visual contract under a realistic
                 mixed load — green IN tint vs magenta OUT tint,
                 with `✎` edit affordances on each."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :event
                    :filters
                    {:in  [{:pattern :cart/add}
                           {:pattern ":auth/*"}
                           {:pattern ":order/cart/*"}]
                     :out [{:pattern :mouse-move}
                           {:pattern :anim-frame}
                           {:pattern ":telemetry/*"}
                           {:pattern "presence"}
                           {:pattern ":heartbeat"}]}}]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 3. Edit popup open via the trailing `[ + ]` add
  ;; affordance — `:source :add :mode :in` so the popup arrives
  ;; empty + IN default, no Delete button.
  (story/reg-variant :story.causa.filters/edit-popup-add
    {:doc        "Edit popup open via the trailing `[ + ]` add
                 affordance. Trigger `{:source :add :mode :in}` —
                 popup arrives empty + IN default; no `[Delete]`."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :filters      {:in [] :out []}
                    :after-seeds
                    [[:rf.causa/open-edit-popup
                      {:source :add :mode :in}]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. Edit popup open via pill click — `:source :pill`
  ;; with pre-populated draft + `[Delete]` button visible.
  (story/reg-variant :story.causa.filters/edit-popup-edit-existing
    {:doc        "Edit popup open via clicking an existing pill.
                 Trigger `{:source :pill :mode :in :idx 0 :pill
                 {:pattern :auth/*}}` — popup pre-populated with
                 `:auth/*`, IN selected, `[Delete]` visible."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :filters      {:in  [{:pattern :auth/*}]
                                   :out []}
                    :after-seeds
                    [[:rf.causa/open-edit-popup
                      {:source :pill
                       :mode   :in
                       :idx    0
                       :pill   {:pattern :auth/*}}]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. Right-click context-menu shortcut — `:rf.causa/hide-
  ;; event-type` opens the popup with `:source :context :mode :out`
  ;; pre-populated with the row's event-id (OUT-filter draft).
  (story/reg-variant :story.causa.filters/right-click-menu
    {:doc        "Edit popup open via the right-click event-row
                 context-menu shortcut (`:rf.causa/hide-event-
                 type`). Trigger `{:source :context :mode :out
                 :pill {:pattern :mouse-move}}` — popup pre-
                 populated with the row's event-id + OUT default;
                 no `[Delete]` (it's an Add)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :event
                    :filters      {:in [] :out []}
                    :after-seeds
                    [[:rf.causa/hide-event-type :mouse-move]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.filters/all
    {:doc      "All five auto-filter variants. The chrome
                internally wraps :rf/causa via a hardcoded frame-
                provider, so workspace cells share interior state —
                see canvas-mode (sidebar pick) for per-variant
                fidelity."
     :layout   :variants-grid
     :story    :story.causa.filters
     :columns  1
     :tags     #{:dev}}))

(register-all!)
