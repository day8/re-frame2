(ns day8.re-frame2-xray.panels.reply-envelope-cljs-test
  "Pure-data tests for Xray's ONE work/reply vocabulary reading the uniform
  reply envelope (EP-0011): phase classification, the cross-family trace-row
  projection, the stale-races and live-work joins, frame scoping, and the
  work-id display redaction."
  (:require [clojure.test :refer [are deftest is testing]]
            [day8.re-frame2-xray.panels.reply-envelope :as re]))

(deftest closed-vocabularies
  ;; The bundle-isolation mirror of `re-frame.reply/statuses`: the EP-0011 closed five.
  (is (= #{:ok :partial :error :cancelled :stale} re/reply-statuses)))

(deftest reply-map?-predicate
  (is (re/reply-map? {:status :ok :value 1}))
  (is (not (re/reply-map? {:status :running}))))

;; ---------------------------------------------------------------------------
;; phase-of — the explicit table entries the suffix heuristic would miss, and
;; the heuristic itself.
;; ---------------------------------------------------------------------------

(deftest phase-classification
  (are [op phase] (= phase (re/phase-of op))
    :rf.machine.timer/stale-after          :stale-suppressed
    :rf.machine.spawn-all/late-completion  :stale-suppressed
    :rf.machine.spawn/stale-completion     :stale-suppressed
    ;; a real completion by its suffix, NOT lowered to stale
    :rf.machine.spawn-all/child-completed  :completed
    ;; a not-yet-enumerated family op is classified by its suffix
    :rf.stream/work-started                :issued
    ;; the heuristic does not admit an arbitrary `*-completion` op
    :rf.other/late-completion              nil))

;; ---------------------------------------------------------------------------
;; work-event-row / project-work-events — cross-family trace projection.
;; ---------------------------------------------------------------------------

(def ^:private mixed-trace
  "Resource + HTTP reply-envelope rows plus one non-managed row."
  [{:id 1 :operation :rf.sub/run :tags {}}
   {:id 2 :operation :rf.resource/work-started
    :time 100 :tags {:work/id [:rf.work/resource :k 1] :rf.frame/id :f
                     :generation 1 :owner [:dashboard/opened :a]}}
   {:id 3 :operation :rf.resource/fetch-started
    :time 110 :tags {:work/id [:rf.work/http :req 1] :rf.frame/id :f}}
   {:id 4 :operation :rf.resource/work-abort-requested
    :time 120 :tags {:work/id [:rf.work/resource :k 1] :reason :superseded}}
   {:id 5 :operation :rf.resource/stale-suppressed
    :time 130 :tags {:rf.reply/work-id [:rf.work/resource :k 1]
                     :rf.reply/carried {:work/id [:rf.work/resource :k 1] :generation 1}
                     :rf.reply/current {:work/id [:rf.work/resource :k 2] :generation 2}
                     :outcome :success}}
   {:id 6 :operation :rf.http/replied
    :time 140 :tags {:work/id [:rf.work/http :req 1] :work/kind :http :status :ok}}])

(deftest work-event-row-projection
  (testing "an issuance row: phase, inferred kind, work id and frame"
    (is (= {:id 2 :operation :rf.resource/work-started :phase :issued
            :work-kind :resource :work-id [:rf.work/resource :k 1] :frame :f
            :work-status nil :time 100 :dispatch-id nil}
           (re/work-event-row (nth mixed-trace 1)))))
  (testing "a completion row carries the reply status and its cross-surface class"
    (is (= {:id 6 :operation :rf.http/replied :phase :completed
            :work-kind :http :work-id [:rf.work/http :req 1] :frame nil
            :work-status nil :time 140 :dispatch-id nil
            :status :ok :status-class :success}
           (re/work-event-row (nth mixed-trace 5)))))
  (testing "a cancel-requested row carries the cancel reason"
    (is (= {:id 4 :operation :rf.resource/work-abort-requested :phase :cancel-requested
            :work-kind :resource :work-id [:rf.work/resource :k 1] :frame nil
            :work-status nil :time 120 :dispatch-id nil
            :cancelled? true :cancel-reason :superseded}
           (re/work-event-row (nth mixed-trace 3)))))
  (testing "each non-stale closed status projects with its badge class off :rf.reply/status"
    (are [s class] (= [s class]
                      ((juxt :status :status-class)
                       (re/work-event-row {:operation :rf.http/work-completed
                                           :tags      {:rf.reply/status s}})))
      :ok        :success
      :partial   :partial
      :error     :failure
      :cancelled :cancellation))
  (testing "an explicit :mutation kind wins over the :rf.work/resource head it reuses"
    (is (= :mutation
           (:work-kind (re/work-event-row
                         {:operation :rf.resource/stale-suppressed
                          :tags      {:work/kind        :mutation
                                      :rf.reply/work-id [:rf.work/resource [:s :update-article {:id 1}] 2]}})))))
  (testing "HTTP's bare [:tags :frame] is read through the canonical raw-event reader"
    (is (= :app/main
           (:frame (re/work-event-row {:operation :rf.http/stale-suppressed
                                       :tags      {:frame :app/main}})))))
  (testing "project-work-events keeps the managed rows in buffer order and drops the rest"
    (is (= [2 3 4 5 6] (mapv :id (re/project-work-events mixed-trace))))))

(deftest production-reply-trace-vocabulary
  (testing "a row carrying its identity only as the additive :rf.reply/* facts
            keeps its work id, status and work status"
    (is (= {:id 10 :operation :rf.machine/done :phase :completed
            :work-kind :machine :work-id [:rf.work/machine :auth#1 [:fetch] 1] :frame nil
            :work-status :completed :time 200 :dispatch-id nil
            :status :ok :status-class :success}
           (re/work-event-row
             {:id 10 :operation :rf.machine/done :time 200
              :tags {:work/kind            :machine
                     :rf.reply/status      :ok
                     :rf.reply/work-id     [:rf.work/machine :auth#1 [:fetch] 1]
                     :rf.reply/work-status :completed}})))))

;; ---------------------------------------------------------------------------
;; stale-suppressed rows read the canonical :stale status off the unambiguous
;; :rf.reply/status, never the bare :status (a LEDGER status on these rows).
;; ---------------------------------------------------------------------------

(deftest stale-suppression-preserves-status
  (testing "a production-shaped resource stale row projects the full stale envelope,
            its wire-bearing slots summarized"
    (let [work-id [:rf.work/resource [:s :article/by-id {:id 1}] 1]
          row     (re/work-event-row
                    {:id 14 :operation :rf.resource/stale-suppressed
                     :time 240
                     :tags {:rf.frame/id           :rf/default
                            :resource/key          [:s :article/by-id {:id 1}]
                            :generation            1
                            :outcome               {:reason :stale-reply}
                            :rf.reply/status       :stale
                            :rf.reply/work-status  :suppressed
                            :rf.reply/work-id      work-id
                            :rf.reply/stale-reason :rf.resource/superseded
                            :rf.reply/correlation  {:generation   {:carried 1 :current 2}
                                                    :resource/key [:s :article/by-id {:id 1}]}
                            :rf.reply/carried      {:work/id work-id :generation 1}
                            :rf.reply/current      {:generation 2}}})]
      (is (= {:id 14 :operation :rf.resource/stale-suppressed :phase :stale-suppressed
              :work-kind :resource :work-id work-id :frame :rf/default
              :work-status :suppressed :time 240 :dispatch-id nil
              :status :stale :status-class :suppression
              :stale? true :completed-at nil :stale-reason :rf.resource/superseded}
             (dissoc row :carried :current :correlation)))
      (is (= ["map" "map" "map"] (mapv (comp :type row) [:carried :current :correlation])))))
  (testing "a bare LEDGER :status :completed on a suppression row is not read as a reply status"
    (is (= [:stale-suppressed nil]
           ((juxt :phase :status)
            (re/work-event-row {:operation :rf.resource/stale-suppressed
                                :tags      {:rf.reply/work-id [:rf.work/resource :k 1]
                                            :status           :completed}}))))))

(deftest spawn-all-stale-completions
  (testing "an exact-attempt stale completion surfaces its completion time and
            its :rf.reply/work-kind over the public :kind tag"
    (is (= {:id 70 :operation :rf.machine.spawn-all/stale-completion :phase :stale-suppressed
            :work-kind :machine :work-id [:rf.work/machine :fetch-user#1 [:hydrating :spawn-all] 1]
            :frame :rf/default :work-status :suppressed :time 470 :dispatch-id nil
            :status :stale :status-class :suppression
            :stale? true :carried nil :current nil :completed-at 471
            :stale-reason :rf.machine.spawn-all/attempt-unverified}
           (dissoc (re/work-event-row
                     {:id 70 :operation :rf.machine.spawn-all/stale-completion :time 470
                      :tags {:actor-id :hydrate#1 :invoke-id [:hydrating :spawn-all]
                             :child-id :fetch-user :kind :done
                             :rf.frame/id           :rf/default
                             :rf.reply/work-id      [:rf.work/machine :fetch-user#1 [:hydrating :spawn-all] 1]
                             :rf.reply/work-kind    :machine
                             :rf.reply/status       :stale
                             :rf.reply/work-status  :suppressed
                             :rf.reply/stale-reason :rf.machine.spawn-all/attempt-unverified
                             :rf.reply/correlation  {:parent-id :hydrate#1 :child-id :fetch-user
                                                     :spawned-id :fetch-user#1}
                             :rf.reply/completed-at 471}})
                   :correlation)))))

;; ---------------------------------------------------------------------------
;; stale-races — keyed on :work/id; the cross-surface tally; attempt arcs.
;; ---------------------------------------------------------------------------

(deftest stale-races-keyed-on-work-id
  (testing "the stale tally counts suppressions per work-kind across families"
    (is (= {:resource 1 :http 1}
           (re/stale-tally-by-kind
             (conj mixed-trace {:id 7 :operation :rf.reply/suppressed
                                :time 150 :tags {:work/id [:rf.work/http :req 9]}})))))
  (testing "races-by-work-id groups each attempt arc on its work id"
    (let [races (re/races-by-work-id mixed-trace)
          arc   #(select-keys (get races %) [:work-kind :phases :terminal-status :suppressed?])]
      (is (= {:work-kind :resource :phases #{:issued :cancel-requested :stale-suppressed}
              :terminal-status :stale :suppressed? true}
             (arc [:rf.work/resource :k 1])))
      (is (= {:work-kind :http :phases #{:issued :completed}
              :terminal-status :ok :suppressed? false}
             (arc [:rf.work/http :req 1]))))))

;; ---------------------------------------------------------------------------
;; live-work — ledger rows joined to reply status + trace cause.
;; ---------------------------------------------------------------------------

(def ^:private ledger
  "A frame work ledger in the production shape: opaque string keys, the
  kind-preserving work id on each record as :work/id."
  {"w-res-2"  {:work/id [:rf.work/resource :k 2] :work/kind :resource :work/frame :f
               :generation 2 :status :running :owners #{[:dashboard/opened :a]}
               :causes [[:ensure :article/by-id]] :cancellable? true
               :started-at 1000 :transport :rf.http/managed}
   "w-res-1"  {:work/id [:rf.work/resource :k 1] :work/kind :resource :work/frame :f
               :generation 1 :status :suppressed :owners #{} :causes []
               :outcome {:reason :stale-reply}}
   "w-http-5" {:work/id [:rf.work/http :req 5] :work/frame :f
               :generation 1 :status :abort-requested :owners #{} :causes []
               :transport :rf.http/managed}})

(deftest ledger-row-projection
  (let [row (re/ledger-row (find ledger "w-res-2"))]
    (is (= {:work-id [:rf.work/resource :k 2] :work-id-text "[:rf.work/resource :k 2]"
            :work-kind :resource :status :running :live? true :frame :f
            :owners [[:dashboard/opened :a]] :cancellable? true :started-at 1000
            :deadline-at nil :attempt 2 :transport :rf.http/managed :outcome nil}
           (dissoc row :causes)))
    (is (= ["vector"] (mapv :type (:causes row))) "causes are summarized"))
  (is (= "map" (:type (:outcome (re/ledger-row (find ledger "w-res-1")))))
      "a terminal row's outcome is summarized")
  (is (= :http (:work-kind (re/ledger-row (find ledger "w-http-5"))))
      "the kind is inferred from the record's :work/id when it names none"))

(deftest live-work-join
  (testing "live-work keeps the non-terminal rows and joins each, by its
            :work/id vector, to its latest trace phase and op"
    (let [trace [{:id 1 :operation :rf.resource/work-started :time 100
                  :tags {:work/id [:rf.work/resource :k 2]}}
                 {:id 2 :operation :rf.http/aborted-on-actor-destroy :time 110
                  :tags {:work/id [:rf.work/http :req 5] :reason :actor-destroyed}}]]
      (is (= {[:rf.work/resource :k 2] [:issued :rf.resource/work-started]
              [:rf.work/http :req 5]   [:cancel-requested :rf.http/aborted-on-actor-destroy]}
             (into {} (map (juxt :work-id (juxt :latest-phase :latest-op)))
                   (re/live-work ledger trace)))))))

;; ---------------------------------------------------------------------------
;; frame scoping — a work id is frame-LOCAL, so two frames loading the same
;; resource at the same generation mint the same one.
;; ---------------------------------------------------------------------------

(def ^:private shared-work-id
  [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "x"}] 1])

(defn- resource-row
  ([id op frame] (resource-row id op frame nil))
  ([id op frame extra]
   {:id id :operation op :time (* 10 id)
    :tags (merge {:rf.frame/id        frame
                  :rf.reply/work-id   shared-work-id
                  :rf.reply/work-kind :resource}
                 extra)}))

(def ^:private two-frame-buffer
  "Frame :app/a issued W and is still waiting; frame :app/b issued the SAME W
  and it completed."
  [(resource-row 1 :rf.resource/work-started :app/a)
   (resource-row 2 :rf.resource/work-started :app/b)
   (resource-row 3 :rf.resource/succeeded :app/b {:rf.reply/status :ok})])

(def ^:private ledger-a
  {"opaque-a" {:work/id shared-work-id :work/kind :resource :work/frame :app/a
               :generation 1 :status :running :owners #{} :causes []}})

(deftest reply-reads-scope-to-one-frame
  (testing "live-work over one frame's rows labels its work with that frame's own latest phase"
    (is (= [:issued :rf.resource/work-started]
           ((juxt :latest-phase :latest-op)
            (first (re/live-work ledger-a
                                 (re/trace-buffer-for-frame two-frame-buffer :app/a)))))))
  (testing "nil escapes — no frame named keeps every row, and a row carrying
            no frame is unattributable rather than foreign"
    (is (= two-frame-buffer (re/trace-buffer-for-frame two-frame-buffer nil)))
    (let [frameless {:id 5 :operation :rf.resource/work-started :tags {}}]
      (is (= [frameless] (re/trace-buffer-for-frame [frameless] :app/a)))))
  (testing "the raw-event [:tags :frame] spelling is read too"
    (is (= [] (re/trace-buffer-for-frame
                [{:id 6 :operation :rf.http/stale-suppressed :tags {:frame :app/b}}]
                :app/a)))))

;; ---------------------------------------------------------------------------
;; the displayed work id — a resource work id embeds its scoped key.
;; ---------------------------------------------------------------------------

(def ^:private id-secret "tok-work-id-secret-5b2")

(defn- secret-work-id
  "A resource work id whose scope and params both carry `id-secret`."
  [rid]
  [:rf.work/resource [[:rf.scope/session {:token id-secret}] rid {:slug id-secret}] 5])

(def ^:private secret-ledger
  "One running record per resource; only `:article/by-slug` is `:sensitive?`."
  {"opaque-s" {:work/id (secret-work-id :article/by-slug) :work/kind :resource
               :generation 5 :status :running}
   "opaque-c" {:work/id (secret-work-id :comments/list) :work/kind :resource
               :generation 5 :status :running}})

(defn- secret-trace
  "One trace row per resource's work id, on `op`, carrying `tags`."
  [op tags]
  (vec (map-indexed (fn [i rid]
                      {:id i :operation op :time (* 10 (inc i))
                       :tags (assoc tags :rf.reply/work-id (secret-work-id rid))})
                    [:article/by-slug :comments/list])))

(def ^:private sensitive-rids #{:article/by-slug})

(def ^:private redacted-text
  (str [:rf.work/resource [:rf/redacted :article/by-slug :rf/redacted] 5]))

(defn- by-rid
  "Index rows by the resource-id inside their raw work id."
  [rows]
  (into {} (map (juxt #(get-in % [:work-id 1 1]) identity)) rows))

(deftest work-id-text-redacts-a-sensitive-resource
  (let [live  (by-rid (re/live-work secret-ledger
                                    (secret-trace :rf.resource/work-started {})
                                    sensitive-rids))
        races (by-rid (vals (re/races-by-work-id
                              (secret-trace :rf.resource/stale-suppressed
                                            {:rf.reply/status :stale})
                              sensitive-rids)))]
    (testing "the live-work and stale-race rows print the sensitive resource's
              work id with its scope and params redacted"
      (is (= redacted-text (:work-id-text (:article/by-slug live))))
      (is (= redacted-text (:work-id-text (:article/by-slug races)))))
    (testing "CONTROL — a resource that is not :sensitive? prints its work id as-is"
      (is (= (str (secret-work-id :comments/list))
             (:work-id-text (:comments/list live))
             (:work-id-text (:comments/list races)))))
    (testing "the raw work id stays on the row, so the trace join still keys on it"
      (is (= :issued (:latest-phase (:article/by-slug live)))))))
