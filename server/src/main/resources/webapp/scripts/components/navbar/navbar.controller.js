'use strict';

angular.module('CentralDogmaAdmin')
    .controller('NavbarController',
                function ($scope, $rootScope, $state, $q, $uibModal, $window,
                          Hostname, Auth, Principal) {

                  $scope.isAuthenticated = Principal.isAuthenticated;

                  Auth.isEnabled().then(function (data) {
                    $scope.isSecurityEnabled = data;

                    if (!$scope.isSecurityEnabled) {
                      $window.sessionStorage.setItem('sessionId', "anonymous");
                    }
                  });

                  Hostname.get().then(function (data) {
                    $scope.hostname = data;
                  });

                  $scope.logout = function () {
                    Auth.logout();
                    $state.go('home');
                  };
                });
