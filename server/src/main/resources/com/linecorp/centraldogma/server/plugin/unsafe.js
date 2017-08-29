(function (global) {
  'use strict';

  var JavaPromise = Java.type('io.netty.util.concurrent.DefaultPromise');

  // NOTE: Plugin.java sets additional properties to __UNSAFE__ after this script is evaluated.
  var unsafe = global.__UNSAFE__ = {
    // Functions written for interfacing with Java, mostly used by polyfills.
    polyfills: Java.type('com.linecorp.centraldogma.server.plugin.Polyfills'),
    // Reads the content of the specified file in the classpath.
    loadResource: function (path) {
      return unsafe.polyfills.loadResource(path);
    },
    // Invokes the specified function and converts the ES6 promise into Java/Netty promise if necessary.
    invoke: function (object, funcName) {
      var args = [].slice.call(arguments, 2);
      var result = object[funcName].apply(global, args);

      // Return as is if 'result' is not a promise.
      if (typeof result.then !== 'function' || typeof result.catch !== 'function') {
        return result;
      }

      // Convert 'result' to Java/Netty promise.
      var javaPromise = new JavaPromise(unsafe.eventLoop);
      result.then(function (result) {
        javaPromise.trySuccess(result);
      }, function (err) {
        javaPromise.tryFailure(err);
      });

      return javaPromise;
    }
  };
})(this);
