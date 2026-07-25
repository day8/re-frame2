(ns re-frame.story.play.presence-host
  "OPTIONAL host bridge — the ONE canonical way to make `[:flush-presence]`
  reach the framework's presence clock (rf2-36biz; Spec 017 §Presence-bearing
  variants).

  ## Why a separate namespace exists

  `re-frame.story.play.presence` is the SEAM: it holds the `:flush-presence!`
  late-bind hook and calls whatever the host registered there. It deliberately
  does NOT `:require` `re-frame.ui` — Story's published jar must not depend on
  the never-published `day8/re-frame2-ui` (the same isolation the view-tool
  consumer keeps). But a seam nothing installs is a seam nothing reaches: the
  hook shipped with no production installer at all, so a presence-bearing
  script could never advance the real clock.

  This namespace is the missing half — the small bridge that DOES require
  `re-frame.ui.test` and wires its verb into the hook. It is opt-in and never
  loaded by Story itself, so the dependency direction is unchanged: Story's
  jar carries this file but never requires it, and the require only resolves
  in an app that already has `re-frame.ui` on its classpath. Exactly the shape
  of `re-frame.story.generate.test-check` (an optional adapter whose dependency
  lives on the `:test` alias only), differing only in that CLJS cannot
  `requiring-resolve`, so an app without `re-frame.ui` gets a compile-time
  \"namespace not found\" naming the missing dep rather than a runtime throw.

  ## Integration — one `:require`

  An app that mounts the Story shell AND renders `re-frame.ui` compiled views
  requires this namespace once at boot:

      (ns my-app.story
        (:require [re-frame.story]
                  [re-frame.story.play.presence-host]))

  Loading it installs the verb; there is nothing else to call. `install!` is
  public and idempotent for the one case that needs it — a test fixture that
  wiped the hook registry (`late-bind/clear!`) and wants it back.

  ## What it deliberately does NOT do

  It installs the flush verb and nothing else. In particular it leaves the
  presence WALL CLOCK armed (`presence-runtime/set-wall-clock!` stays true):
  disabling it process-wide would freeze every retained child in a variant a
  human is merely clicking through in the canvas, which no `[:flush-presence]`
  step is there to release. Playback stays deterministic regardless — the
  clock's `fire-timer!` is id-keyed and exactly-once, so a logical advance
  fires a due exit early and the real `setTimeout` that lands later is a no-op.

  With this bridge absent, `[:flush-presence]` REFUSES (`:cannot-run`) rather
  than skipping silently — see `re-frame.story.play.presence`."
  (:require [re-frame.story.play.presence :as presence]
            [re-frame.ui.test             :as ui-test]))

(defn install!
  "Register `re-frame.ui.test/flush-presence!` under the `:flush-presence!`
  late-bind hook, so a `[:flush-presence]` / `[:flush-presence ms]` script
  step advances the real presence clock.

  Story adds no clock, no phase model and no scheduler of its own — the two
  script arities map 1:1 onto the framework verb's two arities: `nil` ms means
  advance to quiescence, a number advances the logical clock by that many
  milliseconds. `0` is a legal advance, so the arity is chosen on `some?`, not
  on truthiness.

  Runs at this namespace's LOAD time (a bare `:require` is the whole
  integration). Idempotent — re-registration replaces the slot, per Spec 001
  hot-reload semantics."
  []
  (presence/install-presence-flush!
    (fn [ms]
      ;; `ui.test/flush-presence!` is the CLJS mounted host only (the ratified
      ;; ui.test minimization deleted the JVM no-op). The JVM structural render
      ;; has no presence lifecycle — no retention timers to advance — so a JVM
      ;; `[:flush-presence]` step is a no-op returning nil, exactly the old JVM
      ;; `flush-presence!` behaviour. Reader-conditional so the JVM arm never
      ;; references the now-CLJS-only var.
      #?(:clj nil
         :cljs (if (some? ms)
                 (ui-test/flush-presence! ms)
                 (ui-test/flush-presence!)))))
  nil)

;; Load-time install, mirroring how `re-frame.story.canonical` registers its
;; auto-install hook at ns load: requiring the bridge IS the integration.
(install!)
