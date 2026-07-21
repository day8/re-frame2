(ns re-frame.ui.react-interop-dom-cljs-test
  "rf2-vxgfnd.95.4 — the frozen re-frame.ui.react interop tier on a real React
  root (jsdom/browser): the client behaviour the JVM suite cannot exercise.

    - use-ref: `.current` survives re-renders; a write never re-renders; the
      ref attaches to a `:ref` position;
    - use-effect: passive, `rf=` VALUE deps (a distinct-but-value-equal CLJS
      deps value is NOT a change — rf2-u53yy.6 converged the interop tier onto
      `rf=`, superseding the .95.12 Object.is split), cleanup on dep change +
      unmount;
    - use-layout-effect: the readiness C-6 measure-before-paint NATIVE consumer
      (measure in the layout effect, pure geometry, apply, clean up exactly);
      StrictMode replay is idempotent-safe; unmount (reconnect) runs cleanup;
    - use-context: reads a FOREIGN React provider's value uncoerced;
    - use-id: a non-empty host token, stable across re-renders;
    - use-effect-event: latest render's body, UNSTABLE identity (native React
      19.2 — a fresh function every render);
    - lazy: Suspense fallback then foreign activation.

  Compile position law + JVM structural subset + hook-signature HMR law ride the
  sibling `react_interop_jvm_test`."
  (:require ["react" :as React]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview local]]
            [re-frame.ui.react :as react]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true
    :init-fn reactive/reset-scheduler!}))

