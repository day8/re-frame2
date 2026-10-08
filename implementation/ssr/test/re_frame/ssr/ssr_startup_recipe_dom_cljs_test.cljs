(ns re-frame.ssr.ssr-startup-recipe-dom-cljs-test
  "Browser proof of the canonical SSR startup recipe
  (`examples/capabilities/ssr`): with a `__rf_payload` the first render
  HYDRATES — adopting the server DOM node and wiring its handlers — and without
  one it mounts a fresh root. Both run the recipe's own mount helper
  `ssr.mount/mount!`, required instead of `ssr.core`, whose app registrations
  collide with other examples in the shared `:node-test` bundle.

  The view is a plain component on `:rf/default`, so the adopt-vs-replace
  signal is read through the mount branch alone, with no view annotations.
  `:node-test` loads this file too and every test exits early without
  `js/document`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [ssr.mount :as mount]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.test-support :as rf.test-support]))

;; Registered at ns-load so the reset-runtime fixture keeps them in its baseline.
(def ^:private app-frame :rf/default)

(rf/reg-event ::toggle
  (fn [{:keys [db]} _] {:db (update db :show? (fnil not true))}))

(rf/reg-sub ::show?
  (fn [db _] (let [v (:show? db)] (if (nil? v) true v))))

;; A plain component has no frame context of its own, so it names the frame.
(defn- recipe-view []
  [:div.page
   [:button.toggle-bodies
    {:data-testid "toggle-bodies"
     :on-click    #(rf/dispatch [::toggle] {:frame app-frame})}
    (if @(rf/subscribe [::show?] {:frame app-frame}) "Hide bodies" "Show bodies")]])

;; What a server emits for `recipe-view` on the default db; it byte-matches the
;; client render, so `hydrate-root` adopts it with no mismatch.
(def ^:private server-markup
  (str "<div class=\"page\">"
       "<button class=\"toggle-bodies\" data-testid=\"toggle-bodies\">Hide bodies</button>"
       "</div>"))

(defn- run-recipe!
  "The client boot as `ssr.core/run` performs it: seat the adapter, make the
  frame, `ssr/hydrate!`, then `mount!` through `handle`. Returns the payload
  `hydrate!` applied (nil on a client-only load)."
  [handle]
  (rf/init! rf.adapter.reagent/adapter)
  (rf/make-frame {:id app-frame :platform :client})
  (let [el      (and (exists? js/document) (js/document.getElementById "app"))
        payload (rf.ssr/hydrate! {:frame app-frame :render-tree-fn (fn [] [recipe-view])})
        tree    [rf/frame-provider {:frame app-frame} [recipe-view]]]
    (mount/mount! handle el tree payload)
    payload))

;; Map form, because the tests are async; no ambient frame, because the recipe
;; makes its own `:rf/default`.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- clear-stale-apps!
  "Remove leftover `#app` elements: the recipe reads the FIRST one in the document."
  []
  (doseq [el (array-seq (.querySelectorAll js/document "#app"))]
    (when-let [p (.-parentNode el)] (.removeChild p el))))

;; Other browser suites leave `IS_REACT_ACT_ENVIRONMENT` on, which defers renders
;; outside `act`; the recipe commits on React's own schedule, so turn it off.
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(defn- restore-act-env! [prev]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))

(defn- plant-host!
  "Attach `<div id=\"app\">inner-html</div>`, plus a `__rf_payload` script
  seeding an empty app-db when `payload?`. Returns the wrapper host."
  [{:keys [inner-html payload?]}]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host)
          (str "<div id=\"app\">" inner-html "</div>"
               (when payload?
                 (str "<script id=\"" rf.ssr.constants/payload-script-id
                      "\" type=\"application/edn\">{:rf/version 1 :rf/app-db {}}</script>"))))
    (.appendChild (.-body js/document) host)
    host))

(defn- teardown! [host handle act-prev]
  (try (rf.adapter.reagent/unmount! handle) (catch :default _ nil))
  (when-let [p (.-parentNode host)] (.removeChild p host))
  (restore-act-env! act-prev))

(deftest payload-backed-startup-adopts-server-dom-and-activates-handlers
  (testing "the server `<button>` node is adopted, not re-created, and
            clicking it fires the wired `:on-click`"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (clear-stale-apps!)
        (let [act-prev   (disable-act-env!)
              handle     (rf.adapter.reagent/client-root)
              host       (plant-host! {:inner-html server-markup :payload? true})
              app-el     (.getElementById js/document "app")
              server-btn (.querySelector app-el "button.toggle-bodies")]
          ;; `identical?` below is vacuous on two nils.
          (is (some? server-btn))
          (run-recipe! handle)
          ;; A create-root render commits asynchronously, so the identity
          ;; check only discriminates after a yield.
          (js/setTimeout
            (fn []
              (is (identical? server-btn (.querySelector app-el "button.toggle-bodies"))
                  (str "the mounted button is the server node; client-html="
                       (pr-str (.-innerHTML app-el))))
              (.click server-btn)
              ;; `dispatch` is async and the re-render commits on React's schedule.
              (js/setTimeout
                (fn []
                  (is (= false (:show? (rf/app-db-value app-frame)))
                      "clicking the adopted node ran ::toggle")
                  (is (= "Show bodies" (.-textContent server-btn))
                      "the re-render updated the adopted node in place")
                  (teardown! host handle act-prev)
                  (done))
                50))
            50))))))

(deftest client-only-startup-is-a-fresh-root
  (testing "with no payload the recipe mounts a fresh root, discarding what
            the container held"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (clear-stale-apps!)
        (let [act-prev  (disable-act-env!)
              handle    (rf.adapter.reagent/client-root)
              host      (plant-host!
                         {:inner-html "<div data-testid=\"stale-sentinel\">stale</div>"
                          :payload? false})
              app-el    (.getElementById js/document "app")
              sentinel  (.querySelector app-el "[data-testid=\"stale-sentinel\"]")]
          (is (nil? (run-recipe! handle)) "no payload was read")
          (js/setTimeout
            (fn []
              (is (not (.-isConnected sentinel))
                  "the container's previous content was discarded")
              (is (some? (.querySelector app-el "button.toggle-bodies"))
                  "the app mounted fresh into the container")
              (teardown! host handle act-prev)
              (done))
            50))))))
