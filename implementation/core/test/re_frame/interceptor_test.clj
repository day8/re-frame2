(ns re-frame.interceptor-test
  "Dedicated coverage for the v2 retained interceptor surface (Spec 002).

  v2 trims the v1 interceptor stdlib down to four primitives:
    - path
    - unwrap
    - inject-cofx
    - ->interceptor (and the supporting context plumbing)

  These are exercised obliquely by smoke / conformance tests, but nothing
  pins their contract directly. This namespace does — one deftest per
  interceptor plus a chain-composition deftest covering before-order /
  after-reverse-order / exception-interruption.

  Tests run on the JVM via the plain-atom adapter; per the project
  invariant the JVM interop layer must work."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.interceptor :as interceptor]
            [re-frame.registrar :as registrar]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init!)
  ;; Framework-shipped registrations live in routing.cljc / ssr.cljc /
  ;; machines.cljc and are wiped by clear-all!. None of these tests need
  ;; them, so we skip the require-reload dance — keeps the fixture cheap.
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- path -----------------------------------------------------------------

(deftest path-interceptor
  (testing "(path :foo :bar) scopes a handler to the [:foo :bar] sub-tree:
            the handler sees only the slice as :db, and its returned value
            is spliced back at that path."
    (rf/reg-event-db :path-test/init
                     (fn [_ _]
                       {:foo {:bar  10
                              :keep :untouched}
                        :other :preserved}))
    (rf/reg-event-db :path-test/inc
                     [(rf/path :foo :bar)]
                     ;; The handler's `db` is the SLICE value, not the full
                     ;; app-db. Returning a new slice value writes it back
                     ;; at [:foo :bar].
                     (fn [slice _]
                       (is (= 10 slice)
                           "path interceptor presents the slice as :db")
                       (inc slice)))
    (rf/dispatch-sync [:path-test/init])
    (rf/dispatch-sync [:path-test/inc])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 11 (get-in db [:foo :bar]))
          "the handler's return value was spliced back at [:foo :bar]")
      (is (= :untouched (get-in db [:foo :keep]))
          "siblings under :foo are preserved")
      (is (= :preserved (:other db))
          "keys outside the path are preserved"))))

;; ---- unwrap ---------------------------------------------------------------

(deftest unwrap-interceptor
  (testing "[unwrap] replaces the :event coeffect with the payload map from
            the canonical [event-id payload-map] envelope shape."
    (let [seen-event (atom ::not-set)]
      (rf/reg-event-fx :unwrap-test/consume
                       [rf/unwrap]
                       ;; With unwrap, the second arg is the payload map
                       ;; itself — not the [id payload] vector.
                       (fn [_cofx event-arg]
                         (reset! seen-event event-arg)
                         {}))
      (rf/dispatch-sync [:unwrap-test/consume {:k "v" :n 7}])
      (is (= {:k "v" :n 7} @seen-event)
          "handler receives the payload map directly")
      (is (map? @seen-event)
          "the unwrapped event arg is a map, not a vector"))))

;; ---- inject-cofx ----------------------------------------------------------

(deftest inject-cofx-interceptor
  (testing "registered cofx is injected into :coeffects under its keyword id"
    (rf/reg-cofx :now (fn [ctx] (assoc-in ctx [:coeffects :now] 1234567890)))
    (let [seen-cofx (atom nil)]
      (rf/reg-event-fx :cofx-test/read-now
                       [(rf/inject-cofx :now)]
                       (fn [cofx _event]
                         (reset! seen-cofx cofx)
                         {}))
      (rf/dispatch-sync [:cofx-test/read-now])
      (is (= 1234567890 (:now @seen-cofx))
          "the :now value injected by the cofx is visible to the handler")
      (is (contains? @seen-cofx :db)
          "standard cofx (e.g. :db) are still present alongside :now")
      (is (contains? @seen-cofx :event)
          "standard :event cofx is present")))

  (testing "(inject-cofx :id value) passes the value as a second arg"
    (rf/reg-cofx :greeting
                 (fn [ctx greeting]
                   (assoc-in ctx [:coeffects :greeting] greeting)))
    (let [seen (atom nil)]
      (rf/reg-event-fx :cofx-test/use-greeting
                       [(rf/inject-cofx :greeting "hello")]
                       (fn [cofx _]
                         (reset! seen (:greeting cofx))
                         {}))
      (rf/dispatch-sync [:cofx-test/use-greeting])
      (is (= "hello" @seen)
          "the value-arity inject-cofx threads the value into the cofx fn")))

  (testing "a cofx that throws surfaces as :rf.error/handler-exception via trace"
    ;; Per re-frame.interceptor/invoke-before, an exception from a :before
    ;; stage is captured into :rf/interceptor-error and the chain keeps
    ;; running so :after stages can clean up. The router then converts
    ;; that to a :rf.error/handler-exception trace event with :phase :before.
    (rf/reg-cofx :boom
                 (fn [_ctx]
                   (throw (ex-info "cofx blew up" {:why :testing}))))
    (rf/reg-event-fx :cofx-test/explode
                     [(rf/inject-cofx :boom)]
                     (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cofx-throw (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:cofx-test/explode])
      (rf/remove-trace-cb! ::cofx-throw)
      (is (some (fn [ev]
                  (and (= :rf.error/handler-exception (:operation ev))
                       (= :before (get-in ev [:tags :phase]))))
                @traces)
          "expected :rf.error/handler-exception trace with :phase :before"))))

