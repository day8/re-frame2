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
    quiescence, a number → advance by that many logical ms). This is the
    EXACT counterpart of the advance the donor's `re-frame.ui.test/
    flush-presence!` wrapped; the wrapper is what this bridge supplies, and
    the advance itself never moved.

  - **`re-frame.substrate.adapter/flush-render!`** — core's adapter-dispatched
    SYNCHRONOUS commit (Spec 006 §`flush-render!`). The removals an advance
    fires are React state updates, so a bare advance would leave the DOM one
    commit behind; running the advance INSIDE the substrate's synchronous
    commit boundary returns with the DOM settled. Freehand's override of that
    slot also closes the pending ViewCell window inside the same boundary and
    converges, and runs the shared `guard-open-drain!` first — so a flush
    forced from inside an open drain fails loud
    (`:rf.error/flush-in-open-epoch`) exactly as the donor's did.

  Composing them is why crossing to Freehand needed no new published verb.
  It also makes the advance SYNCHRONOUS, where the donor's was Promise-backed
  (its settle point was an awaited React `act`): `flushSync` has committed by
  the time it returns, so there is nothing left to await. The seam has always
  supported both — `presence/advance!` attaches a `:pending` thenable only
  when the host verb returns one, and `settle!` calls back synchronously when
  it does not — so the runner needs no arm for this and gains no latency.

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
            ;; Core's adapter-dispatched synchronous commit. Core is already a
            ;; hard Story dependency, so this adds no coordinate — it is named
            ;; here rather than reached through `re-frame.core` because the
            ;; substrate-contract verbs live on this namespace.
            [re-frame.substrate.adapter :as substrate]
            ;; The substrate's presence clock. This is the ONE require that
            ;; makes this file the app-side half of the seam.
            [re-frame.freehand.presence-runtime :as fh-presence]))

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
     "Advance the presence clock and return with the DOM SETTLED.

     `flush-render!` is an OPTIONAL contract fn: core's dispatcher runs the
     thunk only when the installed adapter ships the slot, and returns nil
     otherwise. A headless Story run installs `plain-atom`, which ships none —
     there is no React commit to settle there, but the clock is still real and
     its removal callbacks must still fire. So the advance is re-run outside
     the boundary exactly when the boundary never ran it, and never twice."
     [ms]
     (let [advanced? (volatile! false)]
       (substrate/flush-render! (fn []
                                  (vreset! advanced? true)
                                  (advance-clock! ms)))
       (when-not @advanced? (advance-clock! ms))
       nil)))

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
