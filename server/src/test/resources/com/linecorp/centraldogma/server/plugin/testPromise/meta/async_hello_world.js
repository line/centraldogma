define([], function () {

  var exports = {};

  exports.hello = function (name) {
    return new Promise(function (resolve, reject) {
      setTimeout(function () {
        resolve("Hell" + name + "o, !");
      }, 100);
    });
  };

  return exports;
});
