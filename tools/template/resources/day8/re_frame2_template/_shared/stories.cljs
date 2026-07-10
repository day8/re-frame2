(ns {{namespace}}.stories
  "Story playground registrations for the scaffolded counter.

   Emitted when the project is scaffolded with `:include-story? true`.

   The four shipped `reg-*` macros each appear once:

   - `reg-story`       — `:story.counter` parent.
   - `reg-variant`     — two variants (`/empty` + `/incremented`).
   - `reg-tag`         — `:{{main}}/canonical` (project-scoped tag).
   - `reg-workspace`   — `:Workspace.counter/all` (auto-grid layout).

   Variant bodies are data: `:setup` establishes preconditions and `:script`
   describes behaviour and assertions. Components and events are referenced
   by registration id."
  (:require [re-frame.story :as story]
            ;; Variant ids resolve against these registrations.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views]))

(defn register-all!
  "Register the scaffolded Story artefacts. Idempotent — the trailing
   top-level call fires this at namespace load; tests / hot-reload may
   call it again after a `clear-all!`. The canonical Story vocabulary
   and assertion handlers auto-installs on the first `reg-*` call below."
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
  ;; Setup runs before the play. Script steps drive behaviour and carry the
  ;; assertions used by the play runner.
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
