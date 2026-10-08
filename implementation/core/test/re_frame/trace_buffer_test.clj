(ns re-frame.trace-buffer-test
  "Per-frame event-keyed trace rings: bundle reads, eviction, retention
  policy, clears, filters, dispatch correlation defaults, the frame-level
  emission gate and hot-reload dedup. Per Spec 009 §Per-frame trace rings
  (event-keyed, dev-only) and §Dispatch correlation. JVM-only by intent —
  the ring and router are platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (rf.trace/clear-frame-no-emit!)
  (rf/configure! {:trace-buffer {:events-retained 50}})
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; init! does not synthesise :rf/default (EP-0002); emit sites need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; Every deftest is ^:requires-debug: emit! is a no-op under
;; -Dre-frame.debug=false (see scripts/test-core-prod-gate.sh).

(defn- flat-events
  ([frame-id] (rf/trace-buffer frame-id {:flat true}))
  ([frame-id opts] (rf/trace-buffer frame-id (assoc opts :flat true))))

(defn- dispatched-events [evs]
  (filterv #(and (= :rf.event (:op-type %)) (= :rf.event/dispatched (:operation %))) evs))

;; ---- bundles and eviction --------------------------------------------------

(deftest ^:requires-debug trace-buffer-appends-events-as-cascades
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db (assoc db :seen? true)}))
  (rf/dispatch-sync [:ping])
  (let [c (first (rf/trace-buffer :rf/default))]
    (is (= [:rf/default [:ping] :rf.event/dispatched]
           [(:frame c) (:event c) (:operation (:dispatched c))]))
    (is (seq (:trace-events c)))))

;; Eviction must copy survivors out of a subvec: a subvec keeps its whole
;; backing vector reachable, so the :run-order spine would grow by one
;; dispatch-id per run for the life of the ring.

