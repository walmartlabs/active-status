## 0.1.14 - 18 Nov 2016

Fix output bug in prior version.

## 0.1.13 - 18 Nov 2016

Correct invalid documentation.

Correctly handle options passed to add-job.

Allow :prefix as an option to add-job.

## 0.1.12 - 10 Nov 2016

Fix an NPE when using the StatusBoardComponent.

## 0.1.11 - 2 Sep 2016

**Incompatible changes:**

The value returned from console-status-board is no longer a channel;
a new protocol with method shutdown! has been added. 

[Closed Issues](https://github.com/walmartlabs/active-status/issues?q=milestone%3A0.1.11)

## 0.1.10 - 26 Aug 2016

Fixed the minimal-status-board, which was completely broken.

[Closed Issues](https://github.com/walmartlabs/active-status/issues?q=milestone%3A0.1.10)

## 0.1.9 - 18 Jul 2016

Update to active-status 0.2.0, which means (currently alpha)
Clojure 1.9.

Remove some other external dependencies.

Fixed a bug that broke the console when using Clojure 1.9.

## 0.1.8 - 1 Jul 2016

The new `with-output-redirected` macro allows standard out and
standard error to be redirected to a pair of files, preventing
unwanted interference with the status board.

The formatting of progress bars has changed to use simple
Unicode block characters.

## 0.1.7 - 24 Jun 2016

The `tput` function is now part of the public API.

Progress reporting has been changed around considerably, and it
is now possible to override the default way progress is formatted,
or override progress formatting for an individual job.

## 0.1.6 - 24 May 2016

Changed the terminal capability codes used for cursor movement to be more
standard, matching more terminals across more operating systems.

Changed the `status-board` function to return a system map with key
:status-board, rather than just the component itself. 

## 0.1.5 - 8 Apr 2016

Lock `*out*` during output. This helps to ensure that output from the console does not
interleave with exception output written to the console.

## 0.1.4 - 11 Mar 2016

It is now possible to set a prefix for a job; typically used to identify the job (much like a thread name).

An alternate, simplified implementation of the status board is now available as
`com.walmartlabs.active-status.minimal-board/minimal-status-board`. This is suitable for use
during development, when a proper terminal is not available.

Added a simple component wrapper around the status board.

[Closed Issues](https://github.com/walmartlabs/active-status/issues?q=milestone%3A0.1.4)

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
