(ns re-frame.live-default-frame-test
  "A frame made without `:images` resolves the registration store live
  (Spec 002 §Image resolution and composition). A `[kind id]` registered from
  more than one namespace raises `:rf.error/ambiguous-registration` when it is
  used, naming the namespaces and the remedy, while every other id keeps
  working. A frame with explicit `:images` keeps its sealed generation and
  still fails assembly on the same duplicate.

  Every frame here is built through the public `rf/make-frame`, never the
  test fixture's ambient `:rf/default` (which resolves the registrar's latest
  registration, Spec 008 §Pattern 5), so these tests certify the contract an
  application runs.

  The probes register through the real `rf/reg-*` macros from real
  namespaces, so each registration carries the provenance a hot-reload
  refactor produces: a handler moved from `probe.a` to `probe.b` while
  `probe.a`'s slot survives.

  JVM-only: it creates namespaces and `eval`s registration forms in them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private probe-namespaces '[probe.a probe.b])

(defn- eval-in-ns
  "Evaluate `form` with `*ns*` bound to the namespace `ns-sym`, so a `rf/reg-*`
  macro in it captures that namespace as its provenance — exactly as
  `(ns ns-sym) (rf/reg-… …)` does at a REPL."
  [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rf 're-frame.core)
    (eval form)))

(defn- error-id
  "The `:rf.error/id` of what `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- thrown-data
  "The ex-data of what `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- ambiguities [records]
  (filterv #(= :rf.error/ambiguous-registration (:error %)) records))

(defn- with-recorded-errors
  "Run `(f seen)` with every always-on error record appended to the atom
  `seen`, and the probe namespaces removed afterwards."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (f seen)
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)
        (doseq [ns-sym probe-namespaces] (remove-ns ns-sym))))))

(def fx-runs
  "Which probe fx bodies ran, in order. Public so a form `eval`ed in a probe
  namespace can name it."
  (atom []))

(defn- register-a! []
  (eval-in-ns 'probe.a '(rf/reg-event :probe/ev {:doc "a"}
                          (fn [{:keys [db]} _] {:db (assoc db :who :a)}))))

(defn- move-to-b!
  "The refactor: `probe.b` now registers `:probe/ev` and adds `:probe/other`.
  `probe.a`'s slot for `:probe/ev` survives, because nothing removed it."
  []
  (eval-in-ns 'probe.b '(do (rf/reg-event :probe/ev {:doc "b"}
                              (fn [{:keys [db]} _] {:db (assoc db :who :b)}))
                            (rf/reg-event :probe/other {:doc "other"}
                              (fn [{:keys [db]} _] {:db (assoc db :other true)})))))

(deftest a-moved-handler-leaves-every-other-id-live
  (with-recorded-errors
    (fn [_seen]
      (register-a!)
      (rf/make-frame {:id :probe/frame})
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (is (= :a (:who (rf/app-db-value :probe/frame))))
      (move-to-b!)
      (rf/dispatch-sync [:probe/other] {:frame :probe/frame})
      (is (true? (:other (rf/app-db-value :probe/frame)))
          "a registration made after the frame exists reaches it, duplicate or not"))))

(deftest an-ambiguous-id-raises-at-use-naming-every-namespace
  (with-recorded-errors
    (fn [seen]
      (register-a!)
      (rf/make-frame {:id :probe/frame})
      (move-to-b!)
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (let [[record :as records] (ambiguities @seen)]
        (is (= 1 (count records)) "one always-on record per use")
        (is (= {:frame        :probe/frame
                :registration [:event :probe/ev]
                :namespaces   ["probe.a" "probe.b"]}
               (select-keys record [:frame :registration :namespaces])))
        (is (str/includes? (str (:reason record)) "(rf/clear :event :probe/ev)")
            "the record names the remedy"))
      (is (nil? (:who (rf/app-db-value :probe/frame)))
          "neither registration ran: load order picks no survivor")
      (rf/dispatch-sync [:probe/other] {:frame :probe/frame})
      (is (true? (:other (rf/app-db-value :probe/frame)))
          "every other id keeps working")
      (is (= 1 (count (ambiguities @seen)))
          "using another id raises nothing"))))

