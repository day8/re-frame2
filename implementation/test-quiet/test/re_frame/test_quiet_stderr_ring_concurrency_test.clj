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
  bug reddening a passing suite). `make-summary-replay-method` also read
  the ring (`.length`/`.toString`) with no coordination, so a red replay
  could snapshot a half-applied mutation.

  These tests reconstruct the EXACT production wiring in-process (the
  private ring writer + a `PrintWriter` for `*err*` + the same
  `OutputStream`->`PrintStream` UTF-8 bridge for `System.err`), release two
  writers together past the ring cap over many trials, and prove: neither
  channel throws, the ring stays at or below `stderr-buffer-cap`, each
  channel's newest tail write survives, and a `locking`-coordinated
  snapshot taken CONCURRENTLY with the writers (the shape
  `make-summary-replay-method` uses) never observes a torn ring. The
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

(defn- make-stderr-wiring
  "Reconstruct `-main`'s stderr wiring over a fresh ring: returns
  `{:stderr-ring :dynamic-err-writer :system-err-stream}` where
  `:dynamic-err-writer` is the `*err*`-side `PrintWriter` and
  `:system-err-stream` is the `System.err`-side
  UTF-8 `PrintStream` over the identical `OutputStream` bridge `-main`
  installs. Both funnel into the same private `buffering-stderr-writer`
  over `:stderr-ring`."
  []
  (let [stderr-ring (StringBuilder.)
        buffered-stderr-writer
        (#'re-frame.test-quiet.runner/buffering-stderr-writer stderr-ring)
        ;; `*err*` channel — a PrintWriter, exactly as `-main` binds `*err*`.
        dynamic-err-writer (java.io.PrintWriter. buffered-stderr-writer)
        ;; `System.err` channel — the same OutputStream->PrintStream(UTF-8)
        ;; bridge `-main` installs via `System/setErr`.
        system-err-bridge
        (proxy [java.io.OutputStream] []
          (write
            ([byte-value]
             (.write buffered-stderr-writer
                     (String. (byte-array [(unchecked-byte byte-value)]))))
            ([byte-buffer offset length]
             (.write buffered-stderr-writer
                     (String. ^bytes byte-buffer (int offset) (int length))))))
        system-err-stream (java.io.PrintStream. system-err-bridge true "UTF-8")]
    {:stderr-ring       stderr-ring
     :dynamic-err-writer dynamic-err-writer
     :system-err-stream system-err-stream}))

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
      (dotimes [trial-index trials]
        (let [{:keys [stderr-ring dynamic-err-writer system-err-stream]}
              (make-stderr-wiring)
              start-gate (promise)
              ;; Release both channels together so their writes overlap
              ;; inside the shared ring's append+trim.
              dynamic-err-future
              (future @start-gate
                      (dotimes [_ writes-each]
                        (.println dynamic-err-writer big-line)))
              system-err-future
              (future @start-gate
                      (dotimes [_ writes-each]
                        (.println system-err-stream big-line)))]
          (deliver start-gate :go)
          (let [thrown (try @dynamic-err-future @system-err-future nil
                            (catch Throwable e e))]
            (when thrown
              (swap! failures conj
                     [trial-index (.getMessage ^Throwable thrown)])))
          ;; After the concurrent flood, write ONE distinct newest marker per
          ;; channel (sequentially — no race on the markers themselves) and
          ;; prove each channel's newest write survives inside a bounded ring.
          (.println dynamic-err-writer (str "ERR-TAIL-" trial-index))
          (.println system-err-stream (str "SYS-TAIL-" trial-index))
          ;; Bind booleans / the length BEFORE asserting so clojure.test's
          ;; `actual:` form never embeds the up-to-256-KiB ring string — a
          ;; failing trial stays readable rather than dumping the whole ring.
          (let [ring-text        (locking stderr-ring (.toString stderr-ring))
                ring-length      (.length stderr-ring)
                dynamic-err-tail? (str/includes? ring-text
                                                 (str "ERR-TAIL-" trial-index))
                system-err-tail? (str/includes? ring-text
                                                (str "SYS-TAIL-" trial-index))]
            (is (<= ring-length stderr-buffer-cap)
                (str "the ring must stay at or below the " stderr-buffer-cap
                     "-char cap after a concurrent flood; got " ring-length
                     " chars on trial " trial-index))
            (is dynamic-err-tail?
                (str "the *err* channel's newest write must survive the ring on"
                     " trial " trial-index " (ring-length=" ring-length ")"))
            (is system-err-tail?
                (str "the System.err channel's newest write must survive the"
                     " ring on trial " trial-index
                     " (ring-length=" ring-length ")")))))
      (is (empty? @failures)
          (str "concurrent writes through the two JVM stderr channels must"
               " never throw (a torn StringBuilder is a reporter bug that"
               " reddens a passing test); got throwing trials: "
               (pr-str @failures))))))

(deftest summary-snapshot-during-concurrent-writes-is-consistent
  (testing "a locking-coordinated ring snapshot taken concurrently with writers never tears"
    (let [{:keys [stderr-ring dynamic-err-writer system-err-stream]}
          (make-stderr-wiring)
          start-gate       (promise)
          writer-errors    (atom [])
          snapshot-errors  (atom [])
          snapshot-lengths (atom [])
          writes-each      40
          dynamic-err-future
          (future @start-gate
                  (try (dotimes [_ writes-each]
                         (.println dynamic-err-writer big-line))
                       (catch Throwable error
                         (swap! writer-errors conj (.getMessage error)))))
          system-err-future
          (future @start-gate
                  (try (dotimes [_ writes-each]
                         (.println system-err-stream big-line))
                       (catch Throwable error
                         (swap! writer-errors conj (.getMessage error)))))
          ;; The snapshotter mirrors EXACTLY what `make-summary-replay-method`
          ;; does: read the ring under `stderr-ring`'s monitor. It must never
          ;; observe a half-applied append+trim, so every snapshot is a valid
          ;; String bounded by `stderr-buffer-cap`.
          snapshot-future (future
                   @start-gate
                   (loop []
                     (when (or (not (realized? dynamic-err-future))
                               (not (realized? system-err-future)))
                       (try
                         (let [captured-stderr
                               (locking stderr-ring
                                 (when (pos? (.length stderr-ring))
                                   (.toString stderr-ring)))]
                           (when captured-stderr
                             (swap! snapshot-lengths conj
                                    (.length ^String captured-stderr))))
                         (catch Throwable error
                           (swap! snapshot-errors conj (.getMessage error))))
                       (recur))))]
      (deliver start-gate :go)
      @dynamic-err-future @system-err-future @snapshot-future
      (is (empty? @writer-errors)
          (str "concurrent writers must not throw; got: " (pr-str @writer-errors)))
      (is (empty? @snapshot-errors)
          (str "a locking-coordinated snapshot must never tear on a concurrent"
               " write; got: " (pr-str @snapshot-errors)))
      (is (pos? (count @snapshot-lengths))
          "the snapshotter must have observed the ring at least once mid-flood")
      (is (every? #(<= % stderr-buffer-cap) @snapshot-lengths)
          (str "every mid-flood snapshot must be bounded by the cap; got a"
               " snapshot over " stderr-buffer-cap ": "
               (pr-str (remove #(<= % stderr-buffer-cap)
                               @snapshot-lengths)))))))
