'use strict';

angular.module('CentralDogmaAdmin')
        .factory('SettingsService',
  function (ApiService) {
    return {
      listTokens: function () {
        return ApiService.get('tokens');
      },

      createToken: function (data) {
        return ApiService.post('tokens', data, {
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
          }
        });
      },

      deleteToken: function (id) {
        return ApiService.delete('tokens/' + id);
      }
    };
  });
