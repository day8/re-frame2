(ns re-frame2-pair-mcp.epoch-frame-test
  "Operating-frame resolution for the two epoch-ring tools —
  `trace-window` and `watch-epochs` (rf2-yo4s).

  ## What this pins

  Both are frame-targeted, so the Tool-Pair contract makes them
  resolve explicit override -> session pin -> sole app frame -> nil,
  and REFUSE at nil rather than read some other frame. Neither did.
  Each emitted an implicit-frame call — `(epoch-history)` /
  `(epochs-since id)` — whose runtime arity defaults to
  `(current-frame)`, nil under two-plus app frames with no pin. And
  `rf/epoch-history` answers `[]` for an unknown frame WITHOUT
  erroring, so the ambiguity arrived as an empty ring.

  That empty ring is two different falsehoods depending on the tool:

    - `trace-window` reports `:count 0` — \"nothing happened\" — in the
      same voice as a genuinely quiet window. Its `:count 0` advisory
      cannot catch it, because the advisory reads the SAME
      implicit-frame history, so both sides come back empty together.
    - `watch-epochs` reports `:count 0` AND `:id-aged-out? true`: the
      caller's cursor id cannot be found in an empty history, so a
      LIVE cursor is declared dead and the agent is sent back to page
      one of a frame it never chose.

  ## Why the stub reads the form

  A test that canned an `:ambiguous-frame` response would pass on the
  DEFECT — the tools faithfully relay whatever the runtime hands them,
  and the relay was never the bug. So `runtime-answer` plays a live
  runtime: it derives the operating frame FROM THE EMITTED FORM the
  way `pure/resolve-operating-frame` would, and then answers with that
  frame's ring, reproducing `epochs-since`'s real not-found semantics.
  On the pre-fix form the frame is nil, the ring is `[]`, and the
  falsehoods appear on their own — which is what makes these witnesses
  fail on the pre-fix tree rather than merely assert the fixed shape."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (raw-state/set-allow-raw-state! false))})

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

;; Two app frames, no pin — the session shape the resolver calls
;; ambiguous and the one where guessing is unrecoverable.
(def ^:private two-frames [:rf/default :stories])

(defn- epoch
  "A ring record shaped enough for both tools: an id and a
  `:committed-at` inside any plausible window."
  [id]
  {:epoch-id id :committed-at (js/Date.now) :trigger-event [:noop id]})

;; ---------------------------------------------------------------------------
;; The runtime simulator — answers are DERIVED from the emitted form.
;; ---------------------------------------------------------------------------

(defn- guards-ambiguity?
  "True when `form` detects the ambiguity WHERE IT ARISES: it binds the
  resolved id, branches to `ambiguous-frame-error` on nil, and only
  then reads the ring. The ORDERING is the assertion — a form that
  merely mentions the refusal after reading would still have taken the
  wrong branch."
  [form operation]
  (let [resolve-at (str/index-of form "(let [fid (re-frame2-pair.runtime/current-frame")
        refuse-at  (str/index-of form
                                 (str "(if (nil? fid) "
                                      "(re-frame2-pair.runtime/ambiguous-frame-error "
                                      operation ")"))
        read-at    (or (str/index-of form "(re-frame2-pair.runtime/epochs-since ")
                       (str/index-of form "(re-frame2-pair.runtime/epoch-history fid)"))]
    (boolean (and resolve-at refuse-at read-at
                  (< resolve-at refuse-at read-at)))))

(defn- resolved-id
  "The id the browser-side resolver would return for `form` in a session
  holding `app-frames` with `pin` selected. Mirrors
  `pure/resolve-operating-frame`: override -> pin -> sole app frame ->
  nil. The override is read off the form because that is where the
  tool puts it — from the resolve call once the fix routes it there,
  and from the ring calls on the pre-fix shape, so this models BOTH
  trees faithfully and the explicit-frame tiers stay real controls
  rather than artefacts of the simulator."
  [form app-frames pin]
  (if-let [override (second (or (re-find #"current-frame\s+(:[^\s)]+)\)" form)
                                (re-find #"epoch-history\s+(:[^\s)]+)\)" form)
                                (re-find #"epochs-since\s+\S+\s+(:[^\s)]+)\)" form)))]
    (keyword (subs override 1))
    (or pin
        (when (= 1 (count app-frames)) (first app-frames)))))

(defn- ambiguous-envelope
  "The envelope `re-frame2-pair.runtime/ambiguous-frame-error` builds —
  reproduced here because the preload is not on this test's classpath.
  Shape per `pure/ambiguous-frame-envelope`."
  [operation app-frames pin]
  {:ok?              false
   :reason           :ambiguous-frame
   :operation        operation
   :available-frames app-frames
   :selected-frame   pin
   :hint             (str "multiple app frames are registered and no frame is "
                          "selected, so " (name operation) " cannot pick a target. "
                          "Pass `frame` (one of " (pr-str app-frames) ") or pin one with "
                          "`select-frame!` / set-operating-frame, then retry.")})

(defn- since-id-of
  "The `:since-id` / cursor `:after-id` the form asked `epochs-since`
  for, as it appears in the emitted source (`nil` when absent)."
  [form]
  (when-let [tok (second (re-find #"epochs-since\s+(\"[^\"]*\"|\S+?)[\s)]" form))]
    (cond
      (= "nil" tok)              nil
      (str/starts-with? tok "\"") (subs tok 1 (dec (count tok)))
      :else                      (js/parseInt tok 10))))

(defn- epochs-since*
  "`re-frame2-pair.runtime/epochs-since` semantics, reproduced: nil id ->
  the whole ring; a known id -> the records strictly after it; an
  UNKNOWN id -> `[]` with `:id-aged-out? true`. That last arm is the
  one that matters — on an ambiguous frame the ring is empty, so ANY
  live cursor id is unknown and the poll declares it dead."
  [history epoch-id]
  (let [head-id (some-> (peek history) :epoch-id)]
    (cond
      (nil? epoch-id)
      {:epochs history :id-aged-out? false :head-id head-id}

      (some #(= epoch-id (:epoch-id %)) history)
      {:epochs       (vec (rest (drop-while #(not= epoch-id (:epoch-id %)) history)))
       :id-aged-out? false
       :head-id      head-id}

      :else
      {:epochs [] :id-aged-out? true :head-id head-id :requested-id epoch-id})))

(defn- runtime-answer
  "Answer `form` as a live runtime would, given the session described by
  `app-frames` / `pin` / `rings` (a frame-id -> ring vector map). The
  FRAME is whatever the form resolves, which is the whole point."
  [form {:keys [operation app-frames pin rings]}]
  (let [fid (resolved-id form app-frames pin)]
    (if (and (nil? fid) (guards-ambiguity? form operation))
      (ambiguous-envelope operation app-frames pin)
      ;; No guard (or an unambiguous session): the read proceeds. For a
      ;; nil frame the per-frame lookup misses and the ring is EMPTY —
      ;; reproducing the pre-fix falsehoods exactly.
      (let [history (vec (get rings fid))]
        (if (= :trace-window operation)
          {:epochs        history
           :id-aged-out?  false
           :requested-id  nil
           :head-id       (some-> (peek history) :epoch-id)
           :next-id       nil
           :history-count (count history)
           :remaining     0}
          (let [{:keys [epochs id-aged-out? head-id requested-id]}
                (epochs-since* history (since-id-of form))]
            {:matches       epochs
             :id-aged-out?  id-aged-out?
             :requested-id  requested-id
             :head-id       head-id
             :next-id       nil
             :history-count (count history)
             :since-count   (count epochs)
             :remaining     0}))))))

(defn- stub-runtime!
  "Install the simulator. The preload sentinel is answered directly; the
  tool's own form is captured into `captured*` and answered by
  `runtime-answer`."
  [captured* session]
  (let [respond
        (fn [form]
          (cond
            (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
            (js/Promise.resolve true)

            (and (string? form) (re-find #"configure-raw-state!" form))
            (js/Promise.resolve nil)

            :else
            (do (when (and captured* (string? form))
                  (reset! captured* form))
                (js/Promise.resolve
                  (if (string? form) (runtime-answer form session) nil)))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

;; ---------------------------------------------------------------------------
;; The witnesses — these fail on the pre-fix tree.
;; ---------------------------------------------------------------------------

(deftest trace-window-ambiguous-frame-refuses-rather-than-reporting-an-empty-window
  ;; Both frames HAVE epochs. Reporting `:count 0` is therefore a
  ;; falsehood about the app, not a thin answer about a quiet one.
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames two-frames
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2)]
                                     :stories    [(epoch 7)]}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (err? r) "an unanswerable read is an isError envelope")
                   (is (= false (:ok? edn)))
                   (is (= :ambiguous-frame (:reason edn))
                       "the refusal names the ambiguity, not an empty window")
                   (is (= :trace-window (:operation edn)))
                   (is (not= 0 (:count edn))
                       "REGRESSION: epochs exist in BOTH frames — :count 0 is a falsehood")
                   (is (not (contains? edn :epochs))
                       "a refusal carries no :epochs — an empty vector reads as 'nothing there'")
                   (is (nil? (:next-cursor edn))
                       "no continuation token for a page that was never read")
                   (is (= two-frames (:available-frames edn))
                       "the candidate frames are named so the agent can choose")
                   (is (nil? (:selected-frame edn))))
                 (done))))))

(deftest watch-epochs-ambiguous-frame-refuses-rather-than-aging-out-a-live-cursor
  ;; The second falsehood, and the more damaging one: on an empty ring
  ;; the caller's live `:since-id` is simply not found, so the poll
  ;; reports the cursor DEAD.
  (async done
    (stub-runtime! nil {:operation  :watch-epochs
                        :app-frames two-frames
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2) (epoch 3)]
                                     :stories    [(epoch 7)]}})
    (-> (we/watch-epochs-tool nil (tu/args->js {:since-id 2}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (err? r))
                   (is (= false (:ok? edn)))
                   (is (= :ambiguous-frame (:reason edn))
                       "the refusal names the ambiguity")
                   (is (= :watch-epochs (:operation edn)))
                   (is (not= :rf.mcp/cursor-stale (:reason edn))
                       "REGRESSION: epoch 2 is alive in :rf/default — declaring the cursor aged out is a falsehood")
                   (is (not (true? (:id-aged-out? edn)))
                       "REGRESSION: a live cursor must not be reported dead")
                   (is (not (contains? edn :matches)))
                   (is (nil? (:next-cursor edn)))
                   (is (= two-frames (:available-frames edn))))
                 (done))))))

(deftest epoch-tool-refusals-name-the-next-action
  ;; An ambiguity error that merely says "ambiguous" is not good
  ;; ergonomics. The hint must name what to DO.
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames two-frames
                        :pin        nil
                        :rings      {}})
    (-> (tw/trace-window-tool nil (tu/args->js {}))
        (.then (fn [r]
                 (let [hint (:hint (read-edn r))]
                   (is (string? hint))
                   (is (str/includes? hint "frame") "names the `frame` arg")
                   (is (str/includes? hint "set-operating-frame")
                       "names the pin tool the agent already has")
                   (is (str/includes? hint ":stories")
                       "names the candidates inline, so choosing needs no second call"))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Where the detection sits — the branch, not the message.
;; ---------------------------------------------------------------------------

(deftest the-emitted-forms-resolve-before-they-read
  ;; Patching only the message would leave the wrong branch taken. Both
  ;; forms must bind the resolved id, refuse on nil, and only then touch
  ;; the ring.
  (async done
    (let [captured (atom nil)]
      (stub-runtime! captured {:operation  :trace-window
                               :app-frames [:rf/default]
                               :pin        nil
                               :rings      {:rf/default [(epoch 1)]}})
      (-> (tw/trace-window-tool nil (tu/args->js {}))
          (.then (fn [_]
                   (is (guards-ambiguity? @captured :trace-window)
                       "trace-window resolves, refuses on nil, THEN reads the ring")
                   (is (str/includes? @captured "(re-frame2-pair.runtime/epoch-history fid)")
                       "the history read goes against the resolved id, never implicitly")))
          (.then (fn [_]
                   (let [captured2 (atom nil)]
                     (stub-runtime! captured2 {:operation  :watch-epochs
                                               :app-frames [:rf/default]
                                               :pin        nil
                                               :rings      {:rf/default [(epoch 1)]}})
                     (-> (we/watch-epochs-tool nil (tu/args->js {}))
                         (.then (fn [_]
                                  (is (guards-ambiguity? @captured2 :watch-epochs)
                                      "watch-epochs resolves, refuses on nil, THEN polls")
                                  (is (str/includes? @captured2
                                                     "(re-frame2-pair.runtime/epochs-since nil fid)")
                                      "the poll goes against the resolved id")
                                  (is (str/includes? @captured2
                                                     "(re-frame2-pair.runtime/epoch-history fid)")
                                      "so does the advisory's history count — one resolution, one truth")
                                  (done)))))))))))

;; ---------------------------------------------------------------------------
;; Controls — the tiers that must keep working, and the genuine empties
;; the refusal must NOT swallow.
;; ---------------------------------------------------------------------------

(deftest tier-1-explicit-frame-reads-that-frame
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames two-frames
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2)]
                                     :stories    [(epoch 7)]}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :frame ":stories"}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)) "an explicit frame is answerable")
                   (is (true? (:ok? edn)))
                   (is (= 1 (:count edn)) "reads the NAMED frame's ring"))
                 (done))))))

