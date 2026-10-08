(ns re-frame.frame-resolver-test
  "The central frame resolver (Spec 002 §Frame target resolution): readers
  return the scope frame or nil and never synthesise `:rf/default`;
  `require-current-frame!` returns the carried stamp or raises
  `:rf.error/no-frame-context`; and the refusal tier lets a substrate withdraw
  the ambient reach for an extent it owns.

  Runs cold (no fixture pins `*current-frame*`), so 'outside any scope' is
  genuinely outside any scope."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn cold-start [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.trace.tooling/clear-listeners!)
  (rf.error-emit/clear-error-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn)
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf.error-emit/clear-error-listeners!))

(use-fixtures :each cold-start)

(deftest resolve-current-frame-returns-nil-outside-scope
  (is (nil? (rf.frame/resolve-current-frame)) "no :rf/default floor")
  (binding [rf.frame/*current-frame* :app]
    (is (= :app (rf.frame/resolve-current-frame)))))

(deftest require-current-frame-returns-stamp-even-when-frame-unregistered
  ;; no registry lookup: an unregistered target is :frame-destroyed's job, later
  (binding [rf.frame/*current-frame* :never-registered]
    (is (= :never-registered (rf.frame/require-current-frame! :dispatch)))))

(deftest require-current-frame-raises-no-frame-context-outside-scope
  (let [data (try
               (rf.frame/require-current-frame! :dispatch {:where 're-frame.router/dispatch!
                                                           :event-id :todo/add})
               nil
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/no-frame-context
            :operation   :dispatch
            :where       're-frame.router/dispatch!
            :event-id    :todo/add
            :recovery    :supply-frame}
           (select-keys data [:rf.error/id :operation :where :event-id :recovery])))))

(deftest no-frame-context-rides-the-always-on-error-axis
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::probe (fn [r] (swap! records conj r)))
    (try
      (try (rf.frame/require-current-frame! :subscribe) (catch clojure.lang.ExceptionInfo _ nil))
      (finally (rf.error-emit/unregister-error-listener! ::probe)))
    (is (= [nil] (mapv :frame (filterv #(= :rf.error/no-frame-context (:error %)) @records)))
        "exactly one frameless record reached the always-on listener")))

;; The refusal tier: a substrate withdraws the AMBIENT find for an extent it
;; owns. On the JVM there is no React-context tier, so these rows pin the
;; runtime-independent half; the CLJS half is
;; `re-frame.bench.fresco.arm1.ambient-refusal-cljs-test`.

(defn- refused-id
  "The `:rf.error/id` of whatever `f` threw, or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest the-refusal-payload-carries-the-substrates-own-account
  (let [data (try (rf.frame/call-with-ambient-frame-refused
                    {:substrate :probe
                     :recovery  :read-through-the-probe
                     :reason    "Use the probe's own reader."}
                    (fn [] (rf.frame/require-current-frame! :subscribe {:where 'probe/read})))
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/ambient-frame-refused
            :operation   :subscribe
            :substrate   :probe
            :recovery    :read-through-the-probe
            :where       'probe/read}
           (select-keys data [:rf.error/id :operation :substrate :recovery :where]))
        "its own error, not absence; the substrate's recovery wins over core's")
    (is (.contains ^String (:reason data) "Use the probe's own reader.")
        "the substrate's sentence is carried verbatim")))

(deftest a-carried-stamp-still-carries-inside-a-refused-extent
  ;; the refusal withdraws the ambient FIND, never the carrying
  (rf.frame/ensure-default-frame!)
  (rf.frame/call-with-ambient-frame-refused
    {:substrate :probe :reason "Use the probe's own reader."}
    (fn []
      (is (nil? (rf.frame/resolve-current-frame)))
      (binding [rf.frame/*current-frame* :rf/default]
        (is (= :rf/default (rf.frame/resolve-current-frame)))
        (is (= :rf/default (rf.frame/require-current-frame! :subscribe)))))))

;; An extent that declares `:extent-frame` has ONE frame: a carried stamp naming
;; a different frame is refused, and a matching one must still answer.

(deftest a-matched-carried-stamp-still-answers-inside-an-extent-that-names-its-frame
  (rf.frame/call-with-ambient-frame-refused
    {:substrate :probe :extent-frame :app :reason "Use the probe's own reader."}
    (fn []
      (binding [rf.frame/*current-frame* :app]
        (is (= :app (rf.frame/resolve-current-frame)))
        (is (= :app (rf.frame/require-current-frame! :subscribe)))))))

(deftest the-mismatch-payload-names-both-frames
  (let [data (try (rf.frame/call-with-ambient-frame-refused
                    {:substrate :probe :extent-frame :app
                     :reason "Use the probe's own reader."}
                    (fn []
                      (binding [rf.frame/*current-frame* :other]
                        (rf.frame/require-current-frame! :capture-frame {:where 'probe/carry}))))
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id    :rf.error/ambient-frame-refused
            :carried-frame :other
            :extent-frame  :app
            :where         'probe/carry}
           (select-keys data [:rf.error/id :carried-frame :extent-frame :where])))
    (is (.contains ^String (:reason data) "Use the probe's own reader."))))

(deftest the-reader-first-subscribe-path-refuses-a-mismatched-stamp
  ;; subs/subscribe inlines (or (resolve-current-frame) (require-current-frame! ...)),
  ;; so the mismatch check must live in the reader too
  (rf/make-frame {:id :app})
  (rf/reg-sub :probe/v (fn [db _] (:v db)))
  (rf.frame/replace-app-db! :app {:v 7})
  (rf.frame/call-with-ambient-frame-refused
    {:substrate :probe :extent-frame :app :reason "Use the probe's own reader."}
    (fn []
      (binding [rf.frame/*current-frame* :app]
        (is (= 7 @(rf/subscribe [:probe/v]))))
      (binding [rf.frame/*current-frame* :other]
        (is (= :rf.error/ambient-frame-refused
               (refused-id #(rf/subscribe [:probe/v])))
            "refuses instead of reading :other's db"))))
  (rf/destroy-frame! :app))

(deftest the-mismatch-is-compared-by-value-not-by-reference
  ;; an extent may declare a frame VALUE; both sides normalize to an id
  (let [f (rf/make-frame {:id :valued})
        under (fn [stamp]
                (rf.frame/call-with-ambient-frame-refused
                  {:substrate :probe :extent-frame f :reason "Use the probe's own reader."}
                  (fn []
                    (binding [rf.frame/*current-frame* stamp]
                      (try (rf.frame/require-current-frame! :subscribe)
                           (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e))))))))]
    (is (= :valued (under :valued)))
    (is (= :rf.error/ambient-frame-refused (under :other)))
    (rf/destroy-frame! :valued)))

;; The pure identity and capture doors answer an extent's DECLARED frame
;; (Spec 002 §The refusal tier); stateful operations stay refused.

(deftest the-pure-doors-answer-the-extents-declared-frame
  (rf.frame/call-with-ambient-frame-refused
    {:substrate :probe :extent-frame :app :reason "Use the probe's own reader."}
    (fn []
      (is (nil? (rf.frame/resolve-current-frame)) "the ambient find is still withdrawn")
      (is (= :app (rf/current-frame-id)))
      (is (= :app (:frame (rf/capture-frame))))))
  (let [f (rf/make-frame {:id :valued})]
    (rf.frame/call-with-ambient-frame-refused
      {:substrate :probe :extent-frame f :reason "Use the probe's own reader."}
      (fn [] (is (= :valued (rf.frame/require-current-frame! :current-frame-id))
                 "a declared frame value answers as its id")))
    (rf/destroy-frame! :valued)))

(deftest the-admission-is-the-declaration-not-the-refusal
  ;; an extent that names no frame has nothing to offer the pure doors
  (is (= :rf.error/ambient-frame-refused
         (refused-id #(rf.frame/call-with-ambient-frame-refused
                        {:substrate :probe :reason "Use the probe's own reader."}
                        (fn [] (rf.frame/require-current-frame! :capture-frame)))))))

(deftest a-mismatched-stamp-is-refused-before-the-pure-door-is-admitted
  (let [data (try (rf.frame/call-with-ambient-frame-refused
                    {:substrate :probe :extent-frame :app :reason "Use the probe's own reader."}
                    (fn []
                      (binding [rf.frame/*current-frame* :other]
                        (rf.frame/require-current-frame! :current-frame-id))))
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/ambient-frame-refused :carried-frame :other :extent-frame :app}
           (select-keys data [:rf.error/id :carried-frame :extent-frame])))))

(deftest stateful-ambient-operations-stay-refused-beside-an-admitted-door
  (rf.frame/call-with-ambient-frame-refused
    {:substrate :probe :extent-frame :app :reason "Use the probe's own reader."}
    (fn []
      (is (= :rf.error/ambient-frame-refused
             (refused-id #(rf.frame/require-current-frame! :dispatch))))
      (is (= :rf.error/ambient-frame-refused
             (refused-id #(rf/subscribe [:probe/v])))
          "including subscribe's inlined reader-then-require path"))))

(deftest the-refusal-is-fail-closed-and-unwinds
  (is (= :rf.error/ambient-frame-refused
         (refused-id #(rf.frame/call-with-ambient-frame-refused
                        nil
                        (fn [] (rf.frame/require-current-frame! :dispatch)))))
      "a nil detail map still refuses")
  (rf.frame/call-with-ambient-frame-refused {:substrate :probe} (fn [] nil))
  (is (= :rf.error/no-frame-context
         (refused-id #(rf.frame/require-current-frame! :subscribe)))
      "the extent has unwound when the call returns"))

(deftest the-refusal-returns-the-thunks-value
  (is (= 42 (rf.frame/call-with-ambient-frame-refused {:substrate :probe} (fn [] 42)))))
