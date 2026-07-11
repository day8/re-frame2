(ns re-frame.ui.root-mount-dom-cljs-test
  "S1c client mount smoke — REAL React roots (react-dom/client 19.x)
  against compiled views: mount / idempotent re-mount / create-root +
  render! / unmount! + remount, Layer-3 duplicate + container-ownership
  failures live, frame-root transparency + the preflight seam, the dev
  descriptor on the registry, and the S1 hydrate fail-loud.

  Browser-only bodies — the `-dom-cljs-test$` suffix (rf2-2hrj8) opts
  this file into the `:browser-test` build; `:node-test` /
  `:node-test-ui` still load it and every DOM-mounting test gates on
  `(browser?)` and exits early where `js/document` is absent."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.error :as error]
            [re-frame.ui :as ui :refer [defview frame-root]]
            [re-frame.ui.client :as client]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  {:before client/reset-live-roots!
   :after  client/reset-live-roots!})

(defn- container []
  (js/document.createElement "div"))

(defn- thrown-error [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defview mini-app
  [{:keys [title]}]
  [:div.mini-app [:h1 title]])

(defview other-app
  []
  [:div.other "other content"])

;; ---------------------------------------------------------------------------
;; mount — the guide-01 one-liner + idempotent re-mount
;; ---------------------------------------------------------------------------

(defn- mount-main! [c]
  (ui/mount [mini-app {:title "hello S1c"}] c {:root-id :dom-smoke/main}))

(deftest mount-smoke
  (when (browser?)
    (let [c (container)
          root (react-dom/flushSync #(mount-main! c))]
      (is (some? root))
      (is (= :dom-smoke/main (client/root-id-of root)))
      (is (re-find #"<div class=\"mini-app\"><h1>hello S1c</h1></div>"
                   (.-innerHTML c))
          "the compiled root template renders through the React root")
      (is (contains? (client/live-root-ids) :dom-smoke/main))
      (let [entry (client/live-root-entry :dom-smoke/main)]
        (is (= :authored (:provenance entry)))
        (is (= 1 (get-in entry [:descriptor :rf.root/schema-version]))
            "the dev registry entry carries Root Descriptor v1")
        (is (= :dom-smoke/main (get-in entry [:descriptor :root-id])))
        (is (= ::mini-app (get-in entry [:descriptor :view-id])))
        (is (= :literal (get-in entry [:descriptor :props-shape])))
        (is (= {:title "hello S1c"}
               (get-in entry [:descriptor :static-props])))))))

(deftest mount-is-idempotent-per-root
  (when (browser?)
    (let [c (container)
          r1 (react-dom/flushSync #(mount-main! c))
          r2 (react-dom/flushSync #(mount-main! c))]
      (is (identical? r1 r2)
          "same root-id + same container re-renders the existing Root")
      (is (= 1 (count (client/live-root-ids)))))))

;; ---------------------------------------------------------------------------
;; Layer-3 failures, live
;; ---------------------------------------------------------------------------

(defn- mount-derived! [c]
  (ui/mount [mini-app {:title "derived"}] c))

(deftest duplicate-derived-root-id-fails-loud-live
  (when (browser?)
    (react-dom/flushSync #(mount-derived! (container)))
    (let [{:keys [id msg]} (thrown-error #(mount-derived! (container)))]
      (is (= :rf.error/duplicate-root-id id)
          "same root-id on a DIFFERENT container fails loud (Layer 3)")
      (is (re-find #"add :disambiguator or author :root-id" msg)
          "both-derived diagnostics name the fix"))))

(deftest container-in-use-fails-loud-live
  (when (browser?)
    (let [c (container)]
      (react-dom/flushSync
       #(ui/mount [mini-app {:title "first"}] c {:root-id :dom-inuse/first}))
      (let [{:keys [id data]}
            (thrown-error
             #(ui/create-root c {:root-id :dom-inuse/second}))]
        (is (= :rf.error/root-container-in-use id))
        (is (= :dom-inuse/first (:owner-root-id data)))))))

(deftest nil-container-fails-loud
  (let [{:keys [id]}
        (thrown-error
         #(ui/mount [mini-app {:title "x"}] nil {:root-id :dom-miss/x}))]
    (is (= :rf.error/root-container-missing id))))

;; ---------------------------------------------------------------------------
;; create-root + render!
;; ---------------------------------------------------------------------------

(deftest create-root-then-render
  (when (browser?)
    (let [c (container)
          root (ui/create-root c {:root-id :dom-cr/panel
                                  :identifier-prefix "rf2-custom-"})]
      (is (= "" (.-innerHTML c)) "create-root renders nothing")
      (react-dom/flushSync #(ui/render! root [mini-app {:title "one"}]))
      (is (re-find #"one" (.-innerHTML c)))
      (react-dom/flushSync #(ui/render! root [mini-app {:title "two"}]))
      (is (re-find #"two" (.-innerHTML c)) "render! re-renders")
      (let [entry (client/live-root-entry :dom-cr/panel)]
        (is (= :dom-cr/panel (get-in entry [:descriptor :root-id]))
            "render! completes the descriptor-base with the Root's identity")
        (is (= :authored (get-in entry [:descriptor :root-id-provenance])))))))

;; ---------------------------------------------------------------------------
;; unmount! — total teardown + remount
;; ---------------------------------------------------------------------------

(deftest unmount-tears-down-and-frees-identity
  (when (browser?)
    (let [c (container)
          root (react-dom/flushSync
                #(ui/mount [other-app {}] c {:root-id :dom-un/x}))]
      (is (re-find #"other content" (.-innerHTML c)))
      (ui/unmount! root)
      (is (= "" (.-innerHTML c)) "total teardown")
      (is (not (contains? (client/live-root-ids) :dom-un/x))
          "the root-id is unregistered")
      (is (nil? (ui/unmount! root)) "idempotent")
      (let [root2 (react-dom/flushSync
                   #(ui/mount [other-app {}] c {:root-id :dom-un/x}))]
        (is (re-find #"other content" (.-innerHTML c))
            "identity + container free for a fresh mount")
        (ui/unmount! root2)))))

;; ---------------------------------------------------------------------------
;; frame-root: transparent render + the preflight seam
;; ---------------------------------------------------------------------------

(defn- mount-framed! [c n]
  (ui/mount [frame-root {:id :dom-frames/session :initial-events [[:boot n]]}
             [mini-app {:title "framed"}]]
            c
            {:root-id :dom-frames/root}))

(deftest frame-root-renders-transparently-and-plans-ride-preflight
  (when (browser?)
    (let [seen (atom nil)]
      (client/set-preflight-hook! #(reset! seen %))
      (try
        (react-dom/flushSync #(mount-framed! (container) 42))
        (let [[plan :as plans] @seen]
          (is (= 1 (count plans)))
          (is (= :dom-frames/session (:frame-id plan)))
          (is (= {:initial-events [[:boot 42]]} (:config plan))
              "config expressions evaluate at preflight, with site locals")
          (is (re-find #"^cf1-[0-9a-f]{16}$" (:config-fingerprint plan))))
        (let [entry (client/live-root-entry :dom-frames/root)]
          (is (= [:dom-frames/session]
                 (mapv :frame-id (get-in entry [:descriptor :frame-plans])))
              "the descriptor carries the static plan subset"))
        (finally
          (client/set-preflight-hook! nil))))))

(deftest frame-root-config-never-evaluates-without-a-hook
  (when (browser?)
    (let [evals (atom 0)
          bump! (fn [] (swap! evals inc))]
      (react-dom/flushSync
       #(ui/mount [frame-root {:id :dom-lazy/f :on-boot (bump!)}
                   [mini-app {:title "lazy"}]]
                  (container)
                  {:root-id :dom-lazy/root}))
      (is (zero? @evals)
          "no preflight hook (all of S1) -> plan config expressions never run"))))

;; ---------------------------------------------------------------------------
;; hydrate-root: S1 fails loud (manifests land S5)
;; ---------------------------------------------------------------------------

(deftest hydrate-root-fails-loud-at-s1
  (when (browser?)
    (let [{:keys [id msg]}
          (thrown-error #(ui/hydrate-root (container)
                                          [mini-app {:title "h"}]))]
      (is (= :rf.error/root-manifest-invalid id))
      (is (error/message-has-id-token? msg)))))
