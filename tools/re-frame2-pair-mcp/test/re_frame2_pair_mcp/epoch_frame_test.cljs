(ns re-frame2-pair-mcp.epoch-frame-test
  "Operating-frame resolution for the two epoch-ring tools, `trace-window`
  and `watch-epochs`.

  Both resolve override -> session pin -> sole app frame -> nil and REFUSE
  at nil. An implicit-frame read gets `[]` back for an unknown frame, which
  `trace-window` would report as a quiet window and `watch-epochs` as a
  dead cursor. The resolved id must also ride back into `:next-cursor`, or
  page 2 re-resolves against whatever the session says by then.

  `runtime-answer` plays a live runtime that derives the frame FROM THE
  EMITTED FORM, the way `pure/resolve-operating-frame` would, so on an
  implicit-frame tree it reads an empty ring and these tests go red. A
  canned refusal would pass on that tree, because the tools only relay."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
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

;; Two app frames, no pin: the session the resolver calls ambiguous.
(def ^:private two-frames [:rf/default :stories])

(defn- epoch [id]
  {:epoch-id id :committed-at (js/Date.now) :trigger-event [:noop id]})

;; ---------------------------------------------------------------------------
;; The runtime simulator — answers are DERIVED from the emitted form.
;; ---------------------------------------------------------------------------

(defn- guards-ambiguity?
  "True when `form` binds the resolved id, branches to
  `ambiguous-frame-error` on nil, and only THEN reads the ring."
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
  "The id the browser-side resolver would return for `form`: the override
  the form carries, else the pin, else the sole app frame, else nil."
  [form app-frames pin]
  (if-let [override (second (or (re-find #"current-frame\s+(:[^\s)]+)\)" form)
                                (re-find #"epoch-history\s+(:[^\s)]+)\)" form)
                                (re-find #"epochs-since\s+\S+\s+(:[^\s)]+)\)" form)))]
    (keyword (subs override 1))
    (or pin
        (when (= 1 (count app-frames)) (first app-frames)))))

(defn- ambiguous-envelope
  "What `re-frame2-pair.runtime/ambiguous-frame-error` builds (the preload
  is not on this test's classpath)."
  [operation app-frames pin]
  {:ok?              false
   :reason           :ambiguous-frame
   :operation        operation
   :available-frames app-frames
   :selected-frame   pin
   :hint             "pass `frame` or pin one, then retry"})

(defn- read-token
  "An emitted scalar literal back as a value: nil, a string id or an integer id."
  [tok]
  (cond
    (nil? tok)                  nil
    (= "nil" tok)               nil
    (str/starts-with? tok "\"") (subs tok 1 (dec (count tok)))
    :else                       (js/parseInt tok 10)))

(defn- since-id-of
  "The id the form asked `epochs-since` for; caller data rides `(quote …)`."
  [form]
  (read-token (second (re-find #"epochs-since\s+(?:\(quote\s+)?(\"[^\"]*\"|[^\s()]+)" form))))

(defn- after-id-of
  "`trace-window`'s cursor watermark, read off its `after-id` let binding."
  [form]
  (read-token (second (re-find #"\bafter-id (?:\(quote )?(nil|\d+|\"[^\"]*\")" form))))

(defn- limit-of
  "The `(take N …)` page size, so a capped page (and so a cursor) exists."
  [form]
  (or (some-> (second (re-find #"\(take (\d+) " form)) (js/parseInt 10)) 50))

(defn- epochs-since*
  "`re-frame2-pair.runtime/epochs-since`: nil id -> the whole ring; a known
  id -> the records after it; an UNKNOWN id -> `[]` with `:id-aged-out? true`."
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
  "Answer `form` as a live runtime would for the session `app-frames` /
  `pin` / `rings`, reading the frame the form resolves and reporting it as
  `:frame`. Every fixture epoch is stamped now and every window is 60s, so
  the time filter is a no-op and paging is the only cap."
  [form {:keys [operation app-frames pin rings]}]
  (let [fid (resolved-id form app-frames pin)]
    (if (and (nil? fid) (guards-ambiguity? form operation))
      (ambiguous-envelope operation app-frames pin)
      ;; Unguarded, a nil frame misses the per-frame lookup: the ring is EMPTY.
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
  "Install the simulator, capturing the tool's own form into `captured*`."
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
;; Page 1 — refuse an ambiguous frame rather than read frame nil.
;; ---------------------------------------------------------------------------

(deftest trace-window-ambiguous-frame-refuses-rather-than-reporting-an-empty-window
  ;; Both frames HAVE epochs, so `:count 0` would be a falsehood about the app.
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames two-frames
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2)]
                                     :stories    [(epoch 7)]}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000}))
        (.then (fn [r]
                 (is (err? r))
                 (is (= (ambiguous-envelope :trace-window two-frames nil) (read-edn r))
                     "the refusal rides verbatim: no :count, no :epochs, no :next-cursor")
                 (done))))))

(deftest watch-epochs-ambiguous-frame-refuses-rather-than-aging-out-a-live-cursor
  ;; Epoch 2 is alive in :rf/default; read against an empty ring it would be
  ;; reported aged out, sending the agent back to page one.
  (async done
    (stub-runtime! nil {:operation  :watch-epochs
                        :app-frames two-frames
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2) (epoch 3)]
                                     :stories    [(epoch 7)]}})
    (-> (we/watch-epochs-tool nil (tu/args->js {:since-id 2}))
        (.then (fn [r]
                 (is (err? r))
                 (is (= (ambiguous-envelope :watch-epochs two-frames nil) (read-edn r))
                     "the refusal rides verbatim, never as :rf.mcp/cursor-stale")
                 (done))))))

(deftest the-emitted-forms-read-and-report-the-resolved-id
  ;; The simulator reads and reports the resolved frame whatever the form
  ;; says, so these pin the forms themselves: watch-epochs' poll and history
  ;; count read `fid`, and both tools report `fid` back for the cursor.
  ;; trace-window's ring read is pinned by `guards-ambiguity?` above.
  (async done
    (let [tw-form (atom nil)
          we-form (atom nil)
          session {:app-frames [:rf/default] :pin nil :rings {:rf/default [(epoch 1)]}}]
      (stub-runtime! tw-form (assoc session :operation :trace-window))
      (-> (tw/trace-window-tool nil (tu/args->js {}))
          (.then (fn [_]
                   (stub-runtime! we-form (assoc session :operation :watch-epochs))
                   (we/watch-epochs-tool nil (tu/args->js {}))))
          (.then (fn [_]
                   (is (str/includes? @tw-form ":frame fid"))
                   (doseq [s ["(re-frame2-pair.runtime/epochs-since nil fid)"
                              "(re-frame2-pair.runtime/epoch-history fid)"
                              ":frame fid"]]
                     (is (str/includes? @we-form s) s))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Cursor frame ownership. By page 2 there is usually nothing left to refuse:
;; the session resolves a frame, just not the one page 1 read. So page 1's
;; cursor must carry the resolved id. Every cursor below is one the tool
;; itself returned.
;; ---------------------------------------------------------------------------

(defn- cursor-payload [r]
  (cursor/decode-cursor (:next-cursor (read-edn r))))

(deftest trace-window-page-1-cursor-owns-the-pin-it-resolved
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
                         "the cursor carries the id the PIN resolved, not the nil it was asked for")
                     (is (= 7 (:after-id c)))
                     ;; The session moves under the agent, mid-pagination.
                     (stub-runtime! nil (assoc session :pin :rf/default))
                     (tw/trace-window-tool
                       nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1})))))
          (.then (fn [r2]
                   (is (not (err? r2))
                       "epoch 7 is alive in :stories, so the cursor is not stale")
                   (is (= 1 (:count (read-edn r2))) "page 2 read the ORIGINAL ring")
                   (is (= :stories (:frame (cursor-payload r2))) "and page 3 inherits it")
                   (done)))))))

(deftest trace-window-page-2-survives-a-second-frame-registering
  ;; A fresh call would now be refused as ambiguous; an owned cursor must not be.
  (async done
    (let [ring {:rf/default [(epoch 1) (epoch 2) (epoch 3)]}]
      (stub-runtime! nil {:operation  :trace-window
                          :app-frames [:rf/default]
                          :pin        nil
                          :rings      ring})
      (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :limit 1}))
          (.then (fn [r1]
                   (is (= :rf/default (:frame (cursor-payload r1)))
                       "the SOLE app frame is the id the read resolved")
                   (stub-runtime! nil {:operation  :trace-window
                                       :app-frames two-frames
                                       :pin        nil
                                       :rings      (assoc ring :stories [(epoch 7)])})
                   (tw/trace-window-tool
                     nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1}))))
          (.then (fn [r2]
                   (is (not (err? r2)) "an owned cursor stays answerable")
                   (is (= 1 (:count (read-edn r2))) "and it read the frame it started on")
                   (done)))))))

(deftest watch-epochs-page-1-cursor-owns-the-pin-it-resolved
  ;; Losing the ring here reports the LIVE cursor dead: the falsehood the
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
                         "the cursor carries the id the PIN resolved, not nil")
                     (is (= 7 (:after-id c)))
                     (stub-runtime! nil (assoc session :pin :rf/default))
                     (we/watch-epochs-tool
                       nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1})))))
          (.then (fn [r2]
                   (is (not (err? r2))
                       "epoch 7 is alive in :stories, so the cursor is not aged out")
                   (is (= 1 (:count (read-edn r2))) "page 2 polled the ORIGINAL ring")
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
                       "the sole app frame is what the cursor owns")
                   (stub-runtime! nil {:operation  :watch-epochs
                                       :app-frames two-frames
                                       :pin        nil
                                       :rings      (assoc ring :stories [(epoch 7)])})
                   (we/watch-epochs-tool
                     nil (tu/args->js {:cursor (:next-cursor (read-edn r1)) :limit 1}))))
          (.then (fn [r2]
                   (is (not (err? r2)) "an owned cursor stays answerable")
                   (is (= 1 (:count (read-edn r2))))
                   (done)))))))

