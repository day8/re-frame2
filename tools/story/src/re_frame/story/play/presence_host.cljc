(ns re-frame.story.play.presence-host
  "OPTIONAL host bridge — the ONE canonical way to make `[:flush-presence]`
  reach the framework's presence clock (rf2-36biz; Spec 017 §Presence-bearing
  variants).

  ## Why a separate namespace exists

  `re-frame.story.play.presence` is the SEAM: it holds the `:flush-presence!`
  late-bind hook and calls whatever the host registered there. It deliberately
  does NOT `:require` the view substrate — Story's published jar must not
  depend on `day8/re-frame2-freehand` (the same isolation the view-tool
  consumer keeps). But a seam nothing installs is a seam nothing reaches: the
  hook shipped with no production installer at all, so a presence-bearing
  script could never advance the real clock.

  This namespace is the missing half — the small bridge that DOES require the
  substrate's presence runtime and wires an advance into the hook. It is
  opt-in and never loaded by Story itself, so the dependency direction is
  unchanged: Story's jar carries this file but never requires it, and the
  require only resolves in an app that already has Freehand on its classpath.
  Exactly the shape of `re-frame.story.generate.test-check` (an optional
  adapter whose dependency lives on the `:test` alias only), differing only in
  that CLJS cannot `requiring-resolve`, so an app without Freehand gets a
  compile-time \"namespace not found\" naming the missing dep rather than a
  runtime throw.

  ## Integration — one `:require`

  An app that mounts the Story shell AND renders Freehand views requires this
  namespace once at boot:

      (ns my-app.story
        (:require [re-frame.story]
                  [re-frame.story.play.presence-host]))

  Loading it installs the verb; there is nothing else to call. `install!` is
  public and idempotent for the one case that needs it — a test fixture that
  wiped the hook registry (`late-bind/clear!`) and wants it back.

  ## The two verbs it composes, and why neither is a new one

  Freehand publishes the MECHANISM this bridge needs, in two pieces that were
  already there:

  - **`re-frame.freehand.presence-runtime/advance-clock!`** — the logical
    clock advance, in the same two arities the script step has (`nil` → to
    quiescence, a number → advance by that many logical ms). The advance is
    the framework's; only the wrapper around it is this bridge's.

  - **`(:flush-render! v/adapter)`** — FREEHAND's synchronous commit, the
    Spec 006 `flush-render!` contract slot read off the published adapter
    VALUE. The removals an advance fires are React state updates, so a bare
    advance would leave the DOM one commit behind; running the advance INSIDE
    that commit boundary returns with the DOM settled. Freehand's
    implementation also closes the pending ViewCell window inside the same
    boundary and converges, and runs the shared `guard-open-drain!` first —
    so a flush forced from inside an open drain fails loud
    (`:rf.error/flush-in-open-epoch`) exactly as the donor's did.

  Composing them is why crossing to Freehand needed no new published verb.
  It also makes the advance SYNCHRONOUS, where the donor's was Promise-backed
  (its settle point was an awaited React `act`): `flushSync` has committed by
  the time it returns, so there is nothing left to await. The seam has always
  supported both — `presence/advance!` attaches a `:pending` thenable only
  when the host verb returns one, and `settle!` calls back synchronously when
  it does not — so the runner needs no arm for this and gains no latency.

  ## Why FREEHAND's boundary and not the PROCESS adapter's (rf2-gzmg0)

  The commit that has to happen is a commit of a FREEHAND root, so the
  boundary that closes it is Freehand's — not whichever substrate adapter the
  process happens to have installed.

  This bridge first reached for `re-frame.substrate.adapter/flush-render!`,
  core's adapter-DISPATCHED spelling of the same slot, on the reasonable-
  sounding ground that a Freehand app installs `v/adapter` and the dispatch
  therefore lands on Freehand's implementation anyway. It does not, in the
  topology that matters. Every shipped Story testbed seats
  `re-frame.adapter.reagent/adapter` — Story's own shell is a Reagent
  application — and a Freehand variant mounts inside that process. Reagent's
  slot is `(fn [f] (f) (reagent.core/flush))`: it runs the thunk bare and then
  drains Reagent's own before-flush / ratom / component / after-render queues.
  A presence removal makes no Reagent component dirty (it calls a plain React
  `useState` setter inside the Freehand root), so that drain never enters
  `react-dom/flushSync`, and it never closes Freehand's ViewCell window
  either. The verb would return, the clock would report zero pending, and the
  retained child would still be painted — a green clock over a stale DOM,
  which is the ONE failure this rung exists to exclude.

  Reading the slot off `v/adapter` removes the process adapter from the
  question entirely. Under `v/adapter` it is byte-identical to what the
  dispatch found before; under any other seating it is the boundary that is
  actually correct. `re-frame.freehand.substrate/flush-render!` — the fn this
  resolves to — is adapter-state-free by construction: it stands on
  `react-dom/flushSync`, the module-global ViewCell registry and core's
  router-state drain guard, none of which consult the installed adapter.

  It also retires the bridge's old headless special case. `flush-render!` is
  an OPTIONAL contract fn and the plain-atom adapter a headless Story run
  installs ships none, so the dispatched call used to run nothing and the
  bridge re-ran the advance outside the boundary to compensate. Freehand's
  slot always exists, so the advance now runs exactly once, on every host,
  through one path.

  KNOWN, WIDER, AND NOT THIS BRIDGE'S (rf2-gzmg0): a Freehand view's
  SUBSCRIPTION reads are inert on the ratom substrates. Core's observation
  port activates a derived value by adding a watch and dereferencing it, and a
  stock Reagent `Reaction` deref'd outside `*ratom-context*` never captures
  its sources — so it never notifies, and the ViewCell it feeds is never
  marked dirty. That is upstream of this file and of Story, it is why the
  mounted proof drives its presence children through props rather than a
  subscription under the Reagent seating, and it is tracked separately.

  ## What it deliberately does NOT do

  It installs the flush verb and nothing else. In particular it leaves the
  presence WALL CLOCK armed (`presence-runtime/set-wall-clock!` stays true):
  disabling it process-wide would freeze every retained child in a variant a
  human is merely clicking through in the canvas, which no `[:flush-presence]`
  step is there to release. This is why the bridge reaches for `advance-clock!`
  and NOT for the runtime's own `flush-presence!`, which disarms the wall clock
  as its first act. Playback stays deterministic regardless — the clock's
  `fire-timer!` is id-keyed and exactly-once, so a logical advance fires a due
  exit early and the real `setTimeout` that lands later is a no-op.

  With this bridge absent, `[:flush-presence]` REFUSES (`:cannot-run`) rather
  than skipping silently — see `re-frame.story.play.presence`."
  (:require [re-frame.story.play.presence :as presence]
            ;; The substrate's presence clock. Host-neutral, so it is required
            ;; unconditionally — the JVM arm below never calls into it, but the
            ;; namespace loads on both hosts.
            [re-frame.freehand.presence-runtime :as fh-presence]
            ;; The Freehand DOOR, for `v/adapter` alone. CLJS-only because the
            ;; adapter value is: it stands on the core React spine, and a JVM
            ;; structural render has no React root to commit.
            #?@(:cljs [[re-frame.freehand :as v]])))

#?(:cljs
   (def ^:private freehand-commit!
     "Freehand's own synchronous commit boundary — the Spec 006
     `flush-render!` contract slot, read off the PUBLISHED adapter value.

     Deliberately not `re-frame.substrate.adapter/flush-render!`, which
     dispatches to whatever adapter the process installed: the commit that has
     to happen here is a commit of a Freehand root. See the ns docstring
     §Why FREEHAND's boundary and not the PROCESS adapter's."
     (:flush-render! v/adapter)))

