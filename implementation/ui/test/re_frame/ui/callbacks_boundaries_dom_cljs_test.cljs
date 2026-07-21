(ns re-frame.ui.callbacks-boundaries-dom-cljs-test
  "rf2-vxgfnd.95.3 — the CLIENT behaviour of the S3 explicit callback boundaries,
  error recovery, and client-only on a real React root (jsdom/browser):

    ui/event      vector-or-nil filtering driven by the LIVE native event; a raw
                  host event never enters the dispatched vector.
    ui/handler    the explicit imperative committed callback at a FOREIGN prop —
                  per-site STABLE identity + reads the WINNING commit, imperative
                  return dropped.
    ui/raw-fn     an explicit callback ref fires with the live DOM node.
    error-boundary render-phase + effect-phase catch; the fallback renders with
                  :error; :on-error dispatches AFTER the failing commit with the
                  error appended; a :reset-key change recovers.
    client-only   the browser ACTIVATES the client subtree; the JVM/SSR fallback
                  never renders on the client.

  The JVM structural subset (transparent error-boundary child, client-only
  fallback marker, opaque internal fn-props) rides the sibling
  `callbacks_boundaries_jvm_test`."
  (:require ["react" :as React]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview local sub event handler
                                        error-boundary client-only]]
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
;; Harness
;; ---------------------------------------------------------------------------

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- dispatch-native! [el event] (.dispatchEvent el event))
(defn- click!
  ([el] (click! el {}))
  ([el opts] (dispatch-native! el (js/MouseEvent. "click" (clj->js (merge {:bubbles true} opts))))))
(defn- app-db [frame-id] @(:app-db (frame/frame frame-id)))
(defn- make-frame [id db] (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))
;; dispatch to a specific frame from outside a view (no ambient scope in the
;; test thunk): capture the frame's committed dispatch bundle by id.
(defn- frame-dispatch! [frame-id event]
  ((:dispatch (rf/capture-frame frame-id)) event))

;; Registrations are process-global but the per-test runtime reset wipes the
;; registrar, so each test re-registers after the fixture reset (before mount).
(defn- register! []
  (rf/reg-event :rec/add (fn [{:keys [db]} [_ x]] {:db (update db :log (fnil conj []) x)}))
  (rf/reg-event :rec/error (fn [{:keys [db]} [_ error]] {:db (assoc db :caught (.-message error))}))
  (rf/reg-event :eb/recover (fn [{:keys [db]} [_ k]] {:db (assoc db :explode? false :reset-key k)}))
  (rf/reg-sub :eb/explode? (fn [db _] (boolean (:explode? db))))
  (rf/reg-sub :eb/reset-key (fn [db _] (:reset-key db))))

;; A foreign React component: it invokes its `on-open` prop with a payload when
;; its button is clicked, and records the received callback identity each render.
(defonce ^:private foreign-cbs (atom []))
(defn Foreign [props]
  (swap! foreign-cbs conj (aget props "on-open"))
  (React/createElement
   "button" #js {"data-role" "foreign-btn"
                 "onClick" (fn [_] ((aget props "on-open") "PAYLOAD"))}
   "open"))

;; ---------------------------------------------------------------------------
;; ui/event — vector-or-nil filtering driven by the live native event (AC3)
;; ---------------------------------------------------------------------------

(defview event-filter []
  [:div
   ;; the live event decides the outcome: shift ⇒ dispatch, else ⇒ nil (filter)
   [:button {:data-role "maybe"
             :on-click (event [e] (when (.-shiftKey e) [:rec/add :shifted]))} "m"]
   [:button {:data-role "never"
             :on-click (event [_] nil)} "n"]])

