(ns re-frame.core-api-additions-test
  "JVM tests for `rf/with-frame` / `rf/with-new-frame`, the `(rf/frame-ids
  ns-prefix)` filter, the frame-state read/write surface, `(rf/clear :route id)`
  and the `rf/image` facade macro's doc gate."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.machines]
            [re-frame.routing :as rf.routing]
            ;; `replace-frame-state!` delegates to the epoch artefact's
            ;; `replace-frame-state!` (synthetic-epoch recording) through the
            ;; `:epoch/replace-frame-state!` hook; load it so the mutator
            ;; round-trip below resolves a live hook.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.flows/reset-last-inputs!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest with-new-frame-let-binding-create-use-destroy
  (let [captured-id (atom nil)]
    (rf/with-new-frame [f (rf.frame/make-anon-frame-record! {:doc "ephemeral"})]
      (reset! captured-id f)
      (is (= f (rf/current-frame-id)))
      (is (some? (rf/frame-meta f)) "alive during the body"))
    (is (some? @captured-id))
    (is (nil? (rf/frame-meta @captured-id)) "destroyed on body exit")
    (is (= :rf.error/no-frame-context
           (:rf.error/id (ex-data
                           (try (rf/current-frame-id) nil
                                (catch clojure.lang.ExceptionInfo e e)))))
        "the frame binding unwound after the body")))

(deftest with-new-frame-destroys-on-exception
  (let [captured-id (atom nil)]
    (try
      (rf/with-new-frame [f (rf.frame/make-anon-frame-record! {:doc "ephemeral-throw"})]
        (reset! captured-id f)
        (throw (ex-info "boom" {:kind ::boom})))
      (catch Exception e
        (is (= ::boom (:kind (ex-data e))) "the body's exception propagates")))
    (is (some? @captured-id))
    (is (nil? (rf/frame-meta @captured-id)) "destroyed even on exception")))

(deftest with-frame-rejects-vector-argument
  ;; call the expansion helper directly: macroexpand would wrap the ex-info
  (require 're-frame.core-reg-view-macro)
  (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-frame)
        data   (try (expand '[f (make-frame {})] '((do nil))) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/with-frame-vector-form :recovery :use-with-new-frame}
           (select-keys data [:rf.error/id :recovery])))))

(deftest with-new-frame-rejects-keyword-argument
  (require 're-frame.core-reg-view-macro)
  (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-new-frame)
        data   (try (expand :existing/id '((do nil))) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/with-new-frame-keyword-form :recovery :use-with-frame}
           (select-keys data [:rf.error/id :recovery])))))

(deftest with-new-frame-rejects-vector-bindings-with-wrong-arity
  (require 're-frame.core-reg-view-macro)
  (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-new-frame)
        data   (fn [bindings]
                 (try (expand bindings '((do nil))) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :rf.error/with-new-frame-bad-binding (:rf.error/id (data '[f g h]))))
    (is (= {:rf.error/id :rf.error/with-new-frame-bad-binding
            :recovery    :fix-registration
            :got         'not-a-vector}
           (select-keys (data 'not-a-vector) [:rf.error/id :recovery :got])))))

(deftest frame-ids-with-a-prefix-returns-the-live-ids-whose-namespace-starts-with-it
  (doseq [[case-label prefix made destroyed expected]
          [["no match returns #{}"
            "no-such-ns" [:fi/alpha] []
            #{}]
           ["a destroyed frame is filtered out"
            "fi.zone" [:fi.zone/one :fi.zone/two] [:fi.zone/one]
            #{:fi.zone/two}]
           ["a prefix matches every namespace it starts, and no other"
            "wide" [:wide.a/one :wide.b/two :elsewhere/one] []
            #{:wide.a/one :wide.b/two}]]]
    (doseq [id made] (rf/make-frame {:id id}))
    (doseq [id destroyed] (rf/destroy-frame! id))
    (is (= expected (rf/frame-ids prefix)) case-label)))

(deftest replace-frame-state-app-reset-preserves-runtime-via-core-facade
  (rf/make-frame {:id :pp/reset-app :doc "reset-app"})
  (rf/reg-event :pp/seed (fn [{:keys [db]} [_ db]] {:db db}))
  (rf/dispatch-sync [:pp/seed {:k 1}] {:frame :pp/reset-app})
  (rf/replace-frame-state! :pp/reset-app {:rf.db/runtime {:rf.runtime/machines {:m 1}}})
  (is (= {:rf.db/app {:k 1} :rf.db/runtime {:rf.runtime/machines {:m 1}}}
         (rf/frame-state-value :pp/reset-app))
      "a runtime-only map leaves the absent app partition untouched")
  (is (true? (rf/replace-frame-state! :pp/reset-app {:rf.db/app {}})))
  (is (= {:rf.db/app {} :rf.db/runtime {:rf.runtime/machines {:m 1}}}
         (rf/frame-state-value :pp/reset-app))
      "an app-only map leaves the absent runtime partition untouched"))

(deftest frame-state-value-projection-shape
  (rf/make-frame {:id :pp/fs :doc "frame-state"})
  (rf/reg-event :pp/seed-fs (fn [{:keys [db]} [_ db]] {:db db}))
  (rf/dispatch-sync [:pp/seed-fs {:a 1}] {:frame :pp/fs})
  (is (= {:rf.db/app {:a 1} :rf.db/runtime {}}
         (rf/frame-state-value :pp/fs))))

(deftest frame-state-value-unknown-frame-is-nil
  (is (nil? (rf/frame-state-value :pp/no-such-frame))))

(deftest replace-frame-state-writes-both-partitions
  (rf/make-frame {:id :pp/fsm :doc "frame-state-mutate"})
  (rf/replace-frame-state! :pp/fsm {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}})
  (is (= {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}}
         (rf/frame-state-value :pp/fsm))))

(deftest replace-frame-state-rejects-no-recognized-keys
  (rf/make-frame {:id :pp/bad-keys-empty :doc "bad-keys-empty"})
  (let [errors (atom [])]
    (rf/register-listener! :trace ::bad-keys
      (fn [ev] (when (= :error (:op-type ev)) (swap! errors conj ev))))
    (try
      (is (= [false false]
             [(rf/replace-frame-state! :pp/bad-keys-empty {})
              (rf/replace-frame-state! :pp/bad-keys-empty {:unrelated 1})]))
      (finally (rf/unregister-listener! :trace ::bad-keys)))
    ;; the unknown-keys check runs first, so only-unrelated keys report :unknown-keys
    (is (= [[:rf.error/replace-frame-state-bad-keys :no-recognized-keys]
            [:rf.error/replace-frame-state-bad-keys :unknown-keys]]
           (mapv (juxt :operation (comp :reason :tags)) @errors)))))

(deftest replace-frame-state-rejects-unknown-keys
  (rf/make-frame {:id :pp/bad-keys-typo :doc "bad-keys-typo"})
  (is (false? (rf/replace-frame-state! :pp/bad-keys-typo {:rf.db/app {:k 1} :rf.db/apps {:k 2}}))
      "an unrecognized key beside a recognized one rejects the whole write")
  (is (= {} (rf/app-db-value :pp/bad-keys-typo))))

(deftest renamed-facade-exports-resolve-old-names-gone
  ;; each public name resolves, on the facade and on its artefact ns, and the
  ;; rejected spelling beside it does not (no compatibility alias)
  (require 're-frame.epoch :reload)
  (require 're-frame.routing :reload)
  (require 're-frame.observability)
  (is (= [] (remove (fn [[n s]] (ns-resolve n s))
                    [['re-frame.core 'restore-epoch!]
                     ['re-frame.core 'register-observability-sink!]
                     ['re-frame.core 'clear]
                     ['re-frame.core 'replace-frame-state!]
                     ['re-frame.core 'frame-state-value]
                     ['re-frame.core 'app-db-value]
                     ['re-frame.epoch 'restore-epoch!]
                     ['re-frame.observability 'register-observability-sink!]])))
  (is (= [] (filter (fn [[n s]] (ns-resolve n s))
                    [['re-frame.core 'restore-epoch]
                     ['re-frame.core 'reg-observability-sink!]
                     ['re-frame.core 'unregister-route!]
                     ['re-frame.core 'clear-route]
                     ['re-frame.core 'snapshot-of]
                     ['re-frame.core 'reset-app-db!]
                     ['re-frame.core 'replace-runtime-db!]
                     ['re-frame.core 'replace-app-db!]
                     ['re-frame.core 'runtime-db-value]
                     ['re-frame.epoch 'restore-epoch]
                     ['re-frame.routing 'clear-route]
                     ['re-frame.routing 'unregister-route!]
                     ['re-frame.observability 'reg-observability-sink!]]))))

(deftest clear-of-a-route-removes-it-from-matching
  (require 're-frame.routing :reload)
  (rf/reg-route :rn/route {} "/rn")
  (is (some? (rf.routing/match-url "/rn")))
  (rf/clear :route :rn/route)
  (is (nil? (rf.routing/match-url "/rn"))))

(deftest image-resolves-on-the-facade
  ;; A literal inline `:doc` slot is gated at expansion so a production build
  ;; can drop the string bytes; a computed spec is left to the runtime strip.
  (let [gated   (macroexpand-1
                  '(re-frame.core/image
                     {:registrations {:reg-event [[:x {:doc "gated"} identity]]}}))
        ungated (macroexpand-1 '(re-frame.core/image some-computed-spec))]
    (is (re-find #"interop/debug-enabled\?" (pr-str gated)))
    (is (not (re-find #"interop/debug-enabled\?" (pr-str ungated))))))
