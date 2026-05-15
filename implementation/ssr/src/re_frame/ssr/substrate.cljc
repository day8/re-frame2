(ns re-frame.ssr.substrate
  "The SSR substrate adapter (rf2-agql). Per Spec 006 §Plain-atom adapter
  and Spec 011 §init flow.

  Each adapter ns exports an `adapter` var that the consumer passes to
  `(rf/init! ...)`. SSR is a server-side adapter — same plain-atom-shaped
  state container, no React reactivity, no hooks — but it binds its own
  render-to-string directly into the slot so a server-side bootstrap is
  one explicit call:

      (require '[re-frame.core :as rf]
               '[re-frame.ssr :as ssr])
      (rf/init! ssr/adapter)

  The render slot deliberately throws — SSR uses render-to-string
  exclusively; see Spec 006 §Plain-atom adapter and rf2-z1ke (substrate
  contract portability findings). Eight of the nine contract slots are
  implemented cleanly; render is the deliberate exception.

  Internal implementation namespace. The public adapter remains
  re-frame.ssr/adapter; this ns deliberately avoids
  re-frame.ssr.adapter so CLJS does not see a child-namespace /
  parent-var name clash.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [re-frame.ssr.emit :as emit]))

(defn- ssr-make-state-container [initial-value]
  (atom initial-value))

(defn- ssr-read-container [container]
  @container)

(defn- ssr-replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- ssr-subscribe-container [container on-change]
  (let [k (gensym "rf-ssr-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

(defn- ssr-make-derived-value [source-containers compute-fn]
  ;; No caching: SSR runs each sub at most a handful of times per
  ;; request; mirrors the plain-atom adapter.
  (reify
    #?(:clj clojure.lang.IDeref :cljs IDeref)
    (#?(:clj deref :cljs -deref) [_]
      (apply compute-fn (map deref source-containers)))))

(defn- ssr-render [_ _ _]
  ;; SSR uses render-to-string exclusively. Calling render on the SSR
  ;; adapter is a programmer error worth surfacing loudly. Per Spec 006
  ;; §Plain-atom adapter and rf2-z1ke.
  (throw (ex-info ":rf.error/render-on-headless-adapter"
                  {:reason "render is not supported on the SSR adapter; use render-to-string"})))

(defn- ssr-register-context-provider [_frame-keyword]
  ;; No React context on the JVM; users thread frames as arguments per
  ;; Spec 002 §View ergonomics fallback.
  nil)

(defn- ssr-dispose-adapter! []
  ;; Watch handles are GC'd with their atoms; nothing else to clean up.
  nil)

(def adapter
  "The SSR adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.core :as rf]
               '[re-frame.ssr :as ssr])
      (rf/init! ssr/adapter)

  Server-side and headless processes use this adapter — it carries
  re-frame.ssr's render-to-string directly in the :render-to-string
  slot, no late-bind wiring required at the call site. The :render slot
  throws (`:rf.error/render-on-headless-adapter`) — SSR uses
  render-to-string exclusively. Per rf2-agql and Spec 011 §init flow."
  {:kind                      :ssr
   :make-state-container      ssr-make-state-container
   :read-container            ssr-read-container
   :replace-container!        ssr-replace-container!
   :subscribe-container       ssr-subscribe-container
   :make-derived-value        ssr-make-derived-value
   :render                    ssr-render
   :render-to-string          emit/render-to-string
   :register-context-provider ssr-register-context-provider
   :dispose-adapter!          ssr-dispose-adapter!})
