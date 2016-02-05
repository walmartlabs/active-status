## 0.1.2 - UNRELEASED

Added `*terminal-type*` Var, to override default terminate type passed to the `tput` command.

Limits refreshes of the status board to intervals; 100ms by default.

No longer "looses" job channel updates; the refresh interval means that the status board
can keep up.

## 0.1.1 - 02 Feb 2016

Fix bug where status would be indented by one space.

## 0.1.0 - 28 Jan 2016

Initial release.
