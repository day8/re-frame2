(ns re-frame.adapter.react-shared-suite
  "Parameterised, substrate-agnostic test suite for the React-shaped
  adapters (UIx, Helix) — rf2-sx77q.

  WHY THIS EXISTS. UIx and Helix both wire their entire public surface
  out of the SAME `re-frame.substrate.spine/make-react-spine` factory
  (see `uix.cljs:25-33` / `helix.cljs:26-34`); the only differences are
  the substrate name string, the gensym prefixes, and which host's
  `use-memo` / `use-callback` / `use-context` are passed in. Their tests
  were, accordingly, ~20 near-byte-identical file PAIRS (~40 files)
  differing only by `uix`↔`helix` and the id keyword (rf2-sx77q audit
  D1). A change to one was routinely hand-copied to the other; the
  docstrings literally cross-referenced their siblings.

  WHAT THIS DOES. Every spine-shared behaviour is asserted ONCE here, as
  a plain `defn` that takes the per-adapter config map and runs
  `cljs.test/is` / `testing` against the *installed* adapter. The
  per-adapter test files (`uix_react_shared_cljs_test.cljs`,
  `helix_react_shared_cljs_test.cljs`) are thin: a fixture installing the
  adapter, plus one `deftest` per shared fn that forwards the config. The
  suite cannot drift between substrates by construction — a gap on one is
  a gap on both, and closing it here closes it everywhere.

  THIS NS IS NOT A TEST FILE. Its name does NOT end in `cljs-test`, so
  the `:node-test` build's `:ns-regexp \"cljs-test$\"` does NOT discover
  it directly. It runs only through the per-adapter entry files, which
  bind a real adapter. (If it ran with no adapter installed every
  assertion would fail at the install seam — exactly why the entry-file
  indirection is mandatory.)

  CONFIG MAP. Each entry fn takes:

    {:adapter      the adapter map (e.g. uix-adapter/adapter)
     :substrate-kw a keyword namespace fragment unique to the substrate
                   (e.g. :uix / :helix) used to mint per-adapter ids so
                   two adapters' suites never collide in the same process
     :wrap-view    the adapter's wrap-view fn
     :clear-warn!  the adapter's clear-warned-non-dom-roots! fn
     :set-emitter! the adapter's set-hiccup-emitter! fn
     :render-to-string the adapter's render-to-string fn
     :name         human substrate name for assertion messages}

  COVERAGE (closes rf2-sx77q gaps G2/G3/G4/G5 for BOTH React adapters):
    - dispose MUST (1) sub-cache walk + best-effort poison tolerance (G3)
    - dispose MUST (2) idempotent root drain
    - dispose MUST (3) clears the hiccup-emitter cell
    - dispose MUST (4) post-dispose delegation throws :adapter-disposed
    - source-coord DOM stamping: annotate / with-attrs merge /
      user-attr-wins / fragment-exempt / format-shape split (G2)
    - view-id (data-rf-view) stamping alongside source-coord
    - frame-context corrupted `_currentValue` emit + recover (G4)
    - warn-once fires EXACTLY once per id across renders, per-id, not
      global (G5)
    - write-after-destroy nil-container guard

  DOM/BROWSER TWINS (rf2-5or96 — the DOM-split remainder of rf2-p4736).
  Two twin clusters defined substrate-specific component vars (UIx
  `defui`/`$`/uix-hooks; Helix `defnc`/`$`/helix.dom/helix.hooks) that
  the suite cannot mint at runtime. Approach A — the substrate-specific
  components are built in each entry file and handed in via the cfg map
  (`:render-element`, the probe vars, observation atoms, frame keywords),
  while the orchestration + every assertion lives here as one source:

    - after-render: ns-load smoke (node-safe) + mount/schedule/drain
      act-driven behaviour (rf2-334d9)
    - use-subscribe: useSyncExternalStore post-dispatch values
      (rf2-518sp), frame-provider 1-arg resolution, 2-arg explicit-frame
      pinning (rf2-rcgsc / rf2-y0db2), refcount cleanup on unmount
      (rf2-7g959), stable-deps-key one-subscribe-across-N-renders spy
      assertions (rf2-mwft2)

  These DOM assertions self-gate on `(browser?)` — under :node-test
  (which discovers the `-dom-cljs-test` entry files via `cljs-test$`)
  they no-op cleanly; the real assertions run under :browser-test
  (`-dom-cljs-test$`)."
  (:require ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            ;; Test-only: react-dom/server gives matching SSR markup for the
            ;; hydrate-branch assertion (rf2-ee38b.1). Lives in the suite
            ;; (a test ns), never in a production bundle.
            ["react-dom/server" :as react-dom-server]
            [cljs.test :refer-macros [is testing async]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.disposable :as rf-disposable]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.subs :as subs]
            [re-frame.late-bind :as late-bind]
            [re-frame.late-bind.directory :as directory]
            [re-frame.machines :as machines]
            [re-frame.routing :as routing]
            [re-frame.ssr :as ssr]
            [re-frame.schemas.malli]
            [re-frame.http-managed :as http-managed]
            ;; rf2-cdmle — canned-stub fxs gate on explicit test-support
            ;; require; the http-managed suite uses :fx-overrides into
            ;; both fx ids.
            [re-frame.http-test-support]
            [re-frame.views :as views]
            [re-frame.epoch]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.react-test-support :as react-test-support]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.spine :as spine]
            [re-frame.trace.tooling :as trace-tooling])
  (:require-macros [re-frame.core :refer [with-frame with-new-frame frame-bound-fn]]))

;; ===========================================================================
;; helpers
;; ===========================================================================

;; rf2-5g21s — `react-element-attr` is hoisted into the dependency-free
;; `re-frame.adapter.react-test-support` so the narrow elision-prod twins
;; can share it without dragging this heavy suite into their build. The
;; suite consumes the same single source of truth.
(def react-element-attr react-test-support/react-element-attr)

(defn- source-coord [el] (react-element-attr el "data-rf2-source-coord"))
(defn- view-attr     [el] (react-element-attr el "data-rf-view"))

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  the vector of joined-message strings observed. Restores the original on
  the way out, even if thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

(defn- mint-kw
  "Mint a substrate-scoped keyword so the UIx and Helix suites never
  collide on a process-wide `defonce` (warn-once set, etc.)."
  [substrate-kw nm]
  (keyword (str "rf.react-shared." (name substrate-kw)) nm))