(defn- host-turn! [] (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))
(defn- click! [el] (.dispatchEvent el (js/MouseEvent. "click" #js {:bubbles true})))
(defn- make-frame [id db] (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))

(defonce ^:private log (atom []))
(defonce ^:private idents (atom []))
(defn- reset-log! [] (reset! log []) (reset! idents []))

;; ---------------------------------------------------------------------------
;; use-ref — survives re-render; a write never re-renders
;; ---------------------------------------------------------------------------

(defview ref-view []
  ;; a use-ref cell written from a committed handler (never during render — that
  ;; would be a render-phase mutation); it survives re-renders and its write
  ;; does NOT re-render (contrast `local`).
  (let [[n set-n] (local 0)
        r         (react/use-ref)]
    [:div
     [:span {:data-role "n"} (str n)]
     [:button {:data-role "stash" :on-click (ui/handler [_] (set! (.-current r) 77))} "stash"]
     [:button {:data-role "re" :on-click (ui/handler [_] (set-n (inc n)))} "re"]
     [:button {:data-role "read" :on-click (ui/handler [_] (swap! log conj [:read (.-current r)]))} "read"]]))

(deftest use-ref-current-survives-re-render
  (if-not (browser?)
    (is true ":node — browser gate runs use-ref persistence")
    (async done
      (reset-log!)
      (let [f (make-frame ::ref {})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [ref-view]]]
              (-> (host-turn!)
                  ;; write the ref from a handler — this must NOT re-render.
                  (.then (fn [] (uit/flush! #(click! (.querySelector root "[data-role='stash']")))))
                  (.then host-turn!)
                  (.then (fn []
                           (is (= "0" (.-textContent (.querySelector root "[data-role='n']")))
                               "writing use-ref's current never re-renders")
                           ;; force an unrelated re-render, then read the ref back.
                           (uit/flush! #(click! (.querySelector root "[data-role='re']")))))
                  (.then host-turn!)
                  (.then (fn []
                           (uit/flush! #(click! (.querySelector root "[data-role='read']")))))
                  (.then host-turn!)
                  (.then (fn []
                           (is (some #(= % [:read 77]) @log)
                               "the ref object survived the re-render (current preserved)")))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str e)) (done))))))))

;; ---------------------------------------------------------------------------
;; use-effect — rf= VALUE deps, cleanup (rf2-u53yy.6: one equality doctrine)
;; ---------------------------------------------------------------------------

(defview effect-view []
  (let [dep   (ui/sub [::dep])
        other (ui/sub [::other])
        _     (react/use-effect
               (fn []
                 (swap! log conj [:run dep])
                 (fn [] (swap! log conj [:cleanup dep])))
               [dep])]
    [:span {:data-role "e"} (str dep "/" other)]))

(deftest use-effect-value-deps-cleanup
  (if-not (browser?)
    (is true ":node — browser gate runs use-effect deps/cleanup")
    (async done
      (rf/reg-sub ::dep (fn [db _] (:dep db)))
      (rf/reg-sub ::other (fn [db _] (:other db)))
      (rf/reg-event ::set-dep (fn [{:keys [db]} [_ v]] {:db (assoc db :dep v)}))
      (rf/reg-event ::set-other (fn [{:keys [db]} [_ v]] {:db (assoc db :other v)}))
      (reset-log!)
      (let [f (make-frame ::eff {:dep 0 :other 0})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [effect-view]]]
              (-> (host-turn!)
                  (.then (fn [] (is (some #(= % [:run 0]) @log) "runs after mount")
                           (uit/flush! #(rf/dispatch-sync [::set-other 9] {:frame f}))))
                  (.then host-turn!)
                  (.then (fn []
                           (is (= 1 (count (filter #(= (first %) :run) @log)))
                               "an unchanged (rf=) dep does NOT re-run")
                           (uit/flush! #(rf/dispatch-sync [::set-dep 1] {:frame f}))))
                  (.then host-turn!)
                  (.then (fn []
                           (is (some #(= % [:cleanup 0]) @log) "dep change cleans up the prior run")
                           (is (some #(= % [:run 1]) @log) "then re-runs with the new dep")))))
            (.then (fn []
                     (is (some #(= % [:cleanup 1]) @log) "unmount runs cleanup")
                     (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str e)) (done))))))))

;; The rf= convergence (rf2-u53yy.6): a deps SLOT holding a fresh-but-value-equal
;; CLJS collection — rebuilt every render — keeps the SAME token, so an unrelated
;; re-render does NOT rerun the effect. Under the superseded Object.is doctrine
;; the distinct collection object WOULD have counted as a change and reran; under
;; `rf=` (value equality) it is the same dep. A value-different collection reruns.
(defview value-collection-effect-view []
  (let [n     (ui/sub [::vn])
        other (ui/sub [::vother])
        ;; the dep slot is a FRESH map literal every render — object-distinct,
        ;; but `rf=`-equal whenever n is unchanged.
        _     (react/use-effect
               (fn []
                 (swap! log conj [:vrun n])
                 (fn [] (swap! log conj [:vcleanup n])))
               [{:k n}])]
    [:span {:data-role "v"} (str n "/" other)]))

(deftest use-effect-rf=-equal-collection-dep-skips-rerun
  (if-not (browser?)
    (is true ":node — browser gate runs the rf= value-deps convergence")
    (async done
      (rf/reg-sub ::vn (fn [db _] (:vn db)))
      (rf/reg-sub ::vother (fn [db _] (:vother db)))
      (rf/reg-event ::set-vn (fn [{:keys [db]} [_ v]] {:db (assoc db :vn v)}))
      (rf/reg-event ::set-vother (fn [{:keys [db]} [_ v]] {:db (assoc db :vother v)}))
      (reset-log!)
      (let [f (make-frame ::veff {:vn 0 :vother 0})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [value-collection-effect-view]]]
              (-> (host-turn!)
                  (.then (fn [] (is (some #(= % [:vrun 0]) @log) "runs after mount")
                           ;; unrelated re-render: rebuilds the {:k 0} dep slot to a
                           ;; DISTINCT-but-rf=-equal object.
                           (uit/flush! #(rf/dispatch-sync [::set-vother 9] {:frame f}))))
                  (.then host-turn!)
                  (.then (fn []
                           (is (= 1 (count (filter #(= (first %) :vrun) @log)))
                               "a fresh-but-rf=-equal collection dep does NOT rerun (rf=, not Object.is)")
                           ;; a value-different collection dep DOES rerun.
                           (uit/flush! #(rf/dispatch-sync [::set-vn 1] {:frame f}))))
                  (.then host-turn!)
                  (.then (fn []
                           (is (some #(= % [:vcleanup 0]) @log)
                               "a value-changed collection dep cleans up the prior run")
                           (is (some #(= % [:vrun 1]) @log)
                               "then reruns with the new value")))))
            (.then (fn []
                     (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str e)) (done))))))))

;; ---------------------------------------------------------------------------
;; use-layout-effect — the readiness C-6 measure-before-paint NATIVE consumer
;; ---------------------------------------------------------------------------

;; Pure `.cljc`-style placement geometry: clamp the measured anchor into the
;; viewport, flipping above when it would overflow below. No host access.
(defn- placement [anchor-top el-height viewport-h]
  (if (> (+ anchor-top el-height) viewport-h)
    {:side :above :top (max 0 (- anchor-top el-height))}
    {:side :below :top anchor-top}))

(defview popover [{:keys [anchor viewport]}]
  ;; a NATIVE consumer (no foreign boundary): measure the element in a layout
  ;; effect (before paint), compute placement with pure geometry, apply it, and
  ;; clean up exactly on teardown / re-measure.
  (let [el (react/use-ref)
        _  (react/use-layout-effect
            (fn []
              (let [h  (.-offsetHeight (.-current el))
                    p  (placement anchor h viewport)]
                (set! (.. el -current -dataset -side) (name (:side p)))
                (set! (.. el -current -dataset -top) (str (:top p)))
                (swap! log conj [:place (:side p) (:top p)])
                (fn [] (swap! log conj [:place-cleanup]))))
            [anchor viewport])]
    [:div {:ref el :data-role "popover"} "menu"]))

(deftest layout-effect-measure-before-paint-and-cleanup
  (if-not (browser?)
    (is true ":node — browser gate runs the C-6 measure-before-paint consumer")
    (async done
      (reset-log!)
      (let [f (make-frame ::pop {})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [popover {:anchor 10 :viewport 500}]]]
              (-> (host-turn!)
                  (.then (fn []
                           (let [^js el (.querySelector root "[data-role='popover']")]
                             ;; the layout effect measured + applied placement to the DOM.
                             (is (some #(= (first %) :place) @log)
                                 "the layout effect measured and computed placement")
                             (is (= "below" (.. el -dataset -side))
                                 "pure geometry applied a placement attribute before paint"))))))
            (.then (fn []
                     (is (some #(= % [:place-cleanup]) @log)
                         "teardown ran the layout effect cleanup exactly")
                     (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str e)) (done))))))))

;; StrictMode replay of the native measure-before-paint consumer is idempotent
;; (cleanup balances setup — no double-applied placement leaks).
(defview strict-popover-host []
  (ui/raw (React/createElement (.-StrictMode React) nil
                               (React/createElement popover #js {:anchor 10 :viewport 500}))))

(deftest layout-effect-strictmode-is-idempotent
  (if-not (browser?)
    (is true ":node — browser gate runs StrictMode layout replay")
    (async done
      (reset-log!)
      (-> (uit/with-root [root [strict-popover-host]]
            (-> (host-turn!)
                (.then (fn []
                         (let [places   (count (filter #(= (first %) :place) @log))
                               cleanups (count (filter #(= % [:place-cleanup]) @log))]
                           (is (>= places 1) "the layout effect ran")
                           ;; StrictMode double-invokes; each setup is balanced by a
                           ;; cleanup, so no orphaned placement survives (idempotent).
                           (is (= places (inc cleanups))
                               "every StrictMode setup but the live one is balanced by a cleanup"))))))
          (.then (fn [] (done)) (fn [e] (is false (str e)) (done)))))))

;; ---------------------------------------------------------------------------
;; use-context — reads a FOREIGN provider's value
;; ---------------------------------------------------------------------------

(def ^:private Theme (React/createContext "default"))

(defview theme-consumer [] (let [v (react/use-context Theme)] [:span {:data-role "theme"} v]))

(defview theme-host []
  (ui/raw (React/createElement (.-Provider Theme) #js {:value "themed"}
                               (React/createElement theme-consumer #js {}))))

(deftest use-context-reads-foreign-provider
  (if-not (browser?)
    (is true ":node — browser gate runs use-context")
    (async done
      (-> (uit/with-root [root [theme-host]]
            (-> (host-turn!)
                (.then (fn []
                         (is (= "themed" (.-textContent (.querySelector root "[data-role='theme']")))
                             "the foreign context value flows through uncoerced")))))
          (.then (fn [] (done)) (fn [e] (is false (str e)) (done)))))))

;; ---------------------------------------------------------------------------
;; use-id — a non-empty host token, stable across re-renders
;; ---------------------------------------------------------------------------

(defview id-view []
  (let [[n set-n] (local 0)
        id        (react/use-id)
        _         (swap! log conj [:id id])]
    [:div
     [:label {:data-role "id" :for id} (str n)]
     [:button {:data-role "re" :on-click (ui/handler [_] (set-n (inc n)))} "re"]]))

(deftest use-id-is-stable-across-renders
  (if-not (browser?)
    (is true ":node — browser gate runs use-id")
    (async done
      (reset-log!)
      (let [f (make-frame ::id {})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [id-view]]]
              (-> (host-turn!)
                  (.then (fn [] (uit/flush! #(click! (.querySelector root "[data-role='re']")))))
                  (.then host-turn!)
                  (.then (fn []
                           (let [ids (map second (filter #(= (first %) :id) @log))]
                             (is (every? #(and (string? %) (seq %)) ids) "a non-empty host token")
                             (is (apply = ids) "stable across re-renders"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str e)) (done))))))))

;; ---------------------------------------------------------------------------
;; use-effect-event — latest body, UNSTABLE identity (native React 19.2)
;; ---------------------------------------------------------------------------

(defview effect-event-view []
  (let [[n set-n] (local 0)
        on-tick   (react/use-effect-event (fn [] (swap! log conj [:tick n])))
        _         (react/use-effect (fn [] (on-tick) nil))
        _         (swap! idents conj on-tick)]
    [:button {:data-role "inc" :on-click (ui/handler [_] (set-n (inc n)))} (str n)]))

(deftest use-effect-event-latest-body-and-unstable-identity
  (if-not (browser?)
    (is true ":node — browser gate runs use-effect-event")
    (async done
      (reset-log!)
      (let [f (make-frame ::ee {})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [effect-event-view]]]
              (-> (host-turn!)
                  (.then (fn [] (uit/flush! #(click! (.querySelector root "[data-role='inc']")))))
                  (.then host-turn!)
                  (.then (fn []
                           ;; the effect (runs every commit) calls the effect-event,
                           ;; whose body sees the LATEST render's n.
                           (is (some #(= % [:tick 1]) @log)
                               "the effect-event body sees the latest render's value")
                           ;; native React 19.2: a fresh function identity each render.
                           (is (>= (count @idents) 2))
                           (is (not= (first @idents) (second @idents))
                               "identity is UNSTABLE across renders (no shim)")))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str e)) (done))))))))

;; ---------------------------------------------------------------------------
;; lazy — Suspense fallback then foreign activation
;; ---------------------------------------------------------------------------

(defn- heavy-thunk []
  ;; a code-split foreign component, resolved on a later microtask.
  (js/Promise.resolve
   #js {:default (fn heavy [_props]
                   (React/createElement "span" #js {:data-role "heavy"} "heavy!"))}))

(def LazyHeavy (react/lazy heavy-thunk {:fallback [:span.spin "loading"]}))

(defview lazy-page [] [:section [LazyHeavy {}]])

(deftest lazy-shows-fallback-then-activates
  (if-not (browser?)
    (is true ":node — browser gate runs lazy activation")
    (async done
      (-> (uit/with-root [root [lazy-page]]
            (-> (host-turn!)
                (.then host-turn!)
                (.then host-turn!)
                (.then (fn []
                         (is (some? (.querySelector root "[data-role='heavy']"))
                             "the code-split foreign component activated after its Promise resolved")
                         (is (= "heavy!" (.-textContent (.querySelector root "[data-role='heavy']"))))))))
          (.then (fn [] (done)) (fn [e] (is false (str e)) (done)))))))
