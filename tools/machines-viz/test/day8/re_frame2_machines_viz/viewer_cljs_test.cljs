(ns day8.re-frame2-machines-viz.viewer-cljs-test
  "The read-only viewer's pure layer: `decode-location` (URL → view-model)
  and `viewer-view` (view-model → hiccup). The DOM mount (`run`) is
  browser-only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [day8.re-frame2-machines-viz.share :as share]
            [day8.re-frame2-machines-viz.viewer :as viewer]))

(def ^:private host "https://x/viewer.html")

(defn- envelope->url
  "A share-URL carrying `envelope` verbatim — the encoder refuses a malformed
  definition, so the decode-side refusal needs a hand-built URL."
  [envelope]
  (str host "#machine="
       (-> (js/btoa (js/unescape (js/encodeURIComponent
                                   (transit/write (transit/writer :json) envelope))))
           (str/replace "+" "-")
           (str/replace "/" "_")
           (str/replace "=" ""))))

(def chart-state
  {:machine-id :auth/login-flow
   :frame-id   :app/main
   :definition {:initial :idle
                :states  {:idle    {:on {:start :loading}}
                          :loading {:on {:ok :success}}
                          :success {:final? true}}}
   :snapshot   {:state :loading}})

(deftest decode-location-maps-each-url-to-its-view-model
  (testing "a valid share-URL → read-only MachineChart props; a bare URL →
            :empty; a malformed definition (a non-keyword :initial) fails
            closed to :error with no props"
    (doseq [[url expected]
            [[(share/encode-share-url chart-state {:host host})
              {:status :ok
               :props  {:machine-id    :auth/login-flow
                        :definition    (:definition chart-state)
                        :current-state :loading
                        :read-only?    true}}]
             [host
              {:status :empty}]
             [(envelope->url
                {:rf.machines-viz.share/v       "2"
                 :rf.machines-viz.share/chart   {:machine-id :demo
                                                 :definition {:initial "idle"
                                                              :states  {:idle {}}}}
                 :rf.machines-viz.share/created 0})
              {:status :error :reason :invalid-chart-state}]]]
      (is (= expected (dissoc (viewer/decode-location url) :message)) url))))

(defn- child-testid
  "The `data-testid` of the child component a `[:div attrs [child & args]]`
  branch wraps, read by rendering that child to hiccup — no mount."
  [view]
  (let [[child & args] (nth view 2)]
    (get-in (apply child args) [1 :data-testid])))

(deftest viewer-view-dispatches-on-status
  (testing ":ok renders a [chart-view props] component; :error and :empty
            wrap different children, told apart by testid"
    (let [ok-view (viewer/viewer-view {:status :ok :props (dissoc chart-state :frame-id)})]
      (is (vector? ok-view))
      (is (fn? (first ok-view))))
    (is (= "rf-mv-viewer-error"
           (child-testid (viewer/viewer-view {:status :error :reason :malformed-fragment :message "boom"}))))
    (is (= "rf-mv-viewer-empty"
           (child-testid (viewer/viewer-view {:status :empty}))))))