(deftest ui-event-vector-or-nil-filtering
  (if-not (browser?)
    (is true ":node — browser gate runs ui/event vector-or-nil filtering")
    (let [f (make-frame ::ev {})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [event-filter]]]
              (let [maybe (.querySelector root "[data-role='maybe']")
                    never (.querySelector root "[data-role='never']")]
                (-> (uit/flush! #(do (click! never) (host-turn!)))
                    (.then (fn []
                             (is (nil? (:log (app-db ::ev)))
                                 "a nil ui/event result dispatches nothing (the filter)")
                             (uit/flush! #(do (click! maybe {}) (host-turn!)))))
                    (.then (fn []
                             (is (nil? (:log (app-db ::ev)))
                                 "no shift on the live event ⇒ nil ⇒ no dispatch")
                             (uit/flush! #(do (click! maybe {:shiftKey true}) (host-turn!)))))
                    (.then (fn []
                             (is (= [:shifted] (:log (app-db ::ev)))
                                 "the live native event drives the vector-or-nil outcome"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "ui/event: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; ui/handler at a FOREIGN prop — imperative, per-site stable, committed read
;; (AC1/AC2)
;; ---------------------------------------------------------------------------

(defonce ^:private handler-seen (atom nil))

(defview handler-host []
  (let [tick (sub [:eb/reset-key])          ; a changing value to force re-render
        [n set-n] (local 0)]
    [:div
     [:span {:data-role "n"} (str n)]
     [:button {:data-role "bump"
               :on-click (event [_] (set-n (inc n)) nil)} "+"]
     ;; imperative committed callback: reads the WINNING commit's n, return dropped
     [Foreign {:tick tick
               :on-open (handler [payload] (reset! handler-seen [payload n]))}]]))

(deftest ui-handler-imperative-stable-and-committed
  (if-not (browser?)
    (is true ":node — browser gate runs ui/handler imperative/stable/committed")
    (let [f (make-frame ::hd {:reset-key 0})]
      (register!)
      (reset! handler-seen nil)
      (reset! foreign-cbs [])
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [handler-host]]]
              (let [bump (.querySelector root "[data-role='bump']")
                    fbtn (.querySelector root "[data-role='foreign-btn']")]
                (-> (uit/flush! #(do (click! fbtn) (host-turn!)))
                    (.then (fn []
                             (is (= ["PAYLOAD" 0] @handler-seen)
                                 "the imperative handler runs with the invoker's arg + committed n=0")
                             ;; bump n twice, then bump `tick` to force the Foreign to re-render
                             (uit/flush! #(do (click! bump) (host-turn!)))))
                    (.then (fn [] (uit/flush! #(do (frame-dispatch! ::hd [:eb/recover 1]) (host-turn!)))))
                    (.then (fn []
                             (let [fbtn2 (.querySelector root "[data-role='foreign-btn']")]
                               (uit/flush! #(do (click! fbtn2) (host-turn!))))))
                    (.then (fn []
                             (is (= ["PAYLOAD" 1] @handler-seen)
                                 "the SAME stable callback now reads the winning commit (n=1)")
                             (is (and (<= 2 (count @foreign-cbs))
                                      (apply = @foreign-cbs))
                                 "the committed callback keeps ONE stable identity across renders"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "ui/handler: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; ui/raw-fn — the explicit callback ref fires with the live DOM node (AC4)
;; ---------------------------------------------------------------------------

(defonce ^:private ref-node (atom nil))

(defview ref-host []
  [:div {:data-role "reffed"
         :ref (ui/raw-fn (fn [node] (reset! ref-node node)))} "x"])

(deftest raw-fn-callback-ref-fires-with-the-node
  (if-not (browser?)
    (is true ":node — browser gate runs the raw-fn callback ref")
    (let [f (make-frame ::rf {})]
      (reset! ref-node nil)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [ref-host]]]
              (let [el (.querySelector root "[data-role='reffed']")]
                (is (some? @ref-node) "the explicit callback ref fired at commit")
                (is (identical? el @ref-node)
                    "it received the live DOM node (no committed-slot promise, layout-time)")))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "raw-fn ref: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; error-boundary — render/effect phases, fallback+:error, on-error, reset (AC5)
;; ---------------------------------------------------------------------------

(defview eb-fallback [{:keys [error]}]
  [:div {:data-role "fallback"} (str "caught:" (.-message error))])

(defview render-boom []
  (if (sub [:eb/explode?])
    (throw (js/Error. "render-boom"))
    [:div {:data-role "child"} "child-ok"]))

(defview eb-host []
  (error-boundary {:fallback eb-fallback
                   :reset-key (sub [:eb/reset-key])
                   :on-error [:rec/error]}
                  [render-boom]))

(deftest error-boundary-render-phase-recovery
  (if-not (browser?)
    (is true ":node — browser gate runs error-boundary render-phase + recovery")
    (let [f (make-frame ::eb {:explode? false :reset-key 0})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [eb-host]]]
              (do
                (is (some? (.querySelector root "[data-role='child']"))
                    "the guarded child renders normally")
                (-> (uit/flush! #(do (frame-dispatch! ::eb [:rf/set-db {:explode? true :reset-key 0}])
                                     (host-turn!)))
                    (.then (fn []
                             (is (some? (.querySelector root "[data-role='fallback']"))
                                 "a render-phase throw is caught — the fallback renders")
                             (is (nil? (.querySelector root "[data-role='child']"))
                                 "the throwing child is gone")
                             (is (= "caught:render-boom"
                                    (.-textContent (.querySelector root "[data-role='fallback']")))
                                 "the fallback renders with the caught :error")
                             (is (= "render-boom" (:caught (app-db ::eb)))
                                 ":on-error dispatched AFTER commit, with the error appended")
                             ;; recover: reset-key change + explode? false clears the error
                             (uit/flush! #(do (frame-dispatch! ::eb [:eb/recover 1]) (host-turn!)))))
                    (.then (fn []
                             (is (some? (.querySelector root "[data-role='child']"))
                                 "a changed :reset-key clears the caught error — the child retries")
                             (is (nil? (.querySelector root "[data-role='fallback']"))
                                 "the fallback is gone after recovery"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "error-boundary: " e)) (done))))))))

(defview effect-boom []
  (let [explode? (sub [:eb/explode?])]
    ;; the sub is read in the view body (render-time); the deferred effect body
    ;; may not read subs. A throwing post-commit effect is a lifecycle failure.
    (ui/effect [explode?] (when explode? (throw (js/Error. "effect-boom"))))
    [:div {:data-role "eff-child"} "ok"]))

(defview eb-effect-host []
  (error-boundary {:fallback eb-fallback :reset-key (sub [:eb/reset-key])}
                  [effect-boom]))

(deftest error-boundary-catches-effect-phase-throw
  (if-not (browser?)
    (is true ":node — browser gate runs error-boundary effect-phase catch")
    (let [f (make-frame ::ebe {:explode? true :reset-key 0})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [eb-effect-host]]]
              (-> (host-turn!)
                  (.then (fn []
                           (is (some? (.querySelector root "[data-role='fallback']"))
                               "an effect-phase (lifecycle) throw is also caught by the boundary")))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "eb effect: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; client-only — the browser activates the client subtree (AC5)
;; ---------------------------------------------------------------------------

(defview client-widget []
  [:div {:data-role "client-live"} "LIVE-INTERACTIVE"])

(defview client-only-host []
  (client-only {:fallback [:div {:data-role "fallback-skeleton"} "SERVER-FALLBACK"]}
               [client-widget]))

(deftest client-only-activates-on-the-client
  (if-not (browser?)
    (is true ":node — browser gate runs client-only activation")
    (let [f (make-frame ::co {})]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [client-only-host]]]
              (do
                (is (some? (.querySelector root "[data-role='client-live']"))
                    "the browser ACTIVATES the client subtree")
                (is (= "LIVE-INTERACTIVE"
                       (.-textContent (.querySelector root "[data-role='client-live']"))))
                (is (nil? (.querySelector root "[data-role='fallback-skeleton']"))
                    "the JVM/SSR fallback does not render on the client (S3 activation)")))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "client-only: " e)) (done))))))))
