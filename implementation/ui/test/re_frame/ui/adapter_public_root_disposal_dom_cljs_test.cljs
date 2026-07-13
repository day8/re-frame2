(ns re-frame.ui.adapter-public-root-disposal-dom-cljs-test
  "Browser proof that the first-party adapter owns public compiled Roots.

  The generic React spine has a separate root registry, so this fixture must
  stay on the public `ui/mount` path: destroying `ui/adapter` drains every
  compiled Root, its ViewCells and observation owners, prevents late renders,
  and permits the same root-id/container to be mounted again. The failure arm
  pins total cleanup when the exact real Root's host unmount seam throws."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]))

(defn- browser? [] (exists? js/document))

(use-fixtures
  :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame :rf/default :async? true}))

(defn- act-promise
  "Direct React 19 act boundary with an explicit rejection channel."
  [thunk]
  (try
    (js/Promise.resolve
     (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- unexpected!
  [done label e]
  (is false (str label ": " e))
  (done))

(defview observed-root [{:keys [label renders]}]
  (let [_ (swap! renders inc)]
    [:output {:data-root label}
     label ":" (ui/sub [::adapter-value])]))

(defview admission-probe []
  [:span {:data-admission "open"} "admitted"])

(defn- mount-phase-two-root! [container]
  (ui/mount [admission-probe] container
            {:root-id :adapter-public/phase-two-attempt}))

(defn- mount-post-destroy-root! [container]
  (ui/mount [admission-probe] container
            {:root-id :adapter-public/post-destroy-mount}))

(defn- phase-two-cleanup-probe [props]
  (let [target (unchecked-get props "target")
        errors (unchecked-get props "errors")]
    (React/useEffect
     (fn []
       (fn []
         (try
           (mount-phase-two-root! target)
           (catch :default e
             (swap! errors conj e)))))
     #js [])
    (React/createElement "span" nil "generic-spine-owner")))

(defn- mount-observed!
  [container root-id label renders]
  (case root-id
    :adapter-public/a
    (ui/mount [observed-root {:label label :renders renders}]
              container {:root-id :adapter-public/a})

    :adapter-public/b
    (ui/mount [observed-root {:label label :renders renders}]
              container {:root-id :adapter-public/b})))

(deftest adapter-destroy-drains-all-public-compiled-roots
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 1})
      (let [a          (js/document.createElement "div")
            b          (js/document.createElement "div")
            renders    (atom 0)
            cell-base  (reactive/root-cell-count)
            frame-cell (frame/frame-state-container :rf/default)]
        (-> (act-promise
             #(do (mount-observed! a :adapter-public/a "a" renders)
                  (mount-observed! b :adapter-public/b "b" renders)))
            (.then
             (fn []
               (is (= #{:adapter-public/a :adapter-public/b}
                      (client/live-root-ids)))
               (is (= ["a:1" "b:1"] [(.-textContent a) (.-textContent b)]))
               (is (> (reactive/root-cell-count) cell-base))
               (is (some? (get @(:sub-cache (frame/frame :rf/default))
                               [::adapter-value])))
               (act-promise rf/destroy-adapter!)))
            (.then
             (fn []
               (let [settled-renders @renders]
                 (is (= #{} (client/live-root-ids)))
                 (is (= ["" ""] [(.-textContent a) (.-textContent b)]))
                 (is (= cell-base (reactive/root-cell-count)))
                 (is (nil? (get @(:sub-cache (frame/frame :rf/default))
                                [::adapter-value])))
                 ;; Mutate the retained SOURCE atom directly: no disposed
                 ;; observation or queued ViewCell may render after adapter
                 ;; destruction. Going through the adapter is deliberately
                 ;; impossible after destroy, and :app-db is a read-only
                 ;; derived value rather than this physical source.
                 (reset! frame-cell
                         (assoc @frame-cell :rf.db/app {:value 99}))
                 (-> (js/Promise.resolve nil)
                     (.then (fn []
                              (is (= settled-renders @renders))
                              (is (= ["" ""]
                                     [(.-textContent a) (.-textContent b)]))))))))
            (.then
             (fn []
               (rf/init! ui/adapter)
               (act-promise
                #(do (mount-observed! a :adapter-public/a "a2" renders)
                     (mount-observed! b :adapter-public/b "b2" renders)))))
            (.then
             (fn []
               (is (= ["a2:99" "b2:99"]
                      [(.-textContent a) (.-textContent b)]))
               (is (= #{:adapter-public/a :adapter-public/b}
                      (client/live-root-ids)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "public-root adapter disposal rejected" e))))))))

(deftest throwing-public-root-cleanup-is-total-and-container-is-reusable
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 7})
      (let [container (js/document.createElement "div")
            cleanup   (js/Error. "public root cleanup failed")
            cell-base (reactive/root-cell-count)
            root-cell (atom nil)]
        (-> (act-promise
             #(reset! root-cell
                      (ui/mount [observed-root
                                 {:label "owned" :renders (atom 0)}]
                                container
                                {:root-id :adapter-public/throwing})))
            (.then
             (fn []
               ;; Keep the public Root, container, ViewCell and React marker
               ;; real; replace only this exact host handle's unmount seam so
               ;; the adapter's synchronous failure fallback is deterministic.
               ;; Exercise :dispose-adapter! directly to avoid taking a
               ;; position on the separately frozen core breadcrumb semantics.
               (let [^client/Root root @root-cell]
                 (set! (.-unmount (.-react-root root))
                       (fn [] (throw cleanup))))
               (try
                 ((:dispose-adapter! ui/adapter))
                 (throw (js/Error. "throwing cleanup unexpectedly resolved"))
                 (catch :default e
                   (is (identical? cleanup e)
                       "the host cleanup error remains observable")
                   nil))))
            (.then
             (fn []
               (is (= #{} (client/live-root-ids)))
               (is (= "" (.-textContent container))
                   "the adapter fallback empties a consumed host container")
               (is (= cell-base (reactive/root-cell-count)))
               (is (not-any? #(str/starts-with? % "__reactContainer$")
                             (array-seq
                              (js/Object.getOwnPropertyNames container)))
                   "the pinned React ownership marker is cleared for re-use")
               (act-promise
                #(ui/mount [observed-root {:label "fresh" :renders (atom 0)}]
                           container {:root-id :adapter-public/throwing}))))
            (.then
             (fn []
               (is (str/starts-with? (.-textContent container) "fresh:")
                   "the same root-id and container can mount a fresh generation")
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "throwing-root recovery rejected" e))))))))

