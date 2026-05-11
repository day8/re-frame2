(ns re-frame.story.async
  "Promise / CompletableFuture abstraction for Story's runtime.

  Per IMPL-SPEC §3.2 + §13.2, `run-variant` returns a promise-like
  object that the host can await:

  - On CLJS the return value is a native `js/Promise`.
  - On JVM the return value is a `java.util.concurrent.CompletableFuture`.

  Stage 3 (rf2-von3) is the call: this namespace abstracts the two so
  the rest of the runtime can write portable code.

  ## API

  - `(promise f)` — run `f` (a 1-arg fn that receives a resolver fn) and
    return a promise / future. The resolver, when called with a value,
    completes the promise. If `f` throws, the promise rejects.
    Shadows `clojure.core/promise` — Story's `promise` is the
    canonical constructor in this namespace.
  - `(resolved v)` — return an already-resolved promise carrying `v`.
  - `(rejected e)` — return an already-rejected promise carrying `e`.
  - `(then p f)` — chain `f` (a 1-arg fn) over the promise's resolved
    value, returning a new promise.
  - `(catch* p f)` — chain `f` (a 1-arg fn taking the thrown value) over
    the promise's rejection path, returning a new promise.
  - `(deref-blocking p timeout-ms)` — JVM-only: block the caller until
    the future resolves (or the timeout elapses). For tests / REPL.
    CLJS callers don't block — they use `then` / `await` instead.
  - `(promise? x)` — true iff `x` is a promise / future of our flavour.

  ## Why not core.async

  Per the user-feedback rule `no_core_async`, re-frame2 does not depend
  on core.async. Story's async surface uses native Promise (CLJS) and
  CompletableFuture (JVM) directly — the two surfaces the host runtime
  already exposes. No additional dependency."
  (:refer-clojure :exclude [promise]))

;; ---- construction ---------------------------------------------------------

(defn promise
  "Run `f` (a 1-arg fn that receives a resolver fn) and return a
  promise / future. The resolver, when called with a value, completes
  the promise. If `f` throws synchronously, the promise rejects with
  the throwable.

  Two-resolver shape `(fn [resolve reject] ...)` is also supported when
  `f` declares two args."
  [f]
  #?(:cljs
     (js/Promise.
       (fn [resolve reject]
         (try
           (let [arity (or (some-> f .-length) 1)]
             (if (= arity 2)
               (f resolve reject)
               (f resolve)))
           (catch :default e
             (reject e)))))
     :clj
     (let [cf (java.util.concurrent.CompletableFuture.)]
       (try
         (let [arity (try
                       ;; Reflection on the fn class. Returns 1 if we can't
                       ;; tell — most call sites use the 1-arg form.
                       (let [methods (.getDeclaredMethods (class f))]
                         (some (fn [^java.lang.reflect.Method m]
                                 (when (and (= "invoke" (.getName m))
                                            (= 2 (.getParameterCount m)))
                                   2))
                               methods))
                       (catch Throwable _ nil))
               resolve-fn (fn [v] (.complete cf v))
               reject-fn  (fn [e] (.completeExceptionally cf
                                                          (if (instance? Throwable e)
                                                            e
                                                            (ex-info "re-frame.story.async rejection"
                                                                     {:rejection e}))))]
           (if (= 2 arity)
             (f resolve-fn reject-fn)
             (f resolve-fn)))
         (catch Throwable e
           (.completeExceptionally cf e)))
       cf)))

(defn resolved
  "Return an already-resolved promise / future carrying `v`."
  [v]
  #?(:cljs (js/Promise.resolve v)
     :clj  (java.util.concurrent.CompletableFuture/completedFuture v)))

(defn rejected
  "Return an already-rejected promise / future carrying `e`."
  [e]
  #?(:cljs (js/Promise.reject e)
     :clj  (let [cf (java.util.concurrent.CompletableFuture.)]
             (.completeExceptionally cf (if (instance? Throwable e)
                                          e
                                          (ex-info "re-frame.story.async rejection"
                                                   {:rejection e})))
             cf)))

;; ---- composition ----------------------------------------------------------

(defn then
  "Chain `f` over the promise's resolved value. Returns a new promise.
  `f` may return a plain value (wrapped into a resolved promise) or
  another promise (chained)."
  [p f]
  #?(:cljs (.then ^js/Promise p f)
     :clj  (.thenCompose ^java.util.concurrent.CompletableFuture p
                         (reify java.util.function.Function
                           (apply [_ v]
                             (let [r (f v)]
                               (if (instance? java.util.concurrent.CompletionStage r)
                                 r
                                 (java.util.concurrent.CompletableFuture/completedFuture r))))))))

(defn catch*
  "Chain `f` over the promise's rejection path. `f` receives the
  rejection value (a Throwable on JVM, any value on CLJS). Returns a
  new promise that resolves to `f`'s return value (or re-rejects if `f`
  itself throws)."
  [p f]
  #?(:cljs (.catch ^js/Promise p f)
     :clj  (.exceptionally ^java.util.concurrent.CompletableFuture p
                           (reify java.util.function.Function
                             (apply [_ t]
                               ;; CompletableFuture wraps user throwables in
                               ;; CompletionException. Unwrap so `f` sees the
                               ;; underlying cause — matches JS Promise.catch
                               ;; semantics (which delivers the rejection
                               ;; value as-is).
                               (let [unwrapped (if (instance? java.util.concurrent.CompletionException t)
                                                 (or (.getCause ^Throwable t) t)
                                                 t)]
                                 (f unwrapped)))))))

(defn promise?
  "True iff `x` is a promise / future of our flavour."
  [x]
  #?(:cljs (and (some? x) (fn? (.-then ^js x)))
     :clj  (instance? java.util.concurrent.CompletionStage x)))

;; ---- JVM-only blocking deref ---------------------------------------------

#?(:clj
   (defn deref-blocking
     "JVM-only: block the caller until `p` resolves (or `timeout-ms`
     elapses). Returns the resolved value, or throws if the promise
     rejected. Use for tests / REPL.

     CLJS callers don't block — chain with `then` / use `await` on the
     promise instead."
     ([p] (.get ^java.util.concurrent.CompletableFuture p))
     ([p timeout-ms]
      (.get ^java.util.concurrent.CompletableFuture p
            timeout-ms
            java.util.concurrent.TimeUnit/MILLISECONDS))))
