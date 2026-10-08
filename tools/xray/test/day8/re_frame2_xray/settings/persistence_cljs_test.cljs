(ns day8.re-frame2-xray.settings.persistence-cljs-test
  "The Settings localStorage layer. `update-setting!` records a sparse
  overlay of the exact paths written, `load-settings-from-storage!` reads
  it back, and the live map resolves `defaults < configure! seed <
  persisted overrides` whichever of `configure!` and the load runs first
  (spec/015-Configuration.md §`configure!` vs `init!` vs persisted
  Settings)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader]
            [day8.re-frame2-xray.config :as config]))

(use-fixtures :each
  {:before (fn [] (config/reset-settings!))
   :after  (fn [] (config/reset-settings!))})

(defn- storage-payload []
  (#'config/storage-get config/settings-storage-key))

(defn- reload!
  "Simulate a page reload: discard the live atom AND the `configure!` seed,
  keep only storage. Resetting the atom alone leaves the seed in process."
  []
  (let [payload (storage-payload)]
    (config/reset-settings!)
    (when payload
      (#'config/storage-set! config/settings-storage-key payload))))

(deftest defaults-match-spec
  ;; :epoch-history matches the substrate's `re-frame.epoch.state/default-depth`.
  (is (= [13 :right-rail false :light 50]
         [(config/get-setting :general :text-size)
          (config/get-setting :general :panel-position)
          (config/get-setting :general :auto-open-on-error?)
          (config/get-setting :theme nil)
          (config/get-setting :general :epoch-history)])))

(deftest text-size-round-trips
  (config/update-setting! :general :text-size 16)
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (= 16 (config/get-setting :general :text-size))))

(deftest reset-clears-everything
  (config/update-setting! :general :text-size 18)
  (config/reset-settings!)
  (is (= [config/default-settings nil] [(config/get-settings) (storage-payload)])))

(deftest malformed-payload-degrades-to-defaults
  (#'config/storage-set! config/settings-storage-key "this is not valid edn { {{")
  (config/load-settings-from-storage!)
  (is (= config/default-settings (config/get-settings))))

;; ---- configure! vs persisted Settings merge order -----------------------
;;
;; On the `:devtools/preloads` path the preload's load runs BEFORE the
;; host's `configure!` (shadow-cljs loads preloads ahead of `:init-fn`), so
;; `configure!` recomputes the three-layer merge rather than `reset!`-ing
;; its seed over the user's persisted values.

(deftest preload-order-persisted-wins-over-later-configure
  (testing "load first, `configure!` second: the persisted value still wins,
            the seed supplies the keys the user never persisted"
    (#'config/storage-set! config/settings-storage-key
                           (pr-str {:general {:text-size 20}}))
    (config/load-settings-from-storage!)
    (is (= 20 (config/get-setting :general :text-size))
        "precondition: the preload loaded the user's persisted value")
    (config/configure!
      {:rf.xray/settings {:general {:text-size      15
                                    :panel-position :fullscreen}}})
    (is (= [20 :fullscreen false]
           [(config/get-setting :general :text-size)
            (config/get-setting :general :panel-position)
            (config/get-setting :general :auto-open-on-error?)])
        "persisted 20 beats the host's 15; the seed's :fullscreen lands; a key
         neither layer names keeps its compiled-in default")))

;; ---- the payload is a sparse overlay of explicit overrides -------------
;;
;; A payload holding the whole resolved map would make every key behave as
;; user-set, so no later host `configure!` value or Xray default could land.

(defn- two-boots!
  "Boot 1: the host configures `{:theme :dark}` and the user performs
  `gesture!`. Real reload. Boot 2: the host has CHANGED its call to
  `{:theme :light :general {:density :compact}}`; the load and the host's
  `configure!` run in `order` — `:load-first` is the `:devtools/preloads`
  path, `:configure-first` a host that orders its own preload ahead of
  Xray's."
  [gesture! order]
  (config/configure! {:rf.xray/settings {:theme :dark}})
  (gesture!)
  (reload!)
  (let [boot-2 {:rf.xray/settings {:theme   :light
                                   :general {:density :compact}}}]
    (case order
      :load-first      (do (config/load-settings-from-storage!)
                           (config/configure! boot-2))
      :configure-first (do (config/configure! boot-2)
                           (config/load-settings-from-storage!)))))

(deftest a-later-host-posture-lands-for-every-key-the-user-never-wrote
  (doseq [order [:load-first :configure-first]]
    (testing (str "order " order ": one panel drag records that width alone, so
                   the host's later :light / :compact still land (a whole-map
                   payload would read back :dark / :cosy)")
      (config/reset-settings!)
      (two-boots! #(config/update-setting! :general :panel-width-px 700) order)
      (is (= [:light :compact 700]
             [(config/get-setting :theme nil)
              (config/get-setting :general :density)
              (config/get-setting :general :panel-width-px)])))))

(deftest an-override-records-exactly-the-path-written
  (testing "a second write lands beside the first, from the payload in storage"
    (config/update-setting! :general :panel-width-px 700)
    (config/update-setting! :theme nil :dark)
    (is (= {:general {:panel-width-px 700} :theme :dark}
           (cljs.reader/read-string (storage-payload))))))

(deftest an-explicit-choice-still-beats-a-later-host-posture
  (testing "a theme the user CHOSE outranks the host's later :light when the
            host's `configure!` runs before the load"
    (two-boots! #(config/update-setting! :theme nil :dark) :configure-first)
    (is (= :dark (config/get-setting :theme nil)))))

(deftest a-choice-equal-to-the-default-still-pins
  (testing "the record is WHAT WAS WRITTEN, not what differs from the base: a
            user who picks :light, the compiled-in default, keeps it against a
            later host :dark"
    (config/update-setting! :theme nil :light)
    (reload!)
    (config/load-settings-from-storage!)
    (config/configure! {:rf.xray/settings {:theme :dark}})
    (is (= :light (config/get-setting :theme nil)))))
