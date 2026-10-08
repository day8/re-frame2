(ns re-frame2-pair-mcp.dispatch-test
  "The dispatch tool's wire boundary: the event and the caller's options
  reach the runtime call as quoted data, malformed arguments are refused
  before the dispatch eval, the mode flags select the runtime fn, the
  epoch-bearing modes project before egress, and the raw-state posture is
  signalled before the dispatch eval. The event-parse matrix lives in
  `args_test`."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.dispatch :as dispatch]))

;; Stubs are installed with a bare `set!` and restored here: a `.finally`
;; restore can land after `done` has moved on to the next namespace and
;; clobber that namespace's stub.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (raw-state/set-allow-raw-state! false)
            (raw-state/reset-runtime-signal-cache!))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- install-stub! [run]
  (set! nrepl/cljs-eval-value
        (fn
          ([_conn _build-id form-str] (run form-str))
          ([_conn _build-id form-str _opts] (run form-str))))
  (raw-state/reset-runtime-signal-cache!))

(defn- with-captured-eval!
  "Record the dispatch form into `captured*` and answer it with
  `canned-value`; the raw-state signal answers nil and is not recorded."
  [captured* canned-value body-fn]
  (install-stub! (fn [form-str]
                   (if (str/includes? form-str "configure-raw-state!")
                     (js/Promise.resolve nil)
                     (do (reset! captured* form-str)
                         (js/Promise.resolve canned-value)))))
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (body-fn)))))

