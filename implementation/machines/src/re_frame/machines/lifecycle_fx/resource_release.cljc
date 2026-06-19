(ns re-frame.machines.lifecycle-fx.resource-release
  "Machine→resource lease release on actor destroy (rf2-xw5t0y).

  Per Spec 016 §Release authority is per owner kind (016:290):

    | Machine | [:machine machine-id instance-id] | Actor destroy — when the
    owning machine instance is stopped/destroyed (005-StateMachines), its
    resource leases are released. |

  The owner key the machine runtime owns is `[:machine actor-id]` — the
  runtime-derivable machine-owner key the derivation algebra names (Spec
  Derivations §Lifecycle: `[:machine :upload/main]`; machines
  `tooling/node-for` emits `:owner [:machine id]`). `actor-id` is the
  registered machine-id for a singleton and the `<type>#<n>` for a spawned
  actor — the SAME id the actor's `ensure` carries as `:owner [:machine
  actor-id]`, so releasing it drops exactly the destroyed actor's leases.

  THE LEAK this closes (rf2-xw5t0y, from the rf2-na0j8r conceptual audit): the
  machine teardown NEVER released the actor's resource leases. A machine that
  `ensure`d a resource under its `[:machine actor-id]` owner and was then
  destroyed LEAKED the lease — the entry stayed owner-pinned and kept
  refetching / polling forever. The spec leaned on \"machine liveness is a pure
  function of frame-state\" as if release were automatic, but resource liveness
  is a SEPARATE durable owner-set, not derived from machine state.

  ## Decoupling + the no-resources guard

  machines NEVER `:require`s resources (it is a SIBLING artefact; machines
  depends only on core). The release is fired by KEYWORD dispatch of the
  EXISTING `:rf.resource/release-owner` effect — the same indirection routing
  uses (`re-frame.resources.route` dispatches it for `[:route route-id
  nav-token]` owners). The release effect drops the owner from EVERY entry it
  owns via the owner-index, so the machine side need not know the resource
  ids; releasing a never-leased owner is a benign no-op (empty `:released`).

  `release-owner-registered?` gates the dispatch: an app that uses machines
  WITHOUT the resources artefact has no `:rf.resource/release-owner` handler,
  and an unconditional dispatch would surface `:rf.error/no-such-handler` on
  every actor destroy. The guard keeps the resources-absent path a clean no-op
  and preserves the no-machines→resources dependency.

  Two call shapes, ONE owner-key + guard contract:

    - `release-fx-entry` — the `[:dispatch [:rf.resource/release-owner …]]`
      `:fx` entry, or nil when guarded out. `finalize.cljc` APPENDS it to its
      returned `:fx` vector (the `:final?` auto-destroy path is a handler-style
      return, so the dispatch rides the same drain as the rest of the
      teardown's fx).
    - `release-actor-resource-leases!` — fire the entry directly via
      `re-frame.fx/do-fx`. `destroy.cljc`'s `teardown-live-actor!` (the
      explicit-destroy + frame-destroy-cascade + spawn-all paths) calls this
      AFTER the actor is gone, mirroring routing's release-on-supersession.

  Together these cover EVERY destroy cause — explicit `:rf.machine/destroy`,
  declarative-`:spawn` exit cascade, `:spawn-all` per-child teardown, the
  frame-destroy cascade, AND the `:final?`-state auto-destroy."
  (:require [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn release-owner-registered?
  "True iff the resources artefact's `:rf.resource/release-owner` event handler
  is registered (i.e. resources is loaded into this runtime). The guard that
  keeps a no-resources app's actor-destroy a clean no-op."
  []
  (some? (registrar/lookup :event :rf.resource/release-owner)))

(defn release-fx-entry
  "The `[:dispatch [:rf.resource/release-owner {:owner [:machine actor-id]}]]`
  `:fx` entry that releases the destroyed actor's resource leases, or nil when
  `actor-id` is nil or resources is not loaded (the guard — see ns docstring).
  Appended to `finalize-machine`'s returned `:fx` vector so the `:final?`
  auto-destroy releases the actor's leases on the same drain as its other
  teardown fx."
  [actor-id]
  (when (and actor-id (release-owner-registered?))
    [:dispatch [:rf.resource/release-owner {:owner [:machine actor-id]}]]))

(defn release-actor-resource-leases!
  "Fire the release fx directly via `re-frame.fx/do-fx` (for the side-effecting
  teardown pipeline in `destroy.cljc`, which runs fx imperatively rather than
  returning an `:fx` vector). Guarded + no-op identically to `release-fx-entry`.
  Uses the frame's `:platform` (defaults to `:client`) so platform-gated fx
  behave consistently with the destroy-time `:exit`-cascade fx fire."
  [frame-id actor-id]
  (when-let [entry (release-fx-entry actor-id)]
    (let [platform (or (:platform (frame/frame-meta frame-id)) :client)]
      (fx/do-fx frame-id [entry] platform)))
  nil)
