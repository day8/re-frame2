(ns re-frame.form-3-async-ownership-cljs-test
  "Reagent adapter README §Form-3 — the async widget recipe owns every embed
  result (rf2-gwye.50 / rf2-fzbj.29 F2).

  The recipe teaches how to release an imperative library's state and
  listeners, so a version of it that leaks on ORDINARY navigation defeats its
  own guidance. Two sequences did exactly that before this pin: a mount whose
  embed Promise settles after the user has navigated away wrote the new widget
  into a closure nothing would ever finalize again, and two overlapping
  requests settling out of order let the older completion overwrite the newer
  accepted instance, leaking it.

  What is pinned is the recipe's OWNERSHIP ALGORITHM — the per-mount request
  token, `retire!`'s read-and-clear, and the three lifecycle bodies — mirrored
  below from `implementation/adapters/reagent/README.md` §Form-3 with
  `r/create-class`, `r/argv` and `js/vegaEmbed` replaced by plain calls and a
  caller-controlled embed fn, so the test drives the Promise sequencing. No
  React, DOM or Vega is needed: the defect is in the algorithm, and a Markdown
  link gate cannot see it. Keep the mirror and the README in step — the
  lifecycle bodies below are meant to read line-for-line against the recipe.

  Pins:

    * a completion that lands after unmount finalizes its own result
    * overlapping requests settling in reverse order keep only the newest;
      the superseded result is finalized once
    * ordinary mount → update → unmount retires each accepted instance once
    * unmount while an update is in flight leaks neither"
  (:require [cljs.test :refer-macros [async deftest is testing]]))

;; ---- the recipe's ownership algorithm, mirrored ----------------------------

(defn- make-widget
  "Mirror of the README recipe's inner factory. `embed-fn` stands in for
  `js/vegaEmbed` — given the spec it returns a Promise of a result object
  carrying `.-view`. Returns the three lifecycle bodies (each taking the spec
  directly rather than reading it off `r/argv`) plus a `:held` probe over the
  closure's currently-owned instance."
  [embed-fn]
  (let [vega-instance (atom nil)
        request       (atom 0)
        retire!       (fn []
                        (some-> (first (reset-vals! vega-instance nil)) .finalize))
        embed!        (fn [spec]
                        (let [token (swap! request inc)]
                          (retire!)
                          (-> (embed-fn spec)
                              (.then (fn [result]
                                       (let [view (.-view result)]
                                         (if (= token @request)
                                           (reset! vega-instance view)
                                           (.finalize view))))))))]
    {:did-mount    (fn [spec] (embed! spec) nil)
     :did-update   (fn [spec] (embed! spec) nil)
     :will-unmount (fn []
                     (swap! request inc)
                     (retire!)
                     nil)
     :held         (fn [] @vega-instance)}))

;; ---- controlled async harness ---------------------------------------------

(defn- deferred
  "A Promise with an out-of-band resolver, so a test settles requests in
  whatever order it likes."
  []
  (let [resolve! (atom nil)
        p        (js/Promise. (fn [res _rej] (reset! resolve! res)))]
    {:promise p :resolve! (fn [v] (@resolve! v))}))

(defn- view
  "A stand-in library instance whose `.finalize` records `id`."
  [id finalized]
  #js {:finalize (fn [] (swap! finalized conj id))})

(defn- result [v] #js {:view v})

(defn- settled
  "A Promise resolved after `n` microtask turns, so callbacks registered
  before ours have run."
  [n]
  (reduce (fn [p _] (.then p (fn [_] nil)))
          (js/Promise.resolve nil)
          (range n)))

;; ---- pins -----------------------------------------------------------------

(deftest completion-after-unmount-is-finalized-cljs-test
  (testing "a mount whose embed settles after unmount releases its own result"
    (async done
      (let [finalized (atom [])
            d         (deferred)
            w         (make-widget (fn [_spec] (:promise d)))]
        ((:did-mount w) {:spec :a})
        ((:will-unmount w))
        ((:resolve! d) (result (view "pending-at-unmount" finalized)))
        (-> (settled 4)
            (.then (fn [_]
                     (is (= ["pending-at-unmount"] @finalized)
                         "the late completion finalized the widget it created")
                     (is (nil? ((:held w)))
                         "and nothing was written back into the torn-down closure")
                     (done))))))))

(deftest out-of-order-completions-keep-only-the-newest-cljs-test
  (testing "two overlapping requests settling in reverse order leak nothing"
    (async done
      (let [finalized (atom [])
            d-older   (deferred)
            d-newer   (deferred)
            pending   (atom [(:promise d-older) (:promise d-newer)])
            next!     (fn [_spec] (let [[p & more] @pending] (reset! pending more) p))
            w         (make-widget next!)
            newer     (view "newer" finalized)]
        ((:did-mount w) {:spec :a})                     ; request 1 → d-older
        ((:did-update w) {:spec :b})                    ; request 2 → d-newer
        ((:resolve! d-newer) (result newer))
        ((:resolve! d-older) (result (view "older" finalized)))
        (-> (settled 4)
            (.then (fn [_]
                     (is (= ["older"] @finalized)
                         "the superseded completion finalized itself, exactly once")
                     (is (identical? newer ((:held w)))
                         "the newest result is the one still owned")
                     ((:will-unmount w))
                     (is (= ["older" "newer"] @finalized)
                         "final unmount retires the owned result, exactly once")
                     (is (nil? ((:held w))))
                     (done))))))))

(deftest ordinary-lifecycle-retires-each-instance-once-cljs-test
  (testing "mount → update → unmount finalizes each accepted instance once"
    (async done
      (let [finalized (atom [])
            d-one     (deferred)
            d-two     (deferred)
            pending   (atom [(:promise d-one) (:promise d-two)])
            next!     (fn [_spec] (let [[p & more] @pending] (reset! pending more) p))
            w         (make-widget next!)]
        ((:did-mount w) {:spec :a})
        ((:resolve! d-one) (result (view "one" finalized)))
        (-> (settled 4)
            (.then (fn [_]
                     (is (= [] @finalized) "nothing retired while the first is live")
                     ((:did-update w) {:spec :b})
                     (is (= ["one"] @finalized) "the update retires the previous instance")
                     ((:resolve! d-two) (result (view "two" finalized)))
                     (settled 4)))
            (.then (fn [_]
                     (is (= ["one"] @finalized) "the accepted second instance is still live")
                     ((:will-unmount w))
                     (is (= ["one" "two"] @finalized) "unmount retires it once")
                     (is (nil? ((:held w))))
                     (done))))))))

(deftest unmount-during-pending-update-leaks-neither-cljs-test
  (testing "teardown with an update in flight releases both the held and the late result"
    (async done
      (let [finalized (atom [])
            d-one     (deferred)
            d-two     (deferred)
            pending   (atom [(:promise d-one) (:promise d-two)])
            next!     (fn [_spec] (let [[p & more] @pending] (reset! pending more) p))
            w         (make-widget next!)]
        ((:did-mount w) {:spec :a})
        ((:resolve! d-one) (result (view "one" finalized)))
        (-> (settled 4)
            (.then (fn [_]
                     ((:did-update w) {:spec :b})       ; retires "one", starts request 2
                     ((:will-unmount w))                ; tears down with request 2 in flight
                     ((:resolve! d-two) (result (view "two" finalized)))
                     (settled 4)))
            (.then (fn [_]
                     (is (= ["one" "two"] @finalized)
                         "both the retired and the post-unmount result were released")
                     (is (nil? ((:held w))))
                     (done))))))))
