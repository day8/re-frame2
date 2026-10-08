(ns re-frame.observability-process-default-cljs-test
  "Spec 015 §The process default — `(rf/configure! {:observability …})`.

  1. Declared once per process. Precedence is per stream: a frame that
     declares a stream uses its own entries, one that omits it inherits the
     default's, and `{:errors []}` on a frame is its opt-out. One source is
     consulted per record per stream, so a sink named by both fires once.

  2. It routes the records no frame policy can: a `:frame nil` record, and a
     record whose producer revoked frame authority (`route-frame?` false).
     Those project under an explicitly nil governing frame, which fails
     closed — tree slots become `:rf/redacted`, summary ids survive — and a
     stale `:frame` id is kept as a diagnostic but never re-resolved.

  A projected record's `:frame` slot is the record's own id whichever frame
  governs, so only the TREE slot (`:tags`) shows which frame's classification
  walked the payload; the pins below read it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))})
  ;; The process default is a `defonce` outside the runtime reset (a hot reload
  ;; must keep a policy declared at boot), so clear it on both sides: a default
  ;; left installed would satisfy a fail-closed pin in the next namespace.
  (fn [t]
    (rf.observability/clear-observability-default!)
    (try (t)
         (finally (rf.observability/clear-observability-default!)))))

;; `classify-frame!` declares this app-db path sensitive. A record slot
;; `:auth {:token …}` lands on `[:tags :auth :token]`, which the walker reaches
;; through this declaration.
(def ^:private classified-path [:auth :token])

(defn- classify-frame!
  [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [classified-path]}))))

;; `:auth` is not a summary slot, so routing lifts it onto `:tags`.
(defn- payload-record
  [error frame]
  {:error error
   :frame frame
   :time  1
   :auth  {:token "secret" :user "ann"}})

(deftest inheriting-frames-own-classification-governs-the-projection
  (testing "a frame inheriting the default's entries is projected under its OWN
            classification: two inheriting frames that classify differently project
            the same payload differently"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf/make-frame {:id :obs.default/classified})
      (rf/make-frame {:id :obs.default/unclassified})
      (classify-frame! :obs.default/classified)
      (rf.error-emit/dispatch-error-record!
        (payload-record :rf.error/test-union :obs.default/classified))
      (rf.error-emit/dispatch-error-record!
        (payload-record :rf.error/test-union :obs.default/unclassified))
      (is (= [{:auth {:token :rf/redacted :user "ann"}}
              {:auth {:token "secret" :user "ann"}}]
             (mapv :tags @seen))))))

(deftest frame-policy-precedence-over-the-process-default
  (testing "precedence is per stream and read by key presence, and one source is
            consulted per record per stream"
    (let [seen (atom 0)]
      (rf/register-observability-sink! :test.sinks/sentry (fn [_] (swap! seen inc)))
      (rf/register-observability-sink! :test.sinks/datadog (fn [_] nil))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (doseq [[frame-id policy deliveries why]
              [[:obs.default/opted-out   {:errors []}
                0 "an empty stream on a frame is its opt-out, not an inherit"]
               [:obs.default/both        {:errors [{:sink :test.sinks/sentry}]}
                1 "a sink named by both sources is invoked once"]
               [:obs.default/events-only {:handled-events [{:sink :test.sinks/datadog}]}
                1 "declaring one stream still inherits the default's :errors"]]]
        (reset! seen 0)
        (rf/make-frame {:id frame-id :observability policy})
        (rf.error-emit/dispatch-error-record!
          {:error :rf.error/test-union :frame frame-id :time 1})
        (is (= deliveries @seen) why)))))

(deftest explicit-nil-clears-the-default
  (testing "omitting `:observability` from a configure! call leaves the default,
            an explicit nil clears it, and `rf/current-config` reports it verbatim
            and omits it once cleared"
    (let [seen   (atom 0)
          policy {:errors [{:sink :test.sinks/sentry}]}
          fire!  #(rf.error-emit/dispatch-error-record!
                    {:error :rf.error/test-union :frame :obs.default/cleared :time 1})]
      (rf/register-observability-sink! :test.sinks/sentry (fn [_] (swap! seen inc)))
      (rf/configure! {:observability policy})
      (rf/make-frame {:id :obs.default/cleared})
      (rf/configure! {:elision {:rf.egress/threshold-bytes 8192}})
      (is (= policy (:observability (rf/current-config))))
      (fire!)
      (is (= 1 @seen) "omitting the key left the default routing")
      (rf/configure! {:observability nil})
      (fire!)
      (is (= 1 @seen) "an explicit nil cleared it")
      (is (not (contains? (rf/current-config) :observability))
          "absent once cleared, never a fabricated nil"))))

