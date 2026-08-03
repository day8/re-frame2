(ns re-frame.routing.readiness
  "Pure route-readiness projection — EP-0037 R1 / Spec 012 §Route readiness
  is a resource projection.

  Route readiness (`:rf.route/transition` / `:rf.route/error`) is a PURE
  projection over the active route plan's blocking resource requirements. It
  is NEVER driven by `:on-match` (which is fire-and-forget). This namespace is
  the COMMIT-TIME half of that projection — the one the navigation-commit
  assembler uses to seed the stored slice from the freshly built plan:

  | Effective-plan state                       | :transition | :error          |
  |--------------------------------------------|-------------|-----------------|
  | plan could not be formed                   | :error      | planning error  |
  | a blocking first load pending, none failed | :loading    | nil             |
  | a blocking first load failed               | :error      | first failure   |
  | all blocking have usable data, or none     | :idle       | nil             |
  | Resources artefact absent                  | :idle       | nil             |

  THE TABLE HAS TWO IMPLEMENTATIONS, and changing the precedence means changing
  both. The reply-driven half is `re-frame.resources.route/reconcile-readiness`
  (Spec 016 §Route integration): it re-reads the live resource facts on every
  settle, retained-owner adoption, `ensure` fresh-skip, SSR hydration and epoch
  restore, and states the same precedence in Spec 016 resource vocabulary
  (`:failed` / `:pending` / an empty outstanding set, where the rows above read
  plan-error / blocking / usable-data). Packaging requires the split rather than
  one shared function: `implementation/resources/deps.edn` holds routing as a
  TEST-ONLY dep and the routing integration is late-bound, so resources cannot
  `:require` this namespace, and publishing a `:routing/project-readiness`
  late-bind hook for a three-line `cond` would cost more than the duplication
  removes. The two agree today — error beats loading beats idle in both — and
  a shared conformance assertion keeps them agreeing:
  `re-frame.readiness-projector-conformance-cljs-test` drives every Spec 012
  input class through BOTH halves, each in its own vocabulary, and fails on a
  divergence. It lives under `implementation/resources/test/` because that is
  the only tree that can require both namespaces; it pins agreement and does
  NOT imply the two should be unified.

  R1 applies this over R0's behaviour-preserving leaf-only plan; R2 swaps the
  plan input for the parent-to-leaf branch plan without changing the table.")

(defn project-at-commit
  "Seed the readiness projection at activation-commit time from a freshly
  built (leaf-only, until R2) resource plan.

  `plan` is the `:routing/on-route-entry` hook result
  `{:blocking {<key-id> <scoped-key>} :plan-error <err-or-nil> …}` (nil when no
  Resources artefact is loaded or the route declares no `:resources`; the
  blocking carrier is byte-keyed with no order promise — rf2-btdl1, and this
  projection reads only its emptiness). At
  commit the blocking requirements have only just been ensured, so a non-empty
  blocking map is a pending first load → `:loading`; a planning failure →
  `:error` (carrying the planning error); otherwise → `:idle`. The Resources
  reply handlers then reconcile `:loading` → `:idle` / `:error` as each blocking
  requirement settles, through their OWN statement of the same table
  (`re-frame.resources.route/reconcile-readiness` — see the ns docstring for why
  there are two). Returns
  `{:transition :idle|:loading|:error :error <err-or-nil>}`."
  [plan]
  (let [plan-error (:plan-error plan)
        blocking   (:blocking plan)]
    (cond
      plan-error     {:transition :error   :error plan-error}
      (seq blocking) {:transition :loading :error nil}
      :else          {:transition :idle    :error nil})))
