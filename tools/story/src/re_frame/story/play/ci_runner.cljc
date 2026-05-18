(ns re-frame.story.play.ci-runner
  "CI-as-test discovery + driver glue for Story's `:play-script` slot
  (rf2-3qcxk).

  The companion to `re-frame.story.play.runner` (pure step state
  machine) and `re-frame.story.play.runner-events` (re-frame-side
  driver). This namespace exposes the seams a CI runner needs:

  - `variants-with-play-scripts` — pure discovery: scan the Story
    registrar's variant side-table and return the variant ids whose
    body carries a non-empty `:play-script`. Pure data → data; works
    on JVM and CLJS.

  - `play-script-summary` — pure: derive the small metadata bundle
    a CI runner needs per variant (id, name, step count). No
    side-effects.

  - `ci-context` — Playwright-readable JSON envelope: the full per-
    variant catalogue, ready for `JSON.parse` on the browser side.

  - `install-ci-hooks!` (CLJS) — install `window.__rf2_story_ci`
    helpers so a Playwright runner can: (a) enumerate variants with
    `:play-script`, and (b) read each variant's terminal run-state
    (`runner-events/current-state`) once the auto-run completes.
    No-op on JVM. The hook is INERT until called explicitly from a
    testbed init path — production / non-CI bundles never hit it.

  ## Why a separate ns

  - The `runner-events` ns mixes pure step execution with `re-frame`
    integration; the CI surface only needs the registrar query +
    `current-state` read. Splitting keeps the runner-events ns clear
    of CI-only ceremony (the `js/window` global) and lets unit tests
    cover the discovery seam without standing up a full Story shell.

  - The discovery seam is symmetric across JVM / CLJS (the registrar
    is `.cljc`), so the same function powers both `clojure -M:test`
    coverage and the browser-side enumeration.

  ## Pure / impure split

  This ns is `.cljc`. The pure seams (`variants-with-play-scripts`,
  `play-script-summary`, `ci-context`) are JVM-runnable; the
  CLJS-only `install-ci-hooks!` is gated by reader conditionals."
  (:require [re-frame.story.play.runner :as runner]
            [re-frame.story.registrar   :as registrar]
            #?(:cljs [re-frame.story.play.runner-events :as runner-events])))

;; ---- pure discovery ------------------------------------------------------

(defn has-play-script?
  "True iff `variant-body` carries a non-empty `:play-script` body.
  Pure data → data. A bare `:play-script` vector is enough; a map
  form is also accepted (the runner's `parse-spec` normalises both
  shapes, but discovery treats an empty vector / map without
  `:script` as 'no script' so the CI runner skips it).

  Empty map (`{}`) and empty vector (`[]`) are treated as absent —
  authors that want to opt-in must declare at least one step."
  [variant-body]
  (let [body (:play-script variant-body)]
    (cond
      (nil? body)    false
      (vector? body) (pos? (count body))
      (map? body)    (pos? (count (:script body [])))
      :else          false)))

(defn has-plays?
  "True iff `variant-body` carries a non-empty `:plays` vector.
  rf2-tl7zk multi-play. Pure data → data."
  [variant-body]
  (let [plays (:plays variant-body)]
    (boolean (and (vector? plays) (pos? (count plays))))))

(defn has-any-play?
  "True iff `variant-body` carries EITHER a non-empty `:play-script`
  or a non-empty `:plays` vector. rf2-tl7zk multi-play."
  [variant-body]
  (or (has-play-script? variant-body)
      (has-plays? variant-body)))

(defn variants-with-play-scripts
  "Return a SORTED vector of variant ids whose body carries a non-empty
  `:play-script` or `:plays` slot. Pure data → data; reads the Story
  registrar's variant side-table.

  Sorted so the CI runner walks variants in a deterministic order —
  helpful when comparing run logs across runs / branches.

  rf2-tl7zk: the name kept its `play-scripts` plural for back-compat;
  it now includes `:plays`-carrying variants too. The CI runner uses
  `ci-rows` to enumerate per-PLAY rows."
  ([]
   (variants-with-play-scripts (registrar/registrations :variant)))
  ([variant-registrations]
   (->> variant-registrations
        (filter (fn [[_ body]] (has-any-play? body)))
        (map first)
        (sort)
        (vec))))

(defn play-script-summary
  "Return a small metadata map for `variant-id` suitable for the CI
  runner's per-variant report row. Pure data → data.

  Shape:
      {:variant-id <kw>
       :name       <string or nil>     ; from `:play-script` `:name`
       :script-len <int>               ; number of steps (post-coerce)
       :auto-run?  <bool>}

  rf2-tl7zk: for variants carrying `:plays` (multi-play), the summary
  reports the FIRST play (the toolbar's default selection). Use
  `ci-rows` for the per-play enumeration."
  [variant-id]
  (let [body  (registrar/handler-meta :variant variant-id)
        plays (runner/variant-body->plays body)
        spec  (or (first plays)
                  ;; Defensive: no play surface registered — return the
                  ;; empty-script shape (mirrors the historical contract).
                  (runner/parse-spec nil))]
    {:variant-id variant-id
     :name       (:name spec)
     :script-len (count (:script spec))
     :auto-run?  (boolean (:auto-run? spec))}))

(defn ci-rows
  "rf2-tl7zk multi-play: enumerate the CI runner's per-row catalogue.
  Each row is one play; a variant with `:plays` of size N yields N
  rows; a single-script `:play-script` variant yields ONE row.

  Pure data → data.

  Shape (one entry per row):
      {:variant-id <kw>
       :play-key   <string or nil>     ; play's :name (nil for legacy)
       :name       <string or nil>     ; same as :play-key, for parity
       :script-len <int>
       :auto-run?  <bool>}

  The CI runner uses this to drive one Playwright assertion per row.
  Stable order: variants sorted alphabetically, plays in declaration
  order within each variant."
  ([]
   (ci-rows (registrar/registrations :variant)))
  ([variant-registrations]
   (let [vids (variants-with-play-scripts variant-registrations)]
     (vec
       (mapcat
         (fn [vid]
           (let [body  (get variant-registrations vid)
                 plays (runner/variant-body->plays body)]
             (map (fn [spec]
                    {:variant-id vid
                     :play-key   (:name spec)
                     :name       (:name spec)
                     :script-len (count (:script spec))
                     :auto-run?  (boolean (:auto-run? spec))})
                  plays)))
         vids)))))

(defn ci-context
  "Build the full CI catalogue: every variant with a play surface
  paired with its summary metadata. Pure data → data. Used by the
  Playwright runner via `install-ci-hooks!`.

  rf2-tl7zk multi-play: adds `:rows` — the per-play enumeration the
  multi-play runner uses to drive its per-play assertions. The legacy
  `:summaries` keeps the per-VARIANT shape (first play of multi-play)
  for back-compat with consumers that haven't migrated."
  []
  (let [vids (variants-with-play-scripts)]
    {:variants  vids
     :summaries (mapv play-script-summary vids)
     :rows      (ci-rows)}))

;; ---- terminal-state helpers ---------------------------------------------

(defn terminal?
  "True iff `state` represents a run that has reached `:pass` or
  `:fail`. Pure data → data."
  [state]
  (boolean (#{:pass :fail} (:status state))))

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
     with `:play-script` bodies. Used by the Playwright runner via
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

     rf2-tl7zk: this reads the LATEST run-state for the variant —
     whichever play was most recently driven. CI runners that want a
     specific play's outcome should use `readPlayRunState`."
     [variant-id-str]
     (let [vid   (->variant-id variant-id-str)
           state (runner-events/current-state vid)]
       (->js (project-state state)))))

#?(:cljs
   (defn- read-play-run-state-js
     "rf2-tl7zk multi-play: read the per-(variant, play-key) run state.
     `play-key-str` is the play's `:name` string, or null/empty for the
     single-script `:play-script` slot. Returns the projected JSON-safe
     shape, or nil when no run has been started for that play."
     [variant-id-str play-key-str]
     (let [vid (->variant-id variant-id-str)
           pk  (when (and play-key-str
                          (not= "" play-key-str))
                 (str play-key-str))
           state (runner-events/current-state-for-play vid pk)]
       (->js (project-state state)))))

#?(:cljs
   (defn- run-play-js
     "rf2-tl7zk multi-play: trigger a run for `(variant-id, play-key)`.
     Used by the CI runner when the auto-run default doesn't fire the
     intended play (e.g. the second play of a multi-play variant whose
     `:auto-run?` defaults to false)."
     [variant-id-str play-key-str]
     (let [vid (->variant-id variant-id-str)
           pk  (when (and play-key-str
                          (not= "" play-key-str))
                 (str play-key-str))]
       (runner-events/run-play! vid pk)
       nil)))

#?(:cljs
   (defn install-ci-hooks!
     "Install the `window.__rf2_story_ci` global the Playwright runner
     uses to enumerate variants and read terminal run states. Safe to
     call multiple times — re-install replaces the existing slot.

     Idempotent + side-effect-only: callers are expected to gate this
     on a CI-only init path (e.g. a testbed's `core/run` checks
     `window.location.search` for a `ci=1` flag, or simply always
     install when re-frame.story.config/enabled? is true since the
     hook is inert until polled).

     rf2-tl7zk multi-play: the hook object grows two new entry points:
     - `readPlayRunState(variantId, playKey)` — per-play state read.
     - `runPlay(variantId, playKey)` — trigger a run for a specific
       play (used by the CI runner for non-auto-run plays)."
     []
     (let [hooks (js-obj
                   "listVariants"     list-variants-js
                   "ciContext"        ci-context-js
                   "readRunState"     read-run-state-js
                   "readPlayRunState" read-play-run-state-js
                   "runPlay"          run-play-js)]
       (aset js/window "__rf2_story_ci" hooks)
       nil)))
