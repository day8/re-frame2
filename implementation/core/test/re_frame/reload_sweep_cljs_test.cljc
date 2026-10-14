(ns re-frame.reload-sweep-cljs-test
  "The dev hot-reload sweep (Spec 001 §Hot-reload semantics): at the end of a
  reload cycle, every namespace that registered anything during the cycle
  loses each registration it did not make again, so a handler moved to
  another namespace, renamed or deleted leaves nothing behind.

  A real reload is bracketed by the registrar's `^:dev/before-load` and
  `^:dev/after-load` hooks. These tests call those two hooks directly around
  the registrations a reload would re-evaluate. Each registration names its
  namespace through `:ns` metadata, the provenance a `reg-*` written in that
  namespace records, and every frame is built through the public
  `rf/make-frame`.

  The sweep is dev-only, so the cases that need it to run carry
  `^:requires-debug` and the production-gate lane skips them;
  `the-sweep-runs-only-in-a-debug-build` holds both postures."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.image :as rf.image]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reload!
  "Run `registrations` as one hot-reload cycle: the registrations a reload
  re-evaluates, between the two hooks shadow-cljs runs around it."
  [registrations]
  (rf.registrar/begin-reload-cycle!)
  (try
    (registrations)
    (finally
      (rf.registrar/sweep-reload-cycle!))))

(defn- reg-who!
  "Register the event `id` from namespace `ns-sym`; it records `who` in app-db."
  [ns-sym id who]
  (rf/reg-event id {:ns ns-sym} (fn [{:keys [db]} _] {:db (assoc db :who who)})))

(defn- who-runs
  "Dispatch `[id]` to `frame` and return who handled it, or nil when nothing did."
  [frame id]
  (rf/dispatch-sync [:test/forget-who] {:frame frame})
  (rf/dispatch-sync [id] {:frame frame})
  (:who (rf/app-db-value frame)))

(defn- with-recorded-errors
  "Call `(f seen)` with every always-on error record appended to the atom
  `seen`. Returns what `f` returns."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (f seen)
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)))))

(defn- errors-of [seen error-id]
  (filterv #(= error-id (:error %)) @seen))

(defn- register-forget-who! []
  (rf/reg-event :test/forget-who (fn [{:keys [db]} _] {:db (dissoc db :who)})))

(deftest ^:requires-debug a-moved-handler-runs-from-its-new-namespace
  (with-recorded-errors
    (fn [seen]
      (register-forget-who!)
      (reg-who! 'probe.a :x/ev :a)
      (reg-who! 'probe.a :x/keep :a-keep)
      (rf/make-frame {:id :probe/frame})
      (is (= :a (who-runs :probe/frame :x/ev)))
      (reload! (fn []
                 (reg-who! 'probe.a :x/keep :a-keep)
                 (reg-who! 'probe.b :x/ev :b)))
      (is (= :b (who-runs :probe/frame :x/ev))
          "the moved handler runs its new namespace's code")
      (is (empty? (errors-of seen :rf.error/ambiguous-registration))
          "the old namespace's registration is gone, so nothing is ambiguous")
      (is (= ["probe.b"] (keys (rf.source-store/descriptors-for :event :x/ev))))
      (is (= :a-keep (who-runs :probe/frame :x/keep))
          "what the reloaded namespace registered again stays"))))

(deftest ^:requires-debug a-deleted-handler-is-gone
  (with-recorded-errors
    (fn [seen]
      (register-forget-who!)
      (reg-who! 'probe.a :x/gone :a)
      (reg-who! 'probe.a :x/keep :a-keep)
      (rf/make-frame {:id :probe/frame})
      (is (= :a (who-runs :probe/frame :x/gone)))
      (reload! (fn [] (reg-who! 'probe.a :x/keep :a-keep)))
      (is (nil? (who-runs :probe/frame :x/gone)))
      (is (= [[:x/gone]] (mapv :event (errors-of seen :rf.error/no-such-handler)))
          "dispatching the deleted id reports no-such-handler")
      (is (nil? (rf/handler-meta {:source :store :kind :event :id :x/gone}))
          "the registrar's slot is gone too")
      (is (empty? (rf.source-store/descriptors-for :event :x/gone))))))

(deftest ^:requires-debug a-namespace-that-registered-nothing-keeps-everything
  (register-forget-who!)
  (reg-who! 'probe.a :x/a :a)
  (reg-who! 'probe.a :x/old :a-old)
  (reg-who! 'probe.c :x/c :c)
  (rf/make-frame {:id :probe/frame})
  (reload! (fn [] (reg-who! 'probe.a :x/a :a)))
  (is (= :c (who-runs :probe/frame :x/c))
      "probe.c registered nothing in the cycle, so none of its registrations go")
  (is (nil? (who-runs :probe/frame :x/old))
      "probe.a did register, so what it did not make again is gone")
  (is (= :a (who-runs :probe/frame :x/a))))

(deftest ^:requires-debug registrations-outside-a-cycle-never-count
  (register-forget-who!)
  (reg-who! 'probe.a :x/a :a)
  (rf/make-frame {:id :probe/frame})
  (rf.registrar/sweep-reload-cycle!)
  (is (= :a (who-runs :probe/frame :x/a))
      "with no cycle open — the first boot — a sweep forgets nothing")
  (reg-who! 'probe.a :x/runtime :a-runtime)
  (reload! (fn [] (reg-who! 'probe.b :x/b :b)))
  (is (= :a (who-runs :probe/frame :x/a))
      "probe.a registered only outside the cycle, so it is untouched")
  (is (= :a-runtime (who-runs :probe/frame :x/runtime))))

(deftest ^:requires-debug a-frame-with-explicit-images-follows-by-reprojection
  (with-recorded-errors
    (fn [seen]
      (register-forget-who!)
      (reg-who! 'probe.a :x/ev :a)
      (reg-who! 'probe.a :x/keep :a-keep)
      (rf/make-frame {:id     :probe/imaged
                      :images [(rf.image/image {:id        :probe/image
                                                :select-ns {:include ["probe.**"]}})]})
      (is (= :a (who-runs :probe/imaged :x/ev)))
      (reload! (fn []
                 (reg-who! 'probe.a :x/keep :a-keep)
                 (reg-who! 'probe.b :x/ev :b)))
      (is (= :b (who-runs :probe/imaged :x/ev))
          "the frame re-assembles from the swept store, so its image sees the move")
      (is (empty? (errors-of seen :rf.error/reprojection-failed))
          "no duplicate is left for the re-assembly to refuse"))))

(deftest the-sweep-runs-only-in-a-debug-build
  (reg-who! 'probe.a :x/gone :a)
  (reload! (fn [] (reg-who! 'probe.a :x/keep :a-keep)))
  (is (= (if rf.interop/debug-enabled? [] ["probe.a"])
         (vec (keys (rf.source-store/descriptors-for :event :x/gone))))
      "a debug build forgets the registration probe.a stopped making; a production build records no cycle, so it forgets nothing"))
