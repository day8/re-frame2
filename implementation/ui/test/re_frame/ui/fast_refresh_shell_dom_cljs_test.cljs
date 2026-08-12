(ns re-frame.ui.fast-refresh-shell-dom-cljs-test
  "rf2-8hf77d — real React/browser counterexamples for the dev-only stable
  component shell. Same-signature registration updates without root.render;
  incompatible hooks remount only the inner Fiber; a render made stale before
  layout cannot publish even an equal dependency set."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as ReactDOMClient]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as runtime]))

(defn- browser? [] (exists? js/document))

(use-fixtures
 :each
 (test-support/make-reset-runtime-fixture
  {:adapter ui/adapter :ambient-frame nil :async? true}))

(defn- act-promise [thunk]
  (try
    (js/Promise.resolve
     (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- register!
  [id render-fn compare-fn hook-signature label]
  (runtime/register-view!
   id render-fn compare-fn label
   {:view-id id
    :display-name label
    :template-fingerprint (str "tf1-" label)
    :hook-signature hook-signature
    :sites {}}))

(defn- child-with-state [props]
  (let [probe (unchecked-get props "probe")
        [n set-n] (React/useState 10)
        token (React/useRef (js-obj))]
    (swap! probe assoc :child-n n :set-child set-n
           :child-token (.-current token))
    (React/createElement "span" #js {:data-hmr-child "state"}
                         (str n))))

(defn- stateful-body [version probe read-sub?]
  (fn [props]
    (let [[n set-n] (React/useState 0)
          token (React/useRef (js-obj))
          prop-v (unchecked-get props "value")
          sub-v  (when read-sub?
                   (reactive/sub-read ::preserve-site [::preserve-value]))]
      (swap! probe assoc :parent-n n :set-parent set-n
             :parent-token (.-current token))
      (React/createElement
       "section" #js {:data-hmr-version version}
       (React/createElement "output" #js {:data-hmr-parent "state"}
                            (str version ":" prop-v ":" n
                                 (when read-sub? (str ":" sub-v))))
       (React/createElement child-with-state #js {:probe probe})))))

(deftest same-signature-refresh-preserves-state-and-uses-current-comparator
  (if-not (browser?)
    (is true ":node — browser gate runs the mounted Fast Refresh proof")
    (async done
      (let [id        ::preserve
            fid       :fast-refresh/preserve-frame
            hs        "hs1-preserve"
            probe     (atom {})
            tokens    (atom nil)
            shell-2   (atom nil)
            shell-3   (atom nil)
            warnings  (atom [])
            original-error (.-error js/console)
            container (js/document.createElement "div")
            root      (ReactDOMClient/createRoot container)
            cache-entry #(get @(:sub-cache (frame/frame fid))
                              [::preserve-value])
            shell-1   (register! id (stateful-body "v1" probe false)
                                 (fn [_ _] true) hs "PreserveView")
            shells-1  (reactive/view-shells id)]
        (rf/reg-sub ::preserve-value (fn [db _] (:value db)))
        (rf/make-frame {:id fid :doc "Fast Refresh sub-site mutation proof"})
        (frame/replace-app-db! fid {:value 42})
        (.appendChild (.-body js/document) container)
        (set! (.-error js/console)
              (fn [& xs] (swap! warnings conj (mapv str xs))))
        (-> (act-promise
             #(.render root
                       (frames/scope-element
                        fid
                        (array (React/createElement
                                shell-1 #js {:value "p1"})))))
            (.then
             (fn []
               (reset! tokens {:parent (:parent-token @probe)
                               :child (:child-token @probe)})
               (act-promise
                #(do ((:set-parent @probe) 7)
                     ((:set-child @probe) 19)))))
            (.then
             (fn []
               ;; No root.render: slot publication + uSES notify is the only
               ;; update path. This edit also adds the first sub site while
               ;; keeping the authored React-hook signature unchanged. v2's
               ;; false comparator deliberately differs from v1's frozen-true
               ;; comparator.
               (act-promise
                #(reset! shell-2
                         (register! id (stateful-body "v2" probe true)
                                    (fn [_ _] false) hs "PreserveView")))))
            (.then
             (fn []
               (testing "same signature and first sub site update in place"
                 (is (identical? shell-1 @shell-2)
                     "public shell identity is stable")
                 (is (identical? (:inner shells-1)
                                 (:inner (reactive/view-shells id)))
                     "the inner component identity is stable too")
                 (is (= 1 (reactive/view-generation id))
                     "body revision advanced")
                 (is (= 0 (reactive/view-remount-generation id))
                     "same signature never changes the inner key")
                 (is (= "v2:p1:7:42"
                        (.-textContent
                         (.querySelector container "[data-hmr-parent]")))
                     "missing-notify / missing-fixed-skeleton mutations fail")
                 (is (= "19" (.-textContent
                              (.querySelector container "[data-hmr-child]"))))
                 (is (identical? (:parent @tokens) (:parent-token @probe)))
                 (is (identical? (:child @tokens) (:child-token @probe)))
                 (is (= 1 (:ref-count (cache-entry)))
                     "the newly added sub is owned after the fresh commit"))
               ;; Remove the sub site again, still without root.render. The
               ;; fixed dev skeleton remains, state remains, and ownership is
               ;; reconciled away rather than leaking the old dependency.
               (act-promise
                #(reset! shell-3
                         (register! id (stateful-body "v3" probe false)
                                    (fn [_ _] false) hs "PreserveView")))))
            (.then
             (fn []
               (testing "same-signature sub removal also updates in place"
                 (is (identical? shell-1 @shell-3))
                 (is (= 2 (reactive/view-generation id)))
                 (is (= 0 (reactive/view-remount-generation id)))
                 (is (= "v3:p1:7"
                        (.-textContent
                         (.querySelector container "[data-hmr-parent]"))))
                 (is (= "19" (.-textContent
                              (.querySelector container "[data-hmr-child]"))))
                 (is (identical? (:parent @tokens) (:parent-token @probe)))
                 (is (identical? (:child @tokens) (:child-token @probe)))
                 (is (nil? (cache-entry))
                     "removing the sub releases the prior ownership")
                 (is (empty? @warnings)
                     "adding/removing a sub site produced no hook-order warning"))
               ;; Parent props now move. The currently published v2 comparator
               ;; (false), not v1's frozen comparator (true), must decide.
               (act-promise
                #(.render root
                          (frames/scope-element
                           fid
                           (array (React/createElement
                                   @shell-3 #js {:value "p2"})))))))
            (.then
             (fn []
               (is (= "v3:p2:7"
                      (.-textContent
                       (.querySelector container "[data-hmr-parent]")))
                   "the stable memo shell delegates to the current comparator")))
            (.then (fn [] (act-promise #(.unmount root))))
            ;; Reports and releases; it never finishes (rf2-fyba). `done` runs the
            ;; whole remainder of the run synchronously, so a `.catch` downstream
            ;; of it claims a later namespace's throw as this row's and fires
            ;; `done` twice. The defensive unmount stays here because the success
            ;; path already unmounted above; the teardown both paths share sits in
            ;; the single trailing step below.
            (.catch
             (fn [e]
               (try (.unmount root) (catch :default _ nil))
               (is false (str "same-signature browser proof rejected: " e))
               nil))
            (.then
             (fn [_]
               (set! (.-error js/console) original-error)
               (.remove container)
               (done))))))))

(defn- effectful-body [version probe events extra-hook?]
  (fn [_props]
    (let [[n set-n] (React/useState 0)]
      (when extra-hook?
        (React/useRef :new-hook-site))
      (React/useLayoutEffect
       (fn []
         (swap! events conj (keyword (str "setup-" version)))
         (fn []
           (swap! events conj (keyword (str "cleanup-" version)))))
       #js [])
      (swap! probe assoc :n n :set-n set-n)
      (React/createElement "output" #js {:data-hmr-effect version}
                           (str version ":" n)))))

(deftest incompatible-signature-keeps-shell-and-remounts-inner-once
  (if-not (browser?)
    (is true ":node — browser gate runs the incompatible-hook proof")
    (async done
      (let [id        ::remount
            probe     (atom {})
            events    (atom [])
            warnings  (atom [])
            shell-2   (atom nil)
            original-error (.-error js/console)
            container (js/document.createElement "div")
            root      (ReactDOMClient/createRoot container)
            shell-1   (register! id (effectful-body "v1" probe events false)
                                 (fn [_ _] true) "hs1-one-hook" "RemountView")
            shells-1  (reactive/view-shells id)]
        (.appendChild (.-body js/document) container)
        (set! (.-error js/console)
              (fn [& xs] (swap! warnings conj (mapv str xs))))
        (-> (act-promise #(.render root (React/createElement shell-1 nil)))
            (.then (fn [] (act-promise #((:set-n @probe) 7))))
            (.then
             (fn []
               (reset! events [])
               (act-promise
                #(reset! shell-2
                         (register! id (effectful-body "v2" probe events true)
                                    (fn [_ _] true) "hs1-two-hooks"
                                    "RemountView")))))
            (.then
             (fn []
               (testing "only the stable inner Fiber remounts"
                 (is (identical? shell-1 @shell-2)
                     "the public shell survives an incompatible edit")
                 (is (identical? (:inner shells-1)
                                 (:inner (reactive/view-shells id)))
                     "hook incompatibility changes the key, never Inner's type")
                 (is (= 1 (reactive/view-remount-generation id)))
                 (is (= [:cleanup-v1 :setup-v2] @events)
                     "one cleanup precedes one setup — always/never-key mutations fail")
                 (is (= "v2:0" (.-textContent
                                 (.querySelector container "[data-hmr-effect]")))
                     "inner local state reset exactly once")
                 (is (empty? @warnings)
                     "the incompatible hook order produced no React warning"))))
            (.then (fn [] (act-promise #(.unmount root))))
            (.catch
             (fn [e]
               (try (.unmount root) (catch :default _ nil))
               (is false (str "incompatible-signature browser proof rejected: " e))
               nil))
            (.then
             (fn [_]
               (set! (.-error js/console) original-error)
               (.remove container)
               (done))))))))

(deftest stale-equal-deps-render-cannot-own-before-fresh-revision
  (if-not (browser?)
    (is true ":node — browser gate runs the render-to-layout revision race")
    (async done
      (let [id        ::stale-render
            fid       :fast-refresh/stale-frame
            hs        "hs1-stale-equal-deps"
            armed     (atom true)
            observed  (atom ::unset)
            container (js/document.createElement "div")
            root      (ReactDOMClient/createRoot container)
            cache-entry #(get @(:sub-cache (frame/frame fid)) [::value])
            render-v2 (fn [_props]
                        (React/createElement
                         "output" #js {:data-hmr-stale "v2"}
                         (str (reactive/sub-read ::value-site [::value]))))
            render-v1 (fn [_props]
                        (let [v (reactive/sub-read ::value-site [::value])]
                          ;; Move the authoritative body revision after capture
                          ;; began but before this render's layout commit.
                          ;; Dependencies remain exactly equal.
                          (when (compare-and-set! armed true false)
                            (register! id render-v2 (fn [_ _] true)
                                       hs "StaleRender"))
                          (React/createElement
                           "output" #js {:data-hmr-stale "v1"} (str v))))
            observer  (fn [_props]
                        (React/useLayoutEffect
                         (fn []
                           ;; Runs after the leaf's layout effect in this commit,
                           ;; before React services the missed-snapshot rerender.
                           (reset! observed (cache-entry))
                           js/undefined)
                         #js [])
                        nil)
            shell     (register! id render-v1 (fn [_ _] true)
                                 hs "StaleRender")]
        (rf/reg-sub ::value (fn [db _] (:value db)))
        (rf/make-frame {:id fid :doc "Fast Refresh stale-capture proof"})
        (frame/replace-app-db! fid {:value 42})
        (.appendChild (.-body js/document) container)
        (-> (act-promise
             #(.render root
                       (frames/scope-element
                        fid
                        (array (React/createElement shell nil)
                               (React/createElement observer nil)))))
            (.then
             (fn []
               (is (nil? @observed)
                   "the stale v1 layout acquired nothing; equal-deps cannot bypass revision")
               (is (= "v2" (.getAttribute
                             (.querySelector container "[data-hmr-stale]")
                             "data-hmr-stale"))
                   "uSES missed-snapshot correction rendered the fresh body")
               (is (= 1 (:ref-count (cache-entry)))
                   "only the fresh revision owns the dependency")))
            (.then (fn [] (act-promise #(.unmount root))))
            (.catch
             (fn [e]
               (try (.unmount root) (catch :default _ nil))
               (is false (str "stale-revision browser proof rejected: " e))
               nil))
            (.then (fn [_] (.remove container) (done))))))))