(deftest an-explicit-frame-is-the-id-the-cursor-owns
  ;; Control: an explicit frame still outranks the pin, and the cursor owns it.
  (async done
    (stub-runtime! nil {:operation  :trace-window
                        :app-frames two-frames
                        :pin        :rf/default
                        :rings      {:rf/default [(epoch 1) (epoch 2)]
                                     :stories    [(epoch 7) (epoch 8) (epoch 9)]}})
    (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :limit 1 :frame ":stories"}))
        (.then (fn [r]
                 (is (= :stories (:frame (cursor-payload r))))
                 (done))))))

(deftest an-advisory-names-the-frame-its-count-came-from
  ;; Reporting the asked-for nil beside a count read from a real frame would
  ;; describe a read that never happened.
  (async done
    (stub-runtime! nil {:operation  :watch-epochs
                        :app-frames [:step-deck]
                        :pin        nil
                        :rings      {:step-deck (mapv epoch (range 1 10))}})
    (-> (we/watch-epochs-tool nil (tu/args->js {:since-id 9}))
        (.then (fn [r]
                 (is (= {:reason            :no-events-since-id
                         :frame             :step-deck
                         :epochs-in-history 9
                         :requested-id      9}
                        (dissoc (:advisory (read-edn r)) :hint)))
                 (done))))))

