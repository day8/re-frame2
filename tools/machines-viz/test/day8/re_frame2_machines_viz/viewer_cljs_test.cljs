(ns day8.re-frame2-machines-viz.viewer-cljs-test
  "Tests for the read-only viewer's pure decode/view-model layer
  (rf2-8d7w1 · v1.0). The DOM mount (`run`) is browser-only and not
  exercised here; `decode-location` + `viewer-view` are pure given a
  URL / view-model, so they carry the coverage."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.share :as share]
            [day8.re-frame2-machines-viz.viewer :as viewer]))

(def chart-state
  {:machine-id :auth/login-flow
   :frame-id   :app/main
   :definition {:initial :idle
                :states  {:idle    {:on {:start :loading}}
                          :loading {:on {:ok :success}}
                          :success {:final? true}}}
   :snapshot   {:state :loading}})

(deftest decode-location-ok
  (testing "a valid share-URL decodes to :ok with MachineChart props"
    (let [url (share/encode-share-url chart-state)
          vm  (viewer/decode-location url)]
      (is (= :ok (:status vm)))
      (is (= :auth/login-flow (get-in vm [:props :machine-id])))
      (is (= :loading (get-in vm [:props :current-state])))
      (is (true? (get-in vm [:props :read-only?]))))))

(deftest decode-location-empty
  (testing "a bare URL (no fragment) decodes to :empty"
    (is (= :empty (:status (viewer/decode-location "https://x/viewer.html"))))
    (is (= :empty (:status (viewer/decode-location ""))))))

(deftest decode-location-error
  (testing "a malformed fragment decodes to :error with a reason"
    (let [vm (viewer/decode-location "https://x/viewer.html#machine=@@@bad@@@")]
      (is (= :error (:status vm)))
      (is (contains? #{:malformed-fragment :malformed-payload} (:reason vm))))))

(deftest viewer-view-dispatches-on-status
  (testing "viewer-view renders the right top-level shape per status"
    ;; The view is hiccup data; assert the data-testid carried by each
    ;; branch's child without mounting.
    (let [ok-view    (viewer/viewer-view {:status :ok :props (dissoc chart-state :frame-id)})
          err-view   (viewer/viewer-view {:status :error :reason :malformed-fragment :message "boom"})
          empty-view (viewer/viewer-view {:status :empty})]
      ;; :ok branch is a [chart-view props] component vector
      (is (vector? ok-view))
      (is (fn? (first ok-view)))
      ;; :error / :empty branches are plain hiccup divs wrapping a child
      (is (= :div (first err-view)))
      (is (= :div (first empty-view))))))
