(ns re-frame.story.invariants
  "Invariant sentinels + the first-bad-epoch utility (NewTestStory
  rf2-5x1wt.5 + rf2-5x1wt.6, spec/017-Testing-Story.md §Invariant
  sentinels).

  ## What an invariant is

  An invariant is a predicate that MUST hold after every committed
  epoch. Spec/017 §Run result makes the `:rf/epoch-record` the single
  evidence atom; an invariant reads that record (its settled
  `:db-after`, its `:trigger-event`, its `:trace-events`) and answers
  one question — \"is the world still well-formed here?\".

  Two surfaces share this one notion of an invariant, so they share one
  namespace:

  - `with-invariants` — the live SENTINEL fixture (rf2-5x1wt.5). It
    registers an epoch listener per invariant before `body` runs, checks
    each invariant after every committed epoch, and reports failures
    through `clojure.test` / `cljs.test`. It NEVER throws from the
    listener (§Never throw): a broken predicate, or a violated one,
    reports and the run continues.
  - `first-bad-epoch` — the pure POST-HOC utility (rf2-5x1wt.6). Given a
    retained epoch tape and an invariant, it returns the first epoch
    where the invariant fails (enriched with the trigger event, the
    db-diff, and the trace events), or `nil` when the invariant holds
    across the whole tape. Both surfaces share `check-epoch` (the one
    per-epoch evaluation primitive), so a violation the live sentinel
    reports and the violation `first-bad-epoch` returns are computed
    identically — the live and post-hoc surfaces agree by construction.

  ## Invariant shape

  An invariant is normalized by `coerce-invariant` into a map:

      {:id    keyword-or-string   ; report label; defaults to an ordinal
       :check (fn [epoch] truthy?)} ; the predicate over an :rf/epoch-record

  Authors MAY pass any of:

  - a bare 1-arg fn `(fn [epoch] …)` — the whole epoch record is the
    argument, so a predicate can read `:db-after`, `:trigger-event`, …;
  - a map carrying an explicit `:check` fn (and an optional `:id`);
  - a `[:db path pred]` / `[:db path = expected]` data shorthand for the
    common \"a value at an app-db path satisfies a predicate\" case, so
    the most frequent invariant needs no fn literal and stays
    data-shaped (matching the Story authoring posture, spec/017
    §Story variant).

  ## Diagnostics

  Each violation carries the spec/017 §A1 diagnostic spine where the
  epoch record provides it — frame id, epoch id, the triggering event,
  the path / expected / actual for a `:db`-path invariant, the predicate
  source where the author supplied an `:id`, and the predicate exception
  (isolated) when the check threw. The same `violation` map feeds both
  the live `do-report` and the `first-bad-epoch` return value.

  ## Pure / JVM-testable

  Every fn here is `.cljc` and PURE: epoch records (data) in, violation
  maps (data) out. `first-bad-epoch`, `coerce-invariant`, `check-epoch`,
  and the report-projection run under `clojure -M:test` with no runtime.
  Only `with-invariants` touches the live epoch-listener registry, and
  that registration is the thin orchestration wrapper around the pure
  core."
  #?(:cljs (:require-macros [re-frame.story.invariants]))
  ;; rf2-rfid2x — the invariant sentinel attaches to the assembled `:epoch`
  ;; stream via the epoch HOME verb (`re-frame.epoch/register-epoch-listener!`),
  ;; NOT the `rf/register-listener! :epoch` facade. Per the Tool-Pair
  ;; §"Facade vs home-namespace verb — the DCE tier rule": canonical devtools
  ;; attach via the home-namespace verbs so a tool preload never drags
  ;; `re-frame.core` into a production build. The home verb is a HARD dep on
  ;; `day8/re-frame2-epoch` (declared in this artefact's deps.edn) — an
  ;; invariant sentinel is meaningless without the epoch stream it checks after
  ;; every committed epoch, so the hard dep (fail-loud when absent) is correct
  ;; where the facade's silent no-op-when-absent was a false-green footgun.
  (:require [re-frame.epoch :as epoch]
            [re-frame.error :as error]
            #?(:clj  [clojure.test :as ctest]
               :cljs [cljs.test :as ctest :include-macros true])))

