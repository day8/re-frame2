(ns day8.re-frame2-machines-viz.viewer-cljs-test
  "Tests for the read-only viewer's pure decode/view-model layer
  (rf2-8d7w1 · v1.0). The DOM mount (`run`) is browser-only and not
  exercised here; `decode-location` + `viewer-view` are pure given a
  URL / view-model, so they carry the coverage."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [day8.re-frame2-machines-viz.share :as share]
            [day8.re-frame2-machines-viz.viewer :as viewer]))

;; Forge a raw share-URL fragment from an arbitrary envelope (the public
;; encoder would refuse a malformed definition, so decode-side fail-closed
;; behaviour is exercised through a hand-crafted URL). Mirrors the encoder's
;; transit-json → base64url step.
(defn- envelope->url
  [envelope]
  (let [transit-str (transit/write (transit/writer :json) envelope)
        b64 (-> (js/btoa (js/unescape (js/encodeURIComponent transit-str)))
                (str/replace "+" "-")
                (str/replace "/" "_")
                (str/replace "=" ""))]
    (str "https://x/viewer.html#machine=" b64)))

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
    (let [url (share/encode-share-url chart-state {:host "https://x/viewer.html"})
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

(deftest decode-location-rejects-malformed-definition
  (testing "rf2-3fc89f.18 — a share-URL carrying a MALFORMED machine definition
            (a non-keyword flat :initial the canonical grammar gate rejects)
            decodes to :status :error (fail-closed) and never yields
            MachineChart props"
    (let [url (envelope->url
                {:rf.machines-viz.share/v       "2"
                 :rf.machines-viz.share/chart   {:machine-id :demo
                                                 :definition {:initial "idle"
                                                              :states  {:idle {}}}}
                 :rf.machines-viz.share/created 0})
          vm  (viewer/decode-location url)]
      (is (= :error (:status vm))
          "a malformed definition must not reach the :ok / MachineChart path")
      (is (= :invalid-chart-state (:reason vm)))
      (is (nil? (:props vm)) "no MachineChart props are produced for a malformed definition")))
  (testing "rf2-3fc89f.18 — a malformed PARALLEL region body (no keyword
            :initial, empty :states) is likewise rejected at the viewer boundary"
    (let [url (envelope->url
                {:rf.machines-viz.share/v       "2"
                 :rf.machines-viz.share/chart   {:machine-id :demo
                                                 :definition {:type    :parallel
                                                              :regions {:main {:states {}}}}}
                 :rf.machines-viz.share/created 0})
          vm  (viewer/decode-location url)]
      (is (= :error (:status vm)))
      (is (= :invalid-chart-state (:reason vm)))
      (is (nil? (:props vm))))))

(deftest decode-location-rejects-recursively-malformed-definition
  (testing "rf2-j538f7.18 — a share-URL carrying a RECURSIVELY-malformed machine
            definition (structurally invalid BELOW the root: a nested compound
            missing :initial, a dangling transition target, or an unknown bare
            node key) also fails closed at the viewer boundary — :status :error,
            :reason :invalid-chart-state, and NO MachineChart props. The pre-fix
            shallow gate blessed these (root shape OK); the recursive gate now
            rejects them."
    (doseq [[label definition]
            {:nested-compound-no-initial {:initial :outer :states {:outer {:states {:inner {}}}}}
             :dangling-target            {:initial :idle :states {:idle {:on {:go :missing}}}}
             :unknown-node-key           {:initial :idle :states {:idle {:on-entry :oops}}}}]
      (let [url (envelope->url
                  {:rf.machines-viz.share/v       "2"
                   :rf.machines-viz.share/chart   {:machine-id :demo :definition definition}
                   :rf.machines-viz.share/created 0})
            vm  (viewer/decode-location url)]
        (is (= :error (:status vm))
            (str "recursively-malformed " label " must not reach the MachineChart path"))
        (is (= :invalid-chart-state (:reason vm)) (str label))
        (is (nil? (:props vm))
            (str "no MachineChart props are produced for recursively-malformed " label))))))

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
