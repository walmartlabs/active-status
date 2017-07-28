(ns com.walmartlabs.active-status.output
  "Utilities related to controlling unwanted output."
  {:added "0.1.16"}
  (:require
    [clojure.java.io :as io])
  (:import
    (java.io PrintStream)))

(defn with-output-redirected*
  "Rebinds `*out*` and `*err*` to files while executing the provided function.

  The status board should alredy be started and will continue to use the binding
  of `*out*` in place when it was created.

  The base-path will have suffixes `.out` and `.err` appended to it;
  the files and directory containing them will be created.

  Returns the result of invoking the function."
  [base-path f]
  (let [out-file (io/file (str base-path ".out"))
        err-file (io/file (str base-path ".err"))
        init-sys-out (System/out)
        init-sys-err (System/err)
        init-*err* *err*]
    (-> out-file
        .getCanonicalFile
        .getParentFile
        .mkdirs)
    (with-open [out-stream (io/output-stream out-file :append true)
                out (io/writer out-stream)
                out-ps (PrintStream. out-stream)
                err-stream (io/output-stream err-file :append true)
                err (io/writer err-stream)
                sys-err (PrintStream. err-stream)]
      (try
        ;; This seems redundant and unnecessary, but is actually important.
        ;; The most common case for using *err* is a Thread uncaughtExceptionHandler;
        ;; that happens so far outside of the Clojure stack that per-thread bindings
        ;; no longer apply. This forces Clojure (but not Java!) code that executes
        ;; in that context to still use the redirected writer.
        ;; Meanwhile, overriding System/err and /out means that Java code,
        ;; such as logging libraries and the default uncaughtExceptionHandler,
        ;; will still redirect where we want.
        (alter-var-root #'*err* (constantly err))
        (System/setErr sys-err)
        (System/setOut out-ps)
        (binding [*out* out
                  *err* err]
          (f))
        (finally
          (System/setOut init-sys-out)
          (System/setErr init-sys-err)
          (alter-var-root #'*err* (constantly init-*err*)))))))

(defmacro with-output-redirected
  "Rebinds `*out*` and `*err*` to files while executing the provided forms.

  The status board should alredy be started and will continue to use the binding
  of `*out*` in place when it was created.

  The base-path will have suffixes `.out` and `.err` appended to it;
  the files and directory containing them will be created.

  Returns the result of the final form."
  [base-path & forms]
  `(with-output-redirected* ~base-path (fn [] ~@forms)))

(defmacro with-output
  "Redirect `*out*` to the provided output PrintStream, and locks it before
  executing the body."
  [out & body]
  `(binding [*out* ~out]
     (locking *out*
       ~@body)))
