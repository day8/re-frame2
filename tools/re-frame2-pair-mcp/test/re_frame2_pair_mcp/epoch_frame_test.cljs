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
  fail on the pre-fix tree rather than merely assert the fixed shape.

  ## And what the refusal does NOT reach

  Both tools are cursor-paged, so resolving page 1 correctly is only
  half the contract. The resolved id has to travel BACK out of the eval
  and into `:next-cursor`, because page 1 typically names no frame:
  a cursor built from the ARGUMENTS stores nil, and page 2 — naming no
  frame either — re-resolves against whatever the session says by then.

  The refusal cannot cover that second call. By page 2 there is usually
  nothing to refuse: pin a frame, or register a second one, and the
  session resolves cleanly — to a DIFFERENT ring, in which the live
  `:after-id` is absent, so `epochs-since` calls the healthy cursor
  aged out. Same falsehood as the original bug, reached from the other
  side. The `## Cursor frame ownership` witnesses below drive real
  page-1-generated cursors through exactly those two session changes;
  the simulator answers `:frame fid` and pages at the emitted `(take
  N …)` so a page-1 cursor exists at all."
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

(defn- read-token
  "An emitted scalar literal back as a value. Epoch-ids are `:any` per
  the schema; the reference runtime emits integers, and the tools also
  ship string ids, so both shapes round-trip here."
  [tok]
  (cond
    (nil? tok)                  nil
    (= "nil" tok)               nil
    (str/starts-with? tok "\"") (subs tok 1 (dec (count tok)))
    :else                       (js/parseInt tok 10)))