(defn- with-captured-forms!
  "Record every form, the raw-state signal included, in order."
  [forms* canned-value body-fn]
  (install-stub! (fn [form-str]
                   (swap! forms* conj form-str)
                   (js/Promise.resolve
                     (when-not (str/includes? form-str "configure-raw-state!")
                       canned-value))))
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (body-fn)))))

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(defn- fires-before?
  "True when a form containing `a` was sent before any form containing `b`."
  [forms a b]
  (let [idx (fn [s] (first (keep-indexed #(when (str/includes? %2 s) %1) forms)))
        i   (idx a)
        j   (idx b)]
    (boolean (and i j (< i j)))))

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted` for
  anything else: an unquoted list is a call and an unquoted symbol a name
  lookup, so neither yields the datum it was printed from."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(defn- event-arg [captured]
  (second (cljs.reader/read-string @captured)))

(defn- opts-arg [captured]
  (quoted-datum (nth (cljs.reader/read-string @captured) 2)))

;; ---------------------------------------------------------------------------
;; Refused before the dispatch eval.
;; ---------------------------------------------------------------------------

(deftest rejects-arbitrary-cljs-source-without-eval
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {:event "(println :pwned)"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :not-an-event-vector (:reason (read-result-text r))))
                   (is (nil? @captured) "no dispatch form was sent")
                   (done)))))))

(deftest bogus-timeout-ms-rejected-before-touching-nrepl
  ;; A NaN deadline would poll the render-settle mailbox forever. No stub
  ;; is installed, so reaching nREPL would fail with a different reason.
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn)
                                #js {:event "[:cart/add]" :await-render true
                                     :timeout-ms "bogus"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "timeout-ms" (:arg edn))))
                 (done))))))

(deftest malformed-cofx-rejected-before-eval
  (async done
    (-> (with-captured-eval! (atom nil) {:ok? true}
          (fn []
            (js/Promise.all
              (into-array
                (for [[cofx reason] [["{:rf/time-ms 1" :invalid-cofx]
                                     ["{:rf/time-ms \"now\"}" :invalid-cofx-time-ms]]]
                  (.then (dispatch/dispatch-tool (fresh-conn)
                                                 #js {:event "[:counter/inc]" :cofx cofx})
                         (fn [r]
                           (is (err? r) cofx)
                           (is (= reason (:reason (read-result-text r))) cofx))))))))
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; What the runtime call carries.
;; ---------------------------------------------------------------------------

(deftest event-payload-lists-are-not-evaluated-before-dispatch
  ;; The parser checks only the OUTER shape, so the event must ride quoted:
  ;; printed, `(inc 41)` would reach the handler as 42.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :no-op? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {:event "[:cart/add (inc 41)]"})))
          (.then (fn [_]
                   (is (= [:cart/add '(inc 41)] (quoted-datum (event-arg captured))))
                   (done)))))))

(defn- dispatched-opts
  "Dispatch `[:counter/inc]` with `args` and return a Promise of the opts
  map the runtime call receives."
  [args]
  (let [captured (atom nil)]
    (-> (with-captured-eval! captured {:ok? true}
          #(dispatch/dispatch-tool (fresh-conn) (tu/args->js (assoc args :event "[:counter/inc]"))))
        (.then (fn [_] (opts-arg captured))))))

(deftest dispatch-opts-carry-exactly-the-callers-options
  ;; Absent options leave no slot. `cofx` alone stays live (no mint
  ;; policy). `replay` is strict with or without a token, and a replay
  ;; re-supplies a recorded envelope's own overrides beside its cofx, or it
  ;; would run under a different effective chain (Tool-Pair §Replay).
  ;; Caller EDN rides as the datum it was: `(inc 41)` is not evaluated.
  (async done
    (-> (reduce
          (fn [p [args expected]]
            (.then p (fn [_]
                       (.then (dispatched-opts args)
                              (fn [opts] (is (= expected opts) (pr-str (keys args))))))))
          (js/Promise.resolve nil)
          [[{} {}]
           [{:cofx "{:rf/time-ms 1781078400123 :review/fact (inc 41)}"}
            {:rf.cofx {:rf/time-ms 1781078400123 :review/fact '(inc 41)}}]
           [{:replay true} {:rf.cofx/mint-policy :strict}]
           [{:interceptor-overrides #js {":auth/required" ":story/skip-auth"}}
            {:interceptor-overrides {:auth/required :story/skip-auth}}]
           [{:replay                true
             :cofx                  "{:rf/time-ms 1781078400123 :counter/delta 4}"
             :fx-overrides          #js {":http" ":stub-http"}
             :interceptor-overrides #js {":audit/record-event" nil}}
            {:rf.cofx               {:rf/time-ms 1781078400123 :counter/delta 4}
             :rf.cofx/mint-policy   :strict
             :fx-overrides          {:http :stub-http}
             :interceptor-overrides {:audit/record-event nil}}]])
        (.catch (fn [e] (is false (str "rejected: " e))))
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; Modes, egress projection and the raw-state signal.
;; ---------------------------------------------------------------------------

(deftest settle-mode-routes-to-dispatch-and-settle
  ;; `:settle` is synchronous (no mailbox) and wins over every other mode
  ;; flag. Its epoch still projects under `:include-sensitive true`, which
  ;; lifts only the app-db sensitive axis.
  (async done
    (let [captured (atom nil)
          canned   {:ok? true :epoch-id 11 :settled? true}]
      (-> (with-captured-eval! captured canned
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :settle true
                                           :trace true :queued true :await-render true
                                           :include-sensitive true})))
          (.then (fn [r]
                   (let [form @captured]
                     (is (str/includes? form "dispatch-and-settle!"))
                     (is (not (str/includes? form "__rf2pair_await__")))
                     (is (str/includes? form "project-egress"))
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool"))
                     (is (str/includes? form ":rf.egress/include-sensitive? true"))
                     (is (not (str/includes? form ":rf.egress/include-fx-args?")))
                     (is (not (str/includes? form ":rf.egress/include-runtime-db?"))))
                   (is (= (assoc canned :mode :settle) (read-result-text r)))
                   (done)))))))

(deftest trace-include-sensitive-string-false-stays-false-no-leak
  ;; Over JSON a decline can arrive as the STRING "false", which is truthy
  ;; in CLJS; read as true it would lift the axis the caller declined.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :trace true
                                           :include-sensitive "false"})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form "project-egress"))
                     (is (not (str/includes? form ":rf.egress/include-sensitive? true"))))
                   (done)))))))

(deftest default-dispatch-signals-configure-raw-state-before-eval
  ;; The runtime redacts the cascade summary's `:event-vector` only once
  ;; told the gate is off, and it starts permissive, so even the first
  ;; dispatch of a session must signal first.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-forms! forms {:ok? true :epoch-id 7 :db-changed? false}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn) #js {:event "[:counter/inc]"})))
          (.then (fn [_]
                   (is (fires-before? @forms "configure-raw-state!" "dispatch-consequence!"))
                   (is (some #(str/includes? % ":allow-raw-state? false") @forms))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Render-settle — `:await-render` resolves only after the substrate's
;; flush, via the shared await-promise mailbox. The flush/paint shape of
;; the settle form is pinned by the `:dispatch/await-render-settles`
;; corpus fixture.
;; ---------------------------------------------------------------------------

(defn- await-wrap-form? [form-str]
  (and (string? form-str)
       (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str ":rf.mcp/await-mailbox")))

(defn- mailbox-read-form? [form-str]
  (and (string? form-str)
       (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str "cljs.reader/read-string")))

(defn- with-staged-mailbox-eval!
  "Play the browser: record every form into `forms*`, answer the wrap form
  with the mailbox sentinel, the first `pending-polls` mailbox reads with
  `:pending` and later ones with `resolved`, and anything else (the
  raw-state signal) with nil. `read-count*`, when given, counts the reads."
  [{:keys [forms* read-count* pending-polls resolved]} body-fn]
  (let [reads (or read-count* (atom 0))]
    (install-stub! (fn [form-str]
                     (swap! forms* conj form-str)
                     (cond
                       (await-wrap-form? form-str)
                       (js/Promise.resolve {:rf.mcp/await-mailbox "settle-mbx"})

                       (mailbox-read-form? form-str)
                       (js/Promise.resolve
                         (if (<= (swap! reads inc) pending-polls)
                           {:status :pending}
                           {:status :resolved :value resolved}))

                       :else
                       (js/Promise.resolve nil))))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

(defn- wrap-form [forms*]
  (some #(when (await-wrap-form? %) %) @forms*))

(deftest await-render-waits-for-flush-then-resolves
  ;; The mailbox stays `:pending` for 3 polls; the tool resolves only once
  ;; it flips. The raw-state signal precedes the wrap form here too.
  (async done
    (let [forms       (atom [])
          read-count* (atom 0)
          resolved    {:ok? true :epoch-id 9 :frame :rf/default :settled? true
                       :cascade-summary {:renders 1 :event-id :counter/inc}}]
      (-> (with-staged-mailbox-eval!
            {:forms* forms :read-count* read-count* :pending-polls 3 :resolved resolved}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :await-render true})))
          (.then (fn [r]
                   (is (>= @read-count* 4) "polled past the pending phase")
                   (is (= (assoc resolved :mode :sync) (read-result-text r)))
                   (is (fires-before? @forms "configure-raw-state!" ":rf.mcp/await-mailbox"))
                   (done)))))))

(deftest await-render-runtime-failure-surfaces-as-error
  ;; A settle resolving to the runtime's `:ok? false` is a failed dispatch:
  ;; isError, with no `:mode` merged over it.
  (async done
    (let [resolved {:ok? false :reason :no-new-epoch :settled? true}]
      (-> (with-staged-mailbox-eval!
            {:forms* (atom []) :pending-polls 0 :resolved resolved}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :await-render true})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= resolved (read-result-text r)))
                   (done)))))))

(deftest await-render-timeout-surfaces-structured-error
  (async done
    (-> (with-staged-mailbox-eval!
          {:forms* (atom []) :pending-polls 1000000 :resolved {:ok? true}}
          (fn []
            (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:counter/inc]"
                                         :await-render true
                                         :timeout-ms 60})))
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :rf.error/dispatch-await-render-timeout (:reason edn)))
                   (is (= 60 (:timeout-ms edn))))
                 (done))))))

(deftest await-render-trace-projects-epoch-off-box-when-gate-off
  ;; Under `:await-render` an explicit `:trace` still returns the raw
  ;; epoch, so the settle form must project it as the non-await path does.
  (async done
    (let [forms (atom [])]
      (-> (with-staged-mailbox-eval!
            {:forms* forms :pending-polls 0 :resolved {:ok? true :epoch-id 9 :settled? true}}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]"
                                           :await-render true :trace true})))
          (.then (fn [_]
                   (let [form (wrap-form forms)]
                     (is (str/includes? form "dispatch-and-collect"))
                     (is (str/includes? form "re-frame.core/project-egress"))
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")))
                   (done)))))))

(deftest await-render-trace-include-sensitive-routes-through-projection
  (async done
    (let [forms (atom [])]
      (-> (with-staged-mailbox-eval!
            {:forms* forms :pending-polls 0 :resolved {:ok? true :epoch-id 9 :settled? true}}
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]"
                                           :await-render true :trace true
                                           :include-sensitive true})))
          (.then (fn [_]
                   (let [form (wrap-form forms)]
                     (is (str/includes? form "project-egress")
                         "include-sensitive still projects, never a raw bypass")
                     (is (str/includes? form ":rf.egress/include-sensitive? true")))
                   (done)))))))

(deftest await-render-event-payload-is-quoted-too
  ;; `:await-render` builds the call through `render-settle-form`, a second
  ;; route for the same parsed event.
  (async done
    (let [forms (atom [])]
      (-> (with-staged-mailbox-eval!
            {:forms* forms :pending-polls 0 :resolved {:ok? true :epoch-id 9 :settled? true}}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/add (inc 41)]"
                                           :await-render true})))
          (.then (fn [_]
                   (is (str/includes? (wrap-form forms) "(quote [:cart/add (inc 41)])"))
                   (done)))))))