(deftest malformed-default-fails-loud-at-configure-time
  (testing "a malformed default is refused at call time under make-frame's grammar,
            naming the door the author typed"
    (doseq [policy [{:errors "x"}
                    {:errors [{:sink :s :opts {:a 1}}]}]]
      (let [data (try (rf/configure! {:observability policy})
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
        (is (= {:rf.error/id :rf.error/bad-frame-classification :where 'rf/configure!}
               (select-keys data [:rf.error/id :where]))))))
  (testing "a refused call installs nothing"
    (is (nil? (rf.observability/current-observability-config)))))

(deftest a-live-ambient-frame-policy-is-not-consulted-for-a-frameless-record
  (testing "with a live, classified frame CARRIED at emit time, a `:frame nil`
            record goes only to the process default and fails closed: the carried
            frame is not its owner"
    (let [default-seen (atom [])
          ambient-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/default
                                       (fn [r] (swap! default-seen conj r)))
      (rf/register-observability-sink! :test.sinks/ambient
                                       (fn [r] (swap! ambient-seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/default}]}})
      (rf/make-frame {:id :obs.default/ambient
                      :observability {:errors [{:sink :test.sinks/ambient}]}})
      (classify-frame! :obs.default/ambient)
      ;; Carried, not merely alive: outside `with-frame` there is no ambient
      ;; frame to fall through to, and the pin would pass for free.
      (rf/with-frame :obs.default/ambient
        (rf.error-emit/dispatch-error-record!
          (payload-record :rf.error/no-frame-context nil)))
      (is (zero? (count @ambient-seen)) "the carried frame's policy was not consulted")
      (is (= [[nil :rf.error/no-frame-context 1 :rf/redacted]]
             (mapv (juxt :frame :error :time :tags) @default-seen))
          "the default receives it once: no frame claimed, summary intact, tree slot
           failed closed rather than walked under the carried frame"))))

(deftest stale-owner-report-reaches-the-default-not-a-successor
  (testing "a record emitted with `route-frame?` false reaches the process default
            and neither the same-id successor's sink nor a carried bystander's.
            `frame` cannot tell a never-registered id from a dissociated one, so
            the authority bit is carried from the producer."
    (let [default-seen   (atom [])
          successor-seen (atom [])
          ambient-seen   (atom [])]
      (rf/register-observability-sink! :test.sinks/default
                                       (fn [r] (swap! default-seen conj r)))
      (rf/register-observability-sink! :test.sinks/successor
                                       (fn [r] (swap! successor-seen conj r)))
      (rf/register-observability-sink! :test.sinks/ambient
                                       (fn [r] (swap! ambient-seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/default}]}})
      (rf/make-frame {:id :obs.default/reborn
                      :observability {:errors [{:sink :test.sinks/successor}]}})
      (rf/make-frame {:id :obs.default/bystander
                      :observability {:errors [{:sink :test.sinks/ambient}]}})
      (classify-frame! :obs.default/bystander)
      (rf/with-frame :obs.default/bystander
        (#'rf.error-emit/dispatch-error-record*
          (assoc (payload-record :rf.error/frame-teardown-failed
                                 :obs.default/reborn)
                 :time 7)
          false))
      (is (= [0 0] [(count @successor-seen) (count @ambient-seen)])
          "neither the successor nor the carried bystander sees the report")
      (is (= [[:obs.default/reborn :rf.error/frame-teardown-failed 7 :rf/redacted]]
             (mapv (juxt :frame :error :time :tags) @default-seen))
          "the default delivers it, keeping the stale id as a diagnostic, with the
           tree payload failed closed"))))

(deftest local-raw-profile-is-the-sanctioned-cross-frame-hook
  (testing "an explicit `:rf.egress/local-raw` entry on the default keeps the tree
            slot that the off-box default fails closed on a nil governing frame"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/raw
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability
                      {:errors [{:sink :test.sinks/raw
                                 :rf.egress/profile :rf.egress/local-raw}]}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/no-frame-context :frame nil :time 1 :reason "r"})
      (is (= ["r"] (mapv #(get-in % [:tags :reason]) @seen))))))

(deftest a-registered-default-sink-owns-the-record
  (testing "the console fallback keys on the delivered count: a default naming an
            unregistered sink owns nothing, a registered one owns the record"
    (rf/configure! {:observability {:errors [{:sink :test.sinks/never-wired}]}})
    (is (zero? (rf.observability/route-error-record!
                 {:error :rf.error/no-frame-context :frame nil :time 1})))
    (rf/register-observability-sink! :test.sinks/never-wired (fn [_] nil))
    (is (= 1 (rf.observability/route-error-record!
               {:error :rf.error/no-frame-context :frame nil :time 1})))))
