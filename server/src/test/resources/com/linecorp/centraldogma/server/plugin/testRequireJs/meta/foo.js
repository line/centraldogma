define(['qux/bar', 'util/util'], function (bar, util) {
  return {
    hello: bar.greet,
    loadCount: util.counter
  };
});
