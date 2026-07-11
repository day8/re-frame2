(ns re-frame.ui.compiler
  "Compiler entry for the re-frame.ui substrate — .cljc because the
  compiler runs on the JVM during CLJS compilation (and emits JVM trees
  for SSR/testing consumers).

  S1a skeleton (rf2-vxgfnd.1): empty-but-loadable. The S1b compiler
  slice (rf2-vxgfnd.2) lands the template AST, analyzer, CLJS emitter
  and JVM emitter here (no reactivity in S1).")
