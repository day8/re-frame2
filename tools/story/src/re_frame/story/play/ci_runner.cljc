(ns re-frame.story.play.ci-runner
  "CI-as-test discovery + driver glue for Story's `:script` slot.

  The companion to `re-frame.story.play.runner` (pure step state
  machine) and `re-frame.story.play.runner-events` (re-frame-side
  driver). This namespace exposes the seams a CI runner needs:

  - `variants-with-play-scripts` — discovery: the registered variant ids
    that run a play with at least one step. It reads the compiled plays
    (`runner-events/variant-plays`), the program the runtime executes, so a
    variant whose only script comes from a `:compose`d fragment is found.
    Works on JVM and CLJS.

  - `ci-rows` — enumerate the per-PLAY catalogue (one row per play; a
    `:plays` variant of size N yields N rows). The metadata bundle a CI
    runner needs per row (id, play-key, step count).

  - `ci-context` — Playwright-readable JSON envelope: the full per-
    variant catalogue, ready for `JSON.parse` on the browser side.

  - `install-ci-hooks!` (CLJS) — install `window.__rf2_story_ci`
    helpers so a Playwright runner can: (a) enumerate variants with
    `:script`, and (b) read each variant's terminal run-state
    (`rf.story.play.runner-events/current-state`) once the auto-run completes.
    No-op on JVM. The hook is INERT until called explicitly from a
    testbed init path — production / non-CI bundles never hit it.

  ## Why a separate ns

  - The `runner-events` ns mixes pure step execution with `re-frame`
    integration; the CI surface only needs the registrar query +
    `current-state` read. Splitting keeps the runner-events ns clear
    of CI-only ceremony (the `js/window` global) and lets unit tests
    cover the discovery seam without standing up a full Story shell.

  - The discovery seam is symmetric across JVM / CLJS (the registrar
    and the plan compiler are `.cljc`), so the same function powers both
    `clojure -M:test` coverage and the browser-side enumeration.

  ## JVM / CLJS split

  This ns is `.cljc`. `variants-with-play-scripts`, `ci-rows` and
  `ci-context` are JVM-runnable; the CLJS-only `install-ci-hooks!` is
  gated by reader conditionals."
  (:require [re-frame.story.registrar   :as rf.story.registrar]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            #?(:cljs [re-frame.story.config :as rf.story.config])
            #?(:cljs [re-frame.story.runtime :as rf.story.runtime])))

;; ---- pure discovery ------------------------------------------------------

(defn variants-with-play-scripts
  "A SORTED vector of the registered variant ids that run at least one play
  with a step — `:script`, `:plays`, or a `:compose`d fragment's script
  alike, read from the compiled plays (`runner-events/variant-plays`).

  Sorted so the CI runner walks variants in a deterministic order —
  helpful when comparing run logs across runs / branches."
  []
  (->> (keys (rf.story.registrar/registrations :variant))
       (filter (fn [vid]
                 (some (comp seq :script) (rf.story.play.runner-events/variant-plays vid))))
       (sort)
       (vec)))

(defn ci-rows
  "Enumerate the CI runner's per-row catalogue (multi-play).
  Each row is one compiled play of a `variants-with-play-scripts` variant;
  a variant with `:plays` of size N yields N rows; a single-script variant
  yields ONE row.

  Shape (one entry per row):
      {:variant-id <kw>
       :play-key   <string or nil>     ; play's :name (nil for single-script)
       :name       <string or nil>     ; same as :play-key, for parity
       :script-len <int>
       :auto-run?  <bool>}

  The CI runner uses this to drive one Playwright assertion per row.
  Stable order: variants sorted alphabetically, plays in declaration
  order within each variant."
  []
  (vec
    (mapcat
      (fn [vid]
        (map (fn [spec]
               {:variant-id vid
                :play-key   (:name spec)
                :name       (:name spec)
                :script-len (count (:script spec))
                :auto-run?  (boolean (:auto-run? spec))})
             (rf.story.play.runner-events/variant-plays vid)))
      (variants-with-play-scripts))))

(defn ci-context
  "Build the full CI catalogue: every variant with a play surface
  paired with the per-play `:rows` enumeration the multi-play runner
  uses to drive its per-play assertions. Pure data → data. Used by the
  Playwright runner via `install-ci-hooks!` (`ciContext().rows`)."
  []
  {:variants (variants-with-play-scripts)
   :rows     (ci-rows)})

;; ---- terminal-state helpers ---------------------------------------------

(defn terminal?
  "True iff `state` represents a run that has reached a terminal verdict —
  `:pass` / `:fail` / `:cannot-run` (`:cannot-run` is the unified distinct
  THIRD status, spec/017 §`:cannot-run`). Pure data → data."
  [state]
  (boolean (#{:pass :fail :cannot-run} (:status state))))

(defn project-state
  "Project a runner state map into the small shape the CI runner
  emits to its consumer (test report / JSON log). Strips the
  `:script` slot (already known from the spec) and projects
  per-step results into a stable shape. Pure data → data."
  [state]
  (when state
    (let [status   (:status state)
          results  (mapv
                     (fn [r]
                       (cond-> {:idx     (:idx r)
                                :type    (:type r)
                                :passed? (:passed? r)}
                         (:message r)   (assoc :message   (:message r))
                         (:expected r)  (assoc :expected  (pr-str (:expected r)))
                         (:actual r)    (assoc :actual    (pr-str (:actual r)))
                         (:exception r) (assoc :exception (:exception r))
                         (:skipped? r)  (assoc :skipped?  (:skipped? r))))
                     (:results state []))]
      {:status      status
       :step-idx    (:step-idx state)
       :total       (:total state)
       :failures    (:failures state)
       :name        (:name state)
       :started-ms  (:started-ms state)
       :finished-ms (:finished-ms state)
       :results     results})))

;; ---- CLJS-only: install the Playwright-readable global ------------------

#?(:cljs
   (defn- ->js
     "Convert a Clojure value to a Playwright-friendly JS value.
     Keywords become their fully-qualified string form so the JSON
     round-trip preserves identity. Sorted to keep output stable."
     [v]
     (cond
       (keyword? v) (if-let [ns (namespace v)]
                      (str ns "/" (name v))
                      (name v))
       (map? v)     (let [obj (js-obj)]
                      (doseq [[k val] v]
                        (aset obj (cond
                                    (keyword? k) (->js k)
                                    (string? k)  k
                                    :else        (str k))
                              (->js val)))
                      obj)
       (vector? v)  (clj->js (mapv ->js v))
       (seq? v)     (clj->js (mapv ->js v))
       (set? v)     (clj->js (mapv ->js v))
       :else        v)))

#?(:cljs
   (defn- list-variants-js
     "Return the JS array of variant ids (as fully-qualified strings)
     with `:script` bodies. Used by the Playwright runner via
     `window.__rf2_story_ci.listVariants()`."
     []
     (->js (variants-with-play-scripts))))

#?(:cljs
   (defn- ci-context-js
     "Return the JS catalogue object for the Playwright runner."
     []
     (->js (ci-context))))

#?(:cljs
   (defn- ->variant-id
     "Coerce a `variant-id-str` of the form `\"story.foo/bar\"` back into
     the keyword `:story.foo/bar`."
     [variant-id-str]
     (let [s (str variant-id-str)
           slash (.indexOf s "/")]
       (if (pos? slash)
         (keyword (subs s 0 slash) (subs s (inc slash)))
         (keyword s)))))

#?(:cljs
   (defn- read-run-state-js
     "Read the current `:rf.story.play/run-state` slot for `variant-id`
     (passed as a fully-qualified string) and return the projected
     shape as JSON-safe JS. Returns nil when no run has been started.

     This reads the LATEST run-state for the variant — whichever play
     was most recently driven. CI runners that want a specific play's
     outcome should use `readPlayRunState`."
     [variant-id-str]
     (let [vid   (->variant-id variant-id-str)
           state (rf.story.play.runner-events/current-state vid)]
       (->js (project-state state)))))

