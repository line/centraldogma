(function (global) {
  'use strict';

  if (typeof global.console === 'undefined') {
    var noop = function () {};
    global.console = {
      memory: {},
      assert: function (assertion) {
        if (assertion === false) {
          __UNSAFE__.polyfills.consoleError(__UNSAFE__.logger, [].slice.call(arguments, 1));
        }
      },
      clear: noop,
      count: noop,
      debug: function () {
        __UNSAFE__.polyfills.consoleDebug(__UNSAFE__.logger, arguments);
      },
      dir: function () {
        __UNSAFE__.polyfills.consoleDebug(__UNSAFE__.logger, arguments);
      },
      dirxml: function () {
        __UNSAFE__.polyfills.consoleDebug(__UNSAFE__.logger, arguments);
      },
      error: function () {
        __UNSAFE__.polyfills.consoleError(__UNSAFE__.logger, arguments);
      },
      exception: function () {
        __UNSAFE__.polyfills.consoleError(__UNSAFE__.logger, arguments);
      },
      group: noop,
      groupCollapsed: noop,
      groupEnd: noop,
      info: function () {
        __UNSAFE__.polyfills.consoleInfo(__UNSAFE__.logger, arguments);
      },
      log: function () {
        __UNSAFE__.polyfills.consoleDebug(__UNSAFE__.logger, arguments);
      },
      markTimeline: noop,
      profile: noop,
      profiles: noop,
      profileEnd: noop,
      show: noop,
      table: function () {
        __UNSAFE__.polyfills.consoleDebug(__UNSAFE__.logger, arguments);
      },
      time: noop,
      timeEnd: noop,
      timeline: noop,
      timelineEnd: noop,
      timeStamp: noop,
      trace: function () {
        __UNSAFE__.polyfills.consoleTrace(__UNSAFE__.logger, new Error().stack);
      },
      warn: function () {
        __UNSAFE__.polyfills.consoleWarn(__UNSAFE__.logger, arguments);
      }
    };
  }
})(this);