(deftest adapter-destroy-closes-root-admission-through-the-generic-spine-tail
  (when (browser?)
    (async done
      (let [generic-container (js/document.createElement "div")
            attempted-root    (js/document.createElement "div")
            post-destroy      (js/document.createElement "div")
            cleanup-errors    (atom [])]
        (-> (act-promise
             #((:render ui/adapter)
               (React/createElement
                phase-two-cleanup-probe
                #js {:target attempted-root :errors cleanup-errors})
               generic-container
               {}))
            (.then
             (fn []
               (is (= "generic-spine-owner"
                      (.-textContent generic-container)))
               ;; The component's effect cleanup runs in phase two: AFTER the
               ;; public-root snapshot drained, while the generic spine root
               ;; unmounts. Its attempted public mount must still be fenced.
               (act-promise rf/destroy-adapter!)))
            (.then
             (fn []
               (let [cleanup-error (first @cleanup-errors)]
                 (is (= 1 (count @cleanup-errors))
                     "the generic-spine cleanup attempted exactly one public remount")
                 (is (= :rf.error/adapter-disposed
                        (:rf.error/id (ex-data cleanup-error)))
                     "phase-two root creation sees terminal adapter disposal")
                 (is (= :install-a-fresh-adapter
                        (:recovery (ex-data cleanup-error)))))
               (is (= "" (.-textContent attempted-root)))
               (is (= #{} (client/live-root-ids)))

               ;; The local drain fence has reopened, but the process-level
               ;; terminal breadcrumb must keep BOTH public creation spellings
               ;; closed until a fresh adapter generation installs.
               (let [create-error
                     (try
                       (ui/create-root post-destroy
                                       {:root-id :adapter-public/post-destroy-create})
                       nil
                       (catch :default e e))
                     mount-error
                     (try
                       (mount-post-destroy-root! post-destroy)
                       nil
                       (catch :default e e))]
                 (is (= [:rf.error/adapter-disposed
                         :rf.error/adapter-disposed]
                        (mapv #(-> % ex-data :rf.error/id)
                              [create-error mount-error])))
                 (is (every? #(= :install-a-fresh-adapter
                                  (:recovery (ex-data %)))
                             [create-error mount-error])))
               (is (= "" (.-textContent post-destroy)))
               (is (= #{} (client/live-root-ids)))

               (rf/init! ui/adapter)
               (act-promise #(mount-post-destroy-root! post-destroy))))
            (.then
             (fn []
               (is (= "admitted" (.-textContent post-destroy))
                   "a fresh generation atomically reopens public root admission")
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "root-admission lifecycle rejected" e))))))))
