(ns re-frame-2.frame
  "Frame container, lifecycle, and the frame registry. Per Spec 002.

  A frame is an isolated runtime boundary identified by a keyword. Every
  frame holds its own app-db (a substrate-managed reactive container),
  its own per-frame router queue, and its own sub-cache.

  Frames are not values — they are mutable runtime objects. User code
  holds keywords; this namespace holds the frame records.

  Reserved frame ids:
    :rf/default              — universal default frame (always present)
    :rf.frame/<gensym>       — anonymous instances from make-frame"
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.interop :as interop]
            [re-frame-2.trace :as trace]))

;; ---- the frame record -----------------------------------------------------
;;
;; Per Spec 002 §What lives in a frame, a frame is a map with:
;;   :id          the keyword identity
;;   :app-db      substrate-managed reactive container (opaque; through adapter)
;;   :router      per-frame queue + drain-state FSM (defined in router.cljc)
;;   :sub-cache   per-frame sub-cache (defined in subs.cljc)
;;   :lifecycle   {:created-at :destroyed? :listeners}
;;   :config      the metadata that reg-frame was given
;;
;; Frame records are stored in `frames` keyed by id.

(defonce
  ^{:doc "Map of frame-id → frame-record. Per-process (one global frame registry)."}
  frames
  (atom {}))

;; ---- lookup ---------------------------------------------------------------

(defn frame
  "Return the frame record for id, or nil if not registered or destroyed."
  [id]
  (when-let [f (get @frames id)]
    (when-not (get-in f [:lifecycle :destroyed?])
      f)))

(defn frame-meta
  "Per Spec 002 §The public registrar query API: return the public metadata
  for a frame (config, lifecycle info, override maps)."
  [id]
  (when-let [f (frame id)]
    {:id        (:id f)
     :config    (:config f)
     :lifecycle (:lifecycle f)}))

(defn frame-ids
  "All registered, non-destroyed frame ids."
  []
  (into #{}
        (comp (filter (fn [[_ f]] (not (get-in f [:lifecycle :destroyed?]))))
              (map key))
        @frames))

(defn get-frame-db
  "Return the underlying app-db container for the frame. Tools and tests
  use this; user handlers receive db via cofx."
  [id]
  (:app-db (frame id)))

(defn frame-app-db-value
  "Read the current app-db value for a frame as a plain map (deref through
  the substrate adapter)."
  [id]
  (when-let [container (get-frame-db id)]
    (adapter/read-container container)))

;; ---- frame presets (Spec 002 §Frame presets) ------------------------------
;;
;; A :preset key in metadata expands at registration time into a fixed
;; bundle of metadata keys. User-supplied keys win on conflict.
;; Per Spec 002 §Frame presets, the v1 closed list is:
;;   :default :test :story :ssr-server

(defn- preset-expansion [preset]
  (case preset
    :default    {}
    :test       {:fx-overrides {:http :rf.test/http-stub}}
    :story      {:fx-overrides {:http :rf.test/http-stub}
                 :drain-depth  16}
    :ssr-server {:platform     :server
                 :on-error     :rf.error/server-projection}
    nil         {}
    (throw (ex-info ":rf.error/unknown-preset"
                    {:preset preset
                     :valid  #{:default :test :story :ssr-server}}))))

(defn- expand-preset [metadata]
  (let [preset    (:preset metadata)
        expansion (preset-expansion preset)]
    ;; user-supplied keys win on conflict
    (merge expansion metadata)))

;; ---- registration ---------------------------------------------------------

(defn- new-frame-record [id config]
  {:id        id
   :app-db    (adapter/make-state-container {})
   :router    (atom {:queue interop/empty-queue :scheduled? false})
   :sub-cache (atom {})
   :lifecycle {:created-at (interop/now-ms)
               :destroyed? false
               :listeners  []}
   :config    config})

(declare destroy-frame!)

(defn reg-frame
  "Atomic create-and-register. Per Spec 002 §reg-frame is atomic:
  - If the id is unregistered, create the frame container, run :on-create
    events synchronously, return the keyword.
  - If the id is already registered, perform a SURGICAL UPDATE: existing
    runtime state (app-db, sub-cache, queue) is preserved; only the
    metadata/config is replaced. Hot-reload Just Works."
  [id metadata]
  (let [config (expand-preset metadata)]
    (registrar/register! :frame id config)
    (let [existing (get @frames id)]
      (cond
        ;; First registration: create everything.
        (nil? existing)
        (let [f (new-frame-record id config)]
          (swap! frames assoc id f)
          ;; Run :on-create events synchronously BEFORE emitting :frame/created.
          ;; Per Spec 002 §Frame creation: on-create completes first, then
          ;; the frame is observable to listeners. The router/dispatch
          ;; namespace handles dispatch-sync; we forward via require to avoid
          ;; a cyclic dep at compile time.
          (when-let [on-create (:on-create config)]
            (when-let [dispatch-sync (resolve 're-frame-2.router/dispatch-sync!)]
              ((deref dispatch-sync) on-create {:frame id})))
          (trace/emit! :frame :frame/created
                       {:frame id :config config})
          id)

        ;; Re-registration: surgical update of replaceable slots only.
        ;; Per Spec 002 §Re-registration — surgical update.
        :else
        (do
          (swap! frames update id assoc :config config)
          (trace/emit! :frame :frame/re-registered
                       {:frame id :config config})
          id)))))

(defn make-frame
  "Anonymous-instance creation. Generates a gensym'd id under :rf.frame/.
  Returns the gensym'd id. Per Spec 002 §Per-instance frames."
  [config]
  (let [id (keyword "rf.frame" (str (gensym "")))]
    (reg-frame id config)
    id))

;; ---- destruction ----------------------------------------------------------

(defn destroy-frame!
  "Tear down a frame. Active machines run their :exit cascades (per
  Spec 005); pending :after timers are cancelled by epoch staleness;
  the sub-cache disposes; the frame slot is removed. Subsequent dispatch
  to a destroyed frame raises :rf.error/frame-destroyed.

  Per Spec 002 §Destroy."
  [id]
  (when-let [f (frame id)]
    (when-let [on-destroy (get-in f [:config :on-destroy])]
      (when-let [dispatch-sync (resolve 're-frame-2.router/dispatch-sync!)]
        ((deref dispatch-sync) on-destroy {:frame id})))
    (swap! frames update id assoc-in [:lifecycle :destroyed?] true)
    ;; Sub-cache disposal: walk every entry and dispose.
    (when-let [cache (:sub-cache f)]
      (doseq [[_k entry] @cache]
        (when-let [r (:reaction entry)]
          (try (interop/dispose! r)
               (catch #?(:clj Throwable :cljs :default) _ nil))))
      (reset! cache {}))
    (trace/emit! :frame :frame/destroyed
                 {:frame id})
    (swap! frames dissoc id)
    nil))

(defn reset-frame!
  "destroy-frame! followed by reg-frame with the same config. Per Spec 002
  §reset-frame — full replace, opt-in."
  [id]
  (when-let [f (frame id)]
    (let [config (:config f)]
      (destroy-frame! id)
      (reg-frame id config))))

;; ---- :rf/default ----------------------------------------------------------

(defn ensure-default-frame!
  "The :rf/default frame is registered automatically the first time the
  runtime boots. Idempotent."
  []
  (when-not (get @frames :rf/default)
    (reg-frame :rf/default {:doc "Universal default frame."})))
