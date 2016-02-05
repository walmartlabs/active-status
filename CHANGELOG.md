## 0.1.3 - 05 Feb 2016

Added `*terminal-type*` Var, to override default terminal type passed to the `tput` command.

There status board now refreshes at an interval (that defaults to 100ms), rather than
after every change.
This greatly reduces the amount of output to `*out*`.

Because of the optimizations to output, the job channels are no longer lossy.

## 0.1.2 - 02 Feb 2016

Fix bug where status would be indented by one space.

## 0.1.0 - 28 Jan 2016

Initial release.
