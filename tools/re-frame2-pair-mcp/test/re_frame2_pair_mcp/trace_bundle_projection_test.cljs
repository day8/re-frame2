(ns re-frame2-pair-mcp.trace-bundle-projection-test
  "The `snapshot` tool's `:traces` arm, run as rendered.

  `epoch-egress/project-trace-bundles-src` is built by string
  concatenation and evaluated app-side, so these tests READ the rendered
  source back as CLJS data and interpret it. The interpreter covers
  exactly the forms the arm renders and throws on an unresolved symbol,
  so a rewritten arm fails loudly rather than passing unexercised. The
  trace-ring fold is the framework's own (`re-frame.trace.projection`).
  Core without the Epoch artefact is the real thing too — this build
  carries no Epoch, so `re-frame.features/features` reports it absent and
  `re-frame.projection/project-egress` throws for a stamped record. With
  Epoch, a stub stands in for the late-bound epoch projector.

  The projected bundles then go through the `snapshot` tool's own wire
  pipeline, because that is where a bundle whose `:trace-events` is not a
  sequence meets the server-side sensitive filter."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame.features :as rf.features]
            [re-frame.projection :as rf.projection]
            [re-frame.trace.projection :as rf.trace.projection]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.snapshot :as snap]))

(use-fixtures :each
  {:before (fn [] (raw-state/reset-runtime-signal-cache!))
   :after  (fn []
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))})

;; ---------------------------------------------------------------------------
;; Interpreting the rendered arm
;; ---------------------------------------------------------------------------

