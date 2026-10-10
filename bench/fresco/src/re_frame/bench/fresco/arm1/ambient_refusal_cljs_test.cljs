(ns re-frame.bench.fresco.arm1.ambient-refusal-cljs-test
  "AMBIENT `rf/subscribe` / `rf/dispatch` INSIDE A BODY REFUSE.

  Fresco mounts the shared adapter-context Provider, and the UIx adapter's
  `:adapter/current-frame` reader reads that slot — so without a fence an
  ambient `rf/subscribe` inside a boundary body would RESOLVE the
  boundary's own frame and quietly succeed: a render-phase sub-cache
  mutation, ZERO collector edges, and a boundary that never re-renders
  when that subscription moves (HD-002 clause (a)).
  [[re-frame.bench.fresco.front.intent/with-frame]] establishes core's
  refusal tier over every render extent and declares the extent's own
  frame, so the answer is a loud `:rf.error/ambient-frame-refused` under
  every adapter, while an explicit or matching carry still answers.

  The refusal rows publish the frame on the shared context slot
  ([[with-context-frame]]), which is what React does while rendering under
  a `frame-provider` and the configuration in which the hazard would
  succeed; `the-refusal-unwinds-with-the-body` shows the same read
  succeeding once the body has returned. Bodies run through `render-body`
  rather than a React root because React routes a render-phase throw to
  `reportError`, where `cljs.test` cannot see it.

  Core's refusal logic is platform-neutral and pinned on the JVM by
  `re-frame.frame-resolver-test`; these rows hold the CLJS half — the
  context tier withdrawn, the prototype's extent declaring its frame, the
  always-on emission, and keywords compared by value."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::rt122)

(defn- make-frame! []
  (rf.live-frame/make-frame {:id frame-id})
  (rf.frame/replace-app-db! frame-id {:v 7})
  (rf/reg-sub :rt122/v (fn [db _] (:v db)))
  (rf/reg-event :rt122/bump (fn [{:keys [db]} _] {:db (update db :v inc)}))
  frame-id)

(defn- with-context-frame
  "Publish `frame-kw` on the SHARED React frame-context slot for `thunk`'s
  extent, then restore — the publication React performs while rendering
  under a `frame-provider`, and the slot the UIx adapter's
  `:adapter/current-frame` reader reads."
  [frame-kw thunk]
  (let [^js ctx  rf.adapter.context/frame-context
        original (.-_currentValue ctx)]
    (set! (.-_currentValue ctx) frame-kw)
    (try (thunk)
         (finally (set! (.-_currentValue ctx) original)))))

(defn- in-body
  "Run `op` inside a body of the boundary rendering `f`. Answers `[::ok v]`
  with its value, or the ex-data it threw."
  [f op]
  (let [v (volatile! nil)]
    (try (rf.bench.fresco.arm1.runtime/render-body f (fn [_] (vreset! v (op)) [:li]) {})
         [::ok @v]
         (catch :default e (ex-data e)))))

(defn- captured-errors
  "The always-on error records `thunk` produced."
  [thunk]
  (let [records (volatile! [])]
    (rf.error-emit/register-error-listener! ::rt122 (fn [r] (vswap! records conj r)))
    (try (thunk)
         (finally (rf.error-emit/unregister-error-listener! ::rt122)))
    @records))

(deftest ambient-subscribe-and-dispatch-inside-a-body-refuse-by-name
  (testing "under the published context, where an unfenced ambient read
           would silently succeed"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (is (= {:rf.error/id :rf.error/ambient-frame-refused
                  :operation   :subscribe
                  :substrate   :fresco
                  :event-id    :rt122/v
                  :where       're-frame.subs/subscribe}
                 (select-keys (in-body f #(deref (rf/subscribe [:rt122/v])))
                              [:rf.error/id :operation :substrate :event-id :where])))
          (is (= {:rf.error/id :rf.error/ambient-frame-refused
                  :operation   :dispatch
                  :substrate   :fresco}
                 (select-keys (in-body f #(rf/dispatch [:rt122/bump]))
                              [:rf.error/id :operation :substrate]))))))))

(deftest the-refusal-rides-the-always-on-axis
  (testing "a boundary that quietly stops re-rendering has no symptom at the
           point of the mistake, so the refusal must survive production
           elision"
    (let [f (make-frame!)]
      (is (some #(= :rf.error/ambient-frame-refused (:error %))
                (captured-errors #(in-body f (fn [] @(rf/subscribe [:rt122/v])))))))))

(deftest the-refusal-unwinds-with-the-body
  (testing "the extent is the body's synchronous run: a closure minted in
           the body and called after it returned — every child fiber,
           interop gate and event callback — resolves ambiently again"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [[_ deferred] (in-body f (fn [] #(deref (rf/subscribe [:rt122/v]))))]
            (is (= 7 (deferred)))))))))

(deftest the-legitimate-spellings-inside-a-body-answer-its-frame
  (testing "the refusal withdraws the ambient FIND, never an explicit or a
           matching carry"
    (let [f     (make-frame!)
          built (keyword (namespace frame-id) (name frame-id))]
      (is (not (identical? built f))
          "precondition: equal to the frame id but not the same object, so the
           matched-stamp row compares by value — keywords are not interned in
           ClojureScript")
      (doseq [[spelling op expected]
              [[:frame-option   #(deref (rf/subscribe [:rt122/v] {:frame f}))                      7]
               [:ambient-carry  #(:frame (rf/capture-frame))                                       f]
               [:composed-carry #(:frame (rf/capture-frame (rf.bench.fresco.front.intent/hframe))) f]
               [:matched-stamp  #(rf/with-frame built @(rf/subscribe [:rt122/v]))                  7]]]
        (is (= [::ok expected] (in-body f op)) (str spelling))))))

(deftest a-mismatched-carried-stamp-is-refused-inside-a-body
  (testing "an enclosing `rf/with-frame` naming a frame the boundary is NOT
           rendering would put two frames in one body, so the carry refuses
           and names both"
    (let [f     (make-frame!)
          other ::rt122-other]
      (rf.live-frame/make-frame {:id other})
      (is (= {:rf.error/id   :rf.error/ambient-frame-refused
              :operation     :capture-frame
              :carried-frame other
              :extent-frame  f}
             (select-keys (rf/with-frame other (in-body f rf/capture-frame))
                          [:rf.error/id :operation :carried-frame :extent-frame]))))))