;; ===========================================================================
;; INVARIANT NORMALIZATION
;; ===========================================================================
;;
;; The author surface accepts a bare predicate, an explicit `:check` map,
;; or a `[:db path …]` data shorthand. `coerce-invariant` lowers all three
;; into the one `{:id … :check …}` shape the checker consumes, so the rest
;; of the namespace reasons about a single form. `idx` is the 0-based
;; position in the author's invariant vector, used for the default `:id`
;; when the author supplied none — a stable, distinct label.

(defn- db-path-check
  "Build the `:check` fn for a `[:db path & rest]` shorthand. `rest` is
  either `[pred]` (a 1-arg predicate over the value at `path`) or
  `[= expected]` (equality against a literal). The returned fn carries
  the resolved `:path` / `:expected` / `:actual` onto the epoch via the
  invariant's diagnostic projection (`check-epoch` reads them back). Pure
  data → fn; the fn itself is pure over an epoch record."
  [path rst]
  (let [[expected pred]
        (cond
          ;; [:db path = expected]  → equality shorthand
          (and (= 2 (count rst)) (= = (first rst)))
          [(second rst) #(= (second rst) %)]
          ;; [:db path pred]        → predicate shorthand
          (= 1 (count rst))
          [::no-expected (first rst)]
          :else
          (error/throw-error!
            :rf.error/story-bad-invariant
            'rf.story/with-invariants
            (str "re-frame2-story :db invariant shorthand must be "
                 "[:db path pred] or [:db path = expected]; correct the "
                 "shorthand to one of those forms.")
            {:recovery :use-a-valid-db-invariant-shorthand
             :extra    {:shorthand (into [:db path] rst)}}))]
    (fn db-path-invariant [epoch]
      (let [actual (get-in (:db-after epoch) path)]
        {:ok?      (boolean (pred actual))
         :path     path
         :actual   actual
         :expected (when (not= ::no-expected expected) expected)}))))

(defn coerce-invariant
  "Normalize one authored invariant into `{:id … :check …}`. `idx` is the
  invariant's 0-based position, used for the default `:id`. Pure.

  `:check` is a fn `(fn [epoch] …)` that returns either a boolean (the
  invariant holds / is violated) or a map `{:ok? boolean & diagnostics}`
  carrying extra report slots (`:path` / `:expected` / `:actual`). The
  map form is what the `[:db …]` shorthand emits; a bare-fn / `:check`-fn
  author returns a boolean and the checker fills the spine from the epoch
  record alone."
  [idx invariant]
  (cond
    ;; A bare predicate over the epoch record.
    (fn? invariant)
    {:id (keyword (str "invariant-" idx)) :check invariant}

    ;; The `[:db path …]` data shorthand.
    (and (vector? invariant) (= :db (first invariant)))
    {:id    (keyword (str "invariant-" idx))
     :check (db-path-check (second invariant) (vec (drop 2 invariant)))}

    ;; An explicit map; default the id from the ordinal.
    (map? invariant)
    (let [check (or (:check invariant) (:pred invariant))]
      (when-not (fn? check)
        (error/throw-error!
          :rf.error/story-bad-invariant
          'rf.story/with-invariants
          (str "re-frame2-story invariant map needs a `:check` (or `:pred`) "
               "fn; add a `:check` fn (epoch → boolean / {:ok? …}) to the "
               "invariant map.")
          {:recovery :add-a-check-fn
           :extra    {:invariant invariant}}))
      (merge {:id (keyword (str "invariant-" idx))} invariant {:check check}))

    :else
    (error/throw-error!
      :rf.error/story-bad-invariant
      'rf.story/with-invariants
      (str "re-frame2-story invariant must be a fn, a `[:db path …]` vector, "
           "or a map; pass one of those shapes as an invariant spec.")
      {:recovery :use-a-fn-vector-or-map-invariant
       :extra    {:invariant invariant}})))

(defn coerce-invariants
  "Normalize a vector of authored invariants. Pure."
  [invariants]
  (into [] (map-indexed coerce-invariant) invariants))

;; ===========================================================================
;; THE EPOCH CHECK  (one invariant × one epoch → a result)
;; ===========================================================================
;;
;; `check-epoch` is the single evaluation primitive both surfaces use. It
;; runs the (already-normalized) invariant's `:check` against ONE epoch
;; record and returns a result map. It NEVER throws (§Never throw): a
;; predicate that explodes is caught and reported as a violation carrying
;; `:error`, so a broken invariant fails the test rather than the run.

(defn- diagnostic-spine
  "The spec/017 §A1 diagnostic spine read from the epoch record alone —
  frame id, epoch id, and the triggering event. The path / expected /
  actual slots come from the check's return map (the `[:db …]` shorthand)
  and are merged on top. Pure data → data."
  [{:keys [frame epoch-id event-id trigger-event] :as _epoch}]
  (cond-> {}
    (some? frame)         (assoc :frame frame)
    (some? epoch-id)      (assoc :epoch-id epoch-id)
    (some? event-id)      (assoc :event event-id)
    (some? trigger-event) (assoc :trigger-event trigger-event)))

(defn check-epoch
  "Evaluate one normalized `invariant` against one `epoch` record. Returns
  `nil` when the invariant HOLDS, or a violation map when it is violated
  (or the predicate threw). Pure / never-throws.

  A violation map carries the invariant `:id`, the diagnostic spine from
  the epoch (`:frame` / `:epoch-id` / `:event` / `:trigger-event`), any
  `:path` / `:expected` / `:actual` the check returned, and — when the
  predicate threw — the isolated `:error` message (the predicate
  exception never escapes; a broken invariant is itself a violation)."
  [{:keys [id check] :as _invariant} epoch]
  (let [result (try
                 (check epoch)
                 (catch #?(:clj Throwable :cljs :default) e
                   {:ok?   false
                    :error #?(:clj  (.getMessage ^Throwable e)
                              :cljs (str e))}))
        ;; A bare-fn check returns a boolean; the `[:db …]` shorthand and
        ;; the catch arm return a `{:ok? …}` map with extra diagnostics.
        {:keys [ok?] :as detail} (if (map? result) result {:ok? (boolean result)})]
    (when-not ok?
      (-> (diagnostic-spine epoch)
          (assoc :invariant id)
          (merge (dissoc detail :ok?))))))

