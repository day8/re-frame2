(ns re-frame.story.recorder.play-export-events
  "re-frame integration seam for the recorder → :play-script export
  flow (rf2-x9zsr). Owns the side-effecty entry points the export
  dialog UI reaches for:

  - **Replay the generated script in-place** — dispatch the just-
    -exported `:play-script` through the runner so the user can
    verify the export is valid before pasting it into source.
  - **Capture the live app-db snapshot** — read the variant frame's
    db at export time. Used by the auto-assert option to derive
    trailing `[:assert-db ...]` steps.
  - **Copy the rendered snippet to the clipboard** — delegated to
    `re-frame.story.review-dialog/copy-to-clipboard!` so the export
    flow doesn't carry its own clipboard shim.

  The translator itself (`re-frame.story.recorder.play-export`) stays
  pure. This namespace is the thin impure shell — it depends on
  `re-frame.core` (for `get-frame-db` / `dispatch-sync*`) and the
  runner-events ns (for `run!`), neither of which the translator
  should pull in.

  ## Pure / impure split

  - `recording->play-script`  — pure (`play-export` ns).
  - `replay-script!`          — impure (this ns; drives the runner).
  - `snapshot-frame-db`       — impure (this ns; reads frame db).

  ## Elision

  Every public fn opens with `(when config/enabled? ...)` so
  production CLJS builds short-circuit cleanly. The translator stays
  reachable (it's pure data → data) but the side-effecty seams
  collapse."
  (:require [re-frame.core                        :as rf]
            [re-frame.story.config                :as config]
            [re-frame.story.play.runner-events    :as runner-events]
            [re-frame.story.recorder.play-export  :as export]))

;; ---------------------------------------------------------------------------
;; Impure: frame-db snapshot
;; ---------------------------------------------------------------------------

(defn snapshot-frame-db
  "Read the current app-db for `frame-id` via the framework registrar.
  Returns nil when the frame is gone or the read throws (tolerant
  per runner-events convention)."
  [frame-id]
  (try
    (rf/get-frame-db frame-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Impure: replay the exported script
;;
;; The dialog's 'replay in this story' button feeds the generated
;; spec into the runner so the user can verify it without leaving
;; the recorder loop. The runner drives an asynchronous loop; the
;; optional `done-cb` is invoked with the terminal run-state once
;; every step has completed (synchronous on JVM, async on CLJS).
;; ---------------------------------------------------------------------------

(defn replay-script!
  "Drive `spec` (a `:play-script` map per `runner/parse-spec`) against
  `frame-id` via `runner-events/run!`. Returns the initial run-state
  (status `:running`, step-idx 0). `done-cb` fires with the terminal
  run-state.

  Idempotent against concurrent runs — `run!` resets the per-frame
  run-state slot on every entry (cancels the previous run's
  callback).

  No-op (returns nil) when production elision is active or
  `frame-id` is nil."
  ([frame-id spec]
   (replay-script! frame-id spec nil))
  ([frame-id spec done-cb]
   (when (and config/enabled? frame-id spec)
     (runner-events/run! frame-id spec done-cb))))

;; ---------------------------------------------------------------------------
;; Pure: export from a recorder snapshot
;;
;; A convenience seam the dialog UI calls — takes the dialog's
;; snapshot map (`{:events ... :source-id ...}` per
;; `recorder/open-dialog`) plus the user-supplied options
;; (`:name :auto-assert?` …) and yields the canonical export tuple
;; `{:spec ...play-script... :rendered <string>}`.
;;
;; The frame-db snapshot is the caller's job (so the pure path stays
;; pure) — the dialog calls `snapshot-frame-db` itself when
;; `:auto-assert?` is on, then threads the result into `:final-db`.
;; ---------------------------------------------------------------------------

(defn build-export
  "Build the canonical export tuple for the dialog. `opts` accepts the
  full surface of `play-export/recording->play-script` plus an
  optional `:variant-id` for the rendered `(reg-variant ...)` form.

  Returns `{:spec <play-script map> :rendered <variant-form string>}`.
  The `:rendered` slot is what the dialog displays + the clipboard
  receives."
  [events {:keys [variant-id extends alias name auto-run?
                  auto-assert? final-db seed-db max-auto-assertions]
           :as   opts}]
  (let [spec     (export/recording->play-script
                   events
                   (cond-> {}
                     (some? name)                (assoc :name name)
                     (some? auto-run?)           (assoc :auto-run? auto-run?)
                     (some? auto-assert?)        (assoc :auto-assert? auto-assert?)
                     (some? final-db)            (assoc :final-db final-db)
                     (some? seed-db)             (assoc :seed-db seed-db)
                     (some? max-auto-assertions) (assoc :max-auto-assertions
                                                        max-auto-assertions)))
        rendered (export/render-variant-form
                   spec
                   (cond-> {}
                     variant-id (assoc :variant-id variant-id)
                     extends    (assoc :extends    extends)
                     alias      (assoc :alias      alias)))]
    {:spec spec :rendered rendered}))
