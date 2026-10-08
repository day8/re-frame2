(ns day8.re-frame2-xray.settings.auto-open-watcher-activates-ratom-node-cljs-test
  "`install-auto-open-watcher!` must put its `:rf.xray/issues-ribbon`
  subscription on the substrate's push path itself. On the ratom family
  that subscription is a bare Reaction, which learns its sources only
  through a capture context; derefed outside one, the installed
  `add-watch` can never fire. The ribbon is a signal no component renders,
  so nothing else supplies that context, and the plain-atom adapter the
  rest of the suite uses cannot show the defect. These rows install
  reagent-slim and drive a real app-db write through the real watch.
  Template: `re-frame.observation-port-activates-ratom-node-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:adapter    rf.adapter.reagent-slim/adapter
     :tier       :runtime
     :post-reset effects/detach-auto-open-watcher!}))

;; `:rf.xray/sync-epoch-history` writes the history slot AND focuses the head
;; epoch, so one dispatch is an ordinary app-db write of the issues feed.

(defn- quiet-epoch [epoch-id]
  {:epoch-id     epoch-id
   :trace-events [{:id 0 :time 0 :op-type :rf.event :operation :app/anything}]})

(defn- error-epoch [epoch-id]
  {:epoch-id     epoch-id
   :trace-events [{:id        1
                   :time      0
                   :op-type   :error
                   :operation :rf.error/handler-exception
                   :tags      {:reason "boom"}}]})

(defn- sync-history! [history]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-epoch-history history])))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- with-hidden-shell-exports
  "Stand the browser API exports the preload installs, with `status`
  reporting a HIDDEN shell and each reopen export recording its own name.
  Calls `(f invoked-atom)`."
  [f]
  (let [invoked     (atom [])
        had-window? (exists? js/globalThis.window)
        record      (fn [nm] (fn [] (swap! invoked conj nm) nil))]
    (when-not had-window?
      (set! (.-window js/globalThis) #js {}))
    (let [win js/globalThis.window]
      (set! (.-day8 win)
            #js {"re_frame2_xray"
                 #js {"open_BANG_"         (record "open_BANG_")
                      "open_overlay_BANG_" (record "open_overlay_BANG_")
                      "toggle_BANG_"       (record "toggle_BANG_")
                      "status"             (fn [] #js {"visible?" false})}})
      (try
        (f invoked)
        (finally
          (js-delete win "day8")
          (when-not had-window?
            (js-delete js/globalThis "window")))))))

(defn- install-watcher!
  "Install the watcher and return the reaction it watches."
  []
  (effects/detach-auto-open-watcher!)
  (effects/install-auto-open-watcher!)
  @@#'effects/auto-open-watcher)

(deftest auto-open-on-error-fires-through-the-real-watch-on-a-ratom-substrate
  (testing "on the empty → non-empty issue edge, with the toggle on and the
            shell hidden, a real app-db write reaches the watch and Xray reopens"
    (setup!)
    (config/update-setting! :general :auto-open-on-error? true)
    ;; A baseline with no issues, so the next write is a genuine edge.
    (sync-history! [(quiet-epoch 1)])
    (with-hidden-shell-exports
      (fn [invoked]
        (install-watcher!)
        (sync-history! [(quiet-epoch 1) (error-epoch 2)])
        (ratom/flush!)
        (is (= ["toggle_BANG_"] @invoked)
            "reopened once, through the surface-preserving `toggle!` route")
        (sync-history! [(quiet-epoch 1) (error-epoch 2) (error-epoch 3)])
        (ratom/flush!)
        (is (= ["toggle_BANG_"] @invoked)
            "a non-empty → non-empty push is not the edge")))))

(deftest the-toggle-still-gates-the-now-live-channel
  (testing "with `:auto-open-on-error?` OFF the same write reaches the same
            live watch and nothing opens"
    (setup!)
    (config/update-setting! :general :auto-open-on-error? false)
    (sync-history! [(quiet-epoch 1)])
    (with-hidden-shell-exports
      (fn [invoked]
        (let [reaction (install-watcher!)]
          (is (some? (.-watching reaction))
              "precondition — the channel is live, so the silence below is a
               gate and not a dead watch")
          (sync-history! [(quiet-epoch 1) (error-epoch 2)])
          (ratom/flush!)
          (is (empty? @invoked)))))))