#?(:cljs
   (defn- read-play-run-state-js
     "Read the per-(variant, play-key) run state (multi-play).
     `play-key-str` is the play's `:name` string, or null/empty for the
     single-script `:script` slot. Returns the projected JSON-safe
     shape, or nil when no run has been started for that play."
     [variant-id-str play-key-str]
     (let [vid (->variant-id variant-id-str)
           pk  (when (and play-key-str
                          (not= "" play-key-str))
                 (str play-key-str))
           state (rf.story.play.runner-events/current-state-for-play vid pk)]
       (->js (project-state state)))))

#?(:cljs
   (defn- run-play-js
     "Trigger a run for `(variant-id, play-key)` (multi-play).
     Used by the CI runner when the auto-run default doesn't fire the
     intended play (e.g. the second play of a multi-play variant whose
     `:auto-run?` defaults to false).

     The same fresh 'Run play' the toolbar dropdown offers:
     the one run owner resets the variant to its declared
     start and runs that play (`rf.story.runtime/rerun!`). The reset clears
     the OTHER plays' run-states, so read each play's state right after
     triggering it — as the CI runner does."
     [variant-id-str play-key-str]
     (let [vid (->variant-id variant-id-str)
           pk  (when (and play-key-str
                          (not= "" play-key-str))
                 (str play-key-str))]
       (rf.story.runtime/rerun! vid {:play pk})
       nil)))

#?(:cljs
   (defn install-ci-hooks!
     "Install the `window.__rf2_story_ci` global the Playwright runner
     uses to enumerate variants and read terminal run states. Safe to
     call multiple times — re-install replaces the existing slot.

     Idempotent + side-effect-only, and a no-op when
     `re-frame.story.config/enabled?` is false, so an `enabled?` false
     build drops the hook and the runtime it reaches. The hook is inert
     until polled, so a testbed may install it unconditionally.

     The hook object carries two per-play entry points (multi-play):
     - `readPlayRunState(variantId, playKey)` — per-play state read.
     - `runPlay(variantId, playKey)` — trigger a run for a specific
       play (used by the CI runner for non-auto-run plays)."
     []
     (when rf.story.config/enabled?
       (aset js/window "__rf2_story_ci"
             (js-obj
               "listVariants"     list-variants-js
               "ciContext"        ci-context-js
               "readRunState"     read-run-state-js
               "readPlayRunState" read-play-run-state-js
               "runPlay"          run-play-js)))
     nil))
