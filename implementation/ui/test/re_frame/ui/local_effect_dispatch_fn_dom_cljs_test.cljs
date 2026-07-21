(ns re-frame.ui.local-effect-dispatch-fn-dom-cljs-test
  "rf2-vxgfnd.95.2 — the client behaviour of the S3 host hooks on a real React
  root (jsdom/browser): `local` re-render + the P0-1 three-tuple (set! stores
  exactly, update! composes same-turn writers over the latest host state), the
  render-phase mutation guard, `effect` rf= value-deps + cleanup + :connect +
  StrictMode replay, and `ui/dispatch-fn` stable identity / committed-frame
  dispatch / fail-after-disconnect. The JVM structural subset + placement law
  ride the sibling `local_effect_dispatch_fn_jvm_test`."
  (:require ["react" :as React]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview local effect dispatch-fn]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

;; ---------------------------------------------------------------------------
;; Harness helpers
;; ---------------------------------------------------------------------------

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- dispatch-native! [el event] (.dispatchEvent el event))
(defn- click! [el] (dispatch-native! el (js/MouseEvent. "click" #js {:bubbles true})))
(defn- app-db [frame-id] @(:app-db (frame/frame frame-id)))

(defn- make-frame [id db]
  (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))

;; Top-level capture atoms (views close over these; each test resets them).
(defonce ^:private captured-d (atom nil))
(defonce ^:private effect-log (atom []))
(defonce ^:private renders (atom 0))

(defn- reset-caps! []
  (reset! captured-d nil)
  (reset! effect-log [])
  (reset! renders 0))

(defn- filter-first-run
  "Keep the first :run and :connect entries, order-insensitive between them
  (React may order the two connect effects either way)."
  [log]
  (vec (sort-by (fn [x] (case (first x) :run 0 :connect 1 2))
                (filter #(#{:run :connect} (first %)) log))))

(defn- runs-and-cleanups [log]
  (vec (filter #(#{:run :cleanup} (first %)) log)))

;; ---------------------------------------------------------------------------
;; local — re-render, committed read, the P0-1 three-tuple
;; ---------------------------------------------------------------------------

(defview counter []
  (let [[n set-n update-n] (local 0)]
    [:div
     [:span {:data-role "n"} (str n)]
     ;; set! reads the COMMITTED n; twice in one turn is last-write-wins.
     [:button {:data-role "set-twice"
               :on-click (ui/event [_] (set-n (inc n)) (set-n (inc n)) nil)} "s2"]
     ;; update! applies to the LATEST host state; twice in one turn composes.
     [:button {:data-role "up-twice"
               :on-click (ui/event [_] (update-n inc) (update-n inc) nil)} "u2"]]))

(deftest local-set-and-update-multi-writer-matrix
  (if-not (browser?)
    (is true ":node — browser gate runs local set!/update! matrix")
    (let [f (make-frame ::cnt {})]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [counter]]]
              (let [n-el (.querySelector root "[data-role='n']")
                    s2   (.querySelector root "[data-role='set-twice']")
                    u2   (.querySelector root "[data-role='up-twice']")]
                (is (= "0" (.-textContent n-el)) "local exposes its initial value")
                (-> (uit/flush! #(do (click! s2) (host-turn!)))
                    (.then
                     (fn []
                       (is (= "1" (.-textContent n-el))
                           "set! twice in one turn is last-write-wins over committed n")
                       (uit/flush! #(do (click! u2) (host-turn!)))))
                    (.then
                     (fn []
                       (is (= "3" (.-textContent n-el))
                           "update! twice composes over the LATEST host state (1 -> 3)"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "local matrix: " e)) (done))))))))

(defview fn-value-holder []
  (let [[v set-v] (local 5)]
    [:div
     [:span {:data-role "kind"} (if (fn? v) "fn" (str v))]
     ;; If set! wrongly treated a fn as a React updater it would store (inc 5)=6;
     ;; P0-1 stores the fn EXACTLY.
     [:button {:data-role "store-fn" :on-click (ui/event [_] (set-v inc) nil)} "f"]]))

(deftest local-set-stores-a-function-value-exactly
  (if-not (browser?)
    (is true ":node — browser gate runs fn-value set!")
    (let [f (make-frame ::fnv {})]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [fn-value-holder]]]
              (let [kind (.querySelector root "[data-role='kind']")]
                (is (= "5" (.-textContent kind)))
                (-> (uit/flush! #(do (click! (.querySelector root "[data-role='store-fn']"))
                                     (host-turn!)))
                    (.then
                     (fn []
                       (is (= "fn" (.-textContent kind))
                           "set! stored the fn exactly — never invoked it as an updater"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "fn-value set!: " e)) (done))))))))

(defview render-phase-offender []
  ;; A set! evaluated in a render-phase binding must fail loud (renders are
  ;; speculative). No infinite loop: the guard throws on the first call.
  (let [[_ set-n] (local 0)
        _ (set-n 1)]
    [:div "unreachable"]))

(deftest local-set-during-render-fails-loud
  (if-not (browser?)
    (is true ":node — browser gate runs the render-phase guard")
    (async done
      (-> (uit/with-root [root [render-phase-offender]]
            (is false "a render-phase set! must reject the mount"))
          (.then
           (fn [] (is false "render-phase set! did not throw"))
           (fn [e]
             (is (re-find #"render|local mutation is host-only"
                          (str (or (.-message e) "") " " e))
                 "the render-phase set! guard fired loud")))
          (.then (fn [] (done)) (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; effect — rf= value-deps, cleanup, :connect
;; ---------------------------------------------------------------------------

(defview effect-probe []
  (let [dep   (ui/sub [::dep])
        other (ui/sub [::other])]
    ;; value-deps: only `dep` is a dependency; `other` changing must not re-run.
    (effect [dep]
      (swap! effect-log conj [:run dep])
      (fn [] (swap! effect-log conj [:cleanup dep])))
    (effect :connect (swap! effect-log conj [:connect]))
    [:span {:data-role "e"} (str dep "/" other)]))

(deftest effect-value-deps-cleanup-and-connect
  (if-not (browser?)
    (is true ":node — browser gate runs effect deps/cleanup/:connect")
    (do
      (rf/reg-sub ::dep (fn [db _] (:dep db)))
      (rf/reg-sub ::other (fn [db _] (:other db)))
      (rf/reg-event ::set-dep (fn [{:keys [db]} [_ v]] {:db (assoc db :dep v)}))
      (rf/reg-event ::set-other (fn [{:keys [db]} [_ v]] {:db (assoc db :other v)}))
      (reset-caps!)
      (let [f (make-frame ::eff {:dep 0 :other 0})]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [effect-probe]]]
                (-> (host-turn!)
                    (.then
                     (fn []
                       (is (= [[:run 0] [:connect]] (filter-first-run @effect-log))
                           "value-deps effect + :connect both run after mount")
                       (uit/flush! #(rf/dispatch-sync [::set-other 9] {:frame f}))))
                    (.then (fn [] (host-turn!)))
                    (.then
                     (fn []
                       (is (not-any? #(= % [:run 9]) @effect-log))
                       (is (= 1 (count (filter #(= (first %) :run) @effect-log)))
                           "a non-dep (other) change does NOT re-run the effect (rf= deps)")
                       (uit/flush! #(rf/dispatch-sync [::set-dep 1] {:frame f}))))
                    (.then (fn [] (host-turn!)))
                    (.then
                     (fn []
                       (is (= [:cleanup 0] (nth (runs-and-cleanups @effect-log) 1))
                           "a dep change cleans up the prior run BEFORE the new run")
                       (is (some #(= % [:run 1]) @effect-log)
                           "the effect re-runs with the new dep")))))
              (.then
               (fn []
                 ;; After teardown the value-deps cleanup ran on unmount.
                 (is (some #(= % [:cleanup 1]) @effect-log)
                     "unmount runs the value-deps cleanup")
                 (rf/destroy-frame! f)
                 (done))
               (fn [e]
                 (rf/destroy-frame! f)
                 (is false (str "effect fixture: " e))
                 (done)))))))))

;; ---------------------------------------------------------------------------
;; effect — StrictMode replay is idempotent-safe (cleanup balances setup)
;; ---------------------------------------------------------------------------

(defview strict-effect-leaf []
  (do (effect :connect
        (swap! effect-log conj :setup)
        (fn [] (swap! effect-log conj :teardown)))
      [:span {:data-role "strict"} "ok"]))

(defview strict-effect-host []
  (ui/raw (React/createElement (.-StrictMode React) nil
                               (React/createElement strict-effect-leaf nil))))

(deftest effect-strictmode-replay-balances-setup-and-teardown
  (if-not (browser?)
    (is true ":node — browser gate runs StrictMode effect replay")
    (do
      (reset-caps!)
      (async done
        (-> (uit/with-root [root [strict-effect-host]]
              (host-turn!))
            (.then
             (fn []
               ;; StrictMode replays setup/teardown; the effect owns cleanup, so
               ;; teardowns never trail setups by more than the one live effect.
               (let [setups    (count (filter #(= % :setup) @effect-log))
                     teardowns (count (filter #(= % :teardown) @effect-log))]
                 (is (pos? setups) "the :connect effect ran under StrictMode")
                 (is (<= (- setups teardowns) 1)
                     "every StrictMode replay setup is balanced by a cleanup (no leak)"))
               (done))
             (fn [e] (is false (str "StrictMode effect: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; dispatch-fn — stable identity, committed-frame dispatch, fail-after-disconnect
;; ---------------------------------------------------------------------------

(defview dispatcher-probe []
  (let [[n set-n] (local 0)
        d (dispatch-fn)
        _ (reset! captured-d d)]
    [:div
     [:span {:data-role "dn"} (str n)]
     ;; a local re-render, so we can re-capture d and compare identity
     [:button {:data-role "bump" :on-click (ui/event [_] (set-n (inc n)) nil)} "b"]
     ;; drive a dispatch through the committed-frame dispatcher from a handler
     [:button {:data-role "send" :on-click (ui/event [_] (d [::hit]) nil)} "s"]]))

(deftest dispatch-fn-stable-committed-and-fails-after-disconnect
  (if-not (browser?)
    (is true ":node — browser gate runs dispatch-fn lifecycle")
    (do
      (rf/reg-event ::hit (fn [{:keys [db]} _] {:db (update db :hits (fnil inc 0))}))
      (reset-caps!)
      (let [f       (make-frame ::disp {:hits 0})
            first-d (atom nil)]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [dispatcher-probe]]]
                (-> (host-turn!)
                    (.then
                     (fn []
                       (reset! first-d @captured-d)
                       (is (fn? @first-d) "dispatch-fn returns a function")
                       (uit/flush! #(do (click! (.querySelector root "[data-role='bump']"))
                                        (host-turn!)))))
                    (.then
                     (fn []
                       (is (identical? @first-d @captured-d)
                           "dispatch-fn identity is stable across renders")
                       (uit/flush! #(do (click! (.querySelector root "[data-role='send']"))
                                        (host-turn!)))))
                    (.then
                     (fn []
                       (is (= 1 (:hits (app-db ::disp)))
                           "the dispatcher routes to the committed frame")))))
              (.then
               (fn []
                 ;; teardown disconnected the owner; the retained dispatcher rejects.
                 (let [id (try (@first-d [::hit]) ::no-throw
                               (catch :default e (:rf.error/id (ex-data e))))]
                   (is (= :rf.error/dispatch-disconnected id)
                       "a dispatcher fired after disconnect rejects loud"))
                 (rf/destroy-frame! f)
                 (done))
               (fn [e]
                 (rf/destroy-frame! f)
                 (is false (str "dispatch-fn fixture: " e))
                 (done)))))))))

;; ---------------------------------------------------------------------------
;; mixed local-set + controlled dispatch — one host render pass
;; ---------------------------------------------------------------------------

(defview mixed-input []
  (let [_ (swap! renders inc)
        [draft set-draft] (local "")]
    [:input {:data-role "mixed"
             :value draft
             ;; a controlled site: the sync door drains the dispatch first, then
             ;; the local set! batches — one host render pass for the pair.
             :on-input (ui/event [e]
                         (set-draft (.. e -target -value))
                         [::typed (.. e -target -value)])}]))

(deftest mixed-local-set-and-controlled-dispatch-one-render-pass
  (if-not (browser?)
    (is true ":node — browser gate runs mixed local-set + dispatch")
    (do
      (rf/reg-event ::typed (fn [{:keys [db]} [_ v]] {:db (assoc db :typed v)}))
      (reset-caps!)
      (let [f (make-frame ::mix {})]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [mixed-input]]]
                (let [el (.querySelector root "[data-role='mixed']")]
                  (-> (host-turn!)
                      (.then
                       (fn []
                         (reset! renders 0)
                         (uit/flush!
                          #(do (set! (.-value el) "hi")
                               (dispatch-native! el (js/Event. "input" #js {:bubbles true}))
                               (host-turn!)))))
                      (.then
                       (fn []
                         (is (= "hi" (:typed (app-db ::mix)))
                             "the controlled dispatch drained")
                         (is (= "hi" (.-value el)) "the local draft updated")
                         (is (= 1 @renders)
                             "the local set! + sync-door dispatch settle in ONE host pass"))))))
              (.then
               (fn [] (rf/destroy-frame! f) (done))
               (fn [e] (rf/destroy-frame! f) (is false (str "mixed fixture: " e)) (done)))))))))

;; ---------------------------------------------------------------------------
;; mixed update! + controlled dispatch — the G-15 updater/dispatch causal arm
;; ---------------------------------------------------------------------------
;;
;; CANONICAL G-15 DEFINITION (07-testing.md §5 :145; EP-0035 §Gates; EP-0034 §4):
;; the atomic-local writer matrix must include a MIXED `update!`+dispatch arm —
;; not merely `set!`+dispatch. The sibling `mixed-input` above proves
;; `set-draft` (a `set!`) composes with a dispatch; it does NOT exercise the
;; `update!` UPDATER (which applies `(f current & args)` to the LATEST host
;; state) alongside a dispatch. This fixture closes that specific gap
;; (rf2-vxgfnd.95.10, 2026-07-17 audit comment): two `update!` writers compose
;; over the latest host state (0 -> 2) AND a live-payload dispatch rides the
;; SAME sync door, all in ONE host render pass. It is the smallest focused
;; causal proof of the updater/dispatch interaction; it does not duplicate the
;; broader roster.

(defview mixed-update-input []
  (let [_ (swap! renders inc)
        [n _ update-n] (local 0)]
    ;; A compiler-proven controlled site (literal `:value` co-present with the
    ;; `ui/event` handler on `:on-input`). In ONE turn the body runs two
    ;; `update!` writers that compose over the LATEST host state and returns a
    ;; runtime dispatch vector carrying the live payload.
    [:input {:data-role "mixed-update"
             :value (str n)
             :on-input (ui/event [e]
                         (update-n inc)
                         (update-n inc)
                         [::typed (.. e -target -value)])}]))

(deftest mixed-local-update-and-controlled-dispatch-compose-in-one-pass
  (if-not (browser?)
    (is true ":node — browser gate runs mixed update! + dispatch")
    (do
      (rf/reg-event ::typed (fn [{:keys [db]} [_ v]] {:db (assoc db :typed v)}))
      (reset-caps!)
      (let [f (make-frame ::mixu {})]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [mixed-update-input]]]
                (let [el (.querySelector root "[data-role='mixed-update']")]
                  (-> (host-turn!)
                      (.then
                       (fn []
                         (is (= "0" (.-value el)) "the updater local exposes its initial value")
                         (reset! renders 0)
                         (uit/flush!
                          #(do (set! (.-value el) "go")
                               (dispatch-native! el (js/Event. "input" #js {:bubbles true}))
                               (host-turn!)))))
                      (.then
                       (fn []
                         (is (= "2" (.-value el))
                             "two update! writers composed over the LATEST host state (0 -> 2)")
                         (is (= "go" (:typed (app-db ::mixu)))
                             "the live-payload dispatch rode the same sync door in the same turn")
                         (is (= 1 @renders)
                             "the update! writers + sync-door dispatch settle in ONE host pass"))))))
              (.then
               (fn [] (rf/destroy-frame! f) (done))
               (fn [e] (rf/destroy-frame! f) (is false (str "mixed update fixture: " e)) (done)))))))))