(deftest tier-2-session-pin-reads-the-pinned-frame
  (async done
    (stub-runtime! nil {:operation  :watch-epochs
                        :app-frames two-frames
                        :pin        :stories
                        :rings      {:rf/default [(epoch 1) (epoch 2)]
                                     :stories    [(epoch 7)]}})
    (-> (we/watch-epochs-tool nil (tu/args->js {}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)) "a pinned session is answerable")
                   (is (true? (:ok? edn)))
                   (is (= 1 (:count edn)) "reads the PINNED frame's ring"))
                 (done))))))

(deftest tier-3-sole-app-frame-reads-it-without-a-pin
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames [:rf/default]
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2)]}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)) "a sole app frame is answerable with no pin")
                   (is (= 2 (:count edn))))
                 (done))))))

(deftest a-cursors-sticky-frame-outranks-the-session-pin
  ;; The care these two need that get-path did not: the frame a paginated
  ;; call is ALREADY iterating rides in the cursor, and page 2 must stay
  ;; on it even when the session was pinned elsewhere in between.
  (async done
    (let [c (cursor/encode-cursor {:v 1 :after-id 1 :ms 60000
                                   :until-ms (+ (js/Date.now) 1000)
                                   :frame :stories})]
      (stub-runtime! nil {:operation  :trace-window
                          :app-frames two-frames
                          :pin        :rf/default
                          :rings      {:rf/default [(epoch 1) (epoch 2)]
                                       :stories    [(epoch 7)]}})
      (-> (tw/trace-window-tool nil (tu/args->js {:cursor c}))
          (.then (fn [r]
                   (let [edn (read-edn r)]
                     (is (not (err? r)))
                     (is (= 1 (:count edn))
                         "page 2 stayed on the cursor's frame, not the session pin"))
                   (done)))))))

(deftest a-genuinely-empty-ring-in-a-resolvable-frame-still-answers
  ;; The complement, and the reason the refusal is scoped to tier 4: an
  ;; honestly quiet frame must still get its honest `:count 0`, not a
  ;; refusal. Over-firing here would trade one falsehood for another.
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames [:rf/default]
                        :pin        nil
                        :rings      {:rf/default []}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)) "a resolvable but quiet frame is not a refusal")
                   (is (true? (:ok? edn)))
                   (is (= 0 (:count edn)) "and it still says zero, honestly"))
                 (done))))))
