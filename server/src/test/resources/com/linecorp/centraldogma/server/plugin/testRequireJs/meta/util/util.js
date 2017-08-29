define([], function () {

  if (__UNSAFE__.globalCount) {
    // Should never reach here because require.js executes the same module only once.
    __UNSAFE__.globalCount++;
  } else {
    __UNSAFE__.globalCount = 1;
  }

  return {
    counter: function () {
      return __UNSAFE__.globalCount;
    },
    concat: function () {
      return [].join.call(arguments, '');
    }
  };
});
