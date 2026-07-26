(ns re-frame.prod-gate-dispatch-probe
  "The CHILD half of `re-frame.prod-gate-dispatch-jvm-test` (rf2-9c2jf).

  This namespace is NOT a test namespace — cognitect-test-runner discovers
  `.*-test$` only, so nothing here runs on the ordinary suite. It is a `-main`
  program the parent test relaunches in a FRESH JVM under
  `-Dre-frame.debug=false`.

  ## Why a separate JVM rather than `with-redefs`

  `re-frame.interop/debug-enabled?` is read ONCE, at namespace-load time, from
  the `re-frame.debug` system property. Every existing \"production gate\" suite
  rebinds that Var with `with-redefs` AFTER the framework has loaded, which
  cannot exercise anything the gate decided at load time — and the rf2-9c2jf
  defect was exactly that: a load-time `defonce` whose body was skipped, so the
  frame-generation freshener was never installed and every handler registered
  after `make-frame` dispatched as `:rf.error/no-such-handler`. A test that
  cannot fail under the real property is worthless for this class of bug, so the
  gate has to be set before the JVM loads `re-frame.interop`.

  Reports its observations as one EDN map on stdout, prefixed by
  `result-marker`. Assertions live in the parent."
  (:require [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(def result-marker
  "Line prefix the parent greps for. Distinctive enough that unrelated
  framework chatter on stdout cannot be mistaken for the result line."
  "RF2-PROD-GATE-PROBE ")

(def ^:private frame-id :rf2-9c2jf/probe)
(def ^:private event-id :rf2-9c2jf/bump)

(defn probe
  "Run the rf2-9c2jf scenario and return the observation map.

  The ORDER is the whole point: `make-frame` FIRST, `reg-event` SECOND. A frame
  seals an image generation at construction (EP-0026 §Default Image — every
  frame is image-loaded, so `registrar/lookup` inside the cascade resolves
  through that generation, not the registrar atom), and the registration lands
  after the seal. Nothing in re-frame2 orders all registrations before all frame
  construction, so this is an ordinary app/REPL/test sequence — and under the
  production gate it used to silently no-op."
  []
  (let [runs   (atom 0)
        errors (atom [])]
    (rf/init! plain-atom/adapter)
    (error-emit/register-error-listener!
      ::probe (fn [record] (swap! errors conj (:error record))))
    (rf/make-frame {:id frame-id})
    (rf/reg-event event-id
                  (fn [{:keys [db]} _]
                    (swap! runs inc)
                    {:db (update db :n (fnil inc 0))}))
    (rf/with-frame frame-id
      (rf/dispatch-sync [event-id]))
    (let [after-dispatch {:debug-enabled?     interop/debug-enabled?
                          :bare-lookup-found? (some? (registrar/lookup :event event-id))
                          :handler-runs       @runs
                          :app-db             (rf/app-db-value frame-id)
                          :errors             @errors}]
      ;; The REMOVAL twin: clearing a registration must likewise reach the
      ;; frame's sealed generation, so a cleared handler stops resolving rather
      ;; than lingering in a store that no longer backs it.
      (reset! errors [])
      (registrar/unregister! :event event-id)
      (rf/with-frame frame-id
        (rf/dispatch-sync [event-id]))
      (assoc after-dispatch
             :runs-after-unregister      @runs
             :errors-after-unregister    @errors))))

(defn -main [& _]
  (let [result (try
                 (probe)
                 (catch Throwable t
                   {:probe-threw (str (class t) ": " (.getMessage t))}))]
    (.print (System/out) (str result-marker (pr-str result) "\n"))
    (.flush (System/out))
    (System/exit 0)))