(defn- since-id-of
  "The `:since-id` / cursor `:after-id` the form asked `epochs-since`
  for, as it appears in the emitted source (`nil` when absent)."
  [form]
  (read-token (second (re-find #"epochs-since\s+(\"[^\"]*\"|\S+?)[\s)]" form))))

(defn- after-id-of
  "`trace-window`'s cursor watermark, read off the `after-id` LET
  BINDING the tool emits (`(let [hist … after-id 7 …] …)`). The later
  textual uses of the name are expressions (`(and after-id …)`), which
  the literal alternation cannot match, so the binding is the only hit."
  [form]
  (read-token (second (re-find #"\bafter-id (nil|\d+|\"[^\"]*\")" form))))

(defn- limit-of
  "The page size the form asked for — the `(take N …)` both tools write
  from their `:limit` arg. Paging is simulated because a cursor only
  EXISTS when a page is capped, and a page-1 cursor is what the
  ownership witnesses below are about."
  [form]
  (or (some-> (second (re-find #"\(take (\d+) " form)) (js/parseInt 10)) 50))

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
  FRAME is whatever the form resolves, which is the whole point.

  Both arms mirror the emitted form's own slice-then-cap pipeline and
  report `:frame fid` — the id the read RESOLVED, which is the slot the
  tool builds `:next-cursor` from. The time filter `trace-window` emits
  between them is a no-op here by construction: every fixture epoch is
  stamped `(js/Date.now)` and every fixture window is 60s wide, so
  `filtered` = `sliced` and paging is the only cap that bites."
  [form {:keys [operation app-frames pin rings]}]
  (let [fid (resolved-id form app-frames pin)]
    (if (and (nil? fid) (guards-ambiguity? form operation))
      (ambiguous-envelope operation app-frames pin)
      ;; No guard (or an unambiguous session): the read proceeds. For a
      ;; nil frame the per-frame lookup misses and the ring is EMPTY —
      ;; reproducing the pre-fix falsehoods exactly.
      (let [history (vec (get rings fid))
            limit   (limit-of form)]
        (if (= :trace-window operation)
          (let [after-id  (after-id-of form)
                aged-out? (boolean (and after-id
                                        (not-any? #(= after-id (:epoch-id %)) history)))
                filtered  (cond
                            aged-out? []
                            after-id  (vec (rest (drop-while #(not= after-id (:epoch-id %))
                                                             history)))
                            :else     history)
                page      (vec (take limit filtered))]
            {:epochs        page
             :id-aged-out?  aged-out?
             :requested-id  after-id
             :head-id       (some-> (peek history) :epoch-id)
             :next-id       (when (< (count page) (count filtered))
                              (:epoch-id (last page)))
             :history-count (count history)
             :frame         fid
             :remaining     (max 0 (- (count filtered) (count page)))})
          (let [{:keys [epochs id-aged-out? head-id requested-id]}
                (epochs-since* history (since-id-of form))
                page (vec (take limit epochs))]
            {:matches       page
             :id-aged-out?  id-aged-out?
             :requested-id  requested-id
             :head-id       head-id
             :next-id       (when (< (count page) (count epochs))
                              (:epoch-id (last page)))
             :history-count (count history)
             :since-count   (count epochs)
             :frame         fid
             :remaining     (max 0 (- (count epochs) (count page)))}))))))

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
    ;; Epoch 1 is live in BOTH rings, so the watermark resolves either
    ;; way and the count is what discriminates: one epoch follows it in
    ;; `:stories`, two in `:rf/default`. A cursor that fell through to
    ;; the pin would answer 2.
    (let [c (cursor/encode-cursor {:v 1 :after-id 1 :ms 60000
                                   :until-ms (+ (js/Date.now) 1000)
                                   :frame :stories})]
      (stub-runtime! nil {:operation  :trace-window
                          :app-frames two-frames
                          :pin        :rf/default
                          :rings      {:rf/default [(epoch 1) (epoch 2) (epoch 3)]
                                       :stories    [(epoch 1) (epoch 7)]}})
      (-> (tw/trace-window-tool nil (tu/args->js {:cursor c}))
          (.then (fn [r]
                   (let [edn (read-edn r)]
                     (is (not (err? r)))
                     (is (= 1 (:count edn))
                         "page 2 stayed on the cursor's frame, not the session pin"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; ## Cursor frame ownership — the residual the refusal does not reach.
;; ---------------------------------------------------------------------------
;;
;; The refusal above answers page 1's ambiguity. It cannot answer page 2's,
;; because by page 2 there is usually no ambiguity left to refuse: the
;; session resolves a frame perfectly well — just not the one page 1 read.
;; So the cursor has to CARRY the resolved id, and page 1 is the only call
;; that can put it there.
;;
;; `a-cursors-sticky-frame-outranks-the-session-pin` above proves a cursor
;; that ALREADY owns a frame is honoured, but it hand-builds that cursor, so
;; it never exercised CREATION — the half where the id has to come back out
;; of the eval. Every cursor below is one the tool itself returned.

(defn- cursor-payload
  "The `:next-cursor` a tool returned, decoded back to its payload map."
  [r]
  (cursor/decode-cursor (:next-cursor (read-edn r))))

(deftest the-emitted-forms-carry-the-resolved-id-back
  ;; The cursor can only own what the eval hands back. Both inner forms
  ;; put the resolved binding in their result map, beside the page.
  (async done
    (let [captured (atom nil)]
      (stub-runtime! captured {:operation  :trace-window
                               :app-frames [:rf/default]
                               :pin        nil
                               :rings      {:rf/default [(epoch 1)]}})
      (-> (tw/trace-window-tool nil (tu/args->js {}))
          (.then (fn [_]
                   (is (str/includes? @captured ":frame fid")
                       "trace-window reports the id it read against, not the one it was asked for")))
          (.then (fn [_]
                   (let [captured2 (atom nil)]
                     (stub-runtime! captured2 {:operation  :watch-epochs
                                               :app-frames [:rf/default]
                                               :pin        nil
                                               :rings      {:rf/default [(epoch 1)]}})
                     (-> (we/watch-epochs-tool nil (tu/args->js {}))
                         (.then (fn [_]
                                  (is (str/includes? @captured2 ":frame fid")
                                      "so does watch-epochs")
                                  (done)))))))))))

(deftest trace-window-page-1-cursor-owns-the-pin-it-resolved
  ;; Tier 2. Page 1 names no frame, so pre-fix the cursor stored nil and
  ;; page 2 re-resolved — landing on whatever the pin said by then.
  (async done
    (let [session {:operation  :trace-window
                   :app-frames two-frames
                   :pin        :stories
                   :rings      {:rf/default [(epoch 1) (epoch 2) (epoch 3) (epoch 4)]
                                :stories    [(epoch 7) (epoch 8) (epoch 9)]}}]
      (stub-runtime! nil session)
      (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :limit 1}))
          (.then (fn [r1]
                   (let [c (cursor-payload r1)]
                     (is (= :stories (:frame c))
                         "REGRESSION: the cursor must carry the id the PIN resolved, not the nil it was asked for")
                     (is (= 7 (:after-id c)) "and the watermark it actually emitted")
                     ;; The session moves under the agent, mid-pagination.
                     (stub-runtime! nil (assoc session :pin :rf/default))
                     (tw/trace-window-tool
                       nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1})))))
          (.then (fn [r2]
                   (let [edn (read-edn r2)]
                     (is (not (err? r2))
                         "REGRESSION: a re-pinned session must not capture the continuation")
                     (is (not= :rf.mcp/cursor-stale (:reason edn))
                         "REGRESSION: epoch 7 is alive in :stories — the cursor is not stale")
                     (is (= 1 (:count edn)) "page 2 read the ORIGINAL ring")
                     (is (= :stories (:frame (cursor-payload r2)))
                         "and page 3 inherits the same ownership"))
                   (done)))))))

(deftest trace-window-page-2-survives-a-second-frame-registering
  ;; Tier 3, and the sharper half: the session does not merely move, it
  ;; becomes AMBIGUOUS. A fresh call would now be refused — an owned
  ;; cursor must not be, because it already knows its ring.
  (async done
    (let [ring {:rf/default [(epoch 1) (epoch 2) (epoch 3)]}]
      (stub-runtime! nil {:operation  :trace-window
                          :app-frames [:rf/default]
                          :pin        nil
                          :rings      ring})
      (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :limit 1}))
          (.then (fn [r1]
                   (is (= :rf/default (:frame (cursor-payload r1)))
                       "REGRESSION: the SOLE app frame is the id the read resolved; a namespaced keyword round-trips the codec")
                   (stub-runtime! nil {:operation  :trace-window
                                       :app-frames two-frames
                                       :pin        nil
                                       :rings      (assoc ring :stories [(epoch 7)])})
                   (tw/trace-window-tool
                     nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1}))))
          (.then (fn [r2]
                   (let [edn (read-edn r2)]
                     (is (not (err? r2))
                         "REGRESSION: an owned cursor stays answerable where a FRESH call would now be ambiguous")
                     (is (not= :ambiguous-frame (:reason edn)))
                     (is (= 1 (:count edn)) "and it read the frame it started on"))
                   (done)))))))

(deftest watch-epochs-page-1-cursor-owns-the-pin-it-resolved
  ;; The same creation defect on the tool where losing the ring is worst:
  ;; a poll that re-resolves cannot find the caller's id in the new ring,
  ;; so it reports the LIVE cursor dead — the exact falsehood this tool's
  ;; frame refusal exists to stop, reached by a second route.
  (async done
    (let [session {:operation  :watch-epochs
                   :app-frames two-frames
                   :pin        :stories
                   :rings      {:rf/default [(epoch 1) (epoch 2) (epoch 3) (epoch 4)]
                                :stories    [(epoch 7) (epoch 8) (epoch 9)]}}]
      (stub-runtime! nil session)
      (-> (we/watch-epochs-tool nil (tu/args->js {:limit 1}))
          (.then (fn [r1]
                   (let [c (cursor-payload r1)]
                     (is (= :stories (:frame c))
                         "REGRESSION: the cursor must carry the id the PIN resolved, not nil")
                     (is (= 7 (:after-id c)))
                     (stub-runtime! nil (assoc session :pin :rf/default))
                     (we/watch-epochs-tool
                       nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1})))))
          (.then (fn [r2]
                   (let [edn (read-edn r2)]
                     (is (not (err? r2))
                         "REGRESSION: the re-pinned session must not capture the poll")
                     (is (not= :rf.mcp/cursor-stale (:reason edn))
                         "REGRESSION: epoch 7 is alive in :stories — the cursor is not stale")
                     (is (not (true? (:id-aged-out? edn)))
                         "REGRESSION: a live cursor must not be reported dead")
                     (is (= 1 (:count edn)) "page 2 polled the ORIGINAL ring"))
                   (done)))))))

(deftest watch-epochs-page-2-survives-a-second-frame-registering
  (async done
    (let [ring {:rf/default [(epoch 1) (epoch 2) (epoch 3)]}]
      (stub-runtime! nil {:operation  :watch-epochs
                          :app-frames [:rf/default]
                          :pin        nil
                          :rings      ring})
      (-> (we/watch-epochs-tool nil (tu/args->js {:limit 1}))
          (.then (fn [r1]
                   (is (= :rf/default (:frame (cursor-payload r1)))
                       "REGRESSION: the sole app frame is what the cursor owns")
                   (stub-runtime! nil {:operation  :watch-epochs
                                       :app-frames two-frames
                                       :pin        nil
                                       :rings      (assoc ring :stories [(epoch 7)])})
                   (we/watch-epochs-tool
                     nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1}))))
          (.then (fn [r2]
                   (let [edn (read-edn r2)]
                     (is (not (err? r2))
                         "REGRESSION: an owned cursor stays answerable where a FRESH poll would now be ambiguous")
                     (is (not= :ambiguous-frame (:reason edn)))
                     (is (not (true? (:id-aged-out? edn))))
                     (is (= 1 (:count edn))))
                   (done)))))))

(deftest an-explicit-frame-is-the-id-the-cursor-owns
  ;; Control: tier 1 still outranks the pin, and it is the RESOLVED id
  ;; — not the raw argument — that the cursor stores. The two agree
  ;; here, which is the point: the fix must not move an explicit target.
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames two-frames
                        :pin        :rf/default
                        :rings      {:rf/default [(epoch 1) (epoch 2)]
                                     :stories    [(epoch 7) (epoch 8) (epoch 9)]}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :limit 1 :frame ":stories"}))
        (.then (fn [r]
                 (is (= :stories (:frame (cursor-payload r)))
                     "the explicit frame, not the session pin, is what the cursor owns")
                 (done))))))

(deftest an-advisory-names-the-frame-its-count-came-from
  ;; The other half of the same seam. The advisory reports `:frame`, and
  ;; pre-fix it reported the ASKED-for nil while quoting a history count
  ;; read from a real frame — a sentence about a frame the call never
  ;; named.
  (async done
    (let [ring (mapv epoch (range 1 10))]
      (stub-runtime! nil {:operation  :watch-epochs
                          :app-frames [:step-deck]
                          :pin        nil
                          :rings      {:step-deck ring}})
      (-> (we/watch-epochs-tool nil (tu/args->js {:since-id 9}))
          (.then (fn [r]
                   (let [advisory (:advisory (read-edn r))]
                     (is (= :no-events-since-id (:reason advisory)))
                     (is (= 9 (:epochs-in-history advisory)))
                     (is (= :step-deck (:frame advisory))
                         "REGRESSION: the advisory names the resolved frame its count came from, not nil"))
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
