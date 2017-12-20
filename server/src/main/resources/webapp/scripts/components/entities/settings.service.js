'use strict';

angular.module('CentralDogmaAdmin')
        .factory('SettingsService',
  function (ApiV1Service, EntitiesUtil, $q) {
    return {
      listTokens: function () {
        var defer = $q.defer();
        ApiV1Service.get("tokens").then(function (tokens) {
          var i;
          if (angular.isArray(tokens)) {
            for (i in tokens) {
              tokens[i].creationTimeStr = moment(tokens[i].creation.timestamp).fromNow();
              tokens[i].isActive = !angular.isDefined(tokens[i].deactivation);
            }
            defer.resolve(tokens);
          } else {
            defer.resolve([]);
          }
        }, function (error) {
          defer.reject(error);
        });
        return defer.promise;
      },

      createToken: function (data) {
        return ApiV1Service.post('tokens', data, {
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
          }
        });
      },

      activateToken: function (id) {
        return ApiV1Service.jsonPatch('tokens/' + id,
          EntitiesUtil.toReplaceJsonPatch('/status', 'active'));
        },

      deactivateToken: function (id) {
        return ApiV1Service.jsonPatch('tokens/' + id,
          EntitiesUtil.toReplaceJsonPatch('/status', 'inactive'));
      },

      deleteToken: function (id) {
        return ApiV1Service.delete('tokens/' + id);
      }
    };
  });
