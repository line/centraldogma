(function () {
  'use strict';

  requirejs.config({
    baseUrl: "/"
  });

  var nextTick = requirejs.nextTick = setImmediate;
  var onError = requirejs.defaultOnError = function (err) {
    if (typeof __UNSAFE__.pluginInitPromise !== 'undefined' &&
        __UNSAFE__.pluginInitPromise.tryFailure(__UNSAFE__.polyfills.exception(err))) {
      return;
    }

    console.warn("Unexpected error while loading module(s):", err.stack ? err.stack : err);
  };

  requirejs.exec = function (text, url) {
    var __contextLeaked__ = true; // This should never be visible in the function body below.

    var name = typeof url !== 'undefined' ? url : 'unknown';
    var script =
        '{if (typeof __contextLeaked__ !== "undefined") throw new Error("unexpected context leak");}' + text;

    load({ name: name, script: script });
  };

  requirejs.load = function (context, moduleName, url) {
    context.nextTick = nextTick;
    context.onError = onError;

    // Append the file extension if missing.
    if (url.length > 3 && url.lastIndexOf('.js') !== url.length - 3) {
      url += '.js';
    }

    console.debug("Loading module: %s at %s", moduleName, url);

    try {
      var content;
      if (url.indexOf("classpath:") === 0) {
        var path = url.substring(10);
        content = __UNSAFE__.loadResource(path);
        if (content === null) {
          throw new Error('classpath resource not found: ' + path);
        }
      } else {
        var entry = __UNSAFE__.pluginRepository.get(__UNSAFE__.pluginRevision, url);
        content = entry.contentAsText();
      }

      requirejs.exec(content, url);
      console.debug("Loaded module:", moduleName);
      context.completeLoad(moduleName);
    } catch (e) {
      context.onError(e);
    }
  };
})();
