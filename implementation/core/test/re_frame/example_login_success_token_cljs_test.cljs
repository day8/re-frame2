(ns re-frame.example-login-success-token-cljs-test
  "The login session token's privacy on the way back
   (docs/core/how-to/keep-secrets-out-of-traces.md). The reply lands on
   `:auth.login/succeeded` (`:sensitive [[:value :token]]`), which persists the
   token through `:auth.session/store` (`:sensitive [[:token]]`) and nudges the
   machine with a credential-free `:success` signal.

   Also the canonical `:story.login/success` variant, which runs the real form
   path: without its own `:network` route the framework's generic canned stub
   answers `{:stubbed true}`, which `:auth.login/succeeded`'s schema refuses,
   and the canvas never leaves `:submitting`."
  (:require [cljs.test :refer-macros [deftest use-fixtures is async]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            ;; Activates the validator behind the `:where :event` boundary the
            ;; live drive asserts refuses nothing.
            [re-frame.schemas.malli]
            [re-frame.machines]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [login.model]
            [login.stories :as login-stories])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; `:async? true` because the live drive is an `(async done …)` test, and
;; cljs.test aborts the whole run on a fn-form fixture beside one.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :async?  true}))

;; A token string that appears nowhere else.
(def sentinel "SESSION-TOKEN-SENTINEL-8b71e0")

(def success-reply
  {:status :ok
   :value  {:user  {:id "u1" :email "alice@example.com"}
            :token sentinel}})

(defn- machine-state [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :state]))

(defn- contains-sentinel?
  [x]
  (cond
    (string? x) (not= -1 (.indexOf x sentinel))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

(defn- submit! [f]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit]] {:frame f}))

(deftest success-token-redacted-across-trace-egress
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (submit! f)
    (let [traces (record-traces!)]
      (rf/dispatch-sync [:auth.login/succeeded success-reply] {:frame f})
      (rf/unregister-listener! :trace ::probe)
      (is (= :authed (machine-state f))
          "the credential-free :success signal drove the flow to :authed")
      (let [succeeded-vs (->> @traces
                              (keep #(get-in % [:tags :rf.event/v]))
                              (filter #(and (vector? %)
                                            (= :auth.login/succeeded (first %)))))]
        (is (seq succeeded-vs)
            "the :auth.login/succeeded event surfaced on the trace with its payload")
        (doseq [v succeeded-vs]
          (is (= (assoc-in success-reply [:value :token] rf.privacy/redacted-sentinel)
                 (second v))
              "only [:value :token] reads :rf/redacted in the reply event")))
      (let [handled (->> @traces
                         (filter #(= :auth.session/store (get-in % [:tags :rf.fx/id]))))]
        (is (seq handled)
            "the :auth.session/store fx emitted a :rf.fx/handled trace")
        (doseq [ev handled]
          (is (= rf.privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
              "the store fx :token arg reads :rf/redacted in :rf.fx/handled")))
      (is (= [] (mapv :operation (filter #(contains-sentinel? (:tags %)) @traces)))
          "the token appears raw in no trace tag, the :rf.event/fx aggregate included"))))

(deftest error-arm-origin-event-and-fx-args-redacted-on-fx-exception
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (submit! f)
    (let [traces (record-traces!)]
      (rf/dispatch-sync
        [:auth.login/succeeded success-reply]
        {:frame        f
         :fx-overrides {:auth.session/store
                        (fn [_ _] (throw (js/Error. "localStorage unavailable")))}})
      (rf/unregister-listener! :trace ::probe)
      (let [errs (->> @traces
                      (filter #(= :rf.error/fx-handler-exception (:operation %))))]
        (is (seq errs)
            "the throwing store fx emitted an :rf.error/fx-handler-exception trace")
        (doseq [ev errs]
          (is (not (contains-sentinel? (:tags ev)))
              "neither the origin :event nor :rf.fx/args carries the raw token"))))))

;; The live drive. `run-variant` allocates the variant's own frame, installs
;; its `:network` route map on the managed-HTTP seam and runs the four-phase
;; lifecycle, so the real cascade runs:
;;
;;     [:login.story/submit good-creds]
;;       -> [:auth.login/submit-form]  -> [:auth.login/flow [:auth.login/submit]]
;;                                     -> [:rf.http/managed ...] (the route fixture)
;;            -> [:auth.login/succeeded {:status :ok :value {... :token}}]
;;                 -> [:auth.session/store {:token ...}]
;;                 -> [:auth.login/flow [:auth.login/success]]  (-> :authed)
;;
;; `:auth.session/store`'s real body writes `js/globalThis.localStorage`, which
;; Node lacks, so the global is swapped for a capture-only stand-in. Re-registering
;; the fx here instead would skip the body under test.

(def ^:private story-fixture-token
  "The token the variant's `:network` route hands back."
  "demo-token-123")

(def ^:private stored-token-key
  "The localStorage key `:auth.session/store` writes under."
  "auth/token")

(defn- install-stub-storage!
  "Swap `js/globalThis.localStorage` for a capture-only stand-in. Returns
   `[captured restore!]`."
  []
  (let [captured (atom {})
        prior    (.-localStorage js/globalThis)]
    (set! (.-localStorage js/globalThis)
          #js {:setItem    (fn [k v] (swap! captured assoc k v) nil)
               :getItem    (fn [k] (get @captured k))
               :removeItem (fn [k] (swap! captured dissoc k) nil)})
    [captured (fn [] (set! (.-localStorage js/globalThis) prior))]))

(defn- event-schema-refusals
  [traces]
  (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                 (= :event (get-in % [:tags :where])))
           traces))

(defn- authenticated?
  "The condition `login.core/login-banner` branches on to render 'Welcome!'."
  [frame-id]
  (rf/compute-sub [:rf.machine/has-tag? :auth.login/flow :auth/authenticated]
                  (rf/frame-state-value frame-id)))

(defn- drive-variant!
  "Register the example's deck, run `variant-id` to settlement and call `k`
   with the result map, the captured storage map and the captured traces, then
   tear the variant frame down."
  [variant-id k]
  (login-stories/register-all!)
  (let [[captured restore!] (install-stub-storage!)
        traces              (record-traces!)]
    (-> (rf.story/run-variant variant-id)
        (rf.story.async/then
          (fn [result]
            (rf/unregister-listener! :trace ::probe)
            (restore!)
            (try
              (k result @captured @traces)
              (finally
                (rf.story/destroy-variant! variant-id))))))))

(deftest story-success-variant-drives-to-authed-with-the-token-stored
  (async done
    (drive-variant! :story.login/success
      (fn [result stored traces]
        (is (= :ready (:lifecycle result))
            "the four-phase lifecycle completed cleanly")
        (is (empty? (event-schema-refusals traces))
            (str "no event-schema refusal on the way; got "
                 (pr-str (mapv :tags (event-schema-refusals traces)))))
        (is (= :authed (machine-state :story.login/success))
            ":auth.login/flow reached :authed, not the :submitting a missing fixture leaves")
        (is (true? (authenticated? :story.login/success))
            "the :auth/authenticated tag the Welcome banner branches on reads true")
        (is (= {stored-token-key story-fixture-token} stored)
            "the fixture's token travelled the whole cascade into :auth.session/store")
        (done)))))
