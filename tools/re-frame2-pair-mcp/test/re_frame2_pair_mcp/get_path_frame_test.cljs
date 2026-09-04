(ns re-frame2-pair-mcp.get-path-frame-test
  "Operating-frame resolution for the `get-path` tool (rf2-q17a).

  ## What this pins

  `get-path` is frame-targeted, so the Tool-Pair contract makes it
  resolve explicit override -> session pin -> sole app frame -> nil,
  and REFUSE at nil rather than read some other frame. Before
  rf2-q17a it did neither: the emitted form called `(snapshot)` with
  no frame and no guard, which resolves to `(rf/app-db-value nil)` =
  nil, and `get-in` over nil answers `:path-not-found` (singular) or
  `{:exists? false}` for every path (batch). The tool told the agent
  \"that path does not exist\" when the truth was \"I could not tell
  which frame you meant\" — and an agent that believes it goes off and
  adds a path that was already there.

  ## Why the stub reads the form

  A test that canned an `:ambiguous-frame` response would pass on the
  DEFECT, because the tool faithfully relays whatever the runtime
  hands it — the bug was never in the relay. So `runtime-answer`
  below does not take a canned envelope. It plays a live runtime with
  a given set of app frames and a given pin, DERIVES the operating
  frame from the emitted form exactly as `pure/resolve-operating-frame`
  would, and answers accordingly:

    - resolved id is nil AND the form guards on it -> the refusal;
    - resolved id is nil and the form does NOT guard -> the read runs
      against `(get db nil)` = nil, and reports the miss — the very
      falsehood this bead is about;
    - resolved id is non-nil -> the read runs against that frame's db.

  So the same test body distinguishes the fix from the defect, and
  the singular/batch refusal tests fail on the pre-fix tree.

  Note `elision_test/build-get-path-form` is a hand-written MIRROR of
  the form composition and asserts against itself, so it can go green
  while production drifts. The tests here drive `get-path-tool`
  itself."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.get-path :as get-path]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

;; Two app frames, no pin — the session shape the resolver calls
;; ambiguous and the one where a wrong guess is unrecoverable.
(def ^:private two-frames [:rf/default :stories])

;; ---------------------------------------------------------------------------
;; The runtime simulator — answers are DERIVED from the emitted form.
;; ---------------------------------------------------------------------------

(defn- guards-ambiguity?
  "True when `form` detects the ambiguity WHERE IT ARISES: it binds the
  resolved id, branches to `ambiguous-frame-error` on nil, and only
  then reads app-db. The ordering is the assertion — a form that
  merely mentions the refusal after reading would still have taken
  the wrong branch."
  [form]
  (let [resolve-at (str/index-of form "(let [fid (re-frame2-pair.runtime/current-frame")
        refuse-at  (str/index-of form
                                 (str "(if (nil? fid) "
                                      "(re-frame2-pair.runtime/ambiguous-frame-error :get-path)"))
        read-at    (str/index-of form "(re-frame2-pair.runtime/snapshot fid)")]
    (boolean (and resolve-at refuse-at read-at
                  (< resolve-at refuse-at read-at)))))

(defn- resolved-id
  "The id the browser-side resolver would return for `form` in a session
  holding `app-frames` with `pin` selected. Mirrors
  `pure/resolve-operating-frame`: override -> pin -> sole app frame ->
  nil. The override is read off the form because that is where the
  tool puts it — from the resolve call once the fix routes it there,
  and from the `snapshot` call on the pre-fix shape, so this models
  BOTH trees faithfully and tier 1 stays a real control rather than
  an artefact of the simulator."
  [form app-frames pin]
  (if-let [override (second (or (re-find #"current-frame\s+(:[^\s)]+)\)" form)
                                (re-find #"snapshot\s+(:[^\s)]+)\)" form)))]
    (keyword (subs override 1))
    (or pin
        (when (= 1 (count app-frames)) (first app-frames)))))

(defn- ambiguous-envelope
  "The envelope `re-frame2-pair.runtime/ambiguous-frame-error` builds —
  reproduced here because the preload is not on this test's classpath.
  Shape per `pure/ambiguous-frame-envelope`."
  [app-frames pin]
  {:ok?              false
   :reason           :ambiguous-frame
   :operation        :get-path
   :available-frames app-frames
   :selected-frame   pin
   :hint             (str "multiple app frames are registered and no frame is "
                          "selected, so get-path cannot pick a target. "
                          "Pass `frame` (one of " (pr-str app-frames) ") or pin one with "
                          "`select-frame!` / set-operating-frame, then retry.")})

(defn- runtime-answer
  "Answer `form` as a live runtime would, given the session described by
  `app-frames` / `pin` / `db` (a frame-id -> app-db map). `path` /
  `paths` are the caller's request; the FRAME is whatever the form
  resolves, which is the whole point."
  [form {:keys [app-frames pin db path paths]}]
  (let [fid (resolved-id form app-frames pin)]
    (if (and (nil? fid) (guards-ambiguity? form))
      (ambiguous-envelope app-frames pin)
      ;; No guard (or an unambiguous session): the read proceeds. For a
      ;; nil frame `(get db nil)` is nil and every lookup misses —
      ;; reproducing the pre-fix falsehood exactly.
      (let [frame-db (get db fid)
            missing  (js-obj)]
        (if paths
          {:ok?     true
           :results (into {}
                          (map (fn [p]
                                 (let [v (get-in frame-db p missing)]
                                   [p (if (identical? v missing)
                                        {:exists? false :value nil}
                                        {:exists? true :value v})])))
                          paths)
           :elided-count 0}
          (let [v (get-in frame-db path missing)]
            (if (identical? v missing)
              {:ok? false :reason :path-not-found :path path :deepest-valid-prefix []}
              {:ok? true :exists? true :path path :value v :elided-count 0})))))))

(defn- stub-runtime!
  "Install the simulator. Prelude evals (the preload sentinel and the
  raw-state signal) are answered directly; the get-path form is
  captured into `captured*` and answered by `runtime-answer`."
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
                  (if (string? form)
                    (runtime-answer form session)
                    nil)))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

;; ---------------------------------------------------------------------------
;; The witnesses — these fail on the pre-fix tree.
;; ---------------------------------------------------------------------------

(deftest singular-ambiguous-frame-refuses-rather-than-reporting-path-not-found
  ;; Acceptance 1: two app frames, no pin, no `frame` arg.
  (async done
    (let [captured (atom nil)]
      (stub-runtime! captured {:app-frames two-frames
                               :pin        nil
                               :db         {:rf/default {:cart {:items [1 2]}}
                                            :stories    {:cart {:items []}}}
                               :path       [:cart :items]})
      (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:cart :items]"}))
          (.then (fn [r]
                   (let [edn (read-edn r)]
                     (is (err? r) "an unanswerable read is an isError envelope")
                     (is (= false (:ok? edn)))
                     (is (= :ambiguous-frame (:reason edn))
                         "the refusal names the ambiguity, not a missing path")
                     (is (not= :path-not-found (:reason edn))
                         "REGRESSION: the path exists in BOTH frames — reporting it absent is a falsehood")
                     (is (= :get-path (:operation edn)))
                     (is (= two-frames (:available-frames edn))
                         "the candidate frames are named so the agent can choose")
                     (is (nil? (:selected-frame edn)))
                     (is (not (contains? edn :deepest-valid-prefix))
                         "no path-discovery breadcrumbs on a question that was never asked"))
                   (done)))))))

(deftest batch-ambiguous-frame-refuses-rather-than-reporting-every-path-absent
  ;; Acceptance 2: the batch shape must not answer `:ok? true` with an
  ;; all-missing results map — the more damaging of the two, since it
  ;; presents as a SUCCESS.
  (async done
    (let [captured (atom nil)]
      (stub-runtime! captured {:app-frames two-frames
                               :pin        nil
                               :db         {:rf/default {:cart {:items [1 2]} :user {:id 7}}
                                            :stories    {:cart {:items []} :user {:id 9}}}
                               :paths      [[:cart :items] [:user :id]]})
      (-> (get-path/get-path-tool (fresh-conn)
                                  (tu/args->js {:paths "[[:cart :items] [:user :id]]"}))
          (.then (fn [r]
                   (let [edn (read-edn r)]
                     (is (err? r))
                     (is (= false (:ok? edn))
                         "REGRESSION: an all-missing batch presented as :ok? true reads as success")
                     (is (= :ambiguous-frame (:reason edn)))
                     (is (= :get-path (:operation edn)))
                     (is (= two-frames (:available-frames edn)))
                     (is (not (contains? edn :results))
                         "a refusal carries no :results — `:results nil` reads as 'nothing there'"))
                   (done)))))))

(deftest refusal-names-the-next-action
  ;; The stance: an ambiguity error that merely says "ambiguous" is not
  ;; good ergonomics. The hint must name what to DO.
  (async done
    (stub-runtime! nil {:app-frames two-frames :pin nil :db {} :path [:a]})
    (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:a]"}))
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

(deftest ambiguity-is-detected-before-app-db-is-read
  ;; Patching the message alone would leave the wrong branch taken.
  ;; Both emitted shapes must resolve, guard, THEN read.
  (async done
    (let [captured (atom nil)]
      (stub-runtime! captured {:app-frames two-frames :pin nil :db {} :path [:a]})
      (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:a]"}))
          (.then (fn [_]
                   (let [form @captured]
                     (is (string? form))
                     (is (guards-ambiguity? form)
                         "singular: resolve -> refuse-on-nil -> read, in that order"))
                   (let [captured2 (atom nil)]
                     (stub-runtime! captured2 {:app-frames two-frames :pin nil
                                               :db {} :paths [[:a]]})
                     (-> (get-path/get-path-tool (fresh-conn)
                                                 (tu/args->js {:paths "[[:a]]"}))
                         (.then (fn [_]
                                  (is (guards-ambiguity? @captured2)
                                      "batch: same ordering")
                                  (done)))))))))))

(deftest one-resolution-serves-both-the-read-and-the-walker
  ;; The read and the elision handle must describe the same frame. The
  ;; walker used to issue a SECOND, independent `(current-frame)` call.
  (async done
    (let [captured (atom nil)]
      (stub-runtime! captured {:app-frames [:rf/default] :pin nil
                               :db {:rf/default {:a 1}} :path [:a]})
      (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:a]"}))
          (.then (fn [_]
                   (let [form @captured]
                     (is (re-find #":frame fid" form)
                         "the walker addresses the resolved id")
                     (is (= 1 (count (re-seq #"re-frame2-pair\.runtime/current-frame" form)))
                         "exactly ONE resolve per form — two could disagree"))
                   (done)))))))

(deftest emitted-form-is-well-formed
  ;; Liveness: the wrapper splices raw source, so a stray paren would
  ;; ship a form the runtime cannot read. Delimiters must balance.
  (async done
    (let [captured (atom nil)
          balanced?
          (fn [s]
            (let [tally (fn [o c] (- (count (re-seq (re-pattern (str "\\" o)) s))
                                     (count (re-seq (re-pattern (str "\\" c)) s))))]
              (and (zero? (tally "(" ")"))
                   (zero? (tally "[" "]"))
                   (zero? (tally "{" "}")))))]
      (stub-runtime! captured {:app-frames [:rf/default] :pin nil
                               :db {:rf/default {:a 1}} :path [:a]})
      (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:a]"}))
          (.then (fn [_]
                   (is (balanced? @captured) "singular form balances")
                   (let [captured2 (atom nil)]
                     (stub-runtime! captured2 {:app-frames [:rf/default] :pin nil
                                               :db {:rf/default {:a 1}} :paths [[:a]]})
                     (-> (get-path/get-path-tool (fresh-conn)
                                                 (tu/args->js {:paths "[[:a]]"}))
                         (.then (fn [_]
                                  (is (balanced? @captured2) "batch form balances")
                                  (done)))))))))))

;; ---------------------------------------------------------------------------
;; Acceptance 3 — the resolvable sessions keep their existing shapes.
;; ---------------------------------------------------------------------------

(deftest explicit-frame-still-reads-that-frame
  ;; An explicit override wins tier 1 even with two frames registered,
  ;; and the value returned is that frame's — not the other's.
  (async done
    (stub-runtime! nil {:app-frames two-frames
                        :pin        nil
                        :db         {:rf/default {:cart {:items [1 2]}}
                                     :stories    {:cart {:items [:only-here]}}}
                        :path       [:cart :items]})
    (-> (get-path/get-path-tool (fresh-conn)
                                (tu/args->js {:path "[:cart :items]" :frame ":stories"}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)))
                   (is (true? (:ok? edn)))
                   (is (true? (:exists? edn)))
                   (is (= [:only-here] (:value edn)) "read the frame the caller named")
                   (is (= :stories (:frame edn))))
                 (done))))))

(deftest session-pin-still-reads-the-pinned-frame
  ;; Tier 2. No `frame` arg, two frames registered, but a pin is in
  ;; effect — a hit, not a refusal.
  (async done
    (stub-runtime! nil {:app-frames two-frames
                        :pin        :stories
                        :db         {:rf/default {:cart {:items [1 2]}}
                                     :stories    {:cart {:items [:pinned]}}}
                        :path       [:cart :items]})
    (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:cart :items]"}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)))
                   (is (true? (:ok? edn)))
                   (is (= [:pinned] (:value edn))))
                 (done))))))

