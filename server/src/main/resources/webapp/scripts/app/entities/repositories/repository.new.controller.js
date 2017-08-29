'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryNewController',
                function ($scope, $state, $stateParams, RepositoryService, NotificationUtil) {
                  $scope.project = {
                    name: $stateParams.projectName
                  };

                  $scope.createRepository = function () {
                    RepositoryService.createRepository($scope.project.name, $scope.repository.name).then(
                        function () {
                          NotificationUtil.success('entities.created_repository',
                                                   {
                                                     projectName: $scope.project.name,
                                                     repositoryName: $scope.repository.name
                                                   });
                          $scope.back();
                        }
                    );
                  };
                });