#?(:cljs
   (defn- advance-clock!
     "The raw logical advance, arity-selected on `some?` so `0` — a legal
     advance — is not mistaken for 'to quiescence'."
     [ms]
     (if (some? ms)
       (fh-presence/advance-clock! ms)
       (fh-presence/advance-clock!))))

#?(:cljs
   (defn- advance-and-commit!
     "Advance the presence clock and return with the Freehand DOM SETTLED,
     whatever adapter the process installed. One advance, one boundary, one
     path on every host — Freehand always ships the slot, so there is no
     'the boundary ran nothing' case to compensate for."
     [ms]
     (freehand-commit! #(advance-clock! ms))
     nil))

(defn install!
  "Register the Freehand presence advance under the `:flush-presence!`
  late-bind hook, so a `[:flush-presence]` / `[:flush-presence ms]` script
  step advances the real presence clock.

  Story adds no clock, no phase model and no scheduler of its own — the two
  script arities map 1:1 onto the framework runtime's two advance arities:
  `nil` ms means advance to quiescence, a number advances the logical clock by
  that many milliseconds.

  Runs at this namespace's LOAD time (a bare `:require` is the whole
  integration). Idempotent — re-registration replaces the slot, per Spec 001
  hot-reload semantics."
  []
  (presence/install-presence-flush!
    (fn [ms]
      ;; The presence clock is a CLJS-only surface: the JVM structural render
      ;; has no presence lifecycle — no retention timers to advance — so a JVM
      ;; `[:flush-presence]` step is a no-op returning nil, exactly the old JVM
      ;; behaviour. Reader-conditional so the JVM arm never references the
      ;; CLJS-only vars.
      #?(:clj  nil
         :cljs (advance-and-commit! ms))))
  nil)

;; Load-time install, mirroring how `re-frame.story.canonical` registers its
;; auto-install hook at ns load: requiring the bridge IS the integration.
(install!)
