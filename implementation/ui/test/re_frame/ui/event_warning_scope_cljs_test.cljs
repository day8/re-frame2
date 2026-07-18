(ns re-frame.ui.event-warning-scope-cljs-test
  "The `re-frame.ui.events` dev-warning contracts around the #6027 ui/event
  widening (rf2-677rf):

    * the INVOCATION-time (committed `ui/event` RESULT) arms — a committed
      `event-handler` whose body returns an unregistered vector carrying a
      placeholder fires BOTH `:rf.warning/unregistered-event-id` and
      `:rf.warning/placeholder-in-dynamic-vector` when the stable callback fires,
      and the unchanged vector still dispatches as ordinary data; and

    * the GENERATION-SCOPED unregistered lookup — existence is resolved through
      the committed frame's OWN sealed image generation (exactly as dispatch
      resolves the handler), not the process-global registrar, so an id present
      only in the frame image does not warn and an id present only process-current
      but absent from the frame image DOES warn — the inverse of the bare
      process-registrar read.

  Node-runtime (`renderToStaticMarkup`-free): the committed callbacks are
  published through the internal EventOwner test seam and invoked directly — the
  exact function a DOM node would call — under the plain-atom substrate so real
  image-loaded frames carry a generation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.events :as core-events]
            [re-frame.image :as image]
            [re-frame.live-frame :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            [re-frame.ui.events :as events]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter plain-atom/adapter
    :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private debug-site
  {:view-id ::warn-probe
   :sid "evt-0"
   :source-coord [::warn-probe 12 4]
   :path [:events 0]})

