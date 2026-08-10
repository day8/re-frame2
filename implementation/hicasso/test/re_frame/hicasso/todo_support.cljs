(ns re-frame.hicasso.todo-support
  "THE SHARED TO-DO FRAME the ported `arm1/*` witnesses read from.

  A boundary that reads nothing proves nothing about the read, so the
  witnesses for `h/hframe`, the one callback form and the minted-key
  warning all need a frame with rows in it. Each of them needs the same
  three things — a seeded frame, a narrow per-row write, and a
  per-row subscription that dirties when that write lands — so they are
  written once here rather than four times inline.

  ## Why this is not the bench tree's `front.dogfood`

  The suites that became these witnesses read
  `re-frame.bench.hicasso.front.dogfood`, and this namespace is
  deliberately NOT that file moved. Two reasons, and both are about the
  boundary between the package and the benchmark rather than about
  taste.

  1. **`front.dogfood` is a benchmark artefact.** Its subject is HD-002's
     three renderings and the ergonomics comparison they feed — the
     filter, the reorder, the per-instance drafts and the commit/cancel
     reset law exist to make that comparison measurable. The package
     asserts none of it, and a support namespace that carries a
     benchmark's design rationale into `implementation/hicasso/test/`
     invites the next reader to maintain it against a screen that lives
     somewhere else.
  2. **The ids would collide.** `implementation/shadow-cljs.edn` puts
     `freehand/test` and `hicasso/test` on ONE `:node-test`
     `:source-paths`, so both trees register into the same global
     handler registry in the same build. A second `(rf/reg-event
     :dogfood/toggle …)` would silently redefine the benchmark's, and
     whichever namespace loaded last would decide what the other one
     measured. The `:hicasso.todo/*` prefix here cannot reach it.

  So this is the SLICE the package's own witnesses read — one seed, one
  toggle, one `done?` — under ids the package owns."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The app-db shape
;; ---------------------------------------------------------------------------

(defn seed-db
  "`n` to-dos, none done. The one place the shape is written out; every
  assertion about the shape reads it from here."
  [n]
  {:todos (into {} (map (fn [i] [i {:id i :title (str "todo " i) :done? false}])) (range n))
   :order (vec (range n))})

;; ---------------------------------------------------------------------------
;; The narrow read and the narrow write
;; ---------------------------------------------------------------------------

(rf/reg-sub :hicasso.todo/done? (fn [db [_ id]] (get-in db [:todos id :done?])))

(rf/reg-event :hicasso.todo/seed (fn [_ [_ n]] {:db (seed-db n)}))

(rf/reg-event :hicasso.todo/toggle
  (fn [{:keys [db]} [_ id]] {:db (update-in db [:todos id :done?] not)}))

;; ---------------------------------------------------------------------------
;; The frame lifecycle
;; ---------------------------------------------------------------------------

(defn make-frame!
  "Create (or idempotently replace) `frame-id`'s frame, seeded with `n`
  to-dos. Runs outside any measured window."
  [frame-id n]
  (rf/make-frame {:id frame-id :initial-events [[:hicasso.todo/seed n]]})
  frame-id)

(defn reseed!
  "Return the frame to its seeded state, so one witness never reads a
  page a previous one already mutated."
  [frame-id n]
  (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.todo/seed n]))
  nil)
