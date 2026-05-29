(ns re-frame.story.play.runner-events
  "re-frame-side driver for the rich-DSL play runner (rf2-8i2a9).

  The pure step state machine + script parsing lives in
  `re-frame.story.play.runner`. This namespace owns the impure seams
  the step types reach for:

  - `:dispatch` / `:dispatch-sync` → `rf/dispatch*` / `rf/dispatch-sync*`
    against the variant's frame.
  - `:wait` ms → JS `setTimeout` (CLJS) or `Thread/sleep` (JVM).
  - `:assert-db` path value → read from `rf/get-frame-db` and compare.
  - `:assert-db` path :pred fn-or-sym → invoke the predicate. A FN
    handed in directly is called as-is (advanced-CLJS-safe); a SYMBOL
    is resolved at run time via `requiring-resolve` (JVM) or a
    best-effort `goog.global` walk (CLJS — fragile under advanced
    compilation, see rf2-inbad).
  - `:click` / `:type` / `:assert-dom` → delegate to
    `re-frame.story.play.dom` (no-op on JVM / no-DOM).

  The driver is async (returns a promise) so `:wait` and async
  dispatches compose naturally. On JVM, the driver runs the steps
  synchronously and returns a resolved future.

  ## Per-frame run state

  The runner stores a per-frame run-state map in a global atom
  (`run-state`) so the toolbar status chip can read it reactively.
  Keys: `{variant-id <runner state map>}`.

  ## Trace integration

  Each step emits a `:rf.story.play/step` trace event via
  `re-frame.trace.tooling/with-trace` so the Xray Trace tab shows the
  full play timeline with PASS/FAIL outcomes alongside the rest of the
  cascade."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string             :as str]
            [re-frame.core              :as rf]
            #?(:cljs [reagent.core      :as r])
            [re-frame.story.assertions  :as assertions]
            [re-frame.story.config      :as config]
            [re-frame.story.late-bind   :as late-bind]
            [re-frame.story.play        :as play]
            [re-frame.story.play.dom    :as dom]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.play.settled-boundary :as boundary]
            [re-frame.story.registrar   :as registrar]))

;; ---- per-variant run-state -----------------------------------------------

(defonce
  ^{:doc "frame-id → runner-state map. The UI's play-status chip derefs
         this atom and renders the per-variant status. The driver
         swaps the slot on every step transition. CLJS uses a Reagent
         ratom (so UI re-renders observe it); JVM uses a plain atom.

         rf2-tl7zk multi-play: the stored state carries a `:play-key`
         slot (the play's `:name` for `:plays` variants, nil for the
         single-script `:play-script` slot). The chip uses this to
         show which play was last run; per-play history lives in
         `runs-by-play` for finer-grained queries."}
  run-state
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "[frame-id play-key] → runner-state. rf2-tl7zk: per-play
         history so the toolbar dropdown can show each play's last
         outcome and the CI runner can read per-play terminal state.
         For single-script (`:play-script`) variants the only key is
         `[variant-id nil]`. CLJS uses a Reagent ratom."}
  runs-by-play
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "frame-id → play-key. rf2-tl7zk: the play the toolbar is
         currently focused on. Default is the first play's name (or
         nil for single-script variants). Changed by the dropdown's
         `select-play!`. Reagent ratom on CLJS."}
  active-play
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defn current-state
  "Read the current run-state for `frame-id`, or nil if no run exists."
  [frame-id]
  (get @run-state frame-id))

(defn current-state-for-play
  "Read the run-state for `(frame-id, play-key)`, or nil. rf2-tl7zk:
  exposes per-play state for the dropdown's per-row status badges +
  the CI runner's per-play outcome read."
  [frame-id play-key]
  (get @runs-by-play [frame-id play-key]))

(defn active-play-key
  "Return the play-key the toolbar is currently focused on for
  `frame-id`, or nil. rf2-tl7zk multi-play."
  [frame-id]
  (get @active-play frame-id))

(defn set-active-play!
  "Set the toolbar's focused play for `frame-id`. rf2-tl7zk multi-play.
  Idempotent — re-setting the same key is a no-op."
  [frame-id play-key]
  (swap! active-play assoc frame-id play-key)
  nil)

(defn clear-state!
  "Wipe the run-state for `frame-id`. Called from frame teardown +
  before each fresh run."
  [frame-id]
  (swap! run-state dissoc frame-id)
  (swap! runs-by-play (fn [m]
                        (into {} (remove (fn [[[fid _]]]
                                           (= fid frame-id)) m))))
  (swap! active-play dissoc frame-id)
  nil)

