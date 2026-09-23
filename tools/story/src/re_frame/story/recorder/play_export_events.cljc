(ns re-frame.story.recorder.play-export-events
  "re-frame integration seam for the recorder → :script export
  flow. Owns the side-effecty entry points the export dialog UI
  reaches for:

  - **Replay the generated script fresh** — run the just-exported
    `:script` on the variant reset to its declared start, so the user can
    verify the export is valid before pasting it into source.
  - **Capture the live app-db snapshot** — read the variant frame's
    db at export time. Used by the auto-assert option to derive
    trailing `[:assert-db ...]` steps.
  - **Copy the rendered snippet to the clipboard** — delegated to
    `re-frame.story.review-dialog/copy-to-clipboard!` so the export
    flow doesn't carry its own clipboard shim.

  The translator itself (`re-frame.story.recorder.play-export`) stays
  pure. This namespace is the thin impure shell — it depends on
  `re-frame.core` (for `app-db-value`) and the runtime's one run owner
  (for `rerun!`), neither of which the translator should pull in.

  ## Pure / impure split

  - `recording->script-body`  — pure (`play-export` ns).
  - `replay-script!`          — impure (this ns; drives the runner).
  - `snapshot-frame-db`       — impure (this ns; reads frame db).

  ## Elision

  Every public fn opens with `(when rf.story.config/enabled? ...)` so
  production CLJS builds short-circuit cleanly. The translator stays
  reachable (it's pure data → data) but the side-effecty seams
  collapse."
  (:require [re-frame.core                        :as rf]
            [re-frame.story.async                 :as rf.story.async]
            [re-frame.story.config                :as rf.story.config]
            [re-frame.story.play.runner-events    :as rf.story.play.runner-events]
            [re-frame.story.recorder.play-export  :as rf.story.recorder.play-export]
            [re-frame.story.runtime               :as rf.story.runtime]))

;; ---------------------------------------------------------------------------
;; Impure: app-db-value snapshot
;; ---------------------------------------------------------------------------

(defn snapshot-frame-db
  "Read the current app-db for `frame-id` via the framework registrar.
  Returns nil when the frame is gone or the read throws (tolerant
  per runner-events convention)."
  [frame-id]
  (try
    (rf/app-db-value frame-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Impure: replay the exported script
;;
;; The dialog's 'replay in this story' button feeds the generated
;; spec into the runner so the user can verify it without leaving
;; the recorder loop. The run is asynchronous; the optional `done-cb`
;; is invoked with the terminal run-state once it settles
;; (synchronous on JVM, async on CLJS).
;; ---------------------------------------------------------------------------

(defn replay-script!
  "Replay `spec` (a `:script` map per `runner/parse-spec`) as a FRESH run
  of `frame-id`'s variant (rf2-3x7nj.29.4): the one run owner resets the
  frame in place to its declared start — `:setup` state, the run's epoch
  baseline re-stamped — and runs `spec`'s concrete steps
  (`rf.story.runtime/rerun!` with `{:spec spec}`). That is what the pasted
  form does when it runs as a variant, so the verdict is the pasted form's,
  never one graded against the recording's end state.

  `done-cb` fires once with the replay's terminal run-state (keyed by the
  spec's `:name`), or `{:status s}` carrying the run's unified status when
  the run settled before the play could start (a refused or superseded
  run). Returns the run's promise of the unified result.

  No-op (returns nil) when production elision is active or
  `frame-id` is nil."
  ([frame-id spec]
   (replay-script! frame-id spec nil))
  ([frame-id spec done-cb]
   (when (and rf.story.config/enabled? frame-id spec)
     (some-> (rf.story.runtime/rerun! frame-id {:spec spec})
             (rf.story.async/then
               (fn [result]
                 (when done-cb
                   (done-cb (or (rf.story.play.runner-events/current-state-for-play
                                  frame-id (:name spec))
                                {:status (:status result)})))
                 result))))))

;; ---------------------------------------------------------------------------
;; Pure: export from a recorder snapshot
;;
;; A convenience seam the dialog UI calls — takes the dialog's
;; snapshot map (`{:events ... :source-id ...}` per
;; `recorder/open-dialog`) plus the user-supplied options
;; (`:name :auto-assert?` …) and yields the canonical export tuple
;; `{:spec ...play-script... :rendered <string>}`.
;;
;; The app-db-value snapshot is the caller's job (so the pure path stays
;; pure) — the dialog calls `snapshot-frame-db` itself when
;; `:auto-assert?` is on, then threads the result into `:final-db`.
;; ---------------------------------------------------------------------------

(defn build-export
  "Build the canonical export tuple for the dialog. `opts` accepts the
  full surface of `play-export/recording->script-body` plus an
  optional `:variant-id` for the rendered `(reg-variant ...)` form.

  Returns `{:spec <play-script map> :rendered <variant-form string>}`.
  The `:rendered` slot is what the dialog displays + the clipboard
  receives."
  [events {:keys [variant-id extends alias name auto-run?
                  auto-assert? final-db seed-db max-auto-assertions]
           :as   opts}]
  (let [spec     (rf.story.recorder.play-export/recording->script-body
                   events
                   (cond-> {}
                     (some? name)                (assoc :name name)
                     (some? auto-run?)           (assoc :auto-run? auto-run?)
                     (some? auto-assert?)        (assoc :auto-assert? auto-assert?)
                     (some? final-db)            (assoc :final-db final-db)
                     (some? seed-db)             (assoc :seed-db seed-db)
                     (some? max-auto-assertions) (assoc :max-auto-assertions
                                                        max-auto-assertions)))
        rendered (rf.story.recorder.play-export/render-variant-form
                   spec
                   (cond-> {}
                     variant-id (assoc :variant-id variant-id)
                     extends    (assoc :extends    extends)
                     alias      (assoc :alias      alias)))]
    {:spec spec :rendered rendered}))
