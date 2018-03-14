'use strict';

angular.module('CentralDogmaAdmin')
        .factory('EntitiesUtil',
  function (StringUtil) {
    return {
      toKeySet: function (map) {
        if (angular.isUndefined(map) || map === null) {
          return [];
        }
        return Object.keys(map);
      },

      toUniqueSet: function (sourceSet, filterSet) {
        if (angular.isUndefined(sourceSet) || sourceSet === null) {
          return [];
        }
        if (angular.isUndefined(filterSet) || filterSet === null || filterSet.length === 0) {
          return sourceSet;
        }
        return sourceSet.filter(function (value) {
          return filterSet.indexOf(value) === -1; // not exist
        });
      },

      toDateTimeStr: function (timestamp) {
        return moment(timestamp).fromNow();
      },

      toReplaceJsonPatch: function (path, value) {
        return [{
          op: 'replace',
          path: path,
          value: value
        }]
      },

      sanitizeEmail: function (email) {
        if (StringUtil.endsWith(email, '@localhost.localdomain')) {
          return email.split('@')[0];
        }
        return email;
      }
    };
  });