(defn- event-desc
  "An image-resolver descriptor for an event id whose handler is `handler-fn` —
  the runnable `event-handler-meta` shape merged with the provenance / kind / id
  slots image assembly keys by (mirrors the core generation-resolution tests)."
  [provenance-ns id handler-fn]
  (merge (core-events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(defn- committed-callback
  "Publish one `ui/event` site through the internal seam, commit it against
  `frame-ops`, and return the stable committed callback whose invocation runs
  `(fn [native-event] -> result-vec)`."
  [frame-ops result-vec]
  (let [owner (events/make-owner ::warn-probe)
        cb    (atom nil)
        capture (nth (events/with-capture
                       owner (:frame frame-ops)
                       (fn []
                         (reset! cb (events/event-handler
                                     "evt-0" (fn [_] result-vec) 0 debug-site))
                         nil))
                     1)]
    (events/commit! owner capture frame-ops)
    @cb))

(defn- warnings-during
  "Invoke `callback` with a bare native event while capturing every warning
  trace event; return the vector of captured warning envelopes."
  [callback]
  (let [seen (atom [])
        key  ::warn-scope-capture]
    (trace/register-listener! key (fn [ev] (when (= :warning (:op-type ev))
                                             (swap! seen conj ev))))
    (try (callback #js {})
         (finally (trace/unregister-listener! key)))
    @seen))

(defn- warning-ops [warnings] (into #{} (map :operation) warnings))

(defn- warning-of [warnings op]
  (some #(when (= op (:operation %)) %) warnings))

;; ---------------------------------------------------------------------------
;; Contract 1 — the INVOCATION-time (committed ui/event RESULT) warning arms.
;; The focused render-path fixture (react-render-cljs-test) covers the
;; render-classified path; this proves the committed event-handler RESULT path.
;; ---------------------------------------------------------------------------

(deftest committed-ui-event-result-arms-both-invocation-warnings
  (let [dispatched (atom [])
        frame-ops  {:frame nil
                    :dispatch      (fn [ev _] (swap! dispatched conj ev))
                    :dispatch-sync (fn [ev _] (swap! dispatched conj ev))}
        ;; An unregistered id AND a top-level placeholder in the runtime result.
        callback   (committed-callback frame-ops [::unregistered-x :rf.ui/value])
        warnings   (warnings-during callback)
        unreg      (warning-of warnings :rf.warning/unregistered-event-id)
        placeholder (warning-of warnings :rf.warning/placeholder-in-dynamic-vector)]
    (testing "both invocation-time arms are structured trace warnings"
      (is (= #{:rf.warning/unregistered-event-id
               :rf.warning/placeholder-in-dynamic-vector}
             (warning-ops warnings))
          "a committed ui/event result vector arms BOTH warnings at invocation"))
    (testing "the unregistered-event-id envelope"
      (is (= {:operation :rf.warning/unregistered-event-id
              :op-type :warning
              :recovery :warned-and-continued}
             (select-keys unreg [:operation :op-type :recovery])))
      (is (= {:event-id ::unregistered-x
              :view-id ::warn-probe
              :site-id "evt-0"
              :source-coord [::warn-probe 12 4]
              :occurrence-path [:events 0]}
             (select-keys (:tags unreg)
                          [:event-id :view-id :site-id :source-coord
                           :occurrence-path]))
          "the committed-result envelope carries the debug-site tags")
      ;; rf2-jxpf3 — placement, not just presence. The emit site supplies
      ;; `:recovery` INSIDE the tags map, but `build-event` strips it and
      ;; hoists it to the envelope top level (asserted above); and a
      ;; `:warning` envelope synthesizes NO `{:category operation}` (that
      ;; merge is the `:error` branch only). Both must be absent HERE, or
      ;; the Spec 009 catalogue row's `:tags` column would be naming keys
      ;; no consumer can read. Per [009 §Error event catalogue] and
      ;; [Spec-Schemas §`:rf/error-event`].
      (is (not (contains? (:tags unreg) :recovery))
          ":recovery is hoisted to the top level, NOT left under :tags")
      (is (not (contains? (:tags unreg) :category))
          "a :warning envelope synthesizes no [:tags :category] — the
           category rides the top-level :operation"))
    (testing "the placeholder-in-dynamic-vector envelope"
      (is (= {:operation :rf.warning/placeholder-in-dynamic-vector
              :op-type :warning
              :recovery :warned-and-continued}
             (select-keys placeholder [:operation :op-type :recovery])))
      (is (= {:event [::unregistered-x :rf.ui/value]
              :placeholder :rf.ui/value
              :view-id ::warn-probe
              :site-id "evt-0"
              :source-coord [::warn-probe 12 4]
              :occurrence-path [:events 0]}
             (select-keys (:tags placeholder)
                          [:event :placeholder :view-id :site-id :source-coord
                           :occurrence-path])))
      ;; rf2-jxpf3 — same placement contract on the sibling warning.
      (is (not (contains? (:tags placeholder) :recovery))
          ":recovery is hoisted to the top level, NOT left under :tags")
      (is (not (contains? (:tags placeholder) :category))
          "a :warning envelope synthesizes no [:tags :category]"))
    (testing "the unchanged vector still dispatches as ordinary data"
      (is (= [[::unregistered-x :rf.ui/value]] @dispatched)
          "the placeholder keyword rides through as data — NOT DOM-projected"))))

;; ---------------------------------------------------------------------------
;; Contract 2 — the unregistered lookup resolves through the COMMITTED frame's
;; sealed image generation (as dispatch does), NOT the process registrar.
;; ---------------------------------------------------------------------------

(deftest unregistered-lookup-resolves-through-the-committed-frame-generation
  ;; `::process-only` is registered PROCESS-current; `::image-only` lives ONLY in
  ;; the frame image. A bare process-registrar read and the frame-generation read
  ;; give OPPOSITE answers — dispatch (and this warning) must take the frame's.
  (rf/reg-event ::process-only (fn [{:keys [db]} _] {:db db}))
  (let [pool [(event-desc "warn.scope.image" ::image-only
                          (fn [{:keys [db]} _] {:db db}))]
        img  (image/image {:id ::warn-scope-image
                           :select-ns {:include ["warn.scope.image"]}})
        _    (lf/make-frame {:id ::gen :images [img]} pool)
        no-op (fn [_ _])
        gen-ops {:frame ::gen :dispatch no-op :dispatch-sync no-op}
        proc-ops {:frame nil :dispatch no-op :dispatch-sync no-op}
        ops-for  (fn [frame-ops result]
                   (warning-ops (warnings-during
                                 (committed-callback frame-ops result))))]
    (testing "resolved through the frame image generation (the committed frame)"
      (is (not (contains? (ops-for gen-ops [::image-only])
                          :rf.warning/unregistered-event-id))
          "an id present only in the frame image does NOT warn (found via the gen)")
      (is (contains? (ops-for gen-ops [::process-only])
                     :rf.warning/unregistered-event-id)
          "an id present only process-current but absent from the frame image WARNS"))
    (testing "the bare process-registrar read gives the INVERSE answer"
      ;; Proves the frame-scoped resolution is load-bearing, not a no-op: with no
      ;; frame generation bound (a nil target) the two ids flip.
      (is (contains? (ops-for proc-ops [::image-only])
                     :rf.warning/unregistered-event-id)
          "process-scoped: the image-only id is (wrongly) unknown → warns")
      (is (not (contains? (ops-for proc-ops [::process-only])
                          :rf.warning/unregistered-event-id))
          "process-scoped: the process-registered id is (wrongly) suppressed"))))