(deftest other-kinds-raise-where-they-are-used
  ;; An ambiguous fx is reported and skipped like an unknown one, and the walk
  ;; continues; an ambiguous subscription throws at the subscribe.
  (with-recorded-errors
    (fn [seen]
      (reset! fx-runs [])
      (doseq [ns-sym '[probe.a probe.b]]
        (eval-in-ns ns-sym '(do (rf/reg-fx :probe/fx (fn [_ _] nil))
                                (rf/reg-sub :probe/sub (fn [_ _] :either)))))
      (rf/reg-fx :probe/ok (fn [_ _] (swap! fx-runs conj :ok)))
      (rf/reg-event :probe/fires (fn [_ _] {:fx [[:probe/fx] [:probe/ok]]}))
      (rf/make-frame {:id :probe/frame})
      (rf/dispatch-sync [:probe/fires] {:frame :probe/frame})
      (is (= [:ok] @fx-runs) "the walk continues past the ambiguous fx")
      (is (= [{:event-id     :probe/fires
               :failing-id   :probe/fx
               :registration [:fx :probe/fx]
               :namespaces   ["probe.a" "probe.b"]}]
             (mapv #(select-keys % [:event-id :failing-id :registration :namespaces])
                   (ambiguities @seen))))
      (is (= :rf.error/ambiguous-registration
             (error-id #(deref (rf/subscribe [:probe/sub] {:frame :probe/frame}))))))))

(deftest clearing-the-id-and-re-evaluating-its-owner-is-the-remedy
  (with-recorded-errors
    (fn [seen]
      (register-a!)
      (rf/make-frame {:id :probe/frame})
      (move-to-b!)
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (is (= 1 (count (ambiguities @seen))))
      (rf/clear :event :probe/ev)
      (eval-in-ns 'probe.b '(rf/reg-event :probe/ev {:doc "b"}
                              (fn [{:keys [db]} _] {:db (assoc db :who :b)})))
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (is (= :b (:who (rf/app-db-value :probe/frame))))
      (is (= 1 (count (ambiguities @seen))) "the remedied id raises nothing"))))

(deftest a-frame-with-explicit-images-still-fails-assembly-on-the-duplicate
  (with-recorded-errors
    (fn [_seen]
      (register-a!)
      (move-to-b!)
      (is (nil? (error-id #(rf/make-frame {:id :probe/live})))
          "a frame made without :images is not refused for a duplicate it may never use")
      (is (= :rf.error/image-duplicate-id
             (error-id #(rf/make-frame {:id     :probe/sealed
                                        :images [(rf/image {:id        :probe/image
                                                            :select-ns {:include ["probe.**"]}})]})))))))

(deftest one-event-resolves-against-one-view-of-the-store
  ;; The handler re-registers the fx its own effects name. The event, its
  ;; interceptors and its :fx resolve against the store as it stood when the
  ;; event began, so the replacement takes effect from the next event.
  (with-recorded-errors
    (fn [_seen]
      (reset! fx-runs [])
      (eval-in-ns 'probe.a '(rf/reg-fx :probe/fx
                              (fn [_ _] (swap! re-frame.live-default-frame-test/fx-runs conj :old))))
      (rf/reg-event :probe/ev
        (fn [_ _]
          (eval-in-ns 'probe.a '(rf/reg-fx :probe/fx
                                  (fn [_ _] (swap! re-frame.live-default-frame-test/fx-runs conj :new))))
          {:fx [[:probe/fx]]}))
      (rf/make-frame {:id :probe/frame})
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (is (= [:old] @fx-runs) "a registration made mid-event does not split it")
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (is (= [:old :new] @fx-runs) "the next event sees it"))))

(deftest an-application-registration-of-a-framework-standard-is-refused
  (with-recorded-errors
    (fn [_seen]
      (let [standard (rf/handler-meta {:source :store :kind :interceptor :id :rf.interceptor/path})
            data     (thrown-data
                       #(eval-in-ns 'probe.a '(rf/reg-interceptor :rf.interceptor/path
                                                {:before (fn [ctx] ctx)})))]
        (is (= :rf.error/image-standard-replacement-forbidden (:rf.error/id data)))
        (is (= [:interceptor :rf.interceptor/path] [(:kind data) (:id data)]))
        (is (= standard (rf/handler-meta {:source :store :kind :interceptor :id :rf.interceptor/path}))
            "the refused registration wrote nothing")))))

(deftest handler-meta-for-a-live-frame-reports-what-it-runs
  (with-recorded-errors
    (fn [_seen]
      (register-a!)
      (rf/make-frame {:id :probe/frame})
      (move-to-b!)
      (is (= "other" (:doc (rf/handler-meta {:frame :probe/frame :kind :event :id :probe/other}))))
      (is (= :rf.error/ambiguous-registration
             (error-id #(rf/handler-meta {:frame :probe/frame :kind :event :id :probe/ev})))
          "an ambiguous id reports the error the frame raises, not a registration it never runs")
      (is (= #{:probe/other}
             (set (filter #{:probe/ev :probe/other}
                          (keys (rf/registrations {:frame :probe/frame :kind :event})))))
          "the frame's catalogue lists the ids it can run"))))
