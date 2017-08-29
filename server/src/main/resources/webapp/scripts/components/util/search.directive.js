'use strict';

angular.module('CentralDogmaAdmin')
    .directive('search',
               function () {
                 return {
                   template: '<form class="form-inline"><div class="form-group">' +
                             '<div class="input-group"><div class="input-group-addon">' +
                             '<span class="glyphicon glyphicon-search"></span></div>' +
                             '<input type="text" class="form-control" ng-model="term" ' +
                             'placeholder="Search this repository" ng-keyUp="keyUp($event)">' +
                             '</a></div></div></form>',
                   restrict: 'E',
                   scope: {
                     project: '=',
                     repository: '=',
                     revision: '=',
                     term: '='
                   },
                   controller: function ($scope, $state) {
                     $scope.keyUp = function (event) {
                       if (event.keyCode === 13) { // return
                         $scope.search();
                       }
                     };

                     $scope.search = function () {
                       $state.go('repositorySearch', {
                         projectName: $scope.project,
                         repositoryName: $scope.repository,
                         revision: $scope.revision,
                         term: $scope.term
                       });
                     };
                   }
                 };
               });
