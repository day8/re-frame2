(ns re-frame.story.play.presence
  "The presence rung of Story playback — the seam a `[:flush-presence]`
  script step reaches for (rf2-qwzmt, S4-H; Spec 004 §Presence).

  ## Why a rung exists

  A variant whose view renders a `(ui/presence {:timeout-ms n} …)` boundary
  RETAINS a removed keyed child in `:unmounting` until the `:timeout-ms`
  safety bound fires. Playback then has a settlement problem the
  `settled-boundary` ladder cannot solve: the retention is a CLOCK, not a
  queue. Draining the router to a fixed point (`:headless`), flushing
  reactions (`:cljs-reactive`) or awaiting a React commit (`:dom`) all leave
  the retained child exactly where it is — the next step races the timeout.
  The only wall-clock answer is `[:wait ms]`, which the determinism gate
  REFUSES (`re-frame.story.determinism` §The bare-`[:wait ms]` opt-out).

  `re-frame.ui.test/flush-presence!` is the framework's own answer: it
  advances the presence FAKE CLOCK, firing due exits with no wall-clock
  sleep. `(flush-presence!)` advances to quiescence (every pending exit
  fires); `(flush-presence! ms)` advances the logical clock by `ms`, firing
  only the exits that come due — so a script can observe the retained
  `:unmounting` phase and THEN its terminal removal, deterministically.

  This namespace is the (thin) seam, nothing more. Story does NOT model
  presence, own a clock, or reimplement the three-phase machine — it CALLS
  the framework verb and lets the framework own the semantics.

  ## The host hook

  Story's shipped jar must not depend on `re-frame.ui` (`day8/re-frame2-ui`
  is pre-publication — the same isolation `re-frame.story.view-tool` keeps),
  so the verb arrives through the `re-frame.story.late-bind` registry under
  `:flush-presence!`. A `re-frame.ui`-hosted shell installs it once at boot:

      (require '[re-frame.ui.test :as uit])

      (story-presence/install-presence-flush!
        (fn [ms] (if ms (uit/flush-presence! ms) (uit/flush-presence!))))

  With NO host installed the advance is a no-op — deliberately, and for the
  same reason the framework's own JVM arm of `flush-presence!` is a no-op:
  a host with no presence runtime has no retention to advance, and
  `.cljc` playback must read the same on either host (`ui.test` §host
  parity). It is NOT a `:cannot-run` refusal: a refusal is for a step that
  would otherwise pass FALSELY, and there is no false pass here — the
  assertion that follows carries its own capability gate (a `:assert-dom`
  step still refuses `:cannot-run` under a headless runner).

  Pure data → data apart from the hook call itself, so both the JVM
  `clojure -M:test` gate and the node `npm run test:cljs` gate exercise it."
  (:require [re-frame.story.late-bind :as late-bind]))

(def presence-flush-hook-key
  "The `re-frame.story.late-bind` hook key a `re-frame.ui`-hosted shell
  registers its presence-clock advance under. The fn signature is
  `(f ms-or-nil) → any` — `nil` means advance to quiescence, a number means
  advance the logical clock by that many milliseconds. Both map 1:1 onto the
  two arities of `re-frame.ui.test/flush-presence!`."
  :flush-presence!)

(defn install-presence-flush!
  "Register the host's presence-clock advance under the `:flush-presence!`
  late-bind hook. `flush-fn` takes ONE argument — the ms to advance by, or
  `nil` for 'to quiescence' — mirroring `flush-presence!`'s two arities.
  Idempotent (re-registration replaces, per Spec 001 hot-reload semantics)."
  [flush-fn]
  (late-bind/set-fn! presence-flush-hook-key flush-fn)
  nil)

(defn presence-flush-fn
  "The installed presence-advance fn, or nil when no host registered one
  (the bare JVM / a Story app with no compiled-view presence boundaries)."
  []
  (late-bind/get-fn presence-flush-hook-key))

(defn advance!
  "Advance the presence clock by `ms` (nil → to quiescence) through the
  installed host verb. Returns a small result map:

    {:status :advanced :ms <ms|nil>}   — the host verb ran
    {:status :no-host  :ms <ms|nil>}   — no host installed; a no-op advance
                                         (host parity — see the ns docstring)
    {:status :error    :ms … :error s} — the host verb threw

  Never throws: the executor projects the map into a step-result."
  [ms]
  (if-let [f (presence-flush-fn)]
    (try
      (f ms)
      {:status :advanced :ms ms}
      (catch #?(:clj Throwable :cljs :default) e
        {:status :error
         :ms     ms
         :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))}))
    {:status :no-host :ms ms}))