(defn- update-state!
  [frame-id play-key f & args]
  (swap! run-state update frame-id #(apply f % args))
  (swap! runs-by-play update [frame-id play-key] #(apply f % args))
  nil)

(defn- set-state!
  [frame-id play-key state]
  (let [tagged (assoc state :play-key play-key)]
    (swap! run-state    assoc frame-id tagged)
    (swap! runs-by-play assoc [frame-id play-key] tagged))
  nil)

;; ---- wall-clock probe ----------------------------------------------------

(defn- now-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

;; ---- spec resolution -----------------------------------------------------

(defn- handler-meta
  "Look up the variant body via the framework registrar."
  [variant-id]
  (try
    (registrar/handler-meta :variant variant-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---- folded-plan consumption (rf2-5x1wt.19) ------------------------------
;;
;; The runtime CONSUMES the `.18`-folded plan: every play's script is folded
;; through `assertions/fold-script` at resolution time, so a shipping
;; `:assert-db` / `:assert-dom` step is rewritten to the canonical
;; `[:assert assertion-atom]` checkpoint BEFORE the run loop drives it
;; (spec/017 §Assertions — one atom, two positions; NewTestStory rf2-5x1wt.19
;; §B5.9). The `.18` worker deferred runtime consumption to this bead; this
;; is where it lands. After folding, `exec-step!` only ever sees the ONE
;; assertion atom in its `[:assert …]` checkpoint position — there is no
;; longer a synthetic `:rf.assert/db` / `:rf.assert/dom` rail (a folded
;; `:assert-db` dispatches the real `:rf.assert/path-equals` handler; a
;; folded `:assert-dom` routes through the DOM executor recording a
;; canonical `:rf.assert/dom-*` record). One assertion-record vocabulary,
;; not two.

(defn- fold-spec
  "Fold a parsed play spec's `:script` through `assertions/fold-script` so
  shipping `:assert-db` / `:assert-dom` steps become canonical
  `[:assert …]` checkpoints (rf2-5x1wt.19). Pure data → data; preserves
  `:auto-run?` / `:name`."
  [spec]
  (cond-> spec
    (contains? spec :script) (update :script assertions/fold-script)))

(defn variant-play-script
  "Resolve the `:play-script` body on `variant-id`, parse it, and FOLD its
  script (rf2-5x1wt.19). Returns the normalised spec map per
  `runner/parse-spec` with every shipping `:assert-db` / `:assert-dom` step
  rewritten to the canonical `[:assert …]` checkpoint. Variants without
  `:play-script` return `{:script [] :auto-run? true}`.

  Note (rf2-tl7zk): variants declaring `:plays` resolve to the FIRST
  play's spec — preserves legacy single-script call sites + matches the
  toolbar's 'default play' behaviour. Callers that need the full plays
  vector should use `variant-plays` instead."
  [variant-id]
  (let [body  (handler-meta variant-id)
        plays (runner/variant-body->plays body)]
    (fold-spec
      (cond
        ;; Multi-play (:plays slot) — default to the first play.
        (and (seq plays) (contains? body :plays))
        (first plays)

        ;; Single-script (:play-script slot) — legacy path.
        :else
        (runner/parse-spec (when body (:play-script body)))))))

;; ---- multi-play warning (one-shot) ---------------------------------------

(defonce ^:private ^{:doc "Set of variant ids that have already received
                          the both-:play-script-and-:plays console
                          warning. One warning per variant per page
                          lifetime keeps the console quiet."}
  warned-both-slots
  (atom #{}))

(defn- warn-both-slots-once!
  [variant-id]
  (when (and variant-id (not (contains? @warned-both-slots variant-id)))
    (swap! warned-both-slots conj variant-id)
    #?(:cljs
       (try
         (js/console.warn
           (str "[re-frame.story.play] " (pr-str variant-id)
                " declares BOTH :play-script and :plays — preferring :plays."
                " Pick one per variant to silence this warning."))
         (catch :default _ nil))
       :clj
       (binding [*out* *err*]
         (println (str "[re-frame.story.play] " (pr-str variant-id)
                       " declares BOTH :play-script and :plays — preferring :plays."
                       " Pick one per variant to silence this warning."))))))

(defn variant-plays
  "Resolve the canonical vector of parsed plays for `variant-id`. Pure
  data → data; works on JVM + CLJS. rf2-tl7zk multi-play.

  - `:plays` present → returns the parsed plays vector (size >= 1).
  - `:play-script` present → returns a single-entry vector wrapping the
    parsed single-script spec.
  - Both present → warns once, prefers `:plays`.
  - Neither → returns `[]`."
  [variant-id]
  (let [body (handler-meta variant-id)]
    (when (and body
               (contains? body :play-script)
               (contains? body :plays))
      (warn-both-slots-once! variant-id))
    ;; rf2-5x1wt.19 — FOLD every resolved play's script so the runtime
    ;; consumes the `.18`-folded plan: `:assert-db` / `:assert-dom` steps
    ;; become canonical `[:assert …]` checkpoints before the run loop.
    (mapv fold-spec (runner/variant-body->plays body))))

(defn resolve-play
  "Resolve a `(variant-id, play-key)` pair to the parsed, FOLDED play spec,
  or nil. `play-key` may be nil — meaning 'the default play' (first
  entry for multi-play, the single script for `:play-script`).
  rf2-tl7zk multi-play. The script is folded (rf2-5x1wt.19) via
  `variant-plays`."
  [variant-id play-key]
  (let [plays (variant-plays variant-id)]
    (if (nil? play-key)
      (first plays)
      (runner/find-play plays play-key))))

;; ---- trace emission ------------------------------------------------------

(defn- emit-trace!
  "Emit a `:rf.story.play/step` trace event for `step` against
  `variant-id`. Goes through `re-frame.core/emit-trace-event!` (which
  delegates to `re-frame.trace/emit!`) so the bus + ring buffer +
  listener fan-out all observe it. Safe under production elision —
  `re-frame.trace/emit!` short-circuits when `interop/debug-enabled?`
  is false."
  [variant-id name idx step result]
  (try
    (let [payload (runner/trace-record
                    {:variant-id variant-id
                     :name       name
                     :idx        idx
                     :step       step
                     :result     result})]
      ;; rf/emit-trace-event! arity is (op operation tags) per
      ;; re-frame.trace/emit!. We tag the envelope's :frame slot so
      ;; consumers (Trace tab, story.assertions listener) can route
      ;; per-frame.
      (rf/emit-trace-event!
        :rf.story.play/step
        runner/trace-event-id
        (merge {:frame variant-id} payload)))
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---- settled-boundary flush hooks (rf2-5x1wt.2) --------------------------
;;
;; `[:dispatch event-vector]` settles through `settled-boundary` (spec/017
;; §Script and `settled-boundary`). The runner takes its flush-hooks from
;; the adapter-aware caller via the `:settled-boundary-hooks` late-bind
;; slot; the default is the headless hooks (`dispatch-sync*` drain). Story
;; core never reaches for `dispatch-sync` directly — the boundary ns owns
;; the drain, the hooks own the richer reactive / DOM flushes.

(defn current-flush-hooks
  "Resolve the active flush-hooks map. An adapter-aware caller (the
  Reagent/UIx/Helix shell, a future `:dom` browser runner) registers a
  richer hooks map via `late-bind/set-fn! :settled-boundary-hooks <fn>`,
  where the fn takes the frame-id and returns the hooks for that frame.
  When no adapter has registered, the headless hooks are used so a
  `[:dispatch …]` step settles to fixed point synchronously."
  [frame-id]
  (if-let [f (late-bind/get-fn :settled-boundary-hooks)]
    (or (try (f frame-id) (catch #?(:clj Throwable :cljs :default) _ nil))
        boundary/headless-flush-hooks)
    boundary/headless-flush-hooks))

;; ---- step executors ------------------------------------------------------

(declare read-frame-db)

(defn- assertion-count
  "Count of records currently in the frame's `:rf.story/assertions`. Tolerant."
  [frame-id]
  (count (:rf.story/assertions (read-frame-db frame-id))))

(defn- failed-since
  "Records appended to `:rf.story/assertions` since `prev-count` that
  have `:passed? false`. Used to bridge handler-recorded assertion
  failures (e.g. `:dispatch-sync [:rf.assert/path-equals ...]`) back
  into the runner's step-result stream so the play's terminal status
  reflects the failure."
  [frame-id prev-count]
  (let [all (vec (:rf.story/assertions (read-frame-db frame-id)))]
    (filterv (fn [r] (false? (:passed? r)))
             (subvec all (min prev-count (count all))))))

(defn- dispatch-step-result
  "Build the step result for a (possibly-assertion-bearing) :dispatch /
  :dispatch-sync. If new failed assertions appeared in the frame's
  `:rf.story/assertions` since `prev` (typically because the
  dispatched event was a `:rf.assert/*` whose reg-event-fx handler
  recorded a `:passed? false` record), surface them as a step-fail.
  Otherwise step-skip (no assertion contribution to pass/fail)."
  [frame-id prev idx step]
  (let [failed (failed-since frame-id prev)]
    (if (seq failed)
      (let [rec (first failed)
            msg (or (:message rec)
                    (str (:id rec) " " (pr-str (:payload rec))
                         " failed (expected " (pr-str (:expected rec))
                         ", actual " (pr-str (:actual rec)) ")"))]
        (runner/step-fail idx step
                          {:expected (:expected rec)
                           :actual   (:actual rec)
                           :message  msg}))
      (runner/step-skip idx step))))

(defn- boundary-result->step
  "Project a `settled-boundary/dispatch-and-settle!` non-`:settled`
  outcome into a runner step-result. `:cannot-run` becomes a step-fail
  carrying the refusal (so the play surfaces a distinct refusal, never a
  silent pass — spec/017 §`:cannot-run`); `:error` becomes a step-fail
  with the boundary error message. Returns nil for `:settled` (the
  dispatch's pass/fail comes from the assertion bridge instead)."
  [idx step settle]
  (case (:status settle)
    :cannot-run
    (runner/step-fail idx step
                      {:cannot-run? true
                       :required-boundary (:required-boundary settle)
                       :provided-boundary (:provided-boundary settle)
                       :reason   (:reason settle)
                       :message  (str "cannot run " (pr-str step)
                                      " — requires settled-boundary "
                                      (pr-str (:required-boundary settle))
                                      " but runner provides "
                                      (pr-str (:provided-boundary settle))
                                      " (" (name (or (:reason settle) :runner-below-required-boundary)) ")")})
    :error
    (runner/step-exception idx step (:error settle))
    ;; :settled → no step-level result from the boundary itself.
    nil))

(defn- exec-dispatch!
  "Execute a `:dispatch` step — dispatch the event and settle through
  `settled-boundary` (spec/017 §Script and `settled-boundary`,
  rf2-5x1wt.2). In headless this is the existing `dispatch-sync*`
  run-to-fixed-point drain, named via `settled-boundary`; richer runners
  supply reactive / DOM flushes through their flush-hooks. The runner
  NEVER hard-codes `dispatch-sync` — it routes through the boundary's
  caller-supplied hooks (`current-flush-hooks`).

  When the active runner cannot satisfy the step's required boundary the
  boundary refuses with `:cannot-run` and the step is NOT dispatched
  (fail-closed); the refusal becomes a runner step-fail rather than a
  silent pass.

  Drains any handler-exception trace events captured by the play
  listener into `:rf.story/assertions` so the test-mode pane + Xray
  assertions panel see the failure (rf2-z2dq8). The re-frame router
  catches handler exceptions and emits `:rf.error/handler-exception`
  rather than re-throwing, so the local catch fires only for
  exceptions that escape the interceptor chain entirely.

  Bridges handler-recorded `:rf.assert/*` failures into the runner's
  step-result stream — a `:rf.assert/*` event whose handler records
  `:passed? false` becomes a runner-visible step-fail so the play's
  terminal status flips to `:fail`."
  [frame-id idx step]
  (let [evec     (runner/step-event step)
        prev     (assertion-count frame-id)
        required (boundary/step-required-boundary step)
        hooks    (current-flush-hooks frame-id)
        settle   (try
                   (boundary/dispatch-and-settle! frame-id evec hooks required step)
                   (catch #?(:clj Throwable :cljs :default) e
                     {:status :error
                      :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))
                      :step   step}))]
    (play/drain-pending-exceptions! frame-id :phase-4-play)
    (or (boundary-result->step idx step settle)
        (dispatch-step-result frame-id prev idx step))))

(defn- exec-dispatch-sync!
  [frame-id idx step]
  (let [evec   (runner/step-event step)
        prev   (assertion-count frame-id)
        result (try
                 (rf/dispatch-sync* evec {:frame frame-id})
                 nil
                 (catch #?(:clj Throwable :cljs :default) e
                   (runner/step-exception idx step
                                          #?(:clj  (.getMessage ^Throwable e)
                                             :cljs (str e)))))]
    (play/drain-pending-exceptions! frame-id :phase-4-play)
    (or result (dispatch-step-result frame-id prev idx step))))

(defn- read-frame-db
  "Read the app-db for `frame-id`. Tolerant — returns nil if the frame
  is gone."
  [frame-id]
  (try
    (rf/get-frame-db frame-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- resolve-predicate
  "Resolve a predicate symbol to a callable fn. JVM uses
  `requiring-resolve`; CLJS resolves via `goog.global` lookup on
  munged dotted names (works for fns reachable from a global ns
  like `js/cljs.user.my_pred`). Returns nil on miss.

  CLJS symbol resolution is BEST-EFFORT and FRAGILE under advanced
  compilation — the closure compiler mangles author namespace names,
  so the munged-dotted-name walk won't find them. The advanced-safe
  authoring path is to pass the predicate as a FN DIRECTLY in the
  `:wait-until [:db path :pred <fn>]` predicate (or the `:assert-db …
  :pred <fn>` form, which folds to `:rf.assert/path-matches [:fn fn]`);
  the resolver is only reached for the symbol escape-hatch.

  rf2-inbad: this fn remains for the symbol-form escape hatch (JVM
  tests + :dev CLJS) — used by `[:wait-until [:db … :pred sym]]` — but is
  NOT the primary authoring path."
  [sym]
  (when sym
    (try
      #?(:clj
         (when-let [v (requiring-resolve (symbol sym))]
           (when (var? v) @v))
         :cljs
         (let [ns-part   (namespace sym)
               name-part (name sym)]
           (when (and ns-part name-part)
             (let [dotted (str ns-part "." name-part)
                   munged (str/replace dotted #"-" "_")
                   parts  (str/split munged #"\.")]
               (loop [obj js/window
                      ks  parts]
                 (cond
                   (nil? obj)  nil
                   (empty? ks) (when (fn? obj) obj)
                   :else       (recur (aget obj (first ks)) (rest ks))))))))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- pred-label
  "Human-readable label for a `:pred` ref — the symbol literal when
  the author handed a symbol, the marker `<fn>` when they handed a
  fn directly. Used in failure messages so authors can spot which
  predicate failed without leaking compiler-munged identifiers."
  [ref]
  (cond
    (symbol? ref) (str ref)
    (fn? ref)     "<fn>"
    :else         (pr-str ref)))

;; ---- DOM-family assertion atom executor (rf2-5x1wt.19) -------------------
;;
;; The DOM family (`:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
;; `:rf.assert/dom-text`) is the fold target for the shipping `:assert-dom`
;; step (`.18`). It has no reg-event-fx handler yet (the DOM runner that
;; PROVES it lands later — the `:dom` capability is wired now via
;; `requirements`), so an `[:assert [:rf.assert/dom-* …]]` checkpoint is
;; EVALUATED directly through the DOM executor (`dom/assert-visible` /
;; `dom/assert-text`) and records a CANONICAL `:rf.assert/dom-*` record on
;; the frame's `:rf.story/assertions` slot. There is no synthetic
;; `:rf.assert/dom` id — the canonical folded id is the one recorded.

(def ^:private dom-atom->mode
  "Map a DOM-family assertion id to the `dom/assert-visible` mode it
  evaluates. `:rf.assert/dom-text` is handled separately (it calls
  `dom/assert-text`)."
  {:rf.assert/dom-visible :visible
   :rf.assert/dom-hidden  :hidden})

(defn- exec-assert-dom-atom!
  "Evaluate a folded DOM-family assertion atom `[:rf.assert/dom-* selector
  & args]` at this checkpoint (rf2-5x1wt.19). Drives the DOM executor,
  records the CANONICAL `:rf.assert/dom-*` record on the frame slot (so the
  unified result's `:assertions` carries the real folded id, not a
  synthetic one), and returns the runner step-result. A no-DOM headless
  context records a `{:skipped? true}` step — `:cannot-run` at assertion
  granularity (the slot mirror keeps it OUT of the recorded outcomes so it
  is not a vacuous pass), never a silent green."
  [frame-id idx step atom-v]
  (let [aid      (first atom-v)
        selector (nth atom-v 1 nil)
        result   (if (= :rf.assert/dom-text aid)
                   (dom/assert-text selector (nth atom-v 2 nil))
                   (dom/assert-visible selector (get dom-atom->mode aid :visible)))]
    ;; Record the canonical DOM-family assertion record on the slot (unless
    ;; the step was a no-DOM skip — a skip proved nothing, so it must not
    ;; read as a vacuous pass).
    (when-not (:skipped? result)
      (assertions/record!
        frame-id
        (cond-> {:assertion    aid
                 :passed?      (boolean (:passed? result))
                 :payload      (vec (rest atom-v))
                 :source-coord (:source (registrar/handler-meta :variant frame-id))}
          (contains? result :expected) (assoc :expected (:expected result))
          (contains? result :actual)   (assoc :actual   (:actual result))
          (:message result)            (assoc :reason   (:message result)))))
    (cond
      (:skipped? result) (runner/step-fail idx step
                                           {:skipped? true
                                            :message  (or (:message result)
                                                          (str "no DOM — cannot prove "
                                                               (pr-str atom-v)))})
      (:passed? result)  (runner/step-pass idx step)
      :else              (runner/step-fail idx step result))))

(defn- exec-click!
  [_frame-id idx step]
  (let [selector (runner/step-selector step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot click " (pr-str selector))})

      (dom/click! selector)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "click failed — no node matched " (pr-str selector))}))))

(defn- exec-type!
  [_frame-id idx step]
  (let [[selector text] (runner/step-type-text step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot type into " (pr-str selector))})

      (dom/type! selector text)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "type failed — no node matched " (pr-str selector))}))))

(defn- exec-focus!
  "Execute a `[:focus selector]` step — a DOM focus event (rf2-5x1wt.17).
  Parallels `exec-click!`: a no-DOM headless runner records a
  `{:skipped? true}` no-op (the capability registry already refuses the
  step at preflight, so this is the belt-and-braces runtime guard); a
  DOM/browser runner fires the synthetic focus and records a step-skip."
  [_frame-id idx step]
  (let [selector (runner/step-selector step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot focus " (pr-str selector))})

      (dom/focus! selector)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "focus failed — no node matched " (pr-str selector))}))))

(declare exec-assert-dom-atom!)

(defn- tape-evaluated-assertion?
  "True iff the assertion atom `atom-v` (`[:rf.assert/id & args]`) is
  evaluated AGAINST THE EPOCH TAPE in the result boundary rather than by
  dispatching a `reg-event-fx` handler into the frame (rf2-8y47c +
  rf2-fh7g4).

  Three assertion families carry NO `reg-event-fx` handler and are minted
  by the result boundary (`re-frame.story.result`), not by a dispatch:

  - `:rf.assert/schema-error` — paired against the projected
    `:rf.error/schema-validation-failure` evidence (rf2-5x1wt.21).
  - the causal / cascade family (`:rf.assert/caused` /
    `:rf.assert/no-cascade-rerender`) — paired against the
    `:reactive-counts` `:by-cause` projection (rf2-5x1wt.31).
  - the browser-tier oracle family (`:rf.assert/visual-snapshot` /
    `:rf.assert/a11y` / `:rf.assert/a11y-structural`) — projected by the
    browser executor into the same assertion record (rf2-5x1wt.28).

  This is the SINGLE classifier the in-script `[:assert …]` executor
  consults so a tape-evaluated checkpoint is NEVER dispatched into the
  frame (which, with no handler, would mint a spurious
  `:rf.error/no-such-handler` trace and skip the real tape evaluation).
  It deliberately reads the existing `re-frame.story.assertions`
  predicates / id-sets — the ONE source of truth for which family an id
  belongs to — so a future tape-evaluated family is covered by adding it
  there, with no special-case to grow here. The DOM family is NOT here:
  it has its own inline executor (`exec-assert-dom-atom!`), routed ahead
  of this classifier."
  [atom-v]
  (or (assertions/schema-error? atom-v)
      (assertions/causal? atom-v)
      (contains? assertions/browser-assertion-ids (first atom-v))))

(defn- exec-assert!
  "Execute an `[:assert [:rf.assert/id & args]]` in-script checkpoint
  (rf2-5x1wt.17). Evaluates the wrapped assertion atom at THIS point in
  the script (spec/017 §Inline script assertions vs terminal
  assertions). This is the SINGLE in-script assertion executor — after
  the `.18` fold the runtime consumes (rf2-5x1wt.19), a shipping
  `:assert-db` / `:assert-dom` step has already been rewritten to this
  `[:assert …]` checkpoint, so there is no longer a parallel `:assert-db`
  / `:assert-dom` executor minting synthetic `:rf.assert/db` /
  `:rf.assert/dom` records.

  Three routes, by atom family:

  - The DOM family (`:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
    `:rf.assert/dom-text`) has no reg-event-fx handler yet (the DOM runner
    that proves it lands later); it is EVALUATED directly through the DOM
    executor (`exec-assert-dom-atom!`), recording a canonical
    `:rf.assert/dom-*` record on the slot.
  - The tape-evaluated families (`tape-evaluated-assertion?`: schema-error,
    the causal / cascade family, the browser-tier oracle family) ALSO carry
    no reg-event-fx handler — they are minted by the result boundary
    against the epoch tape, not by a dispatch. An in-script checkpoint for
    one of these records a no-op step-skip (the boundary owns its verdict);
    dispatching it would mint a spurious `:rf.error/no-such-handler` trace
    AND skip the real tape evaluation (rf2-8y47c + rf2-fh7g4).
  - Every other (dispatchable) `:rf.assert/*` atom dispatches the event
    through `settled-boundary` — the standard reg-event-fx handler records
    the `:passed?` record on the frame's `:rf.story/assertions` slot — and
    we bridge that record back into the runner step stream so a failing
    checkpoint flips the play's terminal status to `:fail`."
  [frame-id idx step]
  (let [atom-v (runner/step-assertion step)]
    (cond
      (contains? assertions/dom-assertion-ids (first atom-v))
      (exec-assert-dom-atom! frame-id idx step atom-v)

      ;; Tape-evaluated families carry no reg-event-fx handler; the result
      ;; boundary owns their verdict. Record a no-op step-skip — never a
      ;; dispatch (which would hit :rf.error/no-such-handler) (rf2-8y47c +
      ;; rf2-fh7g4).
      (tape-evaluated-assertion? atom-v)
      (runner/step-skip idx step)

      :else
      (let [prev     (assertion-count frame-id)
            required (boundary/step-required-boundary step)
            hooks    (current-flush-hooks frame-id)
            settle   (try
                       (boundary/dispatch-and-settle! frame-id atom-v hooks required step)
                       (catch #?(:clj Throwable :cljs :default) e
                         {:status :error
                          :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))
                          :step   step}))]
        (play/drain-pending-exceptions! frame-id :phase-4-play)
        (or (boundary-result->step idx step settle)
            ;; The wrapped :rf.assert/* handler recorded its outcome on the
            ;; frame's :rf.story/assertions slot. Read what landed since the
            ;; pre-dispatch count and surface it as the checkpoint's result.
            (let [all   (vec (:rf.story/assertions (read-frame-db frame-id)))
                  new   (subvec all (min prev (count all)))
                  rec   (last new)]
              (cond
                (nil? rec)            (runner/step-skip idx step)
                (false? (:passed? rec))
                (runner/step-fail idx step
                                  {:expected (:expected rec)
                                   :actual   (:actual rec)
                                   :message  (or (:reason rec)
                                                 (str (:assertion rec) " "
                                                      (pr-str (:payload rec)) " failed"))})
                :else                 (runner/step-pass idx step))))))))

(defn- queue-empty?
  "True iff `frame-id`'s event queue has drained (rf2-5x1wt.17). Under a
  settled-boundary runner the preceding `[:dispatch …]` step ran the
  router to a FIXED POINT (`settled-boundary` — the `dispatch-sync*`
  run-to-completion drain in headless), so by the time a following
  `[:wait-until [:queue-empty]]` is evaluated the queue is, by contract,
  drained. The predicate is the explicit, readable settle-on-drain form;
  it is satisfied whenever the runner has reached the settled boundary,
  which the step driver guarantees before this step runs. Returns true."
  [_frame-id]
  ;; The settled-boundary contract (re-frame.story.play.settled-boundary)
  ;; guarantees the queue has drained to a fixed point before the next
  ;; step executes; there is no partial-drain state to observe here.
  true)

(defn- wait-until-satisfied?
  "Evaluate a decomposed `:wait-until` predicate against `frame-id`'s
  current state (rf2-5x1wt.17). Pure-ish — reads the frame snapshot, no
  dispatch. Returns a boolean."
  [frame-id {:keys [kind path mode expected pred-ref pred-fn?]}]
  (case kind
    :db          (let [db     (read-frame-db frame-id)
                       actual (get-in db path)]
                   (case mode
                     :equals (= expected actual)
                     :pred   (let [f (if pred-fn? pred-ref (resolve-predicate pred-ref))]
                               (boolean (and f (try (f actual)
                                                    (catch #?(:clj Throwable :cljs :default) _ false)))))
                     false))
    :queue-empty (queue-empty? frame-id)
    false))

(defn- exec-wait-until!
  "Execute a `[:wait-until predicate-spec]` step (rf2-5x1wt.17) — the
  DETERMINISTIC settle-on-condition. In headless the preceding
  `[:dispatch …]` already drained to a fixed point, so the predicate is
  checked once synchronously: satisfied → step-skip (advance);
  unsatisfied → step-fail TIMING OUT READABLY with the unmet
  predicate-spec, NEVER a silent pass. (A richer DOM/browser runner that
  needs to await an async flush re-checks under a bounded poll; that
  poll is the adapter caller's concern — the headless contract is the
  one-shot deterministic check this fn implements.)"
  [frame-id idx step]
  (let [pspec   (runner/step-wait-until step)]
    (if (wait-until-satisfied? frame-id pspec)
      (runner/step-skip idx step)
      (runner/step-fail idx step
                        {:cannot-run? false
                         :expected (nth step 1)
                         :message  (str "wait-until " (pr-str (nth step 1))
                                        " never became true (deterministic "
                                        "queue/state predicate did not hold "
                                        "after the preceding dispatch settled)")}))))

(defn exec-step!
  "Execute ONE step against `frame-id`. Returns a step-result record
  (per `runner/step-pass` / `step-fail` / `step-skip` / `step-exception`).
  Pure-shape return — the run-state mutation is the caller's job.

  `:wait` is special-cased OUT of this fn — it requires an async
  yield (`setTimeout` / `Thread/sleep`) the driver schedules around.

  rf2-5x1wt.19 — the runtime consumes the `.18`-folded plan: every script
  is folded at resolution time (`runner-events/variant-plays` /
  `play/variant-play-steps`), so a shipping `:assert-db` / `:assert-dom`
  step has already become the canonical `[:assert assertion-atom]`
  checkpoint by the time it reaches here. A raw `:assert-db` / `:assert-dom`
  (a hand-built step that bypassed resolution) is folded inline as a
  belt-and-braces guard so there is ONE in-script assertion executor and
  ONE assertion-record vocabulary — never a synthetic `:rf.assert/db` /
  `:rf.assert/dom` rail."
  [frame-id idx step]
  (let [stype (runner/step-type step)]
    (case stype
      :dispatch       (exec-dispatch!      frame-id idx step)
      :dispatch-sync  (exec-dispatch-sync! frame-id idx step)
      :assert         (exec-assert!        frame-id idx step)
      ;; A raw shipping assertion step that escaped folding — fold it inline
      ;; to the canonical checkpoint and run the ONE assert executor.
      (:assert-db
       :assert-dom)   (exec-assert! frame-id idx (assertions/fold-assert-step step))
      :wait-until     (exec-wait-until!    frame-id idx step)
      :click          (exec-click!         frame-id idx step)
      :type           (exec-type!          frame-id idx step)
      :focus          (exec-focus!         frame-id idx step)
      :wait           (runner/step-skip idx step)   ; driver handles the actual sleep
      (runner/unknown-step idx step))))

;; ---- single-step driver (rf2-ee38b.3 — step-debugger re-base) ------------
;;
;; The play step-debugger (`re-frame.story.ui.test-mode.stepper-state`)
;; used to drive ONLY `:dispatch` / `:dispatch-sync` steps via the
;; legacy `play/variant-play-events` projection — `:wait` / `:click` /
;; `:type` / `:assert-db` / `:assert-dom` steps were silently dropped, so
;; the stepper walked a TRUNCATED sequence with a wrong cursor/total and
;; no assert outcomes. `run-step!` re-bases the stepper on the SAME rich-
;; DSL executor the canvas auto-run path uses, so every step type runs in
;; the debugger exactly as it does live.

(defn run-step!
  "Execute ONE coerced rich-DSL `step` (at index `idx`) against
  `frame-id`, mirror assertion-class outcomes into the
  `:rf.story/assertions` slot, emit the per-step trace event, and return
  the step-result record. Synchronous on both runtimes (`:wait` records
  a step-skip rather than blocking — the interactive stepper does not
  sleep).

  Public so the step-debugger substrate (`play/step-once!`) can drive a
  full rich-DSL step without re-implementing the executor. Reached from
  `play.cljc` via the `:run-play-step` late-bind hook to avoid the
  play ↔ runner-events require cycle."
  [frame-id idx step]
  (let [result (try
                 (exec-step! frame-id idx step)
                 (catch #?(:clj Throwable :cljs :default) e
                   (runner/step-exception idx step
                                          #?(:clj  (.getMessage ^Throwable e)
                                             :cljs (str e)))))]
    ;; rf2-5x1wt.19 — `exec-step!` (via `exec-assert!`) already wrote the
    ;; canonical assertion record onto `:rf.story/assertions`; no synthetic
    ;; slot mirror here.
    (emit-trace! frame-id nil idx step result)
    result))

;; ---- async scheduler -----------------------------------------------------

(defn- schedule!
  "Run `f` after `ms` milliseconds. CLJS → `js/setTimeout`; JVM →
  block the calling thread via `Thread/sleep` then invoke. Returns a
  cancellable handle on CLJS, nil on JVM."
  [ms f]
  #?(:cljs (js/setTimeout f ms)
     :clj  (do (when (pos? ms) (Thread/sleep ^long ms))
               (f)
               nil)))

;; ---- assertion-slot recording (rf2-5x1wt.19) ----------------------------
;;
;; ONE assertion-record vocabulary. After the runtime consumes the `.18`-
;; folded plan (`variant-plays` / `variant-play-steps` fold every script),
;; every in-script assertion arrives as the canonical `[:assert
;; assertion-atom]` checkpoint, and `exec-assert!` is the SOLE recorder:
;;
;;   - a non-DOM `:rf.assert/*` atom dispatches its reg-event-fx handler,
;;     which writes the canonical record onto `:rf.story/assertions`;
;;   - a DOM-family atom is evaluated by `exec-assert-dom-atom!`, which
;;     writes a canonical `:rf.assert/dom-*` record.
;;
;; There is no longer a synthetic `:rf.assert/db` / `:rf.assert/dom` slot-
;; mirror bridge: the folded checkpoint's OWN record IS the slot record, so
;; mirroring the step-result on top would double-count. `record-result!`
;; therefore only updates the run-state + emits the trace event; the slot
;; write already happened inside `exec-assert!`. This is the move that
;; collapsed the documented "false GREEN" — run-state and the
;; `:rf.story/assertions` slot now derive from the ONE canonical record.

(defn- record-result!
  "Append `result` to the run-state for `frame-id` and emit the per-step
  trace event. The `:rf.story/assertions` slot write already happened
  inside `exec-assert!` (the canonical folded-atom record), so this no
  longer mirrors a synthetic record on top (rf2-5x1wt.19)."
  [frame-id play-key name idx step result]
  (update-state! frame-id play-key runner/record-step-result result)
  (emit-trace! frame-id name idx step result)
  nil)

(defn- finish!
  "Transition the run-state to `:pass` / `:fail` and resolve `done-cb`
  with the final state."
  [frame-id play-key done-cb]
  (update-state! frame-id play-key runner/finish (now-ms))
  (when done-cb
    (try (done-cb (current-state-for-play frame-id play-key))
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- run-loop!
  "Iterate over the script, running each step. `:wait` steps yield
  to the scheduler and resume from the wait time onwards.

  `done-cb` is invoked with the final run-state once the loop ends.

  rf2-ftow6 (race fix): each call to `run!` stamps a unique
  `:run-token` on the state map. The loop carries the token it started
  with and aborts if a fresh `run!` has overwritten the state with a
  newer token — that way a concurrent `runner-events/run!`
  (e.g. selection-watcher fires while runtime's `run-phase-4!` is
  mid-script) does not result in TWO loops walking the same shared
  state and double-dispatching events.

  Sync-class steps (`:dispatch-sync`, `:assert-db`, `:assert-dom`)
  recur synchronously; async-class steps (`:dispatch`, `:click`,
  `:type`, `:wait`) yield one tick so the queued effects drain before
  the next step runs. The blanket setTimeout-0 between every step that
  the original (rf2-8i2a9) implementation used was the source of the
  re-mount race — see `runner/async-yield?`."
  [frame-id play-key token done-cb]
  (let [state (current-state-for-play frame-id play-key)]
    (cond
      ;; abort if state has gone missing (frame torn down mid-run)
      (nil? state)
      nil

      ;; abort if a newer run! has taken over the state slot — the
      ;; newer loop owns continuation now, so the stale loop bails
      ;; silently rather than racing it. rf2-ftow6.
      (and token (some? (:run-token state)) (not= token (:run-token state)))
      nil

      (runner/done? state)
      (finish! frame-id play-key done-cb)

      :else
      (let [idx  (:step-idx state)
            step (runner/current-step state)
            nm   (:name state)]
        (cond
          (= :wait (runner/step-type step))
          (let [ms (runner/step-wait-ms step)]
            (record-result! frame-id play-key nm idx step (runner/step-skip idx step))
            (schedule! (or ms 0) #(run-loop! frame-id play-key token done-cb)))

          :else
          (let [result (try
                         (exec-step! frame-id idx step)
                         (catch #?(:clj Throwable :cljs :default) e
                           (runner/step-exception idx step
                                                  #?(:clj  (.getMessage ^Throwable e)
                                                     :cljs (str e)))))
                yield? (runner/async-yield? step)]
            (record-result! frame-id play-key nm idx step result)
            ;; Sync-class step → recur synchronously so the next step
            ;; observes the just-committed effects atomically (the
            ;; legacy `execute-play!`/`doseq` semantics rf2-ftow6
            ;; restores).
            ;;
            ;; Async-class step → yield one tick on CLJS so the
            ;; queued router work / synthetic DOM event handlers drain
            ;; before the next step runs.
            #?(:cljs (if yield?
                       (js/setTimeout #(run-loop! frame-id play-key token done-cb) 0)
                       (recur frame-id play-key token done-cb))
               :clj  (recur frame-id play-key token done-cb))))))))

;; ---- public driver -------------------------------------------------------

(defn run!
  "Drive the play script for `variant-id`. Resets the per-variant
  run-state, walks every step in order, records results, and resolves
  `done-cb` with the terminal run-state.

  Returns the initial run-state (NOT a promise) so synchronous callers
  can immediately observe `:status :running` + `:total`. CLJS callers
  that need a promise can wrap with `js/Promise.` themselves;
  `done-cb` is the canonical completion hook.

  Idempotent w.r.t. concurrent runs — calling `run!` while a previous
  run is in flight cancels the previous run's `done-cb` (the new one
  takes over the run-state slot).

  Arities:
  - `[variant-id]`                — run the default play (rf2-tl7zk:
                                    the first play of `:plays`, or the
                                    single `:play-script`).
  - `[variant-id done-cb]`        — as above + completion callback.
  - `[variant-id spec done-cb]`   — legacy: drive an explicit spec.
                                    The play-key is read off the spec's
                                    `:name` (nil for unnamed scripts).
  - `[variant-id play-key spec done-cb]` — rf2-tl7zk multi-play form:
                                    drive a specific play. `play-key`
                                    is the play's `:name` string (or
                                    nil for the single-script case)."
  ([variant-id]
   (run! variant-id nil nil nil))
  ([variant-id done-cb]
   ;; Legacy two-arity: variant-id + done-cb. Picks the default play.
   (run! variant-id nil nil done-cb))
  ([variant-id spec done-cb]
   ;; Legacy three-arity: variant-id + spec + done-cb. The play-key is
   ;; derived from the spec's :name (nil for unnamed scripts). Preserved
   ;; for back-compat with callers that hand-build a spec.
   (run! variant-id (when spec (:name spec)) spec done-cb))
  ([variant-id play-key spec done-cb]
   (let [spec  (or spec
                   (resolve-play variant-id play-key)
                   ;; Fall back to the legacy single-script path so the
                   ;; default play of a `:play-script` variant Just Works.
                   (variant-play-script variant-id))
         pk    (or play-key (:name spec))
         init  (runner/initial-state spec)
         ;; rf2-ftow6: stamp a fresh token on every run. The loop
         ;; reads it back from the state map; if a concurrent run!
         ;; replaces the state mid-loop, the stale loop sees a
         ;; mismatched token and aborts before re-dispatching.
         token   #?(:clj (java.util.UUID/randomUUID)
                    :cljs (.toString (js/Math.random)))
         started (-> (runner/start init (now-ms))
                     (assoc :run-token token))]
     (set-state! variant-id pk started)
     (set-active-play! variant-id pk)
     (run-loop! variant-id pk token done-cb)
     started)))

(defn re-run!
  "Re-run the play script for `variant-id`. Convenience wrapper around
  `run!` — distinct fn name so the toolbar's `[Re-run]` button has a
  one-call API.

  rf2-tl7zk: with no explicit `play-key`, re-runs the currently active
  play (set by the dropdown). For single-script variants the active
  play is nil, so this matches the legacy behaviour."
  ([variant-id]
   (re-run! variant-id nil))
  ([variant-id done-cb]
   (let [pk (active-play-key variant-id)]
     (run! variant-id pk nil done-cb))))

(defn run-play!
  "rf2-tl7zk multi-play: run the play identified by `play-key` (a
  play's `:name`) for `variant-id`. Passing nil picks the default
  play (first entry for multi-play, the single script for
  `:play-script`)."
  ([variant-id play-key]
   (run-play! variant-id play-key nil))
  ([variant-id play-key done-cb]
   (run! variant-id play-key nil done-cb)))

(defn select-play!
  "rf2-tl7zk: set `variant-id`'s active play to `play-key` WITHOUT
  running it. Used by the toolbar dropdown when the user picks a play
  but the user hasn't pressed Re-run yet."
  [variant-id play-key]
  (set-active-play! variant-id play-key))

(declare run-plays-sequentially!)

(defn auto-run!
  "Run the play script if `:auto-run?` is true. Called from the shell
  after the variant mounts. No-op when the variant has no
  `:play-script` / `:plays` slot or no play declares `:auto-run? true`.

  rf2-tl7zk multi-play: every play with `:auto-run? true` is run in
  ORDER (sequentially) so they don't race against the same frame. By
  the per-play default (first play true, rest false) only the first
  play auto-runs on mount; subsequent plays opt in explicitly."
  ([variant-id]
   (auto-run! variant-id nil))
  ([variant-id done-cb]
   (when config/enabled?
     (let [plays      (variant-plays variant-id)
           auto-plays (filterv (fn [p] (and (:auto-run? p)
                                            (seq (:script p))))
                               plays)]
       (cond
         (empty? auto-plays)
         nil

         ;; Single auto-run play — direct fire so the legacy
         ;; single-script callers keep their existing run shape.
         (= 1 (count auto-plays))
         (let [spec (first auto-plays)]
           (run! variant-id (:name spec) spec done-cb))

         ;; Multiple auto-run plays — sequence them so a later play
         ;; doesn't trample the frame mid-run.
         :else
         (run-plays-sequentially! variant-id auto-plays done-cb))))))

;; ---- run-all (sequential) — rf2-tl7zk ------------------------------------

(defn- run-plays-sequentially!
  "Internal: run `plays` against `variant-id` one after another.
  Resolves `done-cb` with a vector of terminal states once every play
  has finished (or the loop is interrupted by a missing frame)."
  [variant-id plays done-cb]
  (let [acc (atom [])]
    (letfn [(step! [remaining]
              (if (empty? remaining)
                (when done-cb
                  (try (done-cb @acc)
                       (catch #?(:clj Throwable :cljs :default) _ nil)))
                (let [spec (first remaining)
                      pk   (:name spec)]
                  (run! variant-id pk spec
                        (fn [final]
                          (swap! acc conj final)
                          (step! (rest remaining)))))))]
      (step! plays))))

(defn run-all-plays!
  "rf2-tl7zk multi-play: run every play declared on `variant-id` in
  order, sequentially. Calls `done-cb` with a vector of per-play
  terminal states once every play has completed. Returns nil.

  No-op when the variant carries no plays."
  ([variant-id]
   (run-all-plays! variant-id nil))
  ([variant-id done-cb]
   (let [plays (variant-plays variant-id)]
     (when (seq plays)
       (run-plays-sequentially! variant-id (vec plays) done-cb)))))

;; ---- step-debugger seam (rf2-ee38b.3) ------------------------------------
;;
;; `play.cljc` owns the step-debugger substrate but cannot `:require`
;; this ns (the cycle: runner-events → play). It fetches `run-step!` via
;; the `:run-play-step` late-bind hook. Registered at ns load so it is
;; available as soon as the play module drives a stepped run.

(late-bind/set-fn! :run-play-step run-step!)
