(function () {
  'use strict';

  var pluginInitPromise = __UNSAFE__.pluginInitPromise;
  var polyfills = __UNSAFE__.polyfills;

  require(
      ['classpath:es6-promise-3.0.2'],
      function (promise) {
        try {
          promise.polyfill();
          require(
              [__UNSAFE__.pluginPath],
              function (plugin) {
                pluginInitPromise.setSuccess(plugin);
              },
              function (err) {
                pluginInitPromise.tryFailure(polyfills.exception(err));
              });
        } catch (err) {
          pluginInitPromise.tryFailure(polyfills.exception(err));
        }
      },
      function (err) {
        pluginInitPromise.tryFailure(polyfills.exception(err));
      });
})();