;; ---- ->interceptor primitive ----------------------------------------------

(deftest make-interceptor-via-primitive
  (testing "->interceptor builds a custom interceptor whose :before runs in
            chain order and whose :after runs in reverse — both can mutate
            the context."
    (let [trail (atom [])
          ;; Three custom interceptors, named A / B / C, that each push a
          ;; tagged entry into `trail` from both their :before and :after
          ;; slots. The handler itself pushes :handler.
          mk (fn [tag]
               (rf/->interceptor
                 :id     tag
                 :before (fn [ctx]
                           (swap! trail conj [:before tag])
                           (assoc ctx tag :touched))
                 :after  (fn [ctx]
                           (swap! trail conj [:after tag])
                           ctx)))]
      (rf/reg-event-fx :primitive/run
                       [(mk :a) (mk :b) (mk :c)]
                       (fn [_ _]
                         (swap! trail conj :handler)
                         {}))
      (rf/dispatch-sync [:primitive/run])
      (is (= [[:before :a]
              [:before :b]
              [:before :c]
              :handler
              [:after :c]
              [:after :b]
              [:after :a]]
             @trail)
          ":before runs in declaration order, :after in reverse"))))

;; ---- chain composition ----------------------------------------------------
;;
;; Driven directly through interceptor/execute-chain so the test pins the
;; chain runtime's contract without leaning on the dispatch path. This is
;; the level the bead's "chain composition" deliverable refers to.

(deftest chain-composition
  (testing "execute-chain runs every :before in order then every :after in
            reverse; the captured order matches the standard pattern."
    (let [trail (atom [])
          mk    (fn [tag]
                  (interceptor/->interceptor
                    :id     tag
                    :before (fn [ctx]
                              (swap! trail conj [:before tag])
                              (update ctx :seen (fnil conj []) tag))
                    :after  (fn [ctx]
                              (swap! trail conj [:after tag])
                              ctx)))
          handler (interceptor/->interceptor
                    :id :handler
                    :before (fn [ctx]
                              (swap! trail conj :handler)
                              ctx))
          chain   [(mk :a) (mk :b) (mk :c) handler]
          final   (interceptor/execute-chain chain {:coeffects {} :effects {}})]
      (is (= [[:before :a] [:before :b] [:before :c]
              :handler
              [:after :c] [:after :b] [:after :a]]
             @trail)
          "before-in-order then after-in-reverse — the handler is itself
           an interceptor whose :after slot is nil, so it contributes
           nothing on the way back out")
      (is (= [:a :b :c] (:seen final))
          "each :before stage saw the prior :before's mutations")))

  (testing "an :after that throws does NOT prevent the handler from completing,
            but IS captured on the context as :rf/interceptor-error.

            The downstream chain runtime currently records the error and lets
            subsequent :after stages still run (they receive the error-bearing
            context). This pins THAT contract — the handler completed (we see
            :handler-ran), the throwing :after's id is recorded, and we DID
            see at least one upstream :after run before the throw."
    (let [trail (atom [])
          ran-handler? (atom false)
          mk-good (fn [tag]
                    (interceptor/->interceptor
                      :id     tag
                      :before (fn [ctx]
                                (swap! trail conj [:before tag])
                                ctx)
                      :after  (fn [ctx]
                                (swap! trail conj [:after tag])
                                ctx)))
          mk-bad-after (fn [tag]
                         (interceptor/->interceptor
                           :id     tag
                           :before (fn [ctx]
                                     (swap! trail conj [:before tag])
                                     ctx)
                           :after  (fn [_ctx]
                                     (swap! trail conj [:after tag])
                                     (throw (ex-info "after blew up"
                                                     {:tag tag})))))
          handler (interceptor/->interceptor
                    :id :handler
                    :before (fn [ctx]
                              (reset! ran-handler? true)
                              (swap! trail conj :handler)
                              ctx))
          ;; Order in declaration:  a (good) → boom (bad-after) → c (good) → handler
          ;; :after order (reverse): handler → c → boom (throws) → a
          chain   [(mk-good :a) (mk-bad-after :boom) (mk-good :c) handler]
          final   (interceptor/execute-chain chain {:coeffects {} :effects {}})]
      (is @ran-handler?
          "handler completed even though a downstream :after throws")
      (is (= :after (get-in final [:rf/interceptor-error :phase]))
          "the captured error remembers it happened in the :after phase")
      (is (= :boom (get-in final [:rf/interceptor-error :id]))
          "the captured error names the failing interceptor")
      (is (some #(= [:after :c] %) @trail)
          ":after stages downstream of the failing one (in reverse order:
           those reached BEFORE the throw — i.e. :handler's and :c's :after)
           did execute"))))
