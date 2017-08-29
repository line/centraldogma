'use strict';

angular.module('CentralDogmaAdmin')
    .controller('ProjectController',
                function ($scope, $stateParams, RepositoryService) {
                  $scope.project = {
                    name: $stateParams.projectName
                  };

                  RepositoryService.listRepositories($scope.project.name).then(
                      function (repositories) {
                        $scope.repositories = repositories;
                      }
                  );
                });
