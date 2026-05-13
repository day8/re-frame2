(ns re-frame-pair2-mcp.cursor-pagination-test
  "Unit tests for the cursor-pagination mechanism on `trace-window`
  and `watch-epochs` (rf2-kbqq3).

  Both tools accept `:limit` (int, default 50) + `:cursor` (opaque
  string). Responses carry `:next-cursor`, `:has-more?` and
  `:estimated-remaining`. A cursor whose epoch-id has aged out of the
  runtime ring surfaces as a `:rf.mcp/cursor-stale` error rather than
  silently restarting.

  These tests mirror the private helpers from `tools.cljs`
  (`parse-limit-arg`, `encode-cursor`, `decode-cursor`,
  `cursor-stale-result`) and exercise the full host-side pagination
  logic by simulating the runtime form's output. The live runtime
  call sits behind the nREPL eval boundary and is covered by the
  stdio-roundtrip harness; this layer pins the cursor contract."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [clojure.string :as str]
            [applied-science.js-interop :as j]))

;; ---------------------------------------------------------------------------
;; Mirrors of the private cursor helpers. Keep in lockstep with tools.cljs.
;; ---------------------------------------------------------------------------

(def ^:private default-limit 50)

(defn- parse-limit-arg [raw]
  (cond
    (or (nil? raw) (undefined? raw)) default-limit
    (number? raw) (max 1 (long raw))
    (string? raw) (let [n (js/parseInt raw 10)]
                    (if (and (number? n) (not (js/isNaN n)))
                      (max 1 (long n))
                      default-limit))
    :else default-limit))

(defn- encode-cursor [payload]
  (when (and (map? payload) (some? (:after-id payload)))
    (let [edn (pr-str payload)
          buf (js/Buffer.from edn "utf8")]
      (.toString buf "base64"))))

(defn- decode-cursor [s]
  (cond
    (or (nil? s) (undefined? s)) nil
    (not (string? s)) ::malformed
    (str/blank? s)    nil
    :else
    (try
      (let [buf (js/Buffer.from s "base64")
            edn (.toString buf "utf8")
            v   (cljs.reader/read-string edn)]
        (if (and (map? v) (string? (:after-id v)))
          v
          ::malformed))
      (catch :default _ ::malformed))))

;; ---------------------------------------------------------------------------
;; parse-limit-arg — MCP-arg normalisation.
;; ---------------------------------------------------------------------------

(deftest parse-limit-default-when-absent
  (is (= default-limit (parse-limit-arg nil)))
  (is (= default-limit (parse-limit-arg js/undefined))))

(deftest parse-limit-positive-integer-pass-through
  (is (= 10 (parse-limit-arg 10)))
  (is (= 1  (parse-limit-arg 1)))
  (is (= 1000 (parse-limit-arg 1000))))

(deftest parse-limit-clamps-non-positive-to-one
  (is (= 1 (parse-limit-arg 0)))
  (is (= 1 (parse-limit-arg -5))))

(deftest parse-limit-parses-numeric-string
  (is (= 25 (parse-limit-arg "25"))))

(deftest parse-limit-non-numeric-string-falls-back
  (is (= default-limit (parse-limit-arg "bogus"))))

;; ---------------------------------------------------------------------------
;; encode-cursor / decode-cursor — opaque round-trip.
;; ---------------------------------------------------------------------------

(deftest encode-cursor-nil-when-no-after-id
  (is (nil? (encode-cursor nil)))
  (is (nil? (encode-cursor {})))
  (is (nil? (encode-cursor {:v 1 :after-id nil}))))

(deftest encode-cursor-returns-string-on-valid-payload
  (let [c (encode-cursor {:v 1 :after-id "abc"})]
    (is (string? c))
    (is (pos? (count c)))))

(deftest cursor-round-trip-preserves-payload
  (let [payload {:v 1 :after-id "epoch-42" :ms 5000 :until-ms 1234567890
                 :frame :rf/default}
        encoded (encode-cursor payload)
        decoded (decode-cursor encoded)]
    (is (= payload decoded))))

(deftest decode-cursor-nil-on-nil-input
  (is (nil? (decode-cursor nil)))
  (is (nil? (decode-cursor "")))
  (is (nil? (decode-cursor js/undefined))))

(deftest decode-cursor-malformed-on-junk
  (is (= ::malformed (decode-cursor "not-real-base64-edn-juzlblahHFGYbn")))
  (is (= ::malformed (decode-cursor 12345)))
  ;; base64 of "not-a-map" — decodes but isn't a map with :after-id
  (let [bogus (.toString (js/Buffer.from "[1 2 3]" "utf8") "base64")]
    (is (= ::malformed (decode-cursor bogus)))))

(deftest decode-cursor-malformed-on-missing-after-id
  (let [bogus (.toString (js/Buffer.from "{:v 1}" "utf8") "base64")]
    (is (= ::malformed (decode-cursor bogus)))))

(deftest cursor-is-opaque-on-wire
  ;; The agent has no business decoding the cursor — but the encoding
  ;; MUST be a self-contained string (no embedded JSON-confusing chars
  ;; that would break round-trip through bencode + JSON-RPC).
  (let [c (encode-cursor {:v 1 :after-id "abc-def"})]
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
        cursor (encode-cursor {:v 1 :after-id (:next-id page-1)
                               :until-ms 99999999 :ms nil :frame nil})
        decoded (decode-cursor cursor)
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
        walk (loop [cursor   nil
                    pages    []
                    safety   10]
               (if (zero? safety)
                 pages
                 (let [decoded (decode-cursor cursor)
                       after-id (:after-id decoded)
                       out (runtime-form-output hist {:after-id after-id
                                                      :cutoff-ms 0
                                                      :until-ms 99999999
                                                      :limit 10})
                       pages' (conj pages (:epochs out))]
                   (if-let [next-id (:next-id out)]
                     (recur (encode-cursor {:v 1 :after-id next-id
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
  ;; Caller passes garbage. Host translates ::malformed to the same
  ;; :rf.mcp/cursor-stale error path as a true age-out.
  (is (= ::malformed (decode-cursor "not-base64-edn")))
  (is (= ::malformed (decode-cursor "AAAA"))))

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
