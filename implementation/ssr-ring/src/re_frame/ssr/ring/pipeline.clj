(ns re-frame.ssr.ring.pipeline
  "The Ring request lifecycle pipeline.

  The handler is a 4-step pipeline:

    1. `setup-request-frame!` — gensym frame-id, populate request slot,
       register the per-request frame (which drains :on-create
       synchronously). On failure, returns a {:short-circuit ring-resp}
       map so the outer pipeline emits the on-error response without
       attempting render.
    2. `ssr/get-response`     — read the resolved response accumulator
       (flushes any pending error projection).
    3. branch on :redirect    — short-circuit to a Location response,
       OR `build-full-response` — render the root-view, build the
       hydration payload, wrap in the html-shell, materialise to Ring.
    4. `destroy-frame-quietly!` in `finally` — `:ssr/on-frame-destroyed`
       (rf2-fcj33) clears the per-frame request slot."
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring.headers :as headers]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.payload :as payload]
            [re-frame.ssr.ring.response :as response]))

(set! *warn-on-reflection* true)

(defn setup-request-frame!
  "Register a per-request frame and populate the request slot. Returns
  `{:frame-id fid}` on success, or `{:short-circuit ring-response}` when
  setup fails (the caller short-circuits to that response, skipping
  render).

  The slot is populated BEFORE `reg-frame` so the synchronous
  `:on-create` drain can resolve the `:rf.server/request` cofx (Spec
  011 §Request storage substrate). `make-frame` gensyms the id
  internally and would return only after the drain, so we inline its
  (gensym + reg-frame) shape here and call `ssr/set-request!` between
  them. The assembled config is identical to what `make-frame` would
  have submitted.

  On failure mid-drain, the request slot AND any partial frame
  registration are cleared so neither leaks across requests. The frame
  may be registered in the `frames` atom (see frame.cljc — `swap!
  frames` happens before `dispatch-sync`), so the best-effort destroy
  is required even though `reg-frame` threw."
  [{:keys [on-create fx-overrides ssr on-error]} request]
  (let [fid (keyword "rf.frame" (str (gensym "")))]
    (ssr/set-request! fid request)
    (try
      (rf/reg-frame fid
        (cond-> {:doc       "ssr-ring per-request frame"
                 :platform  :server
                 :on-create (lifecycle/on-create-with-request on-create request)}
          fx-overrides (assoc :fx-overrides fx-overrides)
          ssr          (assoc :ssr           ssr)))
      {:frame-id fid}
      (catch Throwable t
        (ssr/clear-request! fid)
        (lifecycle/destroy-frame-quietly! fid)
        {:short-circuit (on-error request t)}))))

(defn build-full-response
  "Render the caller's `:root-view` against `frame-id`, build the
  hydration payload, wrap in the html-shell, and materialise to a Ring
  response.

  Per rf2-6t36h the root-view resolves EXACTLY ONCE per request — both
  the wire HTML (via `render-to-string` + its embedded
  `data-rf-render-hash`) and the payload's `:rf/render-hash` derive
  from the same hiccup tree, so a non-idempotent fn-form root-view
  cannot fire a spurious `:rf.ssr/hydration-mismatch` on a successful
  hydration."
  [frame-id resp
   {:keys [root-view emit-hash? version schema-digest payload-keys
           html-shell content-type]
    :as   opts}]
  (let [hiccup      (rf/with-frame frame-id (lifecycle/resolve-root-view root-view))
        body-html   (rf/with-frame frame-id
                      (ssr/render-to-string hiccup
                                            {:doctype?   false
                                             :emit-hash? emit-hash?}))
        hash-str    (ssr/render-tree-hash hiccup)
        app-db      (rf/get-frame-db frame-id)
        payload     (payload/build-payload frame-id app-db hash-str
                                           {:version       version
                                            :schema-digest schema-digest
                                            :payload-keys  payload-keys})
        payload-edn (pr-str payload)
        ;; rf2-4dra9: resolve the active route's :head (or
        ;; default-head fallback) and pass the rendered fragment as
        ;; the :head opt. Callers that supplied an explicit :head opt
        ;; take precedence — they chose to bypass route-driven head
        ;; resolution.
        head-html   (or (:head opts) (lifecycle/resolve-head-html frame-id))
        shell-opts  (assoc opts :head head-html)
        html        (html-shell body-html payload-edn shell-opts)
        ;; Ensure Content-Type is set; the SSR runtime defaults
        ;; [:rf/response :headers] to include content-type so this is
        ;; usually a no-op, but we let opts override and trust the
        ;; runtime's default in absence.
        resp*       (update resp :headers headers/ensure-content-type content-type)]
    (response/ssr-response->ring-response resp* html)))
