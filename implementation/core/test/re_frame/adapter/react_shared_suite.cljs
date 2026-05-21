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
    - write-after-destroy nil-container guard"
  (:require ["react" :as React]
            [cljs.test :refer-macros [is testing]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.disposable :as rf-disposable]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace.tooling :as trace-tooling]))

;; ===========================================================================
;; helpers
;; ===========================================================================

(defn- react-element-attr
  "Pull `attr` (a string key) off a React element's `.-props`, or nil."
  [el attr]
  (when (and el (.-props el))
    (aget (.-props el) attr)))

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
        (testing "routed read via rf/current-frame also recovers"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) "")
          (is (= :rf/default (rf/current-frame))
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
        (let [container (frame/get-frame-db fid)]
          (is (nil? container) "get-frame-db on a destroyed frame returns nil")
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
