(function(context) {
  'use strict';

  var MILLISECONDS = Java.type('java.util.concurrent.TimeUnit').MILLISECONDS;
  var unsafe = context.__UNSAFE__;

  context.setImmediate = function(fn) {
    var args = [].slice.call(arguments, 1, arguments.length);

    return unsafe.polyfills.setImmediate(
        unsafe.eventLoop, function () {
          try {
            fn.apply(context, args);
          } catch (e) {
            context.console.warn(e);
          }
        });
  };

  context.setTimeout = function(fn, millis) {
    var args = [].slice.call(arguments, 2, arguments.length);

    return unsafe.polyfills.setTimeout(
        unsafe.eventLoop, function () {
          try {
            fn.apply(context, args);
          } catch (e) {
            context.console.warn(e);
          }
        }, millis);
  };

  context.setInterval = function(fn, delay) {
    var args = [].slice.call(arguments, 2, arguments.length);
    return unsafe.eventLoop.scheduleWithFixedDelay(function () {
      try {
        fn.apply(context, args);
      } catch (e) {
        context.console.warn(e);
      }
    }, delay, delay, MILLISECONDS);
  };

  var cancel = context.clearImmediate = function(cancel) {
    if (cancel && cancel.cancel) {
      cancel.cancel();
    }
  };

  context.clearTimeout = cancel;
  context.clearInterval = cancel;
})(this);
