(ns day8.re-frame2-xray.panels.l2-timeline-cljs-test
  "Pure-data tests for the L2 epoch-timeline row helpers: the source tag,
  the handler-duration column, and the issue wash."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test    :refer-macros [are deftest is]])
            [day8.re-frame2-xray.panels.l2-timeline :as l2]))

;; ---- fixture builders ---------------------------------------------------

(defn- cascade-with-source
  "An event-bundle record whose `:dispatched` trace carries `source` under
  `:tags` (the shape `re-frame.trace.projection/group-by-event` buckets)."
  [source]
  {:dispatch-id 42
   :event       [:cart/add-item {:id 99}]
   :dispatched  {:operation :rf.event/dispatched
                 :op-type   :rf.event
                 :tags      {:source                source
                             :rf.trace/dispatch-id  42}}
   :other       []
   :errors      []})

(defn- cascade-with-duration
  "An event-bundle whose `:handler` run-end trace carries the producer's
  `:rf.event/elapsed-ms`."
  [duration-ms]
  {:dispatch-id 7
   :event       [:poll/tick]
   :handler     {:operation :rf.event/run-end
                 :op-type   :rf.event
                 :tags      {:rf.event/elapsed-ms  duration-ms
                             :rf.trace/dispatch-id 7}}})

(defn- ev
  "A synthetic trace event with the given operation."
  [operation & {:keys [op-type tags] :or {op-type nil tags {}}}]
  (cond-> {:operation operation :tags tags}
    op-type (assoc :op-type op-type)))

;; ---- source column ------------------------------------------------------

(deftest source-of-test
  (is (= :tool (l2/source-of (cascade-with-source :tool)))))

(deftest origin-source-tag-test
  ;; App-code sources and an untagged bundle read `ui`, so no row's source
  ;; cell is blank; a substrate source reads its bare name.
  (are [tag source] (= tag (l2/origin-source-tag source))
    "ui"     :ui
    "ui"     nil
    "router" :router))

;; ---- duration column ----------------------------------------------------

(deftest event-bundle-duration-ms-test
  (is (= 1.234 (l2/event-bundle-duration-ms (cascade-with-duration 1.234))))
  (is (= 2.5 (l2/event-bundle-duration-ms
               {:handler {:operation :rf.event/run-end
                          :tags      {:duration-ms 2.5}}}))
      "falls back to :duration-ms when :rf.event/elapsed-ms is absent"))

(deftest event-bundle-duration-label-test
  ;; One decimal place plus ` ms` on both runtimes; nil leaves the cell empty.
  (are [label bundle] (= label (l2/event-bundle-duration-label bundle))
    "1.2 ms"  (cascade-with-duration 1.234)
    "12.0 ms" (cascade-with-duration 12)
    nil       {}))

;; ---- issue wash ---------------------------------------------------------
;;
;; `event-bundle-has-issue?` lights the L2 row's `:bg-issue-row` wash for
;; exactly the set the Issues ribbon aggregates, by reusing
;; `issues-ribbon-helpers/issue-event?`.

(deftest event-bundle-has-issue?-test
  (are [issue? bundle] (= issue? (l2/event-bundle-has-issue? bundle))
    ;; lifecycle chatter in :other is not an issue
    false {:other [(ev :rf.machine/transition :op-type :rf.machine)
                   (ev :rf.frame/created      :op-type :rf.frame)]}
    true  {:other [(ev :rf.error/handler-exception :op-type :error)]}
    ;; the :errors slot alone lights it too
    true  {:errors [{:operation :rf.error/no-such-fx}]}))
