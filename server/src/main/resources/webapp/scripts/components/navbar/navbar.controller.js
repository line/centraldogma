'use strict';

angular.module('CentralDogmaAdmin')
    .controller('NavbarController',
                function ($scope, $rootScope, $state, $q, $uibModal,
                          Hostname, Auth, Principal, NotificationUtil) {

                  $scope.isAuthenticated = Principal.isAuthenticated;

                  Hostname.get().then(function (data) {
                    $scope.hostname = data;
                  });

                  $scope.logout = function () {
                    Auth.logout();
                    $state.go('home');
                  };
                });
