# shellcheck shell=sh
# python.sh — the Python interpreter for the .github/scripts that run one.
#
# Sourced, never run:
#
#     SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
#     # shellcheck disable=SC1091  # lib/python.sh is shellchecked on its own.
#     . "$SCRIPT_DIR/lib/python.sh"
#     python=$(resolve_python) || exit 2
#     $python - "$ARG" <<'PY'
#
# `resolve_python` prints the first candidate that runs Python 3.10+ and
# returns 0. When none does, it names each candidate and what it did on
# stderr and returns 1. Expand `$python` UNQUOTED, so that `py -3` splits
# into a command and its argument.
#
# Each candidate is RUN rather than looked up, because a name that resolves is
# not a Python: on Windows the Microsoft Store's app-execution aliases put a
# `python` and a `python3` on PATH that print an install hint and exit 49, so
# `command -v python3` succeeds and the script's Python never runs.
# Candidates, in order: `python3`, `python`, then the Windows launcher's
# `py -3`. `python3` goes first so that wherever it works — the Linux runners
# these scripts are written for — the script runs under exactly the
# interpreter a bare `python3` would pick. `implementation/scripts/lib/
# python.cjs` and `scripts/test-fast-pr.sh` (PYTHON LAUNCHER) resolve the same
# way for the npm scripts and the fast-PR spine.
#
# POSIX sh, and safe under `set -eu`; every name it assigns carries the
# `_rf2_py` prefix.

# Answers `<major>.<minor>` and exits 1 below 3.10; Python 2 parses it too,
# so an old interpreter is named by its version rather than by a syntax error.
_rf2_py_probe='import sys; sys.stdout.write("%d.%d" % sys.version_info[:2]); sys.exit(sys.version_info < (3, 10))'

resolve_python() {
  _rf2_py_tried=''
  for _rf2_py in python3 python 'py -3'; do
    # shellcheck disable=SC2086  # `py -3` must split into command + argument.
    if _rf2_py_answer=$($_rf2_py -c "$_rf2_py_probe" </dev/null 2>/dev/null); then
      _rf2_py_status=0
    else
      _rf2_py_status=$?
    fi
    case "$_rf2_py_status:$_rf2_py_answer" in
      0:[0-9]*.[0-9]*)
        printf '%s\n' "$_rf2_py"
        return 0 ;;
      1:[0-9]*.[0-9]*) _rf2_py_why="Python $_rf2_py_answer, older than 3.10" ;;
      127:*) _rf2_py_why='not found' ;;
      49:*) _rf2_py_why='exit 49 — the Microsoft Store alias, which resolves on PATH but is not Python' ;;
      0:*) _rf2_py_why="answered '$_rf2_py_answer', not a Python version" ;;
      *) _rf2_py_why="exit $_rf2_py_status" ;;
    esac
    _rf2_py_tried="$_rf2_py_tried$(printf '\n  %-7s  %s' "$_rf2_py" "$_rf2_py_why")"
  done
  printf '::error::no Python 3.10+ interpreter runs on this host. Tried, in order:%s\nInstall Python 3.10+ so that one of these runs it.\n' "$_rf2_py_tried" >&2
  return 1
}
