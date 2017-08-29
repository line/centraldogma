define(['util/util'], function (util) {
  return {
    howdy: function (name) {
      return util.concat('Howdy, ', name, '!');
    }
  };
});
