(ns {{namespace}}.stories
  "Story playground registrations for the scaffolded counter.

   Emitted by `day8/re-frame2-template` when scaffolded with
   `:include-story? true`. Mirrors the canonical shape at
   `tools/story/testbeds/counter_with_stories/stories.cljs` in the
   re-frame2 repo — kept small here so the scaffold reads at a glance
   rather than overwhelming a first-time Story user.

   The four shipped `reg-*` macros each appear once:

   - `reg-story`       — `:story.counter` parent.
   - `reg-variant`     — two variants (`/empty` + `/incremented`).
   - `reg-tag`         — `:{{main}}/canonical` (project-scoped tag).
   - `reg-workspace`   — `:Workspace.counter/all` (auto-grid layout).

   Per spec/007 §Variants every variant body is plain data — no
   fn-slots. The view at the centre of each variant is referenced by
   id (`:{{namespace}}.views/counter-app`); the events the variant
   dispatches reference event-ids. Add more `reg-variant` / `reg-tag`
   / `reg-decorator` / `reg-mode` calls below as your app grows."
  (:require [re-frame.story :as story]
            ;; Source the events / subs / views by requiring their
            ;; namespaces — registrations fire as a side effect of
            ;; loading the ns. The variant bodies reference those
            ;; registrations by keyword id.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views]))

(defn register-all!
  "Register the scaffolded Story artefacts. Idempotent — the trailing
   top-level call fires this at namespace load; tests / hot-reload may
   call it again after a `clear-all!`."
  []
  ;; Install the seven canonical Story tags (`:dev :docs :test
  ;; :screenshot :experimental :internal :agent`), the lifecycle
  ;; machine, the canonical `:rf.assert/*` handlers, the layout-debug
  ;; decorator set, and the v1 panel set. Idempotent.
  (story/install-canonical-vocabulary!)

  ;; -- reg-tag — a project-scoped tag for the canonical screenshot ---------
  (story/reg-tag :{{main}}/canonical
    {:doc "Tag applied to the variant that ships as the example's
          canonical screenshot."})

  ;; -- reg-story — the parent. Inherits down to every variant. -------------
  (story/reg-story :story.counter
    {:doc        "The scaffolded counter."
     :component  :{{namespace}}.views/counter-app
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -- reg-variant — empty (zero) + incremented (three clicks) -------------
  (story/reg-variant :story.counter/empty
    {:doc    "Fresh counter at zero."
     :events [[:counter/initialise]]
     :play   [[:rf.assert/path-equals [:counter/value] 0]]
     :tags   #{:dev :docs :test :{{main}}/canonical}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter/incremented
    {:doc    "Counter after three increments. Dispatched from :play so
             :rf.assert/dispatched? observes them."
     :events [[:counter/initialise]]
     :play   [[:counter/increment]
              [:counter/increment]
              [:counter/increment]
              [:rf.assert/path-equals [:counter/value] 3]
              [:rf.assert/sub-equals  [:counter/value] 3]
              [:rf.assert/dispatched? [:counter/increment]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; -- reg-workspace — auto-enumerated grid layout -------------------------
  (story/reg-workspace :Workspace.counter/all
    {:doc      "Auto-enumerated grid — pulls every variant off
                :story.counter. New variants appear here without
                touching this workspace."
     :layout   :variants-grid
     :for      :story.counter
     :columns  2
     :tags     #{:docs}}))

;; Fire the registrations once at namespace load.
(register-all!)
