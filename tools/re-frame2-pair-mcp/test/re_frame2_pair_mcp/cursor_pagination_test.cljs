(ns re-frame2-pair-mcp.cursor-pagination-test
  "Unit tests for the cursor-pagination mechanism on `trace-window`
  and `watch-epochs`.

  Both tools accept `:limit` (int, default 50) + `:cursor` (opaque
  string). Responses carry `:next-cursor`, `:has-more?` and
  `:estimated-remaining`. A cursor whose epoch-id has aged out of the
  runtime ring surfaces as a `:rf.mcp/cursor-stale` error rather than
  silently restarting.

  These tests pin the private helpers in
  `re-frame2-pair-mcp.tools.cursor` directly (`parse-limit-arg`,
  `encode-cursor`, `decode-cursor`, `cursor-stale-result`) and exercise
  the full host-side pagination logic by simulating the runtime form's
  output. The live runtime call sits behind the nREPL eval boundary
  and is covered by the stdio-roundtrip harness; this layer pins the
  cursor contract."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]))

(def default-limit cursor/default-limit)

;; The `decode-cursor` impl returns the malformed sentinel keyword
;; from the source ns; tests assert against that same value.
(def malformed :re-frame2-pair-mcp.tools.cursor/malformed)

;; ---------------------------------------------------------------------------
;; parse-limit-arg — MCP-arg normalisation.
;; ---------------------------------------------------------------------------

(deftest parse-limit-default-when-absent
  (is (= default-limit (cursor/parse-limit-arg nil)))
  (is (= default-limit (cursor/parse-limit-arg js/undefined))))

(deftest parse-limit-positive-integer-pass-through
  (is (= 10 (cursor/parse-limit-arg 10)))
  (is (= 1  (cursor/parse-limit-arg 1)))
  (is (= 1000 (cursor/parse-limit-arg 1000))))

(deftest parse-limit-clamps-non-positive-to-one
  (is (= 1 (cursor/parse-limit-arg 0)))
  (is (= 1 (cursor/parse-limit-arg -5))))

(deftest parse-limit-parses-numeric-string
  (is (= 25 (cursor/parse-limit-arg "25"))))

(deftest parse-limit-non-numeric-string-falls-back
  (is (= default-limit (cursor/parse-limit-arg "bogus"))))

;; ---------------------------------------------------------------------------
;; encode-cursor / decode-cursor — opaque round-trip.
;; ---------------------------------------------------------------------------

(deftest encode-cursor-nil-when-no-after-id
  (is (nil? (cursor/encode-cursor nil)))
  (is (nil? (cursor/encode-cursor {})))
  (is (nil? (cursor/encode-cursor {:v 1 :after-id nil}))))

(deftest encode-cursor-returns-string-on-valid-payload
  (let [c (cursor/encode-cursor {:v 1 :after-id "abc"})]
    (is (string? c))
    (is (pos? (count c)))))

(deftest cursor-round-trip-preserves-payload
  (let [payload {:v 1 :after-id "epoch-42" :ms 5000 :until-ms 1234567890
                 :frame :rf/default}
        encoded (cursor/encode-cursor payload)
        decoded (cursor/decode-cursor encoded)]
    (is (= payload decoded))))

;; ---------------------------------------------------------------------------
;; INTEGER epoch-ids (real-runtime fidelity).
;;
;; The reference epoch runtime (`re-frame/epoch/state.cljc`
;; `next-epoch-id` = `(swap! counter inc)`) emits INTEGER epoch-ids, and
;; Spec-Schemas declares `:epoch-id` as `:any`. `decode-cursor` must
;; accept any `:after-id` shape: an integer-bearing second-page cursor
;; round-trips intact rather than decoding as `::malformed` and tripping
;; a spurious `:rf.mcp/cursor-stale`. These pin the contract to `:any` —
;; the tests use REAL integer ids, not synthesised strings, so a
;; regression to a `string?` guard fails here.
;; ---------------------------------------------------------------------------

(deftest cursor-round-trips-integer-after-id
  ;; The headline fix: an integer :after-id survives encode→decode and
  ;; is NOT rejected as malformed.
  (let [payload {:v 1 :after-id 7 :ms 1000 :until-ms 1234567890 :frame :rf/default}
        encoded (cursor/encode-cursor payload)
        decoded (cursor/decode-cursor encoded)]
    (is (string? encoded) "encode emits a token for an integer after-id")
    (is (not= malformed decoded) "integer after-id must NOT decode as malformed")
    (is (= payload decoded) "the integer after-id round-trips losslessly")
    (is (integer? (:after-id decoded)) "after-id stays an integer, not coerced to string")
    (is (= 7 (:after-id decoded)))))

(deftest cursor-round-trips-keyword-after-id
  ;; :epoch-id is :any — a keyword id (a less-common but legal opaque
  ;; shape) must also survive.
  (let [payload {:v 1 :after-id :ev/login :ms nil :until-ms nil :frame nil}
        decoded (cursor/decode-cursor (cursor/encode-cursor payload))]
    (is (not= malformed decoded))
    (is (= :ev/login (:after-id decoded)))))

(deftest encode-cursor-emits-token-for-integer-after-id
  ;; encode-cursor's "is there an after-id?" guard must accept an
  ;; integer (not silently drop it as it would if it required string?).
  (is (string? (cursor/encode-cursor {:v 1 :after-id 0}))
      "integer 0 is a valid after-id (some?, not string?)")
  (is (string? (cursor/encode-cursor {:v 1 :after-id 42}))))

(deftest decode-cursor-nil-on-nil-input
  (is (nil? (cursor/decode-cursor nil)))
  (is (nil? (cursor/decode-cursor "")))
  (is (nil? (cursor/decode-cursor js/undefined))))

(deftest decode-cursor-malformed-on-junk
  (is (= malformed (cursor/decode-cursor "not-real-base64-edn-juzlblahHFGYbn")))
  (is (= malformed (cursor/decode-cursor 12345)))
  ;; base64 of "not-a-map" — decodes but isn't a map with :after-id
  (let [bogus (.toString (js/Buffer.from "[1 2 3]" "utf8") "base64")]
    (is (= malformed (cursor/decode-cursor bogus)))))

(deftest decode-cursor-malformed-on-missing-after-id
  (let [bogus (.toString (js/Buffer.from "{:v 1}" "utf8") "base64")]
    (is (= malformed (cursor/decode-cursor bogus)))))

(deftest cursor-is-opaque-on-wire
  ;; The agent has no business decoding the cursor — but the encoding
  ;; MUST be a self-contained string (no embedded JSON-confusing chars
  ;; that would break round-trip through bencode + JSON-RPC).
  (let [c (cursor/encode-cursor {:v 1 :after-id "abc-def"})]
    (is (re-matches #"^[A-Za-z0-9+/=]+$" c))))

;; ---------------------------------------------------------------------------
;; First-page (no cursor) — the bead's acceptance scenario #1.
;; ---------------------------------------------------------------------------
;;
;; Simulates the host-side post-processing of the runtime form's
;; return value. The runtime form returns
;;   {:epochs <slice> :id-aged-out? bool :head-id <id> :next-id <id> :remaining N}
;; The host wraps that into the final MCP response.

(defn- make-epoch [n]
  {:epoch-id     (str "epoch-" n)
   :committed-at (+ 1000000 n)
   :event-id     :test/ev
   :db-before    {}
   :db-after     {}})

(defn- runtime-form-output
  "Simulate what the inlined eval form in `trace-window-tool` returns
  after server-side slicing. `history` is the full epoch-history vec
  for the frame; `after-id` is the cursor's after-id (or nil for the
  first call); `cutoff-ms`/`until-ms` are the window bounds; `limit`
  caps the page."
  [history {:keys [after-id cutoff-ms until-ms limit]}]
  (let [aged-out? (and after-id (not-any? #(= after-id (:epoch-id %)) history))
        sliced    (cond
                    aged-out?    []
                    after-id     (vec (rest (drop-while #(not= after-id (:epoch-id %))
                                                        history)))
                    :else        history)
        filtered  (filterv #(and (>= (or (:committed-at %) 0) (or cutoff-ms 0))
                                 (<= (or (:committed-at %) 0) (or until-ms (js/Date.now))))
                           sliced)
        page      (vec (take limit filtered))
        next-id   (when (< (count page) (count filtered))
                    (:epoch-id (last page)))]
    {:epochs page
     :id-aged-out? aged-out?
     :requested-id after-id
     :head-id      (some-> history peek :epoch-id)
     :next-id      next-id
     :remaining    (max 0 (- (count filtered) (count page)))}))

(deftest first-page-no-cursor-returns-limit-records
  (let [;; 50-epoch ring; ask for page 1 with limit 10
        hist (vec (for [n (range 50)] (make-epoch n)))
        out  (runtime-form-output hist {:after-id nil
                                        :cutoff-ms 0
                                        :until-ms 99999999
                                        :limit 10})]
    (is (= 10 (count (:epochs out))))
    (is (false? (boolean (:id-aged-out? out))))
    (is (= "epoch-9" (:next-id out)))
    (is (= 40 (:remaining out)))))

(deftest first-page-no-cursor-honours-default-limit
  ;; 30 epochs, default limit 50 → all 30 fit, no next-id.
  (let [hist (vec (for [n (range 30)] (make-epoch n)))
        out  (runtime-form-output hist {:after-id nil
                                        :cutoff-ms 0
                                        :until-ms 99999999
                                        :limit default-limit})]
    (is (= 30 (count (:epochs out))))
    (is (nil? (:next-id out)))
    (is (zero? (:remaining out)))))

;; ---------------------------------------------------------------------------
;; Second-page (with cursor) — the bead's acceptance scenario #2.
;; ---------------------------------------------------------------------------

(deftest second-page-with-cursor-resumes-after-watermark
  (let [hist (vec (for [n (range 50)] (make-epoch n)))
        ;; First call: limit 10 ⇒ next-id "epoch-9"
        page-1 (runtime-form-output hist {:after-id nil
                                          :cutoff-ms 0
                                          :until-ms 99999999
                                          :limit 10})
        ;; Cursor encodes "epoch-9"
        cursor-str (cursor/encode-cursor {:v 1 :after-id (:next-id page-1)
                                          :until-ms 99999999 :ms nil :frame nil})
        decoded (cursor/decode-cursor cursor-str)
        ;; Second call resumes after "epoch-9"
        page-2 (runtime-form-output hist {:after-id (:after-id decoded)
                                          :cutoff-ms 0
                                          :until-ms 99999999
                                          :limit 10})]
    (is (= 10 (count (:epochs page-2))))
    (is (= "epoch-10" (-> page-2 :epochs first :epoch-id)))
    (is (= "epoch-19" (-> page-2 :epochs last :epoch-id)))
    (is (= "epoch-19" (:next-id page-2)))
    (is (= 30 (:remaining page-2)))))

(deftest five-page-walk-over-50-epoch-ring-returns-distinct-records-in-order
  ;; Bead acceptance: "50-epoch ring; pagination over 5 batches returns
  ;; 50 distinct records in order."
  (let [hist (vec (for [n (range 50)] (make-epoch n)))
        walk (loop [cursor-str nil
                    pages      []
                    safety     10]
               (if (zero? safety)
                 pages
                 (let [decoded (cursor/decode-cursor cursor-str)
                       after-id (:after-id decoded)
                       out (runtime-form-output hist {:after-id after-id
                                                      :cutoff-ms 0
                                                      :until-ms 99999999
                                                      :limit 10})
                       pages' (conj pages (:epochs out))]
                   (if-let [next-id (:next-id out)]
                     (recur (cursor/encode-cursor {:v 1 :after-id next-id
                                                   :until-ms 99999999 :ms nil :frame nil})
                            pages'
                            (dec safety))
                     pages'))))
        all-records (vec (mapcat identity walk))]
    (is (= 5 (count walk))
        "Exactly 5 pages of 10")
    (is (= 50 (count all-records))
        "50 records walked total")
    (is (apply distinct? (map :epoch-id all-records))
        "All epoch-ids distinct")
    (is (= (map :epoch-id hist) (map :epoch-id all-records))
        "Records returned in original order")))

;; ---------------------------------------------------------------------------
;; Second-page pagination with REAL integer epoch-ids.
;;
;; The runtime emits integer epoch-ids, so the cursor carries an integer
;; :after-id and the second-page decode must accept it (never `::malformed`).
;; The `make-int-epoch` helper builds records keyed by INTEGER ids
;; (mirroring `re-frame/epoch/state.cljc`'s `(swap! counter inc)`), so
;; this walks the exact shape the real runtime produces.
;; ---------------------------------------------------------------------------

(defn- make-int-epoch [n]
  {:epoch-id     n                       ; INTEGER id, like the real runtime
   :committed-at (+ 1000000 n)
   :event-id     :test/ev
   :db-before    {}
   :db-after     {}})

(deftest second-page-with-integer-cursor-resumes-after-watermark
  (let [hist (vec (for [n (range 50)] (make-int-epoch n)))
        ;; First call: limit 10 ⇒ next-id is the INTEGER 9
        page-1 (runtime-form-output hist {:after-id nil
                                          :cutoff-ms 0
                                          :until-ms 99999999
                                          :limit 10})
        next-id (:next-id page-1)
        ;; The cursor carries the integer id — the exact shape the prior
        ;; string? guard rejected on decode.
        cursor-str (cursor/encode-cursor {:v 1 :after-id next-id
                                          :until-ms 99999999 :ms nil :frame nil})
        decoded (cursor/decode-cursor cursor-str)
        page-2 (runtime-form-output hist {:after-id (:after-id decoded)
                                          :cutoff-ms 0
                                          :until-ms 99999999
                                          :limit 10})]
    (is (= 9 next-id) "first-page next-id is the integer 9")
    (is (not= malformed decoded) "the integer-bearing cursor is NOT stale")
    (is (= 9 (:after-id decoded)) "decoded after-id stays an integer")
    (is (= 10 (count (:epochs page-2))))
    (is (= 10 (-> page-2 :epochs first :epoch-id)) "page 2 resumes at integer 10")
    (is (= 19 (-> page-2 :epochs last :epoch-id)))
    (is (= 19 (:next-id page-2)))
    (is (= 30 (:remaining page-2)))))

(deftest five-page-walk-over-integer-id-ring-returns-distinct-records-in-order
  ;; The full acceptance walk with REAL integer ids — pagination over 5
  ;; batches returns 50 distinct integer-keyed records in order. A
  ;; page-2 cursor decoding as stale would abort the walk after page 1;
  ;; this guards against that.
  (let [hist (vec (for [n (range 50)] (make-int-epoch n)))
        walk (loop [cursor-str nil
                    pages      []
                    safety     10]
               (if (zero? safety)
                 pages
                 (let [decoded (cursor/decode-cursor cursor-str)
                       _       (when (= malformed decoded)
                                 (throw (ex-info "integer cursor decoded as malformed — the rf2-ee38b.18 regression"
                                                 {:cursor cursor-str})))
                       after-id (:after-id decoded)
                       out (runtime-form-output hist {:after-id after-id
                                                      :cutoff-ms 0
                                                      :until-ms 99999999
                                                      :limit 10})
                       pages' (conj pages (:epochs out))]
                   (if-let [next-id (:next-id out)]
                     (recur (cursor/encode-cursor {:v 1 :after-id next-id
                                                   :until-ms 99999999 :ms nil :frame nil})
                            pages'
                            (dec safety))
                     pages'))))
        all-records (vec (mapcat identity walk))]
    (is (= 5 (count walk)) "exactly 5 pages of 10")
    (is (= 50 (count all-records)) "50 records walked total")
    (is (apply distinct? (map :epoch-id all-records)) "all integer epoch-ids distinct")
    (is (= (map :epoch-id hist) (map :epoch-id all-records)) "records returned in original integer order")
    (is (every? integer? (map :epoch-id all-records)) "all ids stayed integers across the walk")))

;; ---------------------------------------------------------------------------
;; Stale cursor — the bead's acceptance scenario #3.
;; ---------------------------------------------------------------------------

(deftest stale-cursor-trips-id-aged-out
  ;; Cursor references "epoch-99" but the ring only has "epoch-0".."epoch-49".
  (let [hist (vec (for [n (range 50)] (make-epoch n)))
        out  (runtime-form-output hist {:after-id "epoch-99"
                                        :cutoff-ms 0
                                        :until-ms 99999999
                                        :limit 10})]
    (is (true? (boolean (:id-aged-out? out))))
    (is (empty? (:epochs out)))
    (is (nil? (:next-id out)))))

(deftest stale-cursor-also-detected-after-buffer-rotation
  ;; Initial ring has "epoch-0".."epoch-9"; agent gets cursor pointing
  ;; at "epoch-5". Buffer then rotates so head moves to "epoch-100";
  ;; "epoch-5" no longer present.
  (let [hist-old (vec (for [n (range 10)] (make-epoch n)))
        first-page (runtime-form-output hist-old {:after-id nil
                                                  :cutoff-ms 0
                                                  :until-ms 99999999
                                                  :limit 5})
        next-id (:next-id first-page)
        ;; Ring rotates — old epochs gone, new ones in.
        hist-new (vec (for [n (range 100 110)] (make-epoch n)))
        page-2 (runtime-form-output hist-new {:after-id next-id
                                              :cutoff-ms 0
                                              :until-ms 99999999
                                              :limit 5})]
    (is (= "epoch-4" next-id))
    (is (true? (boolean (:id-aged-out? page-2))))
    (is (empty? (:epochs page-2)))))

(deftest malformed-cursor-decodes-as-malformed-sentinel
  ;; Caller passes garbage. Host translates malformed sentinel to the
  ;; same :rf.mcp/cursor-stale error path as a true age-out.
  (is (= malformed (cursor/decode-cursor "not-base64-edn")))
  (is (= malformed (cursor/decode-cursor "AAAA"))))

;; ---------------------------------------------------------------------------
;; Sticky window — fresh epochs don't sneak in mid-iteration.
;; ---------------------------------------------------------------------------

(deftest sticky-until-ms-bounds-window-across-pages
  ;; Page 1 fetches at time T; new epochs land BEFORE page 2 fetches.
  ;; The cursor's `:until-ms` clamps the second page to the original T.
  (let [hist-t1   (vec (for [n (range 50)] (make-epoch n)))
        ;; At time T1, until-ms = max committed-at = 1000049
        until-t1  1000049
        page-1    (runtime-form-output hist-t1 {:after-id nil
                                                :cutoff-ms 0
                                                :until-ms until-t1
                                                :limit 10})
        ;; History grows with 5 fresh epochs while agent is thinking.
        hist-t2   (into hist-t1 (for [n (range 100 105)] (make-epoch n)))
        ;; Second call passes the SAME until-ms (sticky from cursor).
        page-2    (runtime-form-output hist-t2 {:after-id (:next-id page-1)
                                                :cutoff-ms 0
                                                :until-ms until-t1
                                                :limit 100})
        all-ids   (set (map :epoch-id (concat (:epochs page-1) (:epochs page-2))))]
    (is (not (contains? all-ids "epoch-100"))
        "Fresh epochs landing after first call's clock don't appear in page 2")
    (is (not (contains? all-ids "epoch-104")))
    (is (= 50 (count all-ids))
        "Page 1 + page 2 = original 50 records, no admission of fresh ones")))

;; ---------------------------------------------------------------------------
;; watch-epochs carries the sticky :pred in the continuation cursor.
;;
;; `watch-epochs`'s first-call `:next-cursor` encodes :after-id / :frame
;; AND the predicate. An agent paginating via the documented opaque-cursor
;; flow (pass back JUST `:cursor`, no `:pred`) keeps both the sticky frame
;; and the predicate, so page 2+ runs `epoch-matches?` with the original
;; predicate rather than degrading to `{}` (MATCH-ALL) — which would
;; return every epoch after the watermark unfiltered, with an envelope
;; identical to a correctly-filtered page so the agent couldn't detect it.
;;
;; These pins exercise BOTH ends of the round-trip:
;;   1. The first-call `:next-cursor` actually carries the predicate.
;;   2. A page-2 call with ONLY `:cursor` (no `:pred` arg) still emits a
;;      filtering `epoch-matches?` form — the sticky pred from the cursor,
;;      NOT the match-all `{}`.
;; ---------------------------------------------------------------------------

(defn- with-form-capture!
  "Install a `nrepl/cljs-eval-value` stub that answers the runtime
  preload-probe with `true` and the real epoch form with `canned`,
  capturing the NON-probe (epoch) form-strings into `forms-atom`.
  Restores the original in `.finally`. Mirrors the substr-matching
  installer in `watch_epochs_test`; here we additionally need the form
  string, not just the value. The probe form is identified by the
  `__re_frame2_pair_runtime` marker so it is neither captured nor
  answered with the matches map (which `runtime-preloaded?` would read
  as a failed probe)."
  [forms-atom canned body-fn]
  (let [orig  nrepl/cljs-eval-value
        probe? (fn [form-str]
                 (and (string? form-str)
                      (re-find #"__re_frame2_pair_runtime" form-str)))
        answer (fn [form-str]
                 (if (probe? form-str)
                   true
                   (do (swap! forms-atom conj form-str)
                       canned)))
        stub  (fn
                ([_conn _build-id form-str]
                 (js/Promise.resolve (answer form-str)))
                ([_conn _build-id form-str _opts]
                 (js/Promise.resolve (answer form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest watch-epochs-next-cursor-carries-pred
  (testing "first-call :next-cursor round-trips the :pred so page 2 can resume filtered"
    (async done
      (let [forms (atom [])
            ;; Runtime form output: a page with a :next-id so a
            ;; continuation cursor is minted.
            canned {:matches       [{:epoch-id :e1}]
                    :id-aged-out?  false
                    :requested-id  nil
                    :head-id       :e2
                    :next-id       :e1
                    :history-count 5
                    :since-count   5
                    :remaining     4}]
        (-> (with-form-capture! forms canned
              (fn []
                (-> (we/watch-epochs-tool
                      nil
                      (tu/args->js {:pred #js {:event-id ":ev/login"} :limit 1}))
                    (.then (fn [result]
                             (let [edn         (tu/extract-edn result)
                                   next-cursor (:next-cursor edn)
                                   decoded     (cursor/decode-cursor next-cursor)]
                               (is (some? next-cursor) "a continuation cursor was minted")
                               ;; `js->clj :keywordize-keys true` keywordizes
                               ;; the KEYS only — the pred VALUE rides as the
                               ;; JSON string it arrived as (the runtime side
                               ;; coerces). The cursor round-trips it verbatim.
                               (is (= {:event-id ":ev/login"} (:pred decoded))
                                   "the cursor carries the first-call predicate verbatim")
                               (is (= :e1 (:after-id decoded)))
                               (done))))))))))))

(deftest watch-epochs-page-2-with-only-cursor-still-filters
  (testing "page-2 call with ONLY :cursor (no :pred arg) emits a filtering epoch-matches? form, NOT match-all"
    (async done
      (let [forms (atom [])
            ;; A cursor as it would have been minted on page 1, carrying
            ;; the sticky predicate (and frame/after-id). This is exactly
            ;; what the agent passes back verbatim.
            page-1-cursor (cursor/encode-cursor
                            {:v 1 :after-id :e1 :ms nil :until-ms nil
                             :frame :rf/default
                             :pred {:event-id :ev/login}})
            canned {:matches       []
                    :id-aged-out?  false
                    :requested-id  :e1
                    :head-id       :e9
                    :next-id       nil
                    :history-count 9
                    :since-count   4
                    :remaining     0}]
        (-> (with-form-capture! forms canned
              (fn []
                ;; The continuation call passes ONLY :cursor — no :pred,
                ;; no :frame, no :since-id. The documented opaque-cursor
                ;; flow.
                (-> (we/watch-epochs-tool nil (tu/args->js {:cursor page-1-cursor}))
                    (.then (fn [_result]
                             (let [form-str (first @forms)]
                               (is (some? form-str) "a runtime form was emitted")
                               ;; The emitted epoch-matches? call carries
                               ;; the sticky predicate from the cursor.
                               (is (re-find #"epoch-matches\? \{:event-id :ev/login\}"
                                            form-str)
                                   "page-2 form filters by the sticky pred from the cursor")
                               ;; And NOT the match-all empty map.
                               (is (not (re-find #"epoch-matches\? \{\}" form-str))
                                   "page-2 form must NOT degrade to match-all {}")
                               (done))))))))))))
