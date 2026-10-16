"""The Python reader for scripts/jvm-lanes.txt, the one list of JVM lanes.

The rows file documents its own five fields. This module parses them for the
Python gates that need the lanes: check_test_lane_bijection.py,
check_fast_pr_gap.py and check_jvm_lanes.py. The shell reader is
scripts/jvm-lanes.sh, and both readers refuse a malformed row rather than skip
it, because a skipped row is a lane that silently stops existing.

Stdlib only: check_fast_pr_gap.py runs in a CI job that installs no packages.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

ROWS_FILE = "scripts/jvm-lanes.txt"


class RowsError(ValueError):
    """A rows file that does not parse, naming the file and the line."""


@dataclass(frozen=True)
class Row:
    dir: str
    aliases: tuple
    slow: bool
    predicate: str
    ci: str
    line: int

    @property
    def matrix(self) -> bool:
        """True when the row runs as legs of test.yml's `jvm` matrix job."""
        return self.ci == "matrix"

    @property
    def roster(self) -> str:
        """The local roster that runs it: `implementation` or `tools`."""
        return "implementation" if self.dir.startswith("implementation/") else "tools"


def parse_rows(text: str, source: str = ROWS_FILE) -> list:
    rows = []
    for n, raw in enumerate(text.splitlines(), 1):
        fields = raw.split("#", 1)[0].split()
        if not fields:
            continue
        if len(fields) != 5:
            raise RowsError(
                f"{source}:{n}: a row is five fields (dir aliases slow predicate ci), "
                f"found {len(fields)}: {raw.strip()}"
            )
        directory, aliases, slow, predicate, ci = fields
        if slow not in ("slow", "-"):
            raise RowsError(f"{source}:{n}: the slow field is `slow` or `-`, not `{slow}`")
        rows.append(Row(directory, tuple(aliases.split(",")), slow == "slow", predicate, ci, n))
    return rows


def read_rows(repo_root) -> list:
    path = Path(repo_root) / ROWS_FILE
    return parse_rows(path.read_text(encoding="utf-8"), ROWS_FILE)


def matrix_legs(rows) -> list:
    """Every (dir, alias) leg the `jvm` matrix job runs when all rows fire."""
    return [(row.dir, alias) for row in rows if row.matrix for alias in row.aliases]