(defn check-all
  "Evaluate every normalized `invariant` against one `epoch`. Returns the
  vector of violations (empty when every invariant holds). Pure /
  never-throws — `check-epoch` isolates each predicate."
  [invariants epoch]
  (into [] (keep #(check-epoch % epoch)) invariants))

;; ===========================================================================
;; FIRST-BAD-EPOCH  (pure post-hoc utility, rf2-5x1wt.6)
;; ===========================================================================
;;
;; spec/017 §First-bad-epoch / NewTestStory §A2: a pure utility over an
;; epoch tape. It walks the tape forward and returns the FIRST epoch where
;; the invariant fails, enriched with the trigger event, the db-diff, and
;; the trace events (the spec's named enrichment slots), or `nil` when the
;; invariant holds across the whole tape. `first-bad-epoch` and the live
;; `with-invariants` sentinel both evaluate through `check-epoch` (the one
;; per-epoch primitive), so the violation each surface reports is computed
;; identically — the live and post-hoc surfaces agree.

(defn- db-diff
  "A shallow before→after diff for the report: the set of top-level
  app-db keys whose value changed across the epoch. Cheap and pure —
  enough to point a reader at the mutated region without serialising two
  whole app-dbs. `nil` when neither snapshot is a map."
  [{:keys [db-before db-after] :as _epoch}]
  (when (or (map? db-before) (map? db-after))
    (let [before (or db-before {})
          after  (or db-after {})
          ks     (into #{} (concat (keys before) (keys after)))]
      (into #{}
            (filter #(not= (get before %) (get after %)))
            ks))))

(defn first-bad-epoch
  "Return the first epoch in `epoch-tape` where `invariant` fails, or
  `nil` when the invariant holds across the whole tape. Pure /
  never-throws (per spec/017 §First-bad-epoch / NewTestStory §A2).

  `invariant` is any authored shape `coerce-invariant` accepts (a bare
  predicate, a `[:db path …]` shorthand, or a `{:check …}` map). The
  returned map is the `check-epoch` violation enriched with the spec's
  named slots:

      {:invariant     id
       :epoch         the failing :rf/epoch-record (verbatim)
       :epoch-id      …            ; the diagnostic spine
       :frame         …
       :event         …
       :trigger-event the triggering event vector
       :db-diff       #{changed-top-level-keys}
       :trace-events  the epoch's raw trace stream (when retained)
       :path/:expected/:actual …}  ; from a :db-path invariant

  An empty tape returns `nil` (no epoch can fail). A failure in the FIRST
  epoch returns that epoch."
  [epoch-tape invariant]
  (let [inv (coerce-invariant 0 invariant)]
    (reduce
      (fn [_ epoch]
        (when-let [violation (check-epoch inv epoch)]
          (reduced
            (cond-> (assoc violation
                           :epoch         epoch
                           :db-diff       (db-diff epoch)
                           :trigger-event (:trigger-event epoch))
              (some? (:trace-events epoch)) (assoc :trace-events (:trace-events epoch))))))
      nil
      (or epoch-tape []))))

;; ===========================================================================
;; REPORTING  (violation → clojure.test / cljs.test, never throw)
;; ===========================================================================
;;
;; The live fixture reports each violation through `ctest/do-report` — the
;; same non-throwing channel `re-frame.test-support/assert-path-equals`
;; uses — so a violation registers a `clojure.test` failure WITHOUT
;; throwing out of the epoch listener (§Never throw). A run with no
;; violations registers one `:pass` per invariant so a green sentinel is
;; visible in the report rather than silent.

(defn violation-message
  "A one-line human-facing message for a violation. Pure / deterministic."
  [{:keys [invariant epoch-id event error path] :as _violation}]
  (str "invariant " (pr-str invariant)
       " violated"
       (when (some? epoch-id) (str " at epoch " (pr-str epoch-id)))
       (when (some? event)    (str " (event " (pr-str event) ")"))
       (when (some? path)     (str " at path " (pr-str path)))
       (when error            (str " — predicate threw: " error))))

(defn report-violation!
  "Report one violation as a `clojure.test` / `cljs.test` `:fail` via
  `do-report` — never throws. Returns the violation."
  [{:keys [expected actual] :as violation}]
  (ctest/do-report
    {:type     :fail
     :message  (violation-message violation)
     :expected expected
     :actual   actual})
  violation)

(defn report-pass!
  "Report a passing invariant as a `:pass` via `do-report` so a green
  sentinel is visible in the report. Never throws."
  [invariant-id]
  (ctest/do-report
    {:type     :pass
     :message  (str "invariant " (pr-str invariant-id) " held across all epochs")})
  nil)

;; ===========================================================================
;; LISTENER ORCHESTRATION  (the live sentinel core, rf2-5x1wt.5)
;; ===========================================================================
;;
;; `with-invariants` (below) brackets the body with one registered epoch
;; listener that checks every invariant after each committed epoch. The
;; pure core is here so the macro stays a thin wrapper and the
;; report-once / exception-isolation logic is itself testable.
;;
;; report-once policy: a violation is reported at most ONCE per
;; (invariant, frame, epoch-id) triple (spec/017 §A1 "exactly once per
;; failing epoch"). The listener threads a `seen` set of
;; `[invariant-id frame epoch-id]` tuples through an atom so a re-fire of
;; the same record (a back-filled render re-notify, rf2-qs6dl) does not
;; double-count.
;;
;; The `:frame` component is defensive. `with-invariants` observes EVERY
;; frame's epochs, so report-once must be per-frame. It is collision-proof
;; today only incidentally — epoch-ids come from a single global counter
;; (`re-frame.epoch.state/next-epoch-id`), so `[invariant-id epoch-id]`
;; cannot collide across frames. Should epoch-ids ever become per-frame (a
;; plausible refactor — per-frame rings already exist), two frames could
;; both produce epoch-id 1, and a violation in frame B's epoch 1 would be
;; SILENTLY DROPPED under a frame-less key once frame A reported. Keying on
;; `:frame` (already on every violation via the diagnostic spine) is
;; equivalent today and robust under a per-frame-id world (rf2-ilatz).

(defn dedup-key
  "The report-once key for a violation against an epoch: the
  `[invariant-id frame epoch-id]` triple. Including `:frame` keeps
  report-once per-frame even if epoch-ids stop being globally unique
  (rf2-ilatz). Pure."
  [violation]
  [(:invariant violation) (:frame violation) (:epoch-id violation)])

(defn on-epoch!
  "The per-epoch listener step: check every `invariants` against `epoch`,
  report each not-yet-seen violation exactly once, and record the seen
  keys + every violation into `state-atom`. Never throws — `check-all`
  isolates predicate exceptions and `report-violation!` uses `do-report`.

  `state-atom` holds `{:seen #{[inv-id frame epoch-id] …} :violations [v …]}`.
  Returns the violations reported on THIS call (for testing)."
  [state-atom invariants epoch]
  (let [violations (check-all invariants epoch)
        fresh      (filterv #(not (contains? (:seen @state-atom) (dedup-key %)))
                            violations)]
    (doseq [v fresh]
      (report-violation! v)
      (swap! state-atom (fn [s]
                          (-> s
                              (update :seen conj (dedup-key v))
                              (update :violations conj v)))))
    fresh))

(defn run-with-invariants*
  "The runtime core of `with-invariants` — registers one epoch listener
  that runs `on-epoch!` after every committed epoch, calls `body-fn`,
  then on the way out (even if `body-fn` throws) unregisters the listener
  and reports a `:pass` for every invariant that recorded no violation.
  Returns `body-fn`'s value.

  `invariants` is the authored vector; it is coerced here so the macro
  passes author shapes through verbatim. The listener exception isolation
  is twofold: `re-frame.epoch/register-epoch-listener!` already catches and
  isolates listener exceptions (Spec 009 §register-epoch-listener!), AND
  `on-epoch!` itself never throws — so a broken predicate can neither
  break the runtime nor abort the body."
  [invariants body-fn]
  (let [coerced (coerce-invariants invariants)
        state   (atom {:seen #{} :violations []})
        key     (keyword "rf.story.invariants" (str (gensym "sentinel-")))]
    (epoch/register-epoch-listener! key (fn [epoch] (on-epoch! state coerced epoch)))
    (try
      (body-fn)
      (finally
        (epoch/unregister-epoch-listener! key)
        ;; Per spec/017 §A1 — a green sentinel is visible: report a
        ;; `:pass` for every invariant that recorded no violation across
        ;; the whole run.
        (let [violated (into #{} (map :invariant) (:violations @state))]
          (doseq [{:keys [id]} coerced
                  :when (not (contains? violated id))]
            (report-pass! id)))))))

;; ===========================================================================
;; with-invariants  (the public fixture macro, rf2-5x1wt.5)
;; ===========================================================================

#?(:clj
   (defmacro with-invariants
     "Run `body` with a live invariant sentinel: each invariant is checked
     after every committed epoch and violations report through
     `clojure.test` / `cljs.test` (spec/017 §Invariant sentinels).

     Shape:

         (with-invariants [invariant-spec …] body+)

     Each `invariant-spec` is a bare predicate `(fn [epoch] …)` over an
     `:rf/epoch-record`, a `[:db path pred]` / `[:db path = expected]`
     data shorthand, or a `{:id … :check (fn [epoch] …)}` map. The
     sentinel:

     - checks every invariant after EACH committed epoch (via
       `re-frame.epoch/register-epoch-listener!`);
     - reports each violation through `clojure.test` / `cljs.test` exactly
       ONCE per failing epoch;
     - NEVER throws from the epoch listener — a violated OR a broken
       (throwing) predicate reports and the run continues;
     - unregisters the listener on the way out, even if `body` throws;
     - reports a `:pass` for every invariant that held across the run, so
       a green sentinel is visible.

     Returns the value of `body`'s final form. The sentinel observes
     EVERY frame's epochs for the duration of the body; scope invariants
     to a frame by reading `(:frame epoch)` inside a `:check` fn.

     Example — an app-db key must stay a vector across a dispatch run:

         (with-invariants [[:db [:cart :items] vector?]
                           (fn [epoch] (not (neg? (get-in (:db-after epoch)
                                                          [:cart :total]))))]
           (rf/dispatch-sync [:cart/add {:sku \"A\"}])
           (rf/dispatch-sync [:cart/checkout]))"
     {:arglists '([[invariant-spec*] body+])}
     [invariants & body]
     (when-not (vector? invariants)
       (error/throw-error!
         :rf.error/story-bad-invariants-vector
         'rf.story/with-invariants
         (str "with-invariants expects a vector of invariant specs as its "
              "first form; pass `[invariant-spec …]` before the body.")
         {:recovery :supply-a-vector-of-invariant-specs
          :extra    {:invariants invariants}}))
     `(run-with-invariants* [~@invariants] (fn [] ~@body))))
