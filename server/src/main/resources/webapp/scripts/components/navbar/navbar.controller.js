'use strict';

angular.module('CentralDogmaAdmin')
    .controller('NavbarController',
                function ($scope, $rootScope, $state, $q, $uibModal, $window,
                          Hostname, Principal) {

                  $scope.isAuthenticated = Principal.isAuthenticated;

                  Hostname.get().then(function (data) {
                    $scope.hostname = data.hostname;
                    $scope.title = angular.isDefined(data.title) ? data.title : "";
                  });
                });
