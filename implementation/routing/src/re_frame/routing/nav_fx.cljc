(ns re-frame.routing.nav-fx
  "Standard navigation fxs (`:rf.nav/push-url`, `:rf.nav/replace-url`)
  + URL-owner resolution for re-frame2 routing.

  Per Spec 012 §Multi-frame routing and rf2-w50qm: `:rf.nav/push-url` /
  `:rf.nav/replace-url` MUST consult the calling frame's `:url-bound?`
  metadata before touching the browser history. The default frame
  (`:rf/default`) is URL-bound; non-default frames are not, unless they
  opt in via `(reg-frame :my-frame {:url-bound? true})`. Non-URL-bound
  frames no-op the fx (history.pushState would race with the
  URL-owning frame). The check honours the framework default:
  `:rf/default` is URL-bound when no explicit `:url-bound?` slot is
  declared.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `fx/reg-fx` calls so a `:reload` re-wires them on
  a fresh registrar. Per the rf2-2yabr cohesion split: NAV-FX seam."
  (:require [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

(defn url-bound?-from-config
  "Read `:url-bound?` from a frame's stored config map. `nil` when
  unset. Default-on for `:rf/default` is applied at the call site, not
  here, so the hook can discriminate explicit-`true` from default-`true`."
  [config]
  (when (map? config)
    (:url-bound? config)))

(defn url-owner-frame-id
  "Return the single frame allowed to mutate browser history. The default
  frame owns the URL unless it explicitly opts out; otherwise the first
  explicit non-default `:url-bound? true` frame wins deterministically.
  Duplicate registrations still emit `:rf.error/duplicate-url-binding`,
  but this predicate enforces the one-owner rule at fx time.

  Public (rather than `defn-`) so the ownership-resolution contract is
  directly assertable — in particular the single-non-default-owner case
  the step-deck testbed relies on (rf2-6qgbs.3): when `:rf/default` opts
  OUT (`:url-bound? false`) a non-default `:url-bound? true` frame becomes
  the owner. A reimplemented gate cannot catch a regression in THIS
  resolution; the test must reach the real fn."
  []
  (let [frames       (registrar/registrations :frame)
        default-meta (get frames :rf/default)]
    (if-not (false? (url-bound?-from-config default-meta))
      :rf/default
      (->> frames
           (filter (fn [[id meta]]
                     (and (not= :rf/default id)
                          (true? (url-bound?-from-config meta)))))
           (sort-by (fn [[id _]] (str id)))
           ffirst))))

(defn url-bound-frame?
  "Return true when the frame named `frame-id` is the one active URL
  owner. Per Spec 012 §Multi-frame routing, duplicate `:url-bound? true`
  declarations are reported AND non-owners are prevented from pushing."
  [frame-id]
  (= (or frame-id :rf/default) (url-owner-frame-id)))

;; `:rf.nav/push-url` and `:rf.nav/replace-url` share one body: gate on
;; the calling frame's URL ownership, then either drive the browser
;; history (CLJS) or emit the standard `:rf.fx/skipped-on-platform`
;; trace (JVM / non-owner). The ONLY per-fx variation is the history
;; method (`history.pushState` vs `history.replaceState`) and the
;; `:fx-id` tag, so the body lives once in `history-mutation-handler`
;; and each handler closes over its method + fx-id.

(defn- history-mutation-handler
  "Shared body for the `:rf.nav/push-url` / `:rf.nav/replace-url` fx
  handlers. `fx-id` tags the platform-skip / non-owner traces;
  `mutate!` is the 0-arg thunk that drives `window.history` on CLJS (a
  no-op on JVM — the caller passes a CLJS-only thunk under a reader
  conditional). Non-URL-bound frames skip the history mutation: the
  frame's route slice at `[:rf/runtime :routing :current]` still
  updates — only the browser-URL sync is suppressed. Per Spec 012
  §Multi-frame routing this is the right default for story-variant /
  devcard / per-test fixtures."
  [fx-id frame url mutate!]
  (if (url-bound-frame? frame)
    #?(:cljs (mutate!)
       :clj  (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                          {:fx-id fx-id :url url}))
    (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                 {:fx-id  fx-id
                  :url    url
                  :frame  frame
                  :reason :frame-not-url-bound})))

(def push-url-meta
  "Metadata for the `:rf.nav/push-url` fx registration. Spec 012
  §Multi-frame routing (rf2-w50qm)."
  {:platforms #{:client}
   :doc       "Push the URL to the browser history (HTML5 pushState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing — rf2-w50qm)."})

(defn push-url-handler
  "`:rf.nav/push-url` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar.

  rf2-cylse.4 (defence-in-depth): the `.pushState` call is wrapped in
  try/catch. A browser throws `SecurityError` when asked to push an
  absolute cross-origin URL — left uncaught that crashes the fx drain (a
  DoS, worse than the redirect it would otherwise be). The
  `url/external-url?` gate at the nav-event sinks (rf2-cylse.4) already
  fails such URLs closed before they reach this fx, so this catch is a
  second line of defence: any residual throw is downgraded to a
  `:rf.fx/push-url-failed` trace, keeping the drain alive."
  [{:keys [frame]} url]
  (history-mutation-handler
    :rf.nav/push-url frame url
    #?(:cljs #(try
                (.pushState js/window.history nil "" url)
                (catch :default e
                  (trace/emit! :rf.fx :rf.fx/push-url-failed
                               {:fx-id :rf.nav/push-url :url url
                                :error (.-message e)})))
       :clj nil)))

(def replace-url-meta
  "Metadata for the `:rf.nav/replace-url` fx registration. Spec 012
  §Multi-frame routing (rf2-w50qm)."
  {:platforms #{:client}
   :doc       "Replace the URL in the browser history (HTML5 replaceState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing — rf2-w50qm)."})

(defn replace-url-handler
  "`:rf.nav/replace-url` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [frame]} url]
  (history-mutation-handler
    :rf.nav/replace-url frame url
    #?(:cljs #(.replaceState js/window.history nil "" url) :clj nil)))