(deftest sole-app-frame-still-auto-resolves
  ;; Tier 3. One app frame, no pin, no arg — auto-resolves, no refusal.
  (async done
    (stub-runtime! nil {:app-frames [:rf/default]
                        :pin        nil
                        :db         {:rf/default {:counter 42}}
                        :path       [:counter]})
    (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:counter]"}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)))
                   (is (true? (:ok? edn)))
                   (is (= 42 (:value edn))))
                 (done))))))

(deftest genuine-miss-in-a-resolved-frame-is-still-path-not-found
  ;; The refusal must not swallow the honest answer: in a session that
  ;; DOES resolve, an absent path is still `:path-not-found`.
  (async done
    (stub-runtime! nil {:app-frames [:rf/default]
                        :pin        nil
                        :db         {:rf/default {:counter 42}}
                        :path       [:no-such :key]})
    (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:no-such :key]"}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (err? r))
                   (is (= :path-not-found (:reason edn))
                       "a resolved frame that genuinely lacks the path still says so"))
                 (done))))))

(deftest batch-in-a-resolved-frame-still-returns-results
  (async done
    (stub-runtime! nil {:app-frames [:rf/default]
                        :pin        nil
                        :db         {:rf/default {:cart {:items [1 2]} :user {:id 7}}}
                        :paths      [[:cart :items] [:user :id] [:nope]]})
    (-> (get-path/get-path-tool
          (fresh-conn)
          (tu/args->js {:paths "[[:cart :items] [:user :id] [:nope]]"}))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not (err? r)))
                   (is (true? (:ok? edn)))
                   (is (= {:exists? true :value [1 2]} (get-in edn [:results [:cart :items]])))
                   (is (= {:exists? true :value 7} (get-in edn [:results [:user :id]])))
                   (is (= {:exists? false :value nil} (get-in edn [:results [:nope]]))
                       "a real per-path miss inside a resolved frame is still reported"))
                 (done))))))