(defn- ring-spine-size
  "How many dispatch-ids `frame-id`'s `:run-order` keeps REACHABLE."
  [frame-id]
  (let [order (get-in @@#'rf.trace.tooling/trace-rings [frame-id :run-order])]
    (if (instance? clojure.lang.APersistentVector$SubVector order)
      (count (.-v ^clojure.lang.APersistentVector$SubVector order))
      (count order))))

(deftest ^:requires-debug run-order-spine-stays-bounded-by-the-cap
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (let [sizes (fn [fid] [(count (rf/trace-buffer fid)) (ring-spine-size fid)])]
    (testing "evicting on push"
      (rf/configure! {:trace-buffer {:events-retained 3}})
      (dotimes [_ 10] (rf/dispatch-sync [:ping]))
      (is (= [3 3] (sizes :rf/default))))
    (testing "trimming an inherited ring through configure!"
      (rf/configure! {:trace-buffer {:events-retained 50}})
      (dotimes [_ 20] (rf/dispatch-sync [:ping]))
      (rf/configure! {:trace-buffer {:events-retained 2}})
      (is (= [2 2] (sizes :rf/default))))
    (testing "resizing a used ring to a per-frame override"
      (rf/configure! {:trace-buffer {:events-retained 50}})
      (rf/make-frame {:id :tb/spine})
      (dotimes [_ 20] (rf/dispatch-sync [:ping] {:frame :tb/spine}))
      (rf.trace.tooling/apply-frame-events-retained-policy! :tb/spine true 4 (constantly true))
      (is (= [4 4] (sizes :tb/spine))))))

(deftest ^:requires-debug cascade-burst-cannot-evict-prior-cascades
  (testing "eviction counts event bundles, not raw trace events"
    (rf/configure! {:trace-buffer {:events-retained 5}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 5] (rf/dispatch-sync [:ping]))
    (let [target-id (:dispatch-id (nth (rf/trace-buffer :rf/default) 2))]
      (binding [rf.trace/*handler-scope* (rf.trace/->HandlerScope nil nil target-id false false)]
        (dotimes [_ 200]
          (rf.trace/emit! :rf.sub :rf.sub/skip {:rf.sub/id :foo :frame :rf/default})))
      (let [cs (rf/trace-buffer :rf/default)]
        (is (= 5 (count cs)))
        (is (< 200 (count (:trace-events (first (filter #(= target-id (:dispatch-id %)) cs))))))))))

;; Dispatch ids are unique only within a frame; each frame owning its own ring
;; is what keeps a bundle from carrying another frame's events.
(deftest ^:requires-debug frame-isolation-trace-events-carry-only-their-own-frame
  (rf/make-frame {:id :iso/a})
  (rf/make-frame {:id :iso/b})
  (rf/reg-event :iso/a-inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/reg-event :iso/b-inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (dotimes [_ 3]
    (rf/dispatch-sync [:iso/a-inc] {:frame :iso/a})
    (rf/dispatch-sync [:iso/b-inc] {:frame :iso/b}))
  (doseq [[fid ev] [[:iso/a [:iso/a-inc]] [:iso/b [:iso/b-inc]]]]
    (let [bundles (rf/trace-buffer fid)]
      (is (= [ev ev ev] (mapv :event bundles)) (str fid " holds its own three runs"))
      (is (= #{fid} (set (for [b bundles e (:trace-events b)] (get-in e [:tags :frame]))))
          (str "no foreign event leaks into " fid)))))

(deftest ^:requires-debug frameless-emits-bypass-the-ring
  (binding [rf.trace/*handler-scope* nil]
    (rf.trace/emit! :rf.registry :rf.registry/handler-registered
                    {:kind :event :id :synthetic/probe}))
  (is (empty? (rf/trace-buffer :rf/default))))

;; ---- retention policy ------------------------------------------------------

(deftest ^:requires-debug per-frame-events-retained-override
  (testing ":rf.trace/events-retained on make-frame caps that frame; others inherit the default"
    (rf/configure! {:trace-buffer {:events-retained 5}})
    ;; Set before the first emit, then exceeded: eviction runs over the ring
    ;; the policy wrote, which must already be complete and vector-backed.
    (rf/make-frame {:id :tb/deep :rf.trace/events-retained 8})
    (rf/make-frame {:id :tb/shallow})
    (rf/reg-event :tb/spam (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 10]
      (rf/dispatch-sync [:tb/spam] {:frame :tb/deep})
      (rf/dispatch-sync [:tb/spam] {:frame :tb/shallow}))
    (let [deep (rf/trace-buffer :tb/deep)]
      (is (= [8 5] [(count deep) (count (rf/trace-buffer :tb/shallow))]))
      (is (apply < (map :dispatch-id deep)) "retained bundles are oldest-first"))))

(deftest ^:requires-debug events-retained-zero-disables-retention
  (testing "{:events-retained 0} retains nothing while the live stream still fires"
    (rf/configure! {:trace-buffer {:events-retained 0}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (let [recv (atom 0)]
      (rf/register-listener! :trace ::probe (fn [_] (swap! recv inc)))
      (rf/dispatch-sync [:ping])
      (rf/unregister-listener! :trace ::probe)
      (is (= [] (rf/trace-buffer :rf/default)))
      (is (pos? @recv)))))

(deftest ^:requires-debug configure-retunes-inherited-but-preserves-override
  (rf/configure! {:trace-buffer {:events-retained 50}})
  (rf/make-frame {:id :tb/inherit})
  (rf/make-frame {:id :tb/pinned :rf.trace/events-retained 8})
  (rf/reg-event :spam (fn [{:keys [db]} _] {:db db}))
  (let [counts #(mapv (comp count rf/trace-buffer) [:tb/inherit :tb/pinned])]
    (dotimes [_ 10]
      (rf/dispatch-sync [:spam] {:frame :tb/inherit})
      (rf/dispatch-sync [:spam] {:frame :tb/pinned}))
    (is (= [10 8] (counts)))
    (testing "lowering the default trims the inherited ring and caps later pushes"
      ;; Every ring stores :events-retained; :override? is what tells the two apart.
      (rf/configure! {:trace-buffer {:events-retained 2}})
      (is (= [2 8] (counts)))
      (rf/dispatch-sync [:spam] {:frame :tb/inherit})
      (is (= [2 8] (counts))))
    (testing "a 0 default disables the inherited ring only"
      (rf/configure! {:trace-buffer {:events-retained 0}})
      (is (= [0 8] (counts))))))

(deftest ^:requires-debug per-frame-override-zero-before-first-emit-disables-cleanly
  (rf/make-frame {:id :tb/silent :rf.trace/events-retained 0})
  (rf/reg-event :tb/ev (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:tb/ev] {:frame :tb/silent})
  (is (= [] (rf/trace-buffer :tb/silent))))

;; ---- clears ----------------------------------------------------------------

(deftest ^:requires-debug clear-trace-buffer-clears-only-the-named-frame
  (rf/make-frame {:id :app/a})
  (rf/make-frame {:id :app/b})
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:frame :app/a})
  (rf/dispatch-sync [:ping] {:frame :app/b})
  (rf/clear-trace-buffer! :app/a)
  (is (= [] (rf/trace-buffer :app/a)))
  (is (seq (rf/trace-buffer :app/b)) ":app/b's ring is intact"))

;; The 0-arity clear empties DATA and keeps retention policy: a "clear
;; buffer" affordance must not revert the user's :events-retained.
(deftest ^:requires-debug clear-trace-buffer-0-arity-clears-every-ring-preserving-policy
  (rf/configure! {:trace-buffer {:events-retained 7}})
  (rf/make-frame {:id :tb/inherits})
  (rf/make-frame {:id :tb/override :rf.trace/events-retained 3})
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (let [counts #(mapv (comp count rf/trace-buffer) [:tb/inherits :tb/override])
        ping!  #(doseq [fid [:tb/inherits :tb/override]] (rf/dispatch-sync [:ping] {:frame fid}))]
    (ping!)
    (rf/clear-trace-buffer!)
    (is (= [0 0] (counts)) "every ring is emptied")
    (dotimes [_ 10] (ping!))
    (is (= [7 3] (counts)) "the process default and the override survive the clear")
    (rf/configure! {:trace-buffer {:events-retained 2}})
    (is (= [2 3] (counts)) ":override? survives the clear, so only the inherited ring retunes")))

(deftest ^:requires-debug frame-destroy-clears-the-rings
  (rf/make-frame {:id :app/transient})
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:frame :app/transient})
  (rf.frame/destroy-frame! :app/transient)
  (is (= [] (rf/trace-buffer :app/transient))))

;; ---- filter vocabulary -----------------------------------------------------

(deftest ^:requires-debug trace-buffer-filters-narrow-to-matching-events
  (rf/reg-event :ev/alpha  (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :ev/beta   (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :ev/throws (fn [_ _] {:db (throw (ex-info "boom" {}))}))
  (rf/dispatch-sync [:ev/alpha] {:origin :pair :source :repl})
  (let [t0    (:time (first (flat-events :rf/default)))
        pivot (last (flat-events :rf/default))
        did   (get-in pivot [:tags :rf.trace/dispatch-id])]
    (Thread/sleep 5)
    (rf/dispatch-sync [:ev/beta] {:origin :story :source :after-timer})
    (rf/dispatch-sync [:ev/throws])
    (testing "event-level filters (:flat true)"
      (doseq [[opts matches?]
              [[{:operation :rf.event/dispatched} #(= :rf.event/dispatched (:operation %))]
               [{:op-type :rf.event}              #(= :rf.event (:op-type %))]
               [{:pred (fn [ev] (#{:rf.event :error} (:op-type ev)))}
                #(#{:rf.event :error} (:op-type %))]
               [{:since (:id pivot)}              #(> (:id %) (:id pivot))]
               [{:since-ms (:time pivot)}         #(> (:time %) (:time pivot))]
               [{:between [t0 (:time pivot)]}     #(<= t0 (:time %) (:time pivot))]
               [{:severity :error}                #(= :error (:op-type %))]
               [{:event-id :ev/alpha}             #(= :ev/alpha (get-in % [:tags :rf.trace/event-id]))]
               [{:handler-id :ev/throws}          #(= :ev/throws (get-in % [:tags :handler-id]))]
               [{:source :repl}                   #(= :repl (or (:source %) (get-in % [:tags :source])))]
               [{:origin :pair}                   #(= :pair (get-in % [:tags :rf.event/origin]))]
               [{:dispatch-id did}                #(= did (get-in % [:tags :rf.trace/dispatch-id]))]]]
        (let [evs (flat-events :rf/default opts)]
          (is (seq evs) (str opts " matches at least one event"))
          (is (every? matches? evs) (str opts " keeps only the events it matches")))))
    (testing "bundle-level filters"
      (is (= [[:ev/alpha]] (mapv :event (rf/trace-buffer :rf/default {:event-id :ev/alpha}))))
      (is (= [[:ev/alpha]] (mapv :event (rf/trace-buffer :rf/default {:origin :pair}))))
      (is (= [did] (mapv :dispatch-id (rf/trace-buffer :rf/default {:dispatch-id did})))))))

;; ---- dispatch correlation defaults -----------------------------------------

(deftest ^:requires-debug dispatch-defaults-origin-to-app-and-source-to-unknown
  (testing "an un-stamped root dispatch: :origin :app, top-level :source :unknown, no parent"
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:ping])
    (is (= [[:app :unknown nil]]
           (mapv (juxt #(get-in % [:tags :rf.event/origin])
                       :source
                       #(get-in % [:tags :rf.trace/parent-dispatch-id]))
                 (dispatched-events (flat-events :rf/default)))))))

;; ---- frame-level trace-emission gate ---------------------------------------

(deftest ^:requires-debug app-frame-emits-trace-while-tool-frame-silent
  (rf/make-frame {:id :tool/inspector :rf.trace/frame-no-emit? true})
  (rf/make-frame {:id :app/main})
  (rf/reg-event :work (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
  (rf/dispatch-sync [:work] {:frame :tool/inspector})
  (rf/dispatch-sync [:work] {:frame :app/main})
  (is (seq (rf/trace-buffer :app/main)) "control: the app frame's ring grows")
  (is (= [] (rf/trace-buffer :tool/inspector))))

;; destroy-frame! must drop the id from the process-global trace-disabled set,
;; or every destroyed tool frame leaks one entry.
(deftest ^:requires-debug destroy-clears-trace-disabled-flag
  (rf/make-frame {:id :tool/a :rf.trace/frame-no-emit? true})
  (rf/make-frame {:id :tool/b :rf.trace/frame-no-emit? true})
  (rf.frame/destroy-frame! :tool/a)
  (is (= [false true] (mapv rf.trace/frame-trace-disabled? [:tool/a :tool/b]))
      "the destroyed frame's flag is cleared; the surviving tool frame's is untouched"))

;; An emit with no :frame tag resolves suppression through the ambient frame;
;; reading only [:tags :frame] would let a tool frame's own emits escape.
(deftest ^:requires-debug untagged-emit-suppressed-when-current-frame-is-tool-disabled
  (rf/make-frame {:id :tool/inspector :rf.trace/frame-no-emit? true})
  (let [seen (atom [])]
    (rf/register-listener! :trace ::untagged (fn [ev] (swap! seen conj ev)))
    (rf/with-frame :tool/inspector
      (rf.trace/emit! :rf.view :rf.view/render {:rf.view/render-key [:some/view nil]}))
    (rf/unregister-listener! :trace ::untagged)
    (is (= [] @seen))))

;; ---- hot-reload dedup-by-shape (Spec 009 §Hot-reload dedup) ----------------

(deftest ^:requires-debug hot-reload-changed-handler-emits-one-trace
  (let [recv (atom [])]
    (rf/reg-event :ev/hot {:doc "v1"} (fn [{:keys [db]} _] {:db (assoc db :v 1)}))
    (rf/register-listener! :trace ::probe
                           (fn [ev]
                             (when (= :rf.registry/handler-replaced (:operation ev))
                               (swap! recv conj ev))))
    (rf/reg-event :ev/hot {:doc "v1"} (fn [{:keys [db]} _] {:db (assoc db :v 2)}))
    (rf/unregister-listener! :trace ::probe)
    (is (= [:ev/hot] (mapv #(get-in % [:tags :id]) @recv)))))

(deftest ^:requires-debug hot-reload-dedup-clears-on-reset
  (testing "clear-listeners! resets the dedup table, so an identical re-registration emits again"
    (let [handler-fn (fn [{:keys [db]} _] {:db db})
          recv       (atom [])]
      (rf/reg-event :ev/cycle {:doc "doc"} handler-fn)
      (rf.trace.tooling/clear-listeners!)
      (rf/register-listener! :trace ::probe
                             (fn [ev]
                               (when (and (= :rf.registry (:op-type ev))
                                          (= :ev/cycle (-> ev :tags :id)))
                                 (swap! recv conj ev))))
      (rf/reg-event :ev/cycle {:doc "doc"} handler-fn)
      (rf/unregister-listener! :trace ::probe)
      (is (seq @recv)))))
