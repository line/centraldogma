'use strict';

angular.module('CentralDogmaAdmin')
        .factory('Security',
  function ($rootScope, $http, $q, NotificationUtil) {
    return {
      resolve: function () {
        var defer = $q.defer();
        if (this.isResolved()) {
          defer.resolve($rootScope.isSecurityEnabled);
        } else {
          $http.get('/security_enabled').then(function () {
            $rootScope.isSecurityEnabled = true;
            defer.resolve(true);
          }, function (error) {
            if (error.status === 404) {
              defer.resolve(false);
            } else {
              NotificationUtil.error(error);
              defer.reject(error);
            }
          });
        }
        return defer.promise;
      },

      isResolved: function () {
        return angular.isDefined($rootScope.isSecurityEnabled);
      }
    };
  });
