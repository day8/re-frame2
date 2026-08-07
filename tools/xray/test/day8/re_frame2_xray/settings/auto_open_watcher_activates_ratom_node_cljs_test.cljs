(ns day8.re-frame2-xray.settings.auto-open-watcher-activates-ratom-node-cljs-test
  "rf2-lynzk — `install-auto-open-watcher!` puts its `:rf.xray/issues-ribbon`
  subscription on the substrate's PUSH path ITSELF, so auto-open-on-error
  actually fires on the ratom family.

  THE DEFECT THIS PINS. The installer did `subscribe` → plain deref →
  `add-watch`, on the stated premise that \"a reagent/re-frame reaction is
  already live the instant `subscribe` returns\". On the ratom family that is
  false, and silently so: the subscription IS a bare Reaction, built WITHOUT
  `:auto-run`, and a Reaction learns its sources only through `deref-capture`.
  A plain deref taken outside `*ratom-context*` runs the body raw and leaves
  `watching` nil — the node is in nobody's watcher set, so the installed
  `add-watch` records a callback that CANNOT fire. Xray never auto-opened on
  the first error under Reagent / reagent-slim, and nothing said so.

  WHY NO SUITE SAW IT. `:rf.xray/issues-ribbon` is a SIGNAL, never a rendered
  value (`panels.cljs` §Issues: there is no Issues tab), so no component render
  ever supplies it a capture context — the one thing that hides this defect
  everywhere else. The sibling suite (`settings.effects-cljs-test`) runs the
  headless plain-atom adapter, whose derived subscriptions are not `IWatchable`
  at all, and drives the watch fn DIRECTLY. Calling the watch fn by hand is
  precisely what cannot see this: everything looks correct until a CHANGE has
  to propagate.

  So this file installs a ratom-family adapter (reagent-slim — the adapter
  Xray's own `deps.edn` declares) and drives the whole production path: the
  real `install-auto-open-watcher!`, the real `add-watch` on the real reaction,
  and a real app-db write that must reach it.

  Node, no DOM: the claim is about the notification channel, not about a
  render. Template: `re-frame.observation-port-activates-ratom-node-cljs-test`,
  the same proof for the observation port (rf2-8cnxg)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; A RATOM-family adapter, deliberately — the plain-atom default the rest of
  ;; the Xray suite uses is exactly the substrate on which this defect is
  ;; invisible. `:runtime` clears the persisted settings so the
  ;; `:auto-open-on-error?` flips below start from the shipped default.
  (xray-test-support/make-xray-runtime-fixture
    {:adapter    reagent-slim-adapter/adapter
     :tier       :runtime
     :post-reset (fn [] (effects/detach-auto-open-watcher!))}))

;; ---- the issues feed, driven through app-db ------------------------------
;;
;; `:rf.xray/issues-ribbon` joins `:rf.xray/focus` against
;; `:rf.xray/epoch-history` and projects the focused epoch's `:trace-events`
;; into the issue subset. `:rf.xray/sync-epoch-history` writes the history
;; slot AND focuses the head epoch, so one dispatch is a complete, ordinary
;; app-db write of the kind the production feed makes.

(defn- quiet-epoch
  "An epoch whose trace carries no issue — the ribbon reads `:issues []`."
  [epoch-id]
  {:epoch-id     epoch-id
   :trace-events [{:id 0 :time 0 :op-type :rf.event :operation :app/anything}]})

(defn- error-epoch
  "An epoch carrying one `:error` trace event — the ribbon reads one issue."
  [epoch-id]
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

;; ---- the mount surface the watcher reopens through -----------------------

(defn- with-hidden-shell-exports
  "Stand the browser API exports the preload installs, with `status`
  reporting a HIDDEN shell (the auto-open precondition) and each reopen
  export recording its own name. Calls `(f invoked-atom)`."
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

(defn- install-watcher! []
  (effects/detach-auto-open-watcher!)
  (effects/install-auto-open-watcher!)
  ;; The reaction the installer actually watched — the object under test.
  @@#'effects/auto-open-watcher)

;; ===========================================================================

(deftest auto-open-on-error-fires-through-the-real-watch-on-a-ratom-substrate
  (testing "the installed watcher hears a real app-db write: on the
            empty → non-empty issue edge, with the toggle on and the shell
            hidden, Xray reopens. Before rf2-lynzk the watch was held on a
            node that could not notify, so this never happened at all"
    (setup!)
    (config/update-setting! :general :auto-open-on-error? true)
    ;; Baseline — a focused epoch that carries NO issues, so the installer
    ;; seeds `last-issue-count` at 0 and the next write is a genuine edge.
    (sync-history! [(quiet-epoch 1)])
    (with-hidden-shell-exports
      (fn [invoked]
        (let [reaction (install-watcher!)]
          (is (some? reaction)
              "precondition — the watcher installed and holds the reaction")
          (is (satisfies? IWatchable reaction)
              "precondition — the node IS watchable, so a silent channel here
               is the installer's fault and not the host's")
          (is (some? (.-watching reaction))
              "the install ACTIVATED the node: it is subscribed to its
               sources. Before rf2-lynzk this was nil — watchable, watched,
               and unable to notify")
          (is (empty? @invoked)
              "activation itself reopened nothing")

          ;; The edge, driven the way production drives it.
          (sync-history! [(quiet-epoch 1) (error-epoch 2)])
          (ratom/flush!)

          (is (= ["toggle_BANG_"] @invoked)
              "the write reached the watch and Xray reopened — through the
               surface-preserving `toggle!` route (rf2-kggzi4), once")

          ;; …and the edge is still an EDGE on the now-live channel.
          (sync-history! [(quiet-epoch 1) (error-epoch 2) (error-epoch 3)])
          (ratom/flush!)

          (is (= ["toggle_BANG_"] @invoked)
              "a second, non-empty → non-empty push is not the edge: the
               live channel did not reopen again"))))))

(deftest the-toggle-still-gates-the-now-live-channel
  (testing "activation puts the node on the push path; it must not make the
            reopen unconditional. With `:auto-open-on-error?` OFF the same
            write reaches the same live watch and nothing opens"
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
          (is (empty? @invoked)
              "toggle off → no reopen, even on the edge"))))))
