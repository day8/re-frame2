(ns re-frame.test-quiet-stderr-ring-concurrency-test
  "Concurrency regression for the JVM runner's central stderr ring
  (`re-frame.test-quiet.runner`).

  `-main` funnels BOTH JVM stderr channels into one
  `java.lang.StringBuilder` ring: the test-driver thread's `*err*` through
  a `PrintWriter`, and raw process-global `System.err` through a
  `System/setErr` `PrintStream` over an `OutputStream` bridge. Those two
  wrappers each serialize only their OWN calls, under DISTINCT locks — so
  before this fix a write from each channel could interleave inside
  `buffering-stderr-writer`'s append-plus-front-trim transaction and tear
  the StringBuilder's internal count/array, throwing
  `ArrayIndexOutOfBoundsException` from an otherwise-valid test (a reporter
  bug reddening a passing suite). `install-summary-replay-hook!` also read
  the ring (`.length`/`.toString`) with no coordination, so a red replay
  could snapshot a half-applied mutation.

  These tests reconstruct the EXACT production wiring in-process (the
  private ring writer + a `PrintWriter` for `*err*` + the same
  `OutputStream`->`PrintStream` UTF-8 bridge for `System.err`), release two
  writers together past the ring cap over many trials, and prove: neither
  channel throws, the ring stays at or below `stderr-buffer-cap`, each
  channel's newest tail write survives, and a `locking`-coordinated
  snapshot taken CONCURRENTLY with the writers (the shape
  `install-summary-replay-hook!` uses) never observes a torn ring. The
  real `:summary` hook is covered end-to-end by the subprocess red fixture
  in `re-frame.test-quiet-runner-contract-test`.

  On the pre-fix (unsynchronized) runner these trials throw
  `ArrayIndexOutOfBoundsException` intermittently (~1 in 5 trials in the
  originating audit); the trial counts here make that detection reliable."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.test-quiet.runner]))

(def ^:private stderr-buffer-cap
  "The runner's private ring cap (256 KiB, characters)."
  @#'re-frame.test-quiet.runner/stderr-buffer-cap)

(defn- make-wiring
  "Reconstruct `-main`'s stderr wiring over a fresh ring: returns
  `{:sb :err-channel :sys-channel}` where `:err-channel` is the
  `*err*`-side `PrintWriter` and `:sys-channel` is the `System.err`-side
  UTF-8 `PrintStream` over the identical `OutputStream` bridge `-main`
  installs. Both funnel into the same private `buffering-stderr-writer`
  over `:sb`."
  []
  (let [sb         (StringBuilder.)
        buffered-w (#'re-frame.test-quiet.runner/buffering-stderr-writer sb)
        ;; `*err*` channel — a PrintWriter, exactly as `-main` binds `*err*`.
        err-channel (java.io.PrintWriter. buffered-w)
        ;; `System.err` channel — the same OutputStream->PrintStream(UTF-8)
        ;; bridge `-main` installs via `System/setErr`.
        sys-bridge  (proxy [java.io.OutputStream] []
                      (write
                        ([b]
                         (.write buffered-w (String. (byte-array [(unchecked-byte b)]))))
                        ([b off len]
                         (.write buffered-w (String. ^bytes b (int off) (int len))))))
        sys-channel (java.io.PrintStream. sys-bridge true "UTF-8")]
    {:sb sb :err-channel err-channel :sys-channel sys-channel}))

(def ^:private big-line
  "A single write comfortably larger than the 256 KiB ring cap, so every
  write drives the front-trim `.delete` — the mutation the concurrent
  `.append` races. 400,000 chars matches the audit's repro payload."
  (apply str (repeat 400000 \x)))

(deftest concurrent-dual-channel-writes-do-not-corrupt-the-ring
  (testing "releasing *err* and raw System.err writers together past the cap never throws or exceeds the cap"
    (let [trials       30
          writes-each  3
          failures     (atom [])]
      (dotimes [t trials]
        (let [{:keys [sb err-channel sys-channel]} (make-wiring)
              gate (promise)
              ;; Release both channels together so their writes overlap
              ;; inside the shared ring's append+trim.
              f-err (future @gate
                            (dotimes [_ writes-each] (.println err-channel big-line)))
              f-sys (future @gate
                            (dotimes [_ writes-each] (.println sys-channel big-line)))]
          (deliver gate :go)
          (let [thrown (try @f-err @f-sys nil
                            (catch Throwable e e))]
            (when thrown
              (swap! failures conj [t (.getMessage ^Throwable thrown)])))
          ;; After the concurrent flood, write ONE distinct newest marker per
          ;; channel (sequentially — no race on the markers themselves) and
          ;; prove each channel's newest write survives inside a bounded ring.
          (.println err-channel (str "ERR-TAIL-" t))
          (.println sys-channel (str "SYS-TAIL-" t))
          ;; Bind booleans / the length BEFORE asserting so clojure.test's
          ;; `actual:` form never embeds the up-to-256-KiB ring string — a
          ;; failing trial stays readable rather than dumping the whole ring.
          (let [ring      (locking sb (.toString sb))
                ring-len  (.length sb)
                err-tail? (str/includes? ring (str "ERR-TAIL-" t))
                sys-tail? (str/includes? ring (str "SYS-TAIL-" t))]
            (is (<= ring-len stderr-buffer-cap)
                (str "the ring must stay at or below the " stderr-buffer-cap
                     "-char cap after a concurrent flood; got " ring-len
                     " chars on trial " t))
            (is err-tail?
                (str "the *err* channel's newest write must survive the ring on"
                     " trial " t " (ring-len=" ring-len ")"))
            (is sys-tail?
                (str "the System.err channel's newest write must survive the"
                     " ring on trial " t " (ring-len=" ring-len ")")))))
      (is (empty? @failures)
          (str "concurrent writes through the two JVM stderr channels must"
               " never throw (a torn StringBuilder is a reporter bug that"
               " reddens a passing test); got throwing trials: "
               (pr-str @failures))))))

(deftest summary-snapshot-during-concurrent-writes-is-consistent
  (testing "a locking-coordinated ring snapshot taken concurrently with writers never tears"
    (let [{:keys [sb err-channel sys-channel]} (make-wiring)
          gate         (promise)
          writer-errs  (atom [])
          snap-errs    (atom [])
          snaps        (atom [])
          writes-each  40
          f-err (future @gate
                        (try (dotimes [_ writes-each] (.println err-channel big-line))
                             (catch Throwable e (swap! writer-errs conj (.getMessage e)))))
          f-sys (future @gate
                        (try (dotimes [_ writes-each] (.println sys-channel big-line))
                             (catch Throwable e (swap! writer-errs conj (.getMessage e)))))
          ;; The snapshotter mirrors EXACTLY what `install-summary-replay-hook!`
          ;; does: read the ring under `sb`'s monitor. It must never observe a
          ;; half-applied append+trim, so every snapshot is a valid String
          ;; bounded by the cap.
          f-snap (future
                   @gate
                   (loop []
                     (when (or (not (realized? f-err)) (not (realized? f-sys)))
                       (try
                         (let [captured (locking sb
                                          (when (pos? (.length sb)) (.toString sb)))]
                           (when captured (swap! snaps conj (.length ^String captured))))
                         (catch Throwable e (swap! snap-errs conj (.getMessage e))))
                       (recur))))]
      (deliver gate :go)
      @f-err @f-sys @f-snap
      (is (empty? @writer-errs)
          (str "concurrent writers must not throw; got: " (pr-str @writer-errs)))
      (is (empty? @snap-errs)
          (str "a locking-coordinated snapshot must never tear on a concurrent"
               " write; got: " (pr-str @snap-errs)))
      (is (pos? (count @snaps))
          "the snapshotter must have observed the ring at least once mid-flood")
      (is (every? #(<= % stderr-buffer-cap) @snaps)
          (str "every mid-flood snapshot must be bounded by the cap; got a"
               " snapshot over " stderr-buffer-cap ": "
               (pr-str (remove #(<= % stderr-buffer-cap) @snaps)))))))
