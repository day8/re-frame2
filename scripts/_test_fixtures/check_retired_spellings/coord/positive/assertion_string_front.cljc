(ns fixture.coord.positive.assertion-string-front
  "POSITIVE fixture (d/string) — THE ONE THIS RULE EXISTS FOR.

  Reproduces the rf2-hic-007 regression verbatim in shape: a GREEN test
  assertion pinning a shipped refusal's `:where` to a benchmark-tree namespace
  the shipped package does not contain. The coordinate is a STRING, so every
  `'front.` scan is blind to it — including the two the sweep's own author used
  to verify the close. It surfaced only in CI.

  The symbol rule cannot see this line: string literals are masked before the
  symbol pattern runs. The finding must come from the STRING pattern, and there
  must be exactly one of them.")

(deftest a-shared-row-is-raised-by-the-runtimes-own-guard
  (is (seq (filter #(str/starts-with? (str (:where (:refuses %))) "front.codec/")
                   shared))
      "at least one row's refusal is raised by the runtime's own guard"))