(defn- run-form
  "Evaluate `form`, read from rendered source, with `locals` bound and
  `env` resolving every other symbol. Handles `let`, single-arity `fn`,
  `if`, keyword lookup and calls."
  [form locals env]
  (let [run #(run-form % locals env)]
    (cond
      (symbol? form)
      (cond
        (contains? locals form) (get locals form)
        (contains? env form)    (get env form)
        :else (throw (ex-info "unresolved symbol in the rendered :traces arm"
                              {:symbol form})))

      (map? form)    (into {} (map (fn [[k v]] [(run k) (run v)])) form)
      (vector? form) (mapv run form)

      (seq? form)
      (let [[op & args] form]
        (cond
          (= 'let op)
          (let [[bindings & body] args
                scope (reduce (fn [l [s e]] (assoc l s (run-form e l env)))
                              locals
                              (partition 2 bindings))]
            (last (map #(run-form % scope env) body)))

          (= 'fn op)
          (let [[params & body] args]
            (fn [& xs]
              (let [scope (merge locals (zipmap params xs))]
                (last (map #(run-form % scope env) body)))))

          (= 'if op)
          (let [[c t e] args]
            (if (run c) (run t) (run e)))

          :else (apply (run op) (map run args))))

      :else form)))

(def ^:private core-fns
  {'mapv        mapv
   'reduce      reduce
   'assoc       assoc
   'get-in      get-in
   'sequential? sequential?
   're-frame.trace.projection/absorb             rf.trace.projection/absorb
   're-frame.trace.projection/empty-event-bundle rf.trace.projection/empty-event-bundle})

(def ^:private core-only
  "Core as this build carries it: no Epoch artefact."
  (assoc core-fns
         're-frame.core/features       rf.features/features
         're-frame.core/project-egress rf.projection/project-egress))

(defn- with-epoch
  "Core with the Epoch artefact loaded, its record projector played by
  `project`, called as `(project record opts)`. Every door call lands in
  `calls`."
  [project calls]
  (assoc core-fns
         're-frame.core/features       (fn [] {:epoch {:loaded? true}})
         're-frame.core/project-egress (fn [record opts]
                                         (swap! calls conj [record opts])
                                         (project record opts))))

(defn- ship
  "The bundles the rendered arm ships for `bundles` under `env`. A throw
  is reported as a failure and yields nil."
  [env bundles]
  (try
    (run-form (reader/read-string (egress/project-trace-bundles-src "ts" false))
              {'ts bundles}
              env)
    (catch :default e
      (is false (str "the :traces arm threw: " (ex-message e) " " (pr-str (ex-data e))))
      nil)))

;; ---------------------------------------------------------------------------
;; A ring bundle, and two projector answers for it
;; ---------------------------------------------------------------------------

(def ^:private fid :app/main)
(def ^:private secret "hunter2-do-not-ship")

(def ^:private raw-events
  [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.event/v [:login secret] :rf.trace/parent-dispatch-id 3}}
   {:id 2 :op-type :rf.event :operation :rf.event/run-end
    :tags {:rf.event/v [:login secret]}}
   {:id 3 :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.fx/args {:password secret}}}])

(def ^:private ring-bundle
  "The bundle `trace-buffer` builds for one run: the fold over the raw events."
  (reduce rf.trace.projection/absorb
          (assoc rf.trace.projection/empty-event-bundle
                 :dispatch-id 7 :frame fid :trace-events raw-events)
          raw-events))

(defn- redact-args
  "An ordinary frame: event and effect arguments redact per event."
  [record _opts]
  (update record :trace-events
          (fn [evs]
            (mapv (fn [ev]
                    (cond-> ev
                      (get-in ev [:tags :rf.event/v])
                      (update-in [:tags :rf.event/v] (fn [[id]] [id :rf/redacted]))
                      (get-in ev [:tags :rf.fx/args])
                      (assoc-in [:tags :rf.fx/args] :rf/redacted)))
                  evs))))

(defn- redact-root
  "A frame classifying its app-db root sensitive (`{:sensitive [[]]}`): the
  projector answers the whole `:trace-events` slot with the sentinel."
  [record _opts]
  (assoc record :trace-events :rf/redacted))

(def ^:private redacted-bundle
  "A bundle whose trace events could not ship: the projected slot beside
  the template's empty domino slots."
  (assoc rf.trace.projection/empty-event-bundle
         :dispatch-id 7 :frame fid :trace-events :rf/redacted))

;; ---------------------------------------------------------------------------
;; Through the snapshot tool
;; ---------------------------------------------------------------------------

(defn- snapshot-of
  "Promise of the `snapshot` envelope (`:mode full`, app-db and traces)
  when the runtime answers with `frame-slices` for `fid`."
  [frame-slices]
  (let [orig   nrepl/cljs-eval-value
        answer (fn [form]
                 (js/Promise.resolve
                   (cond
                     (str/includes? form "__re_frame2_pair_runtime") true
                     (str/includes? form "configure-raw-state!")     nil
                     :else {:value                {fid frame-slices}
                            :elided-count         0
                            :tool-frames-excluded []})))
        stub   (fn
                 ([_conn _build-id form]       (answer form))
                 ([_conn _build-id form _opts] (answer form)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (snap/snapshot-tool nil (tu/args->js {:frames  #js [(str fid)]
                                                       :include #js ["app-db" "traces"]
                                                       :mode    "full"}))))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- check-snapshot
  "Ship `frame-slices` through the snapshot tool and check the frame comes
  back whole: no error, the same slices, and no secret on the wire."
  [frame-slices done]
  (-> (snapshot-of frame-slices)
      (.then (fn [result]
               (is (not (tu/error? result)) (tu/extract-text result))
               (is (= frame-slices (get-in (tu/extract-edn result) [:snapshot fid])))
               (is (not (str/includes? (str (tu/extract-text result)) secret)))))
      (.catch (fn [e] (is false (str "snapshot failed: " e))))
      (.then (fn [_] (done)))))

;; ---------------------------------------------------------------------------
;; The tests
;; ---------------------------------------------------------------------------

(deftest a-whole-slot-redaction-ships-as-the-slot
  ;; The projector may answer a root-sensitive frame's `:trace-events` with
  ;; `:rf/redacted` rather than a sequence. That answer is the slot; the
  ;; domino slots stay empty rather than being rebuilt from the original
  ;; events, and the snapshot still ships.
  (async done
    (let [calls (atom [])]
      (is (= [redacted-bundle] (ship (with-epoch redact-root calls) [ring-bundle])))
      (is (= 1 (count @calls)) "the door is still asked"))
    (check-snapshot {:app-db {:count 1} :traces [redacted-bundle]} done)))

(deftest without-epoch-only-the-trace-payload-fails-closed
  ;; A core-only app has a trace ring but no epoch projector, and the door
  ;; throws for a stamped record. Each bundle's trace events ship as
  ;; `:rf/redacted`, and the snapshot's other slices still read.
  (async done
    (is (false? (get-in (rf.features/features) [:epoch :loaded?]))
        "precondition: this build carries no Epoch artefact")
    (is (= [redacted-bundle] (ship core-only [ring-bundle])))
    (check-snapshot {:app-db {:count 1} :traces [redacted-bundle]} done)))

(deftest with-epoch-each-bundle-is-folded-from-its-projected-events
  ;; The control: with Epoch loaded the bundle's events reach the real door
  ;; inside a stamped record, and every domino slot is a view of the
  ;; projected events.
  (let [calls     (atom [])
        projected (:trace-events (redact-args {:trace-events raw-events} nil))
        [shipped] (ship (with-epoch redact-args calls) [ring-bundle])]
    (is (= [[{:kind :rf/epoch-record :frame fid :trace-events raw-events}
             {:rf.egress/profile :rf.egress/off-box-tool}]]
           @calls))
    (is (= (reduce rf.trace.projection/absorb
                   (assoc rf.trace.projection/empty-event-bundle
                          :dispatch-id 7 :frame fid :trace-events projected)
                   projected)
           shipped))
    (is (= [:login :rf/redacted] (:event shipped)))
    (is (= 3 (:parent-dispatch-id shipped)))
    (is (not (str/includes? (pr-str shipped) secret)))))
