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

   Per spec/017-Testing-Story.md §Public vocabulary every variant body
   is plain data — no fn-slots; the public authoring keys are `:setup`
   (preconditions) + `:script` (behaviour-under-test). The view at the
   centre of each variant is referenced by
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
   call it again after a `clear-all!`. The canonical Story vocabulary
   (`:dev :docs :test :screenshot :experimental :internal :agent` tags,
   the lifecycle machine, the `:rf.assert/*` handlers, the layout-debug
   decorator set, and the v1 panel set) auto-installs on the first
   `reg-*` call below — no explicit boot step required."
  []
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
  ;;
  ;; The authoring surface is `:setup` (preconditions, dispatched before
  ;; the play runs) + `:script` (the ordered behaviour-under-test). Per
  ;; spec/017-Testing-Story.md §Public vocabulary `:setup` / `:script`
  ;; are the public keys. A `:script` step is a tagged vector; an
  ;; `:rf.assert/*` checkpoint rides the `[:assert [...]]` step tag, and a
  ;; bare event vector lifts to `[:dispatch ...]`. This is what makes a
  ;; Story double as a test — the `:rf.assert/*` checkpoints are the
  ;; assertions, run by the CI play-runner.
  (story/reg-variant :story.counter/empty
    {:doc    "Fresh counter at zero."
     :setup  [[:counter/initialise]]
     :script [[:assert [:rf.assert/path-equals [:counter/value] 0]]]
     :tags   #{:dev :docs :test :{{main}}/canonical}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter/incremented
    {:doc    "Counter after three increments. The increments are
             dispatch-sync'd FROM the :script (not seeded in :setup) so
             :rf.assert/dispatched? observes them on the trace bus."
     :setup  [[:counter/initialise]]
     :script [[:dispatch-sync [:counter/increment]]
              [:dispatch-sync [:counter/increment]]
              [:dispatch-sync [:counter/increment]]
              [:assert [:rf.assert/path-equals [:counter/value] 3]]
              [:assert [:rf.assert/sub-equals  [:counter/value] 3]]
              [:assert [:rf.assert/dispatched? [:counter/increment]]]]
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
