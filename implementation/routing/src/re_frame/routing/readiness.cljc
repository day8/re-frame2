(ns re-frame.routing.readiness
  "Pure route-readiness projection — EP-0037 R1 / Spec 012 §Route readiness
  is a resource projection.

  Route readiness (`:rf.route/transition` / `:rf.route/error`) is a PURE
  projection over the active route plan's blocking resource requirements. It
  is NEVER driven by `:on-match` (which is fire-and-forget). This namespace is
  the ONE pure implementation the navigation-commit assembler uses to seed the
  stored slice; the Resources artefact's reply-driven reconciliation (Spec 016
  §Route integration) and any hydration / epoch-restore reconciliation project
  through the same table:

  | Effective-plan state                       | :transition | :error          |
  |--------------------------------------------|-------------|-----------------|
  | plan could not be formed                   | :error      | planning error  |
  | a blocking first load pending, none failed | :loading    | nil             |
  | a blocking first load failed               | :error      | first failure   |
  | all blocking have usable data, or none     | :idle       | nil             |
  | Resources artefact absent                  | :idle       | nil             |

  R1 applies this over R0's behaviour-preserving leaf-only plan; R2 swaps the
  plan input for the parent-to-leaf branch plan without changing the table.")

(defn project-at-commit
  "Seed the readiness projection at activation-commit time from a freshly
  built (leaf-only, until R2) resource plan.

  `plan` is the `:routing/on-route-entry` hook result
  `{:blocking #{<scoped-key> …} :plan-error <err-or-nil> …}` (nil when no
  Resources artefact is loaded or the route declares no `:resources`). At
  commit the blocking requirements have only just been ensured, so a non-empty
  blocking set is a pending first load → `:loading`; a planning failure →
  `:error` (carrying the planning error); otherwise → `:idle`. The Resources
  reply handlers reconcile `:loading` → `:idle` / `:error` through the same
  table as each blocking requirement settles. Returns
  `{:transition :idle|:loading|:error :error <err-or-nil>}`."
  [plan]
  (let [plan-error (:plan-error plan)
        blocking   (:blocking plan)]
    (cond
      plan-error     {:transition :error   :error plan-error}
      (seq blocking) {:transition :loading :error nil}
      :else          {:transition :idle    :error nil})))
