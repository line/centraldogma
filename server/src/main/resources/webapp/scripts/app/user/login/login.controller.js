'use strict';

angular.module('CentralDogmaAdmin')
    .controller('LoginController',
                function ($scope, $timeout, $uibModalInstance, Auth, NotificationUtil) {
                  $scope.rememberMe = true;

                  $scope.login = function () {
                    Auth.login({
                      username: $scope.username,
                      password: $scope.password,
                      rememberMe: $scope.rememberMe
                    }).then(function () {
                      $uibModalInstance.close({
                        translationId: 'login.logged_in',
                        interpolateParams: { username: $scope.username }
                      });
                    }, function (error) {
                      if (typeof error.status !== 'undefined' && error.status === 401) {
                        NotificationUtil.error('login.auth_failed');
                      } else {
                        NotificationUtil.error(error);
                      }
                    });
                  };

                  $scope.cancel = function () {
                    $uibModalInstance.dismiss('');
                  };

                  $timeout(function () {
                    angular.element('[ng-model="username"]').focus();
                  });
                });
