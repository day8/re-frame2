(ns re-frame.lifecycle-contribution-test
  "An artefact holds a frame's lifecycle through one contribution on the list
  `re-frame.frame` keeps, so a contribution registered here reaches every
  engine that reads the list — destroy, registration, preflight, test reset and
  the frame-config vocabulary — with no edit to core (Spec 002 §Lifecycle
  contributions)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- with-contribution
  "Register `contribution` for the extent of `f`, then put the list back."
  [contribution f]
  (let [saved @rf.frame/lifecycle-contributions]
    (try
      (rf.frame/contribute-lifecycle! contribution)
      (f)
      (finally
        (reset! rf.frame/lifecycle-contributions saved)))))

(defn- throwaway
  "A contribution joining both destroy phases that records every member call
  into `calls`. Its preflight refuses a `:throwaway-knob` that is not an int."
  [calls]
  {:id                  ::throwaway
   :phase               #{:pre-dead :post-dead}
   :config-keys         #{:throwaway-knob}
   :preflight-config    (fn [id config]
                          (swap! calls conj [:preflight id (:throwaway-knob config)])
                          (when-not (int? (:throwaway-knob config 0))
                            (throw (ex-info "throwaway-knob must be an int"
                                            {:rf.error/id ::bad-knob}))))
   :on-frame-registered (fn [id]
                          (swap! calls conj [:registered id (some? (rf.frame/frame id))]))
   :on-frame-destroyed  (fn [id phase]
                          ;; the public lookup and the raw registry row, at each phase
                          (swap! calls conj [phase id
                                             (some? (rf.frame/frame id))
                                             (contains? @rf.frame/frames id)])
                          (when (= :post-dead phase)
                            (fn []
                              (swap! calls conj [:after-dissoc id
                                                 (contains? @rf.frame/frames id)]))))
   :test-reset          (fn [] (swap! calls conj [:test-reset]))})

(deftest a-throwaway-contribution-takes-effect-in-every-engine
  (let [calls    (atom [])
        warnings (atom [])]
    (with-contribution (throwaway calls)
      (fn []
        (rf/register-listener! :trace ::config-key
                               (fn [ev]
                                 (when (= :rf.warning/unknown-frame-config-key (:operation ev))
                                   (swap! warnings conj (:tags ev)))))
        (try
          (rf/make-frame {:id ::f :throwaway-knob 1})
          (rf/make-frame {:id ::f :throwaway-knob 2})
          (rf/destroy-frame! ::f)
          (finally
            (rf/unregister-listener! :trace ::config-key)))
        (is (= [[:preflight ::f 1]
                [:registered ::f true]
                [:preflight ::f 2]
                [:registered ::f true]
                [:pre-dead ::f true true]
                [:post-dead ::f false true]
                [:after-dissoc ::f false]]
               @calls)
            "preflight and registration run on create and re-registration; the
             destroy phases run before the dead flip, after it, and after the
             frame leaves the registry")
        (when rf.interop/debug-enabled?
          (is (= [] @warnings)
              "a contribution's :config-keys join the recognised vocabulary"))
        (reset! calls [])
        (is (= [::bad-knob nil]
               [(try (rf/make-frame {:id ::refused :throwaway-knob "high"})
                     (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e))))
                (rf.frame/frame ::refused)])
            "a throwing preflight refuses the config before any frame exists")
        (reset! calls [])
        ((rf.test-support/make-reset-runtime-fixture
           {:adapter rf.substrate.plain-atom/adapter})
         (fn [] :ran))
        (is (= 1 (count (filter #{[:test-reset]} @calls)))
            "the runtime-reset fixture runs the contribution's :test-reset once")))))

(deftest a-throwing-member-is-reported-by-contribution-id-and-phase
  (let [reports (atom [])]
    (rf.error-emit/register-error-listener! ::reports
                                            (fn [r]
                                              (when (= :rf.error/frame-teardown-failed
                                                       (:rf.error/id r))
                                                (swap! reports conj r))))
    (try
      (with-contribution
        {:id                 ::throws
         :phase              #{:pre-dead :post-dead}
         :on-frame-destroyed (fn [_id phase]
                               (if (= :post-dead phase)
                                 (fn [] (throw (ex-info "after dissoc" {})))
                                 (throw (ex-info "pre dead" {}))))}
        (fn []
          (rf/make-frame {:id ::g})
          (rf/destroy-frame! ::g)))
      (finally
        (rf.error-emit/unregister-error-listener! ::reports)))
    (is (= [[[::throws :pre-dead] [::throws :after-dissoc]]]
           (mapv #(mapv (juxt :hook :phase) (:hook-failures %)) @reports))
        "one report, each failure named by its contribution and the phase it threw in")
    (is (nil? (rf.frame/frame ::g)) "the destroy still completed")))
