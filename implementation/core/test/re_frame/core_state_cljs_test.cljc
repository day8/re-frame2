(ns re-frame.core-state-cljs-test
  "`rf/reg-state` — per-instance view state as ordinary frame-local events and
  subscriptions, with no view substrate at all (Spec 002 §Per-instance view
  state). The mounted claims are the adapters' and Fresco's own suites.

  Dual-runtime `-cljs-test`; the plain-atom substrate on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.core-state :as rf.core-state]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.events :as rf.events]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- frame! [id]
  (rf/make-frame {:id id})
  id)

(defn- read* [frame-id query]
  (rf/with-frame frame-id (deref (rf/subscribe query))))

(defn- send! [frame-id event]
  (rf/with-frame frame-id (rf/dispatch-sync event))
  nil)

(defn- db-of [frame-id] (rf/app-db-value frame-id))

(defn- refusals
  "Every framework ex-data `thunk` produced, from BOTH paths: a direct throw
  (registration, on the caller's own stack) and the always-on error channel
  (a read or a write, whose throw the runtime captures and fans). Watching
  one path only would be green over the other."
  [thunk]
  (let [direct  (volatile! nil)
        records (volatile! [])]
    (rf.error-emit/register-error-listener! ::state (fn [r] (vswap! records conj r)))
    (try
      (try (thunk) (catch #?(:clj Throwable :cljs :default) e (vreset! direct (ex-data e))))
      (finally (rf.error-emit/unregister-error-listener! ::state)))
    (->> (concat [@direct]
                 (map (comp ex-data :exception) @records)
                 (map (comp ex-data ex-cause :exception) @records))
         (filter #(some-> % :rf.error/id namespace (= "rf.error")))
         (vec))))

(defn- refusal
  "The ex-data of the `id` refusal `thunk` produced, or nil. Selected by id,
  because a refused read arrives as a sub-exception carrying ours as its
  cause, and the first record would be the wrapper."
  [id thunk]
  (first (filter #(= id (:rf.error/id %)) (refusals thunk))))

(defn- refused?
  "Did `thunk` refuse with `id`, naming `concern`?"
  [id concern thunk]
  (let [d (refusal id thunk)]
    (boolean (and d (= concern (:concern d)) (string? (:reason d))))))

;; ---------------------------------------------------------------------------
;; Independence — the whole reason the key is explicit
;; ---------------------------------------------------------------------------

(deftest two-instances-of-one-widget-are-independent
  (testing "two disclosures on one page: one concern, two keys, two values"
    (rf/reg-state ::open? {:default false})
    (let [f (frame! ::independent)]
      (is (= false (read* f [::open? :billing])) "unwritten reads the default")
      (is (= false (read* f [::open? :shipping])))
      (send! f [::open? :billing true])
      (is (= true  (read* f [::open? :billing])))
      (is (= false (read* f [::open? :shipping]))
          "the sibling did NOT move — the row an unkeyed concern would
           make silently false")
      (is (= {:ui {::open? {:billing true}}} (db-of f))
          "one entry, at the documented app-space path, concern first"))))

(deftest two-frames-hold-independent-values
  (rf/reg-state ::open? {:default false})
  (let [a (frame! ::frame-a)
        b (frame! ::frame-b)]
    (send! a [::open? :billing true])
    (is (= true  (read* a [::open? :billing])))
    (is (= false (read* b [::open? :billing]))
        "app-db is per-frame, so the same concern and key in another frame is
         another value")))

(deftest a-default-is-per-concern-and-any-value
  (rf/reg-state ::draft {:default ""})
  (rf/reg-state ::tab {:default :first})
  (let [f (frame! ::defaults)]
    (is (= "" (read* f [::draft [:order/id 42]])))
    (is (= :first (read* f [::tab :panel])))
    (testing "a concern registered with no options at all defaults to nil"
      (rf/reg-state ::no-opts)
      (is (nil? (read* f [::no-opts :x]))))))

;; ---------------------------------------------------------------------------
;; A frame with explicit :images — the concern belongs to its caller's image
;; ---------------------------------------------------------------------------

(defn- imaged-frame!
  "A frame whose one image selects exactly the namespaces `include`."
  [id include]
  (rf/make-frame {:id id :images [(rf/image {:id (keyword "core-state.img" (name id))
                                             :select-ns {:include include}})]})
  id)

(defn- resolves? [frame-id kind id]
  (some? (rf/handler-meta {:frame frame-id :kind kind :id id})))

(defn- reg-from-other-app!
  "An event registered from namespace `core-state.other-app`, recorded the way
  that namespace's `reg-*` macro would record it."
  []
  (binding [rf.source-coords/*pending-coords* {:ns   'core-state.other-app
                                               :file "core_state/other_app.cljc"
                                               :line 1}]
    (rf.events/reg-event :core-state.other-app/boot (fn [_ _] {}))))

(deftest an-explicit-image-selecting-the-caller-runs-its-concern-as-an-implicit-frame-does
  (testing "a concern registered here belongs to this namespace, so an image
           that selects this namespace carries its sub and setter beside the
           namespace's ordinary registrations, the shared clear resolves from
           the framework base, and the frame reads, writes and clears exactly
           as a frame made without :images does"
    (rf/reg-sub ::control (fn [db _] (get db :control 0)))
    (rf/reg-event ::control (fn [{:keys [db]} _] {:db (update db :control (fnil inc 0))}))
    (rf/reg-event ::seed (fn [{:keys [db]} _] {:db (assoc-in db [:ui ::open? :shipping] true)}))
    (rf/reg-state ::open? {:default false})
    (let [implicit (frame! ::implicit)
          imaged   (imaged-frame! ::imaged ["re-frame.core-state-cljs-test"])]
      (is (= [true true true true]
             [(resolves? imaged :sub ::open?) (resolves? imaged :event ::open?)
              (resolves? imaged :event :rf.state/clear) (resolves? imaged :sub ::control)]))
      (doseq [f [implicit imaged]]
        (testing (str "in " f)
          (is (= false (read* f [::open? :billing])) "unwritten reads the default")
          (send! f [::open? :billing true])
          (is (= true (read* f [::open? :billing])))
          (is (= {::open? {:billing true}} (:ui (db-of f))) "written at [:ui concern ikey]")
          (send! f [::control])
          (is (= 1 (read* f [::control])) "the ordinary control registration runs too")
          (send! f [:rf.state/clear ::open? :billing])
          (is (= false (read* f [::open? :billing])) "cleared back to the default")
          (send! f [::seed])
          (is (= true (read* f [::open? :shipping])) "an entry another event seeded")
          (send! f [:rf.state/clear ::open? :shipping])
          (is (= {} (:ui (db-of f))) "clears like a written one"))))))

(deftest an-explicit-image-not-selecting-the-caller-still-resolves-clear
  (testing "the clear event is the framework's, whichever namespace registered
           a concern, so it resolves where the concern does not"
    (reg-from-other-app!)
    (rf/reg-state ::open? {:default false})
    (let [f (imaged-frame! ::elsewhere ["core-state.other-app"])]
      (is (= [false false true]
             [(resolves? f :sub ::open?) (resolves? f :event ::open?)
              (resolves? f :event :rf.state/clear)])))))

;; ---------------------------------------------------------------------------
;; Clear — a framework-named EVENT, and a removal
;; ---------------------------------------------------------------------------

(deftest the-clear-event-is-reserved
  (is (= :rf.state/clear rf.core-state/clear-event-id))
  (is (= :ui rf.core-state/ui-root)))

(deftest clear-restores-the-default-by-removing-the-entry
  (testing "the entry is GONE, not set to the default — one representation
           of unset, and the concern map is pruned once it empties"
    (rf/reg-state ::open? {:default false})
    (let [f (frame! ::cleared)]
      (send! f [::open? :billing true])
      (is (= {:ui {::open? {:billing true}}} (db-of f)))
      (send! f [:rf.state/clear ::open? :billing])
      (is (= false (read* f [::open? :billing])) "back to the default")
      (is (= {:ui {}} (db-of f))
          "the entry is removed AND the emptied concern map is pruned"))))

(deftest clear-leaves-its-siblings-alone
  (rf/reg-state ::open? {:default false})
  (let [f (frame! ::clear-sibling)]
    (send! f [::open? :billing true])
    (send! f [::open? :shipping true])
    (send! f [:rf.state/clear ::open? :billing])
    (is (= {:ui {::open? {:shipping true}}} (db-of f))
        "one key removed, the concern map kept because it is not empty")
    (is (= false (read* f [::open? :billing])))
    (is (= true  (read* f [::open? :shipping])))))

(deftest a-keyword-valued-concern-survives-being-set-to-any-value
  (testing "a value-sentinel clear would make this row a silent dissoc: a
           concern whose values are keywords could be set to the sentinel by
           legitimate domain data. Clear is an event, so there is no value
           this concern cannot hold."
    (rf/reg-state ::tab {:default :first})
    (let [f (frame! ::sentinel-free)]
      (doseq [v [:rf.state/clear nil false]]
        (send! f [::tab :panel v])
        (is (= v (read* f [::tab :panel]))
            (str "the concern holds " (pr-str v) " as an ordinary value"))
        (is (contains? (get-in (db-of f) [:ui ::tab]) :panel)
            "and the entry is still THERE — no value clears anything")))))

;; ---------------------------------------------------------------------------
;; The loud refusals
;; ---------------------------------------------------------------------------

(deftest a-bad-instance-key-is-refused-at-the-read-naming-the-concern
  (rf/reg-state ::open? {:default false})
  (let [f (frame! ::bad-read)]
    (testing "nil — what a missing prop, a mistyped destructure and a
             forgotten argument all evaluate to"
      (is (refused? :rf.error/state-bad-argument ::open?
                    #(read* f [::open? nil]))))
    (testing "a map — a whole entity handed over where its id was meant. Read
             ONCE: the runtime recovers a failed sub to nil and CACHES it, so a
             second read of the same query fans nothing."
      (let [d (refusal :rf.error/state-bad-argument
                       #(read* f [::open? {:id 1}]))]
        (is (= {:concern ::open? :instance-key {:id 1} :op :read :where 'rf/reg-state}
               (select-keys d [:concern :instance-key :op :where])))))
    (testing "and a missing key altogether"
      (is (refused? :rf.error/state-bad-argument ::open?
                    #(read* f [::open?]))))))

(deftest a-bad-instance-key-is-refused-at-the-write-naming-the-concern
  (rf/reg-state ::open? {:default false})
  (let [f (frame! ::bad-write)]
    (is (refused? :rf.error/state-bad-argument ::open?
                  #(send! f [::open? nil true])))
    (is (refused? :rf.error/state-bad-argument ::open?
                  #(send! f [::open? {:id 1} true])))
    (testing "and nothing was written"
      (is (= {} (db-of f))
          "a refused write is a refused write — the read-side check alone
           would have left this entry in the db"))
    (testing "the refusal names the side it fired on"
      (is (= :write (:op (refusal :rf.error/state-bad-argument
                                  #(send! f [::open? nil true])))))))

  (testing "clear refuses a bad key too, naming the clear event"
    (rf/reg-state ::open? {:default false})
    (let [f (frame! ::bad-clear)
          d (refusal :rf.error/state-bad-argument
                     #(send! f [:rf.state/clear ::open? nil]))]
      (is (= {:concern ::open? :op :clear :where 'rf.state/clear}
             (select-keys d [:concern :op :where]))))))

(deftest registration-refuses-an-unqualified-concern
  (is (refused? :rf.error/state-bad-argument :open?
                #(rf/reg-state :open? {:default false}))))

(deftest registration-refuses-an-unknown-option
  (testing "an option that is quietly ignored is a setting its author
           believes is in force"
    (is (refused? :rf.error/state-bad-argument ::typo
                  #(rf/reg-state ::typo {:default false :defualt true})))
    (is (refused? :rf.error/state-bad-argument ::not-a-map
                  #(rf/reg-state ::not-a-map [:default false])))
    (testing "and the refusal names what it did not recognise"
      (is (= [:defualt]
             (:unknown (refusal :rf.error/state-bad-argument
                                #(rf/reg-state ::typo2 {:defualt true}))))))))

(deftest a-refusal-is-the-canonical-thrown-error-shape
  (let [d (refusal :rf.error/state-bad-argument #(rf/reg-state :open?))]
    (is (= {:rf.error/id :rf.error/state-bad-argument
            :where       'rf/reg-state
            :recovery    :no-recovery}
           (select-keys d [:rf.error/id :where :recovery])))
    (is (string? (:reason d)))))

(deftest re-registering-replaces-the-registration-and-the-last-default-wins
  (rf/reg-state ::reloaded {:default 0})
  (testing "a DIFFERENT default is the ordinary hot-reload edit: every un-set
           instance reads the new default"
    (is (= ::reloaded (rf/reg-state ::reloaded {:default 1})))
    (let [f (frame! ::reg-twice)]
      (is (= 1 (read* f [::reloaded :a]))))))

;; ---------------------------------------------------------------------------
;; Instance keys as values
;; ---------------------------------------------------------------------------

(def ^:private order-a #uuid "00000000-0000-4000-8000-00000000000a")

(deftest a-uuid-is-an-instance-key
  (rf/reg-state ::open? {:default false})
  (let [f (frame! ::uuid-key)]
    (send! f [::open? order-a true])
    (is (= true (read* f [::open? order-a])))
    (is (= false (read* f [::open? [:order/id order-a]]))
        "the entity-qualified spelling of the same id is a different key")))

(deftest instance-key-admits-exactly-the-composable-shapes
  (testing "admitted"
    (doseq [k [:kw "s" 0 order-a [:order/id order-a] [:a 1 "b"] [[:order/id 42] :row] []]]
      (is (rf.core-state/instance-key? k) (str (pr-str k) " is an instance key"))))
  (testing "refused"
    (doseq [k [nil false {} #{:a} '(:a) [:a nil]]]
      (is (not (rf.core-state/instance-key? k)) (str (pr-str k) " is not an instance key")))))

(deftest child-key-nests-and-deep-nests
  (testing "a scalar parent key becomes a two-element vector"
    (is (= [:panel :row] (rf.core-state/child-key :panel :row))))
  (testing "a vector parent key CONJes — so depth costs one element, not one
           level of nesting, and no component needs to know how deep it is"
    (is (= [:panel :row :cell] (rf.core-state/child-key [:panel :row] :cell))))
  (testing "and every key it produces is a legal instance key, which is what
           makes nesting total"
    (is (rf.core-state/instance-key? (rf.core-state/child-key [[:order/id 42]] :row)))))