;; ---------------------------------------------------------------------------
;; `:since-id` arrives as a STRING: the descriptor types it `string`, while
;; the runtime's ids are integers that `epochs-since` finds with `=`. Passed
;; raw, "2" never equals 2 and every resume-by-id reads as a false
;; `:rf.mcp/cursor-stale`. The simulator keeps that `=`.
;; ---------------------------------------------------------------------------

(deftest watch-epochs-string-since-id-resumes-against-integer-ids
  (async done
    (stub-runtime! nil {:operation  :watch-epochs
                        :app-frames [:rf/default]
                        :pin        nil
                        :rings      {:rf/default [(epoch 1) (epoch 2) (epoch 3) (epoch 4)]}})
    (-> (we/watch-epochs-tool nil (tu/args->js {:since-id "2"}))
        (.then (fn [r]
                 (is (not (err? r)) "epoch 2 is in the ring, so the id is not aged out")
                 (is (= 2 (:count (read-edn r))) "the poll resumes after epoch 2: epochs 3 and 4")
                 (done))))))

(deftest watch-epochs-unreadable-since-id-is-refused
  (async done
    (stub-runtime! nil {:operation  :watch-epochs
                        :app-frames [:rf/default]
                        :pin        nil
                        :rings      {:rf/default [(epoch 1)]}})
    (-> (we/watch-epochs-tool nil (tu/args->js {:since-id "{:unclosed"}))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-since-id (:reason (read-edn r)))
                     "an unreadable id is named as such, not reported as aged out")
                 (done))))))