(defn- corruption-traces [traces]
  (filter #(= :rf.error/frame-context-corrupted (:operation %)) @traces))

;; ===========================================================================
;; dispose MUST list (Spec 006 §Adapter disposal lifecycle)
;; ===========================================================================

(defn assert-dispose-clears-hiccup-emitter
  "MUST (3): dispose-adapter! discards internal caches. After
  set-hiccup-emitter! → dispose, the next render-to-string raises
  :rf.error/no-hiccup-emitter-bound — proving the emitter slot cleared."
  [{:keys [adapter set-emitter! render-to-string name]}]
  (testing (str name " — MUST (3): dispose clears the hiccup-emitter cell")
    (set-emitter! (fn [_tree _opts] "<x/>"))
    ((:dispose-adapter! adapter))
    (let [thrown (try (render-to-string [:div] {}) nil
                      (catch :default e e))]
      (is (some? thrown) "render-to-string threw post-dispose")
      (is (= ":rf.error/no-hiccup-emitter-bound" (.-message thrown))
          "the emitter slot was cleared by dispose-adapter!"))))

(defn assert-clear-warn-idempotent-post-dispose
  "MUST (3): the adapter-public warn-once clear thunk remains a safe
  idempotent no-op after dispose."
  [{:keys [adapter clear-warn! name]}]
  (testing (str name " — MUST (3): clear-warned-non-dom-roots! idempotent post-dispose")
    ((:dispose-adapter! adapter))
    (is (nil? (clear-warn!))
        "clear-warned-non-dom-roots! is idempotent post-dispose")))

(defn assert-post-dispose-delegation-throws
  "MUST (4): after dispose-adapter!, subsequent delegation calls raise
  :rf.error/adapter-disposed (breadcrumb owned by substrate-adapter)."
  [{:keys [adapter name]}]
  (testing (str name " — MUST (4): post-dispose delegation throws :adapter-disposed")
    (substrate-adapter/dispose-adapter!)
    (is (substrate-adapter/adapter-disposed?)
        "after dispose, the disposed? breadcrumb is true")
    (let [thrown (try (substrate-adapter/make-state-container {}) nil
                      (catch :default e e))]
      (is (some? thrown) "delegation call after dispose threw")
      (is (= ":rf.error/adapter-disposed" (.-message thrown))
          "the throw shape matches MUST (4)"))
    ;; Reinstall so the fixture's :after teardown lands on clean state.
    (substrate-adapter/install-adapter! adapter)))

(defn assert-dispose-idempotent-no-roots
  "MUST (2): dispose-adapter! drains the active-roots set; a second
  dispose is idempotent. (No real roots mounted in node-runtime.)"
  [{:keys [adapter name]}]
  (testing (str name " — MUST (2): dispose idempotent with no tracked roots")
    (is (nil? ((:dispose-adapter! adapter)))
        "dispose-adapter! returns nil even when no roots are tracked")
    (is (nil? ((:dispose-adapter! adapter)))
        "second dispose is idempotent — active-roots set was already drained")))

(defn assert-dispose-clears-sub-caches
  "MUST (1): dispose-adapter! walks every live frame's sub-cache and
  disposes each cached Reaction (the spine derived-value is an
  re-frame-owned IDisposable)."
  [{:keys [adapter substrate-kw name]}]
  (testing (str name " — MUST (1): dispose clears sub-caches across live frames")
    (let [fid (mint-kw substrate-kw "walk-a")]
      (rf/reg-frame fid {})
      (rf/reg-event-db :seed (fn [_ _] {:n 7}))
      (rf/reg-sub :n (fn [db _] (:n db)))
      (rf/dispatch-sync [:seed] {:frame fid})
      (let [r-a (rf/subscribe fid [:n])]
        (is (= 7 @r-a) "precondition: subscription is live and deref-able")
        (let [cache          (:sub-cache (frame/frame fid))
              entries-before @cache]
          (is (>= (count entries-before) 1)
              "precondition: sub-cache holds at least the [:n] entry")
          (let [disposed  (atom #{})
                reactions (for [[_ entry] entries-before
                                :let [r (:reaction entry)]
                                :when r]
                            r)]
            (doseq [r reactions]
              (rf-disposable/-add-on-dispose r (fn [] (swap! disposed conj r))))
            ((:dispose-adapter! adapter))
            (doseq [r reactions]
              (is (contains? @disposed r)
                  "every cached reaction fired its dispose hook"))
            (is (= {} @cache)
                "the frame's sub-cache atom was reset to {} by the walk")))))))

(defn assert-dispose-walk-best-effort
  "MUST (1) best-effort (rf2-sx77q G3): a throwing per-entry dispose does
  NOT abort the rest of the walk. The behaviour is spine-shared but was
  previously pinned ONLY on the Reagent adapter. Pin it on the React
  adapters too so a future spine refactor that drops the per-entry
  try/catch is caught on every substrate."
  [{:keys [adapter substrate-kw name]}]
  (testing (str name " — MUST (1) best-effort: a throwing entry does not abort the walk")
    (let [fid-a (mint-kw substrate-kw "best-effort-a")
          fid-b (mint-kw substrate-kw "best-effort-b")]
      (rf/reg-frame fid-a {})
      (rf/reg-frame fid-b {})
      (rf/reg-event-db :seed (fn [_ _] {:n 1}))
      (rf/reg-sub :n (fn [db _] (:n db)))
      (rf/dispatch-sync [:seed] {:frame fid-a})
      (rf/dispatch-sync [:seed] {:frame fid-b})
      (let [r-a (rf/subscribe fid-a [:n])
            r-b (rf/subscribe fid-b [:n])]
        (is (= 1 @r-a))
        (is (= 1 @r-b))
        ;; Inject a poison entry into fid-a's sub-cache whose dispose
        ;; throws (a bare object with no IDisposable impl). The walk's
        ;; per-entry try must swallow the throw and still drain the rest
        ;; of fid-a AND fid-b.
        (let [cache-a      (:sub-cache (frame/frame fid-a))
              poison-entry {:reaction (js-obj "not" "a reaction")}]
          (swap! cache-a assoc [:poison] poison-entry)
          (let [reactions [r-a r-b]
                disposed  (atom #{})]
            (doseq [r reactions]
              (rf-disposable/-add-on-dispose r (fn [] (swap! disposed conj r))))
            ((:dispose-adapter! adapter))
            (doseq [r reactions]
              (is (contains? @disposed r)
                  "the walk reached and disposed the real Reaction past the poison entry"))
            (is (= {} @(:sub-cache (frame/frame fid-a)))
                "frame-a's cache was still cleared despite the throw")
            (is (= {} @(:sub-cache (frame/frame fid-b)))
                "frame-b's cache was still cleared after the throwing entry")))))))

;; ===========================================================================
;; source-coord DOM stamping (Spec 006 §Source-coord annotation) — G2
;; ===========================================================================

(defn assert-source-coord-annotates-dom-root
  "A DOM-tag-rooted reg-view*'d component carries data-rf2-source-coord
  on its rendered root React element."
  [{:keys [substrate-kw name]}]
  (testing (str name " — source-coord: annotates a DOM-tag root")
    (let [id      (mint-kw substrate-kw "sc-annotate")
          user-fn (fn [] (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* id user-fn)
      (let [out ((rf/view id))]
        (is (some? out) "registered fn returned a non-nil React element")
        (is (= "span" (.-type out)) "root element type preserved")
        (let [attr (source-coord out)]
          (is (string? attr) "data-rf2-source-coord present on the root element")
          (is (str/starts-with? attr (str (namespace id) ":" (clojure.core/name id)))
              "attribute value starts with <ns>:<sym>"))))))

(defn assert-source-coord-merges-with-attrs
  "With an existing props map on the root, the wrapper merges
  data-rf2-source-coord alongside the user's props (no clobber)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — source-coord: merges into an existing props map")
    (let [id      (mint-kw substrate-kw "sc-with-attrs")
          user-fn (fn [] (React/createElement "div"
                                              #js {:className "card" :id "x"}
                                              "body"))]
      (rf/reg-view* id user-fn)
      (let [out   ((rf/view id))
            props (.-props out)]
        (is (= "div" (.-type out)))
        (is (some? props))
        (is (= "card" (aget props "className")) "user className preserved")
        (is (= "x" (aget props "id")) "user id preserved")
        (is (string? (aget props "data-rf2-source-coord"))
            "data-rf2-source-coord merged into the props alongside user attrs")))))

(defn assert-source-coord-user-supplied-wins
  "A render-fn whose root already carries data-rf2-source-coord is not
  overwritten — composability with hand-stamped tools."
  [{:keys [substrate-kw name]}]
  (testing (str name " — source-coord: user-supplied attribute wins")
    (let [id        (mint-kw substrate-kw "sc-user-stamped")
          user-attr "users.namespace:my-component:1:1"
          user-fn   (fn [] (React/createElement
                             "div" #js {"data-rf2-source-coord" user-attr} "hi"))]
      (rf/reg-view* id user-fn)
      (let [out ((rf/view id))]
        (is (= "div" (.-type out)))
        (is (= user-attr (source-coord out))
            "user-supplied data-rf2-source-coord survives the wrap-view pass")))))

(defn assert-source-coord-fragment-exempt
  "A Fragment-rooted view is on the documented exemption list — the
  cloneElement injection is skipped, no attribute lands."
  [{:keys [substrate-kw name]}]
  (testing (str name " — source-coord: Fragment root is exempt")
    (let [id      (mint-kw substrate-kw "sc-fragment")
          Frag    (.-Fragment React)
          user-fn (fn [] (React/createElement
                          Frag nil
                          (React/createElement "p" nil "a")
                          (React/createElement "p" nil "b")))]
      (rf/reg-view* id user-fn)
      (let [out ((rf/view id))]
        (is (identical? Frag (.-type out)) "Fragment root preserved as element type")
        (is (nil? (source-coord out)) "no data-rf2-source-coord on the Fragment root")))))

(defn assert-source-coord-format-shape
  "The attribute value is exactly <ns>:<sym>:<line>:<col> — a programmatic
  reg-view* with no macro-captured coords degrades to <ns>:<sym>:?:?.

  This split (asserting the format independently of the presence test)
  closes rf2-sx77q G2: R/S carried a dedicated format-shape deftest the
  React adapters lacked."
  [{:keys [substrate-kw name]}]
  (testing (str name " — source-coord: attribute format is <ns>:<sym>:<line>:<col>")
    (let [id      (mint-kw substrate-kw "sc-format")
          user-fn (fn [] (React/createElement "i" #js {} "x"))]
      (rf/reg-view* id user-fn)
      (let [out  ((rf/view id))
            attr (source-coord out)]
        (is (string? attr))
        (let [parts (str/split attr #":")]
          (is (= 4 (count parts)) "exactly four colon-separated segments")
          (is (= (namespace id) (first parts)) "first segment is the id keyword's namespace")
          (is (= (clojure.core/name id) (second parts)) "second segment is the id keyword's name")
          ;; Programmatic reg-view* carries no macro coords → `?:?`.
          (is (= "?" (nth parts 2)) "third segment is `?` for a programmatic reg-view*")
          (is (= "?" (nth parts 3)) "fourth segment is `?` for a programmatic reg-view*"))))))

;; ===========================================================================
;; view-id (data-rf-view) stamping (Spec 006 §View tagging contract)
;; ===========================================================================

(defn assert-view-id-tags-dom-root
  "A DOM-tag root carries BOTH data-rf-view AND data-rf2-source-coord."
  [{:keys [substrate-kw name]}]
  (testing (str name " — view-id: tags a DOM root with data-rf-view")
    (let [id      (mint-kw substrate-kw "view-dom-root")
          user-fn (fn [] (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* id user-fn)
      (let [out ((rf/view id))]
        (is (= "span" (.-type out)) "root element type preserved")
        (is (= (str id) (view-attr out))
            "data-rf-view value is (str id) — leading-colon preserved")
        (is (string? (source-coord out))
            "data-rf2-source-coord still present (parity contract)")))))

(defn assert-view-id-fragment-exempt
  "A Fragment root is exempt for view-id too."
  [{:keys [substrate-kw name]}]
  (testing (str name " — view-id: Fragment root is exempt")
    (let [id      (mint-kw substrate-kw "view-fragment")
          Frag    (.-Fragment React)
          user-fn (fn [] (React/createElement
                          Frag nil (React/createElement "p" nil "a")))]
      (rf/reg-view* id user-fn)
      (let [out ((rf/view id))]
        (is (identical? Frag (.-type out)))
        (is (nil? (view-attr out)) "no data-rf-view on Fragment root (exempt)")
        (is (nil? (source-coord out)) "no data-rf2-source-coord on Fragment root (parity)")))))

(defn assert-view-id-user-supplied-wins
  "A user-supplied data-rf-view is not clobbered."
  [{:keys [substrate-kw name]}]
  (testing (str name " — view-id: user-supplied attribute wins")
    (let [id        (mint-kw substrate-kw "view-user-attr")
          user-attr "stamped:by-user"
          user-fn   (fn [] (React/createElement
                            "div" #js {"data-rf-view" user-attr} "hi"))]
      (rf/reg-view* id user-fn)
      (let [out ((rf/view id))]
        (is (= user-attr (view-attr out))
            "user-supplied data-rf-view survives the wrap-view pass")))))

(defn assert-wrap-view-injects-explicit-coords
  "wrap-view called directly with explicit {:line :column} metadata
  returns a fn whose output carries data-rf-view alongside
  data-rf2-source-coord built from the supplied coords. Pins the direct
  wrap-view seam (the macro-captured-coords path) independently of the
  programmatic reg-view* path above."
  [{:keys [wrap-view substrate-kw name]}]
  (testing (str name " — view-id: wrap-view injects explicit line/col coords")
    (let [id          (mint-kw substrate-kw "view-explicit-coords")
          out-from-fn (atom nil)
          wrapped     (wrap-view id {:line 42 :column 7}
                                 (fn []
                                   (reset! out-from-fn :ran)
                                   (React/createElement "div" #js {} "x")))]
      (is (fn? wrapped) "wrap-view returns a fn")
      (let [out (wrapped)]
        (is (= :ran @out-from-fn) "the wrapped user-fn ran")
        (is (= (str id) (view-attr out))
            "wrap-view's cloneElement injected data-rf-view = (str id)")
        (let [coord (source-coord out)]
          (is (string? coord) "data-rf2-source-coord present")
          (is (str/ends-with? coord ":42:7")
              "the explicit {:line 42 :column 7} coords land in the attribute"))))))

;; ===========================================================================
;; React `:key` parity (Spec 006 §Source-coord annotation — CRITICAL key
;; preservation note) — rf2-pt0u2, follow-up to rf2-1anbp
;;
;; rf2-1anbp pinned the Reagent path: a call-site ^{:key} propagates to
;; React. The React-hook substrates (UIx / Helix) take a different route —
;; the React `:key` rides the substrate's own `createElement` / `$` at the
;; call site, NOT Reagent's hiccup-metadata extraction. The hazard that
;; remains is the wrap-view pass itself: `inject-source-coord-attr` and
;; `append-unmount-sentinel` both `React/cloneElement` the user's root
;; output, and `cloneElement` is documented to preserve the `key` slot
;; (see spine.cljs `inject-source-coord-attr` / `append-unmount-sentinel`
;; docstrings — "SAME `type` and `key` slots"). This assertion pins that
;; preservation by test rather than by argument: a seq of registered views,
;; each whose root element carries a distinct per-item key, must emerge from
;; the wrap-view passes with each key intact and all keys distinct (no
;; collision). Parameterised ⇒ a gap on UIx is a gap on Helix.
;; ===========================================================================

(defn assert-reg-view-react-key-preserved
  "rf2-pt0u2: a registered view whose root React element carries a `:key`
  emerges from wrap-view's cloneElement passes (source-coord injection +
  unmount-sentinel append) with that key intact. Across a seq of views
  carrying distinct per-item keys the keys stay distinct — no collision.
  Headless: `((rf/view id))` runs the full wrap-view path under node-test
  (goog.DEBUG true), so the source-coord attribute also lands, proving the
  cloneElement passes actually executed and the key survived them."
  [{:keys [substrate-kw name]}]
  (testing (str name " — React :key: per-item keys survive the wrap-view passes, stay distinct")
    (let [n        3
          rendered (for [i (range n)
                         :let [id      (mint-kw substrate-kw (str "react-key-" i))
                               item-key (str "rk-" i)
                               user-fn (fn [] (React/createElement
                                               "li" #js {:key item-key} (str "item " i)))]]
                     (do (rf/reg-view* id user-fn)
                         {:item-key item-key :out ((rf/view id))}))
          rendered (vec rendered)]
      (doseq [{:keys [item-key out]} rendered]
        (is (some? out) "registered view returned a non-nil React element")
        (is (= "li" (.-type out)) "root element type preserved through the wrap-view passes")
        (is (= item-key (.-key out))
            (str "the call-site React :key (" item-key ") survives cloneElement "
                 "(source-coord injection + unmount-sentinel append)"))
        (is (string? (source-coord out))
            "data-rf2-source-coord present — the wrap-view pass ran, so key-preservation is meaningful"))
      (let [keys (mapv :item-key rendered)]
        (is (= n (count (distinct keys)))
            (str "all " n " per-item keys are distinct after the wrap-view passes — no collision; got "
                 (pr-str keys)))))))

;; ===========================================================================
;; frame-context corrupted `_currentValue` (Spec 009 §Error contract) — G4
;; ===========================================================================

(defn assert-frame-context-corrupted
  "Corruption-detection + recovery for a non-keyword `_currentValue` on
  the shared frame-context. Pinned on U/H per the audit; this is the
  shared, parameterised version (rf2-sx77q G4). The corruption path lives
  in `re-frame.adapter.context/function-component-current-frame`, which
  both React adapters wire into their `:adapter/current-frame` slot."
  [{:keys [substrate-kw name]}]
  (testing (str name " — frame-context: corrupted _currentValue emits + recovers")
    (let [lk       (keyword "re-frame.adapter.react-shared-suite"
                            (str "fc-" (clojure.core/name substrate-kw)))
          original (.-_currentValue ^js adapter-context/frame-context)
          traces   (atom [])]
      (trace-tooling/register-listener! lk (fn [ev] (swap! traces conj ev)))
      (try
        (testing "nil _currentValue: error trace fires; resolves to :rf/default"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) nil)
          (is (= :rf/default (adapter-context/function-component-current-frame))
              "falls through to :rf/default (recovery preserved)")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs)) "one :rf.error/frame-context-corrupted event fired")
            (is (= :error (:op-type (first errs))) ":op-type is :error per Spec 009")
            (is (= :replaced-with-default (:recovery (first errs)))
                ":recovery is :replaced-with-default — fall-through preserved")
            (is (= :nil (-> errs first :tags :type)) ":tags :type names the corrupted shape")))
        (testing "number _currentValue: error trace fires; resolves to :rf/default"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) 42)
          (is (= :rf/default (adapter-context/function-component-current-frame))
              "falls through to :rf/default")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs)) "one error trace per corrupted read")
            (is (= :number (-> errs first :tags :type)))
            (is (= 42 (-> errs first :tags :received)) ":tags :received echoes the offending value")))
        (testing "routed read via rf/current-frame-id also recovers"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) "")
          (is (= :rf/default (rf/current-frame-id))
              "adapter-routed read recovers to :rf/default")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one error fired through the adapter-routed path")
            (is (= :empty-string (-> errs first :tags :type))
                ":tags :type distinguishes empty-string from a populated string")))
        (finally
          (trace-tooling/unregister-listener! lk)
          (set! (.-_currentValue ^js adapter-context/frame-context) original))))))

;; ===========================================================================
;; frame-provider CORE branches (rf2-7kjz8 / rf2-z7hfp) — folded from UIx's
;; uix_frame_provider_branches_cljs_test.cljs and Helix's
;; helix_frame_provider_children_cljs_test.cljs.
;;
;; rf2-z7hfp — MOVE THE SEAM UP. These assertions pin the substrate-
;; agnostic frame-resolution + children-coercion logic, which now lives
;; in `re-frame.substrate.spine/build-frame-provider-element` (the shared
;; CORE the native per-substrate `frame-provider` component delegates to).
;; Previously they invoked each adapter's public `frame-provider` DIRECTLY
;; as a CLJS fn (the cfg `:frame-provider` slot) because that surface WAS a
;; plain re-exported spine fn. With the seam moved up the public surface is
;; a NATIVE substrate component (`defui` / `defnc`) that is NOT directly
;; CLJS-invocable (UIx's `glue-args` would read nil; Helix's
;; `extract-cljs-props` throws in dev on a map). So the shape assertions
;; target the core builder directly — including the rf2-7kii2 JS-array
;; children branch the trailing-`$`-children unification added — and the
;; END-TO-END `$`-shape propagation (the formerly-bespoke-patched class)
;; is pinned by each adapter's `frame-provider-trailing-children-propagate-
;; frame` DOM regression test (rf2-9ok1s / rf2-8svnm / rf2-7kii2). Both
;; halves are substrate-shared: the core logic here, the native-shell-
;; under-`$` behaviour in the DOM twins.
;;
;; Naming follows the UIx-direction per rf2-uqlce: `provider-element-
;; frame-kw` / `provider-element-children` describe what the slot
;; SEMANTICALLY MEANS (the frame-kw the Context.Provider hands down vs the
;; children prop).
;;
;; Substrate-agnostic: no cfg `:frame-provider` needed — `name` only, for
;; the assertion message.
;; ===========================================================================

(defn- provider-element-frame-kw
  "Pull the `:value` prop off the React element returned by
  `build-frame-provider-element` — the frame keyword the surrounding
  Context.Provider will hand down to `use-context` consumers."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "value")))

(defn- provider-element-children
  "Pull the `children` prop off the React element returned by
  `build-frame-provider-element`. React normalises a single-element
  children to the element directly; multi-element children come through
  as a JS array."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "children")))

(defn assert-frame-provider-missing-frame-falls-through-to-default
  "(build-frame-provider-element nil [...]) — no frame at all — falls
  through to :rf/default per rf2-sixo. The provider-element's `:value`
  slot carries :rf/default."
  [{:keys [name]}]
  (testing (str name " — frame-provider core: missing frame falls through to :rf/default")
    (let [el (spine/build-frame-provider-element nil [:fake-child-a :fake-child-b])]
      (is (some? el) "build-frame-provider-element returned a React element")
      (is (= :rf/default (provider-element-frame-kw el))
          "missing frame defaulted to :rf/default"))))

(defn assert-frame-provider-nil-frame-falls-through-to-default
  "(build-frame-provider-element nil [...]) — explicit nil frame — falls
  through to :rf/default. The `(or frame-kw :rf/default)` clause covers
  both the missing and nil cases."
  [{:keys [name]}]
  (testing (str name " — frame-provider core: nil frame falls through to :rf/default")
    (let [el (spine/build-frame-provider-element nil [:fake-child])]
      (is (some? el))
      (is (= :rf/default (provider-element-frame-kw el))
          "nil frame defaulted to :rf/default"))))

(defn assert-frame-provider-named-frame-preserved
  "A supplied frame keyword is preserved on the provider element's value
  slot. Sanity-check counterpart to the default-fallback assertions."
  [{:keys [name]}]
  (testing (str name " — frame-provider core: named frame keyword preserved")
    (let [el (spine/build-frame-provider-element :tenant-a [:fake-child])]
      (is (= :tenant-a (provider-element-frame-kw el))
          "frame :tenant-a flows through to the provider's value slot"))))

(defn assert-frame-provider-single-child-coerced-to-vector
  "(build-frame-provider-element :session child-a) — a single child (NOT
  a collection, e.g. Helix's lone trailing `$` child) — does not throw and
  is coerced to a one-element children sequence by the core's `:else`
  normalisation branch. Pins the single-child coercion."
  [{:keys [name]}]
  (testing (str name " — frame-provider core: single child coerced to vector")
    (let [single-child :fake-single-child-marker
          el (spine/build-frame-provider-element :session single-child)]
      (is (some? el) "build-frame-provider-element didn't throw on a non-sequential child")
      (is (= :session (provider-element-frame-kw el)))
      ;; React normalises single-element children to the element value;
      ;; the marker survives the coercion regardless of normalisation.
      (let [kids (provider-element-children el)]
        (is (or (= single-child kids)
                (and (some? kids)
                     (or (not (.-length kids))
                         (= 1 (.-length kids)))))
            "single child produced a one-element children slot")))))

(defn assert-frame-provider-sequential-children-preserved
  "A sequential children vector flows through the core's normalisation
  unchanged — multiple children are handed to the Provider as separate
  args."
  [{:keys [name]}]
  (testing (str name " — frame-provider core: sequential children preserved")
    (let [a :child-a
          b :child-b
          el (spine/build-frame-provider-element :session [a b])]
      (is (some? el))
      (is (= :session (provider-element-frame-kw el)))
      (let [kids (provider-element-children el)]
        (is (some? kids))
        (is (= 2 (.-length kids))
            "sequential children produced a two-element children slot")))))

(defn assert-frame-provider-js-array-children-spread
  "rf2-7kii2 — the native trailing-`$`-children idiom hands the core a JS
  ARRAY for multiple children (UIx's `(cljs.core/array …)` via `glue-args`,
  Helix's `(into-array …)` via `extract-cljs-props`). `array?` must be
  detected and the array SPREAD into positional args, so the children
  reach `createElement` as N distinct args (not a single nested-array
  child). This pins the array branch the trailing-children unification
  added."
  [{:keys [name]}]
  (testing (str name " — frame-provider core: JS-array children spread to positional args (rf2-7kii2)")
    (let [a :child-a
          b :child-b
          el (spine/build-frame-provider-element :session #js [a b])]
      (is (some? el))
      (is (= :session (provider-element-frame-kw el)))
      (let [kids (provider-element-children el)]
        (is (some? kids))
        (is (= 2 (.-length kids))
            "JS-array children spread to a two-element children slot")
        (is (= a (aget kids 0)) "first child preserved positionally")
        (is (= b (aget kids 1)) "second child preserved positionally")))))

;; ===========================================================================
;; warn-once fires-once (Spec 006 §Documented exemption) — G5
;; ===========================================================================

(defn assert-warn-once-fires-once
  "The per-id non-DOM-root warning fires EXACTLY once across renders,
  is keyed per-id (not global), and re-arms after the clear hook.

  Closes rf2-sx77q G5: the React adapters previously tested only the
  *clear* chain hook, never the fire-once semantics itself. The cache is
  spine-produced (`spine/make-warn-once-cache`) so the contract is
  substrate-identical — but it was pinned only on Reagent."
  [{:keys [wrap-view clear-warn! substrate-kw name]}]
  (testing (str name " — warn-once: fires exactly once per id across renders")
    (let [id          (mint-kw substrate-kw "warn-once-multi")
          non-dom     (fn [] (React/createElement React/Fragment #js {} "non-dom"))
          wrapped     (wrap-view id {} non-dom)
          phase-1     (with-captured-console-warn
                        (fn [] (dotimes [_ 5] (wrapped))))]
      (is (= 1 (count phase-1))
          (str "expected EXACTLY ONE warning across 5 renders; got "
               (count phase-1) ": " (pr-str phase-1)))
      (is (str/includes? (first phase-1) (clojure.core/name id))
          "the single warning names the offending view-id")
      (is (str/includes? (first phase-1) "data-rf2-source-coord")
          "the warning mentions the attribute that was skipped")
      ;; After the clear hook the same id re-arms and re-warns.
      (clear-warn!)
      (let [phase-2 (with-captured-console-warn (fn [] (wrapped)))]
        (is (= 1 (count phase-2))
            (str "after clear-warned-non-dom-roots! the same id re-emits; got "
                 (count phase-2) ": " (pr-str phase-2)))))))

(defn assert-warn-once-per-id-not-global
  "The warn-once contract is keyed per view-id: two distinct non-DOM
  roots each emit their OWN one-shot warning (not a single global gate)."
  [{:keys [wrap-view substrate-kw name]}]
  (testing (str name " — warn-once: per-id, not a global gate")
    (let [id-a    (mint-kw substrate-kw "warn-once-a")
          id-b    (mint-kw substrate-kw "warn-once-b")
          non-dom (fn [] (React/createElement React/Fragment #js {} "x"))
          w-a     (wrap-view id-a {} non-dom)
          w-b     (wrap-view id-b {} non-dom)
          warns   (with-captured-console-warn
                    (fn [] (w-a) (w-b) (w-a) (w-b)))]
      (is (= 2 (count warns))
          (str "expected EXACTLY TWO warnings (one per id) across 4 renders; got "
               (count warns) ": " (pr-str warns)))
      (is (some #(str/includes? % (clojure.core/name id-a)) warns) "id-a's warning fired")
      (is (some #(str/includes? % (clojure.core/name id-b)) warns) "id-b's warning fired"))))

;; ===========================================================================
;; write-after-destroy nil-container guard (rf2-ft2b / rf2-4tzyq)
;; ===========================================================================

(defn assert-write-after-destroy-guard
  "replace-container! with a nil container is a documented no-op +
  :rf.warning/write-after-destroy (the guard is substrate-agnostic;
  this pins it through the installed React adapter)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — write-after-destroy: nil container no-ops with warning")
    (let [fid      (mint-kw substrate-kw "race-frame")
          recorded (atom [])]
      (trace-tooling/register-listener! ::wad (fn [ev] (swap! recorded conj ev)))
      (try
        (is (nil? (substrate-adapter/replace-container! nil {:any :value}))
            "nil container is a documented no-op, not an exception")
        (rf/reg-frame fid {:doc "write-after-destroy reproducer frame"})
        (frame/destroy-frame! fid)
        (let [container (frame/app-db-container fid)]
          (is (nil? container) "app-db-container on a destroyed frame returns nil")
          (is (nil? (substrate-adapter/replace-container! container {:would-have :npe'd}))
              "writing through the nil container is a documented no-op"))
        (let [warns (filterv (fn [ev]
                               (and (= :warning (:op-type ev))
                                    (= :rf.warning/write-after-destroy (:operation ev))))
                             @recorded)]
          (is (pos? (count warns))
              ":rf.warning/write-after-destroy fired for the post-destroy write"))
        (finally
          (trace-tooling/unregister-listener! ::wad))))))

;; ===========================================================================
;; render-time parity contracts (Spec 001 §Hot-reload / Spec 004
;; §Render-tree primitives) — port of `*_parity` twins
;; ===========================================================================

(defn assert-view-re-register-causes-rerender
  "Hot-reload contract (Spec 001 §Hot-reload semantics rule 4): after
  re-registering a view, the next registry lookup returns the new body."
  [{:keys [substrate-kw name]}]
  (testing (str name " — hot-reload: re-registering a view flips the next render")
    (let [id       (mint-kw substrate-kw "parity-probe")
          observed (atom nil)]
      (rf/reg-view* id (fn [] (reset! observed :body-v1) :v1-output))
      ((rf/view id))
      (is (= :body-v1 @observed) "v1 body ran on first render")
      (rf/reg-view* id (fn [] (reset! observed :body-v2) :v2-output))
      ((rf/view id))
      (is (= :body-v2 @observed)
          "after re-registration, the next render mutates observed to v2"))))

(defn assert-current-render-key-anonymous-fallback
  "Render-key contract (Spec 004 §Render-tree primitives): outside a
  render, current-render-key returns the documented anonymous fallback
  [:rf.view/anonymous nil] and *render-key* is nil. Substrate-agnostic —
  pinned through each installed adapter."
  [{:keys [name]}]
  (testing (str name " — render-key: anonymous fallback outside any render")
    (is (= [:rf.view/anonymous nil] (views/current-render-key))
        "current-render-key reads the anonymous fallback outside any render")
    (is (nil? views/*render-key*)
        "*render-key* is nil outside any render cycle")))

(defn assert-wrap-view-callable-dispatches-to-user-fn
  "wrap-view is a public fn (Spec 006) that returns a callable; invoking
  it runs the user fn. Pins the adapter's wrap-view seam independently of
  the cloneElement output inspection."
  [{:keys [wrap-view substrate-kw name]}]
  (testing (str name " — wrap-view: returns a callable that runs the user fn")
    (let [id          (mint-kw substrate-kw "parity-sample")
          out-from-fn (atom nil)
          wrapped     (wrap-view id {:line 42 :column 7}
                                 (fn [] (reset! out-from-fn :ran) nil))]
      (is (fn? wrapped) "wrap-view returns a fn")
      (wrapped)
      (is (= :ran @out-from-fn) "the wrapped fn invokes the user fn"))))

;; ===========================================================================
;; reg-event metadata-interceptors warning (rf2-bbea) — port of `*_events`
;; ===========================================================================

(defn- collect-meta-interceptor-warnings
  "Listener recording :rf.warning/interceptors-in-metadata-map events."
  [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k
      (fn [ev]
        (when (and (= :warning (:op-type ev))
                   (= :rf.warning/interceptors-in-metadata-map (:operation ev)))
          (swap! a conj ev))))
    a))

(def ^:private noop-icpt
  {:id :test/noop :before identity :after identity})

(defn assert-reg-event-warns-on-meta-interceptors
  "reg-event-{db,fx,ctx} warn when :interceptors is mistakenly placed
  inside the metadata map — observed under the installed adapter
  (rf2-bbea / rf2-ta4b5). Pins the registrar + trace tier compose with
  the React adapter's late-bind hook stack."
  [{:keys [substrate-kw name]}]
  (let [db-id  (mint-kw substrate-kw "events-db-bad")
        fx-id  (mint-kw substrate-kw "events-fx-bad")
        ctx-id (mint-kw substrate-kw "events-ctx-bad")]
    (testing (str name " — reg-event-db warns on metadata-map :interceptors")
      (let [warns (collect-meta-interceptor-warnings ::db-warn)]
        (rf/reg-event-db db-id
          {:doc "Wrongly-shaped." :interceptors [noop-icpt]}
          (fn [db _] db))
        (trace-tooling/unregister-listener! ::db-warn)
        (is (= 1 (count @warns)))
        (let [t (:tags (first @warns))]
          (is (= "reg-event-db" (:reg-fn t)))
          (is (= db-id (:id t))))))
    (testing (str name " — reg-event-fx warns on metadata-map :interceptors")
      (let [warns (collect-meta-interceptor-warnings ::fx-warn)]
        (rf/reg-event-fx fx-id
          {:interceptors [noop-icpt]}
          (fn [_ _] {:db {}}))
        (trace-tooling/unregister-listener! ::fx-warn)
        (is (= 1 (count @warns)))
        (is (= "reg-event-fx" (:reg-fn (:tags (first @warns)))))))
    (testing (str name " — reg-event-ctx warns on metadata-map :interceptors")
      (let [warns (collect-meta-interceptor-warnings ::ctx-warn)]
        (rf/reg-event-ctx ctx-id
          {:interceptors [noop-icpt]}
          (fn [ctx] ctx))
        (trace-tooling/unregister-listener! ::ctx-warn)
        (is (= 1 (count @warns)))
        (is (= "reg-event-ctx" (:reg-fn (:tags (first @warns)))))))))

(defn assert-reg-event-positional-interceptors-silent
  "Interceptors in the positional slot do NOT warn (rf2-bbea)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — positional interceptors stay silent")
    (let [warns (collect-meta-interceptor-warnings ::quiet)]
      (rf/reg-event-db (mint-kw substrate-kw "events-quiet-1")
        [noop-icpt]
        (fn [db _] db))
      (rf/reg-event-db (mint-kw substrate-kw "events-quiet-2")
        {:doc "metadata only"}
        [noop-icpt]
        (fn [db _] db))
      (rf/reg-event-db (mint-kw substrate-kw "events-quiet-3")
        {:doc "metadata only, no positional interceptors"}
        (fn [db _] db))
      (trace-tooling/unregister-listener! ::quiet)
      (is (zero? (count @warns))))))

;; ===========================================================================
;; render-to-string + late-bind chain wiring (rf2-gc5v9 / rf2-y9spn /
;; rf2-4z7bp) — port of `*_render_to_string`
;; ===========================================================================

(defn- a-mock-emitter
  "Toy hiccup → HTML emitter so the install-path test can exercise
  set-hiccup-emitter! without dragging the full SSR artefact in."
  [render-tree _opts]
  (str "<mock>" (pr-str render-tree) "</mock>"))

(defn assert-render-to-string-throws-with-no-emitter
  "Before the emitter is installed, render-to-string throws ExceptionInfo
  whose ex-message is ':rf.error/no-hiccup-emitter-bound' and whose
  ex-data carries :reason + :render-tree (rf2-gc5v9 / rf2-y9spn)."
  [{:keys [set-emitter! render-to-string name]}]
  (testing (str name " — render-to-string throws when no emitter is installed")
    (set-emitter! nil)
    (let [tree   [:div "smoke"]
          thrown (try (render-to-string tree {}) nil
                      (catch :default e e))]
      (is (some? thrown) "render-to-string threw when no emitter was installed")
      (is (= ":rf.error/no-hiccup-emitter-bound" (.-message thrown))
          "ex-message names the error keyword")
      (let [data (ex-data thrown)]
        (is (some? data) "the thrown value carries ex-data")
        (is (string? (:reason data)) ":reason key is a string")
        (is (= tree (:render-tree data)) ":render-tree key carries the caller's tree")))))

(defn assert-render-to-string-returns-html-after-direct-install
  "After (set-hiccup-emitter! emitter-fn), render-to-string returns the
  emitter's output — the direct-install path (rf2-gc5v9 / rf2-y9spn)."
  [{:keys [set-emitter! render-to-string name]}]
  (testing (str name " — render-to-string returns HTML after direct install")
    (set-emitter! a-mock-emitter)
    (let [tree [:div "ok"]
          html (render-to-string tree {})]
      (is (string? html) "render-to-string returns a string after set-hiccup-emitter!")
      (is (str/starts-with? html "<mock>") "the installed emitter is what render-to-string invokes")
      (is (str/includes? html (pr-str tree)) "the installed emitter received the render-tree"))
    (set-emitter! nil)))

(defn assert-set-hiccup-emitter-published-through-chain
  "The adapter chains its set-hiccup-emitter! into the
  `:reagent/set-hiccup-emitter!` late-bind hook at ns-load (rf2-4z7bp /
  rf2-y9spn). Driving the hook installs the emitter into THIS adapter's
  slot so SSR's `re-frame.ssr.emit` ns-load auto-wires render-to-string."
  [{:keys [set-emitter! render-to-string name]}]
  (testing (str name " — set-hiccup-emitter! published through the late-bind chain")
    (let [hook-fn (late-bind/get-fn :reagent/set-hiccup-emitter!)]
      (is (some? hook-fn)
          "the chained hook is registered after the adapter ns has loaded")
      (set-emitter! nil)
      (try
        (is (thrown? :default (render-to-string [:div] {}))
            "precondition: emitter cleared")
        ;; Drive the chained hook — fans across every loaded React-shaped
        ;; adapter; the slot we care about is this adapter's.
        (hook-fn a-mock-emitter)
        (let [html (render-to-string [:div "via-chain"] {})]
          (is (str/starts-with? html "<mock>")
              "the chained hook wired this adapter's emitter slot"))
        (finally
          ;; Reset every loaded sibling adapter slot, not just this one.
          (hook-fn nil))))))

;; ===========================================================================
;; late-bind hook publication set (rf2-rrwwy / rf2-jz15y) —
;; port of `*_late_bind_publication`
;; ===========================================================================

(def ^:private expected-hook-keys
  "Every late-bind hook the React adapters publish at ns-load. Routed
  `:adapter/*` hooks first, then chained hooks. Per rf2-jicu2 the
  reactive-atom hooks are excluded; per rf2-334d9 :adapter/after-render
  IS published. Identical set for UIx (rf2-rrwwy) and Helix (rf2-jz15y)."
  #{:adapter/add-on-dispose!
    :adapter/after-render
    :adapter/current-frame
    :adapter/dispose!
    :adapter/wrap-view
    :adapter/clear-warn-once-caches!
    :reagent/set-hiccup-emitter!})

(defn assert-adapter-publishes-expected-hook-set
  "Every hook key the adapter publishes at ns-load is registered in the
  late-bind table after the adapter ns has loaded (rf2-rrwwy / rf2-jz15y).
  A future refactor that drops or renames a hook trips this test."
  [{:keys [name]}]
  (testing (str name " — adapter publishes the expected late-bind hook set")
    (doseq [k expected-hook-keys]
      (is (some? (late-bind/get-fn k))
          (str "expected the " name " adapter to publish " k
               " through the late-bind hook table at ns-load")))))

(defn assert-adapter-hooks-cross-checked-against-directory
  "Every hook key in expected-hook-keys appears in the authoritative
  late-bind directory with this adapter's producer-ns listed as one of
  its producers (rf2-rrwwy / rf2-jz15y)."
  [{:keys [producer-ns name]}]
  (testing (str name " — adapter hooks cross-checked against the late-bind directory")
    (doseq [k expected-hook-keys]
      (let [entry     (some (fn [e] (when (= k (:key e)) e)) directory/hooks)
            producers (let [p (:producer-ns entry)]
                        (if (sequential? p) p [p]))]
        (is (some? entry) (str "no directory entry for " k))
        (is (some #{producer-ns} producers)
            (str "directory entry for " k " does not list " producer-ns
                 " as a producer; producers: " (pr-str producers)))))))

;; ===========================================================================
;; chained clear-warn-once-caches! end-to-end (rf2-e54wc / rf2-ovbxk) —
;; port of `*_clear_warn_once_chain`
;; ===========================================================================

(defn- non-dom-element
  "React element whose `.-type` is NOT a string — wrap-view's
  source-coord annotator classifies it as a non-DOM root and routes it
  to the warn-once path (Spec 006 §Source-coord annotation)."
  []
  (React/createElement React/Fragment #js {} "non-dom"))

(defn assert-chained-clear-warn-once-empties-cache
  "The chained :adapter/clear-warn-once-caches! hook (registered via
  spine/install-clear-warn-once-step! at adapter ns-load) clears the
  adapter's warn-cache: after one warn-once fire the same id is silenced;
  after the chained hook fires the same id re-warns (rf2-e54wc / rf2-ovbxk)."
  [{:keys [wrap-view substrate-kw name]}]
  (testing (str name " — chained clear-warn-once-caches! empties the warn-cache")
    (let [target-id    (mint-kw substrate-kw "clear-warn-shared")
          wrapped      (wrap-view target-id {} (fn user-fn [] (non-dom-element)))
          phase-1-ws   (with-captured-console-warn
                         (fn [] (dotimes [_ 3] (wrapped))))]
      (is (= 1 (count phase-1-ws))
          (str "phase-1 sanity: warn-once fires exactly once WITHIN a single phase; got "
               (count phase-1-ws) ": " (pr-str phase-1-ws)))
      (let [chained-hook (late-bind/get-fn :adapter/clear-warn-once-caches!)]
        (is (some? chained-hook) "precondition: the chained hook is registered")
        (chained-hook)
        (let [phase-2-ws (with-captured-console-warn (fn [] (wrapped)))]
          (is (= 1 (count phase-2-ws))
              (str "phase-2 must re-emit the warning for the same id AFTER the "
                   "chained :adapter/clear-warn-once-caches! hook fires. Got "
                   (count phase-2-ws) ": " (pr-str phase-2-ws))))))))

(defn assert-clear-warned-non-dom-roots-resets-directly
  "Calling the adapter's clear-warned-non-dom-roots! thunk directly also
  resets the cache — the seam the chained hook invokes (rf2-e54wc / rf2-ovbxk)."
  [{:keys [wrap-view clear-warn! substrate-kw name]}]
  (testing (str name " — clear-warned-non-dom-roots! resets the cache directly")
    (let [target-id (mint-kw substrate-kw "clear-warn-direct")
          wrapped   (wrap-view target-id {} (fn user-fn [] (non-dom-element)))
          ws-1      (with-captured-console-warn (fn [] (wrapped)))]
      (is (= 1 (count ws-1)) "first emission fires")
      (clear-warn!)
      (let [ws-2 (with-captured-console-warn (fn [] (wrapped)))]
        (is (= 1 (count ws-2))
            (str "after clear-warned-non-dom-roots! the same id re-emits. Got "
                 (count ws-2) ": " (pr-str ws-2)))))))

;; ===========================================================================
;; routing pipeline (Spec 012) — port of `*_routing`
;;
;; This suite requires the routing fixture to reset the route-registration
;; counter (`routing/reset-counters!`) per test — wire `:init-fn
;; routing/reset-counters!` into the entry-file fixture.
;; ===========================================================================

(defn- route-kw
  "Mint a substrate-scoped route id keyword."
  [substrate-kw nm]
  (keyword (str "route." (clojure.core/name substrate-kw)) nm))

(defn- route-path
  "Mint a substrate-scoped URL path so two adapters' suites don't collide
  on the URL-keyed route registry."
  [substrate-kw suffix]
  (str "/" (clojure.core/name substrate-kw) suffix))

(defn assert-routing-handle-url-change
  "URL changes are events / reading the route is a sub (Spec 012):
  :rf.route/transitioned drives the :rf/route slice; subscriptions
  resolve; :on-match dispatches; fresh nav-token per navigation."
  [{:keys [substrate-kw name]}]
  (testing (str name " — routing: :rf.route/transitioned drives the slice")
    (let [f          (rf/make-frame {:doc "isolated frame for this test"})
          home       (route-kw substrate-kw "home")
          article    (route-kw substrate-kw "article")
          load-ev    (mint-kw substrate-kw "article-load")
          id-sub     (mint-kw substrate-kw "route-id")
          params-sub (mint-kw substrate-kw "route-params")
          art-path   (route-path substrate-kw "/articles/:id")]
      (rf/reg-route home {:path (route-path substrate-kw "/home")})
      (rf/reg-route article
                    {:path     art-path
                     :params   [:map [:id :string]]
                     :on-match [[load-ev]]})
      (rf/reg-event-db load-ev (fn [db _] (assoc db :article-loaded? true)))
      (rf/reg-sub id-sub     (fn [db _] (get-in db [:rf/runtime :routing :current :id])))
      (rf/reg-sub params-sub (fn [db _] (get-in db [:rf/runtime :routing :current :params])))

      (rf/dispatch-sync [:rf.route/transitioned (route-path substrate-kw "/articles/intro")] {:frame f})
      (is (= article (rf/subscribe-once f [id-sub]))
          ":rf.route/id sub resolves under the adapter")
      (is (= {:id "intro"} (rf/subscribe-once f [params-sub]))
          ":rf.route/params sub resolves under the adapter")
      (is (true? (:article-loaded? (rf/app-db-value f)))
          ":on-match dispatched and ran")

      (rf/dispatch-sync [:rf.route/transitioned (route-path substrate-kw "/articles/welcome")] {:frame f})
      (is (= {:id "welcome"} (rf/subscribe-once f [params-sub]))
          "new params land in the slice on subsequent navigation")
      (is (some? (get-in (rf/app-db-value f) [:rf/runtime :routing :current :nav-token]))
          "fresh nav-token allocated on each full navigation"))))

(defn assert-routing-multi-frame
  "Multi-frame routing (Spec 012 §Multi-frame routing): two frames carry
  independent :rf/route slices over a shared registry."
  [{:keys [substrate-kw name]}]
  (testing (str name " — routing: two frames carry independent :rf/route slices")
    (let [sk2      (keyword (str (clojure.core/name substrate-kw) "2"))
          home     (route-kw sk2 "home")
          articles (route-kw sk2 "articles")
          article  (route-kw sk2 "article")
          route-sub (mint-kw sk2 "route")]
      (rf/reg-route home     {:path (route-path sk2 "/")})
      (rf/reg-route articles {:path (route-path sk2 "/articles")})
      (rf/reg-route article  {:path   (route-path sk2 "/articles/:id")
                              :params [:map [:id :string]]})
      (rf/reg-sub route-sub (fn [db _] (get-in db [:rf/runtime :routing :current])))

      (let [left  (rf/make-frame {:doc "left tab frame"})
            right (rf/make-frame {:doc "right tab frame"})]
        (rf/dispatch-sync [:rf.route/transitioned (route-path sk2 "/articles")] {:frame left})
        (rf/dispatch-sync [:rf.route/transitioned (route-path sk2 "/articles/intro")] {:frame right})

        (let [left-route  (rf/subscribe-once left  [route-sub])
              right-route (rf/subscribe-once right [route-sub])]
          (is (= articles (:id left-route)) "left frame's :rf/route is the collection route")
          (is (= article  (:id right-route)) "right frame's :rf/route is the article route")
          (is (= {} (:params left-route)) "left frame has no :params (collection route)")
          (is (= {:id "intro"} (:params right-route)) "right frame has the article id"))

        (rf/dispatch-sync [:rf.route/transitioned (route-path sk2 "/")] {:frame left})
        (is (= home (:id (rf/subscribe-once left [route-sub])))
            "left re-navigated to home")
        (is (= article (:id (rf/subscribe-once right [route-sub])))
            "right is unaffected by left's navigation")))))

;; ===========================================================================
;; headless runtime slice (dispatch / subs / with-frame / frame-bound-fn /
;; isolation / hot-reload / machines / error paths) — port of `*_runtime`
;; ===========================================================================

(defn assert-dispatch-sync
  "dispatch-sync runs an event-db handler under the installed adapter."
  [{:keys [name]}]
  (testing (str name " — dispatch-sync runs an event-db handler")
    (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/app-db-value :rf/default))))))

(defn assert-sub-chain
  "layer-1 + layer-2 subs return computed values under the adapter."
  [{:keys [name]}]
  (testing (str name " — layer-1 + layer-2 subs return computed values")
    (rf/reg-event-db :seed (fn [_ _] {:items [10 20 30]}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-sum  :<- [:items] (fn [items _] (reduce + items)))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 30] (rf/subscribe-once [:items])))
    (is (= 60         (rf/subscribe-once [:item-sum])))))

(defn assert-with-frame-binds-current-frame
  "with-frame binds *current-frame* in the body; falls back to :rf/default
  outside any binding."
  [{:keys [name]}]
  (testing (str name " — with-frame binds *current-frame*")
    (with-frame :left
      (is (= :left (rf/current-frame-id))))
    (testing "with-new-frame [sym expr] binds the symbol AND the dynamic var"
      (with-new-frame [f :right]
        (is (= :right f))
        (is (= :right (rf/current-frame-id)))))
    (testing "outside any binding the dynamic var falls back to :rf/default"
      (is (= :rf/default (rf/current-frame-id))))))

(defn assert-bound-fn-captures-frame
  "frame-bound-fn captures the current frame and re-binds it inside the body."
  [{:keys [name]}]
  (testing (str name " — frame-bound-fn captures the current frame")
    (rf/reg-frame :side {:doc "side frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/dispatch-sync [:seed 99] {:frame :side})
    (let [captured (with-frame :side (frame-bound-fn [] (rf/current-frame-id)))]
      (is (= :rf/default (rf/current-frame-id)))
      (is (= :side (captured))))))

(defn assert-multi-frame-state-isolation
  "Two frames carry independent app-db state, share the handler registry."
  [{:keys [name]}]
  (testing (str name " — two frames carry independent app-db state")
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event-db :counter/init (fn [_ [_ n]] {:count n}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :count inc)))
    (rf/reg-sub :count (fn [db _] (:count db)))
    (rf/dispatch-sync [:counter/init 10] {:frame :left})
    (rf/dispatch-sync [:counter/init 100] {:frame :right})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (is (= 12  (rf/subscribe-once :left  [:count])))
    (is (= 100 (rf/subscribe-once :right [:count])))
    (is (nil?  (rf/subscribe-once :rf/default [:count])))))

(defn assert-reactive-sub-tracks-changes
  "A subscription's deref reflects post-event state. The React adapters'
  containers are plain atoms; the subscribe layer wraps them with the
  spine's make-derived-value (IDeref+IWatchable), NOT a Reagent reaction."
  [{:keys [name]}]
  (testing (str name " — a subscription's deref reflects post-event state")
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:n])]
      (is (= 0 @r))
      (rf/dispatch-sync [:inc])
      (is (= 1 @r) "the subscription observes the new value after :inc")
      (rf/dispatch-sync [:inc])
      (rf/dispatch-sync [:inc])
      (is (= 3 @r))
      (rf/unsubscribe [:n]))))

(defn assert-sub-hot-reload
  "Re-registering a sub flips the next subscribe-once to the new body."
  [{:keys [name]}]
  (testing (str name " — re-registering a sub flips the next subscribe-once")
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (is (= 7 (rf/subscribe-once [:answer])))
    (let [_pin (rf/subscribe [:answer])]
      (rf/reg-sub :answer (fn [db _] (* 10 (:n db))))
      (is (= 70 (rf/subscribe-once [:answer]))
          "the new sub body is in effect after re-registration")
      (rf/unsubscribe [:answer]))))

(defn assert-machine-transition
  "Pure machine-transition runs under the installed adapter."
  [{:keys [name]}]
  (testing (str name " — pure machine-transition runs")
    (let [m {:initial :red
             :data    {}
             :states  {:red    {:on {:tick {:target :green}}}
                       :green  {:on {:tick {:target :yellow}}}
                       :yellow {:on {:tick {:target :red}}}}}
          {s :re-frame.machines.result/snap}
          (machines/machine-transition m {:state :red :data {}} [:tick])]
      (is (= :green (:state s))))))

(defn assert-sub-exception-recovers-to-nil
  "A sub whose body throws emits :rf.error/sub-exception and resolves to
  nil under :replaced-with-default recovery."
  [{:keys [name]}]
  (testing (str name " — a throwing sub recovers to nil + emits :rf.error/sub-exception")
    (rf/reg-event-db :init (fn [_ _] {:items "broken"}))
    (rf/reg-sub :items (fn [db _] (:items db)))
    (rf/reg-sub :items-count :<- [:items]
      (fn [items _] (count (.something items))))
    (rf/dispatch-sync [:init])
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::sub-err (fn [ev] (swap! traces conj ev)))
      (let [v (rf/subscribe-once [:items-count])]
        (is (nil? v) "the sub returns nil under :replaced-with-default recovery"))
      (trace-tooling/unregister-listener! ::sub-err)
      (is (some (fn [ev] (= :rf.error/sub-exception (:operation ev))) @traces)
          "expected :rf.error/sub-exception trace"))))

;; ===========================================================================
;; :rf.view/rendered op (rf2-25zo2) — port of `*_view_rendered_op`
;; ===========================================================================

(defn- record-view-rendered! []
  (let [recorded (atom [])]
    (trace-tooling/register-listener! ::view-rendered-recorder
      (fn [ev]
        (when (= :rf.view/rendered (:operation ev))
          (swap! recorded conj ev))))
    recorded))

(defn assert-rf-view-rendered-fires-on-render
  ":rf.view/rendered fires on render — same emit site as every React
  adapter (the substrate-agnostic views.cljs frame-aware-view wrapper),
  same tag shape (rf2-25zo2)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — :rf.view/rendered fires on render with the expected tag shape")
    (let [id     (mint-kw substrate-kw "view-rendered-sample")
          traces (record-view-rendered!)]
      (rf/reg-view* id (fn [] (React/createElement "span" #js {} "ok")))
      ((rf/view id))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event fired")
        (is (= id (:rf.view/id t)) ":rf.view/id matches")
        (is (some? (:frame t)) ":frame present")
        (is (vector? (:rf.view/render-key t)) ":rf.view/render-key is a tuple"))
      (trace-tooling/unregister-listener! ::view-rendered-recorder))))

(defn assert-rf-view-rendered-attribution-in-cascade
  ":rf.view/rendered emitted inside a cascade carries :rf.view/cause-event-id +
  :rf.view/cause-subs sourced from the in-flight epoch capture buffer (rf2-25zo2)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — :rf.view/rendered in a cascade carries cause attribution")
    (let [n-sub      (mint-kw substrate-kw "view-rendered-n")
          view-id    (mint-kw substrate-kw "view-rendered-with-sub")
          cascade-ev (mint-kw substrate-kw "view-rendered-cascade")
          traces     (record-view-rendered!)]
      (rf/reg-sub n-sub (fn [_ _] 1))
      (rf/reg-view* view-id (fn [] (React/createElement "span" #js {} "x")))
      (let [render (rf/view view-id)]
        (rf/reg-event-fx cascade-ev
          (fn [_ _]
            @(rf/subscribe [n-sub])
            (render)
            {}))
        (rf/dispatch-sync [cascade-ev]))
      (let [ev (first (filter #(some? (get-in % [:tags :rf.view/cause-event-id])) @traces))]
        (is (some? ev) "at least one in-cascade :rf.view/rendered")
        (when ev
          (let [t (:tags ev)]
            (is (= cascade-ev (:rf.view/cause-event-id t)))
            (is (some #{n-sub} (:rf.view/cause-subs t))))))
      (trace-tooling/unregister-listener! ::view-rendered-recorder))))

(defn assert-rf-view-rendered-carries-render-args
  ":rf.view/rendered carries the view's positional render args/props under
  :rf.view/render-args (rf2-rpgq8). Substrate-agnostic — the args are
  captured by the views.cljs frame-aware-view wrapper that every adapter
  composes, so a direct `((rf/view id) arg…)` invocation surfaces them
  identically across Reagent / UIx / Helix. A no-arg render omits the slot
  (additive — existing :rf.view/rendered consumers are unaffected)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — :rf.view/rendered carries :rf.view/render-args")
    (let [id     (mint-kw substrate-kw "view-rendered-args-sample")
          traces (record-view-rendered!)]
      (rf/reg-view* id (fn [_a _b] (React/createElement "span" #js {} "ok")))
      ((rf/view id) {:label "hi"} 42)
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event fired")
        (is (= [{:label "hi"} 42] (:rf.view/render-args t))
            ":rf.view/render-args is the vector of positional render args"))
      (trace-tooling/unregister-listener! ::view-rendered-recorder))
    (testing "no-arg render omits the slot"
      (let [id     (mint-kw substrate-kw "view-rendered-no-args-sample")
            traces (record-view-rendered!)]
        (rf/reg-view* id (fn [] (React/createElement "span" #js {} "ok")))
        ((rf/view id))
        (let [ev (first @traces)
              t  (:tags ev)]
          (is (some? ev) "an :rf.view/rendered event fired")
          (is (not (contains? t :rf.view/render-args))
              "the slot is absent on a no-arg render (additive contract)"))
        (trace-tooling/unregister-listener! ::view-rendered-recorder)))))

(defn assert-rf-view-rendered-render-args-elided
  "PRIVACY (rf2-rpgq8 / Spec 009 §Privacy): render args are arbitrary user
  data, so :rf.view/render-args routes through the SAME emit-time elision
  chokepoint as every other user-data trace payload — the marks projection
  runs `elide-wire-value` against the frame's app-db elision registry. A
  schema-declared `{:sensitive? true}` path inside a render arg must reach
  the trace surface as the `:rf/redacted` sentinel, never raw. Marks
  artefact must be loaded for this to apply; substrate-agnostic."
  [{:keys [substrate-kw name]}]
  (testing (str name " — :rf.view/render-args sensitive paths elide at emit")
    (let [id     (mint-kw substrate-kw "view-rendered-args-sensitive")
          traces (record-view-rendered!)]
      ;; Declare [:auth :password] sensitive on this frame's app-db elision
      ;; registry via the schema path — the SAME registry :rf.event/db
      ;; consults. `reg-app-schema` records the schema; the populate call
      ;; hydrates the sensitive-declarations registry the walker reads.
      (rf/reg-app-schema [:auth]
                         [:map
                          [:username :string]
                          [:password {:sensitive? true} :string]])
      (rf/populate-sensitive-from-schemas!)
      (rf/reg-view* id (fn [_props] (React/createElement "span" #js {} "ok")))
      ;; Pass a render arg whose [:auth :password] leaf mirrors the
      ;; sensitive app-db path. The marks chokepoint elides it before
      ;; delivery to any listener / the wire.
      ((rf/view id) {:auth {:username "ada" :password "hunter2"}})
      (let [ev   (first @traces)
            t    (:tags ev)
            args (:rf.view/render-args t)]
        (is (some? ev) "an :rf.view/rendered event fired")
        (is (vector? args) ":rf.view/render-args present")
        (let [arg0 (first args)]
          (is (= :rf/redacted (get-in arg0 [:auth :password]))
              "the [:auth :password] leaf inside the render arg is redacted at emit")
          (is (= "ada" (get-in arg0 [:auth :username]))
              "a non-sensitive sibling leaf is preserved")))
      (trace-tooling/unregister-listener! ::view-rendered-recorder))))

;; ===========================================================================
;; make-derived-value per-arity contract (rf2-eoy63) —
;; port of `*_make_derived_value_arity_spec`
;; ===========================================================================

(defn- mk-source [adapter v] ((:make-state-container adapter) v))
(defn- mk-write! [adapter c v] ((:replace-container! adapter) c v))
(defn- mk-derive [adapter sources f] ((:make-derived-value adapter) sources f))

(defn assert-derived-value-arities
  "Per-arity pin for make-derived-value (rf2-eoy63): 0/1/2/≥3-arity paths
  + source-vector order preserved. Driven directly through the adapter map."
  [{:keys [adapter name]}]
  (testing (str name " — make-derived-value per-arity contract")
    (testing "0 sources — compute-fn called with no args"
      (let [derived (mk-derive adapter [] (fn [] ::seed))]
        (is (= ::seed @derived))))
    (testing "1 source — derefs source per recompute (layer-1 dominant)"
      (let [src     (mk-source adapter 7)
            derived (mk-derive adapter [src] (fn [a] (* a 10)))]
        (is (= 70 @derived))
        (mk-write! adapter src 8)
        (is (= 80 @derived))))
    (testing "2 sources — derefs both per recompute (layer-n dominant)"
      (let [a (mk-source adapter 3) b (mk-source adapter 4)
            derived (mk-derive adapter [a b] +)]
        (is (= 7 @derived))
        (mk-write! adapter a 100)
        (is (= 104 @derived))
        (mk-write! adapter b 200)
        (is (= 300 @derived))))
    (testing "3 sources — fallback (apply + mapv deref) path"
      (let [a (mk-source adapter 1) b (mk-source adapter 2) c (mk-source adapter 3)
            derived (mk-derive adapter [a b c] (fn [x y z] (+ x y z)))]
        (is (= 6 @derived))
        (mk-write! adapter a 10) (mk-write! adapter b 20) (mk-write! adapter c 30)
        (is (= 60 @derived))))
    (testing "4 sources — fallback path"
      (let [a (mk-source adapter :a) b (mk-source adapter :b)
            c (mk-source adapter :c) d (mk-source adapter :d)
            derived (mk-derive adapter [a b c d] (fn [w x y z] [w x y z]))]
        (is (= [:a :b :c :d] @derived))))
    (testing "argument order matches source-vector order"
      (let [s0 (mk-source adapter 100) s1 (mk-source adapter 1)
            derived (mk-derive adapter [s0 s1] -)]
        (is (= 99 @derived))))))

;; ===========================================================================
;; derived-value watch-baseline regression (rf2-66hb) —
;; port of `*_derived_value_baseline`
;; ===========================================================================

(defn- mk-subscribe [adapter container]
  (let [calls (atom [])
        unsub ((:subscribe-container adapter)
               container
               (fn [prev nu] (swap! calls conj [prev nu])))]
    ;; rf2-ee38b.1: derived values are now LAZY (no eager compute at
    ;; construction). Deref once at subscribe time to establish the watch
    ;; baseline — exactly what the real sub-cache does on subscribe (it
    ;; reads the subscription's initial value). Without this, the first
    ;; source change would notify against the `unset` sentinel rather than
    ;; the prior derived value, defeating the rf2-66hb no-spurious-first-
    ;; notify guarantee. Production's useSyncExternalStore likewise calls
    ;; getSnapshot (a deref) at subscribe.
    @container
    {:calls calls :unsub unsub}))

(defn assert-derived-baseline-projections
  "Watch-baseline regression (rf2-66hb): a derived projection that stays
  value-equal across a source update must NOT spuriously notify; real
  changes still notify exactly once. Covers odd?/count/key/boolean/vector
  projections."
  [{:keys [adapter name]}]
  (testing (str name " — derived watch-baseline: =-equal projections do not notify")
    (testing "(odd? x) stays true across 1 → 3"
      (let [src (mk-source adapter 1)
            derived (mk-derive adapter [src] odd?)
            {:keys [calls unsub]} (mk-subscribe adapter derived)]
        (mk-write! adapter src 3)
        (is (= [] @calls) "first source update where derived stays = must NOT notify")
        (mk-write! adapter src 4)
        (is (= 1 (count @calls)) "real change notifies once")
        (is (= [true false] (first @calls)))
        (unsub)))
    (testing "(count xs) stays 3 across [1 2 3] → [4 5 6]"
      (let [src (mk-source adapter [1 2 3])
            derived (mk-derive adapter [src] count)
            {:keys [calls unsub]} (mk-subscribe adapter derived)]
        (mk-write! adapter src [4 5 6])
        (is (= [] @calls) "first source update where count stays = must NOT notify")
        (mk-write! adapter src [4 5 6 7])
        (is (= 1 (count @calls)) "real change notifies once")
        (is (= [3 4] (first @calls)))
        (unsub)))
    (testing "(:k m) stays 1 across {:k 1 :other 2} → {:k 1 :other 99}"
      (let [src (mk-source adapter {:k 1 :other 2})
            derived (mk-derive adapter [src] :k)
            {:keys [calls unsub]} (mk-subscribe adapter derived)]
        (mk-write! adapter src {:k 1 :other 99})
        (is (= [] @calls) "first source update where (:k m) stays = must NOT notify")
        (mk-write! adapter src {:k 2 :other 99})
        (is (= 1 (count @calls)) "real change notifies once")
        (is (= [1 2] (first @calls)))
        (unsub)))
    (testing "(boolean (:logged-in? m)) stays true"
      (let [src (mk-source adapter {:logged-in? true})
            derived (mk-derive adapter [src] (fn [m] (boolean (:logged-in? m))))
            {:keys [calls unsub]} (mk-subscribe adapter derived)]
        (mk-write! adapter src {:logged-in? true :name "x"})
        (is (= [] @calls) "first source update where boolean projection stays = must NOT notify")
        (mk-write! adapter src {:logged-in? false})
        (is (= 1 (count @calls)) "real change notifies once")
        (is (= [true false] (first @calls)))
        (unsub)))
    (testing "(:items m) stays [1 2]"
      (let [src (mk-source adapter {:items [1 2] :n 5})
            derived (mk-derive adapter [src] :items)
            {:keys [calls unsub]} (mk-subscribe adapter derived)]
        (mk-write! adapter src {:items [1 2] :n 6})
        (is (= [] @calls) "first source update where vector projection stays = must NOT notify")
        (mk-write! adapter src {:items [1 2 3] :n 6})
        (is (= 1 (count @calls)) "real change notifies once")
        (is (= [[1 2] [1 2 3]] (first @calls)))
        (unsub)))))

(defn assert-derived-baseline-sequence
  "The contract holds across a sequence of updates: only real = changes
  emit (rf2-66hb)."
  [{:keys [adapter name]}]
  (testing (str name " — derived watch-baseline: only real = changes emit across a sequence")
    (let [src (mk-source adapter 0)
          derived (mk-derive adapter [src] odd?)
          {:keys [calls unsub]} (mk-subscribe adapter derived)]
      (mk-write! adapter src 2)   ;; even → even, no emit
      (mk-write! adapter src 4)   ;; even → even, no emit
      (mk-write! adapter src 5)   ;; even → odd, emit once
      (mk-write! adapter src 7)   ;; odd → odd, no emit
      (mk-write! adapter src 8)   ;; odd → even, emit once
      (is (= 2 (count @calls)))
      (is (= [false true] (first @calls)))
      (is (= [true false] (second @calls)))
      (unsub))))

(defn assert-derived-baseline-multi-source
  "Multi-source derived: each source's update recomputes; only = changes
  emit (rf2-66hb)."
  [{:keys [adapter name]}]
  (testing (str name " — derived watch-baseline: multi-source recompute only emits on =-change")
    (let [a (mk-source adapter 1) b (mk-source adapter 2)
          derived (mk-derive adapter [a b] (fn [x y] (+ x y)))
          {:keys [calls unsub]} (mk-subscribe adapter derived)]
      ;; baseline derived = 3
      (mk-write! adapter a 2)    ;; new sum 4, prev 3 → emit
      (mk-write! adapter b 1)    ;; new sum 3, prev 4 → emit
      (is (= 2 (count @calls)))
      (is (= [3 4] (first @calls)))
      (is (= [4 3] (second @calls)))
      (unsub))))

;; ===========================================================================
;; managed HTTP (Spec 014) — port of `*_http_managed`
;;
;; The http-managed suite requires the entry-file fixture to call
;; `http-managed/clear-all-in-flight!` before AND after each test (see the
;; per-substrate twin's fixture). The shared-suite fns assume a freshly
;; reset runtime with the adapter installed.
;; ===========================================================================

(defn assert-http-canned-success-default-reply
  "canned-success stub dispatches a default reply (Spec 014)."
  [{:keys [name]}]
  (testing (str name " — canned-success default reply addressing")
    (rf/reg-event-fx :article/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          (case (:kind reply)
            :success {:db {:article (:value reply)}}
            :failure {:db {:error (:failure reply)}})
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"} :decode :json}]]})))
    (rf/dispatch-sync [:article/load {:slug "hello"}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (is (= {:stubbed true} (:article (rf/app-db-value :rf/default)))
        "default-reply addressing routed the synthesised reply back to :article/load")))

(defn assert-http-canned-failure-on-failure
  "Explicit :on-failure routes the failure reply to the named handler."
  [{:keys [name]}]
  (testing (str name " — canned-failure explicit :on-failure")
    (rf/reg-event-fx :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request {:method :post :url "/auth/login"} :on-failure [:auth/login-error]}]]}))
    (rf/reg-event-db :auth/login-error (fn [db [_ payload]] (assoc db :auth-error payload)))
    (rf/dispatch-sync [:auth/login]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (rf/app-db-value :rf/default)]
      (is (= :failure (get-in db [:auth-error :kind])))
      (is (= :rf.http/transport (get-in db [:auth-error :failure :kind]))
          "default canned-failure :kind classifies as :rf.http/transport"))))

(defn assert-http-canned-success-on-success
  "Explicit :on-success routes the success reply to the named handler."
  [{:keys [name]}]
  (testing (str name " — canned-success explicit :on-success")
    (rf/reg-event-fx :article/load
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request {:method :get :url "/articles/hello"} :on-success [:article/loaded]}]]}))
    (rf/reg-event-db :article/loaded (fn [db [_ payload]] (assoc db :article payload)))
    (rf/dispatch-sync [:article/load]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (rf/app-db-value :rf/default)]
      (is (= :success (get-in db [:article :kind])))
      (is (= {:stubbed true} (get-in db [:article :value]))))))

(defn assert-http-silenced-reply
  "Explicit :on-success nil swallows the reply silently."
  [{:keys [name]}]
  (testing (str name " — :on-success nil swallows the reply")
    (let [seen (atom 0)]
      (rf/reg-event-fx :ping
        (fn [_ _]
          (swap! seen inc)
          {:fx [[:rf.http/managed {:request {:url "/ping"} :on-success nil}]]}))
      (rf/dispatch-sync [:ping]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      (is (= 1 @seen) "no reply was dispatched when :on-success is nil"))))

(defn assert-http-with-managed-request-stubs
  "with-managed-request-stubs* installs a per-call fx."
  [{:keys [name]}]
  (testing (str name " — with-managed-request-stubs* installs a per-call fx")
    (rf/reg-event-fx :articles/list
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:result reply}}
          {:fx [[:rf.http/managed {:request {:method :get :url "/articles"} :decode :json}]]})))
    (rf/with-managed-request-stubs*
      {[:get "/articles"] {:reply {:ok [:hello :world]}}}
      (fn []
        (rf/dispatch-sync [:articles/list]
                          {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
        (let [db (rf/app-db-value :rf/default)]
          (is (= :success (get-in db [:result :kind])))
          (is (= [:hello :world] (get-in db [:result :value]))))))))

(defn assert-http-with-managed-request-stubs-failure
  "with-managed-request-stubs* synthesises a failure reply for
  {:reply {:failure ...}}."
  [{:keys [name]}]
  (testing (str name " — with-managed-request-stubs* failure mapping")
    (rf/reg-event-fx :articles/list
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:result reply}}
          {:fx [[:rf.http/managed {:request {:method :get :url "/articles"} :decode :json}]]})))
    (rf/with-managed-request-stubs*
      {[:get "/articles"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}}
      (fn []
        (rf/dispatch-sync [:articles/list]
                          {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
        (let [db (rf/app-db-value :rf/default)]
          (is (= :failure (get-in db [:result :kind])))
          (is (= :rf.http/http-4xx (get-in db [:result :failure :kind])))
          (is (= 404 (get-in db [:result :failure :status]))))))))

(defn assert-http-multi-frame-reply-isolation
  "Managed requests issued from frame A reply into frame A's app-db."
  [{:keys [name]}]
  (testing (str name " — managed requests reply into the issuing frame's app-db")
    (rf/reg-event-fx :article/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:article (:value reply)}}
          {:fx [[:rf.http/managed {:request {:method :get :url "/articles/hello"} :decode :json}]]})))
    (let [left  (rf/make-frame {:doc "left"
                                :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
          right (rf/make-frame {:doc "right"
                                :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})]
      (rf/dispatch-sync [:article/load] {:frame left})
      (rf/dispatch-sync [:article/load] {:frame right})
      (is (= {:stubbed true} (:article (rf/app-db-value left))))
      (is (= {:stubbed true} (:article (rf/app-db-value right))))
      (is (nil? (:article (rf/app-db-value :rf/default)))))))

;; ===========================================================================
;; Cross-Spec interactions (spec/Cross-Spec-Interactions.md) — port of
;; `*_cross_spec` (headless subset)
;; ===========================================================================

(defn- collect-traces [k]
  (let [traces (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! traces conj ev)))
    traces))

(defn- stop-traces [k] (trace-tooling/unregister-listener! k))

(defn assert-xspec-frame-destroy-with-active-machines
  "#1 Frame disposal with active machine instances."
  [{:keys [name]}]
  (testing (str name " — #1 frame disposal with active machine instances")
    (rf/reg-frame :tenant-x {:doc "tenant frame with two machines"})
    (rf/reg-event-db :seed
      (fn [db _]
        (assoc-in db [:rf/runtime :machines :snapshots] {:flow/login    {:state :authed  :data {}}
                                                         :flow/checkout {:state :pending :data {}}})))
    (rf/dispatch-sync [:seed] {:frame :tenant-x})
    (let [traces (collect-traces ::xspec-1)]
      (rf/destroy-frame! :tenant-x)
      (stop-traces ::xspec-1)
      (let [machine-traces (filter #(= :rf.machine.lifecycle/destroyed (:operation %)) @traces)]
        (is (= 2 (count machine-traces)) "one trace per active machine snapshot at frame destroy")
        (is (every? #(= :tenant-x (:frame (:tags %))) machine-traces) "each trace carries the destroyed frame's id")
        (is (= #{:authed :pending} (set (map #(:last-state (:tags %)) machine-traces))) "each trace records the machine's last state")
        (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) machine-traces) "each trace carries :reason :parent-frame-destroyed")
        (is (some #(= :rf.frame/destroyed (:operation %)) @traces) ":rf.frame/destroyed fires after the per-machine traces")))))

(defn assert-xspec-machine-microstep-subscribe
  "#2 Sub-cache hit inside a machine microstep."
  [{:keys [name]}]
  (testing (str name " — #2 sub-cache hit inside a machine microstep")
    (rf/reg-event-db :seed (fn [_ _] {:user/role :admin}))
    (rf/reg-sub :user-role (fn [db _] (:user/role db)))
    (rf/dispatch-sync [:seed])
    (let [observed-by-action (atom nil)
          machine {:initial :idle :data {}
                   :states  {:idle   {:on {:go {:target :acting :action :record-role}}}
                             :acting {}}
                   :actions {:record-role
                             (fn [_] (reset! observed-by-action (rf/subscribe-once [:user-role])) nil)}}]
      (rf/reg-machine :auth/check machine)
      (rf/dispatch-sync [:auth/check [:go]])
      (is (= :admin @observed-by-action)
          "the sub returns the committed app-db value visible to the action body"))))

(defn assert-xspec-boot-order-adapter-ready
  "#3 Machine spawn at boot before substrate adapter ready."
  [{:keys [name]}]
  (testing (str name " — #3 machine spawn at boot before adapter ready")
    (rf/reg-event-db :init-shape
      (fn [_ _] {:rf/runtime {:machines {:snapshots {:flow/boot {:state :armed :data {}}}}}}))
    (rf/reg-frame :booted {:on-create [:init-shape]})
    (is (= :armed (get-in (rf/app-db-value :booted) [:rf/runtime :machines :snapshots :flow/boot :state]))
        ":on-create completed against an installed adapter — app-db carries the seed")))

(defn assert-xspec-machines-under-ssr
  "#4 Machines under SSR (allowed-subset)."
  [{:keys [name]}]
  (testing (str name " — #4 machines under SSR (allowed-subset)")
    (rf/reg-frame :req {:preset :ssr-server})
    (let [m (rf/frame-meta :req)]
      (is (= :server (:platform m)) ":ssr-server preset sets :platform :server")
      (is (= :rf.error/server-projection (:on-error m)) ":ssr-server preset wires :on-error"))
    (rf/reg-machine :ssr/timed
      {:initial :idle :data {}
       :states {:idle    {:on {:fetch {:target :loading}}}
                :loading {:after {500 :awake}}
                :awake   {}}})
    (let [traces (collect-traces ::xspec-4-after)]
      (rf/dispatch-sync [:ssr/timed [:fetch]] {:frame :req})
      (stop-traces ::xspec-4-after)
      (let [skipped   (filter #(= :rf.machine.timer/skipped-on-server (:operation %)) @traces)
            scheduled (filter #(= :rf.machine.timer/scheduled (:operation %)) @traces)]
        (is (seq skipped) ":after on :ssr-server emits :rf.machine.timer/skipped-on-server")
        (is (some #(= :server (get-in % [:tags :platform])) skipped) "the skipped-on-server trace records :platform :server")
        (is (some #(= 500 (get-in % [:tags :delay])) skipped) "the trace carries the declared :after delay")
        (is (empty? scheduled) "no :rf.machine.timer/scheduled trace fires on :ssr-server")))))

(defn assert-xspec-route-not-found-ssr
  "#7 Route-not-found under SSR."
  [{:keys [name]}]
  (testing (str name " — #7 route-not-found under SSR")
    (rf/reg-route :user/show {:path "/users/:id"})
    (is (nil? (:route-id (rf/match-url "/no-such-thing")))
        "match-url surfaces no route-id for an unmatched URL")
    (let [traces (collect-traces ::xspec-7)]
      (rf/match-url "/no-such-thing")
      (stop-traces ::xspec-7)
      (is (empty? (filter #(= :error (:op-type %)) @traces))
          "match-url is pure: route-not-found does not emit error traces"))))

(defn assert-xspec-headless-frame-resolution-chain
  "#9 Reactive substrate without React-context."
  [{:keys [name]}]
  (testing (str name " — #9 reactive substrate without React-context")
    (rf/reg-frame :alt {:doc "alt frame"})
    (is (= :rf/default (rf/current-frame-id)) "no dynamic binding → resolves to :rf/default")
    (rf/with-frame :alt
      (is (= :alt (rf/current-frame-id)) "dynamic-var tier wins over :rf/default"))
    (is (= :rf/default (rf/current-frame-id)) "with-frame's binding is scoped — dynamic var reverts on exit")))

(defn assert-xspec-machine-action-throws
  "#11 Machine action throws."
  [{:keys [name]}]
  (testing (str name " — #11 machine action throws")
    (rf/reg-event-db :seed-state (fn [_ _] {:val :before}))
    (rf/dispatch-sync [:seed-state])
    (let [machine {:initial :idle :data {}
                   :states  {:idle {:on {:bang {:target :angry :action :boom}}} :angry {}}
                   :actions {:boom (fn [_] (throw (ex-info "kaboom" {})))}}]
      (rf/reg-machine :test/m machine)
      (let [traces (collect-traces ::xspec-11)]
        (rf/dispatch-sync [:test/m [:bang]])
        (stop-traces ::xspec-11)
        (let [errs (filter #(= :rf.error/machine-action-exception (:operation %)) @traces)]
          (is (seq errs) "an action throw surfaces as :rf.error/machine-action-exception")
          (is (some #(= :test/m (get-in % [:tags :machine-id])) errs) "the trace identifies the machine that threw")
          (is (some #(= :boom (get-in % [:tags :action-id])) errs) "the trace identifies the action that threw"))
        (is (not (some #(= :rf.error/handler-exception (:operation %)) @traces))
            "the generic :rf.error/handler-exception does NOT also fire")
        (is (= :before (:val (rf/app-db-value :rf/default)))
            "a non-machine app-db slice is not touched when the cascade halts")))))

(defn assert-xspec-machine-fx-handler-throws
  "#12 Effect handler throws inside a machine action's :fx."
  [{:keys [name]}]
  (testing (str name " — #12 fx handler throws inside a machine action's :fx")
    (let [seen (atom [])]
      (rf/reg-fx :throwy (fn [_ _] (throw (ex-info "fx-bang" {}))))
      (rf/reg-fx :record (fn [_ args] (swap! seen conj args)))
      (let [machine {:initial :idle :data {}
                     :states  {:idle {:on {:go {:target :done :action :emit-fx}}} :done {}}
                     :actions {:emit-fx (fn [_] {:fx [[:throwy :a] [:record :b]]})}}]
        (rf/reg-machine :test/m machine)
        (let [traces (collect-traces ::xspec-12)]
          (rf/dispatch-sync [:test/m [:go]])
          (stop-traces ::xspec-12)
          (is (some #(and (= :rf.error/fx-handler-exception (:operation %))
                          (= :throwy (get-in % [:tags :rf.fx/id]))) @traces)
              "the throwing fx surfaces as :rf.error/fx-handler-exception")
          (is (= [:b] @seen) ":fx walk continued past the throwing fx — :record still ran")
          (is (= :done (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots :test/m :state]))
              "the machine snapshot committed even though a downstream :fx threw"))))))

(defn assert-xspec-hot-reload-machine-action
  "#13 Hot-reload of a machine action."
  [{:keys [name]}]
  (testing (str name " — #13 hot-reload of a machine action")
    (let [machine-v1 {:initial :idle :data {}
                      :states  {:idle    {:on {:go {:target :working :action :tag}}}
                                :working {:on {:go {:target :idle    :action :tag}}}}
                      :actions {:tag (fn [{data :data}] {:data (assoc data :who :v1)})}}
          machine-v2 (assoc-in machine-v1 [:actions :tag] (fn [data _] {:data (assoc data :who :v2)}))]
      (rf/reg-machine :test/m machine-v1)
      (rf/dispatch-sync [:test/m [:go]])
      (is (= :v1 (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots :test/m :data :who]))
          "v1 action ran on the first dispatch")
      (rf/reg-machine :test/m machine-v2)
      (rf/dispatch-sync [:test/m [:go]])
      (is (= :v2 (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots :test/m :data :who]))
          "the next dispatched event resolves to the new action body"))))

(defn assert-xspec-dispatch-sync-from-handler-raises
  "#14 Re-entrant dispatch from inside a handler."
  [{:keys [name]}]
  (testing (str name " — #14 re-entrant dispatch-sync from inside a handler")
    (let [traces (collect-traces ::xspec-14)]
      (rf/reg-event-db :outer (fn [db _] (assoc db :ran? true)))
      (rf/reg-event-fx :nested (fn [_ _] (rf/dispatch-sync [:outer]) {}))
      (rf/dispatch-sync [:nested])
      (stop-traces ::xspec-14)
      (is (some (fn [ev] (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                              (= :error (:op-type ev)))) @traces)
          "a nested dispatch-sync emits :rf.error/dispatch-sync-in-handler"))))

(defn assert-xspec-time-travel-revert
  "#15 Tool-Pair revert via replace-container!."
  [{:keys [name]}]
  (testing (str name " — #15 Tool-Pair revert via replace-container!")
    (let [machine {:initial :idle :data {}
                   :states  {:idle {:on {:go {:target :working}}} :working {:on {:go {:target :idle}}}}}]
      (rf/reg-machine :test/m machine)
      (rf/dispatch-sync [:test/m [:go]])
      (let [post-go-db (rf/app-db-value :rf/default)]
        (is (= :working (get-in post-go-db [:rf/runtime :machines :snapshots :test/m :state])) "machine reached :working")
        (let [container (frame/app-db-container :rf/default)
              reverted  (assoc-in post-go-db [:rf/runtime :machines :snapshots :test/m :state] :idle)]
          (substrate-adapter/replace-container! container reverted))
        (is (= :idle (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots :test/m :state]))
            "after replace-container! the snapshot reads back as :idle")
        (rf/dispatch-sync [:test/m [:go]])
        (is (= :working (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots :test/m :state]))
            "re-dispatch after revert advances from the restored state")))))

(defn assert-xspec-server-error-projection
  "#16 Error projection on the server."
  [{:keys [name]}]
  (testing (str name " — #16 error projection on the server")
    (rf/reg-frame :req {:preset :ssr-server})
    (rf/reg-event-fx :handler-throws (fn [_ _] (throw (ex-info "boom" {}))))
    (let [traces (collect-traces ::xspec-16)]
      (rf/dispatch-sync [:handler-throws] {:frame :req})
      (stop-traces ::xspec-16)
      (let [errs (filter #(= :rf.error/handler-exception (:operation %)) @traces)]
        (is (seq errs) ":rf.error/handler-exception fires on the server frame for a thrown handler")
        (is (some #(= :req (get-in % [:tags :frame])) errs) "the trace records the request frame's id")
        (let [err          (first errs)
              public-error (ssr/apply-error-projection! :req err)]
          (is (= 500 (:status public-error)) "default projector maps to :status 500")
          (is (= :internal-error (:code public-error)) "default projector's :code is :internal-error")
          (is (false? (:retryable? public-error)) "default projector's :retryable? is false")
          (is (string? (:message public-error)) "default projector emits a one-sentence human :message")
          (is (= 500 (:status (ssr/get-response :req))) "the projector's :status is stamped onto [:rf/response]"))))))

(defn assert-xspec-hot-reload-sub-mid-cascade
  "#18 Re-registering a sub mid-cascade."
  [{:keys [name]}]
  (testing (str name " — #18 re-registering a sub mid-cascade")
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (is (= 7 (rf/subscribe-once [:answer])) "the v1 sub computes from app-db")
    (let [_pin (rf/subscribe [:answer])]
      (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
      (is (= 700 (rf/subscribe-once [:answer])) "after re-registration the new sub body is in effect")
      (rf/unsubscribe [:answer]))))

(defn assert-xspec-portable-story-fx-override
  "#19 Story decorators that override fx."
  [{:keys [name]}]
  (testing (str name " — #19 Story decorators that override fx")
    (let [seen (atom [])]
      (rf/reg-fx :http             (fn [_ args] (swap! seen conj [:real-http args])))
      (rf/reg-fx :rf.test/http-stub (fn [_ args] (swap! seen conj [:stub args])))
      (rf/reg-frame :story-frame {:fx-overrides {:http :rf.test/http-stub}})
      (rf/reg-event-fx :go (fn [_ _] {:fx [[:http {:url "/x"}]]}))
      (rf/dispatch-sync [:go] {:frame :story-frame})
      (is (= [[:stub {:url "/x"}]] @seen)
          "the id-valued override redirected :http → :rf.test/http-stub"))))

(defn assert-xspec-adapter-already-installed
  "#20 Adapter swap mid-process is forbidden; clean re-install after destroy."
  [{:keys [adapter name]}]
  (testing (str name " — #20 adapter swap mid-process is forbidden")
    (let [thrown? (try
                    (rf/install-adapter! adapter)
                    false
                    (catch :default e
                      (= ":rf.error/adapter-already-installed" (ex-message e))))]
      (is thrown? "second install-adapter! raises :rf.error/adapter-already-installed"))
    (rf/destroy-adapter!)
    (rf/install-adapter! adapter)
    (is (some? (substrate-adapter/current-adapter))
        "after destroy, install succeeds again — clean swap path")))

;; ===========================================================================
;; *current-frame* propagation across dispatch (rf2-l5q3) — port of
;; `*_dispatch_frame_capture`. Async + sync. Driven from a dedicated
;; entry-file pair carrying a {:before :after} map fixture (async tests
;; require a map-form fixture so :after lands after the async `done`).
;; ===========================================================================

(defn- dfc-seed-frames!
  [substrate-kw]
  (let [tenant-a (mint-kw substrate-kw "dfc-tenant-a")
        tenant-b (mint-kw substrate-kw "dfc-tenant-b")
        seed     (mint-kw substrate-kw "dfc-seed")]
    (rf/reg-frame tenant-a {:doc "tenant-a frame"})
    (rf/reg-frame tenant-b {:doc "tenant-b frame"})
    (rf/reg-event-db seed (fn [_ [_ marker]] {:marker marker :received []}))
    (rf/dispatch-sync [seed :rf/default] {:frame :rf/default})
    (rf/dispatch-sync [seed :tenant-a]  {:frame tenant-a})
    (rf/dispatch-sync [seed :tenant-b]  {:frame tenant-b})
    {:tenant-a tenant-a :tenant-b tenant-b}))

(defn- dfc-received [frame-id] (:received (frame/frame-app-db-value frame-id)))

(defn assert-dfc-sync-dispatch-routes-to-handlers-frame
  "Synchronous direct rf/dispatch from inside a handler routes to that
  handler's frame (rf2-l5q3)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — sync rf/dispatch from a handler routes to the handler's frame")
    (let [{:keys [tenant-a]} (dfc-seed-frames! substrate-kw)
          parent (mint-kw substrate-kw "dfc-parent")
          landed (mint-kw substrate-kw "dfc-landed")]
      (rf/reg-event-fx parent (fn [_ _] (rf/dispatch [landed]) {}))
      (rf/reg-event-db landed (fn [db _] (update db :received (fnil conj []) :landed-sync)))
      (rf/dispatch-sync [parent] {:frame tenant-a})
      (is (= [:landed-sync] (dfc-received tenant-a))
          "the :landed event must land on tenant-a, not :rf/default")
      (is (empty? (dfc-received :rf/default))
          ":rf/default must NOT have received :landed — the dispatch was scoped to tenant-a"))))

(defn assert-dfc-fx-dispatch-routes-to-handlers-frame
  ":fx [[:dispatch ...]] routes to the handler's frame (rf2-l5q3)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — :fx [[:dispatch ...]] routes to the handler's frame")
    (let [{:keys [tenant-a]} (dfc-seed-frames! substrate-kw)
          parent (mint-kw substrate-kw "dfc-parent-fx")
          landed (mint-kw substrate-kw "dfc-landed-fx")]
      (rf/reg-event-fx parent (fn [_ _] {:fx [[:dispatch [landed]]]}))
      (rf/reg-event-db landed (fn [db _] (update db :received (fnil conj []) :landed-fx)))
      (rf/dispatch-sync [parent] {:frame tenant-a})
      (is (= [:landed-fx] (dfc-received tenant-a))
          ":fx [[:dispatch ...]] threads the frame through fx/do-fx — lands on tenant-a")
      (is (empty? (dfc-received :rf/default)) ":rf/default sees nothing"))))

(defn assert-dfc-sync-dispatch-isolation
  "Synchronous dispatch from tenant-a stays in tenant-a; tenant-b
  untouched (rf2-l5q3)."
  [{:keys [substrate-kw name]}]
  (testing (str name " — sync dispatch isolation between frames")
    (let [{:keys [tenant-a tenant-b]} (dfc-seed-frames! substrate-kw)
          fan  (mint-kw substrate-kw "dfc-fan")
          leaf (mint-kw substrate-kw "dfc-leaf")]
      (rf/reg-event-fx fan (fn [_ [_ payload]] (rf/dispatch [leaf payload]) {}))
      (rf/reg-event-db leaf (fn [db [_ payload]] (update db :received (fnil conj []) payload)))
      (rf/dispatch-sync [fan :a-payload] {:frame tenant-a})
      (rf/dispatch-sync [fan :b-payload] {:frame tenant-b})
      (is (= [:a-payload] (dfc-received tenant-a)) "tenant-a only sees its own :a-payload")
      (is (= [:b-payload] (dfc-received tenant-b)) "tenant-b only sees its own :b-payload")
      (is (empty? (dfc-received :rf/default)) ":rf/default sees nothing — neither cascade leaked"))))

(defn assert-dfc-raw-dispatch-from-set-timeout-falls-through
  "Raw rf/dispatch from a setTimeout callback escapes *current-frame* —
  documented gotcha (rf2-l5q3). ASYNC: caller supplies `done`."
  [{:keys [substrate-kw name]} done]
  (testing (str name " — raw rf/dispatch from setTimeout falls through to :rf/default")
    (let [{:keys [tenant-a]} (dfc-seed-frames! substrate-kw)
          defer  (mint-kw substrate-kw "dfc-defer-raw")
          landed (mint-kw substrate-kw "dfc-landed-raw")]
      (rf/reg-event-fx defer
        (fn [_ _] (js/setTimeout (fn [] (rf/dispatch [landed])) 0) {}))
      (rf/reg-event-db landed (fn [db _] (update db :received (fnil conj []) :landed-raw)))
      (rf/dispatch-sync [defer] {:frame tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-raw] (dfc-received :rf/default))
                  ":landed-raw lands on :rf/default (dynamic binding is dead in the setTimeout callback)")
              (is (empty? (dfc-received tenant-a))
                  "tenant-a sees nothing — raw rf/dispatch can't recover the in-flight frame from a setTimeout")
              (done))
            10))
        10))))

(defn assert-dfc-dispatch-later-survives-the-timer
  ":dispatch-later threads :frame through the closure — survives the async
  escape (rf2-l5q3). ASYNC: caller supplies `done`."
  [{:keys [substrate-kw name]} done]
  (testing (str name " — :dispatch-later survives the timer")
    (let [{:keys [tenant-a]} (dfc-seed-frames! substrate-kw)
          parent (mint-kw substrate-kw "dfc-parent-later")
          landed (mint-kw substrate-kw "dfc-landed-later")]
      (rf/reg-event-fx parent
        (fn [_ _] {:fx [[:dispatch-later {:ms 0 :event [landed]}]]}))
      (rf/reg-event-db landed (fn [db _] (update db :received (fnil conj []) :landed-later)))
      (rf/dispatch-sync [parent] {:frame tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-later] (dfc-received tenant-a))
                  ":dispatch-later landed on tenant-a even though the timer fired after the binding popped")
              (is (empty? (dfc-received :rf/default)) ":rf/default sees nothing")
              (done))
            50))
        50))))

(defn assert-dfc-dispatcher-survives-set-timeout
  "(:dispatch (rf/frame-handle)) captures the in-flight frame; the captured fn is safe to
  call from setTimeout (rf2-l5q3). ASYNC: caller supplies `done`."
  [{:keys [substrate-kw name]} done]
  (testing (str name " — (:dispatch (rf/frame-handle)) survives setTimeout")
    (let [{:keys [tenant-a]} (dfc-seed-frames! substrate-kw)
          parent (mint-kw substrate-kw "dfc-parent-bound")
          landed (mint-kw substrate-kw "dfc-landed-bound")]
      (rf/reg-event-fx parent
        (fn [_ _]
          (let [d (:dispatch (rf/frame-handle))]
            (js/setTimeout (fn [] (d [landed])) 0))
          {}))
      (rf/reg-event-db landed (fn [db _] (update db :received (fnil conj []) :landed-bound)))
      (rf/dispatch-sync [parent] {:frame tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-bound] (dfc-received tenant-a))
                  "(:dispatch (rf/frame-handle)) captured tenant-a at call time; the setTimeout callback dispatches there")
              (is (empty? (dfc-received :rf/default)) ":rf/default sees nothing")
              (done))
            10))
        10))))

;; ===========================================================================
;; DOM / browser twins (rf2-5or96 — DOM-split remainder of rf2-p4736)
;;
;; UIx and Helix both define substrate-specific component vars via
;; `defui`/`defnc` + `$` (and, for use-subscribe, the substrate's hooks).
;; The suite cannot mint those at runtime, so each entry file builds the
;; probe components + their observation atoms + a `:render-element` thunk
;; (the substrate's `$`) and hands them in. The orchestration (reg-frame,
;; dispatch, mount under act, assert) lives here as a single source — a
;; gap on one substrate is a gap on both by construction.
;;
;; These functions self-gate on `(browser?)`; under :node-test they no-op
;; cleanly (the entry files still load — the after-render ns-load smoke
;; runs there). The real assertions run under :browser-test.
;; ===========================================================================

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "Return React's act() if available, else nil. React 18 ships act in
  react-dom/test-utils; React 19 promotes it to the React namespace
  proper."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

(defn- enable-react-act-env!
  "React's act() helper warns / behaves as a no-op unless the runner
  opts in by setting the global `IS_REACT_ACT_ENVIRONMENT` flag. The
  Playwright browser runner doesn't set this by default; set it inside
  each test that needs act() so concurrent-renderer pending work commits
  synchronously."
  []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- with-browser-act
  "Run the standard DOM-twin gate ladder: skip under :node-test (no DOM)
  and skip if act() is unreachable from this runner; otherwise enable the
  act env and call `(f act-fn)`. A plain HOF (not a macro) — this is a
  .cljs file, so a same-file macro is not available at runtime."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [act-fn (get-act)]
      (if (nil? act-fn)
        (is true "act() not reachable from this runner; skipping")
        (do (enable-react-act-env!)
            (f act-fn))))))

;; ---- after-render hook (rf2-334d9) ----------------------------------------

(defn assert-after-render-hook-wired
  "rf2-334d9: `interop/after-render` no longer silent-no-ops under the
  React adapter — the hook is wired at ns-load and returns nil (the
  documented swallow shape) rather than falling through to nil because no
  adapter published it. Node-safe (no DOM): runs under :node-test too."
  [{:keys [name]}]
  (testing (str name " — after-render hook wired at ns-load (rf2-334d9)")
    (is (nil? (interop/after-render (fn [] :ok)))
        "interop/after-render under the adapter returns nil — the
         spine-built hook is wired through :adapter/after-render via
         substrate-adapter/route-hook!")))

(defn assert-after-render-runs-after-commit
  "rf2-334d9: `(interop/after-render f)` schedules `f` to run after the
  next mount/render cycle. The sentinel injected by the spine's
  make-render uses React.useLayoutEffect to drain the queue post-commit.

  cfg keys:
    :probe-element  a thunk returning a fresh substrate probe ELEMENT
                    (e.g. `#(uix/$ Probe)` / `#($ Probe)`). Built in the
                    entry file because `$` is a substrate macro."
  [{:keys [name probe-element]}]
  (testing (str name " — after-render runs callback after next commit (rf2-334d9)")
    (with-browser-act
     (fn [act-fn]
      (let [fired      (atom 0)
            callback   (fn after-render-cb [] (swap! fired inc))
            mount-node (make-mount-node!)
            unmount    (atom nil)]
        (try
          ;; Mount through the substrate adapter's render so the spine's
          ;; make-render path injects the after-render sentinel. Direct
          ;; createRoot + .render bypasses the spine wrap and would leave
          ;; no sentinel in the tree — exactly what rf2-334d9 requires.
          (act-fn (fn []
                    (reset! unmount
                            (substrate-adapter/render (probe-element) mount-node {}))))
          (is (zero? @fired)
              "no after-render fn enqueued yet ⇒ no fires")
          ;; Enqueue under act so the set-tick bump → re-render →
          ;; useLayoutEffect drain commits synchronously in the test env.
          (act-fn (fn [] (interop/after-render callback)))
          (is (= 1 @fired)
              "after-render fn fired exactly once after the next commit")
          ;; A second enqueue + drain — the sentinel survives the first
          ;; drain (its useLayoutEffect runs every commit) so a
          ;; subsequent after-render also fires.
          (act-fn (fn [] (interop/after-render callback)))
          (is (= 2 @fired)
              "subsequent after-render fn also fires after its commit")
          (finally
            (when-let [u @unmount]
              (try (u) (catch :default _ nil))))))))))

;; ---- flush-views! cross-substrate parity (rf2-b6nm5) ----------------------

(defn assert-flush-views-canonical-shape
  "rf2-b6nm5: the canonical test-flush hook `flush-views!` is surfaced
  from this adapter's ns with the canonical nil-return shape (Decision 6).
  Node-safe (no DOM): pins the SHAPE — the Var is a fn, the 0-arity call
  returns nil and does not throw under the :node-test runner (act() gated
  / unreachable there ⇒ the spine degrades to a plain synchronous flush).
  The cross-substrate convergence (same name + nil-return on all four
  substrates) is what lets a test suite port touching only the init! Var.

  cfg keys:
    :flush-views! the adapter ns's flush-views! Var"
  [{:keys [name flush-views!]}]
  (testing (str name " — flush-views! surfaced with canonical nil-return shape (rf2-b6nm5)")
    (is (fn? flush-views!)
        "the adapter ns exposes flush-views! as a fn (Decision 6 canonical hook)")
    (is (nil? (flush-views!))
        "0-arity flush-views! returns nil — the converged contract across all four substrates")))

(defn assert-after-render-fires-on-native-mount
  "rf2-t0x90: `(interop/after-render f)` fires post-commit even when the
  app was mounted via the SUBSTRATE-NATIVE renderer (the documented boot
  idiom: `uix-dom/render-root` / Helix's `(.render root …)`) rather than
  through the adapter's `:render` slot.

  The defect this pins: the Fragment-wrap after-render sentinel only
  enters the tree on the `:render`-slot path. The documented idiom mounts
  natively (createRoot + .render), bypassing `make-render`, so a natively-
  mounted UIx/Helix app had NO sentinel — `(rf/after-render f)` degraded
  to a bare microtask FOREVER, defeating the post-commit-timing contract
  Reagent's global `r/after-render` honours regardless of mount path. The
  fix arms a per-adapter SINGLETON DRIVER ROOT the first time after-render
  is called with no app-tree sentinel, restoring post-commit parity.

  This test mounts the probe with a RAW `react-dom-client/createRoot` +
  `.render` (NOT `substrate-adapter/render`) — exactly the native idiom
  that bypasses the spine's Fragment-wrap — then asserts after-render
  still fires.

  cfg keys:
    :probe-element  a thunk returning a fresh substrate probe ELEMENT
                    (reused from the :render-slot after-render twin)."
  [{:keys [name probe-element]}]
  (testing (str name " — after-render fires on the NATIVE-mount path (rf2-t0x90)")
    (with-browser-act
     (fn [act-fn]
      (let [fired      (atom 0)
            callback   (fn native-after-render-cb [] (swap! fired inc))
            mount-node (make-mount-node!)
            ;; NATIVE mount — raw createRoot + .render, NOT
            ;; substrate-adapter/render. This is the documented boot idiom
            ;; (uix-dom/render-root / Helix .render). The spine's
            ;; Fragment-wrap sentinel is therefore NOT in this tree — the
            ;; exact gap rf2-t0x90 names.
            root       (react-dom-client/createRoot mount-node)]
        (try
          (act-fn (fn [] (.render root (probe-element))))
          (is (zero? @fired)
              "no after-render fn enqueued yet ⇒ no fires")
          ;; With NO app-tree sentinel present, the after-render hook arms
          ;; the singleton driver root (a detached React root carrying the
          ;; sentinel) and bumps its tick — the useLayoutEffect drain fires
          ;; post-commit. Enqueue + drain under act so the commit lands
          ;; synchronously in the test env.
          (act-fn (fn [] (interop/after-render callback)))
          (is (= 1 @fired)
              "after-render fired post-commit on the native-mount path —
               the singleton driver root delivered parity (rf2-t0x90)")
          ;; A second enqueue + drain — the driver-root sentinel survives
          ;; (its useLayoutEffect runs every commit), so subsequent
          ;; after-render calls also fire.
          (act-fn (fn [] (interop/after-render callback)))
          (is (= 2 @fired)
              "subsequent after-render also fires via the driver root")
          (finally
            (try (act-fn (fn [] (.unmount root))) (catch :default _ nil)))))))))

;; ---- hydrate render branch (rf2-ee38b.1 — closes the React-hook spine
;;       hydrate test gap that Reagent/reagent-slim already cover) ----------

(defn assert-render-hydrate-branch-mounts-without-remount
  "rf2-ee38b.13 / rf2-ee38b.14: the spine `make-render` `:hydrate? true`
  branch (`react-dom/client/hydrateRoot`) was untested for the React-hook
  substrates while Reagent/reagent-slim both exercise it. This closes the
  gap once for BOTH UIx and Helix.

  The probe ELEMENT is pre-rendered to matching SSR markup with React's own
  `react-dom/server/renderToString` (a test-only require — the same element
  the client hydrates, so no hydration-mismatch warning), the markup is
  planted into a fresh mount node, then `substrate-adapter/render …
  {:hydrate? true}` drives the spine's hydrate branch to adopt the existing
  DOM. We assert the spine's hydrate path returns a working unmount thunk
  (root tracked in `active-roots-cell` + drained) and that the hydrated
  subtree's DOM survives — i.e. `hydrateRoot` adopted the markup rather than
  throwing or blanking the node.

  cfg keys:
    :probe-element  a thunk returning a fresh substrate probe ELEMENT (the
                    same `$`-built element the after-render twin uses)"
  [{:keys [name probe-element]}]
  (testing (str name " — render :hydrate? true branch adopts SSR markup (rf2-ee38b.1)")
    (with-browser-act
     (fn [act-fn]
      (let [mount-node (make-mount-node!)
            ;; Same element the client will hydrate ⇒ matching markup ⇒ no
            ;; hydration mismatch. renderToString is React's, not the
            ;; hiccup emitter (which takes hiccup, not React elements).
            markup     (.renderToString react-dom-server (probe-element))
            unmount    (atom nil)]
        (set! (.-innerHTML mount-node) markup)
        (try
          (act-fn (fn []
                    (reset! unmount
                            (substrate-adapter/render
                              (probe-element) mount-node {:hydrate? true}))))
          (is (fn? @unmount)
              "hydrate render returns an unmount thunk (root tracked)")
          (is (pos? (.-length (.-childNodes mount-node)))
              "hydrated subtree's DOM is present (hydrateRoot adopted the
               markup, did not blank the node)")
          (finally
            (when-let [u @unmount]
              (try (act-fn (fn [] (u))) (catch :default _ nil))))))))))

;; ---- use-subscribe (rf2-518sp / rf2-7g959 / rf2-mwft2 / rf2-rcgsc) --------
;;
;; The probe components read the sub via `use-subscribe` and push the
;; observed value into a side-channel atom owned by the entry file. After
;; a dispatch we re-render under `act` and assert the side-channel
;; reflects the new value. The 2-arg form pins the frame explicitly; the
;; 1-arg form resolves through the surrounding `frame-provider`. All
;; substrate-baked keywords (frame-ids, query-vs) are passed in cfg so
;; they line up with what the entry file's probe vars closed over at
;; compile time.

(defn assert-use-subscribe-tracks-app-db-changes
  "rf2-518sp: use-subscribe sees post-dispatch values via
  useSyncExternalStore.

  cfg keys:
    :probe-element     thunk → the 2-arg-form Probe element
    :probe-observed    atom the Probe pushes observed values into
    :refcount-target   atom the Probe reads its target frame-id from
    :us-frame          frame-id keyword the Probe's query resolves under
    :us-query          query-v keyword the Probe subscribes to"
  [{:keys [name probe-element probe-observed refcount-target us-frame us-query]}]
  (testing (str name " — use-subscribe sees post-dispatch values (rf2-518sp)")
    (with-browser-act
     (fn [act-fn]
      (reset! probe-observed [])
      (reset! refcount-target us-frame)
      (rf/reg-frame us-frame {:doc "use-subscribe probe frame"})
      (rf/reg-event-db ::us-seed (fn [_ _] {:n 1}))
      (rf/reg-event-db ::us-inc  (fn [db _] (update db :n inc)))
      (rf/dispatch-sync [::us-seed] {:frame us-frame})
      (rf/reg-sub us-query (fn [db _] (:n db)))
      (let [mount-node (make-mount-node!)
            root       (react-dom-client/createRoot mount-node)]
        (try
          (act-fn (fn [] (.render root (probe-element))))
          (is (some #{1} @probe-observed)
              "first render observed the seeded value n=1")
          ;; Wrap dispatch in act so React commits the forceUpdate the
          ;; spine's add-watch → on-change path schedules. Plain
          ;; dispatch-sync outside act emits the "not wrapped in act"
          ;; warning AND fails to flush the render in the test env.
          (act-fn (fn [] (rf/dispatch-sync [::us-inc] {:frame us-frame})))
          (is (some #{2} @probe-observed)
              "post-dispatch re-render observed the incremented value n=2")
          (finally
            (try (.unmount root) (catch :default _ nil)))))))))

;; ---- flush-render! synchronous-commit proof (rf2-40a84) -------------------

(defn assert-flush-render-synchronously-commits
  "rf2-40a84: `(adapter/flush-render! f)` SYNCHRONOUSLY commits the render
  scheduled by `f` to the DOM — the committed text reflects the dispatched
  state change by the time `flush-render!` RETURNS, with NO `act()` wrapper
  and NO wait for a `requestAnimationFrame` tick.

  This is the load-bearing proof the bead asks for: it is the synchronous-
  flush guarantee that lets headless tooling (the pair MCP) drive a
  `dispatch → flush-render! → observe-settled-DOM` loop in a backgrounded
  tab where the rAF-scheduled commit would never fire.

  HOW THE PROOF IS RIGOROUS. After the initial mount (done under `act` so
  the test env is happy), we TURN OFF `IS_REACT_ACT_ENVIRONMENT` and run the
  dispatch + flush-render! entirely OUTSIDE `act`. flushSync (UIx/Helix) /
  reagent.core/flush (ratom family) commit synchronously regardless of the
  act env, so the assertion `(= \"n=2\" textContent)` reads TRUE on the line
  immediately after `flush-render!` returns — a deferred (rAF/microtask)
  commit would still read \"n=1\" there. We pass the state-changing dispatch
  AS the flush thunk so the 1-arity `dispatch → commit` contract is what's
  proven.

  cfg keys (reuses the use-subscribe probe wiring):
    :adapter           the installed adapter spec map (for flush-render!)
    :probe-element     thunk → the 2-arg-form Probe element
    :refcount-target   atom the Probe reads its target frame-id from
    :fr-frame          frame-id keyword the Probe's query resolves under
    :fr-query          query-v keyword the Probe subscribes to"
  [{:keys [name adapter probe-element refcount-target fr-frame fr-query]}]
  (testing (str name " — flush-render! synchronously commits a pending render (rf2-40a84)")
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! refcount-target fr-frame)
            (rf/reg-frame fr-frame {:doc "flush-render! synchronous-commit probe frame"})
            (rf/reg-event-db ::fr-seed (fn [_ _] {:n 1}))
            (rf/reg-event-db ::fr-inc  (fn [db _] (update db :n inc)))
            (rf/dispatch-sync [::fr-seed] {:frame fr-frame})
            (rf/reg-sub fr-query (fn [db _] (:n db)))
            (let [mount-node (make-mount-node!)
                  root       (react-dom-client/createRoot mount-node)
                  flush!     (:flush-render! adapter)]
              (try
                (is (fn? flush!)
                    "the adapter map exposes :flush-render! (rf2-40a84 contract slot)")
                ;; Initial mount under act so the test env commits the
                ;; seeded value cleanly.
                (act-fn (fn [] (.render root (probe-element))))
                (is (= "n=1" (.-textContent mount-node))
                    "committed DOM shows the seeded value n=1")
                ;; THE PROOF. Leave the act environment so the next commit
                ;; cannot be attributed to act()'s flush. Dispatch the state
                ;; change AS the flush-render! thunk and assert the DOM has
                ;; the new value the instant flush-render! returns — i.e. the
                ;; commit was SYNCHRONOUS, not rAF/microtask-deferred.
                (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                (flush! (fn [] (rf/dispatch-sync [::fr-inc] {:frame fr-frame})))
                (is (= "n=2" (.-textContent mount-node))
                    "DOM reflects the dispatched change SYNCHRONOUSLY after
                     flush-render! returns — no act(), no rAF wait (rf2-40a84)")
                (finally
                  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
                  (try (.unmount root) (catch :default _ nil)))))))))))

(defn assert-use-subscribe-frame-provider-resolution
  "rf2-518sp: use-subscribe 1-arg form resolves through the surrounding
  frame-provider.

  rf2-z7hfp — MOVE THE SEAM UP. The adapter's `frame-provider` is now a
  NATIVE substrate component (`defui` / `defnc`), NOT a plain CLJS fn, so
  it is mounted via the substrate's OWN `$` (the documented call shape)
  rather than invoked directly. The entry file supplies a
  `:frame-provider-mount-element` thunk `(fn [frame-kw child-el] ...)`
  that builds `($ frame-provider {:frame frame-kw} child-el)` in the
  substrate's idiom — the idiomatic TRAILING-CHILDREN shape (rf2-7kii2),
  no `:children` prop-map key — exercising the native shell through `$`,
  the exact surface the old per-substrate prop-mangling defect (rf2-9ok1s
  / rf2-8svnm) hid under. This makes the 1-arg-resolution contract a
  full end-to-end check of the moved-up seam.

  cfg keys:
    :frame-provider-mount-element   thunk (fn [frame-kw child-el]) →
                                    the substrate `($ frame-provider {…}
                                    child-el)` element with `child-el` as
                                    its only trailing child
    :probe-frame-provider-element   thunk → the 1-arg-form
                                    ProbeFrameProvider element
    :probe-frame-provider-observed  atom the ProbeFrameProvider pushes
                                    observed values into
    :frame-provider-frame           frame-id keyword for the wrapped frame
    :frame-provider-query           query-v keyword ProbeFrameProvider
                                    subscribes to"
  [{:keys [name frame-provider-mount-element probe-frame-provider-element
           probe-frame-provider-observed frame-provider-frame frame-provider-query]}]
  (testing (str name " — use-subscribe 1-arg resolves via frame-provider (rf2-518sp / rf2-z7hfp)")
    (with-browser-act
     (fn [act-fn]
      (reset! probe-frame-provider-observed [])
      (rf/reg-frame frame-provider-frame {:doc "use-subscribe frame-provider probe frame"})
      (rf/reg-event-db ::frame-provider-seed (fn [_ _] {:k :wrapped}))
      (rf/dispatch-sync [::frame-provider-seed] {:frame frame-provider-frame})
      (rf/reg-sub frame-provider-query (fn [db _] (:k db)))
      (let [mount-node (make-mount-node!)
            root       (react-dom-client/createRoot mount-node)]
        (try
          (act-fn
            (fn []
              ;; The NATIVE frame-provider component mounted via the
              ;; substrate's documented `$` shape (rf2-z7hfp).
              (.render root
                (frame-provider-mount-element
                  frame-provider-frame (probe-frame-provider-element)))))
          (is (some #{:wrapped} @probe-frame-provider-observed)
              "use-subscribe 1-arg form read from the wrapped frame, not :rf/default")
          (finally
            (try (.unmount root) (catch :default _ nil)))))))))

(defn assert-use-subscribe-2-arg-pins-explicit-frame
  "rf2-rcgsc / rf2-y0db2: use-subscribe's 2-arg form
  `(use-subscribe frame-kw query-v)` reads from the named frame's app-db,
  bypassing the React-context tier. Two probes pinning two different
  frames in the same render tree must see each frame's distinct seed
  value.

  cfg keys:
    :probe-2arg-element  thunk → an element wrapping Probe2ArgA + Probe2ArgB
    :probe-2arg-a-observed / :probe-2arg-b-observed   the probes' atoms
    :tenant-a-frame / :tenant-b-frame   the two pinned frame-ids
    :explicit-pin-query  query-v keyword both probes subscribe to"
  [{:keys [name probe-2arg-element probe-2arg-a-observed probe-2arg-b-observed
           tenant-a-frame tenant-b-frame explicit-pin-query]}]
  (testing (str name " — use-subscribe 2-arg pins explicit frame (rf2-rcgsc)")
    (with-browser-act
     (fn [act-fn]
      (reset! probe-2arg-a-observed [])
      (reset! probe-2arg-b-observed [])
      (rf/reg-frame tenant-a-frame {:doc "tenant-a"})
      (rf/reg-frame tenant-b-frame {:doc "tenant-b"})
      (rf/reg-event-db ::explicit-pin-seed (fn [_ [_ n]] {:n n}))
      (rf/dispatch-sync [::explicit-pin-seed 10]  {:frame tenant-a-frame})
      (rf/dispatch-sync [::explicit-pin-seed 100] {:frame tenant-b-frame})
      (rf/reg-sub explicit-pin-query (fn [db _] (:n db)))
      (let [mount-node (make-mount-node!)
            root       (react-dom-client/createRoot mount-node)]
        (try
          (act-fn (fn [] (.render root (probe-2arg-element))))
          (is (some #{10} @probe-2arg-a-observed)
              "Probe2ArgA observed tenant-a's value (10) via explicit frame-pin")
          (is (some #{100} @probe-2arg-b-observed)
              "Probe2ArgB observed tenant-b's value (100) via explicit frame-pin")
          (is (not (some #{100} @probe-2arg-a-observed))
              "tenant-a probe did NOT leak tenant-b's value")
          (is (not (some #{10} @probe-2arg-b-observed))
              "tenant-b probe did NOT leak tenant-a's value")
          (finally
            (try (.unmount root) (catch :default _ nil)))))))))

(defn assert-use-subscribe-cleanup-decrements-refcount
  "rf2-7g959: use-subscribe pairs subscribe with subs/unsubscribe on
  unmount so the sub-cache ref-count for the (frame, query) pair returns
  to 0 (or the entry is dropped) after unmount.

  cfg keys:
    :probe-refcount-element  thunk → the ProbeRefcount element (2-arg
                             form, no observe)
    :refcount-target         atom the ProbeRefcount reads its target
                             frame-id from
    :rc-frame                frame-id keyword for the refcount probe
    :rc-query                query-v keyword ProbeRefcount subscribes to"
  [{:keys [name probe-refcount-element refcount-target rc-frame rc-query]}]
  (testing (str name " — use-subscribe cleanup decrements sub-cache refcount (rf2-7g959)")
    (with-browser-act
     (fn [act-fn]
      (reset! refcount-target rc-frame)
      (rf/reg-frame rc-frame {:doc "refcount probe frame"})
      (rf/reg-event-db ::rc-seed (fn [_ _] {:m 0}))
      (rf/dispatch-sync [::rc-seed] {:frame rc-frame})
      (rf/reg-sub rc-query (fn [db _] (:m db)))
      (let [cache-key-v [rc-query]
            cache       (:sub-cache (frame/frame rc-frame))
            mount-node  (make-mount-node!)
            root        (react-dom-client/createRoot mount-node)]
        (try
          (act-fn (fn [] (.render root (probe-refcount-element))))
          (is (pos? (or (get-in @cache [cache-key-v :ref-count]) 0))
              "mounted probe pinned a cache entry with ref-count > 0")
          (act-fn (fn [] (.unmount root)))
          ;; After unmount the useEffect cleanup fires subs/unsubscribe;
          ;; per rf2-cmfln the entry is disposed synchronously on the
          ;; 1 → 0 transition. The ref-count is no longer pinned at >0
          ;; — the regression rf2-7g959 named.
          (is (or (nil? (get @cache cache-key-v))
                  (zero? (or (get-in @cache [cache-key-v :ref-count]) 0)))
              "post-unmount ref-count is zero (or entry already dropped) — rf2-7g959 cleanup fired")
          (finally
            (try (.unmount root) (catch :default _ nil)))))))))

(defn assert-use-subscribe-stable-deps-key
  "rf2-mwft2: stable-literal query-v across N re-renders ⇒ exactly one
  subs/subscribe call (the deps element is JS-ref-stable across renders),
  and the useEffect cleanup (rf2-7g959) fires only on unmount.

  cfg keys:
    :probe-stable-deps-element  thunk → the ProbeStableDepsParent element.
                          The parent owns a tick state + stashes its
                          set-tick fn into :stable-deps-set-tick on mount;
                          the child reads a fixed query-v via use-subscribe.
    :stable-deps-set-tick atom the parent stashes its setter into
    :stable-deps-frame    frame-id keyword the child resolves under
    :stable-deps-query    query-v keyword the child subscribes to"
  [{:keys [name probe-stable-deps-element stable-deps-set-tick stable-deps-frame stable-deps-query]}]
  (testing (str name " — use-subscribe stable deps key: one subscribe across N renders (rf2-mwft2)")
    (with-browser-act
     (fn [act-fn]
      (reset! stable-deps-set-tick nil)
      (rf/reg-frame stable-deps-frame {:doc "rf2-mwft2 stable-deps probe frame"})
      (rf/reg-event-db ::stable-deps-seed (fn [_ _] {:p 0}))
      (rf/dispatch-sync [::stable-deps-seed] {:frame stable-deps-frame})
      (rf/reg-sub stable-deps-query (fn [db _] (:p db)))
      (let [subscribe-calls   (atom 0)
            unsubscribe-calls (atom 0)
            real-subscribe    subs/subscribe
            real-unsubscribe  subs/unsubscribe
            cache-key-v       [stable-deps-query]
            cache             (:sub-cache (frame/frame stable-deps-frame))
            mount-node        (make-mount-node!)
            root              (react-dom-client/createRoot mount-node)]
        ;; Spies preserve the multi-arity shape of subs/subscribe and
        ;; subs/unsubscribe (`[query-v]` and `[frame-id query-v]`) so
        ;; spine call sites that bind the arity-2 invoke-slot resolve.
        ;; A bare `[& args]` variadic spy compiles only the variadic
        ;; slot and trips `…cljs$core$IFn$_invoke$arity$2 is not a
        ;; function` at the spine's subs/subscribe call.
        ;;
        ;; Both fns' 1-arity bodies recur into the 2-arity through the
        ;; Var (canonical-leaf is 2-arity per rf2-cmfln — there is no
        ;; 3-arity since the grace-period was retired). Without
        ;; bypassing, a single logical 1-arity call would trip the spy
        ;; twice (once on entry, once on the recursive 2-arity tail).
        ;; Each spy 1-arity therefore resolves the current frame itself
        ;; and dispatches to the 2-arity REAL directly, mirroring the
        ;; canonical body without routing back through the Var.
        (with-redefs [subs/subscribe
                      (fn spy-subscribe
                        ([query-v]
                         (swap! subscribe-calls inc)
                         (real-subscribe (frame/resolve-current-frame) query-v))
                        ([frame-id query-v]
                         (swap! subscribe-calls inc)
                         (real-subscribe frame-id query-v)))
                      subs/unsubscribe
                      (fn spy-unsubscribe
                        ([query-v]
                         (swap! unsubscribe-calls inc)
                         (real-unsubscribe (frame/resolve-current-frame) query-v))
                        ([frame-id query-v]
                         (swap! unsubscribe-calls inc)
                         (real-unsubscribe frame-id query-v)))]
          (try
            ;; Mount — one subs/subscribe for the useMemo factory.
            (act-fn (fn [] (.render root (probe-stable-deps-element))))
            (let [mounted-subs @subscribe-calls]
              (is (= 1 mounted-subs)
                  "mount triggered exactly one subs/subscribe call")
              (is (zero? @unsubscribe-calls)
                  "no subs/unsubscribe fires during initial mount")
              ;; Force five re-renders by bumping the parent's tick state.
              ;; Each parent render also re-renders the child probe with a
              ;; freshly-allocated CLJS vector for the query-v — without
              ;; the fix the deps mismatch would re-run useMemo (extra
              ;; subscribe) and useEffect (extra unsubscribe) each render.
              (dotimes [_ 5]
                (act-fn (fn [] (when-let [set-tick @stable-deps-set-tick]
                                 (set-tick inc)))))
              (is (= 1 @subscribe-calls)
                  "subs/subscribe still called only once after 5 re-renders (no per-render churn)")
              (is (zero? @unsubscribe-calls)
                  "subs/unsubscribe never fired across re-renders — useEffect cleanup is unmount-only")
              (is (= 1 (or (get-in @cache [cache-key-v :ref-count]) 0))
                  "sub-cache ref-count remains pinned at 1 across re-renders"))
            ;; Unmount must fire exactly one unsubscribe — the rf2-7g959
            ;; cleanup pairing must survive the rf2-mwft2 rewrite.
            (act-fn (fn [] (.unmount root)))
            (is (= 1 @unsubscribe-calls)
                "unmount fired exactly one subs/unsubscribe (rf2-7g959 cleanup survives the rf2-mwft2 rewrite)")
            (is (or (nil? (get @cache cache-key-v))
                    (zero? (or (get-in @cache [cache-key-v :ref-count]) 0)))
                "post-unmount cache entry dropped or ref-count at zero")
            (finally
              (try (.unmount root) (catch :default _ nil))))))))))

;; ---- StrictMode double-mount refcount (rf2-nymuy) -------------------------
;;
;; React 19's createRoot + <StrictMode> is the DEFAULT dev scaffold
;; (Vite/CRA/Next). StrictMode intentionally double-invokes effects on
;; mount: run-effect → run-cleanup → run-effect-again. For use-subscribe
;; that drives subscribe → unsubscribe (refcount 1 → 0, which per
;; rf2-cmfln disposes the cached reaction SYNCHRONOUSLY) → subscribe again
;; (fresh cache miss, rebuild). Because StrictMode is the default dev
;; environment for the substrates these adapters target, a real app hits
;; this path on literally every mount — but the single-mount tests above
;; (rf2-7g959, rf2-mwft2) never exercise the double-invoke churn. This
;; assertion closes that gap: the disposal+refcount dance is exactly the
;; class of bug that only surfaces under StrictMode double-invoke (a
;; disposed-then-derefed reaction, a watch leaked because remove-watch ran
;; against a stale reaction identity, or a ref-count driven below zero).

(defn assert-use-subscribe-strictmode-double-mount-refcount-balances
  "rf2-nymuy: mount the use-subscribe refcount probe wrapped in
  `React.StrictMode` under `act()`. StrictMode double-invokes the mount
  effect (effect → cleanup → effect), driving the spine's
  subscribe/unsubscribe refcount dance through a momentary 1 → 0 → 1
  transition. Asserts:

    (a) after the double-invoke settles, exactly one effect is live — the
        sub-cache ref-count is pinned at 1 (NOT 2 from a leaked first
        mount, NOT negative);
    (b) the observed subscribe/unsubscribe spy calls balance — net
        ref-count never drops below zero across the double-invoke;
    (c) after unmount the cache entry is dropped or its ref-count is 0;
    (d) the probe's deref observed a correct (non-throwing) value through
        the churn — no 'deref of disposed reaction'.

  Reuses the refcount-probe cfg surface (the probe reads its frame from
  `:refcount-target` and subscribes to `:rc-query` under `:rc-frame`).

  cfg keys:
    :probe-refcount-element  thunk → the ProbeRefcount element (2-arg form)
    :refcount-target         atom the ProbeRefcount reads its target
                             frame-id from
    :rc-frame                frame-id keyword for the refcount probe
    :rc-query                query-v keyword ProbeRefcount subscribes to"
  [{:keys [name probe-refcount-element refcount-target rc-frame rc-query]}]
  (testing (str name " — use-subscribe StrictMode double-mount keeps refcount balanced (rf2-nymuy)")
    (with-browser-act
     (fn [act-fn]
      (reset! refcount-target rc-frame)
      (rf/reg-frame rc-frame {:doc "rf2-nymuy StrictMode refcount probe frame"})
      (rf/reg-event-db ::sm-seed (fn [_ _] {:m 7}))
      (rf/dispatch-sync [::sm-seed] {:frame rc-frame})
      (rf/reg-sub rc-query (fn [db _] (:m db)))
      (let [subscribe-calls   (atom 0)
            unsubscribe-calls (atom 0)
            real-subscribe    subs/subscribe
            real-unsubscribe  subs/unsubscribe
            cache-key-v       [rc-query]
            cache             (:sub-cache (frame/frame rc-frame))
            mount-node        (make-mount-node!)
            root              (react-dom-client/createRoot mount-node)]
        ;; Spies mirror the rf2-mwft2 stable-deps-key bypass: preserve the
        ;; 1-/2-arity shape and dispatch straight to the canonical 2-arity
        ;; REAL so a single logical call is not double-counted through the
        ;; Var recur.
        (with-redefs [subs/subscribe
                      (fn spy-subscribe
                        ([query-v]
                         (swap! subscribe-calls inc)
                         (real-subscribe (frame/resolve-current-frame) query-v))
                        ([frame-id query-v]
                         (swap! subscribe-calls inc)
                         (real-subscribe frame-id query-v)))
                      subs/unsubscribe
                      (fn spy-unsubscribe
                        ([query-v]
                         (swap! unsubscribe-calls inc)
                         (real-unsubscribe (frame/resolve-current-frame) query-v))
                        ([frame-id query-v]
                         (swap! unsubscribe-calls inc)
                         (real-unsubscribe frame-id query-v)))]
          (try
            ;; Mount the probe wrapped in React.StrictMode — the
            ;; double-invoke fires effect → cleanup → effect on mount.
            (act-fn (fn []
                      (.render root
                               (React/createElement
                                 (.-StrictMode React) nil
                                 (probe-refcount-element)))))
            ;; After the double-invoke settles exactly ONE effect is live.
            ;; If the spine leaked the first mount's subscription (a stale
            ;; reaction identity, an unbalanced refcount) the ref-count
            ;; would read 2 here, or the entry would have been disposed to
            ;; 0/nil by a 1 → 0 transition that the re-subscribe failed to
            ;; restore.
            (is (= 1 (or (get-in @cache [cache-key-v :ref-count]) 0))
                (str "post-StrictMode-double-mount ref-count is exactly 1 "
                     "(no leaked first-mount subscription, no negative count) "
                     "— observed subscribe=" @subscribe-calls
                     " unsubscribe=" @unsubscribe-calls))
            ;; The double-invoke fires at least one extra subscribe AND one
            ;; extra unsubscribe vs a single mount — and they BALANCE: the
            ;; net (subscribe − unsubscribe) is exactly 1 (one live
            ;; subscription), never negative.
            (is (>= @subscribe-calls 2)
                "StrictMode double-invoked the subscribe (mount + remount)")
            (is (= 1 (- @subscribe-calls @unsubscribe-calls))
                (str "net subscribe − unsubscribe is exactly 1 across the "
                     "double-invoke (refcount never driven below zero) — "
                     "subscribe=" @subscribe-calls
                     " unsubscribe=" @unsubscribe-calls))
            ;; The probe's deref survived the disposed-then-rebuilt churn —
            ;; a working render (DOM present) proves no 'deref of disposed
            ;; reaction' threw during the StrictMode remount.
            (is (= "m=7" (.-textContent mount-node))
                "probe observed the correct subscribed value through the
                 StrictMode churn (no deref-of-disposed-reaction throw)")
            ;; Unmount returns the refcount to baseline.
            (act-fn (fn [] (.unmount root)))
            (is (or (nil? (get @cache cache-key-v))
                    (zero? (or (get-in @cache [cache-key-v :ref-count]) 0)))
                "post-unmount cache entry dropped or ref-count at zero — disposal balanced")
            (finally
              (try (.unmount root) (catch :default _ nil))))))))))

;; ---- unsubscribe arity contract (rf2-gizlj) -------------------------------
;;
;; The shared spine's `use-subscribe` useEffect cleanup calls
;; `subs/unsubscribe` with `[frame-id query-v]` — the canonical 2-arity
;; form. Per rf2-cmfln (Spec 006 §Reference counting and disposal) the
;; 3-arity `[frame-id query-v opts]` was retired with the grace-period
;; mechanism: the cache disposes synchronously on the 1 → 0 transition
;; and there are no more per-call overrides. The spine cleanup at
;; `re-frame.substrate.spine/use-subscribe-effect` is the only
;; production call site whose arity is invisible to the type checker
;; (it goes through the spy in the rf2-mwft2 stable-deps-key test).
;; This assertion locks the call-site arity so a future drift — adding
;; a third arg back, or shifting to a single-arity query-v call — fails
;; loudly here before reaching the cache layer.

(defn assert-use-subscribe-cleanup-calls-unsubscribe-with-2-args
  "rf2-gizlj: the React-hook spine's `use-subscribe` useEffect cleanup
  calls `subs/unsubscribe` with exactly 2 args (`[frame-id query-v]`).
  Per rf2-cmfln the canonical-leaf arity for `subs/unsubscribe` is 2;
  no `opts` map, no grace-period override. This test mounts a probe,
  unmounts it, and asserts the spy observed exactly 2 args at the
  cleanup call — drift here is what introduced the regression bug
  rf2-gizlj fixed.

  cfg keys: re-uses the same stable-deps-key probe surface — the
  cleanup fires on either parent here."
  [{:keys [name probe-stable-deps-element stable-deps-set-tick stable-deps-frame stable-deps-query]}]
  (testing (str name " — use-subscribe cleanup calls subs/unsubscribe with 2 args (rf2-gizlj, rf2-cmfln contract)")
    (with-browser-act
     (fn [act-fn]
      (reset! stable-deps-set-tick nil)
      (rf/reg-frame stable-deps-frame {:doc "rf2-gizlj arity probe frame"})
      (rf/reg-event-db ::gizlj-seed (fn [_ _] {:p 0}))
      (rf/dispatch-sync [::gizlj-seed] {:frame stable-deps-frame})
      (rf/reg-sub stable-deps-query (fn [db _] (:p db)))
      (let [unsubscribe-arg-counts (atom [])
            real-unsubscribe       subs/unsubscribe
            mount-node             (make-mount-node!)
            root                   (react-dom-client/createRoot mount-node)]
        ;; Spy records the arg-count at each call site and delegates to
        ;; the canonical 2-arity body (mirroring the existing spy bypass
        ;; — see the rf2-mwft2 stable-deps-key spy comment).
        (with-redefs [subs/unsubscribe
                      (fn spy-unsubscribe-arity
                        ([query-v]
                         (swap! unsubscribe-arg-counts conj 1)
                         (real-unsubscribe (frame/resolve-current-frame) query-v))
                        ([frame-id query-v]
                         (swap! unsubscribe-arg-counts conj 2)
                         (real-unsubscribe frame-id query-v)))]
          (try
            (act-fn (fn [] (.render root (probe-stable-deps-element))))
            (act-fn (fn [] (.unmount root)))
            ;; The spine's useEffect cleanup is the call site under
            ;; test. There may be additional unsubscribes from layer-2+
            ;; cascades, but the cleanup fn the spine wires must use
            ;; the 2-arity form — anything else is a contract violation.
            (let [arities (set @unsubscribe-arg-counts)]
              (is (seq @unsubscribe-arg-counts)
                  "spine fired at least one subs/unsubscribe across mount + unmount")
              (is (= #{2} arities)
                  (str "every spine-driven subs/unsubscribe call must be 2-arity "
                       "(frame-id + query-v) per rf2-cmfln Spec 006 §Reference "
                       "counting and disposal — observed arities: "
                       (pr-str @unsubscribe-arg-counts)
                       ". A 3-arity call here means the grace-period `opts` "
                       "shape has re-entered the spine; a 1-arity call means "
                       "the spine is dropping the explicit frame-id pin.")))
            (finally
              (try (.unmount root) (catch :default _ nil))))))))))

;; ---- view-unmount parity (rf2-te71r — React-hook twin of rf2-9hoos) -------
;;
;; Phase-A (rf2-9hoos) emits :rf.view/unmounted on the Reagent family via
;; a per-render-instance reaction-dispose hook; the React-hook substrates
;; (UIx / Helix) had no tracked render reaction to ride, so the views-side
;; arm no-oped there. rf2-te71r adds a React.useEffect empty-deps cleanup
;; in the shared spine's wrap-view that fires the emit on unmount. Real
;; mount/unmount needs a DOM (jsdom is NOT in the node runner), so this is
;; a browser-DOM assertion — the neighbour of refcount-cleanup-on-unmount.
;;
;; A registered view's wrapper (`(rf/view id)`) is rendered through
;; `React/createElement` as a function component so the spine wrap-view's
;; useEffect belongs to a real React instance whose teardown fires the
;; cleanup. The probe is built in the suite (raw `React/createElement`, no
;; substrate `defui`/`$` needed) so both UIx and Helix forward it
;; unchanged — a gap on one is a gap on both.

(defn assert-view-unmount-emits-on-react-hook-teardown
  "rf2-te71r: mounting then unmounting a registered view under a
  React-hook substrate (UIx / Helix) emits exactly one :rf.view/unmounted
  carrying the required :rf.view/id + :frame tags (plus the :rf.view/render-key
  instance tuple). The emit rides the spine wrap-view's React.useEffect
  empty-deps cleanup — the React-hook parity for the phase-A Reagent
  reaction-dispose unmount hook.

  cfg keys:
    :substrate-kw  keyword fragment used to mint a per-substrate view-id"
  [{:keys [substrate-kw name]}]
  (testing (str name " — :rf.view/unmounted fires on React-hook view teardown (rf2-te71r)")
    (with-browser-act
     (fn [act-fn]
      (let [view-id   (mint-kw substrate-kw "unmount-parity-probe")
            recorded  (atom [])]
        (trace-tooling/register-listener! ::view-unmounted-recorder
          (fn [ev]
            (when (= :rf.view/unmounted (:operation ev))
              (swap! recorded conj ev))))
        ;; Register a trivial DOM-rooted view. Its wrapper carries the
        ;; spine wrap-view's unmount-sentinel child (which holds the
        ;; useEffect arm). `(rf/view id)` returns a CLJS `MetaFn` (the
        ;; `:contextType` meta makes it an object, not a raw JS function),
        ;; so React cannot use it as a component type directly — mount it
        ;; via a plain JS-function host component that INVOKES the
        ;; registered view. The spine sentinel element rides in the
        ;; returned tree and React renders it as a real instance whose
        ;; teardown fires the cleanup.
        (rf/reg-view* view-id (fn [] (React/createElement "div" #js {} "probe")))
        (let [render-fn  (rf/view view-id)
              host       (fn host-cmp [_props] (render-fn))
              mount-node (make-mount-node!)
              root       (react-dom-client/createRoot mount-node)]
          (try
            (act-fn (fn [] (.render root (React/createElement host #js {}))))
            (is (empty? @recorded)
                "no :rf.view/unmounted before teardown")
            (act-fn (fn [] (.unmount root)))
            (is (= 1 (count @recorded))
                "exactly one :rf.view/unmounted fired on instance teardown")
            (when-let [ev (first @recorded)]
              (let [t (:tags ev)]
                (is (= view-id (:rf.view/id t)) ":rf.view/id tag matches the registered view")
                (is (some? (:frame t)) ":frame tag present")
                (is (vector? (:rf.view/render-key t)) ":rf.view/render-key is a tuple")
                (is (= view-id (first (:rf.view/render-key t)))
                    ":rf.view/render-key's head is the view-id")))
            (finally
              (trace-tooling/unregister-listener! ::view-unmounted-recorder)
              (try (.unmount root) (catch :default _ nil))))))))))
