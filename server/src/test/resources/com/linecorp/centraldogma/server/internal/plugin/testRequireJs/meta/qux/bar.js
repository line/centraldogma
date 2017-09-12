define(['../baz', '../util/util'], function (baz, util) {
  return {
    greet: function (name) {
      return baz.howdy(util.concat("'", name, "'"));
    }
  };
});
